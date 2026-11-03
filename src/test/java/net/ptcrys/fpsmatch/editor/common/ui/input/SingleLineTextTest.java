package net.ptcrys.fpsmatch.editor.common.ui.input;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SingleLineTextTest {
    @Test void movesAndDeletesWholeCodePointsAndClampsInsideSurrogates() {
        var text = new SingleLineText("a😀b");
        text.move(-1, false, false);
        assertEquals(3, text.caret());
        text.move(-1, true, false);
        assertEquals("😀", text.selected());
        assertTrue(text.backspace());
        assertEquals("ab", text.text());
        assertTrue(text.undo());
        assertEquals("😀", text.selected());
        text.select(2, false);
        assertEquals(1, text.caret());
        assertTrue(text.deleteForward());
        assertEquals("ab", text.text());
        assertTrue(text.undo());
        text.select(3, false);
        assertTrue(text.backspace());
        assertEquals("ab", text.text());
    }

    @Test void replacementAndUndoRestoreSelectionAndRedoRestoresEdit() {
        var text = new SingleLineText("abcdef");
        text.select(5, false);
        text.select(2, true);
        assertEquals(2, text.start());
        assertEquals(5, text.end());
        assertTrue(text.insert("😀"));
        assertEquals("ab😀f", text.text());
        assertEquals(4, text.caret());
        assertEquals(text.caret(), text.anchor());
        assertTrue(text.undo());
        assertEquals("abcdef", text.text());
        assertEquals(2, text.caret());
        assertEquals(5, text.anchor());
        assertTrue(text.redo());
        assertEquals("ab😀f", text.text());
        assertTrue(text.undo());
        assertTrue(text.insert("x"));
        assertFalse(text.redo());
    }

    @Test void unchangedSetPreservesSelectionAndHistoryAndChangedSetClampsEachEndpoint() {
        var text = new SingleLineText("abcdef");
        assertTrue(text.insert("g"));
        text.select(5, false);
        text.select(2, true);
        text.set("abcdefg");
        assertEquals(2, text.caret());
        assertEquals(5, text.anchor());
        assertTrue(text.undo());
        assertEquals("abcdef", text.text());
        text.select(4, false);
        text.select(2, true);
        text.set("x😀");
        assertEquals(1, text.caret());
        assertEquals(3, text.anchor());
        assertEquals("😀", text.selected());
        assertFalse(text.undo());
        assertFalse(text.redo());
    }

    @Test void rejectsControlsLineSeparatorsAndMalformedSurrogatesWithoutErasingSelection() {
        var text = new SingleLineText("a\n\r\t\u0000\u007f\u0085\u2028\u2029\ud800b\udc00😀");
        assertEquals("ab😀", text.text());
        text.selectAll();
        assertFalse(text.insert("\n\t\ud800"));
        assertEquals("ab😀", text.selected());
        assertFalse(text.insert(""));
        assertFalse(text.undo());
    }

    @Test void oversizedOperationsAreRejectedWholeAndReplacementUsesFreedCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new SingleLineText("a".repeat(4095) + "😀z"));
        var text = new SingleLineText("a".repeat(4095));
        assertEquals(4095, text.text().length());
        assertFalse(text.insert("😀"));
        assertTrue(text.insert("z"));
        assertEquals(4096, text.text().length());
        text.select(4094, false);
        text.end(true);
        var before = text.snapshot();
        assertFalse(text.insert("😀z"));
        assertEquals(before, text.snapshot());
        assertTrue(text.insert("😀"));
        assertEquals(4096, text.text().length());
        assertTrue(text.text().endsWith("😀"));
        before = text.snapshot();
        assertThrows(IllegalArgumentException.class, () -> text.set("😀".repeat(2049)));
        assertEquals(before, text.snapshot());
        assertEquals(4096, text.text().length());
        assertEquals(4095, text.text().codePointCount(0, text.text().length()));
    }

    @Test void rejectedFullSelectionPastePreservesRawSelectionUndoAndRedo() {
        var text = new SingleLineText("seed");assertTrue(text.insert("!"));
        text.selectAll();var before = text.snapshot();
        assertFalse(text.insert("x".repeat(SingleLineText.MAX_LENGTH + 1)));assertEquals(before,text.snapshot());
        assertTrue(text.undo());assertEquals("seed",text.text());
        assertTrue(text.redo());assertEquals("seed!",text.text());
        var frozen = text.snapshot();text.home(false);assertEquals(5,frozen.caret());
        assertEquals("seed!",frozen.text());assertNotEquals(frozen,text.snapshot());
    }

    @Test void directionalNavigationCollapsesSelectionAndWordMovesRemainAtBoundaries() {
        var text = new SingleLineText("one  two_3, 😀");
        text.home(false);
        text.move(1, true, true);
        assertEquals("one  ", text.selected());
        text.move(-1, false, false);
        assertEquals(0, text.caret());
        text.end(false);
        text.move(-1, false, true);
        assertEquals(12, text.caret());
        text.move(-1, false, true);
        assertEquals(10, text.caret());
        text.move(-1, false, true);
        assertEquals(5, text.caret());
        text.selectAll();
        text.move(1, false, false);
        assertEquals(text.text().length(), text.caret());
        assertEquals("", text.selected());
        text.move(0, true, true);
        assertEquals(text.text().length(), text.caret());
    }

    @Test void boundsAndNoOpsDoNotCreateHistoryAndHistoryRetainsOnlyOneHundredEdits() {
        var text = new SingleLineText("");
        assertFalse(text.backspace());
        assertFalse(text.deleteForward());
        assertFalse(text.undo());
        assertFalse(text.redo());
        text.select(Integer.MIN_VALUE, false);
        assertEquals(0, text.caret());
        for (int i = 0; i < 101; i++) assertTrue(text.insert("x"));
        for (int i = 0; i < 100; i++) assertTrue(text.undo());
        assertEquals("x", text.text());
        assertFalse(text.undo());
        for (int i = 0; i < 100; i++) assertTrue(text.redo());
        assertEquals(101, text.text().length());
        assertFalse(text.redo());
        text.selectAll();
        assertFalse(text.insert(text.text()));
        assertEquals(text.text().length(), text.caret());
        assertTrue(text.undo());
        assertEquals(100, text.text().length());
        text.select(Integer.MAX_VALUE, false);
        assertEquals(100, text.caret());
    }
}
