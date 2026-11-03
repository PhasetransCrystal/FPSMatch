package net.ptcrys.fpsmatch.editor.api.extensions;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;

/**
 * One extension point contract: the contribution kind dimension plus the trusted value type every
 * implementation published under the same key must be an instance of.
 *
 * <p>
 * The {@code id} is the {@code kind} half of a {@link net.ptcrys.fpsmatch.editor.api.registry.ContributionKey},
 * so a frozen registry can publish metadata and the real implementation under one key. The set of
 * kinds is open: an ordinary third party declares its own extension point by defining its own kind,
 * and nothing in this module enumerates known kinds.
 * </p>
 *
 * <p>
 * {@code valueType} must be a reference type: primitives and {@code void} are rejected, because a
 * kind is a contract about an object the extension point will actually call. Contracts are compared
 * by {@link Class} identity, never by class name, so two look-alike classes from different loaders
 * are different contracts.
 * </p>
 *
 * @param <T> the trusted implementation type of this extension point
 */
public record ExtensionKind<T>(EditorId id, Class<T> valueType) {

    public ExtensionKind {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(valueType, "valueType");
        if (valueType.isPrimitive() || valueType == void.class) {
            throw new IllegalArgumentException(
                    "extension value type must not be primitive or void: " + valueType.getName());
        }
    }

    /** Diagnostic text only; contracts are compared by class identity, not by this string. */
    @Override
    public String toString() {
        return id + " -> " + valueType.getName();
    }
}
