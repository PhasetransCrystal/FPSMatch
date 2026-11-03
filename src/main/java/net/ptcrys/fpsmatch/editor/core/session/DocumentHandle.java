package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentCapture;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentModel;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentSession;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentView;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * A typed document draft owned by its project session. This handle exposes editing and history,
 * while baseline replacement and durable-save confirmation remain private to the project runtime.
 * Calls, including reads, belong to the thread that opened the project.
 *
 * <p>
 * {@link #hasUnpublishedChanges()} includes a new document or a decoded format that has not yet
 * been explicitly published. A new {@link DocumentSession} can report clean in {@link #view()}
 * because it has no edits relative to its initial in-memory value; that does not mean it is on disk.
 */
public final class DocumentHandle<T> {

    private final SessionOwner owner;
    private final UUID id;
    private final ActivatedDocumentType<T> type;
    private final String displayName;
    private final DocumentSession<State<T>> draft;
    private final ProjectState.Entry original;
    private final byte[] originalBytes;
    /** Initial content until the first confirmation, then the latest confirmed capture's content. */
    private State<T> discardBaseline;
    private boolean confirmedCapture;
    private DocumentDependencies publishedDependencies;
    private long latestQueuedRevision = -1;

    DocumentHandle(SessionOwner owner, UUID id, ActivatedDocumentType<T> type, String displayName,
                   T initial, int maxHistoryEntries, ProjectState.Entry original, byte[] originalBytes) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.original = original;
        // The package constructor receives the fresh private copy read by ProjectSession.
        this.originalBytes = originalBytes;
        this.draft = new DocumentSession<>(id, new State<>(initial), new DocumentModel<>() {

            @Override
            public State<T> snapshot(State<T> value) {
                return new State<>(type.snapshot(value.value));
            }

            @Override
            public List<Problem> validate(State<T> value) {
                return type.validate(value.value);
            }

            @Override
            public byte[] encode(State<T> value) {
                // The implementation may reuse its buffer in the next callback.
                byte[] encoded = type.encode(value.value).clone();
                DocumentDependencies dependencies = type.dependencies(value.value);
                // This state is private and not yet published. No callback can obtain it.
                // Both caches are complete before DocumentSession may publish or retain it.
                value.dependencies = dependencies;
                value.encodedLength = encoded.length;
                value.matchesSource = matchesSource(encoded, dependencies);
                return encoded;
            }
        }, maxHistoryEntries);
        discardBaseline = draft.view().content();
        publishedDependencies = original == null ? null : initialDependencies();
    }

    public UUID id() {
        owner.requireReadable();
        return id;
    }

    public EditorId typeId() {
        owner.requireReadable();
        return type.id();
    }

    public Class<T> valueType() {
        owner.requireReadable();
        return type.valueType();
    }

    public String displayName() {
        owner.requireReadable();
        return displayName;
    }

    public DocumentView<T> view() {
        owner.requireReadable();
        DocumentView<State<T>> view = draft.view();
        return new DocumentView<>(view.token(), view.content().value, view.dirty(), view.canUndo(),
                view.canRedo(), view.pendingInputs());
    }

    /** Immutable dependencies of the current applied draft, cached by its successful encoding. */
    public DocumentDependencies currentDependencies() {
        owner.requireReadable();
        return draft.view().content().dependencies;
    }

    public boolean hasUnpublishedChanges() {
        owner.requireReadable();
        DocumentView<State<T>> view = draft.view();
        return confirmedCapture ? view.dirty() : !view.content().matchesSource;
    }

    public Optional<TextContent> undoLabel() {
        owner.requireReadable();
        return draft.undoLabel();
    }

    public Optional<TextContent> redoLabel() {
        owner.requireReadable();
        return draft.redoLabel();
    }

    public void edit(DocumentToken expected, TextContent label, UnaryOperator<T> command) {
        owner.mutate(() -> {
            Objects.requireNonNull(command, "command");
            draft.edit(expected, label, isolated -> new State<>(command.apply(isolated.value)));
            return null;
        });
    }

    public void undo(DocumentToken expected) {
        owner.mutate(() -> {
            draft.undo(expected);
            return null;
        });
    }

    public void redo(DocumentToken expected) {
        owner.mutate(() -> {
            draft.redo(expected);
            return null;
        });
    }

    public void setPendingInput(String address, String rawText) {
        owner.mutate(() -> {
            draft.setPendingInput(address, rawText);
            return null;
        });
    }

    public void clearPendingInput(String address) {
        owner.mutate(() -> {
            draft.clearPendingInput(address);
            return null;
        });
    }

    DocumentDependencies initialDependencies() {
        return draft.view().content().dependencies;
    }

    DocumentDependencies publishedDependencies() {
        return publishedDependencies;
    }

    int encodedLength() {
        return draft.view().content().encodedLength;
    }

    /** Called only inside the project mutation boundary after its publication guards pass. */
    void discardChanges() {
        Set<String> pending = draft.view().pendingInputs().keySet();
        // Snapshot, validation and encoding all finish before replaceBaseline publishes anything.
        // Retain raw input if any callback rejects; no callbacks remain after a successful replace.
        draft.replaceBaseline(discardBaseline);
        pending.forEach(draft::clearPendingInput);
        latestQueuedRevision = -1;
    }

    DocumentSaveCapture capture() {
        DocumentCapture<State<T>> captured = draft.captureForSave();
        return new DocumentSaveCapture(captured.token(), captured.content().dependencies, type.id(),
                type.owner(), type.codecId(), type.schemaVersion(), displayName) {

            // Keep one immutable encoding in DocumentCapture. An I/O staging copy is transient;
            // retaining another full array here would double every queued capture's byte budget.
            @Override
            byte[] encoding() {
                return captured.encoding();
            }

            @Override
            boolean canEnqueue() {
                return token.branchId().equals(draft.view().token().branchId()) && token.revision() >= latestQueuedRevision;
            }

            @Override
            void queued() {
                latestQueuedRevision = token.revision();
            }

            @Override
            void confirm() {
                draft.confirmSaved(captured);
                discardBaseline = captured.content();
                publishedDependencies = captured.content().dependencies;
                confirmedCapture = true;
            }
        };
    }

    private boolean matchesSource(byte[] encoded, DocumentDependencies dependencies) {
        if (original == null || original.schemaVersion() != type.schemaVersion() || !original.codecId().equals(type.codecId().toString()) || !Arrays.equals(originalBytes, encoded) || !original.documentDependencies().equals(dependencies.documents())) {
            return false;
        }
        Set<UUID> assets = new TreeSet<>();
        dependencies.resources().forEach(ref -> {
            assets.add(ref.resourceId());
            assets.add(ref.versionId());
        });
        return original.assetDependencies().equals(assets);
    }

    /** Class identity is checked before the single erased handle narrowing used by typed lookup. */
    <R> DocumentHandle<R> narrow(Class<R> expected) {
        if (type.valueType() != Objects.requireNonNull(expected, "valueType")) {
            throw new IllegalArgumentException("document value type does not match requested class");
        }
        @SuppressWarnings("unchecked")
        DocumentHandle<R> narrowed = (DocumentHandle<R>) this;
        return narrowed;
    }

    /** Initialized during encoding, then immutable for the whole published state and its history. */
    private static final class State<T> {

        private final T value;
        private DocumentDependencies dependencies;
        private boolean matchesSource;
        private int encodedLength;

        State(T value) {
            this.value = value;
        }
    }
}
