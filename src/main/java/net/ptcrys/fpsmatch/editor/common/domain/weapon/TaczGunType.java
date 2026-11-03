package net.ptcrys.fpsmatch.editor.common.domain.weapon;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Strict current gun codec and complete legacy matcher with explicit resource choices. */
public final class TaczGunType implements ObjectType<TaczGun>, LegacyItemAdapter {

    public TaczGunType() {}

    public EditorId typeId() {
        return WeaponItemValues.TACZ_GUN;
    }

    @Override
    public Class<TaczGun> valueType() {
        return TaczGun.class;
    }

    @Override
    public EditorId role() {
        return EditorId.parse("fpsmatch:item");
    }

    @Override
    public EditorId codecId() {
        return EditorId.parse("fpsmatch:tacz_gun_json");
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
    public TaczGun snapshot(TaczGun value) {
        if (value == null) throw WeaponCodec.field("", "tacz_gun");
        gunIdentity(value.gunId(), "/gunId");
        WeaponCodec.displayCoupling(gunDisplay(value.displayId()), value.displayResource());
        if (value.count() < 1 || value.count() > 127) throw WeaponCodec.field("/count", "count_1_127");
        if (value.ammoCount() < 0) throw WeaponCodec.field("/ammoCount", "nonnegative_int32");
        if (value.dummyAmmo().isPresent() && value.dummyAmmo().getAsInt() < 0) {
            throw WeaponCodec.field("/dummyAmmo", "nonnegative_int32");
        }
        if (value.maxDummyAmmo().isPresent() && value.maxDummyAmmo().getAsInt() < 0) {
            throw WeaponCodec.field("/maxDummyAmmo", "nonnegative_int32");
        }
        return value;
    }

    @Override
    public List<Problem> validate(TaczGun value) {
        snapshot(value);
        return List.of();
    }

    @Override
    public DocumentDependencies dependencies(TaczGun value) {
        snapshot(value);
        return WeaponCodec.dependencies(value.gunResource(), value.displayResource());
    }

    @Override
    public byte[] encode(TaczGun value) {
        snapshot(value);
        JsonObject json = new JsonObject();
        json.addProperty("gunId", value.gunId().toString());
        json.add("gunResource", WeaponCodec.ref(value.gunResource()));
        if (value.displayId().isPresent()) json.addProperty("displayId", value.displayId().get().toString());
        else json.add("displayId", JsonNull.INSTANCE);
        json.add("displayResource", WeaponCodec.ref(value.displayResource()));
        json.addProperty("count", value.count());
        json.addProperty("fireMode", value.fireMode().name());
        json.addProperty("bulletInBarrel", value.bulletInBarrel());
        json.addProperty("ammoCount", value.ammoCount());
        if (value.dummyAmmo().isPresent()) json.addProperty("dummyAmmo", value.dummyAmmo().getAsInt());
        else json.add("dummyAmmo", JsonNull.INSTANCE);
        if (value.maxDummyAmmo().isPresent()) json.addProperty("maxDummyAmmo", value.maxDummyAmmo().getAsInt());
        else json.add("maxDummyAmmo", JsonNull.INSTANCE);
        return WeaponCodec.freeze(json).canonicalUtf8();
    }

    @Override
    public TaczGun decode(int sourceSchema, byte[] encoding) {
        JsonObject json = WeaponCodec.object(WeaponCodec.read(sourceSchema, encoding), "",
                Set.of("gunId", "gunResource", "displayId", "displayResource", "count", "fireMode",
                        "bulletInBarrel", "ammoCount", "dummyAmmo", "maxDummyAmmo"),
                Set.of());
        EditorId id = WeaponCodec.id(json.get("gunId"), "/gunId");
        gunIdentity(id, "/gunId");
        ResourceRef resource = WeaponCodec.ref(json.get("gunResource"), "/gunResource");
        Optional<EditorId> display = WeaponCodec.optionalId(json.get("displayId"), "/displayId");
        Optional<ResourceRef> displayResource = WeaponCodec.optionalRef(json.get("displayResource"), "/displayResource");
        WeaponCodec.displayCoupling(gunDisplay(display), displayResource);
        return snapshot(new TaczGun(id, resource, display, displayResource,
                WeaponCodec.count(json.get("count"), "/count"),
                WeaponCodec.fireMode(json.get("fireMode"), "/fireMode"),
                WeaponCodec.bool(json.get("bulletInBarrel"), "/bulletInBarrel", false),
                WeaponCodec.nonnegative(json.get("ammoCount"), "/ammoCount"),
                WeaponCodec.optionalInt(json.get("dummyAmmo"), "/dummyAmmo"),
                WeaponCodec.optionalInt(json.get("maxDummyAmmo"), "/maxDummyAmmo")));
    }

    @Override
    public Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item) {
        return convert(item, LegacyItemContext.empty());
    }

