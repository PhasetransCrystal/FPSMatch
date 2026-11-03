package net.ptcrys.fpsmatch.editor.core.packio;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Q49 public behavior: no implementation helpers, reflection, worker threads or large payload arrays. */
final class PackSelectedZipBehaviorTest {
    private static final BooleanSupplier NEVER = () -> false;
    private static final long LIMIT = 512L * 1024 * 1024;
    private static final int META_LIMIT = 4 * 1024 * 1024;
    private static final String VALID_HASH = "0".repeat(64);
    @TempDir Path workspace;

    @Test
    void exactSortedOutputIsIndependentOfZipOrderAndReadableWithoutTheSource() throws Exception {
        List<Item> entries = List.of(item("z", "last"), item("assets/", ""),
                item("Code.class", "code"), item("assets/a", "first"), item("nested.jar", "unused"));
        Path first = zip("first.zip", entries);
        List<Item> reversed = new ArrayList<>(entries);
        java.util.Collections.reverse(reversed);
        Path second = zip("second.zip", reversed);
        Path out = output();
        Path a = out.resolve("a.pack"), b = out.resolve("b.pack"), c = out.resolve("all.pack");
        Set<String> selected = new LinkedHashSet<>(List.of("z", "assets/a"));
        PackIndex index = PackSnapshots.captureZipEntries(first, a, sha(first), selected, NEVER);
        assertEquals(index, PackSnapshots.captureZipEntries(second, b, sha(second), selected, NEVER));
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        assertEquals(List.of("assets/a", "z"), index.entries().stream().map(PackEntry::path).toList());
        assertEquals(9, index.contentBytes());
        assertEquals(sha(a), index.sha256());
        PackIndex all = PackSnapshots.captureZip(first, c, NEVER);
        assertEquals(List.of("Code.class", "assets/a", "nested.jar", "z"),
                all.entries().stream().map(PackEntry::path).toList());
        Files.delete(first);
        Files.delete(second);
        assertIndependentPack(a, Map.of("assets/a", bytes("first"), "z", bytes("last")));
        try (PackSnapshot reader = PackSnapshots.open(a, NEVER)) {
            assertEquals(index, reader.index());
            for (var expected : Map.of("assets/a", "first", "z", "last").entrySet()) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                reader.copyEntry(expected.getKey(), sink, NEVER);
                assertArrayEquals(bytes(expected.getValue()), sink.toByteArray());
            }
        }
        assertEquals(List.of("a.pack", "all.pack", "b.pack"), children(out));
    }

    @Test
    void invalidArgumentsWinOverMissingSourceAndNeverInvokeCancellation() throws Exception {
        Path source = workspace.resolve("absent.zip"), out = output(), target = out.resolve("never.pack");
        BooleanSupplier forbidden = () -> { throw new AssertionError("I/O phase entered"); };
        for (String badHash : List.of("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class,
                    () -> PackSnapshots.captureZipEntries(source, target, badHash, Set.of("a"), forbidden));
        }
        List<Set<String>> invalid = new ArrayList<>();
        invalid.add(Set.of());
        for (String path : List.of("../a", "a/", "a\\b", "CON", "a".repeat(1025), "\ud800")) {
            invalid.add(Set.of(path));
        }
        invalid.add(Set.of("A", "a"));
        invalid.add(Set.of("A", "a/b"));
        Set<String> tooMany = new HashSet<>();
        for (int i = 0; i < 8193; i++) tooMany.add("file-" + i);
        invalid.add(tooMany);
        for (Set<String> selection : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> PackSnapshots.captureZipEntries(source, target, VALID_HASH, selection, forbidden));
        }
        assertThrows(NullPointerException.class, () -> PackSnapshots.captureZipEntries(null, target, VALID_HASH, Set.of("a"), forbidden));
        assertThrows(NullPointerException.class, () -> PackSnapshots.captureZipEntries(source, null, VALID_HASH, Set.of("a"), forbidden));
        assertThrows(NullPointerException.class, () -> PackSnapshots.captureZipEntries(source, target, null, Set.of("a"), forbidden));
        assertThrows(NullPointerException.class, () -> PackSnapshots.captureZipEntries(source, target, VALID_HASH, null, forbidden));
        assertThrows(NullPointerException.class, () -> PackSnapshots.captureZipEntries(source, target, VALID_HASH, Set.of("a"), null));
        Set<String> nullElement = new HashSet<>();
        nullElement.add(null);
        assertThrows(NullPointerException.class, () -> PackSnapshots.captureZipEntries(source, target, VALID_HASH, nullElement, forbidden));
        assertFalse(Files.exists(source));
        assertEquals(List.of(), children(out));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "Assets/a", "folder"})
    void absentWrongCaseAndDirectorySelectionsDoNotPublish(String selection) throws Exception {
        Path source = zip("source.zip", List.of(item("assets/a", "data"), item("folder/", "")));
        Path out = output();
        byte[] before = Files.readAllBytes(source);
        expect(PackSnapshotException.Code.INVALID_SOURCE,
                () -> capture(source, out, sha(source), Set.of(selection), NEVER));
        assertArrayEquals(before, Files.readAllBytes(source));
        assertEquals(List.of(), children(out));
    }

    @Test
    void rawHashIsCheckedBeforeZipInterpretation() throws Exception {
        Path source = Files.write(workspace.resolve("not.zip"), bytes("not a zip"));
        Path out = output();
        expect(PackSnapshotException.Code.CONTENT_MISMATCH,
                () -> capture(source, out, VALID_HASH, Set.of("a"), NEVER));
        expect(PackSnapshotException.Code.INVALID_ARCHIVE,
                () -> capture(source, out, sha(source), Set.of("a"), NEVER));
        assertArrayEquals(bytes("not a zip"), Files.readAllBytes(source));
        assertEquals(List.of(), children(out));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsafe", "duplicate", "case", "prefix", "encrypted", "zip64"})
    void unselectedEntriesStillPassEveryGlobalStructureGate(String defect) throws Exception {
        List<Item> items = switch (defect) {
            case "unsafe" -> List.of(item("keep", "ok"), item("../bad", "bad"));
            case "case" -> List.of(item("keep", "ok"), item("A", ""), item("a", ""));
            case "prefix" -> List.of(item("keep", "ok"), item("A", ""), item("a/b", ""));
            default -> List.of(item("keep", "ok"), item("bad1", ""), item("bad2", ""));
        };
        Path source = zip("source.zip", items);
        if (defect.equals("duplicate")) {
            patchName(source, "bad2", "bad1");
        } else if (defect.equals("encrypted") || defect.equals("zip64")) {
            patchCentral(source, "bad1", defect.equals("encrypted") ? 8 : 24,
                    defect.equals("encrypted") ? 1 : -1, defect.equals("encrypted") ? 2 : 4);
        }
        byte[] before = Files.readAllBytes(source);
        Path out = output();
        PackSnapshotException.Code code = switch (defect) {
            case "unsafe" -> PackSnapshotException.Code.INVALID_PATH;
            case "duplicate", "case", "prefix" -> PackSnapshotException.Code.DUPLICATE_PATH;
            default -> PackSnapshotException.Code.INVALID_ARCHIVE;
        };
        expect(code, () -> capture(source, out, sha(source), Set.of("keep"), NEVER));
        assertArrayEquals(before, Files.readAllBytes(source));
        assertEquals(List.of(), children(out));
    }

    @Test
    void selectedCrcIsRequiredButUnselectedPayloadAndLocalHeaderAreNotRead() throws Exception {
        Path source = storedZip("source.zip", List.of(item("keep", "yes"), item("bad", "payload")));
        String oldHash = sha(source);
        byte[] raw = Files.readAllBytes(source);
        int local = localOffset(source, "bad");
        int payload = local + 30 + u16(raw, local + 26) + u16(raw, local + 28);
        raw[payload] ^= 1;
        Files.write(source, raw);
        Path out = output();
        expect(PackSnapshotException.Code.CONTENT_MISMATCH,
                () -> capture(source, out, oldHash, Set.of("keep"), NEVER));
        expect(PackSnapshotException.Code.INVALID_ARCHIVE,
                () -> capture(source, out, sha(source), Set.of("bad"), NEVER));
        assertEquals(List.of(), children(out));
        capture(source, out, sha(source), Set.of("keep"), NEVER);
        assertIndependentPack(out.resolve("result.pack"), Map.of("keep", bytes("yes")));
        Files.delete(out.resolve("result.pack"));
        raw[local] = 0; // A central entry can remain structurally valid without a readable local stream.
        Files.write(source, raw);
        capture(source, out, sha(source), Set.of("keep"), NEVER);
        assertIndependentPack(out.resolve("result.pack"), Map.of("keep", bytes("yes")));
        assertArrayEquals(raw, Files.readAllBytes(source));
        assertEquals(List.of("result.pack"), children(out));
    }

    @Test
    void selectionIsCopiedBeforeTheFirstCancellationCallback() throws Exception {
        Path source = zip("source.zip", List.of(item("keep", "yes"), item("other", "no")));
        Set<String> selection = new HashSet<>(Set.of("keep"));
        Path out = output();
        AtomicBoolean called = new AtomicBoolean();
        capture(source, out, sha(source), selection, () -> {
            if (called.compareAndSet(false, true)) {
                selection.clear();
                selection.add("other");
            }
            return false;
        });
        assertTrue(called.get());
        assertEquals(Set.of("other"), selection);
        assertIndependentPack(out.resolve("result.pack"), Map.of("keep", bytes("yes")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw-cancel", "pack-cancel", "raw-throw", "pack-throw"})
    void cancellationAndCallbackFailureCleanEveryPrivateFileSynchronously(String stage) throws Exception {
        Path source = storedZip("source.zip", List.of(item("keep", "yes"), item("other", "unused")));
        byte[] before = Files.readAllBytes(source);
        Path out = output();
        List<List<String>> observed = new ArrayList<>();
        AtomicBoolean triggered = new AtomicBoolean();
        IllegalArgumentException sentinel = new IllegalArgumentException("callback sentinel");
        BooleanSupplier callback = () -> {
            List<String> names = uncheckedChildren(out);
            observed.add(names);
            boolean zip = has(names, ".edzip-"), pack = has(names, ".edpack-");
            boolean requestedStage = stage.startsWith("raw") ? zip && !pack : zip && pack;
            // For pack-throw wait until actual selected payload has been written. This exercises
            // copyZip's parser exception boundary, which must preserve caller IAE identity.
            if (stage.equals("pack-throw") && requestedStage) {
                try {
                    requestedStage = false;
                    for (String name : names) {
                        if (name.startsWith(".edpack-") && Files.size(out.resolve(name)) > 72) requestedStage = true;
                    }
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
            if (requestedStage) {
                triggered.set(true);
                if (stage.endsWith("throw")) throw sentinel;
                return true;
            }
            return false;
        };
        String expected = sha(source);
        if (stage.endsWith("throw")) {
            assertSame(sentinel, assertThrows(IllegalArgumentException.class,
                    () -> capture(source, out, expected, Set.of("keep"), callback)));
        } else {
            expect(PackSnapshotException.Code.CANCELLED,
                    () -> capture(source, out, expected, Set.of("keep"), callback));
        }
        assertTrue(triggered.get(), observed.toString());
        assertTrue(observed.stream().anyMatch(names -> has(names, ".edzip-")));
        assertArrayEquals(before, Files.readAllBytes(source));
        assertEquals(List.of(), children(out));
    }

    @Test
    void changingOnlyUnselectedBytesWithRestoredSizeAndTimeStillFailsRawRecheck() throws Exception {
        Path source = storedZip("source.zip", List.of(item("keep", "yes"), item("other", "unused")));
        byte[] before = Files.readAllBytes(source), changed = before.clone();
        int local = localOffset(source, "other");
        changed[local + 30 + u16(changed, local + 26) + u16(changed, local + 28)] ^= 1;
        FileTime modified = Files.getLastModifiedTime(source);
        Path out = output();
        AtomicBoolean mutated = new AtomicBoolean();
        List<List<String>> stages = new ArrayList<>();
        String expected = sha(source);
        expect(PackSnapshotException.Code.SOURCE_CHANGED, () -> capture(source, out, expected, Set.of("keep"), () -> {
            List<String> names = uncheckedChildren(out);
            stages.add(names);
            if (has(names, ".edzip-") && has(names, ".edpack-") && mutated.compareAndSet(false, true)) {
                try {
                    Files.write(source, changed);
                    Files.setLastModifiedTime(source, modified);
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
            return false;
        }));
        assertTrue(mutated.get(), stages.toString());
        assertEquals(before.length, Files.size(source));
        assertEquals(modified, Files.getLastModifiedTime(source));
        assertArrayEquals(changed, Files.readAllBytes(source));
        assertEquals(List.of(), children(out));
    }

    @Test
    void targetIsNeverOverwrittenIncludingWhenItAppearsDuringStaging() throws Exception {
        Path source = zip("source.zip", List.of(item("keep", "yes")));
        Path out = output(), target = out.resolve("result.pack");
        Files.write(target, bytes("existing"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> capture(source, out, sha(source), Set.of("keep"), NEVER));
        assertArrayEquals(bytes("existing"), Files.readAllBytes(target));
        assertEquals(List.of("result.pack"), children(out));
        Files.delete(target);
        AtomicBoolean created = new AtomicBoolean();
        String expected = sha(source);
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> capture(source, out, expected, Set.of("keep"), () -> {
                    if (has(uncheckedChildren(out), ".edpack-") && created.compareAndSet(false, true)) {
                        try { Files.write(target, bytes("arrived")); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }
                    return false;
                }));
        assertTrue(created.get());
        assertArrayEquals(bytes("arrived"), Files.readAllBytes(target));
        assertEquals(List.of("result.pack"), children(out));
    }

    @Test
    void realCompressedUnselectedAggregateDoesNotConsumeSelectedContentBudget() throws Exception {
        Path source = workspace.resolve("large.zip");
        long each = LIMIT / 2 + 1;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            streamZeros(zip, "unused-a", each);
            put(zip, item("keep", "small"));
            streamZeros(zip, "unused-b", each);
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            assertEquals(each, zip.getEntry("unused-a").getSize());
            assertEquals(each, zip.getEntry("unused-b").getSize());
            assertTrue(zip.getEntry("unused-a").getSize() <= LIMIT);
            assertTrue(zip.getEntry("unused-a").getSize() + zip.getEntry("unused-b").getSize() > LIMIT);
        }
        assertTrue(Files.size(source) < 2 * 1024 * 1024, "fixture must contain real compressed streams");
        Path out = output();
        capture(source, out, sha(source), Set.of("keep"), NEVER);
        assertIndependentPack(out.resolve("result.pack"), Map.of("keep", bytes("small")));
        expect(PackSnapshotException.Code.BUDGET,
                () -> PackSnapshots.captureZip(source, out.resolve("all.pack"), NEVER));
        expect(PackSnapshotException.Code.BUDGET,
                () -> PackSnapshots.captureZipEntries(source, out.resolve("selected-large.pack"), sha(source),
                        Set.of("unused-a", "unused-b"), NEVER));
        assertEquals(List.of("result.pack"), children(out));
    }

    @Test
    void realSelectedContentWithinLimitStillFailsCombinedMetadataBudget() throws Exception {
        String name = "keep";
        long metadata = 24L + 44 + name.getBytes(StandardCharsets.UTF_8).length;
        long content = LIMIT - metadata + 1;
        assertTrue(content <= LIMIT, "content alone must fit the selected content budget");
        assertEquals(LIMIT + 1, metadata + content, "combined size crosses the limit by one byte");
        Path source = workspace.resolve("combined-budget.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            streamZeros(zip, name, content);
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            assertEquals(1, zip.size());
            assertEquals(content, zip.getEntry(name).getSize());
        }
        assertTrue(Files.size(source) < 2 * 1024 * 1024, "fixture must contain a real compressed stream");
        Path out = output();
        expect(PackSnapshotException.Code.BUDGET,
                () -> capture(source, out, sha(source), Set.of(name), NEVER));
        assertEquals(List.of(), children(out), "target and both pending file kinds must be absent");
    }

    @Test
    void realOversizedUnselectedEntryStillFailsBeforeFiltering() throws Exception {
        Path source = workspace.resolve("oversized.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            put(zip, item("keep", "small"));
            streamZeros(zip, "unused", LIMIT + 1);
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            assertEquals(LIMIT + 1, zip.getEntry("unused").getSize());
        }
        Path out = output();
        expect(PackSnapshotException.Code.BUDGET,
                () -> capture(source, out, sha(source), Set.of("keep"), NEVER));
        assertEquals(List.of(), children(out));
    }

    @Test
    void actualCentralMetadataBoundaryAppliesBeforeSelection() throws Exception {
        Path boundary = metadataZip("boundary.zip", false);
        Path over = metadataZip("over.zip", true);
        assertEquals(META_LIMIT, centralSize(boundary));
        assertEquals(META_LIMIT + 1, centralSize(over));
        Path out = output();
        capture(boundary, out, sha(boundary), Set.of(longName(0, 978)), NEVER);
        assertIndependentPack(out.resolve("result.pack"), Map.of(longName(0, 978), new byte[0]));
        expect(PackSnapshotException.Code.BUDGET,
                () -> PackSnapshots.captureZipEntries(over, out.resolve("never.pack"), sha(over),
                        Set.of(longName(0, 978)), NEVER));
        assertEquals(List.of("result.pack"), children(out));
    }

    @Test
    void actualFileAndNodeBoundariesCannotBeBypassedByOneFileSelection() throws Exception {
        Path boundary = structureZip("boundary.zip", 8192, 8192);
        Path filesOver = structureZip("files-over.zip", 8193, 0);
        Path nodesOver = structureZip("nodes-over.zip", 8192, 8193);
        try (ZipFile zip = new ZipFile(boundary.toFile())) { assertEquals(16384, zip.size()); }
        Path out = output();
        capture(boundary, out, sha(boundary), Set.of("f0"), NEVER);
        assertIndependentPack(out.resolve("result.pack"), Map.of("f0", new byte[0]));
        for (Path source : List.of(filesOver, nodesOver)) {
            expect(PackSnapshotException.Code.BUDGET,
                    () -> PackSnapshots.captureZipEntries(source, out.resolve("never.pack"), sha(source), Set.of("f0"), NEVER));
        }
        assertEquals(List.of("result.pack"), children(out));
    }

    private Path output() throws IOException { return Files.createDirectory(workspace.resolve("output")); }
    private static void capture(Path source, Path out, String sha, Set<String> selection, BooleanSupplier callback) throws IOException {
        PackSnapshots.captureZipEntries(source, out.resolve("result.pack"), sha, selection, callback);
    }
    private Path zip(String name, List<Item> entries) throws IOException { return writeZip(name, entries, false); }
    private Path storedZip(String name, List<Item> entries) throws IOException { return writeZip(name, entries, true); }
    private Path writeZip(String name, List<Item> entries, boolean stored) throws IOException {
        Path source = workspace.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (Item item : entries) {
                if (stored) {
                    ZipEntry entry = new ZipEntry(item.name());
                    entry.setTime(946684800000L);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(item.content().length);
                    CRC32 crc = new CRC32(); crc.update(item.content()); entry.setCrc(crc.getValue());
                    zip.putNextEntry(entry); zip.write(item.content()); zip.closeEntry();
                } else put(zip, item);
            }
        }
        return source;
    }
    private static void put(ZipOutputStream zip, Item item) throws IOException {
        ZipEntry entry = new ZipEntry(item.name()); entry.setTime(946684800000L);
        zip.putNextEntry(entry); zip.write(item.content()); zip.closeEntry();
    }
    private static void streamZeros(ZipOutputStream zip, String name, long size) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(946684800000L); zip.putNextEntry(entry);
        byte[] buffer = new byte[64 * 1024];
        for (long remaining = size; remaining > 0;) {
            int length = (int) Math.min(buffer.length, remaining); zip.write(buffer, 0, length); remaining -= length;
        }
        zip.closeEntry();
    }
    private Path metadataZip(String name, boolean over) throws IOException {
        Path source = workspace.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (int i = 0; i < 4096; i++) put(zip, item(longName(i, 978 + (over && i == 4095 ? 1 : 0)), ""));
        }
        return source;
    }
    private static String longName(int index, int length) {
        String prefix = "f" + index + "-"; return prefix + "x".repeat(length - prefix.length());
    }
    private Path structureZip(String name, int files, int directories) throws IOException {
        Path source = workspace.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (int i = 0; i < files; i++) put(zip, item("f" + i, ""));
            for (int i = 0; i < directories; i++) put(zip, item("d" + i + "/", ""));
        }
        return source;
    }
    private static void assertIndependentPack(Path file, Map<String, byte[]> expected) throws Exception {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            assertArrayEquals(bytes("EDPACK01"), in.readNBytes(8)); assertEquals(1, in.readInt());
            assertEquals(expected.size(), in.readInt());
            assertEquals(expected.values().stream().mapToLong(value -> value.length).sum(), in.readLong());
            List<String> paths = expected.keySet().stream().sorted().toList();
            for (String path : paths) {
                int length = in.readInt(); assertTrue(length > 0 && length <= 1024);
                assertEquals(path, new String(in.readNBytes(length), StandardCharsets.UTF_8));
                assertEquals(expected.get(path).length, in.readLong());
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected.get(path)), in.readNBytes(32));
            }
            for (String path : paths) assertArrayEquals(expected.get(path), in.readNBytes(expected.get(path).length));
            assertEquals(-1, in.read());
        }
    }
    private static String sha(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static int centralSize(Path source) throws IOException {
        try (FileChannel channel = FileChannel.open(source)) {
            ByteBuffer end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            while (end.hasRemaining()) channel.read(end, channel.size() - 22 + end.position());
            assertEquals(0x06054b50, end.getInt(0)); return end.getInt(12);
        }
    }
    private static int central(byte[] raw, String name) {
        int end = raw.length - 22;
        assertEquals(0x06054b50, le(raw).getInt(end));
        int position = le(raw).getInt(end + 16);
        while (position < end) {
            assertEquals(0x02014b50, le(raw).getInt(position));
            int length = u16(raw, position + 28);
            if (new String(raw, position + 46, length, StandardCharsets.UTF_8).equals(name)) return position;
            position += 46 + length + u16(raw, position + 30) + u16(raw, position + 32);
        }
        throw new AssertionError("Missing central entry " + name);
    }
    private static int localOffset(Path source, String name) throws IOException {
        byte[] raw = Files.readAllBytes(source); return le(raw).getInt(central(raw, name) + 42);
    }
    private static void patchCentral(Path source, String name, int field, int value, int width) throws IOException {
        byte[] raw = Files.readAllBytes(source); int offset = central(raw, name) + field;
        if (width == 2) le(raw).putShort(offset, (short) value); else le(raw).putInt(offset, value);
        Files.write(source, raw);
    }
    private static void patchName(Path source, String oldName, String newName) throws IOException {
        byte[] raw = Files.readAllBytes(source); int central = central(raw, oldName);
        int local = le(raw).getInt(central + 42); assertEquals(oldName.length(), newName.length());
        System.arraycopy(bytes(newName), 0, raw, central + 46, newName.length());
        System.arraycopy(bytes(newName), 0, raw, local + 30, newName.length()); Files.write(source, raw);
    }
    private static ByteBuffer le(byte[] raw) { return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN); }
    private static int u16(byte[] raw, int offset) { return Short.toUnsignedInt(le(raw).getShort(offset)); }
    private static boolean has(List<String> names, String prefix) { return names.stream().anyMatch(name -> name.startsWith(prefix)); }
    private static List<String> children(Path directory) throws IOException {
        try (var files = Files.list(directory)) { return files.map(path -> path.getFileName().toString()).sorted().toList(); }
    }
    private static List<String> uncheckedChildren(Path directory) {
        try { return children(directory); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Item item(String name, String content) { return new Item(name, bytes(content)); }
    private static void expect(PackSnapshotException.Code code, IoAction action) {
        assertEquals(code, assertThrows(PackSnapshotException.class, action::run).code());
    }
    private record Item(String name, byte[] content) { }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
}
