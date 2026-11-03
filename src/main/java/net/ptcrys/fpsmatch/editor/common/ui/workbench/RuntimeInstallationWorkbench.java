package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.core.installation.*;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Owner-thread product flow. Preparation, confirmation and persistent outcomes have separate lifetimes. */
public final class RuntimeInstallationWorkbench {

    public enum State {
        IDLE,
        PREPARING,
        READY,
        CANCELLING,
        EXECUTING,
        INSTALLED,
        CANCELLED,
        FAILED,
        UNKNOWN,
        RECOVERY_REQUIRED
    }

    public enum ReplacementBlocker {
        NONE,
        TASK_BUSY,
        STALE_SELECTION,
        OFFLINE,
        RECORD_NOT_ACTIVE,
        RUNTIME_UNAVAILABLE,
        RUNNING,
        PARTICIPANTS,
        RESET_INCOMPLETE,
        HOST_BUSY
    }

    public record Selection(Target target, String label, UUID worldId, Map<EditorId, EditorId> dimensions) {

        public Selection {
            Objects.requireNonNull(target);
            Objects.requireNonNull(label);
            Objects.requireNonNull(worldId);
            dimensions = Map.copyOf(dimensions);
        }
    }

    public record RecordView(LocalInstallationIndex.Record record, boolean online, ReplacementBlocker replacementBlocker) {

        public RecordView {
            Objects.requireNonNull(record);
            Objects.requireNonNull(replacementBlocker);
        }

        public RecordView(LocalInstallationIndex.Record record, boolean online) {
            this(record, online, online ? ReplacementBlocker.RUNTIME_UNAVAILABLE : ReplacementBlocker.OFFLINE);
        }
    }

    public record Request(ProjectWorkbench.SavedRuntimeSource source, RuntimeInstallationProfile profile,
                          String name, Selection target, LocalInstallationIndex.Record recovery, boolean previous,
                          LocalInstallationIndex.Record replacement) {

        public Request {
            if (replacement != null && (source == null || profile == null || recovery != null || previous))
                throw new IllegalArgumentException("Replacement requires a saved source and no recovery input");
            if (replacement != null && !replacement.plan().key().equals(new InstallationPlan.Key(profile.gameType(), name)))
                throw new IllegalArgumentException("Replacement must retain the selected runtime key");
        }

        public Request(ProjectWorkbench.SavedRuntimeSource source, RuntimeInstallationProfile profile, String name,
                       Selection target, LocalInstallationIndex.Record recovery, boolean previous) {
            this(source, profile, name, target, recovery, previous, null);
        }
    }

    public record Result(State state, TextContent message, Throwable diagnostic) {

        public Result {
            Objects.requireNonNull(state);
            Objects.requireNonNull(message);
            if (state != State.INSTALLED && state != State.CANCELLED && state != State.FAILED && state != State.UNKNOWN && state != State.RECOVERY_REQUIRED) throw new IllegalArgumentException("terminal outcome required");
        }
    }

    public record Preparation(Prepared prepared, TextContent failure, Throwable diagnostic) {

        public Preparation {
            if ((prepared == null) == (failure == null)) throw new IllegalArgumentException("one preparation result required");
        }
    }

    public interface Prepared {

        InstallationPlan plan();

        default InstallationPlan replacedPlan() {
            return null;
        }

        /** Releases only preparation resources. Never retires an installed map. */
        void release(Consumer<Throwable> completed);
    }

    public interface Target {

        void prepare(Request request, BooleanSupplier cancelled, Consumer<Preparation> completed);

        void submit(Prepared prepared, UUID requestId, BooleanSupplier cancelled, Consumer<Result> completed);

        void records(Consumer<List<RecordView>> completed, Consumer<Throwable> failed);

        void lookup(UUID requestId, Consumer<TextContent> completed, Consumer<Throwable> failed);

        /** Gives stopped hosts a chance to reject work which provably never started. */
        default void poll() {}

