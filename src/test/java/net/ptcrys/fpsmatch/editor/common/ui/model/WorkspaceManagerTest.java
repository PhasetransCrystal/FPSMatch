package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerTest {
    private static final EditorId SCENE = EditorId.parse("sample:scene");
    private static final EditorId LAYERS = EditorId.parse("sample:layers");
    private static final EditorId NODE = EditorId.parse("sample:node_graph");

    private static WorkspaceTemplate template(EditorId id, PanelSpec... panels) {
        return new WorkspaceTemplate(id, 1, new TextContent.Literal("t"), List.of(panels));
    }

    private static WorkspaceManager manager(WorkspaceTemplate... templates) {
        return new WorkspaceManager(List.of(templates));
    }

    private static WorkspaceManager sceneManager() {
        return manager(template(EditorId.parse("sample:basic"),
                new PanelSpec(SCENE, false), new PanelSpec(LAYERS, false)));
    }

    private static PanelInstance panel(WorkspaceInstance workspace, EditorId type) {
        return workspace.panels().stream().filter(p -> p.type().equals(type)).findFirst().orElseThrow();
    }

    @Test void panelCopiesPreservePinnedStateAndGroupWithFreshIdentityAndIndependentEdits() {
        EditorId template = EditorId.parse("sample:panels");
        var manager = manager(template(template, new PanelSpec(NODE, true), new PanelSpec(SCENE, false)));
        var workspace = manager.create(template, "A");
        var other = manager.create(template, "B");
        var origin = panel(workspace, NODE); UUID document = UUID.randomUUID();
        manager.bind(workspace.id(), origin.id(), PanelBinding.pinned(document));
        manager.setPanelState(workspace.id(), origin.id(), Map.of("scroll/doc/node", "7", "search/doc", "point"));
        manager.movePanel(workspace.id(), origin.id(), "right");
        var before = manager.workspaces().get(0); var otherBefore = manager.workspaces().get(1);
        var copy = manager.duplicatePanel(workspace.id(), origin.id());
        assertNotEquals(origin.id(), copy.id()); assertEquals(NODE, copy.type());
        assertEquals(PanelBinding.pinned(document), copy.binding());
        assertEquals(before.panels().stream().filter(p -> p.id().equals(origin.id())).findFirst().orElseThrow().viewState(), copy.viewState());
        assertEquals("right", copy.viewState().get("dock.group"));
        manager.movePanel(workspace.id(), copy.id(), "bottom");
        var moved = manager.workspaces().get(0).panels().stream().filter(p -> p.id().equals(copy.id())).findFirst().orElseThrow();
        assertEquals("bottom", moved.viewState().get("dock.group"));
        assertEquals("7", moved.viewState().get("scroll/doc/node"));
        assertEquals("point", moved.viewState().get("search/doc"));
        assertEquals(before.panels(), manager.workspaces().get(0).panels().subList(0, before.panels().size()));
        assertEquals(otherBefore, manager.workspaces().get(1));
        assertEquals(Optional.of(workspace.id()), manager.activeWorkspace());
        manager.removePanel(workspace.id(), copy.id());
        assertEquals(before, manager.workspaces().get(0));
        var added = manager.addPanel(other.id(), NODE);
        assertEquals(PanelBinding.followActive(), added.binding()); assertTrue(added.viewState().isEmpty());
        assertNotEquals(origin.id(), added.id());
        assertEquals(before, manager.workspaces().get(0));
    }

    @Test void explicitDockZonesAppendOnlyTheMovedPanelAndKeepLegacyLayoutValuesReadable() {
        EditorId template = EditorId.parse("sample:docking");
        var manager = manager(template(template, new PanelSpec(NODE, true), new PanelSpec(LAYERS, false), new PanelSpec(SCENE, false)));
        var workspace = manager.create(template, "A");
        var other = manager.create(template, "B");
        var origin = panel(workspace, NODE);
        var siblings = workspace.panels().stream().filter(p -> !p.id().equals(origin.id())).toList();
        var binding = PanelBinding.pinned(UUID.randomUUID());
        manager.bind(workspace.id(), origin.id(), binding);
        manager.setPanelState(workspace.id(), origin.id(), Map.of("search/doc", "raw stays", "scroll/doc/node", "21"));
        for (String group : List.of("leftTop", "leftBottom", "right", "bottom", "left", "center")) {
            manager.movePanel(workspace.id(), origin.id(), group);
            var changed = manager.workspaces().get(0);
            assertEquals(siblings, changed.panels().subList(0, siblings.size()));
            var moved = changed.panels().get(siblings.size());
            assertEquals(origin.id(), moved.id()); assertEquals(origin.type(), moved.type());
            assertEquals(binding, moved.binding());
            assertEquals(Map.of("dock.group", group, "search/doc", "raw stays", "scroll/doc/node", "21"), moved.viewState());
            manager.movePanel(workspace.id(), origin.id(), group);
            assertEquals(changed, manager.workspaces().get(0), "repeated drop does not duplicate or alter the panel");
            assertEquals(other, manager.workspaces().get(1));
            assertEquals(Optional.of(workspace.id()), manager.activeWorkspace());
        }
        manager.movePanel(workspace.id(), origin.id(), "leftBottom");
        manager.saveLayout(workspace.id());
        var saved = manager.workspaces().get(0).panels();
        manager.movePanel(workspace.id(), origin.id(), "leftTop");
        manager.restoreLayout(workspace.id());
        assertEquals(saved, manager.workspaces().get(0).panels());
        var copy = manager.duplicate(workspace.id(), "Copy");
        assertEquals(saved.stream().map(PanelInstance::viewState).toList(), copy.panels().stream().map(PanelInstance::viewState).toList());
        assertEquals(binding, copy.panels().get(siblings.size()).binding());
        assertNotEquals(origin.id(), copy.panels().get(siblings.size()).id());
    }

    @Test void dockStateCapacityFailureDoesNotReorderPanelsOrReplaceSavedLayout() {
        var manager = sceneManager();
        var workspace = manager.create(EditorId.parse("sample:basic"), "A");
        var origin = panel(workspace, SCENE);
        Map<String, String> full = new LinkedHashMap<>();
        for (int i = 0; i < PanelInstance.MAX_STATE_ENTRIES; i++) full.put("key-" + i, "value-" + i);
        manager.setPanelState(workspace.id(), origin.id(), full);
        manager.saveLayout(workspace.id());
        var before = manager.workspaces();
        assertThrows(IllegalArgumentException.class, () -> manager.movePanel(workspace.id(), origin.id(), "leftBottom"));
        assertEquals(before, manager.workspaces());
        manager.setPanelState(workspace.id(), origin.id(), Map.of());
        manager.restoreLayout(workspace.id());
        assertEquals(before, manager.workspaces());
        assertEquals(Optional.of(workspace.id()), manager.activeWorkspace());
    }

    @Test void invalidPanelCommandsAndSingleInstanceCopiesLeaveAllWorkspaceStateUntouched() {
        var manager = sceneManager();
        var a = manager.create(EditorId.parse("sample:basic"), "A");
        var b = manager.create(EditorId.parse("sample:basic"), "B");
        var scene = panel(a, SCENE); var before = manager.workspaces(); var active = manager.activeWorkspace();
        assertThrows(IllegalArgumentException.class, () -> manager.addPanel(a.id(), NODE));
        assertThrows(IllegalStateException.class, () -> manager.addPanel(a.id(), SCENE));
        assertThrows(IllegalStateException.class, () -> manager.duplicatePanel(a.id(), scene.id()));
        assertThrows(IllegalArgumentException.class, () -> manager.movePanel(a.id(), scene.id(), "unknown"));
        assertThrows(IllegalArgumentException.class, () -> manager.removePanel(b.id(), scene.id()));
        assertThrows(IllegalArgumentException.class, () -> manager.duplicatePanel(b.id(), scene.id()));
        assertThrows(IllegalArgumentException.class, () -> manager.movePanel(b.id(), scene.id(), "left"));
        assertThrows(IllegalArgumentException.class, () -> manager.addPanel(UUID.randomUUID(), SCENE));
        assertEquals(before, manager.workspaces()); assertEquals(active, manager.activeWorkspace());
        manager.removePanel(a.id(), scene.id());
        var replacement = manager.addPanel(a.id(), SCENE);
        assertNotEquals(scene.id(), replacement.id());
        assertEquals(1, manager.workspaces().get(0).panels().stream().filter(p -> p.type().equals(SCENE)).count());
        assertEquals(b, manager.workspaces().get(1)); assertEquals(active, manager.activeWorkspace());
    }

    @Test void panelCountLimitRejectsAddAndCopyAtomicallyAndRemovalReopensCapacity() {
        EditorId template = EditorId.parse("sample:many_panels");
        var specs = new ArrayList<PanelSpec>();
        for(int index = 0; index < WorkspaceInstance.MAX_PANELS; index++) specs.add(new PanelSpec(NODE, true));
        var manager = manager(new WorkspaceTemplate(template, 1, new TextContent.Literal("Many"), specs));
        var workspace = manager.create(template, "Full"); var original = workspace.panels().get(0);
        var before = manager.workspaces();
        assertThrows(IllegalStateException.class, () -> manager.addPanel(workspace.id(), NODE));
        assertThrows(IllegalStateException.class, () -> manager.duplicatePanel(workspace.id(), original.id()));
        assertEquals(before, manager.workspaces());
        manager.removePanel(workspace.id(), original.id()); manager.addPanel(workspace.id(), NODE);
        assertEquals(WorkspaceInstance.MAX_PANELS, manager.workspaces().get(0).panels().size());
        assertEquals(Optional.of(workspace.id()), manager.activeWorkspace());
    }

    @Test void savedLayoutRestoresExactPanelEnvironmentAndCopiesHaveIndependentSavedIdentities() {
        EditorId template = EditorId.parse("sample:saved_layout");
        var manager = manager(template(template, new PanelSpec(NODE, true), new PanelSpec(SCENE, false)));
        var workspace = manager.create(template, "Original"); var original = panel(workspace, NODE);
        manager.movePanel(workspace.id(), original.id(), "left");
        manager.restoreLayout(workspace.id());
        assertEquals(workspace.panels(), manager.workspaces().get(0).panels(), "new instances restore the template baseline");
        UUID document = UUID.randomUUID();
        manager.bind(workspace.id(), original.id(), PanelBinding.pinned(document));
        manager.setPanelState(workspace.id(), original.id(), Map.of("search/doc", "saved", "scroll/doc/node", "9"));
        var added = manager.addPanel(workspace.id(), NODE);
        manager.movePanel(workspace.id(), added.id(), "bottom"); manager.saveLayout(workspace.id());
        var saved = manager.workspaces().get(0).panels();
        manager.removePanel(workspace.id(), original.id()); manager.rename(workspace.id(), "Renamed");
        manager.setVisible(workspace.id(), false);
        var copy = manager.duplicate(workspace.id(), "Copy");
        manager.restoreLayout(workspace.id());
        assertEquals(saved, manager.workspaces().get(0).panels());
        assertEquals("Renamed", manager.workspaces().get(0).name());
        assertFalse(manager.workspaces().get(0).visible()); assertTrue(manager.activeWorkspace().isEmpty());
        manager.restoreLayout(copy.id());
        var copied = manager.workspaces().get(1).panels();
        assertEquals(saved.size(), copied.size());
        for(int index = 0; index < saved.size(); index++) {
            assertEquals(saved.get(index).type(), copied.get(index).type());
            assertEquals(saved.get(index).binding(), copied.get(index).binding());
            assertEquals(saved.get(index).viewState(), copied.get(index).viewState());
            assertNotEquals(saved.get(index).id(), copied.get(index).id());
        }
        manager.removePanel(copy.id(), copied.get(0).id()); manager.saveLayout(copy.id());
        manager.restoreLayout(workspace.id()); assertEquals(saved, manager.workspaces().get(0).panels());
        var before = manager.workspaces();
        assertThrows(IllegalArgumentException.class, () -> manager.saveLayout(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> manager.restoreLayout(UUID.randomUUID()));
        assertEquals(before, manager.workspaces()); assertTrue(manager.activeWorkspace().isEmpty());
    }

    @Test void constructorRejectsDuplicateTemplateIdentity() {
        WorkspaceTemplate first = template(EditorId.parse("sample:basic"), new PanelSpec(SCENE, false));
        WorkspaceTemplate second = template(EditorId.parse("sample:basic"), new PanelSpec(LAYERS, false));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceManager(List.of(first, second)));
        assertTrue(failure.getMessage().contains("sample:basic"));
    }

    @Test void templateDuplicateTypeRuleOnlyRejectsSingleInstanceConflicts() {
        WorkspaceTemplate allMultiple = template(EditorId.parse("sample:node"),
                new PanelSpec(NODE, true), new PanelSpec(NODE, true));
        WorkspaceInstance created = manager(allMultiple).create(EditorId.parse("sample:node"), "Node");
        assertEquals(2, created.panels().size());
        assertEquals(List.of(NODE, NODE), created.panels().stream().map(PanelInstance::type).toList());
        assertNotEquals(created.panels().get(0).id(), created.panels().get(1).id());

        WorkspaceTemplate triple = template(EditorId.parse("sample:triple"),
                new PanelSpec(NODE, true), new PanelSpec(NODE, true), new PanelSpec(NODE, true));
        assertEquals(3, triple.panels().size());

        assertThrows(IllegalArgumentException.class, () -> template(EditorId.parse("sample:a"),
                new PanelSpec(NODE, true), new PanelSpec(NODE, false)));
        assertThrows(IllegalArgumentException.class, () -> template(EditorId.parse("sample:b"),
                new PanelSpec(NODE, false), new PanelSpec(NODE, true)));
        assertThrows(IllegalArgumentException.class, () -> template(EditorId.parse("sample:c"),
                new PanelSpec(NODE, false), new PanelSpec(NODE, false)));
    }

    @Test void createIsTheOnlyWayToGetATemplateInstanceAndRejectsUnknownTemplates() {
        WorkspaceManager manager = sceneManager();
        assertThrows(IllegalArgumentException.class,
                () -> manager.create(EditorId.parse("sample:missing"), "View"));
        assertTrue(manager.workspaces().isEmpty());
        assertTrue(manager.activeWorkspace().isEmpty());

        WorkspaceInstance created = manager.create(EditorId.parse("sample:basic"), "Main");
        assertEquals(EditorId.parse("sample:basic"), created.templateId());
        assertEquals(1, created.templateVersion());
        assertEquals("Main", created.name());
        assertTrue(created.visible());
        assertEquals(List.of(SCENE, LAYERS), created.panels().stream().map(PanelInstance::type).toList());
        for (PanelInstance instance : created.panels()) {
            assertEquals(PanelBinding.Mode.FOLLOW_ACTIVE, instance.binding().mode());
            assertNull(instance.binding().targetDocument());
            assertTrue(instance.viewState().isEmpty());
        }
        assertEquals(Optional.of(created.id()), manager.activeWorkspace());
        assertNotEquals(created.panels().get(0).id(), created.panels().get(1).id());
    }

    @Test void createRejectsIllegalNamesWithoutTouchingAnyState() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance existing = manager.create(EditorId.parse("sample:basic"), "Main");
        List<WorkspaceInstance> before = manager.workspaces();
        Optional<UUID> activeBefore = manager.activeWorkspace();

        assertThrows(NullPointerException.class, () -> manager.create(EditorId.parse("sample:basic"), null));
        assertThrows(IllegalArgumentException.class, () -> manager.create(EditorId.parse("sample:basic"), ""));
        assertThrows(IllegalArgumentException.class, () -> manager.create(EditorId.parse("sample:basic"), "   "));
        assertThrows(IllegalArgumentException.class,
                () -> manager.create(EditorId.parse("sample:basic"), "x".repeat(257)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.create(EditorId.parse("sample:basic"), "\t\n"));

        assertEquals(before, manager.workspaces());
        assertEquals(activeBefore, manager.activeWorkspace());
        assertEquals(1, manager.workspaces().size());
        assertEquals("Main", manager.workspaces().get(0).name());
        assertEquals(existing.id(), manager.workspaces().get(0).id());
    }

    @Test void createKeepsExactNameAndNeverInventsADefaultName() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance longName = manager.create(EditorId.parse("sample:basic"), "x".repeat(256));
        assertEquals(256, longName.name().length());
        WorkspaceInstance padded = manager.create(EditorId.parse("sample:basic"), "  Main  ");
        assertEquals("  Main  ", padded.name());
        for (WorkspaceInstance instance : manager.workspaces()) {
            assertNotEquals("Workspace", instance.name());
        }
    }

    @Test void duplicateCopiesLayoutWithFreshIdentityAndLeavesSourceUntouched() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance source = manager.create(EditorId.parse("sample:basic"), "Source");
        UUID document = UUID.randomUUID();
        UUID scenePanel = panel(source, SCENE).id();
        manager.bind(source.id(), scenePanel, PanelBinding.pinned(document));
        manager.setPanelState(source.id(), scenePanel, Map.of("scroll", "120"));
        manager.setVisible(source.id(), false);

        WorkspaceInstance copy = manager.duplicate(source.id(), "Copy");

        assertNotEquals(source.id(), copy.id());
        assertEquals("Copy", copy.name());
        assertFalse(copy.visible());
        assertEquals(2, copy.panels().size());
        for (int index = 0; index < copy.panels().size(); index++) {
            PanelInstance origin = manager.workspaces().get(0).panels().get(index);
            PanelInstance duplicated = copy.panels().get(index);
            assertEquals(origin.type(), duplicated.type());
            assertEquals(origin.viewState(), duplicated.viewState());
            assertEquals(origin.binding(), duplicated.binding());
            assertNotEquals(origin.id(), duplicated.id());
        }
        assertEquals(Map.of("scroll", "120"), panel(copy, SCENE).viewState());

        WorkspaceInstance reloadedSource = manager.workspaces().get(0);
        assertEquals(source.id(), reloadedSource.id());
        assertFalse(reloadedSource.visible());
        assertEquals(Map.of("scroll", "120"), panel(reloadedSource, SCENE).viewState());
        assertEquals(PanelBinding.pinned(document), panel(reloadedSource, SCENE).binding());
        manager.setPanelState(copy.id(), panel(copy, SCENE).id(), Map.of("scroll", "900"));
        assertEquals(Map.of("scroll", "120"), panel(manager.workspaces().get(0), SCENE).viewState());
    }

    @Test void duplicateNeverActivatesItselfAndKeepsOriginVisibility() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance first = manager.create(EditorId.parse("sample:basic"), "First");
        manager.setVisible(first.id(), false);
        assertTrue(manager.activeWorkspace().isEmpty());

        WorkspaceInstance hiddenCopy = manager.duplicate(first.id(), "HiddenCopy");
        assertFalse(hiddenCopy.visible());
        assertTrue(manager.activeWorkspace().isEmpty());
        assertTrue(manager.activate(hiddenCopy.id(), false) == false);

        WorkspaceInstance visible = manager.create(EditorId.parse("sample:basic"), "Visible");
        manager.setVisible(visible.id(), false);
        assertTrue(manager.activeWorkspace().isEmpty());
        WorkspaceInstance copy = manager.duplicate(visible.id(), "CopyOfVisible");
        assertFalse(copy.visible());
        assertTrue(manager.activeWorkspace().isEmpty());

        manager.setVisible(copy.id(), true);
        assertTrue(manager.activeWorkspace().isEmpty());
        WorkspaceInstance last = manager.duplicate(manager.workspaces().get(1).id(), "CopyOfVisible2");
        assertFalse(last.visible());
        assertTrue(manager.activeWorkspace().isEmpty());

        // A visible copy is the only kind that may pick up an empty active slot.
        assertTrue(manager.workspaces().get(3).visible());
        WorkspaceInstance revived = manager.duplicate(manager.workspaces().get(3).id(), "Revived");
        assertTrue(revived.visible());
        assertEquals(Optional.of(revived.id()), manager.activeWorkspace());
    }

    @Test void duplicateRejectsUnknownSourceAndInvalidNames() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance source = manager.create(EditorId.parse("sample:basic"), "Source");
        assertThrows(IllegalArgumentException.class, () -> manager.duplicate(UUID.randomUUID(), "Copy"));
        assertThrows(IllegalArgumentException.class, () -> manager.duplicate(source.id(), " "));
        assertThrows(IllegalArgumentException.class, () -> manager.duplicate(source.id(), "y".repeat(257)));
        assertThrows(NullPointerException.class, () -> manager.duplicate(source.id(), null));
        assertEquals(1, manager.workspaces().size());
    }

    @Test void multipleTemplatesKeepIndependentPerWorkspaceState() {
        WorkspaceManager manager = manager(
                template(EditorId.parse("sample:basic"), new PanelSpec(SCENE, false), new PanelSpec(LAYERS, false)),
                template(EditorId.parse("sample:node"), new PanelSpec(NODE, true), new PanelSpec(NODE, true)));
        WorkspaceInstance basic = manager.create(EditorId.parse("sample:basic"), "Basic");
        WorkspaceInstance node = manager.create(EditorId.parse("sample:node"), "Node");

        assertEquals(2, node.panels().size());
        assertEquals(List.of(NODE, NODE), node.panels().stream().map(PanelInstance::type).toList());
        assertNotEquals(node.panels().get(0).id(), node.panels().get(1).id());
        assertNotEquals(basic.panels().get(0).id(), node.panels().get(0).id());

        manager.setPanelState(basic.id(), panel(basic, SCENE).id(), Map.of("zoom", "2"));
        manager.setPanelState(node.id(), node.panels().get(0).id(), Map.of("zoom", "8"));

        assertEquals(Map.of("zoom", "2"), panel(manager.workspaces().get(0), SCENE).viewState());
        assertEquals(Map.of("zoom", "8"), manager.workspaces().get(1).panels().get(0).viewState());
    }

    @Test void unfinishedGestureBlocksActivationWithoutAnyStateChange() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance first = manager.create(EditorId.parse("sample:basic"), "First");
        WorkspaceInstance second = manager.create(EditorId.parse("sample:basic"), "Second");
        assertEquals(Optional.of(first.id()), manager.activeWorkspace());

        assertFalse(manager.activate(second.id(), true));
        assertEquals(Optional.of(first.id()), manager.activeWorkspace());
        assertEquals(List.of(first, second), manager.workspaces());

        assertTrue(manager.activate(second.id(), false));
        assertEquals(Optional.of(second.id()), manager.activeWorkspace());
    }

    @Test void activateRejectsUnknownIdentityInsteadOfSilentlyReturningFalse() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance only = manager.create(EditorId.parse("sample:basic"), "Only");
        List<WorkspaceInstance> before = manager.workspaces();

        assertThrows(NullPointerException.class, () -> manager.activate(null, false));
        assertThrows(IllegalArgumentException.class, () -> manager.activate(UUID.randomUUID(), false));
        assertThrows(IllegalArgumentException.class, () -> manager.activate(UUID.randomUUID(), true));
        assertEquals(before, manager.workspaces());
        assertEquals(Optional.of(only.id()), manager.activeWorkspace());

        manager.setVisible(only.id(), false);
        assertFalse(manager.activate(only.id(), false));
        assertFalse(manager.activate(only.id(), true));
        assertTrue(manager.activeWorkspace().isEmpty());
    }

    @Test void hiddenWorkspacesNeverBecomeActiveAndHidingActivePicksFirstVisible() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance first = manager.create(EditorId.parse("sample:basic"), "First");
        WorkspaceInstance second = manager.create(EditorId.parse("sample:basic"), "Second");
        WorkspaceInstance third = manager.create(EditorId.parse("sample:basic"), "Third");

        assertTrue(manager.activate(third.id(), false));
        manager.setVisible(third.id(), false);
        assertEquals(Optional.of(first.id()), manager.activeWorkspace());
        assertFalse(manager.activate(third.id(), false));

        manager.setVisible(first.id(), false);
        manager.setVisible(second.id(), false);
        assertTrue(manager.activeWorkspace().isEmpty());
        assertTrue(manager.activate(second.id(), false) == false);
        manager.setVisible(second.id(), true);
        assertTrue(manager.activeWorkspace().isEmpty());
        assertTrue(manager.activate(second.id(), false));
        assertEquals(Optional.of(second.id()), manager.activeWorkspace());
    }

    @Test void renameAndMoveOnlyChangeTheirOwnWorkspace() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance first = manager.create(EditorId.parse("sample:basic"), "First");
        WorkspaceInstance second = manager.create(EditorId.parse("sample:basic"), "Second");
        UUID document = UUID.randomUUID();
        manager.bind(first.id(), panel(first, SCENE).id(), PanelBinding.pinned(document));

        manager.rename(first.id(), "Renamed");
        assertEquals(List.of("Renamed", "Second"), manager.workspaces().stream().map(WorkspaceInstance::name).toList());
        assertEquals(first.id(), manager.workspaces().get(0).id());
        assertEquals(Optional.of(first.id()), manager.activeWorkspace());
        WorkspaceInstance renamed = manager.workspaces().get(0);
        assertEquals(panel(first, SCENE).viewState(), panel(renamed, SCENE).viewState());
        assertEquals(document, panel(renamed, SCENE).binding().targetDocument());

        manager.move(second.id(), 0);
        assertEquals(List.of(second.id(), first.id()), manager.workspaces().stream().map(WorkspaceInstance::id).toList());
        assertEquals(Optional.of(first.id()), manager.activeWorkspace());
        assertThrows(IllegalArgumentException.class, () -> manager.move(second.id(), 99));
        assertThrows(IllegalArgumentException.class, () -> manager.move(first.id(), -5));
        assertEquals(List.of(second.id(), first.id()), manager.workspaces().stream().map(WorkspaceInstance::id).toList());
        manager.move(first.id(), 0);
        assertEquals(first.id(), manager.workspaces().get(0).id());
    }

    @Test void renameAndMoveRejectUnknownIdentityWithoutTouchingOrder() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance first = manager.create(EditorId.parse("sample:basic"), "First");
        UUID unknown = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> manager.rename(unknown, "Nope"));
        assertThrows(IllegalArgumentException.class, () -> manager.move(unknown, 0));
        assertThrows(IllegalArgumentException.class, () -> manager.setVisible(unknown, false));
        assertThrows(IllegalArgumentException.class, () -> manager.rename(first.id(), ""));
        assertEquals(List.of(first), manager.workspaces());
        assertEquals("First", manager.workspaces().get(0).name());
    }

    @Test void pinnedBindingsDoNotDriftWithTheActiveContextAndFollowActiveCarriesNoDocument() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance workspace = manager.create(EditorId.parse("sample:basic"), "Main");
        UUID scenePanel = panel(workspace, SCENE).id();
        UUID layersPanel = panel(workspace, LAYERS).id();
        UUID document = UUID.randomUUID();

        manager.bind(workspace.id(), scenePanel, PanelBinding.pinned(document));
        manager.bind(workspace.id(), layersPanel, PanelBinding.followActive());
        manager.activate(workspace.id(), false);

        WorkspaceInstance stored = manager.workspaces().get(0);
        assertEquals(PanelBinding.Mode.PINNED, panel(stored, SCENE).binding().mode());
        assertEquals(document, panel(stored, SCENE).binding().targetDocument());
        assertNull(panel(stored, LAYERS).binding().targetDocument());
        assertEquals(PanelBinding.Mode.FOLLOW_ACTIVE, panel(stored, LAYERS).binding().mode());

        assertThrows(NullPointerException.class, () -> PanelBinding.pinned(null));
        assertThrows(IllegalArgumentException.class,
                () -> new PanelBinding(PanelBinding.Mode.FOLLOW_ACTIVE, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> new PanelBinding(PanelBinding.Mode.PINNED, null));
    }

    @Test void invalidCallsAreAtomicAndUnknownPanelIdentityChangesNothing() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance workspace = manager.create(EditorId.parse("sample:basic"), "Main");
        List<WorkspaceInstance> before = manager.workspaces();

        assertThrows(IllegalArgumentException.class, () -> manager.bind(workspace.id(), UUID.randomUUID(),
                PanelBinding.followActive()));
        assertThrows(IllegalArgumentException.class, () -> manager.setPanelState(workspace.id(), UUID.randomUUID(),
                Map.of("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> manager.bind(UUID.randomUUID(),
                panel(workspace, SCENE).id(), PanelBinding.followActive()));
        assertThrows(NullPointerException.class,
                () -> manager.bind(workspace.id(), panel(workspace, SCENE).id(), null));
        assertThrows(NullPointerException.class,
                () -> manager.setPanelState(workspace.id(), panel(workspace, SCENE).id(), null));
        assertEquals(before, manager.workspaces());
        assertEquals(Optional.of(workspace.id()), manager.activeWorkspace());
    }

    @Test void viewStateIsBoundedCopiedAndIndependent() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance workspace = manager.create(EditorId.parse("sample:basic"), "Main");
        UUID scenePanel = panel(workspace, SCENE).id();

        Map<String, String> source = new HashMap<>();
        source.put("scroll", "10");
        manager.setPanelState(workspace.id(), scenePanel, source);
        source.put("scroll", "999");
        source.put("late", "added");
        assertEquals(Map.of("scroll", "10"), panel(manager.workspaces().get(0), SCENE).viewState());
        assertThrows(UnsupportedOperationException.class,
                () -> panel(manager.workspaces().get(0), SCENE).viewState().put("x", "y"));

        Map<String, String> oversized = new HashMap<>();
        for (int index = 0; index <= PanelInstance.MAX_STATE_ENTRIES; index++) {
            oversized.put("k" + index, "v");
        }
        assertThrows(IllegalArgumentException.class,
                () -> manager.setPanelState(workspace.id(), scenePanel, oversized));
        assertThrows(IllegalArgumentException.class,
                () -> manager.setPanelState(workspace.id(), scenePanel, Map.of("k".repeat(257), "v")));
        assertThrows(IllegalArgumentException.class,
                () -> manager.setPanelState(workspace.id(), scenePanel, Map.of("k", "v".repeat(65537))));
        assertEquals(Map.of("scroll", "10"), panel(manager.workspaces().get(0), SCENE).viewState());
    }

    @Test void viewStateIsOpaqueAndKeepsCaseDistinctAndEmptyKeys() {
        WorkspaceManager manager = sceneManager();
        WorkspaceInstance workspace = manager.create(EditorId.parse("sample:basic"), "Main");
        UUID scenePanel = panel(workspace, SCENE).id();

        Map<String, String> thirdParty = new LinkedHashMap<>();
        thirdParty.put("Key", "1");
        thirdParty.put("key", "2");
        thirdParty.put("", "empty-key-value");
        thirdParty.put("  ", "blank");
        manager.setPanelState(workspace.id(), scenePanel, thirdParty);

        Map<String, String> stored = panel(manager.workspaces().get(0), SCENE).viewState();
        assertEquals(4, stored.size());
        assertEquals("1", stored.get("Key"));
        assertEquals("2", stored.get("key"));
        assertEquals("empty-key-value", stored.get(""));
        assertEquals("blank", stored.get("  "));
        assertEquals(List.of("Key", "key", "", "  "), List.copyOf(stored.keySet()));

        Map<String, String> keys = new HashMap<>();
        keys.put("k".repeat(256), "v");
        manager.setPanelState(workspace.id(), scenePanel, keys);
        assertEquals("v", panel(manager.workspaces().get(0), SCENE).viewState().get("k".repeat(256)));
    }

    @Test void snapshotsIgnoreExternalMutationAndTemplatesNeverRewriteInstances() {
        List<WorkspaceTemplate> templates = new ArrayList<>();
        templates.add(template(EditorId.parse("sample:basic"), new PanelSpec(SCENE, false)));
        WorkspaceManager manager = new WorkspaceManager(templates);
        templates.add(template(EditorId.parse("sample:node"), new PanelSpec(NODE, false)));

        assertThrows(IllegalArgumentException.class, () -> manager.create(EditorId.parse("sample:node"), "Node"));
        WorkspaceInstance workspace = manager.create(EditorId.parse("sample:basic"), "Main");
        manager.rename(workspace.id(), "Renamed");

        List<WorkspaceInstance> snapshot = manager.workspaces();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(workspace));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.set(0, workspace));
        assertEquals("Renamed", manager.workspaces().get(0).name());
        assertEquals(1, manager.workspaces().get(0).templateVersion());

        List<PanelSpec> specs = new ArrayList<>(List.of(new PanelSpec(SCENE, false)));
        List<PanelInstance> panels = List.copyOf(manager.workspaces().get(0).panels());
        WorkspaceTemplate frozen = new WorkspaceTemplate(EditorId.parse("sample:basic"), 2,
                new TextContent.Literal("v2"), specs);
        specs.clear();
        assertEquals(1, frozen.panels().size());
        manager.rename(workspace.id(), "Again");
        assertEquals(panels, manager.workspaces().get(0).panels());
    }

    @Test void workspaceRecordConstructionValidatesIdentityAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceInstance(UUID.randomUUID(), SCENE, 0,
                "Main", true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceInstance(UUID.randomUUID(), SCENE, 1,
                "  ", true, List.of()));
        UUID panelId = UUID.randomUUID();
        PanelInstance instance = new PanelInstance(panelId, SCENE, PanelBinding.followActive(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceInstance(UUID.randomUUID(), SCENE, 1,
                "Main", true, List.of(instance, instance)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceTemplate(SCENE, 0, new TextContent.Literal("t"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceTemplate(SCENE, 1,
                new TextContent.Literal("t"), List.of(new PanelSpec(SCENE, false), new PanelSpec(SCENE, true))));
        assertThrows(NullPointerException.class, () -> new PanelSpec(null, true));
        assertTrue(instance.viewState().isEmpty());
        assertSame(PanelBinding.Mode.FOLLOW_ACTIVE, instance.binding().mode());
    }
}
