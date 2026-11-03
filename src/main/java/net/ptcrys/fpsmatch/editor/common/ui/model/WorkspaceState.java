package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.*;

/**
 * Immutable user environment, independent of template availability and document lifetime.
 * Current panels and the user's saved baseline are separate, including panels removed since save.
 */
public record WorkspaceState(List<WorkspaceInstance> workspaces, UUID activeWorkspace,
                             Map<UUID, List<PanelInstance>> savedLayouts) {

    public static final int MAX_WORKSPACES = WorkspaceManager.MAX_WORKSPACES;
    public static final int MAX_PANELS = WorkspaceInstance.MAX_PANELS;
    public static final int MAX_STATE_ENTRIES = PanelInstance.MAX_STATE_ENTRIES;
    public static final int MAX_STATE_KEY = PanelInstance.MAX_STATE_KEY;
    public static final int MAX_STATE_VALUE = PanelInstance.MAX_STATE_VALUE;

    public WorkspaceState {
        Objects.requireNonNull(workspaces, "workspaces");
        Objects.requireNonNull(savedLayouts, "savedLayouts");
        if (workspaces.size() > MAX_WORKSPACES) throw new IllegalArgumentException("too many workspaces");
        workspaces = List.copyOf(workspaces);
        var ids = new HashSet<UUID>();
        var owners = new HashMap<UUID, UUID>();
        var types = new HashMap<UUID, EditorId>();
        var saved = new LinkedHashMap<UUID, List<PanelInstance>>();
        boolean activeFound = activeWorkspace == null;
        for (var workspace : workspaces) {
            if (!ids.add(workspace.id())) throw new IllegalArgumentException("duplicate workspace identity");
            if (workspace.id().equals(activeWorkspace)) {
                if (!workspace.visible()) throw new IllegalArgumentException("active workspace is hidden");
                activeFound = true;
            }
            checkPanels(workspace.id(), workspace.panels(), owners, types);
            var baseline = List.copyOf(Objects.requireNonNull(savedLayouts.get(workspace.id()), "missing saved layout"));
            checkPanels(workspace.id(), baseline, owners, types);
            saved.put(workspace.id(), baseline);
        }
        if (!activeFound) throw new IllegalArgumentException("unknown active workspace");
        if (!ids.equals(savedLayouts.keySet())) throw new IllegalArgumentException("saved layout workspace identities differ");
        savedLayouts = Collections.unmodifiableMap(saved);
    }

    private static void checkPanels(UUID workspace, List<PanelInstance> panels, Map<UUID, UUID> owners, Map<UUID, EditorId> types) {
        if (panels.size() > MAX_PANELS) throw new IllegalArgumentException("too many panel instances");
        var ids = new HashSet<UUID>();
        for (var panel : panels) {
            Objects.requireNonNull(panel, "panel");
            if (!ids.add(panel.id())) throw new IllegalArgumentException("duplicate panel identity");
            var owner = owners.putIfAbsent(panel.id(), workspace);
            if (owner != null && !owner.equals(workspace)) throw new IllegalArgumentException("panel identity belongs to multiple workspaces");
            var type = types.putIfAbsent(panel.id(), panel.type());
            if (type != null && !type.equals(panel.type())) throw new IllegalArgumentException("panel identity changes type between layouts");
        }
    }
}
