package net.ptcrys.fpsmatch.editor.common.ui.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.ptcrys.fpsmatch.editor.api.*;
import org.junit.jupiter.api.Test;

class WorkspaceStateTest {
    private static final EditorId TEMPLATE=EditorId.parse("missing:workspace"),TYPE=EditorId.parse("missing:panel");
    private static PanelInstance panel(){return new PanelInstance(UUID.randomUUID(),TYPE,PanelBinding.pinned(UUID.randomUUID()),Map.of("dock.group","leftBottom","raw","unfinished 12e"));}
    private static WorkspaceInstance workspace(PanelInstance panel){return new WorkspaceInstance(UUID.randomUUID(),TEMPLATE,7,"Original",true,List.of(panel));}
    @Test void restorePreservesUnavailableContributionsAndIndependentCurrentSavedLayouts(){
        var current=panel();var removed=panel();var workspace=workspace(current);var state=new WorkspaceState(List.of(workspace),workspace.id(),Map.of(workspace.id(),List.of(removed,current)));
        var manager=new WorkspaceManager(List.of());manager.restore(state);assertEquals(state,manager.snapshot());
        manager.movePanel(workspace.id(),current.id(),"right");manager.rename(workspace.id(),"Renamed");manager.restoreLayout(workspace.id());
        assertEquals(List.of(removed,current),manager.workspaces().get(0).panels());assertEquals("Renamed",manager.workspaces().get(0).name());
        assertEquals(TEMPLATE,manager.workspaces().get(0).templateId());assertEquals(7,manager.workspaces().get(0).templateVersion());assertEquals(Optional.of(workspace.id()),manager.activeWorkspace());
        assertThrows(IllegalArgumentException.class,()->manager.create(TEMPLATE,"Cannot instantiate missing template"));
    }
    @Test void snapshotCapturesBothBaselinesAndRestorationDoesNotApplyUpdatedTemplates(){
        var template=new WorkspaceTemplate(TEMPLATE,1,new TextContent.Literal("Template"),List.of(new PanelSpec(TYPE,true)));
        var manager=new WorkspaceManager(List.of(template));var workspace=manager.create(TEMPLATE,"A");var original=workspace.panels().get(0);manager.saveLayout(workspace.id());manager.removePanel(workspace.id(),original.id());var added=manager.addPanel(workspace.id(),TYPE);var state=manager.snapshot();
        var newer=new WorkspaceManager(List.of(new WorkspaceTemplate(TEMPLATE,9,new TextContent.Literal("New"),List.of())));newer.restore(state);
        assertEquals(state,newer.snapshot());assertEquals(List.of(added),newer.workspaces().get(0).panels());newer.restoreLayout(workspace.id());assertEquals(List.of(original),newer.workspaces().get(0).panels());
        manager.rename(workspace.id(),"Changed");assertEquals("A",state.workspaces().get(0).name());
    }
    @Test void stateDefensivelyCopiesAllOuterCollections(){
        var panel=panel();var workspace=workspace(panel);var workspaces=new ArrayList<>(List.of(workspace));var baseline=new ArrayList<>(List.of(panel));var saved=new HashMap<UUID,List<PanelInstance>>();saved.put(workspace.id(),baseline);
        var state=new WorkspaceState(workspaces,workspace.id(),saved);workspaces.clear();baseline.clear();saved.clear();
        assertEquals(List.of(workspace),state.workspaces());assertEquals(List.of(panel),state.savedLayouts().get(workspace.id()));
        assertThrows(UnsupportedOperationException.class,()->state.workspaces().clear());assertThrows(UnsupportedOperationException.class,()->state.savedLayouts().clear());assertThrows(UnsupportedOperationException.class,()->state.savedLayouts().get(workspace.id()).clear());
    }
    @Test void invalidWholeRestoreLeavesTheExistingEnvironmentUntouched(){
        var panel=panel();var workspace=workspace(panel);var state=new WorkspaceState(List.of(workspace),workspace.id(),Map.of(workspace.id(),List.of(panel)));var manager=new WorkspaceManager(List.of());manager.restore(state);
        assertThrows(NullPointerException.class,()->manager.restore(null));
        assertThrows(IllegalArgumentException.class,()->manager.restore(new WorkspaceState(List.of(workspace,workspace),workspace.id(),state.savedLayouts())));
        assertThrows(IllegalArgumentException.class,()->manager.restore(new WorkspaceState(List.of(workspace),UUID.randomUUID(),state.savedLayouts())));
        assertThrows(NullPointerException.class,()->manager.restore(new WorkspaceState(List.of(workspace),workspace.id(),Map.of())));
        assertEquals(state,manager.snapshot());
    }
    @Test void savedOnlyPanelIdentityCannotLeakIntoAnotherWorkspaceOrChangeType(){
        var panel=panel();var first=workspace(panel);var second=new WorkspaceInstance(UUID.randomUUID(),TEMPLATE,1,"B",false,List.of());
        assertThrows(IllegalArgumentException.class,()->new WorkspaceState(List.of(first,second),first.id(),Map.of(first.id(),List.of(),second.id(),List.of(panel))));
        var changed=new PanelInstance(panel.id(),EditorId.parse("other:panel"),panel.binding(),panel.viewState());
        assertThrows(IllegalArgumentException.class,()->new WorkspaceState(List.of(first),first.id(),Map.of(first.id(),List.of(changed))));
        assertThrows(IllegalArgumentException.class,()->new WorkspaceState(List.of(second),second.id(),Map.of(second.id(),List.of())));
        assertEquals(new WorkspaceState(List.of(),null,Map.of()),new WorkspaceManager(List.of()).snapshot());
    }
}
