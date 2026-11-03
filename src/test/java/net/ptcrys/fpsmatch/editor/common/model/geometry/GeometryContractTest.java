package net.ptcrys.fpsmatch.editor.common.model.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the pure geometry of the world editor rewrite (contract R1 section 2 and the
 * fixed geometry task card R04.1).
 *
 * <p>Expectations are derived from the contract text, never from implementation internals: the
 * block iteration order, the inclusive block predicate, the half open entity predicate and the
 * saturated volume are all recomputed here with independent reference code. No assertion relies on
 * message text, only on exception types and observable values.
 */
class GeometryContractTest {

    private static final double EPSILON = 0.0d;

    // ---------------------------------------------------------------- Position

    @Test
    void positionRejectsNonFiniteComponents() {
        double[] illegal = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double bad : illegal) {
            assertThrows(IllegalArgumentException.class, () -> new Position(bad, 0.0d, 0.0d));
            assertThrows(IllegalArgumentException.class, () -> new Position(0.0d, bad, 0.0d));
            assertThrows(IllegalArgumentException.class, () -> new Position(0.0d, 0.0d, bad));
        }
    }

    @Test
    void positionRetainsValuesIncludingSignedZeroAndExtremes() {
        Position position = new Position(0.1d, -12.5d, 3.0E300d);
        assertEquals(0.1d, position.x(), EPSILON);
        assertEquals(-12.5d, position.y(), EPSILON);
        assertEquals(3.0E300d, position.z(), EPSILON);

        Position extremes = new Position(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE);
        assertEquals(Double.MAX_VALUE, extremes.x(), EPSILON);
        assertEquals(-Double.MAX_VALUE, extremes.y(), EPSILON);
        assertEquals(Double.MIN_VALUE, extremes.z(), EPSILON);

        Position zeros = new Position(-0.0d, 0.0d, -0.0d);
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(zeros.x()));
        assertEquals(Double.doubleToRawLongBits(0.0d), Double.doubleToRawLongBits(zeros.y()));
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(zeros.z()));
        assertEquals(0.0d, zeros.x(), EPSILON);
    }

    // ---------------------------------------------------------------- Orientation

    @Test
    void orientationRejectsNonFiniteComponents() {
        float[] illegal = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float bad : illegal) {
            assertThrows(IllegalArgumentException.class, () -> new Orientation(bad, 0.0f));
            assertThrows(IllegalArgumentException.class, () -> new Orientation(0.0f, bad));
        }
    }

    @Test
    void orientationKeepsYaw720AndPitchOutsideNinetyWithoutClamping() {
        Orientation orientation = new Orientation(720.0f, 180.0f);
        assertEquals(720.0f, orientation.yaw(), 0.0f);
        assertEquals(180.0f, orientation.pitch(), 0.0f);

        Orientation negative = new Orientation(-720.0f, -180.0f);
        assertEquals(-720.0f, negative.yaw(), 0.0f);
        assertEquals(-180.0f, negative.pitch(), 0.0f);

        Orientation huge = new Orientation(Float.MAX_VALUE, Float.MIN_VALUE);
        assertEquals(Float.MAX_VALUE, huge.yaw(), 0.0f);
        assertEquals(Float.MIN_VALUE, huge.pitch(), 0.0f);

        Orientation ninety = new Orientation(90.0f, 90.0f);
        assertEquals(90.0f, ninety.pitch(), 0.0f);
    }

    @Test
    void orientationRetainsSignedZero() {
        Orientation orientation = new Orientation(-0.0f, 0.0f);
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(orientation.yaw()));
        assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(orientation.pitch()));
        assertEquals(0.0f, orientation.yaw(), 0.0f);
    }

    // ---------------------------------------------------------------- IntPosition

    @Test
    void intPositionStoresComponentsVerbatim() {
        IntPosition position = new IntPosition(Integer.MIN_VALUE, 0, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, position.x());
        assertEquals(0, position.y());
        assertEquals(Integer.MAX_VALUE, position.z());

        IntPosition reversed = new IntPosition(5, -5, 0);
        assertEquals(5, reversed.x());
        assertEquals(-5, reversed.y());
        assertEquals(0, reversed.z());
    }

    @Test
    void intPositionAddUsesCheckedLongThenIntRange() {
        IntPosition origin = new IntPosition(10, -10, 0);

        assertEquals(new IntPosition(10, -10, 0), origin.add(0L, 0L, 0L));
        assertEquals(new IntPosition(11, -11, 1), origin.add(1L, -1L, 1L));
        assertEquals(new IntPosition(0, 0, 0), origin.add(-10L, 10L, 0L));

        // int boundary reached exactly: accepted, never wrapped.
        assertEquals(new IntPosition(Integer.MAX_VALUE, Integer.MIN_VALUE, 7),
                new IntPosition(Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1, 7).add(1L, -1L, 0L));
        assertEquals(new IntPosition(Integer.MAX_VALUE, 0, 0), new IntPosition(0, 0, 0).add(Integer.MAX_VALUE, 0L, 0L));
        assertEquals(new IntPosition(Integer.MIN_VALUE, 0, 0), new IntPosition(0, 0, 0).add(Integer.MIN_VALUE, 0L, 0L));

        // one past the int range: rejected per axis.
        assertThrows(IllegalArgumentException.class, () -> new IntPosition(Integer.MAX_VALUE, 0, 0).add(1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new IntPosition(Integer.MIN_VALUE, 0, 0).add(-1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(0, 0, 0).add((long) Integer.MAX_VALUE + 1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(0, 0, 0).add(0L, (long) Integer.MIN_VALUE - 1L, 0L));

        // A delta outside the int domain can never land back inside it, and is still reported as an
        // argument error rather than wrapped by a narrowing cast.
        assertThrows(IllegalArgumentException.class, () -> new IntPosition(0, 0, 0).add(4294967296L, 0L, 0L));
    }

    @Test
    void intPositionAddRejectsLongOverflowInsteadOfWrapping() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(1, 0, 0).add(Long.MAX_VALUE, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(-1, 0, 0).add(Long.MIN_VALUE, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(0, Integer.MIN_VALUE, 0).add(0L, Long.MIN_VALUE, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(0, 0, Integer.MAX_VALUE).add(0L, 0L, Long.MAX_VALUE));

        // A failing axis does not produce a partially shifted neighbour value: the call throws.
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(Integer.MAX_VALUE, 0, 0).add(1L, 5L, 5L));
        assertThrows(IllegalArgumentException.class,
                () -> new IntPosition(0, 0, 0).add(0L, Long.MIN_VALUE - 1L, 0L));
    }

    // ---------------------------------------------------------------- BoxCorners basics

    @Test
    void boxCornersRejectsNullCorners() {
        IntPosition corner = new IntPosition(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new BoxCorners(null, corner));
        assertThrows(IllegalArgumentException.class, () -> new BoxCorners(corner, null));
        assertThrows(IllegalArgumentException.class, () -> new BoxCorners(null, null));
    }

    @Test
    void boxCornersKeepsSuppliedCornerOrder() {
        IntPosition first = new IntPosition(9, 9, 9);
        IntPosition second = new IntPosition(-9, -9, -9);
        BoxCorners corners = new BoxCorners(first, second);

        assertSame(first, corners.first());
        assertSame(second, corners.second());
        assertEquals(new IntPosition(9, 9, 9), corners.first());
        assertEquals(new IntPosition(-9, -9, -9), corners.second());
    }

    @Test
    void minAndMaxArePureDerivationsOnReversedCorners() {
        BoxCorners corners = new BoxCorners(new IntPosition(5, 0, -7), new IntPosition(-3, 8, 2));
        assertEquals(new IntPosition(-3, 0, -7), corners.min());
        assertEquals(new IntPosition(5, 8, 2), corners.max());

        // Deriving min/max never rewrites the stored corner order.
        assertEquals(new IntPosition(5, 0, -7), corners.first());
        assertEquals(new IntPosition(-3, 8, 2), corners.second());

        BoxCorners identical = new BoxCorners(new IntPosition(1, 2, 3), new IntPosition(1, 2, 3));
        assertEquals(identical.first(), identical.min());
        assertEquals(identical.second(), identical.max());
    }

    @Test
    void sizesAreInclusiveBlockCountsInBothCornerOrders() {
        BoxCorners ascending = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(2, 3, 4));
        assertEquals(3L, ascending.sizeX());
        assertEquals(4L, ascending.sizeY());
        assertEquals(5L, ascending.sizeZ());

        BoxCorners descending = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(-2, -3, -4));
        assertEquals(3L, descending.sizeX());
        assertEquals(4L, descending.sizeY());
        assertEquals(5L, descending.sizeZ());

        BoxCorners single = new BoxCorners(new IntPosition(7, 7, 7), new IntPosition(7, 7, 7));
        assertEquals(1L, single.sizeX());
        assertEquals(1L, single.sizeY());
        assertEquals(1L, single.sizeZ());
        assertEquals(1L, single.saturatedBlockVolume());
    }

    @Test
    void fullIntSpanSizeIsExactlyFourBillionBlocksPerAxis() {
        BoxCorners span = new BoxCorners(
                new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(4294967296L, span.sizeX());
        assertEquals(4294967296L, span.sizeY());
        assertEquals(4294967296L, span.sizeZ());
    }

    @Test
    void saturatedBlockVolumeSaturatesInsteadOfOverflowing() {
        BoxCorners span = new BoxCorners(
                new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, span.saturatedBlockVolume());

        // Two axes already overflow: the first step must already saturate.
        BoxCorners twoAxis = new BoxCorners(new IntPosition(Integer.MIN_VALUE, 0, 0),
                new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
        assertEquals(Long.MAX_VALUE, twoAxis.saturatedBlockVolume());

        // Near the long edge: 4e9 * 4e9 saturates, then stays saturated.
        BoxCorners nearEdge = new BoxCorners(new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, 5),
                new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, 5));
        assertEquals(Long.MAX_VALUE, nearEdge.saturatedBlockVolume());
    }

    @Test
    void saturatedBlockVolumeIsExactWhenItFits() {
        BoxCorners small = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(3, 3, 3));
        assertEquals(64L, small.saturatedBlockVolume());

        BoxCorners block = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(0, 0, 0));
        assertEquals(1L, block.saturatedBlockVolume());

        // 4294967296 blocks on X, 1 on Y and 2 on Z fits into a long and must not be saturated.
        BoxCorners wide = new BoxCorners(new IntPosition(Integer.MIN_VALUE, 0, 0),
                new IntPosition(Integer.MAX_VALUE, 0, 1));
        assertEquals(2L * 4294967296L, wide.saturatedBlockVolume());
    }

    // ---------------------------------------------------------------- ofSize

    @Test
    void ofSizeOneByOneByOneIsASingleBlock() {
        IntPosition origin = new IntPosition(4, -4, 12);
        BoxCorners corners = BoxCorners.ofSize(origin, 1L, 1L, 1L);
        assertSame(origin, corners.first());
        assertEquals(origin, corners.first());
        assertEquals(origin, corners.second());
        assertEquals(origin, corners.min());
        assertEquals(origin, corners.max());
        assertEquals(1L, corners.saturatedBlockVolume());
    }

    @Test
    void ofSizeUsesDoubleInclusiveExtents() {
        IntPosition origin = new IntPosition(10, 20, 30);

        assertEquals(new IntPosition(11, 22, 33), BoxCorners.ofSize(origin, 2L, 3L, 4L).second());
        assertEquals(2L, BoxCorners.ofSize(origin, 2L, 3L, 4L).sizeX());
        assertEquals(3L, BoxCorners.ofSize(origin, 2L, 3L, 4L).sizeY());
        assertEquals(4L, BoxCorners.ofSize(origin, 2L, 3L, 4L).sizeZ());

        // The origin is always "first"; the far corner is never promoted above it.
        BoxCorners negative = BoxCorners.ofSize(new IntPosition(0, 0, 0), 3L, 1L, 1L);
        assertEquals(new IntPosition(0, 0, 0), negative.first());
        assertEquals(new IntPosition(2, 0, 0), negative.second());
    }

    @Test
    void ofSizeRejectsSizesBelowOne() {
        IntPosition origin = new IntPosition(0, 0, 0);
        for (long bad : new long[] {0L, -1L, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> BoxCorners.ofSize(origin, bad, 1L, 1L));
            assertThrows(IllegalArgumentException.class, () -> BoxCorners.ofSize(origin, 1L, bad, 1L));
            assertThrows(IllegalArgumentException.class, () -> BoxCorners.ofSize(origin, 1L, 1L, bad));
        }
    }

    @Test
    void ofSizeRejectsNullOrigin() {
        assertThrows(IllegalArgumentException.class, () -> BoxCorners.ofSize(null, 1L, 1L, 1L));
    }

    @Test
    void ofSizeRejectsOutOfRangeFarCornerWithoutClamping() {
        assertThrows(IllegalArgumentException.class,
                () -> BoxCorners.ofSize(new IntPosition(Integer.MAX_VALUE, 0, 0), 2L, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> BoxCorners.ofSize(new IntPosition(0, Integer.MAX_VALUE, 0), 1L, 2L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> BoxCorners.ofSize(new IntPosition(0, 0, Integer.MAX_VALUE), 1L, 1L, 2L));

        // Growing away from Integer.MIN_VALUE stays representable, so it must be accepted: the
        // rejection above is about leaving the int range, not about the origin being extreme.
        assertEquals(new IntPosition(0, 0, Integer.MIN_VALUE + 1),
                BoxCorners.ofSize(new IntPosition(0, 0, Integer.MIN_VALUE), 1L, 1L, 2L).second());
        assertEquals(new IntPosition(Integer.MIN_VALUE + 1, 0, 0),
                BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, 0, 0), 2L, 1L, 1L).second());

        // The largest int span is legal from MIN, but not one block more.
        assertEquals(new IntPosition(Integer.MAX_VALUE, 0, 0),
                BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, 0, 0), 4294967296L, 1L, 1L).second());
        assertThrows(IllegalArgumentException.class,
                () -> BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, 0, 0), 4294967297L, 1L, 1L));

        // Single block at the extreme corners stays representable.
        assertEquals(new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
                BoxCorners.ofSize(new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
                        1L, 1L, 1L).second());
        assertEquals(new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                        1L, 1L, 1L).second());
    }

    @Test
    void ofSizeRejectsHugeSizesThatCannotLandInIntRange() {
        assertThrows(IllegalArgumentException.class,
                () -> BoxCorners.ofSize(new IntPosition(0, 0, 0), Long.MAX_VALUE, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, 0, 0), Long.MAX_VALUE, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    // ---------------------------------------------------------------- predicates

    @Test
    void blockContainsIsClosedOnAllThreeAxes() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(2, 4, 6));

        assertTrue(corners.blockContains(new IntPosition(0, 0, 0)));
        assertTrue(corners.blockContains(new IntPosition(2, 4, 6)));
        assertTrue(corners.blockContains(new IntPosition(1, 2, 3)));
        assertTrue(corners.blockContains(new IntPosition(0, 4, 0)));

        assertFalse(corners.blockContains(new IntPosition(-1, 0, 0)));
        assertFalse(corners.blockContains(new IntPosition(3, 0, 0)));
        assertFalse(corners.blockContains(new IntPosition(0, -1, 0)));
        assertFalse(corners.blockContains(new IntPosition(0, 5, 0)));
        assertFalse(corners.blockContains(new IntPosition(0, 0, 7)));

        // Reversed corners describe the same closed region.
        BoxCorners reversed = new BoxCorners(new IntPosition(2, 4, 6), new IntPosition(0, 0, 0));
        assertTrue(reversed.blockContains(new IntPosition(0, 0, 0)));
        assertTrue(reversed.blockContains(new IntPosition(2, 4, 6)));
        assertFalse(reversed.blockContains(new IntPosition(3, 4, 6)));

        // Single block contains exactly itself.
        BoxCorners single = new BoxCorners(new IntPosition(5, 5, 5), new IntPosition(5, 5, 5));
        assertTrue(single.blockContains(new IntPosition(5, 5, 5)));
        assertFalse(single.blockContains(new IntPosition(6, 5, 5)));
    }

    @Test
    void blockContainsRejectsNull() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> corners.blockContains(null));
    }

    @Test
    void entityContainsIsHalfOpenAndExcludesMaxWithoutAddingOne() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(2, 4, 6));

        assertTrue(corners.entityContains(new Position(0.0d, 0.0d, 0.0d)));
        assertTrue(corners.entityContains(new Position(1.5d, 3.9d, 5.999d)));

        // The high corner is excluded: no "+1" is applied anywhere.
        assertFalse(corners.entityContains(new Position(2.0d, 0.0d, 0.0d)));
        assertFalse(corners.entityContains(new Position(0.0d, 4.0d, 0.0d)));
        assertFalse(corners.entityContains(new Position(0.0d, 0.0d, 6.0d)));
        assertFalse(corners.entityContains(new Position(2.0d, 4.0d, 6.0d)));

        // Just outside the closed block region is outside the entity region as well.
        assertFalse(corners.entityContains(new Position(-0.001d, 0.0d, 0.0d)));
        assertFalse(corners.entityContains(new Position(2.0d + 1.0d, 0.0d, 0.0d)));
        assertFalse(corners.entityContains(new Position(0.0d, 4.0d + 0.5d, 0.0d)));
        assertFalse(corners.entityContains(new Position(0.0d, 0.0d, 6.0d + 0.5d)));

        // A block that blockContains accepts can still be rejected by entityContains at a max face.
        assertTrue(corners.blockContains(new IntPosition(2, 4, 6)));
        assertFalse(corners.entityContains(new Position(2.0d, 4.0d, 6.0d)));
    }

    @Test
    void entityContainsIsAlwaysFalseForASingleBlock() {
        BoxCorners single = new BoxCorners(new IntPosition(3, 3, 3), new IntPosition(3, 3, 3));
        assertFalse(single.entityContains(new Position(3.0d, 3.0d, 3.0d)));
        assertFalse(single.entityContains(new Position(3.5d, 3.5d, 3.5d)));
        assertFalse(single.entityContains(new Position(2.999d, 3.0d, 3.0d)));
    }

    @Test
    void entityContainsIsAlwaysFalseWhenOneAxisHasZeroThickness() {
        // min.z == max.z makes the half open z interval empty, so nothing at all is contained.
        BoxCorners flat = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(10, 10, 0));
        assertFalse(flat.entityContains(new Position(0.0d, 0.0d, 0.0d)));
        assertFalse(flat.entityContains(new Position(5.0d, 5.0d, 0.0d)));
        assertFalse(flat.entityContains(new Position(9.9d, 9.9d, 0.0d)));

        // The horizontal predicate still uses its closed X/Z interval for the same box.
        assertTrue(flat.horizontalContains(new IntPosition(0, 0, 0)));
        assertTrue(flat.horizontalContains(new IntPosition(10, 500, 0)));
    }

    @Test
    void entityContainsRejectsNull() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> corners.entityContains(null));
    }

    @Test
    void horizontalContainsIgnoresYEntirely() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(2, 4, 6));

        assertTrue(corners.horizontalContains(new IntPosition(0, 0, 0)));
        assertTrue(corners.horizontalContains(new IntPosition(2, 4, 6)));
        assertTrue(corners.horizontalContains(new IntPosition(1, Integer.MIN_VALUE, 3)));
        assertTrue(corners.horizontalContains(new IntPosition(1, Integer.MAX_VALUE, 3)));
        assertTrue(corners.horizontalContains(new IntPosition(1, 100000, 3)));

        assertFalse(corners.horizontalContains(new IntPosition(-1, 1, 1)));
        assertFalse(corners.horizontalContains(new IntPosition(3, 1, 1)));
        assertFalse(corners.horizontalContains(new IntPosition(1, 1, -1)));
        assertFalse(corners.horizontalContains(new IntPosition(1, 1, 7)));

        // Reversed corners describe the same closed horizontal region.
        BoxCorners reversed = new BoxCorners(new IntPosition(2, 4, 6), new IntPosition(0, 0, 0));
        assertTrue(reversed.horizontalContains(new IntPosition(0, -999, 0)));
        assertFalse(reversed.horizontalContains(new IntPosition(3, 0, 0)));
    }

    @Test
    void horizontalContainsRejectsNull() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> corners.horizontalContains(null));
    }

    // ---------------------------------------------------------------- blocks()

    @Test
    void blocksIterateXFastestThenYThenZ() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));
        List<IntPosition> expected = List.of(
                new IntPosition(0, 0, 0), new IntPosition(1, 0, 0),
                new IntPosition(0, 1, 0), new IntPosition(1, 1, 0),
                new IntPosition(0, 0, 1), new IntPosition(1, 0, 1),
                new IntPosition(0, 1, 1), new IntPosition(1, 1, 1));
        assertEquals(expected, drain(corners.blocks()));
        assertEquals(8, expected.size());
    }

    @Test
    void blocksStartAtTheDerivedMinCornerForReversedInput() {
        BoxCorners reversed = new BoxCorners(new IntPosition(1, 1, 1), new IntPosition(0, 0, 0));
        List<IntPosition> expected = List.of(
                new IntPosition(0, 0, 0), new IntPosition(1, 0, 0),
                new IntPosition(0, 1, 0), new IntPosition(1, 1, 0),
                new IntPosition(0, 0, 1), new IntPosition(1, 0, 1),
                new IntPosition(0, 1, 1), new IntPosition(1, 1, 1));
        assertEquals(expected, drain(reversed.blocks()));
    }

    @Test
    void blocksReturnTheMaxValueBeforeTerminatingAtIntMax() {
        BoxCorners corners = BoxCorners.ofSize(
                new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), 1L, 1L, 1L);
        Iterator<IntPosition> iterator = corners.blocks().iterator();

        assertTrue(iterator.hasNext());
        assertEquals(new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void blocksTerminateCleanlyAtIntMinWithoutWrapping() {
        BoxCorners corners = BoxCorners.ofSize(
                new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), 1L, 1L, 1L);
        Iterator<IntPosition> iterator = corners.blocks().iterator();

        assertTrue(iterator.hasNext());
        assertEquals(new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);

        // A four block row starting at MIN also ends on MIN + 3 and stops.
        BoxCorners row = BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, 0, 0), 4L, 1L, 1L);
        List<IntPosition> expected = List.of(
                new IntPosition(Integer.MIN_VALUE, 0, 0),
                new IntPosition(Integer.MIN_VALUE + 1, 0, 0),
                new IntPosition(Integer.MIN_VALUE + 2, 0, 0),
                new IntPosition(Integer.MIN_VALUE + 3, 0, 0));
        assertEquals(expected, drain(row.blocks()));
    }

    @Test
    void blocksReturnTheMaxValueBeforeTerminatingAtIntMaxForALongRow() {
        // The row ends exactly on Integer.MAX_VALUE and must stop there, not wrap to MIN.
        BoxCorners row = BoxCorners.ofSize(new IntPosition(Integer.MAX_VALUE - 3, 0, 0), 4L, 1L, 1L);
        List<IntPosition> expected = List.of(
                new IntPosition(Integer.MAX_VALUE - 3, 0, 0),
                new IntPosition(Integer.MAX_VALUE - 2, 0, 0),
                new IntPosition(Integer.MAX_VALUE - 1, 0, 0),
                new IntPosition(Integer.MAX_VALUE, 0, 0));
        assertEquals(expected, drain(row.blocks()));
    }

    @Test
    void blocksOverTheFullIntSpanCanBeSampledWithoutMaterialisingEverything() {
        BoxCorners span = BoxCorners.ofSize(new IntPosition(Integer.MIN_VALUE, 0, 0), 4294967296L, 1L, 1L);
        assertEquals(4294967296L, span.sizeX());

        Iterable<IntPosition> blocks = span.blocks();
        // No pre-built list/collection: the view itself must not be a Collection.
        assertFalse(blocks instanceof java.util.Collection<?>, "blocks() must stay a lazy view");

        Iterator<IntPosition> iterator = blocks.iterator();
        List<IntPosition> firstEight = new ArrayList<>();
        for (int i = 0; i < 8 && iterator.hasNext(); i++) {
            firstEight.add(iterator.next());
        }
        assertEquals(8, firstEight.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(new IntPosition(Integer.MIN_VALUE + i, 0, 0), firstEight.get(i));
        }
        assertTrue(iterator.hasNext(), "the full span iterator is far from exhausted");
    }

    @Test
    void blocksIteratorsAreIndependentPerCall() {
        BoxCorners corners = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 2, 3));
        Iterable<IntPosition> blocks = corners.blocks();

        Iterator<IntPosition> firstRun = blocks.iterator();
        Iterator<IntPosition> secondRun = blocks.iterator();
        assertNotSame(firstRun, secondRun);

        // Advancing one cursor must not move the other.
        assertEquals(new IntPosition(0, 0, 0), firstRun.next());
        assertEquals(new IntPosition(1, 0, 0), firstRun.next());
        assertEquals(new IntPosition(0, 1, 0), firstRun.next());
        assertEquals(new IntPosition(0, 0, 0), secondRun.next());
        assertEquals(new IntPosition(1, 0, 0), secondRun.next());

        // A third cursor is still complete.
        assertEquals(new IntPosition(0, 0, 0), blocks.iterator().next());

        // Two cursors walked to the end both yield the full box.
        BoxCorners cube = new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));
        Iterator<IntPosition> a = cube.blocks().iterator();
        Iterator<IntPosition> b = cube.blocks().iterator();
        while (b.hasNext()) {
            b.next();
        }
        assertEquals(8, drainIterator(a).size());
    }

    @Test
    void blocksIteratorRejectsRemove() {
        Iterator<IntPosition> iterator = new BoxCorners(new IntPosition(0, 0, 0),
                new IntPosition(1, 1, 1)).blocks().iterator();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }

    // ---------------------------------------------------------------- randomised reference cross-check

    @Test
    void randomisedSmallBoxesMatchIndependentReferenceOrderAndPredicates() {
        Random random = new Random(20261014L);
        for (int iteration = 0; iteration < 400; iteration++) {
            IntPosition first = new IntPosition(random.nextInt(9) - 4, random.nextInt(9) - 4, random.nextInt(9) - 4);
            IntPosition second = new IntPosition(random.nextInt(9) - 4, random.nextInt(9) - 4, random.nextInt(9) - 4);
            BoxCorners corners = new BoxCorners(first, second);

            List<IntPosition> expectedBlocks = referenceBlocks(first, second);
            List<IntPosition> actualBlocks = drain(corners.blocks());
            assertEquals(expectedBlocks, actualBlocks, "block order for " + first + " -> " + second);

            long expectedVolume = (long) referenceSize(first.x(), second.x())
                    * referenceSize(first.y(), second.y()) * referenceSize(first.z(), second.z());
            assertEquals(expectedVolume, corners.saturatedBlockVolume(), "volume for " + first + " -> " + second);

            for (int probe = 0; probe < 24; probe++) {
                IntPosition point = new IntPosition(random.nextInt(13) - 6, random.nextInt(13) - 6,
                        random.nextInt(13) - 6);
                assertEquals(referenceBlockContains(first, second, point), corners.blockContains(point),
                        "blockContains " + point + " in " + first + " -> " + second);
                assertEquals(referenceHorizontalContains(first, second, point), corners.horizontalContains(point),
                        "horizontalContains " + point + " in " + first + " -> " + second);
            }

            // Entity probes: exact min (inclusive), exact max (exclusive) and small midpoints.
            IntPosition min = corners.min();
            IntPosition max = corners.max();
            assertFalse(corners.entityContains(new Position(max.x(), max.y(), max.z())));
            assertEquals(referenceEntityContains(first, second, min.x(), min.y(), min.z()),
                    corners.entityContains(new Position(min.x(), min.y(), min.z())));
            double midX = (min.x() + max.x()) / 2.0d;
            double midY = (min.y() + max.y()) / 2.0d;
            double midZ = (min.z() + max.z()) / 2.0d;
            assertEquals(referenceEntityContains(first, second, midX, midY, midZ),
                    corners.entityContains(new Position(midX, midY, midZ)));
            double overX = max.x() + 0.25d;
            assertEquals(referenceEntityContains(first, second, overX, midY, midZ),
                    corners.entityContains(new Position(overX, midY, midZ)));
        }
    }

    // ---------------------------------------------------------------- frozen public API

    @Test
    void publicApiSurfaceIsFrozen() {
        assertEquals(expectedMethods("equals", "hashCode", "toString", "x", "y", "z"), publicMethodNames(Position.class));
        assertEquals(expectedMethods("equals", "hashCode", "toString", "yaw", "pitch"),
                publicMethodNames(Orientation.class));
        assertEquals(expectedMethods("add", "equals", "hashCode", "toString", "x", "y", "z"),
                publicMethodNames(IntPosition.class));
        assertEquals(expectedMethods("blocks", "blockContains", "entityContains", "equals", "first", "hashCode",
                "horizontalContains", "max", "min", "ofSize", "saturatedBlockVolume", "second", "sizeX", "sizeY",
                "sizeZ", "toString"), publicMethodNames(BoxCorners.class));

        assertEquals(List.of("x", "y", "z"), componentNames(Position.class));
        assertEquals(List.of("yaw", "pitch"), componentNames(Orientation.class));
        assertEquals(List.of("x", "y", "z"), componentNames(IntPosition.class));
        assertEquals(List.of("first", "second"), componentNames(BoxCorners.class));

        assertEquals(List.of(double.class, double.class, double.class), componentTypes(Position.class));
        assertEquals(List.of(float.class, float.class), componentTypes(Orientation.class));
        assertEquals(List.of(int.class, int.class, int.class), componentTypes(IntPosition.class));
        assertEquals(List.of(IntPosition.class, IntPosition.class), componentTypes(BoxCorners.class));

        for (Class<?> type : List.of(Position.class, Orientation.class, IntPosition.class, BoxCorners.class)) {
            assertTrue(type.isRecord(), type.getSimpleName() + " must be a record");
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getSimpleName() + " must be final");
            List<Class<?>[]> parameterTypes = new ArrayList<>();
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    parameterTypes.add(constructor.getParameterTypes());
                }
            }
            assertEquals(1, parameterTypes.size(), type.getSimpleName() + " must expose exactly one constructor");
            assertEquals(componentTypes(type), Arrays.asList(parameterTypes.get(0)),
                    type.getSimpleName() + " constructor must take exactly the record components");
        }
    }

    // ---------------------------------------------------------------- reference implementation

    private static List<IntPosition> drain(Iterable<IntPosition> blocks) {
        return drainIterator(blocks.iterator());
    }

    private static List<IntPosition> drainIterator(Iterator<IntPosition> blocks) {
        List<IntPosition> collected = new ArrayList<>();
        while (blocks.hasNext()) {
            collected.add(blocks.next());
            if (collected.size() > 100000) {
                throw new AssertionError("blocks() iteration is unbounded");
            }
        }
        return collected;
    }

    private static int referenceSize(int a, int b) {
        return (b >= a) ? (b - a + 1) : (a - b + 1);
    }

    /** Triple loop over the closed corner region, written from the contract text only. */
    private static List<IntPosition> referenceBlocks(IntPosition first, IntPosition second) {
        int minX = Math.min(first.x(), second.x());
        int maxX = Math.max(first.x(), second.x());
        int minY = Math.min(first.y(), second.y());
        int maxY = Math.max(first.y(), second.y());
        int minZ = Math.min(first.z(), second.z());
        int maxZ = Math.max(first.z(), second.z());
        List<IntPosition> blocks = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    blocks.add(new IntPosition(x, y, z));
                }
            }
        }
        return blocks;
    }

    private static boolean referenceBlockContains(IntPosition first, IntPosition second, IntPosition point) {
        return point.x() >= Math.min(first.x(), second.x()) && point.x() <= Math.max(first.x(), second.x())
                && point.y() >= Math.min(first.y(), second.y()) && point.y() <= Math.max(first.y(), second.y())
                && point.z() >= Math.min(first.z(), second.z()) && point.z() <= Math.max(first.z(), second.z());
    }

    private static boolean referenceHorizontalContains(IntPosition first, IntPosition second, IntPosition point) {
        return point.x() >= Math.min(first.x(), second.x()) && point.x() <= Math.max(first.x(), second.x())
                && point.z() >= Math.min(first.z(), second.z()) && point.z() <= Math.max(first.z(), second.z());
    }

    private static boolean referenceEntityContains(IntPosition first, IntPosition second, double x, double y,
            double z) {
        return x >= Math.min(first.x(), second.x()) && x < Math.max(first.x(), second.x())
                && y >= Math.min(first.y(), second.y()) && y < Math.max(first.y(), second.y())
                && z >= Math.min(first.z(), second.z()) && z < Math.max(first.z(), second.z());
    }

    private static Set<String> expectedMethods(String... names) {
        return new TreeSet<>(List.of(names));
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private static List<String> componentNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static List<Class<?>> componentTypes(Class<?> type) {
        List<Class<?>> types = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            types.add(component.getType());
        }
        return types;
    }
}