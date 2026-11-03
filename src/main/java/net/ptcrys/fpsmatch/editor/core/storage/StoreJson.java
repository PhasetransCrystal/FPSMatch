package net.ptcrys.fpsmatch.editor.core.storage;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.INVALID_FORMAT;
import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.LIMIT_EXCEEDED;
import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.UNSUPPORTED_FORMAT;

/** Strict platform metadata only; document payloads never pass through a JSON parser here. */
final class StoreJson {

    static final int HEAD_LIMIT = 256 * 1024;
    private static final Set<String> MANIFEST_KEYS = Set.of("formatVersion", "projectId", "commitId",
            "parentCommitId", "projectName", "modules", "documents", "assets", "origin");
    private static final Set<String> ENTRY_KEYS = Set.of("id", "ownerModule", "typeId", "codecId",
            "schemaVersion", "displayName", "content", "documentDependencies", "assetDependencies");

    record Receipt(UUID requestId, String manifestHash) {

        Receipt {
            Objects.requireNonNull(requestId);
            checkHash(manifestHash);
        }
    }

    record Head(UUID projectId, UUID commitId, String manifestHash, List<Receipt> receipts) {

        Head {
            Objects.requireNonNull(projectId);
            Objects.requireNonNull(commitId);
            checkHash(manifestHash);
            receipts = List.copyOf(receipts);
        }
    }

    private StoreJson() {}

    static byte[] manifest(SavedProject project, int limit) throws IOException {
        return write(limit, out -> {
            out.beginObject();
            out.name("formatVersion").value(1);
            out.name("projectId").value(project.state().projectId().toString());
            out.name("commitId").value(project.commitId().toString());
            out.name("parentCommitId");
            if (project.parentCommitId() == null) {
                out.nullValue();
            } else {
                out.value(project.parentCommitId().toString());
            }
            out.name("projectName").value(project.state().name());
            out.name("modules").beginObject();
            for (var entry : project.state().modules().entrySet()) {
                out.name(entry.getKey()).value(entry.getValue());
            }
            out.endObject();
            out.name("documents");
            entries(out, project.state().documents());
            out.name("assets");
            entries(out, project.state().assets());
            out.name("origin");
            if (project.state().origin() == null) {
                out.nullValue();
            } else {
                out.beginObject();
                out.name("projectId").value(project.state().origin().projectId().toString());
                out.name("commitId").value(project.state().origin().commitId().toString());
                out.endObject();
            }
            out.endObject();
        });
    }

    private static void entries(JsonWriter out, Map<UUID, ProjectState.Entry> entries) throws IOException {
        out.beginArray();
        for (ProjectState.Entry entry : entries.values()) {
            out.beginObject();
            out.name("id").value(entry.id().toString());
            out.name("ownerModule").value(entry.ownerModule());
            out.name("typeId").value(entry.typeId());
            out.name("codecId").value(entry.codecId());
            out.name("schemaVersion").value(entry.schemaVersion());
            out.name("displayName").value(entry.displayName());
            out.name("content").beginObject();
            out.name("kind").value(entry.content().kind().name());
            out.name("sha256").value(entry.content().sha256());
            out.name("size").value(entry.content().size());
            out.endObject();
            out.name("documentDependencies");
            uuids(out, entry.documentDependencies());
            out.name("assetDependencies");
            uuids(out, entry.assetDependencies());
            out.endObject();
        }
        out.endArray();
    }

    private static void uuids(JsonWriter out, Set<UUID> ids) throws IOException {
        out.beginArray();
        for (UUID id : ids) {
            out.value(id.toString());
        }
        out.endArray();
    }

    static byte[] head(Head head) throws IOException {
        return write(HEAD_LIMIT, out -> {
            out.beginObject();
            out.name("formatVersion").value(1);
            out.name("projectId").value(head.projectId().toString());
            out.name("commitId").value(head.commitId().toString());
            out.name("manifestHash").value(head.manifestHash());
            out.name("receipts").beginArray();
            for (Receipt receipt : head.receipts()) {
                out.beginObject();
                out.name("requestId").value(receipt.requestId().toString());
                out.name("manifestHash").value(receipt.manifestHash());
                out.endObject();
            }
            out.endArray().endObject();
        });
    }

