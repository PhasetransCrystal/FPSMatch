package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A registration-free dock tree. Nothing here knows about panel roles, a mandatory viewport or any
 * product panel id: callers may use arbitrary third-party {@link UUID}s.
 */
public sealed interface DockNode permits DockNode.Empty, DockNode.Leaf, DockNode.Tabs, DockNode.Split {

    /** Split orientation; {@code HORIZONTAL} places {@code first} left of {@code second}. */
    enum Axis {
        /** {@code first} on the left, {@code second} on the right. */
        HORIZONTAL,
        /** {@code first} on top, {@code second} below. */
        VERTICAL
    }

    /** No content at all; contributes no geometry. */
    record Empty() implements DockNode {}

    /** Exactly one panel filling the node area. */
    record Leaf(UUID panelId) implements DockNode {

        public Leaf {
            Objects.requireNonNull(panelId, "panelId");
        }
    }

    /** A tab group: exactly one active panel, every member sharing the same body. */
    record Tabs(UUID id, List<UUID> panels, UUID active) implements DockNode {

        public Tabs {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(panels, "panels");
            Objects.requireNonNull(active, "active");
            if (panels.isEmpty() || panels.size() > Limits.MAX_TAB_PANELS) {
                throw new IllegalArgumentException("tab group size out of range: " + panels.size());
            }
            Set<UUID> unique = new HashSet<>();
            for (UUID panel : panels) {
                Objects.requireNonNull(panel, "panel");
                if (!unique.add(panel)) {
                    throw new IllegalArgumentException("duplicate panel in tab group: " + panel);
                }
            }
            if (!unique.contains(active)) {
                throw new IllegalArgumentException("active panel is not a tab member: " + active);
            }
            panels = List.copyOf(panels);
        }
    }

    /** A binary split with a {@code ratio} in {@code [0.05, 0.95]}; illegal ratios are rejected. */
    record Split(UUID id, Axis axis, double ratio, DockNode first, DockNode second) implements DockNode {

        public Split {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            Bounds.finite(ratio, "ratio");
            if (ratio < Limits.MIN_RATIO || ratio > Limits.MAX_RATIO) {
                throw new IllegalArgumentException("ratio out of range: " + ratio);
            }
        }
    }
}
