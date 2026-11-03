package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;

import java.util.*;

/** Registered, pure creation contribution. The host captures identity, parent and document token. */
public interface ObjectCreator<T> {

    record Target(Optional<String> parent, Optional<TextContent> unavailable) {

        public Target {
            Objects.requireNonNull(parent).ifPresent(DocumentEditor::requireAddress);
            Objects.requireNonNull(unavailable);
            if (parent.isPresent() == unavailable.isPresent()) throw new IllegalArgumentException("creation target or reason required");
        }

        public static Target available(String parent) {
            return new Target(Optional.of(parent), Optional.empty());
        }

        public static Target unavailable(TextContent reason) {
            return new Target(Optional.empty(), Optional.of(reason));
        }
    }

    EditorId documentType();

    Class<T> valueType();

    TextContent label();

    TextContent category();

    default ObjectCreator<T> bind(ExtensionRegistry registry) {
        return this;
    }

    /** Resolve an explicit legal parent from the captured selection, or explain the missing prerequisite. */
    Target target(T value, Optional<String> selection);

    /** Explicit compatibility for creating and attaching to one existing ordered reference property. */
    default boolean supportsReference(T value, String node, String property) {
        return false;
    }

    /** Complete creation form, bounded like DocumentEditor.fields. No persistent object exists yet. */
    List<DocumentEditor.Field> fields(T value, String parent);

    /** Return a complete candidate; reject invalid input atomically. Redo reuses this exact identity. */
    T create(T value, String parent, UUID identity, Map<String, String> raw);
}
