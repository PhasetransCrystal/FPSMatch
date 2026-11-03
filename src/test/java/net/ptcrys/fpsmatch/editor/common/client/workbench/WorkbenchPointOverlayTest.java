package net.ptcrys.fpsmatch.editor.common.client.workbench;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.Ray3;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.Vector3;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldPointMesh;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchPointOverlayTest {
    private static final EditorId SOURCE = new EditorId("test", "source");
    private static final EditorId OTHER = new EditorId("test", "other");
    private static final EditorId WORLD = new EditorId("minecraft", "overworld");
    private static final int NORMAL = 0xff112233, SELECTED = 0xff445566;

    @Test void cachedInstancesRequireAnExplicitCurrentDimensionMapping() {
        var cache = new WorkbenchPointOverlay.GeometryCache();
        var source = List.of(point("first", SOURCE, "1.25", Optional.empty()),
                point("second", OTHER, "2", Optional.empty()),
                point("unmapped", WORLD, "3", Optional.empty()));
        var mappings = Map.of(SOURCE, WORLD, OTHER, new EditorId("minecraft", "the_nether"));
        var instances = cache.instances(source, mappings, WORLD.toString(), null, NORMAL, SELECTED);
        assertEquals(List.of("first"), instances.stream().map(WorkbenchWorldPointRenderer.Instance::node).toList());
        assertEquals(new Position(1.25, 0, 0), instances.get(0).origin());
        assertSame(instances, cache.instances(List.copyOf(source), Map.copyOf(mappings), WORLD.toString(), null, NORMAL, SELECTED));
        assertThrows(UnsupportedOperationException.class, () -> instances.add(instances.get(0)));
        assertTrue(cache.instances(source, Map.of(), WORLD.toString(), null, NORMAL, SELECTED).isEmpty());
        assertEquals("second", cache.instances(source, mappings, "minecraft:the_nether", null, NORMAL, SELECTED).get(0).node());
    }

    @Test void selectionTintAndPositionChangesReuseOnlyTheDirectionMesh() {
        var cache = new WorkbenchPointOverlay.GeometryCache();
        var direction = Optional.of(new SpatialDocument.Direction(0, 0, 1));
        var source = List.of(point("first", SOURCE, "1", direction), point("second", SOURCE, "2", direction));
        var mappings = Map.of(SOURCE, WORLD);
        var initial = cache.instances(source, mappings, WORLD.toString(), "first", NORMAL, SELECTED);
        assertSame(initial.get(0).mesh(), initial.get(1).mesh());
        assertEquals(SELECTED, initial.get(0).argb());
        assertEquals(NORMAL, initial.get(1).argb());
        var selected = cache.instances(source, mappings, WORLD.toString(), "second", NORMAL, SELECTED);
        assertNotSame(initial, selected);
        assertSame(initial.get(0).mesh(), selected.get(0).mesh());
        assertEquals(NORMAL, selected.get(0).argb());
        assertEquals(SELECTED, selected.get(1).argb());
        var recolored = cache.instances(source, mappings, WORLD.toString(), "second", 0xff778899, SELECTED);
        assertEquals(0xff778899, recolored.get(0).argb());
        assertSame(selected.get(0).mesh(), recolored.get(0).mesh());
        var moved = cache.instances(List.of(point("first", SOURCE, "-8.5", direction), source.get(1)),
                mappings, WORLD.toString(), "second", NORMAL, SELECTED);
        assertEquals(new Position(-8.5, 0, 0), moved.get(0).origin());
        assertSame(initial.get(0).mesh(), moved.get(0).mesh());
        assertEquals(new Position(1, 0, 0), initial.get(0).origin(), "a published scene remains immutable");
    }

    @Test void directionChangeDoesNotReuseTheOldShape() {
        var cache = new WorkbenchPointOverlay.GeometryCache();
        var mappings = Map.of(SOURCE, WORLD);
        var first = cache.instances(List.of(point("first", SOURCE, "0", Optional.empty())),
                mappings, WORLD.toString(), null, NORMAL, SELECTED);
        var second = cache.instances(List.of(point("first", SOURCE, "0", Optional.of(new SpatialDocument.Direction(1, 0, 0)))),
                mappings, WORLD.toString(), null, NORMAL, SELECTED);
        assertNotSame(first.get(0).mesh(), second.get(0).mesh());
        assertNotEquals(first.get(0).mesh().bounds(), second.get(0).mesh().bounds());
        cache.clear();
        var fresh = cache.instances(List.of(point("first", SOURCE, "0", Optional.empty())),
                mappings, WORLD.toString(), null, NORMAL, SELECTED);
        assertNotSame(first.get(0).mesh(), fresh.get(0).mesh());
    }

    @Test void verticalLookKeepsItsOwnerDeclaredGroundHeading() {
        var cache = new WorkbenchPointOverlay.GeometryCache();
        var mappings = Map.of(SOURCE, WORLD);
        var east = Optional.of(new SpatialDocument.Direction(1, 0, 0));
        var south = Optional.of(new SpatialDocument.Direction(0, 0, 1));
        var up = Optional.of(new SpatialDocument.Direction(0, 1, 0));
        var down = Optional.of(new SpatialDocument.Direction(0, -1, 0));
        var first = new SpatialDocument.Point("spawn", SOURCE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(-90), up, east);
        var initial = cache.instances(List.of(first), mappings, WORLD.toString(), null, NORMAL, SELECTED);
        var changedPitch = new SpatialDocument.Point("spawn", SOURCE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(90), down, east);
        var sameHeading = cache.instances(List.of(changedPitch), mappings, WORLD.toString(), null, NORMAL, SELECTED);
        assertSame(initial.get(0).mesh(), sameHeading.get(0).mesh(), "look pitch does not reshape a ground marker");
        var changedHeading = new SpatialDocument.Point("spawn", SOURCE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(90), BigDecimal.valueOf(90), down, south);
        var rotated = cache.instances(List.of(changedHeading), mappings, WORLD.toString(), null, NORMAL, SELECTED);
        assertNotSame(initial.get(0).mesh(), rotated.get(0).mesh());
        assertNotEquals(initial.get(0).mesh().bounds(), rotated.get(0).mesh().bounds());
    }

    @Test void nearestUsesTheActualFrontSurfaceThenStableNodeForExactTies() {
        var far = instance("a-far", 0, SELECTED);
        var near = instance("z-near", 5, NORMAL);
        var ray = new Ray3(1, new Position(0, 0, 10), new Vector3(0, 0, -1), 20);
        var hit = WorkbenchPointOverlay.nearest(List.of(far, near), ray).orElseThrow();
        assertSame(near, hit.point());
        assertEquals(5, hit.distance());
        assertEquals(new Position(0, 0, 5), hit.position());
        var tied = instance("a-near", 5, SELECTED);
        assertSame(tied, WorkbenchPointOverlay.nearest(List.of(near, tied), ray).orElseThrow().point());
        assertSame(tied, WorkbenchPointOverlay.nearest(List.of(tied, near), ray).orElseThrow().point());
    }

    @Test void offTriangleBackFacingAndOutsideNearFarAreNotScreenRadiusHits() {
        var target = List.of(instance("target", 5, NORMAL));
        assertTrue(WorkbenchPointOverlay.nearest(target,
                new Ray3(1, new Position(2, 0, 10), new Vector3(0, 0, -1), 20)).isEmpty());
        assertTrue(WorkbenchPointOverlay.nearest(target,
                new Ray3(1, new Position(0, 0, 10), new Vector3(0, 0, -1), 4)).isEmpty());
        assertTrue(WorkbenchPointOverlay.nearest(target,
                new Ray3(1, new Position(0, 0, 4), new Vector3(0, 0, -1), 20)).isEmpty());
        assertTrue(WorkbenchPointOverlay.nearest(target,
                new Ray3(1, new Position(0, 0, 0), new Vector3(0, 0, 1), 20)).isEmpty());
    }

    private static SpatialDocument.Point point(String node, EditorId dimension, String x, Optional<SpatialDocument.Direction> direction) {
        return new SpatialDocument.Point(node, dimension, new BigDecimal(x), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, direction);
    }

    private static WorkbenchWorldPointRenderer.Instance instance(String node, double z, int tint) {
        var triangle = new WorldPointMesh.Triangle(new Position(-1, -1, 0), new Position(1, -1, 0), new Position(0, 1, 0));
        var mesh = new WorldPointMesh.Mesh(List.of(triangle), new WorldPointMesh.Bounds(-1, -1, 0, 1, 1, 0));
        return new WorkbenchWorldPointRenderer.Instance(node, new Position(0, 0, z), mesh, tint);
    }
}
