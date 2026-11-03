package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.FPSMClient;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapDetailChildScreen;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import net.ptcrys.fpsmatch.common.client.screen.team.TeamActionModel;
import net.ptcrys.fpsmatch.common.packet.mapselect.*;
import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchClient;
import net.ptcrys.fpsmatch.editor.common.ui.component.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Length;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/** Live room membership and runtime controls. Authoring writes belong to project versions. */
public final class StudioMapRoomScreen extends StudioMapScreen implements FPSMMapDetailChildScreen {

    private MapRoomDetail detail;
    private Set<UUID> ready;
    private int countdown, bodyScroll, sideScroll, actionScroll;
    private String page = "players", modal = "", search = "";
    private final Set<String> collapsed = new HashSet<>();
    private UUID target;
    private final RoomActionIntent pending;
    private final RoomViewState viewState;
    private RoomActionIntent.Phase observedPendingPhase;
    private boolean observedDetailAvailable = true;
    private MapRoomActionC2SPacket.Action confirmation;

    public StudioMapRoomScreen(MapRoomDetail detail, Screen parent) {
        super(Component.literal(detail.summary().displayName()), parent, "map-room/" + part(detail.summary().gameType()) + part(detail.summary().mapName()));
        this.detail = detail;
        ready = Set.copyOf(detail.readyPlayers());
        countdown = detail.summary().readyCountdownSeconds();
        pending = FPSMMapSelectScreens.roomActionIntent(detail.summary());
        viewState = new RoomViewState(detail.summary().gameType(), detail.summary().mapName());
        roomActionStateChanged();
    }

    public MapRoomSummary roomSummary() {
        return detail.summary();
    }

    public boolean accepts(MapRoomDetail next) {
        return viewState.accepts(next.summary().gameType(), next.summary().mapName());
    }

    /** Called before restoring this cached view and requesting its full detail. */
    public void awaitFullDetail() {
        viewState.awaitFullDetail();
        modal = "";
        confirmation = null;
        roomActionStateChanged();
        requestLayout();
    }

    @Override
    public void applyDetail(MapRoomDetail next) {
        if (!viewState.acceptFullDetail(next.summary().gameType(), next.summary().mapName())) return;
        detail = next;
        ready = Set.copyOf(next.readyPlayers());
        countdown = next.summary().readyCountdownSeconds();
        roomActionStateChanged();
        requestLayout();
    }

    public void applyReadyState(String type, String map, int seconds, Set<UUID> players) {
        if (!viewState.acceptReady(type, map)) return;
        ready = Set.copyOf(players);
        countdown = seconds;
        roomActionStateChanged();
        requestLayout();
    }

    public boolean acceptsSources(MapImportSourcesS2CPacket packet) {
        return false;
    }

    public void applySources(MapImportSourcesS2CPacket packet) { /* Legacy authoring replies cannot mutate this view. */ }

    public void applyToast(MapRoomToastS2CPacket packet) {
        if (pending.phase() == RoomActionIntent.Phase.INVALIDATED) {
            message(tr("gui.fpsm.studio.room.session_ended"), true);
            return;
        }
        // Legacy toasts usually have no request id. Neither an error nor a
        // success proves the pending operation completed or can safely repeat.
        if (packet.error()) pending.markUnconfirmed();
        observedPendingPhase = pending.phase();
        String text = packet.message().getString();
        if (pending.pending()) text += " " + tr(pending.phase() == RoomActionIntent.Phase.UNCONFIRMED ? "gui.fpsm.studio.action_unconfirmed" : "gui.fpsm.studio.waiting");
        else if (!viewState.fullDetailAvailable()) text += " " + tr("gui.fpsm.studio.waiting");
        message(text, packet.error() || pending.phase() == RoomActionIntent.Phase.UNCONFIRMED);
    }

    private UUID self() {
        return minecraft == null || minecraft.player == null ? new UUID(0, 0) : minecraft.player.getUUID();
    }

    private boolean member() {
        return detail.summary().currentPlayerJoined() || detail.summary().currentPlayerSpectating();
    }

