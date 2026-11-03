package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Diagnostic identities owned by the sealed save seam of the project runtime.
 *
 * <p>
 * Product sentences live in
 * {@code assets/fpsmatch/lang/*.json}; nothing here
 * hardcodes prose. Every rejection has exactly one stable code
 * ({@code editor:runtime_save/<name>}) and exactly one translation key
 * ({@code editor.runtime_save.<name>}). Identities travel only as typed arguments, the two byte
 * counts as numbers, and the argument order of a code is part of its contract:
 * </p>
 *
 * <ul>
 * <li>{@code protected_project} - the project identity; the project is in its protected state, so
 * publication is refused.</li>
 * <li>{@code pending_input} - the document identity whose raw pending input was not committed or
 * abandoned by the UI.</li>
 * <li>{@code unsaved_dependency} - the selected document identity, then the referenced document
 * identity that either has no confirmed version or has unpublished changes outside this
 * save. The referenced document is never saved implicitly.</li>
 * <li>{@code stale_capture} - the document identity whose capture was prepared after a newer
 * capture of the same document had already been accepted.</li>
 * <li>{@code capture_bytes_exceeded} - the observed capture size, then the per-capture limit; both
 * are numbers.</li>
 * <li>{@code retained_bytes_exceeded} - the retained capture size, then the retention limit; both
 * are numbers.</li>
 * <li>{@code io_busy} - no argument. Closing was refused because real project I/O, including
 * reading or preparation, is in progress; this does not describe a storage lock.</li>
 * <li>{@code queue_unavailable} - the unavailable save queue as a literal argument.</li>
 * </ul>
 *
 * <p>
 * All eight sentences are ERROR diagnostics whose target is the {@value #TARGET} address, except
 * those that name a concrete document or project. The protected project targets that project;
 * pending input, an unsaved dependency and a stale capture target the selected or captured document
 * whose operation was refused, so a reader can route the sentence to the entry it concerns.
 * </p>
 *
 * <p>
 * None of these sentences repeats the original exception: a failed I/O call keeps its own
 * throwable next to the rejection, and the product wording here always describes the refused
 * operation, never the raw failure text.
 * </p>
 */
final class SaveMessages {

    static final String NAMESPACE = "editor";

    /** The one target every save-seam diagnostic carries when no single document is to blame. */
    static final String TARGET = "project-save";

    static final EditorId PROTECTED_PROJECT = code("protected_project");
    static final EditorId PENDING_INPUT = code("pending_input");
    static final EditorId UNSAVED_DEPENDENCY = code("unsaved_dependency");
    static final EditorId STALE_CAPTURE = code("stale_capture");
    static final EditorId CAPTURE_BYTES_EXCEEDED = code("capture_bytes_exceeded");
    static final EditorId RETAINED_BYTES_EXCEEDED = code("retained_bytes_exceeded");
    static final EditorId IO_BUSY = code("io_busy");
    static final EditorId QUEUE_UNAVAILABLE = code("queue_unavailable");

    static final String KEY_PROTECTED_PROJECT = "editor.runtime_save.protected_project";
    static final String KEY_PENDING_INPUT = "editor.runtime_save.pending_input";
    static final String KEY_UNSAVED_DEPENDENCY = "editor.runtime_save.unsaved_dependency";
    static final String KEY_STALE_CAPTURE = "editor.runtime_save.stale_capture";
    static final String KEY_CAPTURE_BYTES_EXCEEDED = "editor.runtime_save.capture_bytes_exceeded";
    static final String KEY_RETAINED_BYTES_EXCEEDED = "editor.runtime_save.retained_bytes_exceeded";
    static final String KEY_IO_BUSY = "editor.runtime_save.io_busy";
    static final String KEY_QUEUE_UNAVAILABLE = "editor.runtime_save.queue_unavailable";

    private SaveMessages() {}

    private static EditorId code(String path) {
        return new EditorId(NAMESPACE, "runtime_save/" + path);
    }

    // --------------------------------------------------------------- factories

    /** The project is in the protected state, so it refuses to publish a save. */
    static SessionRejection protectedProject(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return rejection(PROTECTED_PROJECT, projectId.toString(), KEY_PROTECTED_PROJECT,
                literal(projectId.toString()));
    }

    /** The document still holds raw pending input that the UI has neither committed nor abandoned. */
    static SessionRejection pendingInput(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return rejection(PENDING_INPUT, documentId.toString(), KEY_PENDING_INPUT,
                literal(documentId.toString()));
    }

    /**
     * A selected document references {@code dependencyId}, which has no confirmed version or has
     * unpublished changes outside this save.
     */
    static SessionRejection unsavedDependency(UUID documentId, UUID dependencyId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(dependencyId, "dependencyId");
        return rejection(UNSAVED_DEPENDENCY, documentId.toString(), KEY_UNSAVED_DEPENDENCY,
                literal(documentId.toString()), literal(dependencyId.toString()));
    }

    /** This capture was prepared after a newer capture of {@code documentId} was already accepted. */
    static SessionRejection staleCapture(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return rejection(STALE_CAPTURE, documentId.toString(), KEY_STALE_CAPTURE,
                literal(documentId.toString()));
    }

    /** The capture to publish is larger than one capture may be. */
    static SessionRejection captureBytes(long observedBytes, long limit) {
        return rejection(CAPTURE_BYTES_EXCEEDED, TARGET, KEY_CAPTURE_BYTES_EXCEEDED,
                number(observedBytes), number(limit));
    }

    /** The queued and active captures retained by the project together exceed the retention limit. */
    static SessionRejection retainedBytes(long observedBytes, long limit) {
        return rejection(RETAINED_BYTES_EXCEEDED, TARGET, KEY_RETAINED_BYTES_EXCEEDED,
                number(observedBytes), number(limit));
    }

    /** Real project I/O is in progress, so close is refused until that operation finishes. */
    static SessionRejection ioBusy() {
        return rejection(IO_BUSY, TARGET, KEY_IO_BUSY);
    }

    /** The save queue this operation needs is not available. */
    static SessionRejection queueUnavailable(String queue) {
        Objects.requireNonNull(queue, "queue");
        return rejection(QUEUE_UNAVAILABLE, TARGET, KEY_QUEUE_UNAVAILABLE, literal(queue));
    }

    // ---------------------------------------------------------------- plumbing

    private static SessionRejection rejection(EditorId code, String target, String messageKey,
                                              TextContent.Argument... arguments) {
        return new SessionRejection(List.of(new Problem(Problem.Severity.ERROR, code, target,
                new TextContent.Message(messageKey, List.of(arguments)))));
    }

    private static TextContent.Argument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.Argument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
