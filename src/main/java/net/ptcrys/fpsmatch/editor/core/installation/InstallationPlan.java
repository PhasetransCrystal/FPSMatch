package net.ptcrys.fpsmatch.editor.core.installation;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Fixed installation identity and provenance. This pure value grants no world or runtime authority. */
public record InstallationPlan(UUID installationId, Key key, UUID projectId, UUID commitId,
                               UUID documentId, EditorId adapterId, EditorId factoryId, String targetVersion,
                               Map<EditorId, String> modules, Map<EditorId, EditorId> dimensions) {

    public record Key(String type, String name) {

        public Key {
            namePart(type);
            namePart(name);
        }
    }

    public InstallationPlan {
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(factoryId, "factoryId");
        namePart(targetVersion);
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(dimensions, "dimensions");
        if (modules.isEmpty() || modules.size() > 256 || dimensions.isEmpty() || dimensions.size() > 64) {
            throw new IllegalArgumentException("Installation metadata budget exceeded");
        }
        TreeMap<EditorId, String> fixedModules = new TreeMap<>();
        modules.forEach((id, version) -> {
            Objects.requireNonNull(id, "module");
            namePart(version);
            fixedModules.put(id, version);
        });
        TreeMap<EditorId, EditorId> fixedDimensions = new TreeMap<>();
        dimensions.forEach((source, target) -> fixedDimensions.put(
                Objects.requireNonNull(source, "source dimension"), Objects.requireNonNull(target, "target dimension")));
        modules = Collections.unmodifiableMap(fixedModules);
        dimensions = Collections.unmodifiableMap(fixedDimensions);
    }

    private static void namePart(String value) {
        Objects.requireNonNull(value, "text");
        if (value.isEmpty() || value.length() > 128) throw new IllegalArgumentException("Invalid installation text");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || c == '/' || c == '\\') {
                throw new IllegalArgumentException("Invalid installation text");
            }
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("Invalid installation text");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid installation text");
        }
    }
}
