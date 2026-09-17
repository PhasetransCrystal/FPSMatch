package net.ptcrys.fpsmatch.common.client.screen.mapselect.modernui;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.FPSMClient;
import net.ptcrys.fpsmatch.common.client.screen.modernui.ModernScreen;
import net.ptcrys.fpsmatch.common.packet.mapselect.*;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Native, searchable map browser. Opening a room waits for its matching server detail. */
public final class ModernMapSelectionScreen extends ModernScreen {

    private MapSelectionSnapshotS2CPacket snapshot;
    private MapRoomSummary selected;
    private MapRoomDetail detail;
    private String query = "", mode = "all", state = "all", status = "";
    private boolean opening;
    private int requestTicks;

    public ModernMapSelectionScreen(MapSelectionSnapshotS2CPacket data, Screen parent) {
        super(Component.translatable("gui.fpsm.map_select.title"), parent);
        snapshot = data;
    }

    public void applySnapshot(MapSelectionSnapshotS2CPacket data) {
        snapshot = data;
        if (selected != null) {
            selected = data.maps().stream().filter(m -> same(m, selected)).findFirst().orElse(null);
            if (selected == null) {
                detail = null;
                opening = false;
            }
        }
        refresh();
    }

    public boolean acceptsDetail(MapRoomDetail next) {
        return selected != null && same(next.summary(), selected);
    }

    public void applyDetail(MapRoomDetail next) {
        if (selected == null) selected = next.summary();
        if (!acceptsDetail(next)) return;
        detail = next;
        refresh();
    }

    public boolean consumePendingDetailOpen() {
        boolean result = opening;
        opening = false;
        return result;
    }

    public void applyToast() {
        status = FPSMClient.getGlobalData().getMapRoomToast().map(p -> p.message().getString()).orElse("");
        opening = false;
        refresh();
    }

