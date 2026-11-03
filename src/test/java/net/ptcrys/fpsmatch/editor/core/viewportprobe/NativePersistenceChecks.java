package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.ptcrys.fpsmatch.core.persistence.RuntimeItemStackCodec;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItem;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItemModules;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemCatalog;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemException;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactories;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactory;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectCatalog;

/** Explicit client-thread checks only; no world, files, control transport or persistent fixtures. */
public final class NativePersistenceChecks {
    private static final String FORMAT = "fpsmatch:item_format";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private NativePersistenceChecks() {}

    public static JsonObject run() {
        require(Minecraft.getInstance().isSameThread(), "native persistence checks require client thread");
        require(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:stone")) == Items.STONE,
                "actual bootstrapped Minecraft item registry");
        JsonObject report = new JsonObject();
        JsonArray checks = new JsonArray();
        CompoundTag emptyBefore = ItemStack.EMPTY.save(new CompoundTag()).copy();
        try (Stacks stacks = new Stacks()) {
            ItemStack stone = stacks.add(new ItemStack(Items.STONE, 1));
            JsonObject plain = encode(stone);
            byte[] plainBytes = HexFormat.of().parseHex(
                    "0a0000010005436f756e74010800026964000f6d696e6563726166743a73746f6e6500");
            require(Arrays.equals(payload(plain), plainBytes), "independent exact canonical stone NBT vector");
            roundtrip(stone, stacks);
            ItemStack air = stacks.add(new ItemStack(Items.AIR, 0));
            roundtrip(air, stacks);
            roundtrip(stacks.add(new ItemStack(Items.STONE, 127)), stacks);
            roundtrip(stacks.add(new ItemStack(Items.STONE, 0)), stacks);
            rejectEncode(stacks.add(new ItemStack(Items.STONE, 128)), "count exceeds byte domain");
            rejectEncode(stacks.add(new ItemStack(Items.STONE, -1)), "raw negative Count hidden by empty stack getter");
            rejectEncode(stacks.add(new ItemStack(Items.AIR, 1)), "raw AIR positive Count hidden by empty stack getter");
            checks.add("actual_registry_plain_air_count_1_127_and_invalid_counts_exact_binary_vector");

            ItemStack typed = stacks.add(new ItemStack(Items.STONE, 3));
            CompoundTag tag = typedTags();
            typed.setTag(tag.copy());
            ItemStack typedRead = roundtrip(typed, stacks);
            require(typedRead.getTag().equals(tag), "every numeric type, list, compound and array preserved");
            for (String key : tag.getAllKeys())
                require(typedRead.getTag().get(key).getId() == tag.get(key).getId(), "tag type: " + key);
            require(typedRead.getTag().get("int28").getId() == Tag.TAG_INT
                    && typedRead.getTag().getInt("int28") == 28
                    && typedRead.getTag().get("Damage").getId() == Tag.TAG_INT
                    && typedRead.getTag().getInt("Damage") == 0, "small IntTag and damage zero remain IntTag");
            JsonObject persisted = encode(typed);
            require(persisted.keySet().equals(java.util.Set.of(FORMAT, "nbt"))
                    && persisted.get(FORMAT).getAsInt() == 1, "versioned envelope shape");
            report.addProperty("typedPayloadBytes", payload(persisted).length);
            report.addProperty("typedInt28TagId", typedRead.getTag().get("int28").getId());
            checks.add("all_numeric_tags_nested_compound_homogeneous_lists_three_arrays_int28_damage0");
            checks.add("saved_json_text_reparsed_before_decode_full_native_state_and_source_unchanged");

            ItemStack emptyTag = stacks.add(new ItemStack(Items.STONE, 1));
            emptyTag.setTag(new CompoundTag());
            require(roundtrip(emptyTag, stacks).getTag() != null, "empty compound differs from absent tag");
            require(!encode(emptyTag).equals(plain), "empty tag has distinct persisted representation");
            ItemStack reverse = stacks.add(new ItemStack(Items.STONE, 3));
            CompoundTag reverseTag = new CompoundTag();
            tag.getAllKeys().stream().sorted(java.util.Comparator.reverseOrder())
                    .forEach(key -> reverseTag.put(key, tag.get(key).copy()));
            reverse.setTag(reverseTag);
            require(encode(reverse).equals(persisted), "compound insertion order does not change bytes");
            checks.add("empty_tag_vs_absence_and_compound_order_determinism");

            legacy(stacks, report);
            checks.add("legacy_unmarked_plain_compatible_and_small_int_narrowing_observed");
            catalog(stacks, checks);
            malformed(plain, plainBytes);
            checks.add("marked_malformed_never_falls_back_version_shape_base64_nbt_identity_and_canonicality");
            budgets(stacks, checks, report);
        }
        aggregateCleanup(checks, report);
        require(emptyBefore.equals(ItemStack.EMPTY.save(new CompoundTag())) && ItemStack.EMPTY.getTag() == null
                && ItemStack.EMPTY.getCount() == 0, "global EMPTY body unchanged");
        checks.add("all_successfully_returned_independent_stacks_invalidated_without_global_empty");
        report.add("checks", checks);
        report.addProperty("checkGroups", checks.size());
        report.addProperty("ok", true);
        return report;
    }

