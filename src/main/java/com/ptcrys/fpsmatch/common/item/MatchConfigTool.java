package com.ptcrys.fpsmatch.common.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.item.tool.CreatorToolItem;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickActionContext;
import com.ptcrys.fpsmatch.common.packet.OpenMatchConfigToolScreenS2CPacket;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MatchConfigTool extends CreatorToolItem {

    public MatchConfigTool(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    protected void onLeftClick(ClickActionContext context) {}

    @Override
    protected void onRightClick(ClickActionContext context) {
        ServerPlayer player = context.player();
        ItemStack stack = context.stack();
        FPSMatch.sendToPlayer(player, OpenMatchConfigToolScreenS2CPacket.fromStack(player, stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.fpsm.match_config_tool"));
        String selectedType = getSelectedType(stack);
        String selectedMap = getSelectedMap(stack);
        if (!selectedType.isBlank() && !selectedMap.isBlank()) {
            tooltip.add(Component.literal(selectedType + " / " + selectedMap));
        }
    }

    public static void setSelected(ItemStack stack, String selectedType, String selectedMap) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TYPE_TAG, selectedType == null ? "" : selectedType);
        tag.putString(MAP_TAG, selectedMap == null ? "" : selectedMap);
    }

    public static String getSelectedType(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        return tag.contains(TYPE_TAG) ? tag.getString(TYPE_TAG) : "";
    }

    public static String getSelectedMap(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        return tag.contains(MAP_TAG) ? tag.getString(MAP_TAG) : "";
    }
}
