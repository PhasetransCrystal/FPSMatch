package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.worldaccess.LocalWorldBindingHost;
import net.ptcrys.fpsmatch.editor.core.worldaccess.LocalWorldBindings;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingException;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingException.Code;

/** Exact server/player/connection/level authentication for this Minecraft target. */
public final class MinecraftWorldBindings implements AutoCloseable {

    private final Thread owner;
    private final LocalWorldBindings bindings;
    private MinecraftServer server;
    private BooleanSupplier active;
    private boolean resolving;

    public MinecraftWorldBindings(MinecraftServer server, BooleanSupplier active) {
        this.server = Objects.requireNonNull(server, "server");
        this.active = Objects.requireNonNull(active, "active");
        if (!server.isSameThread()) throw failure(Code.WRONG_THREAD, "Server owner required");
        owner = Thread.currentThread();
        bindings = new LocalWorldBindings(new Host());
    }

    public LocalWorldBindings bindings() {
        return bindings;
    }

    /** Controller-only synchronous registration. The preparation hook must not escape this exact player authority. */
    public WorldIdentitySnapshot initializeIdentity(UUID actorId, FileIdentityProvider identities,
                                                    Runnable authorizedPrepare, BooleanSupplier cancelled) throws WorldIdentityException {
        requireOwner();
        if (resolving) throw failure(Code.BUSY, "World target resolution is busy");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(identities, "identities");
        Objects.requireNonNull(authorizedPrepare, "authorizedPrepare");
        Objects.requireNonNull(cancelled, "cancelled");
        resolving = true;
        try {
            LocalWorldBindingHost.Selection selected = new Host().capture(actorId, Map.of());
            selected.verify();
            boolean[] prepared = { false };
            boolean[] stopped = { false };
            WorldIdentitySnapshot result = WorldIdentities.initialize(selected.root(), identities, () -> {
                selected.verify();
                if (!stopped[0]) stopped[0] = cancelled.getAsBoolean();
                selected.verify();
                if (!stopped[0] && !prepared[0]) {
                    authorizedPrepare.run();
                    prepared[0] = true;
                    selected.verify();
                }
                return stopped[0];
            });
            selected.verify();
            return result;
        } finally {
            resolving = false;
        }
    }

    /**
     * Synchronous construction handoff only; this neither installs nor owns callback resources.
     * Map factories must enclose this entire call in their construction cleanup scope.
     */
    public <R> R withConfirmedTarget(WorldBinding binding, WorldBindingCheckResult result,
                                     Set<EditorId> requiredDimensions, Function<ConfirmedTarget, R> operation) {
        requireOwner();
        if (resolving) throw failure(Code.BUSY, "World target resolution is busy");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(requiredDimensions, "requiredDimensions");
        Objects.requireNonNull(operation, "operation");
        resolving = true;
        ConfirmedTarget target = null;
        try {
            Map<EditorId, ResourceKey<Level>> keys = new HashMap<>();
            int visited = 0;
            for (EditorId source : requiredDimensions) {
                if (++visited > 64 || source == null || keys.containsKey(source)) {
                    throw failure(Code.INVALID_TARGET, "Expected one to 64 unique required dimensions");
                }
                EditorId mapped = binding.dimensions().get(source);
                if (mapped == null) throw failure(Code.INVALID_TARGET, "Required dimension has no mapping");
                keys.put(source, dimensionKey(mapped));
            }
            if (visited == 0) throw failure(Code.INVALID_TARGET, "Required dimensions are empty");
            Map<EditorId, ResourceKey<Level>> capturedKeys = Map.copyOf(keys);
            bindings.confirm(binding, result);
            MinecraftServer selectedServer = requireActiveServer();
            Map<EditorId, ServerLevel> levels = new HashMap<>();
            for (Map.Entry<EditorId, ResourceKey<Level>> entry : capturedKeys.entrySet()) {
                ServerLevel level = selectedServer.getLevel(entry.getValue());
                if (level == null || level.getServer() != selectedServer || !level.dimension().equals(entry.getValue())) {
                    throw failure(Code.DIMENSION_UNAVAILABLE, "Required target dimension is unavailable");
                }
                levels.put(entry.getKey(), level);
            }
            bindings.verify(binding);
            target = new ConfirmedTarget(this, binding, selectedServer, capturedKeys, Map.copyOf(levels));
            target.verify();
            R value = operation.apply(target);
            target.verify();
            return value;
        } finally {
            if (target != null) target.expire();
            resolving = false;
        }
    }

    /** Native references are valid only inside the owning synchronous handoff. */
    public static final class ConfirmedTarget {

        private final Thread owner;
        private MinecraftWorldBindings backend;
        private WorldBinding binding;
        private MinecraftServer server;
        private Map<EditorId, ResourceKey<Level>> keys;
        private Map<EditorId, ServerLevel> levels;

        private ConfirmedTarget(MinecraftWorldBindings backend, WorldBinding binding,
                                MinecraftServer server, Map<EditorId, ResourceKey<Level>> keys,
                                Map<EditorId, ServerLevel> levels) {
            this.owner = backend.owner;
            this.backend = backend;
            this.binding = binding;
            this.server = server;
            this.keys = keys;
            this.levels = levels;
        }

