package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One user save intent: the changes that should be published on top of the latest confirmed
 * manifest.
 *
 * <p>
 * A delta never carries a baseline. The queue applies it to the newest confirmed state when the
 * request reaches the head of the line, so a queued intent can never roll back content that was
 * saved after the intent was expressed. Removals and upserts of one kind must be disjoint: the same
 * call cannot both delete and rewrite an identity.
 *
 * <p>
 * Values are copied into immutable sorted collections on construction, so a delta is safe to
 * share and its {@code equals} does not depend on iteration order. Invalid input is rejected here,
 * at the boundary where the application builds the intent, and never inside publication.
 */
public record ProjectDelta(
                           Optional<String> name,
                           Map<String, String> moduleAdditions,
                           Map<UUID, ProjectState.Entry> documents,
                           Set<UUID> removedDocuments,
                           Map<UUID, ProjectState.Entry> assets,
                           Set<UUID> removedAssets) {

    public ProjectDelta {
        name = immutableName(name);
        moduleAdditions = immutableModules(moduleAdditions);
        documents = immutableEntries(documents, BlobRef.Kind.DOCUMENT, "documents");
        assets = immutableEntries(assets, BlobRef.Kind.ASSET, "assets");
        removedDocuments = immutableIds(removedDocuments, "removedDocuments");
        removedAssets = immutableIds(removedAssets, "removedAssets");
        requireDisjoint(documents, removedDocuments, "documents");
        requireDisjoint(assets, removedAssets, "assets");
        int entries = documents.size() + assets.size() + removedDocuments.size() + removedAssets.size();
        if (entries > Budgets.MAX_ENTRIES) {
            throw new IllegalArgumentException("delta changes " + entries + " entries, above the " + Budgets.MAX_ENTRIES + " entry budget");
        }
    }

    private static Optional<String> immutableName(Optional<String> name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String value = name.get();
        requireText(value, "name");
        if (value.isBlank()) {
            throw new IllegalArgumentException("name must not be empty or blank when present");
        }
        return Optional.of(value);
    }

    private static Map<String, String> immutableModules(Map<String, String> additions) {
        TreeMap<String, String> copy = new TreeMap<>();
        Objects.requireNonNull(additions, "moduleAdditions").forEach((module, version) -> {
            requireId(module, "module id");
            requireText(version, "module version");
            if (version.isEmpty()) {
                throw new IllegalArgumentException("empty module version for " + module);
            }
            copy.put(module, version);
        });
        if (copy.size() > Budgets.MAX_MODULES) {
            throw new IllegalArgumentException("module additions exceed the " + Budgets.MAX_MODULES + " module budget");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<UUID, ProjectState.Entry> immutableEntries(
                                                                  Map<UUID, ProjectState.Entry> entries, BlobRef.Kind kind, String label) {
        TreeMap<UUID, ProjectState.Entry> copy = new TreeMap<>();
        Objects.requireNonNull(entries, label).forEach((id, entry) -> {
            Objects.requireNonNull(id, "entry id");
            Objects.requireNonNull(entry, "entry");
            if (!id.equals(entry.id()) || entry.content().kind() != kind) {
                throw new IllegalArgumentException("entry identity or content kind mismatch in " + label);
            }
            copy.put(id, entry);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Set<UUID> immutableIds(Set<UUID> ids, String label) {
        TreeSet<UUID> copy = new TreeSet<>();
        Objects.requireNonNull(ids, label).forEach(id -> copy.add(Objects.requireNonNull(id, label)));
        return Collections.unmodifiableSet(copy);
    }

    private static void requireDisjoint(Map<UUID, ?> upserts, Set<UUID> removals, String label) {
        for (UUID id : removals) {
            if (upserts.containsKey(id)) {
                throw new IllegalArgumentException(label + " upsert and remove overlap for " + id);
            }
        }
    }

    private static void requireId(String value, String label) {
        requireText(value, label);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
    }

    /** Mirrors the storage schema's metadata text rule: bounded length, no unpaired surrogate. */
    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length() > Budgets.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(label + " exceeds " + Budgets.MAX_TEXT_LENGTH + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException(label + " contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException(label + " contains an unpaired surrogate");
            }
        }
    }
}
