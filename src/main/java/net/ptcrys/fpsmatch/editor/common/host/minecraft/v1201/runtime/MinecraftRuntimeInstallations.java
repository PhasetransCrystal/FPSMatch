package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.RuntimeMapLease;
import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world.MinecraftWorldBindings;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world.MinecraftWorldBindings.ConfirmedTarget;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationBundle;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.installation.LocalInstallationIndex;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProviders;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Startup ownership gate. Loading records never publishes maps or reuses a player's prior binding. */
public final class MinecraftRuntimeInstallations implements AutoCloseable {

    public enum Status {
        RECOVERING,
        LEGACY,
        RESERVED,
        RECOVERY_REQUIRED,
        CLOSED
    }

    public enum ReplacementStatus {
        READY,
        BUSY,
        RUNNING,
        PARTICIPANTS,
        RESET_INCOMPLETE
    }

    private final MinecraftServer server;
    private final Path root;
    private final FileIdentityProvider identities;
    private LocalInstallationIndex index;
    private Status status = Status.RECOVERING;
    private Throwable recoveryFailure;
    private FileNodeIdentity rootIdentity;
    private FileNodeIdentity editorIdentity;
    private Attempt attempt;
    private boolean reopening;
    private boolean registering;
    private final Map<InstallationPlan.Key, InstalledRuntime> installed = new HashMap<>();
    private final MinecraftRuntimeTests tests = new MinecraftRuntimeTests(this);

