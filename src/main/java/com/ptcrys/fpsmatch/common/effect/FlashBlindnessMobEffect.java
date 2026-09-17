package com.ptcrys.fpsmatch.common.effect;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.effect.FlashBombAddonS2CPacket;
import com.ptcrys.fpsmatch.util.RenderUtil;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FlashBlindnessMobEffect extends MobEffect {

    private int fullBlindnessTime = 0;
    private int totalBlindnessTime = 0;
    private int ticker = 0;

    public FlashBlindnessMobEffect(MobEffectCategory pCategory) {
        super(pCategory, RenderUtil.color(255, 255, 255));
    }

    public int getFullBlindnessTime() {
        return fullBlindnessTime;
    }

    public void setFullBlindnessTime(int fullBlindnessTime) {
        this.fullBlindnessTime = fullBlindnessTime;
    }

    public int getTotalBlindnessTime() {
        return totalBlindnessTime;
    }

    public void setTotalBlindnessTime(int totalBlindnessTime) {
        this.totalBlindnessTime = totalBlindnessTime;
    }

    public void setTicker(int ticker) {
        this.ticker = ticker;
    }

    public int getTicker() {
        return ticker;
    }

    public void setTotalAndTicker(int totalBlindnessTime) {
        this.totalBlindnessTime = totalBlindnessTime;
        this.ticker = totalBlindnessTime;
    }

    @SubscribeEvent
    public static void onServerTickEvent(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (!event.player.level().isClientSide && event.player.hasEffect(FPSMEffectRegister.FLASH_BLINDNESS.get())) {
                MobEffectInstance effectInstance = event.player.getEffect(FPSMEffectRegister.FLASH_BLINDNESS.get());
                if (effectInstance != null && effectInstance.getEffect() instanceof FlashBlindnessMobEffect flashBlindnessMobEffect) {
                    int fullBlindnessTime = flashBlindnessMobEffect.getFullBlindnessTime();
                    if (fullBlindnessTime > 0) {
                        flashBlindnessMobEffect.setFullBlindnessTime(fullBlindnessTime - 1);
                        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) event.player), new FlashBombAddonS2CPacket(flashBlindnessMobEffect.getFullBlindnessTime(), flashBlindnessMobEffect.getTotalBlindnessTime(), flashBlindnessMobEffect.getTicker()));
                    } else {
                        int ticker = flashBlindnessMobEffect.getTicker();
                        if (ticker >= 1) {
                            flashBlindnessMobEffect.setTicker(ticker - 1);
                        }
                        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) event.player), new FlashBombAddonS2CPacket(flashBlindnessMobEffect.getFullBlindnessTime(), flashBlindnessMobEffect.getTotalBlindnessTime(), flashBlindnessMobEffect.getTicker()));
                        if (flashBlindnessMobEffect.getTicker() == 0) {
                            event.player.removeEffect(FPSMEffectRegister.FLASH_BLINDNESS.get());
                        }
                    }
                }
            }
        }
    }
}
