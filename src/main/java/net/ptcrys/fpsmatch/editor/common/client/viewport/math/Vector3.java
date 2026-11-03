package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

/**
 * R18.2a 不可变三分量普通 {@code double} 向量载荷（{@code x}、{@code y}、{@code z}）。
 *
 * <p>
 * 本类型只承载<strong>有限</strong>值，不做任何向量数学：
 * <ul>
 * <li>紧凑构造器逐项校验 {@link Double#isFinite(double)}，任一 {@code NaN}/±Infinity 抛
 * {@link IllegalArgumentException}；因此任何实例的三个分量都一定有限；</li>
 * <li><strong>不</strong>归一化、<strong>不</strong>取长度、<strong>不</strong>推断单位性——
 * {@link Ray3} 的方向单位约束由 {@link Ray3} 自己声明，此处不复制也不放宽；</li>
 * <li>不引用任何原生向量类型（无 LWJGL/JOML {@code Vector3f}，无 MC/Forge 类型）。</li>
 * </ul>
 *
 * <p>
 * 本类型是 {@code record}：值语义，{@code equals}/{@code hashCode}/{@code toString} 由编译器按三个分量
 * 生成，字段 {@code private final}，无可写共享数组，方法无状态、可重入。
 *
 * @param x 第一分量，必须有限
 * @param y 第二分量，必须有限
 * @param z 第三分量，必须有限
 */
public record Vector3(double x, double y, double z) {

    /**
     * 校验三个分量均为有限值。
     *
     * @throws IllegalArgumentException 任一分量为 {@code NaN}/±Infinity
     */
    public Vector3 {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    private static void requireFinite(double value, String component) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Vector3." + component + " must be finite but was " + value);
        }
    }
}
