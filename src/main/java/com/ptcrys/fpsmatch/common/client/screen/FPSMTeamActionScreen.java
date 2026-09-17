package com.ptcrys.fpsmatch.common.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapDetailChildScreen;
import com.ptcrys.fpsmatch.common.client.screen.modernui.ModernScreen;
import com.ptcrys.fpsmatch.common.client.screen.team.TeamActionModel;
import com.ptcrys.fpsmatch.common.packet.mapselect.*;

import java.util.*;

/** Native team picker / kick confirmation, with permissions rechecked at submission. */
public final class FPSMTeamActionScreen extends ModernScreen implements FPSMMapDetailChildScreen {

    private MapRoomDetail detail;
    private final UUID player;
    private final boolean kick;
    private String selected;

    public FPSMTeamActionScreen(MapRoomDetail detail, Screen parent, MapRoomPlayerInfo player) {
        this(detail, parent, player, false);
    }

    public static FPSMTeamActionScreen confirmKick(MapRoomDetail detail, Screen parent, MapRoomPlayerInfo player) {
        return new FPSMTeamActionScreen(detail, parent, player, true);
    }

    private FPSMTeamActionScreen(MapRoomDetail detail, Screen parent, MapRoomPlayerInfo player, boolean kick) {
        super(Component.translatable(kick ? "gui.fpsm.team_manage.context.kick" : "gui.fpsm.team_manage.context.switch"), parent);
        this.detail = detail;
        this.player = player.uuid();
        this.kick = kick;
    }

    private boolean permitted() {
        return detail.summary().currentPlayerOp() || !kick && Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(player);
    }

    @Override
    public void applyDetail(MapRoomDetail next) {
        if (!next.summary().gameType().equals(detail.summary().gameType()) || !next.summary().mapName().equals(detail.summary().mapName())) return;
        detail = next;
        if (!permitted() || detail.players().stream().noneMatch(p -> p.uuid().equals(player))) {
            onClose();
            return;
        }
        if (selected != null && !TeamActionModel.availableTargetTeams(detail, player).contains(selected)) selected = null;
        refresh();
    }

    @Override
    protected List<Node> content() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(title("title", getTitle().getString()));
        var target = detail.players().stream().filter(p -> p.uuid().equals(player)).findFirst().orElse(null);
        nodes.add(text("player", target == null ? "" : target.name() + " / " + target.teamName()));
        List<Node> body = new ArrayList<>();
        if (kick) body.add(text("warning", tr("gui.fpsm.team_manage.kick.message", target == null ? "" : target.name())));
        else for (var team : detail.teams()) body.add(button(team.name(), (team.name().equals(selected) ? "● " : "") + team.name() + "  " + team.currentPlayers() + "/" + (team.playerLimit() < 0 ? "∞" : team.playerLimit()),
                permitted() && TeamActionModel.availableTargetTeams(detail, player).contains(team.name()), () -> selected = team.name()));
        nodes.add(scroll("choices", body));
        nodes.add(row("actions", button("back", tr("gui.back"), true, this::onClose),
                button("confirm", tr(kick ? "gui.fpsm.team_manage.kick.confirm" : "gui.fpsm.team_manage.context.switch"),
                        permitted() && (kick ? TeamActionModel.canKick(detail, player) : selected != null), this::submit)));
        return dialog("team.action", nodes, 330, kick ? 150 : 240);
    }

    private void submit() {
        if (!permitted() || (kick ? !TeamActionModel.canKick(detail, player) : selected == null || !TeamActionModel.availableTargetTeams(detail, player).contains(selected))) return;
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(kick ? MapRoomActionC2SPacket.Action.KICK : MapRoomActionC2SPacket.Action.SWITCH_TEAM,
                detail.summary().gameType(), detail.summary().mapName(), player, kick ? "" : selected));
        onClose();
    }

    @Override
    public void onClose() {
        if (parent instanceof FPSMMapDetailChildScreen screen) screen.applyDetail(detail);
        super.onClose();
    }
}
