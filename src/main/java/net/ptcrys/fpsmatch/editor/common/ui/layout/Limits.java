package net.ptcrys.fpsmatch.editor.common.ui.layout;

/** Package-private structural limits of a dock tree. */
final class Limits {

    /** Maximum structural nodes per layout; every Empty, Leaf, Tabs and Split counts once. */
    static final int MAX_NODES = 4096;
    /** Maximum panels per layout, counting leaves and tab members alike. */
    static final int MAX_PANELS = 1024;
    /** Maximum panels inside one tab group. */
    static final int MAX_TAB_PANELS = 1024;
    /** Maximum tree depth, counting the root as depth 1. */
    static final int MAX_DEPTH = 64;
    /** Smallest accepted split ratio. */
    static final double MIN_RATIO = 0.05;
    /** Largest accepted split ratio. */
    static final double MAX_RATIO = 0.95;

    private Limits() {}
}
