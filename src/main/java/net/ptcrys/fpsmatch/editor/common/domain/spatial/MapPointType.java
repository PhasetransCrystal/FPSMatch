package net.ptcrys.fpsmatch.editor.common.domain.spatial;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Schema 1 strict, synchronous and resource-free point codec. */
public final class MapPointType implements ObjectType<MapPoint> {

    public MapPointType() {}

    @Override
    public EditorId role() {
        return EditorId.parse("fpsmatch:point");
    }

    @Override
    public Class<MapPoint> valueType() {
        return MapPoint.class;
    }

    @Override
    public EditorId codecId() {
        return EditorId.parse("fpsmatch:map_point_json");
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Set<Integer> readableSchemas() {
        return Set.of(1);
    }

    @Override
    public MapPoint snapshot(MapPoint value) {
        return Objects.requireNonNull(value, "value");
    }

    @Override
    public List<Problem> validate(MapPoint value) {
        snapshot(value);
        return List.of();
    }

    @Override
    public DocumentDependencies dependencies(MapPoint value) {
        snapshot(value);
        return SpatialCodec.EMPTY_DEPENDENCIES;
    }

    @Override
    public byte[] encode(MapPoint value) {
        snapshot(value);
        return SpatialCodec.freeze(SpatialCodec.pointJson(value.dimension(), value.x(), value.y(),
                value.z(), value.yaw(), value.pitch())).canonicalUtf8();
    }

    @Override
    public MapPoint decode(int sourceSchema, byte[] encoding) {
        JsonObject root = SpatialCodec.object(SpatialCodec.read(sourceSchema, encoding), "",
                List.of("dimension", "position", "yaw", "pitch"));
        EditorId dimension = SpatialCodec.dimension(root.get("dimension"));
        JsonArray position = SpatialCodec.array(root.get("position"), "/position", 3);
        BigDecimal x = SpatialCodec.number(position.get(0), "/position/0", false);
        BigDecimal y = SpatialCodec.number(position.get(1), "/position/1", false);
        BigDecimal z = SpatialCodec.number(position.get(2), "/position/2", false);
        BigDecimal yaw = SpatialCodec.number(root.get("yaw"), "/yaw", true);
        BigDecimal pitch = SpatialCodec.number(root.get("pitch"), "/pitch", true);
        return new MapPoint(dimension, x, y, z, yaw, pitch);
    }
}