        default RuntimeTestWorkbench.Target tests() {
            return null;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final ProjectWorkbench project;
    private final Supplier<Optional<Selection>> selection;
    private final ConcurrentLinkedQueue<Runnable> replies = new ConcurrentLinkedQueue<>();
    private State state = State.IDLE;
    private TextContent status = text("install_idle");
    private Throwable diagnostic;
    private long version;
    private Flight flight;
    private Receipt receipt;
    private TaskInput taskInput;
    private InstallationPlan preparedPlanSnapshot;
    private WeakReference<DocumentHandle<?>> sourceDocument;
    private Path sourceDirectory;
    private List<RecordView> records = List.of();
    private TextContent recordsStatus = text("install_records_hint");
    private Throwable recordsDiagnostic;
    private Selection recordsTarget;
    private Selection pendingRecordsTarget;
    private long recordsGeneration;
    private boolean readingRecords, querying;

    public record Receipt(String worldLabel, UUID worldId, InstallationPlan plan, UUID requestId, Result result,
                          InstallationPlan replacedPlan) {

        public Receipt(String worldLabel, UUID worldId, InstallationPlan plan, UUID requestId, Result result) {
            this(worldLabel, worldId, plan, requestId, result, null);
        }
    }

    /** Display identity captured before preparation; contains no live target or storage lease. */
    public record TaskInput(String worldLabel, UUID worldId, UUID commitId, UUID documentId, String documentName,
                            InstallationPlan.Key key, InstallationPlan replacedPlan, UUID projectId) {

        public TaskInput(String worldLabel, UUID worldId, UUID commitId, UUID documentId, String documentName, InstallationPlan.Key key) {
            this(worldLabel, worldId, commitId, documentId, documentName, key, null, null);
        }

        public TaskInput(String worldLabel, UUID worldId, UUID commitId, UUID documentId, String documentName, InstallationPlan.Key key, InstallationPlan replacedPlan) {
            this(worldLabel, worldId, commitId, documentId, documentName, key, replacedPlan, null);
        }
    }

    public record RecordsInput(String worldLabel, UUID worldId) {}

    private static final class Flight {

        final Request request;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final UUID requestId = UUID.randomUUID();
        Prepared prepared;
        boolean releasing, submitted;

        Flight(Request request) {
            this.request = request;
        }
    }

    public RuntimeInstallationWorkbench(ProjectWorkbench project, Supplier<Optional<Selection>> selection) {
        this.project = Objects.requireNonNull(project);
        this.selection = Objects.requireNonNull(selection);
    }

    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("installation owner required");
    }

    public long version() {
        owner();
        return version;
    }

    public State state() {
        owner();
        return state;
    }

    public TextContent status() {
        owner();
        return status;
    }

    public Optional<Throwable> diagnostic() {
        owner();
        return Optional.ofNullable(diagnostic);
    }

    public Optional<Receipt> receipt() {
        owner();
        return Optional.ofNullable(receipt);
    }

    /** Navigation hint only: UUID equality does not identify a reopened or copied project session. */
    public boolean receiptBelongsToCurrentProject() {
        owner();
        DocumentHandle<?> captured = sourceDocument == null ? null : sourceDocument.get();
        return receipt != null && captured != null && captured == project.documents().get(receipt.plan().documentId()) && project.directory().filter(path -> path.equals(sourceDirectory)).isPresent();
    }

    public Optional<TaskInput> taskInput() {
        owner();
        return Optional.ofNullable(taskInput);
    }

    public Optional<RecordsInput> recordsInput() {
        owner();
        return recordsTarget == null ? Optional.empty() :
                Optional.of(new RecordsInput(recordsTarget.label(), recordsTarget.worldId()));
    }

    /** Last validated plan for display only; it owns no preparation resources or submission authority. */
    public Optional<InstallationPlan> preparedPlan() {
        owner();
        return Optional.ofNullable(preparedPlanSnapshot);
    }

    public Optional<Selection> preparedTarget() {
        owner();
        return flight == null ? Optional.empty() : Optional.of(flight.request.target());
    }

    public List<RecordView> records() {
        owner();
        return records;
    }

    public TextContent recordsStatus() {
        owner();
        return recordsStatus;
    }

    public Optional<Throwable> recordsDiagnostic() {
        owner();
        return Optional.ofNullable(recordsDiagnostic);
    }

    public boolean readingRecords() {
        owner();
        return readingRecords;
    }

    public boolean busy() {
        owner();
        return state == State.PREPARING || state == State.CANCELLING || state == State.EXECUTING;
    }

