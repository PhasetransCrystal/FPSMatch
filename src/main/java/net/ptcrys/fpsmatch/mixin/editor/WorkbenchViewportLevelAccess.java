package net.ptcrys.fpsmatch.mixin.editor;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.culling.Frustum;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

/** Access to the exact 1.20.1 renderer state borrowed and restored by the viewport scope. */
@Mixin(LevelRenderer.class)
public interface WorkbenchViewportLevelAccess {

    @Accessor("entityTarget")
    RenderTarget fpsmatch$entityTarget();

    @Accessor("entityTarget")
    void fpsmatch$entityTarget(RenderTarget value);

    @Accessor("translucentTarget")
    RenderTarget fpsmatch$translucentTarget();

    @Accessor("translucentTarget")
    void fpsmatch$translucentTarget(RenderTarget value);

    @Accessor("itemEntityTarget")
    RenderTarget fpsmatch$itemEntityTarget();

    @Accessor("itemEntityTarget")
    void fpsmatch$itemEntityTarget(RenderTarget value);

    @Accessor("particlesTarget")
    RenderTarget fpsmatch$particlesTarget();

    @Accessor("particlesTarget")
    void fpsmatch$particlesTarget(RenderTarget value);

    @Accessor("weatherTarget")
    RenderTarget fpsmatch$weatherTarget();

    @Accessor("weatherTarget")
    void fpsmatch$weatherTarget(RenderTarget value);

    @Accessor("cloudsTarget")
    RenderTarget fpsmatch$cloudsTarget();

    @Accessor("cloudsTarget")
    void fpsmatch$cloudsTarget(RenderTarget value);

    @Accessor("transparencyChain")
    PostChain fpsmatch$transparencyChain();

    @Accessor("transparencyChain")
    void fpsmatch$transparencyChain(PostChain value);

    @Accessor("cullingFrustum")
    Frustum fpsmatch$cullingFrustum();

    @Accessor("cullingFrustum")
    void fpsmatch$cullingFrustum(Frustum value);

    @Accessor("capturedFrustum")
    Frustum fpsmatch$capturedFrustum();

    @Accessor("capturedFrustum")
    void fpsmatch$capturedFrustum(Frustum value);

    @Accessor("captureFrustum")
    boolean fpsmatch$captureFrustum();

    @Accessor("captureFrustum")
    void fpsmatch$captureFrustum(boolean value);

    @Accessor("needsFrustumUpdate")
    AtomicBoolean fpsmatch$needsFrustumUpdate();

    @Accessor("needsFullRenderChunkUpdate")
    void fpsmatch$needsFullRenderChunkUpdate(boolean value);
}
