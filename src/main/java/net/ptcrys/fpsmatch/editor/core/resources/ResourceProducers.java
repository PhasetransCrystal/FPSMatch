package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;

/**
 * The extension point resource producers are published under.
 *
 * <p>
 * This kind is declared by the resource module through the generic extension mechanism of
 * {@code editor-extensions}; it is deliberately not part of any hard-coded kind list inside that
 * module. The kind is the single registration dimension of {@code ResourceVersion.importer}: a
 * producer is resolved by looking the stored importer id up under this kind, never by guessing a kind
 * from the string shape of the id.
 * </p>
 *
 * <p>
 * The ids and owners of published producers live in the contribution metadata of the frozen
 * registry, so a consuming service reads them from that registry rather than from a second copy
 * inside the declaration: {@link ResourceProducerSpec} carries support information only and repeats
 * no identity.
 * </p>
 */
public final class ResourceProducers {

    /** The extension point id; a resource producer's contribution kind dimension is always this id. */
    private static final String KIND_ID = "editor:resource_producer";

    /** The one extension point under which resource producers are published. */
    public static final ExtensionKind<ResourceProducerSpec> KIND = new ExtensionKind<>(EditorId.parse(KIND_ID), ResourceProducerSpec.class);

    private ResourceProducers() {}
}