    public boolean canStart() {
        owner();
        return flight == null && !querying;
    }

    public boolean canConfirm() {
        owner();
        return state == State.READY && flight != null && current(flight);
    }

    public boolean canQuery() {
        owner();
        return flight != null && !querying && (state == State.UNKNOWN || state == State.RECOVERY_REQUIRED);
    }

    public ReplacementBlocker replacementBlocker(RecordView selected) {
        owner();
        if (!canStart()) return ReplacementBlocker.TASK_BUSY;
        if (selected == null || recordsTarget == null || !sameSelection(recordsTarget) || !records.contains(selected))
            return ReplacementBlocker.STALE_SELECTION;
        if (!selected.online()) return ReplacementBlocker.OFFLINE;
        if (selected.record().state() != LocalInstallationIndex.State.ACTIVE) return ReplacementBlocker.RECORD_NOT_ACTIVE;
        return selected.replacementBlocker();
    }

    public boolean canReplace(RecordView selected) {
        return replacementBlocker(selected) == ReplacementBlocker.NONE;
    }

    public void prepare(RuntimeInstallationProfile profile, String name) {
        owner();
        requireStart();
        Objects.requireNonNull(profile);
        new InstallationPlan.Key(profile.gameType(), name);
        if (!project.runtimeProfiles().containsValue(profile)) throw new IllegalArgumentException("unregistered installation profile");
        Selection target = selection.get().orElseThrow(() -> new IllegalStateException("verified world required"));
        var source = project.borrowSavedRuntimeSource();
        start(new Request(source, profile, name, target, null, false));
    }

    public void prepareReplacement(RuntimeInstallationProfile profile, RecordView selected) {
        owner();
        requireStart();
        Objects.requireNonNull(profile);
        ReplacementBlocker blocker = replacementBlocker(selected);
        if (blocker != ReplacementBlocker.NONE) throw new IllegalStateException("Replacement unavailable: " + blocker);
        if (!project.runtimeProfiles().containsValue(profile)) throw new IllegalArgumentException("unregistered installation profile");
        if (!profile.gameType().equals(selected.record().plan().key().type()))
            throw new IllegalArgumentException("Replacement profile differs from selected runtime type");
        Selection target = selection.get().orElseThrow(() -> new IllegalStateException("verified world required"));
        var source = project.borrowSavedRuntimeSource();
        start(new Request(source, profile, selected.record().plan().key().name(), target, null, false, selected.record()));
    }

    public void recover(RecordView selected, boolean previous) {
        owner();
        requireStart();
        if (recordsTarget == null || !sameSelection(recordsTarget) || !records.contains(selected) || selected.online()) throw new IllegalStateException("unoccupied exact record required");
        if (previous && selected.record().previous() == null) throw new IllegalArgumentException("no previous input");
        Selection target = selection.get().orElseThrow(() -> new IllegalStateException("verified world required"));
        start(new Request(null, null, selected.record().plan().key().name(), target, selected.record(), previous));
    }

    private void requireStart() {
        if (!canStart()) throw new IllegalStateException("installation task still owns its input");
    }

    private void start(Request request) {
        var source = request.source();
        var recovery = source == null ? (request.previous() ? Objects.requireNonNull(request.recovery().previous()).plan() : request.recovery().plan()) : null;
        taskInput = new TaskInput(request.target().label(), request.target().worldId(),
                source == null ? recovery.commitId() : source.saved().commitId(),
                source == null ? recovery.documentId() : source.documentId(),
                source == null ? recovery.documentId().toString() : source.documentName(),
                source == null ? recovery.key() : new InstallationPlan.Key(request.profile().gameType(), request.name()),
                request.replacement() == null ? null : request.replacement().plan(),
                source == null ? recovery.projectId() : source.saved().state().projectId());
        receipt = null;
        preparedPlanSnapshot = null;
        sourceDocument = source == null ? null : new WeakReference<>(project.documents().get(source.documentId()));
        sourceDirectory = source == null ? null : project.directory().orElse(null);
        Flight next = new Flight(request);
        flight = next;
        set(State.PREPARING, text("install_preparing"), null);
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<Preparation> completed = result -> { if (delivered.compareAndSet(false, true)) replies.add(() -> acceptPreparation(next, result)); };
        try {
            request.target().target().prepare(request, next.cancelled::get, completed);
        } catch (RuntimeException failure) {
            completed.accept(new Preparation(null, text("install_failed"), failure));
        }
    }

