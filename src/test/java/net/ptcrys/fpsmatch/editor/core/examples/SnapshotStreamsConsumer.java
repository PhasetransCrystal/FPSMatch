package net.ptcrys.fpsmatch.editor.core.examples;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.ptcrys.fpsmatch.editor.core.packio.PackEntry;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;

/** Public-artifact, bounded-memory consumer; no game, original sources or asset decoders. */
public final class SnapshotStreamsConsumer {
    private SnapshotStreamsConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("output.tsv default.edpack mcs.edpack gz.edpack");
        long bytes = 0;
        int entries = 0;
        long largest = 0;
        byte[] buffer = new byte[32749];
        String[] labels = {"default", "mcs", "gz"};
        try (BufferedWriter output = Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            output.write("layer\tarchiveSha256\tpath\tsize\tsha256\n");
            for (int layer = 0; layer < labels.length; layer++) {
                try (PackSnapshot snapshot = PackSnapshots.open(Path.of(args[layer + 1]), () -> false)) {
                    for (PackEntry entry : snapshot.index().entries()) {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        long length = 0;
                        try (InputStream stream = snapshot.openEntry(entry.path(), () -> false)) {
                            int read;
                            while ((read = stream.read(buffer)) != -1) {
                                if (read <= 0) throw new AssertionError("nonpositive read");
                                length += read;
                                digest.update(buffer, 0, read);
                            }
                        }
                        String actual = HexFormat.of().formatHex(digest.digest());
                        if (length != entry.size() || !actual.equals(entry.sha256())) {
                            throw new AssertionError("stream differs from index: " + entry.path());
                        }
                        output.write(labels[layer] + "\t" + snapshot.index().sha256() + "\t" + entry.path()
                                + "\t" + length + "\t" + actual + "\n");
                        entries++;
                        bytes += length;
                        largest = Math.max(largest, length);
                    }
                }
            }
        }
        if (entries != 4064 || bytes != 439077198L || largest != 82410554L) {
            throw new AssertionError("unexpected real fixture set");
        }
        System.out.println("SNAPSHOT_STREAMS_PASS entries=" + entries + " bytes=" + bytes + " largest=" + largest
                + "; physical streaming only; no host/weapon/RC approval");
    }
}
