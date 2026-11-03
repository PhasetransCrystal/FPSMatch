package net.ptcrys.fpsmatch.editor.common.client.viewport.render;

import net.ptcrys.fpsmatch.editor.common.client.viewport.FramebufferRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;

import net.minecraft.client.Minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.util.Optional;

/**
 * R19.1a / R19.1a2：视窗专用离屏 GPU 目标的<strong>唯一</strong>所有者。
 *
 * <p>
 * 本类型独占一个 {@link RenderTarget} 子类实例（一个 FBO + 一个颜色纹理 + 一个深度纹理），并提供
 * 生成代数、幂等关闭、以及在尺寸变化时“前检—失效—释放—重建”的唯一算法。本类型<strong>不</strong>渲染世界、
 * <strong>不</strong>提交 draw call、<strong>不</strong>创建 program/VAO/buffer、<strong>不</strong>触碰主
 * framebuffer（{@code Minecraft.mainRenderTarget} 永不 resize）、<strong>不</strong>持有静态缓存、
 * <strong>不</strong>在构造器里分配任何 GL 资源，也<strong>不</strong>排队 {@code recordRenderCall} 来伪造
 * 完成——所有 GL 调用都要求真实的 {@link RenderSystem#isOnRenderThread()}。
 *
 * <p>
 * <strong>尺寸与 layout 的关系</strong>：资源尺寸恒为 {@code layout.framebufferRect()} 的
 * {@code (width, height)}；原点只存在于 {@link Snapshot#rect()} 里。本类型只读消费
 * {@link ViewportLayout#framebufferRect()}，<strong>不</strong>读取 View/GUI 边界、<strong>不</strong>重算
 * floor/ceil、<strong>不</strong>自行分配 {@code layoutGeneration}。
 *
 * <p>
 * <strong>算法</strong>（{@link #synchronize(ViewportLayout)}）：
 * <ol>
 * <li>线程检查 → closed 检查 → 参数非空检查 → {@link #pendingCleanup} 非空检查 → 取
 * {@code rect = layout.framebufferRect()}。</li>
 * <li>{@code rect} 为空：释放（若有）并返回 {@link Optional#empty()}；释放前先做“使用中”前检。</li>
 * <li>尺寸超过 {@link RenderSystem#maxSupportedTextureSize()}：抛 {@link IllegalArgumentException}。</li>
 * <li>同尺寸且句柄有效：<strong>只</strong>更新快照（代数、layoutGeneration、rect），不重建、不 resize、
 * 不遍历任何 texture unit——稳态零资源操作。</li>
 * <li>否则进入 {@link #rebuild(FramebufferRect, long)}：使用中前检 → PBO 前检 → 捕获 GL 状态并钉到
 * unit0 → 失效旧快照 → <strong>受检</strong>递增资源代数 → 释放旧目标 → 在 pinned unit0 与受控状态下
 * 创建并认证新目标 → 恢复状态 → 发布新快照。</li>
 * </ol>
 *
 * <p>
 * <strong>受检代数</strong>（R19.1a2 修法 2）：三条真正改变资源状态的路径（首次/重建、空 rect 释放、
 * close 释放）都在前检、状态捕获与 unit0 准备完成之后才失效快照与 target，随后统一走
 * {@link #advanceTargetGenerationChecked()} 递增。到达 {@code Long.MAX_VALUE} 时<strong>抛出原始
 * {@link IllegalStateException}</strong>（保持在上界、绝不回绕到 0、绝不饱和成“成功”），并在同一 unit0
 * scope 内尽力释放旧资源，清理失败以 {@code addSuppressed} 挂到该原异常上，恢复状态后原异常重抛、不发布
 * 任何 target/snapshot。空 rect / 幂等 close 在没有任何资源时仍然短路，既不递增代数也不做全单元扫描。
 *
 * <p>
 * <strong>前检</strong>：分配或释放前，owner FBO 不得是当前 read/draw 绑定，颜色/深度附件纹理不得仍被
 * 任何 texture unit 绑定；该检查只在真正销毁/重建时执行（稳态不执行），只切 active unit、只做只读
 * {@code GL_TEXTURE_BINDING_2D} 查询，绝不 {@code _bindTexture}。同时 {@code GL_PIXEL_UNPACK_BUFFER_BINDING}
 * 必须为 0，否则立即拒绝；本卡不修改 PBO 绑定。
 *
 * <p>
 * <strong>失败与待清理所有权</strong>（R19.1a2 修法 3/4）：候选创建/resize 失败时，先在原 unit0 scope
 * 内清理候选的部分句柄；<strong>只有成功 {@code destroyBuffers()}</strong> 才算清理完成。准备失败、
 * {@code destroyBuffers()} 抛错、或恢复失败后的收尾清理失败，都必须把尚未成功清理的资源转交给唯一的
 * {@link #pendingCleanup} 引用：此时 {@link #current()} 保持空、不发布假纹理、不保留旧尺寸的“成功”、禁止
 * 同尺寸复用与任何新分配，只能由 {@link #close()} 在新的前检/状态 scope 内重试销毁，成功后才丢弃引用。该
 * 重试属于同一个资源转换，不再递增代数。只捕获 {@code RuntimeException|Error} 以完成清理，随后原样重抛；
 * 清理异常一律 {@code addSuppressed}，绝不吞掉 {@code OutOfMemoryError}，也绝不把 {@code Error} 转成成功。
 *
 * <p>
 * <strong>状态恢复</strong>：{@link GpuAllocationState#restore()} 逐项尝试且只有全部成功才标记已恢复；
 * 失败时保持未恢复。操作本身失败时恢复异常挂 suppressed；<strong>操作成功但首次恢复失败时</strong>先由
 * {@link GpuAllocationState#pinUnit0ForCleanup()} 把 active 固定回 unit0 并同步此刻实际绑定进缓存，再销毁
 * 尚未发布的候选，然后对同一 capture <strong>有界重试一次</strong>恢复，最后抛出第一次恢复异常（后续错误
 * suppressed），既不循环也不发布任何成功。
 *
 * <p>
 * <strong>关闭</strong>：{@link #close()} 幂等；有资源时先做使用中前检，通过后才置 closed、失效快照、
 * 受检递增代数并释放；前检拒绝时保持 not-closed 且旧快照不变，可重试。没有 live 资源也没有待清理引用时
 * 短路返回，不做全单元扫描。已经 closed 但仍有 {@link #pendingCleanup} 时仍然先处理该待清理资源，成功后
 * 丢弃引用、失败则保留引用以便再次 {@code close()} 重试。关闭后 {@link #current()} 为空、
 * {@link #renderTarget(long)} 拒绝、{@link #synchronize(ViewportLayout)} 抛 {@link IllegalStateException}。
 *
 * <p>
 * <strong>非声明</strong>：本类型不保证恢复本阶段之外的引擎状态（program/shader、VAO、buffer、
 * blend、cull、stencil、matrix、PBO 绑定），这些仍由各自卡片负责；{@link GpuAllocationState} 只覆盖本卡
 * 实际触碰的状态集合。
 */
