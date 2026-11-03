package net.ptcrys.fpsmatch.editor.common.model.map;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Orientation;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;

/**
 * One spawn point of a map definition (fixed task card R05.1, revision R1.3).
 *
 * <p>
 * A spawn binds a typed identity, a display name, the owning team identity, an explicit
 * dimension, a finite position and orientation, and two frozen carriers for members this revision
 * does not interpret. It never reads a world, a registry, a live team or a provider.
 * </p>
 *
 * <p>
 * <strong>Identity is never derived or guessed.</strong> {@code id} is the only identity here and
 * is supplied by the caller. {@code teamId} is a required reference to the owning team, not a name:
 * this type does not look the team up, does not check that it exists and does not infer it from the
 * spawn name or from a list index. Whether two spawns share an id, and whether {@code teamId} refers
 * to a real team of the same map, are aggregate questions owned by R05.3.
 * </p>
 *
 * <p>
 * <strong>Null versus JSON null.</strong> Every member must be a non-null Java reference and a
 * null argument is rejected with {@link IllegalArgumentException}, including the case of an empty
 * spawn name, which is legal and different from a missing one. A JSON {@code null} <em>inside</em>
 * {@code extensions} or {@code wireExtras} is a legal frozen JSON value and is carried verbatim.
 * No member is defaulted.
 * </p>
 *
 * <p>
 * <strong>Signed zero is normalised to positive zero at this boundary.</strong> Each of the five
 * floating point components this record owns - the three position components and the two
 * orientation angles - is passed through {@code v == 0 ? +0 : v}. A negative zero is not an illegal
 * input; it is legal and becomes the single canonical zero the domain stores. The reason is wire
 * equivalence: the numeric wire form carries one zero, so a value that kept negative zero would
 * encode and then decode into a record that is not {@code equals} to the original, and two
 * different records could share one canonical digest. Normalising here, at the single construction
 * boundary that creation, import, editing, duplication and history restore all pass through, is
 * what makes a negative zero input and a positive zero input produce an {@code equals} record with
 * the same {@code hashCode}. It is deliberately <em>not</em> repaired inside the codec and it does
 * not introduce a sign field on the wire.
 * </p>
 *
 * <p>
 * <strong>Only negative zero changes; every other bit pattern survives.</strong> The test for
 * negative zero is a raw bit comparison against {@code Long.MIN_VALUE} for a {@code double} and
 * against {@code Integer.MIN_VALUE} for a {@code float}, so no epsilon is involved and no small,
 * subnormal or large finite value can be flattened to zero. All non-zero components - including
 * negative values, signed subnormals and values far outside any playable range - keep their exact
 * bits. A finite pitch is never clamped: 120 degrees stays 120 degrees. The component types
 * themselves are untouched by this rule: {@link Position} and {@link Orientation} still store
 * whatever they were given, negative zero included, and neither the frozen exact JSON number rules
 * nor the geometry records are modified. When no component of a geometry carries a negative zero the
 * supplied instance is reused as is, so no gratuitous copy is made.
 * </p>
 *
 * <p>
 * <strong>Frozen JSON and wire members.</strong> {@code extensions} must be a non-null
 * {@link ExactJson.FrozenJson} and may hold any legal frozen JSON value, object or not. The
 * {@code wireExtras} member is a required frozen carrier whose own slots are object-only and is
 * reused as supplied; unknown members of the spawn object and of its position and orientation
 * objects stay in their own slots and are never merged into {@code extensions} and never hung onto
 * a global map keyed by a current index, so different unknown values change {@code equals} and
 * {@code hashCode} of this record.
 * </p>
 *
 * @param id          the spawn identity, never null and never derived from the other members
 * @param name        the spawn display name, at most 512 UTF-8 bytes; the empty string is legal
 * @param teamId      the owning team identity, never null and never resolved here
 * @param dimensionId explicit {@code namespace:path}, never completed with a default namespace
 * @param position    the finite spawn position, with signed zero normalised at this boundary
 * @param orientation the finite spawn look direction, with signed zero normalised at this boundary
 * @param extensions  frozen unknown members, any legal frozen JSON value
 * @param wireExtras  unknown members of the spawn, position and orientation wire objects
 */
