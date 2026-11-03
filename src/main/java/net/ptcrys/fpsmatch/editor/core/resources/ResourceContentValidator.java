package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.io.IOException;
import java.util.List;

/**
 * A trusted Java contribution that validates an existing resource version's domain format, settings,
 * explicit dependencies and necessary outputs. Validation does not import, migrate, generate bytes,
 * mount resources or install runtime state; Java contributions are not sandboxed.
 *
 * <p>
 * Register under {@link ResourceContentValidators#KIND} using the exact importer contribution id
 * and owner of the matching {@link ResourceProducers#KIND} declaration. Both contributions must be
 * usable in the same frozen registry. The declaration's exact readableVersions check precedes this
 * callback; {@code version.importerVersion()} is the version to validate, with no newest-version
 * fallback and no second version table on this interface.
 * </p>
 *
 * <p>
 * The execution checks cancellation before and between callbacks and before returning a healthy
 * result. A callback must cooperate through {@link ResourceContent#checkCancelled()}; its content
 * context is valid only for this invocation and thread. Callback scope rejects LocalProjectAccess
 * I/O reentry and SessionOwner mutation, including through another access; read-only views remain
 * allowed. Implementations own and finally-clean any private temporary files or outputs.
 * </p>
 */
@FunctionalInterface
public interface ResourceContentValidator {

    /**
     * Validates one fixed version using the complete captured content.
     *
     * <p>
     * Return a non-null list with non-null problems. INFO and WARNING are preserved; any ERROR
     * prevents a healthy publication result. Targets, messages and typed arguments remain unresolved.
     * One callback may return at most 256 problems, and the complete execution at most 1,024; size is
     * checked before copying. Across the execution diagnostic text is limited to 1,048,576 UTF-16
     * units, traversed nodes to 65,536 and Nested depth to 32. Numeric precision and absolute scale
     * are each at most 4,096. Invalid or excessive results are programming errors reported as
     * IllegalArgumentException or NullPointerException, never successful validation. Unexpected
     * RuntimeException and Error propagate unchanged, as do I/O and cancellation failures.
     * </p>
     *
     * @param version the exact immutable version being checked
     * @param content the fixed, invocation-scoped content context
     * @return the validation problems, possibly empty
     * @throws IOException for content I/O or cancellation; propagated unchanged
     */
    List<Problem> validate(ResourceVersion version, ResourceContent content) throws IOException;
}
