package net.ptcrys.fpsmatch.editor.common.client.viewport;

/**
 * R18.1a 帧缓冲像素矩形：以窗口<strong>左上</strong>为原点的不可变值类型（整数像素单位）。
 *
 * <p>
 * 四个组件必须 &gt;= 0。{@code x + width} 与 {@code y + height} 先以 {@code long} 核算并断言
 * &lt;= {@link Integer#MAX_VALUE}，再转换/返回——因此不存在 int 回绕；一旦和超出 int 上限即抛
 * {@link IllegalArgumentException}。允许零宽或零高（空维度）；负坐标、负尺寸统一被拒。
 *
 * <p>
 * {@link #right()} = {@code x + width}、{@link #bottom()} = {@code y + height}，为精确公式，
 * <strong>不减 1</strong>（右/下是开边界）。
 *
 * <p>
 * {@link #isEmpty()} 为任一维度 == 0。{@link #contains(double, double)} 采用左/上含、右/下不含；
 * 空矩形恒返回 {@code false}；点参数为 double（允许分数像素）且必须有限，NaN/Infinity 抛
 * {@link IllegalArgumentException}。
 *
 * <p>
 * 本类型不可变、方法无状态可重入；不绑定 FBO、不读取 Window/ModernUI、不做 Y 翻转。
 *
 * @param x      左边界（左上原点），必须 &gt;= 0
 * @param y      上边界（左上原点），必须 &gt;= 0
 * @param width  宽度，必须 &gt;= 0
 * @param height 高度，必须 &gt;= 0
 */
public record FramebufferRect(int x, int y, int width, int height) {

    public FramebufferRect {
        requireNonNegativeComponent(x, "x");
        requireNonNegativeComponent(y, "y");
        requireNonNegativeComponent(width, "width");
        requireNonNegativeComponent(height, "height");
        // 以 long 核算加法上限，拒绝回绕：两个非负 int 的和最多为 2 * Integer.MAX_VALUE，long 内绝不溢出。
        requireNoIntOverflow((long) x + (long) width, "x + width");
        requireNoIntOverflow((long) y + (long) height, "y + height");
    }

    /**
     * 右边界（开边界）：{@code x + width}，精确公式，不减 1。
     *
     * <p>
     * 构造器已证明 {@code x + width <= Integer.MAX_VALUE}，故此处 int 加法不会溢出。
     *
     * @return 右边界
     */
    public int right() {
        return x + width;
    }

    /**
     * 下边界（开边界）：{@code y + height}，精确公式，不减 1。
     *
     * <p>
     * 构造器已证明 {@code y + height <= Integer.MAX_VALUE}，故此处 int 加法不会溢出。
     *
     * @return 下边界
     */
    public int bottom() {
        return y + height;
    }

    /**
     * 是否为空矩形：任一维度 == 0（{@code width == 0} 或 {@code height == 0}）。
     *
     * @return 任一维度为 0 时为 {@code true}
     */
    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    /**
     * 像素点是否落在矩形内：左/上含、右/下不含。
     *
     * <p>
     * 空矩形恒为 {@code false}。坐标参数允许分数像素，但必须有限；NaN/Infinity 抛
     * {@link IllegalArgumentException}。
     *
     * @param pixelX 像素 X，必须有限
     * @param pixelY 像素 Y，必须有限
     * @return 落在 {@code [x, right) x [y, bottom)} 内时为 {@code true}
     * @throws IllegalArgumentException 任一参数非有限
     */
    public boolean contains(double pixelX, double pixelY) {
        requireFinitePoint(pixelX, "pixelX");
        requireFinitePoint(pixelY, "pixelY");
        if (isEmpty()) {
            return false;
        }
        return pixelX >= x && pixelX < right() && pixelY >= y && pixelY < bottom();
    }

    private static void requireNonNegativeComponent(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0: " + value);
        }
    }

    private static void requireNoIntOverflow(long sum, String expression) {
        if (sum > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(expression + " overflows int: " + sum);
        }
    }

    private static void requireFinitePoint(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
