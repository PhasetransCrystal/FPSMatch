package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import java.util.Optional;

/**
 * R18.1c 有界矩阵数学：{@link FrameMatrix} 上的单位矩阵、乘法、列向量变换与受限求逆。
 *
 * <p>
 * <strong>固定契约（不被本实现放宽或收紧）</strong>：
 * <ul>
 * <li><strong>column-major 只决定存储</strong>。代数一律使用 <em>column vectors</em>：{@link #multiply}
 * 返回 {@code left * right}，即 {@code right} 先作用；{@link #transform} 的结果第 {@code r} 行为
 * {@code M[r,0]*x + M[r,1]*y + M[r,2]*z + M[r,3]*w}。</li>
 * <li>全部运算为<strong>普通 {@code double}</strong> 算术，按 {@code k = 0..3} 顺序从 {@code +0.0}
 * 累加；<strong>禁用 FMA</strong>（每次乘法与每次加法各自舍入，绝不使用 {@code Math.fma} 或任何融合乘加）。</li>
 * <li>逐次 product 与逐次 sum 都必须有限；任一非有限<strong>立即</strong>失败，<strong>不</strong>依赖后续相消恢复值。
 * 无 epsilon、无 saturate、无浮点修补。有限输入<strong>不</strong>保证运算成功。</li>
 * <li>{@link #identity()} / {@link #multiply} / {@link #transform} 的失败是 {@link IllegalArgumentException}
 * （非有限中间结果），{@link #inverse} 的<strong>数值</strong>失败是 {@link Optional#empty()}。</li>
 * </ul>
 *
 * <p>
 * <strong>inverse 的数值失败契约</strong>：奇异、病态或出现非有限中间结果都返回
 * {@code Optional.empty()}；<strong>不</strong>返回伪造单位矩阵，也<strong>不</strong>宣称所有可逆 {@code double}
 * 矩阵都可用。{@code empty} 无法区分 singular / condition / residual / overflow，调用方<strong>不得</strong>猜测精确原因。
 * 公共 {@link #multiply} 的 {@code IllegalArgumentException} 在结构上<strong>不可能</strong>从 {@code inverse}
 * 泄漏：内部数值路径用布尔状态表达失败，全程不抛、也不捕捉异常（未使用 {@code catch Throwable}）。
 *
 * <p>
 * 本类无状态：私有构造器、无实例，全部方法可重入。
 */
public final class MatrixMath {

    /** 固定维度 4。 */
    private static final int DIMENSION = 4;

    /** 4x4 = 16 个 {@code double}。 */
    private static final int LENGTH = DIMENSION * DIMENSION;

    /** 增强矩阵宽度：左 4 列为输入，右 4 列为单位矩阵/候选逆。 */
    private static final int AUGMENTED_WIDTH = DIMENSION * 2;

    /** 相对单位矩阵的最大绝对元素残差上限（双向量测）。 */
    private static final double RESIDUAL_LIMIT = 1e-8D;

    /**
     * 条件估计上限 {@code norm(M) * norm(candidate) <= 1e12}。
     *
     * <p>
     * 这只是<strong>数值可用性上限</strong>，不是精确 condition number，也不是相对 pivot 阈值。
     */
    private static final double CONDITION_LIMIT = 1e12D;

    private MatrixMath() {
        throw new AssertionError("no instances");
    }

    /**
     * 4x4 单位矩阵（column-major）。
     *
     * @return 全新的不可变单位矩阵
     */
    public static FrameMatrix identity() {
        final double[] values = new double[LENGTH];
        values[0] = 1.0D;
        values[5] = 1.0D;
        values[10] = 1.0D;
        values[15] = 1.0D;
        return new FrameMatrix(values);
    }

