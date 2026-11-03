package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class InspectorLayoutTest {
    @Test void focusedLastRowStaysReachableAsInputAndValidationActionsGrow() {
        int[] heights = {46, 46, 46, 46};
        int first = InspectorLayout.reveal(heights, 0, 3, 150);
        assertEquals(1, first);
        heights[3] = 66;
        first = InspectorLayout.reveal(heights, first, 3, 150);
        assertEquals(2, first);
        assertEquals(first, InspectorLayout.reveal(heights, first, 3, 150));
        heights[3] = 80;
        first = InspectorLayout.reveal(heights, first, 3, 150);
        assertEquals(2, first);
        assertTrue(heights[2] + heights[3] <= 150, "focused field and its actions fit together");
        assertEquals(first, InspectorLayout.reveal(heights, first, 3, 150));
        assertEquals(3, InspectorLayout.reveal(heights, first, 3, 70));
        assertEquals(3, InspectorLayout.reveal(heights, 3, 3, 70));
    }

    @Test void stableAnchorCanMoveAboveViewportAndInvalidAnchorsPreserveClampedScroll() {
        int[] heights = {46, 66, 80, 46};
        assertEquals(1, InspectorLayout.reveal(heights, 3, 1, 150));
        assertEquals(0, InspectorLayout.reveal(heights, -20, -1, 150));
        assertEquals(3, InspectorLayout.reveal(heights, 20, 9, 150));
        assertEquals(0, InspectorLayout.reveal(new int[0], 20, 0, 150));
        assertEquals(2, InspectorLayout.reveal(new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, 46}, 0, 2, 100));
    }
}
