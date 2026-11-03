package net.ptcrys.fpsmatch.editor.common.client.viewport;

import net.ptcrys.fpsmatch.editor.common.client.viewport.math.FrameMatrix;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.WorldRef;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;

import java.util.OptionalLong;

/**
 * R18.1b 不可变视窗帧载荷：一次“已完成的渲染”所捕获的全部输入身份、代数与纯数学值。
 *
 * <p>
 * 本类型<strong>只</strong>承载载荷与失效判定，不实现渲染、不取相机、不绑定 FBO、不做矩阵求逆、
 * 不拥有 producer/generation 分配器，也不保存任何真实的 World/Camera/玩家/纹理句柄。相机与布局的
 * generation 由唯一 owner 单调分配，本 record 不自行分配也不修改它们（纯函数、可重入）。
 *
 * <p>
 * <strong>构造约束（逐条固定）</strong>
 * <ul>
 * <li>{@code frameId > 0}：帧 ID 为“每次完成的渲染”的单调 ID，未渲染出帧时为 0，因此不允许 &lt;= 0。</li>
 * <li>{@code targetGeneration}/{@code layoutGeneration}/{@code cameraGeneration} 均 {@code >= 0}。</li>
 * <li>全部引用非 null：{@code world}、{@code connectionId}、{@code serverTick}、{@code cameraPosition}、
 * {@code view}、{@code projection}、{@code inverseView}、{@code inverseProjection}、{@code framebufferRect}。</li>
 * <li>{@code serverTick} 存在时其值必须 {@code >= 0}；<strong>不知道 tick 时必须 empty，不得伪填 0</strong>——
 * empty 表示“来源未知”，与“tick 恰好为 0”是两种不同事实。</li>
 * <li>{@code framebufferRect} 必须非 null 且<strong>非空</strong>（{@code isEmpty() == false}）：
 * 空视窗不发布帧，空矩形不是可发布的载荷。</li>
 * </ul>
 *
 * <p>
 * {@link WorldRef}/{@link Position}/{@link FrameMatrix}/各引用类型本身已经不可变，本 record 直接持有它们，
 * 无需反复 clone。
 *
 * <p>
 * <strong>失效语义（{@link #matches}）</strong>：先校验当前 target/camera generation（负值抛
 * {@link IllegalArgumentException}，即参数非法而不是“不匹配”）；随后当前 world/connection/layout 任一为 null
 * 直接返回 {@code false}；只有全部条件同时成立才为 {@code true}：world 相等、connection 相等、
 * targetGeneration 相同、cameraGeneration 相同、{@code layoutGeneration == currentLayout.layoutGeneration()}
 * 且 {@code framebufferRect.equals(currentLayout.framebufferRect())}。
 *
 * <p>
 * {@link #matches} 刻意<strong>不</strong>要求 {@code frameId} 等于最新帧 ID：相机与布局完全未变时，上一帧的
 * 数学仍然有效，输入消费必须使用捕获的同一个 {@code ViewportFrame}，不能暗换矩阵。
 * {@code serverTick} 只用于显示来源，<strong>不</strong>作为场景视图版本参与匹配。
 *
 * <p>
 * 本卡只定义数据与失效契约；owner 是否真实按契约递增代数、矩阵求逆是否正确、真实 marker 误差、输入消费时序与
 * FBO 均<strong>未</strong>由此类型证明（属后续 R19/R20 验收）。
 *
 * @param frameId           每次完成的渲染的单调帧 ID，必须 &gt; 0
 * @param targetGeneration  目标 epoch 代数（world/渲染资源重建/相机抢占等），必须 &gt;= 0
 * @param layoutGeneration  UI 布局代数，必须 &gt;= 0
 * @param cameraGeneration  相机代数（位置、真实 view 或 projection 任一变化即递增），必须 &gt;= 0
 * @param world             当代世界引用，非 null
 * @param connectionId      当代连接身份，非 null
 * @param serverTick        显示用的服务端 tick 来源；未知时为 {@link OptionalLong#empty()}，存在时必须 &gt;= 0
 * @param cameraPosition    捕获时的相机位置，非 null
 * @param view              本次实际使用的 view 矩阵载荷，非 null
 * @param projection        本次实际使用的 projection 矩阵载荷，非 null
 * @param inverseView       view 的逆矩阵载荷，非 null
 * @param inverseProjection projection 的逆矩阵载荷，非 null
 * @param framebufferRect   帧缓冲像素矩形，非 null 且非空
 */
