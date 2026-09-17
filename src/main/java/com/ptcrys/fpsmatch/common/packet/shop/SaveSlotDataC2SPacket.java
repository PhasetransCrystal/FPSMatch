package com.ptcrys.fpsmatch.common.packet.shop;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerData;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.EditShopSlotMenu;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;

import java.util.function.Supplier;

public class SaveSlotDataC2SPacket {

    private int ammoCount;
    private int defaultCost;
    private int groupId;

    // 构造函数用于初始化从 ContainerData 获取的数据
    public SaveSlotDataC2SPacket(ContainerData data) {
        if (data.getCount() >= 3) {
            this.ammoCount = data.get(0);
            this.defaultCost = data.get(1);
            this.groupId = data.get(2);
        }
    }

    // 构造函数用于直接传递数据
    public SaveSlotDataC2SPacket(int ammoCount, int defaultCost, int groupId) {
        this.ammoCount = ammoCount;
        this.defaultCost = defaultCost;
        this.groupId = groupId;
    }

    // 序列化数据（发送数据时调用）
    public static void encode(SaveSlotDataC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.ammoCount);    // 发送 ammoCount
        buf.writeInt(packet.defaultCost);  // 发送 defaultCost
        buf.writeInt(packet.groupId);      // 发送 groupId
    }

    // 反序列化数据（接收数据时调用）
    public static SaveSlotDataC2SPacket decode(FriendlyByteBuf buf) {
        return new SaveSlotDataC2SPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    // 处理包的逻辑（在服务端执行）
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();  // 获取当前玩家
            if (player == null) {
                return;
            }
            EditShopSlotMenu.SaveResult result = player.containerMenu instanceof EditShopSlotMenu menu ? menu.trySaveData(player, ammoCount, defaultCost, groupId) : EditShopSlotMenu.SaveResult.INVALID_MENU;
            FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(
                    Component.translatable(result.translationKey()),
                    !result.success()));
        });
        ctx.get().setPacketHandled(true); // 标记数据包已处理
    }
}
