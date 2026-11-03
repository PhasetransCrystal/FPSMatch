package net.ptcrys.fpsmatch.editor.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Test-only bridge into the storage commit checkpoint of {@link LocalProjectStore}. Storage keeps its
 * checkpoint as a package-private collaborator, so a test source file in this package can reopen a
 * real project directory with a fault injected at {@link LocalProjectStore.Stage#AFTER_PUBLISH}:
 * the new head has already been replaced, but the receipt never reaches the caller.
 *
 * <p>This class is part of the test source set only and is never packaged into a production
 * artifact. Storage is not modified to make the fault reachable.
 */
public final class PublicationFaultStores {
    private PublicationFaultStores() { }

    /** Creates an empty real store whose first publication loses its reply. */
    public static LocalProjectStore createUncertainAfterPublish(Path directory) throws IOException {
        java.nio.file.Files.createDirectory(directory);
        return new LocalProjectStore(directory, StoreLimits.DEFAULT, true, stage -> {
            if (stage == LocalProjectStore.Stage.AFTER_PUBLISH) throw new IOException("injected lost creation receipt");
        });
    }

    /** Reopens a real project so that the checked failure fires after the head was replaced. */
    public static LocalProjectStore uncertainAfterPublish(Path directory, IOException failure)
            throws IOException {
        Objects.requireNonNull(failure, "failure");
        return new LocalProjectStore(directory, StoreLimits.DEFAULT, false, stage -> {
            if (stage == LocalProjectStore.Stage.AFTER_PUBLISH) {
                throw failure;
            }
        });
    }

    /** Reopens a real project so that the unchecked failure fires after the head was replaced. */
    public static LocalProjectStore crashAfterPublish(Path directory, RuntimeException failure)
            throws IOException {
        Objects.requireNonNull(failure, "failure");
        return new LocalProjectStore(directory, StoreLimits.DEFAULT, false, stage -> {
            if (stage == LocalProjectStore.Stage.AFTER_PUBLISH) {
                throw failure;
            }
        });
    }
}
