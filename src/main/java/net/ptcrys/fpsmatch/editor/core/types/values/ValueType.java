package net.ptcrys.fpsmatch.editor.core.types.values;

import net.ptcrys.fpsmatch.editor.core.types.CodecType;

/**
 * A registered, pure codec for an embedded value, without a default creation operation.
 *
 * <p>
 * Composite implementations must enumerate every nested contribution and locked resource in
 * their dependencies. Content they cannot understand must be rejected, never silently omitted.
 * Implementations perform no I/O and return deep immutable snapshots.
 * </p>
 *
 * @param <T> the embedded value type
 */
public interface ValueType<T> extends CodecType<T> {}
