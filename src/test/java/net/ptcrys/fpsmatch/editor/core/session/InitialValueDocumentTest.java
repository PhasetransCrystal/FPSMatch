package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.BUILD;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.OWNER;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.project;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentRejection;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import net.ptcrys.fpsmatch.editor.core.types.values.ActivatedValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueSnapshot;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Initial-value creation exercised through real frozen registries and temporary local stores. */
class InitialValueDocumentTest {
    private static final EditorId DOC = id("initial:document");
    private static final EditorId CODEC = id("initial:codec");
    private static final EditorId LEAF = id("third:leaf");
    private static final EditorId LEAF_CODEC = id("third:leaf_codec");
    private static final EditorId THIRD = id("third:module");
    private static final EditorId REQUIRED = id("third:required_module");
    private static final ContributionKey MISSING = new ContributionKey(ValueTypes.KIND.id(), id("absent:leaf_child"));
    private static final TextContent EDIT = new TextContent.Message("test.initial.edit", List.of());
    private static final TextContent LATER = new TextContent.Message("test.initial.later", List.of());
    private static final DocumentDependencies EMPTY = new DocumentDependencies(Set.of(), Set.of(), Set.of());
    private static final List<Problem> INVALID = List.of(
            problem(Problem.Severity.WARNING, "initial:warning"),
            problem(Problem.Severity.ERROR, "initial:invalid"));

    @TempDir Path directory;

    @Test void suppliedValueUsesBoundCodecWithoutDefaultFactoryAndBecomesCompleteHistoryBaseline() throws Exception {
        Path path = directory.resolve("round-trip");
        UUID document = UUID.randomUUID();
        Control control = new Control();
        Box imported = box("imported", "second");
        byte[] importedEncoding = bytes("imported,second|third-party|");
        try (var fixture = project(path, registry(control), List.of()); var session = fixture.open()) {
            var handle = session.createWithValue(document, DOC, Box.class, "Converted map", imported);
            assertEquals(0, control.prototypeCalls, "business callbacks must use the bound instance");
            assertEquals(0, control.creates, "the default factory deliberately throws");
            assertEquals(0, control.decodes, "conversion is not decoding old bytes");
            assertTrue(control.leafCalls > 0, "an actual third-party value codec participates");
            assertEquals(0, handle.view().token().revision());
            assertFalse(handle.view().dirty());
            assertTrue(handle.hasUnpublishedChanges());
            assertTrue(handle.undoLabel().isEmpty());
            assertTrue(handle.redoLabel().isEmpty());
            assertFalse(handle.view().canUndo());
            assertFalse(handle.view().canRedo());

            imported.entries().clear();
            imported.entries().add("caller changed its candidate");
            assertEquals(List.of("imported", "second"), handle.view().content().entries());
            handle.edit(handle.view().token(), EDIT, value -> box("edited"));
            assertTrue(handle.view().dirty());
            handle.undo(handle.view().token());
            assertEquals(List.of("imported", "second"), handle.view().content().entries());
            assertFalse(handle.view().dirty());
            assertTrue(handle.hasUnpublishedChanges());
            handle.redo(handle.view().token());
            assertEquals(List.of("edited"), handle.view().content().entries());
            handle.undo(handle.view().token());

            publish(fixture.access(), session, Set.of(document));
            assertFalse(handle.hasUnpublishedChanges());
            assertFalse(handle.view().dirty());
            assertArrayEquals(importedEncoding, fixture.access().read().documentBytes(document));
            assertEquals(Map.of(OWNER.toString(), BUILD, THIRD.toString(), "7.3", REQUIRED.toString(), "2.1"),
                    fixture.store().load().state().modules());
            assertEquals(LATER, editAndUndoAfterSave(handle));
            assertFalse(handle.hasUnpublishedChanges());
        }
        Control fresh = new Control();
        try (var store = LocalProjectStore.open(path)) {
            var access = new LocalProjectAccess(store);
            var loaded = access.read();
            try (var reopened = ProjectSession.open(access, loaded, registry(fresh), 8)) {
                assertFalse(reopened.protectedMode());
                var handle = reopened.find(document, Box.class).orElseThrow();
                assertEquals(new Box(List.of("imported", "second"), "third-party", null), handle.view().content());
                assertArrayEquals(importedEncoding, loaded.documentBytes(document));
                assertFalse(handle.hasUnpublishedChanges());
                assertTrue(fresh.leafCalls > 0);
                assertEquals(0, fresh.creates);
            }
        }
    }

