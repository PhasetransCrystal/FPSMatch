package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.*;

import net.ptcrys.fpsmatch.editor.common.client.viewport.LogicalRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import org.junit.jupiter.api.Test;

class WorldSegmentProjectionTest {
    private static final FrameMatrix PERSPECTIVE = matrix(new double[][] {
            {.5,0,0,0},{0,1,0,0},{0,0,-2,-3},{0,0,-1,0}});

    @Test void visibleEndpointsUseActualCameraAndLogicalTransform() {
        var layout = new ViewportLayout(1,new LogicalRect(15,20,100,40),new ScaleTransform(2,3,10,20,800,600));
        var view = new DocumentPointProjection.View(new Position(10,20,30),MatrixMath.identity(),PERSPECTIVE,layout);
        var result = WorldSegmentProjection.project(view,new Position(10,20,28),new Position(12,21,28)).orElseThrow();
        assertFalse(result.startClipped()); assertFalse(result.endClipped());
        assertEquals(new WorldSegmentProjection.Segment(65,40,90,30),result.segment());
    }

    @Test void clipsBeforeDividingAnEndpointBehindTheCamera() {
        var result = WorldSegmentProjection.project(view(),new Position(0,0,-2),new Position(1,0,0)).orElseThrow();
        assertFalse(result.startClipped()); assertTrue(result.endClipped());
        assertEquals(new WorldSegmentProjection.Segment(110,70,135,70),result.segment());
        var reverse = WorldSegmentProjection.project(view(),new Position(1,0,0),new Position(0,0,-2)).orElseThrow();
        assertTrue(reverse.startClipped()); assertFalse(reverse.endClipped());
        assertEquals(new WorldSegmentProjection.Segment(135,70,110,70),reverse.segment());
    }

    @Test void crossingSegmentsSurviveWithBothEndpointsOutsideAllThreeAxes() {
        var horizontal = WorldSegmentProjection.project(view(),new Position(-8,0,-2),new Position(8,0,-2)).orElseThrow();
        assertTrue(horizontal.startClipped()); assertTrue(horizontal.endClipped());
        assertEquals(new WorldSegmentProjection.Segment(10,70,210,70),horizontal.segment());
        var vertical = WorldSegmentProjection.project(view(),new Position(0,-4,-2),new Position(0,4,-2)).orElseThrow();
        assertEquals(new WorldSegmentProjection.Segment(110,120,110,20),vertical.segment());
        var depth = WorldSegmentProjection.project(view(),new Position(0,0,-.5),new Position(0,0,-4)).orElseThrow();
        assertTrue(depth.startClipped()); assertTrue(depth.endClipped());
        assertEquals(new WorldSegmentProjection.Segment(110,70,110,70),depth.segment());
    }

    @Test void exactBoundaryIsAnOriginalEndpointButOutsideEndpointIsAlwaysFlagged() {
        var exact = WorldSegmentProjection.project(view(),new Position(0,0,-2),new Position(4,0,-2)).orElseThrow();
        assertFalse(exact.endClipped()); assertEquals(210,exact.segment().x1());
        var outside = WorldSegmentProjection.project(view(),new Position(0,0,-2),new Position(5,0,-2)).orElseThrow();
        assertTrue(outside.endClipped()); assertEquals(210,outside.segment().x1());
    }

    @Test void emptyOutsideAndNumericallyUnusableSegmentsProduceNoProjection() {
        assertTrue(WorldSegmentProjection.project(view(),new Position(5,0,-2),new Position(8,0,-2)).isEmpty());
        assertTrue(WorldSegmentProjection.project(view(),new Position(0,0,1),new Position(0,0,2)).isEmpty());
        assertTrue(WorldSegmentProjection.clip(view().layout(),new Vector4(0,0,0,0),new Vector4(0,0,0,0)).isEmpty());
        assertTrue(WorldSegmentProjection.clip(view().layout(),new Vector4(Double.MAX_VALUE,0,0,Double.MAX_VALUE),new Vector4(0,0,0,1)).isEmpty());
        var hugeCamera = new DocumentPointProjection.View(new Position(-Double.MAX_VALUE,0,0),MatrixMath.identity(),PERSPECTIVE,view().layout());
        assertTrue(WorldSegmentProjection.project(hugeCamera,new Position(Double.MAX_VALUE,0,-2),new Position(0,0,-2)).isEmpty());
        var empty = new ViewportLayout(0,new LogicalRect(0,0,0,10),new ScaleTransform(1,1,0,0,800,600));
        assertTrue(WorldSegmentProjection.clip(empty,new Vector4(0,0,0,1),new Vector4(1,0,0,1)).isEmpty());
    }

    private static DocumentPointProjection.View view() {
        return new DocumentPointProjection.View(new Position(0,0,0),MatrixMath.identity(),PERSPECTIVE,
                new ViewportLayout(0,new LogicalRect(10,20,200,100),new ScaleTransform(1,1,0,0,800,600)));
    }
    private static FrameMatrix matrix(double[][] rows) {
        double[] values = new double[16];
        for(int row=0;row<4;row++)for(int column=0;column<4;column++)values[column*4+row]=rows[row][column];
        return new FrameMatrix(values);
    }
}
