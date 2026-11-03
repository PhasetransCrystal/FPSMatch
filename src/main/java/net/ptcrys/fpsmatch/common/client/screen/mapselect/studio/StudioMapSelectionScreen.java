package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import net.ptcrys.fpsmatch.common.client.FPSMClient;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import net.ptcrys.fpsmatch.common.packet.mapselect.*;
import net.ptcrys.fpsmatch.editor.common.ui.component.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Length;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.*;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/** Searchable server-backed browser. A row opens only after its matching detail arrives. */
public final class StudioMapSelectionScreen extends StudioMapScreen {

    private MapSelectionSnapshotS2CPacket snapshot;
    private final MapSelectionPresentation presentation = new MapSelectionPresentation();
    private MapRoomSummary selected;
    private String query = "", mode = "", state = "all", filter = "";
    private boolean opening;
    private int scroll;

    public StudioMapSelectionScreen(MapSelectionSnapshotS2CPacket snapshot, Screen parent) {
        super(Component.translatable("gui.fpsm.map_select.title"), parent, "map-selection");
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    public void applySnapshot(MapSelectionSnapshotS2CPacket next) {
        snapshot = next;
        if (selected != null) {
            selected = next.maps().stream().filter(room -> same(room, selected)).findFirst().orElse(null);
            if (selected == null) opening = false;
        }
        synchronizeNavigation();
    }

    public boolean acceptsDetail(MapRoomDetail detail) {
        return same(selected, detail.summary());
    }

    public void applyDetail(MapRoomDetail detail) {
        if (selected == null) selected = detail.summary();
        if (acceptsDetail(detail)) synchronizeNavigation();
    }

    public boolean consumePendingDetailOpen() {
        boolean result = opening;
        opening = false;
        return result;
    }

    public void applyToast() {
        FPSMClient.getGlobalData().getMapRoomToast().ifPresent(packet -> showNotice(packet.message().getString(), packet.error()));
        if (!waiting()) opening = false;
        requestLayout();
    }

    public void navigationIntentChanged(MapViewIntent.Request request) {
        presentation.navigationChanged(request);
        status = switch (presentation.status()) {
            case LOADING_MAPS -> tr("gui.fpsm.studio.loading");
            case LOADING_ROOM -> tr("gui.fpsm.studio.opening");
            case NOTICE -> presentation.notice();
            case IDLE -> "";
        };
        statusError = presentation.error();
        requestLayout();
    }

    private void synchronizeNavigation() {
        navigationIntentChanged(FPSMMapSelectScreens.navigationRequest(this));
    }

    private void showNotice(String text, boolean error) {
        presentation.notice(text, error);
        synchronizeNavigation();
    }

    private boolean waiting() {
        return FPSMMapSelectScreens.isWaitingForReply(this);
    }

    private boolean accessible() {
        return FPSMClient.getGlobalData().isMapSelectionButtonVisible() && (snapshot.viewerOp() || snapshot.nonOpButtonEnabled());
    }

    private void select(MapRoomSummary room) {
        if (waiting() || !accessible()) return;
        selected = room;
        opening = FPSMMapSelectScreens.requestDetail(this, room);
    }

    @Override
    protected void onScreenTick() {
        MapViewIntent.Request request = FPSMMapSelectScreens.navigationRequest(this);
        if (FPSMMapSelectScreens.tickPendingRequest(this)) {
            opening = false;
            showNotice(tr(request == MapViewIntent.Request.LIST ? "gui.fpsm.studio.list_timeout" : "gui.fpsm.studio.open_timeout"), true);
        }
    }

    @Override
    protected String pageSubtitle() {
        return tr("gui.fpsm.studio.browser_hint");
    }

    @Override
    protected StudioView.Element topControls(StudioView view) {
        var search = view.input(new StudioView.Input("browser/search", tr("gui.fpsm.map_select.search"), query, true, false, false, "", null, value -> {
            query = value;
            scroll = 0;
            requestLayout();
        }, null))
                .style(Style.defaults().width(Length.stretch().limits(130, Integer.MAX_VALUE)));
        return view.wrap("browser/filters", Style.defaults().gap(6), List.of(search,
                view.button(action("browser/mode", mode.isEmpty() ? tr("gui.fpsm.studio.all_modes") : gameTypeText(mode), Icon.CHEVRON_DOWN, Variant.OUTLINED, true, () -> {
                    filter = "mode";
                    resetOverlayScroll();
                    requestLayout();
                })),
                view.button(action("browser/state", tr("gui.fpsm.map_select.filter." + state), Icon.CHEVRON_DOWN, Variant.OUTLINED, true, () -> {
                    filter = "state";
                    resetOverlayScroll();
                    requestLayout();
                })),
                view.button(action("browser/refresh", tr("gui.fpsm.map_select.refresh"), Icon.REDO, Variant.OUTLINED, !waiting() && FPSMClient.getGlobalData().isMapSelectionButtonVisible(), () -> FPSMMapSelectScreens.refreshSelection(this)))));
    }

    @Override
    protected List<StudioComponents.Action> footerActions() {
        return waiting() ? List.of(action("browser/cancel-wait", tr("gui.cancel"), Icon.CLOSE, Variant.OUTLINED, true, () -> {
            FPSMMapSelectScreens.cancelDetailRequest(this);
            opening = false;
            showNotice(tr("gui.fpsm.studio.navigation_cancelled"), false);
        })) : List.of();
    }

    @Override
    protected void composeBody(Composition content, Bounds body) {
        var view = content.view();
        var rows = new ArrayList<StudioView.Element>();
        if (!accessible()) {
            content.emit(view.layoutContent(view.paragraph("browser/denied", tr("gui.fpsm.studio.access_denied"), TextSize.BODY, Variant.DANGER), body.x(), body.y(), body.width()), 0);
            return;
        }
        String search = query.toLowerCase(Locale.ROOT);
        for (MapRoomSummary room : snapshot.maps()) {
            if (!mode.isEmpty() && !mode.equals(room.gameType())) continue;
            if (!(room.displayName() + " " + room.mapName() + " " + room.gameType()).toLowerCase(Locale.ROOT).contains(search)) continue;
            if (state.equals("waiting") && room.started() || state.equals("running") && !room.started() || state.equals("open") && (room.full() || room.started() && !room.allowJoinInProgress())) continue;
            String id = "browser/room/" + part(room.gameType()) + part(room.mapName());
            String openLabel = tr("gui.fpsm.studio.view_room");
            var text = new MapSelectionRow.Text(room.displayName(), gameTypeText(room.gameType()) + " · " + room.mapName(),
                    tr("gui.fpsm.map_select.detail.players", room.joinedPlayers(), room.maxPlayers() < 0 ? "∞" : room.maxPlayers()),
                    statusText(room), openLabel);
            var action = new StudioComponents.Action(id, room.displayName() + " · " + openLabel, Icon.NONE, Variant.DEFAULT, !waiting(), same(room, selected), "", () -> select(room));
            rows.add(MapSelectionRow.compose(view, action, text, room.iconTexture(), Math.max(0, body.width() - 8)));
        }
        MapSelectionPresentation.EmptyState empty = presentation.emptyState(snapshot.maps().size(), rows.size());
        if (empty != MapSelectionPresentation.EmptyState.NONE) {
            String key = switch (empty) {
                case LOADING -> "gui.fpsm.studio.loading";
                case NO_MATCHES -> "gui.fpsm.studio.no_matching_maps";
                default -> "gui.fpsm.map_select.empty";
            };
            rows.add(view.paragraph("browser/empty", tr(key), TextSize.BODY, Variant.MUTED));
            if (empty == MapSelectionPresentation.EmptyState.NO_MATCHES)
                rows.add(view.button(action("browser/clear-filters", tr("gui.fpsm.studio.clear_filters"), Icon.CLEAR, Variant.OUTLINED, true, () -> {
                    query = "";
                    mode = "";
                    state = "all";
                    scroll = 0;
                    requestLayout();
                })));
        }
        scroll = content.scroll("browser/list", view.column("browser/rows", Style.defaults().gap(6), rows), body, 0, scroll, value -> {
            scroll = value;
            requestLayout();
        });
    }

    @Override
    protected void composeOverlay(Composition content) {
        if (filter.isEmpty()) return;
        var actions = new ArrayList<StudioComponents.Action>();
        if (filter.equals("mode")) {
            var modes = new ArrayList<String>();
            modes.add("");
            snapshot.maps().stream().map(MapRoomSummary::gameType).distinct().sorted().forEach(modes::add);
            for (String value : modes) actions.add(action("mode/" + part(value), value.isEmpty() ? tr("gui.fpsm.studio.all_modes") : gameTypeText(value), Icon.NONE, Variant.DEFAULT, true, () -> {
                mode = value;
                filter = "";
                scroll = 0;
                requestLayout();
            }));
        } else for (String value : List.of("all", "waiting", "running", "open")) actions.add(action("state/" + value, tr("gui.fpsm.map_select.filter." + value), Icon.NONE, Variant.DEFAULT, true, () -> {
            state = value;
            filter = "";
            scroll = 0;
            requestLayout();
        }));
        choices(content, "browser/filter-dialog", tr("gui.fpsm.studio.filters"), actions, () -> {
            filter = "";
            requestLayout();
        });
    }

    @Override
    public void onClose() {
        if (!filter.isEmpty()) {
            filter = "";
            requestLayout();
            return;
        }
        opening = false;
        super.onClose();
    }

    public static String gameTypeText(String type) {
        String key = "fpsm.game_type." + type.toLowerCase(Locale.ROOT);
        return I18n.exists(key) ? tr(key) : type.toUpperCase(Locale.ROOT);
    }

    public static String statusText(MapRoomSummary room) {
        return tr(room.full() ? "gui.fpsm.map_select.full" : room.started() ? room.allowJoinInProgress() ? "gui.fpsm.map_select.status.started_joinable" : "gui.fpsm.map_select.status.started" : "gui.fpsm.map_select.status.waiting");
    }

    private static boolean same(MapRoomSummary a, MapRoomSummary b) {
        return a != null && b != null && a.gameType().equals(b.gameType()) && a.mapName().equals(b.mapName());
    }
}
