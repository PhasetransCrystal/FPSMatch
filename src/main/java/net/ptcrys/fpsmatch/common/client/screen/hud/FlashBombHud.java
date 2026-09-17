package net.ptcrys.fpsmatch.common.client.screen.hud;

import net.ptcrys.fpsmatch.common.effect.FPSMEffectRegister;
import net.ptcrys.fpsmatch.common.effect.FlashBlindnessMobEffect;
import net.ptcrys.fpsmatch.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

public class FlashBombHud implements IGuiOverlay {

    public static final FlashBombHud INSTANCE = new FlashBombHud();
    public final Minecraft minecraft;

    public FlashBombHud() {
        minecraft = Minecraft.getInstance();
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        LocalPlayer player = minecraft.player;
        if (player != null && player.hasEffect(FPSMEffectRegister.FLASH_BLINDNESS.get())) {
            MobEffectInstance effectInstance = player.getEffect(FPSMEffectRegister.FLASH_BLINDNESS.get());
            if (effectInstance != null && effectInstance.getEffect() instanceof FlashBlindnessMobEffect flashBlindnessMobEffect) {
                float fullBlindnessTime = flashBlindnessMobEffect.getFullBlindnessTime();
                if (fullBlindnessTime > 0) {
                    int colorWithAlpha = RenderUtil.color(255, 255, 255, 255);
                    guiGraphics.fill(0, 0, screenWidth, screenHeight, colorWithAlpha);
                } else {
                    float ticker = flashBlindnessMobEffect.getTicker();
                    if (ticker > 0) {
                        int alpha = (int) (ticker / flashBlindnessMobEffect.getTotalBlindnessTime() * 255);
                        int colorWithAlpha = RenderUtil.color(255, 255, 255, alpha);
                        guiGraphics.fill(0, 0, screenWidth, screenHeight, colorWithAlpha);
                    }
                }
            }
        }
    }
}
