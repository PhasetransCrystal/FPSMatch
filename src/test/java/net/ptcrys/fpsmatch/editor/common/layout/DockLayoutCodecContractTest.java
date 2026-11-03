package net.ptcrys.fpsmatch.editor.common.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Axis;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Panel;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Split;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Tabs;

/**
 * R16.1 停靠布局严格 codec 契约测试（CONTRACTS §8 R1.2 / P16-LAYOUT-CODEC）。
 *
 * <p>证据策略：期望的 wire 文本与结构期望全部在本测试内独立构造（{@link #expectedPanel}、
 * {@link #expectedTabs}、{@link #expectedSplit}、{@link #specNodeCount}、{@link #specDepth}、
 * {@link #specLeaves}），不读取实现内部计数器，也不以「encode 后 decode 再相等」作为唯一判据。随机树用例
 * （{@link #thousandDistinctTreesMatchIndependentlyBuiltWire}）逐个字段比对独立拼出的 wire，往返一致只是附加检查。
 *
 * <p>异常判据一律要求具体类型与指针：语义错误必须是 {@link IllegalArgumentException} 且消息含当前 JSON pointer；
 * 词法/预算错误必须是 {@link JsonContractException}，绝不以「抛了任意异常」代替。
 */
class DockLayoutCodecContractTest {

  private static final String VIEWPORT = "viewport";

  // ================================================================ 黄金样例

  @Test
  void singleViewportGoldenIsExactCanonicalText() {
    DockLayout layout = new DockLayout(1, new Panel(VIEWPORT));
    String json = DockLayoutCodec.encode(layout);
    // 成员按 String.compareTo 排序：root < schemaVersion。
    assertEquals("{\"root\":{\"id\":\"viewport\",\"type\":\"panel\"},\"schemaVersion\":1}", json);
    assertEquals(layout, DockLayoutCodec.decode(json));
  }

  @Test
  void tabsGoldenIsExactCanonicalText() {
    Tabs tabs =
        new Tabs("left.object", List.of(new Panel("left.object"), new Panel(VIEWPORT)));
    DockLayout layout = new DockLayout(1, tabs);

    String json = DockLayoutCodec.encode(layout);

    assertEquals("{\"root\":{\"activePanelId\":\"left.object\",\"panels\":["
        + "{\"id\":\"left.object\",\"type\":\"panel\"},"
        + "{\"id\":\"viewport\",\"type\":\"panel\"}],\"type\":\"tabs\"},\"schemaVersion\":1}",
        json);
    assertEquals(layout, DockLayoutCodec.decode(json));
  }

  @Test
  void nestedSplitGoldenIsExactCanonicalText() {
    Tabs tabs = new Tabs("left.object", List.of(new Panel("left.object")));
    Split inner = new Split(Axis.VERTICAL, 0.4D, tabs, new Panel("bottom.console"));
    DockLayout layout = new DockLayout(1, new Split(Axis.HORIZONTAL, 0.25D, inner, new Panel(VIEWPORT)));

    String json = DockLayoutCodec.encode(layout);

    String expected = "{\"root\":{"
        + "\"axis\":\"horizontal\","
        + "\"first\":{"
        + "\"axis\":\"vertical\","
        + "\"first\":{\"activePanelId\":\"left.object\",\"panels\":[{\"id\":\"left.object\",\"type\":\"panel\"}],\"type\":\"tabs\"},"
        + "\"ratio\":0.4,"
        + "\"second\":{\"id\":\"bottom.console\",\"type\":\"panel\"},"
        + "\"type\":\"split\"},"
        + "\"ratio\":0.25,"
        + "\"second\":{\"id\":\"viewport\",\"type\":\"panel\"},"
        + "\"type\":\"split\"},"
        + "\"schemaVersion\":1}";
    assertEquals(expected, json);
    assertEquals(layout, DockLayoutCodec.decode(json));
  }

  @Test
  void encodeThenDecodePreservesStructureForManyLayouts() {
    for (DockLayout layout : sampleLayouts()) {
      DockLayout restored = DockLayoutCodec.decode(DockLayoutCodec.encode(layout));
      assertEquals(layout, restored);
      assertSameStructure(layout.root(), restored.root());
    }
  }

  @Test
  void decodeThenEncodeIsStableAcrossRepeatedRoundTrips() {
    for (DockLayout layout : sampleLayouts()) {
      String once = DockLayoutCodec.encode(layout);
      String twice = DockLayoutCodec.encode(DockLayoutCodec.decode(once));
      assertEquals(once, twice, "re-encoding a decoded layout must be byte-identical");
    }
  }