final class GpuViewportTarget implements AutoCloseable {

    private OwnedTarget target;
    private Snapshot snapshot;
    /**
     * 唯一待清理资源引用：准备失败、{@code destroyBuffers()} 抛错或收尾清理失败时持有尚未成功销毁的资源。
     * 非空期间禁止新分配与同尺寸复用，只能由 {@link #close()} 重试；只有成功销毁才置回 {@code null}。
     */
    private OwnedTarget pendingCleanup;
    /** 资源代数：初值 0，只增不减、永不重置为 0；每次 create/destroy/rebuild 之前受检递增。 */
    private long targetGeneration = 0L;
    private boolean closed;

    GpuViewportTarget() {
        requireRenderThread();
    }

    /**
     * 把当前 layout 同步到 GPU 目标，返回新发布的快照；空 rect 返回空。
     *
     * @param layout 非 null 的布局；只读取其 {@code framebufferRect()} 与 {@code layoutGeneration}
     * @return 已发布快照；空 rect 时为 {@link Optional#empty()}
     */
    Optional<Snapshot> synchronize(ViewportLayout layout) {
        requireRenderThread();
        if (this.closed) {
            throw new IllegalStateException("GpuViewportTarget is closed");
        }
        if (layout == null) {
            throw new IllegalArgumentException("layout must not be null");
        }
        if (this.pendingCleanup != null) {
            throw new IllegalStateException(
                    "unreleased target cleanup is pending; close() must succeed before any further allocation");
        }

        final FramebufferRect rect = layout.framebufferRect();
        if (rect.isEmpty()) {
            releaseOwnedTarget();
            return Optional.empty();
        }

        final int maxTextureSize = RenderSystem.maxSupportedTextureSize();
        if (rect.width() > maxTextureSize || rect.height() > maxTextureSize) {
            throw new IllegalArgumentException("framebuffer rect " + rect.width() + "x" + rect.height() + " exceeds maxSupportedTextureSize " + maxTextureSize);
        }

        final OwnedTarget reusable = this.target;
        if (isReusable(reusable, rect)) {
            final Snapshot published = new Snapshot(this.targetGeneration, layout.layoutGeneration(), rect,
                    reusable.frameBufferId, reusable.getColorTextureId(), reusable.getDepthTextureId());
            this.snapshot = published;
            return Optional.of(published);
        }

        return Optional.of(rebuild(rect, layout.layoutGeneration()));
    }

