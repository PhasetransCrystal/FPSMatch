package com.ptcrys.fpsmatch.common.packet.shop;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.item.ShopConfigTool;

import java.util.function.Supplier;

/**
 * C2S 包：商店配置工具交互。
 * <p>
 * 处理客户端在商店配置工具界面中的选择变更和刷新操作。
 */
public record ShopConfigToolActionC2SPacket(
                                            Action action,
                                            String selectedType,
                                            String selectedMap) {

    private static final int ID_MAX_LENGTH = 128;

    public enum Action {
        SELECT,
        REFRESH
    }

    public static void encode(ShopConfigToolActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeUtf(packet.selectedType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.selectedMap(), ID_MAX_LENGTH);
    }

    public static ShopConfigToolActionC2SPacket decode(FriendlyByteBuf buf) {
        return new ShopConfigToolActionC2SPacket(
                buf.readEnum(Action.class),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof ShopConfigTool)) {
                return;
            }

            String type = selectedType() == null ? "" : selectedType();
            String map = selectedMap() == null ? "" : selectedMap();

            // 更新工具 NBT 并重新发送完整界面数据
            FPSMatch.sendToPlayer(player, OpenShopConfigToolScreenS2CPacket.of(player, stack, type, map));
        });
        ctx.get().setPacketHandled(true);
    }
}
