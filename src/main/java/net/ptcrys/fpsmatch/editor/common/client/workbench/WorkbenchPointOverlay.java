package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.Ray3;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldAreaMesh;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldPointMesh;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorldTargetWorkbench;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Read-only world meshes. A displayed scene authorizes neither placement nor world writes. */
final class WorkbenchPointOverlay {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectWorkbench model;
    private final ProjectWorldWorkbench projectWorld;
    private final WorldTargetWorkbench worldTarget;
    private final GeometryCache geometry = new GeometryCache();
    private final AreaGeometryCache areaGeometry = new AreaGeometryCache();
    private final AreaGeometryCache previewGeometry = new AreaGeometryCache();
    private Source source;
    private long directoryVersion = -1;
    private Scene pending;
    private Display shown;

    WorkbenchPointOverlay(ProjectWorkbench model, ProjectWorldWorkbench projectWorld, WorldTargetWorkbench worldTarget) {
        this.model = Objects.requireNonNull(model);
        this.projectWorld = Objects.requireNonNull(projectWorld);
        this.worldTarget = Objects.requireNonNull(worldTarget);
    }

    /** Only this controller can issue scenes or publish a displayed-scene receipt. */
    private final class Scene implements WorkbenchWorldViewport.Scene {

        private final Source captured;
        private final BooleanSupplier exposed;
        private final String dimension, selected;
        private final List<WorkbenchWorldPointRenderer.Instance> points;
        private final List<WorkbenchWorldPointRenderer.Instance> previews;

        private Scene(Source captured, BooleanSupplier exposed, String dimension, String selected,
                      List<WorkbenchWorldPointRenderer.Instance> points, List<WorkbenchWorldPointRenderer.Instance> previews) {
            this.captured = captured;
            this.exposed = exposed;
            this.dimension = dimension;
            this.selected = selected;
            this.points = points;
            this.previews = previews;
        }

        @Override
        public List<WorkbenchWorldPointRenderer.Instance> points() {
            return points;
        }

        @Override
        public List<WorkbenchWorldPointRenderer.Instance> previews() {
            return previews;
        }

        @Override
        public boolean current() {
            return (this == pending || shown != null && shown.scene == this) && source == captured && exposed.getAsBoolean() && sourceCurrent(false) && dimension.equals(client.level.dimension().location().toString()) && Objects.equals(selected, model.selectedNode(captured.document).orElse(null));
        }

        @Override
        public void displayed(WorkbenchWorldViewport viewport, WorkbenchWorldViewport.Frame frame) {
            if (this != pending) return;
            if (!current() || !dimension.equals(frame.dimension()) || !viewport.isCurrent(frame)) {
                invalidate();
                return;
            }
            shown = new Display(this, viewport, frame);
            pending = null;
        }
    }

    private record Display(Scene scene, WorkbenchWorldViewport viewport, WorkbenchWorldViewport.Frame frame) {}

    static final class Hit {

        private final Display display;
        private final WorkbenchWorldPointRenderer.Instance point;

        private Hit(Display display, WorkbenchWorldPointRenderer.Instance point) {
            this.display = display;
            this.point = point;
        }

        UUID document() {
            return display.scene.captured.document;
        }

        String node() {
            return point.node();
        }
    }

    private record Source(Path root, UUID document, DocumentHandle<?> handle, DocumentToken token,
                          List<SpatialDocument.Point> points, List<SpatialDocument.Area> areas, ProjectWorldWorkbench.Permission permission,
                          long targetVersion, UUID worldId, Map<EditorId, EditorId> mappings,
                          Object server, Object level, Object player, Object connection) {}

    /** Called before each world pass. Preparing a scene always revokes the previous hit receipt. */
    WorkbenchWorldViewport.Scene prepare(Path root, BooleanSupplier exposed, int normalTint, int selectedTint,
                                         Optional<SpatialDocument.Area> preview, int previewTint) {
        Objects.requireNonNull(exposed);
        pending = null;
        shown = null;
        if (root == null || !exposed.getAsBoolean() || !client.isWindowActive()) {
            invalidate();
            return null;
        }
        Path normalized = root.toAbsolutePath().normalize();
        try {
            if (source == null || !source.root.equals(normalized) || !sourceCurrent(false)) {
                source = null;
                directoryVersion = -1;
                source = capture(normalized);
            }
            if (source == null) return null;
            String dimension = client.level.dimension().location().toString();
            String selected = model.selectedNode(source.document).orElse(null);
            var points = new ArrayList<>(geometry.instances(source.points, source.mappings, dimension, selected, normalTint, selectedTint));
            points.addAll(areaGeometry.instances(source.areas, source.mappings, dimension, selected, normalTint,
                    preview.isPresent() ? normalTint : selectedTint));
            var previews = previewGeometry.instances(preview.stream().toList(), source.mappings, dimension,
                    selected, previewTint, previewTint);
            pending = new Scene(source, exposed, dimension, selected, List.copyOf(points), previews);
            if (!pending.current()) {
                invalidate();
                return null;
            }
            return pending;
        } catch (RuntimeException | Error failure) {
            invalidate();
            throw failure;
        }
    }

