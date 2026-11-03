package net.ptcrys.fpsmatch.editor.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only access to the existing storage checkpoint; never included in public runtime jars. */
public final class RuntimeFaultStores {
    private RuntimeFaultStores() { }

    public static LocalProjectStore loseReceiptAfterPublish(Path directory) throws IOException {
        return new LocalProjectStore(directory, StoreLimits.DEFAULT, false, stage -> {
            if (stage == LocalProjectStore.Stage.AFTER_PUBLISH) throw new IOException("injected lost receipt");
        });
    }

    public static LocalProjectStore pauseBeforePublish(Path directory, CountDownLatch entered,
            CountDownLatch release) throws IOException {
        return new LocalProjectStore(directory, StoreLimits.DEFAULT, false, stage -> {
            if (stage == LocalProjectStore.Stage.BEFORE_PUBLISH) {
                entered.countDown();
                try {
                    // A regression that blocks the owner on the access monitor cannot block this
                    // storage checkpoint forever: timeout frees the worker and exposes the failure.
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test publication barrier timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test publication barrier interrupted", interrupted);
                }
            }
        });
    }
}
