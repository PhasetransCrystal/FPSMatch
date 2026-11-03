package net.ptcrys.fpsmatch.editor.common.client.viewport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.FrameMatrix;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.WorldRef;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R18.1b 不可变视窗帧契约测试（作者侧）。
 *
 * <p>被测类型：{@link FrameMatrix}、{@link ViewportFrame}。只消费<strong>已冻结</strong>的纯类型
 * （{@link WorldRef}、{@link Ids.ConnectionId}、{@link Position}、{@link ScaleTransform}、
 * {@link LogicalRect}、{@link FramebufferRect}、{@link ViewportLayout}）；不引入 MC/Forge/JOML/LWJGL，
 * 不复制或重定义任何冻结类型。
 *
 * <p>本测试只证明<strong>载荷与失效匹配</strong>语义；<strong>不</strong>证明 owner 是否真实递增代数、
 * 矩阵求逆、真实 marker 误差、输入消费时序或 FBO——那些属后续 R19/R20 验收。
 */
class ViewportFrameContractTest {

  private static final int FB = 200;

  // ------------------------------------------------------------------ 冻结身份与载荷工具

  private static final Ids.WorldId WORLD_A = new Ids.WorldId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final Ids.WorldId WORLD_B = new Ids.WorldId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
  private static final Ids.ConnectionId CONN_A =
      new Ids.ConnectionId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
  private static final Ids.ConnectionId CONN_B =
      new Ids.ConnectionId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

  private static final WorldRef WORLD_OVERWORLD = new WorldRef(WORLD_A, "minecraft:overworld");
  /** 同名（同 dimensionId）但不同 worldId——“按名字相等”不足以判定同一世界。 */
  private static final WorldRef WORLD_OVERWORLD_OTHER_ID = new WorldRef(WORLD_B, "minecraft:overworld");
  /** 同一 worldId 但不同维度。 */
  private static final WorldRef WORLD_NETHER = new WorldRef(WORLD_A, "minecraft:the_nether");

  private static final Position CAMERA_A = new Position(1.0D, 64.0D, 2.0D);
  private static final Position CAMERA_B = new Position(9.0D, 70.0D, -3.5D);

  /** 载荷用单位对角矩阵（仅作 16 个有限值的载荷，本类型不提供 identity 工厂）。 */
  private static FrameMatrix unitPayload() {
    final double[] values = new double[16];
    values[0] = 1.0D;
    values[5] = 1.0D;
    values[10] = 1.0D;
    values[15] = 1.0D;
    return new FrameMatrix(values);
  }

  /** 载荷用任意有限但非正交的矩阵（允许作为纯载荷，数学合法性后续验证）。 */
  private static FrameMatrix skewedPayload() {
    final double[] values = new double[16];
    for (int index = 0; index < 16; index++) {
      values[index] = index * 0.5D - 3.0D;
    }
    values[0] = 2.0D;
    values[1] = 0.25D;
    values[2] = -7.0D;
    values[3] = 1.5D;
    return new FrameMatrix(values);
  }

  private static ScaleTransform scale(double xScale, double yScale) {
    return new ScaleTransform(xScale, yScale, 0.0D, 0.0D, FB, FB);
  }

  private static ViewportLayout layout(long generation, LogicalRect logicalRect, ScaleTransform transform) {
    return new ViewportLayout(generation, logicalRect, transform);
  }

  /** 卡片固定布局变化向量：代数 7 的旧布局与代数 8 的新布局得到<strong>相同</strong>像素矩形。 */
  private static ViewportLayout layoutGenerationSeven() {
    return layout(7L, new LogicalRect(0.0D, 0.0D, 100.0D, 100.0D), scale(1.0D, 1.0D));
  }

  private static ViewportLayout layoutGenerationEight() {
    return layout(8L, new LogicalRect(0.0D, 0.0D, 50.0D, 50.0D), scale(2.0D, 2.0D));
  }

  /** 回到旧布局形状：同一逻辑矩形/scale，但代数必须继续前进到 9。 */
  private static ViewportLayout layoutGenerationNine() {
    return layout(9L, new LogicalRect(0.0D, 0.0D, 100.0D, 100.0D), scale(1.0D, 1.0D));
  }

