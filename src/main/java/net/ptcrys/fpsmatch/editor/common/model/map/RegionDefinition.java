package net.ptcrys.fpsmatch.editor.common.model.map;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;

/**
 * One region of a map definition (fixed task card R05.2, revision R1.1).
 *
 * <p>
 * A region binds a typed identity, a display name, an explicit kind, an axis aligned box and two
 * frozen carriers for members this revision does not interpret. It never reads a world, a
 * registry, a resource library or a provider.
 * </p>
 *
 * <p>
 * <strong>Identity is never derived or guessed.</strong> {@code id} is the only identity here
 * and is supplied by the caller. This type does not allocate an id from the region name or from
 * the corner coordinates, does not consult a Counter-Strike map or a provider catalogue, and does
 * not guess which corner is "A" and which is "B". Two regions that happen to share a name, a kind
 * and a box are still two distinct values whenever their ids differ, and whether two regions of one
 * map share an id is an aggregate question owned by R05.3 rather than a construction failure.
 * </p>
 *
 * <p>
 * <strong>Corner order is preserved exactly.</strong> {@code corners} is carried through
 * untouched: the two end points keep the order the caller supplied, are never swapped or
 * reordered, and are never normalised into a minimum/maximum pair. The frozen geometry record
 * already guarantees that both corners are non-null, so this type adds no second geometry rule and
 * performs no coordinate clamping.
 * </p>
 *
 * <p>
 * <strong>Null versus JSON null.</strong> Every member must be a non-null Java reference and a
 * null argument is rejected with {@link IllegalArgumentException}, including the case of an empty
 * region name, which is legal and different from a missing one. A JSON {@code null} <em>inside</em>
 * {@code config} or {@code extensions} is a legal frozen JSON value and is carried verbatim: the
 * two carriers accept any legal frozen shape, object or not, and an unknown primitive member is
 * never dropped. No member is defaulted.
 * </p>
 *
 * <p>
 * <strong>Kind is syntax only here.</strong> {@code kindId} must be an explicit
 * {@code namespace:path} with a non-empty namespace and a non-empty path. An unknown but
 * syntactically legal kind passes this structure, and whether a provider supports that kind is a
 * business question answered later; this type never consults a registry and never checks whether
 * the kind is reserved or whether a merge rule applies to it.
 * </p>
 *
 * <p>
 * <strong>Frozen JSON and wire members.</strong> {@code config} and {@code extensions} must be
 * non-null {@link ExactJson.FrozenJson} values and may hold any legal frozen JSON shape. The
 * {@code wireExtras} member is a required frozen carrier whose own slots are object-only and is
 * reused as supplied. Unknown members of the region object, of its {@code corners} object and of
 * each corner object stay in their own slots: they are never merged into {@code extensions} or
 * {@code config}, and the unknown members belonging to {@code first} and to {@code second} are
 * never mixed into one shared slot, so different unknown values change {@code equals} and
 * {@code hashCode} of this record.
 * </p>
 *
 * @param id         the region identity, never null and never derived from the other members
 * @param name       the region display name, at most 512 UTF-8 bytes; the empty string is legal
 * @param kindId     explicit {@code namespace:path} kind, never completed with a default namespace
 * @param corners    the axis aligned box, with the supplied corner order preserved
 * @param config     frozen configuration value, any legal frozen JSON shape
 * @param extensions frozen unknown members, any legal frozen JSON shape
 * @param wireExtras unknown members of the region, corners, first corner and second corner objects
 */
public record RegionDefinition(Ids.ObjectId id, String name, String kindId,
                               BoxCorners corners, ExactJson.FrozenJson config, ExactJson.FrozenJson extensions,
                               WireExtras.RegionExtras wireExtras) {

    /**
     * Rejects every null or illegal member. No member is normalised or defaulted.
     */
    public RegionDefinition {
        if (id == null) {
            throw new IllegalArgumentException("RegionDefinition.id must not be null");
        }
        name = MapScalars.requireName(name, false);
        kindId = MapScalars.requireNamespaced(kindId);
        if (corners == null) {
            throw new IllegalArgumentException("RegionDefinition.corners must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("RegionDefinition.config must not be null");
        }
        if (extensions == null) {
            throw new IllegalArgumentException("RegionDefinition.extensions must not be null");
        }
        if (wireExtras == null) {
            throw new IllegalArgumentException("RegionDefinition.wireExtras must not be null");
        }
    }
}
