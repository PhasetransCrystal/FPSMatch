package net.ptcrys.fpsmatch.editor.core.worldaccess;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import static net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingException.Code;

/** Owner-controlled local bindings with explicitly scheduled identity I/O. */
public final class LocalWorldBindings implements AutoCloseable {

    private final LocalWorldBindingHost host;
    private final Thread owner;
    private final Object issuer = new Object();
    private final Map<UUID, Slot> slots = new HashMap<>();
    private boolean closed;
    private boolean busy;

    public LocalWorldBindings(LocalWorldBindingHost host) {
        this.host = Objects.requireNonNull(host, "host");
        host.requireOwner();
        owner = Thread.currentThread();
    }

    public WorldBindingRequest begin(UUID actorId, Map<EditorId, EditorId> dimensions) {
        enter();
        try {
            Objects.requireNonNull(actorId, "actorId");
            Map<EditorId, EditorId> copy = dimensions(dimensions);
            if (actorId.getMostSignificantBits() == 0 && actorId.getLeastSignificantBits() == 0) {
                throw failure(Code.INVALID_TARGET, "Actor identity must be nonzero");
            }
            if (!slots.containsKey(actorId) && slots.size() >= 64) {
                throw failure(Code.LIMIT, "Actor binding budget exceeded");
            }
            remove(actorId, slots.get(actorId));
            active();
            LocalWorldBindingHost.Selection selection = host.capture(actorId, copy);
            open();
            Objects.requireNonNull(selection, "selection");
            Path suppliedRoot = selection.root();
            open();
            Path root = Objects.requireNonNull(suppliedRoot, "root").toAbsolutePath().normalize();
            selection.verify();
            open();
            active();
            UUID id;
            do {
                id = UUID.randomUUID();
            } while (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0);
            WorldBindingRequest request = new WorldBindingRequest(issuer, root, id, actorId, copy);
            slots.put(actorId, new Slot(request, selection));
            return request;
        } finally {
            busy = false;
        }
    }

    public WorldBinding accept(WorldBindingRead read) {
        enter();
        try {
            Objects.requireNonNull(read, "read");
            WorldBindingRequest request = read.request;
            issued(request.issuer);
            Slot slot = pending(request);
            try {
                current(slot);
                active();
                current(slot);
                slot.selection.verify();
                current(slot);
                active();
                current(slot);
                if (!request.root.equals(read.snapshot.root())) {
                    throw failure(Code.STALE, "Identity read root changed");
                }
                WorldBinding binding = new WorldBinding(request, read.snapshot.worldId());
                slot.snapshot = read.snapshot;
                slot.binding = binding;
                return binding;
            } catch (RuntimeException | Error failure) {
                remove(request.actorId(), slot);
                throw failure;
            }
        } finally {
            busy = false;
        }
    }

    public void verify(WorldBinding binding) {
        enter();
        try {
            verifyBound(binding);
        } finally {
            busy = false;
        }
    }

    public WorldBindingCheck check(WorldBinding binding) {
        enter();
        try {
            Slot slot = verifyBound(binding);
            WorldBindingCheck check = new WorldBindingCheck(issuer, binding, slot.snapshot);
            Object old;
            do {
                old = binding.checkState.get();
                if (binding.invalid()) {
                    remove(binding.actorId(), slot);
                    throw failure(Code.STALE, "Binding was revoked");
                }
            } while (!binding.checkState.compareAndSet(old, check));
            if (old instanceof WorldBindingCheck previous) previous.cancel();
            return check;
        } finally {
            busy = false;
        }
    }

    public void confirm(WorldBindingCheckResult result) {
        enter();
        try {
            Objects.requireNonNull(result, "result");
            WorldBindingCheck check = result.check;
            issued(check.issuer);
            // Reject an old check before any callback, without affecting a newer one.
            currentCheck(check, result);
            verifyBound(check.binding);
            currentCheck(check, result);
            result.consumed = true;
        } finally {
            busy = false;
        }
    }

    /** Confirms only the physical result issued for this exact expected binding. */
    public void confirm(WorldBinding expected, WorldBindingCheckResult result) {
        // These gates precede enter's host callback and never alter either slot.
        thread();
        open();
        if (busy) throw failure(Code.BUSY, "Binding controller is busy");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(result, "result");
        issued(expected.issuer);
        issued(result.check.issuer);
        if (result.check.binding != expected) {
            throw failure(Code.INVALID_TARGET, "Identity check does not match expected binding");
        }
        confirm(result);
    }

    public void discard(WorldBindingRequest request) {
        enter();
        try {
            Objects.requireNonNull(request, "request");
            issued(request.issuer);
            Slot slot = slots.get(request.actorId());
            if (slot != null && slot.request == request && slot.binding == null) {
                remove(request.actorId(), slot);
            }
        } finally {
            busy = false;
        }
    }

