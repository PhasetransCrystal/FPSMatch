package net.ptcrys.fpsmatch.editor.common.client.viewport.host;

import net.ptcrys.fpsmatch.editor.common.client.viewport.LogicalRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;

import java.util.Optional;

/**
 * R19.1b：原生 UI 测量桥接向 owner 发布的不可变测量载荷。
 *
 * <p>
 * <strong>交付边界</strong>：本载荷只表述原生 UI 的几何事实（surface 像素尺寸、density、root 与 viewport 的
 * logical rect、以及已冻结的 {@link ViewportLayout}）。它<strong>不</strong>包含相机、渲染后端、选择拾取、
 * 输入遮挡、输入法或任何 service 成功语义；{@code READY} 只代表「原生几何测量成立」，不代表业务能力可用。
 *
 * <p>
 * 规则：
 * <ul>
 * <li>各代数必须 {@code >= 0}，且由 owner/桥接以 {@link Math#incrementExact} 单调递增发布；</li>
 * <li>{@link Status#READY} 必须携带 geometry，且外层 {@code layoutGeneration} 必须等于
 * {@link Geometry#layout()} 的 {@code layoutGeneration}（代数同源，禁止两处不一致）；</li>
 * <li>其余状态必须为空 geometry，避免把失效几何当作可用结果。</li>
 * </ul>
 *
 * @param hostEpoch         宿主纪元，由 owner 提供，必须 {@code >= 0}
 * @param bindingGeneration 绑定代数，由 owner 提供，必须 {@code >= 0}
 * @param viewGeneration    视图代数（attach/detach 递增），必须 {@code >= 0}
 * @param windowGeneration  窗口戳代数（updateWindow 递增），必须 {@code >= 0}
 * @param layoutGeneration  布局测量代数，必须 {@code >= 0}
 * @param status            原生测量状态，非 null
 * @param geometry          几何载荷；仅 {@link Status#READY} 时非空
 */
