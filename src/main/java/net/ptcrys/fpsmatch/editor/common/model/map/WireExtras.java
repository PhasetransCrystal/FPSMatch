package net.ptcrys.fpsmatch.editor.common.model.map;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

/**
 * Immutable carrier for structural unknown members of the world editor wire model (R05.0, design
 * revision R1.1 of WIRE-EXTRAS).
 *
 * <p>
 * Every component of every nested record is a non-null {@link ExactJson.FrozenJson} that must
 * express a JSON <em>object</em>. The frozen value already guarantees that its canonical text is
 * non-empty and syntactically legal JSON, so the object shape is recognised from the first
 * character of that canonical text: a JSON object always starts with {@code '{'} and no other JSON
 * value can. JSON {@code null}, an array, a string, a number and a boolean are therefore rejected
 * with {@link IllegalArgumentException} instead of being mistaken for an empty object.
 * </p>
 *
 * <p>
 * Validation never re-parses, re-freezes, clones or normalises a member, and never walks the
 * frozen tree: the supplied {@code FrozenJson} instance itself is retained, so identity is
 * preserved and construction stays constant time regardless of the value size. An empty object,
 * an empty key, arbitrary legal nested values, large integers, Unicode and escaped control
 * characters are all accepted. Keys that may currently be reserved by an owning wire object are
 * accepted here as well: this type only carries the shape, and reserved-key collision handling
 * belongs to the encoder, not to this record.
 * </p>
 *
 * <p>
 * This class is a pure carrier. It holds no mutable global state, no UUID sidecar, no source
 * wire cache and no defaulting path: a caller must supply every position explicitly, and there is
 * no "pass null and get an empty object" shortcut.
 * </p>
 */
public final class WireExtras {

    private WireExtras() {}

    /**
     * Structural unknown members of the map root object plus its boundary, first boundary and
     * second boundary positions.
     *
     * @param members               unknown members of the map root wire object
     * @param boundaryMembers       unknown members of the {@code boundary} wire object
     * @param boundaryFirstMembers  unknown members of the {@code boundary.first} wire object
     * @param boundarySecondMembers unknown members of the {@code boundary.second} wire object
     */
    public record MapExtras(ExactJson.FrozenJson members,
                            ExactJson.FrozenJson boundaryMembers,
                            ExactJson.FrozenJson boundaryFirstMembers,
                            ExactJson.FrozenJson boundarySecondMembers) {

        public MapExtras {
            WireExtras.requireObjectMember(members, "members");
            WireExtras.requireObjectMember(boundaryMembers, "boundaryMembers");
            WireExtras.requireObjectMember(boundaryFirstMembers, "boundaryFirstMembers");
            WireExtras.requireObjectMember(boundarySecondMembers, "boundarySecondMembers");
        }
    }

    /**
     * Structural unknown members of a team wire object.
     *
     * @param members unknown members of the team wire object
     */
    public record TeamExtras(ExactJson.FrozenJson members) {

        public TeamExtras {
            WireExtras.requireObjectMember(members, "members");
        }
    }

    /**
     * Structural unknown members of a spawn wire object plus its position and orientation
     * positions.
     *
     * @param members            unknown members of the spawn wire object
     * @param positionMembers    unknown members of the spawn {@code position} wire object
     * @param orientationMembers unknown members of the spawn {@code orientation} wire object
     */
    public record SpawnExtras(ExactJson.FrozenJson members,
                              ExactJson.FrozenJson positionMembers,
                              ExactJson.FrozenJson orientationMembers) {

        public SpawnExtras {
            WireExtras.requireObjectMember(members, "members");
            WireExtras.requireObjectMember(positionMembers, "positionMembers");
            WireExtras.requireObjectMember(orientationMembers, "orientationMembers");
        }
    }

    /**
     * Structural unknown members of a region wire object plus its corners, first corner and second
     * corner positions.
     *
     * @param members              unknown members of the region wire object
     * @param cornersMembers       unknown members of the region {@code corners} wire object
     * @param cornersFirstMembers  unknown members of the region {@code corners.first} wire object
     * @param cornersSecondMembers unknown members of the region {@code corners.second} wire object
     */
    public record RegionExtras(ExactJson.FrozenJson members,
                               ExactJson.FrozenJson cornersMembers,
                               ExactJson.FrozenJson cornersFirstMembers,
                               ExactJson.FrozenJson cornersSecondMembers) {

        public RegionExtras {
            WireExtras.requireObjectMember(members, "members");
            WireExtras.requireObjectMember(cornersMembers, "cornersMembers");
            WireExtras.requireObjectMember(cornersFirstMembers, "cornersFirstMembers");
            WireExtras.requireObjectMember(cornersSecondMembers, "cornersSecondMembers");
        }
    }

    /**
     * Structural unknown members of one resource reference occurrence.
     *
     * @param members unknown members of that resource reference occurrence
     */
    public record ResourceExtras(ExactJson.FrozenJson members) {

        public ResourceExtras {
            WireExtras.requireObjectMember(members, "members");
        }
    }

    /**
     * Shared object shape check of every nested record component.
     *
     * <p>
     * The check is intentionally constant time: it rejects a Java {@code null}, then reads the
     * first character of the already verified canonical text. The {@link ExactJson.FrozenJson}
     * instance is never cloned, re-parsed, re-frozen or traversed, so the exact same immutable
     * instance the caller supplied is what the record retains.
     * </p>
     *
     * @param member    frozen JSON member value supplied by the caller
     * @param component component name used in the failure detail
     * @throws IllegalArgumentException if {@code member} is null or does not express a JSON object
     */
    private static void requireObjectMember(ExactJson.FrozenJson member, String component) {
        if (member == null) {
            throw new IllegalArgumentException("WireExtras." + component + " must not be null");
        }
        if (member.canonical().charAt(0) != '{') {
            throw new IllegalArgumentException("WireExtras." + component + " must be a JSON object but was: " + member.canonical());
        }
    }
}
