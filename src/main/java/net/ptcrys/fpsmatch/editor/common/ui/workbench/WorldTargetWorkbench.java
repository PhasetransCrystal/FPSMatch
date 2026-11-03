package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.*;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-owner state for an explicit world selection; native authority stays in the host. */
public final class WorldTargetWorkbench {

    public interface Host {

        Capture capture();
    }

    public record Capture(Target target, TextContent unavailable) {}

    public interface Target {

        String label();

        List<EditorId> dimensions();

        EditorId currentDimension();

        boolean current();

        /** Owner-thread detail captured before an invalid target is released. */
        default Throwable invalidationDiagnostic() {
            return null;
        }

        Cancellation bind(Map<EditorId, EditorId> dimensions, Consumer<Result> completed);

        default void register(Consumer<RegistrationResult> completed) {
            completed.accept(new RegistrationResult(null, text("world_identity_unsupported"), null));
        }

        /** Client-owner poll, including after this target disconnects; may finish a never-started submission. */
        default void pollRegistration() {}
    }

    @FunctionalInterface
    public interface Cancellation {

        void cancel();
    }

    /** close is cancellation-only and must be safe from an I/O callback thread. */
    public interface Connection extends AutoCloseable {

        UUID worldId();

        UUID requestId();

        void verify(Consumer<Result> completed);

        default RuntimeInstallationWorkbench.Target installations() {
            return null;
        }

        @Override
        void close();
    }

    public enum FailureKind {
        GENERAL,
        IDENTITY_MISSING
    }

    public record Result(Connection connection, TextContent failure, Throwable diagnostic, FailureKind kind) {

        public Result(Connection connection, TextContent failure, Throwable diagnostic) {
            this(connection, failure, diagnostic, FailureKind.GENERAL);
        }

        public Result {
            Objects.requireNonNull(kind);
            if ((connection == null) == (failure == null)) throw new IllegalArgumentException("exactly one world result required");
            if (connection != null && kind != FailureKind.GENERAL) throw new IllegalArgumentException("success cannot be missing");
        }
    }

    /** An identity receipt is a fact, never a binding or installation capability. */
    public record RegistrationResult(UUID worldId, TextContent failure, Throwable diagnostic) {

        public RegistrationResult {
            if ((worldId == null) == (failure == null)) throw new IllegalArgumentException("exactly one registration result required");
        }
    }

    public record RegistrationReceipt(String targetLabel, RegistrationResult result) {}

    /** Queued native writes can fail before starting, but an executing write always owns its actual result. */
    public static final class RegistrationSubmission {

        private static final int QUEUED = 0, STARTED = 1, FINISHED = 2;
        private final AtomicInteger phase = new AtomicInteger(QUEUED);
        private final Consumer<RegistrationResult> completed;

        public RegistrationSubmission(Consumer<RegistrationResult> completed) {
            this.completed = Objects.requireNonNull(completed);
        }

        public void run(Supplier<RegistrationResult> operation) {
            Objects.requireNonNull(operation);
            if (!phase.compareAndSet(QUEUED, STARTED)) return;
            RegistrationResult result;
            try {
                result = Objects.requireNonNull(operation.get());
            } catch (RuntimeException | Error failure) {
                finish(new RegistrationResult(null, text("world_registration_failed"), failure));
                throw failure;
            }
            finish(result);
        }

        public void rejectBeforeStart(RegistrationResult failure) {
            Objects.requireNonNull(failure);
            if (failure.worldId() != null) throw new IllegalArgumentException("rejected submission cannot succeed");
            if (phase.compareAndSet(QUEUED, FINISHED)) completed.accept(failure);
        }

        private void finish(RegistrationResult result) {
            phase.set(FINISHED);
            completed.accept(result);
        }
    }

    public static final class RegistrationPrompt {

        private final Target target;
        private final WorldTargetWorkbench source;
        private final long version;
        private final String label;

        private RegistrationPrompt(WorldTargetWorkbench source, Target target) {
            this.source = source;
            this.target = target;
            this.version = source.version;
            this.label = target.label();
        }

        public String targetLabel() {
            return label;
        }
    }

    public enum State {
        UNAVAILABLE,
        UNBOUND,
        VERIFYING,
        BOUND,
        FAILED
    }

    private final Thread owner = Thread.currentThread();
    private final Host host;
    private final Executor completion;
    private final TreeMap<EditorId, EditorId> mappings = new TreeMap<>();
    private Target target;
    private Flight flight;
    private Connection connection;
    private Flight verification;
    private long version;
    private State state = State.UNAVAILABLE;
    private TextContent status = text("world_enter");
    private Throwable diagnostic;
    private boolean identityMissing;
    private Registration registration;

    private static final class Flight {

        Cancellation cancellation;
        volatile RuntimeException rejection;
    }