    private void acceptPreparation(Flight next, Preparation result) {
        owner();
        if (flight != next) {
            if (result.prepared() != null) result.prepared().release(ignored -> {});
            return;
        }
        if (result.prepared() == null) {
            releaseSource(next);
            flight = null;
            set(next.cancelled.get() ? State.CANCELLED : State.FAILED,
                    next.cancelled.get() ? text("install_cancelled") : result.failure(), result.diagnostic());
            return;
        }
        next.prepared = result.prepared();
        if (next.cancelled.get() || !current(next)) {
            next.cancelled.set(true);
            release(next, null);
            return;
        }
        InstallationPlan validatedPlan = next.prepared.plan();
        if (!matchesInput(next.request, validatedPlan) || !Objects.equals(next.prepared.replacedPlan(), next.request.replacement() == null ? null : next.request.replacement().plan())) {
            release(next, new Result(State.FAILED, text("install_failed"), new IllegalStateException("Prepared installation does not match captured input")));
            return;
        }
        preparedPlanSnapshot = validatedPlan;
        set(State.READY, text("install_ready"), null);
    }

    private boolean matchesInput(Request request, InstallationPlan plan) {
        if (plan == null) return false;
        if (request.source() == null) return plan.equals(request.previous() ? request.recovery().previous().plan() : request.recovery().plan());
        var source = request.source();
        var profile = request.profile();
        return (request.replacement() == null || !plan.installationId().equals(request.replacement().plan().installationId())) && plan.projectId().equals(source.saved().state().projectId()) && plan.commitId().equals(source.saved().commitId()) && plan.documentId().equals(source.documentId()) && plan.adapterId().equals(profile.adapterId()) && plan.factoryId().equals(profile.factoryId()) && plan.key().equals(new InstallationPlan.Key(profile.gameType(), request.name())) && plan.dimensions().equals(request.target().dimensions());
    }

    public void confirm() {
        owner();
        if (!canConfirm()) throw new IllegalStateException("installation confirmation is stale");
        Flight next = flight;
        next.submitted = true;
        receipt = new Receipt(next.request.target().label(), next.request.target().worldId(), next.prepared.plan(), next.requestId, null,
                next.request.replacement() == null ? null : next.request.replacement().plan());
        set(State.EXECUTING, text("install_executing"), null);
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<Result> completed = result -> { if (delivered.compareAndSet(false, true)) replies.add(() -> acceptResult(next, result)); };
        try {
            next.request.target().target().submit(next.prepared, next.requestId, next.cancelled::get, completed);
        } catch (RuntimeException failure) {
            completed.accept(new Result(State.UNKNOWN, text("install_unknown"), failure));
        }
    }

    private void acceptResult(Flight next, Result result) {
        owner();
        if (flight != next || !next.submitted || next.releasing) return;
        receipt = new Receipt(next.request.target().label(), next.request.target().worldId(), next.prepared.plan(), next.requestId, result,
                next.request.replacement() == null ? null : next.request.replacement().plan());
        release(next, result);
    }

    private void release(Flight next, Result outcome) {
        if (next.releasing) return;
        next.releasing = true;
        if (outcome == null) set(State.CANCELLING, text("install_cancelling"), null);
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<Throwable> done = failure -> {
            if (!delivered.compareAndSet(false, true)) return;
            replies.add(() -> {
                if (flight != next) return;
                next.prepared = null;
                releaseSource(next);
                Result result = outcome == null ? new Result(failure == null ? State.CANCELLED : State.FAILED,
                        text(failure == null ? "install_cancelled" : "install_failed"), failure) : outcome;
                if (failure != null && result.diagnostic() != null && failure != result.diagnostic()) result.diagnostic().addSuppressed(failure);
                if (result.state() != State.UNKNOWN && result.state() != State.RECOVERY_REQUIRED) flight = null;
                set(result.state(), result.message(), result.diagnostic() == null ? failure : result.diagnostic());
            });
        };
        try {
            next.prepared.release(done);
        } catch (RuntimeException failure) {
            done.accept(failure);
        }
    }

