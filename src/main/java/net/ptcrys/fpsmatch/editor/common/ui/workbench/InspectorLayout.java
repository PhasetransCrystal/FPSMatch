package net.ptcrys.fpsmatch.editor.common.ui.workbench;

/** Keeps an edited row and its validation/actions together when its height changes. */
public final class InspectorLayout {

    private InspectorLayout() {}

    public static int reveal(int[] heights, int first, int anchor, int available) {
        if (heights.length == 0) return 0;
        first = Math.max(0, Math.min(first, heights.length - 1));
        if (anchor < 0 || anchor >= heights.length) return first;
        if (anchor < first) return anchor;
        long total = 0;
        for (int i = first; i <= anchor; i++) total += heights[i];
        while (first < anchor && total > available) total -= heights[first++];
        return first;
    }
}
