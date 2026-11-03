package net.ptcrys.fpsmatch.editor.common.client.viewport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R18.1a2 原生像素边界（NATIVE-PIXEL-BOUNDS R1.1）作者独立测试。
 *
 * <p>范围：{@link ViewportLayout} 的四组件 record、{@link Optional} 测量值的唯一逆公式认证、raw pixel 四边界经唯一
 * clamp/floor·ceil/零轴 helper 的真实消费，以及旧三参数 empty 路径的逐字保持。全部期望值来自本文件自己的独立
 * oracle 或已用 scratch 探针核过的字面量，<strong>不</strong>复用生产 {@code framebufferRect()} 结果、也
 * <strong>不</strong>由另一个 {@code ViewportLayout} 实例推导期望。真实调用生产 API（构造 + {@code framebufferRect()}），
 * 不 mock、不 skip。
 */
final class ViewportMeasuredBoundsContractTest {

  private static final int FB_WIDTH = 1280;
  private static final int FB_HEIGHT = 720;

  private static final LogicalRect ZERO_RECT = new LogicalRect(0.0D, 0.0D, 0.0D, 0.0D);

  /** compareRawCandidate 结果：与独立 raw oracle 对照成功。 */
  private static final int OUTCOME_COMPARED = 0;
  /** compareRawCandidate 结果：逆公式非有限，实现与测试都按 IAE 拒绝。 */
  private static final int OUTCOME_INVERSE_REJECTED = 1;

  // ------------------------------------------------------------------ 构造与 record 语义

