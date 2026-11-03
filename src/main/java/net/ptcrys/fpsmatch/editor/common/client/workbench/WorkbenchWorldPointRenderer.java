package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldPointMesh;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.mixin.editor.WorkbenchViewportRenderMixin.BufferBuilderAccess;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Opaque world geometry. Its private batch cannot flush another world renderer's vertices. */
public final class WorkbenchWorldPointRenderer implements AutoCloseable {

    private BufferBuilder builder;

    public record Instance(String node, Position origin, WorldPointMesh.Mesh mesh, int argb) {

        public Instance {
            Objects.requireNonNull(node);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(mesh);
        }
    }

    List<Instance> draw(List<Instance> points, Vec3 camera, Matrix4f view, Frustum frustum) {
        var submitted = new ArrayList<Instance>();
        for (Instance point : points) {
            var b = point.mesh.bounds();
            var p = point.origin;
            if (frustum.isVisible(new AABB(p.x() + b.minX(), p.y() + b.minY(), p.z() + b.minZ(),
                    p.x() + b.maxX(), p.y() + b.maxY(), p.z() + b.maxZ())))
                submitted.add(point);
        }
        drawBatch(submitted, camera, view, Types.POINTS);
        return List.copyOf(submitted);
    }

    /** Preview color never enters the selection list and never changes scene depth. */
    void preview(List<Instance> instances, Vec3 camera, Matrix4f view) {
        drawBatch(instances, camera, view, Types.PREVIEW);
    }

    /**
     * Re-rasterize exactly the displayed geometry at one scissored pixel. Neither attachment
     * is written. Equal quantized depths choose the last instance in actual draw order.
     */
    Optional<Instance> pick(List<Instance> displayed, Vec3 camera, Matrix4f view) {
        RenderSystem.assertOnRenderThread();
        if (displayed.isEmpty() || queryActive()) return Optional.empty();
        int query = GL15.glGenQueries();
        if (query == 0) return Optional.empty();
        try {
            if (!covered(query, displayed, camera, view)) return Optional.empty();
            int first = 0, end = displayed.size();
            while (end - first > 1) {
                int middle = first + (end - first + 1) / 2;
                if (covered(query, displayed.subList(middle, end), camera, view)) first = middle;
                else end = middle;
            }
            return Optional.of(displayed.get(first));
        } finally {
            GL15.glDeleteQueries(query);
        }
    }

    private boolean covered(int query, List<Instance> points, Vec3 camera, Matrix4f view) {
        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
        try {
            if (GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != query)
                throw new IllegalStateException("Point coverage query did not start");
            drawBatch(points, camera, view, Types.PICK);
        } finally {
            if (GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) == query)
                GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
        }
        int samples = GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT);
        if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0)
            throw new IllegalStateException("Point coverage query has no result");
        return samples != 0;
    }

    private static boolean queryActive() {
        if (GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0) return true;
        var capabilities = GL.getCapabilities();
        if ((capabilities.OpenGL33 || capabilities.GL_ARB_occlusion_query2) && GL15.glGetQueryi(GL33.GL_ANY_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0) return true;
        return (capabilities.OpenGL43 || capabilities.GL_ARB_ES3_compatibility) && GL15.glGetQueryi(GL43.GL_ANY_SAMPLES_PASSED_CONSERVATIVE, GL15.GL_CURRENT_QUERY) != 0;
    }

    private void drawBatch(List<Instance> points, Vec3 camera, Matrix4f view, RenderType type) {
        RenderSystem.assertOnRenderThread();
        if (points.isEmpty()) return;
        if (builder == null) builder = new BufferBuilder(65536);
        try {
            builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (Instance point : points) {
                var p = point.origin;
                double x = p.x() - camera.x, y = p.y() - camera.y, z = p.z() - camera.z;
                for (var triangle : point.mesh.triangles()) {
                    int tint = shaded(point.argb, triangle);
                    vertex(builder, view, triangle.a(), x, y, z, tint);
                    vertex(builder, view, triangle.b(), x, y, z, tint);
                    vertex(builder, view, triangle.c(), x, y, z, tint);
                }
            }
            type.end(builder, VertexSorting.DISTANCE_TO_ORIGIN);
        } finally {
            // Includes partially emitted triangles and an upload which throws after end().
            ((BufferBuilderAccess) builder).fpsmatch$reset();
            builder.discard();
            builder.unsetDefaultColor();
        }
    }

    private static void vertex(VertexConsumer buffer, Matrix4f view, Position p,
                               double x, double y, double z, int tint) {
        buffer.vertex(view, (float) (x + p.x()), (float) (y + p.y()), (float) (z + p.z()))
                .color((tint >>> 16) & 255, (tint >>> 8) & 255, tint & 255, 255).endVertex();
    }

    /** A fixed world-space light makes the octagonal volume legible without textures or glow. */
    private static int shaded(int tint, WorldPointMesh.Triangle t) {
        double ax = t.b().x() - t.a().x(), ay = t.b().y() - t.a().y(), az = t.b().z() - t.a().z();
        double bx = t.c().x() - t.a().x(), by = t.c().y() - t.a().y(), bz = t.c().z() - t.a().z();
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double length = Math.hypot(Math.hypot(nx, ny), nz);
        double light = length == 0 ? 1 : .58 + .42 * Math.max(0, (nx * -.36 + ny * .8 + nz * -.48) / length);
        return ((int) (((tint >>> 16) & 255) * light) << 16) | ((int) (((tint >>> 8) & 255) * light) << 8) | (int) ((tint & 255) * light);
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        BufferBuilder owned = builder;
        builder = null;
        if (owned != null) {
            try {
                owned.discard();
            } finally {
                MemoryUtil.getAllocator(false).free(MemoryUtil.memAddress0(((BufferBuilderAccess) owned).fpsmatch$buffer()));
            }
        }
    }

    /** Lazy so pure instance/cache tests do not initialize the GL render types. */
    private static final class Types extends RenderType {

        private Types() {
            super("workbench_point_factory", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES,
                    65536, false, false, () -> {}, () -> {});
        }

        private static final RenderType POINTS = create("fpsmatch_workbench_world_points",
                DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 65536, false, false,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(NO_TRANSPARENCY).setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(CULL).setWriteMaskState(COLOR_DEPTH_WRITE).setOutputState(MAIN_TARGET)
                        .createCompositeState(false));
        private static final RenderType PICK = create("fpsmatch_workbench_world_point_pick",
                DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 65536, false, false,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(NO_TRANSPARENCY).setDepthTestState(EQUAL_DEPTH_TEST)
                        .setCullState(CULL).setWriteMaskState(new WriteMaskStateShard(false, false))
                        .setOutputState(MAIN_TARGET).createCompositeState(false));
        private static final RenderType PREVIEW = create("fpsmatch_workbench_world_area_preview",
                DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 65536, false, false,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(NO_TRANSPARENCY).setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(CULL).setWriteMaskState(COLOR_WRITE).setOutputState(MAIN_TARGET)
                        .createCompositeState(false));
    }
}
