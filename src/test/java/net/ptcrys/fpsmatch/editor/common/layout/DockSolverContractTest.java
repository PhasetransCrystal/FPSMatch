package net.ptcrys.fpsmatch.editor.common.layout;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Axis;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Panel;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Split;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Tabs;
import net.ptcrys.fpsmatch.editor.common.layout.LayoutSnapshot.Bounds;
import net.ptcrys.fpsmatch.editor.common.layout.LayoutSnapshot.DrawerBox;
import net.ptcrys.fpsmatch.editor.common.layout.LayoutSnapshot.DrawerSide;
import net.ptcrys.fpsmatch.editor.common.layout.LayoutSnapshot.Mode;
import net.ptcrys.fpsmatch.editor.common.layout.LayoutSnapshot.PanelBox;
import net.ptcrys.fpsmatch.editor.common.layout.LayoutSnapshot.SplitBox;
import net.ptcrys.fpsmatch.editor.common.layout.LayoutSnapshot.TabBox;

/**
 * R16.2 停靠求解与 compact 投影契约测试。
 *
 * <p>覆盖卡片固定的参数 golden（1280×720、1920×1080、854×480、980×650 设计树）、边界与非法输入、
 * 私有 prune 后的身份稳定性、overlay 抽屉、快照防御复制/预算 guard，以及至少 1000 个不同合法树与尺寸的
 * 独立最小尺寸/二分区间 oracle。
 *
 * <p>本测试的所有期望值由本文件按规格文本独立计算（{@link #specMin}、{@link #specCap}、
 * {@link #partition}），不读取生产实现的内部计数器，也不把生产输出再次喂给生产判断唯一 oracle。
 */
class DockSolverContractTest {

  /** 坐标 golden 与普通窗口 oracle 的绝对误差上限。 */
  private static final double EPS = 1e-8D;

  private static final Set<String> SPEC_BOTTOM_ROLES =
      Set.of("problems", "events", "watch", "test-controls", "operation-history", "operation-result");

  /** 收起顺序里 BOTTOM 角色的固定序（与 {@link #SPEC_BOTTOM_ROLES} 同集，但顺序参与 oracle）。 */
  private static final List<String> SPEC_BOTTOM_ROLE_ORDER =
      List.of("problems", "events", "watch", "test-controls", "operation-history", "operation-result");

  /** 收起顺序里 LEFT 角色的固定序。 */
  private static final List<String> SPEC_LEFT_ROLE_ORDER = List.of("hierarchy", "project");

  // ------------------------------------------------------------ 规格侧独立 oracle

  private static Panel panel(String id) {
    return new Panel(id);
  }

  private static Split h(double ratio, DockNode first, DockNode second) {
    return new Split(Axis.HORIZONTAL, ratio, first, second);
  }

  private static Split v(double ratio, DockNode first, DockNode second) {
    return new Split(Axis.VERTICAL, ratio, first, second);
  }

  private static LayoutSnapshot solve(DockLayout layout, double w, double h, boolean banner,
      String drawer) {
    return DockSolver.solve(layout, w, h, banner, Optional.ofNullable(drawer));
  }

  private static void near(double actual, double expected) {
    assertTrue(Double.isFinite(actual), "expected finite but was " + actual);
    assertTrue(Math.abs(actual - expected) <= EPS,
        "expected " + expected + " but was " + actual);
  }

  private static void bounds(Bounds b, double left, double top, double width, double height) {
    near(b.left(), left);
    near(b.top(), top);
    near(b.width(), width);
    near(b.height(), height);
  }

  /** 巨大尺度相对比较：actual 与 expected 本身须 finite，再看相对 1e-12 容差（固定尺寸仍用 1e-8）。 */
  private static void relative(double actual, double expected, String what) {
    assertTrue(Double.isFinite(actual), what + ": actual not finite: " + actual);
    assertTrue(Double.isFinite(expected), what + ": expected not finite: " + expected);
    double tol = 1e-12D * Math.max(1.0D, Math.abs(expected));
    assertTrue(Math.abs(actual - expected) <= tol,
        what + ": expected " + expected + " but was " + actual + " (tol " + tol + ")");
  }

  private static void relativeBounds(Bounds b, double left, double top, double width, double height,
      String what) {
    relative(b.left(), left, what + ".left");
    relative(b.top(), top, what + ".top");
    relative(b.width(), width, what + ".width");
    relative(b.height(), height, what + ".height");
  }

  /** 矩形四字段与两条导出边都必须 finite。 */
  private static void assertFiniteRect(Bounds b, String what) {
    assertTrue(Double.isFinite(b.left()) && Double.isFinite(b.top())
        && Double.isFinite(b.width()) && Double.isFinite(b.height()),
        what + ": components not finite: " + b);
    assertTrue(Double.isFinite(b.right()) && Double.isFinite(b.bottom()),
        what + ": edges not finite: " + b);
    assertTrue(b.width() >= 0.0D && b.height() >= 0.0D, what + ": negative size: " + b);
  }

  /**
   * 巨大尺度包含检查：先核 actual 与 limit 各边 finite，再核 actualStart >= limit，允许
   * {@code 1e-12 * max(1, |limit|)} 容差。不构造 limit±tol 的放宽边界（可能溢出）。
   */
  private static void assertStartsAtOrInside(Bounds actual, double limit, boolean horizontal,
      String what) {
    assertFiniteRect(actual, what);
    assertTrue(Double.isFinite(limit), what + ": limit not finite: " + limit);
    double start = horizontal ? actual.left() : actual.top();
    double tol = 1e-12D * Math.max(1.0D, Math.abs(limit));
    assertTrue(start >= limit || limit - start <= tol,
        what + ": start " + start + " outside limit " + limit);
  }

  /** 巨大尺度包含检查：上界方向 {@code actualEdge <= limit}，允许同一相对容差。 */
  private static void assertEndsAtOrInside(Bounds actual, double limit, boolean horizontal,
      String what) {
    assertFiniteRect(actual, what);
    assertTrue(Double.isFinite(limit), what + ": limit not finite: " + limit);
    double edge = horizontal ? actual.right() : actual.bottom();
    double tol = 1e-12D * Math.max(1.0D, Math.abs(limit));
    assertTrue(edge <= limit || edge - limit <= tol,
        what + ": edge " + edge + " outside limit " + limit);
  }

  /** 四边都在 content 内（巨大尺度安全版）。 */
  private static void assertInsideContent(Bounds actual, Bounds content, String what) {
    assertStartsAtOrInside(actual, content.left(), true, what);
    assertStartsAtOrInside(actual, content.top(), false, what);
    assertEndsAtOrInside(actual, content.right(), true, what);
    assertEndsAtOrInside(actual, content.bottom(), false, what);
  }

  /**
   * 独立两叶期望（巨大有限值）：由输入 w/h/banner 的独立 chrome 公式求 content，
   * available = content 同轴长度 - 4，first = max(firstMin, min(available - secondMin, available*ratio))，
   * second = available - first。
   *
   * <p>panel-only 侧（properties）当前 length &gt; cap 时才给候选：viewport 在 first 时
   * candidateFirst = available - min(second, cap)、candidateSecond = available - candidateFirst；
   * viewport 在 second 时 candidateFirst = min(first, cap)、candidateSecond = available - candidateFirst。
   * 两候选均 finite 且各严格 &gt;= 自身 min 才采用，否则保留 min-clamped 长度。全程 ordinary double。
   *
   * @param viewportFirst viewport 是否为 first 子节点
   */
  private static double[] expectedTwoLeafLengths(double w, double h, boolean banner, Axis axis,
      double ratio, double[] viewportMin, double[] propertiesMin, double propertiesCap,
      boolean viewportFirst) {
    Bounds content = specContent(w, h, banner);
    double available = (axis == Axis.HORIZONTAL ? content.width() : content.height()) - 4.0D;
    double firstMin = axis == Axis.HORIZONTAL
        ? (viewportFirst ? viewportMin[0] : propertiesMin[0])
        : (viewportFirst ? viewportMin[1] : propertiesMin[1]);
    double secondMin = axis == Axis.HORIZONTAL
        ? (viewportFirst ? propertiesMin[0] : viewportMin[0])
        : (viewportFirst ? propertiesMin[1] : viewportMin[1]);
    double first = Math.max(firstMin, Math.min(available - secondMin, available * ratio));
    double second = available - first;
    double candFirst = first;
    double candSecond = second;
    double panelOnlyLength = viewportFirst ? second : first;
    if (panelOnlyLength > propertiesCap) {
      if (viewportFirst) {
        // viewport 在 first：properties 收进 cap，余量归还 first
        candFirst = available - Math.min(second, propertiesCap);
        candSecond = available - candFirst;
      } else {
        // viewport 在 second：properties(first) 收进 cap，余量归还 second
        candFirst = Math.min(first, propertiesCap);
        candSecond = available - candFirst;
      }
    }
    if (Double.isFinite(candFirst) && Double.isFinite(candSecond)
        && candFirst >= firstMin && candSecond >= secondMin) {
      first = candFirst;
      second = candSecond;
    }
    return new double[] {first, second};
  }

  private static PanelBox box(LayoutSnapshot snapshot, String id) {
    return snapshot.panels().stream().filter(p -> p.panelId().equals(id)).findFirst()
        .orElseThrow(() -> new AssertionError("missing panel box '" + id + "'"));
  }

  private static TabBox tab(LayoutSnapshot snapshot, String... ids) {
    List<String> wanted = List.of(ids);
    return snapshot.tabs().stream().filter(t -> t.panelIds().equals(wanted)).findFirst()
        .orElseThrow(() -> new AssertionError("missing tab box " + wanted));
  }

  private static SplitBox split(LayoutSnapshot snapshot, Boolean... path) {
    List<Boolean> wanted = Arrays.asList(path);
    return snapshot.splitters().stream().filter(s -> s.nodePath().equals(wanted)).findFirst()
        .orElseThrow(() -> new AssertionError("missing split box " + wanted));
  }

  /** 原树叶 ID 顺序（Tabs 按组内顺序展开）。 */
  private static List<String> specLeaves(DockNode node) {
    List<String> out = new ArrayList<>();
    specLeaves(node, out);
    return out;
  }

  private static void specLeaves(DockNode node, List<String> out) {
    if (node instanceof Panel p) {
      out.add(p.id());
    } else if (node instanceof Tabs t) {
      t.panels().forEach(p -> out.add(p.id()));
    } else {
      Split s = (Split) node;
      specLeaves(s.first(), out);
      specLeaves(s.second(), out);
    }
  }

  /** 规格最小尺寸：viewport 320×220、properties/details 260×120、其他 180×120、Tabs 叠 28、分隔 4。 */
  private static double[] specMin(DockNode node) {
    if (node instanceof Panel p) {
      return switch (p.id()) {
        case "viewport" -> new double[] {320.0D, 220.0D};
        case "properties", "details" -> new double[] {260.0D, 120.0D};
        default -> new double[] {180.0D, 120.0D};
      };
    }
    if (node instanceof Tabs t) {
      double width = 0.0D;
      double height = 0.0D;
      for (Panel p : t.panels()) {
        double[] m = specMin(p);
        width = Math.max(width, m[0]);
        height = Math.max(height, m[1]);
      }
      return new double[] {width, height + 28.0D};
    }
    Split s = (Split) node;
    double[] a = specMin(s.first());
    double[] b = specMin(s.second());
    return s.axis() == Axis.HORIZONTAL
        ? new double[] {a[0] + 4.0D + b[0], Math.max(a[1], b[1])}
        : new double[] {Math.max(a[0], b[0]), a[1] + 4.0D + b[1]};
  }

  private static boolean specContainsViewport(DockNode node) {
    return specLeaves(node).contains("viewport");
  }

  /** 规格 soft 上限：horizontal = max(minWidth, 角色上限)，含 BOTTOM 角色则无上限；vertical = max(minHeight, 240)。 */
  private static double specCap(DockNode node, Axis axis) {
    double[] m = specMin(node);
    if (axis == Axis.VERTICAL) {
      return Math.max(m[1], 240.0D);
    }
    double cap = m[0];
    for (String id : specLeaves(node)) {
      if (SPEC_BOTTOM_ROLES.contains(id)) {
        return Double.POSITIVE_INFINITY;
      }
      cap = Math.max(cap, "properties".equals(id) || "details".equals(id) ? 420.0D : 360.0D);
    }
    return cap;
  }

  private static List<Boolean> childPath(List<Boolean> path, boolean second) {
    List<Boolean> out = new ArrayList<>(path);
    out.add(second);
    return List.copyOf(out);
  }

  /**
   * 独立规格 chrome：26/30/34/可选 32 自顶向下，每步取 {@code min(目标高, 剩余高)}，
   * footer 最后取 {@code min(24, 剩余高)}，content = (0, 各顶段之和, w, 最后剩余高 - footer)。
   */
  private static Bounds specContent(double w, double h, boolean banner) {
    double remaining = h;
    double top = 0.0D;
    for (double target : new double[] {26.0D, 30.0D, 34.0D}) {
      double step = Math.min(target, remaining);
      top += step;
      remaining -= step;
    }
    if (banner) {
      double step = Math.min(32.0D, remaining);
      top += step;
      remaining -= step;
    }
    double footer = Math.min(24.0D, remaining);
    return new Bounds(0.0D, top, w, remaining - footer);
  }

