package net.ptcrys.fpsmatch.editor.core.packio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Black-box tests of the frozen Q42 public contract; fixtures do not call implementation helpers. */
final class PackSnapshotsBehaviorTest {
    private static final BooleanSupplier NEVER = () -> false;
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    @TempDir
    Path workspace;

    @Test
    void directoryAndZipIgnoreEnumerationOrderAndSourceMetadata() throws Exception {
        Path first = Files.createDirectory(workspace.resolve("first"));
        Path second = Files.createDirectory(workspace.resolve("second"));
        write(first, "z.txt", bytes("last"));
        write(first, "A/empty", new byte[0]);
        write(first, "A/first", bytes("first"));
        write(second, "A/first", bytes("first"));
        write(second, "A/empty", new byte[0]);
        write(second, "z.txt", bytes("last"));
        Files.setLastModifiedTime(first.resolve("z.txt"), FileTime.fromMillis(946684800000L));
        Files.setLastModifiedTime(second.resolve("z.txt"), FileTime.fromMillis(1577836800000L));
        Path zip = workspace.resolve("source.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            zipEntry(output, "z.txt", bytes("last"), 946684800000L);
            zipEntry(output, "A/", new byte[0], 1577836800000L);
            zipEntry(output, "A/empty", new byte[0], 1577836800000L);
            zipEntry(output, "A/first", bytes("first"), 1577836800000L);
        }
        Path a = workspace.resolve("a.pack");
        Path b = workspace.resolve("b.pack");
        Path c = workspace.resolve("c.pack");
        PackIndex index = PackSnapshots.captureDirectory(first, a, NEVER);
        assertEquals(index, PackSnapshots.captureDirectory(second, b, NEVER));
        assertEquals(index, PackSnapshots.captureZip(zip, c, NEVER));
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(c));
        byte[] expected = archive(3, 9, List.of(
                item("A/empty", new byte[0]), item("A/first", bytes("first")),
                item("z.txt", bytes("last"))), new byte[0]);
        assertArrayEquals(expected, Files.readAllBytes(a));
        assertEquals(hex(expected), index.sha256());
        assertEquals(List.of("A/empty", "A/first", "z.txt"),
                index.entries().stream().map(PackEntry::path).toList());
        assertEquals(9, index.contentBytes());
        assertEquals(3, regularFiles(first).size());
        assertEquals(3, regularFiles(second).size());
    }

    @Test
    void preexistingTargetSurvivesBothCaptureKindsWithoutTempResidue() throws Exception {
        Path source = directoryWithFile("source", "a", bytes("a"));
        Path zip = zipWithOneFile("source.zip", "a", bytes("a"));
        Path output = Files.createDirectory(workspace.resolve("output"));
        Path target = write(output, "existing.pack", bytes("keep exactly"));
        byte[] before = Files.readAllBytes(target);
        assertThrows(IOException.class, () -> PackSnapshots.captureDirectory(source, target, NEVER));
        assertArrayEquals(before, Files.readAllBytes(target));
        assertEquals(List.of("existing.pack"), children(output));
        assertThrows(IOException.class, () -> PackSnapshots.captureZip(zip, target, NEVER));
        assertArrayEquals(before, Files.readAllBytes(target));
        assertEquals(List.of("existing.pack"), children(output));
    }

    @Test
    void cancellationAfterSeveralChecksPublishesNothing() throws Exception {
        Path source = directoryWithFile("source", "a", new byte[512 * 1024]);
        Path output = Files.createDirectory(workspace.resolve("output"));
        AtomicInteger checks = new AtomicInteger();
        expectCode(PackSnapshotException.Code.CANCELLED, () -> PackSnapshots.captureDirectory(
                source, output.resolve("never.pack"), () -> checks.incrementAndGet() >= 5));
        assertTrue(checks.get() >= 5);
        assertEquals(List.of(), children(output));
        assertEquals(512 * 1024, Files.size(source.resolve("a")));
    }

    @Test
    void cancellationDuringStagingRemovesTempAndLeavesNoTarget() throws Exception {
        Path source = directoryWithFile("source", "a", new byte[512 * 1024]);
        Path output = Files.createDirectory(workspace.resolve("output"));
        AtomicInteger stagingChecks = new AtomicInteger();
        BooleanSupplier cancelled = () -> !uncheckedChildren(output).isEmpty()
                && stagingChecks.incrementAndGet() >= 3;
        expectCode(PackSnapshotException.Code.CANCELLED,
                () -> PackSnapshots.captureDirectory(source, output.resolve("never.pack"), cancelled));
        assertTrue(stagingChecks.get() >= 3, "cancellation must follow multiple staging checks");
        assertEquals(List.of(), children(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rewrite", "add", "delete"})
    void mutationAfterFirstPassIsDetectedAndLeavesNoOutput(String kind) throws Exception {
        Path source = directoryWithFile("source", "a", bytes("original"));
        write(source, "z", bytes("last"));
        Path output = Files.createDirectory(workspace.resolve("output"));
        AtomicBoolean mutated = new AtomicBoolean();
        BooleanSupplier observer = () -> {
            if (!uncheckedChildren(output).isEmpty() && mutated.compareAndSet(false, true)) {
                try {
                    switch (kind) {
                        case "rewrite" -> Files.write(source.resolve("a"), bytes("modified"));
                        case "add" -> Files.write(source.resolve("new"), bytes("new"));
                        case "delete" -> Files.delete(source.resolve("z"));
                        default -> throw new AssertionError(kind);
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
            return false;
        };
        expectCode(PackSnapshotException.Code.SOURCE_CHANGED,
                () -> PackSnapshots.captureDirectory(source, output.resolve("never.pack"), observer));
        assertTrue(mutated.get(), "the fixture must mutate after a staging file exists");
        assertEquals(List.of(), children(output));
    }

    @Test
    void zipReplacedDuringStagingIsDetected() throws Exception {
        Path zip = zipWithOneFile("source.zip", "a", bytes("original"));
        Path replacement = zipWithOneFile("replacement.zip", "a", bytes("modified"));
        Path output = Files.createDirectory(workspace.resolve("output"));
        AtomicBoolean mutated = new AtomicBoolean();
        BooleanSupplier observer = () -> {
            if (!uncheckedChildren(output).isEmpty() && mutated.compareAndSet(false, true)) {
                try {
                    Files.write(zip, Files.readAllBytes(replacement));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
            return false;
        };
        expectCode(PackSnapshotException.Code.SOURCE_CHANGED,
                () -> PackSnapshots.captureZip(zip, output.resolve("never.pack"), observer));
        assertTrue(mutated.get());
        assertEquals(List.of(), children(output));
    }

    @Test
    void mutableZipBecomingOverBudgetAfterFrozenPassIsSourceChanged() throws Exception {
        Path zip = zipWithOneFile("source.zip", "a", bytes("original"));
        byte[] malformedReplacement = Files.readAllBytes(zip);
        int eocd = malformedReplacement.length - 22;
        assertTrue(signature(malformedReplacement, eocd, 0x06054b50));
        littleInt(malformedReplacement, eocd + 12, 4 * 1024 * 1024 + 1);
        Path output = Files.createDirectory(workspace.resolve("output"));
        AtomicBoolean mutated = new AtomicBoolean();
        AtomicBoolean sawBothTemps = new AtomicBoolean();
        BooleanSupplier observer = () -> {
            List<String> names = uncheckedChildren(output);
            if (names.stream().anyMatch(name -> name.startsWith(".edpack-"))
                    && mutated.compareAndSet(false, true)) {
                sawBothTemps.set(names.stream().anyMatch(name -> name.startsWith(".edzip-")));
                try {
                    Files.write(zip, malformedReplacement);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
            return false;
        };
        // Reading the mutable replacement as a ZIP would yield BUDGET/INVALID_ARCHIVE.
        // The frozen private ZIP remains valid; only the original raw-container recheck fails.
        expectCode(PackSnapshotException.Code.SOURCE_CHANGED,
                () -> PackSnapshots.captureZip(zip, output.resolve("never.pack"), observer));
        assertTrue(mutated.get(), "mutation follows .edpack creation, after the fixed ZIP first pass");
        assertTrue(sawBothTemps.get(), "the fixed ZIP must still exist beside staging output");
        assertArrayEquals(malformedReplacement, Files.readAllBytes(zip));
        assertEquals(List.of(), children(output), "target, .edzip and .edpack must all be absent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw-zip", "output-pack"})
    void zipCancellationDuringEachCopyStageCleansBothTempKinds(String stage) throws Exception {
        byte[] content = new byte[512 * 1024];
        new java.util.Random(42).nextBytes(content);
        Path zip = zipWithOneFile("source.zip", "a", content);
        byte[] before = Files.readAllBytes(zip);
        Path output = Files.createDirectory(workspace.resolve("output"));
        AtomicInteger checksInStage = new AtomicInteger();
        AtomicBoolean sawBothTemps = new AtomicBoolean();
        AtomicBoolean sawPack = new AtomicBoolean();
        BooleanSupplier cancelled = () -> {
            List<String> names = uncheckedChildren(output);
            boolean hasZip = names.stream().anyMatch(name -> name.startsWith(".edzip-"));
            boolean hasPack = names.stream().anyMatch(name -> name.startsWith(".edpack-"));
            if (hasPack) {
                sawPack.set(true);
            }
            if (hasZip && hasPack) {
                sawBothTemps.set(true);
            }
            boolean inRequestedStage = stage.equals("raw-zip") ? hasZip && !hasPack : hasPack;
            return inRequestedStage && checksInStage.incrementAndGet() >= 3;
        };
        expectCode(PackSnapshotException.Code.CANCELLED,
                () -> PackSnapshots.captureZip(zip, output.resolve("never.pack"), cancelled));
        assertTrue(checksInStage.get() >= 3, "cancel only after multiple checks in the selected stage");
        if (stage.equals("raw-zip")) {
            assertFalse(sawPack.get(), "raw copy cancellation precedes output creation");
        } else {
            assertTrue(sawBothTemps.get(), "output copy cancellation must clean two existing temps");
        }
        assertArrayEquals(before, Files.readAllBytes(zip));
        assertEquals(List.of(), children(output), "target, .edzip and .edpack must all be absent");
    }

    @Test
    void threadInterruptionCancelsWithoutClearingFlag() throws Exception {
        Path source = directoryWithFile("source", "a", bytes("a"));
        Path target = workspace.resolve("never.pack");
        try {
            Thread.currentThread().interrupt();
            expectCode(PackSnapshotException.Code.CANCELLED,
                    () -> PackSnapshots.captureDirectory(source, target, NEVER));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(Files.exists(target));
    }

    @Test
    void nestedTargetIsRejectedWithoutWritingSource() throws Exception {
        Path source = directoryWithFile("source", "a", bytes("a"));
        List<String> before = children(source);
        expectCode(PackSnapshotException.Code.INVALID_SOURCE,
                () -> PackSnapshots.captureDirectory(source, source.resolve("nested.pack"), NEVER));
        assertEquals(before, children(source));
    }

    @Test
    void detectableSymlinkSourceNodeAndAncestorAreRejected() throws Exception {
        Path outside = directoryWithFile("outside", "a", bytes("a"));
        Path source = directoryWithFile("source", "b", bytes("b"));
        Path link = source.resolve("link");
        try {
            Files.createSymbolicLink(link, outside.resolve("a"));
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "host cannot create symlink: " + unavailable);
        }
        Path target = workspace.resolve("never.pack");
        expectCode(PackSnapshotException.Code.INVALID_SOURCE,
                () -> PackSnapshots.captureDirectory(source, target, NEVER));
        assertFalse(Files.exists(target));
        Files.delete(link);
        Path alias = workspace.resolve("alias");
        Files.createSymbolicLink(alias, outside);
        expectCode(PackSnapshotException.Code.INVALID_SOURCE,
                () -> PackSnapshots.captureDirectory(alias, target, NEVER));
        expectCode(PackSnapshotException.Code.INVALID_SOURCE,
                () -> PackSnapshots.captureDirectory(source, alias.resolve("never.pack"), NEVER));
        assertEquals(List.of("a"), children(outside));
    }

    @Test
    void readerCopiesAuthenticatesAndDoesNotOwnSinkOrDeleteArchive() throws Exception {
        Path archive = fixture("valid.pack", archive(2, 5,
                List.of(item("a", bytes("hello")), item("b", new byte[0])), new byte[0]));
        PackSnapshot reader = PackSnapshots.open(archive, NEVER);
        PackIndex index = reader.index();
        TrackingSink sink = new TrackingSink();
        reader.copyEntry("a", sink, NEVER);
        assertArrayEquals(bytes("hello"), sink.bytes());
        assertFalse(sink.closed);
        reader.copyEntry("b", sink, NEVER);
        assertFalse(sink.closed);
        assertThrows(IllegalArgumentException.class, () -> reader.copyEntry("missing", sink, NEVER));
        reader.close();
        reader.close();
        assertEquals(index, reader.index());
        assertThrows(IOException.class, () -> reader.copyEntry("a", sink, NEVER));
        assertFalse(sink.closed);
        assertTrue(Files.exists(archive));
    }

    @Test
    void readerRechecksContentAfterOpenAndLeavesSinkOpenOnFailure() throws Exception {
        Path archive = fixture("changed.pack", archive(1, 5,
                List.of(item("a", bytes("hello"))), new byte[0]));
        try (PackSnapshot reader = PackSnapshots.open(archive, NEVER)) {
            // One-byte path => 24-byte header + 45-byte metadata; payload begins at byte 69.
            try (var channel = java.nio.channels.FileChannel.open(archive, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes("j")), 69);
            }
            TrackingSink sink = new TrackingSink();
            expectCode(PackSnapshotException.Code.CONTENT_MISMATCH,
                    () -> reader.copyEntry("a", sink, NEVER));
            assertFalse(sink.closed);
        }
    }

    @Test
    void readerCancellationAndSinkFailureLeaveCallerSinkOpen() throws Exception {
        Path archive = fixture("valid.pack", archive(1, 131073,
                List.of(item("a", new byte[131073])), new byte[0]));
        try (PackSnapshot reader = PackSnapshots.open(archive, NEVER)) {
            TrackingSink sink = new TrackingSink();
            AtomicInteger checks = new AtomicInteger();
            expectCode(PackSnapshotException.Code.CANCELLED,
                    () -> reader.copyEntry("a", sink, () -> checks.incrementAndGet() >= 2));
            assertTrue(checks.get() >= 2);
            assertFalse(sink.closed);
            IOException sentinel = new IOException("fixture sink failure");
            AtomicBoolean closed = new AtomicBoolean();
            OutputStream failing = new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw sentinel;
                }

                @Override
                public void close() {
                    closed.set(true);
                }
            };
            assertThrows(IOException.class, () -> reader.copyEntry("a", failing, NEVER));
            assertFalse(closed.get());
            TrackingSink retry = new TrackingSink();
            reader.copyEntry("a", retry, NEVER);
            assertEquals(131073, retry.bytes().length);
        }
    }

    @Test
    void openAuthenticatesBeforeReturningReaderAndRejectsTruncationAndTrailingBytes() throws Exception {
        byte[] valid = archive(1, 3, List.of(item("a", bytes("abc"))), new byte[0]);
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        expectCode(PackSnapshotException.Code.CONTENT_MISMATCH,
                () -> openAndClose(fixture("hash.pack", corrupt)));
        for (int end : new int[] {0, 7, 23, 30, valid.length - 1}) {
            Path file = fixture("short-" + end + ".pack", Arrays.copyOf(valid, end));
            assertThrows(PackSnapshotException.class, () -> openAndClose(file));
        }
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(PackSnapshotException.class,
                () -> openAndClose(fixture("trailing.pack", trailing)));
        byte[] magic = valid.clone();
        magic[0] = 'X';
        assertThrows(PackSnapshotException.class,
                () -> openAndClose(fixture("magic.pack", magic)));
        byte[] version = valid.clone();
        version[11] = 2;
        assertThrows(PackSnapshotException.class,
                () -> openAndClose(fixture("version.pack", version)));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 8193, Integer.MAX_VALUE})
    void maliciousCountIsRejectedFromHeaderOnly(int count) throws Exception {
        Path file = fixture("count.pack", archive(count, 0, List.of(), new byte[0]));
        assertThrows(PackSnapshotException.class, () -> openAndClose(file));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 536870913L, Long.MAX_VALUE})
    void declaredTotalBudgetAndOverflowAreRejectedWithoutPayload(long declared) throws Exception {
        Path file = fixture("total.pack", archive(1, declared,
                List.of(item("a", new byte[0])), new byte[0]));
        assertThrows(PackSnapshotException.class, () -> openAndClose(file));
    }

    @Test
    void fullArchiveBudgetIncludesMetadataAndCountsActualSizes() throws Exception {
        Item huge = new Item(bytes("a"), MAX_BYTES, digest(new byte[0]), new byte[0]);
        expectCode(PackSnapshotException.Code.BUDGET,
                () -> openAndClose(fixture("full-budget.pack", archive(1, MAX_BYTES,
                        List.of(huge), new byte[0]))));
        Path mismatch = fixture("total-mismatch.pack", archive(1, 0,
                List.of(item("a", bytes("abc"))), new byte[0]));
        assertThrows(PackSnapshotException.class, () -> openAndClose(mismatch));
        Item negative = new Item(bytes("a"), -1, digest(new byte[0]), new byte[0]);
        assertThrows(PackSnapshotException.class,
                () -> openAndClose(fixture("negative.pack", archive(1, 0,
                        List.of(negative), new byte[0]))));
    }

    @Test
    void metadataBudgetIsRejectedUsingOnlyFourMiBOfSmallNames() throws Exception {
        List<Item> entries = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            String path = String.format(java.util.Locale.ROOT, "%04d", i) + "x".repeat(1020);
            entries.add(item(path, new byte[0]));
        }
        byte[] fixture = archive(entries.size(), 0, entries, new byte[0]);
        assertTrue(fixture.length > 4 * 1024 * 1024);
        expectCode(PackSnapshotException.Code.BUDGET,
                () -> openAndClose(fixture("metadata-budget.pack", fixture)));
    }

    @Test
    void directoryNodeBudgetCountsRootAndEmptyDirectoriesAtExactBoundary() throws Exception {
        Path source = directoryWithFile("source", "only-file", bytes("content"));
        Path lastDirectory = null;
        for (int i = 0; i < 16383; i++) {
            lastDirectory = Files.createDirectory(source.resolve("empty-" + i));
        }
        assertNotNull(lastDirectory);
        Path output = Files.createDirectory(workspace.resolve("output"));
        Path target = output.resolve("boundary.pack");
        // Root + one file + 16383 empty directories is one node over the limit.
        expectCode(PackSnapshotException.Code.BUDGET,
                () -> PackSnapshots.captureDirectory(source, target, NEVER));
        assertEquals(List.of(), children(output));
        Files.delete(lastDirectory);
        PackIndex index = PackSnapshots.captureDirectory(source, target, NEVER);
        assertEquals(List.of(new PackEntry("only-file", 7, hex(bytes("content")))), index.entries());
        assertEquals(7, index.contentBytes());
        assertEquals(List.of("boundary.pack"), children(output));
        try (PackSnapshot reader = PackSnapshots.open(target, NEVER)) {
            assertEquals(index, reader.index());
            TrackingSink sink = new TrackingSink();
            reader.copyEntry("only-file", sink, NEVER);
            assertArrayEquals(bytes("content"), sink.bytes());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1025, Integer.MAX_VALUE})
    void maliciousPathLengthIsRejectedBeforeAllocation(int length) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            header(output, 1, 0);
            output.writeInt(length);
        }
        assertThrows(PackSnapshotException.class,
                () -> openAndClose(fixture("path-length.pack", buffer.toByteArray())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/a", "../a", "a/../b", "./a", "a//b", "a/", "a\\b",
            "C:/a", "a\u0000b", "a\nb", "a?b", "a*b", "a<b", "a>b", "a|b", "a\"b",
            "a.", "a ", "CON", "con.txt", "PRN", "AUX", "NUL", "COM1.bin", "LPT9",
            "CONIN$", "CONOUT$.txt", "dir/NUL.dat"})
    void unsafeArchivePathsAreRejected(String path) throws Exception {
        expectCode(PackSnapshotException.Code.INVALID_PATH,
                () -> openAndClose(fixture("invalid-path.pack", archive(1, 0,
                        List.of(item(path, new byte[0])), new byte[0]))));
    }

    @Test
    void malformedUtf8PathIsRejected() throws Exception {
        Item malformed = new Item(new byte[] {(byte) 0xc3, 0x28}, 0,
                digest(new byte[0]), new byte[0]);
        expectCode(PackSnapshotException.Code.INVALID_PATH,
                () -> openAndClose(fixture("utf8.pack", archive(1, 0,
                        List.of(malformed), new byte[0]))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "case", "prefix", "case-prefix"})
    void duplicateCaseAndFileDirectoryPrefixCollisionsAreRejected(String kind) throws Exception {
        List<Item> entries = switch (kind) {
            case "duplicate" -> List.of(item("a", new byte[0]), item("a", new byte[0]));
            case "case" -> List.of(item("A", new byte[0]), item("a", new byte[0]));
            case "prefix" -> List.of(item("a", new byte[0]), item("a/b", new byte[0]));
            case "case-prefix" -> List.of(item("A", new byte[0]), item("a/b", new byte[0]));
            default -> throw new AssertionError(kind);
        };
        expectCode(PackSnapshotException.Code.DUPLICATE_PATH,
                () -> openAndClose(fixture("duplicate.pack", archive(2, 0, entries, new byte[0]))));
    }

    @Test
    void unsortedMetadataAndEmptySourcesAreRejected() throws Exception {
        Path unsorted = fixture("unsorted.pack", archive(2, 0,
                List.of(item("z", new byte[0]), item("a", new byte[0])), new byte[0]));
        assertThrows(PackSnapshotException.class, () -> openAndClose(unsorted));
        Path empty = Files.createDirectory(workspace.resolve("empty"));
        assertThrows(PackSnapshotException.class,
                () -> PackSnapshots.captureDirectory(empty, workspace.resolve("never.pack"), NEVER));
        assertFalse(Files.exists(workspace.resolve("never.pack")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "CON.txt", "a//b", "bad/../"})
    void zipFilesAndDirectoryEntriesUseSamePathValidation(String name) throws Exception {
        Path zip = zipWithOneFile("source.zip", name, new byte[0]);
        Path output = Files.createDirectory(workspace.resolve("output"));
        expectCode(PackSnapshotException.Code.INVALID_PATH,
                () -> PackSnapshots.captureZip(zip, output.resolve("never.pack"), NEVER));
        assertEquals(List.of(), children(output));
    }

    @Test
    void zipDuplicateFilesAndCasePrefixCollisionAreRejected() throws Exception {
        Path zip = workspace.resolve("duplicate.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            zipEntry(output, "a", new byte[0], 946684800000L);
            zipEntry(output, "b", new byte[0], 946684800000L);
        }
        // Rewrite both local and central names for b to a. Names are single-byte ASCII;
        // lengths and CRCs remain valid, yielding a genuine duplicate ZIP file name.
        byte[] duplicate = Files.readAllBytes(zip);
        int changed = 0;
        for (int offset = 0; offset + 30 < duplicate.length; offset++) {
            if (signature(duplicate, offset, 0x04034b50) && duplicate[offset + 30] == 'b') {
                duplicate[offset + 30] = 'a';
                changed++;
            }
            if (offset + 46 < duplicate.length && signature(duplicate, offset, 0x02014b50)
                    && duplicate[offset + 46] == 'b') {
                duplicate[offset + 46] = 'a';
                changed++;
            }
        }
        assertEquals(2, changed);
        Files.write(zip, duplicate);
        Path output = Files.createDirectory(workspace.resolve("output"));
        expectCode(PackSnapshotException.Code.DUPLICATE_PATH,
                () -> PackSnapshots.captureZip(zip, output.resolve("never.pack"), NEVER));
        assertEquals(List.of(), children(output));

        Path prefixZip = workspace.resolve("prefix.zip");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(prefixZip))) {
            zipEntry(stream, "A", new byte[0], 946684800000L);
            zipEntry(stream, "a/b", new byte[0], 946684800000L);
        }
        expectCode(PackSnapshotException.Code.DUPLICATE_PATH,
                () -> PackSnapshots.captureZip(prefixZip, output.resolve("never.pack"), NEVER));
        assertEquals(List.of(), children(output));
    }

    @Test
    void zipStoredPayloadCrcCorruptionIsRejectedWithoutPublishing() throws Exception {
        byte[] payload = bytes("crc payload");
        CRC32 crc = new CRC32();
        crc.update(payload);
        Path zip = workspace.resolve("crc.zip");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry entry = new ZipEntry("a");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            stream.putNextEntry(entry);
            stream.write(payload);
            stream.closeEntry();
        }
        byte[] corrupt = Files.readAllBytes(zip);
        assertTrue(signature(corrupt, 0, 0x04034b50));
        int nameLength = (corrupt[26] & 0xff) | ((corrupt[27] & 0xff) << 8);
        int extraLength = (corrupt[28] & 0xff) | ((corrupt[29] & 0xff) << 8);
        corrupt[30 + nameLength + extraLength] ^= 1;
        Files.write(zip, corrupt);
        Path output = Files.createDirectory(workspace.resolve("output"));
        assertThrows(PackSnapshotException.class,
                () -> PackSnapshots.captureZip(zip, output.resolve("never.pack"), NEVER));
        assertEquals(List.of(), children(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"entry-budget", "central-budget", "zip64-count", "zip64-size",
            "zip64-offset", "multidisk", "disk-start", "encrypted", "false-count", "central-bounds"})
    void boundedZipPreflightRejectsMaliciousDeclarationsBeforeZipIndexing(String defect) throws Exception {
        Path zip = zipWithOneFile("source.zip", "a", bytes("a"));
        byte[] malformed = Files.readAllBytes(zip);
        int eocd = malformed.length - 22;
        assertTrue(signature(malformed, eocd, 0x06054b50));
        int central = java.nio.ByteBuffer.wrap(malformed, eocd + 16, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        assertTrue(signature(malformed, central, 0x02014b50));
        switch (defect) {
            case "entry-budget" -> {
                littleShort(malformed, eocd + 8, 16385);
                littleShort(malformed, eocd + 10, 16385);
            }
            case "central-budget" -> littleInt(malformed, eocd + 12, 4 * 1024 * 1024 + 1);
            case "zip64-count" -> {
                littleShort(malformed, eocd + 8, 65535);
                littleShort(malformed, eocd + 10, 65535);
            }
            case "zip64-size" -> littleInt(malformed, eocd + 12, -1);
            case "zip64-offset" -> littleInt(malformed, eocd + 16, -1);
            case "multidisk" -> littleShort(malformed, eocd + 4, 1);
            case "disk-start" -> littleShort(malformed, central + 34, 1);
            case "encrypted" -> malformed[central + 8] |= 1;
            case "false-count" -> {
                littleShort(malformed, eocd + 8, 2);
                littleShort(malformed, eocd + 10, 2);
            }
            case "central-bounds" -> littleInt(malformed, eocd + 16, eocd + 1);
            default -> throw new AssertionError(defect);
        }
        Files.write(zip, malformed);
        Path output = Files.createDirectory(workspace.resolve("output"));
        PackSnapshotException.Code expected = defect.endsWith("budget")
                ? PackSnapshotException.Code.BUDGET : PackSnapshotException.Code.INVALID_ARCHIVE;
        expectCode(expected,
                () -> PackSnapshots.captureZip(zip, output.resolve("never.pack"), NEVER));
        assertEquals(List.of(), children(output));
    }

    @Test
    void sourceKindsAndMissingParentAreRejectedWithoutCreatingDirectories() throws Exception {
        Path file = fixture("file", bytes("plain"));
        Path directory = Files.createDirectory(workspace.resolve("directory"));
        Path missing = workspace.resolve("missing");
        assertThrows(PackSnapshotException.class,
                () -> PackSnapshots.captureDirectory(file, workspace.resolve("never.pack"), NEVER));
        assertThrows(PackSnapshotException.class,
                () -> PackSnapshots.captureZip(directory, workspace.resolve("never.pack"), NEVER));
        assertThrows(PackSnapshotException.class,
                () -> PackSnapshots.captureDirectory(missing, workspace.resolve("never.pack"), NEVER));
        Path source = directoryWithFile("source", "a", bytes("a"));
        assertThrows(IOException.class,
                () -> PackSnapshots.captureDirectory(source, missing.resolve("never.pack"), NEVER));
        assertFalse(Files.exists(missing));
    }

    @Test
    void immutableValueObjectsValidateConstructionAndDefensivelyCopy() {
        PackEntry entry = new PackEntry("a", 0, hex(new byte[0]));
        List<PackEntry> source = new ArrayList<>(List.of(entry));
        PackIndex index = new PackIndex(source, 0, hex(bytes("archive")));
        source.clear();
        assertEquals(List.of(entry), index.entries());
        assertThrows(UnsupportedOperationException.class, () -> index.entries().clear());
        assertThrows(NullPointerException.class, () -> new PackEntry(null, 0, hex(new byte[0])));
        assertThrows(NullPointerException.class, () -> new PackIndex(null, 0, hex(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new PackEntry("../a", 0, hex(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new PackEntry("a", -1, hex(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new PackEntry("a", 0, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new PackEntry("a", 0, "a".repeat(63)));
        assertThrows(IllegalArgumentException.class,
                () -> new PackIndex(List.of(entry), 1, hex(new byte[0])));
        assertThrows(IllegalArgumentException.class,
                () -> new PackIndex(List.of(entry, entry), 0, hex(new byte[0])));
    }

    private Path directoryWithFile(String directory, String name, byte[] content) throws IOException {
        return write(Files.createDirectory(workspace.resolve(directory)), name, content).getParent();
    }

    private static Path write(Path root, String name, byte[] content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, content);
    }

    private Path zipWithOneFile(String name, String entry, byte[] content) throws IOException {
        Path zip = workspace.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            zipEntry(output, entry, content, 946684800000L);
        }
        return zip;
    }

    private static void zipEntry(ZipOutputStream output, String name, byte[] content, long time)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(time);
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    private Path fixture(String name, byte[] content) throws IOException {
        return Files.write(workspace.resolve(name), content);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean signature(byte[] bytes, int offset, int expected) {
        return (bytes[offset] & 0xff) == (expected & 0xff)
                && (bytes[offset + 1] & 0xff) == ((expected >>> 8) & 0xff)
                && (bytes[offset + 2] & 0xff) == ((expected >>> 16) & 0xff)
                && (bytes[offset + 3] & 0xff) == ((expected >>> 24) & 0xff);
    }

    private static void littleShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private static void littleInt(byte[] target, int offset, int value) {
        littleShort(target, offset, value);
        littleShort(target, offset + 2, value >>> 16);
    }

    private static byte[] digest(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] content) {
        return HexFormat.of().formatHex(digest(content));
    }

    private static Item item(String path, byte[] content) {
        return new Item(bytes(path), content.length, digest(content), content);
    }

    private static byte[] archive(int count, long total, List<Item> entries, byte[] trailing)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            header(output, count, total);
            for (Item item : entries) {
                output.writeInt(item.path().length);
                output.write(item.path());
                output.writeLong(item.size());
                output.write(item.hash());
            }
            for (Item item : entries) {
                output.write(item.payload());
            }
            output.write(trailing);
        }
        return buffer.toByteArray();
    }

    private static void header(DataOutputStream output, int count, long total) throws IOException {
        output.write(bytes("EDPACK01"));
        output.writeInt(1);
        output.writeInt(count);
        output.writeLong(total);
    }

    private static void openAndClose(Path file) throws IOException {
        try (PackSnapshot snapshot = PackSnapshots.open(file, NEVER)) {
            assertNotNull(snapshot.index());
        }
    }

    private static void expectCode(PackSnapshotException.Code code, IoAction action) {
        PackSnapshotException failure = assertThrows(PackSnapshotException.class, action::run);
        assertEquals(code, failure.code());
    }

    private static List<String> children(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> uncheckedChildren(Path directory) {
        try {
            return children(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static List<Path> regularFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private record Item(byte[] path, long size, byte[] hash, byte[] payload) { }

    private static final class TrackingSink extends OutputStream {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private boolean closed;

        @Override
        public void write(int value) {
            data.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            data.write(value, offset, length);
        }

        @Override
        public void close() {
            closed = true;
        }

        byte[] bytes() {
            return data.toByteArray();
        }
    }
}
