package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditors;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.installation.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure owner coordination with an actual saved project; fake targets grant no native authority. */
class RuntimeInstallationWorkbenchTest {
    @TempDir Path temporary;
    private static final EditorId OWNER=id("test:installation"), TYPE=id("test:note"),
            ADAPTER=id("test:adapter"), FACTORY=id("test:factory"), DIM=id("minecraft:overworld");
    private static final TextContent LABEL=new TextContent.Literal("Saved note");
    private static final RuntimeInstallationProfile PROFILE=new RuntimeInstallationProfile(TYPE,ADAPTER,FACTORY,"test",LABEL);

    @Test void preparationUsesConfirmedBytesWhileDraftsAndDocumentSelectionContinueIndependently() throws Exception {
        try(var h=saved("captured")) {
            UUID first=h.project.activeDocument().orElseThrow(), commit=h.project.savedVersion().orElseThrow();
            h.project.createDocument(TYPE,"Other note"); UUID other=h.project.activeDocument().orElseThrow();
            h.project.selectDocument(first);
            byte[] current=h.current(); h.edit("unsaved before preparation");
            h.model.prepare(PROFILE,"Runtime name"); var request=h.target.requests.get(0);
            assertEquals(first,request.source().documentId()); assertEquals(commit,request.source().saved().commitId());
            assertEquals("Saved note",request.source().documentName()); assertEquals("Runtime name",request.name());
            assertSame(PROFILE,request.profile()); assertSame(h.selection.get().orElseThrow(),request.target());
            var loaded=request.source().access().read();
            assertEquals(request.source().saved(),loaded.saved());
            assertArrayEquals("persisted".getBytes(StandardCharsets.UTF_8),loaded.documentBytes(first));
            h.project.selectDocument(other);
            h.edit("other draft");
            var prepared=h.prepared(request); h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));
            assertEquals(RuntimeInstallationWorkbench.State.PREPARING,h.model.state()); h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.READY,h.model.state()); assertTrue(h.model.canConfirm());
            assertEquals(first,h.model.preparedPlan().orElseThrow().documentId());
            assertEquals(commit,h.model.preparedPlan().orElseThrow().commitId());
            assertEquals(other,h.project.activeDocument().orElseThrow()); assertTrue(h.project.unsaved());
            assertArrayEquals(current,h.current()); assertEquals(0,h.target.submissions.size());
            h.model.cancel(); prepared.finishRelease(); h.model.tick();
            assertEquals(1,prepared.releases); assertFalse(h.project.busy()); assertArrayEquals(current,h.current());
        }
    }

    @Test void cancelBeforeLatePreparationRetainsBorrowUntilItsResourceIsReleased() throws Exception {
        try(var h=saved("late")) {
            byte[] current=h.current(); h.model.prepare(PROFILE,"Runtime");
            var request=h.target.requests.get(0); h.model.dismiss();
            assertTrue(h.target.cancellations.get(0).getAsBoolean()); assertTrue(h.project.busy());
            assertThrows(SessionRejection.class,h.project::saveAll);
            assertThrows(SessionRejection.class,()->h.project.closeProject(true));
            var prepared=h.prepared(request);
            onWorker(()->h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null)));
            h.model.tick(); assertEquals(1,prepared.releases); assertTrue(h.project.busy());
            assertEquals(RuntimeInstallationWorkbench.State.CANCELLING,h.model.state());
            assertEquals(0,h.target.submissions.size()); assertArrayEquals(current,h.current());
            prepared.finishRelease(); h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.CANCELLED,h.model.state()); assertTrue(h.model.canStart());
            assertFalse(h.project.busy()); assertTrue(h.model.receipt().isEmpty());
            h.project.saveAll(); h.drain(); h.project.closeProject(false); h.drain();
            try(var store=LocalProjectStore.open(h.path)){assertNotNull(store.load());}
        }
    }

    @Test void synchronousPreparationFailureReleasesTheProjectBorrowAndKeepsDiskUntouched() throws Exception {
        try(var h=saved("failure")) {
            byte[] current=h.current(); var failure=new IllegalStateException("source validation failed");
            h.target.prepareFailure=failure; h.model.prepare(PROFILE,"Runtime"); h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.FAILED,h.model.state()); assertFalse(h.project.busy());
            assertSame(failure,h.model.diagnostic().orElseThrow()); assertTrue(h.model.canStart());
            assertTrue(h.model.receipt().isEmpty()); assertEquals(0,h.target.submissions.size());
            assertArrayEquals(current,h.current()); h.project.saveAll(); h.drain();
        }
    }

    @Test void oneConfirmationAndPageDismissalKeepTheActualReceiptAndHoldBorrowThroughRelease() throws Exception {
        try(var h=saved("confirmed")) {
            byte[] current=h.current(); var prepared=h.ready(); h.model.confirm();
            UUID request=h.target.submissions.get(0);
            assertThrows(IllegalStateException.class,h.model::confirm); h.model.dismiss();
            assertEquals(1,h.target.submissions.size()); assertFalse(h.target.submitCancellation.get(0).getAsBoolean());
            assertEquals(RuntimeInstallationWorkbench.State.EXECUTING,h.model.state());
            var actual=result(RuntimeInstallationWorkbench.State.INSTALLED);
            onWorker(()->h.target.results.get(0).accept(actual));
            assertNull(h.model.receipt().orElseThrow().result()); h.model.tick();
            assertSame(actual,h.model.receipt().orElseThrow().result()); assertEquals(1,prepared.releases);
            assertTrue(h.project.busy()); assertThrows(SessionRejection.class,h.project::saveAll);
            prepared.finishRelease(); h.model.tick();
            var receipt=h.model.receipt().orElseThrow(); assertSame(actual,receipt.result());
            assertEquals(request,receipt.requestId()); assertEquals(prepared.plan(),receipt.plan());
            assertEquals("World A",receipt.worldLabel()); assertEquals(h.worldId,receipt.worldId());
            assertEquals(RuntimeInstallationWorkbench.State.INSTALLED,h.model.state()); assertFalse(h.project.busy());
            h.model.dismiss(); assertSame(receipt,h.model.receipt().orElseThrow()); assertArrayEquals(current,h.current());
        }
    }

    @Test void replacementConnectionInvalidatesPreparationAndCannotReceiveTheOldSubmission() throws Exception {
        try(var h=saved("replace-before")) {
            var prepared=h.ready(); var replacement=new FakeTarget();
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(replacement,"World B",UUID.randomUUID(),Map.of(DIM,DIM))));
            assertFalse(h.model.canConfirm()); assertThrows(IllegalStateException.class,h.model::confirm);
            h.model.tick(); assertEquals(1,prepared.releases); assertEquals(0,h.target.submissions.size());
            assertTrue(replacement.requests.isEmpty()); assertTrue(replacement.submissions.isEmpty());
            prepared.finishRelease(); h.model.tick(); assertFalse(h.project.busy());
            assertEquals(RuntimeInstallationWorkbench.State.CANCELLED,h.model.state());
        }
    }

    @Test void sameWorldRebindingInvalidatesTheOldPreparationWithoutSubmittingToEitherConnection() throws Exception {
        try(var h=saved("same-world-rebinding")) {
            var prepared=h.ready();var replacement=new FakeTarget();
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(replacement,"World A",h.worldId,Map.of(DIM,DIM))));
            assertFalse(h.model.canConfirm());h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.CANCELLING,h.model.state());assertEquals(1,prepared.releases);
            assertTrue(h.target.submissions.isEmpty());assertTrue(replacement.submissions.isEmpty());
            prepared.finishRelease();h.model.tick();assertTrue(h.model.canStart());assertFalse(h.project.busy());
        }
    }

    @Test void mismatchedSavedVersionDocumentOrDestinationCannotBecomeAConfirmation() throws Exception {
        for(int mismatch=0;mismatch<4;mismatch++)try(var h=saved("mismatched-input-"+mismatch)) {
            h.model.prepare(PROFILE,"Runtime");var expected=h.prepared(h.target.requests.get(0)).plan();
            var wrong=new InstallationPlan(expected.installationId(),
                    mismatch==3?new InstallationPlan.Key("test","Another runtime"):expected.key(),expected.projectId(),
                    mismatch==0?UUID.randomUUID():expected.commitId(),mismatch==1?UUID.randomUUID():expected.documentId(),
                    expected.adapterId(),expected.factoryId(),expected.targetVersion(),expected.modules(),
                    mismatch==2?Map.of(DIM,id("minecraft:the_nether")):expected.dimensions());
            var prepared=new FakePrepared(wrong);
            h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            assertFalse(h.model.canConfirm());assertTrue(h.project.busy());assertEquals(1,prepared.releases);
            assertTrue(h.model.preparedPlan().isEmpty(),"An invalid preparation must not become display identity");
            assertTrue(h.target.submissions.isEmpty());prepared.finishRelease();h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.FAILED,h.model.state());assertTrue(h.model.diagnostic().isPresent());
            assertTrue(h.model.canStart());assertFalse(h.project.busy());assertTrue(h.model.receipt().isEmpty());
        }
    }

    @Test void receiptTracksOriginalSessionAcrossSavingButDoesNotBelongToSameUuidCopyOrReopenedProject() throws Exception {
        try(var h=saved("receipt-session")) {
            var prepared=h.ready();h.model.confirm();h.target.results.get(0).accept(result(RuntimeInstallationWorkbench.State.INSTALLED));
            h.model.tick();prepared.finishRelease();h.model.tick();var receipt=h.model.receipt().orElseThrow();
            assertTrue(h.model.receiptBelongsToCurrentProject());
            h.project.createDocument(TYPE,"Next draft");h.edit("next saved version");h.project.saveAll();h.drain();
            assertNotEquals(receipt.plan().commitId(),h.project.savedVersion().orElseThrow());
            assertTrue(h.model.receiptBelongsToCurrentProject());assertSame(receipt,h.model.receipt().orElseThrow());
            UUID projectId=h.project.projectId().orElseThrow();h.project.closeProject(false);h.drain();
            assertFalse(h.model.receiptBelongsToCurrentProject());
            Path copy=temporary.resolve("receipt-session-copy");
            try(var files=Files.walk(h.path)) {
                for(Path source:files.toList()) {
                    Path destination=copy.resolve(h.path.relativize(source));
                    if(Files.isDirectory(source))Files.createDirectories(destination);else Files.copy(source,destination);
                }
            }
            h.project.open(copy);h.drain();assertEquals(projectId,h.project.projectId().orElseThrow());
            assertFalse(h.model.receiptBelongsToCurrentProject());assertSame(receipt,h.model.receipt().orElseThrow());
            h.project.closeProject(false);h.drain();h.project.open(h.path);h.drain();
            assertEquals(projectId,h.project.projectId().orElseThrow());assertFalse(h.model.receiptBelongsToCurrentProject());
            assertSame(receipt,h.model.receipt().orElseThrow(),"history remains visible after its source session is closed");
        }
    }

    @Test void disconnectAfterConfirmationCancelsCooperativelyButKeepsOriginalOutcomeAndTarget() throws Exception {
        try(var h=saved("replace-after")) {
            var prepared=h.ready(); h.model.confirm(); UUID request=h.target.submissions.get(0);
            var replacement=new FakeTarget();
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(replacement,"World B",UUID.randomUUID(),Map.of(DIM,DIM))));
            int polls=h.target.polls; h.model.tick(); assertTrue(h.target.submitCancellation.get(0).getAsBoolean());
            assertTrue(replacement.submissions.isEmpty()); assertEquals(polls+1,h.target.polls);
            var actual=result(RuntimeInstallationWorkbench.State.RECOVERY_REQUIRED);
            h.target.results.get(0).accept(actual); h.model.tick(); prepared.finishRelease(); h.model.tick();
            var receipt=h.model.receipt().orElseThrow(); assertSame(actual,receipt.result());
            assertEquals(request,receipt.requestId()); assertEquals(h.worldId,receipt.worldId());
            assertEquals(RuntimeInstallationWorkbench.State.RECOVERY_REQUIRED,h.model.state()); assertFalse(h.project.busy());
            assertTrue(replacement.requests.isEmpty()); assertEquals(1,h.target.submissions.size());
        }
    }

    @Test void ownerTickAcceptsWorkerOutcomeEvenWhenProjectCompletionExecutorRejects() throws Exception {
        try(var h=saved("dispatch")) {
            var prepared=h.ready(); h.model.confirm(); h.completion.reject=true;
            var actual=result(RuntimeInstallationWorkbench.State.INSTALLED);
            onWorker(()->h.target.results.get(0).accept(actual));
            assertNull(h.model.receipt().orElseThrow().result()); h.model.tick();
            onWorker(prepared::finishRelease); h.model.tick();
            assertSame(actual,h.model.receipt().orElseThrow().result()); assertFalse(h.project.busy());
            assertEquals(RuntimeInstallationWorkbench.State.INSTALLED,h.model.state());
            assertEquals(1,prepared.releases); assertEquals(1,h.target.submissions.size()); h.completion.reject=false;
        }
    }

    @Test void unknownOnlyLooksUpOriginalRequestAndCannotReprepareOrResubmit() throws Exception {
        try(var h=saved("unknown")) {
            byte[] current=h.current(); var prepared=h.ready(); h.model.confirm(); UUID request=h.target.submissions.get(0);
            var actual=result(RuntimeInstallationWorkbench.State.UNKNOWN); h.target.results.get(0).accept(actual);
            h.model.tick(); prepared.finishRelease(); h.model.tick();
            assertFalse(h.model.canStart()); assertTrue(h.model.canQuery()); assertFalse(h.project.busy());
            assertThrows(IllegalStateException.class,()->h.model.prepare(PROFILE,"Retry"));
            assertThrows(IllegalStateException.class,h.model::confirm); h.model.query(); h.model.query();
            assertEquals(List.of(request),h.target.lookups);
            TextContent found=new TextContent.Literal("Original request has durable recovery protection");
            h.target.lookupReplies.get(0).accept(found); h.model.tick();
            assertEquals(found,h.model.status()); assertEquals(RuntimeInstallationWorkbench.State.UNKNOWN,h.model.state());
            assertSame(actual,h.model.receipt().orElseThrow().result()); assertFalse(h.model.canStart());
            h.model.query(); assertEquals(List.of(request,request),h.target.lookups);
            h.target.lookupReplies.get(1).accept(found); h.model.tick();
            assertEquals(1,h.target.requests.size()); assertEquals(1,h.target.submissions.size());
            assertArrayEquals(current,h.current());
        }
    }

    @Test void recordsAndPreviousRecoverExactArchivedInputWithoutAnyOpenProject() throws Exception {
        try(var h=new Harness(temporary.resolve("no-project"))) {
            var current=plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
            var old=plan(UUID.randomUUID(),current.projectId(),UUID.randomUUID(),UUID.randomUUID());
            var bundle=new BlobRef(BlobRef.Kind.ASSET,"a".repeat(64),17);
            var previous=new LocalInstallationIndex.Previous(old,new BlobRef(BlobRef.Kind.ASSET,"b".repeat(64),23));
            var record=new LocalInstallationIndex.Record(current,LocalInstallationIndex.State.ACTIVE,bundle,previous);
            var offline=new RuntimeInstallationWorkbench.RecordView(record,false);
            var online=new RuntimeInstallationWorkbench.RecordView(record,true);
            h.model.refreshRecords(); h.target.recordReplies.get(0).accept(List.of(offline,online)); h.model.tick();
            assertFalse(h.project.hasProject()); assertEquals(List.of(offline,online),h.model.records());
            assertThrows(UnsupportedOperationException.class,()->h.model.records().clear());
            assertThrows(IllegalStateException.class,()->h.model.recover(online,false));
            assertTrue(h.target.requests.isEmpty());
            for(boolean restorePrevious:List.of(false,true)) {
                h.model.recover(offline,restorePrevious); var request=h.target.requests.get(h.target.requests.size()-1);
                assertNull(request.source()); assertNull(request.profile()); assertSame(record,request.recovery());
                assertEquals(restorePrevious,request.previous()); assertEquals(current.key().name(),request.name());
                assertSame(previous,request.recovery().previous()); assertSame(bundle,request.recovery().bundle());
                assertEquals((restorePrevious?old:current).key(),h.model.taskInput().orElseThrow().key());
                assertEquals((restorePrevious?old:current).documentId().toString(),h.model.taskInput().orElseThrow().documentName(),
                        "recovery has no captured document name; never relabel the runtime name as a source document");
                var prepared=new FakePrepared(restorePrevious?old:current);
                h.target.preparations.get(h.target.preparations.size()-1).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));
                h.model.tick(); assertEquals(prepared.plan(),h.model.preparedPlan().orElseThrow());
                h.model.confirm(); h.target.results.get(h.target.results.size()-1).accept(result(RuntimeInstallationWorkbench.State.INSTALLED));
                h.model.tick(); prepared.finishRelease(); h.model.tick();
                assertEquals(prepared.plan(),h.model.receipt().orElseThrow().plan()); assertEquals(1,prepared.releases);
                assertFalse(h.project.hasProject()); assertTrue(h.model.canStart());
            }
            assertEquals(2,h.target.submissions.size()); assertFalse(Files.exists(h.path));
        }
    }

    @Test void lateRecordListFromAnotherConnectionCannotBecomeRecoveryChoices() throws Exception {
        try(var h=new Harness(temporary.resolve("stale-records"))) {
            h.model.refreshRecords(); h.selection.set(Optional.empty());
            var record=new LocalInstallationIndex.Record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),
                    LocalInstallationIndex.State.ACTIVE,new BlobRef(BlobRef.Kind.ASSET,"a".repeat(64),1));
            var selected=new RuntimeInstallationWorkbench.RecordView(record,false);
            h.target.recordReplies.get(0).accept(List.of(selected)); h.model.tick();
            assertFalse(h.model.readingRecords()); assertTrue(h.model.records().isEmpty());
            assertThrows(IllegalStateException.class,()->h.model.recover(selected,false)); assertTrue(h.target.requests.isEmpty());
        }
    }

    @Test void pendingRecordsArePolledAndRebindingCanRefreshWithoutWaitingForTheRetiredHost() throws Exception {
        try(var h=new Harness(temporary.resolve("pending-record-rebinding"))) {
            h.model.refreshRecords();h.model.tick();assertEquals(1,h.target.polls);
            var oldReply=h.target.recordReplies.get(0);var replacement=new FakeTarget();
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(replacement,"World A",h.worldId,Map.of(DIM,DIM))));
            h.model.tick();assertFalse(h.model.readingRecords());h.model.refreshRecords();
            assertTrue(h.model.readingRecords());
            var staleRecord=new LocalInstallationIndex.Record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),
                    LocalInstallationIndex.State.ACTIVE,new BlobRef(BlobRef.Kind.ASSET,"a".repeat(64),1));
            oldReply.accept(List.of(new RuntimeInstallationWorkbench.RecordView(staleRecord,false)));h.model.tick();
            assertTrue(h.model.readingRecords());assertTrue(h.model.records().isEmpty());
            replacement.recordReplies.get(0).accept(List.of());h.model.tick();
            assertFalse(h.model.readingRecords());assertTrue(h.model.records().isEmpty());
            assertEquals(new RuntimeInstallationWorkbench.RecordsInput("World A",h.worldId),h.model.recordsInput().orElseThrow());
        }
    }

    @Test void synchronousSuccessThenSubmitExceptionCannotReplaceTheActualSuccessWithUnknown() throws Exception {
        try(var h=saved("callback-then-throw")) {
            var prepared=h.ready(); var actual=result(RuntimeInstallationWorkbench.State.INSTALLED);
            h.target.submitHook=completed->{completed.accept(actual);throw new IllegalStateException("after delivery");};
            h.model.confirm(); h.model.tick(); prepared.finishRelease(); h.model.tick();
            assertSame(actual,h.model.receipt().orElseThrow().result());
            assertEquals(RuntimeInstallationWorkbench.State.INSTALLED,h.model.state());
            assertEquals(1,prepared.releases); assertFalse(h.project.busy()); assertTrue(h.model.canStart());
        }
    }

    @Test void synchronousPrepareCallbackThenExceptionKeepsThePreparedResourceAndBorrow() throws Exception {
        try(var h=saved("prepare-callback-throw")) {
            var prepared=new FakePrepared(plan(UUID.randomUUID(),h.project.projectId().orElseThrow(),h.project.savedVersion().orElseThrow(),h.project.activeDocument().orElseThrow()));
            h.target.prepareHook=completed->{completed.accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));throw new IllegalStateException("after preparation delivery");};
            h.model.prepare(PROFILE,"Runtime"); h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.READY,h.model.state());assertTrue(h.project.busy());
            assertTrue(h.model.diagnostic().isEmpty());assertEquals(0,prepared.releases);assertTrue(h.target.submissions.isEmpty());
            h.model.cancel();prepared.finishRelease();h.model.tick();assertEquals(1,prepared.releases);assertFalse(h.project.busy());
        }
    }

    @Test void synchronousRecordAndLookupFailuresUnlockTheirOwnReadWithoutChangingInstallationOutcome() throws Exception {
        try(var h=saved("read-failures")) {
            var failedRead=new IllegalStateException("record read failed");h.target.recordsFailure=failedRead;
            h.model.refreshRecords();h.model.tick();assertFalse(h.model.readingRecords());
            assertSame(failedRead,h.model.recordsDiagnostic().orElseThrow());assertTrue(h.model.diagnostic().isEmpty());
            assertTrue(h.model.records().isEmpty());assertTrue(h.target.submissions.isEmpty());
            h.target.recordsFailure=null;h.model.refreshRecords();h.target.recordReplies.get(0).accept(List.of());h.model.tick();
            assertFalse(h.model.readingRecords());assertTrue(h.model.recordsDiagnostic().isEmpty());
            assertEquals(RuntimeInstallationWorkbench.text("install_records_hint"),h.model.recordsStatus());
            var prepared=h.ready();h.model.confirm();var actual=result(RuntimeInstallationWorkbench.State.UNKNOWN);
            h.target.results.get(0).accept(actual);h.model.tick();prepared.finishRelease();h.model.tick();
            var failedLookup=new IllegalStateException("lookup failed");h.target.lookupFailure=failedLookup;
            h.model.query();h.model.tick();assertTrue(h.model.canQuery());assertFalse(h.model.canStart());
            assertSame(failedLookup,h.model.diagnostic().orElseThrow());assertSame(actual,h.model.receipt().orElseThrow().result());
            h.target.lookupFailure=null;h.model.query();assertEquals(List.of(h.target.submissions.get(0),h.target.submissions.get(0)),h.target.lookups);
            h.target.lookupReplies.get(0).accept(LABEL);h.model.tick();assertTrue(h.model.canQuery());
            assertEquals(RuntimeInstallationWorkbench.State.UNKNOWN,h.model.state());assertEquals(1,h.target.submissions.size());
        }
    }

    @Test void duplicateResultAndReleaseCallbacksCannotRewriteReceiptOrReleaseAnotherBorrow() throws Exception {
        try(var h=saved("duplicates")) {
            var prepared=h.ready();h.model.confirm();var actual=result(RuntimeInstallationWorkbench.State.INSTALLED);
            h.target.results.get(0).accept(actual);h.target.results.get(0).accept(result(RuntimeInstallationWorkbench.State.UNKNOWN));
            h.model.tick();assertEquals(1,prepared.releases);prepared.finishRelease();prepared.finishRelease();h.model.tick();
            assertSame(actual,h.model.receipt().orElseThrow().result());assertFalse(h.project.busy());
            h.model.prepare(PROFILE,"Next");assertTrue(h.project.busy());
            prepared.finishRelease();h.model.tick();assertTrue(h.project.busy(),"a repeated old release must not release the next source");
            h.target.preparations.get(1).accept(new RuntimeInstallationWorkbench.Preparation(null,LABEL,null));h.model.tick();
            assertFalse(h.project.busy());assertEquals(1,h.target.submissions.size());
        }
    }

    @Test void recordRefreshFailureAndSuccessDoNotReplaceTheOriginalInstallationFailure() throws Exception {
        try(var h=saved("separate-record-diagnostics")) {
            var installFailure=new IllegalStateException("preparation failed");
            h.target.prepareFailure=installFailure;h.model.prepare(PROFILE,"Runtime");h.model.tick();
            TextContent installStatus=h.model.status();
            var recordFailure=new IllegalStateException("record listing failed");h.target.recordsFailure=recordFailure;
            h.model.refreshRecords();h.model.tick();
            assertSame(installFailure,h.model.diagnostic().orElseThrow());assertSame(installStatus,h.model.status());
            assertSame(recordFailure,h.model.recordsDiagnostic().orElseThrow());
            assertEquals(RuntimeInstallationWorkbench.text("install_records_failed"),h.model.recordsStatus());
            h.target.recordsFailure=null;h.model.refreshRecords();
            assertTrue(h.model.recordsDiagnostic().isEmpty());
            h.target.recordReplies.get(0).accept(List.of());h.model.tick();
            assertTrue(h.model.recordsDiagnostic().isEmpty());assertFalse(h.model.readingRecords());
            assertSame(installFailure,h.model.diagnostic().orElseThrow());assertSame(installStatus,h.model.status());
            assertEquals(RuntimeInstallationWorkbench.State.FAILED,h.model.state());
        }
    }

    @Test void lateRecordFailureForReplacedConnectionCannotPolluteTheNewTarget() throws Exception {
        try(var h=new Harness(temporary.resolve("stale-record-failure"))) {
            h.model.refreshRecords();
            var replacement=new FakeTarget();
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(replacement,"World B",UUID.randomUUID(),Map.of(DIM,DIM))));
            h.target.recordFailures.get(0).accept(new IllegalStateException("old world stopped"));h.model.tick();
            assertFalse(h.model.readingRecords());assertTrue(h.model.recordsDiagnostic().isEmpty());
            assertTrue(h.model.diagnostic().isEmpty());assertEquals(RuntimeInstallationWorkbench.State.IDLE,h.model.state());
            h.model.refreshRecords();replacement.recordReplies.get(0).accept(List.of());h.model.tick();
            assertFalse(h.model.readingRecords());assertTrue(h.model.recordsDiagnostic().isEmpty());
            assertTrue(h.model.records().isEmpty());assertTrue(h.target.requests.isEmpty());
        }
    }

    @Test void newPreparationReplacesOldReceiptAndKeepsItsCapturedDisplayIdentityThroughFailure() throws Exception {
        try(var h=saved("new-task-identity")) {
            var first=h.ready();h.model.confirm();
            h.target.results.get(0).accept(result(RuntimeInstallationWorkbench.State.INSTALLED));h.model.tick();
            first.finishRelease();h.model.tick();assertTrue(h.model.receipt().isPresent());
            h.edit("new saved content");h.project.saveAll();h.drain();
            UUID commit=h.project.savedVersion().orElseThrow(),document=h.project.activeDocument().orElseThrow();
            var replacement=new FakeTarget();UUID world=UUID.randomUUID();
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(replacement,"World B",world,Map.of(DIM,DIM))));
            h.model.prepare(PROFILE,"Second runtime");
            assertTrue(h.model.receipt().isEmpty());assertTrue(h.model.preparedPlan().isEmpty());
            var captured=h.model.taskInput().orElseThrow();
            assertEquals("World B",captured.worldLabel());assertEquals(world,captured.worldId());
            assertEquals(commit,captured.commitId());assertEquals(document,captured.documentId());
            assertEquals(h.project.projectId().orElseThrow(),captured.projectId());
            assertEquals(new InstallationPlan.Key("test","Second runtime"),captured.key());
            h.edit("later unsaved content");h.selection.set(Optional.empty());
            replacement.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(null,LABEL,null));h.model.tick();
            assertSame(captured,h.model.taskInput().orElseThrow());assertTrue(h.model.receipt().isEmpty());
            assertEquals(h.project.projectId().orElseThrow(),h.model.taskInput().orElseThrow().projectId());
            assertFalse(h.project.busy());assertTrue(h.project.unsaved());
        }
    }

    @Test void cancelledPreparationRetainsValidatedPlanAfterResourcesAndWorldAreReleased() throws Exception {
        try(var h=saved("cancelled-display-identity")) {
            h.model.prepare(PROFILE,"Cancelled runtime");
            var captured=h.model.taskInput().orElseThrow();
            var prepared=h.prepared(h.target.requests.get(0));
            h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            h.model.cancel();prepared.finishRelease();h.model.tick();h.selection.set(Optional.empty());
            assertEquals(RuntimeInstallationWorkbench.State.CANCELLED,h.model.state());
            assertEquals(prepared.plan(),h.model.preparedPlan().orElseThrow());assertTrue(h.model.preparedTarget().isEmpty());
            assertFalse(h.model.canConfirm());assertFalse(h.project.busy());assertEquals(1,prepared.releases);
            assertSame(captured,h.model.taskInput().orElseThrow());
            assertEquals(new InstallationPlan.Key("test","Cancelled runtime"),captured.key());
            assertEquals(h.worldId,captured.worldId());assertEquals(0,h.target.submissions.size());
        }
    }

    @Test void recordDisplayIdentityBelongsToAcceptedListAndCannotFollowActiveWorld() throws Exception {
        try(var h=new Harness(temporary.resolve("record-display-identity"))) {
            h.model.refreshRecords();assertTrue(h.model.recordsInput().isEmpty());
            h.target.recordReplies.get(0).accept(List.of());h.model.tick();
            var captured=h.model.recordsInput().orElseThrow();
            assertEquals("World A",captured.worldLabel());assertEquals(h.worldId,captured.worldId());
            var replacement=new FakeTarget();UUID next=UUID.randomUUID();
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(replacement,"World B",next,Map.of(DIM,DIM))));
            assertEquals(captured,h.model.recordsInput().orElseThrow());
            h.model.refreshRecords();assertTrue(h.model.recordsInput().isEmpty());
            replacement.recordReplies.get(0).accept(List.of());h.model.tick();
            assertEquals(new RuntimeInstallationWorkbench.RecordsInput("World B",next),h.model.recordsInput().orElseThrow());
        }
    }

    @Test void replacementPinsExplicitOldRecordAndNewSavedSourceThroughSuccess() throws Exception {
        try(var h=saved("replacement-success")) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            var selected=h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE);
            UUID saved=h.project.savedVersion().orElseThrow();byte[] before=h.current();
            h.edit("unsaved replacement draft");
            assertTrue(h.model.canReplace(selected));h.model.prepareReplacement(PROFILE,selected);
            var request=h.target.requests.get(0);
            assertSame(old,request.replacement());assertNull(request.recovery());assertFalse(request.previous());
            assertEquals(old.plan().key().name(),request.name());assertEquals(saved,request.source().saved().commitId());
            assertEquals(old.plan(),h.model.taskInput().orElseThrow().replacedPlan());
            assertEquals(request.source().saved().state().projectId(),h.model.taskInput().orElseThrow().projectId());
            assertNotEquals(old.plan().projectId(),h.model.taskInput().orElseThrow().projectId());
            assertEquals(RuntimeInstallationWorkbench.ReplacementBlocker.TASK_BUSY,h.model.replacementBlocker(selected));
            var prepared=h.prepared(request);h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            assertTrue(h.model.canConfirm());h.model.confirm();
            var actual=result(RuntimeInstallationWorkbench.State.INSTALLED);h.target.results.get(0).accept(actual);h.model.tick();
            assertTrue(h.project.busy());prepared.finishRelease();h.model.tick();
            var receipt=h.model.receipt().orElseThrow();
            assertEquals(old.plan(),receipt.replacedPlan());assertEquals(saved,receipt.plan().commitId());
            assertNotEquals(old.plan().installationId(),receipt.plan().installationId());assertSame(actual,receipt.result());
            assertArrayEquals(before,h.current());assertFalse(h.project.busy());assertTrue(h.project.unsaved());
        }
    }

    @Test void unavailableReplacementNeverBorrowsSourceOrAutomaticallyRecovers() throws Exception {
        for(var reason:List.of(RuntimeInstallationWorkbench.ReplacementBlocker.OFFLINE,
                RuntimeInstallationWorkbench.ReplacementBlocker.RUNTIME_UNAVAILABLE,RuntimeInstallationWorkbench.ReplacementBlocker.RUNNING,
                RuntimeInstallationWorkbench.ReplacementBlocker.PARTICIPANTS,RuntimeInstallationWorkbench.ReplacementBlocker.RESET_INCOMPLETE,
                RuntimeInstallationWorkbench.ReplacementBlocker.HOST_BUSY))try(var h=saved("replacement-blocked-"+reason)) {
            var selected=h.publish(record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())),
                    reason!=RuntimeInstallationWorkbench.ReplacementBlocker.OFFLINE,reason);
            assertEquals(reason,h.model.replacementBlocker(selected));assertFalse(h.model.canReplace(selected));
            assertThrows(IllegalStateException.class,()->h.model.prepareReplacement(PROFILE,selected));
            assertFalse(h.project.busy());assertTrue(h.target.requests.isEmpty());assertTrue(h.target.submissions.isEmpty());
        }
    }

    @Test void replacementRejectsUnselectedStaleNonactiveAndWrongTypeRecordsBeforeBorrow() throws Exception {
        try(var h=saved("replacement-selection")) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            var selected=new RuntimeInstallationWorkbench.RecordView(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE);
            assertEquals(RuntimeInstallationWorkbench.ReplacementBlocker.STALE_SELECTION,h.model.replacementBlocker(selected));
            assertThrows(IllegalStateException.class,()->h.model.prepareReplacement(PROFILE,selected));
            var pending=new LocalInstallationIndex.Record(old.plan(),LocalInstallationIndex.State.PREPARED,old.bundle());
            var pendingView=h.publish(pending,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE);
            assertEquals(RuntimeInstallationWorkbench.ReplacementBlocker.RECORD_NOT_ACTIVE,h.model.replacementBlocker(pendingView));
            assertThrows(IllegalStateException.class,()->h.model.prepareReplacement(PROFILE,pendingView));
            var p=old.plan();
            var wrong=record(new InstallationPlan(p.installationId(),new InstallationPlan.Key("other",p.key().name()),p.projectId(),p.commitId(),p.documentId(),p.adapterId(),p.factoryId(),p.targetVersion(),p.modules(),p.dimensions()));
            var wrongView=h.publish(wrong,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE);
            assertThrows(IllegalArgumentException.class,()->h.model.prepareReplacement(PROFILE,wrongView));
            var valid=h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE);
            h.selection.set(Optional.of(new RuntimeInstallationWorkbench.Selection(new FakeTarget(),"World A",h.worldId,Map.of(DIM,DIM))));
            assertEquals(RuntimeInstallationWorkbench.ReplacementBlocker.STALE_SELECTION,h.model.replacementBlocker(valid));
            assertThrows(IllegalStateException.class,()->h.model.prepareReplacement(PROFILE,valid));
            assertFalse(h.project.busy());assertTrue(h.target.requests.isEmpty());
        }
    }

    @Test void replacementCannotConfirmWrongOldPlanOrReusedInstallationIdentity() throws Exception {
        for(int mismatch=0;mismatch<2;mismatch++)try(var h=saved("replacement-plan-"+mismatch)) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            var selected=h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE);
            h.model.prepareReplacement(PROFILE,selected);var expected=h.prepared(h.target.requests.get(0));
            var p=expected.plan();
            var candidate=mismatch==0?p:new InstallationPlan(old.plan().installationId(),p.key(),p.projectId(),p.commitId(),p.documentId(),p.adapterId(),p.factoryId(),p.targetVersion(),p.modules(),p.dimensions());
            var prepared=new FakePrepared(candidate,mismatch==0?null:old.plan());
            h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            assertFalse(h.model.canConfirm());assertEquals(1,prepared.releases);assertTrue(h.project.busy());
            assertTrue(h.model.preparedPlan().isEmpty(),"A mismatched replacement must not become display identity");
            prepared.finishRelease();h.model.tick();assertEquals(RuntimeInstallationWorkbench.State.FAILED,h.model.state());
            assertFalse(h.project.busy());assertTrue(h.target.submissions.isEmpty());
            assertEquals(old.plan(),h.model.taskInput().orElseThrow().replacedPlan());
            assertEquals(h.project.projectId().orElseThrow(),h.model.taskInput().orElseThrow().projectId());
        }
    }

    @Test void replacementCancellationReleasesLatePreparationWithoutSubmittingOldRuntime() throws Exception {
        try(var h=saved("replacement-cancel")) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            h.model.prepareReplacement(PROFILE,h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE));
            var prepared=h.prepared(h.target.requests.get(0));h.model.dismiss();
            assertTrue(h.target.cancellations.get(0).getAsBoolean());assertTrue(h.project.busy());
            h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            assertEquals(1,prepared.releases);assertTrue(h.target.submissions.isEmpty());prepared.finishRelease();h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.CANCELLED,h.model.state());assertFalse(h.project.busy());
            assertEquals(old.plan(),h.model.taskInput().orElseThrow().replacedPlan());assertTrue(h.model.receipt().isEmpty());
        }
    }

    @Test void replacementLateSuccessAfterCancelRetainsActualReceiptAndBothPlans() throws Exception {
        try(var h=saved("replacement-late-success")) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            h.model.prepareReplacement(PROFILE,h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE));
            var prepared=h.prepared(h.target.requests.get(0));h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            h.model.confirm();h.model.dismiss();assertFalse(h.target.submitCancellation.get(0).getAsBoolean());
            h.model.cancel();assertTrue(h.target.submitCancellation.get(0).getAsBoolean());
            var actual=result(RuntimeInstallationWorkbench.State.INSTALLED);h.target.results.get(0).accept(actual);h.model.tick();prepared.finishRelease();h.model.tick();
            assertSame(actual,h.model.receipt().orElseThrow().result());assertEquals(old.plan(),h.model.receipt().orElseThrow().replacedPlan());
            assertEquals(prepared.plan(),h.model.receipt().orElseThrow().plan());assertEquals(1,h.target.submissions.size());assertFalse(h.project.busy());
        }
    }

    @Test void replacementReadyCancellationRetainsValidatedIdentitiesButNoSubmissionAuthority() throws Exception {
        try(var h=saved("replacement-ready-cancel")) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            h.model.prepareReplacement(PROFILE,h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE));
            var prepared=h.prepared(h.target.requests.get(0));
            h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            var displayed=h.model.preparedPlan().orElseThrow();
            h.model.cancel();prepared.finishRelease();h.model.tick();h.selection.set(Optional.empty());
            assertEquals(displayed,h.model.preparedPlan().orElseThrow());
            assertEquals(prepared.plan().installationId(),displayed.installationId());assertEquals(Map.of(DIM,DIM),displayed.dimensions());
            assertEquals(old.plan(),h.model.taskInput().orElseThrow().replacedPlan());
            assertEquals(h.project.projectId().orElseThrow(),h.model.taskInput().orElseThrow().projectId());
            assertTrue(h.model.preparedTarget().isEmpty());assertTrue(h.model.receipt().isEmpty());
            assertFalse(h.model.canConfirm());assertThrows(IllegalStateException.class,h.model::confirm);
            assertEquals(1,prepared.releases);assertFalse(h.project.busy());assertTrue(h.target.submissions.isEmpty());
        }
    }

    @Test void replacementUnknownKeepsOldAndNewIdentityWithoutAutomaticRetryOrRecovery() throws Exception {
        try(var h=saved("replacement-unknown")) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            h.model.prepareReplacement(PROFILE,h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE));
            var prepared=h.prepared(h.target.requests.get(0));h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));h.model.tick();
            h.model.confirm();h.target.results.get(0).accept(result(RuntimeInstallationWorkbench.State.UNKNOWN));h.model.tick();prepared.finishRelease();h.model.tick();
            assertTrue(h.model.canQuery());assertFalse(h.model.canStart());assertFalse(h.project.busy());
            h.model.query();h.target.lookupReplies.get(0).accept(RuntimeInstallationWorkbench.text("install_receipt_published"));h.model.tick();
            assertEquals(RuntimeInstallationWorkbench.State.UNKNOWN,h.model.state());assertFalse(h.model.canStart());
            assertEquals(old.plan(),h.model.receipt().orElseThrow().replacedPlan());assertEquals(prepared.plan(),h.model.receipt().orElseThrow().plan());
            assertEquals(1,h.target.requests.size());assertEquals(1,h.target.submissions.size());
        }
    }

    @Test void requestCannotCombineReplacementAndRecovery() throws Exception {
        try(var h=saved("replacement-request")) {
            var old=record(plan(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
            h.model.prepareReplacement(PROFILE,h.publish(old,true,RuntimeInstallationWorkbench.ReplacementBlocker.NONE));
            var r=h.target.requests.get(0);
            assertThrows(IllegalArgumentException.class,()->new RuntimeInstallationWorkbench.Request(r.source(),PROFILE,r.name(),r.target(),old,false,old));
            assertThrows(IllegalArgumentException.class,()->new RuntimeInstallationWorkbench.Request(r.source(),PROFILE,r.name(),r.target(),null,true,old));
            assertThrows(IllegalArgumentException.class,()->new RuntimeInstallationWorkbench.Request(null,null,r.name(),r.target(),null,false,old));
            h.target.preparations.get(0).accept(new RuntimeInstallationWorkbench.Preparation(null,LABEL,null));h.model.tick();
            assertFalse(h.project.busy());
        }
    }

    private static LocalInstallationIndex.Record record(InstallationPlan plan) {
        return new LocalInstallationIndex.Record(plan,LocalInstallationIndex.State.ACTIVE,new BlobRef(BlobRef.Kind.ASSET,"a".repeat(64),1));
    }

    private Harness saved(String folder) throws Exception {
        var h=new Harness(temporary.resolve(folder)); h.project.create(h.path,"Installation project");h.drain();
        h.project.createDocument(TYPE,"Saved note"); h.edit("persisted");h.project.saveAll();h.drain();return h;
    }
    private static RuntimeInstallationWorkbench.Result result(RuntimeInstallationWorkbench.State state) {
        return new RuntimeInstallationWorkbench.Result(state,new TextContent.Literal(state.name()),null);
    }
    private static InstallationPlan plan(UUID installation,UUID project,UUID commit,UUID document) {
        return new InstallationPlan(installation,new InstallationPlan.Key("test","Runtime"),project,commit,document,
                ADAPTER,FACTORY,"1",Map.of(OWNER,"1"),Map.of(DIM,DIM));
    }
    private static ExtensionRegistry registry() {
        var api=new ApiVersion(1,0);var builder=new ExtensionRegistryBuilder(api,Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);builder.define(ResourceProducers.KIND);builder.define(ResourceContentValidators.KIND);
        builder.define(RuntimeInstallationProfile.KIND);builder.addModule(new ModuleSpec(OWNER,"1",api,Set.of(),Set.of()));
        builder.add(DocumentTypes.KIND,new Contribution(new ContributionKey(DocumentTypes.KIND.id(),TYPE),OWNER,Contribution.Side.COMMON,Set.of()),new NoteType());
        // Discovery metadata only: these tests never resolve or call a native adapter/factory.
        builder.add(RuntimeInstallationProfile.KIND,new Contribution(new ContributionKey(RuntimeInstallationProfile.KIND.id(),id("test:profile")),OWNER,Contribution.Side.COMMON,Set.of()),PROFILE);
        DocumentEditors.register(builder,TYPE,OWNER,new NoteEditor());return builder.freeze();
    }
    private static final class NoteType implements DocumentType<String> {
        public Class<String> valueType(){return String.class;} public EditorId codecId(){return id("test:utf8");}
        public int schemaVersion(){return 1;} public Set<Integer> readableSchemas(){return Set.of(1);}
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
        final Deque<Runnable> tasks=new ArrayDeque<>();boolean reject;
        public void execute(Runnable task){if(reject)throw new RejectedExecutionException("owner unavailable");tasks.addLast(task);}
        void next(){tasks.removeFirst().run();}
    }
    private static final class FakePrepared implements RuntimeInstallationWorkbench.Prepared {
        final InstallationPlan plan,replacedPlan;int releases;Consumer<Throwable> releaseCompletion;
        FakePrepared(InstallationPlan plan){this(plan,null);}
        FakePrepared(InstallationPlan plan,InstallationPlan replacedPlan){this.plan=plan;this.replacedPlan=replacedPlan;}
        public InstallationPlan plan(){return plan;}
        public InstallationPlan replacedPlan(){return replacedPlan;}
        public void release(Consumer<Throwable> completed){releases++;releaseCompletion=completed;}
        void finishRelease(){assertNotNull(releaseCompletion,"release must have started");releaseCompletion.accept(null);}
    }
    private static final class FakeTarget implements RuntimeInstallationWorkbench.Target {
        final List<RuntimeInstallationWorkbench.Request> requests=new ArrayList<>();
        final List<BooleanSupplier> cancellations=new ArrayList<>(),submitCancellation=new ArrayList<>();
        final List<Consumer<RuntimeInstallationWorkbench.Preparation>> preparations=new ArrayList<>();
        final List<Consumer<RuntimeInstallationWorkbench.Result>> results=new ArrayList<>();
        final List<UUID> submissions=new ArrayList<>(),lookups=new ArrayList<>();
        final List<Consumer<List<RuntimeInstallationWorkbench.RecordView>>> recordReplies=new ArrayList<>();
        final List<Consumer<Throwable>> recordFailures=new ArrayList<>();
        final List<Consumer<TextContent>> lookupReplies=new ArrayList<>();
        RuntimeException prepareFailure,recordsFailure,lookupFailure;
        Consumer<Consumer<RuntimeInstallationWorkbench.Preparation>> prepareHook=ignored->{};
        Consumer<Consumer<RuntimeInstallationWorkbench.Result>> submitHook=ignored->{};int polls;
        public void prepare(RuntimeInstallationWorkbench.Request request,BooleanSupplier cancelled,Consumer<RuntimeInstallationWorkbench.Preparation> completed){
            requests.add(request);cancellations.add(cancelled);preparations.add(completed);if(prepareFailure!=null)throw prepareFailure;prepareHook.accept(completed);
        }
        public void submit(RuntimeInstallationWorkbench.Prepared prepared,UUID request,BooleanSupplier cancelled,Consumer<RuntimeInstallationWorkbench.Result> completed){
            submissions.add(request);submitCancellation.add(cancelled);results.add(completed);submitHook.accept(completed);
        }
        public void records(Consumer<List<RuntimeInstallationWorkbench.RecordView>> completed,Consumer<Throwable> failed){if(recordsFailure!=null)throw recordsFailure;recordReplies.add(completed);recordFailures.add(failed);}
        public void lookup(UUID request,Consumer<TextContent> completed,Consumer<Throwable> failed){lookups.add(request);if(lookupFailure!=null)throw lookupFailure;lookupReplies.add(completed);}
        public void poll(){polls++;}
    }
    private static final class Harness implements AutoCloseable {
        final Path path;final UUID worldId=UUID.randomUUID();final Queue io=new Queue(),completion=new Queue();
        final FakeTarget target=new FakeTarget();final AtomicReference<Optional<RuntimeInstallationWorkbench.Selection>> selection;
        final ProjectWorkbench project=new ProjectWorkbench(registry(),io,completion);final RuntimeInstallationWorkbench model;
        Harness(Path path){this.path=path;selection=new AtomicReference<>(Optional.of(new RuntimeInstallationWorkbench.Selection(target,"World A",worldId,Map.of(DIM,DIM))));model=new RuntimeInstallationWorkbench(project,selection::get);}
        void edit(String value){var address=new ProjectWorkbench.Address(project.activeDocument().orElseThrow(),"root","value");project.input(address,value);project.apply(address);}
        byte[] current() throws Exception{return Files.readAllBytes(path.resolve("CURRENT"));}
        FakePrepared prepared(RuntimeInstallationWorkbench.Request request){
            var saved=request.source().saved();
            return new FakePrepared(new InstallationPlan(UUID.randomUUID(),new InstallationPlan.Key(request.profile().gameType(),request.name()),
                    saved.state().projectId(),saved.commitId(),request.source().documentId(),request.profile().adapterId(),
                    request.profile().factoryId(),"1",Map.of(OWNER,"1"),request.target().dimensions()),request.replacement()==null?null:request.replacement().plan());
        }
        RuntimeInstallationWorkbench.RecordView publish(LocalInstallationIndex.Record record,boolean online,RuntimeInstallationWorkbench.ReplacementBlocker blocker){
            var selected=new RuntimeInstallationWorkbench.RecordView(record,online,blocker);model.refreshRecords();
            target.recordReplies.get(target.recordReplies.size()-1).accept(List.of(selected));model.tick();return selected;
        }
        FakePrepared ready(){model.prepare(PROFILE,"Runtime");var prepared=prepared(target.requests.get(target.requests.size()-1));target.preparations.get(target.preparations.size()-1).accept(new RuntimeInstallationWorkbench.Preparation(prepared,null,null));model.tick();assertTrue(model.canConfirm());return prepared;}
        void drain(){for(int i=0;!io.tasks.isEmpty()||!completion.tasks.isEmpty();i++){assertTrue(i<32);if(!io.tasks.isEmpty())io.next();if(!completion.tasks.isEmpty())completion.next();}}
        public void close(){completion.reject=false;drain();if(project.hasProject()&&!project.busy()){project.closeProject(true);drain();}}
    }
    private static void onWorker(Runnable action) throws Exception {
        var failure=new AtomicReference<Throwable>();var worker=new Thread(()->{try{action.run();}catch(Throwable thrown){failure.set(thrown);}},"installation-test-worker");
        worker.start();worker.join(10000);assertFalse(worker.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static EditorId id(String value){return EditorId.parse(value);}
}
