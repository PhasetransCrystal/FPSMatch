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

class ObjectRemovalTest {
    @TempDir Path temporary;
    private static final EditorId TYPE=EditorId.parse("test:removal");
    private static final TextContent LABEL=new TextContent.Literal("Removal");
    private static final String INITIAL="title\nroot|branch\nbranch|leaf\nroot|other";

    @Test void capturedDocumentAndSubtreeDeleteOnceUndoRedoAndActualDiskReopen() throws Exception {
        var editor=new TreeEditor();var registry=registry(editor);Path path=temporary.resolve("persist");UUID first;
        String removed="title\nroot|other";
        try(var h=new Harness(registry)) {
            h.create(path);first=h.model.activeDocument().orElseThrow();h.model.selectNode("branch");
            var draft=h.model.beginRemoval();assertEquals(List.of("branch","leaf"),draft.plan().objects().stream().map(DocumentEditor.Node::id).toList());
            var before=h.handle(first).view();assertFalse(before.canUndo());
            h.model.createDocument(TYPE,"Second");UUID second=h.model.activeDocument().orElseThrow();h.model.selectNode("other");
            var untouched=h.handle(second).view();h.model.removeObject(draft);
            assertEquals(removed,h.handle(first).view().content());assertEquals(untouched,h.handle(second).view());
            assertEquals(Optional.of(second),h.model.activeDocument());assertEquals(Optional.of("other"),h.model.selectedNode());
            assertEquals(Optional.of("root"),h.model.selectedNode(first));
            assertEquals(before.token().revision()+1,h.handle(first).view().token().revision());assertEquals(1,editor.removes);
            h.model.selectDocument(first);h.model.undo();assertEquals(INITIAL,h.handle(first).view().content());assertFalse(h.model.canUndo());
            h.model.redo();assertEquals(removed,h.handle(first).view().content());assertEquals(1,editor.removes);
            h.model.saveAll();h.drain();assertFalse(h.model.unsaved());
        }
        try(var h=new Harness(registry)) {
            h.model.open(path);h.drain();assertEquals(removed,h.handle(first).view().content());
            h.model.selectDocument(first);assertFalse(h.model.canUndo());assertFalse(h.model.unsaved());
        }
    }

    @Test void pinnedRemovalCapturesItsSubtreeAndPreservesTheActiveDocumentRawSelectionAndHistory() throws Exception {
        try (var h = new Harness(registry(new TreeEditor()))) {
            h.create(temporary.resolve("pinned-removal")); UUID a = h.model.activeDocument().orElseThrow();
            h.model.selectNode("other"); h.model.createDocument(TYPE, "Pinned B");
            UUID b = h.model.activeDocument().orElseThrow(); h.model.selectNode("leaf");
            h.model.selectDocument(a);
            var address = new ProjectWorkbench.Address(a, "root", "title");
            h.model.input(address, "unfinished A"); var before = h.handle(a).view();
            var cancelled = h.model.beginRemoval(b, "branch"); h.model.cancelRemoval();
            assertThrows(SessionRejection.class, () -> h.model.removeObject(cancelled));
            assertEquals(INITIAL, h.handle(b).view().content());
            var draft = h.model.beginRemoval(b, "branch");
            assertEquals(List.of("branch", "leaf"), draft.plan().objects().stream().map(DocumentEditor.Node::id).toList());
            h.model.removeObject(draft);
            assertEquals("title\nroot|other", h.handle(b).view().content());
            assertEquals(Optional.of("root"), h.model.selectedNode(b));
            assertEquals(Optional.of(a), h.model.activeDocument());
            assertEquals(Optional.of("other"), h.model.selectedNode());
            assertEquals(before, h.handle(a).view()); assertEquals("unfinished A", h.model.raw(address, ""));
            assertThrows(SessionRejection.class, () -> h.model.beginRemoval(UUID.randomUUID(), "branch"));
            assertEquals(before, h.handle(a).view());
            h.model.selectDocument(b); h.model.undo(); assertEquals(INITIAL, h.handle(b).view().content());
            assertEquals(before, h.handle(a).view());
        }
    }

