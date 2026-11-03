package net.ptcrys.fpsmatch.editor.common.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Axis;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Panel;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Split;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Tabs;

/**
 * R03 停靠树契约测试（CONTRACTS §8 / P03）。
 *
 * <p>只验证树结构与全树验证；不涉及像素求解、JSON wire 或 UI 宿住。节点/深度期望值由本测试按规格文本
 * 独立计算（{@link #specNodeCount}、{@link #specDepth}），不使用实现内部的计数器。
 */
class DockTreeContractTest {

  // ---------------------------------------------------------------- 合法输入

  @Test
  void singleViewportPanelIsValid() {
    DockLayout layout = new DockLayout(DockLayout.SCHEMA_VERSION, new Panel("viewport"));
    assertEquals(DockLayout.SCHEMA_VERSION, layout.schemaVersion());
    assertEquals(new Panel("viewport"), layout.root());
    DockValidation.validate(layout.root());
  }

  @Test
  void splitOfTabsAndPanelIsValidAndPreservesStructure() {
    Tabs tabs = new Tabs("left.object", List.of(new Panel("left.object"), new Panel("left.problems")));
    Split root = new Split(Axis.HORIZONTAL, 0.3D, tabs, new Panel("viewport"));

    DockLayout layout = new DockLayout(1, root);

    Split built = (Split) layout.root();
    assertEquals(Axis.HORIZONTAL, built.axis());
    assertEquals(0.3D, built.ratio());
    assertEquals(tabs, built.first());
    assertEquals(new Panel("viewport"), built.second());
    assertEquals(2, ((Tabs) built.first()).panels().size());
  }

  @Test
  void bothAxesAreAccepted() {
    for (Axis axis : Axis.values()) {
      DockLayout layout =
          new DockLayout(1, new Split(axis, 0.5D, new Panel("left"), new Panel("viewport")));
      assertEquals(axis, ((Split) layout.root()).axis());
    }
    assertEquals(List.of("HORIZONTAL", "VERTICAL"),
        Arrays.stream(Axis.values()).map(Enum::name).collect(Collectors.toList()));
  }

  @Test
  void unknownButWellFormedPanelIdsAreAllowed() {
    Tabs tabs = new Tabs("plugin.watch", List.of(new Panel("plugin.watch"), new Panel("plugin.stats")));
    DockLayout layout = new DockLayout(1, new Split(Axis.VERTICAL, 0.4D, tabs, new Panel("viewport")));
    DockValidation.validate(layout.root());

    new Panel("plugin.watch");
    new Panel("a");
    new Panel("a".repeat(64));
  }

  @Test
  void tabsCopiesInputListAndIsImmutable() {
    List<Panel> source = new ArrayList<>(List.of(new Panel("left.a"), new Panel("left.b")));
    Tabs tabs = new Tabs("left.a", source);
    source.set(0, new Panel("left.zzz"));
    source.clear();

    assertEquals(2, tabs.panels().size());
    assertEquals("left.a", tabs.panels().get(0).id());
    assertEquals("left.b", tabs.panels().get(1).id());
    assertNotSame(source, tabs.panels());
    assertThrows(UnsupportedOperationException.class, () -> tabs.panels().add(new Panel("left.c")));
    assertThrows(UnsupportedOperationException.class, () -> tabs.panels().clear());
  }

  @Test
  void tabsPreservesPanelOrder() {
    Tabs tabs = new Tabs("p.0", List.of(new Panel("p.0"), new Panel("p.1"), new Panel("p.2")));
    assertEquals(List.of("p.0", "p.1", "p.2"),
        tabs.panels().stream().map(Panel::id).collect(Collectors.toList()));
  }

  @Test
  void ratioBoundariesAreInclusiveAndNotClamped() {
    Split low = new Split(Axis.HORIZONTAL, 0.05D, new Panel("left"), new Panel("viewport"));
    Split high = new Split(Axis.VERTICAL, 0.95D, new Panel("left"), new Panel("viewport"));
    assertEquals(0.05D, low.ratio());
    assertEquals(0.95D, high.ratio());

    new DockLayout(1, new Split(Axis.HORIZONTAL, 0.05D, new Panel("p.any"), new Panel("viewport")));
    new DockLayout(1, new Split(Axis.VERTICAL, 0.95D, new Panel("p.any"), new Panel("viewport")));
  }

