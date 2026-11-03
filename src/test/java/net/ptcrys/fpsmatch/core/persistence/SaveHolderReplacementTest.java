package net.ptcrys.fpsmatch.core.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.ptcrys.fpsmatch.core.persistence.datafixer.DataFixer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SaveHolderReplacementTest {
    @TempDir Path directory;

    record Value(String name) {}

    static final Codec<Value> VALUE_CODEC = Codec.STRING.fieldOf("name").codec().xmap(Value::new, Value::name);
    static final Value CURRENT = new Value("current");

    static final class Probe<T> implements Codec<T> {
        final Codec<T> delegate;
        int decodes;
        int encodes;
        Throwable decodeFailure;
        Throwable encodeFailure;
        boolean nullDecodedValue;
        T partial;

        Probe(Codec<T> delegate) { this.delegate = delegate; }

        @Override
        public <O> DataResult<Pair<T, O>> decode(DynamicOps<O> ops, O input) {
            decodes++;
            rethrow(decodeFailure);
            if (nullDecodedValue) return DataResult.success(Pair.of(null, input));
            if (partial != null) return DataResult.error(() -> "incomplete definition", Pair.of(partial, input));
            return delegate.decode(ops, input);
        }

        @Override
        public <O> DataResult<O> encode(T input, DynamicOps<O> ops, O prefix) {
            encodes++;
            rethrow(encodeFailure);
            return delegate.encode(input, ops, prefix);
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }

    static JsonElement json(String text) { return JsonParser.parseString(text); }

    static JsonElement envelope(int version, String name) {
        return json("{\"version\":" + version + ",\"data\":{\"name\":\"" + name + "\"}}");
    }

    static SaveHolder<Value> replacement(Probe<Value> runtime, Codec<?> validation, int version) {
        return new SaveHolder.Builder<>(runtime).withVersion(version).withValidatedReplacement(validation)
                .withLoadHandler(value -> fail("save must not publish an old runtime value")).build();
    }

    @SuppressWarnings("unchecked")
    private static void identify(SaveHolder<Value> holder, Class<?> marker) {
        holder.setHolderClass((Class<Value>) marker);
    }

    @Test
    void replacementValidatesWithoutRuntimeDecodeOrReadAndReturnsCurrentIdentity() {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        runtime.decodeFailure = new AssertionError("runtime construction during save");
        Probe<Value> validation = new Probe<>(VALUE_CODEC);
        SaveHolder<Value> holder = replacement(runtime, validation, 1);
        assertSame(CURRENT, holder.mergeForSave(envelope(1, "old"), CURRENT));
        assertEquals(1, validation.decodes);
        assertEquals(0, runtime.decodes);
        assertEquals(0, runtime.encodes);
        assertEquals(0, validation.encodes);
    }

    @Test
    void validationCodecCanHaveADifferentValueType() {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        Probe<String> validation = new Probe<>(Codec.STRING.fieldOf("name").codec());
        assertSame(CURRENT, replacement(runtime, validation, 1).mergeForSave(envelope(1, "old"), CURRENT));
        assertEquals(1, validation.decodes);
        assertEquals(0, runtime.decodes);
    }

    @Test
    void defaultCustomAndExplicitNullMergersRetainRuntimeDecoding() {
        for (int mode = 0; mode < 3; mode++) {
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            AtomicInteger merges = new AtomicInteger();
            SaveHolder.Builder<Value> builder = new SaveHolder.Builder<>(runtime);
            if (mode == 1) builder.withMergeHandler((old, current) -> {
                merges.incrementAndGet();
                assertSame(CURRENT, current);
                return new Value(old.name() + "+" + current.name());
            });
            if (mode == 2) builder.withMergeHandler(null);
            Value merged = builder.build().mergeForSave(envelope(1, "old"), CURRENT);
            if (mode == 1) assertEquals(new Value("old+current"), merged);
            else assertSame(CURRENT, merged);
            assertEquals(1, runtime.decodes);
            assertEquals(mode == 1 ? 1 : 0, merges.get());
        }
    }

    @Test
    void defaultInterfaceMergeStillDispatchesThirdPartyOverrides() {
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger merges = new AtomicInteger();
        ISavePort<Value> port = new ISavePort<>() {
            public Codec<Value> codec() { return VALUE_CODEC; }
            public int getVersion() { return 1; }
            public Class<?> getHolderClass() { return Value.class; }
            public Value decodeFromJson(JsonElement input) {
                decodes.incrementAndGet();
                return new Value("override");
            }
            public Value mergeHandler(Value old, Value current) {
                merges.incrementAndGet();
                assertEquals(new Value("override"), old);
                assertSame(CURRENT, current);
                return old;
            }
        };
        assertEquals(new Value("override"), port.mergeForSave(envelope(1, "old"), CURRENT));
        assertEquals(1, decodes.get());
        assertEquals(1, merges.get());
    }

    @Test
    void replacementRejectsCustomMergeAtBuildInEitherOrderButAllowsNull() {
        SaveHolder.Builder<Value> first = new SaveHolder.Builder<>(VALUE_CODEC)
                .withMergeHandler((old, current) -> old).withValidatedReplacement(VALUE_CODEC);
        SaveHolder.Builder<Value> second = new SaveHolder.Builder<>(VALUE_CODEC)
                .withValidatedReplacement(VALUE_CODEC).withMergeHandler((old, current) -> old);
        assertThrows(IllegalStateException.class, first::build);
        assertThrows(IllegalStateException.class, second::build);
        assertThrows(NullPointerException.class,
                () -> new SaveHolder.Builder<>(VALUE_CODEC).withValidatedReplacement(null));
        for (boolean replacementFirst : new boolean[] {false, true}) {
            SaveHolder.Builder<Value> builder = new SaveHolder.Builder<>(VALUE_CODEC);
            if (replacementFirst) builder.withValidatedReplacement(VALUE_CODEC).withMergeHandler(null);
            else builder.withMergeHandler(null).withValidatedReplacement(VALUE_CODEC);
            assertSame(CURRENT, builder.build().mergeForSave(envelope(1, "old"), CURRENT));
        }
    }

    @Test
    void envelopeRulesAcceptBareVersionZeroMissingDataAndMissingVersion() {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        SaveHolder<Value> holder = replacement(runtime, VALUE_CODEC, 0);
        for (String old : new String[] {"{\"name\":\"old\"}",
                "{\"version\":0,\"name\":\"old\"}", "{\"data\":{\"name\":\"old\"}}"}) {
            assertSame(CURRENT, holder.mergeForSave(json(old), CURRENT));
        }
        SaveHolder<String> bare = new SaveHolder.Builder<>(Codec.STRING).withVersion(0)
                .withValidatedReplacement(Codec.STRING).build();
        String current = new String("current");
        assertSame(current, bare.mergeForSave(json("\"old\""), current));
        assertEquals(0, runtime.decodes);
    }

    @Test
    void replacementMigratesEveryStepBeforeValidationWithoutRuntimeDecode() {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        Probe<Value> validation = new Probe<>(VALUE_CODEC);
        SaveHolder<Value> holder = replacement(runtime, validation, 2);
        identify(holder, Migration.class);
        AtomicInteger steps = new AtomicInteger();
        DataFixer.getInstance().registerJsonFixer(Migration.class, 0, old -> {
            steps.incrementAndGet();
            assertEquals("legacy", old.getAsJsonObject().get("legacy").getAsString());
            return json("{\"intermediate\":\"old\"}");
        });
        DataFixer.getInstance().registerJsonFixer(Migration.class, 1, old -> {
            steps.incrementAndGet();
            assertTrue(old.getAsJsonObject().has("intermediate"));
            return json("{\"name\":\"fixed\"}");
        });
        assertSame(CURRENT, holder.mergeForSave(json("{\"legacy\":\"legacy\"}"), CURRENT));
        assertEquals(2, steps.get());
        assertEquals(1, validation.decodes);
        assertEquals(0, runtime.decodes);
    }

    @Test
    void partialValidationResultIsRejected() {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        Probe<Value> validation = new Probe<>(VALUE_CODEC);
        validation.partial = new Value("partial");
        assertThrows(RuntimeException.class,
                () -> replacement(runtime, validation, 1).mergeForSave(envelope(1, "old"), CURRENT));
        assertEquals(0, runtime.decodes);
        assertEquals(0, runtime.encodes);
    }

    @Test
    void missingIntermediateFixerPreventsValidationAndWriting() throws Exception {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        Probe<Value> validation = new Probe<>(VALUE_CODEC);
        SaveHolder<Value> holder = replacement(runtime, validation, 2);
        identify(holder, MissingIntermediateFixer.class);
        AtomicInteger migrations = new AtomicInteger();
        DataFixer.getInstance().registerJsonFixer(MissingIntermediateFixer.class, 0, old -> {
            migrations.incrementAndGet();
            return json("{\"name\":\"first step\"}");
        });
        Path file = directory.resolve("item.json");
        Files.writeString(file, envelope(0, "old").toString());
        byte[] before = Files.readAllBytes(file);
        holder.getWriter(CURRENT, "item", false).accept(directory.toFile());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(1, migrations.get());
        assertEquals(0, validation.decodes);
        assertEquals(0, runtime.decodes);
        assertEquals(0, runtime.encodes);
    }

    @Test
    void validationAndMigrationFailuresPreserveOriginalThrowableIdentity() {
        for (Throwable failure : new Throwable[] {new IllegalArgumentException("validation"), new AssertionError("validation")}) {
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            Probe<Value> validation = new Probe<>(VALUE_CODEC);
            validation.decodeFailure = failure;
            SaveHolder<Value> holder = replacement(runtime, validation, 1);
            assertSame(failure, assertThrows(failure.getClass(), () -> holder.mergeForSave(envelope(1, "old"), CURRENT)));
            identify(holder, FailedMigration.class);
            validation.decodeFailure = null;
            DataFixer.getInstance().registerJsonFixer(FailedMigration.class, 0, old -> {
                if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                throw (Error) failure;
            });
            assertSame(failure, assertThrows(failure.getClass(), () -> holder.mergeForSave(envelope(0, "old"), CURRENT)));
            assertEquals(1, validation.decodes);
            assertEquals(0, runtime.decodes);
            assertEquals(0, runtime.encodes);
        }
    }

    @Test
    void explicitDecodeRetainsSameVersionOverrideAndMigratedDirectCodecDispatch() {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        AtomicInteger overrides = new AtomicInteger();
        ISavePort<Value> port = new ISavePort<>() {
            public Codec<Value> codec() { return runtime; }
            public int getVersion() { return 1; }
            public Class<?> getHolderClass() { return DecodeDispatch.class; }
            public Value decode(JsonElement input) {
                overrides.incrementAndGet();
                return new Value("overridden");
            }
        };
        DataFixer.getInstance().registerJsonFixer(DecodeDispatch.class, 0, old -> json("{\"name\":\"migrated\"}"));
        assertEquals(new Value("overridden"), port.decodeFromJson(envelope(1, "old")));
        assertEquals(new Value("migrated"), port.decodeFromJson(envelope(0, "old")));
        assertEquals(1, overrides.get());
        assertEquals(1, runtime.decodes);
    }

    @Test
    void realWriterUsesReplacementAndPreservesBytesBeforeOpeningOutputOnRejectedInput() throws Exception {
        for (String old : new String[] {"{\"version\":1,\"data\":{}}", "{\"version\":2,\"data\":{\"name\":\"old\"}}",
                "{\"version\":0,\"data\":{\"name\":\"old\"}}", "{ broken json"}) {
            Path file = directory.resolve("item.json");
            Files.writeString(file, "  " + old + "\n");
            byte[] before = Files.readAllBytes(file);
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            SaveHolder<Value> holder = replacement(runtime, VALUE_CODEC, 1);
            identify(holder, NoFixer.class);
            holder.getWriter(CURRENT, "item", false).accept(directory.toFile());
            assertArrayEquals(before, Files.readAllBytes(file), old);
            assertEquals(0, runtime.decodes);
            assertEquals(0, runtime.encodes);
        }
    }

    @Test
    void realWriterValidatesOldAndWritesCurrentWithoutRuntimeDecode() throws Exception {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        runtime.decodeFailure = new AssertionError("runtime decode");
        Probe<Value> validation = new Probe<>(VALUE_CODEC);
        Files.writeString(directory.resolve("item.json"), envelope(1, "old").toString());
        replacement(runtime, validation, 1).getWriter(CURRENT, "item", false).accept(directory.toFile());
        assertEquals(envelope(1, "current"), json(Files.readString(directory.resolve("item.json"))));
        assertEquals(1, validation.decodes);
        assertEquals(0, runtime.decodes);
        assertEquals(1, runtime.encodes);
    }

    @Test
    void realWriterRetainsDefaultCustomAndNullMergeSemantics() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            AtomicInteger merges = new AtomicInteger();
            SaveHolder.Builder<Value> builder = new SaveHolder.Builder<>(runtime);
            if (mode == 1) builder.withMergeHandler((old, current) -> {
                merges.incrementAndGet();
                return new Value(old.name() + "+" + current.name());
            });
            if (mode == 2) builder.withMergeHandler(null);
            Path file = directory.resolve("item.json");
            Files.writeString(file, envelope(1, "old").toString());
            builder.build().getWriter(CURRENT, "item", false).accept(directory.toFile());
            assertEquals(envelope(1, mode == 1 ? "old+current" : "current"), json(Files.readString(file)));
            assertEquals(1, runtime.decodes);
            assertEquals(1, runtime.encodes);
            assertEquals(mode == 1 ? 1 : 0, merges.get());
        }
    }

    @Test
    void realWriterPassesSuccessfullyDecodedNullToCustomMergerAndEncodesItsResult() throws Exception {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        runtime.nullDecodedValue = true;
        AtomicInteger merges = new AtomicInteger();
        SaveHolder<Value> holder = new SaveHolder.Builder<>(runtime).withMergeHandler((old, current) -> {
            merges.incrementAndGet();
            assertNull(old);
            assertSame(CURRENT, current);
            return new Value("merged-null-old");
        }).build();
        Path file = directory.resolve("item.json");
        Files.writeString(file, envelope(1, "old").toString());
        holder.getWriter(CURRENT, "item", false).accept(directory.toFile());
        assertEquals(envelope(1, "merged-null-old"), json(Files.readString(file)));
        assertEquals(1, runtime.decodes);
        assertEquals(1, merges.get());
        assertEquals(1, runtime.encodes);
    }

    @Test
    void realWriterMigratesBeforeValidationAndRejectsPartialWithoutWriting() throws Exception {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        Probe<Value> validation = new Probe<>(VALUE_CODEC);
        SaveHolder<Value> holder = replacement(runtime, validation, 1);
        identify(holder, SuccessfulWriterMigration.class);
        AtomicInteger migrations = new AtomicInteger();
        DataFixer.getInstance().registerJsonFixer(SuccessfulWriterMigration.class, 0, old -> {
            migrations.incrementAndGet();
            return json("{\"name\":\"migrated\"}");
        });
        Path file = directory.resolve("item.json");
        Files.writeString(file, envelope(0, "old").toString());
        holder.getWriter(CURRENT, "item", false).accept(directory.toFile());
        assertEquals(envelope(1, "current"), json(Files.readString(file)));
        assertEquals(1, migrations.get());
        assertEquals(1, validation.decodes);
        assertEquals(0, runtime.decodes);
        assertEquals(1, runtime.encodes);
        byte[] before = Files.readAllBytes(file);
        validation.partial = new Value("partial");
        holder.getWriter(CURRENT, "item", false).accept(directory.toFile());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(2, validation.decodes);
        assertEquals(1, runtime.encodes);
    }

    @Test
    void choosingReplacementDoesNotChangeExplicitReadCodecOrLoadHandler() {
        Probe<Value> runtime = new Probe<>(VALUE_CODEC);
        Probe<Value> validation = new Probe<>(VALUE_CODEC);
        validation.decodeFailure = new AssertionError("read must use runtime codec");
        AtomicInteger reads = new AtomicInteger();
        SaveHolder<Value> holder = new SaveHolder.Builder<>(runtime).withValidatedReplacement(validation)
                .withLoadHandler(old -> {
                    assertEquals(new Value("old"), old);
                    reads.incrementAndGet();
                }).build();
        Value decoded = holder.decodeFromJson(envelope(1, "old"));
        holder.readHandler().accept(decoded);
        assertEquals(new Value("old"), decoded);
        assertEquals(1, runtime.decodes);
        assertEquals(0, validation.decodes);
        assertEquals(1, reads.get());
    }

    @Test
    void realWriterSkipsOldValidationForMissingEmptyAndOverwrite() throws Exception {
        for (String state : new String[] {"missing", "empty", "overwrite"}) {
            Path folder = Files.createDirectory(directory.resolve(state));
            if (!state.equals("missing")) Files.writeString(folder.resolve("item.json"), state.equals("empty") ? "" : "broken");
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            Probe<Value> validation = new Probe<>(VALUE_CODEC);
            validation.decodeFailure = new AssertionError("must skip old input");
            replacement(runtime, validation, 1).getWriter(CURRENT, "item", state.equals("overwrite")).accept(folder.toFile());
            assertEquals(envelope(1, "current"), json(Files.readString(folder.resolve("item.json"))));
            assertEquals(0, validation.decodes);
            assertEquals(0, runtime.decodes);
            assertEquals(1, runtime.encodes);
        }
    }

    @Test
    void realWriterPreservesOldBytesOnValidationMigrationAndEncodingFailures() throws Exception {
        for (String stage : new String[] {"validation", "migration", "encoding"}) {
            Path file = directory.resolve("item.json");
            Files.writeString(file, "  " + envelope(stage.equals("migration") ? 0 : 1, "old") + "\n");
            byte[] before = Files.readAllBytes(file);
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            Probe<Value> validation = new Probe<>(VALUE_CODEC);
            IllegalArgumentException failure = new IllegalArgumentException(stage);
            if (stage.equals("validation")) validation.decodeFailure = failure;
            if (stage.equals("encoding")) runtime.encodeFailure = failure;
            SaveHolder<Value> holder = replacement(runtime, validation, 1);
            identify(holder, WriterMigration.class);
            DataFixer.getInstance().registerJsonFixer(WriterMigration.class, 0, old -> { throw failure; });
            holder.getWriter(CURRENT, "item", false).accept(directory.toFile());
            assertArrayEquals(before, Files.readAllBytes(file), stage);
            assertEquals(0, runtime.decodes);
            assertEquals(stage.equals("encoding") ? 1 : 0, runtime.encodes);
        }
    }

    @Test
    void realWriterPropagatesOriginalErrorsAndPreservesOldBytes() throws Exception {
        for (String stage : new String[] {"validation", "migration", "encoding"}) {
            Path file = directory.resolve("item.json");
            Files.writeString(file, envelope(stage.equals("migration") ? 0 : 1, "old").toString());
            byte[] before = Files.readAllBytes(file);
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            Probe<Value> validation = new Probe<>(VALUE_CODEC);
            AssertionError failure = new AssertionError(stage);
            if (stage.equals("validation")) validation.decodeFailure = failure;
            if (stage.equals("encoding")) runtime.encodeFailure = failure;
            SaveHolder<Value> holder = replacement(runtime, validation, 1);
            identify(holder, ErrorWriterMigration.class);
            DataFixer.getInstance().registerJsonFixer(ErrorWriterMigration.class, 0, old -> { throw failure; });
            assertSame(failure, assertThrows(AssertionError.class, () -> holder.getWriter(CURRENT, "item", false).accept(directory.toFile())));
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(0, runtime.decodes);
            assertEquals(stage.equals("encoding") ? 1 : 0, runtime.encodes);
        }
    }

    private static final class Migration {}
    private static final class FailedMigration {}
    private static final class DecodeDispatch {}
    private static final class NoFixer {}
    private static final class WriterMigration {}
    private static final class SuccessfulWriterMigration {}
    private static final class ErrorWriterMigration {}
    private static final class MissingIntermediateFixer {}
}
