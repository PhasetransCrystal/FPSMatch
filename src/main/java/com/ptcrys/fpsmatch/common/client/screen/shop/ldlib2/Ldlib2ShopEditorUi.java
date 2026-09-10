package com.ptcrys.fpsmatch.common.client.screen.shop.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.math.Size;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.EditorShopContainer;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessiblePanel;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Theme;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Responsive LDLib2 work surface for selecting and opening one fixed shop slot.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/editor_shop.xml}; call
 * {@link View#bind()} after the ModularUI has been attached to its screen.
 */
public final class Ldlib2ShopEditorUi {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/editor_shop.xml";
    private static final int CARD_WIDTH = 80;
    private static final int CARD_HEIGHT = 64;
    private static final int GAP = 6;

    private Ldlib2ShopEditorUi() {
    }

    public static View create(
            EditorShopContainer menu,
            int initialSelection,
            IntConsumer selectionChanged,
            IntConsumer editAction,
            Runnable closeAction
    ) {
        UI ui = Ldlib2XmlUi.loadUi(LAYOUT, size -> Size.of(
                Math.max(280, size.getWidth() - 12),
                Math.max(200, size.getHeight() - 12)));
        ModularUI modularUI = ModularUI.of(ui);
        modularUI.setMenu(menu);
        return new View(modularUI, menu, initialSelection, selectionChanged, editAction, closeAction);
    }

    private static Label label(String id, Component value) {
        Label label = new Label();
        label.setId(id);
        label.setValue(value);
        label.setAllowHitTest(false);
        label.setFocusable(false);
        return label;
    }

    public static final class View {
        private final ModularUI ui;
        private final EditorShopContainer menu;
        private final IntConsumer selectionChanged;
        private final IntConsumer editAction;
        private final Runnable closeAction;
        private final int initialSelection;
        private UIElement header;
        private UIElement categories;
        private UIElement slots;
        private UIElement properties;
        private UIElement actions;
        private Label system;
        private Label title;
        private Label identity;
        private Label mode;
        private Label categoryTitle;
        private ScrollerView categoryScroller;
        private UIElement categoryList;
        private Label slotTitle;
        private Label propertyTitle;
        private Label selectedName;
        private Label selectedTypeLabel;
        private Label selectedSlotLabel;
        private Label selectedPrice;
        private Label selectedQuantity;
        private Label selectedGroup;
        private Label actionStatus;
        private AccessibleButton edit;
        private AccessibleButton close;
        private final Map<String, AccessibleButton> categoryButtons = new LinkedHashMap<>();
        private final Map<String, CategoryView> categoryViews = new LinkedHashMap<>();
        private final Map<Integer, AccessiblePanel> slotCards = new LinkedHashMap<>();
        private String selectedType;
        private int selectedSlotIndex = -1;
        private boolean opening;
        private boolean openTimedOut;
        private boolean bound;

        private View(
                ModularUI ui, EditorShopContainer menu, int initialSelection,
                IntConsumer selectionChanged, IntConsumer editAction, Runnable closeAction
        ) {
            this.ui = ui;
            this.menu = menu;
            this.initialSelection = initialSelection;
            this.selectionChanged = selectionChanged;
            this.editAction = editAction;
            this.closeAction = closeAction;
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
            header = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER, UIElement.class);
            categories = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.CATEGORIES, UIElement.class);
            slots = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.SLOT_LIST, UIElement.class);
            properties = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES, UIElement.class);
            actions = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.ACTIONS, UIElement.class);
            system = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER + ".system", Label.class);
            title = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER + ".title", Label.class);
            identity = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.SUBTITLE, Label.class);
            mode = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.HEADER + ".mode", Label.class);
            categoryTitle = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.CATEGORIES + ".title", Label.class);
            categoryScroller = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.CATEGORY_TABS, ScrollerView.class);
            slotTitle = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.SLOT_LIST + ".title", Label.class);
            propertyTitle = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES + ".title", Label.class);
            selectedName = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES + ".name", Label.class);
            selectedTypeLabel = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES + ".type", Label.class);
            selectedSlotLabel = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES + ".slot", Label.class);
            selectedPrice = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES + ".price", Label.class);
            selectedQuantity = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES + ".quantity", Label.class);
            selectedGroup = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.PROPERTIES + ".group", Label.class);
            actionStatus = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.STATUS, Label.class);
            edit = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.EDIT_SELECTED, AccessibleButton.class);
            close = Ldlib2XmlUi.require(uiDoc, ShopEditorWidgetCatalog.CLOSE, AccessibleButton.class);

            if (header == null || categories == null || slots == null || properties == null
                    || actions == null || system == null || title == null || identity == null
                    || mode == null || categoryTitle == null || categoryScroller == null
                    || slotTitle == null || propertyTitle == null || selectedName == null
                    || selectedTypeLabel == null || selectedSlotLabel == null || selectedPrice == null
                    || selectedQuantity == null || selectedGroup == null || actionStatus == null
                    || edit == null || close == null) {
                FPSMatch.LOGGER.error("[FPSM UI] editor_shop.xml is missing required elements; "
                        + "binding aborted (see errors above)");
                return;
            }

            identity.setValue(Component.literal(
                    menu.getGameType() + " / " + menu.getMapName() + " / " + menu.getTeamName()));

            categoryList = new UIElement().setId(ShopEditorWidgetCatalog.CATEGORY_TABS + ".content");
            categoryScroller.addScrollViewChild(categoryList);

            edit.setAccessibleHint(() -> Component.translatable("gui.fpsm.shop_editor.edit_selected.hint"));
            edit.setOnClick(event -> openSelected());
            close.setOnClick(event -> {
                if (closeAction != null) {
                    closeAction.run();
                }
            });

            bound = true;
            buildCategories();
            restoreSelection(initialSelection);
        }

        public List<Ldlib2AccessibilityController.FocusTarget> focusTargets() {
            List<Ldlib2AccessibilityController.FocusTarget> targets = new ArrayList<>();
            if (!bound) {
                return targets;
            }
            targets.addAll(categoryButtons.values());
            CategoryView category = categoryViews.get(selectedType);
            if (category != null) {
                targets.addAll(category.cards());
            }
            targets.add(edit);
            targets.add(close);
            return targets;
        }

        public void applyResponsiveLayout(int width, int height) {
            if (!bound) {
                return;
            }
            ShopEditorLayoutModel model = ShopEditorLayoutModel.responsive(width, height);
            place(header, model.header());
            place(categories, model.categories());
            place(slots, model.slots());
            place(properties, model.properties());
            place(actions, model.actions());
            layoutHeader(model.header().width() - 4, model.header().height() - 4);
            layoutCategories(model.categories().width() - 4, model.categories().height() - 4, model.compact());
            layoutSlots(model.slots().width() - 4, model.slots().height() - 4);
            layoutProperties(model.properties().width() - 4,
                    model.properties().height() - 4, model.compact());
            layoutActions(model.actions().width() - 4, model.actions().height() - 4);
        }

        public void setOpeningState(boolean opening, boolean timedOut) {
            this.opening = opening;
            this.openTimedOut = timedOut;
            refreshSelection();
        }

        private void buildCategories() {
            List<String> types = new ArrayList<>(menu.getTypes().keySet());
            selectedType = types.isEmpty() ? null : types.get(0);
            for (String type : types) {
                AccessibleButton button = new AccessibleButton();
                button.setId(ShopEditorWidgetCatalog.CATEGORY_TABS + "." + type);
                button.setText(Component.translatable("fpsm.shop.title." + type));
                button.setAccessibleState(() -> type.equals(selectedType)
                        ? Component.translatable("gui.fpsm.shop_editor.category.selected")
                        : Component.empty());
                button.setOnClick(event -> selectCategory(type));
                button.addClass("compact-btn");
                categoryButtons.put(type, button);
                categoryList.addChild(button);
                CategoryView category = buildCategory(type);
                categoryViews.put(type, category);
                slots.addChild(category.root());
            }
            refreshCategory();
        }

        private CategoryView buildCategory(String type) {
            EditorShopContainer.TypeInfo info = menu.getTypes().get(type);
            UIElement root = new UIElement().setId(ShopEditorWidgetCatalog.GROUP + "." + type);
            ScrollerView scroller = new ScrollerView();
            scroller.setId(ShopEditorWidgetCatalog.SLOT_LIST + "." + type);
            scroller.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
            scroller.addClass("rhodes-scroller");
            UIElement content = new UIElement().setId(ShopEditorWidgetCatalog.SLOT_LIST + "." + type + ".content");
            List<AccessiblePanel> cards = new ArrayList<>();
            List<ShopSlot> all = menu.getAllSlots();
            for (int localIndex = 0; localIndex < info.slotCount(); localIndex++) {
                int slotIndex = info.startIndex() + localIndex;
                ShopSlot shopSlot = slotIndex < all.size() ? all.get(slotIndex) : null;
                Component itemName = shopSlot == null
                        ? Component.translatable("gui.fpsm.shop_editor.empty_slot")
                        : shopSlot.process().getHoverName();
                AccessiblePanel card = new AccessiblePanel();
                card.setId(ShopEditorWidgetCatalog.ITEM + "." + type + "." + localIndex + ".card");
                card.addClass("slot-card");
                card.addClass("panel-elevated");
                card.setAccessibleName(Component.translatable(
                        "gui.fpsm.shop_editor.slot.accessible", localIndex + 1, itemName));
                card.setAccessibleState(() -> selectedSlotIndex == slotIndex
                        ? Component.translatable("gui.fpsm.shop_editor.slot.selected")
                        : Component.empty());
                if (shopSlot == null) {
                    card.setActive(false);
                    card.setAllowHitTest(false);
                    card.setFocusable(false);
                    card.addClass("__disabled__");
                } else {
                    card.setOnActivate(() -> selectSlot(type, slotIndex));
                }
                Label name = label(card.getId() + ".name", itemName);
                name.addClass("muted");
                ItemSlot item = new ItemSlot(menu.slots.get(slotIndex));
                item.setId(ShopEditorWidgetCatalog.ITEM + "." + type + "." + localIndex);
                item.setAllowHitTest(false);
                item.setFocusable(false);
                Label price = label(card.getId() + ".price", shopSlot == null
                        ? Component.literal("-")
                        : Component.literal("$" + shopSlot.getDefaultCost()));
                price.textStyle(style -> style.fontSize(9).textColor(shopSlot == null
                        ? FPSMLdlib2Theme.DISABLED : FPSMLdlib2Theme.WARNING));
                absolute(name, 4, 4, CARD_WIDTH - 8, 12);
                absolute(item, 24, 15, 32, 32);
                absolute(price, 4, 50, CARD_WIDTH - 8, 12);
                card.addChildren(name, item, price);
                cards.add(card);
                slotCards.put(slotIndex, card);
                content.addChild(card);
            }
            scroller.addScrollViewChild(content);
            root.addChild(scroller);
            return new CategoryView(root, scroller, content, cards, info);
        }

        private void restoreSelection(int initialSelection) {
            if (initialSelection >= 0 && initialSelection < menu.getAllSlots().size()
                    && menu.getAllSlots().get(initialSelection) != null) {
                for (Map.Entry<String, EditorShopContainer.TypeInfo> entry : menu.getTypes().entrySet()) {
                    EditorShopContainer.TypeInfo info = entry.getValue();
                    if (initialSelection >= info.startIndex()
                            && initialSelection < info.startIndex() + info.slotCount()) {
                        selectedType = entry.getKey();
                        selectedSlotIndex = initialSelection;
                        break;
                    }
                }
            }
            refreshCategory();
            refreshSelection();
        }

        private void selectCategory(String type) {
            if (type.equals(selectedType)) {
                return;
            }
            selectedType = type;
            selectedSlotIndex = -1;
            opening = false;
            openTimedOut = false;
            selectionChanged.accept(-1);
            refreshCategory();
            refreshSelection();
        }

        private void selectSlot(String type, int slotIndex) {
            if (slotIndex < 0 || slotIndex >= menu.getAllSlots().size()
                    || menu.getAllSlots().get(slotIndex) == null) {
                return;
            }
            selectedType = type;
            selectedSlotIndex = slotIndex;
            opening = false;
            openTimedOut = false;
            selectionChanged.accept(slotIndex);
            refreshCategory();
            refreshSelection();
        }

        private void openSelected() {
            if (selectedSlotIndex >= 0 && !opening) {
                editAction.accept(selectedSlotIndex);
            }
        }

        private void refreshCategory() {
            categoryButtons.forEach((type, button) -> {
                button.removeClass("btn-primary");
                button.removeClass("btn-quiet");
                button.addClass(type.equals(selectedType) ? "btn-primary" : "btn-quiet");
            });
            categoryViews.forEach((type, view) -> view.root().setDisplay(type.equals(selectedType)));
        }

        private void refreshSelection() {
            if (!bound) {
                return;
            }
            ShopSlot selected = selectedSlotIndex >= 0 && selectedSlotIndex < menu.getAllSlots().size()
                    ? menu.getAllSlots().get(selectedSlotIndex) : null;
            EditorShopContainer.TypeInfo info = selectedType == null ? null : menu.getTypes().get(selectedType);
            if (selected == null || info == null) {
                selectedName.setValue(Component.translatable("gui.fpsm.shop_editor.selection.none"));
                for (Label value : List.of(selectedTypeLabel, selectedSlotLabel, selectedPrice,
                        selectedQuantity, selectedGroup)) {
                    value.setValue(Component.empty());
                }
            } else {
                int localIndex = selectedSlotIndex - info.startIndex();
                selectedName.setValue(selected.process().getHoverName());
                selectedTypeLabel.setValue(Component.translatable("gui.fpsm.shop_editor.property.type",
                        Component.translatable("fpsm.shop.title." + selectedType)));
                selectedSlotLabel.setValue(Component.translatable("gui.fpsm.shop_editor.property.slot",
                        localIndex + 1, info.slotCount()));
                selectedPrice.setValue(Component.translatable("gui.fpsm.shop_editor.property.price",
                        selected.getDefaultCost()));
                selectedQuantity.setValue(GunCompatManager.isGun(selected.process())
                        ? Component.translatable("gui.fpsm.shop_editor.property.ammo",
                        selected.getAmmoCount())
                        : Component.empty());
                selectedGroup.setValue(Component.translatable("gui.fpsm.shop_editor.property.group",
                        selected.getGroupId()));
            }
            slotCards.forEach((index, card) -> {
                if (card.isActive()) {
                    if (index == selectedSlotIndex) {
                        card.addClass("__selected__");
                    } else {
                        card.removeClass("__selected__");
                    }
                }
            });
            if (opening) {
                actionStatus.setValue(Component.translatable("gui.fpsm.shop_editor.state.opening"));
                actionStatus.textStyle(style -> style.textColor(FPSMLdlib2Theme.WARNING));
            } else if (openTimedOut) {
                actionStatus.setValue(Component.translatable("gui.fpsm.shop_editor.open.timeout"));
                actionStatus.textStyle(style -> style.textColor(FPSMLdlib2Theme.DANGER));
            } else if (selected == null) {
                actionStatus.setValue(Component.translatable("gui.fpsm.shop_editor.selection.none"));
                actionStatus.textStyle(style -> style.textColor(FPSMLdlib2Theme.MUTED));
            } else {
                actionStatus.setValue(Component.translatable("gui.fpsm.shop_editor.selection.ready"));
                actionStatus.textStyle(style -> style.textColor(FPSMLdlib2Theme.SUCCESS));
            }
            setButtonEnabled(edit, selected != null && !opening);
            setButtonEnabled(close, !opening);
        }

        private void layoutHeader(int width, int height) {
            if (height < 42) {
                absolute(system, 8, 1, Math.max(1, width - 16), 8);
                absolute(title, 8, 10, Math.max(1, width - 116), 16);
                absolute(identity, 8, 27, Math.max(1, width - 16), 10);
                absolute(mode, Math.max(8, width - 104), 10, 96, 16);
                return;
            }
            absolute(system, 8, 2, Math.max(1, width - 16), 10);
            absolute(title, 8, 13, Math.max(1, width - 116), 20);
            absolute(identity, 8, Math.max(30, height - 16), Math.max(1, width - 16), 14);
            absolute(mode, Math.max(8, width - 104), 13, 96, 18);
        }

        private void layoutCategories(int width, int height, boolean compact) {
            if (compact && height < 30) {
                int titleWidth = Math.min(88, Math.max(64, width / 5));
                absolute(categoryTitle, 8, 3, titleWidth - 8, 14);
                absolute(categoryScroller, titleWidth, 2,
                        Math.max(1, width - titleWidth - 6), Math.max(1, height - 4));
                int buttonWidth = 90;
                int contentWidth = Math.max(1, categoryButtons.size() * (buttonWidth + 4));
                categoryScroller.scrollerStyle(style -> style.mode(ScrollerMode.HORIZONTAL));
                categoryList.layout(layout -> layout.width(contentWidth)
                        .height(Math.max(1, height - 6)));
                int index = 0;
                for (AccessibleButton button : categoryButtons.values()) {
                    absolute(button, index * (buttonWidth + 4), 0, buttonWidth,
                            Math.max(16, height - 6));
                    index++;
                }
                return;
            }
            absolute(categoryTitle, 8, 5, Math.max(1, width - 16), 16);
            absolute(categoryScroller, 6, 24, Math.max(1, width - 12), Math.max(1, height - 30));
            int buttonWidth = compact ? 90 : Math.max(1, width - 14);
            int contentWidth = compact ? Math.max(1, categoryButtons.size() * (buttonWidth + 4)) : Math.max(1, width - 14);
            int contentHeight = compact ? 22 : Math.max(1, categoryButtons.size() * 24);
            categoryScroller.scrollerStyle(style -> style.mode(compact ? ScrollerMode.HORIZONTAL : ScrollerMode.VERTICAL));
            categoryList.layout(layout -> layout.width(contentWidth).height(contentHeight));
            int index = 0;
            for (AccessibleButton button : categoryButtons.values()) {
                int left = compact ? index * (buttonWidth + 4) : 0;
                int top = compact ? 0 : index * 24;
                absolute(button, left, top, buttonWidth, 20);
                index++;
            }
        }

        private void layoutSlots(int width, int height) {
            boolean shortViewport = height < 110;
            int scrollerTop = shortViewport ? 18 : 24;
            absolute(slotTitle, 8, shortViewport ? 2 : 5,
                    Math.max(1, width - 16), shortViewport ? 14 : 16);
            for (CategoryView category : categoryViews.values()) {
                int scrollerHeight = Math.max(1, height - scrollerTop - 2);
                absolute(category.root(), 6, scrollerTop,
                        Math.max(1, width - 12), scrollerHeight);
                absolute(category.scroller(), 0, 0,
                        Math.max(1, width - 12), scrollerHeight);
                int contentWidth = Math.max(CARD_WIDTH, width - 22);
                int columns = Math.max(1, contentWidth / (CARD_WIDTH + GAP));
                int rows = Math.max(1, (category.cards().size() + columns - 1) / columns);
                category.content().layout(layout -> layout.width(contentWidth)
                        .height(Math.max(CARD_HEIGHT, rows * (CARD_HEIGHT + GAP) + 4)));
                for (int index = 0; index < category.cards().size(); index++) {
                    int left = 2 + index % columns * (CARD_WIDTH + GAP);
                    int top = 2 + index / columns * (CARD_HEIGHT + GAP);
                    absolute(category.cards().get(index), left, top, CARD_WIDTH, CARD_HEIGHT);
                }
            }
        }

        private void layoutProperties(int width, int height, boolean compact) {
            if (compact && height < 48) {
                propertyTitle.setDisplay(false);
                absolute(selectedName, 8, 1, Math.max(1, width - 16), 11);
                int firstRowCell = Math.max(1, (width - 16) / 3);
                absolute(selectedTypeLabel, 8, 13, firstRowCell, 10);
                absolute(selectedSlotLabel, 8 + firstRowCell, 13, firstRowCell, 10);
                absolute(selectedPrice, 8 + firstRowCell * 2, 13, firstRowCell, 10);
                int secondRowCell = Math.max(1, (width - 16) / 2);
                absolute(selectedQuantity, 8, 24, secondRowCell, 10);
                absolute(selectedGroup, 8 + secondRowCell, 24, secondRowCell, 10);
                return;
            }
            propertyTitle.setDisplay(true);
            absolute(propertyTitle, 8, 5, Math.max(1, width - 16), 16);
            absolute(selectedName, 8, 23, Math.max(1, width - 16), 16);
            if (compact) {
                int cell = Math.max(1, (width - 16) / 3);
                absolute(selectedTypeLabel, 8, 42, cell, 14);
                absolute(selectedSlotLabel, 8 + cell, 42, cell, 14);
                absolute(selectedPrice, 8 + cell * 2, 42, cell, 14);
                absolute(selectedQuantity, 8, 58, cell, 14);
                absolute(selectedGroup, 8 + cell, 58, cell, 14);
            } else {
                absolute(selectedTypeLabel, 8, 44, Math.max(1, width - 16), 14);
                absolute(selectedSlotLabel, 8, 62, Math.max(1, width - 16), 14);
                absolute(selectedPrice, 8, 80, Math.max(1, width - 16), 14);
                absolute(selectedQuantity, 8, 98, Math.max(1, width - 16), 14);
                absolute(selectedGroup, 8, 116, Math.max(1, width - 16), 14);
            }
        }

        private void layoutActions(int width, int height) {
            int buttonHeight = Math.max(18, Math.min(24, height - 8));
            int closeWidth = Math.min(88, Math.max(60, width / 5));
            int editWidth = Math.min(132, Math.max(96, width / 4));
            int top = Math.max(2, (height - buttonHeight) / 2);
            absolute(close, Math.max(4, width - closeWidth - 6), top, closeWidth, buttonHeight);
            absolute(edit, Math.max(4, width - closeWidth - editWidth - 12), top, editWidth, buttonHeight);
            absolute(actionStatus, 8, top + 4, Math.max(1, width - closeWidth - editWidth - 26),
                    Math.max(12, buttonHeight - 4));
        }

        private static void place(UIElement element, ShopEditorLayoutModel.Rect rect) {
            absolute(element, rect.x() + 2, rect.y() + 2,
                    Math.max(1, rect.width() - 4), Math.max(1, rect.height() - 4));
        }

        /** Replaces the old procedural disabled-texture swap with the LSS __disabled__ class. */
        private static void setButtonEnabled(AccessibleButton button, boolean enabled) {
            if (button == null) {
                return;
            }
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
    }

    private static void absolute(UIElement element, int left, int top, int width, int height) {
        element.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto().left(left).top(top).width(width).height(height));
    }

    private record CategoryView(
            UIElement root,
            ScrollerView scroller,
            UIElement content,
            List<AccessiblePanel> cards,
            EditorShopContainer.TypeInfo info
    ) {
    }
}
