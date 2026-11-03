package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.Objects;

/** Package-private numeric guards shared by the layout value types. */
final class Bounds {

    private Bounds() {}

    /** Returns a value that is non-null and finite. */
    static double finite(Double value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        return value;
    }

    /** Returns a value that is non-null, finite and not negative. */
    static double nonNegative(Double value, String name) {
        double checked = finite(value, name);
        if (checked < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + checked);
        }
        return checked;
    }
}
