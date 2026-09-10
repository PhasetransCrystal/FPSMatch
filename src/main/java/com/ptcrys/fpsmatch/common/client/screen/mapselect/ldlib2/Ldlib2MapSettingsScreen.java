package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleTextField;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Theme;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingsC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Editable map-room settings list.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/map_settings.xml};
 * {@link MapSettingsGroupingModel} grouping logic stays on the Java side.
 */
public final class Ldlib2MapSettingsScreen extends Ldlib2MapChildScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_settings.xml";

    private MapLobbyTabs tabs;
    private String pendingTab;
    private boolean saveSucceeded;
    private UIElement panel;
    private Label subtitleLabel;
    private Label pendingLabel;
    private AccessibleButton shopButton;
    private TextField searchField;
    private AccessibleButton categoryFilterButton;
    private UIElement categoryFilterPopup;
    private VirtualScrollerView<String> categoryFilterList;
    private AccessibleButton clearCategorySelectionButton;
    private VirtualScrollerView<SettingListEntry> list;
    private Label emptyLabel;
    private AccessibleButton clearButton;
    private AccessibleButton saveButton;
    private AccessibleButton exitButton;
    private final Map<String, String> pendingValues = new LinkedHashMap<>();
    private final Set<String> selectedCategories = new LinkedHashSet<>();
    private List<String> availableCategories = List.of();
    private String searchQuery = "";
    private boolean saveInFlight;
    private int saveTicks;
    private boolean saveFailed;
    private Component saveFailureMessage;
    private int submittedChangeCount;
    private boolean discardConfirmation;
    private boolean compactEditor;
    private boolean bound;

    public Ldlib2MapSettingsScreen(MapRoomDetail detail, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT),
                Component.translatable("gui.fpsm.map_select.settings.title"), detail, parent);
    }

    @Override
    public void init() {
        super.init();
        bind();
        applyResponsiveLayout();
        if (list != null) list.refreshVisibleItems();
        refreshContent();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.drawMapIndex(graphics, width, height);
    }

    private void bind() {
        if (bound) return;
        UI ui = modularUI.ui;
        tabs = new MapLobbyTabs(ui, "fpsmatch.map_settings", "settings", this::requestTab);
        panel = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.panel", UIElement.class);
        subtitleLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.subtitle", Label.class);
        pendingLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.pending", Label.class);
        shopButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.shop", AccessibleButton.class);
        searchField = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.search", TextField.class);
        categoryFilterButton = Ldlib2XmlUi.require(
                ui, "fpsmatch.map_settings.category_filter", AccessibleButton.class);
        categoryFilterPopup = Ldlib2XmlUi.require(
                ui, "fpsmatch.map_settings.category_filter.popup", UIElement.class);
        @SuppressWarnings("unchecked")
        VirtualScrollerView<String> filterList = (VirtualScrollerView<String>) Ldlib2XmlUi.require(
                ui, "fpsmatch.map_settings.category_filter.list", VirtualScrollerView.class);
        categoryFilterList = filterList;
        clearCategorySelectionButton = Ldlib2XmlUi.require(
                ui, "fpsmatch.map_settings.category_filter.clear", AccessibleButton.class);
        @SuppressWarnings("unchecked")
        VirtualScrollerView<SettingListEntry> settingsList =
                (VirtualScrollerView<SettingListEntry>) Ldlib2XmlUi.require(
                        ui, "fpsmatch.map_settings.list", VirtualScrollerView.class);
        list = settingsList;
        emptyLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.empty", Label.class);
        clearButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.clear_changes", AccessibleButton.class);
        saveButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.save", AccessibleButton.class);
        exitButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_settings.exit", AccessibleButton.class);
        if (searchField != null) {
            searchField.setAnyString();
            searchField.textFieldStyle(style -> style.fontSize(10)
                    .placeholder(Component.translatable("gui.fpsm.map_select.settings.search")));
            searchField.setTextResponder(value -> {
                this.searchQuery = value == null ? "" : value.trim();
                refreshContent();
            });
        }
        if (categoryFilterButton != null) {
            categoryFilterButton.style(style -> style.tooltips(Component.translatable(
                    "gui.fpsm.map_select.settings.category_filter.tooltip")));
            categoryFilterButton.setOnClick(e -> {
                if (categoryFilterPopup != null) {
                    categoryFilterPopup.setVisible(!categoryFilterPopup.isVisible());
                }
            });
        }
        if (clearCategorySelectionButton != null) {
            clearCategorySelectionButton.setOnClick(e -> clearCategorySelection());
        }
        if (clearButton != null) {
            clearButton.setOnClick(e -> clearPendingChanges());
        }
        if (shopButton != null) {
            shopButton.setOnClick(e -> FPSMMapSelectScreens.openChild(
                    new Ldlib2MapShopScreen(detail, this)));
        }
        if (saveButton != null) {
            saveButton.setOnClick(e -> {
                if (discardConfirmation) {
                    cancelDiscard();
                } else {
                    saveChanges();
                }
            });
        }
        if (exitButton != null) {
            exitButton.setOnClick(e -> {
                if (discardConfirmation) {
                    discardAndClose();
                } else {
                    requestClose();
                }
            });
        }
        if (list != null) {
            list.setItemUIProvider(this::listRow);
        }
        if (categoryFilterList != null) {
            categoryFilterList.setItemUIProvider(this::categoryFilterRow);
        }
        bound = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!saveInFlight) {
            return;
        }
        saveTicks++;
        if (saveTicks >= 200) {
            saveInFlight = false;
            saveFailed = true;
            saveFailureMessage = Component.translatable(
                    "gui.fpsm.map_select.settings.save_timeout");
            list.refreshVisibleItems();
            updatePendingState();
            announce(saveFailureMessage, true);
        }
    }

    public boolean isSavePending() {
        return saveInFlight;
    }

    public void applySaveFailure(MapRoomToastS2CPacket packet) {
        if (!saveInFlight) {
            return;
        }
        saveInFlight = false;
        saveTicks = 0;
        saveFailed = true;
        saveFailureMessage = packet.message();
        list.refreshVisibleItems();
        updatePendingState();
        announce(saveFailureMessage, true);
    }

    @Override
    protected void onDetailApplied() {
        if (!detail.summary().currentPlayerOp()) {
            discardConfirmation = false;
            if (saveInFlight) {
                saveInFlight = false;
                saveFailed = true;
                saveFailureMessage = Component.translatable("gui.fpsm.map_select.manage.no_permission");
            }
        }
        prunePendingValues();
        if (saveInFlight && pendingValues.isEmpty()) {
            saveInFlight = false;
            saveTicks = 0;
            saveFailed = false;
            saveFailureMessage = null;
            saveSucceeded = true;
            announce(Component.translatable("gui.fpsm.map_select.settings.save_success", submittedChangeCount), true);
        }
        refreshContent();
    }

    private void refreshContent() {
        if (!bound) {
            return;
        }
        subtitleLabel.setValue(Component.literal(detail.summary().gameType() + " / " + detail.summary().mapName()));
        List<String> categories = MapSettingsGroupingModel.categories(detail.settings());
        selectedCategories.retainAll(categories);
        refreshCategoryFilter(categories);
        List<MapRoomSettingInfo> visible = detail.settings().stream()
                .filter(this::matchesSearch)
                .toList();
        List<SettingListEntry> entries = new ArrayList<>();
        for (MapSettingsGroupingModel.Group group : MapSettingsGroupingModel.group(visible, selectedCategories)) {
            entries.add(new CategoryEntry(group.category()));
            group.settings().forEach(setting -> entries.add(new SettingEntry(setting)));
        }
        list.setItems(entries);
        list.refreshVisibleItems();
        emptyLabel.setValue(Component.translatable(detail.settings().isEmpty()
                ? "gui.fpsm.map_select.settings.empty"
                : "gui.fpsm.map_select.settings.no_results"));
        emptyLabel.setVisible(entries.isEmpty());
        updatePendingState();
        if (width > 0 && height > 0) {
            applyResponsiveLayout();
        }
    }

    private void refreshCategoryFilter(List<String> categories) {
        if (!availableCategories.equals(categories)) {
            availableCategories = List.copyOf(categories);
            categoryFilterList.setItems(availableCategories);
            categoryFilterList.refreshVisibleItems();
        }
        if (categories.isEmpty()) {
            categoryFilterPopup.setVisible(false);
        }
        categoryFilterButton.setText(selectedCategories.isEmpty()
                ? Component.translatable("gui.fpsm.map_select.settings.category_filter")
                : Component.translatable("gui.fpsm.map_select.settings.category_filter.selected",
                        selectedCategories.size()));
        setButtonEnabled(categoryFilterButton, !categories.isEmpty());
        setButtonEnabled(clearCategorySelectionButton, !selectedCategories.isEmpty());
        shopButton.setAvailability(true, detail.summary().currentPlayerOp()
                && !detail.editableShops().isEmpty() && pendingValues.isEmpty() && !saveInFlight);
        categoryFilterList.refreshVisibleItems();
    }

    private UIElement categoryFilterRow(String category) {
        AccessibleButton toggle = new AccessibleButton();
        toggle.setId("fpsmatch.map_settings.category_filter.option." + category);
        Component categoryName = Component.translatable(
                MapRoomSettingInfo.categoryTranslationKey(category));
        toggle.setText(categoryName);
        toggle.setAccessibleName(categoryName);
        toggle.setAccessibleState(() -> toggleLabel(selectedCategories.contains(category)));
        toggle.layout(layout -> layout.widthPercent(100).height(20).marginBottom(3));
        toggle.addClass(selectedCategories.contains(category) ? "btn-primary" : "btn-secondary");
        toggle.addClass("compact-btn");
        toggle.style(style -> style.tooltips(Component.translatable(
                MapRoomSettingInfo.categoryTranslationKey(category))));
        toggle.setOnClick(event -> {
            if (!selectedCategories.remove(category)) {
                selectedCategories.add(category);
            }
            refreshContent();
            categoryFilterPopup.setVisible(true);
        });
        return toggle;
    }

    private void clearCategorySelection() {
        if (selectedCategories.isEmpty()) {
            return;
        }
        selectedCategories.clear();
        refreshContent();
        categoryFilterPopup.setVisible(true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (categoryFilterPopup.isVisible()) {
            UIElement target = hitElementAt(mouseX, mouseY);
            boolean insidePopup = target != null
                    && (target == categoryFilterPopup || categoryFilterPopup.isAncestorOf(target));
            boolean onFilterButton = target != null
                    && (target == categoryFilterButton || categoryFilterButton.isAncestorOf(target));
            if (target != null && !insidePopup && !onFilterButton) {
                categoryFilterPopup.setVisible(false);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void applyResponsiveLayout() {
        if (!bound) {
            return;
        }
        int margin = Math.min(16, Math.max(8, width / 32));
        int contentWidth = Math.max(1, width - margin * 2);
        int actionColumns = contentWidth < 300 ? 2 : 4;
        int footerHeight = actionColumns == 2 ? 105 : 74;
        int contentTop = height < 300 ? 61 : 76;
        int contentHeight = Math.max(1, height - contentTop - footerHeight);
        tabs.layout(width, height);
        absolute(subtitleLabel, margin, 32, contentWidth, 13);
        subtitleLabel.setVisible(height >= 300);
        absolute(panel, margin, contentTop, contentWidth, contentHeight);
        absolute(pendingLabel, margin, height - footerHeight + 3, contentWidth, 30);
        int buttonWidth = Math.max(1, (contentWidth - (actionColumns - 1) * 5) / actionColumns);
        AccessibleButton[] actions = {shopButton, clearButton, saveButton, exitButton};
        for (int i = 0; i < actions.length; i++) {
            absolute(actions[i], margin + i % actionColumns * (buttonWidth + 5),
                    height - 34 - (actionColumns == 2 ? 31 : 0) + i / actionColumns * 31, buttonWidth, 26);
            actions[i].textStyle(style -> style.fontSize(9));
        }

        int padding = contentWidth >= 20 ? 7 : 1;
        int controlHeight = 22;
        boolean stackedToolbar = contentWidth < 230;
        int listTop;
        int filterWidth;
        if (stackedToolbar) {
            absolute(searchField, padding, 6, Math.max(1, contentWidth - padding * 2), controlHeight);
            absolute(categoryFilterButton, padding, 33,
                    Math.max(1, contentWidth - padding * 2), controlHeight);
            listTop = 61;
            filterWidth = Math.max(1, contentWidth - padding * 2);
        } else {
            int usableWidth = Math.max(1, contentWidth - padding * 2);
            int controlGap = 6;
            filterWidth = Math.min(126, Math.max(82, usableWidth * 34 / 100));
            int searchWidth = Math.max(1, usableWidth - filterWidth - controlGap);
            absolute(searchField, padding, 6, searchWidth, controlHeight);
            absolute(categoryFilterButton, padding + searchWidth + controlGap, 6,
                    filterWidth, controlHeight);
            listTop = 34;
        }
        absolute(list, padding, listTop, Math.max(1, contentWidth - padding * 2),
                Math.max(1, contentHeight - listTop - 7));
        absolute(emptyLabel, padding, listTop, Math.max(1, contentWidth - padding * 2),
                Math.max(1, contentHeight - listTop - 7));
        int popupTop = stackedToolbar ? 58 : 31;
        int desiredPopupHeight = Math.max(54, availableCategories.size() * 23 + 32);
        int popupHeight = Math.max(1,
                Math.min(desiredPopupHeight, contentHeight - popupTop - padding));
        absolute(categoryFilterPopup, Math.max(padding, contentWidth - padding - filterWidth),
                popupTop, filterWidth, popupHeight);

        boolean nextCompactEditor = contentWidth < 250;
        if (compactEditor != nextCompactEditor) {
            compactEditor = nextCompactEditor;
            list.virtualScrollerViewStyle(style -> style
                    .estimatedItemHeight(compactEditor ? 51f : 33f)
                    .overscanPixels(compactEditor ? 102 : 66));
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

    private void applySetting(String settingName, String value) {
        FPSMatch.sendToServer(new MapRoomSettingsC2SPacket(
                detail.summary().gameType(), detail.summary().mapName(), settingName, value));
    }

    private boolean saveChanges() {
        normalizePendingValues();
        prunePendingValues();
        if (pendingValues.isEmpty()) {
            refreshContent();
            return true;
        }
        if (saveInFlight || !detail.summary().currentPlayerOp() || !allPendingValuesValid()) {
            updatePendingState();
            return false;
        }
        List<Map.Entry<String, String>> changes = new ArrayList<>(pendingValues.entrySet());
        submittedChangeCount = changes.size();
        for (Map.Entry<String, String> change : changes) {
            applySetting(change.getKey(), change.getValue());
        }
        saveInFlight = true;
        saveTicks = 0;
        saveFailed = false;
        saveFailureMessage = null;
        list.refreshVisibleItems();
        updatePendingState();
        return false;
    }

    private void requestTab(String tab) {
        if ("settings".equals(tab) || saveInFlight) return;
        pendingTab = tab;
        requestClose();
    }

    private void finishClose() {
        if (pendingTab != null) {
            String tab = pendingTab;
            pendingTab = null;
            openLobbyTab(tab);
        } else {
            super.onClose();
        }
    }

    private void requestClose() {
        if (pendingValues.isEmpty()) {
            finishClose();
            return;
        }
        discardConfirmation = true;
        list.refreshVisibleItems();
        categoryFilterPopup.setVisible(false);
        updatePendingState();
        announce(Component.translatable("gui.fpsm.map_select.settings.discard.message"), true);
    }

    private void cancelDiscard() {
        pendingTab = null;
        discardConfirmation = false;
        list.refreshVisibleItems();
        updatePendingState();
    }

    private void discardAndClose() {
        pendingValues.clear();
        discardConfirmation = false;
        finishClose();
    }

    @Override
    public void onClose() {
        if (saveInFlight) {
            return;
        }
        if (discardConfirmation) {
            cancelDiscard();
            return;
        }
        requestClose();
    }

    private void clearPendingChanges() {
        discardConfirmation = false;
        saveFailed = false;
        saveFailureMessage = null;
        saveSucceeded = false;
        pendingValues.clear();
        refreshContent();
    }

    private boolean canEdit(MapRoomSettingInfo setting) {
        return setting.editable() && detail.summary().currentPlayerOp() && !saveInFlight && !discardConfirmation;
    }

    private void normalizePendingValues() {
        for (MapRoomSettingInfo setting : detail.settings()) {
            String raw = pendingValues.get(setting.name());
            if (raw != null && (setting.type() == MapRoomSettingInfo.SettingType.INTEGER
                    || setting.type() == MapRoomSettingInfo.SettingType.DECIMAL)) {
                normalizeNumericValue(setting, raw).ifPresent(value -> {
                    if (value.equals(setting.value())) pendingValues.remove(setting.name());
                    else pendingValues.put(setting.name(), value);
                });
            }
        }
    }

    private void stageSetting(MapRoomSettingInfo setting, String value) {
        if (!canEdit(setting)) return;
        discardConfirmation = false;
        saveSucceeded = false;
        saveFailed = false;
        saveFailureMessage = null;
        String staged = value == null ? "" : value;
        String current = detail.settings().stream()
                .filter(candidate -> candidate.name().equals(setting.name()))
                .map(MapRoomSettingInfo::value)
                .findFirst()
                .orElse(setting.value());
        if (Objects.equals(current, staged)) {
            pendingValues.remove(setting.name());
        } else {
            pendingValues.put(setting.name(), staged);
        }
        modularUI.ui.selectId("fpsmatch.map_settings.row.content." + setting.name(), UIElement.class)
                .findFirst().ifPresent(row -> {
                    setInvalid(row, !validValue(setting, staged));
                    if (pendingValues.containsKey(setting.name())) row.addClass("__modified__");
                    else row.removeClass("__modified__");
                });
        updatePendingState();
    }

    private String currentValue(MapRoomSettingInfo setting) {
        return pendingValues.getOrDefault(setting.name(), setting.value());
    }

    private boolean matchesSearch(MapRoomSettingInfo setting) {
        if (searchQuery.isEmpty()) {
            return true;
        }
        String query = searchQuery.toLowerCase(Locale.ROOT);
        String translated = Component.translatable(setting.translationKey()).getString()
                .toLowerCase(Locale.ROOT);
        String categoryKey = setting.categoryTranslationKey();
        String translatedCategory = Component.translatable(categoryKey).getString()
                .toLowerCase(Locale.ROOT);
        return setting.name().toLowerCase(Locale.ROOT).contains(query)
                || setting.translationKey().toLowerCase(Locale.ROOT).contains(query)
                || translated.contains(query)
                || setting.category().toLowerCase(Locale.ROOT).contains(query)
                || categoryKey.toLowerCase(Locale.ROOT).contains(query)
                || translatedCategory.contains(query);
    }

    private void prunePendingValues() {
        pendingValues.entrySet().removeIf(entry -> detail.settings().stream()
                .filter(setting -> setting.name().equals(entry.getKey()))
                .anyMatch(setting -> sameSettingValue(setting, entry.getValue())));
    }

    private static boolean sameSettingValue(MapRoomSettingInfo setting, String value) {
        if (Objects.equals(setting.value(), value)) return true;
        if (setting.type() == MapRoomSettingInfo.SettingType.INTEGER
                || setting.type() == MapRoomSettingInfo.SettingType.DECIMAL) {
            try {
                // DECIMAL metadata covers both Float and Double server settings.
                if (setting.type() == MapRoomSettingInfo.SettingType.DECIMAL
                        && (Double.compare(Double.parseDouble(setting.value()), Double.parseDouble(value)) == 0
                        || Float.toString(Float.parseFloat(value)).equals(setting.value()))) return true;
                return new BigDecimal(setting.value()).compareTo(new BigDecimal(value.trim())) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private void updatePendingState() {
        if (!bound) {
            return;
        }
        boolean invalid = !allPendingValuesValid();
        if (discardConfirmation) {
            pendingLabel.setValue(Component.translatable(
                    "gui.fpsm.map_select.settings.discard.message"));
        } else if (saveInFlight) {
            pendingLabel.setValue(Component.translatable("gui.fpsm.map_select.settings.saving"));
        } else if (saveFailed) {
            pendingLabel.setValue(saveFailureMessage == null
                    ? Component.translatable("gui.fpsm.map_select.settings.save_failed")
                    : saveFailureMessage);
        } else if (saveSucceeded && pendingValues.isEmpty()) {
            pendingLabel.setValue(Component.translatable("gui.fpsm.map_select.settings.save_success", submittedChangeCount));
        } else if (pendingValues.isEmpty()) {
            pendingLabel.setValue(Component.translatable("gui.fpsm.map_select.settings.no_changes"));
        } else if (invalid) {
            pendingLabel.setValue(Component.translatable("gui.fpsm.map_select.settings.invalid"));
        } else {
            pendingLabel.setValue(Component.translatable("gui.fpsm.map_select.settings.pending", pendingValues.size()));
        }
        setPendingTone(discardConfirmation || saveFailed, invalid || saveInFlight);

        boolean canClear = detail.summary().currentPlayerOp()
                && !pendingValues.isEmpty() && !saveInFlight && !discardConfirmation;
        clearButton.setAvailability(true, canClear);
        tabs.update(detail.summary().currentPlayerOp(), !saveInFlight && !discardConfirmation);
        shopButton.setAvailability(true, detail.summary().currentPlayerOp()
                && !detail.editableShops().isEmpty() && pendingValues.isEmpty()
                && !saveInFlight && !discardConfirmation);
        shopButton.setAccessibleHint(() -> Component.translatable(
                !detail.summary().currentPlayerOp() ? "gui.fpsm.team_manage.more.restricted"
                        : detail.editableShops().isEmpty() ? "gui.fpsm.map_settings.shop.unavailable"
                        : "gui.fpsm.map_settings.shop.pending"));

        saveButton.setText(Component.translatable(discardConfirmation
                ? "gui.fpsm.map_select.settings.keep_editing"
                : "gui.fpsm.map_select.settings.save"));
        setButtonKind(saveButton, discardConfirmation ? "btn-secondary" : "btn-primary");
        setButtonEnabled(saveButton, discardConfirmation || !saveInFlight
                && detail.summary().currentPlayerOp()
                && !pendingValues.isEmpty() && !invalid);

        exitButton.setText(Component.translatable(discardConfirmation
                ? "gui.fpsm.map_select.settings.discard"
                : "gui.back"));
        setButtonKind(exitButton, discardConfirmation ? "btn-danger" : "btn-quiet");
        setButtonEnabled(exitButton, !saveInFlight);
    }

    /** Toggles the XML-defined pending tone classes on the sidebar status line. */
    private void setPendingTone(boolean danger, boolean warning) {
        pendingLabel.removeClass("pending-warning");
        pendingLabel.removeClass("pending-danger");
        if (danger) {
            pendingLabel.addClass("pending-danger");
        } else if (warning) {
            pendingLabel.addClass("pending-warning");
        }
    }

    private static void setButtonEnabled(AccessibleButton button, boolean enabled) {
        if (button != null) button.setAvailability(true, enabled);
    }

    /** Swaps the LSS button-kind class (btn-primary/btn-secondary/btn-danger/btn-quiet). */
    private static void setButtonKind(AccessibleButton button, String kind) {
        if (button == null) {
            return;
        }
        button.removeClass("btn-primary");
        button.removeClass("btn-secondary");
        button.removeClass("btn-danger");
        button.removeClass("btn-quiet");
        button.addClass(kind);
    }

    private boolean allPendingValuesValid() {
        return pendingValues.entrySet().stream().allMatch(entry -> detail.settings().stream()
                .filter(setting -> setting.name().equals(entry.getKey()))
                .findFirst()
                .map(setting -> setting.editable() && validValue(setting, entry.getValue()))
                .orElse(false));
    }

    private static boolean validValue(MapRoomSettingInfo setting, String value) {
        if (value == null || value.length() > 1024) {
            return false;
        }
        try {
            return switch (setting.type()) {
                case BOOLEAN -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
                case INTEGER, DECIMAL -> normalizeNumericValue(setting, value).isPresent();
                default -> true;
            };
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static Optional<String> normalizeNumericValue(MapRoomSettingInfo setting, String raw) {
        return MapSettingNumbers.normalize(raw, setting.type() == MapRoomSettingInfo.SettingType.INTEGER,
                setting.minValue(), setting.maxValue(), setting.step());
    }

    private static void setInvalid(UIElement element, boolean invalid) {
        if (element == null) {
            return;
        }
        if (invalid) {
            element.addClass("__invalid__");
        } else {
            element.removeClass("__invalid__");
        }
    }

    private UIElement listRow(SettingListEntry entry) {
        if (entry instanceof CategoryEntry category) {
            return categoryRow(category.category());
        }
        return settingRow(((SettingEntry) entry).setting());
    }

    private UIElement categoryRow(String category) {
        UIElement row = new UIElement().setId("fpsmatch.map_settings.category." + category);
        row.layout(layout -> layout.widthPercent(100).height(22).marginTop(3).marginBottom(2));
        row.addClass("settings-category");

        Label title = label("fpsmatch.map_settings.category.label." + category,
                Component.translatable(MapRoomSettingInfo.categoryTranslationKey(category)));
        title.addClass("section");
        title.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                .left(8).right(8).top(5).height(12));
        title.textStyle(style -> style.fontSize(10).textWrap(TextWrap.HIDE));
        row.addChild(title);
        return row;
    }

    private UIElement settingRow(MapRoomSettingInfo setting) {
        UIElement row = new UIElement().setId("fpsmatch.map_settings.row." + setting.name());
        row.layout(layout -> layout.widthPercent(100)
                .height(compactEditor ? 48 : 30).marginBottom(3));
        addSettingTooltip(row, setting);

        UIElement content = new UIElement().setId("fpsmatch.map_settings.row.content." + setting.name());
        content.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                .left(2).right(2).top(0).bottom(0));
        content.addClass("settings-entry");
        if (pendingValues.containsKey(setting.name())) {
            content.addClass("__modified__");
        }
        setInvalid(content, pendingValues.containsKey(setting.name())
                && !validValue(setting, pendingValues.get(setting.name())));
        addSettingTooltip(content, setting);
        row.addChild(content);

        Label name = label("fpsmatch.map_settings.name." + setting.name(), Component.translatable(setting.translationKey()));
        if (compactEditor) {
            name.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .left(8).right(8).top(5).height(12));
        } else {
            name.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .left(8).top(9).widthPercent(34).height(12));
        }
        name.addClass("body");
        name.textStyle(style -> style.fontSize(9).textWrap(TextWrap.HIDE));
        addSettingTooltip(name, setting);
        content.addChild(name);

        if (setting.type() == MapRoomSettingInfo.SettingType.BOOLEAN) {
            boolean[] value = {Boolean.parseBoolean(currentValue(setting))};
            AccessibleButton toggle = new AccessibleButton();
            toggle.setId("fpsmatch.map_settings.toggle." + setting.name());
            toggle.setText(toggleLabel(value[0]));
            if (compactEditor) {
                toggle.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                        .left(8).right(8).top(22).height(20));
            } else {
                toggle.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                        .right(6).top(5).width(72).height(20));
            }
            toggle.addClass("btn-secondary");
            toggle.addClass("compact-btn");
            toggle.textStyle(style -> style.fontSize(9));
            toggle.setAccessibleName(Component.translatable(setting.translationKey()));
            toggle.setAccessibleState(() -> toggleLabel(value[0]));
            addSettingTooltip(toggle, setting);
            toggle.setAvailability(true, canEdit(setting));
            toggle.setOnClick(e -> {
                if (!canEdit(setting)) return;
                value[0] = !value[0];
                toggle.setText(toggleLabel(value[0]));
                stageSetting(setting, String.valueOf(value[0]));
            });
            content.addChild(toggle);
        } else if (validSlider(setting)) {
            addSliderEditor(content, setting);
        } else {
            addTextEditor(content, setting);
        }
        return row;
    }

    private void addSliderEditor(UIElement row, MapRoomSettingInfo setting) {
        float min = (float) setting.minValue();
        float max = (float) setting.maxValue();
        float current = clamp(parseFloat(currentValue(setting), min), min, max);
        SteppedSlider slider = new SteppedSlider(min, max, (float) setting.step(),
                Component.translatable(setting.translationKey()));
        slider.setId("fpsmatch.map_settings.slider." + setting.name());
        slider.setValue(current, false);
        slider.setScrollBarSize(14);
        slider.scrollerStyle(style -> style.scrollDelta(slider.normalizedStep()));
        if (compactEditor) {
            slider.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .left(8).right(76).top(24).height(16));
        } else {
            slider.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .leftPercent(38).right(76).top(7).height(16));
        }
        slider.addClass("settings-slider");
        slider.scrollBar(button -> button.addClass("btn-secondary"));
        slider.setActive(canEdit(setting));
        slider.setAllowHitTest(canEdit(setting));
        slider.setFocusable(canEdit(setting));
        addSettingTooltip(slider, setting);

        AccessibleTextField value = new AccessibleTextField();
        value.setId("fpsmatch.map_settings.slider.value." + setting.name());
        value.setAnyString();
        value.setText(currentValue(setting), false);
        value.setAccessibleName(Component.translatable(setting.translationKey()));
        value.setActive(canEdit(setting));
        value.setAllowHitTest(canEdit(setting));
        value.setFocusable(canEdit(setting));
        setInvalid(value, !validValue(setting, currentValue(setting)));
        value.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                .right(8).top(compactEditor ? 22 : 5).width(64).height(20));
        value.textFieldStyle(style -> style.fontSize(9));
        addSettingTooltip(value, setting);
        slider.setOnValueChanged(changed -> {
            if (!canEdit(setting)) return;
            String formatted = normalizeNumericValue(setting, Float.toString(changed))
                    .orElse(currentValue(setting));
            value.setText(formatted, false);
            setInvalid(value, false);
            stageSetting(setting, formatted);
        });
        value.setTextResponder(raw -> {
            setInvalid(value, !validValue(setting, raw));
            stageSetting(setting, raw);
        });
        value.addEventListener(UIEvents.BLUR, event -> normalizeField(value, slider, setting));
        row.addChildren(slider, value);
    }

    private void addTextEditor(UIElement row, MapRoomSettingInfo setting) {
        AccessibleTextField field = new AccessibleTextField();
        field.setId("fpsmatch.map_settings.field." + setting.name());
        field.setAnyString();
        field.setText(currentValue(setting), false);
        field.setAccessibleName(Component.translatable(setting.translationKey()));
        setInvalid(field, !validValue(setting, currentValue(setting)));
        if (compactEditor) {
            field.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .left(8).right(8).top(22).height(20));
        } else {
            field.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .leftPercent(40).right(6).top(5).height(20));
        }
        field.textFieldStyle(style -> style
                .fontSize(9)
                .placeholder(Component.literal(setting.defaultValue())));
        field.setActive(canEdit(setting));
        field.setAllowHitTest(canEdit(setting));
        field.setFocusable(canEdit(setting));
        addSettingTooltip(field, setting);
        field.setTextResponder(raw -> {
            boolean invalid = !validValue(setting, raw);
            setInvalid(field, invalid);
            stageSetting(setting, raw);
        });
        field.addEventListener(UIEvents.BLUR, event -> normalizeField(field, null, setting));
        row.addChild(field);
    }

    private void normalizeField(TextField field, SteppedSlider slider, MapRoomSettingInfo setting) {
        if (!canEdit(setting) || (setting.type() != MapRoomSettingInfo.SettingType.INTEGER
                && setting.type() != MapRoomSettingInfo.SettingType.DECIMAL)) return;
        normalizeNumericValue(setting, field.getValue()).ifPresent(formatted -> {
            field.setText(formatted, false);
            setInvalid(field, false);
            if (slider != null) slider.setValue(Float.parseFloat(formatted), false);
            stageSetting(setting, formatted);
        });
    }

    private static boolean validSlider(MapRoomSettingInfo setting) {
        return (setting.type() == MapRoomSettingInfo.SettingType.INTEGER
                || setting.type() == MapRoomSettingInfo.SettingType.DECIMAL)
                && MapSettingNumbers.hasRange(setting.minValue(), setting.maxValue(), setting.step())
                && Float.isFinite((float) setting.minValue()) && Float.isFinite((float) setting.maxValue())
                && (float) setting.maxValue() > (float) setting.minValue() && (float) setting.step() > 0;
    }

    private static String formatNumber(double value, MapRoomSettingInfo.SettingType type) {
        if (type == MapRoomSettingInfo.SettingType.INTEGER) {
            return Long.toString(Math.round(value));
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static float parseFloat(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value);
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void addSettingTooltip(UIElement element, MapRoomSettingInfo setting) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(setting.descriptionKey()));
        lines.add(Component.translatable("gui.fpsm.map_select.setting.default", setting.defaultValue()));
        if (validSlider(setting)) {
            lines.add(Component.translatable("gui.fpsm.map_select.setting.range",
                    formatNumber(setting.minValue(), setting.type()),
                    formatNumber(setting.maxValue(), setting.type()),
                    formatNumber(setting.step(), setting.type())));
        }
        element.style(style -> style.tooltips(lines.toArray(Component[]::new)));
    }

    private static Component toggleLabel(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }

    private static final class SteppedSlider extends Scroller.Horizontal
            implements Ldlib2AccessibilityController.FocusTarget {
        private final float step;
        private final Component name;

        private SteppedSlider(float min, float max, float step, Component name) {
            this.step = step;
            this.name = name;
            setRange(min, max);
            addEventListener(UIEvents.MOUSE_DOWN, event -> {
                if (event.button == 0 && isActive()) focus();
            }, true);
            addEventListener(UIEvents.KEY_DOWN, event -> {
                if (!isActive() || !(isFocused() || isChildFocused())) return;
                Float value = switch (event.keyCode) {
                    case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN -> getValue() - step;
                    case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP -> getValue() + step;
                    case GLFW.GLFW_KEY_HOME -> getMinValue();
                    case GLFW.GLFW_KEY_END -> getMaxValue();
                    default -> null;
                };
                if (value != null) {
                    setValue(value, true);
                    event.stopPropagation();
                }
            });
        }

        @Override public UIElement element() { return this; }
        @Override public java.util.function.Supplier<Component> accessibleName() { return () -> name; }
        @Override public java.util.function.Supplier<Component> state() {
            return () -> Component.literal(Float.toString(getValue()));
        }
        @Override public void activate() { }

        @Override
        protected void onScrollWheel(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent event) {
            // Unfocused sliders leave wheel input to the enclosing settings list.
            if (!isActive() || !(isFocused() || isChildFocused())) return;
            if (event.deltaY != 0 || event.deltaX != 0) {
                float delta = event.deltaY != 0 ? event.deltaY : event.deltaX;
                setValue(getValue() + (delta > 0 ? step : -step), true);
                event.stopPropagation();
            }
        }

        @Override
        public void drawBackgroundOverlay(GUIContext context) {
            super.drawBackgroundOverlay(context);
            FPSMLdlib2Theme.drawFocusRing(this, context);
        }

        private float normalizedStep() {
            return step / (getMaxValue() - getMinValue());
        }

        @Override
        public Scroller setValue(Float value, boolean notify) {
            if (value == null || !Float.isFinite(value) || step <= 0f) {
                return super.setValue(value, notify);
            }
            double lastStep = Math.floor(((double) getMaxValue() - getMinValue()) / step);
            double index = Math.max(0, Math.min(lastStep, Math.rint(((double) value - getMinValue()) / step)));
            float snapped = (float) (getMinValue() + index * step);
            return super.setValue(clamp(snapped, getMinValue(), getMaxValue()), notify);
        }
    }

    private static Label label(String id, Component text) {
        Label label = new Label();
        label.setId(id);
        label.setValue(text);
        return label;
    }

    private sealed interface SettingListEntry permits CategoryEntry, SettingEntry {
    }

    private record CategoryEntry(String category) implements SettingListEntry {
    }

    private record SettingEntry(MapRoomSettingInfo setting) implements SettingListEntry {
    }
}
