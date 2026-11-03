package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioInputPublicationTest {
    private static final Bounds VIEWPORT=new Bounds(0,0,100,20);
    private static Node node(String id,Role role,String value){return new Node(id,role,id,value,VIEWPORT,true,false,false,"",0);}
    private static StudioFrame.Snapshot snapshot(List<Node> nodes,Map<String,Consumer<String>> changes,Map<String,Runnable> blur){
        return new StudioFrame.Snapshot(nodes,Map.of(),Map.of(),changes,blur,Map.of(),Map.of(),List.of(),Map.of());
    }

    @Test void rejectedPublicationKeepsRawHistoryCallbacksFocusAndPreparedOrder(){
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
        var raw=new AtomicReference<>("seed");var oldBlur=new AtomicInteger();var rejected=new AtomicInteger();
        Node first=node("first",Role.TEXT_FIELD,raw.get()),second=node("second",Role.BUTTON,"");
        input.publish(snapshot(List.of(first,second),Map.of(first.id(),raw::set),Map.of(first.id(),oldBlur::incrementAndGet)));
        input.focus(first.id());assertTrue(input.insert("!"));
        var texts=input.visibleText();var edit=texts.get(first.id());edit.select(1,false);edit.select(3,true);
        var frame=tree.snapshot();var geometry=tree.geometry(first.id()).orElseThrow();
        var invalid=snapshot(List.of(node("first",Role.TEXT_FIELD,"overwritten"),node("new",Role.BUTTON,""),node("first",Role.BUTTON,"")),
                Map.of(first.id(),ignored->rejected.incrementAndGet()),Map.of(first.id(),rejected::incrementAndGet));
        assertThrows(IllegalArgumentException.class,()->input.publish(invalid));
        assertSame(frame,tree.snapshot());assertSame(geometry,tree.geometry(first.id()).orElseThrow());assertSame(texts,input.visibleText());
        assertSame(edit,input.visibleText().get(first.id()));assertEquals("seed!",edit.text());assertEquals(3,edit.caret());assertEquals(1,edit.anchor());
        assertEquals(first.id(),tree.focused().orElseThrow().id());assertEquals(0,oldBlur.get());assertEquals(0,rejected.get());
        assertTrue(edit.undo());input.changed(first.id());assertEquals("seed",raw.get());
        assertTrue(edit.redo());input.changed(first.id());assertEquals("seed!",raw.get());
        input.cycleFocus(false);assertEquals(second.id(),tree.focused().orElseThrow().id());
        assertEquals(1,oldBlur.get());assertEquals(0,rejected.get());
    }

    @Test void mutationOfPublicSnapshotSourcesCannotReplaceLiveCallbacksOrOrder(){
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
        var raw=new AtomicReference<>("seed");var committed=new AtomicInteger();var injected=new AtomicInteger();
        var nodes=new ArrayList<>(List.of(node("first",Role.TEXT_FIELD,raw.get()),node("second",Role.BUTTON,"")));
        var changes=new HashMap<String,Consumer<String>>();changes.put("first",raw::set);
        var blur=new HashMap<String,Runnable>();blur.put("first",committed::incrementAndGet);
        var next=snapshot(nodes,changes,blur);
        nodes.clear();nodes.add(node("injected",Role.BUTTON,""));changes.put("first",ignored->injected.incrementAndGet());blur.put("first",injected::incrementAndGet);
        input.publish(next);input.focus("first");assertTrue(input.insert("!"));input.cycleFocus(false);
        assertEquals("seed!",raw.get());assertEquals(1,committed.get());assertEquals(0,injected.get());
        assertEquals("second",tree.focused().orElseThrow().id());assertTrue(tree.node("injected").isEmpty());
    }

    @Test void eachSuccessfulPublicationRebuildsOrderFromCurrentRevealGeometryAndCallbacks(){
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
        Node first=node("first",Role.BUTTON,""),last=node("last",Role.BUTTON,"");
        Node hidden=new Node("hidden",Role.BUTTON,"Hidden","",new Bounds(0,80,100,20),true,false,false,"",0,VIEWPORT);
        var nodes=List.of(first,hidden,last);var oldCalls=new AtomicInteger();var newCalls=new AtomicInteger();
        var oldTarget=new StudioFocusNavigation.Target(new StudioFocusNavigation.Region("body",VIEWPORT,0,100,0,ignored->oldCalls.incrementAndGet()),VIEWPORT,hidden.bounds());
        input.publish(new StudioFrame.Snapshot(nodes,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of(hidden.id(),oldTarget)));
        input.focus(first.id());
        var blockedTarget=new StudioFocusNavigation.Target(new StudioFocusNavigation.Region("body",VIEWPORT,0,0,0,ignored->newCalls.incrementAndGet()),VIEWPORT,hidden.bounds());
        input.publish(new StudioFrame.Snapshot(nodes,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of(hidden.id(),blockedTarget)));
        input.cycleFocus(false);assertEquals(last.id(),tree.focused().orElseThrow().id());assertEquals(0,oldCalls.get());assertEquals(0,newCalls.get());
        var newTarget=new StudioFocusNavigation.Target(new StudioFocusNavigation.Region("body",VIEWPORT,0,100,0,ignored->newCalls.incrementAndGet()),VIEWPORT,hidden.bounds());
        input.publish(new StudioFrame.Snapshot(nodes,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of(hidden.id(),newTarget)));
        input.focus(first.id());input.cycleFocus(false);
        assertEquals(first.id(),tree.focused().orElseThrow().id());assertEquals(0,oldCalls.get());assertEquals(1,newCalls.get());
    }

    @Test void blurCallbackObservesTheCompleteNewPublication(){
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);var calls=new AtomicInteger();var raw=new AtomicReference<>("new");
        input.publish(snapshot(List.of(node("old",Role.TEXT_FIELD,"old")),Map.of(),Map.of("old",()->{
            calls.incrementAndGet();assertTrue(tree.node("old").isEmpty());assertTrue(tree.node("new").isPresent());
            assertEquals(List.of("new"),input.visibleText().keySet().stream().toList());
            input.cycleFocus(false);assertEquals("new",tree.focused().orElseThrow().id());assertTrue(input.insert("!"));
        })));
        input.focus("old");
        input.publish(snapshot(List.of(node("new",Role.TEXT_FIELD,raw.get())),Map.of("new",raw::set),Map.of()));
        assertEquals(1,calls.get());assertEquals("new!",raw.get());assertEquals("new",tree.focused().orElseThrow().id());
    }

    @Test void repeatedBridgeTabsUsePreparedOrderWithBoundedIndexedWork(){
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);var nodes=new ArrayList<Node>();
        int count=2_048,steps=count*2;
        for(int i=0;i<count;i++)nodes.add(node("button/"+i,Role.BUTTON,""));
        input.publish(snapshot(nodes,Map.of(),Map.of()));input.focus(nodes.get(0).id());var before=tree.work();
        for(int i=0;i<steps;i++){input.cycleFocus(false);assertEquals(nodes.get((i+1)%count).id(),tree.focused().orElseThrow().id());}
        var after=tree.work();assertEquals(before.geometryBuilds(),after.geometryBuilds());
        assertEquals(before.pointerCandidatesVisited(),after.pointerCandidatesVisited());
        assertTrue(after.indexedLookups()-before.indexedLookups()<=3L*steps,"Tab work must be bounded independently of the 2,048 published controls");
    }
}
