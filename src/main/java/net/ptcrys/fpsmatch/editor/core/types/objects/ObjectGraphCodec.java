package net.ptcrys.fpsmatch.editor.core.types.objects;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded deterministic graph wire and validation, without I/O or asynchronous publication. */
public final class ObjectGraphCodec {

    public static final int MAX_OBJECTS = 8192;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAGIC = 0x454f4701;
    private final ObjectCatalog catalog;

    private record Position(UUID id, UUID parent) {}

    private record Descriptor(Position position, EditorId role, EditorId type, EditorId codec,
                              int schema, byte[] payload) {}

    public ObjectGraphCodec(ObjectCatalog catalog) {
        if (catalog == null) throw ObjectMessages.node("objects", "catalog");
        this.catalog = catalog;
    }

    public ObjectGraph snapshot(ObjectGraph value) {
        preflight(value, false);
        List<ObjectNode> nodes = new ArrayList<>(value.nodes().size());
        long references = 0;
        long capturedLength = 24;
        for (ObjectNode node : value.nodes()) {
            ObjectNode adopted = catalog.adopt(node);
            capturedLength += nodeWireSize(adopted);
            ObjectMessages.requireBudget("bytes", capturedLength, MAX_BYTES);
            references += adopted.references().size();
            ObjectMessages.requireBudget("references", references, MAX_OBJECTS);
            nodes.add(adopted);
        }
        ObjectGraph result = new ObjectGraph(value.rootId(), nodes);
        requireValid(result);
        return result;
    }

    public List<Problem> validate(ObjectGraph value) {
        try {
            preflight(value, true);
            validateStructure(value);
            collectDependencies(value);
            List<Problem> problems = new ArrayList<>();
            for (ObjectNode node : value.nodes()) problems.addAll(node.value().problems());
            return List.copyOf(problems);
        } catch (DocumentCodecException failure) {
            return failure.problems();
        }
    }

    private void requireValid(ObjectGraph value) {
        for (Problem problem : validate(value)) {
            if (problem.severity() == Problem.Severity.ERROR) throw new DocumentCodecException(List.of(problem));
        }
    }

    /** Computes the exact wire size before retrieving any payload copy or allocating output. */
    private int preflight(ObjectGraph value, boolean requireOwned) {
        if (value == null) throw ObjectMessages.node("objects", "missing");
        ObjectMessages.requireBudget("objects", value.nodes().size(), MAX_OBJECTS);
        long length = 24;
        for (ObjectNode node : value.nodes()) {
            if (requireOwned && !catalog.owns(node)) throw ObjectMessages.node(node.id(), "ownership");
            length += nodeWireSize(node);
            ObjectMessages.requireBudget("bytes", length, MAX_BYTES);
        }
        return (int) length;
    }

    private static int idSize(EditorId id) {
        return 4 + id.toString().length();
    }

    private static long nodeWireSize(ObjectNode node) {
        return 16L + 1L + (node.parent().isPresent() ? 16L : 0L) + idSize(node.role()) + idSize(node.value().typeId()) + idSize(node.value().codecId()) + 4L + 4L + node.value().encodingSize();
    }

    public byte[] encode(ObjectGraph value) {
        int length = preflight(value, true);
        requireValid(value);
        ByteBuffer out = ByteBuffer.allocate(length);
        out.putInt(MAGIC);
        putUuid(out, value.rootId());
        out.putInt(value.nodes().size());
        for (ObjectNode node : value.nodes()) {
            putUuid(out, node.id());
            out.put((byte) (node.parent().isPresent() ? 1 : 0));
            node.parent().ifPresent(parent -> putUuid(out, parent));
            putId(out, node.role());
            putId(out, node.value().typeId());
            putId(out, node.value().codecId());
            out.putInt(node.value().schemaVersion());
            byte[] payload = node.value().encoding();
            out.putInt(payload.length);
            out.put(payload);
        }
        return out.array();
    }

