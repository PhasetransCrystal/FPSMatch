package com.ptcrys.fpsmatch.common.capability.team;

import net.minecraft.network.FriendlyByteBuf;

import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.team.BaseTeam;

import java.util.function.Function;

public class CompensationCapability extends TeamCapability implements FPSMCapability.CapabilitySynchronizable {

    private boolean dirty = false;
    private int compensationFactor = 0;
    // 补偿因子 = 当前连败数（0 表示无连败）；上限 4 对应 CS 连败补偿封顶 2900
    private Function<Integer, Integer> setter = (i) -> Math.max(0, Math.min(i, 4));

    public CompensationCapability(BaseTeam team) {
        super(team);
    }

    public static void register() {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, CompensationCapability.class, CompensationCapability::new);
    }

    public void withSetter(Function<Integer, Integer> setter) {
        this.setter = setter;
    }

    public Function<Integer, Integer> getSetter() {
        return setter;
    }

    public void add(int factor) {
        setFactor(compensationFactor + factor);
    }

    public void reduce(int factor) {
        setFactor(compensationFactor - factor);
    }

    public int getFactor() {
        return compensationFactor;
    }

    public void setFactor(int factor) {
        this.compensationFactor = setter == null ? factor : setter.apply(factor);
        dirty = true;
    }

    @Override
    public void reset() {
        compensationFactor = 0;
        dirty = true;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void readFromBuf(FriendlyByteBuf buf) {
        compensationFactor = buf.readInt();
    }

    @Override
    public void writeToBuf(FriendlyByteBuf buf) {
        buf.writeInt(compensationFactor);
        dirty = false;
    }
}
