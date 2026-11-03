package net.ptcrys.fpsmatch.editor.common.ui.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class ControlTreeTest {
    @Test void compoundPointerOwnersRespectLaterSurfacesAndModalPublication() {
        var tree = new ControlTree();
        var track = new Node("track", Role.PANEL, "", "", new Bounds(90, 0, 10, 100),
                true, false, false, "", 0, new Bounds(0, 0, 100, 100), false);
        var cover = node("cover", Role.PANEL, new Bounds(0, 0, 50, 100), 0);
        tree.publish(List.of(track, cover));
        assertFalse(tree.pointerExposed("track", 20, 20));
        assertTrue(tree.pointerExposed("track", 95, 20));
        assertFalse(tree.pointerExposed("removed", 95, 20));
        tree.publish(List.of(track, node("modal", Role.PANEL, new Bounds(0, 0, 5, 5), 1)));
        assertFalse(tree.pointerExposed("track", 95, 20), "A modal scopes even pointers outside its bounds");
    }

    @Test void laterOpaquePanelBlocksCoveredTargetsOnlyInsideItsClip() {
        var tree = new ControlTree();
        Node button = node("button", Role.BUTTON, new Bounds(0, 0, 80, 20), 0);
        Node cover = new Node("cover", Role.PANEL, "", "", new Bounds(0, 0, 80, 20),
                true, false, false, "", 0, new Bounds(0, 0, 40, 20));
        tree.publish(List.of(button, cover));

        assertTrue(tree.hit(5, 5).isEmpty(), "An opaque surface must stop covered callbacks");
        assertEquals(button, tree.hit(45, 5).orElseThrow(), "Clipped-out paint must not block input");
        tree.cycleFocus(false);
        assertEquals(button, tree.focused().orElseThrow(), "A pointer barrier does not add a keyboard target");
    }

    @Test void decorationsExplicitlyPassPointerWhileLaterChildrenRemainInteractive() {
        var tree = new ControlTree();
        Node button = node("button", Role.BUTTON, new Bounds(0, 0, 80, 20), 0);
        Node decoration = new Node("decoration", Role.PANEL, "", "", new Bounds(0, 0, 80, 20),
                true, false, false, "", 0, new Bounds(0, 0, 80, 20), false);
        tree.publish(List.of(button, decoration));
        assertEquals(button, tree.hit(5, 5).orElseThrow());

        Node panel = node("container", Role.PANEL, new Bounds(0, 0, 80, 20), 0);
        tree.publish(List.of(panel, button, decoration));
        assertEquals(button, tree.hit(5, 5).orElseThrow(), "A container must not hide its later children");
    }

    @Test void topModalBlocksLowerHitTargetsAndClearsTheirExistingFocus() {
        var tree = new ControlTree();
        Node editor = node("editor", Role.TEXT_FIELD, new Bounds(0, 0, 100, 100), 0);
        Node modal = node("modal/action", Role.BUTTON, new Bounds(20, 20, 20, 20), 2);
        tree.publish(List.of(editor));
        tree.focus(editor.id());
        tree.publish(List.of(editor, modal));

        assertTrue(tree.focused().isEmpty());
        assertTrue(tree.hit(5, 5).isEmpty(), "A modal must consume the area outside its own controls");
        assertEquals(modal, tree.hit(25, 25).orElseThrow());
        tree.cycleFocus(false);
        assertEquals(modal, tree.focused().orElseThrow());
    }

    @Test void directFocusCannotEscapeThePublishedModalLayer() {
        var tree = new ControlTree();
        Node editor = node("editor", Role.TEXT_FIELD, new Bounds(0, 0, 100, 100), 0);
        Node modal = node("modal/action", Role.BUTTON, new Bounds(20, 20, 20, 20), 1);
        tree.publish(List.of(editor, modal));
        tree.focus(modal.id());
        tree.focus(editor.id());

        assertFalse(tree.focused().map(n -> n.id().equals(editor.id())).orElse(false),
                "Restoring a remembered lower-layer ID must not bypass the modal focus scope");
    }

    @Test void modalWithoutFocusableChildrenStillBlocksBackgroundInput() {
        var tree = new ControlTree();
        tree.publish(List.of(
                node("background", Role.BUTTON, new Bounds(0, 0, 100, 100), 0),
                node("modal", Role.PANEL, new Bounds(10, 10, 30, 30), 1),
                node("modal/message", Role.LABEL, new Bounds(15, 15, 20, 10), 1)));

        assertTrue(tree.hit(1, 1).isEmpty());
        assertTrue(tree.hit(20, 20).isEmpty());
        tree.cycleFocus(false);
        assertTrue(tree.focused().isEmpty());
    }

    @Test void hitBoundsIncludeLeftTopAndExcludeRightBottomAndUseLastPaintedTarget() {
        var tree = new ControlTree();
        Node left = node("left", Role.BUTTON, new Bounds(10, 20, 30, 40), 0);
        Node right = node("right", Role.BUTTON, new Bounds(40, 20, 30, 40), 0);
        tree.publish(List.of(left, right));

        assertEquals(left, tree.hit(10, 20).orElseThrow());
        assertEquals(left, tree.hit(39.999, 59.999).orElseThrow());
        assertEquals(right, tree.hit(40, 20).orElseThrow());
        assertTrue(tree.hit(70, 20).isEmpty());
        assertTrue(tree.hit(10, 60).isEmpty());
        assertTrue(tree.hit(9.999, 20).isEmpty());
        assertTrue(tree.hit(10, 19.999).isEmpty());
        Node cover = node("cover", Role.BUTTON, new Bounds(10, 20, 30, 40), 0);
        tree.publish(List.of(left, cover));
        assertEquals(cover, tree.hit(10, 20).orElseThrow());
    }

    @Test void sameSemanticIdSurvivesRepublishResizeAndTemporaryHidingWithEditHistory() {
        var tree = new ControlTree();
        var state = tree.text("name", "原文😀");
        tree.publish(List.of(node("name", Role.TEXT_FIELD, new Bounds(10, 20, 100, 20), 0)));
        tree.focus("name");
        state.select(2, false);
        state.end(true);
        assertTrue(state.insert("草稿"));
        state.select(3, false);
        state.select(1, true);
        String raw = state.text();
        int caret = state.caret();
        int anchor = state.anchor();

        tree.publish(List.of(node("name", Role.TEXT_FIELD, new Bounds(0, 0, 45, 20), 0)));
        assertSame(state, tree.text("name", raw));
        assertEquals(raw, state.text());
        assertEquals(caret, state.caret());
        assertEquals(anchor, state.anchor());
        assertEquals("name", tree.focused().orElseThrow().id());
        tree.publish(List.of());
        assertTrue(tree.focused().isEmpty());
        tree.publish(List.of(node("name", Role.TEXT_FIELD, new Bounds(30, 40, 140, 20), 0)));
        assertSame(state, tree.text("name", raw));
        assertEquals(caret, state.caret());
        assertEquals(anchor, state.anchor());
        assertTrue(state.undo(), "Republishing the same model value must retain local undo");
        assertEquals("原文😀", state.text());
        assertEquals("😀", state.selected());
        assertTrue(state.redo());
        assertEquals(raw, state.text());
        assertEquals(caret, state.caret());
        assertEquals(anchor, state.anchor());
    }

    @Test void controlsHiddenByZeroBoundsCannotAcquireFocusAndLoseExistingFocus() {
        var tree = new ControlTree();
        Node visible = node("visible", Role.BUTTON, new Bounds(0, 0, 30, 20), 0);
        Node zeroWidth = node("hidden/width", Role.BUTTON, new Bounds(0, 0, 0, 20), 0);
        Node zeroHeight = node("hidden/height", Role.TEXT_FIELD, new Bounds(0, 0, 30, 0), 0);
        tree.publish(List.of(zeroWidth, visible, zeroHeight));
        tree.cycleFocus(false);
        assertEquals(visible, tree.focused().orElseThrow());
        tree.cycleFocus(false);
        assertEquals(visible, tree.focused().orElseThrow());
        tree.cycleFocus(true);
        assertEquals(visible, tree.focused().orElseThrow());

        tree.publish(List.of(node("visible", Role.BUTTON, new Bounds(0, 0, 0, 20), 0)));
        assertTrue(tree.focused().isEmpty(), "Resize must release focus when its target becomes hidden");
        tree.cycleFocus(false);
        assertTrue(tree.focused().isEmpty());
    }

    @Test void tabCyclesOnlyTopLayerFocusableRolesInPublishedOrder() {
        var tree = new ControlTree();
        Node first = node("first", Role.BUTTON, new Bounds(0, 0, 20, 20), 1);
        Node second = node("second", Role.TEXT_FIELD, new Bounds(20, 0, 20, 20), 1);
        Node third = node("third", Role.TAB, new Bounds(40, 0, 20, 20), 1);
        tree.publish(List.of(
                node("background", Role.BUTTON, new Bounds(0, 30, 20, 20), 0),
                first, node("label", Role.LABEL, new Bounds(0, 60, 20, 20), 1),
                second, node("panel", Role.PANEL, new Bounds(0, 80, 20, 20), 1), third));

        tree.cycleFocus(false);
        assertEquals(first, tree.focused().orElseThrow());
        tree.cycleFocus(false);
        assertEquals(second, tree.focused().orElseThrow());
        tree.cycleFocus(false);
        assertEquals(third, tree.focused().orElseThrow());
        tree.cycleFocus(false);
        assertEquals(first, tree.focused().orElseThrow());
        tree.cycleFocus(true);
        assertEquals(third, tree.focused().orElseThrow());
        tree.cycleFocus(true);
        assertEquals(second, tree.focused().orElseThrow());
    }

    @Test void backwardsTabEntersTheLastTargetWhenThereIsNoCurrentFocus() {
        var tree = new ControlTree();
        Node first = node("first", Role.BUTTON, new Bounds(0, 0, 20, 20), 0);
        Node second = node("second", Role.BUTTON, new Bounds(20, 0, 20, 20), 0);
        Node last = node("last", Role.BUTTON, new Bounds(40, 0, 20, 20), 0);
        tree.publish(List.of(first, second, last));
        tree.cycleFocus(true);
        assertEquals(last, tree.focused().orElseThrow());
        tree.focus(null);
        tree.cycleFocus(true);
        assertEquals(last, tree.focused().orElseThrow());
    }

    @Test void duplicateSemanticIdsRejectTheWholeFrameWithoutChangingFocusOrText() {
        var tree = new ControlTree();
        Node original = node("original", Role.TEXT_FIELD, new Bounds(0, 0, 60, 20), 0);
        var submitted = new ArrayList<>(List.of(original));
        tree.publish(submitted);
        submitted.clear();
        tree.focus(original.id());
        var state = tree.text(original.id(), "seed");
        assertTrue(state.insert("!"));
        state.select(2, false);
        state.select(4, true);
        List<Node> snapshot = tree.snapshot();

        assertThrows(IllegalArgumentException.class, () -> tree.publish(List.of(
                node("duplicate", Role.BUTTON, new Bounds(0, 0, 20, 20), 1),
                node("duplicate", Role.TEXT_FIELD, new Bounds(20, 0, 20, 20), 1))));
        assertSame(snapshot, tree.snapshot());
        assertEquals(List.of(original), tree.snapshot());
        assertEquals(original, tree.focused().orElseThrow());
        assertEquals(original, tree.hit(1, 1).orElseThrow());
        assertEquals(0, tree.topLayer());
        assertSame(state, tree.text(original.id(), "seed!"));
        assertEquals(4, state.caret());
        assertEquals(2, state.anchor());
        assertTrue(state.undo());
        assertEquals("seed", state.text());
        assertThrows(UnsupportedOperationException.class, () -> tree.snapshot().clear());
    }

    @Test void lateBasePanelsCannotPaintOverDrawerActionsInThePublishedDrawingFrame() {
        var tree=new ControlTree();var bounds=new Bounds(0,0,80,20);
        Node drawer=node("drawer",Role.PANEL,bounds,1),action=node("drawer-next",Role.BUTTON,bounds,1);
        Node problems=node("late-problems",Role.PANEL,bounds,0),base=node("base",Role.LABEL,bounds,0);
        tree.publish(List.of(drawer,action,problems,base));
        assertEquals(List.of(problems,base,drawer,action),tree.snapshot(),"one stable frame determines drawing and hit order");
        assertEquals(action,tree.hit(5,5).orElseThrow());
        tree.cycleFocus(false);assertEquals(action,tree.focused().orElseThrow());
    }

    @Test void publicationSharesImmutableGeometryAndStableIndexedOrders() {
        var tree=new ControlTree();
        var clip=new Bounds(10,10,20,20);
        Node background=node("background",Role.PANEL,new Bounds(0,0,100,100),-2);
        Node hidden=new Node("hidden",Role.BUTTON,"","",new Bounds(10,40,20,20),true,false,false,"",3,clip);
        Node disabled=new Node("disabled",Role.BUTTON,"","",new Bounds(0,0,20,20),false,false,false,"Unavailable",3,clip);
        Node label=node("label",Role.LABEL,new Bounds(0,0,10,10),3);
        tree.publish(List.of(hidden,disabled,background,label));
        assertEquals(List.of(background,hidden,disabled,label),tree.snapshot());
        assertEquals(List.of(background,disabled,label),tree.visibleGeometry().stream().map(ControlTree.Geometry::node).toList());
        var geometry=tree.geometry(disabled.id()).orElseThrow();
        assertSame(geometry,tree.visibleGeometry().get(1));
        assertEquals(new Bounds(10,10,10,10),geometry.visibleBounds());
        assertEquals(2,geometry.paintIndex());
        assertTrue(geometry.focusable(),"Disabled controls retain inspectable keyboard focus");
        assertEquals(List.of(disabled),tree.focusOrder());
        assertEquals(List.of(hidden,disabled),tree.interactiveOrder(),"Clipped controls remain eligible for reveal");
        assertSame(disabled,tree.node(disabled.id()).orElseThrow());
        assertTrue(tree.node(null).isEmpty());
        assertTrue(tree.geometry("missing").isEmpty());
        tree.focus(disabled.id());assertSame(disabled,tree.focused().orElseThrow());
        assertSame(disabled,tree.hit(15,15).orElseThrow());
        assertThrows(UnsupportedOperationException.class,()->tree.visibleGeometry().clear());
        assertThrows(UnsupportedOperationException.class,()->tree.focusOrder().clear());
        assertThrows(UnsupportedOperationException.class,()->tree.interactiveOrder().clear());
    }

    @Test void invisibleTopLayerStillOwnsTheModalScope() {
        var tree=new ControlTree();
        Node background=node("background",Role.BUTTON,new Bounds(0,0,100,100),-2);
        Node modal=node("modal",Role.PANEL,new Bounds(0,0,0,0),-1);
        tree.publish(List.of(background));tree.focus(background.id());
        tree.publish(List.of(modal,background));
        assertEquals(-1,tree.topLayer());
        assertEquals(List.of(background),tree.visibleGeometry().stream().map(ControlTree.Geometry::node).toList());
        assertTrue(tree.hit(1,1).isEmpty());
        assertTrue(tree.focused().isEmpty());
        assertFalse(tree.pointerExposed(background.id(),1,1));
        tree.focus(background.id());assertTrue(tree.focused().isEmpty());
        tree.cycleFocus(true);assertTrue(tree.focused().isEmpty());
        assertTrue(tree.focusOrder().isEmpty());assertTrue(tree.interactiveOrder().isEmpty());
    }

    @Test void rejectedPublicationRetainsAllPublishedIndexesAndGeometry() {
        var tree=new ControlTree();
        Node first=node("first",Role.BUTTON,new Bounds(0,0,20,20),1);
        Node second=node("second",Role.BUTTON,new Bounds(30,0,20,20),1);
        tree.publish(List.of(first,second));tree.focus(second.id());
        var snapshot=tree.snapshot();var visible=tree.visibleGeometry();var focus=tree.focusOrder();var interactive=tree.interactiveOrder();
        var geometry=tree.geometry(first.id()).orElseThrow();
        assertThrows(IllegalArgumentException.class,()->tree.publish(List.of(first,node("first",Role.PANEL,new Bounds(0,0,100,100),9))));
        assertSame(snapshot,tree.snapshot());assertSame(visible,tree.visibleGeometry());
        assertSame(focus,tree.focusOrder());assertSame(interactive,tree.interactiveOrder());
        assertSame(geometry,tree.geometry(first.id()).orElseThrow());
        assertSame(second,tree.focused().orElseThrow());assertEquals(1,tree.topLayer());
        tree.cycleFocus(true);assertSame(first,tree.focused().orElseThrow());
    }

    @Test void largeFrameQueriesReuseIndexesAndOnlyVisitRelevantPointerCandidates() {
        var tree=new ControlTree();var nodes=new ArrayList<Node>();
        for(int i=0;i<12_000;i++)nodes.add(node("background/"+i,Role.BUTTON,new Bounds(0,0,20,20),0));
        Node first=node("first",Role.BUTTON,new Bounds(0,0,20,20),1);
        Node second=node("second",Role.BUTTON,new Bounds(30,0,20,20),1);
        nodes.add(first);nodes.add(second);
        for(int i=0;i<3_000;i++)nodes.add(node("label/"+i,Role.LABEL,new Bounds(0,0,100,100),1));
        tree.publish(nodes);var before=tree.work();var geometry=tree.geometry(first.id()).orElseThrow();
        assertEquals(nodes.size(),before.geometryBuilds());
        for(int i=0;i<1_000;i++){
            assertEquals(1,tree.topLayer());tree.focus(first.id());assertSame(first,tree.focused().orElseThrow());
            tree.cycleFocus(false);assertSame(second,tree.focused().orElseThrow());
            tree.cycleFocus(true);assertSame(first,tree.focused().orElseThrow());
            assertSame(first,tree.node(first.id()).orElseThrow());
            assertSame(geometry,tree.geometry(first.id()).orElseThrow());
            assertSame(second,tree.hit(31,1).orElseThrow());
            assertTrue(tree.pointerExposed(first.id(),1,1));
        }
        var after=tree.work();
        assertEquals(before.geometryBuilds(),after.geometryBuilds(),"Queries must never rebuild clipped geometry");
        assertEquals(4_001,after.indexedLookups()-before.indexedLookups());
        assertEquals(2_000,after.pointerCandidatesVisited()-before.pointerCandidatesVisited(),"Each pointer query visits one top-layer control, no labels or lower layers");
    }

    private static Node node(String id, Role role, Bounds bounds, int layer) {
        return new Node(id, role, id, "", bounds, true, false, false, "", layer);
    }
}
