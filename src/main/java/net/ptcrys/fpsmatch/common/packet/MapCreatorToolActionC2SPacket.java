package net.ptcrys.fpsmatch.common.packet;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.capability.map.DemolitionModeCapability;
import net.ptcrys.fpsmatch.common.item.MapCreatorTool;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.util.MapId;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.mojang.datafixers.util.Function3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Supplier;

public record MapCreatorToolActionC2SPacket(
                                            Action action,
                                            String selectedType,
                                            String selectedMap,
                                            String draftMapName,
                                            @Nullable BlockPos pos1,
                                            @Nullable BlockPos pos2) {

    public enum Action {
        SAVE_DRAFT,
        CREATE,
        UPDATE
    }

    public static void encode(MapCreatorToolActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeUtf(packet.selectedType());
        buf.writeUtf(packet.selectedMap());
        buf.writeUtf(packet.draftMapName());
        writeNullableBlockPos(buf, packet.pos1());
        writeNullableBlockPos(buf, packet.pos2());
    }

    public static MapCreatorToolActionC2SPacket decode(FriendlyByteBuf buf) {
        return new MapCreatorToolActionC2SPacket(
                buf.readEnum(Action.class),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                readNullableBlockPos(buf),
                readNullableBlockPos(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }

            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof MapCreatorTool)) {
                return;
            }
            // CREATE/UPDATE 会写服务端地图注册表并落盘，仅允许 OP(权限等级>=2) 执行；
            // SAVE_DRAFT 只修改工具自身的 NBT 存档，普通玩家可保留。
            if ((action() == Action.CREATE || action() == Action.UPDATE) && !player.hasPermissions(2)) {
                player.displayClientMessage(
                        Component.literal("需要管理员(OP)权限才能创建/修改地图"),
                        false);
                return;
            }

            switch (action()) {
                case SAVE_DRAFT -> saveDraft(stack);
                case CREATE -> createMap(player, stack);
                case UPDATE -> updateMap(player, stack);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void saveDraft(ItemStack stack) {
        MapCreatorTool.setSelectedType(stack, selectedType().trim());
        MapCreatorTool.setSelectedMap(stack, selectedMap().trim());
        MapCreatorTool.setDraftMapName(stack, draftMapName());
        MapCreatorTool.setBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_1, pos1());
        MapCreatorTool.setBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_2, pos2());
    }

    private void createMap(ServerPlayer player, ItemStack stack) {
        String type = selectedType().trim();
        if (!FPSMCore.getInstance().checkGameType(type)) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.invalid_type"), false);
            return;
        }

        String mapName = draftMapName().trim();
        if (!MapId.isValid(mapName)) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.invalid_name"), false);
            return;
        }

        Optional<AreaData> areaData = createArea();
        if (areaData.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.invalid_area"), false);
            return;
        }

        if (FPSMCore.getInstance().isRegistered(type, mapName)) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.duplicate_map", mapName), false);
            return;
        }

        ServerLevel level = player.serverLevel();
        Function3<ServerLevel, String, AreaData, BaseMap> factory = FPSMCore.getInstance().getPreBuildGame(type);
        if (factory == null) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.invalid_type"), false);
            return;
        }

        BaseMap newMap = factory.apply(level, mapName, areaData.get());
        if (!FPSMCore.getInstance().registerMap(type, newMap)) {
            player.displayClientMessage(Component.translatable(
                    "message.fpsm.map_creator_tool.duplicate_map", mapName), false);
            return;
        }
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();

        MapCreatorTool.setSelectedType(stack, type);
        MapCreatorTool.setSelectedMap(stack, mapName);
        MapCreatorTool.setBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_1, pos1());
        MapCreatorTool.setBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_2, pos2());
        MapCreatorTool.setDraftMapName(stack, "");

        player.displayClientMessage(Component.translatable("commands.fpsm.create.success", mapName), false);
        FPSMatch.sendToPlayer(player, OpenMapCreatorToolScreenS2CPacket.fromStack(stack, FPSMCore.getInstance().getGameTypes()));
    }

    private void updateMap(ServerPlayer player, ItemStack stack) {
        String type = selectedType().trim();
        if (!FPSMCore.getInstance().checkGameType(type)) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.invalid_type"), false);
            return;
        }

        String mapName = selectedMap().trim();
        if (mapName.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.map_not_found"), false);
            return;
        }

        Optional<AreaData> areaData = createArea();
        if (areaData.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.invalid_area"), false);
            return;
        }

        Optional<BaseMap> mapOptional = FPSMCore.getInstance().getMapByTypeWithName(type, mapName);
        if (mapOptional.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.map_not_found"), false);
            return;
        }

        BaseMap map = mapOptional.get();
        if (map.isStart()) {
            player.displayClientMessage(Component.translatable("gui.fpsm.map_regions.action.in_progress"), false);
            return;
        }
        if (!map.getServerLevel().dimension().equals(player.serverLevel().dimension())) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.dimension_mismatch"), false);
            return;
        }
        boolean containsBombAreas = map.getCapabilityMap().get(DemolitionModeCapability.class)
                .map(capability -> capability.getBombAreaData().stream().allMatch(bombArea -> areaData.get().isBlockPosInArea(bombArea.pos1()) && areaData.get().isBlockPosInArea(bombArea.pos2())))
                .orElse(true);
        if (!containsBombAreas) {
            player.displayClientMessage(Component.translatable("gui.fpsm.map_regions.action.bombs_outside_map"), false);
            return;
        }

        map.setMapArea(areaData.get());
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();

        MapCreatorTool.setSelectedType(stack, type);
        MapCreatorTool.setSelectedMap(stack, mapName);
        MapCreatorTool.setBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_1, pos1());
        MapCreatorTool.setBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_2, pos2());

        player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.update_success", mapName), false);
        FPSMatch.sendToPlayer(player, OpenMapCreatorToolScreenS2CPacket.fromStack(stack, FPSMCore.getInstance().getGameTypes()));
    }

    private Optional<AreaData> createArea() {
        if (pos1() == null || pos2() == null) {
            return Optional.empty();
        }
        return Optional.of(new AreaData(pos1(), pos2()));
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
}
