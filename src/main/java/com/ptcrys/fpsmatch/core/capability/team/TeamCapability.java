package com.ptcrys.fpsmatch.core.capability.team;

import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.team.BaseTeam;

/**
 * 队伍专属能力接口
 * 持有者固定为BaseTeam，继承基础能力接口
 */
public abstract class TeamCapability extends FPSMCapability<BaseTeam> {

    protected final BaseTeam team;

    public TeamCapability(BaseTeam team) {
        this.team = team;
    }

    @Override
    public final BaseTeam getHolder() {
        return team;
    }
}
