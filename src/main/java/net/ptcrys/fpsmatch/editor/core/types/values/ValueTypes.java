package net.ptcrys.fpsmatch.editor.core.types.values;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;

/** The extension point for real embedded value codec implementations. */
public final class ValueTypes {

    public static final ExtensionKind<ValueType<?>> KIND = valueTypeKind();

    private ValueTypes() {}

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ExtensionKind<ValueType<?>> valueTypeKind() {
        // Generic implementation classes require one erasure cast at the kind declaration only.
        return (ExtensionKind<ValueType<?>>) (ExtensionKind<?>) new ExtensionKind<>(
                EditorId.parse("editor:value_type"), ValueType.class);
    }
}
