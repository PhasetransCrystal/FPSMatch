package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.List;
import java.util.Objects;

/**
 * An immutable solve result.
 *
 * @param fits      whether {@code content} covers the whole tree minimum and every produced
 *                  rectangle is finite, non-negative and inside {@code content}
 * @param panels    panel bodies in tree traversal order
 * @param tabs      tab headers in tree traversal order
 * @param splitters splitter strips in tree traversal order
 */
public record DockProjection(boolean fits, List<PanelBox> panels, List<TabBox> tabs, List<SplitBox> splitters) {

    public DockProjection {
        panels = copy(panels, "panels");
        tabs = copy(tabs, "tabs");
        splitters = copy(splitters, "splitters");
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " element");
        }
        return List.copyOf(values);
    }
}
