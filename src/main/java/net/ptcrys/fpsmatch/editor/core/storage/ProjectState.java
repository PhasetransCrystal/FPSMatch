package net.ptcrys.fpsmatch.editor.core.storage;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Domain-neutral saved content. Callers must perform codec and domain validation before commit. */
public record ProjectState(UUID projectId, String name, Map<String, String> modules,
                           Map<UUID, Entry> documents, Map<UUID, Entry> assets, Origin origin) {

    public record Origin(UUID projectId, UUID commitId) {

        public Origin {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(commitId, "commitId");
        }
    }

    public record Entry(UUID id, String ownerModule, String typeId, String codecId,
                        int schemaVersion, String displayName, BlobRef content,
                        Set<UUID> documentDependencies, Set<UUID> assetDependencies) {

        public Entry {
            Objects.requireNonNull(id, "id");
            requireId(ownerModule);
            requireId(typeId);
            requireId(codecId);
            requireText(displayName);
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion must be positive");
            }
            Objects.requireNonNull(content, "content");
            documentDependencies = immutableSet(documentDependencies);
            assetDependencies = immutableSet(assetDependencies);
        }
    }

    public ProjectState {
        Objects.requireNonNull(projectId, "projectId");
        requireText(name);
        TreeMap<String, String> moduleCopy = new TreeMap<>();
        Objects.requireNonNull(modules, "modules").forEach((key, value) -> {
            requireId(key);
            requireText(value);
            if (value.isEmpty()) {
                throw new IllegalArgumentException("empty module version");
            }
            moduleCopy.put(key, value);
        });
        modules = Collections.unmodifiableMap(moduleCopy);
        documents = immutableEntries(documents, BlobRef.Kind.DOCUMENT);
        assets = immutableEntries(assets, BlobRef.Kind.ASSET);
    }

    public ProjectState withOrigin(Origin source) {
        return new ProjectState(projectId, name, modules, documents, assets, source);
    }

    private static Map<UUID, Entry> immutableEntries(Map<UUID, Entry> entries, BlobRef.Kind kind) {
        TreeMap<UUID, Entry> copy = new TreeMap<>();
        Objects.requireNonNull(entries, "entries").forEach((id, entry) -> {
            Objects.requireNonNull(id, "entry id");
            Objects.requireNonNull(entry, "entry");
            if (!id.equals(entry.id()) || entry.content().kind() != kind) {
                throw new IllegalArgumentException("entry identity or content kind mismatch");
            }
            copy.put(id, entry);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Set<UUID> immutableSet(Set<UUID> source) {
        TreeSet<UUID> copy = new TreeSet<>();
        copy.addAll(Objects.requireNonNull(source, "dependencies"));
        return Collections.unmodifiableSet(copy);
    }

    static void requireId(String id) {
        requireText(id);
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid namespaced id");
        }
    }

    static void requireText(String value) {
        Objects.requireNonNull(value, "text");
        if (value.length() > 4096) {
            throw new IllegalArgumentException("metadata string exceeds 4096 characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired surrogate");
            }
        }
    }
}
