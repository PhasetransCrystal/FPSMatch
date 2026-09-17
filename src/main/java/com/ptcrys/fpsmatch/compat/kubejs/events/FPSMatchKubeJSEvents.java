package com.ptcrys.fpsmatch.compat.kubejs.events;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Event;

import com.ptcrys.fpsmatch.common.event.FPSMTeamEvent;
import com.ptcrys.fpsmatch.common.event.FPSMapEvent;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.BaseTeam;
import dev.latvian.mods.kubejs.event.EventExit;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventJS;

public class FPSMatchKubeJSEvents {

    public static final EventGroup GROUP = EventGroup.of("FPSMatchEvents");

    // ---- Base JS event ----
    public abstract static class FPSMatchEventJS<E extends Event> extends EventJS {

        protected final E event;

        public FPSMatchEventJS(E event) {
            this.event = event;
        }

        public E getForgeEvent() {
            return event;
        }

        @Override
        public Object cancel() throws EventExit {
            if (event.isCancelable()) {
                event.setCanceled(true);
            }
            return super.cancel();
        }
    }

    // ---- Map event base ----
    public abstract static class FPSMapEventJS<E extends FPSMapEvent> extends FPSMatchEventJS<E> {

        public FPSMapEventJS(E event) {
            super(event);
        }

        public BaseMap getMap() {
            return event.getMap();
        }
    }

    // ---- Game lifecycle events ----
    public static class StartEventJS extends FPSMapEventJS<FPSMapEvent.StartEvent> {

        public StartEventJS(FPSMapEvent.StartEvent event) {
            super(event);
        }
    }

    public static class VictoryEventJS extends FPSMapEventJS<FPSMapEvent.VictoryEvent> {

        public VictoryEventJS(FPSMapEvent.VictoryEvent event) {
            super(event);
        }
    }

    public static class ClearEventJS extends FPSMapEventJS<FPSMapEvent.ClearEvent> {

        public ClearEventJS(FPSMapEvent.ClearEvent event) {
            super(event);
        }
    }

    public static class ResetEventJS extends FPSMapEventJS<FPSMapEvent.ResetEvent> {

        public ResetEventJS(FPSMapEvent.ResetEvent event) {
            super(event);
        }
    }

    // ---- Player event base ----
    public abstract static class FPSMapPlayerEventJS<E extends FPSMapEvent.PlayerEvent> extends FPSMapEventJS<E> {

        public FPSMapPlayerEventJS(E event) {
            super(event);
        }

        public ServerPlayer getPlayer() {
            return event.getPlayer();
        }
    }

    public static class PlayerJoinEventJS extends FPSMapPlayerEventJS<FPSMapEvent.PlayerEvent.JoinEvent> {

        public PlayerJoinEventJS(FPSMapEvent.PlayerEvent.JoinEvent event) {
            super(event);
        }
    }

    public static class PlayerLeaveEventJS extends FPSMapPlayerEventJS<FPSMapEvent.PlayerEvent.LeaveEvent> {

        public PlayerLeaveEventJS(FPSMapEvent.PlayerEvent.LeaveEvent event) {
            super(event);
        }
    }

    public static class PlayerHurtEventJS extends FPSMapPlayerEventJS<FPSMapEvent.PlayerEvent.HurtEvent> {

        public PlayerHurtEventJS(FPSMapEvent.PlayerEvent.HurtEvent event) {
            super(event);
        }

        public DamageSource getSource() {
            return event.getSource();
        }

        public float getAmount() {
            return event.getAmount();
        }

        public void setAmount(float amount) {
            event.setAmount(amount);
        }
    }

    public static class PlayerDeathEventJS extends FPSMapPlayerEventJS<FPSMapEvent.PlayerEvent.DeathEvent> {

        public PlayerDeathEventJS(FPSMapEvent.PlayerEvent.DeathEvent event) {
            super(event);
        }

        public DamageSource getSource() {
            return event.getSource();
        }
    }

    public static class PlayerKillEventJS extends FPSMapPlayerEventJS<FPSMapEvent.PlayerEvent.KillEvent> {

        public PlayerKillEventJS(FPSMapEvent.PlayerEvent.KillEvent event) {
            super(event);
        }

        public ServerPlayer getDead() {
            return event.getDead();
        }

        public DamageSource getSource() {
            return event.getSource();
        }
    }

    public static class PlayerLoggedInEventJS extends FPSMapPlayerEventJS<FPSMapEvent.PlayerEvent.LoggedInEvent> {

        public PlayerLoggedInEventJS(FPSMapEvent.PlayerEvent.LoggedInEvent event) {
            super(event);
        }
    }

    public static class PlayerLoggedOutEventJS extends FPSMapPlayerEventJS<FPSMapEvent.PlayerEvent.LoggedOutEvent> {

        public PlayerLoggedOutEventJS(FPSMapEvent.PlayerEvent.LoggedOutEvent event) {
            super(event);
        }
    }

    // ---- Team events ----
    public abstract static class FPSMTeamEventJS<E extends FPSMTeamEvent> extends FPSMatchEventJS<E> {

        public FPSMTeamEventJS(E event) {
            super(event);
        }

        public BaseTeam getTeam() {
            return event.getTeam();
        }
    }

    public static class TeamJoinEventJS extends FPSMTeamEventJS<FPSMTeamEvent.JoinEvent> {

        public TeamJoinEventJS(FPSMTeamEvent.JoinEvent event) {
            super(event);
        }

        public Player getPlayer() {
            return event.getPlayer();
        }
    }

    public static class TeamLeaveEventJS extends FPSMTeamEventJS<FPSMTeamEvent.LeaveEvent> {

        public TeamLeaveEventJS(FPSMTeamEvent.LeaveEvent event) {
            super(event);
        }

        public Player getPlayer() {
            return event.getPlayer();
        }
    }

    // ---- No-arg constructors for KJS event group ----
    public FPSMatchKubeJSEvents() {}
}
