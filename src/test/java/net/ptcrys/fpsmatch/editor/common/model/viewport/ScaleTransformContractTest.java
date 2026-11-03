package net.ptcrys.fpsmatch.editor.common.model.viewport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * R04.2 {@link ScaleTransform} 契约测试（CONTRACTS §8 / tasks/R04.2-scale-transform.md）。
 *
 * <p>期望值按任务卡的固定公式独立计算；随机部分与 {@link BigDecimal} 精确参考实现对照，不使用实现的内部量。
 * 只验证本类型自身的变换/校验语义，不涉及 Rect、FBO、viewport generation、GUI 测量或渲染。
 */
class ScaleTransformContractTest {

  private static final double ABS_TOL = 1e-9D;
  private static final double REL_TOL = 1e-9D;

  // ------------------------------------------------------------ identity 与基本双向

  @Test
  void identityMapsLogicalAndPixelBothWaysWithoutChange() {
    ScaleTransform identity = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 854, 480);
    double[] samples = {0.0D, -0.0D, 1.0D, -1.0D, 0.5D, -123.75D, 853.0D, 854.0D, 100000.5D, -1e6D};
    for (double value : samples) {
      assertTrue(value == identity.toFramebufferX(value), "identity X forward must be exact for " + value);
      assertTrue(value == identity.toFramebufferY(value), "identity Y forward must be exact for " + value);
      assertTrue(value == identity.toLogicalX(value), "identity X inverse must be exact for " + value);
      assertTrue(value == identity.toLogicalY(value), "identity Y inverse must be exact for " + value);
    }
    // 同一实例反复调用无状态变化（可重入）。
    // 注意：按任务卡公式，-0.0 + 0.0 得到 +0.0，signed zero 不保留；只要求数值相等。
    assertTrue(-0.0D == identity.toFramebufferX(-0.0D));
    assertTrue(-0.0D == identity.toLogicalX(-0.0D));
    assertTrue(-0.0D == identity.toFramebufferY(-0.0D));
    assertTrue(-0.0D == identity.toLogicalY(-0.0D));
    assertTrue(1.0D == identity.toFramebufferX(1.0D));
  }

  @Test
  void independentXAndYScaleAreAppliedPerAxis() {
    ScaleTransform transform = new ScaleTransform(2.0D, 0.5D, 100.0D, -40.0D, 1280, 720);

    assertTrue(200.0D == transform.toFramebufferX(50.0D));
    assertTrue(-40.0D == transform.toFramebufferY(0.0D));
    assertTrue(-15.0D == transform.toFramebufferY(50.0D));

    assertTrue(50.0D == transform.toLogicalX(200.0D));
    assertTrue(0.0D == transform.toLogicalY(-40.0D));
    assertTrue(50.0D == transform.toLogicalY(-15.0D));
  }

  @Test
  void nonZeroAndNegativeOriginTranslateInPixelSpaceOnly() {
    // 同一 scale、不同 origin：差值恒为 origin 差，斜率不变（origin 不参与缩放）。
    ScaleTransform positiveOrigin = new ScaleTransform(3.0D, 3.0D, 100.0D, 100.0D, 1920, 1080);
    ScaleTransform negativeOrigin = new ScaleTransform(3.0D, 3.0D, -250.0D, -250.0D, 1920, 1080);

    assertTrue(100.0D == positiveOrigin.toFramebufferX(0.0D));
    assertTrue(-250.0D == negativeOrigin.toFramebufferX(0.0D));
    assertTrue(350.0D == positiveOrigin.toFramebufferX(0.0D) - negativeOrigin.toFramebufferX(0.0D));

    for (double logical : new double[] {-10.0D, 0.0D, 7.5D, 640.0D}) {
      double slope = positiveOrigin.toFramebufferX(logical + 1.0D) - positiveOrigin.toFramebufferX(logical);
      assertTrue(slope == 3.0D, "origin must not change the slope, got " + slope);
      double slopeY = negativeOrigin.toFramebufferY(logical + 1.0D) - negativeOrigin.toFramebufferY(logical);
      assertTrue(slopeY == 3.0D, "origin must not change the Y slope, got " + slopeY);
    }
  }

  // ------------------------------------------------------------ framebuffer 尺寸只是元数据

  @Test
  void framebufferSizeIsMetadataOnlyAndDoesNotChangeMapping() {
    ScaleTransform small = new ScaleTransform(1.5D, 2.0D, 12.0D, 34.0D, 854, 480);
    ScaleTransform large = new ScaleTransform(1.5D, 2.0D, 12.0D, 34.0D, 1920, 1080);
    assertNotEquals(small, large);

    for (double value : new double[] {-500.0D, 0.0D, 1.0D, 854.0D, 1920.0D, 1e6D}) {
      assertTrue(small.toFramebufferX(value) == large.toFramebufferX(value));
      assertTrue(small.toFramebufferY(value) == large.toFramebufferY(value));
      assertTrue(small.toLogicalX(value) == large.toLogicalX(value));
      assertTrue(small.toLogicalY(value) == large.toLogicalY(value));
    }
  }

  @Test
  void realFramebufferSizesCombineWithCommonScales() {
    int[] widths = {854, 1280, 1920};
    int[] heights = {480, 720, 1080};
    double[] scales = {0.5D, 1.0D, 1.5D, 2.0D, 3.0D};
    for (int i = 0; i < widths.length; i++) {
      for (double scale : scales) {
        ScaleTransform transform =
            new ScaleTransform(scale, scale, 0.0D, 0.0D, widths[i], heights[i]);
        double logicalX = 640.0D;
        double expectedX = logicalX * scale + 0.0D;
        assertTrue(expectedX == transform.toFramebufferX(logicalX),
            "forward X for width=" + widths[i] + " scale=" + scale);
        assertTrue(logicalX == transform.toLogicalX(expectedX),
            "inverse X for width=" + widths[i] + " scale=" + scale);
        // 尺寸不参与推导：logical 尺寸与 framebuffer 尺寸无隐含比例关系。
        assertTrue(transform.toFramebufferX(widths[i]) == widths[i] * scale);
      }
    }
  }

  // ------------------------------------------------------------ 负数/越界仍变换、不 clamp

  @Test
  void negativeAndOutOfBoundsFiniteCoordinatesStillTransformUnclamped() {
    ScaleTransform transform = new ScaleTransform(2.0D, 4.0D, 0.0D, 0.0D, 854, 480);

    assertTrue(-10.0D == transform.toFramebufferX(-5.0D));
    assertTrue(-20.0D == transform.toFramebufferY(-5.0D));
    assertTrue(1708.0D == transform.toFramebufferX(854.0D));
    assertTrue(1920.0D == transform.toFramebufferY(480.0D));
    assertTrue(100.0D == transform.toFramebufferX(50.0D));

    // 反向：以 framebuffer 之外的有限像素值输入仍照常变换，不 clamp 到 [0,size)。
    assertTrue(-5.0D == transform.toLogicalX(-10.0D));
    assertTrue(500.0D == transform.toLogicalX(1000.0D));
    assertTrue(-1000.0D == transform.toLogicalY(-4000.0D));
  }

  @Test
  void originShiftsBothDirectionsAndInverseUndoesIt() {
    ScaleTransform transform = new ScaleTransform(1.0D, 1.0D, 40.0D, 25.0D, 854, 480);
    assertTrue(40.0D == transform.toFramebufferX(0.0D));
    assertTrue(-40.0D == transform.toLogicalX(0.0D));
    assertTrue(1234.5D == transform.toLogicalX(transform.toFramebufferX(1234.5D)));
    assertTrue(1274.5D == transform.toFramebufferX(1234.5D));
    assertTrue(-25.0D == transform.toLogicalY(0.0D));
    assertTrue(75.0D == transform.toFramebufferY(50.0D));
    assertTrue(50.0D == transform.toLogicalY(transform.toFramebufferY(50.0D)));
  }

  // ------------------------------------------------------------ 精确运算：不截断、不 round

  @Test
  void fractionalResultsAreNotTruncatedOrRounded() {
    ScaleTransform transform = new ScaleTransform(1.5D, 1.5D, 0.0D, 0.0D, 854, 480);
    assertTrue(0.75D == transform.toFramebufferX(0.5D));
    assertClose(0.25D, transform.toFramebufferY(1.0D / 6.0D), "1/6 * 1.5 must stay fractional-accurate");

    ScaleTransform halving = new ScaleTransform(2.0D, 2.0D, 0.0D, 0.0D, 854, 480);
    assertTrue(0.5D == halving.toLogicalX(1.0D));
    assertTrue(0.5D == halving.toLogicalY(1.0D));
    assertTrue(0.0625D == halving.toLogicalX(0.125D));

    // 小数结果既非 floor 也非 ceil 也不减 1。
    double pixel = transform.toFramebufferX(0.5D);
    assertNotEquals(0.0D, pixel);
    assertNotEquals(1.0D, pixel);

    // 结果类型为 double：0.5 logical 在 scale=1 下必须仍为 0.5，不得退化成整数。
    ScaleTransform identity = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 854, 480);
    assertTrue(0.5D == identity.toFramebufferX(0.5D));
    assertTrue(0.5D == identity.toLogicalX(0.5D));
  }

  @Test
  void roundTripOnExactlyRepresentableValuesIsExact() {
    ScaleTransform transform = new ScaleTransform(2.0D, 0.25D, 100.0D, -64.0D, 1920, 1080);
    assertTrue(10.25D == transform.toLogicalX(transform.toFramebufferX(10.25D)));
    assertTrue(-256.0D == transform.toLogicalY(transform.toFramebufferY(-256.0D)));
  }

  // ------------------------------------------------------------ 构造器：非有限、零/负 scale、尺寸

  @Test
  void nonFiniteComponentsAreRejected() {
    double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (double value : bad) {
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(value, 1.0D, 0.0D, 0.0D, 854, 480), "xScale=" + value);
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(1.0D, value, 0.0D, 0.0D, 854, 480), "yScale=" + value);
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(1.0D, 1.0D, value, 0.0D, 854, 480), "pixelOriginX=" + value);
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(1.0D, 1.0D, 0.0D, value, 854, 480), "pixelOriginY=" + value);
    }
  }

  @Test
  void zeroAndNegativeScalesAreRejectedIncludingSignedZero() {
    double[] bad = {0.0D, -0.0D, -1.0D, -0.5D, -Double.MIN_VALUE, -Double.MAX_VALUE, Double.MIN_NORMAL * -1.0D};
    for (double value : bad) {
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(value, 1.0D, 0.0D, 0.0D, 854, 480), "xScale=" + value);
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(1.0D, value, 0.0D, 0.0D, 854, 480), "yScale=" + value);
    }
    // 无默认/静默修复：不能靠省缺或替换成 1.0 通过。
    assertThrows(IllegalArgumentException.class,
        () -> new ScaleTransform(Double.MIN_VALUE * -0.0D, 1.0D, 0.0D, 0.0D, 854, 480));
  }

  @Test
  void nonPositiveFramebufferSizesAreRejected() {
    int[] bad = {0, -1, -480, Integer.MIN_VALUE};
    for (int value : bad) {
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, value, 480), "framebufferWidth=" + value);
      assertThrows(IllegalArgumentException.class,
          () -> new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 854, value), "framebufferHeight=" + value);
    }
  }

  @Test
  void minimalAndMaximalPositiveSizesAreAccepted() {
    ScaleTransform tiny = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 1, 1);
    assertTrue(5.0D == tiny.toFramebufferX(5.0D));
    ScaleTransform huge = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertTrue(5.0D == huge.toFramebufferY(5.0D));
    // 尺寸不溢出、不参与运算：这些实例都没有被拒绝。
    assertTrue(0.0D == huge.toLogicalX(0.0D));
  }

  @Test
  void subnormalScaleIsConstructibleButInverseOverflowIsRejected() {
    ScaleTransform subnormal = new ScaleTransform(Double.MIN_VALUE, Double.MIN_VALUE, 0.0D, 0.0D, 854, 480);
    assertTrue(Double.MIN_VALUE == subnormal.xScale());
    // 正向乘法仍有限：1.0 * Double.MIN_VALUE 不溢出。
    assertTrue(Double.MIN_VALUE == subnormal.toFramebufferX(1.0D));
    assertTrue(2.0D * Double.MIN_VALUE == subnormal.toFramebufferY(2.0D));
    // 反向除以 subnormal 溢出，必须拒绝而不是返回 Infinity/NaN。
    assertThrows(IllegalArgumentException.class, () -> subnormal.toLogicalX(1.0D));
    assertThrows(IllegalArgumentException.class, () -> subnormal.toLogicalY(-1.0D));
    // 除以 0 也已不可能：scale 为 subnormal 但 > 0；正向仍可产生有限结果。
    assertTrue(Double.isFinite(subnormal.toFramebufferX(Double.MAX_VALUE)));
  }

  // ------------------------------------------------------------ 结果非有限一律拒绝

  @Test
  void forwardResultOverflowIsRejected() {
    ScaleTransform multiplyOverflow = new ScaleTransform(Double.MAX_VALUE, 1.0D, 0.0D, 0.0D, 854, 480);
    assertThrows(IllegalArgumentException.class, () -> multiplyOverflow.toFramebufferX(2.0D));
    assertTrue(Double.MAX_VALUE == multiplyOverflow.toFramebufferX(1.0D));

    ScaleTransform addOverflow = new ScaleTransform(1.0D, 1.0D, Double.MAX_VALUE, Double.MAX_VALUE, 854, 480);
    assertThrows(IllegalArgumentException.class, () -> addOverflow.toFramebufferX(Double.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> addOverflow.toFramebufferY(Double.MAX_VALUE));
    assertTrue(Double.MAX_VALUE == addOverflow.toFramebufferX(1.0D));
    assertTrue(Double.MAX_VALUE == addOverflow.toFramebufferY(1.0D));

    ScaleTransform negativeAddOverflow = new ScaleTransform(1.0D, 1.0D, -Double.MAX_VALUE, -Double.MAX_VALUE, 854, 480);
    assertThrows(IllegalArgumentException.class, () -> negativeAddOverflow.toFramebufferX(-Double.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> negativeAddOverflow.toFramebufferY(-Double.MAX_VALUE));
  }

  @Test
  void inverseSubtractOverflowAndDivisionOverflowAreRejected() {
    ScaleTransform subtractOverflow = new ScaleTransform(1.0D, 1.0D, -Double.MAX_VALUE, -Double.MAX_VALUE, 854, 480);
    assertThrows(IllegalArgumentException.class, () -> subtractOverflow.toLogicalX(Double.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> subtractOverflow.toLogicalY(Double.MAX_VALUE));

    ScaleTransform divideOverflow = new ScaleTransform(0.5D, 0.5D, 0.0D, 0.0D, 854, 480);
    assertThrows(IllegalArgumentException.class, () -> divideOverflow.toLogicalX(Double.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> divideOverflow.toLogicalY(-Double.MAX_VALUE));

    // 中间 subtract 有限但最终除法非有限同样拒绝（MIN_NORMAL 除 MAX_VALUE 溢出）。
    ScaleTransform tinyScale = new ScaleTransform(Double.MIN_NORMAL, Double.MIN_NORMAL, 0.0D, 0.0D, 854, 480);
    assertThrows(IllegalArgumentException.class, () -> tinyScale.toLogicalX(Double.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> tinyScale.toLogicalY(Double.MAX_VALUE));
    // 同一实例的小幅反向仍有限，证明拒绝来自 overflow 而非无条件拒绝。
    assertClose(1.0D / Double.MIN_NORMAL, tinyScale.toLogicalX(1.0D), "MIN_NORMAL inverse stays finite");
  }

  @Test
  void largeButFiniteResultsAreStillAccepted() {
    ScaleTransform transform = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 854, 480);
    assertTrue(Double.MAX_VALUE == transform.toFramebufferX(Double.MAX_VALUE));
    assertTrue(-Double.MAX_VALUE == transform.toFramebufferY(-Double.MAX_VALUE));
    assertTrue(Double.MAX_VALUE == transform.toLogicalX(Double.MAX_VALUE));
    assertTrue(-Double.MAX_VALUE == transform.toLogicalY(-Double.MAX_VALUE));
    assertTrue(Double.MAX_VALUE == transform.toLogicalX(Double.MAX_VALUE - 1.0D));
  }

  // ------------------------------------------------------------ 方法参数非有限

  @Test
  void nonFiniteArgumentsAreRejectedByAllFourMethodsForEveryComponent() {
    ScaleTransform transform = new ScaleTransform(1.5D, 2.5D, 30.0D, -70.0D, 1920, 1080);
    double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (double value : bad) {
      assertThrows(IllegalArgumentException.class, () -> transform.toFramebufferX(value), "toFramebufferX " + value);
      assertThrows(IllegalArgumentException.class, () -> transform.toFramebufferY(value), "toFramebufferY " + value);
      assertThrows(IllegalArgumentException.class, () -> transform.toLogicalX(value), "toLogicalX " + value);
      assertThrows(IllegalArgumentException.class, () -> transform.toLogicalY(value), "toLogicalY " + value);
    }
    // 有限参数照常通过，避免“全部拒绝”式假实现。
    assertTrue(0.0D == transform.toLogicalX(30.0D));
    assertTrue(0.0D == transform.toLogicalY(-70.0D));
  }

  // ------------------------------------------------------------ 反例：origin/flip/clamp 误用

  @Test
  void originIsNotALogicalSpaceGuiOffsetCounterexample() {
    // 若把 origin 当成 logical 偏移（(logical+origin)*scale 或 logical*scale+origin*scale），
    // scale=2、origin=100 时 logical=10 会得到 220 而非正确的 120。
    ScaleTransform transform = new ScaleTransform(2.0D, 2.0D, 100.0D, 100.0D, 1920, 1080);
    assertTrue(120.0D == transform.toFramebufferX(10.0D));
    assertNotEquals(220.0D, transform.toFramebufferX(10.0D));
    assertTrue(120.0D == transform.toFramebufferY(10.0D));
    // 正确反向：把 origin 当 logical 偏移会把 (120-100)/2 算成 120/2-100 = -40。
    assertTrue(10.0D == transform.toLogicalX(120.0D));
    assertNotEquals(-40.0D, transform.toLogicalX(120.0D));
    // origin 恰好等于 0 时不隐藏缺陷，用非 0 origin 再验一次。
    ScaleTransform zeroOrigin = new ScaleTransform(2.0D, 2.0D, 0.0D, 0.0D, 1920, 1080);
    assertTrue(20.0D == zeroOrigin.toFramebufferX(10.0D));
  }

  @Test
  void originIsNotTreatedAsPixelsToSkipInInverseCounterexample() {
    // 若反向忘记减 origin（pixel/scale）或减去的是 scale*origin，结果会偏移。
    ScaleTransform transform = new ScaleTransform(1.25D, 4.0D, 37.0D, -19.0D, 1920, 1080);
    assertTrue(100.0D == transform.toLogicalX(162.0D));   // (162-37)/1.25
    assertNotEquals(129.6D, transform.toLogicalX(162.0D)); // 162/1.25 = 129.6
    assertTrue(25.0D == transform.toLogicalY(81.0D));      // (81-(-19))/4
    assertNotEquals(20.25D, transform.toLogicalY(81.0D));  // 81/4
  }

  @Test
  void yIsNotFlippedToBottomLeftOriginCounterexample() {
    ScaleTransform transform = new ScaleTransform(1.0D, 1.0D, 50.0D, 50.0D, 1920, 1080);
    assertTrue(50.0D == transform.toFramebufferY(0.0D));
    assertTrue(150.0D == transform.toFramebufferY(100.0D));
    // 左上原点实现：logicalY 增大 → pixelY 增大（底部原点翻转会递减）。
    assertTrue(transform.toFramebufferY(200.0D) > transform.toFramebufferY(100.0D));
    // 若做了 flip，toFramebufferY(0) 会是 framebufferHeight - origin 之类的值。
    assertNotEquals(1080.0D - 50.0D, transform.toFramebufferY(0.0D));
    assertNotEquals(1080.0D - 150.0D, transform.toFramebufferY(100.0D));
    // 反向也不翻转。
    assertTrue(0.0D == transform.toLogicalY(50.0D));
    assertTrue(100.0D == transform.toLogicalY(150.0D));
  }

  @Test
  void noClampToFramebufferBoundsCounterexample() {
    ScaleTransform transform = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 854, 480);
    assertTrue(-1.0D == transform.toFramebufferX(-1.0D));
    assertTrue(-1.0D == transform.toFramebufferY(-1.0D));
    assertTrue(855.0D == transform.toFramebufferX(855.0D));
    assertTrue(481.0D == transform.toFramebufferY(481.0D));
    assertTrue(-100.0D == transform.toLogicalX(-100.0D));
    assertTrue(1000.0D == transform.toLogicalY(1000.0D));
  }

  @Test
  void sizesAreNotDerivedFromScaleOrViceVersa() {
    // framebuffer 尺寸与 scale 无绑定：1.0 scale 不代表 logical==pixel 尺寸；尺寸只是元数据。
    ScaleTransform weird = new ScaleTransform(1.0D, 1.0D, 0.0D, 0.0D, 854, 480);
    assertTrue(854.0D == weird.toFramebufferX(854.0D));
    ScaleTransform odd = new ScaleTransform(0.5D, 3.0D, 0.0D, 0.0D, 1920, 1080);
    assertTrue(960.0D == odd.toFramebufferX(1920.0D));
    assertTrue(3240.0D == odd.toFramebufferY(1080.0D));
  }

  // ------------------------------------------------------------ 随机正反向 + 独立参考

  @Test
  void randomForwardAndInverseMatchBigDecimalReferenceOnNormalMagnitudes() {
    Random random = new Random(20261014L);
    double[] scales = {0.01D, 0.125D, 0.5D, 1.0D, 1.5D, 2.0D, 2.5D, 3.0D};
    int points = 10000;
    for (int i = 0; i < points; i++) {
      double xScale = scales[random.nextInt(scales.length)];
      double yScale = scales[random.nextInt(scales.length)];
      double originX = (random.nextDouble() - 0.5D) * 4000.0D;
      double originY = (random.nextDouble() - 0.5D) * 4000.0D;
      int width = random.nextBoolean() ? 854 : 1920;
      int height = random.nextBoolean() ? 480 : 1080;
      ScaleTransform transform = new ScaleTransform(xScale, yScale, originX, originY, width, height);

      double logicalX = (random.nextDouble() - 0.5D) * 40000.0D;
      double logicalY = (random.nextDouble() - 0.5D) * 40000.0D;

      double expectedPixelX = reference(logicalX, xScale, originX);
      double expectedPixelY = reference(logicalY, yScale, originY);
      double actualPixelX = transform.toFramebufferX(logicalX);
      double actualPixelY = transform.toFramebufferY(logicalY);
      assertClose(expectedPixelX, actualPixelX, "forward X at point " + i);
      assertClose(expectedPixelY, actualPixelY, "forward Y at point " + i);

      double backX = transform.toLogicalX(actualPixelX);
      double backY = transform.toLogicalY(actualPixelY);
      assertClose(logicalX, backX, "inverse X at point " + i);
      assertClose(logicalY, backY, "inverse Y at point " + i);

      // 独立参考也覆盖反向（不依赖正向的中间值）。
      assertClose(logicalX, referenceInverse(actualPixelX, xScale, originX), "reference inverse X at point " + i);
      assertClose(logicalY, referenceInverse(actualPixelY, yScale, originY), "reference inverse Y at point " + i);
    }
  }

  @Test
  void randomInverseOnNormalPixelMagnitudesMatchesReference() {
    Random random = new Random(424242L);
    double[] scales = {0.01D, 0.125D, 0.5D, 1.0D, 1.5D, 2.0D, 2.5D, 3.0D};
    for (int i = 0; i < 10000; i++) {
      double scale = scales[random.nextInt(scales.length)];
      double origin = (random.nextDouble() - 0.5D) * 4000.0D;
      ScaleTransform transform = new ScaleTransform(scale, scale, origin, origin, 1920, 1080);
      double pixel = (random.nextDouble() - 0.5D) * 100000.0D;
      assertClose(referenceInverse(pixel, scale, origin), transform.toLogicalX(pixel), "random inverse X " + i);
      assertClose(referenceInverse(pixel, scale, origin), transform.toLogicalY(pixel), "random inverse Y " + i);
    }
  }

  // ------------------------------------------------------------ record 值语义与冻结 API

  @Test
  void recordValueSemanticsFollowsComponentsOnly() {
    ScaleTransform first = new ScaleTransform(1.0D, 2.0D, 3.0D, 4.0D, 800, 600);
    ScaleTransform same = new ScaleTransform(1.0D, 2.0D, 3.0D, 4.0D, 800, 600);
    ScaleTransform otherSize = new ScaleTransform(1.0D, 2.0D, 3.0D, 4.0D, 801, 600);

    assertEquals(first, same);
    assertEquals(first.hashCode(), same.hashCode());
    assertNotEquals(first, otherSize);
    assertTrue(first.toString().contains("ScaleTransform"));
    assertEquals(1.0D, first.xScale());
    assertEquals(2.0D, first.yScale());
    assertEquals(3.0D, first.pixelOriginX());
    assertEquals(4.0D, first.pixelOriginY());
    assertEquals(800, first.framebufferWidth());
    assertEquals(600, first.framebufferHeight());
  }

  @Test
  void publicApiSurfaceIsFrozen() throws NoSuchMethodException {
    Class<ScaleTransform> type = ScaleTransform.class;
    assertTrue(Modifier.isPublic(type.getModifiers()));
    assertTrue(Modifier.isFinal(type.getModifiers()), "record must be final");
    assertTrue(type.isRecord());

    RecordComponent[] components = type.getRecordComponents();
    assertEquals(6, components.length);
    String[] names = Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new);
    assertEquals(
        List.of("xScale", "yScale", "pixelOriginX", "pixelOriginY", "framebufferWidth", "framebufferHeight"),
        List.of(names));
    assertEquals(
        List.of(double.class, double.class, double.class, double.class, int.class, int.class),
        Arrays.stream(components).map(RecordComponent::getType).collect(Collectors.toList()));

    Set<String> expectedMethods = new TreeSet<>(List.of(
        "xScale", "yScale", "pixelOriginX", "pixelOriginY", "framebufferWidth", "framebufferHeight",
        "toFramebufferX", "toFramebufferY", "toLogicalX", "toLogicalY",
        "equals", "hashCode", "toString"));
    Set<String> actualMethods = new TreeSet<>();
    Set<String> publicStatics = new LinkedHashSet<>();
    for (Method method : type.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        actualMethods.add(method.getName());
        if (Modifier.isStatic(method.getModifiers())) {
          publicStatics.add(method.getName());
        }
      }
    }
    assertEquals(expectedMethods, actualMethods, "public method surface must be exactly the frozen API");
    assertTrue(publicStatics.isEmpty(), "no public static helpers allowed: " + publicStatics);
    for (String name : List.of("toFramebufferX", "toFramebufferY", "toLogicalX", "toLogicalY")) {
      Method method = type.getDeclaredMethod(name, double.class);
      assertEquals(double.class, method.getReturnType(), name + " must return double");
      assertTrue(!Modifier.isStatic(method.getModifiers()), name + " must be an instance method");
      assertTrue(Modifier.isPublic(method.getModifiers()), name + " must be public");
    }

    Constructor<?>[] constructors = type.getDeclaredConstructors();
    assertEquals(1, constructors.length, "exactly one canonical constructor");
    assertTrue(Modifier.isPublic(constructors[0].getModifiers()));
    assertEquals(
        List.of(double.class, double.class, double.class, double.class, int.class, int.class),
        List.of(constructors[0].getParameterTypes()));

    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
      assertTrue(!(Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())),
          "no public static fields allowed: " + field.getName());
    }
  }

  // ------------------------------------------------------------ helpers

  /** 任务卡公式的精确参考实现：先做精确乘法与加法，再一次性舍入到 double。 */
  private static double reference(double logical, double scale, double origin) {
    return BigDecimal.valueOf(logical)
        .multiply(BigDecimal.valueOf(scale))
        .add(BigDecimal.valueOf(origin))
        .doubleValue();
  }

  /** 任务卡反向公式的精确参考实现：先精确 subtract 再精确除。 */
  private static double referenceInverse(double pixel, double scale, double origin) {
    return BigDecimal.valueOf(pixel)
        .subtract(BigDecimal.valueOf(origin))
        .divide(BigDecimal.valueOf(scale), java.math.MathContext.DECIMAL128)
        .doubleValue();
  }

  private static void assertClose(double expected, double actual, String message) {
    double difference = Math.abs(expected - actual);
    double bound = ABS_TOL + REL_TOL * Math.abs(expected);
    if (!(difference <= bound)) {
      fail(message + ": expected=" + expected + " actual=" + actual + " diff=" + difference + " bound=" + bound);
    }
  }
}