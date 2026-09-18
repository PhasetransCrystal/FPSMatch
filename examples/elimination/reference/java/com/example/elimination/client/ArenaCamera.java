package com.example.elimination.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.ptcrys.fpsmatch.common.camera.SequenceClock;
import net.ptcrys.fpsmatch.common.client.camera.*;
import net.ptcrys.fpsmatch.common.client.camera.rig.FixedRig;

import java.util.List;

// 只从物理客户端的主线程调用。
public final class ArenaCamera {
    private static CameraSession session;

    public static boolean playIntro(Vec3 arenaCenter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        var level = mc.level;
        CameraPose pose = new CameraPose(
                arenaCenter.add(8, 5, 8), 0, 0, 0, 70
        ).lookAt(arenaCenter);
        CameraSequence sequence = new CameraSequence(List.of(
                new CameraSequence.Shot(60, new FixedRig(pose),
                        CameraSequence.Transition.CUT, 0)
        ));
        CameraSession acquired = CameraDirector.playSequence(
                "elimination_addon:arena_intro",
                CameraDirector.CINEMATIC_PRIORITY,
                sequence, CameraPolicy.CINEMATIC, new SequenceClock(),
                () -> mc.level == level && mc.player != null,
                reason -> {
                    // 在这里释放本次场景独占的字幕、音效等资源。
                    // 不要清除其他场景的全局状态。
                }
        );
        if (acquired == null) return false;
        session = acquired;
        return true;
    }

    public static boolean isPlaying() {
        return session != null && session.isActive();
    }

    public static void stop() {
        CameraSession previous = session;
        session = null;
        if (previous != null) previous.close();
    }
}
