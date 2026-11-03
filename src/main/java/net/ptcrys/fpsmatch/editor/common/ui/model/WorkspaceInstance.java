package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One materialized workspace. Panel order is the model order used by {@link WorkspaceManager#move};
 * visibility is a model fact and never rewrites any external document session.
 *
 * @param id              workspace instance identity, never {@code null}
 * @param templateId      identity of the template this instance was created from, never {@code null}
 * @param templateVersion version of that template at creation time, at least {@code 1}
 * @param name            display name, non-blank, at most 256 characters
 * @param visible         whether the workspace takes part in active selection
 * @param panels          panel instances, immutable, unique ids
 */
public record WorkspaceInstance(UUID id, EditorId templateId, int templateVersion, String name,
                                boolean visible, List<PanelInstance> panels) {

    /** Maximum workspace name length in characters. */
    static final int MAX_NAME = 256;
    /** Maximum number of panel instances in one workspace. */
    static final int MAX_PANELS = 1024;

    public WorkspaceInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(panels, "panels");
        if (templateVersion < 1) {
            throw new IllegalArgumentException("template version must be >= 1: " + templateVersion);
        }
        if (name.isBlank() || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("invalid workspace name length: " + name.length());
        }
        if (panels.size() > MAX_PANELS) {
            throw new IllegalArgumentException("too many panel instances: " + panels.size());
        }
        Set<UUID> ids = new HashSet<>();
        for (PanelInstance panel : panels) {
            Objects.requireNonNull(panel, "panel");
            if (!ids.add(panel.id())) {
                throw new IllegalArgumentException("duplicate panel instance id: " + panel.id());
            }
        }
        panels = List.copyOf(panels);
    }

    /** Returns the same workspace with a different visibility flag. */
    WorkspaceInstance withVisible(boolean newVisible) {
        return visible == newVisible ? this : new WorkspaceInstance(id, templateId, templateVersion, name, newVisible, panels);
    }

    /** Returns the same workspace with a different name. */
    WorkspaceInstance withName(String newName) {
        return name.equals(newName) ? this : new WorkspaceInstance(id, templateId, templateVersion, newName, visible, panels);
    }

    /** Returns the same workspace with its panels replaced. */
    WorkspaceInstance withPanels(List<PanelInstance> newPanels) {
        return panels.equals(newPanels) ? this : new WorkspaceInstance(id, templateId, templateVersion, name, visible, newPanels);
    }
}
