package net.ptcrys.fpsmatch.editor.core.packio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public consumer tests for Q45; no production hooks or implementation-owned constants. */
final class PackSnapshotStreamTest {
    private static final BooleanSupplier NEVER = () -> false;
    private static final int CHUNK = 64 * 1024;

    @TempDir
    Path workspace;

    @Test
    void mixedReadsStopAtEntryBoundaryAndKeepEmptyEntriesEmpty() throws Exception {
        byte[] content = pattern(CHUNK + 23, 17);
        Fixture fixture = capture(Map.of("a", content, "empty", new byte[0],
                "z", new byte[] {91, 92, 93}));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream input = snapshot.openEntry("a", NEVER);
             InputStream empty = snapshot.openEntry("empty", NEVER);
             InputStream next = snapshot.openEntry("z", NEVER)) {
            assertEquals(content.length, input.available());
            assertEquals(0, input.read(new byte[2], 2, 0));
            assertEquals(content[0] & 0xff, input.read());
            byte[] part = new byte[29];
            assertEquals(23, input.read(part, 3, 23));
            assertArrayEquals(Arrays.copyOfRange(content, 1, 24), Arrays.copyOfRange(part, 3, 26));
            assertEquals(0, part[2]);
            assertEquals(0, part[26]);
            assertEquals(0, input.skip(-1));
            assertEquals(11, input.skip(11));
            assertEquals(content.length - 35, input.available());
            assertArrayEquals(Arrays.copyOfRange(content, 35, content.length), input.readAllBytes());
            assertEquals(-1, input.read());
            assertEquals(-1, input.read(new byte[8]));
            assertEquals(0, input.skip(Long.MAX_VALUE));
            assertEquals(0, input.available());
            assertEquals(0, empty.read(new byte[0]));
            assertEquals(-1, empty.read());
            assertEquals(-1, empty.read(new byte[8]));
            assertArrayEquals(new byte[] {91, 92, 93}, next.readAllBytes());
        }
    }

    @Test
    void callerBufferAndInputStreamPreconditionsDoNotChangePosition() throws Exception {
        byte[] content = pattern(3 * CHUNK + 7, 39);
        Fixture fixture = capture(Map.of("a", content));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream input = snapshot.openEntry("a", NEVER)) {
            assertThrows(NullPointerException.class, () -> snapshot.openEntry(null, NEVER));
            assertThrows(NullPointerException.class, () -> snapshot.openEntry("a", null));
            assertThrows(IllegalArgumentException.class, () -> snapshot.openEntry("missing", NEVER));
            assertThrows(NullPointerException.class, () -> input.read(null, 0, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[3], -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[3], 2, 2));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> input.read(new byte[3], 1, Integer.MAX_VALUE));
            assertFalse(input.markSupported());
            input.mark(10);
            assertThrows(IOException.class, input::reset);
            assertEquals(content[0] & 0xff, input.read());
            byte[] largeCallerBuffer = new byte[2 * CHUNK];
            int read = input.read(largeCallerBuffer);
            assertTrue(read > 0, "positive-length reads must make progress");
            assertArrayEquals(Arrays.copyOfRange(content, 1, 1 + read),
                    Arrays.copyOf(largeCallerBuffer, read));
            assertArrayEquals(Arrays.copyOfRange(content, 1 + read, content.length), input.readAllBytes());
        }
    }

    @Test
    void interleavedStreamsAndCopyEntryHaveIndependentPositions() throws Exception {
        byte[] content = pattern(CHUNK + 31, 73);
        byte[] other = pattern(127, 11);
        Fixture fixture = capture(Map.of("a", content, "z", other));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream first = snapshot.openEntry("a", NEVER);
             InputStream second = snapshot.openEntry("a", NEVER);
             InputStream third = snapshot.openEntry("z", NEVER)) {
            assertEquals(content[0] & 0xff, first.read());
            assertArrayEquals(Arrays.copyOf(content, 19), second.readNBytes(19));
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            snapshot.copyEntry("z", copied, NEVER);
            assertArrayEquals(other, copied.toByteArray());
            assertArrayEquals(other, third.readAllBytes());
            assertEquals(content[19] & 0xff, second.read());
            snapshot.copyEntry("a", copied, NEVER);
            assertArrayEquals(content, Arrays.copyOfRange(copied.toByteArray(), other.length, copied.size()));
            assertArrayEquals(Arrays.copyOfRange(content, 1, content.length), first.readAllBytes());
            assertArrayEquals(Arrays.copyOfRange(content, 20, content.length), second.readAllBytes());
        }
    }

    @Test
    void parallelConsumersKeepTheirOwnOffsetsAndDigests() throws Exception {
        byte[] content = pattern(3 * CHUNK + 113, 101);
        Fixture fixture = capture(Map.of("a", content));
        var consumers = Executors.newFixedThreadPool(2);
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream first = snapshot.openEntry("a", NEVER);
             InputStream second = snapshot.openEntry("a", NEVER)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var firstResult = consumers.submit(() -> consume(first, ready, start, 997));
            var secondResult = consumers.submit(() -> consume(second, ready, start, 8191));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertArrayEquals(content, firstResult.get(10, TimeUnit.SECONDS));
            assertArrayEquals(content, secondResult.get(10, TimeUnit.SECONDS));
        } finally {
            consumers.shutdownNow();
            assertTrue(consumers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void eofAndFailedStreamsRetainSlotsUntilExactlyOneClose() throws Exception {
        Fixture fixture = capture(Map.of("a", new byte[] {7}, "empty", new byte[0]));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER)) {
            expectCode(PackSnapshotException.Code.CANCELLED,
                    () -> snapshot.openEntry("a", () -> true));
            List<InputStream> held = new ArrayList<>();
            try {
                InputStream eof = snapshot.openEntry("empty", NEVER);
                held.add(eof);
                assertEquals(-1, eof.read());
                AtomicBoolean cancelled = new AtomicBoolean();
                InputStream failed = snapshot.openEntry("a", cancelled::get);
                held.add(failed);
                cancelled.set(true);
                expectCode(PackSnapshotException.Code.CANCELLED, failed::read);
                for (int i = held.size(); i < 64; i++) held.add(snapshot.openEntry("a", NEVER));
                expectCode(PackSnapshotException.Code.BUDGET, () -> snapshot.openEntry("a", NEVER));
                expectCode(PackSnapshotException.Code.CANCELLED,
                        () -> snapshot.openEntry("a", () -> true));
                eof.close();
                eof.close();
                InputStream replacement = snapshot.openEntry("a", NEVER);
                held.add(replacement);
                expectCode(PackSnapshotException.Code.BUDGET, () -> snapshot.openEntry("a", NEVER));
                failed.close();
                failed.close();
                held.add(snapshot.openEntry("a", NEVER));
                expectCode(PackSnapshotException.Code.BUDGET, () -> snapshot.openEntry("a", NEVER));
            } finally {
                for (InputStream input : held) input.close();
            }
            try (InputStream reclaimed = snapshot.openEntry("a", NEVER)) {
                assertEquals(7, reclaimed.read());
                assertEquals(-1, reclaimed.read());
            }
        }
    }

    @Test
    void earlyCloseDoesNotDrainAuthenticateOrAskForCancellation() throws Exception {
        byte[] content = pattern(CHUNK + 17, 29);
        Fixture fixture = capture(Map.of("a", content));
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger checks = new AtomicInteger();
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER)) {
            InputStream input = snapshot.openEntry("a", () -> {
                checks.incrementAndGet();
                return cancelled.get();
            });
            assertEquals(content[0] & 0xff, input.read());
            overwrite(fixture.archive(), fixture.offset("a") + content.length - 1,
                    (byte) (content[content.length - 1] ^ 1));
            cancelled.set(true);
            int before = checks.get();
            input.close();
            input.close();
            assertEquals(before, checks.get(), "close must not invoke the cancellation callback");
            assertThrows(IOException.class, input::read);
            assertThrows(IOException.class, input::available);
            // The tail really is corrupt; successful early close was not authentication.
            try (InputStream full = snapshot.openEntry("a", NEVER)) {
                expectCode(PackSnapshotException.Code.CONTENT_MISMATCH, full::readAllBytes);
            }
        }
    }

    @Test
    void parentCloseInvalidatesActiveAndEofStreamsButKeepsIndex() throws Exception {
        Fixture fixture = capture(Map.of("a", pattern(73, 5), "empty", new byte[0]));
        PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
        PackIndex index = snapshot.index();
        InputStream active = snapshot.openEntry("a", NEVER);
        InputStream eof = snapshot.openEntry("empty", NEVER);
        try {
            assertTrue(active.read() >= 0);
            assertEquals(-1, eof.read());
            snapshot.close();
            snapshot.close();
            assertEquals(index, snapshot.index());
            assertThrows(IOException.class, active::read);
            assertThrows(IOException.class, () -> active.read(new byte[0]));
            assertThrows(IOException.class, active::available);
            assertThrows(IOException.class, eof::read);
            assertThrows(IOException.class, eof::available);
            assertThrows(IOException.class, () -> snapshot.openEntry("a", NEVER));
            assertThrows(IOException.class, () -> snapshot.copyEntry("a", new ByteArrayOutputStream(), NEVER));
            active.close();
            eof.close();
        } finally {
            active.close();
            eof.close();
            snapshot.close();
        }
        assertTrue(Files.isRegularFile(fixture.archive()));
    }

    @Test
    void readCancellationIsTerminalEvenWhenCallbackLaterResumes() throws Exception {
        Fixture fixture = capture(Map.of("a", pattern(CHUNK + 9, 57)));
        AtomicBoolean cancelled = new AtomicBoolean();
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream input = snapshot.openEntry("a", cancelled::get)) {
            assertTrue(input.read() >= 0);
            cancelled.set(true);
            expectCode(PackSnapshotException.Code.CANCELLED, () -> input.read(new byte[13]));
            cancelled.set(false);
            assertThrows(IOException.class, input::read);
            assertThrows(IOException.class, () -> input.read(new byte[13]));
            try (InputStream fresh = snapshot.openEntry("a", NEVER)) {
                assertEquals(CHUNK + 9, fresh.readAllBytes().length);
            }
        }
    }

    @Test
    void openAndReadInterruptionPreserveFlagAndLeaveReaderUsable() throws Exception {
        Fixture fixture = capture(Map.of("a", pattern(31, 9)));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream input = snapshot.openEntry("a", NEVER)) {
            try {
                Thread.currentThread().interrupt();
                expectCode(PackSnapshotException.Code.CANCELLED,
                        () -> snapshot.openEntry("a", NEVER));
                assertTrue(Thread.currentThread().isInterrupted());
                expectCode(PackSnapshotException.Code.CANCELLED, input::read);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertThrows(IOException.class, input::read);
            try (InputStream fresh = snapshot.openEntry("a", NEVER)) {
                assertEquals(31, fresh.readAllBytes().length);
            }
        }
    }

    @Test
    void sameLengthMutationFailsBeforeReturningFinalBatchAndNeverBecomesEof() throws Exception {
        byte[] content = pattern(CHUNK + 37, 67);
        Fixture fixture = capture(Map.of("a", content, "z", new byte[] {99}));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream input = snapshot.openEntry("a", NEVER)) {
            byte[] prefix = input.readNBytes(CHUNK);
            assertArrayEquals(Arrays.copyOf(content, CHUNK), prefix);
            long length = Files.size(fixture.archive());
            overwrite(fixture.archive(), fixture.offset("a") + content.length - 1,
                    (byte) (content[content.length - 1] ^ 1));
            assertEquals(length, Files.size(fixture.archive()));
            expectCode(PackSnapshotException.Code.CONTENT_MISMATCH,
                    () -> input.read(new byte[37]));
            assertThrows(IOException.class, input::read);
            assertThrows(IOException.class, () -> input.read(new byte[37]));
            try (InputStream next = snapshot.openEntry("z", NEVER)) {
                assertEquals(99, next.read());
                assertEquals(-1, next.read());
            }
        }
    }

    @Test
    void skippedBytesParticipateInDigestAndTruncationIsTerminal() throws Exception {
        byte[] content = pattern(CHUNK + 21, 23);
        Fixture fixture = capture(Map.of("a", content));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream skipped = snapshot.openEntry("a", NEVER);
             InputStream truncated = snapshot.openEntry("a", NEVER)) {
            overwrite(fixture.archive(), fixture.offset("a") + 5, (byte) (content[5] ^ 1));
            assertEquals(CHUNK, skipped.skip(CHUNK));
            expectCode(PackSnapshotException.Code.CONTENT_MISMATCH, skipped::readAllBytes);
            assertThrows(IOException.class, skipped::read);
            // A separate already-open stream sees real EOF before its declared entry size.
            assertTrue(truncated.read() >= 0);
            try (FileChannel writer = FileChannel.open(fixture.archive(), StandardOpenOption.WRITE)) {
                writer.truncate(fixture.offset("a") + 1);
            }
            expectCode(PackSnapshotException.Code.CONTENT_MISMATCH, truncated::read);
            assertThrows(IOException.class, truncated::read);
            assertThrows(IOException.class, () -> truncated.read(new byte[8]));
        }
    }

    @Test
    void secondReadPollCannotConsumeCurrentEntryOrReenterOtherIo() throws Exception {
        byte[] content = new byte[] {11, 12, 13};
        Fixture fixture = capture(Map.of("a", content, "z", new byte[] {91, 92, 93, 94}));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream other = snapshot.openEntry("z", NEVER)) {
            InputStream[] current = new InputStream[1];
            AtomicInteger polls = new AtomicInteger();
            AtomicBoolean attempted = new AtomicBoolean();
            AtomicBoolean selfRejected = new AtomicBoolean();
            BooleanSupplier callback = () -> {
                // The open poll is excluded. On read's second poll the outer read
                // may already hold a remaining/limit calculated from the old position.
                if (current[0] != null && polls.incrementAndGet() == 2
                        && attempted.compareAndSet(false, true)) {
                    try {
                        current[0].readNBytes(content.length);
                    } catch (IOException expected) {
                        selfRejected.set(true);
                    }
                    // Without the guard, let the outer read continue so the
                    // content assertion detects bytes leaked from adjacent z.
                    if (!selfRejected.get()) return false;
                    assertThrows(IOException.class, other::read);
                    assertThrows(IOException.class, () -> other.read(new byte[0]));
                    assertThrows(IOException.class, () -> {
                        try (InputStream unexpected = snapshot.openEntry("a", NEVER)) {
                            unexpected.available();
                        }
                    });
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    assertThrows(IOException.class, () -> snapshot.copyEntry("z", sink, NEVER));
                    assertEquals(0, sink.size());
                }
                return false;
            };
            try (InputStream input = snapshot.openEntry("a", callback)) {
                current[0] = input;
                byte[] result = new byte[8];
                int count = input.read(result);
                assertTrue(attempted.get(), "the callback must run inside read's second poll");
                assertEquals(content.length, count);
                assertArrayEquals(content, Arrays.copyOf(result, count));
                assertTrue(selfRejected.get(), "recursive draining must be rejected before position moves");
                assertEquals(-1, input.read());
            }
            assertArrayEquals(new byte[] {91, 92, 93, 94}, other.readAllBytes());
        }
    }

    @Test
    void openCallbackRejectsRecursiveIoWithoutMovingExistingStreamOrReservingSlots() throws Exception {
        Fixture fixture = capture(Map.of("a", new byte[] {41, 42}));
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER)) {
            List<InputStream> held = new ArrayList<>();
            try {
                InputStream existing = snapshot.openEntry("a", NEVER);
                held.add(existing);
                InputStream opened = snapshot.openEntry("a", () -> {
                    assertThrows(IOException.class, existing::read);
                    assertThrows(IOException.class, () -> {
                        try (InputStream unexpected = snapshot.openEntry("a", NEVER)) {
                            unexpected.available();
                        }
                    });
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    assertThrows(IOException.class, () -> snapshot.copyEntry("a", sink, NEVER));
                    assertEquals(0, sink.size());
                    return false;
                });
                held.add(opened);
                assertEquals(41, existing.read());
                for (int i = held.size(); i < 64; i++) held.add(snapshot.openEntry("a", NEVER));
                expectCode(PackSnapshotException.Code.BUDGET, () -> snapshot.openEntry("a", NEVER));
            } finally {
                for (InputStream input : held) input.close();
            }
        }
    }

    @Test
    void callbacksMayCloseParentOrCurrentStreamAtEveryReadPoll() throws Exception {
        Fixture fixture = capture(Map.of("a", new byte[] {51, 52, 53}));
        for (int closeAt = 1; closeAt <= 3; closeAt++) {
            int selectedPoll = closeAt;
            for (boolean closeParent : new boolean[] {false, true}) {
                try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
                     InputStream sibling = snapshot.openEntry("a", NEVER)) {
                    InputStream[] current = new InputStream[1];
                    AtomicInteger polls = new AtomicInteger();
                    BooleanSupplier callback = () -> {
                        if (current[0] != null && polls.incrementAndGet() == selectedPoll) {
                            if (closeParent) assertDoesNotThrow(snapshot::close);
                            else assertDoesNotThrow(current[0]::close);
                        }
                        return false;
                    };
                    try (InputStream input = snapshot.openEntry("a", callback)) {
                        current[0] = input;
                        assertThrows(IOException.class, input::read);
                        assertEquals(selectedPoll, polls.get());
                        assertThrows(IOException.class, input::read);
                        assertThrows(IOException.class, input::available);
                    }
                    if (closeParent) assertThrows(IOException.class, sibling::read);
                    else assertArrayEquals(new byte[] {51, 52, 53}, sibling.readAllBytes());
                }
            }
        }
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER)) {
            assertThrows(IOException.class, () -> snapshot.openEntry("a", () -> {
                assertDoesNotThrow(snapshot::close);
                return false;
            }));
            assertThrows(IOException.class, () -> snapshot.openEntry("a", NEVER));
        }
    }

    @Test
    void callbackExceptionsRestoreGuardAndReadFailureRemainsLocal() throws Exception {
        Fixture fixture = capture(Map.of("a", new byte[] {61, 62, 63}));
        IllegalStateException sentinel = new IllegalStateException("fixture callback failure");
        try (PackSnapshot snapshot = PackSnapshots.open(fixture.archive(), NEVER);
             InputStream sibling = snapshot.openEntry("a", NEVER)) {
            assertSame(sentinel, assertThrows(IllegalStateException.class,
                    () -> snapshot.openEntry("a", () -> { throw sentinel; })));
            AtomicBoolean failRead = new AtomicBoolean();
            try (InputStream failed = snapshot.openEntry("a", () -> {
                if (failRead.get()) throw sentinel;
                return false;
            })) {
                failRead.set(true);
                assertSame(sentinel, assertThrows(IllegalStateException.class, failed::read));
                failRead.set(false);
                assertThrows(IOException.class, failed::read);
                assertArrayEquals(new byte[] {61, 62, 63}, sibling.readAllBytes());
                try (InputStream fresh = snapshot.openEntry("a", NEVER)) {
                    assertArrayEquals(new byte[] {61, 62, 63}, fresh.readAllBytes());
                }
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                snapshot.copyEntry("a", sink, NEVER);
                assertArrayEquals(new byte[] {61, 62, 63}, sink.toByteArray());
            }
        }
    }

    private Fixture capture(Map<String, byte[]> entries) throws IOException {
        Path source = Files.createTempDirectory(workspace, "source-");
        for (var entry : entries.entrySet()) {
            Path file = source.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        Path archive = workspace.resolve(source.getFileName() + ".pack");
        PackIndex index = PackSnapshots.captureDirectory(source, archive, NEVER);
        // Consumers use only the captured file; changing the original source must be irrelevant.
        for (String path : entries.keySet()) Files.delete(source.resolve(path));
        return new Fixture(archive, index);
    }

    private static byte[] pattern(int length, int seed) {
        byte[] result = new byte[length];
        new java.util.Random(seed).nextBytes(result);
        return result;
    }

    private static void overwrite(Path archive, long offset, byte value) throws IOException {
        try (FileChannel writer = FileChannel.open(archive, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[] {value});
            while (buffer.hasRemaining()) offset += writer.write(buffer, offset);
        }
    }

    private static byte[] consume(InputStream input, CountDownLatch ready, CountDownLatch start,
                                  int bufferSize) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];
        int read;
        while ((read = input.read(buffer)) != -1) {
            assertTrue(read > 0);
            result.write(buffer, 0, read);
            Thread.yield();
        }
        return result.toByteArray();
    }

    private static void expectCode(PackSnapshotException.Code code, IoAction action) {
        PackSnapshotException failure = assertThrows(PackSnapshotException.class, action::run);
        assertEquals(code, failure.code());
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private record Fixture(Path archive, PackIndex index) {
        long offset(String path) {
            // EDPACK01: fixed header, then all path/size/digest metadata, then payloads.
            long offset = 24;
            for (PackEntry entry : index.entries()) {
                offset += 44 + entry.path().getBytes(StandardCharsets.UTF_8).length;
            }
            for (PackEntry entry : index.entries()) {
                if (entry.path().equals(path)) return offset;
                offset += entry.size();
            }
            throw new IllegalArgumentException(path);
        }
    }
}
