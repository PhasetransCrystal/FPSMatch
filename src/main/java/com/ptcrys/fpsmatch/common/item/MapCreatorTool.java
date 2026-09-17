package com.ptcrys.fpsmatch.common.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.item.tool.CreatorToolItem;
import com.ptcrys.fpsmatch.common.item.tool.ToolInteractionAction;
import com.ptcrys.fpsmatch.common.item.tool.WorldToolItem;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickActionContext;
import com.ptcrys.fpsmatch.common.packet.AddAreaDataS2CPacket;
import com.ptcrys.fpsmatch.common.packet.OpenMapCreatorToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.RemoveDebugDataByPrefixS2CPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.util.PreviewColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MapCreatorTool extends CreatorToolItem implements WorldToolItem {

    public static final String BLOCK_POS_TAG_1 = "BlockPos1";
    public static final String BLOCK_POS_TAG_2 = "BlockPos2";
    public static final String DRAFT_MAP_NAME_TAG = "DraftMapName";
    public static final String SELECTED_MAP_TAG = "SelectedMapName";
    private static final String HELD_PREVIEW_STATE_TAG = "HeldMapCreatorPreviewState";
    private static final int HELD_PREVIEW_REFRESH_INTERVAL = 10;

    public MapCreatorTool(Properties pProperties) {
        super(pProperties.stacksTo(1));
    }

    @Override
    protected void onLeftClick(ClickActionContext context) {}

    @Override
    protected void onRightClick(ClickActionContext context) {}

    @Override
    public void handleWorldInteraction(ServerPlayer player, ItemStack stack, ToolInteractionAction action, @Nullable BlockPos clickedPos) {
        switch (action) {
            case LEFT_CLICK_BLOCK -> {
                if (clickedPos == null) {
                    return;
                }
                setBlockPos(stack, BLOCK_POS_TAG_1, clickedPos);
                player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.set_pos1", formatPos(clickedPos))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            case RIGHT_CLICK_BLOCK -> {
                if (clickedPos == null) {
                    return;
                }
                setBlockPos(stack, BLOCK_POS_TAG_2, clickedPos);
                player.displayClientMessage(Component.translatable("message.fpsm.map_creator_tool.set_pos2", formatPos(clickedPos))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            case CTRL_RIGHT_CLICK -> FPSMatch.sendToPlayer(player,
                    OpenMapCreatorToolScreenS2CPacket.fromStack(stack, FPSMCore.getInstance().getGameTypes()));
        }
    }

    public void syncHeldPreview(ServerPlayer player, ItemStack stack) {
        BlockPos pos1 = getBlockPos(stack, BLOCK_POS_TAG_1);
        BlockPos pos2 = getBlockPos(stack, BLOCK_POS_TAG_2);
        if (pos1 == null || pos2 == null) {
            clearHeldPreview(player);
            return;
        }

        String selectedType = getSelectedType(stack).trim();
        String draftMapName = getDraftMapName(stack).trim();
        String signature = (selectedType.isBlank() ? "draft" : selectedType) + "|" + draftMapName + "|" + pos1.asLong() + "|" + pos2.asLong();
        String previousSignature = player.getPersistentData().getString(HELD_PREVIEW_STATE_TAG);
        if (signature.equals(previousSignature) && player.tickCount % HELD_PREVIEW_REFRESH_INTERVAL != 0) {
            return;
        }

        FPSMatch.sendToPlayer(player, new AddAreaDataS2CPacket(
                getHeldPreviewKey(player),
                Component.literal(draftMapName.isEmpty() ? "Draft Map" : draftMapName),
                PreviewColorUtil.getMapPreviewColor(selectedType.isBlank() ? "draft" : selectedType),
                new AreaData(pos1, pos2)));
        player.getPersistentData().putString(HELD_PREVIEW_STATE_TAG, signature);
    }

    public static void clearHeldPreview(ServerPlayer player) {
        if (!player.getPersistentData().contains(HELD_PREVIEW_STATE_TAG)) {
            return;
        }

        FPSMatch.sendToPlayer(player, new RemoveDebugDataByPrefixS2CPacket(getHeldPreviewPrefix(player)));
        player.getPersistentData().remove(HELD_PREVIEW_STATE_TAG);
    }

    private static String getHeldPreviewPrefix(ServerPlayer player) {
        return "held_tool_preview:map_creator:" + player.getUUID() + ":";
    }

    private static String getHeldPreviewKey(ServerPlayer player) {
        return getHeldPreviewPrefix(player) + "area";
    }

    public static void setSelectedType(ItemStack stack, String selectedType) {
        stack.getOrCreateTag().putString(TYPE_TAG, selectedType == null ? "" : selectedType);
    }

    public static String getSelectedType(ItemStack stack) {
        return stack.getOrCreateTag().getString(TYPE_TAG);
    }

    public static void setDraftMapName(ItemStack stack, String draftMapName) {
        stack.getOrCreateTag().putString(DRAFT_MAP_NAME_TAG, draftMapName == null ? "" : draftMapName);
    }

    public static String getDraftMapName(ItemStack stack) {
        return stack.getOrCreateTag().getString(DRAFT_MAP_NAME_TAG);
    }

    public static void setSelectedMap(ItemStack stack, String selectedMap) {
        stack.getOrCreateTag().putString(SELECTED_MAP_TAG, selectedMap == null ? "" : selectedMap);
    }

    public static String getSelectedMap(ItemStack stack) {
        return stack.getOrCreateTag().getString(SELECTED_MAP_TAG);
    }

    public static void setBlockPos(ItemStack stack, String tag, @Nullable BlockPos pos) {
        CompoundTag compoundTag = stack.getOrCreateTag();
        if (pos == null) {
            compoundTag.remove(tag);
            return;
        }
        compoundTag.putLong(tag, pos.asLong());
    }

    public static @Nullable BlockPos getBlockPos(ItemStack stack, String tag) {
        CompoundTag compoundTag = stack.getOrCreateTag();
        if (!compoundTag.contains(tag, Tag.TAG_LONG)) {
            return null;
        }
        return BlockPos.of(compoundTag.getLong(tag));
    }

    public static String formatPos(@Nullable BlockPos pos) {
        return pos == null ? "-" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @Override
    public void appendHoverText(ItemStack pStack, @Nullable Level pLevel, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        super.appendHoverText(pStack, pLevel, pTooltipComponents, pIsAdvanced);
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.separator").withStyle(ChatFormatting.GOLD));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.selected.type")
                .append(": ")
                .append(Component.literal(getSelectedType(pStack).isBlank() ? Component.translatable("tooltip.fpsm.none").getString() : getSelectedType(pStack)).withStyle(ChatFormatting.AQUA)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.selected.map")
                .append(": ")
                .append(Component.literal(getSelectedMap(pStack).isBlank() ? Component.translatable("tooltip.fpsm.none").getString() : getSelectedMap(pStack)).withStyle(ChatFormatting.AQUA)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.selected.name")
                .append(": ")
                .append(Component.literal(getDraftMapName(pStack).isBlank() ? Component.translatable("tooltip.fpsm.none").getString() : getDraftMapName(pStack)).withStyle(ChatFormatting.GREEN)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.selected.pos1")
                .append(": ")
                .append(Component.literal(formatPos(getBlockPos(pStack, BLOCK_POS_TAG_1))).withStyle(ChatFormatting.YELLOW)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.selected.pos2")
                .append(": ")
                .append(Component.literal(formatPos(getBlockPos(pStack, BLOCK_POS_TAG_2))).withStyle(ChatFormatting.YELLOW)));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.separator").withStyle(ChatFormatting.GOLD));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.left_click"));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.right_click"));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.map_creator.ctrl_right_click"));
    }
}
