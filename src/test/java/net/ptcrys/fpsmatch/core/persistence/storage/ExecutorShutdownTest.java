package net.ptcrys.fpsmatch.core.persistence.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorShutdownTest {

    @Test
    void acceptedSavesDrainBeforeFinalSaveAndNewOperationsAreRejected() {
        var executor = Executors.newSingleThreadExecutor();
        List<String> saves = new ArrayList<>();
        executor.execute(() -> saves.add("old"));
        assertTrue(ExecutorShutdown.drain(executor, 1000, 1000, message -> fail(message)));
        saves.add("final");
        assertEquals(List.of("old", "final"), saves);
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> saves.add("late")));
    }

    @Test
    void stuckWorkerReportsFailureWithoutThrowingAndBlockingOtherShutdownListeners() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> warnings = new ArrayList<>();
        executor.execute(() -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {}
            }
        });
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(ExecutorShutdown.drain(executor, 5, 5, warnings::add));
            assertFalse(executor.isTerminated());
            assertEquals(2, warnings.size());
        } finally {
            release.countDown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedShutdownPreservesInterruptStatusWithoutThrowing() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            assertFalse(ExecutorShutdown.drain(executor, 1000, 1000, message -> {}));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(executor.isShutdown());
        } finally {
            Thread.interrupted();
            release.countDown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }
}
