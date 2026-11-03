package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item;

import net.ptcrys.fpsmatch.core.persistence.RuntimeItemStackCodec;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectCatalog;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectNode;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/** Owner-thread native construction from one frozen registry, with no retained returned stacks. */
public final class NativeItemCatalog implements AutoCloseable {

    private record StackState(boolean empty, int count, Item item, CompoundTag tag) {}

    private final Thread owner;
    private final EditorId expectedRole;
    private final ObjectCatalog objects;
    private final Set<ContributionKey> contributions;
    private final Map<EditorId, NativeItemFactory<?>> factories;
    private final Map<EditorId, RuntimeException> failures;
    private boolean busy;
    private boolean closed;

    private NativeItemCatalog(Thread owner, EditorId expectedRole, ObjectCatalog objects,
                              Set<ContributionKey> contributions, Map<EditorId, NativeItemFactory<?>> factories,
                              Map<EditorId, RuntimeException> failures) {
        this.owner = owner;
        this.expectedRole = expectedRole;
        this.objects = objects;
        this.contributions = Set.copyOf(contributions);
        this.factories = Collections.unmodifiableMap(new TreeMap<>(factories));
        this.failures = Collections.unmodifiableMap(new TreeMap<>(failures));
    }

    public static NativeItemCatalog activate(ExtensionRegistry registry, EditorId expectedRole) {
        Thread owner = Thread.currentThread();
        if (registry == null || expectedRole == null) {
            throw NativeItemMessages.error("invalid_registration", null);
        }
        Map<EditorId, NativeItemFactory<?>> offered;
        Map<EditorId, ValueType<?>> values;
        try {
            // These exact public lookups reject undefined kinds and incompatible Class identities.
            offered = registry.entries(NativeItemFactories.KIND);
            values = registry.entries(ValueTypes.KIND);
        } catch (RuntimeException failure) {
            throw NativeItemMessages.caused("invalid_registration", null, failure);
        }
        ObjectCatalog objects = ObjectCatalog.activate(registry);
        Map<EditorId, NativeItemFactory<?>> factories = new TreeMap<>();
        Map<EditorId, RuntimeException> failures = new TreeMap<>();
        for (Map.Entry<EditorId, NativeItemFactory<?>> entry : offered.entrySet()) {
            EditorId typeId = entry.getKey();
            RuntimeException activationFailure = objects.activationFailures().get(typeId);
            if (activationFailure != null) {
                failures.put(typeId, activationFailure instanceof DocumentCodecException ? activationFailure : NativeItemMessages.caused(
                        "invalid_registration", typeId, activationFailure));
                continue;
            }
            try {
                Contribution metadata = registry.metadata().contributions().get(
                        new ContributionKey(NativeItemFactories.KIND.id(), typeId));
                ValueType<?> value = values.get(typeId);
                if (metadata == null || !metadata.requiredContributions().contains(
                        new ContributionKey(ValueTypes.KIND.id(), typeId)) || !(value instanceof ObjectType<?> object) || object.valueType() != entry.getValue().valueType() || !expectedRole.equals(object.role())) {
                    throw NativeItemMessages.error("invalid_registration", typeId);
                }
                factories.put(typeId, entry.getValue());
            } catch (DocumentCodecException failure) {
                failures.put(typeId, failure);
            } catch (RuntimeException failure) {
                failures.put(typeId, failure instanceof NativeItemException ? failure : NativeItemMessages.caused("invalid_registration", typeId, failure));
            }
        }
        return new NativeItemCatalog(owner, expectedRole, objects,
                registry.metadata().contributions().keySet(), factories, failures);
    }

    public Map<EditorId, RuntimeException> activationFailures() {
        return failures;
    }

    public ItemStack create(ObjectNode node, BooleanSupplier cancelled) {
        requireInput(node, cancelled);
        busy = true;
        try {
            return createValue(node, cancelled, null);
        } finally {
            busy = false;
        }
    }

    /**
     * Certifies the real JSON codec against the complete native body and persisted NBT.
     * Temporary stacks are invalidated before return. Invalidation can invoke third-party capability
     * callbacks; neither cleanup nor cancellation can undo arbitrary external callback side effects.
     */
    public ExactJson.FrozenJson encode(ObjectNode node, BooleanSupplier cancelled) {
        return encode(node, cancelled, ItemStack.CODEC);
    }

    /** Complete typed NBT for runtime installation; legacy JSON certification remains available. */
    public ExactJson.FrozenJson encodePersistent(ObjectNode node, BooleanSupplier cancelled) {
        return encode(node, cancelled, RuntimeItemStackCodec.CODEC);
    }

