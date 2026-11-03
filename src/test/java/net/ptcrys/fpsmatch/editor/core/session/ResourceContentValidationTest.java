package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContent;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidator;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic format behavior through public registry/store/session APIs only. */
class ResourceContentValidationTest {
    @TempDir Path directory;
    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final EditorId IMPORTER = EditorId.parse("synthetic:raw_validator");
    private static final EditorId ASSET_TYPE = EditorId.parse("synthetic:raw");
    private static final EditorId RESOURCES = EditorId.parse("editor:resources");
    private static final EditorId OTHER_OWNER = EditorId.parse("other:module");
    private static final UUID RESOURCE = id(1), OLD = id(2), CURRENT = id(3), BYTE = id(4), EMPTY_BYTE = id(5), DOCUMENT = id(6);
    private static final TextContent EDIT = new TextContent.Literal("edit synthetic fixture");
    private static final ResourceContentValidator ACCEPT = (version, content) -> List.of();

    @Test void declarationAndOrdinaryReadNeverAuthorizePublication() throws Exception {
        ExtensionRegistry declaredOnly = registry(null, OWNER, Set.of("old", "new"), false);
        try (Fixture f = fixture("declarations", 32)) {
            LoadedProject plain = f.access.read();
            try (ProjectSession session = ProjectSession.open(f.access, plain, f.registry, 2)) {
                assertTrue(session.protectedMode());
                assertCode(session.problems(), "resource_content_validation_required");
                assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(DOCUMENT)));
            }
            LoadedProject refused = f.access.readValidated(declaredOnly, () -> false);
            assertCode(refused.problems(), "resource_content_validator_unavailable");
            assertEquals(plain.saved(), refused.saved());
            assertEquals(plain.resources(), refused.resources());
            try (ProjectSession session = f.open()) {
                var capture = session.captureSave(Set.of(DOCUMENT));
                var before = f.store.load();
                assertCode(assertThrows(SessionRejection.class, () -> f.access.prepare(capture)).problems(),
                        "resource_content_validation_required");
                assertEquals(before, f.store.load());
                session.enqueueSave(f.access.prepareValidated(capture, () -> false));
                assertEquals(1, session.saves().queued());
            }
        }
    }

    @Test void emptyVersionsKeepBothLegacyReadAndPreparePaths() throws Exception {
        var registry = ProjectSessionFixtures.registry(new TestType());
        try (var f = project(directory.resolve("empty"), registry, List.of(source(DOCUMENT, "plain")))) {
            try (ProjectSession session = f.open()) {
                assertFalse(session.protectedMode());
                session.enqueueSave(f.access().prepare(session.captureSave(Set.of(DOCUMENT))));
                assertTrue(f.access().prepareValidated(session.captureSave(Set.of(DOCUMENT)), () -> false).problems().isEmpty());
            }
            try (ProjectSession session = ProjectSession.open(f.access(), f.access().readValidated(registry, () -> false), registry, 2)) {
                assertFalse(session.protectedMode());
            }
        }
    }

    @Test void validationCatalogRequiresSameOwnerAndDistinguishesMissingKindFromWrongClass() throws Exception {
        try (Fixture f = fixture("owners", 8)) {
            var oldVersion = f.catalog.versions().get(OLD);
            var missing = registry(null, OWNER, Set.of("old", "new"), false);
            assertCode(assertThrows(SessionRejection.class, () -> ResourceProducerCatalog.activate(missing)
                    .requireValidationContribution(oldVersion)).problems(), "resource_content_validator_unavailable");
            var mismatch = registry(ACCEPT, OTHER_OWNER, Set.of("old", "new"), true);
            assertCode(assertThrows(SessionRejection.class, () -> ResourceProducerCatalog.activate(mismatch)
                    .requireValidationContribution(oldVersion)).problems(), "resource_content_validator_owner_mismatch");
            assertCode(f.access.readValidated(mismatch, () -> false).problems(), "resource_content_validator_owner_mismatch");
            // A separately built registry with the right ID and wrong value class is an assembly error.
            ExtensionRegistryBuilder wrong = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
            wrong.define(net.ptcrys.fpsmatch.editor.core.types.DocumentTypes.KIND);
            wrong.define(ResourceProducers.KIND);
            wrong.define(new ExtensionKind<>(ResourceContentValidators.KIND.id(), String.class));
            wrong.addModule(module(OWNER));
            wrong.add(ResourceProducers.KIND, contribution(ResourceProducers.KIND, OWNER), new ResourceProducerSpec(Set.of("old")));
            ExtensionRegistry wrongClass = wrong.freeze();
            assertThrows(IllegalArgumentException.class, () -> ResourceProducerCatalog.activate(wrongClass)
                    .requireValidationContribution(oldVersion));
            assertEquals(new ContributionKey(ResourceContentValidators.KIND.id(), IMPORTER),
                    ResourceProducerCatalog.activate(f.registry).requireValidationContribution(oldVersion));
        }
    }

    @Test void exactVersionPreflightCoversInactiveRetainedVersionsBeforeAnyCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture f = fixture("preflight", 8)) {
            ExtensionRegistry newerOnly = registry((version, content) -> { calls.incrementAndGet(); return List.of(); },
                    OWNER, Set.of("new", " old "), true);
            LoadedProject refused = f.access.readValidated(newerOnly, () -> false);
            assertCode(refused.problems(), "resource_producer_version_unavailable");
            assertEquals(0, calls.get());
            List<UUID> seen = new ArrayList<>();
            f.validator.set((version, content) -> {
                seen.add(version.id());
                assertEquals(f.catalog.versions(), content.versions());
                assertEquals(Map.of(RESOURCE, ASSET_TYPE), content.resourceTypes());
                assertEquals(f.catalog.byteEntries(), content.byteEntries());
                assertEquals(List.of(OLD, CURRENT), new ArrayList<>(content.versions().keySet()));
                assertThrows(UnsupportedOperationException.class, () -> content.versions().clear());
                assertThrows(UnsupportedOperationException.class, () -> content.resourceTypes().clear());
                assertThrows(UnsupportedOperationException.class, () -> content.byteEntries().clear());
                return List.of();
            });
            f.access.readValidated(f.registry, () -> false);
            assertEquals(List.of(OLD, CURRENT), seen);
        }
    }

    @Test void validatedReadIsBoundToExactRegistryAndAccessInstances() throws Exception {
        try (Fixture f = fixture("binding", 8)) {
            LoadedProject loaded = f.access.readValidated(f.registry, () -> false);
            ExtensionRegistry sameConfiguration = registry(ACCEPT, OWNER, Set.of("old", "new"), true);
            try (ProjectSession session = ProjectSession.open(f.access, loaded, sameConfiguration, 2)) {
                assertTrue(session.protectedMode());
                assertCode(session.problems(), "resource_content_validation_required");
            }
            assertThrows(IllegalArgumentException.class,
                    () -> ProjectSession.open(new LocalProjectAccess(f.store), loaded, f.registry, 2));
            try (ProjectSession session = ProjectSession.open(f.access, loaded, f.registry, 2)) {
                assertFalse(session.protectedMode());
            }
        }
    }

    @Test void warningsAndDomainErrorsPreserveTypedMessagesAndOriginalBytes() throws Exception {
        Problem warning = problem(Problem.Severity.WARNING, "warning");
        Problem error = problem(Problem.Severity.ERROR, "error");
        try (Fixture f = fixture("diagnostics", 64)) {
            f.validator.set((version, content) -> version.id().equals(OLD) ? List.of(warning) : List.of());
            LoadedProject loaded = f.access.readValidated(f.registry, () -> false);
            assertEquals(List.of(warning), loaded.problems());
            try (ProjectSession session = ProjectSession.open(f.access, loaded, f.registry, 2)) {
                assertFalse(session.protectedMode());
                var capture = session.captureSave(Set.of(DOCUMENT));
                PreparedProjectSave prepared = f.access.prepareValidated(capture, () -> false);
                assertEquals(List.of(warning), prepared.problems());
                assertThrows(UnsupportedOperationException.class, () -> prepared.problems().clear());
                assertEquals(capture, prepared.capture());
            }
            f.validator.set((version, content) -> version.id().equals(OLD) ? List.of(warning, error) : List.of());
            LoadedProject refused = f.access.readValidated(f.registry, () -> false);
            assertEquals(List.of(warning, error), refused.problems());
            assertTrue(refused.resourceFailure().isPresent());
            try (ProjectSession session = ProjectSession.open(f.access, refused, f.registry, 2)) {
                assertTrue(session.protectedMode());
                assertEquals(f.catalog, session.source().resources().orElseThrow());
                assertEquals("before", session.find(DOCUMENT, String.class).orElseThrow().view().content());
                ByteArrayOutputStream original = new ByteArrayOutputStream();
                f.store.copyContent(session.source().saved().state().assets().get(BYTE).content(), original);
                assertEquals(64, original.size());
            }
        }
    }

    @Test void returnedDiagnosticListsAreFixedBeforeLaterCallbacksCanMutateThem() throws Exception {
        Problem warning = problem(Problem.Severity.WARNING, "fixed_warning");
        Problem later = problem(Problem.Severity.ERROR, "late_error");
        try (Fixture f = fixture("mutable-diagnostics", 8)) {
            List<Problem> returned = new ArrayList<>(List.of(warning));
            f.validator.set((version, content) -> {
                if (version.id().equals(OLD)) return returned;
                returned.set(0, later);
                return List.of();
            });
            LoadedProject loaded = f.access.readValidated(f.registry, () -> false);
            assertEquals(List.of(warning), loaded.problems());
            returned.clear();
            assertEquals(List.of(warning), loaded.problems());
            try (ProjectSession session = ProjectSession.open(f.access, loaded, f.registry, 2)) {
                assertFalse(session.protectedMode());
            }
        }
    }

    @Test void preparedValidationResultCannotEnterAReplacementSession() throws Exception {
        try (Fixture f = fixture("late-session", 8)) {
            ProjectSession original = f.open();
            ProjectSaveCapture capture = original.captureSave(Set.of(DOCUMENT));
            PreparedProjectSave prepared = f.access.prepareValidated(capture, () -> false);
            original.close();
            assertThrows(IllegalStateException.class, () -> f.access.prepareValidated(capture, () -> false));
            try (ProjectSession replacement = f.open()) {
                assertThrows(RuntimeException.class, () -> replacement.enqueueSave(prepared));
                assertEquals(0, replacement.saves().queued());
            }
        }
    }

    @Test void failedValidationKeepsOriginalCaptureAndDraftForRetry() throws Exception {
        try (Fixture f = fixture("retry", 8); ProjectSession session = f.open()) {
            var handle = session.find(DOCUMENT, String.class).orElseThrow();
            handle.edit(handle.view().token(), EDIT, ignored -> "captured");
            ProjectSaveCapture capture = session.captureSave(Set.of(DOCUMENT));
            handle.edit(handle.view().token(), EDIT, ignored -> "later");
            var draft = handle.view();
            var queues = session.saves();
            var saved = f.store.load();
            Problem error = problem(Problem.Severity.ERROR, "invalid_settings");
            f.validator.set((version, content) -> List.of(error));
            assertTrue(assertThrows(SessionRejection.class, () -> f.access.prepareValidated(capture, () -> false))
                    .problems().contains(error));
            assertEquals(draft, handle.view());
            assertEquals(queues, session.saves());
            assertEquals(saved, f.store.load());
            f.validator.set(ACCEPT);
            session.enqueueSave(f.access.prepareValidated(capture, () -> false));
            publish(f.access, session);
            assertEquals("captured", new String(f.access.read().documentBytes(DOCUMENT), StandardCharsets.UTF_8));
            assertEquals("later", handle.view().content());
            assertTrue(handle.hasUnpublishedChanges());
        }
    }

    @Test void backgroundPreparationUsesFixedCaptureWhileOwnerCanEditAndRejectsConcurrentIo() throws Exception {
        try (Fixture f = fixture("fixed", 8); ProjectSession session = f.open()) {
            var handle = session.find(DOCUMENT, String.class).orElseThrow();
            handle.edit(handle.view().token(), EDIT, ignored -> "captured");
            ProjectSaveCapture capture = session.captureSave(Set.of(DOCUMENT));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.validator.set((version, content) -> {
                entered.countDown();
                await(release);
                return List.of();
            });
            CompletableFuture<PreparedProjectSave> result = CompletableFuture.supplyAsync(() -> {
                try { return f.access.prepareValidated(capture, () -> false); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(RuntimeException.class, f.access::read);
                assertThrows(RuntimeException.class, () -> f.access.readValidated(f.registry, () -> false));
                assertThrows(RuntimeException.class, session::close);
                handle.edit(handle.view().token(), EDIT, ignored -> "late owner edit");
                assertEquals("late owner edit", handle.view().content());
            } finally { release.countDown(); }
            session.enqueueSave(result.get(10, TimeUnit.SECONDS));
            publish(f.access, session);
            assertEquals("captured", new String(f.access.read().documentBytes(DOCUMENT), StandardCharsets.UTF_8));
            assertTrue(handle.hasUnpublishedChanges());
        }
    }

    @Test void contextIsInvalidAfterReturnOnForeignThreadAndDuringTheNextCallback() throws Exception {
        try (Fixture f = fixture("scope", 8)) {
            AtomicReference<ResourceContent> previous = new AtomicReference<>();
            f.validator.set((version, content) -> {
                ResourceContent stale = previous.getAndSet(content);
                if (stale != null) assertContextExpired(stale);
                CompletableFuture<Void> foreign = CompletableFuture.runAsync(() -> assertContextExpired(content));
                foreign.join();
                assertThrows(IllegalArgumentException.class, () -> content.copyContent(UUID.randomUUID(), OutputStream.nullOutputStream()));
                assertThrows(NullPointerException.class, () -> content.copyContent(null, OutputStream.nullOutputStream()));
                assertThrows(NullPointerException.class, () -> content.copyContent(BYTE, null));
                return List.of();
            });
            f.access.readValidated(f.registry, () -> false);
            assertContextExpired(previous.get());
            f.validator.set((version, content) -> { assertContextExpired(previous.get()); return List.of(); });
            f.access.readValidated(f.registry, () -> false);
        }
    }

    @Test void callbackCannotReenterAnyAccessOrOwnerMutationAndGuardsRecover() throws Exception {
        try (Fixture f = fixture("reentry", 8); Fixture other = fixture("other", 8);
                ProjectSession session = f.open()) {
            var handle = session.find(DOCUMENT, String.class).orElseThrow();
            var capture = session.captureSave(Set.of(DOCUMENT));
            f.validator.set((version, content) -> {
                assertEquals("before", handle.view().content());
                assertThrows(RuntimeException.class, () -> handle.edit(handle.view().token(), EDIT, ignored -> "reentrant"));
                assertThrows(RuntimeException.class, f.access::read);
                assertThrows(RuntimeException.class, other.access::read);
                assertThrows(RuntimeException.class, () -> other.access.readValidated(other.registry, () -> false));
                assertThrows(RuntimeException.class, () -> f.access.prepare(capture));
                assertThrows(RuntimeException.class, session::close);
                return List.of();
            });
            f.access.prepareValidated(capture, () -> false);
            handle.edit(handle.view().token(), EDIT, ignored -> "normal mutation recovered");
            assertEquals("normal mutation recovered", handle.view().content());
            assertNotNull(other.access.read());
            assertNotNull(f.access.read());
        }
    }

    @Test void cancellationBeforeBetweenAndAfterCallbacksNeverReturnsAResult() throws Exception {
        try (Fixture f = fixture("cancel-phases", 8)) {
            AtomicInteger calls = new AtomicInteger();
            f.validator.set((version, content) -> { calls.incrementAndGet(); return List.of(); });
            assertThrows(ResourceValidationCancelledException.class, () -> f.access.readValidated(f.registry, () -> true));
            assertEquals(0, calls.get());
            AtomicBoolean cancelled = new AtomicBoolean();
            f.validator.set((version, content) -> { calls.incrementAndGet(); cancelled.set(true); return List.of(); });
            assertThrows(ResourceValidationCancelledException.class, () -> f.access.readValidated(f.registry, cancelled::get));
            assertEquals(1, calls.get(), "cancellation between retained versions prevents the next callback");
            cancelled.set(false);
            f.validator.set((version, content) -> { if (version.id().equals(CURRENT)) cancelled.set(true); return List.of(); });
            assertThrows(ResourceValidationCancelledException.class, () -> f.access.readValidated(f.registry, cancelled::get));
            f.validator.set(ACCEPT);
            assertNotNull(f.access.readValidated(f.registry, () -> false));
            IllegalStateException original = new IllegalStateException("cancel supplier failure");
            assertSame(original, assertThrows(IllegalStateException.class,
                    () -> f.access.readValidated(f.registry, () -> { throw original; })));
        }
    }

    @Test void cancellationSupplierCannotReenterIoOrOwnerMutationsAndRecursionRecovers() throws Exception {
        try (Fixture f = fixture("cancel-supplier-guard", 8); Fixture other = fixture("cancel-other", 8);
                ProjectSession session = f.open()) {
            var handle = session.find(DOCUMENT, String.class).orElseThrow();
            var capture = session.captureSave(Set.of(DOCUMENT));
            AtomicInteger checks = new AtomicInteger();
            f.access.prepareValidated(capture, () -> {
                checks.incrementAndGet();
                assertThrows(RuntimeException.class, f.access::read);
                assertThrows(RuntimeException.class, other.access::read);
                assertThrows(RuntimeException.class, () -> handle.edit(handle.view().token(), EDIT, ignored -> "supplier mutation"));
                return false;
            });
            assertTrue(checks.get() > 0);
            assertEquals("before", handle.view().content());
            AtomicReference<ResourceContent> active = new AtomicReference<>();
            AtomicInteger recursiveChecks = new AtomicInteger();
            f.validator.set((version, content) -> {
                active.set(content);
                try { content.checkCancelled(); }
                finally { active.set(null); }
                return List.of();
            });
            var retry = session.captureSave(Set.of(DOCUMENT));
            assertThrows(IllegalStateException.class, () -> f.access.prepareValidated(retry, () -> {
                ResourceContent current = active.get();
                if (current != null) {
                    recursiveChecks.incrementAndGet();
                    try { current.checkCancelled(); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
                return false;
            }));
            assertEquals(1, recursiveChecks.get(), "supplier recursion must fail before evaluating itself again");
            f.validator.set(ACCEPT);
            assertNotNull(f.access.prepareValidated(retry, () -> false));
            handle.edit(handle.view().token(), EDIT, ignored -> "normal owner mutation");
            assertNotNull(f.access.read());
            assertNotNull(other.access.read());
        }
    }

    @Test void pushCopyChecksCancellationWithin64KiBAndDoesNotCloseTheOutput() throws Exception {
        try (Fixture f = fixture("cancel-copy", 2L * 1024 * 1024); ProjectSession session = f.open()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicInteger written = new AtomicInteger();
            AtomicBoolean closed = new AtomicBoolean();
            OutputStream target = new OutputStream() {
                @Override public void write(int value) { fail("bulk transfer expected"); }
                @Override public void write(byte[] bytes, int offset, int length) {
                    assertTrue(length <= 65536);
                    written.addAndGet(length);
                    cancelled.set(true);
                }
                @Override public void close() { closed.set(true); }
            };
            f.validator.set((version, content) -> { content.copyContent(BYTE, target); return List.of(); });
            var capture = session.captureSave(Set.of(DOCUMENT));
            var saved = f.store.load();
            assertThrows(ResourceValidationCancelledException.class,
                    () -> f.access.prepareValidated(capture, cancelled::get));
            assertTrue(written.get() > 0 && written.get() <= 65536);
            assertFalse(closed.get());
            assertEquals(0, session.saves().queued());
            assertEquals(saved, f.store.load());
            f.validator.set(ACCEPT);
            session.enqueueSave(f.access.prepareValidated(capture, () -> false));
        }
    }

    @Test void prepareCancellationBeforeAndAtFinalCallbackPreservesCaptureForRetry() throws Exception {
        try (Fixture f = fixture("cancel-prepare", 8); ProjectSession session = f.open()) {
            var capture = session.captureSave(Set.of(DOCUMENT));
            var before = session.saves();
            assertThrows(ResourceValidationCancelledException.class, () -> f.access.prepareValidated(capture, () -> true));
            AtomicBoolean cancelled = new AtomicBoolean();
            f.validator.set((version, content) -> { if (version.id().equals(CURRENT)) cancelled.set(true); return List.of(); });
            assertThrows(ResourceValidationCancelledException.class, () -> f.access.prepareValidated(capture, cancelled::get));
            assertEquals(before, session.saves());
            f.validator.set(ACCEPT);
            session.enqueueSave(f.access.prepareValidated(capture, () -> false));
        }
    }

    @Test void observedCancellationRemainsLatchedWhenValidatorCatchesItAndResetsTheSupplier() throws Exception {
        try (Fixture f = fixture("cancel-latched", 2L * 1024 * 1024); ProjectSession session = f.open()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicInteger swallowed = new AtomicInteger();
            f.validator.set((version, content) -> {
                try {
                    content.copyContent(BYTE, new OutputStream() {
                        @Override public void write(int value) { cancelled.set(true); }
                        @Override public void write(byte[] bytes, int offset, int length) { cancelled.set(true); }
                    });
                    fail("the push must observe cancellation after the first chunk");
                } catch (ResourceValidationCancelledException expected) {
                    swallowed.incrementAndGet();
                    cancelled.set(false);
                }
                return List.of();
            });
            var capture = session.captureSave(Set.of(DOCUMENT));
            var saved = f.store.load();
            assertThrows(ResourceValidationCancelledException.class,
                    () -> f.access.readValidated(f.registry, cancelled::get));
            assertEquals(1, swallowed.get());
            assertFalse(cancelled.get(), "validator deliberately reset the external cancellation source");
            assertThrows(ResourceValidationCancelledException.class,
                    () -> f.access.prepareValidated(capture, cancelled::get));
            assertEquals(2, swallowed.get());
            assertEquals(saved, f.store.load());
            assertEquals(0, session.saves().queued());
            f.validator.set(ACCEPT);
            assertNotNull(f.access.readValidated(f.registry, () -> false));
            session.enqueueSave(f.access.prepareValidated(capture, () -> false));
        }
    }

    @Test void cancellationAfterActualDocumentStageDoesNotIssuePreparedOrPublish() throws Exception {
        try (Fixture f = fixture("cancel-after-stage", 8); ProjectSession session = f.open()) {
            String captured = "unique bytes staged before final cancellation";
            var handle = session.find(DOCUMENT, String.class).orElseThrow();
            handle.edit(handle.view().token(), EDIT, ignored -> captured);
            var capture = session.captureSave(Set.of(DOCUMENT));
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(captured.getBytes(StandardCharsets.UTF_8)));
            Path staged = f.path.resolve("documents").resolve(digest + ".blob");
            assertFalse(Files.exists(staged));
            var saved = f.store.load();
            assertThrows(ResourceValidationCancelledException.class,
                    () -> f.access.prepareValidated(capture, () -> Files.exists(staged)));
            assertTrue(Files.exists(staged), "the cancellation happened after real staging");
            assertEquals(saved, f.store.load());
            assertEquals(0, session.saves().queued());
            assertTrue(handle.hasUnpublishedChanges());
            session.enqueueSave(f.access.prepareValidated(capture, () -> false));
        }
    }

    @Test void ioRuntimeAndErrorEscapeUnchangedAndInvalidateContextWithFinallyRecovery() throws Exception {
        try (Fixture f = fixture("throwing", 8); ProjectSession session = f.open()) {
            var capture = session.captureSave(Set.of(DOCUMENT));
            AtomicReference<ResourceContent> retained = new AtomicReference<>();
            IOException io = new IOException("synthetic I/O failure");
            f.validator.set((version, content) -> { retained.set(content); throw io; });
            assertSame(io, assertThrows(IOException.class, () -> f.access.prepareValidated(capture, () -> false)));
            assertContextExpired(retained.get());
            IllegalStateException runtime = new IllegalStateException("synthetic runtime failure");
            f.validator.set((version, content) -> { throw runtime; });
            assertSame(runtime, assertThrows(IllegalStateException.class, () -> f.access.prepareValidated(capture, () -> false)));
            AssertionError error = new AssertionError("synthetic Error");
            f.validator.set((version, content) -> { throw error; });
            assertSame(error, assertThrows(AssertionError.class, () -> f.access.prepareValidated(capture, () -> false)));
            f.validator.set(ACCEPT);
            assertNotNull(f.access.prepareValidated(capture, () -> false));
            assertNotNull(f.access.read());
        }
    }

    @Test void copyOutputFailurePropagatesWithoutClosingTheCallerStream() throws Exception {
        try (Fixture f = fixture("output-failure", 1024)) {
            IOException original = new IOException("caller sink failed");
            AtomicBoolean closed = new AtomicBoolean();
            f.validator.set((version, content) -> {
                content.copyContent(BYTE, new OutputStream() {
                    @Override public void write(int value) throws IOException { throw original; }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw original; }
                    @Override public void close() { closed.set(true); }
                });
                return List.of();
            });
            assertSame(original, assertThrows(IOException.class, () -> f.access.readValidated(f.registry, () -> false)));
            assertFalse(closed.get());
            f.validator.set(ACCEPT);
            assertNotNull(f.access.readValidated(f.registry, () -> false));
        }
    }

    @Test void zeroByteCopiesStillConsumeTheExecutionCallBudget() throws Exception {
        try (Fixture f = fixture("copy-calls", 0)) {
            AtomicInteger accepted = new AtomicInteger();
            f.validator.set((version, content) -> {
                for (int i = 0; i < 65536; i++) {
                    content.copyContent(EMPTY_BYTE, OutputStream.nullOutputStream());
                    accepted.incrementAndGet();
                }
                content.copyContent(EMPTY_BYTE, OutputStream.nullOutputStream());
                fail("the 65537th copy must not succeed");
                return List.of();
            });
            assertThrows(IllegalArgumentException.class, () -> f.access.readValidated(f.registry, () -> false));
            assertEquals(65536, accepted.get());
        }
    }

    @Test void requestedByteBudgetIsReservedBeforeIoEvenWhenTheDestinationFails() throws Exception {
        // Only 64 MiB on disk, generated with a 64 KiB buffer. Each failing push reads at most one
        // chunk: 512 requests reserve 32 GiB but transfer at most 32 MiB to this failing sink.
        try (Fixture f = fixture("copy-bytes", 64L * 1024 * 1024)) {
            AtomicInteger writes = new AtomicInteger();
            IOException stop = new IOException("stop after first chunk");
            OutputStream sink = new OutputStream() {
                @Override public void write(int value) throws IOException { writes.incrementAndGet(); throw stop; }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    assertTrue(length <= 65536); writes.incrementAndGet(); throw stop;
                }
            };
            f.validator.set((version, content) -> {
                for (int i = 0; i < 512; i++) assertSame(stop,
                        assertThrows(IOException.class, () -> content.copyContent(BYTE, sink)));
                assertThrows(IllegalArgumentException.class, () -> content.copyContent(BYTE, sink));
                assertEquals(512, writes.get(), "over-budget request fails before destination I/O");
                // Stop before the second callback, keeping this one execution's evidence intact.
                throw stop;
            });
            assertSame(stop, assertThrows(IOException.class, () -> f.access.readValidated(f.registry, () -> false)));
        }
    }

    @Test void oversizedDeclaredByteSetIsRejectedBeforeValidatorOrMissingBlobIo() throws Exception {
        try (Fixture f = fixture("byte-set-budget", 8); ProjectSession session = f.open()) {
            UUID huge = id(20);
            Map<UUID, ProjectState.Entry> bytes = new java.util.TreeMap<>(f.catalog.byteEntries());
            // A public structural draft may reference an unstaged blob. The execution budget must
            // reject its declared size before attempting to authenticate those nonexistent bytes.
            bytes.put(huge, asset(huge, new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 16L * 1024 * 1024 * 1024 + 1)));
            var candidate = new ResourceCatalog(f.catalog.resources(), f.catalog.versions(), bytes);
            var resources = session.resources().orElseThrow();
            resources.edit(resources.view().token(), EDIT, ignored -> candidate);
            AtomicInteger callbacks = new AtomicInteger();
            f.validator.set((version, content) -> { callbacks.incrementAndGet(); return List.of(); });
            var capture = session.captureSave(Set.of(), true);
            assertThrows(IllegalArgumentException.class, () -> f.access.prepareValidated(capture, () -> false));
            assertEquals(0, callbacks.get());
            assertEquals(0, session.saves().queued());
            assertEquals(candidate, resources.view().content());
        }
    }

    @Test void changedBytesAfterValidatedOpenCannotPrepareAndExactRestorationAllowsRetry() throws Exception {
        try (Fixture f = fixture("changed-byte", 1024); ProjectSession session = f.open()) {
            var capture = session.captureSave(Set.of(DOCUMENT));
            Path blob = f.path.resolve("assets").resolve(f.catalog.byteEntries().get(BYTE).content().sha256() + ".blob");
            byte[] original = Files.readAllBytes(blob);
            Files.write(blob, new byte[original.length]);
            assertThrows(IOException.class, () -> f.access.prepareValidated(capture, () -> false));
            assertEquals(0, session.saves().queued());
            Files.write(blob, original);
            assertNotNull(f.access.prepareValidated(capture, () -> false));
        }
    }

    @Test void changedCurrentDuringValidationRefusesAReadResult() throws Exception {
        try (Fixture f = fixture("changed-current", 8)) {
            var before = f.store.load();
            AtomicBoolean once = new AtomicBoolean();
            f.validator.set((version, content) -> {
                if (once.compareAndSet(false, true)) {
                    ProjectState state = before.state();
                    f.store.commit(before.commitId(), UUID.randomUUID(), new ProjectState(state.projectId(),
                            "external change", state.modules(), state.documents(), state.assets(), null));
                }
                return List.of();
            });
            assertThrows(IOException.class, () -> f.access.readValidated(f.registry, () -> false));
            assertEquals("external change", f.store.load().state().name());
        }
    }

    @Test void invalidDiagnosticReturnsAreProgrammingFailuresAndDoNotPoisonTheCapture() throws Exception {
        try (Fixture f = fixture("bad-results", 8); ProjectSession session = f.open()) {
            var capture = session.captureSave(Set.of(DOCUMENT));
            f.validator.set((version, content) -> null);
            assertThrows(NullPointerException.class, () -> f.access.prepareValidated(capture, () -> false));
            f.validator.set((version, content) -> java.util.Collections.nCopies(257, problem(Problem.Severity.INFO, "too_many")));
            assertThrows(IllegalArgumentException.class, () -> f.access.prepareValidated(capture, () -> false));
            f.validator.set((version, content) -> List.of(new Problem(Problem.Severity.INFO, EditorId.parse("synthetic:huge"),
                    OLD.toString(), new TextContent.Literal("x".repeat(1048577)))));
            assertThrows(IllegalArgumentException.class, () -> f.access.prepareValidated(capture, () -> false));
            f.validator.set((version, content) -> List.of(new Problem(Problem.Severity.INFO, EditorId.parse("synthetic:scale"),
                    OLD.toString(), new TextContent.Message("synthetic.number", List.of(
                            new TextContent.NumberArgument(new BigDecimal("1E+4097")))))));
            assertThrows(IllegalArgumentException.class, () -> f.access.prepareValidated(capture, () -> false));
            f.validator.set(ACCEPT);
            assertNotNull(f.access.prepareValidated(capture, () -> false));
        }
    }

    @Test void customDiagnosticListInspectionCannotReenterIoOrOwnerMutation() throws Exception {
        try (Fixture f = fixture("list-callback-guard", 8); Fixture other = fixture("list-other", 8);
                ProjectSession session = f.open()) {
            var handle = session.find(DOCUMENT, String.class).orElseThrow();
            AtomicInteger inspections = new AtomicInteger();
            Problem warning = problem(Problem.Severity.WARNING, "list_warning");
            f.validator.set((version, content) -> new java.util.AbstractList<>() {
                @Override public int size() {
                    inspections.incrementAndGet();
                    assertThrows(RuntimeException.class, other.access::read);
                    assertThrows(RuntimeException.class, () -> handle.edit(handle.view().token(), EDIT, ignored -> "list mutation"));
                    assertContextExpired(content);
                    return 1;
                }
                @Override public Problem get(int index) {
                    java.util.Objects.checkIndex(index, 1);
                    return warning;
                }
            });
            assertEquals(List.of(warning, warning),
                    f.access.prepareValidated(session.captureSave(Set.of(DOCUMENT)), () -> false).problems());
            assertTrue(inspections.get() > 0);
            assertEquals("before", handle.view().content());
            handle.edit(handle.view().token(), EDIT, ignored -> "recovered");
            assertNotNull(other.access.read());
        }
    }

    private Fixture fixture(String name, long bytes) throws Exception { return new Fixture(directory.resolve(name), bytes); }

    private static final class Fixture implements AutoCloseable {
        final Path path;
        final LocalProjectStore store;
        final LocalProjectAccess access;
        final ResourceCatalog catalog;
        final AtomicReference<ResourceContentValidator> validator = new AtomicReference<>(ACCEPT);
        final ExtensionRegistry registry = registry((version, content) -> validator.get().validate(version, content),
                OWNER, Set.of("old", "new"), true);

        Fixture(Path path, long bytes) throws Exception {
            this.path = path;
            store = LocalProjectStore.create(path);
            try (InputStream generated = generated(bytes)) {
                ProjectState.Entry source = asset(BYTE, store.stage(BlobRef.Kind.ASSET, generated));
                ProjectState.Entry empty = asset(EMPTY_BYTE, store.stage(BlobRef.Kind.ASSET, new byte[0]));
                catalog = new ResourceCatalog(Map.of(RESOURCE, new LogicalResource(RESOURCE, ASSET_TYPE, "synthetic", CURRENT)),
                        Map.of(OLD, version(OLD, "old"), CURRENT, version(CURRENT, "new")), Map.of(BYTE, source, EMPTY_BYTE, empty));
                ProjectState.Entry document = new ProjectState.Entry(DOCUMENT, OWNER.toString(), TYPE.toString(), CODEC.toString(),
                        1, "note", store.stage(BlobRef.Kind.DOCUMENT, "before".getBytes(StandardCharsets.UTF_8)), Set.of(), Set.of());
                store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "validation fixture",
                        Map.of(OWNER.toString(), BUILD, RESOURCES.toString(), "1"), Map.of(DOCUMENT, document),
                        ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(catalog)), null));
            }
            access = new LocalProjectAccess(store);
        }

        ProjectSession open() throws IOException {
            ProjectSession session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 2);
            assertFalse(session.protectedMode());
            return session;
        }
        @Override public void close() throws IOException { store.close(); }
    }

    private static ExtensionRegistry registry(ResourceContentValidator validator, EditorId validatorOwner,
            Set<String> readable, boolean defineValidator) {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.define(net.ptcrys.fpsmatch.editor.core.types.DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        if (defineValidator) builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, BUILD, API, Set.of(), Set.of()));
        builder.addModule(module(RESOURCES));
        builder.addModule(module(OTHER_OWNER));
        builder.add(net.ptcrys.fpsmatch.editor.core.types.DocumentTypes.KIND, new Contribution(
                new ContributionKey(net.ptcrys.fpsmatch.editor.core.types.DocumentTypes.KIND.id(), TYPE), OWNER,
                Contribution.Side.COMMON, Set.of()), new TestType());
        builder.add(ResourceProducers.KIND, contribution(ResourceProducers.KIND, OWNER), new ResourceProducerSpec(readable));
        if (validator != null) builder.add(ResourceContentValidators.KIND, contribution(ResourceContentValidators.KIND, validatorOwner), validator);
        return builder.freeze();
    }

    private static ModuleSpec module(EditorId owner) { return new ModuleSpec(owner, "1", API, Set.of(), Set.of()); }
    private static Contribution contribution(ExtensionKind<?> kind, EditorId owner) {
        return new Contribution(new ContributionKey(kind.id(), IMPORTER), owner, Contribution.Side.COMMON, Set.of());
    }
    private static ResourceVersion version(UUID id, String version) {
        return new ResourceVersion(id, RESOURCE, IMPORTER, version, Map.of("synthetic", "raw"), BYTE, Set.of(), Set.of());
    }
    private static ProjectState.Entry asset(UUID id, BlobRef blob) {
        return new ProjectState.Entry(id, OWNER.toString(), ASSET_TYPE.toString(), "synthetic:raw", 1, "bytes", blob, Set.of(), Set.of());
    }
    private static InputStream generated(long size) {
        return new InputStream() {
            long remaining = size;
            @Override public int read() { if (remaining == 0) return -1; remaining--; return 90; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (length == 0) return 0;
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                java.util.Arrays.fill(bytes, offset, offset + count, (byte) 90);
                remaining -= count;
                return count;
            }
        };
    }
    private static void assertContextExpired(ResourceContent content) {
        assertThrows(IllegalStateException.class, content::resourceTypes);
        assertThrows(IllegalStateException.class, content::versions);
        assertThrows(IllegalStateException.class, content::byteEntries);
        assertThrows(IllegalStateException.class, content::checkCancelled);
        assertThrows(IllegalStateException.class, () -> content.copyContent(BYTE, OutputStream.nullOutputStream()));
    }
    private static Problem problem(Problem.Severity severity, String code) {
        return new Problem(severity, EditorId.parse("synthetic:" + code), OLD.toString(),
                new TextContent.Message("synthetic." + code, List.of(new TextContent.LiteralArgument("raw"),
                        new TextContent.NumberArgument(new BigDecimal("12.50")),
                        new TextContent.NestedArgument(new TextContent.Literal("nested")))));
    }
    private static void assertCode(List<Problem> problems, String suffix) {
        assertTrue(problems.stream().anyMatch(problem -> problem.code().toString().equals("editor:session/" + suffix)), problems.toString());
    }
    private static void publish(LocalProjectAccess access, ProjectSession session) {
        ProjectSaveJob job = session.dispatchSave().orElseThrow().job().orElseThrow();
        assertTrue(session.completeSave(job, access.publish(job)));
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("test validation release timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
    }
    private static UUID id(long value) { return new UUID(0x5150353000000000L, value); }
}
