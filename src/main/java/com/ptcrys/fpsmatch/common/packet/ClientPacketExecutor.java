package com.ptcrys.fpsmatch.common.packet;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ClientPacketExecutor {

    private ClientPacketExecutor() {}

    public static void execute(Supplier<NetworkEvent.Context> ctxSupplier, Object packet) {
        NetworkEvent.Context context = ctxSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketRegistry.handle(packet)));
        context.setPacketHandled(true);
    }
}
