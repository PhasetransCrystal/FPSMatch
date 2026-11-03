package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioFocusNavigation.Order;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioFocusNavigation.Region;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioFocusNavigation.Target;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioFocusOrderTest {
    private static final Bounds VIEWPORT=new Bounds(0,0,100,20);
    private static Region region(int maximum){return new Region("body",VIEWPORT,0,maximum,2,ignored->{});}
    private static Node node(String id,Role role,Bounds bounds,int layer,boolean enabled){
        return new Node(id,role,id,"",bounds,enabled,false,false,"",layer,VIEWPORT);
    }

    @Test void preparedOrderKeepsDisabledAndRevealableControlsInStableTopLayerOrder(){
        var tree=new ControlTree();var region=region(100);
        Node first=node("first",Role.BUTTON,new Bounds(0,0,40,20),2,false);
        Node hidden=node("hidden",Role.TEXT_FIELD,new Bounds(0,40,40,20),2,true);
        Node outside=node("outside",Role.BUTTON,new Bounds(120,40,40,20),2,true);
        Node unreachable=node("unreachable",Role.BUTTON,new Bounds(0,200,40,20),2,true);
        Node zero=node("zero",Role.BUTTON,new Bounds(0,0,0,20),2,true);
        Node label=node("label",Role.LABEL,new Bounds(0,0,40,20),2,true);
        Node background=node("background",Role.BUTTON,new Bounds(0,0,40,20),0,true);
        tree.publish(List.of(first,hidden,background,outside,unreachable,zero,label));
        var order=Order.prepare(tree,Map.of(
                hidden.id(),new Target(region,VIEWPORT,hidden.bounds()),
                outside.id(),new Target(region,VIEWPORT,outside.bounds()),
                unreachable.id(),new Target(region,VIEWPORT,unreachable.bounds())));
        assertEquals(List.of(first,hidden),order.candidates());assertEquals(2,order.size());
        assertEquals(0,order.indexOf(first.id()));assertEquals(1,order.indexOf(hidden.id()));
        assertEquals(-1,order.indexOf(background.id()));assertEquals(-1,order.indexOf(null));
        assertSame(first,order.next(null,false).orElseThrow());assertSame(hidden,order.next(null,true).orElseThrow());
        assertSame(hidden,order.next(first.id(),false).orElseThrow());assertSame(first,order.next(hidden.id(),false).orElseThrow());
        assertSame(hidden,order.next(first.id(),true).orElseThrow());assertSame(first,order.next(hidden.id(),true).orElseThrow());
        assertSame(first,order.next("removed",false).orElseThrow());
        assertThrows(UnsupportedOperationException.class,()->order.candidates().clear());
        assertTrue(tree.hit(5,45).isEmpty(),"A reveal candidate has no offscreen pointer target");
    }

    @Test void everyPreparationUsesCurrentRegionGeometryWithoutMutatingEarlierOrders(){
        var tree=new ControlTree();
        Node hidden=node("hidden",Role.BUTTON,new Bounds(0,40,40,20),2,true);tree.publish(List.of(hidden));
        var targets=new HashMap<String,Target>();targets.put(hidden.id(),new Target(region(100),VIEWPORT,hidden.bounds()));
        var before=Order.prepare(tree,targets);
        targets.put(hidden.id(),new Target(region(0),VIEWPORT,hidden.bounds()));
        var after=Order.prepare(tree,targets);
        assertEquals(List.of(hidden),before.candidates());assertTrue(after.candidates().isEmpty());
        targets.clear();tree.publish(List.of());
        assertSame(hidden,before.next(null,false).orElseThrow());
        assertTrue(Order.prepare(tree,targets).next(null,false).isEmpty());
    }

    @Test void invisibleModalLayerAndWrongLayerRevealMetadataCannotExposeBackgroundCandidates(){
        var tree=new ControlTree();
        Node background=node("background",Role.BUTTON,new Bounds(0,0,40,20),0,true);
        Node modal=node("modal",Role.PANEL,new Bounds(0,0,0,0),2,true);
        tree.publish(List.of(background,modal));assertTrue(Order.prepare(tree,Map.of()).candidates().isEmpty());
        Node hidden=node("hidden",Role.BUTTON,new Bounds(0,40,40,20),2,true);
        var wrong=new Region("other",VIEWPORT,0,100,1,ignored->{});
        tree.publish(List.of(hidden));
        assertTrue(Order.prepare(tree,Map.of(hidden.id(),new Target(wrong,VIEWPORT,hidden.bounds()))).candidates().isEmpty());
    }

    @Test void repeatedNavigationDoesNotRescanRevealMetadataOrRebuildGeometry(){
        var tree=new ControlTree();var nodes=new ArrayList<Node>();var targets=new CountingTargets();var region=region(100_000);
        int count=4_096;
        for(int i=0;i<count;i++){
            Node node=node("field/"+i,Role.TEXT_FIELD,new Bounds(0,40+i*22,40,20),2,true);nodes.add(node);
            targets.put(node.id(),new Target(region,VIEWPORT,node.bounds()));
        }
        tree.publish(nodes);var before=tree.work();var order=Order.prepare(tree,targets);var candidates=order.candidates();
        assertEquals(count,targets.reads);assertEquals(count,order.size());
        assertEquals(count,tree.work().indexedLookups()-before.indexedLookups());
        var prepared=tree.work();String anchor=null;
        for(int i=0;i<count*2;i++){
            Node next=order.next(anchor,false).orElseThrow();assertSame(nodes.get(i%count),next);anchor=next.id();
            assertEquals(i%count,order.indexOf(anchor));assertSame(candidates,order.candidates());
        }
        for(int i=0;i<count;i++){
            Node next=order.next(anchor,true).orElseThrow();assertSame(nodes.get(Math.floorMod(count-2-i,count)),next);anchor=next.id();
        }
        assertEquals(count,targets.reads,"Tab traversal must use the prepared order without any reveal-map reads");
        assertEquals(prepared,tree.work(),"Tab traversal must not consult or rebuild tree geometry");
    }

    private static final class CountingTargets extends HashMap<String,Target>{
        int reads;
        @Override public Target get(Object key){reads++;return super.get(key);}
    }
}
