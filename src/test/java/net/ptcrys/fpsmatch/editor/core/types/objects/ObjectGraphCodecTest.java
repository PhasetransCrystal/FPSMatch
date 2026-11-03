package net.ptcrys.fpsmatch.editor.core.types.objects;

import static net.ptcrys.fpsmatch.editor.core.types.objects.ObjectGraphFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

class ObjectGraphCodecTest {
    @Test
    void orderedRoundTripKeepsIdentityAndAllowsReferenceCyclesAndSelfReferences() {
        Probe type = new Probe();
        type.referenceReader = value -> Set.of(new ObjectReference(LINK, value.equals("root") ? CHILD : ROOT),
                new ObjectReference(id("vendor:self"), value.equals("root") ? ROOT : CHILD));
        ObjectCatalog catalog = catalog(type);
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog);
        ObjectGraph graph = new ObjectGraph(ROOT, List.of(node(catalog, CHILD, ROOT, "child"), node(catalog, ROOT, null, "root")));
        assertTrue(codec.validate(graph).isEmpty());
        byte[] encoded = codec.encode(graph);
        assertArrayEquals(wire(ROOT, new WireNode(CHILD, ROOT, "child"), new WireNode(ROOT, null, "root")), encoded);
        ObjectGraph decoded = codec.decode(encoded);
        assertEquals(List.of(CHILD, ROOT), decoded.nodes().stream().map(ObjectNode::id).toList());
        assertEquals(ROOT, decoded.rootId());
        assertEquals(Set.of(new ObjectReference(LINK, CHILD), new ObjectReference(id("vendor:self"), ROOT)),
                decoded.nodes().get(1).references());
        assertArrayEquals(encoded, codec.encode(decoded));
        assertTrue(codec.dependencies(decoded).documents().isEmpty(), "local references are not external documents");
        assertEquals(Set.of(key(TYPE)), codec.dependencies(decoded).contributions());
    }

    @Test
    void ownershipCyclesMissingParentsDuplicateIdsAndMissingReferenceTargetsFailAtomically() {
        Probe type = new Probe();
        ObjectCatalog catalog = catalog(type);
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog);
        ObjectNode root = node(catalog, ROOT, null, "root");
        ObjectGraph original = one(root);
        byte[] before = codec.encode(original);
        UUID third = uuid(3);
        List<ObjectGraph> invalid = List.of(
                new ObjectGraph(ROOT, List.of(root, node(catalog, CHILD, third, "a"), node(catalog, third, CHILD, "b"))),
                new ObjectGraph(ROOT, List.of(root, node(catalog, CHILD, uuid(999), "orphan"))),
                new ObjectGraph(ROOT, List.of(root, node(catalog, ROOT, ROOT, "duplicate"))),
                new ObjectGraph(CHILD, List.of(root)),
                new ObjectGraph(ROOT, List.of(root, node(catalog, CHILD, null, "second-root"))),
                new ObjectGraph(ROOT, List.of()));
        for (ObjectGraph graph : invalid) {
            assertFalse(codec.validate(graph).isEmpty());
            failure(() -> codec.snapshot(graph), "invalid_node");
            failure(() -> codec.encode(graph), "invalid_node");
            assertArrayEquals(before, codec.encode(original));
        }
        type.referenceReader = value -> Set.of(new ObjectReference(LINK, uuid(999)));
        ObjectGraph dangling = one(node(catalog, ROOT, null, "dangling"));
        failure(() -> codec.snapshot(dangling), "invalid_node");
        assertArrayEquals(before, codec.encode(original));
    }

    @Test
    void maximumReverseOrderedOwnershipChainIsStackSafeAndPreservesStoredOrder() {
        assertEquals(8192, ObjectGraphCodec.MAX_OBJECTS);
        Probe type = new Probe();
        ObjectCatalog catalog = catalog(type);
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog);
        List<ObjectNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 8192; i++) nodes.add(node(catalog, uuid(i), i == 1 ? null : uuid(i - 1), "x"));
        Collections.reverse(nodes); // First walk must cross all 8191 edges; every later suffix is already visited.
        ObjectGraph graph = new ObjectGraph(ROOT, nodes);
        assertTimeout(Duration.ofSeconds(10), () -> {
            ObjectGraph snapshot = codec.snapshot(graph);
            ObjectGraph reopened = codec.decode(codec.encode(snapshot));
            assertEquals(8192, reopened.nodes().size());
            assertEquals(uuid(8192), reopened.nodes().get(0).id());
            assertEquals(ROOT, reopened.nodes().get(8191).id());
        });
        List<ObjectNode> tooMany = new ArrayList<>(nodes); tooMany.add(nodes.get(0));
        failure(() -> new ObjectGraph(ROOT, tooMany), "budget");
    }

    @Test
    void referencesAreBoundedAcrossTheWholeGraphEvenWhenEachLeafFits() {
        Probe type = new Probe();
        type.referenceReader = value -> references(value.equals("root") ? 4096 : 4097, ROOT);
        ObjectCatalog catalog = catalog(type);
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog);
        ObjectNode root = node(catalog, ROOT, null, "root");
        ObjectNode child = node(catalog, CHILD, ROOT, "child");
        ObjectGraph excessive = new ObjectGraph(ROOT, List.of(root, child));
        budget(() -> codec.snapshot(excessive), "references");
        budget(() -> codec.encode(excessive), "references");
        budget(() -> codec.decode(wire(ROOT, new WireNode(ROOT, null, "root"), new WireNode(CHILD, ROOT, "child"))), "references");
        type.referenceReader = value -> references(4096, ROOT);
        ObjectGraph boundary = new ObjectGraph(ROOT, List.of(node(catalog, ROOT, null, "root"), node(catalog, CHILD, ROOT, "child")));
        assertEquals(8192, codec.snapshot(boundary).nodes().stream().mapToInt(node -> node.references().size()).sum());
    }

    @Test
    void dependencyBudgetMergesAndDeduplicatesBeforeCountingTheWholeGraph() {
        Probe type = new Probe();
        type.dependencyReader = value -> new DocumentDependencies(documents(value.equals("a") ? 0 : 4096, 4096), Set.of(), Set.of());
        ObjectCatalog catalog = catalog(type);
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog);
        ObjectGraph graph = new ObjectGraph(ROOT, List.of(node(catalog, ROOT, null, "a"), node(catalog, CHILD, ROOT, "b")));
        budget(() -> codec.dependencies(graph), "dependencies"); // 8192 documents plus one real value contribution.
        type.dependencyReader = value -> new DocumentDependencies(documents(0, 4096), Set.of(), Set.of());
        ObjectGraph shared = new ObjectGraph(ROOT, List.of(node(catalog, ROOT, null, "a"), node(catalog, CHILD, ROOT, "b")));
        assertEquals(4096, codec.dependencies(shared).documents().size());
        assertEquals(Set.of(key(TYPE)), codec.dependencies(shared).contributions());
    }

    @Test
    void malformedLateDescriptorsAndOwnershipAreRejectedBeforeAnyLeafCallback() {
        Probe type = new Probe();
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog(type));
        WireNode first = new WireNode(ROOT, null, "first");
        WireNode second = new WireNode(CHILD, ROOT, "second");
        byte[] valid = wire(ROOT, first, second);
        int secondOffset = wire(ROOT, first).length;
        int secondRole = secondOffset + 16 + 1 + 16;
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(Arrays.copyOf(valid, valid.length - 1));
        invalid.add(Arrays.copyOf(valid, valid.length + 1));
        invalid.add(changeInt(valid, 0, 0x454f4702));
        invalid.add(changeInt(valid, 20, -1));
        invalid.add(changeInt(valid, 20, 8193));
        byte[] bool = valid.clone(); bool[secondOffset + 16] = 2; invalid.add(bool);
        invalid.add(changeInt(valid, secondRole, -1));
        invalid.add(changeInt(valid, secondRole, 322));
        byte[] utf8 = valid.clone(); utf8[secondRole + 4] = (byte) 0xff; invalid.add(utf8);
        byte[] badId = valid.clone(); badId[secondRole + 4] = '!'; invalid.add(badId);
        invalid.add(changeInt(valid, valid.length - bytes("second").length - 8, 0));
        invalid.add(changeInt(valid, valid.length - bytes("second").length - 4, -1));
        invalid.add(changeInt(valid, valid.length - bytes("second").length - 4, ObjectGraphCodec.MAX_BYTES));
        invalid.add(wire(ROOT, first, new WireNode(CHILD, uuid(999), "orphan")));
        invalid.add(wire(ROOT, first, new WireNode(ROOT, ROOT, "duplicate")));
        invalid.add(wire(ROOT, first, new WireNode(CHILD, uuid(3), "cycle-a"), new WireNode(uuid(3), CHILD, "cycle-b")));
        invalid.add(wire(CHILD, first, second));
        invalid.add(new byte[0]);
        for (int index = 0; index < invalid.size(); index++) {
            byte[] input = invalid.get(index);
            byte[] original = input.clone();
            assertThrows(DocumentCodecException.class, () -> codec.decode(input), "case " + index);
            assertTrue(type.callbacks.isEmpty(), "case " + index + " called a leaf before full wire preflight");
            assertArrayEquals(original, input);
        }
    }

    @Test
    void unknownTypeCodecAndPositiveFutureSchemaKeepInputAndNeverCallTheDecoder() {
        Probe type = new Probe();
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog(type));
        for (WireNode descriptor : List.of(
                new WireNode(ROOT, null, POSITION.toString(), "missing:type", CODEC.toString(), 2, bytes("opaque")),
                new WireNode(ROOT, null, POSITION.toString(), TYPE.toString(), "missing:codec", 2, bytes("opaque")),
                new WireNode(ROOT, null, POSITION.toString(), TYPE.toString(), CODEC.toString(), 99, bytes("opaque")))) {
            byte[] input = wire(ROOT, descriptor);
            byte[] original = input.clone();
            assertThrows(DocumentCodecException.class, () -> codec.decode(input));
            assertTrue(type.callbacks.isEmpty());
            assertArrayEquals(original, input);
        }
        ObjectGraph supportedOld = codec.decode(wire(ROOT,
                new WireNode(ROOT, null, POSITION.toString(), TYPE.toString(), CODEC.toString(), 1, bytes("old"))));
        assertEquals(2, supportedOld.nodes().get(0).value().schemaVersion());
        assertEquals(List.of("old"), type.decoded);
    }

    @Test
    void exactEightMiBIncludesEveryWireFieldAndOneMoreByteFailsBeforeDecoding() {
        assertEquals(8 * 1024 * 1024, ObjectGraphCodec.MAX_BYTES);
        int overhead = wire(ROOT, new WireNode(ROOT, null, "")).length;
        byte[] payload = new byte[ObjectGraphCodec.MAX_BYTES - overhead];
        Arrays.fill(payload, (byte) 'x');
        byte[] exact = wire(ROOT, new WireNode(ROOT, null, POSITION.toString(), TYPE.toString(), CODEC.toString(), 2, payload));
        assertEquals(ObjectGraphCodec.MAX_BYTES, exact.length);
        Probe type = new Probe();
        ObjectCatalog catalog = catalog(type);
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog);
        ObjectGraph decoded = codec.decode(exact);
        assertArrayEquals(exact, codec.encode(decoded));
        type.callbacks.clear();
        budget(() -> codec.decode(Arrays.copyOf(exact, exact.length + 1)), "bytes");
        assertTrue(type.callbacks.isEmpty());
        ObjectNode oneByteLarger = node(catalog, ROOT, null, new String(Arrays.copyOf(payload, payload.length + 1), java.nio.charset.StandardCharsets.UTF_8));
        type.callbacks.clear();
        budget(() -> codec.encode(one(oneByteLarger)), "bytes");
        budget(() -> codec.snapshot(one(oneByteLarger)), "bytes");
        assertTrue(type.callbacks.isEmpty());
    }

    @Test
    void expandedDecodeStopsAtTheFirstCrossingLeafAndPreservesTheOriginalWire() {
        Probe type = expanding();
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog(type));
        byte[] input = wire(ROOT, new WireNode(ROOT, null, "a"), new WireNode(CHILD, ROOT, "b"), new WireNode(uuid(3), ROOT, "never"));
        byte[] original = input.clone();
        budget(() -> codec.decode(input), "bytes");
        assertEquals(List.of("a", "b"), type.decoded);
        assertEquals(2, Collections.frequency(type.callbacks, "references"));
        assertArrayEquals(original, input);
    }

    @Test
    void expandedForeignAdoptionStopsImmediatelyAndLeavesSourceGraphReusable() {
        ObjectCatalog source = catalog(new Probe());
        ObjectGraph foreign = new ObjectGraph(ROOT, List.of(node(source, ROOT, null, "a"),
                node(source, CHILD, ROOT, "b"), node(source, uuid(3), ROOT, "never")));
        ObjectGraphCodec sourceCodec = new ObjectGraphCodec(source);
        byte[] original = sourceCodec.encode(foreign);
        Probe type = expanding();
        ObjectGraphCodec targetCodec = new ObjectGraphCodec(catalog(type));
        budget(() -> targetCodec.snapshot(foreign), "bytes");
        assertEquals(List.of("a", "b"), type.decoded);
        assertEquals(2, Collections.frequency(type.callbacks, "references"));
        assertArrayEquals(original, sourceCodec.encode(foreign));
        assertEquals(List.of("a", "b", "never"), foreign.nodes().stream().map(node -> node.value().content()).toList());
    }

    @Test
    void overBudgetForeignInputIsRejectedBeforeAdoptingEvenTheFirstLeaf() {
        Probe sourceType = new Probe();
        sourceType.encoder = value -> new byte[4 * 1024 * 1024];
        ObjectCatalog source = catalog(sourceType);
        ObjectGraph foreign = new ObjectGraph(ROOT, List.of(node(source, ROOT, null, "a"), node(source, CHILD, ROOT, "b")));
        Probe receiver = new Probe();
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog(receiver));
        budget(() -> codec.snapshot(foreign), "bytes");
        assertTrue(receiver.callbacks.isEmpty());
    }

    private static Probe expanding() {
        Probe type = new Probe();
        type.encoder = value -> new byte[4 * 1024 * 1024];
        return type;
    }
    private static Set<ObjectReference> references(int count, UUID target) {
        Set<ObjectReference> refs = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) refs.add(new ObjectReference(id("vendor:link_" + i), target));
        return refs;
    }
    private static Set<UUID> documents(int start, int count) {
        Set<UUID> documents = new LinkedHashSet<>();
        for (int i = start; i < start + count; i++) documents.add(uuid(100000L + i));
        return documents;
    }
    private static byte[] changeInt(byte[] input, int offset, int value) {
        byte[] changed = input.clone(); ByteBuffer.wrap(changed).putInt(offset, value); return changed;
    }
    private static void budget(Runnable action, String name) {
        var problem = failure(action, "budget");
        TextContent.Message message = (TextContent.Message) problem.message();
        assertEquals(new TextContent.LiteralArgument(name), message.arguments().get(0));
        assertInstanceOf(TextContent.NumberArgument.class, message.arguments().get(1));
        assertInstanceOf(TextContent.NumberArgument.class, message.arguments().get(2));
    }
}