    /** Actual world triangle coverage and this frame's depth buffer decide selection. */
    Optional<Hit> hit(WorkbenchWorldViewport viewport, double x, double y, boolean exposed) {
        if (!exposed || shown == null) return Optional.empty();
        Display display = shown;
        if (display.viewport != viewport || !display.scene.current() || !sourceCurrent(true) || !viewport.isCurrent(display.frame)) {
            invalidate();
            return Optional.empty();
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !display.frame.layout().logicalRect().contains(x, y))
            return Optional.empty();
        return viewport.pointAtPixel(display.frame, x, y)
                .filter(display.scene.points::contains)
                .map(point -> new Hit(display, point));
    }

    /** Recheck after blur/creation callbacks and immediately before the existing UI selection. */
    boolean current(WorkbenchWorldViewport viewport, Hit hit) {
        if (hit == null || hit.display != shown || shown == null || shown.viewport != viewport) return false;
        if (!shown.scene.current() || !sourceCurrent(true) || !viewport.isCurrent(shown.frame)) {
            invalidate();
            return false;
        }
        // Every target write revokes Frame first. The same immutable displayed receipt
        // therefore preserves the coverage result without a second GPU synchronization.
        return true;
    }

    void invalidate() {
        source = null;
        pending = null;
        shown = null;
        directoryVersion = -1;
        geometry.clear();
        areaGeometry.clear();
        previewGeometry.clear();
    }

    static record MeshHit(WorkbenchWorldPointRenderer.Instance point, double distance, Position position) {}

    static Optional<MeshHit> nearest(List<WorkbenchWorldPointRenderer.Instance> points, Ray3 ray) {
        MeshHit nearest = null;
        for (var point : points) {
            var distance = WorldPointMesh.intersect(point.mesh(), point.origin(), ray);
            if (distance.isEmpty()) continue;
            double value = distance.getAsDouble();
            if (nearest != null && (value > nearest.distance || value == nearest.distance && point.node().compareTo(nearest.point.node()) >= 0)) continue;
            Position position = new Position(Math.fma(ray.direction().x(), value, ray.origin().x()),
                    Math.fma(ray.direction().y(), value, ray.origin().y()),
                    Math.fma(ray.direction().z(), value, ray.origin().z()));
            nearest = new MeshHit(point, value, position);
        }
        return Optional.ofNullable(nearest);
    }

    /** Retains meshes through selection/tint/position changes; caches no frame or selection authority. */
    static final class GeometryCache {

        private List<SpatialDocument.Point> points;
        private Map<EditorId, EditorId> mappings;
        private String dimension, selected;
        private int normalTint, selectedTint;
        private Map<Optional<SpatialDocument.Direction>, WorldPointMesh.Mesh> meshes = Map.of();
        private List<WorkbenchWorldPointRenderer.Instance> instances = List.of();

        List<WorkbenchWorldPointRenderer.Instance> instances(List<SpatialDocument.Point> nextPoints,
                                                             Map<EditorId, EditorId> nextMappings, String nextDimension, String nextSelected,
                                                             int nextNormalTint, int nextSelectedTint) {
            if (Objects.equals(points, nextPoints) && Objects.equals(mappings, nextMappings) && Objects.equals(dimension, nextDimension) && Objects.equals(selected, nextSelected) && normalTint == nextNormalTint && selectedTint == nextSelectedTint) return instances;
            var nextMeshes = new HashMap<Optional<SpatialDocument.Direction>, WorldPointMesh.Mesh>();
            var nextInstances = new ArrayList<WorkbenchWorldPointRenderer.Instance>();
            for (var point : nextPoints) {
                var mapped = nextMappings.get(point.dimension());
                if (mapped == null || !mapped.toString().equals(nextDimension)) continue;
                var mesh = nextMeshes.computeIfAbsent(point.groundDirection(), direction -> {
                    var cached = meshes.get(direction);
                    return cached == null ? WorldPointMesh.build(direction) : cached;
                });
                nextInstances.add(new WorkbenchWorldPointRenderer.Instance(point.node(),
                        new Position(point.x().doubleValue(), point.y().doubleValue(), point.z().doubleValue()),
                        mesh, point.node().equals(nextSelected) ? nextSelectedTint : nextNormalTint));
            }
            points = List.copyOf(nextPoints);
            mappings = Map.copyOf(nextMappings);
            dimension = nextDimension;
            selected = nextSelected;
            normalTint = nextNormalTint;
            selectedTint = nextSelectedTint;
            meshes = Map.copyOf(nextMeshes);
            instances = List.copyOf(nextInstances);
            return instances;
        }

        void clear() {
            points = null;
            mappings = null;
            meshes = Map.of();
            instances = List.of();
        }
    }

    /** Ordered endpoints stay in document space. Only local differences enter the world mesh. */
    static final class AreaGeometryCache {

