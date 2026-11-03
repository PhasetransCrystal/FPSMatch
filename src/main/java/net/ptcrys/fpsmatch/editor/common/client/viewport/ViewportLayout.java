package net.ptcrys.fpsmatch.editor.common.client.viewport;

import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;

import java.util.Optional;

/**
 * R18.1a 视窗布局：把逻辑矩形经已冻结的 {@link ScaleTransform} 映射为帧缓冲像素矩形（R1.2）。
 *
 * <p>
 * 原点/scale/framebuffer 一律<strong>左上</strong>。本 record 不渲染、不取相机、不绑定 FBO、不读取
 * Window/ModernUI、不做 OpenGL 底部原点翻转、无矩阵/Gizmo/网络。{@code layoutGeneration} 由 owner 发布，
 * 本类型不自行分配或修改。
 *
 * <p>
 * <strong>四组件（R18.1a2 扩展）</strong>：{@code layoutGeneration}/{@code logicalRect}/
 * {@code scaleTransform} 之外增加 {@link #measuredFramebufferBounds()}。后者为原生测量链携带的<strong>原始
 * framebuffer 像素四边界</strong>：载荷复用不可变的 {@link LogicalRect}，但单位明确是 framebuffer 像素，
 * <strong>不是</strong> logical。{@link Optional#empty()} 表示没有独立原生像素测量（旧三参数构造）。
 * 它既不是新的 UI 对象，也不是可变缓存；present 时保持原引用，不做归一化、不重新分配 generation、
 * 不改 density/原点。record 等值包含 Optional 值，因此原始像素测量变化会改变 layout，即使投影后的 rect
 * 暂时相同；旧三参数对象的 Optional 恒为 empty，旧等值副本继续相等。
 *
 * <p>
 * <strong>规范构造器</strong>：先沿用原 {@code layoutGeneration >= 0} 与 null 校验，再要求
 * {@code measuredFramebufferBounds} 非 null。present 时用<strong>唯一逆公式</strong>从原始 pixel bounds
 * 独立构造 expected logicalRect（{@code left = (pixel.left - pixelOriginX) / xScale}、{@code top} 同理、
 * {@code width = pixel.width / xScale}、{@code height = pixel.height / yScale}），各求值非有限抛
 * {@link IllegalArgumentException}，并要求 {@code expected.equals(logicalRect)} 成立；不成立抛
 * {@link IllegalArgumentException}。该逆公式<strong>不</strong>承诺再投影能 bit-exact 还原 raw 边界——
 * 这正是保存原始边界的理由。<strong>不</strong>加 epsilon、<strong>不</strong> clamp、<strong>不</strong>用
 * FMA、<strong>不</strong>取整、<strong>不</strong>吞异常。
 *
 * <p>
 * {@link #framebufferRect()} 使用<strong>唯一</strong>像素消费算法（clamp / 有界 floor·ceil / 零轴规则
 * 全类只有一份实现）：
 * <ol>
 * <li><strong>empty（旧路径，完全保持旧语义）</strong>：把 {@code logicalRect} 的 {@code left/right/top/bottom}
 * 经 {@link ScaleTransform} 映射为 pixel 值（左上加左上，无 Y 翻转）；任何非有限输出由
 * {@code ScaleTransform} 抛 {@link IllegalArgumentException}。空轴标志取
 * {@code logicalRect.width()/height() == 0}。</li>
 * <li><strong>present（原生路径）</strong>：直接把原始 pixel 四条 edge 交给同一 clamp/round helper，
 * 密度和原点<strong>不再</strong>应用，也不对 raw bounds 先做 int cast；空轴标志必须取原始
 * {@code measuredFramebufferBounds} 自身的 {@code width()/height() == 0}，不能沿用逆坐标的空轴
 * （{@code raw.width = Double.MIN_VALUE} 且 {@code xScale = 2} 时逆 width 下溢成 0，但原始像素跨度
 * 仍非零，该轴必须仍 ceil 出 1px）。</li>
 * <li><strong>两路径共用</strong>：按 framebuffer 边界做交集，把每个 edge clamp 到
 * {@code [0, framebufferWidth]} 或 {@code [0, framebufferHeight]}；min edge 取 {@code floor}、max edge
 * 取 {@code ceil}，<strong>clamp 之后</strong>才安全转换为 {@code int}（极大有限 edge 不会因提前 cast
 * 造成整数溢出）；{@code x}/{@code y} 为交集左上，{@code width = right - x}、
 * {@code height = bottom - y}。framebuffer 尺寸仍取 {@link ScaleTransform}。</li>
 * </ol>
 *
 * <p>
 * <strong>空维度特别规则</strong>：若该轴的空轴标志为真，该轴映射后的 max edge 直接等于
 * {@code floor(clamped min edge)}，不对同一个非整数 edge 取 {@code ceil}，因此不会凭空产生 1px；旧路径下
 * 该标志来自逻辑 {@code width == 0}/{@code height == 0}，原生路径下来自原始像素 {@code width == 0}/
 * {@code height == 0}。非空且完全落在 framebuffer 外时，经交集自然为空（例如完全右侧的 rect 得到
 * {@code x = framebufferWidth} 且 {@code width = 0}）。边界浮点误差<strong>不</strong>发明 epsilon 修复。
 *
 * <p>
 * 本类型不可变、方法无状态可重入；相同 generation、相同 logical/scale 与相同原始像素测量恒定给出相同结果。
 *
 * @param layoutGeneration          UI 布局代数，由 owner 发布，必须 &gt;= 0
 * @param logicalRect               逻辑矩形，非 null
 * @param scaleTransform            已冻结的 logical↔framebuffer 缩放变换，非 null
 * @param measuredFramebufferBounds 原始 framebuffer 像素边界（左上原点），非 null；empty 表示无独立原生
 *                                  测量并按旧逻辑路径消费
 */
