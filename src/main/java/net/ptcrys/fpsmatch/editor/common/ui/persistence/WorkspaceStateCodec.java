package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import net.ptcrys.fpsmatch.editor.common.ui.model.*;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/**
 * Strict versioned user-environment wire format. No registry lookup, repair or schema migration.
 * Opaque state entries use ordered key/value records; value chunks preserve the model's UTF-16
 * limit without exceeding ExactJson's smaller per-string UTF-8 budget.
 */
public final class WorkspaceStateCodec {

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_BYTES = ExactJson.MAX_UTF8_BYTES;
    private static final int CHUNK_CHARS = 16384;
    private static final int MAX_CHUNKS = 5;

    public enum Reason {
        INVALID_FORMAT,
        UNSUPPORTED_VERSION,
        LIMIT_EXCEEDED
    }

    public static final class CodecException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;
        private final Reason reason;

        private CodecException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private WorkspaceStateCodec() {}

    /**
     * Bounded output is streamed before building any JSON tree, so over-budget models cannot
     * allocate their complete encoded representation before the total budget is checked.
     */
    public static byte[] encode(WorkspaceState state) {
        Objects.requireNonNull(state, "state");
        var buffer = new BudgetWriter();
        try (var out = new JsonWriter(buffer)) {
            out.setSerializeNulls(true);
            out.beginObject().name("formatVersion").value(FORMAT_VERSION);
            out.name("activeWorkspace");
            uuid(out, state.activeWorkspace());
            out.name("workspaces").beginArray();
            for (var workspace : state.workspaces()) {
                out.beginObject().name("id").value(workspace.id().toString()).name("templateId").value(workspace.templateId().toString());
                out.name("templateVersion").value(workspace.templateVersion()).name("name").value(workspace.name()).name("visible").value(workspace.visible());
                out.name("panels");
                panels(out, workspace.panels());
                out.endObject();
            }
            out.endArray().name("savedLayouts").beginArray();
            for (var workspace : state.workspaces()) {
                out.beginObject().name("workspace").value(workspace.id().toString()).name("panels");
                panels(out, state.savedLayouts().get(workspace.id()));
                out.endObject();
            }
            out.endArray().endObject();
        } catch (IOException failure) {
            throw invalid("cannot encode workspace state", failure);
        }
        try {
            return ExactJson.parse(buffer.result()).canonical().getBytes(StandardCharsets.UTF_8);
        } catch (JsonContractException failure) {
            throw contract(failure);
        }
    }

    private static void uuid(JsonWriter out, UUID value) throws IOException {
        if (value == null) out.nullValue();
        else out.value(value.toString());
    }

    private static void panels(JsonWriter out, List<PanelInstance> panels) throws IOException {
        out.beginArray();
        for (var panel : panels) {
            out.beginObject().name("id").value(panel.id().toString()).name("type").value(panel.type().toString());
            out.name("binding").beginObject().name("mode").value(panel.binding().mode().name()).name("targetDocument");
            uuid(out, panel.binding().targetDocument());
            out.endObject();
            out.name("viewState").beginArray();
            for (var entry : panel.viewState().entrySet()) {
                out.beginObject().name("key").value(entry.getKey()).name("value").beginArray();
                String value = entry.getValue();
                if (value.isEmpty()) out.value("");
                for (int start = 0; start < value.length();) {
                    int end = Math.min(value.length(), start + CHUNK_CHARS);
                    if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                    out.value(value.substring(start, end));
                    start = end;
                }
                out.endArray().endObject();
            }
            out.endArray().endObject();
        }
        out.endArray();
    }

