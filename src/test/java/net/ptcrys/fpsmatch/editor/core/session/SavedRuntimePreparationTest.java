package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Q62 behavior through public registration, actual saved bytes and original access. */
class SavedRuntimePreparationTest {
    @TempDir Path directory;
    private static final UUID FIRST = new UUID(0x5150363200000000L, 1);
    private static final UUID SECOND = new UUID(0x5150363200000000L, 2);
    private static final EditorId ADAPTER = EditorId.parse("thirdparty:plain_runtime");
    private static final EditorId RUNTIME = EditorId.parse("thirdparty:runtime_module");
    private static final EditorId HELPER = EditorId.parse("thirdparty:helper");
    private static final EditorId TRANSITIVE = EditorId.parse("thirdparty:transitive");
    private static final TextContent LABEL = new TextContent.Literal("edit");

    @Test void preparationProvenanceUsesExactRegistryIdentityWithoutChangingSavedSource() throws Exception {
        ExtensionRegistry original = registered(new TestType(), adapter());
        ExtensionRegistry equivalent = registered(new TestType(), adapter());
        assertNotSame(original, equivalent);
        assertEquals(original.metadata().modules(), equivalent.metadata().modules());
        assertEquals(original.metadata().contributions(), equivalent.metadata().contributions());
        try (var f = project(directory.resolve("registry-identity"), original,
                List.of(source(FIRST, "saved provenance")))) {
            LoadedProject source = validated(f);
            byte[] bytesBefore = source.documentBytes(FIRST);
            byte[] blobBefore = Files.readAllBytes(blob(directory.resolve("registry-identity"), source));
            var savedBefore = f.store().load();
            PreparedRuntime<String> prepared = prepare(f, source);
            assertTrue(prepared.preparedWith(original));
            assertFalse(prepared.preparedWith(equivalent));
            assertFalse(prepared.preparedWith(null));
            assertSame(source, prepared.source());
            assertSame(source.saved(), prepared.source().saved());
            assertArrayEquals(bytesBefore, source.documentBytes(FIRST));
            assertArrayEquals(blobBefore, Files.readAllBytes(blob(directory.resolve("registry-identity"), source)));
            assertEquals(savedBefore, f.store().load());
            assertEquals(source.saved(), f.access().read().saved());
        }
    }

    @Test void publicThirdPartyAdapterReceivesIsolatedSelectedSavedInputAndReturnsImmutableEvidence() throws Exception {
        TestType type = new TestType();
        List<String> decoded = new ArrayList<>();
        type.decodeTransform = value -> { decoded.add(value); return value; };
        type.snapshotTransform = value -> value + " isolated";
        Problem warning = new Problem(Problem.Severity.WARNING, EditorId.parse("thirdparty:warning"),
                FIRST.toString(), new TextContent.Literal("fixture"));
        Problem info = new Problem(Problem.Severity.INFO, EditorId.parse("thirdparty:info"),
                FIRST.toString(), new TextContent.Literal("fixture information"));
        type.validateFunction = value -> List.of(info, warning);
        TestAdapter<String, String> adapter = adapter();
        AtomicReference<SavedRuntimeInput<String>> received = new AtomicReference<>();
        adapter.action = (input, cancelled) -> { received.set(input); return "runtime:" + input.content(); };
        ExtensionRegistry registry = registered(type, adapter);
        try (var f = project(directory.resolve("public"), registry,
                List.of(source(SECOND, "other"), source(FIRST, "selected")))) {
            LoadedProject source = validated(f);
            PreparedRuntime<String> result = prepare(f, source);
            assertSame(source, result.source());
            assertEquals(FIRST, result.documentId());
            assertEquals(ADAPTER, result.adapterId());
            assertEquals("runtime:selected isolated", result.value());
            assertEquals(List.of("selected", "other", "selected"), decoded);
            assertEquals(FIRST, received.get().documentId());
            assertSame(source.saved(), received.get().saved());
            assertSame(source.resources().orElseThrow(), received.get().resources());
            assertSame(registry, adapter.boundRegistry);
            assertEquals(List.of(info, warning, info, warning, info, warning), result.problems(),
                    "full-project INFO/WARNING and the selected second decode are retained in order");
            assertTrue(result.problems().stream().noneMatch(p -> p.severity() == Problem.Severity.ERROR));
            assertThrows(UnsupportedOperationException.class, () -> result.problems().clear());
            assertThrows(UnsupportedOperationException.class, () -> result.problems().set(0, warning));
            assertThrows(UnsupportedOperationException.class, () -> result.runtimeModules().clear());
            assertEquals(0, type.creates);
            assertEquals(0, type.encodings);
            assertArrayEquals(bytes("selected"), source.documentBytes(FIRST));
        }
    }

