package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench.PermissionValidity.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore.World;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.types.*;
import net.ptcrys.fpsmatch.editor.core.worldio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorldWorkbenchTest {
    @TempDir Path temporary;
    private static final EditorId OWNER=id("test:project_world"), REQUIRED=id("test:map"), OPTIONAL=id("test:note");
    private static final TextContent LABEL=new TextContent.Literal("Project world fixture");
    private static final Map<String,String> INPUT=Map.of("body","saved initial document");

    @Test void requiredTemplateRejectsMissingSaveBeforePublishingAnything() throws Exception {
        try(var h=new Harness()){
            Path project=temporary.resolve("missing-selection");
            assertThrows(IllegalArgumentException.class,()->h.worlds.create(project,"Map",REQUIRED,"Scene",INPUT,null));
            assertFalse(Files.exists(project));assertFalse(h.model.hasProject());
            assertFalse(h.model.busy());assertFalse(h.worlds.busy());
            assertEquals(0,h.io.size());assertEquals(0,h.modelIo.size());
        }
    }

    @Test void creationVerifiesSaveBeforePublicationThenBindsThePublishedProjectIdentity() throws Exception {
        try(var h=new Harness()){
            World world=h.save("chosen");Path project=temporary.resolve("created");
            h.worlds.create(project,"Map",REQUIRED,"Scene",INPUT,world);
            assertEquals(ProjectWorldWorkbench.State.CHECKING,h.worlds.state());
            assertFalse(Files.exists(project));assertFalse(h.model.busy());
            h.io.next();assertFalse(Files.exists(project));h.completion.next();
            assertTrue(h.model.busy());assertEquals(ProjectWorldWorkbench.State.BINDING,h.worlds.state());
            assertFalse(h.worlds.canCancelPreparation());h.worlds.cancelPreparation();
            h.drainModel();UUID published=h.model.projectId().orElseThrow();
            assertEquals("saved initial document",h.model.documents().values().iterator().next().view().content());
            assertTrue(h.store.read(project,published).isEmpty());
            h.worlds.tick();assertEquals(ProjectWorldWorkbench.State.BINDING,h.worlds.state());
            h.io.next();h.completion.next();
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());
            var binding=h.worlds.binding().orElseThrow();
            assertEquals(published,binding.projectId());assertEquals(project,binding.project());assertEquals(world,binding.world());
            assertEquals(binding,h.store.read(project,published).orElseThrow());
            assertFalse(h.model.unsaved());assertFalse(h.worlds.busy());
        }
    }

    @Test void replacedSelectionFailsPreflightWithoutCreatingTheProjectDirectory() throws Exception {
        try(var h=new Harness()){
            World selected=h.save("selected");h.replace(selected.root());Path project=temporary.resolve("never-published");
            h.worlds.create(project,"Map",REQUIRED,"Scene",INPUT,selected);h.settle();
            assertEquals(ProjectWorldWorkbench.State.BLOCKED,h.worlds.state());
            assertTrue(h.worlds.failure().isPresent());assertFalse(h.model.hasProject());
            assertFalse(Files.exists(project));assertEquals(0,h.modelIo.size());
        }
    }

    @Test void cancellingCreationPreparationBeforeIoDoesNotPublishWhenVerificationArrives() throws Exception {
        try(var h=new Harness()){
            World world=h.save("cancel-before-io");Path project=temporary.resolve("cancelled-project");
            h.worlds.create(project,"Map",REQUIRED,"Scene",INPUT,world);
            assertTrue(h.worlds.canCancelPreparation());h.worlds.cancelPreparation();
            assertFalse(h.worlds.canCancelPreparation());assertFalse(h.worlds.busy());
            h.io.next();h.completion.next();h.worlds.tick();
            assertEquals(ProjectWorldWorkbench.State.NONE,h.worlds.state());
            assertFalse(Files.exists(project));assertFalse(h.model.hasProject());assertFalse(h.model.pendingCreation());
            assertEquals(0,h.modelIo.size());assertTrue(h.worlds.failure().isEmpty());
        }
    }

    @Test void completedCancelledPreflightCannotPublishOrReplaceTheNextCreation() throws Exception {
        try(var h=new Harness()){
            World abandoned=h.save("abandoned"),chosen=h.save("chosen-next");
            Path oldProject=temporary.resolve("abandoned-project"),newProject=temporary.resolve("next-project");
            h.worlds.create(oldProject,"Old",REQUIRED,"Scene",INPUT,abandoned);h.io.next();
            assertTrue(h.worlds.canCancelPreparation());h.worlds.cancelPreparation();
            h.worlds.create(newProject,"Next",REQUIRED,"Scene",INPUT,chosen);
            h.completion.next();
            assertFalse(h.model.hasProject());assertEquals(0,h.modelIo.size());
            assertFalse(Files.exists(oldProject));assertFalse(Files.exists(newProject));
            h.settle();
            assertFalse(Files.exists(oldProject));assertEquals(newProject,h.model.directory().orElseThrow());
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());
            assertEquals(chosen,h.worlds.binding().orElseThrow().world());
        }
    }

    @Test void optionalTemplateCanCreateWithoutAnAuthoringAssociation() throws Exception {
        try(var h=new Harness()){
            h.worlds.create(temporary.resolve("note"),"Notes",OPTIONAL,"Note",INPUT,null);h.settle();
            assertTrue(h.model.hasProject());assertFalse(h.worlds.requiresWorld());
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());assertTrue(h.worlds.binding().isEmpty());
        }
    }

    @Test void legacyRequiredProjectAllowsOnlyItsFirstBindingAndRejectsAnotherRunningWorld() throws Exception {
        try(var h=new Harness()){
            World original=h.save("original"),other=h.save("other");h.legacy("legacy");
            assertTrue(h.worlds.requiresWorld());assertEquals(ProjectWorldWorkbench.State.UNBOUND,h.worlds.state());
            assertFalse(h.worlds.allows(original.root()));assertTrue(h.worlds.permission(other.root()).isEmpty());
            h.worlds.bind(original);h.settle();
            var permit=h.worlds.permission(original.root()).orElseThrow();
            assertTrue(h.worlds.current(permit));assertDoesNotThrow(()->h.worlds.verify(permit));
            assertTrue(h.worlds.entryAllowed(original.savesRoot(),original.levelId()));
            assertFalse(h.worlds.entryAllowed(other.savesRoot(),other.levelId()));
            assertFalse(h.worlds.entryAllowed(original.savesRoot(),"../original"));
            assertFalse(h.worlds.entryAllowed(original.savesRoot(),"nested\\original"));
            assertFalse(h.worlds.allows(other.root()));assertTrue(h.worlds.permission(other.root()).isEmpty());
            assertThrows(IllegalStateException.class,()->h.worlds.bind(other));
            assertEquals(original,h.worlds.binding().orElseThrow().world());
        }
    }

    @Test void runtimeAdmissionRequiresTheLiveBorrowAndCurrentAuthoringCapture() throws Exception {
        try(var h=new Harness()){
            World world=h.save("runtime-source");h.legacy("runtime-project");h.worlds.bind(world);h.settle();
            var permit=h.worlds.permission(world.root()).orElseThrow();
            var source=h.model.borrowSavedRuntimeSource();
            assertDoesNotThrow(()->h.worlds.requireRuntimeSource(permit,source));
            var copy=new ProjectWorkbench.SavedRuntimeSource(source.access(),source.registry(),source.saved(),source.documentId(),source.documentName());
            assertThrows(IllegalStateException.class,()->h.worlds.requireRuntimeSource(permit,copy));
            h.model.releaseSavedRuntimeSource(source);
            assertThrows(IllegalStateException.class,()->h.worlds.requireRuntimeSource(permit,source));
            h.worlds.refresh();
            assertEquals(GENERATION_CHANGED,h.worlds.validity(permit));
            assertFalse(h.worlds.current(permit));
            h.settle();
            assertEquals(GENERATION_CHANGED,h.worlds.validity(permit));
            var next=h.model.borrowSavedRuntimeSource();
            assertThrows(IllegalStateException.class,()->h.worlds.requireRuntimeSource(permit,next));
            var current=h.worlds.permission(world.root()).orElseThrow();
            assertDoesNotThrow(()->h.worlds.requireRuntimeSource(current,next));
            h.model.releaseSavedRuntimeSource(next);
        }
    }

    @Test void permissionValidityRejectsMissingAndForeignCapturesWithoutChangingCurrentAuthority() throws Exception {
        try(var h=new Harness()){
            World world=h.save("permission-identity");h.legacy("permission-project");h.worlds.bind(world);h.settle();
            var permit=h.worlds.permission(world.root()).orElseThrow();
            var other=new ProjectWorldWorkbench(h.model,h.store,h.io,h.completion);
            long version=h.worlds.version();
            assertEquals(FOREIGN_CAPTURE,h.worlds.validity(null));assertFalse(h.worlds.current(null));
            assertEquals(FOREIGN_CAPTURE,other.validity(permit));assertFalse(other.current(permit));
            assertEquals(CURRENT,h.worlds.validity(permit));assertTrue(h.worlds.current(permit));
            assertEquals(version,h.worlds.version());assertEquals(0,h.io.size());
        }
    }

    @Test void savingAnEditedDocumentPreservesItsExistingWorldPermission() throws Exception {
        try(var h=new Harness()){
            World world=h.save("save-permission");h.legacy("save-permission-project");h.worlds.bind(world);h.settle();
            var permit=h.worlds.permission(world.root()).orElseThrow();
            var oldCommit=h.model.savedVersion().orElseThrow();
            UUID document=h.model.activeDocument().orElseThrow();
            var address=new ProjectWorkbench.Address(document,"root","body");
            h.model.input(address,"saved while runtime is active");h.model.apply(address);h.model.saveDocument(document);
            h.worlds.tick();assertEquals(CURRENT,h.worlds.validity(permit));assertTrue(h.worlds.current(permit));
            h.modelIo.next();h.modelCompletion.next();
            h.worlds.tick();assertEquals(CURRENT,h.worlds.validity(permit));assertTrue(h.worlds.current(permit));
            h.settle();
            assertNotEquals(oldCommit,h.model.savedVersion().orElseThrow());
            assertEquals(CURRENT,h.worlds.validity(permit));assertTrue(h.worlds.current(permit));
            assertFalse(h.model.unsaved());
        }
    }

    @Test void permissionValidityDistinguishesProjectChangeBeforeScopeSynchronization() throws Exception {
        try(var h=new Harness()){
            World world=h.save("closed-permission");h.legacy("closed-permission-project");h.worlds.bind(world);h.settle();
            var permit=h.worlds.permission(world.root()).orElseThrow();
            h.model.closeProject(false);h.drainModel();
            assertEquals(PROJECT_CHANGED,h.worlds.validity(permit));assertFalse(h.worlds.current(permit));
            h.worlds.tick();
            assertEquals(GENERATION_CHANGED,h.worlds.validity(permit));assertFalse(h.worlds.current(permit));
        }
    }

    @Test void conflictedSaveReportsProtectedProjectBeforeScopeOrBindingChecks() throws Exception {
        try(var h=new Harness()){
            World world=h.save("protected-permission");h.legacy("protected-permission-project");h.worlds.bind(world);h.settle();
            var permit=h.worlds.permission(world.root()).orElseThrow();
            Path current=h.model.directory().orElseThrow().resolve("CURRENT");byte[] prior=Files.readAllBytes(current);
            UUID document=h.model.activeDocument().orElseThrow();
            var address=new ProjectWorkbench.Address(document,"root","body");
            h.model.input(address,"next saved baseline");h.model.apply(address);h.model.saveDocument(document);h.settle();
            assertEquals(CURRENT,h.worlds.validity(permit));
            h.model.input(address,"conflicting draft");h.model.apply(address);h.model.saveDocument(document);
            h.modelIo.next();h.modelCompletion.next();
            Files.write(current,prior);h.settle();
            assertEquals(ProjectWorkbench.text("conflict"),h.model.status());assertTrue(h.model.protectedMode());
            assertEquals(PROJECT_PROTECTED,h.worlds.validity(permit));assertFalse(h.worlds.current(permit));
            assertTrue(h.worlds.permission(world.root()).isEmpty());
        }
    }

    @Test void permissionValidityDistinguishesAssociationVerificationFromChangedBinding() throws Exception {
        try(var h=new Harness()){
            World world=h.save("relocated-permission");h.legacy("relocated-permission-project");h.worlds.bind(world);h.settle();
            var permit=h.worlds.permission(world.root()).orElseThrow();
            Path moved=world.root().resolveSibling("relocated-permission-new");Files.move(world.root(),moved);
            h.worlds.locateOriginal(moved);
            assertEquals(ASSOCIATION_NOT_READY,h.worlds.validity(permit));assertFalse(h.worlds.current(permit));
            h.settle();
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());
            assertEquals(ASSOCIATION_CHANGED,h.worlds.validity(permit));assertFalse(h.worlds.current(permit));
            assertEquals(CURRENT,h.worlds.validity(h.worlds.permission(moved).orElseThrow()));
        }
    }

    @Test void sameNamedReplacementRevokesAdmissionAndCannotBecomeTheOriginalBinding() throws Exception {
        try(var h=new Harness()){
            World original=h.save("same-name");h.legacy("replacement");h.worlds.bind(original);h.settle();
            var binding=h.worlds.binding().orElseThrow();var permit=h.worlds.permission(original.root()).orElseThrow();
            Path moved=h.replace(original.root());
            assertThrows(IOException.class,()->h.worlds.verify(permit));
            h.worlds.refresh();h.settle();
            assertEquals(ProjectWorldWorkbench.State.BLOCKED,h.worlds.state());assertEquals(binding,h.worlds.binding().orElseThrow());
            assertFalse(h.worlds.current(permit));assertFalse(h.worlds.allows(original.root()));
            h.worlds.locateOriginal(original.root());h.settle();
            assertEquals(ProjectWorldWorkbench.State.BLOCKED,h.worlds.state());assertEquals(binding,h.worlds.binding().orElseThrow());
            var failure=assertInstanceOf(ProjectWorldStore.BindingException.class,h.worlds.failure().orElseThrow());
            assertEquals(ProjectWorldStore.Code.ALREADY_BOUND,failure.code());
            h.worlds.locateOriginal(moved);h.settle();
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());
            assertEquals(original.identity(),h.worlds.binding().orElseThrow().world().identity());
            assertEquals(moved,h.worlds.binding().orElseThrow().world().root());
            assertFalse(h.worlds.allows(original.root()));assertTrue(h.worlds.allows(moved));
        }
    }

    @Test void cancelledInspectionDropsAnAlreadyComputedSuccess() throws Exception {
        try(var h=new Harness()){
            World world=h.save("cancelled");var accepted=new ArrayList<World>();
            h.worlds.inspect(world.root(),world.label(),accepted::add);h.io.next();
            h.worlds.cancelInspection();assertFalse(h.worlds.busy());h.completion.next();h.worlds.tick();
            assertTrue(accepted.isEmpty());assertTrue(h.worlds.binding().isEmpty());assertTrue(h.worlds.failure().isEmpty());
            assertEquals(ProjectWorldWorkbench.State.NONE,h.worlds.state());
        }
    }

    @Test void cancelledInspectionFailureCannotOverwriteTheNextSelection() throws Exception {
        try(var h=new Harness()){
            World world=h.save("latest");var accepted=new ArrayList<World>();
            h.worlds.inspect(world.savesRoot().resolve("missing"),"Old missing save",ignored->fail("Cancelled callback ran"));
            h.worlds.cancelInspection();h.worlds.inspect(world.root(),world.label(),accepted::add);
            h.io.next();h.completion.next();assertTrue(accepted.isEmpty());assertTrue(h.worlds.failure().isEmpty());
            h.io.next();h.completion.next();h.worlds.tick();
            assertEquals(List.of(world),accepted);assertFalse(h.worlds.busy());assertTrue(h.worlds.failure().isEmpty());
        }
    }

    @Test void inspectionCompletionAfterProjectChangeCannotFillTheRetiredDraftBeforeTick() throws Exception {
        try(var h=new Harness()){
            World world=h.save("inspection");var accepted=new ArrayList<World>();
            h.worlds.inspect(world.root(),world.label(),accepted::add);h.io.next();
            h.model.create(temporary.resolve("opened-while-inspecting"),"Other",REQUIRED,"Scene",INPUT);h.drainModel();
            h.completion.next();
            assertTrue(accepted.isEmpty(),"Completion must synchronize the actual project before accepting a selection");
            h.settle();assertEquals(ProjectWorldWorkbench.State.UNBOUND,h.worlds.state());assertTrue(h.worlds.binding().isEmpty());
        }
    }

    @Test void staleReadCompletionCannotExposeThePreviousProjectsBindingBeforeTick() throws Exception {
        try(var h=new Harness()){
            World first=h.save("first"),second=h.save("second");
            Path firstProject=temporary.resolve("first-project"),secondProject=temporary.resolve("second-project");
            h.model.create(firstProject,"First",REQUIRED,"Scene",INPUT);h.drainModel();
            h.store.bind(firstProject,h.model.projectId().orElseThrow(),first);
            h.worlds.tick();h.io.next();
            h.model.closeProject(false);h.drainModel();
            h.model.create(secondProject,"Second",REQUIRED,"Scene",INPUT);h.drainModel();
            UUID secondId=h.model.projectId().orElseThrow();h.store.bind(secondProject,secondId,second);
            h.completion.next();
            assertTrue(h.worlds.binding().isEmpty(),"A late read must not publish the previous project's binding");
            h.settle();assertEquals(secondId,h.worlds.binding().orElseThrow().projectId());
            assertEquals(second,h.worlds.binding().orElseThrow().world());assertFalse(h.worlds.allows(first.root()));
        }
    }

    @Test void lateSuccessfulBindStaysOnItsPublishedProjectAfterSwitching() throws Exception {
        try(var h=new Harness()){
            World first=h.save("bound-first");h.legacy("bind-first");
            Path firstProject=h.model.directory().orElseThrow();UUID firstId=h.model.projectId().orElseThrow();
            h.worlds.bind(first);h.io.next();
            h.model.closeProject(false);h.drainModel();
            h.model.create(temporary.resolve("bind-second"),"Second",REQUIRED,"Scene",INPUT);h.drainModel();
            UUID secondId=h.model.projectId().orElseThrow();h.completion.next();h.settle();
            assertEquals(ProjectWorldWorkbench.State.UNBOUND,h.worlds.state());assertTrue(h.worlds.binding().isEmpty());
            assertEquals(first,h.store.read(firstProject,firstId).orElseThrow().world());
            assertTrue(h.store.read(h.model.directory().orElseThrow(),secondId).isEmpty());
            assertFalse(h.worlds.allows(first.root()));
        }
    }

    @Test void bindingFailureRetainsThePublishedProjectAndAllowsFirstBindingRecovery() throws Exception {
        try(var h=new Harness()){
            World world=h.save("bind-failure");Path project=temporary.resolve("retained-project");
            h.worlds.create(project,"Map",REQUIRED,"Scene",INPUT,world);
            h.io.next();h.completion.next();h.drainModel();
            UUID published=h.model.projectId().orElseThrow();byte[] current=Files.readAllBytes(project.resolve("CURRENT"));
            h.identities.blocked=world.root();h.worlds.tick();h.io.next();h.completion.next();
            assertEquals(ProjectWorldWorkbench.State.BLOCKED,h.worlds.state());
            assertSame(h.identities.denied,h.worlds.failure().orElseThrow());
            assertTrue(h.model.hasProject());assertEquals(published,h.model.projectId().orElseThrow());
            assertEquals(1,h.model.documents().size());assertFalse(h.model.unsaved());
            assertArrayEquals(current,Files.readAllBytes(project.resolve("CURRENT")));
            assertTrue(h.store.read(project,published).isEmpty());assertFalse(h.worlds.allows(world.root()));
            h.identities.blocked=null;h.worlds.refresh();h.settle();
            assertEquals(ProjectWorldWorkbench.State.UNBOUND,h.worlds.state());
            h.worlds.bind(world);h.settle();assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());
            assertEquals(published,h.worlds.binding().orElseThrow().projectId());
            assertArrayEquals(current,Files.readAllBytes(project.resolve("CURRENT")));
        }
    }

    @Test void unknownPublicationRechecksTheExistingRecordWithoutPublishingAgain() throws Exception {
        try(var h=new Harness()){
            World world=h.save("unknown");h.legacy("unknown-project");
            h.identities.failBindingReadOnce=true;h.worlds.bind(world);h.settle();
            assertEquals(ProjectWorldWorkbench.State.UNKNOWN,h.worlds.state());
            var failure=assertInstanceOf(ProjectWorldStore.BindingException.class,h.worlds.failure().orElseThrow());
            assertEquals(ProjectWorldStore.Code.UNKNOWN,failure.code());assertFalse(h.worlds.allows(world.root()));
            assertTrue(h.model.hasProject());var persisted=h.store.read(h.model.directory().orElseThrow(),h.model.projectId().orElseThrow()).orElseThrow();
            Path record;try(var files=Files.walk(h.local)){record=files.filter(path->path.getFileName().toString().equals("binding.json")).findFirst().orElseThrow();}
            byte[] bytes=Files.readAllBytes(record);
            h.worlds.refresh();h.settle();
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());assertEquals(persisted,h.worlds.binding().orElseThrow());
            assertTrue(h.worlds.failure().isEmpty());assertArrayEquals(bytes,Files.readAllBytes(record));
            assertTrue(h.store.readHistory(persisted.project(),persisted.projectId()).orElseThrow().relocations().isEmpty());
        }
    }

    @Test void uncertainRelocationRejectsAnotherWriteUntilReadOnlyRefreshConfirmsThePublishedRevision() throws Exception {
        try(var h=new Harness()){
            World original=h.save("relocation-unknown");h.legacy("relocation-project");
            h.worlds.bind(original);h.settle();var binding=h.worlds.binding().orElseThrow();
            Path moved=original.root().resolveSibling("relocated-original");Files.move(original.root(),moved);
            h.identities.failRecordReadOnce="relocation-00000001.json";
            h.worlds.locateOriginal(moved);h.settle();
            assertNull(h.identities.failRecordReadOnce,"Failure must occur after the relocation record exists");
            assertEquals(ProjectWorldWorkbench.State.UNKNOWN,h.worlds.state());
            var failure=assertInstanceOf(ProjectWorldStore.BindingException.class,h.worlds.failure().orElseThrow());
            assertEquals(ProjectWorldStore.Code.UNKNOWN,failure.code());assertFalse(h.worlds.allows(moved));
            var published=h.store.readHistory(binding.project(),binding.projectId()).orElseThrow();
            assertEquals(1,published.relocations().size());assertEquals(moved,published.current().world().root());
            Path record;try(var files=Files.walk(h.local)){record=files.filter(path->path.getFileName().toString().equals("relocation-00000001.json")).findFirst().orElseThrow();}
            byte[] bytes=Files.readAllBytes(record);List<Path> records;
            try(var files=Files.list(record.getParent())){records=files.sorted().toList();}
            assertThrows(IllegalStateException.class,()->h.worlds.locateOriginal(moved));
            assertEquals(0,h.io.size());assertEquals(ProjectWorldWorkbench.State.UNKNOWN,h.worlds.state());
            assertEquals(1,h.store.readHistory(binding.project(),binding.projectId()).orElseThrow().relocations().size());
            try(var files=Files.list(record.getParent())){assertEquals(records,files.sorted().toList());}
            assertArrayEquals(bytes,Files.readAllBytes(record));
            h.worlds.refresh();h.settle();
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());assertTrue(h.worlds.failure().isEmpty());
            assertEquals(published.current(),h.worlds.binding().orElseThrow());assertTrue(h.worlds.allows(moved));
            assertEquals(1,h.store.readHistory(binding.project(),binding.projectId()).orElseThrow().relocations().size());
            try(var files=Files.list(record.getParent())){assertEquals(records,files.sorted().toList());}
            assertArrayEquals(bytes,Files.readAllBytes(record));
        }
    }

    @Test void rejectedCompletionStillDeliversTheActualPublishedBindingOnOwnerTick() throws Exception {
        try(var h=new Harness()){
            World world=h.save("rejected-completion");h.legacy("rejected-project");
            h.worlds.bind(world);h.completion.rejectNext=true;h.io.next();
            assertEquals(0,h.completion.size());assertEquals(ProjectWorldWorkbench.State.BINDING,h.worlds.state());
            assertTrue(h.worlds.binding().isEmpty());assertTrue(h.store.read(h.model.directory().orElseThrow(),h.model.projectId().orElseThrow()).isPresent());
            h.worlds.tick();
            assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());assertEquals(world,h.worlds.binding().orElseThrow().world());
            assertFalse(h.worlds.busy());
        }
    }

    @Test void completionOnTheIoThreadCannotMutateOwnerStateAndIsCollectedByTick() throws Exception {
        try(var h=new Harness(true)){
            World world=h.save("worker");h.legacy("worker-project");h.worlds.bind(world);
            Runnable task=h.io.take();var uncaught=new AtomicReference<Throwable>();
            Thread worker=new Thread(task,"project-world-test-io");worker.setUncaughtExceptionHandler((thread,error)->uncaught.set(error));
            worker.start();worker.join(5000);assertFalse(worker.isAlive());assertNull(uncaught.get());
            assertEquals(ProjectWorldWorkbench.State.BINDING,h.worlds.state());assertTrue(h.worlds.binding().isEmpty());
            h.worlds.tick();assertEquals(ProjectWorldWorkbench.State.READY,h.worlds.state());
            assertEquals(world,h.worlds.binding().orElseThrow().world());
        }
    }

    private final class Harness implements AutoCloseable {
        final ManualExecutor io=new ManualExecutor(),completion=new ManualExecutor();
        final ManualExecutor modelIo=new ManualExecutor(),modelCompletion=new ManualExecutor();
        final ControlledIdentities identities=new ControlledIdentities();
        final Path saves=temporary.resolve("saves"),local=temporary.resolve("host-local");
        final ProjectWorldStore store=new ProjectWorldStore(local,identities);
        final ProjectWorkbench model=new ProjectWorkbench(registry(),modelIo,modelCompletion);
        final ProjectWorldWorkbench worlds;
        Harness(){this(false);}
        Harness(boolean directCompletion){worlds=new ProjectWorldWorkbench(model,store,io,directCompletion?Runnable::run:completion);}
        World save(String id)throws IOException{
            Files.createDirectories(saves);Path root=Files.createDirectory(saves.resolve(id));Files.writeString(root.resolve("level.dat"),"fixture level data");
            return store.inspect(saves,id,"World "+id);
        }
        Path replace(Path root)throws IOException{
            Path moved=root.resolveSibling(root.getFileName()+"-original");Files.move(root,moved);
            Files.createDirectory(root);Files.writeString(root.resolve("level.dat"),"fixture level data");return moved;
        }
        void legacy(String name){model.create(temporary.resolve(name),name,REQUIRED,"Scene",INPUT);drainModel();settle();}
        void drainModel(){
            for(int count=0;modelIo.size()+modelCompletion.size()!=0;count++){
                assertTrue(count<32,"Unexpected project task loop");if(modelIo.size()!=0)modelIo.next();if(modelCompletion.size()!=0)modelCompletion.next();
            }
            assertFalse(model.busy());
        }
        void settle(){
            for(int count=0;count<48;count++){
                worlds.tick();
                if(io.size()+completion.size()+modelIo.size()+modelCompletion.size()==0){assertFalse(model.busy());assertFalse(worlds.busy());return;}
                if(modelIo.size()!=0)modelIo.next();if(modelCompletion.size()!=0)modelCompletion.next();
                if(io.size()!=0)io.next();if(completion.size()!=0)completion.next();
            }
            fail("Unexpected project world task loop");
        }
        public void close(){
            identities.blocked=null;identities.failBindingReadOnce=false;identities.failRecordReadOnce=null;completion.rejectNext=false;settle();
            if(model.hasProject()){model.closeProject(true);drainModel();worlds.tick();}
        }
    }

    private static final class ControlledIdentities implements FileIdentityProvider {
        final FileIdentityProvider actual=System.getProperty("os.name").startsWith("Windows")?new WindowsFileIdentityProvider():FileIdentityProviders.jdk();
        final IOException denied=new IOException("Injected save identity read failure");
        Path blocked;boolean failBindingReadOnce;String failRecordReadOnce;
        public FileNodeIdentity identity(Path path)throws IOException{
            if(path.equals(blocked))throw denied;
            if(failBindingReadOnce&&path.getFileName()!=null&&path.getFileName().toString().equals("binding.json")){
                failBindingReadOnce=false;throw new IOException("Injected observation failure after binding publication");
            }
            if(path.getFileName()!=null&&path.getFileName().toString().equals(failRecordReadOnce)){
                failRecordReadOnce=null;throw new IOException("Injected observation failure after relocation publication");
            }
            return actual.identity(path);
        }
    }
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> tasks=new ArrayDeque<>();boolean rejectNext;
        public void execute(Runnable task){if(rejectNext){rejectNext=false;throw new RejectedExecutionException("Injected completion refusal");}tasks.addLast(task);}
        int size(){return tasks.size();}
        Runnable take(){assertFalse(tasks.isEmpty(),"Expected a queued task");return tasks.removeFirst();}
        void next(){take().run();}
    }
    private static ExtensionRegistry registry(){
        var api=new ApiVersion(1,0);var builder=new ExtensionRegistryBuilder(api,Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);builder.define(ResourceProducers.KIND);builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER,"1",api,Set.of(),Set.of()));
        ProjectTemplates.registerCategory(builder,id("test:category"),OWNER,LABEL);
        for(EditorId type:List.of(REQUIRED,OPTIONAL)){
            builder.add(DocumentTypes.KIND,new Contribution(new ContributionKey(DocumentTypes.KIND.id(),type),OWNER,Contribution.Side.COMMON,Set.of()),new NoteType());
            DocumentEditors.register(builder,type,OWNER,new NoteEditor());
            ProjectTemplates.register(builder,type,OWNER,new ProjectTemplate<String>(){
                public EditorId category(){return id("test:category");}
                public TextContent label(){return LABEL;}
                public EditorId documentType(){return type;}
                public Class<String> valueType(){return String.class;}
                public boolean requiresWorld(){return type.equals(REQUIRED);}
                public List<DocumentEditor.Field> fields(){return List.of(new DocumentEditor.Field("body",LABEL,DocumentEditor.Kind.TEXT,"new",LABEL));}
                public String create(ExtensionRegistry registry,Map<String,String> raw){return raw.get("body");}
            });
        }
        return builder.freeze();
    }
    private static final class NoteType implements DocumentType<String>{
        public Class<String> valueType(){return String.class;}
        public EditorId codecId(){return id("test:utf8");}
        public int schemaVersion(){return 1;}
        public Set<Integer> readableSchemas(){return Set.of(1);}
        public String create(){return "new";}
        public String decode(int schema,byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
        public String snapshot(String value){return value;}
        public List<Problem> validate(String value){return List.of();}
        public byte[] encode(String value){return value.getBytes(StandardCharsets.UTF_8);}
        public DocumentDependencies dependencies(String value){return new DocumentDependencies(Set.of(),Set.of(),Set.of());}
    }
    private static final class NoteEditor implements DocumentEditor<String>{
        public Class<String> valueType(){return String.class;}
        public TextContent label(){return LABEL;}
        public List<Node> nodes(String value){return List.of(new Node("root",Optional.empty(),LABEL));}
        public List<Field> fields(String value,String node){return List.of(new Field("body",LABEL,Kind.TEXT,value,LABEL));}
        public String apply(String value,String node,String property,String raw){return raw;}
    }
    private static EditorId id(String value){return EditorId.parse(value);}
}
