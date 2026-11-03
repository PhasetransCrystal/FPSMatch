package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioDocking.Zone.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioDockingTest {
    private static final String WORKSPACE="workspace";
    private static final StudioDocking.Source<String> SOURCE=new StudioDocking.Source<>("objects","tab/objects","leftTop");
    private static final List<StudioDocking.Region> REGIONS=List.of(
            new StudioDocking.Region(LEFT_TOP,new Bounds(0,0,180,120),false),
            new StudioDocking.Region(LEFT_BOTTOM,new Bounds(0,122,180,178),true),
            new StudioDocking.Region(RIGHT,new Bounds(600,0,200,300),true),
            new StudioDocking.Region(BOTTOM,new Bounds(182,220,416,80),true));
    private static final class Fixture {
        final StudioFrame frame=new StudioFrame();
        final StudioComponents components=new StudioComponents(frame,StudioMetrics.compact(),value->value.length()*6);
        final ControlTree tree=new ControlTree();
        final StudioDocking<String> drag=new StudioDocking<>();
        final AtomicInteger actions=new AtomicInteger();
        Fixture(){
            components.dockHeader("dock",new Bounds(0,0,180,24),0,List.of(new StudioComponents.Action(SOURCE.controlId(),"Objects",Icon.NONE,Variant.DEFAULT,true,true,"",actions::incrementAndGet)),
                    List.of(StudioComponents.Action.plain("menu","Menu",actions::incrementAndGet),StudioComponents.Action.plain("maximize","Maximize",actions::incrementAndGet),StudioComponents.Action.plain("hide","Hide",actions::incrementAndGet)),null,0);
            tree.publish(frame.nodes());drag.reconcile(WORKSPACE,List.of(SOURCE),REGIONS);
        }
        boolean press(){return drag.press(tree,10,10,0);}
    }
    @Test void titleClickActivatesOnlyAfterReleaseAndThresholdIsEuclidean(){
        var f=new Fixture();assertTrue(f.press());assertEquals(0,f.actions.get());
        assertFalse(f.drag.drag(13,14));assertFalse(f.drag.moving());
        var click=f.drag.release(16,10,0).orElseThrow();assertFalse(click.move());assertEquals("objects",click.panel());
        assertFalse(f.drag.pending());assertTrue(f.drag.release(16,10,0).isEmpty());
        assertTrue(f.press());assertTrue(f.drag.drag(15,15));assertTrue(f.drag.moving());
        assertEquals(LEFT_TOP,f.drag.release(15,15,0).orElseThrow().target());
        assertEquals(0,f.actions.get(),"Capture returns intent; it never invokes host callbacks");
    }
    @Test void movingCommitsOnceAtReleaseCoordinatesAndDoesNotTurnBackIntoAClick(){
        var f=new Fixture();assertTrue(f.press());f.drag.drag(650,40);assertEquals(RIGHT,f.drag.target().orElseThrow());
        assertTrue(f.drag.release(650,40,1).isEmpty());assertTrue(f.drag.pending());
        var move=f.drag.release(300,250,0).orElseThrow();assertEquals(BOTTOM,move.target());assertEquals(SOURCE.panel(),move.panel());
        assertTrue(f.drag.release(650,40,0).isEmpty());
        assertTrue(f.press());f.drag.drag(650,40);f.drag.drag(10,10);
        assertTrue(f.drag.release(10,10,0).orElseThrow().move(),"Returning to origin still reorders within that group");
    }
    @Test void emptyRegionsAreReachableButCenterSeparatorsAndOutsideNeverAcceptDrops(){
        for(var region:REGIONS){var f=new Fixture();assertTrue(f.press());var b=region.bounds();f.drag.drag(b.x()+10,b.y()+30);assertEquals(region.zone(),f.drag.release(b.x()+10,b.y()+30,0).orElseThrow().target());}
        for(var point:List.of(new int[]{300,100},new int[]{181,100},new int[]{800,40},new int[]{30,-1})){
            var f=new Fixture();assertTrue(f.press());f.drag.drag(point[0],point[1]);assertTrue(f.drag.target().isEmpty());assertTrue(f.drag.release(point[0],point[1],0).isEmpty());
        }
        assertTrue(StudioDocking.Zone.fromKey("center").isEmpty());assertTrue(StudioDocking.Zone.fromKey("left").isEmpty());
    }
    @Test void headerCommandsWrongButtonsAndUnregisteredViewportCannotCapture(){
        var f=new Fixture();
        assertFalse(f.drag.press(f.tree,10,10,1));
        for(String id:List.of("menu","maximize","hide")){var node=f.tree.snapshot().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();assertFalse(f.drag.press(f.tree,node.visibleBounds().x()+1,node.visibleBounds().y()+1,0));}
        f.components.button(StudioComponents.Action.plain("world","World",f.actions::incrementAndGet),new Bounds(300,0,100,24),0,Kind.TAB);f.tree.publish(f.frame.nodes());
        assertFalse(f.drag.press(f.tree,310,10,0));assertEquals(0,f.actions.get());assertFalse(f.drag.pending());
    }
    @Test void clippingOpaquePanelsAndModalLayersUsePublishedHitGeometry(){
        var f=new Fixture();var source=f.tree.snapshot().stream().filter(node->node.id().equals(SOURCE.controlId())).findFirst().orElseThrow();
        f.tree.publish(List.of(new ControlTree.Node(source.id(),source.role(),source.name(),source.value(),source.bounds(),true,true,false,"",0,new Bounds(30,0,20,24))));
        assertFalse(f.drag.press(f.tree,10,10,0));assertTrue(f.drag.press(f.tree,31,10,0));f.drag.cancel();
        f.components.surface("cover",new Bounds(0,0,180,24),0,Kind.PANEL);f.tree.publish(f.frame.nodes());assertFalse(f.drag.press(f.tree,10,10,0));
        f.components.surface("modal",new Bounds(500,500,20,20),1,Kind.DIALOG);f.tree.publish(f.frame.nodes());assertFalse(f.drag.press(f.tree,10,10,0));
    }
    @Test void cancellationNeverProducesLateActivationOrMove(){
        var f=new Fixture();assertTrue(f.press());assertTrue(f.drag.cancel());assertFalse(f.drag.cancel());assertTrue(f.drag.release(10,10,0).isEmpty());
        assertTrue(f.press());f.drag.drag(650,40);f.drag.cancel();assertTrue(f.drag.release(650,40,0).isEmpty());assertTrue(f.drag.source().isEmpty());assertTrue(f.drag.target().isEmpty());
    }
    @Test void workspaceSwitchSourceRemovalAndDestinationRemovalCancelCapture(){
        var f=new Fixture();assertTrue(f.press());f.drag.drag(650,40);assertTrue(f.drag.reconcile("other",List.of(SOURCE),REGIONS));assertTrue(f.drag.release(650,40,0).isEmpty());
        f.drag.reconcile(WORKSPACE,List.of(SOURCE),REGIONS);assertTrue(f.press());assertTrue(f.drag.reconcile(WORKSPACE,List.of(),REGIONS));assertTrue(f.drag.release(10,10,0).isEmpty());
        f.drag.reconcile(WORKSPACE,List.of(SOURCE),REGIONS);assertTrue(f.press());f.drag.drag(650,40);assertTrue(f.drag.reconcile(WORKSPACE,List.of(SOURCE),REGIONS.stream().filter(region->region.zone()!=RIGHT).toList()));assertTrue(f.drag.release(650,40,0).isEmpty());
    }
    @Test void removingAnotherTitleOrChangingSourceGroupAlsoCancelsStaleGesture(){
        var f=new Fixture();var other=new StudioDocking.Source<>("inspector","tab/inspector","right");
        f.drag.reconcile(WORKSPACE,List.of(SOURCE,other),REGIONS);assertTrue(f.press());f.drag.drag(650,40);
        assertTrue(f.drag.reconcile(WORKSPACE,List.of(SOURCE),REGIONS));assertTrue(f.drag.release(650,40,0).isEmpty());
        assertTrue(f.press());assertTrue(f.drag.reconcile(WORKSPACE,List.of(new StudioDocking.Source<>(SOURCE.panel(),SOURCE.controlId(),"right")),REGIONS));assertTrue(f.drag.release(10,10,0).isEmpty());
    }
    @Test void geometryReflowPreservesCaptureAndRefreshesTargetWithoutMutatingCallerLists(){
        var f=new Fixture();var original=new ArrayList<>(REGIONS);f.drag.reconcile(WORKSPACE,List.of(SOURCE),original);original.clear();
        assertEquals(4,f.drag.regions().size());assertTrue(f.press());f.drag.drag(650,40);
        var resized=List.of(REGIONS.get(0),REGIONS.get(1),new StudioDocking.Region(RIGHT,new Bounds(700,0,100,300),true),REGIONS.get(3));
        assertFalse(f.drag.reconcile(WORKSPACE,List.of(SOURCE),resized));assertTrue(f.drag.pending());assertTrue(f.drag.target().isEmpty());
        assertTrue(f.drag.release(650,40,0).isEmpty());
    }
    @Test void headerSourceFeedbackAndTargetBorderDoNotStealHitTesting(){
        var f=new Fixture();f.frame.clear();
        f.components.dockHeader("dock",new Bounds(0,0,180,24),0,List.of(StudioComponents.Action.plain(SOURCE.controlId(),"Objects",()->{})),List.of(),SOURCE.controlId(),0);
        f.components.dockTarget("target",REGIONS.get(0),0,true,"Drop here");f.tree.publish(f.frame.nodes());
        assertEquals(SOURCE.controlId(),f.tree.hit(10,21).orElseThrow().id());assertTrue(f.frame.visual(SOURCE.controlId()).dimmed());
        var edges=f.tree.snapshot().stream().filter(node->node.id().startsWith("target/edge/")).toList();assertEquals(4,edges.size());
        for(var edge:edges){assertFalse(edge.pointerBarrier());assertEquals(edge.bounds(),edge.bounds().intersect(REGIONS.get(0).bounds()));assertEquals(Variant.PRIMARY,f.frame.visual(edge.id()).variant());}
        assertEquals(new Bounds(0,0,180,120),StudioDocking.regionBounds(new Bounds(0,0,180,24),new Bounds(0,24,180,96)));
    }
    @Test void overflowNavigationIsNotADraggableTitle(){
        var f=new Fixture();f.frame.clear();
        var titles=java.util.stream.IntStream.range(0,6).mapToObj(i->StudioComponents.Action.plain("tab/"+i,"Panel "+i,f.actions::incrementAndGet)).toList();
        f.components.dockHeader("dock",new Bounds(0,0,100,24),0,titles,List.of(),null,0);f.tree.publish(f.frame.nodes());
        f.drag.reconcile(WORKSPACE,java.util.stream.IntStream.range(0,6).mapToObj(i->new StudioDocking.Source<>("panel"+i,"tab/"+i,"leftTop")).toList(),REGIONS);
        var next=f.tree.snapshot().stream().filter(node->node.id().equals("dock/next")).findFirst().orElseThrow();assertFalse(f.drag.press(f.tree,next.bounds().x()+1,next.bounds().y()+1,0));
        f.frame.actions().get(next.id()).run();assertEquals(0,f.actions.get());
    }
    @Test void shrinkingDockKeepsDraggedTitleVisibleWithoutActivatingItsPanel(){
        var f=new Fixture();f.frame.clear();
        var titles=java.util.stream.IntStream.range(0,6).mapToObj(i->new StudioComponents.Action("tab/"+i,"Panel "+i,Icon.NONE,Variant.DEFAULT,true,i==0,"",f.actions::incrementAndGet)).toList();
        f.components.dockHeader("dock",new Bounds(0,0,500,24),0,titles,List.of(),null,0);
        f.frame.clear();f.components.dockHeader("dock",new Bounds(0,0,100,24),0,titles,List.of(),"tab/5",0);f.tree.publish(f.frame.nodes());
        var source=f.tree.snapshot().stream().filter(node->node.id().equals("tab/5")).findFirst().orElseThrow();
        assertTrue(source.visibleBounds().width()>0);assertTrue(f.frame.visual(source.id()).dimmed());assertEquals(0,f.actions.get());
        assertTrue(titles.get(0).selected());assertFalse(titles.get(5).selected(),"Temporary source emphasis must not mutate the host's active panel");
    }
    @Test void maximizedTitleCanRevealTargetsDuringCaptureAndCancelWithoutAnIntent(){
        var f=new Fixture();f.drag.reconcile(WORKSPACE,List.of(SOURCE),List.of());assertTrue(f.press());
        f.drag.drag(650,40);assertTrue(f.drag.moving());assertTrue(f.drag.target().isEmpty());
        assertFalse(f.drag.reconcile(WORKSPACE,List.of(SOURCE),REGIONS));assertEquals(RIGHT,f.drag.target().orElseThrow());
        f.drag.cancel();assertTrue(f.drag.release(650,40,0).isEmpty());assertEquals(0,f.actions.get());
    }
}
