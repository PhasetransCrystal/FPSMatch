package com.ptcrys.fpsmatch.compat.tacz.client.test;

import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.ViewportEvent;

public final class TaczSpecScreenShake {
    public static boolean ENABLE = true;
    private static final float FREQ = 8.0f;   // 震动频率(Hz)
    private static final float DAMP = 6.0f;   // 衰减系数
    private static final float MAX_AMPLITUDE_DEG = 0.18f; // 最大振幅(度)
    private static final float ADS_MIN_SCALE = 0.40f; // 开镜时振幅比例
    private static final long RPM_REF_MS = 120L;  // 连射间隔参考值

    private static float amplitudeDeg = 0.0f;
    private static long startMs = -1L;
    private static long lastKickMs = -1L;

    private TaczSpecScreenShake() {}

    public static void kick(float baseDeg) {
        if (!ENABLE) return;
        long now = System.currentTimeMillis();

        // 计算开镜缩放比例
        float adsScale = calculateAdsScale();
        // 计算射速缩放比例
        long dt = lastKickMs < 0 ? Long.MAX_VALUE : (now - lastKickMs);
        float rpmScale = Mth.clamp(dt / (float) RPM_REF_MS, 0.4f, 1.0f);

        // 计算最终振幅
        float scaled = baseDeg * adsScale * rpmScale;
        amplitudeDeg = Math.max(amplitudeDeg * 0.5f, Math.min(scaled, MAX_AMPLITUDE_DEG));
        
        startMs = now;
        lastKickMs = now;
    }

    public static void handleCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (com.ptcrys.fpsmatch.common.client.camera.CameraDirector.policy().suppressViewEffects()) return;
        if (!ENABLE) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isSpectator()) return;

        float amplitude = amplitudeDeg;
        long startTime = startMs;
        if (amplitude <= 1e-3f || startTime < 0) return;

        float t = (System.currentTimeMillis() - startTime) / 1000.0f;
        float env = (float) Math.exp(-DAMP * t);
        if (env < 1e-3f) {
            amplitudeDeg = 0.0f;
            startMs = -1L;
            return;
        }

        // 计算震动角度
        double omega = 2.0 * Math.PI * FREQ;
        event.setPitch(event.getPitch() + (float)(amplitude * env * Math.sin(omega * t)));
        event.setYaw(event.getYaw() + (float)(amplitude * 0.35f * env * Math.cos(omega * t)));
        event.setRoll(event.getRoll() + (float)(amplitude * 0.25f * env * Math.sin(omega * t)));
    }

    private static float calculateAdsScale() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.getCameraEntity() instanceof LivingEntity le)) return 1.0f;
            IGunOperator op = IGunOperator.fromLivingEntity(le);
            float adsProgress = Mth.clamp(op.getSynAimingProgress(), 0.0f, 1.0f);
            return ADS_MIN_SCALE + (1.0f - ADS_MIN_SCALE) * (1.0f - adsProgress);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }
}