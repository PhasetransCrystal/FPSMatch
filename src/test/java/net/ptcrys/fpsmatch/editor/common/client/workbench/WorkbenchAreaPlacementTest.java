package net.ptcrys.fpsmatch.editor.common.client.workbench;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchAreaPlacementTest {
    private static final EditorId DIMENSION = new EditorId("minecraft", "overworld");
    private static final SpatialDocument.AreaSemantics POSITION = new SpatialDocument.AreaSemantics(
            SpatialDocument.AreaCoordinates.INT32, SpatialDocument.AreaBoundary.ENDPOINT_PLANES,
            Set.of(SpatialDocument.AreaUse.POSITION_HALF_OPEN));

    @Test void cornerToolsKeepOrderedIdentityAndTheOtherCorner() {
        var source = area(p(9, 6, 3), p(-2, 1, -7));
        var target = p(10, 11, 12);
        var first = WorkbenchAreaPlacement.candidate(source, WorkbenchAreaPlacement.Mode.FIRST, target);
        assertSame(target, first.first()); assertSame(source.bounds().second(), first.second());
        var second = WorkbenchAreaPlacement.candidate(source, WorkbenchAreaPlacement.Mode.SECOND, target);
        assertSame(source.bounds().first(), second.first()); assertSame(target, second.second());
        assertEquals(p(9, 6, 3), source.bounds().first());
    }

    @Test void translatingReversedCornersPreservesSignedExtentAndAnchorsAtOriginalA() {
        var source = area(p(9, 6, 3), p(-2, 1, -7));
        var moved = WorkbenchAreaPlacement.candidate(source, WorkbenchAreaPlacement.Mode.TRANSLATE, p(-10, 12, 30));
        assertEquals(p(-10, 12, 30), moved.first());
        assertEquals(p(-21, 7, 20), moved.second());
    }

    @Test void integerOverflowIsRejectedWithoutWrappingOrClamping() {
        var source = area(p(0, 1, 2), p(Integer.MAX_VALUE, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> WorkbenchAreaPlacement.candidate(
                source, WorkbenchAreaPlacement.Mode.TRANSLATE, p(1, 1, 2)));
        assertEquals(Integer.MAX_VALUE, source.bounds().second().x().intValueExact());
    }

    @Test void fractionalIntegerCandidateIsRejectedButDecimalOwnersKeepExactTranslation() {
        var fraction = new SpatialDocument.Position(new BigDecimal("0.1234567890123456789"), BigDecimal.ONE, BigDecimal.TEN);
        var source = area(p(0, 1, 2), p(5, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> WorkbenchAreaPlacement.candidate(source, WorkbenchAreaPlacement.Mode.FIRST, fraction));
        var decimal = new SpatialDocument.Area(source.node(), DIMENSION, source.bounds(), new SpatialDocument.AreaSemantics(
                SpatialDocument.AreaCoordinates.DECIMAL, SpatialDocument.AreaBoundary.ENDPOINT_PLANES, POSITION.uses()));
        var result = WorkbenchAreaPlacement.candidate(decimal, WorkbenchAreaPlacement.Mode.TRANSLATE, fraction);
        assertEquals(new BigDecimal("5.1234567890123456789"), result.second().x());
    }

    @Test void zeroSpanWarningDependsOnOwnerPositionSemantics() {
        assertTrue(WorkbenchAreaPlacement.emptyPositionVolume(area(p(0, 0, 0), p(5, 0, 8))));
        assertTrue(WorkbenchAreaPlacement.emptyPositionVolume(area(p(0, 0, 0), p(0, 5, 8))));
        assertFalse(WorkbenchAreaPlacement.emptyPositionVolume(area(p(5, 7, 8), p(0, 0, 0))));
        var boundary = new SpatialDocument.Area("area", DIMENSION, area(p(0, 0, 0), p(0, 0, 0)).bounds(),
                new SpatialDocument.AreaSemantics(SpatialDocument.AreaCoordinates.INT32, SpatialDocument.AreaBoundary.ENDPOINT_PLANES,
                        Set.of(SpatialDocument.AreaUse.BLOCK_CLOSED, SpatialDocument.AreaUse.HORIZONTAL_BLOCK_CLOSED)));
        assertFalse(WorkbenchAreaPlacement.emptyPositionVolume(boundary));
    }

    @Test void cachedWorldGeometryUsesMappedEndpointsAndOnlySelectedCornerHandles() {
        var cache = new WorkbenchPointOverlay.AreaGeometryCache();
        var source = List.of(area(p(9, 6, 3), p(-2, 1, -7)));
        assertTrue(cache.instances(source, Map.of(), DIMENSION.toString(), null, 1, 2).isEmpty());
        var normal = cache.instances(source, Map.of(DIMENSION, DIMENSION), DIMENSION.toString(), null, 1, 2);
        assertEquals(1, normal.size());
        assertEquals(9, normal.get(0).origin().x());
        assertEquals(.02, normal.get(0).mesh().bounds().maxX(), 1e-9, "no max + 1 expansion");
        assertEquals(-11.02, normal.get(0).mesh().bounds().minX(), 1e-9);
        var selected = cache.instances(source, Map.of(DIMENSION, DIMENSION), DIMENSION.toString(), "area", 1, 2);
        assertEquals(3, selected.size()); assertSame(normal.get(0).mesh(), selected.get(0).mesh());
        assertEquals(9, selected.get(1).origin().x()); assertEquals(-2, selected.get(2).origin().x());
        assertTrue(selected.stream().allMatch(value -> value.argb() == 2 && value.node().equals("area")));
        assertSame(selected, cache.instances(source, Map.of(DIMENSION, DIMENSION), DIMENSION.toString(), "area", 1, 2));
    }

    @Test void coincidentCornersHaveOneSelectedHandleAndPublishedGeometryIsImmutable() {
        var cache = new WorkbenchPointOverlay.AreaGeometryCache();
        var same = List.of(area(p(4, 5, 6), p(4, 5, 6)));
        var first = cache.instances(same, Map.of(DIMENSION, DIMENSION), DIMENSION.toString(), "area", 1, 2);
        assertEquals(2, first.size());
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        cache.instances(List.of(area(p(10, 10, 10), p(14, 15, 16))), Map.of(DIMENSION, DIMENSION), DIMENSION.toString(), "area", 1, 2);
        assertEquals(4, first.get(0).origin().x());
    }

    private static SpatialDocument.Area area(SpatialDocument.Position a, SpatialDocument.Position b) {
        return new SpatialDocument.Area("area", DIMENSION, new SpatialDocument.AreaBounds(a, b), POSITION);
    }
    private static SpatialDocument.Position p(int x, int y, int z) {
        return new SpatialDocument.Position(BigDecimal.valueOf(x), BigDecimal.valueOf(y), BigDecimal.valueOf(z));
    }
}
