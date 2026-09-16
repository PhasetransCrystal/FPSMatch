package com.ptcrys.fpsmatch.common.client.net;

import com.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import com.ptcrys.fpsmatch.common.client.spec.SpectateState;
import com.ptcrys.fpsmatch.common.client.spec.SpectateTarget;
import com.ptcrys.fpsmatch.common.client.spec.SpectatorCameraController;
import com.ptcrys.fpsmatch.common.packet.spec.SpectatorTargetS2CPacket;

public final class SpectatorTargetClientHandler {
    private SpectatorTargetClientHandler() {}

    public static void handle(SpectatorTargetS2CPacket packet) {
        // 同目标重复包(服务端周期性重发)不覆盖玩家已拖动的环绕角度，否则会"回弹/锁死"
        boolean orbitMode = packet.mode() == SpectateMode.C4_ORBIT
                || packet.mode() == SpectateMode.DEATH_SPOT;
        SpectateTarget current = SpectateState.getTarget();
        boolean sameTarget = current != null
                && current.mode() == packet.mode()
                && current.entityId() == packet.entityId()
                && current.anchor().distanceToSqr(packet.anchor()) < 4.0;
        if (!sameTarget || !orbitMode) {
            // 新目标/切换目标：采用服务端提供的初始姿态
            SpectatorCameraController.setAngles(packet.yaw(), packet.pitch());
        }
        packet.applyClient();
        com.ptcrys.fpsmatch.common.client.camera.CameraDirector.refresh();
    }
}
