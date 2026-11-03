package net.ptcrys.fpsmatch.editor.common.client.workbench;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.util.Optional;

/** One synchronous click read. No retained pixels or inherited pixel-pack/PBO destinations. */
final class WorkbenchViewportDepthReader {

    record Sample(float depth, int bits, boolean floating) {}

    private static final int[] PACK_PARAMETERS = { GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST,
            GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES };

    static Optional<Sample> read(int framebuffer, int x, int y) {
        RenderSystem.assertOnRenderThread();
        if (framebuffer <= 0 || x < 0 || y < 0) return Optional.empty();
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] pack = new int[PACK_PARAMETERS.length];
        for (int i = 0; i < pack.length; i++) pack[i] = GL11.glGetInteger(PACK_PARAMETERS[i]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                return Optional.empty();
            int bits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
            if (bits <= 0 || type != GL11.GL_FLOAT && type != GL30.GL_UNSIGNED_NORMALIZED) return Optional.empty();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < pack.length; i++) GL11.glPixelStorei(PACK_PARAMETERS[i], i == 0 ? 4 : 0);
            var value = stack.mallocFloat(1);
            value.put(0, Float.NaN);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, value);
            float depth = value.get(0);
            if (!Float.isFinite(depth) || depth < 0 || depth >= 1) return Optional.empty();
            return Optional.of(new Sample(depth, bits, type == GL11.GL_FLOAT));
        } catch (RuntimeException failure) {
            return Optional.empty();
        } finally {
            for (int i = 0; i < pack.length; i++) GL11.glPixelStorei(PACK_PARAMETERS[i], pack[i]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
    }

    private WorkbenchViewportDepthReader() {}
}
