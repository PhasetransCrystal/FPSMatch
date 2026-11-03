package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.common.client.viewport.FramebufferRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportFrame;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.WorldRef;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import org.junit.jupiter.api.Test;

/**
 * R18.2a {@link ProjectionMath} 与载荷 {@link Vector3}/{@link PixelProjection}/{@link Ray3} 的正式契约测试。
 *
 * <p><strong>独立 golden 纪律</strong>：fixture 的 view/projection 逆一律由已 PASS 的 {@link MatrixMath#inverse}
 * 求得（不硬写全 identity）；期望像素与期望射线由本文件自建的独立 double 透视几何 oracle 给出
 * （自写 4x4 变换 + 解析 near/far 深度与 M00/M11 系数），<em>不</em>用生产 {@code project} 结果反推、
 * <em>不</em>做 project→ray 循环互证；1000 组固定 seed 样本全部必须可用，绝不按生产结果挑样本或跳过 empty。
 */
final class ProjectionMathContractTest {

  /** 固定 seed，样本序列可复现。 */
  private static final long SEED = 20261014L;
  /** 像素绝对误差上限（卡固定 1e-7）。 */
  private static final double TOL_PIXEL = 1e-7D;
  /** 射线近点每轴绝对误差上限。 */
  private static final double TOL_ORIGIN = 1e-7D;
  /** 射线方向分量绝对误差上限。 */
  private static final double TOL_DIRECTION = 1e-9D;
  /** maxDistance 绝对误差上限。 */
  private static final double TOL_LENGTH = 1e-7D;
  /** golden 精确 fixture 的断言容差。 */
  private static final double TOL_GOLDEN = 1e-9D;

  // main fixture：纵 FOV 90°、aspect 2、near 1 / far 101、rect (30,40,200,100)、相机 (10,20,30)。
  private static final double MAIN_M00 = 0.5D;
  private static final double MAIN_M11 = 1.0D;
  private static final double MAIN_M22 = -1.02D;
  private static final double MAIN_M23 = -2.02D;
  private static final double MAIN_M32 = -1.0D;
  private static final double MAIN_NEAR = 1.0D;
  private static final double MAIN_FAR = 101.0D;
  private static final FramebufferRect MAIN_RECT = new FramebufferRect(30, 40, 200, 100);
  private static final Position MAIN_CAMERA = new Position(10.0D, 20.0D, 30.0D);

  // binary-exact fixture：near 1 / far 3，M22 = -2、M23 = -3 均为精确二进制系数。
  private static final double EXACT_M00 = 0.5D;
  private static final double EXACT_M11 = 1.0D;
  private static final double EXACT_M22 = -2.0D;
  private static final double EXACT_M23 = -3.0D;
  private static final double EXACT_M32 = -1.0D;
  private static final double EXACT_NEAR = 1.0D;
  private static final double EXACT_FAR = 3.0D;
  private static final FramebufferRect EXACT_RECT = new FramebufferRect(30, 40, 200, 100);

  private static final WorldRef WORLD =
      new WorldRef(new Ids.WorldId(UUID.fromString("6f1c2a44-3b8e-4d5a-9c77-1e0b5a7d2f31")),
          "minecraft:overworld");
  private static final Ids.ConnectionId CONNECTION =
      new Ids.ConnectionId(UUID.fromString("b7d3e6a1-58c9-4f20-8a4b-2c9d0e5f7a13"));

  /**
   * identity 的逆：同样由已 PASS 的 {@link MatrixMath#inverse} 求得，绝不硬写逆矩阵。
   * 仅在人工不一致帧中充当“与正矩阵一致的那一侧”。
   */
  private static final FrameMatrix INVERSE_IDENTITY =
      MatrixMath.inverse(identityMatrix()).orElseThrow();

