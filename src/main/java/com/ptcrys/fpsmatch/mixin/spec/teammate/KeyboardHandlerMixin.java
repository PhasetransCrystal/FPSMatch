package com.ptcrys.fpsmatch.mixin.spec.teammate;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.spec.SpecKeyHandler;
import com.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import com.ptcrys.fpsmatch.common.client.spec.SpectateState;
import com.ptcrys.fpsmatch.common.client.spec.SpectatorSwitchDirection;
import com.ptcrys.fpsmatch.common.packet.spec.SpectatorSwitchC2SPacket;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restricts in-world keys while restricted spectator is active.
 * Open GUIs and the tactical map key must remain usable.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress(JIIII)V", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long window, int keyCode, int scanCode, int action, int modifiers, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && com.ptcrys.fpsmatch.common.client.camera.CameraDirector.hasSession()) {
            var policy = com.ptcrys.fpsmatch.common.client.camera.CameraDirector.policy();
            var options = mc.options;
            boolean blocked = (policy.blockMovement() && matches(keyCode, scanCode, options.keyUp, options.keyDown,
                    options.keyLeft, options.keyRight, options.keyJump, options.keyShift, options.keySprint)) || (policy.blockInteraction() &&
                            matches(keyCode, scanCode, options.keyAttack, options.keyUse,
                                    options.keyPickItem, options.keyDrop, options.keySwapOffhand)) ||
                    (!policy.allowSpectatorSwitch() && SpecKeyHandler.switchKeyMatches(keyCode, scanCode)) || (policy.lockPerspective() && options.keyTogglePerspective.matches(keyCode, scanCode));
            // Release events and UI shortcuts must still reach vanilla.
            if (action != GLFW.GLFW_RELEASE && blocked && keyCode != GLFW.GLFW_KEY_ESCAPE && !options.keyChat.matches(keyCode, scanCode) && !options.keyCommand.matches(keyCode, scanCode)) ci.cancel();
            return;
        }
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        if (!SpectateState.isRestricted() || mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            return;
        }
        // Let pause menu, map select, chat, inventory, and other screens handle keys.
        if (mc.screen != null) {
            return;
        }
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return;
        }

        boolean allowEscape = keyCode == GLFW.GLFW_KEY_ESCAPE;
        boolean allowTeamSwitch = SpecKeyHandler.switchKeyMatches(keyCode, scanCode);
        // Chat is a UI action, not a spectator-world action. Check the
        // configured mapping instead of hard-coding T so users who rebound
        // chat can still open it while attached to a teammate/C4/death spot.
        boolean allowChat = mc.options.keyChat.matches(keyCode, scanCode);

        if (keyCode == GLFW.GLFW_KEY_SPACE && action == GLFW.GLFW_PRESS && (SpectateState.get() == SpectateMode.TEAMMATE || SpectateState.get() == SpectateMode.ATTACH)) {
            FPSMatch.sendToServer(new SpectatorSwitchC2SPacket(SpectatorSwitchDirection.NEXT));
            ci.cancel();
            return;
        }

        if (!(allowEscape || allowTeamSwitch || allowChat)) {
            ci.cancel();
        }
    }

    private static boolean matches(int keyCode, int scanCode, net.minecraft.client.KeyMapping... keys) {
        for (var key : keys) if (key.matches(keyCode, scanCode)) return true;
        return false;
    }
}
