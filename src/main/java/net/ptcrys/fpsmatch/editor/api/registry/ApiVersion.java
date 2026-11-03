package net.ptcrys.fpsmatch.editor.api.registry;

/**
 * The API level a module was built against or provides.
 *
 * <p>
 * Both components are non-negative. A module requiring {@code required} is compatible with a
 * registry whose current level is {@code current} exactly when {@link #isCompatibleWith(ApiVersion)}
 * holds: same major, provider minor not lower than the required minor.
 * </p>
 */
public record ApiVersion(int major, int minor) {

    public ApiVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("api version components must be non-negative");
        }
    }

    /** {@code true} when this (provider) version satisfies {@code required}. */
    public boolean isCompatibleWith(ApiVersion required) {
        return major == required.major() && minor >= required.minor();
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
