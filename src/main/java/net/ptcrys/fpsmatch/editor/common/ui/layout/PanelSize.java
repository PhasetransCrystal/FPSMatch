package net.ptcrys.fpsmatch.editor.common.ui.layout;

/**
 * The registered minimum size of one panel type. The solver only ever reads these values; it neither
 * invents a default nor clamps an illegal one.
 *
 * @param minWidth  minimum width, finite and at least {@code 0}
 * @param minHeight minimum height, finite and at least {@code 0}
 */
public record PanelSize(double minWidth, double minHeight) {

    public PanelSize {
        Bounds.nonNegative(minWidth, "minWidth");
        Bounds.nonNegative(minHeight, "minHeight");
    }
}