    @Test void fixedAAfterSavingBUsesAAndDamagedAHasNoCurrentFallback() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        Path path = directory.resolve("fixed");
        try (var f = project(path, registered(new TestType(), adapter), List.of(source(FIRST, "A only")))) {
            LoadedProject source = validated(f);
            ProjectState.Entry bEntry = new ProjectState.Entry(FIRST, OWNER.toString(), TYPE.toString(), CODEC.toString(),
                    1, "B", f.store().stage(BlobRef.Kind.DOCUMENT, bytes("B current")), Set.of(), Set.of());
            var b = f.store().commit(source.saved().commitId(), UUID.randomUUID(), new ProjectState(
                    source.saved().state().projectId(), "B", Map.of(OWNER.toString(), BUILD),
                    Map.of(FIRST, bEntry), Map.of(), null)).saved();
            assertEquals("A only", prepare(f, source).value());
            Path blob = blob(path, source);
            byte[] original = Files.readAllBytes(blob), damaged = original.clone();
            damaged[0] ^= 1;
            Files.write(blob, damaged);
            try {
                assertEquals(ProjectStorageException.Code.CORRUPT_CONTENT,
                        assertThrows(ProjectStorageException.class, () -> prepare(f, source)).code());
                assertArrayEquals(damaged, Files.readAllBytes(blob));
                assertEquals(b, f.access().read().saved());
            } finally { Files.write(blob, original); }
            assertEquals("A only", prepare(f, source).value());
            assertEquals(b, f.store().load());
        }
    }

    @Test void unknownUnselectedDocumentBlocksBeforeAdapterBindOrPrepare() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        adapter.hook = stage -> { if (stage.equals("bind") || stage.equals("prepare")) fail(stage); };
        SourceDocument opaque = new SourceDocument(SECOND, OWNER.toString(), "future:unknown", CODEC.toString(),
                1, "opaque", bytes("preserved"), Set.of(), Set.of());
        try (var f = project(directory.resolve("unknown"), registered(new TestType(), adapter),
                List.of(source(FIRST, "selected"), opaque))) {
            typed(() -> prepare(f, validated(f)));
            assertArrayEquals(bytes("preserved"), f.loaded().documentBytes(SECOND));
        }
    }

    @Test void ordinaryReadForeignAccessAndEqualButDistinctRegistryAreRejected() throws Exception {
        ExtensionRegistry registry = registered(new TestType(), adapter());
        ExtensionRegistry equalRegistry = registered(new TestType(), adapter());
        try (var f = project(directory.resolve("source"), registry, List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            required(() -> prepare(f, f.loaded()));
            assertThrows(IllegalArgumentException.class, () -> new LocalProjectAccess(f.store()).prepareValidatedRuntime(
                    source, registry, FIRST, ADAPTER, String.class, () -> false));
            required(() -> f.access().prepareValidatedRuntime(
                    source, equalRegistry, FIRST, ADAPTER, String.class, () -> false));
            assertEquals("saved", prepare(f, source).value());
        }
    }

    @Test void selectedSecondSnapshotIsRecheckedAgainstSavedManifestAndDescriptorsAreReadOnce() throws Exception {
        TestType type = new TestType();
        TestAdapter<String, String> adapter = adapter();
        Map<String, Integer> descriptions = new TreeMap<>();
        adapter.hook = stage -> descriptions.merge(stage, 1, Integer::sum);
        try (var f = project(directory.resolve("second-pass"), registered(type, adapter), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            assertEquals("saved", prepare(f, source).value());
            assertEquals(2, descriptions.get("documentType"));
            assertEquals(2, descriptions.get("inputType"));
            assertEquals(2, descriptions.get("resultType"));
            assertEquals(1, descriptions.get("bind"));
            assertEquals(1, descriptions.get("prepare"));
            AtomicInteger passes = new AtomicInteger();
            type.dependencyFunction = value -> passes.incrementAndGet() == 1 ? NO_DEPENDENCIES
                    : new DocumentDependencies(Set.of(SECOND), Set.of(), Set.of());
            adapter.action = (input, cancelled) -> { fail("second snapshot disagrees with saved manifest"); return "bad"; };
            typed(() -> prepare(f, source));
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            adapter.action = (input, cancelled) -> input.content();
            assertEquals("saved", prepare(f, source).value());
        }
    }

    @Test void missingAdapterMissingRequiredDocumentDeclarationAndUnavailableAdapterReject() throws Exception {
        for (String fault : List.of("missing", "declaration", "unavailable")) {
            var builder = runtimeBuilder(new TestType());
            if (!fault.equals("missing")) add(builder, adapter(), RUNTIME,
                    fault.equals("declaration") ? Set.of() : Set.of(docKey(),
                            new ContributionKey(DocumentTypes.KIND.id(), EditorId.parse("future:missing"))));
            ExtensionRegistry registry = builder.freeze();
            try (var f = project(directory.resolve(fault), registry, List.of(source(FIRST, "saved")))) {
                typed(() -> prepare(f, validated(f)));
                assertEquals(f.loaded().saved(), f.access().read().saved());
            }
        }
    }

    @Test void sameKindIdWithWrongClassBecomesTypedUnavailableAndRetainsLookupCause() throws Exception {
        var builder = builder(new TestType());
        ExtensionKind<String> wrongKind = new ExtensionKind<>(SavedRuntimeAdapters.KIND.id(), String.class);
        builder.define(wrongKind);
        builder.add(wrongKind, new Contribution(new ContributionKey(wrongKind.id(), ADAPTER),
                OWNER, Contribution.Side.COMMON, Set.of(docKey())), "unrelated implementation");
        ExtensionRegistry registry = builder.freeze();
        IllegalArgumentException lookupFailure = assertThrows(IllegalArgumentException.class,
                () -> registry.defines(SavedRuntimeAdapters.KIND));
        try (var f = project(directory.resolve("wrong-kind-class"), registry, List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            SessionRejection rejection = typed(() -> prepare(f, source));
            assertEquals(List.of(EditorId.parse("editor:preparation/adapter_unavailable")),
                    rejection.problems().stream().map(Problem::code).toList());
            Throwable cause = rejection.getCause();
            assertNotNull(cause);
            assertEquals(IllegalArgumentException.class, cause.getClass());
            assertEquals(lookupFailure.getMessage(), cause.getMessage());
            assertNull(cause.getCause(), "original lookup exception is not another translated wrapper");
            assertEquals(ExtensionRegistry.class.getName(), cause.getStackTrace()[0].getClassName());
            assertTrue(f.access().verifyValidatedContent(source, registry, () -> false).isEmpty());
            assertEquals(source.saved(), f.access().read().saved());
        }
    }

    @Test void prototypeAndBoundDescriptorsAndBindPropagateOriginalFailuresAndReleaseGuard() throws Exception {
        TestAdapter<String, String> prototype = adapter(), bound = adapter();
        prototype.customBinding = true;
        prototype.binding = bound;
        try (var f = project(directory.resolve("descriptor-failures"), registered(new TestType(), prototype),
                List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            for (String stage : List.of("documentType", "inputType", "resultType", "bind",
                    "bound.documentType", "bound.inputType", "bound.resultType")) {
                boolean boundStage = stage.startsWith("bound.");
                TestAdapter<String, String> target = boundStage ? bound : prototype;
                String callback = boundStage ? stage.substring("bound.".length()) : stage;
                for (Throwable failure : List.of(new IllegalArgumentException(stage), new AssertionError(stage))) {
                    target.hook = at -> {
                        if (at.equals(callback)) {
                            if (failure instanceof RuntimeException runtime) throw runtime;
                            throw (Error) failure;
                        }
                    };
                    try {
                        assertSame(failure, assertThrows(failure.getClass(), () -> prepare(f, source)), stage);
                    } finally { target.hook = at -> { }; }
                    assertEquals("saved", prepare(f, source).value(), "guard recovers after " + stage);
                    assertEquals(source.saved(), f.access().read().saved());
                }
            }
        }
    }

    @Test void descriptorAndExactClassMismatchesRejectBeforePrepare() throws Exception {
        for (String fault : List.of("document", "input", "result", "primitive", "void", "null-document",
                "null-input", "null-result", "bind-document", "bind-input", "bind-result", "bind-null")) {
            TestAdapter<String, String> adapter = adapter();
            adapter.action = (input, cancelled) -> { fail("invalid adapter prepared: " + fault); return "bad"; };
            switch (fault) {
                case "document" -> adapter.document = EditorId.parse("other:document");
                case "input" -> adapter.input = castClass(Object.class);
                case "result" -> adapter.result = castClass(Object.class);
                case "primitive" -> adapter.result = castClass(int.class);
                case "void" -> adapter.result = castClass(void.class);
                case "null-document" -> adapter.document = null;
                case "null-input" -> adapter.input = null;
                case "null-result" -> adapter.result = null;
                default -> {
                    TestAdapter<String, String> bound = adapter();
                    bound.action = adapter.action;
                    if (fault.equals("bind-document")) bound.document = EditorId.parse("other:document");
                    if (fault.equals("bind-input")) bound.input = castClass(Object.class);
                    if (fault.equals("bind-result")) bound.result = castClass(Object.class);
                    adapter.binding = fault.equals("bind-null") ? null : bound;
                    adapter.customBinding = true;
                }
            }
            var builder = runtimeBuilder(new TestType());
            Set<ContributionKey> required = Set.of(docKey());
            if (fault.equals("document")) {
                ContributionKey otherDocument = new ContributionKey(DocumentTypes.KIND.id(), adapter.document);
                builder.add(DocumentTypes.KIND, new Contribution(otherDocument, OWNER,
                        Contribution.Side.COMMON, Set.of()), new TestType());
                required = Set.of(docKey(), otherDocument);
            }
            add(builder, adapter, RUNTIME, required);
            try (var f = project(directory.resolve(fault), builder.freeze(),
                    List.of(source(FIRST, "saved")))) {
                typed(() -> prepare(f, validated(f)));
            }
        }
        try (var f = project(directory.resolve("caller-class"), registered(new TestType(), adapter()),
                List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            typed(() -> f.access().prepareValidatedRuntime(source, f.registry(), FIRST, ADAPTER, Object.class, () -> false));
            typed(() -> f.access().prepareValidatedRuntime(source, f.registry(), SECOND, ADAPTER, String.class, () -> false));
        }
    }

    @Test void nullAndErasedWrongClassResultsAreTypedFailuresAndGuardRecovers() throws Exception {
        TestAdapter<String, Object> adapter = new TestAdapter<>(String.class, castClass(String.class));
        try (var f = project(directory.resolve("bad-result"), registered(new TestType(), adapter),
                List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            for (Object invalid : new Object[] { null, Integer.valueOf(42) }) {
                adapter.action = (input, cancelled) -> invalid;
                typed(() -> prepare(f, source));
                assertEquals(source.saved(), f.access().read().saved());
            }
            adapter.action = (input, cancelled) -> "healthy";
            assertEquals("healthy", prepare(f, source).value());
        }
    }

    @Test void runtimeModuleEvidenceContainsExactSortedStaticTransitiveClosureOnly() throws Exception {
        var builder = runtimeBuilder(new TestType());
        builder.addModule(new ModuleSpec(HELPER, "7.03", new ApiVersion(1, 0), Set.of(TRANSITIVE), Set.of()));
        builder.addModule(new ModuleSpec(TRANSITIVE, "3+build", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(EditorId.parse("other:unused"), "99", new ApiVersion(1, 0), Set.of(), Set.of()));
        add(builder, adapter(), HELPER, Set.of(docKey()));
        ExtensionRegistry registry = builder.freeze();
        try (var f = project(directory.resolve("closure"), registry, List.of(source(FIRST, "saved")))) {
            Map<EditorId, String> expected = new TreeMap<>(Map.of(OWNER, BUILD, HELPER, "7.03", TRANSITIVE, "3+build"));
            var actual = prepare(f, validated(f)).runtimeModules();
            assertEquals(expected, actual);
            assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
            assertThrows(UnsupportedOperationException.class, () -> actual.put(RUNTIME, "intrusion"));
            assertEquals(Map.of(OWNER.toString(), BUILD), f.store().load().state().modules());
        }
    }

    @Test void allAdapterCallbacksAndCancellationSupplierRejectCrossAccessReentryAndDraftMutation() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        try (var f = project(directory.resolve("reentry"), registered(new TestType(), adapter),
                List.of(source(FIRST, "saved"))); var other = project(directory.resolve("other"),
                registry(new TestType()), List.of(source(FIRST, "other")))) {
            LoadedProject source = validated(f);
            try (ProjectSession session = ProjectSession.open(f.access(), source, f.registry(), 8)) {
                var handle = session.find(FIRST, String.class).orElseThrow();
                handle.edit(handle.view().token(), LABEL, value -> "draft");
                var before = handle.view();
                ProjectSaveCapture capture = session.captureSave(Set.of(FIRST));
                Runnable reentry = () -> {
                    assertThrows(IllegalStateException.class, f.access()::read);
                    assertThrows(IllegalStateException.class, other.access()::read);
                    assertThrows(IllegalStateException.class, other.access()::captureSnapshot);
                    assertThrows(IllegalStateException.class, () -> prepare(f, source));
                    assertThrows(IllegalStateException.class, () -> f.access().prepare(capture));
                    assertThrows(IllegalStateException.class, () -> handle.edit(before.token(), LABEL, value -> "intrusion"));
                    assertThrows(IllegalStateException.class, session::close);
                    assertEquals("draft", handle.view().content());
                };
                Set<String> observed = new HashSet<>();
                adapter.hook = stage -> { observed.add(stage); reentry.run(); };
                adapter.action = (input, cancelled) -> { assertFalse(cancelled.getAsBoolean()); return input.content(); };
                f.access().prepareValidatedRuntime(source, f.registry(), FIRST, ADAPTER, String.class,
                        () -> { reentry.run(); return false; });
                assertTrue(observed.containsAll(Set.of("documentType", "inputType", "resultType", "bind", "prepare")));
                assertEquals(before, handle.view());
                assertFalse(session.isClosed());
                adapter.hook = stage -> { };
                assertNotNull(f.access().prepare(capture), "denied reentry did not consume capture");
            }
        }
    }

    @Test void adapterCancellationSupplierCannotRecursivelyEvaluateItself() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        AtomicReference<BooleanSupplier> poll = new AtomicReference<>();
        AtomicBoolean recurse = new AtomicBoolean();
        adapter.action = (input, cancelled) -> {
            poll.set(cancelled);
            recurse.set(true);
            try { assertThrows(IllegalStateException.class, cancelled::getAsBoolean); }
            finally { recurse.set(false); }
            return input.content();
        };
        try (var f = project(directory.resolve("recursive"), registered(new TestType(), adapter), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            assertEquals("saved", f.access().prepareValidatedRuntime(source, f.registry(), FIRST, ADAPTER, String.class,
                    () -> recurse.get() && poll.get().getAsBoolean()).value());
            assertEquals(source.saved(), f.access().read().saved());
        }
    }

    @Test void realPrepareLatchKeepsValidationGuardThroughAdapterExecution() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        adapter.action = (input, cancelled) -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return input.content();
        };
        try (var f = project(directory.resolve("busy"), registered(new TestType(), adapter), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<PreparedRuntime<String>> running = worker.submit(() -> prepare(f, source));
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    busy(f.access()::read);
                    busy(f.access()::captureSnapshot);
                    busy(() -> f.access().readValidated(f.registry(), () -> false));
                    busy(() -> f.access().verifyValidatedContent(source, f.registry(), () -> false));
                    busy(() -> prepare(f, source));
                } finally { release.countDown(); }
                assertEquals("saved", running.get(10, TimeUnit.SECONDS).value());
                assertEquals("saved", prepare(f, source).value());
            } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void adapterAndSupplierFailuresPreserveOriginalObjectsAndAlwaysReleaseGuard() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        try (var f = project(directory.resolve("failures"), registered(new TestType(), adapter), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            for (Throwable failure : List.of(new IOException("io"), new IllegalArgumentException("runtime"), new AssertionError("error"))) {
                adapter.action = (input, cancelled) -> { throwFailure(failure); return "unreachable"; };
                assertSame(failure, assertThrows(failure.getClass(), () -> prepare(f, source)));
                adapter.action = (input, cancelled) -> input.content();
                assertEquals("saved", prepare(f, source).value());
            }
            for (Throwable failure : List.of(new IllegalArgumentException("supplier"), new AssertionError("supplier"))) {
                assertSame(failure, assertThrows(failure.getClass(), () -> f.access().prepareValidatedRuntime(
                        source, f.registry(), FIRST, ADAPTER, String.class, () -> {
                            if (failure instanceof RuntimeException runtime) throw runtime;
                            throw (Error) failure;
                        })));
                adapter.action = (input, cancelled) -> { cancelled.getAsBoolean(); return input.content(); };
                AtomicBoolean inside = new AtomicBoolean();
                adapter.hook = stage -> { if (stage.equals("prepare")) inside.set(true); };
                assertSame(failure, assertThrows(failure.getClass(), () -> f.access().prepareValidatedRuntime(
                        source, f.registry(), FIRST, ADAPTER, String.class, () -> {
                            if (inside.get()) { if (failure instanceof RuntimeException runtime) throw runtime; throw (Error) failure; }
                            return false;
                        })));
                adapter.hook = stage -> { };
                assertEquals("saved", prepare(f, source).value());
            }
        }
    }

    @Test void earlyInternalLatchedAndFinalPhysicalScanCancellationNeverReturnSuccess() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        AtomicInteger prepareCalls = new AtomicInteger();
        adapter.action = (input, cancelled) -> { prepareCalls.incrementAndGet(); return input.content(); };
        try (var f = project(directory.resolve("cancel"), registered(new TestType(), adapter), List.of(source(FIRST, "saved")))) {
            LoadedProject source = validated(f);
            assertThrows(ResourceValidationCancelledException.class, () -> f.access().prepareValidatedRuntime(
                    source, f.registry(), FIRST, ADAPTER, String.class, () -> true));
            assertEquals(0, prepareCalls.get());
            AtomicBoolean underlying = new AtomicBoolean();
            adapter.action = (input, cancelled) -> {
                underlying.set(true);
                assertTrue(cancelled.getAsBoolean());
                underlying.set(false);
                assertTrue(cancelled.getAsBoolean(), "true remains latched inside native polling");
                return input.content();
            };
            assertThrows(ResourceValidationCancelledException.class, () -> f.access().prepareValidatedRuntime(
                    source, f.registry(), FIRST, ADAPTER, String.class, underlying::get));
            adapter.action = (input, cancelled) -> input.content();
            AtomicInteger normal = new AtomicInteger();
            f.access().prepareValidatedRuntime(source, f.registry(), FIRST, ADAPTER, String.class,
                    () -> { normal.incrementAndGet(); return false; });
            int finalCheck = normal.get();
            assertTrue(finalCheck > 0);
            AtomicInteger attempted = new AtomicInteger();
            assertThrows(ResourceValidationCancelledException.class, () -> f.access().prepareValidatedRuntime(
                    source, f.registry(), FIRST, ADAPTER, String.class,
                    () -> attempted.incrementAndGet() == finalCheck));
            assertEquals(finalCheck, attempted.get());
            assertEquals("saved", prepare(f, source).value());
        }
    }

    @Test void physicalBlobCorruptionInsidePrepareRejectsLateSuccessAndRecoversAfterRepair() throws Exception {
        TestAdapter<String, String> adapter = adapter();
        Path path = directory.resolve("late-corruption");
        try (var f = project(path, registered(new TestType(), adapter), List.of(source(FIRST, "original")))) {
            LoadedProject source = validated(f);
            Path blob = blob(path, source);
            byte[] original = Files.readAllBytes(blob);
            adapter.action = (input, cancelled) -> { Files.write(blob, bytes("tampered")); return "late success"; };
            try {
                assertEquals(ProjectStorageException.Code.CORRUPT_CONTENT,
                        assertThrows(ProjectStorageException.class, () -> prepare(f, source)).code());
            } finally { Files.write(blob, original); }
            adapter.action = (input, cancelled) -> input.content();
            assertEquals("original", prepare(f, source).value());
        }
    }

    @Test void openDraftUndoRedoAndPendingPublicationBaselineSurvivePreparation() throws Exception {
        TestType type = new TestType();
        try (var f = project(directory.resolve("draft"), registered(type, adapter()), List.of(source(FIRST, "saved A")))) {
            LoadedProject source = validated(f);
            try (ProjectSession session = ProjectSession.open(f.access(), source, f.registry(), 8)) {
                var handle = session.find(FIRST, String.class).orElseThrow();
                handle.edit(handle.view().token(), LABEL, value -> "captured B");
                session.enqueueSave(f.access().prepare(session.captureSave(Set.of(FIRST))));
                handle.edit(handle.view().token(), LABEL, value -> "draft C");
                var before = handle.view();
                var saves = session.saves();
                int encodings = type.encodings, creates = type.creates;
                assertEquals("saved A", prepare(f, source).value());
                assertEquals(before, handle.view());
                assertEquals(saves, session.saves());
                assertEquals(encodings, type.encodings);
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
                assertFalse(handle.hasUnpublishedChanges());
                assertEquals("saved A", prepare(f, source).value());
            }
        }
    }

    /** A third-party implementation uses only the public runtime SPI and public registry. */
    public static final class TestAdapter<T, R> implements SavedRuntimeAdapter<T, R> {
        EditorId document = TYPE;
        Class<T> input;
        Class<R> result;
        ExtensionRegistry boundRegistry;
        boolean customBinding;
        SavedRuntimeAdapter<T, R> binding;
        Consumer<String> hook = stage -> { };
        RuntimeAction<T, R> action;
        public TestAdapter(Class<T> input, Class<R> result) { this.input = input; this.result = result; }
        @Override public EditorId documentType() { hook.accept("documentType"); return document; }
        @Override public Class<T> inputType() { hook.accept("inputType"); return input; }
        @Override public Class<R> resultType() { hook.accept("resultType"); return result; }
        @Override public SavedRuntimeAdapter<T, R> bind(ExtensionRegistry registry) {
            hook.accept("bind"); boundRegistry = registry; return customBinding ? binding : this;
        }
        @Override public R prepare(SavedRuntimeInput<T> input, BooleanSupplier cancelled) throws IOException {
            hook.accept("prepare"); return action.run(input, cancelled);
        }
    }
    @FunctionalInterface interface RuntimeAction<T, R> {
        R run(SavedRuntimeInput<T> input, BooleanSupplier cancelled) throws IOException;
    }
    private static TestAdapter<String, String> adapter() {
        TestAdapter<String, String> result = new TestAdapter<>(String.class, String.class);
        result.action = (input, cancelled) -> input.content();
        return result;
    }
    private static ExtensionRegistryBuilder runtimeBuilder(TestType type) {
        var result = builder(type);
        result.define(SavedRuntimeAdapters.KIND);
        result.addModule(new ModuleSpec(RUNTIME, "runtime+1", new ApiVersion(1, 0), Set.of(), Set.of()));
        return result;
    }
    private static ExtensionRegistry registered(TestType type, SavedRuntimeAdapter<?, ?> adapter) {
        var builder = runtimeBuilder(type);
        add(builder, adapter, RUNTIME, Set.of(docKey()));
        return builder.freeze();
    }
    private static void add(ExtensionRegistryBuilder builder, SavedRuntimeAdapter<?, ?> adapter,
            EditorId owner, Set<ContributionKey> required) {
        builder.add(SavedRuntimeAdapters.KIND, new Contribution(
                new ContributionKey(SavedRuntimeAdapters.KIND.id(), ADAPTER), owner, Contribution.Side.COMMON, required), adapter);
    }
    private static ContributionKey docKey() { return new ContributionKey(DocumentTypes.KIND.id(), TYPE); }
    private static LoadedProject validated(Fixture f) throws IOException { return f.access().readValidated(f.registry(), () -> false); }
    private static PreparedRuntime<String> prepare(Fixture f, LoadedProject source) throws IOException {
        return f.access().prepareValidatedRuntime(source, f.registry(), FIRST, ADAPTER, String.class, () -> false);
    }
    private static SessionRejection typed(Executable action) {
        SessionRejection rejection = assertThrows(SessionRejection.class, action);
        assertTrue(rejection.problems().stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR
                && p.message() instanceof TextContent.Message), () -> rejection.problems().toString());
        return rejection;
    }
    private static void required(Executable action) {
        assertEquals(List.of(EditorId.parse("editor:session/saved_source_validation_required")),
                typed(action).problems().stream().map(Problem::code).toList());
    }
    private static void busy(Executable action) {
        assertEquals(List.of(EditorId.parse("editor:runtime_save/io_busy")),
                typed(action).problems().stream().map(Problem::code).toList());
    }
    private static Path blob(Path path, LoadedProject source) {
        return path.resolve("documents").resolve(source.saved().state().documents().get(FIRST).content().sha256() + ".blob");
    }
    @SuppressWarnings("unchecked") private static <T> Class<T> castClass(Class<?> value) { return (Class<T>) value; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw (Error) failure;
    }
}
