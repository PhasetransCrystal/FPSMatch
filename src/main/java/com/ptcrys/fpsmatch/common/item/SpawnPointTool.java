package com.ptcrys.fpsmatch.common.item;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import com.ptcrys.fpsmatch.common.item.tool.CreatorToolItem;
import com.ptcrys.fpsmatch.common.item.tool.ToolInteractionAction;
import com.ptcrys.fpsmatch.common.item.tool.WorldToolItem;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickActionContext;
import com.ptcrys.fpsmatch.common.packet.AddAreaDataS2CPacket;
import com.ptcrys.fpsmatch.common.packet.AddPointDataS2CPacket;
import com.ptcrys.fpsmatch.common.packet.RemoveDebugDataByPrefixS2CPacket;
import com.ptcrys.fpsmatch.common.packet.SpawnPointToolActionC2SPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.data.SpawnPointData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.util.PreviewColorUtil;
import com.ptcrys.fpsmatch.util.SpawnPointSafety;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class SpawnPointTool extends CreatorToolItem implements WorldToolItem {
    private static final String HELD_PREVIEW_STATE_TAG = "HeldSpawnPointPreviewState";
    private static final int HELD_PREVIEW_REFRESH_INTERVAL = 10;

    public SpawnPointTool(Properties pProperties) {
        super(pProperties.stacksTo(1));
    }

    @Override
    protected void onLeftClick(ClickActionContext context) {
    }

    @Override
    protected void onRightClick(ClickActionContext context) {
    }

    @Override
    public void handleWorldInteraction(ServerPlayer player, ItemStack stack, ToolInteractionAction action, @Nullable BlockPos clickedPos) {
        switch (action) {
            case RIGHT_CLICK_BLOCK -> {
                if (clickedPos != null) {
                    addSpawnPoint(player, stack, clickedPos);
                }
            }
            case CTRL_RIGHT_CLICK -> SpawnPointToolActionC2SPacket.sendScreen(player, stack,
                    getSelectedType(stack), getSelectedMap(stack), getSelectedTeam(stack), 0);
            case LEFT_CLICK_BLOCK -> {
            }
        }
    }

    public void syncHeldPreview(ServerPlayer player, ItemStack stack) {
        String selectedType = getSelectedType(stack).trim();
        String selectedMap = getSelectedMap(stack).trim();
        if (selectedType.isBlank() || selectedMap.isBlank()) {
            clearHeldPreview(player);
            return;
        }

        Optional<BaseMap> mapOptional = FPSMCore.getInstance().getMapByTypeWithName(selectedType, selectedMap)
                .filter(map -> map.getServerLevel().dimension().equals(player.serverLevel().dimension()));
        if (mapOptional.isEmpty()) {
            clearHeldPreview(player);
            return;
        }

        String signature = selectedType + "|" + selectedMap;
        BaseMap map = mapOptional.get();
        String signatureWithPoints = buildHeldPreviewSignature(signature, map);
        String previousSignature = player.getPersistentData().getString(HELD_PREVIEW_STATE_TAG);
        if (signatureWithPoints.equals(previousSignature) && player.tickCount % HELD_PREVIEW_REFRESH_INTERVAL != 0) {
            return;
        }

        FPSMatch.sendToPlayer(player, new RemoveDebugDataByPrefixS2CPacket(getHeldPreviewPrefix(player)));
        FPSMatch.sendToPlayer(player, new AddAreaDataS2CPacket(
                getHeldPreviewKey(player),
                Component.literal(map.getMapName()),
                PreviewColorUtil.getMapPreviewColor(selectedType),
                map.getMapArea()
        ));

        List<ServerTeam> orderedTeams = getOrderedNormalTeams(map);
        for (int teamIndex = 0; teamIndex < orderedTeams.size(); teamIndex++) {
            ServerTeam team = orderedTeams.get(teamIndex);
            SpawnPointCapability capability = team.getCapabilityMap().get(SpawnPointCapability.class).orElse(null);
            if (capability == null) {
                continue;
            }
            int pointColor = PreviewColorUtil.getPointPreviewColor(selectedType, teamIndex);
            List<SpawnPointData> spawnPoints = capability.getSpawnPointsData();
            for (int i = 0; i < spawnPoints.size(); i++) {
                SpawnPointData data = spawnPoints.get(i);
                FPSMatch.sendToPlayer(player, new AddPointDataS2CPacket(
                        getHeldPreviewPointKey(player, team.getName(), i),
                        Component.literal(team.getName() + " #" + (i + 1)),
                        pointColor,
                        data.getPosition()
                ));
            }
        }

        player.getPersistentData().putString(HELD_PREVIEW_STATE_TAG, signatureWithPoints);
    }

    public static void clearHeldPreview(ServerPlayer player) {
        if (!player.getPersistentData().contains(HELD_PREVIEW_STATE_TAG)) {
            return;
        }

        FPSMatch.sendToPlayer(player, new RemoveDebugDataByPrefixS2CPacket(getHeldPreviewPrefix(player)));
        player.getPersistentData().remove(HELD_PREVIEW_STATE_TAG);
    }

    private static String getHeldPreviewPrefix(ServerPlayer player) {
        return "held_tool_preview:spawn_point:" + player.getUUID() + ":";
    }

    private static String getHeldPreviewKey(ServerPlayer player) {
        return getHeldPreviewPrefix(player) + "area";
    }

    private static String getHeldPreviewPointKey(ServerPlayer player, String teamName, int index) {
        return getHeldPreviewPrefix(player) + teamName + ":" + index;
    }

    private static String buildHeldPreviewSignature(String baseSignature, BaseMap map) {
        StringBuilder builder = new StringBuilder(baseSignature);
        for (ServerTeam team : getOrderedNormalTeams(map)) {
            builder.append('|').append(team.getName());
            team.getCapabilityMap().get(SpawnPointCapability.class).ifPresent(capability -> {
                for (SpawnPointData point : capability.getSpawnPointsData()) {
                    builder.append('|')
                            .append(point.getDimension().location())
                            .append('@')
                            .append(point.getX()).append(',')
                            .append(point.getY()).append(',')
                            .append(point.getZ()).append(',')
                            .append(point.getYaw()).append(',')
                            .append(point.getPitch());
                }
            });
        }
        return builder.toString();
    }

    private static List<ServerTeam> getOrderedNormalTeams(BaseMap map) {
        return map.getMapTeams().getNormalTeams().stream()
                .sorted(Comparator.comparing(ServerTeam::getFixedName))
                .toList();
    }

    private void addSpawnPoint(ServerPlayer player, ItemStack stack, BlockPos clickedPos) {
        String selectedType = getSelectedType(stack);
        String selectedMap = getSelectedMap(stack);
        String selectedTeam = getSelectedTeam(stack);
        if (selectedType.isBlank() || selectedMap.isBlank() || selectedTeam.isBlank()) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.missing_selection"), false);
            return;
        }

        Optional<BaseMap> mapOptional = FPSMCore.getInstance().getMapByTypeWithName(selectedType, selectedMap);
        if (mapOptional.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.map_not_found", selectedMap), false);
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
        BlockPos spawnBlockPos = clickedPos.above();
        if (!map.getMapArea().isBlockPosInPlacementArea(clickedPos)
                && !map.getMapArea().isBlockPosInPlacementArea(spawnBlockPos)
                && !map.getMapArea().isBlockPosInPlacementArea(player.blockPosition())) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.outside_map"), false);
            return;
        }
        if (!SpawnPointSafety.isSafe(player.serverLevel(), spawnBlockPos)) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.unsafe"), false);
            return;
        }

        Optional<ServerTeam> teamOptional = map.getMapTeams().getTeamByName(selectedTeam).filter(ServerTeam::isNormal);
        if (teamOptional.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.team_not_found", selectedTeam), false);
            return;
        }

        SpawnPointCapability capability = teamOptional.get().getCapabilityMap().get(SpawnPointCapability.class).orElse(null);
        if (capability == null) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.missing_capability"), false);
            return;
        }

        SpawnPointData spawnPointData = new SpawnPointData(
                player.serverLevel().dimension(),
                spawnBlockPos.getCenter(),
                player.getYRot(),
                player.getXRot()
        );
        if (!capability.addSpawnPointDataIfAbsent(spawnPointData)) {
            player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.duplicate"), false);
            return;
        }
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();

        player.displayClientMessage(Component.translatable("message.fpsm.spawn_point_tool.added",
                MapCreatorTool.formatPos(spawnBlockPos)).withStyle(ChatFormatting.GREEN), true);
    }

    public static void setSelectedType(ItemStack stack, String selectedType) {
        stack.getOrCreateTag().putString(TYPE_TAG, selectedType == null ? "" : selectedType);
    }

    public static String getSelectedType(ItemStack stack) {
        return stack.getOrCreateTag().getString(TYPE_TAG);
    }

    public static void setSelectedMap(ItemStack stack, String selectedMap) {
        stack.getOrCreateTag().putString(MAP_TAG, selectedMap == null ? "" : selectedMap);
    }

    public static String getSelectedMap(ItemStack stack) {
        return stack.getOrCreateTag().getString(MAP_TAG);
    }

    public static void setSelectedTeam(ItemStack stack, String selectedTeam) {
        stack.getOrCreateTag().putString(TEAM_TAG, selectedTeam == null ? "" : selectedTeam);
    }

    public static String getSelectedTeam(ItemStack stack) {
        return stack.getOrCreateTag().getString(TEAM_TAG);
    }

    @Override
    public void appendHoverText(ItemStack pStack, @Nullable Level pLevel, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        super.appendHoverText(pStack, pLevel, pTooltipComponents, pIsAdvanced);
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.separator").withStyle(ChatFormatting.GOLD));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.spawn_point_tool.selected.type")
                .append(": ")
                .append(Component.literal(getSelectedType(pStack).isBlank()
                        ? Component.translatable("tooltip.fpsm.none").getString()
                        : getSelectedType(pStack)).withStyle(ChatFormatting.AQUA)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.spawn_point_tool.selected.map")
                .append(": ")
                .append(Component.literal(getSelectedMap(pStack).isBlank()
                        ? Component.translatable("tooltip.fpsm.none").getString()
                        : getSelectedMap(pStack)).withStyle(ChatFormatting.GREEN)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.spawn_point_tool.selected.team")
                .append(": ")
                .append(Component.literal(getSelectedTeam(pStack).isBlank()
                        ? Component.translatable("tooltip.fpsm.none").getString()
                        : getSelectedTeam(pStack)).withStyle(ChatFormatting.YELLOW)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.separator").withStyle(ChatFormatting.GOLD));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.spawn_point_tool.right_click"));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.spawn_point_tool.shift_right_click"));
    }
}
