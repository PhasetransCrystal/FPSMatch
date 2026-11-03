package net.ptcrys.fpsmatch.editor.core.nativeitemprobe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tacz.guns.api.item.IGun;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.xjqsh.lrtactical.api.item.IMeleeWeapon;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemContext;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemEncoding;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemResourceKey;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItems;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.LrMelee;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.TaczGun;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemCatalog;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactories;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectCatalog;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectNode;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItem;
import net.ptcrys.fpsmatch.editor.common.domain.map.EmptyItem;
/** Q48 real fixture measurement; invoked synchronously by the surrounding title-screen probe. */
final class NativeItemFixtureDiagnostic {
    private static final Path FIXTURE = Path.of("F:/BlockOffensive/.local-artifacts/world-editor/rewrite/platform-batch07/Q29/fixture");
    private static final String GUN_ITEM = "tacz:modern_kinetic_gun";
    private static final String MELEE_ITEM = "lrtactical:melee";
    private static final EditorId SLOT_ROLE = EditorId.parse("native_item_probe:fixture_slot");
    private static final EditorId VALUE_ROLE = EditorId.parse("fpsmatch:item");
    private NativeItemFixtureDiagnostic() { }

    static Map<String, Object> run(ExtensionRegistry registry) throws Exception {
        Objects.requireNonNull(registry, "registry");
        Map<Path, byte[]> inputs = new LinkedHashMap<>();
        for (String name : List.of("CSGameMaps/test.json", "CSGameMaps/test.cfg",
                "CSDeathMatchMaps/test.json", "CSDeathMatchMaps/test.cfg")) {
            Path path = FIXTURE.resolve(name);
            rejectLinks(path);
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "regular fixed fixture input");
            require(Files.size(path) <= ExactJson.MAX_UTF8_BYTES, "fixture byte budget");
            inputs.put(path, Files.readAllBytes(path));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            List<Occurrence> occurrences = new ArrayList<>();
            for (Map.Entry<Path, byte[]> input : inputs.entrySet()) {
                if (!input.getKey().toString().endsWith(".json")) continue;
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(input.getValue())).toString();
                collect(ExactJson.parse(json).toJson(), input.getKey().getParent().getFileName().toString(), "", occurrences);
            }
            checkFixtureCounts(occurrences);
            require(registry.metadata().problems().isEmpty(), "actual registry diagnostics");
            ObjectCatalog objects = ObjectCatalog.activate(registry);
            Map<String, Object> factories = new LinkedHashMap<>();
            registry.entries(NativeItemFactories.KIND).forEach((id, factory) -> {
                var metadata = registry.metadata().contributions().get(new ContributionKey(NativeItemFactories.KIND.id(), id));
                require(metadata != null, "actual factory contribution metadata");
                factories.put(id.toString(), Map.of("valueClass", factory.valueType().getName(),
                        "implementationClass", factory.factory().getClass().getName(), "permitsEmpty", factory.permitsEmpty(),
                        "owner", metadata.owner().toString(), "side", metadata.side().name(),
                        "requiredContributions", metadata.requiredContributions().stream().map(Object::toString).sorted().toList()));
            });
            report.put("factories", factories);
            report.put("objectActivationFailures", failureSummary(objects.activationFailures()));
            require(objects.activationFailures().isEmpty(), "actual object activation");
            LegacyItemContext context = syntheticContext(occurrences);
            List<Map<String, Object>> observations = new ArrayList<>();
            List<Map<String, Object>> notices = new ArrayList<>();
            Map<String, Integer> types = new LinkedHashMap<>();
            try (NativeItemCatalog nativeItems = NativeItemCatalog.activate(registry, VALUE_ROLE)) {
                report.put("nativeActivationFailures", failureSummary(nativeItems.activationFailures()));
                require(nativeItems.activationFailures().isEmpty(), "actual native factory activation");
                for (Occurrence occurrence : occurrences) {
                    ExactJson.FrozenJson original = ExactJson.freeze(occurrence.item());
                    List<LegacyItemEncoding> accepted = new ArrayList<>();
                    List<String> adapters = new ArrayList<>();
                    for (var adapter : registry.entries(LegacyItems.KIND).entrySet()) {
                        var converted = adapter.getValue().convert(original, context);
                        require(converted != null, "adapter Optional result");
                        if (converted.isPresent()) {
                            accepted.add(converted.get());
                            adapters.add(adapter.getKey().toString());
                        }
                    }
                    require(accepted.size() == 1, occurrence.pointer() + " exactly one registered legacy adapter");
                    LegacyItemEncoding encoding = accepted.get(0);
                    ObjectNode node = objects.decode(UUID.nameUUIDFromBytes(("q48:" + occurrence.fixture() + occurrence.pointer()).getBytes(StandardCharsets.UTF_8)),
                            Optional.empty(), SLOT_ROLE, encoding.typeId(), encoding.codecId(), encoding.schema(), encoding.bytes());
                    require(node.role().equals(SLOT_ROLE) && node.valueRole().equals(VALUE_ROLE), "distinct position/value roles");
                    Object value = node.value().content();
                    Expected expected = expected(value);
                    require(occurrence.item().get("id").getAsString().equals(expected.id()), "fixture/pure item identity");
                    checkNotices(occurrence, encoding, value instanceof TaczGun gun ? gun : null);
                    for (Problem notice : encoding.notices()) {
                        TextContent.Message message = (TextContent.Message) notice.message();
                        notices.add(Map.of("fixture", occurrence.fixture(), "pointer", occurrence.pointer(),
                                "severity", notice.severity().name(), "code", notice.code().toString(),
                                "target", notice.target(), "key", message.key(), "arguments", message.arguments()));
                    }
                    ItemStack first = nativeItems.create(node, () -> false);
                    verify(first, expected, value);
                    CompoundTag saved = first.save(new CompoundTag());
                    ItemStack restored = ItemStack.of(saved.copy());
                    verify(restored, expected, value);
                    require(saved.equals(restored.save(new CompoundTag())), "native save/of/save exact NBT");
                    first.setCount(3);
                    if (first.getTag() == null) first.setTag(new CompoundTag());
                    first.getTag().putString("Q48Mutation", "caller-owned");
                    ItemStack rebuilt = nativeItems.create(node, () -> false);
                    require(rebuilt != first && rebuilt != restored, "distinct returned stack identity");
                    verify(rebuilt, expected, value);
                    verify(restored, expected, value);
                    require(first.getTag() != rebuilt.getTag(), "caller tag alias isolation");
                    require(saved.equals(rebuilt.save(new CompoundTag())), "caller mutation does not affect reconstruction");
                    require(original.equals(ExactJson.freeze(occurrence.item())), "occurrence unchanged");
                    String category = value.getClass().getSimpleName();
                    types.merge(category, 1, Integer::sum);
                    observations.add(Map.of("fixture", occurrence.fixture(), "pointer", occurrence.pointer(),
                            "adapter", adapters.get(0), "type", encoding.typeId().toString(), "codec", encoding.codecId().toString(),
                            "schema", encoding.schema(), "category", category, "count", expected.count(),
                            "savedNbt", saved.toString(), "roundtripAndMutationIsolation", true));
                }
            }
            require(notices.size() == 7, "exactly seven typed normalization notices");
            require(types.equals(Map.of("EmptyItem", 88, "BasicItem", 8, "TaczGun", 7, "LrMelee", 2)),
                    "exact pure value category counts");
            report.put("counts", Map.of("CSGameMaps", Map.of("total", 55, "air", 38, "guns", 7, "melee", 2, "nonempty", 17),
                    "CSDeathMatchMaps", Map.of("total", 50, "air", 50, "nonempty", 0)));
            report.put("pureValueCounts", types);
            report.put("observations", observations);
            report.put("normalizationNotices", notices);
            report.put("resourceContext", Map.of("synthetic", true, "producer", 0, "byteEntries", 0,
                    "sourceEntry", "absent", "realResourceClosureAuthorized", false));
            report.put("scope", "ItemStack body/count/NBT only; capabilities, resources, installation and gameplay unverified");
            report.put("execution", "synchronous calling owner; no world, async, resource production or network operations");
            report.put("modelConfiguration", "requested GPT-6.1-sol medium; runtime configuration not independently verified");
            return report;
        } finally {
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Map.Entry<Path, byte[]> input : inputs.entrySet()) {
                rejectLinks(input.getKey());
                byte[] after = Files.readAllBytes(input.getKey());
                String beforeSha = sha(input.getValue());
                String afterSha = sha(after);
                require(Arrays.equals(input.getValue(), after) && beforeSha.equals(afterSha), "fixture bytes/SHA unchanged");
                evidence.add(Map.of("path", input.getKey().toString(), "beforeBytes", input.getValue().length,
                        "afterBytes", after.length, "beforeSha256", beforeSha, "afterSha256", afterSha, "unchanged", true));
            }
            report.put("inputs", evidence);
        }
    }

    private static Map<String, String> failureSummary(Map<EditorId, RuntimeException> failures) {
        Map<String, String> result = new LinkedHashMap<>();
        failures.forEach((id, failure) -> result.put(id.toString(), failure.getClass().getName()));
        return result;
    }

    private static void rejectLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            require(!Files.isSymbolicLink(current), "fixed input contains symbolic link");
            require(!Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isOther(), "fixed input contains reparse/nonregular component");
        }
        require(absolute.toRealPath().equals(absolute), "fixed input resolves elsewhere");
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void collect(JsonElement value, String fixture, String pointer, List<Occurrence> result) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonElement id = object.get("id");
            JsonElement count = object.get("Count");
            if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                    && count != null && count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
                result.add(new Occurrence(fixture, pointer, object));
            }
            for (var entry : object.entrySet()) collect(entry.getValue(), fixture,
                    pointer + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"), result);
        } else if (value.isJsonArray()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) collect(value.getAsJsonArray().get(i), fixture, pointer + "/" + i, result);
        }
    }

    private static void checkFixtureCounts(List<Occurrence> occurrences) {
        for (String fixture : List.of("CSGameMaps", "CSDeathMatchMaps")) {
            List<Occurrence> group = occurrences.stream().filter(o -> o.fixture().equals(fixture)).toList();
            boolean cs = fixture.equals("CSGameMaps");
            long air = group.stream().filter(o -> o.item().get("id").getAsString().equals("minecraft:air")).count();
            require(group.size() == (cs ? 55 : 50) && air == (cs ? 38 : 50), "fixed fixture total/air/nonempty counts");
            require(group.stream().filter(Occurrence::gun).count() == (cs ? 7 : 0), "fixed fixture gun count");
            require(group.stream().filter(o -> o.item().get("id").getAsString().equals(MELEE_ITEM)).count() == (cs ? 2 : 0), "fixed fixture melee count");
        }
    }

    private static Expected expected(Object value) {
        if (value instanceof EmptyItem) return new Expected("minecraft:air", 0, null);
        if (value instanceof BasicItem basic) {
            CompoundTag tag = null;
            if (basic.damage().isPresent()) {
                tag = new CompoundTag();
                tag.put("Damage", IntTag.valueOf(basic.damage().getAsInt()));
            }
            return new Expected(basic.itemId().toString(), basic.count(), tag);
        }
        if (value instanceof TaczGun gun) return new Expected(GUN_ITEM, gun.count(), normalizedTag(gun, null));
        if (value instanceof LrMelee melee) return new Expected(MELEE_ITEM, melee.count(), normalizedTag(null, melee));
        throw new IllegalStateException("Q48 unsupported fixture pure value: " + value.getClass().getName());
    }

    private static void verify(ItemStack stack, Expected expected, Object value) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(expected.id()));
        require(item != null && stack.getItem() == item && stack.getCount() == expected.count(), "exact registered item/count");
        require(stack.isEmpty() == (value instanceof EmptyItem), "exact empty semantics");
        require(Objects.equals(expected.tag(), stack.getTag()), "exact expected tag including types");
        if (value instanceof TaczGun gun) {
            require(item instanceof IGun, "actual IGun item");
            checkNormalized(stack, item, expected.tag(), gun, null);
        } else if (value instanceof LrMelee melee) {
            require(item instanceof IMeleeWeapon, "actual IMeleeWeapon item");
            checkNormalized(stack, item, expected.tag(), null, melee);
        }
        require(Objects.equals(expected.tag(), stack.getTag()) && stack.getCount() == expected.count()
                && stack.getItem() == item, "getter calls preserve item/count/tag");
    }

    private static LegacyItemContext syntheticContext(List<Occurrence> occurrences) {
        Map<UUID, LogicalResource> resources = new LinkedHashMap<>();
        Map<UUID, ResourceVersion> versions = new LinkedHashMap<>();
        Map<LegacyItemResourceKey, ResourceRef> choices = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            if (!occurrence.gun() && !occurrence.item().get("id").getAsString().equals(MELEE_ITEM)) continue;
            JsonObject tag = occurrence.item().getAsJsonObject("tag");
            EditorId profile = occurrence.gun() ? WeaponItemValues.TACZ_GUN : WeaponItemValues.LR_MELEE;
            EditorId importer = occurrence.gun() ? WeaponItemValues.TACZ_IMPORTER : WeaponItemValues.LR_IMPORTER;
            addSynthetic(resources, versions, choices, profile,
                    occurrence.gun() ? WeaponItemValues.TACZ_GUN_ASSET : WeaponItemValues.LR_MELEE_ASSET,
                    importer, tag.get(occurrence.gun() ? "GunId" : "MeleeWeaponId").getAsString());
            String display = occurrence.gun() ? "GunDisplayId" : "DisplayId";
            if (tag.has(display) && !(occurrence.gun() && tag.get(display).getAsString().equals("tacz:default"))) {
                addSynthetic(resources, versions, choices, profile,
                        occurrence.gun() ? WeaponItemValues.TACZ_DISPLAY_ASSET : WeaponItemValues.LR_DISPLAY_ASSET,
                        importer, tag.get(display).getAsString());
            }
        }
        return LegacyItemContext.from(new ResourceCatalog(resources, versions, Map.of()), choices);
    }

    private static void addSynthetic(Map<UUID, LogicalResource> resources, Map<UUID, ResourceVersion> versions,
                                     Map<LegacyItemResourceKey, ResourceRef> choices, EditorId profile,
                                     EditorId kind, EditorId importer, String assetId) {
        LegacyItemResourceKey key = new LegacyItemResourceKey(profile, kind, EditorId.parse(assetId));
        if (choices.containsKey(key)) return;
        UUID resource = UUID.nameUUIDFromBytes(("q48:synthetic:resource:" + profile + ":" + kind + ":" + assetId).getBytes(StandardCharsets.UTF_8));
        UUID version = UUID.nameUUIDFromBytes(("q48:synthetic:version:" + resource).getBytes(StandardCharsets.UTF_8));
        resources.put(resource, new LogicalResource(resource, kind, "Q48 synthetic metadata only", version));
        versions.put(version, new ResourceVersion(version, resource, importer, "1", Map.of("assetId", assetId), null, Set.of(), Set.of()));
        choices.put(key, new ResourceRef(resource, version));
    }

    private static void checkNotices(Occurrence occurrence, LegacyItemEncoding encoding, TaczGun gun) {
        if (!occurrence.gun()) {
            require(encoding.notices().isEmpty(), "non-gun has no normalization warning");
            return;
        }
        require(encoding.notices().size() == 1, "one gun normalization warning");
        Problem notice = encoding.notices().get(0);
        require(notice.severity() == Problem.Severity.WARNING
                && notice.code().equals(EditorId.parse("fpsmatch:weapons/numeric_normalization"))
                && notice.target().equals("weapons:/tag"), "typed normalization notice");
        require(notice.message() instanceof TextContent.Message, "typed notice message");
        TextContent.Message message = (TextContent.Message) notice.message();
        int fields = 1 + (gun.dummyAmmo().isPresent() ? 1 : 0) + (gun.maxDummyAmmo().isPresent() ? 1 : 0);
        require(message.key().equals("fpsmatch.editor.weapons.numeric_normalization")
                && message.arguments().equals(List.of(new TextContent.LiteralArgument(gun.gunId().toString()),
                        new TextContent.NumberArgument(BigDecimal.valueOf(fields)))), "notice arguments");
    }

    private static CompoundTag normalizedTag(TaczGun gun, LrMelee melee) {
        CompoundTag tag = new CompoundTag();
        if (gun != null) {
            tag.put("GunId", StringTag.valueOf(gun.gunId().toString()));
            tag.put("GunFireMode", StringTag.valueOf(gun.fireMode().name()));
            tag.put("HasBulletInBarrel", ByteTag.valueOf((byte) (gun.bulletInBarrel() ? 1 : 0)));
            tag.put("GunCurrentAmmoCount", IntTag.valueOf(gun.ammoCount()));
            gun.dummyAmmo().ifPresent(value -> tag.put("DummyAmmo", IntTag.valueOf(value)));
            gun.maxDummyAmmo().ifPresent(value -> tag.put("MaxDummyAmmo", IntTag.valueOf(value)));
            gun.displayId().ifPresent(value -> tag.put("GunDisplayId", StringTag.valueOf(value.toString())));
        } else {
            tag.put("MeleeWeaponId", StringTag.valueOf(melee.weaponId().toString()));
            tag.put("Damage", IntTag.valueOf(melee.damage()));
            melee.displayId().ifPresent(value -> tag.put("DisplayId", StringTag.valueOf(value.toString())));
        }
        return tag;
    }

    private static void checkNormalized(ItemStack stack, Item item, CompoundTag expected, TaczGun gun, LrMelee melee) {
        require(!stack.isEmpty() && stack.getItem() == item, "normalized actual item");
        require(stack.getCount() == (gun != null ? gun.count() : melee.count()), "normalized count");
        require(expected.equals(stack.getTag()), "normalized exact NBT fields, types and values");
        if (gun != null) {
            IGun access = (IGun) item;
            require(access.getGunId(stack).toString().equals(gun.gunId().toString()), "normalized gun ID getter");
            require(access.getGunDisplayId(stack).toString().equals(gun.displayId().map(Object::toString).orElse("tacz:default")), "normalized gun display getter");
            require(access.getFireMode(stack).name().equals(gun.fireMode().name()), "normalized fire mode getter");
            require(access.hasBulletInBarrel(stack) == gun.bulletInBarrel(), "normalized barrel getter");
            require(access.getCurrentAmmoCount(stack) == gun.ammoCount(), "normalized ammo getter");
            require(access.useDummyAmmo(stack) == gun.dummyAmmo().isPresent()
                    && access.getDummyAmmoAmount(stack) == gun.dummyAmmo().orElse(0), "normalized dummy presence/amount getters");
            require(access.hasMaxDummyAmmo(stack) == gun.maxDummyAmmo().isPresent()
                    && access.getMaxDummyAmmoAmount(stack) == gun.maxDummyAmmo().orElse(0), "normalized max dummy presence/amount getters");
        } else {
            IMeleeWeapon access = (IMeleeWeapon) item;
            require(access.getId(stack).toString().equals(melee.weaponId().toString()), "normalized melee ID getter");
            require(access.getDisplayId(stack).toString().equals(melee.displayId().orElse(melee.weaponId()).toString()), "normalized melee display getter");
            require(stack.getDamageValue() == melee.damage(), "normalized damage getter");
        }
        require(expected.equals(stack.getTag()), "getters did not change normalized fields");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Q48 fixture verification failed: " + label);
    }
    private record Expected(String id, int count, CompoundTag tag) { }
    private record Occurrence(String fixture, String pointer, JsonObject item) {
        boolean gun() { return item.get("id").getAsString().equals(GUN_ITEM); }
    }
}
