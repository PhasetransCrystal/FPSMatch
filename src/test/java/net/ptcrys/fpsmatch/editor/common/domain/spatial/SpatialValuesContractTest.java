package net.ptcrys.fpsmatch.editor.common.domain.spatial;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.testutil.MinecraftTranslationFormat;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.values.ActivatedValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueSnapshot;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;

/** Public-registry consumers of the frozen Q33 contract; no game or external fixtures. */
class SpatialValuesContractTest {
    private static final EditorId DIMENSION = EditorId.parse("thirdparty:unconnected_dimension");
    private static final String POINT_JSON = "{\"dimension\":\"thirdparty:unconnected_dimension\","
            + "\"position\":[1,2,3],\"yaw\":4,\"pitch\":5}";
    private static final String AREA_JSON = "{\"dimension\":\"thirdparty:unconnected_dimension\","
            + "\"corners\":[[1,2,3],[4,5,6]]}";
    private static final String MESSAGE_PREFIX = "fpsmatch.editor.spatial.";

    @Test
    void registrationPublishesActualCommonImplementationsWithExactModuleMetadata() {
        for (Contribution.Side side : Contribution.Side.values()) {
            ExtensionRegistryBuilder builder = builder(side);
            // Existing consumers may already have defined the public extension point.
            builder.define(ValueTypes.KIND);
            SpatialValues.register(builder, "q33-exact+build.007");
            ExtensionRegistry registry = builder.freeze();
            assertTrue(registry.metadata().problems().isEmpty());
            assertEquals(EditorId.parse("fpsmatch:editor_map_values"), SpatialValues.MODULE);
            assertEquals(EditorId.parse("fpsmatch:map_point"), SpatialValues.POINT);
            assertEquals(EditorId.parse("fpsmatch:map_area"), SpatialValues.AREA);
            assertEquals(Set.of(SpatialValues.MODULE), registry.metadata().modules().keySet());
            var module = registry.metadata().modules().get(SpatialValues.MODULE);
            assertEquals("q33-exact+build.007", module.buildVersion());
            assertEquals(new ApiVersion(1, 0), module.requiredApi());
            assertTrue(module.requiredModules().isEmpty());
            assertTrue(module.optionalModules().isEmpty());
            assertEquals(Set.of(SpatialValues.POINT, SpatialValues.AREA), registry.entries(ValueTypes.KIND).keySet());
            assertInstanceOf(MapPointType.class, registry.find(ValueTypes.KIND, SpatialValues.POINT).orElseThrow());
            assertInstanceOf(MapAreaType.class, registry.find(ValueTypes.KIND, SpatialValues.AREA).orElseThrow());
            assertEquals(2, registry.metadata().contributions().size());
            for (EditorId id : List.of(SpatialValues.POINT, SpatialValues.AREA)) {
                var contribution = registry.metadata().contributions().get(key(id));
                assertEquals(SpatialValues.MODULE, contribution.owner());
                assertEquals(Contribution.Side.COMMON, contribution.side());
                assertTrue(contribution.requiredContributions().isEmpty());
            }
            ValueTypeCatalog catalog = ValueTypeCatalog.activate(registry);
            assertTrue(catalog.problems().isEmpty());
            assertTrue(catalog.activationFailures().isEmpty());
            assertEquals(2, catalog.entries().size());
            assertDescriptor(catalog, SpatialValues.POINT, MapPoint.class, "fpsmatch:map_point_json");
            assertDescriptor(catalog, SpatialValues.AREA, MapArea.class, "fpsmatch:map_area_json");
        }
    }

    @Test
    void missingProvidersAndDuplicateRegistrationAreNotFabricatedAsSuccess() {
        ExtensionRegistryBuilder empty = builder(Contribution.Side.COMMON);
        empty.define(ValueTypes.KIND);
        assertTrue(ValueTypeCatalog.activate(empty.freeze()).find(SpatialValues.POINT).isEmpty());

        ExtensionRegistryBuilder duplicate = builder(Contribution.Side.COMMON);
        SpatialValues.register(duplicate, "one");
        SpatialValues.register(duplicate, "two");
        ExtensionRegistry registry = duplicate.freeze();
        assertTrue(registry.entries(ValueTypes.KIND).isEmpty());
        assertFalse(registry.metadata().problems().isEmpty());
        assertTrue(ValueTypeCatalog.activate(registry).entries().isEmpty());
    }

