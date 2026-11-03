package net.ptcrys.fpsmatch.editor.common.text.java2d;

import java.util.Objects;

/** An explicitly matched installed system family; this is not an embedded font resource. */
public record InstalledFont(FontFaceId face, String family) {

    public InstalledFont {
        Objects.requireNonNull(face);
        Objects.requireNonNull(family);
    }
}
