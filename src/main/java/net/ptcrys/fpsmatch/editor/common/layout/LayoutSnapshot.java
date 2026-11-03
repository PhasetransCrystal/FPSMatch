package net.ptcrys.fpsmatch.editor.common.layout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * R16.2 停靠求解快照：一次 {@link DockSolver#solve} 的完整、不可变输出。
 *
 * <p>
 * 纯逻辑矩形（root-local，原点 0）。{@link Bounds} 是工作台布局矩形，不是第二份
 * {@code ScaleTransform}，也不能直接当作真实 FBO 视口；真实 viewport 由 native 测量桥接既有的
 * {@code ViewportLayout}。本类型不持有文档、选择、相机或任何渲染引用。
 *
 * <p>
 * 构造期只做成员局部合法性：引用非 null、{@link Bounds} 全有限且宽高非负、列表预算与防御复制、面板 ID
 * 语法与列表内部重复。它不要求调用者传入的集合对应某个已知停靠树，也不查面板注册表；包含关系、不重叠、
 * 可交互性等完整 solver 输出关系由正式测试核验。
 *
 * @param mode             布局模式
 * @param menuBar          菜单栏矩形
 * @param documentBar      文档栏矩形
 * @param toolbar          工具栏矩形
 * @param banner           横幅矩形（仅在有横幅时存在）
 * @param content          正文区矩形（面板、视口与抽屉的唯一容器）
 * @param statusBar        状态栏矩形（固定底部）
 * @param viewport         视口矩形（TOO_SMALL 时等于 content，但不可交互）
 * @param panels           全部叶面板盒子（含不可见/被收起项，按原树叶顺序）
 * @param tabs             标签组盒子
 * @param splitters        分隔条盒子
 * @param drawer           抽屉（最多一个）
 * @param overflowPanelIds 溢出入口面板 ID（不可见非视口叶，按原树叶顺序去重）
 */
public record LayoutSnapshot(Mode mode, Bounds menuBar, Bounds documentBar, Bounds toolbar,
                             Optional<Bounds> banner, Bounds content, Bounds statusBar, Bounds viewport, List<PanelBox> panels,
                             List<TabBox> tabs, List<SplitBox> splitters, Optional<DrawerBox> drawer,
                             List<String> overflowPanelIds) {

    /** 任意列表的硬上限（与冻结树 {@code MAX_NODES} 同量级）。 */
    private static final int MAX_LIST_ENTRIES = 64;

    /** nodePath 的硬上限（与冻结树 {@code MAX_DEPTH} 同量级）。 */
    private static final int MAX_NODE_PATH = 16;

    public LayoutSnapshot {
        if (mode == null) {
            throw new IllegalArgumentException("snapshot mode must not be null");
        }
        menuBar = requireBounds(menuBar, "menuBar");
        documentBar = requireBounds(documentBar, "documentBar");
        toolbar = requireBounds(toolbar, "toolbar");
        content = requireBounds(content, "content");
        statusBar = requireBounds(statusBar, "statusBar");
        viewport = requireBounds(viewport, "viewport");
        banner = requireOptionalBounds(banner, "banner");
        drawer = requireOptionalDrawer(drawer, "drawer");
        panels = copyPanelBoxes(panels);
        tabs = copyTabBoxes(tabs);
        splitters = copySplitBoxes(splitters);
        overflowPanelIds = copyPanelIds(overflowPanelIds, "overflowPanelIds");
    }

    /**
     * 先按 {@code size()} 核预算、再复制：超预算的（可能惰性或巨大的）列表在任何分配或逐项读取之前即被拒绝。
     */
    private static int requireSize(List<?> source, String name) {
        if (source == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        int size = source.size();
        if (size > MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException(
                    name + " size " + size + " exceeds budget " + MAX_LIST_ENTRIES);
        }
        return size;
    }

    private static Bounds requireBounds(Bounds value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private static Optional<Bounds> requireOptionalBounds(Optional<Bounds> value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.isPresent() && value.get() == null) {
            throw new IllegalArgumentException(name + " must not be a present-null optional");
        }
        return value;
    }

    private static Optional<DrawerBox> requireOptionalDrawer(Optional<DrawerBox> value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.isPresent() && value.get() == null) {
            throw new IllegalArgumentException(name + " must not be a present-null optional");
        }
        return value;
    }

    private static List<PanelBox> copyPanelBoxes(List<PanelBox> source) {
        String name = "panels";
        int size = requireSize(source, name);
        List<PanelBox> copy = new ArrayList<>(size);
        Set<String> ids = new HashSet<>(size * 2);
        for (int i = 0; i < size; i++) {
            PanelBox box = source.get(i);
            if (box == null) {
                throw new IllegalArgumentException(name + "[" + i + "] must not be null");
            }
            if (!ids.add(box.panelId())) {
                throw new IllegalArgumentException(
                        name + " contains duplicate panel id '" + box.panelId() + "'");
            }
            copy.add(box);
        }
        return List.copyOf(copy);
    }

    private static List<TabBox> copyTabBoxes(List<TabBox> source) {
        String name = "tabs";
        int size = requireSize(source, name);
        List<TabBox> copy = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            TabBox box = source.get(i);
            if (box == null) {
                throw new IllegalArgumentException(name + "[" + i + "] must not be null");
            }
            copy.add(box);
        }
        return List.copyOf(copy);
    }

    private static List<SplitBox> copySplitBoxes(List<SplitBox> source) {
        String name = "splitters";
        int size = requireSize(source, name);
        List<SplitBox> copy = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            SplitBox box = source.get(i);
            if (box == null) {
                throw new IllegalArgumentException(name + "[" + i + "] must not be null");
            }
            copy.add(box);
        }
        return List.copyOf(copy);
    }

    /** 溢出入口 ID：语法合法、非 null、按原树叶顺序去重。 */
    private static List<String> copyPanelIds(List<String> source, String name) {
        int size = requireSize(source, name);
        List<String> copy = new ArrayList<>(size);
        Set<String> seen = new HashSet<>(size * 2);
        for (int i = 0; i < size; i++) {
            String id = DockValidation.requirePanelId(source.get(i));
            if (!seen.add(id)) {
                throw new IllegalArgumentException(name + " contains duplicate panel id '" + id + "'");
            }
            copy.add(id);
        }
        return List.copyOf(copy);
    }

    /** 布局模式。 */
    public enum Mode {
        /** 完整停靠：所有分割器与可见面板都参与求解。 */
        DOCKED,
        /** compact：正文仍全尺寸视口，面板按固定 side 规则放入最多一个抽屉。 */
        COMPACT,
        /** 窗口过小：只保留不可交互的视口占位与 chrome。 */
        TOO_SMALL
    }

    /**
     * root-local 逻辑矩形。{@code left}/{@code top} 可为负（仅当调用方构造非内容矩形），但必须有限；
     * {@code width}/{@code height} 必须有限且非负。
     *
     * <p>
     * 此外右下边界 {@code left + width} 与 {@code top + height} 也必须是有限 double：四个分量各自有限
     * 并不足以保证边界有限（{@code Double.MAX_VALUE + Double.MAX_VALUE} 会溢出为无穷）。合法的巨大有限
     * 边界（例如 {@code 1e100 + 1e100 = 2e100}）一律保留，本类不做任何 clamp，也不设任意尺寸上限。
     */
    public record Bounds(double left, double top, double width, double height) {

        public Bounds {
            if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(width) || !Double.isFinite(height)) {
                throw new IllegalArgumentException(
                        "bounds must be finite, but was (" + left + ", " + top + ", " + width + ", " + height + ")");
            }
            if (width < 0.0D || height < 0.0D) {
                throw new IllegalArgumentException(
                        "bounds width/height must be non-negative, but was (" + width + ", " + height + ")");
            }
            if (!Double.isFinite(left + width)) {
                throw new IllegalArgumentException("bounds right edge left + width must be finite, but was " + (left + width) + " for (" + left + ", " + top + ", " + width + ", " + height + ")");
            }
            if (!Double.isFinite(top + height)) {
                throw new IllegalArgumentException("bounds bottom edge top + height must be finite, but was " + (top + height) + " for (" + left + ", " + top + ", " + width + ", " + height + ")");
            }
        }

        /** 右边界（不含）。 */
        public double right() {
            return left + width;
        }

        /** 下边界（不含）。 */
        public double bottom() {
            return top + height;
        }

        /**
         * 命中测试：左上含、右下不含；零宽或零高恒为 {@code false}（空矩形不覆盖任何点）。
         *
         * @throws IllegalArgumentException 坐标非有限
         */
        public boolean contains(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("contains point must be finite: (" + x + ", " + y + ")");
            }
            if (width == 0.0D || height == 0.0D) {
                return false;
            }
            return x >= left && x < right() && y >= top && y < bottom();
        }
    }

    /**
     * 单叶面板盒子。
     *
     * <p>
     * {@code bounds} 的含义按可见性区分，并非「不可见即空」：
     * <ul>
     * <li>{@code visible=true}：该叶当前实际占用的矩形。</li>
     * <li>不可见且位于 Tabs 组内（非活动成员）：取该组正文矩形（标签头下方的 body），仅 {@code visible}
     * 为 {@code false}；成员仍可被 restore 命中，故保留真实 body 而非空矩形。</li>
     * <li>不可见且已被收起（prune）、被 COMPACT 隐藏或处于 TOO_SMALL：才是 {@code 0×0} 空矩形
     * （content 左上角），仅保留 ID 以便 restore 兼容。</li>
     * </ul>
     */
    public record PanelBox(String panelId, Bounds bounds, boolean visible) {

        public PanelBox {
            panelId = DockValidation.requirePanelId(panelId);
            if (bounds == null) {
                throw new IllegalArgumentException("panel box bounds must not be null");
            }
        }
    }

    /** 标签组盒子：{@code panelIds} 总序为原树顺序，{@code activePanelId} 必须在其中。 */
    public record TabBox(List<String> panelIds, String activePanelId, Bounds header) {

        public TabBox {
            activePanelId = DockValidation.requirePanelId(activePanelId);
            if (header == null) {
                throw new IllegalArgumentException("tab box header must not be null");
            }
            int size = requireSize(panelIds, "tab box panelIds");
            if (size == 0) {
                throw new IllegalArgumentException("tab box must contain at least one panel");
            }
            List<String> copy = new ArrayList<>(size);
            Set<String> ids = new HashSet<>(size * 2);
            for (int i = 0; i < size; i++) {
                String id = DockValidation.requirePanelId(panelIds.get(i));
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("duplicate panel id in tab box: '" + id + "'");
                }
                copy.add(id);
            }
            if (!ids.contains(activePanelId)) {
                throw new IllegalArgumentException(
                        "tab box active panel '" + activePanelId + "' does not belong to this group");
            }
            panelIds = List.copyOf(copy);
        }
    }

    /**
     * 分隔条盒子。{@code nodePath} 根为空列表，每步 {@code false}=first、{@code true}=second，
     * 始终指向<b>原树</b>路径；私有 prune 后不因"剩第几个 split"重新编号。
     */
    public record SplitBox(List<Boolean> nodePath, DockNode.Axis axis, Bounds bounds) {

        public SplitBox {
            if (axis == null) {
                throw new IllegalArgumentException("split box axis must not be null");
            }
            if (bounds == null) {
                throw new IllegalArgumentException("split box bounds must not be null");
            }
            int size = requireSize(nodePath, "split box nodePath");
            if (size > MAX_NODE_PATH) {
                throw new IllegalArgumentException(
                        "split box nodePath length " + size + " exceeds budget " + MAX_NODE_PATH);
            }
            List<Boolean> copy = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Boolean step = nodePath.get(i);
                if (step == null) {
                    throw new IllegalArgumentException("split box nodePath[" + i + "] must not be null");
                }
                copy.add(step);
            }
            nodePath = List.copyOf(copy);
        }
    }

    /** 抽屉（overlay）：最多一个，{@code scrim} 为 content。 */
    public record DrawerBox(String panelId, DrawerSide side, Bounds bounds, Bounds scrim) {

        public DrawerBox {
            panelId = DockValidation.requirePanelId(panelId);
            if (side == null) {
                throw new IllegalArgumentException("drawer side must not be null");
            }
            if (bounds == null) {
                throw new IllegalArgumentException("drawer bounds must not be null");
            }
            if (scrim == null) {
                throw new IllegalArgumentException("drawer scrim must not be null");
            }
        }
    }

    /** 抽屉停靠边。 */
    public enum DrawerSide {
        LEFT,
        RIGHT,
        BOTTOM
    }
}
