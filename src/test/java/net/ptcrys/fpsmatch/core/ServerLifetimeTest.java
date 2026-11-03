package net.ptcrys.fpsmatch.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerLifetimeTest {
    @Test
    void serverIdentityIsCapturedByReferenceEvenWhenAnotherServerIsEqual() {
        String server = new String("same name");
        String equalServer = new String("same name");
        assertEquals(server, equalServer);
        assertNotSame(server, equalServer);
        var lifetime = new ServerLifetime<>(server, () -> true);

        assertSame(server, lifetime.server());
        assertTrue(lifetime.matches(server));
        assertFalse(lifetime.matches(equalServer));
        assertFalse(lifetime.matches(null));
        lifetime.stopped(() -> {});
        assertSame(server, lifetime.server());
        assertTrue(lifetime.matches(server));
        assertFalse(lifetime.matches(equalServer));
    }

    @Test
    void normalStoppingInvalidatesBeforeOneFinalSaveAndOneClose() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        List<String> calls = new ArrayList<>();
        assertTrue(lifetime.active());
        lifetime.stopping(() -> {
            assertFalse(lifetime.active());
            calls.add("save");
        }, () -> {
            assertFalse(lifetime.active());
            calls.add("close");
        });
        lifetime.stopping(() -> calls.add("late-save"), () -> calls.add("late-close"));
        lifetime.stopped(() -> calls.add("stopped-close"));
        lifetime.stopped(() -> calls.add("repeated-close"));
        assertFalse(lifetime.active());
        assertEquals(List.of("save", "close"), calls);
    }

    @Test
    void directStoppedOnlyClosesAndNeverAllowsALaterFinalSave() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        List<String> calls = new ArrayList<>();
        lifetime.stopped(() -> {
            assertFalse(lifetime.active());
            calls.add("close");
        });
        lifetime.stopping(() -> calls.add("save"), () -> calls.add("late-close"));
        lifetime.stopped(() -> calls.add("repeated-close"));
        assertEquals(List.of("close"), calls);
        assertFalse(lifetime.active());
    }

    @Test
    void reentrantStoppingDoesNotSaveOrCloseInsideShutdown() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        List<String> calls = new ArrayList<>();
        lifetime.stopping(() -> {
            calls.add("save-start");
            lifetime.stopping(() -> calls.add("nested-save"), () -> calls.add("nested-close"));
            assertEquals(List.of("save-start"), calls);
            calls.add("save-end");
        }, () -> calls.add("close"));
        assertEquals(List.of("save-start", "save-end", "close"), calls);
    }

    @Test
    void stoppedInsideShutdownDefersCloseUntilShutdownFinishes() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        List<String> calls = new ArrayList<>();
        Runnable close = () -> calls.add("close");
        lifetime.stopping(() -> {
            calls.add("save-start");
            lifetime.stopped(close);
            lifetime.stopped(close);
            lifetime.stopping(() -> calls.add("nested-save"), close);
            assertFalse(lifetime.active());
            assertEquals(List.of("save-start"), calls);
            calls.add("save-end");
        }, close);
        lifetime.stopped(close);
        assertEquals(List.of("save-start", "save-end", "close"), calls);
    }

    @Test
    void closeCanReenterBothTerminationMethodsWithoutBeingRetried() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        List<String> calls = new ArrayList<>();
        lifetime.stopping(() -> calls.add("save"), () -> {
            calls.add("close");
            lifetime.stopped(() -> calls.add("nested-close"));
            lifetime.stopping(() -> calls.add("nested-save"), () -> calls.add("nested-close"));
        });
        assertEquals(List.of("save", "close"), calls);
    }

    @Test
    void directStoppedCloseCanReenterWithoutRepeatingCleanup() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        AtomicInteger closes = new AtomicInteger();
        lifetime.stopped(() -> {
            closes.incrementAndGet();
            lifetime.stopped(closes::incrementAndGet);
            lifetime.stopping(() -> fail("late shutdown"), closes::incrementAndGet);
        });
        assertEquals(1, closes.get());
    }

    @Test
    void runtimeShutdownFailureStillClosesAndPreservesTheOriginalFailure() {
        assertShutdownFailure(new IllegalStateException("save failed"));
    }

    @Test
    void errorDuringShutdownStillClosesAndPreservesTheOriginalFailure() {
        assertShutdownFailure(new AssertionError("save failed"));
    }

    private static void assertShutdownFailure(Throwable failure) {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        AtomicInteger saves = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Throwable caught = assertThrows(Throwable.class, () -> lifetime.stopping(() -> {
            saves.incrementAndGet();
            throwUnchecked(failure);
        }, closes::incrementAndGet));
        assertSame(failure, caught);
        assertFalse(lifetime.active());
        lifetime.stopping(saves::incrementAndGet, closes::incrementAndGet);
        lifetime.stopped(closes::incrementAndGet);
        assertEquals(1, saves.get());
        assertEquals(1, closes.get());
        assertEquals(0, caught.getSuppressed().length);
    }

    @Test
    void shutdownRemainsPrimaryWhenCleanupAlsoFailsIncludingErrors() {
        assertDoubleFailure(new IllegalArgumentException("save"), new IllegalStateException("close"));
        assertDoubleFailure(new AssertionError("save"), new IllegalStateException("close"));
        assertDoubleFailure(new IllegalStateException("save"), new AssertionError("close"));
        assertDoubleFailure(new AssertionError("save"), new AssertionError("close"));
    }

    private static void assertDoubleFailure(Throwable primary, Throwable cleanup) {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        Throwable caught = assertThrows(Throwable.class, () -> lifetime.stopping(
                () -> throwUnchecked(primary), () -> throwUnchecked(cleanup)));
        assertSame(primary, caught);
        assertArrayEquals(new Throwable[]{cleanup}, caught.getSuppressed());
        lifetime.stopped(() -> fail("failed close must not be retried"));
        lifetime.stopping(() -> fail("failed save must not be retried"),
                () -> fail("failed close must not be retried"));
        assertFalse(lifetime.active());
    }

    @Test
    void theSameFailureObjectIsNeverSuppressedOntoItself() {
        assertSameFailure(new IllegalStateException("shared"));
        assertSameFailure(new AssertionError("shared"));
    }

    private static void assertSameFailure(Throwable shared) {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        assertSame(shared, assertThrows(Throwable.class, () -> lifetime.stopping(
                () -> throwUnchecked(shared), () -> throwUnchecked(shared))));
        assertEquals(0, shared.getSuppressed().length);
        lifetime.stopped(() -> fail("close already attempted"));
    }

    @Test
    void cleanupFailureAfterSuccessfulShutdownIsPropagatedAndNeverRetried() {
        for (Throwable failure : List.of(new IllegalStateException("close"), new AssertionError("close"))) {
            var lifetime = new ServerLifetime<>(new Object(), () -> true);
            AtomicInteger saves = new AtomicInteger();
            assertSame(failure, assertThrows(Throwable.class, () -> lifetime.stopping(
                    saves::incrementAndGet, () -> throwUnchecked(failure))));
            lifetime.stopping(saves::incrementAndGet, () -> fail("close retry"));
            lifetime.stopped(() -> fail("close retry"));
            assertEquals(1, saves.get());
            assertFalse(lifetime.active());
        }
    }

    @Test
    void directStoppedFailureLeavesLifetimeRevokedAndDoesNotRetryCloseOrSave() {
        for (Throwable failure : List.of(new IllegalStateException("close"), new AssertionError("close"))) {
            var lifetime = new ServerLifetime<>(new Object(), () -> true);
            assertSame(failure, assertThrows(Throwable.class,
                    () -> lifetime.stopped(() -> throwUnchecked(failure))));
            assertFalse(lifetime.active());
            lifetime.stopped(() -> fail("close retry"));
            lifetime.stopping(() -> fail("late save"), () -> fail("close retry"));
        }
    }

    @Test
    void stoppedDuringFailingShutdownStillDefersCleanupAndPreservesFailures() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        List<String> calls = new ArrayList<>();
        var primary = new AssertionError("shutdown");
        var cleanup = new IllegalStateException("close");
        Runnable close = () -> {
            calls.add("close");
            throw cleanup;
        };
        assertSame(primary, assertThrows(AssertionError.class, () -> lifetime.stopping(() -> {
            calls.add("save");
            lifetime.stopped(close);
            assertEquals(List.of("save"), calls);
            throw primary;
        }, close)));
        assertEquals(List.of("save", "close"), calls);
        assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
        lifetime.stopped(() -> fail("cleanup retry"));
    }

    @Test
    void rejectedOwnerDoesNotMutateLifetimeOrInvokeCallbacks() {
        AtomicBoolean owner = new AtomicBoolean(false);
        var lifetime = new ServerLifetime<>(new Object(), owner::get);
        AtomicInteger callbacks = new AtomicInteger();
        assertThrows(IllegalStateException.class, lifetime::requireOwner);
        assertThrows(IllegalStateException.class,
                () -> lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet));
        assertThrows(IllegalStateException.class, () -> lifetime.stopped(callbacks::incrementAndGet));
        assertTrue(lifetime.active());
        assertEquals(0, callbacks.get());
        owner.set(true);
        assertDoesNotThrow(lifetime::requireOwner);
        lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet);
        assertEquals(2, callbacks.get());
        owner.set(false);
        assertThrows(IllegalStateException.class,
                () -> lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet));
        assertThrows(IllegalStateException.class, () -> lifetime.stopped(callbacks::incrementAndGet));
        assertEquals(2, callbacks.get());
    }

    @Test
    void ownerSupplierFailuresPropagateUnchangedWithoutRevokingLifetime() {
        for (Throwable failure : List.of(new IllegalStateException("owner"), new AssertionError("owner"))) {
            AtomicBoolean reject = new AtomicBoolean(true);
            var lifetime = new ServerLifetime<>(new Object(), () -> {
                if (reject.get()) throwUnchecked(failure);
                return true;
            });
            AtomicInteger callbacks = new AtomicInteger();
            assertSame(failure, assertThrows(Throwable.class, lifetime::requireOwner));
            assertSame(failure, assertThrows(Throwable.class,
                    () -> lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet)));
            assertSame(failure, assertThrows(Throwable.class,
                    () -> lifetime.stopped(callbacks::incrementAndGet)));
            assertTrue(lifetime.active());
            assertEquals(0, callbacks.get());
            reject.set(false);
            assertFalse(lifetime.terminalCleanupFinished());
            lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet);
            assertEquals(2, callbacks.get());
            assertFalse(lifetime.terminalCleanupFinished());
        }
    }

    @Test
    void nullCallbacksFailBeforeRevocationOrAnyCallback() {
        assertThrows(NullPointerException.class, () -> new ServerLifetime<>(null, () -> true));
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        AtomicInteger callbacks = new AtomicInteger();
        assertThrows(NullPointerException.class, () -> lifetime.stopping(null, callbacks::incrementAndGet));
        assertThrows(NullPointerException.class, () -> lifetime.stopping(callbacks::incrementAndGet, null));
        assertThrows(NullPointerException.class, () -> lifetime.stopped(null));
        assertTrue(lifetime.active());
        assertEquals(0, callbacks.get());
        lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet);
        assertEquals(2, callbacks.get());
        assertThrows(NullPointerException.class, () -> lifetime.stopping(null, () -> {}));
        assertThrows(NullPointerException.class, () -> lifetime.stopped(null));
    }

    @Test
    void anotherThreadObservesRevocationWhileShutdownRunsButCannotPerformOwnerActions() throws Exception {
        Thread owner = Thread.currentThread();
        var lifetime = new ServerLifetime<>(new Object(), () -> Thread.currentThread() == owner);
        CountDownLatch observerReady = new CountDownLatch(1);
        CountDownLatch observedRevocation = new CountDownLatch(1);
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        AtomicInteger forbiddenCallbacks = new AtomicInteger();
        Thread observer = new Thread(() -> {
            try {
                assertTrue(lifetime.active());
                observerReady.countDown();
                // No owner-to-observer latch publishes the state write: active() itself must expose it.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (lifetime.active() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(lifetime.active(), "revocation must become visible before shutdown returns");
                assertThrows(IllegalStateException.class, lifetime::requireOwner);
                assertThrows(IllegalStateException.class, () -> lifetime.stopping(
                        forbiddenCallbacks::incrementAndGet, forbiddenCallbacks::incrementAndGet));
                assertThrows(IllegalStateException.class,
                        () -> lifetime.stopped(forbiddenCallbacks::incrementAndGet));
            } catch (Throwable failure) {
                observerFailure.set(failure);
            } finally {
                observerReady.countDown();
                observedRevocation.countDown();
            }
        }, "server-lifetime-observer");
        observer.setDaemon(true);
        observer.start();
        AtomicInteger closes = new AtomicInteger();
        try {
            assertTrue(observerReady.await(5, TimeUnit.SECONDS));
            lifetime.stopping(() -> {
                try {
                    assertTrue(observedRevocation.await(6, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                assertEquals(0, closes.get(), "close must wait for shutdown to finish");
                assertNull(observerFailure.get());
            }, closes::incrementAndGet);
        } finally {
            observer.join(6000);
        }
        assertFalse(observer.isAlive());
        assertNull(observerFailure.get());
        assertEquals(0, forbiddenCallbacks.get());
        assertEquals(1, closes.get());
        assertFalse(lifetime.active());
    }

    @Test
    void terminalCleanupWaitsForReentrantStoppedShutdownAndItsOuterCloseToFinish() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        AtomicInteger closes = new AtomicInteger();
        Runnable close = () -> {
            closes.incrementAndGet();
            assertFalse(lifetime.terminalCleanupFinished(), "close callback is still in flight");
        };
        assertFalse(lifetime.terminalCleanupFinished());
        lifetime.stopping(() -> {
            assertFalse(lifetime.terminalCleanupFinished());
            lifetime.stopped(close);
            assertFalse(lifetime.active());
            assertFalse(lifetime.terminalCleanupFinished(), "Stopped must not release an in-flight shutdown");
            lifetime.stopped(close);
            assertEquals(0, closes.get());
            assertFalse(lifetime.terminalCleanupFinished());
        }, close);
        assertEquals(1, closes.get());
        assertTrue(lifetime.terminalCleanupFinished());
        assertFalse(lifetime.active());
    }

    @Test
    void stoppedReenteredFromCloseDoesNotReportTerminalUntilCloseReturns() {
        for (boolean directStopped : List.of(false, true)) {
            var lifetime = new ServerLifetime<>(new Object(), () -> true);
            AtomicInteger closes = new AtomicInteger();
            Runnable close = () -> {
                closes.incrementAndGet();
                assertFalse(lifetime.terminalCleanupFinished());
                lifetime.stopped(() -> fail("reentrant close must not run"));
                assertFalse(lifetime.terminalCleanupFinished(), "reentrant Stopped cannot finish its outer close");
                lifetime.stopping(() -> fail("reentrant save must not run"),
                        () -> fail("reentrant close must not run"));
                assertFalse(lifetime.terminalCleanupFinished());
            };
            if (directStopped) lifetime.stopped(close);
            else lifetime.stopping(() -> {}, close);
            assertEquals(1, closes.get());
            assertTrue(lifetime.terminalCleanupFinished());
            assertFalse(lifetime.active());
        }
    }

    @Test
    void terminalCleanupMeansCloseAttemptHasFinishedEvenWhenDirectStoppedCloseFails() {
        for (Throwable failure : List.of(new IllegalStateException("close"), new AssertionError("close"))) {
            var lifetime = new ServerLifetime<>(new Object(), () -> true);
            assertSame(failure, assertThrows(Throwable.class, () -> lifetime.stopped(() -> {
                assertFalse(lifetime.active());
                assertFalse(lifetime.terminalCleanupFinished());
                throwUnchecked(failure);
            })));
            assertTrue(lifetime.terminalCleanupFinished());
            assertFalse(lifetime.active());
            lifetime.stopped(() -> fail("failed cleanup must not retry"));
            assertTrue(lifetime.terminalCleanupFinished());
        }
    }

    @Test
    void completedStoppingIsNotTerminalUntilStoppedArrives() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        AtomicInteger closes = new AtomicInteger();
        lifetime.stopping(() -> assertFalse(lifetime.terminalCleanupFinished()), () -> {
            closes.incrementAndGet();
            assertFalse(lifetime.terminalCleanupFinished());
        });
        assertFalse(lifetime.active());
        assertFalse(lifetime.terminalCleanupFinished(), "successful Stopping is not a Stopped notification");
        lifetime.stopping(() -> fail("duplicate save"), () -> fail("duplicate close"));
        assertFalse(lifetime.terminalCleanupFinished());
        lifetime.stopped(() -> fail("close already attempted"));
        assertTrue(lifetime.terminalCleanupFinished());
        assertEquals(1, closes.get());
    }

    @Test
    void rejectedOwnerCannotMakeCleanupTerminalBeforeOrAfterStopping() {
        AtomicBoolean owner = new AtomicBoolean(true);
        var lifetime = new ServerLifetime<>(new Object(), owner::get);
        AtomicInteger callbacks = new AtomicInteger();
        assertFalse(lifetime.terminalCleanupFinished());
        owner.set(false);
        assertThrows(IllegalStateException.class, () -> lifetime.stopped(callbacks::incrementAndGet));
        assertThrows(IllegalStateException.class,
                () -> lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet));
        owner.set(true);
        assertTrue(lifetime.active());
        assertFalse(lifetime.terminalCleanupFinished());
        assertEquals(0, callbacks.get());
        lifetime.stopping(callbacks::incrementAndGet, callbacks::incrementAndGet);
        assertFalse(lifetime.terminalCleanupFinished());
        owner.set(false);
        assertThrows(IllegalStateException.class, () -> lifetime.stopped(callbacks::incrementAndGet));
        owner.set(true);
        assertFalse(lifetime.terminalCleanupFinished());
        lifetime.stopped(() -> fail("close already attempted"));
        assertTrue(lifetime.terminalCleanupFinished());
        assertEquals(2, callbacks.get());
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError("test failure must be unchecked", failure);
    }
}
