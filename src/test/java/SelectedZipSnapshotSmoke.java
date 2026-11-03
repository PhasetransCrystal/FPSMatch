import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.ptcrys.fpsmatch.editor.core.packio.PackEntry;
import net.ptcrys.fpsmatch.editor.core.packio.PackIndex;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;

/** Java 17 public-JAR consumer of exact physical ZIP entry snapshots. */
public final class SelectedZipSnapshotSmoke {
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    private SelectedZipSnapshotSmoke() { }

    private record Manifest(List<PackEntry> lineOrder, List<PackEntry> sorted, long contentBytes) { }

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("capture")) {
            capture(Path.of(args[1]), args[2], Path.of(args[3]), Path.of(args[4]));
        } else if (args.length == 3 && args[0].equals("reopen")) {
            reopen(Path.of(args[1]), Path.of(args[2]));
        } else {
            throw new IllegalArgumentException("Usage: capture <source> <rawSha256> <manifest.tsv> "
                    + "<outputDirectory> | reopen <manifest.tsv> <snapshot.edpack>");
        }
    }

    private static void capture(Path source, String rawSha256, Path manifestPath, Path outputDirectory)
            throws Exception {
        requireHash(rawSha256, "rawSha256");
        Manifest manifest = readManifest(manifestPath);
        if (!Files.isDirectory(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("outputDirectory must already be a directory");
        }
        Path snapshot = outputDirectory.resolve("snapshot.edpack");
        Path reverse = outputDirectory.resolve("reverse.edpack");
        requireAbsent(snapshot);
        requireAbsent(reverse);

        Set<String> forwardPaths = new LinkedHashSet<>();
        for (PackEntry entry : manifest.lineOrder()) forwardPaths.add(entry.path());
        List<PackEntry> reversed = new ArrayList<>(manifest.lineOrder());
        Collections.reverse(reversed);
        Set<String> reversePaths = new LinkedHashSet<>();
        for (PackEntry entry : reversed) reversePaths.add(entry.path());

        PackIndex first = PackSnapshots.captureZipEntries(source, snapshot, rawSha256,
                forwardPaths, NEVER_CANCELLED);
        PackIndex firstRead = verifySnapshot(snapshot, manifest);
        require(first.equals(firstRead), "first returned index differs from independent reader");

        PackIndex second = PackSnapshots.captureZipEntries(source, reverse, rawSha256,
                reversePaths, NEVER_CANCELLED);
        PackIndex secondRead = verifySnapshot(reverse, manifest);
        require(second.equals(secondRead), "reverse returned index differs from independent reader");
        require(first.equals(second), "capture indexes differ with reverse selection order");
        // Hash both complete files independently, including their metadata.
        String firstSha256 = fileSha256(snapshot);
        String secondSha256 = fileSha256(reverse);
        require(firstSha256.equals(secondSha256), "complete snapshot bytes have different SHA-256");
        require(firstSha256.equals(first.sha256()), "complete snapshot digest differs from index");
        require(secondSha256.equals(second.sha256()), "complete reverse digest differs from index");
        printPass("capture", first, firstSha256);
    }

    private static void reopen(Path manifestPath, Path snapshot) throws Exception {
        Manifest manifest = readManifest(manifestPath);
        PackIndex index = verifySnapshot(snapshot, manifest);
        String sha256 = fileSha256(snapshot);
        require(sha256.equals(index.sha256()), "complete reopened snapshot digest differs from index");
        printPass("reopen", index, sha256);
    }

    private static Manifest readManifest(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalArgumentException("Manifest must not be empty");
        List<PackEntry> ordered = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        long total = 0;
        for (int i = 0; i < lines.size(); i++) {
            String[] columns = lines.get(i).split("\t", -1);
            if (columns.length != 3) {
                throw new IllegalArgumentException("Manifest line " + (i + 1) + " must have exactly 3 columns");
            }
            if (!columns[1].matches("[0-9]+")) {
                throw new IllegalArgumentException("Invalid decimal size at manifest line " + (i + 1));
            }
            long size;
            try {
                size = Long.parseLong(columns[1]);
            } catch (NumberFormatException invalidSize) {
                throw new IllegalArgumentException("Invalid decimal size at manifest line " + (i + 1), invalidSize);
            }
            // Public descriptors validate exact paths, per-entry sizes and lowercase hashes.
            PackEntry entry = new PackEntry(columns[0], size, columns[2]);
            if (!paths.add(entry.path())) {
                throw new IllegalArgumentException("Duplicate manifest path at line " + (i + 1));
            }
            ordered.add(entry);
            total = Math.addExact(total, size);
        }
        List<PackEntry> sorted = new ArrayList<>(ordered);
        sorted.sort(Comparator.comparing(PackEntry::path));
        // This placeholder is only for public aggregate validation, never an expected archive hash.
        PackIndex validated = new PackIndex(sorted, total, "0".repeat(64));
        return new Manifest(List.copyOf(ordered), validated.entries(), validated.contentBytes());
    }

    private static PackIndex verifySnapshot(Path path, Manifest manifest) throws Exception {
        PackIndex index;
        try (PackSnapshot reader = PackSnapshots.open(path, NEVER_CANCELLED)) {
            index = reader.index();
            require(index.entries().equals(manifest.sorted()), "snapshot paths, sizes or hashes differ from manifest");
            require(index.contentBytes() == manifest.contentBytes(), "snapshot contentBytes differs from manifest");
            long copiedBytes = 0;
            for (PackEntry expected : manifest.sorted()) {
                String actualSha256;
                long actualSize;
                try (HashSink sink = new HashSink(expected.size())) {
                    reader.copyEntry(expected.path(), sink, NEVER_CANCELLED);
                    actualSize = sink.count;
                    actualSha256 = HexFormat.of().formatHex(sink.digest.digest());
                }
                require(actualSize == expected.size(), "copied size differs for " + expected.path());
                require(actualSha256.equals(expected.sha256()), "copied SHA-256 differs for " + expected.path());
                copiedBytes = Math.addExact(copiedBytes, actualSize);
            }
            require(copiedBytes == index.contentBytes(), "copied total differs from contentBytes");
        }
        return index;
    }

    /** Retains only a digest and a byte count; no asset content is buffered here. */
    private static final class HashSink extends OutputStream {
        private final MessageDigest digest;
        private final long expectedSize;
        private long count;

        private HashSink(long expectedSize) throws Exception {
            this.digest = MessageDigest.getInstance("SHA-256");
            this.expectedSize = expectedSize;
        }

        @Override
        public void write(int value) throws IOException {
            checkLength(1);
            digest.update((byte) value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            checkLength(length);
            digest.update(bytes, offset, length);
            count += length;
        }

        private void checkLength(int length) throws IOException {
            if (length > expectedSize - count) throw new IOException("Copied entry exceeds manifest size");
        }
    }

    private static String fileSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read != 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireHash(String value, String name) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hexadecimal characters");
        }
    }

    private static void requireAbsent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing existing output: " + path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void printPass(String mode, PackIndex index, String snapshotSha256) {
        System.out.println("SELECTED_ZIP_SNAPSHOT_PASS mode=" + mode + " entries=" + index.entries().size()
                + " contentBytes=" + index.contentBytes() + " snapshotSha256=" + snapshotSha256);
    }
}