    /**
     * 计算 {@code left * right}（column vectors 语义：{@code right} 先作用）。
     *
     * <p>
     * 结果元素 {@code (row, column)} 为 {@code sum over k = 0..3 of left[row,k] * right[k,column]}，
     * 按 {@code k} 递增顺序从 {@code +0.0} 累加普通 {@code double} 乘加。
     *
     * @param left  左乘矩阵，非 null
     * @param right 右乘矩阵，非 null
     * @return 新的不可变乘积矩阵
     * @throws IllegalArgumentException 任一参数为 null，或任一 product/sum 中间结果非有限
     */
    public static FrameMatrix multiply(FrameMatrix left, FrameMatrix right) {
        requireNonNull(left, "left");
        requireNonNull(right, "right");
        final double[] result = new double[LENGTH];
        if (!multiplyFinite(left.columnMajor(), right.columnMajor(), result)) {
            throw new IllegalArgumentException("multiply produced a non-finite intermediate result");
        }
        return new FrameMatrix(result);
    }

    /**
     * 用 {@code matrix} 变换列向量 {@code vector}（不做 {@code w} 除法、不做归一化、不做平移推断）。
     *
     * <p>
     * 结果第 {@code r} 个分量为
     * {@code M[r,0]*x + M[r,1]*y + M[r,2]*z + M[r,3]*w}，按 {@code k = 0..3} 从 {@code +0.0} 顺序累加。
     * {@code w = 1}（点）与 {@code w = 0}（方向）的差别完全由这段代数自然产生，不被特殊处理。
     *
     * @param matrix 变换矩阵，非 null
     * @param vector 四分量列向量，非 null
     * @return 变换后的不可变四分量向量
     * @throws IllegalArgumentException 任一参数为 null，或任一 product/sum 中间结果非有限
     */
    public static Vector4 transform(FrameMatrix matrix, Vector4 vector) {
        requireNonNull(matrix, "matrix");
        requireNonNull(vector, "vector");
        final double[] columnMajor = matrix.columnMajor();
        final double[] components = { vector.x(), vector.y(), vector.z(), vector.w() };
        final double[] result = new double[DIMENSION];
        for (int row = 0; row < DIMENSION; row++) {
            double sum = +0.0D;
            for (int k = 0; k < DIMENSION; k++) {
                final double product = columnMajor[k * DIMENSION + row] * components[k];
                if (!Double.isFinite(product)) {
                    throw new IllegalArgumentException(
                            "transform produced a non-finite intermediate product at row " + row + ", k " + k);
                }
                final double next = sum + product;
                if (!Double.isFinite(next)) {
                    throw new IllegalArgumentException(
                            "transform produced a non-finite intermediate sum at row " + row + ", k " + k);
                }
                sum = next;
            }
            result[row] = sum;
        }
        return new Vector4(result[0], result[1], result[2], result[3]);
    }

