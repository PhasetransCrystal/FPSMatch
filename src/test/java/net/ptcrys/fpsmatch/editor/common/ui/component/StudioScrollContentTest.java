package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioScrollContentTest {
    private static StudioComponents components(StudioFrame frame){return new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);}
    private static ControlTree.Node node(StudioFrame frame,String id){return frame.nodes().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();}
    private static void assertHit(ControlTree tree,ControlTree.Node node){assertEquals(node.bounds(),node.visibleBounds());assertEquals(node.id(),tree.hit(node.bounds().x()+1,node.bounds().y()+1).orElseThrow().id());}

    @Test void wholeBodyScrollsAtShortHeightAndItsLastActionRemainsReachable(){
        var frame=new StudioFrame();var ui=components(frame);var viewport=new Bounds(20,40,160,40);var calls=new AtomicInteger();
        for(int offset:List.of(0,120)){
            frame.clear();ui.button(StudioComponents.Action.plain("dock/header","Fixed dock title",calls::incrementAndGet),new Bounds(20,10,160,20),0,Kind.BUTTON);
            int position=ui.scrollContent("body",viewport,160,0,offset,value->fail("Composition must not change scroll state"),body->{
                assertEquals(new Bounds(20,40-offset,152,160),body);
                ui.button(StudioComponents.Action.plain("body/heading","Body heading",calls::incrementAndGet),new Bounds(body.x(),body.y(),body.width(),20),0,Kind.BUTTON);
                ui.button(StudioComponents.Action.plain("body/last","Final field action",calls::incrementAndGet),new Bounds(body.x(),body.y()+140,body.width(),20),0,Kind.BUTTON);
            });
            assertEquals(offset,position);var tree=new ControlTree();tree.publish(frame.snapshot().nodes());assertHit(tree,node(frame,"dock/header"));
            assertHit(tree,node(frame,offset==0?"body/heading":"body/last"));
            assertFalse(node(frame,offset==0?"body/last":"body/heading").focusable());
        }
        assertEquals(0,calls.get());frame.actions().get("body/last").run();assertEquals(1,calls.get());
    }

    @Test void offsetsClampAndTheContentWidthIsStableWithoutCompositionCallbacks(){
        var frame=new StudioFrame();var ui=components(frame);var viewport=new Bounds(10,30,100,60);var supplied=new AtomicReference<Bounds>();var changes=new AtomicInteger();
        for(int offset:List.of(Integer.MIN_VALUE,20,Integer.MAX_VALUE)){
            frame.clear();int expected=offset<0?0:Math.min(offset,140);
            assertEquals(expected,ui.scrollContent("body",viewport,200,0,offset,value->changes.incrementAndGet(),supplied::set));
            assertEquals(new Bounds(10,30-expected,92,200),supplied.get());assertEquals(expected,frame.scrolls().get(0).offset());
        }
        frame.clear();assertEquals(0,ui.scrollContent("body",viewport,20,0,500,value->changes.incrementAndGet(),supplied::set));
        assertEquals(new Bounds(10,30,92,20),supplied.get());assertTrue(frame.scrolls().isEmpty());
        assertEquals(0,ui.scrollContent("empty",viewport,0,0,500,value->changes.incrementAndGet(),ignored->fail("Empty content must not publish controls")));
        assertThrows(IllegalArgumentException.class,()->ui.scrollContent("invalid",viewport,-1,0,0,value->{},ignored->fail("Invalid dimensions must not compose")));
        assertEquals(0,changes.get());
    }

    @Test void outerClipDefinesTheEffectiveVerticalContentAndTrackGeometry(){
        var frame=new StudioFrame();var ui=components(frame);var outer=new Bounds(30,40,90,40);var viewport=new Bounds(20,20,100,100);var changed=new AtomicInteger();
        try(var ignored=frame.clipped(outer)){
            ui.scrollContent("body",viewport,200,0,20,changed::set,body->ui.button(StudioComponents.Action.plain("wide-child","Wide child",()->{}),new Bounds(0,0,300,300),0,Kind.BUTTON));
            assertEquals(outer,frame.clip());
        }
        assertNull(frame.clip());assertEquals(new Bounds(30,40,82,40),node(frame,"wide-child").visibleBounds());
        var region=frame.scrolls().get(0);assertEquals(new Bounds(20,40,100,40),region.viewport());assertEquals(viewport.intersect(outer),region.clip());assertEquals(40,region.track().height());assertEquals(160,region.maximum());
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());assertEquals("wide-child",tree.hit(111,50).orElseThrow().id());assertTrue(tree.hit(112,50).isEmpty());assertTrue(tree.hit(25,50).isEmpty());
        var scrolling=new StudioScrollState();assertFalse(scrolling.wheel(frame.scrolls(),25,50,-1,0,tree));assertFalse(scrolling.wheel(frame.scrolls(),40,25,-1,0,tree));
        assertTrue(scrolling.wheel(frame.scrolls(),40,50,-1,0,tree));assertEquals(40,changed.get());
        assertFalse(scrolling.press(frame.scrolls(),115,25,0,tree));assertTrue(scrolling.press(frame.scrolls(),115,50,0,tree));
    }

    @Test void zeroSizedViewportsDoNotPublishOrInvokeContentAndRetainTheOuterClip(){
        var frame=new StudioFrame();var ui=components(frame);var outer=new Bounds(0,0,200,200);var composed=new AtomicInteger();var changed=new AtomicInteger();
        for(var viewport:List.of(new Bounds(10,20,0,60),new Bounds(10,20,120,0),new Bounds(10,20,0,0))){
            frame.clear();try(var ignored=frame.clipped(outer)){
                int offset=ui.scrollContent("body",viewport,200,0,Integer.MAX_VALUE,value->changed.incrementAndGet(),body->{composed.incrementAndGet();ui.button(StudioComponents.Action.plain("leak","Must not leak",()->{}),new Bounds(0,0,200,200),0,Kind.BUTTON);});
                assertEquals(200-viewport.height(),offset);assertEquals(outer,frame.clip());assertTrue(frame.nodes().isEmpty());assertTrue(frame.scrolls().isEmpty());
            }
            assertNull(frame.clip());var tree=new ControlTree();tree.publish(frame.snapshot().nodes());assertTrue(tree.hit(11,21).isEmpty());
        }
        assertEquals(0,composed.get());assertEquals(0,changed.get());
    }

    @Test void compositionFailureRestoresOuterClipAndDoesNotPublishAScrollCallback(){
        var frame=new StudioFrame();var ui=components(frame);var outer=new Bounds(10,20,150,100);var viewport=new Bounds(30,30,100,60);var failure=new IllegalStateException("builder failed");
        try(var ignored=frame.clipped(outer)){
            var thrown=assertThrows(IllegalStateException.class,()->ui.scrollContent("body",viewport,300,0,40,value->fail("No scroll action during composition"),body->{
                ui.button(StudioComponents.Action.plain("partial","Partial child",()->{}),new Bounds(0,0,400,400),0,Kind.BUTTON);throw failure;
            }));
            assertSame(failure,thrown);assertEquals(outer,frame.clip());assertTrue(frame.scrolls().isEmpty());
            assertEquals(new Bounds(30,30,92,60),node(frame,"partial").visibleBounds());
            ui.button(StudioComponents.Action.plain("after","Caller control",()->{}),new Bounds(10,20,10,10),0,Kind.BUTTON);assertEquals(node(frame,"after").bounds(),node(frame,"after").visibleBounds());
        }
        assertNull(frame.clip());
    }

    @Test void scrollbarPublishesTheClampedRangeAndOnlyUserInputCallsItsChangeCallback(){
        var frame=new StudioFrame();var ui=components(frame);var viewport=new Bounds(20,30,120,60);var changes=new AtomicInteger();var value=new AtomicInteger(-1);var actions=new AtomicInteger();
        ui.scrollContent("body",viewport,300,0,40,next->{changes.incrementAndGet();value.set(next);},body->ui.button(StudioComponents.Action.plain("body/action","Action",actions::incrementAndGet),new Bounds(body.x(),body.y()+40,body.width(),20),0,Kind.BUTTON));
        assertEquals(0,changes.get());var region=frame.scrolls().get(0);assertEquals("body/scroll/track",region.id());assertEquals(40,region.offset());assertEquals(240,region.maximum());assertEquals(20,region.step());
        assertEquals(new Bounds(133,30,7,60),region.track());assertTrue(region.thumb().y()>=region.track().y());assertTrue(region.thumb().y()+region.thumb().height()<=region.track().y()+region.track().height());
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());var scrolling=new StudioScrollState();
        assertTrue(scrolling.wheel(frame.scrolls(),22,32,-1,0,tree));assertEquals(60,value.get());assertEquals(1,changes.get());
        assertFalse(scrolling.wheel(frame.scrolls(),22,32,0,0,tree));assertFalse(scrolling.wheel(frame.scrolls(),22,32,-1,1,tree));assertEquals(1,changes.get());
        assertTrue(scrolling.press(frame.scrolls(),region.thumb().x()+1,region.thumb().y()+2,0,tree));
        assertTrue(scrolling.drag(1000));assertEquals(240,value.get());assertTrue(scrolling.drag(-1000));assertEquals(0,value.get());
        assertTrue(scrolling.release(1000));assertEquals(240,value.get());assertFalse(scrolling.drag(50));assertEquals(0,actions.get());
    }

    @Test void formRowsUseTheContainerWithoutChangingFieldIdentityRawValuesOrCallbacks(){
        var frame=new StudioFrame();var ui=components(frame);var viewport=new Bounds(10,20,200,36);var changes=new HashMap<String,String>();var commits=new AtomicInteger();
        var rows=new ArrayList<List<StudioComponents.Field>>();rows.add(List.of());
        for(int i=0;i<3;i++){String id="field/"+i;rows.add(List.of(new StudioComponents.Field(id,"Field "+i,"raw "+i,"raw "+i,"",StudioComponents.FieldKind.TEXT,0,true,false,value->changes.put(id,value),()->{},commits::incrementAndGet)));}
        int height=3*ui.fieldRowHeight(1,false,false,false,viewport.width()-8);
        assertEquals(height-viewport.height(),ui.formRows("form",rows,viewport,0,Integer.MAX_VALUE,value->fail("Composition must not save scroll state")));
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());var last=node(frame,"field/2");assertHit(tree,last);assertEquals("raw 2",last.value());
        assertEquals("form/scroll/track",frame.scrolls().get(0).id());assertTrue(changes.isEmpty());assertEquals(0,commits.get());
        frame.snapshot().changes().get("field/2").accept("-");assertEquals(Map.of("field/2","-"),changes);
        frame.snapshot().blur().get("field/2").run();assertEquals(1,commits.get());
    }

    @Test void laterOpaqueSurfaceBlocksThisBodyWithoutStealingItsNeighborsScroll(){
        var frame=new StudioFrame();var ui=components(frame);var first=new AtomicInteger();var second=new AtomicInteger();
        ui.scrollContent("first",new Bounds(0,0,100,80),300,0,0,first::set,body->ui.surface("first/content",body,0,Kind.PANEL));
        ui.scrollContent("second",new Bounds(120,0,100,80),300,0,0,second::set,body->ui.surface("second/content",body,0,Kind.PANEL));
        ui.surface("cover",new Bounds(0,0,100,80),0,Kind.PANEL);var tree=new ControlTree();tree.publish(frame.snapshot().nodes());var scrolling=new StudioScrollState();
        assertFalse(scrolling.wheel(frame.scrolls(),20,20,-1,0,tree));assertFalse(scrolling.press(frame.scrolls(),95,20,0,tree));
        assertTrue(scrolling.wheel(frame.scrolls(),140,20,-1,0,tree));assertEquals(0,first.get());assertEquals(20,second.get());
    }
}
