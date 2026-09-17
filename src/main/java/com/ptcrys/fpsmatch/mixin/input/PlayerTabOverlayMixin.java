package com.ptcrys.fpsmatch.mixin.input;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import com.ptcrys.fpsmatch.common.client.FPSMGameHudManager;
import com.ptcrys.fpsmatch.common.client.tab.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @Inject(at = { @At("HEAD") }, method = "render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V", cancellable = true)
    public void fpsMatch$render$Custom(GuiGraphics guiGraphics, int windowWidth, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        if (!FPSMGameHudManager.shouldRender()) {
            return;
        }

        List<PlayerInfo> playerInfoList = this.getPlayerInfos();
        if (playerInfoList == null) return;

        TabManager.getInstance().render(guiGraphics, windowWidth, playerInfoList, scoreboard, objective);
        ci.cancel();
    }

    @Shadow
    protected abstract List<PlayerInfo> getPlayerInfos();
}