  /**
   * 独立二分区间 oracle：从<b>原树</b>递归核每个分区，矩形由<b>调用者输入</b>的 expected
   * 自顶向下推导，返回该子树占用的矩形。
   *
   * <p>期望 first 长度按规格公式重算：{@code max(firstMin, min(available - secondMin, available * ratio))}
   * 再做 soft 上限归还，最后与生产输出比对；生产输出不参与期望值计算。
   */
  private static Bounds partition(DockNode node, List<Boolean> path, Bounds expected,
      LayoutSnapshot snapshot) {
    assertTrue(Double.isFinite(expected.left()) && Double.isFinite(expected.top())
        && Double.isFinite(expected.width()) && Double.isFinite(expected.height()),
        "expected rect must be finite at path " + path + ": " + expected);
    if (node instanceof Panel p) {
      PanelBox b = box(snapshot, p.id());
      assertTrue(b.visible(), "panel '" + p.id() + "' must be visible");
      bounds(b.bounds(), expected.left(), expected.top(), expected.width(), expected.height());
      double[] m = specMin(p);
      assertTrue(b.bounds().width() >= m[0] && b.bounds().height() >= m[1],
          "panel '" + p.id() + "' below minimum: " + b.bounds());
      return expected;
    }
    if (node instanceof Tabs t) {
      List<String> ids = t.panels().stream().map(Panel::id).toList();
      TabBox tabBox = tab(snapshot, ids.toArray(new String[0]));
      String active = ids.contains("viewport") ? "viewport" : t.activePanelId();
      assertEquals(active, tabBox.activePanelId(), "effective active of " + ids);
      // header 是 expected 顶部 28 高；body 由 expected 独立切分，hidden 成员不可见
      bounds(tabBox.header(), expected.left(), expected.top(), expected.width(), 28.0D);
      Bounds body = new Bounds(expected.left(), expected.top() + 28.0D, expected.width(),
          expected.height() - 28.0D);
      for (String id : ids) {
        assertEquals(id.equals(active), box(snapshot, id).visible(),
            "visibility of tab member " + id);
        // 每个成员（含 inactive）的实际 bounds 都规格化为 group body
        bounds(box(snapshot, id).bounds(), body.left(), body.top(), body.width(), body.height());
      }
      double[] m = specMin(t);
      assertTrue(expected.width() >= m[0] && expected.height() >= m[1],
          "tabs below minimum");
      return expected;
    }
    Split s = (Split) node;
    SplitBox divider = split(snapshot, path.toArray(new Boolean[0]));
    assertEquals(s.axis(), divider.axis());
    double[] ma = specMin(s.first());
    double[] mb = specMin(s.second());
    double available;
    double first;
    double second;
    if (s.axis() == Axis.HORIZONTAL) {
      available = expected.width() - 4.0D;
      first = Math.max(ma[0], Math.min(available - mb[0], available * s.ratio()));
    } else {
      available = expected.height() - 4.0D;
      first = Math.max(ma[1], Math.min(available - mb[1], available * s.ratio()));
    }
    second = available - first;
    // soft 上限归还：仅当一侧含 viewport、另一侧当前长度超 cap 时给出候选；两侧候选
    // 均 finite 且各 >= 自身 min 才采用，否则保留原 min-clamped 长度（不允许 min 违例）。
    boolean firstViewport = specContainsViewport(s.first());
    boolean secondViewport = specContainsViewport(s.second());
    double candFirst = first;
    double candSecond = second;
    if (firstViewport && !secondViewport && second > specCap(s.second(), s.axis())) {
      candFirst = available - Math.min(available - first, specCap(s.second(), s.axis()));
      candSecond = available - candFirst;
    } else if (!firstViewport && secondViewport && first > specCap(s.first(), s.axis())) {
      candFirst = Math.min(first, specCap(s.first(), s.axis()));
      candSecond = available - candFirst;
    }
    if (Double.isFinite(candFirst) && Double.isFinite(candSecond)
        && candFirst >= (s.axis() == Axis.HORIZONTAL ? ma[0] : ma[1])
        && candSecond >= (s.axis() == Axis.HORIZONTAL ? mb[0] : mb[1])) {
      first = candFirst;
      second = candSecond;
    }
    Bounds firstRect;
    Bounds secondRect;
    Bounds dividerRect;
    if (s.axis() == Axis.HORIZONTAL) {
      firstRect = new Bounds(expected.left(), expected.top(), first, expected.height());
      dividerRect = new Bounds(expected.left() + first, expected.top(), 4.0D, expected.height());
      secondRect = new Bounds(dividerRect.right(), expected.top(), second, expected.height());
    } else {
      firstRect = new Bounds(expected.left(), expected.top(), expected.width(), first);
      dividerRect = new Bounds(expected.left(), expected.top() + first, expected.width(), 4.0D);
      secondRect = new Bounds(expected.left(), dividerRect.bottom(), expected.width(), second);
    }
    // 递归使用独立构造的矩形；divider 的实际 bounds 与独立矩形核对
    bounds(divider.bounds(), dividerRect.left(), dividerRect.top(), dividerRect.width(),
        dividerRect.height());
    partition(s.first(), childPath(path, false), firstRect, snapshot);
    partition(s.second(), childPath(path, true), secondRect, snapshot);
    return expected;
  }

  /** 叶 ID 全保留且顺序为原树序；每个矩形都在 content 内。 */
  private static void sameIds(DockLayout layout, LayoutSnapshot snapshot) {
    List<String> ids = specLeaves(layout.root());
    assertTrue(sameIds(ids, snapshot.panels().stream().map(PanelBox::panelId).toList()));
    assertEquals(ids.size(), new HashSet<>(ids).size(), "spec tree itself must have unique leaf ids");
    for (PanelBox p : snapshot.panels()) {
      assertTrue(p.bounds().left() >= snapshot.content().left() - EPS
          && p.bounds().top() >= snapshot.content().top() - EPS, "box out of content: " + p);
      assertTrue(p.bounds().right() <= snapshot.content().right() + EPS
          && p.bounds().bottom() <= snapshot.content().bottom() + EPS, "box out of content: " + p);
    }
  }

  /** ID 列表相等（顺序敏感、大小写敏感、允许重复计数）。 */
  private static boolean sameIds(List<String> expected, List<String> actual) {
    return expected.equals(actual);
  }

  /** 可见面板两两不重叠（overlay 抽屉是显式例外，故只在无抽屉时断言）。 */
  private static void assertVisibleDisjoint(LayoutSnapshot snapshot) {
    List<PanelBox> visible = snapshot.panels().stream()
        .filter(p -> p.visible() && p.bounds().width() > 0.0D && p.bounds().height() > 0.0D).toList();
    for (int i = 0; i < visible.size(); i++) {
      for (int j = i + 1; j < visible.size(); j++) {
        Bounds a = visible.get(i).bounds();
        Bounds b = visible.get(j).bounds();
        boolean overlap = a.left() < b.right() - EPS && b.left() < a.right() - EPS
            && a.top() < b.bottom() - EPS && b.top() < a.bottom() - EPS;
        assertFalse(overlap, "visible panels overlap: " + visible.get(i) + " / " + visible.get(j));
      }
    }
  }

  private static DockNode randomTree(Random random, List<String> ids) {
    if (ids.size() == 1) {
      return panel(ids.get(0));
    }
    if (ids.size() <= 5 && random.nextInt(4) == 0) {
      return new Tabs(ids.get(random.nextInt(ids.size())),
          ids.stream().map(DockSolverContractTest::panel).toList());
    }
    int cut = 1 + random.nextInt(ids.size() - 1);
    return new Split(random.nextBoolean() ? Axis.HORIZONTAL : Axis.VERTICAL,
        0.05D + random.nextDouble() * 0.9D,
        randomTree(random, ids.subList(0, cut)),
        randomTree(random, ids.subList(cut, ids.size())));
  }

  // ---------------------------------------------------------------- chrome 与默认树

  private static DockLayout defaultLayout(double w, double h) {
    return DockSolver.defaultLayout(w, h);
  }

  private static void assertChrome(LayoutSnapshot s, double w, double h, boolean banner) {
    near(s.menuBar().left(), 0.0D);
    near(s.menuBar().top(), 0.0D);
    near(s.menuBar().width(), w);
    near(s.menuBar().height(), 26.0D);
    near(s.documentBar().top(), 26.0D);
    near(s.documentBar().height(), 30.0D);
    near(s.toolbar().top(), 56.0D);
    near(s.toolbar().height(), 34.0D);
    double top = banner ? 122.0D : 90.0D;
    if (banner) {
      assertTrue(s.banner().isPresent(), "banner must be present");
      bounds(s.banner().orElseThrow(), 0.0D, 90.0D, w, 32.0D);
    } else {
      assertTrue(s.banner().isEmpty(), "banner must be absent");
    }
    near(s.content().left(), 0.0D);
    near(s.content().top(), top);
    near(s.content().width(), w);
    double remaining = Math.max(0.0D, h - top);
    double status = Math.min(24.0D, remaining);
    near(s.statusBar().height(), status);
    near(s.statusBar().top(), h - status);
    near(s.statusBar().width(), w);
    near(s.content().height(), Math.max(0.0D, remaining - status));
  }

  @Test
  void defaultLayoutIsWideShapeForLargeWindows() {
    Split root = (Split) defaultLayout(1280.0D, 720.0D).root();
    assertEquals(Axis.HORIZONTAL, root.axis());
    assertEquals(new Panel("hierarchy"), root.first());
    near(root.ratio(), 220.0D / 1276.0D);

    Split right = (Split) root.second();
    assertEquals(Axis.HORIZONTAL, right.axis());
    assertEquals(new Panel("properties"), right.second());
    near(right.ratio(), 752.0D / 1052.0D);

    Split inner = (Split) right.first();
    assertEquals(Axis.VERTICAL, inner.axis());
    assertEquals(new Panel("viewport"), inner.first());
    assertEquals(new Panel("problems"), inner.second());
    near(inner.ratio(), 442.0D / 602.0D);
    DockValidation.validate(root);
  }

  @Test
  void defaultLayoutUsesNarrowSidesAndTallBottomWhenSmall() {
    Split root = (Split) defaultLayout(980.0D, 650.0D).root();
    near(root.ratio(), 180.0D / 976.0D);
    Split right = (Split) root.second();
    near(right.ratio(), 532.0D / 792.0D);
    near(((Split) right.first()).ratio(), 372.0D / 532.0D);
    // 高度 >= 900 时底栏更高：nominalHeight = h - 114
    Split tall = (Split) defaultLayout(1280.0D, 1000.0D).root();
    near(((Split) ((Split) tall.second()).first()).ratio(), (1000.0D - 114.0D - 244.0D) / (1000.0D - 114.0D - 4.0D));
  }

  @Test
  void defaultLayoutClampsRatiosIntoLegalRange() {
    // 极小窗口：ratio 必须仍落在 [0.05, 0.95]，且树仍合法
    for (double[] size : new double[][] {{0.0D, 0.0D}, {1.0D, 1.0D}, {640.0D, 360.0D}}) {
      DockLayout layout = defaultLayout(size[0], size[1]);
      checkRatios(layout.root());
      DockValidation.validate(layout.root());
    }
  }

  private static void checkRatios(DockNode node) {
    if (node instanceof Split s) {
      assertTrue(s.ratio() >= 0.05D - EPS && s.ratio() <= 0.95D + EPS, "ratio out of range: " + s.ratio());
      checkRatios(s.first());
      checkRatios(s.second());
    }
  }

