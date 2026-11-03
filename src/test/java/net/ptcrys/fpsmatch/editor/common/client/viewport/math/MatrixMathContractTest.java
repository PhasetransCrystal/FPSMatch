package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * R18.1c 有界矩阵数学 {@link MatrixMath} / {@link Vector4} 的正式契约测试（纯 JDK，无 MC/JOML/LWJGL）。
 *
 * <p>本测试只依据任务卡 R1.5 冻结契约断言，不引入任何生产实现细节作为 oracle：
 * <ul>
 *   <li>乘积与残差由本文件独立的 {@code double} 行主序乘加实现计算，<strong>不</strong>把
 *       {@code MatrixMath.multiply} 得到的单位矩阵当唯一 oracle；</li>
 *   <li>1000 组可逆小整数矩阵使用独立的 {@link BigInteger} determinant / cofactor / adjugate
 *       oracle 逐项验证，且<strong>在调用生产 inverse 之前</strong>就用该 oracle 完成预筛与去重，
 *       绝不按生产 inverse 的成功/误差/残差挑样本；</li>
 *   <li>float 透视 fixture 模拟原生 GPU 输入（每个非零分量先 cast float 再转 double）。</li>
 * </ul>
 */
final class MatrixMathContractTest {

  /** golden / oracle 逐项绝对误差上限。 */
  private static final double ELEMENT_TOLERANCE = 1e-8D;

  /** 相对单位矩阵的最大绝对元素残差上限（与生产契约同值，但由本测试独立计算）。 */
  private static final double RESIDUAL_LIMIT = 1e-8D;

  /** inverse 的条件估计上限（数值可用性上限，不是精确 condition number）。 */
  private static final double CONDITION_LIMIT = 1e12D;

  // ------------------------------------------------------------------
  // 独立工具：行主序 double 运算与载荷读写，不调用生产 multiply/inverse
  // ------------------------------------------------------------------

