package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

/**
 * R18.2a 一次投影的结果载荷：捕获帧 ID、<strong>absolute framebuffer continuous pixel</strong> 坐标
 * 与 NDC 深度。
 *
 * <p>
 * <strong>坐标单位（固定，不被本类型重新解释）</strong>：{@code pixelX}/{@code pixelY} 是相对于
 * <em>帧缓冲左上原点</em>的连续像素坐标（允许分数像素），<strong>不是</strong>控件 local/logical 坐标，
 * 也<strong>不</strong>做 DPI/缩放换算（那属于上游）。本类型<strong>不</strong>自行猜测或校验像素范围，
 * 也<strong>不</strong>知道任何 {@code FramebufferRect}：合法性判定发生在 {@link ProjectionMath} 的
 * 半开 {@code contains} 检测点。
 *
 * <p>
 * <strong>构造约束（逐条固定）</strong>
 * <ul>
 * <li>{@code frameId > 0}：必须来自产生该投影的同一个捕获帧；未渲染出帧时为 0，因此不允许 &lt;= 0，
 * 也<strong>不</strong>提供默认值；</li>
 * <li>{@code pixelX}/{@code pixelY}/{@code ndcZ} 三个 {@code double} 必须有限；</li>
 * <li>{@code ndcZ} 必须落在闭区间 {@code [-1, 1]}（近平面为 {@code -1}、远平面为 {@code +1}）；
 * 边界值本身合法，越界或 {@code NaN} 一律拒绝。</li>
 * </ul>
 *
 * <p>
 * 本类型不持有矩阵、不做除法、不做 clamp；{@code ndcZ} 按上游实际计算值<strong>原样</strong>保存，
 * 便于消费方按同一帧的深度判断可见性。
 *
 * @param frameId 产生该投影的捕获帧 ID，必须 &gt; 0
 * @param pixelX  帧缓冲连续像素 X（左上原点），必须有限
 * @param pixelY  帧缓冲连续像素 Y（左上原点），必须有限
 * @param ndcZ    裁剪空间 NDC 深度，必须有限且落在 {@code [-1, 1]}
 */
public record PixelProjection(long frameId, double pixelX, double pixelY, double ndcZ) {

    /**
     * 校验帧 ID 与三个 {@code double} 分量。
     *
     * @throws IllegalArgumentException {@code frameId <= 0}，任一分量非有限，或 {@code ndcZ} 越界
     */
    public PixelProjection {
        if (frameId <= 0L) {
            throw new IllegalArgumentException("frameId must be > 0: " + frameId);
        }
        requireFinite(pixelX, "pixelX");
        requireFinite(pixelY, "pixelY");
        requireFinite(ndcZ, "ndcZ");
        if (ndcZ < -1.0D || ndcZ > 1.0D) {
            throw new IllegalArgumentException("ndcZ must be within [-1, 1]: " + ndcZ);
        }
    }

    private static void requireFinite(double value, String component) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "PixelProjection." + component + " must be finite but was " + value);
        }
    }
}
