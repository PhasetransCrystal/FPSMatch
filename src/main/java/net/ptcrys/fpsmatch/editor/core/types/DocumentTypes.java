package net.ptcrys.fpsmatch.editor.core.types;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;

/**
 * The extension point document types are published under.
 *
 * <p>
 * This kind is declared by the document module through the generic extension mechanism of
 * {@code editor-extensions}; it is deliberately not part of any hard-coded kind list inside that
 * module. Document types are registered as real implementations
 * ({@code ExtensionRegistryBuilder.add(DocumentTypes.KIND, metadata, implementation)}) and are read
 * back through {@code ExtensionRegistry.find(DocumentTypes.KIND, id)}.
 * </p>
 *
 * <p>
 * The ids and owners of published document types live in the contribution metadata, so a
 * consuming service reads them from the frozen registry rather than from a second copy inside the
 * implementation.
 * </p>
 */
public final class DocumentTypes {

    /** The extension point id; a document type's contribution kind dimension is always this id. */
    private static final String KIND_ID = "editor:document_type";

    /** The one extension point under which document types are published. */
    public static final ExtensionKind<DocumentType<?>> KIND = documentTypeKind();

    private DocumentTypes() {}

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ExtensionKind<DocumentType<?>> documentTypeKind() {
        // A kind whose value type is itself generic needs one erasure cast. It is confined to this
        // constant factory: no untyped Object is ever handed to document content.
        return (ExtensionKind<DocumentType<?>>) (ExtensionKind<?>) new ExtensionKind<>(
                EditorId.parse(KIND_ID), DocumentType.class);
    }
}
