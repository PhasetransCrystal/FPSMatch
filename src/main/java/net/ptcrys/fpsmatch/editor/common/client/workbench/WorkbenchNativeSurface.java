package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.BufferBuilderAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Independent native GUI target. No native child can clear or draw over editor chrome. */
final class WorkbenchNativeSurface implements AutoCloseable {

    private final Minecraft minecraft = Minecraft.getInstance();
    private RenderTarget target;
    private PrivateBuffers buffers;

    void render(GuiGraphics outer, ControlTree.Bounds bounds, float density, Consumer<GuiGraphics> draw) {
        if (bounds.width() < 1 || bounds.height() < 1) return;
        double scale = density * minecraft.getWindow().getGuiScale();
        int pixelWidth = Math.max(1, (int) Math.ceil(bounds.width() * scale)), pixelHeight = Math.max(1, (int) Math.ceil(bounds.height() * scale));
        outer.flush();
        try (var saved = new WorkbenchViewportGraphicsState()) {
            if (target == null || target.width != pixelWidth || target.height != pixelHeight) {
                if (target != null) {
                    target.destroyBuffers();
                    target = null;
                }
                var created = new RenderTarget(true) {};
                try {
                    created.resize(pixelWidth, pixelHeight, Minecraft.ON_OSX);
                    target = created;
                } catch (RuntimeException | Error failure) {
                    try {
                        created.destroyBuffers();
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }
            RenderSystem.disableScissor();
            target.setClearColor(0, 0, 0, 1);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, bounds.width(), bounds.height(), 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
            var modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            try {
                modelView.setIdentity();
                modelView.translate(0, 0, -11000);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.disableDepthTest();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(1, 1, 1, 1);
                var graphics = new LocalGraphics(bounds.width(), bounds.height(), pixelWidth, pixelHeight);
                try {
                    draw.accept(graphics);
                    graphics.flush();
                } catch (RuntimeException | Error failure) {
                    buffers.discardBatch();
                    throw failure;
                } finally {
                    RenderSystem.disableScissor();
                }
            } finally {
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saved.drawFramebuffer());
            int left = (int) Math.floor(bounds.x() * scale), right = (int) Math.ceil((bounds.x() + bounds.width()) * scale);
            int top = (int) Math.floor(bounds.y() * scale), bottom = (int) Math.ceil((bounds.y() + bounds.height()) * scale), hostHeight = minecraft.getWindow().getHeight();
            GL30.glBlitFramebuffer(0, 0, pixelWidth, pixelHeight, left, hostHeight - bottom, right, hostHeight - top, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        }
    }

    @Override
    public void close() {
        try {
            if (target != null) {
                try (var ignored = new WorkbenchViewportGraphicsState()) {
                    target.destroyBuffers();
                    target = null;
                }
            }
        } finally {
            if (buffers != null) {
                buffers.close();
                buffers = null;
            }
        }
    }

    private PrivateBuffers buffers() {
        if (buffers == null) buffers = new PrivateBuffers();
        return buffers;
    }

    /** One explicitly owned native allocation; failures discard geometry without drawing it. */
    private static final class PrivateBuffers extends MultiBufferSource.BufferSource implements AutoCloseable {

        private boolean closed;

        PrivateBuffers() {
            super(new BufferBuilder(1536), Map.of());
        }

        void discardBatch() {
            ((BufferBuilderAccess) builder).fpsmatch$reset();
            builder.discard();
            startedBuffers.clear();
            lastState = Optional.empty();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            discardBatch();
            MemoryUtil.getAllocator(false).free(MemoryUtil.memAddress0(((BufferBuilderAccess) builder).fpsmatch$buffer()));
        }
    }

    /** Native screens and overlays see their actual local size, including nested scissor conversion. */
    private final class LocalGraphics extends GuiGraphics {

        private final int logicalWidth, logicalHeight, pixelWidth, pixelHeight;
        private final Deque<int[]> clips = new ArrayDeque<>();

        LocalGraphics(int w, int h, int pw, int ph) {
            super(Minecraft.getInstance(), WorkbenchNativeSurface.this.buffers());
            logicalWidth = w;
            logicalHeight = h;
            pixelWidth = pw;
            pixelHeight = ph;
        }

        @Override
        public int guiWidth() {
            return logicalWidth;
        }

        @Override
        public int guiHeight() {
            return logicalHeight;
        }

        @Override
        public void enableScissor(int left, int top, int right, int bottom) {
            if (!clips.isEmpty()) {
                var current = clips.peek();
                left = Math.max(left, current[0]);
                top = Math.max(top, current[1]);
                right = Math.min(right, current[2]);
                bottom = Math.min(bottom, current[3]);
            }
            clips.push(new int[] { left, top, Math.max(left, right), Math.max(top, bottom) });
            applyClip();
        }

        @Override
        public void disableScissor() {
            if (!clips.isEmpty()) clips.pop();
            applyClip();
        }

        private void applyClip() {
            flush();
            if (clips.isEmpty()) {
                RenderSystem.disableScissor();
                return;
            }
            var r = clips.peek();
            double sx = (double) pixelWidth / logicalWidth, sy = (double) pixelHeight / logicalHeight;
            int l = (int) Math.floor(r[0] * sx), t = (int) Math.floor(r[1] * sy), rgt = (int) Math.ceil(r[2] * sx), b = (int) Math.ceil(r[3] * sy);
            RenderSystem.enableScissor(l, pixelHeight - b, Math.max(0, rgt - l), Math.max(0, b - t));
        }
    }
}
