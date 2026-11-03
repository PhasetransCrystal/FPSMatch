package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One save coordinator's request line: it turns user intents into frozen, publishable attempts,
 * owns the FIFO order of those intents and tracks exactly one in-flight attempt. It performs no
 * I/O, starts no thread and never publishes; publication belongs to {@link LocalCommitRunner} and
 * the caller decides when to run it.
 *
 * <p>
 * Every instance belongs to the thread that constructed it and every public call verifies that
 * thread. Calls are synchronous, so the queue needs no lock, and a validator that calls back into
 * the queue it is being dispatched from is refused instead of corrupting the request line.
 *
 * <p>
 * A queued delta is applied to the latest confirmed manifest when it reaches the head of the
 * line, not when it was enqueued, so a request enqueued early can never roll back content that was
 * confirmed later. A rejected request leaves the line and changes nothing that was already
 * confirmed: nothing is published, no attempt exists and no other draft is saved on its behalf.
 *
 * <p>
 * Results are accepted only from the active request and only after identity, parent and
 * {@code CURRENT} agree. A conflict freezes the engine in {@link SaveQueueView.State#RECOVERY};
 * that state is terminal for this queue and later results are refused, including a late success
 * receipt for the same request.
 *
 * <p>
 * Request identities are only checked against the current confirmed commit, the queued line and
 * the active attempt. The queue keeps no table of finished requests, so a long editing session does
 * not accumulate per-request state.
 *
 * <p>
 * This class is not thread-safe by design; sharing one instance between threads is a programming
 * error and is reported as {@link IllegalStateException}.
 */
public final class ProjectSaveQueue {

    private final UUID sessionId;
    private final ProjectValidator validator;
    private final int capacity;
    private final Thread owner;

    private final ArrayDeque<Request> pending = new ArrayDeque<>();

    private SaveQueueView.State state = SaveQueueView.State.READY;
    private SaveAttempt active;
    private SavedProject confirmed;
    private int pendingEntries;
    private boolean insideCallback;

    private record Request(UUID requestId, ProjectDelta delta, int entries) {}

    /**
     * Creates a queue for one editing session.
     *
     * @param baseline  the latest locally confirmed manifest; this slice starts from an existing
     *                  project, so the baseline commit id is always present
     * @param validator domain validation applied to every candidate
     * @param capacity  how many requests may wait in line, excluding the active request
     */
    public ProjectSaveQueue(SavedProject baseline, ProjectValidator validator, int capacity) {
        this.confirmed = Objects.requireNonNull(baseline, "baseline");
        this.validator = Objects.requireNonNull(validator, "validator");
        if (capacity < Budgets.MIN_CAPACITY || capacity > Budgets.MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between " + Budgets.MIN_CAPACITY + " and " + Budgets.MAX_CAPACITY);
        }
        this.capacity = capacity;
        this.sessionId = UUID.randomUUID();
        this.owner = Thread.currentThread();
    }

    /**
     * Appends one user intent and returns its request id, which the user interface can use to relate
     * later results to the action that produced them. The request is accepted while nothing or
     * exactly one request is publishing; it is refused once the queue can no longer guarantee order.
     * Capacity and pending budgets are checked before anything is added, so a refusal changes
     * nothing.
     */
    public UUID enqueue(ProjectDelta delta) {
        requireOwner();
        Objects.requireNonNull(delta, "delta");
        requireOutsideCallback();
        requireAccepting();
        if (pending.size() >= capacity) {
            throw new IllegalStateException("save queue already holds " + capacity + " queued requests");
        }
        int entries = entryCount(delta);
        if (pendingEntries > Budgets.MAX_PENDING_ENTRIES - entries) {
            throw new IllegalStateException("queued deltas would exceed the " + Budgets.MAX_PENDING_ENTRIES + " entry budget");
        }
        UUID requestId = newRequestId();
        pending.addLast(new Request(requestId, delta, entries));
        pendingEntries += entries;
        return requestId;
    }

    /**
     * Prepares the head request for publication when the queue is idle: assembles the candidate from
     * the latest confirmed manifest, validates it and freezes an attempt. Returns an empty optional
     * when there is nothing to dispatch; returns a dispatch without an attempt when the head was
     * rejected before any I/O.
     */
    public Optional<SaveDispatch> dispatch() {
        requireOwner();
        requireOutsideCallback();
        if (state != SaveQueueView.State.READY || pending.isEmpty()) {
            return Optional.empty();
        }
        Request head = pending.peekFirst();
        List<Problem> problems = new ArrayList<>();
        SaveAttempt prepared = null;
        insideCallback = true;
        try {
            ProjectState candidate = candidate(head.requestId(), head.delta());
            problems.addAll(validate(head.requestId(), candidate));
            if (!hasError(problems)) {
                prepared = new SaveAttempt(sessionId, head.requestId(), confirmed.commitId(), candidate);
            }
        } catch (CandidateRejectedException rejected) {
            problems.add(rejected.problem());
        } catch (RuntimeException failure) {
            problems.add(Messages.validatorFailed(head.requestId(), Messages.technical(failure)));
        } finally {
            insideCallback = false;
        }
        pending.removeFirst();
        pendingEntries -= head.entries();
        if (prepared == null) {
            return Optional.of(new SaveDispatch(head.requestId(), Optional.empty(), problems));
        }
        active = prepared;
        state = SaveQueueView.State.PUBLISHING;
        return Optional.of(new SaveDispatch(head.requestId(), Optional.of(prepared), problems));
    }

    /**
     * Reports the observed outcome of the active request.
     *
     * <p>
     * Returns {@code false}, without changing anything, for a result that does not belong to the
     * active request, for a success whose commit, parent, candidate or {@code CURRENT} does not
     * match, for a rejection of a request whose result is still unknown, and for any result once the
     * queue is in recovery or closed.
     *
     * <p>
     * Returns {@code true} when the queue accepted the report: a verified success replaces the
     * confirmed manifest and returns to ready; a rejection releases the active request; an unknown
     * result keeps the request in flight and stops further work until the same request is resolved;
     * a conflict freezes the engine in recovery while keeping the request and its receipts
     * available for inspection.
     */
    public boolean complete(SaveAttempt attempt, SaveResult result) {
        requireOwner();
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");
        requireOutsideCallback();
        if (active == null || !active.equals(attempt)) {
            return false;
        }
        if (state == SaveQueueView.State.RECOVERY || state == SaveQueueView.State.CLOSED) {
            return false;
        }
        switch (result.outcome()) {
            case CONFIRMED -> {
                if (!confirms(attempt, result)) {
                    return false;
                }
                confirmed = result.saved();
                active = null;
                state = SaveQueueView.State.READY;
                return true;
            }
            case REJECTED -> {
                if (state != SaveQueueView.State.PUBLISHING) {
                    return false;
                }
                active = null;
                state = SaveQueueView.State.READY;
                return true;
            }
            case CONFLICT -> {
                state = SaveQueueView.State.RECOVERY;
                return true;
            }
            case UNKNOWN -> {
                state = SaveQueueView.State.UNKNOWN;
                return true;
            }
        }
        return false;
    }

    /** An immutable reading of the queue; safe to call from a validator callback. */
    public SaveQueueView view() {
        requireOwner();
        return new SaveQueueView(state, pending.size(), Optional.ofNullable(active), confirmed);
    }

    /**
     * Cancels every queued request and returns their request ids in FIFO order. Refused while a
     * request is active, because only the caller can decide what an unresolved publication means.
     * Closing twice is a no-op that returns an empty list.
     */
    public List<UUID> close() {
        requireOwner();
        requireOutsideCallback();
        if (active != null) {
            throw new IllegalStateException("request " + active.requestId() + " is still active; resolve it before closing the save queue");
        }
        if (state == SaveQueueView.State.CLOSED) {
            return List.of();
        }
        List<UUID> cancelled = new ArrayList<>(pending.size());
        for (Request request : pending) {
            cancelled.add(request.requestId());
        }
        pending.clear();
        pendingEntries = 0;
        state = SaveQueueView.State.CLOSED;
        return List.copyOf(cancelled);
    }

    private ProjectState candidate(UUID requestId, ProjectDelta delta) {
        ProjectState base = confirmed.state();
        Map<String, String> modules = new TreeMap<>(base.modules());
        delta.moduleAdditions().forEach((module, version) -> {
            String saved = modules.get(module);
            if (saved != null && !saved.equals(version)) {
                throw new CandidateRejectedException(
                        Messages.moduleVersionConflict(requestId, module, version, saved));
            }
            modules.put(module, version);
        });
        Map<UUID, ProjectState.Entry> documents = new TreeMap<>(base.documents());
        Map<UUID, ProjectState.Entry> assets = new TreeMap<>(base.assets());
        delta.removedDocuments().forEach(documents::remove);
        delta.removedAssets().forEach(assets::remove);
        documents.putAll(delta.documents());
        assets.putAll(delta.assets());
        for (UUID id : documents.keySet()) {
            if (assets.containsKey(id)) {
                throw new CandidateRejectedException(Messages.entryIdentityConflict(requestId, id));
            }
        }
        int entries = documents.size() + assets.size();
        if (entries > Budgets.MAX_ENTRIES) {
            throw new CandidateRejectedException(
                    Messages.entryBudgetExceeded(requestId, entries, Budgets.MAX_ENTRIES));
        }
        long edges = inspect(requestId, documents.values(), documents.keySet(), assets.keySet(),
                modules.keySet());
        edges += inspect(requestId, assets.values(), documents.keySet(), assets.keySet(), modules.keySet());
        if (edges > Budgets.MAX_DEPENDENCIES) {
            throw new CandidateRejectedException(
                    Messages.dependencyBudgetExceeded(requestId, edges, Budgets.MAX_DEPENDENCIES));
        }
        try {
            return new ProjectState(base.projectId(), delta.name().orElse(base.name()), modules,
                    documents, assets, base.origin());
        } catch (IllegalArgumentException invalid) {
            throw new CandidateRejectedException(
                    Messages.invalidCandidate(requestId, Messages.technical(invalid)));
        }
    }

    private static long inspect(UUID requestId, Iterable<ProjectState.Entry> entries,
                                Set<UUID> documents, Set<UUID> assets, Set<String> modules) {
        long edges = 0;
        for (ProjectState.Entry entry : entries) {
            if (!modules.contains(entry.ownerModule())) {
                throw new CandidateRejectedException(
                        Messages.missingOwner(requestId, entry.id(), entry.ownerModule()));
            }
            edges += dependencyEdge(requestId, entry, entry.documentDependencies(), documents,
                    BlobRef.Kind.DOCUMENT);
            edges += dependencyEdge(requestId, entry, entry.assetDependencies(), assets, BlobRef.Kind.ASSET);
        }
        return edges;
    }

    private static long dependencyEdge(UUID requestId, ProjectState.Entry entry, Set<UUID> dependencies,
                                       Set<UUID> present, BlobRef.Kind kind) {
        for (UUID dependency : dependencies) {
            if (!present.contains(dependency)) {
                throw new CandidateRejectedException(
                        Messages.missingDependency(requestId, entry.id(), kind, dependency));
            }
        }
        return dependencies.size();
    }

    private List<Problem> validate(UUID requestId, ProjectState candidate) {
        List<Problem> reported = validator.validate(candidate);
        if (reported == null) {
            throw new IllegalStateException("validator returned no problem list");
        }
        List<Problem> problems = new ArrayList<>(reported.size());
        for (Problem problem : reported) {
            problems.add(Objects.requireNonNull(problem, "problem"));
        }
        return problems;
    }

    /** A success counts only when every verifiable field agrees with the frozen attempt. */
    private static boolean confirms(SaveAttempt attempt, SaveResult result) {
        SavedProject saved = result.saved();
        return saved != null && attempt.requestId().equals(saved.commitId()) && attempt.expectedCommitId().equals(saved.parentCommitId()) && attempt.candidate().equals(saved.state()) && attempt.requestId().equals(result.currentCommitId());
    }

    private static boolean hasError(List<Problem> problems) {
        return problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR);
    }

    private static int entryCount(ProjectDelta delta) {
        return delta.documents().size() + delta.assets().size() + delta.removedDocuments().size() + delta.removedAssets().size();
    }

    /**
     * Draws an id that is distinct from the identities this queue currently depends on: the confirmed
     * commit, the queued line and the active attempt. Finished requests are deliberately not
     * remembered, so the queue holds no per-request history that could grow without bound.
     */
    private UUID newRequestId() {
        UUID requestId;
        do {
            requestId = UUID.randomUUID();
        } while (isInUse(requestId));
        return requestId;
    }

    private boolean isInUse(UUID requestId) {
        if (requestId.equals(confirmed.commitId())) {
            return true;
        }
        if (active != null && requestId.equals(active.requestId())) {
            return true;
        }
        for (Request request : pending) {
            if (requestId.equals(request.requestId())) {
                return true;
            }
        }
        return false;
    }

    private void requireAccepting() {
        switch (state) {
            case READY, PUBLISHING -> {}
            case UNKNOWN -> throw new IllegalStateException(
                    "the active request's result is unknown; resolve it before queueing more work");
            case RECOVERY -> throw new IllegalStateException(
                    "the save queue is in recovery after a conflict and accepts no further request");
            case CLOSED -> throw new IllegalStateException("the save queue is closed");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("the save queue belongs to its owner thread");
        }
    }

    private void requireOutsideCallback() {
        if (insideCallback) {
            throw new IllegalStateException("the save queue is running a validator callback; " + "re-entrant use would reorder save intent and is refused");
        }
    }

    /** Internal, non-recoverable rejection of one candidate; carries the single reported problem. */
    private static final class CandidateRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Problem problem;

        private CandidateRejectedException(Problem problem) {
            super(problem.code() + " " + problem.target());
            this.problem = problem;
        }

        private Problem problem() {
            return problem;
        }
    }
}