    /**
     * 当前已发布快照；关闭后、从未分配成功时或仍有待清理资源时为空。不返回任何 GL 状态。
     */
    Optional<Snapshot> current() {
        requireRenderThread();
        return Optional.ofNullable(this.snapshot);
    }

    /**
     * 返回底层 {@link RenderTarget}，仅在未关闭、已发布快照、且 {@code expectedTargetGeneration} 等于当前
     * 资源代数时可用；任何一个不满足都抛 {@link IllegalStateException}。
     */
    RenderTarget renderTarget(long expectedTargetGeneration) {
        requireRenderThread();
        if (this.closed) {
            throw new IllegalStateException("GpuViewportTarget is closed");
        }
        final OwnedTarget current = this.target;
        if (current == null || this.snapshot == null) {
            throw new IllegalStateException("no live target is published");
        }
        if (this.targetGeneration != expectedTargetGeneration) {
            throw new IllegalStateException("stale target generation: expected " + expectedTargetGeneration + " but current is " + this.targetGeneration);
        }
        return current;
    }

    /**
     * 幂等关闭：前检通过后失效快照、受检递增代数并释放全部 GPU 资源。既没有 live 资源也没有待清理引用时短路
     * 返回，不做全单元扫描、不递增代数。已经 closed 但仍有 {@link #pendingCleanup} 时仍然先处理该待清理资源。
     *
     * <p>
     * 本卡把一次转换拆成“准备阶段”与“资源转换阶段”：{@code capture} + {@link
     * GpuAllocationState#beginUnit0Work()} 全部成功之前都属于准备阶段，此时任何失败都原样重抛，不改
     * {@code closed}、不动 owner/snapshot、不递增代数，也<strong>不</strong>释放旧资源；准备阶段整体成功后
     * （{@code invalidated = true}）才进入 a2 语义的资源转换阶段（原异常优先 + 同一 unit0 scope 内尽力清理 +
     * 候选转交 pending）。{@code closed = true} 只在准备阶段完整成功之后设置。仍有 {@link #pendingCleanup} 时
     * 先完成该待清理资源的同一代收尾（不递增代数，仅成功销毁后才丢引用），同样要等准备阶段成功后才置
     * {@code closed}。
     */
    @Override
    public void close() {
        requireRenderThread();

        final OwnedTarget pending = this.pendingCleanup;
        if (pending != null) {
            requireNotInUse(pending);
            releasePendingCleanup(pending);
            return;
        }
        if (this.closed) {
            return;
        }
        final OwnedTarget owned = this.target;
        if (owned == null) {
            // 无资源可释放：本分支不触碰任何 GL 状态，也不存在任何可能失败的准备步骤，因此按 a2 语义直接清快照
            // 并置 closed。只有真正要释放资源的分支才需要先完成准备阶段再改 closed。
            this.snapshot = null;
            this.closed = true;
            return;
        }
        requireNotInUse(owned);

        final GpuAllocationState state = GpuAllocationState.capture();
        Throwable failure = null;
        boolean invalidated = false;
        boolean destroyAttempted = false;
        try {
            state.beginUnit0Work();
            this.closed = true;
            this.snapshot = null;
            this.target = null;
            invalidated = true;
            advanceTargetGenerationChecked();
            destroyAttempted = true;
            owned.destroyBuffers();
        } catch (RuntimeException | Error e) {
            failure = e;
            if (!invalidated) {
                // 准备阶段失败：没有进入资源转换，owner/closed/代数与 pending 都不许变，也不许释放旧资源。
                throw e;
            }
            if (destroyAttempted) {
                retainPendingCleanup(owned, e);
            } else {
                tryRelease(owned, e);
            }
            throw e;
        } finally {
            finishRestore(state, null, failure);
        }
    }

