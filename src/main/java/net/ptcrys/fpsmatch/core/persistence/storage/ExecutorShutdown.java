package net.ptcrys.fpsmatch.core.persistence.storage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ExecutorShutdown {

    private ExecutorShutdown() {}

    public static boolean drain(ExecutorService executor, long timeoutMillis, long interruptTimeoutMillis, Consumer<String> report) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) return true;
            int discarded = executor.shutdownNow().size();
            report.accept("Shutdown timed out; interrupted active work and discarded " + discarded + " queued operations");
            if (executor.awaitTermination(interruptTimeoutMillis, TimeUnit.MILLISECONDS)) return true;
            report.accept("Worker did not terminate; storage must remain open until the worker exits");
            return false;
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            report.accept("Shutdown wait was interrupted; worker termination is not confirmed");
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
