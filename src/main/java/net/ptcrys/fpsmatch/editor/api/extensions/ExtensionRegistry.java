package net.ptcrys.fpsmatch.editor.api.extensions;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.RegistrySnapshot;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A frozen set of usable extension contributions, each with its real implementation.
 *
 * <p>
 * Instances come only from {@link ExtensionRegistryBuilder#freeze()}; there is no public or
 * protected constructor. {@link #metadata()} exposes the registry's own frozen metadata, and the
 * implementation lookups expose only contributions the registry still considers usable: a missing
 * owner, an incompatible API, a failed or cyclic required dependency, a wrong side for this
 * environment and every record of a duplicate contribution are all absent here.
 * </p>
 *
 * <p>
 * A lookup never calls back into an implementation, so asking for an extension point can never
 * run third-party code. The returned collections are immutable and deterministically ordered by
 * {@link EditorId}. Immutability covers the collections, not the implementations: the objects
 * themselves are trusted extension references whose own state, threading and lifecycle belong to the
 * extension point contract, and this module promises neither a sandbox nor automatic disposal.
 * </p>
 */
public final class ExtensionRegistry {

    private final RegistrySnapshot metadata;
    private final Map<EditorId, ExtensionKind<?>> kinds;
    private final Map<ContributionKey, Object> implementations;

    ExtensionRegistry(RegistrySnapshot metadata, Map<EditorId, ExtensionKind<?>> kinds,
                      Map<ContributionKey, Object> implementations) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.kinds = Collections.unmodifiableMap(new TreeMap<>(kinds));
        this.implementations = Collections.unmodifiableMap(new TreeMap<>(implementations));
    }

    /** The frozen metadata: modules, usable contributions and every availability problem. */
    public RegistrySnapshot metadata() {
        return metadata;
    }

    /**
     * Whether this frozen registry defines the given extension point, even if it has no usable
     * contributions. This query never invokes an implementation.
     *
     * @return false when the kind id was never defined; otherwise true
     * @throws NullPointerException     if kind is null
     * @throws IllegalArgumentException if the id is defined for a different value type Class,
     *                                  including a same-named class from another class loader
     */
    public boolean defines(ExtensionKind<?> kind) {
        Objects.requireNonNull(kind, "kind");
        ExtensionKind<?> defined = kinds.get(kind.id());
        if (defined == null) {
            return false;
        }
        if (defined.valueType() != kind.valueType()) {
            throw new IllegalArgumentException("extension kind " + kind.id() + " is defined for " + defined.valueType().getName() + ", not " + kind.valueType().getName());
        }
        return true;
    }

    /**
     * The implementation published for one contribution, if the registry still considers it usable.
     *
     * @return the implementation, or an empty optional when the contribution exists in the metadata
     *         under this kind but is unavailable; an unknown contribution id is not an error, but an
     *         extension point that was never defined is one
     * @throws IllegalArgumentException if this kind was never defined, or its value type class is not
     *                                  the identical class the definition carried
     */
    public <T> Optional<T> find(ExtensionKind<T> kind, EditorId id) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        requireDefined(kind);
        Object implementation = implementations.get(new ContributionKey(kind.id(), id));
        if (implementation == null) {
            return Optional.empty();
        }
        return Optional.of(kind.valueType().cast(implementation));
    }

    /**
     * Every usable implementation of one extension point, ordered by contribution id.
     *
     * @throws IllegalArgumentException if this kind was never defined, or its value type class is not
     *                                  the identical class the definition carried
     */
    public <T> Map<EditorId, T> entries(ExtensionKind<T> kind) {
        Objects.requireNonNull(kind, "kind");
        requireDefined(kind);
        Map<EditorId, T> entries = new TreeMap<>();
        for (Map.Entry<ContributionKey, Object> item : implementations.entrySet()) {
            if (kind.id().equals(item.getKey().kind())) {
                entries.put(item.getKey().id(), kind.valueType().cast(item.getValue()));
            }
        }
        return Collections.unmodifiableMap(entries);
    }

    private void requireDefined(ExtensionKind<?> kind) {
        ExtensionKind<?> defined = kinds.get(kind.id());
        if (defined == null) {
            throw new IllegalArgumentException("extension kind is not defined: " + kind.id());
        }
        if (defined.valueType() != kind.valueType()) {
            throw new IllegalArgumentException("extension kind " + kind.id() + " is defined for " + defined.valueType().getName() + ", not " + kind.valueType().getName());
        }
    }
}