    private boolean idle() {
        return viewState.canMutate(pending);
    }

    private boolean wide() {
        return logicalBounds().width() >= 760;
    }

    private boolean allowed(MapRoomActionC2SPacket.Action operation, UUID player, String team) {
        if (!FPSMClient.getGlobalData().isMapSelectionButtonVisible()) return false;
        var room = detail.summary();
        return switch (operation) {
            case JOIN -> !member() && !room.full() && (!room.started() || room.allowJoinInProgress());
            case LEAVE -> member();
            case READY -> room.currentPlayerJoined() && !room.started();
            case SWITCH_TEAM -> (room.currentPlayerOp() || self().equals(player)) && TeamActionModel.availableTargetTeams(detail, player).contains(team);
            case KICK -> TeamActionModel.canKick(detail, player);
            case INVITE -> member() && !room.full() && (!room.started() || room.allowJoinInProgress()) && detail.availableInviteTargets().stream().anyMatch(p -> p.uuid().equals(player) && p.online());
            case DEBUG_START, DEBUG_RESET, DEBUG_NEW_ROUND, DEBUG_CLEANUP, DEBUG_SWITCH -> room.currentPlayerOp();
            default -> false;
        };
    }

    private void send(MapRoomActionC2SPacket.Action operation, UUID player, String team) {
        if (!idle() || !allowed(operation, player, team)) return;
        RoomActionIntent.Operation observed = observedOperation(operation);
        if (observed != null && !pending.begin(observed, player, team, !ready.contains(self()))) return;
        observedPendingPhase = pending.phase();
        target = player;
        modal = "";
        confirmation = null;
        // INVITE and DEBUG have no observable completion contract in this view.
        message(tr(observed == null ? "gui.fpsm.studio.action_submitted" : "gui.fpsm.studio.waiting"), false);
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(operation, detail.summary().gameType(), detail.summary().mapName(), player, team));
    }

    /** Render session state; cached view data must never complete an operation. */
    public void roomActionStateChanged() {
        RoomActionIntent.Phase phase = pending.phase();
        boolean detailAvailable = viewState.fullDetailAvailable();
        if (phase == observedPendingPhase && detailAvailable == observedDetailAvailable) return;
        RoomActionIntent.Phase previous = observedPendingPhase;
        observedPendingPhase = phase;
        observedDetailAvailable = detailAvailable;
        switch (phase) {
            case WAITING -> message(tr("gui.fpsm.studio.waiting"), false);
            case UNCONFIRMED -> message(tr("gui.fpsm.studio.action_unconfirmed"), true);
            case INVALIDATED -> message(tr("gui.fpsm.studio.room.session_ended"), true);
            case IDLE -> {
                if (!detailAvailable) message(tr("gui.fpsm.studio.waiting"), false);
                else if (previous != null) message(tr("gui.fpsm.studio.updated"), false);
            }
        }
    }

    private static RoomActionIntent.Operation observedOperation(MapRoomActionC2SPacket.Action action) {
        return switch (action) {
            case JOIN -> RoomActionIntent.Operation.JOIN;
            case LEAVE -> RoomActionIntent.Operation.LEAVE;
            case READY -> RoomActionIntent.Operation.READY;
            case SWITCH_TEAM -> RoomActionIntent.Operation.SWITCH_TEAM;
            case KICK -> RoomActionIntent.Operation.KICK;
            default -> null;
        };
    }

    private String actionLabel(MapRoomActionC2SPacket.Action action, String fallback) {
        if (!pending.is(observedOperation(action))) return tr(fallback);
        return tr(pending.phase() == RoomActionIntent.Phase.UNCONFIRMED ? "gui.fpsm.studio.result_unconfirmed" : "gui.fpsm.studio.waiting");
    }

    @Override
    protected void onScreenTick() {
        boolean access = FPSMClient.getGlobalData().isMapSelectionButtonVisible();
        if (access != observedAccess) {
            observedAccess = access;
            requestLayout();
        }
        roomActionStateChanged();
    }

    private boolean observedAccess = true;

    @Override
    protected String pageTitle() {
        return detail.summary().displayName();
    }

    @Override
    protected String pageSubtitle() {
        return StudioMapSelectionScreen.gameTypeText(detail.summary().gameType()) + " · " + detail.summary().mapName() + " · " + StudioMapSelectionScreen.statusText(detail.summary());
    }

    @Override
    protected StudioView.Element topControls(StudioView view) {
        var tabs = new ArrayList<StudioView.Element>();
        for (String value : List.of("details", "players", "settings", "more")) {
            if (value.equals("more") && !detail.summary().currentPlayerOp()) continue;
            tabs.add(view.button(new StudioComponents.Action("room/tab/" + value, tr("gui.fpsm.team_manage.tab." + value), Icon.NONE, Variant.DEFAULT, true, page.equals(value), "", () -> {
                page = value;
                bodyScroll = 0;
                requestLayout();
            }), Kind.TAB));
        }
        return view.wrap("room/tabs", Style.defaults().gap(10), tabs);
    }

    private boolean showReady() {
        return detail.summary().currentPlayerJoined() && !detail.summary().currentPlayerSpectating() && !detail.summary().started();
    }

    private List<StudioComponents.Action> primaryActions() {
        var actions = new ArrayList<StudioComponents.Action>();
        if (!member()) {
            actions.add(action("room/membership", actionLabel(MapRoomActionC2SPacket.Action.JOIN, "gui.fpsm.map_select.join"), Icon.PLAY, Variant.PRIMARY,
                    idle() && allowed(MapRoomActionC2SPacket.Action.JOIN, self(), ""), () -> send(MapRoomActionC2SPacket.Action.JOIN, self(), "")));
        } else if (showReady()) {
            actions.add(action("room/ready", actionLabel(MapRoomActionC2SPacket.Action.READY, ready.contains(self()) ? "gui.fpsm.team_manage.ready.off" : "gui.fpsm.team_manage.ready.on"), Icon.NONE,
                    ready.contains(self()) ? Variant.OUTLINED : Variant.PRIMARY, idle() && allowed(MapRoomActionC2SPacket.Action.READY, self(), ""), () -> send(MapRoomActionC2SPacket.Action.READY, self(), "")));
        }
        // Closing the view does not cancel or send a membership operation.
        actions.add(action("room/return-game", tr("gui.fpsm.studio.room.return_game"), Icon.PLAY,
                member() && (!showReady() || ready.contains(self())) ? Variant.PRIMARY : Variant.OUTLINED, true,
                () -> net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens.closeView(this, null)));
        return actions;
    }

    private List<StudioComponents.Action> teamActions() {
        if (!member()) return List.of();
        boolean access = FPSMClient.getGlobalData().isMapSelectionButtonVisible();
        return List.of(
                action("room/switch", actionLabel(MapRoomActionC2SPacket.Action.SWITCH_TEAM, "gui.fpsm.team_manage.context.switch"), Icon.SWAP, Variant.OUTLINED,
                        idle() && access && TeamActionModel.availableTargetTeams(detail, self()).stream().anyMatch(team -> allowed(MapRoomActionC2SPacket.Action.SWITCH_TEAM, self(), team)), () -> openPlayer(self(), "teams")),
                action("room/invite", tr("gui.fpsm.map_select.invite"), Icon.PLUS, Variant.OUTLINED,
                        idle() && access && !detail.summary().full() && (!detail.summary().started() || detail.summary().allowJoinInProgress()), () -> {
                            modal = "invite";
                            resetOverlayScroll();
                            requestLayout();
                        }));
    }

    private StudioComponents.Action leaveAction() {
        return action("room/leave", actionLabel(MapRoomActionC2SPacket.Action.LEAVE, "gui.fpsm.map_select.leave"), Icon.CLOSE, Variant.DANGER,
                idle() && allowed(MapRoomActionC2SPacket.Action.LEAVE, self(), ""), () -> {
                    modal = "leave";
                    resetOverlayScroll();
                    requestLayout();
                });
    }

    private StudioView.Element actionButton(StudioView view, StudioComponents.Action action) {
        return view.button(action).style(Style.defaults().height(Length.fixed(28)));
    }

    private StudioView.Element actionRow(StudioView view, String id, List<StudioComponents.Action> actions) {
        return view.row(id, Style.defaults().gap(6), actions.stream().map(action -> actionButton(view, action)).toList());
    }

    private String memberState() {
        if (detail.summary().currentPlayerSpectating()) return tr("gui.fpsm.studio.spectating");
        if (!member()) return tr("gui.fpsm.studio.not_member");
        return detail.players().stream().filter(player -> player.uuid().equals(self())).findFirst()
                .map(player -> tr("gui.fpsm.studio.room.your_team", teamName(player.teamName())))
                .orElseGet(() -> tr("gui.fpsm.studio.member"));
    }

    private String matchState() {
        return tr(detail.summary().started() ? "gui.fpsm.studio.room.in_progress" : "gui.fpsm.studio.room.waiting");
    }

    private StudioView.Element sidebar(StudioView view) {
        var entries = new ArrayList<StudioView.Element>();
        entries.add(view.paragraph("room/member-state", memberState(), TextSize.HEADING, Variant.DEFAULT));
        entries.add(view.paragraph("room/match-state", matchState(), TextSize.SMALL, Variant.MUTED));
        for (var action : primaryActions()) entries.add(actionButton(view, action));
        if (member()) {
            entries.add(actionRow(view, "room/team-actions", teamActions()));
            entries.add(view.column("room/leave-section", Style.defaults().padding(new Insets(6, 0, 0, 0)), List.of(actionButton(view, leaveAction()))));
        }
        return view.surface("room/side-card", Kind.INLINE_CARD, Style.defaults().padding(Insets.all(10)).gap(8), entries);
    }

    private StudioView.Element compactActions(StudioView view, int width) {
        var entries = new ArrayList<StudioView.Element>();
        entries.add(view.paragraph("room/compact-state", memberState() + " · " + matchState(), TextSize.SMALL, Variant.MUTED));
        entries.add(actionRow(view, "room/compact-primary", primaryActions()));
        if (member()) {
            var secondary = new ArrayList<>(teamActions());
            if (width >= 360) {
                secondary.add(leaveAction());
                entries.add(actionRow(view, "room/compact-secondary", secondary));
            } else {
                entries.add(actionRow(view, "room/compact-secondary", secondary));
                entries.add(actionButton(view, leaveAction()));
            }
        }
        return view.surface("room/compact-actions", Kind.INLINE_CARD, Style.defaults().padding(Insets.all(8)).gap(6), entries);
    }

    @Override
    protected List<StudioComponents.Action> footerActions() {
        return List.of();
    }

    @Override
    protected void composeBody(Composition content, Bounds body) {
        var view = content.view();
        Bounds main;
        if (wide()) {
            var split = view.layout(view.row("room/split", Style.fill().gap(12), List.of(view.placeholder("room/main", Style.fill()), view.placeholder("room/side", Style.fill().width(Length.fixed(216))))), body);
            main = split.bounds("room/main");
            sideScroll = content.scroll("room/sidebar", sidebar(view), split.bounds("room/side"), 0, sideScroll, value -> {
                sideScroll = value;
                requestLayout();
            });
        } else {
            var footer = compactActions(view, Math.max(0, body.width() - 8));
            int wanted = view.layoutContent(footer, 0, 0, Math.max(0, body.width() - 8)).contentHeight();
            int gap = Math.min(8, body.height());
            int available = Math.max(0, body.height() - gap);
            // Keep a useful roster viewport; at extreme heights both panes scroll.
            int rosterReserve = Math.min(120, (available + 1) / 2);
            int footerHeight = Math.min(wanted, available - rosterReserve);
            var split = view.layout(view.column("room/compact-split", Style.fill().gap(gap), List.of(
                    view.placeholder("room/main", Style.fill()),
                    view.placeholder("room/action-area", Style.defaults().height(Length.fixed(footerHeight))))), body);
            main = split.bounds("room/main");
            actionScroll = content.scroll("room/action-scroll", footer, split.bounds("room/action-area"), 0, actionScroll, value -> {
                actionScroll = value;
                requestLayout();
            });
        }
        var entries = switch (page) {
            case "players" -> players(view, Math.max(0, main.width() - 8));
            case "settings" -> settings(view);
            case "more" -> management(view);
            default -> overview(view);
        };
        bodyScroll = content.scroll("room/body", view.column("room/content/" + page, Style.defaults().gap(10), entries), main, 0, bodyScroll, value -> {
            bodyScroll = value;
            requestLayout();
        });
    }

    private String readiness() {
        return tr("gui.fpsm.team_manage.ready_summary", ready.size(), detail.players().stream().filter(p -> !p.spectator()).count()) + (countdown > 0 ? " · " + tr("gui.fpsm.team_manage.countdown", countdown) : "");
    }

    private boolean spectatorTeam(String team) {
        return detail.teams().stream().anyMatch(value -> value.name().equals(team) && value.spectator()) || detail.players().stream().anyMatch(player -> player.teamName().equals(team) && player.spectator());
    }

    private String teamName(String team) {
        if (spectatorTeam(team)) return tr("gui.fpsm.studio.room.spectators");
        return team.equalsIgnoreCase("ct") || team.equalsIgnoreCase("t") ? team.toUpperCase(Locale.ROOT) : team;
    }

    private List<StudioView.Element> players(StudioView view, int width) {
        var groups = new ArrayList<StudioView.Element>();
        groups.add(view.paragraph("room/readiness", readiness(), TextSize.BODY, Variant.MUTED));
        var names = new LinkedHashSet<String>();
        detail.teams().stream().sorted(Comparator.comparing(MapRoomTeamInfo::spectator).thenComparing(MapRoomTeamInfo::name)).forEach(team -> names.add(team.name()));
        detail.players().forEach(player -> names.add(player.teamName()));
        var teams = new ArrayList<StudioView.Element>();
        var spectators = new ArrayList<StudioView.Element>();
        for (String team : names) (spectatorTeam(team) ? spectators : teams).add(teamCard(view, team));
        if (!teams.isEmpty()) groups.add(view.grid("room/team-grid", Style.defaults().gap(12).columns(width >= 432 ? 2 : 1), teams));
        groups.addAll(spectators);
        if (names.isEmpty()) groups.add(view.text("room/empty", tr("gui.fpsm.studio.empty_team")));
        return groups;
    }

    private StudioView.Element teamCard(StudioView view, String team) {
        String id = "room/team/" + part(team);
        var rows = new ArrayList<StudioView.Element>();
        long count = detail.players().stream().filter(player -> player.teamName().equals(team)).count();
        rows.add(view.button(action(id + "/toggle", teamName(team) + " · " + count, collapsed.contains(team) ? Icon.CHEVRON_RIGHT : Icon.CHEVRON_DOWN, Variant.DEFAULT, true,
                () -> {
                    if (!collapsed.remove(team)) collapsed.add(team);
                    requestLayout();
                })).style(Style.defaults().height(Length.fixed(26))));
        if (!collapsed.contains(team)) {
            for (var player : detail.players()) if (player.teamName().equals(team)) {
                String pid = id + "/player/" + player.uuid();
                String name = player.uuid().equals(self()) ? tr("gui.fpsm.studio.room.self_name", player.name()) : player.name();
                String state = tr(!player.online() ? "gui.fpsm.map_select.offline" : ready.contains(player.uuid()) ? "gui.fpsm.team_manage.ready_mark" : !player.spectator() && !detail.summary().started() ? "gui.fpsm.studio.room.not_ready" : "gui.fpsm.map_select.online");
                rows.add(view.actionRow(new StudioComponents.Action(pid, name, Icon.NONE, Variant.DEFAULT,
                        idle() && player.online() && (detail.summary().currentPlayerOp() || player.uuid().equals(self())), player.uuid().equals(self()), "",
                        () -> openPlayer(player.uuid(), "player")), Style.defaults().height(Length.content().limits(32, Integer.MAX_VALUE)).padding(Insets.all(8)).gap(8),
                        List.of(
                                view.paragraph(pid + "/name", name), view.text(pid + "/state", state, TextSize.SMALL, Variant.MUTED).style(Style.content()))));
            }
            if (count == 0) rows.add(view.paragraph(id + "/empty", tr("gui.fpsm.studio.empty_team"), TextSize.SMALL, Variant.MUTED).style(Style.defaults().padding(Insets.all(8))));
        }
        return view.surface(id + "/group", Kind.INLINE_CARD, Style.defaults().padding(Insets.all(6)).gap(4), rows);
    }

    private List<StudioView.Element> overview(StudioView view) {
        var room = detail.summary();
        return List.of(view.paragraph("room/rules", detail.rulesKey().isBlank() ? tr("gui.fpsm.map_select.detail.rules.none") : tr(detail.rulesKey())),
                view.paragraph("room/count", tr("gui.fpsm.map_select.detail.players", room.joinedPlayers(), room.maxPlayers() < 0 ? "∞" : room.maxPlayers())),
                view.paragraph("room/area", tr("gui.fpsm.map_select.detail.area", room.areaText()), TextSize.SMALL, Variant.MUTED),
                view.paragraph("room/dimension", tr("gui.fpsm.map_select.detail.dimension", room.dimension()), TextSize.SMALL, Variant.MUTED),
                view.paragraph("room/member-note", tr("gui.fpsm.studio.membership_hint"), TextSize.SMALL, Variant.MUTED));
    }

    private List<StudioView.Element> settings(StudioView view) {
        var entries = new ArrayList<StudioView.Element>();
        entries.add(view.paragraph("settings/read-only", tr("gui.fpsm.studio.read_only_settings"), TextSize.SMALL, Variant.MUTED));
        entries.add(view.button(action("settings/workbench", tr("editor.workbench.open"), Icon.FOLDER, Variant.OUTLINED, idle(), () -> {
            net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens.closeView(this, null);
            WorkbenchClient.openWorkbench();
        })));
        entries.add(view.input(new StudioView.Input("settings/search", tr("gui.fpsm.map_select.settings.search"), search, true, false, false, "", null, value -> {
            search = value;
            requestLayout();
        }, null)));
        String last = null;
        for (var setting : detail.settings().stream().sorted(Comparator.comparing(MapRoomSettingInfo::category).thenComparing(MapRoomSettingInfo::name)).toList()) {
            if (!(tr(setting.translationKey()) + " " + setting.name()).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))) continue;
            if (!setting.category().equals(last)) {
                last = setting.category();
                entries.add(view.text("settings/category/" + part(last), tr(setting.categoryTranslationKey()), TextSize.HEADING, Variant.MUTED));
            }
            String id = "settings/value/" + part(setting.name());
            entries.add(view.surface(id + "/card", Kind.INLINE_CARD, Style.defaults().padding(Insets.all(10)).gap(5), List.of(
                    view.row(id + "/row", Style.defaults().gap(10), List.of(view.paragraph(id + "/label", tr(setting.translationKey())), view.paragraph(id + "/value", setting.value()).style(Style.defaults().width(Length.fixed(110))))),
                    view.paragraph(id + "/description", tr(setting.descriptionKey()), TextSize.SMALL, Variant.MUTED))));
        }
        return entries;
    }

    private List<StudioView.Element> management(StudioView view) {
        if (!detail.summary().currentPlayerOp()) return List.of(view.paragraph("manage/denied", tr("gui.fpsm.map_select.manage.no_permission")));
        var entries = new ArrayList<StudioView.Element>();
        entries.add(view.paragraph("manage/title", tr("gui.fpsm.map_select.manage.match_actions")));
        for (var operation : List.of(MapRoomActionC2SPacket.Action.DEBUG_START, MapRoomActionC2SPacket.Action.DEBUG_RESET, MapRoomActionC2SPacket.Action.DEBUG_NEW_ROUND, MapRoomActionC2SPacket.Action.DEBUG_CLEANUP, MapRoomActionC2SPacket.Action.DEBUG_SWITCH)) {
            String label = tr("gui.fpsm.map_select.debug." + operation.name().substring(6).toLowerCase(Locale.ROOT));
            entries.add(view.button(action("manage/" + operation, label, Icon.PLAY, Variant.OUTLINED, idle() && allowed(operation, null, ""), () -> {
                confirmation = operation;
                modal = "confirm";
                resetOverlayScroll();
                requestLayout();
            })).style(Style.defaults()));
        }
        return entries;
    }

    private void openPlayer(UUID player, String kind) {
        target = player;
        modal = kind;
        resetOverlayScroll();
        requestLayout();
    }

    @Override
    protected void composeOverlay(Composition content) {
        if (modal.isEmpty()) return;
        var actions = new ArrayList<StudioComponents.Action>();
        String title = tr("gui.fpsm.map_select.invite.title");
        if (modal.equals("invite")) {
            for (var player : detail.availableInviteTargets()) actions.add(action("invite/" + player.uuid(), player.name(), Icon.PLUS, Variant.DEFAULT, idle() && allowed(MapRoomActionC2SPacket.Action.INVITE, player.uuid(), ""), () -> send(MapRoomActionC2SPacket.Action.INVITE, player.uuid(), "")));
            if (actions.isEmpty()) actions.add(action("invite/empty", tr("gui.fpsm.map_select.invite.empty"), Icon.NONE, Variant.MUTED, false, () -> {}));
        } else if (modal.equals("leave")) {
            title = tr("gui.fpsm.studio.room.confirm_leave");
            actions.add(action("room/leave-confirm", tr("gui.fpsm.map_select.leave"), Icon.CLOSE, Variant.DANGER,
                    idle() && allowed(MapRoomActionC2SPacket.Action.LEAVE, self(), ""), () -> send(MapRoomActionC2SPacket.Action.LEAVE, self(), "")));
        } else if (modal.equals("confirm")) {
            title = tr("gui.fpsm.studio.confirm_action");
            var operation = confirmation;
            if (operation == MapRoomActionC2SPacket.Action.KICK) title = tr("gui.fpsm.studio.confirm_kick", detail.players().stream().filter(p -> p.uuid().equals(target)).map(MapRoomPlayerInfo::name).findFirst().orElse(""));
            String label = operation == MapRoomActionC2SPacket.Action.KICK ? tr("gui.fpsm.team_manage.context.kick") : tr("gui.fpsm.map_select.debug." + operation.name().substring(6).toLowerCase(Locale.ROOT));
            actions.add(action("confirm/action", label, Icon.WARNING, Variant.DANGER, idle() && allowed(operation, target, ""), () -> send(operation, target, "")));
        } else {
            title = detail.players().stream().filter(p -> p.uuid().equals(target)).map(MapRoomPlayerInfo::name).findFirst().orElse(tr("gui.fpsm.team_manage.context.switch"));
            for (String team : TeamActionModel.availableTargetTeams(detail, target)) actions.add(action("team-choice/" + part(team), teamName(team), Icon.TEAM, Variant.DEFAULT, idle() && allowed(MapRoomActionC2SPacket.Action.SWITCH_TEAM, target, team), () -> send(MapRoomActionC2SPacket.Action.SWITCH_TEAM, target, team)));
            if (modal.equals("player") && TeamActionModel.canKick(detail, target)) actions.add(action("player/kick", tr("gui.fpsm.team_manage.context.kick"), Icon.DELETE, Variant.DANGER, idle(), () -> {
                confirmation = MapRoomActionC2SPacket.Action.KICK;
                modal = "confirm";
                requestLayout();
            }));
        }
        choices(content, "room/modal/" + modal, title, actions, () -> {
            modal = "";
            confirmation = null;
            requestLayout();
        });
    }

    @Override
    public void onClose() {
        if (!modal.isEmpty()) {
            modal = "";
            confirmation = null;
            requestLayout();
            return;
        }
        super.onClose();
    }
}
