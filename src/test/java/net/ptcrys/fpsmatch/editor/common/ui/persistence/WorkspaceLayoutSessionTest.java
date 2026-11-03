package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.common.ui.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceLayoutSessionTest {
    @TempDir Path temporary;
    private static final EditorId TEMPLATE=EditorId.parse("test:workspace"), PANEL=EditorId.parse("test:panel");
    private static final class Queue implements Executor {
        final Deque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task){tasks.addLast(task);}
        void drain(){while(!tasks.isEmpty())tasks.removeFirst().run();}
    }
    private static WorkspaceManager manager(){
        var result=new WorkspaceManager(List.of(new WorkspaceTemplate(TEMPLATE,1,new TextContent.Literal("Workspace"),List.of(new PanelSpec(PANEL,true)))));
        result.create(TEMPLATE,"First");return result;
    }
    private final Queue io=new Queue(), completion=new Queue();
    private final List<Throwable> failures=new ArrayList<>();
    private WorkspaceLayoutSession session(WorkspaceManager manager){
        return new WorkspaceLayoutSession(manager,temporary.resolve("layouts"),io,completion,()->{},()->{},failures::add);
    }
    private void settle(){io.drain();completion.drain();}
    private static UUID workspace(WorkspaceManager manager){return manager.workspaces().get(0).id();}
    private static UUID panel(WorkspaceManager manager){return manager.workspaces().get(0).panels().get(0).id();}
    private static String group(WorkspaceManager manager){return manager.workspaces().get(0).panels().get(0).viewState().get("dock.group");}
    private Path file() throws Exception {try(var files=Files.list(temporary.resolve("layouts"))){return files.filter(path->path.toString().endsWith(".json")).findFirst().orElseThrow();}}

    @Test void explicitSaveAcknowledgesOnlyBaselineAndKeepsInterveningMoves() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());settle();
        UUID workspace=workspace(manager),panel=panel(manager);
        manager.movePanel(workspace,panel,"leftTop");session.capture();session.saveLayout(workspace);
        assertNull(manager.snapshot().savedLayouts().get(workspace).get(0).viewState().get("dock.group"));
        manager.movePanel(workspace,panel,"bottom");session.capture();session.flush();settle();
        assertEquals("bottom",group(manager));
        assertEquals("leftTop",manager.snapshot().savedLayouts().get(workspace).get(0).viewState().get("dock.group"));
        var disk=WorkspaceStateCodec.decode(Files.readAllBytes(file()));
        assertEquals("bottom",disk.workspaces().get(0).panels().get(0).viewState().get("dock.group"));
        assertEquals("leftTop",disk.savedLayouts().get(workspace).get(0).viewState().get("dock.group"));
        manager.restoreLayout(workspace);assertEquals("leftTop",group(manager));assertTrue(failures.isEmpty());
    }
    @Test void acknowledgementCannotHideUncapturedInput() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());settle();
        UUID workspace=workspace(manager),panel=panel(manager);
        manager.movePanel(workspace,panel,"leftTop");session.saveLayout(workspace);
        manager.movePanel(workspace,panel,"bottom");settle();
        assertEquals("bottom",group(manager));assertEquals("layout_storage_pending",session.statusKey());
        assertEquals("leftTop",manager.snapshot().savedLayouts().get(workspace).get(0).viewState().get("dock.group"));
        session.capture();for(int tick=0;tick<20;tick++)session.tick();settle();
        assertEquals("bottom",WorkspaceStateCodec.decode(Files.readAllBytes(file())).workspaces().get(0).panels().get(0).viewState().get("dock.group"));
        assertEquals("layout_storage_saved",session.statusKey());assertTrue(failures.isEmpty());
    }
    @Test void equalCandidateRetryAndShutdownStillDetectExternalChanges() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());settle();session.flush();settle();
        byte[] external="external original".getBytes(java.nio.charset.StandardCharsets.UTF_8);Path file=file();Files.write(file,external);
        session.saveLayout(workspace(manager));settle();assertEquals(Optional.of("layout_storage_write_failed"),session.problem());
        session.retry();settle();assertEquals(Optional.of("layout_storage_write_failed"),session.problem());
        var stopped=session.flushOnShutdown();io.drain();assertTrue(stopped.isCompletedExceptionally());assertArrayEquals(external,Files.readAllBytes(file));
    }
    @Test void cancelledLoadCannotPublishOrOverwriteAndCanBeReloaded() throws Exception {
        var original=manager();var first=session(original);first.select(null,null,original.snapshot());settle();
        original.movePanel(workspace(original),panel(original),"right");first.capture();first.flush();settle();byte[] before=Files.readAllBytes(file());
        var manager=manager();var defaults=manager.snapshot();var next=session(manager);next.select(null,null,defaults);next.cancelLoad();
        var stopped=next.flushOnShutdown();assertFalse(stopped.isDone());settle();assertTrue(stopped.isDone());assertFalse(stopped.isCompletedExceptionally());
        assertEquals(defaults,manager.snapshot());assertFalse(next.canSave());assertFalse(next.loading());
        assertEquals(Optional.of("layout_storage_cancelled"),next.problem());next.flush();settle();assertArrayEquals(before,Files.readAllBytes(file()));
        next.reload(defaults);settle();assertEquals("right",group(manager));assertTrue(next.canSave());assertTrue(failures.isEmpty());
    }
    @Test void shutdownDuringScopeLoadWaitsForPreviousScopeWrites() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());settle();
        manager.movePanel(workspace(manager),panel(manager),"bottom");session.capture();var expected=manager.snapshot();
        session.select(Files.createDirectory(temporary.resolve("next")),UUID.randomUUID(),manager().snapshot());
        var stopped=session.flushOnShutdown();assertFalse(stopped.isDone());io.drain();assertFalse(stopped.isCompletedExceptionally());assertTrue(stopped.isDone());
        assertEquals(expected,WorkspaceStateCodec.decode(Files.readAllBytes(file())));
    }
    @Test void shutdownDuringScopeLoadReportsEarlierWriteFailureWithoutUiCallback() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());settle();session.flush();settle();
        Files.writeString(file(),"external");manager.movePanel(workspace(manager),panel(manager),"right");session.capture();
        session.select(Files.createDirectory(temporary.resolve("next")),UUID.randomUUID(),manager().snapshot());
        var stopped=session.flushOnShutdown();assertFalse(stopped.isDone());io.drain();assertTrue(stopped.isCompletedExceptionally());
        assertFalse(completion.tasks.isEmpty());assertEquals("external",Files.readString(file()));
    }
    @Test void automaticPersistenceDoesNotReplaceExplicitRestoreBaseline() throws Exception {
        var manager=manager();var initial=manager.snapshot();var session=session(manager);
        session.select(null,null,initial);settle();manager.movePanel(workspace(manager),panel(manager),"right");session.capture();
        for(int tick=0;tick<19;tick++)session.tick();assertTrue(io.tasks.isEmpty());
        session.tick();settle();assertEquals("right",group(manager));
        assertEquals(initial.savedLayouts(),manager.snapshot().savedLayouts());
        var fresh=manager();var reopened=session(fresh);reopened.select(null,null,fresh.snapshot());settle();
        assertEquals(manager.snapshot(),fresh.snapshot());fresh.restoreLayout(workspace(fresh));assertNull(group(fresh));
    }
    @Test void externalWriteRejectsSaveWithoutAdvancingBaselineOrOverwritingOriginal() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());settle();session.flush();settle();
        var baseline=manager.snapshot().savedLayouts();byte[] external="unsupported original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file=file();Files.write(file,external);
        manager.movePanel(workspace(manager),panel(manager),"right");session.capture();session.saveLayout(workspace(manager));settle();
        assertEquals("right",group(manager));assertEquals(baseline,manager.snapshot().savedLayouts());
        assertEquals(Optional.of("layout_storage_write_failed"),session.problem());assertArrayEquals(external,Files.readAllBytes(file));
        session.retry();settle();assertArrayEquals(external,Files.readAllBytes(file));
        session.reload(manager().snapshot());settle();assertFalse(session.canSave());
        assertEquals(Optional.of("layout_storage_protected"),session.problem());session.flush();settle();assertArrayEquals(external,Files.readAllBytes(file));
    }
    @Test void sameProjectIdentityInDifferentDirectoriesDoesNotSharePinnedPanels() throws Exception {
        Path first=Files.createDirectory(temporary.resolve("project-a")),second=Files.createDirectory(temporary.resolve("project-b"));
        UUID project=UUID.randomUUID(),document=UUID.randomUUID();var manager=manager();var session=session(manager);
        session.select(first,project,manager.snapshot());settle();UUID oldWorkspace=workspace(manager),oldPanel=panel(manager);
        manager.bind(oldWorkspace,oldPanel,PanelBinding.pinned(document));manager.movePanel(oldWorkspace,oldPanel,"right");session.capture();session.saveLayout(oldWorkspace);
        var secondDefaults=manager().snapshot();session.select(second,project,secondDefaults);
        // Old write acknowledgement is deliberately delivered after scope switch.
        settle();assertEquals(secondDefaults,manager.snapshot());assertEquals(PanelBinding.followActive(),manager.workspaces().get(0).panels().get(0).binding());
        session.select(first,project,manager().snapshot());settle();assertEquals(oldWorkspace,workspace(manager));assertEquals("right",group(manager));
        assertEquals(PanelBinding.pinned(document),manager.workspaces().get(0).panels().get(0).binding());assertTrue(failures.isEmpty());
    }
    @Test void replacedProjectAtSameDirectoryGetsIndependentEnvironment() throws Exception {
        Path project=Files.createDirectory(temporary.resolve("project"));var manager=manager();var session=session(manager);
        UUID first=UUID.randomUUID();session.select(project,first,manager.snapshot());settle();
        manager.movePanel(workspace(manager),panel(manager),"leftBottom");session.capture();
        var defaults=manager().snapshot();session.select(project,UUID.randomUUID(),defaults);settle();assertEquals(defaults,manager.snapshot());
        session.select(project,first,manager().snapshot());settle();assertEquals("leftBottom",group(manager));
    }
    @Test void staleLoadCannotPublishIntoNewScope() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());
        Path project=Files.createDirectory(temporary.resolve("next"));var defaults=manager().snapshot();session.select(project,UUID.randomUUID(),defaults);
        settle();assertEquals(defaults,manager.snapshot());assertFalse(session.loading());assertTrue(failures.isEmpty());
    }
    @Test void shutdownFlushUsesLatestImmutableCaptureWithoutUiAcknowledgement() throws Exception {
        var manager=manager();var session=session(manager);session.select(null,null,manager.snapshot());settle();
        manager.movePanel(workspace(manager),panel(manager),"bottom");session.capture();
        var future=session.flushOnShutdown();assertFalse(future.isDone());io.drain();assertTrue(future.isDone());
        assertEquals(manager.snapshot(),WorkspaceStateCodec.decode(Files.readAllBytes(file())));assertTrue(completion.tasks.isEmpty());
    }
    @Test void rejectedExecutorKeepsWorkingLayoutAndConfirmedBaseline() {
        var manager=manager();var defaults=manager.snapshot();
        var session=new WorkspaceLayoutSession(manager,temporary,task->{throw new java.util.concurrent.RejectedExecutionException();},Runnable::run,()->{},()->{},failures::add);
        session.select(null,null,defaults);assertFalse(session.loading());assertFalse(session.canSave());assertEquals(defaults,manager.snapshot());
        assertEquals(Optional.of("layout_storage_protected"),session.problem());assertEquals(1,failures.size());
    }
}
