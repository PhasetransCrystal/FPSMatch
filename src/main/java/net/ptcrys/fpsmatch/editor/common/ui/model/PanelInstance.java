package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One panel inside one workspace. View state is opaque presentation data; it never carries domain
 * content, and it is copied defensively on both construction and read.
 *
 * @param id        panel instance identity, never {@code null}
 * @param type      panel type identity, never {@code null}
 * @param binding   document binding, never {@code null}
 * @param viewState opaque view state, immutable, at most 256 entries
 */
public record PanelInstance(UUID id, EditorId type, PanelBinding binding, Map<String, String> viewState) {

    /** Maximum number of view state entries. */
    static final int MAX_STATE_ENTRIES = 256;
    /** Maximum view state key length in characters. */
    static final int MAX_STATE_KEY = 256;
    /** Maximum view state value length in characters. */
    static final int MAX_STATE_VALUE = 65536;

    public PanelInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(binding, "binding");
        viewState = ViewState.copy(viewState);
    }
}
