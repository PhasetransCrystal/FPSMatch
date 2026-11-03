package net.ptcrys.fpsmatch.editor.common.domain.weapon;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemAdapter;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemContext;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemEncoding;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict current melee codec and complete legacy matcher with explicit resource choices. */
public final class LrMeleeType implements ObjectType<LrMelee>, LegacyItemAdapter {

    public LrMeleeType() {}

    public EditorId typeId() {
        return WeaponItemValues.LR_MELEE;
    }

    @Override
    public Class<LrMelee> valueType() {
        return LrMelee.class;
    }

    @Override
    public EditorId role() {
        return EditorId.parse("fpsmatch:item");
    }

    @Override
    public EditorId codecId() {
        return EditorId.parse("fpsmatch:lrtactical_melee_json");
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Set<Integer> readableSchemas() {
        return Set.of(1);
    }

    @Override
    public LrMelee snapshot(LrMelee value) {
        if (value == null) throw WeaponCodec.field("", "lrtactical_melee");
        meleeIdentity(value.weaponId(), "/weaponId");
        value.displayId().ifPresent(id -> meleeIdentity(id, "/displayId"));
        WeaponCodec.displayCoupling(value.displayId().isPresent(), value.displayResource());
        if (value.count() < 1 || value.count() > 127) throw WeaponCodec.field("/count", "count_1_127");
        if (value.damage() < 0) throw WeaponCodec.field("/damage", "nonnegative_int32");
        return value;
    }

    @Override
    public List<Problem> validate(LrMelee value) {
        snapshot(value);
        return List.of();
    }

    @Override
    public DocumentDependencies dependencies(LrMelee value) {
        snapshot(value);
        return WeaponCodec.dependencies(value.weaponResource(), value.displayResource());
    }

    @Override
    public byte[] encode(LrMelee value) {
        snapshot(value);
        JsonObject json = new JsonObject();
        json.addProperty("weaponId", value.weaponId().toString());
        json.add("weaponResource", WeaponCodec.ref(value.weaponResource()));
        if (value.displayId().isPresent()) json.addProperty("displayId", value.displayId().get().toString());
        else json.add("displayId", JsonNull.INSTANCE);
        json.add("displayResource", WeaponCodec.ref(value.displayResource()));
        json.addProperty("count", value.count());
        json.addProperty("damage", value.damage());
        return WeaponCodec.freeze(json).canonicalUtf8();
    }

    @Override
    public LrMelee decode(int sourceSchema, byte[] encoding) {
        JsonObject json = WeaponCodec.object(WeaponCodec.read(sourceSchema, encoding), "",
                Set.of("weaponId", "weaponResource", "displayId", "displayResource", "count", "damage"), Set.of());
        EditorId id = WeaponCodec.id(json.get("weaponId"), "/weaponId");
        meleeIdentity(id, "/weaponId");
        ResourceRef resource = WeaponCodec.ref(json.get("weaponResource"), "/weaponResource");
        Optional<EditorId> display = WeaponCodec.optionalId(json.get("displayId"), "/displayId");
        display.ifPresent(value -> meleeIdentity(value, "/displayId"));
        Optional<ResourceRef> displayResource = WeaponCodec.optionalRef(json.get("displayResource"), "/displayResource");
        WeaponCodec.displayCoupling(display.isPresent(), displayResource);
        return snapshot(new LrMelee(id, resource, display, displayResource,
                WeaponCodec.count(json.get("count"), "/count"),
                WeaponCodec.nonnegative(json.get("damage"), "/damage")));
    }

    @Override
    public Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item) {
        return convert(item, LegacyItemContext.empty());
    }

    @Override
    public Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item, LegacyItemContext context) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(context, "context");
        final LrMelee value;
        try {
            value = legacy(item, context);
        } catch (DocumentCodecException invalidPayload) {
            return Optional.empty();
        }
        return Optional.of(new LegacyItemEncoding(typeId(), codecId(), schemaVersion(), encode(value)));
    }

    private LrMelee legacy(ExactJson.FrozenJson item, LegacyItemContext context) {
        JsonObject json = WeaponCodec.object(item.toJson(), "", Set.of("id", "Count", "tag"), Set.of());
        if (!WeaponCodec.id(json.get("id"), "/id").equals(EditorId.parse("lrtactical:melee"))) {
            throw WeaponCodec.field("/id", "lrtactical_melee");
        }
        int count = WeaponCodec.count(json.get("Count"), "/Count");
        JsonObject tag = WeaponCodec.object(json.get("tag"), "/tag",
                Set.of("MeleeWeaponId", "Damage"), Set.of("DisplayId"));
        EditorId id = WeaponCodec.id(tag.get("MeleeWeaponId"), "/tag/MeleeWeaponId");
        meleeIdentity(id, "/tag/MeleeWeaponId");
        int damage = WeaponCodec.nonnegative(tag.get("Damage"), "/tag/Damage");
        Optional<EditorId> display = tag.has("DisplayId") ? Optional.of(WeaponCodec.id(tag.get("DisplayId"), "/tag/DisplayId")) : Optional.empty();
        display.ifPresent(value -> meleeIdentity(value, "/tag/DisplayId"));
        ResourceRef resource = WeaponCodec.binding(context, typeId(), WeaponItemValues.LR_MELEE_ASSET,
                WeaponItemValues.LR_IMPORTER, id, "/tag/MeleeWeaponId");
        Optional<ResourceRef> displayResource = display.isPresent() ? Optional.of(WeaponCodec.binding(context, typeId(), WeaponItemValues.LR_DISPLAY_ASSET,
                WeaponItemValues.LR_IMPORTER, display.orElseThrow(), "/tag/DisplayId")) : Optional.empty();
        return new LrMelee(id, resource, display, displayResource, count, damage);
    }

    private static void meleeIdentity(EditorId id, String pointer) {
        if (id.equals(EditorId.parse("lrtactical:empty"))) throw WeaponCodec.field(pointer, "nonempty_melee_id");
    }
}
