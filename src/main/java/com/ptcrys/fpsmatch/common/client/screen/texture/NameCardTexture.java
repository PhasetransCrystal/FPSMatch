package com.ptcrys.fpsmatch.common.client.screen.texture;

import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import com.mojang.blaze3d.platform.NativeImage;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

public final class NameCardTexture extends SimpleTexture {

    private final File file;
    public int realWidth = 256, realHeight = 256;

    public NameCardTexture(File file, ResourceLocation id) {
        super(id);
        this.file = file;
    }

    @Override
    protected @NotNull TextureImage getTextureImage(ResourceManager rm) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) throw new IOException("ImageIO.read returned null");
            realWidth = img.getWidth();
            realHeight = img.getHeight();
            NativeImage ni = new NativeImage(NativeImage.Format.RGBA, realWidth, realHeight, false);
            for (int y = 0; y < realHeight; y++) for (int x = 0; x < realWidth; x++)
                ni.setPixelRGBA(x, y, argbToAbgr(img.getRGB(x, y)));
            return new TextureImage(null, ni);
        } catch (IOException ex) {
            NativeImage fb = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            fb.setPixelRGBA(0, 0, 0xFFFF00FF);
            return new TextureImage(null, fb);
        }
    }

    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
