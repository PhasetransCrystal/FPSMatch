package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorkbenchTest {
    @TempDir Path temporary;
    private static final EditorId OWNER = id("test:workbench"), TYPE = id("test:note"), IMPORTER = id("test:note_import");
    private static final TextContent LABEL = new TextContent.Literal("Note");

    @Test void diagnosticEventsRetainOriginalFailuresAndExposeOnlySafeMetadata() {
        var events = new ArrayList<ProjectWorkbench.FailureDiagnostic>();
        var failures = new ArrayList<Throwable>();
        var model = new ProjectWorkbench(registry(new NoteImporter()), Runnable::run, Runnable::run,
                (diagnostic, failure) -> { events.add(diagnostic); failures.add(failure); });
        var first = new IllegalArgumentException("private form text");
        model.report(first);
        assertSame(first, model.technicalFailure().orElseThrow());
        var diagnostic = model.failureDiagnostic().orElseThrow();
        assertEquals(1, diagnostic.eventId()); assertEquals("action", diagnostic.operation());
        assertEquals("IllegalArgumentException", diagnostic.exceptionType());
        assertEquals(ProjectWorkbench.text("failure_unexpected"), diagnostic.reason());
        assertFalse(diagnostic.toString().contains("private form text"));
        model.report(new IllegalStateException("second private value"));
        assertEquals(2, model.failureDiagnostic().orElseThrow().eventId());
        assertEquals(List.of(1L, 2L), events.stream().map(ProjectWorkbench.FailureDiagnostic::eventId).toList());
        assertSame(first, failures.get(0));
    }

    @Test void storageReasonsUseStableCodesWithoutExposingPathsOrMessages() {
        var model = new ProjectWorkbench(registry(new NoteImporter()), Runnable::run, Runnable::run);
        for (var code : ProjectStorageException.Code.values()) {
            model.report(new ProjectStorageException(code, "private/path and unsaved input"));
            var diagnostic = model.failureDiagnostic().orElseThrow();
            assertInstanceOf(TextContent.Message.class, diagnostic.reason());
            assertTrue(((TextContent.Message) diagnostic.reason()).key().startsWith("editor.workbench.failure_storage_"));
            assertFalse(diagnostic.toString().contains("private/path"));
        }
        model.report(new java.nio.file.AccessDeniedException("private/path"));
        assertEquals(ProjectWorkbench.text("failure_access"), model.failureDiagnostic().orElseThrow().reason());
        model.report(new java.io.IOException("private input"));
        assertEquals(ProjectWorkbench.text("failure_io"), model.failureDiagnostic().orElseThrow().reason());
    }

    @Test void missingDirectoryFailureGivesRecoverableReasonAndNewOperationClearsIt() {
        var io = new ManualExecutor(); var completion = new ManualExecutor();
        var model = new ProjectWorkbench(registry(new NoteImporter()), io, completion);
        model.open(temporary.resolve("not-a-project")); io.next(); completion.next();
        assertFalse(model.busy()); assertFalse(model.hasProject());
        var diagnostic = model.failureDiagnostic().orElseThrow();
        assertEquals("opening", diagnostic.operation());
        assertEquals(ProjectWorkbench.text("failure_storage_path"), diagnostic.reason());
        assertFalse(Files.exists(temporary.resolve("not-a-project")));
        model.open(temporary.resolve("still-missing"));
        assertTrue(model.busy()); assertTrue(model.failureDiagnostic().isEmpty());
        model.cancel(); io.next(); completion.next();
        assertTrue(model.failureDiagnostic().isEmpty()); assertFalse(model.busy());
    }

    @Test void pendingActionDiagnosticDoesNotOrphanWorkAndClearsAfterSuccessfulCompletion() {
        var io = new ManualExecutor(); var completion = new ManualExecutor();
        var events = new ArrayList<ProjectWorkbench.FailureDiagnostic>();
        var model = new ProjectWorkbench(registry(new NoteImporter()), io, completion,
                (diagnostic, failure) -> events.add(diagnostic));
        model.create(temporary.resolve("diagnostic-lifecycle"), "Project");
        model.report(new IllegalArgumentException("rejected while creating"));
        assertTrue(model.busy()); assertEquals(1, events.size());
        assertTrue(model.failureDiagnostic().isPresent());
        io.next(); completion.next();
        assertTrue(model.hasProject()); assertFalse(model.busy()); assertTrue(model.problems().isEmpty());
        assertTrue(model.failureDiagnostic().isEmpty()); assertTrue(model.technicalFailure().isEmpty());
        model.closeProject(true); io.next(); completion.next();
        assertEquals(1, events.size());
    }

    @Test void failingDiagnosticSinkNeverReplacesOriginalFailureOrBreaksCancellation() {
        var io = new ManualExecutor(); var completion = new ManualExecutor();
        var model = new ProjectWorkbench(registry(new NoteImporter()), io, completion,
                (diagnostic, failure) -> { throw new IllegalStateException("logger unavailable"); });
        var failure = new IllegalArgumentException("original");
        assertDoesNotThrow(() -> model.report(failure));
        assertSame(failure, model.technicalFailure().orElseThrow());
        model.open(temporary.resolve("cancelled-open")); model.cancel();
        io.next(); completion.next();
        assertFalse(model.busy()); assertTrue(model.problems().isEmpty());
        assertTrue(model.failureDiagnostic().isEmpty()); assertTrue(model.technicalFailure().isEmpty());
    }

    @Test void discardedFieldClearsOnlyItsOwnFailureAfterTheWholeFailedRowIsResolved() throws Exception {
        try (var h = new Harness(registry(new NoteImporter(), new AxisEditor()))) {
            h.model.create(temporary.resolve("field-failure"), "Fields");h.drain();h.model.createDocument(TYPE,"Axes");
            UUID document=h.model.activeDocument().orElseThrow();
            var x=new ProjectWorkbench.Address(document,"root","x");
            var y=new ProjectWorkbench.Address(document,"root","y");
            var z=new ProjectWorkbench.Address(document,"root","z");
            h.model.input(x,"invalid");h.model.input(y,"also invalid");h.model.input(z,"7");
            var before=h.model.documents().get(document).view();
            var failure=assertThrows(NumberFormatException.class,()->h.model.applyFields(List.of(x,y)));
            h.model.reportFieldFailure(List.of(x,y),failure);
            var event=h.model.failureDiagnostic().orElseThrow();
            h.model.discardInput(z);assertEquals(event,h.model.failureDiagnostic().orElseThrow());
            h.model.discardInput(x);assertEquals(event,h.model.failureDiagnostic().orElseThrow());
            assertFalse(h.model.problems().isEmpty());assertEquals("also invalid",h.model.raw(y,"0"));
            h.model.discardInput(y);
            assertTrue(h.model.failureDiagnostic().isEmpty());assertTrue(h.model.technicalFailure().isEmpty());
            assertTrue(h.model.problems().isEmpty());assertEquals(ProjectWorkbench.text("ready"),h.model.status());
            assertEquals(before.content(),h.model.documents().get(document).view().content());

            h.model.input(x,"invalid");h.model.reportFieldFailure(List.of(x),failure);
            var unrelated=new java.io.IOException("private resource path");
            h.model.reportResourceImportFailure(unrelated);event=h.model.failureDiagnostic().orElseThrow();
            h.model.discardInput(x);
            assertEquals(event,h.model.failureDiagnostic().orElseThrow());assertSame(unrelated,h.model.technicalFailure().orElseThrow());

            h.model.input(x,"invalid");h.model.reportFieldFailure(List.of(x),failure);
            Path source=Files.writeString(temporary.resolve("cancelled-import.txt"),"test");
            h.model.inspectImport(IMPORTER,Map.of("source",source));assertTrue(h.model.busy());h.model.discardInput(x);
            assertTrue(h.model.busy());h.model.cancel();h.drain();assertTrue(h.model.failureDiagnostic().isEmpty());
        }
    }

    @Test void centralFormsValidateOwnerDeclaredTargetsAndPagesWithoutChangingSelectionOrDrafts() throws Exception {
        var editor=new FormEditor();
        try(var h=new Harness(registry(new NoteImporter(),editor))) {
            h.model.create(temporary.resolve("forms"),"Forms");h.drain();
            h.model.createDocument(TYPE,"First");UUID first=h.model.activeDocument().orElseThrow();
            h.model.selectNode("root");var address=new ProjectWorkbench.Address(first,"root","value");
            h.model.input(address,"retained raw");var before=h.model.documents().get(first).view();
            h.model.createDocument(TYPE,"Second");UUID second=h.model.activeDocument().orElseThrow();
            var forms=h.model.studioForms(first);
            assertEquals(List.of(new StudioDocument.Form("main",LABEL,"root",List.of("overview"))),forms);
            assertThrows(UnsupportedOperationException.class,()->forms.clear());
            assertEquals(second,h.model.activeDocument().orElseThrow());assertEquals(before,h.model.documents().get(first).view());
            assertEquals("retained raw",h.model.raw(address,"missing"));
            assertTrue(h.model.studioForms(UUID.randomUUID()).isEmpty());
            for(int mode=1;mode<=4;mode++) {
                editor.mode=mode;
                var failure=assertThrows(SessionRejection.class,()->h.model.studioForms(first));
                assertEquals(id("editor:workbench/invalid_presentation"),failure.problems().get(0).code());
                assertEquals(before,h.model.documents().get(first).view());
            }
            editor.mode=0;h.model.discardInput(address);
            h.model.input(address,"shared applied value");h.model.apply(address);
            assertEquals("shared applied value",h.model.documents().get(first).view().content());
            assertEquals(forms,h.model.studioForms(first));
            h.model.selectDocument(first);h.model.undo();assertEquals("new",h.model.documents().get(first).view().content());
            assertTrue(h.model.studioForms(first).stream().allMatch(form->form.target().equals("root")));
        }
    }
    @Test void singleDocumentQueriesSeeDirectSessionChangesWithoutChangingActiveSelection() throws Exception {
        try(var h=new Harness(registry(new NoteImporter(),new LookupEditor(),new LookupCreator()))) {
            h.model.create(temporary.resolve("direct-lookup"),"Direct lookup");h.drain();
            h.model.createDocument(TYPE,"First");UUID first=h.model.activeDocument().orElseThrow();
            h.model.selectNode("root");
            h.model.createDocument(TYPE,"Second");UUID second=h.model.activeDocument().orElseThrow();
            h.model.selectNode("cell");
            var directory=h.model.documents();var secondBefore=directory.get(second).view();
            UUID added=UUID.randomUUID();assertTrue(h.model.nodes(added).isEmpty());
            long version=h.model.version();
            // Exercise the public session API outside the workbench command/version boundary.
            var sessionField=ProjectWorkbench.class.getDeclaredField("session");sessionField.setAccessible(true);
            var session=(ProjectSession)sessionField.get(h.model);
            var created=session.createWithValue(added,TYPE,String.class,"External","external");
            assertEquals(version,h.model.version());assertFalse(directory.containsKey(added));
            assertSame(created,h.model.documents().get(added));
            assertEquals("external",h.model.fields(added,"root").get(0).raw());
            assertEquals(2,h.model.nodes(added).size());
            assertThrows(UnsupportedOperationException.class,()->directory.put(added,created));
            var firstHandle=session.find(first,String.class).orElseThrow();
            firstHandle.edit(firstHandle.view().token(),LABEL,value->"changed");
            assertEquals(version,h.model.version());
            assertEquals("changed",h.model.fields(first,"root").get(0).raw());
            assertEquals(new TextContent.Literal("changed"),h.model.nodes(first).get(0).label());
            assertEquals(new TextContent.Literal("changed"),h.model.studioGrid(first,"root").orElseThrow().label());
            assertEquals(new TextContent.Literal("changed"),h.model.studioSections(first,"root").get(0).context());
            assertEquals(new TextContent.Literal("changed"),h.model.studioForms(first).get(0).label());
            assertEquals(List.of("cell"),h.model.referenceLists(first,"root").get(0).selected());
            assertEquals(Optional.of("root"),h.model.creationChoices(first,"root").get(0).target().parent());
            assertEquals(1,h.model.creationChoicesForReference(new ProjectWorkbench.Address(first,"root","refs")).size());
            var address=new ProjectWorkbench.Address(first,"root","value");
            h.model.input(address,"unapplied");
            assertEquals(version,h.model.version());assertTrue(h.model.documentHasPendingInputs(first));
            assertEquals("unapplied",h.model.raw(address,"missing"));
            assertEquals("changed",h.model.fields(first,"root").get(0).raw());
            assertEquals(second,h.model.activeDocument().orElseThrow());
            assertEquals(Optional.of("root"),h.model.selectedNode(first));
            assertEquals(Optional.of("cell"),h.model.selectedNode());
            assertEquals(secondBefore,h.model.documents().get(second).view());
            assertFalse(h.model.canUndo());
        }
    }

    @Test void singleDocumentQueriesPreserveMissingTargetAndOwnerChecks() throws Exception {
        try(var h=new Harness(registry(new NoteImporter(),new LookupEditor(),new LookupCreator()))) {
            UUID missing=UUID.randomUUID();
            assertTrue(h.model.nodes(missing).isEmpty());
            assertThrows(NullPointerException.class,()->h.model.nodes(null));
            h.model.create(temporary.resolve("missing-lookup"),"Missing lookup");h.drain();
            h.model.createDocument(TYPE,"Present");UUID present=h.model.activeDocument().orElseThrow();
            var before=h.model.documents().get(present).view();
            var address=new ProjectWorkbench.Address(missing,"root","value");
            assertTrue(h.model.nodes(missing).isEmpty());assertTrue(h.model.sceneNodes(missing).isEmpty());
            assertTrue(h.model.fields(missing,"root").isEmpty());assertTrue(h.model.studioForms(missing).isEmpty());
            assertTrue(h.model.studioGrid(missing,"root").isEmpty());assertTrue(h.model.studioSections(missing,"root").isEmpty());
            assertTrue(h.model.referenceLists(missing,"root").isEmpty());assertTrue(h.model.selectedNode(missing).isEmpty());
            assertTrue(h.model.resourcesForDocument(missing).isEmpty());assertFalse(h.model.documentUnsaved(missing));
            assertFalse(h.model.documentHasPendingInputs(missing));assertTrue(h.model.creationChoices(missing,"root").isEmpty());
            assertTrue(h.model.creationChoicesForReference(address).isEmpty());
            for(Runnable action:List.<Runnable>of(
                    ()->h.model.selectDocument(missing),()->h.model.selectNode(missing,"root"),
                    ()->h.model.input(address,"raw"),()->h.model.apply(address),
                    ()->h.model.beginReferences(address),()->h.model.copyStudioCell(missing,"cell"),
                    ()->h.model.beginObjectCreation(missing,"root",id("test:creator_0")),
                    ()->h.model.beginRemoval(missing,"root"),()->h.model.beginRemoval(null,"root"),
                    ()->h.model.saveDocument(missing))) {
                var rejection=assertThrows(SessionRejection.class,action::run);
                assertEquals(id("editor:workbench/missing_target"),rejection.problems().get(0).code());
            }
            h.model.discardInput(address);
            assertThrows(NullPointerException.class,()->h.model.nodes(null));
            java.util.concurrent.CompletableFuture.runAsync(()->{
                assertThrows(IllegalStateException.class,()->h.model.nodes(present));
                assertThrows(IllegalStateException.class,()->h.model.nodes(null));
                assertThrows(IllegalStateException.class,()->h.model.selectedNode(present));
                assertThrows(IllegalStateException.class,()->h.model.input(address,"raw"));
            }).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(present,h.model.activeDocument().orElseThrow());
            assertEquals(before,h.model.documents().get(present).view());
        }
    }

    @Test void singleDocumentQueriesAfterReopenUseNewHandleWithoutOldInputsSelectionOrClipboard() throws Exception {
        try(var h=new Harness(registry(new NoteImporter(),new LookupEditor(),new LookupCreator()))) {
            Path path=temporary.resolve("lookup-reopen");
            h.model.create(path,"Reopen lookup",TYPE,"Saved",Map.of("body","saved"));h.drain();
            UUID document=h.model.activeDocument().orElseThrow();var old=h.model.documents().get(document);
            h.model.selectNode("root");h.model.copyStudioCell(document,"cell");
            assertTrue(h.model.canPasteStudioCell(document));
            var address=new ProjectWorkbench.Address(document,"root","value");
            h.model.input(address,"discarded");h.model.apply(address);h.model.input(address,"pending");
            h.model.closeProject(true);h.drain();
            assertThrows(IllegalStateException.class,old::view);
            assertTrue(h.model.fields(document,"root").isEmpty());assertTrue(h.model.selectedNode(document).isEmpty());
            h.model.open(path);h.drain();
            assertNotSame(old,h.model.documents().get(document));
            assertEquals(document,h.model.activeDocument().orElseThrow());
            assertEquals("saved",h.model.fields(document,"root").get(0).raw());
            assertEquals("fallback",h.model.raw(address,"fallback"));
            assertFalse(h.model.pending(address));assertFalse(h.model.documentHasPendingInputs(document));
            assertTrue(h.model.selectedNode(document).isEmpty());assertTrue(h.model.selectedNode().isEmpty());
            assertFalse(h.model.canPasteStudioCell(document));assertFalse(h.model.canUndo());
        }
    }

    private static final class LookupEditor extends NoteEditor {
        @Override public List<Node> nodes(String value) {
            return List.of(new Node("root",Optional.empty(),new TextContent.Literal(value)),
                    new Node("cell",Optional.of("root"),LABEL));
        }
        @Override public List<StudioDocument.Form> studioForms(String value) {
            return List.of(new StudioDocument.Form("main",new TextContent.Literal(value),"root",List.of("overview")));
        }
        @Override public Optional<StudioDocument.Grid> studioGrid(String value,String node) {
            return Optional.of(new StudioDocument.Grid("root",new TextContent.Literal(value),
                    List.of(new StudioDocument.Column("column",LABEL,1)),
                    List.of(new StudioDocument.Cell("column",0,Optional.of("cell"),Optional.empty(),LABEL,List.of(),Optional.empty()))));
        }
        @Override public List<StudioDocument.Section> studioSections(String value,String node) {
            var nodes=nodes(value);
            return List.of(new StudioDocument.Section("main",nodes.get(0),new TextContent.Literal(value),
                    List.of(nodes.get(1)),List.of(),false));
        }
        @Override public List<ReferenceList> referenceLists(String value,String node) {
            return List.of(new ReferenceList("refs",LABEL,LABEL,List.of(new Choice("cell",LABEL)),
                    value.equals("changed")?List.of("cell"):List.of()));
        }
        @Override public Object copyStudioCell(String value,String node) { return value; }
    }
    private static final class LookupCreator implements ObjectCreator<String> {
        public EditorId documentType() { return TYPE; }
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        public TextContent category() { return LABEL; }
        public Target target(String value,Optional<String> selection) {
            return value.equals("changed")?Target.available("root"):Target.unavailable(LABEL);
        }
        public boolean supportsReference(String value,String node,String property) {
            return value.equals("changed")&&node.equals("root")&&property.equals("refs");
        }
        public List<DocumentEditor.Field> fields(String value,String parent) { return List.of(); }
        public String create(String value,String parent,UUID identity,Map<String,String> raw) { return value; }
    }

    @Test void centralFormDescriptorsOwnTheirPageListsAndRejectInvalidRoutes() {
        var pages=new ArrayList<>(List.of("overview"));
        var form=new StudioDocument.Form("main",LABEL,"root",pages);pages.clear();
        assertEquals(List.of("overview"),form.pageIds());
        assertThrows(UnsupportedOperationException.class,()->form.pageIds().clear());
        assertThrows(IllegalArgumentException.class,()->new StudioDocument.Form("main",LABEL,"root",List.of()));
        assertThrows(IllegalArgumentException.class,()->new StudioDocument.Form("main",LABEL,"root",List.of("overview","overview")));
        assertThrows(IllegalArgumentException.class,()->new StudioDocument.Form("main",LABEL,"root",List.of("")));
    }
    private static final class FormEditor extends NoteEditor {
        int mode;
        @Override public List<StudioDocument.Form> studioForms(String value) {
            var form=new StudioDocument.Form("main",LABEL,mode==1?"missing":"root",List.of(mode==2?"absent":"overview"));
            if(mode==3)return List.of(form,form);
            if(mode==4)return java.util.stream.IntStream.range(0,33).mapToObj(i->new StudioDocument.Form("form-"+i,LABEL,"root",List.of("overview"))).toList();
            return List.of(form);
        }
    }

    @Test void templatePublishesItsFirstDocumentInTheInitialCommit() throws Exception {
        Path path = temporary.resolve("template");
        UUID document;
        try (Harness h = new Harness(registry(new NoteImporter()))) {
            assertEquals(Set.of(id("test:category")), h.model.projectCategories().keySet());
            assertEquals(Set.of(TYPE), h.model.projectTemplates(id("test:category")).keySet());
            h.model.create(path, "Project", TYPE, "First note", Map.of("body", "first published value"));
            assertFalse(h.model.canCancel(), "publication cannot claim cancellation after it starts");
            h.model.cancel(); h.drain();
            assertTrue(h.model.hasProject()); assertFalse(h.model.unsaved());
            document = h.model.activeDocument().orElseThrow();
            assertEquals("first published value", h.model.documents().get(document).view().content());
        }
        try (var store = LocalProjectStore.open(path)) {
            var saved = store.load();
            assertEquals(Set.of(document), saved.state().documents().keySet());
            assertEquals("First note", saved.state().documents().get(document).displayName());
            assertEquals(Map.of(OWNER.toString(), "1"), saved.state().modules());
            assertNull(saved.parentCommitId(), "the first publication already contains the document");
        }
    }

    @Test void rejectedTemplateParametersNeverCreateTheTargetDirectory() {
        Path path = temporary.resolve("invalid-template");
        try (Harness h = new Harness(registry(new NoteImporter()))) {
            assertThrows(DocumentCodecException.class, () -> h.model.create(path, "Project", TYPE, "First note", Map.of("body", "")));
            assertFalse(Files.exists(path)); assertFalse(h.model.busy());
            assertThrows(SessionRejection.class, () -> h.model.create(path, "Project", TYPE, "First note", Map.of("typo", "value")));
            assertFalse(Files.exists(path)); assertFalse(h.model.hasProject());
        }
    }

    @Test void templateUnknownReconcilesTheSameInitialDocumentWithoutRepublishing() throws Exception {
        Path path = temporary.resolve("unknown-template");
        var io = new ManualExecutor(); var completion = new ManualExecutor();
        var model = new ProjectWorkbench(registry(new NoteImporter()), io, completion,
                PublicationFaultStores::createUncertainAfterPublish);
        model.create(path, "Project", TYPE, "First note", Map.of("body", "captured"));
        io.next(); completion.next();
        assertTrue(model.pendingCreation()); assertTrue(model.unresolvedSave());
        byte[] current = Files.readAllBytes(path.resolve("CURRENT"));
        assertThrows(SessionRejection.class, () -> model.closeProject(true));
        model.reconcile(); io.next(); completion.next(); io.next(); completion.next();
        assertFalse(model.unresolvedSave()); assertEquals(1, model.documents().size());
        assertEquals("captured", model.documents().values().iterator().next().view().content());
        assertFalse(model.unsaved()); assertArrayEquals(current, Files.readAllBytes(path.resolve("CURRENT")));
        model.closeProject(false); io.next(); completion.next();
    }

    @Test void createsEditsSavesAndReopensTheActualDiskProject() throws Exception {
        var registry = registry(new NoteImporter());
        Path path = temporary.resolve("roundtrip");
        UUID document;
        UUID saved;
        try (Harness h = new Harness(registry)) {
            h.model.create(path, "A project"); h.drain();
            assertTrue(h.model.hasProject());
            assertTrue(h.model.nodes().isEmpty()); assertTrue(h.model.fields().isEmpty());
            assertFalse(h.model.canUndo()); assertFalse(h.model.canRedo());
            h.model.createDocument(TYPE, "A note");
            document = h.model.activeDocument().orElseThrow();
            h.edit("persisted");
            assertTrue(h.model.unsaved());
            h.model.saveAll(); h.drain();
            assertFalse(h.model.unsaved());
            saved = h.model.savedVersion().orElseThrow();
            assertFalse(h.model.unresolvedSave());
        }
        try (Harness reopened = new Harness(registry)) {
            reopened.model.open(path); reopened.drain();
            assertEquals("A project", reopened.model.projectName());
            assertEquals(saved, reopened.model.savedVersion().orElseThrow());
            assertEquals("persisted", reopened.model.documents().get(document).view().content());
            assertFalse(reopened.model.unsaved());
        }
    }

    @Test void confirmsOnlyTheCapturedRevisionAndBusyActionReportsCannotOrphanPublication() throws Exception {
        var registry = registry(new NoteImporter());
        Path path = temporary.resolve("captured");
        UUID document;
        try (Harness h = new Harness(registry)) {
            h.model.create(path, "Captured save"); h.drain();
            h.model.createDocument(TYPE, "A note"); document = h.model.activeDocument().orElseThrow();
            h.edit("captured"); h.model.saveAll();
            assertTrue(h.model.canCancel());
            h.edit("later edit");
            h.io.next(); h.completion.next(); // prepare reply dispatches the original publication.
            assertTrue(h.model.busy()); assertFalse(h.model.canCancel());
            SessionRejection busy = assertThrows(SessionRejection.class, h.model::saveAll);
            assertEquals(id("editor:workbench/busy"), busy.problems().get(0).code());
            h.model.report(busy);
            assertTrue(h.model.busy()); assertFalse(h.model.canCancel());
            h.model.cancel();
            h.io.next();
            assertTrue(h.model.unresolvedSave(), "disk reply has not reached the owner");
            h.completion.next();
            assertFalse(h.model.busy()); assertFalse(h.model.unresolvedSave());
            assertEquals(ProjectWorkbench.text("saved"), h.model.status());
            assertEquals("later edit", h.model.documents().get(document).view().content());
            assertTrue(h.model.documents().get(document).hasUnpublishedChanges());
            assertTrue(h.model.unsaved());
        } // Explicit discard affects the later draft only.
        try (Harness reopened = new Harness(registry)) {
            reopened.model.open(path); reopened.drain();
            assertEquals("captured", reopened.model.documents().get(document).view().content());
            assertFalse(reopened.model.unsaved());
        }
    }

    @Test void documentSavePublishesOnlyItsOwnerWhileOtherDraftsAndRawRemain() throws Exception {
        Path path=temporary.resolve("one-document"); UUID first,second;
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(path,"Scoped");h.drain();
            h.model.createDocument(TYPE,"First");first=h.model.activeDocument().orElseThrow();
            h.edit("first saved");h.model.createDocument(TYPE,"Second");second=h.model.activeDocument().orElseThrow();
            h.edit("second saved");h.model.saveAll();h.drain();
            h.edit("second draft");var otherRaw=new ProjectWorkbench.Address(second,"root","value");h.model.input(otherRaw,"");
            h.model.selectDocument(first);h.edit("first updated");
            var other=h.model.documents().get(second).view();
            var request=h.model.saveDocument(first);
            assertEquals(first,request.document());
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.PREPARING),h.model.documentSaveState(request));
            h.drain();
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.CONFIRMED),h.model.documentSaveState(request));
            assertFalse(h.model.documentUnsaved(first));assertTrue(h.model.documentUnsaved(second));
            assertEquals(other,h.model.documents().get(second).view());assertTrue(h.model.pending(otherRaw));
            assertTrue(h.model.documentHasSavedBaseline(first));
        }
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.open(path);h.drain();
            assertEquals("first updated",h.model.documents().get(first).view().content());
            assertEquals("second saved",h.model.documents().get(second).view().content());
        }
    }

    @Test void documentSaveReceiptConfirmsCaptureWithoutHidingLaterEdits() throws Exception {
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("receipt-capture"),"Receipt");h.drain();
            h.model.createDocument(TYPE,"First");UUID document=h.model.activeDocument().orElseThrow();
            h.edit("captured");var request=h.model.saveDocument(document);h.edit("later");
            h.io.next();h.completion.next();
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.PUBLISHING),h.model.documentSaveState(request));
            assertThrows(SessionRejection.class,()->h.model.discardDocumentChanges(document));
            h.drain();
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.CONFIRMED),h.model.documentSaveState(request));
            assertTrue(h.model.documentUnsaved(document));
            h.model.discardDocumentChanges(document);
            assertEquals("captured",h.model.documents().get(document).view().content());
            assertFalse(h.model.documentUnsaved(document));assertFalse(h.model.canUndo());assertFalse(h.model.canRedo());
            h.edit("saved after discard");var next=h.model.saveDocument(document);h.drain();
            assertNotEquals(request.id(),next.id());assertTrue(h.model.documentSaveState(request).isEmpty());
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.CONFIRMED),h.model.documentSaveState(next));
        }
    }

    @Test void documentSaveRejectsOwnRawAndCancellationDoesNotConfirmOrWrite() throws Exception {
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("receipt-cancel"),"Receipt");h.drain();
            h.model.createDocument(TYPE,"First");UUID document=h.model.activeDocument().orElseThrow();
            var raw=new ProjectWorkbench.Address(document,"root","value");h.model.input(raw,"");
            assertTrue(h.model.documentHasPendingInputs(document));
            assertThrows(SessionRejection.class,()->h.model.saveDocument(document));assertEquals(0,h.io.size());
            h.model.discardInput(raw);var version=h.model.savedVersion();
            var request=h.model.saveDocument(document);h.io.next();h.model.cancel();h.completion.next();
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.CANCELLED),h.model.documentSaveState(request));
            assertEquals(version,h.model.savedVersion());assertFalse(h.model.documentHasSavedBaseline(document));
            assertTrue(h.model.documentUnsaved(document));assertFalse(h.model.busy());
        }
    }

    @Test void discardRestoresLatestBaselineAndOnlyClearsTheOwnersRawAndFailure() throws Exception {
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("scoped-discard"),"Discard");h.drain();
            h.model.createDocument(TYPE,"First");UUID first=h.model.activeDocument().orElseThrow();
            h.edit("saved one");h.model.saveDocument(first);h.drain();
            h.edit("saved two");h.model.saveDocument(first);h.drain();h.edit("discard me");
            var firstRaw=new ProjectWorkbench.Address(first,"root","value");h.model.input(firstRaw,"");
            h.model.createDocument(TYPE,"Second");UUID second=h.model.activeDocument().orElseThrow();
            h.edit("keep me");var secondRaw=new ProjectWorkbench.Address(second,"root","value");h.model.input(secondRaw,"keep raw");
            var other=h.model.documents().get(second).view();var version=h.model.savedVersion();
            h.model.reportFieldFailure(List.of(firstRaw),new IllegalArgumentException("bad field"));
            h.model.discardDocumentChanges(first);
            assertEquals("saved two",h.model.documents().get(first).view().content());
            assertFalse(h.model.pending(firstRaw));assertFalse(h.model.documentUnsaved(first));
            assertTrue(h.model.failureDiagnostic().isEmpty());assertTrue(h.model.problems().isEmpty());
            assertEquals(other,h.model.documents().get(second).view());assertEquals(Optional.of(second),h.model.activeDocument());
            assertEquals(version,h.model.savedVersion());
            var foreign=new java.io.IOException("unrelated resource failure");h.model.reportResourceImportFailure(foreign);
            var diagnostic=h.model.failureDiagnostic();h.model.discardDocumentChanges(second);
            assertEquals(diagnostic,h.model.failureDiagnostic());assertSame(foreign,h.model.technicalFailure().orElseThrow());
            assertEquals("new",h.model.documents().get(second).view().content());
            assertTrue(h.model.documentUnsaved(second));assertFalse(h.model.documentHasSavedBaseline(second));
            assertFalse(h.model.pending(secondRaw));assertEquals(version,h.model.savedVersion());
        }
    }

    @Test void documentSaveReceiptDistinguishesPreparationFailureAndUncertainPublication() throws Exception {
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("receipt-failure"),"Receipt");h.drain();h.model.createDocument(TYPE,"First");
            UUID document=h.model.activeDocument().orElseThrow();var version=h.model.savedVersion();
            h.io.rejectNext=true;var failed=h.model.saveDocument(document);
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.FAILED),h.model.documentSaveState(failed));
            assertFalse(h.model.unresolvedSave());assertFalse(h.model.busy());assertEquals(version,h.model.savedVersion());
            var uncertain=h.model.saveDocument(document);h.io.next();h.completion.next();
            h.completion.rejectNext=true;h.io.next();h.completion.next();
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.UNKNOWN),h.model.documentSaveState(uncertain));
            assertTrue(h.model.unresolvedSave());assertTrue(h.model.documentUnsaved(document));
            assertThrows(SessionRejection.class,()->h.model.discardDocumentChanges(document));
            h.model.reconcile();h.drain();
            assertFalse(h.model.unresolvedSave());assertNotEquals(version,h.model.savedVersion());
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.CONFIRMED),h.model.documentSaveState(uncertain));
            assertFalse(h.model.documentUnsaved(document));
        }
    }

    @Test void documentSaveReceiptCannotSatisfyAReopenedProjectGuard() throws Exception {
        Path path=temporary.resolve("receipt-session");
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(path,"Receipt");h.drain();h.model.createDocument(TYPE,"First");
            var request=h.model.saveDocument(h.model.activeDocument().orElseThrow());h.drain();
            assertEquals(Optional.of(ProjectWorkbench.DocumentSaveState.CONFIRMED),h.model.documentSaveState(request));
            h.model.closeProject(false);h.drain();assertTrue(h.model.documentSaveState(request).isEmpty());
            h.model.open(path);h.drain();assertTrue(h.model.documentSaveState(request).isEmpty());
        }
    }

    @Test void cancellingAnOpenAfterItsWorkerCompletesReleasesTheRealWriterLockWithoutAdoption() throws Exception {
        Path path = emptyProject("cancel-open");
        var registry = registry(new NoteImporter());
        try (Harness h = new Harness(registry)) {
            h.model.open(path); h.io.next();
            ProjectStorageException locked = assertThrows(ProjectStorageException.class, () -> LocalProjectStore.open(path));
            assertEquals(ProjectStorageException.Code.BUSY, locked.code());
            h.model.cancel(); h.completion.next();
            assertFalse(h.model.hasProject());
            assertTrue(h.model.busy(), "cleanup still owns the unadopted store");
            h.io.next(); h.completion.next();
            assertFalse(h.model.busy()); assertFalse(h.model.hasProject());
            assertTrue(h.model.documents().isEmpty());
            assertTrue(h.model.directory().isEmpty());
            try (var unlocked = LocalProjectStore.open(path)) {
                assertEquals("Existing", unlocked.load().state().name());
            }
        }
    }

    @Test void reportingARejectedActionDuringOpenPreservesItsWorkerAndAdoptsItsResult() throws Exception {
        Path path = emptyProject("busy-open");
        try (Harness h = new Harness(registry(new NoteImporter()))) {
            h.model.open(path);
            SessionRejection busy = assertThrows(SessionRejection.class, () -> h.model.createDocument(TYPE, "too early"));
            h.model.report(busy);
            assertTrue(h.model.busy()); assertTrue(h.model.canCancel());
            h.drain();
            assertTrue(h.model.hasProject()); assertFalse(h.model.busy());
            assertEquals(path, h.model.directory().orElseThrow());
            assertEquals(ProjectWorkbench.text("opened"), h.model.status());
        }
        try (var unlocked = LocalProjectStore.open(path)) { assertNotNull(unlocked.load()); }
    }

    @Test void invalidFieldApplyPreservesRawInputTokenContentAndHistoryAcrossSelection() throws Exception {
        try (Harness h = new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("pending"), "Pending"); h.drain();
            h.model.createDocument(TYPE, "First"); h.edit("valid");
            UUID first = h.model.activeDocument().orElseThrow();
            var address = new ProjectWorkbench.Address(first, "root", "value");
            h.model.input(address, "");
            var before = h.model.documents().get(first).view();
            h.model.createDocument(TYPE, "Second");
            h.model.selectDocument(first); h.model.selectNode("root");
            assertEquals("", h.model.raw(address, "fallback")); assertTrue(h.model.pending(address));
            assertThrows(DocumentRejection.class, () -> h.model.apply(address));
            assertEquals(before, h.model.documents().get(first).view());
            assertEquals("", h.model.documents().get(first).view().pendingInputs().get(address.pendingKey()));
            assertFalse(h.model.canUndo());
            assertThrows(SessionRejection.class, h.model::saveAll);
            assertEquals(0, h.io.size());
            h.model.discardInput(address);
            assertTrue(h.model.canUndo()); h.model.undo();
            assertEquals("new", h.model.documents().get(first).view().content());
            h.model.redo(); assertEquals("valid", h.model.documents().get(first).view().content());
        }
    }

    @Test void missingAndDirectoryDocumentInputsHaveSpecificSafeDiagnosticsAndNeverCreateDrafts() throws Exception {
        var importer=new NoteImporter();
        try(var h=new Harness(registry(importer))) {
            h.model.create(temporary.resolve("bad-document-inputs"),"Inputs");h.drain();
            var saved=h.model.savedVersion();
            h.model.inspectImport(IMPORTER,Map.of("source",temporary.resolve("private-missing-note")));h.drain();
            assertEquals(ProjectWorkbench.text("import_missing_file"),h.model.problems().get(0).message());
            assertEquals(ProjectWorkbench.text("import_missing_file"),h.model.failureDiagnostic().orElseThrow().reason());
            assertFalse(h.model.failureDiagnostic().toString().contains("private-missing-note"));
            h.model.inspectImport(IMPORTER,Map.of("source",temporary));h.drain();
            assertEquals(ProjectWorkbench.text("import_file_type"),h.model.problems().get(0).message());
            assertEquals(0,importer.calls);assertTrue(h.model.documents().isEmpty());assertFalse(h.model.unsaved());
            assertEquals(saved,h.model.savedVersion());assertFalse(h.model.importReady());
        }
    }

    @Test void cancellingDocumentImportDismissesOnlyItsFailureIncludingQueuedCallbacks() throws Exception {
        for(boolean queued:List.of(false,true))try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("cancel-failure-"+queued),"Imports");h.drain();
            h.model.inspectImport(IMPORTER,Map.of("source",temporary.resolve("missing")));h.io.next();
            if(!queued){h.completion.next();assertTrue(h.model.failureDiagnostic().isPresent());}
            h.model.cancelImport();h.drain();
            assertEquals(ProjectWorkbench.text("cancelled"),h.model.status());
            assertTrue(h.model.problems().isEmpty());assertTrue(h.model.technicalFailure().isEmpty());
            assertTrue(h.model.failureDiagnostic().isEmpty());assertFalse(h.model.importReady());
            assertTrue(h.model.documents().isEmpty());assertFalse(h.model.unsaved());
            var unrelated=new IllegalArgumentException("new field failure");h.model.report(unrelated);
            var diagnostic=h.model.failureDiagnostic().orElseThrow();h.model.cancelImport();
            assertEquals(diagnostic,h.model.failureDiagnostic().orElseThrow());assertSame(unrelated,h.model.technicalFailure().orElseThrow());
            h.model.inspectImport(IMPORTER,Map.of("source",temporary.resolve("missing")));h.io.next();h.model.cancelImport();
            h.model.report(unrelated);diagnostic=h.model.failureDiagnostic().orElseThrow();h.drain();
            assertEquals(diagnostic,h.model.failureDiagnostic().orElseThrow());assertFalse(h.model.importReady());
        }
    }

    @Test void synchronousDocumentImportFailuresAreScopedAndDoNotCancelOtherWorkers() throws Exception {
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("sync-import-failures"),"Imports");h.drain();
            var saved=h.model.savedVersion();
            h.model.reportImportFailure(new java.nio.file.InvalidPathException("private-source","invalid"));
            assertEquals("inspecting",h.model.failureDiagnostic().orElseThrow().operation());
            assertEquals(ProjectWorkbench.text("invalid_path"),h.model.failureDiagnostic().orElseThrow().reason());
            assertFalse(h.model.failureDiagnostic().toString().contains("private-source"));
            h.model.cancelImport();assertTrue(h.model.failureDiagnostic().isEmpty());assertTrue(h.model.problems().isEmpty());
            h.model.reportImportAcceptanceFailure(assertThrows(SessionRejection.class,()->h.model.acceptImport("Missing")));
            assertEquals("import_accepting",h.model.failureDiagnostic().orElseThrow().operation());
            h.model.cancelImport();assertTrue(h.model.failureDiagnostic().isEmpty());assertTrue(h.model.technicalFailure().isEmpty());
            assertEquals(saved,h.model.savedVersion());assertTrue(h.model.documents().isEmpty());assertFalse(h.model.unsaved());
            h.model.saveAll();assertTrue(h.model.busy());
            h.model.reportImportFailure(new IllegalArgumentException("rejected parallel import"));
            h.model.cancelImport();assertTrue(h.model.busy(),"leaving import must not cancel a save worker");
            h.drain();assertFalse(h.model.busy());assertEquals(ProjectWorkbench.text("saved"),h.model.status());
        }
    }

    @Test void fixedImportReportSurvivesAdoptionFailureAndSuccessfulRetryClearsOnlyItsDiagnostic() throws Exception {
        var importer=new NoteImporter();
        var warning=new Problem(Problem.Severity.WARNING,id("test:compatibility"),"source",LABEL);
        importer.diagnostics=List.of(warning);
        var source=Files.writeString(temporary.resolve("preview-with-warning.txt"),"fixed candidate");
        try(var h=new Harness(registry(importer))) {
            h.model.create(temporary.resolve("retry-import"),"Imports");h.drain();var saved=h.model.savedVersion();
            h.model.inspectImport(IMPORTER,Map.of("source",source));h.drain();
            assertEquals(List.of(warning),h.model.importProblems());
            assertThrows(UnsupportedOperationException.class,()->h.model.importProblems().clear());
            h.model.reportImportAcceptanceFailure(assertThrows(SessionRejection.class,()->h.model.acceptImport(" ")));
            assertTrue(h.model.importReady());assertEquals(List.of(warning),h.model.importProblems());
            assertTrue(h.model.documents().isEmpty());assertFalse(h.model.unsaved());
            Files.writeString(source,"changed after preflight");h.model.acceptImport("Adopted");
            assertEquals("fixed candidate",h.model.documents().values().iterator().next().view().content());
            assertTrue(h.model.failureDiagnostic().isEmpty());assertTrue(h.model.technicalFailure().isEmpty());
            assertTrue(h.model.problems().isEmpty());assertTrue(h.model.importProblems().isEmpty());
            assertEquals(ProjectWorkbench.text("imported"),h.model.status());assertEquals(saved,h.model.savedVersion());assertTrue(h.model.unsaved());
            h.model.inspectImport(IMPORTER,Map.of("source",source));h.drain();
            var foreign=new IllegalArgumentException("unrelated field failure");h.model.report(foreign);
            var diagnostic=h.model.failureDiagnostic().orElseThrow();
            assertEquals(List.of(warning),h.model.importProblems(),"the report must not become the global failure list");
            h.model.acceptImport("Second");
            assertEquals(diagnostic,h.model.failureDiagnostic().orElseThrow());assertSame(foreign,h.model.technicalFailure().orElseThrow());
            assertTrue(h.model.importProblems().isEmpty());
        }
    }

    @Test void cancellingRejectedAdoptionClearsPreviewButPreservesANewerForeignFailure() throws Exception {
        var source=Files.writeString(temporary.resolve("abandoned-preview.txt"),"candidate");
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("cancel-adoption"),"Imports");h.drain();
            h.model.inspectImport(IMPORTER,Map.of("source",source));h.drain();
            h.model.reportImportAcceptanceFailure(assertThrows(SessionRejection.class,()->h.model.acceptImport("")));
            var foreign=new IllegalStateException("new action failure");h.model.report(foreign);
            var diagnostic=h.model.failureDiagnostic().orElseThrow();h.model.cancelImport();
            assertFalse(h.model.importReady());assertTrue(h.model.importProblems().isEmpty());
            assertEquals(diagnostic,h.model.failureDiagnostic().orElseThrow());assertSame(foreign,h.model.technicalFailure().orElseThrow());
            assertTrue(h.model.documents().isEmpty());assertFalse(h.model.unsaved());
        }
    }

    @Test void importAcceptsTheFixedPreviewAndCancelledLatePreviewCannotBecomeADraft() throws Exception {
        NoteImporter importer = new NoteImporter();
        Path source = temporary.resolve("source.txt"); Files.writeString(source, "first candidate");
        Path path = temporary.resolve("imports");
        var registry = registry(importer);
        try (Harness h = new Harness(registry)) {
            h.model.create(path, "Imports"); h.drain();
            h.model.inspectImport(IMPORTER, Map.of("source", source)); h.io.next();
            Files.writeString(source, "changed after inspection");
            h.completion.next();
            assertTrue(h.model.importReady());
            h.model.acceptImport("Imported");
            assertEquals("first candidate", h.model.documents().values().iterator().next().view().content());
            assertEquals(1, importer.calls);
            assertTrue(h.model.unsaved());
            UUID imported = h.model.activeDocument().orElseThrow();
            h.model.inspectImport(IMPORTER, Map.of("source", source)); h.io.next();
            h.model.cancelImport(); h.completion.next();
            assertFalse(h.model.busy()); assertFalse(h.model.importReady());
            assertEquals(Set.of(imported), h.model.documents().keySet());
            assertThrows(SessionRejection.class, () -> h.model.acceptImport("late"));
            assertEquals("changed after inspection", Files.readString(source));
        }
        try (var store = LocalProjectStore.open(path)) {
            assertTrue(store.load().state().documents().isEmpty(), "import acceptance is draft-only");
        }
    }

    @Test void independentPendingFieldsRebaseButAnExternallyChangedTargetStaysStale() throws Exception {
        try (Harness h = new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("two-fields"), "Two fields"); h.drain();
            h.model.createDocument(TYPE, "A note");
            UUID id = h.model.activeDocument().orElseThrow();
            var title = new ProjectWorkbench.Address(id, "root", "value");
            var detail = new ProjectWorkbench.Address(id, "root", "detail");
            h.model.input(title, "title draft"); h.model.input(detail, "detail draft");
            h.model.apply(title);
            assertTrue(h.model.pending(detail)); assertEquals("detail draft", h.model.raw(detail, "fallback"));
            h.model.apply(detail);
            assertEquals("title draft\ndetail draft", h.model.documents().get(id).view().content());
            assertFalse(h.model.pending(title)); assertFalse(h.model.pending(detail));

            h.model.input(title, "stale draft");
            DocumentHandle<String> handle = note(h.model.documents().get(id));
            handle.edit(handle.view().token(), LABEL, value -> "external title\ndetail draft");
            var before = handle.view();
            DocumentRejection stale = assertThrows(DocumentRejection.class, () -> h.model.apply(title));
            assertEquals(id("editor:document/stale_token"), stale.problems().get(0).code());
            assertEquals(before, handle.view());
            assertTrue(h.model.pending(title)); assertEquals("stale draft", h.model.raw(title, "fallback"));
        }
    }

    @Test void compactRowFailureKeepsEveryRawAxisAndSuccessPublishesOneUndoableEdit() throws Exception {
        try (var h = new Harness(registry(new NoteImporter(), new AxisEditor()))) {
            h.model.create(temporary.resolve("atomic-row"), "Atomic row"); h.drain();
            h.model.createDocument(TYPE, "Position"); h.model.selectNode("root");
            UUID document = h.model.activeDocument().orElseThrow();
            var axes = List.of("x", "y", "z").stream()
                    .map(axis -> new ProjectWorkbench.Address(document, "root", axis)).toList();
            h.model.input(axes.get(0), "17"); h.model.input(axes.get(1), "-"); h.model.input(axes.get(2), "-9");
            var handle = h.model.documents().get(document); var before = handle.view();
            assertThrows(NumberFormatException.class, () -> h.model.applyFields(axes));
            assertEquals(before, handle.view());
            assertEquals(List.of("17", "-", "-9"), axes.stream().map(a -> h.model.raw(a, "missing")).toList());
            assertTrue(axes.stream().allMatch(h.model::pending)); assertFalse(h.model.canUndo());
            h.model.input(axes.get(1), "71");
            long revision = handle.view().token().revision();
            h.model.applyFields(axes);
            assertEquals("17,71,-9,true", handle.view().content());
            assertEquals(revision + 1, handle.view().token().revision());
            assertTrue(handle.view().pendingInputs().isEmpty());
            assertTrue(axes.stream().noneMatch(h.model::pending));
            h.model.undo(); assertEquals("new", handle.view().content()); assertFalse(h.model.canUndo());
            h.model.redo(); assertEquals("17,71,-9,true", handle.view().content());
        }
    }

    @Test void compactRowRebasesUnchangedAxesButRejectsAnExternallyChangedAxisAsAWhole() throws Exception {
        try (var h = new Harness(registry(new NoteImporter(), new AxisEditor()))) {
            h.model.create(temporary.resolve("stale-row"), "Stale row"); h.drain();
            h.model.createDocument(TYPE, "Position"); h.model.selectNode("root");
            UUID document = h.model.activeDocument().orElseThrow();
            var x = new ProjectWorkbench.Address(document, "root", "x");
            var y = new ProjectWorkbench.Address(document, "root", "y");
            var z = new ProjectWorkbench.Address(document, "root", "z");
            h.model.input(x, "1"); h.model.input(y, "2");
            h.model.input(z, "3"); h.model.apply(z);
            h.model.applyFields(List.of(x, y));
            var handle = note(h.model.documents().get(document));
            assertEquals("1,2,3,true", handle.view().content());
            h.model.input(x, "10"); h.model.input(y, "20");
            handle.edit(handle.view().token(), LABEL, value -> "99,2,3,true");
            var before = handle.view();
            var stale = assertThrows(SessionRejection.class, () -> h.model.applyFields(List.of(x, y)));
            assertEquals(id("editor:workbench/stale_input"), stale.problems().get(0).code());
            assertEquals(before, handle.view());
            assertEquals("10", h.model.raw(x, "")); assertEquals("20", h.model.raw(y, ""));
        }
    }

    @Test void disablingADependencyPreservesPendingAxesUntilExplicitlyReenabled() throws Exception {
        try (var h = new Harness(registry(new NoteImporter(), new AxisEditor()))) {
            h.model.create(temporary.resolve("dependent-row"), "Dependent row"); h.drain();
            h.model.createDocument(TYPE, "Position"); h.model.selectNode("root");
            UUID document = h.model.activeDocument().orElseThrow();
            var x = new ProjectWorkbench.Address(document, "root", "x");
            var y = new ProjectWorkbench.Address(document, "root", "y");
            var enabled = new ProjectWorkbench.Address(document, "root", "enabled");
            h.model.input(x, "8"); h.model.input(y, "9");
            h.model.input(enabled, "false"); h.model.apply(enabled);
            var handle = h.model.documents().get(document); var before = handle.view();
            assertThrows(SessionRejection.class, () -> h.model.input(x, "100"));
            assertThrows(SessionRejection.class, () -> h.model.applyFields(List.of(x, y)));
            assertEquals(before, handle.view());
            assertEquals("8", h.model.raw(x, "")); assertEquals("9", h.model.raw(y, ""));
            h.model.input(enabled, "true"); h.model.apply(enabled);
            h.model.applyFields(List.of(x, y));
            assertEquals("8,9,0,true", handle.view().content());
        }
    }

    @Test void creationUnknownKeepsTheOriginalWriterAndReconcilesWithoutAnotherCommit() throws Exception {
        Path path = temporary.resolve("unknown-creation");
        var io = new ManualExecutor(); var completion = new ManualExecutor();
        var model = new ProjectWorkbench(registry(new NoteImporter()), io, completion,
                PublicationFaultStores::createUncertainAfterPublish);
        model.create(path, "Unknown first publication"); io.next(); completion.next();
        assertTrue(model.pendingCreation()); assertTrue(model.unresolvedSave()); assertFalse(model.hasProject());
        assertEquals(path, model.directory().orElseThrow());
        assertThrows(SessionRejection.class, () -> model.closeProject(true));
        assertThrows(SessionRejection.class, () -> model.create(temporary.resolve("another"), "No retry"));
        var locked = assertThrows(ProjectStorageException.class, () -> LocalProjectStore.open(path));
        assertEquals(ProjectStorageException.Code.BUSY, locked.code());
        byte[] original = Files.readAllBytes(path.resolve("CURRENT"));
        model.reconcile(); io.next(); completion.next(); // Original receipt, then fresh read.
        assertTrue(model.busy()); assertFalse(model.unresolvedSave());
        io.next(); completion.next();
        assertTrue(model.hasProject()); assertFalse(model.pendingCreation());
        assertEquals("Unknown first publication", model.projectName());
        assertArrayEquals(original, Files.readAllBytes(path.resolve("CURRENT")));
        assertFalse(model.unsaved());
        model.closeProject(false); io.next(); completion.next();
        try (var reopened = LocalProjectStore.open(path)) { assertEquals(model.hasProject(), false); assertNotNull(reopened.load()); }
    }

    @Test void stablePendingAddressesCannotCollideAcrossNodeAndPropertyBoundaries() {
        UUID document = UUID.randomUUID();
        assertNotEquals(new ProjectWorkbench.Address(document, "a/b", "c").pendingKey(),
                new ProjectWorkbench.Address(document, "a", "b/c").pendingKey());
    }

    @SuppressWarnings("unchecked")
    private static DocumentHandle<String> note(DocumentHandle<?> handle) {
        assertEquals(String.class, handle.valueType()); return (DocumentHandle<String>) handle;
    }

    @Test void switchingDocumentsRetainsSelectionAndClickingTheActiveTabIsANoop() throws Exception {
        try(var h=new Harness(registry(new NoteImporter()))) {
            h.model.create(temporary.resolve("selection"),"Selection");h.drain();
            h.model.createDocument(TYPE,"A"); UUID a=h.model.activeDocument().orElseThrow();
            h.model.selectNode("root");
            h.model.input(new ProjectWorkbench.Address(a,"root","value"),"unapplied");
            long version=h.model.version(); h.model.selectDocument(a);
            assertEquals(version,h.model.version()); assertEquals(Optional.of("root"),h.model.selectedNode());
            h.model.createDocument(TYPE,"B"); UUID b=h.model.activeDocument().orElseThrow();
            h.model.selectNode("root");h.model.selectDocument(a);
            assertEquals(Optional.of("root"),h.model.selectedNode());
            assertEquals("unapplied",h.model.raw(new ProjectWorkbench.Address(a,"root","value"),"new"));
            h.model.selectDocument(b);assertEquals(Optional.of("root"),h.model.selectedNode());
        }
    }

    @Test void studioClipboardCapturesCopyTimeAndRejectsOtherDocumentsAndPendingRaw() throws Exception {
        var editor=new NoteEditor() {
            @Override public List<Node> nodes(String value) { return List.of(new Node("root",Optional.empty(),LABEL),new Node("slot",Optional.of("root"),LABEL)); }
            @Override public Optional<StudioDocument.Grid> studioGrid(String value,String node) {
                return Optional.of(new StudioDocument.Grid("root",LABEL,List.of(new StudioDocument.Column("a",LABEL,1)),
                        List.of(new StudioDocument.Cell("a",0,Optional.of("slot"),Optional.empty(),LABEL,List.of(),Optional.empty()))));
            }
            @Override public Object copyStudioCell(String value,String node) { return value; }
            @Override public String applyStudioCommand(String value,StudioDocument.Command command,Optional<Object> clipboard) { return (String)clipboard.orElseThrow(); }
        };
        try(var h=new Harness(registry(new NoteImporter(),editor))) {
            h.model.create(temporary.resolve("studio-clipboard"),"Studio");h.drain();h.model.createDocument(TYPE,"A");UUID a=h.model.activeDocument().orElseThrow();
            h.edit("copied");h.model.copyStudioCell(a,"slot");h.edit("later");
            var paste=new StudioDocument.Command(StudioDocument.Action.PASTE,List.of("slot"),Optional.empty());
            h.model.applyStudioCommand(a,paste);assertEquals("copied",h.model.documents().get(a).view().content());
            h.model.undo();assertEquals("later",h.model.documents().get(a).view().content());
            var address=new ProjectWorkbench.Address(a,"root","value");h.model.input(address,"pending");var before=h.model.documents().get(a).view();
            assertThrows(SessionRejection.class,()->h.model.applyStudioCommand(a,paste));assertEquals(before,h.model.documents().get(a).view());h.model.discardInput(address);
            h.model.createDocument(TYPE,"B");UUID b=h.model.activeDocument().orElseThrow();
            assertTrue(h.model.canPasteStudioCell(a));assertFalse(h.model.canPasteStudioCell(b));
            assertThrows(SessionRejection.class,()->h.model.applyStudioCommand(b,paste));assertEquals("new",h.model.documents().get(b).view().content());
        }
    }

    @Test void creatingAndAttachingADefinitionIsOneUndoAndFailedAttachmentPublishesNothing() throws Exception {
        var editor=new NoteEditor() {
            @Override public List<Node> nodes(String value) {
                var result=new ArrayList<Node>();result.add(new Node("root",Optional.empty(),LABEL));
                if(value.contains("|"))result.add(new Node(value.split("\\|")[1],Optional.of("root"),LABEL));return result;
            }
            @Override public List<ReferenceList> referenceLists(String value,String node) {
                var choices=value.contains("|")?List.of(new Choice(value.split("\\|")[1],LABEL)):List.<Choice>of();
                return List.of(new ReferenceList("refs",LABEL,LABEL,choices,value.endsWith("|attached")?List.of(choices.get(0).raw()):List.of()));
            }
            @Override public String replaceReferences(String value,String node,String property,List<String> selected) {
                if(value.startsWith("reject"))throw new IllegalArgumentException("attachment rejected");return value+"|attached";
            }
        };
        ObjectCreator<String> creator=new ObjectCreator<>() {
            public EditorId documentType(){return TYPE;}public Class<String> valueType(){return String.class;}
            public TextContent label(){return LABEL;}public TextContent category(){return LABEL;}
            public Target target(String value,Optional<String> selection){return Target.available("root");}
            public boolean supportsReference(String value,String node,String property){return node.equals("root")&&property.equals("refs");}
            public List<DocumentEditor.Field> fields(String value,String parent){return List.of();}
            public String create(String value,String parent,UUID identity,Map<String,String> raw){return value+"|"+identity;}
        };
        try(var h=new Harness(registry(new NoteImporter(),editor,creator))) {
            h.model.create(temporary.resolve("studio-attach"),"Studio");h.drain();h.model.createDocument(TYPE,"A");UUID a=h.model.activeDocument().orElseThrow();
            var address=new ProjectWorkbench.Address(a,"root","refs");assertEquals(1,h.model.creationChoicesForReference(address).size());
            var draft=h.model.beginObjectCreation(a,"root",id("test:creator_0"));h.model.createObject(draft,Map.of(),address);
            assertEquals(List.of(draft.identity().toString()),h.model.referenceLists(a,"root").get(0).selected());
            h.model.undo();assertEquals("new",h.model.documents().get(a).view().content());assertFalse(h.model.canUndo());
            h.model.redo();assertEquals(2,h.model.nodes(a).size());
            h.model.undo();h.edit("reject");var before=h.model.documents().get(a).view();
            var rejected=h.model.beginObjectCreation(a,"root",id("test:creator_0"));
            assertThrows(RuntimeException.class,()->h.model.createObject(rejected,Map.of(),address));assertEquals(before,h.model.documents().get(a).view());
        }
    }

    private Path emptyProject(String folder) throws Exception {
        Path path = temporary.resolve(folder);
        try (var store = LocalProjectStore.create(path)) {
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "Existing", Map.of(), Map.of(), Map.of(), null));
        }
        return path;
    }

    @Test void pinnedDocumentReadsSelectionAndEditsNeverSwitchTheActiveDocument() throws Exception {
        var editor = new NoteEditor() {
            @Override public List<Node> nodes(String value) {
                return List.of(new Node("root", Optional.empty(), LABEL), new Node("child", Optional.of("root"), LABEL));
            }
            @Override public String apply(String value, String node, String property, String raw) {
                if (!List.of("root", "child").contains(node)) throw new IllegalArgumentException("wrong node");
                return super.apply(value, "root", property, raw);
            }
            @Override public List<ReferenceList> referenceLists(String value, String node) {
                return List.of(new ReferenceList("refs", LABEL, LABEL,
                        List.of(new Choice("r1", LABEL), new Choice("r2", LABEL)),
                        List.of(value.startsWith("beta") ? "r2" : "r1")));
            }
        };
        try (var h = new Harness(registry(new NoteImporter(), editor))) {
            h.model.create(temporary.resolve("pinned-context"), "Pinned context"); h.drain();
            h.model.createDocument(TYPE, "A"); UUID a = h.model.activeDocument().orElseThrow();
            h.edit("alpha"); h.model.selectNode("root");
            h.model.createDocument(TYPE, "B"); UUID b = h.model.activeDocument().orElseThrow();
            h.edit("beta"); h.model.selectNode("child");
            h.model.selectDocument(a); var originalA = h.model.documents().get(a).view();
            assertEquals("root", h.model.selectedNode().orElseThrow());
            assertEquals("child", h.model.selectedNode(b).orElseThrow());
            assertEquals(List.of("root", "child"), h.model.nodes(b).stream().map(DocumentEditor.Node::id).toList());
            assertEquals("beta", h.model.fields(b, "child").get(0).raw());
            assertEquals(List.of("r2"), h.model.referenceLists(b, "child").get(0).selected());
            h.model.selectNode(b, "root");
            assertEquals(a, h.model.activeDocument().orElseThrow());
            assertEquals("root", h.model.selectedNode().orElseThrow());
            h.model.selectNode(b, "child");
            var address = new ProjectWorkbench.Address(b, "child", "detail");
            h.model.input(address, "B raw"); h.model.apply(address);
            assertEquals("beta\nB raw", h.model.documents().get(b).view().content());
            assertEquals(originalA, h.model.documents().get(a).view());
            assertEquals(a, h.model.activeDocument().orElseThrow());
            assertEquals("child", h.model.selectedNode(b).orElseThrow());
            UUID missing = UUID.randomUUID();
            assertTrue(h.model.nodes(missing).isEmpty()); assertTrue(h.model.fields(missing, "root").isEmpty());
            assertTrue(h.model.referenceLists(missing, "root").isEmpty()); assertTrue(h.model.selectedNode(missing).isEmpty());
            assertThrows(SessionRejection.class, () -> h.model.selectNode(missing, "root"));
            assertEquals(a, h.model.activeDocument().orElseThrow());
            h.model.selectDocument(b); assertEquals("child", h.model.selectedNode().orElseThrow());
            h.model.undo(); assertEquals("beta", h.model.documents().get(b).view().content());
            assertEquals(originalA, h.model.documents().get(a).view());
        }
    }

    @Test void choicesKeepTheirCapturedAddressAndRejectUnavailableValuesWithoutLosingDraftOrHistory() throws Exception {
        var registry=registry(new NoteImporter(),new ChoiceEditor());
        Path path=temporary.resolve("choices");UUID a;
        try(var h=new Harness(registry)) {
            h.model.create(path,"Choices");h.drain();h.model.createDocument(TYPE,"A");a=h.model.activeDocument().orElseThrow();
            var address=new ProjectWorkbench.Address(a,"root","selection");
            h.model.input(address,"green");
            assertEquals("new",h.model.documents().get(a).view().content());
            h.model.discardInput(address);assertFalse(h.model.pending(address));assertFalse(h.model.canUndo());
            h.model.input(address,"green");
            var detail=new ProjectWorkbench.Address(a,"root","detail");
            h.model.input(detail,"old item draft");var pendingBefore=h.model.documents().get(a).view();
            assertThrows(SessionRejection.class,()->h.model.apply(address));
            assertThrows(SessionRejection.class,()->h.model.apply(detail));
            assertEquals(pendingBefore,h.model.documents().get(a).view());
            assertEquals("old item draft",h.model.raw(detail,""));assertEquals("green",h.model.raw(address,"new"));
            h.model.discardInput(detail);
            h.model.createDocument(TYPE,"B");UUID b=h.model.activeDocument().orElseThrow();
            h.model.apply(address);
            assertEquals("green",h.model.documents().get(a).view().content());
            assertEquals("new",h.model.documents().get(b).view().content());
            h.model.selectDocument(a);h.model.undo();assertEquals("new",h.model.documents().get(a).view().content());
            h.model.redo();assertEquals("green",h.model.documents().get(a).view().content());
            h.model.input(address,"forged");var before=h.model.documents().get(a).view();
            SessionRejection rejected=assertThrows(SessionRejection.class,()->h.model.apply(address));
            assertEquals(id("editor:workbench/invalid_choice"),rejected.problems().get(0).code());
            assertEquals(before,h.model.documents().get(a).view());assertEquals("forged",h.model.raw(address,"green"));
            h.model.discardInput(address);
            // The same target field still exists but a valid-looking old choice was removed.
            h.model.input(address,"green");var handle=note(h.model.documents().get(a));
            handle.edit(handle.view().token(),LABEL,value->"green\nrestricted");
            before=handle.view();
            assertThrows(SessionRejection.class,()->h.model.apply(address));assertEquals(before,handle.view());
            assertEquals("green",h.model.raw(address,"new"));h.model.discardInput(address);
            h.model.input(address,"blue");h.model.apply(address);
            h.model.saveAll();h.drain();assertFalse(h.model.unsaved());
        }
        try(var h=new Harness(registry)) {
            h.model.open(path);h.drain();assertEquals("blue\nrestricted",h.model.documents().get(a).view().content());
        }
    }

    @Test void choiceDescriptorsAreImmutableAndRejectDuplicateTokensOrUnboundedCatalogs() {
        var option=new DocumentEditor.Choice("blue",LABEL);var options=new ArrayList<>(List.of(option));
        var field=new DocumentEditor.Field("choice",LABEL,DocumentEditor.Kind.CHOICE,"blue",LABEL,options);
        options.clear();assertEquals(List.of(option),field.choices());
        assertThrows(UnsupportedOperationException.class,()->field.choices().clear());
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.Field("choice",LABEL,DocumentEditor.Kind.CHOICE,"blue",LABEL,List.of(option,option)));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.Field("choice",LABEL,DocumentEditor.Kind.TEXT,"blue",LABEL,List.of(option)));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.Choice("x".repeat(4097),LABEL));
        var many=new ArrayList<DocumentEditor.Choice>();for(int i=0;i<8193;i++)many.add(new DocumentEditor.Choice("c"+i,LABEL));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.Field("choice",LABEL,DocumentEditor.Kind.CHOICE,"blue",LABEL,many));
    }

    @Test void referencesCaptureTargetAndExactRevisionAndKeepInvalidOrPendingChangesAtomic() throws Exception {
        var registry=registry(new NoteImporter(),new ReferenceEditor());UUID a;Path path=temporary.resolve("references");
        try(var h=new Harness(registry)) {
            h.model.create(path,"References");h.drain();h.model.createDocument(TYPE,"A");a=h.model.activeDocument().orElseThrow();h.model.selectNode("root");
            var address=new ProjectWorkbench.Address(a,"root","refs");
            var draft=h.model.beginReferences(address);var before=h.model.documents().get(a).view();
            h.model.cancelReferences();assertEquals(before,h.model.documents().get(a).view());
            var cancelled=draft;assertThrows(SessionRejection.class,()->h.model.replaceReferences(cancelled,List.of("r2")));
            draft=h.model.beginReferences(address);var captured=draft;
            assertThrows(IllegalArgumentException.class,()->h.model.replaceReferences(captured,List.of("r1","r1")));
            assertThrows(IllegalArgumentException.class,()->h.model.replaceReferences(captured,List.of("foreign")));
            assertEquals(before,h.model.documents().get(a).view());
            var scalar=new ProjectWorkbench.Address(a,"root","value");h.model.input(scalar,"raw");
            assertThrows(SessionRejection.class,()->h.model.replaceReferences(captured,List.of("r2","r1")));
            assertThrows(SessionRejection.class,()->h.model.beginReferences(address));
            assertEquals("raw",h.model.raw(scalar,""));h.model.discardInput(scalar);
            h.model.createDocument(TYPE,"B");UUID b=h.model.activeDocument().orElseThrow();
            h.model.replaceReferences(captured,List.of("r2","r1"));
            assertEquals("r2,r1",h.model.documents().get(a).view().content());assertEquals("new",h.model.documents().get(b).view().content());
            assertThrows(SessionRejection.class,()->h.model.replaceReferences(captured,List.of()));
            h.model.selectDocument(a);h.model.undo();assertEquals("new",h.model.documents().get(a).view().content());
            h.model.redo();assertEquals("r2,r1",h.model.documents().get(a).view().content());
            draft=h.model.beginReferences(address);var stale=draft;h.model.input(scalar,"r1");h.model.apply(scalar);
            before=h.model.documents().get(a).view();
            assertThrows(net.ptcrys.fpsmatch.editor.core.documents.DocumentRejection.class,()->h.model.replaceReferences(stale,List.of("r2")));
            assertEquals(before,h.model.documents().get(a).view());h.model.cancelReferences();
            var current=h.model.beginReferences(address);h.model.replaceReferences(current,List.of("r2","r1"));
            var unchanged=h.model.beginReferences(address);before=h.model.documents().get(a).view();h.model.replaceReferences(unchanged,List.of("r2","r1"));
            assertEquals(before,h.model.documents().get(a).view());
            h.model.saveAll();h.drain();assertFalse(h.model.unsaved());
        }
        try(var h=new Harness(registry)){h.model.open(path);h.drain();assertEquals("r2,r1",h.model.documents().get(a).view().content());}
    }

    @Test void referenceDescriptorsPreserveOrderImmutablyBeyondScalarBudgetAndRejectInvalidSets() {
        var candidates=new ArrayList<DocumentEditor.Choice>();var ids=new ArrayList<String>();
        for(int i=0;i<8192;i++){String id="reference-"+i;ids.add(id);candidates.add(new DocumentEditor.Choice(id,LABEL));}
        var list=new DocumentEditor.ReferenceList("refs",LABEL,LABEL,candidates,ids);candidates.clear();ids.clear();
        assertEquals(8192,list.selected().size());assertTrue(String.join(",",list.selected()).length()>4096);
        assertThrows(UnsupportedOperationException.class,()->list.selected().clear());
        var c=new DocumentEditor.Choice("a",LABEL);
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.ReferenceList("refs",LABEL,LABEL,List.of(c,c),List.of()));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.ReferenceList("refs",LABEL,LABEL,List.of(c),List.of("a","a")));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.ReferenceList("refs",LABEL,LABEL,List.of(c),List.of("foreign")));
        candidates.addAll(list.candidates());candidates.add(new DocumentEditor.Choice("overflow",LABEL));
        assertThrows(IllegalArgumentException.class,()->new DocumentEditor.ReferenceList("refs",LABEL,LABEL,candidates,List.of()));
    }

    private static final class ReferenceEditor extends NoteEditor {
        @Override public List<ReferenceList> referenceLists(String value,String node) {
            if(!node.equals("root"))return List.of();
            return List.of(new ReferenceList("refs",LABEL,LABEL,List.of(new Choice("r1",LABEL),new Choice("r2",LABEL)),
                    value.equals("new")?List.of():List.of(value.split(","))));
        }
        @Override public String replaceReferences(String value,String node,String property,List<String> selected) {
            if(!node.equals("root")||!property.equals("refs"))throw new IllegalArgumentException();
            return selected.isEmpty()?"new":String.join(",",selected);
        }
    }

    private static ExtensionRegistry registry(NoteImporter importer) {
        return registry(importer,new NoteEditor());
    }
    @SafeVarargs
    private static ExtensionRegistry registry(NoteImporter importer,DocumentEditor<String> editor,ObjectCreator<String>... creators) {
        var api = new ApiVersion(1, 0);
        var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.define(ResourceProducers.KIND); builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE), OWNER,
                Contribution.Side.COMMON, Set.of()), new NoteType());
        DocumentEditors.register(builder, TYPE, OWNER, editor);
        DocumentEditors.registerImporter(builder, IMPORTER, OWNER, importer);
        for(int i=0;i<creators.length;i++)DocumentEditors.registerCreator(builder,id("test:creator_"+i),OWNER,creators[i]);
        ProjectTemplates.registerCategory(builder, id("test:category"), OWNER, LABEL);
        ProjectTemplates.register(builder, TYPE, OWNER, new ProjectTemplate<String>() {
            public EditorId category() { return id("test:category"); }
            public TextContent label() { return LABEL; }
            public EditorId documentType() { return TYPE; }
            public Class<String> valueType() { return String.class; }
            public List<DocumentEditor.Field> fields() { return List.of(new DocumentEditor.Field("body", LABEL, DocumentEditor.Kind.TEXT, "new", LABEL)); }
            public String create(ExtensionRegistry registry, Map<String,String> raw) { return raw.get("body"); }
        });
        return builder.freeze();
    }

    private static final class NoteType implements DocumentType<String> {
        public Class<String> valueType() { return String.class; }
        public EditorId codecId() { return id("test:utf8"); }
        public int schemaVersion() { return 1; }
        public Set<Integer> readableSchemas() { return Set.of(1); }
        public String create() { return "new"; }
        public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        public String snapshot(String value) { return value; }
        public List<Problem> validate(String value) {
            return value.isBlank() ? List.of(new Problem(Problem.Severity.ERROR, id("test:blank"), "value", LABEL)) : List.of();
        }
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public DocumentDependencies dependencies(String value) { return new DocumentDependencies(Set.of(), Set.of(), Set.of()); }
    }

    private static class NoteEditor implements DocumentEditor<String> {
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        public List<Node> nodes(String value) { return List.of(new Node("root", Optional.empty(), LABEL)); }
        public List<Field> fields(String value, String node) {
            String[] parts = value.split("\n", 2);
            return List.of(new Field("value", LABEL, Kind.TEXT, parts[0], LABEL),
                    new Field("detail", LABEL, Kind.TEXT, parts.length == 2 ? parts[1] : "", LABEL));
        }
        public String apply(String value, String node, String property, String raw) {
            if (!node.equals("root")) throw new IllegalArgumentException("wrong node");
            String[] parts = value.split("\n", 2);
            return switch (property) {
                case "value" -> raw + (parts.length == 2 ? "\n" + parts[1] : "");
                case "detail" -> parts[0] + "\n" + raw;
                default -> throw new IllegalArgumentException("wrong field");
            };
        }
    }

    private static final class AxisEditor extends NoteEditor {
        private String[] axes(String value) { return value.equals("new") ? new String[]{"0", "0", "0", "true"} : value.split(",", -1); }
        @Override public List<Field> fields(String value, String node) {
            String[] parts = axes(value); var result = new ArrayList<Field>();
            for (int i = 0; i < 3; i++) {
                String axis = List.of("x", "y", "z").get(i);
                result.add(new Field(axis, LABEL, Kind.INTEGER, parts[i], LABEL).presented(
                        new Presentation("overview", LABEL, "position", LABEL, "position", LABEL,
                                Accent.valueOf(axis.toUpperCase(Locale.ROOT)), Optional.of(new Dependency("enabled", "true", LABEL)))));
            }
            result.add(new Field("enabled", LABEL, Kind.BOOLEAN, parts[3], LABEL).presented(
                    new Presentation("overview", LABEL, "settings", LABEL, "enabled", LABEL, Accent.NONE)));
            return List.copyOf(result);
        }
        @Override public String apply(String value, String node, String property, String raw) {
            if (!node.equals("root")) throw new IllegalArgumentException("wrong node");
            String[] parts = axes(value);
            if (property.equals("enabled")) {
                if (!raw.equals("true") && !raw.equals("false")) throw new IllegalArgumentException("invalid boolean");
                parts[3] = raw;
            } else {
                int index = List.of("x", "y", "z").indexOf(property);
                if (index < 0) throw new IllegalArgumentException("wrong axis");
                parts[index] = Integer.toString(Integer.parseInt(raw));
            }
            return String.join(",", parts);
        }
    }

    private static final class ChoiceEditor extends NoteEditor {
        @Override public List<Field> fields(String value,String node) {
            var choices=new ArrayList<>(List.of(new Choice("blue",LABEL)));
            if(!value.endsWith("\nrestricted"))choices.add(new Choice("green",LABEL));
            String[] parts=value.split("\n",2);
            return List.of(new Field("selection",LABEL,Kind.CHOICE,parts[0],LABEL,choices),
                    new Field("detail",LABEL,Kind.TEXT,parts.length==2?parts[1]:"",LABEL));
        }
        @Override public String apply(String value,String node,String property,String raw) {
            return super.apply(value,node,property.equals("selection")?"value":property,raw);
        }
    }

    private static final class NoteImporter implements DocumentImporter<String> {
        int calls;
        List<Problem> diagnostics=List.of();
        public EditorId documentType() { return TYPE; }
        public Class<String> valueType() { return String.class; }
        public TextContent label() { return LABEL; }
        public List<Input> inputs() { return List.of(new Input("source", LABEL, false, true)); }
        public Result<String> convert(ExtensionRegistry registry, Map<String, byte[]> sources, UUID seed) {
            calls++; return new Result<>(Optional.of(new String(sources.get("source"), StandardCharsets.UTF_8)), diagnostics);
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        boolean rejectNext;
        public void execute(Runnable task) {
            if(rejectNext){rejectNext=false;throw new java.util.concurrent.RejectedExecutionException("test executor refusal");}
            tasks.addLast(task);
        }
        int size() { return tasks.size(); }
        void next() { assertFalse(tasks.isEmpty(), "expected a queued task"); tasks.removeFirst().run(); }
    }

    private static final class Harness implements AutoCloseable {
        final ManualExecutor io = new ManualExecutor(), completion = new ManualExecutor();
        final ProjectWorkbench model;
        Harness(ExtensionRegistry registry) { model = new ProjectWorkbench(registry, io, completion); }
        void edit(String value) {
            var address = new ProjectWorkbench.Address(model.activeDocument().orElseThrow(), "root", "value");
            model.input(address, value); model.apply(address);
        }
        void drain() {
            for (int count = 0; io.size() + completion.size() != 0; count++) {
                assertTrue(count < 32, "unexpected asynchronous task loop");
                if (io.size() != 0) io.next();
                if (completion.size() != 0) completion.next();
            }
            assertFalse(model.busy());
        }
        public void close() {
            drain();
            if (model.hasProject()) { model.closeProject(true); drain(); }
        }
    }
    private static EditorId id(String value) { return EditorId.parse(value); }
}
