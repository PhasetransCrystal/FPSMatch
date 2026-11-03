package net.ptcrys.fpsmatch.editor.core.types.objects;

import java.util.List;
import java.util.UUID;

/** Ordered candidate graph. Construction performs no extension callbacks. */
public record ObjectGraph(UUID rootId, List<ObjectNode> nodes) {

    public ObjectGraph {
        if (rootId == null || nodes == null) throw ObjectMessages.node("objects", "missing");
        ObjectMessages.requireBudget("objects", nodes.size(), ObjectGraphCodec.MAX_OBJECTS);
        for (ObjectNode node : nodes) {
            if (node == null) throw ObjectMessages.node("objects", "missing_node");
        }
        nodes = List.copyOf(nodes);
    }
}
