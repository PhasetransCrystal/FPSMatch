package net.ptcrys.fpsmatch.editor.common.client.viewport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R18.1a 视窗布局与物理矩形契约测试（作者侧）。
 *
 * <p>被测类型：{@link LogicalRect}、{@link FramebufferRect}、{@link ViewportLayout}；缩放一律使用已 P04-SCALE
 * PASS 并冻结的 {@link ScaleTransform}，本测试<strong>不</strong>复制或重定义任何 scale 类型。
 */
class ViewportLayoutContractTest {

  private static final int FB_WIDTH = 1280;
  private static final int FB_HEIGHT = 720;

  /** 卡片给定的样例 transform：scale(1.5, 2, 0, 0, 1280, 720)。 */
  private static ScaleTransform scale(double xScale, double yScale, double originX, double originY) {
    return new ScaleTransform(xScale, yScale, originX, originY, FB_WIDTH, FB_HEIGHT);
  }

  private static ScaleTransform identityScale() {
    return scale(1.0D, 1.0D, 0.0D, 0.0D);
  }

  private static ScaleTransform cardExampleScale() {
    return scale(1.5D, 2.0D, 0.0D, 0.0D);
  }

  // ------------------------------------------------------------------ LogicalRect：构造与校验

  @Test
  @DisplayName("LogicalRect 接受负 left/top 与零宽高，不 normalize 不 clamp")
  void logicalRectAcceptsNegativeOriginAndZeroDimension() {
    LogicalRect negative = new LogicalRect(-500.5D, -100.25D, 50.0D, 25.0D);
    assertEquals(-500.5D, negative.left());
    assertEquals(-100.25D, negative.top());
    assertEquals(50.0D, negative.width());
    assertEquals(25.0D, negative.height());

    LogicalRect zeroWidth = new LogicalRect(10.0D, 20.0D, 0.0D, 5.0D);
    assertEquals(0.0D, zeroWidth.width());
    assertTrue(zeroWidth.isEmpty());

    // 负宽高不静默翻转为正。
    assertThrows(IllegalArgumentException.class, () -> new LogicalRect(0.0D, 0.0D, -1.0D, 5.0D));
    assertThrows(IllegalArgumentException.class, () -> new LogicalRect(0.0D, 0.0D, 5.0D, -1.0D));
  }

  @Test
  @DisplayName("LogicalRect 拒绝四组件的 NaN/Infinity")
  void logicalRectRejectsNonFiniteComponents() {
    double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (double v : bad) {
      assertThrows(IllegalArgumentException.class, () -> new LogicalRect(v, 0.0D, 1.0D, 1.0D), "left=" + v);
      assertThrows(IllegalArgumentException.class, () -> new LogicalRect(0.0D, v, 1.0D, 1.0D), "top=" + v);
      assertThrows(IllegalArgumentException.class, () -> new LogicalRect(0.0D, 0.0D, v, 1.0D), "width=" + v);
      assertThrows(IllegalArgumentException.class, () -> new LogicalRect(0.0D, 0.0D, 1.0D, v), "height=" + v);
    }
  }

  @Test
  @DisplayName("LogicalRect 拒绝 right/bottom 溢出成 Infinity 的组合")
  void logicalRectRejectsRightOrBottomOverflowToInfinity() {
    double huge = Double.MAX_VALUE;
    assertThrows(IllegalArgumentException.class, () -> new LogicalRect(huge, 0.0D, huge, 1.0D));
    assertThrows(IllegalArgumentException.class, () -> new LogicalRect(0.0D, huge, 1.0D, huge));
    // 恰好到 MAX 仍有限，必须被接受。
    LogicalRect maximal = new LogicalRect(huge / 2.0D, huge / 2.0D, huge / 2.0D, huge / 2.0D);
    assertEquals(huge, maximal.right());
    assertEquals(huge, maximal.bottom());
  }

  @Test
  @DisplayName("LogicalRect right/bottom 是精确 left+width、top+height，不减 1")
  void logicalRectRightBottomAreExactAndNotMinusOne() {
    LogicalRect rect = new LogicalRect(10.2D, 20.2D, 100.1D, 50.1D);
    assertEquals(rect.left() + rect.width(), rect.right(), 0.0D);
    assertEquals(rect.top() + rect.height(), rect.bottom(), 0.0D);
    // 明确区别于“右下含”的减一公式。
    assertFalse(rect.right() == rect.left() + rect.width() - 1.0D);
    assertFalse(rect.bottom() == rect.top() + rect.height() - 1.0D);
    // 零宽零高时 right==left、bottom==top。
    LogicalRect degenerate = new LogicalRect(3.5D, 4.5D, 0.0D, 0.0D);
    assertEquals(3.5D, degenerate.right(), 0.0D);
    assertEquals(4.5D, degenerate.bottom(), 0.0D);
  }

