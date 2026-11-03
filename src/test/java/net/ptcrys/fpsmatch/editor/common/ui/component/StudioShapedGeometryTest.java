package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.BODY;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudioShapedGeometryTest {
    @Test void shapedGeometryOwnsHitCaretSelectionAndPixelRevealWithoutPrefixMeasurement() {
        var geometry=new StudioTextLayout.LineGeometry(){
            @Override public float advance(){return 30f;}
            @Override public float caretX(int index){return new float[]{0,20,10,30}[index];}
            @Override public int hitTest(float x){return x<5?0:x<15?2:x<25?1:3;}
            @Override public int boundary(int index){return Math.max(0,Math.min(3,index));}
            @Override public List<StudioTextLayout.Span> selectionSpans(int a,int b){return List.of(new StudioTextLayout.Span(0,5),new StudioTextLayout.Span(20,5));}
            @Override public long cacheWeight(){return 64;}
        };
        var layout=new StudioTextLayout(new StudioTextLayout.Metrics(){
            @Override public int width(String value,StudioComponent.TextSize size){throw new AssertionError("shaped geometry must not measure prefixes");}
            @Override public StudioTextLayout.LineGeometry geometry(String value,StudioComponent.TextSize size){return geometry;}
        });
        var line=layout.line("abc",BODY);assertEquals(30,line.width());assertEquals(20,line.x(1));assertEquals(10,line.x(2));
        assertEquals(2,line.hitTest(11));assertEquals(1,line.hitTest(21));
        assertEquals(List.of(new StudioTextLine.Selection(0,3,0,5),new StudioTextLine.Selection(0,3,20,5)),line.selections(0,3));
        var first=line.revealAt(0,1,12);assertEquals(8,first.originX());assertEquals(12,first.x(1));
        assertEquals(2,first.hitTest(3));assertEquals(8,line.revealAt(first.originX(),2,12).originX());
        assertEquals(0,line.revealAt(18,0,12).originX());assertEquals(0,line.revealAt(18,3,80).originX());
        var partial=line.viewAt(2,22);assertEquals(List.of(new StudioTextLine.Selection(0,3,0,3),new StudioTextLine.Selection(0,3,18,4)),partial.selections(0,3));
        assertSame(line,layout.line("abc",BODY));
    }
}
