package com.ptcrys.fpsmatch.common.client.screen.shop.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleModularUIScreen;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleSelector;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Theme;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorNavigation;
import com.ptcrys.fpsmatch.common.packet.mapselect.EditableShopInfo;
import com.ptcrys.fpsmatch.common.packet.shop.OpenShopConfigToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.OpenShopEditorC2SPacket;
import com.ptcrys.fpsmatch.common.packet.shop.ShopConfigToolActionC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LDLib2 overview for the handheld shop configuration tool.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/shop_config_tool.xml}; this class binds data.
 */
public final class Ldlib2ShopConfigToolScreen extends AccessibleModularUIScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/shop_config_tool.xml";
    private static final int OPEN_TIMEOUT_TICKS = 200;

    private UIElement filters;
    private AccessibleSelector<String> typeSelector;
    private AccessibleSelector<String> mapSelector;
    private VirtualScrollerView<EditableShopInfo> shopList;
    private Label emptyLabel;
    private Label statusLabel;
    private AccessibleButton refreshButton;
    private AccessibleButton closeButton;
    private OpenShopConfigToolScreenS2CPacket data;
    private boolean openingEditor;
    private int openingTicks;
    private boolean bound;

    public Ldlib2ShopConfigToolScreen(OpenShopConfigToolScreenS2CPacket data) {
        super(Ldlib2XmlUi.load(LAYOUT), Component.translatable("gui.fpsm.shop_config.title"));
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public void init() {
        super.init();
        bind();
        applyResponsiveLayout();
        applyData(data);
    }

    private void bind() {
        UI ui = modularUI.ui;
        filters = Ldlib2XmlUi.require(ui, "fpsmatch.shop_config.filters", UIElement.class);
        @SuppressWarnings("unchecked")
        AccessibleSelector<String> type = (AccessibleSelector<String>) Ldlib2XmlUi.require(
                ui, "fpsmatch.shop_config.type", AccessibleSelector.class);
        typeSelector = type;
        @SuppressWarnings("unchecked")
        AccessibleSelector<String> map = (AccessibleSelector<String>) Ldlib2XmlUi.require(
                ui, "fpsmatch.shop_config.map", AccessibleSelector.class);
        mapSelector = map;
        @SuppressWarnings("unchecked")
        VirtualScrollerView<EditableShopInfo> list = (VirtualScrollerView<EditableShopInfo>) Ldlib2XmlUi.require(
                ui, "fpsmatch.shop_config.list", VirtualScrollerView.class);
        shopList = list;
        emptyLabel = Ldlib2XmlUi.require(ui, "fpsmatch.shop_config.empty", Label.class);
        statusLabel = Ldlib2XmlUi.require(ui, "fpsmatch.shop_config.status", Label.class);
        refreshButton = Ldlib2XmlUi.require(ui, "fpsmatch.shop_config.refresh", AccessibleButton.class);
        closeButton = Ldlib2XmlUi.require(ui, "fpsmatch.shop_config.close", AccessibleButton.class);

        if (filters == null || typeSelector == null || mapSelector == null || shopList == null
                || emptyLabel == null || statusLabel == null || refreshButton == null
                || closeButton == null) {
            FPSMatch.LOGGER.error("[FPSM UI] shop_config_tool.xml is missing required elements; "
                    + "binding aborted, fallback UI shown (see errors above)");
            return;
        }

        bindSelector(typeSelector, "fpsmatch.shop_config.type");
        bindSelector(mapSelector, "fpsmatch.shop_config.map");
        typeSelector.setOnValueChanged(value -> selectType(value));
        mapSelector.setOnValueChanged(value -> selectMap(value));
        refreshButton.setOnClick(event -> refresh());
        closeButton.setOnClick(event -> onClose());
        shopList.setItemUIProvider(this::shopRow);
        bound = true;
    }

    private static void bindSelector(AccessibleSelector<String> selector, String id) {
        selector.setCandidateUIProvider(value -> {
            Label option = new Label();
            option.setId(id + ".option." + value);
            option.setValue(Component.literal(value == null || value.isBlank() ? "-" : value));
            option.addClass("selector-candidate");
            return option;
        });
        selector.setAccessibleName(Component.literal(id));
    }

    @Override
    public void tick() {
        super.tick();
        if (openingEditor && ++openingTicks >= OPEN_TIMEOUT_TICKS) {
            applyEditorOpenFailure(Component.translatable("gui.fpsm.shop_editor.open.timeout"));
        }
    }

    public void applyData(OpenShopConfigToolScreenS2CPacket data) {
        this.data = Objects.requireNonNull(data, "data");
        if (!bound) {
            return;
        }
        List<String> types = data.maps().stream().map(OpenShopConfigToolScreenS2CPacket.MapEntry::gameType)
                .distinct().toList();
        typeSelector.setCandidates(types);
        String type = types.contains(data.selectedType())
                ? data.selectedType() : (types.isEmpty() ? "" : types.get(0));
        typeSelector.setSelected(type, false);

        List<String> maps = mapsFor(type);
        mapSelector.setCandidates(maps);
        String map = maps.contains(data.selectedMap())
                ? data.selectedMap() : (maps.isEmpty() ? "" : maps.get(0));
        mapSelector.setSelected(map, false);
        shopList.setItems(data.shops());
        shopList.refreshVisibleItems();
        emptyLabel.setValue(Component.translatable(data.maps().isEmpty()
                ? "gui.fpsm.shop_config.no_maps" : "gui.fpsm.shop_config.empty"));
        emptyLabel.setVisible(data.shops().isEmpty());
        if (!openingEditor) {
            statusLabel.setValue(Component.translatable(data.shops().isEmpty()
                    ? "gui.fpsm.shop_config.empty" : "gui.fpsm.shop_config.edit"));
            statusLabel.textStyle(style -> style.textColor(data.shops().isEmpty()
                    ? FPSMLdlib2Theme.MUTED : FPSMLdlib2Theme.SUCCESS));
        }
        refreshButtons();
    }

    public boolean isEditorOpenPending() {
        return openingEditor;
    }

    public void applyEditorOpenFailure(Component message) {
        if (!openingEditor) {
            return;
        }
        openingEditor = false;
        openingTicks = 0;
        statusLabel.setValue(message);
        statusLabel.textStyle(style -> style.textColor(FPSMLdlib2Theme.DANGER));
        refreshButtons();
        announce(message, true);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.draw(graphics, width, height);
    }

    @Override
    public void onClose() {
        if (openingEditor) {
            return;
        }
        ShopEditorNavigation.clear();
        minecraft.setScreen(null);
    }

    private UIElement shopRow(EditableShopInfo shop) {
        AccessibleButton row = new AccessibleButton();
        row.setId("fpsmatch.shop_config.row." + shop.gameType() + "." + shop.mapName()
                + "." + shop.teamName());
        row.setText(Component.literal(shop.displayName() + "  /  " + shop.teamName()));
        row.setAccessibleName(Component.literal(shop.displayName()));
        row.setAccessibleState(() -> Component.literal(shop.teamName()));
        row.setAccessibleHint(() -> Component.translatable("gui.fpsm.shop_config.edit"));
        row.setOnClick(event -> openEditor(shop));
        row.layout(layout -> layout.widthPercent(100).height(30).marginBottom(4));
        row.addClass("btn-secondary");
        row.addClass("compact-btn");
        row.setActive(!openingEditor);
        return row;
    }

    private void openEditor(EditableShopInfo shop) {
        if (openingEditor || !data.shops().contains(shop)) {
            return;
        }
        openingEditor = true;
        openingTicks = 0;
        statusLabel.setValue(Component.translatable("gui.fpsm.shop_editor.state.opening"));
        statusLabel.textStyle(style -> style.textColor(FPSMLdlib2Theme.WARNING));
        refreshButtons();
        ShopEditorNavigation.beginConfigTool(shop.gameType(), shop.mapName(), shop.teamName());
        FPSMatch.sendToServer(new OpenShopEditorC2SPacket(
                shop.gameType(), shop.mapName(), shop.teamName()));
    }

    private void selectType(String type) {
        if (type == null || type.isBlank() || openingEditor) {
            return;
        }
        String map = mapsFor(type).stream().findFirst().orElse("");
        sendSelection(type, map);
    }

    private void selectMap(String map) {
        if (map == null || map.isBlank() || openingEditor) {
            return;
        }
        sendSelection(typeSelector.getValue(), map);
    }

    private void sendSelection(String type, String map) {
        if (type == null || type.isBlank() || map == null || map.isBlank()) {
            return;
        }
        FPSMatch.sendToServer(new ShopConfigToolActionC2SPacket(
                ShopConfigToolActionC2SPacket.Action.SELECT, type, map));
    }

    private void refresh() {
        if (openingEditor) {
            return;
        }
        FPSMatch.sendToServer(new ShopConfigToolActionC2SPacket(
                ShopConfigToolActionC2SPacket.Action.REFRESH,
                typeSelector.getValue() == null ? "" : typeSelector.getValue(),
                mapSelector.getValue() == null ? "" : mapSelector.getValue()));
    }

    private List<String> mapsFor(String type) {
        if (type == null || type.isBlank()) {
            return List.of();
        }
        return data.maps().stream().filter(map -> type.equals(map.gameType()))
                .map(OpenShopConfigToolScreenS2CPacket.MapEntry::mapName).toList();
    }

    private void refreshButtons() {
        boolean enabled = !openingEditor;
        setButtonEnabled(refreshButton, enabled);
        setButtonEnabled(closeButton, enabled);
        typeSelector.setActive(enabled && data.maps().stream()
                .anyMatch(map -> !map.gameType().isBlank()));
        mapSelector.setActive(enabled && data.maps().stream()
                .anyMatch(map -> map.gameType().equals(typeSelector.getValue())));
        shopList.allChildrenStream().filter(AccessibleButton.class::isInstance)
                .map(AccessibleButton.class::cast).forEach(row -> row.setActive(enabled));
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

    private void applyResponsiveLayout() {
        if (!bound) {
            return;
        }
        int margin = Math.min(16, Math.max(8, width / 32));
        int headerHeight = 54;
        int footerHeight = 38;
        absolute(filters, margin, headerHeight, width - margin * 2,
                Math.min(52, Math.max(44, height / 5)));
        int listTop = headerHeight + 60;
        absolute(shopList, margin + 6, listTop + 6, width - margin * 2 - 12,
                Math.max(1, height - listTop - footerHeight - 12));
        absolute(emptyLabel, margin + 14, listTop + 18, width - margin * 2 - 28, 18);
        absolute(statusLabel, margin + 4, height - footerHeight + 10,
                Math.max(1, width - margin * 2 - 210), 16);
        absolute(refreshButton, width - margin - 196, height - footerHeight + 6, 92, 24);
        absolute(closeButton, width - margin - 96, height - footerHeight + 6, 96, 24);
        absolute(typeSelector, 8, 22, Math.max(1, width / 2 - 16), 22);
        absolute(mapSelector, width / 2 + 8, 22, Math.max(1, width / 2 - 16), 22);
        shopList.virtualScrollerViewStyle(style -> style.estimatedItemHeight(34f));
        shopList.refreshVisibleItems();
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