  @Test
  @DisplayName("四参数构造：拒绝负 generation 与三个 null 引用，接受 generation=0 与 empty")
  void fourArgRejectsNullAndNegativeGeneration() {
    LogicalRect logical = new LogicalRect(0.0D, 0.0D, 1.0D, 1.0D);
    ScaleTransform transform = identityScale();

    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(-1L, logical, transform, Optional.empty()));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(Long.MIN_VALUE, logical, transform, Optional.empty()));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(0L, null, transform, Optional.empty()));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(0L, logical, null, Optional.empty()));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(0L, logical, transform, null));

    ViewportLayout ok = new ViewportLayout(0L, logical, transform, Optional.empty());
    assertEquals(0L, ok.layoutGeneration());
    assertTrue(ok.measuredFramebufferBounds().isEmpty());
    // R18.1a2c 第 3 条：成功对象真实消费 framebufferRect()（期望由本文件独立给出）。
    assertRect(0, 0, 1, 1, ok);
  }

  @Test
  @DisplayName("旧三参数构造与四参数 empty 完全等值，且 measuredFramebufferBounds 是共享 empty 单例")
  void threeArgLegacyEqualsFourArgEmptyAndKeepsEmptyOptional() {
    LogicalRect logical = new LogicalRect(10.2D, 20.2D, 100.1D, 50.1D);
    ScaleTransform transform = cardExampleScale();

    ViewportLayout legacy = new ViewportLayout(7L, logical, transform);
    ViewportLayout explicitEmpty = new ViewportLayout(7L, logical, transform, Optional.empty());

    assertEquals(legacy, explicitEmpty);
    assertEquals(explicitEmpty, legacy);
    assertEquals(legacy.hashCode(), explicitEmpty.hashCode());
    assertEquals(legacy.toString(), explicitEmpty.toString());
    assertTrue(legacy.measuredFramebufferBounds().isEmpty());
    assertSame(Optional.empty(), legacy.measuredFramebufferBounds());
    assertSame(logical, legacy.logicalRect());
    assertSame(transform, legacy.scaleTransform());
    assertEquals(legacy.framebufferRect(), explicitEmpty.framebufferRect());

    // R18.1a2c 第 3 条：两个成功 empty 布局都按独立 old oracle 真实消费 framebufferRect()。
    FramebufferRect legacyExpected = emptyPathOracle(logical, transform);
    assertRect(legacyExpected.x(), legacyExpected.y(), legacyExpected.width(), legacyExpected.height(),
        legacy.framebufferRect(), "independent old oracle=" + legacyExpected + " legacy=" + legacy);
    assertRect(legacyExpected.x(), legacyExpected.y(), legacyExpected.width(), legacyExpected.height(),
        explicitEmpty.framebufferRect(), "independent old oracle=" + legacyExpected);
  }

  @Test
  @DisplayName("present：Optional 与内部 raw rect 都保原引用，不被复制或归一化")
  void measuredOptionalAndRawRectKeepSameReferences() {
    ScaleTransform transform = scale(2.0D, 3.0D, 7.25D, 50.5D);
    LogicalRect raw = new LogicalRect(100.5D, 200.25D, 40.5D, 60.75D);
    Optional<LogicalRect> optional = Optional.of(raw);
    ViewportLayout layout = new ViewportLayout(3L, inverse(raw, transform), transform, optional);

    assertSame(optional, layout.measuredFramebufferBounds());
    assertSame(raw, layout.measuredFramebufferBounds().get());
    assertSame(layout.measuredFramebufferBounds(), layout.measuredFramebufferBounds());
    assertEquals(raw, layout.measuredFramebufferBounds().get());

    // 等值但不同实例的 raw rect：保留传入的那个实例，record 仍等值。
    LogicalRect copy = new LogicalRect(raw.left(), raw.top(), raw.width(), raw.height());
    ViewportLayout other = new ViewportLayout(3L, inverse(copy, transform), transform, Optional.of(copy));
    assertSame(copy, other.measuredFramebufferBounds().get());
    assertFalse(layout.measuredFramebufferBounds().get() == copy);
    assertEquals(layout, other);
    assertEquals(layout.hashCode(), other.hashCode());

    // R18.1a2c 第 3 条：原 layout 与等值 copy 都真实消费 framebufferRect() 并核出同一像素矩形。
    int[] rawExpected = rawPixelOracle(raw, transform);
    assertEquals(100, rawExpected[0]);
    assertEquals(200, rawExpected[1]);
    assertEquals(41, rawExpected[2]);
    assertEquals(61, rawExpected[3]);
    assertRect(100, 200, 41, 61, layout.framebufferRect(), "layout=" + layout);
    assertRect(100, 200, 41, 61, other.framebufferRect(), "other=" + other);
  }

  @Test
  @DisplayName("present：唯一逆公式认证通过，logicalRect 逐位等于逆公式结果")
  void presentCertifiesUniqueInverseFormula() {
    ScaleTransform transform = scale(1.1D, 0.75D, -13.5D, 41.25D);
    LogicalRect raw = new LogicalRect(300.25D, 120.5D, 480.5D, 360.25D);

    LogicalRect expected = inverse(raw, transform);
    ViewportLayout layout = new ViewportLayout(11L, expected, transform, Optional.of(raw));

    assertEquals(expected, layout.logicalRect());
    assertEquals((raw.left() - transform.pixelOriginX()) / transform.xScale(), layout.logicalRect().left());
    assertEquals((raw.top() - transform.pixelOriginY()) / transform.yScale(), layout.logicalRect().top());
    assertEquals(raw.width() / transform.xScale(), layout.logicalRect().width());
    assertEquals(raw.height() / transform.yScale(), layout.logicalRect().height());
    assertRect(300, 120, 481, 361, layout);
  }

  @Test
  @DisplayName("present：logical 与逆公式差 1ulp 即拒绝；等值不同实例则接受")
  void presentRejectsLogicalThatDiffersFromInverse() {
    ScaleTransform identity = identityScale();
    LogicalRect raw = new LogicalRect(10.0D, 20.0D, 5.0D, 5.0D);

    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, new LogicalRect(10.0D, 20.0D, Math.nextUp(5.0D), 5.0D), identity,
            Optional.of(raw)));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, new LogicalRect(Math.nextDown(10.0D), 20.0D, 5.0D, 5.0D), identity,
            Optional.of(raw)));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, new LogicalRect(10.0D, Math.nextUp(20.0D), 5.0D, 5.0D), identity,
            Optional.of(raw)));

    LogicalRect exact = inverse(raw, identity);
    LogicalRect distinct = new LogicalRect(exact.left(), exact.top(), exact.width(), exact.height());
    ViewportLayout layout = new ViewportLayout(1L, distinct, identity, Optional.of(raw));
    assertSame(distinct, layout.logicalRect());
    // R18.1a2c 第 3 条：成功 distinct 布局真实消费 framebufferRect()（raw 四边即 (10,20,5,5)）。
    assertRect(10, 20, 5, 5, layout);

    ScaleTransform third = scale(3.0D, 3.0D, 0.0D, 0.0D);
    LogicalRect rawOne = new LogicalRect(0.0D, 0.0D, 1.0D, 1.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, new LogicalRect(0.0D, 0.0D, Math.nextUp(1.0D / 3.0D), 1.0D / 3.0D),
            third, Optional.of(rawOne)));
    ViewportLayout thirdLayout = new ViewportLayout(1L, inverse(rawOne, third), third, Optional.of(rawOne));
    assertEquals(1.0D / 3.0D, thirdLayout.logicalRect().width());
    assertRect(0, 0, 1, 1, thirdLayout);
  }

  @Test
  @DisplayName("present：逆公式非有限/溢出明确抛 IllegalArgumentException")
  void presentRejectsNonFiniteInverseAndOverflow() {
    ScaleTransform identity = identityScale();

    // width / xScale 上溢到 +Infinity。
    ScaleTransform tinyScale = scale(Double.MIN_VALUE, Double.MIN_VALUE, 0.0D, 0.0D);
    LogicalRect wideRaw = new LogicalRect(0.0D, 0.0D, 1.0D, 1.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, ZERO_RECT, tinyScale, Optional.of(wideRaw)));

    // (pixel - origin) 相减上溢到 +Infinity。
    LogicalRect extremeRaw = new LogicalRect(Double.MAX_VALUE, 0.0D, 0.0D, 0.0D);
    ScaleTransform negativeOrigin = scale(1.0D, 1.0D, -Double.MAX_VALUE, 0.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, ZERO_RECT, negativeOrigin, Optional.of(extremeRaw)));

    // width / xScale 上溢（raw 自身合法）。
    LogicalRect hugeWidthRaw = new LogicalRect(0.0D, 0.0D, Double.MAX_VALUE, 1.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, ZERO_RECT, scale(0.5D, 1.0D, 0.0D, 0.0D), Optional.of(hugeWidthRaw)));

    // 逆 left/width 各自有限、但相加溢出（raw right 仍有限）。
    LogicalRect sumOverflowRaw = new LogicalRect(9.0E307D, 0.0D, 8.9E307D, 1.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, ZERO_RECT, scale(0.5D, 0.5D, 0.0D, 0.0D), Optional.of(sumOverflowRaw)));

    // 除数为 0 级极小 scale：height / yScale 上溢。
    ScaleTransform tinyY = scale(1.0D, Double.MIN_VALUE, 0.0D, 0.0D);
    LogicalRect tallRaw = new LogicalRect(0.0D, 0.0D, 1.0D, 1.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(1L, ZERO_RECT, tinyY, Optional.of(tallRaw)));
  }

  @Test
  @DisplayName("record 语义：toString/equals/hashCode 覆盖 Optional 值，generation 与测量都参与")
  void measuredLayoutRecordSemanticsAndToString() {
    ScaleTransform transform = identityScale();
    LogicalRect raw = new LogicalRect(4.0D, 5.0D, 6.0D, 7.0D);
    ViewportLayout layout = new ViewportLayout(5L, inverse(raw, transform), transform, Optional.of(raw));
    ViewportLayout same = new ViewportLayout(5L, inverse(raw, transform), transform,
        Optional.of(new LogicalRect(4.0D, 5.0D, 6.0D, 7.0D)));

    assertEquals(layout, same);
    assertEquals(layout.hashCode(), same.hashCode());
    assertNotEquals(layout, new ViewportLayout(6L, inverse(raw, transform), transform, Optional.of(raw)));
    assertNotEquals(layout, new ViewportLayout(5L, inverse(raw, transform), transform, Optional.empty()));
    assertNotNull(layout.toString());
    assertTrue(layout.toString().contains("measuredFramebufferBounds"), layout.toString());
    assertTrue(layout.toString().contains("layoutGeneration=5"), layout.toString());
    assertFalse(layout.toString().isEmpty());

    // R18.1a2c 第 3 条：两个成功布局（原实例与等值新实例）都真实消费 framebufferRect()。
    assertRect(4, 5, 6, 7, layout.framebufferRect(), "layout=" + layout);
    assertRect(4, 5, 6, 7, same.framebufferRect(), "same=" + same);
  }

  @Test
  @DisplayName("framebufferRect 在 measured/empty 布局上都是纯函数、可重入且与等值副本一致")
  void framebufferRectIsPureAndReentrantForMeasuredLayouts() {
    ScaleTransform transform = scale(2.0D, 3.0D, 7.25D, 50.5D);
    List<ViewportLayout> layouts = new ArrayList<>();
    layouts.add(new ViewportLayout(1L, new LogicalRect(10.2D, 20.2D, 100.1D, 50.1D), transform));
    layouts.add(measured(2L, new LogicalRect(100.5D, 200.25D, 40.5D, 60.75D), transform));
    layouts.add(measured(3L, new LogicalRect(0.0D, 0.0D, Double.MIN_VALUE, 4.0D), scale(2.0D, 1.0D, 0.0D, 0.0D)));
    layouts.add(measured(4L, new LogicalRect(-5.25D, -2.5D, 10.75D, 6.25D), identityScale()));

    for (ViewportLayout layout : layouts) {
      FramebufferRect first = layout.framebufferRect();
      FramebufferRect second = layout.framebufferRect();
      assertEquals(first, second);
      assertEquals(first.x(), second.x());
      assertEquals(first.y(), second.y());
      assertEquals(first.width(), second.width());
      assertEquals(first.height(), second.height());
    }
    ViewportLayout measuredLayout = layouts.get(1);
    ViewportLayout copy = new ViewportLayout(measuredLayout.layoutGeneration(), measuredLayout.logicalRect(),
        measuredLayout.scaleTransform(), measuredLayout.measuredFramebufferBounds());
    assertEquals(measuredLayout.framebufferRect(), copy.framebufferRect());
    assertEquals(2L, measuredLayout.layoutGeneration());
  }

  // ------------------------------------------------------------------ raw 路径：直接消费原始四边界

  @Test
  @DisplayName("present 路径不动原点/密度：同一 raw 在任意 scale/origin 下给出同一像素矩形")
  void rawPathIgnoresDensityAndOriginForPixels() {
    LogicalRect raw = new LogicalRect(1.0D, 1.0D, 4.0D, 4.0D);
    ScaleTransform[] transforms = {
        scale(49.0D, 49.0D, 0.0D, 0.0D),
        scale(49.0D, 49.0D, 0.5D, 7.25D),
        scale((double) 1.1F, (double) 1.1F, 29.0D, 29.0D),
        scale(2.0D, 0.5D, -1000.0D, 512.5D),
    };
    for (ScaleTransform transform : transforms) {
      ViewportLayout layout = new ViewportLayout(1L, inverse(raw, transform), transform, Optional.of(raw));
      assertRect(1, 1, 4, 4, layout);
      assertEquals(new FramebufferRect(1, 1, 4, 4), layout.framebufferRect());
    }
  }

  @Test
  @DisplayName("回归：raw origin0/density49 四边——raw 路径给 1px，旧路径反向往返映射塌成 0/5")
  void density49Origin0RawFourEdgesRegression() {
    ScaleTransform transform = scale(49.0D, 49.0D, 0.0D, 0.0D);
    LogicalRect raw = new LogicalRect(1.0D, 1.0D, 4.0D, 4.0D);
    LogicalRect certified = inverse(raw, transform);

    ViewportLayout measuredLayout = new ViewportLayout(1L, certified, transform, Optional.of(raw));
    ViewportLayout legacy = new ViewportLayout(1L, certified, transform);

    assertRect(1, 1, 4, 4, measuredLayout);
    assertRect(0, 0, 5, 5, legacy);
    assertNotEquals(legacy.framebufferRect(), measuredLayout.framebufferRect());
    assertNotEquals(legacy, measuredLayout);
  }

  @Test
  @DisplayName("回归：raw origin29/density1.1f 四边——raw 路径精确，旧路径 floor 到 3/7")
  void density1p1fOrigin29RawFourEdgesRegression() {
    ScaleTransform transform = scale((double) 1.1F, (double) 1.1F, 29.0D, 29.0D);
    LogicalRect raw = new LogicalRect(4.0D, 8.0D, 6.0D, 6.0D);
    LogicalRect certified = inverse(raw, transform);

    ViewportLayout measuredLayout = new ViewportLayout(1L, certified, transform, Optional.of(raw));
    ViewportLayout legacy = new ViewportLayout(1L, certified, transform);

    assertRect(4, 8, 6, 6, measuredLayout);
    assertRect(3, 7, 7, 7, legacy);
    assertNotEquals(legacy.framebufferRect(), measuredLayout.framebufferRect());
  }

  @Test
  @DisplayName("raw 路径：非零原点与独立 X/Y scale 下四边逐位保留")
  void nonZeroOriginAndIndependentScaleRawFourEdgesExact() {
    assertRect(3, 7, 10, 5,
        measured(1L, new LogicalRect(3.0D, 7.0D, 10.0D, 5.0D), scale(1.0D, 1.0D, 9.5D, 4.25D)));
    assertRect(1, 3, 7, 9,
        measured(2L, new LogicalRect(1.0D, 3.0D, 7.0D, 9.0D), scale(2.0D, 0.5D, 0.0D, 0.0D)));
    assertRect(0, 2, 6, 5,
        measured(3L, new LogicalRect(0.75D, 2.5D, 5.25D, 3.75D), scale(0.25D, 0.125D, 1280.0D, 720.0D)));
  }

  @Test
  @DisplayName("raw 路径：负/小数边界 clamp 到窗口内并按 floor/ceil 取整")
  void negativeAndFractionalRawEdgesClampToWindow() {
    assertRect(0, 0, 6, 4,
        measured(1L, new LogicalRect(-5.25D, -2.5D, 10.75D, 6.25D), identityScale()));
    assertRect(0, 720, 1, 0,
        measured(2L, new LogicalRect(-0.25D, 720.0D, 0.5D, 0.5D), identityScale()));
    assertMatchesRawOracle(3L, new LogicalRect(0.9999999999999999D, 1.0000000000000002D, 4.0D, 4.0D),
        identityScale());
  }

  @Test
  @DisplayName("raw 路径：完全在 framebuffer 外时交集自然为空")
  void offscreenRawBoundsCollapseToEmptyRect() {
    assertRect(FB_WIDTH, FB_HEIGHT, 0, 0,
        measured(1L, new LogicalRect(1280.5D, 720.5D, 5.0D, 5.0D), identityScale()));
    assertRect(0, 0, 0, 0,
        measured(2L, new LogicalRect(-50.0D, -40.0D, 1.0D, 1.0D), identityScale()));
    assertRect(FB_WIDTH, 10, 0, 5,
        measured(3L, new LogicalRect(1300.0D, 10.0D, 20.0D, 5.0D), identityScale()));
    assertRect(FB_WIDTH, 0, 0, FB_HEIGHT,
        measured(4L, new LogicalRect(1280.0D, 0.0D, 3.0D, 720.0D), identityScale()));
  }

  @Test
  @DisplayName("raw 路径：raw 空轴标志为真时该轴不凭空产生 1px")
  void zeroRawAxesDoNotInventPixels() {
    assertRect(10, 20, 0, 9, measured(1L, new LogicalRect(10.5D, 20.25D, 0.0D, 8.0D), identityScale()));
    assertRect(10, 20, 9, 0, measured(2L, new LogicalRect(10.5D, 20.25D, 8.0D, 0.0D), identityScale()));
    assertRect(10, 20, 0, 0, measured(3L, new LogicalRect(10.5D, 20.25D, 0.0D, 0.0D), identityScale()));
    assertRect(0, 5, 0, 0,
        measured(4L, new LogicalRect(-2.5D, 5.5D, 0.0D, 0.0D), scale(3.0D, 7.0D, 60.0D, -40.0D)));
    assertRect(FB_WIDTH, FB_HEIGHT, 0, 0,
        measured(5L, new LogicalRect(1280.5D, 720.5D, 0.0D, 0.0D), identityScale()));
  }

  @Test
  @DisplayName("raw 下溢：xScale=2 时 Double.MIN_VALUE/2 逆 width=0，但 raw 轴非零仍 ceil 出 1px")
  void rawUnderflowInverseWidthStillCeilsOnePixel() {
    ScaleTransform transform = scale(2.0D, 1.0D, 0.0D, 0.0D);
    LogicalRect raw = new LogicalRect(0.0D, 0.0D, Double.MIN_VALUE, 4.0D);
    LogicalRect certified = inverse(raw, transform);

    assertEquals(0.0D, certified.width(), "逆 width 应下溢成 +0.0");
    assertEquals(4.0D, certified.height());
    assertNotEquals(0.0D, raw.width(), "raw 像素跨度必须仍非零");
    assertTrue(raw.width() > 0.0D);

    ViewportLayout measuredLayout = new ViewportLayout(1L, certified, transform, Optional.of(raw));
    ViewportLayout legacy = new ViewportLayout(1L, certified, transform);

    assertRect(0, 0, 1, 4, measuredLayout);
    assertRect(0, 0, 0, 4, legacy);
    assertNotEquals(measuredLayout.framebufferRect(), legacy.framebufferRect());
    assertNotEquals(measuredLayout, legacy);
  }

  @Test
  @DisplayName("raw 路径：framebuffer 尺寸为 Integer.MAX_VALUE 时饱和截断且加法不回绕")
  void integerMaxFramebufferBoundsSaturateWithoutOverflow() {
    ScaleTransform maxTransform =
        new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, Integer.MAX_VALUE, Integer.MAX_VALUE);
    LogicalRect huge = new LogicalRect(-1.0E300D, -1.0E300D, 2.0E300D, 2.0E300D);
    FramebufferRect saturated = measured(1L, huge, maxTransform).framebufferRect();
    assertRect(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, saturated);
    assertEquals(Integer.MAX_VALUE, saturated.width());
    assertEquals(Integer.MAX_VALUE, saturated.height());
    assertEquals(Integer.MAX_VALUE, saturated.right());
    assertEquals(Integer.MAX_VALUE, saturated.bottom());

    LogicalRect beyondRight = new LogicalRect(1.0E18D, 1.0E18D, 1.0E18D, 1.0E18D);
    assertRect(FB_WIDTH, FB_HEIGHT, 0, 0, measured(2L, beyondRight, identityScale()));
  }

  @Test
  @DisplayName("raw 路径：clamp 先于 int cast，极大有限 edge 不会整数溢出")
  void rawEdgesAreClampedBeforeIntegerCast() {
    assertRect(FB_WIDTH, FB_HEIGHT, 0, 0,
        measured(1L, new LogicalRect(1.0E18D, 1.0E18D, 1.0E18D, 1.0E18D), identityScale()));
    assertRect(0, 0, FB_WIDTH, FB_HEIGHT,
        measured(2L, new LogicalRect(0.0D, 0.0D, 1.0E18D, 1.0E18D), identityScale()));
    assertRect(0, 5, 0, 3,
        measured(3L, new LogicalRect(-1.0E18D, 5.0D, 1.0E18D, 3.0D), identityScale()));
  }

  @Test
  @DisplayName("raw 路径：framebuffer 边界上的 edge 为左/上含、右/下开")
  void rawBoundsAtFramebufferEdgesAreInclusive() {
    assertRect(1279, 719, 1, 1,
        measured(1L, new LogicalRect(1279.0D, 719.0D, 1.0D, 1.0D), identityScale()));
    assertRect(0, 0, FB_WIDTH, FB_HEIGHT,
        measured(2L, new LogicalRect(0.0D, 0.0D, 1280.0D, 720.0D), identityScale()));
    assertRect(FB_WIDTH, FB_HEIGHT, 0, 0,
        measured(3L, new LogicalRect(1280.0D, 720.0D, 0.0D, 0.0D), identityScale()));

    FramebufferRect edge = measured(4L, new LogicalRect(0.0D, 0.0D, 1280.0D, 720.0D), identityScale())
        .framebufferRect();
    assertTrue(edge.contains(0.0D, 0.0D));
    assertTrue(edge.contains(1279.0D, 719.0D));
    assertFalse(edge.contains(1280.0D, 719.0D));
    assertFalse(edge.contains(0.0D, 720.0D));
  }

  @Test
  @DisplayName("raw 路径：clamp 上界取 scaleTransform 自带的 framebuffer 尺寸，不写死 1280x720")
  void rawPathClampsToTransformFramebufferDimensions() {
    ScaleTransform small = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 320, 200);
    assertRect(10, 20, 310, 180, measured(1L, new LogicalRect(10.5D, 20.5D, 400.0D, 300.0D), small));
    FramebufferRect clamped = measured(1L, new LogicalRect(10.5D, 20.5D, 400.0D, 300.0D), small).framebufferRect();
    assertEquals(320, clamped.right());
    assertEquals(200, clamped.bottom());
    assertRect(320, 200, 0, 0, measured(2L, new LogicalRect(400.0D, 300.0D, 1.0D, 1.0D), small));
    assertRect(0, 0, 0, 0, measured(3L, new LogicalRect(-5.0D, -5.0D, 3.0D, 3.0D), small));
  }

  @Test
  @DisplayName("同一 raw 在不同 scale/origin 下像素矩形完全相同，而 logicalRect 两两不同")
  void sameRawAcrossTransformsKeepsPixelsButDiffersInLogical() {
    LogicalRect raw = new LogicalRect(1.0D, 1.0D, 4.0D, 4.0D);
    ViewportLayout density49 = measured(1L, raw, scale(49.0D, 49.0D, 0.0D, 0.0D));
    ViewportLayout density1p1f = measured(2L, raw, scale((double) 1.1F, (double) 1.1F, 29.0D, 29.0D));
    ViewportLayout shifted = measured(3L, raw, scale(2.0D, 3.0D, 7.25D, 50.5D));

    assertEquals(density49.framebufferRect(), density1p1f.framebufferRect());
    assertEquals(density1p1f.framebufferRect(), shifted.framebufferRect());
    // R18.1a2c 第 3 条：三个成功布局都真实消费 framebufferRect()（raw 四边即 (1,1,4,4)）。
    assertRect(1, 1, 4, 4, density49.framebufferRect(), "density49=" + density49);
    assertRect(1, 1, 4, 4, density1p1f.framebufferRect(), "density1p1f=" + density1p1f);
    assertRect(1, 1, 4, 4, shifted);
    assertNotEquals(density49.logicalRect(), density1p1f.logicalRect());
    assertNotEquals(density1p1f.logicalRect(), shifted.logicalRect());
    assertNotEquals(density49.logicalRect(), shifted.logicalRect());
    assertNotEquals(density49, shifted);
  }

  @Test
  @DisplayName("测量路径是旧路径的严格扩展：raw 恰等于映射像素矩形时两者像素结果一致")
  void measuredMatchesLegacyWhenRawEqualsMappedPixelRect() {
    ScaleTransform identity = identityScale();
    LogicalRect logical = new LogicalRect(10.0D, 20.0D, 100.0D, 50.0D);
    LogicalRect raw = new LogicalRect(10.0D, 20.0D, 100.0D, 50.0D);

    ViewportLayout measuredLayout = new ViewportLayout(1L, logical, identity, Optional.of(raw));
    ViewportLayout legacy = new ViewportLayout(1L, logical, identity);

    assertRect(10, 20, 100, 50, legacy);
    assertRect(10, 20, 100, 50, measuredLayout);
    assertEquals(legacy.framebufferRect(), measuredLayout.framebufferRect());
    assertEquals(logical, measuredLayout.logicalRect());
    assertNotEquals(legacy, measuredLayout);
    assertTrue(measuredLayout.measuredFramebufferBounds().isPresent());
    assertTrue(legacy.measuredFramebufferBounds().isEmpty());
  }

  @Test
  @DisplayName("present：raw 变化但 clamp 后矩形相同时 layout 仍不等值（Optional 参与 record 等值）")
  void rawChangeWithSameClampedRectBreaksLayoutEquality() {
    ScaleTransform transform = identityScale();
    LogicalRect rawA = new LogicalRect(10.25D, 20.5D, 5.0D, 5.0D);
    LogicalRect rawB = new LogicalRect(10.5D, 20.25D, 5.5D, 5.25D);

    ViewportLayout layoutA = measured(1L, rawA, transform);
    ViewportLayout layoutB = measured(1L, rawB, transform);
    ViewportLayout layoutACopy = measured(1L, new LogicalRect(10.25D, 20.5D, 5.0D, 5.0D), transform);

    assertRect(10, 20, 6, 6, layoutA);
    assertRect(10, 20, 6, 6, layoutB);
    assertEquals(layoutA.framebufferRect(), layoutB.framebufferRect());
    assertNotEquals(layoutA, layoutB);
    assertEquals(layoutA, layoutACopy);
    assertEquals(layoutA.hashCode(), layoutACopy.hashCode());
    assertRect(10, 20, 6, 6, layoutACopy.framebufferRect(), "layoutACopy=" + layoutACopy);

    // R18.1a2c 第 2 条：Optional 内部测量值被隔离核对——两 raw 的 measured 值不等，
    // 而 generation/logical/scale/FB 四项全部相等，layout 必须不等值。
    ScaleTransform tinyScale = new ScaleTransform(4.0D, 4.0D, 0.0D, 0.0D, 128, 128);
    LogicalRect rawOneMin = new LogicalRect(0.0D, 0.0D, Double.MIN_VALUE, Double.MIN_VALUE);
    LogicalRect rawTwoMin = new LogicalRect(0.0D, 0.0D, 2.0D * Double.MIN_VALUE, 2.0D * Double.MIN_VALUE);
    assertNotEquals(rawOneMin, rawTwoMin, "两 raw 的 measured 值必须不等");

    // 独立事实：逆公式给出 +0.0 逻辑矩形（width 下溢到 +0.0，非 -0.0）。
    LogicalRect certifiedOneMin = inverse(rawOneMin, tinyScale);
    LogicalRect certifiedTwoMin = inverse(rawTwoMin, tinyScale);
    assertEquals(new LogicalRect(0.0D, 0.0D, 0.0D, 0.0D), certifiedOneMin);
    assertEquals(new LogicalRect(0.0D, 0.0D, 0.0D, 0.0D), certifiedTwoMin);
    assertEquals(0L, Double.doubleToRawLongBits(certifiedOneMin.width()));
    assertEquals(0L, Double.doubleToRawLongBits(certifiedTwoMin.width()));

    // 独立事实：两 raw 的像素结果都是 (0,0,1,1)（raw 跨度非零，ceil 出 1px）。
    int[] oracleOneMin = rawPixelOracle(rawOneMin, tinyScale);
    int[] oracleTwoMin = rawPixelOracle(rawTwoMin, tinyScale);
    assertEquals(0, oracleOneMin[0]);
    assertEquals(0, oracleOneMin[1]);
    assertEquals(1, oracleOneMin[2]);
    assertEquals(1, oracleOneMin[3]);
    assertEquals(0, oracleTwoMin[0]);
    assertEquals(0, oracleTwoMin[1]);
    assertEquals(1, oracleTwoMin[2]);
    assertEquals(1, oracleTwoMin[3]);

    ViewportLayout oneMin = new ViewportLayout(9L, certifiedOneMin, tinyScale, Optional.of(rawOneMin));
    ViewportLayout twoMin = new ViewportLayout(9L, certifiedTwoMin, tinyScale, Optional.of(rawTwoMin));

    assertEquals(oneMin.layoutGeneration(), twoMin.layoutGeneration());
    assertEquals(oneMin.logicalRect(), twoMin.logicalRect());
    assertEquals(oneMin.scaleTransform(), twoMin.scaleTransform());
    assertRect(0, 0, 1, 1, oneMin);
    assertRect(0, 0, 1, 1, twoMin);
    assertEquals(oneMin.framebufferRect(), twoMin.framebufferRect());
    assertNotEquals(oneMin.measuredFramebufferBounds().get(), twoMin.measuredFramebufferBounds().get());
    assertNotEquals(oneMin, twoMin, "四组件其余全等但 measured 值不等时 layout 必须不等");
  }

  @Test
  @DisplayName("两条路径共用同一零轴规则：空轴标志为真时 max edge 取 floor(min edge)")
  void bothPathsShareSingleZeroAxisRule() {
    ScaleTransform identity = identityScale();

    ViewportLayout emptyWidthLogical =
        new ViewportLayout(1L, new LogicalRect(10.5D, 3.25D, 0.0D, 4.0D), identity);
    ViewportLayout emptyWidthRaw = measured(1L, new LogicalRect(10.5D, 3.25D, 0.0D, 4.0D), identity);
    assertEquals(emptyWidthLogical.framebufferRect(), emptyWidthRaw.framebufferRect());
    assertRect(10, 3, 0, 5, emptyWidthRaw);
    // R18.1a2c 第 3 条：empty 侧成功布局同样真实消费 framebufferRect()。
    assertRect(10, 3, 0, 5, emptyWidthLogical.framebufferRect(), "emptyWidthLogical=" + emptyWidthLogical);

    ViewportLayout emptyHeightLogical =
        new ViewportLayout(2L, new LogicalRect(10.5D, 3.25D, 4.0D, 0.0D), identity);
    ViewportLayout emptyHeightRaw = measured(2L, new LogicalRect(10.5D, 3.25D, 4.0D, 0.0D), identity);
    assertEquals(emptyHeightLogical.framebufferRect(), emptyHeightRaw.framebufferRect());
    assertRect(10, 3, 5, 0, emptyHeightRaw);
    assertRect(10, 3, 5, 0, emptyHeightLogical.framebufferRect(), "emptyHeightLogical=" + emptyHeightLogical);

    ViewportLayout bothLogical = new ViewportLayout(3L, new LogicalRect(10.5D, 3.25D, 0.0D, 0.0D), identity);
    ViewportLayout bothRaw = measured(3L, new LogicalRect(10.5D, 3.25D, 0.0D, 0.0D), identity);
    assertEquals(bothLogical.framebufferRect(), bothRaw.framebufferRect());
    assertRect(10, 3, 0, 0, bothRaw);
    assertRect(10, 3, 0, 0, bothLogical.framebufferRect(), "bothLogical=" + bothLogical);
  }

  // ------------------------------------------------------------------ empty 旧路径逐字保持

  @Test
  @DisplayName("empty 旧路径：既有无交点/负原点/零轴向量逐字保持")
  void legacyEmptyPathVectorsUnchanged() {
    assertRect(10, 20, 100, 50,
        new ViewportLayout(1L, new LogicalRect(10.0D, 20.0D, 100.0D, 50.0D), identityScale()));
    assertRect(10, 20, 0, 50,
        new ViewportLayout(2L, new LogicalRect(10.5D, 20.0D, 0.0D, 50.0D), identityScale()));
    assertRect(FB_WIDTH, 0, 0, 10,
        new ViewportLayout(3L, new LogicalRect(1281.0D, 0.0D, 10.0D, 10.0D), identityScale()));
    assertRect(0, 0, 120, 0,
        new ViewportLayout(4L, new LogicalRect(10.0D, 20.0D, 100.0D, 50.0D),
            scale(2.0D, 2.0D, -100.0D, -200.0D)));
    assertRect(0, 0, 0, 0,
        new ViewportLayout(5L, new LogicalRect(-5000.5D, -100.0D, 0.0D, 0.0D), identityScale()));
  }

  @Test
  @DisplayName("empty 旧路径：映射非有限时 framebufferRect 抛 IllegalArgumentException")
  void emptyPathStillThrowsOnNonFiniteMapping() {
    ScaleTransform overflowScale = scale(2.0D, 2.0D, 0.0D, 0.0D);
    LogicalRect huge = new LogicalRect(Double.MAX_VALUE, 0.0D, 0.0D, 0.0D);
    ViewportLayout legacy = new ViewportLayout(1L, huge, overflowScale);
    assertThrows(IllegalArgumentException.class, legacy::framebufferRect);

    ViewportLayout emptyExplicit = new ViewportLayout(1L, huge, overflowScale, Optional.empty());
    assertThrows(IllegalArgumentException.class, emptyExplicit::framebufferRect);
  }

  // ------------------------------------------------------------------ 1000+ 独立 oracle 对照

  @Test
  @DisplayName("1000+ 固定 seed raw 候选：与独立 BigDecimal raw oracle 四整数完全一致")
  void thousandRawCandidatesMatchIndependentRawOracle() {
    Random random = new Random(20261015L);
    ScaleTransform[] transforms = {
        identityScale(),
        cardExampleScale(),
        scale(49.0D, 49.0D, 0.0D, 0.0D),
        scale((double) 1.1F, (double) 1.1F, 29.0D, 29.0D),
        scale(2.0D, 1.0D, 0.0D, 0.0D),
        scale(0.25D, 0.125D, 1280.0D, 720.0D),
        scale(3.0D, 7.0D, -40.0D, 60.0D),
        scale(Double.MIN_VALUE, Double.MIN_VALUE, 0.0D, 0.0D),
    };
    LogicalRect[] specials = {
        new LogicalRect(0.0D, 0.0D, Double.MIN_VALUE, 4.0D),
        new LogicalRect(0.0D, 0.0D, 0.0D, 0.0D),
        new LogicalRect(-0.5D, 3.25D, 0.0D, 10.0D),
        new LogicalRect(1280.5D, 720.5D, 5.0D, 5.0D),
        new LogicalRect(-5000.0D, -100.0D, 10.0D, 10.0D),
        new LogicalRect(1.0D, 2.0D, 1.0E300D, 3.0D),
        new LogicalRect(-2.5D, 5.5D, 0.0D, 0.0D),
        new LogicalRect(10.25D, 20.5D, 5.0D, 5.0D),
        new LogicalRect(1279.0D, 719.0D, 1.0D, 1.0D),
        new LogicalRect(0.0D, 0.0D, 1280.0D, 720.0D),
    };

    Set<String> distinctCandidates = new HashSet<>();
    boolean[] coverage = new boolean[3];
    int compared = 0;
    int inverseRejected = 0;

    for (LogicalRect raw : specials) {
      for (ScaleTransform transform : transforms) {
        int outcome = compareRawCandidate(raw, transform, distinctCandidates, coverage);
        if (outcome == OUTCOME_COMPARED) {
          compared++;
        } else {
          inverseRejected++;
        }
      }
    }
    while (compared < 1400) {
      ScaleTransform transform = transforms[random.nextInt(transforms.length)];
      int outcome = compareRawCandidate(randomRawCandidate(random), transform, distinctCandidates, coverage);
      if (outcome == OUTCOME_COMPARED) {
        compared++;
      } else {
        inverseRejected++;
      }
    }

    System.out.println("RAW_ORACLE_EVIDENCE compared=" + compared + " inverseRejected=" + inverseRejected
        + " distinctCandidates=" + distinctCandidates.size() + " inside=" + coverage[0] + " outside="
        + coverage[1] + " zeroAxis=" + coverage[2]);
    assertTrue(inverseRejected > 0, "应至少覆盖一次逆公式非有限拒绝");
    assertTrue(compared >= 1000, "raw 对照样本不足: " + compared);
    assertTrue(distinctCandidates.size() >= 1000, "raw 候选不够互异: " + distinctCandidates.size());
    assertTrue(coverage[0], "候选必须含窗口内样本");
    assertTrue(coverage[1], "候选必须含窗口外样本");
    assertTrue(coverage[2], "候选必须含零轴样本");
  }

  @Test
  @DisplayName("1000+ 固定 seed old(empty) 输入：与独立 BigDecimal 原 oracle 完全一致")
  void thousandLegacyEmptyInputsMatchIndependentOriginalOracle() {
    Random random = new Random(20261016L);
    ScaleTransform[] transforms = {
        identityScale(),
        cardExampleScale(),
        scale(0.5D, 0.5D, -100.0D, -200.0D),
        scale(2.0D, 3.0D, 7.25D, 50.5D),
        scale(0.25D, 0.125D, 1280.0D, 720.0D),
        scale(1.5D, 2.0D, -3.75D, 11.5D),
        scale(2.0D, 2.0D, 0.0D, 0.0D),
    };

    Set<String> distinctInputs = new HashSet<>();
    int samples = 0;
    int overflowAgreed = 0;
    boolean sawZeroAxis = false;

    for (int index = 0; index < 4000 && samples < 1400; index++) {
      LogicalRect logical;
      ScaleTransform transform;
      if (index % 7 == 0) {
        // 确定性覆盖映射上溢：MAX * 2 => +Infinity，实现与 oracle 必须一致地抛 IAE。
        logical = new LogicalRect(Double.MAX_VALUE, 0.0D, 0.0D, 0.0D);
        transform = scale(2.0D, 2.0D, 0.0D, 0.0D);
      } else {
        logical = new LogicalRect(pickCoordinate(random), pickCoordinate(random), pickSize(random),
            pickSize(random));
        transform = transforms[random.nextInt(transforms.length)];
      }
      distinctInputs.add(logical + "|" + transform);
      if (logical.width() == 0.0D || logical.height() == 0.0D) {
        sawZeroAxis = true;
      }

      // R18.1a2c 第 1 条：独立 old oracle 先给 expected/oracleThrew，再构造并消费生产布局。
      boolean oracleThrew = false;
      FramebufferRect expected = null;
      try {
        expected = emptyPathOracle(logical, transform);
      } catch (IllegalArgumentException expectedFailure) {
        oracleThrew = true;
      }

      ViewportLayout layout = new ViewportLayout(1L, logical, transform);
      boolean implementationThrew = false;
      FramebufferRect actual = null;
      try {
        actual = layout.framebufferRect();
      } catch (IllegalArgumentException expectedFailure) {
        implementationThrew = true;
      }

      String message = "logical=" + logical + " transform=" + transform;
      assertEquals(oracleThrew, implementationThrew, "非有限一致性不匹配: " + message);
      if (implementationThrew) {
        overflowAgreed++;
        continue;
      }
      assertEquals(expected, actual, message);
      samples++;
    }

    System.out.println("OLD_ORACLE_EVIDENCE samples=" + samples + " overflowAgreed=" + overflowAgreed
        + " distinctInputs=" + distinctInputs.size() + " sawZeroAxis=" + sawZeroAxis);
    assertTrue(samples >= 1000, "old(empty) 对照样本不足: " + samples);
    assertTrue(overflowAgreed > 0, "应至少覆盖一次非有限映射一致拒绝");
    assertTrue(distinctInputs.size() >= 1000, "old(empty) 输入不够互异: " + distinctInputs.size());
    assertTrue(sawZeroAxis, "old(empty) 候选必须含零轴样本");
  }

  // ------------------------------------------------------------------ 独立 oracle 与工具

  /**
   * 单个 raw 候选对照：<strong>先</strong>由独立 raw oracle 给出 expected，再按唯一逆公式构造、消费生产布局并逐整数
   * 比对。返回是否计入样本。
   *
   * <p>逆公式非有限时必须由生产构造抛 IAE（逆公式由本文件独立复写，未调用生产）。
   */
  private static int compareRawCandidate(LogicalRect raw, ScaleTransform transform, Set<String> distinct,
      boolean[] coverage) {
    distinct.add(raw + "|" + transform);
    // R18.1a2c 第 1 条：expected 必须先于任何 ViewportLayout 构造给出，且不得由生产结果推导。
    int[] expected = rawPixelOracle(raw, transform);
    LogicalRect certified;
    try {
      certified = inverse(raw, transform);
    } catch (IllegalArgumentException nonFiniteInverse) {
      assertThrows(IllegalArgumentException.class,
          () -> new ViewportLayout(1L, ZERO_RECT, transform, Optional.of(raw)));
      return OUTCOME_INVERSE_REJECTED;
    }

    // 逆公式构造出的 logical 必须总能通过认证（否则此处直接以异常暴露缺陷）。
    ViewportLayout layout = new ViewportLayout(1L, certified, transform, Optional.of(raw));
    FramebufferRect actual = layout.framebufferRect();
    String message = "raw=" + raw + " transform=" + transform;
    assertEquals(expected[0], actual.x(), message);
    assertEquals(expected[1], actual.y(), message);
    assertEquals(expected[2], actual.width(), message);
    assertEquals(expected[3], actual.height(), message);
    assertEquals(expected[2], actual.right() - actual.x(), message);
    assertEquals(expected[3], actual.bottom() - actual.y(), message);

    if (raw.width() == 0.0D || raw.height() == 0.0D) {
      coverage[2] = true;
    }
    if (raw.left() >= 0.0D && raw.top() >= 0.0D && raw.right() <= transform.framebufferWidth()
        && raw.bottom() <= transform.framebufferHeight()) {
      coverage[0] = true;
    }
    if (raw.right() < 0.0D || raw.bottom() < 0.0D || raw.left() > transform.framebufferWidth()
        || raw.top() > transform.framebufferHeight()) {
      coverage[1] = true;
    }
    return OUTCOME_COMPARED;
  }

  private static LogicalRect randomRawCandidate(Random random) {
    return new LogicalRect(pickCoordinate(random), pickCoordinate(random), pickSize(random), pickSize(random));
  }

  private static double pickCoordinate(Random random) {
    double[] pool = {-5000.5D, -1280.0D, -1.0D, -0.5D, 0.0D, 0.25D, 1.0D, 10.25D, 639.75D, 1280.0D,
        1280.5D, 2000.0D, 1.0E18D, -1.0E18D, 1.0E300D};
    return pool[random.nextInt(pool.length)];
  }

  private static double pickSize(Random random) {
    double[] pool = {0.0D, Double.MIN_VALUE, 0.5D, 1.0D, 50.1D, 100.1D, 639.5D, 1280.0D, 1.0E300D, 3.0D};
    return pool[random.nextInt(pool.length)];
  }

  /** 字面量期望 + 独立 oracle 双重核对（用于不便手算的 ulp 级边界）；expected 先于构造给出。 */
  private static void assertMatchesRawOracle(long generation, LogicalRect raw, ScaleTransform transform) {
    int[] expected = rawPixelOracle(raw, transform);
    ViewportLayout layout = new ViewportLayout(generation, inverse(raw, transform), transform, Optional.of(raw));
    FramebufferRect actual = layout.framebufferRect();
    assertEquals(expected[0], actual.x(), "raw=" + raw);
    assertEquals(expected[1], actual.y(), "raw=" + raw);
    assertEquals(expected[2], actual.width(), "raw=" + raw);
    assertEquals(expected[3], actual.height(), "raw=" + raw);
  }

  /**
   * 唯一逆公式：本文件独立复写（与生产同一算式，用于给生产同一 given）。必须逐位等于生产认证时的 expected。
   */
  private static LogicalRect inverse(LogicalRect raw, ScaleTransform transform) {
    return new LogicalRect(
        (raw.left() - transform.pixelOriginX()) / transform.xScale(),
        (raw.top() - transform.pixelOriginY()) / transform.yScale(),
        raw.width() / transform.xScale(),
        raw.height() / transform.yScale());
  }

  private static ViewportLayout measured(long generation, LogicalRect raw, ScaleTransform transform) {
    return new ViewportLayout(generation, inverse(raw, transform), transform, Optional.of(raw));
  }

  /** 独立 raw oracle：只用 raw 四边界与 framebuffer 尺寸的精确十进制量算 expected，不调用生产任何方法。 */
  private static int[] rawPixelOracle(LogicalRect raw, ScaleTransform transform) {
    BigDecimal left = clampExact(BigDecimal.valueOf(raw.left()), transform.framebufferWidth());
    BigDecimal right = clampExact(BigDecimal.valueOf(raw.right()), transform.framebufferWidth());
    BigDecimal top = clampExact(BigDecimal.valueOf(raw.top()), transform.framebufferHeight());
    BigDecimal bottom = clampExact(BigDecimal.valueOf(raw.bottom()), transform.framebufferHeight());

    int x = left.min(right).setScale(0, RoundingMode.FLOOR).intValueExact();
    int y = top.min(bottom).setScale(0, RoundingMode.FLOOR).intValueExact();
    int rightEdge = left.max(right).setScale(0, RoundingMode.CEILING).intValueExact();
    int bottomEdge = top.max(bottom).setScale(0, RoundingMode.CEILING).intValueExact();

    if (raw.width() == 0.0D) {
      rightEdge = x;
    }
    if (raw.height() == 0.0D) {
      bottomEdge = y;
    }
    return new int[] {x, y, rightEdge - x, bottomEdge - y};
  }

  /** 独立 old(empty) oracle：映射用已冻结的 ScaleTransform，clamp/取整/零轴用 BigDecimal 精确实现。 */
  private static FramebufferRect emptyPathOracle(LogicalRect logical, ScaleTransform transform) {
    BigDecimal left = clampExact(BigDecimal.valueOf(transform.toFramebufferX(logical.left())),
        transform.framebufferWidth());
    BigDecimal right = clampExact(BigDecimal.valueOf(transform.toFramebufferX(logical.right())),
        transform.framebufferWidth());
    BigDecimal top = clampExact(BigDecimal.valueOf(transform.toFramebufferY(logical.top())),
        transform.framebufferHeight());
    BigDecimal bottom = clampExact(BigDecimal.valueOf(transform.toFramebufferY(logical.bottom())),
        transform.framebufferHeight());

    int x = left.min(right).setScale(0, RoundingMode.FLOOR).intValueExact();
    int y = top.min(bottom).setScale(0, RoundingMode.FLOOR).intValueExact();
    int rightEdge = left.max(right).setScale(0, RoundingMode.CEILING).intValueExact();
    int bottomEdge = top.max(bottom).setScale(0, RoundingMode.CEILING).intValueExact();

    if (logical.width() == 0.0D) {
      rightEdge = x;
    }
    if (logical.height() == 0.0D) {
      bottomEdge = y;
    }
    return new FramebufferRect(x, y, rightEdge - x, bottomEdge - y);
  }

  private static BigDecimal clampExact(BigDecimal edge, int upperBoundInclusive) {
    if (edge.signum() < 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal upper = BigDecimal.valueOf(upperBoundInclusive);
    if (edge.compareTo(upper) > 0) {
      return upper;
    }
    return edge;
  }

  private static ScaleTransform identityScale() {
    return new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, FB_WIDTH, FB_HEIGHT);
  }

  private static ScaleTransform cardExampleScale() {
    return new ScaleTransform(2.0D, 3.0D, 7.25D, 50.5D, FB_WIDTH, FB_HEIGHT);
  }

  private static ScaleTransform scale(double xScale, double yScale, double originX, double originY) {
    return new ScaleTransform(xScale, yScale, originX, originY, FB_WIDTH, FB_HEIGHT);
  }

  private static void assertRect(int x, int y, int width, int height, ViewportLayout layout) {
    assertRect(x, y, width, height, layout.framebufferRect(), "layout=" + layout);
  }

  private static void assertRect(int x, int y, int width, int height, FramebufferRect actual) {
    assertRect(x, y, width, height, actual, "framebufferRect=" + actual);
  }

  private static void assertRect(int x, int y, int width, int height, FramebufferRect actual, String message) {
    assertEquals(x, actual.x(), message);
    assertEquals(y, actual.y(), message);
    assertEquals(width, actual.width(), message);
    assertEquals(height, actual.height(), message);
    assertEquals(x + width, actual.right(), message);
    assertEquals(y + height, actual.bottom(), message);
  }
}
