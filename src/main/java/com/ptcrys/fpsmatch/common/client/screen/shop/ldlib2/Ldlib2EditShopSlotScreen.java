package com.ptcrys.fpsmatch.common.client.screen.shop.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.EditShopSlotMenu;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.ModularMenuUiSupport;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Theme;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.shop.ldlib2.Ldlib2EditShopSlotUi;
import com.ptcrys.fpsmatch.common.client.screen.shop.ldlib2.ShopEditorWidgetCatalog;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.OpenShopEditorC2SPacket;
import com.ptcrys.fpsmatch.common.packet.shop.SaveSlotDataC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * Thin Forge menu adapter.
 * LDLib2 owns custom panels and slot visuals; AbstractContainerScreen keeps the standard
 * render/mouse chain for inventory, carried stack and menu sync.
 */
public final class Ldlib2EditShopSlotScreen extends AbstractContainerScreen<EditShopSlotMenu> {
    private static final int RETURN_TIMEOUT_TICKS = 200;

    private ModularUI modularUI;
    private Ldlib2EditShopSlotUi.View view;
    private Ldlib2AccessibilityController accessibility;
    private Ldlib2EditShopSlotUi.Draft resizeDraft;
    private Button saveButton;
    private Button closeButton;
    private Label statusLabel;
    private ItemStack originalItem = ItemStack.EMPTY;
    private int originalAmmo;
    private int originalPrice;
    private int originalGroup;
    private boolean saving;
    private int savingTicks;
    private boolean failureVisible;
    private boolean baselineCaptured;
    private int lateResultTicks;
    private boolean returningToShop;
    private int returningTicks;
    private DiscardSnapshot discardConfirmation;

