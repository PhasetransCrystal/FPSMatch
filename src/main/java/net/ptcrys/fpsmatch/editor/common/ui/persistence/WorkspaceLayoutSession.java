package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import net.ptcrys.fpsmatch.editor.common.ui.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Owner-thread presentation coordinator. File operations require one serial IO executor.
 * Acknowledgements merge confirmed baselines, never newer panels or another active scope.
 */
public final class WorkspaceLayoutSession {

    private record Scope(Path directory, UUID project) {

        Scope {
            if ((directory == null) != (project == null)) throw new IllegalArgumentException("incomplete layout scope");
            if (directory != null) directory = directory.toAbsolutePath().normalize();
        }
    }

    private static final class Context {

        final Scope scope;
        volatile WorkspaceState latest;
        volatile boolean loaded;
        WorkspaceState queued;
        long revision, operation, lastResult, explicit;
        int quietTicks, pending;
        volatile boolean protectedFile;
        boolean failed, cancelled;
        String problem;
        // Exclusively accessed on the serial IO executor.
        WorkspaceLayoutStore store;
        WorkspaceLayoutStore.LoadResult receipt;

        Context(Scope scope, WorkspaceState state) {
            this.scope = scope;
            latest = state;
        }
    }

    private final WorkspaceManager manager;
    private final Path directory;
    private final Executor io, completion;
    private final Runnable restored, changed;
    private final Consumer<Throwable> failures;
    private volatile Context current;
    // Accessed only by the serial IO executor, including the final shutdown barrier.
    private final Map<Scope, Throwable> writeFailures = new HashMap<>();

    public WorkspaceLayoutSession(WorkspaceManager manager, Path directory, Executor io, Executor completion,
                                  Runnable restored, Runnable changed, Consumer<Throwable> failures) {
        this.manager = Objects.requireNonNull(manager);
        this.directory = Objects.requireNonNull(directory);
        this.io = Objects.requireNonNull(io);
        this.completion = Objects.requireNonNull(completion);
        this.restored = Objects.requireNonNull(restored);
        this.changed = Objects.requireNonNull(changed);
        this.failures = Objects.requireNonNull(failures);
    }

    public boolean matches(Path projectDirectory, UUID project) {
        return current != null && current.scope.equals(new Scope(projectDirectory, project));
    }

    public void select(Path projectDirectory, UUID project, WorkspaceState defaults) {
        Scope scope = new Scope(projectDirectory, project);
        if (current != null && current.scope.equals(scope)) return;
        capture();
        flush();
        start(scope, defaults);
    }

    private void start(Scope scope, WorkspaceState defaults) {
        Context next = new Context(scope, defaults);
        current = next;
        manager.restore(defaults);
        restored.run();
        changed.run();
        try {
            io.execute(() -> {
                try {
                    next.store = new WorkspaceLayoutStore(directory, scopeHash(scope));
                    var receipt = next.store.load();
                    next.receipt = receipt;
                    completion.execute(() -> {
                        if (current != next || next.cancelled) return;
                        next.protectedFile = receipt.status() == WorkspaceLayoutStore.Status.PROTECTED;
                        next.problem = next.protectedFile ? "layout_storage_protected" : null;
                        WorkspaceState loaded = receipt.state().orElse(defaults);
                        manager.restore(loaded);
                        next.latest = loaded;
                        next.loaded = true;
                        next.queued = receipt.state().orElse(null);
                        restored.run();
                        changed.run();
                    });
                } catch (Exception failure) {
                    completion.execute(() -> loadFailed(next, failure));
                }
            });
        } catch (RuntimeException failure) {
            loadFailed(next, failure);
        }
    }

    private void loadFailed(Context context, Throwable failure) {
        failures.accept(failure);
        if (current != context || context.cancelled) return;
        context.protectedFile = true;
        context.problem = "layout_storage_protected";
        context.loaded = true;
        changed.run();
    }

