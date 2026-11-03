package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorldTargetWorkbench;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;

import net.minecraft.client.Minecraft;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Document-only placement. Neither a render frame nor a world UUID grants world write access. */
final class WorkbenchPointPlacement {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectWorkbench model;
    private final ProjectWorldWorkbench projectWorld;
    private final WorldTargetWorkbench worldTarget;
    private ProjectWorkbench.PointMoveDraft draft;
    private ProjectWorldWorkbench.Permission permission;
    private long targetVersion;
    private UUID worldId;
    private Map<EditorId, EditorId> mappings = Map.of();
    private Object server, level, player, connection;
    private SpatialDocument.Position preview;
    private String hint = "point_move_aim";

    WorkbenchPointPlacement(ProjectWorkbench model, ProjectWorldWorkbench projectWorld, WorldTargetWorkbench worldTarget) {
        this.model = model;
        this.projectWorld = projectWorld;
        this.worldTarget = worldTarget;
    }

    boolean active() {
        return draft != null;
    }

    String hint() {
        return hint;
    }

    Optional<SpatialDocument.Position> preview() {
        return Optional.ofNullable(preview);
    }

    void begin(Path root, UUID document, String node) {
        cancel();
        if (!client.isWindowActive()) throw new IllegalStateException("Point placement requires an active window");
        if (worldTarget.state() != WorldTargetWorkbench.State.BOUND || !worldTarget.available())
            throw new IllegalStateException("A verified world target is required for point placement");
        permission = projectWorld.permission(root).orElseThrow(() -> new IllegalStateException("Authoring world association is unavailable"));
        targetVersion = worldTarget.version();
        worldId = worldTarget.worldId().orElseThrow();
        mappings = worldTarget.mappings();
        server = client.getSingleplayerServer();
        level = client.level;
        player = client.player;
        connection = client.getConnection();
        draft = model.beginPointMove(document, node);
        hint = "point_move_aim";
    }

    boolean current() {
        return draft != null && client.isWindowActive() && model.pointMoveCurrent(draft) && projectWorld.current(permission) && worldTarget.state() == WorldTargetWorkbench.State.BOUND && worldTarget.available() && targetVersion == worldTarget.version() && worldTarget.worldId().filter(worldId::equals).isPresent() && mappings.equals(worldTarget.mappings()) && client.getSingleplayerServer() == server && client.level != null && client.level == level && client.player != null && client.player == player && client.getConnection() != null && client.getConnection() == connection && model.activeDocument().filter(draft.document()::equals).isPresent() && model.selectedNode(draft.document()).filter(draft.node()::equals).isPresent();
    }

    /** Called after this frame's viewport has actually been published. */
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
        var point = position(picked.get());
        if (preview == null || !same(preview, point)) draft = model.previewPointMove(draft, point);
        preview = point;
        hint = "point_move_confirm";
    }

    /** Never commits the previous hover candidate: every click obtains a fresh hit. */
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
        draft = model.previewPointMove(draft, position(hit));
        if (!current() || !viewport.isCurrent(hit.frame())) {
            cancel();
            return false;
        }
        var accepted = draft;
        draft = null;
        preview = null;
        try {
            model.commitPointMove(accepted);
            return true;
        } finally {
            cancel();
        }
    }

    private Optional<WorkbenchWorldViewport.Pick> pick(WorkbenchWorldViewport viewport, double x, double y, boolean exposed) {
        if (!exposed) {
            hint = "point_move_aim";
            return Optional.empty();
        }
        var hit = viewport.pick(x, y);
        if (hit.isEmpty()) {
            hint = "point_move_wait";
            return Optional.empty();
        }
        EditorId mapped = mappings.get(draft.origin().dimension());
        if (mapped == null || !mapped.toString().equals(hit.get().frame().dimension())) {
            hint = "point_move_dimension";
            return Optional.empty();
        }
        if (!hit.get().hasBlockHit() || !viewport.isCurrent(hit.get().frame())) {
            hint = "point_move_miss";
            return Optional.empty();
        }
        return hit;
    }

    private void clearPreview() {
        preview = null;
        if (draft != null && draft.target().isPresent()) {
            var old = draft;
            model.cancelPointMove(old);
            draft = model.beginPointMove(old.document(), old.node());
        }
    }

    void cancel() {
        if (draft != null) model.cancelPointMove(draft);
        draft = null;
        preview = null;
        permission = null;
        mappings = Map.of();
        server = null;
        level = null;
        player = null;
        connection = null;
    }

    private static SpatialDocument.Position position(WorkbenchWorldViewport.Pick hit) {
        var point = hit.hit().getLocation();
        return new SpatialDocument.Position(BigDecimal.valueOf(point.x), BigDecimal.valueOf(point.y), BigDecimal.valueOf(point.z));
    }

    private static boolean same(SpatialDocument.Position a, SpatialDocument.Position b) {
        return a.x().compareTo(b.x()) == 0 && a.y().compareTo(b.y()) == 0 && a.z().compareTo(b.z()) == 0;
    }
}
