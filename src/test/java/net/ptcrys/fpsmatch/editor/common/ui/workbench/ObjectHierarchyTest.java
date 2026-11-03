package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor.Node;
import org.junit.jupiter.api.Test;

class ObjectHierarchyTest {
    @Test void unorderedInputProjectsActualOwnershipAndDepth() {
        var hierarchy = new ObjectHierarchy(List.of(node("leaf", "branch"), node("other", null),
                node("branch", "root"), node("root", null), node("sibling", "root")));
        var rows = hierarchy.rows(Set.of(), "", Node::id);
        assertEquals(List.of("other", "root", "branch", "leaf", "sibling"), ids(rows));
        assertEquals(List.of(0, 0, 1, 2, 1), rows.stream().map(ObjectHierarchy.Row::depth).toList());
        assertEquals(List.of("branch", "root"), hierarchy.ancestors("leaf"));
        assertTrue(rows.get(1).branch()); assertTrue(rows.get(2).expanded());
        assertFalse(rows.get(3).branch());
    }

    @Test void collapsedBranchesHideOnlyTheirDescendantsAndSearchRetainsTheWholeAncestorChain() {
        var hierarchy = new ObjectHierarchy(List.of(node("root", null), node("branch", "root"),
                node("needle", "branch"), node("sibling", "root"), node("other", null)));
        var collapsed = hierarchy.rows(Set.of("branch"), "", Node::id);
        assertEquals(List.of("root", "branch", "sibling", "other"), ids(collapsed));
        assertFalse(collapsed.get(1).expanded());
        assertEquals(List.of("root", "other"), ids(hierarchy.rows(Set.of("root"), "", Node::id)));
        var found = hierarchy.rows(Set.of("root", "branch"), "  NEEDLE  ", Node::id);
        assertEquals(List.of("root", "branch", "needle"), ids(found));
        assertEquals(List.of(0, 1, 2), found.stream().map(ObjectHierarchy.Row::depth).toList());
        assertTrue(found.stream().allMatch(ObjectHierarchy.Row::expanded));
        assertTrue(hierarchy.rows(Set.of(), "absent", Node::id).isEmpty());
        assertEquals(5, hierarchy.rows(Set.of(), "", Node::id).size());
    }

    @Test void rejectsMissingParentsDuplicateIdentitiesAndCyclesIncludingDisconnectedCycles() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectHierarchy(List.of(node("child", "missing"))));
        assertThrows(IllegalArgumentException.class, () -> new ObjectHierarchy(List.of(node("same", null), node("same", null))));
        assertThrows(IllegalArgumentException.class, () -> new ObjectHierarchy(List.of(node("self", "self"))));
        assertThrows(IllegalArgumentException.class, () -> new ObjectHierarchy(List.of(
                node("root", null), node("a", "b"), node("b", "a"))));
    }

    @Test void fullBudgetDeepChainBuildsSearchesAndFindsAncestorsWithoutRecursion() {
        List<Node> source = new ArrayList<>();
        for (int i = 8191; i >= 0; i--) source.add(node("node" + i, i == 0 ? null : "node" + (i - 1)));
        var hierarchy = assertDoesNotThrow(() -> new ObjectHierarchy(source));
        var rows = assertDoesNotThrow(() -> hierarchy.rows(Set.of("node0"), "node8191", Node::id));
        assertEquals(8192, rows.size());
        assertEquals("node0", rows.get(0).node().id());
        assertEquals(8191, rows.get(8191).depth());
        var ancestors = assertDoesNotThrow(() -> hierarchy.ancestors("node8191"));
        assertEquals(8191, ancestors.size());
        assertEquals("node8190", ancestors.get(0));
        assertEquals("node0", ancestors.get(8190));
        assertEquals(1, hierarchy.rows(Set.of("node0"), "", Node::id).size());
        source.add(node("extra", null));
        assertThrows(IllegalArgumentException.class, () -> new ObjectHierarchy(source));
    }

    private static Node node(String id, String parent) {
        return new Node(id, Optional.ofNullable(parent), new TextContent.Literal(id));
    }
    private static List<String> ids(List<ObjectHierarchy.Row> rows) {
        return rows.stream().map(row -> row.node().id()).toList();
    }
}
