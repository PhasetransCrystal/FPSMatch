package net.ptcrys.fpsmatch.editor.common.client.workbench;

import static org.junit.jupiter.api.Assertions.*;

import net.ptcrys.fpsmatch.editor.common.client.viewport.math.FrameMatrix;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.MatrixMath;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import org.junit.jupiter.api.Test;

class WorkbenchViewportDepthProjectionTest {
    private static final Position ZERO = new Position(0, 0, 0);
    private static final FrameMatrix PERSPECTIVE = matrix(new double[][] {
            {.5, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, -1.25, -2.25}, {0, 0, -1, 0}});

    @Test void referenceProjectionUsesCameraRelativeFloatVerticesAndActualView() {
        assertEquals(.84375, WorkbenchViewportDepthProjection.projectDepth(MatrixMath.identity(), PERSPECTIVE, ZERO,
                new Position(0, 0, -4)).orElseThrow());
        var camera = new Position(1e16, 32, -1e16);
        assertEquals(.84375, WorkbenchViewportDepthProjection.projectDepth(MatrixMath.identity(), PERSPECTIVE, camera,
                new Position(1e16, 32, -1e16 - 4)).orElseThrow());
        var rotation = matrix(new double[][] {{0, 0, 1, 0}, {0, 1, 0, 0}, {-1, 0, 0, 0}, {0, 0, 0, 1}});
        assertEquals(.84375, WorkbenchViewportDepthProjection.projectDepth(rotation, PERSPECTIVE, ZERO,
                new Position(4, 0, 0)).orElseThrow());
        var infiniteFar = matrix(new double[][] {{.5, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, -1, -2}, {0, 0, -1, 0}});
        assertEquals(1 - Math.scalb(1.0, -24), WorkbenchViewportDepthProjection.projectDepth(MatrixMath.identity(), infiniteFar, ZERO,
                new Position(0, 0, -16777217)).orElseThrow(), "vertex casts to float before projection");
    }

    @Test void referenceProjectionRejectsClippedBehindAndUnrepresentableInputs() {
        assertEquals(0, WorkbenchViewportDepthProjection.projectDepth(MatrixMath.identity(), PERSPECTIVE, ZERO,
                new Position(0, 0, -1)).orElseThrow());
        for (var hit : new Position[] {new Position(0, 0, 1), new Position(0, 0, -.5),
                new Position(0, 0, -9), new Position(9, 0, -4), new Position(0, 5, -4)})
            assertTrue(WorkbenchViewportDepthProjection.projectDepth(MatrixMath.identity(), PERSPECTIVE, ZERO, hit).isEmpty());
        assertTrue(WorkbenchViewportDepthProjection.projectDepth(MatrixMath.identity(), PERSPECTIVE,
                new Position(-Double.MAX_VALUE, 0, 0), new Position(Double.MAX_VALUE, 0, -4)).isEmpty());
        var invalidView = matrix(new double[][] {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 1, 1}});
        assertTrue(WorkbenchViewportDepthProjection.projectDepth(invalidView, PERSPECTIVE, ZERO, new Position(0, 0, -4)).isEmpty());
        var notActualFloat = matrix(new double[][] {{.1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}});
        assertTrue(WorkbenchViewportDepthProjection.projectDepth(notActualFloat, PERSPECTIVE, ZERO, new Position(0, 0, -4)).isEmpty());
    }

    private static FrameMatrix matrix(double[][] rows) {
        double[] values = new double[16];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++)
            values[column * 4 + row] = rows[row][column];
        return new FrameMatrix(values);
    }
}
