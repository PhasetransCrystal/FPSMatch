package com.ptcrys.fpsmatch.common.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.compat.PassThroughFlagResolver;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.map.DeathContext;
import com.ptcrys.fpsmatch.core.team.MapTeams;
import com.ptcrys.fpsmatch.util.FPSMUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-match damage/death proxy pipeline.
 */
@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FPSMDeathPipelineEventHook {

    private static final long RECENT_GUN_HIT_MATCH_WINDOW_TICKS = 5L;
    private static final long RECENT_GUN_HIT_RETENTION_TICKS = 20L;

    private static final Set<UUID> RECENTLY_KILLED = new HashSet<>();
    private static final Map<UUID, PendingDeath> readyDeaths = new HashMap<>();
    private static final Map<UUID, GunKillDetail> pendingGunKills = new HashMap<>();

    /**
     * Last gun hurt details observed before the death pipeline receives or finalizes a death.
     * TACZ can report the correct headshot flag on the hurt event even when delayed
     * kill-event handling misses or overwrites it, so keep the latest hit briefly.
     */
    private static final Map<UUID, RecentGunHitDetail> recentGunHits = new HashMap<>();

    public static boolean isRecentlyKilled(UUID uuid) {
        return RECENTLY_KILLED.contains(uuid);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer hurt)) return;

        Optional<BaseMap> opt = FPSMCore.getInstance().getMapByPlayer(hurt);
        if (opt.isEmpty()) return;
        BaseMap map = opt.get();
        if (!map.isStart()) return;

        if (hurt.isSpectator() || isRecentlyKilled(hurt.getUUID())) {
            event.setCanceled(true);
            return;
        }

        DamageSource attributedSource = com.ptcrys.fpsmatch.compat.LrtUtilityAttribution.resolve(hurt, event.getSource());
        FPSMapEvent.PlayerEvent.HurtEvent hurtEvent = new FPSMapEvent.PlayerEvent.HurtEvent(map, hurt, attributedSource, event.getAmount());
        if (MinecraftForge.EVENT_BUS.post(hurtEvent)) {
            event.setCanceled(true);
            return;
        }

        event.setAmount(hurtEvent.getAmount());
        if (event.getAmount() <= 0) {
            event.setCanceled(true);
            return;
        }

        map.recordHurtData(hurt, attributedSource, event.getAmount());
    }

    @SubscribeEvent
    public static void onGunDamage(FPSMGunDamageEvent event) {
        if (event.getBaseAmount() <= 0.0F) {
            return;
        }
        if (!(event.getHurtEntity() instanceof ServerPlayer hurt)) {
            return;
        }
        if (!(event.getAttacker() instanceof ServerPlayer attacker)) {
            return;
        }

        Optional<BaseMap> mapOpt = FPSMCore.getInstance().getMapByPlayer(hurt);
        if (mapOpt.isEmpty()) return;
        BaseMap map = mapOpt.get();
        if (!map.isStart()) return;
        if (FPSMCore.getInstance().getMapByPlayer(attacker).orElse(null) != map) return;

        PassThroughFlagResolver.Flags passThroughFlags = PassThroughFlagResolver.fromBulletAndTarget(
                event.getBullet(),
                event.getHurtEntity());
        recentGunHits.put(hurt.getUUID(), new RecentGunHitDetail(
                event.isHeadShot(),
                attacker,
                hurt.serverLevel().getGameTime(),
                event.getBullet(),
                passThroughFlags.passWall(),
                passThroughFlags.passSmoke(),
                passThroughFlags.scoped()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPlayerDeathEvent(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Optional<BaseMap> opt = FPSMCore.getInstance().getMapByPlayer(player);
            if (opt.isEmpty()) return;

            BaseMap map = opt.get();
            if (map.isStart()) {
                if (player.isSpectator() || isRecentlyKilled(player.getUUID())) {
                    event.setCanceled(true);
                    return;
                }

                event.setCanceled(true);
                player.setHealth(player.getMaxHealth());
                RECENTLY_KILLED.add(player.getUUID());

                DamageSource attributedSource = com.ptcrys.fpsmatch.compat.LrtUtilityAttribution.resolve(player, event.getSource());
                FPSMapEvent.PlayerEvent.DeathEvent deathEvent = new FPSMapEvent.PlayerEvent.DeathEvent(map, player, attributedSource);
                MinecraftForge.EVENT_BUS.post(deathEvent);
                if (deathEvent.isCanceled()) {
                    return;
                }

                ServerPlayer attacker = deathEvent.getAttacker().orElse(null);
                ItemStack deathItem = map.resolveDeathItem(attacker, deathEvent.getSource());
                DeathContext context = new DeathContext(player, attacker, deathEvent.getSource(), deathItem, player.serverLevel().getGameTime());
                applyRecentGunHitDetail(context, recentGunHits.get(player.getUUID()));

                readyDeaths.put(player.getUUID(), new PendingDeath(map, context));
            }
            return;
        }

        if (event.getEntity() instanceof Player player && player.level().isClientSide && FPSMCore.getInstance().getMapByPlayer(player).isPresent()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerKillEvent(FPSMGunKillEvent event) {
        if (!(event.getKilledEntity() instanceof ServerPlayer deadPlayer)) {
            return;
        }

        Optional<BaseMap> mapOpt = FPSMCore.getInstance().getMapByPlayer(deadPlayer);
        if (mapOpt.isEmpty()) return;
        BaseMap map = mapOpt.get();

        if (!(event.getAttacker() instanceof ServerPlayer attacker)) return;

        if (FPSMCore.getInstance().getMapByPlayer(attacker).orElse(null) != map) return;

        DamageSource source = deadPlayer.getLastDamageSource() != null ? deadPlayer.getLastDamageSource() : deadPlayer.damageSources().generic();
        ItemStack gunStack = event.getGunItemStack();
        boolean recognizedGun = GunCompatManager.isGun(gunStack);
        ItemStack deathItem = recognizedGun ? gunStack : map.resolveDeathItem(attacker, source);

        PassThroughFlagResolver.Flags passThroughFlags = PassThroughFlagResolver.fromBulletAndTarget(
                event.getBullet(),
                event.getKilledEntity());
        GunKillDetail detail = new GunKillDetail(
                resolveGunKillHeadShot(deadPlayer, event.isHeadShot(), attacker),
                event.getBullet(),
                attacker,
                deathItem,
                passThroughFlags.passWall(),
                passThroughFlags.passSmoke(),
                passThroughFlags.scoped());
        pendingGunKills.put(deadPlayer.getUUID(), detail);
        PendingDeath pendingDeath = readyDeaths.get(deadPlayer.getUUID());
        if (pendingDeath != null) {
            applyGunKillDetail(pendingDeath.context(), detail);
        }

        readyDeaths.computeIfAbsent(deadPlayer.getUUID(), uuid -> {
            deadPlayer.setHealth(deadPlayer.getMaxHealth());
            RECENTLY_KILLED.add(deadPlayer.getUUID());
            DeathContext context = new DeathContext(
                    deadPlayer,
                    attacker,
                    source,
                    deathItem,
                    deadPlayer.serverLevel().getGameTime());
            applyGunKillDetail(context, detail);
            return new PendingDeath(map, context);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        long now = FPSMCore.getInstance().getServer().overworld().getGameTime();

        if (readyDeaths.isEmpty()) {
            pendingGunKills.clear();
            purgeExpiredRecentGunHits(now);
            RECENTLY_KILLED.clear();
            return;
        }

        List<PendingDeath> dueDeaths = new ArrayList<>();
        Iterator<Map.Entry<UUID, PendingDeath>> iterator = readyDeaths.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingDeath pending = iterator.next().getValue();
            if (!isDeathReadyForFinalization(pending.context().getCreatedTick(), now)) {
                continue;
            }

            iterator.remove();
            dueDeaths.add(pending);
        }

        for (PendingDeath pending : dueDeaths) {
            finalizeDeath(pending.map(), pending.context());
        }

        if (readyDeaths.isEmpty()) {
            pendingGunKills.clear();
            purgeExpiredRecentGunHits(now);
            RECENTLY_KILLED.clear();
        }
    }

    static boolean isDeathReadyForFinalization(long createdTick, long currentTick) {
        return DeathFinalizationTiming.isReady(createdTick, currentTick);
    }

    private static void finalizeDeath(BaseMap map, DeathContext context) {
        ServerPlayer player = context.getDeadPlayer();
        MapTeams mapTeams = map.getMapTeams();

        GunKillDetail gunKill = pendingGunKills.remove(player.getUUID());
        RecentGunHitDetail recentGunHit = recentGunHits.remove(player.getUUID());
        if (gunKill != null) {
            applyGunKillDetail(context, gunKill);
        }
        if (recentGunHit != null) {
            applyRecentGunHitDetail(context, recentGunHit);
        }

        ServerPlayer killer = context.getAttacker();

        map.handleDeath(context);

        if (killer != null) {
            boolean enemyKill = !mapTeams.isSameTeam(player, killer);
            if (enemyKill) {
                if (!MinecraftForge.EVENT_BUS.post(new FPSMapEvent.PlayerEvent.KillRecordEvent(map, killer, player, context.getDamageSource()))) {
                    mapTeams.getPlayerData(killer).ifPresent(PlayerData::addKill);
                    if (context.isHeadShot()) {
                        mapTeams.getPlayerData(killer).ifPresent(PlayerData::addHeadshotKill);
                    }
                }
            }

            FPSMUtil.calculateAssistPlayer(map, player, map.getMinAssistDamageRatio()).ifPresent(assistData -> {
                if (!killer.getUUID().equals(assistData.getOwner())) {
                    map.creditAssist(assistData);
                }
            });

            FPSMapEvent.PlayerEvent.KillEvent killEvent = new FPSMapEvent.PlayerEvent.KillEvent(map, killer, player, context.getDamageSource(), context.isHeadShot());
            MinecraftForge.EVENT_BUS.post(killEvent);
        }
    }

    private static void applyGunKillDetail(DeathContext context, GunKillDetail gunKill) {
        ServerPlayer attacker = context.getAttacker() != null ? context.getAttacker() : gunKill.attacker();
        boolean selfKill = attacker != null && attacker.getUUID().equals(context.getDeadPlayer().getUUID());
        context.setGunKill(true);
        context.setHeadShot((context.isHeadShot() || gunKill.isHeadShot()) && !selfKill);
        if (gunKill.bullet() != null) {
            context.setGunBullet(gunKill.bullet());
        }
        context.setPassWall(context.isPassWall() || gunKill.passWall());
        context.setPassSmoke(context.isPassSmoke() || gunKill.passSmoke());
        context.setScopedKill(context.isScopedKill() || gunKill.scopedKill());

        if (context.getAttacker() == null) {
            context.setAttacker(gunKill.attacker());
        }

        if (!gunKill.deathItem().isEmpty()) {
            context.setDeathItem(gunKill.deathItem());
        }
    }

    private static boolean resolveGunKillHeadShot(ServerPlayer deadPlayer, boolean eventHeadShot, @Nullable ServerPlayer attacker) {
        if (eventHeadShot) {
            return true;
        }

        RecentGunHitDetail recentGunHit = recentGunHits.get(deadPlayer.getUUID());
        return recentGunHit != null && recentGunHit.isHeadShot() && (attacker == null || recentGunHit.attacker().getUUID().equals(attacker.getUUID())) && isRecentGunHitFresh(deadPlayer.serverLevel().getGameTime(), recentGunHit);
    }

    private static void applyRecentGunHitDetail(DeathContext context, @Nullable RecentGunHitDetail recentGunHit) {
        if (!isRecentGunHitForDeath(context, recentGunHit)) {
            return;
        }

        context.setGunKill(true);
        if (context.getAttacker() == null) {
            context.setAttacker(recentGunHit.attacker());
        }
        if (recentGunHit.isHeadShot()) {
            context.setHeadShot(true);
        }
        if (context.getGunBullet() == null) {
            context.setGunBullet(recentGunHit.bullet());
        }
        context.setPassWall(context.isPassWall() || recentGunHit.passWall());
        context.setPassSmoke(context.isPassSmoke() || recentGunHit.passSmoke());
        context.setScopedKill(context.isScopedKill() || recentGunHit.scopedKill());
    }

    private static boolean isRecentGunHitForDeath(DeathContext context, @Nullable RecentGunHitDetail recentGunHit) {
        if (recentGunHit == null) {
            return false;
        }

        if (context.getDeadPlayer().getUUID().equals(recentGunHit.attacker().getUUID())) {
            return false;
        }

        ServerPlayer attacker = context.getAttacker();
        if (attacker != null && !attacker.getUUID().equals(recentGunHit.attacker().getUUID())) {
            return false;
        }

        return isRecentGunHitFresh(context.getCreatedTick(), recentGunHit) && (isDeathSourceFromRecentGunHit(context, recentGunHit) || context.isGunKill() || GunCompatManager.isGun(context.getDeathItem()) || hasDeathIconSignal(recentGunHit));
    }

    private static boolean hasDeathIconSignal(RecentGunHitDetail recentGunHit) {
        return recentGunHit.isHeadShot() || recentGunHit.passWall() || recentGunHit.passSmoke() || recentGunHit.scopedKill();
    }

    private static boolean isDeathSourceFromRecentGunHit(DeathContext context, RecentGunHitDetail recentGunHit) {
        Entity bullet = recentGunHit.bullet();
        if (bullet == null) {
            return false;
        }

        Entity directEntity = context.getDamageSource().getDirectEntity();
        return directEntity != null && directEntity.getUUID().equals(bullet.getUUID());
    }

    private static boolean isRecentGunHitFresh(long currentTick, RecentGunHitDetail recentGunHit) {
        return Math.abs(currentTick - recentGunHit.createdTick()) <= RECENT_GUN_HIT_MATCH_WINDOW_TICKS;
    }

    private static void purgeExpiredRecentGunHits(long currentTick) {
        recentGunHits.values().removeIf(hit -> currentTick - hit.createdTick() > RECENT_GUN_HIT_RETENTION_TICKS);
    }

    private record PendingDeath(BaseMap map, DeathContext context) {}

    private record GunKillDetail(
                                 boolean isHeadShot,
                                 @Nullable Entity bullet,
                                 @Nullable ServerPlayer attacker,
                                 ItemStack deathItem,
                                 boolean passWall,
                                 boolean passSmoke,
                                 boolean scopedKill) {

        GunKillDetail(boolean isHeadShot, @Nullable Entity bullet, @Nullable ServerPlayer attacker, ItemStack deathItem) {
            this(isHeadShot, bullet, attacker, deathItem, false, false, false);
        }
    }

    private record RecentGunHitDetail(boolean isHeadShot, ServerPlayer attacker, long createdTick,
                                      @Nullable Entity bullet, boolean passWall, boolean passSmoke,
                                      boolean scopedKill) {}
}
