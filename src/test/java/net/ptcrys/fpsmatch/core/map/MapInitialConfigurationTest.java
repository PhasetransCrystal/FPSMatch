package net.ptcrys.fpsmatch.core.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.ptcrys.fpsmatch.core.data.Setting;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MapInitialConfigurationTest {
    @Test
    void captureOwnsItsInputAndCanBeReusedWithRealScalarSettings() {
        JsonObject input = json("{\"integer\":21,\"long\":9007199254740993,\"float\":1.25,"
                + "\"double\":2.125,\"byte\":7,\"boolean\":true,\"text\":\"captured\"}").getAsJsonObject();
        MapInitialConfiguration configuration = MapInitialConfiguration.capture(input);
        input.addProperty("integer", -100);
        input.addProperty("text", "later caller edit");
        input.remove("boolean");
        for (int reuse = 0; reuse < 2; reuse++) {
            Setting<Integer> integer = Setting.of("integer", 0);
            Setting<Long> longer = Setting.of("long", 0L);
            Setting<Float> floating = Setting.of("float", 0F);
            Setting<Double> doubled = Setting.of("double", 0D);
            Setting<Byte> small = Setting.of("byte", (byte) 0);
            Setting<Boolean> bool = Setting.of("boolean", false);
            Setting<String> text = Setting.of("text", "default");
            apply(configuration, List.of(integer, longer, floating, doubled, small, bool, text));
            assertEquals(Integer.valueOf(21), integer.get());
            assertEquals(Long.valueOf(9007199254740993L), longer.get());
            assertEquals(Float.valueOf(1.25F), floating.get());
            assertEquals(Double.valueOf(2.125D), doubled.get());
            assertEquals(Byte.valueOf((byte) 7), small.get());
            assertEquals(Boolean.TRUE, bool.get());
            assertEquals("captured", text.get());
        }
    }

    @Test
    void captureRejectsNonObjectsAndUsesTheWholeExactJsonBudget() {
        assertThrows(RuntimeException.class, () -> MapInitialConfiguration.capture(null));
        for (JsonElement invalid : List.of(JsonNull.INSTANCE, json("[]"), json("1"), json("\"text\""))) {
            assertThrows(RuntimeException.class, () -> MapInitialConfiguration.capture(invalid));
        }
        JsonObject oversized = new JsonObject();
        oversized.addProperty("value", "x".repeat(ExactJson.MAX_STRING_UTF8_BYTES + 1));
        assertThrows(RuntimeException.class, () -> MapInitialConfiguration.capture(oversized));
        JsonObject invalidUnicode = new JsonObject();
        invalidUnicode.addProperty("value", "\uD800");
        assertThrows(RuntimeException.class, () -> MapInitialConfiguration.capture(invalidUnicode));
        JsonObject tooDeep = new JsonObject();
        JsonObject child = tooDeep;
        for (int depth = 0; depth <= ExactJson.MAX_DEPTH; depth++) {
            JsonObject next = new JsonObject();
            child.add("child", next);
            child = next;
        }
        assertThrows(RuntimeException.class, () -> MapInitialConfiguration.capture(tooDeep));
        apply(MapInitialConfiguration.capture(new JsonObject()), List.of());
    }

    @Test
    void collectionOrderControlsBothDecodeAndRealFromJsonCallbacks() {
        List<String> events = new ArrayList<>();
        Probe<Integer> zCodec = new Probe<>(Codec.INT);
        Probe<Boolean> aCodec = new Probe<>(Codec.BOOL);
        zCodec.onDecode = () -> events.add("decode:z");
        aCodec.onDecode = () -> events.add("decode:a");
        ObservedSetting<Integer> z = new ObservedSetting<>("z", zCodec, 0);
        ObservedSetting<Boolean> a = new ObservedSetting<>("a", aCodec, false);
        z.before = ignored -> events.add("apply:z");
        a.before = ignored -> events.add("apply:a");
        apply(MapInitialConfiguration.capture(json("{\"a\":true,\"z\":19}")), List.of(z, a));
        assertEquals(List.of("decode:z", "decode:a", "apply:z", "decode:z", "apply:a", "decode:a"), events);
        assertEquals(19, z.get().intValue());
        assertTrue(a.get());
        assertEquals(1, z.applications);
        assertEquals(1, a.applications);
    }

    @Test
    void entireInventoryIsConsumedBeforeAnyCodecOrFromJsonRuns() {
        AtomicInteger consumed = new AtomicInteger();
        Probe<Integer> codec = new Probe<>(Codec.INT);
        codec.onDecode = () -> assertEquals(2, consumed.get(), "inventory must be complete before decoding");
        ObservedSetting<Integer> first = new ObservedSetting<>("first", codec, 0);
        ObservedSetting<Integer> second = new ObservedSetting<>("second", codec, 0);
        first.onCodec = () -> assertEquals(2, consumed.get(), "codec lookup must follow the whole inventory");
        second.onCodec = first.onCodec;
        Collection<Setting<?>> inventory = countedInventory(List.of(first, second), 2, consumed);
        apply(MapInitialConfiguration.capture(json("{\"first\":1,\"second\":2}")), inventory);
        assertEquals(4, codec.decodes);
        assertEquals(1, first.applications);
        assertEquals(1, second.applications);
    }

    @Test
    void invalidInventoryRejectsBeforeAnyCodecOrApplication() {
        for (String badName : Arrays.asList(null, "", " \t\n")) {
            Probe<Integer> codec = new Probe<>(Codec.INT);
            ObservedSetting<Integer> first = new ObservedSetting<>("first", codec, 0);
            ObservedSetting<Integer> bad = new ObservedSetting<>(badName, codec, 0);
            assertRejectedWithoutCalls(json("{\"first\":1}"), List.of(first, bad), codec, first, bad);
        }
        Probe<Integer> codec = new Probe<>(Codec.INT);
        ObservedSetting<Integer> first = new ObservedSetting<>("first", codec, 0);
        ObservedSetting<Integer> duplicate = new ObservedSetting<>("first", codec, 0);
        assertRejectedWithoutCalls(json("{\"first\":1}"), List.of(first, duplicate), codec, first, duplicate);
        assertRejectedWithoutCalls(json("{\"first\":1}"), Arrays.asList(first, null), codec, first);
        assertRejectedWithoutCalls(json("{}"), List.of(first), codec, first);
        assertRejectedWithoutCalls(json("{\"first\":1,\"extra\":2}"), List.of(first), codec, first);
        assertRejectedWithoutCalls(json("{\"FIRST\":1}"), List.of(first), codec, first);
        MapInitialConfiguration configuration = MapInitialConfiguration.capture(json("{\"first\":1}"));
        assertThrows(RuntimeException.class, () -> apply(configuration, null));
        assertEquals(0, codec.decodes);
        assertEquals(0, first.applications);
    }

    @Test
    void exact4096EntriesSucceedEvenWhenSizeLiesAnd4097NeverReachesCodec() {
        JsonObject input = new JsonObject();
        List<Setting<?>> entries = new ArrayList<>();
        Probe<Integer> codec = new Probe<>(Codec.INT);
        for (int index = 0; index < 4096; index++) {
            String name = "entry" + index;
            input.addProperty(name, index);
            entries.add(new ObservedSetting<>(name, codec, -1));
        }
        AtomicInteger consumed = new AtomicInteger();
        apply(MapInitialConfiguration.capture(input), countedInventory(entries, 0, consumed));
        assertEquals(4096, consumed.get());
        assertEquals(8192, codec.decodes);
        for (int index = 0; index < entries.size(); index++) {
            assertEquals(index, entries.get(index).get());
            assertEquals(1, ((ObservedSetting<?>) entries.get(index)).applications);
        }
        codec.decodes = 0;
        entries.forEach(setting -> ((ObservedSetting<?>) setting).applications = 0);
        input.addProperty("overflow", 1);
        entries.add(new ObservedSetting<>("overflow", codec, -1));
        consumed.set(0);
        assertRejectedWithoutCalls(input, countedInventory(entries, 0, consumed), codec,
                entries.toArray(ObservedSetting<?>[]::new));
        assertTrue(consumed.get() <= 4097, "overflow must terminate actual traversal");
        assertTrue(consumed.get() >= 4096, "a false size must not replace actual counting");
    }

    @Test
    void laterCodecFailureLeavesEverySettingUnapplied() {
        Probe<Integer> firstCodec = new Probe<>(Codec.INT);
        Probe<Integer> failingCodec = new Probe<>(Codec.INT);
        ObservedSetting<Integer> first = new ObservedSetting<>("first", firstCodec, -1);
        ObservedSetting<Integer> second = new ObservedSetting<>("second", failingCodec, -2);
        assertThrows(RuntimeException.class, () -> apply(MapInitialConfiguration.capture(
                json("{\"first\":5,\"second\":\"invalid\"}")), List.of(first, second)));
        assertEquals(1, firstCodec.decodes);
        assertEquals(1, failingCodec.decodes);
        assertEquals(0, first.applications);
        assertEquals(0, second.applications);
        assertEquals(-1, first.get().intValue());
        assertEquals(-2, second.get().intValue());
    }

    @Test
    void partialAndNullDecodedValuesCannotApplyEvenAnEarlierValidEntry() {
        for (boolean nullValue : new boolean[] {false, true}) {
            Probe<Integer> codec = new Probe<>(Codec.INT);
            codec.partial = nullValue ? null : 8;
            codec.nullValue = nullValue;
            ObservedSetting<Integer> first = new ObservedSetting<>("first", Codec.INT, -1);
            ObservedSetting<Integer> rejected = new ObservedSetting<>("rejected", codec, -2);
            assertThrows(RuntimeException.class, () -> apply(MapInitialConfiguration.capture(
                    json("{\"first\":4,\"rejected\":8}")), List.of(first, rejected)));
            assertEquals(1, codec.decodes);
            assertEquals(0, first.applications);
            assertEquals(0, rejected.applications);
            assertEquals(-1, first.get().intValue());
            assertEquals(-2, rejected.get().intValue());
        }
    }

    @Test
    void runtimeAndErrorIdentitySurvivePredecodeAndCustomFromJson() {
        for (Throwable primary : List.of(new IllegalArgumentException("codec"), new AssertionError("codec"))) {
            Probe<Integer> codec = new Probe<>(Codec.INT);
            codec.onDecode = () -> throwUnchecked(primary);
            ObservedSetting<Integer> setting = new ObservedSetting<>("value", codec, 0);
            assertSame(primary, assertThrows(Throwable.class, () -> apply(
                    MapInitialConfiguration.capture(json("{\"value\":5}")), List.of(setting))));
            assertEquals(0, setting.applications);
        }
        for (Throwable primary : List.of(new IllegalStateException("callback"), new AssertionError("callback"))) {
            ObservedSetting<Integer> first = new ObservedSetting<>("first", Codec.INT, 0);
            ObservedSetting<Integer> second = new ObservedSetting<>("second", Codec.INT, 0);
            second.before = ignored -> throwUnchecked(primary);
            assertSame(primary, assertThrows(Throwable.class, () -> apply(
                    MapInitialConfiguration.capture(json("{\"first\":5,\"second\":6}")), List.of(first, second))));
            assertEquals(1, first.applications);
            assertEquals(5, first.get().intValue(), "local writes are not rolled back");
            assertEquals(1, second.applications);
            assertEquals(0, second.get().intValue());
            assertFalse(MapConstructionScope.isConstructing());
        }
    }

    @Test
    void normallyReturningCustomFromJsonMustActuallyAdoptTheDecodedTypedValue() {
        for (boolean skip : new boolean[] {false, true}) {
            ObservedSetting<Integer> setting = new ObservedSetting<>("value", Codec.INT, 0);
            setting.skip = skip;
            if (!skip) setting.after = ignored -> setting.set(99);
            assertThrows(RuntimeException.class, () -> apply(
                    MapInitialConfiguration.capture(json("{\"value\":5}")), List.of(setting)));
            assertEquals(1, setting.applications, "the custom method must be invoked");
            assertEquals(skip ? 0 : 99, setting.get().intValue());
        }
        // A later callback can change an earlier entry: adoption must be checked after all callbacks.
        ObservedSetting<Integer> first = new ObservedSetting<>("first", Codec.INT, 0);
        ObservedSetting<Integer> second = new ObservedSetting<>("second", Codec.INT, 0);
        second.after = ignored -> first.set(99);
        assertThrows(RuntimeException.class, () -> apply(MapInitialConfiguration.capture(
                json("{\"first\":5,\"second\":6}")), List.of(first, second)));
        assertEquals(1, second.applications);
    }

    @Test
    void callbackJsonEditsAndRetainedReferencesCannotPolluteAnotherEntryOrReuse() {
        JsonObject shared = json("{\"value\":\"original\"}").getAsJsonObject();
        JsonObject input = new JsonObject();
        input.add("first", shared);
        input.add("second", shared);
        MapInitialConfiguration configuration = MapInitialConfiguration.capture(input);
        shared.addProperty("value", "caller edit");
        List<JsonElement> retained = new ArrayList<>();
        Codec<String> objectCodec = Codec.STRING.fieldOf("value").codec();
        for (int reuse = 0; reuse < 2; reuse++) {
            ObservedSetting<String> first = new ObservedSetting<>("first", objectCodec, "default");
            ObservedSetting<String> second = new ObservedSetting<>("second", objectCodec, "default");
            first.after = element -> {
                retained.add(element);
                element.getAsJsonObject().addProperty("value", "callback edit");
            };
            second.before = element -> assertEquals("original", element.getAsJsonObject().get("value").getAsString());
            apply(configuration, List.of(first, second));
            assertEquals("original", first.get());
            assertEquals("original", second.get());
            retained.forEach(element -> element.getAsJsonObject().addProperty("value", "retained edit"));
        }
        assertNotSame(retained.get(0), retained.get(1));
    }

    @Test
    void outsideConstructionRejectsBeforeTouchingCollectionOrSetting() {
        AtomicInteger touches = new AtomicInteger();
        Probe<Integer> codec = new Probe<>(Codec.INT);
        ObservedSetting<Integer> setting = new ObservedSetting<>("value", codec, 0);
        Collection<Setting<?>> inventory = new AbstractCollection<>() {
            @Override public Iterator<Setting<?>> iterator() { touches.incrementAndGet(); return List.<Setting<?>>of(setting).iterator(); }
            @Override public int size() { touches.incrementAndGet(); return 1; }
        };
        assertThrows(IllegalStateException.class, () -> MapInitialConfiguration.capture(
                json("{\"value\":5}")).apply(inventory));
        assertEquals(0, touches.get());
        assertEquals(0, codec.decodes);
        assertEquals(0, setting.applications);
        assertEquals(0, setting.get().intValue());
    }

    @Test
    void failedApplicationCleansEarlierResourcesPreservesSuppressedAndAllowsNextScope() {
        for (Throwable primary : List.of(new IllegalStateException("application"), new AssertionError("application"))) {
            List<String> cleanups = new ArrayList<>();
            RuntimeException cleanupFailure = new IllegalArgumentException("cleanup");
            ObservedSetting<Integer> first = new ObservedSetting<>("first", Codec.INT, 0);
            ObservedSetting<Integer> second = new ObservedSetting<>("second", Codec.INT, 0);
            second.before = ignored -> throwUnchecked(primary);
            MapInitialConfiguration configuration = MapInitialConfiguration.capture(json("{\"first\":5,\"second\":6}"));
            assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                MapConstructionScope.acquire(Object::new, ignored -> cleanups.add("earlier"));
                MapConstructionScope.acquire(Object::new, ignored -> {
                    assertFalse(MapConstructionScope.isConstructing());
                    cleanups.add("later");
                    throw cleanupFailure;
                });
                configuration.apply(List.of(first, second));
                return new Object();
            })));
            assertEquals(List.of("later", "earlier"), cleanups);
            assertArrayEquals(new Throwable[] {cleanupFailure}, primary.getSuppressed());
            assertFalse(MapConstructionScope.isConstructing());
            assertEquals(5, first.get().intValue());
            second.before = ignored -> {};
            apply(configuration, List.of(first, second));
            assertEquals(6, second.get().intValue());
            assertFalse(MapConstructionScope.isConstructing());
        }
    }

    private static void assertRejectedWithoutCalls(JsonElement input, Collection<Setting<?>> inventory,
                                                    Probe<?> codec, ObservedSetting<?>... settings) {
        assertThrows(RuntimeException.class, () -> apply(MapInitialConfiguration.capture(input), inventory));
        assertEquals(0, codec.decodes);
        for (ObservedSetting<?> setting : settings) assertEquals(0, setting.applications);
    }

    private static void apply(MapInitialConfiguration configuration, Collection<Setting<?>> settings) {
        MapConstructionScope.construct(() -> {
            configuration.apply(settings);
            return Boolean.TRUE;
        });
    }

    private static Collection<Setting<?>> countedInventory(List<Setting<?>> entries, int reportedSize,
                                                           AtomicInteger consumed) {
        return new AbstractCollection<>() {
            @Override public int size() { return reportedSize; }
            @Override public Iterator<Setting<?>> iterator() {
                Iterator<Setting<?>> delegate = entries.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return delegate.hasNext(); }
                    @Override public Setting<?> next() { consumed.incrementAndGet(); return delegate.next(); }
                };
            }
        };
    }

    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }

    private static final class ObservedSetting<T> extends Setting<T> {
        int applications;
        boolean skip;
        Runnable onCodec = () -> {};
        Consumer<JsonElement> before = ignored -> {};
        Consumer<JsonElement> after = ignored -> {};

        ObservedSetting(String name, Codec<T> codec, T initial) { super(name, codec, initial); }

        @Override public Codec<T> codec() {
            onCodec.run();
            return super.codec();
        }

        @Override public void fromJson(JsonElement input) {
            applications++;
            before.accept(input);
            if (!skip) super.fromJson(input);
            after.accept(input);
        }
    }

    private static final class Probe<T> implements Codec<T> {
        private final Codec<T> delegate;
        int decodes;
        boolean nullValue;
        T partial;
        Runnable onDecode = () -> {};

        Probe(Codec<T> delegate) { this.delegate = delegate; }

        @Override public <O> DataResult<Pair<T, O>> decode(DynamicOps<O> ops, O input) {
            decodes++;
            onDecode.run();
            if (nullValue) return DataResult.success(Pair.of(null, input));
            if (partial != null) return DataResult.error(() -> "partial configuration value", Pair.of(partial, input));
            return delegate.decode(ops, input);
        }

        @Override public <O> DataResult<O> encode(T input, DynamicOps<O> ops, O prefix) {
            return delegate.encode(input, ops, prefix);
        }
    }
}