    private void select(MapRoomSummary room, boolean open) {
        if (!same(room, selected)) detail = null;
        selected = room;
        opening = open;
        requestTicks = 0;
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.REQUEST_DETAIL,
                room.gameType(), room.mapName(), null));
    }

    @Override
    public void tick() {
        if (opening && ++requestTicks >= 200) {
            opening = false;
            status = tr("gui.fpsm.shop_editor.open.timeout");
            refresh();
        }
    }

    @Override
    protected List<Node> content() {
        int margin = Math.min(16, Math.max(8, width / 32)), w = Math.max(1, width - margin * 2);
        var layout = net.ptcrys.fpsmatch.common.client.screen.mapselect.modernui.MapSelectionLayoutModel.responsive(w, Math.max(1, height - 16));
        List<Node> nodes = new ArrayList<>();
        nodes.add(title("title", getTitle().getString()).at(margin + 8, 16, w - 68, 24));
        nodes.add(iconButton("refresh", "rotate-cw", tr("gui.fpsm.map_select.refresh"), !opening, () -> FPSMatch.sendToServer(new OpenMapSelectionC2SPacket()))
                .hint(tr("gui.fpsm.map_select.refresh")).at(width - margin - 52, 18, 22, 22));
        nodes.add(iconButton("close", "x", tr("gui.back"), true, this::onClose).at(width - margin - 26, 18, 22, 22));
        nodes.add(text("status", opening ? tr("gui.fpsm.shop_editor.state.opening") : status).at(margin, 8 + layout.toast().y(), w, layout.toast().height()));
        int top = 8 + layout.list().y();
        nodes.add(text("rooms.heading", tr("gui.fpsm.map_select.rooms.title")).at(margin, top, w, 16));
        top += 20;
        boolean wrap = w < 250;
        int selector = wrap ? (w - 6) / 2 : w < 300 ? 70 : 88, searchWidth = wrap ? w : w - selector * 2 - 12;
        nodes.add(field("search", query, true, v -> query = v).hint(tr("gui.fpsm.map_select.search")).at(margin, top, searchWidth, 22));
        List<Node> states = new ArrayList<>(), modes = new ArrayList<>();
        for (String filter : List.of("all", "waiting", "running", "open")) states.add(text(filter, tr("gui.fpsm.map_select.filter." + filter)));
        modes.add(text("all", tr("gui.fpsm.map_select.filter.all")));
        snapshot.maps().stream().map(MapRoomSummary::gameType).distinct().sorted().forEach(m -> modes.add(text(m, gameTypeText(m))));
        nodes.add(select("states", state, states, v -> state = v).at(wrap ? margin : margin + searchWidth + 6, top + (wrap ? 26 : 0), selector, 22));
        nodes.add(select("modes", mode, modes, v -> mode = v).at(wrap ? margin + selector + 6 : margin + searchWidth + selector + 12, top + (wrap ? 26 : 0), selector, 22));
        List<Node> rooms = new ArrayList<>();
        String search = query.toLowerCase(Locale.ROOT);
        for (MapRoomSummary room : snapshot.maps()) {
            if (!mode.equals("all") && !mode.equals(room.gameType())) continue;
            if (!(room.displayName() + " " + room.mapName() + " " + room.gameType()).toLowerCase(Locale.ROOT).contains(search)) continue;
            if (state.equals("waiting") && room.started() || state.equals("running") && !room.started() || state.equals("open") && (room.full() || room.started() && !room.allowJoinInProgress())) continue;
            int thumb = wrap ? 60 : 84, factsWidth = wrap ? 76 : 112;
            List<Node> card = new ArrayList<>();
            card.add((room.iconTexture().isBlank() ? text("preview", gameTypeText(room.gameType())).surface() : image("preview", room.iconTexture())).at(5, 5, thumb, 46));
            card.add(text("name", room.displayName()).at(thumb + 12, 5, Math.max(1, w - thumb - factsWidth - 24), 24));
            card.add(text("meta", gameTypeText(room.gameType()) + " · " + room.mapName()).at(thumb + 12, 30, Math.max(1, w - thumb - factsWidth - 24), 20));
            card.add(text("players", room.joinedPlayers() + " / " + (room.maxPlayers() < 0 ? "∞" : room.maxPlayers())).at(w - factsWidth - 6, 5, factsWidth, 22));
            card.add(text("state", statusText(room)).at(w - factsWidth - 6, 29, factsWidth, 22));
            rooms.add(actionCanvas(room.gameType() + ":" + room.mapName(), room.displayName(), !opening,
                    event -> { if (!event.startsWith("hover")) select(room, true); }, card).selected(same(room, selected)).size(-1, 56));
        }
        if (rooms.isEmpty()) rooms.add(text("empty", tr("gui.fpsm.map_select.empty")));
        top += wrap ? 54 : 28;
        nodes.add(scroll("rooms", rooms).at(margin, top, w, Math.max(1, height - top - 8)));
        return List.of(canvas("browser", nodes).fill());
    }

    public static String gameTypeText(String type) {
        String key = "fpsm.game_type." + type.toLowerCase(Locale.ROOT);
        return net.minecraft.client.resources.language.I18n.exists(key) ? tr(key) : type.toUpperCase(Locale.ROOT);
    }

    public static String statusText(MapRoomSummary room) {
        return tr(room.full() ? "gui.fpsm.map_select.full" : room.started() ? (room.allowJoinInProgress() ? "gui.fpsm.map_select.status.started_joinable" : "gui.fpsm.map_select.status.started") : "gui.fpsm.map_select.status.waiting");
    }

    private static boolean same(MapRoomSummary a, MapRoomSummary b) {
        return a != null && b != null && a.gameType().equals(b.gameType()) && a.mapName().equals(b.mapName());
    }

    @Override
    public void onClose() {
        FPSMatch.sendToServer(new CloseMapViewC2SPacket());
        super.onClose();
    }
}
