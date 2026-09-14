package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualItemHeightMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.FPSMTeamActionScreen;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.client.screen.team.TeamActionModel;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSummary;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomTeamInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Unified room lobby with a stable accordion roster and an operator-only context menu. */
public class Ldlib2TeamManageScreen extends Ldlib2MapChildScreen implements TeamRowBinder.Context {
    private final Set<String> collapsedTeams = new HashSet<>();
    private final Set<UUID> readyPlayers = new HashSet<>();
    private int countdown;
    private String activeTab = "details";
    private String focusAfterRefresh;
    private Label header;
    private Label subtitle;
    private Label readySummary;
    private Label detailsName;
    private Ldlib2MapThumbnailElement detailsPreview;
    private List<Row> rosterItems = List.of();
    private Set<UUID> rosterReadyPlayers = Set.of();
    private ScrollerView detailsPanel;
    private VirtualScrollerView<Row> roster;
    private TeamRowBinder rows;
    private MapLobbyTabs tabs;
    private AccessibleButton join;
    private AccessibleButton leave;
    private AccessibleButton ready;
    private AccessibleButton switchTeam;
    private AccessibleButton back;
    private UIElement menuLayer;
    private UIElement menu;
    private Label menuPlayer;
    private AccessibleButton menuMove;
    private AccessibleButton menuKick;
    private UUID menuTarget;
    private boolean bound;

    public Ldlib2TeamManageScreen(MapRoomDetail detail, Screen parent) {
        super(Ldlib2XmlUi.load("fpsmatch:ldlib2/ui/team_manage.xml"),
                Component.translatable("gui.fpsm.team_manage.title"), detail, parent);
        readyPlayers.addAll(detail.readyPlayers());
        countdown = detail.summary().readyCountdownSeconds();
    }

    @Override
    public void init() {
        super.init();
        if (!bound) bind();
        layoutPage();
        refreshContent();
    }

    private <T extends UIElement> T require(String suffix, Class<T> type) {
        return Ldlib2XmlUi.require(modularUI.ui, "fpsmatch.team_manage." + suffix, type);
    }