  @Test
  void dockedGolden1280x720() {
    LayoutSnapshot s = solve(defaultLayout(1280.0D, 720.0D), 1280.0D, 720.0D, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    assertChrome(s, 1280.0D, 720.0D, false);
    bounds(s.content(), 0.0D, 90.0D, 1280.0D, 606.0D);
    bounds(s.statusBar(), 0.0D, 696.0D, 1280.0D, 24.0D);
    bounds(s.viewport(), 224.0D, 90.0D, 752.0D, 442.0D);
    bounds(box(s, "hierarchy").bounds(), 0.0D, 90.0D, 220.0D, 606.0D);
    assertTrue(box(s, "hierarchy").visible());
    bounds(box(s, "viewport").bounds(), 224.0D, 90.0D, 752.0D, 442.0D);
    assertTrue(box(s, "viewport").visible());
    bounds(box(s, "problems").bounds(), 224.0D, 536.0D, 752.0D, 160.0D);
    assertTrue(box(s, "problems").visible());
    bounds(box(s, "properties").bounds(), 980.0D, 90.0D, 300.0D, 606.0D);
    assertTrue(box(s, "properties").visible());
    assertTrue(s.tabs().isEmpty());
    assertEquals(3, s.splitters().size());
    SplitBox root = split(s);
    assertEquals(Axis.HORIZONTAL, root.axis());
    bounds(root.bounds(), 220.0D, 90.0D, 4.0D, 606.0D);
    SplitBox outer = split(s, true);
    assertEquals(Axis.HORIZONTAL, outer.axis());
    bounds(outer.bounds(), 976.0D, 90.0D, 4.0D, 606.0D);
    SplitBox inner = split(s, true, false);
    assertEquals(Axis.VERTICAL, inner.axis());
    bounds(inner.bounds(), 224.0D, 532.0D, 752.0D, 4.0D);
    assertTrue(s.drawer().isEmpty());
    assertTrue(s.overflowPanelIds().isEmpty());
    assertVisibleDisjoint(s);
  }

  @Test
  void dockedGolden1920x1080() {
    LayoutSnapshot s = solve(defaultLayout(1920.0D, 1080.0D), 1920.0D, 1080.0D, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    assertChrome(s, 1920.0D, 1080.0D, false);
    bounds(s.content(), 0.0D, 90.0D, 1920.0D, 966.0D);
    bounds(s.statusBar(), 0.0D, 1056.0D, 1920.0D, 24.0D);
    bounds(s.viewport(), 224.0D, 90.0D, 1392.0D, 722.0D);
    bounds(box(s, "hierarchy").bounds(), 0.0D, 90.0D, 220.0D, 966.0D);
    bounds(box(s, "problems").bounds(), 224.0D, 816.0D, 1392.0D, 240.0D);
    bounds(box(s, "properties").bounds(), 1620.0D, 90.0D, 300.0D, 966.0D);
    bounds(split(s).bounds(), 220.0D, 90.0D, 4.0D, 966.0D);
    bounds(split(s, true).bounds(), 1616.0D, 90.0D, 4.0D, 966.0D);
    bounds(split(s, true, false).bounds(), 224.0D, 812.0D, 1392.0D, 4.0D);
    assertTrue(s.drawer().isEmpty());
    assertTrue(s.overflowPanelIds().isEmpty());
    assertVisibleDisjoint(s);
  }

  // ---------------------------------------------------------------- compact 与 banner

  @Test
  void compactGolden854x480() {
    LayoutSnapshot s = solve(defaultLayout(854.0D, 480.0D), 854.0D, 480.0D, false, null);
    assertEquals(Mode.COMPACT, s.mode());
    assertChrome(s, 854.0D, 480.0D, false);
    bounds(s.content(), 0.0D, 90.0D, 854.0D, 366.0D);
    bounds(s.statusBar(), 0.0D, 456.0D, 854.0D, 24.0D);
    bounds(s.viewport(), 0.0D, 90.0D, 854.0D, 366.0D);
    for (String id : new String[] {"hierarchy", "problems", "properties"}) {
      bounds(box(s, id).bounds(), 0.0D, 90.0D, 0.0D, 0.0D);
      assertFalse(box(s, id).visible(), id + " must be hidden in compact");
    }
    bounds(box(s, "viewport").bounds(), 0.0D, 90.0D, 854.0D, 366.0D);
    assertTrue(box(s, "viewport").visible());
    assertTrue(s.tabs().isEmpty());
    assertTrue(s.splitters().isEmpty());
    assertTrue(s.drawer().isEmpty());
    assertEquals(List.of("hierarchy", "problems", "properties"), s.overflowPanelIds());
  }

  @Test
  void compactBannerShiftsContentDown() {
    LayoutSnapshot s = solve(defaultLayout(854.0D, 480.0D), 854.0D, 480.0D, true, null);
    assertEquals(Mode.COMPACT, s.mode());
    assertChrome(s, 854.0D, 480.0D, true);
    bounds(s.content(), 0.0D, 122.0D, 854.0D, 334.0D);
    bounds(s.statusBar(), 0.0D, 456.0D, 854.0D, 24.0D);
    bounds(s.viewport(), 0.0D, 122.0D, 854.0D, 334.0D);
    for (String id : new String[] {"hierarchy", "problems", "properties"}) {
      bounds(box(s, id).bounds(), 0.0D, 122.0D, 0.0D, 0.0D);
      assertFalse(box(s, id).visible());
    }
    assertEquals(List.of("hierarchy", "problems", "properties"), s.overflowPanelIds());
  }

  @Test
  void compactDrawerRightForSidePanel() {
    LayoutSnapshot s = solve(defaultLayout(854.0D, 480.0D), 854.0D, 480.0D, false, "properties");
    assertEquals(Mode.COMPACT, s.mode());
    DrawerBox drawer = s.drawer().orElseThrow();
    assertEquals("properties", drawer.panelId());
    assertEquals(DrawerSide.RIGHT, drawer.side());
    bounds(drawer.bounds(), 550.0D, 90.0D, 304.0D, 366.0D);
    bounds(drawer.scrim(), 0.0D, 90.0D, 854.0D, 366.0D);
    bounds(box(s, "properties").bounds(), 550.0D, 90.0D, 304.0D, 366.0D);
    assertTrue(box(s, "properties").visible());
    // 打开抽屉不改动其余面板与视口
    bounds(s.viewport(), 0.0D, 90.0D, 854.0D, 366.0D);
    assertFalse(box(s, "hierarchy").visible());
    assertFalse(box(s, "problems").visible());
    assertEquals(List.of("hierarchy", "problems", "properties"), s.overflowPanelIds());
  }

  @Test
  void compactDrawerBottomForBottomRolePanel() {
    LayoutSnapshot s = solve(defaultLayout(854.0D, 480.0D), 854.0D, 480.0D, false, "problems");
    DrawerBox drawer = s.drawer().orElseThrow();
    assertEquals("problems", drawer.panelId());
    assertEquals(DrawerSide.BOTTOM, drawer.side());
    bounds(drawer.bounds(), 0.0D, 216.0D, 854.0D, 240.0D);
    bounds(drawer.scrim(), 0.0D, 90.0D, 854.0D, 366.0D);
    bounds(box(s, "problems").bounds(), 0.0D, 216.0D, 854.0D, 240.0D);
    assertTrue(box(s, "problems").visible());
    assertEquals(List.of("hierarchy", "problems", "properties"), s.overflowPanelIds());
  }

  @Test
  void compactDrawerLeftForUnknownOrHierarchy() {
    LayoutSnapshot s = solve(defaultLayout(854.0D, 480.0D), 854.0D, 480.0D, false, "hierarchy");
    DrawerBox drawer = s.drawer().orElseThrow();
    assertEquals(DrawerSide.LEFT, drawer.side());
    bounds(drawer.bounds(), 0.0D, 90.0D, 256.0D, 366.0D);
    bounds(box(s, "hierarchy").bounds(), 0.0D, 90.0D, 256.0D, 366.0D);
    assertTrue(box(s, "hierarchy").visible());
  }

  @Test
  void tooSmallNeverProducesDrawer() {
    for (double[] size : new double[][] {{320.0D, 480.0D}, {639.0D, 480.0D}, {854.0D, 359.0D},
        {0.0D, 0.0D}}) {
      LayoutSnapshot s = solve(defaultLayout(size[0], size[1]), size[0], size[1], false, "properties");
      assertEquals(Mode.TOO_SMALL, s.mode(), "expected TOO_SMALL at " + Arrays.toString(size));
      assertTrue(s.drawer().isEmpty(), "TOO_SMALL 不产生抽屉");
      assertTrue(s.splitters().isEmpty());
      assertTrue(s.tabs().isEmpty());
    }
  }

  @Test
  void compactNeverHasMoreThanOneDrawer() {
    for (String id : new String[] {"hierarchy", "problems", "properties", "viewport"}) {
      if (id.equals("viewport")) {
        continue;
      }
      LayoutSnapshot s = solve(defaultLayout(854.0D, 480.0D), 854.0D, 480.0D, false, id);
      assertEquals(1, s.drawer().isPresent() ? 1 : 0, "exactly one drawer for " + id);
    }
  }

  // ---------------------------------------------------------------- 设计树 golden

  /**
   * 设计树：{@code h(viewport, h(tabs, h(hierarchy, h(project, mystery))))}，
   * {@code tabs = Tabs("problems", [properties, details, problems])}，所有 ratio = 0.5。
   */
  private static DockLayout designLayout() {
    Tabs tabs = new Tabs("problems",
        List.of(panel("properties"), panel("details"), panel("problems")));
    DockNode tree = h(0.5D, panel("viewport"),
        h(0.5D, tabs,
            h(0.5D, panel("hierarchy"),
                h(0.5D, panel("project"), panel("mystery")))));
    return new DockLayout(DockLayout.SCHEMA_VERSION, tree);
  }

  @Test
  void designGolden980x650PrunesToFit() {
    DockLayout layout = designLayout();
    LayoutSnapshot s = solve(layout, 980.0D, 650.0D, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    bounds(s.content(), 0.0D, 90.0D, 980.0D, 536.0D);
    bounds(s.statusBar(), 0.0D, 626.0D, 980.0D, 24.0D);
    bounds(s.viewport(), 0.0D, 90.0D, 348.0D, 536.0D);

    bounds(box(s, "properties").bounds(), 352.0D, 118.0D, 260.0D, 508.0D);
    assertTrue(box(s, "properties").visible());
    bounds(box(s, "details").bounds(), 352.0D, 118.0D, 260.0D, 508.0D);
    assertFalse(box(s, "details").visible(), "非活动标签成员不可见");
    // problems 与 hierarchy 被私有 prune 移除：0×0 且不可见，但 ID 仍保留
    for (String id : new String[] {"problems", "hierarchy"}) {
      bounds(box(s, id).bounds(), 0.0D, 90.0D, 0.0D, 0.0D);
      assertFalse(box(s, id).visible());
    }
    bounds(box(s, "project").bounds(), 616.0D, 90.0D, 180.0D, 536.0D);
    assertTrue(box(s, "project").visible());
    bounds(box(s, "mystery").bounds(), 800.0D, 90.0D, 180.0D, 536.0D);
    assertTrue(box(s, "mystery").visible());

    TabBox tabs = tab(s, "properties", "details");
    assertEquals("properties", tabs.activePanelId());
    bounds(tabs.header(), 352.0D, 90.0D, 260.0D, 28.0D);
    assertEquals(1, s.tabs().size());

    // 原树路径身份稳定：prune 掉 [true,true] 后，[true,true,true] 不重新编号
    assertEquals(3, s.splitters().size());
    bounds(split(s).bounds(), 348.0D, 90.0D, 4.0D, 536.0D);
    bounds(split(s, true).bounds(), 612.0D, 90.0D, 4.0D, 536.0D);
    bounds(split(s, true, true, true).bounds(), 796.0D, 90.0D, 4.0D, 536.0D);

    assertTrue(s.drawer().isEmpty());
    // overflow 为原树叶顺序中被 prune 的 ID，含打开抽屉的那个面板
    assertEquals(List.of("details", "problems", "hierarchy"), s.overflowPanelIds());
    // 叶 ID 总序仍为原树顺序
    assertEquals(List.of("viewport", "properties", "details", "problems", "hierarchy", "project",
        "mystery"), s.panels().stream().map(PanelBox::panelId).toList());
  }

  @Test
  void designGoldenKeepsInputTreeUnmutated() {
    DockLayout layout = designLayout();
    int before = layout.hashCode();
    LayoutSnapshot s = solve(layout, 980.0D, 650.0D, false, "hierarchy");
    assertEquals(before, layout.hashCode(), "求解不得改写输入树（stored active / ratio）");
    // Tabs 的 stored active 未被改写
    Tabs stored = (Tabs) ((Split) ((Split) layout.root()).second()).first();
    assertEquals("problems", stored.activePanelId());
    assertEquals(List.of("properties", "details", "problems"),
        stored.panels().stream().map(Panel::id).toList());
    // 被 prune 的 hierarchy 以 overlay 抽屉重新出现，但不回到持久树
    DrawerBox drawer = s.drawer().orElseThrow();
    assertEquals("hierarchy", drawer.panelId());
    assertEquals(DrawerSide.LEFT, drawer.side());
    bounds(drawer.bounds(), 0.0D, 90.0D, 256.0D, 536.0D);
    bounds(drawer.scrim(), 0.0D, 90.0D, 980.0D, 536.0D);
    bounds(box(s, "hierarchy").bounds(), 0.0D, 90.0D, 256.0D, 536.0D);
    assertTrue(box(s, "hierarchy").visible());
    // 持久树与视口不受抽屉影响
    bounds(s.viewport(), 0.0D, 90.0D, 348.0D, 536.0D);
    assertEquals(3, s.splitters().size());
    bounds(split(s).bounds(), 348.0D, 90.0D, 4.0D, 536.0D);
    bounds(split(s, true).bounds(), 612.0D, 90.0D, 4.0D, 536.0D);
    bounds(split(s, true, true, true).bounds(), 796.0D, 90.0D, 4.0D, 536.0D);
    assertEquals(List.of("details", "problems", "hierarchy"), s.overflowPanelIds());
    assertEquals("properties", tab(s, "properties", "details").activePanelId());
  }

  @Test
  void designGoldenDrawerBottomForPrunedBottomRole() {
    LayoutSnapshot s = solve(designLayout(), 980.0D, 650.0D, false, "problems");
    DrawerBox drawer = s.drawer().orElseThrow();
    assertEquals("problems", drawer.panelId());
    assertEquals(DrawerSide.BOTTOM, drawer.side());
    bounds(drawer.bounds(), 0.0D, 386.0D, 980.0D, 240.0D);
    bounds(box(s, "problems").bounds(), 0.0D, 386.0D, 980.0D, 240.0D);
    assertTrue(box(s, "problems").visible());
    bounds(s.viewport(), 0.0D, 90.0D, 348.0D, 536.0D);
    assertEquals(List.of("details", "problems", "hierarchy"), s.overflowPanelIds());
  }

  @Test
  void dockedDrawerIsNoOpForVisiblePanel() {
    // properties 在 DOCKED 下可见 ⇒ 请求应为空抽屉
    LayoutSnapshot s = solve(designLayout(), 980.0D, 650.0D, false, "properties");
    assertTrue(s.drawer().isEmpty(), "可见面板的抽屉请求必须为空操作");
    bounds(box(s, "properties").bounds(), 352.0D, 118.0D, 260.0D, 508.0D);
    assertEquals(3, s.splitters().size());
  }

  @Test
  void dockedDrawerNeverTouchesViewport() {
    LayoutSnapshot s = solve(designLayout(), 980.0D, 650.0D, false, "mystery");
    assertTrue(s.drawer().isEmpty(), "已可见的 mystery 抽屉请求为空操作");
    LayoutSnapshot t = solve(designLayout(), 980.0D, 650.0D, false, "hierarchy");
    bounds(t.viewport(), 0.0D, 90.0D, 348.0D, 536.0D);
  }

  @Test
  void tabsGroupContainingViewportForcesViewportActive() {
    Tabs tabs = new Tabs("properties", List.of(panel("viewport"), panel("properties")));
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, tabs);
    LayoutSnapshot s = solve(layout, 1280.0D, 720.0D, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    bounds(s.content(), 0.0D, 90.0D, 1280.0D, 606.0D);
    TabBox tabBox = tab(s, "viewport", "properties");
    assertEquals("viewport", tabBox.activePanelId(), "含 viewport 的标签组强制 viewport 激活");
    bounds(tabBox.header(), 0.0D, 90.0D, 1280.0D, 28.0D);
    bounds(s.viewport(), 0.0D, 118.0D, 1280.0D, 578.0D);
    bounds(box(s, "properties").bounds(), 0.0D, 118.0D, 1280.0D, 578.0D);
    assertFalse(box(s, "properties").visible());
    assertTrue(box(s, "viewport").visible());
    assertTrue(s.splitters().isEmpty());
    assertEquals(List.of("properties"), s.overflowPanelIds());
  }

  @Test
  void tabsGroupContainingViewportStillOpensDrawerForInactiveMember() {
    Tabs tabs = new Tabs("properties", List.of(panel("viewport"), panel("properties")));
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, tabs);
    LayoutSnapshot s = solve(layout, 1280.0D, 720.0D, false, "properties");
    DrawerBox drawer = s.drawer().orElseThrow();
    assertEquals("properties", drawer.panelId());
    assertEquals(DrawerSide.RIGHT, drawer.side());
    bounds(drawer.bounds(), 976.0D, 90.0D, 304.0D, 606.0D);
    bounds(box(s, "properties").bounds(), 976.0D, 90.0D, 304.0D, 606.0D);
    assertTrue(box(s, "properties").visible());
    // 持久 TabBox 的 active 不受抽屉影响
    assertEquals("viewport", tab(s, "viewport", "properties").activePanelId());
    bounds(s.viewport(), 0.0D, 118.0D, 1280.0D, 578.0D);
    assertEquals(List.of("properties"), s.overflowPanelIds());
  }

  @Test
  void tabsActiveFallsBackToFirstSurvivingMember() {
    // stored active = problems；prune 先移除 BOTTOM 角色 problems，组内仍剩 [details, mystery]，
    // 因此必须回退到投影组中按原面板顺序的第一个成员 details。
    Tabs tabs = new Tabs("problems",
        List.of(panel("details"), panel("problems"), panel("mystery")));
    DockNode tree = v(0.5D,
        v(0.5D, v(0.5D, panel("viewport"), panel("project")), panel("hierarchy")),
        tabs);
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, tree);
    LayoutSnapshot s = solve(layout, 980.0D, 650.0D, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    TabBox tabBox = tab(s, "details", "mystery");
    assertEquals("details", tabBox.activePanelId(), "stored active 被 prune 后回退到首个存活成员");
    assertTrue(box(s, "details").visible());
    assertFalse(box(s, "mystery").visible());
    bounds(box(s, "problems").bounds(), 0.0D, 90.0D, 0.0D, 0.0D);
    assertFalse(box(s, "problems").visible());
    // 输入树的 stored active 未被改写
    assertEquals("problems", tabs.activePanelId());
    assertTrue(s.drawer().isEmpty());
  }

  // ---------------------------------------------------------------- 阈值与边界

  @Test
  void modeThresholdsAreStrictInequalities() {
    DockLayout layout = defaultLayout(1280.0D, 720.0D);
    // W < 640 或 H < 360 ⇒ TOO_SMALL
    assertEquals(Mode.TOO_SMALL, solve(layout, 639.75D, 720.0D, false, null).mode());
    assertEquals(Mode.TOO_SMALL, solve(layout, 640.0D, 359.75D, false, null).mode());
    // 恰好 640×360 不是 TOO_SMALL（content 640×246 >= 320×220）
    assertEquals(Mode.COMPACT, solve(layout, 640.0D, 360.0D, false, null).mode());
    // COMPACT 阈值：W < 980 或 H < 650
    assertEquals(Mode.COMPACT, solve(layout, 979.75D, 720.0D, false, null).mode());
    assertEquals(Mode.COMPACT, solve(layout, 1280.0D, 649.75D, false, null).mode());
    assertEquals(Mode.DOCKED, solve(layout, 980.0D, 650.0D, false, null).mode());
    // 0 与负尺寸
    assertEquals(Mode.TOO_SMALL, solve(layout, 0.0D, 0.0D, false, null).mode());
    assertEquals(Mode.TOO_SMALL, solve(layout, 1.0D, 1.0D, false, null).mode());
  }

  @Test
  void contentThresholdsAlsoForceTooSmall() {
    // W/H 达标但正文区不足 320×220：banner 吃掉 32 后 640×360 的正文仍为 640×214 < 220
    DockLayout layout = defaultLayout(640.0D, 360.0D);
    LayoutSnapshot s = solve(layout, 640.0D, 360.0D, true, null);
    assertEquals(Mode.TOO_SMALL, s.mode());
    near(s.content().height(), 214.0D);
    near(s.content().height(), Math.max(0.0D, 360.0D - 122.0D - 24.0D));
    // 无 banner 的同一尺寸正文 246 >= 220 ⇒ 不是 TOO_SMALL
    assertEquals(Mode.COMPACT, solve(layout, 640.0D, 360.0D, false, null).mode());
  }

  @Test
  void boundaryScanKeepsContentNonNegativeAndTooSmallExact() {
    double[] widths = {0.0D, 1.0D, 639.75D, 640.0D, 979.75D, 980.0D, 1179.75D, 1180.0D, 1280.0D};
    double[] heights = {0.0D, 1.0D, 89.0D, 114.0D, 359.75D, 360.0D, 649.75D, 650.0D, 899.75D, 900.0D};
    for (boolean banner : new boolean[] {false, true}) {
      for (double w : widths) {
        for (double h : heights) {
          LayoutSnapshot s = solve(defaultLayout(w, h), w, h, banner, null);
          String at = "w=" + w + " h=" + h + " banner=" + banner;
          assertTrue(s.content().width() >= 0.0D && s.content().height() >= 0.0D,
              "content must be non-negative at " + at);
          assertTrue(s.menuBar().height() >= 0.0D && s.documentBar().height() >= 0.0D
              && s.toolbar().height() >= 0.0D && s.statusBar().height() >= 0.0D,
              "chrome heights must be non-negative at " + at);
          if (s.banner().isPresent()) {
            assertTrue(s.banner().orElseThrow().height() >= 0.0D, "banner height at " + at);
          }
          if (w < 640.0D || h < 360.0D) {
            assertEquals(Mode.TOO_SMALL, s.mode(), "expected TOO_SMALL at " + at);
          }
          if (s.mode() != Mode.TOO_SMALL) {
            assertTrue(s.viewport().width() >= 320.0D - EPS && s.viewport().height() >= 220.0D - EPS,
                "viewport below minimum at " + at + ": " + s.viewport());
          }
          assertNoNegativeRects(s, at);
        }
      }
    }
  }

  private static void assertNoNegativeRects(LayoutSnapshot s, String at) {
    List<Bounds> all = new ArrayList<>();
    all.add(s.menuBar());
    all.add(s.documentBar());
    all.add(s.toolbar());
    all.add(s.content());
    all.add(s.statusBar());
    all.add(s.viewport());
    s.banner().ifPresent(all::add);
    s.panels().forEach(p -> all.add(p.bounds()));
    s.tabs().forEach(t -> all.add(t.header()));
    s.splitters().forEach(x -> all.add(x.bounds()));
    s.drawer().ifPresent(d -> {
      all.add(d.bounds());
      all.add(d.scrim());
    });
    for (Bounds b : all) {
      assertTrue(b.width() >= 0.0D && b.height() >= 0.0D, "negative rect " + b + " at " + at);
    }
  }

  @Test
  void zeroSizeWindowStillReturnsLegalSnapshot() {
    LayoutSnapshot s = solve(defaultLayout(0.0D, 0.0D), 0.0D, 0.0D, false, null);
    assertEquals(Mode.TOO_SMALL, s.mode());
    for (Bounds b : new Bounds[] {s.menuBar(), s.documentBar(), s.toolbar(), s.content(),
        s.statusBar(), s.viewport()}) {
      bounds(b, 0.0D, 0.0D, 0.0D, 0.0D);
    }
    assertTrue(s.banner().isEmpty());
    assertTrue(s.tabs().isEmpty());
    assertTrue(s.splitters().isEmpty());
    assertTrue(s.drawer().isEmpty());
    assertEquals(List.of("hierarchy", "viewport", "problems", "properties"),
        s.panels().stream().map(PanelBox::panelId).toList());
    assertTrue(s.panels().stream().noneMatch(PanelBox::visible), "TOO_SMALL 全不可见");
    assertEquals(List.of("hierarchy", "problems", "properties"), s.overflowPanelIds());
  }

  @Test
  void invalidDimensionsThrow() {
    DockLayout layout = defaultLayout(1280.0D, 720.0D);
    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, -0.5D, -1.0D}) {
      assertThrows(IllegalArgumentException.class,
          () -> DockSolver.solve(layout, bad, 720.0D, false, Optional.empty()),
          "width " + bad);
      assertThrows(IllegalArgumentException.class,
          () -> DockSolver.solve(layout, 1280.0D, bad, false, Optional.empty()),
          "height " + bad);
    }
  }

