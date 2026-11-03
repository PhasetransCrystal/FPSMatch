package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable workspace template. Templates are inputs only: changing or re-registering a
 * template never rewrites already created workspaces.
 *
 * <p>
 * A panel type may be contributed more than once only while every contribution allows multiple
 * instances. A single-instance type that appears more than once is rejected, because no rule could
 * decide which of the copies is the single instance.
 * </p>
 *
 * @param id      template identity, never {@code null}
 * @param version template version, at least {@code 1}
 * @param label   unresolved display label, never {@code null}
 * @param panels  contributed panel specs, immutable, at most 1024 entries
 */
public record WorkspaceTemplate(EditorId id, int version, TextContent label, List<PanelSpec> panels) {

    /** Maximum number of panel specs one template may contribute. */
    static final int MAX_PANELS = 1024;

    public WorkspaceTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(panels, "panels");
        if (version < 1) {
            throw new IllegalArgumentException("template version must be >= 1: " + version);
        }
        if (panels.size() > MAX_PANELS) {
            throw new IllegalArgumentException("too many panel specs: " + panels.size());
        }
        Map<EditorId, Integer> counts = new HashMap<>();
        Set<EditorId> singleInstance = new HashSet<>();
        for (PanelSpec spec : panels) {
            Objects.requireNonNull(spec, "panel spec");
            counts.merge(spec.type(), 1, Integer::sum);
            if (!spec.multipleInstances()) {
                singleInstance.add(spec.type());
            }
        }
        for (EditorId type : singleInstance) {
            if (counts.get(type) > 1) {
                throw new IllegalArgumentException("single-instance panel type repeated: " + type);
            }
        }
        panels = List.copyOf(panels);
    }
}
