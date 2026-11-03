package net.ptcrys.fpsmatch.editor.common.ui.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Package-private bounds and defensive copying for opaque panel view state. */
final class ViewState {

    private ViewState() {}

    /**
     * Copies a view state map with bounded sizes. The state stays opaque: keys are neither folded,
     * trimmed nor ordered, so an empty key and case-distinct keys such as {@code Key} and {@code key}
     * are preserved exactly as the caller supplied them.
     */
    static Map<String, String> copy(Map<String, String> state) {
        Objects.requireNonNull(state, "viewState");
        if (state.size() > PanelInstance.MAX_STATE_ENTRIES) {
            throw new IllegalArgumentException("too many view state entries: " + state.size());
        }
        Map<String, String> copy = new LinkedHashMap<>(Math.max(4, state.size() * 2));
        for (Map.Entry<String, String> entry : state.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "view state key");
            String value = Objects.requireNonNull(entry.getValue(), "view state value");
            if (key.length() > PanelInstance.MAX_STATE_KEY) {
                throw new IllegalArgumentException("view state key too long: " + key.length());
            }
            if (value.length() > PanelInstance.MAX_STATE_VALUE) {
                throw new IllegalArgumentException("view state value too long: " + value.length());
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