public record SpawnDefinition(Ids.ObjectId id, String name, Ids.TeamId teamId,
                              String dimensionId, Position position, Orientation orientation,
                              ExactJson.FrozenJson extensions, WireExtras.SpawnExtras wireExtras) {

    /** Raw bit pattern of a {@code double} negative zero. */
    private static final long NEGATIVE_ZERO_DOUBLE_BITS = Long.MIN_VALUE;

    /** Raw bit pattern of a {@code float} negative zero. */
    private static final int NEGATIVE_ZERO_FLOAT_BITS = Integer.MIN_VALUE;

    /**
     * Rejects every null or illegal member and normalises the signed zero of both geometry members.
     */
    public SpawnDefinition {
        if (id == null) {
            throw new IllegalArgumentException("SpawnDefinition.id must not be null");
        }
        name = MapScalars.requireName(name, false);
        if (teamId == null) {
            throw new IllegalArgumentException("SpawnDefinition.teamId must not be null");
        }
        dimensionId = MapScalars.requireDimension(dimensionId);
        position = normalisePosition(position);
        orientation = normaliseOrientation(orientation);
        if (extensions == null) {
            throw new IllegalArgumentException("SpawnDefinition.extensions must not be null");
        }
        if (wireExtras == null) {
            throw new IllegalArgumentException("SpawnDefinition.wireExtras must not be null");
        }
    }

    /**
     * Returns a position whose signed zeros are canonical, reusing the supplied instance when it has
     * none.
     *
     * @param position the caller supplied position
     * @return the same instance if no component is a negative zero, otherwise a rebuilt position
     * @throws IllegalArgumentException if {@code position} is null
     */
    private static Position normalisePosition(Position position) {
        if (position == null) {
            throw new IllegalArgumentException("SpawnDefinition.position must not be null");
        }
        boolean negativeZeroX = isNegativeZero(position.x());
        boolean negativeZeroY = isNegativeZero(position.y());
        boolean negativeZeroZ = isNegativeZero(position.z());
        if (!negativeZeroX && !negativeZeroY && !negativeZeroZ) {
            return position;
        }
        return new Position(negativeZeroX ? 0.0d : position.x(),
                negativeZeroY ? 0.0d : position.y(),
                negativeZeroZ ? 0.0d : position.z());
    }

    /**
     * Returns an orientation whose signed zeros are canonical, reusing the supplied instance when it
     * has none.
     *
     * @param orientation the caller supplied orientation
     * @return the same instance if neither angle is a negative zero, otherwise a rebuilt orientation
     * @throws IllegalArgumentException if {@code orientation} is null
     */
    private static Orientation normaliseOrientation(Orientation orientation) {
        if (orientation == null) {
            throw new IllegalArgumentException("SpawnDefinition.orientation must not be null");
        }
        boolean negativeZeroYaw = isNegativeZero(orientation.yaw());
        boolean negativeZeroPitch = isNegativeZero(orientation.pitch());
        if (!negativeZeroYaw && !negativeZeroPitch) {
            return orientation;
        }
        return new Orientation(negativeZeroYaw ? 0.0f : orientation.yaw(),
                negativeZeroPitch ? 0.0f : orientation.pitch());
    }

    /**
     * Tests a double for negative zero by raw bit pattern, so that no epsilon tolerance and no value
     * comparison can be involved.
     */
    private static boolean isNegativeZero(double value) {
        return Double.doubleToRawLongBits(value) == NEGATIVE_ZERO_DOUBLE_BITS;
    }

    /**
     * Tests a float for negative zero by raw bit pattern, so that no epsilon tolerance and no value
     * comparison can be involved.
     */
    private static boolean isNegativeZero(float value) {
        return Float.floatToRawIntBits(value) == NEGATIVE_ZERO_FLOAT_BITS;
    }
}
