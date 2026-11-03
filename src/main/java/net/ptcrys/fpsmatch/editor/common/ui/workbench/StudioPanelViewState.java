package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Pure validation boundary for the host-owned JSON inside an opaque panel view-state map. */
public final class StudioPanelViewState {

    private static final int MAX_PARTS = 128;

    private StudioPanelViewState() {}

    /**
     * Returns an independent JSON object only after every present host field has been validated.
     * Missing fields keep their legacy defaults at the caller. Exact int values, including negatives,
     * are preserved; the caller may clamp scroll offsets after this method succeeds. Unknown JSON
     * members, collection order and duplicate string-set entries are retained for future writeback.
     *
     * <p>
     * No {@code ui.parts} means no host JSON, unless orphaned numeric chunks are present.
     * Numeric {@code ui.N} keys and {@code ui.parts} belong to the host; other keys remain opaque.
     * Otherwise the manifest must name exactly {@code ui.0} through {@code ui.(parts-1)}. Chunks
     * are joined as UTF-16 text before parsing so a surrogate pair or JSON escape may span chunks.
     * Strict syntax, Unicode, duplicate keys and all JSON budgets come from {@link ExactJson}.
     * No external map, document, selection or cache is changed, on success or failure.
     * </p>
     *
     * @throws IllegalArgumentException if the manifest, JSON or a known host field is invalid
     */
    public static Optional<JsonObject> decode(Map<String, String> viewState) {
        if (viewState == null) throw invalid("ui.parts", "view state must not be null");
        if (!viewState.containsKey("ui.parts")) {
            if (viewState.keySet().stream().anyMatch(StudioPanelViewState::hostChunkKey)) throw invalid("ui.parts", "orphaned host chunks");
            return Optional.empty();
        }
        String manifest = viewState.get("ui.parts");
        int parts;
        try {
            parts = Integer.parseInt(manifest);
        } catch (NumberFormatException failure) {
            throw invalid("ui.parts", "expected a decimal chunk count");
        }
        if (parts < 1 || parts > MAX_PARTS || !Integer.toString(parts).equals(manifest)) throw invalid("ui.parts", "expected a canonical count from 1 to " + MAX_PARTS);

        Set<String> chunks = new HashSet<>();
        for (int i = 0; i < parts; i++) chunks.add("ui." + i);
        for (String key : viewState.keySet()) if (hostChunkKey(key) && !chunks.contains(key)) throw invalid("ui.parts", "unexpected chunk name");
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < parts; i++) {
            String key = "ui." + i, chunk = viewState.get(key);
            if (chunk == null || chunk.isEmpty()) throw invalid(key, "missing or empty chunk");
            // Valid UTF-8 uses at least as many bytes as UTF-16 code units. This cheap preflight
            // bounds assembly; ExactJson subsequently checks the exact whole-text UTF-8 budget.
            if (chunk.length() > ExactJson.MAX_UTF8_BYTES - encoded.length()) throw invalid(key, "assembled text exceeds the ExactJson input budget");
            encoded.append(chunk);
        }
        JsonElement parsed = ExactJson.parse(encoded.toString()).toJson();
        JsonObject state = object(parsed, "/");
        for (var entry : state.entrySet()) {
            String name = entry.getKey(), path = "/" + name;
            JsonElement value = entry.getValue();
            switch (name) {
                case "objectContextScroll", "fieldContextScroll" -> values(value, path, StudioPanelViewState::integer);
                case "resourceVersions", "objectSearch", "inspectorPages" -> values(value, path, StudioPanelViewState::string);
                case "objectCollapsed", "inspectorCollapsed" -> values(value, path, StudioPanelViewState::strings);
                case "objectScroll", "fieldScroll", "resourceScroll", "resourceDetailScroll", "taskScroll", "projectScroll" -> integer(value, path);
                case "listScrollVersion" -> listScrollVersion(value, path);
                case "inspectorSearch", "resourceSearch", "resourceTab", "task", "projectSearch" -> string(value, path);
                case "resource" -> uuid(value, path);
                case "active" -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(path, "expected a boolean");
                }
                default -> {}
            }
        }
        return Optional.of(state);
    }

    /**
     * Converts a legacy list row offset to pixels without changing or stamping the supplied JSON.
     * Version 1 already stores pixels. A legacy offset at or before the first row stays at the top;
     * a positive row offset skips the legacy chrome and saturates instead of overflowing an int.
     * The host stamps version 1 only when it persists the converted state.
     */
    public static int listScrollOffset(JsonObject state, int value, int legacyRowHeight, int legacyChromeHeight) {
        if (state == null) throw invalid("/", "state must not be null");
        if (state.has("listScrollVersion")) {
            listScrollVersion(state.get("listScrollVersion"), "/listScrollVersion");
            return Math.max(0, value);
        }
        if (legacyRowHeight <= 0 || legacyChromeHeight < 0) throw new IllegalArgumentException("Legacy list metrics require a positive row height and nonnegative chrome height");
        if (value <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, (long) legacyChromeHeight + (long) value * legacyRowHeight);
    }

    private static void listScrollVersion(JsonElement value, String path) {
        integer(value, path);
        if (value.getAsBigDecimal().intValueExact() != 1) throw invalid(path, "unsupported list scroll version");
    }

    private static boolean hostChunkKey(String key) {
        return key != null && key.matches("ui[.][+-]?[0-9]+");
    }

    private static JsonObject object(JsonElement value, String path) {
        if (!value.isJsonObject()) throw invalid(path, "expected an object");
        return value.getAsJsonObject();
    }

    private static void values(JsonElement value, String path, BiConsumer<JsonElement, String> validate) {
        for (var entry : object(value, path).entrySet()) validate.accept(entry.getValue(), path + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"));
    }

    private static void integer(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(path, "expected an exact int number");
        try {
            value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw invalid(path, "number must be an exact int without truncation or overflow");
        }
    }

    private static String string(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(path, "expected a string");
        return value.getAsString();
    }

    private static void strings(JsonElement value, String path) {
        if (!value.isJsonArray()) throw invalid(path, "expected an array of strings");
        int index = 0;
        for (var item : value.getAsJsonArray()) string(item, path + "/" + index++);
    }

    private static void uuid(JsonElement value, String path) {
        String text = string(value, path);
        if (text.length() != 36) throw invalid(path, "expected a 36-character UUID");
        try {
            if (!UUID.fromString(text).toString().equalsIgnoreCase(text)) throw invalid(path, "expected a standard UUID");
        } catch (IllegalArgumentException failure) {
            throw invalid(path, "expected a standard UUID");
        }
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException("Invalid panel view state at " + path + ": " + reason);
    }
}
