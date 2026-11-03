package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.image.StudioImageRasterizer;
import net.ptcrys.fpsmatch.editor.common.text.java2d.Raster;
import net.ptcrys.fpsmatch.editor.common.text.java2d.StudioIconRasterizer;
import net.ptcrys.fpsmatch.editor.common.text.java2d.StudioJava2dText;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioCanvas;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioDeviceClip;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioStroke;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.BufferBuilderAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

/** Ordered submission backend. No control skins, measurement or input state live here. */
final class StudioRenderer implements AutoCloseable {

    /** Last submitted frame, including the resources retained when that frame ended. */
    record Stats(int quads, int textRuns, int submittedCodePoints, int batches, int flushes, int clipChanges, int stagingBytes, int coverageQuads, StudioCoverageAtlas.Stats atlas, StudioIconRasterizer.Stats icons) {}

    /**
     * Current native capacity. Counters track buffer-owner creation/disposal, not the
     * BufferBuilder's internal reallocations when its capacity grows.
     */
    record StagingStats(int bytes, long allocations, long releases) {}

    /**
     * Render-thread snapshot, independent of the last frame. Atlas bytes are the logical page
     * size, not driver-measured residency; texture counters track name creation/deletion calls.
     * Allocation counters also identify successive resource generations of this renderer.
     */
    record ResourceStats(boolean rendering, StagingStats solidStaging, StagingStats maskStaging,
                         StudioCoverageAtlas.Stats atlas, int textureId, long textureAllocations,
                         long textureReleases, StudioIconRasterizer.Stats icons, StudioImageRasterizer.Stats images) {

        long stagingBytes() {
            return (long) solidStaging.bytes() + maskStaging.bytes();
        }
    }

    private OrderedBuffers buffers;
    private MaskBuffer masks;
    private long solidAllocations, solidReleases, maskAllocations, maskReleases;
    private final StudioCoverageAtlas atlas = new StudioCoverageAtlas();
    private final StudioIconRasterizer icons = new StudioIconRasterizer();
    private final StudioImageRasterizer images = new StudioImageRasterizer(resource -> {
        var found = Minecraft.getInstance().getResourceManager().getResource(new net.minecraft.resources.ResourceLocation(resource));
        return found.isPresent() ? found.get().open() : null;
    });
    private static StudioRenderer active;
    private final StudioJava2dText textBackend;

    private record TextTile(long line, float density, int start, int width) {}

    private Matrix4f transform;
    private WorkbenchViewportGraphicsState host;
    private boolean hostScissor;
    private int hostSampler;
    private boolean samplerObjects;
    private Bounds hostScissorBox;
    private Bounds clip;
    private double deviceScale;
    private int framebufferHeight, quads, textRuns, submittedCodePoints, flushes, clipChanges, commands, coverageQuads, maskBatches;
    private boolean dirty, failed;
    private Stats last = new Stats(0, 0, 0, 0, 0, 0, 0, 0, atlas.stats(), icons.stats());

    StudioRenderer(StudioJava2dText textBackend) {
        this.textBackend = textBackend;
    }

    void begin(GuiGraphics graphics, float density) {
        RenderSystem.assertOnRenderThread();
        if (active != null) throw new IllegalStateException("nested UI render session");
        host = new WorkbenchViewportGraphicsState();
        try {
            hostScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            int[] box = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            hostScissorBox = new Bounds(box[0], box[1], box[2], box[3]);
            graphics.flush();
            var capabilities = GL.getCapabilities();
            samplerObjects = capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects;
            hostSampler = samplerObjects ? GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0) : 0;
            if (buffers == null) {
                buffers = new OrderedBuffers();
                solidAllocations++;
            }
            if (masks == null) {
                masks = new MaskBuffer();
                maskAllocations++;
            }
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.disableColorLogicOp();
            GL20.glBlendEquationSeparate(GL20.GL_FUNC_ADD, GL20.GL_FUNC_ADD);
            transform = new Matrix4f(graphics.pose().last().pose());
            var window = Minecraft.getInstance().getWindow();
            deviceScale = density * window.getGuiScale();
            framebufferHeight = window.getHeight();
            clip = null;
            dirty = false;
            failed = false;
            quads = 0;
            textRuns = 0;
            submittedCodePoints = 0;
            flushes = 0;
            clipChanges = 0;
            commands = 0;
            coverageQuads = 0;
            maskBatches = 0;
            buffers.batches = 0;
            images.beginFrame();
            active = this;
        } catch (RuntimeException | Error failure) {
            try {
                host.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            host = null;
            throw failure;
        }
    }