    static SavedProject readManifest(byte[] bytes, StoreLimits limits) throws IOException {
        try {
            Map<String, Object> root = read(bytes, limits.maxManifestBytes());
            version(root);
            keys(root, MANIFEST_KEYS);
            Map<String, String> modules = new LinkedHashMap<>();
            for (var entry : object(root.get("modules")).entrySet()) {
                modules.put(entry.getKey(), text(entry.getValue()));
            }
            Map<UUID, ProjectState.Entry> documents = readEntries(root.get("documents"), limits);
            Map<UUID, ProjectState.Entry> assets = readEntries(root.get("assets"), limits);
            ProjectState.Origin origin = null;
            if (root.get("origin") != null) {
                Map<String, Object> source = object(root.get("origin"));
                keys(source, Set.of("projectId", "commitId"));
                origin = new ProjectState.Origin(uuid(source.get("projectId")), uuid(source.get("commitId")));
            }
            ProjectState state = new ProjectState(uuid(root.get("projectId")), text(root.get("projectName")),
                    modules, documents, assets, origin);
            UUID parent = root.get("parentCommitId") == null ? null : uuid(root.get("parentCommitId"));
            return new SavedProject(uuid(root.get("commitId")), parent, state);
        } catch (IllegalArgumentException | NullPointerException | ArithmeticException failure) {
            throw invalid("invalid manifest metadata", failure);
        }
    }

    private static Map<UUID, ProjectState.Entry> readEntries(Object value, StoreLimits limits) throws IOException {
        List<Object> array = array(value);
        if (array.size() > limits.maxEntries()) {
            throw new ProjectStorageException(LIMIT_EXCEEDED, "entry count exceeds budget");
        }
        Map<UUID, ProjectState.Entry> entries = new LinkedHashMap<>();
        for (Object item : array) {
            Map<String, Object> entry = object(item);
            keys(entry, ENTRY_KEYS);
            Map<String, Object> blob = object(entry.get("content"));
            keys(blob, Set.of("kind", "sha256", "size"));
            BlobRef ref = new BlobRef(BlobRef.Kind.valueOf(text(blob.get("kind"))),
                    text(blob.get("sha256")), integer(blob.get("size")));
            UUID id = uuid(entry.get("id"));
            ProjectState.Entry result = new ProjectState.Entry(id, text(entry.get("ownerModule")),
                    text(entry.get("typeId")), text(entry.get("codecId")),
                    Math.toIntExact(integer(entry.get("schemaVersion"))), text(entry.get("displayName")),
                    ref, uuidSet(entry.get("documentDependencies")), uuidSet(entry.get("assetDependencies")));
            if (entries.putIfAbsent(id, result) != null) {
                throw invalid("duplicate entry identity", null);
            }
        }
        return entries;
    }

    static Head readHead(byte[] bytes, StoreLimits limits) throws IOException {
        try {
            Map<String, Object> root = read(bytes, HEAD_LIMIT);
            version(root);
            keys(root, Set.of("formatVersion", "projectId", "commitId", "manifestHash", "receipts"));
            List<Receipt> receipts = new ArrayList<>();
            Set<UUID> identities = new HashSet<>();
            for (Object value : array(root.get("receipts"))) {
                Map<String, Object> receipt = object(value);
                keys(receipt, Set.of("requestId", "manifestHash"));
                UUID id = uuid(receipt.get("requestId"));
                if (!identities.add(id)) {
                    throw invalid("duplicate request identity", null);
                }
                receipts.add(new Receipt(id, text(receipt.get("manifestHash"))));
            }
            if (receipts.isEmpty() || receipts.size() > limits.retainedReceipts()) {
                throw new ProjectStorageException(LIMIT_EXCEEDED, "invalid receipt window size");
            }
            Head head = new Head(uuid(root.get("projectId")), uuid(root.get("commitId")),
                    text(root.get("manifestHash")), receipts);
            Receipt latest = receipts.get(receipts.size() - 1);
            if (!latest.requestId().equals(head.commitId()) || !latest.manifestHash().equals(head.manifestHash())) {
                throw invalid("latest receipt does not match CURRENT", null);
            }
            return head;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid("invalid CURRENT metadata", failure);
        }
    }

    private static Set<UUID> uuidSet(Object value) throws IOException {
        Set<UUID> ids = new HashSet<>();
        for (Object item : array(value)) {
            if (!ids.add(uuid(item))) {
                throw invalid("duplicate dependency identity", null);
            }
        }
        return ids;
    }

    private static void version(Map<String, Object> root) throws IOException {
        if (integer(root.get("formatVersion")) != 1) {
            throw new ProjectStorageException(UNSUPPORTED_FORMAT, "unsupported project format");
        }
    }

    private static void keys(Map<String, Object> object, Set<String> expected) throws IOException {
        if (!object.keySet().equals(expected)) {
            throw invalid("missing or unsupported metadata fields", null);
        }
    }

    private static long integer(Object value) throws IOException {
        if (!(value instanceof BigDecimal number)) {
            throw invalid("expected integer", null);
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException failure) {
            throw invalid("integer outside supported range", failure);
        }
    }

    private static String text(Object value) throws IOException {
        if (!(value instanceof String result)) {
            throw invalid("expected string", null);
        }
        return result;
    }

    private static UUID uuid(Object value) throws IOException {
        String input = text(value);
        UUID id = UUID.fromString(input);
        if (!id.toString().equals(input)) {
            throw invalid("expected canonical UUID", null);
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?>)) {
            throw invalid("expected object", null);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) throws IOException {
        if (!(value instanceof List<?>)) {
            throw invalid("expected array", null);
        }
        return (List<Object>) value;
    }

