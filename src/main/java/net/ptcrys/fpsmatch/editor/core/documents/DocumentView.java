package net.ptcrys.fpsmatch.editor.core.documents;

import java.util.Map;
import java.util.Objects;

/**
 * An immutable reading of a session.
 *
 * <p>
 * {@code content} is the deep immutable snapshot owned by the session, not a live mutable model
 * value. The session computes it once per published state, so reading many views never re-encodes
 * or re-snapshots the domain value. {@code pendingInputs} is a copy: later session changes are not
 * visible through a previously returned view.
 * </p>
 *
 * @param <T> the domain value type
 */
public record DocumentView<T>(
                              DocumentToken token,
                              T content,
                              boolean dirty,
                              boolean canUndo,
                              boolean canRedo,
                              Map<String, String> pendingInputs) {

    public DocumentView {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(pendingInputs, "pendingInputs");
        pendingInputs = Map.copyOf(pendingInputs);
    }
}
