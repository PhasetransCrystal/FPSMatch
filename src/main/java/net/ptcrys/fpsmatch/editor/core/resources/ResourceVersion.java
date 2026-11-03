package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One immutable resource version. Versions are never edited in place: a change produces a new
 * version whose identity is independent of the logical resource it belongs to.
 *
 * <p>
 * {@code settings}, {@code dependencyVersions} and {@code outputEntries} are defensively copied
 * and immutably sorted, so caller-owned collections cannot leak into stored descriptions.
 */
public record ResourceVersion(UUID id, UUID resourceId, EditorId importer, String importerVersion,
                              Map<String, String> settings, UUID sourceEntry,
                              Set<UUID> dependencyVersions, Set<UUID> outputEntries) {

    public ResourceVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(importer, "importer");
        Objects.requireNonNull(importerVersion, "importerVersion");
        if (importerVersion.isBlank()) {
            throw ResourceSupport.invalid("importerVersion must not be blank");
        }
        settings = ResourceSupport.copySettings(settings);
        dependencyVersions = ResourceSupport.copyIds(dependencyVersions, "dependencyVersions");
        outputEntries = ResourceSupport.copyIds(outputEntries, "outputEntries");
    }
}
