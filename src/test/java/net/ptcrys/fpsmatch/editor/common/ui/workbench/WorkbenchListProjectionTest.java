package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioRowSource;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioRowSource.FocusSlot;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
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

class WorkbenchListProjectionTest {
    @TempDir Path temporary;
    private static final EditorId OWNER = EditorId.parse("test:list_projection");
    private static final EditorId TYPE = EditorId.parse("test:list_document");
    private static final TextContent LABEL = literal("Document");

    @Test void warmQueriesReuseDirectoryValidationSourcesAndStableFocusSlots() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("warm"));
            UUID alpha = h.document("Alpha"), beta = h.document("Beta");
            h.edit(beta, "noforms");
            var projection = new WorkbenchListProjection(h.model, WorkbenchListProjectionTest::label);
            var directory = projection.documents();
            var projects = projection.projects("projects", "", 1);
            var objects = projection.objects("objects", alpha, Set.of(), "", 1);
            var alphaForms = projection.forms(alpha);
            var stats = projection.stats(); int calls = h.editor.calls();
            for (int frame = 0; frame < 100; frame++) {
                assertSame(directory, projection.documents());
                assertSame(projects, projection.projects("projects", "  ", 1));
                assertSame(objects, projection.objects("objects", alpha, new HashSet<>(), "", 1));
                assertSame(alphaForms, projection.forms(alpha));
            }
            assertEquals(stats, projection.stats()); assertEquals(calls, h.editor.calls());
            assertEquals(new ArrayList<>(directory.keySet()).stream().map(UUID::toString).toList(), keys(projects));
            assertEquals(List.of(new FocusSlot("activate", Role.TREE_ITEM), new FocusSlot("forms", Role.BUTTON)),
                    projects.focusSlots(projects.indexOf(alpha.toString())));
            assertEquals(List.of(new FocusSlot("activate", Role.TREE_ITEM)), projects.focusSlots(projects.indexOf(beta.toString())));
            assertEquals(List.of(new FocusSlot("expand", Role.BUTTON), new FocusSlot("activate", Role.TREE_ITEM)),
                    objects.focusSlots(objects.indexOf("root")));
            assertEquals(List.of(new FocusSlot("activate", Role.TREE_ITEM)), objects.focusSlots(objects.indexOf("leaf")));
            assertThrows(UnsupportedOperationException.class, () -> directory.clear());
            assertThrows(UnsupportedOperationException.class, () -> projection.forms(alpha).clear());
        }
    }

    @Test void equalImmutableCollapseReplacementStopsRepeatedValueComparisons() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("equal-collapse")); UUID document = h.document("Alpha");
            var projection = new WorkbenchListProjection(h.model, WorkbenchListProjectionTest::label);
            var firstState = Set.of("branch", "deep");
            var source = projection.objects("objects", document, firstState, "", 1);
            var replacement = Set.copyOf(new HashSet<>(firstState));
            assertNotSame(firstState, replacement); assertEquals(firstState, replacement);
            long comparisons = projection.collapseComparisons();
            assertSame(source, projection.objects("objects", document, replacement, "", 1));
            assertEquals(comparisons + 1, projection.collapseComparisons());
            var stats = projection.stats(); int calls = h.editor.calls();
            for (int frame = 0; frame < 100; frame++)
                assertSame(source, projection.objects("objects", document, replacement, "", 1));
            assertEquals(comparisons + 1, projection.collapseComparisons(),
                    "An equal immutable replacement must not scan the collapse set on later scrolls");
            assertEquals(stats, projection.stats()); assertEquals(calls, h.editor.calls());

            var mutable = new HashSet<>(replacement);
            assertSame(source, projection.objects("objects", document, mutable, "", 1));
            mutable.remove("branch");
            var expanded = projection.objects("objects", document, mutable, "", 1);
            assertNotSame(source, expanded); assertTrue(expanded.revision() > source.revision());
            assertEquals(List.of("root", "branch", "other"), keys(source));
            assertEquals(List.of("root", "branch", "deep", "other"), keys(expanded));
        }
    }

    @Test void unrelatedModelChangesPreserveProjectionWhileAppliedEditsRefreshOnlyChangedDocument() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("versions"));
            UUID alpha = h.document("Alpha"), beta = h.document("Beta");
            var projection = new WorkbenchListProjection(h.model, WorkbenchListProjectionTest::label);
            var original = projection.projects("projects", "", 0);
            var betaForms = projection.forms(beta); long loads = projection.stats().formLoads();
            h.model.selectDocument(alpha);
            assertSame(original, projection.projects("projects", "", 0));
            assertEquals(loads, projection.stats().formLoads());
            h.edit(alpha, "changed");
            var changed = projection.projects("projects", "", 0);
            assertNotSame(original, changed); assertTrue(changed.revision() > original.revision());
            assertEquals(literal("changed"), changed.rowAt(changed.indexOf(alpha.toString())).forms().get(0).label());
            assertSame(betaForms, projection.forms(beta)); assertEquals(loads + 1, projection.stats().formLoads());
            h.model.undo();
            assertEquals(literal("base"), projection.projects("projects", "", 0)
                    .rowAt(changed.indexOf(alpha.toString())).forms().get(0).label());
            assertEquals(loads + 2, projection.stats().formLoads());
        }
    }

    @Test void branchReplacementAtEqualRevisionInvalidatesFormsHierarchyAndObjectSource() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("branches")); UUID document = h.document("Branch");
            var projection = new WorkbenchListProjection(h.model, WorkbenchListProjectionTest::label);
            var handle = projection.documents().get(document); var before = handle.view().token();
            var forms = projection.forms(document); var tree = projection.hierarchy(document);
            var source = projection.objects("objects", document, Set.of(), "", 0);
            h.model.discardDocumentChanges(document);
            var after = handle.view().token();
            assertEquals(before.revision(), after.revision()); assertNotEquals(before.branchId(), after.branchId());
            assertNotSame(forms, projection.forms(document)); assertNotSame(tree, projection.hierarchy(document));
            assertNotSame(source, projection.objects("objects", document, Set.of(), "", 0));
            assertEquals(2, projection.stats().formLoads()); assertEquals(2, projection.stats().hierarchyLoads());
        }
    }

    @Test void collapseFilterLanguageAndViewChangesReuseTheValidatedHierarchy() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("views")); UUID document = h.document("Alpha");
            var language = new AtomicReference<>("English "); var translations = new AtomicInteger();
            var projection = new WorkbenchListProjection(h.model, text -> {
                translations.incrementAndGet(); return language.get() + label(text);
            });
            var defaults = projection.initialCollapsed(document);
            assertEquals(Set.of("deep"), defaults); assertSame(defaults, projection.initialCollapsed(document));
            assertThrows(UnsupportedOperationException.class, () -> defaults.clear());
            assertEquals(List.of("deep", "branch", "root"), projection.hierarchy(document).ancestors("leaf"));
            var collapse = new HashSet<>(defaults);
            var first = projection.objects("a", document, collapse, "", 1);
            assertEquals(List.of("root", "branch", "deep", "other"), keys(first));
            collapse.add("branch");
            var second = projection.objects("a", document, collapse, "", 1);
            assertEquals(List.of("root", "branch", "other"), keys(second));
            assertEquals(List.of("root", "branch", "deep", "other"), keys(first));
            assertSame(second, projection.objects("a", document, Set.of("branch", "deep"), "", 1));
            var filtered = projection.objects("b", document, collapse, " NEEDLE ", 1);
            assertEquals(List.of("root", "branch", "deep", "leaf"), keys(filtered));
            assertTrue(filtered.rowAt(2).expanded());
            int translated = translations.get();
            assertSame(filtered, projection.objects("b", document, collapse, "needle", 1));
            assertEquals(translated, translations.get());
            assertSame(second, projection.objects("a", document, collapse, "", 1));
            assertEquals(0, projection.objects("language", document, collapse, "中文", 1).size());
            language.set("中文 ");
            assertEquals(5, projection.objects("language", document, collapse, "中文", 2).size());
            assertEquals(1, projection.stats().hierarchyLoads()); assertEquals(1, h.editor.sceneCalls);
            var project = projection.projects("projects", " ALP ", 1);
            assertEquals(List.of(document.toString()), keys(project));
            assertSame(project, projection.projects("projects", "alp", 1));
            assertNotSame(project, projection.projects("projects", "alp", 2));
        }
    }

    @Test void failedValidationNeverPublishesPartialOrStaleProjectionAndCanRetry() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("failure")); UUID document = h.document("Failure");
            var projection = new WorkbenchListProjection(h.model, WorkbenchListProjectionTest::label);
            var valid = projection.projects("projects", "", 0);
            h.edit(document, "invalid-form");
            assertThrows(SessionRejection.class, () -> projection.projects("projects", "", 0));
            int firstFailureCalls = h.editor.formCalls;
            assertThrows(SessionRejection.class, () -> projection.projects("projects", "", 0));
            assertEquals(firstFailureCalls + 1, h.editor.formCalls);
            assertEquals(1, projection.stats().formLoads());
            assertEquals(literal("base"), valid.rowAt(0).forms().get(0).label());
            h.edit(document, "recovered");
            assertEquals(literal("recovered"), projection.projects("projects", "", 0).rowAt(0).forms().get(0).label());
            assertEquals(2, projection.stats().formLoads());
        }
    }

    @Test void failedSceneAndTranslationDoNotCachePartialResultsOrReplaceAValidView() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("scene-failure")); UUID document = h.document("Scene");
            var rejectLabels = new java.util.concurrent.atomic.AtomicBoolean();
            var projection = new WorkbenchListProjection(h.model, text -> {
                if (rejectLabels.get()) throw new IllegalStateException("translation failed");
                return label(text);
            });
            var first = projection.objects("objects", document, Set.of(), "", 0);
            h.edit(document, "invalid-scene");
            assertThrows(SessionRejection.class, () -> projection.objects("objects", document, Set.of(), "", 0));
            int failures = h.editor.sceneCalls;
            assertThrows(SessionRejection.class, () -> projection.objects("objects", document, Set.of(), "", 0));
            assertEquals(failures + 1, h.editor.sceneCalls);
            assertEquals(1, projection.stats().hierarchyLoads());
            assertEquals(List.of("root", "branch", "deep", "leaf", "other"), keys(first));
            h.edit(document, "recovered");
            var recovered = projection.objects("objects", document, Set.of(), "", 0);
            rejectLabels.set(true);
            assertThrows(IllegalStateException.class, () -> projection.objects("objects", document, Set.of(), "needle", 0));
            assertSame(recovered, projection.objects("objects", document, Set.of(), "", 0));
            rejectLabels.set(false);
            assertEquals(List.of("root", "branch", "deep", "leaf"),
                    keys(projection.objects("objects", document, Set.of(), "needle", 0)));
            assertEquals(2, projection.stats().hierarchyLoads());
        }
    }

    @Test void retainClearCloseAndSameIdReopenReleaseCachedOwnersWithoutRecyclingRevision() {
        try (var h = new Harness()) {
            Path path = temporary.resolve("owners"); h.create(path); UUID document = h.document("Owner");
            h.model.saveAll(); h.drain();
            var projection = new WorkbenchListProjection(h.model, WorkbenchListProjectionTest::label);
            var oldHandle = projection.documents().get(document);
            var kept = projection.projects("keep", "", 0); var dropped = projection.projects("drop", "", 0);
            projection.objects("keep", document, Set.of(), "", 0); projection.objects("drop", document, Set.of(), "", 0);
            projection.retainViews(Set.of("keep"));
            assertEquals(1, projection.stats().projectViews()); assertEquals(1, projection.stats().objectViews());
            assertSame(kept, projection.projects("keep", "", 0));
            assertTrue(projection.projects("drop", "", 0).revision() > dropped.revision());
            h.model.closeProject(true); h.drain(); h.model.open(path); h.drain();
            assertNotSame(oldHandle, projection.documents().get(document));
            assertEquals(0, projection.stats().cachedDocuments()); assertEquals(0, projection.stats().objectViews());
            assertEquals(0, projection.stats().projectViews());
            var reopened = projection.projects("keep", "", 0);
            assertNotSame(kept, reopened); assertTrue(reopened.revision() > kept.revision());
            assertEquals(document, kept.rowAt(0).id(), "Published value rows survive their former handle closing");
            projection.clear();
            assertEquals(0, projection.stats().cachedDocuments()); assertEquals(0, projection.stats().projectViews());
            assertEquals(0, projection.stats().objectViews());
            assertTrue(projection.projects("keep", "", 0).revision() > reopened.revision());
            h.model.closeProject(true); h.drain();
            assertTrue(projection.documents().isEmpty()); assertTrue(projection.forms(document).isEmpty());
            assertEquals(0, projection.projects("empty", "", 0).size());
            assertEquals(0, projection.objects("empty", document, Set.of(), "", 0).size());
            assertEquals(0, projection.stats().cachedDocuments());
        }
    }

    private static List<String> keys(StudioRowSource<?> source) {
        var result = new ArrayList<String>();
        for (int i = 0; i < source.size(); i++) result.add(source.keyAt(i));
        return List.copyOf(result);
    }
    private static TextContent.Literal literal(String text) { return new TextContent.Literal(text); }
    private static String label(TextContent text) { return ((TextContent.Literal) text).text(); }

    private static final class CountingEditor implements DocumentEditor<String> {
        int nodeCalls, sceneCalls, formCalls, fieldCalls;
        int calls() { return nodeCalls + sceneCalls + formCalls + fieldCalls; }
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        private List<Node> tree(String value) {
            return List.of(new Node("root", Optional.empty(), literal("Root")),
                    new Node("branch", Optional.of("root"), literal("Branch")),
                    new Node("deep", Optional.of("branch"), literal("Deep")),
                    new Node("leaf", Optional.of("deep"), literal("Needle " + value)),
                    new Node("other", Optional.empty(), literal("Other")));
        }
        public List<Node> nodes(String value) { nodeCalls++; return tree(value); }
        public List<Node> sceneNodes(String value) {
            sceneCalls++;
            return value.equals("invalid-scene") ? List.of(new Node("root", Optional.empty(), literal("mismatch"))) : tree(value);
        }
        public List<StudioDocument.Form> studioForms(String value) {
            formCalls++;
            return value.equals("noforms") ? List.of() : List.of(new StudioDocument.Form("main", literal(value),
                    value.equals("invalid-form") ? "absent" : "root", List.of("overview")));
        }
        public List<Field> fields(String value, String node) {
            fieldCalls++;
            return List.of(new Field("value", LABEL, Kind.TEXT, value, literal("")).presented(
                    new Presentation("overview", LABEL, "main", LABEL, "value", LABEL, Accent.NONE)));
        }
        public String apply(String value, String node, String property, String raw) { return raw; }
    }

    private static final class NoteType implements DocumentType<String> {
        public Class<String> valueType() { return String.class; }
        public EditorId codecId() { return EditorId.parse("test:list_utf8"); }
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
        DocumentEditors.register(builder, TYPE, OWNER, editor);
        return builder.freeze();
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable action) { tasks.addLast(action); }
    }
    private static final class Harness implements AutoCloseable {
        final Queue io = new Queue(), completion = new Queue();
        final CountingEditor editor = new CountingEditor();
        final ProjectWorkbench model = new ProjectWorkbench(registry(editor), io, completion);
        void create(Path path) { model.create(path, "Projection"); drain(); }
        UUID document(String name) { model.createDocument(TYPE, name); return model.activeDocument().orElseThrow(); }
        void edit(UUID document, String value) {
            model.selectDocument(document);
            var address = new ProjectWorkbench.Address(document, "root", "value"); model.input(address, value); model.apply(address);
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
