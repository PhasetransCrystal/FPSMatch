package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationBundle;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.installation.LocalInstallationIndex;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProviders;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import com.google.gson.JsonParser;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Complete archives and persistent ownership through public saved-preparation APIs only. */
class InstallationBundleTest {
    private static final UUID DOCUMENT = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final EditorId ADAPTER = EditorId.parse("thirdparty:installation_text");
    private static final EditorId FACTORY = EditorId.parse("thirdparty:text_factory");
    private static final InstallationPlan.Key KEY = new InstallationPlan.Key("thirdparty", "Exact install");
    private static final Map<EditorId, EditorId> DIMENSIONS = Map.of(
            EditorId.parse("minecraft:overworld"), EditorId.parse("minecraft:overworld"));
    private static final FileIdentityProvider IDENTITIES = System.getProperty("os.name").startsWith("Windows")
            ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
    private static final BooleanSupplier CONTINUE = () -> false;

    @TempDir Path temporary;

    @Test
    void savedAExportsWhileCurrentIsBAndReopensFromDurableBytesAfterSourceCloses() throws Exception {
        AtomicInteger preparations = new AtomicInteger();
        ExtensionRegistry registry = registered(preparations);
        Path world = world();
        InstallationPlan plan;
        BlobRef archiveBytes;
        UUID currentB;
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(world, IDENTITIES)) {
            try (Fixture fixture = fixture(registry)) {
                PreparedRuntime<String> preparedA = prepare(fixture);
                UUID originalCommit = preparedA.source().saved().commitId();
                currentB = saveB(fixture);
                assertNotEquals(originalCommit, currentB);
                try (InstallationBundle<String> bundle = capture(fixture, preparedA)) {
                    assertEquals("saved A", bundle.preparation().value());
                    plan = plan(bundle);
                    archiveBytes = bundle.bytes();
                    assertEquals(originalCommit, plan.commitId());
                    assertEquals(preparedA.source().saved().state().projectId(), plan.projectId());
                    assertEquals(DOCUMENT, plan.documentId());
                    assertEquals(ADAPTER, plan.adapterId());
                    assertEquals(Map.of(OWNER, BUILD), plan.modules());
                    LocalInstallationIndex.Record reserved = bundle.reserve(index, index.currentCommitId(),
                            UUID.randomUUID(), plan, CONTINUE);
                    assertEquals(archiveBytes, reserved.bundle());
                    assertEquals(LocalInstallationIndex.State.PREPARED, reserved.state());
                }
                assertEquals(currentB, fixture.store().load().commitId());
                assertEquals("current B", new String(fixture.access().read().documentBytes(DOCUMENT), StandardCharsets.UTF_8));
            }
        }
        // The source writer, source access and capturing bundle are all closed before this read.
        Files.move(fresh("source"), fresh("retained-source"));
        Files.move(fresh("source.zip"), fresh("retained-source.zip"));
        int beforeRecovery = preparations.get();
        Path recoveredProject = fresh("recovered-project");
        try (LocalInstallationIndex reopenedIndex = LocalInstallationIndex.openIfPresent(world, IDENTITIES).orElseThrow();
             InstallationBundle<String> recovered = InstallationBundle.reopen(reopenedIndex, KEY, registry,
                     String.class, fresh("recovered.zip"), recoveredProject, IDENTITIES, CONTINUE)) {
            assertTrue(preparations.get() > beforeRecovery, "Recovery must run the registered adapter again");
            assertEquals("saved A", recovered.preparation().value());
            assertEquals(archiveBytes, recovered.bytes());
            assertNotEquals(plan.commitId(), recovered.preparation().source().saved().commitId());
            assertNotEquals(currentB, recovered.preparation().source().saved().commitId());
            assertEquals(new ProjectState.Origin(plan.projectId(), plan.commitId()),
                    recovered.preparation().source().saved().state().origin());
            assertEquals(plan, recovered.plan(plan.installationId(), KEY, FACTORY, "1.20.1", DIMENSIONS));
            recovered.verify(CONTINUE);
        }
        try (LocalProjectStore scratch = LocalProjectStore.open(recoveredProject)) {
            assertEquals(plan.commitId(), scratch.load().state().origin().commitId(),
                    "Closing a recovered bundle must release its scratch writer lock");
        }
    }

    @Test
    void equivalentRegistryCannotExportAnotherRegistrysPreparation() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        ExtensionRegistry equivalent = registered(new AtomicInteger());
        Path archive = fresh("wrong-registry.zip");
        Path verification = fresh("wrong-registry-verification");
        try (Fixture fixture = fixture(registry)) {
            PreparedRuntime<String> prepared = prepare(fixture);
            expect(ProjectStorageException.Code.REQUEST_MISMATCH, () -> InstallationBundle.capture(fixture.access(),
                    prepared, equivalent, archive, verification, IDENTITIES, CONTINUE));
            assertFalse(Files.exists(archive));
            assertFalse(Files.exists(verification));
        }
    }

    @Test
    void changedPlanProvenanceCannotPublishEvenPreparedOwnership() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationPlan original = plan(bundle);
            List<InstallationPlan> mismatches = List.of(
                    copy(original, UUID.randomUUID(), original.commitId(), DOCUMENT, ADAPTER, original.modules()),
                    copy(original, original.projectId(), UUID.randomUUID(), DOCUMENT, ADAPTER, original.modules()),
                    copy(original, original.projectId(), original.commitId(), UUID.randomUUID(), ADAPTER, original.modules()),
                    copy(original, original.projectId(), original.commitId(), DOCUMENT, EditorId.parse("thirdparty:other"), original.modules()),
                    copy(original, original.projectId(), original.commitId(), DOCUMENT, ADAPTER, Map.of(OWNER, "wrong")));
            UUID head = index.currentCommitId();
            for (InstallationPlan mismatch : mismatches) {
                UUID request = UUID.randomUUID();
                expect(ProjectStorageException.Code.REQUEST_MISMATCH,
                        () -> bundle.reserve(index, head, request, mismatch, CONTINUE));
                assertTrue(index.records().isEmpty());
                assertEquals(head, index.currentCommitId());
                assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
            }
            bundle.reserve(index, head, UUID.randomUUID(), original, CONTINUE);
            assertEquals(original, index.records().get(KEY).plan());
        }
    }

    @Test
    void archiveReplacementWithIdenticalBytesCannotReuseItsPhysicalCertification() throws Exception {
        rejectChangedArchive(true);
    }

    @Test
    void sameLengthArchiveTamperingCannotPublishOwnershipOrEraseTheEvidence() throws Exception {
        rejectChangedArchive(false);
    }

    @Test
    void damagedCertifiedSourceCannotBeHiddenByAnIntactExportedArchive() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            BlobRef content = bundle.preparation().source().saved().state().documents().get(DOCUMENT).content();
            Path blob = fresh("source").resolve("documents").resolve(content.sha256() + ".blob");
            byte[] damaged = Files.readAllBytes(blob);
            damaged[0] ^= 1;
            Files.write(blob, damaged);
            byte[] archiveBefore = Files.readAllBytes(archive(fixture));
            UUID head = index.currentCommitId();
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> bundle.verify(CONTINUE));
            expect(ProjectStorageException.Code.CORRUPT_CONTENT,
                    () -> bundle.reserve(index, head, UUID.randomUUID(), plan(bundle), CONTINUE));
            assertTrue(index.records().isEmpty());
            assertEquals(head, index.currentCommitId());
            assertArrayEquals(damaged, Files.readAllBytes(blob));
            assertArrayEquals(archiveBefore, Files.readAllBytes(archive(fixture)));
        }
    }

    @Test
    void wrongOriginalCommitInPersistentRecordIsRejectedAfterActualImport() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        Path imported = fresh("wrong-origin-project");
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationPlan real = plan(bundle);
            InstallationPlan wrong = copy(real, real.projectId(), UUID.randomUUID(), DOCUMENT, ADAPTER, real.modules());
            reserveRaw(index, wrong, Files.readAllBytes(archive(fixture)));
            expect(ProjectStorageException.Code.REQUEST_MISMATCH, () -> InstallationBundle.reopen(index, KEY, registry,
                    String.class, fresh("wrong-origin.zip"), imported, IDENTITIES, CONTINUE));
            assertEquals(wrong, index.records().get(KEY).plan());
            try (LocalProjectStore retained = LocalProjectStore.open(imported)) {
                assertEquals(real.commitId(), retained.load().state().origin().commitId());
            }
        }
    }

    @Test
    void persistentRuntimeModulesMustEqualFreshAdapterPreparation() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        Path imported = fresh("wrong-modules-project");
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationPlan real = plan(bundle);
            InstallationPlan wrong = copy(real, real.projectId(), real.commitId(), DOCUMENT, ADAPTER, Map.of(OWNER, "wrong"));
            reserveRaw(index, wrong, Files.readAllBytes(archive(fixture)));
            expect(ProjectStorageException.Code.REQUEST_MISMATCH, () -> InstallationBundle.reopen(index, KEY, registry,
                    String.class, fresh("wrong-modules.zip"), imported, IDENTITIES, CONTINUE));
            assertEquals(wrong, index.records().get(KEY).plan());
            try (LocalProjectStore retained = LocalProjectStore.open(imported)) {
                assertEquals(BUILD, retained.load().state().modules().get(OWNER.toString()));
            }
        }
    }

    @Test
    void unavailableDocumentOrAdapterCannotBeSubstitutedDuringReopen() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationPlan original = plan(bundle);
            InstallationPlan missingDocument = copy(original, original.projectId(), original.commitId(),
                    UUID.randomUUID(), ADAPTER, original.modules());
            reserveRaw(index, missingDocument, Files.readAllBytes(archive(fixture)));
            assertThrows(SessionRejection.class, () -> InstallationBundle.reopen(index, KEY, registry, String.class,
                    fresh("missing-document.zip"), fresh("missing-document-project"), IDENTITIES, CONTINUE));
            assertEquals(missingDocument, index.records().get(KEY).plan());
        }
        // A second independent index permits the same exact runtime key without mutating the first record.
        try (Fixture fixture = project(fresh("adapter-source"), registry, List.of(source(DOCUMENT, "saved A")));
             InstallationBundle<String> bundle = InstallationBundle.capture(fixture.access(), prepare(fixture), registry,
                     fresh("adapter-source.zip"), fresh("adapter-verification"), IDENTITIES, CONTINUE);
             LocalInstallationIndex index = LocalInstallationIndex.initialize(newWorld(), IDENTITIES)) {
            InstallationPlan original = plan(bundle);
            InstallationPlan missingAdapter = copy(original, original.projectId(), original.commitId(), DOCUMENT,
                    EditorId.parse("thirdparty:unavailable_adapter"), original.modules());
            reserveRaw(index, missingAdapter, Files.readAllBytes(temporary.resolve("adapter-source.zip")));
            assertThrows(SessionRejection.class, () -> InstallationBundle.reopen(index, KEY, registry, String.class,
                    fresh("missing-adapter.zip"), fresh("missing-adapter-project"), IDENTITIES, CONTINUE));
            assertEquals(missingAdapter, index.records().get(KEY).plan());
        }
    }

    @Test
    void arbitraryIndexBytesAreNotCertifiedAsAnInstallableZip() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        Path copied = fresh("invalid.zip");
        Path imported = fresh("invalid-project");
        byte[] invalid = "not a saved project archive".getBytes(StandardCharsets.UTF_8);
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationPlan plan = plan(bundle);
            reserveRaw(index, plan, invalid);
            assertThrows(java.io.IOException.class, () -> InstallationBundle.reopen(index, KEY, registry, String.class,
                    copied, imported, IDENTITIES, CONTINUE));
            assertArrayEquals(invalid, Files.readAllBytes(copied));
            assertFalse(Files.exists(imported));
            assertEquals(plan, index.records().get(KEY).plan());
        }
    }

    @Test
    void expectedBlobMismatchCannotPublishOrPoisonAnOtherwiseUsableIndex() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            byte[] archive = Files.readAllBytes(archive(fixture));
            archive[archive.length / 2] ^= 1;
            UUID head = index.currentCommitId();
            UUID request = UUID.randomUUID();
            expect(ProjectStorageException.Code.CORRUPT_CONTENT, () -> index.reserve(head, request,
                    plan(bundle), new ByteArrayInputStream(archive), bundle.bytes()));
            assertEquals(head, index.currentCommitId());
            assertTrue(index.records().isEmpty());
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
            bundle.reserve(index, head, UUID.randomUUID(), plan(bundle), CONTINUE);
            assertEquals(1, index.records().size());
        }
    }

    @Test
    void cancellationBeforeCaptureCreatesNoArchiveAndBeforeReservePublishesNoRecord() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        Path cancelledArchive = fresh("cancelled.zip");
        Path cancelledVerification = fresh("cancelled-verification");
        try (Fixture fixture = fixture(registry)) {
            PreparedRuntime<String> prepared = prepare(fixture);
            assertThrows(ResourceValidationCancelledException.class, () -> InstallationBundle.capture(fixture.access(),
                    prepared, registry, cancelledArchive, cancelledVerification, IDENTITIES, () -> true));
            assertFalse(Files.exists(cancelledArchive));
            assertFalse(Files.exists(cancelledVerification));
            try (InstallationBundle<String> bundle = capture(fixture, prepared);
                 LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
                UUID head = index.currentCommitId();
                assertThrows(ResourceValidationCancelledException.class, () -> bundle.verify(() -> true));
                assertThrows(ResourceValidationCancelledException.class,
                        () -> bundle.reserve(index, head, UUID.randomUUID(), plan(bundle), () -> true));
                assertEquals(head, index.currentCommitId());
                assertTrue(index.records().isEmpty());
                bundle.reserve(index, head, UUID.randomUUID(), plan(bundle), CONTINUE);
                Path reopenArchive = fresh("cancelled-reopen.zip");
                Path reopenProject = fresh("cancelled-reopen-project");
                assertThrows(ResourceValidationCancelledException.class, () -> InstallationBundle.reopen(index, KEY,
                        registry, String.class, reopenArchive, reopenProject, IDENTITIES, () -> true));
                assertFalse(Files.exists(reopenArchive));
                assertFalse(Files.exists(reopenProject));
            }
        }
    }

    @Test
    void closingCapturedBundleRevokesItsOperationsWhileLeavingBorrowedSourceUsable() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
            InstallationPlan plan = plan(bundle);
            bundle.close();
            bundle.close();
            assertThrows(IllegalStateException.class, bundle::preparation);
            assertThrows(IllegalStateException.class, bundle::bytes);
            assertThrows(IllegalStateException.class, () -> bundle.verify(CONTINUE));
            assertThrows(IllegalStateException.class, () -> bundle.requirePlan(plan));
            assertThrows(IllegalStateException.class, () -> bundle.plan(UUID.randomUUID(), KEY, FACTORY, "1.20.1", DIMENSIONS));
            assertThrows(IllegalStateException.class,
                    () -> bundle.reserve(index, index.currentCommitId(), UUID.randomUUID(), plan, CONTINUE));
            assertEquals("saved A", prepare(fixture).value());
            assertTrue(index.records().isEmpty());
            assertTrue(Files.exists(archive(fixture)));
        }
    }

    @Test
    void reopenRejectsLegalArchiveSwappedAfterCopyEvenWhenAdapterRestoresOriginalBytes() throws Exception {
        Path reopenedArchive = fresh("swapped-reopen.zip");
        AtomicBoolean restoreAtAdapter = new AtomicBoolean();
        AtomicBoolean restored = new AtomicBoolean();
        byte[][] original = new byte[1][];
        ExtensionRegistry registry = registered(new AtomicInteger(), input -> {
            if (restoreAtAdapter.get()) {
                assertEquals("changed", input.content(), "The counterexample must reach the substituted document");
                try { Files.write(reopenedArchive, original[0]); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                restored.set(true);
            }
        });
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationPlan plan = plan(bundle);
            bundle.reserve(index, index.currentCommitId(), UUID.randomUUID(), plan, CONTINUE);
            original[0] = Files.readAllBytes(archive(fixture));
            byte[] replacement = archiveWithChangedDocument(original[0]);
            // Prove the substituted bytes are a complete legal archive with the same asserted origin.
            Path replacementFile = fresh("legal-replacement.zip");
            Files.write(replacementFile, replacement);
            var imported = LocalProjectStore.importSnapshot(replacementFile, fresh("replacement-proof"));
            assertEquals(plan.projectId(), imported.state().projectId());
            assertEquals(new ProjectState.Origin(plan.projectId(), plan.commitId()), imported.state().origin());
            try (LocalProjectStore proof = LocalProjectStore.open(fresh("replacement-proof"))) {
                LocalProjectAccess access = new LocalProjectAccess(proof);
                assertEquals("changed", access.prepareValidatedRuntime(access.readValidated(registry, CONTINUE),
                        registry, DOCUMENT, ADAPTER, String.class, CONTINUE).value());
            }
            AtomicBoolean swapped = new AtomicBoolean();
            BooleanSupplier swapAfterCopy = () -> {
                if (Files.exists(reopenedArchive) && swapped.compareAndSet(false, true)) {
                    try {
                        assertArrayEquals(original[0], Files.readAllBytes(reopenedArchive));
                        Files.write(reopenedArchive, replacement);
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                    restoreAtAdapter.set(true);
                }
                return false;
            };
            assertThrows(ProjectStorageException.class, () -> {
                try (InstallationBundle<String> ignored = InstallationBundle.reopen(index, KEY, registry, String.class,
                        reopenedArchive, fresh("swapped-project"), IDENTITIES, swapAfterCopy)) {
                    fail("A preparation from substituted bytes must never be returned under the original blob reference");
                }
            });
            assertTrue(swapped.get(), "Substitution must happen at the first cancellation callback after copy");
            if (restored.get()) assertArrayEquals(original[0], Files.readAllBytes(reopenedArchive));
            assertEquals(plan, index.records().get(KEY).plan());
            assertEquals(bundle.bytes(), index.records().get(KEY).bundle());
        }
    }

    @Test
    void cancellationSupplierClosingBundleCannotLetVerifyOrReserveSucceed() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            PreparedRuntime<String> prepared = prepare(fixture);
            try (InstallationBundle<String> verifying = InstallationBundle.capture(fixture.access(), prepared, registry,
                    fresh("verify-close.zip"), fresh("verify-close-project"), IDENTITIES, CONTINUE)) {
                AtomicBoolean closed = new AtomicBoolean();
                assertThrows(IllegalStateException.class, () -> verifying.verify(() -> {
                    try { verifying.close(); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                    closed.set(true);
                    return false;
                }));
                assertTrue(closed.get());
                assertThrows(IllegalStateException.class, verifying::preparation);
            }
            try (InstallationBundle<String> reserving = InstallationBundle.capture(fixture.access(), prepared, registry,
                    fresh("reserve-close.zip"), fresh("reserve-close-project"), IDENTITIES, CONTINUE)) {
                InstallationPlan plan = plan(reserving);
                UUID head = index.currentCommitId();
                UUID request = UUID.randomUUID();
                AtomicBoolean closed = new AtomicBoolean();
                assertThrows(IllegalStateException.class, () -> reserving.reserve(index, head, request, plan, () -> {
                    try { reserving.close(); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                    closed.set(true);
                    return false;
                }));
                assertTrue(closed.get());
                assertEquals(head, index.currentCommitId());
                assertTrue(index.records().isEmpty());
                assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, index.lookup(request).status());
            }
            assertEquals("saved A", prepare(fixture).value());
        }
    }

    @Test
    void replacementRetainsBothCertifiedSavedInputsAndPreviousReopenUsesExactRecord() throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> first = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            InstallationPlan oldPlan = plan(first);
            first.reserve(index, index.currentCommitId(), UUID.randomUUID(), oldPlan, CONTINUE);
            LocalInstallationIndex.Record old = index.transition(index.currentCommitId(), UUID.randomUUID(), KEY,
                    LocalInstallationIndex.State.ACTIVE);
            saveB(fixture);
            try (InstallationBundle<String> second = InstallationBundle.capture(fixture.access(), prepare(fixture), registry,
                    fresh("second.zip"), fresh("second-check"), IDENTITIES, CONTINUE)) {
                InstallationPlan nextPlan = plan(second);
                UUID head = index.currentCommitId();
                assertThrows(ResourceValidationCancelledException.class, () -> second.beginReplacement(index, head,
                        UUID.randomUUID(), old, nextPlan, () -> true));
                assertEquals(old, index.records().get(KEY));
                assertEquals(head, index.currentCommitId());
                LocalInstallationIndex.Record next = second.beginReplacement(index, head, UUID.randomUUID(), old,
                        nextPlan, CONTINUE);
                assertEquals(new LocalInstallationIndex.Previous(oldPlan, first.bytes()), next.previous());
                assertEquals(second.bytes(), next.bundle());
                try (InstallationBundle<String> previous = InstallationBundle.reopenPrevious(index, KEY, registry,
                             String.class, fresh("old.zip"), fresh("old-project"), IDENTITIES, CONTINUE);
                     InstallationBundle<String> current = InstallationBundle.reopen(index, KEY, registry,
                             String.class, fresh("new.zip"), fresh("new-project"), IDENTITIES, CONTINUE)) {
                    assertEquals("saved A", previous.preparation().value());
                    assertEquals("current B", current.preparation().value());
                    assertEquals(oldPlan, previous.plan(oldPlan.installationId(), KEY, FACTORY, "1.20.1", DIMENSIONS));
                    assertEquals(next, index.records().get(KEY));
                    assertEquals(first.bytes(), previous.bytes());
                    assertEquals(second.bytes(), current.bytes());
                }
                AtomicBoolean changed = new AtomicBoolean();
                expect(ProjectStorageException.Code.CONFLICT, () -> InstallationBundle.reopenPrevious(index, KEY,
                        registry, String.class, fresh("changed-old.zip"), fresh("changed-old-project"), IDENTITIES, () -> {
                            if (changed.compareAndSet(false, true)) {
                                try { index.transition(index.currentCommitId(), UUID.randomUUID(), KEY,
                                        LocalInstallationIndex.State.RECOVERY_REQUIRED); }
                                catch (IOException failure) { throw new UncheckedIOException(failure); }
                            }
                            return false;
                        }));
                assertNotNull(index.records().get(KEY).previous());
            }
        }
    }

    private static byte[] archiveWithChangedDocument(byte[] original) throws Exception {
        byte[] changed = "changed".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(changed));
        byte[] manifest = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(original))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals("snapshot.json")) manifest = zip.readAllBytes();
            }
        }
        assertNotNull(manifest);
        var metadata = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        var content = metadata.getAsJsonArray("documents").get(0).getAsJsonObject().getAsJsonObject("content");
        content.addProperty("sha256", digest);
        content.addProperty("size", changed.length);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("snapshot.json"));
            zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("documents/" + digest + ".blob"));
            zip.write(changed);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private void rejectChangedArchive(boolean replace) throws Exception {
        ExtensionRegistry registry = registered(new AtomicInteger());
        try (Fixture fixture = fixture(registry);
             InstallationBundle<String> bundle = capture(fixture, prepare(fixture));
             LocalInstallationIndex index = LocalInstallationIndex.initialize(world(), IDENTITIES)) {
            Path archive = archive(fixture);
            byte[] bytes = Files.readAllBytes(archive);
            if (replace) {
                Files.move(archive, fresh("retained-original.zip"));
            } else {
                bytes[bytes.length / 2] ^= 1;
            }
            Files.write(archive, bytes);
            UUID head = index.currentCommitId();
            expect(ProjectStorageException.Code.CONFLICT, () -> bundle.verify(CONTINUE));
            expect(ProjectStorageException.Code.CONFLICT,
                    () -> bundle.reserve(index, head, UUID.randomUUID(), plan(bundle), CONTINUE));
            assertEquals(head, index.currentCommitId());
            assertTrue(index.records().isEmpty());
            assertArrayEquals(bytes, Files.readAllBytes(archive));
        }
    }

    private Fixture fixture(ExtensionRegistry registry) throws Exception {
        return project(fresh("source"), registry, List.of(source(DOCUMENT, "saved A")));
    }

    private static PreparedRuntime<String> prepare(Fixture fixture) throws Exception {
        LoadedProject loaded = fixture.access().readValidated(fixture.registry(), CONTINUE);
        return fixture.access().prepareValidatedRuntime(loaded, fixture.registry(), DOCUMENT, ADAPTER, String.class, CONTINUE);
    }

    private InstallationBundle<String> capture(Fixture fixture, PreparedRuntime<String> prepared) throws Exception {
        return InstallationBundle.capture(fixture.access(), prepared, fixture.registry(), archive(fixture),
                fresh("verification"), IDENTITIES, CONTINUE);
    }

    private static InstallationPlan plan(InstallationBundle<String> bundle) {
        return bundle.plan(UUID.randomUUID(), KEY, FACTORY, "1.20.1", DIMENSIONS);
    }

    private static InstallationPlan copy(InstallationPlan original, UUID project, UUID commit, UUID document,
                                         EditorId adapter, Map<EditorId, String> modules) {
        return new InstallationPlan(original.installationId(), original.key(), project, commit, document, adapter,
                original.factoryId(), original.targetVersion(), modules, original.dimensions());
    }

    private static UUID saveB(Fixture fixture) throws Exception {
        var saved = fixture.store().load();
        ProjectState.Entry entry = new ProjectState.Entry(DOCUMENT, OWNER.toString(), TYPE.toString(), CODEC.toString(),
                1, "B", fixture.store().stage(BlobRef.Kind.DOCUMENT, "current B".getBytes(StandardCharsets.UTF_8)), Set.of(), Set.of());
        return fixture.store().commit(saved.commitId(), UUID.randomUUID(), new ProjectState(saved.state().projectId(),
                "B current", saved.state().modules(), Map.of(DOCUMENT, entry), Map.of(), null)).currentCommitId();
    }

    private static ExtensionRegistry registered(AtomicInteger preparations) {
        return registered(preparations, input -> { });
    }

    private static ExtensionRegistry registered(AtomicInteger preparations, Consumer<SavedRuntimeInput<String>> observe) {
        var builder = builder(new TestType());
        builder.define(SavedRuntimeAdapters.KIND);
        builder.add(SavedRuntimeAdapters.KIND, new Contribution(new ContributionKey(SavedRuntimeAdapters.KIND.id(), ADAPTER),
                OWNER, Contribution.Side.COMMON, Set.of(new ContributionKey(DocumentTypes.KIND.id(), TYPE))),
                new SavedRuntimeAdapter<String, String>() {
                    @Override public EditorId documentType() { return TYPE; }
                    @Override public Class<String> inputType() { return String.class; }
                    @Override public Class<String> resultType() { return String.class; }
                    @Override public String prepare(SavedRuntimeInput<String> input, BooleanSupplier cancelled) {
                        preparations.incrementAndGet();
                        observe.accept(input);
                        return input.content();
                    }
                });
        return builder.freeze();
    }

    private static void reserveRaw(LocalInstallationIndex index, InstallationPlan plan, byte[] bytes) throws Exception {
        index.reserve(index.currentCommitId(), UUID.randomUUID(), plan, new ByteArrayInputStream(bytes));
    }

    private static void expect(ProjectStorageException.Code code, Executable action) {
        assertEquals(code, assertThrows(ProjectStorageException.class, action).code());
    }

    private Path world() throws Exception {
        Path world = Files.createDirectory(fresh("world"));
        WorldIdentities.initialize(world, IDENTITIES, CONTINUE);
        return world;
    }

    private Path newWorld() throws Exception {
        Path world = Files.createDirectory(fresh("other-world"));
        WorldIdentities.initialize(world, IDENTITIES, CONTINUE);
        return world;
    }

    private Path fresh(String name) { return temporary.resolve(name); }
    private Path archive(Fixture ignored) { return fresh("source.zip"); }
}
