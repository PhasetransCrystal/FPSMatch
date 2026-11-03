package net.ptcrys.fpsmatch.editor.core.worldaccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Q68 public behavior with real Windows identity files; Host is not Minecraft evidence. */
@EnabledOnOs(OS.WINDOWS)
class ConfirmedWorldBindingBehaviorTest {
    private static final BooleanSupplier CONTINUE = () -> false;
    private static final UUID ACTOR_A = UUID.fromString("b6230cf1-fd28-40ba-b07d-961158d8da91");
    private static final UUID ACTOR_B = UUID.fromString("80dd29b8-f026-4e0a-bfeb-14c4b6d65380");
    private static final Map<EditorId, EditorId> DIMENSIONS = Map.of(
            EditorId.parse("project:main"), EditorId.parse("minecraft:overworld"));
    private final FileIdentityProvider provider = new WindowsFileIdentityProvider();
    @TempDir Path temporary;

    @Test
    void sameControllerDifferentActorsNeedExactBindingPairDespiteEqualWorldUuid() throws Exception {
        Host host = new Host(world("two-actors"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding a = bind(bindings, ACTOR_A);
            WorldBinding b = bind(bindings, ACTOR_B);
            assertNotSame(a, b);
            assertEquals(a.worldId(), b.worldId());
            assertNotEquals(a.actorId(), b.actorId());
            WorldBindingCheckResult resultA = checked(bindings, a);
            WorldBindingCheckResult resultB = checked(bindings, b);

            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.INVALID_TARGET,
                    () -> bindings.confirm(a, resultB));
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.INVALID_TARGET,
                    () -> bindings.confirm(b, resultA));

            // The original results must still be usable, and both slots must survive.
            bindings.confirm(a, resultA);
            bindings.confirm(b, resultB);
            bindings.verify(a);
            bindings.verify(b);
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(a, resultA));
        }
    }

    @Test
    void foreignExpectedResultAndBothForeignAreRejectedInEitherController() throws Exception {
        Path original = world("issuer-a");
        Path copy = Files.createDirectories(temporary.resolve("issuer-b/.editor-platform")).getParent();
        Files.copy(identity(original), identity(copy));
        Host hostA = new Host(original);
        Host hostB = new Host(copy);
        try (LocalWorldBindings a = new LocalWorldBindings(hostA);
             LocalWorldBindings b = new LocalWorldBindings(hostB)) {
            WorldBinding bindingA = bind(a, ACTOR_A);
            WorldBinding bindingB = bind(b, ACTOR_A);
            assertEquals(bindingA.worldId(), bindingB.worldId(), "copied lineage is not issuer authority");
            WorldBindingCheckResult resultA = checked(a, bindingA);
            WorldBindingCheckResult resultB = checked(b, bindingB);
            rejectedBeforeTargetCallbacks(hostA, WorldBindingException.Code.FOREIGN,
                    () -> a.confirm(bindingB, resultA));
            rejectedBeforeTargetCallbacks(hostA, WorldBindingException.Code.FOREIGN,
                    () -> a.confirm(bindingA, resultB));
            rejectedBeforeTargetCallbacks(hostA, WorldBindingException.Code.FOREIGN,
                    () -> a.confirm(bindingB, resultB));
            rejectedBeforeTargetCallbacks(hostB, WorldBindingException.Code.FOREIGN,
                    () -> b.confirm(bindingA, resultB));
            rejectedBeforeTargetCallbacks(hostB, WorldBindingException.Code.FOREIGN,
                    () -> b.confirm(bindingB, resultA));
            rejectedBeforeTargetCallbacks(hostB, WorldBindingException.Code.FOREIGN,
                    () -> b.confirm(bindingA, resultA));
            a.confirm(bindingA, resultA);
            b.confirm(bindingB, resultB);
            a.verify(bindingA);
            b.verify(bindingB);
        }
    }

    @Test
    void nullArgumentsDoNotConsumeResultOrRevokeBinding() throws Exception {
        Host host = new Host(world("nulls"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding binding = bind(bindings, ACTOR_A);
            WorldBindingCheckResult result = checked(bindings, binding);
            int callbacks = host.targetCallbacks;
            int ownerCallbacks = host.ownerCallbacks;
            assertThrows(NullPointerException.class, () -> bindings.confirm(null, result));
            assertThrows(NullPointerException.class, () -> bindings.confirm(binding, null));
            assertThrows(NullPointerException.class, () -> bindings.confirm(null, null));
            assertEquals(callbacks, host.targetCallbacks);
            assertEquals(ownerCallbacks, host.ownerCallbacks, "null gate must precede every host callback");
            bindings.confirm(binding, result);
            bindings.verify(binding);
        }
    }

    @Test
    void supersededCheckCannotConsumeOrInvalidateNewerCheck() throws Exception {
        Host host = new Host(world("new-check"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding binding = bind(bindings, ACTOR_A);
            WorldBindingCheckResult old = checked(bindings, binding);
            WorldBindingCheckResult current = checked(bindings, binding);
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(binding, old));
            bindings.confirm(binding, current);
            bindings.verify(binding);
        }
    }

    @Test
    void cancellingSuccessfulCheckRejectsItsResultButBindingCanBeCheckedAgain() throws Exception {
        Host host = new Host(world("cancel-check"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding binding = bind(bindings, ACTOR_A);
            WorldBindingCheck check = bindings.check(binding);
            WorldBindingCheckResult result = check.verify(CONTINUE);
            check.cancel();
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(binding, result));
            bindings.confirm(binding, checked(bindings, binding));
        }
    }

    @Test
    void cancelledRequestAndExplicitRevokeCannotConfirmPreviouslySuccessfulChecks() throws Exception {
        Host host = new Host(world("cancel-request"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBindingRequest request = bindings.begin(ACTOR_A, DIMENSIONS);
            WorldBinding cancelled = bindings.accept(request.read(provider, CONTINUE));
            WorldBindingCheckResult cancelledResult = checked(bindings, cancelled);
            request.cancel();
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(cancelled, cancelledResult));
            WorldBinding revoked = bind(bindings, ACTOR_A);
            WorldBindingCheckResult revokedResult = checked(bindings, revoked);
            bindings.revoke(ACTOR_A);
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(revoked, revokedResult));
            WorldBinding fresh = bind(bindings, ACTOR_A);
            bindings.confirm(fresh, checked(bindings, fresh));
        }
    }

    @Test
    void reselectionWithSameWorldAndActorCannotSubstituteNewBindingForOldObject() throws Exception {
        Host host = new Host(world("same-world-reselect"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding old = bind(bindings, ACTOR_A);
            WorldBindingCheckResult oldResult = checked(bindings, old);
            WorldBinding current = bind(bindings, ACTOR_A);
            WorldBindingCheckResult currentResult = checked(bindings, current);
            assertEquals(old.worldId(), current.worldId());
            assertEquals(old.actorId(), current.actorId());
            assertEquals(old.dimensions(), current.dimensions());
            assertNotEquals(old.requestId(), current.requestId());
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.INVALID_TARGET,
                    () -> bindings.confirm(current, oldResult));
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.INVALID_TARGET,
                    () -> bindings.confirm(old, currentResult));
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(old, oldResult));
            bindings.confirm(current, currentResult);
            bindings.verify(current);
        }
    }

    @Test
    void closeRejectsRetainedPairWithoutCallingTargetHost() throws Exception {
        Host host = new Host(world("close"));
        LocalWorldBindings bindings = new LocalWorldBindings(host);
        try {
            WorldBinding binding = bind(bindings, ACTOR_A);
            WorldBindingCheckResult result = checked(bindings, binding);
            bindings.close();
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.CLOSED,
                    () -> bindings.confirm(binding, result));
        } finally { bindings.close(); }
    }

    @Test
    void workerAndHostOwnerRejectionsLeaveCorrectPairUsableOnOwner() throws Exception {
        Host host = new Host(world("owner"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding binding = bind(bindings, ACTOR_A);
            WorldBindingCheck check = bindings.check(binding);
            AtomicReference<WorldBindingCheckResult> result = new AtomicReference<>();
            onWorker(() -> result.set(check.verify(CONTINUE)));
            onWorker(() -> rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.WRONG_THREAD,
                    () -> bindings.confirm(binding, result.get())));
            host.ownerAllowed = false;
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.WRONG_THREAD,
                    () -> bindings.confirm(binding, result.get()));
            host.ownerAllowed = true;
            bindings.confirm(binding, result.get());
            bindings.verify(binding);
        }
    }

    @Test
    void cancellationDuringHostVerificationIsCaughtByPostCheckAndDoesNotRevokeBinding() throws Exception {
        Host host = new Host(world("cancel-in-callback"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding binding = bind(bindings, ACTOR_A);
            WorldBindingCheck check = bindings.check(binding);
            WorldBindingCheckResult result = check.verify(CONTINUE);
            int before = host.selectionVerifications;
            host.onVerify = check::cancel;
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.confirm(binding, result));
            assertTrue(host.selectionVerifications > before, "must reach host before cancellation");
            host.onVerify = () -> {};
            bindings.verify(binding);
            bindings.confirm(binding, checked(bindings, binding));
        }
    }

    @Test
    void matchedPairStillChecksHostAndHostFailureRevokesOnlyItsActor() throws Exception {
        Host host = new Host(world("permission"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding a = bind(bindings, ACTOR_A);
            WorldBinding b = bind(bindings, ACTOR_B);
            WorldBindingCheckResult resultA = checked(bindings, a);
            WorldBindingCheckResult resultB = checked(bindings, b);
            int before = host.selectionVerifications;
            host.selectionFailure = WorldBindingException.Code.PERMISSION_DENIED;
            bindingCode(WorldBindingException.Code.PERMISSION_DENIED, () -> bindings.confirm(a, resultA));
            assertTrue(host.selectionVerifications > before);
            host.selectionFailure = null;
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(a, resultA));
            bindings.confirm(b, resultB);
            bindings.verify(b);
        }
    }

    @Test
    void singleAndTwoArgumentOverloadsShareOneConsumptionAndLeaveBindingReusable() throws Exception {
        Host host = new Host(world("compatibility"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding binding = bind(bindings, ACTOR_A);
            WorldBindingCheckResult oldEntry = checked(bindings, binding);
            bindings.confirm(oldEntry);
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(binding, oldEntry));
            WorldBindingCheckResult newEntry = checked(bindings, binding);
            bindings.confirm(binding, newEntry);
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(newEntry));
            rejectedBeforeTargetCallbacks(host, WorldBindingException.Code.STALE,
                    () -> bindings.confirm(binding, newEntry));
            bindings.confirm(checked(bindings, binding));
            bindings.verify(binding);
        }
    }

    private Path world(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        WorldIdentities.initialize(root, provider, CONTINUE);
        return root;
    }

    private static Path identity(Path root) { return root.resolve(".editor-platform/world.identity"); }

    private WorldBinding bind(LocalWorldBindings bindings, UUID actor) throws Exception {
        return bindings.accept(bindings.begin(actor, DIMENSIONS).read(provider, CONTINUE));
    }

    private static WorldBindingCheckResult checked(LocalWorldBindings bindings, WorldBinding binding)
            throws Exception {
        return bindings.check(binding).verify(CONTINUE);
    }

    private static void bindingCode(WorldBindingException.Code code, Executable operation) {
        assertEquals(code, assertThrows(WorldBindingException.class, operation).code());
    }

    private static void rejectedBeforeTargetCallbacks(Host host, WorldBindingException.Code code,
            Executable operation) {
        int callbacks = host.targetCallbacks;
        int ownerCallbacks = host.ownerCallbacks;
        bindingCode(code, operation);
        assertEquals(callbacks, host.targetCallbacks,
                "rejected pair must not reach active/capture/root/selection verification");
        if (code == WorldBindingException.Code.INVALID_TARGET || code == WorldBindingException.Code.FOREIGN)
            assertEquals(ownerCallbacks, host.ownerCallbacks,
                    "issuer and exact-pair gates must precede even the host owner callback");
    }

    private static void onWorker(Executable operation) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { operation.execute(); } catch (Throwable error) { failure.set(error); }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(10_000);
        } finally {
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(1_000);
            }
        }
        assertFalse(worker.isAlive(), "worker did not finish");
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
    }

    /** Trusted selection seam only. Counts distinguish local gates from host callbacks. */
    private static final class Host implements LocalWorldBindingHost {
        private final Thread owner = Thread.currentThread();
        private final Path root;
        private boolean ownerAllowed = true;
        private int ownerCallbacks;
        private int targetCallbacks;
        private int selectionVerifications;
        private WorldBindingException.Code selectionFailure;
        private Runnable onVerify = () -> {};

        private Host(Path root) { this.root = root; }

        @Override public void requireOwner() {
            ownerCallbacks++;
            if (Thread.currentThread() != owner || !ownerAllowed)
                throw new WorldBindingException(WorldBindingException.Code.WRONG_THREAD, "test host owner");
        }

        @Override public boolean active() {
            requireOwner();
            targetCallbacks++;
            return true;
        }

        @Override public Selection capture(UUID actor, Map<EditorId, EditorId> dimensions) {
            requireOwner();
            targetCallbacks++;
            return new Selection() {
                @Override public Path root() {
                    targetCallbacks++;
                    return root;
                }

                @Override public void verify() {
                    requireOwner();
                    targetCallbacks++;
                    selectionVerifications++;
                    onVerify.run();
                    if (selectionFailure != null)
                        throw new WorldBindingException(selectionFailure, "test host permission changed");
                }
            };
        }
    }
}
