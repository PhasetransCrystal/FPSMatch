package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A validated dock tree. Construction walks the whole tree and rejects structural problems
 * immediately, so every later use may assume a legal shape.
 *
 * <p>
 * Rules: at most {@code 4096} structural nodes, at most {@code 1024} panels, depth at most
 * {@code 64} (the root counts as depth 1); every panel id is unique across leaves and tab members;
 * every Tabs/Split node id is unique. An {@link DockNode.Empty} tree is legal.
 *
 * @param root tree root, never {@code null}
 */
public record DockLayout(DockNode root) {

    public DockLayout {
        Objects.requireNonNull(root, "root");
        Set<UUID> panelIds = new HashSet<>();
        Set<UUID> nodeIds = new HashSet<>();
        Deque<Visit> stack = new ArrayDeque<>();
        stack.push(new Visit(root, 1));
        int nodes = 0;
        int panels = 0;
        while (!stack.isEmpty()) {
            Visit visit = stack.pop();
            DockNode node = visit.node();
            if (visit.depth() > Limits.MAX_DEPTH) {
                throw new IllegalArgumentException("layout deeper than " + Limits.MAX_DEPTH);
            }
            nodes++;
            if (nodes > Limits.MAX_NODES) {
                throw new IllegalArgumentException("too many structural nodes: " + nodes);
            }
            if (node instanceof DockNode.Leaf leaf) {
                panels = collectPanel(leaf.panelId(), panelIds, panels);
            } else if (node instanceof DockNode.Tabs tabs) {
                requireUniqueNode(tabs.id(), nodeIds);
                for (UUID panel : tabs.panels()) {
                    panels = collectPanel(panel, panelIds, panels);
                }
            } else if (node instanceof DockNode.Split split) {
                requireUniqueNode(split.id(), nodeIds);
                stack.push(new Visit(split.second(), visit.depth() + 1));
                stack.push(new Visit(split.first(), visit.depth() + 1));
            }
        }
    }

    private static int collectPanel(UUID panelId, Set<UUID> panelIds, int panels) {
        if (!panelIds.add(panelId)) {
            throw new IllegalArgumentException("duplicate panelId in layout: " + panelId);
        }
        int total = panels + 1;
        if (total > Limits.MAX_PANELS) {
            throw new IllegalArgumentException("too many panels: " + total);
        }
        return total;
    }

    private static void requireUniqueNode(UUID nodeId, Set<UUID> nodeIds) {
        if (!nodeIds.add(nodeId)) {
            throw new IllegalArgumentException("duplicate node id in layout: " + nodeId);
        }
    }

    private record Visit(DockNode node, int depth) {}
}