    private static final class Registration {

        Target target;
        final String label;
        final AtomicReference<RegistrationResult> outcome = new AtomicReference<>();
        boolean accepted;

        Registration(Target target, String label) {
            this.target = target;
            this.label = label;
        }
    }

    public WorldTargetWorkbench(Host host, Executor completion) {
        this.host = Objects.requireNonNull(host);
        this.completion = Objects.requireNonNull(completion);
    }

    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("world panel owner required");
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

    public String targetLabel() {
        owner();
        return target == null ? "" : target.label();
    }

    public List<EditorId> dimensions() {
        owner();
        return target == null ? List.of() : List.copyOf(target.dimensions());
    }

    public Map<EditorId, EditorId> mappings() {
        owner();
        return Collections.unmodifiableMap(new TreeMap<>(mappings));
    }

    public Optional<UUID> worldId() {
        owner();
        return connection == null ? Optional.empty() : Optional.of(connection.worldId());
    }

    public Optional<RuntimeInstallationWorkbench.Selection> installationTarget() {
        owner();
        if (state != State.BOUND || !available() || connection == null || connection.installations() == null) return Optional.empty();
        return Optional.of(new RuntimeInstallationWorkbench.Selection(connection.installations(), target.label(),
                connection.worldId(), mappings()));
    }

    public boolean available() {
        owner();
        return target != null && target.current();
    }

    public boolean busy() {
        owner();
        return flight != null || registering();
    }

    public boolean registering() {
        owner();
        return registration != null && !registration.accepted;
    }

    public Optional<RegistrationReceipt> registrationReceipt() {
        owner();
        return registration == null ? Optional.empty() : Optional.of(new RegistrationReceipt(
                registration.label, registration.accepted ? registration.outcome.get() : null));
    }

    public boolean canRegister() {
        owner();
        return identityMissing && available() && !busy();
    }

    public RegistrationPrompt registrationPrompt() {
        owner();
        if (!canRegister()) throw new IllegalStateException("a verified missing identity is required");
        return new RegistrationPrompt(this, target);
    }

    public boolean canRegister(RegistrationPrompt prompt) {
        owner();
        return prompt != null && prompt.source == this && prompt.target == target && prompt.version == version && canRegister();
    }

    /** Confirmation submits a non-cancellable write on the captured target; its receipt survives disconnect. */
    public void register(RegistrationPrompt prompt) {
        owner();
        if (!canRegister(prompt)) throw new IllegalStateException("registration confirmation is stale");
        release();
        identityMissing = false;
        Registration next = new Registration(prompt.target, prompt.label);
        registration = next;
        set(State.UNBOUND, text("world_registering"), null);
        Consumer<RegistrationResult> delivered = result -> {
            if (!next.outcome.compareAndSet(null, Objects.requireNonNull(result))) return;
            try {
                completion.execute(() -> acceptRegistration(next));
            } catch (RuntimeException ignored) { /* The actual outcome remains available to the owner tick. */ }
        };
        try {
            next.target.register(delivered);
        } catch (RuntimeException failure) {
            delivered.accept(new RegistrationResult(null, text("world_failed"), failure));
        }
    }

    private void acceptRegistration(Registration next) {
        owner();
        RegistrationResult result = next.outcome.get();
        if (registration != next || next.accepted || result == null) return;
        Target selected = next.target;
        next.target = null;
        next.accepted = true;
        version++;
        if (target == selected && selected.current()) {
            set(result.worldId() != null ? State.UNBOUND : State.FAILED,
                    result.worldId() != null ? text("world_registered_verify") : result.failure(), result.diagnostic());
        }
    }

    public void refresh() {
        owner();
        if (target != null && target.current()) return;
        release();
        identityMissing = false;
        target = null;
        mappings.clear();
        try {
            Capture captured = Objects.requireNonNull(host.capture());
            target = captured.target();
            if (target == null) set(State.UNAVAILABLE, Objects.requireNonNull(captured.unavailable()), null);
            else {
                if (!target.current()) {
                    target = null;
                    set(State.UNAVAILABLE, text("world_disconnected"), null);
                    return;
                }
                mappings.put(target.currentDimension(), target.currentDimension());
                set(State.UNBOUND, text("world_unverified"), null);
            }
        } catch (RuntimeException failure) {
            target = null;
            set(State.FAILED, text("world_failed"), failure);
        }
    }

    public void setMapping(EditorId source, EditorId destination) {
        owner();
        Objects.requireNonNull(source);
        Objects.requireNonNull(destination);
        requireNotRegistering();
        if (!available() || !target.dimensions().contains(destination)) throw new IllegalArgumentException("unavailable target dimension");
        var candidate = new TreeMap<>(mappings);
        candidate.put(source, destination);
        if (candidate.size() > 64 || new HashSet<>(candidate.values()).size() != candidate.size())
            throw new IllegalArgumentException("one to 64 unique target dimensions required");
        if (candidate.equals(mappings)) return;
        release();
        identityMissing = false;
        mappings.clear();
        mappings.putAll(candidate);
        set(State.UNBOUND, text("world_unverified"), null);
    }

