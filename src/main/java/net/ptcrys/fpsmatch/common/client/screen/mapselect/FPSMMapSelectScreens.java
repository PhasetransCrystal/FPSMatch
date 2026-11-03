package net.ptcrys.fpsmatch.common.client.screen.mapselect;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.FPSMClient;
import net.ptcrys.fpsmatch.common.client.event.FPSMClientResetEvent;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.studio.*;
import net.ptcrys.fpsmatch.common.packet.mapselect.CloseMapViewC2SPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomActionC2SPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetailS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomInvitationS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomReadyStateS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSummary;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionSnapshotS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.OpenMapSelectionC2SPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Map-room UI router.
 * Studio navigation with client-local intent. Passive packets never open views.
 * Legacy replies have no request sequence, so a new intent for the same room
 * cannot distinguish an earlier reply, even in a new view. No client cancellation
 * rolls back server work.
 */
@Mod.EventBusSubscriber(modid = FPSMatch.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FPSMMapSelectScreens {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MapViewIntent INTENT = new MapViewIntent();
    private static final RoomActionSession ROOM_ACTIONS = new RoomActionSession();

    private FPSMMapSelectScreens() {}

    /** Every view of the same room in this server session shares write authority. */
    public static RoomActionIntent roomActionIntent(MapRoomSummary summary) {
        return ROOM_ACTIONS.intent(summary.gameType(), summary.mapName());
    }

    private static void observeRoomDetail(MapRoomDetail detail) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        Map<UUID, String> roster = new HashMap<>();
        detail.players().forEach(player -> roster.put(player.uuid(), player.teamName()));
        MapRoomSummary summary = detail.summary();
        ROOM_ACTIONS.observeDetail(summary.gameType(), summary.mapName(),
                summary.currentPlayerJoined() || summary.currentPlayerSpectating(),
                detail.readyPlayers().contains(minecraft.player.getUUID()), roster);
    }

    public static boolean isMapSelectionScreen(Screen screen) {
        return screen instanceof StudioMapSelectionScreen;
    }

    /**
     * Explicit local open. Network handlers must use applySelectionIfOpen instead.
     */
    public static void openSelection(MapSelectionSnapshotS2CPacket packet) {
        openSelection(packet, sanitizeParent(Minecraft.getInstance().screen));
    }

    /**
     * Active open with an explicit parent. Pass {@code null} from the ESC pause entry point so
     * closing the browser does not re-open {@link PauseScreen} (which freezes the integrated server).
     */
    public static void openSelection(MapSelectionSnapshotS2CPacket packet, @Nullable Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> openSelection(packet, parent));
            return;
        }
        if (minecraft.screen instanceof StudioMapSelectionScreen screen) {
            INTENT.openSelection(screen);
            navigationStateChanged(screen);
            screen.applySnapshot(packet);
            return;
        }
        try {
            StudioMapSelectionScreen screen = new StudioMapSelectionScreen(packet, parent);
            INTENT.openSelection(screen);
            navigationStateChanged(screen);
            minecraft.setScreen(screen);
            if (minecraft.screen != screen) {
                throw new IllegalStateException("Studio map selection screen was not installed");
            }
        } catch (Throwable error) {
            INTENT.clear();
            minecraft.setScreen(parent);
            LOGGER.error("Failed to open Studio map selection UI", error);
        }
    }

    public static Optional<AcceptanceHandle> openAcceptance(
                                                            MapSelectionSnapshotS2CPacket snapshot,
                                                            MapRoomDetail detail,
                                                            MapRoomToastS2CPacket toast) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(toast, "toast");
        boolean containsDetail = snapshot.maps().stream().anyMatch(summary -> summary.gameType().equals(detail.summary().gameType()) && summary.mapName().equals(detail.summary().mapName()));
        if (!containsDetail || !toast.error()) {
            return Optional.empty();
        }

        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = sanitizeParent(minecraft.screen);
        StudioMapSelectionScreen screen = new StudioMapSelectionScreen(snapshot, parent);
        try {
            INTENT.openSelection(screen);
            navigationStateChanged(screen);
            minecraft.setScreen(screen);
            if (minecraft.screen != screen) {
                INTENT.clear();
                return Optional.empty();
            }
            INTENT.applySelection(screen, false);
            screen.applySnapshot(snapshot);
            screen.applyDetail(detail);
            screen.applyToast();
            return Optional.of(new AcceptanceHandle(screen));
        } catch (RuntimeException failure) {
            if (minecraft.screen == screen) {
                minecraft.setScreen(parent);
            }
            INTENT.clear();
            LOGGER.error("Failed to open Studio map selection acceptance UI", failure);
            return Optional.empty();
        }
    }

    public static final class AcceptanceHandle {

        private final StudioMapSelectionScreen screen;

        private AcceptanceHandle(StudioMapSelectionScreen screen) {
            this.screen = Objects.requireNonNull(screen, "screen");
        }

        public boolean isCurrent() {
            return Minecraft.getInstance().screen == screen;
        }

        public boolean ownsScreen(Screen candidate) {
            return candidate == screen;
        }

        public boolean closeAcceptance() {
            if (!isCurrent()) {
                return false;
            }
            screen.onClose();
            return true;
        }
    }

    /**
     * Active detail update: the room detail and lobby are one unified team-management page.
     * It owns room context, membership actions, team switching and the live roster.
     */
    public static void openDetail(MapRoomDetailS2CPacket packet) {
        observeRoomDetail(packet.detail());
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;

        if (current instanceof FPSMMapDetailChildScreen screen) {
            screen.applyDetail(packet.detail());
            return;
        }

        if (current instanceof StudioMapSelectionScreen list) {
            if (!list.acceptsDetail(packet.detail())) return;
            if (INTENT.consumeDetail(list, room(packet.detail().summary())) != MapViewIntent.Request.DETAIL) return;
            list.applyDetail(packet.detail());
            if (list.consumePendingDetailOpen()) {
                openChild(new StudioMapRoomScreen(packet.detail(), list));
                return;
            }
            restoreListSubscription(list);
            return;
        }
        if (current instanceof StudioMapInvitationScreen invitation && INTENT.consumeDetail(invitation, room(packet.detail().summary())) == MapViewIntent.Request.ACCEPT_INVITATION) {
            FPSMClient.getGlobalData().clearMapRoomInvitation();
            Screen parent = invitation.parentScreen();
            openChild(new StudioMapRoomScreen(packet.detail(), parent instanceof PauseScreen ? null : parent));
        }
    }

    /**
     * Passive detail updates observe pending writes even after the view closes.
     * Presentation is refreshed only when a detail/child screen is already open.
     */
    public static void applyDetailIfOpen(net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail detail) {
        observeRoomDetail(detail);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof FPSMMapDetailChildScreen screen) {
            screen.applyDetail(detail);
        }
    }

    /** Ready observations update session state without opening any screen. */
    public static void applyReadyState(MapRoomReadyStateS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            ROOM_ACTIONS.observeReady(packet.gameType(), packet.mapName(),
                    packet.readyPlayers().contains(minecraft.player.getUUID()));
        }
        if (minecraft.screen instanceof StudioMapRoomScreen screen) {
            screen.applyReadyState(packet.gameType(), packet.mapName(), packet.countdownSeconds(), packet.readyPlayers());
        }
    }

    /**
     * Passive list update: only refresh when the list screen is already open.
     */
    public static void applySelectionIfOpen(MapSelectionSnapshotS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof StudioMapSelectionScreen screen && INTENT.ownsSelection(screen)) {
            MapViewIntent.Room pending = INTENT.pendingRoom(screen);
            if (pending != null && packet.maps().stream().noneMatch(summary -> room(summary).equals(pending))) {
                INTENT.cancel(screen);
                restoreListSubscription(screen);
            }
            INTENT.applySelection(screen, packet.passive());
            screen.applySnapshot(packet);
        }
    }

    public static void openChild(Screen child) {
        INTENT.clear();
        Minecraft.getInstance().setScreen(child);
    }

    public static void openInvitation(MapRoomInvitationS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        INTENT.clear();
        if (minecraft.screen instanceof StudioMapInvitationScreen screen) {
            minecraft.setScreen(new StudioMapInvitationScreen(packet, screen.parentScreen()));
        } else {
            minecraft.setScreen(new StudioMapInvitationScreen(packet, minecraft.screen instanceof PauseScreen ? null : minecraft.screen));
        }
    }

    public static boolean refreshSelection(Screen owner) {
        if (!current(owner) || !canBrowse() || !INTENT.refreshSelection(owner)) return false;
        navigationStateChanged(owner);
        FPSMatch.sendToServer(new OpenMapSelectionC2SPacket());
        return true;
    }

    public static boolean requestDetail(Screen owner, MapRoomSummary target) {
        if (!current(owner) || !canBrowse() || !INTENT.requestDetail(owner, room(target))) return false;
        navigationStateChanged(owner);
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.REQUEST_DETAIL,
                target.gameType(), target.mapName(), null));
        return true;
    }

    public static boolean acceptInvitation(Screen owner, MapRoomInvitationS2CPacket invitation) {
        if (!current(owner) || !INTENT.acceptInvitation(owner, new MapViewIntent.Room(invitation.gameType(), invitation.mapName()))) return false;
        navigationStateChanged(owner);
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.ACCEPT_INVITE,
                invitation.gameType(), invitation.mapName(), null));
        return true;
    }

    public static boolean isWaitingForReply(Screen owner) {
        return current(owner) && INTENT.waiting(owner);
    }

    public static MapViewIntent.Request navigationRequest(Screen owner) {
        return current(owner) ? INTENT.request(owner) : MapViewIntent.Request.NONE;
    }

    /** Returns true once when the local wait expires; the server request is not undone. */
    public static boolean tickPendingRequest(Screen owner) {
        if (!current(owner) || !INTENT.tick(owner)) return false;
        navigationStateChanged(owner);
        restoreListSubscription(owner);
        return true;
    }

    public static void cancelDetailRequest(Screen owner) {
        if (!current(owner)) return;
        if (INTENT.cancel(owner)) {
            navigationStateChanged(owner);
            restoreListSubscription(owner);
        }
    }

    private static void navigationStateChanged(Screen owner) {
        if (owner instanceof StudioMapSelectionScreen list) list.navigationIntentChanged(INTENT.request(owner));
        else if (owner instanceof StudioMapScreen screen) screen.navigationStateChanged();
    }

    public static void applyAccessIfOpen() {
        Screen current = Minecraft.getInstance().screen;
        if (!canBrowse() && current instanceof StudioMapSelectionScreen) INTENT.cancel(current);
        navigationStateChanged(current);
    }

    private static boolean canBrowse() {
        return FPSMClient.getGlobalData().isMapSelectionButtonVisible();
    }

    private static void restoreListSubscription(Screen owner) {
        if (canBrowse() && owner instanceof StudioMapSelectionScreen && INTENT.ownsSelection(owner))
            FPSMatch.sendToServer(new OpenMapSelectionC2SPacket());
    }

    /** End browsing, or explicitly resume a parent list and its server subscription. */
    public static void closeView(Screen owner, @Nullable Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        INTENT.close(owner);
        if (minecraft.screen != owner) return;
        // ACCEPT_INVITE can open a room even when ordinary browsing is disabled.
        // REQUEST_DETAIL and OpenMapSelection cannot restore an older parent in
        // that case, so leave the map UI instead of presenting a stale room/list.
        if (!canBrowse() && (parent instanceof FPSMMapDetailChildScreen || parent instanceof StudioMapSelectionScreen)) {
            parent = null;
        }
        if (parent instanceof StudioMapSelectionScreen list) {
            INTENT.openSelection(list);
            navigationStateChanged(list);
            minecraft.setScreen(list);
            FPSMatch.sendToServer(new OpenMapSelectionC2SPacket());
        } else {
            if (parent instanceof StudioMapRoomScreen room) room.awaitFullDetail();
            minecraft.setScreen(parent instanceof PauseScreen ? null : parent);
            if (parent instanceof StudioMapRoomScreen room && minecraft.player != null) {
                MapRoomSummary summary = room.roomSummary();
                FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.REQUEST_DETAIL,
                        summary.gameType(), summary.mapName(), null));
            } else if (!(parent instanceof FPSMMapDetailChildScreen)) {
                INTENT.clear();
                if (minecraft.player != null) FPSMatch.sendToServer(new CloseMapViewC2SPacket());
            }
        }
    }

    public static void applyToastIfOpen(MapRoomToastS2CPacket packet) {
        Screen current = Minecraft.getInstance().screen;
        // Room toasts normally have requestId=-1. Only known navigation failures
        // end a navigation wait; a generic success never acknowledges a write.
        String key = packet.message().getContents() instanceof TranslatableContents text ? text.getKey() : "";
        boolean navigationFailure = key.equals("gui.fpsm.map_select.action.no_permission") || key.equals("gui.fpsm.map_select.action.map_not_found") || current instanceof StudioMapInvitationScreen && (key.startsWith("commands.fpsm.team.join.failure.") || key.equals("gui.fpsm.map_select.action.cancelled") || key.equals("gui.fpsm.map_select.action.invite.invalid"));
        if (packet.error() && navigationFailure) INTENT.cancel(current);
        if (current instanceof StudioMapRoomScreen room) room.applyToast(packet);
        else if (current instanceof StudioMapSelectionScreen list) list.applyToast();
        else if (current instanceof StudioMapInvitationScreen invitation) invitation.applyToast(packet);
    }

    @SubscribeEvent
    public static void onClientReset(FPSMClientResetEvent event) {
        // Match/room changes also reset HUD statistics. They do not end the
        // connection or cancel an in-flight invitation or membership action.
        if (Minecraft.getInstance().screen instanceof StudioMapRoomScreen room) room.roomActionStateChanged();
        navigationStateChanged(Minecraft.getInstance().screen);
    }

    @SubscribeEvent
    public static void onClientConnected(ClientPlayerNetworkEvent.LoggingIn event) {
        resetConnection();
    }

    @SubscribeEvent
    public static void onClientDisconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        resetConnection();
    }

    private static void resetConnection() {
        INTENT.clear();
        ROOM_ACTIONS.reset();
        if (Minecraft.getInstance().screen instanceof StudioMapRoomScreen room) room.roomActionStateChanged();
        navigationStateChanged(Minecraft.getInstance().screen);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ROOM_ACTIONS.tick();
        if (Minecraft.getInstance().screen instanceof StudioMapRoomScreen room) room.roomActionStateChanged();
    }

    private static boolean current(Screen owner) {
        Minecraft minecraft = Minecraft.getInstance();
        return owner != null && minecraft.screen == owner && minecraft.player != null;
    }

    private static MapViewIntent.Room room(MapRoomSummary summary) {
        return new MapViewIntent.Room(summary.gameType(), summary.mapName());
    }

    @Nullable
    private static Screen sanitizeParent(@Nullable Screen screen) {
        if (screen instanceof PauseScreen || isMapSelectionScreen(screen)) {
            return null;
        }
        return screen;
    }
}
