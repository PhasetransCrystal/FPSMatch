package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.*;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeInstallationProfile;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeInstallationWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeInstallationWorkbench.*;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeTestWorkbench;
import net.ptcrys.fpsmatch.editor.core.installation.*;
import net.ptcrys.fpsmatch.editor.core.session.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.*;
import net.ptcrys.fpsmatch.editor.core.worldio.*;

import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Exact integrated-server bridge; neither UI selection nor a UUID can manufacture native authority. */
final class MinecraftWorkbenchRuntimeInstallations implements RuntimeInstallationWorkbench.Target, RuntimeTestWorkbench.Target {

    private final MinecraftServer server;
    private final FPSMCore core;
    private final WorldBinding binding;
    private final ExtensionRegistry registry;
    private final Executor io;
    private final BooleanSupplier disconnected;
    private final ProjectWorldWorkbench projectWorld;
    private final ProjectWorldWorkbench.Permission permission;
    private final ProjectWorldStore.World authoringWorld;
    private final ConcurrentLinkedQueue<ServerCall<?>> calls = new ConcurrentLinkedQueue<>();

    MinecraftWorkbenchRuntimeInstallations(FPSMCore core, WorldBinding binding, ExtensionRegistry registry,
                                           Executor io, BooleanSupplier disconnected, ProjectWorldWorkbench projectWorld,
                                           ProjectWorldWorkbench.Permission permission, ProjectWorldStore.World authoringWorld) {
        this.core = core;
        server = core.getServer();
        this.binding = binding;
        this.registry = registry;
        this.io = io;
        this.disconnected = disconnected;
        this.projectWorld = Objects.requireNonNull(projectWorld);
        this.permission = Objects.requireNonNull(permission);
        this.authoringWorld = authoringWorld;
    }

    @Override
    public void prepare(Request request, BooleanSupplier cancelled, Consumer<Preparation> completed) {
        try {
            if (!projectWorld.current(permission)) throw new IllegalStateException("Authoring project changed");
            if (request.source() != null) projectWorld.requireRuntimeSource(permission, request.source());
        } catch (RuntimeException failure) {
            completed.accept(new Preparation(null, message(failure, "install_failed"), failure));
            return;
        }
        runIo(() -> {
            projectWorld.verify(permission);
            return true;
        }, ignored -> prepareVerified(request, cancelled, completed),
                failure -> completed.accept(new Preparation(null, message(failure, "install_failed"), failure)));
    }