    private ExactJson.FrozenJson encode(ObjectNode node, BooleanSupplier cancelled, Codec<ItemStack> codec) {
        EditorId typeId = requireInput(node, cancelled);
        EncodingScope scope = new EncodingScope(cancelled);
        busy = true;
        Throwable primary = null;
        ExactJson.FrozenJson frozen = null;
        NativeState state = null;
        try {
            ItemStack stack = createValue(node, scope, scope);
            // Capture before another cancellation callback can observe or alter the owned stack.
            state = NativeState.capture(stack, typeId);
            encodingCheckpoint(scope, stack, state, typeId);
            JsonElement json = fullResult(codec.encodeStart(JsonOps.INSTANCE, stack));
            encodingCheckpoint(scope, stack, state, typeId);
            frozen = ExactJson.freeze(json);
            if (!frozen.toJson().isJsonObject()) throw NativeItemMessages.error("codec_failed", typeId);
            encodingCheckpoint(scope, stack, state, typeId);
            ItemStack parsed = fullResult(codec.parse(JsonOps.INSTANCE, frozen.toJson()));
            scope.parsed = parsed;
            encodingCheckpoint(scope, stack, state, typeId);
            state.requireUnchanged(parsed, typeId);
            encodingCheckpoint(scope, stack, state, typeId);
            // The cancellation callback above may know the parsed stack through a capability hook.
            state.requireUnchanged(parsed, typeId);
            state.requireBody(stack, typeId);
            requireOpen();
        } catch (DocumentCodecException | NativeItemException failure) {
            primary = failure;
        } catch (RuntimeException failure) {
            primary = NativeItemMessages.caused("codec_failed", typeId, failure);
        } catch (Error failure) {
            primary = failure;
        } finally {
            try {
                primary = scope.cleanup(primary, typeId);
                // A cleanup callback may close the catalog or signal cancellation.
                if (primary == null) {
                    try {
                        checkpoint(scope, typeId);
                        // Owned capabilities have been invalidated. Do not invoke serialization
                        // hooks after this final cancellation gate or revalidate disposed state.
                        requireOpen();
                    } catch (DocumentCodecException | NativeItemException failure) {
                        primary = failure;
                    } catch (RuntimeException failure) {
                        primary = NativeItemMessages.caused("codec_failed", typeId, failure);
                    } catch (Error failure) {
                        primary = failure;
                    }
                }
            } finally {
                busy = false;
            }
        }
        if (primary instanceof RuntimeException failure) throw failure;
        if (primary instanceof Error failure) throw failure;
        return Objects.requireNonNull(frozen);
    }

    private EditorId requireInput(ObjectNode node, BooleanSupplier cancelled) {
        requireOpen();
        if (busy) throw NativeItemMessages.error("busy");
        EditorId typeId = node == null ? null : node.value().typeId();
        if (node == null || cancelled == null || !expectedRole.equals(node.valueRole())) {
            throw NativeItemMessages.error("invalid_node", typeId);
        }
        return typeId;
    }

    private ItemStack createValue(ObjectNode node, BooleanSupplier cancelled, EncodingScope scope) {
        EditorId typeId = node.value().typeId();
        checkpoint(cancelled, typeId);
        ObjectNode adopted;
        try {
            adopted = objects.adopt(node);
        } catch (DocumentCodecException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw NativeItemMessages.caused("invalid_node", typeId, failure);
        }
        checkpoint(cancelled, typeId);
        if (!expectedRole.equals(adopted.valueRole()) || !contributions.containsAll(adopted.value().dependencies().contributions())) {
            throw NativeItemMessages.error("invalid_node", typeId);
        }
        typeId = adopted.value().typeId();
        NativeItemFactory<?> factory = factories.get(typeId);
        if (factory == null) throw NativeItemMessages.error("unavailable", typeId);
        ItemStack borrowed = construct(factory, adopted.value().content(), typeId);
        try {
            if (borrowed == null) throw NativeItemMessages.error("factory_failed", typeId);
            boolean empty = borrowed.isEmpty();
            if (empty && !factory.permitsEmpty()) {
                throw NativeItemMessages.error("empty_result", typeId);
            }
            // Capture before another callback or copy hook can mutate the borrowed value.
            int count = borrowed.getCount();
            Item item = borrowed.getItem();
            CompoundTag tag = borrowed.getTag();
            StackState state = new StackState(empty, count, item, tag == null ? null : tag.copy());
            if (empty && (state.count() != 0 || state.tag() != null)) {
                throw NativeItemMessages.error("factory_failed", typeId);
            }
            requireMatches(borrowed, state);
            checkpoint(cancelled, typeId);
            requireMatches(borrowed, state);
            ItemStack result = empty ? new ItemStack(Items.AIR, 0) : borrowed.copy();
            // Track only an independently owned identity, before validation or another callback.
            if (scope != null && result != null && result != borrowed && result != ItemStack.EMPTY) {
                scope.created = result;
            }
            requireIsolated(borrowed, result, state);
            checkpoint(cancelled, typeId);
            // No user callback may run after this final validation.
            requireIsolated(borrowed, result, state);
            requireOpen();
            return result;
        } catch (DocumentCodecException failure) {
            throw failure;
        } catch (NativeItemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw NativeItemMessages.caused("factory_failed", typeId, failure);
        }
    }

