package net.ptcrys.fpsmatch.editor.core.documents;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Diagnostic identities owned by the document session.
 *
 * <p>
 * Product sentences live in
 * {@code assets/fpsmatch/lang/*.json}; nothing here
 * hardcodes prose. Every state rejection has exactly one stable code
 * ({@code editor:document/<name>}) and exactly one translation key
 * ({@code editor.document.<name>}), the target is the current document id, and technical
 * identifiers, revisions and limits are passed only as typed arguments. The argument order of a
 * code is part of its contract and is fixed:
 *
 * <ul>
 * <li>{@code nothing_to_undo}, {@code nothing_to_redo}, {@code reentrant_mutation},
 * {@code capture_during_mutation}: no arguments.</li>
 * <li>{@code wrong_branch}: token document id, token branch id, token revision, current branch id,
 * current revision.</li>
 * <li>{@code future_revision}: token revision, current revision.</li>
 * <li>{@code older_confirmation}: token revision, confirmed revision.</li>
 * <li>{@code capture_not_retained}: token revision, current revision, history depth.</li>
 * <li>{@code foreign_capture}: capture document id, capture branch id, capture revision.</li>
 * <li>{@code stale_token}: operation, expected document id, expected branch id, expected revision,
 * current branch id, current revision.</li>
 * <li>{@code pending_address_limit}, {@code pending_text_limit}, {@code pending_count_limit}: the
 * observed length or count, then the limit it crossed.</li>
 * </ul>
 */
final class Messages {

    static final String NAMESPACE = "editor";

    static final EditorId NOTHING_TO_UNDO = code("nothing_to_undo");
    static final EditorId NOTHING_TO_REDO = code("nothing_to_redo");
    static final EditorId WRONG_BRANCH = code("wrong_branch");
    static final EditorId FUTURE_REVISION = code("future_revision");
    static final EditorId OLDER_CONFIRMATION = code("older_confirmation");
    static final EditorId CAPTURE_NOT_RETAINED = code("capture_not_retained");
    static final EditorId FOREIGN_CAPTURE = code("foreign_capture");
    static final EditorId REENTRANT_MUTATION = code("reentrant_mutation");
    static final EditorId CAPTURE_DURING_MUTATION = code("capture_during_mutation");
    static final EditorId STALE_TOKEN = code("stale_token");
    static final EditorId PENDING_ADDRESS_LIMIT = code("pending_address_limit");
    static final EditorId PENDING_TEXT_LIMIT = code("pending_text_limit");
    static final EditorId PENDING_COUNT_LIMIT = code("pending_count_limit");

    static final String KEY_NOTHING_TO_UNDO = "editor.document.nothing_to_undo";
    static final String KEY_NOTHING_TO_REDO = "editor.document.nothing_to_redo";
    static final String KEY_WRONG_BRANCH = "editor.document.wrong_branch";
    static final String KEY_FUTURE_REVISION = "editor.document.future_revision";
    static final String KEY_OLDER_CONFIRMATION = "editor.document.older_confirmation";
    static final String KEY_CAPTURE_NOT_RETAINED = "editor.document.capture_not_retained";
    static final String KEY_FOREIGN_CAPTURE = "editor.document.foreign_capture";
    static final String KEY_REENTRANT_MUTATION = "editor.document.reentrant_mutation";
    static final String KEY_CAPTURE_DURING_MUTATION = "editor.document.capture_during_mutation";
    static final String KEY_STALE_TOKEN = "editor.document.stale_token";
    static final String KEY_PENDING_ADDRESS_LIMIT = "editor.document.pending_address_limit";
    static final String KEY_PENDING_TEXT_LIMIT = "editor.document.pending_text_limit";
    static final String KEY_PENDING_COUNT_LIMIT = "editor.document.pending_count_limit";

    private Messages() {}

    private static EditorId code(String path) {
        return new EditorId(NAMESPACE, "document/" + path);
    }

    static DocumentRejection nothingToUndo(UUID documentId) {
        return rejection(error(NOTHING_TO_UNDO, documentId, KEY_NOTHING_TO_UNDO));
    }

    static DocumentRejection nothingToRedo(UUID documentId) {
        return rejection(error(NOTHING_TO_REDO, documentId, KEY_NOTHING_TO_REDO));
    }

    static DocumentRejection wrongBranch(UUID documentId, UUID tokenDocumentId, UUID tokenBranchId,
                                         long tokenRevision, UUID currentBranchId, long currentRevision) {
        return rejection(error(WRONG_BRANCH, documentId, KEY_WRONG_BRANCH,
                id(tokenDocumentId), id(tokenBranchId), count(tokenRevision), id(currentBranchId),
                count(currentRevision)));
    }

    static DocumentRejection futureRevision(UUID documentId, long tokenRevision, long currentRevision) {
        return rejection(error(FUTURE_REVISION, documentId, KEY_FUTURE_REVISION,
                count(tokenRevision), count(currentRevision)));
    }

    static DocumentRejection olderConfirmation(UUID documentId, long tokenRevision, long confirmedRevision) {
        return rejection(error(OLDER_CONFIRMATION, documentId, KEY_OLDER_CONFIRMATION,
                count(tokenRevision), count(confirmedRevision)));
    }

    static DocumentRejection captureNotRetained(UUID documentId, long tokenRevision, long currentRevision,
                                                int historyEntries) {
        return rejection(error(CAPTURE_NOT_RETAINED, documentId, KEY_CAPTURE_NOT_RETAINED,
                count(tokenRevision), count(currentRevision), count(historyEntries)));
    }

    static DocumentRejection foreignCapture(UUID documentId, UUID captureDocumentId, UUID captureBranchId,
                                            long captureRevision) {
        return rejection(error(FOREIGN_CAPTURE, documentId, KEY_FOREIGN_CAPTURE,
                id(captureDocumentId), id(captureBranchId), count(captureRevision)));
    }

    static DocumentRejection reentrantMutation(UUID documentId) {
        return rejection(error(REENTRANT_MUTATION, documentId, KEY_REENTRANT_MUTATION));
    }

    static DocumentRejection captureDuringMutation(UUID documentId) {
        return rejection(error(CAPTURE_DURING_MUTATION, documentId, KEY_CAPTURE_DURING_MUTATION));
    }

    static DocumentRejection staleToken(UUID documentId, String operation, UUID expectedDocumentId,
                                        UUID expectedBranchId, long expectedRevision, UUID currentBranchId, long currentRevision) {
        return rejection(error(STALE_TOKEN, documentId, KEY_STALE_TOKEN,
                text(operation), id(expectedDocumentId), id(expectedBranchId), count(expectedRevision),
                id(currentBranchId), count(currentRevision)));
    }

    static DocumentRejection pendingAddressLimit(UUID documentId, int length, int limit) {
        return rejection(error(PENDING_ADDRESS_LIMIT, documentId, KEY_PENDING_ADDRESS_LIMIT,
                count(length), count(limit)));
    }

    static DocumentRejection pendingTextLimit(UUID documentId, int length, int limit) {
        return rejection(error(PENDING_TEXT_LIMIT, documentId, KEY_PENDING_TEXT_LIMIT,
                count(length), count(limit)));
    }

    static DocumentRejection pendingCountLimit(UUID documentId, int count, int limit) {
        return rejection(error(PENDING_COUNT_LIMIT, documentId, KEY_PENDING_COUNT_LIMIT,
                count(count), count(limit)));
    }

    private static DocumentRejection rejection(Problem problem) {
        return new DocumentRejection(List.of(problem));
    }

    private static Problem error(EditorId code, UUID documentId, String key,
                                 TextContent.Argument... arguments) {
        return new Problem(Problem.Severity.ERROR, code, documentId.toString(),
                new TextContent.Message(key, List.of(arguments)));
    }

    private static TextContent.Argument id(UUID value) {
        return new TextContent.LiteralArgument(value.toString());
    }

    private static TextContent.Argument text(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.Argument count(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
