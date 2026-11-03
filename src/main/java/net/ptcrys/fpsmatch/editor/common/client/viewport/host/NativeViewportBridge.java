package net.ptcrys.fpsmatch.editor.common.client.viewport.host;

import net.ptcrys.fpsmatch.editor.common.client.viewport.LogicalRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.client.viewport.host.NativeViewportMeasurement.Geometry;
import net.ptcrys.fpsmatch.editor.common.client.viewport.host.NativeViewportMeasurement.Status;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;

import icyllis.modernui.core.Context;
import icyllis.modernui.core.Core;
import icyllis.modernui.graphics.Matrix;
import icyllis.modernui.graphics.Rect;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.view.ViewParent;
import icyllis.modernui.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * R19.1b：真实 ModernUI 3.12 原生 UI 的测量桥接（纯几何 owner：不创建界面入口，也不建立会话生命周期）。
 *
 * <p>
 * 职责边界：
 * <ul>
 * <li>只在<strong>真实 attach</strong> 的原生视图上测量：{@link ViewTreeObserver.OnPreDrawListener}
 * 逐帧读真实布局与全局矩阵；绝不在浮动 {@link ViewTreeObserver} 上挂 preDraw，也绝不造 fake View；</li>
 * <li>只输出 {@link NativeViewportMeasurement}（surface 像素尺寸 / density / logical rect / 冻结布局），
 * <strong>不</strong>触碰相机、渲染后端、选择拾取、输入遮挡、输入法与文档写盘，也不读取其它作者的源；</li>
 * <li>所有公开入口先做 {@link Core#checkUiThread()}，全部状态只在 UI 线程推进。</li>
 * </ul>
 *
 * <p>
 * 代数语义（一律 {@link Math#incrementExact} 单调递增，溢出即永久失效）：
 * <ul>
 * <li>{@code viewGeneration}：首次 attach 与每次 detach（显式或物理）各递增一次，重复 detach 同样递增，
 * 旧投递随旧代数一起失效；</li>
 * <li>{@code windowGeneration}：由 owner 经 {@link #updateWindow} 发布，只接受非递减窗口戳；</li>
 * <li>{@code layoutGeneration}：仅当真实测量语义变化时递增，同帧同值绝不重复发消息。</li>
 * </ul>
 *
 * <p>
 * 几何语义（与卡片逐项对齐）：
 * <ul>
 * <li>surface 取 {@code root.getRootView()} 的<strong>真实</strong>宽高，绝不用窗口/ GUI scale 顶替；</li>
 * <li>surface 全局原点必须<strong>严格为 (0,0)</strong>，本阶段明确拒绝非 0 原点，不猜 framebuffer 对齐；</li>
 * <li>density 只从真实 {@code DisplayMetrics.density} 取一次，且只施加一次；</li>
 * <li>{@code rootSurfaceRect} 保留 root 自身四角包围盒（非 0 内缩原样保留），viewport 侧再与 surface、
 * 显式 clip 以及祖先裁剪求交；<strong>viewport 零轴是合法 READY</strong>（暂不可渲染/拾取）；</li>
 * <li>坐标一律由完整全局矩阵映射（含真实滚动与分数平移），不再叠加 location/scroll，也不做
 * {@code getLocationInWindow} 量化。</li>
 * </ul>
 *
 * <p>
 * attach 记录本次 {@code issuedToken} 作为期望代数，并以哨兵对 sink 重入做保护：sink 内再次
 * {@link #detach()} 时旧闭包立即失效，绝不把旧 READY 覆盖回去。
 */
public final class NativeViewportBridge {

    /** {@code alpha * transitionAlpha} 小于等于此值视为不可见；部分透明不收缩矩形。 */
    private static final float ALPHA_DRAW_THRESHOLD = 0.001F;

    private static final long VIEW_GENERATION_INITIAL = 0L;
    private static final long LAYOUT_GENERATION_INITIAL = 0L;
    private static final long WINDOW_GENERATION_INITIAL = 0L;
    /** 四角映射：本卡片只映射 4 个点。 */
    private static final int CORNER_POINT_COUNT = 4;
    /** 祖先链遍历上限：纯防御，避免异常视图树造成无界循环。 */
    private static final int MAX_ANCESTRY_DEPTH = 512;

    private final long hostEpoch;
    private final long bindingGeneration;
    private final Consumer<NativeViewportMeasurement> uiSink;

    /** UI owner 独占的可复用草稿区：绝不进入 payload，也绝不跨帧携带语义。 */
    private final Scratch scratch = new Scratch();

    private long currentViewGeneration = VIEW_GENERATION_INITIAL;
    private long currentLayoutGeneration = LAYOUT_GENERATION_INITIAL;

    private WindowStamp windowStamp;
    private ViewAttachment attachment;
    private ViewTreeObserver observedObserver;
    private PreDraw observedPreDraw;

    private MeasureKey lastKey;

    /** 永久失效标记：代数溢出后只重抛原异常，绝不重置代数、绝不发布新的 READY。 */
    private boolean invalid;
    /** sink 重入哨兵：为 true 时 onPreDraw 旧闭包立即失效，不会递归发布。 */
    private boolean insideSink;

    public NativeViewportBridge(long hostEpoch, long bindingGeneration,
                                Consumer<NativeViewportMeasurement> uiSink) {
        Core.checkUiThread();
        if (hostEpoch < 0L) {
            throw new IllegalArgumentException("hostEpoch must be >= 0: " + hostEpoch);
        }
        if (bindingGeneration < 0L) {
            throw new IllegalArgumentException("bindingGeneration must be >= 0: " + bindingGeneration);
        }
        if (uiSink == null) {
            throw new IllegalArgumentException("uiSink must not be null");
        }
        this.hostEpoch = hostEpoch;
        this.bindingGeneration = bindingGeneration;
        this.uiSink = uiSink;
    }

    /**
     * 绑定真实原生 UI 的 root 与 viewport，并返回本次发布的 viewGeneration。
     *
     * <p>
     * 契约：参数非 null；viewport 必须是 root 自身或其真实后代（允许浮动视图的 {@code getRootView}
     * 与之不同，只要在 root 的子树内）；未 detach 前二次 attach 抛 {@link IllegalArgumentException}。
     *
     * <p>
     * 本方法只递增一次 viewGeneration 并以该局部 {@code issuedToken} 构造 WAITING_LAYOUT 交给 sink；
     * sink 返回后只返回该 {@code issuedToken}，绝不重读当前代数。若 sink 内重入 detach→attach 了新
     * View，旧 {@code issuedToken} 会被后续 {@link #updateWindow} 拒绝，也不会把旧 READY 覆盖回去。
     *
     * <p>
     * 只有两侧都真实 attach 后才安装唯一的 preDraw 监听；否则只装 attach 监听并发布 WAITING_LAYOUT。
     */
    public long attach(View root, View viewport) {
        Core.checkUiThread();
        requireUsable();
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (viewport == null) {
            throw new IllegalArgumentException("viewport must not be null");
        }
        if (attachment != null) {
            throw new IllegalArgumentException("attach called while already attached");
        }
        if (!isSelfOrDescendant(root, viewport)) {
            throw new IllegalArgumentException("viewport must be root itself or a descendant of root");
        }
        final long issuedToken = nextViewGeneration();
        windowStamp = null;
        final AttachObserver observer = new AttachObserver();
        final ViewAttachment registered = new ViewAttachment(root, viewport, issuedToken, observer);
        attachment = registered;
        lastKey = null;
        root.addOnAttachStateChangeListener(observer);
        viewport.addOnAttachStateChangeListener(observer);
        if (root.isAttachedToWindow() && viewport.isAttachedToWindow()) {
            installPreDraw(registered);
        }
        emitWaiting(issuedToken, WINDOW_GENERATION_INITIAL);
        return issuedToken;
    }

    /**
     * 发布窗口戳（framebuffer 像素尺寸）。数据必须来自 MC owner 捕获的真实 Window 尺寸，并经 UI 任务
     * 投递；本桥只校验代数与尺寸本身。
     *
     * <p>
     * 四个参数先全部校验（{@code expectedViewGeneration}/{@code windowGeneration} 与宽高均 {@code >= 0}，
     * 否则 {@link IllegalArgumentException}）；随后若期望代数与当前 attach 不符或当前未 attach，则
     * <strong>静默忽略</strong>，绝不触碰新 View 的数据。窗口戳倒退或同代数不同尺寸同样抛
     * {@link IllegalArgumentException} 且不改动旧戳；完全同值则幂等返回。
     *
     * <p>
     * 新戳立即发布 WAITING_LAYOUT 并作废旧布局（空 geometry）；只有在真实 surface 测量与戳记一致后
     * 才可能由 preDraw 发布 READY。绝不把旧 surface 临时缩放成新窗口尺寸。
     */
    public void updateWindow(long expectedViewGeneration, long windowGeneration, int framebufferWidth,
                             int framebufferHeight) {
        Core.checkUiThread();
        requireUsable();
        if (expectedViewGeneration < 0L) {
            throw new IllegalArgumentException(
                    "expectedViewGeneration must be >= 0: " + expectedViewGeneration);
        }
        if (windowGeneration < 0L) {
            throw new IllegalArgumentException("windowGeneration must be >= 0: " + windowGeneration);
        }
        if (framebufferWidth < 0) {
            throw new IllegalArgumentException("framebufferWidth must be >= 0: " + framebufferWidth);
        }
        if (framebufferHeight < 0) {
            throw new IllegalArgumentException("framebufferHeight must be >= 0: " + framebufferHeight);
        }
        final ViewAttachment current = attachment;
        if (current == null || current.issuedToken() != expectedViewGeneration) {
            return;
        }
        final WindowStamp stamp = windowStamp;
        if (stamp != null) {
            if (windowGeneration < stamp.windowGeneration()) {
                throw new IllegalArgumentException("windowGeneration must not decrease: " + windowGeneration + " < " + stamp.windowGeneration());
            }
            if (windowGeneration == stamp.windowGeneration()) {
                if (framebufferWidth != stamp.framebufferWidth() || framebufferHeight != stamp.framebufferHeight()) {
                    throw new IllegalArgumentException("framebuffer size changed within windowGeneration " + windowGeneration + ": " + framebufferWidth + "x" + framebufferHeight + " != " + stamp.framebufferWidth() + "x" + stamp.framebufferHeight());
                }
                return;
            }
        }
        windowStamp = new WindowStamp(windowGeneration, framebufferWidth, framebufferHeight);
        lastKey = null;
        emitWaiting(current.issuedToken(), windowGeneration);
    }

    /**
     * 显式解绑：递增 viewGeneration、清空窗口戳与引用，随后卸载 preDraw 与全部 attach 监听，
     * 并发布 DETACHED（空 geometry）。重复调用同样递增并失效旧投递。
     */
    public void detach() {
        Core.checkUiThread();
        requireUsable();
        final long issuedToken = nextViewGeneration();
        final ViewAttachment current = attachment;
        windowStamp = null;
        attachment = null;
        uninstallPreDraw();
        if (current != null) {
            final AttachObserver observer = current.attachObserver();
            observer.retire();
            current.root().removeOnAttachStateChangeListener(observer);
            current.viewport().removeOnAttachStateChangeListener(observer);
        }
        emitDetached(issuedToken);
    }

    // ------------------------------------------------------------------ 监听安装与卸载

    /**
     * 只在不真实 attach 时安装 attach 监听；真实 attach 时安装唯一的 preDraw。
     * 本方法<strong>不发布</strong>任何消息（发布由调用方在同一帧内完成，避免多出一条 layoutGeneration）。
     */
    private void installPreDraw(ViewAttachment expected) {
        if (invalid || attachment != expected || observedPreDraw != null) {
            return;
        }
        final View root = expected.root();
        final View viewport = expected.viewport();
        if (!root.isAttachedToWindow() || !viewport.isAttachedToWindow()) {
            return;
        }
        final View surface = root.getRootView();
        if (surface == null) {
            return;
        }
        final ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            return;
        }
        final PreDraw listener = new PreDraw(observer, expected.issuedToken(), root, viewport, surface);
        observer.addOnPreDrawListener(listener);
        observedObserver = observer;
        observedPreDraw = listener;
    }

    /**
     * 卸载 live preDraw：先判 {@code isAlive()}，只在 live observer 上摘除；若旧 observer 已被合并/杀死，
     * 则改从当前真实 View 的 live observer 上摘除同一个 listener，绝不调用已死 observer、也不重复注册。
     */
    private void uninstallPreDraw() {
        final ViewTreeObserver observer = observedObserver;
        final PreDraw listener = observedPreDraw;
        observedObserver = null;
        observedPreDraw = null;
        if (observer == null || listener == null) {
            return;
        }
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(listener);
            return;
        }
        final View root = listener.observedRoot();
        if (root == null) {
            return;
        }
        final ViewTreeObserver live = root.getViewTreeObserver();
        if (live != null && live != observer && live.isAlive()) {
            live.removeOnPreDrawListener(listener);
        }
    }

    /**
     * 物理 detach：先校验当前 View 身份，再递增 viewGeneration、清窗口戳、作废 READY、卸载 live preDraw；
     * attach 监听保留，重新 attach 后由<strong>新</strong> viewGeneration 的新闭包重挂，绝不复用旧闭包。
     */
    private void handlePhysicalDetach(View detached) {
        Core.checkUiThread();
        final ViewAttachment current = attachment;
        if (current == null) {
            return;
        }
        if (detached != current.root() && detached != current.viewport()) {
            return;
        }
        final long issuedToken = nextViewGeneration();
        windowStamp = null;
        lastKey = null;
        uninstallPreDraw();
        attachment = new ViewAttachment(current.root(), current.viewport(), issuedToken,
                current.attachObserver());
        emitWaiting(issuedToken, WINDOW_GENERATION_INITIAL);
    }

    // ------------------------------------------------------------------ 测量与发布

    /**
     * 真实 preDraw 测量：只有真实布局与真实矩阵齐备时才可能 READY；任何一项不成立都退化为 WAITING /
     * UNSUPPORTED 且几何为空。
     */
    private void evaluate(long expectedToken, View expectedSurface) {
        Core.checkUiThread();
        final ViewAttachment current = attachment;
        if (current == null || current.issuedToken() != expectedToken) {
            return;
        }
        final WindowStamp stamp = windowStamp;
        scratch.reset();
        final Ancestry ancestry = stamp == null ? null : resolveAncestry(current, expectedSurface, stamp);
        final Status status;
        if (ancestry == null) {
            status = Status.WAITING_LAYOUT;
        } else {
            status = measure(ancestry, current, stamp, scratch);
        }
        final MeasureKey key = keyOf(expectedToken, stamp, status, ancestry);
        if (key.equals(lastKey)) {
            return; // 同帧同语义：绝不重复发消息、绝不重挂监听。
        }
        final long layoutGeneration = nextLayoutGeneration();
        final Geometry geometry = status == Status.READY ? buildGeometry(scratch, stamp, layoutGeneration) : null;
        // 先记状态再派发：sink 内重入 detach 也无法把旧 READY 恢复回来。
        lastKey = key;
        publish(new NativeViewportMeasurement(hostEpoch, bindingGeneration, expectedToken,
                stamp == null ? WINDOW_GENERATION_INITIAL : stamp.windowGeneration(), layoutGeneration,
                status, Optional.ofNullable(geometry)));
    }

    /**
     * 真实测量前置条件：两侧真实 attach 且非 relayout 挂起、root 两轴 &gt; 0、surface 与捕获的
     * {@code root.getRootView()} 身份一致、surface 两轴 &gt; 0，且 surface 尺寸与窗口戳完全一致。
     * 任一不成立返回 {@code null}（WAITING_LAYOUT），绝不把旧回调转发到新 root，也绝不用旧戳强行 READY。
     */
    private Ancestry resolveAncestry(ViewAttachment current, View expectedSurface, WindowStamp stamp) {
        final View root = current.root();
        final View viewport = current.viewport();
        if (!root.isAttachedToWindow() || !viewport.isAttachedToWindow()) {
            return null;
        }
        if (root.isLayoutRequested() || viewport.isLayoutRequested()) {
            return null;
        }
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            return null;
        }
        if (expectedSurface == null || root.getRootView() != expectedSurface) {
            return null;
        }
        if (!expectedSurface.isAttachedToWindow()) {
            return null;
        }
        if (expectedSurface.getWidth() <= 0 || expectedSurface.getHeight() <= 0) {
            return null;
        }
        if (expectedSurface.getWidth() != stamp.framebufferWidth() || expectedSurface.getHeight() != stamp.framebufferHeight()) {
            return null; // 绝不把旧 surface 临时缩放到新窗口尺寸。
        }
        return buildAncestry(current, expectedSurface);
    }

    /**
     * 从 viewport 沿真实父链走到 surface，逐节点快照（全局矩阵 / 可见性 / alpha / 显式 clip / clipChildren /
     * clipToPadding），并在同一趟里确认链上确实经过本桥 root。链条断裂、非同源 ViewParent、深度耗尽或
     * 节点未真实 attach 都返回 {@code null}，交给 WAITING_LAYOUT 处理，绝不猜测。
     */
    private Ancestry buildAncestry(ViewAttachment current, View surface) {
        final List<ViewSnapshot> nodes = new ArrayList<>(8);
        View walker = current.viewport();
        int rootIndex = -1;
        boolean unsupportedTransform = false;
        boolean drawn = true;
        for (int depth = 0; walker != null && depth < MAX_ANCESTRY_DEPTH; depth++) {
            if (!walker.isAttachedToWindow()) {
                return null;
            }
            final ViewSnapshot snapshot = snapshotOf(walker);
            if (snapshot == null) {
                return null;
            }
            if (walker == current.root()) {
                rootIndex = nodes.size();
            }
            unsupportedTransform |= snapshot.unsupported();
            drawn = drawn && snapshot.visible() && isAlphaVisible(snapshot);
            nodes.add(snapshot);
            if (walker == surface) {
                if (rootIndex < 0) {
                    return null; // 不经过本桥 root 的链：绝不测量。
                }
                return new Ancestry(List.copyOf(nodes), rootIndex, unsupportedTransform, drawn);
            }
            final ViewParent parent = walker.getParent();
            if (!(parent instanceof View parentView)) {
                return null; // 链条断裂或非同源 ViewParent：不猜。
            }
            walker = parentView;
        }
        return null; // 深度耗尽：视为不可测量。
    }

    /** 单节点快照：读真实 View 状态与两个矩阵（节点自身矩阵、到全局的复合矩阵）。 */
    private ViewSnapshot snapshotOf(View node) {
        final float alpha = node.getAlpha();
        final float transitionAlpha = node.getTransitionAlpha();
        final Matrix local = node.getMatrix();
        final Matrix animation = node.getAnimationMatrix();
        final Matrix global = new Matrix();
        node.transformMatrixToGlobal(global);
        final ViewParent parent = node.getParent();
        // 字节码语义：节点自身的边界裁剪由「父是否为 null / 父的 clipChildren」决定，
        // 不是由节点自己的 getClipChildren 决定。
        final boolean clipToOwnBounds = parent == null || (parent instanceof ViewGroup group && group.getClipChildren());
        LocalRect clipLocal = null;
        if (node.getClipBounds(scratch.clip)) {
            clipLocal = new LocalRect(scratch.clip.left, scratch.clip.top, scratch.clip.right,
                    scratch.clip.bottom);
        }
        final LocalRect paddedLocal = paddedRectOf(node);
        final boolean unsupported = !Float.isFinite(alpha) || !Float.isFinite(transitionAlpha) || local == null || !local.isFinite() || !local.isTranslate() || !global.isFinite() || !global.isTranslate() || (animation != null && !animation.isIdentity());
        return new ViewSnapshot(node, global, unsupported, node.getVisibility() == View.VISIBLE, alpha,
                transitionAlpha, node.getWidth(), node.getHeight(), clipToOwnBounds, clipLocal, paddedLocal);
    }

    /**
     * 该节点自身的 padding 内缩矩形（本地坐标）：只有 {@link ViewGroup} 且 {@code getClipToPadding()}
     * 为 true 且解析后 padding 任一非 0 时才产生；该矩形用于裁剪其<strong>子树</strong>，
     * 因此 viewport 自身的 padding 不裁剪 viewport。
     */
    private static LocalRect paddedRectOf(View node) {
        if (!(node instanceof ViewGroup group) || !group.getClipToPadding()) {
            return null;
        }
        final int left = node.getPaddingLeft();
        final int top = node.getPaddingTop();
        final int right = node.getWidth() - node.getPaddingRight();
        final int bottom = node.getHeight() - node.getPaddingBottom();
        if (left == 0 && top == 0 && right == node.getWidth() && bottom == node.getHeight()) {
            return null;
        }
        return new LocalRect(left, top, right, bottom);
    }

    private static boolean isAlphaVisible(ViewSnapshot snapshot) {
        return snapshot.alpha() * snapshot.transitionAlpha() > ALPHA_DRAW_THRESHOLD;
    }

    /**
     * 测量判定（不发布）：返回 READY / WAITING_LAYOUT / UNSUPPORTED_TRANSFORM，并把可测量结果写进草稿区。
     */
    private Status measure(Ancestry ancestry, ViewAttachment current, WindowStamp stamp,
                           Scratch draft) {
        if (ancestry.unsupportedTransform()) {
            return Status.UNSUPPORTED_TRANSFORM;
        }
        // 第1项：density 必须来自真实 DisplayMetrics，且有限且 > 0，否则 UNSUPPORTED_TRANSFORM。
        final Context context = current.root().getContext();
        if (context == null) {
            return Status.UNSUPPORTED_TRANSFORM;
        }
        draft.density = context.getResources().getDisplayMetrics().density;
        if (!Double.isFinite(draft.density) || draft.density <= 0.0D) {
            return Status.UNSUPPORTED_TRANSFORM;
        }
        // 第2项：surface 全局原点必须严格为 (0,0)；本阶段明确拒绝非 0 原点，不猜 framebuffer 对齐。
        final ViewSnapshot surfaceNode = ancestry.nodes().get(ancestry.nodes().size() - 1);
        final PixelRect surfaceOrigin = mapBounds(surfaceNode.global(), 0.0D, 0.0D, 0.0D, 0.0D, draft);
        if (surfaceOrigin == null || surfaceOrigin.left() != 0.0D || surfaceOrigin.top() != 0.0D) {
            return Status.UNSUPPORTED_TRANSFORM;
        }
        // 第3项：root 保留自身四角包围盒（非 0 内缩原样保留），viewport 取其真实四角包围盒。
        final ViewSnapshot rootNode = ancestry.nodes().get(ancestry.rootIndex());
        final PixelRect rootPixel = mapBounds(rootNode.global(), 0.0D, 0.0D, rootNode.width(),
                rootNode.height(), draft);
        final ViewSnapshot viewportNode = ancestry.nodes().get(0);
        final PixelRect viewportPixel = mapBounds(viewportNode.global(), 0.0D, 0.0D,
                viewportNode.width(), viewportNode.height(), draft);
        if (rootPixel == null || viewportPixel == null) {
            return Status.UNSUPPORTED_TRANSFORM;
        }
        // 第4项：先与 surface [0,W)x[0,H) 求交，再逐节点叠加显式 clip / 自身边界 / 祖先 padding。
        final PixelRect base = intersect(viewportPixel,
                new PixelRect(0.0D, 0.0D, stamp.framebufferWidth(), stamp.framebufferHeight()));
        final PixelRect candidate = ancestry.drawn() ? clipToVisible(ancestry, base, draft) : zeroPoint(base);
        draft.rootPixel = rootPixel;
        draft.viewportPixel = candidate;
        draft.computeLogical();
        if (!draft.logicalFinite()) {
            return Status.UNSUPPORTED_TRANSFORM;
        }
        if (!draft.rootPositive()) {
            return Status.WAITING_LAYOUT; // 等同 root 任一实际轴 <= 0。
        }
        return Status.READY;
    }

    /**
     * viewport 可见区域的真实裁剪：先与 surface 求交（已含在 {@code base} 里），再按 viewport→surface
     * 顺序逐节点叠加显式 {@code getClipBounds}、父 {@code clipChildren} 决定的自身边界、以及
     * <strong>严格祖先</strong>的 padding 内缩。非正尺寸或空交集一律归一为真正的零轴矩形，绝不补齐为 1。
     */
    private PixelRect clipToVisible(Ancestry ancestry, PixelRect base, Scratch draft) {
        PixelRect visible = base;
        final List<ViewSnapshot> nodes = ancestry.nodes();
        for (int index = 0; index < nodes.size(); index++) {
            final ViewSnapshot node = nodes.get(index);
            if (node.clipLocal() != null) {
                visible = intersect(visible, mapLocalRect(node, node.clipLocal(), draft));
            }
            if (node.clipToOwnBounds()) {
                visible = intersect(visible,
                        mapBounds(node.global(), 0.0D, 0.0D, node.width(), node.height(), draft));
            }
            if (index > 0 && node.paddedLocal() != null) {
                visible = intersect(visible, mapLocalRect(node, node.paddedLocal(), draft));
            }
            if (visible.right() <= visible.left() || visible.bottom() <= visible.top()) {
                break;
            }
        }
        return normalize(visible, base);
    }

    private static PixelRect normalize(PixelRect visible, PixelRect base) {
        final double left = clamp(visible.left(), base.left(), base.right());
        final double right = Math.max(clamp(visible.right(), base.left(), base.right()), left);
        final double top = clamp(visible.top(), base.top(), base.bottom());
        final double bottom = Math.max(clamp(visible.bottom(), base.top(), base.bottom()), top);
        return new PixelRect(left, top, right, bottom);
    }

    private static double clamp(double value, double low, double high) {
        if (high < low) {
            return low;
        }
        return Math.min(Math.max(value, low), high);
    }

    private static PixelRect zeroPoint(PixelRect base) {
        return normalize(new PixelRect(base.left(), base.top(), base.left(), base.top()), base);
    }

    private static PixelRect intersect(PixelRect left, PixelRect right) {
        return new PixelRect(Math.max(left.left(), right.left()), Math.max(left.top(), right.top()),
                Math.min(left.right(), right.right()), Math.min(left.bottom(), right.bottom()));
    }

    private static PixelRect mapLocalRect(ViewSnapshot node, LocalRect local, Scratch draft) {
        return mapBounds(node.global(), local.left(), local.top(), local.right(), local.bottom(), draft);
    }

    /**
     * 把本地坐标矩形四角经完整全局矩阵映射，取映射后四点的轴对齐包围盒；结果保持 float 宽化后的 double
     * 精度，绝不做 {@code getLocationInWindow} 量化，也绝不叠加 location/scroll。
     *
     * <p>
     * R19.1b2 第1点：传给四角映射<strong>之前</strong>先做「非正本地裁剪尺寸的显式空表示」——
     * {@code right <= left} 的轴折叠为 {@code right = left}，{@code bottom <= top} 的轴折叠为
     * {@code bottom = top}，另一合法轴保持原范围。绝不把倒置区间按 min/max 正规化成有面积矩形：祖先
     * padding 左右各 15、祖先宽 20 时，[15,5) 必须折叠成 [15,15) 的空区间，而不是 10 像素宽的可见带；
     * {@code width}/{@code height} 为 0 的轴同样保持 0，不得扩成 1。四角 min/max 只取折叠后已非负矩形的
     * global bounds，且本 helper 对矩形 bounds 与 clip 都是同一份实现。
     */
    private static PixelRect mapBounds(Matrix global, double left, double top, double right,
                                       double bottom, Scratch draft) {
        // 空表示：倒置（或零宽/零高）轴折叠到 min 边，避免四角 AABB 把空区间还原成有面积区间。
        final double foldedRight = right <= left ? left : right;
        final double foldedBottom = bottom <= top ? top : bottom;
        final float[] src = draft.src;
        src[0] = (float) left;
        src[1] = (float) top;
        src[2] = (float) foldedRight;
        src[3] = (float) top;
        src[4] = (float) left;
        src[5] = (float) foldedBottom;
        src[6] = (float) foldedRight;
        src[7] = (float) foldedBottom;
        global.mapPoints(src, 0, draft.dst, 0, CORNER_POINT_COUNT);
        final float[] dst = draft.dst;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < dst.length; index += 2) {
            final double x = dst[index];
            final double y = dst[index + 1];
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return null;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new PixelRect(minX, minY, maxX, maxY);
    }

    /**
     * R19.1b2 第2/3/5点：{@code rootSurfaceRect} 虽复用 {@code LogicalRect} 载荷类型，单位却是<strong>真实
     * surface 像素</strong>，直接取 root 原始四角包围盒（原点可非零/负/分数），绝不为类型同名先除 density；
     * {@code viewportSurfaceRect} 单位仍是相对 root 左上的 logical（只转换一次）；scale 的 pixelOrigin
     * 直接取原始 root 像素左上，不做任何除乘往返；并把原始 viewport 像素完整 bounds 作为 present 的原生
     * 像素测量交给四参数布局，由 {@code framebufferRect()} 直接消费，不再 density 往返。
     */
    private Geometry buildGeometry(Scratch draft, WindowStamp stamp, long layoutGeneration) {
        final PixelRect rootPixel = draft.rootPixel;
        final PixelRect viewportPixel = draft.viewportPixel;
        final LogicalRect rootSurfaceRect = new LogicalRect(rootPixel.left(), rootPixel.top(),
                rootPixel.right() - rootPixel.left(), rootPixel.bottom() - rootPixel.top());
        final LogicalRect viewportSurfaceRect = new LogicalRect(draft.viewLeft, draft.viewTop,
                draft.viewWidth, draft.viewHeight);
        final ScaleTransform scale = new ScaleTransform(draft.density, draft.density,
                rootPixel.left(), rootPixel.top(), stamp.framebufferWidth(), stamp.framebufferHeight());
        final LogicalRect measuredFramebufferBounds = new LogicalRect(viewportPixel.left(),
                viewportPixel.top(), viewportPixel.right() - viewportPixel.left(),
                viewportPixel.bottom() - viewportPixel.top());
        return new Geometry(stamp.framebufferWidth(), stamp.framebufferHeight(), draft.density,
                rootSurfaceRect, viewportSurfaceRect,
                new ViewportLayout(layoutGeneration, viewportSurfaceRect, scale,
                        Optional.of(measuredFramebufferBounds)));
    }

    /**
     * 第6项：不可变语义指纹。窗口戳、density、root/viewport 可见包围盒、祖先链的支持性/可见性/alpha/
     * 裁剪任一变化都会得到不同的键并递增 layoutGeneration 后发布；完全相同则一条消息都不发。
     */
    private MeasureKey keyOf(long viewGeneration, WindowStamp stamp, Status status,
                             Ancestry ancestry) {
        final List<NodeKey> nodes;
        if (ancestry == null) {
            nodes = List.of();
        } else {
            final List<NodeKey> keys = new ArrayList<>(ancestry.nodes().size());
            for (final ViewSnapshot snapshot : ancestry.nodes()) {
                final PixelRect origin = mapBounds(snapshot.global(), 0.0D, 0.0D, 0.0D, 0.0D, scratch);
                keys.add(new NodeKey(snapshot.view(), snapshot.visible(), snapshot.alpha(),
                        snapshot.transitionAlpha(), snapshot.width(), snapshot.height(),
                        snapshot.clipToOwnBounds(), snapshot.clipLocal(), snapshot.paddedLocal(),
                        origin == null ? Double.NaN : origin.left(),
                        origin == null ? Double.NaN : origin.top()));
            }
            nodes = List.copyOf(keys);
        }
        return new MeasureKey(viewGeneration,
                stamp == null ? WINDOW_GENERATION_INITIAL : stamp.windowGeneration(), status,
                Double.doubleToLongBits(scratch.density), scratch.rootPixel, scratch.viewportPixel, nodes);
    }

    private void emitWaiting(long viewGeneration, long windowGeneration) {
        final long layoutGeneration = nextLayoutGeneration();
        lastKey = MeasureKey.withoutGeometry(viewGeneration, windowGeneration, Status.WAITING_LAYOUT);
        publish(new NativeViewportMeasurement(hostEpoch, bindingGeneration, viewGeneration,
                windowGeneration, layoutGeneration, Status.WAITING_LAYOUT, Optional.empty()));
    }

    private void emitDetached(long viewGeneration) {
        final long layoutGeneration = nextLayoutGeneration();
        lastKey = MeasureKey.withoutGeometry(viewGeneration, WINDOW_GENERATION_INITIAL,
                Status.DETACHED);
        publish(new NativeViewportMeasurement(hostEpoch, bindingGeneration, viewGeneration,
                WINDOW_GENERATION_INITIAL, layoutGeneration, Status.DETACHED, Optional.empty()));
    }

    /** sink 重入只用于失效旧闭包，绝不吞掉 sink 抛出的异常，也不重试或伪造成功。 */
    private void publish(NativeViewportMeasurement measured) {
        final boolean outer = insideSink;
        insideSink = true;
        try {
            uiSink.accept(measured);
        } finally {
            insideSink = outer;
        }
    }

    private static boolean isSelfOrDescendant(View root, View viewport) {
        if (root == viewport) {
            return true;
        }
        ViewParent walker = viewport.getParent();
        for (int depth = 0; depth < MAX_ANCESTRY_DEPTH; depth++) {
            if (!(walker instanceof View parentView)) {
                return false;
            }
            if (parentView == root) {
                return true;
            }
            walker = parentView.getParent();
        }
        return false;
    }

    // ------------------------------------------------------------------ 代数

    private long nextViewGeneration() {
        try {
            currentViewGeneration = Math.incrementExact(currentViewGeneration);
            return currentViewGeneration;
        } catch (ArithmeticException overflow) {
            invalidate();
            throw overflow;
        }
    }

    private long nextLayoutGeneration() {
        try {
            currentLayoutGeneration = Math.incrementExact(currentLayoutGeneration);
            return currentLayoutGeneration;
        } catch (ArithmeticException overflow) {
            invalidate();
            throw overflow;
        }
    }

    /** 代数耗尽即永久失效：注销观察者、清空 View 引用，不重置任何代数，也不发布新的 READY。 */
    private void invalidate() {
        invalid = true;
        windowStamp = null;
        lastKey = null;
        final ViewAttachment current = attachment;
        attachment = null;
        uninstallPreDraw();
        if (current != null) {
            final AttachObserver observer = current.attachObserver();
            observer.retire();
            current.root().removeOnAttachStateChangeListener(observer);
            current.viewport().removeOnAttachStateChangeListener(observer);
        }
    }

    private void requireUsable() {
        if (invalid) {
            throw new IllegalStateException("bridge is permanently invalid after generation overflow");
        }
    }

    // ------------------------------------------------------------------ 内部结构

    /** 观察者闭包绑定的 attach 记录：{@code issuedToken} 即本次 attach 的期望代数。 */
    private record ViewAttachment(View root, View viewport, long issuedToken,
                                  AttachObserver attachObserver) {}

    private record WindowStamp(long windowGeneration, int framebufferWidth, int framebufferHeight) {}

    /** 本地坐标下的矩形（右/下为开边界）。 */
    private record LocalRect(double left, double top, double right, double bottom) {}

    /** surface 像素坐标下的矩形（左上原点，右/下为开边界）；允许零轴。 */
    private record PixelRect(double left, double top, double right, double bottom) {}

    private record ViewSnapshot(View view, Matrix global, boolean unsupported, boolean visible,
                                float alpha, float transitionAlpha, int width, int height, boolean clipToOwnBounds,
                                LocalRect clipLocal, LocalRect paddedLocal) {}

    private record Ancestry(List<ViewSnapshot> nodes, int rootIndex, boolean unsupportedTransform,
                            boolean drawn) {}

    /**
     * 去重键：只装不可变值数据（View 身份 + 逐节点语义 + 像素包围盒），绝不持有草稿数组或矩阵对象。
     * 等待/解绑消息用空 {@code nodes} 哨兵，而真实测量的祖先链至少含 viewport 一个节点，二者永不相等。
     */
    private record MeasureKey(long viewGeneration, long windowGeneration, Status status,
                              long densityBits, PixelRect rootPixel, PixelRect viewportPixel, List<NodeKey> nodes) {

        static MeasureKey withoutGeometry(long viewGeneration, long windowGeneration, Status status) {
            return new MeasureKey(viewGeneration, windowGeneration, status, 0L, null, null, List.of());
        }
    }

    /** 逐节点语义：View 身份、真实可见性/透明度、尺寸、裁剪开关与全局平移原点。 */
    private record NodeKey(View view, boolean visible, float alpha, float transitionAlpha, int width,
                           int height, boolean clipToOwnBounds, LocalRect clipLocal, LocalRect paddedLocal,
                           double globalOriginX, double globalOriginY) {}

    /**
     * UI owner 独占的草稿区：可跨帧复用，但绝不进入 payload。所有字段在使用前都被当帧测量重写。
     */
    private static final class Scratch {

        private final float[] src = new float[8];
        private final float[] dst = new float[8];
        private final Rect clip = new Rect();

        /** root 原始 surface 像素四角包围盒；作为像素载荷直接进入 Geometry，不再折算成 logical。 */
        private PixelRect rootPixel;
        /** viewport 可见区域原始 surface 像素四角包围盒；作为 present 的原生测量原样交给布局消费。 */
        private PixelRect viewportPixel;
        private double density;

        private double viewLeft;
        private double viewTop;
        private double viewWidth;
        private double viewHeight;

        private void reset() {
            rootPixel = null;
            viewportPixel = null;
            density = 0.0D;
            viewLeft = 0.0D;
            viewTop = 0.0D;
            viewWidth = 0.0D;
            viewHeight = 0.0D;
        }

        /**
         * R19.1b2 第2/3点：只对 viewport 做一次 logical 转换，原点取 root 左上<strong>像素</strong>；root 自身
         * 绝不做「先除 density 再乘 density」的往返，其原始像素四边界原样保留给 Geometry 的 rootSurfaceRect
         * 与 ScaleTransform 的 pixelOrigin。
         */
        private void computeLogical() {
            viewLeft = (viewportPixel.left() - rootPixel.left()) / density;
            viewTop = (viewportPixel.top() - rootPixel.top()) / density;
            viewWidth = (viewportPixel.right() - viewportPixel.left()) / density;
            viewHeight = (viewportPixel.bottom() - viewportPixel.top()) / density;
        }

        /**
         * 预先确认进入载荷的数值合法性：root 侧核原始像素四边界的有限性与有序性（四角 AABB 已保证
         * width/height 非负），viewport 侧核相对 logical 组件、派生边界的有限性与两轴非负，避免用非法输入
         * 触发构造异常。语义按真实单位核，不额外 normalize、不加 epsilon。
         */
        private boolean logicalFinite() {
            return Double.isFinite(rootPixel.left()) && Double.isFinite(rootPixel.top()) && Double.isFinite(rootPixel.right()) && Double.isFinite(rootPixel.bottom()) && rootPixel.right() >= rootPixel.left() && rootPixel.bottom() >= rootPixel.top() && Double.isFinite(viewLeft) && Double.isFinite(viewTop) && Double.isFinite(viewWidth) && Double.isFinite(viewHeight) && Double.isFinite(viewLeft + viewWidth) && Double.isFinite(viewTop + viewHeight) && viewWidth >= 0.0D && viewHeight >= 0.0D;
        }

        /** root 实际像素轴必须严格 &gt; 0；任一轴 &lt;= 0 时不可 READY（等同原 root 实际轴判据）。 */
        private boolean rootPositive() {
            return rootPixel.right() > rootPixel.left() && rootPixel.bottom() > rootPixel.top();
        }
    }

    /**
     * attach 监听：只负责「两侧真实 attach 后安装 preDraw」。允许跨真实 detach/reattach 存活，
     * 但总是读取本桥 UI owner 当前持有的最新 attach 身份；显式 detach 后 {@code retired} 使其立即失效。
     */
    private final class AttachObserver implements View.OnAttachStateChangeListener {

        private boolean retired;

        private void retire() {
            retired = true;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            Core.checkUiThread();
            if (retired || invalid) {
                return;
            }
            final ViewAttachment current = attachment;
            if (current == null) {
                return;
            }
            if (view != current.root() && view != current.viewport()) {
                return;
            }
            if (!current.root().isAttachedToWindow() || !current.viewport().isAttachedToWindow()) {
                return;
            }
            installPreDraw(current);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (retired || invalid) {
                return;
            }
            handlePhysicalDetach(view);
        }
    }

    /**
     * 真实 preDraw 闭包：捕获 observer、本次 viewGeneration 与 root/viewport/surface 身份。每次读 View 前
     * 先校验 bridge 有效性、attachment 身份与全部代数，绝不把旧回调转发到新 root；所有分支都返回 true。
     */
    private final class PreDraw implements ViewTreeObserver.OnPreDrawListener {

        private final ViewTreeObserver observer;
        private final long expectedToken;
        private final View root;
        private final View viewport;
        private final View surface;

        private PreDraw(ViewTreeObserver observer, long expectedToken, View root, View viewport,
                        View surface) {
            this.observer = observer;
            this.expectedToken = expectedToken;
            this.root = root;
            this.viewport = viewport;
            this.surface = surface;
        }

        private View observedRoot() {
            return root;
        }

        @Override
        public boolean onPreDraw() {
            if (invalid || insideSink) {
                return true; // 永久失效 / sink 重入中的旧闭包：立即失效，绝不递归发布。
            }
            final ViewAttachment current = attachment;
            if (current == null || current.issuedToken() != expectedToken || current.root() != root || current.viewport() != viewport) {
                return true;
            }
            if (observer != observedObserver || this != observedPreDraw) {
                return true;
            }
            Core.checkUiThread();
            evaluate(expectedToken, surface);
            return true;
        }
    }
}
