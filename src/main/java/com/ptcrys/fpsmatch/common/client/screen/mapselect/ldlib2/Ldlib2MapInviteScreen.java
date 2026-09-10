package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UITemplate;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Responsive, keyboard-accessible player picker for room invitations.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/map_invite.xml}; this class binds data.
 */
public final class Ldlib2MapInviteScreen extends Ldlib2MapChildScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_invite.xml";
    private static final String ROW_LAYOUT = "fpsmatch:ldlib2/ui/rows/invite_row.xml";

    private Label systemLabel;
    private Label headerLabel;
    private Label subtitleLabel;
    private UIElement panel;
    private VirtualScrollerView<MapRoomPlayerInfo> list;
    private Label emptyLabel;
    private AccessibleButton backButton;
    private UITemplate rowTemplate;
    private boolean compact;
    private boolean bound;

    public Ldlib2MapInviteScreen(MapRoomDetail detail, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT),
                Component.translatable("gui.fpsm.map_select.invite.title"), detail, parent);
    }

    @Override
    public void init() {
        super.init();
        bind();
        applyResponsiveLayout();
        refreshContent();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.drawMapIndex(graphics, this.width, this.height);
    }

    @Override
    protected void onDetailApplied() {
        if (bound) {
            refreshContent();
        }
    }

    private void bind() {
        UI ui = modularUI.ui;
        systemLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invite.system", Label.class);
        headerLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invite.header", Label.class);
        subtitleLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invite.subtitle", Label.class);
        panel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invite.panel", UIElement.class);
        emptyLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invite.empty", Label.class);
        backButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_invite.back", AccessibleButton.class);
        @SuppressWarnings("unchecked")
        VirtualScrollerView<MapRoomPlayerInfo> roomList =
                (VirtualScrollerView<MapRoomPlayerInfo>) Ldlib2XmlUi.require(
                        ui, "fpsmatch.map_invite.list", VirtualScrollerView.class);
        list = roomList;
        rowTemplate = UITemplate.of(Ldlib2XmlUi.loadUi(ROW_LAYOUT).rootElement);
        if (list != null) {
            list.setItemUIProvider(this::playerRow);
        }
        if (backButton != null) {
            backButton.setOnClick(event -> onClose());
            backButton.setAccessibleState(() -> this.detail.availableInviteTargets().isEmpty()
                    ? Component.translatable("gui.fpsm.map_select.invite.empty")
                    : Component.empty());
        }
        bound = true;
    }

    private void refreshContent() {
        if (!bound) {
            return;
        }
        if (subtitleLabel != null) {
            subtitleLabel.setValue(Component.literal(
                    detail.summary().gameType() + " / " + detail.summary().mapName()));
        }
        List<MapRoomPlayerInfo> targets = detail.availableInviteTargets();
        boolean empty = targets.isEmpty();
        if (list != null) {
            list.setItems(targets);
            list.setVisible(!empty);
            list.refreshVisibleItems();
        }
        if (emptyLabel != null) {
            emptyLabel.setVisible(empty);
        }
    }

    private UIElement playerRow(MapRoomPlayerInfo player) {
        UIElement row = rowTemplate.copy().createUI().rootElement;
        row.setId("fpsmatch.map_invite.row." + player.uuid());
        row.layout(layout -> layout.widthPercent(100).height(compact ? 46 : 34));

        AccessibleButton invite = row.selectId("invite", AccessibleButton.class).findFirst().orElse(null);
        if (invite == null) {
            FPSMatch.LOGGER.error("[FPSM UI] invite row fragment is missing #invite button");
            return row;
        }
        invite.noText();
        invite.setId("fpsmatch.map_invite.btn." + player.uuid());
        invite.setAccessibleName(() -> Component.translatable("gui.fpsm.map_select.invite")
                .copy().append(" ").append(player.name()));
        invite.setAccessibleState(() -> Component.translatable("gui.fpsm.map_select.online"));
        invite.setOnClick(event -> sendInvite(player.uuid()));
        if (compact) {
            invite.addClass("compact");
        } else {
            invite.removeClass("compact");
        }

        Label name = row.selectId("name", Label.class).findFirst().orElse(null);
        if (name != null) {
            name.setId("fpsmatch.map_invite.name." + player.uuid());
            name.setValue(Component.literal(player.name()));
        }
        Label online = row.selectId("online", Label.class).findFirst().orElse(null);
        if (online != null) {
            online.setId("fpsmatch.map_invite.online." + player.uuid());
        }
        Label action = row.selectId("action", Label.class).findFirst().orElse(null);
        if (action != null) {
            action.setId("fpsmatch.map_invite.action." + player.uuid());
        }
        return row;
    }

    private void sendInvite(UUID target) {
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(
                MapRoomActionC2SPacket.Action.INVITE,
                detail.summary().gameType(),
                detail.summary().mapName(),
                target));
    }

    private void applyResponsiveLayout() {
        if (!bound) {
            return;
        }
        compact = width < 420 || height < 270;
        int margin = compact ? 8 : 16;
        int contentWidth = Math.max(1, width - margin * 2);
        int panelTop = compact ? 52 : 58;
        int footerHeight = compact ? 38 : 46;
        int panelHeight = Math.max(1, height - panelTop - footerHeight);

        absolute(systemLabel, margin + 2, 3, contentWidth - 4, 10);
        absolute(headerLabel, margin + 2, 14, contentWidth - 4, 18);
        absolute(subtitleLabel, margin + 2, 34, contentWidth - 4, 14);
        absolute(panel, margin, panelTop, contentWidth, panelHeight);
        absolute(emptyLabel, 12, 16, Math.max(1, contentWidth - 24), 32);
        absolute(list, 6, 6, Math.max(1, contentWidth - 12), Math.max(1, panelHeight - 12));
        absolute(backButton, margin, height - footerHeight + 7,
                Math.min(112, contentWidth), 26);

        if (list != null) {
            list.virtualScrollerViewStyle(style -> style
                    .estimatedItemHeight(compact ? 48f : 36f)
                    .overscanPixels(48));
            list.refreshVisibleItems();
        }
    }

    private static void absolute(UIElement element, int left, int top, int width, int height) {
        if (element == null) {
            return;
        }
        element.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto().left(left).top(top)
                .width(Math.max(1, width)).height(Math.max(1, height)));
    }
}
