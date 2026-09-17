package com.ptcrys.fpsmatch.common.capability.team;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.ptcrys.fpsmatch.common.event.FPSMapEvent;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.team.BaseTeam;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TeamSwitchRestrictionCapability extends TeamCapability {

    private final List<UUID> unableToSwitchPlayers = new ArrayList<>();

    public TeamSwitchRestrictionCapability(BaseTeam team) {
        super(team);
    }

    @SubscribeEvent
    public static void onJoin(FPSMapEvent.PlayerEvent.LoggedInEvent event) {
        ServerPlayer player = event.getPlayer();
        UUID playerUUID = player.getUUID();
        event.getMap().getMapTeams().getTeamByPlayer(player).ifPresent(t -> {
            t.getCapabilityMap().get(TeamSwitchRestrictionCapability.class)
                    .ifPresent(cap -> {
                        if (cap.isUnableToSwitch(playerUUID)) {
                            player.getScoreboard().addPlayerToTeam(player.getScoreboardName(), t.getPlayerTeam());
                            cap.removeUnableToSwitchPlayer(playerUUID);
                        } ;
                    });
        });
    }

    public static void register() {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, TeamSwitchRestrictionCapability.class, TeamSwitchRestrictionCapability::new);
    }

    public void addUnableToSwitchPlayer(UUID playerUUID) {
        if (!isUnableToSwitch(playerUUID)) {
            unableToSwitchPlayers.add(playerUUID);
        }
    }

    public void removeUnableToSwitchPlayer(UUID playerUUID) {
        unableToSwitchPlayers.remove(playerUUID);
    }

    public boolean isUnableToSwitch(UUID playerUUID) {
        return unableToSwitchPlayers.contains(playerUUID);
    }

    public void clearUnableToSwitchPlayers() {
        unableToSwitchPlayers.clear();
    }

    public List<UUID> getUnableToSwitchPlayers() {
        return new ArrayList<>(unableToSwitchPlayers);
    }

    @Override
    public void destroy() {
        clearUnableToSwitchPlayers();
    }
}
