package net.ptcrys.fpsmatch.editor.common.domain.item;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.ptcrys.fpsmatch.editor.testutil.MinecraftTranslationFormat;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemContext;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import org.junit.jupiter.api.Test;

/** Pure consumers of the frozen Q39 profile, including current-codec bypass attempts. */
class BasicItemTypeTest {
    private static final EditorId TYPE = id("thirdparty:explicit_items");
    private static final EditorId TOKEN = id("thirdparty:token"), HELMET = id("minecraft:iron_helmet");
    private static final String CURRENT = "{\"itemId\":\"thirdparty:token\",\"count\":1,\"damage\":null}";

    @Test void profileCopiesSortsAndBoundsTheCallerTableBeforeTraversal() {
        Map<EditorId, BasicItemType.TagMode> input = new LinkedHashMap<>();
        input.put(TOKEN, BasicItemType.TagMode.EMPTY); input.put(HELMET, BasicItemType.TagMode.DAMAGE);
        BasicItemType type = new BasicItemType(TYPE, input); input.clear();
        assertEquals(List.of(HELMET, TOKEN), new ArrayList<>(type.acceptedItems().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> type.acceptedItems().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> type.acceptedItems().entrySet().iterator().next().setValue(BasicItemType.TagMode.EMPTY));
        assertEquals(2, type.acceptedItems().size());
        for (int size : List.of(0, BasicItemType.MAX_ITEMS + 1)) {
            Map<EditorId, BasicItemType.TagMode> hostile = new AbstractMap<>() {
                @Override public int size() { return size; }
                @Override public Set<Entry<EditorId, BasicItemType.TagMode>> entrySet() {
                    throw new AssertionError("budget must precede table traversal");
                }
            };
            assertThrows(IllegalArgumentException.class, () -> new BasicItemType(TYPE, hostile));
        }
        Map<EditorId, BasicItemType.TagMode> maximum = new TreeMap<>();
        for (int i = 0; i < BasicItemType.MAX_ITEMS; i++) maximum.put(id("thirdparty:item_" + i), BasicItemType.TagMode.EMPTY);
        assertEquals(8192, new BasicItemType(TYPE, maximum).acceptedItems().size());
        assertThrows(NullPointerException.class, () -> new BasicItemType(null, Map.of(TOKEN, BasicItemType.TagMode.EMPTY)));
        assertThrows(NullPointerException.class, () -> new BasicItemType(TYPE, null));
        Map<EditorId, BasicItemType.TagMode> nullEntry = new LinkedHashMap<>(); nullEntry.put(null, BasicItemType.TagMode.EMPTY);
        assertThrows(NullPointerException.class, () -> new BasicItemType(TYPE, nullEntry));
        nullEntry.clear(); nullEntry.put(TOKEN, null);
        assertThrows(NullPointerException.class, () -> new BasicItemType(TYPE, nullEntry));
    }

    @Test void basicValueRejectsImpossibleCountsDamageAndNullState() {
        for (int count : List.of(Integer.MIN_VALUE, -1, 0, 128, 256, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> new BasicItem(TOKEN, count, OptionalInt.empty()));
        }
        assertThrows(IllegalArgumentException.class, () -> new BasicItem(HELMET, 1, OptionalInt.of(-1)));
        assertThrows(NullPointerException.class, () -> new BasicItem(null, 1, OptionalInt.empty()));
        assertThrows(NullPointerException.class, () -> new BasicItem(TOKEN, 1, null));
        assertEquals(127, new BasicItem(TOKEN, 127, OptionalInt.empty()).count());
        assertEquals(Integer.MAX_VALUE, new BasicItem(HELMET, 1, OptionalInt.of(Integer.MAX_VALUE)).damage().orElseThrow());
    }

    @Test void legacyAcceptsOnlyTheEntireDeclaredShapeAndNormalizesKnownDamageDefault() {
        BasicItemType type = profile();
        for (String count : List.of("1", "127", "1.0", "127e0")) {
            String legacy = "{\"id\":\"thirdparty:token\",\"Count\":" + count + "}";
            var encoding = type.convert(ExactJson.parse(legacy)).orElseThrow();
            assertEquals(TYPE, encoding.typeId()); assertEquals(id("thirdparty:explicit_items_json"), encoding.codecId());
            assertEquals(1, encoding.schema());
            assertEquals(new BasicItem(TOKEN, new BigDecimal(count).intValueExact(), OptionalInt.empty()),
                    type.decode(encoding.schema(), encoding.bytes()));
            var contextual = type.convert(ExactJson.parse(legacy), LegacyItemContext.empty()).orElseThrow();
            assertArrayEquals(encoding.bytes(), contextual.bytes());
        }
        for (String tag : List.of("", ",\"tag\":{}", ",\"tag\":{\"Damage\":0}")) {
            var encoding = type.convert(ExactJson.parse("{\"id\":\"minecraft:iron_helmet\",\"Count\":1" + tag + "}")).orElseThrow();
            assertEquals(OptionalInt.of(0), type.decode(1, encoding.bytes()).damage());
        }
        var fullDamage = type.convert(ExactJson.parse("{\"id\":\"minecraft:iron_helmet\",\"Count\":1,\"tag\":{\"Damage\":2147483647}}" )).orElseThrow();
        assertEquals(OptionalInt.of(Integer.MAX_VALUE), type.decode(1, fullDamage.bytes()).damage());
        assertTrue(type.convert(ExactJson.parse("{\"id\":\"thirdparty:token\",\"Count\":1,\"tag\":{}}")).isPresent());
    }

    @Test void unknownNamespacePeersFieldsCapsAndTagsCannotSilentlyBecomeBasicItems() {
        BasicItemType type = profile();
        for (String invalid : List.of(
                "{\"id\":\"thirdparty:other\",\"Count\":1}",
                "{\"id\":\"minecraft:diamond\",\"Count\":1}",
                "{\"id\":1,\"Count\":1}", "{\"id\":null,\"Count\":1}",
                "{\"id\":\"thirdparty:token\"}", "{\"Count\":1}", "[]", "null")) {
            assertTrue(type.convert(ExactJson.parse(invalid)).isEmpty(), invalid);
        }
        for (String extra : List.of(",\"ForgeCaps\":{}", ",\"future\":0", ",\"tag\":null", ",\"tag\":[]",
                ",\"tag\":true", ",\"tag\":\"{}\"", ",\"tag\":{\"Damage\":0}",
                ",\"tag\":{\"display\":{\"Name\":\"x\"}}", ",\"tag\":{\"ForgeCaps\":{}}")) {
            String source = "{\"id\":\"thirdparty:token\",\"Count\":1" + extra + "}";
            assertTrue(type.convert(ExactJson.parse(source)).isEmpty(), source);
        }
        for (String tag : List.of("{\"Damage\":-1}", "{\"Damage\":2147483648}", "{\"Damage\":0.5}",
                "{\"Damage\":\"0\"}", "{\"Damage\":true}", "{\"Damage\":null}",
                "{\"Damage\":{}}", "{\"Damage\":0,\"future\":1}")) {
            assertTrue(type.convert(ExactJson.parse("{\"id\":\"minecraft:iron_helmet\",\"Count\":1,\"tag\":" + tag + "}")).isEmpty(), tag);
        }
    }

    @Test void numericCountPolicyRejectsCoercionFractionsAndSignedByteOverflowInBothFormats() {
        BasicItemType type = profile();
        for (String count : List.of("0", "-1", "128", "256", "2147483647", "2147483648", "-2147483649",
                "1.5", "1e-1000000", "1e1000000", "\"1\"", "true", "null", "[]", "{}", "\"NaN\"")) {
            assertTrue(type.convert(ExactJson.parse("{\"id\":\"thirdparty:token\",\"Count\":" + count + "}")).isEmpty(), count);
            field(assertThrows(DocumentCodecException.class,
                    () -> type.decode(1, bytes(CURRENT.replace("\"count\":1", "\"count\":" + count)))));
        }
        // Non-finite Java/Gson primitives cannot enter the frozen ExactJson boundary either.
        for (double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            JsonObject shape = new JsonObject(); shape.addProperty("id", "thirdparty:token"); shape.addProperty("Count", value);
            assertThrows(JsonContractException.class, () -> ExactJson.freeze(shape));
        }
    }

    @Test void everyCurrentEntryPointEnforcesWhitelistAndTagModeWithoutMutatingAcceptedValue() {
        BasicItemType type = profile(); BasicItem good = new BasicItem(TOKEN, 1, OptionalInt.empty()); byte[] original = type.encode(good);
        for (BasicItem invalid : List.of(new BasicItem(id("thirdparty:other"), 1, OptionalInt.empty()),
                new BasicItem(TOKEN, 1, OptionalInt.of(0)), new BasicItem(HELMET, 1, OptionalInt.empty()))) {
            field(assertThrows(DocumentCodecException.class, () -> type.snapshot(invalid)));
            field(assertThrows(DocumentCodecException.class, () -> type.validate(invalid)));
            field(assertThrows(DocumentCodecException.class, () -> type.encode(invalid)));
            field(assertThrows(DocumentCodecException.class, () -> type.dependencies(invalid)));
            assertArrayEquals(original, type.encode(good));
        }
        for (String invalid : List.of(CURRENT.replace("thirdparty:token", "thirdparty:other"),
                CURRENT.replace("null", "0"), CURRENT.replace("thirdparty:token", "minecraft:iron_helmet"),
                CURRENT.replace("\"itemId\":\"thirdparty:token\"", "\"itemId\":true"),
                CURRENT.replace("thirdparty:token", "Not an ID"), CURRENT.replace(",\"damage\":null", ""),
                CURRENT.replace("{", "{\"future\":0,"), CURRENT.replace("null", "\"0\""), "[]", "null")) {
            byte[] source = bytes(invalid), before = source.clone();
            field(assertThrows(DocumentCodecException.class, () -> type.decode(1, source)));
            assertArrayEquals(before, source);
        }
        for (String damage : List.of("-1", "2147483648", "0.1", "true", "\"0\"", "[]")) {
            field(assertThrows(DocumentCodecException.class, () -> type.decode(1,
                    bytes(CURRENT.replace("thirdparty:token", "minecraft:iron_helmet").replace("null", damage)))));
        }
    }

    @Test void strictReaderReportsAllFiveTypedDiagnosticsWithBothPackagedLanguages() {
        BasicItemType type = profile();
        for (int schema : List.of(0, 2, Integer.MAX_VALUE)) {
            var message = failure(assertThrows(DocumentCodecException.class, () -> type.decode(schema, bytes(CURRENT))), "unsupported_schema", "items");
            assertEquals(List.of(number(schema)), message.arguments());
        }
        for (byte[] malformed : List.of(new byte[] {(byte)0xc3, 0x28}, new byte[] {(byte)0xc0, (byte)0xaf},
                new byte[] {(byte)0xed, (byte)0xa0, (byte)0x80}, bytes("\uFEFF" + CURRENT))) {
            assertTrue(failure(assertThrows(DocumentCodecException.class, () -> type.decode(1, malformed)), "invalid_utf8", "items").arguments().isEmpty());
        }
        for (String invalid : List.of(CURRENT + "{}", CURRENT.replace("{", "{\"count\":2,"),
                CURRENT.replace("thirdparty:token", "\\uD800"), CURRENT.replace("\"count\":1", "\"count\":NaN"),
                CURRENT.replace("\"count\":1", "\"count\":01"), CURRENT.substring(0, CURRENT.length() - 1))) {
            JsonContractException expected = assertThrows(JsonContractException.class, () -> ExactJson.parse(invalid));
            var message = failure(assertThrows(DocumentCodecException.class, () -> type.decode(1, bytes(invalid))), "invalid_json", "items:" + expected.path());
            assertEquals(List.of(new TextContent.LiteralArgument(expected.code()), new TextContent.LiteralArgument(expected.path())), message.arguments());
        }
        byte[] huge = new byte[ExactJson.MAX_UTF8_BYTES + 1]; Arrays.fill(huge, (byte)0xff);
        var budget = failure(assertThrows(DocumentCodecException.class, () -> type.decode(1, huge)), "byte_budget", "items");
        assertEquals(List.of(number(huge.length), number(ExactJson.MAX_UTF8_BYTES)), budget.arguments());
        field(assertThrows(DocumentCodecException.class, () -> type.decode(1, bytes(CURRENT.replace("\"count\":1", "\"count\":0")))));
    }

    @Test void explicitThirdPartyRegistrationProducesActualCodecAndOnlyThePersistedLeafDependency() {
        BasicItemType type = profile(); EditorId owner = id("thirdparty:module");
        var builder = new ExtensionRegistryBuilder(new ApiVersion(1, 0), Contribution.Side.COMMON);
        builder.define(ValueTypes.KIND);
        builder.addModule(new ModuleSpec(owner, "actual-build.39", new ApiVersion(1, 0), Set.of(), Set.of()));
        var leaf = new ContributionKey(ValueTypes.KIND.id(), TYPE);
        builder.add(ValueTypes.KIND, new Contribution(leaf, owner, Contribution.Side.COMMON, Set.of()), type);
        var registry = builder.freeze(); var catalog = ValueTypeCatalog.activate(registry);
        var active = catalog.find(TYPE).orElseThrow();
        assertEquals(BasicItem.class, active.valueType()); assertEquals(owner, active.owner());
        assertEquals(type.codecId(), active.codecId()); assertEquals(1, active.schemaVersion()); assertEquals(Set.of(1), active.readableSchemas());
        assertEquals(id("fpsmatch:item"), type.role());
        var legacy = type.convert(ExactJson.parse("{\"id\":\"thirdparty:token\",\"Count\":127}")).orElseThrow();
        var captured = active.decode(legacy.codecId(), legacy.schema(), legacy.bytes());
        assertEquals(Set.of(leaf), captured.dependencies().contributions());
        assertTrue(captured.dependencies().resources().isEmpty()); assertTrue(captured.dependencies().documents().isEmpty());
        assertTrue(type.dependencies((BasicItem)captured.content()).contributions().isEmpty());
        assertTrue(type.references((BasicItem)captured.content()).isEmpty());
        byte[] wire = captured.encoding(); assertArrayEquals(ExactJson.parse(new String(wire, StandardCharsets.UTF_8)).canonicalUtf8(), wire);
        var reopened = ValueTypeCatalog.activate(registry).find(TYPE).orElseThrow().decode(active.codecId(), 1, wire);
        assertEquals(captured.content(), reopened.content()); assertArrayEquals(wire, reopened.encoding());
        wire[0] = '!'; assertEquals('{', captured.encoding()[0]);
        assertFalse(type.convert(ExactJson.parse("{\"id\":\"thirdparty:unlisted\",\"Count\":1}")).isPresent());
    }

    private static BasicItemType profile() { return new BasicItemType(TYPE, Map.of(TOKEN, BasicItemType.TagMode.EMPTY, HELMET, BasicItemType.TagMode.DAMAGE)); }
    private static EditorId id(String value) { return EditorId.parse(value); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static TextContent.NumberArgument number(long value) { return new TextContent.NumberArgument(BigDecimal.valueOf(value)); }
    private static void field(DocumentCodecException exception) {
        Problem problem = exception.problems().get(0);
        TextContent.Message message = failure(exception, "invalid_field", problem.target());
        assertEquals(2, message.arguments().size());
        String pointer = assertInstanceOf(TextContent.LiteralArgument.class, message.arguments().get(0)).value();
        assertFalse(assertInstanceOf(TextContent.LiteralArgument.class, message.arguments().get(1)).value().isBlank());
        assertEquals("items:" + pointer, problem.target());
    }
    private static TextContent.Message failure(DocumentCodecException exception, String suffix, String target) {
        assertEquals(1, exception.problems().size()); Problem problem = exception.problems().get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity()); assertEquals(id("fpsmatch:items/" + suffix), problem.code());
        assertEquals(target, problem.target());
        var message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals("fpsmatch.editor.items." + suffix, message.key());
        for (String language : List.of("en_us", "zh_cn")) {
            try (InputStream stream = BasicItemTypeTest.class.getResourceAsStream("/assets/fpsmatch/lang/" + language + ".json")) {
                assertNotNull(stream, language);
                JsonObject translations = ExactJson.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).toJson().getAsJsonObject();
                translations.keySet().removeIf(key -> !key.startsWith("fpsmatch.editor.items."));
                assertEquals(Set.of("fpsmatch.editor.items.unsupported_schema", "fpsmatch.editor.items.invalid_utf8", "fpsmatch.editor.items.invalid_json",
                        "fpsmatch.editor.items.invalid_field", "fpsmatch.editor.items.byte_budget"), translations.keySet());
                String template = translations.get(message.key()).getAsString();
                for (TextContent.Argument argument : message.arguments()) {
                    if (!(argument instanceof TextContent.LiteralArgument)) {
                        assertInstanceOf(TextContent.NumberArgument.class, argument);
                    }
                }
                String rendered = MinecraftTranslationFormat.render(message, template);
                assertFalse(rendered.isBlank());
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        return message;
    }
}
