package net.ptcrys.fpsmatch.editor.core.installation;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.StoreLimits;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProviders;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Durable ownership checks against real filesystem identities; every world is disposable @TempDir data. */
class LocalInstallationIndexTest {
    private static final FileIdentityProvider IDENTITIES = System.getProperty("os.name").startsWith("Windows")
            ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
    private static final byte[] ARCHIVE = {0, 1, 2, -1, 0, 80, 75, 13, 10};
    private static final byte[] REPLACEMENT_ARCHIVE = {80, 75, 3, 4, 12, 42, -8, 0, 9, 17};
    private static final InstallationPlan.Key KEY = new InstallationPlan.Key("cs", "Exact Name");

    @TempDir Path temporary;

    @Test
    void neverInitializedWorldIsAbsentWithoutCreatingWorldIdentityOrIndex() throws Exception {
        Path world = Files.createDirectory(temporary.resolve("unregistered"));
        Files.writeString(world.resolve("unrelated"), "retain");
        Map<String, String> before = files(world);

        assertTrue(LocalInstallationIndex.openIfPresent(world, IDENTITIES).isEmpty());
        assertEquals(before, files(world));
        assertFalse(Files.exists(platform(world)));
    }

    @Test
    void registeredWorldWithoutEitherOwnershipNodeIsAbsentWithoutWriting() throws Exception {
        Path world = world();
        Files.writeString(platform(world).resolve("unrelated"), "retain");
        Map<String, String> before = files(world);

        assertTrue(LocalInstallationIndex.openIfPresent(world, IDENTITIES).isEmpty());
        assertEquals(before, files(world));
    }

    @Test
    void initializationRequiresPriorExplicitWorldRegistration() throws Exception {
        Path world = Files.createDirectory(temporary.resolve("unregistered"));

        WorldIdentityException failure = assertThrows(WorldIdentityException.class,
                () -> LocalInstallationIndex.initialize(world, IDENTITIES));

        assertEquals(WorldIdentityException.Code.MISSING, failure.code());
        assertFalse(Files.exists(platform(world)));
    }

