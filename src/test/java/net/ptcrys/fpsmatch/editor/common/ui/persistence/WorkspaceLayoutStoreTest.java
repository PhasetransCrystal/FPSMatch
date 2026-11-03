package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.persistence.WorkspaceLayoutStore.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.ptcrys.fpsmatch.editor.common.ui.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLayoutStoreTest {
    private static final String SCOPE="a".repeat(64);
    @TempDir Path root;
    private Path file(){return root.resolve(SCOPE+".json");}
    private WorkspaceLayoutStore store(){return new WorkspaceLayoutStore(root,SCOPE);}
    private static WorkspaceState renamed(WorkspaceState state,String name){var before=state.workspaces().get(0);var after=new WorkspaceInstance(before.id(),before.templateId(),before.templateVersion(),name,before.visible(),before.panels());return new WorkspaceState(List.of(after),state.activeWorkspace(),state.savedLayouts());}
    private void noTemporaryFiles()throws IOException{try(var paths=Files.list(root)){assertTrue(paths.noneMatch(path->path.getFileName().toString().endsWith(".tmp")));}}
    private static void reason(Reason expected,org.junit.jupiter.api.function.Executable action){assertEquals(expected,assertThrows(StorageException.class,action).reason());}

    @Test void missingLoadDoesNotCreateDirectoriesAndSaveReturnsAFreshReadRevision()throws IOException{
        var directory=root.resolve("nested");var store=new WorkspaceLayoutStore(directory,SCOPE);var missing=store.load();assertEquals(Status.MISSING,missing.status());assertTrue(missing.state().isEmpty());assertFalse(Files.exists(directory));
        var state=WorkspaceStateCodecTest.sample("One");var saved=store.save(state,missing);assertEquals(Status.LOADED,saved.status());assertEquals(state,saved.state().orElseThrow());assertNotSame(missing.revision(),saved.revision());
        assertEquals(state,new WorkspaceLayoutStore(directory,SCOPE).load().state().orElseThrow());
        reason(Reason.CHANGED,()->store.save(renamed(state,"Two"),missing));
    }
    @Test void reopeningRestoresCurrentAndSavedBaselinesIndependently()throws IOException{
        var store=store();var state=WorkspaceStateCodecTest.sample("One");store.save(state,store.load());var reloaded=new WorkspaceLayoutStore(root,SCOPE).load();assertEquals(state,reloaded.state().orElseThrow());
        var manager=new WorkspaceManager(List.of());manager.restore(reloaded.state().orElseThrow());manager.restoreLayout(state.activeWorkspace());assertEquals(state.savedLayouts().get(state.activeWorkspace()),manager.workspaces().get(0).panels());noTemporaryFiles();
    }
    @Test void corruptAndUnknownVersionOriginalsStayProtectedUntilExplicitReread()throws IOException{
        var store=store();var state=WorkspaceStateCodecTest.sample("One");
        for(String original:List.of("{broken","{\"formatVersion\":2}")){
            byte[] bytes=original.getBytes(StandardCharsets.UTF_8);Files.write(file(),bytes);var read=store.load();assertEquals(Status.PROTECTED,read.status());assertTrue(read.state().isEmpty());assertTrue(read.issue().isPresent());
            reason(Reason.PROTECTED,()->store.save(state,read));assertArrayEquals(bytes,Files.readAllBytes(file()));
            Files.write(file(),WorkspaceStateCodec.encode(state));reason(Reason.PROTECTED,()->store.save(state,read));assertEquals(Status.LOADED,store.load().status());
        }
        var reread=store.load();assertEquals(state,store.save(state,reread).state().orElseThrow());noTemporaryFiles();
    }
    @Test void missingTicketRejectsAFileThatAppearedAfterLoad()throws IOException{
        var store=store();var missing=store.load();byte[] original="external contents".getBytes(StandardCharsets.UTF_8);Files.write(file(),original);
        reason(Reason.CHANGED,()->store.save(WorkspaceStateCodecTest.sample("One"),missing));assertArrayEquals(original,Files.readAllBytes(file()));noTemporaryFiles();
    }
    @Test void shaRejectsSameSizeContentChangesEvenWhenTimestampWasRestored()throws IOException{
        var store=store();var state=WorkspaceStateCodecTest.sample("One");var saved=store.save(state,store.load());var time=Files.getLastModifiedTime(file());byte[] external=WorkspaceStateCodec.encode(renamed(state,"Two"));assertEquals(Files.size(file()),external.length);Files.write(file(),external);Files.setLastModifiedTime(file(),time);
        reason(Reason.CHANGED,()->store.save(state,saved));assertArrayEquals(external,Files.readAllBytes(file()));noTemporaryFiles();
    }
    @Test void deletedOrNonFileReplacementCannotBeOverwrittenUsingAnOldTicket()throws IOException{
        var store=store();var state=WorkspaceStateCodecTest.sample("One");var saved=store.save(state,store.load());Files.delete(file());reason(Reason.CHANGED,()->store.save(state,saved));assertFalse(Files.exists(file()));
        Files.createDirectory(file());assertEquals(Status.PROTECTED,store.load().status());reason(Reason.CHANGED,()->store.save(state,saved));assertTrue(Files.isDirectory(file()));
    }
    @Test void staleWriterAndForeignScopeTicketAreRejectedBeforeAnyReplacement()throws IOException{
        var first=store();var state=WorkspaceStateCodecTest.sample("One");first.save(state,first.load());var second=store();var older=second.load();var latest=first.save(renamed(state,"Two"),first.load());
        reason(Reason.CHANGED,()->second.save(state,older));assertEquals(latest.state(),second.load().state());
        var other=new WorkspaceLayoutStore(root,"b".repeat(64));reason(Reason.TICKET_MISMATCH,()->other.save(state,latest));assertEquals(Status.MISSING,other.load().status());noTemporaryFiles();
    }
    @Test void externalChangeAfterStagingIsRecheckedBeforeCommit()throws IOException{
        var state=WorkspaceStateCodecTest.sample("One");var ordinary=store();var prior=ordinary.save(state,ordinary.load());byte[] external=WorkspaceStateCodec.encode(renamed(state,"External"));
        var racing=new WorkspaceLayoutStore(root,SCOPE,new CommitIO(){@Override void staged(Path temporary,Path target)throws IOException{Files.write(target,external);}@Override void replace(Path temporary,Path target){fail("Changed target must never reach replacement");}});
        reason(Reason.CHANGED,()->racing.save(renamed(state,"Two"),prior));assertArrayEquals(external,Files.readAllBytes(file()));noTemporaryFiles();
    }
    @Test void atomicUnsupportedAndStagingFailureKeepOriginalAndRemoveOnlyTemporaryFile()throws IOException{
        var state=WorkspaceStateCodecTest.sample("One");var ordinary=store();var prior=ordinary.save(state,ordinary.load());byte[] original=Files.readAllBytes(file());
        var unavailable=new WorkspaceLayoutStore(root,SCOPE,new CommitIO(){@Override void replace(Path temporary,Path target)throws IOException{throw new AtomicMoveNotSupportedException(temporary.toString(),target.toString(),"test");}});
        reason(Reason.ATOMIC_UNSUPPORTED,()->unavailable.save(renamed(state,"Two"),prior));assertArrayEquals(original,Files.readAllBytes(file()));noTemporaryFiles();
        var failing=new WorkspaceLayoutStore(root,SCOPE,new CommitIO(){@Override void staged(Path temporary,Path target)throws IOException{throw new IOException("staging failure");}});
        reason(Reason.IO,()->failing.save(renamed(state,"Two"),prior));assertArrayEquals(original,Files.readAllBytes(file()));noTemporaryFiles();
    }
    @Test void byteBudgetFailureDoesNotOverwriteEitherValidOrOversizedOriginal()throws IOException{
        var store=store();var state=WorkspaceStateCodecTest.sample("One");var prior=store.save(state,store.load());byte[] original=Files.readAllBytes(file());
        reason(Reason.LIMIT_EXCEEDED,()->store.save(WorkspaceStateCodecTest.oversized(),prior));assertArrayEquals(original,Files.readAllBytes(file()));noTemporaryFiles();
        Files.write(file(),new byte[WorkspaceStateCodec.MAX_BYTES+1]);var protectedFile=store.load();assertEquals(Status.PROTECTED,protectedFile.status());assertEquals(Reason.LIMIT_EXCEEDED,protectedFile.issue().orElseThrow().reason());
        reason(Reason.PROTECTED,()->store.save(state,protectedFile));assertEquals(WorkspaceStateCodec.MAX_BYTES+1,Files.size(file()));
    }
    @Test void invalidDirectoryAndScopeNeverBecomeWritableDefaults()throws IOException{
        var ordinaryFile=root.resolve("not-directory");Files.writeString(ordinaryFile,"keep");var store=new WorkspaceLayoutStore(ordinaryFile,SCOPE);var read=store.load();assertEquals(Status.PROTECTED,read.status());assertEquals(Reason.INVALID_PATH,read.issue().orElseThrow().reason());
        reason(Reason.PROTECTED,()->store.save(WorkspaceStateCodecTest.sample("One"),read));assertEquals("keep",Files.readString(ordinaryFile));
        for(String bad:List.of("../escape","A".repeat(64),"a".repeat(63),""))assertThrows(IllegalArgumentException.class,()->new WorkspaceLayoutStore(root,bad));
    }
    @Test void failedFirstReplacementLeavesNoOriginalAndNoStagingFile()throws IOException{
        var store=new WorkspaceLayoutStore(root,SCOPE,new CommitIO(){@Override void replace(Path temporary,Path target)throws IOException{throw new IOException("replacement failure");}});
        var missing=store.load();reason(Reason.IO,()->store.save(WorkspaceStateCodecTest.sample("One"),missing));assertFalse(Files.exists(file()));assertEquals(Status.MISSING,store.load().status());noTemporaryFiles();
    }
    @Test void externalChangeAfterCommitCannotBeReportedAsAConfirmedSave()throws IOException{
        var state=WorkspaceStateCodecTest.sample("One");var ordinary=store();var prior=ordinary.save(state,ordinary.load());byte[] external=WorkspaceStateCodec.encode(renamed(state,"External"));
        var racing=new WorkspaceLayoutStore(root,SCOPE,new CommitIO(){@Override void replace(Path temporary,Path target)throws IOException{super.replace(temporary,target);Files.write(target,external);}});
        reason(Reason.CHANGED,()->racing.save(renamed(state,"Two"),prior));assertArrayEquals(external,Files.readAllBytes(file()));assertEquals(renamed(state,"External"),ordinary.load().state().orElseThrow());noTemporaryFiles();
    }
}
