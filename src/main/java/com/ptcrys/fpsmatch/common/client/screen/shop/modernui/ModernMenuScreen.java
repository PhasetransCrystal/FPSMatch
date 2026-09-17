package com.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import com.ptcrys.fpsmatch.common.client.screen.modernui.ModernScreen;

/** Native controls over a vanilla synchronized container; slot clicks retain vanilla packet/state IDs. */
public abstract class ModernMenuScreen<T extends AbstractContainerMenu> extends ModernScreen implements MenuAccess<T> {

    protected final T menu;

    protected ModernMenuScreen(T menu, Component title) {
        super(title, null);
        this.menu = menu;
    }

    @Override
    public T getMenu() {
        return menu;
    }

    @Override
    public boolean isMenuScreen() {
        return true;
    }

    protected final void clickSlot(int index, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != menu) return;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, index, button, ClickType.PICKUP, mc.player);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu != menu) {
            mc.setScreen(null);
            return;
        }
        refresh();
    }

    @Override
    public void onClose() {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.closeContainer();
    }

    @Override
    public void removed() {
        if (Minecraft.getInstance().player != null) menu.removed(Minecraft.getInstance().player);
        super.removed();
    }
}
