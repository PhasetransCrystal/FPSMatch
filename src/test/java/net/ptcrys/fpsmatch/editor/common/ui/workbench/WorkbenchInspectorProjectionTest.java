package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditors;
import net.ptcrys.fpsmatch.editor.core.editing.StudioDocument;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchInspectorProjectionTest {
    @TempDir Path temporary;
    private static final EditorId OWNER = EditorId.parse("test:inspector_projection");
    private static final EditorId TYPE = EditorId.parse("test:inspector_document");
    private static final TextContent LABEL = literal("Document");

    @Test void warmSelectionUsesFullNodesAndReusesLazyFieldsWithoutEditorCalls() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("warm")); UUID document = h.document("Alpha");
            var projection = h.projection();
            var selected = projection.selection("inspector", document, "root");
            assertEquals(0, h.editor.fieldCalls, "Collapsed targets must not load their fields");
            assertEquals(0, h.editor.sceneCalls);
            assertEquals("internal", selected.sections().get(0).target().id());
            assertEquals(List.of("internal"), selected.references().get(0).selected());
            assertTrue(selected.node("internal").isPresent());
            var fields = selected.fields("internal"); var pages = selected.pages("internal");
            var stats = projection.stats(); int calls = h.editor.calls();
            for (int frame = 0; frame < 100; frame++) {
                assertSame(selected, projection.selection("inspector", document, "root"));
                assertSame(fields, selected.fields("internal")); assertSame(pages, selected.pages("internal"));
            }
            assertEquals(stats, projection.stats()); assertEquals(calls, h.editor.calls());
            var leaf = projection.selection("inspector", document, "leaf");
            assertEquals(List.of("root", "internal", "leaf"), leaf.path().stream().map(DocumentEditor.Node::id).toList());
            assertEquals(literal("Leaf base"), leaf.node().label());
            assertEquals(stats.nodeLoads(), projection.stats().nodeLoads());
            assertThrows(UnsupportedOperationException.class, () -> leaf.path().clear());
            assertThrows(UnsupportedOperationException.class, () -> selected.sections().clear());
            assertThrows(UnsupportedOperationException.class, () -> fields.clear());
        }
    }

    @Test void rawInputRemainsLiveWhileAppliedDataChangesOnlyWithTheToken() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("raw")); UUID document = h.document("Alpha"); var projection = h.projection();
            var selected = projection.selection("inspector", document, "root");
            var fields = selected.fields("root"); var token = selected.token();
            var address = new ProjectWorkbench.Address(document, "root", "value");
            h.model.input(address, "draft"); int calls = h.editor.calls();
            assertSame(selected, projection.selection("inspector", document, "root"));
            assertSame(fields, selected.fields("root")); assertEquals(token, selected.token());
            assertEquals("base", fields.get(0).raw()); assertEquals("draft", h.model.raw(address, fields.get(0).raw()));
            assertTrue(h.model.pending(address)); assertEquals(calls, h.editor.calls());
            h.model.apply(address);
            var applied = projection.selection("inspector", document, "root");
            assertNotSame(selected, applied); assertNotEquals(token, applied.token());
            assertEquals("draft", applied.fields("root").get(0).raw()); assertFalse(h.model.pending(address));
            assertThrows(IllegalStateException.class, () -> selected.fields("root"));
            assertEquals(literal("Root"), selected.node().label());
        }
    }

    @Test void selectionChangesReleaseTheirTargetsAndEditsDoNotInvalidateOtherDocuments() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("isolation")); UUID alpha = h.document("Alpha"), beta = h.document("Beta");
            var projection = h.projection();
            var a = projection.selection("left", alpha, "root"); a.fields("root");
            var b = projection.selection("right", beta, "root"); var bFields = b.fields("root");
            var changedSelection = projection.selection("left", alpha, "leaf");
            assertEquals(2, projection.stats().nodeLoads()); assertEquals(1, projection.stats().cachedFieldTargets());
            assertThrows(IllegalStateException.class, () -> a.pages("root"));
            h.edit(alpha, "changed");
            var changed = projection.selection("left", alpha, "leaf");
            assertNotSame(changedSelection, changed); assertEquals(literal("Leaf changed"), changed.node().label());
            assertSame(b, projection.selection("right", beta, "root")); assertSame(bFields, b.fields("root"));
            assertEquals(3, projection.stats().nodeLoads());
        }
    }

    @Test void equalRevisionBranchReplacementAndUndoRedoInvalidateTheEntireDocumentData() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("branches")); UUID document = h.document("Alpha"); var projection = h.projection();
            var initial = projection.selection("inspector", document, "root"); initial.fields("root");
            h.model.discardDocumentChanges(document);
            var branch = projection.selection("inspector", document, "root");
            assertEquals(initial.token().revision(), branch.token().revision());
            assertNotEquals(initial.token().branchId(), branch.token().branchId());
            assertNotSame(initial, branch); assertThrows(IllegalStateException.class, () -> initial.fields("root"));
            h.edit(document, "changed"); var edited = projection.selection("inspector", document, "root");
            assertEquals("changed", edited.fields("root").get(0).raw());
            h.model.undo(); var undone = projection.selection("inspector", document, "root");
            assertEquals("base", undone.fields("root").get(0).raw()); assertNotEquals(edited.token(), undone.token());
            h.model.redo(); var redone = projection.selection("inspector", document, "root");
            assertEquals("changed", redone.fields("root").get(0).raw()); assertNotEquals(undone.token(), redone.token());
            assertEquals(1, projection.stats().cachedDocuments()); assertEquals(1, projection.stats().cachedViews());
        }
    }

    @Test void directHandleEditsInvalidateEvenWithoutAWorkbenchVersionChange() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("direct-edit")); UUID document = h.document("Alpha"); var projection = h.projection();
            var selected = projection.selection("inspector", document, "root"); selected.fields("root");
            long version = h.model.version();
            @SuppressWarnings("unchecked") var handle = (net.ptcrys.fpsmatch.editor.core.session.DocumentHandle<String>) h.lists.documents().get(document);
            handle.edit(handle.view().token(), LABEL, value -> "external");
            assertEquals(version, h.model.version());
            assertThrows(IllegalStateException.class, () -> selected.fields("root"), "A cached target must still reject a changed token");
            var changed = projection.selection("inspector", document, "root");
            assertEquals("external", changed.fields("root").get(0).raw()); assertNotEquals(selected.token(), changed.token());
        }
    }

    @Test void invalidSelectionPreparationNeverInstallsAnOldOrPartiallyBuiltResult() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("invalid-selection")); UUID document = h.document("Alpha"); var projection = h.projection();
            var original = projection.selection("inspector", document, "root"); original.fields("root");
            for (String failure : List.of("invalid-section", "invalid-references")) {
                h.edit(document, failure);
                assertThrows(SessionRejection.class, () -> projection.selection("inspector", document, "root"));
                int calls = h.editor.calls();
                assertThrows(SessionRejection.class, () -> projection.selection("inspector", document, "root"));
                assertTrue(h.editor.calls() > calls, "Failed preparation must be retried, not cached as valid");
                assertEquals(1, projection.stats().selectionLoads());
                assertThrows(IllegalStateException.class, () -> original.fields("root"));
                assertEquals("internal", original.sections().get(0).target().id());
            }
            h.edit(document, "recovered");
            assertEquals("recovered", projection.selection("inspector", document, "root").fields("root").get(0).raw());
        }
    }

    @Test void lazyFieldFailurePreservesOtherTargetsAndDoesNotPoisonRetry() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("invalid-fields")); UUID document = h.document("Alpha"); var projection = h.projection();
            var selected = projection.selection("inspector", document, "root"); var root = selected.fields("root");
            var address = new ProjectWorkbench.Address(document, "root", "value"); h.model.input(address, "unapplied");
            h.editor.rejectInternalFields = true;
            assertThrows(SessionRejection.class, () -> selected.pages("internal"));
            int calls = h.editor.fieldCalls;
            assertThrows(SessionRejection.class, () -> selected.fields("internal")); assertEquals(calls + 1, h.editor.fieldCalls);
            assertEquals(1, projection.stats().fieldLoads()); assertEquals(1, projection.stats().cachedFieldTargets());
            assertSame(root, selected.fields("root"));
            assertTrue(h.model.pending(address)); assertEquals("unapplied", h.model.raw(address, "fallback"));
            h.editor.rejectInternalFields = false; assertEquals(1, selected.pages("internal").size());
            assertEquals(2, projection.stats().fieldLoads());
            calls = h.editor.fieldCalls; assertThrows(IllegalArgumentException.class, () -> selected.fields("missing"));
            assertEquals(calls, h.editor.fieldCalls);
        }
    }

    @Test void viewAndFieldTargetBudgetsEvictWithoutInvalidatingReturnedMetadata() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("budgets")); UUID document = h.document("Alpha");
            var projection = new WorkbenchInspectorProjection(h.model, h.lists, new WorkbenchInspectorProjection.Limits(2, 2));
            var a = projection.selection("a", document, "root"); var root = a.fields("root"); a.fields("internal");
            a.pages("root"); a.fields("leaf"); assertEquals(2, projection.stats().cachedFieldTargets());
            assertSame(root, a.fields("root")); int calls = h.editor.fieldCalls;
            a.fields("internal"); assertEquals(calls + 1, h.editor.fieldCalls);
            projection.selection("b", document, "leaf"); var c = projection.selection("c", document, "internal");
            assertEquals(2, projection.stats().cachedViews()); assertEquals(0, projection.stats().cachedFieldTargets());
            assertThrows(IllegalStateException.class, () -> a.fields("root")); assertEquals(literal("Root"), a.node().label());
            c.fields("internal"); projection.retainViews(Set.of("c"));
            assertEquals(1, projection.stats().cachedViews()); assertEquals(1, projection.stats().cachedDocuments());
            projection.retainViews(Set.of());
            assertEquals(0, projection.stats().cachedViews()); assertEquals(0, projection.stats().cachedDocuments());
            assertEquals(0, projection.stats().cachedFieldTargets()); assertThrows(IllegalStateException.class, () -> c.pages("internal"));
        }
    }

    @Test void closedOrSameIdReopenedDocumentsCannotServeOldLazyData() {
        try (var h = new Harness()) {
            Path path = temporary.resolve("reopen"); h.create(path); UUID document = h.document("Alpha");
            h.model.saveAll(); h.drain(); var projection = h.projection();
            var original = projection.selection("inspector", document, "root"); original.fields("root");
            var oldHandle = h.lists.documents().get(document); h.model.closeProject(true); h.drain();
            assertThrows(IllegalStateException.class, () -> original.fields("root"));
            assertEquals(0, projection.stats().cachedDocuments()); assertEquals(0, projection.stats().cachedViews());
            h.model.open(path); h.drain(); assertNotSame(oldHandle, h.lists.documents().get(document));
            var reopened = projection.selection("inspector", document, "root"); assertNotSame(original, reopened);
            assertThrows(IllegalStateException.class, () -> original.pages("root"));
            assertEquals(List.of("root"), original.path().stream().map(DocumentEditor.Node::id).toList());
            projection.clear(); assertEquals(0, projection.stats().cachedDocuments());
            assertThrows(IllegalStateException.class, () -> reopened.fields("root"));
        }
    }

    private static TextContent.Literal literal(String text) { return new TextContent.Literal(text); }
    private static final class CountingEditor implements DocumentEditor<String> {
        int nodeCalls, sceneCalls, sectionCalls, referenceCalls, fieldCalls;
        boolean rejectInternalFields;
        int calls() { return nodeCalls + sceneCalls + sectionCalls + referenceCalls + fieldCalls; }
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        private List<Node> tree(String value) {
            return List.of(new Node("root", Optional.empty(), literal("Root")),
                    new Node("internal", Optional.of("root"), literal("Internal")),
                    new Node("leaf", Optional.of("internal"), literal("Leaf " + value)));
        }
        public List<Node> nodes(String value) { nodeCalls++; return tree(value); }
        public List<Node> sceneNodes(String value) {
            sceneCalls++; return List.of(tree(value).get(0), new Node("leaf", Optional.of("root"), literal("Leaf " + value)));
        }
        public List<StudioDocument.Section> studioSections(String value, String node) {
            sectionCalls++; if (!node.equals("root")) return List.of();
            var target = value.equals("invalid-section") ? new Node("missing", Optional.empty(), LABEL) : tree(value).get(1);
            return List.of(new StudioDocument.Section("module", target, LABEL, List.of(tree(value).get(2)), List.of(), false));
        }
        public List<ReferenceList> referenceLists(String value, String node) {
            referenceCalls++; if (!node.equals("root")) return List.of();
            var reference = new ReferenceList("modules", LABEL, literal(""), List.of(new Choice("internal", LABEL)), List.of("internal"));
            return value.equals("invalid-references") ? List.of(reference, reference) : List.of(reference);
        }
        public List<Field> fields(String value, String node) {
            fieldCalls++; var field = new Field("value", LABEL, Kind.TEXT, value, literal(""));
            return rejectInternalFields && node.equals("internal") ? List.of(field, field) : List.of(field);
        }
        public String apply(String value, String node, String property, String raw) { return raw; }
    }
    private static final class NoteType implements DocumentType<String> {
        public Class<String> valueType() { return String.class; }
        public EditorId codecId() { return EditorId.parse("test:inspector_utf8"); }
        public int schemaVersion() { return 1; }
        public Set<Integer> readableSchemas() { return Set.of(1); }
        public String create() { return "base"; }
        public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        public String snapshot(String value) { return value; }
        public List<Problem> validate(String value) { return List.of(); }
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public DocumentDependencies dependencies(String value) { return new DocumentDependencies(Set.of(), Set.of(), Set.of()); }
    }
    private static ExtensionRegistry registry(CountingEditor editor) {
        var api = new ApiVersion(1, 0); var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.define(ResourceProducers.KIND); builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE), OWNER,
                Contribution.Side.COMMON, Set.of()), new NoteType());
        DocumentEditors.register(builder, TYPE, OWNER, editor); return builder.freeze();
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable action) { tasks.addLast(action); }
    }
    private static final class Harness implements AutoCloseable {
        final Queue io = new Queue(), completion = new Queue(); final CountingEditor editor = new CountingEditor();
        final ProjectWorkbench model = new ProjectWorkbench(registry(editor), io, completion);
        final WorkbenchListProjection lists = new WorkbenchListProjection(model, text -> ((TextContent.Literal) text).text());
        WorkbenchInspectorProjection projection() { return new WorkbenchInspectorProjection(model, lists); }
        void create(Path path) { model.create(path, "Inspector"); drain(); }
        UUID document(String name) { model.createDocument(TYPE, name); return model.activeDocument().orElseThrow(); }
        void edit(UUID document, String value) {
            model.selectDocument(document); var address = new ProjectWorkbench.Address(document, "root", "value");
            model.input(address, value); model.apply(address);
        }
        void drain() {
            for (int i = 0; !io.tasks.isEmpty() || !completion.tasks.isEmpty(); i++) {
                assertTrue(i < 32, "asynchronous work failed to settle");
                if (!io.tasks.isEmpty()) io.tasks.removeFirst().run();
                if (!completion.tasks.isEmpty()) completion.tasks.removeFirst().run();
            }
            assertFalse(model.busy());
        }
        public void close() { drain(); if (model.hasProject()) { model.closeProject(true); drain(); } }
    }
}
