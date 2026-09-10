package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapDetailChildScreen;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleModularUIScreen;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2RenderGuard;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ConcurrentModificationException;
import java.util.Objects;

/** Shared lifecycle for LDLib2 map-room child pages. */
public abstract class Ldlib2MapChildScreen extends AccessibleModularUIScreen implements FPSMMapDetailChildScreen {
    protected final Screen parent;
    protected MapRoomDetail detail;

    protected Ldlib2MapChildScreen(ModularUI ui, Component title, MapRoomDetail detail, Screen parent) {
        super(ui, title);
        this.detail = Objects.requireNonNull(detail, "detail");
        this.parent = parent;
    }

    @Override
    public void applyDetail(MapRoomDetail detail) {
        MapRoomDetail next = Objects.requireNonNull(detail, "detail");
        // A successful action sends an authoritative detail immediately and the
        // subscription may send the same snapshot on the next server tick. Avoid
        // rebuilding the LDLib2 tree for an unchanged snapshot.
        if (next.equals(this.detail)) {
            return;
        }
        this.detail = next;
        onDetailApplied();
    }

    protected void onDetailApplied() {
    }

    @Override
    public void onClose() {
        if (parent instanceof FPSMMapDetailChildScreen screen) {
            screen.applyDetail(detail);
        }
        Minecraft.getInstance().setScreen(parent);
    }

    protected void openLobbyTab(String tab) {
        if ("more".equals(tab) && !detail.summary().currentPlayerOp()) return;
        Ldlib2TeamManageScreen lobby = this instanceof Ldlib2TeamManageScreen screen ? screen
                : parent instanceof Ldlib2TeamManageScreen screen ? screen
                : new Ldlib2TeamManageScreen(detail, parent);
        lobby.applyDetail(detail);
        Screen destination = switch (tab) {
            case "settings" -> new Ldlib2MapSettingsScreen(detail, lobby);
            case "more" -> new Ldlib2MapManageScreen(detail, lobby);
            default -> {
                lobby.showTab(tab);
                yield lobby;
            }
        };
        Minecraft.getInstance().setScreen(destination);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.draw(graphics, this.width, this.height);
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

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        modularUI.getWidget().mouseMoved(mouseX, mouseY);
    }

    /**
     * LDLib2 normally routes wheel input through the element hovered by the real cursor. The
     * process-local agent (and a few screen stacks) can provide coordinates without updating that
     * hover cache, so dispatch the same event against the hit-tested element as a fallback.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        boolean handled = super.mouseScrolled(mouseX, mouseY, scrollY);
        var hit = modularUI.ui.rootElement.hitTest(
                mouseX - modularUI.getLeftPos(), mouseY - modularUI.getTopPos()
        );
        UIElement target = hit == null ? null : hit.getA();
        if (handled || target == null || target == modularUI.getLastHoveredElement()) {
            return handled;
        }
        UIEvent event = UIEvent.create("mouseWheel");
        event.x = (float) (mouseX - modularUI.getLeftPos());
        event.y = (float) (mouseY - modularUI.getTopPos());
        event.deltaX = 0;
        event.deltaY = (float) scrollY;
        event.target = target;
        UIEventDispatcher.dispatchEvent(event);
        return event.hasHandler;
    }
}
