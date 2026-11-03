package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.input.SingleLineText;
import org.junit.jupiter.api.Test;

class StudioTextLineTest {
    private static StudioTextLayout text() {
        return new StudioTextLayout((value,size)->value.codePoints().map(cp->Character.getType(cp)==Character.NON_SPACING_MARK?0:5).sum());
    }

    @Test void unicodeCaretHitAndSelectionUseTheSameFrozenAdvances() {
        var line=text().line("A😀e\u0301B",BODY);
        assertEquals(20,line.width());assertEquals(1,line.boundary(2));assertEquals(5,line.x(2));
        assertEquals(1,line.hitTest(7.4));assertEquals(3,line.hitTest(7.5));
        assertEquals(5,line.hitTest(15));
        assertEquals(new StudioTextLine.Selection(1,5,5,10),line.selection(5,2));
        assertEquals(0,line.hitTest(-10));assertEquals(line.text().length(),line.hitTest(100));
        assertEquals(0,line.boundary(-20));assertEquals(line.text().length(),line.boundary(100));
        assertThrows(IllegalArgumentException.class,()->line.hitTest(Double.NaN));
    }

    @Test void horizontalRevealReclaimsSpaceAndSharesHitSelectionCoordinates() {
        var line=text().line("A😀e\u0301B",BODY);var viewport=line.reveal(0,line.text().length(),10);
        assertEquals(3,viewport.start());assertEquals(6,viewport.end());assertEquals(10,viewport.originX());
        assertEquals(10,viewport.x(6));assertEquals(3,viewport.hitTest(0));
        assertEquals("e\u0301B",viewport.visibleText());
        assertEquals(new StudioTextLine.Selection(0,6,0,10),viewport.selection(0,6));
        assertEquals(0,line.reveal(viewport.start(),6,100).start());
        assertEquals(0,line.reveal(viewport.start(),0,10).start());
        assertEquals(6,line.reveal(0,6,0).start());
        assertThrows(IllegalArgumentException.class,()->line.viewport(0,-1));
    }

    @Test void prefixRoundingIsMeasuredOnceRatherThanAccumulatedPerCharacter() {
        var measured=new AtomicInteger();var layout=StudioTextLayout.scaled(value->{measured.incrementAndGet();return value.length()*5;},9);
        var line=layout.line("abcd",SMALL);int calls=measured.get();
        assertEquals(18,line.width());assertEquals(9,line.x(2));
        for(int index=0;index<10;index++){var viewport=line.reveal(0,4,9);viewport.hitTest(4);viewport.selection(0,4);line.x(3);}
        assertEquals(calls,measured.get());assertSame(line,layout.line("abcd",SMALL));assertEquals(calls,measured.get());
    }

    @Test void emptyAndLongLinesRemainCompleteWithoutOwningEditingHistory() {
        var layout=text();var empty=layout.line("",BODY);
        assertEquals(0,empty.width());assertEquals(0,empty.hitTest(10));assertEquals("",empty.reveal(40,20,0).visibleText());
        var raw=new SingleLineText("old");raw.selectAll();raw.insert("draft😀");
        var before=layout.line(raw.text(),BODY);layout.generation(42);layout.clearCache();
        assertEquals("draft😀",before.text());assertEquals(0,before.generation());
        assertEquals(42,layout.line(raw.text(),BODY).generation());assertEquals("draft😀",raw.text());
        assertTrue(raw.undo());assertEquals("old",raw.text());assertTrue(raw.redo());assertEquals("draft😀",raw.text());
        String longValue="😀x".repeat(2048);var longLine=layout.line(longValue,BODY);
        assertEquals(longValue,longLine.text());assertEquals(20480,longLine.width());
        assertEquals(longValue.length(),longLine.reveal(0,longValue.length(),0).start());
    }
}
