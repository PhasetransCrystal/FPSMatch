package com.ptcrys.fpsmatch.common.client.net;

import com.ptcrys.fpsmatch.common.client.FPSMGameHudManager;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.packet.*;
import com.ptcrys.fpsmatch.common.packet.mapselect.*;
import com.ptcrys.fpsmatch.common.packet.shop.*;
import com.ptcrys.fpsmatch.common.packet.team.*;
import com.ptcrys.fpsmatch.common.packet.spec.SpectatorTargetS2CPacket;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorInspectPackets;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorLrtAttackPackets;

public final class FPSMClientPacketRegistrar {
    private static boolean registered;
    private FPSMClientPacketRegistrar() {}

    public static synchronized void registerAll() {
        if (registered) return;
        registered = true;
        ClientPacketRegistry.register(OpenMapCreatorToolScreenS2CPacket.class, FPSMClientPacketHandlers::handleOpenMapCreatorToolScreen);
        ClientPacketRegistry.register(OpenMatchConfigToolScreenS2CPacket.class, FPSMClientPacketHandlers::handleOpenMatchConfigToolScreen);
        ClientPacketRegistry.register(OpenSpawnPointToolScreenS2CPacket.class, FPSMClientPacketHandlers::handleOpenSpawnPointToolScreen);
        ClientPacketRegistry.register(OpenShopConfigToolScreenS2CPacket.class, FPSMClientPacketHandlers::handleOpenShopConfigToolScreen);
        ClientPacketRegistry.register(FPSMatchRespawnS2CPacket.class, FPSMClientPacketHandlers::handleRespawn);
        ClientPacketRegistry.register(FPSMInventorySelectedS2CPacket.class, FPSMClientPacketHandlers::handleInventorySelected);
        ClientPacketRegistry.register(AddAreaDataS2CPacket.class, FPSMClientPacketHandlers::handleAddAreaData);
        ClientPacketRegistry.register(AddPointDataS2CPacket.class, FPSMClientPacketHandlers::handleAddPointData);
        ClientPacketRegistry.register(FPSMatchGameTypeS2CPacket.class, FPSMClientPacketHandlers::handleGameType);
        ClientPacketRegistry.register(FPSMatchStatsResetS2CPacket.class, FPSMClientPacketHandlers::handleStatsReset);
        ClientPacketRegistry.register(FPSMSoundPlayS2CPacket.class, FPSMClientPacketHandlers::handleSoundPlay);
        ClientPacketRegistry.register(FPSMusicPlayS2CPacket.class, FPSMClientPacketHandlers::handleMusicPlay);
        ClientPacketRegistry.register(FPSMusicStopS2CPacket.class, FPSMClientPacketHandlers::handleMusicStop);
        ClientPacketRegistry.register(RemoveDebugDataByPrefixS2CPacket.class, FPSMClientPacketHandlers::handleRemoveDebugDataByPrefix);
        ClientPacketRegistry.register(ShopDataSlotS2CPacket.class, FPSMClientPacketHandlers::handleShopDataSlot);
        ClientPacketRegistry.register(ShopActionResultS2CPacket.class, FPSMClientPacketHandlers::handleShopActionResult);
        ClientPacketRegistry.register(ShopMoneyS2CPacket.class, FPSMClientPacketHandlers::handleShopMoney);
        ClientPacketRegistry.register(MapImportSourcesS2CPacket.class, FPSMClientPacketHandlers::handleMapImportSources);
        ClientPacketRegistry.register(FPSMAddTeamS2CPacket.class, FPSMClientPacketHandlers::handleAddTeam);
        ClientPacketRegistry.register(TeamCapabilitiesS2CPacket.class, FPSMClientPacketHandlers::handleTeamCapabilities);
        ClientPacketRegistry.register(TeamPlayerLeaveS2CPacket.class, FPSMClientPacketHandlers::handleTeamPlayerLeave);
        ClientPacketRegistry.register(TeamPlayerStatsS2CPacket.class, FPSMClientPacketHandlers::handleTeamPlayerStats);
        ClientPacketRegistry.register(MapSelectionAccessS2CPacket.class, FPSMClientPacketHandlers::handleMapSelectionAccess);
        ClientPacketRegistry.register(MapSelectionSnapshotS2CPacket.class, FPSMClientPacketHandlers::handleMapSelectionSnapshot);
        ClientPacketRegistry.register(MapRoomDetailS2CPacket.class, FPSMClientPacketHandlers::handleMapRoomDetail);
        ClientPacketRegistry.register(MapRoomReadyStateS2CPacket.class, FPSMClientPacketHandlers::handleMapRoomReadyState);
        ClientPacketRegistry.register(MapRoomToastS2CPacket.class, FPSMClientPacketHandlers::handleMapRoomToast);
        ClientPacketRegistry.register(MapRoomInvitationS2CPacket.class, FPSMClientPacketHandlers::handleMapRoomInvitation);
        ClientPacketRegistry.register(TeamManageResultS2CPacket.class, FPSMClientPacketHandlers::handleTeamManageResult);
        ClientPacketRegistry.register(SpectatorTargetS2CPacket.class, SpectatorTargetClientHandler::handle);
        ClientPacketRegistry.register(SpectatorInspectPackets.S2CWatchedPlayerInspectPacket.class, SpectatorClientPacketHandlers::handleWatchedPlayerInspect);
        ClientPacketRegistry.register(SpectatorLrtAttackPackets.S2CWatchedPlayerLrtAttackPacket.class, SpectatorClientPacketHandlers::handleWatchedPlayerLrtAttack);
    }
}
