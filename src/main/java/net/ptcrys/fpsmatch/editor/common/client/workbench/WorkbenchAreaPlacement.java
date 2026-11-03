package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorldTargetWorkbench;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One document operation from an explicitly chosen corner or translation tool. */
final class WorkbenchAreaPlacement {

    enum Mode {
        FIRST,
        SECOND,
        TRANSLATE
    }

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectWorkbench model;
    private final ProjectWorldWorkbench projectWorld;
    private final WorldTargetWorkbench worldTarget;
    private ProjectWorkbench.AreaEditDraft draft;
    private ProjectWorldWorkbench.Permission permission;
    private long targetVersion;
    private UUID worldId;
    private Path root;
    private Map<EditorId, EditorId> mappings = Map.of();
    private Object server, level, player, connection;
    private Mode mode;
    private String hint = "area_edit_aim";

    WorkbenchAreaPlacement(ProjectWorkbench model, ProjectWorldWorkbench projectWorld, WorldTargetWorkbench worldTarget) {
        this.model = model;
        this.projectWorld = projectWorld;
        this.worldTarget = worldTarget;
    }

    boolean active() {
        return draft != null;
    }

    Mode mode() {
        return mode;
    }

    String hint() {
        return hint;
    }

    Optional<SpatialDocument.Area> origin() {
        return draft == null ? Optional.empty() : Optional.of(draft.origin());
    }

    Optional<SpatialDocument.Area> previewArea() {
        if (!current() || draft.target().isEmpty()) return Optional.empty();
        var origin = draft.origin();
        return Optional.of(new SpatialDocument.Area(origin.node(), origin.dimension(), draft.target().orElseThrow(), origin.semantics()));
    }

    void begin(Path root, UUID document, String node, Mode mode) {
        cancel();
        if (!client.isWindowActive() || client.getSingleplayerServer() == null || client.level == null || client.player == null || client.getConnection() == null)
            throw new IllegalStateException("Area placement requires an active local world");
        if (worldTarget.state() != WorldTargetWorkbench.State.BOUND || !worldTarget.available())
            throw new IllegalStateException("A verified world target is required for area placement");
        this.root = root.toAbsolutePath().normalize();
        permission = projectWorld.permission(this.root).orElseThrow(() -> new IllegalStateException("Authoring world association is unavailable"));
        targetVersion = worldTarget.version();
        worldId = worldTarget.worldId().orElseThrow();
        mappings = Map.copyOf(worldTarget.mappings());
        server = client.getSingleplayerServer();
        level = client.level;
        player = client.player;
        connection = client.getConnection();
        this.mode = java.util.Objects.requireNonNull(mode);
        draft = model.beginAreaEdit(document, node);
        hint = "area_edit_aim";
        if (!current()) {
            cancel();
            throw new IllegalStateException("Area placement source changed");
        }
    }

