package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore.Binding;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owner-thread authoring association. Runtime world authority remains a separate verification. */
public final class ProjectWorldWorkbench {

    public enum State {
        NONE,
        CHECKING,
        UNBOUND,
        READY,
        BINDING,
        BLOCKED,
        UNKNOWN
    }

    public enum PermissionValidity {
        CURRENT,
        FOREIGN_CAPTURE,
        GENERATION_CHANGED,
        ASSOCIATION_NOT_READY,
        PROJECT_PROTECTED,
        PROJECT_CHANGED,
        ASSOCIATION_CHANGED
    }

    private enum Operation {
        READ,
        CHECK,
        INSPECT,
        CREATE,
        BIND
    }

    private record Scope(Path path, UUID id, boolean required) {}

    private record Creation(Path path, World world) {}

    private record Outcome(Object value, Throwable failure) {}

    private static final class Flight {

        final long generation;
        final Operation operation;
        final State previous;
        final Consumer<Object> accept;
        final AtomicReference<Outcome> result = new AtomicReference<>();

        Flight(long generation, Operation operation, State previous, Consumer<Object> accept) {
            this.generation = generation;
            this.operation = operation;
            this.previous = previous;
            this.accept = accept;
        }
    }

    /** Immutable capture; its association must be checked again by I/O before runtime admission. */
    public static final class Permission {

        private final ProjectWorldWorkbench owner;
        private final long generation;
        private final Scope scope;
        private final Binding binding;

        private Permission(ProjectWorldWorkbench owner, long generation, Scope scope, Binding binding) {
            this.owner = owner;
            this.generation = generation;
            this.scope = scope;
            this.binding = binding;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final ProjectWorkbench model;
    private final ProjectWorldStore store;
    private final Executor io, completion;
    private Scope scope;
    private Binding binding;
    private Flight flight;
    private Creation creation;
    private State state = State.NONE;
    private Throwable failure;
    private long generation, version;
    private int checkTicks;

    public ProjectWorldWorkbench(ProjectWorkbench model, ProjectWorldStore store, Executor io, Executor completion) {
        this.model = Objects.requireNonNull(model);
        this.store = Objects.requireNonNull(store);
        this.io = Objects.requireNonNull(io);
        this.completion = Objects.requireNonNull(completion);
    }

    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("project world owner thread required");
    }

    public State state() {
        owner();
        return state;
    }

    public long version() {
        owner();
        return version;
    }

    public Optional<Binding> binding() {
        owner();
        return Optional.ofNullable(binding);
    }

    public Optional<Throwable> failure() {
        owner();
        return Optional.ofNullable(failure);
    }

    public boolean requiresWorld() {
        owner();
        return binding != null || model.projectRequiresWorld();
    }

    public boolean busy() {
        owner();
        return flight != null && flight.operation != Operation.CHECK || creation != null;
    }

    public TextContent status() {
        owner();
        return ProjectWorkbench.text(switch (state) {
            case NONE -> "project_world_none";
            case CHECKING -> "project_world_checking";
            case UNBOUND -> "project_world_required";
            case READY -> "project_world_ready";
            case BINDING -> "project_world_binding";
            case BLOCKED -> "project_world_blocked";
            case UNKNOWN -> "project_world_unknown";
        });
    }

    private Scope currentScope() {
        return model.hasProject() ? new Scope(model.directory().orElseThrow(), model.projectId().orElseThrow(), model.projectRequiresWorld()) : null;
    }

    /** Called even when the screen is closed, so writes always retain their actual result. */
    public void tick() {
        owner();
        Scope current = currentScope();
        if (!Objects.equals(current, scope)) {
            generation++;
            flight = null;
            scope = current;
            binding = null;
            failure = null;
            checkTicks = 0;
            if (scope == null) {
                set(State.NONE, null);
            } else if (creation != null && creation.path.equals(scope.path)) {
                World selected = creation.world;
                creation = null;
                startBind(selected);
            } else {
                creation = null;
                read(false);
            }
        }
        poll();
        if (creation != null && !model.busy() && !model.hasProject()) {
            if (model.pendingCreation()) {
                if (model.unresolvedSave()) set(State.UNKNOWN, null);
            } else {
                creation = null;
                set(State.BLOCKED, model.technicalFailure().orElse(null));
            }
        }
        if (state == State.READY && binding != null && flight == null && ++checkTicks >= 100) {
            checkTicks = 0;
            read(true);
        }
    }

