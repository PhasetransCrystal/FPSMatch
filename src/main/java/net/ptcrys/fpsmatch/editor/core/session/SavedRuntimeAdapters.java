package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;

/** Public registration point for explicitly selected saved-runtime adapters. */
public final class SavedRuntimeAdapters {

    private SavedRuntimeAdapters() {}

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static final ExtensionKind<SavedRuntimeAdapter<?, ?>> KIND = new ExtensionKind(
            new EditorId("editor", "saved_runtime_adapter"), SavedRuntimeAdapter.class);
}