    boolean current() {
        return draft != null && client.isWindowActive() && model.areaEditCurrent(draft) && projectWorld.current(permission) && worldTarget.state() == WorldTargetWorkbench.State.BOUND && worldTarget.available() && targetVersion == worldTarget.version() && worldTarget.worldId().filter(worldId::equals).isPresent() && mappings.equals(worldTarget.mappings()) && client.getSingleplayerServer() == server && client.level != null && client.level == level && client.player != null && client.player == player && client.getConnection() != null && client.getConnection() == connection && client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root) && model.activeDocument().filter(draft.document()::equals).isPresent() && model.selectedNode(draft.document()).filter(draft.node()::equals).isPresent();
    }

    void preview(WorkbenchWorldViewport viewport, double x, double y, boolean exposed) {
        if (!current()) {
            cancel();
            return;
        }
        var picked = pick(viewport, x, y, exposed);
        if (picked.isEmpty()) {
            clearPreview();
            return;
        }
        try {
            var bounds = candidate(draft.origin(), mode, position(picked.get()));
            if (draft.target().isEmpty() || !draft.target().orElseThrow().sameBounds(bounds))
                draft = model.previewAreaEdit(draft, bounds);
            hint = "area_edit_confirm";
        } catch (IllegalArgumentException invalid) {
            hint = "area_edit_invalid";
            clearPreview();
        }
    }

    /** Every click obtains new physical-world and frame evidence; hover is never commit authority. */
    boolean commit(WorkbenchWorldViewport viewport, double x, double y, boolean exposed) {
        if (!current()) {
            cancel();
            return false;
        }
        var picked = pick(viewport, x, y, exposed);
        if (picked.isEmpty()) {
            clearPreview();
            return false;
        }
        var hit = picked.get();
        try {
            draft = model.previewAreaEdit(draft, candidate(draft.origin(), mode, position(hit)));
        } catch (IllegalArgumentException invalid) {
            hint = "area_edit_invalid";
            clearPreview();
            return false;
        }
        if (!current() || !viewport.isCurrent(hit.frame())) {
            cancel();
            return false;
        }
        var accepted = draft;
        draft = null;
        try {
            model.commitAreaEdit(accepted);
            return true;
        } finally {
            cancel();
        }
    }

    private Optional<WorkbenchWorldViewport.Pick> pick(WorkbenchWorldViewport viewport, double x, double y, boolean exposed) {
        if (!exposed) {
            hint = "area_edit_aim";
            return Optional.empty();
        }
        var hit = viewport.pick(x, y);
        if (hit.isEmpty()) {
            hint = "point_move_wait";
            return Optional.empty();
        }
        EditorId mapped = mappings.get(draft.origin().dimension());
        if (mapped == null || !mapped.toString().equals(hit.get().frame().dimension())) {
            hint = "area_edit_dimension";
            return Optional.empty();
        }
        if (!hit.get().hasBlockHit() || !viewport.isCurrent(hit.get().frame())) {
            hint = "point_move_miss";
            return Optional.empty();
        }
        return hit;
    }

    private void clearPreview() {
        if (draft != null && draft.target().isPresent()) {
            var old = draft;
            model.cancelAreaEdit(old);
            draft = model.beginAreaEdit(old.document(), old.node());
        }
    }

    void cancel() {
        if (draft != null) model.cancelAreaEdit(draft);
        draft = null;
        permission = null;
        root = null;
        mappings = Map.of();
        server = null;
        level = null;
        player = null;
        connection = null;
        mode = null;
    }

    private static SpatialDocument.Position position(WorkbenchWorldViewport.Pick hit) {
        var block = hit.hit().getBlockPos();
        return new SpatialDocument.Position(BigDecimal.valueOf(block.getX()), BigDecimal.valueOf(block.getY()), BigDecimal.valueOf(block.getZ()));
    }

    static SpatialDocument.AreaBounds candidate(SpatialDocument.Area origin, Mode mode, SpatialDocument.Position position) {
        var bounds = origin.bounds();
        SpatialDocument.AreaBounds result;
        if (mode == Mode.FIRST) result = new SpatialDocument.AreaBounds(position, bounds.second());
        else if (mode == Mode.SECOND) result = new SpatialDocument.AreaBounds(bounds.first(), position);
        else {
            var a = bounds.first();
            var b = bounds.second();
            result = new SpatialDocument.AreaBounds(position, new SpatialDocument.Position(
                    b.x().add(position.x().subtract(a.x())), b.y().add(position.y().subtract(a.y())), b.z().add(position.z().subtract(a.z()))));
        }
        if (origin.semantics().coordinates() == SpatialDocument.AreaCoordinates.INT32) {
            try {
                for (var p : java.util.List.of(result.first(), result.second())) {
                    p.x().intValueExact();
                    p.y().intValueExact();
                    p.z().intValueExact();
                }
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Area coordinates exceed integer block bounds", invalid);
            }
        }
        return result;
    }

    static boolean emptyPositionVolume(SpatialDocument.Area area) {
        if (!area.semantics().uses().contains(SpatialDocument.AreaUse.POSITION_HALF_OPEN)) return false;
        var a = area.bounds().first();
        var b = area.bounds().second();
        return a.x().compareTo(b.x()) == 0 || a.y().compareTo(b.y()) == 0 || a.z().compareTo(b.z()) == 0;
    }
}
