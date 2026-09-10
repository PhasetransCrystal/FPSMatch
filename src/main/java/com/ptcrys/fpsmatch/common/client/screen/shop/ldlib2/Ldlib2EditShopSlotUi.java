package com.ptcrys.fpsmatch.common.client.screen.shop.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.math.Size;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.EditShopSlotMenu;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessiblePanel;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleTextField;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntConsumer;

/**
 * Responsive LDLib2 work surface for one server-owned shop slot.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/edit_shop_slot.xml}; call
 * {@link View#bind()} after the ModularUI has been attached to its screen.
 */
public final class Ldlib2EditShopSlotUi {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/edit_shop_slot.xml";

    private Ldlib2EditShopSlotUi() {
    }

    public static View create(
            EditShopSlotMenu menu,
            Runnable saveAction,
            Runnable closeAction,
            Runnable copyHeldItemAction
    ) {
        UI ui = Ldlib2XmlUi.loadUi(LAYOUT, size -> Size.of(
                Math.max(280, size.getWidth() - 12),
                Math.max(220, size.getHeight() - 12)));
        ModularUI modularUI = ModularUI.of(ui);
        modularUI.setMenu(menu);
        return new View(modularUI, menu, saveAction, closeAction, copyHeldItemAction);
    }

    private static OptionalInt parse(String text, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(text);
            return value >= minimum && value <= maximum
                    ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    public static final class View {
        private final ModularUI ui;
        private final EditShopSlotMenu menu;
        private final Runnable saveAction;
        private final Runnable closeAction;
        private final Runnable copyHeldItemAction;
        private UIElement header;
        private UIElement product;
        private UIElement form;
        private UIElement inventory;
        private UIElement actions;
        private Label system;
        private Label title;
        private Label identity;
        private AccessiblePanel productCard;
        private Label itemCaption;
        private ItemSlot item;
        private Label itemHint;
        private Label ammoLabel;
        private AccessibleTextField ammo;
        private Label priceLabel;
        private AccessibleTextField price;
        private Label groupLabel;
        private AccessibleTextField group;
        private Label inventoryCaption;
        private List<ItemSlot> playerSlots;
        private Label status;
        private AccessibleButton save;
        private AccessibleButton close;
        private boolean bound;

        private View(
                ModularUI ui, EditShopSlotMenu menu, Runnable saveAction,
                Runnable closeAction, Runnable copyHeldItemAction
        ) {
            this.ui = ui;
            this.menu = menu;
            this.saveAction = saveAction;
            this.closeAction = closeAction;
            this.copyHeldItemAction = copyHeldItemAction;
        }

        public ModularUI modularUI() {
            return ui;
        }

        /** Resolves XML elements and wires callbacks; call after {@code setScreenAndInit}. */
        public void bind() {
            if (bound) {
                return;
            }
            UI uiDoc = this.ui.ui;
            header = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER + ".slot", UIElement.class);
            product = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.ITEM + ".panel", UIElement.class);
            form = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.SLOT_EDITOR + ".form", UIElement.class);
            inventory = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.SLOT_LIST + ".player", UIElement.class);
            actions = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.ACTIONS + ".slot", UIElement.class);
            system = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER + ".slot.system", Label.class);
            title = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER + ".slot.title", Label.class);
            identity = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER + ".slot.identity", Label.class);
            productCard = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.ITEM + ".card", AccessiblePanel.class);
            itemCaption = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.ITEM + ".caption", Label.class);
            itemHint = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.ITEM + ".hint", Label.class);
            ammoLabel = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.AMMO + ".label", Label.class);
            ammo = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.AMMO, AccessibleTextField.class);
            priceLabel = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PRICE + ".label", Label.class);
            price = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PRICE + ".selected", AccessibleTextField.class);
            groupLabel = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.GROUP + ".label", Label.class);
            group = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.GROUP + ".selected", AccessibleTextField.class);
            inventoryCaption = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.SLOT_LIST + ".player.caption", Label.class);
            status = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.STATUS, Label.class);
            save = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.SAVE, AccessibleButton.class);
            close = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.CLOSE + ".slot", AccessibleButton.class);

            if (header == null || product == null || form == null || inventory == null
                    || actions == null || system == null || title == null || identity == null
                    || productCard == null || itemCaption == null || itemHint == null
                    || ammoLabel == null || ammo == null || priceLabel == null || price == null
                    || groupLabel == null || group == null || inventoryCaption == null
                    || status == null || save == null || close == null) {
                FPSMatch.LOGGER.error("[FPSM UI] edit_shop_slot.xml is missing required elements; "
                        + "binding aborted (see errors above)");
                return;
            }

            identity.setValue(Component.literal(menu.getGameType() + " / " + menu.getMapName() + " / "
                    + menu.getTeamName() + " / " + menu.getShopType() + " #"
                    + (menu.getSlotNum() + 1)));

            productCard.setAccessibleName(Component.translatable("gui.fpsm.shop_editor.item_label"));
            productCard.setAccessibleState(() -> menu.slots.get(0).getItem().getHoverName());
            productCard.setAccessibleHint(() -> Component.translatable(
                    "gui.fpsm.shop_editor.item.replace.hint"));
            productCard.setOnActivate(copyHeldItemAction);

            item = new ItemSlot(menu.slots.get(0));
            item.setId(ShopEditorWidgetCatalog.ITEM + ".selected");
            item.setAllowHitTest(false);
            item.setFocusable(false);
            productCard.addChild(item);

            configureField(ammo, menu.getAmmo(), 0, 999_999, menu::setAmmo, "gui.fpsm.dummy_ammo");
            configureField(price, menu.getPrice(), 0, 1_000_000, menu::setPrice, "gui.fpsm.price");
            configureField(group, menu.getGroupId(), -1, 999_999, menu::setGroupId, "gui.fpsm.group");
            if (!menu.isGun()) {
                ammo.setVisible(false);
                ammo.setActive(false);
                ammo.setAllowHitTest(false);
                ammo.setFocusable(false);
                ammoLabel.setVisible(false);
            }

            playerSlots = new ArrayList<>();
            for (int index = 1; index < menu.slots.size(); index++) {
                Slot slot = menu.slots.get(index);
                ItemSlot playerSlot = new ItemSlot(slot);
                playerSlot.setId(ShopEditorWidgetCatalog.ITEM + ".player." + (index - 1));
                playerSlot.slotStyle(style -> style.isPlayerSlot(true).acceptQuickMove(true));
                playerSlots.add(playerSlot);
                inventory.addChild(playerSlot);
            }

            save.setAccessibleHint(() -> Component.translatable("gui.fpsm.shop_editor.save.hint"));
            save.setOnClick(event -> saveAction.run());
            close.setOnClick(event -> closeAction.run());

            bound = true;
        }

        private static void configureField(
                AccessibleTextField field,
                int value,
                int minimum,
                int maximum,
                IntConsumer responder,
                String labelKey
        ) {
            field.setAccessibleName(Component.translatable(labelKey));
            field.setAccessibleHint(() -> Component.translatable(
                    "gui.fpsm.shop_editor.numeric.hint", minimum, maximum));
            field.setNumbersOnlyInt(minimum, maximum);
            field.setText(Integer.toString(value));
            field.textFieldStyle(style -> style
                    .fontSize(10)
                    .placeholder(Component.literal(Integer.toString(minimum))));
            field.setTextResponder(text -> parse(text, minimum, maximum).ifPresent(responder));
        }

        public Label statusLabel() {
            return status;
        }

        public AccessibleButton saveButton() {
            return save;
        }

        public AccessibleButton closeButton() {
            return close;
        }

        public List<Ldlib2AccessibilityController.FocusTarget> focusTargets() {
            List<Ldlib2AccessibilityController.FocusTarget> targets = new ArrayList<>();
            if (!bound) {
                return targets;
            }
            targets.add(productCard);
            if (menu.isGun()) {
                targets.add(ammo);
            }
            targets.add(price);
            targets.add(group);
            targets.add(save);
            targets.add(close);
            return List.copyOf(targets);
        }

        public boolean inputValid() {
            return (!menu.isGun() || parse(ammo.getText(), 0, 999_999).isPresent())
                    && parse(price.getText(), 0, 1_000_000).isPresent()
                    && parse(group.getText(), -1, 999_999).isPresent();
        }

        public Draft draft() {
            if (!bound) {
                return new Draft("", "", "");
            }
            return new Draft(ammo.getText(), price.getText(), group.getText());
        }

        public void restoreDraft(Draft draft) {
            if (!bound || draft == null) {
                return;
            }
            ammo.setText(draft.ammo());
            price.setText(draft.price());
            group.setText(draft.group());
        }

        public void applyResponsiveLayout(int width, int height) {
            if (!bound) {
                return;
            }
            boolean compact = width < 440 || height < 300;
            int headerHeight = compact ? 42 : 48;
            int actionHeight = compact ? 46 : 38;
            int inventoryHeight = 90;
            int mainHeight = Math.max(42, height - headerHeight - actionHeight - inventoryHeight);
            int inventoryTop = Math.min(height - actionHeight - inventoryHeight,
                    headerHeight + mainHeight);

            absolute(header, 2, 2, width - 4, headerHeight - 4);
            int productWidth = compact ? 98 : Math.min(132, width / 3);
            absolute(product, 2, headerHeight + 2, productWidth - 4, mainHeight - 4);
            absolute(form, productWidth + 2, headerHeight + 2,
                    width - productWidth - 4, mainHeight - 4);
            absolute(inventory, 2, inventoryTop + 2, width - 4, inventoryHeight - 4);
            absolute(actions, 2, height - actionHeight + 2, width - 4, actionHeight - 4);

            layoutHeader(width - 4, headerHeight - 4);
            layoutProduct(productWidth - 4, mainHeight - 4, compact);
            layoutForm(width - productWidth - 4, mainHeight - 4, compact);
            layoutInventory(width - 4);
            layoutActions(width - 4, actionHeight - 4, compact);
        }

        private void layoutHeader(int width, int height) {
            absolute(system, 8, 2, Math.max(1, width - 16), 10);
            absolute(title, 8, 13, Math.max(1, width - 16), 18);
            absolute(identity, 8, Math.max(28, height - 14), Math.max(1, width - 16), 12);
        }

        private void layoutProduct(int width, int height, boolean compact) {
            absolute(productCard, 4, 4, Math.max(1, width - 8), Math.max(1, height - 8));
            absolute(itemCaption, 5, 3, Math.max(1, width - 10), 12);
            int itemSize = Math.max(24, Math.min(compact ? 30 : 38, height - 23));
            absolute(item, Math.max(4, (width - itemSize) / 2), 15, itemSize, itemSize);
            absolute(itemHint, 5, Math.max(16, height - 14), Math.max(1, width - 10), 11);
        }

        private void layoutForm(int width, int height, boolean compact) {
            if (compact) {
                int cell = Math.max(1, width / 3);
                layoutField(ammoLabel, ammo, 0, cell, height);
                layoutField(priceLabel, price, cell, cell, height);
                layoutField(groupLabel, group, cell * 2, width - cell * 2, height);
                return;
            }
            int rowHeight = Math.max(18, Math.min(26, height / 3));
            int labelWidth = Math.min(82, Math.max(58, width / 3));
            layoutRow(ammoLabel, ammo, 6, 4, width, rowHeight, labelWidth);
            layoutRow(priceLabel, price, 6, 4 + rowHeight, width, rowHeight, labelWidth);
            layoutRow(groupLabel, group, 6, 4 + rowHeight * 2, width, rowHeight, labelWidth);
        }

        private static void layoutField(
                Label label, AccessibleTextField field, int left, int width, int height
        ) {
            absolute(label, left + 4, 4, Math.max(1, width - 8), 12);
            absolute(field, left + 4, 18, Math.max(1, width - 8), Math.max(18, height - 24));
        }

        private static void layoutRow(
                Label label, AccessibleTextField field, int left, int top,
                int width, int rowHeight, int labelWidth
        ) {
            absolute(label, left, top + 5, labelWidth, 14);
            absolute(field, left + labelWidth, top + 1,
                    Math.max(1, width - labelWidth - left - 6), Math.max(18, rowHeight - 3));
        }

        private void layoutInventory(int width) {
            absolute(inventoryCaption, 8, 3, Math.max(1, width - 16), 12);
            int gridWidth = 9 * 18;
            int gridLeft = Math.max(6, (width - gridWidth) / 2);
            for (int index = 0; index < playerSlots.size(); index++) {
                int column = index % 9;
                int row = index < 27 ? index / 9 : 3;
                absolute(playerSlots.get(index), gridLeft + column * 18, 14 + row * 18, 18, 18);
            }
        }

        private void layoutActions(int width, int height, boolean compact) {
            int buttonHeight = compact ? 20 : Math.max(20, height - 8);
            int closeWidth = Math.min(102, Math.max(76, width / 4));
            int saveWidth = Math.min(132, Math.max(106, width / 3));
            int buttonTop = compact ? Math.max(20, height - buttonHeight - 2)
                    : Math.max(2, (height - buttonHeight) / 2);
            absolute(close, width - closeWidth - 6, buttonTop, closeWidth, buttonHeight);
            absolute(save, width - closeWidth - saveWidth - 12, buttonTop, saveWidth, buttonHeight);
            int statusWidth = compact ? width - 12 : width - closeWidth - saveWidth - 24;
            absolute(status, 8, compact ? 4 : buttonTop + 4,
                    Math.max(1, statusWidth), compact ? 14 : Math.max(12, buttonHeight - 4));
        }
    }

    public record Draft(String ammo, String price, String group) {
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