    private void bind() {
        header = require("header", Label.class);
        subtitle = require("subtitle", Label.class);
        readySummary = require("ready_summary", Label.class);
        detailsPanel = require("details", ScrollerView.class);
        detailsName = require("details.name", Label.class);
        detailsPreview = require("details.preview", Ldlib2MapThumbnailElement.class);
        roster = require("roster", VirtualScrollerView.class);
        join = require("join", AccessibleButton.class);
        leave = require("leave", AccessibleButton.class);
        ready = require("ready", AccessibleButton.class);
        switchTeam = require("switch", AccessibleButton.class);
        back = require("back", AccessibleButton.class);
        menuLayer = require("menu_layer", UIElement.class);
        menu = require("menu", UIElement.class);
        menuPlayer = require("menu.player", Label.class);
        menuMove = require("menu.move", AccessibleButton.class);
        menuKick = require("menu.kick", AccessibleButton.class);
        tabs = new MapLobbyTabs(modularUI.ui, "fpsmatch.team_manage", activeTab, tab -> {
            if ("details".equals(tab) || "players".equals(tab)) showTab(tab);
            else openLobbyTab(tab);
        });
        rows = new TeamRowBinder(this);
        roster.virtualScrollerViewStyle(style -> style.itemHeightMode(VirtualItemHeightMode.FIXED)
                .estimatedItemHeight(36).overscanPixels(108));
        roster.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL).adaptiveWidth(false).adaptiveHeight(false));
        roster.setItemUIProvider(row -> row.team() != null ? rows.headerRow(row.team()) : rows.playerRow(row.player()));
        roster.setBeforeMountItems(() -> {
            UIElement focused = modularUI.getFocusedElement();
            if (focusAfterRefresh == null && focused != null && roster.isAncestorOf(focused)) {
                focusAfterRefresh = focused.getId();
            }
        });
        detailsPanel.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL).adaptiveWidth(false).adaptiveHeight(false));
        join.setOnClick(event -> {
            if (!detail.summary().currentPlayerJoined() && !detail.summary().currentPlayerSpectating()) {
                send(MapRoomActionC2SPacket.Action.JOIN, selfId());
            }
        });
        leave.setOnClick(event -> send(MapRoomActionC2SPacket.Action.LEAVE, selfId()));
        ready.setOnClick(event -> send(MapRoomActionC2SPacket.Action.READY, selfId()));
        switchTeam.setOnClick(event -> openMoveDialog(findPlayer(selfId())));
        back.setOnClick(event -> onClose());
        menuMove.setOnClick(event -> {
            MapRoomPlayerInfo player = findPlayer(menuTarget);
            closeMenu(false);
            if (detail.summary().currentPlayerOp()) openMoveDialog(player);
        });
        menuKick.setOnClick(event -> {
            MapRoomPlayerInfo player = findPlayer(menuTarget);
            closeMenu(true);
            if (player != null && TeamActionModel.canKick(detail, player.uuid())) {
                Minecraft.getInstance().setScreen(FPSMTeamActionScreen.confirmKick(detail, this, player));
            }
        });
        show(menuLayer, false);
        bound = true;
    }

    @Override
    protected void onDetailApplied() {
        readyPlayers.clear();
        readyPlayers.addAll(detail.readyPlayers());
        countdown = detail.summary().readyCountdownSeconds();
        if (bound) refreshContent();
    }

    public void applyReadyState(String gameType, String mapName, int seconds, Set<UUID> players) {
        if (!detail.summary().gameType().equals(gameType) || !detail.summary().mapName().equals(mapName)) return;
        countdown = seconds;
        readyPlayers.clear();
        if (players != null) readyPlayers.addAll(players);
        if (bound) refreshContent();
    }

    private void refreshContent() {
        var summary = detail.summary();
        header.setValue(Component.literal(summary.displayName()));
        subtitle.setValue(Component.literal(Ldlib2MapSelectionScreen.gameTypeText(summary.gameType()).getString()
                + " / " + summary.mapName()));
        long total = detail.players().stream().filter(player -> !player.spectator()).count();
        long readyCount = detail.players().stream().filter(player -> !player.spectator() && isReady(player.uuid())).count();
        Component readiness = Component.translatable("gui.fpsm.team_manage.ready_summary", readyCount, total);
        if (countdown > 0 && readyCount == total && total > 0) {
            readiness = readiness.copy().append("  ").append(Component.translatable("gui.fpsm.team_manage.countdown", countdown));
        }
        readySummary.setValue(readiness);
        detailsName.setValue(Component.literal(summary.displayName()));
        detailsPreview.setThumbnailData(detail.backgroundTexture().isBlank() ? detail.iconTexture() : detail.backgroundTexture(),
                summary.mapName(), summary.gameType(), summary.displayName());
        show(detailsPreview, !detail.backgroundTexture().isBlank() || !detail.iconTexture().isBlank());
        require("details.mode", Label.class).setValue(Component.translatable("gui.fpsm.map_select.info.mode",
                Ldlib2MapSelectionScreen.gameTypeText(summary.gameType())));
        require("details.status", Label.class).setValue(Component.translatable("gui.fpsm.map_select.info.status",
                Ldlib2MapSelectionScreen.statusText(summary)));
        require("details.players", Label.class).setValue(Component.translatable("gui.fpsm.map_select.detail.players",
                summary.joinedPlayers(), summary.maxPlayers() < 0 ? "?" : summary.maxPlayers()));
        require("details.area", Label.class).setValue(Component.translatable("gui.fpsm.map_select.detail.area", summary.areaText()));
        require("details.dimension", Label.class).setValue(Component.translatable("gui.fpsm.map_select.detail.dimension", summary.dimension()));
        require("details.rules", Label.class).setValue(detail.rulesKey().isBlank()
                ? Component.translatable("gui.fpsm.map_select.detail.rules.none")
                : Component.translatable("gui.fpsm.map_select.detail.rules", Component.translatable(detail.rulesKey())));
        require("details.ready", Label.class).setValue(readiness);
        rebuildRoster();
        boolean joined = summary.currentPlayerJoined() || summary.currentPlayerSpectating();
        boolean canJoin = !summary.full() && (!summary.started() || summary.allowJoinInProgress());
        join.setAvailability(!joined, canJoin);
        leave.setAvailability(joined, joined);
        ready.setText(Component.translatable(isReady(selfId())
                ? "gui.fpsm.team_manage.ready.off" : "gui.fpsm.team_manage.ready.on"));
        boolean canReady = summary.currentPlayerJoined() && !summary.started();
        ready.setAvailability(true, canReady);
        boolean canSwitchTeam = !TeamActionModel.availableTargetTeams(detail, selfId()).isEmpty();
        switchTeam.setAvailability(true, canSwitchTeam);
        updateActionHints(summary, joined, canJoin, canReady, canSwitchTeam);
        tabs.update(summary.currentPlayerOp(), true);
        updatePanels();
        if (menuTarget != null) {
            if (!summary.currentPlayerOp() || findPlayer(menuTarget) == null) closeMenu(false);
            else updateMenu();
        }
        layoutActions();
    }

    private void rebuildRoster() {
        List<Row> items = new ArrayList<>();
        List<MapRoomTeamInfo> teams = detail.teams().stream()
                .sorted(Comparator.comparing(MapRoomTeamInfo::spectator).thenComparing(MapRoomTeamInfo::name)).toList();
        collapsedTeams.retainAll(teams.stream().map(MapRoomTeamInfo::name).toList());
        for (MapRoomTeamInfo team : teams) {
            items.add(new Row(team, null));
            if (isExpanded(team.name())) {
                detail.players().stream().filter(player -> player.teamName().equals(team.name()))
                        .sorted(Comparator.comparing(MapRoomPlayerInfo::name, String.CASE_INSENSITIVE_ORDER))
                        .forEach(player -> items.add(new Row(null, player)));
            }
        }
        if (!items.equals(rosterItems) || !readyPlayers.equals(rosterReadyPlayers)) {
            rosterItems = List.copyOf(items);
            rosterReadyPlayers = Set.copyOf(readyPlayers);
            roster.setItems(items);
        }
    }

    @Override public boolean isReady(UUID player) { return readyPlayers.contains(player); }
    @Override public boolean isOperator() { return detail.summary().currentPlayerOp(); }
    @Override public boolean isExpanded(String team) { return !collapsedTeams.contains(team); }

    @Override
    public void toggleTeam(String team) {
        if (!collapsedTeams.remove(team)) collapsedTeams.add(team);
        focusAfterRefresh = TeamRowBinder.TEAM_HEADER_PREFIX + team + ".toggle";
        rebuildRoster();
    }

    @Override
    public void onRowClick(MapRoomPlayerInfo player, int button) {
        // Left click does not select a player; keyboard activation has the context-menu equivalent.
    }

    private void openMoveDialog(MapRoomPlayerInfo player) {
        if (player == null || (!isOperator() && !player.uuid().equals(selfId()))) return;
        Minecraft.getInstance().setScreen(new FPSMTeamActionScreen(detail, this, player));
    }

    private void openMenu(MapRoomPlayerInfo player, double x, double y) {
        if (!isOperator() || player == null) return;
        menuTarget = player.uuid();
        updateMenu();
        show(menuLayer, true);
        int menuWidth = Math.min(184, Math.max(1, width - 12));
        int menuHeight = 92;
        place(menu, (int) Math.max(6, Math.min(x, width - menuWidth - 6)),
                (int) Math.max(6, Math.min(y, height - menuHeight - 6)), menuWidth, menuHeight);
        if (menuMove.isActive()) menuMove.focus();
        else if (menuKick.isActive()) menuKick.focus();
        else modularUI.clearFocus();
    }

    private void updateMenu() {
        MapRoomPlayerInfo player = findPlayer(menuTarget);
        if (player == null) return;
        menuPlayer.setValue(Component.literal(player.name()));
        menuMove.setAvailability(true, isOperator() && !TeamActionModel.availableTargetTeams(detail, menuTarget).isEmpty());
        menuKick.setAvailability(true, TeamActionModel.canKick(detail, menuTarget));
    }

    private void closeMenu(boolean restoreFocus) {
        UUID target = menuTarget;
        menuTarget = null;
        show(menuLayer, false);
        if (restoreFocus && target != null) focusAfterRefresh = "fpsmatch.team_manage.select." + target;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        setKeyboardFocusVisible(false);
        if (menuTarget != null) {
            UIElement hit = hitElementAt(x, y);
            if (hit == null || !(hit == menu || menu.isAncestorOf(hit))) {
                closeMenu(true);
                return true;
            }
            return super.mouseClicked(x, y, button);
        }
        if (button == 1 && "players".equals(activeTab)) {
            MapRoomPlayerInfo player = playerAt(hitElementAt(x, y));
            if (player != null && isOperator()) {
                openMenu(player, x, y);
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return menuTarget != null || super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (menuTarget != null) {
            setKeyboardFocusVisible(true);
            if (key == GLFW.GLFW_KEY_ESCAPE) { closeMenu(true); return true; }
            if (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                AccessibleButton next = menuMove.isFocused() && menuKick.isActive() ? menuKick : menuMove;
                if (!next.isActive()) next = menuKick;
                if (next.isActive()) next.focus();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_KP_ENTER) {
                return super.keyPressed(key, scan, modifiers);
            }
            return true;
        }
        if ("players".equals(activeTab) && isOperator()
                && (key == GLFW.GLFW_KEY_MENU || key == GLFW.GLFW_KEY_F10 && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0
                || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE)) {
            UIElement focused = modularUI.getFocusedElement();
            MapRoomPlayerInfo player = playerAt(focused);
            if (player != null) {
                setKeyboardFocusVisible(true);
                openMenu(player, focused.getPositionX() + 24, focused.getPositionY() + focused.getSizeHeight());
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        if (focusAfterRefresh != null && menuTarget == null) {
            String id = focusAfterRefresh;
            focusAfterRefresh = null;
            modularUI.ui.rootElement.selectId(id, AccessibleButton.class).findFirst()
                    .filter(button -> button.isVisible() && button.isDisplayed() && button.isActive())
                    .ifPresent(AccessibleButton::focus);
        }
    }

    private MapRoomPlayerInfo playerAt(UIElement target) {
        for (UIElement current = target; current != null; current = current.getParent()) {
            String id = current.getId();
            if (id != null && id.startsWith(TeamRowBinder.PLAYER_ROW_PREFIX)) {
                try { return findPlayer(UUID.fromString(id.substring(TeamRowBinder.PLAYER_ROW_PREFIX.length()))); }
                catch (IllegalArgumentException ignored) { return null; }
            }
        }
        return null;
    }

    public void showTab(String tab) {
        focusAfterRefresh = null;
        activeTab = "players".equals(tab) ? "players" : "details";
        if (bound) {
            closeMenu(false);
            updatePanels();
            layoutPage();
        }
    }

    private void updatePanels() {
        show(roster, "players".equals(activeTab));
        show(detailsPanel, "details".equals(activeTab));
        readySummary.setVisible("players".equals(activeTab));
        tabs.select(activeTab);
    }

    private void layoutPage() {
        int margin = Math.min(16, Math.max(8, width / 32));
        int innerWidth = Math.max(1, width - margin * 2);
        int top = height < 300 ? 61 : 76;
        place(header, margin, 8, innerWidth, 21);
        place(subtitle, margin, 32, innerWidth, 13);
        subtitle.setVisible(height >= 300);
        tabs.layout(width, height);
        place(readySummary, margin, top, innerWidth, 14);
        place(roster, margin, top + 20, innerWidth, Math.max(1, height - top - 60));
        place(detailsPanel, margin, top, innerWidth, Math.max(1, height - top - 40));
        int previewWidth = Math.max(1, Math.min(320, innerWidth - 8));
        detailsPreview.layout(l -> l.width(previewWidth).height(previewWidth * 9f / 16f));
        layoutActions();
        if (menuTarget != null) closeMenu(false);
    }

    private void layoutActions() {
        int margin = Math.min(16, Math.max(8, width / 32));
        int buttonWidth = Math.max(1, (width - margin * 2 - 18) / 4);
        List<AccessibleButton> actions = List.of(join.isVisible() ? join : leave, switchTeam, ready, back);
        for (int i = 0; i < actions.size(); i++) {
            place(actions.get(i), margin + i * (buttonWidth + 6), height - 34, buttonWidth, 26);
        }
    }

    private void updateActionHints(MapRoomSummary summary, boolean joined, boolean canJoin,
                                   boolean canReady, boolean canSwitchTeam) {
        setActionHint(join, !joined && !canJoin
                ? summary.full()
                ? Component.translatable("gui.fpsm.team_manage.join.unavailable.full")
                : Component.translatable("gui.fpsm.team_manage.join.unavailable.in_progress")
                : Component.empty());
        setActionHint(ready, !canReady
                ? summary.currentPlayerSpectating()
                ? Component.translatable("gui.fpsm.team_manage.ready.unavailable.spectator")
                : summary.started()
                ? Component.translatable("gui.fpsm.team_manage.ready.unavailable.in_progress")
                : Component.translatable("gui.fpsm.team_manage.ready.unavailable.join_first")
                : Component.empty());
        setActionHint(switchTeam, !canSwitchTeam
                ? Component.translatable("gui.fpsm.team_manage.switch.unavailable")
                : Component.empty());
    }

    private static void setActionHint(AccessibleButton button, Component hint) {
        button.setAccessibleHint(() -> hint);
        button.style(style -> style.tooltips(hint));
    }

    private static void place(UIElement element, int x, int y, int width, int height) {
        element.layout(l -> l.positionType(YogaPositionType.ABSOLUTE).rightAuto().bottomAuto()
                .left(x).top(y).width(width).height(height));
    }

    private static void show(UIElement element, boolean visible) {
        if (!visible && element.getModularUI() != null && (element.isFocused() || element.isChildFocused())) {
            element.getModularUI().clearFocus();
        }
        element.setVisible(visible);
        element.setDisplay(visible);
        element.setAllowHitTest(visible);
    }

    private UUID selfId() {
        return Minecraft.getInstance().player == null ? new UUID(0, 0) : Minecraft.getInstance().player.getUUID();
    }

    private MapRoomPlayerInfo findPlayer(UUID player) {
        return detail.players().stream().filter(info -> info.uuid().equals(player)).findFirst().orElse(null);
    }

    private void send(MapRoomActionC2SPacket.Action action, UUID player) {
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(action,
                detail.summary().gameType(), detail.summary().mapName(), player));
    }

    @Override
    public void onClose() {
        if (menuTarget != null) { closeMenu(true); return; }
        super.onClose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.drawMapIndex(graphics, width, height);
    }

    private record Row(MapRoomTeamInfo team, MapRoomPlayerInfo player) { }
}
