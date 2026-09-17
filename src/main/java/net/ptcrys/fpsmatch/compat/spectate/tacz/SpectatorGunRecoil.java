package net.ptcrys.fpsmatch.compat.spectate.tacz;

import net.ptcrys.fpsmatch.compat.spectate.SpectatorView;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.tacz.guns.api.entity.IGunOperator;

/**
 * Applies a local camera shake that mirrors the spectated player's recoil.
 * Registered by {@link net.ptcrys.fpsmatch.compat.tacz.TACZBootstrap} when TACZ is loaded.
 */
public final class SpectatorGunRecoil {

    public static boolean ENABLE = true;
    private static final float FREQ = 8.0f;
    private static final float DAMP = 6.0f;
    private static final float MAX_AMPLITUDE_DEG = 0.18f;
    private static final float ADS_MIN_SCALE = 0.4f;
    private static final long RPM_REF_MS = 120L;
    private static volatile float amplitudeDeg = 0.0f;
    private static volatile long startMs = -1L;
    private static volatile long lastKickMs = -1L;

    private SpectatorGunRecoil() {}

    public static void kick(float baseDeg) {
        if (!ENABLE) {
            return;
        }
        long now = System.currentTimeMillis();
        float ads = resolveAimingProgress();
        float adsScale = ADS_MIN_SCALE + 0.6f * (1.0f - ads);
        long dt = lastKickMs < 0L ? Long.MAX_VALUE : now - lastKickMs;
        float rpmScale = Mth.clamp((float) dt / RPM_REF_MS, 0.4f, 1.0f);
        float scaled = baseDeg * adsScale * rpmScale;
        scaled = Math.min(scaled, MAX_AMPLITUDE_DEG);
        amplitudeDeg = Math.max(amplitudeDeg * 0.5f, scaled);
        startMs = now;
        lastKickMs = now;
    }

    @SubscribeEvent
    public static void onAngles(ViewportEvent.ComputeCameraAngles e) {
        if (net.ptcrys.fpsmatch.common.client.camera.CameraDirector.policy().suppressViewEffects()) return;
        if (!ENABLE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !SpectatorView.isSpectatingOther(mc.player)) {
            return;
        }
        float amplitude = amplitudeDeg;
        long startTime = startMs;
        if (amplitude <= 0.001f || startTime < 0L) {
            return;
        }
        float t = (float) (System.currentTimeMillis() - startTime) / 1000.0f;
        float env = (float) Math.exp(-DAMP * t);
        if (env < 0.001f) {
            amplitudeDeg = 0.0f;
            startMs = -1L;
            return;
        }
        double omega = 2.0 * Math.PI * FREQ;
        float pitchKick = (float) ((double) (amplitude * env) * Math.sin(omega * t));
        float yawKick = (float) ((double) (amplitude * 0.35f * env) * Math.cos(omega * t));
        float rollKick = (float) ((double) (amplitude * 0.25f * env) * Math.sin(omega * t));
        e.setPitch(e.getPitch() + pitchKick);
        e.setYaw(e.getYaw() + yawKick);
        e.setRoll(e.getRoll() + rollKick);
    }

    private static float resolveAimingProgress() {
        try {
            Entity cam = Minecraft.getInstance().getCameraEntity();
            if (!(cam instanceof LivingEntity le)) {
                return 0.0f;
            }
            IGunOperator op = IGunOperator.fromLivingEntity(le);
            return Mth.clamp(op.getSynAimingProgress(), 0.0f, 1.0f);
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }
}
