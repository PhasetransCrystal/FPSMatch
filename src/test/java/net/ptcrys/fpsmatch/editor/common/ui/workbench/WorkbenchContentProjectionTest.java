package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.ptcrys.fpsmatch.editor.core.editing.ObjectCreator;
import net.ptcrys.fpsmatch.editor.core.editing.StudioDocument;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchContentProjectionTest {
    @TempDir Path temporary;
    private static final EditorId OWNER = EditorId.parse("test:content_projection");
    private static final EditorId TYPE = EditorId.parse("test:content_document");
    private static final EditorId CREATOR = EditorId.parse("test:content_creator");
    private static final TextContent LABEL = literal("Document");

    @Test void warmLookupsCachePositiveAndEmptyResultsWithFullHiddenPaths() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("warm")); UUID document = h.document("Alpha"); var p = h.projection();
            var grid = p.grid(document, "root"); var emptyGrid = p.grid(document, "plain");
            var grids = p.grids(document); var node = p.node(document, "leaf").orElseThrow();
            var path = p.path(document, "leaf"); var missingPath = p.path(document, "missing");
            var choices = p.creationChoices(document, "root"); var noSelection = p.creationChoices(document, null);
            var modules = new ProjectWorkbench.Address(document, "root", "modules");
            var other = new ProjectWorkbench.Address(document, "root", "other");
            var missing = new ProjectWorkbench.Address(document, "root", "missing");
            var references = p.creationChoicesForReference(modules);
            var unsupported = p.creationChoicesForReference(other); var absent = p.creationChoicesForReference(missing);
            assertEquals(List.of("root", "internal", "leaf"), path.stream().map(DocumentEditor.Node::id).toList());
            assertSame(node, path.get(2)); assertEquals(literal("Leaf base"), node.label());
            assertEquals(List.of("root"), grids.stream().map(StudioDocument.Grid::owner).toList());
            assertTrue(emptyGrid.isEmpty()); assertTrue(missingPath.isEmpty());
            assertTrue(choices.get(0).target().parent().isPresent()); assertTrue(noSelection.get(0).target().unavailable().isPresent());
            assertEquals(1, references.size()); assertTrue(unsupported.isEmpty()); assertTrue(absent.isEmpty());
            int calls = h.calls(); var stats = p.stats();
            for (int frame = 0; frame < 100; frame++) {
                assertSame(grid, p.grid(document, "root")); assertSame(emptyGrid, p.grid(document, "plain"));
                assertSame(grids, p.grids(document)); assertSame(node, p.node(document, "leaf").orElseThrow());
                assertSame(path, p.path(document, "leaf")); assertSame(missingPath, p.path(document, "missing"));
                assertTrue(p.node(document, "missing").isEmpty());
                assertSame(choices, p.creationChoices(document, "root")); assertSame(noSelection, p.creationChoices(document, null));
                assertSame(references, p.creationChoicesForReference(modules));
                assertSame(unsupported, p.creationChoicesForReference(other)); assertSame(absent, p.creationChoicesForReference(missing));
            }
            assertEquals(calls, h.calls()); assertEquals(stats, p.stats());
            assertThrows(UnsupportedOperationException.class, () -> path.clear());
            assertThrows(UnsupportedOperationException.class, () -> grids.clear());
            assertThrows(UnsupportedOperationException.class, () -> choices.clear());
        }
    }

    @Test void wholeGridDiscoverySurvivesTinyQueryBudgetAndCachesAnEmptyDocument() {
        try (var h = new Harness()) {
            h.editor.extraOwners = 12; h.create(temporary.resolve("grids")); UUID document = h.document("Alpha");
            var p = new WorkbenchContentProjection(h.model, h.lists, new WorkbenchContentProjection.Limits(2, 1));
            var grids = p.grids(document); assertEquals(13, grids.size());
            int sceneCalls = h.editor.sceneCalls;
            for (var grid : grids) {
                p.grid(document, grid.owner()); p.path(document, grid.owner()); p.creationChoices(document, grid.owner());
                int gridCalls = h.editor.gridCalls;
                assertSame(grids, p.grids(document)); assertEquals(gridCalls, h.editor.gridCalls);
                assertEquals(sceneCalls, h.editor.sceneCalls); assertEquals(1, p.stats().cachedQueries());
            }
            h.edit(document, "no-grids"); var empty = p.grids(document); assertTrue(empty.isEmpty());
            int calls = h.calls();
            for (int frame = 0; frame < 100; frame++) assertSame(empty, p.grids(document));
            assertEquals(calls, h.calls()); assertEquals(2, p.stats().gridsLoads());
        }
    }

    @Test void documentsWithoutMatchingCreatorsRetainAnEmptyChoiceResult() {
        try (var h = new Harness(false)) {
            h.create(temporary.resolve("no-creators")); UUID document = h.document("Alpha"); var p = h.projection();
            var empty = p.creationChoices(document, null); assertTrue(empty.isEmpty());
            for (int frame = 0; frame < 100; frame++) assertSame(empty, p.creationChoices(document, null));
            assertEquals(1, p.stats().choiceLoads()); assertEquals(1, p.stats().cachedDocuments());
            assertEquals(1, p.stats().cachedQueries()); assertEquals(0, h.calls());
        }
    }

    @Test void selectionAndReferenceAddressesAreIndependentAndOtherDocumentEditsStayLocal() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("isolation")); UUID alpha = h.document("Alpha"), beta = h.document("Beta"); var p = h.projection();
            var a = p.creationChoices(alpha, "root"); var b = p.creationChoices(beta, "root");
            var leaf = p.creationChoices(alpha, "leaf"); var none = p.creationChoices(alpha, null);
            var aGrid = p.grid(alpha, "root"); var bGrid = p.grid(beta, "root");
            assertEquals(Optional.of("leaf"), leaf.get(0).target().parent()); assertTrue(none.get(0).target().parent().isEmpty());
            assertEquals(1, p.creationChoicesForReference(new ProjectWorkbench.Address(alpha, "root", "modules")).size());
            assertTrue(p.creationChoicesForReference(new ProjectWorkbench.Address(alpha, "leaf", "modules")).isEmpty());
            h.edit(alpha, "updated");
            assertNotSame(a, p.creationChoices(alpha, "root")); assertNotSame(aGrid, p.grid(alpha, "root"));
            assertSame(b, p.creationChoices(beta, "root")); assertSame(bGrid, p.grid(beta, "root"));
        }
    }

    @Test void rawAndFailedInputDoNotInvalidateAppliedMetadataOrAuthorizeCreation() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("raw")); UUID document = h.document("Alpha"); var p = h.projection();
            var grid = p.grid(document, "root"); var choices = p.creationChoices(document, "root"); var path = p.path(document, "leaf");
            var address = new ProjectWorkbench.Address(document, "root", "value"); var token = h.handle(document).view().token();
            h.model.input(address, "invalid-input"); assertThrows(RuntimeException.class, () -> h.model.apply(address));
            assertEquals(token, h.handle(document).view().token()); assertTrue(h.model.pending(address));
            int calls = h.calls();
            assertSame(grid, p.grid(document, "root")); assertSame(choices, p.creationChoices(document, "root"));
            assertSame(path, p.path(document, "leaf")); assertEquals(calls, h.calls());
            assertEquals("invalid-input", h.model.raw(address, "fallback"));
            assertThrows(SessionRejection.class, () -> h.model.beginObjectCreation(document, "root", CREATOR));
            h.model.discardInput(address); h.creator.available = false; int targets = h.creator.targetCalls;
            assertSame(choices, p.creationChoices(document, "root"));
            assertThrows(SessionRejection.class, () -> h.model.beginObjectCreation(document, "root", CREATOR));
            assertEquals(targets + 1, h.creator.targetCalls, "Action-time target validation must bypass the projection");
        }
    }

    @Test void fullBranchTokenUndoRedoAndDirectHandleChangesInvalidateAllMetadata() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("tokens")); UUID document = h.document("Alpha"); var p = h.projection();
            var initial = p.grid(document, "root"); var choices = p.creationChoices(document, "root");
            var token = h.handle(document).view().token(); h.model.discardDocumentChanges(document);
            assertEquals(token.revision(), h.handle(document).view().token().revision());
            assertNotEquals(token.branchId(), h.handle(document).view().token().branchId());
            assertNotSame(initial, p.grid(document, "root")); assertNotSame(choices, p.creationChoices(document, "root"));
            h.edit(document, "changed"); assertEquals(literal("Grid changed"), p.grid(document, "root").orElseThrow().label());
            h.model.undo(); assertEquals(literal("Grid base"), p.grid(document, "root").orElseThrow().label());
            h.model.redo(); assertEquals(literal("Grid changed"), p.grid(document, "root").orElseThrow().label());
            var oldPath = p.path(document, "leaf"); long version = h.model.version(); h.directEdit(document, "external");
            assertEquals(version, h.model.version()); assertNotSame(oldPath, p.path(document, "leaf"));
            assertEquals(literal("Leaf external"), p.node(document, "leaf").orElseThrow().label());
            assertEquals(literal("Grid external"), p.grid(document, "root").orElseThrow().label());
            assertEquals(1, p.stats().cachedDocuments());
        }
    }

    @Test void invalidGridDiscoveryNeverPublishesAPartialListAndFailuresRetry() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("invalid-grid")); UUID document = h.document("Alpha"); var p = h.projection();
            h.editor.badGridNode = "leaf";
            assertThrows(SessionRejection.class, () -> p.grids(document)); int calls = h.editor.gridCalls;
            assertThrows(SessionRejection.class, () -> p.grids(document)); assertTrue(h.editor.gridCalls > calls);
            assertEquals(0, p.stats().cachedDocuments()); assertEquals(0, p.stats().gridsLoads());
            assertThrows(SessionRejection.class, () -> p.grid(document, "leaf")); assertEquals(0, p.stats().gridLoads());
            h.editor.badGridNode = null; assertEquals(1, p.grids(document).size());
            assertTrue(p.grid(document, "leaf").isPresent());
            assertThrows(SessionRejection.class, () -> p.grid(document, "missing"));
        }
    }

    @Test void failedChoicesDoNotPoisonRetriesOrUnrelatedCachedResults() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("invalid-choices")); UUID document = h.document("Alpha"); var p = h.projection();
            var grid = p.grid(document, "root"); h.creator.rejectTarget = true;
            assertThrows(IllegalArgumentException.class, () -> p.creationChoices(document, "root"));
            int calls = h.creator.targetCalls;
            assertThrows(IllegalArgumentException.class, () -> p.creationChoices(document, "root"));
            assertEquals(calls + 1, h.creator.targetCalls); assertEquals(0, p.stats().choiceLoads());
            h.creator.rejectTarget = false; assertEquals(1, p.creationChoices(document, "root").size());
            var address = new ProjectWorkbench.Address(document, "root", "modules"); h.creator.rejectReference = true;
            assertThrows(IllegalArgumentException.class, () -> p.creationChoicesForReference(address)); calls = h.creator.referenceCalls;
            assertThrows(IllegalArgumentException.class, () -> p.creationChoicesForReference(address));
            assertEquals(calls + 1, h.creator.referenceCalls); assertEquals(0, p.stats().referenceChoiceLoads());
            h.creator.rejectReference = false; assertEquals(1, p.creationChoicesForReference(address).size());
            assertSame(grid, p.grid(document, "root"));
        }
    }

    @Test void tokenChangesDuringAnyColdLoaderRejectTheCandidateWithoutInstallingIt() {
        for (String kind : List.of("grid", "grids", "choices", "references", "node", "path")) {
            try (var h = new Harness()) {
                h.create(temporary.resolve("during-" + kind)); UUID document = h.document("Alpha"); var p = h.projection();
                Runnable mutate = () -> h.directEdit(document, "during-read");
                Runnable lookup = switch (kind) {
                    case "grid" -> { h.editor.gridHook = mutate; yield () -> p.grid(document, "root"); }
                    case "grids" -> { h.editor.gridHook = mutate; yield () -> p.grids(document); }
                    case "choices" -> { h.creator.targetHook = mutate; yield () -> p.creationChoices(document, "root"); }
                    case "references" -> { h.creator.targetHook = mutate; yield () -> p.creationChoicesForReference(new ProjectWorkbench.Address(document, "root", "modules")); }
                    case "node" -> { h.editor.nodeHook = mutate; yield () -> p.node(document, "leaf"); }
                    default -> { h.editor.nodeHook = mutate; yield () -> p.path(document, "leaf"); }
                };
                assertThrows(IllegalStateException.class, lookup::run, kind);
                assertEquals(0, p.stats().cachedDocuments(), kind); assertEquals(0, p.stats().cachedQueries(), kind);
                assertEquals(0, p.stats().nodeLoads(), kind);
                assertDoesNotThrow(lookup::run); assertEquals(1, p.stats().cachedDocuments(), kind);
            }
        }
    }

    @Test void documentAndQueryBudgetsRetainAndClearReleaseOwnersIncludingEmptyResults() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("budgets")); UUID a = h.document("A"), b = h.document("B"), c = h.document("C");
            var p = new WorkbenchContentProjection(h.model, h.lists, new WorkbenchContentProjection.Limits(2, 2));
            var aGrid = p.grid(a, "root"); p.grid(b, "root"); assertSame(aGrid, p.grid(a, "root")); p.grid(c, "root");
            assertEquals(2, p.stats().cachedDocuments()); assertEquals(2, p.stats().cachedQueries());
            int calls = h.editor.gridCalls; p.grid(b, "root"); assertEquals(calls + 1, h.editor.gridCalls);
            p.grid(b, "plain"); p.path(b, "missing"); assertEquals(2, p.stats().cachedQueries());
            p.retainDocuments(Set.of(b)); assertEquals(1, p.stats().cachedDocuments());
            assertEquals(2, p.stats().cachedQueries());
            p.retainDocuments(Set.of()); assertEquals(0, p.stats().cachedDocuments()); assertEquals(0, p.stats().cachedQueries());
            p.grid(a, "plain"); assertEquals(1, p.stats().cachedDocuments()); p.clear();
            assertEquals(0, p.stats().cachedDocuments()); assertEquals(0, p.stats().cachedQueries());
            assertThrows(IllegalArgumentException.class, () -> new WorkbenchContentProjection.Limits(0, 1));
        }
    }

    @Test void closeAndSameIdReopenCannotReuseTheOldHandleOrAbsentDocumentResults() {
        try (var h = new Harness()) {
            Path project = temporary.resolve("reopen"); h.create(project); UUID document = h.document("Alpha");
            h.model.saveAll(); h.drain(); var p = h.projection();
            var grid = p.grid(document, "root"); var choices = p.creationChoices(document, "root"); var oldHandle = h.handle(document);
            h.model.closeProject(true); h.drain(); assertEquals(0, p.stats().cachedDocuments());
            assertTrue(p.grid(document, "root").isEmpty()); assertTrue(p.grids(document).isEmpty());
            assertTrue(p.creationChoices(document, null).isEmpty()); assertTrue(p.node(document, "root").isEmpty());
            assertTrue(p.path(document, "root").isEmpty());
            assertTrue(p.creationChoicesForReference(new ProjectWorkbench.Address(document, "root", "modules")).isEmpty());
            assertEquals(0, p.stats().cachedDocuments()); assertEquals(0, p.stats().cachedQueries());
            h.model.open(project); h.drain(); assertNotSame(oldHandle, h.handle(document));
            assertNotSame(grid, p.grid(document, "root")); assertNotSame(choices, p.creationChoices(document, "root"));
        }
    }

    private static TextContent.Literal literal(String text) { return new TextContent.Literal(text); }
    private static final class CountingEditor implements DocumentEditor<String> {
        int nodeCalls, sceneCalls, gridCalls, referenceCalls, fieldCalls, extraOwners;
        String badGridNode; Runnable gridHook, nodeHook;
        int calls() { return nodeCalls + sceneCalls + gridCalls + referenceCalls + fieldCalls; }
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        private List<Node> tree(String value) {
            var nodes = new ArrayList<>(List.of(new Node("root", Optional.empty(), literal("Root")),
                    new Node("internal", Optional.of("root"), literal("Internal")),
                    new Node("leaf", Optional.of("internal"), literal("Leaf " + value)),
                    new Node("plain", Optional.of("root"), literal("Plain"))));
            for (int i = 0; i < extraOwners; i++) nodes.add(new Node("owner-" + i, Optional.of("root"), literal("Owner " + i)));
            return List.copyOf(nodes);
        }
        public List<Node> nodes(String value) {
            nodeCalls++; if (nodeHook != null) { var hook = nodeHook; nodeHook = null; hook.run(); } return tree(value);
        }
        public List<Node> sceneNodes(String value) {
            sceneCalls++; var nodes = new ArrayList<Node>(); nodes.add(tree(value).get(0));
            nodes.add(new Node("leaf", Optional.of("root"), literal("Leaf " + value)));
            nodes.addAll(tree(value).subList(3, tree(value).size())); return List.copyOf(nodes);
        }
        public Optional<StudioDocument.Grid> studioGrid(String value, String node) {
            gridCalls++; if (gridHook != null) { var hook = gridHook; gridHook = null; hook.run(); }
            if (value.equals("no-grids") || node.equals("plain")) return Optional.empty();
            String owner = node.equals(badGridNode) ? "missing" : node.startsWith("owner-") ? node : "root";
            var column = new StudioDocument.Column("column", LABEL, 1);
            var cell = owner.equals("root")
                    ? new StudioDocument.Cell("column", 0, Optional.of("internal"), Optional.of("leaf"), LABEL, List.of(), Optional.empty())
                    : new StudioDocument.Cell("column", 0, Optional.empty(), Optional.empty(), LABEL, List.of(), Optional.empty());
            return Optional.of(new StudioDocument.Grid(owner, literal("Grid " + value), List.of(column), List.of(cell)));
        }
        public List<ReferenceList> referenceLists(String value, String node) {
            referenceCalls++; if (!node.equals("root")) return List.of();
            return List.of(new ReferenceList("modules", LABEL, literal(""), List.of(new Choice("internal", LABEL)), List.of()),
                    new ReferenceList("other", LABEL, literal(""), List.of(new Choice("internal", LABEL)), List.of()));
        }
        public List<Field> fields(String value, String node) {
            fieldCalls++; return List.of(new Field("value", LABEL, Kind.TEXT, value, literal("")));
        }
        public String apply(String value, String node, String property, String raw) {
            if (raw.equals("invalid-input")) throw new IllegalArgumentException("invalid input"); return raw;
        }
    }
    private static final class CountingCreator implements ObjectCreator<String> {
        int targetCalls, referenceCalls; boolean rejectTarget, rejectReference, available = true; Runnable targetHook;
        public EditorId documentType() { return TYPE; }
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        public TextContent category() { return LABEL; }
        public Target target(String value, Optional<String> selection) {
            targetCalls++; if (targetHook != null) { var hook = targetHook; targetHook = null; hook.run(); }
            if (rejectTarget) throw new IllegalArgumentException("invalid target");
            return available && selection.isPresent() ? Target.available(selection.orElseThrow()) : Target.unavailable(LABEL);
        }
        public boolean supportsReference(String value, String node, String property) {
            referenceCalls++; if (rejectReference) throw new IllegalArgumentException("invalid reference");
            return node.equals("root") && property.equals("modules");
        }
        public List<DocumentEditor.Field> fields(String value, String parent) { return List.of(); }
        public String create(String value, String parent, UUID identity, Map<String, String> raw) { return value; }
    }
    private static final class NoteType implements DocumentType<String> {
        public Class<String> valueType() { return String.class; }
        public EditorId codecId() { return EditorId.parse("test:content_utf8"); }
        public int schemaVersion() { return 1; }
        public Set<Integer> readableSchemas() { return Set.of(1); }
        public String create() { return "base"; }
        public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        public String snapshot(String value) { return value; }
        public List<Problem> validate(String value) { return List.of(); }
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public DocumentDependencies dependencies(String value) { return new DocumentDependencies(Set.of(), Set.of(), Set.of()); }
    }
    private static ExtensionRegistry registry(CountingEditor editor, CountingCreator creator, boolean registerCreator) {
        var api = new ApiVersion(1, 0); var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.define(ResourceProducers.KIND); builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE), OWNER,
                Contribution.Side.COMMON, Set.of()), new NoteType());
        DocumentEditors.register(builder, TYPE, OWNER, editor); builder.define(DocumentEditors.CREATORS);
        if (registerCreator) DocumentEditors.registerCreator(builder, CREATOR, OWNER, creator);
        return builder.freeze();
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable action) { tasks.addLast(action); }
    }
    private static final class Harness implements AutoCloseable {
        final Queue io = new Queue(), completion = new Queue();
        final CountingEditor editor = new CountingEditor(); final CountingCreator creator = new CountingCreator();
        final ProjectWorkbench model;
        final WorkbenchListProjection lists;
        Harness() { this(true); }
        Harness(boolean registerCreator) {
            model = new ProjectWorkbench(registry(editor, creator, registerCreator), io, completion);
            lists = new WorkbenchListProjection(model, text -> ((TextContent.Literal) text).text());
        }
        WorkbenchContentProjection projection() { return new WorkbenchContentProjection(model, lists); }
        int calls() { return editor.calls() + creator.targetCalls + creator.referenceCalls; }
        void create(Path path) { model.create(path, "Content"); drain(); }
        UUID document(String name) { model.createDocument(TYPE, name); return model.activeDocument().orElseThrow(); }
        @SuppressWarnings("unchecked") DocumentHandle<String> handle(UUID document) { return (DocumentHandle<String>) lists.documents().get(document); }
        void directEdit(UUID document, String value) { var handle = handle(document); handle.edit(handle.view().token(), LABEL, old -> value); }
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
