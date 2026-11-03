package net.ptcrys.fpsmatch.editor.core.documents;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.util.List;

/**
 * Domain value contract consumed by {@link DocumentSession}.
 *
 * <p>
 * The session never mutates the value it was given. Every operation first asks the model for an
 * isolated {@link #snapshot(Object) snapshot}; only snapshot values are validated, encoded, stored
 * in history and published to callers. A model that returns a shared mutable value therefore breaks
 * the isolation guarantee and is an implementation fault, but misuse is not sandboxed: the session
 * cannot verify deep immutability of an arbitrary {@code T}.
 * </p>
 *
 * <p>
 * {@link #encode(Object)} must be deterministic for a given value: the session compares
 * encodings byte for byte to decide whether a change is semantically meaningful and whether the
 * current draft still equals the last confirmed save. It keeps the encoding as an opaque byte array
 * and copies it on every boundary crossing.
 * </p>
 *
 * @param <T> the domain value type
 */
public interface DocumentModel<T> {

    /**
     * Returns an isolated, deep immutable value for {@code value}.
     *
     * <p>
     * An implementation may return {@code value} itself when that value is already immutable.
     * The returned value must not observe later mutation of {@code value}.
     * </p>
     */
    T snapshot(T value);

    /** Returns the deterministic content encoding of a snapshot value. */
    byte[] encode(T value);

    /**
     * Returns the diagnostics for a snapshot value; an ERROR status rejects the value.
     *
     * <p>
     * The returned list is an isolated snapshot too: the session copies it, keeps every element
     * and never inspects or rewrites the messages. When it holds at least one ERROR the value is
     * rejected with a {@link DocumentRejection} whose {@link DocumentRejection#problems() problems}
     * are exactly this list, in this order, so one validation run may report a whole set of problems
     * at once. WARNING and INFO entries alone do not reject the value, and they are not cached
     * anywhere.
     * </p>
     */
    List<Problem> validate(T value);
}
