package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Fixed-version test task. Draft editing and saving never change its captured input. */
public final class RuntimeTestWorkbench {

    public enum State {
        IDLE,
        PREPARING,
        READY,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        CANCELLED,
        FAILED,
        INACTIVE
    }

    public interface Prepared {

        InstallationPlan plan();
    }

    public interface Running {

        InstallationPlan plan();
    }

    public record Preparation(Prepared prepared, List<Problem> problems, TextContent failure, Throwable diagnostic) {

        public Preparation {
            problems = List.copyOf(problems);
        }
    }

    public record Outcome(State state, Running running, TextContent message, Throwable diagnostic) {

        public Outcome {
            Objects.requireNonNull(state);
            Objects.requireNonNull(message);
        }
    }

    public interface Target {

        void prepare(InstallationPlan plan, BooleanSupplier cancelled, Consumer<Preparation> completed);

        void start(Prepared prepared, BooleanSupplier cancelled, Consumer<Outcome> completed);

        void stop(Running running, Consumer<Outcome> completed);

        void status(Running running, Consumer<Outcome> completed);
    }

    public record Input(String worldLabel, UUID worldId, InstallationPlan plan) {}

    private final Thread owner = Thread.currentThread();
    private final Supplier<Optional<RuntimeInstallationWorkbench.Selection>> selection;
    private final ConcurrentLinkedQueue<Runnable> replies = new ConcurrentLinkedQueue<>();
    private State state = State.IDLE;
    private TextContent message = text("idle");
    private Throwable diagnostic;
    private List<Problem> problems = List.of();
    private Input input;
    private Flight flight;
    private long version;
    private int ticks;

    private static final class Flight {

        final RuntimeInstallationWorkbench.Selection selection;
        final Target target;
        final Input input;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Prepared prepared;
        Running running;
        boolean polling, stopping;
        long statusGeneration;

        Flight(RuntimeInstallationWorkbench.Selection selection, Target target, Input input) {
            this.selection = selection;
            this.target = target;
            this.input = input;
        }
    }