    /**
     * 按固定 Gauss-Jordan（partial pivot）算法求逆，返回候选或 {@link Optional#empty()}。
     *
     * <p>
     * <strong>固定步骤</strong>（常量空间，仅一个 {@code double[4][8]} 增强矩阵）：
     * <ol>
     * <li>复制 {@code M} 到 row-major 增强矩阵左半，右半为单位矩阵；</li>
     * <li>对 {@code col = 0..3}，在 {@code row = col..3} 中取 {@code abs(a[row][col])} 最大者为 pivot，
     * 平局取<strong>最小 row</strong>；{@code max == 0} 立即拒绝（<strong>不</strong>另设相对 pivot 阈值）；
     * 交换整行（8 个分量）；</li>
     * <li>pivot 行全部 8 个分量除以 pivot（逐项检查有限），随后 pivot 位置<strong>明确置 1</strong>；
     * 对其余行依次消元，缓存 {@code factor = a[row][col]}，按 {@code k = 0..7} 计算普通
     * {@code a[row][k] - factor * a[col][k]}，product 与 difference 都必须有限，该列<strong>明确置 0</strong>；
     * 禁止以 {@code float}/{@code BigDecimal} 替代算法本身、禁 FMA；</li>
     * <li>右半即候选逆，保存为 column-major 不可变 {@link FrameMatrix}；计算输入与候选的 infinity norm
     * （每行 {@code abs} 总和的最大值，每项 sum 必须有限）；{@code conditionEstimate = norm(M) * norm(candidate)}
     * 必须有限且 {@code <= 1e12}，否则 {@code empty}；</li>
     * <li>用同样的普通 {@code double} 按 {@code k} 顺序乘加分别计算 {@code M * candidate} 与
     * {@code candidate * M}；任何非有限中间结果 {@code empty}；两者相对单位矩阵的<strong>最大绝对元素残差</strong>
     * 都必须 {@code <= 1e-8}（<strong>不</strong>按 norm 放宽），才返回候选。</li>
     * </ol>
     *
     * @param matrix 待求逆矩阵，非 null
     * @return 候选逆矩阵；数值门槛不满足时为 {@link Optional#empty()}
     * @throws IllegalArgumentException {@code matrix} 为 null
     */
    public static Optional<FrameMatrix> inverse(FrameMatrix matrix) {
        if (matrix == null) {
            throw new IllegalArgumentException("matrix must not be null");
        }
        final double[][] original = toRows(matrix);
        final double[][] augmented = new double[DIMENSION][AUGMENTED_WIDTH];
        for (int row = 0; row < DIMENSION; row++) {
            for (int column = 0; column < DIMENSION; column++) {
                augmented[row][column] = original[row][column];
                augmented[row][DIMENSION + column] = (row == column) ? 1.0D : 0.0D;
            }
        }

        for (int column = 0; column < DIMENSION; column++) {
            int pivotRow = -1;
            double pivotMagnitude = 0.0D;
            for (int row = column; row < DIMENSION; row++) {
                final double magnitude = Math.abs(augmented[row][column]);
                // 严格大于才替换：平局保留更小的 row。
                if (pivotRow < 0 || magnitude > pivotMagnitude) {
                    pivotMagnitude = magnitude;
                    pivotRow = row;
                }
            }
            if (pivotMagnitude == 0.0D) {
                // 无 pivot 阈值之外的相对判断：max == 0 即为精确奇异。
                return Optional.empty();
            }
            if (pivotRow != column) {
                final double[] swap = augmented[column];
                augmented[column] = augmented[pivotRow];
                augmented[pivotRow] = swap;
            }

            final double pivot = augmented[column][column];
            for (int k = 0; k < AUGMENTED_WIDTH; k++) {
                final double scaled = augmented[column][k] / pivot;
                if (!Double.isFinite(scaled)) {
                    return Optional.empty();
                }
                augmented[column][k] = scaled;
            }
            augmented[column][column] = 1.0D;

            for (int row = 0; row < DIMENSION; row++) {
                if (row == column) {
                    continue;
                }
                final double factor = augmented[row][column];
                for (int k = 0; k < AUGMENTED_WIDTH; k++) {
                    final double product = factor * augmented[column][k];
                    if (!Double.isFinite(product)) {
                        return Optional.empty();
                    }
                    final double difference = augmented[row][k] - product;
                    if (!Double.isFinite(difference)) {
                        return Optional.empty();
                    }
                    augmented[row][k] = difference;
                }
                augmented[row][column] = 0.0D;
            }
        }

        final double[][] candidateRows = new double[DIMENSION][DIMENSION];
        final double[] candidateColumnMajor = new double[LENGTH];
        for (int row = 0; row < DIMENSION; row++) {
            for (int column = 0; column < DIMENSION; column++) {
                final double value = augmented[row][DIMENSION + column];
                candidateRows[row][column] = value;
                candidateColumnMajor[column * DIMENSION + row] = value;
            }
        }

        final double inputNorm = infinityNorm(original);
        if (!Double.isFinite(inputNorm)) {
            return Optional.empty();
        }
        final double candidateNorm = infinityNorm(candidateRows);
        if (!Double.isFinite(candidateNorm)) {
            return Optional.empty();
        }
        final double conditionEstimate = inputNorm * candidateNorm;
        if (!Double.isFinite(conditionEstimate) || conditionEstimate > CONDITION_LIMIT) {
            return Optional.empty();
        }

        if (!residualWithinLimit(original, candidateRows)) {
            return Optional.empty();
        }
        if (!residualWithinLimit(candidateRows, original)) {
            return Optional.empty();
        }
        return Optional.of(new FrameMatrix(candidateColumnMajor));
    }

