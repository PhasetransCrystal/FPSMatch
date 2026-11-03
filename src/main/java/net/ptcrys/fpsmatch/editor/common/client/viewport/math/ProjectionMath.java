package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import net.ptcrys.fpsmatch.editor.common.client.viewport.FramebufferRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportFrame;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;

import java.util.Optional;

/**
 * R18.2a 同帧投影与反投影的唯一纯数学入口。
 *
 * <p>
 * <strong>固定单位与算法</strong>
 * <ul>
 * <li>frame 中<em>实际</em>的 {@code view} 作用于 {@code worldPoint - cameraPosition}（camera-relative），
 * 随后 {@code projection} 再作用于该 camera-relative 坐标；不重建“理想相机”、不重复平移相机；</li>
 * <li>存储为 column-major / column-vector；所有矩阵乘加只消费 {@link MatrixMath}，
 * <strong>无</strong> FMA、<strong>无</strong> {@code float} 中间值；</li>
 * <li>每次显式 subtract / divide / add / 差值的中间结果都必须在有限时才继续，
 * 否则在<em>该运算点</em>返回 {@link Optional#empty()}；</li>
 * <li>{@link MatrixMath} 的数值 {@link IllegalArgumentException} 在<em>该运算点</em>转换为 empty；
 * {@code null} 与原始参数错误仍然抛 {@link IllegalArgumentException}——
 * 绝不 catch 整个方法，以免吞掉程序缺陷。</li>
 * </ul>
 *
 * <p>
 * <strong>absolute framebuffer continuous pixel</strong>：{@code pixelX}/{@code pixelY} 相对帧缓冲左上原点、
 * 允许分数、<strong>不</strong>减 1、<strong>不</strong>加 0.5；结果是半开 {@code [x, right) x [y, bottom)} 成员判定，
 * 恰右/下边界为 empty、恰左/上可接受。本类<strong>不</strong>读 {@code ScaleTransform}、不重做 DPI/origin 换算
 * （上游已完成），也不新增帧 factory 或矩阵缓存 owner。
 */
public final class ProjectionMath {

    /** 近平面裁剪空间 z（column-vector、z∈[-1,1] 约定）。 */
    private static final double NEAR_CLIP_Z = -1.0D;
    /** 远平面裁剪空间 z。 */
    private static final double FAR_CLIP_Z = 1.0D;

    private ProjectionMath() {}

    /**
     * 把世界点投影到该捕获帧的 absolute framebuffer 连续像素坐标。
     *
     * <p>
     * 步骤固定：camera-relative → {@code view.transform(Vector4(relative, 1))} →
     * {@code projection.transform(viewResult)} → {@code clip.w > 0} → {@code ndc = clip.xyz / clip.w} →
     * 三个 NDC 分量全部有限且落在闭区间 {@code [-1, 1]}（不做 epsilon clamp）→
     * {@code x = rect.x + (ndcX + 1) * rect.width / 2}、{@code y = rect.y + (1 - ndcY) * rect.height / 2} →
     * 结果像素必须落在半开 {@code rect} 内。
     *
     * @param frame      捕获帧，非 null
     * @param worldPoint 世界点，非 null
     * @return 有效投影；视窗外、{@code clip.w <= 0}、NDC 越界或任一步非有限时为 empty
     * @throws IllegalArgumentException {@code frame} 或 {@code worldPoint} 为 {@code null}
     */
    public static Optional<PixelProjection> project(ViewportFrame frame, Position worldPoint) {
        requireFrame(frame);
        requirePoint(worldPoint);
        final Position camera = frame.cameraPosition();
        final double relativeX = worldPoint.x() - camera.x();
        if (!Double.isFinite(relativeX)) {
            return Optional.empty();
        }
        final double relativeY = worldPoint.y() - camera.y();
        if (!Double.isFinite(relativeY)) {
            return Optional.empty();
        }
        final double relativeZ = worldPoint.z() - camera.z();
        if (!Double.isFinite(relativeZ)) {
            return Optional.empty();
        }
        final Optional<Vector4> viewResult = transformOrEmpty(frame.view(), new Vector4(relativeX, relativeY, relativeZ, 1.0D));
        if (viewResult.isEmpty()) {
            return Optional.empty();
        }
        final Optional<Vector4> clipResult = transformOrEmpty(frame.projection(), viewResult.get());
        if (clipResult.isEmpty()) {
            return Optional.empty();
        }
        final Vector4 clip = clipResult.get();
        final double clipW = clip.w();
        if (!(clipW > 0.0D)) {
            return Optional.empty();
        }
        final double ndcX = clip.x() / clipW;
        final double ndcY = clip.y() / clipW;
        final double ndcZ = clip.z() / clipW;
        if (!isUsableNdc(ndcX) || !isUsableNdc(ndcY) || !isUsableNdc(ndcZ)) {
            return Optional.empty();
        }
        final FramebufferRect rect = frame.framebufferRect();
        final double pixelX = rect.x() + (ndcX + 1.0D) * rect.width() / 2.0D;
        final double pixelY = rect.y() + (1.0D - ndcY) * rect.height() / 2.0D;
        if (!Double.isFinite(pixelX) || !Double.isFinite(pixelY)) {
            return Optional.empty();
        }
        if (!rect.contains(pixelX, pixelY)) {
            return Optional.empty();
        }
        return Optional.of(new PixelProjection(frame.frameId(), pixelX, pixelY, ndcZ));
    }

