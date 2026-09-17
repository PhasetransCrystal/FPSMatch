package net.ptcrys.fpsmatch.common.client.net;

import net.ptcrys.fpsmatch.compat.spectate.lrtactical.SpectatorLrtAttackNet;
import net.ptcrys.fpsmatch.compat.spectate.net.SpectatorInspectPackets;
import net.ptcrys.fpsmatch.compat.spectate.net.SpectatorLrtAttackPackets;
import net.ptcrys.fpsmatch.compat.spectate.tacz.SpectatorGunInspectNet;

public final class SpectatorClientPacketHandlers {

    private SpectatorClientPacketHandlers() {}

    public static void handleWatchedPlayerInspect(SpectatorInspectPackets.S2CWatchedPlayerInspectPacket packet) {
        SpectatorGunInspectNet.handleWatchedPlayerInspectPacket(packet);
    }

    public static void handleWatchedPlayerLrtAttack(SpectatorLrtAttackPackets.S2CWatchedPlayerLrtAttackPacket packet) {
        SpectatorLrtAttackNet.handleWatchedPlayerAttackPacket(packet);
    }
}
