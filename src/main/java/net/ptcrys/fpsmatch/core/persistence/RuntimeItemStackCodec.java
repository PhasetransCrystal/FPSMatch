package net.ptcrys.fpsmatch.core.persistence;

import net.ptcrys.fpsmatch.FPSMatch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Versioned full native persistence; unmarked legacy ItemStack JSON remains readable. */
public final class RuntimeItemStackCodec {

    private static final String FORMAT = "fpsmatch:item_format";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int CHUNK = 16384;
    private static final int MAX_BASE64 = ((MAX_BYTES + 2) / 3) * 4;
    private static final ThreadLocal<DecodeScope> DECODING = new ThreadLocal<>();

    public static final Codec<ItemStack> CODEC = new Codec<>() {

        @Override
        public <T> DataResult<Pair<ItemStack, T>> decode(DynamicOps<T> ops, T input) {
            var map = ops.getMap(input).result();
            if (map.isEmpty() || (map.get().get(FORMAT) == null && map.get().get("nbt") == null)) {
                return ItemStack.CODEC.decode(ops, input).map(pair -> {
                    ownDecoded(pair.getFirst());
                    return pair;
                });
            }
            try {
                JsonElement json = ops.convertTo(JsonOps.INSTANCE, input);
                ItemStack parsed = read(json);
                ownDecoded(parsed);
                return DataResult.success(Pair.of(parsed, ops.empty()));
            } catch (IOException | RuntimeException failure) {
                FPSMatch.LOGGER.warn("Failed to decode complete native item persistence", failure);
                return DataResult.error(() -> "Invalid or changed typed item persistence");
            }
        }

        @Override
        public <T> DataResult<T> encode(ItemStack stack, DynamicOps<T> ops, T prefix) {
            try {
                T value = JsonOps.INSTANCE.convertTo(ops, write(stack));
                return ops.mergeToMap(prefix, ops.getMap(value).getOrThrow(false, ignored -> {}));
            } catch (IOException | RuntimeException failure) {
                FPSMatch.LOGGER.warn("Failed to encode complete native item persistence", failure);
                return DataResult.error(() -> "Cannot persist complete native item");
            }
        }
    };

    private RuntimeItemStackCodec() {}