    public RuntimeTestWorkbench(Supplier<Optional<RuntimeInstallationWorkbench.Selection>> selection) {
        this.selection = Objects.requireNonNull(selection);
    }

    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("test panel owner required");
    }

    public State state() {
        owner();
        return state;
    }

    public long version() {
        owner();
        return version;
    }

    public TextContent status() {
        owner();
        return message;
    }

    public Optional<Throwable> diagnostic() {
        owner();
        return Optional.ofNullable(diagnostic);
    }

    public List<Problem> problems() {
        owner();
        return problems;
    }

    public Optional<Input> input() {
        owner();
        return Optional.ofNullable(input);
    }

    public boolean busy() {
        owner();
        return state == State.PREPARING || state == State.STARTING || state == State.STOPPING;
    }

    public boolean canPrepare() {
        owner();
        return flight == null;
    }

    public boolean canConfirm() {
        owner();
        return state == State.READY && flight != null && current(flight) && problems.stream().noneMatch(p -> p.severity() == Problem.Severity.ERROR);
    }

    public boolean canStop() {
        owner();
        return flight != null && flight.running != null && !flight.stopping && state == State.RUNNING;
    }

    public void prepare(UUID expectedWorldId, InstallationPlan plan) {
        owner();
        if (!canPrepare()) throw new IllegalStateException("test still owns its runtime");
        Objects.requireNonNull(plan);
        var selected = selection.get().orElseThrow(() -> new IllegalStateException("verified world required"));
        if (!selected.worldId().equals(expectedWorldId)) throw new IllegalStateException("test world changed");
        Target target = selected.target().tests();
        if (target == null) throw new IllegalStateException("runtime testing unavailable");
        input = new Input(selected.label(), selected.worldId(), plan);
        problems = List.of();
        Flight next = new Flight(selected, target, input);
        flight = next;
        set(State.PREPARING, text("preparing"), null);
        Consumer<Preparation> completed = once(result -> acceptPreparation(next, result));
        try {
            target.prepare(plan, next.cancelled::get, completed);
        } catch (RuntimeException failure) {
            completed.accept(new Preparation(null, List.of(), text("failed"), failure));
        }
    }

    private void acceptPreparation(Flight next, Preparation result) {
        if (flight != next) return;
        if (next.cancelled.get() || !current(next)) {
            flight = null;
            set(State.CANCELLED, text("cancelled"), null);
            return;
        }
        problems = result.problems();
        if (result.prepared() == null) {
            flight = null;
            set(State.FAILED, result.failure() == null ? text("failed") : result.failure(), result.diagnostic());
            return;
        }
        if (!next.input.plan().equals(result.prepared().plan())) {
            flight = null;
            set(State.FAILED, text("stale"), null);
            return;
        }
        next.prepared = result.prepared();
        set(State.READY, text("ready"), null);
    }

    public void confirm() {
        owner();
        if (!canConfirm()) throw new IllegalStateException("test confirmation is stale or blocked");
        Flight next = flight;
        set(State.STARTING, text("starting"), null);
        Consumer<Outcome> completed = once(result -> acceptStart(next, result));
        try {
            next.target.start(next.prepared, next.cancelled::get, completed);
        } catch (RuntimeException failure) {
            completed.accept(new Outcome(State.FAILED, null, text("failed"), failure));
        }
    }

    private void acceptStart(Flight next, Outcome result) {
        if (flight != next) return;
        next.prepared = null;
        next.running = result.running();
        if (result.running() != null && !next.input.plan().equals(result.running().plan())) {
            // A broken target must not authorize operations on a different native runtime.
            set(State.FAILED, text("stale"), result.diagnostic());
            return;
        }
        if (next.running == null && next.cancelled.get() && result.diagnostic() instanceof CancellationException) {
            flight = null;
            set(State.CANCELLED, text("cancelled"), null);
            return;
        }
        set(result.state(), result.message(), result.diagnostic());
        if (next.running == null) {
            flight = null;
            return;
        }
        if (next.cancelled.get() || !current(next)) stop(next);
    }

    public void cancel() {
        owner();
        Flight next = flight;
        if (next == null) return;
        next.cancelled.set(true);
        if (next.running != null) {
            if (!next.stopping && state == State.RUNNING) stop(next);
            return;
        }
        if (state == State.READY) {
            flight = null;
            set(State.CANCELLED, text("cancelled"), null);
        }
    }

    public void stop() {
        owner();
        if (canStop()) stop(flight);
    }

    private void stop(Flight next) {
        if (next.stopping) return;
        // A status captured before stop cannot establish whether a failed stop is safe to retry.
        next.statusGeneration++;
        next.polling = false;
        next.stopping = true;
        set(State.STOPPING, text("stopping"), null);
        Consumer<Outcome> completed = once(result -> {
            if (flight != next) return;
            next.stopping = false;
            acceptStatus(next, result);
        });
        try {
            next.target.stop(next.running, completed);
        } catch (RuntimeException failure) {
            completed.accept(new Outcome(State.FAILED, next.running, text("stop_failed"), failure));
        }
    }

    private void acceptStatus(Flight next, Outcome result) {
        if (flight != next) return;
        set(result.state(), result.message(), result.diagnostic());
        if (result.running() == null) {
            next.running = null;
            flight = null;
        }
    }

    public void tick() {
        owner();
        Runnable reply;
        while ((reply = replies.poll()) != null) reply.run();
        Flight next = flight;
        if (next == null) return;
        next.selection.target().poll();
        if (!current(next)) cancel();
        if (flight != next || next.running == null || next.stopping || next.polling || state != State.RUNNING && state != State.FAILED || ++ticks % 20 != 0) return;
        next.polling = true;
        long generation = ++next.statusGeneration;
        Consumer<Outcome> completed = once(result -> {
            if (flight != next || generation != next.statusGeneration) return;
            next.polling = false;
            if (!next.stopping) acceptStatus(next, result);
        });
        try {
            next.target.status(next.running, completed);
        } catch (RuntimeException failure) {
            completed.accept(new Outcome(State.FAILED, next.running, text("failed"), failure));
        }
    }

    private boolean current(Flight captured) {
        return selection.get().filter(s -> s.target() == captured.selection.target() && s.worldId().equals(captured.input.worldId()) && s.dimensions().equals(captured.selection.dimensions())).isPresent();
    }

    private <T> Consumer<T> once(Consumer<T> accepted) {
        AtomicBoolean delivered = new AtomicBoolean();
        return result -> { if (delivered.compareAndSet(false, true)) replies.add(() -> accepted.accept(result)); };
    }

    private void set(State state, TextContent message, Throwable diagnostic) {
        this.state = state;
        this.message = message;
        this.diagnostic = diagnostic;
        version++;
    }

    public static TextContent text(String reason) {
        return new TextContent.Message("fpsmatch.editor.runtime_test." + reason, List.of());
    }
}
