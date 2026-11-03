package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import java.util.Arrays;

/**
 * R18.1b 不可变纯数据矩阵载荷：固定 16 个 {@code double}，存储顺序为 <strong>column-major</strong>。
 *
 * <p>
 * 本类型只承载“本次实际传给渲染器的 16 个值”，不做任何矩阵数学：
 * <ul>
 * <li><strong>不</strong>提供 identity / 乘法 / 求逆 / transpose / 变换点等运算（后续由 MatrixMath 承担）；</li>
 * <li><strong>不</strong>引用任何原生矩阵类型（无 LWJGL/JOML {@code Matrix4f}，无 MC/Forge 类型）；</li>
 * <li><strong>不</strong>暴露可变内部缓冲，<strong>不</strong>提供 copy setter，任何 getter 都返回副本。</li>
 * </ul>
 *
 * <p>
 * <strong>构造语义（顺序被冻结）</strong>：先校验输入数组非 null、长度恰为 16，随后<strong>立即 clone</strong>，
 * 再对<strong>副本</strong>逐项校验有限（{@code NaN}/±Infinity 抛 {@link IllegalArgumentException}）。因此实际存进
 * 字段的值一定是有限值。刻意<strong>不</strong>“先校验原数组再 clone”：若调用方在两次访问之间并发修改原数组，那种写法会
 * 造成“检查与存储不一致”（检查通过但存下非有限值），本实现从结构上排除该窗口。调用方之后对原数组的任意修改也不会影响
 * 本对象。
 *
 * <p>
 * <strong>读出语义</strong>：{@link #get(int, int)} 的下标换算固定为 {@code column * 4 + row}，{@code row} 与
 * {@code column} 都必须落在 {@code 0..3}，越界抛 {@link IllegalArgumentException}（不静默 clamp、不取模）。
 * {@link #columnMajor()} 每次调用都返回新的 clone 副本。
 *
 * <p>
 * <strong>等值语义</strong>：{@link #equals(Object)} 与 {@link #hashCode()} 使用 {@link Arrays#equals(double[], double[])}
 * 与 {@link Arrays#hashCode(double[])} 比较<strong>全部 16 个值</strong>，遵循 {@code double} 数组语义——即
 * {@code -0.0} 与 {@code +0.0} <strong>不</strong>相等，{@code NaN} 被视为相等（但本类型构造已拒绝 NaN，故不构成实际状态）。
 * 不与其他类型相等，也不做跨类型比较。
 *
 * <p>
 * <strong>载荷合法性</strong>：任意<strong>有限</strong>矩阵（包含奇异矩阵、非正交矩阵、任意缩放）都可作为合法载荷；
 * 数学操作的合法性由后续任务验证。本类型不声称、也未提供任何求逆证据。
 *
 * <p>
 * 本类型不可变：字段 {@code private final}，全部方法无状态、可重入。
 */
public final class FrameMatrix {

    /** 固定维度：4x4 = 16 个 {@code double}。 */
    private static final int LENGTH = 16;

    /** 每行/每列的长度（4x4）。 */
    private static final int DIMENSION = 4;

    /** column-major 存储：下标 {@code column * 4 + row}。 */
    private final double[] values;

    /**
     * 以 column-major 顺序的 16 个有限 {@code double} 构造。
     *
     * <p>
     * 输入数组<strong>先被 clone</strong>，随后只对副本做有限性校验；原数组在本构造返回后不再被读取。
     *
     * @param columnMajor column-major 顺序的 16 个值，非 null，长度恰为 16，每个值必须有限
     * @throws IllegalArgumentException 数组为 null、长度不是 16，或任一值为 NaN/±Infinity
     */
    public FrameMatrix(double[] columnMajor) {
        if (columnMajor == null) {
            throw new IllegalArgumentException("columnMajor must not be null");
        }
        if (columnMajor.length != LENGTH) {
            throw new IllegalArgumentException(
                    "columnMajor must have exactly " + LENGTH + " values, got " + columnMajor.length);
        }
        // 先 clone 再校验副本：保证“被检查的数组”与“被存储的数组”是同一个对象，不存在并发修改窗口。
        final double[] copy = columnMajor.clone();
        for (int index = 0; index < LENGTH; index++) {
            if (!Double.isFinite(copy[index])) {
                throw new IllegalArgumentException(
                        "columnMajor[" + index + "] must be finite: " + copy[index]);
            }
        }
        this.values = copy;
    }

    /**
     * 读取第 {@code row} 行、第 {@code column} 列的值。
     *
     * <p>
     * 下标换算固定为 {@code column * 4 + row}（column-major）。
     *
     * @param row    行下标，必须为 {@code 0..3}
     * @param column 列下标，必须为 {@code 0..3}
     * @return 该位置的值（构造已保证有限）
     * @throws IllegalArgumentException 任一 basis 越界
     */
    public double get(int row, int column) {
        if (row < 0 || row >= DIMENSION) {
            throw new IllegalArgumentException("row must be in 0.." + (DIMENSION - 1) + ": " + row);
        }
        if (column < 0 || column >= DIMENSION) {
            throw new IllegalArgumentException("column must be in 0.." + (DIMENSION - 1) + ": " + column);
        }
        return values[column * DIMENSION + row];
    }

    /**
     * 返回 column-major 顺序的全部 16 个值的<strong>副本</strong>。
     *
     * <p>
     * 每次调用都返回新的数组；修改返回值不会影响本对象。
     *
     * @return column-major 顺序 16 个值的独立副本
     */
    public double[] columnMajor() {
        return values.clone();
    }

    /**
     * 全部 16 个值逐项相等时为 {@code true}（{@code double} 数组语义：{@code -0.0} 不等于 {@code +0.0}）。
     *
     * <p>
     * 不与 {@code null}、也不与其他任何类型相等。
     *
     * @param other 比较对象
     * @return 同一对象，或另一个 {@code FrameMatrix} 且 16 个值按 {@link Arrays#equals(double[], double[])} 相等
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FrameMatrix frameMatrix)) {
            return false;
        }
        return Arrays.equals(values, frameMatrix.values);
    }

    /**
     * 基于全部 16 个值的散列（{@link Arrays#hashCode(double[])}）。
     *
     * @return 与 {@link #equals(Object)} 一致的散列值
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