    @Test
    void pointCaptureAndReopenKeepExactValuesUnclampedAnglesAndRealOwnership() {
        ExtensionRegistry registry = registered();
        ValueTypeCatalog catalog = ValueTypeCatalog.activate(registry);
        ActivatedValueType<MapPoint> type = activated(catalog, SpatialValues.POINT, MapPoint.class);
        MapPoint original = new MapPoint(DIMENSION, decimal("9007199254740993.125"),
                decimal("-0.12345678901234567890123456789"), decimal("1E-1000000"),
                decimal("721.123456789"), decimal("-125.125"));
        ValueType<MapPoint> implementation = implementation(registry, SpatialValues.POINT, MapPoint.class);
        assertTrue(implementation.validate(original).isEmpty());
        assertNoDependencies(implementation.dependencies(original));
        ValueSnapshot<MapPoint> captured = type.capture(original);
        assertPoint(original, captured.content());
        assertEquals(SpatialValues.MODULE, captured.owner());
        assertEquals(SpatialValues.POINT, captured.typeId());
        assertEquals(Set.of(key(SpatialValues.POINT)), captured.dependencies().contributions());
        assertTrue(captured.dependencies().documents().isEmpty());
        assertTrue(captured.dependencies().resources().isEmpty());
        assertTrue(captured.problems().isEmpty());
        assertTrue(type.owns(captured));
        assertFalse(activated(ValueTypeCatalog.activate(registry), SpatialValues.POINT, MapPoint.class).owns(captured));
        assertFalse(activated(catalog, SpatialValues.AREA, MapArea.class).owns(captured));
        byte[] persisted = captured.encoding();
        assertTrue(persisted.length < 512, "huge negative exponent must remain compact");
        JsonObject root = ExactJson.parse(text(persisted)).toJson().getAsJsonObject();
        assertEquals(Set.of("dimension", "position", "yaw", "pitch"), root.keySet());
        assertTrue(root.get("position").isJsonArray());
        root.getAsJsonArray("position").forEach(value -> assertTrue(value.getAsJsonPrimitive().isNumber()));
        assertArrayEquals(ExactJson.parse(text(persisted)).canonicalUtf8(), persisted);
        assertPoint(original, type.decode(type.codecId(), 1, persisted).content());
        assertArrayEquals(persisted, type.decode(type.codecId(), 1, persisted).encoding());
        persisted[0] = '!';
        assertEquals('{', captured.encoding()[0]);
    }

    @Test
    void finiteUnderflowOfEveryRuntimeConversionRetainsOriginalDecimal() {
        var type = activated(ValueTypeCatalog.activate(registered()), SpatialValues.POINT, MapPoint.class);
        MapPoint point = new MapPoint(DIMENSION, decimal("1E-400"), decimal("-1E-400"),
                decimal("1E-1000000"), decimal("1E-100"), decimal("-1E-100"));
        assertEquals(0.0, point.x().doubleValue());
        assertEquals(0.0f, point.yaw().floatValue());
        ValueSnapshot<MapPoint> snapshot = type.capture(point);
        assertPoint(point, type.decode(type.codecId(), 1, snapshot.encoding()).content());
        assertTrue(text(snapshot.encoding()).contains("1E-1000000"));
    }

