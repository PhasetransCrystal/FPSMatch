package net.ptcrys.fpsmatch.common.client.camera.rig;

import net.ptcrys.fpsmatch.common.client.camera.*;

public record FixedRig(CameraPose pose) implements CameraRig {

    @Override
    public CameraFrame sample(double ticks) {
        return CameraFrame.independent(pose);
    }
}
