package net.ptcrys.fpsmatch.editor.core.documents;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * A single-document draft session: draft value, revision tracking, bounded history and pending
 * raw text input.
 *
 * <p>
 * <b>Publication is atomic.</b> A command runs against an isolated snapshot. Nothing changes -
 * not the content, not the token, not the history and not the dirty flag - unless the whole
 * sequence succeeds: command, snapshot of its result, validation with no ERROR diagnostic, and
 * deterministic encoding. A throwing command or an invalid result leaves the session exactly as it
 * was.
 * </p>
 *
 * <p>
 * <b>Revision and dirtiness are separate.</b> {@code revision} counts every published content
 * change, including undo and redo, and a change that encodes identically does not produce a history
 * entry or a new revision. Dirtiness is never derived from the revision: it compares the current
 * encoding with the encoding of the most recently confirmed save.
 * </p>
 *
 * <p>
 * <b>History is bounded</b> by {@code maxHistoryEntries}, which may be zero. Trimming drops the
 * oldest entries; a token captured before the retained window can no longer be confirmed as saved
 * and is rejected instead of pretending success. This class holds no durable save queue: a later
 * project session is responsible for keeping in-flight captures, so replacing the baseline here
 * does not by itself promise recovery of an old branch.
 * </p>
 *
 * <p>
 * <b>Saves are captured, not queued.</b> {@link #captureForSave()} hands out an immutable
 * {@link DocumentCapture} holding the token, the content and the encoding of the current revision,
 * and {@link #confirmSaved(DocumentCapture)} confirms such a capture later without depending on the
 * retained history window. The caller's save queue owns those captures; this class keeps no list of
 * them. Every history entry keeps the label of the command that produced it, readable through
 * {@link #undoLabel()} and {@link #redoLabel()}.
 * </p>
 *
 * <p>
 * <b>Mutations are not reentrant.</b> Every state-changing method holds a guard for its whole
 * duration, including the model callbacks and the edit command. A state-changing call into the same
 * session from that window is rejected with {@link DocumentRejection} before it can touch anything;
 * {@link #view()} stays readable and returns the last published state. The guard is released even
 * when the mutation fails, so a rejected mutation cannot lock the session.
 * </p>
 *
 * <p>
 * <b>Rejections are diagnosable.</b> No state rejection relies on an English sentence: each one
 * carries a stable {@code editor:document/...} code, a translation key and typed arguments inside
 * {@link DocumentRejection#problems()}, and a rejection caused by domain validation keeps the whole
 * diagnostic list of that validation run. Product sentences belong to this module's {@code lang}
 * resources, so presentation layers translate the problems instead of parsing a message.
 * </p>
 *
 * @param <T> the domain value type
 */
public final class DocumentSession<T> {

    /** Maximum number of pending input entries. */
    private static final int MAX_PENDING_INPUTS = 1024;
    /** Maximum length of a pending input address in characters. */
    private static final int MAX_PENDING_ADDRESS_LENGTH = 1024;
    /** Maximum length of a pending input raw text in characters. */
    private static final int MAX_PENDING_RAW_LENGTH = 65536;

    private final UUID documentId;
    private final DocumentModel<T> model;
    private final int maxHistoryEntries;

    private UUID branchId;
    private long revision;
    private T content;
    private byte[] contentEncoding;

    private final List<HistoryEntry<T>> past = new ArrayList<>();
    private final List<HistoryEntry<T>> future = new ArrayList<>();
    private final Map<Long, byte[]> retainedEncodings = new HashMap<>();

    private byte[] savedEncoding;
    /** Derived only when content or its confirmed baseline changes, never while reading a view. */
    private boolean dirty;
    private long confirmedRevision;
    private final Map<String, String> pendingInputs = new LinkedHashMap<>();

    /** Set while a public mutation is in progress, so that callback reentry is rejected. */
    private boolean mutating;

    /**
     * Non-forgeable identity of this session, handed only to the captures this session issues and
     * never published; {@link #confirmSaved(DocumentCapture)} compares it by identity.
     */
    private final Object issuer = new Object();

    /**
     * Creates a session for {@code initial}.
     *
     * <p>
     * The initial value is snapshotted and validated immediately; an ERROR diagnostic, a null
     * snapshot or a null encoding rejects construction.
     * </p>
     *
     * @param maxHistoryEntries bounded history depth; zero disables undo and redo
     */
    public DocumentSession(UUID documentId, T initial, DocumentModel<T> model, int maxHistoryEntries) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.model = Objects.requireNonNull(model, "model");
        if (maxHistoryEntries < 0) {
            throw new IllegalArgumentException("maxHistoryEntries must be non-negative");
        }
        this.maxHistoryEntries = maxHistoryEntries;
        this.branchId = UUID.randomUUID();
        this.revision = 0L;

        T snapshot = requireValidSnapshot(initial);
        byte[] encoding = requireEncoding(snapshot);
        this.content = snapshot;
        this.contentEncoding = encoding;
        this.retainedEncodings.put(0L, encoding);
        this.savedEncoding = encoding;
    }

    /** Returns an immutable reading of the current published state. */
    public DocumentView<T> view() {
        return new DocumentView<>(
                token(),
                content,
                dirty,
                !past.isEmpty(),
                !future.isEmpty(),
                pendingInputs);
    }

    /**
     * Applies {@code command} to an isolated snapshot of the current content and publishes the
     * result only when it validates and encodes.
     *
     * <p>
     * The command runs inside the mutation guard: the model callbacks and the command itself may
     * only read this session through {@link #view()}.
     * </p>
     */
    public void edit(DocumentToken expected, TextContent label, UnaryOperator<T> command) {
        guarded(() -> {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(command, "command");
            requireCurrentToken(expected, "edit");

            T isolated = requireValidSnapshot(content);
            T candidate = command.apply(isolated);
            T snapshot = requireValidSnapshot(candidate);
            byte[] encoding = requireEncoding(snapshot);

            if (Arrays.equals(encoding, contentEncoding)) {
                return;
            }
            T before = content;
            byte[] beforeEncoding = contentEncoding;
            past.add(new HistoryEntry<>(before, beforeEncoding, snapshot, encoding, label));
            future.clear();
            trim(past);
            publish(snapshot, encoding);
        });
    }

    /** Reverts the last published change; unavailable when the history is empty. */
    public void undo(DocumentToken expected) {
        guarded(() -> {
            requireCurrentToken(expected, "undo");
            if (past.isEmpty()) {
                throw Messages.nothingToUndo(documentId);
            }
            HistoryEntry<T> entry = past.remove(past.size() - 1);
            future.add(entry);
            publish(entry.before(), entry.beforeEncoding());
        });
    }

    /** Reapplies the last undone change; unavailable when nothing was undone. */
    public void redo(DocumentToken expected) {
        guarded(() -> {
            requireCurrentToken(expected, "redo");
            if (future.isEmpty()) {
                throw Messages.nothingToRedo(documentId);
            }
            HistoryEntry<T> entry = future.remove(future.size() - 1);
            past.add(entry);
            publish(entry.after(), entry.afterEncoding());
        });
    }

    /**
     * Returns the label of the command that {@link #undo} would revert, or empty when the branch has
     * nothing to undo.
     *
     * <p>
     * The label is the caller's own label of the command that produced the change; undo never
     * invents one, and a command that changes nothing adds neither an entry nor a label.
     * </p>
     */
    public Optional<TextContent> undoLabel() {
        return past.isEmpty() ? Optional.empty() : Optional.of(past.get(past.size() - 1).label());
    }

    /**
     * Returns the label of the command that {@link #redo} would reapply, or empty when nothing was
     * undone. The label is the original command label recorded when that change was first applied.
     */
    public Optional<TextContent> redoLabel() {
        return future.isEmpty() ? Optional.empty() : Optional.of(future.get(future.size() - 1).label());
    }

    /**
     * Confirms {@code captured} as the content that was written to durable storage.
     *
     * <p>
     * The captured encoding becomes the save baseline; the current draft, the revision, the
     * history and the pending inputs are untouched, so an edit made after the capture stays dirty
     * and a late confirmation of an older retained token is honoured. Repeating the same token is
     * idempotent. A token of another document or branch, a revision from the future, or a revision
     * already trimmed out of the retained window is rejected.
     * </p>
     */
    public void markSaved(DocumentToken captured) {
        guarded(() -> {
            Objects.requireNonNull(captured, "captured");
            if (!documentId.equals(captured.documentId()) || !branchId.equals(captured.branchId())) {
                throw Messages.wrongBranch(documentId, captured.documentId(), captured.branchId(),
                        captured.revision(), branchId, revision);
            }
            long capturedRevision = captured.revision();
            if (capturedRevision > revision) {
                throw Messages.futureRevision(documentId, capturedRevision, revision);
            }
            if (capturedRevision < confirmedRevision) {
                throw Messages.olderConfirmation(documentId, capturedRevision, confirmedRevision);
            }
            byte[] encoding = retainedEncodings.get(capturedRevision);
            if (encoding == null) {
                throw Messages.captureNotRetained(documentId, capturedRevision, revision,
                        maxHistoryEntries);
            }
            savedEncoding = encoding;
            dirty = !Arrays.equals(contentEncoding, savedEncoding);
            confirmedRevision = capturedRevision;
        });
    }

    /**
     * Captures the current token, content and already computed encoding for a later save.
     *
     * <p>
     * This is a read: it does not re-encode, does not change the dirty flag, the history, the
     * revision or the pending input, and it keeps no list of the captures it hands out. The returned
     * capture stays valid for {@link #confirmSaved(DocumentCapture)} even after the history window
     * has dropped the captured revision.
     * </p>
     *
     * <p>
     * Taking a capture from inside an in-flight mutation of this session - that is, from an edit
     * command or from a model callback - is rejected with {@link DocumentRejection}; {@link #view()}
     * stays readable.
     * </p>
     */
    public DocumentCapture<T> captureForSave() {
        if (mutating) {
            throw Messages.captureDuringMutation(documentId);
        }
        return new DocumentCapture<>(issuer, token(), content, contentEncoding);
    }

    /**
     * Confirms a capture as written to durable storage.
     *
     * <p>
     * Confirmation is independent of the retained-token window used by {@link #markSaved(DocumentToken)}:
     * a capture accepted here was issued by this very session, belongs to the current branch, and
     * carries a revision that is neither from the future nor older than the last confirmation. Its
     * own encoding is the new save baseline, so a revision already trimmed out of the history window
     * still confirms. Confirming the same capture again is idempotent.
     * </p>
     *
     * <p>
     * The current draft, the revision, the history and the pending input are untouched, so an
     * edit made after the capture stays dirty and history is never cleared by a save. A capture from
     * another session - even one with the same {@code documentId} - and a capture of a branch that
     * was replaced are rejected.
     * </p>
     */
    public void confirmSaved(DocumentCapture<T> captured) {
        guarded(() -> {
            Objects.requireNonNull(captured, "captured");
            DocumentToken capturedToken = captured.token();
            if (!captured.issuedBy(issuer)) {
                throw Messages.foreignCapture(documentId, capturedToken.documentId(),
                        capturedToken.branchId(), capturedToken.revision());
            }
            if (!documentId.equals(capturedToken.documentId()) || !branchId.equals(capturedToken.branchId())) {
                throw Messages.wrongBranch(documentId, capturedToken.documentId(), capturedToken.branchId(),
                        capturedToken.revision(), branchId, revision);
            }
            long capturedRevision = capturedToken.revision();
            if (capturedRevision > revision) {
                throw Messages.futureRevision(documentId, capturedRevision, revision);
            }
            if (capturedRevision < confirmedRevision) {
                throw Messages.olderConfirmation(documentId, capturedRevision, confirmedRevision);
            }
            savedEncoding = captured.encoding();
            dirty = !Arrays.equals(contentEncoding, savedEncoding);
            confirmedRevision = capturedRevision;
        });
    }

    /**
     * Stores raw text for {@code address} without touching the domain value.
     *
     * <p>
     * Pending input is not domain state: it does not change the content, the revision, the
     * history or the dirty flag. At most 1024 addresses are kept; an address is at most 1024
     * characters and raw text is at most 65536 characters.
     * </p>
     */
    public void setPendingInput(String address, String rawText) {
        guarded(() -> {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(rawText, "rawText");
            if (address.length() > MAX_PENDING_ADDRESS_LENGTH) {
                throw Messages.pendingAddressLimit(documentId, address.length(),
                        MAX_PENDING_ADDRESS_LENGTH);
            }
            if (rawText.length() > MAX_PENDING_RAW_LENGTH) {
                throw Messages.pendingTextLimit(documentId, rawText.length(), MAX_PENDING_RAW_LENGTH);
            }
            if (!pendingInputs.containsKey(address) && pendingInputs.size() >= MAX_PENDING_INPUTS) {
                throw Messages.pendingCountLimit(documentId, pendingInputs.size(), MAX_PENDING_INPUTS);
            }
            pendingInputs.put(address, rawText);
        });
    }

    /** Removes the pending raw text for {@code address}; removing an unknown address is a no-op. */
    public void clearPendingInput(String address) {
        guarded(() -> {
            Objects.requireNonNull(address, "address");
            pendingInputs.remove(address);
        });
    }

    /**
     * Replaces the baseline with {@code value} on a new branch.
     *
     * <p>
     * The new branch starts at revision zero with no history and treats {@code value} as the
     * confirmed save, so every token of the old branch is invalid afterwards. Pending input is
     * kept but is not re-addressed: this method cannot decide whether an old input still belongs
     * to a field of the new baseline, and it does not try. The caller must clear or re-map
     * pending input itself.
     * </p>
     */
    public void replaceBaseline(T value) {
        guarded(() -> {
            T snapshot = requireValidSnapshot(value);
            byte[] encoding = requireEncoding(snapshot);
            branchId = UUID.randomUUID();
            revision = 0L;
            content = snapshot;
            contentEncoding = encoding;
            savedEncoding = encoding;
            dirty = false;
            past.clear();
            future.clear();
            retainedEncodings.clear();
            retainedEncodings.put(0L, encoding);
            confirmedRevision = 0L;
        });
    }

    /**
     * Runs {@code mutation} as the outermost state change of this session.
     *
     * <p>
     * The guard is held for the whole mutation - including every {@code model.snapshot},
     * {@code model.validate} and {@code model.encode} call and the whole edit command - and is
     * released in a {@code finally} block, so a failed mutation never leaves the session locked.
     * Any state-changing method of the same session called from that window is rejected instead of
     * being applied on top of an in-flight mutation. Other sessions are unaffected.
     * </p>
     */
    private void guarded(Runnable mutation) {
        if (mutating) {
            throw Messages.reentrantMutation(documentId);
        }
        mutating = true;
        try {
            mutation.run();
        } finally {
            mutating = false;
        }
    }

    private DocumentToken token() {
        return new DocumentToken(documentId, branchId, revision);
    }

    private void requireCurrentToken(DocumentToken expected, String operation) {
        Objects.requireNonNull(expected, "expected");
        if (!documentId.equals(expected.documentId()) || !branchId.equals(expected.branchId()) || expected.revision() != revision) {
            throw Messages.staleToken(documentId, operation, expected.documentId(),
                    expected.branchId(), expected.revision(), branchId, revision);
        }
    }

    private void publish(T snapshot, byte[] encoding) {
        revision++;
        content = snapshot;
        contentEncoding = encoding;
        dirty = !Arrays.equals(contentEncoding, savedEncoding);
        retainedEncodings.put(revision, encoding);
        long oldest = revision - maxHistoryEntries;
        retainedEncodings.keySet().removeIf(retained -> retained < oldest);
    }

    private void trim(List<HistoryEntry<T>> entries) {
        while (entries.size() > maxHistoryEntries) {
            entries.remove(0);
        }
    }

    private T requireValidSnapshot(T value) {
        Objects.requireNonNull(value, "value");
        T snapshot = model.snapshot(value);
        Objects.requireNonNull(snapshot, "snapshot");
        List<Problem> problems = model.validate(snapshot);
        Objects.requireNonNull(problems, "problems");
        boolean rejected = false;
        for (Problem problem : problems) {
            Objects.requireNonNull(problem, "problem");
            rejected |= problem.severity() == Problem.Severity.ERROR;
        }
        if (rejected) {
            throw new DocumentRejection(problems);
        }
        return snapshot;
    }

    private byte[] requireEncoding(T snapshot) {
        byte[] encoding = model.encode(snapshot);
        Objects.requireNonNull(encoding, "encoding");
        return Arrays.copyOf(encoding, encoding.length);
    }

    private record HistoryEntry<T>(T before, byte[] beforeEncoding, T after, byte[] afterEncoding,
                                   TextContent label) {}
}