    public void refresh() {
        owner();
        tick();
        if (scope == null || busy()) return;
        generation++;
        flight = null;
        read(false);
    }

    private void read(boolean quiet) {
        Scope selected = scope;
        Binding expected = binding;
        if (!quiet) set(State.CHECKING, null);
        submit(quiet ? Operation.CHECK : Operation.READ, () -> {
            Optional<Binding> found = store.read(selected.path, selected.id);
            if (expected != null && found.isEmpty()) throw new IOException("Established authoring association disappeared");
            if (found.isPresent()) {
                // Retain the immutable saved target even when the world is now missing or replaced.
                Binding value = found.get();
                try {
                    store.verify(value.world());
                } catch (IOException | RuntimeException error) {
                    return new Read(value, error);
                }
            }
            return new Read(found.orElse(null), null);
        }, value -> {
            binding = value.binding;
            if (value.failure != null) set(State.BLOCKED, value.failure);
            else set(binding != null || !selected.required ? State.READY : State.UNBOUND, null);
        });
    }

    private record Read(Binding binding, Throwable failure) {}

    public void inspect(Path root, String label, Consumer<World> selected) {
        owner();
        Objects.requireNonNull(selected);
        cancelInspection();
        requireIdle();
        Path fixed = absolute(root);
        if (fixed.getParent() == null) throw new IllegalArgumentException("A save directory is required");
        submit(Operation.INSPECT, () -> store.inspect(fixed.getParent(), fixed.getFileName().toString(), label), selected);
    }

    public void cancelInspection() {
        owner();
        if (flight != null && flight.operation == Operation.INSPECT) {
            flight = null;
            version++;
        }
    }

    public boolean canCancelPreparation() {
        owner();
        return flight != null && flight.operation == Operation.CREATE;
    }

    /** Cancellation ends only pre-publication verification; a started project/binding write is retained. */
    public void cancelPreparation() {
        owner();
        if (canCancelPreparation()) {
            flight = null;
            set(State.NONE, null);
            version++;
        }
    }

    /** Validates the selected world before project publication and binds its exact returned project identity. */
    public void create(Path path, String name, EditorId template, String documentName, Map<String, String> raw, World world) {
        owner();
        requireIdle();
        if (model.hasProject() || model.pendingCreation()) throw new IllegalStateException("Close the current project first");
        boolean required = model.templateRequiresWorld(template);
        if (required && world == null) throw new IllegalArgumentException("A save must be selected before project creation");
        Path fixed = absolute(path);
        Map<String, String> inputs = Map.copyOf(raw);
        if (!required) {
            model.create(fixed, name, template, documentName, inputs);
            return;
        }
        set(State.CHECKING, null);
        submit(Operation.CREATE, () -> {
            store.verify(world);
            return world;
        }, verified -> {
            creation = new Creation(fixed, verified);
            try {
                model.create(fixed, name, template, documentName, inputs);
                set(State.BINDING, null);
            } catch (RuntimeException error) {
                creation = null;
                model.report(error);
                set(State.BLOCKED, error);
            }
        });
    }

    public void bind(World world) {
        owner();
        tick();
        requireIdle();
        if (scope == null || state != State.UNBOUND || binding != null) throw new IllegalStateException("First binding is not available");
        startBind(Objects.requireNonNull(world));
    }

    private void startBind(World world) {
        Scope selected = scope;
        set(State.BINDING, null);
        submit(Operation.BIND, () -> store.bind(selected.path, selected.id, world), value -> {
            binding = value;
            set(State.READY, null);
        });
    }

    public void locateOriginal(Path root) {
        owner();
        tick();
        requireIdle();
        if (state == State.UNKNOWN) throw new IllegalStateException("Read the uncertain binding before locating its world");
        if (scope == null || binding == null) throw new IllegalStateException("An existing binding is required");
        Path fixed = absolute(root);
        if (fixed.getParent() == null) throw new IllegalArgumentException("A save directory is required");
        Scope selected = scope;
        String label = binding.world().label();
        set(State.BINDING, null);
        submit(Operation.BIND, () -> store.locateOriginal(selected.path, selected.id, fixed.getParent(), fixed.getFileName().toString(), label),
                value -> {
                    binding = value;
                    set(State.READY, null);
                });
    }