    @Test
    void reservationRetainsExactProvenanceAndActualBytesAcrossActivationAndReopen() throws Exception {
        Path world = world();
        InstallationPlan plan = plan(UUID.randomUUID(), KEY);
        UUID request = UUID.randomUUID();
        UUID activation = UUID.randomUUID();
        byte[] input = ARCHIVE.clone();
        LocalInstallationIndex.Record reserved;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            assertEquals(WorldIdentities.read(world, IDENTITIES, () -> false).worldId(), index.worldId());
            assertTrue(index.records().isEmpty());
            reserved = index.reserve(index.currentCommitId(), request, plan, new ByteArrayInputStream(input));
            Arrays.fill(input, (byte) 42);
            assertEquals(LocalInstallationIndex.State.PREPARED, reserved.state());
            assertEquals(plan, reserved.plan());
            assertEquals(ARCHIVE.length, reserved.bundle().size());
            assertEquals(BlobRef.Kind.ASSET, reserved.bundle().kind());
            assertEquals(request, index.currentCommitId());
            assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, index.lookup(request).status());
            assertThrows(UnsupportedOperationException.class, () -> index.records().clear());
            LocalInstallationIndex.Record active = index.transition(request, activation, KEY, LocalInstallationIndex.State.ACTIVE);
            assertEquals(reserved.plan(), active.plan());
            assertEquals(reserved.bundle(), active.bundle());
            assertEquals(LocalInstallationIndex.State.ACTIVE, active.state());
        }
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            reopened.verify();
            assertEquals(activation, reopened.currentCommitId());
            assertEquals(List.of(KEY), List.copyOf(reopened.records().keySet()));
            assertEquals(plan, reopened.records().get(KEY).plan());
            assertEquals(reserved.bundle(), reopened.records().get(KEY).bundle());
            assertEquals(LocalInstallationIndex.State.ACTIVE, reopened.records().get(KEY).state());
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            reopened.copyBundle(KEY, copied);
            assertArrayEquals(ARCHIVE, copied.toByteArray());
            assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, reopened.lookup(request).status());
        }
    }

    @Test
    void exactKeyAndInstallationIdAreIndependentlyUniqueWithoutLosingExistingReservation() throws Exception {
        Path world = world();
        InstallationPlan first = plan(UUID.randomUUID(), KEY);
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            reserve(index, first);
            UUID head = index.currentCommitId();
            expect(ProjectStorageException.Code.CONFLICT, () -> reserve(index, plan(UUID.randomUUID(), KEY)));
            expect(ProjectStorageException.Code.CONFLICT, () -> reserve(index,
                    plan(first.installationId(), new InstallationPlan.Key("dm", "Different"))));
            assertEquals(head, index.currentCommitId());
            assertEquals(first, index.records().get(KEY).plan());
            // Type and spelling are part of the key, with no case folding or cross-type name collision.
            reserve(index, plan(UUID.randomUUID(), new InstallationPlan.Key("dm", KEY.name())));
            reserve(index, plan(UUID.randomUUID(), new InstallationPlan.Key(KEY.type(), "exact name")));
            assertEquals(3, index.records().size());
        }
    }

    @Test
    void staleCasCannotReportPublicationOrExposeAPermissiveSnapshot() throws Exception {
        Path world = world();
        InstallationPlan first = plan(UUID.randomUUID(), KEY);
        InstallationPlan rejected = plan(UUID.randomUUID(), new InstallationPlan.Key("dm", "Rejected"));
        UUID rejectedRequest = UUID.randomUUID();
        UUID committed;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            UUID stale = index.currentCommitId();
            reserve(index, first);
            committed = index.currentCommitId();
            expect(ProjectStorageException.Code.CONFLICT, () -> index.reserve(stale, rejectedRequest, rejected,
                    new ByteArrayInputStream(ARCHIVE)));
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(rejectedRequest).status());
            assertThrows(IllegalStateException.class, index::records);
            assertThrows(IllegalStateException.class, index::currentCommitId);
            assertThrows(IllegalStateException.class, index::verify);
        }
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(committed, reopened.currentCommitId());
            assertEquals(Map.of(KEY, first), Map.of(KEY, reopened.records().get(KEY).plan()));
            assertEquals(1, reopened.records().size());
            assertFalse(reopened.records().containsKey(rejected.key()));
        }
    }

    @Test
    void recoveryRequiredRetainsOwnershipAndArchiveAndCannotBeClearedByTransition() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            LocalInstallationIndex.Record prepared = reserve(index, plan(UUID.randomUUID(), KEY));
            expect(ProjectStorageException.Code.CONFLICT, () -> index.transition(index.currentCommitId(), UUID.randomUUID(),
                    KEY, LocalInstallationIndex.State.PREPARED));
            index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, LocalInstallationIndex.State.ACTIVE);
            index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, LocalInstallationIndex.State.RECOVERY_REQUIRED);
            expect(ProjectStorageException.Code.CONFLICT, () -> index.transition(index.currentCommitId(), UUID.randomUUID(),
                    KEY, LocalInstallationIndex.State.ACTIVE));
            assertEquals(prepared.bundle(), index.records().get(KEY).bundle());
        }
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(LocalInstallationIndex.State.RECOVERY_REQUIRED, reopened.records().get(KEY).state());
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            reopened.copyBundle(KEY, copied);
            assertArrayEquals(ARCHIVE, copied.toByteArray());
        }
    }

    @ParameterizedTest
    @EnumSource(LocalInstallationIndex.State.class)
    void explicitRecoveryAfterReopenRecommitsPreparedWithoutChangingIdentityOrOtherRecords(
            LocalInstallationIndex.State oldState) throws Exception {
        Path world = world();
        LocalInstallationIndex.Record original;
        LocalInstallationIndex.Record other;
        InstallationPlan.Key otherKey = new InstallationPlan.Key("dm", "Other preserved installation");
        UUID oldHead;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            reserve(index, plan(UUID.randomUUID(), KEY));
            if (oldState != LocalInstallationIndex.State.PREPARED)
                index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, oldState);
            original = index.records().get(KEY);
            other = reserve(index, plan(UUID.randomUUID(), otherKey));
            oldHead = index.currentCommitId();
        }
        UUID request = UUID.randomUUID();
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(oldState, reopened.records().get(KEY).state());
            assertEquals(original, reopened.records().get(KEY));
            LocalInstallationIndex.Record recovered = reopened.beginRecovery(oldHead, request, original, original.bundle());
            assertEquals(new LocalInstallationIndex.Record(original.plan(), LocalInstallationIndex.State.PREPARED,
                    original.bundle()), recovered);
            assertEquals(original.plan().installationId(), recovered.plan().installationId());
            assertEquals(request, reopened.currentCommitId());
            assertNotEquals(oldHead, reopened.currentCommitId());
            assertEquals(Map.of(KEY, recovered, otherKey, other), reopened.records());
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            reopened.copyBundle(KEY, archive);
            assertArrayEquals(ARCHIVE, archive.toByteArray());
            var receipt = reopened.lookup(request);
            assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, receipt.status());
            assertEquals(request, receipt.saved().commitId());
            assertEquals(request, receipt.currentCommitId());
            // A successful durable request leaves PREPARED ownership; it does not assert a running map.
            assertEquals(LocalInstallationIndex.State.PREPARED, reopened.records().get(KEY).state());
        }
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(request, reopened.currentCommitId());
            assertEquals(original.plan(), reopened.records().get(KEY).plan());
            assertEquals(original.bundle(), reopened.records().get(KEY).bundle());
            assertEquals(LocalInstallationIndex.State.PREPARED, reopened.records().get(KEY).state());
            assertEquals(other, reopened.records().get(otherKey));
        }
    }

    @Test
    void explicitRecoveryRequiresTheCompleteCurrentRecordAndCertifiedBlobBeforeAnyWrite() throws Exception {
        Path world = initializedWorld();
        byte[] originalLock = Files.readAllBytes(directory(world).resolve(".editor.lock"));
        Map<String, String> before;
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            LocalInstallationIndex.Record record = reserve(index, plan(UUID.randomUUID(), KEY));
            InstallationPlan source = record.plan();
            InstallationPlan changedSource = new InstallationPlan(source.installationId(), source.key(), UUID.randomUUID(),
                    source.commitId(), source.documentId(), source.adapterId(), source.factoryId(), source.targetVersion(),
                    source.modules(), source.dimensions());
            BlobRef wrongBlob = new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), record.bundle().size());
            List<LocalInstallationIndex.Record> wrongRecords = List.of(
                    new LocalInstallationIndex.Record(source, LocalInstallationIndex.State.ACTIVE, record.bundle()),
                    new LocalInstallationIndex.Record(changedSource, record.state(), record.bundle()),
                    new LocalInstallationIndex.Record(source, record.state(), wrongBlob),
                    new LocalInstallationIndex.Record(plan(UUID.randomUUID(), new InstallationPlan.Key("cs", "Absent")),
                            record.state(), record.bundle()));
            UUID head = index.currentCommitId();
            before = liveFiles(world);
            for (LocalInstallationIndex.Record wrong : wrongRecords) {
                UUID request = UUID.randomUUID();
                expect(ProjectStorageException.Code.CONFLICT,
                        () -> index.beginRecovery(head, request, wrong, record.bundle()));
                assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
                assertEquals(Map.of(KEY, record), index.records());
                assertEquals(head, index.currentCommitId());
                assertEquals(before, liveFiles(world));
            }
            UUID request = UUID.randomUUID();
            expect(ProjectStorageException.Code.CORRUPT_CONTENT,
                    () -> index.beginRecovery(head, request, record, wrongBlob));
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
            assertEquals(Map.of(KEY, record), index.records());
            assertEquals(head, index.currentCommitId());
            assertEquals(before, liveFiles(world));
        }
        assertClosedFiles(world, before, originalLock);
    }

    @Test
    void staleRecoveryCasCannotChangeDurableRecordsOrReportPublication() throws Exception {
        Path world = initializedWorld();
        byte[] originalLock = Files.readAllBytes(directory(world).resolve(".editor.lock"));
        Map<String, String> before;
        LocalInstallationIndex.Record original;
        LocalInstallationIndex.Record other;
        UUID committed;
        UUID request = UUID.randomUUID();
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            original = reserve(index, plan(UUID.randomUUID(), KEY));
            UUID stale = index.currentCommitId();
            other = reserve(index, plan(UUID.randomUUID(), new InstallationPlan.Key("dm", "Later record")));
            committed = index.currentCommitId();
            before = liveFiles(world);
            expect(ProjectStorageException.Code.CONFLICT,
                    () -> index.beginRecovery(stale, request, original, original.bundle()));
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
            assertEquals(before, liveFiles(world));
        }
        assertClosedFiles(world, before, originalLock);
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(committed, reopened.currentCommitId());
            assertEquals(Map.of(KEY, original, other.plan().key(), other), reopened.records());
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, reopened.lookup(request).status());
        }
        assertClosedFiles(world, before, originalLock);
    }

    @Test
    void externalCurrentChangeCannotBeAdoptedByExplicitRecovery() throws Exception {
        Path world = initializedWorld();
        byte[] originalLock = Files.readAllBytes(directory(world).resolve(".editor.lock"));
        Map<String, String> before;
        LocalInstallationIndex.Record original;
        UUID committed;
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            original = reserve(index, plan(UUID.randomUUID(), KEY));
            committed = index.currentCommitId();
            Path current = directory(world).resolve("CURRENT");
            byte[] previous = Files.readAllBytes(current);
            byte[] changed = Arrays.copyOf(previous, previous.length + 1);
            changed[changed.length - 1] = '\n';
            Files.write(current, changed);
            before = liveFiles(world);
            expect(ProjectStorageException.Code.CONFLICT,
                    () -> index.beginRecovery(committed, UUID.randomUUID(), original, original.bundle()));
            assertEquals(before, liveFiles(world));
            assertArrayEquals(changed, Files.readAllBytes(current));
        }
        assertClosedFiles(world, before, originalLock);
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(committed, reopened.currentCommitId());
            assertEquals(Map.of(KEY, original), reopened.records());
        }
        assertClosedFiles(world, before, originalLock);
    }

    @Test
    void reopenedRecoveryRequiredRecordStillCannotUseOrdinaryTransitionToBecomeActive() throws Exception {
        Path world = world();
        LocalInstallationIndex.Record original;
        UUID head;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            reserve(index, plan(UUID.randomUUID(), KEY));
            original = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY,
                    LocalInstallationIndex.State.RECOVERY_REQUIRED);
            head = index.currentCommitId();
        }
        byte[] originalLock = Files.readAllBytes(directory(world).resolve(".editor.lock"));
        Map<String, String> before;
        try (LocalInstallationIndex reopened = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            before = liveFiles(world);
            expect(ProjectStorageException.Code.CONFLICT, () -> reopened.transition(head, UUID.randomUUID(), KEY,
                    LocalInstallationIndex.State.ACTIVE));
            expect(ProjectStorageException.Code.CONFLICT, () -> reopened.transition(head, UUID.randomUUID(), KEY,
                    LocalInstallationIndex.State.PREPARED));
            assertEquals(head, reopened.currentCommitId());
            assertEquals(Map.of(KEY, original), reopened.records());
            assertEquals(before, liveFiles(world));
        }
        assertClosedFiles(world, before, originalLock);
    }

    @Test
    void recoveryRechecksStoredArchiveBytesEvenWithMatchingCertifiedReference() throws Exception {
        Path world = initializedWorld();
        byte[] originalLock = Files.readAllBytes(directory(world).resolve(".editor.lock"));
        Map<String, String> before;
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            LocalInstallationIndex.Record original = reserve(index, plan(UUID.randomUUID(), KEY));
            Path blob = directory(world).resolve("assets").resolve(original.bundle().sha256() + ".blob");
            byte[] damaged = ARCHIVE.clone();
            damaged[0] ^= 1;
            Files.write(blob, damaged);
            before = liveFiles(world);
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.beginRecovery(index.currentCommitId(),
                    UUID.randomUUID(), original, original.bundle()));
            assertEquals(before, liveFiles(world));
            assertArrayEquals(damaged, Files.readAllBytes(blob));
        }
        assertClosedFiles(world, before, originalLock);
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
    }

    @Test
    void replacementAndSelectedPreviousRecoveryRetainBothExactInputsIncludingActiveAcrossReopen() throws Exception {
        Path world = world();
        InstallationPlan newPlan = plan(UUID.randomUUID(), KEY);
        InstallationPlan.Key otherKey = new InstallationPlan.Key("dm", "Unchanged installation");
        LocalInstallationIndex.Record old, other, replacement;
        UUID request = UUID.randomUUID();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            old = active(index, plan(UUID.randomUUID(), KEY));
            other = reserve(index, plan(UUID.randomUUID(), otherKey));
            UUID baseline = index.currentCommitId();
            replacement = index.beginReplacement(baseline, request, old, newPlan,
                    new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
            assertEquals(new LocalInstallationIndex.Record(newPlan, LocalInstallationIndex.State.PREPARED,
                    reference(REPLACEMENT_ARCHIVE), new LocalInstallationIndex.Previous(old.plan(), old.bundle())), replacement);
            assertEquals(Map.of(KEY, replacement, otherKey, other), index.records());
            assertEquals(request, index.currentCommitId());
            assertBothBytes(index, REPLACEMENT_ARCHIVE, ARCHIVE);
            var receipt = index.lookup(request);
            assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, receipt.status());
            assertEquals(baseline, receipt.saved().parentCommitId());
            assertEquals(request, receipt.saved().commitId());
            assertEquals(Map.of("fpsmatch:runtime_installations", "2"), receipt.saved().state().modules());
            assertEquals(Set.of(newPlan.installationId(), old.plan().installationId(), other.plan().installationId()),
                    receipt.saved().state().assets().keySet());
            assertEquals(Set.of(newPlan.installationId(), old.plan().installationId()),
                    receipt.saved().state().documents().get(newPlan.installationId()).assetDependencies());
            assertEquals(2, binaryVersion(world, receipt.saved().state(), newPlan.installationId()));
            assertEquals(1, binaryVersion(world, receipt.saved().state(), other.plan().installationId()));
        }
        UUID selectedRequest = UUID.randomUUID();
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(replacement, index.records().get(KEY));
            assertEquals(other, index.records().get(otherKey));
            assertBothBytes(index, REPLACEMENT_ARCHIVE, ARCHIVE);
            var failed = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY,
                    LocalInstallationIndex.State.RECOVERY_REQUIRED);
            assertEquals(replacement.previous(), failed.previous());
            var retried = index.beginRecovery(index.currentCommitId(), UUID.randomUUID(), failed, failed.bundle());
            assertEquals(replacement, retried);
            var selected = index.beginPreviousRecovery(index.currentCommitId(), selectedRequest, retried, old.bundle());
            assertEquals(old.plan(), selected.plan());
            assertEquals(old.bundle(), selected.bundle());
            assertEquals(new LocalInstallationIndex.Previous(newPlan, replacement.bundle()), selected.previous());
            assertEquals(LocalInstallationIndex.State.PREPARED, selected.state());
            assertBothBytes(index, ARCHIVE, REPLACEMENT_ARCHIVE);
            assertEquals(other, index.records().get(otherKey));
        }
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(selectedRequest, index.currentCommitId());
            assertEquals(old.plan(), index.records().get(KEY).plan());
            assertBothBytes(index, ARCHIVE, REPLACEMENT_ARCHIVE);
            var active = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, LocalInstallationIndex.State.ACTIVE);
            assertEquals(new LocalInstallationIndex.Record(old.plan(), LocalInstallationIndex.State.ACTIVE, old.bundle(),
                    new LocalInstallationIndex.Previous(newPlan, replacement.bundle())), active);
            assertBothBytes(index, ARCHIVE, REPLACEMENT_ARCHIVE);
            ProjectState current = index.lookup(index.currentCommitId()).saved().state();
            assertEquals(Map.of("fpsmatch:runtime_installations", "2"), current.modules());
            assertEquals(Set.of(old.plan().installationId(), newPlan.installationId(), other.plan().installationId()), current.assets().keySet());
            assertEquals(2, binaryVersion(world, current, old.plan().installationId()));
            assertEquals(other, index.records().get(otherKey));
            // Both the current ACTIVE snapshot and historical receipt keep complete fixed inputs.
            assertEquals(Map.of("fpsmatch:runtime_installations", "2"), index.lookup(request).saved().state().modules());
        }
        Map<String, String> before = files(world);
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(new LocalInstallationIndex.Previous(newPlan, replacement.bundle()), index.records().get(KEY).previous());
            assertEquals(old.plan(), index.records().get(KEY).plan());
            assertBothBytes(index, ARCHIVE, REPLACEMENT_ARCHIVE);
            assertEquals(other, index.records().get(otherKey));
        }
        assertEquals(before, files(world));
    }

    @Test
    void replacementFrontDoorsRejectWrongHeadFullRecordPlanOrIdWithoutReadingNewArchive() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            var old = active(index, plan(UUID.randomUUID(), KEY));
            var other = reserve(index, plan(UUID.randomUUID(), new InstallationPlan.Key("dm", "Occupied")));
            InstallationPlan source = old.plan();
            InstallationPlan wrongSource = new InstallationPlan(source.installationId(), source.key(), UUID.randomUUID(),
                    source.commitId(), source.documentId(), source.adapterId(), source.factoryId(), source.targetVersion(),
                    source.modules(), source.dimensions());
            List<LocalInstallationIndex.Record> wrongRecords = List.of(
                    new LocalInstallationIndex.Record(source, LocalInstallationIndex.State.PREPARED, old.bundle()),
                    new LocalInstallationIndex.Record(wrongSource, old.state(), old.bundle()),
                    new LocalInstallationIndex.Record(source, old.state(), reference(REPLACEMENT_ARCHIVE)),
                    new LocalInstallationIndex.Record(source, old.state(), old.bundle(),
                            new LocalInstallationIndex.Previous(plan(UUID.randomUUID(), KEY), old.bundle())));
            UUID head = index.currentCommitId();
            Map<String, String> before = liveFiles(world);
            AtomicInteger reads = new AtomicInteger();
            for (var wrong : wrongRecords) {
                UUID request = UUID.randomUUID();
                expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(head, request, wrong,
                        plan(UUID.randomUUID(), KEY), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
                assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
            }
            for (InstallationPlan invalid : List.of(plan(source.installationId(), KEY),
                    plan(other.plan().installationId(), KEY), plan(UUID.randomUUID(), other.plan().key()))) {
                expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(head, UUID.randomUUID(), old,
                        invalid, countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            }
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(UUID.randomUUID(), UUID.randomUUID(), old,
                    plan(UUID.randomUUID(), KEY), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            assertEquals(0, reads.get());
            index.verify();
            assertEquals(head, index.currentCommitId());
            assertEquals(Map.of(KEY, old, other.plan().key(), other), index.records());
            assertEquals(before, liveFiles(world));
        }
    }

    @ParameterizedTest
    @EnumSource(value = LocalInstallationIndex.State.class, names = {"PREPARED", "RECOVERY_REQUIRED"})
    void replacementRequiresActiveAndRefusesBeforeInputRead(LocalInstallationIndex.State state) throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            var old = reserve(index, plan(UUID.randomUUID(), KEY));
            if (state == LocalInstallationIndex.State.RECOVERY_REQUIRED)
                old = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, state);
            var expected = old;
            UUID head = index.currentCommitId();
            Map<String, String> before = liveFiles(world);
            AtomicInteger reads = new AtomicInteger();
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(head, UUID.randomUUID(), expected,
                    plan(UUID.randomUUID(), KEY), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            assertEquals(0, reads.get());
            assertEquals(before, liveFiles(world));
            assertEquals(expected, index.records().get(KEY));
            assertEquals(head, index.currentCommitId());
        }
    }

    @Test
    void previousIdsStayReservedAcrossAllKeysAndNoSecondGenerationCanBeStarted() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            var old = active(index, plan(UUID.randomUUID(), KEY));
            var other = active(index, plan(UUID.randomUUID(), new InstallationPlan.Key("dm", "Other active")));
            var pending = index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), old,
                    plan(UUID.randomUUID(), KEY), new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
            UUID head = index.currentCommitId();
            Map<String, String> before = liveFiles(world);
            AtomicInteger reads = new AtomicInteger();
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(head, UUID.randomUUID(), pending,
                    plan(UUID.randomUUID(), KEY), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(head, UUID.randomUUID(), other,
                    plan(old.plan().installationId(), other.plan().key()), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            expect(ProjectStorageException.Code.CONFLICT, () -> index.reserve(head, UUID.randomUUID(),
                    plan(old.plan().installationId(), new InstallationPlan.Key("dm", "Cannot reuse backup")), countedArchive(reads)));
            assertEquals(0, reads.get());
            assertEquals(Map.of(KEY, pending, other.plan().key(), other), index.records());
            assertEquals(head, index.currentCommitId());
            assertEquals(before, liveFiles(world));
        }
    }

    @Test
    void consecutiveActiveReplacementsKeepOnlyCurrentAndImmediatePreviousInputsWithoutReusingEvictedId() throws Exception {
        Path world = world();
        byte[] thirdArchive = {80, 75, 5, 6, 91, 11, 0, 2};
        LocalInstallationIndex.Record first, second;
        InstallationPlan thirdPlan = plan(UUID.randomUUID(), KEY);
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            first = active(index, plan(UUID.randomUUID(), KEY));
            var pending = index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), first,
                    plan(UUID.randomUUID(), KEY), new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
            second = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, LocalInstallationIndex.State.ACTIVE);
            assertEquals(pending.previous(), second.previous());
            assertBothBytes(index, REPLACEMENT_ARCHIVE, ARCHIVE);
        }
        LocalInstallationIndex.Record third;
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(second, index.records().get(KEY));
            UUID head = index.currentCommitId();
            Map<String, String> before = liveFiles(world);
            AtomicInteger reads = new AtomicInteger();
            // Even the backup about to be superseded is occupied at the admission boundary.
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(head, UUID.randomUUID(), second,
                    plan(first.plan().installationId(), KEY), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            assertEquals(0, reads.get());
            assertEquals(before, liveFiles(world));
            var pending = index.beginReplacement(head, UUID.randomUUID(), second, thirdPlan,
                    new ByteArrayInputStream(thirdArchive), reference(thirdArchive));
            assertEquals(new LocalInstallationIndex.Previous(second.plan(), second.bundle()), pending.previous());
            assertBothBytes(index, thirdArchive, REPLACEMENT_ARCHIVE);
            ProjectState state = index.lookup(index.currentCommitId()).saved().state();
            assertEquals(Set.of(thirdPlan.installationId(), second.plan().installationId()), state.assets().keySet());
            assertFalse(state.assets().containsKey(first.plan().installationId()));
            third = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, LocalInstallationIndex.State.ACTIVE);
            assertEquals(pending.previous(), third.previous());
        }
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(third, index.records().get(KEY));
            assertEquals(LocalInstallationIndex.State.ACTIVE, third.state());
            assertBothBytes(index, thirdArchive, REPLACEMENT_ARCHIVE);
        }
    }

    @Test
    void wrongCertifiedNewBytesKeepExactActiveRecordAndUnknownReceiptWithoutPoisoningIndex() throws Exception {
        Path world = world();
        UUID request = UUID.randomUUID();
        LocalInstallationIndex.Record old;
        UUID head;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            old = active(index, plan(UUID.randomUUID(), KEY));
            head = index.currentCommitId();
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.beginReplacement(head, request, old,
                    plan(UUID.randomUUID(), KEY), new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(ARCHIVE)));
            index.verify();
            assertEquals(head, index.currentCommitId());
            assertEquals(Map.of(KEY, old), index.records());
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            index.copyBundle(KEY, bytes);
            assertArrayEquals(ARCHIVE, bytes.toByteArray());
        }
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(head, index.currentCommitId());
            assertEquals(Map.of(KEY, old), index.records());
        }
    }

    @Test
    void replacementRevalidatesOldBytesBeforeReadingNewArchive() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            var old = active(index, plan(UUID.randomUUID(), KEY));
            Path content = directory(world).resolve("assets").resolve(old.bundle().sha256() + ".blob");
            byte[] damaged = ARCHIVE.clone();
            damaged[0] ^= 1;
            Files.write(content, damaged);
            Map<String, String> before = liveFiles(world);
            AtomicInteger reads = new AtomicInteger();
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.beginReplacement(index.currentCommitId(),
                    UUID.randomUUID(), old, plan(UUID.randomUUID(), KEY), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            assertEquals(0, reads.get());
            assertEquals(Map.of(KEY, old), index.records());
            assertEquals(before, liveFiles(world));
        }
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
    }

    @Test
    void previousRecoveryRequiresExactFullRecordHeadAndCertifiedPreviousReference() throws Exception {
        Path world = replacementWorld();
        byte[] lock = Files.readAllBytes(directory(world).resolve(".editor.lock"));
        Map<String, String> before;
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            var current = index.records().get(KEY);
            UUID head = index.currentCommitId();
            before = liveFiles(world);
            var withoutBackup = new LocalInstallationIndex.Record(current.plan(), current.state(), current.bundle());
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginPreviousRecovery(head, UUID.randomUUID(),
                    withoutBackup, current.previous().bundle()));
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginPreviousRecovery(UUID.randomUUID(), UUID.randomUUID(),
                    current, current.previous().bundle()));
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.beginPreviousRecovery(head, UUID.randomUUID(),
                    current, current.bundle()));
            var changedPrevious = new LocalInstallationIndex.Record(current.plan(), current.state(), current.bundle(),
                    new LocalInstallationIndex.Previous(plan(UUID.randomUUID(), KEY), current.previous().bundle()));
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginPreviousRecovery(head, UUID.randomUUID(),
                    changedPrevious, current.previous().bundle()));
            assertEquals(head, index.currentCommitId());
            assertEquals(Map.of(KEY, current), index.records());
            assertEquals(before, liveFiles(world));
            index.verify();
        }
        assertClosedFiles(world, before, lock);
    }

    @Test
    void damagedPreviousBytesAreRefusedByCopyAndSelectionAndRemainProtectedOnReopen() throws Exception {
        Path world = replacementWorld();
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            var current = index.records().get(KEY);
            Path previous = directory(world).resolve("assets").resolve(current.previous().bundle().sha256() + ".blob");
            byte[] damaged = ARCHIVE.clone();
            damaged[0] ^= 1;
            Files.write(previous, damaged);
            Map<String, String> before = liveFiles(world);
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.copyPreviousBundle(KEY, new ByteArrayOutputStream()));
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.beginPreviousRecovery(index.currentCommitId(),
                    UUID.randomUUID(), current, current.previous().bundle()));
            assertEquals(Map.of(KEY, current), index.records());
            assertEquals(before, liveFiles(world));
        }
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
    }

    @Test
    void entryBudgetRefusesReplacementAtFullV1CapacityAndReservationAtFullMixedCapacityBeforeReading() throws Exception {
        Path world = initializedWorld();
        InstallationPlan target = plan(UUID.randomUUID(), KEY);
        // Populate a valid full v1 snapshot in one storage commit, without 256 unrelated domain commits.
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            Map<UUID, ProjectState.Entry> documents = new TreeMap<>(), assets = new TreeMap<>();
            BlobRef archive = raw.stage(BlobRef.Kind.ASSET, ARCHIVE);
            for (int i = 0; i < 256; i++) {
                var plan = i == 0 ? target : plan(UUID.randomUUID(), new InstallationPlan.Key("dm", "Budget " + i));
                BlobRef document = raw.stage(BlobRef.Kind.DOCUMENT, legacyRecord(plan, LocalInstallationIndex.State.ACTIVE));
                documents.put(plan.installationId(), fixtureEntry(plan.installationId(), true, document, Set.of(plan.installationId())));
                assets.put(plan.installationId(), fixtureEntry(plan.installationId(), false, archive, Set.of()));
            }
            raw.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(saved.state().projectId(), saved.state().name(),
                    saved.state().modules(), documents, assets, null));
        }
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            var old = index.records().get(KEY);
            UUID head = index.currentCommitId();
            Map<InstallationPlan.Key, LocalInstallationIndex.Record> records = index.records();
            Map<String, String> before = liveFiles(world);
            AtomicInteger reads = new AtomicInteger();
            expect(ProjectStorageException.Code.LIMIT_EXCEEDED, () -> index.beginReplacement(head, UUID.randomUUID(), old,
                    plan(UUID.randomUUID(), KEY), countedArchive(reads), reference(REPLACEMENT_ARCHIVE)));
            expect(ProjectStorageException.Code.LIMIT_EXCEEDED, () -> index.reserve(head, UUID.randomUUID(),
                    plan(UUID.randomUUID(), new InstallationPlan.Key("dm", "Over budget")), countedArchive(reads)));
            assertEquals(0, reads.get());
            assertEquals(head, index.currentCommitId());
            assertEquals(records, index.records());
            assertEquals(before, liveFiles(world));
            index.verify();
        }
        // Remove one unrelated v1 record through raw fixture storage, then use exactly 512 entries:
        // 255 current documents + 255 current archives + two retained archives.
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            Map<UUID, ProjectState.Entry> documents = new TreeMap<>(saved.state().documents());
            UUID removed = documents.values().stream().map(ProjectState.Entry::id)
                    .filter(id -> !id.equals(target.installationId())).findFirst().orElseThrow();
            documents.remove(removed);
            Map<UUID, ProjectState.Entry> assets = new TreeMap<>(saved.state().assets());
            assets.remove(removed);
            raw.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(saved.state().projectId(), saved.state().name(),
                    saved.state().modules(), documents, assets, null));
        }
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            var first = index.records().get(KEY);
            index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), first, plan(UUID.randomUUID(), KEY),
                    new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
            var second = index.records().values().stream().filter(record -> !record.plan().key().equals(KEY)).findFirst().orElseThrow();
            index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), second, plan(UUID.randomUUID(), second.plan().key()),
                    new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
            assertEquals(512, index.lookup(index.currentCommitId()).saved().state().documents().size()
                    + index.lookup(index.currentCommitId()).saved().state().assets().size());
            var replaceable = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY, LocalInstallationIndex.State.ACTIVE);
            var next = index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), replaceable,
                    plan(UUID.randomUUID(), KEY), new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
            assertEquals(new LocalInstallationIndex.Previous(replaceable.plan(), replaceable.bundle()), next.previous());
            assertEquals(512, index.lookup(index.currentCommitId()).saved().state().documents().size()
                    + index.lookup(index.currentCommitId()).saved().state().assets().size());
            UUID head = index.currentCommitId();
            Map<String, String> before = liveFiles(world);
            AtomicInteger reads = new AtomicInteger();
            expect(ProjectStorageException.Code.LIMIT_EXCEEDED, () -> index.reserve(head, UUID.randomUUID(),
                    plan(UUID.randomUUID(), new InstallationPlan.Key("dm", "Mixed overflow")), countedArchive(reads)));
            assertEquals(0, reads.get());
            assertEquals(before, liveFiles(world));
            assertEquals(head, index.currentCommitId());
        }
    }

    @Test
    void replacementCommitRefusalStaysProtectedAndUnknownLookupCannotRestoreUsability() throws Exception {
        Path world = world();
        LocalInstallationIndex.Record old;
        UUID head;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            old = active(index, plan(UUID.randomUUID(), KEY));
            head = index.currentCommitId();
            expect(ProjectStorageException.Code.REQUEST_MISMATCH, () -> index.beginReplacement(head, head, old,
                    plan(UUID.randomUUID(), KEY), new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE)));
            assertThrows(IllegalStateException.class, index::verify);
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(UUID.randomUUID()).status());
            assertThrows(IllegalStateException.class, index::records);
            assertThrows(IllegalStateException.class, index::currentCommitId);
        }
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(head, index.currentCommitId());
            assertEquals(Map.of(KEY, old), index.records());
        }
    }

    @Test
    void activeResultUnknownAfterActualPublicationKeepsBothInputsAndLookupDoesNotClearProtection() throws Exception {
        Path world = replacementWorld();
        AtomicInteger faults = new AtomicInteger();
        IOException injected = new IOException("Injected after durable ACTIVE publication");
        UUID request = UUID.randomUUID();
        LocalInstallationIndex.Record original;
        try (LocalInstallationIndex index = withAfterPublishFailure(world, faults, injected)) {
            original = index.records().get(KEY);
            ProjectStorageException failure = assertThrows(ProjectStorageException.class,
                    () -> index.transition(index.currentCommitId(), request, KEY, LocalInstallationIndex.State.ACTIVE));
            assertEquals(ProjectStorageException.Code.RESULT_UNKNOWN, failure.code());
            assertSame(injected, failure.getCause());
            assertEquals(1, faults.get());
            assertThrows(IllegalStateException.class, index::verify);
            var receipt = index.lookup(request);
            assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, receipt.status());
            assertEquals(request, receipt.currentCommitId());
            assertEquals(Map.of("fpsmatch:runtime_installations", "2"), receipt.saved().state().modules());
            assertEquals(Set.of(original.plan().installationId(), original.previous().plan().installationId()),
                    receipt.saved().state().assets().keySet());
            assertThrows(IllegalStateException.class, index::records);
            assertThrows(IllegalStateException.class, index::verify);
        }
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(request, index.currentCommitId());
            assertEquals(new LocalInstallationIndex.Record(original.plan(), LocalInstallationIndex.State.ACTIVE,
                    original.bundle(), original.previous()), index.records().get(KEY));
            assertBothBytes(index, REPLACEMENT_ARCHIVE, ARCHIVE);
        }
    }

    @Test
    void externalHeadChangeDuringNewArchiveReadCannotPublishReplacementOrRefreshBaseline() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            var old = active(index, plan(UUID.randomUUID(), KEY));
            UUID head = index.currentCommitId();
            Path current = directory(world).resolve("CURRENT");
            byte[] original = Files.readAllBytes(current);
            byte[] changed = Arrays.copyOf(original, original.length + 1);
            changed[changed.length - 1] = '\n';
            InputStream mutating = new ByteArrayInputStream(REPLACEMENT_ARCHIVE) {
                private boolean first = true;
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    if (first) {
                        first = false;
                        try { Files.write(current, changed); }
                        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    }
                    return super.read(bytes, offset, length);
                }
            };
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginReplacement(head, UUID.randomUUID(), old,
                    plan(UUID.randomUUID(), KEY), mutating, reference(REPLACEMENT_ARCHIVE)));
            assertArrayEquals(changed, Files.readAllBytes(current));
            assertEquals(head, index.currentCommitId());
            assertEquals(Map.of(KEY, old), index.records());
            expect(ProjectStorageException.Code.CONFLICT, index::verify);
        }
    }

    @Test
    void independentlyEncodedLegacyV1IsReadWithoutMigration() throws Exception {
        Path world = initializedWorld();
        InstallationPlan legacy = plan(UUID.randomUUID(), KEY);
        byte[] bytes = legacyRecord(legacy, LocalInstallationIndex.State.ACTIVE);
        UUID originalHead = UUID.randomUUID();
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            BlobRef archive = raw.stage(BlobRef.Kind.ASSET, ARCHIVE);
            BlobRef document = raw.stage(BlobRef.Kind.DOCUMENT, bytes);
            raw.commit(saved.commitId(), originalHead, new ProjectState(saved.state().projectId(), saved.state().name(),
                    Map.of("fpsmatch:runtime_installations", "1"),
                    Map.of(legacy.installationId(), fixtureEntry(legacy.installationId(), true, document, Set.of(legacy.installationId()))),
                    Map.of(legacy.installationId(), fixtureEntry(legacy.installationId(), false, archive, Set.of())), null));
        }
        Map<String, String> before = files(world);
        try (LocalInstallationIndex index = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
            assertEquals(originalHead, index.currentCommitId());
            assertEquals(new LocalInstallationIndex.Record(legacy, LocalInstallationIndex.State.ACTIVE, reference(ARCHIVE)),
                    index.records().get(KEY));
            assertNull(index.records().get(KEY).previous());
            expect(ProjectStorageException.Code.CONFLICT, () -> index.copyPreviousBundle(KEY, new ByteArrayOutputStream()));
            expect(ProjectStorageException.Code.CONFLICT, () -> index.beginPreviousRecovery(originalHead, UUID.randomUUID(),
                    index.records().get(KEY), reference(ARCHIVE)));
            ProjectState state = index.lookup(originalHead).saved().state();
            assertArrayEquals(bytes, documentBytes(world, state.documents().get(legacy.installationId())));
        }
        assertEquals(before, files(world));
    }

    @ParameterizedTest
    @ValueSource(strings = {"module1", "module3", "missing-previous", "extra-asset", "dependencies",
            "v1-with-previous-assets", "v2-truncated", "v2-trailing", "v2-same-id", "v2-wrong-key", "v2-bad-asset-schema"})
    void malformedReplacementSchemaIsProtectedEvenWithValidStorageHashes(String mutation) throws Exception {
        Path world = replacementWorld();
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            ProjectState old = saved.state();
            var original = old.documents().values().iterator().next();
            byte[] bytes = documentBytes(world, original);
            UUID previousId = old.assets().keySet().stream().filter(id -> !id.equals(original.id())).findFirst().orElseThrow();
            Map<UUID, ProjectState.Entry> assets = new TreeMap<>(old.assets());
            Map<String, String> modules = old.modules();
            Set<UUID> dependencies = original.assetDependencies();
            switch (mutation) {
                case "module1" -> modules = Map.of("fpsmatch:runtime_installations", "1");
                case "module3" -> modules = Map.of("fpsmatch:runtime_installations", "3");
                case "missing-previous" -> { assets.remove(previousId); dependencies = Set.of(original.id()); }
                case "extra-asset" -> {
                    UUID extra = UUID.randomUUID();
                    assets.put(extra, fixtureEntry(extra, false, assets.get(previousId).content(), Set.of()));
                }
                case "dependencies" -> dependencies = Set.of(original.id());
                case "v1-with-previous-assets" -> {
                    bytes = Arrays.copyOf(bytes, previousPlanOffset(bytes));
                    ByteBuffer.wrap(bytes).putInt(4, 1);
                    dependencies = Set.of(original.id());
                }
                case "v2-truncated" -> bytes = Arrays.copyOf(bytes, bytes.length - 1);
                case "v2-trailing" -> bytes = Arrays.copyOf(bytes, bytes.length + 1);
                case "v2-same-id" -> {
                    ByteBuffer.wrap(bytes).position(previousPlanOffset(bytes))
                            .putLong(original.id().getMostSignificantBits()).putLong(original.id().getLeastSignificantBits());
                    assets.remove(previousId);
                    dependencies = Set.of(original.id());
                }
                case "v2-wrong-key" -> bytes[previousPlanOffset(bytes) + 18] ^= 1;
                case "v2-bad-asset-schema" -> {
                    var asset = assets.get(previousId);
                    assets.put(previousId, new ProjectState.Entry(asset.id(), asset.ownerModule(), asset.typeId(), asset.codecId(),
                            2, asset.displayName(), asset.content(), asset.documentDependencies(), asset.assetDependencies()));
                }
                default -> throw new AssertionError(mutation);
            }
            BlobRef content = raw.stage(BlobRef.Kind.DOCUMENT, bytes);
            var changed = fixtureEntry(original.id(), true, content, dependencies);
            raw.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(old.projectId(), old.name(), modules,
                    Map.of(changed.id(), changed), assets, null));
        }
        assertProtected(world, ProjectStorageException.Code.INVALID_FORMAT);
    }

    @Test
    void sharedPreviousIdentityAcrossDistinctRecordsIsRejectedRatherThanSilentlyOverwritingAnAsset() throws Exception {
        Path world = world();
        InstallationPlan.Key otherKey = new InstallationPlan.Key("dm", "Other input");
        LocalInstallationIndex.Record first, second;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            var firstOld = active(index, plan(UUID.randomUUID(), KEY));
            var secondOld = active(index, plan(UUID.randomUUID(), otherKey));
            first = index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), firstOld,
                    plan(UUID.randomUUID(), KEY), new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
            second = index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), secondOld,
                    plan(UUID.randomUUID(), otherKey), new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
        }
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            ProjectState old = saved.state();
            var original = old.documents().get(second.plan().installationId());
            byte[] bytes = documentBytes(world, original);
            UUID repeated = first.previous().plan().installationId();
            ByteBuffer.wrap(bytes).position(previousPlanOffset(bytes)).putLong(repeated.getMostSignificantBits())
                    .putLong(repeated.getLeastSignificantBits());
            BlobRef content = raw.stage(BlobRef.Kind.DOCUMENT, bytes);
            Map<UUID, ProjectState.Entry> documents = new TreeMap<>(old.documents());
            documents.put(original.id(), fixtureEntry(original.id(), true, content, Set.of(original.id(), repeated)));
            Map<UUID, ProjectState.Entry> assets = new TreeMap<>(old.assets());
            assets.remove(second.previous().plan().installationId());
            raw.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(old.projectId(), old.name(), old.modules(), documents, assets, null));
        }
        assertProtected(world, ProjectStorageException.Code.INVALID_FORMAT);
    }

    @Test
    void existingOwnershipCannotBeReinitializedEvenWhenEmpty() throws Exception {
        Path world = initializedWorld();
        Map<String, String> before = files(world);
        expect(ProjectStorageException.Code.CONFLICT, () -> LocalInstallationIndex.initialize(world, IDENTITIES));
        assertEquals(before, files(world));
    }

    @Test
    void witnessWithoutIndexIsProtectedAndNeverRebuilt() throws Exception {
        Path world = initializedWorld();
        Files.move(directory(world), temporary.resolve("retained-index"));
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
        assertFalse(Files.exists(directory(world)));
    }

    @Test
    void orphanIndexWithoutWitnessIsProtectedAndNeverAdopted() throws Exception {
        Path world = initializedWorld();
        Files.move(marker(world), temporary.resolve("retained-witness"));
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
        assertFalse(Files.exists(marker(world)));
    }

    @Test
    void corruptedWitnessRemainsProtectedOnOpenAndInitialization() throws Exception {
        Path world = initializedWorld();
        byte[] damaged = Files.readAllBytes(marker(world));
        damaged[0] ^= 1;
        Files.write(marker(world), damaged);
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
        expect(ProjectStorageException.Code.CONFLICT, () -> LocalInstallationIndex.initialize(world, IDENTITIES));
        assertArrayEquals(damaged, Files.readAllBytes(marker(world)));
    }

    @Test
    void installationDirectoryReplacedByFileIsProtectedAsInvalidPath() throws Exception {
        Path world = initializedWorld();
        Files.move(directory(world), temporary.resolve("retained-index-directory"));
        Files.writeString(directory(world), "damaged ownership container");
        assertProtected(world, ProjectStorageException.Code.INVALID_PATH);
    }

    @Test
    void missingWorldIdentityCannotTurnOwnedWorldIntoLegacyAbsence() throws Exception {
        Path world = initializedWorld();
        Files.move(platform(world).resolve("world.identity"), temporary.resolve("retained-world-identity"));
        Map<String, String> before = files(world);
        WorldIdentityException failure = assertThrows(WorldIdentityException.class,
                () -> LocalInstallationIndex.openIfPresent(world, IDENTITIES));
        assertEquals(WorldIdentityException.Code.MISSING, failure.code());
        assertEquals(before, files(world));
        assertTrue(Files.exists(marker(world)));
        assertTrue(Files.exists(directory(world)));
    }

    @Test
    void changedRegisteredWorldIdentityInvalidatesLiveAndReopenedOwnership() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            byte[] identity = Files.readAllBytes(platform(world).resolve("world.identity"));
            identity[23] ^= 1;
            Files.write(platform(world).resolve("world.identity"), identity);
            WorldIdentityException failure = assertThrows(WorldIdentityException.class, index::verify);
            assertEquals(WorldIdentityException.Code.CHANGED, failure.code());
            assertThrows(WorldIdentityException.class, () -> reserve(index, plan(UUID.randomUUID(), KEY)));
        }
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
    }

    @Test
    void replacedLiveWitnessFailsPhysicalIdentityEvenWithIdenticalBytes() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            byte[] bytes = Files.readAllBytes(marker(world));
            Files.move(marker(world), temporary.resolve("retained-original-witness"));
            Files.write(marker(world), bytes);
            expect(ProjectStorageException.Code.CONFLICT, index::verify);
            expect(ProjectStorageException.Code.CONFLICT, () -> reserve(index, plan(UUID.randomUUID(), KEY)));
        }
    }

    @Test
    void externalCurrentChangeIsRejectedWithoutAdoptingAnotherBaseline() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            UUID captured = index.currentCommitId();
            Path current = directory(world).resolve("CURRENT");
            byte[] original = Files.readAllBytes(current);
            byte[] changed = Arrays.copyOf(original, original.length + 1);
            changed[changed.length - 1] = '\n';
            Files.write(current, changed);
            expect(ProjectStorageException.Code.CONFLICT, index::verify);
            expect(ProjectStorageException.Code.CONFLICT, () -> reserve(index, plan(UUID.randomUUID(), KEY)));
            assertEquals(captured, index.currentCommitId());
            assertArrayEquals(changed, Files.readAllBytes(current));
        }
    }

    @Test
    void sameLengthArchiveCorruptionIsRejectedWhenConsumedAndWhenReopened() throws Exception {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            LocalInstallationIndex.Record record = reserve(index, plan(UUID.randomUUID(), KEY));
            Path blob = directory(world).resolve("assets").resolve(record.bundle().sha256() + ".blob");
            byte[] damaged = ARCHIVE.clone();
            damaged[0] ^= 1;
            Files.write(blob, damaged);
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.copyBundle(KEY, new ByteArrayOutputStream()));
            assertArrayEquals(damaged, Files.readAllBytes(blob));
        }
        assertProtected(world, ProjectStorageException.Code.CORRUPT_CONTENT);
    }

    @Test
    void missingCurrentInExistingContainerIsNotAnUninitializedWorld() throws Exception {
        Path world = initializedWorld();
        Files.move(directory(world).resolve("CURRENT"), temporary.resolve("retained-current"));
        assertProtected(world, ProjectStorageException.Code.MISSING_PROJECT);
        assertFalse(Files.exists(directory(world).resolve("CURRENT")));
    }

    @Test
    void moduleVersion2WithoutAnyPreviousInputIsRejectedEvenWithValidStorageDigests() throws Exception {
        Path world = initializedWorld();
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            ProjectState old = saved.state();
            raw.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(old.projectId(), old.name(),
                    Map.of("fpsmatch:runtime_installations", "2"), old.documents(), old.assets(), old.origin()));
        }
        assertProtected(world, ProjectStorageException.Code.INVALID_FORMAT);
    }

    @Test
    void unknownRecordVersionIsRejectedEvenWithValidStorageDigests() throws Exception {
        Path world = reservedWorld();
        rewriteRecord(world, bytes -> {
            ByteBuffer.wrap(bytes).putInt(4, 3);
            return bytes;
        });
        assertProtected(world, ProjectStorageException.Code.UNSUPPORTED_FORMAT);
    }

    @Test
    void incompatibleRecordEntrySchemaIsRejectedEvenWithValidStorageDigests() throws Exception {
        Path world = reservedWorld();
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            ProjectState old = saved.state();
            ProjectState.Entry entry = old.documents().values().iterator().next();
            ProjectState.Entry incompatible = new ProjectState.Entry(entry.id(), entry.ownerModule(), entry.typeId(),
                    entry.codecId(), 2, entry.displayName(), entry.content(),
                    entry.documentDependencies(), entry.assetDependencies());
            raw.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(old.projectId(), old.name(), old.modules(),
                    Map.of(incompatible.id(), incompatible), old.assets(), old.origin()));
        }
        assertProtected(world, ProjectStorageException.Code.INVALID_FORMAT);
    }

    @Test
    void unknownRecordStateIsRejectedInsteadOfDefaultingToActiveOrEmpty() throws Exception {
        Path world = reservedWorld();
        rewriteRecord(world, bytes -> {
            // The first UTF field follows the magic/version pair; preserve framing and byte length.
            byte[] invalidState = "UNKNOWN!".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(invalidState, 0, bytes, 10, invalidState.length);
            return bytes;
        });
        assertProtected(world, ProjectStorageException.Code.INVALID_FORMAT);
    }

    @Test
    void trailingRecordBytesAreRejectedRatherThanIgnored() throws Exception {
        Path world = reservedWorld();
        rewriteRecord(world, bytes -> Arrays.copyOf(bytes, bytes.length + 1));
        assertProtected(world, ProjectStorageException.Code.INVALID_FORMAT);
    }

    private Path world() throws IOException {
        Path world = Files.createDirectory(temporary.resolve("world"));
        WorldIdentities.initialize(world, IDENTITIES, () -> false);
        return world;
    }

    private Path initializedWorld() throws IOException {
        Path world = world();
        try (LocalInstallationIndex ignored = LocalInstallationIndex.initialize(world, IDENTITIES)) { }
        return world;
    }

    private Path reservedWorld() throws IOException {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            reserve(index, plan(UUID.randomUUID(), KEY));
        }
        return world;
    }

    private Path replacementWorld() throws IOException {
        Path world = world();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            var old = active(index, plan(UUID.randomUUID(), KEY));
            index.beginReplacement(index.currentCommitId(), UUID.randomUUID(), old, plan(UUID.randomUUID(), KEY),
                    new ByteArrayInputStream(REPLACEMENT_ARCHIVE), reference(REPLACEMENT_ARCHIVE));
        }
        return world;
    }

    private static LocalInstallationIndex.Record active(LocalInstallationIndex index, InstallationPlan plan) throws IOException {
        reserve(index, plan);
        return index.transition(index.currentCommitId(), UUID.randomUUID(), plan.key(), LocalInstallationIndex.State.ACTIVE);
    }

    private static BlobRef reference(byte[] bytes) {
        try {
            return new BlobRef(BlobRef.Kind.ASSET,
                    HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static InputStream countedArchive(AtomicInteger reads) {
        return new ByteArrayInputStream(REPLACEMENT_ARCHIVE) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads.incrementAndGet();
                return super.read(bytes, offset, length);
            }
            @Override public synchronized int read() {
                reads.incrementAndGet();
                return super.read();
            }
        };
    }

    private static void assertBothBytes(LocalInstallationIndex index, byte[] current, byte[] previous) throws IOException {
        ByteArrayOutputStream currentBytes = new ByteArrayOutputStream(), previousBytes = new ByteArrayOutputStream();
        index.copyBundle(KEY, currentBytes);
        index.copyPreviousBundle(KEY, previousBytes);
        assertArrayEquals(current, currentBytes.toByteArray());
        assertArrayEquals(previous, previousBytes.toByteArray());
    }

    private static byte[] documentBytes(Path world, ProjectState.Entry entry) throws IOException {
        return Files.readAllBytes(directory(world).resolve("documents").resolve(entry.content().sha256() + ".blob"));
    }

    private static int binaryVersion(Path world, ProjectState state, UUID id) throws IOException {
        return ByteBuffer.wrap(documentBytes(world, state.documents().get(id))).getInt(4);
    }

    private static ProjectState.Entry fixtureEntry(UUID id, boolean document, BlobRef content, Set<UUID> dependencies) {
        String type = document ? "fpsmatch:runtime_installation" : "fpsmatch:installation_bundle";
        return new ProjectState.Entry(id, "fpsmatch:runtime_installations", type, type, 1, type, content, Set.of(), dependencies);
    }

    /** Independent legacy-format fixture: no production encoder or reflective access. */
    private static byte[] legacyRecord(InstallationPlan plan, LocalInstallationIndex.State state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x45444931);
            output.writeInt(1);
            output.writeUTF(state.name());
            fixtureUuid(output, plan.installationId());
            output.writeUTF(plan.key().type()); output.writeUTF(plan.key().name());
            fixtureUuid(output, plan.projectId()); fixtureUuid(output, plan.commitId()); fixtureUuid(output, plan.documentId());
            output.writeUTF(plan.adapterId().toString()); output.writeUTF(plan.factoryId().toString());
            output.writeUTF(plan.targetVersion());
            output.writeInt(plan.modules().size());
            for (var module : plan.modules().entrySet()) { output.writeUTF(module.getKey().toString()); output.writeUTF(module.getValue()); }
            output.writeInt(plan.dimensions().size());
            for (var dimension : plan.dimensions().entrySet()) { output.writeUTF(dimension.getKey().toString()); output.writeUTF(dimension.getValue().toString()); }
        }
        return bytes.toByteArray();
    }

    private static void fixtureUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits());
    }

    /** Locate the second plan from framed fields; do not guess UUID byte offsets or search arbitrary payloads. */
    private static int previousPlanOffset(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals(0x45444931, input.readInt());
            assertEquals(2, input.readInt());
            input.readUTF();
            input.readLong(); input.readLong();
            input.readUTF(); input.readUTF();
            for (int index = 0; index < 6; index++) input.readLong();
            input.readUTF(); input.readUTF(); input.readUTF();
            int modules = input.readInt();
            for (int index = 0; index < modules; index++) { input.readUTF(); input.readUTF(); }
            int dimensions = input.readInt();
            for (int index = 0; index < dimensions; index++) { input.readUTF(); input.readUTF(); }
            return bytes.length - input.available();
        }
    }

    /** Reuse storage's existing package-private checkpoint without adding a production mutation API. */
    private static LocalInstallationIndex withAfterPublishFailure(Path world, AtomicInteger faults, IOException injected) throws Exception {
        Class<?> checkpointType = Class.forName(LocalProjectStore.class.getName() + "$Checkpoint");
        Object checkpoint = java.lang.reflect.Proxy.newProxyInstance(LocalProjectStore.class.getClassLoader(),
                new Class<?>[] {checkpointType}, (proxy, method, arguments) -> {
                    if (method.getName().equals("reached") && arguments[0].toString().equals("AFTER_PUBLISH")
                            && faults.getAndIncrement() == 0) throw injected;
                    return null;
                });
        var storeConstructor = LocalProjectStore.class.getDeclaredConstructor(Path.class, StoreLimits.class,
                boolean.class, checkpointType);
        storeConstructor.setAccessible(true);
        LocalProjectStore store = storeConstructor.newInstance(directory(world), StoreLimits.DEFAULT, false, checkpoint);
        try {
            var indexConstructor = LocalInstallationIndex.class.getDeclaredConstructor(
                    net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot.class,
                    FileIdentityProvider.class, LocalProjectStore.class);
            indexConstructor.setAccessible(true);
            return indexConstructor.newInstance(WorldIdentities.read(world, IDENTITIES, () -> false), IDENTITIES, store);
        } catch (Exception | Error failure) {
            try { store.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static InstallationPlan plan(UUID id, InstallationPlan.Key key) {
        return new InstallationPlan(id, key, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EditorId.parse("test:adapter"), EditorId.parse("test:factory"), "1.20.1",
                Map.of(EditorId.parse("test:module"), "4.2"),
                Map.of(EditorId.parse("minecraft:overworld"), EditorId.parse("test:arena")));
    }

    private static LocalInstallationIndex.Record reserve(LocalInstallationIndex index, InstallationPlan plan) throws IOException {
        return index.reserve(index.currentCommitId(), UUID.randomUUID(), plan, new ByteArrayInputStream(ARCHIVE));
    }

    /** Publish deliberately incompatible domain content through valid generic storage, not a damaged hash. */
    private static void rewriteRecord(Path world, UnaryOperator<byte[]> mutation) throws IOException {
        try (LocalProjectStore raw = LocalProjectStore.open(directory(world), StoreLimits.DEFAULT)) {
            var saved = raw.load();
            ProjectState old = saved.state();
            ProjectState.Entry entry = old.documents().values().iterator().next();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            raw.copyContent(entry.content(), bytes);
            BlobRef rewritten = raw.stage(BlobRef.Kind.DOCUMENT, mutation.apply(bytes.toByteArray()));
            ProjectState.Entry changed = new ProjectState.Entry(entry.id(), entry.ownerModule(), entry.typeId(),
                    entry.codecId(), entry.schemaVersion(), entry.displayName(), rewritten,
                    entry.documentDependencies(), entry.assetDependencies());
            raw.commit(saved.commitId(), UUID.randomUUID(), new ProjectState(old.projectId(), old.name(), old.modules(),
                    Map.of(changed.id(), changed), old.assets(), old.origin()));
        }
    }

    private static void assertProtected(Path world, ProjectStorageException.Code code) throws Exception {
        Map<String, String> before = files(world);
        expect(code, () -> {
            try (LocalInstallationIndex ignored = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow()) {
                fail("Incompatible ownership must not be opened");
            }
        });
        // Existing lock-file bytes are included. No failed-open cleanup may erase the damaged evidence.
        assertEquals(before, files(world));
    }

    private static void expect(ProjectStorageException.Code code, Checked action) {
        assertEquals(code, assertThrows(ProjectStorageException.class, action::run).code());
    }

    private static Map<String, String> files(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString(), HexFormat.of().formatHex(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    /** Windows refuses reading an OS-locked file; only its existence/type/length are observed live. */
    private static Map<String, String> liveFiles(Path root) throws IOException {
        Path lock = directory(root).resolve(".editor.lock");
        assertTrue(Files.isRegularFile(lock, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString(), path.equals(lock)
                        ? "held-lock-length:" + Files.size(path)
                        : HexFormat.of().formatHex(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    /** Complete byte comparison resumes immediately after close, including the original lock bytes. */
    private static void assertClosedFiles(Path root, Map<String, String> liveBaseline, byte[] originalLock) throws IOException {
        String lock = root.relativize(directory(root).resolve(".editor.lock")).toString();
        assertEquals("held-lock-length:" + originalLock.length, liveBaseline.get(lock));
        Map<String, String> expected = new TreeMap<>(liveBaseline);
        expected.put(lock, HexFormat.of().formatHex(originalLock));
        assertEquals(expected, files(root));
    }

    private static Path platform(Path world) { return world.resolve(".editor-platform"); }
    private static Path directory(Path world) { return platform(world).resolve("installations"); }
    private static Path marker(Path world) { return platform(world).resolve("installations.owner"); }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
