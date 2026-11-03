package net.ptcrys.fpsmatch.editor.core.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSaveCapture;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.session.ResourceHandle;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/** Actual public-JAR consumer of opaque physical packs, never a weapon semantic producer. */
public final class ResourceDraftConsumer {
    private static final EditorId OWNER = EditorId.parse("example:physical_capsules");
    private static final EditorId TYPE = EditorId.parse("example:asset_use");
    private static final EditorId CODEC = EditorId.parse("example:asset_use_utf8");
    private static final EditorId IMPORTER = EditorId.parse("example:opaque_pack_snapshot");
    private static final EditorId ASSET = EditorId.parse("example:opaque_pack");
    private static final UUID DOCUMENT = id("document");
    private static final UUID FIRST_RESOURCE = id("resource-0");
    private static final UUID FIRST_VERSION = id("version-0");
    private static final UUID SECOND_VERSION = id("version-0-second");
    private static final TextContent EDIT = new TextContent.Message("example.resource.edit", List.of());
    private ResourceDraftConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("exercise")) {
            exercise(Path.of(args[1]), List.of(Path.of(args[2]), Path.of(args[3]), Path.of(args[4])));
        } else if (args.length == 3 && args[0].equals("reopen")) {
            reopen(Path.of(args[1]), Path.of(args[2]));
        } else throw new IllegalArgumentException("exercise OUT THREE_SNAPSHOTS | reopen SHARE NEW_DIRECTORY");
    }

    private static void exercise(Path output, List<Path> snapshots) throws Exception {
        Files.createDirectories(output);
        try (LocalProjectStore store = LocalProjectStore.create(output.resolve("project"))) {
            var initial = new ProjectState.Entry(DOCUMENT, OWNER.toString(), TYPE.toString(), CODEC.toString(),
                    1, "physical capsules", store.stage(BlobRef.Kind.DOCUMENT, "plain".getBytes(StandardCharsets.UTF_8)),
                    Set.of(), Set.of());
            store.commit(null, id("initial-request"), new ProjectState(id("project"), "Opaque snapshot consumer",
                    Map.of(OWNER.toString(), "1", "editor:resources", "1"), Map.of(DOCUMENT, initial), Map.of(), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.read(), registry(), 8)) {
                check(!session.protectedMode(), "initial project must be writable");
                ResourceHandle handle = session.resources().orElseThrow();
                ResourceCatalog imported = imported(store, snapshots);
                handle.edit(handle.view().token(), EDIT, ignored -> imported);
                check(handle.view().dirty(), "explicit admission must be dirty");
                var document = session.find(DOCUMENT, String.class).orElseThrow();
                document.edit(document.view().token(), EDIT, ignored -> "locked");
                reject("unsaved_resources", () -> session.captureSave(Set.of(DOCUMENT)));
                document.undo(document.view().token());
                handle.undo(handle.view().token());
                check(handle.view().content().resources().isEmpty(), "resource undo must restore empty catalog");
                handle.redo(handle.view().token());
                document.redo(document.view().token());
                enqueue(session, access, session.captureSave(Set.of(DOCUMENT), true));
                publishNext(session, access);
                check(!handle.view().dirty() && !document.hasUnpublishedChanges(), "captured scopes must confirm");

                document.edit(document.view().token(), EDIT, ignored -> "locked-edited");
                ProjectSaveCapture oldDocument = session.captureSave(Set.of(DOCUMENT));
                ResourceCatalog second = secondVersion(imported);
                handle.edit(handle.view().token(), EDIT, ignored -> second);
                enqueue(session, access, session.captureSave(Set.of(), true));
                enqueue(session, access, oldDocument);
                publishNext(session, access);
                publishNext(session, access);
                check(access.read().resources().orElseThrow().equals(second), "late document save must preserve current v2");

                ProjectSaveCapture oldResources = session.captureSave(Set.of(), true);
                ResourceCatalog renamed = renamed(second, "reviewed capsules");
                handle.edit(handle.view().token(), EDIT, ignored -> renamed);
                enqueue(session, access, session.captureSave(Set.of(), true));
                var late = access.prepare(oldResources);
                reject("stale_capture", () -> session.enqueueSave(late));
                publishNext(session, access);
                check(!handle.view().dirty(), "latest resource capture must confirm");
                check(document.view().content().equals("locked-edited"), "resource save must preserve the document");
                check(handle.view().content().versions().containsKey(FIRST_VERSION), "locked v1 must remain");
            }
            store.exportSnapshot(output.resolve("opaque-resources.zip"), store.captureSnapshot());
        }
        System.out.println("RESOURCE_DRAFT_EXERCISE_PASS; opaque physical data only; weapon closure/runtime unverified");
    }

    private static ResourceCatalog imported(LocalProjectStore store, List<Path> sources) throws IOException {
        Map<UUID, LogicalResource> resources = new TreeMap<>();
        Map<UUID, ResourceVersion> versions = new TreeMap<>();
        Map<UUID, ProjectState.Entry> entries = new TreeMap<>();
        for (int index = 0; index < sources.size(); index++) {
            UUID resource = id("resource-" + index);
            UUID version = id("version-" + index);
            UUID bytes = id("bytes-" + index);
            BlobRef blob;
            try (InputStream input = Files.newInputStream(sources.get(index))) {
                blob = store.stage(BlobRef.Kind.ASSET, input);
            }
            entries.put(bytes, new ProjectState.Entry(bytes, OWNER.toString(), ASSET.toString(),
                    "example:raw_physical_snapshot", 1, "physical capsule " + index, blob, Set.of(), Set.of()));
            resources.put(resource, new LogicalResource(resource, ASSET, "physical capsule " + index, version));
            versions.put(version, new ResourceVersion(version, resource, IMPORTER, "1", Map.of("format", "EDPACK01"),
                    bytes, Set.of(), Set.of()));
        }
        return new ResourceCatalog(resources, versions, entries);
    }

    private static ResourceCatalog secondVersion(ResourceCatalog old) {
        Map<UUID, ResourceVersion> versions = new TreeMap<>(old.versions());
        // A generic opaque resource can explicitly select different physical content. This is no
        // claim that the two source packs have compatible weapon identities or runtime semantics.
        versions.put(SECOND_VERSION, new ResourceVersion(SECOND_VERSION, FIRST_RESOURCE, IMPORTER, "1",
                Map.of("format", "EDPACK01", "selection", "explicit-second-physical-content"),
                id("bytes-1"), Set.of(), Set.of()));
        Map<UUID, LogicalResource> resources = new TreeMap<>(old.resources());
        var first = resources.get(FIRST_RESOURCE);
        resources.put(FIRST_RESOURCE, new LogicalResource(first.id(), first.type(), first.name(), SECOND_VERSION));
        return new ResourceCatalog(resources, versions, old.byteEntries());
    }

    private static ResourceCatalog renamed(ResourceCatalog old, String name) {
        Map<UUID, LogicalResource> resources = new TreeMap<>(old.resources());
        var first = resources.get(FIRST_RESOURCE);
        resources.put(FIRST_RESOURCE, new LogicalResource(first.id(), first.type(), name, first.currentVersion()));
        return new ResourceCatalog(resources, old.versions(), old.byteEntries());
    }

    private static void reopen(Path share, Path output) throws Exception {
        Files.createDirectories(output);
        LocalProjectStore.importSnapshot(share, output.resolve("project"));
        long files = 0;
        long bytes = 0;
        try (LocalProjectStore store = LocalProjectStore.open(output.resolve("project"))) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.read(), registry(), 8)) {
                check(!session.protectedMode(), "received project must be readable");
                ResourceCatalog catalog = session.resources().orElseThrow().view().content();
                check(catalog.resources().size() == 3 && catalog.versions().size() == 4, "all physical resource versions must arrive");
                check(catalog.resources().get(FIRST_RESOURCE).currentVersion().equals(SECOND_VERSION), "current v2 must arrive");
                check(catalog.versions().containsKey(FIRST_VERSION), "locked v1 must arrive");
                check(session.find(DOCUMENT, String.class).orElseThrow().view().content().equals("locked-edited"), "saved document must arrive");
                Path recovered = Files.createDirectory(output.resolve("physical"));
                for (var entry : catalog.byteEntries().values()) {
                    Path path = recovered.resolve(entry.id() + ".edpack");
                    try (OutputStream sink = Files.newOutputStream(path)) { store.copyContent(entry.content(), sink); }
                    try (var snapshot = PackSnapshots.open(path, () -> false)) {
                        for (var item : snapshot.index().entries()) {
                            snapshot.copyEntry(item.path(), OutputStream.nullOutputStream(), () -> false);
                            files++;
                            bytes += item.size();
                        }
                    }
                }
            }
        }
        check(files == 4064 && bytes == 439077198L, "complete real physical input must arrive");
        Files.writeString(output.resolve("reopen.txt"), "files=" + files + "\ncontentBytes=" + bytes + "\n", StandardCharsets.UTF_8);
        System.out.println("RESOURCE_DRAFT_FRESH_REOPEN_PASS files=" + files + " contentBytes=" + bytes);
    }

    private static ExtensionRegistry registry() {
        ApiVersion api = new ApiVersion(1, 0);
        var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1", api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(EditorId.parse("editor:resources"), "1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE),
                OWNER, Contribution.Side.COMMON, Set.of()), new UseType());
        builder.add(ResourceProducers.KIND, new Contribution(new ContributionKey(ResourceProducers.KIND.id(), IMPORTER),
                OWNER, Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(Set.of("1")));
        return builder.freeze();
    }

    private static final class UseType implements DocumentType<String> {
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String create() { return "plain"; }
        @Override public String decode(int schema, byte[] data) { return new String(data, StandardCharsets.UTF_8); }
        @Override public String snapshot(String value) { return value; }
        @Override public List<Problem> validate(String value) {
            if (!Set.of("plain", "locked", "locked-edited").contains(value)) throw new IllegalArgumentException("invalid sample value");
            return List.of();
        }
        @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public DocumentDependencies dependencies(String value) {
            return new DocumentDependencies(Set.of(), value.startsWith("locked")
                    ? Set.of(new ResourceRef(FIRST_RESOURCE, FIRST_VERSION)) : Set.of(), Set.of());
        }
    }

    private static void enqueue(ProjectSession session, LocalProjectAccess access, ProjectSaveCapture capture) throws IOException {
        session.enqueueSave(access.prepare(capture));
    }
    private static void publishNext(ProjectSession session, LocalProjectAccess access) {
        var dispatch = session.dispatchSave().orElseThrow();
        check(dispatch.job().isPresent(), "save dispatch must be accepted: " + dispatch.problems());
        var job = dispatch.job().orElseThrow();
        SaveResult result = access.publish(job);
        check(result.outcome() == SaveResult.Outcome.CONFIRMED, "save must confirm: " + result.outcome());
        check(session.completeSave(job, result), "owner must accept the exact job");
    }
    private static void reject(String suffix, Runnable action) {
        try { action.run(); } catch (SessionRejection expected) {
            check(expected.problems().get(0).code().toString().equals("editor:resource_draft/" + suffix), "unexpected rejection");
            return;
        }
        throw new IllegalStateException("expected rejection: " + suffix);
    }
    private static UUID id(String name) { return UUID.nameUUIDFromBytes(("Q43-" + name).getBytes(StandardCharsets.UTF_8)); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
