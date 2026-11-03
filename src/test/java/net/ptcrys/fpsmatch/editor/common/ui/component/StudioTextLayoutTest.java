package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudioTextLayoutTest {
    private final StudioTextLayout text = new StudioTextLayout((value, size) -> value.codePointCount(0, value.length()) * 5);

    @Test void wrappingRespectsWordsHardBreaksAndLineMetrics() {
        var lines = text.wrap("alpha beta\n\ngamma\n", 25, BODY);
        assertEquals(List.of("alpha", "beta", "", "gamma", ""), lines.lines());
        assertEquals(25, lines.width());
        assertEquals(lines.lines().size() * text.lineHeight(BODY), lines.height());
        assertFalse(lines.overflow());
        assertThrows(UnsupportedOperationException.class, () -> lines.lines().add("later"));
    }

    @Test void narrowWordsAndEllipsisNeverSplitSurrogatePairs() {
        var lines = text.wrap("A😀B", 5, SMALL);
        assertEquals(List.of("A", "😀", "B"), lines.lines());
        assertEquals("A😀…", text.ellipsize("A😀BC", 15, SMALL));
        assertEquals("", text.ellipsize("A", 0, SMALL));
        assertTrue(text.wrap("😀", 1, SMALL).overflow());
    }

    @Test void zeroWidthRetainsContentAndNativeMetricsScaleExactlyOnce() {
        assertEquals(List.of("unfinished value"), text.wrap("unfinished value", 0, BODY).lines());
        assertTrue(text.wrap("unfinished value", 0, BODY).overflow());
        var scaled = StudioTextLayout.scaled(String::length, 9);
        assertEquals(8, scaled.width("123456789", SMALL));
        assertEquals(9, scaled.width("123456789", BODY));
        assertEquals(13, scaled.width("123456789", TITLE));
        assertThrows(IllegalArgumentException.class, () -> text.wrap("x", -1, BODY));
    }
}
