package com.ptcrys.fpsmatch.core.entity;

import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.ptcrys.fpsmatch.common.gamerule.FPSMatchRule;
import org.jetbrains.annotations.NotNull;

/**
 * 抽象基类，用于表示 FPSMatch 的投掷物实体。
 * 该类继承自 Minecraft 的 {@link ThrowableItemProjectile}，并扩展了投掷物的碰撞逻辑和状态管理。
 * 提供了对地面碰撞、水平和垂直碰撞的处理，以及可扩展的激活逻辑。
 */
public abstract class BaseProjectileEntity extends ThrowableItemProjectile {

    // 状态同步字段
    /**
     * 实体数据访问器，用于同步投掷物的状态（0: 初始状态，1: 碰撞后，2: 激活状态）。
     */
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(BaseProjectileEntity.class, EntityDataSerializers.INT);

    /**
     * 实体数据访问器，用于同步投掷物是否被激活。
     */
    private static final EntityDataAccessor<Boolean> ACTIVATED = SynchedEntityData.defineId(BaseProjectileEntity.class, EntityDataSerializers.BOOLEAN);

    // 碰撞参数（服务端）
    /**
     * 是否在地面碰撞时激活投掷物。
     */
    protected boolean activateOnGroundHit = false;

    /**
     * 水平方向的减速系数，用于处理水平碰撞后的速度衰减。
     */
    protected double horizontalReduction = 0.25;

    /**
     * 垂直方向的减速系数，用于处理垂直碰撞后的速度衰减。
     */
    protected double verticalReduction = 0.25;

    /**
     * 垂直速度的最小阈值，用于判断是否触发地面碰撞激活。
     */
    protected double minVerticalSpeed = -0.1;

    /**
     * 构造函数，用于创建投掷物实体。
     *
     * @param type  投掷物的实体类型
     * @param level 投掷物所在的层级
     */
    public BaseProjectileEntity(EntityType<? extends BaseProjectileEntity> type, Level level) {
        super(type, level);
    }