  @Test
  @DisplayName("LogicalRect isEmpty 为任一维度 == 0；极窄非零矩形不算空")
  void logicalRectIsEmptyOnEitherZeroDimensionAndNarrowRectIsNotEmpty() {
    assertTrue(new LogicalRect(0.0D, 0.0D, 0.0D, 10.0D).isEmpty());
    assertTrue(new LogicalRect(0.0D, 0.0D, 10.0D, 0.0D).isEmpty());
    assertTrue(new LogicalRect(0.0D, 0.0D, 0.0D, 0.0D).isEmpty());
    assertFalse(new LogicalRect(0.0D, 0.0D, Double.MIN_VALUE, 10.0D).isEmpty());
    assertFalse(new LogicalRect(0.0D, 0.0D, 0.25D, 0.25D).isEmpty());
  }

  @Test
  @DisplayName("LogicalRect contains：左/上含、右/下不含；空矩形恒 false")
  void logicalRectContainsFollowsHalfOpenSemantics() {
    LogicalRect rect = new LogicalRect(10.0D, 20.0D, 100.0D, 50.0D);
    assertTrue(rect.contains(10.0D, 20.0D));
    assertTrue(rect.contains(109.999999D, 69.999999D));
    assertTrue(rect.contains(50.0D, 40.0D));
    assertFalse(rect.contains(9.999999D, 40.0D));
    assertFalse(rect.contains(40.0D, 19.999999D));
    assertFalse(rect.contains(110.0D, 40.0D));
    assertFalse(rect.contains(40.0D, 70.0D));
    // 负原点矩形。
    LogicalRect negative = new LogicalRect(-100.0D, -50.0D, 200.0D, 100.0D);
    assertTrue(negative.contains(-100.0D, -50.0D));
    assertFalse(negative.contains(100.0D, 0.0D));
    // 空矩形恒 false，即使点恰在退化坐标上。
    LogicalRect empty = new LogicalRect(5.0D, 5.0D, 0.0D, 10.0D);
    assertFalse(empty.contains(5.0D, 6.0D));
    assertFalse(empty.contains(5.0D, 5.0D));
  }

