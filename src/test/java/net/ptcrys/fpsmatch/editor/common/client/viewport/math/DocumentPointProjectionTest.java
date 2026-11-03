package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.client.viewport.LogicalRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;
import org.junit.jupiter.api.Test;

class DocumentPointProjectionTest {
    private static final EditorId SOURCE = EditorId.parse("fixture:source");
    private static final EditorId TARGET = EditorId.parse("minecraft:overworld");
    private static final Map<EditorId, EditorId> MAPPING = Map.of(SOURCE, TARGET);
    // Vertical FOV 90 degrees, aspect 2, near=1, far=3. Exact binary coefficients.
    private static final FrameMatrix PERSPECTIVE = rows(new double[][] {
            {.5,0,0,0}, {0,1,0,0}, {0,0,-2,-3}, {0,0,-1,0}});

    @Test void translatedCameraAndNonuniformScaleProduceLogicalCenterWithoutDoubleScaling() {
        var scale = new ScaleTransform(2, 3, 10, 20, 800, 600);
        var layout = new ViewportLayout(7, new LogicalRect(15, 20, 100, 40), scale);
        var view = new DocumentPointProjection.View(new Position(10, 20, 30), MatrixMath.identity(), PERSPECTIVE, layout);
        // Camera-relative (2,1,-2) projects to NDC (.5,.5,.5), then pixel (190,110).
        var point = point("translated", 12, 21, 28);
        var marker = project(view, point).get(0);
        assertEquals(90, marker.x());
        assertEquals(30, marker.y());
        assertEquals(2, marker.depth());
        assertEquals(new BigDecimal("12.0"), point.x(), "rendering must not write converted coordinates");
        assertEquals(marker, DocumentPointProjection.hit(List.of(marker), 90, 30).orElseThrow());
    }

    @Test void actualRotatedViewControlsProjectionInsteadOfAssumingPlayerForward() {
        var rotation = rows(new double[][] {{0,0,1,0},{0,1,0,0},{-1,0,0,0},{0,0,0,1}});
        var base = view();
        var rotated = new DocumentPointProjection.View(new Position(10, 20, 30), rotation, PERSPECTIVE, base.layout());
        // World relative (2,0,0) is camera forward (0,0,-2) after the captured rotation.
        var marker = project(rotated, point("rotated", 12, 20, 30)).get(0);
        assertEquals(130, marker.x()); assertEquals(90, marker.y()); assertEquals(2, marker.depth());
        assertTrue(project(rotated, point("behind", 8, 20, 30)).isEmpty());
    }

    @Test void clipsBehindNearFarAndHalfOpenViewportEdgesWithoutClamping() {
        var points = List.of(point("behind",0,0,2), point("tooNear",0,0,-.5),
                point("tooFar",0,0,-4), point("outside",5,0,-2),
                point("left",-4,0,-2), point("right",4,0,-2),
                point("top",0,2,-2), point("bottom",0,-2,-2),
                point("near",0,0,-1), point("far",0,0,-3));
        var markers = DocumentPointProjection.project(view(), points, MAPPING, TARGET.toString(), null);
        assertEquals(java.util.Set.of("left","top","near","far"),
                markers.stream().map(DocumentPointProjection.Marker::node).collect(java.util.stream.Collectors.toSet()));
        assertEquals(30, markers.stream().filter(m -> m.node().equals("left")).findFirst().orElseThrow().x());
        assertEquals(40, markers.stream().filter(m -> m.node().equals("top")).findFirst().orElseThrow().y());
    }

    @Test void roundingAndHitTestingShareOneLogicalCenter() {
        // Pixel X=130.49 and Y=90.49 round once to the displayed center (130,90).
        var marker = project(view(), point("rounded", .0196, -.0196, -2)).get(0);
        assertEquals(130, marker.x()); assertEquals(90, marker.y());
        assertTrue(DocumentPointProjection.hit(List.of(marker), 138, 90).isPresent());
        assertTrue(DocumentPointProjection.hit(List.of(marker), Math.nextUp(138.0), 90).isEmpty());
        assertTrue(DocumentPointProjection.hit(List.of(marker), 136, 96).isEmpty(), "radius is circular, not a square");
        assertTrue(project(view(), point("roundOutside", 3.99, 0, -2)).isEmpty(),
                "rounding onto the excluded right edge must not create an invisible clickable marker");
    }

