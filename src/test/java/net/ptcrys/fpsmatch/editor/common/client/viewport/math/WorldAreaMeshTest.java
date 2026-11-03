package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import org.junit.jupiter.api.Test;

class WorldAreaMeshTest {
    private static final Position ZERO = new Position(0, 0, 0);
    private static final double R = .02;

    @Test void outlineUsesSuppliedPlanesWithoutBlockExpansionAndPreservesInputOrder() {
        var first = new Position(-3, 5, 2);
        var second = new Position(1, 2, 0);
        var mesh = WorldAreaMesh.outline(first, second);
        assertEquals(new WorldPointMesh.Bounds(-3.02, 1.98, -.02, 1.02, 5.02, 2.02), mesh.bounds());
        assertEquals(mesh, WorldAreaMesh.outline(second, first));
        assertEquals(new Position(-3, 5, 2), first);
        assertEquals(new Position(1, 2, 0), second);
        assertClosedUnique(mesh);
        assertEquals(16 * R * R * (4 + 3 + 2) - 32 * R * R * R, volume(mesh), 1e-12);
    }

    @Test void allSixFaceInteriorsAndTheBoxInteriorStayEmpty() {
        var mesh = WorldAreaMesh.outline(ZERO, new Position(4, 3, 2));
        assertMiss(mesh, new Position(2, 10, 1), new Vector3(0, -1, 0));
        assertMiss(mesh, new Position(2, -10, 1), new Vector3(0, 1, 0));
        assertMiss(mesh, new Position(10, 1.5, 1), new Vector3(-1, 0, 0));
        assertMiss(mesh, new Position(-10, 1.5, 1), new Vector3(1, 0, 0));
        assertMiss(mesh, new Position(2, 1.5, 10), new Vector3(0, 0, -1));
        assertMiss(mesh, new Position(2, 1.5, -10), new Vector3(0, 0, 1));
        assertMiss(mesh, new Position(2, 1.5, 1), new Vector3(0, 1, 0));
        assertDistance(mesh, new Position(2, 10, 0), new Vector3(0, -1, 0), 6.98);
        assertDistance(mesh, new Position(10, 1.5, 0), new Vector3(-1, 0, 0), 5.98);
        assertDistance(mesh, new Position(0, 0, 10), new Vector3(0, 0, -1), 7.98);
    }

    @Test void planarAreasHaveOneClosedPerimeterWithoutDuplicateCoplanarFaces() {
        for (var end : List.of(new Position(4, 3, 0), new Position(4, 0, 3), new Position(0, 4, 3))) {
            var mesh = WorldAreaMesh.outline(ZERO, end);
            assertClosedUnique(mesh);
            assertEquals(8 * R * R * 7, volume(mesh), 1e-12);
        }
        var horizontal = WorldAreaMesh.outline(ZERO, new Position(4, 0, 3));
        assertMiss(horizontal, new Position(2, 10, 1.5), new Vector3(0, -1, 0));
        assertDistance(horizontal, new Position(2, 10, 0), new Vector3(0, -1, 0), 9.98);
    }

    @Test void lineAreasHaveOneClosedRodOnEveryAxis() {
        for (var end : List.of(new Position(4, 0, 0), new Position(0, 4, 0), new Position(0, 0, 4))) {
            var mesh = WorldAreaMesh.outline(ZERO, end);
            assertClosedUnique(mesh);
            assertEquals((4 + 2 * R) * 4 * R * R, volume(mesh), 1e-12);
            assertEquals(mesh, WorldAreaMesh.outline(end, ZERO));
        }
        assertDistance(WorldAreaMesh.outline(ZERO, new Position(4, 0, 0)),
                new Position(2, 10, 0), new Vector3(0, -1, 0), 9.98);
    }

    @Test void coincidentEndpointsMakeOneSmallCubeWithoutZeroAreaTriangles() {
        var mesh = WorldAreaMesh.outline(ZERO, ZERO);
        assertEquals(12, mesh.triangles().size());
        assertEquals(new WorldPointMesh.Bounds(-R, -R, -R, R, R, R), mesh.bounds());
        assertClosedUnique(mesh);
        assertEquals(8 * R * R * R, volume(mesh), 1e-12);
        assertEquals(mesh, WorldAreaMesh.outline(new Position(-0.0, 0, -0.0), ZERO));
        assertDistance(mesh, new Position(0, 1, 0), new Vector3(0, -1, 0), .98);
    }

    @Test void overlappingAndTouchingRodBandsDoNotProduceInvertedHolesOrInternalFaces() {
        for (double extent : new double[] {.01, .04}) {
            var mesh = WorldAreaMesh.outline(ZERO, new Position(extent, extent, extent));
            assertClosedUnique(mesh);
            assertEquals(Math.pow(extent + 2 * R, 3), volume(mesh), 1e-12);
            assertDistance(mesh, new Position(extent / 2, 1, extent / 2),
                    new Vector3(0, -1, 0), 1 - extent - R);
        }
    }

