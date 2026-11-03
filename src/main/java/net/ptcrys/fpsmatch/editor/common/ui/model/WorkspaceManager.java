package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The workspace state model of the workbench. It owns workspace and panel identity, template
 * instantiation, active selection, order, visibility and per-panel document binding and view state.
 *
 * <p>
 * This is model state only: no screenshots, no MC controls, no dock geometry format and no
 * document session. Bounds and membership are checked before any mutation, so an invalid call never
 * leaves a partial change behind.
 * </p>
 */
public final class WorkspaceManager {

    /** Maximum number of registered templates. */
    public static final int MAX_TEMPLATES = 4096;
    /** Maximum number of live workspaces. */
    public static final int MAX_WORKSPACES = 4096;

    private final Map<EditorId, WorkspaceTemplate> templates;
    private final Map<UUID, WorkspaceInstance> instances = new LinkedHashMap<>();
    private final Map<UUID, List<PanelInstance>> savedLayouts = new LinkedHashMap<>();
    private UUID active;

    /**
     * Creates a manager over a fixed set of templates.
     *
     * @param templates registered templates; duplicate template identities are rejected instead of
     *                  being silently overwritten by load order
     * @throws IllegalArgumentException when a template identity repeats or the count is out of range
     */
    public WorkspaceManager(Collection<WorkspaceTemplate> templates) {
        Objects.requireNonNull(templates, "templates");
        if (templates.size() > MAX_TEMPLATES) {
            throw new IllegalArgumentException("too many templates: " + templates.size());
        }
        Map<EditorId, WorkspaceTemplate> copy = new LinkedHashMap<>();
        for (WorkspaceTemplate template : templates) {
            Objects.requireNonNull(template, "template");
            if (copy.putIfAbsent(template.id(), template) != null) {
                throw new IllegalArgumentException("duplicate template identity: " + template.id());
            }
        }
        this.templates = Collections.unmodifiableMap(copy);
    }