    private void prepareVerified(Request request, BooleanSupplier cancelled, Consumer<Preparation> completed) {
        Consumer<Throwable> fail = failure -> completed.accept(new Preparation(null, message(failure, "install_failed"), failure));
        if (request.target().target() != this || !request.target().worldId().equals(binding.worldId()) || !request.target().dimensions().equals(binding.dimensions())) {
            fail.accept(new IllegalArgumentException("foreign world selection"));
            return;
        }
        if (request.recovery() != null) {
            onServer(() -> {
                verify(cancelled);
                var controller = core.runtimeInstallations();
                var expected = request.recovery();
                if (!expected.equals(controller.records().get(expected.plan().key()))) throw new IOException("Installation record changed");
                // Recovery inspects the retained input; persistent ownership is expected here.
                // Actual construction remains guarded by resume after explicit confirmation.
                core.requireRuntimeMapNameUnoccupied(expected.plan().key().type(), expected.plan().key().name());
                var plan = request.previous() ? Objects.requireNonNull(expected.previous()).plan() : expected.plan();
                var factory = factory(plan.factoryId());
                InstallationScratch scratch = InstallationScratch.create(identities());
                InstallationBundle<?> bundle = null;
                try {
                    bundle = request.previous() ? controller.reopenPreviousBundle(plan.key(), registry, factory.preparationType(), scratch.archive(), scratch.projectDirectory(), () -> cancelled.getAsBoolean() || disconnected.getAsBoolean()) : controller.reopenBundle(plan.key(), registry, factory.preparationType(), scratch.archive(), scratch.projectDirectory(), () -> cancelled.getAsBoolean() || disconnected.getAsBoolean());
                    verify(cancelled);
                    return new Fixed(bundle, plan, expected, request.previous(), scratch);
                } catch (Throwable failure) {
                    try {
                        closePreparation(bundle, scratch);
                    } catch (Throwable cleanup) {
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }, fixed -> completed.accept(new Preparation(fixed, null, null)), fail);
            return;
        }
        // Storage validation runs on the I/O executor. Native adapter activation remains on the server owner.
        runIo(() -> {
            projectWorld.verify(permission);
            var source = Objects.requireNonNull(request.source());
            if (source.registry() != registry) throw new IllegalArgumentException("foreign source registry");
            var captured = source.access().captureSnapshot();
            if (!source.saved().equals(captured.saved())) throw new IOException("Saved source changed before capture");
            return source.access().readValidated(captured, registry, cancelled);
        }, loaded -> onServer(() -> {
            verify(cancelled);
            var source = request.source();
            var profile = Objects.requireNonNull(request.profile());
            var controller = core.runtimeInstallations();
            var replacement = request.replacement() == null ? null : controller.captureInstalled(request.replacement());
            if (replacement != null) controller.requireReplaceable(replacement);
            var factory = profileFactory(profile);
            var prepared = source.access().prepareValidatedRuntime(loaded, registry, source.documentId(), profile.adapterId(), factory.preparationType(), cancelled);
            InstallationScratch scratch = InstallationScratch.create(identities());
            InstallationBundle<?> bundle = null;
            try {
                bundle = InstallationBundle.capture(source.access(), prepared, registry, scratch.archive(), scratch.projectDirectory(), identities(), cancelled);
                verify(cancelled);
                InstallationPlan plan = bundle.plan(UUID.randomUUID(), new InstallationPlan.Key(profile.gameType(), request.name()),
                        profile.factoryId(), "1.20.1", request.target().dimensions());
                if (replacement != null) controller.requireReplaceable(replacement);
                return new Fixed(bundle, plan, request.replacement(), false, scratch, replacement);
            } catch (Throwable failure) {
                try {
                    closePreparation(bundle, scratch);
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }, fixed -> completed.accept(new Preparation(fixed, null, null)), fail), fail);
    }

    private NativeMapFactory<?> factory(EditorId id) {
        if (!registry.defines(NativeMapFactories.KIND)) throw new IllegalStateException("Native factory unavailable");
        return registry.find(NativeMapFactories.KIND, id).orElseThrow(() -> new IllegalStateException("Native factory unavailable"));
    }

    private NativeMapFactory<?> profileFactory(RuntimeInstallationProfile profile) {
        if (!registry.defines(RuntimeInstallationProfile.KIND)) throw new IllegalArgumentException("Profile unavailable");
        var registered = registry.entries(RuntimeInstallationProfile.KIND).entrySet().stream().filter(e -> e.getValue().equals(profile)).findFirst().orElseThrow();
        var profileMetadata = registry.metadata().contributions().get(new ContributionKey(RuntimeInstallationProfile.KIND.id(), registered.getKey()));
        var factoryMetadata = registry.metadata().contributions().get(new ContributionKey(NativeMapFactories.KIND.id(), profile.factoryId()));
        var adapter = registry.find(SavedRuntimeAdapters.KIND, profile.adapterId()).orElseThrow();
        var factory = factory(profile.factoryId());
        if (!profileMetadata.owner().equals(factoryMetadata.owner()) || !factory.adapterId().equals(profile.adapterId()) || factory.preparationType() != adapter.resultType() || !profile.documentType().equals(adapter.documentType()))
            throw new IllegalArgumentException("Installation profile does not match registered factory");
        return factory;
    }

    @Override
    public void submit(Prepared prepared, UUID requestId, BooleanSupplier cancelled, Consumer<Result> completed) {
        if (!(prepared instanceof Fixed fixed) || fixed.source != this) {
            completed.accept(new Result(State.FAILED, text("install_failed"), new IllegalArgumentException("foreign preparation")));
            return;
        }
        if (!fixed.consumed.compareAndSet(false, true)) {
            completed.accept(new Result(State.FAILED, text("install_failed"), new IllegalStateException("preparation already submitted")));
            return;
        }
        Consumer<Throwable> rejected = failure -> completed.accept(outcome(failure, false));
        onServer(() -> {
            verify(cancelled);
            return core.getWorldBindings().check(binding);
        }, check -> runIo(() -> checkTarget(check, cancelled), checked -> onServer(() -> {
            verify(cancelled);
            try {
                if (fixed.replacement != null) core.runtimeInstallations().replace(core, registry, fixed.bundle, fixed.plan, fixed.replacement, binding, checked, requestId, () -> cancelled.getAsBoolean() || disconnected.getAsBoolean());
                else if (fixed.expected == null) core.runtimeInstallations().install(core, registry, fixed.bundle, fixed.plan, binding, checked, requestId, () -> cancelled.getAsBoolean() || disconnected.getAsBoolean());
                else if (fixed.previous) core.runtimeInstallations().resumePrevious(core, registry, fixed.bundle, fixed.expected, binding, checked, requestId, () -> cancelled.getAsBoolean() || disconnected.getAsBoolean());
                else core.runtimeInstallations().resume(core, registry, fixed.bundle, fixed.expected, binding, checked, requestId, () -> cancelled.getAsBoolean() || disconnected.getAsBoolean());
                return new Result(State.INSTALLED, text("install_installed"), null);
            } catch (Throwable failure) {
                return outcome(failure, true);
            }
        }, completed, rejected), rejected), rejected);
    }

    private Result outcome(Throwable failure, boolean enteredInstallation) {
        reportFailure("submit", failure);
        if (containsUnknown(failure, new HashSet<>())) return new Result(State.UNKNOWN, text("install_unknown"), failure);
        if (enteredInstallation && core.runtimeInstallations().status() == MinecraftRuntimeInstallations.Status.RECOVERY_REQUIRED)
            return new Result(State.RECOVERY_REQUIRED, text("install_recovery_required"), failure);
        boolean cancelled = failure instanceof CancellationException || failure instanceof net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
        return new Result(cancelled ? State.CANCELLED : State.FAILED, message(failure, cancelled ? "install_cancelled" : "install_failed"), failure);
    }

    private static boolean containsUnknown(Throwable failure, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) return false;
        if (failure instanceof ProjectStorageException storage && storage.code() == ProjectStorageException.Code.RESULT_UNKNOWN) return true;
        if (containsUnknown(failure.getCause(), seen)) return true;
        for (Throwable suppressed : failure.getSuppressed()) if (containsUnknown(suppressed, seen)) return true;
        return false;
    }

    @Override
    public void records(Consumer<List<RecordView>> completed, Consumer<Throwable> failed) {
        onServer(() -> {
            verify(() -> false);
            List<RecordView> result = new ArrayList<>();
            var controller = core.runtimeInstallations();
            controller.records().forEach((key, record) -> {
                boolean online = core.getMapByTypeWithName(key.type(), key.name()).isPresent();
                ReplacementBlocker blocker = online ? ReplacementBlocker.RUNTIME_UNAVAILABLE : ReplacementBlocker.OFFLINE;
                if (online && record.state() != LocalInstallationIndex.State.ACTIVE) blocker = ReplacementBlocker.RECORD_NOT_ACTIVE;
                else if (online) try {
                    blocker = switch (controller.replacementStatus(controller.captureInstalled(record))) {
                        case READY -> ReplacementBlocker.NONE;
                        case BUSY -> ReplacementBlocker.HOST_BUSY;
                        case RUNNING -> ReplacementBlocker.RUNNING;
                        case PARTICIPANTS -> ReplacementBlocker.PARTICIPANTS;
                        case RESET_INCOMPLETE -> ReplacementBlocker.RESET_INCOMPLETE;
                    };
                } catch (RuntimeException unavailable) {
                    blocker = ReplacementBlocker.RUNTIME_UNAVAILABLE;
                }
                result.add(new RecordView(record, online, blocker));
            });
            verify(() -> false);
            return List.copyOf(result);
        }, completed, failed);
    }

    @Override
    public void lookup(UUID requestId, Consumer<TextContent> completed, Consumer<Throwable> failed) {
        onServer(() -> {
            requireCore();
            var result = core.runtimeInstallations().lookupRequest(requestId);
            return text(result.status() == LocalProjectStore.RequestStatus.UNKNOWN ? "install_receipt_unknown" : "install_receipt_published");
        }, completed, failed);
    }

    @Override
    public RuntimeTestWorkbench.Target tests() {
        return this;
    }

    @Override
    public void prepare(InstallationPlan plan, BooleanSupplier cancelled, Consumer<RuntimeTestWorkbench.Preparation> completed) {
        Consumer<Throwable> failed = failure -> completed.accept(new RuntimeTestWorkbench.Preparation(null, List.of(), testMessage(failure, "failed"), failure));
        onServer(() -> {
            verify(cancelled);
            var controller = core.runtimeInstallations();
            var record = controller.records().get(plan.key());
            if (record == null || !record.plan().equals(plan)) throw new IllegalStateException("Fixed installation changed");
            return controller.captureInstalled(record);
        }, installed -> onServer(() -> {
            verify(cancelled);
            return core.getWorldBindings().check(binding);
        }, check -> runIo(() -> checkTarget(check, cancelled), checked -> onServer(() -> {
            verify(cancelled);
            var problems = core.runtimeInstallations().tests().inspect(installed, registry, binding, checked);
            verify(cancelled);
            return new RuntimeTestWorkbench.Preparation(new TestFixed(installed), problems, null, null);
        }, completed, failed), failed), failed), failed);
    }

    @Override
    public void start(RuntimeTestWorkbench.Prepared prepared, BooleanSupplier cancelled, Consumer<RuntimeTestWorkbench.Outcome> completed) {
        if (!(prepared instanceof TestFixed fixed) || fixed.source != this || !fixed.consumed.compareAndSet(false, true)) {
            completed.accept(new RuntimeTestWorkbench.Outcome(RuntimeTestWorkbench.State.FAILED, null, RuntimeTestWorkbench.text("stale"), null));
            return;
        }
        Consumer<Throwable> failed = failure -> completed.accept(testFailure(failure, null));
        onServer(() -> {
            verify(cancelled);
            return core.getWorldBindings().check(binding);
        }, check -> runIo(() -> checkTarget(check, cancelled), checked -> onServer(() -> {
            verify(cancelled);
            var attempt = core.runtimeInstallations().tests().newStartAttempt();
            try {
                var session = core.runtimeInstallations().tests().start(attempt, fixed.installed, registry, binding, checked, () -> cancelled.getAsBoolean() || disconnected.getAsBoolean());
                return testOutcome(new TestRunning(session));
            } catch (RuntimeException | Error failure) {
                var session = attempt.retainedSession();
                return testFailure(failure, session == null ? null : new TestRunning(session));
            }
        }, completed, failed), failed), failed);
    }

    @Override
    public void stop(RuntimeTestWorkbench.Running running, Consumer<RuntimeTestWorkbench.Outcome> completed) {
        if (!(running instanceof TestRunning selected) || selected.source != this) {
            completed.accept(testFailure(new IllegalArgumentException("foreign runtime test"), null));
            return;
        }
        onServer(() -> {
            requireCore();
            try {
                core.runtimeInstallations().tests().stop(selected.session);
                return testOutcome(selected);
            } catch (RuntimeException | Error failure) {
                return testFailure(failure, selected);
            }
        }, completed, failure -> completed.accept(testFailure(failure, server.isStopped() ? null : selected)));
    }

    @Override
    public void status(RuntimeTestWorkbench.Running running, Consumer<RuntimeTestWorkbench.Outcome> completed) {
        if (!(running instanceof TestRunning selected) || selected.source != this) {
            completed.accept(testFailure(new IllegalArgumentException("foreign runtime test"), null));
            return;
        }
        onServer(() -> {
            requireCore();
            return testOutcome(selected);
        }, completed,
                failure -> completed.accept(server.isStopped() ? new RuntimeTestWorkbench.Outcome(RuntimeTestWorkbench.State.INACTIVE, null, RuntimeTestWorkbench.text("inactive"), failure) : testFailure(failure, selected)));
    }

    private RuntimeTestWorkbench.Outcome testOutcome(TestRunning running) {
        var state = running.session.state();
        var result = switch (state) {
            case STARTING -> RuntimeTestWorkbench.State.STARTING;
            case RUNNING -> RuntimeTestWorkbench.State.RUNNING;
            case STOPPED -> RuntimeTestWorkbench.State.STOPPED;
            case FAILED -> RuntimeTestWorkbench.State.FAILED;
            case INACTIVE -> RuntimeTestWorkbench.State.INACTIVE;
        };
        String reason = switch (state) {
            case STARTING -> "starting";
            case RUNNING -> "running";
            case STOPPED -> "stopped";
            case FAILED -> "stop_failed";
            case INACTIVE -> "inactive";
        };
        return new RuntimeTestWorkbench.Outcome(result, state == MinecraftRuntimeTests.State.STOPPED || state == MinecraftRuntimeTests.State.INACTIVE ? null : running,
                RuntimeTestWorkbench.text(reason), running.session.diagnostic());
    }

    private RuntimeTestWorkbench.Outcome testFailure(Throwable failure, TestRunning running) {
        reportFailure("runtime test", failure);
        return new RuntimeTestWorkbench.Outcome(RuntimeTestWorkbench.State.FAILED, running, testMessage(failure, "failed"), failure);
    }

    private static TextContent testMessage(Throwable failure, String fallback) {
        if (failure instanceof DocumentCodecException codec && !codec.problems().isEmpty()) return codec.problems().get(0).message();
        if (failure instanceof RuntimeMapLeaseException lease) return lease.problem().message();
        return RuntimeTestWorkbench.text(fallback);
    }

    private final class TestFixed implements RuntimeTestWorkbench.Prepared {

        final MinecraftWorkbenchRuntimeInstallations source = MinecraftWorkbenchRuntimeInstallations.this;
        final MinecraftRuntimeInstallations.InstalledRuntime installed;
        final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();

        TestFixed(MinecraftRuntimeInstallations.InstalledRuntime installed) {
            this.installed = installed;
        }

        public InstallationPlan plan() {
            return installed.plan();
        }
    }

    private final class TestRunning implements RuntimeTestWorkbench.Running {

        final MinecraftWorkbenchRuntimeInstallations source = MinecraftWorkbenchRuntimeInstallations.this;
        final MinecraftRuntimeTests.Session session;

        TestRunning(MinecraftRuntimeTests.Session session) {
            this.session = session;
        }

        public InstallationPlan plan() {
            return session.plan();
        }
    }

    private void requireCore() {
        if (!server.isSameThread() || server.isStopped() || FPSMCore.getInstance() != core || !core.isActive()) throw new IllegalStateException("Original installation server is inactive");
    }

    private void verify(BooleanSupplier cancelled) {
        requireCore();
        if (cancelled.getAsBoolean() || disconnected.getAsBoolean()) throw new CancellationException();
        if (authoringWorld != null) core.runtimeInstallations().requirePhysicalWorld(authoringWorld.root(), authoringWorld.identity());
        core.getWorldBindings().verify(binding);
    }

    private WorldBindingCheckResult checkTarget(WorldBindingCheck check, BooleanSupplier cancelled) throws IOException {
        projectWorld.verify(permission);
        var checked = check.verify(() -> cancelled.getAsBoolean() || disconnected.getAsBoolean());
        projectWorld.verify(permission);
        return checked;
    }

    @Override
    public void poll() {
        if (server.isStopped()) for (ServerCall<?> call : calls) call.reject(new IllegalStateException("Original server stopped before this operation started"));
    }

    private <T> void onServer(Callable<T> operation, Consumer<T> completed, Consumer<Throwable> failed) {
        ServerCall<T> call = new ServerCall<>(operation, completed, failed);
        calls.add(call);
        if (server.isStopped()) {
            call.reject(new IllegalStateException("Original server stopped"));
            return;
        }
        try {
            server.execute(call::run);
        } catch (RuntimeException failure) {
            call.reject(failure);
        }
    }

    private <T> void runIo(Callable<T> operation, Consumer<T> completed, Consumer<Throwable> failed) {
        try {
            io.execute(() -> {
                T result;
                try {
                    result = operation.call();
                } catch (Throwable failure) {
                    reportFailure("read source", failure);
                    failed.accept(failure);
                    return;
                }
                completed.accept(result);
            });
        } catch (RuntimeException failure) {
            reportFailure("schedule source read", failure);
            failed.accept(failure);
        }
    }

    private void reportFailure(String stage, Throwable failure) {
        if (failure instanceof CancellationException || failure instanceof net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException) return;
        FPSMatch.LOGGER.warn("Editor runtime installation {} failed (world {})", stage, binding.worldId(), failure);
    }

    private final class ServerCall<T> {

        final AtomicInteger phase = new AtomicInteger();
        final Callable<T> operation;
        final Consumer<T> completed;
        final Consumer<Throwable> failed;

        ServerCall(Callable<T> operation, Consumer<T> completed, Consumer<Throwable> failed) {
            this.operation = operation;
            this.completed = completed;
            this.failed = failed;
        }

        void run() {
            if (!phase.compareAndSet(0, 1)) return;
            T value;
            try {
                value = operation.call();
            } catch (Throwable failure) {
                phase.set(2);
                calls.remove(this);
                reportFailure("server operation", failure);
                failed.accept(failure);
                return;
            }
            phase.set(2);
            calls.remove(this);
            completed.accept(value);
        }

        void reject(Throwable failure) {
            if (phase.compareAndSet(0, 2)) {
                calls.remove(this);
                reportFailure("schedule server operation", failure);
                failed.accept(failure);
            }
        }
    }

    private final class Fixed implements Prepared {

        final MinecraftWorkbenchRuntimeInstallations source = MinecraftWorkbenchRuntimeInstallations.this;
        final InstallationBundle<?> bundle;
        final InstallationPlan plan;
        final LocalInstallationIndex.Record expected;
        final boolean previous;
        final MinecraftRuntimeInstallations.InstalledRuntime replacement;
        final InstallationScratch scratch;
        final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();

        Fixed(InstallationBundle<?> bundle, InstallationPlan plan, LocalInstallationIndex.Record expected, boolean previous, InstallationScratch scratch) {
            this(bundle, plan, expected, previous, scratch, null);
        }

        Fixed(InstallationBundle<?> bundle, InstallationPlan plan, LocalInstallationIndex.Record expected, boolean previous, InstallationScratch scratch, MinecraftRuntimeInstallations.InstalledRuntime replacement) {
            this.bundle = bundle;
            this.plan = plan;
            this.expected = expected;
            this.previous = previous;
            this.scratch = scratch;
            this.replacement = replacement;
        }

        public InstallationPlan plan() {
            return plan;
        }

        public InstallationPlan replacedPlan() {
            return replacement == null ? null : replacement.plan();
        }

        public void release(Consumer<Throwable> completed) {
            Runnable close = () -> {
                Throwable failure = null;
                try {
                    closePreparation(bundle, scratch);
                } catch (Throwable thrown) {
                    failure = thrown;
                }
                completed.accept(failure);
            };
            try {
                io.execute(close);
            } catch (RuntimeException rejection) {
                close.run();
            }
        }
    }

    private static void closePreparation(InstallationBundle<?> bundle, InstallationScratch scratch) throws IOException {
        try (scratch) {
            if (bundle != null) bundle.close();
        }
    }

    private static FileIdentityProvider identities() {
        return "\\".equals(FileSystems.getDefault().getSeparator()) ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
    }

    private static TextContent text(String key) {
        return RuntimeInstallationWorkbench.text(key);
    }

    private static TextContent message(Throwable failure, String fallback) {
        if (failure instanceof SessionRejection rejected && !rejected.problems().isEmpty()) return rejected.problems().get(0).message();
        if (failure instanceof DocumentCodecException codec && !codec.problems().isEmpty()) return codec.problems().get(0).message();
        if (failure instanceof RuntimeMapLeaseException lease) return lease.problem().message();
        return text(fallback);
    }
}
