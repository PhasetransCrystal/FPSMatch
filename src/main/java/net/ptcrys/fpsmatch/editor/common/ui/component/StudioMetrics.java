package net.ptcrys.fpsmatch.editor.common.ui.component;

/**
 * Logical measures for the 13px Studio design at the existing host text density.
 * The host may scale the whole composition; individual components never shrink text to fit.
 */
public record StudioMetrics(int row, int header, int gap, int padding, int radius, int treeIndent, int slotHeight, int fieldGap) {

    public static StudioMetrics compact() {
        return new StudioMetrics(20, 23, 4, 6, 3, 9, 52, 8);
    }

    public int fieldHeight(boolean toggle, boolean pending, boolean error) {
        return (toggle ? row + fieldGap : 13 + row + fieldGap) + (pending ? row + gap : 0) + (error ? 13 : 0);
    }
}