  @Test
  @DisplayName("LogicalRect contains 对 NaN/Infinity 点参数抛 IAE，即使矩形为空")
  void logicalRectContainsRejectsNonFinitePoints() {
    LogicalRect rect = new LogicalRect(0.0D, 0.0D, 10.0D, 10.0D);
    LogicalRect empty = new LogicalRect(0.0D, 0.0D, 0.0D, 10.0D);
    for (double v : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> rect.contains(v, 1.0D));
      assertThrows(IllegalArgumentException.class, () -> rect.contains(1.0D, v));
      assertThrows(IllegalArgumentException.class, () -> empty.contains(v, 1.0D));
      assertThrows(IllegalArgumentException.class, () -> empty.contains(1.0D, v));
    }
  }

  // ------------------------------------------------------------------ FramebufferRect：构造与校验

  @Test
  @DisplayName("FramebufferRect 接受零宽高与非负坐标")
  void framebufferRectAcceptsZeroExtentAndNonNegativeOrigin() {
    FramebufferRect zero = new FramebufferRect(0, 0, 0, 0);
    assertTrue(zero.isEmpty());
    assertEquals(0, zero.right());
    assertEquals(0, zero.bottom());

    FramebufferRect edge = new FramebufferRect(FB_WIDTH, FB_HEIGHT, 0, 0);
    assertEquals(FB_WIDTH, edge.x());
    assertEquals(FB_HEIGHT, edge.y());
    assertTrue(edge.isEmpty());
  }

  @Test
  @DisplayName("FramebufferRect 拒绝负组件")
  void framebufferRectRejectsNegativeComponents() {
    assertThrows(IllegalArgumentException.class, () -> new FramebufferRect(-1, 0, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> new FramebufferRect(0, -1, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> new FramebufferRect(0, 0, -1, 10));
    assertThrows(IllegalArgumentException.class, () -> new FramebufferRect(0, 0, 10, -1));
    assertThrows(IllegalArgumentException.class,
        () -> new FramebufferRect(Integer.MIN_VALUE, 0, 0, 0));
  }

  @Test
  @DisplayName("FramebufferRect 以 long 核 x+width/y+height，拒绝 int 回绕")
  void framebufferRectRejectsAdditiveIntOverflow() {
    assertThrows(IllegalArgumentException.class,
        () -> new FramebufferRect(Integer.MAX_VALUE, 0, 1, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new FramebufferRect(0, Integer.MAX_VALUE, 0, 1));
    assertThrows(IllegalArgumentException.class,
        () -> new FramebufferRect(Integer.MAX_VALUE - 1, 0, 2, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new FramebufferRect(0, Integer.MAX_VALUE - 1, 0, 2));
    // 恰好等于 MAX_VALUE 允许（不回绕）。
    FramebufferRect maximal = new FramebufferRect(Integer.MAX_VALUE - 5, Integer.MAX_VALUE - 5, 5, 5);
    assertEquals(Integer.MAX_VALUE, maximal.right());
    assertEquals(Integer.MAX_VALUE, maximal.bottom());
    assertFalse(maximal.right() < 0);
    assertFalse(maximal.bottom() < 0);
  }

  @Test
  @DisplayName("FramebufferRect right/bottom 精确为 x+width、y+height，不减 1")
  void framebufferRectRightBottomAreExact() {
    FramebufferRect rect = new FramebufferRect(15, 40, 151, 101);
    assertEquals(166, rect.right());
    assertEquals(141, rect.bottom());
    assertEquals(rect.x() + rect.width(), rect.right());
    assertEquals(rect.y() + rect.height(), rect.bottom());
    assertFalse(rect.right() == rect.x() + rect.width() - 1);
  }

  @Test
  @DisplayName("FramebufferRect contains：左/上含、右/下不含；含分数像素；空矩形恒 false")
  void framebufferRectContainsFollowsHalfOpenSemantics() {
    FramebufferRect rect = new FramebufferRect(15, 40, 151, 101);
    assertTrue(rect.contains(15.0D, 40.0D));
    assertTrue(rect.contains(15.5D, 40.5D));
    assertTrue(rect.contains(165.999D, 140.999D));
    assertFalse(rect.contains(14.999D, 40.0D));
    assertFalse(rect.contains(15.0D, 39.999D));
    assertFalse(rect.contains(166.0D, 40.0D));
    assertFalse(rect.contains(15.0D, 141.0D));

    FramebufferRect empty = new FramebufferRect(15, 40, 0, 101);
    assertTrue(empty.isEmpty());
    assertFalse(empty.contains(15.0D, 41.0D));
    assertFalse(empty.contains(15.5D, 41.0D));
  }

  @Test
  @DisplayName("FramebufferRect contains 对 NaN/Infinity 点参数抛 IAE")
  void framebufferRectContainsRejectsNonFinitePoints() {
    FramebufferRect rect = new FramebufferRect(0, 0, 10, 10);
    FramebufferRect empty = new FramebufferRect(0, 0, 0, 10);
    for (double v : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> rect.contains(v, 1.0D));
      assertThrows(IllegalArgumentException.class, () -> rect.contains(1.0D, v));
      assertThrows(IllegalArgumentException.class, () -> empty.contains(v, 1.0D));
      assertThrows(IllegalArgumentException.class, () -> empty.contains(1.0D, v));
    }
  }

  // ------------------------------------------------------------------ ViewportLayout：身份与卡片样例

  @Test
  @DisplayName("identity：logicalRect 映射为 x=left, y=top, width, height（整数输入）")
  void identityMappingReproducesLogicalRect() {
    ViewportLayout layout =
        new ViewportLayout(7L, new LogicalRect(100.0D, 200.0D, 300.0D, 400.0D), identityScale());
    FramebufferRect fb = layout.framebufferRect();
    assertEquals(100, fb.x());
    assertEquals(200, fb.y());
    assertEquals(300, fb.width());
    assertEquals(400, fb.height());
    assertEquals(400, fb.right());
    assertEquals(600, fb.bottom());
    assertFalse(fb.isEmpty());
  }

  @Test
  @DisplayName("卡片样例：LogicalRect(10.2,20.2,100.1,50.1) + scale(1.5,2,0,0,1280,720) → (15,40,151,101)")
  void cardExampleMapsToExactFloorCeilRect() {
    ViewportLayout layout = new ViewportLayout(
        1L, new LogicalRect(10.2D, 20.2D, 100.1D, 50.1D), cardExampleScale());
    FramebufferRect fb = layout.framebufferRect();
    assertEquals(15, fb.x());
    assertEquals(40, fb.y());
    assertEquals(151, fb.width());
    assertEquals(101, fb.height());
    assertEquals(166, fb.right());
    assertEquals(141, fb.bottom());
  }

  @Test
  @DisplayName("不同 scale 与 origin：X/Y 独立缩放、origin 平移，全部左上不翻转")
  void differentScalesAndOriginsMapPerAxis() {
    // scale X=2, Y=3, origin(10,-20)：left=5,top=5,w=10,h=10 → mapped x:20..40, y:-5..25
    ViewportLayout layout = new ViewportLayout(
        2L, new LogicalRect(5.0D, 5.0D, 10.0D, 10.0D), scale(2.0D, 3.0D, 10.0D, -20.0D));
    FramebufferRect fb = layout.framebufferRect();
    assertEquals(20, fb.x());
    assertEquals(0, fb.y());
    assertEquals(20, fb.width());
    assertEquals(25, fb.height());

    // origin 只平移 pixel 空间：同一 rect 换 origin 只改变 x/y。
    ViewportLayout shifted = new ViewportLayout(
        3L, new LogicalRect(5.0D, 5.0D, 10.0D, 10.0D), scale(2.0D, 3.0D, 11.0D, -20.0D));
    assertEquals(21, shifted.framebufferRect().x());
    assertEquals(20, shifted.framebufferRect().width());

    // 不做 Y 翻转：更大的 logical Y 必须得到更大的 pixel Y。
    ViewportLayout lower = new ViewportLayout(
        4L, new LogicalRect(0.0D, 100.0D, 10.0D, 10.0D), identityScale());
    ViewportLayout upper = new ViewportLayout(
        5L, new LogicalRect(0.0D, 10.0D, 10.0D, 10.0D), identityScale());
    assertTrue(lower.framebufferRect().y() > upper.framebufferRect().y());
  }

  // ------------------------------------------------------------------ ViewportLayout：交集与裁剪

  @Test
  @DisplayName("部分交集：四边分别越界时逐边裁到 [0,framebuffer]")
  void partialIntersectionClampsEachEdge() {
    // 左边越界（left 负）：x=0，right 保留。
    ViewportLayout leftOut = new ViewportLayout(
        1L, new LogicalRect(-50.0D, 0.0D, 150.0D, 10.0D), identityScale());
    assertEquals(0, leftOut.framebufferRect().x());
    assertEquals(100, leftOut.framebufferRect().width());

    // 上边越界：y=0。
    ViewportLayout topOut = new ViewportLayout(
        1L, new LogicalRect(0.0D, -50.0D, 10.0D, 150.0D), identityScale());
    assertEquals(0, topOut.framebufferRect().y());
    assertEquals(100, topOut.framebufferRect().height());

    // 右边越界：right 裁到 framebufferWidth。
    ViewportLayout rightOut = new ViewportLayout(
        1L, new LogicalRect(1200.0D, 0.0D, 500.0D, 10.0D), identityScale());
    assertEquals(1200, rightOut.framebufferRect().x());
    assertEquals(FB_WIDTH, rightOut.framebufferRect().right());
    assertEquals(80, rightOut.framebufferRect().width());

    // 下边越界：bottom 裁到 framebufferHeight。
    ViewportLayout bottomOut = new ViewportLayout(
        1L, new LogicalRect(0.0D, 700.0D, 10.0D, 500.0D), identityScale());
    assertEquals(700, bottomOut.framebufferRect().y());
    assertEquals(FB_HEIGHT, bottomOut.framebufferRect().bottom());
    assertEquals(20, bottomOut.framebufferRect().height());

    // 两侧同时越界：整个 framebuffer。
    ViewportLayout covering = new ViewportLayout(
        1L, new LogicalRect(-100.0D, -100.0D, FB_WIDTH + 200.0D, FB_HEIGHT + 200.0D), identityScale());
    FramebufferRect fb = covering.framebufferRect();
    assertEquals(0, fb.x());
    assertEquals(0, fb.y());
    assertEquals(FB_WIDTH, fb.width());
    assertEquals(FB_HEIGHT, fb.height());
  }

  @Test
  @DisplayName("完全在 framebuffer 外：交集为空，右/下侧得到 x=W 或 y=H 且 width/height=0")
  void fullyOutsideRectsCollapseToZeroExtent() {
    // 完全右侧：x 为 framebufferWidth，width=0。
    ViewportLayout rightOut = new ViewportLayout(
        1L, new LogicalRect(2000.0D, 0.0D, 10.0D, 10.0D), identityScale());
    assertEquals(FB_WIDTH, rightOut.framebufferRect().x());
    assertEquals(0, rightOut.framebufferRect().width());
    assertTrue(rightOut.framebufferRect().isEmpty());

    // 完全下方：y 为 framebufferHeight，height=0。
    ViewportLayout bottomOut = new ViewportLayout(
        1L, new LogicalRect(0.0D, 2000.0D, 10.0D, 10.0D), identityScale());
    assertEquals(FB_HEIGHT, bottomOut.framebufferRect().y());
    assertEquals(0, bottomOut.framebufferRect().height());

    // 完全左侧：x=0，width=0。
    ViewportLayout leftOut = new ViewportLayout(
        1L, new LogicalRect(-2000.0D, 0.0D, 10.0D, 10.0D), identityScale());
    assertEquals(0, leftOut.framebufferRect().x());
    assertEquals(0, leftOut.framebufferRect().width());

    // 完全上方：y=0，height=0。
    ViewportLayout topOut = new ViewportLayout(
        1L, new LogicalRect(0.0D, -2000.0D, 10.0D, 10.0D), identityScale());
    assertEquals(0, topOut.framebufferRect().y());
    assertEquals(0, topOut.framebufferRect().height());
  }

  @Test
  @DisplayName("零宽 + 分数 edge 不得因 ceil 生出 1px（高度同理）")
  void zeroLogicalWidthWithFractionalEdgeDoesNotBecomeOnePixel() {
    // left=10.2 → mapped 15.3；width=0 → right 同一 edge 15.3；不做特别规则会得到 ceil(15.3)=16 → 1px。
    ViewportLayout zeroWidth = new ViewportLayout(
        1L, new LogicalRect(10.2D, 20.2D, 0.0D, 50.1D), cardExampleScale());
    FramebufferRect fb = zeroWidth.framebufferRect();
    assertEquals(15, fb.x());
    assertEquals(0, fb.width());
    assertFalse(fb.contains(15.5D, 40.0D));

    // 零高：top=20.2 → mapped 40.4；bottom 同一 edge；不得得到 ceil(40.4)=41 → 1px。
    ViewportLayout zeroHeight = new ViewportLayout(
        1L, new LogicalRect(10.2D, 20.2D, 100.1D, 0.0D), cardExampleScale());
    FramebufferRect fb2 = zeroHeight.framebufferRect();
    assertEquals(40, fb2.y());
    assertEquals(0, fb2.height());

    // 分数 left 且带非零 origin 的组合。
    ViewportLayout negativeOrigin = new ViewportLayout(
        1L, new LogicalRect(0.5D, 0.5D, 0.0D, 0.0D), scale(1.0D, 1.0D, 0.25D, 0.75D));
    FramebufferRect fb3 = negativeOrigin.framebufferRect();
    assertEquals(0, fb3.width());
    assertEquals(0, fb3.height());
    assertTrue(fb3.isEmpty());
  }

  @Test
  @DisplayName("零宽/零高在 framebuffer 之外时仍为 0 而非 1px")
  void zeroExtentOutsideFramebufferStaysZero() {
    ViewportLayout beyondRight = new ViewportLayout(
        1L, new LogicalRect(1300.0D, 10.0D, 0.0D, 10.0D), identityScale());
    FramebufferRect fb = beyondRight.framebufferRect();
    assertEquals(FB_WIDTH, fb.x());
    assertEquals(0, fb.width());

    ViewportLayout beyondBottom = new ViewportLayout(
        1L, new LogicalRect(10.0D, 800.0D, 10.0D, 0.0D), identityScale());
    FramebufferRect fb2 = beyondBottom.framebufferRect();
    assertEquals(FB_HEIGHT, fb2.y());
    assertEquals(0, fb2.height());
  }

  @Test
  @DisplayName("极大有限 edge 在 clamp 后才转 int，不先 cast 造成整数溢出")
  void maximalFiniteEdgesAreClampedBeforeCasting() {
    double huge = 1.0e300D;
    ViewportLayout layout = new ViewportLayout(
        1L, new LogicalRect(huge, huge, huge, huge), identityScale());
    FramebufferRect fb = layout.framebufferRect();
    assertEquals(FB_WIDTH, fb.x());
    assertEquals(FB_HEIGHT, fb.y());
    assertEquals(0, fb.width());
    assertEquals(0, fb.height());
    assertTrue(fb.isEmpty());

    ViewportLayout negativeHuge = new ViewportLayout(
        1L, new LogicalRect(-huge, -huge, 2.0D * huge, 2.0D * huge), identityScale());
    FramebufferRect fb2 = negativeHuge.framebufferRect();
    assertEquals(0, fb2.x());
    assertEquals(0, fb2.y());
    assertEquals(FB_WIDTH, fb2.width());
    assertEquals(FB_HEIGHT, fb2.height());

    // 贴近 double 上限、且缩放后仍有限的 rect：clamp 后必须落在 framebuffer 上界。
    double maxFinite = Double.MAX_VALUE / 4.0D;
    ViewportLayout maximal = new ViewportLayout(
        1L, new LogicalRect(-maxFinite, -maxFinite, maxFinite * 2.0D, maxFinite * 2.0D),
        scale(1.0D, 1.0D, 0.0D, 0.0D));
    FramebufferRect fb3 = maximal.framebufferRect();
    assertEquals(0, fb3.x());
    assertEquals(0, fb3.y());
    assertEquals(FB_WIDTH, fb3.width());
    assertEquals(FB_HEIGHT, fb3.height());
  }

  @Test
  @DisplayName("Scale 映射输出非有限时统一抛 IAE，不返回 NaN/Infinity")
  void nonFiniteScaleOutputIsRejected() {
    // scale X=1e10 使 left=1e300 映射为 Infinity。
    assertThrows(IllegalArgumentException.class, () -> new ViewportLayout(
        1L, new LogicalRect(0.0D, 0.0D, 1.0e300D, 1.0D), scale(1.0e10D, 1.0D, 0.0D, 0.0D))
        .framebufferRect());

    // 加法溢出：origin=MAX 与 left=1e308 相加为 Infinity。
    assertThrows(IllegalArgumentException.class, () -> new ViewportLayout(
        1L, new LogicalRect(1.0e308D, 0.0D, 1.0D, 1.0D), scale(1.0D, 1.0D, Double.MAX_VALUE, 0.0D))
        .framebufferRect());

    // 负方向溢出：right 映射为 -Infinity。
    assertThrows(IllegalArgumentException.class, () -> new ViewportLayout(
        1L, new LogicalRect(-1.0e308D, 0.0D, 1.0E290D, 1.0D),
        scale(1.0D, 1.0D, -Double.MAX_VALUE, 0.0D)).framebufferRect());
  }

  // ------------------------------------------------------------------ ViewportLayout：构造校验

  @Test
  @DisplayName("ViewportLayout 拒绝负 generation 与 null 引用")
  void viewportLayoutRejectsBadGenerationAndNulls() {
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(-1L, new LogicalRect(0.0D, 0.0D, 1.0D, 1.0D), identityScale()));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(0L, null, identityScale()));
    assertThrows(IllegalArgumentException.class,
        () -> new ViewportLayout(0L, new LogicalRect(0.0D, 0.0D, 1.0D, 1.0D), null));
    // generation=0 合法。
    assertEquals(0L, new ViewportLayout(0L, new LogicalRect(0.0D, 0.0D, 1.0D, 1.0D), identityScale())
        .layoutGeneration());
  }

  @Test
  @DisplayName("ViewportLayout 不分配或修改 generation：纯函数、多次调用结果一致")
  void viewportLayoutIsPureAndDoesNotMutateGeneration() {
    ViewportLayout layout = new ViewportLayout(
        42L, new LogicalRect(10.2D, 20.2D, 100.1D, 50.1D), cardExampleScale());
    FramebufferRect first = layout.framebufferRect();
    FramebufferRect second = layout.framebufferRect();
    assertEquals(first, second);
    assertEquals(42L, layout.layoutGeneration());
    // 值语义：组件相等即相等。
    assertEquals(layout, new ViewportLayout(
        42L, new LogicalRect(10.2D, 20.2D, 100.1D, 50.1D), cardExampleScale()));
    assertFalse(layout.equals(new ViewportLayout(
        43L, new LogicalRect(10.2D, 20.2D, 100.1D, 50.1D), cardExampleScale())));
  }

  @Test
  @DisplayName("每像素边界：映射结果的 contains 与逻辑 rect 的左/上含右/下不含一致")
  void mappedContainsMatchesHalfOpenBoundariesAtEveryPixelEdge() {
    // identity + 整数 rect：floating rect 与 framebuffer rect 边界应完全重合。
    ViewportLayout layout = new ViewportLayout(
        1L, new LogicalRect(10.0D, 20.0D, 100.0D, 50.0D), identityScale());
    FramebufferRect fb = layout.framebufferRect();
    for (int px = 0; px <= FB_WIDTH; px += 1) {
      boolean inFb = px >= fb.x() && px < fb.right();
      boolean expected = px >= 10 && px < 110;
      assertEquals(expected, inFb, "x=" + px);
    }
    for (int py = 0; py <= FB_HEIGHT; py += 1) {
      boolean inFb = py >= fb.y() && py < fb.bottom();
      boolean expected = py >= 20 && py < 70;
      assertEquals(expected, inFb, "y=" + py);
    }
    assertTrue(fb.contains(10.0D, 20.0D));
    assertFalse(fb.contains(110.0D, 20.0D));
    assertFalse(fb.contains(10.0D, 70.0D));
  }

  // ------------------------------------------------------------------ 独立区间交集 oracle

  @Test
  @DisplayName("10000+ 样本：与独立 BigDecimal 区间交集 oracle 完全一致")
  void mappingMatchesIndependentBigDecimalIntervalOracle() {
    double[] coords = {-5000.5D, -100.0D, -0.5D, 0.0D, 0.25D, 10.2D, 100.1D, 1280.0D, 1280.5D, 2000.0D,
        Double.MAX_VALUE / 8.0D};
    double[] sizes = {0.0D, 0.5D, 50.1D, 100.1D, 3.0D, 1.0E300D};
    ScaleTransform[] transforms = {
        identityScale(),
        cardExampleScale(),
        scale(0.5D, 0.5D, -100.0D, -200.0D),
        scale(2.0D, 3.0D, 7.25D, 50.5D),
        scale(0.25D, 0.125D, 1280.0D, 720.0D),
        scale(1.5D, 2.0D, -3.75D, 11.5D),
    };

    int samples = 0;
    for (double left : coords) {
      for (double top : coords) {
        for (double width : sizes) {
          for (double height : sizes) {
            if (!Double.isFinite(left + width) || !Double.isFinite(top + height)) {
              continue;
            }
            LogicalRect rect = new LogicalRect(left, top, width, height);
            for (ScaleTransform transform : transforms) {
              ViewportLayout layout = new ViewportLayout(1L, rect, transform);

              boolean implementationThrew = false;
              FramebufferRect actual = null;
              try {
                actual = layout.framebufferRect();
              } catch (IllegalArgumentException expectedFailure) {
                implementationThrew = true;
              }

              boolean oracleThrew = false;
              FramebufferRect expected = null;
              try {
                expected = oracle(rect, transform);
              } catch (IllegalArgumentException expectedFailure) {
                oracleThrew = true;
              }

              assertEquals(oracleThrew, implementationThrew,
                  "overflow agreement mismatch: rect=" + rect + " transform=" + transform);
              if (implementationThrew) {
                continue;
              }
              assertEquals(expected, actual, "rect=" + rect + " transform=" + transform);
              samples++;
            }
          }
        }
      }
    }
    assertTrue(samples > 10000, "sample count too small: " + samples);
  }

  /**
   * 独立 oracle：不复用实现结构，直接按“映射 → clamp 到 [0,fb] → min floor / max ceil → 空维度取同一 edge”
   * 用 {@link BigDecimal} 精确计算。
   */
  private static FramebufferRect oracle(LogicalRect rect, ScaleTransform transform) {
    double mappedLeft = transform.toFramebufferX(rect.left());
    double mappedRight = transform.toFramebufferX(rect.right());
    double mappedTop = transform.toFramebufferY(rect.top());
    double mappedBottom = transform.toFramebufferY(rect.bottom());

    BigDecimal leftEdge = clamp(BigDecimal.valueOf(mappedLeft), transform.framebufferWidth());
    BigDecimal rightEdge = clamp(BigDecimal.valueOf(mappedRight), transform.framebufferWidth());
    BigDecimal topEdge = clamp(BigDecimal.valueOf(mappedTop), transform.framebufferHeight());
    BigDecimal bottomEdge = clamp(BigDecimal.valueOf(mappedBottom), transform.framebufferHeight());

    BigDecimal minX = leftEdge.min(rightEdge);
    BigDecimal maxX = leftEdge.max(rightEdge);
    BigDecimal minY = topEdge.min(bottomEdge);
    BigDecimal maxY = topEdge.max(bottomEdge);

    int x = minX.setScale(0, RoundingMode.FLOOR).intValueExact();
    int y = minY.setScale(0, RoundingMode.FLOOR).intValueExact();
    int right = maxX.setScale(0, RoundingMode.CEILING).intValueExact();
    int bottom = maxY.setScale(0, RoundingMode.CEILING).intValueExact();

    if (rect.width() == 0.0D) {
      right = x;
    }
    if (rect.height() == 0.0D) {
      bottom = y;
    }
    return new FramebufferRect(x, y, right - x, bottom - y);
  }

  private static BigDecimal clamp(BigDecimal edge, int upperBoundInclusive) {
    BigDecimal upper = BigDecimal.valueOf(upperBoundInclusive);
    if (edge.signum() < 0) {
      return BigDecimal.ZERO;
    }
    if (edge.compareTo(upper) > 0) {
      return upper;
    }
    return edge;
  }

  // ------------------------------------------------------------------ 冻结 API 面

  @Test
  @DisplayName("冻结 API：三个 record 的公开成员面与卡片逐字一致")
  void publicApiSurfaceMatchesCard() throws Exception {
    assertRecordApi(LogicalRect.class,
        new String[][] {{"double", "double", "double", "double"}},
        new String[] {"left", "top", "width", "height", "right", "bottom", "isEmpty", "contains",
            "equals", "hashCode", "toString"});
    assertRecordApi(FramebufferRect.class,
        new String[][] {{"int", "int", "int", "int"}},
        new String[] {"x", "y", "width", "height", "right", "bottom", "isEmpty", "contains",
            "equals", "hashCode", "toString"});
    assertRecordApi(ViewportLayout.class,
        new String[][] {
            {"long", LogicalRect.class.getName(), ScaleTransform.class.getName()},
            {"long", LogicalRect.class.getName(), ScaleTransform.class.getName(),
                Optional.class.getName()}},
        new String[] {"layoutGeneration", "logicalRect", "scaleTransform", "measuredFramebufferBounds",
            "framebufferRect", "equals", "hashCode", "toString"});

    // 卡片签名精确核对。
    Method framebufferRect = ViewportLayout.class.getMethod("framebufferRect");
    assertEquals(FramebufferRect.class, framebufferRect.getReturnType());
    Method logicalContains = LogicalRect.class.getMethod("contains", double.class, double.class);
    assertEquals(boolean.class, logicalContains.getReturnType());
    Method fbContains =
        FramebufferRect.class.getMethod("contains", double.class, double.class);
    assertEquals(boolean.class, fbContains.getReturnType());
    assertEquals(int.class, FramebufferRect.class.getMethod("right").getReturnType());
    assertEquals(double.class, LogicalRect.class.getMethod("right").getReturnType());
    assertEquals(boolean.class, LogicalRect.class.getMethod("isEmpty").getReturnType());
    assertEquals(boolean.class, FramebufferRect.class.getMethod("isEmpty").getReturnType());
  }

  private static void assertRecordApi(Class<?> type, String[][] constructorSignatures,
      String[] expectedMethodNames) {
    assertTrue(type.isRecord(), type + " must be a record");
    List<String> parameterSets = Arrays.stream(type.getConstructors())
        .filter(c -> Modifier.isPublic(c.getModifiers()))
        .map(ViewportLayoutContractTest::describe)
        .collect(Collectors.toList());
    assertEquals(constructorSignatures.length, type.getConstructors().length,
        type + " must expose exactly the card constructors: " + parameterSets);
    Set<String> expectedConstructors = Arrays.stream(constructorSignatures)
        .map(signature -> String.join(",", signature))
        .collect(Collectors.toSet());
    assertEquals(expectedConstructors, new HashSet<>(parameterSets),
        type + " constructor mismatch: " + parameterSets);

    Set<String> expected = new HashSet<>(Arrays.asList(expectedMethodNames));
    Set<String> actual = new HashSet<>();
    for (Method m : type.getDeclaredMethods()) {
      if (!Modifier.isPublic(m.getModifiers())) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals(expected, actual, type + " public method surface mismatch");
    // 无 public static 辅助方法。
    for (Method m : type.getDeclaredMethods()) {
      if (Modifier.isPublic(m.getModifiers())) {
        assertFalse(Modifier.isStatic(m.getModifiers()), m + " must not be static");
      }
    }
  }

  private static String describe(Constructor<?> constructor) {
    List<String> names = new ArrayList<>();
    for (Class<?> parameterType : constructor.getParameterTypes()) {
      names.add(parameterType.getName());
    }
    return String.join(",", names);
  }
}