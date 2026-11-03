package net.ptcrys.fpsmatch.editor.core.types.objects;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

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
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

/** Public registration and an independent wire writer, never a fabricated snapshot or issuer. */
final class ObjectGraphFixtures {
    static final ApiVersion API = new ApiVersion(1, 0);
    static final EditorId OWNER = id("vendor:objects");
    static final EditorId TYPE = id("vendor:object");
    static final EditorId CODEC = id("vendor:object_codec");
    static final EditorId POSITION = id("vendor:children");
    static final EditorId SEMANTIC = id("vendor:entity");
    static final EditorId LINK = id("vendor:link");
    static final DocumentDependencies EMPTY = new DocumentDependencies(Set.of(), Set.of(), Set.of());
    static final UUID ROOT = uuid(1);
    static final UUID CHILD = uuid(2);

    private ObjectGraphFixtures() { }

    static class Plain implements ValueType<String> {
        final List<String> callbacks = new ArrayList<>();
        final List<String> decoded = new ArrayList<>();
        Function<String, byte[]> encoder = ObjectGraphFixtures::bytes;
        Function<String, DocumentDependencies> dependencyReader = value -> EMPTY;
        Function<String, String> decoder = Function.identity();
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 2; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1, 2); }
        @Override public String decode(int schema, byte[] encoding) {
            String value = new String(encoding, StandardCharsets.UTF_8);
            callbacks.add("decode"); decoded.add(value);
            java.util.Arrays.fill(encoding, (byte) '!');
            return decoder.apply(value);
        }
        @Override public String snapshot(String value) { callbacks.add("snapshot"); return value; }
        @Override public List<Problem> validate(String value) { callbacks.add("validate"); return List.of(); }
        @Override public byte[] encode(String value) { callbacks.add("encode"); return encoder.apply(value); }
        @Override public DocumentDependencies dependencies(String value) {
            callbacks.add("dependencies"); return dependencyReader.apply(value);
        }
    }

    static class Probe extends Plain implements ObjectType<String> {
        int roleReads;
        EditorId semantic = SEMANTIC;
        Function<String, Set<ObjectReference>> referenceReader = value -> Set.of();
        @Override public EditorId role() { roleReads++; return semantic; }
        @Override public Set<ObjectReference> references(String snapshot) {
            callbacks.add("references"); return referenceReader.apply(snapshot);
        }
    }

    static ExtensionRegistryBuilder builder() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.define(ValueTypes.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1.2.3", API, Set.of(), Set.of()));
        return builder;
    }

    static void publish(ExtensionRegistryBuilder builder, EditorId type, EditorId owner, ValueType<?> value) {
        builder.add(ValueTypes.KIND, new Contribution(key(type), owner, Contribution.Side.COMMON, Set.of()), value);
    }

    static ExtensionRegistry registry(ValueType<?> type) {
        ExtensionRegistryBuilder builder = builder();
        publish(builder, TYPE, OWNER, type);
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty());
        assertSame(type, registry.find(ValueTypes.KIND, TYPE).orElseThrow());
        return registry;
    }

    static ObjectCatalog catalog(ValueType<?> type) { return ObjectCatalog.activate(registry(type)); }
    static ObjectNode node(ObjectCatalog catalog, UUID id, UUID parent, String value) {
        return catalog.capture(id, Optional.ofNullable(parent), POSITION, TYPE, String.class, value);
    }
    static ObjectGraph one(ObjectNode root) { return new ObjectGraph(root.id(), List.of(root)); }
    static ContributionKey key(EditorId type) { return new ContributionKey(ValueTypes.KIND.id(), type); }
    static EditorId id(String text) { return EditorId.parse(text); }
    static UUID uuid(long number) { return new UUID(0, number); }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    static Problem failure(Runnable action, String suffix) {
        DocumentCodecException exception = assertThrows(DocumentCodecException.class, action::run);
        assertFalse(exception.problems().isEmpty());
        Problem problem = exception.problems().get(0);
        assertEquals(id("editor:objects/" + suffix), problem.code());
        assertEquals(Problem.Severity.ERROR, problem.severity());
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals("editor.objects." + suffix, message.key());
        return problem;
    }

    record WireNode(UUID id, UUID parent, String role, String type, String codec, int schema, byte[] payload) {
        WireNode(UUID id, UUID parent, String value) {
            this(id, parent, POSITION.toString(), TYPE.toString(), CODEC.toString(), 2, bytes(value));
        }
    }

    /** Format from Wire 1, intentionally independent of ObjectGraphCodec.encode. */
    static byte[] wire(UUID root, WireNode... nodes) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.write(new byte[] {0x45, 0x4f, 0x47, 1});
            writeUuid(out, root);
            out.writeInt(nodes.length);
            for (WireNode node : nodes) {
                writeUuid(out, node.id());
                out.writeByte(node.parent() == null ? 0 : 1);
                if (node.parent() != null) writeUuid(out, node.parent());
                writeString(out, node.role()); writeString(out, node.type()); writeString(out, node.codec());
                out.writeInt(node.schema()); out.writeInt(node.payload().length); out.write(node.payload());
            }
            out.flush();
            return buffer.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = bytes(value); out.writeInt(encoded.length); out.write(encoded);
    }
}