    private static CompoundTag typedTags() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("byte", (byte) -120);
        tag.putShort("short", (short) 300);
        tag.putInt("int28", 28);
        tag.putInt("Damage", 0);
        tag.putLong("long", 9007199254740993L);
        tag.putFloat("float", -0.0F);
        tag.putDouble("double", Math.PI);
        tag.putString("unicode", "原生\u0000😀");
        tag.putByteArray("bytes", new byte[] {Byte.MIN_VALUE, 0, Byte.MAX_VALUE});
        tag.putIntArray("ints", new int[] {Integer.MIN_VALUE, 28, Integer.MAX_VALUE});
        tag.putLongArray("longs", new long[] {Long.MIN_VALUE, 9007199254740993L, Long.MAX_VALUE});
        tag.putByteArray("emptyBytes", new byte[0]);
        tag.putIntArray("emptyInts", new int[0]);
        tag.putLongArray("emptyLongs", new long[0]);
        ListTag ints = new ListTag(); ints.add(IntTag.valueOf(28)); ints.add(IntTag.valueOf(-3));
        tag.put("intList", ints);
        ListTag compounds = new ListTag();
        CompoundTag first = new CompoundTag(); first.putLong("value", Long.MAX_VALUE);
        CompoundTag second = new CompoundTag(); second.putString("name", "nested");
        compounds.add(first); compounds.add(second); tag.put("compoundList", compounds);
        ListTag lists = new ListTag(); lists.add(ints.copy()); lists.add(new ListTag());
        tag.put("listList", lists);
        CompoundTag nested = new CompoundTag(); nested.put("emptyCompound", new CompoundTag());
        nested.put("emptyList", new ListTag()); nested.put("values", ints.copy());
        tag.put("nested", nested);
        return tag;
    }

    private static void legacy(Stacks stacks, JsonObject report) {
        ItemStack source = stacks.add(new ItemStack(Items.STONE, 4));
        JsonElement plain = full(ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, source));
        require(!plain.getAsJsonObject().has(FORMAT) && !plain.getAsJsonObject().has("nbt"), "legacy is unmarked");
        same(source, stacks.add(full(RuntimeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, reparse(plain)))));
        source.getOrCreateTag().putInt("int28", 28);
        JsonElement old = full(ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, source));
        ItemStack vanilla = stacks.add(full(ItemStack.CODEC.parse(JsonOps.INSTANCE, reparse(old))));
        ItemStack compatible = stacks.add(full(RuntimeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, reparse(old))));
        require(source.getTag().get("int28").getId() == Tag.TAG_INT
                && vanilla.getTag().get("int28").getId() == Tag.TAG_BYTE
                && compatible.getTag().get("int28").getId() == Tag.TAG_BYTE,
                "actual legacy JsonOps narrows int28 to byte; compatibility preserves legacy behavior");
        same(vanilla, compatible);
        report.addProperty("legacyInt28SourceTagId", Tag.TAG_INT);
        report.addProperty("legacyInt28DecodedTagId", compatible.getTag().get("int28").getId());
    }

    private static void catalog(Stacks stacks, JsonArray checks) {
        ItemStack borrowed = stacks.add(new ItemStack(Items.DIAMOND, 3));
        borrowed.getOrCreateTag().putInt("int28", 28);
        borrowed.getOrCreateTag().putInt("Damage", 0);
        CompoundTag before = borrowed.save(new CompoundTag()).copy();
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(new ApiVersion(1, 0), Contribution.Side.COMMON);
        BasicItemModules.registerVanilla(builder, "1.0.0", "1.20.1");
        NativeItemFactories.register(builder, BasicItemModules.VANILLA_ITEM, BasicItemModules.VANILLA_MODULE,
                new NativeItemFactory<>(BasicItem.class, false, ignored -> borrowed));
        var registry = builder.freeze();
        require(registry.metadata().problems().isEmpty(), "healthy actual extension registry");
        var node = ObjectCatalog.activate(registry).capture(UUID.randomUUID(), Optional.empty(),
                EditorId.parse("native_persistence_probe:slot"), BasicItemModules.VANILLA_ITEM,
                BasicItem.class, new BasicItem(EditorId.parse("minecraft:diamond"), 3, OptionalInt.empty()));
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(registry, EditorId.parse("fpsmatch:item"))) {
            require(catalog.activationFailures().isEmpty(), "catalog activated registered native factory");
            JsonElement persisted = catalog.encodePersistent(node, () -> false).toJson();
            ItemStack parsed = stacks.add(full(RuntimeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, reparse(persisted))));
            same(borrowed, parsed);
            boolean legacyRefused = false;
            try { catalog.encode(node, () -> false); }
            catch (NativeItemException expected) {
                require(expected.problem().code().equals(EditorId.parse("editor:native_items/roundtrip_changed")),
                        "legacy certification rejects changed typed tags");
                legacyRefused = true;
            }
            require(legacyRefused, "legacy catalog certification must not silently accept numeric loss");
            require(catalog.encodePersistent(node, () -> false).toJson().equals(persisted),
                    "persistent catalog succeeds again after failed legacy certification");
        }
        require(before.equals(borrowed.save(new CompoundTag())), "catalog preserves borrowed factory source");
        checks.add("real_extension_registry_catalog_encodePersistent_preserves_typed_native_state_and_legacy_refuses_loss");
    }

    private static void malformed(JsonObject valid, byte[] plainBytes) {
        for (JsonElement version : new JsonElement[] {JsonParser.parseString("0"), JsonParser.parseString("2"),
                JsonParser.parseString("\"1\""), JsonParser.parseString("true"), JsonParser.parseString("null"),
                JsonParser.parseString("1.1")}) {
            JsonObject bad = valid.deepCopy(); bad.add(FORMAT, version); reject(bad, "unsupported version " + version);
        }
        JsonObject extra = valid.deepCopy(); extra.addProperty("extra", 1); reject(extra, "extra field");
        reject(envelope(raw(out -> { root(out, ""); named(out, Tag.TAG_STRING, "id"); out.writeUTF("minecraft:stone"); out.writeByte(0); })),
                "missing Count");
        reject(envelope(raw(out -> { root(out, ""); named(out, Tag.TAG_BYTE, "Count"); out.writeByte(1); out.writeByte(0); })),
                "missing item id");
        JsonObject markedLegacy = new JsonObject(); markedLegacy.addProperty("id", "minecraft:stone");
        markedLegacy.addProperty("Count", 1); markedLegacy.addProperty(FORMAT, 1);
        reject(markedLegacy, "format marker alone cannot fall back");
        markedLegacy.remove(FORMAT); markedLegacy.add("nbt", new JsonArray());
        reject(markedLegacy, "nbt marker alone cannot fall back");
        for (String text : new String[] {"", "!", "YQ", "YR==", "YQ==\n"})
            reject(envelopeText(text), "noncanonical or invalid base64");
        JsonObject noChunks = valid.deepCopy(); noChunks.add("nbt", new JsonArray()); reject(noChunks, "empty chunk array");
        JsonObject wrongNbt = valid.deepCopy(); wrongNbt.addProperty("nbt", "AAAA"); reject(wrongNbt, "nbt must be chunk array");
        JsonArray numeric = new JsonArray(); numeric.add(1);
        JsonObject numberChunk = valid.deepCopy(); numberChunk.add("nbt", numeric); reject(numberChunk, "chunk must be string");
        JsonArray shortChunks = new JsonArray(); shortChunks.add("AAAA"); shortChunks.add("AAAA");
        JsonObject shortMiddle = valid.deepCopy(); shortMiddle.add("nbt", shortChunks); reject(shortMiddle, "short nonfinal chunk");
        reject(envelopeText("A".repeat(16385)), "chunk longer than 16384");
        JsonArray excessiveChunks = new JsonArray();
        for (int i = 0; i < 343; i++) excessiveChunks.add("AAAA");
        JsonObject tooManyChunks = valid.deepCopy(); tooManyChunks.add("nbt", excessiveChunks);
        reject(tooManyChunks, "chunk count exceeds maximum base64 budget");
        reject(envelope(Arrays.copyOf(plainBytes, plainBytes.length + 1)), "trailing binary byte");
        reject(envelope(Arrays.copyOf(plainBytes, plainBytes.length - 1)), "truncated NBT");
        reject(envelope(raw(out -> {
            root(out, "named"); simpleFields(out, "minecraft:stone", 1); out.writeByte(0);
        })), "nonempty root name");
        reject(envelope(raw(out -> {
            root(out, ""); simpleFields(out, "minecraft:stone", 1);
            named(out, Tag.TAG_BYTE, "Count"); out.writeByte(1); out.writeByte(0);
        })), "duplicate compound key");
        reject(envelope(raw(out -> {
            root(out, ""); named(out, Tag.TAG_INT, "Count"); out.writeInt(1);
            named(out, Tag.TAG_STRING, "id"); out.writeUTF("minecraft:stone"); out.writeByte(0);
        })), "Count must be byte");
        for (String id : new String[] {"minecraft:does_not_exist", "broken::id"})
            reject(envelope(raw(out -> { root(out, ""); simpleFields(out, id, 1); out.writeByte(0); })), "unknown/invalid item id");
        for (int count : new int[] {-1, 0, 128})
            reject(envelope(raw(out -> { root(out, ""); simpleFields(out, "minecraft:stone", count); out.writeByte(0); })), "invalid stored Count");
        reject(envelope(raw(out -> {
            root(out, ""); simpleFields(out, "minecraft:stone", 1);
            named(out, Tag.TAG_STRING, "tag"); out.writeUTF("not a compound"); out.writeByte(0);
        })), "tag must be compound");
        reject(envelope(raw(out -> {
            root(out, ""); named(out, Tag.TAG_BYTE, "Count"); out.writeByte(1);
            named(out, Tag.TAG_STRING, "ForgeCaps"); out.writeUTF("wrong type");
            named(out, Tag.TAG_STRING, "id"); out.writeUTF("minecraft:stone"); out.writeByte(0);
        })), "ForgeCaps must be compound");
        reject(envelope(raw(out -> {
            root(out, ""); simpleFields(out, "minecraft:stone", 1);
            named(out, Tag.TAG_INT, "unknown"); out.writeInt(2); out.writeByte(0);
        })), "unknown saved NBT field cannot disappear");
        reject(envelope(raw(out -> {
            root(out, ""); named(out, Tag.TAG_STRING, "id"); out.writeUTF("minecraft:stone");
            named(out, Tag.TAG_BYTE, "Count"); out.writeByte(1); out.writeByte(0);
        })), "noncanonical compound key order");
    }

    private static void budgets(Stacks stacks, JsonArray checks, JsonObject report) {
        // Root=0, item tag=1, then 63 named children: deepest byte is exactly depth 64.
        ItemStack depth = stacks.add(new ItemStack(Items.STONE));
        CompoundTag top = new CompoundTag(); CompoundTag cursor = top;
        for (int i = 0; i < 62; i++) { CompoundTag child = new CompoundTag(); cursor.put("n", child); cursor = child; }
        cursor.putByte("v", (byte) 1); depth.setTag(top);
        roundtrip(depth, stacks);
        CompoundTag tooDeep = new CompoundTag(); tooDeep.put("n", top.copy()); depth.setTag(tooDeep);
        rejectEncode(depth, "depth 65 encode");
        reject(envelope(independent(depth.save(new CompoundTag()))), "depth 65 decode");
        checks.add("depth64_accepted_depth65_rejected_both_directions");

        // root, Count, id, tag, list = 5 nodes. Repeated IntTag identity still counts per occurrence.
        ItemStack nodes = stacks.add(new ItemStack(Items.STONE));
        ListTag list = new ListTag();
        for (int i = 0; i < 499995; i++) list.add(IntTag.valueOf(28));
        nodes.getOrCreateTag().put("l", list);
        roundtrip(nodes, stacks);
        list.add(IntTag.valueOf(28));
        rejectEncode(nodes, "500001 nodes encode");
        reject(envelope(independent(nodes.save(new CompoundTag()))), "500001 nodes decode");
        checks.add("500000_nodes_accepted_500001_rejected_both_directions");

        // Independent framing: root3 + Count9 + id22 + tag6 + byte-array header8 + ends2 = 50.
        // id22 = type1 + key UTF length2 + key2 + value UTF length2 + "minecraft:stone"15.
        byte[] emptyByteArrayVector = HexFormat.of().parseHex(
                "0a0000010005436f756e74010800026964000f6d696e6563726166743a73746f6e65"
                        + "0a000374616707000162000000000000");
        require(emptyByteArrayVector.length == 50, "independent empty byte-array framing vector is 50 bytes");
        ItemStack binary = stacks.add(new ItemStack(Items.STONE));
        binary.getOrCreateTag().putByteArray("b", new byte[0]);
        require(Arrays.equals(independent(binary.save(new CompoundTag())), emptyByteArrayVector),
                "independent native empty byte-array framing matches exact hex vector");
        binary.getOrCreateTag().putByteArray("b", new byte[MAX_BYTES - 50]);
        byte[] limit = independent(binary.save(new CompoundTag()));
        require(limit.length == MAX_BYTES, "independently calculated binary boundary");
        JsonObject encoded = encode(binary);
        require(Arrays.equals(limit, payload(encoded)), "binary at exact limit is canonical");
        roundtrip(binary, stacks);
        JsonArray chunks = encoded.getAsJsonArray("nbt");
        for (int i = 0; i < chunks.size() - 1; i++)
            require(chunks.get(i).getAsString().length() == 16384, "base64 chunk size exactly 16384");
        require(chunks.get(chunks.size() - 1).getAsString().length() <= 16384, "bounded final chunk");
        binary.getOrCreateTag().putByteArray("b", new byte[MAX_BYTES - 49]);
        rejectEncode(binary, "4MiB plus one encode");
        byte[] overLimit = independent(binary.save(new CompoundTag()));
        require(overLimit.length == MAX_BYTES + 1, "independently calculated binary plus-one boundary");
        reject(envelope(overLimit), "4MiB plus one decode");
        checks.add("exact_4MiB_binary_accepted_plus_one_rejected_and_16384_base64_chunks");
        report.addProperty("maxBinaryBytes", limit.length);
        report.addProperty("maxBinaryChunks", chunks.size());

        // 400000 uniquely named empty compounds: <4MiB wire / <500000 nodes, >32MiB NBT accounting.
        byte[] accounting = raw(out -> {
            root(out, ""); simpleFields(out, "minecraft:stone", 1); named(out, Tag.TAG_COMPOUND, "tag");
            for (int i = 0; i < 400000; i++) {
                String key = Integer.toString(100000 + i);
                named(out, Tag.TAG_COMPOUND, key); out.writeByte(0);
            }
            out.writeByte(0); out.writeByte(0);
        });
        require(accounting.length < MAX_BYTES, "accounting fixture within binary limit");
        boolean accounted = false;
        try { NbtIo.read(new DataInputStream(new ByteArrayInputStream(accounting)), new NbtAccounter(32L * 1024 * 1024)); }
        catch (IOException | RuntimeException expected) { accounted = true; }
        require(accounted, "real NbtAccounter rejects independently generated accounting fixture");
        reject(envelope(accounting), "32MiB accounting budget");
        checks.add("real_32MiB_accounting_rejection_with_wire_and_node_budgets_satisfied");
        report.addProperty("accountingFixtureBytes", accounting.length);
    }

    private static void aggregateCleanup(JsonArray checks, JsonObject report) {
        try (CapabilityScope caps = new CapabilityScope()) {
            try (Stacks stacks = new Stacks()) {
                ItemStack source = stacks.add(new ItemStack(Items.EMERALD, 2));
                source.getCapability(ForgeCapabilities.ITEM_HANDLER);
                CompoundTag before = source.save(new CompoundTag()).copy();
                require(caps.created.containsKey(source), "real Forge source capability attached");
                JsonObject valid = encode(source);
                ItemStack capRoundtrip = roundtrip(source, stacks);
                capRoundtrip.getCapability(ForgeCapabilities.ITEM_HANDLER);
                require(caps.created.containsKey(capRoundtrip), "real Forge roundtrip capability materialized");
                require(capRoundtrip.save(new CompoundTag()).getCompound("ForgeCaps")
                        .getCompound("native_persistence_probe:state").get("int28").getId() == Tag.TAG_INT,
                        "serialized ForgeCaps typed IntTag survives real roundtrip");
                JsonObject invalid = valid.deepCopy(); invalid.addProperty(FORMAT, 2);
                // Forge can retain serialized lazyData without firing AttachCapabilitiesEvent.
                // Materialize only in this fixture's decoding adapter, before observing provider counts.
                Codec<ItemStack> materialized = RuntimeItemStackCodec.CODEC.xmap(stack -> {
                    stack.getCapability(ForgeCapabilities.ITEM_HANDLER);
                    return stack;
                }, stack -> stack);
                Codec<List<ItemStack>> list = RuntimeItemStackCodec.owned(materialized.listOf());
                JsonArray values = new JsonArray(); values.add(valid); values.add(invalid);
                int baseline = caps.created.size();
                failedAggregate(list, values, "typed valid first malformed second");
                caps.requireCleanedSince(baseline, 1);

                JsonArray nested = new JsonArray();
                JsonArray first = new JsonArray(); first.add(valid); nested.add(first);
                JsonArray second = new JsonArray(); second.add(invalid); nested.add(second);
                baseline = caps.created.size();
                failedAggregate(RuntimeItemStackCodec.owned(list.listOf()), nested, "nested successful scope transfers ownership");
                caps.requireCleanedSince(baseline, 1);

                Codec<StackRecord> record = RuntimeItemStackCodec.owned(RecordCodecBuilder.create(instance -> instance.group(
                        materialized.fieldOf("stack").forGetter(StackRecord::stack),
                        Codec.INT.fieldOf("number").forGetter(StackRecord::number)).apply(instance, StackRecord::new)));
                JsonObject brokenRecord = new JsonObject(); brokenRecord.add("stack", valid);
                brokenRecord.addProperty("number", "bad integer"); baseline = caps.created.size();
                failedAggregate(record, brokenRecord, "record later field failed");
                caps.requireCleanedSince(baseline, 1);

                JsonElement legacy = full(ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, source));
                JsonArray legacyThenBad = new JsonArray(); legacyThenBad.add(legacy); legacyThenBad.add(invalid);
                baseline = caps.created.size();
                failedAggregate(list, legacyThenBad, "legacy valid first malformed second");
                caps.requireCleanedSince(baseline, 1);

                JsonArray twoThenBad = new JsonArray(); twoThenBad.add(valid); twoThenBad.add(valid); twoThenBad.add(invalid);
                RuntimeException cleanupRuntime = new IllegalStateException("controlled first cleanup RuntimeException");
                AssertionError cleanupError = new AssertionError("controlled second cleanup Error");
                baseline = caps.created.size();
                caps.failuresFrom = baseline; caps.firstFailure = cleanupRuntime; caps.secondFailure = cleanupError;
                try {
                    list.parse(JsonOps.INSTANCE, reparse(twoThenBad));
                    throw new AssertionError("cleanup Error must propagate");
                } catch (AssertionError observed) {
                    require(observed == cleanupError, "cleanup Error identity wins over cleanup RuntimeException");
                    require(Arrays.asList(observed.getSuppressed()).contains(cleanupRuntime), "cleanup Error retains RuntimeException");
                } finally { caps.stopFailures(); }
                caps.requireCleanedSince(baseline, 2);

                JsonArray twoGood = new JsonArray(); twoGood.add(valid); twoGood.add(valid);
                AssertionError primaryError = new AssertionError("controlled aggregate primary Error");
                RuntimeException secondaryRuntime = new IllegalStateException("controlled cleanup RuntimeException under primary");
                AssertionError secondaryError = new AssertionError("controlled cleanup Error under primary");
                Codec<List<ItemStack>> throwsAfterDecode = RuntimeItemStackCodec.owned(list.xmap(value -> {
                    throw primaryError;
                }, value -> value));
                baseline = caps.created.size();
                caps.failuresFrom = baseline; caps.firstFailure = secondaryRuntime; caps.secondFailure = secondaryError;
                try {
                    throwsAfterDecode.parse(JsonOps.INSTANCE, reparse(twoGood));
                    throw new AssertionError("primary Error must propagate");
                } catch (AssertionError observed) {
                    require(observed == primaryError, "existing primary Error retains exact identity");
                    require(Arrays.asList(observed.getSuppressed()).contains(secondaryRuntime)
                            && Arrays.asList(observed.getSuppressed()).contains(secondaryError),
                            "primary Error retains both attempted cleanup failures");
                } finally { caps.stopFailures(); }
                caps.requireCleanedSince(baseline, 2);

                JsonArray successInput = new JsonArray(); successInput.add(valid);
                baseline = caps.created.size();
                List<ItemStack> success = full(list.parse(JsonOps.INSTANCE, reparse(successInput)));
                require(success.size() == 1, "successful aggregate returns complete list");
                stacks.add(success.get(0));
                require(caps.created.size() == baseline + 1 && caps.invalidations.getOrDefault(success.get(0), 0) == 0,
                        "successful scope retains caller ownership after earlier failures");
                require(!caps.invalidations.containsKey(source) && !caps.invalidations.containsKey(ItemStack.EMPTY)
                        && before.equals(source.save(new CompoundTag())), "aggregate failures preserve source and global EMPTY");
            }
            for (ItemStack stack : caps.created.keySet())
                require(caps.invalidations.getOrDefault(stack, 0) == 1, "each actual provider invalidated exactly once");
            report.addProperty("observedCapabilityProviders", caps.created.size());
            report.addProperty("observedCapabilityInvalidations", caps.invalidations.size());
        }
        checks.add("serializable_ForgeCaps_IntTag_preserved_with_real_attach_provider");
        checks.add("owned_list_legacy_list_nested_list_and_record_failure_cleanup_counted_once_no_partial");
        checks.add("owned_success_retains_caller_ownership_and_recovers_after_failed_aggregate");
        checks.add("two_cleanup_failures_both_attempted_Error_identity_priority_and_existing_primary_Error_identity");
    }

    private record StackRecord(ItemStack stack, int number) {}
    private static <A> void failedAggregate(Codec<A> codec, JsonElement input, String label) {
        DataResult<A> result = codec.parse(JsonOps.INSTANCE, reparse(input));
        require(result.error().isPresent() && result.result().isEmpty()
                && result.resultOrPartial(ignored -> {}).isEmpty(), "aggregate rejects all partial values: " + label);
    }

    public static final class CapabilityScope implements AutoCloseable {
        private final IdentityHashMap<ItemStack, Integer> created = new IdentityHashMap<>();
        private final IdentityHashMap<ItemStack, Integer> invalidations = new IdentityHashMap<>();
        private boolean active = true;
        private int failuresFrom = Integer.MAX_VALUE;
        private Throwable firstFailure, secondFailure;
        private CapabilityScope() { MinecraftForge.EVENT_BUS.register(this); }
        @SubscribeEvent public void attach(AttachCapabilitiesEvent<ItemStack> event) {
            if (!active || event.getObject().getItem() != Items.EMERALD) return;
            ItemStack stack = event.getObject();
            require(!created.containsKey(stack), "provider attached once per stack identity");
            created.put(stack, created.size());
            ICapabilitySerializable<CompoundTag> provider = new ICapabilitySerializable<>() {
                private CompoundTag state = initial();
                private CompoundTag initial() { CompoundTag tag = new CompoundTag(); tag.putInt("int28", 28); return tag; }
                @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) { return LazyOptional.empty(); }
                @Override public CompoundTag serializeNBT() {
                    require(!invalidations.containsKey(stack), "provider cannot serialize after invalidation"); return state.copy();
                }
                @Override public void deserializeNBT(CompoundTag tag) { state = tag.copy(); }
            };
            event.addCapability(ResourceLocation.parse("native_persistence_probe:state"), provider);
            event.addListener(() -> {
                require(stack != ItemStack.EMPTY, "never invalidate global EMPTY");
                invalidations.merge(stack, 1, Integer::sum);
                int index = created.get(stack) - failuresFrom;
                Throwable failure = index == 0 ? firstFailure : index == 1 ? secondFailure : null;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
            });
        }
        private void requireCleanedSince(int baseline, int expected) {
            require(created.size() == baseline + expected, "actual successful item decodes observed before aggregate failure");
            for (var entry : created.entrySet()) if (entry.getValue() >= baseline)
                require(invalidations.getOrDefault(entry.getKey(), 0) == 1, "failed aggregate invalidates each decoded identity once");
        }
        private void stopFailures() { failuresFrom = Integer.MAX_VALUE; firstFailure = null; secondFailure = null; }
        @Override public void close() {
            active = false;
            try { MinecraftForge.EVENT_BUS.unregister(this); }
            finally { created.clear(); invalidations.clear(); }
        }
    }

    private static ItemStack roundtrip(ItemStack source, Stacks stacks) {
        CompoundTag before = source.save(new CompoundTag()).copy();
        CompoundTag beforeTag = source.getTag() == null ? null : source.getTag().copy();
        JsonObject json = encode(source);
        ItemStack decoded = stacks.add(full(RuntimeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, reparse(json))));
        require(decoded != source && decoded != ItemStack.EMPTY, "decoded independently owned identity");
        same(source, decoded);
        require(before.equals(source.save(new CompoundTag())) && Objects.equals(beforeTag, source.getTag()), "source unchanged");
        require(before.equals(decoded.save(new CompoundTag())), "complete native saved NBT unchanged");
        return decoded;
    }

    private static void same(ItemStack expected, ItemStack actual) {
        require(expected.getItem() == actual.getItem() && expected.getCount() == actual.getCount()
                && expected.isEmpty() == actual.isEmpty() && Objects.equals(expected.getTag(), actual.getTag())
                && expected.save(new CompoundTag()).equals(actual.save(new CompoundTag())), "complete native stack equality");
    }

    private static JsonObject encode(ItemStack stack) {
        return full(RuntimeItemStackCodec.CODEC.encodeStart(JsonOps.INSTANCE, stack)).getAsJsonObject();
    }
    private static JsonElement reparse(JsonElement json) { return JsonParser.parseString(json.toString()); }
    private static <T> T full(DataResult<T> result) { return result.getOrThrow(false, ignored -> {}); }
    private static void rejectEncode(ItemStack stack, String label) {
        DataResult<JsonElement> result = RuntimeItemStackCodec.CODEC.encodeStart(JsonOps.INSTANCE, stack);
        require(result.error().isPresent() && result.resultOrPartial(ignored -> {}).isEmpty(), "must reject encode: " + label);
    }
    private static void reject(JsonObject json, String label) {
        DataResult<ItemStack> result = RuntimeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, reparse(json));
        ItemStack unexpected = result.resultOrPartial(ignored -> {}).orElse(null);
        try { require(result.error().isPresent() && unexpected == null, "must reject decode without partial: " + label); }
        finally { if (unexpected != null && unexpected != ItemStack.EMPTY) unexpected.invalidateCaps(); }
    }
    private static byte[] payload(JsonObject json) {
        StringBuilder text = new StringBuilder();
        for (JsonElement part : json.getAsJsonArray("nbt")) text.append(part.getAsString());
        return Base64.getDecoder().decode(text.toString());
    }
    private static JsonObject envelopeText(String text) {
        JsonObject json = new JsonObject(); json.addProperty(FORMAT, 1);
        JsonArray chunks = new JsonArray(); chunks.add(text); json.add("nbt", chunks); return json;
    }
    private static JsonObject envelope(byte[] bytes) {
        String text = Base64.getEncoder().encodeToString(bytes);
        JsonObject json = new JsonObject(); json.addProperty(FORMAT, 1);
        JsonArray chunks = new JsonArray();
        for (int i = 0; i < text.length(); i += 16384) chunks.add(text.substring(i, Math.min(text.length(), i + 16384)));
        json.add("nbt", chunks); return json;
    }
    @FunctionalInterface private interface Bytes { void write(DataOutputStream output) throws IOException; }
    private static byte[] raw(Bytes writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes); writer.write(output); output.flush(); return bytes.toByteArray();
        } catch (IOException failure) { throw new AssertionError("in-memory independent NBT fixture", failure); }
    }
    private static void root(DataOutputStream output, String name) throws IOException { output.writeByte(Tag.TAG_COMPOUND); output.writeUTF(name); }
    private static void named(DataOutputStream output, int type, String name) throws IOException { output.writeByte(type); output.writeUTF(name); }
    private static void simpleFields(DataOutputStream output, String id, int count) throws IOException {
        named(output, Tag.TAG_BYTE, "Count"); output.writeByte(count);
        named(output, Tag.TAG_STRING, "id"); output.writeUTF(id);
    }
    /** Fixture writer uses vanilla payload serialization after recursively ordering compounds. */
    private static byte[] independent(CompoundTag tag) {
        return raw(output -> { root(output, ""); writeFixture(tag, output); });
    }
    private static void writeFixture(Tag tag, DataOutputStream output) throws IOException {
        if (tag instanceof CompoundTag compound) {
            String[] keys = compound.getAllKeys().toArray(String[]::new); Arrays.sort(keys);
            for (String key : keys) {
                Tag value = compound.get(key); named(output, value.getId(), key); writeFixture(value, output);
            }
            output.writeByte(0);
        } else if (tag instanceof ListTag list) {
            output.writeByte(list.getElementType()); output.writeInt(list.size());
            for (Tag value : list) writeFixture(value, output);
        } else tag.write(output);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Stacks implements AutoCloseable {
        private final IdentityHashMap<ItemStack, Boolean> values = new IdentityHashMap<>();
        ItemStack add(ItemStack value) { if (value != ItemStack.EMPTY) values.put(value, Boolean.TRUE); return value; }
        @Override public void close() {
            Throwable first = null;
            for (ItemStack stack : values.keySet()) {
                try { stack.invalidateCaps(); }
                catch (RuntimeException | Error failure) {
                    if (first == null) first = failure; else if (first != failure) first.addSuppressed(failure);
                }
            }
            values.clear();
            if (first instanceof RuntimeException failure) throw failure;
            if (first instanceof Error failure) throw failure;
        }
    }
}
