package net.ptcrys.fpsmatch.editor.common.model.viewport;

/**
 * R04.2 logical/framebuffer 之间的唯一缩放变换（CONTRACTS §8）。
 *
 * <p>
 * 坐标一律以窗口<strong>左上</strong>为原点；{@code pixelOriginX}/{@code pixelOriginY} 是 logical=0
 * 映射到的像素平移：
 *
 * <pre>
 *   pixelX   = logicalX * xScale + pixelOriginX
 *   pixelY   = logicalY * yScale + pixelOriginY
 *   logicalX = (pixelX - pixelOriginX) / xScale
 *   logicalY = (pixelY - pixelOriginY) / yScale
 * </pre>
 *
 * <p>
 * 按上式直接以 Java {@code double} 运算求值（不使用 FMA、不做 round）。所有 double 组件必须有限，
 * scale 严格大于 0（{@code -0.0}/{@code +0.0} 均拒绝），framebuffer 尺寸严格大于 0；任何非法输入或
 * 求值结果非有限（含中间 subtract 溢出导致的非有限结果）都抛 {@link IllegalArgumentException}，绝不返回
 * {@code Infinity}/{@code NaN}，也没有默认值、静默修复或 clamp。任意极端 double 的精确实数运算不作承诺。
 *
 * <p>
 * 本类型<strong>不</strong>做 clamp、不做 floor/ceil、不减 1、不翻转 Y、不把 framebuffer 尺寸当作 scale，
 * 也不承诺 X/Y 使用同一 scale 或同一 origin。负数坐标与超出 framebuffer 的有限坐标照常变换，范围裁剪由后续
 * rect 交集/picking 自行决定。OpenGL 底部原点翻转只在后续 render compositing 端执行。
 *
 * <p>
 * 全部内容不可变：record 组件为 private final，方法无状态、可重入。
 *
 * @param xScale            logical→pixel 的 X 方向缩放，必须有限且严格 &gt; 0
 * @param yScale            logical→pixel 的 Y 方向缩放，必须有限且严格 &gt; 0
 * @param pixelOriginX      logicalX=0 映射到的像素 X 平移，必须有限
 * @param pixelOriginY      logicalY=0 映射到的像素 Y 平移，必须有限
 * @param framebufferWidth  帧缓冲宽度（仅元数据），必须严格 &gt; 0
 * @param framebufferHeight 帧缓冲高度（仅元数据），必须严格 &gt; 0
 */
public record ScaleTransform(double xScale, double yScale,
                             double pixelOriginX, double pixelOriginY,
                             int framebufferWidth, int framebufferHeight) {

    public ScaleTransform {
        requireFiniteComponent(xScale, "xScale");
        requireFiniteComponent(yScale, "yScale");
        requireFiniteComponent(pixelOriginX, "pixelOriginX");
        requireFiniteComponent(pixelOriginY, "pixelOriginY");
        requirePositiveScale(xScale, "xScale");
        requirePositiveScale(yScale, "yScale");
        if (framebufferWidth <= 0) {
            throw new IllegalArgumentException("framebufferWidth must be strictly positive: " + framebufferWidth);
        }
        if (framebufferHeight <= 0) {
            throw new IllegalArgumentException("framebufferHeight must be strictly positive: " + framebufferHeight);
        }
    }

    /**
     * logical X → pixel X：{@code logicalX * xScale + pixelOriginX}。
     *
     * @param logicalX 有限的 logical X（左上原点）
     * @return 未 clamp 的 pixel X
     * @throws IllegalArgumentException 参数非有限，或结果非有限
     */
    public double toFramebufferX(double logicalX) {
        requireFiniteArgument(logicalX, "logicalX");
        return requireFiniteResult(logicalX * xScale + pixelOriginX, "logicalX * xScale + pixelOriginX");
    }

    /**
     * logical Y → pixel Y：{@code logicalY * yScale + pixelOriginY}。不做 Y 翻转。
     *
     * @param logicalY 有限的 logical Y（左上原点）
     * @return 未 clamp 的 pixel Y
     * @throws IllegalArgumentException 参数非有限，或结果非有限
     */
    public double toFramebufferY(double logicalY) {
        requireFiniteArgument(logicalY, "logicalY");
        return requireFiniteResult(logicalY * yScale + pixelOriginY, "logicalY * yScale + pixelOriginY");
    }

    /**
     * pixel X → logical X：{@code (pixelX - pixelOriginX) / xScale}。
     *
     * @param pixelX 有限的 pixel X（左上原点）
     * @return 未 clamp 的 logical X
     * @throws IllegalArgumentException 参数非有限，或结果非有限（含 subtract 溢出）
     */
    public double toLogicalX(double pixelX) {
        requireFiniteArgument(pixelX, "pixelX");
        return requireFiniteResult((pixelX - pixelOriginX) / xScale, "(pixelX - pixelOriginX) / xScale");
    }

    /**
     * pixel Y → logical Y：{@code (pixelY - pixelOriginY) / yScale}。不做 Y 翻转。
     *
     * @param pixelY 有限的 pixel Y（左上原点）
     * @return 未 clamp 的 logical Y
     * @throws IllegalArgumentException 参数非有限，或结果非有限（含 subtract 溢出）
     */
    public double toLogicalY(double pixelY) {
        requireFiniteArgument(pixelY, "pixelY");
        return requireFiniteResult((pixelY - pixelOriginY) / yScale, "(pixelY - pixelOriginY) / yScale");
    }

    private static void requireFiniteComponent(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }

    private static void requireFiniteArgument(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }

    private static void requirePositiveScale(double value, String name) {
        // NaN 已在前一步拒绝；此处 !(v > 0) 同时拒绝 +0.0、-0.0 与一切负数，不做 clamp。
        if (!(value > 0.0D)) {
            throw new IllegalArgumentException(name + " must be strictly positive: " + value);
        }
    }

    private static double requireFiniteResult(double value, String expression) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite result for " + expression + ": " + value);
        }
        return value;
    }
}
