package net.ptcrys.fpsmatch.editor.common.layout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 停靠树的全树验证：显式栈迭代，无递归，不重建也不"修复"输入的树。
 *
 * <p>
 * 规则（CONTRACTS §8、P03）：
 * <ul>
 * <li>节点数 ≤ {@value #MAX_NODES}，深度 ≤ {@value #MAX_DEPTH}（root 深度为 1）；</li>
 * <li>Tabs 自身计 1 个节点，其每个 Panel child 各计 1 个节点且深度 +1；</li>
 * <li>所有叶 Panel 的 ID 在整个子树内唯一，共享子节点在两处出现按重复叶拒绝；</li>
 * <li>叶集合中必须恰好有一个 {@code viewport}。</li>
 * </ul>
 *
 * <p>
 * 本类不提供默认布局、像素求解器或面板注册表；唯一构造为 private。
 */
public final class DockValidation {

    /** 允许的最大树深度（root 为 1）。 */
    public static final int MAX_DEPTH = 16;

    /** 允许的最大节点数（Tabs 与 Panel 都计）。 */
    public static final int MAX_NODES = 64;

    /** 必须恰好存在一个的叶面板 ID。 */
    static final String VIEWPORT_PANEL_ID = "viewport";

    private static final Pattern PANEL_ID_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    private DockValidation() {
        throw new AssertionError("net.ptcrys.fpsmatch.editor.common.layout.DockValidation is not instantiable");
    }

    /**
     * 校验面板 ID 的局部格式并原样返回；不做 trim、不自动加前缀、不按展示名生成。
     *
     * @throws IllegalArgumentException ID 为 null 或不符合 {@code [a-z][a-z0-9_.-]{0,63}}
     */
    static String requirePanelId(String id) {
        if (id == null) {
            throw new IllegalArgumentException("panel id must not be null");
        }
        if (!PANEL_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "invalid panel id (expected [a-z][a-z0-9_.-]{0,63}): '" + id + "'");
        }
        return id;
    }

    /**
     * 验证整棵（子）树。任何违规都以 {@link IllegalArgumentException} 拒绝，不做自动修复、不丢弃面板。
     *
     * @param root 待验证的树根，不能为 null
     * @throws IllegalArgumentException root 为 null、超出节点/深度预算、叶ID重复、viewport 数量不为 1
     */
    public static void validate(DockNode root) {
        if (root == null) {
            throw new IllegalArgumentException("dock tree root must not be null");
        }
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, 1));
        Set<String> leafIds = new HashSet<>();
        int nodeCount = 0;
        int viewportCount = 0;
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            DockNode node = frame.node();
            if (frame.depth() > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "dock tree exceeds MAX_DEPTH=" + MAX_DEPTH + " at node " + describe(node));
            }
            nodeCount++;
            if (nodeCount > MAX_NODES) {
                throw new IllegalArgumentException(
                        "dock tree exceeds MAX_NODES=" + MAX_NODES + " at node " + describe(node));
            }
            if (node instanceof DockNode.Panel panel) {
                if (!leafIds.add(panel.id())) {
                    throw new IllegalArgumentException("duplicate dock tree leaf panel id: '" + panel.id() + "'");
                }
                if (VIEWPORT_PANEL_ID.equals(panel.id())) {
                    viewportCount++;
                }
            } else if (node instanceof DockNode.Tabs tabs) {
                for (DockNode.Panel child : tabs.panels()) {
                    stack.push(new Frame(child, frame.depth() + 1));
                }
            } else if (node instanceof DockNode.Split split) {
                stack.push(new Frame(split.second(), frame.depth() + 1));
                stack.push(new Frame(split.first(), frame.depth() + 1));
            } else {
                throw new IllegalArgumentException(
                        "unsupported dock tree node type: " + node.getClass().getName());
            }
        }
        if (viewportCount != 1) {
            throw new IllegalArgumentException(
                    "dock tree must contain exactly one '" + VIEWPORT_PANEL_ID + "' panel, found " + viewportCount);
        }
    }

    private static String describe(DockNode node) {
        if (node instanceof DockNode.Panel panel) {
            return "panel('" + panel.id() + "')";
        }
        if (node instanceof DockNode.Tabs tabs) {
            return "tabs('" + tabs.activePanelId() + "', " + tabs.panels().size() + " panels)";
        }
        if (node instanceof DockNode.Split split) {
            return "split(" + split.axis() + ")";
        }
        return node.getClass().getSimpleName();
    }

    /** 显式栈帧：节点与其深度（root 为 1）。 */
    private record Frame(DockNode node, int depth) {}
}