    /** Current-player first registration; success authorizes only the new editor directory baseline. */
    public UUID initializeIdentity(FPSMCore core, MinecraftWorldBindings backend, UUID actorId,
                                   BooleanSupplier cancelled) throws IOException {
        requireIdle();
        if (core == null || core.runtimeInstallations() != this || core.getServer() != server || !core.isActive() || backend == null || backend.bindings() != core.getWorldBindings()) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(cancelled, "cancelled");
        requireConstructionAvailable();
        if (status != Status.LEGACY || index != null || identityPresent()) {
            throw new ProjectStorageException(ProjectStorageException.Code.CONFLICT, "World identity or installation ownership already exists");
        }
        registering = true;
        try {
            var registered = backend.initializeIdentity(actorId, identities, () -> {
                try {
                    verifyPhysicalBaseline();
                    requireOwnershipAbsent();
                    if (editorIdentity == null) {
                        Files.createDirectory(root.resolve(".editor-platform"));
                        FileNodeIdentity created = editorIdentity();
                        if (created == null) throw new IOException("New editor directory is absent");
                        editorIdentity = created;
                    }
                    verifyPhysicalBaseline();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }, () -> {
                try {
                    requireRegistrationCurrent(core);
                    verifyPhysicalBaseline();
                    requireOwnershipAbsent();
                    boolean stop = cancelled.getAsBoolean();
                    requireRegistrationCurrent(core);
                    verifyPhysicalBaseline();
                    requireOwnershipAbsent();
                    return stop;
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            requireRegistrationCurrent(core);
            verifyPhysicalBaseline();
            requireOwnershipAbsent();
            if (!registered.root().equals(root)) throw new IOException("Registered world root differs");
            WorldIdentities.verify(registered, () -> false);
            return registered.worldId();
        } catch (IOException | RuntimeException | Error failure) {
            if (status != Status.CLOSED) {
                boolean unsafe = false;
                try {
                    verifyPhysicalBaseline();
                    requireOwnershipAbsent();
                    unsafe = identityPresent();
                } catch (IOException | RuntimeException | Error observation) {
                    unsafe = true;
                    if (observation != failure) failure.addSuppressed(observation);
                }
                if (unsafe) {
                    protect(failure);
                    throw recoveryRequired();
                }
            }
            throw failure;
        } finally {
            registering = false;
        }
    }

    private void requireRegistrationCurrent(FPSMCore core) {
        if (status != Status.LEGACY || index != null || core.runtimeInstallations() != this || !core.isActive()) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
        }
    }

    private boolean identityPresent() throws IOException {
        return nodePresent(root.resolve(".editor-platform/world.identity"));
    }

    private void requireOwnershipAbsent() throws IOException {
        if (nodePresent(root.resolve(".editor-platform/installations.owner")) || nodePresent(root.resolve(".editor-platform/installations"))) {
            throw new IOException("Installation ownership appeared during identity registration");
        }
    }

    private static boolean nodePresent(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    /** Revalidate the retained archive without restoring player authority or granting construction. */
    public <R> InstallationBundle<R> reopenBundle(InstallationPlan.Key key, ExtensionRegistry registry,
                                                  Class<R> resultType, Path archive, Path projectDirectory, BooleanSupplier cancelled) throws IOException {
        return reopenBundle(key, registry, resultType, archive, projectDirectory, cancelled, false);
    }

    /** Inspect the retained old input without selecting it or restoring player authority. */
    public <R> InstallationBundle<R> reopenPreviousBundle(InstallationPlan.Key key, ExtensionRegistry registry,
                                                          Class<R> resultType, Path archive, Path projectDirectory, BooleanSupplier cancelled) throws IOException {
        return reopenBundle(key, registry, resultType, archive, projectDirectory, cancelled, true);
    }

    private <R> InstallationBundle<R> reopenBundle(InstallationPlan.Key key, ExtensionRegistry registry,
                                                   Class<R> resultType, Path archive, Path projectDirectory, BooleanSupplier cancelled,
                                                   boolean previous) throws IOException {
        requireIdle();
        requireConstructionAvailable();
        if (index == null) throw new ProjectStorageException(ProjectStorageException.Code.CONFLICT, "No installation index");
        LocalInstallationIndex captured = index;
        InstallationBundle<R> result = null;
        reopening = true;
        try {
            result = previous ? InstallationBundle.reopenPrevious(captured, key, registry, resultType, archive, projectDirectory, identities, cancelled) : InstallationBundle.reopen(captured, key, registry, resultType, archive, projectDirectory, identities, cancelled);
            requireConstructionAvailable();
            if (index != captured) throw recoveryRequired();
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            if (result != null) try {
                result.close();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            reopening = false;
        }
    }

    /** Read only: even a PUBLISHED receipt cannot clear uncertainty or grant runtime permission. */
    public LocalProjectStore.RequestResult lookupRequest(UUID requestId) throws IOException {
        requireOwner();
        if (status == Status.CLOSED) throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
        if (index == null) throw recoveryRequired();
        verifyPhysicalBaseline();
        return index.lookup(Objects.requireNonNull(requestId, "requestId"));
    }

    /** Explicit reconstruction after fresh world binding; refuses every live same-name instance. */
    public NativeMapFactories.Candidate resume(FPSMCore core, ExtensionRegistry registry,
                                               InstallationBundle<?> bundle, LocalInstallationIndex.Record expectedRecord, WorldBinding binding,
                                               WorldBindingCheckResult check, UUID requestId, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(expectedRecord, "expectedRecord");
        return install(core, registry, bundle, expectedRecord.plan(), binding, check, requestId, cancelled, expectedRecord, null, false);
    }

    /** Explicitly select the retained old input after fresh binding and with no live same-name map. */
    public NativeMapFactories.Candidate resumePrevious(FPSMCore core, ExtensionRegistry registry,
                                                       InstallationBundle<?> bundle, LocalInstallationIndex.Record expectedRecord, WorldBinding binding,
                                                       WorldBindingCheckResult check, UUID requestId, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(expectedRecord, "expectedRecord");
        if (expectedRecord.previous() == null) {
            throw new ProjectStorageException(ProjectStorageException.Code.CONFLICT, "No previous installation input");
        }
        return install(core, registry, bundle, expectedRecord.previous().plan(), binding, check, requestId,
                cancelled, expectedRecord, null, true);
    }

    /** Prepare while the exact old installation stays live, then retain both inputs before retiring it. */
    public NativeMapFactories.Candidate replace(FPSMCore core, ExtensionRegistry registry,
                                                InstallationBundle<?> bundle, InstallationPlan newPlan, InstalledRuntime expected,
                                                WorldBinding binding, WorldBindingCheckResult check, UUID requestId, BooleanSupplier cancelled) throws IOException {
        requireReplaceable(expected);
        return replace(core, registry, bundle, newPlan, expected.record, expected.candidate, binding, check, requestId, cancelled);
    }

    /** Native callers still provide the exact old map and lease, never a name lookup. */
    public NativeMapFactories.Candidate replace(FPSMCore core, ExtensionRegistry registry,
                                                InstallationBundle<?> bundle, InstallationPlan newPlan, LocalInstallationIndex.Record expectedRecord,
                                                NativeMapFactories.Candidate oldCandidate, WorldBinding binding, WorldBindingCheckResult check,
                                                UUID requestId, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(expectedRecord, "expectedRecord");
        Objects.requireNonNull(oldCandidate, "oldCandidate");
        return install(core, registry, bundle, newPlan, binding, check, requestId, cancelled,
                expectedRecord, oldCandidate, false);
    }

    /**
     * First installation only. The source and target must already be explicitly selected.
     * This synchronous seam retains PREPARED/recovery evidence after any persistent side effect.
     * It does not replace an existing key or restore a prior process's player authority.
     */
    public NativeMapFactories.Candidate install(FPSMCore core, ExtensionRegistry registry,
                                                InstallationBundle<?> bundle, InstallationPlan plan, WorldBinding binding,
                                                WorldBindingCheckResult check, UUID requestId, BooleanSupplier cancelled) throws IOException {
        return install(core, registry, bundle, plan, binding, check, requestId, cancelled, null, null, false);
    }

    private NativeMapFactories.Candidate install(FPSMCore core, ExtensionRegistry registry,
                                                 InstallationBundle<?> bundle, InstallationPlan plan, WorldBinding binding,
                                                 WorldBindingCheckResult check, UUID requestId, BooleanSupplier cancelled,
                                                 LocalInstallationIndex.Record expectedRecord, NativeMapFactories.Candidate oldCandidate,
                                                 boolean previous) throws IOException {
        requireIdle();
        tests.requireInstallationIdle();
        if (core == null || core.runtimeInstallations() != this || core.getServer() != server || !core.isActive()) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!"1.20.1".equals(plan.targetVersion()) || !plan.dimensions().equals(binding.dimensions()) || !bundle.preparation().preparedWith(registry) || !core.checkGameType(plan.key().type())) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        requireConstructionAvailable();
        BaseMap oldMap = oldCandidate == null ? null : oldCandidate.map();
        RuntimeMapLease oldLease = oldCandidate == null ? null : oldCandidate.lease();
        if (expectedRecord == null) core.requireUnoccupiedMapName(plan.key().type(), plan.key().name());
        else {
            if (index == null || !expectedRecord.equals(index.records().get(plan.key()))) {
                throw new ProjectStorageException(ProjectStorageException.Code.CONFLICT, "Installation record differs");
            }
            if (oldCandidate != null) {
                if (expectedRecord.state() != LocalInstallationIndex.State.ACTIVE || !expectedRecord.plan().key().equals(plan.key()) || expectedRecord.plan().installationId().equals(plan.installationId())) {
                    throw new ProjectStorageException(ProjectStorageException.Code.CONFLICT, "Invalid replacement baseline");
                }
                core.requireInstalledRuntimeMap(oldMap, oldLease, plan.key());
                requireReplacementIdle(oldMap);
            } else {
                core.requireRuntimeMapNameUnoccupied(plan.key().type(), plan.key().name());
                var selected = previous ? expectedRecord.previous().bundle() : expectedRecord.bundle();
                if (!bundle.bytes().equals(selected)) {
                    throw new ProjectStorageException(ProjectStorageException.Code.CORRUPT_CONTENT, "Installation recovery archive differs");
                }
            }
        }
        Attempt current = new Attempt(core, bundle, plan, requestId, cancelled, expectedRecord,
                index == null ? null : index.currentCommitId(), oldMap, oldLease, previous);
        attempt = current;
        try {
            bundle.requirePlan(plan);
            bundle.verify(cancelled);
            current.candidate = NativeMapFactories.construct(registry, plan.factoryId(), bundle.preparation(),
                    core, binding, check, plan.key().name(), cancelled, current);
            bundle.preparation(); // A callback may have closed the job while construction was in progress.
            core.getWorldBindings().verify(binding);
            index.verify();
            current.checkMap(current.candidate.map());
            core.publishInstalledRuntimeMap(current.candidate.map(), current.candidate.lease());
            core.getWorldBindings().verify(binding);
            index.verify();
            // All fallible host checks precede the ACTIVE CAS. Previous remains a durable recovery input, including
            // after UNKNOWN.
            index.transition(index.currentCommitId(), UUID.randomUUID(), plan.key(), LocalInstallationIndex.State.ACTIVE);
            installed.put(plan.key(), new InstalledRuntime(this, core, current.candidate,
                    index.records().get(plan.key()), binding.worldId()));
            return current.candidate;
        } catch (IOException | RuntimeException | Error failure) {
            if (current.candidate != null) {
                try {
                    current.candidate.withdrawFrom(core);
                } catch (RuntimeException | Error withdrawal) {
                    if (withdrawal != failure) failure.addSuppressed(withdrawal);
                }
                try {
                    current.candidate.close();
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            if (current.started && current.oldMap != null) {
                try {
                    core.withdrawInstalledRuntimeMap(current.oldMap, current.oldLease);
                } catch (RuntimeException | Error withdrawal) {
                    if (withdrawal != failure) failure.addSuppressed(withdrawal);
                }
                // A failed readiness check is retried explicitly or by core-stop, never twice in this attempt.
                if (!current.retirementAttempted) {
                    current.retirementAttempted = true;
                    try {
                        current.oldLease.close();
                    } catch (RuntimeException | Error cleanup) {
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                }
            }
            if (current.started) {
                installed.remove(plan.key());
                try {
                    if (index != null && index.records().containsKey(plan.key())) {
                        var state = index.records().get(plan.key()).state();
                        if (state != LocalInstallationIndex.State.RECOVERY_REQUIRED) {
                            index.transition(index.currentCommitId(), UUID.randomUUID(), plan.key(), LocalInstallationIndex.State.RECOVERY_REQUIRED);
                        }
                    }
                } catch (IOException | RuntimeException | Error recovery) {
                    if (recovery != failure) failure.addSuppressed(recovery);
                }
                if (status != Status.CLOSED) protect(failure);
            }
            throw failure;
        } finally {
            attempt = null;
        }
    }

    /** Captured permanently by maps born while the installation owns construction. */
    public boolean isInstallationConstruction() {
        requireOwner();
        return attempt != null && attempt.reserved && attempt.constructionAllowed;
    }

    /** Internal construction permission is exact-key and never authorizes legacy publication/save. */
    public void requireConstruction(String type, String name) {
        requireConstructionAvailable();
        if (attempt != null) {
            if (attempt.reserved && attempt.constructionAllowed && attempt.plan.key().type().equals(type) && attempt.plan.key().name().equals(name)) return;
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.BUSY);
        }
        requireLegacyConstruction(type, name);
    }

    /** Called only by core's dedicated publication path; arbitrary candidates cannot enter GAMES. */
    public InstallationPlan.Key requirePublication(BaseMap map, RuntimeMapLease lease) {
        requireConstructionAvailable();
        if (attempt == null || !attempt.reserved || attempt.candidate == null || attempt.candidate.map() != map || attempt.candidate.lease() != lease || index.records().get(attempt.plan.key()).state() != LocalInstallationIndex.State.PREPARED) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.PERSISTENT_OWNERSHIP);
        }
        attempt.checkMap(map);
        return attempt.plan.key();
    }

    final class Attempt {

        private final FPSMCore core;
        private final InstallationBundle<?> bundle;
        private final InstallationPlan plan;
        private final UUID requestId;
        private final BooleanSupplier cancelled;
        private final LocalInstallationIndex.Record expectedRecord;
        private final UUID expectedHead;
        private final BaseMap oldMap;
        private final RuntimeMapLease oldLease;
        private final boolean previous;
        private boolean retirementAttempted;
        private boolean constructionAllowed;
        private boolean started;
        private boolean reserved;
        private NativeMapFactories.Candidate candidate;

        private Attempt(FPSMCore core, InstallationBundle<?> bundle, InstallationPlan plan, UUID requestId,
                        BooleanSupplier cancelled, LocalInstallationIndex.Record expectedRecord, UUID expectedHead,
                        BaseMap oldMap, RuntimeMapLease oldLease, boolean previous) {
            this.core = core;
            this.bundle = bundle;
            this.plan = plan;
            this.requestId = requestId;
            this.cancelled = cancelled;
            this.expectedRecord = expectedRecord;
            this.expectedHead = expectedHead;
            this.oldMap = oldMap;
            this.oldLease = oldLease;
            this.previous = previous;
        }

        void begin(ConfirmedTarget target) {
            if (attempt != this || started || target.server() != server || core.runtimeInstallations() != MinecraftRuntimeInstallations.this) {
                throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
            }
            requireConstructionAvailable();
            if (expectedRecord == null) core.requireRuntimeMapNameUnoccupied(plan.key().type(), plan.key().name());
            else if (oldMap == null) core.requireRuntimeMapNameUnoccupied(plan.key().type(), plan.key().name());
            else {
                core.requireInstalledRuntimeMap(oldMap, oldLease, plan.key());
                requireReplacementIdle(oldMap);
            }
            try {
                if (expectedRecord == null) started = true;
                if (index == null) {
                    // Only the existing authenticated editor directory is accepted. Never refresh a missing baseline.
                    if (editorIdentity == null) throw new IOException("World identity must be explicitly registered before installation");
                    index = LocalInstallationIndex.initialize(root, identities);
                    status = Status.RESERVED;
                }
                target.verify();
                if (expectedRecord == null) bundle.reserve(index, index.currentCommitId(), requestId, plan, cancelled);
                else {
                    LocalInstallationIndex capturedIndex = index;
                    bundle.requirePlan(plan);
                    bundle.verify(cancelled);
                    target.verify();
                    requireConstructionAvailable();
                    if (attempt != this || index != capturedIndex || index == null) {
                        throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
                    }
                    if (oldMap != null) {
                        core.requireInstalledRuntimeMap(oldMap, oldLease, plan.key());
                        requireReplacementIdle(oldMap);
                    } else core.requireRuntimeMapNameUnoccupied(plan.key().type(), plan.key().name());
                    try {
                        if (oldMap != null) bundle.beginReplacement(index, expectedHead, requestId, expectedRecord, plan, () -> false);
                        else if (previous) index.beginPreviousRecovery(expectedHead, requestId, expectedRecord, bundle.bytes());
                        else index.beginRecovery(expectedHead, requestId, expectedRecord, bundle.bytes());
                        started = true;
                    } catch (IOException | RuntimeException | Error failure) {
                        // Only a fully authenticated unchanged baseline can retain the old live instance.
                        try {
                            index.verify();
                            started = !expectedHead.equals(index.currentCommitId()) || !expectedRecord.equals(index.records().get(expectedRecord.plan().key()));
                        } catch (IOException | RuntimeException | Error observation) {
                            started = true;
                            if (observation != failure) failure.addSuppressed(observation);
                        }
                        throw failure;
                    }
                }
                reserved = true;
                if (oldMap != null) {
                    core.requireInstalledRuntimeMap(oldMap, oldLease, plan.key());
                    requireReplacementIdle(oldMap);
                    core.withdrawInstalledRuntimeMap(oldMap, oldLease);
                    retirementAttempted = true;
                    oldLease.close();
                    core.requireRuntimeMapNameUnoccupied(plan.key().type(), plan.key().name());
                }
                constructionAllowed = true;
                target.verify();
                index.verify();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        void checkMap(BaseMap map) {
            if (!plan.key().name().equals(map.mapName) || !plan.key().type().equals(map.getGameType())) {
                throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INVALID_INPUT);
            }
        }
    }

    public MinecraftRuntimeInstallations(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        requireOwner();
        root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        identities = "\\".equals(FileSystems.getDefault().getSeparator()) ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
    }

    /** Called once after core publication and before any registration event or legacy data read. */
    public void restoreOwnership() {
        requireOwner();
        if (status != Status.RECOVERING) throw new IllegalStateException("Installation ownership was already inspected");
        try {
            rootIdentity = identities.identity(root);
            editorIdentity = editorIdentity();
            index = LocalInstallationIndex.openIfPresent(root, identities).orElse(null);
            verifyPhysicalBaseline();
            status = index == null ? Status.LEGACY : Status.RESERVED;
        } catch (IOException | RuntimeException | LinkageError failure) {
            protect(failure);
        }
    }

    public Status status() {
        requireOwner();
        return status;
    }

    public MinecraftRuntimeTests tests() {
        requireOwner();
        return tests;
    }

    void requireTestOwner() {
        requireOwner();
    }

    /**
     * Matches an independently verified authoring association to this exact server world.
     * A shared world UUID or a matching path alone never authorizes a copied/replaced save.
     * This check does not replace the current player's WorldBinding or its confirmation.
     */
    public void requirePhysicalWorld(Path expectedRoot, FileNodeIdentity expectedIdentity) {
        requireOwner();
        requireConstructionAvailable();
        if (expectedRoot == null || expectedIdentity == null || !expectedRoot.isAbsolute() || !root.equals(expectedRoot.normalize()) || !rootIdentity.equals(expectedIdentity)) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
    }

    /** Captures only a successful publication in this process, never a same-name reconstruction. */
    public InstalledRuntime captureInstalled(LocalInstallationIndex.Record expected) {
        requireIdle();
        requireConstructionAvailable();
        Objects.requireNonNull(expected, "expected");
        InstalledRuntime result = installed.get(expected.plan().key());
        if (result == null || !result.record.equals(expected)) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        requireInstalled(result);
        return result;
    }

    /** Read-only availability of this exact installation; a same-name runtime grants no authority. */
    public ReplacementStatus replacementStatus(InstalledRuntime selected) {
        return replacementStatus(requireInstalled(selected));
    }

    public void requireReplaceable(InstalledRuntime selected) {
        requireReplacementIdle(requireInstalled(selected));
    }

    private ReplacementStatus replacementStatus(BaseMap map) {
        try {
            tests.requireInstallationIdle();
        } catch (net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException busy) {
            return ReplacementStatus.BUSY;
        }
        if (map.isStart()) return ReplacementStatus.RUNNING;
        if (!map.getMapTeams().getJoinedPlayersWithSpec().isEmpty()) return ReplacementStatus.PARTICIPANTS;
        if (map.resetObservation(map.resetGeneration()) == BaseMap.ResetObservation.INCOMPLETE)
            return ReplacementStatus.RESET_INCOMPLETE;
        return ReplacementStatus.READY;
    }

    private void requireReplacementIdle(BaseMap map) {
        ReplacementStatus observed = replacementStatus(map);
        if (observed != ReplacementStatus.READY)
            throw new IllegalStateException("Runtime replacement unavailable: " + observed);
    }

    /** Full persistent and live identity checks before a new test operation. */
    BaseMap requireInstalled(InstalledRuntime selected) {
        requireIdle();
        requireConstructionAvailable();
        if (selected == null || selected.owner != this || index == null || selected.record.state() != LocalInstallationIndex.State.ACTIVE || !selected.record.equals(index.records().get(selected.record.plan().key()))) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        return requireLiveInstalled(selected);
    }

    /** Exact native cleanup authority survives loss of the player's binding, never replacement. */
    BaseMap requireLiveInstalled(InstalledRuntime selected) {
        requireOwner();
        if (selected == null || selected.owner != this || status == Status.CLOSED || installed.get(selected.record.plan().key()) != selected || selected.core.runtimeInstallations() != this || selected.core.getServer() != server || FPSMCore.getInstance() != selected.core || !selected.core.isActive()) {
            throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        BaseMap map = selected.candidate.map();
        selected.core.requireInstalledRuntimeMap(map, selected.candidate.lease(), selected.record.plan().key());
        return map;
    }

    /** Opaque process-local identity. Metadata is descriptive and confers no authority by itself. */
    public static final class InstalledRuntime {

        private final MinecraftRuntimeInstallations owner;
        private final FPSMCore core;
        private final NativeMapFactories.Candidate candidate;
        private final LocalInstallationIndex.Record record;
        private final UUID worldId;

        private InstalledRuntime(MinecraftRuntimeInstallations owner, FPSMCore core,
                                 NativeMapFactories.Candidate candidate, LocalInstallationIndex.Record record, UUID worldId) {
            this.owner = owner;
            this.core = core;
            this.candidate = candidate;
            this.record = record;
            this.worldId = worldId;
        }

        public InstallationPlan plan() {
            return record.plan();
        }

        public UUID worldId() {
            return worldId;
        }

        FPSMCore core() {
            return core;
        }

        boolean preparedWith(ExtensionRegistry registry) {
            return candidate.preparation().preparedWith(registry);
        }
    }

    /** Metadata only. ACTIVE remains a durable record, not a statement that a map runs in this process. */
    public Map<InstallationPlan.Key, LocalInstallationIndex.Record> records() {
        requireOwner();
        if (status == Status.LEGACY) return Map.of();
        if (status != Status.RESERVED) throw recoveryRequired();
        return index.records();
    }

    public void requireConstructionAvailable() {
        requireOwner();
        if (registering) throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.BUSY);
        if (status == Status.CLOSED) throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.INACTIVE);
        if (status == Status.RECOVERING || status == Status.RECOVERY_REQUIRED) throw recoveryRequired();
        try {
            verifyPhysicalBaseline();
            if (index == null) {
                // An authority appearing after the startup capture is an external change, never a new empty baseline.
                var appeared = LocalInstallationIndex.openIfPresent(root, identities);
                if (appeared.isPresent()) {
                    appeared.get().close();
                    throw new IOException("Installation ownership appeared after startup");
                }
            } else {
                index.verify();
            }
        } catch (RuntimeMapLeaseException refusal) {
            throw refusal;
        } catch (IOException | RuntimeException | LinkageError failure) {
            protect(failure);
            throw recoveryRequired();
        }
    }

    public void requireLegacyConstruction(String type, String name) {
        requireConstructionAvailable();
        if (index != null) for (InstallationPlan.Key key : index.records().keySet()) {
            if (key.type().equals(type) && key.name().equals(name)) {
                throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.PERSISTENT_OWNERSHIP);
            }
        }
    }

    private FileNodeIdentity editorIdentity() throws IOException {
        Path editor = root.resolve(".editor-platform");
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(editor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return null;
        }
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Invalid editor directory");
        return identities.identity(editor);
    }

    private void verifyPhysicalBaseline() throws IOException {
        if (!root.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()) || !rootIdentity.equals(identities.identity(root)) || !Objects.equals(editorIdentity, editorIdentity())) {
            throw new IOException("Installation world directory changed after startup");
        }
    }

    private void protect(Throwable failure) {
        status = Status.RECOVERY_REQUIRED;
        recoveryFailure = failure;
        FPSMatch.LOGGER.error("Runtime installation ownership requires recovery; legacy map construction is blocked", failure);
    }

    private RuntimeMapLeaseException recoveryRequired() {
        RuntimeMapLeaseException refusal = new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.RECOVERY_REQUIRED);
        if (recoveryFailure != null) refusal.initCause(recoveryFailure);
        return refusal;
    }

    private void requireOwner() {
        if (!server.isSameThread()) throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.WRONG_THREAD);
    }

    private void requireIdle() {
        requireOwner();
        if (attempt != null || reopening || registering) throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.BUSY);
    }

    @Override
    public void close() {
        requireOwner();
        if (status == Status.CLOSED) return;
        status = Status.CLOSED;
        tests.closed();
        installed.clear();
        LocalInstallationIndex old = index;
        index = null;
        recoveryFailure = null;
        if (old != null) {
            try {
                old.close();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
