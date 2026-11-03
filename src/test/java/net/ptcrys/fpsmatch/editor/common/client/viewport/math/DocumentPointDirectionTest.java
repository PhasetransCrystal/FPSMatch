package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.client.viewport.LogicalRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;
import org.junit.jupiter.api.Test;

class DocumentPointDirectionTest {
    private static final EditorId DIMENSION = EditorId.parse("fixture:world");
    // near=1, far=9; all coefficients are exact binary values.
    private static final FrameMatrix PERSPECTIVE = matrix(new double[][] {
            {.5,0,0,0},{0,1,0,0},{0,0,-1.25,-2.25},{0,0,-1,0}});

    @Test void cardinalAndVerticalDirectionsFollowWorldAxes() {
        var right = glyph(view(),point("right",0,0,-4,1,0,0));
        var left = glyph(view(),point("left",0,0,-4,-1,0,0));
        var up = glyph(view(),point("up",0,0,-4,0,1,0));
        var down = glyph(view(),point("down",0,0,-4,0,-1,0));
        assertEquals(new WorldSegmentProjection.Segment(110,70,135,70),right.segments().get(0));
        assertEquals(new WorldSegmentProjection.Segment(110,70,85,70),left.segments().get(0));
        assertEquals(new WorldSegmentProjection.Segment(110,70,110,45),up.segments().get(0));
        assertEquals(new WorldSegmentProjection.Segment(110,70,110,95),down.segments().get(0));
        for(var value:List.of(right,left,up,down)) {
            assertEquals(DocumentPointProjection.Facing.NONE,value.facing()); assertEquals(3,value.segments().size());
        }
        assertEquals(DocumentPointProjection.Facing.TOWARD_CAMERA,glyph(view(),point("toward",0,0,-4,0,0,1)).facing());
        assertEquals(DocumentPointProjection.Facing.AWAY_FROM_CAMERA,glyph(view(),point("away",0,0,-4,0,0,-1)).facing());
    }

    @Test void radialBadgesUseTheActualOffAxisSightLine() {
        var away = glyph(view(),point("away",2,0,-4,2,0,-4));
        var toward = glyph(view(),point("toward",2,0,-4,-2,0,4));
        assertEquals(DocumentPointProjection.Facing.AWAY_FROM_CAMERA,away.facing()); assertTrue(away.segments().isEmpty());
        assertEquals(DocumentPointProjection.Facing.TOWARD_CAMERA,toward.facing()); assertTrue(toward.segments().isEmpty());
        var cameraAxis = glyph(view(),point("cameraAxis",2,0,-4,0,0,-1));
        assertEquals(DocumentPointProjection.Facing.NONE,cameraAxis.facing());
        assertTrue(cameraAxis.segments().get(0).x1()<cameraAxis.segments().get(0).x0());
        assertEquals(DocumentPointProjection.Facing.NONE,glyph(view(),point("almost",0,0,-4,.00001,0,-1)).facing());
    }

    @Test void capturedRotationAndNonuniformScaleDetermineDirection() {
        var rotation = matrix(new double[][] {{0,0,1,0},{0,1,0,0},{-1,0,0,0},{0,0,0,1}});
        var rotated = new DocumentPointProjection.View(new Position(10,20,30),rotation,PERSPECTIVE,view().layout());
        var value = glyph(rotated,point("rotated",14,20,30,0,0,1));
        assertEquals(new WorldSegmentProjection.Segment(110,70,135,70),value.segments().get(0));
        var layout = new ViewportLayout(1,new LogicalRect(15,20,100,40),new ScaleTransform(2,3,10,20,800,600));
        var scaled = new DocumentPointProjection.View(new Position(0,0,0),MatrixMath.identity(),PERSPECTIVE,layout);
        var arrow = glyph(scaled,point("scaled",0,0,-4,1,1,0));
        var shaft = arrow.segments().get(0); var wing = arrow.segments().get(1);
        assertEquals(65,shaft.x0()); assertEquals(40,shaft.y0());
        double ux = shaft.x1()-shaft.x0(), uy = shaft.y1()-shaft.y0(), length = Math.hypot(ux,uy);
        ux/=length; uy/=length;
        assertEquals(-6,(wing.x1()-wing.x0())*ux+(wing.y1()-wing.y0())*uy,1e-10);
        assertEquals(3,Math.abs((wing.x1()-wing.x0())*-uy+(wing.y1()-wing.y0())*ux),1e-10);
    }

