package com.ptcrys.fpsmatch.common.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualItemHeightMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2.Ldlib2MapChildScreen;
import com.ptcrys.fpsmatch.common.client.screen.team.TeamActionModel;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomTeamInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.Comparator;
import java.util.UUID;

/** Team picker and kick confirmation. The parent lobby remains visible underneath. */
public final class FPSMTeamActionScreen extends Ldlib2MapChildScreen {
    private final UUID player;
    private final boolean kickConfirmation;
    private UIElement panel;
    private Label playerLabel;
    private Label status;
    private VirtualScrollerView<MapRoomTeamInfo> teamList;
    private AccessibleButton confirm;
    private AccessibleButton cancel;
    private String selectedTeam;
    private boolean bound;
    private boolean submitted;

    public FPSMTeamActionScreen(MapRoomDetail detail, Screen parent, MapRoomPlayerInfo player) {
        this(detail, parent, player, false);
    }

    public static FPSMTeamActionScreen confirmKick(MapRoomDetail detail, Screen parent, MapRoomPlayerInfo player) {
        return new FPSMTeamActionScreen(detail, parent, player, true);
    }

    private FPSMTeamActionScreen(MapRoomDetail detail, Screen parent, MapRoomPlayerInfo player, boolean kickConfirmation) {
        super(Ldlib2XmlUi.load("fpsmatch:ldlib2/ui/team_action.xml"),
                Component.translatable(kickConfirmation ? "gui.fpsm.team_manage.context.kick"
                        : "gui.fpsm.team_manage.context.switch"), detail, parent);
        this.player = player.uuid();
        this.kickConfirmation = kickConfirmation;
    }

    private <T extends UIElement> T require(String suffix, Class<T> type) {
        return Ldlib2XmlUi.require(modularUI.ui, "fpsmatch.team_action." + suffix, type);
    }

