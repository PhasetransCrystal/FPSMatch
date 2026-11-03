package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;

import java.util.List;
import java.util.Map;

/** Pure initial-document recipe. Called on the owner thread before any filesystem creation. */
public interface ProjectTemplate<T> {

    EditorId category();

    TextContent label();

    EditorId documentType();

    Class<T> valueType();

    /** This template authors content against one persistent host-local Minecraft save. */
    default boolean requiresWorld() {
        return false;
    }

    List<DocumentEditor.Field> fields();

    T create(ExtensionRegistry registry, Map<String, String> raw);
}