    @Test void aClippedRealTipNeverGetsAReplacementArrowhead() {
        for(var point:List.of(point("near",0,0,-2,.6,0,.8),point("side",7,0,-4,1,0,0),point("far",0,0,-8,1,0,-1))) {
            var value = glyph(view(),point);
            assertEquals(DocumentPointProjection.Facing.NONE,value.facing());
            assertEquals(1,value.segments().size(),"only the clipped shaft may remain");
        }
        var near = glyph(view(),point("near",0,0,-2,.6,0,.8)).segments().get(0);
        assertEquals(110,near.x0()); assertEquals(147.5,near.x1(),1e-10);
    }

    @Test void distantSubpixelShaftKeepsItsBearingWithoutPretendingToBeRadial() {
        var infiniteFar = matrix(new double[][] {{.5,0,0,0},{0,1,0,0},{0,0,-1,-2},{0,0,-1,0}});
        var farView = new DocumentPointProjection.View(new Position(0,0,0),MatrixMath.identity(),infiniteFar,view().layout());
        var value = glyph(farView,point("tiny",0,0,-1e12,1,0,0));
        assertEquals(DocumentPointProjection.Facing.NONE,value.facing()); assertEquals(3,value.segments().size());
        var shaft = value.segments().get(0);
        assertTrue(shaft.x1()>shaft.x0()); assertTrue(shaft.x1()-shaft.x0()<1e-6);
        var wing = value.segments().get(1);
        assertEquals(-6,wing.x1()-wing.x0(),1e-10); assertEquals(3,Math.abs(wing.y1()-wing.y0()),1e-10);
    }

    @Test void shortWorldOffsetsAreAddedAfterSubtractingTheCamera() {
        var translated = new DocumentPointProjection.View(new Position(1e16,0,0),MatrixMath.identity(),PERSPECTIVE,view().layout());
        var point = point("largeOrigin",1e16,0,-4,1,0,1);
        var shaft = glyph(translated,point).segments().get(0);
        double delta = 2*point.direction().orElseThrow().x();
        assertEquals(110+50*delta/(4-delta),shaft.x1(),1e-10);
    }

    @Test void directionsDoNotChangeHitRadiusOrderingOrLegacyFallback() {
        var old = new SpatialDocument.Point("old",DIMENSION,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.valueOf(-4),BigDecimal.ZERO,BigDecimal.ZERO);
        var legacy = marker(view(),old); assertTrue(legacy.direction().isEmpty());
        assertTrue(new DocumentPointProjection.Marker("legacy",1,2,3,false).direction().isEmpty());
        var current = marker(view(),point("arrow",0,0,-4,1,0,0));
        assertTrue(DocumentPointProjection.hit(List.of(current),135,70).isEmpty(),"arrowhead is not a selection target");
        assertTrue(DocumentPointProjection.hit(List.of(current),118,70).isPresent());
        var points = List.of(point("z",0,0,-4,1,0,0),point("a",0,0,-4,0,1,0));
        var markers = DocumentPointProjection.project(view(),points,Map.of(DIMENSION,DIMENSION),DIMENSION.toString(),"z");
        assertEquals("z",markers.get(1).node()); assertEquals("a",DocumentPointProjection.hit(markers,110,70).orElseThrow().node());
        assertThrows(UnsupportedOperationException.class,()->current.direction().orElseThrow().segments().clear());
    }

    private static DocumentPointProjection.Glyph glyph(DocumentPointProjection.View view,SpatialDocument.Point point) {
        return marker(view,point).direction().orElseThrow();
    }
    private static DocumentPointProjection.Marker marker(DocumentPointProjection.View view,SpatialDocument.Point point) {
        return DocumentPointProjection.project(view,List.of(point),Map.of(DIMENSION,DIMENSION),DIMENSION.toString(),null).get(0);
    }
    private static SpatialDocument.Point point(String node,double x,double y,double z,double dx,double dy,double dz) {
        return new SpatialDocument.Point(node,DIMENSION,BigDecimal.valueOf(x),BigDecimal.valueOf(y),BigDecimal.valueOf(z),
                new BigDecimal("123.400"),new BigDecimal("-93.25"),Optional.of(new SpatialDocument.Direction(dx,dy,dz)));
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
