package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import net.ptcrys.fpsmatch.editor.core.types.values.ActivatedValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueSnapshot;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

/** Binding runs against actual frozen registries and routes create through the bound instance. */
class DocumentBindingTest {
    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final EditorId OWNER = id("binding:module");
    private static final EditorId DOC = id("binding:document");
    private static final EditorId VALUE = id("third:embedded");
    private static final EditorId SECOND_VALUE = id("third:second");
    private static final EditorId CODEC = id("binding:codec");
    private static final DocumentDependencies EMPTY = new DocumentDependencies(Set.of(), Set.of(), Set.of());

    @Test
    void onePrototypeBindsIndependentlyToTwoRegistriesWithoutReadingBusinessData() {
        List<Bound> bindings = new ArrayList<>();
        Prototype prototype = new Prototype(registry -> {
            ValueTypeCatalog values = ValueTypeCatalog.activate(registry);
            Bound bound = new Bound(values.find(VALUE).orElseThrow(), values.find(SECOND_VALUE).orElseThrow());
            bindings.add(bound);
            return bound;
        });
        ExtensionRegistry firstRegistry = registry(prototype, "first");
        ExtensionRegistry secondRegistry = registry(prototype, "second");
        DocumentTypeCatalog first = DocumentTypeCatalog.activate(firstRegistry);
        DocumentTypeCatalog second = DocumentTypeCatalog.activate(secondRegistry);
        assertTrue(first.problems().isEmpty());
        assertTrue(second.problems().isEmpty());
        assertEquals(List.of(firstRegistry, secondRegistry), prototype.registries);
        assertEquals(0, prototype.creates, "the prototype is never a document service");
        assertNull(bindings.get(0).captured, "binding must not read business payload");
        assertNull(bindings.get(1).captured);
        assertEquals("first:aux-first", first.find(DOC).orElseThrow().create());
        assertEquals("second:aux-second", second.find(DOC).orElseThrow().create());
        assertEquals("first:aux-first", first.find(DOC).orElseThrow().create(), "later binding cannot replace the first registry's codecs");
        ValueSnapshot<?> firstSnapshot = bindings.get(0).captured;
        ValueSnapshot<?> secondSnapshot = bindings.get(1).captured;
        assertTrue(bindings.get(0).value.owns(firstSnapshot));
        assertFalse(bindings.get(1).value.owns(firstSnapshot));
        assertTrue(bindings.get(1).value.owns(secondSnapshot));
        assertEquals(Set.of(new ContributionKey(ValueTypes.KIND.id(), VALUE)), firstSnapshot.dependencies().contributions());
        assertEquals(Set.of(new ContributionKey(ValueTypes.KIND.id(), SECOND_VALUE)),
                bindings.get(0).secondCaptured.dependencies().contributions());
        assertFalse(bindings.get(1).secondValue.owns(bindings.get(0).secondCaptured));
        assertEquals(List.of(2, 2, 2, 2), prototype.readCounts());
        for (Bound bound : bindings) assertEquals(List.of(1, 1, 1, 1), bound.readCounts());
    }

