package net.ptcrys.fpsmatch.editor.common.domain.spatial;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Schema 1 strict area codec; corner order is content. */
public final class MapAreaType implements ObjectType<MapArea> {

    public MapAreaType() {}

    @Override
    public EditorId role() {
        return EditorId.parse("fpsmatch:area");
    }

    @Override
    public Class<MapArea> valueType() {
        return MapArea.class;
    }

    @Override
    public EditorId codecId() {
        return EditorId.parse("fpsmatch:map_area_json");
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
    public MapArea snapshot(MapArea value) {
        return Objects.requireNonNull(value, "value");
    }

    @Override
    public List<Problem> validate(MapArea value) {
        snapshot(value);
        return List.of();
    }

    @Override
    public DocumentDependencies dependencies(MapArea value) {
        snapshot(value);
        return SpatialCodec.EMPTY_DEPENDENCIES;
    }

    @Override
    public byte[] encode(MapArea value) {
        snapshot(value);
        return SpatialCodec.freeze(SpatialCodec.areaJson(value.dimension(), value.corners())).canonicalUtf8();
    }

    @Override
    public MapArea decode(int sourceSchema, byte[] encoding) {
        JsonObject root = SpatialCodec.object(SpatialCodec.read(sourceSchema, encoding), "",
                List.of("dimension", "corners"));
        EditorId dimension = SpatialCodec.dimension(root.get("dimension"));
        JsonArray corners = SpatialCodec.array(root.get("corners"), "/corners", 2);
        IntPosition first = SpatialCodec.corner(corners.get(0), "/corners/0");
        IntPosition second = SpatialCodec.corner(corners.get(1), "/corners/1");
        return new MapArea(dimension, new BoxCorners(first, second));
    }
}
