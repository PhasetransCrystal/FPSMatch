package net.ptcrys.fpsmatch.editor.common.client.viewport.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * R19.1a / R19.1a2 分配期 GL 状态边界：只捕获并恢复本卡实际触碰的状态。
 *
 * <p>
 * <strong>捕获范围</strong>（严格限于本卡的 FBO/纹理分配与释放）：read/draw framebuffer 绑定、
 * viewport、实际 active texture unit、unit0 的 {@code GL_TEXTURE_2D} 绑定、depth test 开关、depth 写掩码、
 * RGBA 写掩码、scissor 开关与 scissor box、clear 颜色、clear 深度。program/shader/VAO/buffer/blend/cull/
 * stencil/matrix 一律<strong>不</strong>捕获、<strong>不</strong>恢复、<strong>不</strong>声明。
 *
 * <p>
 * <strong>缓存型与直写型</strong>（依据 {@code GlStateManager} 实际字节码）：
 * <ul>
 * <li>有 Java 缓存且会短路：{@code _enableDepthTest/_disableDepthTest}（{@code DepthState.mode}）、
 * {@code _depthMask}（{@code DepthState.mask}）、{@code _colorMask}（逐通道短路）、
 * {@code _enableScissorTest/_disableScissorTest}（{@code ScissorState.mode}）、{@code _activeTexture}
 * （{@code activeTexture}）、{@code _bindTexture}（{@code TEXTURES[activeTexture].binding}）。单次
 * setter 在缓存已等于目标值时<strong>不会</strong>下发 GL 调用，所以恢复时每一项都执行“先设相反值、
 * 再设目标值”，保证驱动侧与引擎缓存侧最终都等于 capture 值。</li>
 * <li>无缓存直写：{@code _viewport}、{@code _scissorBox}、{@code _clearColor}、{@code _clearDepth}、
 * {@code _glBindFramebuffer}。单次调用即让驱动等于 capture 值。</li>
 * <li>读回：多值状态（viewport、scissor box、color writemask）用 {@code GL11.glGetIntegerv}，clear 颜色用
 * {@code GL11.glGetFloatv} 数组重载；单值状态用 {@code GlStateManager._getInteger}；clear 深度用标量
 * {@code GL11.glGetDouble(GL_DEPTH_CLEAR_VALUE)} 直接取 double，<strong>不</strong>先取 float 再转
 * double（R19.1a2 修法 5）。能力位用 {@code GL11.glIsEnabled}。</li>
 * </ul>
 *
 * <p>
 * <strong>unit0 窄例外</strong>：引擎 {@code RenderTarget} 的分配路径会在 unit0 上直接
 * {@code glBindTexture}（{@code _bindTexture}），而 {@code TextureUtil.releaseTextureId} →
 * {@code GlStateManager._deleteTexture} 会把命中的 {@code TEXTURES[i].binding} 改成 {@code -1}，
 * 于是“驱动实际绑定”与“引擎缓存绑定”可能已经不一致。因此本类型在进入 unit0 工作前先裸读实际绑定，紧接着
 * 用 {@code _bindTexture(实际绑定)} 把引擎缓存同步到驱动实际值（R19.1a2 修法 1：驱动已经是该值，缓存一致
 * 时短路，不一致时只补一次同值绑定），恢复时再先裸 {@code GL11.glBindTexture} 写驱动、后用同一值同步缓存。
 *
 * <p>
 * <strong>active unit 只切不绑</strong>：{@code GlStateManager.TEXTURES} 只有 {@code TEXTURE_COUNT}（12）
 * 个槽位，而外部 active unit 可以 {@code >= 12}；因此本类型对 active unit 一律只用
 * {@code _activeTexture}（它只做 {@code arg - GL_TEXTURE0} 的缓存比较与 {@code glActiveTexture}，不索引
 * {@code TEXTURES}），<strong>绝不</strong>对 {@code >= 12} 的单元调用 {@code _bindTexture}。同步 active
 * 时先切到另一个合法单元、再切回目标单元，两次互补赋值使驱动与缓存都等于目标值。
 *
 * <p>
 * <strong>线程</strong>：全部入口先做实际的 {@link RenderSystem#isOnRenderThread()} 检查，非渲染线程
 * 一律拒绝；不使用 {@code OrInit} 语义，也不排队 {@code recordRenderCall}。
 *
 * <p>
 * <strong>恢复失败语义</strong>（R19.1a2 修法 3）：逐项尝试，任何一项失败都<strong>不</strong>跳过后续项
 * ——包括“相反值/目标值”两次调用中的第二次、以及最后切回 active unit 的两次调用；第一个失败作为主异常，其余
 * 按真实发生顺序 {@code addSuppressed}，绝不自我抑制、绝不吞掉。只有<strong>全部成功</strong>才把
 * {@code restored} 置真；失败时保持假，允许调用方在同一实例上有界重试一次（本类型自身不循环、不重试）。没有
 * 原始异常时把主异常原样抛出，有原始异常时由调用方以 {@code addSuppressed} 挂接——本类型不吞异常，也不把
 * {@code Error} 降级。
 *
 * <p>
 * 本类型不可变（除 unit0 钉住状态与一次性 {@code restored} 标记外），不持有 GL 句柄、不发布任何状态。
 */
final class GpuAllocationState {

    private final int activeTexture;
    private final int readFramebuffer;
    private final int drawFramebuffer;
    private final boolean depthTestEnabled;
    private final boolean depthWriteMask;
    private final boolean scissorEnabled;
    private final boolean[] colorWriteMask;
    private final int[] viewport;
    private final int[] scissorBox;
    private final float[] clearColor;
    private final double clearDepth;

    private int unit0TextureBinding;
    private boolean unit0Pinned;
    private boolean restored;

    private GpuAllocationState(int activeTexture, int readFramebuffer, int drawFramebuffer,
                               boolean depthTestEnabled, boolean depthWriteMask, boolean scissorEnabled, boolean[] colorWriteMask,
                               int[] viewport, int[] scissorBox, float[] clearColor, double clearDepth) {
        this.activeTexture = activeTexture;
        this.readFramebuffer = readFramebuffer;
        this.drawFramebuffer = drawFramebuffer;
        this.depthTestEnabled = depthTestEnabled;
        this.depthWriteMask = depthWriteMask;
        this.scissorEnabled = scissorEnabled;
        this.colorWriteMask = colorWriteMask;
        this.viewport = viewport;
        this.scissorBox = scissorBox;
        this.clearColor = clearColor;
        this.clearDepth = clearDepth;
    }

    /**
     * 只读捕获当前驱动状态；不改变任何 GL 状态，因此可以在任何资源变更之前调用。每次调用都新建独立数组，
     * 不与任何其它实例共享。clear 深度用标量 {@code GL11.glGetDouble} 直接取 double。
     */
    static GpuAllocationState capture() {
        requireRenderThread();
        final int[] viewport = new int[4];
        final int[] scissorBox = new int[4];
        final int[] colorWriteMask = new int[4];
        final float[] clearColor = new float[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorWriteMask);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        return new GpuAllocationState(
                GlStateManager._getInteger(GL13.GL_ACTIVE_TEXTURE),
                GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                GlStateManager._getInteger(GL11.GL_DEPTH_WRITEMASK) != 0,
                GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                new boolean[] { colorWriteMask[0] != 0, colorWriteMask[1] != 0, colorWriteMask[2] != 0,
                        colorWriteMask[3] != 0 },
                viewport,
                scissorBox,
                clearColor,
                clearDepth);
    }

    /**
     * 把后续分配/释放工作钉到 unit0：先同步 active 到 unit0，再只读记录 unit0 的 {@code GL_TEXTURE_2D}
     * 实际绑定（只有首次调用记录，重入<strong>不</strong>覆盖首次捕获的原绑定），随后在任何资源操作之前立即用
     * 该实际值 {@code _bindTexture} 同步引擎缓存（缓存与驱动已经一致时短路，不一致时补一次同值绑定），最后按
     * “临时值先全 false 再全 true / scissor 先 enable 再 disable”把目标资源可以安全改写的状态置好。绑定读取
     * 成功之前不置钉住标记，读取抛异常时不会留下任何需要恢复的额外状态。
     */
    void beginUnit0Work() {
        requireRenderThread();
        forceActiveTexture(GL13.GL_TEXTURE0);
        if (!this.unit0Pinned) {
            this.unit0TextureBinding = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        this.unit0Pinned = true;
        GlStateManager._bindTexture(this.unit0TextureBinding);
        GlStateManager._depthMask(false);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(false, false, false, false);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableScissorTest();
        GlStateManager._disableScissorTest();
    }

    /**
     * 恢复失败后的收尾准备（R19.1a2 修法 4）：强制 active 到 unit0，并把<strong>此刻</strong>的实际
     * {@code GL_TEXTURE_2D} 绑定回填进引擎缓存——失败的创建路径可能已经改过驱动绑定，缓存必须先同步，否则随后
     * 的裸销毁会在错误的 active 或错误的缓存上工作。本方法<strong>不</strong>改写首次捕获的 unit0 原绑定，
     * 因此其后仍能恢复到进入值。
     */
    void pinUnit0ForCleanup() {
        requireRenderThread();
        forceActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D));
    }

    /**
     * 按 capture 值逐项恢复驱动实际值与引擎缓存值：每一项都尝试，任一项失败都不跳过后续项；全部成功才标记已
     * 恢复，失败时保持未恢复并抛出第一个失败（其余以 suppressed 追加）。重复调用仅在已经全部成功后才成为空操作。
     *
     * <p>
     * 顺序：缓存型开关（相反值→目标值）、无缓存直写值、read/draw framebuffer、unit0 绑定窄例外、最后
     * active unit。本方法覆盖本阶段被触碰的状态，不声明恢复本阶段之外的引擎状态（program/VAO/buffer/blend/
     * stencil 等仍属其它卡片）。
     */
    void restore() {
        requireRenderThread();
        if (this.restored) {
            return;
        }

        Throwable failure = null;

        // 缓存型：先设相反值，再设目标值，两次互补赋值强制驱动与缓存都收敛到 capture 值。
        failure = attemptPair(failure, this.depthTestEnabled, GlStateManager::_disableDepthTest,
                GlStateManager::_enableDepthTest);
        failure = attemptPair(failure, this.depthWriteMask, () -> GlStateManager._depthMask(false),
                () -> GlStateManager._depthMask(true));
        failure = attempt(failure, () -> GlStateManager._colorMask(!this.colorWriteMask[0],
                !this.colorWriteMask[1], !this.colorWriteMask[2], !this.colorWriteMask[3]));
        failure = attempt(failure, () -> GlStateManager._colorMask(this.colorWriteMask[0],
                this.colorWriteMask[1], this.colorWriteMask[2], this.colorWriteMask[3]));
        failure = attemptPair(failure, this.scissorEnabled, GlStateManager::_disableScissorTest,
                GlStateManager::_enableScissorTest);

        // 无缓存直写：单次调用即与驱动一致。
        failure = attempt(failure, () -> GlStateManager._scissorBox(this.scissorBox[0], this.scissorBox[1],
                this.scissorBox[2], this.scissorBox[3]));
        failure = attempt(failure, () -> GlStateManager._viewport(this.viewport[0], this.viewport[1],
                this.viewport[2], this.viewport[3]));
        failure = attempt(failure, () -> GlStateManager._clearColor(this.clearColor[0], this.clearColor[1],
                this.clearColor[2], this.clearColor[3]));
        failure = attempt(failure, () -> GlStateManager._clearDepth(this.clearDepth));

        // read/draw framebuffer 分开恢复；_glBindFramebuffer 无 Java 缓存。
        failure = attempt(failure,
                () -> GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFramebuffer));
        failure = attempt(failure,
                () -> GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFramebuffer));

        // unit0 窄例外：先裸 GL 写驱动实际值，再用同一值同步引擎缓存。
        if (this.unit0Pinned) {
            failure = attemptActiveTexture(failure, GL13.GL_TEXTURE0);
            failure = attempt(failure,
                    () -> GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.unit0TextureBinding));
            failure = attempt(failure, () -> GlStateManager._bindTexture(this.unit0TextureBinding));
        }

        // 最后只切回原 active unit；只切 active，绝不 _bindTexture。
        failure = attemptActiveTexture(failure, this.activeTexture);

        if (failure == null) {
            this.restored = true;
            return;
        }
        rethrow(failure);
    }

    /**
     * 尝试一项恢复动作：第一个失败作为主异常保留，后续失败只以 {@code addSuppressed} 追加（不自我抑制、不吞掉）。
     *
     * @return 主异常，或 {@code null} 表示目前为止全部成功
     */
    private static Throwable attempt(Throwable first, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (first == null) {
                return failure;
            }
            if (failure != first) {
                first.addSuppressed(failure);
            }
        }
        return first;
    }

    /**
     * 目标值为 {@code true} 时先 {@code setFalse} 再 {@code setTrue}，否则先 {@code setTrue} 再
     * {@code setFalse}。两次互补写入使带缓存的开关无论进入时缓存是否与驱动一致，最终都等于目标值；两次调用
     * <strong>各自独立尝试</strong>，第二次不因第一次失败而跳过。
     */
    private static Throwable attemptPair(Throwable first, boolean target, Runnable setFalse,
                                         Runnable setTrue) {
        if (target) {
            return attempt(attempt(first, setFalse), setTrue);
        }
        return attempt(attempt(first, setTrue), setFalse);
    }

    /** {@link #forceActiveTexture(int)} 的两次 {@code _activeTexture} 各自独立尝试。 */
    private static Throwable attemptActiveTexture(Throwable first, int unit) {
        final int alternate = unit == GL13.GL_TEXTURE0 ? GL13.GL_TEXTURE1 : GL13.GL_TEXTURE0;
        return attempt(attempt(first, () -> GlStateManager._activeTexture(alternate)),
                () -> GlStateManager._activeTexture(unit));
    }

    /** 只把捕获到的 {@code RuntimeException|Error} 原样重抛：不包装、不降级、不转成“成功”。 */
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    /**
     * 只用 {@code _activeTexture} 把 active unit 同步到 {@code unit}：先切到另一个合法单元再切回目标，
     * 两次调用互补赋值。{@code _activeTexture} 不索引 {@code TEXTURES}，因此任意合法单元号都安全；本方法
     * 绝不触碰任何单元的纹理绑定。
     */
    private static void forceActiveTexture(int unit) {
        final int alternate = unit == GL13.GL_TEXTURE0 ? GL13.GL_TEXTURE1 : GL13.GL_TEXTURE0;
        GlStateManager._activeTexture(alternate);
        GlStateManager._activeTexture(unit);
    }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("GpuAllocationState requires the render thread");
        }
    }
}
