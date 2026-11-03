package net.ptcrys.fpsmatch.editor.common.domain.map.legacy;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Fixed resource choices for one legacy import. A new selection requires a new context.
 * Structural validation does not authenticate stored bytes, importer availability or asset semantics.
 */
public final class LegacyItemContext {

    public static final int MAX_BINDINGS = 8192;

    private static final LegacyItemContext EMPTY = from(
            new ResourceCatalog(Map.of(), Map.of(), Map.of()), Map.of());

    private final Map<LegacyItemResourceKey, ResourceRef> bindings;
    private final Map<LegacyItemResourceKey, Binding> resolved;

    private LegacyItemContext(TreeMap<LegacyItemResourceKey, ResourceRef> bindings,
                              TreeMap<LegacyItemResourceKey, Binding> resolved) {
        this.bindings = Collections.unmodifiableMap(bindings);
        this.resolved = Collections.unmodifiableMap(resolved);
    }

    public static LegacyItemContext empty() {
        return EMPTY;
    }

    /**
     * Copies the choices and validates their catalog once, including when no choices are supplied.
     * The caller must not modify the input map concurrently with this call.
     */
    public static LegacyItemContext from(ResourceCatalog catalog,
                                         Map<LegacyItemResourceKey, ResourceRef> bindings) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(bindings, "bindings");
        int size = bindings.size();
        if (size < 0 || size > MAX_BINDINGS) {
            throw new IllegalArgumentException("bindings size must be between 0 and " + MAX_BINDINGS);
        }

        TreeMap<LegacyItemResourceKey, ResourceRef> copy = new TreeMap<>();
        for (Map.Entry<LegacyItemResourceKey, ResourceRef> entry : bindings.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "binding key"),
                    Objects.requireNonNull(entry.getValue(), "binding reference"));
        }
        ResourceStorageBridge.plan(catalog, new LinkedHashSet<>(copy.values()));

        TreeMap<LegacyItemResourceKey, Binding> resolved = new TreeMap<>();
        for (Map.Entry<LegacyItemResourceKey, ResourceRef> entry : copy.entrySet()) {
            ResourceRef reference = entry.getValue();
            LogicalResource resource = catalog.resources().get(reference.resourceId());
            ResourceVersion version = catalog.versions().get(reference.versionId());
            resolved.put(entry.getKey(), new Binding(reference, resource.type(),
                    version.importer(), version.importerVersion(), version.settings()));
        }
        return new LegacyItemContext(copy, resolved);
    }

    /** The immutable choices, iterated in provider, kind, then asset identity order. */
    public Map<LegacyItemResourceKey, ResourceRef> bindings() {
        return bindings;
    }

    public Optional<Binding> find(LegacyItemResourceKey key) {
        return Optional.ofNullable(resolved.get(Objects.requireNonNull(key, "key")));
    }

    /** Catalog-derived metadata for an explicitly selected, locked resource version. */
    public static final class Binding {

        private final ResourceRef reference;
        private final EditorId resourceType;
        private final EditorId importer;
        private final String importerVersion;
        private final Map<String, String> settings;

        private Binding(ResourceRef reference, EditorId resourceType,
                        EditorId importer, String importerVersion, Map<String, String> settings) {
            this.reference = reference;
            this.resourceType = resourceType;
            this.importer = importer;
            this.importerVersion = importerVersion;
            // ResourceVersion already owns this bounded, immutable map. Do not retain the catalog
            // or duplicate the map for every alias of the same selected version.
            this.settings = settings;
        }

        public ResourceRef reference() {
            return reference;
        }

        public EditorId resourceType() {
            return resourceType;
        }

        public EditorId importer() {
            return importer;
        }

        public String importerVersion() {
            return importerVersion;
        }

        /** Immutable settings of this selected version, never those of a later current version. */
        public Map<String, String> settings() {
            return settings;
        }
    }
}