        public void verify() {
            if (Thread.currentThread() != owner) throw failure(Code.WRONG_THREAD, "Server owner required");
            if (backend == null) throw failure(Code.STALE, "Confirmed target scope has expired");
            backend.bindings.verify(binding);
            if (backend.requireActiveServer() != server) {
                throw failure(Code.INACTIVE, "Confirmed server instance changed");
            }
            for (Map.Entry<EditorId, ServerLevel> entry : levels.entrySet()) {
                ResourceKey<Level> key = keys.get(entry.getKey());
                ServerLevel level = entry.getValue();
                if (level.getServer() != server || !level.dimension().equals(key) || server.getLevel(key) != level) {
                    throw failure(Code.DIMENSION_UNAVAILABLE, "Confirmed dimension instance changed");
                }
            }
        }

        public MinecraftServer server() {
            verify();
            return server;
        }

        public ServerLevel level(EditorId sourceDimension) {
            verify();
            if (sourceDimension == null) throw failure(Code.INVALID_TARGET, "Source dimension is null");
            ServerLevel level = levels.get(sourceDimension);
            if (level == null) throw failure(Code.INVALID_TARGET, "Dimension was not required by this scope");
            return level;
        }

        private void expire() {
            backend = null;
            binding = null;
            server = null;
            keys = null;
            levels = null;
        }
    }

    private static ResourceKey<Level> dimensionKey(EditorId dimension) {
        return ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(dimension.namespace(), dimension.path()));
    }

    /** A late logout for an old player must not revoke a replacement connection. */
    public void disconnected(ServerPlayer player) {
        requireOwner();
        Objects.requireNonNull(player, "player");
        MinecraftServer current = server;
        if (current == null || player.server != current) return;
        ServerPlayer listed = current.getPlayerList().getPlayer(player.getUUID());
        if (listed == null || listed == player) bindings.revoke(player.getUUID());
    }

    @Override
    public void close() {
        requireOwner();
        try {
            bindings.close();
        } finally {
            server = null;
            active = null;
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw failure(Code.WRONG_THREAD, "Server owner required");
    }

    private boolean active() {
        MinecraftServer captured = server;
        BooleanSupplier predicate = active;
        return captured != null && predicate != null && !captured.isStopped() && predicate.getAsBoolean() && server == captured && !captured.isStopped();
    }

    private MinecraftServer requireActiveServer() {
        requireOwner();
        if (!active()) throw failure(Code.INACTIVE, "Server lifetime is inactive");
        return server;
    }

    private static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private static void verifyPlayer(MinecraftServer server, UUID actor, ServerPlayer player,
                                     ServerGamePacketListenerImpl listener, Connection connection) {
        if (player == null || listener == null || connection == null || player.server != server || server.getPlayerList().getPlayer(actor) != player || player.connection != listener || listener.player != player || listener.connection != connection || !connection.isConnected()) {
            throw failure(Code.DISCONNECTED, "Player connection changed or disconnected");
        }
        if (!player.hasPermissions(2)) throw failure(Code.PERMISSION_DENIED, "Management permission required");
    }

    private final class Host implements LocalWorldBindingHost {

        @Override
        public void requireOwner() {
            MinecraftWorldBindings.this.requireOwner();
        }

        @Override
        public boolean active() {
            return MinecraftWorldBindings.this.active();
        }

        @Override
        public Selection capture(UUID actorId, Map<EditorId, EditorId> dimensions) {
            MinecraftServer selectedServer = requireActiveServer();
            ServerPlayer player = selectedServer.getPlayerList().getPlayer(actorId);
            ServerGamePacketListenerImpl listener = player == null ? null : player.connection;
            Connection connection = listener == null ? null : listener.connection;
            verifyPlayer(selectedServer, actorId, player, listener, connection);
            Path selectedRoot = root(selectedServer);
            Map<ResourceKey<Level>, ServerLevel> levels = new TreeMap<>(
                    java.util.Comparator.comparing(key -> key.location().toString()));
            for (EditorId target : dimensions.values()) {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                        new ResourceLocation(target.namespace(), target.path()));
                ServerLevel level = selectedServer.getLevel(key);
                if (level == null || level.getServer() != selectedServer) {
                    throw failure(Code.DIMENSION_UNAVAILABLE, "Target dimension is unavailable");
                }
                levels.put(key, level);
            }
            Map<ResourceKey<Level>, ServerLevel> selectedLevels = Map.copyOf(levels);
            return new Selection() {

                @Override
                public Path root() {
                    return selectedRoot;
                }

                @Override
                public void verify() {
                    if (requireActiveServer() != selectedServer) {
                        throw failure(Code.INACTIVE, "Server instance changed");
                    }
                    verifyPlayer(selectedServer, actorId, player, listener, connection);
                    if (!MinecraftWorldBindings.root(selectedServer).equals(selectedRoot)) {
                        throw failure(Code.INVALID_TARGET, "Server world root changed");
                    }
                    for (Map.Entry<ResourceKey<Level>, ServerLevel> entry : selectedLevels.entrySet()) {
                        if (entry.getValue().getServer() != selectedServer || selectedServer.getLevel(entry.getKey()) != entry.getValue()) {
                            throw failure(Code.DIMENSION_UNAVAILABLE, "Target dimension instance changed");
                        }
                    }
                }
            };
        }
    }

    private static WorldBindingException failure(Code code, String message) {
        return new WorldBindingException(code, message);
    }
}
