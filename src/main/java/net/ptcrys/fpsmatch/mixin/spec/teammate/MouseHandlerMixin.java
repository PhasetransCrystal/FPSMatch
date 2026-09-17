package net.ptcrys.fpsmatch.mixin.spec.teammate;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.camera.CameraDirector;
import net.ptcrys.fpsmatch.common.client.camera.CameraPolicy;
import net.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import net.ptcrys.fpsmatch.common.client.spec.SpectateState;
import net.ptcrys.fpsmatch.common.client.spec.SpectatorSwitchDirection;
import net.ptcrys.fpsmatch.common.packet.spec.SpectatorSwitchC2SPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.level.GameType;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restricts in-world mouse while restricted spectator is active.
 * Any open GUI (pause, map select, inventory, chat, etc.) must keep normal mouse events.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$handleRestrictedClick(long window, int button, int action, int modifiers, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (action == GLFW.GLFW_PRESS && mc.screen == null && CameraDirector.hasSession() && CameraDirector.policy().blockInteraction()) {
            ci.cancel();
            return;
        }
        if (mc.player == null || mc.gameMode == null || mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            return;
        }
        // Never swallow clicks while a screen is open (ESC pause, map select, shop, etc.).
        if (mc.screen != null || !SpectateState.isRestricted()) {
            return;
        }
        if (action != GLFW.GLFW_PRESS) {
            return;
        }
        if (SpectateState.get() == SpectateMode.TEAMMATE || SpectateState.get() == SpectateMode.ATTACH) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                FPSMatch.sendToServer(new SpectatorSwitchC2SPacket(SpectatorSwitchDirection.PREV));
                ci.cancel();
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                FPSMatch.sendToServer(new SpectatorSwitchC2SPacket(SpectatorSwitchDirection.NEXT));
                ci.cancel();
            }
        } else {
            ci.cancel();
        }
    }

    /**
     * 环绕(C4/死亡地)旋转：拦截 {@link MouseHandler#turnPlayer()}。
     * 该方法的 accumulatedDX/DY 是原版按“上一事件位置”算好的真实每事件位移，
     * 套用原版灵敏度公式 (sens*0.6+0.2)^3*8，与玩家正常视角转动的体感完全一致。
     * 用这些位移驱动环绕相机，并取消原版的 player.turn，避免重复/污染。
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$handleRestrictedTurn(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        CameraPolicy.LookInput look = CameraDirector.policy().look();
        if (look == CameraPolicy.LookInput.PLAYER) return;
        if (look == CameraPolicy.LookInput.LOCKED) {
            this.accumulatedDX = this.accumulatedDY = 0;
            ci.cancel();
            return;
        }

        double sens = mc.options.sensitivity().get();
        double base = sens * 0.6D + 0.2D;
        double factor = base * base * base * 8.0D;
        double dy = this.accumulatedDY;
        if (mc.options.invertYMouse().get()) {
            dy = -dy;
        }
        CameraDirector.turn(
                (float) (this.accumulatedDX * factor),
                (float) (dy * factor));

        // 取消原版转身；这里手动清零累积量，等价于原版尾部行为，避免重复使用
        this.accumulatedDX = 0.0D;
        this.accumulatedDY = 0.0D;
        ci.cancel();
    }

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;
}
