package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Diagnostic identities and message keys owned by the save queue. Product sentences live in
 * {@code assets/fpsmatch/lang/*.json}; nothing here hardcodes prose, and
 * storage or runtime detail is passed only as a technical argument.
 *
 * <p>
 * Two consequences of that boundary are deliberate. A category that is part of the product
 * sentence selects a whole sentence per category instead of travelling as a translatable argument
 * (see {@link #missingDependency}), and a numeric quantity is passed as a typed
 * {@link TextContent.NumberArgument} so that formatting belongs to the language layer and not to
 * this module.
 *
 * <p>
 * Queue problems are ordered by the caller (validator output first, then the single structural
 * failure that rejected the head). The codes are stable and are part of this module's contract.
 */
final class Messages {

    static final String NAMESPACE = "editor";

    static final EditorId MODULE_VERSION_CONFLICT = code("module_version_conflict");
    static final EditorId ENTRY_IDENTITY_CONFLICT = code("entry_identity_conflict");
    static final EditorId MISSING_OWNER = code("missing_owner");
    static final EditorId MISSING_DEPENDENCY = code("missing_dependency");
    static final EditorId ENTRY_BUDGET_EXCEEDED = code("entry_budget_exceeded");
    static final EditorId DEPENDENCY_BUDGET_EXCEEDED = code("dependency_budget_exceeded");
    static final EditorId INVALID_CANDIDATE = code("invalid_candidate");
    static final EditorId VALIDATOR_FAILED = code("validator_failed");
    static final EditorId CONFLICT = code("conflict");
    static final EditorId CONFLICT_UNOBSERVED = code("conflict_unobserved");
    static final EditorId UNKNOWN = code("unknown");
    static final EditorId REJECTED = code("rejected");

    static final String KEY_MODULE_VERSION_CONFLICT = "editor.save.module_version_conflict";
    static final String KEY_ENTRY_IDENTITY_CONFLICT = "editor.save.entry_identity_conflict";
    static final String KEY_MISSING_OWNER = "editor.save.missing_owner";
    static final String KEY_MISSING_DOCUMENT_DEPENDENCY = "editor.save.missing_document_dependency";
    static final String KEY_MISSING_ASSET_DEPENDENCY = "editor.save.missing_asset_dependency";
    static final String KEY_ENTRY_BUDGET_EXCEEDED = "editor.save.entry_budget_exceeded";
    static final String KEY_DEPENDENCY_BUDGET_EXCEEDED = "editor.save.dependency_budget_exceeded";
    static final String KEY_INVALID_CANDIDATE = "editor.save.invalid_candidate";
    static final String KEY_VALIDATOR_FAILED = "editor.save.validator_failed";
    static final String KEY_CONFLICT = "editor.save.conflict";
    static final String KEY_CONFLICT_UNOBSERVED = "editor.save.conflict_unobserved";
    static final String KEY_UNKNOWN = "editor.save.unknown";
    static final String KEY_REJECTED = "editor.save.rejected";

    private Messages() {}

    private static EditorId code(String path) {
        return new EditorId(NAMESPACE, "save/" + path);
    }

    static Problem moduleVersionConflict(UUID requestId, String module, String requested, String saved) {
        return error(MODULE_VERSION_CONFLICT, module, KEY_MODULE_VERSION_CONFLICT,
                requestId.toString(), module, requested, saved);
    }

    static Problem entryIdentityConflict(UUID requestId, UUID id) {
        return error(ENTRY_IDENTITY_CONFLICT, id.toString(), KEY_ENTRY_IDENTITY_CONFLICT,
                requestId.toString(), id.toString());
    }

    static Problem missingOwner(UUID requestId, UUID entryId, String owner) {
        return error(MISSING_OWNER, entryId.toString(), KEY_MISSING_OWNER,
                requestId.toString(), entryId.toString(), owner);
    }

    /**
     * A dangling document or asset reference. The depended-on category is part of the sentence, so it
     * selects the whole message key and never becomes an argument; the arguments stay
     * {@code [requestId, entryId, dependencyId]} for both categories.
     */
    static Problem missingDependency(UUID requestId, UUID entryId, BlobRef.Kind kind, UUID dependencyId) {
        return error(MISSING_DEPENDENCY, entryId.toString(), dependencyKey(kind),
                requestId.toString(), entryId.toString(), dependencyId.toString());
    }

    private static String dependencyKey(BlobRef.Kind kind) {
        return switch (kind) {
            case DOCUMENT -> KEY_MISSING_DOCUMENT_DEPENDENCY;
            case ASSET -> KEY_MISSING_ASSET_DEPENDENCY;
        };
    }

    static Problem entryBudgetExceeded(UUID requestId, long count, int limit) {
        return error(ENTRY_BUDGET_EXCEEDED, requestId.toString(), KEY_ENTRY_BUDGET_EXCEEDED,
                List.of(new TextContent.LiteralArgument(requestId.toString()), number(count), number(limit)));
    }

    static Problem dependencyBudgetExceeded(UUID requestId, long count, int limit) {
        return error(DEPENDENCY_BUDGET_EXCEEDED, requestId.toString(), KEY_DEPENDENCY_BUDGET_EXCEEDED,
                List.of(new TextContent.LiteralArgument(requestId.toString()), number(count), number(limit)));
    }

    static Problem invalidCandidate(UUID requestId, String detail) {
        return error(INVALID_CANDIDATE, requestId.toString(), KEY_INVALID_CANDIDATE,
                requestId.toString(), detail);
    }

    static Problem validatorFailed(UUID requestId, String detail) {
        return error(VALIDATOR_FAILED, requestId.toString(), KEY_VALIDATOR_FAILED,
                requestId.toString(), detail);
    }

    static Problem conflict(UUID requestId, UUID expected, UUID observed) {
        return error(CONFLICT, requestId.toString(), KEY_CONFLICT,
                requestId.toString(), expected.toString(), observed.toString());
    }

    static Problem conflictUnobserved(UUID requestId, UUID expected) {
        return error(CONFLICT_UNOBSERVED, requestId.toString(), KEY_CONFLICT_UNOBSERVED,
                requestId.toString(), expected.toString());
    }

    static Problem unknown(UUID requestId, String detail) {
        return error(UNKNOWN, requestId.toString(), KEY_UNKNOWN, requestId.toString(), detail);
    }

    static Problem rejected(UUID requestId, String code, String detail) {
        return error(REJECTED, requestId.toString(), KEY_REJECTED, requestId.toString(), code, detail);
    }

    /** Technical identity of a failure; never a product sentence and never a user-facing reason. */
    static String technical(Throwable failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getName();
        return message == null || message.isEmpty() ? type : type + ": " + message;
    }

    private static Problem error(EditorId code, String target, String messageKey, String... arguments) {
        List<TextContent.Argument> values = new ArrayList<>(arguments.length);
        for (String argument : arguments) {
            values.add(new TextContent.LiteralArgument(argument));
        }
        return error(code, target, messageKey, values);
    }

    /**
     * Typed entry point for the messages that carry a numeric quantity; the language layer, not this
     * module, decides how the number is written.
     */
    private static Problem error(EditorId code, String target, String messageKey,
                                 List<TextContent.Argument> arguments) {
        return new Problem(Problem.Severity.ERROR, code, target, new TextContent.Message(messageKey, arguments));
    }

    private static TextContent.NumberArgument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
