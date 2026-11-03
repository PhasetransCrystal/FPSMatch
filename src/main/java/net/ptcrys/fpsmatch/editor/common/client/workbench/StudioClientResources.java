package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Font objects may survive resource reloads; cache validity follows the resource generation. */
@Mod.EventBusSubscriber(modid = "fpsmatch", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StudioClientResources {

    private static final AtomicLong generation = new AtomicLong();
    private static final AtomicLong shaderGeneration = new AtomicLong();
    private static ShaderInstance coverageShader;

    private StudioClientResources() {}

    public static long generation() {
        return generation.get();
    }

    public static long shaderGeneration() {
        return shaderGeneration.get();
    }

    static ShaderInstance coverageShader() {
        return java.util.Objects.requireNonNull(coverageShader, "Studio coverage shader is unavailable");
    }

    @SubscribeEvent
    public static void shaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), new ResourceLocation("fpsmatch", "studio_coverage"), DefaultVertexFormat.POSITION_TEX_COLOR), shader -> {
            coverageShader = shader;
            shaderGeneration.incrementAndGet();
        });
    }

    @SubscribeEvent
    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> generation.incrementAndGet());
    }
}
