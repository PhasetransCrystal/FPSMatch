package com.ptcrys.fpsmatch.common.event;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Event;

import com.ptcrys.fpsmatch.core.team.BaseTeam;

public class FPSMTeamEvent extends Event {

    private final BaseTeam team;

    public FPSMTeamEvent(BaseTeam team) {
        this.team = team;
    }

    public BaseTeam getTeam() {
        return team;
    }

    public static class JoinEvent extends FPSMTeamEvent {

        private final Player player;

        public JoinEvent(BaseTeam team, Player player) {
            super(team);
            this.player = player;
        }

        public Player getPlayer() {
            return player;
        }
    }

    public static class LeaveEvent extends FPSMTeamEvent {

        private final Player player;

        public LeaveEvent(BaseTeam team, Player player) {
            super(team);
            this.player = player;
        }

        public Player getPlayer() {
            return player;
        }
    }
}
