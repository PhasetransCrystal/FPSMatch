package net.ptcrys.fpsmatch.editor.common.layout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * R16.2 停靠布局求解器：把冻结的 {@link DockLayout} + 窗口逻辑尺寸投影为一次性的
 * {@link LayoutSnapshot}。
 *
 * <p>
 * 纯算法：不读 JSON、不访问面板注册表、不持有文档/渲染引用、不修改输入树（不改 Tabs stored active、
 * 不改 Split ratio、不写回任何持久布局）。默认树与降级规则是任务卡固定值，本类不做自适应缩放或字号兜底。
 *
 * <p>
 * chrome 固定：menu 26 / document 30 / toolbar 34 / status 24（固定底部）、banner 可见时 32，所有矩形
 * root-local、原点 0；顶部分段按序取 {@code min(目标高, 剩余高)}，正文区高度非负。
 *
 * <p>
 * 模式：{@code W < 640 || H < 360}（或 banner 让正文可用高/宽低于视口最低尺寸）返回
 * {@link LayoutSnapshot.Mode#TOO_SMALL}；否则 {@code W < 980 || H < 650} 或树最小尺寸装不下时返回
 * {@link LayoutSnapshot.Mode#COMPACT}；其余 {@link LayoutSnapshot.Mode#DOCKED}。
 */
public final class DockSolver {

    /** 菜单栏高度。 */
    static final double MENU_BAR_HEIGHT = 26.0D;
    /** 文档栏高度。 */
    static final double DOCUMENT_BAR_HEIGHT = 30.0D;
    /** 工具栏高度。 */
    static final double TOOLBAR_HEIGHT = 34.0D;
    /** 状态栏高度（固定底部）。 */
    static final double STATUS_BAR_HEIGHT = 24.0D;
    /** 横幅高度（可见时）。 */
    static final double BANNER_HEIGHT = 32.0D;
    /** 无横幅时正文区顶部高度（26 + 30 + 34）。 */
    static final double CONTENT_TOP_WITHOUT_BANNER = 90.0D;

    /** TOO_SMALL 宽度阈值。 */
    static final double MIN_WINDOW_WIDTH = 640.0D;
    /** TOO_SMALL 高度阈值。 */
    static final double MIN_WINDOW_HEIGHT = 360.0D;
    /** COMPACT 宽度阈值。 */
    static final double COMPACT_WINDOW_WIDTH = 980.0D;
    /** COMPACT 高度阈值。 */
    static final double COMPACT_WINDOW_HEIGHT = 650.0D;
    /** 视口最低尺寸。 */
    static final double MIN_VIEWPORT_WIDTH = 320.0D;
    /** 视口最低高度。 */
    static final double MIN_VIEWPORT_HEIGHT = 220.0D;

    /** 分隔条厚度。 */
    static final double SPLITTER = 4.0D;
    /** 标签头高度。 */
    static final double TAB_HEADER_HEIGHT = 28.0D;

    /** 固定面板 ID：层次、视口、属性、问题。 */
    static final String HIERARCHY_PANEL_ID = "hierarchy";
    static final String VIEWPORT_PANEL_ID = "viewport";
    static final String PROPERTIES_PANEL_ID = "properties";
    static final String PROBLEMS_PANEL_ID = "problems";
    /** 项目面板 ID（LEFT 收起顺序第二名）。 */
    static final String PROJECT_PANEL_ID = "project";
    /** 详情面板 ID。 */
    static final String DETAILS_PANEL_ID = "details";

    /** BOTTOM 角色：既决定 compact 抽屉边，也决定收紧顺序与 soft 上限例外。 */
    static final List<String> BOTTOM_ROLE_PANEL_IDS = List.of(
            PROBLEMS_PANEL_ID, "events", "watch", "test-controls", "operation-history", "operation-result");
    /** LEFT 角色（非 unknown 部分）的收紧顺序。 */
    static final List<String> LEFT_ROLE_PANEL_IDS = List.of(HIERARCHY_PANEL_ID, PROJECT_PANEL_ID);

    /** 侧栏面板（properties/details）最大尺寸与最低尺寸都更大。 */
    static final double SIDE_PANEL_MIN_WIDTH = 260.0D;
    /** 普通面板最低宽度。 */
    static final double PLAIN_PANEL_MIN_WIDTH = 180.0D;
    /** 普通面板最低高度。 */
    static final double PLAIN_PANEL_MIN_HEIGHT = 120.0D;
    /** properties/details 的 horizontal soft 上限。 */
    static final double SIDE_PANEL_SOFT_CAP = 420.0D;
    /** 其他面板的 horizontal soft 上限。 */
    static final double PLAIN_PANEL_SOFT_CAP = 360.0D;
    /** vertical soft 上限下限。 */
    static final double VERTICAL_SOFT_CAP_FLOOR = 240.0D;

    /** 默认左栏宽（宽窗口 / 窄窗口）。 */
    static final double DEFAULT_LEFT_WIDE = 220.0D;
    static final double DEFAULT_LEFT_NARROW = 180.0D;
    /** 默认右栏宽（宽窗口 / 窄窗口）。 */
    static final double DEFAULT_RIGHT_WIDE = 300.0D;
    static final double DEFAULT_RIGHT_NARROW = 260.0D;
    /** 默认底栏高（高窗口 / 矮窗口）。 */
    static final double DEFAULT_BOTTOM_TALL = 240.0D;
    static final double DEFAULT_BOTTOM_SHORT = 160.0D;
    /** 默认树 ratio 的标称窗口尺寸。 */
    static final double DEFAULT_NOMINAL_WIDTH = 980.0D;
    static final double DEFAULT_NOMINAL_HEIGHT = 536.0D;
    /** ratio clamp 区间（只最终 clamp 一次）。 */
    static final double MIN_RATIO = 0.05D;
    static final double MAX_RATIO = 0.95D;

    private DockSolver() {
        throw new AssertionError("net.ptcrys.fpsmatch.editor.common.layout.DockSolver is not instantiable");
    }

    /**
     * 默认布局：hierarchy | (viewport / problems) | properties，4 叶且唯一 viewport。
     *
     * <p>
     * ratio 以标称正文区（{@code max(W, 980)} × {@code max(H - 114, 536)}）计算，且每个 ratio 只在最后
     * clamp 一次；尺寸本身不 clamp，极窄/极小的合法输入也会返回结构合法的树。
     *
     * @throws IllegalArgumentException width/height 非有限或为负
     */
    public static DockLayout defaultLayout(double width, double height) {
        requireFiniteNonNegative(width, "width");
        requireFiniteNonNegative(height, "height");
        boolean wide = width >= 1180.0D;
        double left = wide ? DEFAULT_LEFT_WIDE : DEFAULT_LEFT_NARROW;
        double right = wide ? DEFAULT_RIGHT_WIDE : DEFAULT_RIGHT_NARROW;
        double bottom = height >= 900.0D ? DEFAULT_BOTTOM_TALL : DEFAULT_BOTTOM_SHORT;
        double nominalWidth = Math.max(width, DEFAULT_NOMINAL_WIDTH);
        double nominalHeight = Math.max(height - (CONTENT_TOP_WITHOUT_BANNER + STATUS_BAR_HEIGHT),
                DEFAULT_NOMINAL_HEIGHT);
        double rootRatio = clamp(left / (nominalWidth - SPLITTER), MIN_RATIO, MAX_RATIO);
        double remainingWidth = nominalWidth - left - SPLITTER;
        double innerHorizontalRatio = clamp(
                (remainingWidth - right - SPLITTER) / (remainingWidth - SPLITTER), MIN_RATIO, MAX_RATIO);
        double innerVerticalRatio = clamp(
                (nominalHeight - bottom - SPLITTER) / (nominalHeight - SPLITTER), MIN_RATIO, MAX_RATIO);
        DockNode inner = new DockNode.Split(DockNode.Axis.VERTICAL, innerVerticalRatio,
                new DockNode.Panel(VIEWPORT_PANEL_ID), new DockNode.Panel(PROBLEMS_PANEL_ID));
        DockNode middle = new DockNode.Split(DockNode.Axis.HORIZONTAL, innerHorizontalRatio, inner,
                new DockNode.Panel(PROPERTIES_PANEL_ID));
        DockNode root = new DockNode.Split(DockNode.Axis.HORIZONTAL, rootRatio,
                new DockNode.Panel(HIERARCHY_PANEL_ID), middle);
        return new DockLayout(DockLayout.SCHEMA_VERSION, root);
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, but was " + value);
        }
        if (value < 0.0D) {
            throw new IllegalArgumentException(name + " must be non-negative, but was " + value);
        }
    }

    /** ordinary double clamp；{@code lo > hi} 时返回 {@code lo}（正式路径不会出现该情况）。 */
    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    // __R16_2_APPEND__

    /**
     * 求解一次停靠投影：把冻结的树与逻辑窗口尺寸投影为不可变快照。
     *
     * <p>
     * 输入树只读：不写回 stored active、不改 ratio、不生成新的持久 {@link DockLayout}；compact 与收起
     * 都只作用于私有投影。view 永不消失：任何叶 ID 都出现在 {@code panels} 或 {@code overflowPanelIds}。
     *
     * @param layout        冻结树（root 非 null）
     * @param width         逻辑窗口宽，非有限或负则 IAE（0 合法）
     * @param height        逻辑窗口高，非有限或负则 IAE（0 合法）
     * @param bannerVisible 横幅是否可见（可见时正文区再下移 32）
     * @param drawerPanelId 请求以 overlay 抽屉打开的不可见面板；必须存在于树且不是 viewport
     * @throws IllegalArgumentException 参数为 null、尺寸非有限/负、抽屉 ID 不在树中或等于 viewport
     */
    public static LayoutSnapshot solve(DockLayout layout, double width, double height,
                                       boolean bannerVisible, Optional<String> drawerPanelId) {
        requireFiniteNonNegative(width, "width");
        requireFiniteNonNegative(height, "height");
        if (layout == null) {
            throw new IllegalArgumentException("layout must not be null");
        }
        if (drawerPanelId == null) {
            throw new IllegalArgumentException("drawerPanelId must not be null");
        }
        DockNode root = layout.root();
        if (root == null) {
            throw new IllegalArgumentException("layout root must not be null");
        }

        // 固定 chrome：顶部分段按 26/30/34/32 顺序各取 min(目标高, 剩余高)，footer 固定底部，正文区高度非负。
        double top = 0.0D;
        double remaining = height;
        LayoutSnapshot.Bounds menuBar = new LayoutSnapshot.Bounds(0.0D, top, width,
                Math.min(MENU_BAR_HEIGHT, remaining));
        top += menuBar.height();
        remaining = Math.max(0.0D, height - top);
        LayoutSnapshot.Bounds documentBar = new LayoutSnapshot.Bounds(0.0D, top, width,
                Math.min(DOCUMENT_BAR_HEIGHT, remaining));
        top += documentBar.height();
        remaining = Math.max(0.0D, height - top);
        LayoutSnapshot.Bounds toolbar = new LayoutSnapshot.Bounds(0.0D, top, width,
                Math.min(TOOLBAR_HEIGHT, remaining));
        top += toolbar.height();
        remaining = Math.max(0.0D, height - top);
        Optional<LayoutSnapshot.Bounds> banner;
        if (bannerVisible) {
            LayoutSnapshot.Bounds bannerBounds = new LayoutSnapshot.Bounds(0.0D, top, width,
                    Math.min(BANNER_HEIGHT, remaining));
            top += bannerBounds.height();
            remaining = Math.max(0.0D, height - top);
            banner = Optional.of(bannerBounds);
        } else {
            banner = Optional.empty();
        }
        double statusHeight = Math.min(STATUS_BAR_HEIGHT, remaining);
        LayoutSnapshot.Bounds statusBar = new LayoutSnapshot.Bounds(0.0D, height - statusHeight, width,
                statusHeight);
        LayoutSnapshot.Bounds content = new LayoutSnapshot.Bounds(0.0D, top, width,
                Math.max(0.0D, remaining - statusHeight));

        List<String> leafOrder = new ArrayList<>();
        collectLeaves(root, leafOrder);

        String requestedDrawerId = null;
        if (drawerPanelId.isPresent()) {
            requestedDrawerId = drawerPanelId.get();
            if (requestedDrawerId == null) {
                throw new IllegalArgumentException("drawerPanelId must not contain null");
            }
            if (VIEWPORT_PANEL_ID.equals(requestedDrawerId)) {
                throw new IllegalArgumentException(
                        "drawerPanelId must not be the '" + VIEWPORT_PANEL_ID + "' panel");
            }
            if (!leafOrder.contains(requestedDrawerId)) {
                throw new IllegalArgumentException(
                        "drawerPanelId '" + requestedDrawerId + "' is not part of the layout tree");
            }
        }

        boolean tooSmall = width < MIN_WINDOW_WIDTH || height < MIN_WINDOW_HEIGHT || content.width() < MIN_VIEWPORT_WIDTH || content.height() < MIN_VIEWPORT_HEIGHT;
        if (tooSmall) {
            return buildTooSmall(menuBar, documentBar, toolbar, banner, content, statusBar, leafOrder);
        }

        Projection projection = null;
        if (width >= COMPACT_WINDOW_WIDTH && height >= COMPACT_WINDOW_HEIGHT) {
            projection = fit(project(root), content, leafOrder);
        }
        if (projection == null) {
            return buildCompact(menuBar, documentBar, toolbar, banner, content, statusBar, leafOrder,
                    requestedDrawerId);
        }
        return buildDocked(menuBar, documentBar, toolbar, banner, content, statusBar, leafOrder,
                projection, requestedDrawerId);
    }

    // __R16_2_PROJECTION__

    /** 私有投影节点：只承载求解所需的身份/最小尺寸/比例，绝不回写原树。 */
    private sealed interface Projection permits ProjectionLeaf, ProjectionTabs, ProjectionSplit {}

    /** 单面板叶。 */
    private record ProjectionLeaf(String id) implements Projection {}

    /** 标签组；{@code storedActiveId} 原样保留，有效 active 由 {@link #effectiveActive} 派生。 */
    private record ProjectionTabs(List<String> panelIds, String storedActiveId) implements Projection {}

    /**
     * 二分分隔，ratio 原样保留。
     *
     * <p>
     * {@code nodePath} 是该 split 在<b>原树</b>中的身份（根为空，每步 {@code false}=first、
     * {@code true}=second）：私有 prune 会折叠节点，但存活的 split 仍带着自己原来的路径，不因「剩第几个
     * split」而换 identity。
     */
    private record ProjectionSplit(List<Boolean> nodePath, DockNode.Axis axis, double ratio,
                                   Projection first, Projection second)
            implements Projection {}

    /** 按 first/second、Tabs 组内顺序登记原树叶 ID（原序，用于 panels/overflow 顺序与未知 ID 保留）。 */
    private static void collectLeaves(DockNode node, List<String> out) {
        if (node instanceof DockNode.Panel panel) {
            out.add(panel.id());
            return;
        }
        if (node instanceof DockNode.Tabs tabs) {
            for (DockNode.Panel child : tabs.panels()) {
                out.add(child.id());
            }
            return;
        }
        DockNode.Split split = (DockNode.Split) node;
        collectLeaves(split.first(), out);
        collectLeaves(split.second(), out);
    }

    /** 从冻结树构建私有投影；不修改输入。 */
    private static Projection project(DockNode node) {
        return project(node, List.of());
    }

    /** 递归构建私有投影，同时记录每个 split 在原树中的路径。 */
    private static Projection project(DockNode node, List<Boolean> nodePath) {
        if (node instanceof DockNode.Panel panel) {
            return new ProjectionLeaf(panel.id());
        }
        if (node instanceof DockNode.Tabs tabs) {
            List<String> ids = new ArrayList<>(tabs.panels().size());
            for (DockNode.Panel child : tabs.panels()) {
                ids.add(child.id());
            }
            return new ProjectionTabs(List.copyOf(ids), tabs.activePanelId());
        }
        DockNode.Split split = (DockNode.Split) node;
        return new ProjectionSplit(nodePath, split.axis(), split.ratio(),
                project(split.first(), childPath(nodePath, false)),
                project(split.second(), childPath(nodePath, true)));
    }

    /** 原树路径扩展一步；根路径为空列表。 */
    private static List<Boolean> childPath(List<Boolean> nodePath, boolean second) {
        List<Boolean> out = new ArrayList<>(nodePath.size() + 1);
        out.addAll(nodePath);
        out.add(second);
        return List.copyOf(out);
    }

    /** 有效 active：组内含 viewport 则强制 viewport（stored active 不改），否则 stored 仍在组内保留、被裁剪回退首项。 */
    private static String effectiveActive(ProjectionTabs group) {
        List<String> ids = group.panelIds();
        if (ids.contains(VIEWPORT_PANEL_ID)) {
            return VIEWPORT_PANEL_ID;
        }
        if (ids.contains(group.storedActiveId())) {
            return group.storedActiveId();
        }
        return ids.get(0);
    }

    private static void projectionLeaves(Projection node, List<String> out) {
        if (node instanceof ProjectionLeaf leaf) {
            out.add(leaf.id());
            return;
        }
        if (node instanceof ProjectionTabs group) {
            out.addAll(group.panelIds());
            return;
        }
        ProjectionSplit split = (ProjectionSplit) node;
        projectionLeaves(split.first(), out);
        projectionLeaves(split.second(), out);
    }

    private static boolean containsViewport(Projection node) {
        if (node instanceof ProjectionLeaf leaf) {
            return VIEWPORT_PANEL_ID.equals(leaf.id());
        }
        if (node instanceof ProjectionTabs group) {
            return group.panelIds().contains(VIEWPORT_PANEL_ID);
        }
        ProjectionSplit split = (ProjectionSplit) node;
        return containsViewport(split.first()) || containsViewport(split.second());
    }

    /** 单叶最低宽度：viewport 320、properties/details 260、其余 180。 */
    private static double minWidth(Projection node) {
        if (node instanceof ProjectionLeaf leaf) {
            return leafMinWidth(leaf.id());
        }
        if (node instanceof ProjectionTabs group) {
            double width = 0.0D;
            for (String id : group.panelIds()) {
                width = Math.max(width, leafMinWidth(id));
            }
            return width;
        }
        ProjectionSplit split = (ProjectionSplit) node;
        return split.axis() == DockNode.Axis.HORIZONTAL ? minWidth(split.first()) + SPLITTER + minWidth(split.second()) : Math.max(minWidth(split.first()), minWidth(split.second()));
    }

    /** 单叶最低高度：viewport 220、其余 120；Tabs 再叠加 28 的标签头。 */
    private static double minHeight(Projection node) {
        if (node instanceof ProjectionLeaf leaf) {
            return leafMinHeight(leaf.id());
        }
        if (node instanceof ProjectionTabs group) {
            double height = 0.0D;
            for (String id : group.panelIds()) {
                height = Math.max(height, leafMinHeight(id));
            }
            return height + TAB_HEADER_HEIGHT;
        }
        ProjectionSplit split = (ProjectionSplit) node;
        return split.axis() == DockNode.Axis.HORIZONTAL ? Math.max(minHeight(split.first()), minHeight(split.second())) : minHeight(split.first()) + SPLITTER + minHeight(split.second());
    }

    private static double leafMinWidth(String id) {
        if (VIEWPORT_PANEL_ID.equals(id)) {
            return MIN_VIEWPORT_WIDTH;
        }
        if (PROPERTIES_PANEL_ID.equals(id) || DETAILS_PANEL_ID.equals(id)) {
            return SIDE_PANEL_MIN_WIDTH;
        }
        return PLAIN_PANEL_MIN_WIDTH;
    }

    private static double leafMinHeight(String id) {
        return VIEWPORT_PANEL_ID.equals(id) ? MIN_VIEWPORT_HEIGHT : PLAIN_PANEL_MIN_HEIGHT;
    }

    private static boolean fits(Projection node, LayoutSnapshot.Bounds content) {
        return minWidth(node) <= content.width() && minHeight(node) <= content.height();
    }

    /**
     * 固定顺序收起直到树最小尺寸装进正文区；全部收起后仍装不下返回 {@code null}（调用方转 COMPACT）。
     *
     * <p>
     * 顺序：BOTTOM 角色固定序 → hierarchy/project → 其余未知叶按原叶序 → properties/details 最后。
     * viewport 永不进入收起序列。
     */
    private static Projection fit(Projection node, LayoutSnapshot.Bounds content,
                                  List<String> leafOrder) {
        if (fits(node, content)) {
            return node;
        }
        for (String id : buildPruneOrder(leafOrder)) {
            Projection pruned = prune(node, id);
            if (pruned == null || pruned == node) {
                continue;
            }
            node = pruned;
            if (fits(node, content)) {
                return node;
            }
        }
        return fits(node, content) ? node : null;
    }

    private static List<String> buildPruneOrder(List<String> leafOrder) {
        Set<String> present = new LinkedHashSet<>(leafOrder);
        Set<String> used = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (String id : BOTTOM_ROLE_PANEL_IDS) {
            if (present.contains(id) && used.add(id)) {
                order.add(id);
            }
        }
        for (String id : LEFT_ROLE_PANEL_IDS) {
            if (present.contains(id) && used.add(id)) {
                order.add(id);
            }
        }
        for (String id : leafOrder) {
            if (VIEWPORT_PANEL_ID.equals(id) || PROPERTIES_PANEL_ID.equals(id) || DETAILS_PANEL_ID.equals(id)) {
                continue;
            }
            if (used.add(id)) {
                order.add(id);
            }
        }
        if (present.contains(PROPERTIES_PANEL_ID) && used.add(PROPERTIES_PANEL_ID)) {
            order.add(PROPERTIES_PANEL_ID);
        }
        if (present.contains(DETAILS_PANEL_ID) && used.add(DETAILS_PANEL_ID)) {
            order.add(DETAILS_PANEL_ID);
        }
        return order;
    }

    /** 从私有投影移除一个叶；Tabs 剩 0 删节点、剩 1 化 Panel，Split 一侧为空则化另一侧。 */
    private static Projection prune(Projection node, String id) {
        if (node instanceof ProjectionLeaf leaf) {
            return leaf.id().equals(id) ? null : leaf;
        }
        if (node instanceof ProjectionTabs group) {
            if (!group.panelIds().contains(id)) {
                return group;
            }
            List<String> remaining = new ArrayList<>(group.panelIds().size());
            for (String panelId : group.panelIds()) {
                if (!panelId.equals(id)) {
                    remaining.add(panelId);
                }
            }
            if (remaining.isEmpty()) {
                return null;
            }
            if (remaining.size() == 1) {
                return new ProjectionLeaf(remaining.get(0));
            }
            return new ProjectionTabs(List.copyOf(remaining), group.storedActiveId());
        }
        ProjectionSplit split = (ProjectionSplit) node;
        Projection first = prune(split.first(), id);
        Projection second = prune(split.second(), id);
        if (first == null && second == null) {
            return null;
        }
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new ProjectionSplit(split.nodePath(), split.axis(), split.ratio(), first, second);
    }

    /**
     * 仅用于「一侧不含 viewport、另一侧含 viewport」时的 soft 上限。
     *
     * <p>
     * horizontal = {@code max(该子树 minWidth, 各角色上限最大值)}，任一 BOTTOM 角色则该方向无上限；
     * vertical = {@code max(该子树 minHeight, 240)}。min 与 soft 冲突以 min 优先（cap 恒不低于 min）。
     */
    private static double softCap(Projection node, DockNode.Axis axis) {
        if (axis == DockNode.Axis.VERTICAL) {
            return Math.max(minHeight(node), VERTICAL_SOFT_CAP_FLOOR);
        }
        double cap = minWidth(node);
        List<String> ids = new ArrayList<>();
        projectionLeaves(node, ids);
        for (String id : ids) {
            if (BOTTOM_ROLE_PANEL_IDS.contains(id)) {
                return Double.POSITIVE_INFINITY;
            }
            cap = Math.max(cap, PROPERTIES_PANEL_ID.equals(id) || DETAILS_PANEL_ID.equals(id) ? SIDE_PANEL_SOFT_CAP : PLAIN_PANEL_SOFT_CAP);
        }
        return cap;
    }

    /**
     * soft 上限重分配守卫：仅当候选划分的<b>实际 double</b> 两侧长度都有限、且分别不低于对应子树的 min
     * 时才采用候选，否则保留原 min-clamped 划分 {@code fallback}。
     *
     * <p>
     * 必要性：窗口极大时 {@code available - cap} 会因舍入等于 {@code available}（{@code cap} 远小于
     * {@code available} 的 ulp），于是 {@code available - candidateFirst} 归零，另一侧长度塌成 0 并违反其
     * min。此处不做 epsilon 容差、不人为 +1、不放宽 min、不改 ratio。
     *
     * @param candidate 按原顺序算出的 soft 上限候选首段长度
     * @param fallback  原 min-clamped 首段长度
     * @param available 该轴扣掉分隔条后的可用长度
     * @param firstMin  首子树 min
     * @param secondMin 次子树 min
     * @return 可采用的候选，或原 fallback
     */
    private static double acceptSoftCapCandidate(double candidate, double fallback, double available,
                                                 double firstMin, double secondMin) {
        if (!Double.isFinite(candidate)) {
            return fallback;
        }
        double candidateSecond = available - candidate;
        if (!Double.isFinite(candidateSecond)) {
            return fallback;
        }
        if (candidate < firstMin || candidateSecond < secondMin) {
            return fallback;
        }
        return candidate;
    }

    // __R16_2_LAYOUT__

    /** 溢出入口：原叶序、去重、排除 viewport 与当前可见项；须在 drawer overlay 之前计算。 */
    private static List<String> overflowOf(List<String> leafOrder, Map<String, Boolean> visible) {
        List<String> overflow = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : leafOrder) {
            if (VIEWPORT_PANEL_ID.equals(id) || Boolean.TRUE.equals(visible.get(id))) {
                continue;
            }
            if (seen.add(id)) {
                overflow.add(id);
            }
        }
        return overflow;
    }

    /** 按原叶序装配 PanelBox；缺矩形（被收起）一律 content 左上 0×0。 */
    private static List<LayoutSnapshot.PanelBox> panelBoxes(List<String> leafOrder,
                                                            Map<String, LayoutSnapshot.Bounds> bounds, Map<String, Boolean> visible,
                                                            LayoutSnapshot.Bounds content) {
        LayoutSnapshot.Bounds empty = new LayoutSnapshot.Bounds(content.left(), content.top(), 0.0D, 0.0D);
        List<LayoutSnapshot.PanelBox> panels = new ArrayList<>(leafOrder.size());
        for (String id : leafOrder) {
            LayoutSnapshot.Bounds rect = bounds.get(id);
            panels.add(new LayoutSnapshot.PanelBox(id, rect == null ? empty : rect,
                    Boolean.TRUE.equals(visible.get(id))));
        }
        return panels;
    }

    /** 抽屉所在边：properties/details→RIGHT；BOTTOM 角色→BOTTOM；其余（含 unknown）→LEFT。 */
    private static LayoutSnapshot.DrawerSide drawerSide(String panelId) {
        if (PROPERTIES_PANEL_ID.equals(panelId) || DETAILS_PANEL_ID.equals(panelId)) {
            return LayoutSnapshot.DrawerSide.RIGHT;
        }
        if (BOTTOM_ROLE_PANEL_IDS.contains(panelId)) {
            return LayoutSnapshot.DrawerSide.BOTTOM;
        }
        return LayoutSnapshot.DrawerSide.LEFT;
    }

    /**
     * 抽屉矩形：LEFT/RIGHT 宽 {@code min(256|304, W-96)}、高 content.height；BOTTOM 高 {@code min(240, H-80)}、宽 content.width
     * 且贴底。
     */
    private static LayoutSnapshot.Bounds drawerBounds(LayoutSnapshot.DrawerSide side,
                                                      LayoutSnapshot.Bounds content) {
        if (side == LayoutSnapshot.DrawerSide.BOTTOM) {
            double height = Math.max(0.0D, Math.min(240.0D, content.height() - 80.0D));
            return new LayoutSnapshot.Bounds(content.left(), content.bottom() - height, content.width(),
                    height);
        }
        double width = side == LayoutSnapshot.DrawerSide.RIGHT ? Math.max(0.0D, Math.min(304.0D, content.width() - 96.0D)) : Math.max(0.0D, Math.min(256.0D, content.width() - 96.0D));
        double left = side == LayoutSnapshot.DrawerSide.RIGHT ? content.right() - width : content.left();
        return new LayoutSnapshot.Bounds(left, content.top(), width, content.height());
    }

    /**
     * TOO_SMALL：只有固定 chrome 可达。
     *
     * <p>
     * viewport 等于 content 但不可交互（禁用手势由 UI bridge 负责，不能当作 pick-ready）；每个叶保留 ID 且
     * {@code visible=false}、空 bounds=content 左上 0×0；无 splitter/tabs/drawer；overflow 为全部非 viewport
     * 叶原序。
     */
    private static LayoutSnapshot buildTooSmall(LayoutSnapshot.Bounds menuBar,
                                                LayoutSnapshot.Bounds documentBar, LayoutSnapshot.Bounds toolbar,
                                                Optional<LayoutSnapshot.Bounds> banner, LayoutSnapshot.Bounds content,
                                                LayoutSnapshot.Bounds statusBar, List<String> leafOrder) {
        return new LayoutSnapshot(LayoutSnapshot.Mode.TOO_SMALL, menuBar, documentBar, toolbar, banner,
                content, statusBar, content, panelBoxes(leafOrder, Map.of(), Map.of(), content), List.of(),
                List.of(), Optional.empty(), overflowOf(leafOrder, Map.of()));
    }

    /** DOCKED 求解期间的可变装配状态；仅在单次 solve 内存在，不外泄。 */
    private static final class SolveState {

        private final Map<String, LayoutSnapshot.Bounds> bounds = new LinkedHashMap<>();
        private final Map<String, Boolean> visible = new LinkedHashMap<>();
        private final List<LayoutSnapshot.TabBox> tabs = new ArrayList<>();
        private final List<LayoutSnapshot.SplitBox> splitters = new ArrayList<>();
        private LayoutSnapshot.Bounds viewport;
    }

    /**
     * DOCKED：投影树按 ratio 递归分区，viewport 一定可达。
     *
     * <p>
     * 抽屉只对「不在投影树里（已收起）」或「在树里但不可见（Tabs 非活动成员）」的请求面板开 overlay：只有该
     * {@code PanelBox} 变为 {@code visible=true} 且取抽屉矩形，持久树、viewport 与 TabBox active 全不变；请求的
     * 面板本来就可见时是 no-op。
     */
    private static LayoutSnapshot buildDocked(LayoutSnapshot.Bounds menuBar,
                                              LayoutSnapshot.Bounds documentBar, LayoutSnapshot.Bounds toolbar,
                                              Optional<LayoutSnapshot.Bounds> banner, LayoutSnapshot.Bounds content,
                                              LayoutSnapshot.Bounds statusBar, List<String> leafOrder, Projection projection,
                                              String requestedDrawerId) {
        SolveState state = new SolveState();
        solveDocked(projection, content, state);
        LayoutSnapshot.Bounds viewport = state.viewport == null ? new LayoutSnapshot.Bounds(content.left(), content.top(), 0.0D, 0.0D) : state.viewport;
        // overflow 必须在 overlay 改写可见性之前算：抽屉面板仍算「不在正文里」，条目保留。
        List<String> overflow = overflowOf(leafOrder, state.visible);
        List<LayoutSnapshot.PanelBox> panels = panelBoxes(leafOrder, state.bounds, state.visible, content);
        Optional<LayoutSnapshot.DrawerBox> drawer = Optional.empty();
        if (requestedDrawerId != null && !Boolean.TRUE.equals(state.visible.get(requestedDrawerId))) {
            LayoutSnapshot.DrawerSide side = drawerSide(requestedDrawerId);
            LayoutSnapshot.Bounds drawerRect = drawerBounds(side, content);
            drawer = Optional.of(
                    new LayoutSnapshot.DrawerBox(requestedDrawerId, side, drawerRect, content));
            panels = withOverlay(panels, requestedDrawerId, drawerRect);
        }
        return new LayoutSnapshot(LayoutSnapshot.Mode.DOCKED, menuBar, documentBar, toolbar, banner,
                content, statusBar, viewport, panels, state.tabs, state.splitters, drawer, overflow);
    }

    /**
     * COMPACT：正文整体是 viewport（始终 ≥320×220，否则早已 TOO_SMALL），全部面板先按不可见 0×0 登记，
     * 最多一个 overlay 抽屉。
     */
    private static LayoutSnapshot buildCompact(LayoutSnapshot.Bounds menuBar,
                                               LayoutSnapshot.Bounds documentBar, LayoutSnapshot.Bounds toolbar,
                                               Optional<LayoutSnapshot.Bounds> banner, LayoutSnapshot.Bounds content,
                                               LayoutSnapshot.Bounds statusBar, List<String> leafOrder, String requestedDrawerId) {
        Map<String, LayoutSnapshot.Bounds> bounds = new LinkedHashMap<>();
        Map<String, Boolean> visible = new LinkedHashMap<>();
        bounds.put(VIEWPORT_PANEL_ID, content);
        visible.put(VIEWPORT_PANEL_ID, Boolean.TRUE);
        List<String> overflow = overflowOf(leafOrder, visible);
        List<LayoutSnapshot.PanelBox> panels = panelBoxes(leafOrder, bounds, visible, content);
        Optional<LayoutSnapshot.DrawerBox> drawer = Optional.empty();
        if (requestedDrawerId != null) {
            LayoutSnapshot.DrawerSide side = drawerSide(requestedDrawerId);
            LayoutSnapshot.Bounds drawerRect = drawerBounds(side, content);
            drawer = Optional.of(
                    new LayoutSnapshot.DrawerBox(requestedDrawerId, side, drawerRect, content));
            panels = withOverlay(panels, requestedDrawerId, drawerRect);
        }
        return new LayoutSnapshot(LayoutSnapshot.Mode.COMPACT, menuBar, documentBar, toolbar, banner,
                content, statusBar, content, panels, List.of(), List.of(), drawer, overflow);
    }

    /** 把一个面板改成 overlay：bounds 换成抽屉矩形并置可见，其余保持原样。 */
    private static List<LayoutSnapshot.PanelBox> withOverlay(List<LayoutSnapshot.PanelBox> panels,
                                                             String panelId, LayoutSnapshot.Bounds bounds) {
        List<LayoutSnapshot.PanelBox> out = new ArrayList<>(panels.size());
        for (LayoutSnapshot.PanelBox box : panels) {
            out.add(box.panelId().equals(panelId) ? new LayoutSnapshot.PanelBox(box.panelId(), bounds, true) : box);
        }
        return out;
    }

    /**
     * 递归求解 DOCKED 矩形：叶登记矩形、Tabs 登记 28 高的标签头与组内可见性、Split 按 ratio 分区并登记分隔条。
     *
     * @param rect 该节点在正文区内的矩形（root 为 content）
     * @return 该节点占用的矩形（即 {@code rect}）
     */
    private static LayoutSnapshot.Bounds solveDocked(Projection node, LayoutSnapshot.Bounds rect,
                                                     SolveState state) {
        if (node instanceof ProjectionLeaf leaf) {
            state.bounds.put(leaf.id(), rect);
            state.visible.put(leaf.id(), Boolean.TRUE);
            if (VIEWPORT_PANEL_ID.equals(leaf.id())) {
                state.viewport = rect;
            }
            return rect;
        }
        if (node instanceof ProjectionTabs group) {
            double headerHeight = Math.min(TAB_HEADER_HEIGHT, rect.height());
            LayoutSnapshot.Bounds header = new LayoutSnapshot.Bounds(rect.left(), rect.top(),
                    rect.width(), headerHeight);
            LayoutSnapshot.Bounds body = new LayoutSnapshot.Bounds(rect.left(), rect.top() + headerHeight,
                    rect.width(), rect.height() - headerHeight);
            String active = effectiveActive(group);
            state.tabs.add(new LayoutSnapshot.TabBox(group.panelIds(), active, header));
            for (String id : group.panelIds()) {
                state.bounds.put(id, body);
                state.visible.put(id, id.equals(active));
            }
            if (group.panelIds().contains(VIEWPORT_PANEL_ID)) {
                state.viewport = body;
            }
            return rect;
        }
        ProjectionSplit split = (ProjectionSplit) node;
        boolean horizontal = split.axis() == DockNode.Axis.HORIZONTAL;
        double extent = horizontal ? rect.width() : rect.height();
        double available = Math.max(0.0D, extent - SPLITTER);
        double firstMin = horizontal ? minWidth(split.first()) : minHeight(split.first());
        double secondMin = horizontal ? minWidth(split.second()) : minHeight(split.second());
        // preferred = available * ratio，再夹到「两侧都不低于各自 min」；装不下时退化为不产生负长度
        // （由末尾的 clamp 兜底）。
        double firstLength = Math.max(firstMin, Math.min(available - secondMin, available * split.ratio()));
        boolean firstHasViewport = containsViewport(split.first());
        boolean secondHasViewport = containsViewport(split.second());
        if (firstHasViewport && !secondHasViewport) {
            // viewport 在 first：候选把 second 收到 soft 上限以内，多出的长度还给 viewport 侧。
            double candidate = available - Math.min(available - firstLength,
                    softCap(split.second(), split.axis()));
            firstLength = acceptSoftCapCandidate(candidate, firstLength, available, firstMin, secondMin);
        } else if (!firstHasViewport && secondHasViewport) {
            // viewport 在 second：候选让 first 不越过自己的 soft 上限。
            double candidate = Math.min(firstLength, softCap(split.first(), split.axis()));
            firstLength = acceptSoftCapCandidate(candidate, firstLength, available, firstMin, secondMin);
        }
        firstLength = clamp(firstLength, 0.0D, available);
        double secondLength = available - firstLength;
        LayoutSnapshot.Bounds firstRect;
        LayoutSnapshot.Bounds secondRect;
        LayoutSnapshot.Bounds divider;
        if (horizontal) {
            firstRect = new LayoutSnapshot.Bounds(rect.left(), rect.top(), firstLength, rect.height());
            divider = new LayoutSnapshot.Bounds(rect.left() + firstLength, rect.top(), SPLITTER,
                    rect.height());
            // 非 finite 尾端才修正第二子矩形同轴 length；finite 场景逐 bit 原样返回。
            secondLength = finiteTailLength(divider.right(), rect.right(), secondLength, secondMin);
            secondRect = new LayoutSnapshot.Bounds(divider.right(), rect.top(), secondLength,
                    rect.height());
        } else {
            firstRect = new LayoutSnapshot.Bounds(rect.left(), rect.top(), rect.width(), firstLength);
            divider = new LayoutSnapshot.Bounds(rect.left(), rect.top() + firstLength, rect.width(),
                    SPLITTER);
            secondLength = finiteTailLength(divider.bottom(), rect.bottom(), secondLength, secondMin);
            secondRect = new LayoutSnapshot.Bounds(rect.left(), divider.bottom(), rect.width(),
                    secondLength);
        }
        state.splitters.add(new LayoutSnapshot.SplitBox(split.nodePath(), split.axis(), divider));
        solveDocked(split.first(), firstRect, state);
        solveDocked(split.second(), secondRect, state);
        return rect;
    }

    /**
     * 第二子矩形同轴 length 的尾端有限化：只在 {@code start + requested} 非 finite 时介入。
     *
     * <p>
     * finite 场景（绝大多数）直接返回 {@code requested}，逐 bit 不做任何修改。
     * 溢出场景把 candidate 夹到 {@code [secondMin, parentEnd - start]}；若仍溢出，仅一次
     * {@link Math#nextDown(double)}，不循环。全部为 ordinary double 运算，无 epsilon、无新的加减重排。
     */
    private static double finiteTailLength(double start, double parentEnd, double requested,
                                           double secondMin) {
        if (Double.isFinite(start + requested)) {
            return requested;
        }
        double candidate = Math.max(secondMin, Math.min(requested, parentEnd - start));
        if (!Double.isFinite(start + candidate)) {
            candidate = Math.nextDown(candidate);
        }
        if (!Double.isFinite(candidate) || candidate < secondMin || !Double.isFinite(start + candidate)) {
            throw new IllegalStateException("finite tail guard violated: start=" + start + " parentEnd=" + parentEnd + " requested=" + requested + " secondMin=" + secondMin + " candidate=" + candidate);
        }
        return candidate;
    }
}
