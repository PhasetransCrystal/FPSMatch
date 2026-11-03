package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidator;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import net.ptcrys.fpsmatch.editor.core.storage.StoreLimits;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Q61: the complete saved project is checked through its original public access. */
class ValidatedSavedContentTest {
    @TempDir Path directory;
    private static final UUID FIRST = id(1), SECOND = id(2), RESOURCE = id(3);
    private static final UUID ACTIVE = id(4), RETAINED = id(5), BYTE = id(6);
    private static final EditorId RESOURCES = EditorId.parse("editor:resources");
    private static final EditorId IMPORTER = EditorId.parse("synthetic:content_importer");
    private static final EditorId OLD_IMPORTER = EditorId.parse("synthetic:retained_importer");
    private static final EditorId ASSET = EditorId.parse("synthetic:content_bytes");
    private static final EditorId HELPER = EditorId.parse("synthetic:helper");
    private static final EditorId TRANSITIVE = EditorId.parse("synthetic:transitive");
    private static final TextContent LABEL = new TextContent.Literal("edit");

    @Test void checksEverySavedDocumentInUuidOrderAndReturnsImmutableWarningsWithoutEncoding() throws Exception {
        TestType type = new TestType();
        List<String> decoded = new ArrayList<>();
        List<String> validated = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        Problem warning = problem(Problem.Severity.WARNING, "synthetic:content_warning", SECOND);
        type.decodeTransform = value -> { decoded.add(value); return value; };
        type.snapshotTransform = value -> value + " isolated";
        type.validateFunction = value -> { validated.add(value); return List.of(warning); };
        type.dependencyFunction = value -> { dependencies.add(value); return NO_DEPENDENCIES; };
        type.encodeFunction = value -> { throw new AssertionError("saved-content verification encoded"); };
        ExtensionRegistry registry = registry(type);
        try (var f = project(directory.resolve("healthy"), registry,
                List.of(source(SECOND, "second"), source(FIRST, "first")))) {
            LoadedProject source = validated(f);
            List<Problem> result = verify(f, source);
            assertEquals(List.of("first", "second"), decoded);
            assertEquals(List.of("first isolated", "second isolated"), validated);
            assertEquals(validated, dependencies);
            assertTrue(result.contains(warning));
            assertTrue(result.stream().noneMatch(p -> p.severity() == Problem.Severity.ERROR));
            assertThrows(UnsupportedOperationException.class, () -> result.add(warning));
            assertEquals(2, type.decodes);
            assertEquals(2, type.snapshots);
            assertEquals(2, type.validations);
            assertEquals(2, type.dependencies);
            assertEquals(0, type.creates);
            assertEquals(0, type.encodings);
            assertArrayEquals(bytes("first"), source.documentBytes(FIRST));
            assertEquals(source.saved(), f.store().load());
        }
    }

    @Test void unreadableUnselectedDocumentRejectsTheWholeProjectAndPreservesItsBytes() throws Exception {
        for (String fault : List.of("type", "codec", "schema")) {
            TestType type = new TestType();
            ExtensionRegistry registry = registry(type);
            SourceDocument unknown = new SourceDocument(SECOND, OWNER.toString(),
                    fault.equals("type") ? "future:opaque" : TYPE.toString(),
                    fault.equals("codec") ? "future:codec" : CODEC.toString(),
                    fault.equals("schema") ? 99 : 1, "unselected opaque", bytes("opaque"), Set.of(), Set.of());
            try (var f = project(directory.resolve(fault), registry, List.of(source(FIRST, "selected"), unknown))) {
                LoadedProject source = validated(f);
                SessionRejection rejection = assertThrows(SessionRejection.class, () -> verify(f, source));
                assertProblem(rejection, "editor:runtime/document_unavailable", SECOND.toString());
                assertArrayEquals(bytes("opaque"), source.documentBytes(SECOND));
                assertEquals(source.saved(), f.store().load());
                assertEquals(0, type.encodings);
            }
        }
    }

