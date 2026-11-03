package net.ptcrys.fpsmatch.editor.core.types.objects;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;

import java.util.Set;

/** A value type with a semantic role and references within its containing graph. */
public interface ObjectType<T> extends ValueType<T> {

    EditorId role();

    default Set<ObjectReference> references(T snapshot) {
        return Set.of();
    }
}
