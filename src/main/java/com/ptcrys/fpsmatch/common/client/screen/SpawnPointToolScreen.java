package com.ptcrys.fpsmatch.common.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMGuiTheme;
import com.ptcrys.fpsmatch.common.packet.OpenSpawnPointToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.SpawnPointToolActionC2SPacket;
import com.ptcrys.fpsmatch.core.data.SpawnPointData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SpawnPointToolScreen extends Screen {

    private static final int PANEL_WIDTH = 326;
    private static final int PANEL_HEIGHT = 220;
    // 统一设计 Token：遮罩/面板/边框全部引用 FPSMGuiTheme（原金色边框改为主题中性边框）
    private static final int SCREEN_OVERLAY = FPSMGuiTheme.BG_SHADOW;
    private static final int PANEL_BACKGROUND = FPSMGuiTheme.BG_PANEL;
    private static final int PANEL_BORDER = FPSMGuiTheme.BORDER_INNER;

    private List<String> availableTypes;
    private List<String> availableMaps;
    private List<String> availableTeams;
    private List<SpawnPointData> spawnPoints;
    private String selectedType;
    private String selectedMap;
    private String selectedTeam;
    private int selectedIndex;

    private Button typeButton;
    private Button mapButton;
    private Button teamButton;
    private Button prevButton;
    private Button nextButton;
    private Button deleteButton;
    private Button clearButton;

    public SpawnPointToolScreen(OpenSpawnPointToolScreenS2CPacket data) {
        super(Component.translatable("gui.fpsm.spawn_point_tool.title"));
        this.availableTypes = new ArrayList<>(data.availableTypes());
        this.availableMaps = new ArrayList<>(data.availableMaps());
        this.availableTeams = new ArrayList<>(data.availableTeams());
        this.spawnPoints = new ArrayList<>(data.spawnPoints());
        this.selectedType = data.selectedType();
        this.selectedMap = data.selectedMap();
        this.selectedTeam = data.selectedTeam();
        this.selectedIndex = data.selectedIndex();
    }

    @Override
    protected void init() {
        int left = 18;
        int top = Math.max(18, (this.height - PANEL_HEIGHT) / 2);

        this.typeButton = this.addRenderableWidget(new Button.Builder(Component.empty(), button -> cycleType())
                .pos(left + 124, top + 24)
                .size(184, 20)
                .build());
        this.mapButton = this.addRenderableWidget(new Button.Builder(Component.empty(), button -> cycleMap())
                .pos(left + 124, top + 54)
                .size(184, 20)
                .build());
        this.teamButton = this.addRenderableWidget(new Button.Builder(Component.empty(), button -> cycleTeam())
                .pos(left + 124, top + 84)
                .size(184, 20)
                .build());

        this.prevButton = this.addRenderableWidget(new Button.Builder(Component.literal("<"), button -> stepIndex(-1))
                .pos(left + 124, top + 114)
                .size(24, 20)
                .build());
        this.nextButton = this.addRenderableWidget(new Button.Builder(Component.literal(">"), button -> stepIndex(1))
                .pos(left + 284, top + 114)
                .size(24, 20)
                .build());

        this.deleteButton = this.addRenderableWidget(new Button.Builder(Component.translatable("gui.fpsm.spawn_point_tool.delete"), button -> sendAction(SpawnPointToolActionC2SPacket.Action.DELETE_SELECTED))
                .pos(left + 18, top + 170)
                .size(140, 20)
                .build());
        this.clearButton = this.addRenderableWidget(new Button.Builder(Component.translatable("gui.fpsm.spawn_point_tool.clear"), button -> sendAction(SpawnPointToolActionC2SPacket.Action.CLEAR_TEAM))
                .pos(left + 168, top + 170)
                .size(140, 20)
                .build());
        var debugData = FPSMClient.getGlobalData().getDebugData();
        this.addRenderableWidget(new Button.Builder(Component.translatable(
                debugData.isVisible() ? "gui.fpsm.preview.hide" : "gui.fpsm.preview.show"), button -> {
                    debugData.toggleVisibility();
                    button.setMessage(Component.translatable(debugData.isVisible() ? "gui.fpsm.preview.hide" : "gui.fpsm.preview.show"));
                }).pos(left + 18, top + 194).size(140, 20).build());
        this.addRenderableWidget(new Button.Builder(Component.translatable("gui.fpsm.close"), button -> onClose())
                .pos(left + 168, top + 194)
                .size(140, 20)
                .build());

        updateButtonLabels();
    }

    public void applyData(OpenSpawnPointToolScreenS2CPacket data) {
        this.availableTypes = new ArrayList<>(data.availableTypes());
        this.availableMaps = new ArrayList<>(data.availableMaps());
        this.availableTeams = new ArrayList<>(data.availableTeams());
        this.spawnPoints = new ArrayList<>(data.spawnPoints());
        this.selectedType = data.selectedType();
        this.selectedMap = data.selectedMap();
        this.selectedTeam = data.selectedTeam();
        this.selectedIndex = data.selectedIndex();
        updateButtonLabels();
    }

    @Override
    public void onClose() {
        sendAction(SpawnPointToolActionC2SPacket.Action.SAVE_SELECTIONS);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int left = 18;
        int top = Math.max(18, (this.height - PANEL_HEIGHT) / 2);
        guiGraphics.fill(0, 0, this.width, this.height, SCREEN_OVERLAY);
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BACKGROUND);
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + 1, PANEL_BORDER);
        guiGraphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BORDER);
        guiGraphics.fill(left, top, left + 1, top + PANEL_HEIGHT, PANEL_BORDER);
        guiGraphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BORDER);

        guiGraphics.drawString(this.font, this.title, left + 10, top + 8, 0xFFFFFF, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.spawn_point_tool.type"), left + 12, top + 30, 0xF1D9B0, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.spawn_point_tool.map"), left + 12, top + 60, 0xF1D9B0, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.spawn_point_tool.team"), left + 12, top + 90, 0xF1D9B0, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.spawn_point_tool.count", this.spawnPoints.size()), left + 12, top + 120, 0xFFFFFF, false);
        guiGraphics.drawString(this.font, currentPointLabel(), left + 160, top + 120, 0xD7E3EA, false);
        guiGraphics.drawString(this.font, currentPointDetail(), left + 12, top + 144, 0xA4C4D3, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void cycleType() {
        if (availableTypes.isEmpty()) {
            return;
        }
        int currentIndex = availableTypes.indexOf(selectedType);
        int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % availableTypes.size();
        this.selectedType = availableTypes.get(nextIndex);
        this.selectedMap = "";
        this.selectedTeam = "";
        sendAction(SpawnPointToolActionC2SPacket.Action.REFRESH);
    }

    private void cycleMap() {
        if (availableMaps.isEmpty()) {
            return;
        }
        int currentIndex = availableMaps.indexOf(selectedMap);
        int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % availableMaps.size();
        this.selectedMap = availableMaps.get(nextIndex);
        this.selectedTeam = "";
        sendAction(SpawnPointToolActionC2SPacket.Action.REFRESH);
    }

    private void cycleTeam() {
        if (availableTeams.isEmpty()) {
            return;
        }
        int currentIndex = availableTeams.indexOf(selectedTeam);
        int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % availableTeams.size();
        this.selectedTeam = availableTeams.get(nextIndex);
        sendAction(SpawnPointToolActionC2SPacket.Action.REFRESH);
    }

    private void stepIndex(int offset) {
        if (spawnPoints.isEmpty()) {
            selectedIndex = -1;
        } else {
            int base = selectedIndex < 0 ? 0 : selectedIndex;
            selectedIndex = Math.max(0, Math.min(base + offset, spawnPoints.size() - 1));
        }
        updateButtonLabels();
    }

    private void updateButtonLabels() {
        if (this.typeButton == null) {
            return;
        }
        this.typeButton.setMessage(Component.literal(selectedType.isBlank() ? "-" : selectedType));
        this.mapButton.setMessage(Component.literal(selectedMap.isBlank() ? "-" : selectedMap));
        this.teamButton.setMessage(Component.literal(selectedTeam.isBlank() ? "-" : selectedTeam));
        boolean hasPoints = !spawnPoints.isEmpty();
        this.prevButton.active = hasPoints;
        this.nextButton.active = hasPoints;
        this.deleteButton.active = hasPoints;
        this.clearButton.active = hasPoints;
    }

    private Component currentPointLabel() {
        if (spawnPoints.isEmpty() || selectedIndex < 0 || selectedIndex >= spawnPoints.size()) {
            return Component.translatable("gui.fpsm.spawn_point_tool.current", "-");
        }
        return Component.translatable("gui.fpsm.spawn_point_tool.current", (selectedIndex + 1) + "/" + spawnPoints.size());
    }

    private Component currentPointDetail() {
        if (spawnPoints.isEmpty() || selectedIndex < 0 || selectedIndex >= spawnPoints.size()) {
            return Component.translatable("gui.fpsm.spawn_point_tool.no_point");
        }
        SpawnPointData data = spawnPoints.get(selectedIndex);
        return Component.literal(String.format("X %.1f Y %.1f Z %.1f  Yaw %.1f Pitch %.1f",
                data.getX(), data.getY(), data.getZ(), data.getYaw(), data.getPitch()));
    }

    private void sendAction(SpawnPointToolActionC2SPacket.Action action) {
        FPSMatch.sendToServer(new SpawnPointToolActionC2SPacket(
                action,
                this.selectedType,
                this.selectedMap,
                this.selectedTeam,
                this.selectedIndex));
    }
}
