package net.ptcrys.fpsmatch.editor.core.packio;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static net.ptcrys.fpsmatch.editor.core.packio.PackSnapshotException.Code.*;

/** Bounded, synchronous snapshot I/O. Call from the caller's I/O execution domain. */
public final class PackSnapshots {

    private PackSnapshots() {}

    private record FileState(PackEntry entry, BasicFileAttributes attributes) {}

    private record OriginalContainer(BasicFileAttributes attributes, String sha256) {}

    private record Scan(List<FileState> files, BasicFileAttributes root) {

        List<PackEntry> entries() {
            return files.stream().map(FileState::entry).toList();
        }
    }

    private static final class Budget {

        long bytes;
        long metadata = 24;
        int count;
        int nodes;

        void node() throws IOException {
            if (++nodes > PackIo.NODE_LIMIT) throw PackIo.fail(BUDGET, "Traversal node budget exceeded");
        }

        void add(String path, long size) throws IOException {
            if (++count > PackIo.COUNT_LIMIT || size < 0 || size > PackIo.LIMIT) throw PackIo.fail(BUDGET, "File budget exceeded");
            bytes += size;
            metadata += 44L + PackIo.pathBytes(path).length;
            if (metadata > PackIo.META_LIMIT || bytes > PackIo.LIMIT || metadata + bytes > PackIo.LIMIT) {
                throw PackIo.fail(BUDGET, "Archive budget exceeded");
            }
        }
    }

    public static PackIndex captureDirectory(Path source, Path target, BooleanSupplier cancelled) throws IOException {
        return capture(source, target, cancelled, false);
    }

    public static PackIndex captureZip(Path source, Path target, BooleanSupplier cancelled) throws IOException {
        return capture(source, target, cancelled, true);
    }

