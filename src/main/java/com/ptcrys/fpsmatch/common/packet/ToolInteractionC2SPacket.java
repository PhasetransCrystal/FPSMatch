package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.item.tool.ToolInteractionAction;
import com.ptcrys.fpsmatch.common.item.tool.WorldToolItem;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public record ToolInteractionC2SPacket(ToolInteractionAction action, @Nullable BlockPos clickedPos) {

    public static void encode(ToolInteractionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeBoolean(packet.clickedPos() != null);
        if (packet.clickedPos() != null) {
            buf.writeBlockPos(packet.clickedPos());
        }
    }

    public static ToolInteractionC2SPacket decode(FriendlyByteBuf buf) {
        ToolInteractionAction action = buf.readEnum(ToolInteractionAction.class);
        BlockPos pos = buf.readBoolean() ? buf.readBlockPos() : null;
        return new ToolInteractionC2SPacket(action, pos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }

            ItemStack stack = player.getMainHandItem();
            if (stack.getItem() instanceof WorldToolItem worldToolItem) {
                // 地图工具(建筑/编辑)只允许 OP(权限等级>=2) 使用，防止普通玩家篡改服务端地图/配置
                if (!player.hasPermissions(2)) {
                    player.displayClientMessage(
                            Component.literal("需要管理员(OP)权限才能使用地图工具"),
                            false);
                    return;
                }
                worldToolItem.handleWorldInteraction(player, stack, action, clickedPos);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