    /**
     * 尺寸变化／首次分配的唯一重建算法。调用前已完成 closed、rect 与尺寸上限检查；本方法自己完成使用中前检
     * 与 PBO 前检，二者都<strong>先于</strong>快照失效与代数递增。
     *
     * <p>
     * 准备阶段（{@code capture} + {@code beginUnit0Work()}）失败时原异常重抛：owner/snapshot/代数与
     * {@link #pendingCleanup} 都不变，previous 也<strong>不</strong>被销毁，{@code finally} 仍恢复 GL 状态。
     * 只有准备阶段完整成功后才失效 owner 并进入 a2 语义的资源转换阶段（MAX 时旧资源仍被安全尝试清理）。
     */
    private Snapshot rebuild(FramebufferRect rect, long layoutGeneration) {
        final OwnedTarget previous = this.target;
        requireNotInUse(previous);
        requireNoPixelUnpackBuffer();

        final GpuAllocationState state = GpuAllocationState.capture();
        OwnedTarget candidate = null;
        Snapshot pending = null;
        Throwable failure = null;
        boolean invalidated = false;
        boolean destroyAttempted = false;
        try {
            state.beginUnit0Work();
            this.snapshot = null;
            this.target = null;
            invalidated = true;
            advanceTargetGenerationChecked();
            destroyAttempted = true;
            if (previous != null) {
                try {
                    previous.destroyBuffers();
                } catch (RuntimeException | Error destroyFailure) {
                    retainPendingCleanup(previous, destroyFailure);
                    throw destroyFailure;
                }
            }

            candidate = new OwnedTarget();
            candidate.resize(rect.width(), rect.height(), Minecraft.ON_OSX);
            requireUsable(candidate, rect.width(), rect.height());
            pending = new Snapshot(this.targetGeneration, layoutGeneration, rect, candidate.frameBufferId,
                    candidate.getColorTextureId(), candidate.getDepthTextureId());
        } catch (RuntimeException | Error e) {
            failure = e;
            if (!invalidated) {
                // 准备阶段失败：不进入资源转换，previous 仍然存活且 owner 未被改动。
                throw e;
            }
            final OwnedTarget failedCandidate = candidate;
            candidate = null;
            if (failedCandidate != null) {
                tryRelease(failedCandidate, e);
            } else if (!destroyAttempted) {
                // 只在“代数受检失败、previous 尚未被销毁”这一条路径上释放 previous；销毁已尝试过时其所有权
                // 已归 pendingCleanup，绝不二次销毁。
                tryRelease(previous, e);
            }
            throw e;
        } finally {
            finishRestore(state, candidate, failure);
        }

        this.target = candidate;
        this.snapshot = pending;
        return pending;
    }

