package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.client.viewport.math.FrameMatrix;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.OptionalDouble;

/** Host-side float projection adapter; it performs no GL calls or readback. */
public final class WorkbenchViewportDepthProjection {

    private WorkbenchViewportDepthProjection() {}

    /**
     * Reference projection through the renderer's float vertex path.
     * Requires the actual finite float matrix entries and an affine view matrix. Camera
     * subtraction happens in double before the vertex cast. JOML itself performs the view
     * multiplication, honoring its runtime FMA configuration; the projection multiplication
     * is a CPU reference, not a claim that GPU shader contraction/interpolation is identical.
     * The caller must also retain the correct source/frame and test the actual sampled pixel.
     * An already rounded world hit cannot recover a lost local offset at extreme origins.
     */
    public static OptionalDouble projectDepth(FrameMatrix view, FrameMatrix projection, Position camera, Position hit) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(projection);
        Objects.requireNonNull(camera);
        Objects.requireNonNull(hit);
        if (view.get(3, 0) != 0 || view.get(3, 1) != 0 || view.get(3, 2) != 0 || view.get(3, 3) != 1)
            return OptionalDouble.empty();
        Matrix4f viewFloat = actualFloatMatrix(view), projectionFloat = actualFloatMatrix(projection);
        if (viewFloat == null || projectionFloat == null) return OptionalDouble.empty();
        float x = (float) (hit.x() - camera.x()), y = (float) (hit.y() - camera.y()), z = (float) (hit.z() - camera.z());
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return OptionalDouble.empty();
        Vector4f eye = new Vector4f(x, y, z, 1).mul(viewFloat);
        if (!finite(eye)) return OptionalDouble.empty();
        // VertexConsumer submits eye-space XYZ; the position shader supplies W=1 again.
        Vector4f clip = new Vector4f(eye.x, eye.y, eye.z, 1).mul(projectionFloat);
        if (!finite(clip) || !(clip.w > 0) || clip.x < -clip.w || clip.x > clip.w || clip.y < -clip.w || clip.y > clip.w || clip.z < -clip.w || clip.z >= clip.w)
            return OptionalDouble.empty();
        double depth = ((double) clip.z / clip.w) * .5 + .5;
        return Double.isFinite(depth) && depth >= 0 && depth < 1 ? OptionalDouble.of(depth) : OptionalDouble.empty();
    }

    private static Matrix4f actualFloatMatrix(FrameMatrix matrix) {
        double[] values = matrix.columnMajor();
        float[] floats = new float[16];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = (float) values[i];
            if (!Float.isFinite(floats[i]) || (double) floats[i] != values[i]) return null;
        }
        return new Matrix4f().set(floats);
    }

    private static boolean finite(Vector4f vector) {
        return Float.isFinite(vector.x) && Float.isFinite(vector.y) && Float.isFinite(vector.z) && Float.isFinite(vector.w);
    }
}