        private List<SpatialDocument.Area> areas;
        private Map<EditorId, EditorId> mappings;
        private String dimension, selected;
        private int normalTint, selectedTint;
        private Map<Position, WorldPointMesh.Mesh> meshes = Map.of();
        private List<WorkbenchWorldPointRenderer.Instance> instances = List.of();
        private WorldPointMesh.Mesh handle;

        List<WorkbenchWorldPointRenderer.Instance> instances(List<SpatialDocument.Area> nextAreas,
                                                             Map<EditorId, EditorId> nextMappings, String nextDimension, String nextSelected,
                                                             int nextNormalTint, int nextSelectedTint) {
            if (Objects.equals(areas, nextAreas) && Objects.equals(mappings, nextMappings) && Objects.equals(dimension, nextDimension) && Objects.equals(selected, nextSelected) && normalTint == nextNormalTint && selectedTint == nextSelectedTint) return instances;
            var nextMeshes = new HashMap<Position, WorldPointMesh.Mesh>();
            var result = new ArrayList<WorkbenchWorldPointRenderer.Instance>();
            for (var area : nextAreas) {
                var mapped = nextMappings.get(area.dimension());
                if (mapped == null || !mapped.toString().equals(nextDimension)) continue;
                if (area.semantics().displayBoundary() != SpatialDocument.AreaBoundary.ENDPOINT_PLANES) continue;
                var a = area.bounds().first();
                var b = area.bounds().second();
                var delta = new Position(b.x().subtract(a.x()).doubleValue(), b.y().subtract(a.y()).doubleValue(), b.z().subtract(a.z()).doubleValue());
                var mesh = nextMeshes.computeIfAbsent(delta, value -> {
                    var cached = meshes.get(value);
                    return cached == null ? WorldAreaMesh.outline(new Position(0, 0, 0), value) : cached;
                });
                boolean chosen = area.node().equals(nextSelected);
                int tint = chosen ? nextSelectedTint : nextNormalTint;
                Position origin = position(a);
                result.add(new WorkbenchWorldPointRenderer.Instance(area.node(), origin, mesh, tint));
                if (chosen) {
                    if (handle == null) handle = WorldAreaMesh.handle();
                    result.add(new WorkbenchWorldPointRenderer.Instance(area.node(), origin, handle, tint));
                    if (!a.sameCoordinates(b)) result.add(new WorkbenchWorldPointRenderer.Instance(area.node(), position(b), handle, tint));
                }
            }
            areas = List.copyOf(nextAreas);
            mappings = Map.copyOf(nextMappings);
            dimension = nextDimension;
            selected = nextSelected;
            normalTint = nextNormalTint;
            selectedTint = nextSelectedTint;
            meshes = Map.copyOf(nextMeshes);
            instances = List.copyOf(result);
            return instances;
        }

        private static Position position(SpatialDocument.Position value) {
            return new Position(value.x().doubleValue(), value.y().doubleValue(), value.z().doubleValue());
        }

        void clear() {
            areas = null;
            mappings = null;
            meshes = Map.of();
            instances = List.of();
            handle = null;
        }
    }

    private Source capture(Path root) {
        if (!worldReady()) return null;
        UUID document = model.activeDocument().orElse(null);
        if (document == null) return null;
        var permission = projectWorld.permission(root).orElse(null);
        if (permission == null) return null;
        var handle = model.documents().get(document);
        if (handle == null) return null;
        var token = handle.view().token();
        var points = List.copyOf(model.spatialPoints(document));
        var areas = List.copyOf(model.spatialAreas(document));
        var captured = new Source(root, document, handle, token, points, areas, permission, worldTarget.version(),
                worldTarget.worldId().orElseThrow(), Map.copyOf(worldTarget.mappings()), client.getSingleplayerServer(),
                client.level, client.player, client.getConnection());
        source = captured;
        if (!sourceCurrent(true)) {
            invalidate();
            return null;
        }
        return captured;
    }

    private boolean worldReady() {
        return client.isWindowActive() && client.getSingleplayerServer() != null && client.level != null && client.player != null && client.getConnection() != null && worldTarget.state() == WorldTargetWorkbench.State.BOUND && worldTarget.available();
    }

    private boolean sourceCurrent(boolean verifyDirectory) {
        Source captured = source;
        if (captured == null || !worldReady() || !projectWorld.current(captured.permission) || !model.activeDocument().filter(captured.document::equals).isPresent() || worldTarget.version() != captured.targetVersion || worldTarget.worldId().filter(captured.worldId::equals).isEmpty() || !worldTarget.mappings().equals(captured.mappings) || client.getSingleplayerServer() != captured.server || client.level != captured.level || client.player != captured.player || client.getConnection() != captured.connection || !client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(captured.root)) return false;
        long version = model.version();
        if (verifyDirectory || version != directoryVersion) {
            if (model.documents().get(captured.document) != captured.handle) return false;
            directoryVersion = version;
        }
        return captured.handle.view().token().equals(captured.token);
    }
}
