package net.ptcrys.fpsmatch.editor.common.model.map;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;

/**
 * One team of a map definition (fixed task card R05.1, revision R1.3).
 *
 * <p>
 * A team is the minimal persistent unit this card fixes: a typed identity, a single line display
 * name and two frozen JSON carriers for members that this revision of the model does not interpret.
 * Nothing here reads a live team, a player, a world or a registry.
 * </p>
 *
 * <p>
 * <strong>Identity is never derived.</strong> {@code id} is the only identity of this record. It
 * is supplied by the caller, is never generated, and is never derived from the name, from an index
 * or from a position in a list, so renaming a team cannot silently re-identify it. This type also
 * does not decide whether the id exists in an owning map: existence, cross list uniqueness and
 * referential integrity are aggregate questions that belong to the cross reference point R05.3, and
 * guessing a registry answer inside a single point constructor is exactly what this card forbids.
 * </p>
 *
 * <p>
 * <strong>Null versus JSON null.</strong> Every member is required to be a non-null Java
 * reference and a null argument is rejected with {@link IllegalArgumentException}. That is
 * deliberately different from a JSON {@code null} written inside {@code config} or
 * {@code extensions}: the latter is a legal frozen JSON value and is carried verbatim, whereas a
 * null Java reference would mean "this member was never supplied". The two are never conflated and
 * no member is defaulted to an empty value.
 * </p>
 *
 * <p>
 * <strong>Frozen JSON is carried, not reinterpreted.</strong> {@code config} and
 * {@code extensions} only have to be non-null {@link ExactJson.FrozenJson} references. The
 * structural layer accepts <em>any</em> already legal frozen JSON value here, including an object,
 * an array, a primitive and JSON {@code null}: a provider's formal schema may later require an
 * object with particular fields and report a business problem, but this record must not silently
 * discard a value merely because it is not an object, because doing so would destroy author content
 * that the wire model is obliged to preserve. {@code FrozenJson} is already deeply immutable, so
 * the exact instance supplied is retained and no defensive clone happens here.
 * </p>
 *
 * <p>
 * <strong>Wire members.</strong> {@code wireExtras} is a required, non-null, already frozen
 * carrier whose slots are themselves object-only. It is kept as its own member and is never merged
 * into {@code extensions} and never hung onto a global map keyed by a current index, so two
 * different unknown values stay distinguishable through {@code equals} and {@code hashCode} of this
 * record.
 * </p>
 *
 * <p>
 * <strong>Name policy.</strong> The name passes {@code nonEmpty = true}: a genuinely empty string
 * is rejected, while an ordinary space is a non-empty name and is kept exactly as given. There is no
 * trim, no normalisation and no fallback label, because a fallback belongs to the UI and is never
 * written back into the model.
 * </p>
 *
 * @param id         the team identity, never null and never derived from the other members
 * @param name       the single line team name, at most 512 UTF-8 bytes and non-empty
 * @param config     frozen provider configuration, any legal frozen JSON value
 * @param extensions frozen unknown members, any legal frozen JSON value
 * @param wireExtras unknown members of the team wire object
 */
public record TeamDefinition(Ids.TeamId id, String name,
                             ExactJson.FrozenJson config, ExactJson.FrozenJson extensions,
                             WireExtras.TeamExtras wireExtras) {

    /**
     * Rejects every null or illegal member. No member is defaulted, generated or repaired.
     */
    public TeamDefinition {
        if (id == null) {
            throw new IllegalArgumentException("TeamDefinition.id must not be null");
        }
        name = MapScalars.requireName(name, true);
        if (config == null) {
            throw new IllegalArgumentException("TeamDefinition.config must not be null");
        }
        if (extensions == null) {
            throw new IllegalArgumentException("TeamDefinition.extensions must not be null");
        }
        if (wireExtras == null) {
            throw new IllegalArgumentException("TeamDefinition.wireExtras must not be null");
        }
    }
}
