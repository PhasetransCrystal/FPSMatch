package com.ptcrys.fpsmatch.core.map;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 统一的死亡上下文。
 *
 * 说明：
 * - 死亡事件先生成基础上下文（不依赖枪击细节）
 * - EntityKillByGunEvent 到达后可补全枪击细节
 */
public final class DeathContext {

    private final ServerPlayer deadPlayer;
    @Nullable
    private ServerPlayer attacker;
    private final DamageSource damageSource;
    private ItemStack deathItem;
    private boolean gunKill;
    private boolean headShot;
    private boolean passWall;
    private boolean passSmoke;
    private boolean scopedKill;
    @Nullable
    private Entity gunBullet;
    private final long createdTick;

    public DeathContext(ServerPlayer deadPlayer,
                        @Nullable ServerPlayer attacker,
                        DamageSource damageSource,
                        ItemStack deathItem,
                        long createdTick) {
        this.deadPlayer = deadPlayer;
        this.attacker = attacker;
        this.damageSource = damageSource;
        this.deathItem = deathItem == null ? ItemStack.EMPTY : deathItem;
        this.createdTick = createdTick;
    }

    public ServerPlayer getDeadPlayer() {
        return deadPlayer;
    }

    @Nullable
    public ServerPlayer getAttacker() {
        return attacker;
    }

    public Optional<ServerPlayer> getAttackerOptional() {
        return Optional.ofNullable(attacker);
    }

    public boolean isSuicide() {
        return attacker != null && attacker.getUUID().equals(deadPlayer.getUUID());
    }

    public void setAttacker(@Nullable ServerPlayer attacker) {
        this.attacker = attacker;
        if (isSuicide()) {
            this.headShot = false;
        }
    }

    public DamageSource getDamageSource() {
        return damageSource;
    }

    public ItemStack getDeathItem() {
        return deathItem;
    }

    public void setDeathItem(ItemStack deathItem) {
        this.deathItem = deathItem == null ? ItemStack.EMPTY : deathItem;
    }

    public boolean isGunKill() {
        return gunKill;
    }

    public void setGunKill(boolean gunKill) {
        this.gunKill = gunKill;
    }

    public boolean isHeadShot() {
        return headShot;
    }

    public void setHeadShot(boolean headShot) {
        this.headShot = headShot && !isSuicide();
    }

    public boolean isPassWall() {
        return passWall;
    }

    public void setPassWall(boolean passWall) {
        this.passWall = passWall;
    }

    public boolean isPassSmoke() {
        return passSmoke;
    }

    public void setPassSmoke(boolean passSmoke) {
        this.passSmoke = passSmoke;
    }

    public boolean isScopedKill() {
        return scopedKill;
    }

    public void setScopedKill(boolean scopedKill) {
        this.scopedKill = scopedKill;
    }

    @Nullable
    public Entity getGunBullet() {
        return gunBullet;
    }

    public void setGunBullet(@Nullable Entity gunBullet) {
        this.gunBullet = gunBullet;
    }

    public long getCreatedTick() {
        return createdTick;
    }
}
