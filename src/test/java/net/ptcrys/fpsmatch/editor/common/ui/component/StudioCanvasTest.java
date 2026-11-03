package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.BODY;
import java.util.List;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioCanvasTest {
    @Test void segmentsRetainFractionalGeometryClipAndPainterOrder(){
        var bounds=new Bounds(10,20,30,40);var canvas=new StudioCanvas(bounds);
        canvas.fill(10,20,12,22,0xff123456);
        canvas.segment(8.25,21.75,30.5,48.125,1.5,0x80123456);
        canvas.text("after",10,20,0xffffffff,BODY);
        canvas.segment(10,20,10,20,2,0xffffffff);
        canvas.segment(10,20,20,30,2,0);
        var picture=canvas.finish(false);assertEquals(3,picture.commands().size());
        assertEquals(new StudioCanvas.Segment(8.25,21.75,30.5,48.125,1.5,0x80123456,bounds),picture.commands().get(1));
        assertInstanceOf(StudioCanvas.Text.class,picture.commands().get(2));
        assertThrows(IllegalArgumentException.class,()->canvas.segment(Double.NaN,0,1,2,1,0xffffffff));
        assertThrows(IllegalArgumentException.class,()->canvas.segment(-Double.MAX_VALUE,0,Double.MAX_VALUE,2,1,0xffffffff));
        assertThrows(IllegalArgumentException.class,()->canvas.segment(0,0,1,2,0,0xffffffff));
    }
    @Test void iconCommandsPreserveFullSamplingBoundsAndPainterOrder(){
        var clip=new Bounds(12,21,5,6);var box=new Bounds(10,20,12,12);
        var canvas=new StudioCanvas(clip);
        canvas.text("before",10,20,0xffffffff,BODY);
        canvas.icon(StudioComponent.Icon.CLOSE,box,0xff123456);
        canvas.text("after",10,20,0xffffffff,BODY);
        canvas.icon(StudioComponent.Icon.NONE,box,0xffffffff);
        canvas.icon(StudioComponent.Icon.CLOSE,box,0);
        canvas.icon(StudioComponent.Icon.CLOSE,new Bounds(50,50,12,12),0xffffffff);
        var commands=canvas.finish(false).commands();assertEquals(3,commands.size());
        assertEquals(new StudioCanvas.IconGlyph(StudioComponent.Icon.CLOSE,box,0xff123456,clip),commands.get(1));
        assertEquals("before",((StudioCanvas.Text)commands.get(0)).value());
        assertEquals("after",((StudioCanvas.Text)commands.get(2)).value());
    }
    @Test void fillsNormalizeAndIntersectWhileEmptyOrTransparentGeometryIsOmitted() {
        var canvas=new StudioCanvas(new Bounds(10,20,30,40));
        canvas.fill(50,70,0,0,0xff123456);
        canvas.fill(12,24,18,29,0x80123456);
        canvas.fill(12,24,18,29,0x00123456);
        canvas.fill(15,25,15,30,0xffffffff);
        canvas.fill(0,0,5,5,0xffffffff);
        canvas.fill(40,20,50,30,0xffffffff);
        assertEquals(List.of(
                new StudioCanvas.Quad(new Bounds(10,20,30,40),0xff123456),
                new StudioCanvas.Quad(new Bounds(12,24,6,5),0x80123456)),canvas.finish(false).commands());
    }

    @Test void textClipsIntersectWithoutMovingTheOriginalBaseline() {
        var canvas=new StudioCanvas(new Bounds(10,20,30,40));
        canvas.text("original",-5,15,0xffffffff,BODY,new Bounds(15,10,40,30));
        canvas.text("whole",0,0,0xffffffff,BODY);
        canvas.text("",10,20,0xffffffff,BODY);
        canvas.text("outside",0,0,0xffffffff,BODY,new Bounds(50,50,2,2));
        assertEquals(List.of(
                new StudioCanvas.Text("original",-5,15,0xffffffff,BODY,new Bounds(15,20,25,20)),
                new StudioCanvas.Text("whole",0,0,0xffffffff,BODY,new Bounds(10,20,30,40))),canvas.finish(false).commands());
    }

    @Test void alternatingClipsAndSolidsKeepTheirExactSubmissionOrder() {
        var all=new Bounds(0,0,100,30);var a=new Bounds(0,0,20,30);var b=new Bounds(40,0,20,30);
        var canvas=new StudioCanvas(all);
        canvas.text("A1",0,0,0xffffffff,BODY,a);
        canvas.fill(0,0,100,30,0xff112233);
        canvas.text("B",40,0,0xffffffff,BODY,b);
        canvas.fill(5,5,10,10,0xff445566);
        canvas.text("A2",0,0,0xffffffff,BODY,a);
        assertEquals(List.of(
                new StudioCanvas.Text("A1",0,0,0xffffffff,BODY,a),
                new StudioCanvas.Quad(all,0xff112233),
                new StudioCanvas.Text("B",40,0,0xffffffff,BODY,b),
                new StudioCanvas.Quad(new Bounds(5,5,5,5),0xff445566),
                new StudioCanvas.Text("A2",0,0,0xffffffff,BODY,a)),canvas.finish(false).commands());
    }

    @Test void finishedPicturesAreImmutableSnapshotsWithTheirOwnTruncationAndWeight() {
        var canvas=new StudioCanvas(new Bounds(0,0,20,20));
        canvas.text("A😀",0,0,0xffffffff,BODY);var first=canvas.finish(true);
        canvas.fill(0,0,10,10,0xff112233);var second=canvas.finish(false);
        assertTrue(first.truncated());assertFalse(second.truncated());
        assertEquals(1,first.commands().size());assertEquals(2,second.commands().size());
        assertEquals(4,first.weight());assertEquals(5,second.weight());
        assertThrows(UnsupportedOperationException.class,()->first.commands().clear());
    }
}