    /**
     * 把该捕获帧的 absolute framebuffer 连续像素坐标反投影为世界空间近平面到远平面的有限线段。
     *
     * <p>
     * 步骤固定：像素必须落在半开 {@code frame.framebufferRect()} 内 →
     * {@code ndcX = 2 * (pixelX - rect.x) / rect.width - 1}、{@code ndcY = 1 - 2 * (pixelY - rect.y) / rect.height} →
     * near clip {@code (ndcX, ndcY, -1, 1)} 与 far clip {@code (ndcX, ndcY, 1, 1)} 各自
     * {@code inverseProjection.transform} → 除 {@code w}（{@code w == 0} 拒绝，允许负非零）→
     * {@code inverseView.transform(Vector4(cameraRelative, 1))} → 再除 {@code w} →
     * 各分量加 {@code cameraPosition} 得 worldNear/worldFar。
     *
     * <p>
     * 方向为 {@code far - near} 归一化后的单位向量，{@code maxDistance = hypot(hypot(dx, dy), dz)} 必须有限且 {@code > 0}；
     * 极大世界坐标导致 near 与 far 舍入到同一点时返回 empty，<strong>不</strong>伪造方向。
     *
     * @param frame  捕获帧，非 null
     * @param pixelX 帧缓冲连续像素 X（左上原点），必须有限
     * @param pixelY 帧缓冲连续像素 Y（左上原点），必须有限
     * @return 有效射线；像素在视窗外、{@code w == 0}、任一步非有限或退化线段时为 empty
     * @throws IllegalArgumentException {@code frame} 为 {@code null}，或 {@code pixelX}/{@code pixelY} 非有限
     */
    public static Optional<Ray3> ray(ViewportFrame frame, double pixelX, double pixelY) {
        requireFrame(frame);
        requireFinitePixel(pixelX, "pixelX");
        requireFinitePixel(pixelY, "pixelY");
        final FramebufferRect rect = frame.framebufferRect();
        if (!rect.contains(pixelX, pixelY)) {
            return Optional.empty();
        }
        final double ndcX = 2.0D * (pixelX - rect.x()) / rect.width() - 1.0D;
        final double ndcY = 1.0D - 2.0D * (pixelY - rect.y()) / rect.height();
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)) {
            return Optional.empty();
        }
        final Optional<Vector3> nearRelative = unprojectClip(frame, ndcX, ndcY, NEAR_CLIP_Z);
        if (nearRelative.isEmpty()) {
            return Optional.empty();
        }
        final Optional<Vector3> farRelative = unprojectClip(frame, ndcX, ndcY, FAR_CLIP_Z);
        if (farRelative.isEmpty()) {
            return Optional.empty();
        }
        final Optional<Position> worldNear = toWorld(frame, nearRelative.get());
        if (worldNear.isEmpty()) {
            return Optional.empty();
        }
        final Optional<Position> worldFar = toWorld(frame, farRelative.get());
        if (worldFar.isEmpty()) {
            return Optional.empty();
        }
        return segment(frame.frameId(), worldNear.get(), worldFar.get());
    }

    /** 逆投影 + 逆 view 两段，返回 camera-relative 世界分量（未加相机平移）。 */
    private static Optional<Vector3> unprojectClip(ViewportFrame frame, double ndcX, double ndcY, double clipZ) {
        final Optional<Vector4> inverseProjected = transformOrEmpty(frame.inverseProjection(), new Vector4(ndcX, ndcY, clipZ, 1.0D));
        if (inverseProjected.isEmpty()) {
            return Optional.empty();
        }
        final Optional<Vector3> relative = divideByW(inverseProjected.get());
        if (relative.isEmpty()) {
            return Optional.empty();
        }
        final Vector3 point = relative.get();
        final Optional<Vector4> inverseViewed = transformOrEmpty(
                frame.inverseView(), new Vector4(point.x(), point.y(), point.z(), 1.0D));
        if (inverseViewed.isEmpty()) {
            return Optional.empty();
        }
        return divideByW(inverseViewed.get());
    }

    /** 除以 {@code w}：{@code w == 0} 拒绝，负非零允许；三个商必须有限。 */
    private static Optional<Vector3> divideByW(Vector4 vector) {
        final double w = vector.w();
        if (w == 0.0D) {
            return Optional.empty();
        }
        final double x = vector.x() / w;
        final double y = vector.y() / w;
        final double z = vector.z() / w;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return Optional.empty();
        }
        return Optional.of(new Vector3(x, y, z));
    }

    /** camera-relative 分量逐项加相机位置，任一分量非有限即 empty。 */
    private static Optional<Position> toWorld(ViewportFrame frame, Vector3 relative) {
        final Position camera = frame.cameraPosition();
        final double worldX = relative.x() + camera.x();
        if (!Double.isFinite(worldX)) {
            return Optional.empty();
        }
        final double worldY = relative.y() + camera.y();
        if (!Double.isFinite(worldY)) {
            return Optional.empty();
        }
        final double worldZ = relative.z() + camera.z();
        if (!Double.isFinite(worldZ)) {
            return Optional.empty();
        }
        return Optional.of(new Position(worldX, worldY, worldZ));
    }

    /** 由世界近点/远点构造单位方向线段；退化或非有限即 empty，不伪造方向。 */
    private static Optional<Ray3> segment(long frameId, Position worldNear, Position worldFar) {
        final double deltaX = worldFar.x() - worldNear.x();
        final double deltaY = worldFar.y() - worldNear.y();
        final double deltaZ = worldFar.z() - worldNear.z();
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)) {
            return Optional.empty();
        }
        final double maxDistance = Math.hypot(Math.hypot(deltaX, deltaY), deltaZ);
        if (!Double.isFinite(maxDistance) || maxDistance <= 0.0D) {
            return Optional.empty();
        }
        final double directionX = deltaX / maxDistance;
        final double directionY = deltaY / maxDistance;
        final double directionZ = deltaZ / maxDistance;
        if (!Double.isFinite(directionX) || !Double.isFinite(directionY) || !Double.isFinite(directionZ)) {
            return Optional.empty();
        }
        final double norm = Math.hypot(Math.hypot(directionX, directionY), directionZ);
        if (!Double.isFinite(norm) || Math.abs(norm - 1.0D) > Ray3.UNIT_DIRECTION_TOLERANCE) {
            return Optional.empty();
        }
        return Optional.of(
                new Ray3(frameId, worldNear, new Vector3(directionX, directionY, directionZ), maxDistance));
    }

    private static void requireFinitePixel(double value, String component) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(component + " must be finite but was " + value);
        }
    }

    private static boolean isUsableNdc(double value) {
        return Double.isFinite(value) && value >= -1.0D && value <= 1.0D;
    }

    private static Optional<Vector4> transformOrEmpty(FrameMatrix matrix, Vector4 vector) {
        try {
            return Optional.of(MatrixMath.transform(matrix, vector));
        } catch (IllegalArgumentException numerical) {
            return Optional.empty();
        }
    }

    private static void requireFrame(ViewportFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
    }

    private static void requirePoint(Position worldPoint) {
        if (worldPoint == null) {
            throw new IllegalArgumentException("worldPoint must not be null");
        }
    }
}
