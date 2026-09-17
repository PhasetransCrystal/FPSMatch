package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.core.data.SpawnPointData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record OpenSpawnPointToolScreenS2CPacket(
                                                List<String> availableTypes,
                                                String selectedType,
                                                List<String> availableMaps,
                                                String selectedMap,
                                                List<String> availableTeams,
                                                String selectedTeam,
                                                int selectedIndex,
                                                List<SpawnPointData> spawnPoints) {

    public static void encode(OpenSpawnPointToolScreenS2CPacket packet, FriendlyByteBuf buf) {
        writeStringList(buf, packet.availableTypes());
        buf.writeUtf(packet.selectedType());
        writeStringList(buf, packet.availableMaps());
        buf.writeUtf(packet.selectedMap());
        writeStringList(buf, packet.availableTeams());
        buf.writeUtf(packet.selectedTeam());
        buf.writeVarInt(packet.selectedIndex());
        buf.writeJsonWithCodec(SpawnPointData.CODEC.listOf(), packet.spawnPoints());
    }

    public static OpenSpawnPointToolScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenSpawnPointToolScreenS2CPacket(
                readStringList(buf),
                buf.readUtf(),
                readStringList(buf),
                buf.readUtf(),
                readStringList(buf),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readJsonWithCodec(SpawnPointData.CODEC.listOf()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }

    private static void writeStringList(FriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(values.size());
        for (String value : values) {
            buf.writeUtf(value);
        }
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buf.readUtf());
        }
        return values;
    }
}
