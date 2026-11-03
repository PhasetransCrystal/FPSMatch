package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.Stream;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioStrokeTest {
    private static StudioCanvas.Segment line(double x0,double y0,double x1,double y1,double width){
        return new StudioCanvas.Segment(x0,y0,x1,y1,width,0x80123456,new Bounds(0,0,100,100));
    }
    @Test void rotatedStrokeHasConstantWidthAndOnePhysicalPixelFringe(){
        var mesh=StudioStroke.mesh(line(10.25,20.75,40.25,60.75,2),2);
        assertEquals(5,mesh.size());var core=mesh.get(0);var edge=mesh.get(1);
        assertEquals(1.5,Math.hypot(core.b().x()-core.c().x(),core.b().y()-core.c().y()),1e-12);
        assertEquals(0.5,Math.hypot(edge.a().x()-core.a().x(),edge.a().y()-core.a().y())/Math.sqrt(2),1e-12);
        assertEquals(0,edge.a().coverage());assertEquals(1,core.a().coverage());
        assertEquals(0.6,(core.b().x()-core.a().x())/49.5,1e-12);
        assertEquals(0.8,(core.b().y()-core.a().y())/49.5,1e-12);
    }
    @Test void subpixelStrokeRetainsCoverageInsteadOfRoundingAway(){
        var mesh=StudioStroke.mesh(line(10.125,20.25,20.125,20.25,0.25),2);
        assertEquals(0.5,mesh.get(0).a().coverage());
        assertEquals(20.25,mesh.get(0).a().y());
        assertEquals(0.375,mesh.get(1).a().y()-20.25);
    }
    @Test void shortAndZeroLengthStrokesAreFiniteAndBounded(){
        assertTrue(StudioStroke.mesh(line(1,1,1,1,1),1.5).isEmpty());
        var mesh=StudioStroke.mesh(line(1,1,1.01,1.02,0.1),1.5);
        mesh.stream().flatMap(f->Stream.of(f.a(),f.b(),f.c(),f.d())).forEach(v->{
            assertTrue(Double.isFinite(v.x())&&Double.isFinite(v.y()));
            assertTrue(v.coverage()>=0&&v.coverage()<=1);
        });
        assertThrows(IllegalArgumentException.class,()->StudioStroke.mesh(line(0,0,1,1,1),0));
    }
}
