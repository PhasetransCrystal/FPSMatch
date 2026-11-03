package net.ptcrys.fpsmatch.editor.common.client.viewport;

/**
 * R18.1a 逻辑坐标矩形：以窗口<strong>左上</strong>为原点的不可变值类型。
 *
 * <p>
 * 四个组件必须全部有限；{@code width}/{@code height} 必须 &gt;= 0（允许 0，表示该轴为空维度），并且派生量
 * {@code left + width} 与 {@code top + height} 也必须有限——因此“组件有限却让右/下边界溢出成 Infinity”的
 * 构造被直接拒绝，{@link #right()}/{@link #bottom()} 永不为非有限值。
 *
 * <p>
 * 允许负的 {@code left}/{@code top}（逻辑原点不必落在矩形内部），允许零宽或零高；<strong>不做</strong>
 * normalize、<strong>不做</strong> clamp、也不把负宽高静默翻转成正。任何非法输入统一抛
 * {@link IllegalArgumentException}。
 *
 * <p>
 * {@link #right()} = {@code left + width}、{@link #bottom()} = {@code top + height}，为精确公式，
 * <strong>不减 1</strong>（右/下是开边界）。
 *
 * <p>
 * {@link #isEmpty()} 为任一维度 == 0；极窄但非零的矩形<strong>不</strong>视为空。
 * {@link #contains(double, double)} 采用左/上含、右/下不含；空矩形恒返回 {@code false}；点参数
 * NaN/Infinity 抛 {@link IllegalArgumentException}。
 *
 * <p>
 * 本类型不可变、方法无状态可重入；不涉及 OpenGL 底部原点、不绑定 FBO、不读取 Window/ModernUI。
 *
 * @param left   左边界（左上原点），必须有限；可为负
 * @param top    上边界（左上原点），必须有限；可为负
 * @param width  宽度，必须有限且 &gt;= 0
 * @param height 高度，必须有限且 &gt;= 0
 */
public record LogicalRect(double left, double top, double width, double height) {

    public LogicalRect {
        requireFiniteComponent(left, "left");
        requireFiniteComponent(top, "top");
        requireNonNegativeComponent(width, "width");
        requireNonNegativeComponent(height, "height");
        requireFiniteComponent(left + width, "left + width");
        requireFiniteComponent(top + height, "top + height");
    }

    /**
     * 右边界（开边界）：{@code left + width}，精确公式，不减 1。
     *
     * @return 右边界，构造器已保证有限
     */
    public double right() {
        return left + width;
    }

    /**
     * 下边界（开边界）：{@code top + height}，精确公式，不减 1。
     *
     * @return 下边界，构造器已保证有限
     */
    public double bottom() {
        return top + height;
    }

    /**
     * 是否为空矩形：任一维度 == 0（{@code width == 0} 或 {@code height == 0}）。
     *
     * <p>
     * 零宽/零高即为空；非零的窄矩形不算空。
     *
     * @return 任一维度为 0 时为 {@code true}
     */
    public boolean isEmpty() {
        return width == 0.0D || height == 0.0D;
    }

    /**
     * 点是否落在矩形内：左/上含、右/下不含。
     *
     * <p>
     * 空矩形恒为 {@code false}。坐标参数必须有限，NaN/Infinity 抛
     * {@link IllegalArgumentException}（参数校验先于空判，保证坏输入不会被“空矩形恒 false”吞掉）。
     *
     * @param x 逻辑 X，必须有限
     * @param y 逻辑 Y，必须有限
     * @return 落在 {@code [left, right) x [top, bottom)} 内时为 {@code true}
     * @throws IllegalArgumentException 任一参数非有限
     */
    public boolean contains(double x, double y) {
        requireFinitePoint(x, "x");
        requireFinitePoint(y, "y");
        if (isEmpty()) {
            return false;
        }
        return x >= left && x < right() && y >= top && y < bottom();
    }

    private static void requireFiniteComponent(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }

    private static void requireNonNegativeComponent(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        if (!(value >= 0.0D)) {
            throw new IllegalArgumentException(name + " must be >= 0: " + value);
        }
    }

    private static void requireFinitePoint(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
