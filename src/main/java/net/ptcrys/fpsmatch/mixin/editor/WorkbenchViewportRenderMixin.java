package net.ptcrys.fpsmatch.mixin.editor;

import net.ptcrys.fpsmatch.editor.common.client.workbench.StudioScreen;
import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchViewportBatches;
import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchWorldViewport;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Client-only seams scoped to the hosted workbench or its synchronous viewport render. */
public final class WorkbenchViewportRenderMixin {

    private WorkbenchViewportRenderMixin() {}

    @Mixin(Minecraft.class)
    public abstract static class MinecraftTarget {

        @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
        private void fpsmatch$workbenchTarget(CallbackInfoReturnable<RenderTarget> callback) {
            RenderTarget target = WorkbenchWorldViewport.scopedTarget();
            if (target != null) callback.setReturnValue(target);
        }

        @Inject(method = "useShaderTransparency", at = @At("HEAD"), cancellable = true)
        private static void fpsmatch$workbenchTransparency(CallbackInfoReturnable<Boolean> callback) {
            // Full-window fabulous post targets cannot be borrowed by a smaller viewport.
            if (WorkbenchWorldViewport.scopedTarget() != null) callback.setReturnValue(false);
        }
    }

    @Mixin(GameRenderer.class)
    public abstract static class GameCamera {

        @Shadow
        private float renderDistance;

        @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
        private boolean fpsmatch$centralWorldOnly(boolean renderLevel, float partialTick,
                                                  long finishTimeNano, boolean originalRenderLevel) {
            if (!WorkbenchWorldViewport.ownsMainRender()) return renderLevel;
            Minecraft minecraft = Minecraft.getInstance();
            renderDistance = minecraft.options.getEffectiveRenderDistance() * 16.0F;
            // Keep the ordinary camera's read-only player pose current for sound and other
            // consumers. The editor owns a separate Camera and never moves this entity.
            if (renderLevel && minecraft.level != null && minecraft.player != null) {
                minecraft.gameRenderer.getMainCamera().setup(minecraft.level,
                        minecraft.getCameraEntity() == null ? minecraft.player : minecraft.getCameraEntity(),
                        !minecraft.options.getCameraType().isFirstPerson(),
                        minecraft.options.getCameraType().isMirrored(), partialTick);
            }
            // Native loading/clearLevel explicitly calls runTick(false). Its world may still
            // be visible here after other mods have released their native world resources.
            // Consume the original argument in this one seam, before suppressing vanilla;
            // separate HEAD injectors cannot rely on their ordering to retain that argument.
            WorkbenchWorldViewport.renderHostedWorld(partialTick, finishTimeNano, renderLevel);
            // The normal main-loop world, player HUD and screen-sized post effects are omitted.
            // The GUI branch still draws the persistent workbench shell and its central texture.
            return false;
        }

        @Redirect(method = "render",
                  at = @At(value = "INVOKE",
                           target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lnet/minecraft/client/gui/GuiGraphics;)V"))
        private void fpsmatch$workbenchToasts(ToastComponent toasts, GuiGraphics graphics) {
            // Vanilla toasts use full-window coordinates and gameplay instructions. Preserve
            // their queue and options while a full-screen Studio page owns the screen.
            if (!WorkbenchWorldViewport.ownsMainRender() && !(Minecraft.getInstance().screen instanceof StudioScreen)) toasts.render(graphics);
        }

        @Inject(method = "getMainCamera", at = @At("HEAD"), cancellable = true)
        private void fpsmatch$workbenchCamera(CallbackInfoReturnable<Camera> callback) {
            Camera camera = WorkbenchWorldViewport.scopedCamera();
            if (camera != null) callback.setReturnValue(camera);
        }
    }

    @Mixin(RenderSystem.class)
    public interface RenderStateAccess {

        @Accessor("shaderLightDirections")
        static Vector3f[] fpsmatch$shaderLights() {
            throw new AssertionError("mixin not applied");
        }

        @Accessor("shaderGameTime")
        static void fpsmatch$shaderGameTime(float time) {
            throw new AssertionError("mixin not applied");
        }
    }

    @Mixin(PoseStack.class)
    public interface PoseStackAccess {

        @Accessor("poseStack")
        Deque<PoseStack.Pose> fpsmatch$poses();
    }

    @Mixin(BlendMode.class)
    public interface BlendStateAccess {

        @Accessor("lastApplied")
        static BlendMode fpsmatch$lastApplied() {
            throw new AssertionError("mixin not applied");
        }

        @Accessor("lastApplied")
        static void fpsmatch$lastApplied(BlendMode mode) {
            throw new AssertionError("mixin not applied");
        }
    }

    @Mixin(MultiBufferSource.BufferSource.class)
    public interface BufferSourceAccess {

        @Accessor("builder")
        BufferBuilder fpsmatch$builder();

        @Accessor("fixedBuffers")
        Map<RenderType, BufferBuilder> fpsmatch$fixedBuffers();

        @Accessor("startedBuffers")
        Set<BufferBuilder> fpsmatch$startedBuffers();

        @Accessor("lastState")
        Optional<RenderType> fpsmatch$lastState();

        @Accessor("lastState")
        void fpsmatch$lastState(Optional<RenderType> state);
    }

    @Mixin(MultiBufferSource.BufferSource.class)
    public abstract static class BufferSourceTracking {

        @Inject(method = "getBuffer", at = @At("HEAD"))
        private void fpsmatch$trackViewportBuffer(RenderType type, CallbackInfoReturnable<VertexConsumer> callback) {
            WorkbenchViewportBatches.bufferRequested((MultiBufferSource.BufferSource) (Object) this, type);
        }
    }

    @Mixin(OutlineBufferSource.class)
    public interface OutlineSourceAccess {

        @Accessor("outlineBufferSource")
        MultiBufferSource.BufferSource fpsmatch$outlineSource();
    }

    @Mixin(BufferBuilder.class)
    public interface BufferBuilderAccess {

        @Invoker("reset")
        void fpsmatch$reset();

        @Accessor("renderedBufferCount")
        int fpsmatch$renderedBufferCount();

        @Accessor("buffer")
        java.nio.ByteBuffer fpsmatch$buffer();
    }
}
