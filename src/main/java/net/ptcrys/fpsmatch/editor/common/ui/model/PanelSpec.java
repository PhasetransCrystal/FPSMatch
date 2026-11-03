package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;

/**
 * A panel kind contributed by a template.
 *
 * @param type              panel type identity, never {@code null}
 * @param multipleInstances whether one workspace may host more than one panel of this type
 */
public record PanelSpec(EditorId type, boolean multipleInstances) {

    public PanelSpec {
        Objects.requireNonNull(type, "type");
    }
}
