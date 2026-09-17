package com.ptcrys.fpsmatch.common.packet.spec;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import com.ptcrys.fpsmatch.common.client.spec.SpectateState;
import com.ptcrys.fpsmatch.common.client.spec.SpectateTarget;
import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import java.util.function.Supplier;

public record SpectatorTargetS2CPacket(SpectateMode mode, int entityId, Vec3 anchor, float yaw, float pitch, float orbitRadius) {

    public static void encode(SpectatorTargetS2CPacket p, FriendlyByteBuf b) {
        b.writeEnum(p.mode());
        b.writeVarInt(p.entityId());
        b.writeDouble(p.anchor().x);
        b.writeDouble(p.anchor().y);
        b.writeDouble(p.anchor().z);
        b.writeFloat(p.yaw());
        b.writeFloat(p.pitch());
        b.writeFloat(p.orbitRadius());
    }

    public static SpectatorTargetS2CPacket decode(FriendlyByteBuf b) {
        return new SpectatorTargetS2CPacket(b.readEnum(SpectateMode.class), b.readVarInt(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readFloat(), b.readFloat(), b.readFloat());
    }

    public void handle(Supplier<NetworkEvent.Context> c) {
        ClientPacketExecutor.execute(c, this);
    }

    public void applyClient() {
        SpectateState.setTarget(new SpectateTarget(mode, entityId, anchor, yaw, pitch, orbitRadius));
    }
}
