package com.ptcrys.fpsmatch.core.map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.ptcrys.fpsmatch.core.capability.map.MapCapability;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.match.RoundContext;
import com.ptcrys.fpsmatch.core.match.RoundLifecycle;
import com.ptcrys.fpsmatch.core.match.RoundResult;

import java.util.List;

/**
 * 带回合生命周期的地图基类。
 * 子类通过 {@link #buildRoundLifecycle()} 配置回合时间、规则和回调，
 * 由基类统一驱动 tick、启动和重置。
 */
public abstract class BaseRoundMap<W, R> extends BaseMap {

    protected RoundLifecycle<W, R> roundLifecycle;

    public BaseRoundMap(ServerLevel serverLevel, String mapName, AreaData areaData) {
        super(serverLevel, mapName, areaData);
    }

    public BaseRoundMap(ServerLevel serverLevel,
                        String mapName,
                        AreaData areaData,
                        List<Class<? extends MapCapability>> capabilities) {
        super(serverLevel, mapName, areaData, capabilities);
    }

    /**
     * 构造当前地图的回合生命周期配置。
     * 子类可先用 {@link #lifecycleBuilder()} 获取已绑定默认回调的 Builder，再补充时间/规则/超时结果。
     */
    protected abstract RoundLifecycle<W, R> buildRoundLifecycle();

    /**
     * 返回已绑定默认生命周期回调的 Builder。
     */
    protected RoundLifecycle.Builder<W, R> lifecycleBuilder() {
        return RoundLifecycle.<W, R>builder()
                .onRoundStart(this::onRoundStart)
                .onRoundEnd(this::onRoundEnd)
                .onNextRoundRequested(this::onNextRoundRequested)
                .onWaitingTick(this::onWaitingTick)
                .onRoundTick(this::onRoundTick);
    }

    /**
     * 子类可覆盖：回合开始回调。
     */
    protected void onRoundStart() {}

    /**
     * 子类可覆盖：回合结束回调。
     */
    protected void onRoundEnd(RoundResult<W, R> result) {}

    /**
     * 子类可覆盖：请求进入下一回合回调。
     */
    protected void onNextRoundRequested() {}

    /**
     * 子类可覆盖：等待阶段每 tick 回调。
     */
    protected void onWaitingTick(RoundContext ctx) {}

    /**
     * 子类可覆盖：回合进行中每 tick 回调。
     */
    protected void onRoundTick(RoundContext ctx) {}

    /**
     * 子类可覆盖：构造传入 lifecycle 的上下文。
     */
    protected RoundContext createRoundContext() {
        return new RoundContext() {};
    }

    /**
     * 子类可覆盖：是否允许 lifecycle 在本 tick 推进。
     * 可用于暂停、热身等全局状态拦截。
     */
    protected boolean shouldAdvanceRoundLifecycle() {
        return true;
    }

    public void handleRespawn(ServerPlayer player) {
        this.getMapTeams().getPlayerData(player).ifPresent(data -> data.setLiving(true));
        this.teleportPlayerToReSpawnPoint(player);
    }

    /**
     * 重新创建 lifecycle，通常在开始新回合时调用。
     */
    protected void rebuildRoundLifecycle() {
        this.roundLifecycle = buildRoundLifecycle();
    }

    @Override
    public void tick() {
        super.tick();
        if (isStart && roundLifecycle != null && shouldAdvanceRoundLifecycle()) {
            roundLifecycle.tick(createRoundContext());
        }
    }

    @Override
    public boolean start() {
        if (!super.start()) {
            return false;
        }
        this.roundLifecycle = buildRoundLifecycle();
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        this.roundLifecycle = null;
    }
}
