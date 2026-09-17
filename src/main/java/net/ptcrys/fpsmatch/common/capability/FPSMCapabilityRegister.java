package net.ptcrys.fpsmatch.common.capability;

import net.ptcrys.fpsmatch.common.capability.map.DemolitionModeCapability;
import net.ptcrys.fpsmatch.common.capability.map.GameEndTeleportCapability;
import net.ptcrys.fpsmatch.common.capability.team.*;

public class FPSMCapabilityRegister {

    public static void register() {
        // TEAM
        CompensationCapability.register();
        PauseCapability.register();
        SpawnPointCapability.register();
        TeamSwitchRestrictionCapability.register();
        StartKitsCapability.register();
        ShopCapability.register();
        // MAP
        DemolitionModeCapability.register();
        GameEndTeleportCapability.register();
    }
}