    private void encodingCheckpoint(EncodingScope scope, ItemStack stack, NativeState state, EditorId typeId) {
        requireOpen();
        state.requireUnchanged(stack, typeId);
        checkpoint(scope, typeId);
        state.requireUnchanged(stack, typeId);
        requireOpen();
    }

    private static <T> T fullResult(DataResult<T> result) {
        // false refuses partial values; any diagnostic exception is retained only as a codec cause.
        return result.getOrThrow(false, ignored -> {});
    }

    private record NativeState(StackState body, CompoundTag saved) {

        static NativeState capture(ItemStack stack, EditorId typeId) {
            CompoundTag tag = stack.getTag();
            StackState body = new StackState(stack.isEmpty(), stack.getCount(), stack.getItem(),
                    tag == null ? null : tag.copy());
            NativeState state = new NativeState(body, stack.save(new CompoundTag()).copy());
            state.requireBody(stack, typeId);
            return state;
        }

        void requireBody(ItemStack stack, EditorId typeId) {
            if (stack == null || stack.isEmpty() != body.empty() || stack.getItem() != body.item() || stack.getCount() != body.count() || !Objects.equals(stack.getTag(), body.tag())) {
                throw NativeItemMessages.error("roundtrip_changed", typeId);
            }
        }

        void requireUnchanged(ItemStack stack, EditorId typeId) {
            requireBody(stack, typeId);
            CompoundTag current = stack.save(new CompoundTag());
            requireBody(stack, typeId);
            if (!saved.equals(current)) throw NativeItemMessages.error("roundtrip_changed", typeId);
        }
    }

    /** All state is per invocation, including cancellation observed during construction. */
    private static final class EncodingScope implements BooleanSupplier {

        private final BooleanSupplier cancelled;
        private boolean cancellationSeen;
        private ItemStack created;
        private ItemStack parsed;

        EncodingScope(BooleanSupplier cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean getAsBoolean() {
            if (!cancellationSeen) cancellationSeen = cancelled.getAsBoolean();
            return cancellationSeen;
        }

        Throwable cleanup(Throwable primary, EditorId typeId) {
            Throwable first = invalidate(created);
            Throwable second = parsed == created ? null : invalidate(parsed);
            if (primary != null) {
                suppress(primary, first);
                suppress(primary, second);
                return primary;
            }
            // A cleanup Error keeps its identity, but never prevents the other stack's attempt.
            if (first instanceof Error) {
                suppress(first, second);
                return first;
            }
            if (second instanceof Error) {
                suppress(second, first);
                return second;
            }
            if (first == null && second == null) return null;
            Throwable cause = first == null ? second : first;
            NativeItemException failure = NativeItemMessages.error("cleanup_failed", typeId);
            failure.initCause(cause);
            if (first != null) suppress(failure, second);
            return failure;
        }

        private static Throwable invalidate(ItemStack stack) {
            if (stack == null || stack == ItemStack.EMPTY) return null;
            try {
                stack.invalidateCaps();
                return null;
            } catch (RuntimeException | Error failure) {
                return failure;
            }
        }

        private static void suppress(Throwable primary, Throwable secondary) {
            if (secondary != null && secondary != primary) primary.addSuppressed(secondary);
        }
    }

    private static <T> ItemStack construct(NativeItemFactory<T> factory, Object value, EditorId typeId) {
        try {
            return factory.factory().apply(factory.valueType().cast(value));
        } catch (DocumentCodecException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw NativeItemMessages.caused("factory_failed", typeId, failure);
        }
    }

    private static void requireIsolated(ItemStack borrowed, ItemStack copy, StackState state) {
        if (copy == null || copy == borrowed || copy == ItemStack.EMPTY || (borrowed.getTag() != null && copy.getTag() == borrowed.getTag())) {
            throw new IllegalStateException("native item copy changed its borrowed value");
        }
        requireMatches(borrowed, state);
        requireMatches(copy, state);
    }

    private static void requireMatches(ItemStack stack, StackState state) {
        if (stack.isEmpty() != state.empty() || stack.getCount() != state.count() || stack.getItem() != state.item() || !Objects.equals(stack.getTag(), state.tag())) {
            throw new IllegalStateException("native item value changed after construction");
        }
    }

    private void checkpoint(BooleanSupplier cancelled, EditorId typeId) {
        requireOpen();
        boolean cancellation;
        try {
            cancellation = cancelled.getAsBoolean();
        } catch (DocumentCodecException failure) {
            requireOpen();
            throw failure;
        } catch (RuntimeException failure) {
            requireOpen();
            throw NativeItemMessages.caused("factory_failed", typeId, failure);
        }
        requireOpen();
        if (cancellation) throw NativeItemMessages.error("cancelled", typeId);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw NativeItemMessages.error("wrong_thread");
    }

    private void requireOpen() {
        requireOwner();
        if (closed) throw NativeItemMessages.error("closed");
    }

    /** Closing from an owner callback invalidates its in-flight result. */
    @Override
    public void close() {
        requireOwner();
        closed = true;
    }
}
