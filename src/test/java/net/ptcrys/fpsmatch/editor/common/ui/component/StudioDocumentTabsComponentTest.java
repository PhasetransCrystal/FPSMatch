package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioDocumentTabsComponentTest {
    @Test void closeAndActivationHaveSeparateSemanticTargetsWithinOneSelectedTab(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var activated=new AtomicInteger();var closed=new AtomicInteger();
        ui.documentTabs("strip",new Bounds(10,20,500,24),0,List.of(tab("doc","Map A",true,true,activated,closed)),0);
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
        var body=node(frame,"doc");var close=node(frame,"doc/close");var background=node(frame,"doc/surface");
        assertEquals(ControlTree.Role.TAB,body.role());assertTrue(body.selected());assertEquals("Map A",body.name());
        assertEquals(Kind.DOCUMENT_TAB,frame.visual(background.id()).kind());assertTrue(background.selected());
        assertEquals(ControlTree.Role.BUTTON,close.role());assertEquals("Close Map A",close.name());
        assertTrue(body.bounds().x()+body.bounds().width()<=close.bounds().x());
        assertEquals("doc",tree.hit(body.bounds().x()+2,body.bounds().y()+10).orElseThrow().id());
        assertEquals("doc/close",tree.hit(close.bounds().x()+2,close.bounds().y()+2).orElseThrow().id());
        frame.actions().get("doc/close").run();assertEquals(1,closed.get());assertEquals(0,activated.get());
        frame.actions().get("doc").run();assertEquals(1,activated.get());assertEquals(1,closed.get());
        assertFalse(node(frame,"doc/dirty").focusable());
        var marker=node(frame,"doc/dirty").bounds();assertTrue(tree.pointerExposed("doc/surface",marker.x()+1,marker.y()+1));
        assertFalse(tree.pointerExposed("doc/surface",close.bounds().x()+1,close.bounds().y()+1));
        ui.surface("overlay",background.bounds(),0,Kind.PANEL);tree.publish(frame.snapshot().nodes());
        assertFalse(tree.pointerExposed("doc/surface",marker.x()+1,marker.y()+1),"Compound padding must not receive pointers through later opaque surfaces");
    }
    @Test void dirtyStateReservesTheSameCaptionAndCloseGeometry(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var activated=new AtomicInteger();var closed=new AtomicInteger();var bounds=new Bounds(0,0,400,24);
        ui.documentTabs("strip",bounds,0,List.of(tab("doc","A long document name",true,false,activated,closed)),0);
        var cleanBody=node(frame,"doc").bounds();var cleanClose=node(frame,"doc/close").bounds();
        assertTrue(frame.nodes().stream().noneMatch(node->node.id().equals("doc/dirty")));
        frame.clear();ui.documentTabs("strip",bounds,0,List.of(tab("doc","A long document name",true,true,activated,closed)),0);
        assertEquals(cleanBody,node(frame,"doc").bounds());assertEquals(cleanClose,node(frame,"doc/close").bounds());
        assertEquals("A long document name",frame.details().get("doc"));assertEquals(0,activated.get());assertEquals(0,closed.get());
    }
    @Test void overflowBrowsingRetainsItsIdentityAcrossReorderWithoutActivatingOrClosing(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var activated=new AtomicInteger();var closed=new AtomicInteger();
        var tabs=java.util.stream.IntStream.range(0,5).mapToObj(i->tab("tab/"+i,"Document "+i,i==4,false,activated,closed)).toList();
        int offset=ui.documentTabs("strip",new Bounds(0,0,165,24),0,tabs,0);assertEquals(List.of("tab/4"),visibleTabs(frame));
        frame.actions().get("strip/previous").run();frame.clear();offset=ui.documentTabs("strip",new Bounds(0,0,165,24),0,tabs,offset);
        assertEquals(List.of("tab/3"),visibleTabs(frame));
        var reordered=List.of(tabs.get(4),tabs.get(3),tabs.get(2),tabs.get(1),tabs.get(0));
        frame.clear();ui.documentTabs("strip",new Bounds(0,0,165,24),0,reordered,offset);assertEquals(List.of("tab/3"),visibleTabs(frame));
        assertEquals(0,activated.get());assertEquals(0,closed.get());
        frame.actions().get("tab/3/close").run();assertEquals(1,closed.get());assertEquals(0,activated.get());
    }
    @Test void narrowStripsKeepCloseAndOverflowArrowsInsideTheirPublishedClip(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var activated=new AtomicInteger();var closed=new AtomicInteger();
        var tabs=List.of(tab("first","First document",true,true,activated,closed),tab("second","Second document",false,false,activated,closed));
        for(int width:List.of(70,96,135)){
            frame.clear();var bounds=new Bounds(10,20,width,24);ui.documentTabs("strip",bounds,0,tabs,0);
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());var close=node(frame,"first/close");
            assertTrue(close.visibleBounds().width()>0);assertEquals("first/close",tree.hit(close.visibleBounds().x()+1,close.visibleBounds().y()+1).orElseThrow().id());
            for(var node:frame.nodes()){var visible=node.visibleBounds();assertTrue(visible.x()>=10);assertTrue(visible.x()+visible.width()<=10+width);}
            for(String id:List.of("strip/previous","strip/next")){var arrow=node(frame,id);assertEquals(id,tree.hit(arrow.visibleBounds().x()+1,arrow.visibleBounds().y()+1).orElseThrow().id());}
        }
        assertEquals(0,activated.get());assertEquals(0,closed.get());
    }
    private static StudioComponents.DocumentTab tab(String id,String title,boolean selected,boolean dirty,AtomicInteger activated,AtomicInteger closed){
        return new StudioComponents.DocumentTab(new StudioComponents.Action(id,title,Icon.MAP,Variant.DEFAULT,true,selected,"",activated::incrementAndGet),dirty,"Close "+title,closed::incrementAndGet);
    }
    @Test void dragFeedbackShowsTheSourceAndInsertionEdgeWithoutChangingSelectionOrInvokingActions(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var activated=new AtomicInteger();var closed=new AtomicInteger();
        var source=tab("source","First",false,false,activated,closed);var target=tab("target","Second",true,false,activated,closed);
        ui.documentTabs("strip",new Bounds(0,0,500,24),0,List.of(
            new StudioComponents.DocumentTab(source.activate(),false,source.closeLabel(),source.close(),true,StudioComponents.TabInsertion.NONE),
            new StudioComponents.DocumentTab(target.activate(),false,target.closeLabel(),target.close(),false,StudioComponents.TabInsertion.AFTER)),0);
        assertTrue(frame.visual("source/surface").dimmed());assertTrue(frame.visual("source").dimmed());
        assertFalse(node(frame,"source").selected());assertTrue(node(frame,"target").selected());
        var drop=node(frame,"target/drop");var bounds=node(frame,"target/surface").bounds();
        assertEquals(bounds.x()+bounds.width(),drop.bounds().x()+drop.bounds().width());assertEquals(Variant.PRIMARY,frame.visual(drop.id()).variant());
        assertFalse(drop.focusable());assertFalse(drop.pointerBarrier());assertEquals(0,activated.get());assertEquals(0,closed.get());
        frame.clear();ui.documentTabs("strip",new Bounds(0,0,500,24),0,List.of(source,target),0);
        assertFalse(frame.visual("source/surface").dimmed());assertTrue(frame.nodes().stream().noneMatch(node->node.id().endsWith("/drop")));
    }
    private static ControlTree.Node node(StudioFrame frame,String id){return frame.nodes().stream().filter(node->node.id().equals(id)).findFirst().orElseThrow();}
    private static List<String> visibleTabs(StudioFrame frame){return frame.nodes().stream().filter(node->node.role()==ControlTree.Role.TAB&&node.visibleBounds().width()>0).map(ControlTree.Node::id).toList();}
}