  @Test
  void multipleTabGroupsKeepTheirOwnActivePanel() {
    Tabs left = new Tabs("left.object", List.of(new Panel("left.object"), new Panel("left.problems")));
    Tabs right = new Tabs("right.inspector", List.of(new Panel("right.inspector"), new Panel("right.flags")));
    DockNode root = new Split(Axis.HORIZONTAL, 0.5D,
        left, new Split(Axis.VERTICAL, 0.6D, right, new Panel("viewport")));

    DockLayout layout = new DockLayout(1, root);
    DockValidation.validate(layout.root());

    assertEquals("left.object", left.activePanelId());
    assertEquals("right.inspector", right.activePanelId());
    assertEquals(List.of("left.object", "left.problems"),
        left.panels().stream().map(Panel::id).collect(Collectors.toList()));
  }

  // ---------------------------------------------------------------- ratio 反例

  @Test
  void ratioOutsideRangeIsRejectedWithoutClamping() {
    for (double ratio : new double[] {0.0499D, 0.0499999999D, 0.9500000001D, 0.9501D, 0.0D, 1.0D, -0.5D, 2.0D}) {
      assertThrows(IllegalArgumentException.class,
          () -> new Split(Axis.HORIZONTAL, ratio, new Panel("left"), new Panel("viewport")),
          "ratio=" + ratio);
    }
  }

