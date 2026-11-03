package net.ptcrys.fpsmatch.editor.core.types.values;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.types.ActivatedCodec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

/** Public registry fixtures; no shadow registry, fabricated metadata, or snapshot issuer. */
class ValueTypeCatalogTest {
    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final EditorId OWNER = id("vendor:values");
    private static final EditorId TYPE = id("vendor:note");
    private static final EditorId CODEC = id("vendor:note_codec");
    private static final DocumentDependencies EMPTY = new DocumentDependencies(Set.of(), Set.of(), Set.of());

    @Test
    void metadataIsReadOnceFrozenAndCatalogCollectionsAreImmutable() {
        assertEquals(id("editor:value_type"), ValueTypes.KIND.id());
        Probe implementation = new Probe();
        ValueTypeCatalog catalog = catalog(implementation);
        ActivatedValueType<?> type = catalog.find(TYPE).orElseThrow();
        assertEquals(List.of(1, 1, 1, 1), implementation.readCounts());
        implementation.codec = id("vendor:changed");
        implementation.schema = 7;
        implementation.readables.clear();
        assertEquals(CODEC, type.codecId());
        assertEquals(2, type.schemaVersion());
        assertEquals(List.of(1, 2), List.copyOf(type.readableSchemas()));
        assertEquals(OWNER, type.owner());
        assertEquals(TYPE, type.id());
        assertEquals(String.class, type.valueType());
        assertThrows(UnsupportedOperationException.class, () -> type.readableSchemas().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.activationFailures().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.problems().add(domain(Problem.Severity.INFO)));
        assertEquals(List.of(1, 1, 1, 1), implementation.readCounts());
    }

