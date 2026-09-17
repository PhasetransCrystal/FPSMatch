package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.item.MapCreatorTool;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record OpenMapCreatorToolScreenS2CPacket(
                                                List<String> availableTypes,
                                                String selectedType,
                                                String selectedMap,
                                                List<MapEntry> maps,
                                                String draftMapName,
                                                @Nullable BlockPos pos1,
                                                @Nullable BlockPos pos2) {

    public static OpenMapCreatorToolScreenS2CPacket fromStack(ItemStack stack, List<String> availableTypes) {
        return new OpenMapCreatorToolScreenS2CPacket(
                List.copyOf(availableTypes),
                MapCreatorTool.getSelectedType(stack),
                MapCreatorTool.getSelectedMap(stack),
                collectMaps(),
                MapCreatorTool.getDraftMapName(stack),
                MapCreatorTool.getBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_1),
                MapCreatorTool.getBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_2));
    }

    public static void encode(OpenMapCreatorToolScreenS2CPacket packet, FriendlyByteBuf buf) {
        writeStringList(buf, packet.availableTypes());
        buf.writeUtf(packet.selectedType());
        buf.writeUtf(packet.selectedMap());
        buf.writeVarInt(packet.maps().size());
        for (MapEntry map : packet.maps()) {
            buf.writeUtf(map.type());
            buf.writeUtf(map.name());
            buf.writeBlockPos(map.pos1());
            buf.writeBlockPos(map.pos2());
        }
        buf.writeUtf(packet.draftMapName());
        writeNullableBlockPos(buf, packet.pos1());
        writeNullableBlockPos(buf, packet.pos2());
    }

    public static OpenMapCreatorToolScreenS2CPacket decode(FriendlyByteBuf buf) {
        List<String> availableTypes = readStringList(buf);
        String selectedType = buf.readUtf();
        String selectedMap = buf.readUtf();
        int mapCount = buf.readVarInt();
        List<MapEntry> maps = new ArrayList<>(mapCount);
        for (int i = 0; i < mapCount; i++) {
            maps.add(new MapEntry(buf.readUtf(), buf.readUtf(), buf.readBlockPos(), buf.readBlockPos()));
        }
        return new OpenMapCreatorToolScreenS2CPacket(
                availableTypes,
                selectedType,
                selectedMap,
                maps,
                buf.readUtf(),
                readNullableBlockPos(buf),
                readNullableBlockPos(buf));
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

    private static void writeNullableBlockPos(FriendlyByteBuf buf, @Nullable BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeBlockPos(pos);
        }
    }

    private static @Nullable BlockPos readNullableBlockPos(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBlockPos() : null;
    }

    private static List<MapEntry> collectMaps() {
        List<MapEntry> maps = new ArrayList<>();
        if (!FPSMCore.initialized()) {
            return maps;
        }
        FPSMCore.getInstance().getAllMaps().forEach((type, mapList) -> {
            for (BaseMap map : mapList) {
                maps.add(new MapEntry(type, map.getMapName(), map.getMapArea().pos1(), map.getMapArea().pos2()));
            }
        });
        return maps;
    }

    public record MapEntry(String type, String name, BlockPos pos1, BlockPos pos2) {}
}