    /**
     * 空 rect：释放已有资源并清空快照。无资源时只清空快照，不做任何扫描、不递增代数。有资源时先做使用中前检，
     * 捕获状态并钉到 unit0 之后才失效快照与受检递增代数；代数到上界时原异常优先，在同一 scope 内尽力释放旧资源
     * （清理失败 suppressed），恢复状态后原异常重抛，不发布任何成功。
     *
     * <p>
     * 准备阶段（{@code capture} + {@code beginUnit0Work()}）失败时原异常重抛：owner/snapshot/代数与
     * {@link #pendingCleanup} 都不变，资源也<strong>不</strong>被销毁，{@code finally} 仍恢复 GL 状态。
     */
    private void releaseOwnedTarget() {
        final OwnedTarget owned = this.target;
        if (owned == null) {
            this.snapshot = null;
            return;
        }
        requireNotInUse(owned);

        final GpuAllocationState state = GpuAllocationState.capture();
        Throwable failure = null;
        boolean invalidated = false;
        boolean destroyAttempted = false;
        try {
            state.beginUnit0Work();
            this.snapshot = null;
            this.target = null;
            invalidated = true;
            advanceTargetGenerationChecked();
            destroyAttempted = true;
            owned.destroyBuffers();
        } catch (RuntimeException | Error e) {
            failure = e;
            if (!invalidated) {
                // 准备阶段失败：不进入资源转换，owner 与资源保持不变。
                throw e;
            }
            if (destroyAttempted) {
                retainPendingCleanup(owned, e);
            } else {
                tryRelease(owned, e);
            }
            throw e;
        } finally {
            finishRestore(state, null, failure);
        }
    }

    /**
     * 完成先前失败的同一资源转换：使用中前检通过后，在新的 capture/unit0 scope 内销毁待清理资源。只有成功
     * {@code destroyBuffers()} 才清除 {@link #pendingCleanup} 引用（恢复失败也不例外，资源已经销毁），失败则
     * 保留引用以便再次 {@code close()} 重试。本路径不递增代数——它与失败的那次转换同属一代——最后仍尝试恢复
     * 状态。
     *
     * <p>
     * {@code closed} 与快照只在准备阶段（{@code capture} + {@code beginUnit0Work()}）完整成功后才设置／
     * 清空：准备阶段失败即原异常重抛，{@code closed}/owner/代数/pending 引用全部保持原样。
     */
    private void releasePendingCleanup(OwnedTarget pending) {
        final GpuAllocationState state = GpuAllocationState.capture();
        Throwable failure = null;
        boolean destroyed = false;
        try {
            state.beginUnit0Work();
            this.closed = true;
            this.snapshot = null;
            pending.destroyBuffers();
            destroyed = true;
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            if (destroyed && this.pendingCleanup == pending) {
                this.pendingCleanup = null;
            }
            finishRestore(state, null, failure);
        }
    }

    /**
     * 统一收尾恢复：调用一次 {@link GpuAllocationState#restore()}。有原始异常时把恢复异常挂 suppressed；没有
     * 原始异常（操作本身成功）时先清理尚未发布的候选，再对同一 capture 有界重试一次恢复，最后抛出第一次恢复
     * 异常（后续错误 suppressed）。由此保证既不发布假成功，也不把 {@code Error} 降级。调用方必须已经退出
     * try/catch（不能在挂着异常的 finally 里调用本方法）。
     */
    private void finishRestore(GpuAllocationState state, OwnedTarget candidate, Throwable failure) {
        final Throwable restoreFailure = attemptRestore(state);
        if (restoreFailure == null) {
            return;
        }
        if (failure != null) {
            appendSuppressed(failure, restoreFailure);
            return;
        }
        final Throwable cleanupFailure = cleanupCandidate(state, candidate);
        appendSuppressed(restoreFailure, cleanupFailure);
        final Throwable retryFailure = attemptRestore(state);
        appendSuppressed(restoreFailure, retryFailure);
        throwUnchecked(restoreFailure);
    }