    public ObjectGraph decode(byte[] bytes) {
        if (bytes == null) throw ObjectMessages.wire("missing");
        ObjectMessages.requireBudget("bytes", bytes.length, MAX_BYTES);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        need(in, 24);
        if (in.getInt() != MAGIC) throw ObjectMessages.wire("magic_version");
        UUID root = getUuid(in);
        int count = in.getInt();
        if (count < 0 || count > MAX_OBJECTS) throw ObjectMessages.wire("count");
        List<Descriptor> descriptors = new ArrayList<>(count);
        List<Position> positions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID id = getUuid(in);
            need(in, 1);
            byte flag = in.get();
            if (flag != 0 && flag != 1) throw ObjectMessages.wire("parent_flag");
            UUID parent = flag == 1 ? getUuid(in) : null;
            EditorId role = getId(in);
            EditorId type = getId(in);
            EditorId codec = getId(in);
            need(in, 8);
            int schema = in.getInt();
            if (schema <= 0) throw ObjectMessages.wire("schema");
            int length = in.getInt();
            if (length < 0 || length > MAX_BYTES || length > in.remaining()) {
                throw ObjectMessages.wire("payload_length");
            }
            byte[] payload = new byte[length];
            in.get(payload);
            Position position = new Position(id, parent);
            positions.add(position);
            descriptors.add(new Descriptor(position, role, type, codec, schema, payload));
        }
        if (in.hasRemaining()) throw ObjectMessages.wire("trailing_bytes");
        // No plugin callback occurs until every descriptor and the entire ownership tree is valid.
        checkOwnership(root, positions);
        List<ObjectNode> nodes = new ArrayList<>(count);
        long references = 0;
        long capturedLength = 24;
        for (Descriptor descriptor : descriptors) {
            ObjectNode node = catalog.decode(descriptor.position().id(),
                    Optional.ofNullable(descriptor.position().parent()), descriptor.role(), descriptor.type(),
                    descriptor.codec(), descriptor.schema(), descriptor.payload());
            capturedLength += nodeWireSize(node);
            ObjectMessages.requireBudget("bytes", capturedLength, MAX_BYTES);
            references += node.references().size();
            ObjectMessages.requireBudget("references", references, MAX_OBJECTS);
            nodes.add(node);
        }
        ObjectGraph result = new ObjectGraph(root, nodes);
        requireValid(result);
        return result;
    }

    private static void validateStructure(ObjectGraph graph) {
        List<Position> positions = new ArrayList<>(graph.nodes().size());
        Set<UUID> ids = new HashSet<>();
        for (ObjectNode node : graph.nodes()) {
            positions.add(new Position(node.id(), node.parent().orElse(null)));
            ids.add(node.id());
        }
        checkOwnership(graph.rootId(), positions);
        long references = 0;
        for (ObjectNode node : graph.nodes()) {
            references += node.references().size();
            ObjectMessages.requireBudget("references", references, MAX_OBJECTS);
            for (ObjectReference reference : node.references()) {
                if (!ids.contains(reference.target())) throw ObjectMessages.node(node.id(), "ref_target");
            }
        }
    }

    /** Each ownership edge is traversed at most twice; local references never enter this walk. */
    private static void checkOwnership(UUID root, List<Position> positions) {
        Map<UUID, Integer> indices = new HashMap<>();
        int roots = 0;
        for (int i = 0; i < positions.size(); i++) {
            Position position = positions.get(i);
            if (indices.putIfAbsent(position.id(), i) != null) throw ObjectMessages.node(position.id(), "duplicate_id");
            if (position.parent() == null) {
                roots++;
                if (!position.id().equals(root)) throw ObjectMessages.node(position.id(), "root_id");
            }
        }
        if (roots != 1) throw ObjectMessages.node("objects", "root_count");
        int[] parents = new int[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            Position position = positions.get(i);
            Integer parent = position.parent() == null ? Integer.valueOf(-1) : indices.get(position.parent());
            if (parent == null) throw ObjectMessages.node(position.id(), "parent");
            parents[i] = parent;
        }
        byte[] states = new byte[positions.size()];
        for (int start = 0; start < positions.size(); start++) {
            int cursor = start;
            while (cursor >= 0 && states[cursor] == 0) {
                states[cursor] = 1;
                cursor = parents[cursor];
            }
            if (cursor >= 0 && states[cursor] == 1) throw ObjectMessages.node(positions.get(cursor).id(), "ownership_cycle");
            cursor = start;
            while (cursor >= 0 && states[cursor] == 1) {
                states[cursor] = 2;
                cursor = parents[cursor];
            }
        }
    }

    public DocumentDependencies dependencies(ObjectGraph value) {
        preflight(value, true);
        validateStructure(value);
        return collectDependencies(value);
    }

    private static DocumentDependencies collectDependencies(ObjectGraph value) {
        Set<UUID> documents = new HashSet<>();
        Set<ResourceRef> resources = new HashSet<>();
        Set<ContributionKey> contributions = new HashSet<>();
        for (ObjectNode node : value.nodes()) {
            DocumentDependencies dependencies = node.value().dependencies();
            for (UUID document : dependencies.documents()) {
                documents.add(document);
                dependencyBudget(documents, resources, contributions);
            }
            for (ResourceRef resource : dependencies.resources()) {
                resources.add(resource);
                dependencyBudget(documents, resources, contributions);
            }
            for (ContributionKey contribution : dependencies.contributions()) {
                contributions.add(contribution);
                dependencyBudget(documents, resources, contributions);
            }
        }
        return new DocumentDependencies(documents, resources, contributions);
    }

    private static void dependencyBudget(Set<?> documents, Set<?> resources, Set<?> contributions) {
        ObjectMessages.requireBudget("dependencies", (long) documents.size() + resources.size() + contributions.size(), MAX_OBJECTS);
    }

    private static void need(ByteBuffer in, int length) {
        if (in.remaining() < length) throw ObjectMessages.wire("truncated");
    }

    private static UUID getUuid(ByteBuffer in) {
        need(in, 16);
        return new UUID(in.getLong(), in.getLong());
    }

    private static void putUuid(ByteBuffer out, UUID value) {
        out.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static void putId(ByteBuffer out, EditorId id) {
        byte[] bytes = id.toString().getBytes(StandardCharsets.UTF_8);
        out.putInt(bytes.length).put(bytes);
    }

    private static EditorId getId(ByteBuffer in) {
        need(in, 4);
        int length = in.getInt();
        if (length < 0 || length > 321 || length > in.remaining()) throw ObjectMessages.wire("id_length");
        ByteBuffer encoded = in.slice();
        encoded.limit(length);
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(encoded).toString();
        } catch (CharacterCodingException failure) {
            throw ObjectMessages.wire("utf8");
        }
        in.position(in.position() + length);
        try {
            return EditorId.parse(decoded);
        } catch (IllegalArgumentException failure) {
            throw ObjectMessages.wire("id");
        }
    }
}