    private static String scopeHash(Scope scope) throws Exception {
        String identity = "fpsmatch-studio-layout-v1\nno-project";
        if (scope.directory() != null) {
            String real = scope.directory().toRealPath().toString();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) real = real.toLowerCase(Locale.ROOT);
            identity = "fpsmatch-studio-layout-v1\n" + real + "\n" + scope.project();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean loading() {
        return current != null && !current.loaded;
    }

    /** Cancels publication, not the underlying file read. Defaults remain protected until reload. */
    public void cancelLoad() {
        Context context = current;
        if (context == null || context.loaded) return;
        context.cancelled = true;
        context.protectedFile = true;
        context.problem = "layout_storage_cancelled";
        context.loaded = true;
        changed.run();
    }

    public boolean canSave() {
        return current != null && current.loaded && !current.protectedFile && current.explicit == 0;
    }

    public boolean saving() {
        return current != null && current.pending > 0;
    }

    public Optional<String> problem() {
        return current == null ? Optional.empty() : Optional.ofNullable(current.problem);
    }

    public String statusKey() {
        if (loading()) return "layout_storage_loading";
        if (problem().isPresent()) return problem().orElseThrow();
        if (saving()) return "layout_storage_saving";
        return current != null && !Objects.equals(current.latest, current.queued) ? "layout_storage_pending" : "layout_storage_saved";
    }

    /** Captures immutable presentation data only; no IO or domain edits happen here. */
    public void capture() {
        Context context = current;
        if (context == null || !context.loaded) return;
        WorkspaceState value = manager.snapshot();
        if (value.equals(context.latest)) return;
        context.latest = value;
        context.revision++;
        context.quietTicks = 0;
    }

    /** Debounces automatic persistence; callers also flush when leaving the screen or scope. */
    public void tick() {
        Context context = current;
        if (context == null || !context.loaded || context.protectedFile || context.failed) return;
        if (!Objects.equals(context.latest, context.queued) && ++context.quietTicks >= 20) flush();
    }

    public void flush() {
        Context context = current;
        if (context != null && context.loaded && !context.protectedFile && !context.failed && !Objects.equals(context.latest, context.queued))
            schedule(context, context.latest, null);
    }

    public void retry() {
        Context context = current;
        if (context == null || !context.loaded || context.protectedFile) return;
        capture();
        schedule(context, context.latest, null);
    }

    public void saveLayout(UUID workspace) {
        if (!canSave()) return;
        capture();
        Context context = current;
        if (context.latest.workspaces().stream().noneMatch(value -> value.id().equals(workspace))) throw new IllegalArgumentException("unknown workspace");
        schedule(context, context.latest, workspace);
    }

    /** Explicitly reloads the disk environment. Document drafts are not affected. */
    public void reload(WorkspaceState defaults) {
        Context context = current;
        if (context == null || loading() || saving()) return;
        start(context.scope, defaults);
    }

    private void schedule(Context context, WorkspaceState snapshot, UUID explicitWorkspace) {
        long operation = ++context.operation, revision = context.revision;
        context.queued = snapshot;
        context.pending++;
        context.failed = false;
        if (explicitWorkspace != null) context.explicit = operation;
        changed.run();
        try {
            io.execute(() -> {
                try {
                    WorkspaceState written = write(context, snapshot, explicitWorkspace);
                    completion.execute(() -> completed(context, operation, revision, written, null));
                } catch (Exception failure) {
                    completion.execute(() -> completed(context, operation, revision, null, failure));
                }
            });
        } catch (RuntimeException failure) {
            completed(context, operation, revision, null, failure);
        }
    }

    private WorkspaceState write(Context context, WorkspaceState snapshot, UUID explicitWorkspace) throws Exception {
        try {
            if (context.store == null || context.receipt == null) throw new IllegalStateException("layout has not loaded");
            WorkspaceState candidate = mergeBaselines(snapshot, context.receipt.state().orElse(null), explicitWorkspace);
            // Each queued write uses the latest receipt, including a preceding explicit save.
            // Equality with an old receipt does not prove that the file is still unchanged.
            context.receipt = context.store.save(candidate, context.receipt);
            writeFailures.remove(context.scope);
            return candidate;
        } catch (Exception failure) {
            writeFailures.put(context.scope, failure);
            throw failure;
        }
    }

    private static WorkspaceState mergeBaselines(WorkspaceState working, WorkspaceState confirmed, UUID explicitWorkspace) {
        Map<UUID, List<PanelInstance>> saved = new LinkedHashMap<>(working.savedLayouts());
        for (var workspace : working.workspaces()) {
            if (workspace.id().equals(explicitWorkspace)) saved.put(workspace.id(), workspace.panels());
            else if (confirmed != null && confirmed.savedLayouts().containsKey(workspace.id())) saved.put(workspace.id(), confirmed.savedLayouts().get(workspace.id()));
        }
        return new WorkspaceState(working.workspaces(), working.activeWorkspace(), saved);
    }

    private void completed(Context context, long operation, long revision, WorkspaceState written, Throwable failure) {
        context.pending--;
        if (context.explicit == operation) context.explicit = 0;
        if (failure != null) failures.accept(failure);
        if (current != context || operation < context.lastResult) return;
        context.lastResult = operation;
        if (failure != null) {
            context.failed = true;
            context.problem = "layout_storage_write_failed";
            changed.run();
            return;
        }
        context.failed = false;
        context.problem = null;
        // Input can mutate the manager before the next render captures it. Record those changes
        // before merging the acknowledgement, so they cannot be relabelled as already written.
        capture();
        // Adopt only saved baselines: intervening moves, names and visibility remain intact.
        manager.restore(mergeBaselines(manager.snapshot(), written, null));
        context.latest = manager.snapshot();
        if (context.revision == revision) context.queued = context.latest;
        changed.run();
    }

    /** Shutdown hook entry: immutable capture only, no owner-thread manager or UI access. */
    public CompletableFuture<Void> flushOnShutdown() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Context context = current;
        WorkspaceState captured = context != null && context.loaded && !context.protectedFile ? context.latest : null;
        // Always queue a barrier: another scope may still have a write pending while this one loads.
        try {
            io.execute(() -> {
                try {
                    if (captured != null && !context.protectedFile) write(context, captured, null);
                    if (!writeFailures.isEmpty()) throw new java.io.IOException("An earlier workspace layout write was not confirmed", writeFailures.values().iterator().next());
                    done.complete(null);
                } catch (Exception failure) {
                    failures.accept(failure);
                    done.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            done.completeExceptionally(failure);
        }
        return done;
    }
}
