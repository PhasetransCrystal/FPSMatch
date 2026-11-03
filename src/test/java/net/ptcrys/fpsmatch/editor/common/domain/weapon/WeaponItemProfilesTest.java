package net.ptcrys.fpsmatch.editor.common.domain.weapon;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.ptcrys.fpsmatch.editor.testutil.MinecraftTranslationFormat;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.types.*;
import net.ptcrys.fpsmatch.editor.core.types.values.*;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItemModules;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.*;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import org.junit.jupiter.api.Test;

/** Synthetic metadata proves structural binding only, never production asset support. */
class WeaponItemProfilesTest {
    private static final EditorId GUN = id("foreign:gun"), MELEE = id("foreign:knife"), DISPLAY = id("foreign:display");
    private static final ResourceRef REF = new ResourceRef(new UUID(40, 1), new UUID(40, 2));
    private static final ResourceRef DISPLAY_REF = new ResourceRef(new UUID(40, 3), new UUID(40, 4));
    private static final TaczGunType GUN_TYPE = new TaczGunType();
    private static final LrMeleeType MELEE_TYPE = new LrMeleeType();

    @Test void completeValuesRoundTripEveryOptionalCombinationAndExactDependencies() {
        for (var mode : TaczGun.FireMode.values()) for (boolean barrel : List.of(false, true))
                for (OptionalInt dummy : List.of(OptionalInt.empty(), OptionalInt.of(0), OptionalInt.of(Integer.MAX_VALUE)))
                for (OptionalInt max : List.of(OptionalInt.empty(), OptionalInt.of(0), OptionalInt.of(1)))
                for (int display : List.of(0, 1, 2)) {
            var value = gun(display, mode, barrel, dummy, max);
            byte[] wire = GUN_TYPE.encode(value);
            assertEquals(value, GUN_TYPE.decode(1, wire)); assertEquals(value, GUN_TYPE.snapshot(value)); assertTrue(GUN_TYPE.validate(value).isEmpty());
            assertArrayEquals(ExactJson.parse(new String(wire, StandardCharsets.UTF_8)).canonicalUtf8(), wire);
            assertEquals(Set.of("gunId", "gunResource", "displayId", "displayResource", "count", "fireMode", "bulletInBarrel", "ammoCount", "dummyAmmo", "maxDummyAmmo"), object(wire).keySet());
            var deps = GUN_TYPE.dependencies(value); assertEquals(display == 2 ? Set.of(REF, DISPLAY_REF) : Set.of(REF), deps.resources());
            assertTrue(deps.contributions().isEmpty()); assertTrue(deps.documents().isEmpty()); assertTrue(GUN_TYPE.references(value).isEmpty());
            assertEquals(Integer.MAX_VALUE, value.ammoCount(), "no magazine capacity clamp");
        }
        for (boolean display : List.of(false, true)) for (int damage : List.of(0, Integer.MAX_VALUE)) {
            var value = new LrMelee(MELEE, REF, display ? Optional.of(DISPLAY) : Optional.empty(), display ? Optional.of(DISPLAY_REF) : Optional.empty(), 127, damage);
            assertEquals(value, MELEE_TYPE.snapshot(value)); assertTrue(MELEE_TYPE.validate(value).isEmpty());
            byte[] wire = MELEE_TYPE.encode(value); assertEquals(value, MELEE_TYPE.decode(1, wire));
            assertEquals(Set.of("weaponId", "weaponResource", "displayId", "displayResource", "count", "damage"), object(wire).keySet());
            assertEquals(display ? Set.of(REF, DISPLAY_REF) : Set.of(REF), MELEE_TYPE.dependencies(value).resources());
            assertTrue(MELEE_TYPE.references(value).isEmpty());
        }
        var alias = new TaczGun(GUN, REF, Optional.of(DISPLAY), Optional.of(REF), 1, TaczGun.FireMode.SEMI, false, 0, OptionalInt.empty(), OptionalInt.empty());
        assertEquals(Set.of(REF), GUN_TYPE.dependencies(alias).resources());
        assertEquals(id("fpsmatch:item"), GUN_TYPE.role()); assertEquals(id("fpsmatch:item"), MELEE_TYPE.role());
        assertEquals(Set.of(1), GUN_TYPE.readableSchemas()); assertEquals(Set.of(1), MELEE_TYPE.readableSchemas());
    }