    public Ldlib2EditShopSlotScreen(EditShopSlotMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.translatable("gui.fpsm.edit_shop_slot.title"));
        imageWidth = 360;
        imageHeight = 250;
    }

    @Override
    protected void init() {
        if (!baselineCaptured) {
            captureBaseline();
            baselineCaptured = true;
        }
        view = Ldlib2EditShopSlotUi.create(
                menu, this::requestSave, this::returnToShop, this::copyHeldItem);
        modularUI = view.modularUI();
        modularUI.setScreenAndInit(this);
        // XML element ids are queryable only after the UI is attached to the screen.
        view.bind();
        view.restoreDraft(resizeDraft);
        resizeDraft = null;
        ModularMenuUiSupport.attach(modularUI, menu);
        imageWidth = Math.max(1, Math.round(modularUI.getWidth()));
        imageHeight = Math.max(1, Math.round(modularUI.getHeight()));
        view.applyResponsiveLayout(imageWidth, imageHeight);
        super.init();
        addRenderableWidget(modularUI.getWidget());
        setFocused(modularUI.getWidget());
        saveButton = view.saveButton();
        statusLabel = view.statusLabel();
        closeButton = view.closeButton();
        accessibility = new Ldlib2AccessibilityController(modularUI, title);
        refreshEditState();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (view != null) {
            resizeDraft = view.draft();
        }
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
        if (saving) {
            savingTicks++;
            if (savingTicks >= 200) {
                saving = false;
                lateResultTicks = 40;
                failureVisible = true;
                setStatus("gui.fpsm.shop_editor.save.timeout", FPSMLdlib2Theme.DANGER);
                refreshButtons();
            }
        } else if (returningToShop) {
            if (++returningTicks >= RETURN_TIMEOUT_TICKS) {
                returningToShop = false;
                returningTicks = 0;
                failureVisible = true;
                setStatus("gui.fpsm.shop_editor.return.timeout", FPSMLdlib2Theme.DANGER);
                refreshButtons();
            }
        } else {
            if (lateResultTicks > 0) {
                lateResultTicks--;
            }
            if (discardConfirmation != null && !discardConfirmation.matches(menu, currentDraft())) {
                discardConfirmation = null;
            }
            if (!failureVisible && discardConfirmation == null) {
                refreshEditState();
            }
        }
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        if (accessibility != null) {
            accessibility.updateNarrationState(output);
            return;
        }
        super.updateNarrationState(output);
    }

    public boolean isSavePending() {
        return saving;
    }

    public boolean isSaveResultRelevant() {
        return saving || lateResultTicks > 0;
    }

    public boolean isReturnPending() {
        return returningToShop;
    }

    public void applyReturnFailure(Component message) {
        if (!returningToShop) {
            return;
        }
        returningToShop = false;
        returningTicks = 0;
        failureVisible = true;
        statusLabel.setValue(message);
        statusLabel.textStyle(style -> style.textColor(FPSMLdlib2Theme.DANGER));
        refreshButtons();
        if (accessibility != null) {
            accessibility.announce(message, true);
        }
    }

    public void applySaveResult(MapRoomToastS2CPacket result) {
        if (!isSaveResultRelevant()) {
            return;
        }
        saving = false;
        savingTicks = 0;
        lateResultTicks = 0;
        if (result.error()) {
            failureVisible = true;
            statusLabel.setValue(result.message());
            statusLabel.textStyle(style -> style.textColor(FPSMLdlib2Theme.DANGER));
            refreshButtons();
            return;
        }
        failureVisible = false;
        discardConfirmation = null;
        captureBaseline();
        statusLabel.setValue(result.message());
        statusLabel.textStyle(style -> style.textColor(FPSMLdlib2Theme.SUCCESS));
        refreshButtons();
        openShopEditor();
    }

    private void requestSave() {
        if (saving || returningToShop || !isDirty() || view == null || !view.inputValid()) {
            return;
        }
        saving = true;
        savingTicks = 0;
        lateResultTicks = 0;
        failureVisible = false;
        discardConfirmation = null;
        setStatus("gui.fpsm.shop_editor.state.saving", FPSMLdlib2Theme.WARNING);
        refreshButtons();
        FPSMatch.sendToServer(new SaveSlotDataC2SPacket(
                menu.getAmmo(), menu.getPrice(), menu.getGroupId()
        ));
    }

    private void returnToShop() {
        if (saving || returningToShop) {
            return;
        }
        if ((isDirty() || (view != null && !view.inputValid()))
                && (discardConfirmation == null
                || !discardConfirmation.matches(menu, currentDraft()))) {
            discardConfirmation = DiscardSnapshot.capture(menu, currentDraft());
            setStatus("gui.fpsm.shop_editor.discard.confirm", FPSMLdlib2Theme.DANGER);
            closeButton.setText(Component.translatable("gui.fpsm.shop_editor.discard.button"));
            return;
        }
        openShopEditor();
    }

    private void openShopEditor() {
        returningToShop = true;
        returningTicks = 0;
        discardConfirmation = null;
        setStatus("gui.fpsm.shop_editor.state.opening", FPSMLdlib2Theme.WARNING);
        refreshButtons();
        FPSMatch.sendToServer(new OpenShopEditorC2SPacket(
                menu.getGameType(), menu.getMapName(), menu.getTeamName()));
    }

    private void captureBaseline() {
        originalItem = menu.slots.get(0).getItem().copy();
        originalAmmo = menu.getAmmo();
        originalPrice = menu.getPrice();
        originalGroup = menu.getGroupId();
    }

    private void copyHeldItem() {
        if (saving || returningToShop || menu.getCarried().isEmpty()) {
            setStatus("gui.fpsm.shop_editor.item.replace.empty", FPSMLdlib2Theme.DANGER);
            failureVisible = true;
            return;
        }
        failureVisible = false;
        discardConfirmation = null;
        slotClicked(menu.slots.get(0), 0, 0, ClickType.PICKUP);
        refreshEditState();
    }

    private boolean isDirty() {
        return !ItemStack.matches(originalItem, menu.slots.get(0).getItem())
                || originalAmmo != menu.getAmmo()
                || originalPrice != menu.getPrice()
                || originalGroup != menu.getGroupId();
    }

    private void refreshEditState() {
        if (statusLabel == null || saveButton == null) {
            return;
        }
        if (view != null && !view.inputValid()) {
            setStatus("gui.fpsm.shop_editor.input.invalid", FPSMLdlib2Theme.DANGER);
            refreshButtons();
            return;
        }
        boolean dirty = isDirty();
        setStatus(
                dirty ? "gui.fpsm.shop_editor.state.pending" : "gui.fpsm.shop_editor.state.editing",
                dirty ? FPSMLdlib2Theme.WARNING : FPSMLdlib2Theme.ACCENT
        );
        refreshButtons();
    }

    private void refreshSaveButton() {
        if (saveButton == null) {
            return;
        }
        setButtonEnabled(saveButton,
                !saving && !returningToShop && isDirty()
                        && view != null && view.inputValid());
    }

    private void refreshButtons() {
        refreshSaveButton();
        if (closeButton != null) {
            if (discardConfirmation == null) {
                closeButton.setText(Component.translatable("gui.back"));
            }
            setButtonEnabled(closeButton, !saving && !returningToShop);
        }
    }

    /** Replaces the old procedural disabled-texture swap with the LSS __disabled__ class. */
    private static void setButtonEnabled(Button button, boolean enabled) {
        button.setActive(enabled);
        button.setAllowHitTest(enabled);
        button.setFocusable(enabled);
        if (enabled) {
            button.removeClass("__disabled__");
            return;
        }
        if (button.isFocused()) {
            button.blur();
        }
        button.addClass("__disabled__");
    }

    private void setStatus(String translationKey, int color) {
        if (statusLabel == null) {
            return;
        }
        statusLabel.setValue(Component.translatable(translationKey));
        statusLabel.textStyle(style -> style.textColor(color));
    }

    @Override
    public void removed() {
        releaseModularUi();
        super.removed();
    }

    private Ldlib2EditShopSlotUi.Draft currentDraft() {
        return view == null ? resizeDraft : view.draft();
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
        returnToShop();
    }

    private record DiscardSnapshot(
            ItemStack item,
            int ammo,
            int price,
            int group,
            Ldlib2EditShopSlotUi.Draft draft
    ) {
        private static DiscardSnapshot capture(
                EditShopSlotMenu menu,
                Ldlib2EditShopSlotUi.Draft draft
        ) {
            return new DiscardSnapshot(
                    menu.slots.get(0).getItem().copy(),
                    menu.getAmmo(), menu.getPrice(), menu.getGroupId(), draft);
        }

        private boolean matches(
                EditShopSlotMenu menu,
                Ldlib2EditShopSlotUi.Draft currentDraft
        ) {
            return ItemStack.matches(item, menu.slots.get(0).getItem())
                    && ammo == menu.getAmmo()
                    && price == menu.getPrice()
                    && group == menu.getGroupId()
                    && java.util.Objects.equals(draft, currentDraft);
        }
    }
}
