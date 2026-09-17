package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.client.event.FirstPersonRenderGunEvent;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.functional.MuzzleFlashRender;
import com.tacz.guns.util.math.SecondOrderDynamics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FirstPersonRenderGunEvent.class, remap = false)
public abstract class FirstPersonRenderGunEventMixin {

    @Final
    @Shadow
    private static SecondOrderDynamics JUMPING_DYNAMICS;

    @Shadow
    private static float jumpingSwayProgress;

    @Shadow
    private static boolean lastOnGround;

    @Shadow
    private static long jumpingTimeStamp;

    @Shadow
    private static long shootTimeStamp;

    @Inject(method = "onGunFire(Lcom/tacz/guns/api/event/common/GunFireEvent;)V", at = @At("HEAD"))
    private static void fpsmatch$syncSpectatorMuzzleFlash(GunFireEvent event, CallbackInfo ci) {
        if (!event.getLogicalSide().isClient()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer local = mc.player;
        if (local == null || !local.isSpectator()) {
            return;
        }
        Entity cam = mc.getCameraEntity();
        if (cam == null || cam == local) {
            return;
        }
        if (event.getShooter() == cam) {
            shootTimeStamp = System.currentTimeMillis();
            MuzzleFlashRender.onShoot();
        }
    }

    @Inject(method = "applyJumpingSway(Lcom/tacz/guns/client/model/BedrockGunModel;F)V", at = @At("HEAD"), cancellable = true)
    private static void fpsmatch$useSpectatedJumpingSway(BedrockGunModel model, float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer local = mc.player;
        if (local == null || !local.isSpectator()) {
            return;
        }
        Entity cam = mc.getCameraEntity();
        if (!(cam instanceof LivingEntity target) || target == local) {
            return;
        }
        long now = System.currentTimeMillis();
        if (jumpingTimeStamp == -1L) {
            jumpingTimeStamp = now;
        }
        float safePartial = partialTicks <= 0.0f ? 0.05f : partialTicks;
        double posY = Mth.lerp(safePartial, target.yOld, target.getY());
        float velocityY = (float) (posY - target.yOld) / safePartial;
        if (target.onGround()) {
            if (!lastOnGround) {
                jumpingSwayProgress = velocityY / -0.1f;
                if (jumpingSwayProgress > 1.0f) {
                    jumpingSwayProgress = 1.0f;
                }
                lastOnGround = true;
            } else if ((jumpingSwayProgress -= (float) (now - jumpingTimeStamp) / 150.0f) < 0.0f) {
                jumpingSwayProgress = 0.0f;
            }
        } else if (lastOnGround) {
            jumpingSwayProgress = velocityY / 0.42f;
            if (jumpingSwayProgress > 1.0f) {
                jumpingSwayProgress = 1.0f;
            }
            lastOnGround = false;
        } else if ((jumpingSwayProgress -= (float) (now - jumpingTimeStamp) / 300.0f) < 0.0f) {
            jumpingSwayProgress = 0.0f;
        }
        jumpingTimeStamp = now;
        float ySway = JUMPING_DYNAMICS.update(-2.0f * jumpingSwayProgress);
        BedrockPart rootNode = model.getRootNode();
        if (rootNode != null) {
            rootNode.offsetY += -ySway / 16.0f;
        }
        ci.cancel();
    }
}