    @Test void onlyExplicitlyMappedCurrentDimensionIsProjectedWithoutCoordinateConversion() {
        var other = new SpatialDocument.Point("other", EditorId.parse("fixture:other"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(-2), BigDecimal.ZERO, BigDecimal.ZERO);
        var points = List.of(point("mapped", 0, 0, -2), other);
        assertEquals(List.of("mapped"), DocumentPointProjection.project(view(), points, MAPPING, TARGET.toString(), null)
                .stream().map(DocumentPointProjection.Marker::node).toList());
        assertTrue(DocumentPointProjection.project(view(), points, Map.of(), TARGET.toString(), null).isEmpty());
        assertTrue(DocumentPointProjection.project(view(), points, MAPPING, "minecraft:the_nether", null).isEmpty());
    }

    @Test void duplicatePositionsRemainDistinctAndPickOrderIgnoresInputAndSelectionOrder() {
        var points = List.of(point("z",0,0,-2), point("a",0,0,-2), point("near",0,0,-1));
        var markers = DocumentPointProjection.project(view(), points, MAPPING, TARGET.toString(), "z");
        assertEquals(3, markers.size());
        assertEquals("z", markers.get(2).node(), "selection paints last");
        assertTrue(markers.get(2).selected());
        assertEquals("near", DocumentPointProjection.hit(markers,130,90).orElseThrow().node());
        var tied = markers.stream().filter(m -> !m.node().equals("near")).toList();
        assertEquals("a", DocumentPointProjection.hit(tied,130,90).orElseThrow().node());
        assertEquals("a", DocumentPointProjection.hit(List.of(tied.get(1),tied.get(0)),130,90).orElseThrow().node());
    }

    @Test void screenDistancePrecedesDepthAndRadiusStaysInLogicalPixels() {
        var closest = new DocumentPointProjection.Marker("closest",100,100,20,false);
        var nearerWorld = new DocumentPointProjection.Marker("nearerWorld",104,100,1,false);
        assertEquals(closest, DocumentPointProjection.hit(List.of(nearerWorld, closest),100,100).orElseThrow());
        assertTrue(DocumentPointProjection.hit(List.of(closest),108,100).isPresent());
        assertTrue(DocumentPointProjection.hit(List.of(closest),109,100).isEmpty());
        assertTrue(DocumentPointProjection.hit(List.of(closest),Double.NaN,100).isEmpty());
        assertTrue(DocumentPointProjection.hit(List.of(closest),Double.MAX_VALUE,100).isEmpty());
    }

    @Test void finiteInputsWithOverflowingArithmeticAreNotProjected() {
        var base = view();
        var hugeCamera = new DocumentPointProjection.View(new Position(-Double.MAX_VALUE,0,0),
                base.view(),base.projection(),base.layout());
        assertTrue(project(hugeCamera,point("overflowSubtract",Double.MAX_VALUE,0,-2)).isEmpty());
        var hugeView = rows(new double[][] {{Double.MAX_VALUE,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}});
        assertTrue(project(new DocumentPointProjection.View(new Position(0,0,0),hugeView,base.projection(),base.layout()),
                point("overflowMultiply",2,0,-2)).isEmpty());
        var tinyScale = new ScaleTransform(Double.MIN_VALUE,1,0,0,800,600);
        var unrepresentable = new ViewportLayout(0,new LogicalRect(0,0,Double.MAX_VALUE,100),tinyScale);
        assertTrue(project(new DocumentPointProjection.View(new Position(0,0,0),base.view(),base.projection(),unrepresentable),
                point("logicalOverflow",0,0,-2)).isEmpty());
    }

    @Test void identityDuplicatesAreRejectedEvenWhenOneIsOffscreenOrUnmapped() {
        var duplicate = new SpatialDocument.Point("same",EditorId.parse("fixture:unmapped"),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> DocumentPointProjection.project(view(),
                List.of(point("same",0,0,-2),duplicate),MAPPING,TARGET.toString(),null));
    }

    @Test void outputIsImmutableAndNoTerrainInputIsRequiredForXrayMarkers() {
        var markers = project(view(),point("insideSolidOrSky",0,0,-2));
        assertEquals(1,markers.size());
        assertThrows(UnsupportedOperationException.class, () -> markers.clear());
    }

    private static DocumentPointProjection.View view() {
        var layout = new ViewportLayout(1,new LogicalRect(30,40,200,100),new ScaleTransform(1,1,0,0,800,600));
        return new DocumentPointProjection.View(new Position(0,0,0),MatrixMath.identity(),PERSPECTIVE,layout);
    }
    private static List<DocumentPointProjection.Marker> project(DocumentPointProjection.View view, SpatialDocument.Point point) {
        return DocumentPointProjection.project(view,List.of(point),MAPPING,TARGET.toString(),null);
    }
    private static SpatialDocument.Point point(String node,double x,double y,double z) {
        return new SpatialDocument.Point(node,SOURCE,BigDecimal.valueOf(x),BigDecimal.valueOf(y),BigDecimal.valueOf(z),
                new BigDecimal("179.500"),new BigDecimal("-20.125"));
    }
    private static FrameMatrix rows(double[][] values) {
        double[] matrix = new double[16];
        for(int row=0;row<4;row++)for(int column=0;column<4;column++)matrix[column*4+row]=values[row][column];
        return new FrameMatrix(matrix);
    }
}