    @Test void completeIntegerRangeNeedsOnlyBoundedGeometry() {
        var mesh = WorldAreaMesh.outline(new Position(Integer.MIN_VALUE, -64, Integer.MAX_VALUE),
                new Position(Integer.MAX_VALUE, 320, Integer.MIN_VALUE));
        assertEquals(Integer.MIN_VALUE - R, mesh.bounds().minX());
        assertEquals(Integer.MAX_VALUE + R, mesh.bounds().maxX());
        assertTrue(mesh.triangles().size() <= 27 * 6 * 2);
        assertClosedUnique(mesh);
    }

    @Test void unrepresentableFixedWidthIsRejectedInsteadOfSilentlyDisappearing() {
        assertThrows(IllegalArgumentException.class, () -> WorldAreaMesh.outline(ZERO, new Position(1e16, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> WorldAreaMesh.outline(new Position(-Double.MAX_VALUE, 0, 0), ZERO));
        assertThrows(NullPointerException.class, () -> WorldAreaMesh.outline(null, ZERO));
        assertThrows(NullPointerException.class, () -> WorldAreaMesh.outline(ZERO, null));
    }

    @Test void handleIsAnOutwardOctahedronCenteredAtTheCallerSuppliedOrigin() {
        var mesh = WorldAreaMesh.handle();
        assertEquals(8, mesh.triangles().size());
        assertEquals(new WorldPointMesh.Bounds(-.1, -.1, -.1, .1, .1, .1), mesh.bounds());
        assertClosedUnique(mesh);
        assertEquals(4 * .1 * .1 * .1 / 3, volume(mesh), 1e-12);
        assertDistance(mesh, new Position(0, 1, 0), new Vector3(0, -1, 0), .9);
        assertMiss(mesh, new Position(.08, 1, .08), new Vector3(0, -1, 0));
        var world = new Position(1e16, -60, -1e16);
        assertEquals(3.9, WorldPointMesh.intersect(mesh, world,
                new Ray3(1, new Position(1e16 + 4, -60, -1e16), new Vector3(-1, 0, 0), 8)).orElseThrow(), 1e-12);
    }

    @Test void areaMeshesReuseImmutableMeshSnapshots() {
        assertThrows(UnsupportedOperationException.class, () -> WorldAreaMesh.handle().triangles().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> WorldAreaMesh.outline(ZERO, new Position(1, 2, 3)).triangles().clear());
    }

    private record Edge(Position from, Position to) {}

    private static void assertClosedUnique(WorldPointMesh.Mesh mesh) {
        Set<Set<Position>> triangles = new HashSet<>();
        Map<Set<Position>, List<Edge>> edges = new HashMap<>();
        for (var triangle : mesh.triangles()) {
            var a = triangle.a(); var b = triangle.b(); var c = triangle.c();
            var normal = cross(subtract(b, a), subtract(c, a));
            assertTrue(Math.hypot(Math.hypot(normal.x(), normal.y()), normal.z()) > 0, "nondegenerate face");
            assertTrue(triangles.add(Set.of(a, b, c)), "no duplicate surface triangle in either winding");
            for (var edge : List.of(new Edge(a, b), new Edge(b, c), new Edge(c, a)))
                edges.computeIfAbsent(Set.of(edge.from(), edge.to()), ignored -> new ArrayList<>()).add(edge);
        }
        for (var uses : edges.values()) {
            assertEquals(2, uses.size(), "closed surface with no internal or T-junction edges");
            assertEquals(uses.get(0).from(), uses.get(1).to());
            assertEquals(uses.get(0).to(), uses.get(1).from());
        }
    }

    private static double volume(WorldPointMesh.Mesh mesh) {
        var reference = mesh.triangles().get(0).a();
        double result = 0;
        for (var t : mesh.triangles())
            result += dot(subtract(t.a(), reference), cross(subtract(t.b(), reference), subtract(t.c(), reference))) / 6;
        return result;
    }
    private static void assertMiss(WorldPointMesh.Mesh mesh, Position origin, Vector3 direction) {
        assertTrue(WorldPointMesh.intersect(mesh, ZERO, new Ray3(1, origin, direction, 32)).isEmpty());
    }
    private static void assertDistance(WorldPointMesh.Mesh mesh, Position origin, Vector3 direction, double expected) {
        assertEquals(expected, WorldPointMesh.intersect(mesh, ZERO, new Ray3(1, origin, direction, 32)).orElseThrow(), 1e-10);
    }
    private static Position subtract(Position a, Position b) { return new Position(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()); }
    private static Position cross(Position a, Position b) {
        return new Position(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }
    private static double dot(Position a, Position b) { return a.x() * b.x() + a.y() * b.y() + a.z() * b.z(); }
}
