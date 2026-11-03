package net.ptcrys.fpsmatch.editor.core.editing;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import org.junit.jupiter.api.Test;

class SpatialDocumentDirectionTest {
    @Test void areaEndpointsRemainOrderedExactAndOwnerSemanticsAreImmutable() {
        var tiny = new BigDecimal("1e-1000");
        var first = new SpatialDocument.Position(new BigDecimal("10.000"), tiny, BigDecimal.ZERO);
        var second = new SpatialDocument.Position(BigDecimal.ONE, tiny, BigDecimal.ZERO);
        var bounds = new SpatialDocument.AreaBounds(first, second);
        assertSame(first, bounds.first()); assertSame(second, bounds.second());
        assertSame(tiny, bounds.first().y());
        assertFalse(bounds.sameBounds(new SpatialDocument.AreaBounds(second, first)));
        assertTrue(bounds.sameBounds(new SpatialDocument.AreaBounds(
                new SpatialDocument.Position(BigDecimal.TEN, tiny, new BigDecimal("0.00")), second)));
        var uses = new java.util.HashSet<>(Set.of(SpatialDocument.AreaUse.POSITION_HALF_OPEN));
        var semantics = new SpatialDocument.AreaSemantics(SpatialDocument.AreaCoordinates.DECIMAL,
                SpatialDocument.AreaBoundary.ENDPOINT_PLANES, uses);
        uses.clear(); assertEquals(Set.of(SpatialDocument.AreaUse.POSITION_HALF_OPEN), semantics.uses());
        assertThrows(UnsupportedOperationException.class, () -> semantics.uses().clear());
        assertEquals(bounds, new SpatialDocument.Area("area", EditorId.parse("fixture:world"), bounds, semantics).bounds());
        assertDoesNotThrow(() -> new SpatialDocument.AreaBounds(first, first));
        assertThrows(NullPointerException.class, () -> new SpatialDocument.AreaBounds(null, second));
        assertThrows(IllegalArgumentException.class, () -> new SpatialDocument.AreaSemantics(
                SpatialDocument.AreaCoordinates.DECIMAL, SpatialDocument.AreaBoundary.ENDPOINT_PLANES, Set.of()));
        var integers = new SpatialDocument.AreaSemantics(SpatialDocument.AreaCoordinates.INT32,
                SpatialDocument.AreaBoundary.ENDPOINT_PLANES, semantics.uses());
        assertThrows(ArithmeticException.class, () -> new SpatialDocument.Area("area", EditorId.parse("fixture:world"), bounds, integers));
        var limit = new SpatialDocument.Position(new BigDecimal("2147483648"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThrows(ArithmeticException.class, () -> new SpatialDocument.Area("area", EditorId.parse("fixture:world"),
                new SpatialDocument.AreaBounds(limit, limit), integers));
    }

    @Test void normalizesFiniteVectorsAcrossTheDoubleRange() {
        var ordinary = new SpatialDocument.Direction(3, 4, -0.0);
        assertEquals(0.6, ordinary.x(), 1e-15);
        assertEquals(0.8, ordinary.y(), 1e-15);
        assertEquals(0.0, ordinary.z());
        var huge = new SpatialDocument.Direction(Double.MAX_VALUE, Double.MAX_VALUE, 0);
        assertEquals(Math.sqrt(0.5), huge.x(), 1e-15);
        assertEquals(Math.sqrt(0.5), huge.y(), 1e-15);
        assertEquals(new SpatialDocument.Direction(1, 0, 0),
                new SpatialDocument.Direction(Double.MIN_VALUE, -0.0, 0));
        assertEquals(new SpatialDocument.Direction(0, 0, -1),
                new SpatialDocument.Direction(0, 0, -Double.MIN_NORMAL));
    }

    @Test void rejectsZeroAndNonFiniteDirectionsInsteadOfInventingFacing() {
        assertThrows(IllegalArgumentException.class, () -> new SpatialDocument.Direction(0, -0.0, 0));
        for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> new SpatialDocument.Direction(invalid, 1, 0));
            assertThrows(IllegalArgumentException.class, () -> new SpatialDocument.Direction(1, invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> new SpatialDocument.Direction(1, 0, invalid));
        }
    }

    @Test void oldDeclarationsHaveNoFacingAndExplicitFacingDoesNotRewriteAngles() {
        var dimension = EditorId.parse("fixture:world");
        var yaw = new BigDecimal("1E-100");
        var pitch = new BigDecimal("-93.2500");
        var legacy = new SpatialDocument.Point("point", dimension, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, yaw, pitch);
        assertTrue(legacy.direction().isEmpty());
        assertTrue(legacy.groundDirection().isEmpty());
        var direction = new SpatialDocument.Direction(0, 1, 0);
        var explicit = new SpatialDocument.Point("point", dimension, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, yaw, pitch, Optional.of(direction));
        assertSame(yaw, explicit.yaw());
        assertSame(pitch, explicit.pitch());
        assertEquals(Optional.of(direction), explicit.direction());
        assertTrue(explicit.groundDirection().isEmpty(), "a vertical vector alone cannot declare ground facing");
        assertThrows(NullPointerException.class, () -> new SpatialDocument.Point("point", dimension,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, yaw, pitch, null));
    }

    @Test void compatibleFacingConstructorProjectsOnlyNonzeroHorizontalComponents() {
        var dimension = EditorId.parse("fixture:world");
        for (var direction : new SpatialDocument.Direction[] {
                new SpatialDocument.Direction(3, 4, -3),
                new SpatialDocument.Direction(Double.MIN_VALUE, 1, -Double.MIN_VALUE),
                new SpatialDocument.Direction(1e-200, -1, -1e-200) }) {
            var point = new SpatialDocument.Point("point", dimension, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Optional.of(direction));
            assertEquals(Optional.of(direction), point.direction());
            assertEquals(Optional.of(new SpatialDocument.Direction(1, 0, -1)), point.groundDirection());
        }
        var vertical = new SpatialDocument.Point("point", dimension, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Optional.of(new SpatialDocument.Direction(0, -1, 0)));
        assertTrue(vertical.groundDirection().isEmpty());
    }

    @Test void explicitGroundFacingIsIndependentAndDoesNotRewriteStoredValues() {
        var dimension = EditorId.parse("fixture:world");
        var x = new BigDecimal("1.2500");
        var yaw = new BigDecimal("450.000");
        var pitch = new BigDecimal("90.000");
        var ground = Optional.of(new SpatialDocument.Direction(-1, -0.0, 0));
        var vertical = Optional.of(new SpatialDocument.Direction(0, -1, 0));
        var point = new SpatialDocument.Point("point", dimension, x, BigDecimal.ZERO, BigDecimal.ZERO,
                yaw, pitch, vertical, ground);
        assertSame(x, point.x()); assertSame(yaw, point.yaw()); assertSame(pitch, point.pitch());
        assertEquals(vertical, point.direction()); assertEquals(ground, point.groundDirection());
        assertEquals(ground, new SpatialDocument.Point("point", dimension, x, BigDecimal.ZERO, BigDecimal.ZERO,
                yaw, pitch, Optional.empty(), ground).groundDirection());
        assertTrue(new SpatialDocument.Point("point", dimension, x, BigDecimal.ZERO, BigDecimal.ZERO,
                yaw, pitch, Optional.of(new SpatialDocument.Direction(1, 0, 0)), Optional.empty()).groundDirection().isEmpty());
        assertNotEquals(point, new SpatialDocument.Point("point", dimension, x, BigDecimal.ZERO, BigDecimal.ZERO,
                yaw, pitch, vertical, Optional.of(new SpatialDocument.Direction(0, 0, 1))));
        assertThrows(NullPointerException.class, () -> new SpatialDocument.Point("point", dimension, x,
                BigDecimal.ZERO, BigDecimal.ZERO, yaw, pitch, vertical, null));
        assertThrows(NullPointerException.class, () -> new SpatialDocument.Point("point", dimension, x,
                BigDecimal.ZERO, BigDecimal.ZERO, yaw, pitch, null, ground));
        for (var invalid : new SpatialDocument.Direction[] {
                new SpatialDocument.Direction(0, 1, 0), new SpatialDocument.Direction(1, Double.MIN_VALUE, 0) })
            assertThrows(IllegalArgumentException.class, () -> new SpatialDocument.Point("point", dimension, x,
                    BigDecimal.ZERO, BigDecimal.ZERO, yaw, pitch, vertical, Optional.of(invalid)));
    }
}