    /** Rejects partial aggregates and disposes all stacks decoded before a later field failed. */
    public static <A> Codec<A> owned(Codec<A> codec) {
        return new Codec<>() {

            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                DecodeScope parent = DECODING.get();
                DecodeScope scope = new DecodeScope();
                DECODING.set(scope);
                boolean accepted = false;
                Throwable primary = null;
                try {
                    DataResult<Pair<A, T>> result = codec.decode(ops, input);
                    if (result.error().isPresent()) {
                        String message = result.error().orElseThrow().message();
                        return DataResult.error(() -> message);
                    }
                    if (parent != null) parent.items.putAll(scope.items);
                    accepted = true;
                    return result;
                } catch (RuntimeException | Error failure) {
                    primary = failure;
                    throw failure;
                } finally {
                    if (parent == null) DECODING.remove();
                    else DECODING.set(parent);
                    if (!accepted) scope.close(primary);
                }
            }

            @Override
            public <T> DataResult<T> encode(A value, DynamicOps<T> ops, T prefix) {
                return codec.encode(value, ops, prefix);
            }
        };
    }

    /** Registers an independent replacement created by an aggregate decoder. */
    public static void ownDecoded(ItemStack stack) {
        DecodeScope scope = DECODING.get();
        if (scope != null && stack != ItemStack.EMPTY) scope.items.put(stack, Boolean.TRUE);
    }

    /** A decoder copied this input; release it rather than leaving an unreachable provider. */
    public static void releaseDecoded(ItemStack stack) {
        DecodeScope scope = DECODING.get();
        if (scope != null) scope.items.remove(stack);
        if (stack != ItemStack.EMPTY) stack.invalidateCaps();
    }

    /** Disposes both copied inputs even when one provider fails; preserves a primary Error. */
    public static void releaseDecodedInputs(Throwable primary, ItemStack first, ItemStack second) {
        Throwable failure = primary;
        for (ItemStack stack : first == second ? new ItemStack[] { first } : new ItemStack[] { first, second }) {
            try {
                releaseDecoded(stack);
            } catch (RuntimeException | Error cleanup) {
                failure = mergeCleanup(primary, failure, cleanup);
            }
        }
        throwCleanup(primary, failure);
    }

    private static Throwable mergeCleanup(Throwable primary, Throwable previous, Throwable cleanup) {
        if (previous == null) return cleanup;
        if (previous == cleanup) return previous;
        if (primary == null && cleanup instanceof Error && !(previous instanceof Error)) {
            cleanup.addSuppressed(previous);
            return cleanup;
        }
        previous.addSuppressed(cleanup);
        return previous;
    }

    private static void throwCleanup(Throwable primary, Throwable failure) {
        if (primary == null) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }

    private static final class DecodeScope {

        final IdentityHashMap<ItemStack, Boolean> items = new IdentityHashMap<>();

        void close(Throwable primary) {
            Throwable failure = primary;
            for (ItemStack item : items.keySet()) {
                try {
                    item.invalidateCaps();
                } catch (RuntimeException | Error cleanup) {
                    failure = mergeCleanup(primary, failure, cleanup);
                }
            }
            items.clear();
            throwCleanup(primary, failure);
        }
    }

    private static JsonObject write(ItemStack stack) throws IOException {
        if (stack == null) throw new IllegalArgumentException("null stack");
        int count = stack.getCount();
        var item = stack.getItem();
        CompoundTag tag = stack.getTag() == null ? null : stack.getTag().copy();
        require((item == Items.AIR && count == 0 && tag == null) || (item != Items.AIR && count >= 1 && count <= 127));
        CompoundTag saved = stack.save(new CompoundTag());
        require(stack.getCount() == count && stack.getItem() == item && Objects.equals(tag, stack.getTag()));
        require(saved.contains("Count", Tag.TAG_BYTE) && saved.getByte("Count") == count);
        String base64 = Base64.getEncoder().encodeToString(binary(saved));
        JsonArray chunks = new JsonArray();
        for (int offset = 0; offset < base64.length(); offset += CHUNK) {
            chunks.add(base64.substring(offset, Math.min(offset + CHUNK, base64.length())));
        }
        JsonObject result = new JsonObject();
        result.addProperty(FORMAT, 1);
        result.add("nbt", chunks);
        return result;
    }

    private static ItemStack read(JsonElement input) throws IOException {
        require(input.isJsonObject());
        JsonObject object = input.getAsJsonObject();
        require(object.keySet().equals(Set.of(FORMAT, "nbt")));
        JsonElement version = object.get(FORMAT);
        require(version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber() && version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) == 0);
        require(object.get("nbt").isJsonArray());
        JsonArray chunks = object.getAsJsonArray("nbt");
        require(!chunks.isEmpty() && chunks.size() <= (MAX_BASE64 + CHUNK - 1) / CHUNK);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            JsonElement chunk = chunks.get(i);
            require(chunk.isJsonPrimitive() && chunk.getAsJsonPrimitive().isString());
            String text = chunk.getAsString();
            require(!text.isEmpty() && text.length() <= CHUNK && (i == chunks.size() - 1 || text.length() == CHUNK));
            require(encoded.length() <= MAX_BASE64 - text.length());
            encoded.append(text);
        }
        byte[] bytes = Base64.getDecoder().decode(encoded.toString());
        require(bytes.length <= MAX_BYTES && Base64.getEncoder().encodeToString(bytes).contentEquals(encoded));
        DataInputStream stream = new DataInputStream(new ByteArrayInputStream(bytes));
        CompoundTag saved = NbtIo.read(stream, new NbtAccounter(32L * 1024 * 1024));
        require(stream.available() == 0 && Arrays.equals(bytes, binary(saved)));
        require(saved.contains("id", Tag.TAG_STRING) && saved.contains("Count", Tag.TAG_BYTE));
        require(!saved.contains("tag") || saved.contains("tag", Tag.TAG_COMPOUND));
        require(!saved.contains("ForgeCaps") || saved.contains("ForgeCaps", Tag.TAG_COMPOUND));
        ItemStack parsed = ItemStack.of(saved.copy());
        require(parsed != ItemStack.EMPTY);
        boolean accepted = false;
        Throwable primary = null;
        try {
            require((parsed.getItem() == Items.AIR && parsed.getCount() == 0 && parsed.getTag() == null) || (!parsed.isEmpty() && parsed.getCount() >= 1 && parsed.getCount() <= 127));
            require(Objects.equals(parsed.getTag(), saved.contains("tag") ? saved.getCompound("tag") : null));
            require(saved.equals(parsed.save(new CompoundTag())));
            require(parsed.getCount() == saved.getByte("Count") && Objects.equals(parsed.getTag(), saved.contains("tag") ? saved.getCompound("tag") : null));
            accepted = true;
            return parsed;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (!accepted && parsed != ItemStack.EMPTY) {
                try {
                    parsed.invalidateCaps();
                } catch (RuntimeException | Error cleanup) {
                    if (primary == null) throw cleanup;
                    if (primary != cleanup) primary.addSuppressed(cleanup);
                }
            }
        }
    }

    /** Compound iteration order must not affect fixed runtime bytes or restart verification. */
    private static byte[] binary(CompoundTag saved) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream() {

            @Override
            public synchronized void write(int value) {
                require(count < MAX_BYTES);
                super.write(value);
            }

            @Override
            public synchronized void write(byte[] value, int offset, int length) {
                require(length <= MAX_BYTES - count);
                super.write(value, offset, length);
            }
        };
        DataOutputStream output = new DataOutputStream(buffer);
        output.writeByte(Tag.TAG_COMPOUND);
        output.writeUTF("");
        body(saved, output, 0, new int[1]);
        return buffer.toByteArray();
    }

    private static void body(Tag tag, DataOutputStream output, int depth, int[] nodes) throws IOException {
        require(depth <= 64 && ++nodes[0] <= 500000);
        if (tag instanceof CompoundTag compound) {
            for (String key : new TreeSet<>(compound.getAllKeys())) {
                Tag child = compound.get(key);
                output.writeByte(child.getId());
                output.writeUTF(key);
                body(child, output, depth + 1, nodes);
            }
            output.writeByte(Tag.TAG_END);
        } else if (tag instanceof ListTag list) {
            output.writeByte(list.getElementType());
            output.writeInt(list.size());
            for (Tag child : list) body(child, output, depth + 1, nodes);
        } else {
            tag.write(output);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid typed item persistence");
    }
}