    /**
     * row-major 计算 {@code left * right}，逐次检查 product/sum 有限。
     *
     * @return 全部中间结果有限时为 {@code true}；否则 {@code false}（不写出可信结果）
     */
    private static boolean multiplyFinite(double[] leftColumnMajor, double[] rightColumnMajor, double[] out) {
        for (int column = 0; column < DIMENSION; column++) {
            for (int row = 0; row < DIMENSION; row++) {
                double sum = +0.0D;
                for (int k = 0; k < DIMENSION; k++) {
                    final double product = leftColumnMajor[k * DIMENSION + row] * rightColumnMajor[column * DIMENSION + k];
                    if (!Double.isFinite(product)) {
                        return false;
                    }
                    final double next = sum + product;
                    if (!Double.isFinite(next)) {
                        return false;
                    }
                    sum = next;
                }
                out[column * DIMENSION + row] = sum;
            }
        }
        return true;
    }

    /**
     * 以 column-major 载荷读出 row-major 行数组（载荷已由 {@link FrameMatrix} 保证全部有限）。
     */
    private static double[][] toRows(FrameMatrix matrix) {
        final double[] values = matrix.columnMajor();
        final double[][] rows = new double[DIMENSION][DIMENSION];
        for (int row = 0; row < DIMENSION; row++) {
            for (int column = 0; column < DIMENSION; column++) {
                rows[row][column] = values[column * DIMENSION + row];
            }
        }
        return rows;
    }

    /**
     * infinity norm：每行 {@code abs} 总和的最大值；每项 sum 都必须有限。
     *
     * @return infinity norm；任一行累加出现非有限值时为 {@link Double#NaN}（调用方据此拒绝）
     */
    private static double infinityNorm(double[][] rows) {
        double max = 0.0D;
        for (int row = 0; row < DIMENSION; row++) {
            double sum = +0.0D;
            for (int column = 0; column < DIMENSION; column++) {
                final double next = sum + Math.abs(rows[row][column]);
                if (!Double.isFinite(next)) {
                    return Double.NaN;
                }
                sum = next;
            }
            if (sum > max) {
                max = sum;
            }
        }
        return max;
    }

    /**
     * 以普通 {@code double}（{@code k} 递增、无 FMA）计算 {@code left * right}，并检查它相对单位矩阵的
     * 最大绝对元素残差是否 {@code <= 1e-8}。
     *
     * @return 全部中间结果有限且残差在门槛内为 {@code true}
     */
    private static boolean residualWithinLimit(double[][] leftRows, double[][] rightRows) {
        for (int row = 0; row < DIMENSION; row++) {
            for (int column = 0; column < DIMENSION; column++) {
                double sum = +0.0D;
                for (int k = 0; k < DIMENSION; k++) {
                    final double product = leftRows[row][k] * rightRows[k][column];
                    if (!Double.isFinite(product)) {
                        return false;
                    }
                    final double next = sum + product;
                    if (!Double.isFinite(next)) {
                        return false;
                    }
                    sum = next;
                }
                final double expected = (row == column) ? 1.0D : 0.0D;
                final double residual = sum - expected;
                if (!Double.isFinite(residual)) {
                    return false;
                }
                if (Math.abs(residual) > RESIDUAL_LIMIT) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
