package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.mojang.logging.LogUtils;
import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorInspectPackets.S2CWatchedPlayerInspectPacket;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Handles inspect sync packets for local and spectated players.
 */
public final class SpectatorGunInspectNet {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DEBUG_SYS_PROP = "fpsm.spectate.tacz.inspect.debug";
    private static volatile boolean DEBUG_ENABLED = Boolean.parseBoolean(System.getProperty(DEBUG_SYS_PROP, "false"));

    private SpectatorGunInspectNet() {}

    public static void setDebugEnabled(boolean enabled) {
        DEBUG_ENABLED = enabled;
    }

    public static void handleWatchedPlayerInspectPacket(S2CWatchedPlayerInspectPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer localPlayer = mc.player;
        if (localPlayer == null) {
            return;
        }
        UUID targetId = packet.getPlayerId();
        dbg("[SpectatorGunInspectNet] Received S2CWatchedPlayerInspectPacket: playerId={}", targetId);
        if (localPlayer.getUUID().equals(targetId)) {
            dbg("[SpectatorGunInspectNet] Local inspect trigger.");
            SpectatorGunInspect.playInspectAnimationFor(localPlayer);
            return;
        }
        if (SpectatorView.isSpectatingOther(localPlayer)) {
            Player target = SpectatorView.getSpectatedPlayer(localPlayer);
            if (target != null && target.getUUID().equals(targetId)) {
                dbg("[SpectatorGunInspectNet] Spectating that player: mirror inspect.");
                ItemStack targetStack = target.getMainHandItem();
                if (!targetStack.isEmpty()) {
                    SpectatorGunItemMirror.equip(localPlayer, targetStack);
                    SpectatorGunItemMirror.tick(localPlayer);
                }
                SpectatorGunInspect.playInspectAnimationFor(localPlayer);
                return;
            }
        }
        dbg("[SpectatorGunInspectNet] Packet ignored: not self nor current spectate target.");
    }

    private static void dbg(String msg, Object... args) {
        if (DEBUG_ENABLED) {
            LOGGER.info(msg, args);
        }
    }
}
