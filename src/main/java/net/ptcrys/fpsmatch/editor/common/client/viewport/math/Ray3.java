package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;

/**
 * R18.2a 一次反投影的结果载荷：捕获帧 ID、<strong>世界空间</strong>近平面起点、单位方向与有限正长度。
 *
 * <p>
 * <strong>几何定义（固定）</strong>：{@code origin} 是该像素视线与<em>近平面</em>的交点（世界坐标，
 * 已包含相机平移），{@code direction} 是近平面指向远平面的单位方向，线段覆盖
 * {@code [origin, origin + maxDistance * direction]}，即 {@code [near, far]}。
 *
 * <p>
 * <strong>构造约束（逐条固定）</strong>
 * <ul>
 * <li>{@code frameId > 0}：必须来自产生该射线的同一个捕获帧，不提供默认值；</li>
 * <li>{@code origin}/{@code direction} 非空（{@code null} 抛 {@link IllegalArgumentException}），
 * 其分量有限性由 {@link Position}/{@link Vector3} 各自保证；</li>
 * <li>{@code maxDistance} 必须有限且 {@code > 0}：{@code 0} 或 {@code NaN} 都不是可用线段；</li>
 * <li>{@code direction} 必须<strong>已经</strong>是单位向量：
 * {@code |hypot(hypot(x, y), z) - 1| <= }{@value #UNIT_DIRECTION_TOLERANCE}。本类型
 * <strong>不</strong>做静默归一化：不满足即拒绝，绝不把非单位向量“修好”后返回。</li>
 * </ul>
 *
 * <p>
 * 本类型不做除法、不做平移、不读矩阵；所有数值都由 {@link ProjectionMath} 在真实逆矩阵上算好后原样保存。
 *
 * @param frameId     产生该射线的捕获帧 ID，必须 &gt; 0
 * @param origin      世界空间近平面起点，非空
 * @param direction   世界空间单位方向，非空
 * @param maxDistance 近到远的有向长度，有限且 &gt; 0
 */
public record Ray3(long frameId, Position origin, Vector3 direction, double maxDistance) {

    /** {@code direction} 单位性允许的绝对偏差上限（包内常量，不扩大公共 API）。 */
    static final double UNIT_DIRECTION_TOLERANCE = 1e-12D;

    /**
     * 校验帧 ID、非空载荷、正长度与方向单位性。
     *
     * @throws IllegalArgumentException {@code frameId <= 0}，{@code origin}/{@code direction} 为
     *                                  {@code null}，{@code maxDistance} 非有限或不为正，或方向不是单位向量
     */
    public Ray3 {
        if (frameId <= 0L) {
            throw new IllegalArgumentException("frameId must be > 0: " + frameId);
        }
        if (origin == null) {
            throw new IllegalArgumentException("origin must not be null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (!Double.isFinite(maxDistance)) {
            throw new IllegalArgumentException("maxDistance must be finite but was " + maxDistance);
        }
        if (maxDistance <= 0.0D) {
            throw new IllegalArgumentException("maxDistance must be > 0 but was " + maxDistance);
        }
        requireUnitDirection(direction);
    }

    private static void requireUnitDirection(Vector3 direction) {
        double norm = Math.hypot(Math.hypot(direction.x(), direction.y()), direction.z());
        if (!Double.isFinite(norm) || Math.abs(norm - 1.0D) > UNIT_DIRECTION_TOLERANCE) {
            throw new IllegalArgumentException(
                    "direction must be a unit vector: |hypot(hypot(x, y), z) - 1| <= " + UNIT_DIRECTION_TOLERANCE + " but hypot was " + norm);
        }
    }
}
