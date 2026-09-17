package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.item.tool.EditToolItem;
import com.ptcrys.fpsmatch.common.item.tool.FPSMToolItem;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickAction;

import java.util.function.Supplier;

public class EditToolClickC2SPacket {

    private final ClickAction action;
    private final boolean isShiftKeyDown;

    public EditToolClickC2SPacket(ClickAction action,
                                  boolean isShiftKeyDown) {
        this.action = action;
        this.isShiftKeyDown = isShiftKeyDown;
    }

    public static void encode(EditToolClickC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeBoolean(msg.isShiftKeyDown);
    }

    public static EditToolClickC2SPacket decode(FriendlyByteBuf buf) {
        return new EditToolClickC2SPacket(
                buf.readEnum(ClickAction.class),
                buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ItemStack item = player.getMainHandItem();

            if (!(item.getItem() instanceof FPSMToolItem tool)) return;

            int clickCount = tool.getIntTag(item, EditToolItem.DOUBLE_CLICK_COUNT_TAG) + 1;

            boolean isDoubleClicked = clickCount >= 2;
            if (isDoubleClicked) {
                tool.setIntTag(item, EditToolItem.DOUBLE_CLICK_COUNT_TAG, 0);
            } else {
                tool.setIntTag(item, EditToolItem.DOUBLE_CLICK_COUNT_TAG, clickCount);
            }

            tool.handleClick(item, player, isDoubleClicked, isShiftKeyDown, action);
        });
        ctx.get().setPacketHandled(true);
    }
}
