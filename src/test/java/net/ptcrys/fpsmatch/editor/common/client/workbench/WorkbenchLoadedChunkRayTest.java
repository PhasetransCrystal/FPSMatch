package net.ptcrys.fpsmatch.editor.common.client.workbench;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchLoadedChunkRayTest {
    private record Chunk(int x, int z) {}

    @Test void diagonalCannotJumpOverBriefUnloadedCornerInterval() {
        double diagonal = Math.sqrt(.5);
        var visited = new ArrayList<Chunk>();
        var limit = WorkbenchLoadedChunkRay.trace(15.75, 15.5, diagonal, diagonal, 10, (x, z) -> {
            visited.add(new Chunk(x, z));
            return x != 1 || z != 0;
        });
        assertTrue(limit.unloaded());
        assertEquals(.25 / diagonal, limit.distance());
        assertEquals(List.of(new Chunk(0, 0), new Chunk(1, 0)), visited);
        assertTrue(.30 < limit.distance(), "a hit immediately before the gap remains reachable");
        assertTrue(.40 > limit.distance(), "the missing interval and anything behind it are excluded");
    }

    @Test void negativeCoordinatesUseFloorAndKeepTheNearPrefix() {
        double diagonal = -Math.sqrt(.5);
        var visited = new ArrayList<Chunk>();
        var limit = WorkbenchLoadedChunkRay.trace(-15.75, -15.5, diagonal, diagonal, 10, (x, z) -> {
            visited.add(new Chunk(x, z));
            return x != -2 || z != -1;
        });
        assertEquals(.25 / -diagonal, limit.distance());
        assertTrue(limit.unloaded());
        assertEquals(List.of(new Chunk(-1, -1), new Chunk(-2, -1)), visited);
    }

    @Test void axisAlignedAndZeroComponentsDoNotInventCrossings() {
        var x = WorkbenchLoadedChunkRay.trace(15.9, -2, 1, 0, 20, (cx, cz) -> cx == 0 && cz == -1);
        assertEquals(16 - 15.9, x.distance());
        assertTrue(x.unloaded());
        var z = WorkbenchLoadedChunkRay.trace(-2, -15.9, -0.0, -1, 20, (cx, cz) -> cx == -1 && cz == -1);
        assertEquals(16 - 15.9, z.distance());
        assertTrue(z.unloaded());
        assertEquals(new WorkbenchLoadedChunkRay.Limit(256, false),
                WorkbenchLoadedChunkRay.trace(2, 3, 0, 0, 256, (cx, cz) -> cx == 0 && cz == 0));
    }

    @Test void negativeTravelCrossesExactBoundaryImmediately() {
        var visited = new ArrayList<Chunk>();
        var limit = WorkbenchLoadedChunkRay.trace(0, -16, -1, 0, 4, (x, z) -> {
            visited.add(new Chunk(x, z));
            return x == 0;
        });
        assertEquals(0, limit.distance());
        assertTrue(limit.unloaded());
        assertEquals(List.of(new Chunk(0, -1), new Chunk(-1, -1)), visited);
    }

    @Test void exactCornerOnlyEntersDiagonalAndStopsAtFarBoundary() {
        var visited = new ArrayList<Chunk>();
        double diagonal = Math.sqrt(.5), boundary = 1 / diagonal;
        var limit = WorkbenchLoadedChunkRay.trace(15, 15, diagonal, diagonal, boundary, (x, z) -> {
            visited.add(new Chunk(x, z));
            return x == 0 && z == 0;
        });
        assertEquals(boundary, limit.distance());
        assertTrue(limit.unloaded());
        assertEquals(List.of(new Chunk(0, 0), new Chunk(1, 1)), visited);
    }

    @Test void allLoadedAndUnloadedOriginHaveDistinctResults() {
        var visited = new ArrayList<Chunk>();
        var full = WorkbenchLoadedChunkRay.trace(-17, 4, 1, 0, 50, (x, z) -> {
            visited.add(new Chunk(x, z));
            return true;
        });
        assertEquals(new WorkbenchLoadedChunkRay.Limit(50, false), full);
        assertEquals(List.of(new Chunk(-2, 0), new Chunk(-1, 0), new Chunk(0, 0), new Chunk(1, 0), new Chunk(2, 0)), visited);
        assertEquals(new WorkbenchLoadedChunkRay.Limit(0, true),
                WorkbenchLoadedChunkRay.trace(1, 1, 1, 0, 20, (x, z) -> false));
    }

    @Test void rejectsNonFiniteOrUnboundedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchLoadedChunkRay.trace(Double.NaN, 0, 1, 0, 10, (x, z) -> true));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchLoadedChunkRay.trace(0, 0, Double.POSITIVE_INFINITY, 0, 10, (x, z) -> true));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchLoadedChunkRay.trace(0, 0, 1, 0, 257, (x, z) -> true));
    }
}
