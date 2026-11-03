package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Kind;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Visual;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioFrameSnapshotTest {
    @Test void publicConstructorFreezesEveryCollectionBeforeTheCallerCanReuseItsBuilders(){
        var bounds=new Bounds(0,0,100,20);
        var node=new Node("field",Role.TEXT_FIELD,"Field","seed",bounds,true,false,false,"",0);
        var visual=Visual.of(Kind.INPUT);Runnable action=()->{};Runnable commit=()->{};Consumer<String> change=ignored->{};
        var region=new StudioScrollState.Region(bounds,bounds,bounds,0,10,0,ignored->{});
        var target=new StudioFocusNavigation.Target(new StudioFocusNavigation.Region("body",bounds,0,10,0,ignored->{}),bounds,bounds);
        var nodes=new ArrayList<>(List.of(node));var visuals=new HashMap<>(Map.of(node.id(),visual));
        var actions=new HashMap<>(Map.of(node.id(),action));var changes=new HashMap<>(Map.of(node.id(),change));
        var blur=new HashMap<>(Map.of(node.id(),commit));var groups=new HashMap<>(Map.of(node.id(),"row/"));
        var details=new HashMap<>(Map.of(node.id(),"Help"));var scrolls=new ArrayList<>(List.of(region));
        var targets=new HashMap<>(Map.of(node.id(),target));
        var snapshot=new StudioFrame.Snapshot(nodes,visuals,actions,changes,blur,groups,details,scrolls,targets);
        nodes.clear();visuals.clear();actions.clear();changes.clear();blur.clear();groups.clear();details.clear();scrolls.clear();targets.clear();

        assertEquals(List.of(node),snapshot.nodes());assertEquals(Map.of(node.id(),visual),snapshot.visuals());
        assertSame(action,snapshot.actions().get(node.id()));assertSame(change,snapshot.changes().get(node.id()));
        assertSame(commit,snapshot.blur().get(node.id()));assertEquals(Map.of(node.id(),"row/"),snapshot.groups());
        assertEquals(Map.of(node.id(),"Help"),snapshot.details());assertEquals(List.of(region),snapshot.scrolls());
        assertEquals(Map.of(node.id(),target),snapshot.focusTargets());
        assertThrows(UnsupportedOperationException.class,()->snapshot.nodes().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.visuals().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.actions().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.changes().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.blur().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.groups().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.details().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.scrolls().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.focusTargets().clear());
    }

    @Test void legacyConstructorAlsoOwnsItsCollectionsAndHasNoRevealTargets(){
        var node=new Node("button",Role.BUTTON,"Button","",new Bounds(0,0,40,20),true,false,false,"",0);
        var nodes=new ArrayList<>(List.of(node));Runnable action=()->{};var actions=new HashMap<>(Map.of(node.id(),action));
        var snapshot=new StudioFrame.Snapshot(nodes,Map.of(),actions,Map.of(),Map.of(),Map.of(),Map.of(),List.of());
        nodes.clear();actions.clear();
        assertEquals(List.of(node),snapshot.nodes());assertSame(action,snapshot.actions().get(node.id()));
        assertTrue(snapshot.focusTargets().isEmpty());assertThrows(UnsupportedOperationException.class,()->snapshot.actions().clear());
    }

    @Test void malformedMetadataIsRejectedAtSnapshotConstruction(){
        var changes=new HashMap<String,Consumer<String>>();changes.put("field",null);
        assertThrows(NullPointerException.class,()->new StudioFrame.Snapshot(List.of(),Map.of(),Map.of(),changes,Map.of(),Map.of(),Map.of(),List.of(),Map.of()));
        var nodes=new ArrayList<Node>();nodes.add(null);
        assertThrows(NullPointerException.class,()->new StudioFrame.Snapshot(nodes,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()));
    }
}