    /**
     * Creates a workspace from a template and activates it when no workspace is active yet.
     *
     * <p>
     * The requested name is stored verbatim: this model never invents a default name, so a
     * localized default has to be supplied by the calling layer.
     * </p>
     *
     * @param template template identity that must already be registered
     * @param userName requested display name; must be non-blank and at most 256 characters
     * @return the new workspace with fresh workspace and panel identities
     * @throws NullPointerException     for a {@code null} template or name
     * @throws IllegalArgumentException for a blank or over-long name, or an unknown template
     */
    public WorkspaceInstance create(EditorId template, String userName) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(userName, "userName");
        String name = requireName(userName);
        if (instances.size() >= MAX_WORKSPACES) {
            throw new IllegalStateException("too many workspaces: " + instances.size());
        }
        WorkspaceTemplate spec = requireTemplate(template);
        List<PanelInstance> panels = new ArrayList<>(spec.panels().size());
        for (PanelSpec panel : spec.panels()) {
            panels.add(new PanelInstance(UUID.randomUUID(), panel.type(), PanelBinding.followActive(), Map.of()));
        }
        WorkspaceInstance created = new WorkspaceInstance(UUID.randomUUID(), spec.id(), spec.version(),
                name, true, panels);
        instances.put(created.id(), created);
        savedLayouts.put(created.id(), created.panels());
        if (active == null) {
            active = created.id();
        }
        return created;
    }

    /**
     * Copies a workspace layout. Workspace and panel identities are new, bindings and view state are
     * copied, no document is duplicated or reopened, and the copy keeps the visibility of its
     * origin.
     *
     * <p>
     * The copy becomes active only when it is visible and nothing else is active, so copying a
     * hidden workspace never re-activates a hidden workspace.
     * </p>
     *
     * @param source  identity of the workspace to copy
     * @param newName display name of the copy; must be non-blank and at most 256 characters
     */
    public WorkspaceInstance duplicate(UUID source, String newName) {
        Objects.requireNonNull(newName, "newName");
        if (instances.size() >= MAX_WORKSPACES) {
            throw new IllegalStateException("too many workspaces: " + instances.size());
        }
        WorkspaceInstance origin = requireWorkspace(source);
        String name = requireName(newName);
        Map<UUID, UUID> copiedIds = new LinkedHashMap<>();
        List<PanelInstance> panels = new ArrayList<>(origin.panels().size());
        for (PanelInstance panel : origin.panels()) {
            panels.add(new PanelInstance(copiedIds.computeIfAbsent(panel.id(), ignored -> UUID.randomUUID()), panel.type(),
                    new PanelBinding(panel.binding().mode(), panel.binding().targetDocument()),
                    panel.viewState()));
        }
        WorkspaceInstance created = new WorkspaceInstance(UUID.randomUUID(), origin.templateId(),
                origin.templateVersion(), name, origin.visible(), panels);
        instances.put(created.id(), created);
        List<PanelInstance> saved = new ArrayList<>();
        for (var panel : savedLayouts.getOrDefault(source, origin.panels())) saved.add(new PanelInstance(copiedIds.computeIfAbsent(panel.id(), ignored -> UUID.randomUUID()), panel.type(), panel.binding(), panel.viewState()));
        savedLayouts.put(created.id(), List.copyOf(saved));
        if (active == null && created.visible()) {
            active = created.id();
        }
        return created;
    }

    /** Renames one workspace; identity, panels, order and visibility are untouched. */
    public void rename(UUID id, String name) {
        Objects.requireNonNull(name, "name");
        WorkspaceInstance current = requireWorkspace(id);
        instances.put(id, current.withName(requireName(name)));
    }

    /**
     * Sets workspace visibility. Hiding the active workspace moves the active selection to the first
     * visible workspace in model order, or clears it when every workspace is hidden.
     */
    public void setVisible(UUID id, boolean visible) {
        WorkspaceInstance current = requireWorkspace(id);
        WorkspaceInstance updated = current.withVisible(visible);
        instances.put(id, updated);
        if (!visible && id.equals(active)) {
            active = firstVisible();
        }
    }

    /**
     * Moves one workspace to a new index in model order.
     *
     * @param index target index in {@code [0, workspaceCount - 1]}
     * @throws IllegalArgumentException for unknown identity or an index outside the current range
     */
    public void move(UUID id, int index) {
        WorkspaceInstance current = requireWorkspace(id);
        List<WorkspaceInstance> ordered = new ArrayList<>(instances.values());
        ordered.remove(current);
        if (index < 0 || index >= instances.size()) {
            throw new IllegalArgumentException("workspace index out of range: " + index);
        }
        ordered.add(index, current);
        instances.clear();
        for (WorkspaceInstance instance : ordered) {
            instances.put(instance.id(), instance);
        }
    }

    /**
     * Makes one workspace active. An unfinished gesture or a hidden workspace leaves every workspace
     * untouched; there is no automatic commit.
     *
     * @return {@code true} when the active workspace is the requested one afterwards
     * @throws NullPointerException     for a {@code null} identity
     * @throws IllegalArgumentException for an unknown identity, which is rejected instead of being
     *                                  reported as a failed switch
     */
    public boolean activate(UUID id, boolean hasUnfinishedGesture) {
        WorkspaceInstance candidate = requireWorkspace(id);
        if (!candidate.visible() || hasUnfinishedGesture) {
            return false;
        }
        active = id;
        return true;
    }

    /**
     * Rebinds one panel. Only the addressed panel changes; a closed document never causes another
     * document to be bound implicitly, because document existence belongs to the workbench
     * presentation layer rather than to this model.
     *
     * @throws IllegalArgumentException for unknown workspace or panel identities
     */
    public void bind(UUID workspaceId, UUID panelId, PanelBinding binding) {
        Objects.requireNonNull(binding, "binding");
        WorkspaceInstance current = requireWorkspace(workspaceId);
        WorkspaceInstance updated = current.withPanels(rebind(current, panelId, binding));
        instances.put(workspaceId, updated);
    }

    /** Adds a fresh following panel of a type contributed by a registered template. */
    public PanelInstance addPanel(UUID workspaceId, EditorId type) {
        Objects.requireNonNull(type, "type");
        WorkspaceInstance current = requireWorkspace(workspaceId);
        requirePanelMultiplicity(current, type);
        return appendPanel(current, new PanelInstance(UUID.randomUUID(), type, PanelBinding.followActive(), Map.of()));
    }

    /** Copies one panel's presentation and binding, giving the copy an independent identity. */
    public PanelInstance duplicatePanel(UUID workspaceId, UUID panelId) {
        WorkspaceInstance current = requireWorkspace(workspaceId);
        PanelInstance panel = requirePanel(current, panelId);
        requirePanelMultiplicity(current, panel.type());
        return appendPanel(current, new PanelInstance(UUID.randomUUID(), panel.type(), panel.binding(), panel.viewState()));
    }

    private void requirePanelMultiplicity(WorkspaceInstance current, EditorId type) {
        PanelSpec spec = requireTemplate(current.templateId()).panels().stream().filter(panel -> panel.type().equals(type)).findFirst().orElseGet(() -> templates.values().stream().flatMap(template -> template.panels().stream()).filter(panel -> panel.type().equals(type)).findFirst().orElseThrow(() -> new IllegalArgumentException("unknown panel type: " + type)));
        if (!spec.multipleInstances() && current.panels().stream().anyMatch(panel -> panel.type().equals(type))) throw new IllegalStateException("panel type permits one instance: " + type);
    }

    private PanelInstance appendPanel(WorkspaceInstance current, PanelInstance panel) {
        if (current.panels().size() >= WorkspaceInstance.MAX_PANELS) throw new IllegalStateException("too many panel instances");
        List<PanelInstance> panels = new ArrayList<>(current.panels());
        panels.add(panel);
        WorkspaceInstance updated = current.withPanels(panels);
        instances.put(current.id(), updated);
        return panel;
    }

    /** Removes only the addressed presentation instance; document lifetime is independent. */
    public void removePanel(UUID workspaceId, UUID panelId) {
        WorkspaceInstance current = requireWorkspace(workspaceId);
        PanelInstance panel = requirePanel(current, panelId);
        List<PanelInstance> panels = new ArrayList<>(current.panels());
        panels.remove(panel);
        WorkspaceInstance updated = current.withPanels(panels);
        instances.put(workspaceId, updated);
    }

    /**
     * Appends a panel to its destination dock group without changing its identity, binding or other
     * view state. Explicit leftTop/leftBottom positions coexist with legacy left/center records.
     * The global list supplies each group's tab order; unrelated panels retain their relative order.
     * Validation and immutable replacement finish before the workspace is changed.
     */
    public void movePanel(UUID workspaceId, UUID panelId, String group) {
        Objects.requireNonNull(group, "group");
        if (!List.of("leftTop", "leftBottom", "right", "bottom", "left", "center").contains(group)) throw new IllegalArgumentException("unknown dock group: " + group);
        WorkspaceInstance current = requireWorkspace(workspaceId);
        PanelInstance panel = requirePanel(current, panelId);
        Map<String, String> state = new LinkedHashMap<>(panel.viewState());
        state.put("dock.group", group);
        PanelInstance moved = new PanelInstance(panel.id(), panel.type(), panel.binding(), state);
        List<PanelInstance> panels = new ArrayList<>(current.panels());
        panels.remove(panel);
        panels.add(moved);
        WorkspaceInstance updated = current.withPanels(panels);
        instances.put(workspaceId, updated);
    }

    private PanelInstance requirePanel(WorkspaceInstance current, UUID panelId) {
        Objects.requireNonNull(panelId, "panelId");
        return current.panels().stream().filter(panel -> panel.id().equals(panelId)).findFirst().orElseThrow(() -> new IllegalArgumentException("unknown panel instance: " + panelId));
    }

    /** Replaces the opaque view state of one panel with a bounded defensive copy. */
    public void setPanelState(UUID workspaceId, UUID panelId, Map<String, String> state) {
        Objects.requireNonNull(state, "state");
        Map<String, String> copy = ViewState.copy(state);
        WorkspaceInstance current = requireWorkspace(workspaceId);
        WorkspaceInstance updated = current.withPanels(applyState(current, panelId, copy));
        instances.put(workspaceId, updated);
    }

    /** Saves only the addressed workspace's immutable panel layout and presentation state. */
    public void saveLayout(UUID workspaceId) {
        WorkspaceInstance current = requireWorkspace(workspaceId);
        savedLayouts.put(workspaceId, current.panels());
    }

    /** Restores the explicit saved layout, preserving workspace identity, name, visibility and active selection. */
    public void restoreLayout(UUID workspaceId) {
        WorkspaceInstance current = requireWorkspace(workspaceId);
        WorkspaceInstance updated = current.withPanels(savedLayouts.getOrDefault(workspaceId, List.of()));
        instances.put(workspaceId, updated);
    }

    /** Returns the workspaces in model order as an immutable list. */
    public List<WorkspaceInstance> workspaces() {
        return List.copyOf(instances.values());
    }

    /** Returns the active workspace, or empty when none is active or every workspace is hidden. */
    public Optional<UUID> activeWorkspace() {
        return Optional.ofNullable(active);
    }

    /** Captures current and saved layouts together; callers can safely pass this value to I/O. */
    public WorkspaceState snapshot() {
        return new WorkspaceState(workspaces(), active, savedLayouts);
    }

    /**
     * Replaces the whole environment after validation. Missing contributions remain opaque
     * instances rather than being recreated from whichever templates happen to be installed.
     */
    public void restore(WorkspaceState state) {
        Objects.requireNonNull(state, "state");
        var candidate = new WorkspaceState(state.workspaces(), state.activeWorkspace(), state.savedLayouts());
        var next = new LinkedHashMap<UUID, WorkspaceInstance>();
        for (var workspace : candidate.workspaces()) next.put(workspace.id(), workspace);
        var saved = new LinkedHashMap<>(candidate.savedLayouts());
        instances.clear();
        instances.putAll(next);
        savedLayouts.clear();
        savedLayouts.putAll(saved);
        active = candidate.activeWorkspace();
    }

    private List<PanelInstance> rebind(WorkspaceInstance current, UUID panelId, PanelBinding binding) {
        Objects.requireNonNull(panelId, "panelId");
        List<PanelInstance> panels = new ArrayList<>(current.panels().size());
        boolean found = false;
        for (PanelInstance panel : current.panels()) {
            if (panel.id().equals(panelId)) {
                found = true;
                panels.add(new PanelInstance(panel.id(), panel.type(), binding, panel.viewState()));
            } else {
                panels.add(panel);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("unknown panel instance: " + panelId);
        }
        return panels;
    }

    private List<PanelInstance> applyState(WorkspaceInstance current, UUID panelId, Map<String, String> state) {
        Objects.requireNonNull(panelId, "panelId");
        List<PanelInstance> panels = new ArrayList<>(current.panels().size());
        boolean found = false;
        for (PanelInstance panel : current.panels()) {
            if (panel.id().equals(panelId)) {
                found = true;
                panels.add(new PanelInstance(panel.id(), panel.type(), panel.binding(), state));
            } else {
                panels.add(panel);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("unknown panel instance: " + panelId);
        }
        return panels;
    }

    private WorkspaceTemplate requireTemplate(EditorId id) {
        WorkspaceTemplate template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("unknown template: " + id);
        }
        return template;
    }

    private WorkspaceInstance requireWorkspace(UUID id) {
        Objects.requireNonNull(id, "id");
        WorkspaceInstance instance = instances.get(id);
        if (instance == null) {
            throw new IllegalArgumentException("unknown workspace: " + id);
        }
        return instance;
    }

    private UUID firstVisible() {
        for (WorkspaceInstance instance : instances.values()) {
            if (instance.visible()) {
                return instance.id();
            }
        }
        return null;
    }

    private static String requireName(String name) {
        if (name.isBlank() || name.length() > 256) {
            throw new IllegalArgumentException("invalid workspace name length: " + name.length());
        }
        return name;
    }
}