    /**
     * 构造函数，用于创建投掷物实体并指定发射者。
     *
     * @param type    投掷物的实体类型
     * @param shooter 投掷物的发射者
     * @param level   投掷物所在的层级
     */
    public BaseProjectileEntity(EntityType<? extends BaseProjectileEntity> type, LivingEntity shooter, Level level) {
        super(type, shooter, level);
        this.noCulling = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(STATE, 0);
        entityData.define(ACTIVATED, false);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (this.isActivated()) onActiveTick();
        }
    }

    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    // region 核心碰撞逻辑
    /**
     * 处理投掷物的碰撞逻辑。
     * 该方法扩展了父类的碰撞逻辑，增加了对地面碰撞、水平和垂直碰撞的处理。
     * 同时支持激活逻辑和碰撞音效播放。
     *
     * @param r 碰撞结果
     */
    @Override
    protected void onHit(@NotNull HitResult r) {
        if (this.getState() == 2) return;
        super.onHit(r);

        if (r instanceof EntityHitResult entityHitResult) {
            entityHitResult.getEntity().hurt(this.damageSource(), 1);
        }

        if (!(r instanceof BlockHitResult result)) return;
        if (this.level().getGameRules().getRule(FPSMatchRule.RULE_THROWABLE_CAN_CROSS_BARRIER).get()) {
            if (this.level().getBlockState(result.getBlockPos()).getBlock() == Blocks.BARRIER) {
                return;
            }
        }
        // 状态管理
        if (getState() == 0) setState(1);

        Vec3 delta = getDeltaMovement();

        playCollisionSound(level().getBlockState(result.getBlockPos()));

        // 地面碰撞处理
        if (result.getDirection() == Direction.UP) {
            if (activateOnGroundHit) {
                markActivated();
                handleSurfaceStick(result);
            } else {
                if (delta.y >= minVerticalSpeed) {
                    markActivated();
                    handleSurfaceStick(result);
                } else {
                    handleVerticalCollision(delta);
                }
            }
        } else {
            handleGeneralCollision(result, delta);
        }

        playCollisionSound(level().getBlockState(result.getBlockPos()));
    }

    /**
     * 处理通用碰撞逻辑。
     *
     * @param result 碰撞结果
     * @param delta  碰撞时的速度向量
     */
    private void handleGeneralCollision(BlockHitResult result, Vec3 delta) {
        Direction dir = result.getDirection();

        if (dir.getAxis().isHorizontal()) {
            handleHorizontalCollision(dir, delta);
        } else if (dir == Direction.DOWN || delta.y < minVerticalSpeed) {
            handleVerticalCollision(delta);
        } else {
            // 斜向碰撞混合处理
            handleHorizontalCollision(dir, delta);
            handleVerticalCollision(getDeltaMovement());
        }
    }

    /**
     * 处理投掷物粘附到表面的逻辑。
     * <p>
     * 将投掷物的位置微调到碰撞面外侧，并停止其运动。
     *
     * @param result 碰撞结果
     */
    private void handleSurfaceStick(BlockHitResult result) {
        // 获取碰撞点并微调位置到碰撞面外侧
        Vec3 hitPos = result.getLocation();
        Direction dir = result.getDirection();
        Vec3 correctedPos = hitPos.add(dir.getStepX() * 0.001,
                dir.getStepY() * 0.001,
                dir.getStepZ() * 0.001);
        this.setPos(correctedPos.x, correctedPos.y, correctedPos.z);

        this.setDeltaMovement(Vec3.ZERO);
        this.setNoGravity(true);
        this.setState(2);
    }

    /**
     * 处理水平方向的碰撞逻辑。
     * 反转水平方向的速度，并根据减速系数调整速度大小。
     *
     * @param direction 碰撞方向
     * @param delta     碰撞时的速度向量
     */
    private void handleHorizontalCollision(Direction direction, Vec3 delta) {
        // 只反转水平方向速度，保留垂直速度
        double reducedX = delta.x * horizontalReduction;
        double reducedZ = delta.z * horizontalReduction;

        if (direction.getAxis() == Direction.Axis.X) {
            this.setDeltaMovement(-reducedX, delta.y, reducedZ); // 保留Y速度
        } else {
            this.setDeltaMovement(reducedX, delta.y, -reducedZ); // 保留Y速度
        }
    }

    /**
     * 处理垂直方向的碰撞逻辑。
     * <p>
     * 反转垂直方向的速度，并根据减速系数调整速度大小。
     *
     * @param delta 碰撞时的速度向量
     */
    private void handleVerticalCollision(Vec3 delta) {
        // 反转Y方向速度，保留水平动量
        this.setDeltaMovement(
                delta.x * verticalReduction,
                -(delta.y * verticalReduction),
                delta.z * verticalReduction);
    }

    /**
     * 播放碰撞音效。
     *
     * @param blockState 碰撞点的方块状态
     */
    private void playCollisionSound(BlockState blockState) {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                blockState.getSoundType().getStepSound(), SoundSource.PLAYERS, 1, 1.6F);
    }
    // endregion

    // region 可扩展方法
    /**
     * 标记投掷物为激活状态。
     * <p>
     * 调用该方法后，投掷物的状态会被设置为激活，并触发 {@link #onActivated()} 方法。
     */
    protected final void markActivated() {
        if (!level().isClientSide) {
            entityData.set(ACTIVATED, true);
            onActivated();
        }
    }

    /**
     * 投掷物被激活时的回调方法。
     * <p>
     * 子类可以通过覆盖该方法实现自定义的激活逻辑。
     */
    protected void onActivated() {}

    /**
     * 每次激活状态的 tick 调用。
     * <p>
     * 子类可以通过覆盖该方法实现激活状态下的持续逻辑。
     */
    protected void onActiveTick() {}

    // endregion

    // region 访问方法
    /**
     * 获取投掷物的当前状态。
     * 状态值说明：
     * - 0: 初始状态（未碰撞）。
     * - 1: 碰撞后（未激活）。
     * - 2: 激活状态（如粘附到表面）。
     * 
     * @return 投掷物的状态
     */
    public int getState() {
        return entityData.get(STATE);
    }

    /**
     * 设置投掷物的状态。
     * 
     * @param state 新的状态值
     */
    protected void setState(int state) {
        entityData.set(STATE, state);
    }

    /**
     * 检查投掷物是否已被激活。
     * 
     * @return 如果投掷物已被激活，返回 true；否则返回 false
     */
    public boolean isActivated() {
        return entityData.get(ACTIVATED);
    }

    /**
     * 获取是否在地面碰撞时激活投掷物。
     * 
     * @return 如果在地面碰撞时激活，返回 true；否则返回 false
     */
    public boolean isActivateOnGroundHit() {
        return activateOnGroundHit;
    }

    /**
     * 设置是否在地面碰撞时激活投掷物。
     * 
     * @param activateOnGroundHit 是否在地面碰撞时激活
     */
    public void setActivateOnGroundHit(boolean activateOnGroundHit) {
        this.activateOnGroundHit = activateOnGroundHit;
    }

    /**
     * 获取水平方向的减速系数。
     * 
     * @return 水平方向的减速系数
     */
    public double getHorizontalReduction() {
        return horizontalReduction;
    }

    /**
     * 设置水平方向的减速系数。
     * 
     * @param horizontalReduction 新的水平方向减速系数
     */
    public void setHorizontalReduction(double horizontalReduction) {
        this.horizontalReduction = horizontalReduction;
    }

    /**
     * 获取垂直方向的减速系数。
     * 
     * @return 垂直方向的减速系数
     */
    public double getVerticalReduction() {
        return verticalReduction;
    }

    /**
     * 设置垂直方向的减速系数。
     * 
     * @param verticalReduction 新的垂直方向减速系数
     */
    public void setVerticalReduction(double verticalReduction) {
        this.verticalReduction = verticalReduction;
    }

    // endregion

    @Override
    public boolean shouldRender(double pX, double pY, double pZ) {
        return true;
    }

    public DamageSource damageSource() {
        if (this.getOwner() instanceof LivingEntity livingEntity) {
            return this.damageSources().mobProjectile(this, livingEntity);
        } else {
            return this.damageSources().mobProjectile(this, null);
        }
    }

    @Override
    public @NotNull ItemStack getItem() {
        return new ItemStack(this.getDefaultItem());
    }
}
