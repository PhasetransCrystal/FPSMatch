package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditors;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises ownership and asynchronous delivery; fake targets grant no native runtime authority. */
class RuntimeTestWorkbenchTest {
    @TempDir Path temporary;
    private static final EditorId OWNER=id("test:runtime_test"), TYPE=id("test:note"),
            ADAPTER=id("test:adapter"), FACTORY=id("test:factory"), DIM=id("minecraft:overworld");
    private static final TextContent LABEL=new TextContent.Literal("Runtime test note");

    @Test void capturedPlanAndWorldSurviveDraftEditingDocumentSelectionAndSavingDuringRunning() throws Exception {
        try(var project=new ProjectFixture(temporary.resolve("independent-editing"))) {
            var h=new Harness();
            UUID document=project.model.activeDocument().orElseThrow();
            UUID commit=project.model.savedVersion().orElseThrow();
            var captured=plan(commit,document);
            project.edit("unsaved before preparation");
            h.model.prepare(h.worldId,captured);
            var input=h.model.input().orElseThrow();
            assertEquals("World A",input.worldLabel());assertEquals(h.worldId,input.worldId());
            assertSame(captured,input.plan());assertSame(captured,h.target.plans.get(0));
            assertFalse(project.model.busy(),"a test task must not borrow or lock the live project");
            project.model.createDocument(TYPE,"Other note");
            UUID other=project.model.activeDocument().orElseThrow();
            project.edit("other draft while preparing");
            h.finishPreparation(List.of());h.model.confirm();
            assertSame(captured,h.target.started.get(0).plan());
            var running=h.finishStart();
            project.edit("other draft while running");project.model.saveAll();project.drain();
            assertNotEquals(commit,project.model.savedVersion().orElseThrow());
            assertEquals(other,project.model.activeDocument().orElseThrow());
            assertSame(input,h.model.input().orElseThrow());
            assertEquals(commit,input.plan().commitId());assertEquals(document,input.plan().documentId());
            assertEquals(RuntimeTestWorkbench.State.RUNNING,h.model.state());assertFalse(h.model.canPrepare());
            assertEquals(1,h.target.started.size());assertTrue(h.target.stopped.isEmpty());
            h.model.stop();assertSame(running,h.target.stopped.get(0));
            h.finishStop();assertTrue(h.model.canPrepare());
        }
    }

    @Test void missingOrDifferentVerifiedWorldCannotDispatchPreparation() {
        var h=new Harness();h.selection.set(Optional.empty());
        assertThrows(IllegalStateException.class,()->h.model.prepare(h.worldId,h.plan));
        h.select(h.host,UUID.randomUUID(),Map.of(DIM,DIM));
        assertThrows(IllegalStateException.class,()->h.model.prepare(h.worldId,h.plan));
        assertTrue(h.target.plans.isEmpty());assertTrue(h.model.input().isEmpty());
        assertEquals(RuntimeTestWorkbench.State.IDLE,h.model.state());assertTrue(h.model.canPrepare());
    }

    @Test void hostWithoutOptionalTestCapabilityRejectsPreparationBeforeCapturingInput() {
        var h=new Harness();h.select(new Host(null),h.worldId,Map.of(DIM,DIM));
        assertThrows(IllegalStateException.class,()->h.model.prepare(h.worldId,h.plan));
        assertTrue(h.model.input().isEmpty());assertTrue(h.model.canPrepare());
        assertEquals(RuntimeTestWorkbench.State.IDLE,h.model.state());
    }

    @Test void errorPreflightRemainsVisibleAndBlocksStartUntilCancelled() {
        var h=new Harness();var error=problem(Problem.Severity.ERROR);
        h.prepare();h.finishPreparation(List.of(error));
        assertEquals(RuntimeTestWorkbench.State.READY,h.model.state());assertEquals(List.of(error),h.model.problems());
        assertFalse(h.model.canConfirm());assertThrows(IllegalStateException.class,h.model::confirm);
        assertFalse(h.model.canPrepare());assertTrue(h.target.started.isEmpty());
        h.model.cancel();assertEquals(RuntimeTestWorkbench.State.CANCELLED,h.model.state());
        assertTrue(h.model.canPrepare());assertTrue(h.target.started.isEmpty());
    }

