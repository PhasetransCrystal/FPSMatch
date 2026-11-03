package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.BufferBuilderAccess;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.BufferSourceAccess;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.OutlineSourceAccess;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Borrows idle render-thread batches for one local world pass. Closing never draws anything.
 * In particular it does not call endBatch on an incomplete vertex after a renderer throws.
 */
public final class WorkbenchViewportBatches implements AutoCloseable {

    private static WorkbenchViewportBatches borrowing;
    private final MultiBufferSource.BufferSource main, crumbling;
    private final MultiBufferSource.BufferSource outline;
    private final List<BufferSourceAccess> sources;
    private final Set<BufferBuilder> builders = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    WorkbenchViewportBatches(RenderBuffers buffers) {
        RenderSystem.assertOnRenderThread();
        if (borrowing != null) throw new IllegalStateException("nested viewport batch borrow");
        main = buffers.bufferSource();
        crumbling = buffers.crumblingBufferSource();
        outline = ((OutlineSourceAccess) buffers.outlineBufferSource()).fpsmatch$outlineSource();
        sources = List.of((BufferSourceAccess) main, (BufferSourceAccess) crumbling, (BufferSourceAccess) outline);
        for (BufferSourceAccess source : sources) {
            builders.add(source.fpsmatch$builder());
            builders.addAll(source.fpsmatch$fixedBuffers().values());
            if (!source.fpsmatch$startedBuffers().isEmpty())
                throw new IllegalStateException("world viewport requires idle shared render batches");
        }
        builders.add(Tesselator.getInstance().getBuilder());
        Set<BufferBuilder> chunkBuilders = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RenderType layer : RenderType.chunkBufferLayers()) chunkBuilders.add(buffers.fixedBufferPack().builder(layer));
        for (BufferBuilder builder : builders) {
            // A previous interrupted pass may leave queued chunk uploads in the fixed pack.
            // Let renderLevel reach its upload pump before checking those at actual borrow.
            if (builder.building() || (!chunkBuilders.contains(builder) && ((BufferBuilderAccess) builder).fpsmatch$renderedBufferCount() != 0))
                throw new IllegalStateException("world viewport cannot borrow an active or retained vertex builder");
        }
        // No mutation precedes all preconditions. A rejected borrow does not discard another
        // consumer's vertices. lastState may survive an empty vanilla endBatch, so clear only
        // that stale lookup after the builder ownership checks have succeeded.
        for (BufferSourceAccess source : sources) source.fpsmatch$lastState(Optional.empty());
        // Fixed builders are also used for synchronous chunk compilation. Only getBuffer
        // requests belong to the immediate world pass; do not discard a chunk upload's
        // retained bytes just because it shares one of those builders.
        builders.clear();
        builders.add(Tesselator.getInstance().getBuilder());
        borrowing = this;
    }

    /** Mixin seam, inert outside this render-thread borrow and for unrelated buffer sources. */
    public static void bufferRequested(MultiBufferSource.BufferSource source, RenderType type) {
        if (!RenderSystem.isOnRenderThread() || borrowing == null) return;
        WorkbenchViewportBatches scope = borrowing;
        if (source != scope.main && source != scope.crumbling && source != scope.outline) return;
        BufferSourceAccess access = (BufferSourceAccess) source;
        BufferBuilder builder = access.fpsmatch$fixedBuffers().getOrDefault(type, access.fpsmatch$builder());
        if (!scope.builders.contains(builder)) {
            if (builder.building() || ((BufferBuilderAccess) builder).fpsmatch$renderedBufferCount() != 0)
                throw new IllegalStateException("world viewport cannot borrow a vertex builder before its chunk upload completes");
            scope.builders.add(builder);
        }
    }

    /** Called only on success, while the viewport target and world matrices still own the pass. */
    void flushWorld() {
        RenderSystem.assertOnRenderThread();
        main.endBatch();
        crumbling.endBatch();
        // This viewport deliberately omits entity-outline post effects. Their private batch
        // is discarded by close, rather than rebound to a full-window target or null target.
    }

    /** An interrupted synchronous chunk compile cannot be recovered as an ordinary UI error. */
    static boolean hasUnfinishedChunkBuild(RenderBuffers buffers) {
        for (RenderType layer : RenderType.chunkBufferLayers()) {
            if (buffers.fixedBufferPack().builder(layer).building()) return true;
        }
        return false;
    }

    @Override
    public void close() {
        if (closed) return;
        RenderSystem.assertOnRenderThread();
        Throwable failed = null;
        try {
            for (BufferSourceAccess source : sources) builders.addAll(source.fpsmatch$startedBuffers());
            // Include every requested builder, not just startedBuffers: endBatch removes its
            // builder from that set before RenderType.end, which can throw during upload.
            for (BufferBuilder builder : builders) {
                try {
                    ((BufferBuilderAccess) builder).fpsmatch$reset();
                    builder.discard();
                    builder.unsetDefaultColor();
                } catch (RuntimeException | Error failure) {
                    failed = append(failed, failure);
                }
            }
            for (BufferSourceAccess source : sources) {
                try {
                    source.fpsmatch$startedBuffers().clear();
                    source.fpsmatch$lastState(Optional.empty());
                } catch (RuntimeException | Error failure) {
                    failed = append(failed, failure);
                }
            }
        } finally {
            borrowing = null;
        }
        if (failed instanceof RuntimeException exception) throw exception;
        if (failed instanceof Error error) throw error;
        closed = true;
    }

    private static Throwable append(Throwable prior, Throwable failure) {
        if (prior == null) return failure;
        if (prior != failure) prior.addSuppressed(failure);
        return prior;
    }
}
