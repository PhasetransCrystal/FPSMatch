package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.input.EditSessionStore;
import org.junit.jupiter.api.Test;

class StudioRuntimeTest {
    private static StudioFrame.Snapshot field(String id,String raw,int width,Consumer<String> change,Runnable blur) {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        ui.input(id,"Label",raw,new Bounds(0,0,width,20),0,true,false,change);
        if(blur!=null)frame.blur(id,id+"/",blur);
        return frame.snapshot();
    }

    @Test void rejectedCandidateKeepsTheEntireOldFrameEditorHistoryAndCallbacks() {
        var runtime=new StudioRuntime();var value=new AtomicReference<>("seed");var calls=new AtomicInteger();var invalidCalls=new AtomicInteger();
        runtime.publish(field("field",value.get(),100,value::set,calls::incrementAndGet));runtime.input().focus("field");
        assertTrue(runtime.input().insert("!"));var editor=runtime.input().visibleText().get("field");
        editor.select(1,false);editor.select(3,true);var old=runtime.captureEdits();
        var candidate=field("field","replacement",20,ignored->invalidCalls.incrementAndGet(),invalidCalls::incrementAndGet);
        var invalid=new StudioFrame.Snapshot(candidate.nodes(),candidate.visuals(),Map.of("absent",invalidCalls::incrementAndGet),candidate.changes(),candidate.blur(),candidate.groups(),candidate.details(),candidate.scrolls());
        assertThrows(IllegalArgumentException.class,()->runtime.publish(invalid));
        assertSame(old,runtime.frame());assertSame(old.geometry(),runtime.tree().frame());assertEquals("field",runtime.frame().focusedId());
        assertSame(editor,runtime.input().visibleText().get("field"));assertEquals(old.edits().get("field"),editor.snapshot());
        assertEquals(0,calls.get());assertEquals(0,invalidCalls.get());
        assertTrue(editor.undo());runtime.input().changed("field");assertEquals("seed",value.get());
        assertTrue(editor.redo());runtime.input().changed("field");assertEquals("seed!",value.get());
        runtime.input().focus(null);assertEquals(1,calls.get());assertEquals(0,invalidCalls.get());
    }

    @Test void renderingSnapshotsFreezeTextGeometryAndFocusAndReuseUnchangedCaptures() {
        var runtime=new StudioRuntime();runtime.publish(field("field","seed",100,ignored->{},null));runtime.input().focus("field");
        var before=runtime.frame();var edit=runtime.input().visibleText().get("field");edit.home(false);
        assertEquals(4,before.edits().get("field").caret());assertEquals(4,runtime.frame().edits().get("field").caret());
        var captured=runtime.captureEdits();assertEquals(0,captured.edits().get("field").caret());assertSame(before.geometry(),captured.geometry());
        for(int index=0;index<100;index++)assertSame(captured,runtime.captureEdits());
        runtime.publish(field("field","seed",30,ignored->{},null));
        assertEquals(100,before.geometry().node("field").orElseThrow().bounds().width());
        assertEquals(30,runtime.frame().geometry().node("field").orElseThrow().bounds().width());
        assertSame(runtime.tree().frame(),runtime.frame().geometry());
        runtime.tree().focus(null);assertNull(runtime.captureEdits().focusedId());assertEquals("field",before.focusedId());
    }

    @Test void temporaryUnmountAndReflowDoNotReplaceAnUnacknowledgedDraft() {
        var runtime=new StudioRuntime();runtime.publish(field("field","seed",100,ignored->{},null));runtime.input().focus("field");
        runtime.input().insert("!");var editor=runtime.input().visibleText().get("field");editor.select(2,false);
        runtime.publish(new StudioFrame().snapshot());assertTrue(runtime.input().visibleText().isEmpty());assertEquals(1,runtime.sessions().size());
        runtime.publish(field("field","seed",30,ignored->{},null));assertSame(editor,runtime.input().visibleText().get("field"));
        assertEquals("seed!",runtime.frame().edits().get("field").text());assertEquals(2,runtime.frame().edits().get("field").caret());
        assertTrue(editor.undo());assertEquals("seed",editor.text());
    }

    @Test void documentScopeSeparatesTheSameFieldIdWithoutUsingMountIdentity() {
        var runtime=new StudioRuntime();runtime.publish("one",field("name","first",100,ignored->{},null));runtime.input().focus("name");runtime.input().insert("!");
        var first=runtime.input().visibleText().get("name");
        runtime.publish("two",field("name","second",100,ignored->{},null));var second=runtime.input().visibleText().get("name");
        assertNotSame(first,second);assertNull(runtime.frame().focusedId());assertEquals("second",second.text());
        runtime.publish("one",field("name","first",100,ignored->{},null));assertSame(first,runtime.input().visibleText().get("name"));assertEquals("first!",first.text());
        assertEquals(2,runtime.sessions().size());
    }

    @Test void oversizedExternalOriginalIsReadOnlyAndCannotCallOrdinaryChangeOrBlur() {
        var runtime=new StudioRuntime();var calls=new AtomicInteger();String original="😀".repeat(2049);
        runtime.publish(field("field",original,100,ignored->calls.incrementAndGet(),calls::incrementAndGet));
        var published=runtime.frame();var diagnostic=published.diagnostics().get("field");
        assertEquals(EditSessionStore.Problem.EXTERNAL_TOO_LONG,diagnostic.code());assertEquals(original,diagnostic.externalValue());
        assertTrue(published.geometry().node("field").orElseThrow().readOnly());assertEquals(original,published.geometry().node("field").orElseThrow().value());
        assertFalse(published.edits().containsKey("field"));assertTrue(runtime.input().visibleText().isEmpty());
        runtime.input().focus("field");assertFalse(runtime.input().insert("short"));runtime.input().focus(null);
        assertEquals(0,calls.get());assertEquals(original,runtime.sessions().externalValue("","field").orElseThrow());
    }

