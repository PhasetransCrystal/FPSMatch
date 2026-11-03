package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Trusted synchronous preparation of module-owned immutable data from one saved document.
 * Registered under {@link SavedRuntimeAdapters#KIND}, with a required contribution for the declared
 * document type. Descriptors remain stable across binding and use exact Class identities.
 *
 * <p>
 * This contract grants no world authority and is not a sandbox. Implementations must not acquire
 * world objects, modify files or publish runtime state. Results must be non-null immutable data,
 * without closeable resources or native handles. The host chooses the calling execution context;
 * callbacks and cooperative cancellation run synchronously in that context.
 * </p>
 */
public interface SavedRuntimeAdapter<T, R> {

    EditorId documentType();

    Class<T> inputType();

    Class<R> resultType();

    /** Resolves only this registry; binding must not acquire resources or open files/worlds. */
    default SavedRuntimeAdapter<T, R> bind(ExtensionRegistry registry) {
        return this;
    }

    /**
     * Consumes the isolated saved content, never the editing draft or a later CURRENT selection.
     * Poll cancellation during bounded work and release all temporary resources before returning
     * or throwing. I/O exceptions are allowed for failure reporting, not permission to write files.
     */
    R prepare(SavedRuntimeInput<T> input, BooleanSupplier cancelled) throws IOException;
}
