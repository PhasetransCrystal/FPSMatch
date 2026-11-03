package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Icon;
import org.junit.jupiter.api.Test;

class StudioIconTest {
    @Test void everyVisibleIconFitsTheExistingLogicalSquare() {
        assertEquals(12, StudioIcon.SIZE);
        assertEquals(16, StudioIcon.ADVANCE);
        for (var icon : Icon.values()) {
            var glyph = StudioIcon.glyph(icon);
            assertNotNull(glyph, icon.name());
            if (icon != Icon.NONE) assertFalse(glyph.shapes().isEmpty(), icon.name());
            for (var shape : glyph.shapes()) {
                if(shape instanceof StudioIcon.Fill rectangle){
                    assertTrue(rectangle.x() >= 0 && rectangle.y() >= 0, icon.name());
                    assertTrue(rectangle.x() + rectangle.width() <= 12, icon.name());
                    assertTrue(rectangle.y() + rectangle.height() <= 12, icon.name());
                }else if(shape instanceof StudioIcon.Stroke stroke){
                    assertEquals(1,stroke.width());
                    for(var point:stroke.points()){
                        assertTrue(point.x()>=.5&&point.x()<=11.5,icon.name());
                        assertTrue(point.y()>=.5&&point.y()<=11.5,icon.name());
                    }
                }
            }
        }
    }

    @Test void actionsWithDifferentMeaningsHaveDifferentPaintedShapes() {
        var pairs = List.of(
                List.of(Icon.SAVE, Icon.MAP), List.of(Icon.MAXIMIZE, Icon.RESTORE),
                List.of(Icon.COPY, Icon.PASTE), List.of(Icon.SLIDERS, Icon.SETTINGS),
                List.of(Icon.SUN, Icon.MOON), List.of(Icon.MOON, Icon.CLOCK),
                List.of(Icon.LAYOUT, Icon.SETTINGS), List.of(Icon.LAYOUT, Icon.SLIDERS),
                List.of(Icon.GRID, Icon.RESOURCES), List.of(Icon.GRID, Icon.BOX),
                List.of(Icon.RESOURCES, Icon.BOX), List.of(Icon.DELETE, Icon.CLOSE),
                List.of(Icon.DELETE, Icon.ERASER), List.of(Icon.DELETE, Icon.MINUS),
                List.of(Icon.MINUS, Icon.CLOSE), List.of(Icon.MINUS, Icon.ERASER),
                List.of(Icon.ERASER, Icon.CLEAR),
                List.of(Icon.MINUS, Icon.PLUS));
        for (var pair : pairs)
            assertNotEquals(StudioIcon.glyph(pair.get(0)), StudioIcon.glyph(pair.get(1)), pair.toString());
    }

    @Test void nonePaintsNothingAndPrecomputedGeometryCannotBeMutated() {
        assertTrue(StudioIcon.glyph(Icon.NONE).shapes().isEmpty());
        var glyph = StudioIcon.glyph(Icon.SAVE);
        assertSame(glyph, StudioIcon.glyph(Icon.SAVE));
        assertThrows(UnsupportedOperationException.class, () -> glyph.shapes().clear());
        var source = new java.util.ArrayList<>(glyph.shapes());
        var copy = new StudioIcon.Glyph(source);
        source.clear();
        assertFalse(copy.shapes().isEmpty());
    }

    @Test void diagonalsRemainContinuousAndStrokePointsAreImmutable(){
        var close=StudioIcon.glyph(Icon.CLOSE);assertEquals(2,close.shapes().size());
        var diagonal=(StudioIcon.Stroke)close.shapes().get(0);
        assertEquals(List.of(new StudioIcon.Point(3.5,3.5),new StudioIcon.Point(9.5,9.5)),diagonal.points());
        assertFalse(diagonal.closed());
        assertThrows(UnsupportedOperationException.class,()->diagonal.points().clear());
        assertThrows(IllegalArgumentException.class,()->new StudioIcon.Fill(11,0,2,1));
        assertThrows(IllegalArgumentException.class,()->new StudioIcon.Point(Double.NaN,0));
    }
}
