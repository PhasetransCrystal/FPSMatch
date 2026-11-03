package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.Objects;
import java.util.UUID;

/**
 * The solved geometry of one splitter, sitting between the two child areas of a
 * {@link DockNode.Split}.
 *
 * @param nodeId the {@link DockNode.Split} identity
 * @param axis   split orientation
 * @param bounds splitter strip; its extent along {@code axis} is the applied gap
 */
public record SplitBox(UUID nodeId, DockNode.Axis axis, Rect bounds) {

    public SplitBox {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(bounds, "bounds");
    }
}
