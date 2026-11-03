package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioFocusNavigationTest {
    private static final class Harness {
        final StudioFrame frame=new StudioFrame();
        final StudioComponents ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        final ControlTree tree=new ControlTree();
        final StudioComponents.InputBridge input=new StudioComponents.InputBridge(tree);
        final AtomicInteger offset=new AtomicInteger();
        final Map<String,String> raw=new HashMap<>();
        final Map<String,Integer> commits=new HashMap<>();
        List<String> ids=List.of("a","b","c");
        String scope="document/one/body";
        Bounds viewport=new Bounds(10,30,200,40);
        IntConsumer scrollChange=offset::set;
        boolean outside,unrelatedVirtual;
        StudioFrame.Snapshot compose(){
            frame.clear();
            var rows=ids.stream().map(id->List.of(new StudioComponents.Field(id,id,raw.getOrDefault(id,""),"","",StudioComponents.FieldKind.TEXT,0,true,false,value->raw.put(id,value),()->{},()->commits.merge(id,1,Integer::sum)))).toList();
            int height=ui.formRowsHeight(rows,Math.max(0,viewport.width()-8));
            ui.scrollContent(scope,viewport,height,0,offset.get(),scrollChange,body->ui.formRowsContent(rows,body,0));
            if(outside)ui.button(StudioComponents.Action.plain("outside","Outside",()->{}),new Bounds(300,30,80,20),0,Kind.BUTTON);
            if(unrelatedVirtual)addUnrelatedVirtualRange(frame);
            return frame.snapshot();
        }
        void publish(){input.publish(compose());}
        String focused(){return tree.focused().map(ControlTree.Node::id).orElse(null);}
        int commits(String id){return commits.getOrDefault(id,0);}
        void cycle(boolean backwards){input.cycleFocus(backwards);publish();}
    }
    private static ControlTree.Node node(StudioFrame frame,String id){return frame.nodes().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();}
    private static void addUnrelatedVirtualRange(StudioFrame frame){
        frame.virtualRange(new StudioFocusNavigation.VirtualRange("unrelated",0,new Bounds(400,30,100,40),
                StudioRowSource.of(1,List.<String>of(),value->value,value->List.of()),request->fail("empty range cannot request reveal")));
    }
    private static void assertVisibleFocus(Harness h,String id){assertEquals(id,h.focused());var n=h.tree.focused().orElseThrow();assertEquals(n.bounds(),n.visibleBounds());}
    private static final class GroupHarness {
        final StudioFrame frame=new StudioFrame();final StudioComponents ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        final ControlTree tree=new ControlTree();final StudioComponents.InputBridge input=new StudioComponents.InputBridge(tree);
        final AtomicInteger offset=new AtomicInteger(),automatic=new AtomicInteger(),applied=new AtomicInteger(),discarded=new AtomicInteger();
        final AtomicReference<String> raw=new AtomicReference<>("-1e");boolean source=true,modal,unrelatedVirtual;String scope="document/one/body";Runnable sourceCommit=automatic::incrementAndGet;
        StudioFrame.Snapshot compose(){
            frame.clear();ui.scrollContent(scope,new Bounds(10,30,200,40),160,0,offset.get(),offset::set,body->{
                if(source){ui.input("value","Value",raw.get(),new Bounds(body.x(),body.y(),body.width(),20),0,true,true,raw::set);frame.blur("value","row/",sourceCommit);}
                ui.button(StudioComponents.Action.plain("row/apply","Apply",applied::incrementAndGet),new Bounds(body.x(),body.y()+50,body.width(),20),0,Kind.BUTTON);
                ui.button(StudioComponents.Action.plain("row/discard","Discard",()->{discarded.incrementAndGet();raw.set("original");}),new Bounds(body.x(),body.y()+80,body.width(),20),0,Kind.BUTTON);
                ui.button(StudioComponents.Action.plain("next","Next",()->{}),new Bounds(body.x(),body.y()+120,body.width(),20),0,Kind.BUTTON);
            });if(unrelatedVirtual)addUnrelatedVirtualRange(frame);if(modal)ui.surface("modal",new Bounds(0,0,400,300),1,Kind.DIALOG);return frame.snapshot();
        }
        void publish(){input.publish(compose());}
        void cycle(boolean backwards){input.cycleFocus(backwards);publish();}
        void focusSource(){publish();input.focus("value");}
    }

    @Test void tabbingThroughSameGroupActionsCommitsTheSourceOnlyWhenLeavingTheGroup(){
        var h=new GroupHarness();h.focusSource();h.cycle(false);assertEquals("row/apply",h.tree.focused().orElseThrow().id());assertEquals(0,h.automatic.get());
        h.cycle(false);assertEquals("row/discard",h.tree.focused().orElseThrow().id());assertEquals(0,h.automatic.get());
        h.cycle(false);assertEquals("next",h.tree.focused().orElseThrow().id());assertEquals(1,h.automatic.get());
        h.publish();assertEquals(1,h.automatic.get());assertEquals(0,h.applied.get());assertEquals(0,h.discarded.get());assertEquals("-1e",h.raw.get());
    }

    @Test void actualGroupActionRetiresTheSourceWithoutGuessingWhetherItAppliesOrDiscards(){
        for(String action:List.of("row/apply","row/discard")){
            var h=new GroupHarness();h.focusSource();h.cycle(false);if(action.equals("row/discard"))h.cycle(false);
            h.input.beforeAction(action);h.frame.actions().get(action).run();
            if(action.equals("row/apply"))h.cycle(false);h.cycle(false);h.publish();
            assertEquals(0,h.automatic.get());assertEquals(action.equals("row/apply")?1:0,h.applied.get());assertEquals(action.equals("row/discard")?1:0,h.discarded.get());
            assertEquals(action.equals("row/discard")?"original":"-1e",h.raw.get());
        }
    }

    @Test void returningToTheSourceRestoresItsNormalBlurOwnership(){
        var h=new GroupHarness();h.focusSource();h.cycle(false);h.cycle(true);assertEquals("value",h.tree.focused().orElseThrow().id());assertEquals(0,h.automatic.get());
        h.cycle(false);h.cycle(false);h.cycle(false);assertEquals(1,h.automatic.get());h.publish();assertEquals(1,h.automatic.get());
    }

    @Test void cancellingNavigationWhileOnAGroupActionDoesNotActivateOrLoseTheSource(){
        var h=new GroupHarness();h.focusSource();h.cycle(false);
        h.input.cancelNavigation();h.input.cancelNavigation();h.publish();
        assertEquals(0,h.automatic.get());assertEquals(0,h.applied.get());assertEquals(0,h.discarded.get());
        h.input.focus("next");assertEquals(1,h.automatic.get());h.publish();assertEquals(1,h.automatic.get());
    }

    @Test void deferredGroupSourceSettlesOnContextLossAndSurvivesRejectedPublication(){
        for(String change:List.of("modal","removed","document","inactive","rejected")){
            var h=new GroupHarness();h.focusSource();h.cycle(false);assertEquals(0,h.automatic.get());
            switch(change){
                case "modal"->h.modal=true;
                case "removed"->h.source=false;
                case "document"->h.scope="document/two/body";
                case "inactive"->{h.input.windowActive(false);h.input.windowActive(false);}
                default->{var rejected=new AtomicInteger();h.sourceCommit=rejected::incrementAndGet;h.compose();h.ui.input("value","Duplicate","",new Bounds(0,0,30,20),0,true,false,value->{});
                    assertThrows(IllegalArgumentException.class,()->h.input.publish(h.frame.snapshot()));assertEquals(0,h.automatic.get());assertEquals(0,rejected.get());h.input.focus("next");assertEquals(1,h.automatic.get());assertEquals(0,rejected.get());}
            }
            h.publish();h.publish();assertEquals(1,h.automatic.get());assertEquals(0,h.applied.get());assertEquals(0,h.discarded.get());
        }
    }

    @Test void unrelatedVirtualRangePreservesOrdinaryRemovalAndDeferredGroupContextLoss(){
        var removed=new Harness();removed.unrelatedVirtual=true;removed.publish();removed.input.focus("a");removed.input.insert("draft");
        removed.ids=List.of("b","c");removed.publish();removed.publish();assertEquals(1,removed.commits("a"));assertNull(removed.focused());
        for(String change:List.of("modal","removed","document","inactive")){
            var h=new GroupHarness();h.unrelatedVirtual=true;h.focusSource();h.cycle(false);assertEquals(0,h.automatic.get());
            switch(change){case "modal"->h.modal=true;case "removed"->h.source=false;case "document"->h.scope="document/two/body";default->h.input.windowActive(false);}
            h.publish();h.publish();assertEquals(1,h.automatic.get(),change);assertEquals(0,h.applied.get());assertEquals(0,h.discarded.get());
        }
    }

    @Test void unrelatedVirtualRangePreservesOrdinaryPendingContextValidation(){
        for(String change:List.of("modal","removed","document","resize","direct-focus")){
            var h=new Harness();h.unrelatedVirtual=true;h.outside=true;h.publish();h.input.focus("a");h.input.cycleFocus(false);
            switch(change){case "removed"->h.ids=List.of("a","c");case "document"->h.scope="document/two/body";case "resize"->h.viewport=new Bounds(10,30,180,40);case "direct-focus"->h.tree.focus("outside");default->{}}
            h.compose();if(change.equals("modal"))h.ui.surface("modal",new Bounds(0,0,400,300),1,Kind.DIALOG);
            h.input.publish(h.frame.snapshot());assertEquals(change.equals("direct-focus")?"outside":null,h.focused(),change);assertEquals(1,h.commits("a"),change);
            h.publish();assertEquals(1,h.commits("a"),change);
        }
    }

    @Test void unrelatedVirtualRangePreservesOrdinaryReflowAndAdoptsNewRevealCallbacks(){
        var h=new Harness();h.unrelatedVirtual=true;var oldCalls=new AtomicInteger();var newCalls=new AtomicInteger();h.scrollChange=value->oldCalls.incrementAndGet();
        h.publish();h.input.focus("a");h.input.cycleFocus(false);assertEquals(1,oldCalls.get());
        h.scrollChange=value->{newCalls.incrementAndGet();h.offset.set(value);};h.input.reflow();h.viewport=new Bounds(10,30,140,30);h.publish();
        assertEquals(1,newCalls.get());assertEquals(0,h.commits("a"));h.publish();assertVisibleFocus(h,"b");assertEquals(1,h.commits("a"));
        h.input.reflow();h.ids=List.of("b","c","a");h.publish();h.publish();assertVisibleFocus(h,"b");assertEquals(0,h.commits("b"));
    }

    @Test void forwardAndReverseTabsRevealEveryFieldBeforeGivingItActualFocus(){
        var h=new Harness();h.publish();h.input.cycleFocus(false);assertVisibleFocus(h,"a");
        assertFalse(node(h.frame,"c").focusable());assertTrue(h.tree.hit(20,125).isEmpty());
        h.input.cycleFocus(false);assertEquals(StudioFocusNavigation.revealOffset(h.frame.snapshot().focusTargets().get("b")),h.offset.get());assertEquals("a",h.focused());assertEquals(0,h.commits("a"));
        h.publish();assertVisibleFocus(h,"b");assertEquals(1,h.commits("a"));
        for(String id:List.of("c","a","b","c")){h.cycle(false);assertVisibleFocus(h,id);}
        for(String id:List.of("b","a","c","b","a")){h.cycle(true);assertVisibleFocus(h,id);}
        assertTrue(h.offset.get()>=0&&h.offset.get()<=h.frame.scrolls().get(0).maximum());
    }

    @Test void partiallyClippedTargetIsRevealedBeforeInitialFocusAndRapidTabsUsePendingOrder(){
        var h=new Harness();h.viewport=new Bounds(10,30,200,30);h.publish();
        assertTrue(node(h.frame,"a").focusable());assertNotEquals(node(h.frame,"a").bounds(),node(h.frame,"a").visibleBounds());
        h.input.cycleFocus(false);assertNull(h.focused());assertEquals(StudioFocusNavigation.revealOffset(h.frame.snapshot().focusTargets().get("a")),h.offset.get());h.publish();assertVisibleFocus(h,"a");
        h.input.cycleFocus(false);h.input.cycleFocus(false);assertEquals("a",h.focused());assertEquals(0,h.commits("a"));
        h.publish();assertVisibleFocus(h,"c");assertEquals(1,h.commits("a"));assertEquals(0,h.commits("b"));
    }

    @Test void rawCaretAndUndoHistorySurviveRevealAndTheSourceBlursExactlyOnce(){
        var h=new Harness();h.publish();h.input.focus("a");assertTrue(h.input.insert("-1e"));h.publish();
        var text=h.input.visibleText().get("a");text.home(false);assertEquals(0,text.caret());text.end(false);assertEquals(3,text.caret());
        h.input.cycleFocus(false);assertEquals(0,h.commits("a"));h.publish();h.publish();
        assertEquals(1,h.commits("a"));assertEquals("-1e",h.raw.get("a"));assertSame(text,h.input.visibleText().get("a"));
        h.cycle(true);assertVisibleFocus(h,"a");assertSame(text,h.input.visibleText().get("a"));assertEquals(3,text.caret());
        assertTrue(text.undo());h.input.changed("a");assertEquals("",h.raw.get("a"));assertTrue(text.redo());h.input.changed("a");assertEquals("-1e",h.raw.get("a"));
    }

    @Test void revealingSameGroupDiscardOrChoiceNeverCommitsTheInvalidRawValue(){
        for(String target:List.of("row/discard","row/choice")){
            var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
            var offset=new AtomicInteger();var commits=new AtomicInteger();var choices=new AtomicInteger();var raw=new AtomicReference<>("-1e");
            Runnable publish=()->{frame.clear();ui.scrollContent("document/body",new Bounds(10,30,200,40),120,0,offset.get(),offset::set,body->{
                ui.input("value","Value",raw.get(),new Bounds(body.x(),body.y(),body.width(),20),0,true,true,raw::set);frame.blur("value","row/",commits::incrementAndGet);
                ui.button(StudioComponents.Action.plain(target,"Row action",choices::incrementAndGet),new Bounds(body.x(),body.y()+90,body.width(),20),0,Kind.BUTTON);
            });input.publish(frame.snapshot());};
            publish.run();input.focus("value");input.cycleFocus(false);assertEquals("value",tree.focused().orElseThrow().id());publish.run();
            assertEquals(target,tree.focused().orElseThrow().id());assertEquals(0,commits.get());assertEquals("-1e",raw.get());
            frame.actions().get(target).run();assertEquals(1,choices.get());assertEquals(0,commits.get());
        }
    }

    @Test void modalTargetRemovalAndDocumentIdentityChangesCancelLateFocus(){
        for(String change:List.of("modal","removed","document")){
            var h=new Harness();h.publish();h.input.focus("a");h.input.cycleFocus(false);
            if(change.equals("removed"))h.ids=List.of("a","c");
            if(change.equals("document"))h.scope="document/two/body";
            h.compose();if(change.equals("modal"))h.ui.surface("modal",new Bounds(0,0,400,300),1,Kind.DIALOG);
            h.input.publish(h.frame.snapshot());assertNull(h.focused());assertEquals(1,h.commits("a"));
            h.publish();assertNull(h.focused());assertEquals(1,h.commits("a"));
        }
    }

    @Test void pointerFocusReflowRevealsTheSameRawEditWithoutCommitting(){
        var h=new Harness();h.publish();h.input.focus("a");assertTrue(h.input.insert("-1e"));h.publish();
        var edit=h.input.visibleText().get("a");edit.home(false);
        h.input.reflow();h.ids=List.of("b","c","a");h.viewport=new Bounds(10,30,150,30);h.publish();
        assertNull(h.focused());assertTrue(h.offset.get()>0);assertEquals(0,h.commits("a"));
        h.publish();assertVisibleFocus(h,"a");assertEquals("-1e",h.raw.get("a"));assertSame(edit,h.input.visibleText().get("a"));assertEquals(0,edit.caret());assertEquals(0,h.commits("a"));
        h.input.focus(null);assertEquals(1,h.commits("a"));h.publish();assertEquals(1,h.commits("a"));
    }

    @Test void pendingTabReflowAdoptsNewGeometryButNeverANewDocument(){
        for(boolean newDocument:List.of(false,true)){
            var h=new Harness();h.publish();h.input.focus("a");h.input.cycleFocus(false);h.input.reflow();
            h.viewport=new Bounds(10,30,140,30);if(newDocument)h.scope="document/two/body";
            h.publish();h.publish();
            if(newDocument)assertNull(h.focused());else assertVisibleFocus(h,"b");
            assertEquals(1,h.commits("a"));
        }
    }

    @Test void explicitFocusCancellationAndResizeCannotStealFocusOnTheNextFrame(){
        for(String change:List.of("focus","cancel","resize","direct-focus")){
            var h=new Harness();h.outside=true;h.publish();h.input.focus("a");h.input.cycleFocus(false);
            switch(change){
                case "focus"->h.input.focus("outside");
                case "direct-focus"->h.tree.focus("outside");
                case "cancel"->h.input.cancelNavigation();
                default->h.viewport=new Bounds(10,30,180,40);
            }
            h.publish();assertEquals(change.contains("focus")?"outside":null,h.focused());assertEquals(1,h.commits("a"));
            h.publish();assertEquals(change.contains("focus")?"outside":null,h.focused());assertEquals(1,h.commits("a"));
        }
    }

    @Test void windowInactivityCancelsPendingAndDoesNotDoubleBlurWhenTheSourceScrollsAway(){
        var h=new Harness();h.publish();h.input.focus("a");h.input.cycleFocus(false);
        assertTrue(h.input.windowActive(false));assertFalse(h.input.windowActive(false));assertEquals(1,h.commits("a"));
        h.publish();assertNull(h.focused());assertEquals(1,h.commits("a"));h.input.windowActive(true);h.publish();assertNull(h.focused());assertEquals(1,h.commits("a"));
    }

    @Test void rejectedFrameKeepsThePreviousMetadataPendingOrderAndCallbacks(){
        var h=new Harness();var oldCalls=new AtomicInteger();var rejectedCalls=new AtomicInteger();h.scrollChange=value->{oldCalls.incrementAndGet();h.offset.set(value);};
        h.publish();var previous=h.tree.snapshot();h.input.focus("a");h.input.cycleFocus(false);
        h.scrollChange=value->rejectedCalls.incrementAndGet();h.compose();h.ui.input("a","Duplicate","",new Bounds(0,0,30,20),0,true,false,value->{});
        assertThrows(IllegalArgumentException.class,()->h.input.publish(h.frame.snapshot()));
        assertEquals(previous,h.tree.snapshot());assertEquals("a",h.focused());assertEquals(0,h.commits("a"));assertEquals(0,rejectedCalls.get());
        h.input.cycleFocus(false);assertEquals(2,oldCalls.get());assertEquals(0,rejectedCalls.get());
        h.scrollChange=h.offset::set;h.publish();assertVisibleFocus(h,"c");assertEquals(1,h.commits("a"));
    }

    @Test void pendingRevealAdoptsANewSuccessfullyPublishedScrollCallback(){
        var h=new Harness();var oldCalls=new AtomicInteger();var newCalls=new AtomicInteger();h.scrollChange=value->oldCalls.incrementAndGet();
        h.publish();h.input.focus("a");h.input.cycleFocus(false);assertEquals(1,oldCalls.get());assertEquals(0,h.offset.get());
        h.scrollChange=value->{newCalls.incrementAndGet();h.offset.set(value);};h.publish();
        assertEquals(1,oldCalls.get());assertEquals(1,newCalls.get());assertEquals(StudioFocusNavigation.revealOffset(h.frame.snapshot().focusTargets().get("b")),h.offset.get());assertEquals("a",h.focused());assertEquals(0,h.commits("a"));
        h.publish();assertVisibleFocus(h,"b");assertEquals(1,h.commits("a"));h.cycle(false);assertVisibleFocus(h,"c");assertEquals(2,newCalls.get());
    }

    @Test void cancellingAfterPartialScrollingSettlesTheRememberedSourceOnce(){
        var h=new Harness();h.scrollChange=value->{};h.publish();h.input.focus("a");h.input.cycleFocus(false);h.input.cycleFocus(false);
        h.offset.set(40);h.publish();assertNull(h.focused());assertEquals(0,h.commits("a"));
        h.input.cancelNavigation();h.input.cancelNavigation();assertEquals(1,h.commits("a"));
        h.offset.set(75);h.publish();assertNull(h.focused());assertEquals(1,h.commits("a"));
    }

    @Test void topLayerModalKeepsKeyboardOrderOutOfTheUnderlyingBody(){
        var h=new Harness();h.compose();h.ui.surface("modal",new Bounds(0,0,400,300),1,Kind.DIALOG);
        h.ui.button(StudioComponents.Action.plain("modal/first","First",()->{}),new Bounds(30,40,100,20),1,Kind.BUTTON);
        h.ui.button(StudioComponents.Action.plain("modal/last","Last",()->{}),new Bounds(30,80,100,20),1,Kind.BUTTON);h.input.publish(h.frame.snapshot());
        for(String id:List.of("modal/first","modal/last","modal/first")){h.input.cycleFocus(false);assertEquals(id,h.focused());}
        h.input.cycleFocus(true);assertEquals("modal/last",h.focused());assertEquals(0,h.offset.get());
    }

    @Test void nestedClipDefinesTheRevealViewportAndNeverExpandsHitGeometry(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
        var offset=new AtomicInteger();var outer=new Bounds(20,45,90,30);var inner=new Bounds(25,40,60,40);
        Runnable publish=()->{frame.clear();try(var ignored=frame.clipped(outer)){ui.scrollContent("body",new Bounds(10,30,200,100),300,0,offset.get(),offset::set,body->{
            try(var nested=frame.clipped(inner)){var positions=List.of(0,60,120,280);for(int i=0;i<positions.size();i++)ui.button(StudioComponents.Action.plain("card/"+i,"Card",()->{}),new Bounds(body.x(),body.y()+positions.get(i),body.width(),20),0,Kind.BUTTON);}
        });}input.publish(frame.snapshot());};
        publish.run();assertEquals(outer,frame.snapshot().focusTargets().get("card/1").region().viewport());
        assertEquals(outer.intersect(inner),frame.snapshot().focusTargets().get("card/1").viewport());
        input.cycleFocus(false);assertEquals("card/0",tree.focused().orElseThrow().id());assertEquals(45,node(frame,"card/0").bounds().y());input.cycleFocus(false);assertEquals(50,offset.get());publish.run();
        assertEquals("card/1",tree.focused().orElseThrow().id());assertEquals(new Bounds(25,55,60,20),tree.focused().orElseThrow().visibleBounds());
        assertTrue(tree.hit(24,56).isEmpty());assertEquals("card/1",tree.hit(26,56).orElseThrow().id());assertTrue(tree.hit(26,76).isEmpty());
        input.cycleFocus(false);publish.run();input.cycleFocus(false);publish.run();assertEquals("card/3",tree.focused().orElseThrow().id());assertEquals(270,offset.get());
        assertEquals(new Bounds(25,55,60,20),tree.focused().orElseThrow().visibleBounds());assertEquals(30,frame.scrolls().get(0).track().height());assertEquals(270,frame.scrolls().get(0).maximum());
        for(int i=0;i<3;i++){input.cycleFocus(true);publish.run();}assertEquals("card/0",tree.focused().orElseThrow().id());assertEquals(0,offset.get());
        var scrolling=new StudioScrollState();input.cancelNavigation();for(int i=0;i<14;i++){assertTrue(scrolling.wheel(frame.scrolls(),26,56,-1,0,tree));publish.run();}
        assertEquals(270,offset.get());assertEquals("card/3",tree.hit(26,56).orElseThrow().id());
    }

    @Test void keyboardAnchorRevealsTheSameTargetAfterSourceBlurShrinksTheLayout(){
        for(boolean cancel:List.of(false,true)){
            var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
            var offset=new AtomicInteger();var compact=new java.util.concurrent.atomic.AtomicBoolean();var firstCommits=new AtomicInteger();var secondCommits=new AtomicInteger();var requests=new AtomicInteger();
            Runnable publish=()->{frame.clear();ui.scrollContent("document/body",new Bounds(10,30,200,40),compact.get()?100:160,0,offset.get(),value->{requests.incrementAndGet();offset.set(value);},body->{
                ui.input("first","First","1",new Bounds(body.x(),body.y(),body.width(),20),0,true,false,value->{});frame.blur("first","first-row/",()->{firstCommits.incrementAndGet();compact.set(true);});
                ui.input("second","Second","2",new Bounds(body.x(),body.y()+(compact.get()?20:80),body.width(),20),0,true,false,value->{});frame.blur("second","second-row/",secondCommits::incrementAndGet);
            });input.publish(frame.snapshot());};
            publish.run();input.focus("first");input.cycleFocus(false);publish.run();assertEquals("second",tree.focused().orElseThrow().id());assertEquals(1,firstCommits.get());assertEquals(1,requests.get());
            if(cancel)input.cancelNavigation();publish.run();
            if(cancel){assertTrue(tree.focused().isEmpty());assertEquals(1,requests.get());assertEquals(1,secondCommits.get());}
            else {assertTrue(tree.focused().isEmpty());assertEquals(2,requests.get());publish.run();assertEquals("second",tree.focused().orElseThrow().id());assertEquals(node(frame,"second").bounds(),node(frame,"second").visibleBounds());assertEquals(0,secondCommits.get());}
            publish.run();assertEquals(1,firstCommits.get());
        }
    }

    @Test void actualDetailCardCommandsUsageAndTabsRemainInOffscreenKeyboardOrder(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);var offset=new AtomicInteger();var actions=new AtomicInteger();
        var mode=StudioComponents.Action.plain("module/mode","Mode",actions::incrementAndGet);
        var command=new StudioComponents.Action("module/command","Command",Icon.MORE,Variant.DEFAULT,true,false,"",actions::incrementAndGet);
        var usage=StudioComponents.Action.plain("module/usage","Usage",actions::incrementAndGet);
        var header=new StudioComponents.CardHeader("Module","",true,false,List.of(List.of(mode,command),List.of(usage)),actions::incrementAndGet);
        Runnable publish=()->{frame.clear();ui.scrollContent("document/body",new Bounds(0,0,240,40),220,0,offset.get(),offset::set,body->{
            ui.button(StudioComponents.Action.plain("start","Start",actions::incrementAndGet),new Bounds(body.x(),body.y(),body.width(),20),0,Kind.BUTTON);
            ui.detailCard("module",header,new Bounds(body.x(),body.y()+80,body.width(),ui.cardHeaderHeight(header,body.width())),0);
            ui.tabs("pages",new Bounds(body.x(),body.y()+180,body.width(),22),0,List.of(StudioComponents.Action.plain("page/a","A",actions::incrementAndGet),StudioComponents.Action.plain("page/b","B",actions::incrementAndGet)),false,0);
        });input.publish(frame.snapshot());};
        publish.run();assertTrue(frame.snapshot().focusTargets().keySet().containsAll(List.of("module/mode","module/command","module/usage","page/a","page/b")));
        for(String id:List.of("module/mode","module/command","module/usage","page/a","page/b"))assertFalse(node(frame,id).focusable());
        input.focus("start");for(String id:List.of("module","module/mode","module/command","module/usage","page/a","page/b")){input.cycleFocus(false);publish.run();assertEquals(id,tree.focused().orElseThrow().id());assertEquals(node(frame,id).bounds(),node(frame,id).visibleBounds());}
        assertEquals(0,actions.get());assertEquals(1,frame.scrolls().size());
    }

    @Test void movingClipRetainsVerticalTargetsButRejectsHorizontalOverflow(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);var offset=new AtomicInteger();
        Runnable publish=()->{frame.clear();ui.scrollContent("body",new Bounds(0,0,120,40),120,0,offset.get(),offset::set,body->{
            ui.button(StudioComponents.Action.plain("start","Start",()->{}),new Bounds(body.x(),body.y(),body.width(),20),0,Kind.BUTTON);
            try(var moving=frame.contentClipped(new Bounds(body.x(),body.y()+80,60,20))){
                ui.button(StudioComponents.Action.plain("inside","Inside",()->{}),new Bounds(body.x()+10,body.y()+80,20,20),0,Kind.BUTTON);
                ui.button(StudioComponents.Action.plain("outside","Outside",()->{}),new Bounds(body.x()+80,body.y()+80,20,20),0,Kind.BUTTON);
                ui.button(StudioComponents.Action.plain("partial","Partial",()->{}),new Bounds(body.x()+50,body.y()+80,20,20),0,Kind.BUTTON);
            }
        });input.publish(frame.snapshot());};
        publish.run();assertEquals(Set.of("start","inside","partial"),frame.snapshot().focusTargets().keySet());input.focus("start");
        input.cycleFocus(false);publish.run();assertEquals("inside",tree.focused().orElseThrow().id());input.cycleFocus(false);publish.run();assertEquals("partial",tree.focused().orElseThrow().id());
        assertEquals(10,tree.focused().orElseThrow().visibleBounds().width());assertTrue(tree.hit(65,25).isEmpty());assertFalse(node(frame,"outside").focusable());
    }

    @Test void emptyZeroWidthAndZeroHeightScopesCannotCreateKeyboardTargets(){
        for(var viewport:List.of(new Bounds(10,30,0,40),new Bounds(10,30,100,0),new Bounds(10,30,8,40))){
            var h=new Harness();h.viewport=viewport;h.outside=true;h.publish();assertTrue(h.frame.snapshot().focusTargets().isEmpty());
            h.input.cycleFocus(false);assertEquals("outside",h.focused());assertEquals(0,h.offset.get());
        }
        var h=new Harness();h.ids=List.of();h.publish();h.input.cycleFocus(false);assertNull(h.focused());assertTrue(h.frame.snapshot().focusTargets().isEmpty());
    }

    @Test void revealScopeRestoresAfterFailureAndSnapshotsSurviveFrameReuse(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var viewport=new Bounds(10,30,200,40);
        assertThrows(IllegalStateException.class,()->ui.scrollContent("body",viewport,200,0,0,value->{},body->{
            ui.button(StudioComponents.Action.plain("inside","Inside",()->{}),new Bounds(body.x(),body.y()+100,body.width(),20),0,Kind.BUTTON);throw new IllegalStateException("builder failure");
        }));
        ui.button(StudioComponents.Action.plain("outside","Outside",()->{}),new Bounds(10,10,100,20),0,Kind.BUTTON);
        var snapshot=frame.snapshot();assertEquals(Set.of("inside"),snapshot.focusTargets().keySet());assertNull(frame.clip());
        assertThrows(UnsupportedOperationException.class,()->snapshot.focusTargets().clear());frame.clear();assertTrue(frame.snapshot().focusTargets().isEmpty());assertEquals(Set.of("inside"),snapshot.focusTargets().keySet());
    }

    @Test void clippedControlsWithoutARevealScopeStayOutsideTheKeyboardOrder(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
        try(var ignored=frame.clipped(new Bounds(0,0,100,20))){ui.input("hidden","Hidden","",new Bounds(0,40,100,20),0,true,false,value->fail("Hidden input cannot edit"));}
        ui.button(StudioComponents.Action.plain("visible","Visible",()->{}),new Bounds(0,0,100,20),0,Kind.BUTTON);input.publish(frame.snapshot());
        input.cycleFocus(false);assertEquals("visible",tree.focused().orElseThrow().id());input.cycleFocus(true);assertEquals("visible",tree.focused().orElseThrow().id());assertFalse(input.insert("ignored"));
    }
}