    @Test
    void undefinedKindFailsAssemblyAndRuntimeFailuresAreIsolatedInIdOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> ValueTypeCatalog.activate(new ExtensionRegistryBuilder(API, Contribution.Side.COMMON).freeze()));
        RuntimeException original = new IllegalStateException("private implementation detail");
        Probe broken = new Probe() { @Override public EditorId codecId() { throw original; } };
        ExtensionRegistryBuilder builder = builder();
        publish(builder, id("vendor:z_good"), new Probe());
        publish(builder, id("vendor:a_bad"), broken);
        publish(builder, id("vendor:b_bad"), new Probe() { @Override public int schemaVersion() { return 0; } });
        ValueTypeCatalog catalog = ValueTypeCatalog.activate(builder.freeze());
        assertEquals(Set.of(id("vendor:z_good")), catalog.entries().keySet());
        assertSame(original, catalog.activationFailures().get(id("vendor:a_bad")));
        assertInstanceOf(IllegalArgumentException.class, catalog.activationFailures().get(id("vendor:b_bad")));
        assertEquals(List.of("vendor:a_bad", "vendor:b_bad"), catalog.problems().stream().map(Problem::target).toList());
        diagnostic(catalog.problems().get(0), "type_activation_failed", id("vendor:a_bad"),
                new TextContent.LiteralArgument("vendor:a_bad"));
    }

    @Test
    void errorsAreNeverConvertedToHealthyPartialCatalogs() {
        AssertionError original = new AssertionError("fatal");
        Probe broken = new Probe() { @Override public EditorId codecId() { throw original; } };
        assertSame(original, assertThrows(AssertionError.class, () -> catalog(broken)));
    }

    @Test
    void codecAndSchemaAreRejectedBeforeDecoderAndDecodeOwnsItsInput() {
        Probe implementation = new Probe();
        ActivatedValueType<?> type = catalog(implementation).find(TYPE).orElseThrow();
        byte[] input = "old".getBytes(StandardCharsets.UTF_8);
        diagnostic(assertThrows(DocumentCodecException.class, () -> type.decode(id("other:codec"), 1, input))
                .problems().get(0), "unsupported_codec", TYPE,
                new TextContent.LiteralArgument(TYPE.toString()), new TextContent.LiteralArgument("other:codec"),
                new TextContent.LiteralArgument(CODEC.toString()));
        diagnostic(assertThrows(DocumentCodecException.class, () -> type.decode(CODEC, 99, input))
                .problems().get(0), "unsupported_schema", TYPE,
                new TextContent.LiteralArgument(TYPE.toString()), number(99));
        assertEquals(0, implementation.decodes);
        ValueSnapshot<?> snapshot = type.decode(CODEC, 1, input);
        assertArrayEquals("old".getBytes(StandardCharsets.UTF_8), input);
        assertNotSame(input, implementation.decoderInput);
        assertEquals("migrated:old", snapshot.content());
        assertEquals(2, snapshot.schemaVersion());
        assertArrayEquals("migrated:old".getBytes(StandardCharsets.UTF_8), snapshot.encoding());
        assertEquals(List.of("decode", "snapshot", "validate", "encode", "dependencies"), implementation.calls);
    }

    @Test
    void captureFixesEncodingBeforeDependenciesCanReuseTheBufferAndGetterCopies() {
        Probe implementation = new Probe();
        implementation.reuseBuffer = true;
        implementation.validation.add(domain(Problem.Severity.WARNING));
        implementation.validation.add(domain(Problem.Severity.INFO));
        ValueSnapshot<?> snapshot = decode(catalog(implementation));
        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), snapshot.encoding());
        assertEquals((byte) '!', implementation.encoding[0]);
        byte[] returned = snapshot.encoding();
        returned[0] = '?';
        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), snapshot.encoding());
        assertEquals(List.copyOf(implementation.validation), snapshot.problems());
        implementation.validation.clear();
        assertEquals(2, snapshot.problems().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.problems().clear());
        assertEquals(TYPE, snapshot.typeId());
        assertEquals(OWNER, snapshot.owner());
        assertEquals(CODEC, snapshot.codecId());
    }

    @Test
    void validationErrorsPreserveEveryProblemAndStopBeforeEncoding() {
        Probe implementation = new Probe();
        implementation.validation.add(domain(Problem.Severity.WARNING));
        implementation.validation.add(domain(Problem.Severity.ERROR));
        implementation.validation.add(domain(Problem.Severity.INFO));
        List<Problem> expected = List.copyOf(implementation.validation);
        ActivatedValueType<?> type = catalog(implementation).find(TYPE).orElseThrow();
        DocumentCodecException failure = assertThrows(DocumentCodecException.class,
                () -> type.decode(CODEC, 2, new byte[] {1}));
        assertEquals(expected, failure.problems());
        for (int index = 0; index < expected.size(); index++) assertSame(expected.get(index), failure.problems().get(index));
        assertEquals(List.of("decode", "snapshot", "validate"), implementation.calls);
        implementation.validation.clear();
        assertEquals(expected, failure.problems());
    }

    @Test
    void decoderDomainExceptionSurvivesWithoutSubsequentCapture() {
        DocumentCodecException original = new DocumentCodecException(List.of(domain(Problem.Severity.ERROR)));
        Probe implementation = new Probe() {
            @Override public String decode(int schema, byte[] bytes) { throw original; }
        };
        ActivatedValueType<?> type = catalog(implementation).find(TYPE).orElseThrow();
        assertSame(original, assertThrows(DocumentCodecException.class, () -> type.decode(CODEC, 2, new byte[0])));
        assertTrue(implementation.calls.isEmpty());
    }

    @Test
    void eightMiBIsAcceptedAndLargerDecodeIsRejectedBeforeAnyPluginCall() {
        assertEquals(8 * 1024 * 1024, ValueTypeCatalog.MAX_VALUE_BYTES);
        Probe implementation = new Probe();
        implementation.encodingLength = ValueTypeCatalog.MAX_VALUE_BYTES;
        ActivatedValueType<?> type = catalog(implementation).find(TYPE).orElseThrow();
        assertEquals(ValueTypeCatalog.MAX_VALUE_BYTES,
                type.decode(CODEC, 2, new byte[ValueTypeCatalog.MAX_VALUE_BYTES]).encoding().length);
        implementation.calls.clear();
        diagnostic(assertThrows(DocumentCodecException.class,
                () -> type.decode(CODEC, 2, new byte[ValueTypeCatalog.MAX_VALUE_BYTES + 1])).problems().get(0),
                "value_bytes", TYPE, new TextContent.LiteralArgument(TYPE.toString()),
                number(ValueTypeCatalog.MAX_VALUE_BYTES + 1), number(ValueTypeCatalog.MAX_VALUE_BYTES));
        assertTrue(implementation.calls.isEmpty(), "oversize input must not reach even the decoder");
        implementation.encodingLength++;
        diagnostic(assertThrows(DocumentCodecException.class,
                () -> type.decode(CODEC, 2, new byte[0])).problems().get(0),
                "value_bytes", TYPE, new TextContent.LiteralArgument(TYPE.toString()),
                number(ValueTypeCatalog.MAX_VALUE_BYTES + 1), number(ValueTypeCatalog.MAX_VALUE_BYTES));
        assertFalse(implementation.calls.contains("dependencies"));
    }

    @Test
    void dependencyBudgetCountsSelfOnceAndDoesNotTruncate() {
        assertEquals(8192, ValueTypeCatalog.MAX_DEPENDENCIES);
        Probe implementation = new Probe();
        Set<UUID> documents = documents(8191);
        implementation.dependencies = new DocumentDependencies(documents, Set.of(), Set.of());
        ValueSnapshot<?> accepted = decode(catalog(implementation));
        assertEquals(documents, accepted.dependencies().documents());
        assertEquals(Set.of(self(TYPE)), accepted.dependencies().contributions());
        implementation.dependencies = new DocumentDependencies(documents(8192), Set.of(), Set.of());
        diagnostic(assertThrows(DocumentCodecException.class, () -> decode(catalog(implementation))).problems().get(0),
                "value_dependencies", TYPE, new TextContent.LiteralArgument(TYPE.toString()), number(8193), number(8192));
        implementation.dependencies = new DocumentDependencies(documents, Set.of(), Set.of(self(TYPE)));
        assertEquals(8191, decode(catalog(implementation)).dependencies().documents().size());
    }

    @Test
    void realThirdPartyNestedContributionAndTwoLockedResourceVersionsSurvive() {
        EditorId nested = id("other:nested");
        EditorId nestedOwner = id("other:module");
        UUID resource = new UUID(5, 6);
        Set<ResourceRef> locked = Set.of(new ResourceRef(resource, new UUID(0, 1)),
                new ResourceRef(resource, new UUID(0, 2)));
        Probe child = new Probe();
        child.dependencies = new DocumentDependencies(Set.of(), locked, Set.of());
        ExtensionRegistryBuilder builder = builder();
        builder.addModule(new ModuleSpec(nestedOwner, "2.3.4", API, Set.of(), Set.of()));
        builder.add(ValueTypes.KIND, new Contribution(self(nested), nestedOwner, Contribution.Side.COMMON, Set.of()), child);
        Probe parent = new Probe();
        parent.dependencies = new DocumentDependencies(Set.of(), locked, Set.of(self(nested)));
        publish(builder, TYPE, parent);
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty());
        assertSame(child, registry.find(ValueTypes.KIND, nested).orElseThrow());
        ValueTypeCatalog catalog = ValueTypeCatalog.activate(registry);
        ValueSnapshot<?> snapshot = decode(catalog);
        assertEquals(Set.of(self(TYPE), self(nested)), snapshot.dependencies().contributions());
        assertEquals(locked, snapshot.dependencies().resources());
        assertEquals(nestedOwner, catalog.find(nested).orElseThrow().owner());
        assertEquals(locked, catalog.find(nested).orElseThrow().decode(CODEC, 2, new byte[0]).dependencies().resources());
    }

    @Test
    void ownershipIsIssuerIdentityAcrossRegistriesAndRepeatedActivation() {
        Probe implementation = new Probe();
        ExtensionRegistry registry = registry(implementation);
        ValueTypeCatalog first = ValueTypeCatalog.activate(registry);
        ValueTypeCatalog repeated = ValueTypeCatalog.activate(registry);
        ValueTypeCatalog independent = catalog(implementation);
        ValueSnapshot<?> snapshot = decode(first);
        assertTrue(first.find(TYPE).orElseThrow().owns(snapshot));
        assertFalse(repeated.find(TYPE).orElseThrow().owns(snapshot));
        assertFalse(independent.find(TYPE).orElseThrow().owns(snapshot));
        assertThrows(NullPointerException.class, () -> first.find(TYPE).orElseThrow().owns(null));
    }

    @Test
    void sharedCodecRetainsUncopiedEncodingContractAndRefusesNullOutputs() {
        Probe implementation = new Probe();
        ActivatedCodec<String> codec = new ActivatedCodec<>(TYPE, implementation);
        byte[] encoding = codec.encode("value");
        assertSame(implementation.encoding, encoding);
        assertThrows(NullPointerException.class, () -> codec.snapshot(null));
        Probe nullSnapshot = new Probe() { @Override public String snapshot(String value) { return null; } };
        assertThrows(NullPointerException.class, () -> new ActivatedCodec<>(TYPE, nullSnapshot).snapshot("x"));
        Probe nullDecode = new Probe() { @Override public String decode(int schema, byte[] bytes) { return null; } };
        assertThrows(NullPointerException.class, () -> new ActivatedCodec<>(TYPE, nullDecode).decode(CODEC, 2, new byte[0]));
        List<Probe> nullCaptureOutputs = List.of(
                new Probe() { @Override public byte[] encode(String value) { return null; } },
                new Probe() { @Override public List<Problem> validate(String value) { return null; } },
                new Probe() { @Override public DocumentDependencies dependencies(String value) { return null; } });
        for (Probe nullOutput : nullCaptureOutputs) {
            assertThrows(NullPointerException.class, () -> decode(catalog(nullOutput)));
        }
    }

    @Test
    void sharedCodecChecksTheDeclaredRuntimeClassBeforeCallbacksAndAfterOutputs() {
        LyingClass implementation = new LyingClass();
        ActivatedCodec<Object> codec = new ActivatedCodec<>(TYPE, implementation);
        assertThrows(ClassCastException.class, () -> codec.snapshot(Integer.valueOf(3)));
        assertFalse(implementation.called, "wrong input must not reach the plugin");
        assertThrows(ClassCastException.class, () -> codec.snapshot("valid input"));
        assertTrue(implementation.called);
        assertThrows(ClassCastException.class, () -> codec.decode(CODEC, 2, new byte[0]));
    }

    /** Only the metadata Class lies; no unchecked cast is used in a payload path. */
    private static final class LyingClass implements ValueType<Object> {
        boolean called;
        @Override @SuppressWarnings("unchecked")
        public Class<Object> valueType() { return (Class<Object>) (Class<?>) String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 2; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1, 2); }
        @Override public Object decode(int schema, byte[] bytes) { return Integer.valueOf(1); }
        @Override public Object snapshot(Object value) { called = true; return Integer.valueOf(1); }
        @Override public byte[] encode(Object value) { return new byte[0]; }
        @Override public List<Problem> validate(Object value) { return List.of(); }
        @Override public DocumentDependencies dependencies(Object value) { return EMPTY; }
    }

    @Test
    void captureDoesNotDecodeOrReinterpretTheCapturedContent() {
        Probe implementation = new Probe();
        captureAgain(catalog(implementation).find(TYPE).orElseThrow(), implementation);
    }

    private static <T> void captureAgain(ActivatedValueType<T> type, Probe implementation) {
        ValueSnapshot<T> decoded = type.decode(CODEC, 2, "value".getBytes(StandardCharsets.UTF_8));
        implementation.calls.clear();
        int decodes = implementation.decodes;
        ValueSnapshot<T> captured = type.capture(decoded.content());
        assertEquals("value", captured.content());
        assertEquals(decodes, implementation.decodes);
        assertEquals(List.of("snapshot", "validate", "encode", "dependencies"), implementation.calls);
    }

    @Test
    void everySharedMetadataConstraintRejectsOnlyItsOwnRegistration() {
        List<ValueType<?>> invalid = List.of(
                new Probe() { @Override public Class<String> valueType() { return null; } },
                new Primitive(),
                new Probe() { @Override public EditorId codecId() { return null; } },
                new Probe() { @Override public int schemaVersion() { return 0; } },
                new Probe() { @Override public Set<Integer> readableSchemas() { return null; } },
                new Probe() { @Override public Set<Integer> readableSchemas() { return Set.of(); } },
                new Probe() { @Override public Set<Integer> readableSchemas() { return Set.of(1); } },
                new Probe() { @Override public Set<Integer> readableSchemas() { return Set.of(0, 2); } },
                new Probe() { @Override public Set<Integer> readableSchemas() {
                    Set<Integer> schemas = new LinkedHashSet<>(List.of(1, 2)); schemas.add(null); return schemas;
                } },
                new Probe() { @Override public Set<Integer> readableSchemas() { return schemas(257); } });
        for (ValueType<?> implementation : invalid) {
            ExtensionRegistryBuilder builder = builder();
            publish(builder, TYPE, implementation);
            publish(builder, id("vendor:healthy"), new Probe());
            ValueTypeCatalog catalog = ValueTypeCatalog.activate(builder.freeze());
            assertTrue(catalog.find(TYPE).isEmpty());
            assertTrue(catalog.find(id("vendor:healthy")).isPresent());
            assertInstanceOf(IllegalArgumentException.class, catalog.activationFailures().get(TYPE));
            assertEquals(1, catalog.problems().size());
        }
        Probe maximum = new Probe() { @Override public Set<Integer> readableSchemas() { return schemas(256); } };
        assertEquals(256, catalog(maximum).find(TYPE).orElseThrow().readableSchemas().size());
    }

    private static Set<Integer> schemas(int count) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int schema = 1; schema <= count; schema++) result.add(schema);
        return result;
    }

    private static final class Primitive implements ValueType<Integer> {
        @Override public Class<Integer> valueType() { return Integer.TYPE; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 2; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1, 2); }
        @Override public Integer decode(int schema, byte[] bytes) { return 0; }
        @Override public Integer snapshot(Integer value) { return value; }
        @Override public byte[] encode(Integer value) { return new byte[0]; }
        @Override public List<Problem> validate(Integer value) { return List.of(); }
        @Override public DocumentDependencies dependencies(Integer value) { return EMPTY; }
    }

    static class Probe implements ValueType<String> {
        final int[] reads = new int[4];
        EditorId codec = CODEC;
        int schema = 2;
        final Set<Integer> readables = new LinkedHashSet<>(List.of(2, 1));
        final List<String> calls = new ArrayList<>();
        final List<Problem> validation = new ArrayList<>();
        DocumentDependencies dependencies = EMPTY;
        byte[] encoding;
        byte[] decoderInput;
        int decodes;
        int encodingLength = -1;
        boolean reuseBuffer;
        @Override public Class<String> valueType() { reads[0]++; return String.class; }
        @Override public EditorId codecId() { reads[1]++; return codec; }
        @Override public int schemaVersion() { reads[2]++; return schema; }
        @Override public Set<Integer> readableSchemas() { reads[3]++; return readables; }
        @Override public String decode(int sourceSchema, byte[] bytes) {
            calls.add("decode"); decodes++; decoderInput = bytes;
            String value = new String(bytes, StandardCharsets.UTF_8);
            Arrays.fill(bytes, (byte) '!');
            return sourceSchema == 1 ? "migrated:" + value : value;
        }
        @Override public String snapshot(String value) { calls.add("snapshot"); return value; }
        @Override public List<Problem> validate(String value) { calls.add("validate"); return validation; }
        @Override public byte[] encode(String value) {
            calls.add("encode");
            encoding = encodingLength < 0 ? value.getBytes(StandardCharsets.UTF_8) : new byte[encodingLength];
            return encoding;
        }
        @Override public DocumentDependencies dependencies(String value) {
            calls.add("dependencies");
            if (reuseBuffer) Arrays.fill(encoding, (byte) '!');
            return dependencies;
        }
        List<Integer> readCounts() { return Arrays.stream(reads).boxed().toList(); }
    }

    private static ExtensionRegistryBuilder builder() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.define(ValueTypes.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1.2.3", API, Set.of(), Set.of()));
        return builder;
    }
    private static void publish(ExtensionRegistryBuilder builder, EditorId type, ValueType<?> implementation) {
        builder.add(ValueTypes.KIND, new Contribution(self(type), OWNER, Contribution.Side.COMMON, Set.of()), implementation);
    }
    private static ExtensionRegistry registry(ValueType<?> implementation) {
        ExtensionRegistryBuilder builder = builder(); publish(builder, TYPE, implementation);
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty());
        return registry;
    }
    private static ValueTypeCatalog catalog(ValueType<?> implementation) { return ValueTypeCatalog.activate(registry(implementation)); }
    private static ValueSnapshot<?> decode(ValueTypeCatalog catalog) {
        return catalog.find(TYPE).orElseThrow().decode(CODEC, 2, "value".getBytes(StandardCharsets.UTF_8));
    }
    private static Set<UUID> documents(int count) {
        Set<UUID> result = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) result.add(new UUID(0, index));
        return result;
    }
    private static ContributionKey self(EditorId type) { return new ContributionKey(ValueTypes.KIND.id(), type); }
    private static EditorId id(String text) { return EditorId.parse(text); }
    private static TextContent.NumberArgument number(int value) { return new TextContent.NumberArgument(BigDecimal.valueOf(value)); }
    private static Problem domain(Problem.Severity severity) {
        return new Problem(severity, id("vendor:validation"), TYPE.toString(), new TextContent.Literal(severity.name()));
    }
    private static void diagnostic(Problem problem, String suffix, EditorId target, TextContent.Argument... arguments) {
        assertEquals(id("editor:codec/" + suffix), problem.code());
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(target.toString(), problem.target());
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals("editor.codec." + suffix, message.key());
        assertEquals(List.of(arguments), message.arguments());
        for (String language : List.of("zh_cn", "en_us")) {
            assertFalse(LangResources.rendered(LangResources.load(language), message).isBlank());
        }
    }
}