  private static ViewportFrame frame(long frameId, long targetGeneration, long cameraGeneration,
      WorldRef world, Ids.ConnectionId connection, OptionalLong serverTick,
      Position cameraPosition, ViewportLayout frameLayout, FrameMatrix view) {
    return new ViewportFrame(frameId, targetGeneration, frameLayout.layoutGeneration(), cameraGeneration,
        world, connection, serverTick, cameraPosition, view, unitPayload(), unitPayload(), unitPayload(),
        frameLayout.framebufferRect());
  }

  /** 基准帧：代数与上下文全部对齐（frameId 9、target 3、camera 10、layout 7、tick 存在 100）。 */
  private static ViewportFrame baselineFrame() {
    return frame(9L, 3L, 10L, WORLD_OVERWORLD, CONN_A, OptionalLong.of(100L), CAMERA_A,
        layoutGenerationSeven(), unitPayload());
  }

  // ------------------------------------------------------------------ FrameMatrix：构造与坏输入

  @Test
  @DisplayName("FrameMatrix 接受 16 个有限值，包含奇异矩阵与任意非正交矩阵")
  void frameMatrixAcceptsAnyFiniteMatrix() {
    final double[] zero = new double[16];
    final FrameMatrix singular = new FrameMatrix(zero);
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        assertEquals(0.0D, singular.get(row, column), 0.0D);
      }
    }
    assertNotEquals(0.0D, skewedPayload().get(2, 0), 0.0D);
  }

  @Test
  @DisplayName("FrameMatrix 拒绝 null 与长度不是 16 的数组")
  void frameMatrixRejectsNullOrWrongLength() {
    assertThrows(IllegalArgumentException.class, () -> new FrameMatrix(null));
    assertThrows(IllegalArgumentException.class, () -> new FrameMatrix(new double[0]));
    assertThrows(IllegalArgumentException.class, () -> new FrameMatrix(new double[15]));
    assertThrows(IllegalArgumentException.class, () -> new FrameMatrix(new double[17]));
    assertEquals(16, new FrameMatrix(new double[16]).columnMajor().length);
  }

  @Test
  @DisplayName("FrameMatrix 对全部 16 个位置逐一拒绝 NaN/±Infinity")
  void frameMatrixRejectsNonFiniteAtEveryIndex() {
    final double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (final double value : bad) {
      for (int index = 0; index < 16; index++) {
        final double[] values = new double[16];
        values[index] = value;
        assertThrows(IllegalArgumentException.class, () -> new FrameMatrix(values),
            "index=" + index + " value=" + value);
      }
    }
  }

  // ------------------------------------------------------------------ FrameMatrix：索引与副本

  @Test
  @DisplayName("FrameMatrix get 索引固定为 column*4+row（column-major golden）")
  void frameMatrixGetUsesColumnMajorIndexing() {
    final double[] values = new double[16];
    for (int index = 0; index < 16; index++) {
      values[index] = index * 1.5D + 0.25D;
    }
    final FrameMatrix matrix = new FrameMatrix(values);
    for (int column = 0; column < 4; column++) {
      for (int row = 0; row < 4; row++) {
        assertEquals(values[column * 4 + row], matrix.get(row, column), 0.0D,
            "row=" + row + " column=" + column);
      }
    }
    // 非对称值时 get(row,column) 必须区别于 row-major 解读。
    assertNotEquals(matrix.get(0, 1), matrix.get(1, 0), 0.0D);
  }

  @Test
  @DisplayName("FrameMatrix get 的 row/column 越界抛 IAE，不 clamp 不取模")
  void frameMatrixGetRejectsOutOfRangeIndexes() {
    final FrameMatrix matrix = unitPayload();
    for (final int bad : new int[] {-1, 4, 5, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> matrix.get(bad, 0), "row=" + bad);
      assertThrows(IllegalArgumentException.class, () -> matrix.get(0, bad), "column=" + bad);
    }
  }

  @Test
  @DisplayName("FrameMatrix columnMajor() 每次返回副本，修改返回值不污染矩阵")
  void frameMatrixColumnMajorGetterReturnsIsolatedCopy() {
    final FrameMatrix matrix = unitPayload();
    final double[] first = matrix.columnMajor();
    first[0] = 999.0D;
    first[15] = -999.0D;
    assertEquals(1.0D, matrix.get(0, 0), 0.0D);
    assertEquals(1.0D, matrix.get(3, 3), 0.0D);

    final double[] second = matrix.columnMajor();
    assertNotSame(first, second);
    assertEquals(1.0D, second[0], 0.0D);
    assertEquals(1.0D, second[15], 0.0D);
  }

  @Test
  @DisplayName("FrameMatrix 构造后修改原数组不污染矩阵（先 clone 再校验的结构性结果）")
  void frameMatrixDoesNotAliasConstructorInput() {
    final double[] values = new double[16];
    values[3] = 7.5D;
    final FrameMatrix matrix = new FrameMatrix(values);
    values[3] = -1.0D;
    values[0] = 42.0D;
    assertEquals(7.5D, matrix.get(3, 0), 0.0D);
    assertEquals(0.0D, matrix.get(0, 0), 0.0D);
    assertEquals(7.5D, matrix.columnMajor()[3], 0.0D);
  }

  // ------------------------------------------------------------------ FrameMatrix：equals / hashCode / signed zero

  @Test
  @DisplayName("FrameMatrix equals/hashCode 覆盖全部 16 个值，同一对象与不同值语义")
  void frameMatrixEqualityCoversAllSixteenValues() {
    final double[] values = new double[16];
    for (int index = 0; index < 16; index++) {
      values[index] = index * 0.125D;
    }
    final FrameMatrix a = new FrameMatrix(values);
    final FrameMatrix b = new FrameMatrix(values.clone());
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertSame(a, a);
    assertEquals(a, a);
    assertNotEquals(a, null);
    assertFalse(a.equals("not a matrix"));
    assertFalse(a.equals(Integer.valueOf(3)));

    // 仅最低位不同也必须不等且能把差异暴露出来。
    for (int index = 0; index < 16; index++) {
      final double[] mutated = values.clone();
      mutated[index] += 1.0D;
      assertNotEquals(a, new FrameMatrix(mutated), "index=" + index);
    }
  }

  @Test
  @DisplayName("FrameMatrix 遵守 double 数组 signed zero 语义：-0.0 与 +0.0 不相等且符号位保留")
  void frameMatrixKeepsSignedZeroSemantics() {
    final double[] negative = new double[16];
    negative[7] = -0.0D;
    final double[] positive = new double[16];
    positive[7] = 0.0D;
    final FrameMatrix negativeZero = new FrameMatrix(negative);
    final FrameMatrix positiveZero = new FrameMatrix(positive);
    assertNotEquals(negativeZero, positiveZero);
    // 下标 7 的 column-major 换算是 column=1, row=3。
    assertEquals(Double.doubleToRawLongBits(-0.0D), Double.doubleToRawLongBits(negativeZero.get(3, 1)));
    assertEquals(Double.doubleToRawLongBits(0.0D), Double.doubleToRawLongBits(positiveZero.get(3, 1)));
    // 未被静默 normalize 成 +0.0。
    assertTrue(Double.doubleToRawLongBits(negativeZero.columnMajor()[7]) < 0L);
  }

  @Test
  @DisplayName("FrameMatrix API 面：final class、单一 private final double[16]、无身份/乘法/求逆/可变 buffer")
  void frameMatrixApiSurfaceIsFrozen() {
    final Class<FrameMatrix> type = FrameMatrix.class;
    assertTrue(Modifier.isFinal(type.getModifiers()), "FrameMatrix 必须是 final class");

    final Field[] fields = type.getDeclaredFields();
    final List<Field> instanceFields = Arrays.stream(fields)
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .toList();
    assertEquals(1, instanceFields.size(), "只允许一个实例字段（double[16] 存储）");
    final Field storage = instanceFields.get(0);
    assertTrue(Modifier.isPrivate(storage.getModifiers()));
    assertTrue(Modifier.isFinal(storage.getModifiers()));
    assertEquals(double[].class, storage.getType());
    // 其余字段只能是 private static final 的编译期常量（不得是可变静态状态）。
    for (final Field field : fields) {
      if (Modifier.isStatic(field.getModifiers())) {
        assertTrue(Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
            "静态字段必须是 private static final 常量: " + field.getName());
        assertEquals(int.class, field.getType(), "静态常量只能是 int: " + field.getName());
      }
    }

    final Set<String> declared = Arrays.stream(type.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .map(Method::getName)
        .collect(Collectors.toSet());
    assertEquals(Set.of("get", "columnMajor", "equals", "hashCode"), declared);

    final Set<String> forbidden = Set.of("identity", "multiply", "invert", "inverse", "transpose",
        "determinant", "copy", "copyFrom", "set", "put", "transform", "lookAt", "ortho", "perspective",
        "ofColumnMajor", "mutableBuffer");
    for (final Method method : type.getMethods()) {
      assertFalse(forbidden.contains(method.getName()),
          "禁止暴露矩阵运算/可变写入口: " + method.getName());
    }

    final Constructor<?>[] constructors = type.getDeclaredConstructors();
    assertEquals(1, constructors.length);
    assertTrue(Modifier.isPublic(constructors[0].getModifiers()));
    assertEquals(List.of(double[].class), List.of(constructors[0].getParameterTypes()));
  }

  // ------------------------------------------------------------------ ViewportFrame：构造校验

  @Test
  @DisplayName("ViewportFrame 接受 frameId>0、三个 generation>=0 与已知/未知 tick")
  void viewportFrameAcceptsValidCombinations() {
    final ViewportFrame known = baselineFrame();
    assertEquals(9L, known.frameId());
    assertEquals(3L, known.targetGeneration());
    assertEquals(7L, known.layoutGeneration());
    assertEquals(10L, known.cameraGeneration());
    assertEquals(OptionalLong.of(100L), known.serverTick());
    assertEquals(CAMERA_A, known.cameraPosition());
    assertFalse(known.framebufferRect().isEmpty());

    final ViewportFrame zeroGenerations = frame(1L, 0L, 0L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.empty(), CAMERA_A, layoutGenerationSeven(), unitPayload());
    assertEquals(1L, zeroGenerations.frameId());
    assertEquals(0L, zeroGenerations.targetGeneration());
    assertEquals(0L, zeroGenerations.cameraGeneration());

    final ViewportFrame tickZero = frame(2L, 0L, 0L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(0L), CAMERA_A, layoutGenerationSeven(), unitPayload());
    assertEquals(OptionalLong.of(0L), tickZero.serverTick());
  }

  @Test
  @DisplayName("ViewportFrame 拒绝 frameId<=0")
  void viewportFrameRejectsNonPositiveFrameId() {
    assertThrows(IllegalArgumentException.class, () -> frame(0L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(1L), CAMERA_A, layoutGenerationSeven(), unitPayload()));
    assertThrows(IllegalArgumentException.class, () -> frame(-1L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(1L), CAMERA_A, layoutGenerationSeven(), unitPayload()));
  }

  @Test
  @DisplayName("ViewportFrame 拒绝任一负 generation")
  void viewportFrameRejectsNegativeGenerations() {
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, -1L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), new FramebufferRect(0, 0, 100, 100)));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, -1L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), new FramebufferRect(0, 0, 100, 100)));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, -1L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), new FramebufferRect(0, 0, 100, 100)));
  }

  @Test
  @DisplayName("ViewportFrame 拒绝任一 null 引用（含 serverTick 与四个矩阵）")
  void viewportFrameRejectsNullReferences() {
    final FramebufferRect rect = new FramebufferRect(0, 0, 100, 100);
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        null, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, null, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, null, CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), null, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, null, unitPayload(),
        unitPayload(), unitPayload(), rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), null,
        unitPayload(), unitPayload(), rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        null, unitPayload(), rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), null, rect));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), null));
  }

  @Test
  @DisplayName("ViewportFrame 拒绝存在的负 serverTick，并保留 tick unknown 为 empty（不伪填 0）")
  void viewportFrameRejectsNegativeServerTickAndKeepsUnknownEmpty() {
    assertThrows(IllegalArgumentException.class, () -> frame(1L, 0L, 0L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(-1L), CAMERA_A, layoutGenerationSeven(), unitPayload()));
    assertThrows(IllegalArgumentException.class, () -> frame(1L, 0L, 0L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(Long.MIN_VALUE), CAMERA_A, layoutGenerationSeven(), unitPayload()));

    final ViewportFrame unknown = frame(1L, 0L, 0L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.empty(), CAMERA_A, layoutGenerationSeven(), unitPayload());
    assertTrue(unknown.serverTick().isEmpty(), "未知 tick 必须是 empty");
    assertFalse(unknown.serverTick().isPresent());
    // empty 与 of(0) 是两种不同事实，不得互相冒充。
    assertNotEquals(OptionalLong.of(0L), unknown.serverTick());
  }

  @Test
  @DisplayName("ViewportFrame 拒绝空 framebufferRect（空视窗不发布帧）")
  void viewportFrameRejectsEmptyFramebufferRect() {
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), new FramebufferRect(0, 0, 0, 50)));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), new FramebufferRect(0, 0, 50, 0)));
    assertThrows(IllegalArgumentException.class, () -> new ViewportFrame(1L, 0L, 0L, 0L,
        WORLD_OVERWORLD, CONN_A, OptionalLong.empty(), CAMERA_A, unitPayload(), unitPayload(),
        unitPayload(), unitPayload(), new FramebufferRect(0, 0, 0, 0)));
    // 1px 非空矩形必须被接受。
    assertEquals(1, new FramebufferRect(0, 0, 1, 1).width());
  }

  // ------------------------------------------------------------------ ViewportFrame：matches 基线

  @Test
  @DisplayName("matches 在上下文完全一致时为 true")
  void matchesReturnsTrueForIdenticalContext() {
    final ViewportFrame frame = baselineFrame();
    assertTrue(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
    // 等价的新构造实例（记录值相等）同样为 true。
    assertTrue(frame.matches(new WorldRef(WORLD_A, "minecraft:overworld"),
        new Ids.ConnectionId(CONN_A.value()), 3L, 10L,
        layout(7L, new LogicalRect(0.0D, 0.0D, 100.0D, 100.0D), scale(1.0D, 1.0D))));
  }

  @Test
  @DisplayName("matches 逐个变更 world（同名不同 ID）/dimension/connection/target/layout/camera/rect 均为 false")
  void matchesRejectsEverySingleDimensionChange() {
    final ViewportFrame frame = baselineFrame();
    assertFalse(frame.matches(WORLD_OVERWORLD_OTHER_ID, CONN_A, 3L, 10L, layoutGenerationSeven()),
        "同名不同 worldId 必须拒绝");
    assertFalse(frame.matches(WORLD_NETHER, CONN_A, 3L, 10L, layoutGenerationSeven()),
        "不同 dimension 必须拒绝");
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_B, 3L, 10L, layoutGenerationSeven()),
        "不同 connection 必须拒绝");
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 4L, 10L, layoutGenerationSeven()),
        "不同 targetGeneration 必须拒绝");
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 11L, layoutGenerationSeven()),
        "不同 cameraGeneration 必须拒绝");
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L,
        layout(8L, new LogicalRect(0.0D, 0.0D, 100.0D, 100.0D), scale(1.0D, 1.0D))),
        "不同 layoutGeneration 必须拒绝");
    // 相同 layoutGeneration，但实际像素矩形不同也要拒绝。
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L,
        layout(7L, new LogicalRect(0.0D, 0.0D, 60.0D, 100.0D), scale(1.0D, 1.0D))),
        "同 generation 但 rect 不同必须拒绝");
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L,
        layout(7L, new LogicalRect(10.0D, 0.0D, 100.0D, 100.0D), scale(1.0D, 1.0D))),
        "同 generation 但 rect 偏移必须拒绝");
  }

  @Test
  @DisplayName("matches 对 null 当前上下文返回 false（world/connection/layout 各自）")
  void matchesReturnsFalseForNullCurrentContext() {
    final ViewportFrame frame = baselineFrame();
    assertFalse(frame.matches(null, CONN_A, 3L, 10L, layoutGenerationSeven()));
    assertFalse(frame.matches(WORLD_OVERWORLD, null, 3L, 10L, layoutGenerationSeven()));
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, null));
    assertFalse(frame.matches(null, null, 3L, 10L, null));
    assertFalse(frame.matches(null, CONN_A, 3L, 10L, null));
  }

  @Test
  @DisplayName("matches 先校验负 current generation：即使 null 上下文同时存在也抛 IAE")
  void matchesValidatesNegativeCurrentGenerationsFirst() {
    final ViewportFrame frame = baselineFrame();
    assertThrows(IllegalArgumentException.class,
        () -> frame.matches(WORLD_OVERWORLD, CONN_A, -1L, 10L, layoutGenerationSeven()));
    assertThrows(IllegalArgumentException.class,
        () -> frame.matches(WORLD_OVERWORLD, CONN_A, 3L, -1L, layoutGenerationSeven()));
    assertThrows(IllegalArgumentException.class,
        () -> frame.matches(WORLD_OVERWORLD, CONN_A, Long.MIN_VALUE, 10L, layoutGenerationSeven()));
    assertThrows(IllegalArgumentException.class,
        () -> frame.matches(null, null, -1L, -1L, null));
    assertThrows(IllegalArgumentException.class,
        () -> frame.matches(null, null, 0L, -1L, null));
    assertThrows(IllegalArgumentException.class,
        () -> frame.matches(null, null, -1L, 0L, null));
    // 非负代数 + null 上下文是 false，而不是 IAE。
    assertFalse(frame.matches(null, null, 0L, 0L, null));
  }

  @Test
  @DisplayName("matches 不要求 frameId 等于最新帧 ID：帧 ID 不同但代数/rect 一致仍 true")
  void matchesIgnoresFrameId() {
    final ViewportFrame earlier = frame(4L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(100L), CAMERA_A, layoutGenerationSeven(), unitPayload());
    final ViewportFrame later = frame(1234L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(100L), CAMERA_A, layoutGenerationSeven(), unitPayload());
    assertNotEquals(earlier.frameId(), later.frameId());
    assertTrue(earlier.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
    assertTrue(later.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
  }

  @Test
  @DisplayName("matches 不把 serverTick 当场景视图版本：tick 变化/未知不影响判定")
  void matchesDoesNotUseServerTickAsVersion() {
    final ViewportFrame known = baselineFrame();
    final ViewportFrame otherTick = frame(9L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(101L), CAMERA_A, layoutGenerationSeven(), unitPayload());
    final ViewportFrame unknownTick = frame(9L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.empty(), CAMERA_A, layoutGenerationSeven(), unitPayload());
    assertNotEquals(known.serverTick(), otherTick.serverTick());
    assertTrue(known.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
    assertTrue(otherTick.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
    assertTrue(unknownTick.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
  }

  @Test
  @DisplayName("matches 只以 cameraGeneration 表达相机版本：位置/矩阵值本身不参与比对（依赖 owner 契约）")
  void matchesTreatsCameraGenerationAsTheOnlyCameraVersion() {
    final ViewportFrame frame = baselineFrame();
    // 位置与矩阵载荷都变了，但代数相同：按冻结契约 matches 仍为 true。位置/真实 view/projection 的任何变化
    // 都必须由 owner 递增 cameraGeneration 表达（R19/R20 验收），本类型不比对值。
    final ViewportFrame movedCamera = frame(9L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(100L), CAMERA_B, layoutGenerationSeven(), skewedPayload());
    assertTrue(movedCamera.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
    // 一旦 owner 按契约递增代数（同一场景里 A→B），旧帧立即失效。
    assertFalse(movedCamera.matches(WORLD_OVERWORLD, CONN_A, 3L, 11L, layoutGenerationSeven()));
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 11L, layoutGenerationSeven()));
  }

  // ------------------------------------------------------------------ 相机 A→B→A 与布局 7→8→9

  @Test
  @DisplayName("camera A→B→A：新代数下旧 A 帧必须继续被拒（不得 ABA 复用）")
  void cameraAbaNeverRevalidatesOldFrame() {
    final ViewportFrame frameA = frame(5L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(100L), CAMERA_A, layoutGenerationSeven(), unitPayload());
    assertTrue(frameA.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));

    // 移动到 B：owner 递增到 11，旧 A 帧失效。
    assertFalse(frameA.matches(WORLD_OVERWORLD, CONN_A, 3L, 11L, layoutGenerationSeven()));

    // 回到 A 的位置：owner 继续递增到 12（不是回到 10），旧 A 帧仍必须被拒。
    assertFalse(frameA.matches(WORLD_OVERWORLD, CONN_A, 3L, 12L, layoutGenerationSeven()));
    assertFalse(frameA.matches(WORLD_OVERWORLD, CONN_A, 3L, Long.MAX_VALUE, layoutGenerationSeven()));

    // 只有当场捕获的新 A 帧（generation 12）才有效。
    final ViewportFrame newFrameA = frame(6L, 3L, 12L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(100L), CAMERA_A, layoutGenerationSeven(), unitPayload());
    assertTrue(newFrameA.matches(WORLD_OVERWORLD, CONN_A, 3L, 12L, layoutGenerationSeven()));
    assertFalse(newFrameA.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationSeven()));
  }

  @Test
  @DisplayName("固定布局向量 7→8→9：两个像素矩形相同，旧帧在 8 与回到旧形状的 9 上都必须被拒")
  void layoutGenerationSevenEightNineRejectsOldFrame() {
    final ViewportLayout generation7 = layoutGenerationSeven();
    final ViewportLayout generation8 = layoutGenerationEight();
    final ViewportLayout generation9 = layoutGenerationNine();

    // 布局变化向量：逻辑矩形与 scale 都变了，但最终像素矩形完全相同。
    assertEquals(new FramebufferRect(0, 0, 100, 100), generation7.framebufferRect());
    assertEquals(new FramebufferRect(0, 0, 100, 100), generation8.framebufferRect());
    assertEquals(generation7.framebufferRect(), generation8.framebufferRect());

    final ViewportFrame oldFrame = frame(1L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(100L), CAMERA_A, generation7, unitPayload());
    assertTrue(oldFrame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, generation7));
    // 代数 8：像素矩形相同也仍然失效（本卡核心用例）。
    assertFalse(oldFrame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, generation8));
    // 回到旧布局形状：代数 9，继续拒绝旧帧。
    assertEquals(generation7.framebufferRect(), generation9.framebufferRect());
    assertEquals(9L, generation9.layoutGeneration());
    assertFalse(oldFrame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, generation9));

    // 只有按新代数捕获的帧才有效。
    final ViewportFrame newFrame = frame(2L, 3L, 10L, WORLD_OVERWORLD, CONN_A,
        OptionalLong.of(100L), CAMERA_A, generation8, unitPayload());
    assertEquals(8L, newFrame.layoutGeneration());
    assertTrue(newFrame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, generation8));
    assertFalse(newFrame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, generation7));
  }

  @Test
  @DisplayName("多连接/跨维度与多重变更组合：任一不符即 false（不因其他项相同而放行）")
  void matchesRejectsCombinedMismatches() {
    final ViewportFrame frame = baselineFrame();
    assertFalse(frame.matches(WORLD_NETHER, CONN_B, 4L, 11L, layoutGenerationEight()));
    assertFalse(frame.matches(WORLD_OVERWORLD, CONN_A, 3L, 10L, layoutGenerationEight()));
    assertFalse(frame.matches(WORLD_OVERWORLD_OTHER_ID, CONN_A, 3L, 10L, layoutGenerationSeven()));
  }

  // ------------------------------------------------------------------ API 面反射（冻结签名）

  @Test
  @DisplayName("ViewportFrame 是 record，13 个组件名/类型/顺序与卡片逐字一致")
  void viewportFrameComponentsAreFrozen() {
    final Class<ViewportFrame> type = ViewportFrame.class;
    assertTrue(type.isRecord());
    assertTrue(Modifier.isFinal(type.getModifiers()));

    final RecordComponent[] components = type.getRecordComponents();
    final List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
    assertEquals(List.of("frameId", "targetGeneration", "layoutGeneration", "cameraGeneration",
        "world", "connectionId", "serverTick", "cameraPosition", "view", "projection",
        "inverseView", "inverseProjection", "framebufferRect"), names);

    final List<Class<?>> types = Arrays.stream(components).map(RecordComponent::getType).toList();
    assertEquals(List.of(long.class, long.class, long.class, long.class,
        WorldRef.class, Ids.ConnectionId.class, OptionalLong.class, Position.class,
        FrameMatrix.class, FrameMatrix.class, FrameMatrix.class, FrameMatrix.class,
        FramebufferRect.class), types);
  }

  @Test
  @DisplayName("ViewportFrame.matches 签名逐字一致（参数类型与返回 boolean）")
  void viewportFrameMatchesSignatureIsFrozen() throws ReflectiveOperationException {
    final Method matches = ViewportFrame.class.getMethod("matches", WorldRef.class,
        Ids.ConnectionId.class, long.class, long.class, ViewportLayout.class);
    assertEquals(boolean.class, matches.getReturnType());
    assertTrue(Modifier.isPublic(matches.getModifiers()));
    assertFalse(Modifier.isStatic(matches.getModifiers()));
    // 公开方法面恰好为组件访问器 + matches + equals/hashCode/toString。
    final Set<String> publicNames = Arrays.stream(ViewportFrame.class.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .map(Method::getName)
        .collect(Collectors.toSet());
    assertTrue(publicNames.containsAll(new ArrayList<>(List.of("matches", "equals", "hashCode", "toString"))));
    assertEquals(13 + 4, publicNames.size(), "公开方法面必须恰为 13 个访问器 + matches/equals/hashCode/toString");
  }
}