  /** row-major 行数组 → column-major {@link FrameMatrix} 载荷。 */
  private static FrameMatrix fromRows(double[][] rows) {
    final double[] values = new double[16];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        values[column * 4 + row] = rows[row][column];
      }
    }
    return new FrameMatrix(values);
  }

  /** {@link FrameMatrix} → row-major 行数组（供独立 oracle 使用，不经过生产运算）。 */
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

  private static FrameMatrix identityMatrix() {
    return fromRows(new double[][] {
        {1.0D, 0.0D, 0.0D, 0.0D},
        {0.0D, 1.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 1.0D, 0.0D},
        {0.0D, 0.0D, 0.0D, 1.0D}});
  }

  private static FrameMatrix mainProjection() {
    return fromRows(new double[][] {
        {MAIN_M00, 0.0D, 0.0D, 0.0D},
        {0.0D, MAIN_M11, 0.0D, 0.0D},
        {0.0D, 0.0D, MAIN_M22, MAIN_M23},
        {0.0D, 0.0D, MAIN_M32, 0.0D}});
  }

  private static FrameMatrix exactProjection() {
    return fromRows(new double[][] {
        {EXACT_M00, 0.0D, 0.0D, 0.0D},
        {0.0D, EXACT_M11, 0.0D, 0.0D},
        {0.0D, 0.0D, EXACT_M22, EXACT_M23},
        {0.0D, 0.0D, EXACT_M32, 0.0D}});
  }

  /** 绕 Y 轴 90° 的 view（全部系数为精确二进制值）：view·(rx,ry,rz,1) = (rz, ry, -rx, 1)。 */
  private static FrameMatrix rotatedView() {
    return fromRows(new double[][] {
        {0.0D, 0.0D, 1.0D, 0.0D},
        {0.0D, 1.0D, 0.0D, 0.0D},
        {-1.0D, 0.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 0.0D, 1.0D}});
  }

  /** 合法 fixture：逆矩阵一律由已 PASS 的 {@link MatrixMath#inverse} 求得。 */
  private static ViewportFrame frame(long frameId, Position camera, FrameMatrix view,
      FrameMatrix projection, FramebufferRect rect) {
    return new ViewportFrame(frameId, 0L, 0L, 0L, WORLD, CONNECTION, OptionalLong.empty(), camera,
        view, projection, MatrixMath.inverse(view).orElseThrow(),
        MatrixMath.inverse(projection).orElseThrow(), rect);
  }

  /**
   * 刻意不一致的人工载荷：仅用于把“矩阵算术溢出 / w == 0”精确打到某个运算点。
   * 卡明确允许公共 record 承载不一致的人工矩阵，本方法<em>不</em>宣称这些逆是正确的。
   */
  private static ViewportFrame artificialFrame(long frameId, Position camera, FrameMatrix view,
      FrameMatrix projection, FrameMatrix inverseView, FrameMatrix inverseProjection,
      FramebufferRect rect) {
    return new ViewportFrame(frameId, 0L, 0L, 0L, WORLD, CONNECTION, OptionalLong.empty(), camera,
        view, projection, inverseView, inverseProjection, rect);
  }

  private static ViewportFrame mainFrame(long frameId, Position camera, FramebufferRect rect) {
    return frame(frameId, camera, identityMatrix(), mainProjection(), rect);
  }

  private static ViewportFrame exactFrame(long frameId, Position camera) {
    return frame(frameId, camera, identityMatrix(), exactProjection(), EXACT_RECT);
  }

  private static void assertPixel(String label, double expectedX, double expectedY,
      PixelProjection actual) {
    assertEquals(expectedX, actual.pixelX(), TOL_PIXEL, label + " pixelX");
    assertEquals(expectedY, actual.pixelY(), TOL_PIXEL, label + " pixelY");
  }

  /** 期望向量布局：{originX, originY, originZ, dirX, dirY, dirZ, maxDistance}。 */
  private static void assertRay(String label, double[] expected, Ray3 actual) {
    assertEquals(expected[0], actual.origin().x(), TOL_ORIGIN, label + " origin.x");
    assertEquals(expected[1], actual.origin().y(), TOL_ORIGIN, label + " origin.y");
    assertEquals(expected[2], actual.origin().z(), TOL_ORIGIN, label + " origin.z");
    assertEquals(expected[3], actual.direction().x(), TOL_DIRECTION, label + " direction.x");
    assertEquals(expected[4], actual.direction().y(), TOL_DIRECTION, label + " direction.y");
    assertEquals(expected[5], actual.direction().z(), TOL_DIRECTION, label + " direction.z");
    assertEquals(expected[6], actual.maxDistance(), TOL_LENGTH, label + " maxDistance");
  }

  // ------------------------------------------------------------------
  // 独立 oracle：自写 4x4 变换与解析透视几何，不调用 ProjectionMath，也不复用生产算术
  // ------------------------------------------------------------------

  /** 独立 row-major 4x4 变换：k = 0..3 顺序累加，无 FMA。 */
  private static double[] independentTransform(double[][] rows, double[] vector) {
    final double[] result = new double[4];
    for (int row = 0; row < 4; row++) {
      double sum = +0.0D;
      for (int k = 0; k < 4; k++) {
        sum += rows[row][k] * vector[k];
      }
      result[row] = sum;
    }
    return result;
  }

  /** 独立除 w；w == 0 视为不可用并返回 null。 */
  private static double[] independentDivideW(double[] vector) {
    if (vector[3] == 0.0D) {
      return null;
    }
    return new double[] {vector[0] / vector[3], vector[1] / vector[3], vector[2] / vector[3]};
  }

  /** 解析透视几何：NDC + 深度 → view-space 齐次点（near/far 平面即 depth = near/far）。 */
  private static double[] viewPoint(double ndcX, double ndcY, double depth, double m00, double m11) {
    return new double[] {ndcX * depth / m00, ndcY * depth / m11, -depth, 1.0D};
  }

  /** 独立 oracle：由相机、view 逆、fixture 系数与像素给出期望射线（布局见 {@link #assertRay}）。 */
  private static double[] oracleRay(Position camera, double[][] inverseViewRows, double m00,
      double m11, double near, double far, FramebufferRect rect, double pixelX, double pixelY) {
    final double ndcX = 2.0D * (pixelX - rect.x()) / rect.width() - 1.0D;
    final double ndcY = 1.0D - 2.0D * (pixelY - rect.y()) / rect.height();
    final double[] nearRelative = independentDivideW(
        independentTransform(inverseViewRows, viewPoint(ndcX, ndcY, near, m00, m11)));
    final double[] farRelative = independentDivideW(
        independentTransform(inverseViewRows, viewPoint(ndcX, ndcY, far, m00, m11)));
    final double[] nearWorld = {camera.x() + nearRelative[0], camera.y() + nearRelative[1],
        camera.z() + nearRelative[2]};
    final double[] farWorld = {camera.x() + farRelative[0], camera.y() + farRelative[1],
        camera.z() + farRelative[2]};
    final double deltaX = farWorld[0] - nearWorld[0];
    final double deltaY = farWorld[1] - nearWorld[1];
    final double deltaZ = farWorld[2] - nearWorld[2];
    final double length = Math.hypot(Math.hypot(deltaX, deltaY), deltaZ);
    return new double[] {nearWorld[0], nearWorld[1], nearWorld[2], deltaX / length, deltaY / length,
        deltaZ / length, length};
  }

  /**
   * 独立 oracle：先接收<em>实际</em>构造出的 {@link Position}（含相机相加舍入），再独立做
   * {@code world - camera} 与透视公式，给出期望 {pixelX, pixelY, ndcZ}。
   */
  private static double[] oraclePixel(Position camera, FramebufferRect rect, Position world,
      double m00, double m11, double m22, double m23, double m32) {
    final double relativeX = world.x() - camera.x();
    final double relativeY = world.y() - camera.y();
    final double relativeZ = world.z() - camera.z();
    final double clipW = m32 * relativeZ;
    final double clipX = m00 * relativeX;
    final double clipY = m11 * relativeY;
    final double clipZ = m22 * relativeZ + m23 * 1.0D;
    final double ndcX = clipX / clipW;
    final double ndcY = clipY / clipW;
    final double ndcZ = clipZ / clipW;
    return new double[] {rect.x() + (ndcX + 1.0D) * rect.width() / 2.0D,
        rect.y() + (1.0D - ndcY) * rect.height() / 2.0D, ndcZ};
  }

  @Test
  void cardFixtureProjectMatchesGoldenPixels() {
    final ViewportFrame frame = mainFrame(41L, MAIN_CAMERA, MAIN_RECT);

    final Optional<PixelProjection> center =
        ProjectionMath.project(frame, new Position(10.0D, 20.0D, 20.0D));
    assertTrue(center.isPresent(), "camera-relative (0,0,-10) 必须可投影");
    assertEquals(41L, center.get().frameId(), "frameId 必须来自捕获帧");
    assertPixel("camera-relative (0,0,-10)", 130.0D, 90.0D, center.get());
    assertEquals(0.818D, center.get().ndcZ(), TOL_GOLDEN, "ndcZ");

    final Optional<PixelProjection> offCenter =
        ProjectionMath.project(frame, new Position(20.0D, 25.0D, 20.0D));
    assertTrue(offCenter.isPresent(), "camera-relative (10,5,-10) 必须可投影");
    assertPixel("camera-relative (10,5,-10)", 180.0D, 65.0D, offCenter.get());
    assertEquals(0.818D, offCenter.get().ndcZ(), TOL_GOLDEN, "ndcZ");

    // 独立代数另解两个偏心点：不由生产输出反推期望。
    final Optional<PixelProjection> upperLeft =
        ProjectionMath.project(frame, new Position(0.0D, 22.5D, 10.0D));
    assertTrue(upperLeft.isPresent(), "camera-relative (-10,2.5,-20) 必须可投影");
    assertPixel("camera-relative (-10,2.5,-20)", 105.0D, 83.75D, upperLeft.get());

    final Optional<PixelProjection> lowerRight =
        ProjectionMath.project(frame, new Position(15.0D, 17.5D, 25.0D));
    assertTrue(lowerRight.isPresent(), "camera-relative (5,-2.5,-5) 必须可投影");
    assertPixel("camera-relative (5,-2.5,-5)", 180.0D, 115.0D, lowerRight.get());

    // Y 轴只翻转一次：camera-relative y > 0 的点必须落在中心像素 90 之上。
    assertTrue(offCenter.get().pixelY() < 90.0D, "camera-relative y > 0 必须位于中心之上（单次翻转）");
    assertTrue(lowerRight.get().pixelY() > 90.0D, "camera-relative y < 0 必须位于中心之下（单次翻转）");
  }

  @Test
  void projectHalfOpenEdgesAcceptLeftTopAndRejectRightBottom() {
    final ViewportFrame frame = mainFrame(42L, MAIN_CAMERA, MAIN_RECT);

    // 恰左/上边界：pixelX == rect.x、pixelY == rect.y，半开区间内必须接受。
    final Optional<PixelProjection> leftTop =
        ProjectionMath.project(frame, new Position(-10.0D, 30.0D, 20.0D));
    assertTrue(leftTop.isPresent(), "恰左/上边界必须接受");
    assertPixel("恰左/上边界", 30.0D, 40.0D, leftTop.get());

    // 恰右边界：pixelX == right() == 230，必须 empty（不 -1、不 +0.5）。
    final Optional<PixelProjection> rightEdge =
        ProjectionMath.project(frame, new Position(30.0D, 20.0D, 20.0D));
    assertTrue(rightEdge.isEmpty(), "恰右边界必须 empty");

    // 恰下边界：pixelY == bottom() == 140，必须 empty。
    final Optional<PixelProjection> bottomEdge =
        ProjectionMath.project(frame, new Position(10.0D, 10.0D, 20.0D));
    assertTrue(bottomEdge.isEmpty(), "恰下边界必须 empty");
  }

  @Test
  void projectUsesObviousInsideAndOutsideDepthPointsWithoutClamping() {
    final ViewportFrame frame = mainFrame(43L, MAIN_CAMERA, MAIN_RECT);

    // 明显在 near/far 之间：接受（不断言符号平面上的点一定被接受）。
    for (double depth : new double[] {2.0D, 10.0D, 50.0D, 90.0D}) {
      final Optional<PixelProjection> inside =
          ProjectionMath.project(frame, new Position(10.0D, 20.0D, 30.0D - depth));
      assertTrue(inside.isPresent(), "深度 " + depth + " 明显位于 near..far 内，必须接受");
      assertPixel("深度 " + depth, 130.0D, 90.0D, inside.get());
    }

    // 明显在 near 之前（ndcZ < -1）：empty，不做 epsilon clamp。
    assertTrue(ProjectionMath.project(frame, new Position(10.0D, 20.0D, 29.5D)).isEmpty(),
        "位于近平面之前的点必须 empty");
    // 明显在 far 之后（ndcZ > 1）：empty。
    assertTrue(ProjectionMath.project(frame, new Position(10.0D, 20.0D, -170.0D)).isEmpty(),
        "位于远平面之后的点必须 empty");
    // 相机之后（clip.w < 0）：empty。
    assertTrue(ProjectionMath.project(frame, new Position(10.0D, 20.0D, 40.0D)).isEmpty(),
        "相机之后的点必须 empty");
    // 相机自身（clip.w == 0）：empty，绝不虚构像素。
    assertTrue(ProjectionMath.project(frame, MAIN_CAMERA).isEmpty(), "相机自身点 w == 0 必须 empty");
  }

  @Test
  void cardFixtureCenterRayMatchesGoldenSegment() {
    final ViewportFrame frame = mainFrame(44L, MAIN_CAMERA, MAIN_RECT);

    final Optional<Ray3> centerRay = ProjectionMath.ray(frame, 130.0D, 90.0D);
    assertTrue(centerRay.isPresent(), "中心像素 (130,90) 必须可反投影");
    assertEquals(44L, centerRay.get().frameId(), "frameId 必须来自捕获帧");
    assertRay("中心像素 (130,90)",
        new double[] {10.0D, 20.0D, 29.0D, 0.0D, 0.0D, -1.0D, 100.0D}, centerRay.get());
  }

  @Test
  void cardFixtureOffCenterRaysMatchIndependentPerspectiveOracle() {
    final ViewportFrame frame = mainFrame(45L, MAIN_CAMERA, MAIN_RECT);
    final double[][] inverseViewRows = rowsOf(frame.inverseView());

    // 半开 rect 内的偏心与角点像素：期望全部来自独立解析几何 oracle。
    final double[][] pixels = {
        {30.0D, 40.0D}, {229.5D, 139.5D}, {130.0D, 40.0D}, {30.0D, 90.0D}, {180.0D, 65.0D},
        {105.0D, 83.75D}, {180.0D, 115.0D}};
    for (double[] pixel : pixels) {
      final Optional<Ray3> ray = ProjectionMath.ray(frame, pixel[0], pixel[1]);
      assertTrue(ray.isPresent(), "像素 (" + pixel[0] + "," + pixel[1] + ") 必须可反投影");
      assertRay("像素 (" + pixel[0] + "," + pixel[1] + ")",
          oracleRay(MAIN_CAMERA, inverseViewRows, MAIN_M00, MAIN_M11, MAIN_NEAR, MAIN_FAR, MAIN_RECT,
              pixel[0], pixel[1]),
          ray.get());
    }

    // 恰左/上像素必须接受；恰右/下与视窗外必须 empty。
    assertTrue(ProjectionMath.ray(frame, 30.0D, 40.0D).isPresent(), "恰左/上像素必须接受");
    assertTrue(ProjectionMath.ray(frame, 229.9999999999D, 139.9999999999D).isPresent(),
        "半开区间内最后一刻像素必须接受");
    assertTrue(ProjectionMath.ray(frame, 230.0D, 90.0D).isEmpty(), "恰右边界像素必须 empty");
    assertTrue(ProjectionMath.ray(frame, 130.0D, 140.0D).isEmpty(), "恰下边界像素必须 empty");
    assertTrue(ProjectionMath.ray(frame, 29.9999999999D, 90.0D).isEmpty(), "视窗外左侧像素必须 empty");
    assertTrue(ProjectionMath.ray(frame, 130.0D, 39.9999999999D).isEmpty(), "视窗外上侧像素必须 empty");
    assertTrue(ProjectionMath.ray(frame, 500.0D, 90.0D).isEmpty(), "视窗外右侧像素必须 empty");
    assertTrue(ProjectionMath.ray(frame, 130.0D, 500.0D).isEmpty(), "视窗外下侧像素必须 empty");
  }

  @Test
  void rotatedViewAndCameraTranslationUseNonUniformRectOnce() {
    // 90° 绕 Y 旋转 view + 相机平移 + 非均匀（宽高比不为 2）的帧缓冲 rect。
    final FramebufferRect rect = new FramebufferRect(11, 13, 302, 154);
    final Position camera = new Position(100.0D, -50.0D, 7.0D);
    final ViewportFrame frame = frame(46L, camera, rotatedView(), mainProjection(), rect);

    // view-space (4,3,-20) ⇒ camera-relative (20,3,4) ⇒ world (120,-47,11)。
    final Optional<PixelProjection> projected =
        ProjectionMath.project(frame, new Position(120.0D, -47.0D, 11.0D));
    assertTrue(projected.isPresent(), "旋转视图下的可见点必须可投影");
    assertPixel("旋转视图 camera-relative (20,3,4)", 177.1D, 78.45D, projected.get());
    assertEquals(0.919D, projected.get().ndcZ(), TOL_GOLDEN, "ndcZ");

    // 相机平移只做一次：view 作用前必须恰好减一次 cameraPosition，二次平移会得到 102 的位移。
    assertEquals(177.1D, projected.get().pixelX(), TOL_PIXEL,
        "像素 X 依赖相机只被减一次（二次平移会得到 102 的位移）");

    // rect 中心像素 ⇒ 世界方向沿 +X，近点 = 相机 + (1,0,0)。
    final Optional<Ray3> centerRay = ProjectionMath.ray(frame, 162.0D, 90.0D);
    assertTrue(centerRay.isPresent(), "旋转视图中心像素必须可反投影");
    assertRay("旋转视图中心像素 (162,90)",
        new double[] {101.0D, -50.0D, 7.0D, 1.0D, 0.0D, 0.0D, 100.0D}, centerRay.get());

    // 中心下方像素：ndcY = -0.5，世界 y 必须随之减小（只翻转一次，不双重翻转）。
    final Optional<Ray3> belowCenter = ProjectionMath.ray(frame, 162.0D, 128.5D);
    assertTrue(belowCenter.isPresent(), "旋转视图偏心像素必须可反投影");
    assertTrue(belowCenter.get().direction().y() < 0.0D,
        "像素向下 ⇒ 世界 y 必须减小（单次翻转，无 Y 双翻转）");
    assertEquals(-50.5D, belowCenter.get().origin().y(), TOL_ORIGIN,
        "近点 y 必须为 camera.y + ndcY * near，且相机只被加一次");
    assertRay("旋转视图像素 (162,128.5)",
        oracleRay(camera, rowsOf(frame.inverseView()), MAIN_M00, MAIN_M11, MAIN_NEAR, MAIN_FAR, rect,
            162.0D, 128.5D),
        belowCenter.get());
  }

  @Test
  void exactBinaryFixtureResolvesNearFarAndHalfOpenEdgesBitExactly() {
    final ViewportFrame frame = exactFrame(47L, new Position(0.0D, 0.0D, 0.0D));

    // 近平面 z = -1 与远平面 z = -3 的中心点：精确 NDC (±1) 边界内必须接受。
    final Optional<PixelProjection> nearCenter =
        ProjectionMath.project(frame, new Position(0.0D, 0.0D, -1.0D));
    assertTrue(nearCenter.isPresent(), "近平面中心点必须接受");
    assertPixel("近平面中心点", 130.0D, 90.0D, nearCenter.get());
    assertEquals(-1.0D, nearCenter.get().ndcZ(), 0.0D, "近平面 ndcZ 必须精确为 -1");

    final Optional<PixelProjection> farCenter =
        ProjectionMath.project(frame, new Position(0.0D, 0.0D, -3.0D));
    assertTrue(farCenter.isPresent(), "远平面中心点必须接受");
    assertPixel("远平面中心点", 130.0D, 90.0D, farCenter.get());
    assertEquals(1.0D, farCenter.get().ndcZ(), 0.0D, "远平面 ndcZ 必须精确为 +1");

    // 精确左上角：ndcX = -1、ndcY = +1 ⇒ pixel (30,40)，半开区间内必须接受。
    final Optional<PixelProjection> exactLeftTop =
        ProjectionMath.project(frame, new Position(-6.0D, 3.0D, -3.0D));
    assertTrue(exactLeftTop.isPresent(), "精确左上角必须接受");
    assertPixel("精确左上角", 30.0D, 40.0D, exactLeftTop.get());

    // 精确右边缘 pixelX == 230、精确下边缘 pixelY == 140：必须 empty。
    assertTrue(ProjectionMath.project(frame, new Position(6.0D, 0.0D, -3.0D)).isEmpty(),
        "精确右边缘必须 empty");
    assertTrue(ProjectionMath.project(frame, new Position(0.0D, -3.0D, -3.0D)).isEmpty(),
        "精确下边缘必须 empty");

    // 精确 fixture 的中心射线：near z = -1、far z = -3、maxDistance = 2、方向 (0,0,-1)。
    final Optional<Ray3> centerRay = ProjectionMath.ray(frame, 130.0D, 90.0D);
    assertTrue(centerRay.isPresent(), "精确 fixture 中心像素必须可反投影");
    assertRay("精确 fixture 中心像素",
        new double[] {0.0D, 0.0D, -1.0D, 0.0D, 0.0D, -1.0D, 2.0D}, centerRay.get());
  }

  @Test
  void projectAndRayRejectNullAndNonFinitePrimitiveArguments() {
    final ViewportFrame frame = mainFrame(48L, MAIN_CAMERA, MAIN_RECT);
    final Position point = new Position(0.0D, 0.0D, 0.0D);

    assertThrows(IllegalArgumentException.class, () -> ProjectionMath.project(null, point),
        "frame == null 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> ProjectionMath.project(frame, null),
        "worldPoint == null 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> ProjectionMath.ray(null, 1.0D, 1.0D),
        "frame == null 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class,
        () -> ProjectionMath.ray(frame, Double.NaN, 90.0D),
        "原始 NaN pixelX 必须抛 IllegalArgumentException，而不是 empty");
    assertThrows(IllegalArgumentException.class,
        () -> ProjectionMath.ray(frame, 130.0D, Double.NaN),
        "原始 NaN pixelY 必须抛 IllegalArgumentException，而不是 empty");
    assertThrows(IllegalArgumentException.class,
        () -> ProjectionMath.ray(frame, Double.POSITIVE_INFINITY, 90.0D),
        "原始 +Inf pixelX 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class,
        () -> ProjectionMath.ray(frame, 130.0D, Double.NEGATIVE_INFINITY),
        "原始 -Inf pixelY 必须抛 IllegalArgumentException");
  }

  @Test
  void vector3RejectsNonFiniteComponents() {
    assertThrows(IllegalArgumentException.class, () -> new Vector3(Double.NaN, 0.0D, 0.0D));
    assertThrows(IllegalArgumentException.class, () -> new Vector3(0.0D, Double.NaN, 0.0D));
    assertThrows(IllegalArgumentException.class, () -> new Vector3(0.0D, 0.0D, Double.NaN));
    assertThrows(IllegalArgumentException.class,
        () -> new Vector3(Double.POSITIVE_INFINITY, 0.0D, 0.0D));
    assertThrows(IllegalArgumentException.class,
        () -> new Vector3(0.0D, Double.NEGATIVE_INFINITY, 0.0D));

    final Vector3 unit = new Vector3(0.0D, 0.0D, -1.0D);
    assertEquals(0.0D, unit.x(), 0.0D);
    assertEquals(0.0D, unit.y(), 0.0D);
    assertEquals(-1.0D, unit.z(), 0.0D);
  }

  @Test
  void pixelProjectionRejectsBadPayloadsAndAcceptsInclusiveNdcBounds() {
    assertThrows(IllegalArgumentException.class, () -> new PixelProjection(0L, 1.0D, 1.0D, 0.0D),
        "frameId 0 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> new PixelProjection(-1L, 1.0D, 1.0D, 0.0D),
        "负 frameId 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class,
        () -> new PixelProjection(1L, Double.NaN, 1.0D, 0.0D));
    assertThrows(IllegalArgumentException.class,
        () -> new PixelProjection(1L, 1.0D, Double.POSITIVE_INFINITY, 0.0D));
    assertThrows(IllegalArgumentException.class,
        () -> new PixelProjection(1L, 1.0D, 1.0D, Double.NaN));
    assertThrows(IllegalArgumentException.class,
        () -> new PixelProjection(1L, 1.0D, 1.0D, -1.0000001D), "ndcZ < -1 必须拒绝");
    assertThrows(IllegalArgumentException.class,
        () -> new PixelProjection(1L, 1.0D, 1.0D, 1.0000001D), "ndcZ > 1 必须拒绝");

    // 闭区间端点必须接受；连续像素允许分数，不做整数化。
    final PixelProjection low = new PixelProjection(1L, 30.25D, 40.75D, -1.0D);
    final PixelProjection high = new PixelProjection(1L, 229.5D, 139.5D, 1.0D);
    assertEquals(30.25D, low.pixelX(), 0.0D);
    assertEquals(40.75D, low.pixelY(), 0.0D);
    assertEquals(-1.0D, low.ndcZ(), 0.0D);
    assertEquals(229.5D, high.pixelX(), 0.0D);
    assertEquals(139.5D, high.pixelY(), 0.0D);
    assertEquals(1.0D, high.ndcZ(), 0.0D);
  }

  @Test
  void ray3RejectsBadPayloadsAndEnforcesUnitDirectionWithoutNormalizing() {
    final Position origin = new Position(0.0D, 0.0D, 0.0D);
    final Vector3 unit = new Vector3(0.0D, 0.0D, -1.0D);

    assertThrows(IllegalArgumentException.class, () -> new Ray3(0L, origin, unit, 1.0D),
        "frameId 0 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> new Ray3(-5L, origin, unit, 1.0D),
        "负 frameId 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> new Ray3(1L, null, unit, 1.0D),
        "origin == null 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> new Ray3(1L, origin, null, 1.0D),
        "direction == null 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class,
        () -> new Ray3(1L, origin, unit, Double.NaN), "NaN maxDistance 必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> new Ray3(1L, origin, unit, 0.0D),
        "零距离必须抛 IllegalArgumentException");
    assertThrows(IllegalArgumentException.class, () -> new Ray3(1L, origin, unit, -1.0D),
        "负距离必须抛 IllegalArgumentException");

    // 非单位方向必须拒绝，且不得静默归一化。
    assertThrows(IllegalArgumentException.class,
        () -> new Ray3(1L, origin, new Vector3(0.0D, 0.0D, -2.0D), 1.0D), "非单位方向必须拒绝");
    assertThrows(IllegalArgumentException.class,
        () -> new Ray3(1L, origin, new Vector3(0.5D, 0.0D, 0.0D), 1.0D), "非单位方向必须拒绝");
    assertThrows(IllegalArgumentException.class,
        () -> new Ray3(1L, origin, new Vector3(0.0D, 0.0D, -(1.0D + 2.0e-12D)), 1.0D),
        "超出容差的近单位方向必须拒绝");

    // 容差之内必须接受，并且分量保持原样（不归一化、不缩放）。
    final Vector3 almostUnit = new Vector3(0.0D, 0.0D, -(1.0D + 5.0e-13D));
    final Ray3 accepted = new Ray3(7L, origin, almostUnit, 12.5D);
    assertEquals(7L, accepted.frameId(), 0L);
    assertEquals(-(1.0D + 5.0e-13D), accepted.direction().z(), 0.0D,
        "方向必须原样保留，绝不静默归一化");
    assertEquals(12.5D, accepted.maxDistance(), 0.0D);
    assertEquals(0.0D, accepted.origin().x(), 0.0D);
  }

  @Test
  void projectReturnsEmptyAtEachNumericalFailurePoint() {
    // (1) world - camera 溢出为 Infinity：在该 subtract 运算点 empty。
    final ViewportFrame subtractOverflow = artificialFrame(61L, new Position(-1.5e308D, 0.0D, 0.0D),
        identityMatrix(), identityMatrix(), INVERSE_IDENTITY, INVERSE_IDENTITY, MAIN_RECT);
    assertTrue(ProjectionMath.project(subtractOverflow, new Position(1.5e308D, 0.0D, -10.0D)).isEmpty(),
        "world - camera 溢出必须 empty");

    // (2) view 变换的乘加溢出：MatrixMath 的数值 IAE 必须在该运算点转成 empty。
    final FrameMatrix overflowView = fromRows(new double[][] {
        {1.0e308D, 0.0D, 0.0D, 0.0D},
        {0.0D, 1.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 1.0D, 0.0D},
        {0.0D, 0.0D, 0.0D, 1.0D}});
    final ViewportFrame transformOverflow = artificialFrame(62L, new Position(0.0D, 0.0D, 0.0D),
        overflowView, identityMatrix(), INVERSE_IDENTITY, INVERSE_IDENTITY, MAIN_RECT);
    assertTrue(ProjectionMath.project(transformOverflow, new Position(10.0D, 0.0D, -10.0D)).isEmpty(),
        "view 变换溢出必须 empty，而不是抛出");

    // (3) 除以极小 w 溢出为 Infinity：NDC 不可用时 empty，不做 clamp。
    final FrameMatrix tinyWProjection = fromRows(new double[][] {
        {1.0D, 0.0D, 0.0D, 0.0D},
        {0.0D, 1.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 1.0D, 0.0D},
        {0.0D, 0.0D, 0.0D, 1.0e-300D}});
    final ViewportFrame divideOverflow = artificialFrame(63L, new Position(0.0D, 0.0D, 0.0D),
        identityMatrix(), tinyWProjection, INVERSE_IDENTITY, INVERSE_IDENTITY, MAIN_RECT);
    assertTrue(ProjectionMath.project(divideOverflow, new Position(1.0e300D, 0.0D, -10.0D)).isEmpty(),
        "NDC 除法溢出必须 empty");

    // (4) 平凡可用的合法 fixture 作为对照：同一批断言不得因 empty 分支而失效。
    final Optional<PixelProjection> usable =
        ProjectionMath.project(mainFrame(60L, MAIN_CAMERA, MAIN_RECT),
            new Position(10.0D, 20.0D, 20.0D));
    assertTrue(usable.isPresent(), "合法对照帧必须可用");
    assertPixel("合法对照帧", 130.0D, 90.0D, usable.get());
  }

  @Test
  void rayReturnsEmptyAtEachNumericalFailurePoint() {
    // (1) 逆投影除以极小 w 溢出：empty。
    final FrameMatrix tinyWInverse = fromRows(new double[][] {
        {1.0e300D, 0.0D, 0.0D, 0.0D},
        {0.0D, 1.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 1.0D, 0.0D},
        {0.0D, 0.0D, 0.0D, 1.0e-300D}});
    final ViewportFrame divideOverflow = artificialFrame(64L, new Position(0.0D, 0.0D, 0.0D),
        identityMatrix(), identityMatrix(), INVERSE_IDENTITY, tinyWInverse, MAIN_RECT);
    assertTrue(ProjectionMath.ray(divideOverflow, 180.0D, 90.0D).isEmpty(),
        "逆投影除法溢出必须 empty");

    // (2) w == 0：拒绝，绝不虚构方向。
    final FrameMatrix zeroWInverse = fromRows(new double[][] {
        {1.0D, 0.0D, 0.0D, 0.0D},
        {0.0D, 1.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 1.0D, 0.0D},
        {0.0D, 0.0D, 0.0D, 0.0D}});
    final ViewportFrame zeroW = artificialFrame(65L, new Position(0.0D, 0.0D, 0.0D), identityMatrix(),
        identityMatrix(), INVERSE_IDENTITY, zeroWInverse, MAIN_RECT);
    assertTrue(ProjectionMath.ray(zeroW, 130.0D, 90.0D).isEmpty(), "w == 0 必须 empty");

    // (3) 负的非零 w：允许，且结果必须与独立解析几何一致（不因负号被拒）。
    final FrameMatrix negativeWInverse = fromRows(new double[][] {
        {2.0D, 0.0D, 0.0D, 0.0D},
        {0.0D, 1.0D, 0.0D, 0.0D},
        {0.0D, 0.0D, 1.0D, 0.0D},
        {0.0D, 0.0D, 1.0D, -2.0D}});
    final FramebufferRect rect = new FramebufferRect(0, 0, 100, 100);
    final ViewportFrame negativeW = artificialFrame(66L, new Position(0.0D, 0.0D, 0.0D),
        identityMatrix(), identityMatrix(), INVERSE_IDENTITY, negativeWInverse, rect);
    final Optional<Ray3> negativeWRay = ProjectionMath.ray(negativeW, 75.0D, 25.0D);
    assertTrue(negativeWRay.isPresent(), "负的非零 w 必须被接受");

    // 独立解析期望：ndc = (0.5, 0.5)；near 相对点 = (-1/3, -1/6, 1/3)、far 相对点 = (-1, -1/2, -1)。
    final double nearX = -1.0D / 3.0D;
    final double nearY = -1.0D / 6.0D;
    final double nearZ = 1.0D / 3.0D;
    final double farX = -1.0D;
    final double farY = -0.5D;
    final double farZ = -1.0D;
    final double deltaX = farX - nearX;
    final double deltaY = farY - nearY;
    final double deltaZ = farZ - nearZ;
    final double length = Math.hypot(Math.hypot(deltaX, deltaY), deltaZ);
    assertRay("负 w 合法载荷",
        new double[] {nearX, nearY, nearZ, deltaX / length, deltaY / length, deltaZ / length, length},
        negativeWRay.get());
    assertEquals(1.0D, Math.hypot(Math.hypot(negativeWRay.get().direction().x(),
        negativeWRay.get().direction().y()), negativeWRay.get().direction().z()), 1.0e-12D,
        "方向必须是单位向量");
  }

  @Test
  void hugeCameraRoundingCollapsesNearAndFarAndReturnsEmpty() {
    // 1e20 处 double 间距远大于 near/far 差：中心像素的 near 与 far 舍入为同一点 ⇒ 零长度 ⇒ empty。
    final ViewportFrame frame = exactFrame(67L, new Position(0.0D, 0.0D, 1.0e20D));
    assertTrue(ProjectionMath.ray(frame, 130.0D, 90.0D).isEmpty(),
        "极大相机导致 near == far 时必须 empty，绝不编造方向");

    // 大但可用的对照：1e15 相机（间距 0.125）仍必须精确工作。
    final Position large = new Position(1.0e15D, 1.0e15D, 1.0e15D);
    final ViewportFrame usableFrame = mainFrame(68L, large, MAIN_RECT);
    final Optional<PixelProjection> usable =
        ProjectionMath.project(usableFrame, new Position(1.0e15D, 1.0e15D, 1.0e15D - 10.0D));
    assertTrue(usable.isPresent(), "1e15 相机下的普通可见点仍必须可用");
    assertPixel("1e15 相机可见点", 130.0D, 90.0D, usable.get());

    final Optional<Ray3> usableRay = ProjectionMath.ray(usableFrame, 130.0D, 90.0D);
    assertTrue(usableRay.isPresent(), "1e15 相机下的中心像素仍必须可用");
    assertRay("1e15 相机中心像素",
        new double[] {1.0e15D, 1.0e15D, 1.0e15D - 1.0D, 0.0D, 0.0D, -1.0D, 100.0D},
        usableRay.get());
  }

  @Test
  void extremeFiniteButUnusableCasesAreReportedAsEmptyAndNotSkipped() {
    // 每一个极端用例都显式断言，并统计 empty 数量，证明没有把 empty 样本悄悄跳过。
    int empty = 0;
    int usable = 0;
    final ViewportFrame frame = mainFrame(69L, MAIN_CAMERA, MAIN_RECT);

    final boolean[] outcomes = {
        ProjectionMath.project(frame, MAIN_CAMERA).isEmpty(),          // w == 0
        ProjectionMath.project(frame, new Position(10.0D, 20.0D, 40.0D)).isEmpty(),   // w < 0
        ProjectionMath.project(frame, new Position(10.0D, 20.0D, 29.5D)).isEmpty(),   // ndcZ < -1
        ProjectionMath.project(frame, new Position(10.0D, 20.0D, -170.0D)).isEmpty(), // ndcZ > 1
        ProjectionMath.project(frame, new Position(30.0D, 20.0D, 20.0D)).isEmpty(),   // 恰右
        ProjectionMath.project(frame, new Position(10.0D, 10.0D, 20.0D)).isEmpty(),   // 恰下
        ProjectionMath.ray(frame, 230.0D, 90.0D).isEmpty(),                          // 恰右像素
        ProjectionMath.ray(frame, 130.0D, 140.0D).isEmpty(),                         // 恰下像素
        ProjectionMath.ray(frame, 0.0D, 0.0D).isEmpty(),                             // 视窗外
        ProjectionMath.ray(frame, 130.0D, 90.0D).isPresent(),                        // 可用对照
    };
    for (int index = 0; index < outcomes.length; index++) {
      assertTrue(outcomes[index], "极端用例索引 " + index + " 的预期结果不成立");
      if (outcomes[index]) {
        if (index == outcomes.length - 1) {
          usable++;
        } else {
          empty++;
        }
      }
    }
    assertEquals(9, empty, "必须恰好 9 个极端用例为 empty（全部显式断言，不跳过）");
    assertEquals(1, usable, "必须恰好 1 个可用对照");
  }

  @Test
  void fixedSeedThousandSamplesMatchIndependentPerspectiveOracle() {
    final Random random = new Random(SEED);
    final double[][] identityRows = rowsOf(identityMatrix());
    int projectedCount = 0;
    int rayCount = 0;

    for (int index = 0; index < 1000; index++) {
      final double cameraX = (random.nextDouble() * 2.0D - 1.0D) * 1.0e6D;
      final double cameraY = (random.nextDouble() * 2.0D - 1.0D) * 1.0e6D;
      final double cameraZ = (random.nextDouble() * 2.0D - 1.0D) * 1.0e6D;
      final double depth = 2.0D + random.nextDouble() * 88.0D;
      final double ndcX = (random.nextDouble() * 2.0D - 1.0D) * 0.8D;
      final double ndcY = (random.nextDouble() * 2.0D - 1.0D) * 0.8D;
      final Position camera = new Position(cameraX, cameraY, cameraZ);

      // 由规范预给的目标 NDC/深度构造实际可见点（含相机相加舍入）。
      final double[] view = viewPoint(ndcX, ndcY, depth, MAIN_M00, MAIN_M11);
      final Position world =
          new Position(cameraX + view[0], cameraY + view[1], cameraZ + view[2]);

      final long frameId = 1000L + index;
      final ViewportFrame frame = mainFrame(frameId, camera, MAIN_RECT);
      final String label = "样本 " + index;

      // 期望像素：独立 oracle 从实际 Position 重新相减并套用透视公式。
      final double[] expectedPixel = oraclePixel(camera, MAIN_RECT, world, MAIN_M00, MAIN_M11,
          MAIN_M22, MAIN_M23, MAIN_M32);
      final Optional<PixelProjection> projected = ProjectionMath.project(frame, world);
      assertTrue(projected.isPresent(), label + " 必须可投影（禁止跳过 empty 样本）");
      assertEquals(frameId, projected.get().frameId(), label + " frameId");
      assertEquals(expectedPixel[0], projected.get().pixelX(), TOL_PIXEL, label + " pixelX");
      assertEquals(expectedPixel[1], projected.get().pixelY(), TOL_PIXEL, label + " pixelY");
      assertEquals(expectedPixel[2], projected.get().ndcZ(), 1.0e-9D, label + " ndcZ");
      projectedCount++;

      // 期望射线：在 oracle 像素处用解析 near/far 几何独立求解，不由生产 project 反推。
      final double[] expectedRay = oracleRay(camera, identityRows, MAIN_M00, MAIN_M11, MAIN_NEAR,
          MAIN_FAR, MAIN_RECT, expectedPixel[0], expectedPixel[1]);
      final Optional<Ray3> ray = ProjectionMath.ray(frame, expectedPixel[0], expectedPixel[1]);
      assertTrue(ray.isPresent(), label + " 必须可反投影（禁止跳过 empty 样本）");
      assertEquals(frameId, ray.get().frameId(), label + " frameId");
      assertRay(label, expectedRay, ray.get());
      rayCount++;
    }

    assertEquals(1000, projectedCount, "1000 个固定 seed 样本必须全部成功投影");
    assertEquals(1000, rayCount, "1000 个固定 seed 样本必须全部成功反投影");
  }
}
