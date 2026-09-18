package com.example.elimination;

import net.ptcrys.fpsmatch.common.packet.register.NetworkPacketRegister;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;

public final class EliminationNetwork {
    public static final NetworkPacketRegister PACKETS = new NetworkPacketRegister(
            new ResourceLocation(EliminationMod.MODID, "main"), "1");

    public static void register() {
        PACKETS.registerPacket(ScoreS2CPacket.class, NetworkDirection.PLAY_TO_CLIENT);
    }
}