    @Test void anotherScopesSameNamedDiagnosticDoesNotSuppressTheOldFieldsCommit() {
        var runtime=new StudioRuntime();var oldCommits=new AtomicInteger();var newCommits=new AtomicInteger();
        runtime.publish("old-document",field("name","seed",100,ignored->{},oldCommits::incrementAndGet));
        runtime.input().focus("name");runtime.input().insert("!");
        runtime.publish("new-document",field("name","x".repeat(4097),100,ignored->{},newCommits::incrementAndGet));
        assertEquals(1,oldCommits.get());assertEquals(0,newCommits.get());assertNull(runtime.frame().focusedId());
        assertEquals("seed!",runtime.sessions().draft("old-document","name").orElseThrow().text());
        runtime.input().focus("name");runtime.input().focus(null);assertEquals(0,newCommits.get());
    }

    @Test void rejectedPastePreservesTheSelectionAndNeverNotifiesTheDomain() {
        var runtime=new StudioRuntime();var calls=new AtomicInteger();
        runtime.publish(field("field","seed",100,ignored->calls.incrementAndGet(),null));runtime.input().focus("field");
        var edit=runtime.input().visibleText().get("field");edit.selectAll();var before=runtime.captureEdits();
        assertTrue(runtime.input().insert("x".repeat(4097)),"Focused editor consumes rejected input without passing it to world controls");
        assertSame(before,runtime.frame());assertEquals(0,calls.get());assertEquals("seed",edit.selected());assertFalse(edit.undo());
    }

    @Test void blurObservesTheCompleteNewFrameAndCompanionCommitRunsFirst() {
        var runtime=new StudioRuntime();var companion=new AtomicBoolean();var calls=new AtomicInteger();
        runtime.publish(field("old","old",100,ignored->{},()->{
            calls.incrementAndGet();assertTrue(companion.get());assertTrue(runtime.frame().geometry().node("old").isEmpty());
            assertEquals(runtime.tree().focusedId(),runtime.frame().focusedId());assertNull(runtime.frame().focusedId());
            assertEquals("new",runtime.frame().edits().get("new").text());runtime.input().focus("new");runtime.input().insert("!");
        }));runtime.input().focus("old");
        var published=runtime.publish(field("new","new",100,ignored->{},null),()->companion.set(true));
        assertEquals(1,calls.get());assertEquals("new",published.focusedId());assertEquals("new!",published.edits().get("new").text());
    }

    @Test void focusMovementPublishesItsSnapshotBeforeInvokingBlur() {
        var runtime=new StudioRuntime();var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var calls=new AtomicInteger();
        ui.input("first","First","a",new Bounds(0,0,100,20),0,true,false,ignored->{});
        ui.input("second","Second","b",new Bounds(0,30,100,20),0,true,false,ignored->{});
        frame.blur("first","first/",()->{calls.incrementAndGet();assertEquals("second",runtime.tree().focusedId());assertEquals("second",runtime.frame().focusedId());});
        runtime.publish(frame.snapshot());runtime.input().focus("first");runtime.input().cycleFocus(false);assertEquals(1,calls.get());
    }

    @Test void reentrantBlurPublicationWinsOverTheOuterFramesFinalCapture() {
        var runtime=new StudioRuntime();var committed=new AtomicBoolean();
        runtime.publish(field("old","old",100,ignored->{},()->{
            assertTrue(committed.get());runtime.publish(field("latest","latest",40,ignored->{},null));runtime.input().focus("latest");
        }));runtime.input().focus("old");
        var result=runtime.publish(field("intermediate","middle",80,ignored->{},null),()->committed.set(true));
        assertSame(result,runtime.frame());assertSame(result.geometry(),runtime.tree().frame());assertEquals("latest",result.focusedId());
        assertTrue(result.geometry().node("intermediate").isEmpty());assertEquals("latest",result.edits().get("latest").text());
    }

    @Test void pointerFocusSurvivesSameScopeBlurRebuildWithoutCrossingDocumentScopes() {
        var runtime=new StudioRuntime();var first=new StudioFrame();var ui=new StudioComponents(first,StudioMetrics.compact(),String::length);var calls=new AtomicInteger();
        ui.input("source","Source","a",new Bounds(0,0,100,20),0,true,false,ignored->{});
        ui.input("target","Target","b",new Bounds(0,30,100,20),0,true,false,ignored->{});
        first.blur("source","source/",()->{calls.incrementAndGet();runtime.publish(field("target","b",80,ignored->{},null));});
        runtime.publish(first.snapshot());runtime.input().focus("source");runtime.input().focus("target");
        assertEquals(1,calls.get());assertEquals("target",runtime.frame().focusedId());assertEquals(80,runtime.frame().geometry().node("target").orElseThrow().bounds().width());
        first.blur("source","source/",()->{calls.incrementAndGet();runtime.publish("another-document",field("target","other",80,ignored->{},null));});
        runtime.publish(first.snapshot());runtime.input().focus("source");runtime.input().focus("target");
        assertEquals(2,calls.get());assertNull(runtime.frame().focusedId());assertEquals("other",runtime.frame().edits().get("target").text());
    }
}
