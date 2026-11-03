package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.BlendStateAccess;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.PoseStackAccess;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.RenderStateAccess;

import net.minecraft.client.renderer.ShaderInstance;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/** Render-thread scope for a viewport pass, allocation or native child-screen pass. */
final class WorkbenchViewportGraphicsState implements AutoCloseable {

    private final int readFramebuffer, drawFramebuffer, renderbuffer, program, vao, arrayBuffer, elementBuffer, activeTexture;
    private final int[] viewport = new int[4], scissor = new int[4], colorMask = new int[4], polygonMode = new int[2];
    private final int[] textures = new int[GlStateManager.TEXTURE_COUNT], shaderTextures = new int[GlStateManager.TEXTURE_COUNT];
    private final boolean depth, depthMask, blend, cull, scissorEnabled, polygonOffset, colorLogic;
    private final int depthFunction, blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, blendRgbEquation, blendAlphaEquation, logicOp;
    private final float polygonFactor, polygonUnits;
    private final float[] clearColor = new float[4];
    private final double clearDepth;
    private final double[] depthRange = new double[2];
    private final int frontFace, cullFace;
    private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
    private final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
    private final Matrix3f inverseView = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
    private final VertexSorting sorting = RenderSystem.getVertexSorting();
    private final ShaderInstance shader = RenderSystem.getShader();
    private final float[] shaderColor = RenderSystem.getShaderColor().clone(), fogColor = RenderSystem.getShaderFogColor().clone();
    private final float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
    private final FogShape fogShape = RenderSystem.getShaderFogShape();
    private final float gameTime = RenderSystem.getShaderGameTime(), lineWidth = RenderSystem.getShaderLineWidth();
    private final float glint = RenderSystem.getShaderGlintAlpha();
    private final Vector3f light0, light1;
    private final BlendMode blendCache;
    private final List<SavedPose> modelView = new ArrayList<>();
    private boolean closed;

    WorkbenchViewportGraphicsState() {
        RenderSystem.assertOnRenderThread();
        readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygonMode);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, depthRange);
        frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
        cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        colorLogic = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
        logicOp = GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE);
        depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        blendRgbEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        blendAlphaEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        polygonFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        polygonUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        Vector3f[] lights = RenderStateAccess.fpsmatch$shaderLights();
        light0 = lights[0];
        light1 = lights[1];
        blendCache = BlendStateAccess.fpsmatch$lastApplied();
        for (PoseStack.Pose pose : ((PoseStackAccess) RenderSystem.getModelViewStack()).fpsmatch$poses())
            modelView.add(new SavedPose(pose, new Matrix4f(pose.pose()), new Matrix3f(pose.normal())));
        try {
            for (int unit = 0; unit < textures.length; unit++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
                textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                shaderTextures[unit] = RenderSystem.getShaderTexture(unit);
            }
        } finally {
            GlStateManager._activeTexture(activeTexture);
        }
    }

    int drawFramebuffer() {
        return drawFramebuffer;
    }

    @Override
    public void close() {
        if (closed) return;
        RenderSystem.assertOnRenderThread();
        // Each group is attempted even if a preceding restoration throws. The caller receives
        // the first failure with the remainder suppressed, so no completed frame is published.
        Throwable failure = null;
        failure = restore(failure, () -> {
            ShaderInstance current = RenderSystem.getShader();
            if (current != null) current.clear();
            RenderSystem.setShader(() -> shader);
            GlStateManager._glUseProgram(program);
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        });
        failure = restore(failure, () -> {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GlStateManager._scissorBox(scissor[0], scissor[1], scissor[2], scissor[3]);
            if (scissorEnabled) GlStateManager._enableScissorTest();
            else GlStateManager._disableScissorTest();
        });
        failure = restore(failure, () -> {
            for (int unit = 0; unit < textures.length; unit++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
                GlStateManager._bindTexture(textures[unit]);
                RenderSystem.setShaderTexture(unit, shaderTextures[unit]);
            }
            GlStateManager._activeTexture(activeTexture);
        });
        failure = restore(failure, () -> {
            var poses = ((PoseStackAccess) RenderSystem.getModelViewStack()).fpsmatch$poses();
            poses.clear();
            for (SavedPose saved : modelView) {
                saved.original().pose().set(saved.pose());
                saved.original().normal().set(saved.normal());
                poses.addLast(saved.original());
            }
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setTextureMatrix(textureMatrix);
            RenderSystem.setInverseViewRotationMatrix(inverseView);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
            RenderSystem.setShaderLights(light0, light1);
            RenderSystem.setShaderGlintAlpha(glint);
            RenderStateAccess.fpsmatch$shaderGameTime(gameTime);
            RenderSystem.lineWidth(lineWidth);
        });
        failure = restore(failure, () -> {
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.depthMask(depthMask);
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            GL20.glBlendEquationSeparate(blendRgbEquation, blendAlphaEquation);
            BlendStateAccess.fpsmatch$lastApplied(blendCache);
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            GL11.glFrontFace(frontFace);
            GL11.glCullFace(cullFace);
            GL11.glDepthRange(depthRange[0], depthRange[1]);
            RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
            GlStateManager._polygonMode(GL11.GL_FRONT_AND_BACK, polygonMode[0]);
            GlStateManager._polygonOffset(polygonFactor, polygonUnits);
            if (polygonOffset) GlStateManager._enablePolygonOffset();
            else GlStateManager._disablePolygonOffset();
            GlStateManager._logicOp(logicOp);
            if (colorLogic) GlStateManager._enableColorLogicOp();
            else GlStateManager._disableColorLogicOp();
            GlStateManager._clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            GlStateManager._clearDepth(clearDepth);
        });
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        closed = true;
    }

    private static Throwable restore(Throwable prior, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (prior == null) return failure;
            if (prior != failure) prior.addSuppressed(failure);
        }
        return prior;
    }

    private record SavedPose(PoseStack.Pose original, Matrix4f pose, Matrix3f normal) {}
}
