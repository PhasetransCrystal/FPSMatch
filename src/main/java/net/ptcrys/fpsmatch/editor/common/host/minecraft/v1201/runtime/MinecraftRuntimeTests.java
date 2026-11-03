package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime;

import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeInstallations.InstalledRuntime;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Server-owner coordinator; stopping affects only the captured native instance, never a draft. */
public final class MinecraftRuntimeTests {

    public enum State {
        STARTING,
        RUNNING,
        STOPPED,
        FAILED,
        INACTIVE
    }

    private final MinecraftRuntimeInstallations installations;
    private Session active;
    private boolean operating;

    MinecraftRuntimeTests(MinecraftRuntimeInstallations installations) {
        this.installations = installations;
    }

    /** Pure domain preflight inside a current player/world scope. No join, start or reset. */
    public List<Problem> inspect(InstalledRuntime installed, ExtensionRegistry registry,
                                 WorldBinding binding, WorldBindingCheckResult check) {
        BaseMap map = installations.requireInstalled(installed);
        requireAvailable();
        NativeMapTestDriver<?> driver = driver(registry, installed, map);
        requireBinding(installed, binding);
        operating = true;
        try {
            return installed.core().withConfirmedWorld(binding, check, installed.plan().dimensions().keySet(), target -> {
                if (target.server() != installed.core().getServer()) throw failure("stale");
                installations.requireInstalled(installed);
                ServerPlayer actor = target.server().getPlayerList().getPlayer(binding.actorId());
                List<Problem> result = preflight(installed, map, actor, driver);
                target.verify();
                installations.requireInstalled(installed);
                return result;
            });
        } finally {
            operating = false;
        }
    }

    /** A single caller's start receipt; it never grants access to another caller's active test. */
    public StartAttempt newStartAttempt() {
        installations.requireTestOwner();
        return new StartAttempt(this);
    }

