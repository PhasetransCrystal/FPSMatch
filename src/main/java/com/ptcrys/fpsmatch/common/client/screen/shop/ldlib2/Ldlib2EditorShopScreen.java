package com.ptcrys.fpsmatch.common.client.screen.shop.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.ModularMenuUiSupport;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorNavigation;
import com.ptcrys.fpsmatch.common.client.screen.EditorShopContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;

/**
 * Thin Forge menu adapter.
 * LDLib2 owns custom panels and slot visuals; AbstractContainerScreen keeps the standard
 * render/mouse chain for carried stack and menu sync.
 */
public final class Ldlib2EditorShopScreen extends AbstractContainerScreen<EditorShopContainer> {
    private static final int OPEN_TIMEOUT_TICKS = 200;

    private Ldlib2ShopEditorUi.View view;
    private ModularUI modularUI;
    private Ldlib2AccessibilityController accessibility;
    private int selectedSlotIndex = -1;
    private boolean openingSlot;
    private int openingTicks;

    public Ldlib2EditorShopScreen(EditorShopContainer container, Inventory inv, Component title) {
        super(container, inv, Component.translatable("gui.fpsm.shop_editor.title"));
        imageWidth = 520;
        imageHeight = Math.max(260, container.getImageHeight());
    }

    @Override
    protected void init() {
        selectedSlotIndex = ShopEditorNavigation.selectionFor(
                menu.getGameType(), menu.getMapName(), menu.getTeamName());
        view = Ldlib2ShopEditorUi.create(
                menu,
                selectedSlotIndex,
                this::selectSlot,
                this::openSlotEditor,
                this::onClose
        );
        modularUI = view.modularUI();
        modularUI.setScreenAndInit(this);
        // XML element ids are queryable only after the UI is attached to the screen.
        view.bind();
        ModularMenuUiSupport.attach(modularUI, menu);
        imageWidth = Math.max(1, Math.round(modularUI.getWidth()));
        imageHeight = Math.max(1, Math.round(modularUI.getHeight()));
        view.applyResponsiveLayout(imageWidth, imageHeight);
        view.setOpeningState(openingSlot, false);
        super.init();
        addRenderableWidget(modularUI.getWidget());
        setFocused(modularUI.getWidget());
        accessibility = new Ldlib2AccessibilityController(modularUI, title);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        releaseModularUi();
        super.resize(minecraft, width, height);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (modularUI != null) {
            ModularMenuUiSupport.syncSlotPositions(menu);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (modularUI != null) {
            modularUI.tick();
        }
        if (openingSlot && ++openingTicks >= OPEN_TIMEOUT_TICKS) {
            openingSlot = false;
            openingTicks = 0;
            if (view != null) {
                view.setOpeningState(false, true);
            }
            if (accessibility != null) {
                accessibility.announce(
                        Component.translatable("gui.fpsm.shop_editor.open.timeout"), true);
                }
        }
    }

    public boolean isSlotOpenPending() {
        return openingSlot;
    }

    public void applySlotOpenFailure(Component message) {
        if (!openingSlot) {
            return;
        }
        openingSlot = false;
        openingTicks = 0;
        if (view != null) {
            view.setOpeningState(false, true);
        }
        if (accessibility != null) {
            accessibility.announce(message, true);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (openingSlot && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        if (accessibility != null) {
            accessibility.updateNarrationState(output);
            return;
        }
        super.updateNarrationState(output);
    }

    private void openSlotEditor(int slotIndex) {
        if (openingSlot || slotIndex < 0 || slotIndex >= menu.slots.size()
                || slotIndex >= menu.getAllSlots().size()
                || menu.getAllSlots().get(slotIndex) == null) {
            return;
        }
        selectSlot(slotIndex);
        openingSlot = true;
        openingTicks = 0;
        if (view != null) {
            view.setOpeningState(true, false);
        }
        if (accessibility != null) {
            accessibility.clearAnnouncement();
        }
        slotClicked(menu.slots.get(slotIndex), slotIndex, 0, ClickType.PICKUP);
    }

    private void selectSlot(int slotIndex) {
        selectedSlotIndex = slotIndex;
        ShopEditorNavigation.rememberSelection(
                menu.getGameType(), menu.getMapName(), menu.getTeamName(), slotIndex);
    }

    @Override
    public void removed() {
        releaseModularUi();
        super.removed();
    }

    private void releaseModularUi() {
        if (modularUI != null) {
            modularUI.onRemoved();
            modularUI = null;
        }
        view = null;
        accessibility = null;
    }

    @Override
    public void onClose() {
        if (openingSlot) {
            return;
        }
        minecraft.player.closeContainer();
        ShopEditorNavigation.returnFromEditor(
                menu.getGameType(), menu.getMapName(), menu.getTeamName());
    }
}
