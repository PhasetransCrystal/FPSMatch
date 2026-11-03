package net.ptcrys.fpsmatch.editor.common.domain.spatial;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;

import java.util.Objects;

/** A dimension requirement and two immutable corners in their supplied order. */
public record MapArea(EditorId dimension, BoxCorners corners) {

    public MapArea {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(corners, "corners");
        SpatialCodec.freeze(SpatialCodec.areaJson(dimension, corners));
    }
}