    @Test
    void constructionCannotAdmitValuesThatCaptureButCannotReopen() {
        // Finite runtime conversions alone do not certify the exact JSON number budget.
        for (BigDecimal invalid : List.of(decimal("1E-1000001"),
                decimal("0." + "1234567890".repeat(110)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MapPoint(DIMENSION, invalid, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO));
        }
        assertThrows(IllegalArgumentException.class, () -> new MapPoint(DIMENSION,
                decimal("1E309"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MapPoint(DIMENSION,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, decimal("1E39"), BigDecimal.ZERO));
    }

    @Test
    void areaPreservesReversedDegenerateAndFullInt32CornersThroughSnapshots() {
        ExtensionRegistry registry = registered();
        var type = activated(ValueTypeCatalog.activate(registry), SpatialValues.AREA, MapArea.class);
        var implementation = implementation(registry, SpatialValues.AREA, MapArea.class);
        for (BoxCorners corners : List.of(
                new BoxCorners(new IntPosition(10, 20, 30), new IntPosition(-10, -20, -30)),
                new BoxCorners(new IntPosition(7, 7, 7), new IntPosition(7, 7, 7)),
                new BoxCorners(new IntPosition(Integer.MAX_VALUE, Integer.MIN_VALUE, 0),
                        new IntPosition(Integer.MIN_VALUE, Integer.MAX_VALUE, 0)))) {
            MapArea area = new MapArea(DIMENSION, corners);
            assertTrue(implementation.validate(area).isEmpty());
            assertNoDependencies(implementation.dependencies(area));
            ValueSnapshot<MapArea> captured = type.capture(area);
            assertEquals(area, type.decode(type.codecId(), 1, captured.encoding()).content());
            assertEquals(corners.first(), captured.content().corners().first());
            assertEquals(corners.second(), captured.content().corners().second());
            assertEquals(Set.of(key(SpatialValues.AREA)), captured.dependencies().contributions());
            assertArrayEquals(captured.encoding(), ExactJson.parse(text(captured.encoding())).canonicalUtf8());
            JsonArray wire = ExactJson.parse(text(captured.encoding())).toJson().getAsJsonObject().getAsJsonArray("corners");
            assertEquals(2, wire.size());
            assertEquals(3, wire.get(0).getAsJsonArray().size());
        }
    }

    @Test
    void mathematicallyIntegralAreaNumbersAreAcceptedWithoutNarrowingOrReordering() {
        var type = activated(ValueTypeCatalog.activate(registered()), SpatialValues.AREA, MapArea.class);
        String wire = AREA_JSON.replace("[[1,2,3],[4,5,6]]",
                "[[2147483647.0,-2147483648e0,3e0],[-2147483648,2147483647,6.000]]");
        MapArea area = type.decode(type.codecId(), 1, bytes(wire)).content();
        assertEquals(new IntPosition(Integer.MAX_VALUE, Integer.MIN_VALUE, 3), area.corners().first());
        assertEquals(new IntPosition(Integer.MIN_VALUE, Integer.MAX_VALUE, 6), area.corners().second());
    }

    @Test
    void pointRejectsUnknownMissingWrongShapeStringNullIdAndRuntimeOverflow() {
        var type = activated(ValueTypeCatalog.activate(registered()), SpatialValues.POINT, MapPoint.class);
        for (String invalid : List.of(
                POINT_JSON.replace("\"yaw\":4,", "\"extra\":0,\"yaw\":4,"),
                POINT_JSON.replace(",\"pitch\":5", ""),
                POINT_JSON.replace("[1,2,3]", "[1,2]"),
                POINT_JSON.replace("[1,2,3]", "[1,2,3,4]"),
                POINT_JSON.replace("[1,2,3]", "\"[1,2,3]\""),
                POINT_JSON.replace("[1,2,3]", "[\"1\",2,3]"),
                POINT_JSON.replace("\"yaw\":4", "\"yaw\":\"4\""),
                POINT_JSON.replace("\"pitch\":5", "\"pitch\":null"),
                POINT_JSON.replace("thirdparty:unconnected_dimension", "not a dimension"),
                POINT_JSON.replace("\"thirdparty:unconnected_dimension\"", "null"),
                POINT_JSON.replace("[1,2,3]", "[1e309,2,3]"),
                POINT_JSON.replace("\"yaw\":4", "\"yaw\":1e39"),
                POINT_JSON.replace("\"pitch\":5", "\"pitch\":-1e39"),
                "[]", "null")) {
            byte[] source = bytes(invalid);
            byte[] before = source.clone();
            DocumentCodecException failure = assertThrows(DocumentCodecException.class,
                    () -> type.decode(type.codecId(), 1, source), invalid);
            assertFieldFailure(failure);
            assertArrayEquals(before, source);
        }
    }

    @Test
    void areaRejectsNonInt32AndEveryNestedArrayBoundary() {
        var type = activated(ValueTypeCatalog.activate(registered()), SpatialValues.AREA, MapArea.class);
        for (String corners : List.of("[]", "[[1,2,3]]", "[[1,2,3],[4,5,6],[7,8,9]]",
                "[[1,2],[4,5,6]]", "[[1,2,3],[4,5,6,7]]", "[null,[4,5,6]]",
                "[[\"1\",2,3],[4,5,6]]", "[[1.5,2,3],[4,5,6]]",
                "[[2147483648,2,3],[4,5,6]]", "[[1,2,3],[-2147483649,5,6]]",
                "[[1e1000000,2,3],[4,5,6]]", "[[1e-1000000,2,3],[4,5,6]]")) {
            assertFieldFailure(assertThrows(DocumentCodecException.class, () -> type.decode(type.codecId(), 1,
                    bytes(AREA_JSON.replace("[[1,2,3],[4,5,6]]", corners))), corners));
        }
        for (String invalid : List.of(AREA_JSON.replace(",\"corners\":[[1,2,3],[4,5,6]]", ""),
                AREA_JSON.replace("\"corners\":", "\"unknown\":0,\"corners\":"))) {
            assertFieldFailure(assertThrows(DocumentCodecException.class,
                    () -> type.decode(type.codecId(), 1, bytes(invalid))));
        }
    }

    @Test
    void strictJsonFailuresKeepExactJsonCodeAndPointerAsTypedArguments() {
        ExtensionRegistry registry = registered();
        for (EditorId id : List.of(SpatialValues.POINT, SpatialValues.AREA)) {
            ValueType<?> type = registry.find(ValueTypes.KIND, id).orElseThrow();
            String valid = id.equals(SpatialValues.POINT) ? POINT_JSON : AREA_JSON;
            for (String invalid : List.of(valid + "{}", valid.replace("\"dimension\":",
                    "\"dimension\":\"first:duplicate\",\"dimension\":"),
                    valid.replace("thirdparty:unconnected_dimension", "\\uD800"),
                    valid.replaceFirst("[1-6]", "01"),
                    valid.substring(0, valid.length() - 1),
                    valid.replaceFirst("[1-6]", "1e1000001"),
                    valid.replace("thirdparty:unconnected_dimension", "a".repeat(ExactJson.MAX_STRING_UTF8_BYTES + 1)),
                    "[".repeat(ExactJson.MAX_DEPTH + 1) + "0" + "]".repeat(ExactJson.MAX_DEPTH + 1))) {
                JsonContractException expected = assertThrows(JsonContractException.class, () -> ExactJson.parse(invalid));
                byte[] source = bytes(invalid);
                byte[] before = source.clone();
                Problem problem = failure(assertThrows(DocumentCodecException.class,
                        () -> type.decode(1, source)), "invalid_json", "spatial:" + expected.path());
                assertEquals(List.of(literal(expected.code()), literal(expected.path())), message(problem).arguments());
                assertArrayEquals(before, source);
            }
        }
    }

    @Test
    void invalidNumericFieldsIdentifyTheActualNestedLocation() {
        ExtensionRegistry registry = registered();
        var point = activated(ValueTypeCatalog.activate(registry), SpatialValues.POINT, MapPoint.class);
        var area = activated(ValueTypeCatalog.activate(registry), SpatialValues.AREA, MapArea.class);
        Problem pointProblem = failure(assertThrows(DocumentCodecException.class,
                () -> point.decode(point.codecId(), 1, bytes(POINT_JSON.replace("[1,2,3]", "[\"1\",2,3]")))),
                "invalid_field", "spatial:/position/0");
        assertEquals(literal("/position/0"), message(pointProblem).arguments().get(0));
        Problem areaProblem = failure(assertThrows(DocumentCodecException.class,
                () -> area.decode(area.codecId(), 1, bytes(AREA_JSON.replace("[[1,2,3]", "[[1.5,2,3]")))),
                "invalid_field", "spatial:/corners/0/0");
        assertEquals(literal("/corners/0/0"), message(areaProblem).arguments().get(0));
    }

    @Test
    void strictUtf8BomSchemaAndByteBudgetFailuresUseFrozenSpatialMessages() {
        ExtensionRegistry registry = registered();
        for (EditorId id : List.of(SpatialValues.POINT, SpatialValues.AREA)) {
            ValueType<?> type = registry.find(ValueTypes.KIND, id).orElseThrow();
            String valid = id.equals(SpatialValues.POINT) ? POINT_JSON : AREA_JSON;
            for (int schema : List.of(0, 2, Integer.MAX_VALUE)) {
                Problem problem = failure(assertThrows(DocumentCodecException.class,
                        () -> type.decode(schema, bytes(valid))), "unsupported_schema", "spatial");
                assertEquals(List.of(number(schema)), message(problem).arguments());
            }
            for (byte[] malformed : List.of(new byte[] {(byte) 0xc3, 0x28},
                    new byte[] {(byte) 0xc0, (byte) 0xaf},
                    new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
                Problem problem = failure(assertThrows(DocumentCodecException.class,
                        () -> type.decode(1, malformed)), "invalid_utf8", "spatial");
                assertTrue(message(problem).arguments().isEmpty());
            }
            byte[] bom = bytes("\uFEFF" + valid);
            Problem bomProblem = failure(assertThrows(DocumentCodecException.class,
                    () -> type.decode(1, bom)), "invalid_utf8", "spatial");
            assertTrue(message(bomProblem).arguments().isEmpty());
            byte[] oversized = new byte[ExactJson.MAX_UTF8_BYTES + 1];
            Arrays.fill(oversized, (byte) 0xff); // Proves the byte gate wins over UTF-8 decoding.
            Problem problem = failure(assertThrows(DocumentCodecException.class,
                    () -> type.decode(1, oversized)), "byte_budget", "spatial");
            assertEquals(List.of(number(oversized.length), number(ExactJson.MAX_UTF8_BYTES)), message(problem).arguments());
            assertEquals((byte) 0xff, oversized[0]);
            assertEquals((byte) 0xff, oversized[oversized.length - 1]);
        }
    }

    @Test
    void successfulDecodeDoesNotModifyInputAndCanonicalizesEquivalentNumericSpellings() {
        var type = activated(ValueTypeCatalog.activate(registered()), SpatialValues.POINT, MapPoint.class);
        byte[] source = bytes(" \n" + POINT_JSON.replace("[1,2,3]", "[1.00,2e0,3.000]") + "\t");
        byte[] before = source.clone();
        ValueSnapshot<MapPoint> snapshot = type.decode(type.codecId(), 1, source);
        assertArrayEquals(before, source);
        assertArrayEquals(type.decode(type.codecId(), 1, bytes(POINT_JSON)).encoding(), snapshot.encoding());
        Arrays.fill(source, (byte) '!');
        assertArrayEquals(type.decode(type.codecId(), 1, bytes(POINT_JSON)).encoding(), snapshot.encoding());
    }

    private static ExtensionRegistryBuilder builder(Contribution.Side side) {
        return new ExtensionRegistryBuilder(new ApiVersion(1, 0), side);
    }

    private static ExtensionRegistry registered() {
        ExtensionRegistryBuilder builder = builder(Contribution.Side.COMMON);
        // register must define the kind itself and must leave the builder open for freeze.
        SpatialValues.register(builder, "test-build");
        return builder.freeze();
    }

    private static ContributionKey key(EditorId id) { return new ContributionKey(ValueTypes.KIND.id(), id); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static BigDecimal decimal(String text) { return new BigDecimal(text); }
    private static TextContent.LiteralArgument literal(String text) { return new TextContent.LiteralArgument(text); }
    private static TextContent.NumberArgument number(long value) { return new TextContent.NumberArgument(BigDecimal.valueOf(value)); }

    @SuppressWarnings("unchecked")
    private static <T> ActivatedValueType<T> activated(ValueTypeCatalog catalog, EditorId id, Class<T> valueType) {
        ActivatedValueType<?> type = catalog.find(id).orElseThrow();
        assertEquals(valueType, type.valueType());
        return (ActivatedValueType<T>) type;
    }

    @SuppressWarnings("unchecked")
    private static <T> ValueType<T> implementation(ExtensionRegistry registry, EditorId id, Class<T> valueType) {
        ValueType<?> type = registry.find(ValueTypes.KIND, id).orElseThrow();
        assertEquals(valueType, type.valueType());
        return (ValueType<T>) type;
    }

    private static void assertDescriptor(ValueTypeCatalog catalog, EditorId id, Class<?> type, String codec) {
        ActivatedValueType<?> actual = catalog.find(id).orElseThrow();
        assertEquals(type, actual.valueType());
        assertEquals(SpatialValues.MODULE, actual.owner());
        assertEquals(EditorId.parse(codec), actual.codecId());
        assertEquals(1, actual.schemaVersion());
        assertEquals(Set.of(1), actual.readableSchemas());
    }

    private static void assertNoDependencies(DocumentDependencies dependencies) {
        assertTrue(dependencies.documents().isEmpty());
        assertTrue(dependencies.resources().isEmpty());
        assertTrue(dependencies.contributions().isEmpty());
    }

    private static void assertPoint(MapPoint expected, MapPoint actual) {
        assertEquals(expected.dimension(), actual.dimension());
        List<BigDecimal> expectedValues = List.of(expected.x(), expected.y(), expected.z(), expected.yaw(), expected.pitch());
        List<BigDecimal> actualValues = List.of(actual.x(), actual.y(), actual.z(), actual.yaw(), actual.pitch());
        for (int index = 0; index < expectedValues.size(); index++) {
            assertEquals(0, expectedValues.get(index).compareTo(actualValues.get(index)), "exact component " + index);
        }
    }

    private static void assertFieldFailure(DocumentCodecException exception) {
        Problem problem = exception.problems().get(0);
        assertTrue(problem.target().startsWith("spatial:"));
        failure(exception, "invalid_field", problem.target());
        List<TextContent.Argument> arguments = message(problem).arguments();
        assertEquals(2, arguments.size());
        String pointer = assertInstanceOf(TextContent.LiteralArgument.class, arguments.get(0)).value();
        String expected = assertInstanceOf(TextContent.LiteralArgument.class, arguments.get(1)).value();
        assertEquals("spatial:" + pointer, problem.target());
        assertTrue(pointer.isEmpty() || pointer.startsWith("/"));
        assertFalse(expected.isBlank());
    }

    private static Problem failure(DocumentCodecException exception, String suffix, String target) {
        assertFalse(exception.problems().isEmpty());
        Problem problem = exception.problems().get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(EditorId.parse("fpsmatch:spatial/" + suffix), problem.code());
        assertEquals(target, problem.target());
        assertEquals(MESSAGE_PREFIX + suffix, message(problem).key());
        assertLanguage(message(problem));
        return problem;
    }

    private static TextContent.Message message(Problem problem) {
        return assertInstanceOf(TextContent.Message.class, problem.message());
    }

    private static final Map<String, Map<String, String>> LANGUAGES = loadLanguages();

    private static Map<String, Map<String, String>> loadLanguages() {
        Map<String, Map<String, String>> languages = new TreeMap<>();
        Set<String> keys = Set.of(MESSAGE_PREFIX + "unsupported_schema", MESSAGE_PREFIX + "invalid_utf8",
                MESSAGE_PREFIX + "invalid_json", MESSAGE_PREFIX + "invalid_field", MESSAGE_PREFIX + "byte_budget");
        for (String language : List.of("zh_cn", "en_us")) {
            try (InputStream stream = SpatialValuesContractTest.class.getResourceAsStream("/assets/fpsmatch/lang/" + language + ".json")) {
                assertNotNull(stream, "packaged spatial language: " + language);
                JsonObject json = ExactJson.parse(text(stream.readAllBytes())).toJson().getAsJsonObject();
                json.keySet().removeIf(key -> !key.startsWith(MESSAGE_PREFIX));
                assertEquals(keys, json.keySet());
                Map<String, String> translations = new TreeMap<>();
                json.entrySet().forEach(entry -> {
                    assertTrue(entry.getValue().isJsonPrimitive());
                    assertTrue(entry.getValue().getAsJsonPrimitive().isString());
                    translations.put(entry.getKey(), entry.getValue().getAsString());
                });
                languages.put(language, Map.copyOf(translations));
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }
        return Map.copyOf(languages);
    }

    /** Render actual diagnostics, proving every positional typed argument is consumed in each locale. */
    private static void assertLanguage(TextContent.Message message) {
        for (var language : LANGUAGES.entrySet()) {
            String template = language.getValue().get(message.key());
            assertNotNull(template, language.getKey() + ": " + message.key());
            for (TextContent.Argument argument : message.arguments()) {
                if (!(argument instanceof TextContent.LiteralArgument)) {
                    assertInstanceOf(TextContent.NumberArgument.class, argument);
                }
            }
            String rendered = MinecraftTranslationFormat.rendered(language.getValue(), message);
            assertFalse(rendered.isBlank());
        }
    }
}