    /** Confirmation re-runs preflight and every identity check before the first native side effect. */
    public Session start(StartAttempt attempt, InstalledRuntime installed, ExtensionRegistry registry,
                         WorldBinding binding, WorldBindingCheckResult check, BooleanSupplier cancelled) {
        installations.requireTestOwner();
        if (attempt == null || attempt.owner != this || attempt.consumed) throw failure("stale");
        attempt.consumed = true;
        Objects.requireNonNull(cancelled);
        BaseMap map = installations.requireInstalled(installed);
        requireAvailable();
        NativeMapTestDriver<?> driver = driver(registry, installed, map);
        requireBinding(installed, binding);
        long resetGeneration = map.resetGeneration();
        Session session = new Session(this, installed, driver, binding, resetGeneration);
        attempt.session = session;
        operating = true;
        try {
            installed.core().withConfirmedWorld(binding, check, installed.plan().dimensions().keySet(), target -> {
                checkCancelled(cancelled);
                installations.requireInstalled(installed);
                ServerPlayer actor = target.server().getPlayerList().getPlayer(binding.actorId());
                List<Problem> problems = preflight(installed, map, actor, driver);
                if (problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR)) throw new DocumentCodecException(problems);
                target.verify();
                installations.requireInstalled(installed);
                checkCancelled(cancelled);
                target.verify();
                installations.requireInstalled(installed);
                List<Problem> finalReadiness = preflight(installed, map, actor, driver);
                if (finalReadiness.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR))
                    throw new DocumentCodecException(finalReadiness);
                target.verify();
                installations.requireInstalled(installed);
                active = session; // Publish ownership before join/start can call back into the host.
                session.actor = actor;
                if (!driver.admit(map, actor)) throw failure("start_rejected");
                checkCancelled(cancelled);
                target.verify();
                installations.requireInstalled(installed);
                if (!map.checkGameHasPlayer(actor)) throw failure("start_rejected");
                requireAdmittedReady(installed, map, actor, driver, false);
                target.verify();
                installations.requireInstalled(installed);
                boolean[] readyInvoked = { false };
                boolean[] deliveryInvoked = { false };
                if (!driver.begin(map, actor, () -> {
                    if (readyInvoked[0]) throw failure("start_rejected");
                    readyInvoked[0] = true;
                    checkCancelled(cancelled);
                    target.verify();
                    installations.requireInstalled(installed);
                    requireAdmittedReady(installed, map, actor, driver, false);
                    target.verify();
                    installations.requireInstalled(installed);
                }, () -> {
                    if (!readyInvoked[0] || deliveryInvoked[0]) throw failure("start_rejected");
                    deliveryInvoked[0] = true;
                    checkCancelled(cancelled);
                    target.verify();
                    installations.requireInstalled(installed);
                    requireAdmittedReady(installed, map, actor, driver, true);
                    target.verify();
                    installations.requireInstalled(installed);
                }) || !readyInvoked[0] || !deliveryInvoked[0]) throw failure("start_rejected");
                checkCancelled(cancelled);
                target.verify();
                installations.requireInstalled(installed);
                if (!map.isStart() || !map.checkGameHasPlayer(actor)) throw failure("start_rejected");
                return null;
            });
            session.state = State.RUNNING;
            return session;
        } catch (RuntimeException | Error failure) {
            session.diagnostic = failure;
            if (active == session) {
                // start() can already have attempted reset; never replay partially failed event effects.
                if (map.resetGeneration() != resetGeneration) {
                    session.state = State.FAILED;
                } else end(session, failure);
            }
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Idempotent terminal observation. Cleanup needs the original host, not the player's old binding. */
    public State stop(Session session) {
        requireSession(session);
        if (session.state == State.STOPPED || session.state == State.INACTIVE) return session.state;
        if (operating || session.state == State.FAILED) throw failure("busy");
        if (active != session) throw failure("stale");
        operating = true;
        try {
            end(session, null);
            return session.state;
        } finally {
            operating = false;
        }
    }

    /** Called by the exact core, including while the workbench is closed for actual play. */
    public void tick() {
        installations.requireTestOwner();
        Session session = active;
        if (session == null || operating || session.state != State.RUNNING) return;
        try {
            BaseMap map = installations.requireLiveInstalled(session.installed);
            session.installed.core().getWorldBindings().verify(session.binding);
            if (map.resetGeneration() == session.resetGeneration && map.isStart() && map.checkGameHasPlayer(session.actor)) return;
        } catch (RuntimeException | Error failure) {
            session.diagnostic = failure;
        }
        operating = true;
        try {
            end(session, null);
        } catch (RuntimeException | Error failure) {
            // Retain the failed token. A replacement or another test cannot mask failed cleanup.
            session.diagnostic = failure;
        } finally {
            operating = false;
        }
    }

    void requireInstallationIdle() {
        installations.requireTestOwner();
        if (active != null || operating) throw failure("busy");
    }

    void closed() {
        if (active != null) {
            active.state = State.INACTIVE;
            active = null;
        }
    }

    private void end(Session session, Throwable primary) {
        try {
            BaseMap map = installations.requireLiveInstalled(session.installed);
            // A natural game end may already have run reset before this tick/queued stop.
            // Observe that exact reset's result; replaying it can repeat world/player effects.
            if (map.resetGeneration() == session.resetGeneration) session.driver.end(map);
            else if (!map.resetCompleted(map.resetGeneration())) throw failure("stop_failed");
            installations.requireLiveInstalled(session.installed);
            if (map.resetObservation(map.resetGeneration()) == BaseMap.ResetObservation.INCOMPLETE) throw failure("stop_failed");
            if (map.isStart() || !map.getMapTeams().getJoinedPlayersWithSpec().isEmpty()) throw failure("stop_failed");
            session.state = primary == null ? State.STOPPED : State.FAILED;
            active = null;
        } catch (RuntimeException | Error cleanup) {
            session.state = State.FAILED;
            session.diagnostic = primary == null ? cleanup : primary;
            if (primary == null) throw cleanup;
            if (primary != cleanup) primary.addSuppressed(cleanup);
        }
    }

    private void requireAvailable() {
        if (active != null || operating) throw failure("busy");
    }

    private void requireSession(Session session) {
        installations.requireTestOwner();
        if (session == null || session.owner != this) throw failure("stale");
    }

    private static void requireBinding(InstalledRuntime installed, WorldBinding binding) {
        Objects.requireNonNull(binding);
        if (!installed.worldId().equals(binding.worldId()) || !installed.plan().dimensions().equals(binding.dimensions()))
            throw failure("stale");
    }

    private static NativeMapTestDriver<?> driver(ExtensionRegistry registry, InstalledRuntime installed, BaseMap map) {
        Objects.requireNonNull(registry);
        if (!installed.preparedWith(registry)) throw failure("unsupported");
        EditorId factoryId = installed.plan().factoryId();
        var driver = registry.defines(NativeMapTestDriver.KIND) ? registry.find(NativeMapTestDriver.KIND, factoryId).orElse(null) : null;
        if (driver == null || !driver.mapType().isInstance(map)) throw failure("unsupported");
        var metadata = registry.metadata().contributions();
        var factory = metadata.get(new ContributionKey(NativeMapFactories.KIND.id(), factoryId));
        var test = metadata.get(new ContributionKey(NativeMapTestDriver.KIND.id(), factoryId));
        if (factory == null || test == null || !factory.owner().equals(test.owner())) throw failure("unsupported");
        return driver;
    }

    private List<Problem> preflight(InstalledRuntime installed, BaseMap map, ServerPlayer actor, NativeMapTestDriver<?> driver) {
        List<Problem> host = hostPreflight(installed, map, actor);
        if (!host.isEmpty()) return host;
        List<Problem> result = driver.inspect(map, actor);
        List<Problem> after = hostPreflight(installed, map, actor);
        return after.isEmpty() ? result : after;
    }

    private List<Problem> hostPreflight(InstalledRuntime installed, BaseMap map, ServerPlayer actor) {
        if (actor == null || map.isStart() || !map.getMapTeams().getJoinedPlayersWithSpec().isEmpty())
            return List.of(problem("occupied"));
        for (var maps : installed.core().getAllMaps().values()) for (BaseMap other : maps)
            if (other.checkGameHasPlayer(actor) || other.checkSpecHasPlayer(actor)) return List.of(problem("participant_busy"));
        return List.of();
    }

    private void requireAdmittedReady(InstalledRuntime installed, BaseMap map, ServerPlayer actor,
                                      NativeMapTestDriver<?> driver, boolean expectedStarted) {
        if (map.isStart() != expectedStarted || !map.checkGameHasPlayer(actor) || map.getMapTeams().getJoinedPlayersWithSpec().size() != 1)
            throw failure("start_rejected");
        List<Problem> problems = driver.inspect(map, actor);
        installations.requireInstalled(installed);
        if (!map.checkGameHasPlayer(actor) || map.isStart() != expectedStarted || map.getMapTeams().getJoinedPlayersWithSpec().size() != 1)
            throw failure("start_rejected");
        if (problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR))
            throw new DocumentCodecException(problems);
    }

    private static Problem problem(String reason) {
        return new Problem(Problem.Severity.ERROR, EditorId.parse("editor:runtime_test/" + reason), "runtime_test",
                new TextContent.Message("fpsmatch.editor.runtime_test." + reason, List.of()));
    }

    private static DocumentCodecException failure(String reason) {
        return new DocumentCodecException(List.of(problem(reason)));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Runtime test cancelled");
    }

    public static final class StartAttempt {

        private final MinecraftRuntimeTests owner;
        private boolean consumed;
        private Session session;

        private StartAttempt(MinecraftRuntimeTests owner) {
            this.owner = owner;
        }

        /** Only this invocation's retained cleanup failure can be handed back to its caller. */
        public Session retainedSession() {
            owner.installations.requireTestOwner();
            return session != null && owner.active == session && session.state == State.FAILED ? session : null;
        }
    }

    public static final class Session {

        private final MinecraftRuntimeTests owner;
        private final InstalledRuntime installed;
        private final NativeMapTestDriver<?> driver;
        private final WorldBinding binding;
        private final long resetGeneration;
        private ServerPlayer actor;
        private State state = State.STARTING;
        private Throwable diagnostic;

        private Session(MinecraftRuntimeTests owner, InstalledRuntime installed, NativeMapTestDriver<?> driver, WorldBinding binding, long resetGeneration) {
            this.owner = owner;
            this.installed = installed;
            this.driver = driver;
            this.binding = binding;
            this.resetGeneration = resetGeneration;
        }

        public InstallationPlan plan() {
            return installed.plan();
        }

        public State state() {
            owner.requireSession(this);
            return state;
        }

        public Throwable diagnostic() {
            owner.requireSession(this);
            return diagnostic;
        }
    }
}
