package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;
import org.junit.jupiter.api.Test;

class WorldPointMeshTest {
    private static final Position ZERO = new Position(0, 0, 0);
    private static final double ARROW_VOLUME = (.06 * .13 + .22 * .16 / 2) * .016;
    private static final double DOT_VOLUME = 8 * .05 * .05 * Math.sin(Math.PI / 8) * .016;

    @Test void directionlessPointIsOnlyASmallDotCenteredOnTheStoredFoot() {
        var mesh = WorldPointMesh.build(Optional.empty());
        var bounds = mesh.bounds();
        assertEquals(-.05, bounds.minX(), 1e-12); assertEquals(.05, bounds.maxX(), 1e-12);
        assertEquals(-.05, bounds.minZ(), 1e-12); assertEquals(.05, bounds.maxZ(), 1e-12);
        assertEquals(.012, bounds.minY()); assertEquals(.028, bounds.maxY());
        assertTrue(vertices(mesh).contains(new Position(0, .012, 0)));
        assertTrue(vertices(mesh).contains(new Position(0, .028, 0)));
        assertDistance(mesh, new Position(2, .02, 0), new Vector3(-1, 0, 0), 1.95);
        assertDistance(mesh, new Position(0, -1, 0), new Vector3(0, 1, 0), 1.012);
        assertMiss(mesh, new Position(2, 1, 0), new Vector3(-1, 0, 0));
    }

    @Test void markerHasTheCompactFootprintAndDotIndependentOfTheArrow() {
        var mesh = marker(0, 0, 1);
        var bounds = mesh.bounds();
        assertEquals(-.11, bounds.minX()); assertEquals(.11, bounds.maxX());
        assertEquals(-.05, bounds.minZ(), 1e-12); assertEquals(.40, bounds.maxZ());
        assertEquals(.45, bounds.maxZ() - bounds.minZ(), 1e-12);
        assertEquals(.22, bounds.maxX() - bounds.minX(), 1e-12);
        assertEquals(.012, bounds.minY()); assertEquals(.028, bounds.maxY());
        assertTrue(vertices(mesh).contains(new Position(0, .012, .40)));
        assertTrue(vertices(mesh).contains(new Position(0, .028, .40)));
        assertTrue(mesh.triangles().containsAll(WorldPointMesh.build(Optional.empty()).triangles()));
        assertDistance(mesh, new Position(0, 2, 0), new Vector3(0, -1, 0), 1.972);
        assertDistance(mesh, new Position(0, 2, .16), new Vector3(0, -1, 0), 1.972);
    }

