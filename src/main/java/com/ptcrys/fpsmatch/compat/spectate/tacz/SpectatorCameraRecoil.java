package com.ptcrys.fpsmatch.compat.spectate.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.modifier.ParameterizedCache;
import com.tacz.guns.api.modifier.ParameterizedCachePair;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.modifier.custom.RecoilModifier;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import java.util.Optional;
import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ViewportEvent;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

/**
 * Applies TACZ camera recoil while spectating another player.
 */
public final class SpectatorCameraRecoil {
    private static PolynomialSplineFunction specPitchSplineFunction;
    private static PolynomialSplineFunction specYawSplineFunction;
    private static long specShootTimeStamp = -1L;
    private static double specXRotO = 0.0;
    private static double specYRotO = 0.0;

    private SpectatorCameraRecoil() {
    }

    public static boolean trigger(LivingEntity shooter) {
        if (shooter == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer local = mc.player;
        if (local == null || !local.isSpectator()) {
            return false;
        }
        Entity cam = mc.getCameraEntity();
        if (!(cam instanceof LivingEntity) || cam != shooter) {
            return false;
        }
        if (!SpectatorView.isSpectatingOther(local)) {
            return false;
        }
        return initSpectatorCameraRecoil(shooter);
    }

    public static void apply(ViewportEvent.ComputeCameraAngles event) {
        if (com.ptcrys.fpsmatch.common.client.camera.CameraDirector.policy().suppressViewEffects()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer local = mc.player;
        if (local == null || !local.isSpectator()) {
            return;
        }
        Entity cam = mc.getCameraEntity();
        if (!(cam instanceof LivingEntity) || cam == local) {
            return;
        }
        long timeTotal = System.currentTimeMillis() - specShootTimeStamp;
        if (specPitchSplineFunction != null && specPitchSplineFunction.isValidPoint((double) timeTotal)) {
            double value = specPitchSplineFunction.value((double) timeTotal);
            float delta = (float) (value - specXRotO);
            event.setPitch(event.getPitch() - delta);
            specXRotO = value;
        }
        if (specYawSplineFunction != null && specYawSplineFunction.isValidPoint((double) timeTotal)) {
            double value = specYawSplineFunction.value((double) timeTotal);
            float delta = (float) (value - specYRotO);
            event.setYaw(event.getYaw() - delta);
            specYRotO = value;
        }
    }

    private static boolean initSpectatorCameraRecoil(LivingEntity shooter) {
        ItemStack mainHandItem = shooter.getMainHandItem();
        Item item = mainHandItem.getItem();
        if (!(item instanceof IGun iGun)) {
            return false;
        }
        AttachmentCacheProperty cacheProperty = IGunOperator.fromLivingEntity(shooter).getCacheProperty();
        if (cacheProperty == null) {
            return false;
        }
        ResourceLocation gunId = iGun.getGunId(mainHandItem);
        Optional<ClientGunIndex> gunIndexOptional = TimelessAPI.getClientGunIndex(gunId);
        if (gunIndexOptional.isEmpty()) {
            return false;
        }
        GunData gunData = gunIndexOptional.get().getGunData();
        ParameterizedCachePair attachmentRecoilModifier = cacheProperty.getCache(RecoilModifier.ID);
        if (attachmentRecoilModifier == null) {
            return false;
        }
        float aimingProgress = Mth.clamp(IGunOperator.fromLivingEntity(shooter).getSynAimingProgress(), 0.0f, 1.0f);
        float zoom = iGun.getAimingZoom(mainHandItem);
        float aimingRecoilModifier = 1.0f - aimingProgress + aimingProgress / (float) Math.min(Math.sqrt(zoom), 1.5);
        if (!shooter.isInWater() && shooter.getPose() == Pose.SWIMMING) {
            aimingRecoilModifier *= gunData.getCrawlRecoilMultiplier();
        }
        specPitchSplineFunction = gunData.getRecoil()
                .genPitchSplineFunction((float) ((ParameterizedCache<?>) attachmentRecoilModifier.left()).eval(aimingRecoilModifier));
        specYawSplineFunction = gunData.getRecoil()
                .genYawSplineFunction((float) ((ParameterizedCache<?>) attachmentRecoilModifier.right()).eval(aimingRecoilModifier));
        specShootTimeStamp = System.currentTimeMillis();
        specXRotO = 0.0;
        specYRotO = 0.0;
        return true;
    }
}
