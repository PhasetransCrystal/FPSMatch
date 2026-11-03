package net.ptcrys.fpsmatch.editor.core.storage;

import java.nio.file.Path;
import java.util.UUID;

/** Separate JVM used to prove process interruption and OS lock behavior. */
public final class CrashWorker {
    private CrashWorker() {
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[1]);
        if (args[0].equals("lock")) {
            try (LocalProjectStore store = LocalProjectStore.open(path)) {
                store.load();
                System.exit(3);
            } catch (ProjectStorageException failure) {
                System.exit(failure.code() == ProjectStorageException.Code.BUSY ? 31 : 4);
            }
            return;
        }
        LocalProjectStore.Stage stop = LocalProjectStore.Stage.valueOf(args[3]);
        LocalProjectStore.Checkpoint halt = stage -> {
            if (stage == stop) {
                Runtime.getRuntime().halt(73);
            }
        };
        if (args[0].equals("export")) {
            try (LocalProjectStore store = new LocalProjectStore(path, StoreLimits.DEFAULT, false, halt)) {
                store.exportSnapshot(Path.of(args[4]), store.captureSnapshot());
            }
            System.exit(5);
        }
        try (LocalProjectStore store = new LocalProjectStore(path, StoreLimits.DEFAULT, false, halt)) {
            SavedProject baseline = store.load();
            ProjectState state = baseline.state();
            ProjectState next = new ProjectState(state.projectId(), "next", state.modules(),
                    state.documents(), state.assets(), state.origin());
            store.commit(baseline.commitId(), UUID.fromString(args[2]), next);
        }
        System.exit(5);
    }
}
