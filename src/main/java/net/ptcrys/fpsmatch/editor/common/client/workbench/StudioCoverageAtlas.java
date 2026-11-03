package net.ptcrys.fpsmatch.editor.common.client.workbench;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Render-thread coverage storage. Display lists never retain atlas coordinates. The single
 * page is reused only after ordered draws referencing it have been submitted in this context.
 * OpenGL orders the subsequent upload after those draws and defers deletion of in-use storage.
 */
final class StudioCoverageAtlas implements AutoCloseable {

    static final int SIDE = 2048;
    private static final int MAX_ENTRIES = 4096;

    record Region(int x, int y, int width, int height, float originX, float baseline) {}

    /** deviceBytes is the logical RGBA page size, not measured driver memory or residency. */
    record Stats(int entries, long deviceBytes, long uploads, long uploadedBytes, long resets) {}

    private final Map<Object, Region> entries = new HashMap<>();
    private int texture, x, y, rowHeight;
    private long uploads, uploadedBytes, resets;
    private long textureAllocations, textureReleases;

    int texture() {
        return texture;
    }

    long textureAllocations() {
        return textureAllocations;
    }

    long textureReleases() {
        return textureReleases;
    }

    Region find(Object key) {
        return entries.get(key);
    }

    Stats stats() {
        return new Stats(entries.size(), texture == 0 ? 0L : 4L * SIDE * SIDE, uploads, uploadedBytes, resets);
    }

    Region get(Object key, int width, int height, Supplier<byte[]> pixels, Runnable beforeReuse) {
        return get(key, width, height, 0, 0, pixels, beforeReuse);
    }

    Region get(Object key, int width, int height, float originX, float baseline, Supplier<byte[]> pixels, Runnable beforeReuse) {
        RenderSystem.assertOnRenderThread();
        var cached = entries.get(key);
        if (cached != null) return cached;
        if (width < 1 || height < 1 || width > SIDE || height > SIDE) throw new IllegalArgumentException("coverage tile exceeds atlas page");
        if (x + width > SIDE) {
            x = 0;
            y += rowHeight;
            rowHeight = 0;
        }
        if (y + height > SIDE || entries.size() >= MAX_ENTRIES) {
            beforeReuse.run();
            entries.clear();
            x = 0;
            y = 0;
            rowHeight = 0;
            resets++;
        }
        byte[] rgba = pixels.get();
        if (rgba.length != 4L * width * height) throw new IllegalArgumentException("coverage byte count");
        var region = new Region(x, y, width, height, originX, baseline);
        upload(region, rgba);
        x += width;
        rowHeight = Math.max(rowHeight, height);
        entries.put(key, region);
        uploads++;
        uploadedBytes += rgba.length;
        return region;
    }

    private void upload(Region region, byte[] rgba) {
        int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int row = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int skipX = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS), skipY = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        ByteBuffer data = MemoryUtil.memAlloc(rgba.length);
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            if (texture == 0) {
                texture = GlStateManager._genTexture();
                if (texture == 0) throw new IllegalStateException("coverage texture creation returned zero");
                textureAllocations++;
                RenderSystem.bindTexture(texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIDE, SIDE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            } else RenderSystem.bindTexture(texture);
            data.put(rgba).flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, region.x(), region.y(), region.width(), region.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        } finally {
            MemoryUtil.memFree(data);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, row);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipX);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipY);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        entries.clear();
        x = 0;
        y = 0;
        rowHeight = 0;
        if (texture != 0) {
            GlStateManager._deleteTexture(texture);
            texture = 0;
            textureReleases++;
        }
    }
}