    @Test void eachInitializationFailureLeavesExistingDirtyHistoryQueueAndCurrentUntouchedAndSameIdCanRetry()
            throws Exception {
        UUID existing = UUID.randomUUID();
        Control control = new Control();
        try (var fixture = project(directory.resolve("atomic"), registry(control), List.of(source(existing, "stored")), recordedModules());
             var session = fixture.open()) {
            var old = session.find(existing, Box.class).orElseThrow();
            old.edit(old.view().token(), EDIT, value -> box("dirty"));
            old.edit(old.view().token(), LATER, value -> box("future"));
            old.undo(old.view().token());
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(existing))));
            old.setPendingInput("name", "unfinished");
            assertTrue(old.view().dirty());
            assertTrue(old.view().canUndo());
            assertTrue(old.view().canRedo());

            for (Failure failure : Failure.values()) {
                UUID candidate = UUID.randomUUID();
                var oldView = old.view();
                var undo = old.undoLabel();
                var redo = old.redoLabel();
                var documents = session.documents();
                var failures = session.documentFailures();
                var queue = session.saves();
                var source = session.source();
                var current = fixture.store().load();
                control.failure = failure;
                Throwable rejected;
                if (failure == Failure.FATAL) {
                    rejected = assertThrows(AssertionError.class,
                            () -> session.createWithValue(candidate, DOC, Box.class, "rejected", box("candidate")));
                    assertSame(control.fatal, rejected);
                } else {
                    rejected = assertThrows(RuntimeException.class,
                            () -> session.createWithValue(candidate, DOC, Box.class, "rejected", box("candidate")));
                    if (failure == Failure.INVALID) {
                        assertEquals(INVALID, assertInstanceOf(DocumentRejection.class, rejected).problems());
                    } else if (failure == Failure.MISSING_NESTED) {
                        assertEquals("editor:session/contribution_unavailable",
                                assertInstanceOf(SessionRejection.class, rejected).problems().get(0).code().toString());
                    } else if (failure == Failure.NULL_SNAPSHOT || failure == Failure.NULL_ENCODING) {
                        assertInstanceOf(NullPointerException.class, rejected);
                    } else {
                        assertSame(control.original, rejected, failure.toString());
                    }
                }
                control.failure = null;
                assertEquals(documents, session.documents(), failure.toString());
                assertTrue(session.find(candidate, Box.class).isEmpty());
                assertEquals(failures, session.documentFailures());
                assertEquals(oldView, old.view());
                assertEquals(undo, old.undoLabel());
                assertEquals(redo, old.redoLabel());
                assertTrue(old.hasUnpublishedChanges());
                assertEquals(queue, session.saves());
                assertSame(source, session.source());
                assertEquals(current, fixture.store().load());
                var retry = session.createWithValue(candidate, DOC, Box.class, "retry", box("accepted"));
                assertEquals(candidate, retry.id());
                assertEquals(List.of("accepted"), retry.view().content().entries());
                assertTrue(retry.hasUnpublishedChanges());
                assertEquals(queue, session.saves());
                assertEquals(current, fixture.store().load());
            }
            assertEquals(0, control.creates);
            old.redo(old.view().token());
            assertEquals(List.of("future"), old.view().content().entries(), "the redo branch survived every failure");
            old.undo(old.view().token());
            old.undo(old.view().token());
            assertEquals(List.of("stored"), old.view().content().entries(), "the older undo branch also survived");
            var job = session.dispatchSave().orElseThrow().job().orElseThrow();
            assertTrue(session.completeSave(job, fixture.access().publish(job)));
            assertArrayEquals(bytes("dirty|third-party|"), fixture.access().read().documentBytes(existing));
            assertTrue(old.hasUnpublishedChanges(), "confirmation only cleans the queued capture, not later undo");
        }
    }

    @Test void invalidArgumentsUnknownTypeAndWrongExactClassDoNotEnterBusinessCallbacks() throws Exception {
        Control control = new Control();
        UUID existing = UUID.randomUUID();
        try (var fixture = project(directory.resolve("preflight"), registry(control),
                List.of(source(existing, "stored")), recordedModules()); var session = fixture.open()) {
            var old = session.find(existing, Box.class).orElseThrow();
            old.edit(old.view().token(), EDIT, value -> box("dirty"));
            old.edit(old.view().token(), LATER, value -> box("future"));
            old.undo(old.view().token());
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(existing))));
            List<Consumer<UUID>> invalidCalls = List.of(
                    candidate -> assertThrows(NullPointerException.class,
                            () -> session.createWithValue(null, DOC, Box.class, "name", box("x"))),
                    candidate -> assertThrows(NullPointerException.class,
                            () -> session.createWithValue(candidate, null, Box.class, "name", box("x"))),
                    candidate -> assertThrows(NullPointerException.class,
                            () -> session.createWithValue(candidate, DOC, null, "name", box("x"))),
                    candidate -> assertThrows(NullPointerException.class,
                            () -> session.createWithValue(candidate, DOC, Box.class, null, box("x"))),
                    candidate -> assertThrows(NullPointerException.class,
                            () -> session.createWithValue(candidate, DOC, Box.class, "name", null)),
                    candidate -> assertThrows(IllegalArgumentException.class,
                            () -> session.createWithValue(candidate, DOC, Box.class, "\uD800", box("x"))),
                    candidate -> assertThrows(IllegalArgumentException.class,
                            () -> session.createWithValue(candidate, DOC, Box.class, "x".repeat(4097), box("x"))),
                    candidate -> assertThrows(SessionRejection.class,
                            () -> session.createWithValue(candidate, id("absent:document"), Box.class, "name", box("x"))),
                    candidate -> assertThrows(IllegalArgumentException.class,
                            () -> session.createWithValue(candidate, DOC, Object.class, "name", box("x"))),
                    candidate -> assertWrongErasedValue(session, candidate));
            for (var call : invalidCalls) {
                UUID candidate = UUID.randomUUID();
                int callbacks = control.calls;
                var before = session.documents();
                var view = old.view();
                var undo = old.undoLabel();
                var redo = old.redoLabel();
                var queue = session.saves();
                call.accept(candidate);
                assertEquals(callbacks, control.calls, "preflight must not snapshot, validate, encode or collect dependencies");
                assertEquals(before, session.documents());
                assertEquals(view, old.view());
                assertEquals(undo, old.undoLabel());
                assertEquals(redo, old.redoLabel());
                assertTrue(old.hasUnpublishedChanges());
                assertEquals(queue, session.saves());
                assertEquals(fixture.loaded().saved(), fixture.store().load());
                assertEquals(candidate, session.createWithValue(candidate, DOC, Box.class, "retry", box("ok")).id());
            }
            assertEquals(0, control.creates);
            assertEquals(0, control.prototypeCalls);
        }
    }

    @Test void storedDocumentUnavailableDocumentAndAssetIdsRejectBeforeBusinessCallbacks() throws Exception {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        Control control = new Control();
        var unavailable = new ProjectSessionFixtures.SourceDocument(unknown, OWNER.toString(), "absent:document",
                CODEC.toString(), 1, "unavailable", new byte[]{3, 2, 1}, Set.of(), Set.of());
        try (var fixture = project(directory.resolve("collisions"), registry(control),
                List.of(source(known, "stored"), unavailable), recordedModules())) {
            var saved = fixture.store().load();
            var assetEntry = new ProjectState.Entry(asset, OWNER.toString(), "initial:raw_asset", "initial:raw", 1,
                    "raw", fixture.store().stage(BlobRef.Kind.ASSET, new byte[]{9, 8}), Set.of(), Set.of());
            fixture.store().commit(saved.commitId(), UUID.randomUUID(), new ProjectState(saved.state().projectId(),
                    saved.state().name(), saved.state().modules(), saved.state().documents(), Map.of(asset, assetEntry), null));
            var loaded = fixture.access().read();
            try (var session = ProjectSession.open(fixture.access(), loaded, fixture.registry(), 8)) {
                var before = session.documents();
                var current = fixture.store().load();
                int callbacks = control.calls;
                for (UUID collision : List.of(known, unknown, asset)) {
                    SessionRejection failure = assertThrows(SessionRejection.class,
                            () -> session.createWithValue(collision, DOC, Box.class, "replacement", box("wrong")));
                    assertEquals("editor:runtime/document_identity_conflict", failure.problems().get(0).code().toString());
                    assertEquals(callbacks, control.calls);
                    assertEquals(before, session.documents());
                    assertEquals(current, fixture.store().load());
                }
                assertArrayEquals(new byte[]{3, 2, 1}, session.source().documentBytes(unknown));
                assertEquals(assetEntry, session.source().saved().state().assets().get(asset));
            }
        }
    }

    @Test void ownerThreadRejectionAndClosedSessionRunNoBusinessCallback() throws Exception {
        UUID candidate = UUID.randomUUID();
        Control control = new Control();
        try (var fixture = project(directory.resolve("owner"), registry(control), List.of())) {
            var session = fixture.open();
            try {
                int callbacks = control.calls;
                CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class,
                        () -> session.createWithValue(candidate, DOC, Box.class, "wrong thread", box("x"))))
                        .get(5, TimeUnit.SECONDS);
                assertEquals(callbacks, control.calls);
                assertTrue(session.documents().isEmpty());
                assertEquals(candidate, session.createWithValue(candidate, DOC, Box.class, "owner retry", box("ok")).id());
                session.close();
                callbacks = control.calls;
                UUID late = UUID.randomUUID();
                assertThrows(IllegalStateException.class,
                        () -> session.createWithValue(late, DOC, Box.class, "late candidate", box("late")));
                assertEquals(callbacks, control.calls);
                assertEquals(fixture.loaded().saved(), fixture.store().load());
                try (var reopened = ProjectSession.open(fixture.access(), fixture.access().read(), registry(new Control()), 8)) {
                    assertEquals(late, reopened.createWithValue(late, DOC, Box.class, "fresh session retry", box("ok")).id());
                }
            } finally {
                session.close();
            }
        }
    }

    @Test void callbacksCannotPublishNestedInitialValuesMutateOtherDraftsOrCloseTheSession() throws Exception {
        Control control = new Control();
        UUID oldId = UUID.randomUUID();
        try (var fixture = project(directory.resolve("reentry"), registry(control), List.of(source(oldId, "stored")), recordedModules())) {
            var session = fixture.open();
            try {
                var old = session.find(oldId, Box.class).orElseThrow();
                old.edit(old.view().token(), EDIT, value -> box("dirty"));
                session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(oldId))));
                for (Stage stage : Stage.values()) {
                    for (int action = 0; action < 3; action++) {
                        UUID outer = UUID.randomUUID();
                        UUID nested = UUID.randomUUID();
                        var before = old.view();
                        var directoryBefore = session.documents();
                        var queue = session.saves();
                        int selected = action;
                        control.hookStage = stage;
                        control.hook = () -> {
                            assertEquals(directoryBefore, session.documents(), "no partially initialized outer handle is visible");
                            assertEquals(before, old.view());
                            if (selected == 0) session.createWithValue(nested, DOC, Box.class, "nested", box("nested"));
                            else if (selected == 1) old.edit(before.token(), LATER, value -> box("forbidden"));
                            else session.close();
                        };
                        SessionRejection rejected = assertThrows(SessionRejection.class,
                                () -> session.createWithValue(outer, DOC, Box.class, "outer", box("outer")));
                        assertEquals("editor:runtime/reentrant_mutation", rejected.problems().get(0).code().toString());
                        control.hook = null;
                        assertEquals(before, old.view());
                        assertEquals(directoryBefore, session.documents());
                        assertEquals(queue, session.saves());
                        assertFalse(session.isClosed());
                        assertEquals(fixture.loaded().saved(), fixture.store().load());
                        assertEquals(outer, session.createWithValue(outer, DOC, Box.class, "retry outer", box("ok")).id());
                        if (selected == 0) {
                            assertEquals(nested, session.createWithValue(nested, DOC, Box.class, "retry nested", box("ok")).id());
                        }
                    }
                }
            } finally {
                session.close();
            }
        }
    }

    @Test void aCreationAttemptInsideAnotherDocumentsEditIsRejectedBeforeItsCodecRuns() throws Exception {
        UUID oldId = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        Control control = new Control();
        try (var fixture = project(directory.resolve("incoming-reentry"), registry(control), List.of(source(oldId, "stored")), recordedModules());
             var session = fixture.open()) {
            var old = session.find(oldId, Box.class).orElseThrow();
            var before = old.view();
            SessionRejection rejected = assertThrows(SessionRejection.class, () -> old.edit(before.token(), EDIT, value -> {
                int calls = control.calls;
                try {
                    session.createWithValue(candidate, DOC, Box.class, "nested", box("x"));
                    fail("nested create must be refused");
                } finally {
                    assertEquals(calls, control.calls);
                }
                return value;
            }));
            assertEquals("editor:runtime/reentrant_mutation", rejected.problems().get(0).code().toString());
            assertEquals(before, old.view());
            assertEquals(candidate, session.createWithValue(candidate, DOC, Box.class, "retry", box("ok")).id());
        }
    }

    @Test void forwardDocumentReferencesMayBeCreatedBeforeTheirTargetsAndProtectedProjectsStillAllowDrafts()
            throws Exception {
        Control control = new Control();
        UUID first = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        try (var fixture = project(directory.resolve("forward"), registry(control), List.of()); var session = fixture.open()) {
            var forward = new Box(new ArrayList<>(List.of("forward")), "third-party", target);
            assertEquals(first, session.createWithValue(first, DOC, Box.class, "forward", forward).id());
            session.createWithValue(target, DOC, Box.class, "target", box("target"));
            publish(fixture.access(), session, Set.of(first, target));
            assertEquals(Set.of(target), fixture.store().load().state().documents().get(first).documentDependencies());
        }
        try (var fixture = project(directory.resolve("protected"), registry(new Control()), List.of(),
                Map.of(OWNER.toString(), BUILD, "absent:module", "1")); var session = fixture.open()) {
            assertTrue(session.protectedMode());
            UUID draft = UUID.randomUUID();
            var handle = session.createWithValue(draft, DOC, Box.class, "protected draft", box("retained"));
            assertTrue(handle.hasUnpublishedChanges());
            SessionRejection rejected = assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(draft)));
            assertEquals("editor:runtime_save/protected_project", rejected.problems().get(0).code().toString());
            assertEquals(fixture.loaded().saved(), fixture.store().load());
        }
    }

    private static TextContent editAndUndoAfterSave(DocumentHandle<Box> handle) {
        handle.edit(handle.view().token(), LATER, value -> box("after save"));
        var label = handle.undoLabel().orElseThrow();
        handle.undo(handle.view().token());
        return label;
    }

    private static void assertWrongErasedValue(ProjectSession session, UUID candidate) {
        // Reflection supplies the otherwise unrepresentable erroneous Java caller without unsafe casts.
        InvocationTargetException rejected = assertThrows(InvocationTargetException.class,
                () -> ProjectSession.class.getMethod("createWithValue", UUID.class, EditorId.class,
                        Class.class, String.class, Object.class)
                        .invoke(session, candidate, DOC, Box.class, "wrong payload", "not a Box"));
        assertInstanceOf(ClassCastException.class, rejected.getCause());
    }

    private static void publish(LocalProjectAccess access, ProjectSession session, Set<UUID> documents) throws Exception {
        session.enqueueSave(access.prepare(session.captureSave(documents)));
        var dispatch = session.dispatchSave().orElseThrow();
        assertTrue(dispatch.problems().isEmpty(), dispatch.problems().toString());
        var job = dispatch.job().orElseThrow();
        assertTrue(session.completeSave(job, access.publish(job)));
    }

    private static ProjectSessionFixtures.SourceDocument source(UUID document, String content) {
        return new ProjectSessionFixtures.SourceDocument(document, OWNER.toString(), DOC.toString(), CODEC.toString(),
                1, "existing", bytes(content + "|third-party|"), Set.of(), Set.of());
    }

    private static ExtensionRegistry registry(Control control) {
        ApiVersion api = new ApiVersion(1, 0);
        var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ValueTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.addModule(new ModuleSpec(OWNER, BUILD, api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(THIRD, "7.3", api, Set.of(REQUIRED), Set.of()));
        builder.addModule(new ModuleSpec(REQUIRED, "2.1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, contribution(DocumentTypes.KIND.id(), DOC, OWNER),
                new BoxType(control, null));
        builder.add(ValueTypes.KIND, contribution(ValueTypes.KIND.id(), LEAF, THIRD), new LeafType(control));
        var registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty());
        return registry;
    }

    private static Contribution contribution(EditorId kind, EditorId type, EditorId owner) {
        return new Contribution(new ContributionKey(kind, type), owner, Contribution.Side.COMMON, Set.of());
    }

    private static Map<String, String> recordedModules() {
        return Map.of(OWNER.toString(), BUILD, THIRD.toString(), "7.3", REQUIRED.toString(), "2.1");
    }

    private enum Stage { SNAPSHOT, VALIDATE, ENCODE, DEPENDENCIES }
    private enum Failure { SNAPSHOT, NULL_SNAPSHOT, VALIDATE_THROW, INVALID, ENCODE, NULL_ENCODING, DEPENDENCIES, MISSING_NESTED, FATAL }

    private static final class Control {
        int calls;
        int creates;
        int decodes;
        int prototypeCalls;
        int leafCalls;
        Failure failure;
        Stage hookStage;
        Runnable hook;
        final RuntimeException original = new IllegalStateException("initial value codec failure");
        final AssertionError fatal = new AssertionError("initial value fatal failure");
        void callback(Stage stage) {
            calls++;
            if (hook != null && stage == hookStage) hook.run();
            if ((stage == Stage.SNAPSHOT && failure == Failure.SNAPSHOT)
                    || (stage == Stage.VALIDATE && failure == Failure.VALIDATE_THROW)
                    || (stage == Stage.ENCODE && failure == Failure.ENCODE)
                    || (stage == Stage.DEPENDENCIES && failure == Failure.DEPENDENCIES)) throw original;
            if (stage == Stage.SNAPSHOT && failure == Failure.FATAL) throw fatal;
        }
    }

    /** Caller candidates may contain a mutable list; accepted snapshots contain a copied list. */
    private record Box(List<String> entries, String leaf, UUID reference) { }

    private static final class BoxType implements DocumentType<Box> {
        private final Control control;
        private final ActivatedValueType<?> leaf;
        BoxType(Control control, ActivatedValueType<?> leaf) { this.control = control; this.leaf = leaf; }
        @Override public Class<Box> valueType() { return Box.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public DocumentType<Box> bind(ExtensionRegistry registry) {
            return new BoxType(control, ValueTypeCatalog.activate(registry).find(LEAF).orElseThrow());
        }
        private void business(Stage stage) {
            if (leaf == null) { control.prototypeCalls++; throw new AssertionError("prototype callback"); }
            control.callback(stage);
        }
        @Override public Box create() {
            control.creates++;
            throw new AssertionError("default factory must never run for supplied values");
        }
        @Override public Box decode(int schema, byte[] payload) {
            if (leaf == null) { control.prototypeCalls++; throw new AssertionError("prototype decode"); }
            control.calls++;
            control.decodes++;
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            var decodedLeaf = leaf.decode(LEAF_CODEC, 1, bytes(fields[1]));
            return new Box(List.of(fields[0].split(",", -1)), String.class.cast(decodedLeaf.content()),
                    fields[2].isEmpty() ? null : UUID.fromString(fields[2]));
        }
        @Override public Box snapshot(Box value) {
            business(Stage.SNAPSHOT);
            if (control.failure == Failure.NULL_SNAPSHOT) return null;
            return new Box(List.copyOf(value.entries()), value.leaf(), value.reference());
        }
        @Override public List<Problem> validate(Box value) {
            business(Stage.VALIDATE);
            return control.failure == Failure.INVALID ? INVALID : List.of();
        }
        @Override public byte[] encode(Box value) {
            business(Stage.ENCODE);
            if (control.failure == Failure.NULL_ENCODING) return null;
            String leafEncoding = new String(capture(leaf, value.leaf()).encoding(), StandardCharsets.UTF_8);
            return bytes(String.join(",", value.entries()) + "|" + leafEncoding + "|"
                    + (value.reference() == null ? "" : value.reference()));
        }
        @Override public DocumentDependencies dependencies(Box value) {
            business(Stage.DEPENDENCIES);
            var nested = capture(leaf, value.leaf()).dependencies();
            return new DocumentDependencies(value.reference() == null ? Set.of() : Set.of(value.reference()),
                    nested.resources(), nested.contributions());
        }
    }

    /** This registered third-party codec, rather than the document, reports its missing child. */
    private static final class LeafType implements ValueType<String> {
        private final Control control;
        LeafType(Control control) { this.control = control; }
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return LEAF_CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String decode(int schema, byte[] payload) {
            control.leafCalls++;
            return new String(payload, StandardCharsets.UTF_8);
        }
        @Override public String snapshot(String value) { control.leafCalls++; return value; }
        @Override public List<Problem> validate(String value) { control.leafCalls++; return List.of(); }
        @Override public byte[] encode(String value) { control.leafCalls++; return bytes(value); }
        @Override public DocumentDependencies dependencies(String value) {
            control.leafCalls++;
            return control.failure == Failure.MISSING_NESTED
                    ? new DocumentDependencies(Set.of(), Set.of(), Set.of(MISSING)) : EMPTY;
        }
    }

    private static <T> ValueSnapshot<T> capture(ActivatedValueType<T> leaf, String value) {
        return leaf.capture(leaf.valueType().cast(value));
    }
    private static Box box(String... values) { return new Box(new ArrayList<>(List.of(values)), "third-party", null); }
    private static EditorId id(String value) { return EditorId.parse(value); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Problem problem(Problem.Severity severity, String code) {
        return new Problem(severity, id(code), DOC.toString(), new TextContent.Message("test.initial.invalid", List.of()));
    }
}
