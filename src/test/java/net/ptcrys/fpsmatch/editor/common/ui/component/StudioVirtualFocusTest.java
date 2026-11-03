package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioVirtualFocusTest {
    private static final class Harness {
        final StudioRuntime runtime=new StudioRuntime();
        final StudioFrame frame=new StudioFrame();
        final StudioComponents ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        final List<StudioFocusNavigation.RevealRequest> requests=new ArrayList<>();
        final AtomicInteger blurs=new AtomicInteger(),rowBlurs=new AtomicInteger(),actions=new AtomicInteger();
        List<StudioRowSource.FocusSlot> slots=List.of(new StudioRowSource.FocusSlot("open",Role.BUTTON));
        StudioRowSource<String> source;int first=2,viewportHeight=40,rowYInset;boolean modal;String scope="document",beforeRaw="seed";
        Runnable beforeCommit=blurs::incrementAndGet;
        Consumer<StudioFocusNavigation.RevealRequest> reveal=requests::add;
        Harness(){source(1,List.of("a","b","c","d"));}
        void source(long revision,List<String> keys){source=StudioRowSource.of(revision,keys,key->key,key->slots);}
        StudioFrame.Snapshot compose(){
            frame.clear();ui.input("before","Before",beforeRaw,new Bounds(0,0,100,20),0,true,false,ignored->{});frame.blur("before","before/",beforeCommit);
            var viewport=new Bounds(0,30,140,viewportHeight);
            frame.virtualRange(new StudioFocusNavigation.VirtualRange("rows",0,viewport,source,reveal));
            try(var ignored=frame.clipped(viewport)){
                for(int row=first;row<Math.min(source.size(),first+2);row++){
                    String key=source.keyAt(row);int column=0;
                    for(var slot:source.focusSlots(row)){
                        String id="row/"+key+"/"+slot.key();var bounds=new Bounds(column++*65,30+rowYInset+(row-first)*20,60,20);
                        if(slot.role()==Role.TEXT_FIELD){ui.input(id,key,"raw",bounds,0,true,false,value->{});frame.blur(id,"row/"+key+"/",rowBlurs::incrementAndGet);}
                        else ui.button(StudioComponents.Action.plain(id,key,actions::incrementAndGet),bounds,0,StudioComponent.Kind.BUTTON);
                        frame.virtualTarget(id,"rows",key,slot.key());
                    }
                }
            }
            ui.button(StudioComponents.Action.plain("after","After",actions::incrementAndGet),new Bounds(0,80,100,20),0,StudioComponent.Kind.BUTTON);
            if(modal)ui.surface("modal",new Bounds(0,0,160,120),1,StudioComponent.Kind.DIALOG);
            return frame.snapshot();
        }
        void publish(){runtime.publish(scope,compose());}
        void tab(){runtime.input().cycleFocus(false);}
        String actual(){return runtime.frame().focusedId();}
        StudioFocusNavigation.FocusRef logical(){return runtime.input().currentLogicalFocus();}
    }

    @Test void offscreenTargetsHaveNoFakeNodesAndOnlyPublishedVisibilityCommitsBlur(){
        var h=new Harness();h.publish();h.runtime.input().focus("before");h.runtime.input().insert("!");h.tab();
        var first=h.runtime.input().requestedFocus();assertEquals("a",first.rowKey());assertNull(first.target().nodeId());
        assertEquals("before",h.actual());assertEquals(0,h.blurs.get());assertFalse(h.runtime.input().actionReady("before"));
        assertTrue(h.runtime.frame().geometry().node("row/a/open").isEmpty());assertFalse(h.runtime.frame().snapshot().actions().containsKey("row/a/open"));
        h.tab();var second=h.runtime.input().requestedFocus();assertEquals("b",second.rowKey());assertTrue(second.requestSerial()>first.requestSerial());assertFalse(h.runtime.input().isCurrent(first));
        h.first=1;h.publish();assertEquals("row/b/open",h.actual());assertEquals(1,h.blurs.get());assertNull(h.runtime.input().requestedFocus());
        assertEquals("seed!",h.runtime.input().visibleText().get("before").text());h.publish();assertEquals(1,h.blurs.get());assertEquals(0,h.actions.get());
    }

    @Test void mixedSequenceWrapsAndRestoreFocusMovesItsLogicalCursor(){
        var h=new Harness();h.publish();h.runtime.input().restoreFocus("row/d/open");assertEquals("d",h.logical().rowKey());
        h.tab();assertEquals("after",h.actual());assertEquals("after",h.logical().nodeId());
        h.tab();assertEquals("before",h.actual());h.runtime.input().cycleFocus(true);assertEquals("after",h.actual());
        h.runtime.input().cycleFocus(true);assertEquals("row/d/open",h.actual());
    }

    @Test void sortingRelocatesTheRequestedKeyAndFilteringCancelsItsToken(){
        var h=new Harness();h.publish();h.runtime.input().focus("before");h.tab();var old=h.runtime.input().requestedFocus();
        h.source(2,List.of("b","a","c","d"));h.publish();var moved=h.runtime.input().requestedFocus();
        assertEquals("a",moved.rowKey());assertEquals(1,moved.rowIndex());assertTrue(moved.sequenceRevision()>old.sequenceRevision());assertFalse(h.runtime.input().isCurrent(old));assertEquals(0,h.blurs.get());
        h.source(3,List.of("b","c","d"));h.publish();assertNull(h.runtime.input().requestedFocus());assertFalse(h.runtime.input().isCurrent(moved));assertEquals(0,h.blurs.get());assertEquals("before",h.actual());
    }

    @Test void failedCandidateRetainsRequestRawHistoryAndAllLiveMetadata(){
        var h=new Harness();h.publish();h.runtime.input().focus("before");h.runtime.input().insert("!");h.tab();
        var request=h.runtime.input().requestedFocus();var published=h.runtime.frame();int revealCount=h.requests.size();
        h.compose();h.frame.virtualTarget("after","rows","missing","open");
        assertThrows(IllegalArgumentException.class,()->h.runtime.publish(h.scope,h.frame.snapshot()));
        assertSame(published,h.runtime.frame());assertSame(request,h.runtime.input().requestedFocus());assertTrue(h.runtime.input().isCurrent(request));assertEquals(revealCount,h.requests.size());assertEquals(0,h.blurs.get());
        var editor=h.runtime.input().visibleText().get("before");assertEquals("seed!",editor.text());assertTrue(editor.undo());assertEquals("seed",editor.text());
    }

    @Test void modalAndDocumentSwitchInvalidatePendingFocusWithoutLateBlur(){
        for(boolean modal:List.of(false,true)){
            var h=new Harness();h.publish();h.runtime.input().focus("before");h.tab();var old=h.runtime.input().requestedFocus();
            if(modal)h.modal=true;else h.scope="another-document";h.publish();
            assertNull(h.runtime.input().requestedFocus());assertFalse(h.runtime.input().isCurrent(old));assertNull(h.actual());assertEquals(0,h.blurs.get());
            h.first=0;h.publish();assertNull(h.actual());assertEquals(0,h.blurs.get());
        }
    }

    @Test void virtualizedEditKeepsRawAndGroupBlurUntilAVisibleDestination(){
        var h=new Harness();h.slots=List.of(new StudioRowSource.FocusSlot("edit",Role.TEXT_FIELD),new StudioRowSource.FocusSlot("apply",Role.BUTTON));h.source(2,List.of("a","b","c","d"));h.first=0;h.publish();
        h.runtime.input().focus("row/a/edit");h.runtime.input().insert("!");var editor=h.runtime.input().visibleText().get("row/a/edit");
        h.first=2;h.publish();assertNull(h.actual());assertEquals("a",h.logical().rowKey());assertEquals(0,h.rowBlurs.get());
        h.tab();assertEquals("apply",h.logical().slotKey());assertEquals(0,h.rowBlurs.get());
        h.first=0;h.publish();assertEquals("row/a/apply",h.actual());assertEquals(0,h.rowBlurs.get());assertSame(editor,h.runtime.input().visibleText().get("row/a/edit"));assertEquals("raw!",editor.text());
        h.tab();assertEquals("row/b/edit",h.actual());assertEquals(1,h.rowBlurs.get());h.publish();assertEquals(1,h.rowBlurs.get());
    }

    @Test void synchronousRevealCanPublishAndResolveExactlyOnce(){
        var h=new Harness();h.reveal=request->{h.requests.add(request);assertTrue(h.runtime.input().isCurrent(request));h.first=request.rowIndex();h.publish();};
        h.publish();h.runtime.input().focus("before");h.tab();assertEquals("row/a/open",h.actual());assertEquals(1,h.blurs.get());assertEquals(1,h.requests.size());assertNull(h.runtime.input().requestedFocus());
    }

    @Test void passiveReflowWaitsForFullyVisibleGeometryWithoutCommittingTheEdit(){
        var h=new Harness();h.slots=List.of(new StudioRowSource.FocusSlot("edit",Role.TEXT_FIELD));h.source(2,List.of("a","b","c","d"));h.publish();
        h.runtime.input().focus("row/d/edit");h.runtime.input().insert("!");var editor=h.runtime.input().visibleText().get("row/d/edit");
        h.runtime.input().reflow();h.viewportHeight=25;h.publish();var request=h.runtime.input().requestedFocus();
        assertEquals("d",request.rowKey());assertNull(h.actual());assertEquals(0,h.rowBlurs.get());assertFalse(h.runtime.input().actionReady("row/d/edit"));
        h.first=3;h.publish();assertEquals("row/d/edit",h.actual());assertNull(h.runtime.input().requestedFocus());assertEquals(0,h.rowBlurs.get());
        assertSame(editor,h.runtime.input().visibleText().get("row/d/edit"));assertEquals("raw!",editor.text());
        h.tab();assertEquals("after",h.actual());assertEquals(1,h.rowBlurs.get());
    }

    @Test void oversizedVirtualRowsMustCoverTheViewportBeforeReceivingFocus(){
        var h=new Harness();h.first=0;h.viewportHeight=10;h.rowYInset=9;h.publish();h.runtime.input().focus("before");h.tab();
        assertEquals("a",h.runtime.input().requestedFocus().rowKey());assertEquals("before",h.actual());assertEquals(0,h.blurs.get());
        assertEquals(1,h.runtime.frame().geometry().node("row/a/open").orElseThrow().visibleBounds().height());
        h.rowYInset=0;h.publish();assertEquals("row/a/open",h.actual());assertNull(h.runtime.input().requestedFocus());assertEquals(1,h.blurs.get());
    }

    @Test void explicitRowRequestsUseTheSamePendingCursorAndPublicationBoundary(){
        var h=new Harness();h.publish();h.runtime.input().focus("before");
        var target=new StudioFocusNavigation.FocusRef("rows","b","open",null,Role.BUTTON);assertTrue(h.runtime.input().requestFocus(target));
        var request=h.runtime.input().requestedFocus();assertEquals(target,h.logical());assertEquals("before",h.actual());assertEquals(0,h.blurs.get());
        assertFalse(h.runtime.input().requestFocus(new StudioFocusNavigation.FocusRef("rows","missing","open",null,Role.BUTTON)));assertSame(request,h.runtime.input().requestedFocus());
        h.tab();assertEquals("row/c/open",h.actual());assertFalse(h.runtime.input().isCurrent(request));assertEquals(1,h.blurs.get());
    }

    @Test void sameScopeDiagnosticRetiresThePendingOriginsOrdinaryBlur(){
        var h=new Harness();h.publish();h.runtime.input().focus("before");h.runtime.input().insert("!");h.tab();
        h.beforeRaw="x".repeat(4097);h.first=0;h.publish();
        assertTrue(h.runtime.frame().diagnostics().containsKey("before"));assertEquals("row/a/open",h.actual());assertEquals(0,h.blurs.get());
    }

    @Test void editingTheActualFieldCancelsARequestedDestinationWithoutBlur(){
        var h=new Harness();h.publish();h.runtime.input().focus("before");h.tab();var request=h.runtime.input().requestedFocus();
        assertTrue(h.runtime.input().visibleText().get("before").insert("!"));h.runtime.input().changed("before");
        assertNull(h.runtime.input().requestedFocus());assertFalse(h.runtime.input().isCurrent(request));assertEquals("before",h.actual());assertEquals(0,h.blurs.get());
        h.first=0;h.publish();assertEquals("before",h.actual());assertEquals(0,h.blurs.get());
    }

    @Test void reentrantBlurKeepsTheInnerPublicationsChosenFocus(){
        var h=new Harness();h.beforeCommit=()->{h.blurs.incrementAndGet();assertEquals("row/a/open",h.actual());h.first=2;h.publish();h.runtime.input().restoreFocus("after");};
        h.publish();h.runtime.input().focus("before");h.tab();h.first=0;h.publish();
        assertEquals("after",h.actual());assertEquals("after",h.logical().nodeId());assertEquals(1,h.blurs.get());assertNull(h.runtime.input().requestedFocus());
    }
}
