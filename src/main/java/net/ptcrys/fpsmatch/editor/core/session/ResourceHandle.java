package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Owner-thread resource structure draft. Commands validate immutable candidates and contribution
 * availability before adopting the draft; content validation runs separately on a fixed save
 * capture through {@link LocalProjectAccess#prepareValidated}. Its independent history never
 * rewrites a document's locked resource references.
 */
public final class ResourceHandle {

    private static final long MAX_METADATA_BYTES = 32L * 1024 * 1024;
    private static final long MAX_HISTORY_BYTES = 64L * 1024 * 1024;
    private static final int MAX_IDENTITIES = 8192;
    private final SessionOwner owner;
    private final Consumer<ResourceCatalog> validator;
    private final int maxHistoryEntries;
    private ResourceCatalog current;
    private ResourceCatalog saved;
    private boolean dirty;
    private long revision;
    private long latestQueuedRevision = -1;
    private List<History> past = List.of();
    private List<History> future = List.of();
    private boolean historyTruncated;
    private Ledger ledger;

    ResourceHandle(SessionOwner owner, ResourceCatalog initial, int maxHistoryEntries,
                   Consumer<ResourceCatalog> validator) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.current = Objects.requireNonNull(initial, "initial");
        this.validator = Objects.requireNonNull(validator, "validator");
        if (maxHistoryEntries < 0) {
            throw new IllegalArgumentException("maxHistoryEntries must be non-negative");
        }
        this.maxHistoryEntries = Math.min(maxHistoryEntries, 128);
        // Opening diagnostics and protected mode belong to ProjectSession, not an edit callback.
        requireMetadataBudget(initial);
        this.ledger = extendLedger(new Ledger(Map.of(), Map.of(), Map.of()), initial);
        this.saved = initial;
    }

    public ResourceView view() {
        owner.requireReadable();
        return new ResourceView(token(), current, dirty, !past.isEmpty(),
                !future.isEmpty(), historyTruncated);
    }

    public Optional<TextContent> undoLabel() {
        owner.requireReadable();
        return past.isEmpty() ? Optional.empty() : Optional.of(past.get(past.size() - 1).label());
    }

    public Optional<TextContent> redoLabel() {
        owner.requireReadable();
        return future.isEmpty() ? Optional.empty() : Optional.of(future.get(future.size() - 1).label());
    }

    public void edit(ResourceToken expected, TextContent label,
                     UnaryOperator<ResourceCatalog> command) {
        owner.mutate(() -> {
            requireToken(expected);
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(command, "command");
            ResourceCatalog candidate = Objects.requireNonNull(command.apply(current), "candidate");
            requirePreserved(current.resources(), candidate.resources());
            requirePreserved(current.versions(), candidate.versions());
            requirePreserved(current.byteEntries(), candidate.byteEntries());
            requireIdentities(ledger, candidate);
            requireGraph(candidate);
            requireMetadataBudget(candidate);
            Ledger nextLedger = extendLedger(ledger, candidate);
            validator.accept(candidate);
            if (candidate.equals(current)) return null;

            long nextRevision = Math.incrementExact(revision);
            boolean nextDirty = !candidate.equals(saved);
            List<History> nextPast = new ArrayList<>(past);
            nextPast.add(new History(current, candidate, label,
                    metadataBytes(current) + metadataBytes(candidate)));
            long historyBytes = 0;
            for (History entry : nextPast) historyBytes += entry.metadataBytes();
            boolean truncated = historyTruncated;
            while (nextPast.size() > maxHistoryEntries || historyBytes > MAX_HISTORY_BYTES) {
                historyBytes -= nextPast.remove(0).metadataBytes();
                truncated = true;
            }
            List<History> retainedPast = List.copyOf(nextPast);
            // No callbacks or fallible planning remain beyond this publication boundary.
            ledger = nextLedger;
            past = retainedPast;
            future = List.of();
            historyTruncated = truncated;
            current = candidate;
            dirty = nextDirty;
            revision = nextRevision;
            return null;
        });
    }

    public void undo(ResourceToken expected) {
        moveHistory(expected, true);
    }

    public void redo(ResourceToken expected) {
        moveHistory(expected, false);
    }

    private void moveHistory(ResourceToken expected, boolean undo) {
        owner.mutate(() -> {
            requireToken(expected);
            List<History> from = undo ? past : future;
            if (from.isEmpty()) {
                throw undo ? ResourceMessages.nothingToUndo() : ResourceMessages.nothingToRedo();
            }
            History entry = from.get(from.size() - 1);
            ResourceCatalog candidate = undo ? entry.before() : entry.after();
            requireIdentities(ledger, candidate);
            requireGraph(candidate);
            requireMetadataBudget(candidate);
            validator.accept(candidate);
            long nextRevision = Math.incrementExact(revision);
            boolean nextDirty = !candidate.equals(saved);
            List<History> remaining = List.copyOf(from.subList(0, from.size() - 1));
            List<History> destination = new ArrayList<>(undo ? future : past);
            destination.add(entry);
            List<History> retainedDestination = List.copyOf(destination);
            past = undo ? remaining : retainedDestination;
            future = undo ? retainedDestination : remaining;
            current = candidate;
            dirty = nextDirty;
            revision = nextRevision;
            return null;
        });
    }

    ResourceCatalog savedCatalog() {
        owner.requireReadable();
        return saved;
    }

    Set<UUID> knownAssetIds() {
        owner.requireReadable();
        Set<UUID> ids = new TreeSet<>(ledger.resources().keySet());
        ids.addAll(ledger.versions().keySet());
        ids.addAll(ledger.byteEntries().keySet());
        return Set.copyOf(ids);
    }

    ResourceSaveCapture capture() {
        owner.requireReadable();
        return new Capture(token(), current, metadataBytes(current));
    }

    private final class Capture extends ResourceSaveCapture {

        private final ResourceToken token;
        private final ResourceCatalog catalog;
        private final long bytes;

        private Capture(ResourceToken token, ResourceCatalog catalog, long bytes) {
            this.token = token;
            this.catalog = catalog;
            this.bytes = bytes;
        }

        @Override
        ResourceToken token() {
            return token;
        }

        @Override
        ResourceCatalog catalog() {
            return catalog;
        }

        @Override
        long metadataBytes() {
            return bytes;
        }

        @Override
        boolean canEnqueue() {
            owner.requireReadable();
            return token.revision() >= latestQueuedRevision;
        }

        @Override
        void queued() {
            owner.requireReadable();
            latestQueuedRevision = Math.max(latestQueuedRevision, token.revision());
        }

        @Override
        void confirm() {
            owner.requireReadable();
            boolean nextDirty = !current.equals(catalog);
            saved = catalog;
            dirty = nextDirty;
        }
    }

    private ResourceToken token() {
        return new ResourceToken(owner.id(), revision);
    }

    private void requireToken(ResourceToken expected) {
        Objects.requireNonNull(expected, "expected");
        if (!expected.sessionId().equals(owner.id()) || expected.revision() != revision) {
            throw ResourceMessages.staleToken(expected.sessionId(), expected.revision(), revision);
        }
    }

    private static void requireGraph(ResourceCatalog catalog) {
        try {
            ResourceStorageBridge.planAll(catalog);
        } catch (IllegalArgumentException failure) {
            throw ResourceMessages.invalidCatalog(failure);
        }
    }

    private static void requireMetadataBudget(ResourceCatalog catalog) {
        long bytes = metadataBytes(catalog);
        if (bytes > MAX_METADATA_BYTES) {
            throw ResourceMessages.metadataBudget(bytes, MAX_METADATA_BYTES);
        }
    }

    private static void requirePreserved(Map<UUID, ?> before, Map<UUID, ?> after) {
        for (UUID id : before.keySet()) {
            if (!after.containsKey(id)) throw ResourceMessages.removalUnsupported(id);
        }
    }

    private static void requireIdentities(Ledger known, ResourceCatalog catalog) {
        Set<UUID> ids = new TreeSet<>();
        for (LogicalResource resource : catalog.resources().values()) {
            UUID id = resource.id();
            if (!ids.add(id) || known.versions().containsKey(id) || known.byteEntries().containsKey(id) || (known.resources().containsKey(id) && !known.resources().get(id).equals(resource.type()))) {
                throw ResourceMessages.identityConflict(id);
            }
        }
        for (ResourceVersion version : catalog.versions().values()) {
            UUID id = version.id();
            if (!ids.add(id) || known.resources().containsKey(id) || known.byteEntries().containsKey(id) || (known.versions().containsKey(id) && !known.versions().get(id).equals(version))) {
                throw ResourceMessages.identityConflict(id);
            }
        }
        for (ProjectState.Entry entry : catalog.byteEntries().values()) {
            UUID id = entry.id();
            if (!ids.add(id) || known.resources().containsKey(id) || known.versions().containsKey(id) || (known.byteEntries().containsKey(id) && !known.byteEntries().get(id).equals(entry))) {
                throw ResourceMessages.identityConflict(id);
            }
        }
    }

    private static Ledger extendLedger(Ledger known, ResourceCatalog catalog) {
        requireIdentities(known, catalog);
        Map<UUID, EditorId> resources = new TreeMap<>(known.resources());
        catalog.resources().forEach((id, value) -> resources.put(id, value.type()));
        Map<UUID, ResourceVersion> versions = new TreeMap<>(known.versions());
        versions.putAll(catalog.versions());
        Map<UUID, ProjectState.Entry> entries = new TreeMap<>(known.byteEntries());
        entries.putAll(catalog.byteEntries());
        long bytes = 0;
        for (EditorId type : resources.values()) bytes += 512 + stringBytes(type.toString());
        for (ResourceVersion version : versions.values()) bytes += versionBytes(version);
        for (ProjectState.Entry entry : entries.values()) bytes += entryBytes(entry);
        long nodes = (long) resources.size() + versions.size() + entries.size();
        if (nodes > MAX_IDENTITIES || bytes > MAX_METADATA_BYTES) {
            throw ResourceMessages.identityBudget(nodes, MAX_IDENTITIES, bytes, MAX_METADATA_BYTES);
        }
        return new Ledger(Map.copyOf(resources), Map.copyOf(versions), Map.copyOf(entries));
    }

    /** Conservative metadata accounting only; never reads or retains asset bytes. */
    static long metadataBytes(ResourceCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        long bytes = 0;
        for (LogicalResource resource : catalog.resources().values()) {
            bytes += 512 + stringBytes(resource.type().toString()) + stringBytes(resource.name());
        }
        for (ResourceVersion version : catalog.versions().values()) bytes += versionBytes(version);
        for (ProjectState.Entry entry : catalog.byteEntries().values()) bytes += entryBytes(entry);
        return bytes;
    }

    private static long versionBytes(ResourceVersion version) {
        long bytes = 512 + stringBytes(version.importer().toString()) + stringBytes(version.importerVersion());
        for (Map.Entry<String, String> setting : version.settings().entrySet()) {
            bytes += 64 + stringBytes(setting.getKey()) + stringBytes(setting.getValue());
        }
        return bytes + 32L * (version.dependencyVersions().size() + (long) version.outputEntries().size());
    }

    private static long entryBytes(ProjectState.Entry entry) {
        return 512 + stringBytes(entry.ownerModule()) + stringBytes(entry.typeId()) + stringBytes(entry.codecId()) + stringBytes(entry.displayName()) + stringBytes(entry.content().sha256()) + 32L * (entry.documentDependencies().size() + (long) entry.assetDependencies().size());
    }

    private static long stringBytes(String value) {
        return 2L * value.length();
    }

    private record History(ResourceCatalog before, ResourceCatalog after, TextContent label,
                           long metadataBytes) {}

    private record Ledger(Map<UUID, EditorId> resources, Map<UUID, ResourceVersion> versions,
                          Map<UUID, ProjectState.Entry> byteEntries) {}
}
