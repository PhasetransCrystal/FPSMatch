package net.ptcrys.fpsmatch.common.packet.mapselect;

import net.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record MapImportSourcesS2CPacket(
                                        String gameType,
                                        String mapName,
                                        List<MapImportSourceInfo> sources) {

    private static final int MAX_LENGTH = 128;

    public static void encode(MapImportSourcesS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.gameType, MAX_LENGTH);
        buf.writeUtf(packet.mapName, MAX_LENGTH);
        buf.writeCollection(packet.sources, (buffer, source) -> source.encode(buffer));
    }

    public static MapImportSourcesS2CPacket decode(FriendlyByteBuf buf) {
        return new MapImportSourcesS2CPacket(buf.readUtf(MAX_LENGTH), buf.readUtf(MAX_LENGTH),
                buf.readCollection(ArrayList::new, MapImportSourceInfo::decode));
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        ClientPacketExecutor.execute(contextSupplier, this);
    }
}
