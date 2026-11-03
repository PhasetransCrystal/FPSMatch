package net.ptcrys.fpsmatch.editor.core.worldaccess;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

/** Q58 public API behavior with real temporary Windows files; Host is not Minecraft evidence. */
@EnabledOnOs(OS.WINDOWS)
class LocalWorldBindingsBehaviorTest {
    private static final BooleanSupplier CONTINUE = () -> false;
    private static final Map<EditorId, EditorId> DIMENSIONS = Map.of(
            EditorId.parse("project:main"), EditorId.parse("minecraft:overworld"));
    private static final UUID ACTOR = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private final FileIdentityProvider provider = new WindowsFileIdentityProvider();
    @TempDir Path temporary;

    @Test
    void currentBindingPreservesIdentityAndMappingThroughOneCheckAndConfirm() throws Exception {
        Path root = world("current");
        UUID worldId = WorldIdentities.read(root, provider, CONTINUE).worldId();
        Host host = new Host(root);
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
            WorldBinding binding = bindings.accept(request.read(provider, CONTINUE));
            assertEquals(ACTOR, request.actorId());
            assertNotEquals(new UUID(0, 0), request.requestId());
            assertEquals(request.requestId(), binding.requestId());
            assertEquals(ACTOR, binding.actorId());
            assertEquals(worldId, binding.worldId());
            assertEquals(DIMENSIONS, binding.dimensions());
            bindings.verify(binding);
            WorldBindingCheck check = bindings.check(binding);
            bindings.confirm(check.verify(CONTINUE));
            bindings.verify(binding);
        }
    }

    @Test
    void copiedWorldUuidDoesNotTransferIssuerAuthorityOrDamageCurrentSlots() throws Exception {
        Path first = world("a/world");
        Path copy = Files.createDirectories(temporary.resolve("b/world/.editor-platform")).getParent();
        Files.copy(identity(first), identity(copy));
        try (LocalWorldBindings a = new LocalWorldBindings(new Host(first));
             LocalWorldBindings b = new LocalWorldBindings(new Host(copy))) {
            WorldBindingRequest ra = a.begin(ACTOR, DIMENSIONS);
            WorldBindingRequest rb = b.begin(ACTOR, DIMENSIONS);
            WorldBindingRead readA = ra.read(provider, CONTINUE);
            WorldBindingRead readB = rb.read(provider, CONTINUE);
            bindingCode(WorldBindingException.Code.FOREIGN, () -> b.accept(readA));
            bindingCode(WorldBindingException.Code.FOREIGN, () -> b.discard(ra));
            WorldBinding ba = a.accept(readA);
            WorldBinding bb = b.accept(readB);
            assertEquals(ba.worldId(), bb.worldId());
            assertNotEquals(ba.requestId(), bb.requestId());
            bindingCode(WorldBindingException.Code.FOREIGN, () -> b.verify(ba));
            WorldBindingCheckResult ca = a.check(ba).verify(CONTINUE);
            bindingCode(WorldBindingException.Code.FOREIGN, () -> b.confirm(ca));
            b.confirm(b.check(bb).verify(CONTINUE));
            a.confirm(ca);
        }
    }

    @Test
    void reselectionRejectsLateReadsAndChecksAndOldDiscardCannotDeleteNewSlot() throws Exception {
        Host host = new Host(world("reselect"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBindingRequest old = bindings.begin(ACTOR, DIMENSIONS);
            WorldBindingRead late = old.read(provider, CONTINUE);
            WorldBindingRequest replacement = bindings.begin(ACTOR, DIMENSIONS);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.accept(late));
            bindings.discard(old);
            WorldBinding current = bindings.accept(replacement.read(provider, CONTINUE));
            WorldBindingCheckResult lateCheck = bindings.check(current).verify(CONTINUE);
            WorldBindingRequest next = bindings.begin(ACTOR, DIMENSIONS);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.confirm(lateCheck));
            bindings.discard(replacement);
            WorldBinding newest = bindings.accept(next.read(provider, CONTINUE));
            bindings.verify(newest);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.verify(current));
        }
    }

    @Test
    void failedNewCaptureStillRevokesOldBinding() throws Exception {
        Host host = new Host(world("capture-fails"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding old = bind(bindings);
            host.captureFailure = WorldBindingException.Code.DIMENSION_UNAVAILABLE;
            bindingCode(WorldBindingException.Code.DIMENSION_UNAVAILABLE,
                    () -> bindings.begin(ACTOR, DIMENSIONS));
            host.captureFailure = null;
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.verify(old));
            bindings.verify(bind(bindings));
        }
    }

    @Test
    void hostConnectionPermissionLevelRootAndActivityChangesInvalidatePendingAndBound() throws Exception {
        Path root = world("host-changes");
        for (WorldBindingException.Code failure : List.of(WorldBindingException.Code.DISCONNECTED,
                WorldBindingException.Code.PERMISSION_DENIED, WorldBindingException.Code.DIMENSION_UNAVAILABLE,
                WorldBindingException.Code.STALE, WorldBindingException.Code.INACTIVE)) {
            for (boolean pending : List.of(true, false)) {
                Host host = new Host(root);
                try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
                    WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
                    WorldBindingRead read = request.read(provider, CONTINUE);
                    WorldBinding bound = pending ? null : bindings.accept(read);
                    if (failure == WorldBindingException.Code.INACTIVE) host.active = false;
                    else if (failure == WorldBindingException.Code.STALE) host.root = temporary.resolve("other-root");
                    else host.selectionFailure = failure;
                    bindingCode(failure, () -> {
                        if (pending) bindings.accept(read); else bindings.verify(bound);
                    });
                    host.active = true;
                    host.root = root;
                    host.selectionFailure = null;
                    bindingCode(WorldBindingException.Code.STALE, () -> {
                        if (pending) bindings.accept(read); else bindings.verify(bound);
                    });
                }
            }
        }
    }

    @Test
    void replacingOrCorruptingIdentityRevokesBindingEvenWhenOwnerMemoryStillMatches() throws Exception {
        for (boolean sameBytes : List.of(true, false)) {
            Path root = world(sameBytes ? "replaced" : "corrupted");
            try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(root))) {
                WorldBinding binding = bind(bindings);
                WorldBindingCheck check = bindings.check(binding);
                byte[] original = Files.readAllBytes(identity(root));
                if (sameBytes) {
                    var before = provider.identity(identity(root));
                    Files.move(identity(root), root.resolve("retired-identity"));
                    Files.write(identity(root), original);
                    assertNotEquals(before, provider.identity(identity(root)));
                } else Files.write(identity(root), new byte[] {1, 2, 3});
                // Memory-only verification deliberately has no disk claim.
                bindings.verify(binding);
                ioCode(WorldIdentityException.Code.CHANGED, () -> check.verify(CONTINUE));
                bindingCode(WorldBindingException.Code.STALE, () -> bindings.verify(binding));
                if (sameBytes) assertArrayEquals(original, Files.readAllBytes(identity(root)));
                else assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(identity(root)));
            }
        }
    }

    @Test
    void missingReadIsReadOnlyAndFailedRequestCannotBeRetriedOrAccepted() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("missing"));
        Files.writeString(root.resolve("unrelated"), "keep");
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(root))) {
            WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
            ioCode(WorldIdentityException.Code.MISSING, () -> request.read(provider, CONTINUE));
            bindingCode(WorldBindingException.Code.STALE, () -> request.read(provider, CONTINUE));
            bindings.discard(request);
            try (var files = Files.list(root)) {
                assertEquals(List.of("unrelated"), files.map(p -> p.getFileName().toString()).toList());
            }
            assertEquals("keep", Files.readString(root.resolve("unrelated")));
        }
    }

    @Test
    void readCheckAndConfirmAreSingleUseAndNewCheckRejectsOldSuccessfulResult() throws Exception {
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(world("single-use")))) {
            WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
            WorldBindingRead read = request.read(provider, CONTINUE);
            bindingCode(WorldBindingException.Code.STALE, () -> request.read(provider, CONTINUE));
            WorldBinding binding = bindings.accept(read);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.accept(read));
            bindings.verify(binding);
            WorldBindingCheck first = bindings.check(binding);
            WorldBindingCheckResult oldResult = first.verify(CONTINUE);
            bindingCode(WorldBindingException.Code.STALE, () -> first.verify(CONTINUE));
            WorldBindingCheck second = bindings.check(binding);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.confirm(oldResult));
            WorldBindingCheckResult result = second.verify(CONTINUE);
            bindings.confirm(result);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.confirm(result));
            bindings.verify(binding);
        }
    }

    @Test
    void cancellationBeforeIoAndAtFinalSupplierCallCannotPublishUsableResults() throws Exception {
        Path root = world("cancel");
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(root))) {
            WorldBindingRequest before = bindings.begin(ACTOR, DIMENSIONS);
            before.cancel();
            ioCode(WorldIdentityException.Code.CANCELLED, () -> before.read(provider, CONTINUE));
            AtomicInteger calls = new AtomicInteger();
            WorldBindingRequest probe = bindings.begin(ACTOR, DIMENSIONS);
            bindings.accept(probe.read(provider, () -> { calls.incrementAndGet(); return false; }));
            assertTrue(calls.get() > 0);
            WorldBindingRequest tail = bindings.begin(ACTOR, DIMENSIONS);
            AtomicInteger tailCalls = new AtomicInteger();
            ioCode(WorldIdentityException.Code.CANCELLED,
                    () -> tail.read(provider, () -> tailCalls.incrementAndGet() == calls.get()));
            assertEquals(calls.get(), tailCalls.get());
            WorldBinding binding = bind(bindings);
            WorldBindingCheck cancelledCheck = bindings.check(binding);
            cancelledCheck.cancel();
            ioCode(WorldIdentityException.Code.CANCELLED, () -> cancelledCheck.verify(CONTINUE));
            bindings.verify(binding);
            bindings.confirm(bindings.check(binding).verify(CONTINUE));
            WorldBinding next = bind(bindings);
            AtomicInteger checkCalls = new AtomicInteger();
            bindings.confirm(bindings.check(next).verify(() -> { checkCalls.incrementAndGet(); return false; }));
            WorldBindingCheck tailCheck = bindings.check(next);
            AtomicInteger checkTailCalls = new AtomicInteger();
            ioCode(WorldIdentityException.Code.CANCELLED,
                    () -> tailCheck.verify(() -> checkTailCalls.incrementAndGet() == checkCalls.get()));
            assertEquals(checkCalls.get(), checkTailCalls.get());
            bindings.verify(next);
            bindings.confirm(bindings.check(next).verify(CONTINUE));
        }
    }

    @Test
    void supplierExceptionsEscapeUnchangedAndInvalidateReadOrCheckOnly() throws Exception {
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(world("supplier-failure")))) {
            RuntimeException failure = new IllegalArgumentException("supplier failure");
            WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> request.read(provider, () -> { throw failure; })));
            bindingCode(WorldBindingException.Code.STALE, () -> request.read(provider, CONTINUE));
            WorldBinding binding = bind(bindings);
            WorldBindingCheck check = bindings.check(binding);
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> check.verify(() -> { throw failure; })));
            bindings.verify(binding);
            bindings.confirm(bindings.check(binding).verify(CONTINUE));
        }
    }

    @Test
    void duplicateExecutionClaimWhileIoIsRunningDoesNotCancelFirstExecution() throws Exception {
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(world("duplicate-claim")))) {
            WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
            AtomicBoolean readClaim = new AtomicBoolean();
            WorldBindingRead read = request.read(provider, () -> {
                if (readClaim.compareAndSet(false, true))
                    bindingCode(WorldBindingException.Code.STALE, () -> request.read(provider, CONTINUE));
                return false;
            });
            WorldBinding binding = bindings.accept(read);
            WorldBindingCheck check = bindings.check(binding);
            AtomicBoolean checkClaim = new AtomicBoolean();
            WorldBindingCheckResult result = check.verify(() -> {
                if (checkClaim.compareAndSet(false, true))
                    bindingCode(WorldBindingException.Code.STALE, () -> check.verify(CONTINUE));
                return false;
            });
            assertTrue(readClaim.get());
            assertTrue(checkClaim.get());
            bindings.confirm(result);
            bindings.verify(binding);
        }
    }

    @Test
    void currentCheckIoFailureRevokesBindingWithoutReplacingOrRepairingIdentity() throws Exception {
        Path root = world("io-failure");
        byte[] bytes = Files.readAllBytes(identity(root));
        AtomicBoolean fail = new AtomicBoolean();
        java.io.IOException original = new java.io.IOException("injected native metadata failure");
        FileIdentityProvider failing = path -> {
            var identity = provider.identity(path);
            if (fail.get()) throw original;
            return identity;
        };
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(root))) {
            WorldBinding binding = bindings.accept(bindings.begin(ACTOR, DIMENSIONS).read(failing, CONTINUE));
            WorldBindingCheck check = bindings.check(binding);
            fail.set(true);
            WorldIdentityException error = assertThrows(WorldIdentityException.class, () -> check.verify(CONTINUE));
            assertEquals(WorldIdentityException.Code.IO_FAILURE, error.code());
            assertSame(original, error.getCause());
            fail.set(false);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.verify(binding));
            assertArrayEquals(bytes, Files.readAllBytes(identity(root)));
            bindings.verify(bind(bindings));
        }
    }

    @Test
    void supplierCanSupersedeCheckAndOldFailureCannotRevokeNewCheck() throws Exception {
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(world("superseded-check")))) {
            WorldBinding binding = bind(bindings);
            WorldBindingCheck old = bindings.check(binding);
            AtomicReference<WorldBindingCheck> replacement = new AtomicReference<>();
            RuntimeException failure = new IllegalStateException("old supplier failed after replacement");
            assertSame(failure, assertThrows(RuntimeException.class, () -> old.verify(() -> {
                replacement.set(bindings.check(binding));
                throw failure;
            })));
            bindings.confirm(replacement.get().verify(CONTINUE));
            bindings.verify(binding);

            WorldBindingCheck cancelled = bindings.check(binding);
            ioCode(WorldIdentityException.Code.CANCELLED, () -> cancelled.verify(() -> {
                replacement.set(bindings.check(binding));
                return false;
            }));
            bindings.confirm(replacement.get().verify(CONTINUE));
        }
    }

    @Test
    void supersededCheckIoFailureCannotRevokeReplacementDuringRealMetadataQuery() throws Exception {
        Path root = world("superseded-io-failure");
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.io.IOException original = new java.io.IOException("old metadata query failed after replacement");
        FileIdentityProvider paused = path -> {
            var node = provider.identity(path);
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new java.io.IOException("owner did not release paused metadata query");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("paused metadata query interrupted", interrupted);
                }
                throw original;
            }
            return node;
        };
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(root))) {
            WorldBinding binding = bindings.accept(bindings.begin(ACTOR, DIMENSIONS).read(paused, CONTINUE));
            WorldBindingCheck old = bindings.check(binding);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { old.verify(CONTINUE); }
                catch (Throwable failure) { outcome.set(failure); }
            }, "q58-superseded-identity-query");
            worker.setDaemon(true);
            armed.set(true);
            WorldBindingCheck replacement;
            try {
                worker.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS), "worker did not reach real metadata query");
                replacement = bindings.check(binding);
            } finally {
                release.countDown();
                worker.join(10_000);
                if (worker.isAlive()) {
                    worker.interrupt();
                    worker.join(1_000);
                }
                assertFalse(worker.isAlive(), "paused identity worker did not finish");
            }
            WorldIdentityException failure = assertInstanceOf(WorldIdentityException.class, outcome.get());
            assertEquals(WorldIdentityException.Code.IO_FAILURE, failure.code());
            assertSame(original, failure.getCause());
            assertFalse(armed.get(), "one-shot failure was not consumed");
            bindings.verify(binding);
            bindings.confirm(replacement.verify(CONTINUE));
            bindings.verify(binding);
        }
    }

    @Test
    void supersedingCheckDoesNotHideRealDiskCorruptionFromNewCheck() throws Exception {
        Path root = world("superseded-corrupt");
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(root))) {
            WorldBinding binding = bind(bindings);
            WorldBindingCheck old = bindings.check(binding);
            AtomicReference<WorldBindingCheck> replacement = new AtomicReference<>();
            ioCode(WorldIdentityException.Code.CANCELLED, () -> old.verify(() -> {
                try { Files.write(identity(root), new byte[] {9}); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                replacement.set(bindings.check(binding));
                return false;
            }));
            bindings.verify(binding);
            ioCode(WorldIdentityException.Code.CHANGED, () -> replacement.get().verify(CONTINUE));
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.verify(binding));
        }
    }

    @Test
    void cancelledOldCheckCannotRevokeNewBindingAndExplicitRevokeIsActorScoped() throws Exception {
        try (LocalWorldBindings bindings = new LocalWorldBindings(new Host(world("check-race")))) {
            WorldBinding old = bind(bindings);
            WorldBindingCheck oldCheck = bindings.check(old);
            WorldBinding current = bind(bindings);
            ioCode(WorldIdentityException.Code.CANCELLED, () -> oldCheck.verify(CONTINUE));
            bindings.verify(current);
            UUID other = UUID.randomUUID();
            WorldBinding otherBinding = bindings.accept(bindings.begin(other, DIMENSIONS).read(provider, CONTINUE));
            bindings.revoke(ACTOR);
            bindings.revoke(ACTOR);
            bindingCode(WorldBindingException.Code.STALE, () -> bindings.verify(current));
            bindings.verify(otherBinding);
        }
    }

    @Test
    void wrongOwnerAndWrongThreadDoNotMutateCurrentSlotAndIoMayRunOnWorker() throws Exception {
        Host host = new Host(world("owner"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
            AtomicReference<WorldBindingRead> result = new AtomicReference<>();
            onWorker(() -> result.set(request.read(provider, CONTINUE)));
            WorldBinding binding = bindings.accept(result.get());
            onWorker(() -> bindingCode(WorldBindingException.Code.WRONG_THREAD,
                    () -> bindings.begin(ACTOR, DIMENSIONS)));
            host.ownerAllowed = false;
            bindingCode(WorldBindingException.Code.WRONG_THREAD, () -> bindings.revoke(ACTOR));
            bindingCode(WorldBindingException.Code.WRONG_THREAD, bindings::close);
            host.ownerAllowed = true;
            bindings.verify(binding);
            WorldBindingCheck check = bindings.check(binding);
            AtomicReference<WorldBindingCheckResult> checked = new AtomicReference<>();
            onWorker(() -> checked.set(check.verify(CONTINUE)));
            bindings.confirm(checked.get());
        }
    }

    @Test
    void reentrantControllerCallsAreBusyAndCallbackCloseCannotResurrectSlots() throws Exception {
        Host host = new Host(world("reentrant"));
        LocalWorldBindings bindings = new LocalWorldBindings(host);
        host.onCapture = () -> bindingCode(WorldBindingException.Code.BUSY,
                () -> bindings.begin(UUID.randomUUID(), DIMENSIONS));
        WorldBinding binding = bind(bindings);
        host.onVerify = () -> bindingCode(WorldBindingException.Code.BUSY, () -> bindings.revoke(ACTOR));
        bindings.verify(binding);
        host.onVerify = bindings::close;
        bindingCode(WorldBindingException.Code.CLOSED, () -> bindings.verify(binding));
        bindings.close();
        bindingCode(WorldBindingException.Code.CLOSED, () -> bindings.begin(ACTOR, DIMENSIONS));

        Host captureHost = new Host(host.root);
        LocalWorldBindings duringCapture = new LocalWorldBindings(captureHost);
        captureHost.onCapture = duringCapture::close;
        bindingCode(WorldBindingException.Code.CLOSED, () -> duringCapture.begin(ACTOR, DIMENSIONS));
        duringCapture.close();

        Host activeHost = new Host(host.root);
        LocalWorldBindings duringActive = new LocalWorldBindings(activeHost);
        activeHost.onActive = duringActive::close;
        bindingCode(WorldBindingException.Code.CLOSED, () -> duringActive.begin(ACTOR, DIMENSIONS));
        duringActive.close();
    }

    @Test
    void cancelledSuccessfulReadCannotBeAcceptedAndCloseDuringReadCannotPublishBinding() throws Exception {
        Host host = new Host(world("read-close"));
        LocalWorldBindings bindings = new LocalWorldBindings(host);
        WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
        WorldBindingRead read = request.read(provider, CONTINUE);
        request.cancel();
        bindingCode(WorldBindingException.Code.STALE, () -> bindings.accept(read));
        WorldBindingRequest closing = bindings.begin(ACTOR, DIMENSIONS);
        ioCode(WorldIdentityException.Code.CANCELLED, () -> closing.read(provider, () -> {
            bindings.close();
            return false;
        }));
        bindingCode(WorldBindingException.Code.CLOSED, () -> bindings.accept(read));
        bindings.close();
    }

    @Test
    void nullArgumentsAndInvalidTargetAreRejectedWithoutReplacingCurrentBinding() throws Exception {
        Host host = new Host(world("nulls"));
        assertThrows(NullPointerException.class, () -> new LocalWorldBindings(null));
        host.ownerAllowed = false;
        bindingCode(WorldBindingException.Code.WRONG_THREAD, () -> new LocalWorldBindings(host));
        host.ownerAllowed = true;
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            WorldBinding binding = bind(bindings);
            assertThrows(NullPointerException.class, () -> bindings.begin(null, DIMENSIONS));
            assertThrows(NullPointerException.class, () -> bindings.begin(ACTOR, null));
            Map<EditorId, EditorId> nullKey = new HashMap<>();
            nullKey.put(null, EditorId.parse("minecraft:overworld"));
            assertThrows(NullPointerException.class, () -> bindings.begin(ACTOR, nullKey));
            Map<EditorId, EditorId> nullValue = new HashMap<>();
            nullValue.put(EditorId.parse("project:main"), null);
            assertThrows(NullPointerException.class, () -> bindings.begin(ACTOR, nullValue));
            assertThrows(NullPointerException.class, () -> bindings.accept(null));
            assertThrows(NullPointerException.class, () -> bindings.verify(null));
            assertThrows(NullPointerException.class, () -> bindings.check(null));
            assertThrows(NullPointerException.class, () -> bindings.confirm(null));
            assertThrows(NullPointerException.class, () -> bindings.discard(null));
            assertThrows(NullPointerException.class, () -> bindings.revoke(null));
            bindings.verify(binding);
        }
    }

    @Test
    void actorAndDimensionBudgetsRejectBeforeRevokingAndMappingsAreSortedImmutableCopies() throws Exception {
        Host host = new Host(world("limits"));
        try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
            Map<EditorId, EditorId> source = new LinkedHashMap<>();
            source.put(EditorId.parse("project:z"), EditorId.parse("minecraft:the_end"));
            source.put(EditorId.parse("project:a"), EditorId.parse("minecraft:overworld"));
            WorldBindingRequest request = bindings.begin(ACTOR, source);
            source.clear();
            assertEquals(List.of(EditorId.parse("project:a"), EditorId.parse("project:z")),
                    new ArrayList<>(request.dimensions().keySet()));
            assertThrows(UnsupportedOperationException.class, () -> request.dimensions().clear());
            WorldBinding current = bindings.accept(request.read(provider, CONTINUE));
            assertEquals(request.dimensions(), current.dimensions());
            assertThrows(UnsupportedOperationException.class, () -> current.dimensions().clear());
            Map<EditorId, EditorId> dimensions = dimensions(64);
            WorldBindingRequest max = bindings.begin(ACTOR, dimensions);
            assertEquals(64, max.dimensions().size());
            WorldBinding binding = bindings.accept(max.read(provider, CONTINUE));
            for (Map<EditorId, EditorId> invalid : List.of(Map.<EditorId, EditorId>of(), dimensions(65),
                    Map.of(EditorId.parse("project:a"), EditorId.parse("minecraft:overworld"),
                           EditorId.parse("project:b"), EditorId.parse("minecraft:overworld")))) {
                bindingCode(WorldBindingException.Code.INVALID_TARGET, () -> bindings.begin(ACTOR, invalid));
                bindings.verify(binding);
            }
            bindingCode(WorldBindingException.Code.INVALID_TARGET,
                    () -> bindings.begin(new UUID(0, 0), DIMENSIONS));
            for (int i = 1; i < 64; i++) bindings.begin(new UUID(1, i), DIMENSIONS);
            int captures = host.captures;
            bindingCode(WorldBindingException.Code.LIMIT, () -> bindings.begin(new UUID(2, 1), DIMENSIONS));
            assertEquals(captures, host.captures);
            bindings.verify(binding);
            WorldBindingRequest replacement = bindings.begin(ACTOR, DIMENSIONS);
            bindings.discard(replacement);
            bindings.begin(new UUID(2, 1), DIMENSIONS);
            bindings.revoke(new UUID(1, 1));
            bindings.begin(new UUID(2, 2), DIMENSIONS);
        }
    }

    @Test
    void closeCancelsOutstandingWorkAndReleasesHostSelectionReachability() throws Exception {
        Host host = new Host(world("close"));
        LocalWorldBindings bindings = new LocalWorldBindings(host);
        WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
        WorldBindingRead read = request.read(provider, CONTINUE);
        WorldBinding binding = bindings.accept(read);
        WorldBindingCheck check = bindings.check(binding);
        WorldBindingCheckResult result = check.verify(CONTINUE);
        WeakReference<LocalWorldBindingHost.Selection> selected = host.lastSelection;
        bindings.close();
        bindings.close();
        bindingCode(WorldBindingException.Code.CLOSED, () -> bindings.verify(binding));
        bindingCode(WorldBindingException.Code.CLOSED, () -> bindings.confirm(result));
        // Only this fake host Selection is observed. This is no whole-heap/native leak claim.
        for (int i = 0; i < 30 && selected.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(selected.get(), "closed service and retained public tokens must release host Selection");
        assertNotNull(request.requestId());
        assertNotNull(binding.worldId());
        assertNotNull(read);
        assertNotNull(result);
        java.lang.ref.Reference.reachabilityFence(request);
        java.lang.ref.Reference.reachabilityFence(read);
        java.lang.ref.Reference.reachabilityFence(binding);
        java.lang.ref.Reference.reachabilityFence(check);
        java.lang.ref.Reference.reachabilityFence(result);
        java.lang.ref.Reference.reachabilityFence(bindings);
    }

    private Path world(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        WorldIdentities.initialize(root, provider, CONTINUE);
        return root;
    }

    private static Path identity(Path root) { return root.resolve(".editor-platform/world.identity"); }

    private WorldBinding bind(LocalWorldBindings bindings) throws Exception {
        return bindings.accept(bindings.begin(ACTOR, DIMENSIONS).read(provider, CONTINUE));
    }

    private static Map<EditorId, EditorId> dimensions(int count) {
        Map<EditorId, EditorId> result = new HashMap<>();
        for (int i = 0; i < count; i++) result.put(new EditorId("project", "d" + i), new EditorId("target", "d" + i));
        return result;
    }

    private static void bindingCode(WorldBindingException.Code code, Executable action) {
        assertEquals(code, assertThrows(WorldBindingException.class, action).code());
    }

    private static void ioCode(WorldIdentityException.Code code, Executable action) {
        assertEquals(code, assertThrows(WorldIdentityException.class, action).code());
    }

    private static void onWorker(Executable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { action.execute(); } catch (Throwable error) { failure.set(error); } });
        worker.start();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "worker did not finish");
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
    }

    /** Trusted owner/selection seam only; no claim that this represents actual MC authentication. */
    private static final class Host implements LocalWorldBindingHost {
        final Thread owner = Thread.currentThread();
        Path root;
        boolean ownerAllowed = true;
        boolean active = true;
        int captures;
        WorldBindingException.Code captureFailure;
        WorldBindingException.Code selectionFailure;
        Runnable onCapture = () -> {};
        Runnable onVerify = () -> {};
        Runnable onActive = () -> {};
        WeakReference<Selection> lastSelection;

        Host(Path root) { this.root = root; }
        @Override public void requireOwner() {
            if (!ownerAllowed || Thread.currentThread() != owner)
                throw new WorldBindingException(WorldBindingException.Code.WRONG_THREAD, "fake host owner");
        }
        @Override public boolean active() { requireOwner(); onActive.run(); return active; }
        @Override public Selection capture(UUID actor, Map<EditorId, EditorId> dimensions) {
            requireOwner();
            captures++;
            onCapture.run();
            if (captureFailure != null) throw new WorldBindingException(captureFailure, "fake capture failure");
            Path selectedRoot = root;
            Selection selection = new Selection() {
                @Override public Path root() { return selectedRoot; }
                @Override public void verify() {
                    requireOwner();
                    onVerify.run();
                    if (selectionFailure != null)
                        throw new WorldBindingException(selectionFailure, "fake selection changed");
                    if (!selectedRoot.equals(root))
                        throw new WorldBindingException(WorldBindingException.Code.STALE, "fake root changed");
                }
            };
            lastSelection = new WeakReference<>(selection);
            return selection;
        }
    }
}
