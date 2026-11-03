package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

/**
 * R18.1c 不可变四分量普通 {@code double} 向量载荷（{@code x}、{@code y}、{@code z}、{@code w}）。
 *
 * <p>
 * 本类型只承载<strong>有限</strong>值，不做任何向量/矩阵数学：
 * <ul>
 * <li>紧凑构造器对四个分量逐项校验 {@link Double#isFinite(double)}，任一 {@code NaN}/±Infinity 抛
 * {@link IllegalArgumentException}；因此任何 {@code Vector4} 实例的四个分量都一定是有限值；</li>
 * <li>不提供 any 归一化、不假设 {@code w} 是什么、不推断平移，也不做 {@code w} 除法；</li>
 * <li>不引用任何原生向量类型（无 LWJGL/JOML {@code Vector4f}，无 MC/Forge 类型）。</li>
 * </ul>
 *
 * <p>
 * 本类型是 {@code record}：值语义、{@code equals}/{@code hashCode}/{@code toString} 由编译器按四个分量生成，
 * 字段 {@code private final}，无共享可写数组，方法无状态、可重入。
 *
 * <p>
 * 分量含义由调用方决定：{@code w = 1} 表示点、{@code w = 0} 表示方向，只是使用约定，
 * 本类型<strong>不</strong>校验、也<strong>不</strong>强制该约定。
 *
 * @param x 第一分量，必须有限
 * @param y 第二分量，必须有限
 * @param z 第三分量，必须有限
 * @param w 第四分量（齐次分量），必须有限
 */
public record Vector4(double x, double y, double z, double w) {

    /**
     * 校验四个分量均为有限值。
     *
     * @throws IllegalArgumentException 任一分量为 {@code NaN}/±Infinity
     */
    public Vector4 {
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException("x must be finite: " + x);
        }
        if (!Double.isFinite(y)) {
            throw new IllegalArgumentException("y must be finite: " + y);
        }
        if (!Double.isFinite(z)) {
            throw new IllegalArgumentException("z must be finite: " + z);
        }
        if (!Double.isFinite(w)) {
            throw new IllegalArgumentException("w must be finite: " + w);
        }
    }
}