    void submit(StudioCanvas.Picture picture) {
        if (active != this) throw new IllegalStateException("UI renderer is not active");
        try {
            for (var command : picture.commands()) {
                if (commands++ >= 2048) {
                    flush();
                    commands = 0;
                }
                if (command instanceof StudioCanvas.Quad quad) {
                    clip(null);
                    flushMasks();
                    var b = quad.bounds();
                    int c = quad.color();
                    var vertices = buffers.getBuffer(RenderType.guiOverlay());
                    dirty = true;
                    int r = (c >>> 16) & 255, g = (c >>> 8) & 255, blue = c & 255, a = c >>> 24;
                    vertices.vertex(transform, b.x() + b.width(), b.y() + b.height(), 0).color(r, g, blue, a).endVertex();
                    vertices.vertex(transform, b.x() + b.width(), b.y(), 0).color(r, g, blue, a).endVertex();
                    vertices.vertex(transform, b.x(), b.y(), 0).color(r, g, blue, a).endVertex();
                    vertices.vertex(transform, b.x(), b.y() + b.height(), 0).color(r, g, blue, a).endVertex();
                    quads++;
                } else if (command instanceof StudioCanvas.Segment segment) {
                    clip(segment.clip());
                    flushMasks();
                    var mesh = StudioStroke.mesh(segment, deviceScale);
                    if (!mesh.isEmpty()) {
                        var vertices = buffers.getBuffer(RenderType.guiOverlay());
                        dirty = true;
                        for (var face : mesh) {
                            strokeVertex(vertices, face.a(), segment.color());
                            strokeVertex(vertices, face.b(), segment.color());
                            strokeVertex(vertices, face.c(), segment.color());
                            strokeVertex(vertices, face.d(), segment.color());
                            quads++;
                        }
                    }
                } else if (command instanceof StudioCanvas.IconGlyph icon) {
                    clip(icon.clip());
                    flushSolids();
                    int w = Math.max(1, (int) Math.round(icon.bounds().width() * deviceScale));
                    int h = Math.max(1, (int) Math.round(icon.bounds().height() * deviceScale));
                    var mask = icons.mask(icon.icon(), w, h);
                    var region = atlas.get(mask.key(), w, h, mask::rgba, this::flush);
                    maskQuad(region, snap(icon.bounds().x()), snap(icon.bounds().y()), icon.color());
                } else if (command instanceof StudioCanvas.Image image) {
                    clip(image.clip());
                    flushSolids();
                    submitImage(image);
                } else if (command instanceof StudioCanvas.Text text) {
                    clip(text.clip());
                    flushSolids();
                    var line = textBackend.line(text.value(), text.size());
                    float d = (float) deviceScale;
                    // Rasterize the same shaped line, in bounded physical-width windows. Adjacent
                    // tiles crop padding at their common boundary so coverage is never composited twice.
                    long originPixel = Math.round(text.x() * deviceScale);
                    int start = (int) (Math.floor(text.clip().x() * deviceScale) - originPixel);
                    int end = (int) (Math.ceil((text.clip().x() + text.clip().width()) * deviceScale) - originPixel);
                    int inkStart = (int) Math.floor(line.inkLeft() * deviceScale) - 2;
                    int inkEnd = (int) Math.ceil(line.inkRight() * deviceScale) + 2;
                    start = Math.max(inkStart, start);
                    end = Math.min(inkEnd, end);
                    long baselinePixel = Math.round((text.y() + line.ascent()) * deviceScale);
                    for (int from = start; from < end; from += 1024) {
                        int width = Math.min(1024, end - from);
                        var key = new TextTile(line.identity(), d, from, width);
                        var region = atlas.find(key);
                        if (region == null) {
                            var raster = textBackend.raster(line, d, from / d, width / d);
                            region = atlas.get(key, raster.width(), raster.height(), raster.originX(), raster.baseline(), () -> coverage(raster), this::flush);
                        }
                        float x = (float) ((originPixel - region.originX()) / deviceScale), y = (float) ((baselinePixel - region.baseline()) / deviceScale);
                        maskQuad(region, x, y, text.color(), (float) ((originPixel + from) / deviceScale), (float) ((originPixel + from + width) / deviceScale));
                    }
                    textRuns++;
                    submittedCodePoints += text.value().codePointCount(0, text.value().length());
                }
            }
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    private void submitImage(StudioCanvas.Image image) {
        var box = image.bounds();
        double physicalWidth = Math.max(1, box.width() * deviceScale), physicalHeight = Math.max(1, box.height() * deviceScale);
        double reduction = Math.min(1, StudioImageRasterizer.MAX_SIDE / Math.max(physicalWidth, physicalHeight));
        int width = Math.max(1, (int) Math.round(physicalWidth * reduction)), height = Math.max(1, (int) Math.round(physicalHeight * reduction));
        if (!image.resource().isBlank() && image.resource().length() <= StudioImageRasterizer.MAX_RESOURCE_LENGTH) {
            var key = new StudioImageRasterizer.Key(image.resource(), width, height, StudioImageRasterizer.RASTER_VERSION);
            var region = atlas.find(key);
            if (region == null) {
                var raster = images.raster(image.resource(), width, height);
                if (raster.isPresent()) region = atlas.get(key, width, height, raster.get()::rgba, this::flush);
            }
            if (region != null) {
                imageQuad(region, box, 0xffffffff);
                return;
            }
        }
        if (image.fallback() == net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Icon.NONE) return;
        int side = Math.min(20, Math.min(box.width(), box.height()));
        if (side < 1) return;
        int pixels = Math.max(1, Math.min(256, (int) Math.round(side * deviceScale)));
        var mask = icons.mask(image.fallback(), pixels, pixels);
        var region = atlas.get(mask.key(), pixels, pixels, mask::rgba, this::flush);
        imageQuad(region, new Bounds(box.x() + (box.width() - side) / 2, box.y() + (box.height() - side) / 2, side, side), image.fallbackColor());
    }

    /** Full-colour and fallback images share the existing ordered RGBA atlas batch. */
    private void imageQuad(StudioCoverageAtlas.Region region, Bounds bounds, int color) {
        if (masks.quads >= 512) flushMasks();
        float x = snap(bounds.x()), y = snap(bounds.y()), right = snap((double) bounds.x() + bounds.width()), bottom = snap((double) bounds.y() + bounds.height());
        float u = region.x() / (float) StudioCoverageAtlas.SIDE, v = region.y() / (float) StudioCoverageAtlas.SIDE;
        float u2 = (region.x() + region.width()) / (float) StudioCoverageAtlas.SIDE, v2 = (region.y() + region.height()) / (float) StudioCoverageAtlas.SIDE;
        int red = color >>> 16 & 255, green = color >>> 8 & 255, blue = color & 255, alpha = color >>> 24;
        masks.begin();
        var buffer = masks.builder;
        buffer.vertex(transform, x, bottom, 0).uv(u, v2).color(red, green, blue, alpha).endVertex();
        buffer.vertex(transform, right, bottom, 0).uv(u2, v2).color(red, green, blue, alpha).endVertex();
        buffer.vertex(transform, right, y, 0).uv(u2, v).color(red, green, blue, alpha).endVertex();
        buffer.vertex(transform, x, y, 0).uv(u, v).color(red, green, blue, alpha).endVertex();
        masks.quads++;
        coverageQuads++;
    }

    private void strokeVertex(com.mojang.blaze3d.vertex.VertexConsumer target, StudioStroke.Vertex vertex, int color) {
        target.vertex(transform, (float) vertex.x(), (float) vertex.y(), 0)
                .color(color >>> 16 & 255, color >>> 8 & 255, color & 255, (int) Math.round((color >>> 24) * vertex.coverage())).endVertex();
    }

    private void clip(Bounds logical) {
        Bounds next = logical == null ? null : StudioDeviceClip.pixels(logical, deviceScale, framebufferHeight);
        if (next != null && hostScissor) next = next.intersect(hostScissorBox);
        if (java.util.Objects.equals(clip, next)) return;
        flush();
        if (next == null) {
            if (hostScissor) RenderSystem.enableScissor(hostScissorBox.x(), hostScissorBox.y(), hostScissorBox.width(), hostScissorBox.height());
            else RenderSystem.disableScissor();
        } else RenderSystem.enableScissor(next.x(), next.y(), next.width(), next.height());
        clip = next;
        clipChanges++;
    }

    private void flush() {
        flushSolids();
        flushMasks();
    }

    private void flushSolids() {
        if (!dirty) return;
        try {
            buffers.endBatch();
            flushes++;
        } finally {
            dirty = false;
        }
    }

    private float snap(double value) {
        return (float) (Math.rint(value * deviceScale) / deviceScale);
    }

    private void maskQuad(StudioCoverageAtlas.Region r, float x, float y, int color) {
        maskQuad(r, x, y, color, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
    }

    private static byte[] coverage(Raster raster) {
        int[] argb = raster.argb();
        byte[] rgba = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int at = i * 4;
            rgba[at] = (byte) 255;
            rgba[at + 1] = (byte) 255;
            rgba[at + 2] = (byte) 255;
            rgba[at + 3] = (byte) (argb[i] >>> 24);
        }
        return rgba;
    }

    private void maskQuad(StudioCoverageAtlas.Region r, float x, float y, int color, float cropLeft, float cropRight) {
        if (masks.quads >= 512) flushMasks();
        float x2 = x + (float) (r.width() / deviceScale), y2 = y + (float) (r.height() / deviceScale);
        float u = r.x() / (float) StudioCoverageAtlas.SIDE, v = r.y() / (float) StudioCoverageAtlas.SIDE;
        float u2 = (r.x() + r.width()) / (float) StudioCoverageAtlas.SIDE, v2 = (r.y() + r.height()) / (float) StudioCoverageAtlas.SIDE;
        if (cropLeft > x) {
            u += (float) ((cropLeft - x) * deviceScale / StudioCoverageAtlas.SIDE);
            x = cropLeft;
        }
        if (cropRight < x2) {
            u2 -= (float) ((x2 - cropRight) * deviceScale / StudioCoverageAtlas.SIDE);
            x2 = cropRight;
        }
        if (x2 <= x) return;
        masks.begin();
        int red = color >>> 16 & 255, green = color >>> 8 & 255, blue = color & 255, alpha = color >>> 24;
        var b = masks.builder;
        b.vertex(transform, x, y2, 0).uv(u, v2).color(red, green, blue, alpha).endVertex();
        b.vertex(transform, x2, y2, 0).uv(u2, v2).color(red, green, blue, alpha).endVertex();
        b.vertex(transform, x2, y, 0).uv(u2, v).color(red, green, blue, alpha).endVertex();
        b.vertex(transform, x, y, 0).uv(u, v).color(red, green, blue, alpha).endVertex();
        masks.quads++;
        coverageQuads++;
    }

    private void flushMasks() {
        if (masks == null || !masks.building) return;
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.setShaderTexture(0, atlas.texture());
        if (samplerObjects) GL33.glBindSampler(0, 0);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShader(StudioClientResources::coverageShader);
        masks.building = false;
        masks.quads = 0;
        BufferUploader.drawWithShader(masks.builder.end());
        maskBatches++;
        flushes++;
    }

    void end() {
        if (active != this) return;
        try {
            if (!failed) flush();
        } finally {
            try {
                try {
                    buffers.reset();
                } finally {
                    masks.reset();
                }
            } finally {
                try {
                    if (samplerObjects) GL33.glBindSampler(0, hostSampler);
                    host.close();
                } finally {
                    last = new Stats(quads, textRuns, submittedCodePoints, buffers.batches + maskBatches, flushes, clipChanges, buffers.bytes() + masks.bytes(), coverageQuads, atlas.stats(), icons.stats());
                    active = null;
                    host = null;
                    transform = null;
                    clip = null;
                    dirty = false;
                }
            }
        }
    }

    Stats stats() {
        return last;
    }

    ResourceStats resourceStats() {
        RenderSystem.assertOnRenderThread();
        return new ResourceStats(active == this,
                new StagingStats(buffers == null ? 0 : buffers.bytes(), solidAllocations, solidReleases),
                new StagingStats(masks == null ? 0 : masks.bytes(), maskAllocations, maskReleases),
                atlas.stats(), atlas.texture(), atlas.textureAllocations(), atlas.textureReleases(), icons.stats(), images.stats());
    }

    void invalidateResources() {
        RenderSystem.assertOnRenderThread();
        if (active == this) throw new IllegalStateException("cannot invalidate active UI resources");
        try {
            atlas.close();
        } finally {
            try {
                icons.clear();
            } finally {
                images.clear();
            }
        }
    }

    /**
     * Retires owned resources between render sessions. Safe to repeat; begin() may reuse this
     * renderer afterwards. Every owner is attempted even when another owner's cleanup fails.
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (active == this) throw new IllegalStateException("cannot release an active UI renderer");
        try {
            if (buffers != null) {
                var current = buffers;
                try {
                    current.close();
                } finally {
                    if (current.closed) {
                        buffers = null;
                        solidReleases++;
                    }
                }
            }
        } finally {
            try {
                if (masks != null) {
                    var current = masks;
                    try {
                        current.close();
                    } finally {
                        if (current.closed) {
                            masks = null;
                            maskReleases++;
                        }
                    }
                }
            } finally {
                invalidateResources();
            }
        }
    }

    private static final class MaskBuffer implements AutoCloseable {

        final BufferBuilder builder = new BufferBuilder(65536);
        boolean building, closed;
        int quads;

        void begin() {
            if (!building) {
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                building = true;
            }
        }

        void reset() {
            try {
                ((BufferBuilderAccess) builder).fpsmatch$reset();
            } finally {
                builder.discard();
                building = false;
                quads = 0;
            }
        }

        int bytes() {
            return closed ? 0 : ((BufferBuilderAccess) builder).fpsmatch$buffer().capacity();
        }

        @Override
        public void close() {
            if (closed) return;
            try {
                reset();
            } finally {
                MemoryUtil.getAllocator(false).free(MemoryUtil.memAddress0(((BufferBuilderAccess) builder).fpsmatch$buffer()));
                closed = true;
            }
        }
    }

    private static final class OrderedBuffers extends MultiBufferSource.BufferSource implements AutoCloseable {

        int batches;
        private boolean closed;

        OrderedBuffers() {
            super(new BufferBuilder(262144), java.util.Map.of());
        }

        @Override
        public void endBatch(RenderType type) {
            boolean pending = startedBuffers.contains(builder) && lastState.equals(type.asOptional());
            super.endBatch(type);
            if (pending) batches++;
        }

        void reset() {
            // Also recover from a partially generated run or a failed RenderType submission.
            try {
                ((BufferBuilderAccess) builder).fpsmatch$reset();
            } finally {
                builder.discard();
                startedBuffers.clear();
                lastState = java.util.Optional.empty();
            }
        }

        int bytes() {
            return closed ? 0 : ((BufferBuilderAccess) builder).fpsmatch$buffer().capacity();
        }

        @Override
        public void close() {
            if (closed) return;
            try {
                reset();
            } finally {
                MemoryUtil.getAllocator(false).free(MemoryUtil.memAddress0(((BufferBuilderAccess) builder).fpsmatch$buffer()));
                closed = true;
            }
        }
    }
}
