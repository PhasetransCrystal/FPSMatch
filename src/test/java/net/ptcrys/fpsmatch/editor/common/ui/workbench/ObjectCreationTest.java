package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentRejection;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.session.*;
import net.ptcrys.fpsmatch.editor.core.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectCreationTest {
    @TempDir Path temporary;
    private static final EditorId OWNER = id("test:creation"), TYPE = id("test:creation_note"), CREATOR = id("test:child");
    private static final TextContent LABEL = new TextContent.Literal("Note child");

    @Test void capturedParentAndIdentitySurviveRetryUndoRedoSaveAndActualDiskReopen() throws Exception {
        var creator = new NoteCreator();
        var registry = registry(creator);
        Path path = temporary.resolve("roundtrip");
        UUID document;
        UUID child;
        String created;
        try (var h = new Harness(registry)) {
            h.create(path);
            document = h.model.activeDocument().orElseThrow();
            h.model.selectNode("root");
            var choice = h.model.creationChoices().get(0);
            assertEquals(CREATOR, choice.id()); assertEquals(OWNER, choice.owner());
            assertEquals(Optional.of("root"), choice.target().parent());
            assertEquals(LABEL, choice.label()); assertEquals(new TextContent.Literal("Objects"), choice.category());
            var draft = h.model.beginObjectCreation(CREATOR);
            child = draft.identity();
            assertEquals(document, draft.document()); assertEquals("root", draft.parent());
            assertEquals(List.of("name"), draft.fields().stream().map(DocumentEditor.Field::property).toList());
            var before = h.handle(document).view();
            assertEquals(before.token(), draft.expected());
            assertFalse(before.canUndo());
            assertEquals(2, h.model.nodes().size(), "opening the form allocates no persistent node");
            h.model.selectNode("other");
            assertThrows(IllegalArgumentException.class, () -> h.model.createObject(draft, Map.of("name", "")));
            assertEquals(before, h.handle(document).view());
            assertEquals(List.of(child), creator.identities);
            assertThrows(DocumentRejection.class, () -> h.model.createObject(draft, Map.of("name", "forbidden")));
            assertEquals(before, h.handle(document).view(), "complete candidate validation is atomic");
            h.model.createObject(draft, Map.of("name", "persisted"));
            assertEquals(List.of(child, child, child), creator.identities, "every attempt uses the one captured UUID");
            assertEquals(List.of("root", "root", "root"), creator.parents);
            assertEquals(Optional.of(child.toString()), h.model.selectedNode());
            assertEquals(Optional.of("root"), h.model.nodes().stream().filter(n -> n.id().equals(child.toString())).findFirst().orElseThrow().parent());
            created = h.handle(document).view().content();
            assertTrue(h.model.canUndo());
            h.model.undo();
            assertEquals("new", h.handle(document).view().content());
            assertEquals(Optional.of("root"), h.model.selectedNode());
            assertFalse(h.model.canUndo(), "success is exactly one history entry");
            assertEquals(2, h.model.nodes().size());
            h.model.redo();
            assertEquals(created, h.handle(document).view().content());
            assertTrue(h.model.nodes().stream().anyMatch(n -> n.id().equals(child.toString())));
            assertEquals(3, creator.identities.size(), "redo replays history without creating another identity");
            h.model.saveAll(); h.drain();
            assertFalse(h.model.unsaved());
        }
        try (var h = new Harness(registry)) {
            h.model.open(path); h.drain();
            assertEquals(created, h.handle(document).view().content());
            h.model.selectDocument(document);
            assertTrue(h.model.nodes().stream().anyMatch(n -> n.id().equals(child.toString()) && n.parent().equals(Optional.of("root"))));
            assertFalse(h.model.unsaved());
        }
    }

    @Test void cancelChangesNeitherGraphNorHistoryAndRejectsTheCancelledForm() throws Exception {
        var creator = new NoteCreator();
        try (var h = new Harness(registry(creator))) {
            h.create(temporary.resolve("cancel")); h.model.selectNode("other");
            var before = h.handle(h.model.activeDocument().orElseThrow()).view();
            var draft = h.model.beginObjectCreation(CREATOR);
            h.model.cancelObjectCreation();
            assertEquals(before, h.handle(draft.document()).view());
            assertEquals(Optional.of("other"), h.model.selectedNode());
            assertFalse(h.model.canUndo()); assertFalse(h.model.canRedo());
            assertEquals(2, h.model.nodes().size()); assertTrue(creator.identities.isEmpty());
            assertThrows(SessionRejection.class, () -> h.model.createObject(draft, Map.of("name", "late")));
            assertEquals(before, h.handle(draft.document()).view());
        }
    }

    @Test void staleTokenRejectsBeforeCallingCreatorAndDoesNotOverwriteTheExternalEdit() throws Exception {
        var creator = new NoteCreator();
        try (var h = new Harness(registry(creator))) {
            h.create(temporary.resolve("stale")); h.model.selectNode("root");
            var draft = h.model.beginObjectCreation(CREATOR);
            var handle = h.handle(draft.document());
            handle.edit(handle.view().token(), LABEL, value -> "external");
            var before = handle.view();
            var rejection = assertThrows(DocumentRejection.class, () -> h.model.createObject(draft, Map.of("name", "retained raw")));
            assertEquals(id("editor:document/stale_token"), rejection.problems().get(0).code());
            assertEquals(before, handle.view()); assertTrue(creator.identities.isEmpty());
            assertThrows(DocumentRejection.class, () -> h.model.createObject(draft, Map.of("name", "retained raw")));
            assertEquals(before, handle.view());
        }
    }

    @Test void switchingDocumentAndSelectionCannotRetargetTheCapturedCreation() throws Exception {
        var creator = new NoteCreator();
        try (var h = new Harness(registry(creator))) {
            h.create(temporary.resolve("fixed-target")); h.model.selectNode("root");
            var draft = h.model.beginObjectCreation(CREATOR);
            h.model.createDocument(TYPE, "Second");
            UUID second = h.model.activeDocument().orElseThrow();
            h.model.selectNode("other");
            var secondBefore = h.handle(second).view();
            h.model.createObject(draft, Map.of("name", "first only"));
            assertEquals(secondBefore, h.handle(second).view());
            assertEquals(Optional.of(second), h.model.activeDocument());
            assertEquals(Optional.of("other"), h.model.selectedNode());
            assertEquals(Optional.of(draft.identity().toString()), h.model.selectedNode(draft.document()));
            assertEquals(List.of("root"), creator.parents);
            assertTrue(h.handle(draft.document()).view().content().contains(draft.identity().toString()));
            h.model.selectDocument(draft.document());
            h.model.undo(); assertEquals("new", h.handle(draft.document()).view().content());
            h.model.selectDocument(second); assertFalse(h.model.canUndo());
            assertEquals(Optional.of("other"), h.model.selectedNode());
        }
    }

    @Test void pinnedCreationUsesItsDocumentAndParentWhileTheActiveDocumentKeepsItsRawAndHistory() throws Exception {
        try (var h = new Harness(registry(new NoteCreator()))) {
            h.create(temporary.resolve("pinned-creation"));
            UUID a = h.model.activeDocument().orElseThrow(); h.model.selectNode("root");
            h.model.createDocument(TYPE, "Pinned B"); UUID b = h.model.activeDocument().orElseThrow();
            h.model.selectNode("root"); h.model.selectDocument(a);
            var address = new ProjectWorkbench.Address(a, "root", "title");
            h.model.input(address, "unfinished A"); var before = h.handle(a).view();
            assertEquals(Optional.of("other"), h.model.creationChoices(b, "other").get(0).target().parent());
            var cancelled = h.model.beginObjectCreation(b, "other", CREATOR);
            h.model.cancelObjectCreation();
            assertThrows(SessionRejection.class, () -> h.model.createObject(cancelled, Map.of("name", "late")));
            var draft = h.model.beginObjectCreation(b, "other", CREATOR);
            h.model.createObject(draft, Map.of("name", "B child"));
            assertEquals(before, h.handle(a).view());
            assertEquals(Optional.of(a), h.model.activeDocument());
            assertEquals(Optional.of("root"), h.model.selectedNode());
            assertEquals("unfinished A", h.model.raw(address, ""));
            assertEquals(Optional.of(draft.identity().toString()), h.model.selectedNode(b));
            assertEquals(Optional.of("other"), h.model.nodes(b).stream()
                    .filter(n -> n.id().equals(draft.identity().toString())).findFirst().orElseThrow().parent());
            UUID missing = UUID.randomUUID();
            assertTrue(h.model.creationChoices(missing, "root").isEmpty());
            assertThrows(SessionRejection.class, () -> h.model.beginObjectCreation(missing, "root", CREATOR));
            assertEquals(before, h.handle(a).view());
            h.model.selectDocument(b); h.model.undo();
            assertEquals("new", h.handle(b).view().content());
            assertEquals(before, h.handle(a).view());
        }
    }

    @Test void pendingFieldsAndIncompleteExtraOrOversizedFormsAreRejectedBeforeCreation() throws Exception {
        var creator = new NoteCreator();
        try (var h = new Harness(registry(creator))) {
            h.create(temporary.resolve("invalid-form")); h.model.selectNode("root");
            UUID document = h.model.activeDocument().orElseThrow();
            var address = new ProjectWorkbench.Address(document, "root", "title");
            h.model.input(address, "pending");
            assertThrows(SessionRejection.class, () -> h.model.beginObjectCreation(CREATOR));
            assertTrue(h.model.pending(address));
            h.model.discardInput(address);
            var draft = h.model.beginObjectCreation(CREATOR);
            var before = h.handle(document).view();
            for (Map<String,String> raw : List.of(Map.<String,String>of(), Map.of("name", "ok", "extra", "x"), Map.of("name", "x".repeat(4097)))) {
                assertThrows(SessionRejection.class, () -> h.model.createObject(draft, raw));
                assertEquals(before, h.handle(document).view());
            }
            h.model.input(address, "later pending");
            assertThrows(SessionRejection.class, () -> h.model.createObject(draft, Map.of("name", "ok")));
            assertTrue(creator.identities.isEmpty());
            assertEquals("later pending", h.model.raw(address, "fallback"));
            h.model.discardInput(address);
            h.model.createObject(draft, Map.of("name", "ok"));
            assertEquals(List.of(draft.identity()), creator.identities);
        }
    }

    @Test void creationChoicesRejectForgedTokensBeforeCallingProviderAndKeepCapturedParentOnRetry() throws Exception {
        var creator=new NoteCreator(true);
        try(var h=new Harness(registry(creator))) {
            h.create(temporary.resolve("creation-choices"));h.model.selectNode("root");
            UUID document=h.model.activeDocument().orElseThrow();var draft=h.model.beginObjectCreation(CREATOR);
            assertEquals(DocumentEditor.Kind.CHOICE,draft.fields().get(0).kind());
            assertEquals(List.of("blue","green"),draft.fields().get(0).choices().stream().map(DocumentEditor.Choice::raw).toList());
            var before=h.handle(document).view();h.model.selectNode("other");
            var rejected=assertThrows(SessionRejection.class,()->h.model.createObject(draft,Map.of("name","forged")));
            assertEquals(id("editor:workbench/invalid_choice"),rejected.problems().get(0).code());
            assertEquals(before,h.handle(document).view());assertTrue(creator.identities.isEmpty());
            h.model.createObject(draft,Map.of("name","green"));
            assertEquals(List.of("root"),creator.parents);assertEquals(Optional.of(draft.identity().toString()),h.model.selectedNode());
            String created=h.handle(document).view().content();h.model.undo();assertEquals(before.content(),h.handle(document).view().content());
            h.model.redo();assertEquals(created,h.handle(document).view().content());
        }
    }

    private static ExtensionRegistry registry(NoteCreator creator) {
        var api = new ApiVersion(1, 0);
        var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.define(ResourceProducers.KIND); builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE), OWNER,
                Contribution.Side.COMMON, Set.of()), new NoteType());
        DocumentEditors.register(builder, TYPE, OWNER, new NoteEditor());
        DocumentEditors.registerCreator(builder, CREATOR, OWNER, creator);
        return builder.freeze();
    }

    private static final class NoteType implements DocumentType<String> {
        public Class<String> valueType() { return String.class; }
        public EditorId codecId() { return id("test:creation_utf8"); }
        public int schemaVersion() { return 1; }
        public Set<Integer> readableSchemas() { return Set.of(1); }
        public String create() { return "new"; }
        public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        public String snapshot(String value) { return value; }
        public List<Problem> validate(String value) {
            return value.contains("|forbidden") ? List.of(new Problem(Problem.Severity.ERROR, id("test:forbidden"), "name", LABEL)) : List.of();
        }
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public DocumentDependencies dependencies(String value) { return new DocumentDependencies(Set.of(), Set.of(), Set.of()); }
    }

    private static final class NoteEditor implements DocumentEditor<String> {
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        public List<Node> nodes(String value) {
            List<Node> nodes = new ArrayList<>();
            nodes.add(new Node("root", Optional.empty(), LABEL));
            nodes.add(new Node("other", Optional.of("root"), new TextContent.Literal("Other parent")));
            String[] lines = value.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].split("\\|", 3);
                nodes.add(new Node(parts[1], Optional.of(parts[0]), new TextContent.Literal(parts[2])));
            }
            return List.copyOf(nodes);
        }
        public List<Field> fields(String value, String node) {
            return List.of(new Field("title", LABEL, Kind.TEXT, value.split("\n", 2)[0], LABEL));
        }
        public String apply(String value, String node, String property, String raw) {
            String[] parts = value.split("\n", 2);
            return raw + (parts.length == 2 ? "\n" + parts[1] : "");
        }
    }

    private static final class NoteCreator implements ObjectCreator<String> {
        final boolean choices;
        NoteCreator() {this(false);}
        NoteCreator(boolean choices) {this.choices=choices;}
        final List<UUID> identities = new ArrayList<>();
        final List<String> parents = new ArrayList<>();
        public EditorId documentType() { return TYPE; }
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        public TextContent category() { return new TextContent.Literal("Objects"); }
        public Target target(String value, Optional<String> selection) { return Target.available(selection.orElse("root")); }
        public List<DocumentEditor.Field> fields(String value, String parent) {
            if(choices)return List.of(new DocumentEditor.Field("name",LABEL,DocumentEditor.Kind.CHOICE,"blue",LABEL,
                    List.of(new DocumentEditor.Choice("blue",LABEL),new DocumentEditor.Choice("green",LABEL))));
            return List.of(new DocumentEditor.Field("name", LABEL, DocumentEditor.Kind.TEXT, "", LABEL));
        }
        public String create(String value, String parent, UUID identity, Map<String,String> raw) {
            identities.add(identity); parents.add(parent);
            String name = raw.get("name");
            if (name.isBlank() || name.contains("\n") || name.contains("|")) throw new IllegalArgumentException("invalid child name");
            return value + "\n" + parent + "|" + identity + "|" + name;
        }
    }

    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.addLast(task); }
        void next() { assertFalse(tasks.isEmpty()); tasks.removeFirst().run(); }
    }
    private static final class Harness implements AutoCloseable {
        final ManualExecutor io = new ManualExecutor(), completion = new ManualExecutor();
        final ProjectWorkbench model;
        Harness(ExtensionRegistry registry) { model = new ProjectWorkbench(registry, io, completion); }
        void create(Path path) {
            model.create(path, "Creation project"); drain(); model.createDocument(TYPE, "First");
        }
        @SuppressWarnings("unchecked") DocumentHandle<String> handle(UUID document) {
            var handle = model.documents().get(document); assertEquals(String.class, handle.valueType());
            return (DocumentHandle<String>) handle;
        }
        void drain() {
            for (int count = 0; !io.tasks.isEmpty() || !completion.tasks.isEmpty(); count++) {
                assertTrue(count < 32, "unexpected asynchronous task loop");
                if (!io.tasks.isEmpty()) io.next();
                if (!completion.tasks.isEmpty()) completion.next();
            }
            assertFalse(model.busy());
            assertTrue(model.technicalFailure().isEmpty(), () -> model.technicalFailure().toString());
        }
        public void close() {
            drain(); if (model.hasProject()) { model.closeProject(true); drain(); }
        }
    }
    private static EditorId id(String value) { return EditorId.parse(value); }
}
