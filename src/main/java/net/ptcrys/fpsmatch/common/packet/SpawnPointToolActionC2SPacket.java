package net.ptcrys.fpsmatch.common.packet;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.common.item.SpawnPointTool;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.SpawnPointData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.team.ServerTeam;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public record SpawnPointToolActionC2SPacket(
                                            Action action,
                                            String selectedType,
                                            String selectedMap,
                                            String selectedTeam,
                                            int selectedIndex) {

    public enum Action {
        REFRESH,
        SAVE_SELECTIONS,
        DELETE_SELECTED,
        CLEAR_TEAM
    }

    public static void encode(SpawnPointToolActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeUtf(packet.selectedType());
        buf.writeUtf(packet.selectedMap());
        buf.writeUtf(packet.selectedTeam());
        buf.writeVarInt(packet.selectedIndex());
    }

    public static SpawnPointToolActionC2SPacket decode(FriendlyByteBuf buf) {
        return new SpawnPointToolActionC2SPacket(
                buf.readEnum(Action.class),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt());
    }

    public static void sendScreen(ServerPlayer player, ItemStack stack, String requestedType, String requestedMap, String requestedTeam, int requestedIndex) {
        SelectionSnapshot snapshot = resolveSelection(stack, requestedType, requestedMap, requestedTeam, requestedIndex);
        FPSMatch.sendToPlayer(player, new OpenSpawnPointToolScreenS2CPacket(
                snapshot.availableTypes(),
                snapshot.selectedType(),
                snapshot.availableMaps(),
                snapshot.selectedMap(),
                snapshot.availableTeams(),
                snapshot.selectedTeam(),
                snapshot.selectedIndex(),
                snapshot.spawnPoints()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }

            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof SpawnPointTool)) {
                return;
            }
            if (!player.hasPermissions(2)) {
                player.displayClientMessage(Component.literal("需要管理员(OP)权限才能修改复活点"), false);
                return;
            }

            switch (action()) {
                case REFRESH -> sendScreen(player, stack, selectedType(), selectedMap(), selectedTeam(), selectedIndex());
                case SAVE_SELECTIONS -> resolveSelection(stack, selectedType(), selectedMap(), selectedTeam(), selectedIndex());
                case DELETE_SELECTED -> deleteSelected(player, stack);
                case CLEAR_TEAM -> clearTeam(player, stack);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void deleteSelected(ServerPlayer player, ItemStack stack) {
        SelectionSnapshot snapshot = resolveSelection(stack, selectedType(), selectedMap(), selectedTeam(), selectedIndex());
        if (snapshot.capability().isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.team_not_found", snapshot.selectedTeam()), false);
            return;
        }
        if (snapshot.map().map(BaseMap::isStart).orElse(false)) {
            player.displayClientMessage(Component.translatable("gui.fpsm.map_regions.action.in_progress"), false);
            return;
        }
        if (snapshot.selectedIndex() < 0 || snapshot.selectedIndex() >= snapshot.spawnPoints().size()) {
            sendScreen(player, stack, snapshot.selectedType(), snapshot.selectedMap(), snapshot.selectedTeam(), -1);
            return;
        }

        snapshot.capability().get().removeSpawnPointData(snapshot.selectedIndex());
        snapshot.capability().get().clearPlayerSpawnPointAssignments();
        if (!snapshot.capability().get().getSpawnPointsData().isEmpty()) {
            snapshot.capability().get().assignNextSpawnPoints();
        }
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();
        sendScreen(player, stack, snapshot.selectedType(), snapshot.selectedMap(), snapshot.selectedTeam(), snapshot.selectedIndex());
    }

    private void clearTeam(ServerPlayer player, ItemStack stack) {
        SelectionSnapshot snapshot = resolveSelection(stack, selectedType(), selectedMap(), selectedTeam(), selectedIndex());
        if (snapshot.capability().isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.team_not_found", snapshot.selectedTeam()), false);
            return;
        }
        if (snapshot.map().map(BaseMap::isStart).orElse(false)) {
            player.displayClientMessage(Component.translatable("gui.fpsm.map_regions.action.in_progress"), false);
            return;
        }

        snapshot.capability().get().clearSpawnPointsData();
        snapshot.capability().get().clearPlayerSpawnPointAssignments();
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();
        sendScreen(player, stack, snapshot.selectedType(), snapshot.selectedMap(), snapshot.selectedTeam(), -1);
    }

    private static SelectionSnapshot resolveSelection(ItemStack stack, String requestedType, String requestedMap, String requestedTeam, int requestedIndex) {
        List<String> availableTypes = FPSMCore.getInstance().getGameTypes();
        String selectedType = availableTypes.contains(requestedType) ? requestedType : firstOrBlank(availableTypes);
        List<String> availableMaps = selectedType.isBlank() ? List.of() : FPSMCore.getInstance().getMapNamesWithType(selectedType);
        String selectedMap = availableMaps.contains(requestedMap) ? requestedMap : firstOrBlank(availableMaps);
        Optional<BaseMap> map = selectedType.isBlank() || selectedMap.isBlank() ? Optional.empty() : FPSMCore.getInstance().getMapByTypeWithName(selectedType, selectedMap);
        List<String> availableTeams = map.map(baseMap -> baseMap.getMapTeams().getNormalTeamsName()).orElse(List.of());
        String selectedTeam = availableTeams.contains(requestedTeam) ? requestedTeam : firstOrBlank(availableTeams);
        Optional<ServerTeam> team = map.flatMap(baseMap -> baseMap.getMapTeams().getTeamByName(selectedTeam)).filter(ServerTeam::isNormal);
        Optional<SpawnPointCapability> capability = team.flatMap(serverTeam -> serverTeam.getCapabilityMap().get(SpawnPointCapability.class));
        List<SpawnPointData> spawnPoints = capability.map(cap -> List.copyOf(cap.getSpawnPointsData())).orElse(List.of());

        SpawnPointTool.setSelectedType(stack, selectedType);
        SpawnPointTool.setSelectedMap(stack, selectedMap);
        SpawnPointTool.setSelectedTeam(stack, selectedTeam);

        int normalizedIndex = spawnPoints.isEmpty() ? -1 : Math.max(0, Math.min(requestedIndex < 0 ? 0 : requestedIndex, spawnPoints.size() - 1));

        return new SelectionSnapshot(
                availableTypes,
                selectedType,
                availableMaps,
                selectedMap,
                availableTeams,
                selectedTeam,
                normalizedIndex,
                spawnPoints,
                map,
                team,
                capability);
    }

    private static String firstOrBlank(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    private record SelectionSnapshot(
                                     List<String> availableTypes,
                                     String selectedType,
                                     List<String> availableMaps,
                                     String selectedMap,
                                     List<String> availableTeams,
                                     String selectedTeam,
                                     int selectedIndex,
                                     List<SpawnPointData> spawnPoints,
                                     Optional<BaseMap> map,
                                     Optional<ServerTeam> team,
                                     Optional<SpawnPointCapability> capability) {}
}