    public void revoke(UUID actorId) {
        enter();
        try {
            Objects.requireNonNull(actorId, "actorId");
            remove(actorId, slots.get(actorId));
        } finally {
            busy = false;
        }
    }

    @Override
    public void close() {
        thread();
        if (closed) return;
        host.requireOwner();
        if (closed) return;
        closed = true;
        for (Slot slot : slots.values()) cancel(slot);
        slots.clear();
    }

    private Slot verifyBound(WorldBinding binding) {
        Objects.requireNonNull(binding, "binding");
        issued(binding.issuer);
        Slot slot = slots.get(binding.actorId());
        if (slot == null || slot.binding != binding) {
            throw failure(Code.STALE, "Binding is no longer current");
        }
        try {
            current(slot);
            active();
            current(slot);
            slot.selection.verify();
            current(slot);
            active();
            current(slot);
            return slot;
        } catch (RuntimeException | Error failure) {
            remove(binding.actorId(), slot);
            throw failure;
        }
    }

    private void currentCheck(WorldBindingCheck check, WorldBindingCheckResult result) {
        Slot slot = slots.get(check.binding.actorId());
        if (slot == null || slot.binding != check.binding || check.binding.invalid() || check.binding.checkState.get() != check || check.cancelled.get() || result.consumed) {
            if (slot != null && slot.binding == check.binding && check.binding.invalid()) {
                remove(check.binding.actorId(), slot);
            }
            throw failure(Code.STALE, "Identity check is no longer current");
        }
    }

    private Slot pending(WorldBindingRequest request) {
        Slot slot = slots.get(request.actorId());
        if (slot == null || slot.request != request || slot.binding != null) {
            throw failure(Code.STALE, "Identity request is no longer pending");
        }
        return slot;
    }

    private void current(Slot slot) {
        open();
        if (slots.get(slot.request.actorId()) != slot || slot.request.cancelled.get() || (slot.binding != null && slot.binding.invalid())) {
            throw failure(Code.STALE, "Binding request was revoked");
        }
    }

    private void active() {
        boolean active = host.active();
        open();
        if (!active) throw failure(Code.INACTIVE, "Local host is inactive");
    }

    private void issued(Object candidate) {
        if (issuer != candidate) throw failure(Code.FOREIGN, "Token belongs to another issuer");
    }

    private void enter() {
        thread();
        open();
        if (busy) throw failure(Code.BUSY, "Binding controller is busy");
        busy = true;
        try {
            host.requireOwner();
            open();
        } catch (RuntimeException | Error failure) {
            busy = false;
            throw failure;
        }
    }

    private void thread() {
        if (Thread.currentThread() != owner) throw failure(Code.WRONG_THREAD, "Wrong binding owner thread");
    }

    private void open() {
        if (closed) throw failure(Code.CLOSED, "Binding controller is closed");
    }

    private void remove(UUID actorId, Slot expected) {
        if (expected != null && slots.remove(actorId, expected)) cancel(expected);
    }

    private static void cancel(Slot slot) {
        slot.request.cancel();
        if (slot.binding != null) {
            Object check = slot.binding.checkState.getAndSet(WorldBinding.REVOKED);
            if (check instanceof WorldBindingCheck current) current.cancel();
        }
    }

    private static Map<EditorId, EditorId> dimensions(Map<EditorId, EditorId> dimensions) {
        Objects.requireNonNull(dimensions, "dimensions");
        if (dimensions.isEmpty() || dimensions.size() > 64) {
            throw failure(Code.INVALID_TARGET, "Expected between one and 64 dimensions");
        }
        TreeMap<EditorId, EditorId> copy = new TreeMap<>();
        HashSet<EditorId> targets = new HashSet<>();
        for (Map.Entry<EditorId, EditorId> entry : dimensions.entrySet()) {
            EditorId source = Objects.requireNonNull(entry.getKey(), "source dimension");
            EditorId target = Objects.requireNonNull(entry.getValue(), "target dimension");
            if (!targets.add(target)) throw failure(Code.INVALID_TARGET, "Duplicate target dimension");
            copy.put(source, target);
        }
        return Collections.unmodifiableSortedMap(copy);
    }

    private static WorldBindingException failure(Code code, String message) {
        return new WorldBindingException(code, message);
    }

    private static final class Slot {

        final WorldBindingRequest request;
        final LocalWorldBindingHost.Selection selection;
        WorldIdentitySnapshot snapshot;
        WorldBinding binding;

        Slot(WorldBindingRequest request, LocalWorldBindingHost.Selection selection) {
            this.request = request;
            this.selection = selection;
        }
    }
}
