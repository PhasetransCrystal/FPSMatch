package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.common.shop.editor.ListenerModuleService;
import net.ptcrys.fpsmatch.common.shop.editor.ListenerModuleSnapshot.Definition;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ListenerModuleActionC2SPacket(long requestId, ShopEditorSnapshot.Target target, Action action, String revision, Definition draft) {

    public enum Action {
        LOAD,
        CREATE,
        UPDATE,
        DELETE
    }

    public static void encode(ListenerModuleActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.requestId);
        packet.target.write(buf);
        buf.writeEnum(packet.action);
        buf.writeUtf(packet.revision, 64);
        packet.draft.write(buf);
    }

    public static ListenerModuleActionC2SPacket decode(FriendlyByteBuf buf) {
        return new ListenerModuleActionC2SPacket(buf.readLong(), ShopEditorSnapshot.Target.read(buf), buf.readEnum(Action.class), buf.readUtf(64), Definition.read(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ListenerModuleService.execute(ctx.get().getSender(), this));
        ctx.get().setPacketHandled(true);
    }
}