    @Test void cancellationPendingAndExactStaleTokenPreserveGraphRawAndPreview() throws Exception {
        var editor=new TreeEditor();
        try(var h=new Harness(registry(editor))) {
            h.create(temporary.resolve("reject"));UUID document=h.model.activeDocument().orElseThrow();h.model.selectNode("branch");
            var before=h.handle(document).view();var cancelled=h.model.beginRemoval();h.model.cancelRemoval();
            assertThrows(SessionRejection.class,()->h.model.removeObject(cancelled));assertEquals(before,h.handle(document).view());
            var address=new ProjectWorkbench.Address(document,"root","title");h.model.input(address,"pending");
            assertThrows(SessionRejection.class,h.model::beginRemoval);assertEquals("pending",h.model.raw(address,""));h.model.discardInput(address);
            var draft=h.model.beginRemoval();h.model.input(address,"changed");
            assertThrows(SessionRejection.class,()->h.model.removeObject(draft));assertEquals(INITIAL,h.handle(document).view().content());
            assertEquals("changed",h.model.raw(address,""));h.model.apply(address);
            var changed=h.handle(document).view();assertThrows(DocumentRejection.class,()->h.model.removeObject(draft));
            assertEquals(changed,h.handle(document).view());assertEquals(0,editor.removes);
            var fresh=h.model.beginRemoval();editor.blocked=true;
            assertThrows(SessionRejection.class,()->h.model.removeObject(fresh));assertEquals(changed,h.handle(document).view());
            editor.blocked=false;h.model.removeObject(fresh);assertEquals("changed\nroot|other",h.handle(document).view().content());
        }
    }

    @Test void missingCapabilityFixedStructuresAndDeclaredReferencesNeverInvokeRemoval() throws Exception {
        var editor=new TreeEditor();
        try(var h=new Harness(registry(editor))) {
            h.create(temporary.resolve("blocked"));UUID document=h.model.activeDocument().orElseThrow();
            h.model.selectNode("root");var fixed=h.model.beginRemoval();assertFalse(fixed.plan().allowed());
            assertThrows(SessionRejection.class,()->h.model.removeObject(fixed));h.model.cancelRemoval();
            h.model.selectNode("branch");editor.blocked=true;var referenced=h.model.beginRemoval();
            assertEquals(1,referenced.plan().references().size());assertFalse(referenced.plan().allowed());
            var before=h.handle(document).view();assertThrows(SessionRejection.class,()->h.model.removeObject(referenced));
            assertEquals(before,h.handle(document).view());assertEquals(0,editor.removes);
            editor.blocked=false;editor.unsupported=true;var unsupported=h.model.beginRemoval();
            assertTrue(unsupported.plan().unavailable().isPresent());assertThrows(SessionRejection.class,()->h.model.removeObject(unsupported));
            assertEquals(before,h.handle(document).view());
        }
    }

    @Test void incompletePlansAndProviderCandidatesCannotLoseExtraNodesOrReparentSurvivors() throws Exception {
        var editor=new TreeEditor();
        try(var h=new Harness(registry(editor))) {
            h.create(temporary.resolve("provider"));UUID document=h.model.activeDocument().orElseThrow();h.model.selectNode("branch");
            var before=h.handle(document).view();editor.incomplete=true;
            assertThrows(SessionRejection.class,h.model::beginRemoval);assertEquals(before,h.handle(document).view());editor.incomplete=false;
            var draft=h.model.beginRemoval();
            for(int mode=1;mode<=4;mode++) {
                editor.badCandidate=mode;assertThrows(RuntimeException.class,()->h.model.removeObject(draft));
                assertEquals(before,h.handle(document).view());assertEquals(Optional.of("branch"),h.model.selectedNode());
            }
            editor.badCandidate=0;h.model.removeObject(draft);assertEquals("title\nroot|other",h.handle(document).view().content());
        }
    }

