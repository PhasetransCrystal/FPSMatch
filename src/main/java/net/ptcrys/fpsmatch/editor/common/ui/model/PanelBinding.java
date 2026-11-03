package net.ptcrys.fpsmatch.editor.common.ui.model;

import java.util.Objects;
import java.util.UUID;

/**
 * How one panel follows documents. {@code FOLLOW_ACTIVE} means "whatever the active document is"
 * and therefore carries no document identity; {@code PINNED} never drifts with the active context.
 *
 * @param mode           binding mode, never {@code null}
 * @param targetDocument pinned document, non-{@code null} exactly when {@code mode == PINNED}
 */
public record PanelBinding(Mode mode, UUID targetDocument) {

    /** Binding mode of a panel. */
    public enum Mode {
        /** The panel shows the active document of the workspace. */
        FOLLOW_ACTIVE,
        /** The panel keeps showing one fixed document. */
        PINNED
    }

    public PanelBinding {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.PINNED && targetDocument == null) {
            throw new IllegalArgumentException("PINNED binding requires a target document");
        }
        if (mode == Mode.FOLLOW_ACTIVE && targetDocument != null) {
            throw new IllegalArgumentException("FOLLOW_ACTIVE binding must not carry a target document");
        }
    }

    /** A binding that follows the workspace active document. */
    public static PanelBinding followActive() {
        return new PanelBinding(Mode.FOLLOW_ACTIVE, null);
    }

    /** A binding fixed to one document. */
    public static PanelBinding pinned(UUID documentId) {
        return new PanelBinding(Mode.PINNED, Objects.requireNonNull(documentId, "documentId"));
    }
}
