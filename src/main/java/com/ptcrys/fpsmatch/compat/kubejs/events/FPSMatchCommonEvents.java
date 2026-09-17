package com.ptcrys.fpsmatch.compat.kubejs.events;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import com.ptcrys.fpsmatch.common.event.FPSMTeamEvent;
import com.ptcrys.fpsmatch.common.event.FPSMapEvent;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.EventJS;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.script.ScriptTypePredicate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class FPSMatchCommonEvents {

    public static final FPSMatchCommonEvents INSTANCE = new FPSMatchCommonEvents();

    private final Map<Class<? extends Event>, Consumer<Event>> eventHandlers = new HashMap<>();
    /** 幂等保护：防止 KubeJS 插件重复加载导致事件处理器被重复注册。 */
    private boolean initialized = false;

    public static EventHandler START;
    public static EventHandler VICTORY;
    public static EventHandler CLEAR;
    public static EventHandler RESET;
    public static EventHandler PLAYER_JOIN;
    public static EventHandler PLAYER_LEAVE;
    public static EventHandler PLAYER_HURT;
    public static EventHandler PLAYER_DEATH;
    public static EventHandler PLAYER_KILL;
    public static EventHandler PLAYER_LOGGED_IN;
    public static EventHandler PLAYER_LOGGED_OUT;
    public static EventHandler TEAM_JOIN;
    public static EventHandler TEAM_LEAVE;

    private <E extends Event> EventHandler registerFPSMatchEvent(
                                                                 String name,
                                                                 Class<? extends FPSMatchKubeJSEvents.FPSMatchEventJS<E>> jsClass,
                                                                 Class<E> forgeClass,
                                                                 Function<E, ? extends FPSMatchKubeJSEvents.FPSMatchEventJS<E>> factory) {
        Supplier<Class<? extends EventJS>> supplier = () -> jsClass;
        EventHandler handler = FPSMatchKubeJSEvents.GROUP.add(name, ScriptTypePredicate.STARTUP_OR_SERVER, supplier);

        eventHandlers.put(forgeClass, event -> {
            FPSMatchKubeJSEvents.FPSMatchEventJS<E> js = factory.apply((E) event);
            handler.post(ScriptType.SERVER, js);
        });

        return handler;
    }

    private boolean postKubeJSEvent(Event event) {
        Consumer<Event> consumer = eventHandlers.get(event.getClass());
        if (consumer != null) {
            consumer.accept(event);
            return false;
        }
        return true;
    }

    public synchronized void init() {
        if (initialized) return;
        initialized = true;
        if (!ModList.get().isLoaded("kubejs")) return;

        START = registerFPSMatchEvent("mapStart",
                FPSMatchKubeJSEvents.StartEventJS.class, FPSMapEvent.StartEvent.class,
                FPSMatchKubeJSEvents.StartEventJS::new);
        VICTORY = registerFPSMatchEvent("mapVictory",
                FPSMatchKubeJSEvents.VictoryEventJS.class, FPSMapEvent.VictoryEvent.class,
                FPSMatchKubeJSEvents.VictoryEventJS::new);
        CLEAR = registerFPSMatchEvent("mapClear",
                FPSMatchKubeJSEvents.ClearEventJS.class, FPSMapEvent.ClearEvent.class,
                FPSMatchKubeJSEvents.ClearEventJS::new);
        RESET = registerFPSMatchEvent("mapReset",
                FPSMatchKubeJSEvents.ResetEventJS.class, FPSMapEvent.ResetEvent.class,
                FPSMatchKubeJSEvents.ResetEventJS::new);
        PLAYER_JOIN = registerFPSMatchEvent("playerJoin",
                FPSMatchKubeJSEvents.PlayerJoinEventJS.class, FPSMapEvent.PlayerEvent.JoinEvent.class,
                FPSMatchKubeJSEvents.PlayerJoinEventJS::new);
        PLAYER_LEAVE = registerFPSMatchEvent("playerLeave",
                FPSMatchKubeJSEvents.PlayerLeaveEventJS.class, FPSMapEvent.PlayerEvent.LeaveEvent.class,
                FPSMatchKubeJSEvents.PlayerLeaveEventJS::new);
        PLAYER_HURT = registerFPSMatchEvent("playerHurt",
                FPSMatchKubeJSEvents.PlayerHurtEventJS.class, FPSMapEvent.PlayerEvent.HurtEvent.class,
                FPSMatchKubeJSEvents.PlayerHurtEventJS::new);
        PLAYER_DEATH = registerFPSMatchEvent("playerDeath",
                FPSMatchKubeJSEvents.PlayerDeathEventJS.class, FPSMapEvent.PlayerEvent.DeathEvent.class,
                FPSMatchKubeJSEvents.PlayerDeathEventJS::new);
        PLAYER_KILL = registerFPSMatchEvent("playerKill",
                FPSMatchKubeJSEvents.PlayerKillEventJS.class, FPSMapEvent.PlayerEvent.KillEvent.class,
                FPSMatchKubeJSEvents.PlayerKillEventJS::new);
        PLAYER_LOGGED_IN = registerFPSMatchEvent("playerLoggedIn",
                FPSMatchKubeJSEvents.PlayerLoggedInEventJS.class, FPSMapEvent.PlayerEvent.LoggedInEvent.class,
                FPSMatchKubeJSEvents.PlayerLoggedInEventJS::new);
        PLAYER_LOGGED_OUT = registerFPSMatchEvent("playerLoggedOut",
                FPSMatchKubeJSEvents.PlayerLoggedOutEventJS.class, FPSMapEvent.PlayerEvent.LoggedOutEvent.class,
                FPSMatchKubeJSEvents.PlayerLoggedOutEventJS::new);
        TEAM_JOIN = registerFPSMatchEvent("teamJoin",
                FPSMatchKubeJSEvents.TeamJoinEventJS.class, FPSMTeamEvent.JoinEvent.class,
                FPSMatchKubeJSEvents.TeamJoinEventJS::new);
        TEAM_LEAVE = registerFPSMatchEvent("teamLeave",
                FPSMatchKubeJSEvents.TeamLeaveEventJS.class, FPSMTeamEvent.LeaveEvent.class,
                FPSMatchKubeJSEvents.TeamLeaveEventJS::new);

        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onMapStart(FPSMapEvent.StartEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onMapVictory(FPSMapEvent.VictoryEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onMapClear(FPSMapEvent.ClearEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onMapReset(FPSMapEvent.ResetEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onPlayerJoin(FPSMapEvent.PlayerEvent.JoinEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onPlayerLeave(FPSMapEvent.PlayerEvent.LeaveEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onPlayerHurt(FPSMapEvent.PlayerEvent.HurtEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onPlayerDeath(FPSMapEvent.PlayerEvent.DeathEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onPlayerKill(FPSMapEvent.PlayerEvent.KillEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(FPSMapEvent.PlayerEvent.LoggedInEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(FPSMapEvent.PlayerEvent.LoggedOutEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onTeamJoin(FPSMTeamEvent.JoinEvent event) {
        postKubeJSEvent(event);
    }

    @SubscribeEvent
    public void onTeamLeave(FPSMTeamEvent.LeaveEvent event) {
        postKubeJSEvent(event);
    }
}