  @Test
  void negativeZeroIsLegalAndBehavesAsZero() {
    LayoutSnapshot s = solve(defaultLayout(-0.0D, -0.0D), -0.0D, -0.0D, false, null);
    assertEquals(Mode.TOO_SMALL, s.mode());
    near(s.content().width(), 0.0D);
    near(s.content().height(), 0.0D);
  }

  @Test
  void nullArgumentsThrow() {
    DockLayout layout = defaultLayout(1280.0D, 720.0D);
    assertThrows(IllegalArgumentException.class,
        () -> DockSolver.solve(null, 1280.0D, 720.0D, false, Optional.empty()));
    assertThrows(IllegalArgumentException.class,
        () -> DockSolver.solve(layout, 1280.0D, 720.0D, false, null));
    // 注：present-null 的 Optional 无法经 JDK 公开 API 构造（Optional.of(null) 直接 NPE），
    // 因此该分支只能由 LayoutSnapshot/DockSolver 的代码审查覆盖，此处不做断言。
  }

  @Test
  void drawerIdMustExistAndNotBeViewport() {
    DockLayout layout = defaultLayout(1280.0D, 720.0D);
    IllegalArgumentException viewport = assertThrows(IllegalArgumentException.class,
        () -> solve(layout, 1280.0D, 720.0D, false, "viewport"));
    assertEquals("drawerPanelId must not be the 'viewport' panel", viewport.getMessage());

    IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
        () -> solve(layout, 1280.0D, 720.0D, false, "nope"));
    assertEquals("drawerPanelId 'nope' is not part of the layout tree", unknown.getMessage());

