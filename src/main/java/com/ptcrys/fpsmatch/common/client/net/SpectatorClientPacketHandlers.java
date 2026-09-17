package com.ptcrys.fpsmatch.common.client.net;

import com.ptcrys.fpsmatch.compat.spectate.lrtactical.SpectatorLrtAttackNet;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorInspectPackets;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorLrtAttackPackets;
import com.ptcrys.fpsmatch.compat.spectate.tacz.SpectatorGunInspectNet;

public final class SpectatorClientPacketHandlers {

    private SpectatorClientPacketHandlers() {}

    public static void handleWatchedPlayerInspect(SpectatorInspectPackets.S2CWatchedPlayerInspectPacket packet) {
        SpectatorGunInspectNet.handleWatchedPlayerInspectPacket(packet);
    }

    public static void handleWatchedPlayerLrtAttack(SpectatorLrtAttackPackets.S2CWatchedPlayerLrtAttackPacket packet) {
        SpectatorLrtAttackNet.handleWatchedPlayerAttackPacket(packet);
    }
}
