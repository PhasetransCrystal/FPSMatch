package net.ptcrys.fpsmatch.editor.common.domain.spatial;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.math.BigDecimal;
import java.util.Objects;

/** Exact coordinates and orientation, without a world binding or angle clamping. */
public record MapPoint(EditorId dimension, BigDecimal x, BigDecimal y, BigDecimal z,
                       BigDecimal yaw, BigDecimal pitch) {

    public MapPoint {
        Objects.requireNonNull(dimension, "dimension");
        x = SpatialCodec.coordinate(x, "x");
        y = SpatialCodec.coordinate(y, "y");
        z = SpatialCodec.coordinate(z, "z");
        yaw = SpatialCodec.angle(yaw, "yaw");
        pitch = SpatialCodec.angle(pitch, "pitch");
        // Certify the entire canonical encoding now, so every legal instance can reopen.
        SpatialCodec.freeze(SpatialCodec.pointJson(dimension, x, y, z, yaw, pitch));
    }
}
