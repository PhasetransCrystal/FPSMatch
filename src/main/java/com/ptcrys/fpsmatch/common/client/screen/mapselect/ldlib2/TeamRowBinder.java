package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UITemplate;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomTeamInfo;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Assembles team-roster rows for {@link Ldlib2TeamManageScreen} from XML fragments
 * ({@code rows/team_header_row.xml}, {@code rows/team_player_row.xml}). Visuals come from
 * team_lobby.lss; ready/offline states are bound here.
 */
final class TeamRowBinder {
    static final String TEAM_HEADER_PREFIX = "fpsmatch.team_manage.header.";
    static final String PLAYER_ROW_PREFIX = "fpsmatch.team_manage.player.";
    static final int TEAM_HEADER_HEIGHT = 33;
    static final int PLAYER_ROW_HEIGHT = 33;
    static final int ROW_GAP = 3;
    private static final String HEADER_ROW_LAYOUT = "fpsmatch:ldlib2/ui/rows/team_header_row.xml";
    private static final String PLAYER_ROW_LAYOUT = "fpsmatch:ldlib2/ui/rows/team_player_row.xml";

    /** Screen-side state the rows need; implemented by the owning screen. */
    interface Context {
        boolean isReady(UUID player);

        boolean isOperator();

        void onRowClick(MapRoomPlayerInfo player, int button);

        boolean isExpanded(String team);

        void toggleTeam(String team);
    }

    private final UITemplate headerTemplate;
    private final UITemplate playerTemplate;
    private final Context context;

    TeamRowBinder(Context context) {
        this.headerTemplate = UITemplate.of(Ldlib2XmlUi.loadUi(HEADER_ROW_LAYOUT).rootElement);
        this.playerTemplate = UITemplate.of(Ldlib2XmlUi.loadUi(PLAYER_ROW_LAYOUT).rootElement);
        this.context = context;
    }

    UIElement headerRow(MapRoomTeamInfo team) {
        UIElement row = headerTemplate.copy().createUI().rootElement;
        row.setId(TEAM_HEADER_PREFIX + team.name());
        row.layout(layout -> layout.widthPercent(100).height(TEAM_HEADER_HEIGHT).marginBottom(ROW_GAP));
        AccessibleButton toggle = row.selectId("toggle", AccessibleButton.class).findFirst().orElse(null);
        Label label = row.selectId("label", Label.class).findFirst().orElse(null);
        Label count = row.selectId("count", Label.class).findFirst().orElse(null);
        UIElement chevron = row.selectId("chevron", UIElement.class).findFirst().orElse(null);
        UIElement segment = row.selectId("segment", UIElement.class).findFirst().orElse(null);
        if (toggle == null || label == null || count == null || chevron == null || segment == null) {
            FPSMatch.LOGGER.error("[FPSM UI] team header row fragment is incomplete");
            return row;
        }
        boolean expanded = context.isExpanded(team.name());
        Component teamName = team.spectator()
                ? Component.translatable("gui.fpsm.team_manage.spectators") : Component.literal(team.name());
        toggle.setId(TEAM_HEADER_PREFIX + team.name() + ".toggle");
        toggle.noText();
        chevron.removeClass("team-chevron-expanded");
        chevron.removeClass("team-chevron-collapsed");
        chevron.addClass(expanded ? "team-chevron-expanded" : "team-chevron-collapsed");
        chevron.setAllowHitTest(false);
        toggle.setAccessibleName(teamName);
        toggle.setAccessibleState(() -> Component.translatable(context.isExpanded(team.name())
                ? "gui.fpsm.team_manage.team.expanded" : "gui.fpsm.team_manage.team.collapsed"));
        toggle.setOnClick(event -> context.toggleTeam(team.name()));
        label.setId(TEAM_HEADER_PREFIX + team.name() + ".label");
        label.setValue(teamName);
        count.setId(TEAM_HEADER_PREFIX + team.name() + ".count");
        String limit = team.playerLimit() < 0 ? "?" : Integer.toString(team.playerLimit());
        count.setValue(Component.literal(team.currentPlayers() + " / " + limit));
        label.setAllowHitTest(false);
        count.setAllowHitTest(false);
        segment.setAllowHitTest(false);
        return row;
    }

    UIElement playerRow(MapRoomPlayerInfo player) {
        UIElement row = playerTemplate.copy().createUI().rootElement;
        row.setId(PLAYER_ROW_PREFIX + player.uuid());
        row.layout(layout -> layout.widthPercent(100).height(PLAYER_ROW_HEIGHT).marginBottom(ROW_GAP));
        AccessibleButton select = row.selectId("select", AccessibleButton.class).findFirst().orElse(null);
        if (select == null) {
            FPSMatch.LOGGER.error("[FPSM UI] team player row fragment is missing #select button");
            return row;
        }
        select.noText();
        select.setId("fpsmatch.team_manage.select." + player.uuid());
        select.setAccessibleName(Component.literal(player.name()));
        select.setAccessibleState(() -> {
            Component state = Component.literal(player.teamName()).copy().append(" · ")
                    .append(Component.translatable(player.online()
                            ? "gui.fpsm.map_select.online"
                            : "gui.fpsm.map_select.offline"));
            if (context.isReady(player.uuid())) {
                state = state.copy().append(" · ").append(Component.translatable(
                        "gui.fpsm.team_manage.ready_mark"));
            }
            return state;
        });
        select.setAccessibleHint(() -> context.isOperator()
                ? Component.translatable("gui.fpsm.team_manage.context.title")
                : Component.empty());
        select.setOnClick(event -> context.onRowClick(player, event.button));

        UIElement signal = row.selectId("signal", UIElement.class).findFirst().orElse(null);
        if (signal != null) {
            signal.setId("fpsmatch.team_manage.player.signal." + player.uuid());
            signal.removeClass("online");
            signal.removeClass("ready");
            if (player.online()) signal.addClass("online");
            if (player.online() && context.isReady(player.uuid())) signal.addClass("ready");
            signal.setAllowHitTest(false);
        }

        Ldlib2PlayerAvatarElement avatar = row.selectId("avatar", Ldlib2PlayerAvatarElement.class)
                .findFirst().orElse(null);
        if (avatar != null) {
            avatar.setId("fpsmatch.team_manage.player.avatar." + player.uuid());
            avatar.setAvatar(player.uuid(), player.name());
        }

        Label state = row.selectId("state", Label.class).findFirst().orElse(null);
        if (state != null) {
            state.setId("fpsmatch.team_manage.player.state." + player.uuid());
            state.setValue(Component.translatable(!player.online() ? "gui.fpsm.map_select.offline"
                    : context.isReady(player.uuid()) ? "gui.fpsm.team_manage.ready_mark" : "gui.fpsm.map_select.online"));
            state.setAllowHitTest(false);
            if (player.online() && context.isReady(player.uuid())) state.addClass("ready");
        }
        Label name = row.selectId("name", Label.class).findFirst().orElse(null);
        if (name != null) {
            name.setId("fpsmatch.team_manage.player.name." + player.uuid());
            name.setValue(Component.literal(player.name()));
            name.setAllowHitTest(false);
            if (player.online()) {
                name.removeClass("offline");
            } else {
                name.addClass("offline");
            }
        }
        return row;
    }
}
