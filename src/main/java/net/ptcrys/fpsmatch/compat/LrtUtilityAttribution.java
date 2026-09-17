package net.ptcrys.fpsmatch.compat;

import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.PlayerData;
import net.ptcrys.fpsmatch.core.map.BaseMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;

import java.util.Map;
import java.util.WeakHashMap;

/** LRT ignites entities without preserving the thrower in vanilla's later ON_FIRE source. */
public final class LrtUtilityAttribution {

    private static final Map<ServerPlayer, Fire> FIRES = new WeakHashMap<>();

    private LrtUtilityAttribution() {}

    public static void ignited(Entity target, Entity source, LivingEntity owner, int seconds) {
        if (!(target instanceof ServerPlayer victim) || !(owner instanceof ServerPlayer thrower) || seconds <= 0 || !victim.isOnFire()) return;
        FPSMCore.getInstance().getMapByPlayer(thrower).ifPresent(map -> {
            if (!map.isStart() || !map.checkGameHasPlayer(victim)) return;
            map.getMapTeams().getPlayerData(victim).ifPresent(data -> FIRES.put(victim,
                    new Fire(source, thrower, map, data, data.getDeaths(), data.getCombatRevision(),
                            victim.level().getGameTime() + Math.max(seconds * 20L, victim.getRemainingFireTicks()))));
        });
    }

    public static DamageSource resolve(ServerPlayer victim, DamageSource source) {
        if (!source.is(DamageTypes.ON_FIRE) || source.getEntity() != null) return source;
        Fire fire = FIRES.get(victim);
        if (fire == null) return source;
        if (!victim.isOnFire() || victim.level().getGameTime() > fire.expires || FPSMCore.getInstance().getMapByPlayer(victim).orElse(null) != fire.map || fire.map.getMapTeams().getPlayerData(victim).orElse(null) != fire.data || fire.data.getDeaths() != fire.deaths || fire.data.getCombatRevision() != fire.revision || !fire.map.isStart() || !fire.map.checkGameHasPlayer(fire.owner)) {
            FIRES.remove(victim);
            return source;
        }
        return new DamageSource(source.typeHolder(), fire.source, fire.owner);
    }

    public static LivingEntity owner(Entity source) {
        if (source instanceof net.minecraft.world.entity.AreaEffectCloud cloud) return cloud.getOwner();
        return source instanceof TraceableEntity traceable && traceable.getOwner() instanceof LivingEntity living ? living : null;
    }

    private record Fire(Entity source, ServerPlayer owner, BaseMap map, PlayerData data, int deaths, long revision, long expires) {}
}