    @Override
    public void init() {
        super.init();
        if (parent != null && (parent.width != width || parent.height != height)) {
            parent.resize(Minecraft.getInstance(), width, height);
        }
        if (!bound) {
            panel = require("panel", UIElement.class);
            playerLabel = require("player", Label.class);
            status = require("status", Label.class);
            teamList = require("actions", VirtualScrollerView.class);
            confirm = require("confirm", AccessibleButton.class);
            cancel = require("cancel", AccessibleButton.class);
            cancel.setOnClick(event -> onClose());
            confirm.setOnClick(event -> confirmAction());
            require("title", Label.class).setValue(getTitle());
            if (kickConfirmation) {
                teamList.setVisible(false);
                teamList.setDisplay(false);
                teamList.setAllowHitTest(false);
                confirm.removeClass("btn-primary");
                confirm.addClass("btn-danger");
                confirm.setText(Component.translatable("gui.fpsm.team_manage.kick.confirm"));
                status.textStyle(style -> style.textWrap(TextWrap.WRAP));
                cancel.focus();
            }
            teamList.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL).adaptiveWidth(false).adaptiveHeight(false));
            teamList.virtualScrollerViewStyle(style -> style.itemHeightMode(VirtualItemHeightMode.FIXED)
                    .estimatedItemHeight(34).overscanPixels(68));
            teamList.setItemUIProvider(this::teamRow);
            bound = true;
        }
        layoutDialog();
        refreshContent();
    }

    @Override
    protected void onDetailApplied() {
        if (bound) refreshContent();
    }

    private boolean permitted() {
        return detail.summary().currentPlayerOp() || !kickConfirmation && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.getUUID().equals(player);
    }

    private void refreshContent() {
        MapRoomPlayerInfo target = detail.players().stream()
                .filter(info -> info.uuid().equals(player)).findFirst().orElse(null);
        if (target == null || !permitted()) { onClose(); return; }
        playerLabel.setValue(Component.literal(target.name() + " / " + target.teamName()));
        if (kickConfirmation) {
            status.setValue(Component.translatable("gui.fpsm.team_manage.kick.message", target.name()));
            confirm.setAvailability(true, !submitted && TeamActionModel.canKick(detail, player));
            return;
        }
        if (selectedTeam != null && !TeamActionModel.availableTargetTeams(detail, player).contains(selectedTeam)) {
            selectedTeam = null;
        }
        teamList.setItems(detail.teams().stream()
                .sorted(Comparator.comparing(MapRoomTeamInfo::spectator).thenComparing(MapRoomTeamInfo::name)).toList());
        teamList.refreshVisibleItems();
        updateSelection();
    }

    private UIElement teamRow(MapRoomTeamInfo team) {
        AccessibleButton button = new AccessibleButton();
        button.setId("fpsmatch.team_action.move." + team.name());
        Component label = team.spectator() ? Component.translatable("gui.fpsm.team_manage.spectators")
                : Component.literal(team.name());
        boolean available = permitted() && TeamActionModel.availableTargetTeams(detail, player).contains(team.name());
        Component count = Component.literal(team.currentPlayers() + " / " + (team.playerLimit() < 0 ? "?" : team.playerLimit()));
        button.setText(label.copy().append("   ").append(count)
                .append(team.isFull() ? Component.literal("  ").append(Component.translatable("gui.fpsm.map_select.full")) : Component.empty()));
        button.setAccessibleName(label);
        button.addClass("team-choice");
        if (team.name().equals(selectedTeam)) button.addClass("choice-active");
        button.layout(layout -> layout.widthPercent(100).height(30).marginBottom(4));
        button.setAvailability(true, available);
        button.setOnClick(event -> {
            if (!permitted() || !TeamActionModel.availableTargetTeams(detail, player).contains(team.name())) return;
            selectedTeam = team.name();
            // Update in place so mouse focus survives selection; no virtual-row rebuild is needed.
            for (UIElement element : modularUI.getAllElements()) {
                if (element.hasClass("team-choice")) {
                    if (element == button) element.addClass("choice-active");
                    else element.removeClass("choice-active");
                }
            }
            updateSelection();
        });
        return button;
    }

    private void updateSelection() {
        confirm.setAvailability(true, !submitted && permitted() && selectedTeam != null
                && TeamActionModel.availableTargetTeams(detail, player).contains(selectedTeam));
        status.setValue(Component.translatable(selectedTeam != null
                ? "gui.fpsm.team_manage.context.move" : "gui.fpsm.team_manage.team.choose",
                selectedTeam == null ? "" : selectedTeam));
    }

    private void confirmAction() {
        if (kickConfirmation) {
            if (submitted || !TeamActionModel.canKick(detail, player)) return;
            submitted = true;
            confirm.setAvailability(true, false);
            FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.KICK,
                    detail.summary().gameType(), detail.summary().mapName(), player));
            onClose();
            return;
        }
        if (submitted || !permitted() || selectedTeam == null
                || !TeamActionModel.availableTargetTeams(detail, player).contains(selectedTeam)) return;
        submitted = true;
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.SWITCH_TEAM,
                detail.summary().gameType(), detail.summary().mapName(), player, selectedTeam));
        onClose();
    }

    private void layoutDialog() {
        int panelWidth = Math.max(1, Math.min(360, width - 24));
        int panelHeight = Math.max(1, Math.min(kickConfirmation ? 180 : 310, height - 24));
        place(panel, (width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
        place(teamList, 10, 59, panelWidth - 20, Math.max(1, panelHeight - 125));
        if (kickConfirmation) {
            place(status, 12, 59, panelWidth - 24, Math.max(1, panelHeight - 105));
        } else {
            place(status, 12, panelHeight - 61, panelWidth - 24, 17);
        }
        int actionWidth = (panelWidth - 30) / 2;
        place(cancel, 10, panelHeight - 36, actionWidth, 26);
        place(confirm, 20 + actionWidth, panelHeight - 36, actionWidth, 26);
    }

    private static void place(UIElement element, int x, int y, int width, int height) {
        element.layout(l -> l.positionType(YogaPositionType.ABSOLUTE).rightAuto().bottomAuto()
                .left(x).top(y).width(width).height(height));
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        if (parent != null) parent.render(graphics, -100, -100, 0);
        graphics.fill(0, 0, width, height, 0xB00D1012);
    }
}