    @Test void mutableProviderCannotHideExtraDeletionByMutatingItsIsolatedInput() throws Exception {
        var editor=new MutableTreeEditor();
        try(var h=new Harness(registry(new MutableTreeType(),editor))) {
            h.create(temporary.resolve("mutable"));UUID document=h.model.activeDocument().orElseThrow();
            var handle=h.model.documents().get(document);h.model.selectNode("branch");
            var token=handle.view().token();var draft=h.model.beginRemoval();
            assertThrows(SessionRejection.class,()->h.model.removeObject(draft));
            assertEquals(INITIAL,handle.view().content().toString());assertEquals(token,handle.view().token());
            assertFalse(h.model.canUndo());assertEquals(Optional.of("branch"),h.model.selectedNode());
            editor.extraDeletion=false;h.model.removeObject(draft);
            assertEquals("title\nroot|other",handle.view().content().toString());
            h.model.undo();assertEquals(INITIAL,handle.view().content().toString());assertFalse(h.model.canUndo());
            h.model.redo();assertEquals("title\nroot|other",handle.view().content().toString());
        }
    }

    @Test void previewIsImmutableAndRejectsDuplicateSemanticEdgesWhileKeepingFullDeclaredProperty() {
        var source=new DocumentEditor.Node("source",Optional.of("root"),LABEL);
        var target=new DocumentEditor.Node("target",Optional.of("root"),LABEL);
        String property="a".repeat(64)+":"+"b".repeat(256);
        var reference=new DocumentEditor.RemovalReference(source,property,target);
        var nodes=new ArrayList<>(List.of(target));var refs=new ArrayList<>(List.of(reference));
        var plan=new DocumentEditor.RemovalPlan(nodes,refs,Optional.empty());nodes.clear();refs.clear();
        assertEquals(List.of(target),plan.objects());assertEquals(property,plan.references().get(0).property());
        assertThrows(UnsupportedOperationException.class,()->plan.objects().clear());
        var renamed=new DocumentEditor.Node("source",Optional.empty(),new TextContent.Literal("Other label"));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.RemovalPlan(List.of(target),List.of(reference,new DocumentEditor.RemovalReference(renamed,property,target)),Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.RemovalPlan(List.of(target),List.of(new DocumentEditor.RemovalReference(target,property,target)),Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.RemovalPlan(List.of(),List.of(),Optional.empty()));
    }

    private static final class TreeEditor implements DocumentEditor<String> {
        boolean blocked,unsupported,incomplete;int badCandidate,removes;
        public Class<String> valueType(){return String.class;}
        public TextContent label(){return LABEL;}
        public List<Node> nodes(String value){
            var result=new ArrayList<Node>();result.add(new Node("root",Optional.empty(),LABEL));
            String[] lines=value.split("\n");
            for(int index=1;index<lines.length;index++) {
                String[] parts=lines[index].split("\\|",2);result.add(new Node(parts[1],Optional.of(parts[0]),new TextContent.Literal(parts[1])));
            }
            return List.copyOf(result);
        }
        public List<Field> fields(String value,String node){return List.of(new Field("title",LABEL,Kind.TEXT,value.split("\n")[0],LABEL));}
        public String apply(String value,String node,String property,String raw){return raw+value.substring(value.indexOf('\n'));}
        public Optional<RemovalPlan> planRemoval(String value,String node){
            if(unsupported)return Optional.empty();var byId=new HashMap<String,Node>();nodes(value).forEach(n->byId.put(n.id(),n));
            if(node.equals("root"))return Optional.of(new RemovalPlan(List.of(),List.of(),Optional.of(LABEL)));
            var removed=incomplete?List.of(byId.get("branch")):List.of(byId.get("branch"),byId.get("leaf"));
            return Optional.of(new RemovalPlan(removed,blocked?List.of(new RemovalReference(byId.get("other"),"test:link",byId.get("leaf"))):List.of(),Optional.empty()));
        }
        public String remove(String value,String node){
            removes++;String title=value.split("\n")[0];
            return title+switch(badCandidate){case 1->"\nroot|other\nother|extra";case 2->"";case 3->"\nbranch|other";case 4->"\nother|other";default->"\nroot|other";};
        }
    }
    private static final class MutableTreeEditor implements DocumentEditor<StringBuilder> {
        final TreeEditor delegate=new TreeEditor();boolean extraDeletion=true;
        public Class<StringBuilder> valueType(){return StringBuilder.class;}
        public TextContent label(){return LABEL;}
        public List<Node> nodes(StringBuilder value){return delegate.nodes(value.toString());}
        public List<Field> fields(StringBuilder value,String node){return List.of();}
        public StringBuilder apply(StringBuilder value,String node,String property,String raw){throw new UnsupportedOperationException();}
        public Optional<RemovalPlan> planRemoval(StringBuilder value,String node){return delegate.planRemoval(value.toString(),node);}
        public StringBuilder remove(StringBuilder value,String node){
            value.setLength(0);value.append(extraDeletion?"title":"title\nroot|other");return value;
        }
    }
    private static final class MutableTreeType implements DocumentType<StringBuilder> {
        public Class<StringBuilder> valueType(){return StringBuilder.class;}public EditorId codecId(){return TYPE;}public int schemaVersion(){return 1;}
        public Set<Integer> readableSchemas(){return Set.of(1);}public StringBuilder create(){return new StringBuilder(INITIAL);}
        public StringBuilder snapshot(StringBuilder value){return new StringBuilder(value);}
        public byte[] encode(StringBuilder value){return value.toString().getBytes(StandardCharsets.UTF_8);}
        public StringBuilder decode(int schema,byte[] bytes){return new StringBuilder(new String(bytes,StandardCharsets.UTF_8));}
        public List<Problem> validate(StringBuilder value){return List.of();}
        public DocumentDependencies dependencies(StringBuilder value){return new DocumentDependencies(Set.of(),Set.of(),Set.of());}
    }
    private static ExtensionRegistry registry(TreeEditor editor){return registry(new TreeType(),editor);}
    private static <T> ExtensionRegistry registry(DocumentType<T> type,DocumentEditor<T> editor){
        var api=new ApiVersion(1,0);var builder=new ExtensionRegistryBuilder(api,Contribution.Side.COMMON);
        builder.define(ResourceProducers.KIND);builder.define(ResourceContentValidators.KIND);builder.define(DocumentTypes.KIND);
        builder.addModule(new ModuleSpec(TYPE,"1",api,Set.of(),Set.of()));
        builder.add(DocumentTypes.KIND,new Contribution(new ContributionKey(DocumentTypes.KIND.id(),TYPE),TYPE,Contribution.Side.COMMON,Set.of()),type);
        DocumentEditors.register(builder,TYPE,TYPE,editor);return builder.freeze();
    }
    private static final class TreeType implements DocumentType<String>{
        public Class<String> valueType(){return String.class;}public EditorId codecId(){return TYPE;}public int schemaVersion(){return 1;}
        public Set<Integer> readableSchemas(){return Set.of(1);}public String create(){return INITIAL;}public String snapshot(String value){return value;}
        public byte[] encode(String value){return value.getBytes(StandardCharsets.UTF_8);}public String decode(int schema,byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
        public List<Problem> validate(String value){return List.of();}public DocumentDependencies dependencies(String value){return new DocumentDependencies(Set.of(),Set.of(),Set.of());}
    }
    private static final class ManualExecutor implements Executor{
        final Deque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable task){tasks.addLast(task);}
    }
    private static final class Harness implements AutoCloseable{
        final ManualExecutor io=new ManualExecutor(),completion=new ManualExecutor();final ProjectWorkbench model;
        Harness(ExtensionRegistry registry){model=new ProjectWorkbench(registry,io,completion);}
        void create(Path path){model.create(path,"Removal project");drain();model.createDocument(TYPE,"First");}
        @SuppressWarnings("unchecked") DocumentHandle<String> handle(UUID id){return (DocumentHandle<String>)model.documents().get(id);}
        void drain(){for(int i=0;!io.tasks.isEmpty()||!completion.tasks.isEmpty();i++){assertTrue(i<32);if(!io.tasks.isEmpty())io.tasks.removeFirst().run();if(!completion.tasks.isEmpty())completion.tasks.removeFirst().run();}assertFalse(model.busy());assertTrue(model.technicalFailure().isEmpty());}
        public void close(){drain();if(model.hasProject()){model.closeProject(true);drain();}}
    }
}
