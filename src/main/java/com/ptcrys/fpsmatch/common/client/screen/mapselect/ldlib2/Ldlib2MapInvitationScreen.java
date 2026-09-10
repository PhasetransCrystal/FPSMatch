package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleModularUIScreen;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2RenderGuard;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomInvitationS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.UUID;

/**
 * Responsive, keyboard-accessible confirmation layer for an incoming room invitation.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/map_invitation.xml}; this class binds data.
 */
public final class Ldlib2MapInvitationScreen extends AccessibleModularUIScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_invitation.xml";

    private final MapRoomInvitationS2CPacket invitation;
    private final Screen parent;
    private Label systemLabel;
    private UIElement panel;
    private Label titleLabel;
    private Label messageLabel;
    private Label roomLabel;
    private AccessibleButton acceptButton;
    private AccessibleButton rejectButton;
    private boolean bound;

    public Ldlib2MapInvitationScreen(MapRoomInvitationS2CPacket invitation, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT),
                Component.translatable("gui.fpsm.map_select.invitation.title"));
        this.invitation = invitation;
        this.parent = parent;
    }

    public Screen parentScreen() {
        return parent;
    }

    @Override
    public void init() {
        super.init();
        bind();
        applyResponsiveLayout();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.drawMapIndex(graphics, this.width, this.height);
    }

    @Override
    public void onClose() {
        reject();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        try {
            super.render(graphics, mouseX, mouseY, partialTick);
        } catch (ConcurrentModificationException failure) {
            if (!Ldlib2RenderGuard.ignoreConcurrentModification(this, failure)) {
                throw failure;
            }
        }
    }

    private void bind() {
        UI ui = modularUI.ui;
        systemLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invitation.system", Label.class);
        panel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invitation.panel", UIElement.class);
        titleLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invitation.title", Label.class);
        messageLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invitation.message", Label.class);
        roomLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_invitation.room", Label.class);
        acceptButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_invitation.accept", AccessibleButton.class);
        rejectButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_invitation.reject", AccessibleButton.class);
        Component roomIdentity = Component.literal(
                invitation.gameType() + " / " + invitation.mapName());
        if (messageLabel != null) {
            messageLabel.setValue(invitation.message());
        }
        if (roomLabel != null) {
            roomLabel.setValue(roomIdentity);
        }
        if (acceptButton != null) {
            acceptButton.setOnClick(event -> accept());
            acceptButton.setAccessibleState(() -> roomIdentity);
            acceptButton.setAccessibleHint(invitation::message);
        }
        if (rejectButton != null) {
            rejectButton.setOnClick(event -> reject());
            rejectButton.setAccessibleState(() -> roomIdentity);
        }
        bound = true;
    }

    private void applyResponsiveLayout() {
        if (!bound) {
            return;
        }
        int outerMargin = width < 420 ? 8 : 18;
        int panelWidth = Math.max(1, Math.min(420, width - outerMargin * 2));
        boolean stacked = panelWidth < 220 && height >= 200;
        boolean showSystem = height >= 180;
        int topReserve = showSystem ? 24 : 6;
        int bottomReserve = 8;
        int availableHeight = Math.max(1, height - topReserve - bottomReserve);
        int desiredHeight = stacked ? 212 : 180;
        int panelHeight = Math.max(1, Math.min(desiredHeight, availableHeight));
        int panelLeft = Math.max(0, (width - panelWidth) / 2);
        int panelTop = topReserve + Math.max(0, (availableHeight - panelHeight) / 2);
        int inset = panelWidth < 340 ? 12 : 18;

        if (systemLabel != null) {
            systemLabel.setVisible(showSystem);
        }
        absolute(systemLabel, outerMargin + 2, 5,
                Math.max(1, width - outerMargin * 2 - 4), 12);
        absolute(panel, panelLeft, panelTop, panelWidth, panelHeight);
        absolute(titleLabel, inset, 14, Math.max(1, panelWidth - inset * 2), 22);

        int messageTop = 44;
        if (stacked) {
            int rejectTop = Math.max(1, panelHeight - 42);
            int acceptTop = Math.max(1, rejectTop - 34);
            int roomTop = Math.max(messageTop, acceptTop - 25);
            absolute(messageLabel, inset, messageTop,
                    Math.max(1, panelWidth - inset * 2), Math.max(1, roomTop - messageTop - 5));
            absolute(roomLabel, inset, roomTop,
                    Math.max(1, panelWidth - inset * 2), 17);
            absolute(acceptButton, inset, acceptTop,
                    Math.max(1, panelWidth - inset * 2), 28);
            absolute(rejectButton, inset, rejectTop,
                    Math.max(1, panelWidth - inset * 2), 28);
        } else {
            int buttonTop = Math.max(1, panelHeight - 42);
            int roomTop = Math.max(messageTop, buttonTop - 25);
            int gap = 8;
            int buttonWidth = Math.max(1, (panelWidth - inset * 2 - gap) / 2);
            absolute(messageLabel, inset, messageTop,
                    Math.max(1, panelWidth - inset * 2), Math.max(1, roomTop - messageTop - 5));
            absolute(roomLabel, inset, roomTop,
                    Math.max(1, panelWidth - inset * 2), 17);
            absolute(acceptButton, inset, buttonTop, buttonWidth, 28);
            absolute(rejectButton, inset + buttonWidth + gap, buttonTop, buttonWidth, 28);
        }
    }

    private void accept() {
        FPSMClient.getGlobalData().clearMapRoomInvitation();
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(
                MapRoomActionC2SPacket.Action.ACCEPT_INVITE,
                invitation.gameType(), invitation.mapName(), new UUID(0L, 0L)));
        Minecraft.getInstance().setScreen(parent);
    }

    private void reject() {
        FPSMClient.getGlobalData().clearMapRoomInvitation();
        Minecraft.getInstance().setScreen(parent);
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
