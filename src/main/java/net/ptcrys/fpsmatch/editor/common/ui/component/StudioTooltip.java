package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;

import java.util.ArrayList;
import java.util.List;

/** Tooltip content contains hidden information; visible labels and active editing stay unobscured. */
public final class StudioTooltip {

    private StudioTooltip() {}

    public static String content(Node node, String detail, boolean truncated, boolean focused) {
        if (node.role() == Role.PANEL || node.role() == Role.TEXT_FIELD && focused) return "";
        var lines = new ArrayList<String>();
        if (truncated) add(lines, node.role() == Role.TEXT_FIELD ? node.value() : node.name());
        add(lines, node.reason());
        if (detail != null && !detail.equals(node.name())) add(lines, detail);
        return String.join("\n", lines);
    }

    private static void add(List<String> lines, String value) {
        if (value == null || value.isBlank() || lines.stream().anyMatch(line -> line.contains(value))) return;
        lines.removeIf(value::contains);
        lines.add(value);
    }
}
