package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.client.viewport.FramebufferRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.LogicalRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.FrameMatrix;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.MatrixMath;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.Ray3;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.Vector3;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.Vector4;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldViewportDepth;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportLevelAccess;

import net.minecraft.ReportedException;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One real, live-world viewport. Owns only a local camera and a color/depth framebuffer.
 * Never changes the player, camera entity, world contents, project or world binding.
 *
 * <p>
 * Logical bounds are in the caller's already density-scaled GuiGraphics coordinate space.
 * The physical rectangle, projection and input transformation all come from one snapshot.
 * Vanilla's current client chunks are shared; this does not request remote chunks. The local
 * pass uses ordinary transparency, without the host's full-window fabulous/outline post chains.
 * Shader-pack and alternative chunk-renderer compatibility require real host verification.
 */
@Mod.EventBusSubscriber(modid = "fpsmatch", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorkbenchWorldViewport implements AutoCloseable {

    private static WorkbenchWorldViewport attached;
    private static WorkbenchWorldViewport rendering;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final UUID producer = UUID.randomUUID();
    private OwnedTarget target;
    private EditorCamera renderingCamera;
    private ClientLevel level;
    private ClientPacketListener connection;
    private LocalPlayer player;
    private ShaderInstance resourceShader;
    private RenderTarget hostTarget;
    private ViewportLayout layout;
    private Frame frame;
    private Frame pendingFrame;
    private Supplier<Scene> sceneSupplier = () -> null;
    private Scene pendingScene, renderingScene;
    private List<WorkbenchWorldPointRenderer.Instance> renderingPoints = List.of();
    private List<WorkbenchWorldPointRenderer.Instance> renderingPreviews = List.of();
    private List<WorkbenchWorldPointRenderer.Instance> drawnPoints = List.of();
    private Matrix4f renderingView, renderingProjection;
    private boolean sceneRendered;
    private final WorkbenchWorldPointRenderer pointRenderer = new WorkbenchWorldPointRenderer();
    private Screen hostScreen;
    private Vec3 position;
    private float yaw;
    private float pitch;
    private float density;
    private long targetGeneration;
    private long cameraGeneration;
    private long layoutGeneration;
    private long frameSequence;
    private long renderSequence;
    private int capturedButton = -1;
    private final Set<Integer> heldMovementKeys = new HashSet<>();
    private boolean closed;
    private boolean worldEnabled;
    private boolean worldPreparing;
    private String failure;

    public WorkbenchWorldViewport() {}

    /** Read-only scene captured before the world pass; publication follows the color composite. */
    public interface Scene {

        List<WorkbenchWorldPointRenderer.Instance> points();

        default List<WorkbenchWorldPointRenderer.Instance> previews() {
            return List.of();
        }

        boolean current();

        void displayed(WorkbenchWorldViewport viewport, Frame frame);
    }

    public void setSceneSupplier(Supplier<Scene> supplier) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("viewport closed");
        sceneSupplier = java.util.Objects.requireNonNull(supplier);
        frame = null;
        pendingFrame = null;
        pendingScene = null;
    }

    /** Inert in ordinary gameplay: only the synchronous, privately owned world pass is eligible. */
    @SubscribeEvent
    public static void renderWorldPoints(RenderLevelStageEvent event) {
        if (!RenderSystem.isOnRenderThread() || rendering == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        WorkbenchWorldViewport owner = rendering;
        if (event.getCamera() != owner.renderingCamera || event.getLevelRenderer() != owner.minecraft.levelRenderer || owner.sceneRendered || owner.renderingScene == null || !owner.renderingScene.current()) return;
        try (WorkbenchViewportGraphicsState ignored = new WorkbenchViewportGraphicsState()) {
            owner.target.bindWrite(true);
            RenderSystem.disableScissor();
            RenderSystem.setProjectionMatrix(owner.renderingProjection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.disablePolygonOffset();
            RenderSystem.disableColorLogicOp();
            GL11.glDepthRange(0, 1);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            RenderSystem.enableCull();
            owner.drawnPoints = owner.pointRenderer.draw(owner.renderingPoints, owner.renderingCamera.getPosition(),
                    owner.renderingView, event.getFrustum());
            owner.pointRenderer.preview(owner.renderingPreviews, owner.renderingCamera.getPosition(), owner.renderingView);
        }
        owner.sceneRendered = true;
    }

    /** Immutable viewing identity; deliberately contains no persistent WorldId or write authority. */
    public record Frame(UUID producer, long frameId, long renderSequence, long targetGeneration, long cameraGeneration,
                        long clientGameTime, String dimension, ViewportLayout layout,
                        Position cameraPosition, FrameMatrix view, FrameMatrix projection,
                        FrameMatrix inverseView, FrameMatrix inverseProjection) {}

    /** A miss is retained as a miss; callers must not interpret an empty sky ray as a placement hit. */
    public record Pick(Frame frame, Ray3 ray, BlockHitResult hit) {

        public boolean hasBlockHit() {
            return hit.getType() == HitResult.Type.BLOCK;
        }
    }

    /** Only the render thread inside the synchronous renderLevel call can observe the override. */
    public static RenderTarget scopedTarget() {
        return RenderSystem.isOnRenderThread() && rendering != null ? rendering.target : null;
    }

    public static Camera scopedCamera() {
        return RenderSystem.isOnRenderThread() && rendering != null ? rendering.renderingCamera : null;
    }

    /** Explicitly owns the normal main-loop world pass only while this exact Screen is current. */
    public void attach(Screen screen) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("viewport closed");
        if (screen == null) throw new IllegalArgumentException("host screen missing");
        if (attached != null && attached != this) {
            attached.cancelInput();
            attached.frame = null;
            attached.pendingFrame = null;
            attached.pendingScene = null;
        }
        hostScreen = screen;
        attached = this;
    }

    /** Loading, overlays and disconnect revoke the old frame before any input can be routed. */
    public void setWorldEnabled(boolean enabled) {
        RenderSystem.assertOnRenderThread();
        if (worldEnabled == enabled || closed) return;
        worldEnabled = enabled;
        frame = null;
        pendingFrame = null;
        pendingScene = null;
        cancelInput();
        if (!enabled && !worldPreparing) releaseTarget();
        else synchronizeWorld();
    }

    /** Drives the local chunk compilation/upload pass under the central loading surface. */
    public void setWorldPreparing(boolean preparing) {
        RenderSystem.assertOnRenderThread();
        if (worldPreparing == preparing || closed) return;
        if (!preparing) {
            synchronizeWorld();
            followPreparingPlayer();
        }
        worldPreparing = preparing;
        frame = null;
        pendingFrame = null;
        pendingScene = null;
        cancelInput();
        if (!preparing && !worldEnabled) releaseTarget();
        else synchronizeWorld();
    }

    /** Revoke pixels and input after a host resize while retaining this world's editor camera. */
    public void invalidateLayout() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        cancelInput();
        layout = null;
        failure = null;
        releaseTarget();
    }

    /** Called by Screen.init/rebuild, before the next main-loop world pass. No world is drawn here. */
    public void configure(ControlTree.Bounds bounds, float density) {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        if (bounds == null || !Float.isFinite(density) || density <= 0) {
            frame = null;
            pendingFrame = null;
            pendingScene = null;
            layout = null;
            cancelInput();
            releaseTarget();
            return;
        }
        var window = minecraft.getWindow();
        if (window.getWidth() <= 0 || window.getHeight() <= 0) {
            releaseTarget();
            return;
        }
        double scale = density * window.getGuiScale();
        ScaleTransform transform = new ScaleTransform(scale, scale, 0, 0, window.getWidth(), window.getHeight());
        LogicalRect logical = new LogicalRect(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        if (layout == null || !layout.logicalRect().equals(logical) || !layout.scaleTransform().equals(transform)) {
            layoutGeneration = Math.incrementExact(layoutGeneration);
            layout = new ViewportLayout(layoutGeneration, logical, transform);
            this.density = density;
            frame = null;
            pendingFrame = null;
            pendingScene = null;
            cancelInput();
            failure = null;
        }
        if (layout.framebufferRect().isEmpty()) releaseTarget();
    }

    /** Inert for normal gameplay and all unrelated Screens. Also suppresses the player's HUD. */
    public static boolean ownsMainRender() {
        return RenderSystem.isOnRenderThread() && attached != null && !attached.closed && attached.hostScreen != null && attached.minecraft.screen == attached.hostScreen;
    }

    /** Invoked once at GameRenderer.render HEAD. The original full-screen world branch is skipped. */
    public static void renderHostedWorld(float partialTick, long finishTimeNano, boolean renderLevel) {
        if (!ownsMainRender()) return;
        WorkbenchWorldViewport owner = attached;
        owner.renderSequence = Math.incrementExact(owner.renderSequence);
        owner.frame = null;
        owner.pendingFrame = null;
        owner.pendingScene = null;
        if (!renderLevel) {
            owner.cancelInput();
            return;
        }
        if (!owner.minecraft.noRender && (owner.worldEnabled || owner.worldPreparing) && Float.isFinite(partialTick)) owner.drawWorld(partialTick, finishTimeNano);
    }

    private void drawWorld(float partialTick, long finishTimeNano) {
        drawnPoints = List.of();
        synchronizeWorld();
        if (level == null || layout == null) return;
        if (worldPreparing) followPreparingPlayer();
        var window = minecraft.getWindow();
        if (layout.scaleTransform().framebufferWidth() != window.getWidth() || layout.scaleTransform().framebufferHeight() != window.getHeight() || layout.scaleTransform().xScale() != density * window.getGuiScale()) return;
        FramebufferRect pixels = layout.framebufferRect();
        if (pixels.isEmpty()) return;
        RenderTarget currentHost = minecraft.getMainRenderTarget();
        ShaderInstance currentShader = GameRenderer.getPositionShader();
        if (resourceShader != currentShader || hostTarget != currentHost) {
            releaseTarget();
            resourceShader = currentShader;
            hostTarget = currentHost;
            failure = null;
        }
        if (failure != null || currentShader == null) return;
        Frame completed;
        Scene completedScene;
        try (WorkbenchViewportGraphicsState ignored = new WorkbenchViewportGraphicsState()) {
            synchronizeTarget(pixels);
            // A new camera per pass keeps vanilla's asynchronous chunk traversal from observing
            // a camera object subsequently moved by another viewport frame.
            EditorCamera camera = new EditorCamera();
            // Detached means vanilla third-person rendering, which includes the camera entity.
            // Keep that model hidden at the initial eye position; place owns the editor pose.
            camera.setup(level, player, false, false, partialTick);
            camera.place(position, yaw, pitch);
            PoseStack viewStack = new PoseStack();
            viewStack.mulPose(Axis.XP.rotationDegrees(pitch));
            viewStack.mulPose(Axis.YP.rotationDegrees(yaw + 180.0F));
            float far = Math.max(64, minecraft.options.getEffectiveRenderDistance() * 16.0F * 4.0F);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70),
                    (float) pixels.width() / pixels.height(), 0.05F, far);
            FrameMatrix view = matrix(viewStack.last().pose());
            FrameMatrix projectionValues = matrix(projection);
            FrameMatrix inverseView = MatrixMath.inverse(view).orElseThrow(() -> new IllegalStateException("singular view"));
            FrameMatrix inverseProjection = MatrixMath.inverse(projectionValues).orElseThrow(() -> new IllegalStateException("singular projection"));
            RenderSystem.disableScissor();
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            GL11.glDepthRange(0, 1);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glCullFace(GL11.GL_BACK);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(viewStack.last().normal()).invert());
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            try {
                modelView.setIdentity();
                RenderSystem.applyModelViewMatrix();
                completedScene = worldEnabled && !worldPreparing ? sceneSupplier.get() : null;
                if (completedScene != null && !completedScene.current()) completedScene = null;
                renderWorld(camera, viewStack, projection, partialTick, finishTimeNano, completedScene);
            } finally {
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
            }
            // Preparation must advance chunk compilation without exposing an editable frame.
            if (worldPreparing || !worldEnabled) return;
            if (completedScene != null && (!sceneRendered || !completedScene.current())) return;
            completed = new Frame(producer, Math.incrementExact(frameSequence), renderSequence, targetGeneration, cameraGeneration,
                    level.getGameTime(), level.dimension().location().toString(), layout,
                    new Position(position.x, position.y, position.z), view, projectionValues, inverseView, inverseProjection);
        } catch (RuntimeException exception) {
            failure = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
            cancelInput();
            try {
                releaseTarget();
            } catch (RuntimeException cleanup) {
                if (exception != cleanup) exception.addSuppressed(cleanup);
            }
            // Synchronous chunk tessellation shares the fixed builder pack and has no rollback
            // contract. A partial compile must retain vanilla's fatal path; swallowing it would
            // leave a globally building buffer that neither the viewport nor gameplay can use.
            // Never discard that pack: earlier RenderedBuffers may still be queued for upload.
            if (exception instanceof ReportedException || WorkbenchViewportBatches.hasUnfinishedChunkBuild(minecraft.renderBuffers())) throw exception;
            LogUtils.getLogger().error("Workbench local world render failed; frame invalidated", exception);
            return;
        }
        // Publication follows both rendering and successful state restoration.
        frameSequence = completed.frameId();
        pendingFrame = completed;
        pendingScene = completedScene;
    }

    /** UI phase: composite only the world pass produced earlier in this exact main-render call. */
    public void render(GuiGraphics graphics, ControlTree.Bounds bounds, float density, float partialTick) {
        RenderSystem.assertOnRenderThread();
        configure(bounds, density);
        frame = null;
        Frame completed = pendingFrame;
        Scene scene = pendingScene;
        if (!contextCurrent() || completed == null || completed.renderSequence() != renderSequence || completed.layout() != layout || completed.cameraGeneration() != cameraGeneration || completed.targetGeneration() != targetGeneration || scene != null && !scene.current()) return;
        pendingFrame = null;
        pendingScene = null;
        graphics.flush();
        try (WorkbenchViewportGraphicsState state = new WorkbenchViewportGraphicsState()) {
            FramebufferRect pixels = layout.framebufferRect();
            RenderSystem.disableScissor();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, state.drawFramebuffer());
            int bottom = layout.scaleTransform().framebufferHeight() - pixels.y() - pixels.height();
            GL30.glBlitFramebuffer(0, 0, pixels.width(), pixels.height(), pixels.x(), bottom,
                    pixels.x() + pixels.width(), bottom + pixels.height(), GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }
        if (!contextCurrent() || scene != null && !scene.current()) return;
        frame = completed;
        if (scene != null) {
            try {
                scene.displayed(this, completed);
            } catch (RuntimeException | Error failure) {
                frame = null;
                throw failure;
            }
        }
    }

    private void renderWorld(EditorCamera camera, PoseStack view, Matrix4f projection, float partialTick, long finishTimeNano, Scene scene) {
        if (rendering != null) throw new IllegalStateException("nested workbench world render");
        var entities = minecraft.getEntityRenderDispatcher();
        var blocks = minecraft.getBlockEntityRenderDispatcher();
        Camera oldEntityCamera = entities.camera;
        var oldCrosshair = entities.crosshairPickEntity;
        var oldOrientation = entities.cameraOrientation();
        Camera oldBlockCamera = blocks.camera;
        HitResult oldBlockHit = blocks.cameraHitResult;
        var oldBlockLevel = blocks.level;
        HitResult oldHit = minecraft.hitResult;
        var oldPickedEntity = minecraft.crosshairPickEntity;
        renderingCamera = camera;
        renderingScene = scene;
        renderingPoints = scene == null ? List.of() : List.copyOf(scene.points());
        renderingPreviews = scene == null ? List.of() : List.copyOf(scene.previews());
        renderingView = new Matrix4f(view.last().pose());
        renderingProjection = new Matrix4f(projection);
        sceneRendered = false;
        try (LevelScope ignored = new LevelScope((WorkbenchViewportLevelAccess) minecraft.levelRenderer);
                WorkbenchViewportBatches batches = new WorkbenchViewportBatches(minecraft.renderBuffers())) {
            rendering = this;
            // The game's aim belongs to its own camera and must not appear in this pass.
            minecraft.hitResult = null;
            minecraft.crosshairPickEntity = null;
            minecraft.levelRenderer.prepareCullFrustum(view, position, projection);
            minecraft.getProfiler().push("workbench_world");
            try {
                minecraft.gameRenderer.lightTexture().updateLightTexture(partialTick);
                minecraft.levelRenderer.renderLevel(view, partialTick, finishTimeNano, false, camera,
                        minecraft.gameRenderer, minecraft.gameRenderer.lightTexture(), projection);
                net.minecraftforge.client.ForgeHooksClient.dispatchRenderStage(
                        net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL,
                        minecraft.levelRenderer, view, projection, minecraft.levelRenderer.getTicks(), camera,
                        minecraft.levelRenderer.getFrustum());
                // Stage listeners can leave deferred vertices after LevelRenderer returns.
                // Flush while the local target override, post-target isolation and matrices
                // remain in scope. A failing draw instead discards batches without drawing.
                target.bindWrite(true);
                RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
                RenderSystem.getModelViewStack().setIdentity();
                RenderSystem.applyModelViewMatrix();
                batches.flushWorld();
            } finally {
                minecraft.getProfiler().pop();
            }
        } finally {
            rendering = null;
            renderingCamera = null;
            renderingScene = null;
            renderingPoints = List.of();
            renderingPreviews = List.of();
            renderingView = null;
            renderingProjection = null;
            minecraft.hitResult = oldHit;
            minecraft.crosshairPickEntity = oldPickedEntity;
            entities.camera = oldEntityCamera;
            entities.crosshairPickEntity = oldCrosshair;
            entities.overrideCameraOrientation(oldOrientation);
            blocks.camera = oldBlockCamera;
            blocks.cameraHitResult = oldBlockHit;
            blocks.level = oldBlockLevel;
        }
    }

    public boolean available() {
        return frame != null && contextCurrent() && frame.cameraGeneration() == cameraGeneration && frame.targetGeneration() == targetGeneration && frame.renderSequence() == renderSequence && frame.clientGameTime() == level.getGameTime();
    }

    public Optional<Frame> frame() {
        return available() ? Optional.of(frame) : Optional.empty();
    }

    public Optional<String> failure() {
        return Optional.ofNullable(failure);
    }

    /** Rechecks the captured token before a caller consumes a pick after another callback. */
    public boolean isCurrent(Frame captured) {
        return available() && frame == captured;
    }

    /**
     * Select actual rasterized coverage without reconstructing GPU depth on the CPU.
     * Queries never write either framebuffer attachment. Equal encoded depths retain the
     * last point in draw order; this is not a world-object identity buffer.
     */
    Optional<WorkbenchWorldPointRenderer.Instance> pointAtPixel(Frame captured, double x, double y) {
        RenderSystem.assertOnRenderThread();
        if (!isCurrent(captured)) return Optional.empty();
        var pixel = WorldViewportDepth.pixel(captured.layout(), x, y);
        if (pixel.isEmpty()) return Optional.empty();
        OwnedTarget capturedTarget = target;
        var instances = drawnPoints;
        Optional<WorkbenchWorldPointRenderer.Instance> result;
        try (WorkbenchViewportGraphicsState ignored = new WorkbenchViewportGraphicsState()) {
            capturedTarget.bindWrite(true);
            RenderSystem.enableScissor(pixel.get().readX(), pixel.get().readY(), 1, 1);
            RenderSystem.setProjectionMatrix(nativeMatrix(captured.projection()), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disablePolygonOffset();
            RenderSystem.disableColorLogicOp();
            GL11.glDepthRange(0, 1);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            RenderSystem.enableCull();
            Position camera = captured.cameraPosition();
            result = pointRenderer.pick(instances, new Vec3(camera.x(), camera.y(), camera.z()), nativeMatrix(captured.view()));
        } catch (RuntimeException failure) {
            frame = null;
            pendingFrame = null;
            pendingScene = null;
            LogUtils.getLogger().error("Workbench point coverage query failed; frame invalidated", failure);
            return Optional.empty();
        }
        return target == capturedTarget && drawnPoints == instances && isCurrent(captured) ? result : Optional.empty();
    }

    /** Selection rays pass through the center of exactly the pixel read by depthMatches. */
    public Optional<Ray3> screenRay(Frame captured, double logicalX, double logicalY) {
        if (!isCurrent(captured)) return Optional.empty();
        return WorldViewportDepth.pixel(captured.layout(), logicalX, logicalY)
                .flatMap(pixel -> ray(captured, pixel.framebufferCenterX(), pixel.framebufferCenterY()));
    }

    /** Conservative visibility check; quantized depth is not an object identity buffer. */
    public boolean depthMatches(Frame captured, double logicalX, double logicalY, Position worldHit) {
        RenderSystem.assertOnRenderThread();
        if (!isCurrent(captured) || worldHit == null) return false;
        var pixel = WorldViewportDepth.pixel(captured.layout(), logicalX, logicalY);
        var expected = WorkbenchViewportDepthProjection.projectDepth(captured.view(), captured.projection(), captured.cameraPosition(), worldHit);
        if (pixel.isEmpty() || expected.isEmpty()) return false;
        OwnedTarget capturedTarget = target;
        var sample = WorkbenchViewportDepthReader.read(capturedTarget.frameBufferId, pixel.get().readX(), pixel.get().readY());
        return target == capturedTarget && isCurrent(captured) && sample.isPresent() && WorldViewportDepth.matches(expected.getAsDouble(), sample.get().depth(),
                sample.get().bits(), sample.get().floating());
    }

    public Optional<Pick> pick(double logicalX, double logicalY) {
        if (!available() || !Double.isFinite(logicalX) || !Double.isFinite(logicalY)) return Optional.empty();
        Frame captured = frame;
        var scale = captured.layout().scaleTransform();
        double px = scale.toFramebufferX(logicalX), py = scale.toFramebufferY(logicalY);
        Optional<Ray3> candidate = ray(captured, px, py);
        if (candidate.isEmpty()) return Optional.empty();
        Ray3 ray = candidate.get();
        Vec3 start = new Vec3(ray.origin().x(), ray.origin().y(), ray.origin().z());
        Vec3 direction = new Vec3(ray.direction().x(), ray.direction().y(), ray.direction().z());
        var limit = WorkbenchLoadedChunkRay.trace(start.x, start.z, direction.x, direction.z,
                Math.min(ray.maxDistance(), 256.0), (x, z) -> level.getChunkSource().hasChunk(x, z));
        double distance = limit.unloaded() ? Math.nextDown(limit.distance()) : limit.distance();
        if (distance <= 0) return Optional.empty();
        Vec3 end = start.add(direction.scale(distance));
        if (limit.unloaded()) {
            // Addition at world-coordinate magnitudes can round nextDown(t) back onto the
            // missing chunk boundary. Keep the endpoint on the loaded side by one ULP.
            end = new Vec3(Math.nextAfter(end.x, start.x), Math.nextAfter(end.y, start.y), Math.nextAfter(end.z, start.z));
        }
        BlockHitResult hit = level.clip(new ClipContext(start, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && !level.hasChunkAt(hit.getBlockPos())) return Optional.empty();
        Ray3 visibleRay = new Ray3(ray.frameId(), ray.origin(), ray.direction(), distance);
        return isCurrent(captured) ? Optional.of(new Pick(captured, visibleRay, hit)) : Optional.empty();
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (!minecraft.isWindowActive() || !contains(x, y) || button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        capturedButton = button;
        return true;
    }

    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (capturedButton != button || !contextCurrent() || !minecraft.isWindowActive()) {
            cancelInput();
            return false;
        }
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return true;
        yaw = Mth.wrapDegrees(yaw + (float) dx * 0.25F);
        pitch = Mth.clamp(pitch + (float) dy * 0.25F, -89.5F, 89.5F);
        cameraChanged();
        return true;
    }

    public boolean mouseReleased(double x, double y, int button) {
        if (capturedButton != button) return false;
        // Releasing RMB ends capture, while a still-held W/A/S/D may be used by a later
        // RMB press. Actual key releases and ownership cancellation clear that key state.
        capturedButton = -1;
        return true;
    }

    public boolean mouseScrolled(double x, double y, double delta) {
        return false;
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!contextCurrent() || !minecraft.isWindowActive()) {
            cancelInput();
            return false;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && capturedButton >= 0) {
            cancelInput();
            return true;
        }
        if (!movementKey(key)) return false;
        // Screen routes these only after text/modal owners. Remember a key pressed before
        // RMB without consuming it; movement still requires the viewport's pointer capture.
        heldMovementKeys.add(key);
        return capturedButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    /** Release routing must run even after a modal or target change revoked input ownership. */
    public boolean keyReleased(int key, int scan, int modifiers) {
        return heldMovementKeys.remove(key);
    }

    /** Screen calls this when any modal, drawer or other input owner covers the viewport. */
    public void cancelInput() {
        capturedButton = -1;
        heldMovementKeys.clear();
    }

    public void tick() {
        if (closed) return;
        synchronizeWorld();
        if (!contextCurrent() || !minecraft.isWindowActive()) {
            cancelInput();
            frame = null;
            return;
        }
        if (capturedButton != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        double forward = pressed(GLFW.GLFW_KEY_W) - pressed(GLFW.GLFW_KEY_S);
        double side = pressed(GLFW.GLFW_KEY_D) - pressed(GLFW.GLFW_KEY_A);
        if (forward == 0 && side == 0) return;
        double radians = Math.toRadians(yaw);
        Vec3 movement = forward().scale(forward).add(-Math.cos(radians) * side, 0, -Math.sin(radians) * side);
        position = position.add(movement.normalize().scale(0.5));
        cameraChanged();
    }

    private int pressed(int key) {
        return heldMovementKeys.contains(key) ? 1 : 0;
    }

    private static boolean movementKey(int key) {
        return key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_D;
    }

    private Vec3 forward() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    private void cameraChanged() {
        cameraGeneration = Math.incrementExact(cameraGeneration);
        frame = null;
        pendingFrame = null;
        pendingScene = null;
    }

    private void followPreparingPlayer() {
        if (player == null) return;
        // Spawn packets may arrive after identity becomes available, including in the tick
        // that finishes receiving. Preserve the final player pose as the editor's initial pose.
        Vec3 spawnPosition = player.getEyePosition();
        float spawnYaw = player.getYRot(), spawnPitch = player.getXRot();
        if (!spawnPosition.equals(position) || spawnYaw != yaw || spawnPitch != pitch) {
            position = spawnPosition;
            yaw = spawnYaw;
            pitch = spawnPitch;
            cameraChanged();
        }
    }

    private boolean contains(double x, double y) {
        return available() && layout.logicalRect().contains(x, y);
    }

    private boolean contextCurrent() {
        if (closed || !worldEnabled || worldPreparing || attached != this || !ownsMainRender() || level == null || minecraft.level != level || minecraft.player != player || minecraft.getConnection() != connection || layout == null || target == null) return false;
        var window = minecraft.getWindow();
        var scale = layout.scaleTransform();
        return scale.framebufferWidth() == window.getWidth() && scale.framebufferHeight() == window.getHeight() && scale.xScale() == density * window.getGuiScale() && resourceShader == GameRenderer.getPositionShader() && hostTarget == minecraft.getMainRenderTarget();
    }

    private void synchronizeWorld() {
        if (level == minecraft.level && connection == minecraft.getConnection() && player == minecraft.player) return;
        frame = null;
        pendingFrame = null;
        pendingScene = null;
        cancelInput();
        releaseTarget();
        level = minecraft.level;
        connection = minecraft.getConnection();
        player = minecraft.player;
        failure = null;
        if (level == null || player == null || connection == null) {
            level = null;
            player = null;
            connection = null;
            position = null;
            return;
        }
        position = player.getEyePosition();
        yaw = player.getYRot();
        pitch = player.getXRot();
        cameraGeneration = Math.incrementExact(cameraGeneration);
    }

    private void synchronizeTarget(FramebufferRect pixels) {
        if (target != null && target.width == pixels.width() && target.height == pixels.height() && GL30.glIsFramebuffer(target.frameBufferId)) return;
        releaseTarget();
        if (pixels.width() > RenderSystem.maxSupportedTextureSize() || pixels.height() > RenderSystem.maxSupportedTextureSize())
            throw new IllegalStateException("viewport exceeds maximum texture size");
        targetGeneration = Math.incrementExact(targetGeneration);
        // Assign ownership before allocation: even a partially allocated target remains closable.
        target = new OwnedTarget();
        target.resize(pixels.width(), pixels.height(), Minecraft.ON_OSX);
    }

    private void releaseTarget() {
        drawnPoints = List.of();
        frame = null;
        pendingFrame = null;
        pendingScene = null;
        if (target == null) return;
        RenderSystem.assertOnRenderThread();
        if (rendering == this) throw new IllegalStateException("cannot release an active viewport target");
        targetGeneration = Math.incrementExact(targetGeneration);
        try (WorkbenchViewportGraphicsState ignored = new WorkbenchViewportGraphicsState()) {
            target.destroyBuffers();
            target = null;
        }
    }

    @Override
    public void close() {
        frame = null;
        pendingFrame = null;
        pendingScene = null;
        cancelInput();
        closed = true;
        worldEnabled = false;
        worldPreparing = false;
        if (attached == this) attached = null;
        hostScreen = null;
        sceneSupplier = () -> null;
        try {
            pointRenderer.close();
        } finally {
            releaseTarget();
        }
        level = null;
        player = null;
        connection = null;
        position = null;
        layout = null;
        resourceShader = null;
        hostTarget = null;
    }

    private static FrameMatrix matrix(Matrix4f matrix) {
        float[] floats = matrix.get(new float[16]);
        double[] values = new double[16];
        for (int i = 0; i < values.length; i++) values[i] = floats[i];
        return new FrameMatrix(values);
    }

    private static Matrix4f nativeMatrix(FrameMatrix matrix) {
        double[] values = matrix.columnMajor();
        float[] floats = new float[16];
        for (int i = 0; i < floats.length; i++) floats[i] = (float) values[i];
        return new Matrix4f().set(floats);
    }

    private static Optional<Ray3> ray(Frame frame, double px, double py) {
        FramebufferRect rect = frame.layout().framebufferRect();
        if (!rect.contains(px, py)) return Optional.empty();
        double x = 2 * (px - rect.x()) / rect.width() - 1;
        double y = 1 - 2 * (py - rect.y()) / rect.height();
        try {
            Position near = unproject(frame, x, y, -1);
            Position far = unproject(frame, x, y, 1);
            double dx = far.x() - near.x(), dy = far.y() - near.y(), dz = far.z() - near.z();
            double length = Math.hypot(Math.hypot(dx, dy), dz);
            return Optional.of(new Ray3(frame.frameId(), near, new Vector3(dx / length, dy / length, dz / length), length));
        } catch (IllegalArgumentException numericalFailure) {
            return Optional.empty();
        }
    }

    private static Position unproject(Frame frame, double x, double y, double z) {
        Vector4 projected = MatrixMath.transform(frame.inverseProjection(), new Vector4(x, y, z, 1));
        Vector4 world = MatrixMath.transform(frame.inverseView(), new Vector4(projected.x() / projected.w(),
                projected.y() / projected.w(), projected.z() / projected.w(), 1));
        return new Position(world.x() / world.w() + frame.cameraPosition().x(),
                world.y() / world.w() + frame.cameraPosition().y(), world.z() / world.w() + frame.cameraPosition().z());
    }

    private static final class OwnedTarget extends RenderTarget {

        private OwnedTarget() {
            super(true);
        }
    }

    private static final class EditorCamera extends Camera {

        private void place(Vec3 position, float yaw, float pitch) {
            setPosition(position);
            setRotation(yaw, pitch);
        }
    }

    private static final class LevelScope implements AutoCloseable {

        private final WorkbenchViewportLevelAccess access;
        private final RenderTarget entity, translucent, item, particles, weather, clouds;
        private final PostChain transparency;
        private final Frustum culling, captured;
        private final boolean capture;

        private LevelScope(WorkbenchViewportLevelAccess access) {
            this.access = access;
            entity = access.fpsmatch$entityTarget();
            translucent = access.fpsmatch$translucentTarget();
            item = access.fpsmatch$itemEntityTarget();
            particles = access.fpsmatch$particlesTarget();
            weather = access.fpsmatch$weatherTarget();
            clouds = access.fpsmatch$cloudsTarget();
            transparency = access.fpsmatch$transparencyChain();
            culling = access.fpsmatch$cullingFrustum();
            captured = access.fpsmatch$capturedFrustum();
            capture = access.fpsmatch$captureFrustum();
            access.fpsmatch$entityTarget(null);
            access.fpsmatch$translucentTarget(null);
            access.fpsmatch$itemEntityTarget(null);
            access.fpsmatch$particlesTarget(null);
            access.fpsmatch$weatherTarget(null);
            access.fpsmatch$cloudsTarget(null);
            access.fpsmatch$transparencyChain(null);
            access.fpsmatch$capturedFrustum(null);
            access.fpsmatch$captureFrustum(false);
            access.fpsmatch$needsFrustumUpdate().set(true);
        }

        @Override
        public void close() {
            access.fpsmatch$entityTarget(entity);
            access.fpsmatch$translucentTarget(translucent);
            access.fpsmatch$itemEntityTarget(item);
            access.fpsmatch$particlesTarget(particles);
            access.fpsmatch$weatherTarget(weather);
            access.fpsmatch$cloudsTarget(clouds);
            access.fpsmatch$transparencyChain(transparency);
            access.fpsmatch$cullingFrustum(culling);
            access.fpsmatch$capturedFrustum(captured);
            access.fpsmatch$captureFrustum(capture);
            access.fpsmatch$needsFrustumUpdate().set(true);
        }
    }
}
