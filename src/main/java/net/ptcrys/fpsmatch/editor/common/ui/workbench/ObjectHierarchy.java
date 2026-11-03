package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor.Node;

import java.util.*;
import java.util.function.Function;

/** Bounded iterative ownership tree projection; display order never becomes an object address. */
public final class ObjectHierarchy {

    public record Row(Node node, int depth, boolean branch, boolean expanded) {}

    private record Entry(Node node, int depth) {}

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<Node>> children = new HashMap<>();
    private final List<Node> roots = new ArrayList<>();
    private final List<Entry> ordered = new ArrayList<>();

    public ObjectHierarchy(List<Node> source) {
        if (source.size() > 8192) throw new IllegalArgumentException("object tree budget");
        for (Node node : source) if (nodes.putIfAbsent(node.id(), node) != null) throw new IllegalArgumentException("duplicate tree identity");
        for (Node node : source) {
            if (node.parent().isEmpty()) roots.add(node);
            else {
                String parent = node.parent().orElseThrow();
                if (!nodes.containsKey(parent)) throw new IllegalArgumentException("missing tree parent");
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
            }
        }
        Deque<Entry> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(new Entry(roots.get(i), 0));
        Set<String> reached = new HashSet<>();
        while (!stack.isEmpty()) {
            Entry entry = stack.pop();
            if (!reached.add(entry.node.id())) throw new IllegalArgumentException("ownership cycle");
            ordered.add(entry);
            List<Node> own = children.getOrDefault(entry.node.id(), List.of());
            for (int i = own.size() - 1; i >= 0; i--) stack.push(new Entry(own.get(i), entry.depth + 1));
        }
        if (reached.size() != nodes.size()) throw new IllegalArgumentException("unreachable ownership cycle");
    }

    public List<Row> rows(Set<String> collapsed, String query, Function<Node, String> label) {
        String search = query.strip().toLowerCase(Locale.ROOT);
        Set<String> matches = new HashSet<>();
        if (!search.isEmpty()) for (Entry entry : ordered)
            if (label.apply(entry.node).toLowerCase(Locale.ROOT).contains(search)) matches.add(entry.node.id());
        // Reverse preorder propagates matches to every ancestor in linear time.
        if (!search.isEmpty()) for (int i = ordered.size() - 1; i >= 0; i--) {
            Node node = ordered.get(i).node;
            if (matches.contains(node.id())) node.parent().ifPresent(matches::add);
        }
        List<Row> result = new ArrayList<>();
        int hiddenBelow = Integer.MAX_VALUE;
        for (Entry entry : ordered) {
            if (entry.depth <= hiddenBelow) hiddenBelow = Integer.MAX_VALUE;
            if (entry.depth > hiddenBelow || !search.isEmpty() && !matches.contains(entry.node.id())) continue;
            boolean branch = children.containsKey(entry.node.id()), expanded = !search.isEmpty() || !collapsed.contains(entry.node.id());
            result.add(new Row(entry.node, entry.depth, branch, expanded));
            if (branch && !expanded) hiddenBelow = entry.depth;
        }
        return List.copyOf(result);
    }

    public List<String> ancestors(String id) {
        List<String> result = new ArrayList<>();
        Node node = nodes.get(id);
        while (node != null && node.parent().isPresent()) {
            String parent = node.parent().orElseThrow();
            result.add(parent);
            node = nodes.get(parent);
        }
        return List.copyOf(result);
    }
}