    private static Map<String, Object> read(byte[] bytes, int limit) throws IOException {
        if (bytes.length > limit) {
            throw new ProjectStorageException(LIMIT_EXCEEDED, "metadata byte budget exceeded");
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw invalid("invalid UTF-8", failure);
        }
        if (json.startsWith("\ufeff")) {
            throw invalid("unexpected byte order mark", null);
        }
        checkJsonLexemes(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            Object result = value(reader, 0, new int[1]);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid("trailing JSON data", null);
            }
            return object(result);
        } catch (ProjectStorageException failure) {
            throw failure;
        } catch (IOException | IllegalStateException | IllegalArgumentException failure) {
            throw invalid("invalid JSON metadata", failure);
        }
    }

    // Gson 2.10's non-lenient reader still accepts uppercase keywords and raw controls in strings.
    // Enforce JSON lexemes here; JsonReader below enforces their structure and decoded semantics.
    private static void checkJsonLexemes(String json) throws IOException {
        int offset = 0;
        while (offset < json.length()) {
            char ch = json.charAt(offset++);
            if (jsonWhitespace(ch) || "{}[],:".indexOf(ch) >= 0) {
                continue;
            }
            if (ch == '"') {
                boolean closed = false;
                while (offset < json.length()) {
                    char next = json.charAt(offset++);
                    if (next == '"') {
                        closed = true;
                        break;
                    }
                    if (next < 0x20) {
                        throw invalid("unescaped string control", null);
                    }
                    if (next == '\\') {
                        if (offset == json.length()) {
                            throw invalid("incomplete string escape", null);
                        }
                        char escape = json.charAt(offset++);
                        if (escape == 'u') {
                            for (int i = 0; i < 4; i++) {
                                if (offset == json.length() || !hexDigit(json.charAt(offset++))) {
                                    throw invalid("invalid Unicode escape", null);
                                }
                            }
                        } else if ("\"\\/bfnrt".indexOf(escape) < 0) {
                            throw invalid("invalid string escape", null);
                        }
                    }
                }
                if (!closed) {
                    throw invalid("unterminated string", null);
                }
                continue;
            }
            int start = offset - 1;
            while (offset < json.length() && !jsonWhitespace(json.charAt(offset)) && "{}[],:\"".indexOf(json.charAt(offset)) < 0) {
                if (offset - start >= 64) {
                    throw invalid("oversized JSON token", null);
                }
                offset++;
            }
            String token = json.substring(start, offset);
            if (!token.equals("true") && !token.equals("false") && !token.equals("null") && !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                throw invalid("invalid JSON token", null);
            }
        }
    }

    private static boolean jsonWhitespace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n';
    }

    private static boolean hexDigit(char ch) {
        return ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F';
    }

    private static Object value(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 16 || ++nodes[0] > 262144) {
            throw new ProjectStorageException(LIMIT_EXCEEDED, "metadata structure budget exceeded");
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Map<String, Object> result = new LinkedHashMap<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    ProjectState.requireText(key);
                    if (result.containsKey(key)) {
                        throw invalid("duplicate JSON member", null);
                    }
                    result.put(key, value(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                List<Object> result = new ArrayList<>();
                while (reader.hasNext()) {
                    result.add(value(reader, depth + 1, nodes));
                }
                reader.endArray();
                yield result;
            }
            case STRING -> {
                String text = reader.nextString();
                ProjectState.requireText(text);
                yield text;
            }
            case NUMBER -> {
                String token = reader.nextString();
                if (token.length() > 64 || !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                    throw invalid("invalid number", null);
                }
                yield new BigDecimal(token);
            }
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> {
                reader.nextNull();
                yield null;
            }
            default -> throw invalid("unexpected JSON token", null);
        };
    }

    private static byte[] write(int limit, WriteAction action) throws IOException {
        BoundedTextWriter text = new BoundedTextWriter(limit);
        try (JsonWriter writer = new JsonWriter(text)) {
            writer.setIndent("  ");
            writer.setSerializeNulls(true);
            action.write(writer);
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) {
            throw new ProjectStorageException(LIMIT_EXCEEDED, "encoded metadata byte budget exceeded");
        }
        return bytes;
    }

    private static void checkHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid metadata hash");
        }
    }

    private static ProjectStorageException invalid(String message, Throwable cause) {
        return new ProjectStorageException(INVALID_FORMAT, message, cause);
    }

    @FunctionalInterface
    private interface WriteAction {

        void write(JsonWriter writer) throws IOException;
    }

    private static final class BoundedTextWriter extends Writer {

        private final int limit;
        private final StringBuilder text = new StringBuilder();

        private BoundedTextWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            if (length > limit - text.length()) {
                throw new ProjectStorageException(LIMIT_EXCEEDED, "metadata character budget exceeded");
            }
            text.append(characters, offset, length);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
