package com.ptcrys.fpsmatch.common.client.screen.shop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.shop.ShopConfigToolActionC2SPacket;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Client-only navigation state for the server-owned shop editor menu chain. */
public final class ShopEditorNavigation {

    private static final long SESSION_TIMEOUT_NANOS = Duration.ofMinutes(30).toNanos();

    private static Session session;

    private ShopEditorNavigation() {}

    public static void beginMapRoom(
                                    Supplier<Screen> returnScreen,
                                    String gameType,
                                    String mapName,
                                    String teamName) {
        begin(Source.MAP_ROOM, Objects.requireNonNull(returnScreen, "returnScreen"),
                gameType, mapName, teamName);
    }

    public static void beginConfigTool(String gameType, String mapName, String teamName) {
        begin(Source.CONFIG_TOOL, () -> null, gameType, mapName, teamName);
    }

    public static int selectionFor(String gameType, String mapName, String teamName) {
        Session current = current(gameType, mapName, teamName);
        return current == null ? -1 : current.selectedSlot();
    }

    public static void rememberSelection(
                                         String gameType, String mapName, String teamName, int selectedSlot) {
        Session current = current(gameType, mapName, teamName);
        if (current != null) {
            session = current.withSelection(selectedSlot);
        }
    }

    public static void returnFromEditor(String gameType, String mapName, String teamName) {
        Session current = current(gameType, mapName, teamName);
        session = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null) {
            minecraft.setScreen(null);
            return;
        }
        if (current.source() == Source.MAP_ROOM) {
            minecraft.setScreen(current.returnScreen().get());
            return;
        }
        minecraft.setScreen(null);
        FPSMatch.sendToServer(new ShopConfigToolActionC2SPacket(
                ShopConfigToolActionC2SPacket.Action.REFRESH,
                gameType,
                mapName));
    }

    public static void clear() {
        session = null;
    }

    private static void begin(
                              Source source,
                              Supplier<Screen> returnScreen,
                              String gameType,
                              String mapName,
                              String teamName) {
        Minecraft minecraft = Minecraft.getInstance();
        session = new Session(
                source,
                returnScreen,
                minecraft.getConnection(),
                Objects.requireNonNull(gameType, "gameType"),
                Objects.requireNonNull(mapName, "mapName"),
                Objects.requireNonNull(teamName, "teamName"),
                -1,
                System.nanoTime());
    }

    private static Session current(String gameType, String mapName, String teamName) {
        Session current = session;
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null || current.connection() == null || current.connection() != minecraft.getConnection() || System.nanoTime() - current.touchedAtNanos() > SESSION_TIMEOUT_NANOS || !current.matches(gameType, mapName, teamName)) {
            session = null;
            return null;
        }
        return current;
    }

    private enum Source {
        MAP_ROOM,
        CONFIG_TOOL
    }

    private record Session(
                           Source source,
                           Supplier<Screen> returnScreen,
                           Object connection,
                           String gameType,
                           String mapName,
                           String teamName,
                           int selectedSlot,
                           long touchedAtNanos) {

        private boolean matches(String gameType, String mapName, String teamName) {
            return this.gameType.equals(gameType) && this.mapName.equals(mapName) && this.teamName.equals(teamName);
        }

        private Session withSelection(int selectedSlot) {
            return new Session(source, returnScreen, connection, gameType, mapName, teamName,
                    selectedSlot, System.nanoTime());
        }
    }
}