    @Test void codecFailureAndValidationErrorKeepOriginalTypedDiagnosticsAndCause() throws Exception {
        for (boolean codecFailure : List.of(true, false)) {
            TestType type = new TestType();
            Problem reason = problem(Problem.Severity.ERROR, "synthetic:bad_saved_content", SECOND);
            DocumentCodecException failure = new DocumentCodecException(List.of(reason));
            if (codecFailure) type.decodeTransform = value -> {
                if (value.equals("bad")) throw failure;
                return value;
            };
            else type.validateFunction = value -> value.equals("bad") ? List.of(reason) : List.of();
            try (var f = project(directory.resolve("failure-" + codecFailure), registry(type),
                    List.of(source(FIRST, "selected"), source(SECOND, "bad")))) {
                LoadedProject source = validated(f);
                SessionRejection rejection = assertThrows(SessionRejection.class, () -> verify(f, source));
                assertTrue(rejection.problems().contains(reason));
                assertProblem(rejection, "editor:runtime/document_unavailable", SECOND.toString());
                if (codecFailure) assertSame(failure, rejection.getCause());
                type.decodeTransform = value -> value;
                type.validateFunction = value -> List.of();
                verify(f, source);
                assertEquals(source.saved(), f.access().read().saved());
            }
        }
    }

    @Test void ownerAndDecodedManifestMismatchRejectInsteadOfRepairingSavedEntries() throws Exception {
        TestType type = new TestType();
        SourceDocument wrongOwner = new SourceDocument(FIRST, "other:module", TYPE.toString(), CODEC.toString(),
                1, "wrong owner", bytes("first"), Set.of(), Set.of());
        try (var f = project(directory.resolve("owner"), registry(type), List.of(wrongOwner),
                Map.of(OWNER.toString(), BUILD, "other:module", "1"))) {
            LoadedProject source = validated(f);
            assertProblem(assertThrows(SessionRejection.class, () -> verify(f, source)),
                    "editor:runtime/document_owner_mismatch", FIRST.toString());
            assertEquals(0, type.decodes);
        }
        type.dependencyFunction = value -> new DocumentDependencies(Set.of(SECOND), Set.of(), Set.of());
        try (var f = project(directory.resolve("manifest"), registry(type),
                List.of(source(FIRST, "first"), source(SECOND, "second")))) {
            LoadedProject source = validated(f);
            assertProblem(assertThrows(SessionRejection.class, () -> verify(f, source)),
                    "editor:runtime/dependency_manifest_mismatch", FIRST.toString());
            assertTrue(f.store().load().state().documents().get(FIRST).documentDependencies().isEmpty());
        }
    }

    @Test void completeSavedModulesAndTransitiveRequiredModulesAreChecked() throws Exception {
        TestType type = new TestType();
        var builder = builder(type);
        builder.addModule(new ModuleSpec(HELPER, "7", new ApiVersion(1, 0), Set.of(TRANSITIVE), Set.of()));
        builder.addModule(new ModuleSpec(TRANSITIVE, "3", new ApiVersion(1, 0), Set.of(), Set.of()));
        ExtensionRegistry registry = builder.freeze();
        for (Map<String, String> modules : List.of(
                Map.of(OWNER.toString(), BUILD, "missing:unselected", "1"),
                Map.of(OWNER.toString(), BUILD, HELPER.toString(), "7.0"),
                Map.of(OWNER.toString(), BUILD, HELPER.toString(), "7"))) {
            try (var f = project(directory.resolve(UUID.randomUUID().toString()), registry,
                    List.of(source(FIRST, "saved")), modules)) {
                LoadedProject source = validated(f);
                String code = modules.containsKey("missing:unselected") ? "required_module_unavailable"
                        : modules.get(HELPER.toString()).equals("7.0") ? "module_version_mismatch" : "required_module_not_recorded";
                String target = code.equals("required_module_unavailable") ? "missing:unselected"
                        : code.equals("module_version_mismatch") ? HELPER.toString() : TRANSITIVE.toString();
                assertProblem(assertThrows(SessionRejection.class, () -> verify(f, source)),
                        "editor:runtime/" + code, target);
            }
        }
        try (var f = project(directory.resolve("full-modules"), registry, List.of(source(FIRST, "saved")),
                Map.of(OWNER.toString(), BUILD, HELPER.toString(), "7", TRANSITIVE.toString(), "3"))) {
            assertTrue(verify(f, validated(f)).isEmpty());
        }
    }

