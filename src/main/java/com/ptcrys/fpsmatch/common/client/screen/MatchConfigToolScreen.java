package com.ptcrys.fpsmatch.common.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMGuiTheme;
import com.ptcrys.fpsmatch.common.packet.MatchConfigToolActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.OpenMatchConfigToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MatchConfigToolScreen extends Screen {

    // 统一设计 Token：颜色全部引用 FPSMGuiTheme，消除与主界面割裂的私有色板
    private static final int PANEL_BACKGROUND = FPSMGuiTheme.BG_PANEL;
    private static final int PANEL_BORDER = FPSMGuiTheme.BORDER_INNER;
    private static final int ROW_BACKGROUND = FPSMGuiTheme.ROW_NORMAL;
    private static final int ROW_HOVER = FPSMGuiTheme.ROW_HOVER;
    private static final int TEXT_MAIN = FPSMGuiTheme.TEXT_BODY;
    private static final int TEXT_SUB = FPSMGuiTheme.TEXT_SUB;
    private static final int TEXT_DISABLED = FPSMGuiTheme.TEXT_DISABLED;
    private static final int ROW_HEIGHT = 25;
    private static final int TOP = 28;
    private static final int FIELD_WIDTH = 126;
    private static final int BUTTON_HEIGHT = FPSMGuiTheme.BUTTON_HEIGHT;

    private OpenMatchConfigToolScreenS2CPacket data;
    private final List<EditBox> valueFields = new ArrayList<>();
    private final List<Button> applyButtons = new ArrayList<>();
    private final List<Button> toggleButtons = new ArrayList<>();
    private Button typeButton;
    private Button mapButton;
    private Button refreshButton;
    private int scrollOffset;

    public MatchConfigToolScreen(OpenMatchConfigToolScreenS2CPacket data) {
        super(Component.translatable("gui.fpsm.match_config.title"));
        this.data = data;
    }

    public void applyData(OpenMatchConfigToolScreenS2CPacket data) {
        this.data = data;
        this.scrollOffset = 0;
        rebuildToolWidgets();
    }

    @Override
    protected void init() {
        rebuildToolWidgets();
    }

    private void rebuildToolWidgets() {
        clearWidgets();
        valueFields.clear();
        applyButtons.clear();
        toggleButtons.clear();

        int left = panelLeft();
        int top = TOP;
        this.typeButton = addRenderableWidget(Button.builder(selectedTypeLabel(), button -> cycleType())
                .bounds(left + 64, top + 22, 132, BUTTON_HEIGHT)
                .build());
        this.mapButton = addRenderableWidget(Button.builder(selectedMapLabel(), button -> cycleMap())
                .bounds(left + 250, top + 22, 170, BUTTON_HEIGHT)
                .build());
        this.refreshButton = addRenderableWidget(Button.builder(Component.translatable("gui.fpsm.match_config.refresh"), button -> refresh())
                .bounds(left + panelWidth() - 76, top + 22, 64, BUTTON_HEIGHT)
                .build());

        int listLeft = left + 12;
        int valueX = left + panelWidth() - FIELD_WIDTH - 82;
        int buttonX = left + panelWidth() - 74;
        for (int i = 0; i < data.settings().size(); i++) {
            MapRoomSettingInfo setting = data.settings().get(i);
            int rowY = listTop() + i * ROW_HEIGHT;
            if (setting.type() == MapRoomSettingInfo.SettingType.BOOLEAN) {
                valueFields.add(null);
                applyButtons.add(null);
                Button toggleButton = addRenderableWidget(Button.builder(toggleLabel(Boolean.parseBoolean(setting.value())),
                        button -> toggleSetting(setting, button))
                        .bounds(valueX, rowY + 2, FIELD_WIDTH + 66, BUTTON_HEIGHT)
                        .build());
                toggleButton.active = setting.editable();
                toggleButtons.add(toggleButton);
            } else {
                EditBox field = new EditBox(font, valueX, rowY + 3, FIELD_WIDTH, 18, Component.translatable(setting.translationKey()));
                field.setMaxLength(1024);
                field.setValue(setting.value());
                field.setEditable(setting.editable());
                addRenderableWidget(field);
                valueFields.add(field);

                Button applyButton = addRenderableWidget(Button.builder(Component.translatable("gui.fpsm.match_config.apply"),
                        button -> applySetting(setting, field))
                        .bounds(buttonX, rowY + 2, 62, BUTTON_HEIGHT)
                        .build());
                applyButton.active = setting.editable();
                applyButtons.add(applyButton);
                toggleButtons.add(null);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        valueFields.stream().filter(Objects::nonNull).forEach(EditBox::tick);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = panelLeft();
        int right = left + panelWidth();
        int bottom = height - 24;
        graphics.fill(left, TOP, right, bottom, PANEL_BACKGROUND);
        graphics.fill(left, TOP, right, TOP + 1, PANEL_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
        graphics.fill(left, TOP, left + 1, bottom, PANEL_BORDER);
        graphics.fill(right - 1, TOP, right, bottom, PANEL_BORDER);

        graphics.drawString(font, title, left + 12, TOP + 8, TEXT_MAIN, false);
        graphics.drawString(font, Component.translatable("gui.fpsm.match_config.type"), left + 12, TOP + 28, TEXT_SUB, false);
        graphics.drawString(font, Component.translatable("gui.fpsm.match_config.map"), left + 214, TOP + 28, TEXT_SUB, false);

        int listTop = listTop();
        int listBottom = bottom - 10;
        int visibleRows = visibleRows();
        int maxScroll = maxScroll();
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        Component tooltip = null;
        graphics.enableScissor(left + 8, listTop, right - 8, listBottom);
        for (int i = 0; i < data.settings().size(); i++) {
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            boolean visible = rowY + ROW_HEIGHT > listTop && rowY < listBottom;
            setRowVisible(i, visible, rowY);
            if (!visible) continue;

            MapRoomSettingInfo setting = data.settings().get(i);
            boolean hovered = mouseX >= left + 12 && mouseX <= right - 12 && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;
            graphics.fill(left + 10, rowY, right - 10, rowY + ROW_HEIGHT - 1, hovered ? ROW_HOVER : ROW_BACKGROUND);
            int color = setting.editable() ? TEXT_MAIN : TEXT_DISABLED;
            drawClipped(graphics, Component.translatable(setting.translationKey()), left + 18, rowY + 8, color, 136);
            drawClipped(graphics, Component.translatable("gui.fpsm.match_config.default", setting.defaultValue()), left + 158, rowY + 8, TEXT_SUB, 130);
            if (hovered && mouseX >= left + 18 && mouseX <= left + 154) {
                tooltip = Component.translatable(setting.translationKey() + ".desc");
            }
        }
        graphics.disableScissor();

        super.render(graphics, mouseX, mouseY, partialTick);

        if (data.maps().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.fpsm.match_config.empty_maps"), width / 2, listTop + 36, TEXT_SUB);
        } else if (data.settings().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.fpsm.match_config.empty_settings"), width / 2, listTop + 36, TEXT_SUB);
        }

        if (!data.settings().isEmpty() && maxScroll > 0) {
            int barX = right - 8;
            int barTop = listTop;
            int barHeight = listBottom - listTop;
            int handleHeight = Math.max(18, barHeight * visibleRows / data.settings().size());
            int handleY = barTop + (barHeight - handleHeight) * scrollOffset / maxScroll;
            graphics.fill(barX - 2, barTop, barX, listBottom, 0x552C3640);
            graphics.fill(barX - 2, handleY, barX, handleY + handleHeight, PANEL_BORDER);
        }
        if (tooltip != null) {
            graphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        scrollOffset = Mth.clamp(scrollOffset - (int) scrollY, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (EditBox field : valueFields) {
            if (field != null && field.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        for (EditBox field : valueFields) {
            if (field != null && field.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void setRowVisible(int index, boolean visible, int rowY) {
        if (index >= valueFields.size()) return;
        EditBox field = valueFields.get(index);
        Button applyButton = applyButtons.get(index);
        Button toggleButton = toggleButtons.get(index);
        if (field != null) {
            field.visible = visible;
            field.setY(rowY + 3);
        }
        if (applyButton != null) {
            applyButton.visible = visible;
            applyButton.setY(rowY + 2);
        }
        if (toggleButton != null) {
            toggleButton.visible = visible;
            toggleButton.setY(rowY + 2);
        }
    }

    private void cycleType() {
        List<String> types = data.maps().stream().map(OpenMatchConfigToolScreenS2CPacket.MapEntry::gameType).distinct().toList();
        if (types.isEmpty()) return;
        int index = types.indexOf(data.selectedType());
        String nextType = types.get(index < 0 ? 0 : (index + 1) % types.size());
        String nextMap = data.maps().stream()
                .filter(map -> map.gameType().equals(nextType))
                .findFirst()
                .map(OpenMatchConfigToolScreenS2CPacket.MapEntry::mapName)
                .orElse("");
        select(nextType, nextMap);
    }

    private void cycleMap() {
        List<OpenMatchConfigToolScreenS2CPacket.MapEntry> maps = data.maps().stream()
                .filter(map -> map.gameType().equals(data.selectedType()))
                .toList();
        if (maps.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < maps.size(); i++) {
            if (maps.get(i).mapName().equals(data.selectedMap())) {
                index = i;
                break;
            }
        }
        OpenMatchConfigToolScreenS2CPacket.MapEntry next = maps.get(index < 0 ? 0 : (index + 1) % maps.size());
        select(next.gameType(), next.mapName());
    }

    private void refresh() {
        FPSMatch.sendToServer(new MatchConfigToolActionC2SPacket(MatchConfigToolActionC2SPacket.Action.REFRESH,
                data.selectedType(), data.selectedMap(), "", ""));
    }

    private void select(String gameType, String mapName) {
        FPSMatch.sendToServer(new MatchConfigToolActionC2SPacket(MatchConfigToolActionC2SPacket.Action.SELECT, gameType, mapName, "", ""));
    }

    private void applySetting(MapRoomSettingInfo setting, EditBox field) {
        FPSMatch.sendToServer(new MatchConfigToolActionC2SPacket(MatchConfigToolActionC2SPacket.Action.SET_SETTING,
                data.selectedType(), data.selectedMap(), setting.name(), field.getValue()));
    }

    private void toggleSetting(MapRoomSettingInfo setting, Button button) {
        boolean value = !Boolean.parseBoolean(setting.value());
        button.setMessage(toggleLabel(value));
        FPSMatch.sendToServer(new MatchConfigToolActionC2SPacket(MatchConfigToolActionC2SPacket.Action.SET_SETTING,
                data.selectedType(), data.selectedMap(), setting.name(), String.valueOf(value)));
    }

    private Component selectedTypeLabel() {
        return Component.literal(data.selectedType().isBlank() ? "-" : data.selectedType());
    }

    private Component selectedMapLabel() {
        if (data.selectedMap().isBlank()) {
            return Component.literal("-");
        }
        return data.maps().stream()
                .filter(map -> map.gameType().equals(data.selectedType()) && map.mapName().equals(data.selectedMap()))
                .findFirst()
                .map(map -> Component.literal(map.displayName().isBlank() ? map.mapName() : map.displayName()))
                .orElseGet(() -> Component.literal(data.selectedMap()));
    }

    private Component toggleLabel(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }

    private int panelWidth() {
        return Math.min(560, width - 24);
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int listTop() {
        return TOP + 54;
    }

    private int visibleRows() {
        return Math.max(1, (height - 24 - listTop() - 10) / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, data.settings().size() - visibleRows());
    }

    private void drawClipped(GuiGraphics graphics, Component text, int x, int y, int color, int maxWidth) {
        String value = text.getString();
        if (font.width(value) > maxWidth) {
            value = font.plainSubstrByWidth(value, maxWidth - font.width("...")) + "...";
        }
        graphics.drawString(font, value, x, y, color, false);
    }
}
