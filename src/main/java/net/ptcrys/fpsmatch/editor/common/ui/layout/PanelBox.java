package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.Objects;
import java.util.UUID;

/**
 * The solved geometry of one panel.
 *
 * @param panelId      registered panel identity
 * @param bounds       body rectangle; for a tab member this is the shared tab body
 * @param visible      {@code true} for a leaf and for the active member of a tab group
 * @param meetsMinimum whether the actual body satisfies this panel's registered minimum
 */
public record PanelBox(UUID panelId, Rect bounds, boolean visible, boolean meetsMinimum) {

    public PanelBox {
        Objects.requireNonNull(panelId, "panelId");
        Objects.requireNonNull(bounds, "bounds");
    }
}
