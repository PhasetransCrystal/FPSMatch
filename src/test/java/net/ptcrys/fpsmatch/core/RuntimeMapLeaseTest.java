package net.ptcrys.fpsmatch.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeMapLeaseTest {
    @Test
    void constructionCopiesConsumersWithoutCallingAnyCallback() {
        List<String> calls = new ArrayList<>();
        List<Runnable> consumers = new ArrayList<>();
        consumers.add(() -> calls.add("map"));
        consumers.add(() -> calls.add("teams"));
        RuntimeMapLease lease = new RuntimeMapLease(consumers,
                () -> calls.add("release"), () -> calls.add("detach"), () -> {});
        consumers.clear();
        consumers.add(() -> calls.add("replacement"));
        assertDoesNotThrow(lease::requireOpen);
        assertTrue(calls.isEmpty());
        lease.close();
        assertEquals(List.of("detach", "map", "teams", "release"), calls);
    }

    @Test
    void nullInputsIncludingLateConsumerFailBeforeCallbacks() {
        List<String> calls = new ArrayList<>();
        Runnable callback = () -> calls.add("called");
        assertReason("invalid_input", () -> new RuntimeMapLease(null, callback, callback, callback));
        assertReason("invalid_input", () -> new RuntimeMapLease(List.of(callback), null, callback, callback));
        assertReason("invalid_input", () -> new RuntimeMapLease(List.of(callback), callback, null, callback));
        assertReason("invalid_input", () -> new RuntimeMapLease(List.of(callback), callback, callback, null));
        assertReason("invalid_input", () -> new RuntimeMapLease(Arrays.asList(callback, null), callback, callback, callback));
        assertTrue(calls.isEmpty());
    }

    @Test
    void emptyConsumersStillDetachThenReleaseExactlyOnce() {
        List<String> calls = new ArrayList<>();
        RuntimeMapLease lease = new RuntimeMapLease(List.of(),
                () -> calls.add("release"), () -> calls.add("detach"), () -> {});
        lease.close();
        lease.close();
        assertReason("closed", lease::requireOpen);
        assertEquals(List.of("detach", "release"), calls);
    }

    @Test
    void terminalStateAndAllCallbackReferencesAreClearedBeforeAnyCallback() {
        RuntimeMapLease[] holder = new RuntimeMapLease[1];
        List<String> calls = new ArrayList<>();
        Runnable observe = () -> {
            RuntimeMapLease lease = holder[0];
            assertReason("closed", lease::requireOpen);
            for (String field : List.of("consumers", "release", "detached", "beforeClose")) {
                assertNull(field(lease, field), field);
            }
            lease.close();
            calls.add("observed");
        };
        holder[0] = new RuntimeMapLease(List.of(observe, observe), observe, observe, () -> {});
        holder[0].close();
        assertEquals(4, calls.size());
    }

    @Test
    void detachFailureRemainsPrimaryWhileEveryConsumerAndReleaseIsAttempted() {
        assertFailureSequence(new IllegalStateException("detach"));
    }

    @Test
    void detachErrorRemainsPrimaryWhileEveryConsumerAndReleaseIsAttempted() {
        assertFailureSequence(new AssertionError("detach"));
    }

    private static void assertFailureSequence(Throwable primary) {
        List<String> calls = new ArrayList<>();
        AssertionError mapFailure = new AssertionError("map");
        IllegalArgumentException releaseFailure = new IllegalArgumentException("release");
        RuntimeMapLease lease = new RuntimeMapLease(List.of(() -> {
            calls.add("map");
            throw mapFailure;
        }, () -> calls.add("teams")), () -> {
            calls.add("release");
            throw releaseFailure;
        }, () -> {
            calls.add("detach");
            throwUnchecked(primary);
        }, () -> {});
        assertSame(primary, assertThrows(Throwable.class, lease::close));
        assertArrayEquals(new Throwable[]{mapFailure, releaseFailure}, primary.getSuppressed());
        assertEquals(List.of("detach", "map", "teams", "release"), calls);
        lease.close();
        assertEquals(4, calls.size());
        assertReason("closed", lease::requireOpen);
    }

    @Test
    void firstConsumerFailureIsPrimaryAndRepeatedIdentityNeverSuppressesItself() {
        AssertionError shared = new AssertionError("shared");
        IllegalStateException later = new IllegalStateException("later");
        List<String> calls = new ArrayList<>();
        RuntimeMapLease lease = new RuntimeMapLease(List.of(() -> {
            calls.add("first");
            throw shared;
        }, () -> {
            calls.add("second");
            throw shared;
        }, () -> {
            calls.add("third");
            throw later;
        }), () -> {
            calls.add("release");
            throw shared;
        }, () -> calls.add("detach"), () -> {});
        assertSame(shared, assertThrows(AssertionError.class, lease::close));
        assertArrayEquals(new Throwable[]{later}, shared.getSuppressed());
        assertEquals(List.of("detach", "first", "second", "third", "release"), calls);
    }

    @Test
    void releaseFailurePropagatesAfterAllConsumersAndIsNeverRetried() {
        AssertionError failure = new AssertionError("release");
        List<String> calls = new ArrayList<>();
        RuntimeMapLease lease = new RuntimeMapLease(List.of(() -> calls.add("consumer")), () -> {
            calls.add("release");
            throw failure;
        }, () -> calls.add("detach"), () -> {});
        assertSame(failure, assertThrows(AssertionError.class, lease::close));
        lease.close();
        assertEquals(List.of("detach", "consumer", "release"), calls);
    }

    @Test
    void wrongThreadPrecedesOpenAndClosedStateWithoutRunningCallbacks() throws InterruptedException {
        List<String> calls = new ArrayList<>();
        RuntimeMapLease lease = new RuntimeMapLease(List.of(() -> calls.add("consumer")),
                () -> calls.add("release"), () -> calls.add("detach"), () -> {});
        assertWorkerWrongThread(lease);
        assertTrue(calls.isEmpty());
        lease.requireOpen();
        lease.close();
        assertWorkerWrongThread(lease);
        assertEquals(List.of("detach", "consumer", "release"), calls);
    }

    private static void assertWorkerWrongThread(RuntimeMapLease lease) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                assertReason("wrong_thread", lease::requireOpen);
                assertReason("wrong_thread", lease::close);
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
    }

    @Test
    void shutdownCleanupAttemptsAnUninstalledCandidateDespitePersistenceFailureAndReentry() {
        List<String> calls = new ArrayList<>();
        RuntimeMapLease pending = new RuntimeMapLease(List.of(() -> calls.add("pending-map"),
                () -> calls.add("pending-teams")), () -> calls.add("templates"),
                () -> calls.add("detach"), () -> {});
        IllegalStateException saveFailure = new IllegalStateException("save");
        assertSame(saveFailure, assertThrows(IllegalStateException.class, () ->
                ShutdownCleanup.run(() -> {
                    calls.add("save");
                    pending.close();
                    throw saveFailure;
                }, List.of(pending::close, () -> calls.add("legacy-map")))));
        assertEquals(List.of("save", "detach", "pending-map", "pending-teams", "templates", "legacy-map"), calls);
        assertReason("closed", pending::requireOpen);
    }

    @Test
    void preflightRuntimeFailurePreservesOpenLeaseReferencesAndCanBeRetried() {
        assertPreflightFailure(new IllegalStateException("preflight"));
    }

    @Test
    void preflightErrorPreservesOpenLeaseReferencesAndCanBeRetried() {
        assertPreflightFailure(new AssertionError("preflight"));
    }

    private static void assertPreflightFailure(Throwable expected) {
        List<String> calls = new ArrayList<>();
        AtomicBoolean blocked = new AtomicBoolean(true);
        RuntimeMapLease lease = new RuntimeMapLease(List.of(() -> calls.add("consumer")),
                () -> calls.add("release"), () -> calls.add("detach"), () -> {
                    calls.add("preflight");
                    if (blocked.get()) throwUnchecked(expected);
                });
        Object consumers = field(lease, "consumers");
        Object release = field(lease, "release");
        Object detached = field(lease, "detached");
        Object preflight = field(lease, "beforeClose");
        assertSame(expected, assertThrows(Throwable.class, lease::close));
        lease.requireOpen();
        assertEquals(List.of("preflight"), calls);
        assertSame(consumers, field(lease, "consumers"));
        assertSame(release, field(lease, "release"));
        assertSame(detached, field(lease, "detached"));
        assertSame(preflight, field(lease, "beforeClose"));
        blocked.set(false);
        lease.close();
        lease.close();
        assertEquals(List.of("preflight", "preflight", "detach", "consumer", "release"), calls);
        assertReason("closed", lease::requireOpen);
    }

    @Test
    void preflightReentryIsBusyWithoutClosingOrClearingAnyResources() {
        List<String> calls = new ArrayList<>();
        RuntimeMapLease[] holder = new RuntimeMapLease[1];
        AtomicBoolean reenter = new AtomicBoolean(true);
        holder[0] = new RuntimeMapLease(List.of(() -> calls.add("consumer")),
                () -> calls.add("release"), () -> calls.add("detach"), () -> {
                    calls.add("preflight");
                    holder[0].requireOpen();
                    if (reenter.get()) holder[0].close();
                });
        RuntimeMapLease lease = holder[0];
        assertReason("busy", lease::close);
        lease.requireOpen();
        for (String name : List.of("consumers", "release", "detached", "beforeClose")) {
            assertNotNull(field(lease, name));
        }
        assertEquals(List.of("preflight"), calls);
        reenter.set(false);
        lease.close();
        assertEquals(List.of("preflight", "preflight", "detach", "consumer", "release"), calls);
    }

    @Test
    void wrongThreadAndAlreadyClosedChecksNeverEnterPreflight() throws InterruptedException {
        List<String> calls = new ArrayList<>();
        RuntimeMapLease lease = new RuntimeMapLease(List.of(), () -> calls.add("release"),
                () -> calls.add("detach"), () -> calls.add("preflight"));
        assertWorkerWrongThread(lease);
        assertTrue(calls.isEmpty());
        lease.close();
        lease.close();
        assertWorkerWrongThread(lease);
        assertEquals(List.of("preflight", "detach", "release"), calls);
    }

    private static void assertReason(String reason, Runnable operation) {
        RuntimeMapLeaseException failure = assertThrows(RuntimeMapLeaseException.class, operation::run);
        assertEquals("editor:runtime_map/" + reason, failure.getMessage());
        assertNotNull(failure.problem());
    }

    private static Object field(Object object, String name) {
        try {
            Field field = RuntimeMapLease.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }
}