    @Test void contributionClosureRequiresNestedContributionOwnerAndItsRequiredModule() throws Exception {
        EditorId nested = EditorId.parse("synthetic:nested");
        EditorId nestedOwner = EditorId.parse("synthetic:nested_owner");
        ContributionKey nestedKey = new ContributionKey(DocumentTypes.KIND.id(), nested);
        TestType type = new TestType();
        type.dependencyFunction = value -> new DocumentDependencies(Set.of(), Set.of(), Set.of(nestedKey));
        var builder = builder(type);
        builder.addModule(new ModuleSpec(nestedOwner, "2", new ApiVersion(1, 0), Set.of(HELPER), Set.of()));
        builder.addModule(new ModuleSpec(HELPER, "7", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, new Contribution(nestedKey, nestedOwner, Contribution.Side.COMMON,
                Set.of()), new TestType());
        ExtensionRegistry registry = builder.freeze();
        try (var f = project(directory.resolve("contribution"), registry, List.of(source(FIRST, "saved")),
                Map.of(OWNER.toString(), BUILD, nestedOwner.toString(), "2"))) {
            assertProblem(assertThrows(SessionRejection.class, () -> verify(f, validated(f))),
                    "editor:runtime/required_module_not_recorded", HELPER.toString());
        }
    }

    @Test void emptyResourcesStillRequireOriginalAccessAndIdenticalValidatedRegistry() throws Exception {
        ExtensionRegistry registry = registry(new TestType());
        try (var f = project(directory.resolve("authority"), registry, List.of(source(FIRST, "saved")))) {
            assertRequired(() -> verify(f, f.loaded()));
            LoadedProject source = validated(f);
            assertThrows(IllegalArgumentException.class, () ->
                    new LocalProjectAccess(f.store()).verifyValidatedContent(source, registry, () -> false));
            assertRequired(() -> f.access().verifyValidatedContent(source, registry(new TestType()), () -> false));
            assertThrows(NullPointerException.class, () -> f.access().verifyValidatedContent(null, registry, () -> false));
            assertThrows(NullPointerException.class, () -> f.access().verifyValidatedContent(source, null, () -> false));
            assertThrows(NullPointerException.class, () -> f.access().verifyValidatedContent(source, registry, null));
            verify(f, source);
        }
    }

    @Test void fixedAIsDecodedAfterSavingBAndDamagedAOnlyBlobNeverFallsBackToB() throws Exception {
        TestType type = new TestType();
        List<String> decoded = new ArrayList<>();
        type.decodeTransform = value -> { decoded.add(value); return value; };
        Path path = directory.resolve("fixed");
        try (var f = project(path, registry(type), List.of(source(FIRST, "A only")))) {
            LoadedProject source = validated(f);
            LoadedProject explicit = f.access().readValidated(f.access().captureSnapshot(), f.registry(), () -> false);
            ProjectState.Entry bEntry = document(FIRST, f.store().stage(BlobRef.Kind.DOCUMENT, bytes("B current")), Set.of());
            SavedProject b = f.store().commit(source.saved().commitId(), UUID.randomUUID(),
                    new ProjectState(source.saved().state().projectId(), "B", Map.of(OWNER.toString(), BUILD),
                            Map.of(FIRST, bEntry), Map.of(), null)).saved();
            verify(f, source);
            verify(f, explicit);
            assertEquals(List.of("A only", "A only"), decoded);
            Path blob = path.resolve("documents").resolve(source.saved().state().documents().get(FIRST)
                    .content().sha256() + ".blob");
            byte[] original = Files.readAllBytes(blob);
            byte[] damaged = original.clone();
            damaged[0] ^= 1;
            Files.write(blob, damaged);
            try {
                ProjectStorageException failure = assertThrows(ProjectStorageException.class, () -> verify(f, source));
                assertEquals(ProjectStorageException.Code.CORRUPT_CONTENT, failure.code());
                assertArrayEquals(damaged, Files.readAllBytes(blob));
                assertEquals(b, f.access().read().saved());
            } finally { Files.write(blob, original); }
            verify(f, source);
            assertEquals(b, f.store().load());
        }
    }

    @Test void resourceLockedOldVersionAndRetainedProducersAreRequiredWithoutRerunningValidators() throws Exception {
        TestType type = new TestType();
        type.dependencyFunction = value -> new DocumentDependencies(Set.of(),
                Set.of(new ResourceRef(RESOURCE, RETAINED)), Set.of());
        AtomicInteger validatorCalls = new AtomicInteger();
        Problem warning = problem(Problem.Severity.WARNING, "synthetic:resource_warning", RESOURCE);
        ExtensionRegistry registry = resourceRegistry(type, true, true, (version, content) -> {
            validatorCalls.incrementAndGet();
            return List.of(warning);
        });
        try (ResourceFixture f = resources("resources", registry)) {
            LoadedProject source = f.access.readValidated(registry, () -> false);
            int calls = validatorCalls.get();
            List<Problem> result = f.access.verifyValidatedContent(source, registry, () -> false);
            assertTrue(result.contains(warning));
            assertEquals(calls, validatorCalls.get());
            assertEquals(ACTIVE, source.resources().orElseThrow().resources().get(RESOURCE).currentVersion());
            assertEquals(0, type.encodings);
            assertEquals(0, type.creates);
            type.dependencyFunction = value -> new DocumentDependencies(Set.of(),
                    Set.of(new ResourceRef(RESOURCE, id(999))), Set.of());
            assertThrows(SessionRejection.class, () -> f.access.verifyValidatedContent(source, registry, () -> false));
            assertEquals(calls, validatorCalls.get());
        }
        for (boolean producerPresent : List.of(false, true)) {
            ExtensionRegistry missing = resourceRegistry(new TestType(), producerPresent, false,
                    (version, content) -> List.of());
            try (ResourceFixture f = resources("missing-" + producerPresent, missing)) {
                LoadedProject source = f.access.readValidated(missing, () -> false);
                assertTrue(source.problems().stream().anyMatch(p -> p.target().equals(RETAINED.toString())));
                assertRequired(() -> f.access.verifyValidatedContent(source, missing, () -> false));
            }
        }
    }

    @Test void firstAndLateCancellationAndCancellationSupplierFailureReleaseTheGuard() throws Exception {
        TestType type = new TestType();
        try (var f = project(directory.resolve("cancel"), registry(type), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            assertThrows(ResourceValidationCancelledException.class, () ->
                    f.access().verifyValidatedContent(source, f.registry(), () -> true));
            assertEquals(0, type.decodes);
            AtomicBoolean late = new AtomicBoolean();
            type.dependencyFunction = value -> { late.set(true); return NO_DEPENDENCIES; };
            assertThrows(ResourceValidationCancelledException.class, () ->
                    f.access().verifyValidatedContent(source, f.registry(), late::get));
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            verify(f, source);
            RuntimeException failure = new IllegalArgumentException("cancellation supplier");
            assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
                    f.access().verifyValidatedContent(source, f.registry(), () -> { throw failure; })));
            verify(f, source);
            late.set(false);
            type.dependencyFunction = value -> { late.set(true); return NO_DEPENDENCIES; };
            assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
                    f.access().verifyValidatedContent(source, f.registry(), () -> {
                        if (late.get()) throw failure;
                        return false;
                    })));
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            verify(f, source);
        }
    }

    @Test void cancellationAtTheLastNormalCheckRejectsAndReleasesGuard() throws Exception {
        TestType type = new TestType();
        try (var f = project(directory.resolve("final-cancel"), registry(type), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            AtomicInteger normalChecks = new AtomicInteger();
            f.access().verifyValidatedContent(source, f.registry(), () -> {
                normalChecks.incrementAndGet();
                return false;
            });
            int lastCheck = normalChecks.get();
            assertTrue(lastCheck > 0);
            AtomicInteger cancelledChecks = new AtomicInteger();
            assertThrows(ResourceValidationCancelledException.class, () ->
                    f.access().verifyValidatedContent(source, f.registry(),
                            () -> cancelledChecks.incrementAndGet() == lastCheck));
            assertEquals(lastCheck, cancelledChecks.get(), "the final normal check observes cancellation");
            verify(f, source);
            assertEquals(source.saved(), f.access().read().saved());
        }
    }

    @Test void byteEntryOwnersNeedTheirExactInstalledVersionAndCompleteRequiredModuleClosure() throws Exception {
        ExtensionRegistry registry = resourceRegistry(new TestType(), true, true, (version, content) -> List.of());
        for (String variant : List.of("absent", "version", "transitive")) {
            EditorId byteOwner = variant.equals("absent") ? EditorId.parse("missing:bytes") : HELPER;
            Map<String, String> modules = Map.of(OWNER.toString(), BUILD, RESOURCES.toString(), "1",
                    byteOwner.toString(), variant.equals("version") ? "7.0" : variant.equals("absent") ? "1" : "7");
            try (ResourceFixture f = resources("byte-owner-" + variant, registry, byteOwner, modules)) {
                LoadedProject source = f.access.readValidated(registry, () -> false);
                assertTrue(source.problems().isEmpty(), "resource validation alone does not check module closure");
                String code = variant.equals("absent") ? "required_module_unavailable"
                        : variant.equals("version") ? "module_version_mismatch" : "required_module_not_recorded";
                String target = variant.equals("transitive") ? TRANSITIVE.toString() : byteOwner.toString();
                assertProblem(assertThrows(SessionRejection.class, () ->
                        f.access.verifyValidatedContent(source, registry, () -> false)), "editor:runtime/" + code, target);
            }
        }
    }

    @Test void documentCountBudgetRejectsBeforeAnyDomainDecodeEvenWithLargerStorageBudget() throws Exception {
        TestType type = new TestType();
        ExtensionRegistry registry = registry(type);
        StoreLimits limits = new StoreLimits(8 * 1024 * 1024, 1024, 16384, 9000, 65536, 64);
        try (LocalProjectStore store = LocalProjectStore.create(directory.resolve("document-limit"), limits)) {
            BlobRef blob = store.stage(BlobRef.Kind.DOCUMENT, bytes("x"));
            Map<UUID, ProjectState.Entry> documents = new TreeMap<>();
            for (int index = 1; index <= 8193; index++) documents.put(id(index), document(id(index), blob, Set.of()));
            store.commit(null, UUID.randomUUID(), new ProjectState(id(10000), "large project",
                    Map.of(OWNER.toString(), BUILD), documents, Map.of(), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            LoadedProject source = access.readValidated(registry, () -> false);
            assertProblem(assertThrows(SessionRejection.class, () ->
                    access.verifyValidatedContent(source, registry, () -> false)),
                    "editor:runtime/document_count_exceeded", source.saved().state().projectId().toString());
            assertEquals(0, type.decodes);
            assertEquals(0, type.creates);
            assertEquals(0, type.encodings);
            assertEquals(source.saved(), access.read().saved());
        }
    }

    @Test void totalDependencyBudgetCountsRepeatedEdgesAcrossAllSavedDocuments() throws Exception {
        Set<ContributionKey> edges = new java.util.TreeSet<>();
        for (int index = 0; index < 8191; index++) edges.add(new ContributionKey(DocumentTypes.KIND.id(),
                EditorId.parse("synthetic:edge_" + index)));
        DocumentDependencies dependencies = new DocumentDependencies(Set.of(), Set.of(), edges);
        TestType type = new TestType();
        type.dependencyFunction = value -> dependencies;
        List<SourceDocument> documents = new ArrayList<>();
        for (int index = 1; index <= 9; index++) documents.add(source(id(index), "saved " + index));
        try (var f = project(directory.resolve("edge-limit"), registry(type), documents)) {
            assertProblem(assertThrows(SessionRejection.class, () -> verify(f, validated(f))),
                    "editor:session/dependency_budget_exceeded", "dependency_graph");
            assertEquals(9, type.decodes, "the ninth saved document crosses the aggregate edge bound");
            assertEquals(0, type.encodings);
        }
    }

    @Test void callbackRuntimeFailureRetainsCauseAndErrorPropagatesUnwrappedThenGuardRecovers() throws Exception {
        ProbeType type = new ProbeType();
        try (var f = project(directory.resolve("throwing"), registry(type), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            for (String stage : List.of("bind", "decode", "snapshot", "validate", "dependencies")) {
                RuntimeException failure = new IllegalArgumentException(stage);
                type.hook = (at) -> { if (at.equals(stage)) throw failure; };
                SessionRejection rejection = assertThrows(SessionRejection.class, () -> verify(f, source));
                assertTrue(causeContains(rejection, failure), "original failure remains in cause chain at " + stage);
                type.hook = at -> { };
                verify(f, source);
                AssertionError error = new AssertionError(stage);
                type.hook = at -> { if (at.equals(stage)) throw error; };
                assertSame(error, assertThrows(AssertionError.class, () -> verify(f, source)));
                type.hook = at -> { };
                verify(f, source);
            }
        }
    }

    @Test void everyDomainCallbackAndReturnedProblemListCannotReenterIoOrExistingDraftMutation() throws Exception {
        ProbeType type = new ProbeType();
        try (var f = project(directory.resolve("reentry"), registry(type), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            try (ProjectSession session = ProjectSession.open(f.access(), source, f.registry(), 8)) {
                var handle = session.find(FIRST, String.class).orElseThrow();
                handle.edit(handle.view().token(), LABEL, value -> "draft");
                ProjectSaveCapture capture = session.captureSave(Set.of(FIRST));
                var before = handle.view();
                Runnable reentry = () -> {
                    assertThrows(IllegalStateException.class, f.access()::read);
                    assertThrows(IllegalStateException.class, f.access()::captureSnapshot);
                    assertThrows(IllegalStateException.class, () -> verify(f, source));
                    assertThrows(IllegalStateException.class, () -> f.access().prepare(capture));
                    assertThrows(IllegalStateException.class, () -> handle.edit(before.token(), LABEL, value -> "intrusion"));
                    assertThrows(IllegalStateException.class, () -> session.captureSave(Set.of(FIRST)));
                    assertThrows(IllegalStateException.class, session::close);
                };
                Set<String> observed = new java.util.HashSet<>();
                type.hook = stage -> { observed.add(stage); reentry.run(); };
                type.validateFunction = value -> new AbstractList<>() {
                    @Override public Problem get(int index) { reentry.run(); return problem(Problem.Severity.WARNING, "synthetic:list", FIRST); }
                    @Override public int size() { reentry.run(); return 1; }
                };
                verify(f, source);
                assertTrue(observed.containsAll(Set.of("bind", "decode", "snapshot", "validate", "dependencies")));
                assertEquals(before, handle.view());
                assertFalse(session.isClosed());
                type.hook = stage -> { };
                type.validateFunction = value -> List.of();
                assertNotNull(f.access().prepare(capture), "denied prepare did not consume its capture");
            }
        }
    }

    @Test void callbackLatchExcludesOtherThreadIoAndGuardIsReleasedAfterCompletion() throws Exception {
        TestType type = new TestType();
        try (var f = project(directory.resolve("busy"), registry(type), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            type.decodeTransform = value -> {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS), "release callback"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return value;
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> running = worker.submit(() -> { verify(f, source); return null; });
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS), "entered real decode callback");
                    assertBusy(f.access()::read);
                    assertBusy(f.access()::captureSnapshot);
                    assertBusy(() -> f.access().readValidated(f.registry(), () -> false));
                    assertBusy(() -> verify(f, source));
                } finally { release.countDown(); running.get(10, TimeUnit.SECONDS); }
                verify(f, source);
                assertEquals(source.saved(), f.access().read().saved());
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void existingDraftTokenHistoryLeaseAndPendingSaveBaselineSurviveSavedContentVerification() throws Exception {
        TestType type = new TestType();
        try (var f = project(directory.resolve("draft"), registry(type), List.of(source(FIRST, "saved A")))) {
            LoadedProject source = validated(f);
            try (ProjectSession session = ProjectSession.open(f.access(), source, f.registry(), 8)) {
                var handle = session.find(FIRST, String.class).orElseThrow();
                handle.edit(handle.view().token(), LABEL, value -> "captured B");
                session.enqueueSave(f.access().prepare(session.captureSave(Set.of(FIRST))));
                handle.edit(handle.view().token(), LABEL, value -> "draft C");
                var before = handle.view();
                var saves = session.saves();
                int encodes = type.encodings, creates = type.creates;
                List<String> checked = new ArrayList<>();
                type.validateFunction = value -> { checked.add(value); return List.of(); };
                verify(f, source);
                assertEquals(List.of("saved A"), checked, "saved bytes, never C or pending B, are the input");
                assertEquals(before, handle.view());
                assertEquals(saves, session.saves());
                assertEquals(encodes, type.encodings);
                assertEquals(creates, type.creates);
                assertThrows(IllegalStateException.class, () -> ProjectSession.open(f.access(), source, f.registry(), 8));
                handle.undo(before.token());
                assertEquals("captured B", handle.view().content());
                handle.redo(handle.view().token());
                assertEquals("draft C", handle.view().content());
                ProjectSaveJob job = session.dispatchSave().orElseThrow().job().orElseThrow();
                assertTrue(session.completeSave(job, f.access().publish(job)));
                assertArrayEquals(bytes("captured B"), f.access().read().documentBytes(FIRST));
                assertTrue(handle.hasUnpublishedChanges());
                handle.edit(handle.view().token(), LABEL, value -> "captured B");
                assertFalse(handle.hasUnpublishedChanges(), "pending capture retains its publication baseline");
            }
            try (ProjectSession reopened = ProjectSession.open(f.access(), f.access().read(), f.registry(), 8)) {
                assertFalse(reopened.protectedMode());
            }
        }
    }

    @Test void mutationOfFixedBlobInsideCallbackIsCaughtByFinalPhysicalScan() throws Exception {
        TestType type = new TestType();
        Path path = directory.resolve("late-io");
        try (var f = project(path, registry(type), List.of(source(FIRST, "original")))) {
            LoadedProject source = validated(f);
            Path blob = path.resolve("documents").resolve(source.saved().state().documents().get(FIRST).content().sha256() + ".blob");
            byte[] original = Files.readAllBytes(blob);
            type.dependencyFunction = value -> {
                try { Files.write(blob, bytes("tampered")); }
                catch (IOException failure) { throw new AssertionError(failure); }
                return NO_DEPENDENCIES;
            };
            try {
                assertEquals(ProjectStorageException.Code.CORRUPT_CONTENT,
                        assertThrows(ProjectStorageException.class, () -> verify(f, source)).code());
            } finally { Files.write(blob, original); }
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            verify(f, source);
        }
    }

    private static LoadedProject validated(Fixture f) throws IOException {
        return f.access().readValidated(f.registry(), () -> false);
    }
    private static List<Problem> verify(Fixture f, LoadedProject source) throws IOException {
        return f.access().verifyValidatedContent(source, f.registry(), () -> false);
    }
    private static void assertProblem(SessionRejection rejection, String code, String target) {
        assertTrue(rejection.problems().stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR
                && p.code().equals(EditorId.parse(code)) && p.target().equals(target)), () -> rejection.problems().toString());
    }
    private static void assertRequired(Executable action) {
        SessionRejection rejection = assertThrows(SessionRejection.class, action);
        assertEquals(List.of(EditorId.parse("editor:session/saved_source_validation_required")),
                rejection.problems().stream().map(Problem::code).toList());
    }
    private static void assertBusy(Executable action) {
        SessionRejection rejection = assertThrows(SessionRejection.class, action);
        assertEquals(List.of(EditorId.parse("editor:runtime_save/io_busy")),
                rejection.problems().stream().map(Problem::code).toList());
    }
    private static boolean causeContains(Throwable failure, Throwable expected) {
        for (Throwable at = failure; at != null; at = at.getCause()) if (at == expected) return true;
        return false;
    }
    private static Problem problem(Problem.Severity severity, String code, UUID target) {
        return new Problem(severity, EditorId.parse(code), target.toString(), new TextContent.Literal("fixture diagnostic"));
    }
    private static ProjectState.Entry document(UUID id, BlobRef blob, Set<UUID> assets) {
        return new ProjectState.Entry(id, OWNER.toString(), TYPE.toString(), CODEC.toString(), 1,
                "saved document", blob, Set.of(), assets);
    }
    private static ExtensionRegistry resourceRegistry(TestType type, boolean oldProducer, boolean oldValidator,
            ResourceContentValidator validator) {
        var builder = builder(type);
        builder.addModule(new ModuleSpec(RESOURCES, "1", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(HELPER, "7", new ApiVersion(1, 0), Set.of(TRANSITIVE), Set.of()));
        builder.addModule(new ModuleSpec(TRANSITIVE, "3", new ApiVersion(1, 0), Set.of(), Set.of()));
        for (EditorId importer : List.of(IMPORTER, OLD_IMPORTER)) {
            if (importer.equals(OLD_IMPORTER) && !oldProducer) continue;
            builder.add(ResourceProducers.KIND, new Contribution(new ContributionKey(ResourceProducers.KIND.id(), importer),
                    OWNER, Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(Set.of("1")));
            if (importer.equals(OLD_IMPORTER) && !oldValidator) continue;
            builder.add(ResourceContentValidators.KIND, new Contribution(new ContributionKey(ResourceContentValidators.KIND.id(), importer),
                    OWNER, Contribution.Side.COMMON, Set.of()), validator);
        }
        return builder.freeze();
    }
    private ResourceFixture resources(String name, ExtensionRegistry registry) throws IOException {
        return resources(name, registry, OWNER, Map.of(OWNER.toString(), BUILD, RESOURCES.toString(), "1"));
    }
    private ResourceFixture resources(String name, ExtensionRegistry registry, EditorId byteOwner,
            Map<String, String> modules) throws IOException {
        LocalProjectStore store = LocalProjectStore.create(directory.resolve(name));
        try {
            ProjectState.Entry asset = new ProjectState.Entry(BYTE, byteOwner.toString(), ASSET.toString(),
                    "synthetic:raw", 1, "resource bytes", store.stage(BlobRef.Kind.ASSET, bytes("retained bytes")), Set.of(), Set.of());
            ResourceCatalog catalog = new ResourceCatalog(Map.of(RESOURCE, new LogicalResource(RESOURCE, ASSET, "resource", ACTIVE)),
                    Map.of(ACTIVE, new ResourceVersion(ACTIVE, RESOURCE, IMPORTER, "1", Map.of(), BYTE, Set.of(), Set.of()),
                            RETAINED, new ResourceVersion(RETAINED, RESOURCE, OLD_IMPORTER, "1", Map.of(), BYTE, Set.of(), Set.of())),
                    Map.of(BYTE, asset));
            Map<UUID, ProjectState.Entry> assets = ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(catalog));
            store.commit(null, UUID.randomUUID(), new ProjectState(id(100), "resources",
                    modules,
                    Map.of(FIRST, document(FIRST, store.stage(BlobRef.Kind.DOCUMENT, bytes("locked old")), Set.of(RESOURCE, RETAINED))), assets, null));
            return new ResourceFixture(store, new LocalProjectAccess(store));
        } catch (IOException | RuntimeException | Error failure) {
            try { store.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
    private record ResourceFixture(LocalProjectStore store, LocalProjectAccess access) implements AutoCloseable {
        @Override public void close() throws IOException { store.close(); }
    }
    private static class ProbeType extends TestType {
        java.util.function.Consumer<String> hook = stage -> { };
        @Override public DocumentType<String> bind(ExtensionRegistry registry) { hook.accept("bind"); return this; }
        @Override public String decode(int schema, byte[] payload) { hook.accept("decode"); return super.decode(schema, payload); }
        @Override public String snapshot(String value) { hook.accept("snapshot"); return super.snapshot(value); }
        @Override public List<Problem> validate(String value) { hook.accept("validate"); return super.validate(value); }
        @Override public DocumentDependencies dependencies(String value) { hook.accept("dependencies"); return super.dependencies(value); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static UUID id(long value) { return new UUID(0x5150363100000000L, value); }
}