    /**
     * Captures exact file paths from a ZIP whose complete raw bytes match the supplied SHA-256.
     * The selection is copied before I/O; every ZIP path and structural budget is validated,
     * while only selected files are decompressed. The returned digest identifies the EDPACK01 output.
     */
    public static PackIndex captureZipEntries(Path source, Path target, String expectedContainerSha256,
                                              Set<String> paths, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expectedContainerSha256, "expectedContainerSha256");
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(cancelled, "cancelled");
        PackIo.validateHash(expectedContainerSha256);
        if (paths.isEmpty() || paths.size() > PackIo.COUNT_LIMIT) {
            throw new IllegalArgumentException("Invalid selected path count");
        }
        Set<String> selection = new HashSet<>();
        Set<String> folded = new HashSet<>();
        for (String path : paths) {
            Objects.requireNonNull(path, "path");
            PackIo.validatePath(path);
            if (selection.size() >= PackIo.COUNT_LIMIT) throw new IllegalArgumentException("Invalid selected path count");
            selection.add(path);
            try {
                PackIo.addPath(folded, path);
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        if (selection.isEmpty()) throw new IllegalArgumentException("Invalid selected path count");
        try {
            PackIo.prefixes(folded);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return capture(source, target, cancelled, true, expectedContainerSha256, Set.copyOf(selection));
    }

    private static PackIndex capture(Path source, Path target, BooleanSupplier cancelled, boolean zip) throws IOException {
        return capture(source, target, cancelled, zip, null, null);
    }

    private static PackIndex capture(Path source, Path target, BooleanSupplier cancelled, boolean zip,
                                     String expectedContainerSha256, Set<String> selection) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cancelled, "cancelled");
        source = source.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        PackIo.cancel(cancelled);
        validateSource(source, zip);
        validateTarget(target);
        if (!zip && target.startsWith(source)) throw PackIo.fail(INVALID_SOURCE, "Target is inside source");
        BasicFileAttributes originalBaseline = zip ? PackIo.attributes(source) : null;
        Path temp = null;
        Path zipTemp = null;
        Throwable failure = null;
        boolean published = false;
        try {
            Path captureSource = source;
            OriginalContainer original = null;
            if (zip) {
                PackIo.cancel(cancelled);
                zipTemp = Files.createTempFile(target.getParent(), ".edzip-", ".pending");
                try (FileChannel channel = FileChannel.open(zipTemp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        original = readOriginalContainer(source, Channels.newOutputStream(channel),
                                new OriginalContainer(originalBaseline, null), cancelled);
                    } catch (IOException e) {
                        throw afterFreeze(e);
                    }
                }
                if (expectedContainerSha256 != null && !expectedContainerSha256.equals(original.sha256())) {
                    throw PackIo.fail(CONTENT_MISMATCH, "ZIP container digest mismatch");
                }
                captureSource = zipTemp;
            }
            // ZIP parsers only receive this operation's fixed, private container.
            Scan frozen = scan(captureSource, zip, selection, cancelled);
            PackIo.cancel(cancelled);
            temp = Files.createTempFile(target.getParent(), ".edpack-", ".pending");
            MessageDigest archiveDigest = PackIo.digest();
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    DataOutputStream output = new DataOutputStream(new DigestOutputStream(Channels.newOutputStream(channel), archiveDigest))) {
                writeMetadata(output, frozen.entries(), cancelled);
                try {
                    if (zip) copyZip(captureSource, frozen, output, selection, cancelled);
                    else copyDirectory(source, frozen, output, cancelled);
                } catch (IOException e) {
                    throw afterFreeze(e);
                }
            }
            Scan verified;
            try {
                verified = scan(captureSource, zip, selection, cancelled);
            } catch (IOException e) {
                throw afterFreeze(e);
            }
            compare(frozen, verified);
            if (zip) {
                try {
                    readOriginalContainer(source, null, original, cancelled);
                } catch (IOException e) {
                    throw afterFreeze(e);
                }
            }
            try {
                validateSource(source, zip);
            } catch (IOException e) {
                throw afterFreeze(e);
            }
            validateTarget(target);
            PackIo.cancel(cancelled);
            Files.createLink(target, temp);
            published = true;
            // The link is the publication point. Cancellation after it cannot undo publication.
            long total = frozen.files().stream().mapToLong(f -> f.entry().size()).sum();
            return new PackIndex(frozen.entries(), total, PackIo.hex(archiveDigest.digest()));
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            IOException cleanup = cleanupTemp(temp, null);
            cleanup = cleanupTemp(zipTemp, cleanup);
            if (cleanup != null) {
                IOException reported = new IOException("Temporary cleanup failed; target published=" + published + ": " + target, cleanup);
                if (failure != null) failure.addSuppressed(reported);
                else throw reported;
            }
        }
    }

    private static IOException cleanupTemp(Path temp, IOException previous) {
        if (temp != null) {
            try {
                Files.delete(temp);
            } catch (IOException cleanup) {
                if (previous == null) return cleanup;
                previous.addSuppressed(cleanup);
            }
        }
        return previous;
    }

    private static OriginalContainer readOriginalContainer(Path source, OutputStream output,
                                                           OriginalContainer expected, BooleanSupplier cancelled) throws IOException {
        PackIo.cancel(cancelled);
        validateSource(source, true);
        BasicFileAttributes before = PackIo.attributes(source);
        if (!before.isRegularFile()) throw PackIo.fail(SOURCE_CHANGED, "Original ZIP type changed");
        if (expected != null && !PackIo.same(expected.attributes(), before)) {
            throw PackIo.fail(SOURCE_CHANGED, "Original ZIP attributes changed");
        }
        if (before.size() > PackIo.LIMIT) throw PackIo.fail(BUDGET, "ZIP container budget exceeded");
        String digest;
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != before.size()) throw PackIo.fail(SOURCE_CHANGED, "Original ZIP length changed");
            digest = transfer(Channels.newInputStream(channel), output, before.size(), -1, cancelled);
            if (channel.size() != before.size()) throw PackIo.fail(SOURCE_CHANGED, "Original ZIP length changed");
        } catch (IOException readFailure) {
            if (readFailure instanceof PackSnapshotException) throw readFailure;
            try {
                validateSource(source, true);
            } catch (IOException identityFailure) {
                IOException changed = afterFreeze(identityFailure);
                if (changed instanceof PackSnapshotException snapshot && snapshot.code() == SOURCE_CHANGED) {
                    changed.addSuppressed(readFailure);
                    throw changed;
                }
                readFailure.addSuppressed(identityFailure);
            }
            throw readFailure;
        }
        validateSource(source, true);
        BasicFileAttributes after = PackIo.attributes(source);
        if (!PackIo.same(before, after) || expected != null && expected.sha256() != null && !expected.sha256().equals(digest)) {
            throw PackIo.fail(SOURCE_CHANGED, "Original ZIP changed");
        }
        return new OriginalContainer(after, digest);
    }

    private static IOException afterFreeze(IOException error) {
        if (error instanceof NoSuchFileException || error instanceof PackSnapshotException snapshot && snapshot.code() == INVALID_SOURCE) {
            return new PackSnapshotException(SOURCE_CHANGED, "Source identity changed", error);
        }
        return error;
    }

    private static void validateSource(Path source, boolean zip) throws IOException {
        if (source.getParent() != null) PackIo.ancestors(source.getParent());
        BasicFileAttributes attrs;
        try {
            attrs = PackIo.attributes(source);
        } catch (NoSuchFileException e) {
            throw new PackSnapshotException(INVALID_SOURCE, "Missing source", e);
        }
        if (zip ? !attrs.isRegularFile() : !attrs.isDirectory()) throw PackIo.fail(INVALID_SOURCE, "Invalid source kind");
    }

    private static void validateTarget(Path target) throws IOException {
        if (target.getParent() == null) throw PackIo.fail(INVALID_SOURCE, "Missing target parent");
        PackIo.ancestors(target.getParent());
        try {
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return;
        }
        throw new java.nio.file.FileAlreadyExistsException(target.toString());
    }

    private static Scan scan(Path source, boolean zip, Set<String> selection, BooleanSupplier cancelled) throws IOException {
        validateSource(source, zip);
        BasicFileAttributes before = PackIo.attributes(source);
        List<FileState> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Budget budget = new Budget();
        if (zip) {
            preflightZip(source, cancelled);
            try (ZipFile archive = openZip(source)) {
                List<ZipEntry> entries = selectedZipEntries(archive, selection, cancelled);
                for (ZipEntry entry : entries) {
                    PackIo.cancel(cancelled);
                    budget.add(entry.getName(), entry.getSize());
                    try (InputStream input = archive.getInputStream(entry)) {
                        String digest = transfer(input, null, entry.getSize(), entry.getCrc(), cancelled);
                        files.add(new FileState(new PackEntry(entry.getName(), entry.getSize(), digest), null));
                    }
                }
            } catch (ZipException | IllegalArgumentException e) {
                // In selected captures, callback failures retain their original identity.
                if (selection != null && e instanceof IllegalArgumentException runtime) throw runtime;
                throw new PackSnapshotException(INVALID_ARCHIVE, "Invalid ZIP", e);
            }
        } else {
            budget.node(); // Include the root, including an empty root.
            scanDirectory(source, source, files, names, budget, cancelled);
        }
        PackIo.cancel(cancelled);
        BasicFileAttributes after = PackIo.attributes(source);
        if (!PackIo.same(before, after)) throw PackIo.fail(SOURCE_CHANGED, "Source changed during scan");
        if (files.isEmpty()) throw PackIo.fail(INVALID_SOURCE, "Empty source");
        files.sort(Comparator.comparing(f -> f.entry().path()));
        return new Scan(List.copyOf(files), after);
    }

    private static void scanDirectory(Path root, Path directory, List<FileState> files, Set<String> names,
                                      Budget budget, BooleanSupplier cancelled) throws IOException {
        PackIo.cancel(cancelled);
        BasicFileAttributes before = PackIo.attributes(directory);
        if (!before.isDirectory()) throw PackIo.fail(SOURCE_CHANGED, "Directory changed");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                PackIo.cancel(cancelled);
                budget.node();
                String name = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                PackIo.inputPath(name);
                BasicFileAttributes attrs = PackIo.attributes(path);
                if (attrs.isDirectory()) {
                    scanDirectory(root, path, files, names, budget, cancelled);
                } else if (attrs.isRegularFile()) {
                    PackIo.addPath(names, name);
                    budget.add(name, attrs.size());
                    String digest;
                    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        digest = transfer(Channels.newInputStream(channel), null, attrs.size(), -1, cancelled);
                    }
                    if (!PackIo.same(attrs, PackIo.attributes(path))) throw PackIo.fail(SOURCE_CHANGED, "File changed during scan");
                    files.add(new FileState(new PackEntry(name, attrs.size(), digest), attrs));
                } else throw PackIo.fail(INVALID_SOURCE, "Unsupported source node");
            }
        } catch (java.nio.file.DirectoryIteratorException e) {
            throw e.getCause();
        }
        if (!PackIo.same(before, PackIo.attributes(directory))) throw PackIo.fail(SOURCE_CHANGED, "Directory changed during scan");
        // Prefixes are checked once at the root, after all files are known.
        if (directory.equals(root)) PackIo.prefixes(names);
    }

    private static List<ZipEntry> zipEntries(ZipFile archive, BooleanSupplier cancelled) throws IOException {
        List<ZipEntry> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long metadata = 24;
        int nodeCount = 0;
        Enumeration<? extends ZipEntry> entries = archive.entries();
        while (entries.hasMoreElements()) {
            PackIo.cancel(cancelled);
            if (++nodeCount > PackIo.NODE_LIMIT) throw PackIo.fail(BUDGET, "ZIP entry budget exceeded");
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
            PackIo.inputPath(name);
            if (entry.isDirectory()) continue;
            PackIo.addPath(names, name);
            if (entry.getSize() < 0 || entry.getCrc() < 0) throw PackIo.fail(INVALID_ARCHIVE, "Missing ZIP size or CRC");
            metadata += 44L + PackIo.pathBytes(name).length;
            if (files.size() >= PackIo.COUNT_LIMIT || metadata > PackIo.META_LIMIT) throw PackIo.fail(BUDGET, "ZIP metadata budget exceeded");
            files.add(entry);
        }
        PackIo.prefixes(names);
        files.sort(Comparator.comparing(ZipEntry::getName));
        return files;
    }

    private static ZipFile openZip(Path source) throws IOException {
        try {
            return new ZipFile(source.toFile());
        } catch (IllegalArgumentException e) {
            throw new PackSnapshotException(INVALID_ARCHIVE, "Invalid ZIP", e);
        }
    }

    private static List<ZipEntry> selectedZipEntries(ZipFile archive, Set<String> selection,
                                                     BooleanSupplier cancelled) throws IOException {
        // Validate the complete ZIP before filtering; unselected files keep every structural budget.
        List<ZipEntry> entries = zipEntries(archive, cancelled);
        if (selection == null) return entries;
        List<ZipEntry> selected = new ArrayList<>();
        for (ZipEntry entry : entries) {
            PackIo.cancel(cancelled);
            if (selection.contains(entry.getName())) selected.add(entry);
        }
        if (selected.size() != selection.size()) throw PackIo.fail(INVALID_SOURCE, "Missing selected ZIP file");
        return selected;
    }

    /** Bounds central-directory allocation before the JDK ZIP implementation sees it. */
    private static void preflightZip(Path source, BooleanSupplier cancelled) throws IOException {
        PackIo.cancel(cancelled);
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = channel.size();
            if (length > PackIo.LIMIT) throw PackIo.fail(BUDGET, "ZIP container budget exceeded");
            if (length < 22) throw PackIo.fail(INVALID_ARCHIVE, "Missing ZIP end record");
            int tailLength = (int) Math.min(length, 65557L);
            // This small metadata window is separate from the 64KiB content transfer buffer.
            ByteBuffer tail = ByteBuffer.allocate(tailLength).order(ByteOrder.LITTLE_ENDIAN);
            readAt(channel, tail, length - tailLength, cancelled);
            int end = -1;
            for (int i = tailLength - 22; i >= 0; i--) {
                PackIo.cancel(cancelled);
                if (tail.getInt(i) == 0x06054b50 && i + 22 + Short.toUnsignedInt(tail.getShort(i + 20)) == tailLength) {
                    end = i;
                    break;
                }
            }
            if (end < 0) throw PackIo.fail(INVALID_ARCHIVE, "Missing ZIP end record");
            int disk = Short.toUnsignedInt(tail.getShort(end + 4));
            int centralDisk = Short.toUnsignedInt(tail.getShort(end + 6));
            int diskCount = Short.toUnsignedInt(tail.getShort(end + 8));
            int count = Short.toUnsignedInt(tail.getShort(end + 10));
            long size = Integer.toUnsignedLong(tail.getInt(end + 12));
            long offset = Integer.toUnsignedLong(tail.getInt(end + 16));
            if (disk == 0xffff || centralDisk == 0xffff || count == 0xffff || diskCount == 0xffff || size == 0xffffffffL || offset == 0xffffffffL) {
                throw PackIo.fail(INVALID_ARCHIVE, "ZIP64 is unsupported");
            }
            if (disk != 0 || centralDisk != 0 || diskCount != count) throw PackIo.fail(INVALID_ARCHIVE, "Multidisk ZIP is unsupported");
            if (count > PackIo.NODE_LIMIT || size > PackIo.META_LIMIT || offset > PackIo.LIMIT || offset + size > PackIo.LIMIT) {
                throw PackIo.fail(BUDGET, "ZIP central directory budget exceeded");
            }
            long centralEnd = length - tailLength + end;
            if (offset + size != centralEnd) throw PackIo.fail(INVALID_ARCHIVE, "ZIP central directory bounds mismatch");
            long cursor = offset;
            ByteBuffer fixed = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                PackIo.cancel(cancelled);
                if (centralEnd - cursor < 46) throw PackIo.fail(INVALID_ARCHIVE, "Truncated ZIP central record");
                fixed.clear();
                readAt(channel, fixed, cursor, cancelled);
                if (fixed.getInt(0) != 0x02014b50) throw PackIo.fail(INVALID_ARCHIVE, "Invalid ZIP central record");
                int flags = Short.toUnsignedInt(fixed.getShort(8));
                int nameLength = Short.toUnsignedInt(fixed.getShort(28));
                int extraLength = Short.toUnsignedInt(fixed.getShort(30));
                int commentLength = Short.toUnsignedInt(fixed.getShort(32));
                int diskStart = Short.toUnsignedInt(fixed.getShort(34));
                long compressed = Integer.toUnsignedLong(fixed.getInt(20));
                long uncompressed = Integer.toUnsignedLong(fixed.getInt(24));
                long localOffset = Integer.toUnsignedLong(fixed.getInt(42));
                if ((flags & 1) != 0 || diskStart != 0) throw PackIo.fail(INVALID_ARCHIVE, "Encrypted or multidisk ZIP is unsupported");
                if (compressed == 0xffffffffL || uncompressed == 0xffffffffL || localOffset == 0xffffffffL) {
                    throw PackIo.fail(INVALID_ARCHIVE, "ZIP64 is unsupported");
                }
                if (nameLength < 1) throw PackIo.fail(INVALID_ARCHIVE, "Empty ZIP path");
                if (nameLength > PackIo.PATH_LIMIT) throw PackIo.fail(BUDGET, "ZIP path byte budget exceeded");
                if (compressed > PackIo.LIMIT || uncompressed > PackIo.LIMIT || localOffset > PackIo.LIMIT) {
                    throw PackIo.fail(BUDGET, "ZIP entry size or offset budget exceeded");
                }
                long next = cursor + 46L + nameLength + extraLength + commentLength;
                if (next > centralEnd) throw PackIo.fail(INVALID_ARCHIVE, "ZIP central record exceeds directory");
                cursor = next;
            }
            if (cursor != centralEnd || channel.size() != length) throw PackIo.fail(INVALID_ARCHIVE, "ZIP central directory count mismatch");
        }
    }

    private static void readAt(FileChannel channel, ByteBuffer buffer, long offset, BooleanSupplier cancelled) throws IOException {
        while (buffer.hasRemaining()) {
            PackIo.cancel(cancelled);
            int oldPosition = buffer.position();
            int oldLimit = buffer.limit();
            buffer.limit((int) Math.min(oldLimit, (long) oldPosition + PackIo.BUFFER));
            int read;
            try {
                read = channel.read(buffer, offset + oldPosition);
            } finally {
                buffer.limit(oldLimit);
            }
            if (read < 0) throw PackIo.fail(INVALID_ARCHIVE, "Truncated ZIP metadata");
        }
    }

    private static String transfer(InputStream input, OutputStream output, long expected, long crc,
                                   BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = PackIo.digest();
        CRC32 checksum = new CRC32();
        byte[] buffer = new byte[PackIo.BUFFER];
        long remaining = expected;
        while (remaining > 0) {
            PackIo.cancel(cancelled);
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw PackIo.fail(crc >= 0 ? INVALID_ARCHIVE : SOURCE_CHANGED, "Source was truncated");
            if (read == 0) continue;
            digest.update(buffer, 0, read);
            checksum.update(buffer, 0, read);
            if (output != null) output.write(buffer, 0, read);
            remaining -= read;
        }
        PackIo.cancel(cancelled);
        if (input.read() != -1) throw PackIo.fail(crc >= 0 ? INVALID_ARCHIVE : SOURCE_CHANGED, "Source length changed");
        if (crc >= 0 && checksum.getValue() != crc) throw PackIo.fail(INVALID_ARCHIVE, "ZIP CRC mismatch");
        return PackIo.hex(digest.digest());
    }

    private static void copyDirectory(Path source, Scan frozen, OutputStream output, BooleanSupplier cancelled) throws IOException {
        if (!PackIo.same(frozen.root(), PackIo.attributes(source))) throw PackIo.fail(SOURCE_CHANGED, "Source root changed");
        for (FileState state : frozen.files()) {
            PackIo.cancel(cancelled);
            Path path = source.resolve(state.entry().path());
            PackIo.ancestors(path.getParent());
            BasicFileAttributes before = PackIo.attributes(path);
            if (!PackIo.same(state.attributes(), before)) throw PackIo.fail(SOURCE_CHANGED, "File attributes changed");
            String digest;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                digest = transfer(Channels.newInputStream(channel), output, state.entry().size(), -1, cancelled);
            }
            if (!digest.equals(state.entry().sha256()) || !PackIo.same(before, PackIo.attributes(path))) {
                throw PackIo.fail(SOURCE_CHANGED, "File changed during capture");
            }
        }
    }

    private static void copyZip(Path source, Scan frozen, OutputStream output, Set<String> selection,
                                BooleanSupplier cancelled) throws IOException {
        validateSource(source, true);
        if (!PackIo.same(frozen.root(), PackIo.attributes(source))) throw PackIo.fail(SOURCE_CHANGED, "ZIP changed");
        preflightZip(source, cancelled);
        try (ZipFile archive = openZip(source)) {
            List<ZipEntry> entries = selectedZipEntries(archive, selection, cancelled);
            if (entries.size() != frozen.files().size()) throw PackIo.fail(SOURCE_CHANGED, "ZIP set changed");
            for (int i = 0; i < entries.size(); i++) {
                PackIo.cancel(cancelled);
                ZipEntry entry = entries.get(i);
                PackEntry expected = frozen.files().get(i).entry();
                if (!entry.getName().equals(expected.path()) || entry.getSize() != expected.size()) throw PackIo.fail(SOURCE_CHANGED, "ZIP entry changed");
                try (InputStream input = archive.getInputStream(entry)) {
                    if (!transfer(input, output, expected.size(), entry.getCrc(), cancelled).equals(expected.sha256())) {
                        throw PackIo.fail(SOURCE_CHANGED, "ZIP content changed");
                    }
                }
            }
        } catch (ZipException | IllegalArgumentException e) {
            if (selection != null && e instanceof IllegalArgumentException runtime) throw runtime;
            throw new PackSnapshotException(INVALID_ARCHIVE, "Invalid ZIP", e);
        }
        if (!PackIo.same(frozen.root(), PackIo.attributes(source))) throw PackIo.fail(SOURCE_CHANGED, "ZIP changed during copy");
    }

    private static void compare(Scan expected, Scan actual) throws IOException {
        if (!expected.entries().equals(actual.entries()) || !PackIo.same(expected.root(), actual.root())) {
            throw PackIo.fail(SOURCE_CHANGED, "Source set or content changed");
        }
        for (int i = 0; i < expected.files().size(); i++) {
            BasicFileAttributes a = expected.files().get(i).attributes();
            BasicFileAttributes b = actual.files().get(i).attributes();
            if (a != null && !PackIo.same(a, b)) throw PackIo.fail(SOURCE_CHANGED, "Source attributes changed");
        }
    }

    private static void writeMetadata(DataOutputStream output, List<PackEntry> entries, BooleanSupplier cancelled) throws IOException {
        PackIo.cancel(cancelled);
        output.write(PackIo.MAGIC);
        output.writeInt(1);
        output.writeInt(entries.size());
        output.writeLong(entries.stream().mapToLong(PackEntry::size).sum());
        for (PackEntry entry : entries) {
            PackIo.cancel(cancelled);
            byte[] path = PackIo.pathBytes(entry.path());
            output.writeInt(path.length);
            output.write(path);
            output.writeLong(entry.size());
            output.write(HexFormat.of().parseHex(entry.sha256()));
        }
    }

    public static PackSnapshot open(Path archive, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(cancelled, "cancelled");
        PackIo.cancel(cancelled);
        archive = archive.toAbsolutePath().normalize();
        validateSource(archive, true);
        FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            long length = channel.size();
            if (length > PackIo.LIMIT) throw PackIo.fail(BUDGET, "Archive size budget exceeded");
            MessageDigest digest = PackIo.digest();
            DataInputStream input = new DataInputStream(new DigestInputStream(Channels.newInputStream(channel), digest));
            byte[] magic = new byte[8];
            input.readFully(magic);
            if (!Arrays.equals(magic, PackIo.MAGIC) || input.readInt() != 1) throw PackIo.fail(INVALID_ARCHIVE, "Invalid archive header");
            int count = input.readInt();
            long total = input.readLong();
            if (count <= 0 || count > PackIo.COUNT_LIMIT || total < 0 || total > PackIo.LIMIT) throw PackIo.fail(BUDGET, "Invalid archive budget");
            List<PackEntry> entries = new ArrayList<>(count);
            Set<String> names = new HashSet<>();
            long metadata = 24, sum = 0;
            String previous = null;
            for (int i = 0; i < count; i++) {
                PackIo.cancel(cancelled);
                int pathLength = input.readInt();
                if (pathLength <= 0 || pathLength > PackIo.PATH_LIMIT) throw PackIo.fail(INVALID_PATH, "Invalid path byte length");
                metadata += 44L + pathLength;
                if (metadata > PackIo.META_LIMIT || metadata + total > PackIo.LIMIT) throw PackIo.fail(BUDGET, "Metadata budget exceeded");
                byte[] path = new byte[pathLength];
                input.readFully(path);
                String name = PackIo.decodePath(path);
                PackIo.inputPath(name);
                PackIo.addPath(names, name);
                if (previous != null && previous.compareTo(name) >= 0) throw PackIo.fail(INVALID_ARCHIVE, "Unsorted metadata");
                previous = name;
                long size = input.readLong();
                if (size < 0 || size > PackIo.LIMIT || sum + size > total) throw PackIo.fail(BUDGET, "Invalid content size");
                sum += size;
                byte[] hash = new byte[32];
                input.readFully(hash);
                entries.add(new PackEntry(name, size, PackIo.hex(hash)));
            }
            PackIo.prefixes(names);
            if (sum != total || metadata + total != length) throw PackIo.fail(INVALID_ARCHIVE, "Archive length mismatch");
            Map<String, PackSnapshot.Location> locations = new HashMap<>();
            byte[] buffer = new byte[PackIo.BUFFER];
            long offset = metadata;
            for (PackEntry entry : entries) {
                PackIo.cancel(cancelled);
                locations.put(entry.path(), new PackSnapshot.Location(entry, offset));
                MessageDigest entryDigest = PackIo.digest();
                long remaining = entry.size();
                while (remaining > 0) {
                    PackIo.cancel(cancelled);
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw PackIo.fail(INVALID_ARCHIVE, "Truncated entry");
                    if (read == 0) continue;
                    entryDigest.update(buffer, 0, read);
                    remaining -= read;
                }
                if (!PackIo.hex(entryDigest.digest()).equals(entry.sha256())) throw PackIo.fail(CONTENT_MISMATCH, "Entry digest mismatch");
                offset += entry.size();
            }
            PackIo.cancel(cancelled);
            if (input.read() != -1 || channel.size() != length) throw PackIo.fail(INVALID_ARCHIVE, "Trailing archive bytes");
            return new PackSnapshot(new PackIndex(entries, total, PackIo.hex(digest.digest())), channel, locations);
        } catch (IOException | RuntimeException | Error e) {
            try {
                channel.close();
            } catch (IOException close) {
                e.addSuppressed(close);
            }
            if (e instanceof EOFException) throw new PackSnapshotException(INVALID_ARCHIVE, "Truncated archive", e);
            if (e instanceof IllegalArgumentException) throw new PackSnapshotException(INVALID_ARCHIVE, "Invalid archive metadata", e);
            throw e;
        }
    }
}