    @Test void warningPreflightAllowsExactlyOneExplicitStartAndNoAutomaticStart() {
        var h=new Harness();var warning=problem(Problem.Severity.WARNING);
        h.prepare();h.finishPreparation(List.of(warning));
        assertEquals(List.of(warning),h.model.problems());assertTrue(h.model.canConfirm());
        assertTrue(h.target.started.isEmpty());
        h.model.confirm();assertEquals(RuntimeTestWorkbench.State.STARTING,h.model.state());
        assertEquals(1,h.target.started.size());assertFalse(h.model.canConfirm());
        assertThrows(IllegalStateException.class,h.model::confirm);
    }

    @Test void readyCancellationNeverStartsAndOldPreparationCannotChangeTheNextTask() {
        var h=new Harness();h.ready();var oldReply=h.target.preparations.get(0);
        h.model.cancel();assertTrue(h.model.canPrepare());assertFalse(h.model.canConfirm());
        assertEquals(RuntimeTestWorkbench.State.CANCELLED,h.model.state());
        h.prepare();long version=h.model.version();
        oldReply.accept(new RuntimeTestWorkbench.Preparation(()->h.plan,List.of(),null,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.PREPARING,h.model.state());assertEquals(version,h.model.version());
        assertTrue(h.target.started.isEmpty());assertEquals(2,h.target.plans.size());
    }

    @Test void preparingCancellationSignalsTargetAndWaitsForLateReplyBeforeAllowingAnotherTask() {
        var h=new Harness();h.prepare();h.model.cancel();
        assertTrue(h.target.prepareCancellation.get(0).getAsBoolean());assertFalse(h.model.canPrepare());
        assertThrows(IllegalStateException.class,h::prepare);
        var reply=h.target.preparations.get(0);
        reply.accept(new RuntimeTestWorkbench.Preparation(()->h.plan,List.of(),null,null));
        reply.accept(new RuntimeTestWorkbench.Preparation(null,List.of(),LABEL,new IllegalStateException("duplicate")));
        h.model.tick();assertEquals(RuntimeTestWorkbench.State.CANCELLED,h.model.state());
        assertTrue(h.model.canPrepare());assertTrue(h.model.diagnostic().isEmpty());assertTrue(h.target.started.isEmpty());
        h.prepare();reply.accept(new RuntimeTestWorkbench.Preparation(()->h.plan,List.of(),null,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.PREPARING,h.model.state());assertFalse(h.model.canConfirm());
    }

    @Test void replacedConnectionOrDimensionMappingInvalidatesReadyConfirmationEvenWithSameWorldId() {
        var h=new Harness();h.ready();h.select(new Host(new FakeTarget()),h.worldId,Map.of(DIM,DIM));
        assertFalse(h.model.canConfirm());assertThrows(IllegalStateException.class,h.model::confirm);
        h.model.tick();assertEquals(RuntimeTestWorkbench.State.CANCELLED,h.model.state());assertTrue(h.model.canPrepare());
        h.select(h.host,h.worldId,Map.of(DIM,DIM));h.ready();
        h.select(h.host,h.worldId,Map.of(DIM,id("minecraft:the_nether")));
        assertFalse(h.model.canConfirm());h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.CANCELLED,h.model.state());assertTrue(h.target.started.isEmpty());
    }

    @Test void startingCancellationStopsLateSuccessExactlyOnceOnCapturedTarget() {
        var h=new Harness();h.ready();h.model.confirm();h.model.cancel();
        assertTrue(h.target.startCancellation.get(0).getAsBoolean());assertFalse(h.model.canPrepare());
        var running=new Running(h.plan);var reply=h.target.starts.get(0);
        reply.accept(outcome(RuntimeTestWorkbench.State.RUNNING,running));
        reply.accept(outcome(RuntimeTestWorkbench.State.RUNNING,running));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.STOPPING,h.model.state());assertEquals(List.of(running),h.target.stopped);
        h.model.cancel();h.model.stop();assertEquals(1,h.target.stopped.size());
        var stop=h.target.stops.get(0);stop.accept(outcome(RuntimeTestWorkbench.State.STOPPED,null));h.model.tick();
        assertTrue(h.model.canPrepare());long version=h.model.version();
        stop.accept(outcome(RuntimeTestWorkbench.State.RUNNING,running));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.STOPPED,h.model.state());assertEquals(version,h.model.version());
    }

    @Test void acknowledgedStartCancellationReleasesOnlyWhenNoRuntimeWasReturned() {
        var h=new Harness();h.ready();h.model.confirm();h.model.cancel();
        h.target.starts.get(0).accept(new RuntimeTestWorkbench.Outcome(RuntimeTestWorkbench.State.FAILED,null,LABEL,
                new java.util.concurrent.CancellationException("cancelled before native start")));
        h.model.tick();assertEquals(RuntimeTestWorkbench.State.CANCELLED,h.model.state());
        assertTrue(h.model.canPrepare());assertTrue(h.model.diagnostic().isEmpty());assertTrue(h.target.stopped.isEmpty());
        h.ready();h.model.confirm();h.model.cancel();var failure=new IllegalStateException("unrelated start failure");
        h.target.starts.get(1).accept(new RuntimeTestWorkbench.Outcome(RuntimeTestWorkbench.State.FAILED,null,LABEL,failure));
        h.model.tick();assertEquals(RuntimeTestWorkbench.State.FAILED,h.model.state());
        assertSame(failure,h.model.diagnostic().orElseThrow());assertTrue(h.model.canPrepare());
    }

    @Test void sameWorldNewConnectionStillStopsOnlyTheCapturedRunningInstance() {
        var h=new Harness();var running=h.running();var replacement=new FakeTarget();
        h.select(new Host(replacement),h.worldId,Map.of(DIM,DIM));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.STOPPING,h.model.state());assertEquals(List.of(running),h.target.stopped);
        assertTrue(replacement.stopped.isEmpty());assertTrue(replacement.plans.isEmpty());assertFalse(h.model.canPrepare());
        h.finishStop();assertTrue(h.model.canPrepare());assertEquals(h.worldId,h.model.input().orElseThrow().worldId());
    }

    @Test void worldChangeWhileStartingStopsLateSuccessOnOriginalHostBeforeNewWorldMayStart() {
        var h=new Harness();h.ready();h.model.confirm();
        var replacement=new FakeTarget();h.select(new Host(replacement),UUID.randomUUID(),Map.of(DIM,DIM));
        h.model.tick();assertTrue(h.target.startCancellation.get(0).getAsBoolean());assertFalse(h.model.canPrepare());
        var running=h.finishStart();
        assertEquals(RuntimeTestWorkbench.State.STOPPING,h.model.state());
        assertEquals(List.of(running),h.target.stopped);assertTrue(replacement.stopped.isEmpty());
        assertTrue(replacement.plans.isEmpty());assertFalse(h.model.canPrepare());h.finishStop();assertTrue(h.model.canPrepare());
    }

    @Test void runningWorldDisconnectionRequestsOriginalRuntimeStop() {
        var h=new Harness();var running=h.running();h.selection.set(Optional.empty());h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.STOPPING,h.model.state());assertEquals(List.of(running),h.target.stopped);
        assertFalse(h.model.canPrepare());h.finishStop();assertEquals(RuntimeTestWorkbench.State.STOPPED,h.model.state());
        assertEquals(h.worldId,h.model.input().orElseThrow().worldId());
    }

    @Test void stopExceptionPreservesRuntimeOwnershipUntilHostReportsInactiveWithoutReplayingStop() {
        var h=new Harness();var running=h.running();var failure=new IllegalStateException("server rejected stop");
        h.target.stopFailure=failure;h.model.stop();h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.FAILED,h.model.state());assertSame(failure,h.model.diagnostic().orElseThrow());
        assertFalse(h.model.canPrepare());assertThrows(IllegalStateException.class,h::prepare);
        assertEquals(List.of(running),h.target.stopped);assertEquals(1,h.target.started.size());
        assertFalse(h.model.canStop(),"a failed reset must not replay arbitrary native side effects");
        h.awaitPoll();assertSame(running,h.target.polled.get(0));
        assertEquals(1,h.target.stopped.size(),"observing host shutdown must not retry the failed stop");
        h.target.statuses.get(0).accept(outcome(RuntimeTestWorkbench.State.INACTIVE,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.INACTIVE,h.model.state());assertTrue(h.model.canPrepare());
    }

    @Test void unsuccessfulStopReportingStillRunningKeepsOwnershipAndAllowsRetry() {
        var h=new Harness();var running=h.running();h.model.stop();
        var failure=new IllegalStateException("runtime still active");
        h.target.stops.get(0).accept(new RuntimeTestWorkbench.Outcome(RuntimeTestWorkbench.State.RUNNING,running,LABEL,failure));
        h.model.tick();assertFalse(h.model.canPrepare());assertTrue(h.model.canStop());
        assertSame(failure,h.model.diagnostic().orElseThrow());assertThrows(IllegalStateException.class,h::prepare);
        h.model.stop();assertEquals(List.of(running,running),h.target.stopped);
        h.target.stops.get(1).accept(outcome(RuntimeTestWorkbench.State.STOPPED,null));h.model.tick();assertTrue(h.model.canPrepare());
    }

    @Test void statusCapturedBeforeFailedStopCannotAuthorizeRetryOrReleaseOwnership() {
        for(var staleState:List.of(RuntimeTestWorkbench.State.RUNNING,RuntimeTestWorkbench.State.INACTIVE)) {
            var h=new Harness();var running=h.running();h.awaitPoll();var oldPoll=h.target.statuses.get(0);
            var failure=new IllegalStateException("reset may have partially run");h.target.stopFailure=failure;
            h.model.stop();h.model.tick();long version=h.model.version();
            oldPoll.accept(outcome(staleState,staleState==RuntimeTestWorkbench.State.RUNNING?running:null));h.model.tick();
            assertEquals(RuntimeTestWorkbench.State.FAILED,h.model.state());assertEquals(version,h.model.version());
            assertSame(failure,h.model.diagnostic().orElseThrow());assertFalse(h.model.canStop());assertFalse(h.model.canPrepare());
            assertEquals(List.of(running),h.target.stopped);
        }
    }

    @Test void freshStatusAfterFailedDispatchCanRestoreSafeStopRetryEvenIfOlderPollNeverReplies() {
        var h=new Harness();var running=h.running();h.awaitPoll();var oldPoll=h.target.statuses.get(0);
        h.target.stopFailure=new IllegalStateException("stop dispatch rejected before reaching host");
        h.model.stop();h.model.tick();assertFalse(h.model.canStop());h.target.stopFailure=null;
        for(int i=0;i<21&&h.target.statuses.size()<2;i++)h.model.tick();
        assertEquals(2,h.target.statuses.size(),"an obsolete poll cannot block post-stop observation");
        h.target.statuses.get(1).accept(outcome(RuntimeTestWorkbench.State.RUNNING,running));h.model.tick();
        assertTrue(h.model.canStop());assertFalse(h.model.canPrepare());
        oldPoll.accept(outcome(RuntimeTestWorkbench.State.INACTIVE,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.RUNNING,h.model.state());assertTrue(h.model.canStop());
        h.model.stop();assertEquals(List.of(running,running),h.target.stopped);h.finishStop();assertTrue(h.model.canPrepare());
    }

    @Test void statusDispatchFailureDoesNotPretendTheCapturedRuntimeIsInactive() {
        var h=new Harness();var running=h.running();var failure=new IllegalStateException("status dispatch unavailable");
        h.target.statusFailure=failure;h.awaitPoll();h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.FAILED,h.model.state());assertSame(failure,h.model.diagnostic().orElseThrow());
        assertFalse(h.model.canPrepare());assertFalse(h.model.canStop());assertTrue(h.target.stopped.isEmpty());
        h.target.statusFailure=null;
        for(int i=0;i<21&&h.target.statuses.size()<2;i++)h.model.tick();
        assertEquals(2,h.target.statuses.size());assertSame(running,h.target.polled.get(1));
        h.target.statuses.get(1).accept(outcome(RuntimeTestWorkbench.State.INACTIVE,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.INACTIVE,h.model.state());assertTrue(h.model.canPrepare());
    }

    @Test void inFlightStatusCannotOverwriteCompletedStop() {
        var h=new Harness();var running=h.running();h.awaitPoll();var oldPoll=h.target.statuses.get(0);
        h.model.stop();h.finishStop();assertEquals(RuntimeTestWorkbench.State.STOPPED,h.model.state());
        long stoppedVersion=h.model.version();oldPoll.accept(outcome(RuntimeTestWorkbench.State.RUNNING,running));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.STOPPED,h.model.state());assertEquals(stoppedVersion,h.model.version());
    }

    @Test void previousRuntimeFirstStatusReplyCannotTerminateAReplacementPreparation() {
        var h=new Harness();h.running();h.awaitPoll();var oldPoll=h.target.statuses.get(0);
        h.model.stop();h.finishStop();h.prepare();long version=h.model.version();
        oldPoll.accept(outcome(RuntimeTestWorkbench.State.INACTIVE,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.PREPARING,h.model.state());assertFalse(h.model.canPrepare());
        assertEquals(version,h.model.version());assertEquals(2,h.target.plans.size());
    }

    @Test void statusDeliveredDuringStopCannotReleaseRuntimeBeforeStopFinishes() {
        var h=new Harness();h.running();h.awaitPoll();h.model.stop();
        h.target.statuses.get(0).accept(outcome(RuntimeTestWorkbench.State.INACTIVE,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.STOPPING,h.model.state());assertFalse(h.model.canPrepare());
        h.finishStop();assertEquals(RuntimeTestWorkbench.State.STOPPED,h.model.state());assertTrue(h.model.canPrepare());
    }

    @Test void pollingAllowsOnlyOneOutstandingRequestAndExternalTerminationReleasesOwnership() {
        var h=new Harness();var running=h.running();h.awaitPoll();
        for(int i=0;i<60;i++)h.model.tick();assertEquals(1,h.target.statuses.size());
        assertSame(running,h.target.polled.get(0));
        h.target.statuses.get(0).accept(outcome(RuntimeTestWorkbench.State.STOPPED,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.STOPPED,h.model.state());assertTrue(h.model.canPrepare());
        assertFalse(h.model.canStop());assertTrue(h.target.stopped.isEmpty());
    }

    @Test void workerCompletionIsQueuedUntilOwnerTickAndDuplicateCannotReplaceItsFirstResult() throws Exception {
        var h=new Harness();h.prepare();var reply=h.target.preparations.get(0);
        var worker=new Thread(()->{
            reply.accept(new RuntimeTestWorkbench.Preparation(()->h.plan,List.of(),null,null));
            reply.accept(new RuntimeTestWorkbench.Preparation(null,List.of(),LABEL,new IllegalStateException("duplicate")));
        },"runtime-test-completion");
        worker.start();worker.join(10000);assertFalse(worker.isAlive());
        assertEquals(RuntimeTestWorkbench.State.PREPARING,h.model.state());assertFalse(h.model.canConfirm());
        h.model.tick();assertEquals(RuntimeTestWorkbench.State.READY,h.model.state());
        assertTrue(h.model.canConfirm());assertTrue(h.model.diagnostic().isEmpty());
    }

    @Test void preparedPlanMismatchFailsWithoutDispatchingStartAndCanBeRetried() {
        var h=new Harness();h.prepare();var other=plan(UUID.randomUUID(),UUID.randomUUID());
        h.target.preparations.get(0).accept(new RuntimeTestWorkbench.Preparation(()->other,List.of(),null,null));h.model.tick();
        assertEquals(RuntimeTestWorkbench.State.FAILED,h.model.state());assertFalse(h.model.canConfirm());
        assertTrue(h.model.canPrepare());assertTrue(h.target.started.isEmpty());assertSame(h.plan,h.model.input().orElseThrow().plan());
    }

    private static Problem problem(Problem.Severity severity) {
        return new Problem(severity,id("test:preflight"),"runtime",LABEL);
    }
    private static RuntimeTestWorkbench.Outcome outcome(RuntimeTestWorkbench.State state,RuntimeTestWorkbench.Running running) {
        return new RuntimeTestWorkbench.Outcome(state,running,new TextContent.Literal(state.name()),null);
    }
    private static InstallationPlan plan(UUID commit,UUID document) {
        return new InstallationPlan(UUID.randomUUID(),new InstallationPlan.Key("test","Captured runtime"),UUID.randomUUID(),
                commit,document,ADAPTER,FACTORY,"1",Map.of(OWNER,"1"),Map.of(DIM,DIM));
    }
    private record Running(InstallationPlan plan) implements RuntimeTestWorkbench.Running { }
    private static final class Harness {
        final UUID worldId=UUID.randomUUID();final InstallationPlan plan=plan(UUID.randomUUID(),UUID.randomUUID());
        final FakeTarget target=new FakeTarget();final Host host=new Host(target);
        final AtomicReference<Optional<RuntimeInstallationWorkbench.Selection>> selection=new AtomicReference<>();
        final RuntimeTestWorkbench model=new RuntimeTestWorkbench(selection::get);
        Harness(){select(host,worldId,Map.of(DIM,DIM));}
        void select(Host target,UUID world,Map<EditorId,EditorId> dimensions) {
            selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(target,"World A",world,dimensions)));
        }
        void prepare(){model.prepare(worldId,plan);}
        void finishPreparation(List<Problem> problems) {
            var captured=target.plans.get(target.plans.size()-1);
            target.preparations.get(target.preparations.size()-1).accept(new RuntimeTestWorkbench.Preparation(()->captured,problems,null,null));
            model.tick();
        }
        void ready(){prepare();finishPreparation(List.of());assertTrue(model.canConfirm());}
        Running finishStart() {
            var running=new Running(target.started.get(target.started.size()-1).plan());
            target.starts.get(target.starts.size()-1).accept(outcome(RuntimeTestWorkbench.State.RUNNING,running));model.tick();return running;
        }
        Running running(){ready();model.confirm();return finishStart();}
        void finishStop(){target.stops.get(target.stops.size()-1).accept(outcome(RuntimeTestWorkbench.State.STOPPED,null));model.tick();}
        void awaitPoll(){for(int i=0;i<21&&target.statuses.isEmpty();i++)model.tick();assertEquals(1,target.statuses.size());}
    }
    private static final class FakeTarget implements RuntimeTestWorkbench.Target {
        final List<InstallationPlan> plans=new ArrayList<>();
        final List<BooleanSupplier> prepareCancellation=new ArrayList<>(),startCancellation=new ArrayList<>();
        final List<Consumer<RuntimeTestWorkbench.Preparation>> preparations=new ArrayList<>();
        final List<RuntimeTestWorkbench.Prepared> started=new ArrayList<>();
        final List<RuntimeTestWorkbench.Running> stopped=new ArrayList<>(),polled=new ArrayList<>();
        final List<Consumer<RuntimeTestWorkbench.Outcome>> starts=new ArrayList<>(),stops=new ArrayList<>(),statuses=new ArrayList<>();
        RuntimeException stopFailure,statusFailure;
        public void prepare(InstallationPlan plan,BooleanSupplier cancelled,Consumer<RuntimeTestWorkbench.Preparation> completed) {
            plans.add(plan);prepareCancellation.add(cancelled);preparations.add(completed);
        }
        public void start(RuntimeTestWorkbench.Prepared prepared,BooleanSupplier cancelled,Consumer<RuntimeTestWorkbench.Outcome> completed) {
            started.add(prepared);startCancellation.add(cancelled);starts.add(completed);
        }
        public void stop(RuntimeTestWorkbench.Running running,Consumer<RuntimeTestWorkbench.Outcome> completed) {
            stopped.add(running);stops.add(completed);if(stopFailure!=null)throw stopFailure;
        }
        public void status(RuntimeTestWorkbench.Running running,Consumer<RuntimeTestWorkbench.Outcome> completed) {
            polled.add(running);statuses.add(completed);if(statusFailure!=null)throw statusFailure;
        }
    }
    private static final class Host implements RuntimeInstallationWorkbench.Target {
        final RuntimeTestWorkbench.Target tests;
        Host(RuntimeTestWorkbench.Target tests){this.tests=tests;}
        public RuntimeTestWorkbench.Target tests(){return tests;}
        public void prepare(RuntimeInstallationWorkbench.Request request,BooleanSupplier cancelled,Consumer<RuntimeInstallationWorkbench.Preparation> completed){fail("installation preparation is unrelated to testing");}
        public void submit(RuntimeInstallationWorkbench.Prepared prepared,UUID request,BooleanSupplier cancelled,Consumer<RuntimeInstallationWorkbench.Result> completed){fail("persistent installation is unrelated to testing");}
        public void records(Consumer<List<RuntimeInstallationWorkbench.RecordView>> completed,Consumer<Throwable> failed){fail("test flow must not read installation records");}
        public void lookup(UUID request,Consumer<TextContent> completed,Consumer<Throwable> failed){fail("test flow must not look up installation requests");}
    }
    private static ExtensionRegistry registry() {
        var api=new ApiVersion(1,0);var builder=new ExtensionRegistryBuilder(api,Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);builder.define(ResourceProducers.KIND);builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER,"1",api,Set.of(),Set.of()));
        builder.add(DocumentTypes.KIND,new Contribution(new ContributionKey(DocumentTypes.KIND.id(),TYPE),OWNER,Contribution.Side.COMMON,Set.of()),new NoteType());
        DocumentEditors.register(builder,TYPE,OWNER,new NoteEditor());return builder.freeze();
    }
    private static final class NoteType implements DocumentType<String> {
        public Class<String> valueType(){return String.class;}public EditorId codecId(){return id("test:utf8");}
        public int schemaVersion(){return 1;}public Set<Integer> readableSchemas(){return Set.of(1);}
        public String create(){return "new";}public String snapshot(String value){return value;}
        public String decode(int schema,byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
        public byte[] encode(String value){return value.getBytes(StandardCharsets.UTF_8);}
        public List<Problem> validate(String value){return List.of();}
        public DocumentDependencies dependencies(String value){return new DocumentDependencies(Set.of(),Set.of(),Set.of());}
    }
    private static final class NoteEditor implements DocumentEditor<String> {
        public Class<String> valueType(){return String.class;}public TextContent label(){return LABEL;}
        public List<Node> nodes(String value){return List.of(new Node("root",Optional.empty(),LABEL));}
        public List<Field> fields(String value,String node){return List.of(new Field("value",LABEL,Kind.TEXT,value,LABEL));}
        public String apply(String value,String node,String property,String raw){return raw;}
    }
    private static final class Queue implements Executor {
        final Deque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task){tasks.addLast(task);}
        void next(){tasks.removeFirst().run();}
    }
    private static final class ProjectFixture implements AutoCloseable {
        final Queue io=new Queue(),completion=new Queue();final ProjectWorkbench model=new ProjectWorkbench(registry(),io,completion);
        ProjectFixture(Path path) {
            model.create(path,"Runtime test project");drain();model.createDocument(TYPE,"Saved note");
            edit("persisted");model.saveAll();drain();
        }
        void edit(String value){var address=new ProjectWorkbench.Address(model.activeDocument().orElseThrow(),"root","value");model.input(address,value);model.apply(address);}
        void drain(){for(int i=0;!io.tasks.isEmpty()||!completion.tasks.isEmpty();i++){assertTrue(i<32);if(!io.tasks.isEmpty())io.next();if(!completion.tasks.isEmpty())completion.next();}}
        public void close(){drain();if(model.hasProject()&&!model.busy()){model.closeProject(true);drain();}}
    }
    private static EditorId id(String value){return EditorId.parse(value);}
}