    @Test void cardinalAndDiagonalHeadingsRotateOnlyTheArrowAroundTheStoredFoot() {
        double diagonal = 1 / Math.sqrt(2);
        double[][] cases = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {diagonal, diagonal}, {-diagonal, diagonal}, {diagonal, -diagonal}, {-diagonal, -diagonal}};
        var dot = WorldPointMesh.build(Optional.empty());
        for (var heading : cases) {
            var mesh = marker(heading[0], 0, heading[1]);
            Position expected = new Position(.40 * heading[0], .028, .40 * heading[1]);
            assertTrue(vertices(mesh).stream().anyMatch(p -> length(subtract(p, expected)) < 1e-12));
            assertTrue(mesh.triangles().containsAll(dot.triangles()), "the foot dot must not move with heading");
            for (var p : vertices(mesh)) {
                double along = p.x() * heading[0] + p.z() * heading[1];
                double across = p.x() * heading[1] - p.z() * heading[0];
                assertTrue(along >= -.05 - 1e-12 && along <= .40 + 1e-12);
                assertTrue(Math.abs(across) <= .11 + 1e-12);
                assertTrue(p.y() >= .012 && p.y() <= .028);
            }
        }
    }

    @Test void unexpectedVerticalInputDefensivelyKeepsTheDotWithoutInventingHeading() {
        assertEquals(marker(1, 0, 1), marker(1, 10, 1));
        assertEquals(marker(1, 0, 1), marker(1, -10, 1));
        var dot = WorldPointMesh.build(Optional.empty());
        assertEquals(dot, marker(0, 1, 0));
        assertEquals(dot, marker(0, -1, 0));
    }

    @Test void everyNonzeroHorizontalComponentRetainsItsHeadingEvenAtSubnormalScale() {
        assertEquals(marker(1, 0, 1), marker(Double.MIN_VALUE, 1, Double.MIN_VALUE));
        assertEquals(marker(-1, 0, 0), marker(-Double.MIN_VALUE, 1, 0));
        assertEquals(marker(0, 0, -1), marker(0, -1, -Double.MIN_VALUE));
        assertEquals(marker(1, 0, 1), marker(1e-200, 1, 1e-200));
    }

    @Test void dotAndArrowAreSeparateClosedOutwardSolidsWithTheExpectedVolumes() {
        assertClosedOutwardComponents(WorldPointMesh.build(Optional.empty()), DOT_VOLUME);
        for (var d : directions()) {
            var mesh = WorldPointMesh.build(Optional.of(d));
            if (d.x() == 0 && d.z() == 0) assertClosedOutwardComponents(mesh, DOT_VOLUME);
            else assertClosedOutwardComponents(mesh, DOT_VOLUME, ARROW_VOLUME);
        }
    }

    @Test void intersectionsReachTheActualDotTipTailShaftAndHeadSides() {
        var mesh = marker(0, 0, 1);
        assertDistance(mesh, new Position(0, .02, 2), new Vector3(0, 0, -1), 1.60);
        assertDistance(mesh, new Position(0, .02, .08), new Vector3(0, 0, 1), .03);
        assertDistance(mesh, new Position(2, .02, .16), new Vector3(-1, 0, 0), 1.97);
        assertDistance(mesh, new Position(2, .02, .32), new Vector3(-1, 0, 0), 1.945);
        assertDistance(mesh, new Position(2, .02, 0), new Vector3(-1, 0, 0), 1.95);
        assertMiss(mesh, new Position(2, 1.62, 0), new Vector3(-1, 0, 0));
    }

    @Test void concaveShouldersAndSpaceOutsideTheHeadAreNotFilledByTheCaps() {
        var mesh = marker(0, 0, 1);
        for (double x : new double[] {-.07, .07}) {
            assertMiss(mesh, new Position(x, 2, .16), new Vector3(0, -1, 0));
            assertMiss(mesh, new Position(x, 2, .36), new Vector3(0, -1, 0));
            assertDistance(mesh, new Position(x, 2, .27), new Vector3(0, -1, 0), 1.972);
        }
    }

    @Test void gapBetweenTheActualFootAndDirectionArrowStaysEmptyForEveryHeading() {
        double diagonal = 1 / Math.sqrt(2);
        double[][] headings = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {diagonal, diagonal}};
        for (var h : headings) {
            var mesh = marker(h[0], 0, h[1]);
            assertMiss(mesh, new Position(.08 * h[0], 2, .08 * h[1]), new Vector3(0, -1, 0));
        }
        assertDistance(marker(0, 0, 1), new Position(0, .02, .08), new Vector3(0, 0, -1), .03);
    }

    @Test void finiteRayIntervalAndFacingMatchVisibleTriangles() {
        var mesh = marker(0, 0, 1);
        Position origin = new Position(0, .02, 2);
        assertTrue(WorldPointMesh.intersect(mesh, ZERO, ray(origin, new Vector3(0, 0, -1), 1.599)).isEmpty());
        assertTrue(WorldPointMesh.intersect(mesh, ZERO, ray(origin, new Vector3(0, 0, -1), 1.601)).isPresent());
        assertMiss(mesh, origin, new Vector3(0, 0, 1));
        assertDistance(mesh, new Position(0, .028, 0), new Vector3(0, -1, 0), 0);
        assertDistance(mesh, new Position(0, .028001, 0), new Vector3(0, -1, 0), .000001);
        assertMiss(mesh, new Position(0, .02, .16), new Vector3(0, 1, 0));
        assertMiss(mesh, new Position(0, .02, 0), new Vector3(0, 1, 0));
    }

    @Test void sharedCapEdgesAndOutlineVerticesDoNotLeavePickCracks() {
        var mesh = marker(0, 0, 1);
        for (var p : vertices(mesh)) {
            if (p.y() == .028) assertDistance(mesh, new Position(p.x(), 2, p.z()), new Vector3(0, -1, 0), 1.972);
        }
        assertDistance(mesh, new Position(0, 2, .28), new Vector3(0, -1, 0), 1.972);
        assertDistance(mesh, new Position(.015, 2, .195), new Vector3(0, -1, 0), 1.972);
        assertDistance(mesh, new Position(0, 2, 0), new Vector3(0, -1, 0), 1.972);
    }

    @Test void subtractingWorldTranslationBeforeIntersectingKeepsSubBlockGeometry() {
        var mesh = marker(0, 0, 1);
        var world = new Position(1e16, -60, -1e16);
        var ray = ray(new Position(1e16 + 4, -59.98, -1e16), new Vector3(-1, 0, 0), 8);
        assertEquals(3.95, WorldPointMesh.intersect(mesh, world, ray).orElseThrow(), 1e-12);
        assertEquals(3.95, WorldPointMesh.intersect(mesh, ZERO,
                ray(new Position(4, .02, 0), new Vector3(-1, 0, 0), 8)).orElseThrow(), 1e-12);
        assertTrue(WorldPointMesh.intersect(mesh, new Position(-Double.MAX_VALUE, 0, 0),
                ray(new Position(Double.MAX_VALUE, 0, 0), new Vector3(-1, 0, 0), 8)).isEmpty());
    }

    @Test void meshSnapshotsAreImmutableAndBoundsCannotHideVertices() {
        var mesh = marker(0, 0, 1);
        var mutable = new ArrayList<>(mesh.triangles());
        var copy = new WorldPointMesh.Mesh(mutable, mesh.bounds());
        mutable.clear();
        assertFalse(copy.triangles().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> copy.triangles().clear());
        assertThrows(IllegalArgumentException.class, () -> new WorldPointMesh.Mesh(mesh.triangles(),
                new WorldPointMesh.Bounds(0, 0, 0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new WorldPointMesh.Bounds(1, 0, 0, -1, 0, 0));
    }

    private record EdgeUse(int triangle, Position from, Position to) {}

    private static void assertClosedOutwardComponents(WorldPointMesh.Mesh mesh, double... expectedVolumes) {
        Map<Set<Position>, List<EdgeUse>> edges = new HashMap<>();
        var faces = mesh.triangles();
        for (int i = 0; i < faces.size(); i++) {
            var t = faces.get(i);
            assertTrue(length(cross(subtract(t.b(), t.a()), subtract(t.c(), t.a()))) > 1e-12, "nondegenerate face");
            addEdge(edges, i, t.a(), t.b()); addEdge(edges, i, t.b(), t.c()); addEdge(edges, i, t.c(), t.a());
        }
        List<Set<Integer>> neighbors = new ArrayList<>();
        for (int i = 0; i < faces.size(); i++) neighbors.add(new HashSet<>());
        for (var uses : edges.values()) {
            assertEquals(2, uses.size(), "each edge closes exactly two faces");
            var a = uses.get(0); var b = uses.get(1);
            assertEquals(a.from(), b.to()); assertEquals(a.to(), b.from());
            neighbors.get(a.triangle()).add(b.triangle()); neighbors.get(b.triangle()).add(a.triangle());
        }
        Set<Integer> visited = new HashSet<>();
        List<Double> volumes = new ArrayList<>();
        for (int first = 0; first < faces.size(); first++) {
            if (visited.contains(first)) continue;
            double volume = 0;
            var queue = new ArrayDeque<Integer>(); queue.add(first);
            while (!queue.isEmpty()) {
                int face = queue.remove();
                if (!visited.add(face)) continue;
                var t = faces.get(face);
                volume += dot(t.a(), cross(t.b(), t.c())) / 6;
                queue.addAll(neighbors.get(face));
            }
            assertTrue(volume > 0, "each component is outward wound");
            volumes.add(volume);
        }
        assertEquals(expectedVolumes.length, volumes.size(), "the dot and arrow remain separate closed components");
        Arrays.sort(expectedVolumes); volumes.sort(Double::compare);
        for (int i = 0; i < expectedVolumes.length; i++)
            assertEquals(expectedVolumes[i], volumes.get(i), 1e-12, "exact intended footprint and thickness");
    }

    private static void addEdge(Map<Set<Position>, List<EdgeUse>> edges, int triangle, Position a, Position b) {
        edges.computeIfAbsent(Set.of(a, b), ignored -> new ArrayList<>()).add(new EdgeUse(triangle, a, b));
    }
    private static Set<Position> vertices(WorldPointMesh.Mesh mesh) {
        Set<Position> result = new HashSet<>();
        for (var t : mesh.triangles()) { result.add(t.a()); result.add(t.b()); result.add(t.c()); }
        return result;
    }
    private static List<SpatialDocument.Direction> directions() {
        return List.of(new SpatialDocument.Direction(1, 0, 0), new SpatialDocument.Direction(-1, 0, 0),
                new SpatialDocument.Direction(0, 1, 0), new SpatialDocument.Direction(0, -1, 0),
                new SpatialDocument.Direction(0, 0, 1), new SpatialDocument.Direction(0, 0, -1),
                new SpatialDocument.Direction(1, 2, 3), new SpatialDocument.Direction(Double.MIN_VALUE, 1, Double.MIN_VALUE));
    }
    private static WorldPointMesh.Mesh marker(double x, double y, double z) {
        return WorldPointMesh.build(Optional.of(new SpatialDocument.Direction(x, y, z)));
    }
    private static void assertMiss(WorldPointMesh.Mesh mesh, Position origin, Vector3 direction) {
        assertTrue(WorldPointMesh.intersect(mesh, ZERO, ray(origin, direction, 8)).isEmpty());
    }
    private static void assertDistance(WorldPointMesh.Mesh mesh, Position origin, Vector3 direction, double expected) {
        assertEquals(expected, WorldPointMesh.intersect(mesh, ZERO, ray(origin, direction, 8)).orElseThrow(), 1e-10);
    }
    private static Ray3 ray(Position origin, Vector3 direction, double limit) { return new Ray3(1, origin, direction, limit); }
    private static Position subtract(Position a, Position b) { return new Position(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()); }
    private static Position cross(Position a, Position b) {
        return new Position(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }
    private static double dot(Position a, Position b) { return a.x() * b.x() + a.y() * b.y() + a.z() * b.z(); }
    private static double length(Position p) { return Math.hypot(Math.hypot(p.x(), p.y()), p.z()); }
}
