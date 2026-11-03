package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only content of one fixed validation capture, with no live resource selection or host state.
 * All maps are immutable, ordered by UUID and cover the complete capture, including retained versions
 * that are inactive or unreferenced by documents. No current-version pointer, editable logical name,
 * filesystem path or storage instance is exposed.
 *
 * <p>
 * Every method is valid only on the calling thread while its single
 * {@link ResourceContentValidator#validate(ResourceVersion, ResourceContent)} callback is active.
 * Use after return or failure, from another thread, or during a later version's callback is rejected.
 * Implementations must revoke the context in a finally boundary;
 * retaining it does not extend its lifetime.
 * </p>
 *
 * <p>
 * Budgets apply to one complete validation execution, across all callbacks: at most 65,536 copy
 * requests and 32 GiB of requested declared bytes. Even a zero-length copy counts as a request;
 * repeated copies count their full declared length each time. Remaining budgets are checked before
 * reading. The complete byte set may declare at most 16 GiB after deduplication by BlobRef. Budget
 * violations are {@link IllegalArgumentException}; these are runtime limits, not disk-format rules.
 * The validator owns any private temporary materialization and its cleanup.
 * </p>
 */
public interface ResourceContent {

    /** Stable logical resource id to resource type for the complete capture. */
    Map<UUID, EditorId> resourceTypes();

    /** Every fixed version, keyed by its version id. */
    Map<UUID, ResourceVersion> versions();

    /** Only fixed byte entries available for copying, keyed by entry id. */
    Map<UUID, ProjectState.Entry> byteEntries();

    /**
     * Copies one listed entry through storage's full BlobRef length and SHA authentication.
     * Cancellation is checked before copying and between output blocks of at most 64 KiB.
     * The target is never closed by the platform; any failure may leave partial output in it.
     *
     * @throws NullPointerException                 if entryId or target is null
     * @throws IllegalArgumentException             if the entry is not listed or an execution budget is exceeded
     * @throws IOException                          if reading, authentication or output fails; failures propagate
     *                                              unchanged
     * @throws ResourceValidationCancelledException if cancellation is observed
     */
    void copyContent(UUID entryId, OutputStream target) throws IOException;

    /**
     * Checks cooperative cancellation. Validators must call this in their own CPU loops too.
     * The platform cannot forcibly stop arbitrary Java/native work, blocking I/O or spawned threads;
     * no fixed cancellation latency is promised. Cancellation supplier failures propagate unchanged.
     *
     * @throws ResourceValidationCancelledException if cancellation is observed
     */
    void checkCancelled() throws ResourceValidationCancelledException;
}