    @Override
    public Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item, LegacyItemContext context) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(context, "context");
        final TaczGun value;
        try {
            value = legacy(item, context);
        } catch (DocumentCodecException invalidPayload) {
            return Optional.empty();
        }
        return Optional.of(new LegacyItemEncoding(typeId(), codecId(), schemaVersion(), encode(value),
                List.of(normalization(value))));
    }

    private TaczGun legacy(ExactJson.FrozenJson item, LegacyItemContext context) {
        JsonObject json = WeaponCodec.object(item.toJson(), "", Set.of("id", "Count", "tag"), Set.of());
        if (!WeaponCodec.id(json.get("id"), "/id").equals(EditorId.parse("tacz:modern_kinetic_gun"))) {
            throw WeaponCodec.field("/id", "tacz_modern_kinetic_gun");
        }
        int count = WeaponCodec.count(json.get("Count"), "/Count");
        JsonObject tag = WeaponCodec.object(json.get("tag"), "/tag",
                Set.of("GunId", "GunFireMode", "HasBulletInBarrel", "GunCurrentAmmoCount"),
                Set.of("DummyAmmo", "MaxDummyAmmo", "GunDisplayId"));
        EditorId id = WeaponCodec.id(tag.get("GunId"), "/tag/GunId");
        gunIdentity(id, "/tag/GunId");
        TaczGun.FireMode fireMode = WeaponCodec.fireMode(tag.get("GunFireMode"), "/tag/GunFireMode");
        boolean barrel = WeaponCodec.bool(tag.get("HasBulletInBarrel"), "/tag/HasBulletInBarrel", true);
        int ammo = WeaponCodec.nonnegative(tag.get("GunCurrentAmmoCount"), "/tag/GunCurrentAmmoCount");
        OptionalInt dummy = tag.has("DummyAmmo") ? OptionalInt.of(WeaponCodec.nonnegative(tag.get("DummyAmmo"), "/tag/DummyAmmo")) : OptionalInt.empty();
        OptionalInt max = tag.has("MaxDummyAmmo") ? OptionalInt.of(WeaponCodec.nonnegative(tag.get("MaxDummyAmmo"), "/tag/MaxDummyAmmo")) : OptionalInt.empty();
        Optional<EditorId> display = tag.has("GunDisplayId") ? Optional.of(WeaponCodec.id(tag.get("GunDisplayId"), "/tag/GunDisplayId")) : Optional.empty();
        ResourceRef resource = WeaponCodec.binding(context, typeId(), WeaponItemValues.TACZ_GUN_ASSET,
                WeaponItemValues.TACZ_IMPORTER, id, "/tag/GunId");
        Optional<ResourceRef> displayResource = gunDisplay(display) ? Optional.of(WeaponCodec.binding(context, typeId(), WeaponItemValues.TACZ_DISPLAY_ASSET,
                WeaponItemValues.TACZ_IMPORTER, display.orElseThrow(), "/tag/GunDisplayId")) : Optional.empty();
        return new TaczGun(id, resource, display, displayResource, count, fireMode, barrel, ammo, dummy, max);
    }

    private static void gunIdentity(EditorId id, String pointer) {
        if (id.equals(EditorId.parse("tacz:empty"))) throw WeaponCodec.field(pointer, "nonempty_gun_id");
    }

    private static boolean gunDisplay(Optional<EditorId> display) {
        return display.isPresent() && !display.get().equals(EditorId.parse("tacz:default"));
    }

    private static Problem normalization(TaczGun value) {
        int fields = 1 + (value.dummyAmmo().isPresent() ? 1 : 0) + (value.maxDummyAmmo().isPresent() ? 1 : 0);
        return new Problem(Problem.Severity.WARNING, EditorId.parse("fpsmatch:weapons/numeric_normalization"),
                "weapons:/tag", new TextContent.Message("fpsmatch.editor.weapons.numeric_normalization",
                        List.of(new TextContent.LiteralArgument(value.gunId().toString()),
                                new TextContent.NumberArgument(BigDecimal.valueOf(fields)))));
    }
}
