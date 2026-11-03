package net.ptcrys.fpsmatch.editor.core.worldio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Public-API Q56 acceptance checks, using only newly created local temporary worlds. */
class WorldIdentitiesBehaviorTest {
    private static final BooleanSupplier CONTINUE = () -> false;
    private static final FileIdentityProvider IDENTITIES = System.getProperty("os.name").startsWith("Windows")
            ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
    private static final UUID FIXTURE_ID = UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210");
    private static final byte[] FIXTURE = HexFormat.of().parseHex(
            "4544574f524c44310123456789abcdeffedcba9876543210");

    @TempDir
    Path temporary;

    @Test
    void missingReadDoesNotCreatePlatformDirectory() throws Exception {
        Path root = world("missing");
        Files.writeString(root.resolve("unrelated.txt"), "keep");

        expect(WorldIdentityException.Code.MISSING, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));

        assertEquals(List.of("unrelated.txt"), children(root));
        assertEquals("keep", Files.readString(root.resolve("unrelated.txt")));
    }

    @Test
    void missingRecordInExistingPlatformDirectoryDoesNotCreateAnything() throws Exception {
        Path root = world("missing-record");
        Files.createDirectory(platform(root));
        Files.writeString(platform(root).resolve("unrelated"), "keep");

        expect(WorldIdentityException.Code.MISSING, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));

        assertEquals(List.of("unrelated"), children(platform(root)));
        assertEquals("keep", Files.readString(platform(root).resolve("unrelated")));
    }

    @Test
    void firstRegistrationPublishesExactlyTheFrozen24BytesAndCanBeReadAgain() throws Exception {
        Path root = world("first");
        WorldIdentitySnapshot captured = initialize(root);

        byte[] bytes = Files.readAllBytes(identity(root));
        assertEquals(24, bytes.length);
        assertArrayEquals("EDWORLD1".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(bytes, 8));
        ByteBuffer uuidBytes = ByteBuffer.wrap(bytes, 8, 16);
        assertEquals(captured.worldId(), new UUID(uuidBytes.getLong(), uuidBytes.getLong()));
        assertNotEquals(new UUID(0, 0), captured.worldId());
        assertEquals(root.toAbsolutePath().normalize(), captured.root());
        assertEquals(List.of("world.identity"), children(platform(root)));

        WorldIdentitySnapshot reopened = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        assertEquals(captured.worldId(), reopened.worldId());
        WorldIdentities.verify(captured, CONTINUE);
        WorldIdentities.verify(reopened, CONTINUE);
        assertArrayEquals(bytes, Files.readAllBytes(identity(root)));
    }

    @Test
    void readsKnownBigEndianFixtureWithoutChangingIt() throws Exception {
        Path root = fixtureWorld("fixture", FIXTURE);
        Object key = nodeIdentity(identity(root));
        var modified = Files.getLastModifiedTime(identity(root));

        WorldIdentitySnapshot snapshot = WorldIdentities.read(root.resolve("."), IDENTITIES, CONTINUE);

        assertEquals(FIXTURE_ID, snapshot.worldId());
        assertEquals(root.toAbsolutePath().normalize(), snapshot.root());
        WorldIdentities.verify(snapshot, CONTINUE);
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(root)));
        assertEquals(key, nodeIdentity(identity(root)));
        assertEquals(modified, Files.getLastModifiedTime(identity(root)));
    }

    @Test
    void repeatedRegistrationPreservesOriginalRecordAndCapture() throws Exception {
        Path root = world("repeat");
        WorldIdentitySnapshot original = initialize(root);
        byte[] bytes = Files.readAllBytes(identity(root));
        Object key = nodeIdentity(identity(root));

        expect(WorldIdentityException.Code.ALREADY_EXISTS,
                () -> WorldIdentities.initialize(root, IDENTITIES, CONTINUE));

        assertArrayEquals(bytes, Files.readAllBytes(identity(root)));
        assertEquals(key, nodeIdentity(identity(root)));
        assertEquals(List.of("world.identity"), children(platform(root)));
        WorldIdentities.verify(original, CONTINUE);
    }

    static Stream<Arguments> invalidRecords() {
        byte[] magic = FIXTURE.clone();
        magic[0] = 'X';
        byte[] zeroId = FIXTURE.clone();
        Arrays.fill(zeroId, 8, 24, (byte) 0);
        return Stream.of(
                Arguments.of("empty", new byte[0]),
                Arguments.of("short", Arrays.copyOf(FIXTURE, 23)),
                Arguments.of("trailing", Arrays.copyOf(FIXTURE, 25)),
                Arguments.of("magic", magic),
                Arguments.of("zero UUID", zeroId));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRecords")
    void invalidRecordsAreRejectedAndNeverRepaired(String description, byte[] bytes) throws Exception {
        Path root = fixtureWorld("invalid", bytes);
        Object key = nodeIdentity(identity(root));

        expect(WorldIdentityException.Code.CORRUPT, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));
        expect(WorldIdentityException.Code.ALREADY_EXISTS,
                () -> WorldIdentities.initialize(root, IDENTITIES, CONTINUE));

        assertArrayEquals(bytes, Files.readAllBytes(identity(root)), description);
        assertEquals(key, nodeIdentity(identity(root)));
        assertEquals(List.of("world.identity"), children(platform(root)));
    }

    @Test
    void directoryAtRecordPathIsRejectedAndPreserved() throws Exception {
        Path root = world("directory-record");
        Files.createDirectories(identity(root));
        Files.writeString(identity(root).resolve("keep"), "original");

        assertThrows(WorldIdentityException.class, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));
        expect(WorldIdentityException.Code.ALREADY_EXISTS,
                () -> WorldIdentities.initialize(root, IDENTITIES, CONTINUE));

        assertEquals("original", Files.readString(identity(root).resolve("keep")));
        assertEquals(List.of("world.identity"), children(platform(root)));
    }

    @Test
    void absentAndFileRootsAreInvalidAndNeverCreatedOrModified() throws Exception {
        Path absent = temporary.resolve("absent");
        Path file = Files.writeString(temporary.resolve("file-root"), "original");
        for (Path root : List.of(absent, file)) {
            expect(WorldIdentityException.Code.INVALID_ROOT, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));
            expect(WorldIdentityException.Code.INVALID_ROOT,
                    () -> WorldIdentities.initialize(root, IDENTITIES, CONTINUE));
        }
        assertFalse(Files.exists(absent));
        assertEquals("original", Files.readString(file));
    }

    @Test
    void fileAtPlatformPathIsRejectedWithoutChangingIt() throws Exception {
        Path root = world("platform-file");
        Files.writeString(platform(root), "original");
        assertThrows(WorldIdentityException.class, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));
        assertThrows(WorldIdentityException.class, () -> WorldIdentities.initialize(root, IDENTITIES, CONTINUE));
        assertEquals("original", Files.readString(platform(root)));
    }

    @Test
    void sameNamedWorldsAtDifferentRootsHaveIndependentIdentities() throws Exception {
        Path first = Files.createDirectories(temporary.resolve("a/world"));
        Path second = Files.createDirectories(temporary.resolve("b/world"));
        WorldIdentitySnapshot a = initialize(first);
        WorldIdentitySnapshot b = initialize(second);

        assertNotEquals(a.worldId(), b.worldId());
        assertNotEquals(a.root(), b.root());
        WorldIdentities.verify(a, CONTINUE);
        WorldIdentities.verify(b, CONTINUE);
    }

    @Test
    void fullCopyPreservesLineageButCannotReplaceTheCapturedPhysicalRoot() throws Exception {
        Path root = fixtureWorld("source", FIXTURE);
        Files.createDirectories(root.resolve("terrain"));
        Files.writeString(root.resolve("terrain/fixture.txt"), "copied world data");
        WorldIdentitySnapshot original = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        Path copy = temporary.resolve("copy");
        copyTree(root, copy);
        WorldIdentitySnapshot copied = WorldIdentities.read(copy, IDENTITIES, CONTINUE);
        assertEquals(original.worldId(), copied.worldId());
        assertNotEquals(original.root(), copied.root());
        assertEquals("copied world data", Files.readString(copy.resolve("terrain/fixture.txt")));
        WorldIdentities.verify(original, CONTINUE);
        WorldIdentities.verify(copied, CONTINUE);

        Path retained = temporary.resolve("retained-source");
        Files.move(root, retained);
        Files.move(copy, root);

        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(original, CONTINUE));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(root)));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(retained)));
        WorldIdentitySnapshot newlyCaptured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        assertEquals(original.worldId(), newlyCaptured.worldId());
        WorldIdentities.verify(newlyCaptured, CONTINUE);
    }

    @Test
    void sameByteFileReplacementInvalidatesCaptureEvenWhenTimestampMatches() throws Exception {
        Path root = fixtureWorld("replace-file", FIXTURE);
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        Object originalKey = nodeIdentity(identity(root));
        var modified = Files.getLastModifiedTime(identity(root));
        Path retained = platform(root).resolve("retained-record");
        Files.move(identity(root), retained);
        Files.write(identity(root), FIXTURE);
        Files.setLastModifiedTime(identity(root), modified);
        assertNotEquals(originalKey, nodeIdentity(identity(root)));

        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(captured, CONTINUE));

        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(root)));
        assertArrayEquals(FIXTURE, Files.readAllBytes(retained));
    }

    @Test
    void replacingOnlyPlatformDirectoryInvalidatesCapture() throws Exception {
        Path root = fixtureWorld("replace-platform", FIXTURE);
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        Path retained = root.resolve("retained-platform");
        Files.move(platform(root), retained);
        Files.createDirectory(platform(root));
        // Preserve the actual identity-file node so the changed directory is independently decisive.
        Files.move(retained.resolve("world.identity"), identity(root));

        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(captured, CONTINUE));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(root)));
        assertTrue(Files.isDirectory(retained));
    }

    @Test
    void replacingOnlyRootInvalidatesCapture() throws Exception {
        Path root = fixtureWorld("replace-root", FIXTURE);
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        Path retained = temporary.resolve("retained-root");
        Files.move(root, retained);
        Files.createDirectory(root);
        // Preserve both inner nodes so the changed root is independently decisive.
        Files.move(platform(retained), platform(root));

        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(captured, CONTINUE));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(root)));
        assertTrue(Files.isDirectory(retained));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRecords")
    void corruptingCapturedRecordIsChangedRatherThanFreshReadCorrupt(String description, byte[] bytes)
            throws Exception {
        Path root = fixtureWorld("changed", FIXTURE);
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        Files.write(identity(root), bytes);
        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(captured, CONTINUE));
        assertArrayEquals(bytes, Files.readAllBytes(identity(root)), description);
    }

    @Test
    void missingCapturedRecordIsChangedAndIsNotRecreated() throws Exception {
        Path root = fixtureWorld("deleted", FIXTURE);
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        Files.delete(identity(root));
        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(captured, CONTINUE));
        assertTrue(children(platform(root)).isEmpty());
    }

    @Test
    void validButDifferentUuidInTheSameFileInvalidatesCapture() throws Exception {
        Path root = fixtureWorld("changed-uuid", FIXTURE);
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        Object key = nodeIdentity(identity(root));
        byte[] different = FIXTURE.clone();
        different[23] ^= 1;
        Files.write(identity(root), different);

        assertEquals(key, nodeIdentity(identity(root)));
        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(captured, CONTINUE));
        assertNotEquals(captured.worldId(), WorldIdentities.read(root, IDENTITIES, CONTINUE).worldId());
        assertArrayEquals(different, Files.readAllBytes(identity(root)));
    }

    @Test
    void missingCapturedDirectoryAndRootAreChangedWithoutRecreation() throws Exception {
        Path first = fixtureWorld("missing-platform", FIXTURE);
        WorldIdentitySnapshot firstCapture = WorldIdentities.read(first, IDENTITIES, CONTINUE);
        Path retainedPlatform = first.resolve("retained-platform");
        Files.move(platform(first), retainedPlatform);
        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(firstCapture, CONTINUE));
        assertFalse(Files.exists(platform(first), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(FIXTURE, Files.readAllBytes(retainedPlatform.resolve("world.identity")));

        Path second = fixtureWorld("missing-root", FIXTURE);
        WorldIdentitySnapshot secondCapture = WorldIdentities.read(second, IDENTITIES, CONTINUE);
        Path retainedRoot = temporary.resolve("retained-missing-root");
        Files.move(second, retainedRoot);
        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.verify(secondCapture, CONTINUE));
        assertFalse(Files.exists(second, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(retainedRoot)));
    }

    @Test
    void concurrentFirstRegistrationsPublishExactlyOneRecord() throws Exception {
        Path root = world("concurrent");
        requireHardLinks(root);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = workers.submit(() -> raceInitialize(root, ready, start));
            Future<Object> b = workers.submit(() -> raceInitialize(root, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Object> outcomes = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(WorldIdentitySnapshot.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(WorldIdentityException.class::isInstance).count());
            WorldIdentitySnapshot winner = outcomes.stream().filter(WorldIdentitySnapshot.class::isInstance)
                    .map(WorldIdentitySnapshot.class::cast).findFirst().orElseThrow();
            WorldIdentityException loser = outcomes.stream().filter(WorldIdentityException.class::isInstance)
                    .map(WorldIdentityException.class::cast).findFirst().orElseThrow();
            assertEquals(WorldIdentityException.Code.ALREADY_EXISTS, loser.code());
            assertEquals(winner.worldId(), WorldIdentities.read(root, IDENTITIES, CONTINUE).worldId());
            WorldIdentities.verify(winner, CONTINUE);
            assertEquals(24, Files.size(identity(root)));
            assertEquals(List.of("world.identity"), children(platform(root)));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationBeforeAnyIoLeavesWorldUntouched() throws Exception {
        Path root = world("cancel-before");
        expect(WorldIdentityException.Code.CANCELLED, () -> WorldIdentities.read(root, IDENTITIES, () -> true));
        expect(WorldIdentityException.Code.CANCELLED, () -> WorldIdentities.initialize(root, IDENTITIES, () -> true));
        assertTrue(children(root).isEmpty());
    }

    @Test
    void cancellationAfterRealTemporaryWriteDoesNotPublishAndCleansOnlyItsFile() throws Exception {
        Path root = world("cancel-written");
        requireHardLinks(root);
        Files.createDirectory(platform(root));
        Path unrelated = Files.writeString(platform(root).resolve("unrelated"), "keep");
        AtomicBoolean sawWrittenTemporary = new AtomicBoolean();
        BooleanSupplier cancelWhenWritten = () -> {
            boolean written = hasCompleteTemporary(root, unrelated);
            if (written) {
                sawWrittenTemporary.set(true);
            }
            return written;
        };

        expect(WorldIdentityException.Code.CANCELLED,
                () -> WorldIdentities.initialize(root, IDENTITIES, cancelWhenWritten));

        assertTrue(sawWrittenTemporary.get(), "Cancellation must observe a real complete temporary record");
        assertFalse(Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of("unrelated"), children(platform(root)));
        assertEquals("keep", Files.readString(unrelated));
    }

    @Test
    void cancellationAsSoonAsTemporaryExistsCleansItBeforeReturning() throws Exception {
        Path root = world("cancel-created");
        requireHardLinks(root);
        AtomicBoolean sawTemporary = new AtomicBoolean();

        expect(WorldIdentityException.Code.CANCELLED, () -> WorldIdentities.initialize(root, IDENTITIES, () -> {
            boolean exists = hasTemporary(root);
            if (exists) {
                sawTemporary.set(true);
            }
            return exists;
        }));

        assertTrue(sawTemporary.get(), "Cancellation must observe an actual temporary filesystem node");
        assertFalse(Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS));
        assertTrue(children(platform(root)).isEmpty());
    }

    static Stream<RuntimeException> supplierFailures() {
        return Stream.of(new IllegalStateException("supplier failure"),
                new UnsupportedOperationException("supplier unsupported"),
                new SecurityException("supplier security"));
    }

    @ParameterizedTest
    @MethodSource("supplierFailures")
    void supplierRuntimeExceptionOnceTemporaryExistsEscapesAndCleansTemporary(RuntimeException sentinel)
            throws Exception {
        Path root = world("supplier-throws");
        requireHardLinks(root);
        AtomicBoolean sawTemporary = new AtomicBoolean();
        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> WorldIdentities.initialize(root, IDENTITIES, () -> {
                    if (hasTemporary(root)) {
                        sawTemporary.set(true);
                        throw sentinel;
                    }
                    return false;
                }));

        assertSame(sentinel, actual);
        assertTrue(sawTemporary.get());
        assertFalse(Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS));
        assertTrue(children(platform(root)).isEmpty());
    }

    @Test
    void cancellationSupplierIsNeverConsultedAfterPublication() throws Exception {
        Path root = world("published");
        requireHardLinks(root);
        WorldIdentitySnapshot snapshot = WorldIdentities.initialize(root, IDENTITIES, () -> {
            if (Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS)) {
                throw new AssertionError("Cancellation supplier called after formal record publication");
            }
            return false;
        });
        WorldIdentities.verify(snapshot, CONTINUE);
        assertEquals(List.of("world.identity"), children(platform(root)));
    }

    @Test
    void readRejectsValidContentChangedByFinalCallbackEvenWithOriginalModificationTime() throws Exception {
        Path root = fixtureWorld("read-final-callback", FIXTURE);
        Path file = identity(root);
        FileNodeIdentity originalNode = IDENTITIES.identity(file);
        var originalModified = Files.getLastModifiedTime(file);
        byte[] different = FIXTURE.clone();
        different[23] ^= 1;
        AtomicInteger actualFileChecks = new AtomicInteger();
        AtomicBoolean changed = new AtomicBoolean();
        FileIdentityProvider observed = path -> {
            FileNodeIdentity real = IDENTITIES.identity(path);
            if (path.equals(file)) {
                actualFileChecks.incrementAndGet();
            }
            return real;
        };

        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.read(root, observed, () -> {
            // Two real identity queries witness the read's before/after metadata checks.
            if (actualFileChecks.get() >= 2 && changed.compareAndSet(false, true)) {
                replaceBytesRestoringModificationTime(file, different);
            }
            return false;
        }));

        assertTrue(changed.get(), "The mutation must follow both real file-identity checks");
        assertEquals(originalNode, IDENTITIES.identity(file));
        assertEquals(originalModified, Files.getLastModifiedTime(file));
        assertArrayEquals(different, Files.readAllBytes(file));
        assertNotEquals(FIXTURE_ID, WorldIdentities.read(root, IDENTITIES, CONTINUE).worldId());
    }

    @Test
    void providerFailureAfterPublicationIsUnknownAndRetainsReadableFormalRecord() throws Exception {
        Path root = world("provider-after-publication");
        requireHardLinks(root);
        IOException sentinel = new IOException("formal record identity check failed");
        AtomicBoolean faultObserved = new AtomicBoolean();
        FileIdentityProvider failing = path -> {
            FileNodeIdentity real = IDENTITIES.identity(path);
            if (path.equals(identity(root))) {
                assertTrue(Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS));
                faultObserved.set(true);
                throw sentinel;
            }
            return real;
        };

        expect(WorldIdentityException.Code.UNKNOWN,
                () -> WorldIdentities.initialize(root, failing, CONTINUE));

        assertTrue(faultObserved.get());
        assertEquals(24, Files.size(identity(root)));
        byte[] preserved = Files.readAllBytes(identity(root));
        WorldIdentitySnapshot reconciled = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        WorldIdentities.verify(reconciled, CONTINUE);
        assertArrayEquals(preserved, Files.readAllBytes(identity(root)));
        assertEquals(List.of("world.identity"), children(platform(root)));
    }

    @Test
    void firstTemporaryIdentityFailureIsIoFailureWithNoFormalPublication() throws Exception {
        Path root = world("provider-first-temporary");
        requireHardLinks(root);
        IOException sentinel = new IOException("initial temporary identity query failed");
        AtomicReference<Path> faultedTemporary = new AtomicReference<>();
        FileIdentityProvider failing = path -> {
            FileNodeIdentity real = IDENTITIES.identity(path);
            if (isTemporaryNode(root, path) && faultedTemporary.compareAndSet(null, path)) {
                throw sentinel;
            }
            return real;
        };

        expect(WorldIdentityException.Code.IO_FAILURE,
                () -> WorldIdentities.initialize(root, failing, CONTINUE));

        Path observed = faultedTemporary.get();
        assertNotNull(observed, "Fault must occur on a real temporary node's first identity query");
        assertFalse(Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS));
        expect(WorldIdentityException.Code.MISSING, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));
        // A failed first ownership query permits residue; cleanup must never assume ownership.
        if (Files.exists(observed, LinkOption.NOFOLLOW_LINKS)) {
            assertTrue(Files.isRegularFile(observed, LinkOption.NOFOLLOW_LINKS));
            assertNotNull(IDENTITIES.identity(observed));
            assertEquals(List.of(observed.getFileName().toString()), children(platform(root)));
        } else {
            assertTrue(children(platform(root)).isEmpty());
        }
    }

    @Test
    void supplierExceptionRetainsItsIdentityAndSuppressesCleanupIoFailure() throws Exception {
        Path root = world("supplier-and-cleanup-fail");
        requireHardLinks(root);
        IllegalStateException supplierFailure = new IllegalStateException("supplier failed with temporary visible");
        IOException cleanupFailure = new IOException("temporary ownership recheck failed during cleanup");
        AtomicBoolean supplierThrew = new AtomicBoolean();
        AtomicBoolean cleanupFaultObserved = new AtomicBoolean();
        AtomicReference<Path> ownedTemporary = new AtomicReference<>();
        FileIdentityProvider failingCleanup = path -> {
            FileNodeIdentity real = IDENTITIES.identity(path);
            if (isTemporaryNode(root, path)) {
                ownedTemporary.compareAndSet(null, path);
                if (supplierThrew.get()) {
                    cleanupFaultObserved.set(true);
                    throw cleanupFailure;
                }
            }
            return real;
        };

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> WorldIdentities.initialize(root, failingCleanup, () -> {
                    if (hasTemporary(root)) {
                        supplierThrew.set(true);
                        throw supplierFailure;
                    }
                    return false;
                }));

        assertSame(supplierFailure, actual);
        assertTrue(cleanupFaultObserved.get());
        assertTrue(Arrays.asList(actual.getSuppressed()).contains(cleanupFailure),
                "The original supplier exception must retain the same cleanup exception as suppressed evidence");
        Path retained = ownedTemporary.get();
        assertNotNull(retained);
        assertTrue(Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS));
        assertNotNull(IDENTITIES.identity(retained));
        assertFalse(Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of(retained.getFileName().toString()), children(platform(root)));
        expect(WorldIdentityException.Code.MISSING, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));
    }

    @Test
    void changingCompleteTemporaryContentInCallbackRejectsAndCleansOwnedTemporary() throws Exception {
        Path root = world("changed-complete-temporary");
        requireHardLinks(root);
        AtomicReference<Path> changedTemporary = new AtomicReference<>();

        expect(WorldIdentityException.Code.CHANGED, () -> WorldIdentities.initialize(root, IDENTITIES, () -> {
            Path complete = completeTemporary(root);
            if (complete != null && changedTemporary.compareAndSet(null, complete)) {
                byte[] different = FIXTURE.clone();
                try {
                    if (Arrays.equals(different, Files.readAllBytes(complete))) {
                        different[23] ^= 1;
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                replaceBytesRestoringModificationTime(complete, different);
            }
            return false;
        }));

        assertNotNull(changedTemporary.get(), "Mutation must observe the full real 24-byte temporary record");
        assertFalse(Files.exists(identity(root), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(changedTemporary.get(), LinkOption.NOFOLLOW_LINKS));
        assertTrue(children(platform(root)).isEmpty());
        expect(WorldIdentityException.Code.MISSING, () -> WorldIdentities.read(root, IDENTITIES, CONTINUE));
    }

    @Test
    void readAndVerifyDoNotSwallowSupplierRuntimeExceptions() throws Exception {
        Path root = fixtureWorld("runtime-read", FIXTURE);
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        IllegalArgumentException sentinel = new IllegalArgumentException("supplier failure");
        BooleanSupplier throwing = () -> { throw sentinel; };
        assertSame(sentinel, assertThrows(IllegalArgumentException.class,
                () -> WorldIdentities.read(root, IDENTITIES, throwing)));
        assertSame(sentinel, assertThrows(IllegalArgumentException.class,
                () -> WorldIdentities.verify(captured, throwing)));
        expect(WorldIdentityException.Code.CANCELLED,
                () -> WorldIdentities.verify(captured, () -> true));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(root)));
    }

    @Test
    void symbolicRootAndAncestorAreRejectedWithoutTouchingTarget() throws Exception {
        Path target = fixtureWorld("link-target", FIXTURE);
        Path rootLink = temporary.resolve("root-link");
        createSymbolicLinkOrSkip(rootLink, target);
        assertThrows(WorldIdentityException.class, () -> WorldIdentities.read(rootLink, IDENTITIES, CONTINUE));
        assertThrows(WorldIdentityException.class, () -> WorldIdentities.initialize(rootLink, IDENTITIES, CONTINUE));

        Path parent = Files.createDirectory(temporary.resolve("parent"));
        Path inner = fixtureWorldUnder(parent.resolve("inner"), FIXTURE);
        Path ancestorLink = temporary.resolve("ancestor-link");
        createSymbolicLinkOrSkip(ancestorLink, parent);
        assertThrows(WorldIdentityException.class,
                () -> WorldIdentities.read(ancestorLink.resolve("inner"), IDENTITIES, CONTINUE));
        assertThrows(WorldIdentityException.class,
                () -> WorldIdentities.initialize(ancestorLink.resolve("inner"), IDENTITIES, CONTINUE));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(target)));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(inner)));
    }

    @Test
    void symbolicPlatformAndIdentityAreRejectedWithoutTouchingTarget() throws Exception {
        Path target = fixtureWorld("inner-link-target", FIXTURE);
        Path linkedPlatformRoot = world("linked-platform");
        createSymbolicLinkOrSkip(platform(linkedPlatformRoot), platform(target));
        assertThrows(WorldIdentityException.class,
                () -> WorldIdentities.read(linkedPlatformRoot, IDENTITIES, CONTINUE));
        assertThrows(WorldIdentityException.class,
                () -> WorldIdentities.initialize(linkedPlatformRoot, IDENTITIES, CONTINUE));

        Path linkedRecordRoot = world("linked-record");
        Files.createDirectory(platform(linkedRecordRoot));
        createSymbolicLinkOrSkip(identity(linkedRecordRoot), identity(target));
        assertThrows(WorldIdentityException.class, () -> WorldIdentities.read(linkedRecordRoot, IDENTITIES, CONTINUE));
        assertThrows(WorldIdentityException.class,
                () -> WorldIdentities.initialize(linkedRecordRoot, IDENTITIES, CONTINUE));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(target)));
        assertEquals(List.of("world.identity"), children(platform(target)));
    }

    @Test
    void nullArgumentsAreRejected() throws Exception {
        Path root = fixtureWorld("nulls", FIXTURE);
        WorldIdentitySnapshot snapshot = WorldIdentities.read(root, IDENTITIES, CONTINUE);
        assertThrows(NullPointerException.class, () -> WorldIdentities.read(null, IDENTITIES, CONTINUE));
        assertThrows(NullPointerException.class, () -> WorldIdentities.read(root, IDENTITIES, null));
        assertThrows(NullPointerException.class, () -> WorldIdentities.initialize(null, IDENTITIES, CONTINUE));
        assertThrows(NullPointerException.class, () -> WorldIdentities.initialize(root, IDENTITIES, null));
        assertThrows(NullPointerException.class, () -> WorldIdentities.read(root, null, CONTINUE));
        assertThrows(NullPointerException.class, () -> WorldIdentities.initialize(root, null, CONTINUE));
        assertThrows(NullPointerException.class, () -> WorldIdentities.read(null, CONTINUE));
        assertThrows(NullPointerException.class, () -> WorldIdentities.read(root, null));
        assertThrows(NullPointerException.class, () -> WorldIdentities.initialize(null, CONTINUE));
        assertThrows(NullPointerException.class, () -> WorldIdentities.initialize(root, null));
        assertThrows(NullPointerException.class, () -> WorldIdentities.verify(null, CONTINUE));
        assertThrows(NullPointerException.class, () -> WorldIdentities.verify(snapshot, null));
        assertArrayEquals(FIXTURE, Files.readAllBytes(identity(root)));
    }

    @Test
    void defaultOverloadCannotSilentlyUseNativeProviderWhenJdkFileKeyIsMissing() throws Exception {
        Path root = world("jdk-unsupported");
        Object jdkKey = Files.readAttributes(root, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        assumeTrue(jdkKey == null,
                "This negative capability case requires an actual JDK null fileKey; current provider supplies one");

        expect(WorldIdentityException.Code.UNSUPPORTED, () -> WorldIdentities.read(root, CONTINUE));
        expect(WorldIdentityException.Code.UNSUPPORTED, () -> WorldIdentities.initialize(root, CONTINUE));

        assertTrue(children(root).isEmpty());
    }

    @Test
    void fileNodeIdentityHasImmutableValueSemanticsAndExactFieldBudgets() {
        FileNodeIdentity value = new FileNodeIdentity("native-provider", "volume-and-file-id");
        FileNodeIdentity equal = new FileNodeIdentity("native-provider", "volume-and-file-id");
        assertEquals("native-provider", value.provider());
        assertEquals("volume-and-file-id", value.key());
        assertEquals(value, equal);
        assertEquals(value.hashCode(), equal.hashCode());
        assertNotEquals(value, new FileNodeIdentity("other-provider", value.key()));
        assertNotEquals(value, new FileNodeIdentity(value.provider(), "other-node"));
        FileNodeIdentity maximum = new FileNodeIdentity("p".repeat(128), "k".repeat(1024));
        assertEquals(128, maximum.provider().length());
        assertEquals(1024, maximum.key().length());
        assertThrows(NullPointerException.class, () -> new FileNodeIdentity(null, "key"));
        assertThrows(NullPointerException.class, () -> new FileNodeIdentity("provider", null));
        assertThrows(IllegalArgumentException.class, () -> new FileNodeIdentity("", "key"));
        assertThrows(IllegalArgumentException.class, () -> new FileNodeIdentity("provider", ""));
        assertThrows(IllegalArgumentException.class, () -> new FileNodeIdentity("p".repeat(129), "key"));
        assertThrows(IllegalArgumentException.class, () -> new FileNodeIdentity("provider", "k".repeat(1025)));
    }

    private Path world(String name) throws IOException {
        return Files.createDirectory(temporary.resolve(name));
    }

    private Path fixtureWorld(String name, byte[] bytes) throws IOException {
        return fixtureWorldUnder(temporary.resolve(name), bytes);
    }

    private static Path fixtureWorldUnder(Path root, byte[] bytes) throws IOException {
        Files.createDirectories(platform(root));
        Files.write(identity(root), bytes);
        return root;
    }

    private WorldIdentitySnapshot initialize(Path root) throws Exception {
        requireHardLinks(root);
        return WorldIdentities.initialize(root, IDENTITIES, CONTINUE);
    }

    private static Path platform(Path root) {
        return root.resolve(".editor-platform");
    }

    private static Path identity(Path root) {
        return platform(root).resolve("world.identity");
    }

    private static List<String> children(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static FileNodeIdentity nodeIdentity(Path path) throws IOException {
        FileNodeIdentity key = IDENTITIES.identity(path);
        assertNotNull(key, "The selected real backend must provide a stable physical node identity");
        return key;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private static Object raceInitialize(Path root, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent initialization start was not released");
        }
        try {
            return WorldIdentities.initialize(root, IDENTITIES, CONTINUE);
        } catch (WorldIdentityException failure) {
            return failure;
        }
    }

    private static boolean hasCompleteTemporary(Path root, Path unrelated) {
        if (!Files.isDirectory(platform(root), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> paths = Files.list(platform(root))) {
            for (Path path : paths.toList()) {
                if (!path.equals(identity(root)) && !path.equals(unrelated)
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == 24) {
                    return true;
                }
            }
            return false;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static boolean hasTemporary(Path root) {
        if (!Files.isDirectory(platform(root), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> paths = Files.list(platform(root))) {
            return paths.anyMatch(path -> !path.equals(identity(root)));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static boolean isTemporaryNode(Path root, Path path) {
        return platform(root).equals(path.getParent()) && !path.equals(identity(root));
    }

    private static Path completeTemporary(Path root) {
        if (!Files.isDirectory(platform(root), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(platform(root))) {
            for (Path path : paths.toList()) {
                if (isTemporaryNode(root, path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(path) == 24) {
                    return path;
                }
            }
            return null;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void replaceBytesRestoringModificationTime(Path path, byte[] bytes) {
        try {
            var modified = Files.getLastModifiedTime(path);
            Files.write(path, bytes);
            Files.setLastModifiedTime(path, modified);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void requireHardLinks(Path root) throws IOException {
        Path probe = Files.createTempFile(temporary, "hardlink-source-", ".probe");
        Path link = root.resolve("hardlink-capability.probe");
        try {
            try {
                Files.createLink(link, probe);
            } catch (UnsupportedOperationException | IOException failure) {
                assumeTrue(false, "Local provider/OS refused real hard-link capability probe: " + failure);
            }
            assertTrue(Files.isSameFile(probe, link));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(probe);
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException failure) {
            assumeTrue(false, "Local provider/OS refused symbolic-link creation at " + link + ": " + failure);
        }
    }

    private static WorldIdentityException expect(WorldIdentityException.Code code, IoAction action) {
        WorldIdentityException failure = assertThrows(WorldIdentityException.class, action::run);
        assertEquals(code, failure.code());
        return failure;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