public record ViewportFrame(long frameId, long targetGeneration,
                            long layoutGeneration, long cameraGeneration,
                            WorldRef world, Ids.ConnectionId connectionId, OptionalLong serverTick,
                            Position cameraPosition, FrameMatrix view, FrameMatrix projection,
                            FrameMatrix inverseView, FrameMatrix inverseProjection,
                            FramebufferRect framebufferRect) {

    public ViewportFrame {
        if (frameId <= 0L) {
            throw new IllegalArgumentException("frameId must be > 0: " + frameId);
        }
        if (targetGeneration < 0L) {
            throw new IllegalArgumentException("targetGeneration must be >= 0: " + targetGeneration);
        }
        if (layoutGeneration < 0L) {
            throw new IllegalArgumentException("layoutGeneration must be >= 0: " + layoutGeneration);
        }
        if (cameraGeneration < 0L) {
            throw new IllegalArgumentException("cameraGeneration must be >= 0: " + cameraGeneration);
        }
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionId must not be null");
        }
        if (serverTick == null) {
            throw new IllegalArgumentException("serverTick must not be null; use OptionalLong.empty() when unknown");
        }
        if (cameraPosition == null) {
            throw new IllegalArgumentException("cameraPosition must not be null");
        }
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (projection == null) {
            throw new IllegalArgumentException("projection must not be null");
        }
        if (inverseView == null) {
            throw new IllegalArgumentException("inverseView must not be null");
        }
        if (inverseProjection == null) {
            throw new IllegalArgumentException("inverseProjection must not be null");
        }
        if (framebufferRect == null) {
            throw new IllegalArgumentException("framebufferRect must not be null");
        }
        // tick 未知必须是 empty，因此只有“存在且为负”才是非法；不得用 0 伪造未知。
        if (serverTick.isPresent() && serverTick.getAsLong() < 0L) {
            throw new IllegalArgumentException("serverTick must be >= 0 when present: " + serverTick.getAsLong());
        }
        if (framebufferRect.isEmpty()) {
            throw new IllegalArgumentException(
                    "framebufferRect must not be empty; an empty viewport does not publish a frame: " + framebufferRect);
        }
    }

    /**
     * 本帧是否仍对给定当前上下文有效（失效判定，逐条对应卡面）。
     *
     * <p>
     * <strong>参数非法优先于不匹配</strong>：{@code currentTargetGeneration} 或 {@code currentCameraGeneration}
     * 为负时立即抛 {@link IllegalArgumentException}——即使同时存在 null 上下文，也先拒绝坏代数，不会静默返回
     * {@code false} 而把调用方的 bug 隐藏成“不匹配”。
     *
     * <p>
     * 随后 {@code currentWorld}、{@code currentConnection}、{@code currentLayout} 任一为 null 返回 {@code false}
     * （当前上下文未知，不能据以确认有效）。
     *
     * <p>
     * 全部成立才返回 {@code true}：world、connection 相等，targetGeneration 与 cameraGeneration 相同，
     * {@code layoutGeneration == currentLayout.layoutGeneration()}，且
     * {@code framebufferRect.equals(currentLayout.framebufferRect())}。相同 layout generation 但实际像素矩形不同
     * 仍然返回 {@code false}。
     *
     * <p>
     * 不比较 {@code frameId}，也不比较 {@code serverTick}。
     *
     * @param currentWorld            当前世界引用，可为 null
     * @param currentConnection       当前连接身份，可为 null
     * @param currentTargetGeneration 当前目标 epoch 代数，必须 &gt;= 0
     * @param currentCameraGeneration 当前相机代数，必须 &gt;= 0
     * @param currentLayout           当前布局（含其 layoutGeneration 与像素矩形），可为 null
     * @return 本帧在给定上下文下仍有效时为 {@code true}
     * @throws IllegalArgumentException 任一 current generation 为负
     */
    public boolean matches(WorldRef currentWorld, Ids.ConnectionId currentConnection,
                           long currentTargetGeneration, long currentCameraGeneration,
                           ViewportLayout currentLayout) {
        // 负代数属参数非法，必须优先于 null 上下文判定。
        if (currentTargetGeneration < 0L) {
            throw new IllegalArgumentException("currentTargetGeneration must be >= 0: " + currentTargetGeneration);
        }
        if (currentCameraGeneration < 0L) {
            throw new IllegalArgumentException("currentCameraGeneration must be >= 0: " + currentCameraGeneration);
        }
        if (currentWorld == null || currentConnection == null || currentLayout == null) {
            return false;
        }
        if (!world.equals(currentWorld)) {
            return false;
        }
        if (!connectionId.equals(currentConnection)) {
            return false;
        }
        if (targetGeneration != currentTargetGeneration) {
            return false;
        }
        if (cameraGeneration != currentCameraGeneration) {
            return false;
        }
        if (layoutGeneration != currentLayout.layoutGeneration()) {
            return false;
        }
        return framebufferRect.equals(currentLayout.framebufferRect());
    }
}