    @Test void recordsRejectImpossibleValuesDisplayPairingAndNullState() {
        for (int count : List.of(0, -1, 128, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> new TaczGun(GUN, REF, Optional.empty(), Optional.empty(), count, TaczGun.FireMode.AUTO, false, 0, OptionalInt.empty(), OptionalInt.empty()));
            assertThrows(IllegalArgumentException.class, () -> new LrMelee(MELEE, REF, Optional.empty(), Optional.empty(), count, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new TaczGun(id("tacz:empty"), REF, Optional.empty(), Optional.empty(), 1, TaczGun.FireMode.AUTO, false, 0, OptionalInt.empty(), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LrMelee(id("lrtactical:empty"), REF, Optional.empty(), Optional.empty(), 1, 0));
        for (int field : List.of(0, 1, 2)) assertThrows(IllegalArgumentException.class, () -> new TaczGun(GUN, REF, Optional.empty(), Optional.empty(), 1, TaczGun.FireMode.AUTO, false,
                field == 0 ? -1 : 0, field == 1 ? OptionalInt.of(-1) : OptionalInt.empty(), field == 2 ? OptionalInt.of(-1) : OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LrMelee(MELEE, REF, Optional.empty(), Optional.empty(), 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new LrMelee(MELEE, REF, Optional.of(id("lrtactical:empty")), Optional.of(DISPLAY_REF), 1, 0));
        for (Optional<EditorId> display : List.of(Optional.<EditorId>empty(), Optional.of(id("tacz:default"))))
            assertThrows(IllegalArgumentException.class, () -> new TaczGun(GUN, REF, display, Optional.of(DISPLAY_REF), 1, TaczGun.FireMode.AUTO, false, 0, OptionalInt.empty(), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new TaczGun(GUN, REF, Optional.of(DISPLAY), Optional.empty(), 1, TaczGun.FireMode.AUTO, false, 0, OptionalInt.empty(), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LrMelee(MELEE, REF, Optional.of(MELEE), Optional.empty(), 1, 0));
        assertThrows(NullPointerException.class, () -> new TaczGun(GUN, REF, null, Optional.empty(), 1, TaczGun.FireMode.AUTO, false, 0, OptionalInt.empty(), OptionalInt.empty()));
        assertThrows(NullPointerException.class, () -> new TaczGun(GUN, REF, Optional.empty(), Optional.empty(), 1, null, false, 0, OptionalInt.empty(), OptionalInt.empty()));
        assertThrows(NullPointerException.class, () -> new TaczGun(GUN, REF, Optional.empty(), Optional.empty(), 1, TaczGun.FireMode.AUTO, false, 0, null, OptionalInt.empty()));
        assertThrows(NullPointerException.class, () -> new LrMelee(null, REF, Optional.empty(), Optional.empty(), 1, 0));
        assertThrows(NullPointerException.class, () -> new LrMelee(MELEE, null, Optional.empty(), Optional.empty(), 1, 0));
        assertThrows(NullPointerException.class, () -> new LrMelee(MELEE, REF, Optional.empty(), null, 1, 0));
    }

    @Test void currentCodecRejectsAllMissingUnknownCoercedFieldsAndNoncanonicalReferences() {
        JsonObject goodGun = object(GUN_TYPE.encode(gun(2, TaczGun.FireMode.BURST, true, OptionalInt.of(50), OptionalInt.of(1))));
        JsonObject goodMelee = object(MELEE_TYPE.encode(new LrMelee(MELEE, REF, Optional.of(DISPLAY), Optional.of(DISPLAY_REF), 1, 0)));
        for (JsonObject good : List.of(goodGun, goodMelee)) {
            boolean gun = good.has("gunId");
            for (String key : good.keySet()) { var bad = good.deepCopy(); bad.remove(key); invalid(gun, bad); }
            var extra = good.deepCopy(); extra.addProperty("future", 1); invalid(gun, extra);
            for (String key : good.keySet()) {
                if (key.equals("displayId") || key.equals("displayResource") || key.equals("dummyAmmo") || key.equals("maxDummyAmmo")) continue;
                var bad = good.deepCopy(); bad.add(key, JsonNull.INSTANCE); invalid(gun, bad);
            }
            for (String field : gun ? List.of("count", "ammoCount", "dummyAmmo", "maxDummyAmmo") : List.of("count", "damage"))
                for (String value : List.of("-1", "2147483648", "0.5", "\"1\"", "true", "[]", "{}")) {
                    var bad = good.deepCopy(); bad.add(field, ExactJson.parse(value).toJson()); invalid(gun, bad);
                }
            for (String refField : gun ? List.of("gunResource", "displayResource") : List.of("weaponResource", "displayResource"))
                for (String uuid : List.of("0-0-0-0-1", "00000000-0000-0000-0000-00000000000A", "invalid")) {
                    var bad = good.deepCopy(); bad.getAsJsonObject(refField).addProperty("versionId", uuid); invalid(gun, bad);
                }
            var badRef = good.deepCopy(); badRef.getAsJsonObject(gun ? "gunResource" : "weaponResource").addProperty("extra", "x"); invalid(gun, badRef);
            var pair = good.deepCopy(); pair.add("displayId", JsonNull.INSTANCE); invalid(gun, pair);
            pair = good.deepCopy(); pair.add("displayResource", JsonNull.INSTANCE); invalid(gun, pair);
        }
        for (String mode : List.of("UNKNOWN", "auto", "", "1")) { var bad = goodGun.deepCopy(); bad.addProperty("fireMode", mode); invalid(true, bad); }
        for (String value : List.of("0", "1", "\"true\"", "null")) { var bad = goodGun.deepCopy(); bad.add("bulletInBarrel", ExactJson.parse(value).toJson()); invalid(true, bad); }
        for (int count : List.of(0, 128)) { var bad = goodGun.deepCopy(); bad.addProperty("count", count); invalid(true, bad); }
        for (String root : List.of("null", "[]")) field(assertThrows(DocumentCodecException.class, () -> GUN_TYPE.decode(1, bytes(root))));
    }

    @Test void legacyRequiresExactResourceMetadataAndPreservesOptionalFieldSemantics() {
        for (boolean gun : List.of(false, true)) {
            var context = context(gun, true, "foreign:gun", null, null, null);
            if (!gun) context = context(false, true, "foreign:knife", null, null, null);
            final var selected = context;
            var input = legacy(gun); var adapter = gun ? (LegacyItemAdapter)GUN_TYPE : MELEE_TYPE;
            assertTrue(adapter.convert(ExactJson.freeze(input)).isEmpty());
            assertThrows(NullPointerException.class, () -> adapter.convert(ExactJson.freeze(input), null));
            assertThrows(NullPointerException.class, () -> adapter.convert(null, selected));
            var encoded = adapter.convert(ExactJson.freeze(input), selected).orElseThrow();
            assertEquals(gun ? id("fpsmatch:tacz_gun") : id("fpsmatch:lrtactical_melee"), encoded.typeId());
            if (gun) {
                assertEquals(1, encoded.notices().size()); var notice = encoded.notices().get(0);
                assertEquals(Problem.Severity.WARNING, notice.severity()); assertEquals("weapons:/tag", notice.target());
                var msg = localized(notice, "numeric_normalization"); assertEquals(List.of(new TextContent.LiteralArgument("foreign:gun"), number(1)), msg.arguments());
            } else assertTrue(encoded.notices().isEmpty());
            for (LegacyItemContext invalid : List.of(LegacyItemContext.empty(), context(gun, true, "other:asset", null, null, null),
                    context(gun, true, gun ? "foreign:gun" : "foreign:knife", id("wrong:type"), null, null),
                    context(gun, true, gun ? "foreign:gun" : "foreign:knife", null, id("wrong:importer"), null),
                    context(gun, true, gun ? "foreign:gun" : "foreign:knife", null, null, "2")))
                assertTrue(adapter.convert(ExactJson.freeze(input), invalid).isEmpty());
            var unknown = input.deepCopy(); unknown.getAsJsonObject("tag").addProperty("future", 1); assertTrue(adapter.convert(ExactJson.freeze(unknown), selected).isEmpty());
            var caps = input.deepCopy(); caps.add("ForgeCaps", new JsonObject()); assertTrue(adapter.convert(ExactJson.freeze(caps), selected).isEmpty());
            for (String required : input.getAsJsonObject("tag").keySet()) { var bad = input.deepCopy(); bad.getAsJsonObject("tag").remove(required); assertTrue(adapter.convert(ExactJson.freeze(bad), selected).isEmpty()); }
            var display = input.deepCopy(); display.getAsJsonObject("tag").addProperty(gun ? "GunDisplayId" : "DisplayId", "foreign:display");
            assertTrue(adapter.convert(ExactJson.freeze(display), context(gun, false, gun ? "foreign:gun" : "foreign:knife", null, null, null)).isEmpty());
            assertTrue(adapter.convert(ExactJson.freeze(display), selected).isPresent());
        }
        for (String bool : List.of("true", "false", "0", "1", "1.0")) { var item = legacy(true); item.getAsJsonObject("tag").add("HasBulletInBarrel", ExactJson.parse(bool).toJson()); assertTrue(GUN_TYPE.convert(ExactJson.freeze(item), context(true, true, "foreign:gun", null, null, null)).isPresent()); }
        for (String bool : List.of("2", "-1", "0.5", "\"true\"", "null")) { var item = legacy(true); item.getAsJsonObject("tag").add("HasBulletInBarrel", ExactJson.parse(bool).toJson()); assertTrue(GUN_TYPE.convert(ExactJson.freeze(item), context(true, true, "foreign:gun", null, null, null)).isEmpty()); }
        for (int flags : List.of(0, 1, 2, 3)) { var item = legacy(true); if ((flags & 1) != 0) item.getAsJsonObject("tag").addProperty("DummyAmmo", 1000); if ((flags & 2) != 0) item.getAsJsonObject("tag").addProperty("MaxDummyAmmo", 1);
            var encoded = GUN_TYPE.convert(ExactJson.freeze(item), context(true, true, "foreign:gun", null, null, null)).orElseThrow();
            assertEquals(number(1 + Integer.bitCount(flags)), ((TextContent.Message)encoded.notices().get(0).message()).arguments().get(1));
        }
    }

    @Test void strictReaderExposesAllTypedDiagnosticsInBothPackagedLanguages() {
        for (boolean gun : List.of(false, true)) {
            byte[] valid = gun ? GUN_TYPE.encode(gun(0, TaczGun.FireMode.AUTO, false, OptionalInt.empty(), OptionalInt.empty())) : MELEE_TYPE.encode(new LrMelee(MELEE, REF, Optional.empty(), Optional.empty(), 1, 0));
            for (int schema : List.of(0, 2)) { var p = decodeFailure(gun, schema, valid); assertEquals(List.of(number(schema)), localized(p, "unsupported_schema").arguments()); }
            for (byte[] invalid : List.of(new byte[] {(byte)0xc3, 0x28}, bytes("\uFEFF{}"), new byte[] {(byte)0xed, (byte)0xa0, (byte)0x80})) assertTrue(localized(decodeFailure(gun, 1, invalid), "invalid_utf8").arguments().isEmpty());
            for (String invalid : List.of("{", "{}{}", "{\"x\":1,\"x\":2}")) assertEquals(2, localized(decodeFailure(gun, 1, bytes(invalid)), "invalid_json").arguments().size());
            byte[] huge = new byte[ExactJson.MAX_UTF8_BYTES + 1]; Arrays.fill(huge, (byte)0xff);
            assertEquals(List.of(number(huge.length), number(ExactJson.MAX_UTF8_BYTES)), localized(decodeFailure(gun, 1, huge), "byte_budget").arguments());
            field(assertThrows(DocumentCodecException.class, () -> { if (gun) GUN_TYPE.decode(1, bytes("{}")); else MELEE_TYPE.decode(1, bytes("{}")); }));
        }
    }

    @Test void legacyNumericEnumAndOptionalNullPoliciesCannotBypassCurrentValueRules() {
        var selected = context(true, true, "foreign:gun", null, null, null);
        for (String field : List.of("GunCurrentAmmoCount", "DummyAmmo", "MaxDummyAmmo"))
            for (String bad : List.of("-1", "2147483648", "1.5", "\"1\"", "true", "null", "{}", "[]")) {
                var item = legacy(true); item.getAsJsonObject("tag").add(field, ExactJson.parse(bad).toJson());
                assertTrue(GUN_TYPE.convert(ExactJson.freeze(item), selected).isEmpty(), field + "=" + bad);
            }
        for (String bad : List.of("0", "128", "-1", "1.5", "\"1\"", "true", "null")) {
            var item = legacy(true); item.add("Count", ExactJson.parse(bad).toJson()); assertTrue(GUN_TYPE.convert(ExactJson.freeze(item), selected).isEmpty());
        }
        for (String bad : List.of("UNKNOWN", "auto", "", "BOGUS")) {
            var item = legacy(true); item.getAsJsonObject("tag").addProperty("GunFireMode", bad); assertTrue(GUN_TYPE.convert(ExactJson.freeze(item), selected).isEmpty());
        }
        for (String mode : List.of("AUTO", "SEMI", "BURST")) {
            var item = legacy(true); item.getAsJsonObject("tag").addProperty("GunFireMode", mode);
            item.getAsJsonObject("tag").addProperty("GunDisplayId", "tacz:default");
            item.getAsJsonObject("tag").add("GunCurrentAmmoCount", ExactJson.parse("2147483647e0").toJson());
            var encoded = GUN_TYPE.convert(ExactJson.freeze(item), selected).orElseThrow();
            var value = GUN_TYPE.decode(1, encoded.bytes()); assertEquals(Integer.MAX_VALUE, value.ammoCount());
            assertEquals(Optional.of(id("tacz:default")), value.displayId()); assertTrue(value.displayResource().isEmpty());
            assertEquals(TaczGun.FireMode.valueOf(mode), value.fireMode());
        }
        var meleeContext = context(false, true, "foreign:knife", null, null, null);
        for (String bad : List.of("-1", "2147483648", "0.5", "\"0\"", "true", "null")) {
            var item = legacy(false); item.getAsJsonObject("tag").add("Damage", ExactJson.parse(bad).toJson()); assertTrue(MELEE_TYPE.convert(ExactJson.freeze(item), meleeContext).isEmpty());
        }
        for (boolean gun : List.of(false, true)) {
            var item = legacy(gun); item.getAsJsonObject("tag").add(gun ? "GunDisplayId" : "DisplayId", JsonNull.INSTANCE);
            assertTrue((gun ? (LegacyItemAdapter)GUN_TYPE : MELEE_TYPE).convert(ExactJson.freeze(item), gun ? selected : meleeContext).isEmpty());
        }
        // Current payloads also accept mathematical integers without depending on the original JSON spelling.
        var current = object(GUN_TYPE.encode(gun(0, TaczGun.FireMode.AUTO, false, OptionalInt.empty(), OptionalInt.empty())));
        current.add("count", ExactJson.parse("1.0").toJson()); current.add("ammoCount", ExactJson.parse("2147483647e0").toJson());
        assertEquals(1, GUN_TYPE.decode(1, ExactJson.freeze(current).canonicalUtf8()).count());
    }

    @Test void selectedV1IdentitySurvivesCurrentV2AndAliasCannotForgeAssetIdentity() {
        UUID v2 = new UUID(40, 99); EditorId alias = id("foreign:alias");
        var catalog = new ResourceCatalog(Map.of(REF.resourceId(), new LogicalResource(REF.resourceId(), WeaponItemValues.TACZ_GUN_ASSET, "synthetic", v2)),
                Map.of(REF.versionId(), new ResourceVersion(REF.versionId(), REF.resourceId(), WeaponItemValues.TACZ_IMPORTER, "1", Map.of("assetId", GUN.toString()), null, Set.of(), Set.of()),
                        v2, new ResourceVersion(v2, REF.resourceId(), WeaponItemValues.TACZ_IMPORTER, "1", Map.of("assetId", alias.toString()), null, Set.of(), Set.of())), Map.of());
        var key = new LegacyItemResourceKey(WeaponItemValues.TACZ_GUN, WeaponItemValues.TACZ_GUN_ASSET, GUN);
        var aliasKey = new LegacyItemResourceKey(WeaponItemValues.TACZ_GUN, WeaponItemValues.TACZ_GUN_ASSET, alias);
        var selected = LegacyItemContext.from(catalog, Map.of(key, REF, aliasKey, REF));
        assertEquals(REF, GUN_TYPE.decode(1, GUN_TYPE.convert(ExactJson.freeze(legacy(true)), selected).orElseThrow().bytes()).gunResource());
        var item = legacy(true); item.getAsJsonObject("tag").addProperty("GunId", alias.toString());
        assertTrue(GUN_TYPE.convert(ExactJson.freeze(item), selected).isEmpty(), "an exact key is insufficient when selected version assetId disagrees");
        assertTrue(GUN_TYPE.convert(ExactJson.freeze(legacy(true)), LegacyItemContext.from(catalog, Map.of(key, new ResourceRef(REF.resourceId(), v2)))).isEmpty());
    }

    @Test void realRegistrationUsesActualTypesAdaptersAndExactRuntimeVersions() {
        var b = new ExtensionRegistryBuilder(new ApiVersion(1, 0), Contribution.Side.COMMON);
        BasicItemModules.registerVanilla(b, "integration.40", "1.20.1");
        WeaponItemValues.registerTacz(b, "integration.40", "1.1.7-hotfix"); WeaponItemValues.registerLrTactical(b, "integration.40", "0.4.3");
        var registry = b.freeze(); assertTrue(registry.metadata().problems().isEmpty(), registry.metadata().problems().toString());
        for (EditorId type : List.of(WeaponItemValues.TACZ_GUN, WeaponItemValues.LR_MELEE)) {
            var impl = registry.find(ValueTypes.KIND, type).orElseThrow(); assertSame(impl, registry.find(LegacyItems.KIND, type).orElseThrow());
            var leaf = new ContributionKey(ValueTypes.KIND.id(), type); var adapter = registry.metadata().contributions().get(new ContributionKey(LegacyItems.KIND.id(), type));
            assertEquals(Set.of(leaf), adapter.requiredContributions()); assertEquals(Contribution.Side.COMMON, adapter.side());
            var active = ValueTypeCatalog.activate(registry).find(type).orElseThrow();
            byte[] payload = type.equals(WeaponItemValues.TACZ_GUN) ? GUN_TYPE.encode(gun(0, TaczGun.FireMode.AUTO, false, OptionalInt.empty(), OptionalInt.empty())) : MELEE_TYPE.encode(new LrMelee(MELEE, REF, Optional.empty(), Optional.empty(), 1, 0));
            assertEquals(Set.of(leaf), active.decode(active.codecId(), 1, payload).dependencies().contributions());
        }
        assertEquals(Set.of(WeaponItemValues.TACZ_RUNTIME, BasicItemModules.VANILLA_RUNTIME), registry.metadata().modules().get(WeaponItemValues.TACZ_MODULE).requiredModules());
        assertEquals(Set.of(WeaponItemValues.LR_RUNTIME, WeaponItemValues.TACZ_RUNTIME, BasicItemModules.VANILLA_RUNTIME), registry.metadata().modules().get(WeaponItemValues.LR_MODULE).requiredModules());
        assertEquals("1.1.7-hotfix", registry.metadata().modules().get(WeaponItemValues.TACZ_RUNTIME).buildVersion());
        assertEquals("0.4.3", registry.metadata().modules().get(WeaponItemValues.LR_RUNTIME).buildVersion());
    }

    private static TaczGun gun(int display, TaczGun.FireMode mode, boolean barrel, OptionalInt dummy, OptionalInt max) {
        return new TaczGun(GUN, REF, display == 0 ? Optional.empty() : Optional.of(display == 1 ? id("tacz:default") : DISPLAY), display == 2 ? Optional.of(DISPLAY_REF) : Optional.empty(), 127, mode, barrel, Integer.MAX_VALUE, dummy, max);
    }
    private static JsonObject legacy(boolean gun) { return object(bytes(gun ? "{\"id\":\"tacz:modern_kinetic_gun\",\"Count\":1,\"tag\":{\"GunId\":\"foreign:gun\",\"GunFireMode\":\"AUTO\",\"HasBulletInBarrel\":1,\"GunCurrentAmmoCount\":30}}" : "{\"id\":\"lrtactical:melee\",\"Count\":1,\"tag\":{\"MeleeWeaponId\":\"foreign:knife\",\"Damage\":0}}")); }
    private static LegacyItemContext context(boolean gun, boolean display, String asset, EditorId wrongType, EditorId wrongImporter, String wrongVersion) {
        EditorId profile = gun ? WeaponItemValues.TACZ_GUN : WeaponItemValues.LR_MELEE, kind = gun ? WeaponItemValues.TACZ_GUN_ASSET : WeaponItemValues.LR_MELEE_ASSET,
                displayKind = gun ? WeaponItemValues.TACZ_DISPLAY_ASSET : WeaponItemValues.LR_DISPLAY_ASSET, importer = gun ? WeaponItemValues.TACZ_IMPORTER : WeaponItemValues.LR_IMPORTER;
        Map<UUID, LogicalResource> resources = new HashMap<>(); Map<UUID, ResourceVersion> versions = new HashMap<>(); Map<LegacyItemResourceKey, ResourceRef> selections = new HashMap<>();
        resources.put(REF.resourceId(), new LogicalResource(REF.resourceId(), wrongType == null ? kind : wrongType, "synthetic", REF.versionId()));
        versions.put(REF.versionId(), new ResourceVersion(REF.versionId(), REF.resourceId(), wrongImporter == null ? importer : wrongImporter, wrongVersion == null ? "1" : wrongVersion, Map.of("assetId", asset), null, Set.of(), Set.of()));
        selections.put(new LegacyItemResourceKey(profile, kind, gun ? GUN : MELEE), REF);
        if (display) { resources.put(DISPLAY_REF.resourceId(), new LogicalResource(DISPLAY_REF.resourceId(), displayKind, "synthetic display", DISPLAY_REF.versionId()));
            versions.put(DISPLAY_REF.versionId(), new ResourceVersion(DISPLAY_REF.versionId(), DISPLAY_REF.resourceId(), importer, "1", Map.of("assetId", DISPLAY.toString()), null, Set.of(), Set.of())); selections.put(new LegacyItemResourceKey(profile, displayKind, DISPLAY), DISPLAY_REF); }
        return LegacyItemContext.from(new ResourceCatalog(resources, versions, Map.of()), selections);
    }
    private static Problem decodeFailure(boolean gun, int schema, byte[] payload) { return assertThrows(DocumentCodecException.class, () -> { if (gun) GUN_TYPE.decode(schema, payload); else MELEE_TYPE.decode(schema, payload); }).problems().get(0); }
    private static void invalid(boolean gun, JsonObject bad) { byte[] input = ExactJson.freeze(bad).canonicalUtf8(), copy = input.clone(); field(assertThrows(DocumentCodecException.class, () -> { if (gun) GUN_TYPE.decode(1, input); else MELEE_TYPE.decode(1, input); })); assertArrayEquals(copy, input); }
    private static void field(DocumentCodecException e) { var p = e.problems().get(0); var m = localized(p, "invalid_field"); assertEquals(Problem.Severity.ERROR, p.severity()); assertEquals(2, m.arguments().size()); assertEquals("weapons:" + assertInstanceOf(TextContent.LiteralArgument.class, m.arguments().get(0)).value(), p.target()); }
    private static TextContent.Message localized(Problem p, String suffix) {
        assertEquals(id("fpsmatch:weapons/" + suffix), p.code()); var m = assertInstanceOf(TextContent.Message.class, p.message()); assertEquals("fpsmatch.editor.weapons." + suffix, m.key());
        for (String language : List.of("en_us", "zh_cn")) try (var stream = WeaponItemProfilesTest.class.getResourceAsStream("/assets/fpsmatch/lang/" + language + ".json")) {
            assertNotNull(stream); var translations = object(stream.readAllBytes());
            translations.keySet().removeIf(key -> !key.startsWith("fpsmatch.editor.weapons."));
            assertEquals(Set.of("fpsmatch.editor.weapons.unsupported_schema", "fpsmatch.editor.weapons.invalid_utf8",
                    "fpsmatch.editor.weapons.invalid_json", "fpsmatch.editor.weapons.invalid_field",
                    "fpsmatch.editor.weapons.byte_budget", "fpsmatch.editor.weapons.numeric_normalization",
                    "fpsmatch.editor.weapons.invalid_builtin_asset"), translations.keySet()); String text = translations.get(m.key()).getAsString();
            assertFalse(MinecraftTranslationFormat.render(m, text).isBlank());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        return m;
    }
    private static TextContent.NumberArgument number(long n) { return new TextContent.NumberArgument(BigDecimal.valueOf(n)); }
    private static EditorId id(String s) { return EditorId.parse(s); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static JsonObject object(byte[] b) { return ExactJson.parse(new String(b, StandardCharsets.UTF_8)).toJson().getAsJsonObject(); }
}