    public boolean entryAllowed(Path savesRoot, String levelId) {
        owner();
        if (levelId == null || levelId.contains("/") || levelId.contains("\\")) return false;
        return allows(savesRoot.resolve(levelId));
    }

    public boolean allows(Path root) {
        return permission(root).isPresent();
    }

    public Optional<Permission> permission(Path root) {
        owner();
        if (scope == null || model.protectedMode() || !Objects.equals(scope, currentScope()) || state != State.READY) return Optional.empty();
        if (binding != null && !binding.world().root().equals(root.toAbsolutePath().normalize())) return Optional.empty();
        return Optional.of(new Permission(this, generation, scope, binding));
    }

    public boolean current(Permission permit) {
        return validity(permit) == PermissionValidity.CURRENT;
    }

    /** Reports the first failed check without changing the capture or granting new authority. */
    public PermissionValidity validity(Permission permit) {
        owner();
        if (permit == null || permit.owner != this) return PermissionValidity.FOREIGN_CAPTURE;
        if (permit.generation != generation) return PermissionValidity.GENERATION_CHANGED;
        if (state != State.READY) return PermissionValidity.ASSOCIATION_NOT_READY;
        if (model.protectedMode()) return PermissionValidity.PROJECT_PROTECTED;
        if (!Objects.equals(scope, currentScope()) || !Objects.equals(scope, permit.scope)) return PermissionValidity.PROJECT_CHANGED;
        if (!Objects.equals(binding, permit.binding)) return PermissionValidity.ASSOCIATION_CHANGED;
        return PermissionValidity.CURRENT;
    }

    /** Owner-only. A copied value or another physical project cannot borrow this association. */
    public void requireRuntimeSource(Permission permit, ProjectWorkbench.SavedRuntimeSource source) {
        owner();
        if (!current(permit) || !model.ownsRuntimeSource(source))
            throw new IllegalStateException("The runtime source does not belong to the captured authoring project");
    }

    /** I/O-only. Checking a capture never grants a live server capability. */
    public void verify(Permission permit) throws IOException {
        Objects.requireNonNull(permit);
        if (permit.owner != this) throw new IllegalArgumentException("Foreign authoring capture");
        if (permit.binding == null) return;
        Binding actual = store.read(permit.scope.path, permit.scope.id).orElseThrow(() -> new IOException("Authoring association disappeared"));
        if (!actual.equals(permit.binding)) throw new IOException("Authoring association changed");
        store.verify(actual.world());
    }

    private void requireIdle() {
        if (busy() || model.busy()) throw new IllegalStateException("An operation is already in progress");
        if (flight != null) flight = null;
    }

    private <T> void submit(Operation operation, Callable<T> action, Consumer<T> accepted) {
        @SuppressWarnings("unchecked")
        Consumer<Object> accept = value -> accepted.accept((T) value);
        Flight next = new Flight(generation, operation, state, accept);
        flight = next;
        failure = null;
        version++;
        try {
            io.execute(() -> {
                Outcome outcome;
                try {
                    outcome = new Outcome(action.call(), null);
                } catch (Exception error) {
                    outcome = new Outcome(null, error);
                }
                next.result.set(outcome);
                try {
                    completion.execute(this::poll);
                } catch (RuntimeException ignored) {/* Owner tick delivers the retained actual result. */}
            });
        } catch (RuntimeException error) {
            next.result.set(new Outcome(null, error));
            poll();
        }
    }

    private void poll() {
        owner();
        Flight next = flight;
        if (next == null) return;
        Outcome outcome = next.result.get();
        if (outcome == null) return;
        if (!Objects.equals(scope, currentScope())) {
            tick();
            return;
        }
        flight = null;
        if (next.generation != generation) return;
        version++;
        if (outcome.failure != null) {
            failure = outcome.failure;
            if (next.operation == Operation.INSPECT) {
                set(next.previous, outcome.failure);
                return;
            }
            boolean unknown = outcome.failure instanceof ProjectWorldStore.BindingException error && error.code() == ProjectWorldStore.Code.UNKNOWN;
            set(unknown ? State.UNKNOWN : State.BLOCKED, outcome.failure);
        } else next.accept.accept(outcome.value);
    }

    private void set(State next, Throwable error) {
        if (state != next || failure != error) {
            state = next;
            failure = error;
            version++;
        }
    }

    private static Path absolute(Path path) {
        Objects.requireNonNull(path);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute path required");
        return path.normalize();
    }
}