public record NativeViewportMeasurement(
                                        long hostEpoch,
                                        long bindingGeneration,
                                        long viewGeneration,
                                        long windowGeneration,
                                        long layoutGeneration,
                                        Status status,
                                        Optional<Geometry> geometry) {

    public NativeViewportMeasurement {
        if (hostEpoch < 0L) {
            throw new IllegalArgumentException("hostEpoch must be >= 0: " + hostEpoch);
        }
        if (bindingGeneration < 0L) {
            throw new IllegalArgumentException("bindingGeneration must be >= 0: " + bindingGeneration);
        }
        if (viewGeneration < 0L) {
            throw new IllegalArgumentException("viewGeneration must be >= 0: " + viewGeneration);
        }
        if (windowGeneration < 0L) {
            throw new IllegalArgumentException("windowGeneration must be >= 0: " + windowGeneration);
        }
        if (layoutGeneration < 0L) {
            throw new IllegalArgumentException("layoutGeneration must be >= 0: " + layoutGeneration);
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (geometry == null) {
            throw new IllegalArgumentException("geometry must not be null (use Optional.empty())");
        }
        if (status == Status.READY) {
            final Geometry present = geometry.orElseThrow(() -> new IllegalArgumentException(
                    "READY requires a geometry payload"));
            if (present.layout().layoutGeneration() != layoutGeneration) {
                throw new IllegalArgumentException("READY layoutGeneration mismatch: message=" + layoutGeneration + " geometry.layout=" + present.layout().layoutGeneration());
            }
        } else if (geometry.isPresent()) {
            throw new IllegalArgumentException(status + " must not carry geometry");
        }
    }

    /** 原生测量状态；四个取值互斥，不提供 Uxx/P19 结论。 */
    public enum Status {
        /** 真实 preDraw 测量成立，且 surface 尺寸与当前窗口戳一致。 */
        READY,
        /**
         * 尚未满足可测条件（root/viewport 未真实 attach、需要重新布局、root 任一实际轴 {@code <= 0}、
         * surface 任一轴 {@code 0}、surface 尺寸与窗口戳不一致、尚无窗口戳等）。viewport 零轴<strong>不</strong>属此列。
         */
        WAITING_LAYOUT,
        /**
         * 存在无法安全折算的变换或转换：非平移/非有限矩阵、非恒等 animation matrix、非有限
         * {@code alpha}/{@code transitionAlpha}、非有限或 {@code <= 0} 的 density、非零 surface global 原点。
         */
        UNSUPPORTED_TRANSFORM,
        /** 显式 {@link NativeViewportBridge#detach()} 之后：已解绑并撤销全部观察者，没有逻辑 attachment。 */
        DETACHED
    }

    /**
     * 已成立的几何载荷：surface 像素尺寸、density、root 原始 surface 像素边界、相对 root 的 viewport
     * logical rect 与已冻结布局。
     *
     * <p>
     * 单位与公式（R19.1b2 第2/4/5点；每个坐标只做一次 logical↔pixel 转换）。设 root 四角在 surface
     * 像素坐标中的 bounds 为 {@code rootPixel}、viewport 可见四角 bounds 为 {@code viewportPixel}：
     *
     * <pre>
     * rootSurfaceRect   = (rootPixel.left, rootPixel.top,
     *                      rootPixel.width, rootPixel.height)            // 单位：surface 像素
     * viewportSurfaceRect = ((viewportPixel.left - rootPixel.left) / density,
     *                        (viewportPixel.top - rootPixel.top) / density,
     *                        viewportPixel.width / density,
     *                        viewportPixel.height / density)              // 单位：相对 root 的 logical
     * layout.scaleTransform = (density, density, rootPixel.left, rootPixel.top,
     *                          surfaceWidth, surfaceHeight)
     * layout.measuredFramebufferBounds = viewportPixel 完整边界（present 原始像素，不被除 density）
     * </pre>
     *
     * {@code rootSurfaceRect} 复用 {@link LogicalRect} 载荷类型但<strong>不是</strong> logical：其
     * {@code left}/{@code top} 就是 root 的原始像素原点，可非零、可负、可分数，未做任何除/乘往返；
     * {@link ScaleTransform#pixelOriginX()} 是 logical=0 映射到的<strong>像素</strong>平移，所以这里必须与
     * bridge 使用<strong>同一个</strong>原始像素分量以保持 bit-exact 相等，绝不写回「把 root 原点先除
     * density 再乘 density」这类会吞掉真实原点的往返表达式。
     *
     * <p>
     * {@code layout} 必须是携带 present 原生像素测量的布局，且该 present 边界必须能按
     * {@link ScaleTransform} 的<strong>唯一逆公式</strong>（{@code (pixel - pixelOrigin) / scale} 与
     * {@code pixel.width / scale}）反演回 {@code viewportSurfaceRect}；{@code layout.logicalRect()} 亦须等于
     * {@code viewportSurfaceRect}。本条独立复算，不把「layout 内部两处公式互相一致」当作正确性证据。
     * 零 viewport 合法：两轴可为 0，表示暂不可渲染/拾取；root 两轴必须 &gt; 0。
     *
     * @param surfaceWidth        真实 surface 像素宽度
     * @param surfaceHeight       真实 surface 像素高度
     * @param density             资源密度（DisplayMetrics.density），每个坐标只施加一次
     * @param rootSurfaceRect     root 自身四角 bounds（<strong>surface 像素</strong>，两轴必须 &gt; 0，
     *                            原点非零/负/分数时照实保留）
     * @param viewportSurfaceRect viewport 可见四角 bounds（logical，相对 root 左上原点，已含 surface/祖先裁剪，
     *                            两轴可为 0 表示暂不可渲染/拾取）
     * @param layout              已冻结的 ViewportLayout，必须携带 present 原生像素测量
     */
    public record Geometry(
                           int surfaceWidth,
                           int surfaceHeight,
                           double density,
                           LogicalRect rootSurfaceRect,
                           LogicalRect viewportSurfaceRect,
                           ViewportLayout layout) {

        public Geometry {
            if (surfaceWidth <= 0) {
                throw new IllegalArgumentException("surfaceWidth must be strictly positive: " + surfaceWidth);
            }
            if (surfaceHeight <= 0) {
                throw new IllegalArgumentException("surfaceHeight must be strictly positive: " + surfaceHeight);
            }
            if (!Double.isFinite(density) || density <= 0.0D) {
                throw new IllegalArgumentException("density must be finite and strictly positive: " + density);
            }
            if (rootSurfaceRect == null) {
                throw new IllegalArgumentException("rootSurfaceRect must not be null");
            }
            if (viewportSurfaceRect == null) {
                throw new IllegalArgumentException("viewportSurfaceRect must not be null");
            }
            if (layout == null) {
                throw new IllegalArgumentException("layout must not be null");
            }

            final ScaleTransform actual = layout.scaleTransform();
            if (actual.framebufferWidth() != surfaceWidth || actual.framebufferHeight() != surfaceHeight) {
                throw new IllegalArgumentException("layout framebuffer " + actual.framebufferWidth() + "x" + actual.framebufferHeight() + " does not match surface " + surfaceWidth + "x" + surfaceHeight);
            }
            // R19.1b2 第4点：pixelOrigin 直接取 raw root 像素分量，不再乘 density（旧的往返公式会吞掉真实原点）。
            final ScaleTransform expected = new ScaleTransform(density, density,
                    rootSurfaceRect.left(), rootSurfaceRect.top(), surfaceWidth, surfaceHeight);
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException("layout scaleTransform " + actual + " does not match the raw root pixel origin transform " + expected);
            }
            if (!layout.logicalRect().equals(viewportSurfaceRect)) {
                throw new IllegalArgumentException("layout logicalRect " + layout.logicalRect() + " does not match viewportSurfaceRect " + viewportSurfaceRect);
            }
            if (rootSurfaceRect.width() <= 0.0D || rootSurfaceRect.height() <= 0.0D) {
                throw new IllegalArgumentException("rootSurfaceRect must have both axes > 0: " + rootSurfaceRect);
            }
            // R19.1b2 第5点：只接受携带 present 原生像素测量的布局，并按唯一逆公式独立复核。
            final LogicalRect measured = layout.measuredFramebufferBounds().orElseThrow(
                    () -> new IllegalArgumentException(
                            "layout must carry a present measuredFramebufferBounds (native raw pixel bounds)"));
            final double inverseLeft = expected.toLogicalX(measured.left());
            final double inverseTop = expected.toLogicalY(measured.top());
            final double inverseWidth = measured.width() / expected.xScale();
            final double inverseHeight = measured.height() / expected.yScale();
            if (!Double.isFinite(inverseWidth) || !Double.isFinite(inverseHeight)) {
                throw new IllegalArgumentException(
                        "non-finite inverse viewport span from measuredFramebufferBounds " + measured);
            }
            if (inverseLeft != viewportSurfaceRect.left() || inverseTop != viewportSurfaceRect.top() || inverseWidth != viewportSurfaceRect.width() || inverseHeight != viewportSurfaceRect.height()) {
                throw new IllegalArgumentException("measuredFramebufferBounds " + measured + " does not invert to viewportSurfaceRect " + viewportSurfaceRect + " via " + expected);
            }
        }
    }
}
