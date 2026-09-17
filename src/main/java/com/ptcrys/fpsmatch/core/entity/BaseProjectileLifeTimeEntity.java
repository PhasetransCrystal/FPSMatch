package com.ptcrys.fpsmatch.core.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * 抽象基类，用于表示具有生命周期的投掷物实体。
 * <p>
 * 该类继承自 {@link BaseProjectileEntity}，并扩展了投掷物的生命周期管理功能。
 * 提供了超时逻辑和激活状态下的持续时间逻辑，允许子类实现自定义的超时和激活时间到期行为。
 */
public abstract class BaseProjectileLifeTimeEntity extends BaseProjectileEntity {

    /**
     * 实体数据访问器，用于同步投掷物的超时时间（单位：tick）。
     * <p>
     * -1 表示无超时限制。
     */
    private static final EntityDataAccessor<Integer> TIMEOUT_TICKS = SynchedEntityData.defineId(BaseProjectileLifeTimeEntity.class, EntityDataSerializers.INT);

    /**
     * 实体数据访问器，用于同步投掷物的剩余激活时间（单位：tick）。
     * <p>
     * -1 表示无时间限制。
     */
    private static final EntityDataAccessor<Integer> TIME_LEFT = SynchedEntityData.defineId(BaseProjectileLifeTimeEntity.class, EntityDataSerializers.INT);

    /**
     * 构造函数，用于创建具有生命周期的投掷物实体。
     *
     * @param type  投掷物的实体类型
     * @param level 投掷物所在的层级
     */
    public BaseProjectileLifeTimeEntity(EntityType<? extends BaseProjectileLifeTimeEntity> type, Level level) {
        super(type, level);
    }

    /**
     * 构造函数，用于创建具有生命周期的投掷物实体并指定发射者。
     *
     * @param type    投掷物的实体类型
     * @param shooter 投掷物的发射者
     * @param level   投掷物所在的层级
     */
    public BaseProjectileLifeTimeEntity(EntityType<? extends BaseProjectileLifeTimeEntity> type, LivingEntity shooter, Level level) {
        super(type, shooter, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(TIMEOUT_TICKS, -1);
        entityData.define(TIME_LEFT, -1);
    }

    @Override
    public void tick() {
        super.tick();

        if (!level().isClientSide) {
            handleTimeoutLogic();
            handleActiveTimeLogic();
        }
    }

    /**
     * 处理投掷物的超时逻辑。
     * <p>
     * 如果投掷物未被激活且设置了超时时间，则每 tick 减少超时时间。
     * 当超时时间耗尽时，调用 {@link #onTimeOut()} 方法并销毁投掷物。
     */
    protected final void handleTimeoutLogic() {
        if (!isActivated() && getTimeoutTicks() > 0) {
            setTimeoutTicks(getTimeoutTicks() - 1);
            if (getTimeoutTicks() <= 0) {
                onTimeOut();
                discard();
            }
        }
    }

    /**
     * 处理投掷物的激活时间逻辑。
     * <p>
     * 如果投掷物已被激活且设置了剩余激活时间，则每 tick 减少剩余时间。
     * 当剩余时间耗尽时，调用 {@link #onActiveTimeExpired()} 方法。
     */
    protected final void handleActiveTimeLogic() {
        if (isActivated() && getTimeLeft() > 0) {
            setTimeLeft(getTimeLeft() - 1);
            if (getTimeLeft() <= 0) {
                onActiveTimeExpired();
            }
        }
    }

    /**
     * 投掷物激活时间到期时的回调方法。
     * <p>
     * 子类可以通过覆盖该方法实现自定义的激活时间到期逻辑。
     * 默认行为是销毁投掷物。
     */
    protected void onActiveTimeExpired() {
        discard();
    }

    /**
     * 投掷物超时时的回调方法。
     * <p>
     * 子类可以通过覆盖该方法实现自定义的超时逻辑。
     */
    protected void onTimeOut() {}

    // 访问方法
    /**
     * 获取投掷物的超时时间（单位：tick）。
     * <p>
     * 返回值为 -1 表示无超时限制。
     *
     * @return 超时时间
     */
    public int getTimeoutTicks() {
        return entityData.get(TIMEOUT_TICKS);
    }

    /**
     * 设置投掷物的超时时间（单位：tick）。
     * <p>
     * 设置为 -1 可以禁用超时逻辑。
     *
     * @param ticks 超时时间
     */
    public void setTimeoutTicks(int ticks) {
        entityData.set(TIMEOUT_TICKS, ticks);
    }

    /**
     * 获取投掷物的剩余激活时间（单位：tick）。
     * <p>
     * 返回值为 -1 表示无时间限制。
     *
     * @return 剩余激活时间
     */
    public int getTimeLeft() {
        return entityData.get(TIME_LEFT);
    }

    /**
     * 设置投掷物的剩余激活时间（单位：tick）。
     * <p>
     * 设置为 -1 可以禁用激活时间逻辑。
     *
     * @param ticks 剩余激活时间
     */
    public void setTimeLeft(int ticks) {
        entityData.set(TIME_LEFT, ticks);
    }
}
