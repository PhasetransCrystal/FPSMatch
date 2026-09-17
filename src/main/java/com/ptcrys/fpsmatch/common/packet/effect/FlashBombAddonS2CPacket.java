package com.ptcrys.fpsmatch.common.packet.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.effect.FPSMEffectRegister;
import com.ptcrys.fpsmatch.common.effect.FlashBlindnessMobEffect;

import java.util.function.Supplier;

public class FlashBombAddonS2CPacket {

    private final int fullBlindnessTime;
    private final int totalBlindnessTime;
    private final int ticker;

    public FlashBombAddonS2CPacket(int fullBlindnessTime, int totalBlindnessTime, int ticker) {
        this.fullBlindnessTime = fullBlindnessTime;
        this.totalBlindnessTime = totalBlindnessTime;
        this.ticker = ticker;
    }

    public static void encode(FlashBombAddonS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.fullBlindnessTime);
        buf.writeInt(packet.totalBlindnessTime);
        buf.writeInt(packet.ticker);
    }

    public static FlashBombAddonS2CPacket decode(FriendlyByteBuf buf) {
        return new FlashBombAddonS2CPacket(
                buf.readInt(),
                buf.readInt(),
                buf.readInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.hasEffect(FPSMEffectRegister.FLASH_BLINDNESS.get())) {
                MobEffectInstance effectInstance = player.getEffect(FPSMEffectRegister.FLASH_BLINDNESS.get());
                if (effectInstance != null && effectInstance.getEffect() instanceof FlashBlindnessMobEffect flashBlindnessMobEffect) {
                    flashBlindnessMobEffect.setFullBlindnessTime(fullBlindnessTime);
                    flashBlindnessMobEffect.setTotalBlindnessTime(totalBlindnessTime);
                    flashBlindnessMobEffect.setTicker(ticker);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
