package net.ptcrys.fpsmatch.editor.common.domain.spatial.examples;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.types.values.ActivatedValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueSnapshot;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;
import net.ptcrys.fpsmatch.editor.common.domain.spatial.MapArea;
import net.ptcrys.fpsmatch.editor.common.domain.spatial.MapPoint;
import net.ptcrys.fpsmatch.editor.common.domain.spatial.SpatialValues;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;

/**
 * Public-JAR consumer of isolated Q29 map definitions. Checks spatial fragments only, using no
 * game classes, legacy constructors, original save, or runtime installation.
 */
public final class SpatialRealFixtureSmoke {
    private SpatialRealFixtureSmoke() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "expected isolated Q29 fixture directory");
        Path fixtures = Path.of(args[0]).toAbsolutePath().normalize();
        ValueTypeCatalog first = ValueTypeCatalog.activate(registry());
        ValueTypeCatalog fresh = ValueTypeCatalog.activate(registry());
        var point = typed(first, SpatialValues.POINT, MapPoint.class);
        var freshPoint = typed(fresh, SpatialValues.POINT, MapPoint.class);
        var area = typed(first, SpatialValues.AREA, MapArea.class);
        var freshArea = typed(fresh, SpatialValues.AREA, MapArea.class);
        int[] cs = verifyMap(fixtures.resolve("CSGameMaps/test.json"), point, freshPoint, area, freshArea);
        int[] dm = verifyMap(fixtures.resolve("CSDeathMatchMaps/test.json"), point, freshPoint, area, freshArea);
        require(Arrays.equals(cs, new int[] {11, 2, 6}), "unexpected actual CS fragment counts");
        require(Arrays.equals(dm, new int[] {5, 1, 2}), "unexpected actual DM fragment counts");
        System.out.println("SPATIAL_REAL_FIXTURE_PASS CS points=11 areas=2 widePitch=6 DM points=5 areas=1 widePitch=2");
    }

    private static ExtensionRegistry registry() {
        var builder = new ExtensionRegistryBuilder(new ApiVersion(1, 0), Contribution.Side.COMMON);
        SpatialValues.register(builder, "Q33-consumer");
        ExtensionRegistry registry = builder.freeze();
        require(registry.metadata().problems().isEmpty(), "registration problems");
        require(registry.metadata().modules().get(SpatialValues.MODULE).buildVersion().equals("Q33-consumer"),
                "actual module build version not retained");
        return registry;
    }

    private static int[] verifyMap(Path input, ActivatedValueType<MapPoint> point,
            ActivatedValueType<MapPoint> freshPoint, ActivatedValueType<MapArea> area,
            ActivatedValueType<MapArea> freshArea) throws Exception {
        byte[] before = Files.readAllBytes(input);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(before)).toString();
        JsonObject root = ExactJson.parse(text).toJson().getAsJsonObject().getAsJsonObject("data");
        EditorId dimension = EditorId.parse(root.get("serverLevel").getAsString());
        verifyArea(root.getAsJsonObject("mapArea"), dimension, area, freshArea);
        int areas = 1;
        int points = 0;
        int widePitch = 0;
        JsonObject capabilities = root.getAsJsonObject("capabilities");
        JsonElement ending = capabilities.get("GameEndTeleportCapability");
        if (ending != null && ending.isJsonObject()) {
            MapPoint value = verifyPoint(ending.getAsJsonObject(), point, freshPoint);
            points++;
            if (value.pitch().abs().compareTo(BigDecimal.valueOf(90)) > 0) widePitch++;
        }
        JsonObject demolition = capabilities.getAsJsonObject("DemolitionModeCapability");
        if (demolition != null) {
            for (JsonElement bomb : demolition.getAsJsonArray("bombAreas")) {
                verifyArea(bomb.getAsJsonObject(), dimension, area, freshArea);
                areas++;
            }
        }
        for (var team : root.getAsJsonObject("teams").entrySet()) {
            JsonArray spawns = team.getValue().getAsJsonObject().getAsJsonObject("capabilities")
                    .getAsJsonArray("SpawnPointCapability");
            for (JsonElement spawn : spawns) {
                MapPoint value = verifyPoint(spawn.getAsJsonObject(), point, freshPoint);
                points++;
                if (value.pitch().abs().compareTo(BigDecimal.valueOf(90)) > 0) widePitch++;
            }
        }
        require(Arrays.equals(before, Files.readAllBytes(input)), "fixture bytes changed");
        return new int[] {points, areas, widePitch};
    }

    private static MapPoint verifyPoint(JsonObject legacy, ActivatedValueType<MapPoint> type,
            ActivatedValueType<MapPoint> fresh) {
        JsonArray position = legacy.getAsJsonArray("Position");
        MapPoint point = new MapPoint(EditorId.parse(legacy.get("Dimension").getAsString()),
                position.get(0).getAsBigDecimal(), position.get(1).getAsBigDecimal(),
                position.get(2).getAsBigDecimal(), legacy.get("Yaw").getAsBigDecimal(),
                legacy.get("Pitch").getAsBigDecimal());
        ValueSnapshot<MapPoint> captured = type.capture(point);
        verifyMetadata(captured, SpatialValues.POINT);
        byte[] original = captured.encoding();
        byte[] supplied = original.clone();
        ValueSnapshot<MapPoint> reopened = fresh.decode(captured.codecId(), captured.schemaVersion(), supplied);
        require(Arrays.equals(original, supplied), "decode mutated caller bytes");
        require(!fresh.owns(captured) && fresh.owns(reopened), "catalog ownership not isolated");
        require(Arrays.equals(original, reopened.encoding()), "point canonical encoding changed on reopen");
        MapPoint actual = reopened.content();
        require(point.dimension().equals(actual.dimension()), "dimension changed");
        List<BigDecimal> expected = List.of(point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        List<BigDecimal> result = List.of(actual.x(), actual.y(), actual.z(), actual.yaw(), actual.pitch());
        for (int i = 0; i < expected.size(); i++) {
            require(expected.get(i).compareTo(result.get(i)) == 0, "coordinate or orientation changed");
        }
        byte[] mutableGetter = captured.encoding();
        mutableGetter[0] ^= 1;
        require(Arrays.equals(original, captured.encoding()), "snapshot encoding not isolated");
        return actual;
    }

    private static void verifyArea(JsonObject legacy, EditorId dimension,
            ActivatedValueType<MapArea> type, ActivatedValueType<MapArea> fresh) {
        MapArea value = new MapArea(dimension, new BoxCorners(corner(legacy.getAsJsonArray("Position1")),
                corner(legacy.getAsJsonArray("Position2"))));
        ValueSnapshot<MapArea> captured = type.capture(value);
        verifyMetadata(captured, SpatialValues.AREA);
        byte[] supplied = captured.encoding();
        ValueSnapshot<MapArea> reopened = fresh.decode(captured.codecId(), captured.schemaVersion(), supplied);
        require(value.equals(reopened.content()), "ordered corners changed");
        require(Arrays.equals(captured.encoding(), supplied), "area decoder mutated input");
        require(Arrays.equals(captured.encoding(), reopened.encoding()), "area canonical encoding changed");
    }

    private static IntPosition corner(JsonArray coordinates) {
        return new IntPosition(coordinates.get(0).getAsBigDecimal().intValueExact(),
                coordinates.get(1).getAsBigDecimal().intValueExact(),
                coordinates.get(2).getAsBigDecimal().intValueExact());
    }

    private static void verifyMetadata(ValueSnapshot<?> snapshot, EditorId type) {
        require(snapshot.owner().equals(SpatialValues.MODULE), "snapshot owner not actual provider");
        require(snapshot.typeId().equals(type), "wrong registered value type");
        require(snapshot.dependencies().contributions().equals(Set.of(new ContributionKey(ValueTypes.KIND.id(), type))),
                "missing actual nested contribution");
        require(snapshot.dependencies().resources().isEmpty() && snapshot.dependencies().documents().isEmpty(),
                "spurious spatial dependency");
    }

    private static <T> ActivatedValueType<T> typed(ValueTypeCatalog catalog, EditorId id, Class<T> expected) {
        ActivatedValueType<?> type = catalog.find(id).orElseThrow();
        require(type.valueType() == expected, "registered Java class differs");
        return checkedCast(type);
    }

    @SuppressWarnings("unchecked")
    private static <T> ActivatedValueType<T> checkedCast(ActivatedValueType<?> type) {
        // The sole caller checked the captured runtime class against the exact requested class.
        return (ActivatedValueType<T>) type;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
