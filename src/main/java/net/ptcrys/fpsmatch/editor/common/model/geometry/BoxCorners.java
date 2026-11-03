package net.ptcrys.fpsmatch.editor.common.model.geometry;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Immutable axis aligned box of the world editor rewrite (contract R1, section 2 / fixed geometry
 * task card R04.1).
 *
 * <p>
 * The two corners are stored in the exact order they were supplied and are never rewritten,
 * swapped or clamped; {@link #min()} and {@link #max()} are pure derivations on top of them. All
 * predicates are inclusive on {@code min} and follow the legacy {@code AreaData} semantics:
 * {@link #blockContains(IntPosition)} is closed on both ends on all three axes,
 * {@link #entityContains(Position)} is half open ({@code [min, max)}) on all three axes and
 * {@link #horizontalContains(IntPosition)} uses only the X/Z closed interval and ignores Y.
 *
 * <p>
 * Sizes are expressed as block counts, so a single block box has size {@code 1} on every axis
 * and the largest possible span of an {@code int} axis is {@code 4294967296} blocks. Every volume
 * computation saturates at {@link Long#MAX_VALUE} instead of overflowing.
 */
public record BoxCorners(IntPosition first, IntPosition second) {

    /**
     * Rejects {@code null} corners. The supplied order is preserved as given.
     */
    public BoxCorners {
        if (first == null) {
            throw new IllegalArgumentException("BoxCorners.first must not be null");
        }
        if (second == null) {
            throw new IllegalArgumentException("BoxCorners.second must not be null");
        }
    }

    /**
     * Builds a box from an origin corner and a per axis block count (double inclusive size).
     *
     * <p>
     * Each axis requires a size of at least {@code 1}. The far corner is computed as
     * {@code origin + (size - 1)}; the size is reduced with {@link Math#subtractExact(long, long)}
     * and the coordinate is then added with checked arithmetic, so an out of range far corner is
     * rejected rather than clamped. {@code first} stays the supplied origin: the corners are never
     * reordered. A size of {@code 1} on all three axes yields a single block box.
     *
     * @param origin the corner that becomes {@link #first()}
     * @param width  block count on X, must be {@code >= 1}
     * @param height block count on Y, must be {@code >= 1}
     * @param depth  block count on Z, must be {@code >= 1}
     * @return the box spanning the requested number of blocks
     * @throws IllegalArgumentException if {@code origin} is {@code null}, if any size is below
     *                                  {@code 1}, or if the resulting far corner leaves the {@code int} range
     */
    public static BoxCorners ofSize(IntPosition origin, long width, long height, long depth) {
        if (origin == null) {
            throw new IllegalArgumentException("BoxCorners.ofSize origin must not be null");
        }
        requireSize(width, "width");
        requireSize(height, "height");
        requireSize(depth, "depth");
        long spanX = subtractOne(width, "width");
        long spanY = subtractOne(height, "height");
        long spanZ = subtractOne(depth, "depth");
        return new BoxCorners(
                origin,
                new IntPosition(
                        checkedOffset(origin.x(), spanX, "x"),
                        checkedOffset(origin.y(), spanY, "y"),
                        checkedOffset(origin.z(), spanZ, "z")));
    }

    /** Derived low corner on every axis; the stored {@code first} corner is not replaced. */
    public IntPosition min() {
        return new IntPosition(
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z()));
    }

    /** Derived high corner on every axis; the stored {@code second} corner is not replaced. */
    public IntPosition max() {
        return new IntPosition(
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z()));
    }

    /** Number of blocks along X, always {@code >= 1}. */
    public long sizeX() {
        return size(axisMin(first.x(), second.x()), axisMax(first.x(), second.x()));
    }

    /** Number of blocks along Y, always {@code >= 1}. */
    public long sizeY() {
        return size(axisMin(first.y(), second.y()), axisMax(first.y(), second.y()));
    }

    /** Number of blocks along Z, always {@code >= 1}. */
    public long sizeZ() {
        return size(axisMin(first.z(), second.z()), axisMax(first.z(), second.z()));
    }

    /**
     * Block volume of this box, saturated at {@link Long#MAX_VALUE}.
     *
     * <p>
     * The three sizes are multiplied step by step; before every step the multiplication is
     * pre-checked so no intermediate product can overflow even when the exact volume is far beyond
     * {@code long} range (the full {@code int} span alone is {@code 4294967296^3}).
     */
    public long saturatedBlockVolume() {
        long volume = sizeX();
        volume = saturatedMultiply(volume, sizeY());
        return saturatedMultiply(volume, sizeZ());
    }

    /**
     * Closed interval test on all three axes (legacy block semantics).
     *
     * @throws IllegalArgumentException if {@code point} is {@code null}
     */
    public boolean blockContains(IntPosition point) {
        if (point == null) {
            throw new IllegalArgumentException("BoxCorners.blockContains point must not be null");
        }
        return point.x() >= Math.min(first.x(), second.x()) && point.x() <= Math.max(first.x(), second.x()) && point.y() >= Math.min(first.y(), second.y()) && point.y() <= Math.max(first.y(), second.y()) && point.z() >= Math.min(first.z(), second.z()) && point.z() <= Math.max(first.z(), second.z());
    }

    /**
     * Half open interval test on all three axes, matching the legacy {@code AABB} entity semantics:
     * the high corner itself is excluded and nothing is added to it, so a single block box never
     * contains an entity point.
     *
     * @throws IllegalArgumentException if {@code point} is {@code null}
     */
    public boolean entityContains(Position point) {
        if (point == null) {
            throw new IllegalArgumentException("BoxCorners.entityContains point must not be null");
        }
        return point.x() >= Math.min(first.x(), second.x()) && point.x() < Math.max(first.x(), second.x()) && point.y() >= Math.min(first.y(), second.y()) && point.y() < Math.max(first.y(), second.y()) && point.z() >= Math.min(first.z(), second.z()) && point.z() < Math.max(first.z(), second.z());
    }

    /**
     * Closed interval test on X and Z only; Y is ignored entirely.
     *
     * @throws IllegalArgumentException if {@code point} is {@code null}
     */
    public boolean horizontalContains(IntPosition point) {
        if (point == null) {
            throw new IllegalArgumentException("BoxCorners.horizontalContains point must not be null");
        }
        return point.x() >= Math.min(first.x(), second.x()) && point.x() <= Math.max(first.x(), second.x()) && point.z() >= Math.min(first.z(), second.z()) && point.z() <= Math.max(first.z(), second.z());
    }

    /**
     * Lazy view over every block of this box in X fastest, then Y, then Z order.
     *
     * <p>
     * Nothing is materialised: a huge but legal box still uses constant storage and every call to
     * {@link Iterable#iterator()} returns an independent cursor, so two iterators never interfere
     * and there is no setter on them. A box is never empty, so {@link Iterator#hasNext()} is
     * {@code true} until the last block has been handed out; the high corner value itself is always
     * returned before the iterator terminates and the cursor advances in {@code long} arithmetic
     * rather than by incrementing an {@code int} boundary.
     */
    public Iterable<IntPosition> blocks() {
        return new BlockIterable(
                sizeX(), sizeY(), sizeZ(), min());
    }

    // ---------------------------------------------------------------- helpers

    private static void requireSize(long value, String axis) {
        if (value < 1L) {
            throw new IllegalArgumentException("BoxCorners.ofSize " + axis + " must be >= 1 but was " + value);
        }
    }

    private static long subtractOne(long value, String axis) {
        try {
            return Math.subtractExact(value, 1L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("BoxCorners.ofSize " + axis + " overflow: " + value + " - 1", e);
        }
    }

    private static int checkedOffset(int origin, long span, String axis) {
        long sum;
        try {
            sum = Math.addExact((long) origin, span);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "BoxCorners.ofSize " + axis + " overflow: " + origin + " + " + span, e);
        }
        if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "BoxCorners.ofSize " + axis + " out of int range: " + origin + " + " + span + " = " + sum);
        }
        return (int) sum;
    }

    private static int axisMin(int a, int b) {
        return Math.min(a, b);
    }

    private static int axisMax(int a, int b) {
        return Math.max(a, b);
    }

    private static long size(int low, int high) {
        return (long) high - (long) low + 1L;
    }

    private static long saturatedMultiply(long a, long b) {
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    /**
     * Immutable, allocation free {@link Iterable}; each {@link #iterator()} call creates a new
     * cursor over the same bounds.
     */
    private static final class BlockIterable implements Iterable<IntPosition> {

        private final long sizeX;
        private final long sizeY;
        private final long sizeZ;
        private final IntPosition min;

        private BlockIterable(long sizeX, long sizeY, long sizeZ, IntPosition min) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.min = min;
        }

        @Override
        public Iterator<IntPosition> iterator() {
            return new BlockIterator(sizeX, sizeY, sizeZ, min);
        }
    }

    /**
     * Independent cursor over the box. The cursor is held in {@code long} coordinates, so even the
     * full {@code int} span can be walked from one end without any arithmetic wrap.
     */
    private static final class BlockIterator implements Iterator<IntPosition> {

        private final long maxX;
        private final long maxY;
        private final long maxZ;
        private final long startX;
        private final long startY;

        private long nextX;
        private long nextY;
        private long nextZ;
        private boolean exhausted;

        private BlockIterator(long sizeX, long sizeY, long sizeZ, IntPosition min) {
            this.startX = min.x();
            this.startY = min.y();
            this.nextX = min.x();
            this.nextY = min.y();
            this.nextZ = min.z();
            this.maxX = this.startX + sizeX - 1L;
            this.maxY = this.startY + sizeY - 1L;
            this.maxZ = this.nextZ + sizeZ - 1L;
            this.exhausted = false;
        }

        @Override
        public boolean hasNext() {
            return !exhausted;
        }

        @Override
        public IntPosition next() {
            if (exhausted) {
                throw new NoSuchElementException("blocks() iterator is exhausted");
            }
            IntPosition current = new IntPosition((int) nextX, (int) nextY, (int) nextZ);
            if (nextX < maxX) {
                nextX++;
            } else {
                nextX = startX;
                if (nextY < maxY) {
                    nextY++;
                } else {
                    nextY = startY;
                    if (nextZ < maxZ) {
                        nextZ++;
                    } else {
                        exhausted = true;
                    }
                }
            }
            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("blocks() iteration is read only");
        }
    }
}
