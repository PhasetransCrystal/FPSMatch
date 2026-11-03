package net.ptcrys.fpsmatch.editor.api.registry;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A module offered to the registry.
 *
 * <p>
 * {@code requiredModules} drives availability and cycle detection. {@code optionalModules} is
 * recorded but a missing optional module never fails the spec.
 * </p>
 */
public record ModuleSpec(
                         EditorId id,
                         String buildVersion,
                         ApiVersion requiredApi,
                         Set<EditorId> requiredModules,
                         Set<EditorId> optionalModules) {

    public ModuleSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(buildVersion, "buildVersion");
        Objects.requireNonNull(requiredApi, "requiredApi");
        Objects.requireNonNull(requiredModules, "requiredModules");
        Objects.requireNonNull(optionalModules, "optionalModules");
        if (buildVersion.isBlank()) {
            throw new IllegalArgumentException("buildVersion must not be blank");
        }
        if (requiredModules.stream().anyMatch(Objects::isNull) || optionalModules.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("module dependencies must not contain null");
        }
        // The copies below are what make this record defensive against the caller's set.
        requiredModules = Set.copyOf(requiredModules);
        optionalModules = Set.copyOf(optionalModules);
    }

    /** Sorted unmodifiable copy; deterministic traversal without depending on insertion order. */
    static Set<EditorId> sorted(Set<EditorId> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }
}
