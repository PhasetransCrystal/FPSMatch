package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.List;
import java.util.UUID;

/**
 * Room management console for match operators.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/map_manage.xml}; this class binds data.
 */
public final class Ldlib2MapManageScreen extends Ldlib2MapChildScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_manage.xml";

    private UIElement commandPanel;
    private MapLobbyTabs tabs;
    private ScrollerView commandScroller;
    private Label commandTitle;
    private Label subtitleLabel;
    private Label permissionLabel;
    private AccessibleButton startButton;
    private AccessibleButton resetButton;
    private AccessibleButton newRoundButton;
    private AccessibleButton cleanupButton;
    private AccessibleButton switchButton;
    private AccessibleButton backButton;
    private Component transientStatus;
    private int transientStatusTicks;
    private boolean bound;

    public Ldlib2MapManageScreen(MapRoomDetail detail, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT),
                Component.translatable("gui.fpsm.map_select.manage.title"), detail, parent);
    }

    @Override
    public void init() {
        super.init();
        bind();
        refreshContent();
        applyResponsiveLayout();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.drawMapIndex(graphics, width, height);
    }

    @Override
    public void tick() {
        super.tick();
        if (transientStatusTicks <= 0) {
            return;
        }
        transientStatusTicks--;
        if (transientStatusTicks == 0) {
            transientStatus = null;
            refreshContent();
            applyResponsiveLayout();
        }
    }

    public void showSettingsSaveSuccess(int changeCount) {
        transientStatus = Component.translatable(
                "gui.fpsm.map_select.settings.save_success",
                Math.max(1, changeCount)
        );
        transientStatusTicks = 100;
        refreshContent();
        if (width > 0 && height > 0) {
            applyResponsiveLayout();
        }
    }

    @Override
    protected void onDetailApplied() {
        refreshContent();
        if (width > 0 && height > 0) {
            applyResponsiveLayout();
        }
    }

    private void bind() {
        if (bound) return;
        UI ui = modularUI.ui;
        tabs = new MapLobbyTabs(ui, "fpsmatch.map_manage", "more", tab -> {
            if (!"more".equals(tab)) openLobbyTab(tab);
        });
        commandScroller = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.commands", ScrollerView.class);
        commandPanel = commandScroller.viewContainer;
        commandScroller.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL)
                .adaptiveWidth(false).adaptiveHeight(false));
        commandTitle = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.commands.title", Label.class);
        subtitleLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.subtitle", Label.class);
        permissionLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.permission", Label.class);
        startButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.start", AccessibleButton.class);
        resetButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.reset", AccessibleButton.class);
        newRoundButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.new_round", AccessibleButton.class);
        cleanupButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.cleanup", AccessibleButton.class);
        switchButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.switch", AccessibleButton.class);
        backButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_manage.back", AccessibleButton.class);
        if (commandPanel == null || commandTitle == null
                || subtitleLabel == null || permissionLabel == null
                || startButton == null || resetButton == null || newRoundButton == null
                || cleanupButton == null || switchButton == null
                || backButton == null) {
            FPSMatch.LOGGER.error("[FPSM UI] map_manage.xml is missing required elements; "
                    + "binding aborted, fallback UI shown (see errors above)");
            return;
        }
        startButton.setOnClick(e -> send(MapRoomActionC2SPacket.Action.DEBUG_START));
        resetButton.setOnClick(e -> send(MapRoomActionC2SPacket.Action.DEBUG_RESET));
        newRoundButton.setOnClick(e -> send(MapRoomActionC2SPacket.Action.DEBUG_NEW_ROUND));
        cleanupButton.setOnClick(e -> send(MapRoomActionC2SPacket.Action.DEBUG_CLEANUP));
        switchButton.setOnClick(e -> send(MapRoomActionC2SPacket.Action.DEBUG_SWITCH));
        backButton.setOnClick(e -> onClose());
        bound = true;
    }

    private void applyResponsiveLayout() {
        if (!bound) {
            return;
        }
        int margin = Math.min(16, Math.max(8, width / 32));
        int availableWidth = Math.max(1, width - margin * 2);
        tabs.layout(width, height);
        absolute(Ldlib2XmlUi.require(modularUI.ui, "fpsmatch.map_manage.header", Label.class),
                margin, 8, availableWidth, 21);
        absolute(subtitleLabel, margin, 32, availableWidth, 13);
        subtitleLabel.setVisible(height >= 300);
        int contentTop = height < 300 ? 61 : 76;
        absolute(commandScroller, margin, contentTop, availableWidth, Math.max(1, height - contentTop - 74));
        absolute(commandTitle, 4, 6, availableWidth - 12, 14);
        int columns = availableWidth < 340 ? 1 : 2;
        int buttonWidth = Math.max(1, (availableWidth - 16 - (columns - 1) * 8) / columns);
        List<AccessibleButton> actions = List.of(startButton, resetButton, newRoundButton, cleanupButton, switchButton);
        layoutButtonGrid(actions, 4, 26, columns, buttonWidth, 26, 8);
        commandPanel.layout(l -> l.widthPercent(100)
                .height(30 + ((actions.size() + columns - 1) / columns) * 34));
        absolute(permissionLabel, margin, height - 70, availableWidth, 28);
        absolute(backButton, margin, height - 34, Math.min(112, availableWidth), 26);
    }

    private static void absolute(UIElement element, int left, int top, int width, int height) {
        if (element == null) {
            return;
        }
        element.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto().left(left).top(top)
                .width(Math.max(1, width)).height(Math.max(1, height)));
    }

    private static void layoutButtonGrid(List<AccessibleButton> buttons, int padding, int top,
                                         int columns, int buttonWidth, int buttonHeight, int gap) {
        for (int i = 0; i < buttons.size(); i++) {
            int index = i;
            int column = index % columns;
            int row = index / columns;
            AccessibleButton button = buttons.get(i);
            if (button == null) {
                continue;
            }
            button.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .rightAuto().bottomAuto()
                    .left(padding + column * (buttonWidth + gap))
                    .top(top + row * (buttonHeight + gap))
                    .width(buttonWidth).height(buttonHeight));
        }
    }

    private void refreshContent() {
        if (!bound) {
            return;
        }
        subtitleLabel.setValue(Component.literal(detail.summary().gameType() + " / " + detail.summary().mapName()));
        boolean op = detail.summary().currentPlayerOp();
        boolean showTransientStatus = op && transientStatus != null;
        permissionLabel.setVisible(!op || showTransientStatus);
        if (!op) {
            permissionLabel.setValue(Component.translatable("gui.fpsm.map_select.manage.no_permission"));
        } else if (showTransientStatus) {
            permissionLabel.setValue(transientStatus);
        }
        permissionLabel.textStyle(style -> style
                .textColor(showTransientStatus ? FPSMMapSelectTheme.SUCCESS : FPSMMapSelectTheme.WARNING));
        setButtonEnabled(startButton, op);
        setButtonEnabled(resetButton, op);
        setButtonEnabled(newRoundButton, op);
        setButtonEnabled(cleanupButton, op);
        setButtonEnabled(switchButton, op);
        tabs.update(op, true);
        setButtonEnabled(backButton, true);
        if (width > 0 && height > 0) {
            applyResponsiveLayout();
        }
    }

    private static void setButtonEnabled(AccessibleButton button, boolean enabled) {
        if (button != null) button.setAvailability(true, enabled);
    }

    private void send(MapRoomActionC2SPacket.Action action) {
        if (!detail.summary().currentPlayerOp()) return;
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(action, detail.summary().gameType(), detail.summary().mapName(), new UUID(0L, 0L)));
    }
}