    public static WorkspaceState decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) throw limit("workspace file exceeds byte budget");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root = object(ExactJson.parse(text).toJson(), "/");
            if (!root.has("formatVersion")) throw invalid("missing formatVersion", null);
            int version = integer(root.get("formatVersion"), "/formatVersion");
            if (version != FORMAT_VERSION) throw new CodecException(Reason.UNSUPPORTED_VERSION, "unsupported workspace format version: " + version, null);
            fields(root, Set.of("formatVersion", "activeWorkspace", "workspaces", "savedLayouts"), "/");
            var workspaces = new ArrayList<WorkspaceInstance>();
            var entries = array(root.get("workspaces"), WorkspaceState.MAX_WORKSPACES, "/workspaces");
            for (int i = 0; i < entries.size(); i++) {
                String path = "/workspaces/" + i;
                var item = object(entries.get(i), path);
                fields(item, Set.of("id", "templateId", "templateVersion", "name", "visible", "panels"), path);
                workspaces.add(new WorkspaceInstance(uuid(item.get("id"), path + "/id", false), EditorId.parse(string(item.get("templateId"), path + "/templateId")), integer(item.get("templateVersion"), path + "/templateVersion"), string(item.get("name"), path + "/name"), bool(item.get("visible"), path + "/visible"), panels(item.get("panels"), path + "/panels")));
            }
            var saved = new LinkedHashMap<UUID, List<PanelInstance>>();
            var layouts = array(root.get("savedLayouts"), WorkspaceState.MAX_WORKSPACES, "/savedLayouts");
            for (int i = 0; i < layouts.size(); i++) {
                String path = "/savedLayouts/" + i;
                var item = object(layouts.get(i), path);
                fields(item, Set.of("workspace", "panels"), path);
                var id = uuid(item.get("workspace"), path + "/workspace", false);
                if (saved.putIfAbsent(id, panels(item.get("panels"), path + "/panels")) != null) throw invalid(path + ": duplicate saved workspace", null);
            }
            return new WorkspaceState(workspaces, uuid(root.get("activeWorkspace"), "/activeWorkspace", true), saved);
        } catch (CodecException failure) {
            throw failure;
        } catch (JsonContractException failure) {
            throw contract(failure);
        } catch (CharacterCodingException | IllegalArgumentException | NullPointerException failure) {
            throw invalid("invalid workspace state", failure);
        }
    }

    private static List<PanelInstance> panels(JsonElement value, String path) {
        var entries = array(value, WorkspaceState.MAX_PANELS, path);
        var panels = new ArrayList<PanelInstance>();
        for (int i = 0; i < entries.size(); i++) {
            String at = path + "/" + i;
            var item = object(entries.get(i), at);
            fields(item, Set.of("id", "type", "binding", "viewState"), at);
            var binding = object(item.get("binding"), at + "/binding");
            fields(binding, Set.of("mode", "targetDocument"), at + "/binding");
            var mode = PanelBinding.Mode.valueOf(string(binding.get("mode"), at + "/binding/mode"));
            var state = new LinkedHashMap<String, String>();
            var values = array(item.get("viewState"), WorkspaceState.MAX_STATE_ENTRIES, at + "/viewState");
            for (int j = 0; j < values.size(); j++) {
                String entryPath = at + "/viewState/" + j;
                var entry = object(values.get(j), entryPath);
                fields(entry, Set.of("key", "value"), entryPath);
                String key = string(entry.get("key"), entryPath + "/key");
                if (key.length() > WorkspaceState.MAX_STATE_KEY) throw limit(entryPath + ": state key too long");
                var chunks = array(entry.get("value"), MAX_CHUNKS, entryPath + "/value");
                if (chunks.isEmpty()) throw invalid(entryPath + ": missing value chunks", null);
                var joined = new StringBuilder();
                for (var chunk : chunks) {
                    String piece = string(chunk, entryPath + "/value");
                    if (piece.length() > CHUNK_CHARS || joined.length() + piece.length() > WorkspaceState.MAX_STATE_VALUE) throw limit(entryPath + ": state value too long");
                    joined.append(piece);
                }
                if (state.putIfAbsent(key, joined.toString()) != null) throw invalid(entryPath + ": duplicate state key", null);
            }
            panels.add(new PanelInstance(uuid(item.get("id"), at + "/id", false), EditorId.parse(string(item.get("type"), at + "/type")), new PanelBinding(mode, uuid(binding.get("targetDocument"), at + "/binding/targetDocument", true)), state));
        }
        return List.copyOf(panels);
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) throw invalid(path + ": expected object", null);
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, int max, String path) {
        if (value == null || !value.isJsonArray()) throw invalid(path + ": expected array", null);
        var array = value.getAsJsonArray();
        if (array.size() > max) throw limit(path + ": too many entries");
        return array;
    }

    private static void fields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) throw invalid(path + ": unexpected or missing members", null);
    }

    private static String string(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(path + ": expected string", null);
        return value.getAsString();
    }

    private static boolean bool(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(path + ": expected boolean", null);
        return value.getAsBoolean();
    }

    private static int integer(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(path + ": expected integer", null);
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw invalid(path + ": integer out of range", failure);
        }
    }

    private static UUID uuid(JsonElement value, String path, boolean nullable) {
        if (nullable && value != null && value.isJsonNull()) return null;
        String text = string(value, path);
        var uuid = UUID.fromString(text);
        if (!uuid.toString().equals(text)) throw invalid(path + ": noncanonical UUID", null);
        return uuid;
    }

    private static CodecException invalid(String detail, Throwable cause) {
        return new CodecException(Reason.INVALID_FORMAT, detail, cause);
    }

    private static CodecException limit(String detail) {
        return new CodecException(Reason.LIMIT_EXCEEDED, detail, null);
    }

    private static CodecException contract(JsonContractException failure) {
        return new CodecException("LIMIT_EXCEEDED".equals(failure.code()) ? Reason.LIMIT_EXCEEDED : Reason.INVALID_FORMAT, failure.getMessage(), failure);
    }

    private static final class BudgetWriter extends Writer {

        private final StringBuilder text = new StringBuilder();
        private int bytes;
        private boolean high;

        @Override
        public void write(char[] value, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                char c = value[i];
                if (high) {
                    if (!Character.isLowSurrogate(c)) throw invalid("unpaired Unicode surrogate", null);
                    bytes += 4;
                    high = false;
                } else if (Character.isHighSurrogate(c)) high = true;
                else if (Character.isLowSurrogate(c)) throw invalid("unpaired Unicode surrogate", null);
                else bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
                if (bytes > MAX_BYTES) throw limit("workspace state exceeds byte budget");
                text.append(c);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            if (high) throw invalid("unpaired Unicode surrogate", null);
        }

        String result() {
            close();
            return text.toString();
        }
    }
}
