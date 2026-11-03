package net.ptcrys.fpsmatch.editor.common.ui.layout;

/**
 * Converts pixel requests into ratios accepted by the dock-tree contract. Requested extents are
 * preferences; the solver still owns panel minimums and the final partition.
 */
public final class DockSizing {

    private DockSizing() {}

    /** Requests the first (left/top) child size, excluding the splitter from usable space. */
    public static double leading(double extent, double requested, double splitterWidth) {
        return ratio(extent, requested, splitterWidth, false);
    }

    /** Requests the second (right/bottom) child size, without subtracting into a negative request. */
    public static double trailing(double extent, double requested, double splitterWidth) {
        return ratio(extent, requested, splitterWidth, true);
    }

    private static double ratio(double extent, double requested, double splitterWidth, boolean trailing) {
        Bounds.nonNegative(extent, "extent");
        Bounds.nonNegative(requested, "requested extent");
        Bounds.nonNegative(splitterWidth, "splitter width");
        double usable = extent - Math.min(extent, splitterWidth);
        if (usable == 0) return 0.5;
        // Clamp the requested fraction before complementing it; finite inputs may divide to infinity.
        double fraction = Math.min(1, requested / usable);
        double first = trailing ? 1 - fraction : fraction;
        return Math.max(Limits.MIN_RATIO, Math.min(Limits.MAX_RATIO, first));
    }
}