    @Test
    void returningSameInstanceDoesNotReadMetadataTwice() {
        Base same = new Base() {
            @Override public DocumentType<String> bind(ExtensionRegistry registry) { binds++; return this; }
        };
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry(same, "unused"));
        assertTrue(catalog.problems().isEmpty());
        assertEquals(List.of(1, 1, 1, 1), same.readCounts());
        assertEquals(1, same.binds);
        assertEquals("base", catalog.find(DOC).orElseThrow().create());
        assertEquals(List.of(1, 1, 1, 1), same.readCounts());
    }

    @Test
    void defaultBindingRemainsCompatible() {
        Base oldImplementation = new Base();
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry(oldImplementation, "unused"));
        assertTrue(catalog.problems().isEmpty());
        assertEquals("base", catalog.find(DOC).orElseThrow().create());
        assertEquals(List.of(1, 1, 1, 1), oldImplementation.readCounts());
    }

    @Test
    void everyDifferentBoundMetadataFieldRejectsOnlyThatType() throws IOException {
        List<DocumentType<?>> changed = List.of(
                new WrongClass(),
                new Base() { @Override public EditorId codecId() { reads[1]++; return id("binding:other_codec"); } },
                new Base() {
                    @Override public int schemaVersion() { reads[2]++; return 1; }
                },
                new Base() { @Override public Set<Integer> readableSchemas() { reads[3]++; return Set.of(2); } });
        for (DocumentType<?> bound : changed) {
            // The deliberately different value Class requires no unsafe generic payload cast.
            Prototype prototype = new Prototype(registry -> stringType(bound));
            DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry(prototype, "unused"));
            assertEquals(List.of(1, 1, 1, 1), prototype.readCounts());
            assertEquals(List.of(1, 1, 1, 1), stringType(bound).readCounts());
            assertEquals(1, prototype.registries.size());
            assertTrue(catalog.find(DOC).isEmpty());
            assertTrue(catalog.find(id("binding:healthy")).isPresent());
            DocumentCodecException failure = assertInstanceOf(DocumentCodecException.class,
                    catalog.activationFailures().get(DOC));
            Problem mismatch = failure.problems().get(0);
            assertEquals(id("editor:codec/binding_mismatch"), mismatch.code());
            assertEquals(Problem.Severity.ERROR, mismatch.severity());
            assertEquals(DOC.toString(), mismatch.target());
            TextContent.Message message = assertInstanceOf(TextContent.Message.class, mismatch.message());
            assertEquals("editor.codec.binding_mismatch", message.key());
            assertEquals(List.of(new TextContent.LiteralArgument(DOC.toString())), message.arguments());
            for (String language : List.of("en_us", "zh_cn")) {
                String path = "/assets/fpsmatch/lang/" + language + ".json";
                try (InputStream stream = getClass().getResourceAsStream(path)) {
                    assertNotNull(stream, path);
                    String resource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    assertFalse(LangResources.rendered(LangResources.parse(resource, path), message).isBlank());
                }
            }
        }
    }

    @Test
    void nullAndRuntimeExceptionBindingAreIsolatedAndOriginalFailureIsRetained() {
        RuntimeException original = new IllegalStateException("private bind failure");
        for (Prototype prototype : List.of(new Prototype(registry -> null), new Prototype(registry -> { throw original; }))) {
            DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry(prototype, "unused"));
            assertTrue(catalog.find(DOC).isEmpty());
            assertTrue(catalog.find(id("binding:healthy")).isPresent());
            assertEquals(1, prototype.registries.size());
            assertEquals(List.of(1, 1, 1, 1), prototype.readCounts());
            assertEquals(1, catalog.problems().size());
            assertEquals(id("editor:session/type_activation_failed"), catalog.problems().get(0).code());
            RuntimeException failure = catalog.activationFailures().get(DOC);
            if (failure != original) assertInstanceOf(NullPointerException.class, failure);
        }
        Prototype throwsOriginal = new Prototype(registry -> { throw original; });
        assertSame(original, DocumentTypeCatalog.activate(registry(throwsOriginal, "unused")).activationFailures().get(DOC));
    }

    @Test
    void bindingErrorPropagatesAndInvalidPrototypeNeverBinds() {
        AssertionError original = new AssertionError("fatal bind");
        Prototype fatal = new Prototype(registry -> { throw original; });
        assertSame(original, assertThrows(AssertionError.class,
                () -> DocumentTypeCatalog.activate(registry(fatal, "unused"))));
        Prototype invalid = new Prototype(registry -> new Base()) {
            @Override public int schemaVersion() { return 0; }
        };
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry(invalid, "unused"));
        assertTrue(catalog.find(DOC).isEmpty());
        assertTrue(invalid.registries.isEmpty(), "validate prototype before calling bind");
    }

    /** Deliberately false descriptor Class; no payload is ever passed through this cast. */
    private static final class WrongClass extends Base {
        @Override
        @SuppressWarnings("unchecked")
        public Class<String> valueType() {
            reads[0]++;
            return (Class<String>) (Class<?>) Integer.class;
        }
    }
    static class Base implements DocumentType<String> {
        final int[] reads = new int[4];
        int creates;
        int binds;
        @Override public Class<String> valueType() { reads[0]++; return String.class; }
        @Override public EditorId codecId() { reads[1]++; return CODEC; }
        @Override public int schemaVersion() { reads[2]++; return 2; }
        @Override public Set<Integer> readableSchemas() { reads[3]++; return Set.of(1, 2); }
        @Override public String create() { creates++; return "base"; }
        @Override public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public List<Problem> validate(String value) { return List.of(); }
        @Override public DocumentDependencies dependencies(String value) { return EMPTY; }
        List<Integer> readCounts() { return java.util.Arrays.stream(reads).boxed().toList(); }
    }
    static class Prototype extends Base {
        final Function<ExtensionRegistry, DocumentType<String>> binding;
        final List<ExtensionRegistry> registries = new ArrayList<>();
        Prototype(Function<ExtensionRegistry, DocumentType<String>> binding) { this.binding = binding; }
        @Override public DocumentType<String> bind(ExtensionRegistry registry) {
            registries.add(registry); return binding.apply(registry);
        }
    }
    private static final class Bound extends Base {
        final ActivatedValueType<?> value;
        final ActivatedValueType<?> secondValue;
        ValueSnapshot<?> captured;
        ValueSnapshot<?> secondCaptured;
        Bound(ActivatedValueType<?> value, ActivatedValueType<?> secondValue) {
            this.value = value; this.secondValue = secondValue;
        }
        @Override public String create() {
            captured = value.decode(CODEC, 2, new byte[0]);
            secondCaptured = secondValue.decode(CODEC, 2, new byte[0]);
            return String.class.cast(captured.content()) + ':' + String.class.cast(secondCaptured.content());
        }
    }
    private static ExtensionRegistry registry(DocumentType<?> implementation, String marker) {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.define(ValueTypes.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1.0.0", API, Set.of(), Set.of()));
        EditorId nestedOwner = id("third:module");
        builder.addModule(new ModuleSpec(nestedOwner, "9.8.7", API, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, metadata(DocumentTypes.KIND.id(), DOC, OWNER), implementation);
        builder.add(DocumentTypes.KIND, metadata(DocumentTypes.KIND.id(), id("binding:healthy"), OWNER), new Base());
        builder.add(ValueTypes.KIND, metadata(ValueTypes.KIND.id(), VALUE, nestedOwner), new Marker(marker));
        builder.add(ValueTypes.KIND, metadata(ValueTypes.KIND.id(), SECOND_VALUE, nestedOwner), new Marker("aux-" + marker));
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty());
        return registry;
    }
    private static final class Marker implements ValueType<String> {
        private final String marker;
        Marker(String marker) { this.marker = marker; }
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 2; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1, 2); }
        @Override public String decode(int schema, byte[] bytes) { return marker; }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public List<Problem> validate(String value) { return List.of(); }
        @Override public DocumentDependencies dependencies(String value) { return EMPTY; }
    }
    private static Contribution metadata(EditorId kind, EditorId type, EditorId owner) {
        return new Contribution(new ContributionKey(kind, type), owner, Contribution.Side.COMMON, Set.of());
    }
    private static EditorId id(String text) { return EditorId.parse(text); }
    private static Base stringType(DocumentType<?> type) {
        // Only Base instances enter this branch; keeping this check avoids generic erasure casts.
        return Base.class.cast(type);
    }
}