  @Test
  void nonFiniteRatioIsRejected() {
    for (double ratio : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0D / 0.0D}) {
      assertThrows(IllegalArgumentException.class,
          () -> new Split(Axis.VERTICAL, ratio, new Panel("left"), new Panel("viewport")),
          "ratio=" + ratio);
    }
  }

  // ---------------------------------------------------------------- null / 局部反例

  @Test
  void nullComponentsAreRejectedAsIllegalArgumentNotNpe() {
    Panel viewport = new Panel("viewport");
    assertThrows(IllegalArgumentException.class, () -> new Panel(null));
    assertThrows(IllegalArgumentException.class, () -> new Tabs(null, List.of(viewport)));
    assertThrows(IllegalArgumentException.class, () -> new Tabs("viewport", null));
    assertThrows(IllegalArgumentException.class, () -> new Tabs(null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new Tabs("viewport", Arrays.asList(viewport, null)));
    assertThrows(IllegalArgumentException.class,
        () -> new Tabs("viewport", Collections.singletonList(null)));
    assertThrows(IllegalArgumentException.class,
        () -> new Split(null, 0.5D, viewport, viewport));
    assertThrows(IllegalArgumentException.class,
        () -> new Split(Axis.HORIZONTAL, 0.5D, null, viewport));
    assertThrows(IllegalArgumentException.class,
        () -> new Split(Axis.HORIZONTAL, 0.5D, viewport, null));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, null));
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(null));
  }

  @Test
  void emptyTabsIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Tabs("viewport", List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Tabs("viewport", new ArrayList<>()));
  }

  @Test
  void tabsActivePanelMustBeAMemberOfItsOwnGroup() {
    assertThrows(IllegalArgumentException.class,
        () -> new Tabs("left.other", List.of(new Panel("left.a"), new Panel("left.b"))));
    assertThrows(IllegalArgumentException.class,
        () -> new Tabs("viewport", List.of(new Panel("left.a"), new Panel("left.b"))));
    assertThrows(IllegalArgumentException.class, () -> new Tabs("left.a", List.of(new Panel("left.b"))));
  }

  @Test
  void invalidPanelIdsAreRejected() {
    String[] bad = {
      "", " ", "a ", " a", "A", "Viewport", "viewport ", " viewport", "1abc", "a".repeat(65),
      "a/b", "-a", "a#b", "a b", ".a", "_a", "a$", "Å", "viewport\u0000", "pan\u00e9l", "viewport\n"
    };
    for (String id : bad) {
      assertThrows(IllegalArgumentException.class, () -> new Panel(id), "id='" + id + "'");
      assertThrows(IllegalArgumentException.class,
          () -> new Tabs(id, List.of(new Panel("left.a"))), "active='" + id + "'");
    }
    // 契约正则 [a-z][a-z0-9_.-]{0,63} 允许以分隔符结尾，实现不得比契约更严
    new Panel("a.b.");
    new Panel("a...");
    new Panel("a-");
    new Panel("a_");
  }

  @Test
  void duplicatePanelIdInsideOneTabsGroupIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new Tabs("left.a", List.of(new Panel("left.a"), new Panel("left.a"))));
    assertThrows(IllegalArgumentException.class,
        () -> new Tabs("viewport", List.of(new Panel("viewport"), new Panel("viewport"))));
  }

  // ---------------------------------------------------------------- 全树反例

  @Test
  void duplicateLeafAcrossBranchesIsRejected() {
    DockNode root = new Split(Axis.HORIZONTAL, 0.5D, new Panel("left.a"), new Panel("left.a"));
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(root));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, root));

    // 同一 Panel 实例在两处出现即两个叶引用，必须拒绝而不是去重
    Panel shared = new Panel("left.shared");
    DockNode sharing = new Split(Axis.HORIZONTAL, 0.5D, shared,
        new Split(Axis.VERTICAL, 0.5D, shared, new Panel("viewport")));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, sharing));
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(sharing));
    assertSame(shared, ((Split) sharing).first());

    DockNode acrossTabs = new Split(Axis.HORIZONTAL, 0.5D,
        new Tabs("left.a", List.of(new Panel("left.a"))), new Panel("left.a"));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, acrossTabs));
  }

  @Test
  void missingViewportIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(new Panel("left.a")));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, new Panel("left.a")));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1,
        new Split(Axis.HORIZONTAL, 0.5D,
            new Tabs("left.a", List.of(new Panel("left.a"), new Panel("left.b"))),
            new Panel("right.c"))));
  }

  @Test
  void twoViewportsAreRejected() {
    DockNode root = new Split(Axis.HORIZONTAL, 0.5D, new Panel("viewport"), new Panel("viewport"));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, root));
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(root));

    DockNode nested = new Split(Axis.VERTICAL, 0.5D,
        new Tabs("viewport", List.of(new Panel("viewport"))), new Panel("viewport"));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, nested));
  }

  // ---------------------------------------------------------------- 深度预算

  @Test
  void depthSixteenIsAcceptedAndSeventeenIsRejected() {
    DockNode atLimit = chainOfSplits(16);
    assertEquals(16, specDepth(atLimit));
    assertTrue(specNodeCount(atLimit) <= DockValidation.MAX_NODES);
    DockValidation.validate(atLimit);
    new DockLayout(1, atLimit);

    DockNode overLimit = chainOfSplits(17);
    assertEquals(17, specDepth(overLimit));
    assertTrue(specNodeCount(overLimit) <= DockValidation.MAX_NODES);
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(overLimit));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, overLimit));
  }

  @Test
  void tabsCountsAsANodeAndItsPanelsCountAsChildrenOneDeeper() {
    DockNode accepted = chainEndingWith(14, new Tabs("deep.a",
        List.of(new Panel("deep.a"), new Panel("viewport"), new Panel("deep.b"))));
    assertEquals(16, specDepth(accepted));
    assertEquals(32, specNodeCount(accepted));
    DockValidation.validate(accepted);

    DockNode rejected = chainEndingWith(15, new Tabs("viewport", List.of(new Panel("viewport"))));
    assertEquals(17, specDepth(rejected));
    assertEquals(32, specNodeCount(rejected));
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(rejected));
  }

  // ---------------------------------------------------------------- 节点预算

  @Test
  void sixtyFourNodesAreAcceptedAndSixtyFiveAreRejected() {
    DockNode atLimit = twoTabGroupTree(32, 27);
    assertEquals(64, specNodeCount(atLimit));
    assertEquals(DockValidation.MAX_NODES, specNodeCount(atLimit));
    assertEquals(4, specDepth(atLimit));
    DockValidation.validate(atLimit);
    DockLayout layout = new DockLayout(1, atLimit);
    assertSame(atLimit, layout.root());

    DockNode overLimit = twoTabGroupTree(32, 28);
    assertEquals(65, specNodeCount(overLimit));
    assertTrue(specDepth(overLimit) <= DockValidation.MAX_DEPTH);
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(overLimit));
    assertThrows(IllegalArgumentException.class, () -> new DockLayout(1, overLimit));
  }

  @Test
  void wideTabsTreeAtMaxNodesIsAccepted() {
    DockNode root = new Split(Axis.HORIZONTAL, 0.5D,
        new Tabs("wide.0", panels("wide.", 61)), new Panel("viewport"));
    assertEquals(64, specNodeCount(root));
    assertEquals(3, specDepth(root));
    DockValidation.validate(root);
    new DockLayout(1, root);

    DockNode tooWide = new Split(Axis.HORIZONTAL, 0.5D,
        new Tabs("wide.0", panels("wide.", 62)), new Panel("viewport"));
    assertEquals(65, specNodeCount(tooWide));
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(tooWide));
  }

  // ---------------------------------------------------------------- 单组预算（MAX_NODES - 1）

  @Test
  void tabsGroupOf63PanelsWithViewportIsAccepted() {
    List<Panel> wide = panels("g.", 62);
    wide.add(new Panel("viewport"));
    assertEquals(63, wide.size());

    Tabs tabs = new Tabs("g.0", wide);
    assertEquals(63, tabs.panels().size());
    assertEquals(64, specNodeCount(tabs)); // Tabs 自身 1 + 63 个 Panel
    assertEquals(2, specDepth(tabs));
    DockValidation.validate(tabs);
    new DockLayout(1, tabs);
  }

  @Test
  void tabsGroupOf64PanelsIsRejectedBeforeAnyTraversal() {
    List<Panel> tooWide = panels("g.", 63);
    tooWide.add(new Panel("viewport"));
    assertEquals(64, tooWide.size());

    // 早拒绝：不依赖全树验证，Tabs 构造期即抛出
    assertThrows(IllegalArgumentException.class, () -> new Tabs("g.0", tooWide));
    assertThrows(IllegalArgumentException.class, () -> new Tabs("viewport", tooWide));
    assertThrows(IllegalArgumentException.class,
        () -> new Tabs("g.0", panels("g.", DockValidation.MAX_NODES)));
  }

  @Test
  void lazilySizedOversizedListIsRejectedFromSizeOnlyWithoutTouchingElements() {
    // size 报告 Integer.MAX_VALUE，任何 get 调用都会以 AssertionError 暴露"被遍历/被分配"
    List<Panel> lazy = new AbstractList<>() {
      @Override
      public int size() {
        return Integer.MAX_VALUE;
      }

      @Override
      public Panel get(int index) {
        throw new AssertionError("元素被读取了：index=" + index);
      }
    };

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> new Tabs("viewport", lazy));
    assertTrue(failure.getMessage().contains(String.valueOf(Integer.MAX_VALUE)), failure.getMessage());
  }

  // ---------------------------------------------------------------- schema 与 API

  @Test
  void schemaVersionMustBeOne() {
    for (int version : new int[] {0, -1, 2, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> new DockLayout(version, new Panel("viewport")));
    }
    assertEquals(1, DockLayout.SCHEMA_VERSION);
    new DockLayout(1, new Panel("viewport"));
  }

  @Test
  void layoutConstructorValidatesTheWholeTree() {
    assertThrows(IllegalArgumentException.class,
        () -> new DockLayout(1, chainOfSplits(17)));
    assertThrows(IllegalArgumentException.class,
        () -> new DockLayout(1, twoTabGroupTree(32, 28)));
    assertThrows(IllegalArgumentException.class,
        () -> new DockLayout(1, new Split(Axis.HORIZONTAL, 0.5D, new Panel("x"), new Panel("x"))));
  }

  @Test
  void validateIsPureAndStateless() {
    DockNode root = new Split(Axis.HORIZONTAL, 0.5D,
        new Tabs("left.a", List.of(new Panel("left.a"))), new Panel("viewport"));
    DockValidation.validate(root);
    DockValidation.validate(root);
    assertEquals(4, specNodeCount(root));
    // 以子树调用时，viewport 规则仍作用于传入的树本身
    assertThrows(IllegalArgumentException.class, () -> DockValidation.validate(new Panel("left.a")));
  }

  @Test
  void publicApiSurfaceIsFixed() {
    assertEquals(16, DockValidation.MAX_DEPTH);
    assertEquals(64, DockValidation.MAX_NODES);

    TreeSet<String> permitted = Arrays.stream(DockNode.class.getPermittedSubclasses())
        .map(Class::getSimpleName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(new TreeSet<>(List.of("Panel", "Split", "Tabs")), permitted);

    List<String> components = Arrays.stream(DockLayout.class.getRecordComponents())
        .map(c -> c.getName() + ":" + c.getType().getSimpleName())
        .collect(Collectors.toList());
    assertEquals(List.of("schemaVersion:int", "root:DockNode"), components);

    TreeSet<String> validationPublicMethods = Arrays.stream(DockValidation.class.getDeclaredMethods())
        .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
        .map(java.lang.reflect.Method::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(new TreeSet<>(List.of("validate")), validationPublicMethods);

    TreeSet<String> validationPublicFields = Arrays.stream(DockValidation.class.getFields())
        .map(java.lang.reflect.Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(new TreeSet<>(List.of("MAX_DEPTH", "MAX_NODES")), validationPublicFields);

    var constructors = DockValidation.class.getDeclaredConstructors();
    assertEquals(1, constructors.length);
    assertTrue(java.lang.reflect.Modifier.isPrivate(constructors[0].getModifiers()));
    assertEquals(0, DockValidation.class.getConstructors().length);

    TreeSet<String> layoutPublicStaticMethods = Arrays.stream(DockLayout.class.getDeclaredMethods())
        .filter(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers()))
        .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
        .map(java.lang.reflect.Method::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertTrue(layoutPublicStaticMethods.isEmpty(), "no default-layout factory expected");

    TreeSet<String> nodePublicStaticMethods = Arrays.stream(DockNode.class.getDeclaredMethods())
        .filter(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers()))
        .map(java.lang.reflect.Method::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertTrue(nodePublicStaticMethods.isEmpty(), "DockNode keeps no static helpers");

    assertFalse(DockNode.class.isAssignableFrom(DockLayout.class));
    assertInstanceOf(DockNode.class, new Panel("viewport"));
  }

  // ---------------------------------------------------------------- 独立期望

  /** 按规格文本独立统计节点数：Split 计 1，Tabs 计 1 且每个 Panel child 各计 1，叶 Panel 计 1。 */
  private static int specNodeCount(DockNode root) {
    if (root instanceof Tabs tabs) {
      return 1 + tabs.panels().size();
    }
    if (root instanceof Split split) {
      return 1 + specNodeCount(split.first()) + specNodeCount(split.second());
    }
    return 1;
  }

  /** 按规格文本独立统计深度：root 为 1，Tabs 的 Panel child 深度 +1。 */
  private static int specDepth(DockNode root) {
    if (root instanceof Tabs tabs) {
      return 1 + specDepth(tabs.panels().get(0));
    }
    if (root instanceof Split split) {
      return 1 + Math.max(specDepth(split.first()), specDepth(split.second()));
    }
    return 1;
  }

  // ---------------------------------------------------------------- 构造工具

  /** 深度为 {@code depth} 的 Split 链：每层 first 继续，second 为填充面板，最深叶为 viewport。 */
  private static DockNode chainOfSplits(int depth) {
    return chainEndingWith(depth - 1, new Panel("viewport"));
  }

  /** 以 {@code terminal} 作为最深子节点，其上方套 {@code splitLevels} 层 Split（terminal 深度为 splitLevels+1）。 */
  private static DockNode chainEndingWith(int splitLevels, DockNode terminal) {
    DockNode node = terminal;
    for (int level = splitLevels; level >= 1; level--) {
      node = new Split(Axis.HORIZONTAL, 0.5D, node, new Panel("filler." + level));
    }
    return node;
  }

  private static List<Panel> panels(String prefix, int count) {
    List<Panel> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      result.add(new Panel(prefix + i));
    }
    return result;
  }

  /** 1 个根 Split + 两个各自带 Tabs 的分支 + 一个 viewport 叶。 */
  private static DockNode twoTabGroupTree(int firstGroupSize, int secondGroupSize) {
    List<Panel> leftPanels = panels("left.p", firstGroupSize);
    List<Panel> rightPanels = panels("right.p", secondGroupSize);
    DockNode leftTabs = new Tabs(leftPanels.get(0).id(), leftPanels);
    DockNode rightTabs = new Tabs(rightPanels.get(0).id(), rightPanels);
    return new Split(Axis.HORIZONTAL, 0.5D, leftTabs,
        new Split(Axis.VERTICAL, 0.5D, rightTabs, new Panel("viewport")));
  }
}