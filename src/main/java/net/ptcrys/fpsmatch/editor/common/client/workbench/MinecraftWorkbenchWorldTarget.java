package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorldTargetWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorldTargetWorkbench.*;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.*;
import net.ptcrys.fpsmatch.editor.core.worldio.*;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Integrated-server bridge. Requests cross executors; native authority never leaves its server owner. */
final class MinecraftWorkbenchWorldTarget implements WorldTargetWorkbench.Host {

    private final Minecraft minecraft;
    private final Executor io;
    private final ProjectWorldWorkbench projectWorld;
    private final net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry registry;

    MinecraftWorkbenchWorldTarget(Minecraft minecraft, Executor io, net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry registry, ProjectWorldWorkbench projectWorld) {
        this.minecraft = minecraft;
        this.io = io;
        this.registry = registry;
        this.projectWorld = projectWorld;
    }

    @Override
    public Capture capture() {
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null)
            return new Capture(null, text("world_enter"));
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) return new Capture(null, text("world_remote_pending"));
        var permit = projectWorld.permission(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).orElse(null);
        if (permit == null) return new Capture(null, text(projectWorld.state() == ProjectWorldWorkbench.State.READY ? "project_world_mismatch" : "project_world_required"));
        var authoringWorld = projectWorld.binding().map(net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore.Binding::world).orElse(null);
        var player = minecraft.player;
        var level = minecraft.level;
        var network = minecraft.getConnection();
        UUID actor = player.getUUID();
        List<EditorId> dimensions = network.levels().stream().map(key -> EditorId.parse(key.location().toString())).sorted().toList();
        EditorId current = EditorId.parse(level.dimension().location().toString());
        String label = server.getWorldData().getLevelName();
        return new Capture(new Target() {

            private volatile ServerPlayer verifiedActor;
            private volatile FPSMCore verifiedCore;
            private RegistrationSubmission submission;
            private RuntimeException invalidation;

            public String label() {
                return label;
            }

            public List<EditorId> dimensions() {
                return dimensions;
            }

            public EditorId currentDimension() {
                return current;
            }

            public boolean current() {
                String reason = null;
                if (minecraft.getSingleplayerServer() != server) reason = "server_replaced";
                else if (server.isStopped()) reason = "server_stopped";
                else {
                    var validity = projectWorld.validity(permit);
                    if (validity != ProjectWorldWorkbench.PermissionValidity.CURRENT) reason = "authoring_" + validity.name();
                    else if (minecraft.player != player) reason = "client_player_replaced";
                    else if (minecraft.level != level) reason = "client_level_replaced";
                    else if (minecraft.getConnection() != network) reason = "client_connection_replaced";
                }
                if (reason == null) return true;
                if (invalidation == null) {
                    invalidation = new IllegalStateException("Client world target changed: " + reason);
                    com.mojang.logging.LogUtils.getLogger().warn("Editor world target invalidated: {}", reason);
                }
                return false;
            }

            public Throwable invalidationDiagnostic() {
                return invalidation;
            }

            public Cancellation bind(Map<EditorId, EditorId> mappings, Consumer<Result> completed) {
                if (!current()) throw new WorldBindingException(WorldBindingException.Code.DISCONNECTED, "Client target changed");
                var attempt = new Attempt(server, actor, Map.copyOf(mappings), completed, permit, authoringWorld);
                attempt.onServer(() -> {
                    verifiedActor = server.getPlayerList().getPlayer(actor);
                    verifiedCore = FPSMCore.getInstance();
                    attempt.begin();
                });
                return attempt::cancel;
            }

            public void register(Consumer<RegistrationResult> completed) {
                if (!current()) throw new WorldBindingException(WorldBindingException.Code.DISCONNECTED, "Client target changed");
                ServerPlayer expectedActor = verifiedActor;
                FPSMCore expectedCore = verifiedCore;
                RegistrationSubmission submitted = new RegistrationSubmission(completed);
                submission = submitted;
                try {
                    if (server.isStopped()) throw new WorldBindingException(WorldBindingException.Code.INACTIVE, "Selected server stopped");
                    server.execute(() -> submitted.run(() -> {
                        try {
                            FPSMCore core = FPSMCore.getInstance();
                            if (core == null || core != expectedCore || !core.isActive() || core.getServer() != server || expectedActor == null || server.getPlayerList().getPlayer(actor) != expectedActor)
                                throw new WorldBindingException(WorldBindingException.Code.DISCONNECTED, "Selected player or core changed");
                            UUID identity = core.initializeEditorWorldIdentity(actor, () -> false);
                            return new RegistrationResult(identity, null, null);
                        } catch (IOException | RuntimeException failure) {
                            return new RegistrationResult(null, registrationMessage(failure), failure);
                        }
                    }));
                } catch (RuntimeException failure) {
                    submitted.rejectBeforeStart(new RegistrationResult(null, registrationMessage(failure), failure));
                }
            }

            public void pollRegistration() {
                if (submission != null && server.isStopped())
                    submission.rejectBeforeStart(new RegistrationResult(null, text("world_registration_not_started"), null));
            }
        }, null);
    }

    private final class Attempt implements Connection {

        private final MinecraftServer server;
        private final UUID actor;
        private final Map<EditorId, EditorId> mappings;
        private final Consumer<Result> completed;
        private final ProjectWorldWorkbench.Permission permit;
        private final net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore.World authoringWorld;
        private final AtomicBoolean cancelled = new AtomicBoolean(), delivered = new AtomicBoolean();
        private final AtomicBoolean failureReported = new AtomicBoolean();
        private volatile WorldBindingRequest request;
        private volatile WorldBindingCheck check;
        // These three references are accessed only on the captured server owner.
        private FPSMCore core;
        private LocalWorldBindings bindings;
        private WorldBinding binding;
        // Immutable UI facts are published before delivery, never used as authority.
        private volatile UUID worldId, requestId;
        private volatile MinecraftWorkbenchRuntimeInstallations installations;

        Attempt(MinecraftServer server, UUID actor, Map<EditorId, EditorId> mappings, Consumer<Result> completed, ProjectWorldWorkbench.Permission permit, net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore.World authoringWorld) {
            this.server = server;
            this.actor = actor;
            this.mappings = mappings;
            this.completed = completed;
            this.permit = permit;
            this.authoringWorld = authoringWorld;
        }

        private void begin() {
            try {
                stop();
                core = FPSMCore.getInstance();
                requireCore();
                bindings = core.getWorldBindings();
                request = bindings.begin(actor, mappings);
                if (cancelled.get()) {
                    cancel();
                    return;
                }
                io.execute(() -> {
                    try {
                        FileIdentityProvider provider = "\\".equals(FileSystems.getDefault().getSeparator()) ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
                        projectWorld.verify(permit);
                        WorldBindingRead read = request.read(provider, cancelled::get);
                        onServer(() -> accept(read));
                    } catch (IOException | RuntimeException failure) {
                        fail(failure);
                    }
                });
            } catch (RuntimeException failure) {
                fail(failure);
            }
        }

        private void accept(WorldBindingRead read) {
            try {
                stop();
                requireCore();
                binding = bindings.accept(read);
                check = bindings.check(binding);
                io.execute(() -> {
                    try {
                        projectWorld.verify(permit);
                        WorldBindingCheckResult checked = check.verify(cancelled::get);
                        onServer(() -> confirm(checked));
                    } catch (IOException | RuntimeException failure) {
                        fail(failure);
                    }
                });
            } catch (RuntimeException failure) {
                fail(failure);
            }
        }

        private void confirm(WorldBindingCheckResult checked) {
            try {
                stop();
                requireCore();
                bindings.confirm(binding, checked);
                stop();
                worldId = binding.worldId();
                requestId = binding.requestId();
                installations = new MinecraftWorkbenchRuntimeInstallations(core, binding, registry, io, cancelled::get, projectWorld, permit, authoringWorld);
                if (delivered.compareAndSet(false, true)) completed.accept(new Result(this, null, null));
            } catch (RuntimeException failure) {
                fail(failure);
            }
        }

        private void requireCore() {
            if (core == null || !core.isActive() || core.getServer() != server || FPSMCore.getInstance() != core)
                throw new WorldBindingException(WorldBindingException.Code.INACTIVE, "Selected server is inactive");
        }

        private void stop() {
            if (cancelled.get()) throw new WorldBindingException(WorldBindingException.Code.STALE, "World selection cancelled");
        }

        private void onServer(Runnable operation) {
            try {
                if (server.isStopped()) throw new WorldBindingException(WorldBindingException.Code.INACTIVE, "Selected server stopped");
                server.execute(operation);
            } catch (RuntimeException failure) {
                fail(failure);
            }
        }

        private void fail(Throwable failure) {
            reportFailure(failure);
            cancel();
            if (delivered.compareAndSet(false, true)) completed.accept(new Result(null, message(failure), failure,
                    failure instanceof WorldIdentityException identity && identity.code() == WorldIdentityException.Code.MISSING ? FailureKind.IDENTITY_MISSING : FailureKind.GENERAL));
        }

        private void reportFailure(Throwable failure) {
            if (failureReported.compareAndSet(false, true))
                com.mojang.logging.LogUtils.getLogger().warn("Editor runtime world authority failed", failure);
        }

        private void cancel() {
            cancelled.set(true);
            WorldBindingRequest old = request;
            if (old != null) old.cancel();
            WorldBindingCheck physical = check;
            if (physical != null) physical.cancel();
            // Never revoke by actor UUID: a later selection may already own that slot.
            if (!server.isStopped()) {
                try {
                    server.execute(this::cleanup);
                } catch (RuntimeException ignored) { /* server lifetime owns final revocation */ }
            }
        }

        private void cleanup() {
            try {
                if (bindings != null) {
                    if (binding != null) bindings.verify(binding); // expired exact token removes only its own slot
                    else if (request != null) bindings.discard(request);
                }
            } catch (WorldBindingException ignored) { /* stale/closed is the expected cancelled state */ } finally {
                binding = null;
                bindings = null;
                core = null;
            }
        }

        @Override
        public UUID worldId() {
            return Objects.requireNonNull(worldId);
        }

        @Override
        public UUID requestId() {
            return Objects.requireNonNull(requestId);
        }

        @Override
        public net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeInstallationWorkbench.Target installations() {
            return installations;
        }

        @Override
        public void close() {
            cancel();
        }

        @Override
        public void verify(Consumer<Result> callback) {
            try {
                if (server.isStopped()) {
                    callback.accept(new Result(null, text("world_disconnected"), null));
                    return;
                }
                server.execute(() -> {
                    try {
                        stop();
                        requireCore();
                        bindings.verify(binding);
                        callback.accept(new Result(this, null, null));
                    } catch (RuntimeException failure) {
                        reportFailure(failure);
                        cancel();
                        callback.accept(new Result(null, message(failure), failure));
                    }
                });
            } catch (RuntimeException failure) {
                reportFailure(failure);
                cancel();
                callback.accept(new Result(null, message(failure), failure));
            }
        }
    }

    private static TextContent text(String suffix) {
        return WorldTargetWorkbench.text(suffix);
    }

    private static TextContent registrationMessage(Throwable failure) {
        if (failure instanceof RuntimeMapLeaseException lease) return lease.problem().message();
        if (failure instanceof ProjectStorageException storage) return text(switch (storage.code()) {
            case CONFLICT -> "world_identity_exists";
            case RESULT_UNKNOWN -> "world_identity_unknown";
            case BUSY -> "world_registration_busy";
            default -> "world_registration_failed";
        });
        if (failure instanceof WorldIdentityException identity && identity.code() == WorldIdentityException.Code.ALREADY_EXISTS)
            return text("world_identity_exists");
        if (failure instanceof WorldIdentityException || failure instanceof WorldBindingException) return message(failure);
        return text("world_registration_failed");
    }

    private static TextContent message(Throwable failure) {
        if (failure instanceof WorldIdentityException identity) return text(switch (identity.code()) {
            case MISSING -> "world_identity_missing";
            case CORRUPT, INVALID_ROOT, CHANGED -> "world_identity_changed";
            case UNKNOWN -> "world_identity_unknown";
            case CANCELLED -> "world_cancelled";
            case UNSUPPORTED -> "world_identity_unsupported";
            default -> "world_failed";
        });
        if (failure instanceof WorldBindingException binding) return text(switch (binding.code()) {
            case PERMISSION_DENIED -> "world_permission";
            case DIMENSION_UNAVAILABLE, INVALID_TARGET -> "world_dimension_unavailable";
            case DISCONNECTED, INACTIVE, CLOSED, STALE -> "world_disconnected";
            default -> "world_failed";
        });
        return text("world_failed");
    }
}
