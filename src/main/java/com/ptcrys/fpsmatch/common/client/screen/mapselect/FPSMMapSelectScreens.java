package com.ptcrys.fpsmatch.common.client.screen.mapselect;

import com.mojang.logging.LogUtils;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui.*;
import com.ptcrys.fpsmatch.common.client.screen.FPSMTeamActionScreen;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetailS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomInvitationS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionSnapshotS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Map-room UI router.
 * Product open path always uses Modern UI screens.
 */
public final class FPSMMapSelectScreens {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FPSMMapSelectScreens() {
    }

    public static boolean isMapSelectionScreen(Screen screen) {
        return screen instanceof ModernMapSelectionScreen;
    }

    /**
     * Active open/refresh: apply into an already-open Modern UI browser, otherwise open a new one.
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
        if (minecraft.screen instanceof ModernMapSelectionScreen screen) {
            screen.applySnapshot(packet);
            return;
        }
        try {
            minecraft.setScreen(new ModernMapSelectionScreen(packet, parent));
            if (!(minecraft.screen instanceof ModernMapSelectionScreen)) {
                throw new IllegalStateException("Modern UI map selection screen was not installed");
            }
        } catch (Throwable error) {
            minecraft.setScreen(parent);
            LOGGER.error("Failed to open Modern UI map selection UI", error);
        }
    }

    public static Optional<AcceptanceHandle> openAcceptance(
            MapSelectionSnapshotS2CPacket snapshot,
            MapRoomDetail detail,
            MapRoomToastS2CPacket toast
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(toast, "toast");
        boolean containsDetail = snapshot.maps().stream().anyMatch(summary ->
                summary.gameType().equals(detail.summary().gameType())
                        && summary.mapName().equals(detail.summary().mapName())
        );
        if (!containsDetail || !toast.error()) {
            return Optional.empty();
        }

        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = sanitizeParent(minecraft.screen);
        ModernMapSelectionScreen screen =
                new ModernMapSelectionScreen(snapshot, parent);
        try {
            minecraft.setScreen(screen);
            if (minecraft.screen != screen) {
                return Optional.empty();
            }
            screen.applySnapshot(snapshot);
            screen.applyDetail(detail);
            screen.applyToast();
            return Optional.of(new AcceptanceHandle(screen));
        } catch (RuntimeException failure) {
            if (minecraft.screen == screen) {
                minecraft.setScreen(parent);
            }
            LOGGER.error("Failed to open Modern UI map selection acceptance UI", failure);
            return Optional.empty();
        }
    }

    public static final class AcceptanceHandle {
        private final ModernMapSelectionScreen screen;

        private AcceptanceHandle(ModernMapSelectionScreen screen) {
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
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;

        if (current instanceof FPSMMapDetailChildScreen screen) {
            screen.applyDetail(packet.detail());
            return;
        }

        if (current instanceof ModernMapSelectionScreen list) {
            if (!list.acceptsDetail(packet.detail())) return;
            list.applyDetail(packet.detail());
            if (list.consumePendingDetailOpen()) {
                openChild(new ModernMapRoomScreen(packet.detail(), list));
                return;
            }
            return;
        }

        openChild(new ModernMapRoomScreen(packet.detail(), sanitizeParent(current)));
    }

    /**
     * Passive detail update: only refresh when a detail/child screen is already open.
     */
    public static void applyDetailIfOpen(com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail detail) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof FPSMMapDetailChildScreen screen) {
            screen.applyDetail(detail);
        }
    }

    /**
     * Passive list update: only refresh when the list screen is already open.
     */
    public static void applySelectionIfOpen(MapSelectionSnapshotS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ModernMapSelectionScreen screen) {
            screen.applySnapshot(packet);
        }
    }

    public static void openChild(Screen child) {
        Minecraft.getInstance().setScreen(child);
    }

    public static void openInvitation(MapRoomInvitationS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ModernMapInvitationScreen screen) {
            minecraft.setScreen(new ModernMapInvitationScreen(packet, screen.parentScreen()));
        } else {
            minecraft.setScreen(new ModernMapInvitationScreen(packet, minecraft.screen));
        }
    }

    @Nullable
    private static Screen sanitizeParent(@Nullable Screen screen) {
        if (screen instanceof PauseScreen || isMapSelectionScreen(screen)) {
            return null;
        }
        return screen;
    }
}
