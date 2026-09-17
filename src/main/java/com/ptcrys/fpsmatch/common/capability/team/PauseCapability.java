package com.ptcrys.fpsmatch.common.capability.team;

import net.minecraft.network.FriendlyByteBuf;

import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.team.BaseTeam;

public class PauseCapability extends TeamCapability implements FPSMCapability.CapabilitySynchronizable {

    private boolean dirty = false;
    private int pauseTime = 0;
    private boolean needPause = false;

    public PauseCapability(BaseTeam team) {
        super(team);
    }

    public static void register() {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, PauseCapability.class, PauseCapability::new);
    }

    public void addPause() {
        if (pauseTime < 2 && !needPause) {
            needPause = true;
            pauseTime++;
        }
    }

    public boolean canPause() {
        return pauseTime < 2 && !needPause;
    }

    public void setPauseTime(int t) {
        this.pauseTime = t;
        dirty = true;
    }

    public void resetPauseIfNeed() {
        if (this.needPause) {
            this.needPause = false;
            this.pauseTime--;
            this.dirty = true;
        }
    }

    public void setNeedPause(boolean needPause) {
        this.needPause = needPause;
        this.dirty = true;
    }

    public boolean needPause() {
        return needPause;
    }

    public int getPauseTime() {
        return pauseTime;
    }

    @Override
    public void init() {
        this.pauseTime = 0;
        this.needPause = false;
    }

    @Override
    public void reset() {
        this.pauseTime = 0;
        this.needPause = false;
        this.dirty = true;
    }

    @Override
    public void destroy() {
        reset();
    }

    @Override
    public boolean isDirty() {
        return this.dirty;
    }

    @Override
    public void readFromBuf(FriendlyByteBuf buf) {
        this.pauseTime = buf.readInt();
        this.needPause = buf.readBoolean();
    }

    @Override
    public void writeToBuf(FriendlyByteBuf buf) {
        buf.writeInt(this.pauseTime);
        buf.writeBoolean(this.needPause);
        this.dirty = false;
    }
}