  /** 以 row-major 行数组构造 {@link FrameMatrix}（内部换算为 column-major 载荷）。 */
  private static FrameMatrix fromRows(double[][] rows) {
    final double[] values = new double[16];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        values[column * 4 + row] = rows[row][column];
      }
    }
    return new FrameMatrix(values);
  }

  /** 读出 row-major 行数组。 */
  private static double[][] rowsOf(FrameMatrix matrix) {
    final double[] values = matrix.columnMajor();
    final double[][] rows = new double[4][4];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        rows[row][column] = values[column * 4 + row];
      }
    }
    return rows;
  }

  /** 独立的普通 double 行主序乘法（k = 0..3 顺序，无 FMA）。 */
  private static double[][] independentMultiply(double[][] left, double[][] right) {
    final double[][] product = new double[4][4];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        double sum = +0.0D;
        for (int k = 0; k < 4; k++) {
          sum += left[row][k] * right[k][column];
        }
        product[row][column] = sum;
      }
    }
    return product;
  }

  /** {@code left * right} 相对单位矩阵的最大绝对元素残差（独立计算）。 */
  private static double maxAbsResidualAgainstIdentity(double[][] left, double[][] right) {
    final double[][] product = independentMultiply(left, right);
    double worst = 0.0D;
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final double expected = (row == column) ? 1.0D : 0.0D;
        worst = Math.max(worst, Math.abs(product[row][column] - expected));
      }
    }
    return worst;
  }

  /** infinity norm：每行 abs 总和的最大值。 */
  private static double infinityNorm(double[][] rows) {
    double worst = 0.0D;
    for (int row = 0; row < 4; row++) {
      double sum = +0.0D;
      for (int column = 0; column < 4; column++) {
        sum += Math.abs(rows[row][column]);
      }
      worst = Math.max(worst, sum);
    }
    return worst;
  }

  /** 对角矩阵。 */
  private static FrameMatrix diagonal(double a, double b, double c, double d) {
    return fromRows(new double[][] {
      {a, 0.0D, 0.0D, 0.0D},
      {0.0D, b, 0.0D, 0.0D},
      {0.0D, 0.0D, c, 0.0D},
      {0.0D, 0.0D, 0.0D, d}
    });
  }

  /** 平移矩阵（列向量语义：M[r,3] 为平移量）。 */
  private static FrameMatrix translation(double tx, double ty, double tz) {
    return fromRows(new double[][] {
      {1.0D, 0.0D, 0.0D, tx},
      {0.0D, 1.0D, 0.0D, ty},
      {0.0D, 0.0D, 1.0D, tz},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });
  }

  /** 绕 Z 轴 90° 旋转矩阵（把 +X 送到 +Y）。 */
  private static FrameMatrix rotationZ90() {
    return fromRows(new double[][] {
      {0.0D, -1.0D, 0.0D, 0.0D},
      {1.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 1.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });
  }

  // ------------------------------------------------------------------
  // golden：identity / 平移 / 90°旋转 / 非 uniform scale / 透视
  // ------------------------------------------------------------------

  @Test
  void identityIsExactUnitMatrixInColumnMajorOrder() {
    final double[] expected = {
      1.0D, 0.0D, 0.0D, 0.0D,
      0.0D, 1.0D, 0.0D, 0.0D,
      0.0D, 0.0D, 1.0D, 0.0D,
      0.0D, 0.0D, 0.0D, 1.0D
    };
    final FrameMatrix identity = MatrixMath.identity();
    for (int index = 0; index < 16; index++) {
      final int at = index;
      assertEquals(expected[at], identity.columnMajor()[at], 0.0D,
          "identity column-major [" + at + "]");
    }
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final double want = (row == column) ? 1.0D : 0.0D;
        assertEquals(want, identity.get(row, column), 0.0D,
            "identity get(" + row + "," + column + ")");
      }
    }
  }

  @Test
  void identityGetterArrayIsIndependentClonePerCall() {
    final FrameMatrix identity = MatrixMath.identity();
    final double[] first = identity.columnMajor();
    first[0] = 42.0D;
    first[5] = 42.0D;
    final FrameMatrix second = MatrixMath.identity();
    assertEquals(1.0D, second.get(0, 0), 0.0D);
    assertEquals(1.0D, second.get(1, 1), 0.0D);
    assertEquals(1.0D, identity.get(0, 0), 0.0D, "污染 getter 副本后原对象不变");
    assertNotSame(first, identity.columnMajor(), "每次 columnMajor() 返回新副本");
  }

  @Test
  void identityIsMultiplicativeNeutralOnBothSides() {
    final FrameMatrix[] samples = {
      translation(3.0D, -4.5D, 0.25D),
      rotationZ90(),
      diagonal(2.0D, 3.0D, 4.0D, 1.0D),
      fromRows(new double[][] {
        {1.0D, 2.0D, 3.0D, 4.0D},
        {5.0D, 6.0D, 7.0D, 8.0D},
        {9.0D, 10.0D, 11.0D, 12.0D},
        {13.0D, 14.0D, 15.0D, 16.0D}
      })
    };
    final FrameMatrix identity = MatrixMath.identity();
    for (final FrameMatrix sample : samples) {
      assertExactMatrix(sample, MatrixMath.multiply(identity, sample), "identity * M");
      assertExactMatrix(sample, MatrixMath.multiply(sample, identity), "M * identity");
    }
  }

  /** 全部 16 个分量精确相等（容差 0）。 */
  private static void assertExactMatrix(FrameMatrix expected, FrameMatrix actual, String message) {
    final double[] want = expected.columnMajor();
    final double[] got = actual.columnMajor();
    for (int index = 0; index < 16; index++) {
      final int at = index;
      assertEquals(want[at], got[at], 0.0D, message + " 第 " + at + " 个 column-major 分量");
    }
  }

  @Test
  void translationMatrixTransformsPointAndLeavesDirectionAlone() {
    final FrameMatrix translate = translation(10.0D, -2.0D, 0.5D);
    final Vector4 point = new Vector4(1.0D, 1.0D, 1.0D, 1.0D);
    final Vector4 direction = new Vector4(1.0D, 1.0D, 1.0D, 0.0D);

    assertEquals(new Vector4(11.0D, -1.0D, 1.5D, 1.0D), MatrixMath.transform(translate, point),
        "w=1 的点被平移");
    assertEquals(new Vector4(1.0D, 1.0D, 1.0D, 0.0D), MatrixMath.transform(translate, direction),
        "w=0 的方向不被平移");
    assertFalse(MatrixMath.transform(translate, point).equals(MatrixMath.transform(translate, direction)),
        "点与方向的结果必须不同，不得做归一化/推断");
  }

  @Test
  void ninetyDegreeRotationGoldenSendsXToYWithoutTrigonometry() {
    final FrameMatrix rotate = rotationZ90();
    assertEquals(new Vector4(0.0D, 1.0D, 0.0D, 1.0D),
        MatrixMath.transform(rotate, new Vector4(1.0D, 0.0D, 0.0D, 1.0D)), "90° 把 +X 送到 +Y");
    assertEquals(new Vector4(-1.0D, 0.0D, 0.0D, 1.0D),
        MatrixMath.transform(rotate, new Vector4(0.0D, 1.0D, 0.0D, 1.0D)), "90° 把 +Y 送到 -X");
    assertEquals(new Vector4(0.0D, 0.0D, 1.0D, 1.0D),
        MatrixMath.transform(rotate, new Vector4(0.0D, 0.0D, 1.0D, 1.0D)), "Z 轴不动");
    assertEquals(new Vector4(0.0D, 0.0D, 0.0D, 1.0D),
        MatrixMath.transform(rotate, new Vector4(0.0D, 0.0D, 0.0D, 1.0D)), "原点在该矩阵下不动");
    assertEquals(new Vector4(0.0D, 0.0D, 0.0D, 0.0D),
        MatrixMath.transform(rotate, new Vector4(0.0D, 0.0D, 0.0D, 0.0D)), "零向量映射到零向量");
  }

  /** 绕 Z 轴 180° 旋转矩阵（用于 90° 自乘的独立期望值）。 */
  private static FrameMatrix rotationZ180() {
    return fromRows(new double[][] {
      {-1.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, -1.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 1.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });
  }

  @Test
  void ninetyDegreeRotationSquaredEqualsOneEightyDegrees() {
    final FrameMatrix rotate = rotationZ90();
    assertExactMatrix(rotationZ180(), MatrixMath.multiply(rotate, rotate), "90° * 90° = 180°");
    assertExactMatrix(MatrixMath.identity(), MatrixMath.multiply(rotationZ180(), rotationZ180()),
        "180° * 180° = identity");
  }

  @Test
  void nonUniformScaleGolden() {
    final FrameMatrix scale = diagonal(2.0D, 0.5D, -3.0D, 1.0D);
    assertEquals(new Vector4(4.0D, 0.5D, -3.0D, 1.0D),
        MatrixMath.transform(scale, new Vector4(2.0D, 1.0D, 1.0D, 1.0D)), "非 uniform scale");
    assertEquals(new Vector4(4.0D, 0.5D, -3.0D, 0.0D),
        MatrixMath.transform(scale, new Vector4(2.0D, 1.0D, 1.0D, 0.0D)),
        "w=0 的方向同样只按 scale 缩放，不加平移");
  }

  @Test
  void nullArgumentsAreIllegalArgumentForEveryMethod() {
    final FrameMatrix matrix = MatrixMath.identity();
    final Vector4 vector = new Vector4(1.0D, 2.0D, 3.0D, 1.0D);
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(null, matrix)),
        () -> assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(matrix, null)),
        () -> assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(null, null)),
        () -> assertThrows(IllegalArgumentException.class, () -> MatrixMath.transform(null, vector)),
        () -> assertThrows(IllegalArgumentException.class, () -> MatrixMath.transform(matrix, null)),
        () -> assertThrows(IllegalArgumentException.class, () -> MatrixMath.inverse(null)));
  }

  @Test
  void vector4RejectsNonFiniteComponents() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> new Vector4(Double.NaN, 0.0D, 0.0D, 1.0D)),
        () -> assertThrows(IllegalArgumentException.class, () -> new Vector4(0.0D, Double.NaN, 0.0D, 1.0D)),
        () -> assertThrows(IllegalArgumentException.class, () -> new Vector4(0.0D, 0.0D, Double.NaN, 1.0D)),
        () -> assertThrows(IllegalArgumentException.class, () -> new Vector4(0.0D, 0.0D, 0.0D, Double.NaN)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new Vector4(Double.POSITIVE_INFINITY, 0.0D, 0.0D, 1.0D)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new Vector4(0.0D, 0.0D, 0.0D, Double.NEGATIVE_INFINITY)));
    assertNotNull(new Vector4(-0.0D, 0.0D, 0.0D, 0.0D), "-0.0 是有限值，必须被接受");
  }

  // ------------------------------------------------------------------
  // 代数方向：column vectors、right 先作用、行列索引、w=1/w=0
  // ------------------------------------------------------------------

  @Test
  void multiplyReturnsLeftTimesRightWithRightAppliedFirst() {
    final FrameMatrix left = fromRows(new double[][] {
      {1.0D, 2.0D, 0.0D, 5.0D},
      {0.0D, 1.0D, 3.0D, -1.0D},
      {2.0D, 0.0D, 1.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });
    final FrameMatrix right = fromRows(new double[][] {
      {0.0D, -1.0D, 0.0D, 4.0D},
      {1.0D, 0.0D, 0.0D, -2.0D},
      {0.0D, 0.0D, 2.0D, 7.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });
    assertExactMatrix(fromRows(independentMultiply(rowsOf(left), rowsOf(right))),
        MatrixMath.multiply(left, right), "multiply(left,right) 必须是 left*right");

    final Vector4 probe = new Vector4(0.5D, -1.5D, 2.0D, 1.0D);
    final Vector4 viaProduct = MatrixMath.transform(MatrixMath.multiply(left, right), probe);
    final Vector4 viaSteps = MatrixMath.transform(left, MatrixMath.transform(right, probe));
    assertEquals(viaSteps.x(), viaProduct.x(), 1e-12D, "right 先作用：x");
    assertEquals(viaSteps.y(), viaProduct.y(), 1e-12D, "right 先作用：y");
    assertEquals(viaSteps.z(), viaProduct.z(), 1e-12D, "right 先作用：z");
    assertEquals(viaSteps.w(), viaProduct.w(), 1e-12D, "right 先作用：w");
  }

  @Test
  void multiplyIsNotCommutativeForTranslationAndRotation() {
    final FrameMatrix translate = translation(10.0D, 0.0D, 0.0D);
    final FrameMatrix rotate = rotationZ90();
    final FrameMatrix rotateThenTranslate = MatrixMath.multiply(translate, rotate);
    final FrameMatrix translateThenRotate = MatrixMath.multiply(rotate, translate);
    assertFalse(rotateThenTranslate.equals(translateThenRotate),
        "平移与旋转不可交换：right 先作用决定了差别");
    assertExactMatrix(fromRows(independentMultiply(rowsOf(translate), rowsOf(rotate))),
        rotateThenTranslate, "translate*rotate 独立期望");
    assertExactMatrix(fromRows(independentMultiply(rowsOf(rotate), rowsOf(translate))),
        translateThenRotate, "rotate*translate 独立期望");
    // 校验方向性：multiply(translate, rotate) 中 rotate 先作用，故 +X 先到 +Y 再平移 +10X。
    assertEquals(new Vector4(10.0D, 1.0D, 0.0D, 1.0D),
        MatrixMath.transform(rotateThenTranslate, new Vector4(1.0D, 0.0D, 0.0D, 1.0D)),
        "translate*rotate：rotate 先作用，+X 先到 +Y 再平移 +10X");
    assertEquals(new Vector4(0.0D, 11.0D, 0.0D, 1.0D),
        MatrixMath.transform(translateThenRotate, new Vector4(1.0D, 0.0D, 0.0D, 1.0D)),
        "rotate*translate：translate 先作用，+X 先平移 +10X 再旋转到 +Y");
  }

  @Test
  void getUsesColumnMajorIndexingExactly() {
    final double[] values = new double[16];
    for (int index = 0; index < 16; index++) {
      values[index] = index + 1.0D;
    }
    final FrameMatrix matrix = new FrameMatrix(values);
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        assertEquals(column * 4 + row + 1.0D, matrix.get(row, column), 0.0D,
            "get(" + row + "," + column + ") 必须是 column*4+row 下标");
      }
    }
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> matrix.get(-1, 0)),
        () -> assertThrows(IllegalArgumentException.class, () -> matrix.get(4, 0)),
        () -> assertThrows(IllegalArgumentException.class, () -> matrix.get(0, -1)),
        () -> assertThrows(IllegalArgumentException.class, () -> matrix.get(0, 4)));
  }

  @Test
  void pointAndDirectionDifferUnderTranslationInWComponent() {
    final FrameMatrix matrix = fromRows(new double[][] {
      {1.0D, 0.0D, 0.0D, 3.0D},
      {0.0D, 1.0D, 0.0D, -4.0D},
      {0.0D, 0.0D, 1.0D, 8.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });
    final Vector4 point = MatrixMath.transform(matrix, new Vector4(0.0D, 0.0D, 0.0D, 1.0D));
    final Vector4 direction = MatrixMath.transform(matrix, new Vector4(0.0D, 0.0D, 0.0D, 0.0D));
    assertEquals(new Vector4(3.0D, -4.0D, 8.0D, 1.0D), point, "w=1 的点被平移");
    assertEquals(new Vector4(0.0D, 0.0D, 0.0D, 0.0D), direction, "w=0 的方向不被平移");
    assertEquals(1.0D, point.w(), 0.0D, "contract 不做 w 除法：w 保持 1");
  }

  @Test
  void transformChecksEveryRowWithIndependentExpectation() {
    final FrameMatrix matrix = fromRows(new double[][] {
      {1.0D, 2.0D, 3.0D, 4.0D},
      {5.0D, 6.0D, 7.0D, 8.0D},
      {9.0D, 10.0D, 11.0D, 12.0D},
      {13.0D, 14.0D, 15.0D, 16.0D}
    });
    final Vector4 vector = new Vector4(1.0D, -2.0D, 0.5D, 3.0D);
    final double[] components = {vector.x(), vector.y(), vector.z(), vector.w()};
    final double[][] rows = rowsOf(matrix);
    final double[] expected = new double[4];
    for (int row = 0; row < 4; row++) {
      double sum = +0.0D;
      for (int k = 0; k < 4; k++) {
        sum += rows[row][k] * components[k];
      }
      expected[row] = sum;
    }
    assertEquals(new Vector4(expected[0], expected[1], expected[2], expected[3]),
        MatrixMath.transform(matrix, vector), "transform 第 r 行 = M[r,0]*x+...+M[r,3]*w");
  }

  @Test
  void inputsAreNeverMutatedAndGetterPollutionDoesNotAffectOperations() {
    final double[] payload = {
      1.0D, 0.0D, 0.0D, 0.0D,
      0.0D, 1.0D, 0.0D, 0.0D,
      0.0D, 0.0D, 1.0D, 0.0D,
      7.0D, -8.0D, 9.0D, 1.0D
    };
    final double[] pristine = payload.clone();
    final FrameMatrix matrix = new FrameMatrix(payload);
    payload[0] = 999.0D;
    assertEquals(1.0D, matrix.get(0, 0), 0.0D, "构造后修改输入数组不得影响矩阵");

    final FrameMatrix identity = MatrixMath.identity();
    final Vector4 probe = new Vector4(1.0D, 2.0D, 3.0D, 1.0D);
    final Vector4 before = MatrixMath.transform(matrix, probe);
    final FrameMatrix productBefore = MatrixMath.multiply(matrix, identity);
    final Optional<FrameMatrix> inverseBefore = MatrixMath.inverse(matrix);

    final double[] polluted = matrix.columnMajor();
    polluted[3] = -12345.0D;
    polluted[12] = 54321.0D;
    final double[] identityPolluted = identity.columnMajor();
    identityPolluted[0] = -7.0D;
    identityPolluted[10] = 7.0D;

    assertEquals(before, MatrixMath.transform(matrix, probe), "getter 数组污染不得影响 transform");
    assertExactMatrix(productBefore, MatrixMath.multiply(matrix, identity),
        "getter 数组污染不得影响 multiply");
    assertEquals(inverseBefore.isPresent(), MatrixMath.inverse(matrix).isPresent(),
        "getter 数组污染不得影响 inverse 可用性");
    assertArrayEqualsExact(pristine, matrix.columnMajor(), "原矩阵 16 个分量在运算后不变");
  }

  /** 16 个分量精确逐项比较。 */
  private static void assertArrayEqualsExact(double[] expected, double[] actual, String message) {
    assertEquals(16, actual.length, message + " 长度");
    for (int index = 0; index < 16; index++) {
      final int at = index;
      assertEquals(expected[at], actual[at], 0.0D, message + " 下标 " + at);
    }
  }

  // ------------------------------------------------------------------
  // 有限中间结果规则：乘积/求和任一非有限即 IAE，不靠相消恢复
  // ------------------------------------------------------------------

  @Test
  void multiplyRejectsNonFiniteProductEvenIfLaterCancellationWouldRecover() {
    final FrameMatrix huge = diagonal(1.0e308D, 1.0D, 1.0D, 1.0D);
    final FrameMatrix alsoHuge = diagonal(1.0e308D, 1.0D, 1.0D, 1.0D);
    // 数学上 1e308*1e308 溢出；即使别的项能相消也不得恢复值。
    assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(huge, alsoHuge),
        "非有限乘积必须立即 IAE，不得以随后相消恢复");

    // 前置：两项乘积确实互为相反数（+Infinity 与 -Infinity），若允许先溢出再相消，实数上可回到 0。
    final double positiveOverflowProduct = 1.0e308D * 1.0e308D;
    final double negativeOverflowProduct = 1.0e308D * -1.0e308D;
    assertTrue(Double.isInfinite(positiveOverflowProduct) && positiveOverflowProduct > 0.0D,
        "前置：第一项乘积必须为 +Infinity，实际 " + positiveOverflowProduct);
    assertTrue(Double.isInfinite(negativeOverflowProduct) && negativeOverflowProduct < 0.0D,
        "前置：第二项乘积必须为 -Infinity，实际 " + negativeOverflowProduct);
    assertTrue(positiveOverflowProduct == -negativeOverflowProduct,
        "前置：两项乘积必须真正互为相反数，实际 " + positiveOverflowProduct + " 与 " + negativeOverflowProduct);

    final FrameMatrix cancel = fromRows(new double[][] {
      {1.0e308D, 1.0e308D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final FrameMatrix oppositeLaterTerm = fromRows(new double[][] {
      {1.0e308D, 0.0D, 0.0D, 0.0D},
      {-1.0e308D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    // 乘积 (0,0) = 1e308*1e308 + 1e308*(-1e308)：按实数精确相消为 0，
    // 但 double 下先得到 +Infinity 再相加，中间结果非有限，必须 IAE 而不得"相消恢复"。
    assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(cancel, oppositeLaterTerm),
        "乘积溢出不得靠后续真正相反项相消恢复");
  }

  @Test
  void multiplyRejectsNonFiniteSumEvenWhenAllProductsAreFinite() {
    final FrameMatrix left = fromRows(new double[][] {
      {1.0e308D, 1.0e308D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final FrameMatrix right = fromRows(new double[][] {
      {1.0D, 0.0D, 0.0D, 0.0D},
      {1.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    // 每个 product 都是 1e308（有限），但 k 顺序累加到第二项即溢出为 Infinity。
    assertTrue(Double.isFinite(1.0e308D * 1.0D), "前置：单项乘积必须有限");
    assertTrue(Double.isInfinite((0.0D + 1.0e308D) + 1.0e308D),
        "前置：按 k 顺序递增累加必须溢出为 Infinity");
    assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(left, right),
        "product 有限但 sum 溢出必须 IAE");

    // sum 版相消：多一个真正相反的后续项，中间和先为 +Infinity，精确总和却是有限的 1e308。
    final FrameMatrix sumCancelLeft = fromRows(new double[][] {
      {1.0e308D, 1.0e308D, -1.0e308D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final FrameMatrix sumCancelRight = fromRows(new double[][] {
      {1.0D, 0.0D, 0.0D, 0.0D},
      {1.0D, 0.0D, 0.0D, 0.0D},
      {1.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final double overflowedPartialSum = (0.0D + 1.0e308D) + 1.0e308D;
    assertTrue(Double.isInfinite(overflowedPartialSum) && overflowedPartialSum > 0.0D,
        "前置：前两项累加必须为 +Infinity");
    // 前置：按实数精确计算，三项总和恰好是有限的 1e308；若允许"溢出后再恢复"，此处应得有限值。
    final BigDecimal firstProduct = new BigDecimal(1.0e308D);
    final BigDecimal exactTotal = firstProduct.add(firstProduct).subtract(firstProduct);
    assertEquals(0, exactTotal.compareTo(firstProduct), "前置：精确总和必须等于 1e308");
    assertTrue(Double.isFinite(exactTotal.doubleValue()) && exactTotal.doubleValue() == 1.0e308D,
        "前置：精确总和是有限可表示值，实际 " + exactTotal);
    assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(sumCancelLeft, sumCancelRight),
        "sum 中间溢出后不得靠后续相反项相消恢复");
  }

  @Test
  void transformRejectsNonFiniteIntermediateProductAndSum() {
    final FrameMatrix overflowProduct = diagonal(Double.MAX_VALUE, 1.0D, 1.0D, 1.0D);
    assertThrows(IllegalArgumentException.class,
        () -> MatrixMath.transform(overflowProduct, new Vector4(2.0D, 0.0D, 0.0D, 0.0D)),
        "乘积溢出必须 IAE");

    final FrameMatrix overflowSum = fromRows(new double[][] {
      {Double.MAX_VALUE, Double.MAX_VALUE, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    assertThrows(IllegalArgumentException.class,
        () -> MatrixMath.transform(overflowSum, new Vector4(1.0D, 1.0D, 0.0D, 0.0D)),
        "product 有限但 sum 溢出必须 IAE");
  }

  @Test
  void finiteInputsDoNotGuaranteeSuccessButOrdinaryValuesSucceed() {
    final FrameMatrix ordinary = translation(1.0D, 2.0D, 3.0D);
    assertNotNull(MatrixMath.multiply(ordinary, ordinary), "普通有限输入必须成功");
    assertNotNull(MatrixMath.transform(ordinary, new Vector4(1.0D, 2.0D, 3.0D, 1.0D)));
  }

  // ------------------------------------------------------------------
  // inverse：singular / overflow / 病态 / 极端对角
  // ------------------------------------------------------------------

  @Test
  void inverseRejectsZeroMatrixAndRepeatedRows() {
    final FrameMatrix zero = new FrameMatrix(new double[16]);
    assertTrue(MatrixMath.inverse(zero).isEmpty(), "零矩阵必须 empty");

    final FrameMatrix repeated = fromRows(new double[][] {
      {1.0D, 2.0D, 3.0D, 4.0D},
      {1.0D, 2.0D, 3.0D, 4.0D},
      {5.0D, 6.0D, 7.0D, 8.0D},
      {9.0D, 10.0D, 11.0D, 12.0D}
    });
    assertTrue(MatrixMath.inverse(repeated).isEmpty(), "重复行奇异必须 empty");

    final FrameMatrix dependent = fromRows(new double[][] {
      {1.0D, 2.0D, 3.0D, 4.0D},
      {2.0D, 4.0D, 6.0D, 8.0D},
      {0.0D, 1.0D, 1.0D, 1.0D},
      {0.0D, 0.0D, 1.0D, 1.0D}
    });
    assertTrue(MatrixMath.inverse(dependent).isEmpty(), "线性相关行必须 empty");
  }

  @Test
  void inverseIsEmptyWhenPivotReciprocalOverflows() {
    final FrameMatrix tiny = diagonal(1.0e-320D, 1.0D, 1.0D, 1.0D);
    // 1/1e-320 溢出为 Infinity：必须 empty，不得返回含 Infinity 的伪造结果。
    assertTrue(MatrixMath.inverse(tiny).isEmpty(), "极小 pivot 倒数溢出必须 empty");
  }

  @Test
  void inverseIsEmptyWhenEliminationOverflowsToNonFinite() {
    // 第 0 列消元：row1 -= (1/1) * row0，使 row1 第 1 列成为 -MAX_VALUE - MAX_VALUE = -Infinity。
    // 该矩阵精确行列式 = -2 * MAX_VALUE（非零，见下方 BigInteger 前置），所以它不是"重复行
    // singular"；拒绝不可能来自"精确奇异"分支，只能来自对含 ±MAX_VALUE 的第 1 列做行消元减法时
    // 出现的非有限中间值（-MAX - MAX = -Infinity，前置已证）。
    final FrameMatrix eliminationOverflow = fromRows(new double[][] {
      {1.0D, Double.MAX_VALUE, 0.0D, 0.0D},
      {1.0D, -Double.MAX_VALUE, 0.0D, 0.0D},
      {0.0D, 0.0D, 1.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });

    // 前置 1：普通 double 下 -MAX - MAX 确实溢出为 -Infinity（不是 NaN，也不是饱和的 -MAX）。
    final double overflowedElimination = -Double.MAX_VALUE - Double.MAX_VALUE;
    assertTrue(Double.isInfinite(overflowedElimination) && overflowedElimination < 0.0D,
        "前置：-MAX - MAX 必须是 -Infinity，实际 " + overflowedElimination);
    assertEquals(Double.NEGATIVE_INFINITY, overflowedElimination, 0.0D, "前置：溢出值必须精确为负无穷");
    assertFalse(Double.isNaN(overflowedElimination), "前置：溢出结果不是 NaN");

    // 前置 2：两行第 1 列符号相反，明确排除"重复行"这一平凡解释。
    final double[][] eliminationRows = rowsOf(eliminationOverflow);
    assertEquals(Double.MAX_VALUE, eliminationRows[0][1], 0.0D, "前置：row0 第 1 列是 +MAX_VALUE");
    assertEquals(-Double.MAX_VALUE, eliminationRows[1][1], 0.0D, "前置：row1 第 1 列是 -MAX_VALUE");
    assertTrue(eliminationRows[0][1] != eliminationRows[1][1], "前置：两行不同，不属于重复行 singular");

    // 前置 3：BigInteger 独立精确证明行列式为 -2 * MAX_VALUE，非零。
    final BigInteger maxValueExact = BigInteger.valueOf(9007199254740991L).shiftLeft(971);
    assertEquals(Double.MAX_VALUE, maxValueExact.doubleValue(), 0.0D,
        "前置：MAX_VALUE 的精确整数表示为 (2^53-1)*2^971");
    final BigInteger exactDeterminant = maxValueExact.negate().subtract(maxValueExact);
    assertEquals(BigInteger.valueOf(-2L).multiply(maxValueExact), exactDeterminant,
        "前置：精确行列式必须是 -2 * MAX_VALUE");
    assertTrue(exactDeterminant.signum() != 0, "前置：精确行列式非零，故该矩阵非奇异");

    // 结论：必须 empty，且不得以异常形式泄漏内部数值失败。
    final Optional<FrameMatrix> candidate = assertDoesNotThrow(
        () -> MatrixMath.inverse(eliminationOverflow), "消元溢出必须 empty，不得抛异常");
    assertTrue(candidate.isEmpty(), "消元过程溢出为 -Infinity 必须 empty");
  }

  @Test
  void inverseRejectsIllConditionedDiagonalInsideAbsoluteThreshold() {
    final FrameMatrix illConditioned = diagonal(1.0D, 1.0D, 1.0D, 1.0e-13D);
    assertTrue(MatrixMath.inverse(illConditioned).isEmpty(),
        "diag(1,1,1,1e-13) 病态：乘积残差/norm 门槛必须拒绝");
  }

  @Test
  void inverseHandlesExtremeDiagonalsWithoutAbsolutePivotThreshold() {
    // diag(1e-100)：逆分量是 1e100，量级巨大。若用绝对 1e-8 容差，边缘数值差异会掩盖错误，
    // 因此这里必须同时给出"相对容差"和"双向残差"两道独立门槛，使全零/错量级逆无法通过。
    final Optional<FrameMatrix> tiny = MatrixMath.inverse(diagonal(1.0e-100D, 1.0e-100D, 1.0e-100D, 1.0e-100D));
    assertTrue(tiny.isPresent(), "diag(1e-100) 必须正常求逆，不得用绝对 pivot 阈值错误拒绝");
    final FrameMatrix tinyInverse = tiny.orElseThrow();
    final double[][] tinyRows = rowsOf(tinyInverse);
    final double expectedTiny = 1.0D / 1.0e-100D;
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final double want = (row == column) ? expectedTiny : 0.0D;
        // 相对容差：want 非零时按 |want| * 1e-12 判定；want 为 0 时只允许精确 0（全零逆在此仍会因
        // 对角项全错而失败）。
        final double relativeTolerance = (want == 0.0D) ? 0.0D : Math.abs(want) * 1e-12D;
        assertEquals(want, tinyRows[row][column], relativeTolerance,
            "diag(1e-100) 的 inverse 分量 (" + row + "," + column + ") 必须匹配精确解析值（相对容差）");
      }
    }
    final double tinyResidual = Math.max(
        maxAbsResidualAgainstIdentity(rowsOf(diagonal(1.0e-100D, 1.0e-100D, 1.0e-100D, 1.0e-100D)), tinyRows),
        maxAbsResidualAgainstIdentity(tinyRows, rowsOf(diagonal(1.0e-100D, 1.0e-100D, 1.0e-100D, 1.0e-100D))));
    assertTrue(tinyResidual <= RESIDUAL_LIMIT,
        "diag(1e-100) 的双向残差必须在 1e-8 内，实际 " + tinyResidual);

    // diag(1e100)：逆分量是 1e-100。这是 M2 的核心——绝不能只写"绝对 1e-8 内"，
    // 因为 |0 - 1e-100| < 1e-8，全零逆会假通过。此处相对容差 + 逐项精确性 + 双向残差三重把关。
    final Optional<FrameMatrix> huge = MatrixMath.inverse(diagonal(1.0e100D, 1.0e100D, 1.0e100D, 1.0e100D));
    assertTrue(huge.isPresent(), "diag(1e100) 必须正常求逆");
    final FrameMatrix hugeInverse = huge.orElseThrow();
    final double[][] hugeRows = rowsOf(hugeInverse);
    final double expectedHuge = 1.0D / 1.0e100D;
    assertEquals(1.0e-100D, expectedHuge, 0.0D, "前置：1/1e100 精确等于字面量 1e-100");
    assertTrue(expectedHuge < ELEMENT_TOLERANCE,
        "前置：目标分量 1e-100 远小于绝对容差 1e-8，故绝对容差在此无法区分全零逆");

    // 反证前置：全零候选在"仅绝对容差"下会通过，因此下面的断言必须依赖相对容差与残差。
    boolean zeroMatrixPassesAbsoluteTolerance = true;
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final double want = (row == column) ? expectedHuge : 0.0D;
        if (Math.abs(want - 0.0D) > ELEMENT_TOLERANCE + Math.abs(want) * 1e-12D) {
          zeroMatrixPassesAbsoluteTolerance = false;
        }
      }
    }
    assertTrue(zeroMatrixPassesAbsoluteTolerance,
        "前置：全零矩阵确实能骗过纯绝对 1e-8 容差，所以 M2 必须改用相对容差");

    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final double want = (row == column) ? expectedHuge : 0.0D;
        final double relativeTolerance = (want == 0.0D) ? 0.0D : Math.abs(want) * 1e-12D;
        assertEquals(want, hugeRows[row][column], relativeTolerance,
            "diag(1e100) 的 inverse 分量 (" + row + "," + column + ") 必须匹配精确解析值（相对容差）");
      }
    }
    final double hugeResidual = Math.max(
        maxAbsResidualAgainstIdentity(rowsOf(diagonal(1.0e100D, 1.0e100D, 1.0e100D, 1.0e100D)), hugeRows),
        maxAbsResidualAgainstIdentity(hugeRows, rowsOf(diagonal(1.0e100D, 1.0e100D, 1.0e100D, 1.0e100D))));
    assertTrue(hugeResidual <= RESIDUAL_LIMIT,
        "diag(1e100) 的双向残差必须在 1e-8 内，实际 " + hugeResidual);
    assertTrue(Double.isFinite(hugeRows[0][0]) && hugeRows[0][0] != 0.0D,
        "diag(1e100) 的逆对角项必须是非零有限值，实际 " + hugeRows[0][0]);
  }

  @Test
  void inverseOfIdentityAndGoldenMatricesMatchesIndependentExpectation() {
    final Optional<FrameMatrix> identityInverse = MatrixMath.inverse(MatrixMath.identity());
    assertTrue(identityInverse.isPresent(), "单位矩阵必须可逆");
    assertExactMatrix(MatrixMath.identity(), identityInverse.orElseThrow(), "identity^-1 = identity");

    final Optional<FrameMatrix> translateInverse = MatrixMath.inverse(translation(3.0D, -4.0D, 5.0D));
    assertTrue(translateInverse.isPresent(), "平移矩阵必须可逆");
    assertExactMatrix(translation(-3.0D, 4.0D, -5.0D), translateInverse.orElseThrow(),
        "平移矩阵的逆是反向平移");

    final Optional<FrameMatrix> scaleInverse = MatrixMath.inverse(diagonal(2.0D, 4.0D, 8.0D, 1.0D));
    assertTrue(scaleInverse.isPresent(), "对角矩阵必须可逆");
    assertExactMatrix(diagonal(0.5D, 0.25D, 0.125D, 1.0D), scaleInverse.orElseThrow(), "对角逆");
  }

  @Test
  void inverseProducesTwoSidedIdentityResidualsForGoldenMatrices() {
    final FrameMatrix[] samples = {
      MatrixMath.identity(),
      translation(3.0D, -4.0D, 5.0D),
      rotationZ90(),
      diagonal(2.0D, 4.0D, 8.0D, 1.0D),
      fromRows(new double[][] {
        {1.0D, 2.0D, 3.0D, 4.0D},
        {0.0D, 1.0D, 5.0D, 6.0D},
        {0.0D, 0.0D, 1.0D, 7.0D},
        {0.0D, 0.0D, 0.0D, 1.0D}
      })
    };
    for (final FrameMatrix sample : samples) {
      final Optional<FrameMatrix> candidate = MatrixMath.inverse(sample);
      assertTrue(candidate.isPresent(), "golden 矩阵必须可逆");
      assertTrue(maxAbsResidualAgainstIdentity(rowsOf(sample), rowsOf(candidate.orElseThrow()))
          <= RESIDUAL_LIMIT, "M*candidate 残差必须在门槛内");
      assertTrue(maxAbsResidualAgainstIdentity(rowsOf(candidate.orElseThrow()), rowsOf(sample))
          <= RESIDUAL_LIMIT, "candidate*M 残差必须在门槛内");
    }
  }

  @Test
  void inverseDoesNotLeakIllegalArgumentExceptionFromInternalNumericFailure() {
    // 公共 multiply 对非有限中间结果抛 IAE；inverse 的数值失败必须是 empty 而不是该 IAE 泄漏。
    final FrameMatrix[] numericFailures = {
      new FrameMatrix(new double[16]),
      diagonal(1.0e-320D, 1.0D, 1.0D, 1.0D),
      diagonal(1.0D, 1.0D, 1.0D, 1.0e-13D),
      fromRows(new double[][] {
        {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE},
        {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE},
        {0.0D, 0.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 0.0D, 0.0D}
      })
    };
    for (final FrameMatrix failure : numericFailures) {
      assertFalse(MatrixMath.inverse(failure).isPresent(), "数值失败必须 empty");
    }
  }

  // ------------------------------------------------------------------
  // 独立 BigInteger determinant / cofactor / adjugate oracle（与生产实现无关）
  // ------------------------------------------------------------------

  /** 3x3 精确行列式。 */
  private static BigInteger determinant3(BigInteger[][] m) {
    final BigInteger a = m[0][0].multiply(m[1][1].multiply(m[2][2]).subtract(m[1][2].multiply(m[2][1])));
    final BigInteger b = m[0][1].multiply(m[1][0].multiply(m[2][2]).subtract(m[1][2].multiply(m[2][0])));
    final BigInteger c = m[0][2].multiply(m[1][0].multiply(m[2][1]).subtract(m[1][1].multiply(m[2][0])));
    return a.subtract(b).add(c);
  }

  /** 4x4 精确行列式：沿第 0 行做余子式展开。 */
  private static BigInteger determinant4(BigInteger[][] m) {
    BigInteger det = BigInteger.ZERO;
    for (int column = 0; column < 4; column++) {
      final BigInteger term = m[0][column].multiply(minor3(m, 0, column));
      det = ((column & 1) == 0) ? det.add(term) : det.subtract(term);
    }
    return det;
  }

  /** 划去第 {@code skipRow} 行、第 {@code skipColumn} 列后的 3x3 精确行列式（余子式）。 */
  private static BigInteger minor3(BigInteger[][] m, int skipRow, int skipColumn) {
    final BigInteger[][] minor = new BigInteger[3][3];
    int r = 0;
    for (int row = 0; row < 4; row++) {
      if (row == skipRow) {
        continue;
      }
      int c = 0;
      for (int column = 0; column < 4; column++) {
        if (column == skipColumn) {
          continue;
        }
        minor[r][c] = m[row][column];
        c++;
      }
      r++;
    }
    return determinant3(minor);
  }

  /** 伴随矩阵：cofactor[row][column] = (-1)^(row+column) * minor(row,column)，再转置。 */
  private static BigInteger[][] adjugate4(BigInteger[][] m) {
    final BigInteger[][] adjugate = new BigInteger[4][4];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final BigInteger cofactor = minor3(m, row, column);
        final BigInteger signed = (((row + column) & 1) == 0) ? cofactor : cofactor.negate();
        adjugate[column][row] = signed;
      }
    }
    return adjugate;
  }

  /** exact inverse row-major（adj/det），50 位十进制精度足够消除 oracle 自身舍入。 */
  private static BigDecimal[][] exactInverseRows(BigInteger[][] m, BigInteger det) {
    final BigInteger[][] adjugate = adjugate4(m);
    final BigDecimal divisor = new BigDecimal(det);
    final BigDecimal[][] inverse = new BigDecimal[4][4];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        inverse[row][column] =
            new BigDecimal(adjugate[row][column]).divide(divisor, new MathContext(50));
      }
    }
    return inverse;
  }

  /** oracle inverse 的 double 视图（仅用于门槛比较，精度损失远小于 1e-8 判据）。 */
  private static double[][] toDoubleRows(BigDecimal[][] values) {
    final double[][] rows = new double[4][4];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        rows[row][column] = values[row][column].doubleValue();
      }
    }
    return rows;
  }

  /** 整数 int 矩阵转 BigInteger 行数组。 */
  private static BigInteger[][] intRows(int[][] values) {
    final BigInteger[][] rows = new BigInteger[4][4];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        rows[row][column] = BigInteger.valueOf(values[row][column]);
      }
    }
    return rows;
  }

  /** 整数矩阵的 double 视图。 */
  private static double[][] toDoubleRows(BigInteger[][] values) {
    final double[][] rows = new double[4][4];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        rows[row][column] = values[row][column].doubleValue();
      }
    }
    return rows;
  }

  // ------------------------------------------------------------------
  // 样本生成与预筛：固定 seed、去重、调用生产 inverse 之前完成独立筛选
  // ------------------------------------------------------------------

  /** 线性同余生成器：固定 seed、可复现、纯 JDK。 */
  private static final class FixedSeedGenerator {
    private long state;

    FixedSeedGenerator(long seed) {
      this.state = seed;
    }

    /** 下一个 {@code long}。 */
    long nextLong() {
      state = state * 6364136223846793005L + 1442695040888963407L;
      return state;
    }

    /** {@code [-bound, bound]} 内的整数。 */
    int nextBounded(int bound) {
      return (int) Math.floorMod(nextLong(), 2L * bound + 1L) - bound;
    }
  }

  /** 一个通过独立预筛的应可用样本。 */
  private record OracleSample(BigInteger[][] integerRows, BigInteger determinant, BigDecimal[][] exactInverse,
                              double[][] asDouble) {
  }

  /** 16 个整数的紧凑键，用于固定去重。 */
  private static String sampleKey(BigInteger[][] rows) {
    final StringBuilder builder = new StringBuilder(64);
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        builder.append(rows[row][column]).append(',');
      }
    }
    return builder.toString();
  }

  /** 极大值绝对值（用于拒绝会溢出 double 的接近奇异样本）。 */
  private static BigInteger maxAbs(BigInteger[][] rows) {
    BigInteger max = BigInteger.ZERO;
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        max = max.max(rows[row][column].abs());
      }
    }
    return max;
  }

  /** 元素绝对值上限（生成预算）。 */
  private static final int GENERATED_ELEMENT_BOUND = 100;

  /** 预筛：oracle infinity-norm 乘积上限。 */
  private static final double ORACLE_NORM_PRODUCT_LIMIT = 1.0e4D;

  /** 预筛：oracle inverse infinity norm 上限。 */
  private static final double ORACLE_INVERSE_NORM_LIMIT = 1.0e3D;

  /** 要求生成的去重应可用样本数量（任务卡下限 1000）。 */
  private static final int ORACLE_SAMPLE_COUNT = 1000;

  /**
   * 生成指定数量的去重应可用样本。
   *
   * <p>全部筛选条件<strong>只</strong>来自本文件独立实现的 BigInteger det/cofactor/adjugate oracle：
   * 元素绝对值 {@code <= 100}、{@code det != 0}、oracle infinity-norm 乘积 {@code <= 1e4}、
   * oracle inverse infinity norm {@code <= 1e3}。调用生产 inverse <strong>之前</strong>即完成筛选与去重，
   * <strong>不</strong>参考生产 inverse 的成功、误差或残差。
   */
  private static List<OracleSample> generateOracleSamples(int requiredCount, long seed) {
    final FixedSeedGenerator generator = new FixedSeedGenerator(seed);
    final Set<String> seen = new HashSet<>();
    final List<OracleSample> samples = new ArrayList<>(requiredCount);
    int attempts = 0;
    while (samples.size() < requiredCount) {
      attempts++;
      if (attempts > 400000) {
        throw new IllegalStateException("固定 seed 下未能生成 " + requiredCount
            + " 个去重应可用样本，尝试次数 " + attempts + "，已收集 " + samples.size());
      }
      final BigInteger[][] rows = new BigInteger[4][4];
      boolean withinElementBound = true;
      for (int row = 0; row < 4; row++) {
        for (int column = 0; column < 4; column++) {
          final BigInteger value = BigInteger.valueOf(generator.nextBounded(GENERATED_ELEMENT_BOUND));
          rows[row][column] = value;
          if (value.abs().intValueExact() > GENERATED_ELEMENT_BOUND) {
            withinElementBound = false;
          }
        }
      }
      if (!withinElementBound || !seen.add(sampleKey(rows))) {
        continue;
      }
      final BigInteger determinant = determinant4(rows);
      if (determinant.signum() == 0) {
        continue;
      }
      final BigInteger[][] adjugate = adjugate4(rows);
      if (maxAbs(adjugate).compareTo(BigInteger.valueOf(1L << 60)) > 0) {
        // 极端伴随矩阵在 double 下可能自身溢出，属高条件探针范畴，不混入应可用样本。
        continue;
      }
      final BigDecimal[][] exactInverse = exactInverseRows(rows, determinant);
      final double[][] doubleRows = toDoubleRows(rows);
      final double[][] doubleInverse = toDoubleRows(exactInverse);
      if (!allFinite(doubleInverse)) {
        continue;
      }
      final double oracleInverseNorm = infinityNorm(doubleInverse);
      if (!(oracleInverseNorm <= ORACLE_INVERSE_NORM_LIMIT)) {
        continue;
      }
      final double oracleNormProduct = infinityNorm(doubleRows) * oracleInverseNorm;
      if (!(oracleNormProduct <= ORACLE_NORM_PRODUCT_LIMIT)) {
        continue;
      }
      samples.add(new OracleSample(rows, determinant, exactInverse, doubleRows));
    }
    return samples;
  }

  private static boolean allFinite(double[][] values) {
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        if (!Double.isFinite(values[row][column])) {
          return false;
        }
      }
    }
    return true;
  }

  // ------------------------------------------------------------------
  // 1000 组：独立 BigInteger det/cofactor/adjugate oracle 逐项验证
  // ------------------------------------------------------------------

  @Test
  void oneThousandIntegerMatricesMatchIndependentBigIntegerAdjugateOracle() {
    final List<OracleSample> samples = generateOracleSamples(ORACLE_SAMPLE_COUNT, 20261014L);
    assertEquals(ORACLE_SAMPLE_COUNT, samples.size(), "必须生成 1000 个去重应可用样本");
    final Set<String> distinct = new HashSet<>();
    int verified = 0;
    for (final OracleSample sample : samples) {
      assertTrue(distinct.add(sampleKey(sample.integerRows())), "样本必须两两不同");
      final int determinantSignum = sample.determinant().signum();
      assertTrue(determinantSignum != 0, "预筛必须排除奇异样本（det != 0）");
      assertTrue(maxAbs(sample.integerRows()).intValueExact() <= GENERATED_ELEMENT_BOUND,
          "每个元素绝对值必须 <= 100");

      final FrameMatrix matrix = fromRows(sample.asDouble());
      final Optional<FrameMatrix> candidate = MatrixMath.inverse(matrix);
      // 应可用样本：生产 inverse 若给出 empty 即直接失败，不吞拒绝。
      assertTrue(candidate.isPresent(),
          "预筛通过的可逆样本不得被 empty 拒绝，det=" + sample.determinant());
      final FrameMatrix actual = candidate.orElseThrow();

      for (int row = 0; row < 4; row++) {
        for (int column = 0; column < 4; column++) {
          final double expected = sample.exactInverse()[row][column].doubleValue();
          assertEquals(expected, actual.get(row, column), ELEMENT_TOLERANCE,
              "adjugate oracle 期望 inverse(" + row + "," + column + ")，det=" + sample.determinant());
        }
      }
      assertTrue(maxAbsResidualAgainstIdentity(sample.asDouble(), rowsOf(actual)) <= RESIDUAL_LIMIT,
          "M*candidate 残差必须 <= 1e-8，det=" + sample.determinant());
      assertTrue(maxAbsResidualAgainstIdentity(rowsOf(actual), sample.asDouble()) <= RESIDUAL_LIMIT,
          "candidate*M 残差必须 <= 1e-8，det=" + sample.determinant());
      verified++;
    }
    assertEquals(ORACLE_SAMPLE_COUNT, verified, "实际验证样本数必须是 1000");
    assertEquals(ORACLE_SAMPLE_COUNT, distinct.size(), "去重后样本数必须是 1000");
  }

  @Test
  void oracleSampleGenerationIsDeterministicUnderFixedSeed() {
    final List<OracleSample> first = generateOracleSamples(20, 777L);
    final List<OracleSample> second = generateOracleSamples(20, 777L);
    assertEquals(first.size(), second.size(), "固定 seed 必须可复现");
    for (int index = 0; index < first.size(); index++) {
      assertEquals(sampleKey(first.get(index).integerRows()), sampleKey(second.get(index).integerRows()),
          "固定 seed 下第 " + index + " 个样本必须一致");
      assertEquals(first.get(index).determinant(), second.get(index).determinant(), "det 必须一致");
    }
  }

  @Test
  void oracleIsIndependentOfProductionMultiply() {
    // 直接检验独立 adjugate oracle 自身：(adj(M) * M) / det == identity。
    final BigInteger[][] rows = intRows(new int[][] {
      {3, -7, 11, 2},
      {5, 4, -3, 9},
      {-8, 6, 13, -1},
      {7, 2, 9, 4}
    });
    final BigInteger determinant = determinant4(rows);
    assertTrue(determinant.signum() != 0, "固定样本的行列式必须非零，实际 det=" + determinant);
    final BigInteger[][] adjugate = adjugate4(rows);
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        BigInteger sum = BigInteger.ZERO;
        for (int k = 0; k < 4; k++) {
          sum = sum.add(adjugate[row][k].multiply(rows[k][column]));
        }
        final BigInteger expected = (row == column) ? determinant : BigInteger.ZERO;
        assertEquals(expected, sum, "adj(M)*M 必须等于 det*I，位置 (" + row + "," + column + ")");
      }
    }
  }

  // ------------------------------------------------------------------
  // 高条件 / 溢出拒绝探针（固定门槛，不混入应可用样本）
  // ------------------------------------------------------------------

  /** 固定的高条件拒绝探针样本：全部预期 empty。 */
  private static List<FrameMatrix> highConditionProbes() {
    final List<FrameMatrix> probes = new ArrayList<>();
    probes.add(diagonal(1.0D, 1.0D, 1.0D, 1.0e-13D));
    probes.add(diagonal(1.0D, 1.0e-14D, 1.0e-14D, 1.0e-14D));
    probes.add(diagonal(1.0e-320D, 1.0D, 1.0D, 1.0D));
    probes.add(fromRows(new double[][] {
      {1.0D, 1.0D, 0.0D, 0.0D},
      {1.0D, 1.0D + 1.0e-15D, 0.0D, 0.0D},
      {0.0D, 0.0D, 1.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    }));
    probes.add(fromRows(new double[][] {
      {1.0D, 2.0D, 3.0D, 4.0D},
      {2.0D, 4.0D + 1.0e-15D, 6.0D, 8.0D},
      {3.0D, 6.0D, 9.0D, 12.0D},
      {4.0D, 8.0D, 12.0D, 16.0D}
    }));
    probes.add(diagonal(1.0e160D, 1.0D, 1.0D, 1.0D));
    probes.add(diagonal(1.0e-160D, 1.0D, 1.0D, 1.0D));
    return probes;
  }

  @Test
  void highConditionProbesAreRejectedWithEmptyAndNeverFakeIdentity() {
    final List<FrameMatrix> probes = highConditionProbes();
    assertEquals(7, probes.size(), "探针数量固定");
    for (int index = 0; index < probes.size(); index++) {
      final Optional<FrameMatrix> result = MatrixMath.inverse(probes.get(index));
      final int at = index;
      assertTrue(result.isEmpty(), "高条件/溢出探针 " + at + " 必须 empty");
      if (result.isPresent()) {
        assertFalse(MatrixMath.identity().equals(result.orElseThrow()),
            "不得返回伪造单位矩阵（探针 " + at + "）");
      }
    }
  }

  @Test
  void uniformScalingDiagonalsAreInvertibleSoConditionGateIsNotAboutMagnitude() {
    // diag(1e160 四重) 与 diag(1e-160 四重) 的 condition 估计均为 1.0，必须可用：
    // 门槛是相对条件（norm 乘积），不是元素绝对幅值，也不存在绝对 pivot 阈值。
    final FrameMatrix huge = diagonal(1.0e160D, 1.0e160D, 1.0e160D, 1.0e160D);
    final FrameMatrix tiny = diagonal(1.0e-160D, 1.0e-160D, 1.0e-160D, 1.0e-160D);
    assertTrue(MatrixMath.inverse(huge).isPresent(), "均匀放大 diag(1e160) 必须可用");
    assertTrue(MatrixMath.inverse(tiny).isPresent(), "均匀缩小 diag(1e-160) 必须可用");
    final FrameMatrix hugeInverse = MatrixMath.inverse(huge).orElseThrow();
    final FrameMatrix tinyInverse = MatrixMath.inverse(tiny).orElseThrow();
    assertEquals(1.0e-160D, hugeInverse.get(0, 0), 0.0D, "diag(1e160) 的逆必须精确为 1e-160");
    assertEquals(1.0e160D, tinyInverse.get(0, 0), 0.0D, "diag(1e-160) 的逆必须精确为 1e160");
    assertTrue(maxAbsResidualAgainstIdentity(rowsOf(huge), rowsOf(hugeInverse)) <= RESIDUAL_LIMIT,
        "diag(1e160) 往返残差必须为 0 级");
    assertTrue(maxAbsResidualAgainstIdentity(rowsOf(tiny), rowsOf(tinyInverse)) <= RESIDUAL_LIMIT,
        "diag(1e-160) 往返残差必须为 0 级");
    // 对照：混合幅值 diag(1e-160,1,1,1) 的 norm 乘积为 1e160，必须被条件门槛拒绝。
    assertTrue(MatrixMath.inverse(diagonal(1.0e-160D, 1.0D, 1.0D, 1.0D)).isEmpty(),
        "混合幅值 diag(1e-160,1,1,1) 的 condition 估计超限必须 empty");
  }

  @Test
  void conditionGateIsAnUpperBoundNotAnAbsolutePivotThreshold() {
    // diag(1e-100) 与 diag(1e100) 都在门槛内必须可用；而混合幅值对角（如 diag(1e-160,1,1,1)）
    // 的 norm 乘积为 1e160，必须被条件门槛拒绝：门槛针对相对条件而非元素绝对幅值。
    assertTrue(MatrixMath.inverse(diagonal(1.0e-100D, 1.0e-100D, 1.0e-100D, 1.0e-100D)).isPresent(),
        "diag(1e-100) 必须可用：不得设绝对 pivot 阈值");
    assertTrue(MatrixMath.inverse(diagonal(1.0e100D, 1.0e100D, 1.0e100D, 1.0e100D)).isPresent(),
        "diag(1e100) 必须可用");
    assertTrue(MatrixMath.inverse(diagonal(1.0e-160D, 1.0D, 1.0D, 1.0D)).isEmpty(),
        "diag(1e-160,1,1,1) 的 norm 乘积为 1e160，必须 empty");
    assertTrue(MatrixMath.inverse(diagonal(1.0e160D, 1.0D, 1.0D, 1.0D)).isEmpty(),
        "diag(1e160,1,1,1) 的 norm 乘积为 1e160，必须 empty");
  }

  @Test
  void conditionEstimateLimitIsRespectedOnConstructedBoundary() {
    // 构造 norm(M) * norm(candidate) 略超 1e12 的对角矩阵：inverse 必须 empty。
    final FrameMatrix overLimit = diagonal(1.0D, 1.0D, 1.0D, 1.0e-13D);
    final double inputNorm = infinityNorm(rowsOf(overLimit));
    final double candidateNorm = infinityNorm(rowsOf(diagonal(1.0D, 1.0D, 1.0D, 1.0e13D)));
    assertTrue(inputNorm * candidateNorm > CONDITION_LIMIT,
        "构造的 condition 估计必须超过 1e12，实际 " + (inputNorm * candidateNorm));
    assertTrue(MatrixMath.inverse(overLimit).isEmpty(), "超过 1e12 必须 empty");

    // 更正：均匀对角 lambda*I 的 condition 估计恒为 1（norm(M) = lambda，norm(M^-1) = 1/lambda，
    // 乘积为 1），与 lambda 的量级无关；所以"均匀缩放对角"不是一条 condition 门槛，只是量级刻度测试。
    final double uniformScale = 1.0e-6D;
    final double uniformNorm = infinityNorm(rowsOf(diagonal(uniformScale, uniformScale, uniformScale, uniformScale)));
    final double uniformInverseNorm =
        infinityNorm(rowsOf(diagonal(1.0D / uniformScale, 1.0D / uniformScale, 1.0D / uniformScale, 1.0D / uniformScale)));
    assertEquals(1.0D, uniformNorm * uniformInverseNorm, 1e-12D,
        "更正：均匀 1e6 对角的 condition 估计是 1，不是 1e12");
    assertTrue(uniformNorm * uniformInverseNorm <= CONDITION_LIMIT, "均匀对角的 condition 估计必须远低于门槛");
    assertTrue(MatrixMath.inverse(diagonal(uniformScale, uniformScale, uniformScale, uniformScale)).isPresent(),
        "均匀对角在门槛内必须可用");

    // 真实相对门槛附近（单轴病态，condition 估计 = 1/1e-12 = 1e12，恰在门槛上，必须可用）。
    final double atLimitPivot = 1.0e-12D;
    final FrameMatrix atLimit = diagonal(1.0D, 1.0D, 1.0D, atLimitPivot);
    final double atLimitProduct = infinityNorm(rowsOf(atLimit)) * (1.0D / atLimitPivot);
    assertEquals(CONDITION_LIMIT, atLimitProduct, 0.0D, "前置：1/1e-12 精确等于门槛 1e12");
    assertFalse(atLimitProduct > CONDITION_LIMIT, "前置：恰在门槛上不构成越界");
    assertTrue(MatrixMath.inverse(atLimit).isPresent(), "condition 估计恰为 1e12（不越界）必须可用");

    // 真正越界的一侧：nextDown(1e-12)。前置先按实际计算 norm(M) * norm(inv) 核实它确实越门槛。
    final double justOverPivot = Math.nextDown(1.0e-12D);
    final double justOverReciprocal = 1.0D / justOverPivot;
    final double justOverProduct = infinityNorm(rowsOf(diagonal(1.0D, 1.0D, 1.0D, justOverPivot))) * justOverReciprocal;
    assertTrue(justOverPivot < atLimitPivot, "前置：nextDown(1e-12) 必须严格小于 1e-12");
    assertTrue(justOverReciprocal > 1.0e12D,
        "前置：1/nextDown(1e-12) 必须严格大于 1e12，实际 " + justOverReciprocal);
    assertTrue(justOverProduct > CONDITION_LIMIT,
        "前置：按实际计算的 condition 估计必须越门槛，实际 " + justOverProduct);
    assertTrue(MatrixMath.inverse(diagonal(1.0D, 1.0D, 1.0D, justOverPivot)).isEmpty(),
        "condition 估计越门槛必须 empty");
  }

  // ------------------------------------------------------------------
  // 透视 fixture：仅数学向量，不声称真实相机；模拟原生 float → double 输入
  // ------------------------------------------------------------------

  /** 每个非零分量先 cast float 再转 double，模拟 GPU 上的 float 输入。 */
  private static double asFloatThenDouble(double value) {
    return (double) (float) value;
  }

  /** 纵 FOV 70°、aspect 16/9、near 0.05、far 4096 的 OpenGL 透视矩阵（非零项按任务卡公式，float 精度）。 */
  private static FrameMatrix floatPerspectiveFixture() {
    final double fovDegrees = 70.0D;
    final double aspect = 16.0D / 9.0D;
    final double near = 0.05D;
    final double far = 4096.0D;
    final double focal = 1.0D / Math.tan(Math.toRadians(fovDegrees / 2.0D));
    final double m00 = asFloatThenDouble(focal / aspect);
    final double m11 = asFloatThenDouble(focal);
    final double m22 = asFloatThenDouble((far + near) / (near - far));
    final double m23 = asFloatThenDouble(2.0D * far * near / (near - far));
    final double m32 = asFloatThenDouble(-1.0D);
    return fromRows(new double[][] {
      {m00, 0.0D, 0.0D, 0.0D},
      {0.0D, m11, 0.0D, 0.0D},
      {0.0D, 0.0D, m22, m23},
      {0.0D, 0.0D, m32, 0.0D}
    });
  }

  @Test
  void floatPerspectiveFixtureHasExpectedNonZeroTermsOnly() {
    final FrameMatrix projection = floatPerspectiveFixture();
    final double focal = 1.0D / Math.tan(Math.toRadians(35.0D));
    assertEquals(asFloatThenDouble(focal / (16.0D / 9.0D)), projection.get(0, 0), 1e-6D, "M00 = f/aspect");
    assertEquals(asFloatThenDouble(focal), projection.get(1, 1), 1e-6D, "M11 = f");
    assertEquals(asFloatThenDouble((4096.0D + 0.05D) / (0.05D - 4096.0D)), projection.get(2, 2), 1e-6D,
        "M22 = (far+near)/(near-far)");
    assertEquals(asFloatThenDouble(2.0D * 4096.0D * 0.05D / (0.05D - 4096.0D)), projection.get(2, 3), 1e-6D,
        "M23 = 2*far*near/(near-far)");
    assertEquals(-1.0D, projection.get(3, 2), 0.0D, "M32 = -1");
    final double[][] zeroPositions = {
      {0.0D, 1.0D}, {0.0D, 2.0D}, {0.0D, 3.0D}, {1.0D, 0.0D}, {1.0D, 2.0D}, {1.0D, 3.0D},
      {2.0D, 0.0D}, {2.0D, 1.0D}, {3.0D, 0.0D}, {3.0D, 1.0D}, {3.0D, 3.0D}
    };
    for (final double[] position : zeroPositions) {
      final int row = (int) position[0];
      final int column = (int) position[1];
      assertEquals(0.0D, projection.get(row, column), 0.0D,
          "非零项之外必须精确为 0，位置 (" + row + "," + column + ")");
    }
  }

  /** 该透视矩阵的独立解析逆（不调用生产 inverse）。 */
  private static double[][] analyticPerspectiveInverse() {
    final double focal = 1.0D / Math.tan(Math.toRadians(35.0D));
    final double aspect = 16.0D / 9.0D;
    final double near = 0.05D;
    final double far = 4096.0D;
    final double a = asFloatThenDouble(focal / aspect);
    final double b = asFloatThenDouble(focal);
    final double c = asFloatThenDouble((far + near) / (near - far));
    final double d = asFloatThenDouble(2.0D * far * near / (near - far));
    return new double[][] {
      {1.0D / a, 0.0D, 0.0D, 0.0D},
      {0.0D, 1.0D / b, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, -1.0D},
      {0.0D, 0.0D, 1.0D / d, c / d}
    };
  }

  @Test
  void floatPerspectiveMatrixAndItsInversePassTheNumericGates() {
    final FrameMatrix projection = floatPerspectiveFixture();
    final Optional<FrameMatrix> inverse = MatrixMath.inverse(projection);
    assertTrue(inverse.isPresent(), "float 透视矩阵必须通过 inverse 的数值门槛");
    final FrameMatrix candidate = inverse.orElseThrow();

    final double[][] expectedInverse = analyticPerspectiveInverse();
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final double expected = expectedInverse[row][column];
        assertEquals(expected, candidate.get(row, column), Math.max(Math.abs(expected) * 1e-6D, 1e-8D),
            "解析逆分量 (" + row + "," + column + ")");
      }
    }

    final double[][] projectionRows = rowsOf(projection);
    final double[][] candidateRows = rowsOf(candidate);
    assertTrue(maxAbsResidualAgainstIdentity(projectionRows, candidateRows) <= RESIDUAL_LIMIT,
        "M * candidate 残差必须 <= 1e-8");
    assertTrue(maxAbsResidualAgainstIdentity(candidateRows, projectionRows) <= RESIDUAL_LIMIT,
        "candidate * M 残差必须 <= 1e-8");
    final double conditionEstimate = infinityNorm(projectionRows) * infinityNorm(candidateRows);
    assertTrue(conditionEstimate <= CONDITION_LIMIT,
        "透视 fixture 的 condition 估计必须 <= 1e12，实际 " + conditionEstimate);
    assertTrue(conditionEstimate > 0.0D, "condition 估计必须为正");
  }

  @Test
  void floatPerspectiveFixtureRoundTripsAPointInsideTheViewVolume() {
    final FrameMatrix projection = floatPerspectiveFixture();
    final FrameMatrix candidate = MatrixMath.inverse(projection).orElseThrow();
    final Vector4 eyePoint = new Vector4(1.25D, -0.75D, -10.0D, 1.0D);
    final Vector4 clip = MatrixMath.transform(projection, eyePoint);
    assertFalse(clip.w() == 0.0D, "view 内点的 w 必须非零（本测试不做除法）");
    final Vector4 roundTrip = MatrixMath.transform(candidate, clip);
    assertEquals(eyePoint.x(), roundTrip.x(), 1e-6D, "往返 x");
    assertEquals(eyePoint.y(), roundTrip.y(), 1e-6D, "往返 y");
    assertEquals(eyePoint.z(), roundTrip.z(), 1e-6D, "往返 z");
    assertEquals(1.0D, roundTrip.w(), 1e-6D, "往返 w 必须回到 1");
  }

  // ------------------------------------------------------------------
  // 非 FMA 证据：每次乘加各自舍入，禁止融合乘加
  // ------------------------------------------------------------------

  @Test
  void multiplyDoesNotFuseMulAddUnlikeMathFma() {
    // 固定 golden：a = 1 + 2^-27，b = 1 - 2^-27。
    // ordinary 递增 k 累加：0 + (-1)*1 = -1，再加 a*b（乘积舍入为 1）→ +0。
    // FMA 链：fma(-1,1,0) = -1，再 fma(a,b,-1) = -2^-54（精确），随后两个 +0 保持 -2^-54。
    final double a = 1.0D + Math.scalb(1.0D, -27);
    final double b = 1.0D - Math.scalb(1.0D, -27);
    final double ordinarySum = ((0.0D + (-1.0D) * 1.0D) + a * b) + 0.0D * 0.0D + 0.0D * 0.0D;
    final double fmaChain = Math.fma(a, b, Math.fma(-1.0D, 1.0D, 0.0D));
    assertEquals(0.0D, ordinarySum, 0.0D, "ordinary 递增累加必须精确为 +0");
    assertEquals(-Math.scalb(1.0D, -54), fmaChain, 0.0D, "FMA 链必须精确为 -2^-54");
    assertFalse(ordinarySum == fmaChain,
        "前置条件：本 fixture 的 bits 必须能区分普通乘加与 FMA（+0 vs -2^-54）");
    assertFalse(Double.doubleToRawLongBits(ordinarySum) == Double.doubleToRawLongBits(fmaChain),
        "前置条件：两者 double bits 必须不同");

    // A 第一行 [-1, a, 0, 0]，B 第一列 [1, b, 0, 0]，其余合法有限且全 0。
    final FrameMatrix left = fromRows(new double[][] {
      {-1.0D, a, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final FrameMatrix right = fromRows(new double[][] {
      {1.0D, 0.0D, 0.0D, 0.0D},
      {b, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final double actual = MatrixMath.multiply(left, right).get(0, 0);
    assertEquals(0.0D, actual, 0.0D, "生产结果必须是 ordinary +0，不得是 FMA 的 -2^-54");
    assertEquals(Double.doubleToRawLongBits(0.0D), Double.doubleToRawLongBits(actual),
        "生产结果的 raw bits 必须与 ordinary +0 完全一致");
    assertFalse(actual == fmaChain, "生产结果不得等于 FMA 链结果");
  }

  @Test
  void transformDoesNotFuseMulAddUnlikeMathFma() {
    final double a = 1.0D + Math.scalb(1.0D, -27);
    final double b = 1.0D - Math.scalb(1.0D, -27);
    // transform 第 0 行：0 + (-1)*1 → -1，再 + a*b → +0；FMA 链为 -2^-54。
    final double ordinarySum = ((0.0D + (-1.0D) * 1.0D) + a * b) + 0.0D * 0.0D + 0.0D * 0.0D;
    final double fmaChain = Math.fma(a, b, Math.fma(-1.0D, 1.0D, 0.0D));
    assertFalse(ordinarySum == fmaChain,
        "前置条件：本 fixture 的 bits 必须能区分普通乘加与 FMA（+0 vs -2^-54）");
    assertFalse(Double.doubleToRawLongBits(ordinarySum) == Double.doubleToRawLongBits(fmaChain),
        "前置条件：两者 double bits 必须不同");

    final FrameMatrix matrix = fromRows(new double[][] {
      {-1.0D, a, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final Vector4 vector = new Vector4(1.0D, b, 0.0D, 0.0D);
    final double actual = MatrixMath.transform(matrix, vector).x();
    assertEquals(0.0D, actual, 0.0D, "生产结果必须是 ordinary +0，不得是 FMA 的 -2^-54");
    assertEquals(Double.doubleToRawLongBits(0.0D), Double.doubleToRawLongBits(actual),
        "生产结果的 raw bits 必须与 ordinary +0 完全一致");
  }

  @Test
  void accumulationStartsFromPositiveZeroInIndexOrder() {
    // 固定 golden：四项 [1e16, 1, -1e16, 1]，按 k=0→3 正零起始累加结果为 1；
    // 反序累加结果为 0，故顺序差异可观测。每项显式乘 1。
    final double[] terms = {1.0e16D, 1.0D, -1.0e16D, 1.0D};
    double inOrder = 0.0D;
    for (final double term : terms) {
      inOrder += term * 1.0D;
    }
    double reverseOrder = 0.0D;
    for (int index = terms.length - 1; index >= 0; index--) {
      reverseOrder += terms[index] * 1.0D;
    }
    assertEquals(1.0D, inOrder, 0.0D, "固定 golden：正序累加必须为 1");
    assertEquals(0.0D, reverseOrder, 0.0D, "固定 golden：反序累加必须为 0");
    assertFalse(inOrder == reverseOrder, "前置条件：顺序差异必须可观测");

    final FrameMatrix matrix = fromRows(new double[][] {
      {terms[0], terms[1], terms[2], terms[3]},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final Vector4 ones = new Vector4(1.0D, 1.0D, 1.0D, 1.0D);
    assertEquals(inOrder, MatrixMath.transform(matrix, ones).x(), 0.0D,
        "transform 必须按 k=0..3 从 +0.0 顺序累加（结果 1，非反序的 0）");

    // multiply 同一元素：A 第一行 = terms，B 第一列全 1。
    final FrameMatrix left = fromRows(new double[][] {
      {terms[0], terms[1], terms[2], terms[3]},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    final FrameMatrix right = fromRows(new double[][] {
      {1.0D, 0.0D, 0.0D, 0.0D},
      {1.0D, 0.0D, 0.0D, 0.0D},
      {1.0D, 0.0D, 0.0D, 0.0D},
      {1.0D, 0.0D, 0.0D, 0.0D}
    });
    assertEquals(inOrder, MatrixMath.multiply(left, right).get(0, 0), 0.0D,
        "multiply(0,0) 必须按 k=0..3 顺序累加（结果 1，非反序的 0）");
  }

  @Test
  void noEpsilonSaturationOrRepairInFiniteChecks() {
    // 刚好溢出为 Infinity 的和必须被拒绝，不允许 clamp/saturate 成 MAX_VALUE。
    final FrameMatrix matrix = fromRows(new double[][] {
      {Double.MAX_VALUE, Double.MAX_VALUE, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    assertTrue(Double.isInfinite(Double.MAX_VALUE + Double.MAX_VALUE), "两个 MAX_VALUE 之和确为 Infinity");
    assertThrows(IllegalArgumentException.class,
        () -> MatrixMath.transform(matrix, new Vector4(1.0D, 1.0D, 0.0D, 0.0D)),
        "溢出必须拒绝，不得 saturate 到 MAX_VALUE");

    final FrameMatrix fine = fromRows(new double[][] {
      {Double.MAX_VALUE, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D},
      {0.0D, 0.0D, 0.0D, 0.0D}
    });
    assertEquals(Double.MAX_VALUE,
        MatrixMath.transform(fine, new Vector4(1.0D, 0.0D, 0.0D, 0.0D)).x(), 0.0D,
        "未溢出路径必须原样返回，不做修补");
  }

  @Test
  void inverseCandidateIsImmutableAndIndependentOfInternalScratch() {
    final FrameMatrix matrix = fromRows(new double[][] {
      {2.0D, 1.0D, 0.0D, 3.0D},
      {0.0D, 4.0D, 1.0D, -1.0D},
      {1.0D, 0.0D, 5.0D, 2.0D},
      {0.0D, 0.0D, 0.0D, 1.0D}
    });
    final FrameMatrix first = MatrixMath.inverse(matrix).orElseThrow();
    final FrameMatrix second = MatrixMath.inverse(matrix).orElseThrow();
    assertTrue(first.equals(second), "同一输入两次求逆必须得到相同结果");
    assertNotSame(first, second, "每次求逆必须返回新的不可变对象");
    final double[] polluted = first.columnMajor();
    polluted[0] = 1.0e9D;
    assertEquals(second.get(0, 0), first.get(0, 0), 0.0D, "污染 getter 副本不得改变结果对象");
    assertExactMatrix(second, MatrixMath.inverse(matrix).orElseThrow(), "内部 scratch 不得被共享");
  }

}