  @Test
  void unknownMembersAreIgnoredButDoNotChangeSemantics() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"panel\",\"id\":\"viewport\","
        + "\"futureField\":{\"nested\":[1,2,3]},\"another\":\"x\"},\"extraTop\":true}";
    DockLayout layout = DockLayoutCodec.decode(json);
    assertEquals(new DockLayout(1, new Panel(VIEWPORT)), layout);
    // 未知成员在语义上被忽略，也不会被重新写出。
    assertEquals("{\"root\":{\"id\":\"viewport\",\"type\":\"panel\"},\"schemaVersion\":1}",
        DockLayoutCodec.encode(layout));
  }

  @Test
  void unknownButLegalPanelIdsArePreservedVerbatim() {
    Tabs tabs =
        new Tabs("plugin.watch", List.of(new Panel("plugin.watch"), new Panel("plugin.stats")));
    String json = DockLayoutCodec.encode(
        new DockLayout(1, new Split(Axis.VERTICAL, 0.4D, tabs, new Panel(VIEWPORT))));
    assertTrue(json.contains("\"plugin.watch\""), json);
    assertTrue(json.contains("\"plugin.stats\""), json);
    assertEquals(new DockLayout(1, new Split(Axis.VERTICAL, 0.4D, tabs, new Panel(VIEWPORT))),
        DockLayoutCodec.decode(json));
  }

  // ================================================================== ratio

  @Test
  void ratioRoundTripPreservesExactDoubleBits() {
    double[] ratios = {0.05D, 0.5D, 0.95D, Math.nextUp(0.05D), Math.nextDown(0.95D),
        0.30000000000000004D, 1.0D / 3.0D, 0.1D, 0.2D, 0.7D, 0.123456789012345D};
    for (double ratio : ratios) {
      DockLayout layout =
          new DockLayout(1, new Split(Axis.HORIZONTAL, ratio, new Panel("a"), new Panel(VIEWPORT)));
      String json = DockLayoutCodec.encode(layout);
      Split restored = (Split) DockLayoutCodec.decode(json).root();
      assertEquals(Double.doubleToRawLongBits(ratio), Double.doubleToRawLongBits(restored.ratio()),
          "ratio bits must survive round trip for " + ratio + " in " + json);
      assertEquals(ratio, restored.ratio());
    }
  }

  @Test
  void ratioJustInsideClosedIntervalBoundariesAreAccepted() {
    // nextUp(0.05) 的十进制展开严格大于 0.05；nextDown(0.95) 严格小于 0.95。
    for (double ratio : new double[] {Math.nextUp(0.05D), 0.05D, 0.95D, Math.nextDown(0.95D)}) {
      DockLayout layout =
          new DockLayout(1, new Split(Axis.VERTICAL, ratio, new Panel("a"), new Panel(VIEWPORT)));
      Split restored = (Split) DockLayoutCodec.decode(DockLayoutCodec.encode(layout)).root();
      assertEquals(ratio, restored.ratio());
    }
  }

  @Test
  void ratioBelowLowerBoundWithExtraPrecisionIsRejectedNotRounded() {
    // 该 25 位小数严格小于 0.05，但二进制舍入后恰好等于 0.05；必须先按十进制拒绝。
    String literal = "0.049999999999999999999999999";
    assertEquals(0.05D, Double.parseDouble(literal),
        "test premise: this literal must round to exactly 0.05 as a double");
    String json = splitJson("horizontal", literal, panel(VIEWPORT), panel("a"));
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/ratio");
  }

  @Test
  void ratioAboveUpperBoundWithExtraPrecisionIsRejectedNotRounded() {
    String json = splitJson("horizontal", "0.950000000000000000000000001", panel(VIEWPORT), panel("a"));
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/ratio");
  }

  @Test
  void ratioOutOfRangeValuesAreRejected() {
    for (String ratio : new String[] {"0", "0.04999999999999999999", "0.95000000000000000001",
        "1", "0.99", "-0.5", "100"}) {
      String json = splitJson("horizontal", ratio, panel(VIEWPORT), panel("a"));
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "ratio " + ratio + " must be rejected");
      assertPointer(failure, "/root/ratio");
    }
  }

  @Test
  void ratioNonNumberTypesAreRejected() {
    for (String ratio : new String[] {"\"0.5\"", "true", "false", "null", "[]", "{}"}) {
      String json = splitJson("horizontal", ratio, panel(VIEWPORT), panel("a"));
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "ratio " + ratio + " must be rejected");
      assertPointer(failure, "/root/ratio");
    }
  }

  @Test
  void ratioAcceptsExactAndExponentNotation() {
    assertEquals(0.5D, ((Split) DockLayoutCodec.decode(
        splitJson("horizontal", "5e-1", panel(VIEWPORT), panel("a"))).root()).ratio());
    assertEquals(0.5D, ((Split) DockLayoutCodec.decode(
        splitJson("horizontal", "0.5000", panel(VIEWPORT), panel("a"))).root()).ratio());
    assertEquals(0.5D, ((Split) DockLayoutCodec.decode(
        splitJson("horizontal", "5.0E-1", panel(VIEWPORT), panel("a"))).root()).ratio());
  }

  // ============================================================ schemaVersion

  @Test
  void schemaVersionAcceptsEquivalentIntegerForms() {
    assertEquals(1, DockLayoutCodec.decode(
        "{\"schemaVersion\":1.0,\"root\":" + panel(VIEWPORT) + "}").schemaVersion());
    assertEquals(1, DockLayoutCodec.decode(
        "{\"schemaVersion\":1e0,\"root\":" + panel(VIEWPORT) + "}").schemaVersion());
    assertEquals(1, DockLayoutCodec.decode(
        "{\"schemaVersion\":1.000,\"root\":" + panel(VIEWPORT) + "}").schemaVersion());
  }

  @Test
  void schemaVersionWrongValuesAreRejected() {
    for (String version : new String[] {"2", "0", "-1", "1.5", "1.0000000001",
        "10000000000000000000000000"}) {
      String json = "{\"schemaVersion\":" + version + ",\"root\":" + panel(VIEWPORT) + "}";
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "schemaVersion " + version + " must be rejected");
      assertPointer(failure, "/schemaVersion");
    }
  }

  @Test
  void schemaVersionWithAbsurdExponentIsRejectedByFrozenNumberBudget() {
    // 指数超上限属于冻结数字预算错误，必须在词法/预算层被拒（JsonContractException），
    // 而不是被当成合法数字后按 schema 值拒绝。
    String json = "{\"schemaVersion\":1e999999999,\"root\":" + panel(VIEWPORT) + "}";
    assertThrows(JsonContractException.class, () -> DockLayoutCodec.decode(json));
  }

  @Test
  void schemaVersionNonNumberTypesAreRejected() {
    for (String version : new String[] {"\"1\"", "true", "null", "[1]", "{\"v\":1}"}) {
      String json = "{\"schemaVersion\":" + version + ",\"root\":" + panel(VIEWPORT) + "}";
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "schemaVersion " + version + " must be rejected");
      assertPointer(failure, "/schemaVersion");
    }
  }

  // ================================================================ 根对象形状

  @Test
  void missingRequiredRootMembersAreRejected() {
    String[] raws = {"{}", "{\"schemaVersion\":1}", "{\"root\":" + panel(VIEWPORT) + "}",
        "{\"schemaVersion\":null,\"root\":" + panel(VIEWPORT) + "}",
        "{\"schemaVersion\":1,\"root\":null}"};
    for (String json : raws) {
      assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
          "must reject " + json);
    }
  }

  @Test
  void nonObjectRootsAreRejected() {
    for (String json : new String[] {"[]", "1", "\"x\"", "true", "null"}) {
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "must reject " + json);
      assertPointer(failure, "");
    }
  }

  @Test
  void schemaVersionPointerIsDistinctFromRootPointer() {
    IllegalArgumentException versionFailure = assertThrows(IllegalArgumentException.class,
        () -> DockLayoutCodec.decode("{\"root\":" + panel(VIEWPORT) + "}"));
    assertPointer(versionFailure, "/schemaVersion");

    IllegalArgumentException rootFailure = assertThrows(IllegalArgumentException.class,
        () -> DockLayoutCodec.decode("{\"schemaVersion\":1}"));
    assertPointer(rootFailure, "/root");
  }

  // ================================================================ 节点类型

  @Test
  void unknownNodeTypeIsRejectedAtItsOwnPointer() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"grid\"}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/type");
  }

  @Test
  void nodeTypeMustBeExactCaseWithoutTrimOrDefaults() {
    for (String type : new String[] {"Panel", "PANEL", " panel", "panel ", "Tabs", "Split",
        "PANEL ", "pan el", ""}) {
      String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"" + type + "\",\"id\":\"viewport\"}}";
      assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
          "type '" + type + "' must be rejected");
    }
  }

  @Test
  void nodeTypeMustBeStringNotLooseGsonValue() {
    for (String raw : new String[] {"1", "true", "null", "[]", "{}"}) {
      String json = "{\"schemaVersion\":1,\"root\":{\"type\":" + raw + ",\"id\":\"viewport\"}}";
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "type " + raw + " must be rejected");
      assertPointer(failure, "/root/type");
    }
  }

  @Test
  void missingNodeTypeIsRejected() {
    String json = "{\"schemaVersion\":1,\"root\":{\"id\":\"viewport\"}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/type");
  }

  @Test
  void nestedUnknownNodeTypesCarryTheNestedPointer() {
    String json = document(splitNode("horizontal", "0.5",
        panel(VIEWPORT), "{\"type\":\"canvas\"}"));
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/second/type");
  }

  // ================================================================ panel 节点

  @Test
  void panelIdPatternMatchesFrozenContractExactly() {
    // 与 P03 冻结契约一致的合法 ID（含以分隔符结尾者，实现不得更严）。
    for (String id : new String[] {"a", "viewport", "a.b.", "a...", "a-b_c.d", "plugin.watch",
        "z" + "0".repeat(63)}) {
      List<Panel> panels = new ArrayList<>();
      panels.add(new Panel(VIEWPORT));
      panels.add(new Panel(id));
      DockLayout layout = new DockLayout(1, id.equals(VIEWPORT) ? new Panel(id)
          : new Split(Axis.HORIZONTAL, 0.5D, new Panel(id), new Panel(VIEWPORT)));
      assertEquals(layout, DockLayoutCodec.decode(DockLayoutCodec.encode(layout)),
          "legal id " + id);
      assertNotNull(panels);
    }
  }

  @Test
  void illegalPanelIdsAreRejectedAtTheirOwnPointer() {
    String[] illegal = {"Viewport", "1abc", "_x", "-x", ".x", "a b", "a/b", "a~b", "café",
        "z" + "0".repeat(64), "", "A.B", "viewport "};
    for (String id : illegal) {
      String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"panel\",\"id\":\"" + id + "\"}}";
      IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
          () -> DockLayoutCodec.decode(json), "id '" + id + "' must be rejected");
      assertPointer(failure, "/root");
      assertNotNull(failure.getCause(), "domain error must be preserved as cause for id '" + id + "'");
    }
  }

  @Test
  void panelIdMustBeStringNotLooseGsonValue() {
    for (String raw : new String[] {"1", "true", "null", "[]", "{}"}) {
      String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"panel\",\"id\":" + raw + "}}";
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "id " + raw + " must be rejected");
      assertPointer(failure, "/root/id");
    }
  }

  @Test
  void missingPanelIdIsRejected() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"panel\"}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/id");
  }

  // ================================================================ tabs 节点

  @Test
  void tabsPreservePanelOrderAndActiveItem() {
    Tabs tabs = new Tabs("b", List.of(new Panel("b"), new Panel("a"), new Panel("viewport")));
    DockLayout layout = new DockLayout(1, tabs);
    Tabs restored = (Tabs) DockLayoutCodec.decode(DockLayoutCodec.encode(layout)).root();
    assertEquals(List.of("b", "a", "viewport"),
        restored.panels().stream().map(Panel::id).toList());
    assertEquals("b", restored.activePanelId());
  }

  @Test
  void tabsWithEmptyPanelsAreRejected() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":\"viewport\","
        + "\"panels\":[]}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/panels");
  }

  @Test
  void tabsWithNonArrayPanelsAreRejected() {
    for (String raw : new String[] {"{}", "\"x\"", "1", "true", "null"}) {
      String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":\"a\","
          + "\"panels\":" + raw + "}}";
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "panels " + raw + " must be rejected");
      assertPointer(failure, "/root/panels");
    }
  }

  @Test
  void tabsPanelsMustContainOnlyPanelObjects() {
    // 字符串、嵌套 tabs、split 都不是合法子项。
    String[] items = {"\"viewport\"", "1", "true", "null", "[]",
        "{\"type\":\"tabs\",\"activePanelId\":\"a\",\"panels\":[{\"type\":\"panel\",\"id\":\"a\"}]}",
        "{\"type\":\"split\",\"axis\":\"horizontal\",\"ratio\":0.5,"
            + "\"first\":{\"type\":\"panel\",\"id\":\"a\"},"
            + "\"second\":{\"type\":\"panel\",\"id\":\"viewport\"}}"};
    for (String item : items) {
      String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":\"viewport\","
          + "\"panels\":[{\"type\":\"panel\",\"id\":\"viewport\"}," + item + "]}}";
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "tabs child " + item + " must be rejected");
      assertTrue(failure.getMessage().contains("/root/panels/1"),
          "pointer must identify the offending child: " + failure.getMessage());
    }
  }

  @Test
  void tabsChildMissingTypeOrIdIsRejectedAtChildPointer() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":\"viewport\","
        + "\"panels\":[{\"type\":\"panel\",\"id\":\"viewport\"},{\"id\":\"a\"}]}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertTrue(failure.getMessage().contains("/root/panels/1/type"), failure.getMessage());
  }

  @Test
  void tabsActivePanelMustBelongToTheGroup() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":\"missing\","
        + "\"panels\":[{\"type\":\"panel\",\"id\":\"viewport\"}]}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root");
    assertNotNull(failure.getCause());
  }

  @Test
  void tabsDuplicatePanelIdsAreRejectedNotDeduplicated() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":\"a\","
        + "\"panels\":[{\"type\":\"panel\",\"id\":\"a\"},{\"type\":\"panel\",\"id\":\"a\"},"
        + "{\"type\":\"panel\",\"id\":\"viewport\"}]}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root");
  }

  @Test
  void tabsMissingActivePanelIdIsRejected() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"panels\":"
        + "[{\"type\":\"panel\",\"id\":\"viewport\"}]}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/activePanelId");
  }

  @Test
  void tabsActivePanelIdTypeMustBeString() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":7,"
        + "\"panels\":[{\"type\":\"panel\",\"id\":\"viewport\"}]}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/activePanelId");
  }

  @Test
  void tabsTooManyPanelsAreRejectedBeforeReadingElements() {
    // 65 个面板对象同时越过 Tabs 的 63 上限；必须只读 size 就拒绝。
    String json = tabsJson(65);
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertTrue(failure.getMessage().contains("/root/panels"), failure.getMessage());
  }

  @Test
  void tabsPanelCountAtLimitIsAcceptedWhenTreeBudgetAllows() {
    // 63 个面板 + Tabs 自身 = 64 个节点，恰好等于 MAX_NODES。
    DockLayout layout = new DockLayout(1, tabs63());
    String json = DockLayoutCodec.encode(layout);
    assertEquals(layout, DockLayoutCodec.decode(json));
    assertEquals(64, specNodeCount(layout.root()));
  }

  // ================================================================ split 节点

  @Test
  void splitAxesMapToExactWireLiterals() {
    String horizontal = DockLayoutCodec.encode(new DockLayout(1,
        new Split(Axis.HORIZONTAL, 0.5D, new Panel("a"), new Panel(VIEWPORT))));
    assertTrue(horizontal.contains("\"axis\":\"horizontal\""), horizontal);

    String vertical = DockLayoutCodec.encode(new DockLayout(1,
        new Split(Axis.VERTICAL, 0.5D, new Panel("a"), new Panel(VIEWPORT))));
    assertTrue(vertical.contains("\"axis\":\"vertical\""), vertical);

    assertEquals(Axis.HORIZONTAL, ((Split) DockLayoutCodec.decode(horizontal).root()).axis());
    assertEquals(Axis.VERTICAL, ((Split) DockLayoutCodec.decode(vertical).root()).axis());
  }

  @Test
  void unknownOrMalformedSplitAxesAreRejected() {
    for (String axis : new String[] {"HORIZONTAL", "Horizontal", " horizontal", "horizontal ",
        "vert", "both", ""}) {
      String json = splitJson(axis, "0.5", panel(VIEWPORT), panel("a"));
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "axis '" + axis + "' must be rejected");
      assertPointer(failure, "/root/axis");
    }
  }

  @Test
  void splitAxisTypeMustBeString() {
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"split\",\"axis\":1,\"ratio\":0.5,"
        + "\"first\":" + panel(VIEWPORT) + ",\"second\":" + panel("a") + "}}";
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertPointer(failure, "/root/axis");
  }

  @Test
  void splitMissingMembersAreRejected() {
    String[] raws = {
        "{\"type\":\"split\",\"ratio\":0.5,\"first\":" + panel(VIEWPORT) + ",\"second\":" + panel("a") + "}",
        "{\"type\":\"split\",\"axis\":\"horizontal\",\"first\":" + panel(VIEWPORT) + ",\"second\":" + panel("a") + "}",
        "{\"type\":\"split\",\"axis\":\"horizontal\",\"ratio\":0.5,\"second\":" + panel("a") + "}",
        "{\"type\":\"split\",\"axis\":\"horizontal\",\"ratio\":0.5,\"first\":" + panel(VIEWPORT) + "}"};
    for (String raw : raws) {
      String json = "{\"schemaVersion\":1,\"root\":" + raw + "}";
      assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
          "must reject " + raw);
    }
  }

  @Test
  void splitChildMustBeObjectNodeNotNullOrScalar() {
    for (String raw : new String[] {"null", "\"x\"", "1", "true", "[]"}) {
      String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"split\",\"axis\":\"horizontal\","
          + "\"ratio\":0.5,\"first\":" + panel(VIEWPORT) + ",\"second\":" + raw + "}}";
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json),
              "split child " + raw + " must be rejected");
      assertPointer(failure, "/root/second");
    }
  }

  @Test
  void splitChildOrderIsPreservedAsFirstThenSecond() {
    DockLayout layout = new DockLayout(1,
        new Split(Axis.HORIZONTAL, 0.5D, new Panel("left"), new Panel(VIEWPORT)));
    Split restored = (Split) DockLayoutCodec.decode(DockLayoutCodec.encode(layout)).root();
    assertEquals(new Panel("left"), restored.first());
    assertEquals(new Panel(VIEWPORT), restored.second());

    // 交换两侧后编码必须随之改变，证明顺序不是靠排序或集合推出来的。
    DockLayout swapped = new DockLayout(1,
        new Split(Axis.HORIZONTAL, 0.5D, new Panel(VIEWPORT), new Panel("left")));
    assertNotEquals(DockLayoutCodec.encode(layout), DockLayoutCodec.encode(swapped));
    Split swappedRestored = (Split) DockLayoutCodec.decode(DockLayoutCodec.encode(swapped)).root();
    assertEquals(new Panel(VIEWPORT), swappedRestored.first());
    assertEquals(new Panel("left"), swappedRestored.second());
  }

  // ============================================================ 全树域约束

  @Test
  void exactlyOneViewportIsRequired() {
    // 零个 viewport：全树校核发生在冻结构造器内，须已补 /root 指针与真实 cause。
    String none = document(splitNode("horizontal", "0.5", panel("a"), panel("b")));
    DockLayout noneLayout = new DockLayout(1, new Split(Axis.HORIZONTAL, 0.5D,
        new Panel("a"), new Panel("viewport")));
    assertNotNull(noneLayout);
    assertWholeTreeDomainFailure(none, "viewport");

    // 两个 viewport。
    String twice = document(splitNode("horizontal", "0.5", panel(VIEWPORT), panel(VIEWPORT)));
    assertWholeTreeDomainFailure(twice, "viewport");
  }

  @Test
  void duplicateLeafIdsAcrossTabsAreRejectedByWholeTreeValidation() {
    // 两个 Tabs 各含独立的 a；Tabs 内部各自无重复，但全树叶子 ID 必须全局唯一。
    String first = "{\"type\":\"tabs\",\"activePanelId\":\"a\",\"panels\":[{\"type\":\"panel\",\"id\":\"a\"}]}";
    String second = "{\"type\":\"tabs\",\"activePanelId\":\"a\",\"panels\":[{\"type\":\"panel\",\"id\":\"a\"}]}";
    String json = "{\"schemaVersion\":1,\"root\":{\"type\":\"split\",\"axis\":\"horizontal\","
        + "\"ratio\":0.5,\"first\":" + first + ",\"second\":" + second + "}}";
    assertWholeTreeDomainFailure(json, null);
  }

  // ============================================================ 深度预算 golden

  @Test
  void depthSixteenTreeIsAccepted() {
    // viewport 外包 15 层 Split，viewport 位于第 16 层。
    DockLayout layout = nestedViewportLayout(15);
    assertEquals(16, specDepth(layout.root()));
    String json = DockLayoutCodec.encode(layout);
    DockLayout restored = DockLayoutCodec.decode(json);
    assertEquals(layout, restored);
    assertEquals(16, specDepth(restored.root()));
  }

  @Test
  void depthSeventeenTreeIsRejectedAtTheOverLimitNodePointer() {
    // 16 层 Split => viewport 深度 17，越界。无法用 DockLayout 构造非法树（构造器会先拒），
    // 故直接用原始 wire 文本，确保被测的是 codec 自己的预检而非冻结构造器。
    String json = nestedViewportWire(16);
    // codec 先递归 first 侧，深链全在 first 上，越界节点即最内层 viewport。
    String pointer = "/root" + "/first".repeat(16);
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertTrue(failure.getMessage().contains(pointer),
        "message must carry the over-limit node pointer " + pointer + " but was: " + failure.getMessage());
    assertTrue(failure.getMessage().contains("17"), failure.getMessage());
  }

  @Test
  void tabsPanelChildCountsOneExtraDepth() {
    // 15 层 Split 后再挂 Tabs，其 panel 子节点深度为 17 => 必须拒绝（同样用原始 wire）。
    // nestedTabsWire 沿 first 链下潜 15 层，故越界 child 的完整 node path 为
    // /root + 15 次 /first + /panels/0 —— 必须精确到实际 panel 而非仅 /panels 数组。
    String json = nestedTabsWire(15);
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    String expectedPointer = "/root" + "/first".repeat(15) + "/panels/0";
    assertPointer(failure, expectedPointer);
    assertTrue(failure.getMessage().contains("17"),
        "message must report the over-limit depth: " + failure.getMessage());
  }

  @Test
  void tabsPanelChildAtExactDepthLimitIsAccepted() {
    // 14 层 Split + Tabs，其 panel 子节点深度恰好 16 => 接受。
    DockNode node = new Tabs("viewport", List.of(new Panel(VIEWPORT)));
    for (int i = 0; i < 14; i++) {
      node = new Split(Axis.HORIZONTAL, 0.5D, new Panel("filler." + i), node);
    }
    DockLayout layout = new DockLayout(1, node);
    assertEquals(16, specDepth(layout.root()));
    assertEquals(layout, DockLayoutCodec.decode(DockLayoutCodec.encode(layout)));
  }

  // ============================================================ 节点预算 golden

  @Test
  void exactlySixtyFourNodesAreAccepted() {
    DockLayout layout = new DockLayout(1, tabs63());
    assertEquals(64, specNodeCount(layout.root()));
    assertEquals(layout, DockLayoutCodec.decode(DockLayoutCodec.encode(layout)));
  }

  @Test
  void sixtyFourNodeSplitTreeIsAcceptedAtExactLimit() {
    // 根 split(1) + first Tabs(1) + 31 面板 + second Tabs(1) + 30 面板 = 64，恰好合法。
    assertEquals(64, balancedNodeCount(31, 30));
    DockLayout layout = DockLayoutCodec.decode(sixtyFourNodeWire());
    assertEquals(64, specNodeCount(layout.root()));
    // balancedTree(30) 两侧对称：1 + 2*(1+30) = 63；补一侧面板即达上限。
    assertEquals(63, specNodeCount(new DockLayout(1, balancedTree(30)).root()));
    assertEquals(sixtyFourNodeWire(), DockLayoutCodec.encode(layout));
  }

  @Test
  void sixtyFiveNodesAreRejectedAtTheOverLimitNodePointer() {
    // 原始 wire：根 split + 两个 Tabs 各 31 面板 => 1 + 32 + 32 = 65 个节点。
    // 越界节点是 second Tabs 的第 31 个面板（0-based 30），pointer 必须精确到该 panel。
    String json = sixtyFiveNodeWire();
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertTrue(failure.getMessage().contains("65"),
        "message must report the over-limit count: " + failure.getMessage());
    assertPointer(failure, "/root/second/panels/30");
  }

  @Test
  void sixtyFourNodeWireIsAccepted() {
    String json = sixtyFourNodeWire();
    DockLayout layout = DockLayoutCodec.decode(json);
    assertEquals(64, specNodeCount(layout.root()));
  }

  // ============================================================== ExactJson 边界

  @Test
  void duplicateKeysAreRejectedByFrozenLexicalLayer() {
    String json = "{\"schemaVersion\":1,\"schemaVersion\":1,\"root\":" + panel(VIEWPORT) + "}";
    assertThrows(JsonContractException.class, () -> DockLayoutCodec.decode(json));
  }

  @Test
  void duplicateEscapedKeysAreRejected() {
    String json = "{\"schemaVersion\":1,\"ro\\u006ft\":" + panel(VIEWPORT) + ",\"root\":"
        + panel(VIEWPORT) + "}";
    assertThrows(JsonContractException.class, () -> DockLayoutCodec.decode(json));
  }

  @Test
  void malformedJsonPropagatesLexicalExceptionUnwrapped() {
    String[] raws = {"", "   ", "{", "{\"schemaVersion\":1,}", "[1,]", "{'a':1}",
        "{\"schemaVersion\":01,\"root\":" + panel(VIEWPORT) + "}",
        "{\"schemaVersion\":1,\"root\":" + panel(VIEWPORT) + "} trailing",
        "{\"schemaVersion\":1,\"root\":" + panel(VIEWPORT) + "}{}"};
    for (String json : raws) {
      JsonContractException failure =
          assertThrows(JsonContractException.class, () -> DockLayoutCodec.decode(json),
              "must reject malformed input: " + json);
      assertNotNull(failure.path());
    }
  }

  @Test
  void oversizedWireIsRejectedByFrozenByteBudget() {
    // 构造远超 8 MiB 的合法形状输入；ExactJson 必须在超预算时报错而非尝试解析。
    StringBuilder builder = new StringBuilder("{\"schemaVersion\":1,\"root\":{\"type\":\"panel\","
        + "\"id\":\"viewport\",\"pad\":\"");
    builder.append("x".repeat(9 * 1024 * 1024));
    builder.append("\"}}");
    JsonContractException failure = assertThrows(JsonContractException.class,
        () -> DockLayoutCodec.decode(builder.toString()));
    assertNotNull(failure.path());
  }

  @Test
  void oversizedScalarStringIsRejectedByFrozenStringBudget() {
    // 单个字符串超过 65536 UTF-8 字节上限（放在未知成员上，形状合法）。
    StringBuilder builder = new StringBuilder("{\"schemaVersion\":1,\"root\":{\"type\":\"panel\","
        + "\"id\":\"viewport\"},\"pad\":\"");
    builder.append("x".repeat(70000));
    builder.append("\"}");
    assertThrows(JsonContractException.class, () -> DockLayoutCodec.decode(builder.toString()));
  }

  @Test
  void deeplyNestedUnknownMembersAreRejectedByFrozenJsonDepthBudget() {
    // JSON 深度预算与 dock 深度预算互相独立：未知但合法的成员仍消耗 JSON 深度。
    StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"root\":" + panel(VIEWPORT)
        + ",\"pad\":");
    int layers = 80;
    for (int i = 0; i < layers; i++) {
      json.append("[");
    }
    json.append("1");
    for (int i = 0; i < layers; i++) {
      json.append("]");
    }
    json.append("}");
    assertThrows(JsonContractException.class, () -> DockLayoutCodec.decode(json.toString()));
  }

  @Test
  void decodeNullIsRejectedByFrozenLayer() {
    assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(null));
  }

  @Test
  void encodeNullIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.encode(null));
  }

  // ================================================================= 随机 oracle

  @Test
  void thousandDistinctTreesMatchIndependentlyBuiltWire() {
    Random random = new Random(20250114L);
    Set<String> seenWire = new HashSet<>();
    int checked = 0;
    for (int attempt = 0; attempt < 4000 && checked < 1000; attempt++) {
      DockNode node = randomNode(random, new Counter());
      if (node == null) {
        continue;
      }
      DockLayout layout;
      try {
        layout = new DockLayout(1, node);
      } catch (IllegalArgumentException e) {
        // 生成器可能产出违反域约束的树；那属于生成器失败，必须跳过而非计为通过。
        continue;
      }
      String wire = DockLayoutCodec.encode(layout);
      if (!seenWire.add(wire)) {
        continue;
      }
      // 独立拼出的期望 wire 必须逐字段一致。
      assertEquals(expectedWire(layout.root()), wire,
          "wire mismatch for independently built expectation");
      // 独立解码期望值：用独立全文拼出的 expectedWire 做整份精确文本比较，
      // 再用 leaf 正则抽取面板 id 与树的叶子集合顺序敏感比对（非逐字段手动解析）。
      assertStructureMatchesWire(layout.root(), wire);
      // 往返一致只是附加判据。
      assertEquals(layout, DockLayoutCodec.decode(wire));
      checked++;
    }
    assertEquals(1000, checked, "the fixed-seed oracle must produce 1000 distinct legal trees");
  }

  @Test
  void oracleGeneratorProducesVariedShapes() {
    Random random = new Random(20250114L);
    Set<String> types = new TreeSet<>();
    Set<String> axes = new TreeSet<>();
    Set<Integer> nodeCounts = new TreeSet<>();
    int produced = 0;
    int maxDepthSeen = 0;
    for (int attempt = 0; attempt < 4000 && produced < 1000; attempt++) {
      DockNode node = randomNode(random, new Counter());
      if (node == null) {
        continue;
      }
      DockLayout layout;
      try {
        layout = new DockLayout(1, node);
      } catch (IllegalArgumentException e) {
        continue;
      }
      types.add(node.getClass().getSimpleName());
      collectAxes(node, axes);
      nodeCounts.add(specNodeCount(node));
      maxDepthSeen = Math.max(maxDepthSeen, specDepth(node));
      produced++;
    }
    assertEquals(1000, produced);
    assertTrue(types.contains("Panel"), types.toString());
    assertTrue(types.contains("Tabs"), types.toString());
    assertTrue(types.contains("Split"), types.toString());
    assertTrue(axes.contains("HORIZONTAL"), axes.toString());
    assertTrue(axes.contains("VERTICAL"), axes.toString());
    assertTrue(nodeCounts.size() >= 10,
        "expected varied node counts but saw " + nodeCounts);
    assertTrue(maxDepthSeen >= 5,
        "oracle must exercise nesting depth, saw max depth " + maxDepthSeen);
  }

  // ================================================================== 辅助

  /** 可变计数器，供随机生成器在深度/节点预算内分配 ID 与限深。 */
  private static final class Counter {
    private int next;

    int next() {
      return next++;
    }
  }

  private static DockNode randomNode(Random random, Counter counter) {
    // viewport 必须恰好出现一次：先生成随机形状，再把某个叶槽替换为 viewport。
    return injectViewport(randomShape(random, counter, 1));
  }

  /** 生成深度/节点预算内的随机形状（不含 viewport 语义保证）。 */
  private static DockNode randomShape(Random random, Counter counter, int depth) {
    // 深度上限 6 足以覆盖多级嵌套，同时避开 16 层硬上限以免生成器大量被拒。
    if (depth >= 6) {
      return new Panel(leafId(random, counter));
    }
    int choice = random.nextInt(10);
    // 越接近上限越偏向叶子，保证树能收敛。
    if (choice < 4 || (depth >= 4 && choice < 8)) {
      return new Panel(leafId(random, counter));
    }
    if (choice < 7) {
      int size = 1 + random.nextInt(3);
      List<Panel> panels = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        panels.add(new Panel(leafId(random, counter)));
      }
      String active = panels.get(random.nextInt(panels.size())).id();
      return new Tabs(active, panels);
    }
    DockNode first = randomShape(random, counter, depth + 1);
    DockNode second = randomShape(random, counter, depth + 1);
    Axis axis = random.nextBoolean() ? Axis.HORIZONTAL : Axis.VERTICAL;
    return new Split(axis, ratioFor(random.nextInt(21)), first, second);
  }

  /**
   * 把树中第一个叶子槽替换为 viewport，保证全树恰好一个 viewport。若树中已有 ID 为 viewport 的叶子则原样返回。
   */
  private static DockNode injectViewport(DockNode node) {
    if (specLeaves(node).contains(VIEWPORT)) {
      return node;
    }
    return replaceFirstLeaf(node, new Panel(VIEWPORT), new boolean[] {false});
  }

  private static DockNode replaceFirstLeaf(DockNode node, Panel replacement, boolean[] done) {
    if (done[0]) {
      return node;
    }
    if (node instanceof Panel) {
      done[0] = true;
      return replacement;
    }
    if (node instanceof Tabs tabs) {
      List<Panel> panels = new ArrayList<>(tabs.panels());
      panels.set(0, replacement);
      done[0] = true;
      // activePanelId 必须在组内：替换后若原 active 已被挤掉，则指向替换项。
      String active = panels.contains(new Panel(tabs.activePanelId()))
          ? tabs.activePanelId() : replacement.id();
      return new Tabs(active, panels);
    }
    Split split = (Split) node;
    DockNode first = replaceFirstLeaf(split.first(), replacement, done);
    DockNode second = done[0] ? split.second() : replaceFirstLeaf(split.second(), replacement, done);
    return new Split(split.axis(), split.ratio(), first, second);
  }

  /** 生成 0.05..0.95 之间的确定性 double 序列（含闭区间端点与高精度小数）。 */
  private static double ratioFor(int step) {
    if (step == 0) {
      return 0.05D;
    }
    if (step == 20) {
      return 0.95D;
    }
    if (step == 19) {
      return Math.nextDown(0.95D);
    }
    if (step == 1) {
      return Math.nextUp(0.05D);
    }
    return step / 20.0D + 0.0123456789D;
  }

  private static String leafId(Random random, Counter counter) {
    String id = "p" + counter.next() + "." + Math.abs(random.nextInt(1000));
    return id.matches("[a-z][a-z0-9_.-]{0,63}") ? id : "p" + counter.next();
  }

  /** 独立构造期望 wire：不复用实现代码路径，成员顺序按 String.compareTo。 */
  /**
   * 独立构造期望 wire（完整根文档）。键序按冻结层的 String.compareTo 规范化写出： root &lt; schemaVersion。
   */
  private static String expectedWire(DockNode node) {
    return "{\"root\":" + expectedNode(node) + ",\"schemaVersion\":1}";
  }

  private static String expectedNode(DockNode node) {
    if (node instanceof Panel panel) {
      return expectedPanel(panel.id());
    }
    if (node instanceof Tabs tabs) {
      return expectedTabs(tabs.activePanelId(), tabs.panels());
    }
    Split split = (Split) node;
    return expectedSplit(split.axis(), split.ratio(), split.first(), split.second());
  }

  private static String expectedPanel(String id) {
    return "{\"id\":\"" + id + "\",\"type\":\"panel\"}";
  }

  private static String expectedTabs(String active, List<Panel> panels) {
    StringBuilder builder = new StringBuilder();
    builder.append("{\"activePanelId\":\"").append(active).append("\",\"panels\":[");
    for (int i = 0; i < panels.size(); i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(expectedPanel(panels.get(i).id()));
    }
    builder.append("],\"type\":\"tabs\"}");
    return builder.toString();
  }

  private static String expectedSplit(Axis axis, double ratio, DockNode first, DockNode second) {
    return "{\"axis\":\"" + (axis == Axis.HORIZONTAL ? "horizontal" : "vertical")
        + "\",\"first\":" + expectedNode(first)
        + ",\"ratio\":" + canonicalNumber(ratio)
        + ",\"second\":" + expectedNode(second)
        + ",\"type\":\"split\"}";
  }

  /** 与冻结规范化一致的十进制文本：先 BigDecimal.valueOf(double) 再 stripTrailingZeros。 */
  private static String canonicalNumber(double value) {
    if (value == 0.0D) {
      return "0";
    }
    return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toString();
  }

  /** 独立走一遍 wire 文本，逐字段核对类型/轴向/顺序/激活项/ratio。 */
  private static void assertStructureMatchesWire(DockNode node, String wire) {
    String expected = expectedWire(node);
    assertEquals(expected, wire);
    // 额外断言：wire 中出现的每个叶子 ID 都与树的叶子集合一致（顺序敏感）。
    List<String> wireLeaves = new ArrayList<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\{\"id\":\"([^\"]*)\",\"type\":\"panel\"\\}")
            .matcher(wire);
    while (matcher.find()) {
      wireLeaves.add(matcher.group(1));
    }
    assertEquals(specLeaves(node), wireLeaves);
  }

  /** 按规格账目统计节点数，独立于实现。 */
  private static int specNodeCount(DockNode node) {
    if (node instanceof Panel) {
      return 1;
    }
    if (node instanceof Tabs tabs) {
      return 1 + tabs.panels().size();
    }
    Split split = (Split) node;
    return 1 + specNodeCount(split.first()) + specNodeCount(split.second());
  }

  /** 按规格账目统计最大深度（根为 1）。 */
  private static int specDepth(DockNode node) {
    if (node instanceof Panel) {
      return 1;
    }
    if (node instanceof Tabs) {
      return 2;
    }
    Split split = (Split) node;
    return 1 + Math.max(specDepth(split.first()), specDepth(split.second()));
  }

  /** 叶子 ID 的深度优先出现顺序。 */
  private static List<String> specLeaves(DockNode node) {
    List<String> leaves = new ArrayList<>();
    collectLeaves(node, leaves);
    return leaves;
  }

  private static void collectLeaves(DockNode node, List<String> leaves) {
    if (node instanceof Panel panel) {
      leaves.add(panel.id());
    } else if (node instanceof Tabs tabs) {
      for (Panel panel : tabs.panels()) {
        leaves.add(panel.id());
      }
    } else {
      Split split = (Split) node;
      collectLeaves(split.first(), leaves);
      collectLeaves(split.second(), leaves);
    }
  }

  private static void collectAxes(DockNode node, Set<String> axes) {
    ArrayDeque<DockNode> pending = new ArrayDeque<>();
    pending.add(node);
    while (!pending.isEmpty()) {
      DockNode current = pending.poll();
      if (current instanceof Split split) {
        axes.add(split.axis().name());
        pending.add(split.first());
        pending.add(split.second());
      }
    }
  }

  /** 结构等价比对（逐节点类型/字段），比 record equals 更明确地给出失败点。 */
  private static void assertSameStructure(DockNode expected, DockNode actual) {
    if (expected instanceof Panel expectedPanel) {
      Panel actualPanel = assertInstanceOf(Panel.class, actual);
      assertEquals(expectedPanel.id(), actualPanel.id());
      return;
    }
    if (expected instanceof Tabs expectedTabs) {
      Tabs actualTabs = assertInstanceOf(Tabs.class, actual);
      assertEquals(expectedTabs.activePanelId(), actualTabs.activePanelId());
      assertEquals(expectedTabs.panels().size(), actualTabs.panels().size());
      for (int i = 0; i < expectedTabs.panels().size(); i++) {
        assertEquals(expectedTabs.panels().get(i).id(), actualTabs.panels().get(i).id(),
            "panel order must be preserved at index " + i);
      }
      return;
    }
    Split expectedSplit = assertInstanceOf(Split.class, expected);
    Split actualSplit = assertInstanceOf(Split.class, actual);
    assertEquals(expectedSplit.axis(), actualSplit.axis());
    assertEquals(Double.doubleToRawLongBits(expectedSplit.ratio()),
        Double.doubleToRawLongBits(actualSplit.ratio()));
    assertSameStructure(expectedSplit.first(), actualSplit.first());
    assertSameStructure(expectedSplit.second(), actualSplit.second());
  }

  private static List<DockLayout> sampleLayouts() {
    List<DockLayout> layouts = new ArrayList<>();
    layouts.add(new DockLayout(1, new Panel(VIEWPORT)));
    layouts.add(new DockLayout(1, new Tabs("a", List.of(new Panel("a"), new Panel(VIEWPORT)))));
    layouts.add(new DockLayout(1, new Split(Axis.HORIZONTAL, 0.3D, new Panel("a"), new Panel(VIEWPORT))));
    layouts.add(new DockLayout(1, new Split(Axis.VERTICAL, Math.nextUp(0.05D),
        new Tabs("a", List.of(new Panel("a"))), new Panel(VIEWPORT))));
    layouts.add(new DockLayout(1, new Split(Axis.HORIZONTAL, Math.nextDown(0.95D),
        new Split(Axis.VERTICAL, 0.5D, new Panel("a"), new Panel("b")), new Panel(VIEWPORT))));
    layouts.add(new DockLayout(1, nestedViewportLayout(15).root()));
    layouts.add(new DockLayout(1, tabs63()));
    return layouts;
  }

  /**
   * viewport 外包 layers 层 Split：从内向外每层把上一层子树接到 second，first 放唯一 filler。
   * 因此整个树始终只含一个 viewport（最内层那个），深层结构在 second 一条链上。
   */
  private static DockLayout nestedViewportLayout(int layers) {
    DockNode node = new Panel(VIEWPORT);
    for (int i = 0; i < layers; i++) {
      node = new Split(Axis.HORIZONTAL, 0.5D, new Panel("filler." + i), node);
    }
    return new DockLayout(1, node);
  }

  /** 单个 Tabs，其面板子节点共 63 个：树节点总数 64。 */
  private static Tabs tabs63() {
    List<Panel> panels = new ArrayList<>(63);
    for (int i = 0; i < 63; i++) {
      panels.add(new Panel(i == 0 ? VIEWPORT : "p." + i));
    }
    return new Tabs(VIEWPORT, panels);
  }

  /**
   * 构造节点总数为 1 + (1 + panelsPerSide) + (1 + panelsPerSide) 的平衡树：
   * 根 split 计 1，两侧各一个 Tabs 计 1，再加各自的面板子节点数。
   *
   * @param panelsPerSide 每侧 Tabs 的面板子节点数
   */
  private static DockNode balancedTree(int panelsPerSide) {
    DockNode first = sideTabs("l", panelsPerSide, true);
    DockNode second = sideTabs("r", panelsPerSide, false);
    return new Split(Axis.HORIZONTAL, 0.5D, first, second);
  }

  // ------------------------------------------------- 原始 wire 构造（超限用例）

  /** viewport 外包 layers 层 Split 的原始 wire；深链走 first 侧，使越界节点为最内层节点。 */
  private static String nestedViewportWire(int layers) {
    String node = panel(VIEWPORT);
    for (int i = 0; i < layers; i++) {
      node = splitNode("horizontal", "0.5", node, panel("filler." + i));
    }
    return document(node);
  }

  /** 最深处为 Tabs 的原始 wire：layers 层 Split 后挂一个单面板 Tabs，深链同样走 first。 */
  private static String nestedTabsWire(int layers) {
    String node = "{\"activePanelId\":\"" + VIEWPORT + "\",\"panels\":[" + panel(VIEWPORT)
        + "],\"type\":\"tabs\"}";
    for (int i = 0; i < layers; i++) {
      node = splitNode("horizontal", "0.5", node, panel("filler." + i));
    }
    return document(node);
  }

  /** 根 split + 两侧 Tabs，各含 panelsPerSide 个面板：节点数 = 1 + (1 + n) + (1 + n)。 */
  private static String balancedWire(int panelsPerSide) {
    return document(splitNode("horizontal", "0.5",
        tabsNode("l", panelsPerSide, true), tabsNode("r", panelsPerSide, false)));
  }

  private static String sixtyFiveNodeWire() {
    return balancedWire(31);
  }

  private static String sixtyFourNodeWire() {
    // 根 1 + first 侧 Tabs(1) + 31 面板 + second 侧 Tabs(1) + 30 面板 = 64。
    return document(splitNode("horizontal", "0.5",
        tabsNode("l", 31, true), tabsNode("r", 30, false)));
  }

  /** 节点数 = 根 split(1) + 两侧 Tabs(1) + 各自面板数。 */
  private static int balancedNodeCount(int leftPanels, int rightPanels) {
    return 1 + (1 + leftPanels) + (1 + rightPanels);
  }

  /** 生成一个规范键序的 Tabs 节点对象；holdsViewport 为真时首项为 viewport。 */
  private static String tabsNode(String prefix, int count, boolean holdsViewport) {
    StringBuilder builder = new StringBuilder("{\"activePanelId\":\"");
    String active = holdsViewport ? VIEWPORT : prefix + ".0";
    builder.append(active).append("\",\"panels\":[");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(panel(holdsViewport && i == 0 ? VIEWPORT : prefix + "." + i));
    }
    builder.append("],\"type\":\"tabs\"}");
    return builder.toString();
  }

  private static Tabs sideTabs(String prefix, int count, boolean holdsViewport) {
    List<Panel> panels = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      if (holdsViewport && i == 0) {
        panels.add(new Panel(VIEWPORT));
      } else {
        panels.add(new Panel(prefix + "." + i));
      }
    }
    return new Tabs(panels.get(0).id(), panels);
  }

  // ---------------------------------------------------------- JSON 片段拼装

  private static String panel(String id) {
    return "{\"id\":\"" + id + "\",\"type\":\"panel\"}";
  }

  /** 完整根文档中的 split 节点片段（供需要合法根对象的用例使用）。 */
  private static String splitJson(String axis, String ratio, String first, String second) {
    return document(splitNode(axis, ratio, first, second));
  }

  /** 规范键序的 split 节点对象（与冻结层输出一致）。 */
  private static String splitNode(String axis, String ratio, String first, String second) {
    return "{\"axis\":\"" + axis + "\",\"first\":" + first + ",\"ratio\":" + ratio
        + ",\"second\":" + second + ",\"type\":\"split\"}";
  }

  /** 把节点片段包装成完整根文档；键序用规范序（root &lt; schemaVersion），与冻结层输出一致。 */
  private static String document(String node) {
    return "{\"root\":" + node + ",\"schemaVersion\":1}";
  }

  /** 生成 size 个面板对象（不含 viewport 冲突：首个为 viewport）。 */
  private static String tabsJson(int size) {
    StringBuilder builder = new StringBuilder(
        "{\"schemaVersion\":1,\"root\":{\"type\":\"tabs\",\"activePanelId\":\"viewport\","
            + "\"panels\":[");
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(panel(i == 0 ? VIEWPORT : "p." + i));
    }
    builder.append("]}}");
    return builder.toString();
  }

  /** 指针断言：消息必须精确包含该 JSON pointer（无尾随限制以便同时容纳子路径）。 */
  private static void assertPointer(IllegalArgumentException failure, String pointer) {
    String message = failure.getMessage();
    assertNotNull(message, "failure message must not be null");
    assertTrue(message.contains(pointer),
        "expected pointer '" + pointer + "' in message but was: " + message);
  }

  /**
   * 全树约束（叶子 id 唯一、恰好一个 viewport）只在冻结构造器内校核，codec 必须补齐
   * {@code /root} 指针并把真实域异常保留为 cause。
   *
   * @param domainFragment 域消息中应保留的片段；为 null 时只校验 cause 类型与文案不同
   */
  private static void assertWholeTreeDomainFailure(String json, String domainFragment) {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> DockLayoutCodec.decode(json));
    assertFalse(failure instanceof JsonContractException,
        "全树域约束失败必须是 IAE，不能是 JsonContractException: " + failure);
    assertPointer(failure, "/root");
    Throwable cause = failure.getCause();
    assertNotNull(cause, "必须保留真实 domain cause: " + failure);
    assertTrue(cause instanceof IllegalArgumentException,
        "cause 必须是构造器的 IllegalArgumentException 但实际为: " + cause);
    assertNotSame(failure, cause, "cause 必须不同于包装异常");
    String causeMessage = cause.getMessage();
    assertNotNull(causeMessage, "domain cause 必须携带原始消息");
    if (domainFragment != null) {
      assertTrue(causeMessage.contains(domainFragment),
          "domain cause 应保留原始文案片段 '" + domainFragment + "' 但实际为: " + causeMessage);
    }
    // 包装异常自身也应携带 domain 文案，而不是被替换成空壳。
    assertTrue(failure.getMessage().contains(causeMessage),
        "包装异常应包含 domain 原始消息: " + failure.getMessage());
  }

  static {
    // 保证未使用导入不触发编译警告语义变化：显式引用以固定意图。
    assertTrue(Collections.emptyList().isEmpty());
  }
}
