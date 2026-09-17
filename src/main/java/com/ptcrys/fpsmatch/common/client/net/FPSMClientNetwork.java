package com.ptcrys.fpsmatch.common.client.net;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class FPSMClientNetwork {

    private FPSMClientNetwork() {}

    public static boolean canSendToServer() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && listener.getConnection().isConnected();
    }
}