    public void removeMapping(EditorId source) {
        owner();
        requireNotRegistering();
        if (!mappings.containsKey(source)) return;
        release();
        identityMissing = false;
        mappings.remove(source);
        set(State.UNBOUND, text("world_unverified"), null);
    }

    public void bind() {
        owner();
        requireNotRegistering();
        if (!available() || mappings.isEmpty()) throw new IllegalStateException("world target and mapping required");
        release();
        identityMissing = false;
        Target captured = target;
        var next = new Flight();
        flight = next;
        set(State.VERIFYING, text("world_verifying"), null);
        try {
            Cancellation handle = captured.bind(Map.copyOf(mappings), result -> complete(result, next, () -> {
                owner();
                if (flight != next || target != captured || !captured.current()) {
                    if (result.connection() != null) result.connection().close();
                    if (flight == next) {
                        release();
                        target = null;
                        set(State.UNAVAILABLE, text("world_disconnected"), null);
                    }
                    return;
                }
                flight = null;
                if (result.connection() != null) {
                    connection = result.connection();
                    set(State.BOUND, text("world_bound"), null);
                } else {
                    identityMissing = result.kind() == FailureKind.IDENTITY_MISSING;
                    set(State.FAILED, result.failure(), result.diagnostic());
                }
            }));
            next.cancellation = Objects.requireNonNull(handle);
            if (flight != next && connection == null) handle.cancel();
        } catch (RuntimeException failure) {
            if (flight == next) {
                release();
                set(State.FAILED, text("world_failed"), failure);
            } else throw failure;
        }
    }

    /** Closing a verification panel cancels in-flight work, while an accepted target survives. */
    public void cancelPending() {
        owner();
        if (flight == null) return;
        release();
        set(State.UNBOUND, text("world_cancelled"), null);
    }

    public void disconnect() {
        owner();
        release();
        identityMissing = false;
        set(target == null ? State.UNAVAILABLE : State.UNBOUND, text("world_unverified"), null);
    }

    /** Call on client ticks, periodically with checkAuthority=true; never performs filesystem I/O. */
    public void tick(boolean checkAuthority) {
        owner();
        if (registering()) registration.target.pollRegistration();
        if (registration != null) acceptRegistration(registration);
        RuntimeException rejected = flight != null ? flight.rejection : verification == null ? null : verification.rejection;
        if (rejected != null) {
            release();
            set(State.FAILED, text("world_failed"), rejected);
            return;
        }
        if (target != null && !target.current()) {
            Throwable invalidation;
            try {
                invalidation = target.invalidationDiagnostic();
            } catch (RuntimeException failure) {
                invalidation = failure;
            }
            release();
            identityMissing = false;
            target = null;
            set(State.UNAVAILABLE, text("world_disconnected"), invalidation);
            return;
        }
        if (!checkAuthority || connection == null || verification != null) return;
        Connection captured = connection;
        Flight next = new Flight();
        verification = next;
        try {
            captured.verify(result -> complete(result, next, () -> {
                owner();
                if (connection != captured || verification != next) {
                    if (result.connection() != null && result.connection() != connection) result.connection().close();
                    return;
                }
                verification = null;
                if (result.failure() != null) {
                    release();
                    set(State.FAILED, result.failure(), result.diagnostic());
                } else if (result.connection() != captured) {
                    result.connection().close();
                    release();
                    set(State.FAILED, text("world_failed"), null);
                }
            }));
        } catch (RuntimeException failure) {
            release();
            set(State.FAILED, text("world_failed"), failure);
        }
    }

    private void complete(Result result, Flight delivery, Runnable operation) {
        try {
            completion.execute(operation);
        } catch (RuntimeException failure) {
            delivery.rejection = failure;
            if (result.connection() != null) result.connection().close();
            throw failure;
        }
    }

    private void release() {
        Flight previous = flight;
        flight = null;
        Connection old = connection;
        connection = null;
        verification = null;
        if (previous != null && previous.cancellation != null) previous.cancellation.cancel();
        if (old != null) old.close();
    }

    private void requireNotRegistering() {
        if (registering()) throw new IllegalStateException("registration is still running");
    }

    private void set(State next, TextContent message, Throwable failure) {
        state = next;
        status = Objects.requireNonNull(message);
        diagnostic = failure;
        version++;
    }

    public static TextContent text(String suffix) {
        return new TextContent.Message("editor.workbench." + suffix, List.of());
    }
}
