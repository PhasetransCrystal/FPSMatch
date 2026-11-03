package net.ptcrys.fpsmatch.editor.core.examples;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import net.ptcrys.fpsmatch.editor.core.packio.PackEntry;
import net.ptcrys.fpsmatch.editor.core.packio.PackIndex;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

/** Public-JAR physical-byte consumer. No gameplay asset producer or domain closure claim. */
public final class PackSnapshotConsumer {
    private PackSnapshotConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("capture")) {
            capture(Path.of(args[1]), List.of(Path.of(args[2]), Path.of(args[3]), Path.of(args[4])));
        } else if (args.length == 3 && args[0].equals("reopen")) {
            reopen(Path.of(args[1]), Path.of(args[2]));
        } else {
            throw new IllegalArgumentException("capture OUT DIR DIR ZIP | reopen SHARE NEW_DIRECTORY");
        }
    }

    private static void capture(Path output, List<Path> sources) throws Exception {
        Files.createDirectory(output);
        Path snapshots = Files.createDirectory(output.resolve("snapshots"));
        List<String> report = new ArrayList<>();
        report.add("label\tfiles\tcontentBytes\tarchiveBytes\tsha256\tcaptureNanos\tverifyNanos");
        Map<UUID, ProjectState.Entry> assets = new TreeMap<>();
        List<String> labels = List.of("default", "mcs", "gz");
        try (LocalProjectStore store = LocalProjectStore.create(output.resolve("project"))) {
            for (int i = 0; i < sources.size(); i++) {
                String label = labels.get(i);
                Path archive = snapshots.resolve(label + ".edpack");
                long start = System.nanoTime();
                PackIndex captured = i == 2 ? PackSnapshots.captureZip(sources.get(i), archive, () -> false)
                        : PackSnapshots.captureDirectory(sources.get(i), archive, () -> false);
                long elapsed = System.nanoTime() - start;
                start = System.nanoTime();
                try (PackSnapshot reader = PackSnapshots.open(archive, () -> false)) {
                    require(captured.equals(reader.index()), "returned index differs from reopened archive");
                    verifyEveryEntry(reader);
                }
                long verified = System.nanoTime() - start;
                BlobRef blob;
                try (InputStream input = Files.newInputStream(archive)) {
                    blob = store.stage(BlobRef.Kind.ASSET, input);
                }
                require(blob.sha256().equals(captured.sha256()), "storage blob digest differs");
                UUID id = UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
                // Example-only ownership: this low-level store does not certify a gameplay format.
                assets.put(id, new ProjectState.Entry(id, "example:physical_packs", "example:pack_snapshot",
                        "example:edpack", 1, label, blob, Set.of(), Set.of()));
                report.add(label + "\t" + captured.entries().size() + "\t" + captured.contentBytes() + "\t"
                        + blob.size() + "\t" + captured.sha256() + "\t" + elapsed + "\t" + verified);
            }
            ProjectState state = new ProjectState(UUID.randomUUID(), "Q42 physical source bytes",
                    Map.of("example:physical_packs", "1"), Map.of(), assets, null);
            store.commit(null, UUID.randomUUID(), state);
            store.exportSnapshot(output.resolve("physical-packs.zip"), store.captureSnapshot());
        }
        Files.write(output.resolve("capture.tsv"), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("PACK_CAPTURE_AND_STORAGE_SHARE_PASS packs=" + assets.size()
                + " physical-bytes-only; no weapon producer/closure/runtime claim");
    }

    private static void reopen(Path share, Path copy) throws Exception {
        LocalProjectStore.importSnapshot(share, copy);
        Path verification = Files.createDirectory(copy.resolve("verified-archives"));
        long files = 0;
        long bytes = 0;
        List<String> report = new ArrayList<>();
        report.add("label\tfiles\tcontentBytes\tarchiveBytes\tsha256");
        try (LocalProjectStore store = LocalProjectStore.open(copy)) {
            ProjectState state = store.load().state();
            require(state.assets().size() == 3 && state.documents().isEmpty(), "unexpected physical project");
            for (ProjectState.Entry entry : state.assets().values()) {
                Path archive = verification.resolve(entry.id() + ".edpack");
                try (OutputStream target = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
                    store.copyContent(entry.content(), target);
                }
                try (PackSnapshot reader = PackSnapshots.open(archive, () -> false)) {
                    require(reader.index().sha256().equals(entry.content().sha256()), "reopened digest differs");
                    verifyEveryEntry(reader);
                    files += reader.index().entries().size();
                    bytes += reader.index().contentBytes();
                    report.add(entry.displayName() + "\t" + reader.index().entries().size() + "\t"
                            + reader.index().contentBytes() + "\t" + entry.content().size() + "\t"
                            + reader.index().sha256());
                }
            }
        }
        Files.write(copy.resolve("reopen.tsv"), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("PACK_FRESH_PROCESS_REOPEN_PASS packs=3 files=" + files + " bytes=" + bytes
                + " no source path argument; same-machine independent directory; physical-bytes-only");
    }

    private static void verifyEveryEntry(PackSnapshot reader) throws Exception {
        for (PackEntry entry : reader.index().entries()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestOutputStream target = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                reader.copyEntry(entry.path(), target, () -> false);
            }
            require(HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()), "entry digest differs");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) { throw new IllegalStateException(message); }
    }
}
