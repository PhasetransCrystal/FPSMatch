package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure document-space declarations. They carry no world, render-frame or write authority. */
public final class SpatialDocument {

    private SpatialDocument() {}

    /** Exact stored coordinates; conversion for rendering must not replace these values. */
    public record Position(BigDecimal x, BigDecimal y, BigDecimal z) {

        public Position {
            coordinate(x, "x");
            coordinate(y, "y");
            coordinate(z, "z");
        }

        /** Scale-only differences do not represent a move. */
        public boolean sameCoordinates(Position other) {
            return other != null && x.compareTo(other.x) == 0 && y.compareTo(other.y) == 0 && z.compareTo(other.z) == 0;
        }
    }

    /** Ordered endpoint planes. No corner sorting, block padding or coordinate rounding occurs. */
    public record AreaBounds(Position first, Position second) {

        public AreaBounds {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }

        public boolean sameBounds(AreaBounds other) {
            return other != null && first.sameCoordinates(other.first) && second.sameCoordinates(other.second);
        }
    }

    public enum AreaCoordinates {
        DECIMAL,
        INT32
    }

    /** Render the supplied endpoint planes, including coincident planes, without max + 1 padding. */
    public enum AreaBoundary {
        ENDPOINT_PLANES
    }

    public enum AreaUse {
        POSITION_HALF_OPEN,
        BLOCK_CLOSED,
        HORIZONTAL_BLOCK_CLOSED,
        ENTITY_BOUNDS_INTERSECTION
    }

    /**
     * Owner-declared coordinate domain, display geometry and actual runtime predicates.
     * One area may have several uses; a host must not infer a single containment rule.
     */
    public record AreaSemantics(AreaCoordinates coordinates, AreaBoundary displayBoundary, Set<AreaUse> uses) {

        public AreaSemantics {
            Objects.requireNonNull(coordinates, "coordinates");
            Objects.requireNonNull(displayBoundary, "displayBoundary");
            uses = Set.copyOf(uses);
            if (uses.isEmpty()) throw new IllegalArgumentException("Area uses must be declared");
        }
    }

    public record Area(String node, EditorId dimension, AreaBounds bounds, AreaSemantics semantics) {

        public Area {
            DocumentEditor.requireAddress(node);
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(semantics, "semantics");
            if (semantics.coordinates() == AreaCoordinates.INT32) {
                integerCorner(bounds.first());
                integerCorner(bounds.second());
            }
        }
    }

    private static void integerCorner(Position corner) {
        corner.x().intValueExact();
        corner.y().intValueExact();
        corner.z().intValueExact();
    }

    /**
     * Owner-declared effective direction in document X/Y/Z axes, independent of stored angles.
     * Scaled nonzero vectors are normalized without overflowing or discarding an entirely
     * subnormal vector.
     */
    public record Direction(double x, double y, double z) {

        public Direction {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Direction must be finite");
            double scale = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
            if (scale == 0) throw new IllegalArgumentException("Direction must be nonzero");
            x /= scale;
            y /= scale;
            z /= scale;
            double length = Math.sqrt(x * x + y * y + z * z);
            x = x == 0 ? 0 : x / length;
            y = y == 0 ? 0 : y / length;
            z = z == 0 ? 0 : z / length;
        }
    }

    /**
     * One existing document node. Stored angles remain exact; effective full and ground
     * directions are owner declarations. All metadata survives a position-only move.
     */
    public record Point(String node, EditorId dimension, BigDecimal x, BigDecimal y, BigDecimal z,
                        BigDecimal yaw, BigDecimal pitch, Optional<Direction> direction,
                        Optional<Direction> groundDirection) {

        public Point {
            DocumentEditor.requireAddress(node);
            Objects.requireNonNull(dimension, "dimension");
            coordinate(x, "x");
            coordinate(y, "y");
            coordinate(z, "z");
            angle(yaw, "yaw");
            angle(pitch, "pitch");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(groundDirection, "groundDirection");
            if (groundDirection.isPresent() && groundDirection.get().y() != 0)
                throw new IllegalArgumentException("Ground direction must be horizontal");
        }

        /** Compatibility for owners which previously supplied only full facing. */
        public Point(String node, EditorId dimension, BigDecimal x, BigDecimal y, BigDecimal z,
                     BigDecimal yaw, BigDecimal pitch, Optional<Direction> direction) {
            this(node, dimension, x, y, z, yaw, pitch, direction, horizontal(direction));
        }

        /** Existing declarations remain marker-only until the owner explicitly supplies facing. */
        public Point(String node, EditorId dimension, BigDecimal x, BigDecimal y, BigDecimal z,
                     BigDecimal yaw, BigDecimal pitch) {
            this(node, dimension, x, y, z, yaw, pitch, Optional.empty());
        }

        public Position position() {
            return new Position(x, y, z);
        }
    }

    private static Optional<Direction> horizontal(Optional<Direction> direction) {
        Objects.requireNonNull(direction, "direction");
        return direction.filter(value -> value.x() != 0 || value.z() != 0)
                .map(value -> new Direction(value.x(), 0, value.z()));
    }

    private static void coordinate(BigDecimal value, String name) {
        if (!Double.isFinite(Objects.requireNonNull(value, name).doubleValue()))
            throw new IllegalArgumentException(name + " must convert to finite double");
    }

    private static void angle(BigDecimal value, String name) {
        if (!Float.isFinite(Objects.requireNonNull(value, name).floatValue()))
            throw new IllegalArgumentException(name + " must convert to finite float");
    }
}
