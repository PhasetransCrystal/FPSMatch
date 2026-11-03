package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.StudioDocumentTabs.Key;

class StudioDocumentTabsTest {
    @Test void sharedOwnerViewsCloseIndependentlyAndCurrentFallsBackToTheLeft(){
        UUID owner=UUID.randomUUID();var scene=Key.scene(owner);var form=Key.form(owner,"rules");var grid=Key.grid(owner,"shop-a");
        var tabs=new StudioDocumentTabs();tabs.open(scene);tabs.open(form);tabs.open(grid);
        assertEquals(List.of(scene,form,grid),tabs.keys());assertEquals(Optional.of(owner),tabs.activeOwner());
        assertTrue(tabs.close(tabs.requestClose(form)));assertEquals(Optional.of(grid),tabs.active());
        assertEquals(List.of(scene,grid),tabs.keys());assertTrue(tabs.close(tabs.requestClose(grid)));
        assertEquals(Optional.of(scene),tabs.active());assertTrue(tabs.close(tabs.requestClose(scene)));
        assertTrue(tabs.keys().isEmpty());assertTrue(tabs.active().isEmpty());assertTrue(tabs.activeOwner().isEmpty());
        tabs.retain(key->true);assertTrue(tabs.keys().isEmpty(),"A render-time availability check must not reopen closed views");
    }
    @Test void closingFirstCurrentViewChoosesItsRightNeighborAndResourcesHaveNoSaveOwner(){
        var first=Key.scene(UUID.randomUUID());var second=Key.scene(UUID.randomUUID());var resources=Key.resources();
        var tabs=new StudioDocumentTabs();tabs.open(first);tabs.open(second);tabs.open(resources);tabs.open(first);
        tabs.close(tabs.requestClose(first));assertEquals(Optional.of(second),tabs.active());
        tabs.open(resources);assertTrue(tabs.activeOwner().isEmpty(),"Resources must not save the previously active map");
        tabs.close(tabs.requestClose(resources));assertEquals(Optional.of(second.document()),tabs.activeOwner());
    }
    @Test void reopeningAnExistingViewDoesNotDuplicateOrInvalidateItsCloseRequest(){
        var first=Key.scene(UUID.randomUUID());var second=Key.form(first.document(),"rules");var tabs=new StudioDocumentTabs();
        tabs.open(first);var pending=tabs.requestClose(first);tabs.open(second);tabs.open(first);
        assertEquals(List.of(first,second),tabs.keys());assertTrue(tabs.matches(pending));
        assertEquals(Optional.of(first),tabs.active());
    }
    @Test void lateSaveContinuationCannotCloseAReopenedViewOrANewProjectSession(){
        var key=Key.form(UUID.randomUUID(),"rules");var tabs=new StudioDocumentTabs();tabs.open(key);var pending=tabs.requestClose(key);
        tabs.close(pending);tabs.open(key);assertFalse(tabs.close(pending));assertEquals(List.of(key),tabs.keys());
        var otherSession=tabs.requestClose(key);tabs.clear();tabs.open(key);
        assertFalse(tabs.matches(otherSession));assertFalse(tabs.close(otherSession));assertEquals(Optional.of(key),tabs.active());
    }
    @Test void draggingUsesAThresholdAndReordersWithoutChangingTheSelectedView(){
        var first=Key.scene(UUID.randomUUID());var second=Key.form(first.document(),"rules");var third=Key.grid(first.document(),"shop");
        var tabs=new StudioDocumentTabs();tabs.open(first);tabs.open(second);tabs.open(third);
        tabs.beginDrag(first,10);assertEquals(Optional.of(first),tabs.endDrag(first,18));assertEquals(List.of(first,second,third),tabs.keys());
        tabs.beginDrag(first,10);tabs.drag(30);assertTrue(tabs.endDrag(third,30).isEmpty());
        assertEquals(List.of(second,third,first),tabs.keys());assertEquals(Optional.of(third),tabs.active());
        tabs.beginDrag(first,30);tabs.drag(10);tabs.endDrag(second,10);assertEquals(List.of(first,second,third),tabs.keys());
        assertEquals(Optional.of(third),tabs.active());
    }
    @Test void cancelledOutsideAndReturnedDragsNeverActivateOrMoveAnything(){
        var first=Key.scene(UUID.randomUUID());var second=Key.scene(UUID.randomUUID());var tabs=new StudioDocumentTabs();tabs.open(first);tabs.open(second);
        tabs.beginDrag(first,0);tabs.drag(20);assertTrue(tabs.endDrag(null,20).isEmpty());assertEquals(List.of(first,second),tabs.keys());
        tabs.beginDrag(first,0);tabs.drag(20);tabs.cancelDrag();assertTrue(tabs.endDrag(second,20).isEmpty());
        tabs.beginDrag(first,0);tabs.drag(20);assertTrue(tabs.endDrag(first,0).isEmpty(),"Crossing the threshold remains a drag after moving back");
        tabs.beginDrag(first,0);assertTrue(tabs.endDrag(second,1).isEmpty(),"Release over another activation control is not a click");
        assertEquals(Optional.of(second),tabs.active());assertEquals(List.of(first,second),tabs.keys());
    }
    @Test void unavailableOwnedViewIsRemovedWithoutClosingItsOtherViews(){
        var owner=UUID.randomUUID();var scene=Key.scene(owner);var deletedShop=Key.grid(owner,"deleted-shop");var otherShop=Key.grid(owner,"remaining-shop");
        var tabs=new StudioDocumentTabs();tabs.open(scene);tabs.open(deletedShop);tabs.open(otherShop);tabs.beginDrag(deletedShop,0);
        var stale=tabs.requestClose(deletedShop);tabs.retain(key->!key.equals(deletedShop));
        assertFalse(tabs.dragging());assertFalse(tabs.matches(stale));assertEquals(List.of(scene,otherShop),tabs.keys());assertEquals(Optional.of(otherShop),tabs.active());
    }
    @Test void contributorTargetsCannotCollideWithAnotherViewsSubcontrols(){
        UUID owner=UUID.randomUUID();
        for(var first:List.of(Key.form(owner,"a"),Key.grid(owner,"a"))){
            var all=new HashSet<String>();
            for(String target:List.of("a","a/close","a/surface","a/dirty","1:a")){
                var key=new Key(first.kind(),owner,target);
                for(String suffix:List.of("","/close","/surface","/dirty","/drop"))assertTrue(all.add(key.controlId()+suffix),"Stable targets and component children must remain distinct");
            }
        }
    }
}