    private void releaseSource(Flight next) {
        if (next.request.source() != null) project.releaseSavedRuntimeSource(next.request.source());
    }

    /** Closing a page cancels preparation only. Submitted work keeps its receipt and real outcome. */
    public void dismiss() {
        owner();
        if (state == State.PREPARING || state == State.READY) cancel();
    }

    public void cancel() {
        owner();
        if (flight == null || flight.releasing) return;
        flight.cancelled.set(true);
        if (state == State.READY) release(flight, null);
        else if (state == State.PREPARING) set(State.CANCELLING, text("install_cancelling"), null);
        else if (state == State.EXECUTING) {
            status = text("install_cancel_requested");
            version++;
        }
    }

    public void refreshRecords() {
        owner();
        if (readingRecords || busy()) return;
        Selection captured = selection.get().orElseThrow(() -> new IllegalStateException("verified world required"));
        long generation = ++recordsGeneration;
        readingRecords = true;
        pendingRecordsTarget = captured;
        records = List.of();
        recordsTarget = null;
        recordsStatus = text("install_records_hint");
        recordsDiagnostic = null;
        version++;
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<List<RecordView>> completed = list -> {
            if (!delivered.compareAndSet(false, true)) return;
            replies.add(() -> {
                if (generation != recordsGeneration) return;
                readingRecords = false;
                pendingRecordsTarget = null;
                if (sameSelection(captured)) {
                    records = List.copyOf(list);
                    recordsTarget = captured;
                    recordsStatus = text("install_records_hint");
                    recordsDiagnostic = null;
                }
                version++;
            });
        };
        Consumer<Throwable> failed = failure -> {
            if (!delivered.compareAndSet(false, true)) return;
            replies.add(() -> {
                if (generation != recordsGeneration) return;
                readingRecords = false;
                pendingRecordsTarget = null;
                if (sameSelection(captured)) {
                    recordsDiagnostic = failure;
                    recordsStatus = text("install_records_failed");
                }
                version++;
            });
        };
        try {
            captured.target().records(completed, failed);
        } catch (RuntimeException failure) {
            failed.accept(failure);
        }
    }

    public void query() {
        owner();
        if (!canQuery()) return;
        Flight captured = flight;
        querying = true;
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<TextContent> completed = message -> {
            if (delivered.compareAndSet(false, true)) replies.add(() -> {
                querying = false;
                status = message;
                version++;
            });
        };
        Consumer<Throwable> failed = failure -> {
            if (delivered.compareAndSet(false, true)) replies.add(() -> {
                querying = false;
                diagnostic = failure;
                status = text("install_query_failed");
                version++;
            });
        };
        try {
            captured.request.target().target().lookup(captured.requestId, completed, failed);
        } catch (RuntimeException failure) {
            failed.accept(failure);
        }
    }

    public void tick() {
        owner();
        Runnable reply;
        while ((reply = replies.poll()) != null) reply.run();
        if (pendingRecordsTarget != null) {
            if (!sameSelection(pendingRecordsTarget)) {
                recordsGeneration++;
                pendingRecordsTarget = null;
                readingRecords = false;
                version++;
            } else pendingRecordsTarget.target().poll();
        }
        if (flight == null) return;
        flight.request.target().target().poll();
        if (!current(flight)) {
            if (state == State.PREPARING || state == State.READY || state == State.EXECUTING) cancel();
            // The original server owns UNKNOWN. Reconnection only makes the durable recovery entry available.
            if ((state == State.UNKNOWN || state == State.RECOVERY_REQUIRED) && !querying) flight = null;
        }
    }

    private boolean current(Flight f) {
        return sameSelection(f.request.target());
    }

    private boolean sameSelection(Selection captured) {
        return selection.get().filter(s -> s.target() == captured.target() && s.worldId().equals(captured.worldId()) && s.dimensions().equals(captured.dimensions())).isPresent();
    }

    private void set(State state, TextContent message, Throwable failure) {
        this.state = state;
        status = message;
        diagnostic = failure;
        version++;
    }

    public static TextContent text(String suffix) {
        return WorldTargetWorkbench.text(suffix);
    }
}
