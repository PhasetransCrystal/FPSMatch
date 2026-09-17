package com.ptcrys.fpsmatch.mixin.spec.glow;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.common.MinecraftForge;

import com.ptcrys.fpsmatch.common.event.RequestSpectatorOutlinesEvent;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 禁用原版高亮玩家按键
 */
@Mixin(KeyMapping.class)
public abstract class MixinKeyMappingBlockSpectatorOutlines {

    @Inject(method = "setDown(Z)V", at = @At("HEAD"), cancellable = true)
    private void onSetDown(boolean isDown, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (player == null || gameMode == null) return;
        KeyMapping self = (KeyMapping) (Object) this;
        if ("key.spectatorOutlines".equals(self.getName())) {
            if (isDown) {
                if (FPSMConfig.Server.disableSpecGlowKey.get() || MinecraftForge.EVENT_BUS.post(new RequestSpectatorOutlinesEvent(player, gameMode))) {
                    ci.cancel();
                }
            }
        }
    }
}