    /**
     * 恢复失败后的候选收尾（R19.1a2 修法 4）：绝不直接在未知 active unit 上销毁，先把 active 固定回 unit0 并把
     * 此刻实际绑定同步进引擎缓存，再销毁候选。准备失败或销毁失败都把候选转交 {@link #pendingCleanup}。
     *
     * @return 失败原因，或 {@code null} 表示候选已清理（或本来就没有候选）
     */
    private Throwable cleanupCandidate(GpuAllocationState state, OwnedTarget candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            state.pinUnit0ForCleanup();
        } catch (RuntimeException | Error preparationFailure) {
            retainPendingCleanup(candidate, preparationFailure);
            return preparationFailure;
        }
        try {
            candidate.destroyBuffers();
            return null;
        } catch (RuntimeException | Error cleanupFailure) {
            retainPendingCleanup(candidate, cleanupFailure);
            return cleanupFailure;
        }
    }

    /**
     * 在<strong>已经钉住 unit0 的当前 scope</strong> 内尝试释放资源：成功即清理完成；失败绝不覆盖原始异常，
     * 只以 suppressed 挂到 {@code sink}，并把资源转交 {@link #pendingCleanup} 等待 {@code close()} 重试。
     */
    private void tryRelease(OwnedTarget resource, Throwable sink) {
        if (resource == null) {
            return;
        }
        try {
            resource.destroyBuffers();
        } catch (RuntimeException | Error cleanupFailure) {
            appendSuppressed(sink, cleanupFailure);
            retainPendingCleanup(resource, sink);
        }
    }

    /**
     * 把尚未成功清理的资源转交给唯一 {@link #pendingCleanup} 引用。引用已非空属于不可达路径（每次失败的转换
     * 最多留下一套资源，且持有期间拒绝任何新分配）；此时不覆盖既有引用，只把技术异常挂到原始异常上。
     */
    private void retainPendingCleanup(OwnedTarget resource, Throwable sink) {
        if (this.pendingCleanup == null) {
            this.pendingCleanup = resource;
            return;
        }
        if (this.pendingCleanup != resource) {
            appendSuppressed(sink,
                    new IllegalStateException("pending cleanup already holds an unreleased target"));
        }
    }

    /**
     * 唯一允许调用 {@link Throwable#addSuppressed(Throwable)} 的地方：{@code primary} 或 {@code next} 为
     * {@code null}、或两者是<strong>同一实例</strong>时直接忽略。绝不替换主异常、绝不包装、绝不因为
     * {@code addSuppressed} 自身的 {@link IllegalArgumentException} 去改变原始错误，也绝不把主异常自我抑制。
     * 主异常始终保持它作为“第一个真实失败”的身份（{@code RuntimeException} 或 {@code Error}）。
     */
    private static void appendSuppressed(Throwable primary, Throwable next) {
        if (primary == null || next == null || next == primary) {
            return;
        }
        primary.addSuppressed(next);
    }

    /** 调一次 {@code restore()} 并把失败作为返回值，便于区分“挂 suppressed”与“必须抛出”。 */
    private static Throwable attemptRestore(GpuAllocationState state) {
        try {
            state.restore();
            return null;
        } catch (RuntimeException | Error restoreFailure) {
            return restoreFailure;
        }
    }

    /** 只把 {@code RuntimeException|Error} 原样重抛：不包装、不降级、不转成“成功”。 */
    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    /** 同尺寸复用判据：三个句柄都有效、useDepth 为真、四个尺寸字段都等于目标 rect。 */
    private static boolean isReusable(OwnedTarget owned, FramebufferRect rect) {
        return owned != null && owned.frameBufferId > 0 && owned.getColorTextureId() > 0 && owned.getDepthTextureId() > 0 && owned.useDepth && owned.width == rect.width() && owned.height == rect.height() && owned.viewWidth == rect.width() && owned.viewHeight == rect.height();
    }

    /**
     * 发布前认证：四个尺寸字段必须都等于目标 rect，{@code useDepth} 必须为真，三个句柄必须都 &gt; 0。
     * FBO 完整性本身由 {@code RenderTarget.createBuffers} 内部在候选 FBO 仍绑定时执行的 {@code checkStatus()}
     * 认证（失败即抛），本方法不重复在 FBO 0 上调用 {@code checkStatus} 来伪造认证。
     */
    private static void requireUsable(OwnedTarget candidate, int width, int height) {
        if (candidate.useDepth && candidate.frameBufferId > 0 && candidate.getColorTextureId() > 0 && candidate.getDepthTextureId() > 0 && candidate.width == width && candidate.height == height && candidate.viewWidth == width && candidate.viewHeight == height) {
            return;
        }
        throw new IllegalStateException("allocated target is not usable: framebuffer=" + candidate.frameBufferId + " color=" + candidate.getColorTextureId() + " depth=" + candidate.getDepthTextureId() + " size=" + candidate.width + "x" + candidate.height + " view=" + candidate.viewWidth + "x" + candidate.viewHeight + " useDepth=" + candidate.useDepth + " expected=" + width + "x" + height);
    }

    /**
     * 使用中前检：owner FBO 不得是当前 read/draw 绑定，颜色/深度附件纹理不得仍被任何 texture unit 绑定。
     * 只在真正销毁/重建/关闭时调用。扫描上限取 {@code GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS}；全程只切 active
     * unit（{@code _activeTexture}，两次互补独立尝试）并用只读 {@code GL_TEXTURE_BINDING_2D} 查询，
     * <strong>绝不</strong>调用 {@code _bindTexture}，因此在 active unit {@code >= 12}
     * （{@code GlStateManager.TEXTURE_COUNT}）时也安全——{@code _activeTexture} 只保存索引并转发
     * {@code GL13.glActiveTexture}，不索引引擎的 {@code TEXTURES} 数组。
     *
     * <p>
     * 扫描过程中的任何 {@code RuntimeException}/{@code Error}（仍然绑定、查询失败或切 unit 失败）都被原样
     * 捕获为扫描原始错误；{@code previousActive} 的恢复失败只能作为<strong>非自我抑制</strong>的后续错误追加，
     * 绝不覆盖扫描原始错误；只有在没有扫描错误时才把恢复错误作为主异常抛出。
     */
    private static void requireNotInUse(OwnedTarget owned) {
        if (owned == null) {
            return;
        }
        final int frameBufferId = owned.frameBufferId;
        if (frameBufferId > 0) {
            final int readFramebuffer = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            final int drawFramebuffer = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            if (readFramebuffer == frameBufferId || drawFramebuffer == frameBufferId) {
                throw new IllegalStateException("target framebuffer " + frameBufferId + " is still bound (read=" + readFramebuffer + ", draw=" + drawFramebuffer + ")");
            }
        }

        final int colorTextureId = owned.getColorTextureId();
        final int depthTextureId = owned.getDepthTextureId();
        final int maxUnits = GlStateManager._getInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        if (maxUnits < 1) {
            throw new IllegalStateException("GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS is " + maxUnits);
        }
        final int previousActive = GlStateManager._getInteger(GL13.GL_ACTIVE_TEXTURE);
        Throwable scanFailure = null;
        try {
            for (int unit = 0; unit < maxUnits; unit++) {
                forceActiveTexture(GL13.GL_TEXTURE0 + unit);
                final int bound = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
                if ((colorTextureId > 0 && bound == colorTextureId) || (depthTextureId > 0 && bound == depthTextureId)) {
                    throw new IllegalStateException("target attachment texture " + bound + " is still bound on texture unit " + unit);
                }
            }
        } catch (RuntimeException | Error e) {
            // 扫描原始错误：保持它作为主异常，稍后在独立尝试恢复之后再原样重抛。
            scanFailure = e;
        }

        Throwable restoreFailure = null;
        try {
            forceActiveTexture(previousActive);
        } catch (RuntimeException | Error e) {
            restoreFailure = e;
        }
        if (scanFailure != null) {
            appendSuppressed(scanFailure, restoreFailure);
            throwUnchecked(scanFailure);
        }
        if (restoreFailure != null) {
            throwUnchecked(restoreFailure);
        }
    }

    /**
     * 分配前拒收非零 {@code GL_PIXEL_UNPACK_BUFFER_BINDING}：引擎 {@code _texImage2D} 会直接引用该绑定，
     * 非零时分配语义不确定。本卡只读取、不修改该绑定。
     */
    private static void requireNoPixelUnpackBuffer() {
        final int bound = GlStateManager._getInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (bound != 0) {
            throw new IllegalStateException(
                    "GL_PIXEL_UNPACK_BUFFER_BINDING must be 0 before allocation, but is " + bound);
        }
    }

    /**
     * 受检递增资源代数：只增不减、永不重置为 0。已在上界时抛出与 R19.1a 相同的原始
     * {@link IllegalStateException} 并<strong>保持</strong>上界，不做饱和式“成功”。
     */
    private void advanceTargetGenerationChecked() {
        if (this.targetGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("target generation exhausted: " + Long.MAX_VALUE);
        }
        this.targetGeneration = this.targetGeneration + 1L;
    }

    /**
     * 只切 active unit，绝不触碰任何纹理绑定（不调用 {@code _bindTexture}，因此任意合法单元号都安全，包括
     * {@code >= GlStateManager.TEXTURE_COUNT} 的外部 active 值）：先切到互补单元再切到目标，两次互补赋值使
     * 驱动与引擎缓存都收敛到 {@code unit}。
     *
     * <p>
     * 两次 {@code _activeTexture} <strong>各自独立尝试</strong>：第一次抛错也仍然执行第二次；第一个错误作为
     * 主异常，后续错误只以 suppressed 追加（非自我抑制），最后重抛主异常。
     */
    private static void forceActiveTexture(int unit) {
        final int alternate = unit == GL13.GL_TEXTURE0 ? GL13.GL_TEXTURE1 : GL13.GL_TEXTURE0;
        Throwable failure = null;
        try {
            GlStateManager._activeTexture(alternate);
        } catch (RuntimeException | Error alternateFailure) {
            failure = alternateFailure;
        }
        try {
            GlStateManager._activeTexture(unit);
        } catch (RuntimeException | Error unitFailure) {
            if (failure == null) {
                failure = unitFailure;
            } else {
                appendSuppressed(failure, unitFailure);
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("GpuViewportTarget requires the render thread");
        }
    }

    /**
     * 已发布的资源快照。{@code rect} 携带原点，资源尺寸等于 {@code (rect.width(), rect.height())}；
     * {@code targetGeneration} 与当前资源代数一致，{@code layoutGeneration} 来自产生它的 layout。
     */
    record Snapshot(long targetGeneration, long layoutGeneration, FramebufferRect rect, int framebufferId,
                    int colorTextureId, int depthTextureId) {}

    /**
     * 本卡唯一拥有的 GPU 资源载体：一个 FBO + 一个颜色纹理 + 一个深度纹理（{@code useDepth = true}）。
     * 构造器只调用 {@code super(true)}，绝不分配任何 GL 资源；分配只能通过 {@code resize} 显式触发。
     */
    private static final class OwnedTarget extends RenderTarget {

        private OwnedTarget() {
            super(true);
        }
    }
}
