package net.ptcrys.fpsmatch.editor.common.text.java2d;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;

/**
 * Identity of one registered font face inside a {@link Java2dTextBackend}.
 *
 * <p>
 * Faces from an embedded font file use a namespaced identity derived from the caller supplied
 * family, with a {@code /face-N} suffix for the Nth font of the file. Logical JDK fonts use the
 * identity passed to {@link Java2dTextBackend#registerLogical}. The identity is intentionally not a
 * font file path: the backend never re-reads a path, and a face stays valid for the lifetime of the
 * backend that registered it.
 * </p>
 *
 * @param id stable editor identity of the face
 */
public record FontFaceId(EditorId id) {

    public FontFaceId {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