    // 非法语法同样被拒
    assertThrows(IllegalArgumentException.class,
        () -> solve(layout, 1280.0D, 720.0D, false, "Nope"));
  }

  @Test
  void drawerIsValidatedBeforeModeSelection() {
    // 即使窗口 TOO_SMALL，非法抽屉 ID 仍应抛错
    DockLayout layout = defaultLayout(1280.0D, 720.0D);
    assertThrows(IllegalArgumentException.class,
        () -> solve(layout, 0.0D, 0.0D, false, "viewport"));
    assertThrows(IllegalArgumentException.class,
        () -> solve(layout, 0.0D, 0.0D, false, "nope"));
  }

  @Test
  void hugeFiniteVectorsStayFinite() {
    DockLayout layout = defaultLayout(1280.0D, 720.0D);
    double w = 1.0e9D;
    double h = 1.0e9D;
    LayoutSnapshot s = solve(layout, w, h, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    List<Bounds> all = new ArrayList<>(List.of(s.menuBar(), s.documentBar(), s.toolbar(), s.content(),
        s.statusBar(), s.viewport()));
    s.panels().forEach(p -> all.add(p.bounds()));
    s.splitters().forEach(x -> all.add(x.bounds()));
    for (Bounds b : all) {
      assertTrue(Double.isFinite(b.left()) && Double.isFinite(b.top())
          && Double.isFinite(b.width()) && Double.isFinite(b.height()), "non-finite rect " + b);
    }
    near(s.content().width(), w);
    assertTrue(s.viewport().width() >= 320.0D);
    assertTrue(s.viewport().height() >= 220.0D);
  }

  @Test
  void unknownPanelIdsAreRetainedLikeKnownOnes() {
    DockNode tree = h(0.5D, panel("viewport"), panel("mystery.panel-1"));
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, tree);
    LayoutSnapshot s = solve(layout, 1280.0D, 720.0D, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    assertEquals(List.of("viewport", "mystery.panel-1"), s.panels().stream()
        .map(PanelBox::panelId).toList());
    assertTrue(box(s, "mystery.panel-1").visible());
    // 未知叶按「其他面板」最小 180×120
    assertTrue(box(s, "mystery.panel-1").bounds().width() >= 180.0D - EPS);
    assertTrue(box(s, "mystery.panel-1").bounds().height() >= 120.0D - EPS);
  }

  // ---------------------------------------------------------------- 快照构造期 guard

  /** 一个各成员都合法的最小快照，供逐项替换以验证 guard。 */
  private static LayoutSnapshot validSnapshot() {
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    return new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero, zero, zero,
        List.of(new PanelBox("viewport", zero, true)), List.of(), List.of(), Optional.empty(),
        List.of());
  }

  @Test
  void snapshotModeAndRequiredBoundsRejectNull() {
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new LayoutSnapshot(null, zero, zero, zero, Optional.empty(), zero, zero, zero,
            List.of(), List.of(), List.of(), Optional.empty(), List.of()));
    for (int slot = 0; slot < 6; slot++) {
      final int index = slot;
      assertThrows(IllegalArgumentException.class, () -> {
        Bounds[] b = {zero, zero, zero, zero, zero, zero};
        b[index] = null;
        new LayoutSnapshot(Mode.DOCKED, b[0], b[1], b[2], Optional.empty(), b[3], b[4], b[5],
            List.of(), List.of(), List.of(), Optional.empty(), List.of());
      }, "bounds slot " + slot);
    }
  }

  @Test
  void snapshotRejectsPresentNullOptionals() {
    LayoutSnapshot base = validSnapshot();
    assertThrows(IllegalArgumentException.class, () -> new LayoutSnapshot(base.mode(),
        base.menuBar(), base.documentBar(), base.toolbar(), null, base.content(), base.statusBar(),
        base.viewport(), base.panels(), base.tabs(), base.splitters(), base.drawer(),
        base.overflowPanelIds()), "banner Optional 为 null 必须被拒");
    assertThrows(IllegalArgumentException.class, () -> new LayoutSnapshot(base.mode(),
        base.menuBar(), base.documentBar(), base.toolbar(), base.banner(), base.content(),
        base.statusBar(), base.viewport(), base.panels(), base.tabs(), base.splitters(), null,
        base.overflowPanelIds()), "drawer Optional 为 null 必须被拒");
    // 注：present-null 的 Optional（isPresent()==true 但 get()==null）无法用 JDK 公开 API 构造，
    // 该防御分支由 requireOptionalBounds/requireOptionalDrawer 的代码审查覆盖，此处不可达。
  }

  @Test
  void snapshotRejectsNullLists() {
    LayoutSnapshot base = validSnapshot();
    assertThrows(IllegalArgumentException.class, () -> new LayoutSnapshot(base.mode(),
        base.menuBar(), base.documentBar(), base.toolbar(), base.banner(), base.content(),
        base.statusBar(), base.viewport(), null, base.tabs(), base.splitters(), base.drawer(),
        base.overflowPanelIds()));
    assertThrows(IllegalArgumentException.class, () -> new LayoutSnapshot(base.mode(),
        base.menuBar(), base.documentBar(), base.toolbar(), base.banner(), base.content(),
        base.statusBar(), base.viewport(), base.panels(), null, base.splitters(), base.drawer(),
        base.overflowPanelIds()));
    assertThrows(IllegalArgumentException.class, () -> new LayoutSnapshot(base.mode(),
        base.menuBar(), base.documentBar(), base.toolbar(), base.banner(), base.content(),
        base.statusBar(), base.viewport(), base.panels(), base.tabs(), null, base.drawer(),
        base.overflowPanelIds()));
    assertThrows(IllegalArgumentException.class, () -> new LayoutSnapshot(base.mode(),
        base.menuBar(), base.documentBar(), base.toolbar(), base.banner(), base.content(),
        base.statusBar(), base.viewport(), base.panels(), base.tabs(), base.splitters(),
        base.drawer(), null));
  }

  @Test
  void snapshotListBudgetIsEnforcedBeforeAllocation() {
    LayoutSnapshot base = validSnapshot();
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    List<PanelBox> tooManyPanels = new ArrayList<>();
    for (int i = 0; i < 65; i++) {
      tooManyPanels.add(new PanelBox("p" + i, zero, false));
    }
    IllegalArgumentException panels = assertThrows(IllegalArgumentException.class,
        () -> new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero, zero, zero,
            tooManyPanels, List.of(), List.of(), Optional.empty(), List.of()));
    assertEquals("panels size 65 exceeds budget 64", panels.getMessage());

    List<String> tooManyIds = new ArrayList<>();
    for (int i = 0; i < 65; i++) {
      tooManyIds.add("p" + i);
    }
    IllegalArgumentException ids = assertThrows(IllegalArgumentException.class,
        () -> new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero, zero, zero,
            List.of(), List.of(), List.of(), Optional.empty(), tooManyIds));
    assertEquals("overflowPanelIds size 65 exceeds budget 64", ids.getMessage());
    // 恰好 64 合法
    List<String> exactly = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      exactly.add("p" + i);
    }
    new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero, zero, zero, List.of(),
        List.of(), List.of(), Optional.empty(), exactly);
    assertNotNull(base);
  }

  @Test
  void snapshotPanelIdsMustBeUniqueAndWellFormed() {
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
        () -> new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero, zero, zero,
            List.of(new PanelBox("viewport", zero, true), new PanelBox("viewport", zero, false)),
            List.of(), List.of(), Optional.empty(), List.of()));
    assertEquals("panels contains duplicate panel id 'viewport'", dup.getMessage());
    // 语法非法：大写
    assertThrows(IllegalArgumentException.class,
        () -> new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero, zero, zero,
            List.of(new PanelBox("Viewport", zero, true)), List.of(), List.of(), Optional.empty(),
            List.of()));
    // 逐项 null
    List<PanelBox> withNull = new ArrayList<>();
    withNull.add(new PanelBox("viewport", zero, true));
    withNull.add(null);
    IllegalArgumentException nullItem = assertThrows(IllegalArgumentException.class,
        () -> new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero, zero, zero,
            withNull, List.of(), List.of(), Optional.empty(), List.of()));
    assertEquals("panels[1] must not be null", nullItem.getMessage());
  }

  @Test
  void snapshotTabBoxInvariants() {
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    assertThrows(IllegalArgumentException.class, () -> new TabBox(List.of(), "viewport", zero));
    assertThrows(IllegalArgumentException.class,
        () -> new TabBox(List.of("viewport", "viewport"), "viewport", zero));
    assertThrows(IllegalArgumentException.class,
        () -> new TabBox(List.of("viewport"), "properties", zero));
    assertThrows(IllegalArgumentException.class,
        () -> new TabBox(List.of("viewport"), null, zero));
    assertThrows(IllegalArgumentException.class,
        () -> new TabBox(List.of("viewport"), "viewport", null));
    assertThrows(IllegalArgumentException.class, () -> new TabBox(null, "viewport", zero));
    // 合法
    TabBox ok = new TabBox(List.of("properties", "details"), "details", zero);
    assertEquals("details", ok.activePanelId());
    assertEquals(List.of("properties", "details"), ok.panelIds());
  }

  @Test
  void snapshotSplitBoxInvariants() {
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new SplitBox(null, Axis.HORIZONTAL, zero));
    assertThrows(IllegalArgumentException.class, () -> new SplitBox(List.of(), null, zero));
    assertThrows(IllegalArgumentException.class, () -> new SplitBox(List.of(), Axis.VERTICAL, null));
    List<Boolean> withNull = new ArrayList<>();
    withNull.add(Boolean.TRUE);
    withNull.add(null);
    assertThrows(IllegalArgumentException.class,
        () -> new SplitBox(withNull, Axis.HORIZONTAL, zero));
    // 合法：根路径为空
    SplitBox ok = new SplitBox(List.of(), Axis.HORIZONTAL, zero);
    assertEquals(List.of(), ok.nodePath());
    assertEquals(Axis.HORIZONTAL, ok.axis());
  }

  @Test
  void snapshotDrawerBoxInvariants() {
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new DrawerBox("properties", null, zero, zero));
    assertThrows(IllegalArgumentException.class,
        () -> new DrawerBox("properties", DrawerSide.RIGHT, null, zero));
    assertThrows(IllegalArgumentException.class,
        () -> new DrawerBox("properties", DrawerSide.RIGHT, zero, null));
    assertThrows(IllegalArgumentException.class,
        () -> new DrawerBox("Properties", DrawerSide.RIGHT, zero, zero));
    DrawerBox ok = new DrawerBox("properties", DrawerSide.BOTTOM, zero, zero);
    assertEquals(DrawerSide.BOTTOM, ok.side());
  }

  @Test
  void boundsRejectsNonFiniteAndNegativeExtent() {
    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> new Bounds(bad, 0.0D, 1.0D, 1.0D));
      assertThrows(IllegalArgumentException.class, () -> new Bounds(0.0D, bad, 1.0D, 1.0D));
      assertThrows(IllegalArgumentException.class, () -> new Bounds(0.0D, 0.0D, bad, 1.0D));
      assertThrows(IllegalArgumentException.class, () -> new Bounds(0.0D, 0.0D, 1.0D, bad));
    }
    assertThrows(IllegalArgumentException.class, () -> new Bounds(0.0D, 0.0D, -1.0D, 1.0D));
    assertThrows(IllegalArgumentException.class, () -> new Bounds(0.0D, 0.0D, 1.0D, -1.0D));
    // left/top 可为负
    Bounds negativeOrigin = new Bounds(-5.0D, -7.0D, 10.0D, 10.0D);
    near(negativeOrigin.right(), 5.0D);
    near(negativeOrigin.bottom(), 3.0D);
  }

  @Test
  void boundsContainsIsTopLeftInclusiveBottomRightExclusive() {
    Bounds b = new Bounds(10.0D, 20.0D, 100.0D, 50.0D);
    assertTrue(b.contains(10.0D, 20.0D), "左上含");
    assertTrue(b.contains(109.999D, 69.999D));
    assertFalse(b.contains(110.0D, 20.0D), "右下不含（x）");
    assertFalse(b.contains(10.0D, 70.0D), "右下不含（y）");
    assertFalse(b.contains(9.999D, 20.0D));
    assertFalse(b.contains(10.0D, 19.999D));
    // 空矩形恒为 false（含左上角点）
    Bounds empty = new Bounds(10.0D, 20.0D, 0.0D, 0.0D);
    assertFalse(empty.contains(10.0D, 20.0D));
    Bounds flatWidth = new Bounds(10.0D, 20.0D, 0.0D, 50.0D);
    assertFalse(flatWidth.contains(10.0D, 25.0D));
    Bounds flatHeight = new Bounds(10.0D, 20.0D, 100.0D, 0.0D);
    assertFalse(flatHeight.contains(15.0D, 20.0D));
    // 非有限点先抛 IAE
    assertThrows(IllegalArgumentException.class, () -> b.contains(Double.NaN, 20.0D));
    assertThrows(IllegalArgumentException.class, () -> b.contains(10.0D, Double.POSITIVE_INFINITY));
  }

  @Test
  void snapshotIsDefensivelyCopied() {
    Bounds zero = new Bounds(0.0D, 0.0D, 0.0D, 0.0D);
    List<PanelBox> panels = new ArrayList<>();
    panels.add(new PanelBox("viewport", zero, true));
    List<String> overflow = new ArrayList<>();
    overflow.add("properties");
    LayoutSnapshot s = new LayoutSnapshot(Mode.DOCKED, zero, zero, zero, Optional.empty(), zero,
        zero, zero, panels, List.of(), List.of(), Optional.empty(), overflow);
    panels.add(new PanelBox("properties", zero, false));
    overflow.add("details");
    assertEquals(1, s.panels().size(), "panels 必须防御复制");
    assertEquals(List.of("properties"), s.overflowPanelIds());
    assertThrows(UnsupportedOperationException.class,
        () -> s.panels().add(new PanelBox("details", zero, false)));
    assertThrows(UnsupportedOperationException.class, () -> s.overflowPanelIds().add("details"));
  }

  // ---------------------------------------------------------------- 独立 oracle

  /**
   * 独立 oracle：随机生成 ≥1000 棵互不相同的合法树，仅从原树（叶最小尺寸 + 二分划分）推导期望，
   * 不读取任何 solver 输出。窗口取 4096+ 以保证 DOCKED 且不触发 prune；overflow 仍可能非空，
   * 因为 Tabs 组内未激活成员按规格进入 overflow（本用例逐叶校验该集合而非断言其为空）。
   */
  @Test
  void independentOracleOverThousandDistinctTrees() {
    Random random = new Random(2026101452L);
    List<String> ids = new ArrayList<>(List.of("viewport", "properties", "hierarchy", "problems"));
    Set<String> seenTrees = new HashSet<>();
    int checks = 0;
    int trees = 0;
    int attempts = 0;
    while (trees < 1000) {
      attempts++;
      assertTrue(attempts <= 100000, "fixture 无法生成 1000 棵不同树，仅得 " + trees);
      List<String> leaves = new ArrayList<>(ids);
      int extra = random.nextInt(5);
      for (int j = 0; j < extra; j++) {
        leaves.add("unknown" + j);
      }
      DockNode root = randomTree(random, leaves);
      DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, root);
      String key = root.toString();
      if (!seenTrees.add(key)) {
        continue;
      }
      trees++;
      double w = 4096.0D + trees % 71;
      double h = 4096.0D + trees % 89;
      LayoutSnapshot s = solve(layout, w, h, false, null);
      assertEquals(Mode.DOCKED, s.mode(), "expected DOCKED for tree " + key);
      assertTrue(s.drawer().isEmpty());
      List<String> specOrder = specLeaves(root);
      assertTrue(sameIds(specOrder, s.panels().stream().map(PanelBox::panelId).toList()),
          "leaf id 集与顺序必须等于原树: " + key);
      // 无抽屉时 overflow = 原叶序中所有「非 viewport 且不可见」的叶（含未激活的 Tabs 成员）
      List<String> expectedOverflow = new ArrayList<>();
      for (PanelBox p : s.panels()) {
        if (!p.panelId().equals("viewport") && !p.visible()) {
          expectedOverflow.add(p.panelId());
        }
      }
      assertEquals(expectedOverflow, s.overflowPanelIds(), "overflow 必须是原叶序子序列: " + key);
      assertEquals(s.overflowPanelIds().size(), new HashSet<>(s.overflowPanelIds()).size(),
          "overflow 不得重复: " + key);
      // 先核 snapshot.content 与独立 expected，再把该 expected 作为 oracle 根输入
      Bounds expectedContent = specContent(w, h, false);
      bounds(s.content(), expectedContent.left(), expectedContent.top(), expectedContent.width(),
          expectedContent.height());
      partition(root, List.of(), expectedContent, s);
      checks++;
      assertVisibleDisjoint(s);
    }
    assertTrue(checks >= 1000, "must check >=1000 trees, was " + checks);
  }

  /** 默认扫描：所有非 TOO_SMALL 结果都必须给出 ≥320×220 的视口。 */
  @Test
  void defaultScanKeepsViewportAtLeastMinimum() {
    int checked = 0;
    int tooSmall = 0;
    for (int w = 640; w <= 2048; w += 17) {
      for (int h = 360; h <= 1200; h += 19) {
        LayoutSnapshot s = solve(defaultLayout(w, h), w, h, false, null);
        if (s.mode() == Mode.TOO_SMALL) {
          tooSmall++;
          continue;
        }
        assertTrue(s.viewport().width() >= 320.0D - EPS && s.viewport().height() >= 220.0D - EPS,
            "viewport below minimum at " + w + "x" + h + ": " + s.viewport());
        assertTrue(s.content().contains(s.viewport().left(), s.viewport().top()),
            "viewport origin inside content at " + w + "x" + h);
        assertTrue(s.content().contains(s.viewport().right() - EPS, s.viewport().bottom() - EPS),
            "viewport corner inside content at " + w + "x" + h);
        assertVisibleDisjoint(s);
        checked++;
      }
    }
    assertTrue(checked > 0, "scan must cover at least one non-TOO_SMALL size");
    assertTrue(tooSmall >= 0);
  }

  @Test
  void defaultScanWithBannerKeepsInvariants() {
    for (int w = 640; w <= 2048; w += 37) {
      for (int h = 360; h <= 1200; h += 41) {
        LayoutSnapshot s = solve(defaultLayout(w, h), w, h, true, null);
        assertTrue(s.banner().isPresent());
        assertTrue(s.content().height() >= 0.0D);
        if (s.mode() != Mode.TOO_SMALL) {
          assertTrue(s.viewport().height() >= 220.0D - EPS, "banner viewport too short at "
              + w + "x" + h + ": " + s.viewport());
        }
        assertNoNegativeRects(s, "banner " + w + "x" + h);
      }
    }
  }

  @Test
  void deepTreeAtDepthBudgetStillSolves() {
    // 深度 16（根 depth=1）：8 次水平 + 7 次垂直，最小宽 1792、最小高 1088
    DockNode node = panel("viewport");
    for (int i = 0; i < 15; i++) {
      Axis axis = i % 2 == 0 ? Axis.HORIZONTAL : Axis.VERTICAL;
      node = new Split(axis, 0.5D, node, panel("p" + i));
    }
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, node);
    DockValidation.validate(node);
    double[] m = specMin(node);
    near(m[0], 1792.0D);
    near(m[1], 1088.0D);
    LayoutSnapshot s = solve(layout, 1920.0D, 1220.0D, false, null);
    assertEquals(Mode.DOCKED, s.mode());
    assertTrue(s.overflowPanelIds().isEmpty(), "deep tree must fit without prune");
    assertTrue(s.viewport().width() >= 320.0D - EPS, "viewport width " + s.viewport().width());
    assertTrue(s.viewport().height() >= 220.0D - EPS);
    for (SplitBox box : s.splitters()) {
      assertTrue(box.nodePath().size() <= 16, "nodePath too long: " + box.nodePath());
    }
    // 先核 snapshot.content 与独立 expected，再把该 expected 作为 oracle 根输入
    Bounds expectedContent = specContent(1920.0D, 1220.0D, false);
    bounds(s.content(), expectedContent.left(), expectedContent.top(), expectedContent.width(),
        expectedContent.height());
    partition(node, List.of(), expectedContent, s);
    assertNoNegativeRects(s, "deep");
  }

  @Test
  void extremeRatiosNeverStarveViewport() {
    for (double ratio : new double[] {0.05D, 0.5D, 0.95D}) {
      DockNode root = h(ratio, panel("viewport"), panel("properties"));
      LayoutSnapshot s = solve(new DockLayout(DockLayout.SCHEMA_VERSION, root), 1280.0D, 720.0D,
          false, null);
      assertEquals(Mode.DOCKED, s.mode(), "ratio " + ratio);
      assertTrue(s.viewport().width() >= 320.0D - EPS, "viewport starved at ratio " + ratio);
      assertTrue(box(s, "properties").bounds().width() >= 260.0D - EPS,
          "properties below minimum at ratio " + ratio);
    }
  }

  @Test
  void verticalSplitGivesBothSidesTheirMinimum() {
    DockNode root = v(0.95D, panel("viewport"), panel("problems"));
    LayoutSnapshot s = solve(new DockLayout(DockLayout.SCHEMA_VERSION, root), 1280.0D, 720.0D,
        false, null);
    assertEquals(Mode.DOCKED, s.mode());
    assertTrue(box(s, "viewport").bounds().height() >= 220.0D - EPS);
    assertTrue(box(s, "problems").bounds().height() >= 120.0D - EPS);
    SplitBox divider = split(s);
    assertEquals(Axis.VERTICAL, divider.axis());
    near(divider.bounds().height(), 4.0D);
    // 相邻：viewport 底 == 分隔条顶，分隔条底 == problems 顶
    near(box(s, "viewport").bounds().bottom(), divider.bounds().top());
    near(divider.bounds().bottom(), box(s, "problems").bounds().top());
  }

  @Test
  void splitterAlwaysSeparatesAdjacentSiblings() {
    DockLayout layout = defaultLayout(1920.0D, 1080.0D);
    LayoutSnapshot s = solve(layout, 1920.0D, 1080.0D, false, null);
    SplitBox root = split(s);
    near(root.bounds().left(), box(s, "hierarchy").bounds().right());
    near(root.bounds().right(), box(s, "viewport").bounds().left());
    SplitBox outer = split(s, true);
    near(outer.bounds().right(), box(s, "properties").bounds().left());
    SplitBox inner = split(s, true, false);
    near(inner.bounds().top(), box(s, "viewport").bounds().bottom());
    near(inner.bounds().bottom(), box(s, "problems").bounds().top());
  }

  @Test
  void splitterNodePathsMatchOriginalTreeStructure() {
    DockLayout layout = defaultLayout(1920.0D, 1080.0D);
    LayoutSnapshot s = solve(layout, 1920.0D, 1080.0D, false, null);
    DockNode root = layout.root();
    for (SplitBox box : s.splitters()) {
      DockNode cursor = root;
      for (boolean right : box.nodePath()) {
        assertTrue(cursor instanceof Split, "nodePath 指向非 Split 节点");
        Split split = (Split) cursor;
        cursor = right ? split.second() : split.first();
      }
      assertTrue(cursor instanceof Split, "nodePath 末端必须是 Split");
      assertEquals(((Split) cursor).axis(), box.axis(), "axis 必须等于原树节点 axis");
    }
    // 根路径为空
    assertEquals(List.of(), split(s).nodePath());
    assertEquals(List.of(Boolean.TRUE), split(s, true).nodePath());
    assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), split(s, true, false).nodePath());
  }

  @Test
  void allPanelBoxesAppearInOriginalLeafOrder() {
    for (double[] size : new double[][] {{1280.0D, 720.0D}, {854.0D, 480.0D}, {200.0D, 200.0D}}) {
      DockLayout layout = defaultLayout(size[0], size[1]);
      LayoutSnapshot s = solve(layout, size[0], size[1], false, null);
      assertEquals(specLeaves(layout.root()), s.panels().stream().map(PanelBox::panelId).toList(),
          "panels 必须按原树叶顺序且完整覆盖");
    }
  }

  @Test
  void overflowAndPanelsTogetherCoverEveryLeaf() {
    DockLayout layout = designLayout();
    List<String> leaves = specLeaves(layout.root());
    for (String drawer : new String[] {null, "hierarchy", "problems", "details"}) {
      LayoutSnapshot s = solve(layout, 980.0D, 650.0D, false, drawer);
      assertTrue(sameIds(leaves, s.panels().stream().map(PanelBox::panelId).toList()),
          "panels 必须按原叶序覆盖全部叶");
      Set<String> hidden = new HashSet<>(s.overflowPanelIds());
      assertEquals(s.overflowPanelIds().size(), hidden.size(), "overflow 不得重复");
      assertFalse(hidden.contains("viewport"), "viewport 永不进入 overflow");
      // 每个叶：要么可见，要么在 overflow 中；overlay 抽屉面板是唯一同时满足两者的例外
      for (PanelBox p : s.panels()) {
        boolean inOverflow = hidden.contains(p.panelId());
        if (p.visible()) {
          assertTrue(!inOverflow || p.panelId().equals(drawer),
              p.panelId() + " 可见却进入 overflow");
        } else {
          assertTrue(inOverflow, p.panelId() + " 不可见却不在 overflow 中");
        }
      }
      // overflow = 原叶序中所有「非 viewport 且未在正文里可见」的叶；overlay 抽屉面板
      // 在改写可见性之前就已登记，因此它仍然留在 overflow 中
      String overlayId = s.drawer().map(DrawerBox::panelId).orElse(null);
      List<String> expected = new ArrayList<>();
      for (PanelBox p : s.panels()) {
        if (p.panelId().equals("viewport")) {
          continue;
        }
        if (!p.visible() || p.panelId().equals(overlayId)) {
          expected.add(p.panelId());
        }
      }
      assertEquals(expected, s.overflowPanelIds(), "overflow 必须是原叶序子序列");
    }
  }

  // ------------------------------------------------------------------
  // R16.2b2 巨大有限值正式回归
  // ------------------------------------------------------------------

  @Test
  void boundsRejectFiniteComponentsWhoseEdgesOverflow() {
    // 四数本身 finite，但 left+width / top+height 溢出为 Infinity，必须严格 IAE
    assertThrows(IllegalArgumentException.class,
        () -> new Bounds(Double.MAX_VALUE, 0.0D, Double.MAX_VALUE, 1.0D));
    assertThrows(IllegalArgumentException.class,
        () -> new Bounds(0.0D, Double.MAX_VALUE, 1.0D, Double.MAX_VALUE));
    // 合法对照：边仍 finite
    Bounds whole = new Bounds(0.0D, 0.0D, Double.MAX_VALUE, Double.MAX_VALUE);
    assertTrue(Double.isFinite(whole.right()) && Double.isFinite(whole.bottom()),
        "whole right/bottom must be finite");
    Bounds shifted = new Bounds(-Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
    assertTrue(Double.isFinite(shifted.right()) && Double.isFinite(shifted.bottom()),
        "shifted right/bottom must be finite");
  }

  @Test
  void hugeDefaultLayoutsKeepFiniteBoundsAndExactMinimums() {
    double[][] sizes = {{1e100D, 1e100D}, {Double.MAX_VALUE, Double.MAX_VALUE}};
    int cases = 0;
    for (double[] size : sizes) {
      for (boolean banner : new boolean[] {false, true}) {
        cases++;
        double w = size[0];
        double h = size[1];
        DockLayout layout = defaultLayout(w, h);
        DockNode before = layout.root();
        LayoutSnapshot s = solve(layout, w, h, banner, null);
        String at = "default w=" + w + " h=" + h + " banner=" + banner;

        assertEquals(Mode.DOCKED, s.mode(), at + ": mode");
        // 固定默认拓扑：叶序是独立常量，不从生产 defaultLayout 反推；默认拓扑/叶序不随尺寸变，
        // 随尺寸变化的只有 ratio。分别对 spec 树与实际 snapshot 的序断言。
        List<String> expectedOrder = List.of("hierarchy", "viewport", "problems", "properties");
        assertEquals(expectedOrder, specLeaves(layout.root()), at + ": spec leaf order");
        assertEquals(expectedOrder, s.panels().stream().map(PanelBox::panelId).toList(),
            at + ": actual leaf order");
        // 四叶全部可见、无 prune、无 drawer
        assertTrue(s.overflowPanelIds().isEmpty(), at + ": overflow must be empty " + s.overflowPanelIds());
        assertTrue(s.drawer().isEmpty(), at + ": drawer must be absent");
        for (PanelBox p : s.panels()) {
          assertTrue(p.visible(), at + ": panel " + p.panelId() + " must be visible");
        }
        // 原树 equals 保持
        assertEquals(new DockLayout(layout.schemaVersion(), before), layout, at + ": layout mutated");

        // chrome / content / viewport / panel / tab / splitter / drawer 相关矩形全部 finite 且宽高 >= 0
        assertFiniteRect(s.menuBar(), at + " menuBar");
        assertFiniteRect(s.documentBar(), at + " documentBar");
        assertFiniteRect(s.toolbar(), at + " toolbar");
        assertFiniteRect(s.content(), at + " content");
        assertFiniteRect(s.statusBar(), at + " statusBar");
        assertFiniteRect(s.viewport(), at + " viewport");
        s.banner().ifPresent(b -> assertFiniteRect(b, at + " banner"));
        for (PanelBox p : s.panels()) {
          assertFiniteRect(p.bounds(), at + " panel " + p.panelId());
        }
        for (TabBox t : s.tabs()) {
          assertFiniteRect(t.header(), at + " tab header " + t.panelIds());
          Bounds tabBody = new Bounds(t.header().left(), t.header().bottom(), t.header().width(), 0.0D);
          assertFiniteRect(tabBody, at + " tab body " + t.panelIds());
        }
        for (SplitBox sp : s.splitters()) {
          assertFiniteRect(sp.bounds(), at + " splitter " + sp.nodePath());
        }
        s.drawer().ifPresent(d -> assertFiniteRect(d.bounds(), at + " drawer"));

        // 正文矩形包含检查（巨大尺度安全容差）
        assertInsideContent(s.viewport(), s.content(), at + " viewport in content");
        for (PanelBox p : s.panels()) {
          assertInsideContent(p.bounds(), s.content(), at + " panel " + p.panelId() + " in content");
        }
        for (SplitBox sp : s.splitters()) {
          assertInsideContent(sp.bounds(), s.content(), at + " splitter " + sp.nodePath() + " in content");
        }

        // 四个 Panel 严格实际 width/height >= 角色 min（不减 epsilon）
        for (PanelBox p : s.panels()) {
          double[] m = specMin(panel(p.panelId()));
          assertTrue(p.bounds().width() >= m[0],
              at + ": panel " + p.panelId() + " width " + p.bounds().width() + " < min " + m[0]);
          assertTrue(p.bounds().height() >= m[1],
              at + ": panel " + p.panelId() + " height " + p.bounds().height() + " < min " + m[1]);
        }
        // viewport 严格 320×220
        assertTrue(s.viewport().width() >= 320.0D && s.viewport().height() >= 220.0D,
            at + ": viewport below 320x220: " + s.viewport());
        // 独立 oracle 内容对照（expected 由输入独立求得）。
        // 不调用 partition：既有 partition 用普通尺寸绝对 1e-8，与本卡巨大数值相对 1e-12 不一致。
        Bounds expectedContent = specContent(w, h, banner);
        relativeBounds(s.content(), expectedContent.left(), expectedContent.top(),
            expectedContent.width(), expectedContent.height(), at + " content");
      }
    }
    assertEquals(4, cases, "default case count");
  }

  @Test
  void hugeHorizontalSoftCapsPreserveBothMinimums() {
    double[] ratios = {0.05D, 0.5D, 0.95D};
    double[] widths = {1e100D, Double.MAX_VALUE};
    int cases = 0;
    for (double w : widths) {
      for (boolean viewportFirst : new boolean[] {true, false}) {
        for (double ratio : ratios) {
          cases++;
          double h = 1080.0D;
          double[] vpMin = {320.0D, 220.0D};
          double[] prMin = {260.0D, 120.0D};
          DockNode vp = panel("viewport");
          DockNode pr = panel("properties");
          Split root = viewportFirst ? h(ratio, vp, pr) : h(ratio, pr, vp);
          DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, root);
          DockNode before = layout.root();
          LayoutSnapshot s = solve(layout, w, h, false, null);
          String at = "H w=" + w + " viewportFirst=" + viewportFirst + " ratio=" + ratio;

          assertEquals(Mode.DOCKED, s.mode(), at + ": mode");
          assertEquals(viewportFirst ? List.of("viewport", "properties")
              : List.of("properties", "viewport"),
              s.panels().stream().map(PanelBox::panelId).toList(), at + ": actual leaf order");
          assertEquals(new DockLayout(layout.schemaVersion(), before), layout, at + ": layout mutated");

          Bounds expectedContent = specContent(w, h, false);
          relativeBounds(s.content(), expectedContent.left(), expectedContent.top(),
              expectedContent.width(), expectedContent.height(), at + " content");

          // 独立期望长度
          double[] len = expectedTwoLeafLengths(w, h, false, Axis.HORIZONTAL, ratio, vpMin, prMin,
              420.0D, viewportFirst);
          double first = len[0];
          double second = len[1];
          Bounds firstRect = new Bounds(expectedContent.left(), expectedContent.top(), first,
              expectedContent.height());
          Bounds dividerRect = new Bounds(expectedContent.left() + first, expectedContent.top(), 4.0D,
              expectedContent.height());
          Bounds secondRect = new Bounds(dividerRect.right(), expectedContent.top(), second,
              expectedContent.height());

          // root SplitBox 路径为空且 axis 相符
          SplitBox rootBox = split(s, new Boolean[0]);
          assertEquals(Axis.HORIZONTAL, rootBox.axis(), at + ": root axis");
          relativeBounds(rootBox.bounds(), dividerRect.left(), dividerRect.top(),
              dividerRect.width(), dividerRect.height(), at + " divider");

          // 两叶矩形独立核对
          relativeBounds(box(s, viewportFirst ? "viewport" : "properties").bounds(),
              firstRect.left(), firstRect.top(), firstRect.width(), firstRect.height(),
              at + " first");
          relativeBounds(box(s, viewportFirst ? "properties" : "viewport").bounds(),
              secondRect.left(), secondRect.top(), secondRect.width(), secondRect.height(),
              at + " second");

          // snapshot.viewport 独立核对：期望只能来自输入求出的两个矩形，不从 actual 反推。
          // PanelBox 正确不能代替 viewport 字段正确。
          Bounds vpExpected = viewportFirst ? firstRect : secondRect;
          relativeBounds(s.viewport(), vpExpected.left(), vpExpected.top(), vpExpected.width(),
              vpExpected.height(), at + " viewport");
          assertFiniteRect(s.viewport(), at + " viewport");
          assertInsideContent(s.viewport(), s.content(), at + " viewport in content");
          assertTrue(s.viewport().width() >= vpMin[0] && s.viewport().height() >= vpMin[1],
              at + ": viewport below " + vpMin[0] + "x" + vpMin[1] + ": " + s.viewport());

          // 所有 bounds finite / 在 content 内；两 visible 各角色 min 严格成立
          for (PanelBox p : s.panels()) {
            assertFiniteRect(p.bounds(), at + " panel " + p.panelId());
            assertInsideContent(p.bounds(), s.content(), at + " panel " + p.panelId());
            assertTrue(p.visible(), at + ": panel " + p.panelId() + " must be visible");
            double[] m = specMin(panel(p.panelId()));
            assertTrue(p.bounds().width() >= m[0],
                at + ": " + p.panelId() + " width " + p.bounds().width() + " < min " + m[0]);
            assertTrue(p.bounds().height() >= m[1],
                at + ": " + p.panelId() + " height " + p.bounds().height() + " < min " + m[1]);
          }
        }
      }
    }
    assertEquals(12, cases, "horizontal two-leaf case count");
  }

  @Test
  void hugeVerticalSoftCapsPreserveBothMinimums() {
    double[] ratios = {0.05D, 0.5D, 0.95D};
    double[] heights = {1e100D, Double.MAX_VALUE};
    int cases = 0;
    for (double h : heights) {
      for (boolean viewportFirst : new boolean[] {true, false}) {
        for (double ratio : ratios) {
          cases++;
          double w = 1920.0D;
          double[] vpMin = {320.0D, 220.0D};
          double[] prMin = {260.0D, 120.0D};
          DockNode vp = panel("viewport");
          DockNode pr = panel("properties");
          Split root = viewportFirst ? v(ratio, vp, pr) : v(ratio, pr, vp);
          DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, root);
          DockNode before = layout.root();
          LayoutSnapshot s = solve(layout, w, h, false, null);
          String at = "V h=" + h + " viewportFirst=" + viewportFirst + " ratio=" + ratio;

          assertEquals(Mode.DOCKED, s.mode(), at + ": mode");
          assertEquals(viewportFirst ? List.of("viewport", "properties")
              : List.of("properties", "viewport"),
              s.panels().stream().map(PanelBox::panelId).toList(), at + ": actual leaf order");
          assertEquals(new DockLayout(layout.schemaVersion(), before), layout, at + ": layout mutated");

          Bounds expectedContent = specContent(w, h, false);
          relativeBounds(s.content(), expectedContent.left(), expectedContent.top(),
              expectedContent.width(), expectedContent.height(), at + " content");

          double[] len = expectedTwoLeafLengths(w, h, false, Axis.VERTICAL, ratio, vpMin, prMin,
              240.0D, viewportFirst);
          double first = len[0];
          double second = len[1];
          Bounds firstRect = new Bounds(expectedContent.left(), expectedContent.top(),
              expectedContent.width(), first);
          Bounds dividerRect = new Bounds(expectedContent.left(), expectedContent.top() + first,
              expectedContent.width(), 4.0D);
          Bounds secondRect = new Bounds(expectedContent.left(), dividerRect.bottom(),
              expectedContent.width(), second);

          SplitBox rootBox = split(s, new Boolean[0]);
          assertEquals(Axis.VERTICAL, rootBox.axis(), at + ": root axis");
          relativeBounds(rootBox.bounds(), dividerRect.left(), dividerRect.top(),
              dividerRect.width(), dividerRect.height(), at + " divider");

          relativeBounds(box(s, viewportFirst ? "viewport" : "properties").bounds(),
              firstRect.left(), firstRect.top(), firstRect.width(), firstRect.height(),
              at + " first");
          relativeBounds(box(s, viewportFirst ? "properties" : "viewport").bounds(),
              secondRect.left(), secondRect.top(), secondRect.width(), secondRect.height(),
              at + " second");

          // snapshot.viewport 独立核对：期望只能来自输入求出的两个矩形，不从 actual 反推。
          // PanelBox 正确不能代替 viewport 字段正确。
          Bounds vpExpected = viewportFirst ? firstRect : secondRect;
          relativeBounds(s.viewport(), vpExpected.left(), vpExpected.top(), vpExpected.width(),
              vpExpected.height(), at + " viewport");
          assertFiniteRect(s.viewport(), at + " viewport");
          assertInsideContent(s.viewport(), s.content(), at + " viewport in content");
          assertTrue(s.viewport().width() >= vpMin[0] && s.viewport().height() >= vpMin[1],
              at + ": viewport below " + vpMin[0] + "x" + vpMin[1] + ": " + s.viewport());

          for (PanelBox p : s.panels()) {
            assertFiniteRect(p.bounds(), at + " panel " + p.panelId());
            assertInsideContent(p.bounds(), s.content(), at + " panel " + p.panelId());
            assertTrue(p.visible(), at + ": panel " + p.panelId() + " must be visible");
            double[] m = specMin(panel(p.panelId()));
            assertTrue(p.bounds().width() >= m[0],
                at + ": " + p.panelId() + " width " + p.bounds().width() + " < min " + m[0]);
            assertTrue(p.bounds().height() >= m[1],
                at + ": " + p.panelId() + " height " + p.bounds().height() + " < min " + m[1]);
          }
        }
      }
    }
    assertEquals(12, cases, "vertical two-leaf case count");
  }

  /**
   * R16.2c1：极端但完全合法的比例 {@code 0x1.0000000000007p-3}（= 1/8 之上 7 个 ulp，即 1/8 + 7 ULP）。
   *
   * <p>该比例本身合法（0 &lt; r &lt; 1、非 NaN、非 0），MAX_VALUE 尺度下两侧长度之和会溢出为
   * Infinity，导致 {@code LayoutSnapshot.Bounds} 的 edge finite 校验抛 IAE。本方法只如实记录该
   * RED，不篡改断言为绿。solve 应正常返回；任何异常由 assertAll 的 Executable 直接传播，不 catch。
   */
  @Test
  void extremeLegalRatioKeepsFiniteTailBounds() {
    // 唯一比例：0x1.0000000000007p-3，即 1/8 向上 7 个 ulp（1/8 + 7 ULP）。
    final double ratio = 0x1.0000000000007p-3;
    assertEquals(0.1250000000000002D, ratio, "ratio literal must stay 0x1.0000000000007p-3");
    assertTrue(ratio > 0.0D && ratio < 1.0D && Double.isFinite(ratio),
        "ratio must be a legal fraction: " + ratio);

    // 两个独立 case：首 case 抛异常不得阻止第二 case 运行 → 用 assertAll 包两个 Executable。
    assertAll("extremeLegalRatioKeepsFiniteTailBounds",
        () -> extremeLegalRatioCase(ratio, Axis.HORIZONTAL, Double.MAX_VALUE, 1080.0D, 320.0D, 220.0D,
            260.0D, 120.0D, "H"),
        () -> extremeLegalRatioCase(ratio, Axis.VERTICAL, 1920.0D, Double.MAX_VALUE, 320.0D, 220.0D,
            260.0D, 120.0D, "V"));
  }

  /**
   * 单个极端比例 case：期望全部由输入独立求出（{@code specContent} + 冻结 {@code specMin}），
   * 不从 actual 任何 bounds 反推。solve 直接正常返回；异常自然传播为 RED，不 catch、不忽略。
   */
  private static void extremeLegalRatioCase(double ratio, Axis axis, double w, double h,
      double vpMinW, double vpMinH, double prMinW, double prMinH, String tag) {
    String at = tag + " ratio=" + Double.toHexString(ratio) + " w=" + w + " h=" + h;

    DockNode vp = panel("viewport");
    DockNode pr = panel("properties");
    // 树：Split(axis, ratio, Panel(viewport), Panel(properties))，viewport 恒在 first。
    Split root = axis == Axis.HORIZONTAL ? h(ratio, vp, pr) : v(ratio, vp, pr);
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, root);
    DockNode before = layout.root();

    // solve 应正常返回：异常不在此 catch，直接作为 RED 传播。
    LayoutSnapshot s = solve(layout, w, h, false, null);

    assertEquals(Mode.DOCKED, s.mode(), at + ": mode");
    assertEquals(List.of("viewport", "properties"),
        s.panels().stream().map(PanelBox::panelId).toList(), at + ": leaf order");
    assertEquals(new DockLayout(layout.schemaVersion(), before), layout, at + ": layout mutated");
    assertTrue(s.drawer().isEmpty(), at + ": drawer must be absent");
    assertTrue(s.overflowPanelIds().isEmpty(), at + ": overflow must be empty");
    for (PanelBox p : s.panels()) {
      assertTrue(p.visible(), at + ": panel " + p.panelId() + " must be visible");
    }

    // content 由输入独立求出，不读 actual。
    Bounds expectedContent = specContent(w, h, false);
    relativeBounds(s.content(), expectedContent.left(), expectedContent.top(),
        expectedContent.width(), expectedContent.height(), at + " content");

    // 所有 panel / viewport / splitter：四字段与 right/bottom finite，且在独立 expected content 内。
    for (PanelBox p : s.panels()) {
      assertFiniteRect(p.bounds(), at + " panel " + p.panelId());
      assertInsideContent(p.bounds(), expectedContent, at + " panel " + p.panelId() + " in content");
      double[] m = specMin(panel(p.panelId()));
      assertTrue(p.bounds().width() >= m[0],
          at + ": " + p.panelId() + " width " + p.bounds().width() + " < min " + m[0]);
      assertTrue(p.bounds().height() >= m[1],
          at + ": " + p.panelId() + " height " + p.bounds().height() + " < min " + m[1]);
    }
    assertFiniteRect(s.viewport(), at + " viewport");
    assertInsideContent(s.viewport(), expectedContent, at + " viewport in content");
    assertTrue(s.viewport().width() >= 320.0D && s.viewport().height() >= 220.0D,
        at + ": viewport below 320x220: " + s.viewport());
    for (SplitBox sp : s.splitters()) {
      assertFiniteRect(sp.bounds(), at + " splitter " + sp.nodePath());
      assertInsideContent(sp.bounds(), expectedContent, at + " splitter in content");
    }

    // root splitter：空 path 且 axis 与输入一致。
    SplitBox rootBox = split(s, new Boolean[0]);
    assertEquals(axis, rootBox.axis(), at + ": root axis");
  }

  /**
   * R16.2c2：有限尾端返修验收。4 固定拓扑 × 3 尺寸 × 2 banner = 24 个嵌套 case（**不是** 24 个 JUnit 方法）。
   *
   * <p>比例固定 {@code 0x1.0000000000007p-3}（= 1/8 + 7 ULP）。期望全部由输入独立求出，不从 actual 反推。
   */
  @Test
  void extremeFiniteTailRepairPreservesMinimumsAndNestedLayouts() {
    final double r = 0x1.0000000000007p-3;
    final double max = Double.MAX_VALUE;
    final double nextDownMax = Math.nextDown(Double.MAX_VALUE);

    // 4 个固定拓扑，全部以 hierarchy 或 viewport 起手，叶序在拓扑内预先固定。
    List<DockNode> topologies = List.of(
        h(r, panel("viewport"), h(0.5D, panel("properties"), panel("details"))),
        v(r, panel("viewport"), v(0.5D, panel("properties"), panel("details"))),
        h(0.5D, panel("hierarchy"), h(r, panel("viewport"), panel("properties"))),
        v(0.5D, panel("hierarchy"), v(r, panel("viewport"), panel("properties"))));
    List<String> tags = List.of("H(r,vp,H(.5,pr,det))", "V(r,vp,V(.5,pr,det))",
        "H(.5,hier,H(r,vp,pr))", "V(.5,hier,V(r,vp,pr))");

    // 3 尺寸 × 2 banner。
    double[][] sizes = {{max, max}, {nextDownMax, max}, {max, nextDownMax}};
    boolean[] banners = {false, true};

    int cases = 0;
    for (int t = 0; t < topologies.size(); t++) {
      for (double[] size : sizes) {
        for (boolean banner : banners) {
          finiteTailCase(r, topologies.get(t), tags.get(t), size[0], size[1], banner);
          cases++;
        }
      }
    }
    assertEquals(24, cases, "finite tail case count");
  }

  /** 单个有限尾端 case：期望由输入独立求出，任何失败自然传播，不 catch。 */
  private static void finiteTailCase(double ratio, DockNode root, String tag, double w, double h,
      boolean banner) {
    String at = tag + " w=" + w + " h=" + h + " banner=" + banner;
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, root);
    DockNode before = layout.root();

    LayoutSnapshot s = solve(layout, w, h, banner, null);

    assertEquals(Mode.DOCKED, s.mode(), at + ": mode");
    // 独立 predefined 叶序，不从 actual 反推。
    assertEquals(specLeaves(before), s.panels().stream().map(PanelBox::panelId).toList(),
        at + ": leaf order");
    assertEquals(new DockLayout(layout.schemaVersion(), before), layout, at + ": layout mutated");
    assertTrue(s.drawer().isEmpty(), at + ": drawer must be absent");
    assertTrue(s.overflowPanelIds().isEmpty(), at + ": overflow must be empty (no prune)");
    for (PanelBox p : s.panels()) {
      assertTrue(p.visible(), at + ": panel " + p.panelId() + " must be visible");
      double[] m = specMin(panel(p.panelId()));
      assertTrue(p.bounds().width() >= m[0],
          at + ": " + p.panelId() + " width " + p.bounds().width() + " < min " + m[0]);
      assertTrue(p.bounds().height() >= m[1],
          at + ": " + p.panelId() + " height " + p.bounds().height() + " < min " + m[1]);
    }

    // content 由输入独立求出。
    Bounds expectedContent = specContent(w, h, banner);
    relativeBounds(s.content(), expectedContent.left(), expectedContent.top(),
        expectedContent.width(), expectedContent.height(), at + " content");

    // chrome + viewport + panel + splitter：四字段与 edge finite、非负宽高、在独立 expected content 内。
    List<Bounds> chrome = new ArrayList<>(List.of(s.menuBar(), s.documentBar(), s.toolbar(),
        s.statusBar()));
    s.banner().ifPresent(chrome::add);
    for (Bounds b : chrome) {
      assertFiniteRect(b, at + " chrome");
      assertTrue(b.width() >= 0.0D && b.height() >= 0.0D, at + ": chrome negative: " + b);
    }
    assertFiniteRect(s.content(), at + " content");
    assertTrue(s.content().width() >= 0.0D && s.content().height() >= 0.0D,
        at + ": content negative: " + s.content());
    assertTrue(banner == s.banner().isPresent(), at + ": banner presence");

    assertFiniteRect(s.viewport(), at + " viewport");
    assertTrue(s.viewport().width() >= 0.0D && s.viewport().height() >= 0.0D,
        at + ": viewport negative: " + s.viewport());
    assertInsideContent(s.viewport(), expectedContent, at + " viewport in content");
    assertTrue(s.viewport().width() >= 320.0D && s.viewport().height() >= 220.0D,
        at + ": viewport below 320x220: " + s.viewport());

    for (PanelBox p : s.panels()) {
      assertFiniteRect(p.bounds(), at + " panel " + p.panelId());
      assertTrue(p.bounds().width() >= 0.0D && p.bounds().height() >= 0.0D,
          at + ": " + p.panelId() + " negative: " + p.bounds());
      assertInsideContent(p.bounds(), expectedContent, at + " panel " + p.panelId() + " in content");
    }
    for (SplitBox sp : s.splitters()) {
      assertFiniteRect(sp.bounds(), at + " splitter " + sp.nodePath());
      assertTrue(sp.bounds().width() >= 0.0D && sp.bounds().height() >= 0.0D,
          at + ": splitter negative: " + sp.bounds());
      assertInsideContent(sp.bounds(), expectedContent, at + " splitter in content");
    }

    // splitter 数 2，原 path [] 与 [true]，axis 与输入树对应。
    assertEquals(2, s.splitters().size(), at + ": splitter count");
    Split rootSplit = (Split) root;
    SplitBox rootBox = split(s, new Boolean[0]);
    assertEquals(rootSplit.axis(), rootBox.axis(), at + ": root axis");
    SplitBox firstBox = split(s, true);
    if (rootSplit.first() instanceof Split innerSplit) {
      assertEquals(innerSplit.axis(), firstBox.axis(), at + ": first axis");
    } else {
      // first 是叶：其内部没有分裂，[true] 对应第二子树的根分裂。
      assertEquals(((Split) rootSplit.second()).axis(), firstBox.axis(), at + ": second axis");
    }
  }
}
