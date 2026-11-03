import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** External consumer: compile with only the published storage JAR and its Gson dependency. */
public final class StorageSmoke {
    private StorageSmoke() {
    }

    public static void main(String[] arguments) throws Exception {
        Path sandbox = Files.createTempDirectory(Path.of(arguments[0]), "artifact-smoke-");
        Path project = sandbox.resolve("source");
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        byte[] original = {0, 1, (byte) 255, 7};
        SavedProject saved;
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            BlobRef document = store.stage(BlobRef.Kind.DOCUMENT, original);
            BlobRef asset = store.stage(BlobRef.Kind.ASSET, new byte[]{4, 3, 2, 1});
            ProjectState state = new ProjectState(projectId, "External consumer",
                    Map.of("example:module", "1.0.0"),
                    Map.of(documentId, new ProjectState.Entry(documentId, "example:module", "example:document",
                            "example:binary", 1, "Document", document, Set.of(), Set.of(assetId))),
                    Map.of(assetId, new ProjectState.Entry(assetId, "example:module", "example:asset",
                            "example:binary", 1, "Asset", asset, Set.of(), Set.of())), null);
            saved = store.commit(null, requestId, state).saved();
        }
        Path archive = sandbox.resolve("snapshot.zip");
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            require(saved.equals(store.load()), "save/reopen mismatch");
            store.exportSnapshot(archive);
        }
        SavedProject imported = LocalProjectStore.importSnapshot(archive, sandbox.resolve("copy"));
        require(imported.state().documents().equals(saved.state().documents()), "document identity changed");
        require(imported.state().assets().equals(saved.state().assets()), "asset identity changed");
        require(imported.state().origin().commitId().equals(saved.commitId()), "origin mismatch");
        try (LocalProjectStore source = LocalProjectStore.open(project);
             LocalProjectStore copy = LocalProjectStore.open(sandbox.resolve("copy"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copy.copyContent(imported.state().documents().get(documentId).content(), output);
            require(Arrays.equals(original, output.toByteArray()), "content bytes changed");
            ProjectState state = saved.state();
            source.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(projectId, "Source changed",
                    state.modules(), state.documents(), state.assets(), state.origin()));
            require(copy.load().equals(imported), "independent copy changed with source");
        }
        System.out.println("External storage JAR save/reopen/share PASS: " + sandbox);
    }

    private static void require(boolean condition, String diagnostic) {
        if (!condition) {
            throw new AssertionError(diagnostic);
        }
    }
}
