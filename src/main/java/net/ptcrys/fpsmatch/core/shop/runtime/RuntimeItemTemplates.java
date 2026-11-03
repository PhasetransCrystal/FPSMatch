package net.ptcrys.fpsmatch.core.shop.runtime;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Fixed, owner-thread templates. Delivery stacks belong to the caller, not this owner. */
public final class RuntimeItemTemplates implements AutoCloseable {

    private final Thread owner = Thread.currentThread();
    private final int maxTemplates;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final IdentityHashMap<ItemStack, Boolean> identities = new IdentityHashMap<>();
    private boolean busy;
    private boolean sealed;
    private boolean closed;

    public RuntimeItemTemplates(int maxTemplates) {
        if (maxTemplates < 1 || maxTemplates > 8192) throw RuntimeItemMessages.error("invalid_input");
        this.maxTemplates = maxTemplates;
    }

    public Template capture(ItemStack borrowed, Consumer<ItemStack> initialize,
                            BooleanSupplier cancelled) {
        requireAvailable();
        if (sealed) throw RuntimeItemMessages.error("sealed");
        if (entries.size() >= maxTemplates) throw RuntimeItemMessages.error("budget");
        if (borrowed == null || initialize == null || cancelled == null) {
            throw RuntimeItemMessages.error("invalid_input");
        }
        busy = true;
        ItemStack temporary = null;
        Throwable failure = null;
        try {
            checkpoint(cancelled);
            Body source = Body.capture(borrowed);
            CompoundTag saved = borrowed.save(new CompoundTag()).copy();
            source.requireMatches(borrowed);
            requireSaved(borrowed, source, saved);
            ItemStack copied = source.empty ? new ItemStack(Items.AIR, 0) : borrowed.copy();
            // Establish temporary ownership before any further native calls or callbacks.
            if (isIndependentIdentity(borrowed, copied)) temporary = copied;
            requireIsolated(borrowed, copied, source);
            requireSaved(borrowed, source, saved);
            requireSaved(copied, source, saved);
            checkpoint(cancelled);
            requireSaved(borrowed, source, saved);
            requireIsolated(borrowed, copied, source);
            initialize.accept(copied);
            Body initialized = Body.capture(copied);
            if (initialized.empty && (initialized.item != Items.AIR || initialized.count != 0 || initialized.tag != null)) {
                throw RuntimeItemMessages.error("invalid_copy");
            }
            CompoundTag initializedSaved = copied.save(new CompoundTag()).copy();
            initialized.requireMatches(copied);
            checkpoint(cancelled);
            requireSaved(borrowed, source, saved);
            requireSaved(copied, initialized, initializedSaved);
            source.requireMatches(borrowed);
            initialized.requireMatches(copied);
            // Initialization may change the private body, but may not adopt a borrowed tag.
            if (borrowed.getTag() != null && copied.getTag() == borrowed.getTag()) {
                throw RuntimeItemMessages.error("invalid_copy");
            }
            requireOpen();
            Entry entry = new Entry(copied);
            Template result = new Template(this, entry);
            identities.put(copied, Boolean.TRUE);
            try {
                entries.add(entry);
            } catch (RuntimeException | Error publicationFailure) {
                identities.remove(copied);
                throw publicationFailure;
            }
            temporary = null;
            return result;
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            try {
                cleanup(temporary, failure);
            } finally {
                busy = false;
            }
        }
    }

    public void seal() {
        requireAvailable();
        sealed = true;
    }

    public int size() {
        requireOwner();
        return entries.size();
    }

    private void checkpoint(BooleanSupplier cancelled) {
        requireOpen();
        if (cancelled.getAsBoolean()) throw RuntimeItemMessages.error("cancelled");
        requireOpen();
    }

    private boolean isIndependentIdentity(ItemStack source, ItemStack candidate) {
        return candidate != null && candidate != source && candidate != ItemStack.EMPTY && !identities.containsKey(candidate);
    }

    private void requireIsolated(ItemStack source, ItemStack candidate, Body body) {
        if (!isIndependentIdentity(source, candidate) || (source.getTag() != null && candidate.getTag() == source.getTag())) {
            throw RuntimeItemMessages.error("invalid_copy");
        }
        body.requireMatches(source);
        body.requireMatches(candidate);
    }