public record ViewportLayout(long layoutGeneration, LogicalRect logicalRect, ScaleTransform scaleTransform,
                             Optional<LogicalRect> measuredFramebufferBounds) {

    /**
     * 兼容旧调用方的三参数构造：没有独立原生像素测量，{@code measuredFramebufferBounds} 为
     * {@link Optional#empty()}，行为与 R18.1a 完全一致。
     *
     * @param generation UI 布局代数，必须 &gt;= 0
     * @param rect       逻辑矩形，非 null
     * @param scale      已冻结的缩放变换，非 null
     */
    public ViewportLayout(long generation, LogicalRect rect, ScaleTransform scale) {
        this(generation, rect, scale, Optional.empty());
    }

    public ViewportLayout {
        if (layoutGeneration < 0L) {
            throw new IllegalArgumentException("layoutGeneration must be >= 0: " + layoutGeneration);
        }
        if (logicalRect == null) {
            throw new IllegalArgumentException("logicalRect must not be null");
        }
        if (scaleTransform == null) {
            throw new IllegalArgumentException("scaleTransform must not be null");
        }
        if (measuredFramebufferBounds == null) {
            throw new IllegalArgumentException("measuredFramebufferBounds must not be null");
        }
        if (measuredFramebufferBounds.isPresent()) {
            final LogicalRect raw = measuredFramebufferBounds.get();
            final LogicalRect expected = expectedLogicalRect(raw, scaleTransform);
            if (!expected.equals(logicalRect)) {
                throw new IllegalArgumentException(
                        "logicalRect must equal the inverse of measuredFramebufferBounds: expected " + expected + " but was " + logicalRect);
            }
        }
    }

    /**
     * 把当前布局映射为帧缓冲像素矩形（左上原点，R1.2 语义）。
     *
     * <p>
     * empty 时走旧路径（logical 经 {@link ScaleTransform} 投影后交集）；present 时直接用原始像素四边界
     * 交集。两路径共用同一 clamp/floor/ceil/零轴 helper，x/y 取交集左上，width/height 由交集右/下减左上得到，
     * 右/下为开边界。结果四组件恒 &gt;= 0 且加法不回绕。
     *
     * @return 映射后的帧缓冲矩形；与该 framebuffer 无交集时为空矩形
     * @throws IllegalArgumentException 任一映射结果非有限
     */
    public FramebufferRect framebufferRect() {
        if (measuredFramebufferBounds.isPresent()) {
            final LogicalRect raw = measuredFramebufferBounds.get();
            return pixelBounds(raw.left(), raw.right(), raw.top(), raw.bottom(),
                    raw.width() == 0.0D, raw.height() == 0.0D,
                    scaleTransform.framebufferWidth(), scaleTransform.framebufferHeight());
        }
        return pixelBounds(
                scaleTransform.toFramebufferX(logicalRect.left()),
                scaleTransform.toFramebufferX(logicalRect.right()),
                scaleTransform.toFramebufferY(logicalRect.top()),
                scaleTransform.toFramebufferY(logicalRect.bottom()),
                logicalRect.width() == 0.0D, logicalRect.height() == 0.0D,
                scaleTransform.framebufferWidth(), scaleTransform.framebufferHeight());
    }

    /**
     * 唯一逆公式：由原始 framebuffer 像素边界与 scale 计算 expected logicalRect。
     *
     * <p>
     * 每项求值非有限即抛 {@link IllegalArgumentException}；不做 epsilon/clamp/FMA/取整。
     */
    private static LogicalRect expectedLogicalRect(LogicalRect raw, ScaleTransform scale) {
        final double expectedLeft = scale.toLogicalX(raw.left());
        final double expectedTop = scale.toLogicalY(raw.top());
        final double expectedWidth = requireFinite(raw.width() / scale.xScale(), "measured width / xScale");
        final double expectedHeight = requireFinite(raw.height() / scale.yScale(), "measured height / yScale");
        return new LogicalRect(expectedLeft, expectedTop, expectedWidth, expectedHeight);
    }

    private static double requireFinite(double value, String expression) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite result for " + expression + ": " + value);
        }
        return value;
    }

    /**
     * 唯一像素交集/取整实现：投影后的四条 edge + 空轴标志 + framebuffer 尺寸。
     *
     * <p>
     * 先 clamp 到 {@code [0, framebufferWidth/Height]}，再对 min edge 取 floor、max edge 取 ceil，之后才转
     * {@code int}；空轴标志为真时该轴 max edge 直接取 {@code floor(clamped min edge)}。
     */
    private static FramebufferRect pixelBounds(double leftEdge, double rightEdge, double topEdge, double bottomEdge,
                                               boolean emptyWidthAxis, boolean emptyHeightAxis, int framebufferWidth, int framebufferHeight) {
        final double clampedLeft = clampToBounds(leftEdge, (double) framebufferWidth);
        final double clampedRight = clampToBounds(rightEdge, (double) framebufferWidth);
        final double clampedTop = clampToBounds(topEdge, (double) framebufferHeight);
        final double clampedBottom = clampToBounds(bottomEdge, (double) framebufferHeight);

        // 交集：min edge 取 floor、max edge 取 ceil（先 clamp 后 cast，绝不提前截断成 int）。
        final double minX = Math.min(clampedLeft, clampedRight);
        final double maxX = Math.max(clampedLeft, clampedRight);
        final double minY = Math.min(clampedTop, clampedBottom);
        final double maxY = Math.max(clampedTop, clampedBottom);

        final int x = toInt(Math.floor(minX), "floor(minX)");
        int right = toInt(Math.ceil(maxX), "ceil(maxX)");
        final int y = toInt(Math.floor(minY), "floor(minY)");
        int bottom = toInt(Math.ceil(maxY), "ceil(maxY)");

        // 空维度特别规则：max edge 直接等于 floor(clamped min edge)，避免 ceil 同一非整数 edge 生 1px。
        if (emptyWidthAxis) {
            right = x;
        }
        if (emptyHeightAxis) {
            bottom = y;
        }

        return new FramebufferRect(x, y, right - x, bottom - y);
    }

    private static double clampToBounds(double edge, double upperBoundInclusive) {
        if (edge < 0.0D) {
            return 0.0D;
        }
        if (edge > upperBoundInclusive) {
            return upperBoundInclusive;
        }
        return edge;
    }

    private static int toInt(double value, String expression) {
        // clamp 后该值必落在 [0, framebufferWidth/Height] 内，理论上一定可用 int 表示；此处仅作防御性断言。
        if (!Double.isFinite(value) || value < 0.0D || value > (double) Integer.MAX_VALUE) {
            throw new IllegalArgumentException(expression + " is not representable as int: " + value);
        }
        return (int) value;
    }
}
