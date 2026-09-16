package com.ptcrys.fpsmatch.mixin.spec.teammate;

import com.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import com.ptcrys.fpsmatch.common.client.spec.SpectateState;
import com.ptcrys.fpsmatch.common.client.spec.SpectatorCameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraRestrictedSpectatorMixin {
    @Inject(method = "setup", at = @At("RETURN"))
    private void fpsmatch$applyRestrictedCamera(net.minecraft.world.level.BlockGetter level, net.minecraft.world.entity.Entity entity, boolean detached, boolean thirdPerson, float partialTick, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null || !player.isSpectator()) return;
        SpectateMode mode = SpectateState.get();
        if (mode != SpectateMode.C4_ORBIT && mode != SpectateMode.DEATH_SPOT) return;
        // 位置与朝向全部由控制器统一计算(环绕锚点+墙体收近+平滑旋转)，此处不再额外覆盖
        SpectatorCameraController.applyToCamera((Camera) (Object) this, partialTick);
    }
}