    private static void requireSaved(ItemStack stack, Body body, CompoundTag saved) {
        body.requireMatches(stack);
        CompoundTag current = stack.save(new CompoundTag());
        body.requireMatches(stack);
        if (!saved.equals(current)) throw RuntimeItemMessages.error("invalid_copy");
    }

    private ItemStack copy(Entry entry, Function<ItemStack, ?> observer, Object[] observed) {
        requireAvailable();
        busy = true;
        ItemStack temporary = null;
        Throwable failure = null;
        try {
            ItemStack source = entry.raw;
            Body body = Body.capture(source);
            ItemStack candidate = body.empty ? new ItemStack(Items.AIR, 0) : source.copy();
            if (isIndependentIdentity(source, candidate)) temporary = candidate;
            requireIsolated(source, candidate, body);
            if (observer != null) {
                observed[0] = observer.apply(candidate);
                body.requireMatches(source);
                requireOpen();
                return null;
            }
            requireOpen();
            temporary = null;
            return candidate;
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            try {
                cleanup(temporary, failure);
            } finally {
                busy = false;
            }
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw RuntimeItemMessages.error("wrong_thread");
    }

    private void requireOpen() {
        requireOwner();
        if (closed) throw RuntimeItemMessages.error("closed");
    }

    public void requireAvailable() {
        requireOpen();
        if (busy) throw RuntimeItemMessages.error("busy");
    }

    public boolean owns(Template template) {
        requireAvailable();
        return template != null && template.owner == this;
    }

    /** Clear every retained raw reference before attempting any third-party invalidation. */
    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        if (busy) throw RuntimeItemMessages.error("busy");
        ItemStack[] retired = new ItemStack[entries.size()];
        closed = true;
        for (int i = 0; i < retired.length; i++) {
            Entry entry = entries.get(i);
            retired[i] = entry.raw;
            entry.raw = null;
        }
        entries.clear();
        identities.clear();
        Throwable failure = null;
        for (int i = retired.length - 1; i >= 0; i--) {
            ItemStack stack = retired[i];
            retired[i] = null;
            try {
                stack.invalidateCaps();
            } catch (RuntimeException | Error thrown) {
                if (failure == null) failure = thrown;
                else suppress(failure, thrown);
            }
        }
        if (failure instanceof RuntimeException thrown) throw thrown;
        if (failure instanceof Error thrown) throw thrown;
    }

    private static void cleanup(ItemStack temporary, Throwable primary) {
        if (temporary == null) return;
        try {
            temporary.invalidateCaps();
        } catch (RuntimeException | Error thrown) {
            if (primary == null) throw thrown;
            suppress(primary, thrown);
        }
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) primary.addSuppressed(secondary);
    }

    private static final class Entry {

        private ItemStack raw;

        Entry(ItemStack raw) {
            this.raw = raw;
        }
    }

    private record Body(boolean empty, Item item, int count, CompoundTag tag) {

        static Body capture(ItemStack stack) {
            CompoundTag tag = stack.getTag();
            return new Body(stack.isEmpty(), stack.getItem(), stack.getCount(),
                    tag == null ? null : tag.copy());
        }

        void requireMatches(ItemStack stack) {
            if (stack == null || stack.isEmpty() != empty || stack.getItem() != item || stack.getCount() != count || !Objects.equals(stack.getTag(), tag)) {
                throw RuntimeItemMessages.error("invalid_copy");
            }
        }
    }

    public static final class Template implements Supplier<ItemStack> {

        private final RuntimeItemTemplates owner;
        private final Entry entry;

        private Template(RuntimeItemTemplates owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        @Override
        public ItemStack get() {
            return owner.copy(entry, null, null);
        }

        public void requireAvailable() {
            owner.requireAvailable();
        }

        public boolean hasSameOwner(Template other) {
            owner.requireAvailable();
            return other != null && owner == other.owner;
        }

        /** The temporary argument is invalidated before this trusted package-local call returns. */
        <R> R inspect(Function<ItemStack, R> observer) {
            owner.requireAvailable();
            if (observer == null) throw RuntimeItemMessages.error("invalid_input");
            Object[] observed = new Object[1];
            owner.copy(entry, observer, observed);
            @SuppressWarnings("unchecked")
            R result = (R) observed[0];
            return result;
        }
    }
}
