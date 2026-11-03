package net.ptcrys.fpsmatch.editor.common.layout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 不可变停靠树节点。
 *
 * <p>
 * 只承载树结构与局部合法性：面板标识、标签组活动项、分隔比例。布局树不持有文档、选择、相机或任何
 * 渲染引用；未知但格式合法的面板ID允许存在（由 UI 注册表决定是否显示 MissingPanel）。
 *
 * <p>
 * 整棵树的上限（节点数、深度、唯一 viewport、叶ID全局唯一）由 {@link DockValidation#validate} 负责，
 * 节点的 compact constructor 只做局部验证。
 */
public sealed interface DockNode permits DockNode.Panel, DockNode.Tabs, DockNode.Split {

    /** 单个面板叶。{@code id} 必须匹配 {@code [a-z][a-z0-9_.-]{0,63}}，不做 trim、不加前缀。 */
    public record Panel(String id) implements DockNode {

        public Panel {
            id = DockValidation.requirePanelId(id);
        }
    }

    /**
     * 标签组：一个 Tabs 节点在整树计数中按一个节点计，其每个 Panel child 各自再计一个节点。
     * 活动项必须在本组内；组内ID不得重复；输入列表被复制，构造后不可变。
     *
     * <p>
     * 预算局部拒绝：读取 {@code panels.size()} 后、在任何分配或遍历之前即校验
     * {@code size <= MAX_NODES - 1}（每组最多 63 个面板），因此超大或惰性列表不会被分配、也不会被逐项读取。
     */
    public record Tabs(String activePanelId, List<Panel> panels) implements DockNode {

        public Tabs {
            activePanelId = DockValidation.requirePanelId(activePanelId);
            if (panels == null) {
                throw new IllegalArgumentException("tabs panels must not be null");
            }
            int size = panels.size();
            if (size == 0) {
                throw new IllegalArgumentException("tabs must contain at least one panel");
            }
            // 必须在任何分配/遍历之前；本组最少占 1 个节点，故单组上限为 MAX_NODES - 1
            if (size > DockValidation.MAX_NODES - 1) {
                throw new IllegalArgumentException(
                        "tabs group size " + size + " exceeds per-group budget of " + (DockValidation.MAX_NODES - 1) + " panels");
            }
            List<Panel> copy = new ArrayList<>(size);
            Set<String> ids = new HashSet<>(size * 2);
            for (int i = 0; i < size; i++) {
                Panel panel = panels.get(i);
                if (panel == null) {
                    throw new IllegalArgumentException("tabs panels[" + i + "] must not be null");
                }
                if (!ids.add(panel.id())) {
                    throw new IllegalArgumentException("duplicate panel id in tabs group: '" + panel.id() + "'");
                }
                copy.add(panel);
            }
            if (!ids.contains(activePanelId)) {
                throw new IllegalArgumentException(
                        "active panel '" + activePanelId + "' does not belong to this tabs group");
            }
            panels = List.copyOf(copy);
        }
    }

    /** 二分分隔：ratio 为 first 侧占比，必须有限且落在 [0.05, 0.95] 闭区间内，不做 clamp。 */
    public record Split(Axis axis, double ratio, DockNode first, DockNode second) implements DockNode {

        public Split {
            if (axis == null) {
                throw new IllegalArgumentException("split axis must not be null");
            }
            if (!Double.isFinite(ratio)) {
                throw new IllegalArgumentException("split ratio must be finite, but was " + ratio);
            }
            if (ratio < 0.05D || ratio > 0.95D) {
                throw new IllegalArgumentException("split ratio must be within [0.05, 0.95], but was " + ratio);
            }
            if (first == null) {
                throw new IllegalArgumentException("split first must not be null");
            }
            if (second == null) {
                throw new IllegalArgumentException("split second must not be null");
            }
        }
    }

    /** 分隔方向。 */
    public enum Axis {
        HORIZONTAL,
        VERTICAL
    }
}
