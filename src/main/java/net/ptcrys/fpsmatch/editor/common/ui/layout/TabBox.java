package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The solved geometry of one tab group. Every member keeps the same body; only the active member is
 * visible.
 *
 * @param nodeId the {@link DockNode.Tabs} identity
 * @param panels all members in tree order
 * @param active the visible member
 * @param header the tab header strip, at the top of the node area
 */
public record TabBox(UUID nodeId, List<UUID> panels, UUID active, Rect header) {

    public TabBox {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(panels, "panels");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(header, "header");
        if (panels.isEmpty()) {
            throw new IllegalArgumentException("tab box needs at least one panel");
        }
        Set<UUID> unique = new HashSet<>();
        for (UUID panel : panels) {
            Objects.requireNonNull(panel, "panel");
            if (!unique.add(panel)) {
                throw new IllegalArgumentException("duplicate panel in tab box: " + panel);
            }
        }
        if (!unique.contains(active)) {
            throw new IllegalArgumentException("active panel is not a tab member: " + active);
        }
        panels = List.copyOf(panels);
    }
}
