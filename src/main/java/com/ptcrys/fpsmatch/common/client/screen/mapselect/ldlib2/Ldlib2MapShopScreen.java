package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UITemplate;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessiblePanel;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorNavigation;
import com.ptcrys.fpsmatch.common.packet.mapselect.EditableShopInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.shop.OpenShopEditorC2SPacket;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessible, responsive shop picker for opening the server-owned editor menu.
 * Layout structure lives in {@code fpsmatch:ldlib2/ui/map_shop.xml}; this class binds data.
 */
public final class Ldlib2MapShopScreen extends Ldlib2MapChildScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_shop.xml";
    private static final String ROW_LAYOUT = "fpsmatch:ldlib2/ui/rows/shop_row.xml";
    private static final int OPEN_TIMEOUT_TICKS = 200;

    private Label header;
    private Label subtitleLabel;
    private UIElement panel;
    private VirtualScrollerView<EditableShopInfo> list;
    private Label emptyLabel;
    private Label statusLabel;
    private AccessibleButton backButton;
    private UITemplate rowTemplate;
    private boolean compact;
    private boolean bound;
    private boolean openingEditor;
    private int openingTicks;

    public Ldlib2MapShopScreen(MapRoomDetail detail, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT),
                Component.translatable("gui.fpsm.map_shop.title"), detail, parent);
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
        FPSMLdlib2Backdrop.drawMapIndex(graphics, width, height);
    }

    @Override
    public void tick() {
        super.tick();
        if (openingEditor && ++openingTicks >= OPEN_TIMEOUT_TICKS) {
            applyEditorOpenFailure(Component.translatable(
                    "gui.fpsm.shop_editor.open.timeout"));
        }
    }

    @Override
    protected void onDetailApplied() {
        if (bound) {
            refreshContent();
        }
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
        if (statusLabel != null) {
            statusLabel.setValue(message);
            statusLabel.textStyle(style -> style.textColor(FPSMMapSelectTheme.DANGER));
        }
        setBackEnabled(true);
        if (list != null) {
            list.refreshVisibleItems();
        }
        announce(message, true);
    }

    private void bind() {
        UI ui = modularUI.ui;
        header = Ldlib2XmlUi.require(ui, "fpsmatch.map_shop.header", Label.class);
        subtitleLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_shop.subtitle", Label.class);
        panel = Ldlib2XmlUi.require(ui, "fpsmatch.map_shop.panel", UIElement.class);
        emptyLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_shop.empty", Label.class);
        statusLabel = Ldlib2XmlUi.require(ui, "fpsmatch.map_shop.status", Label.class);
        backButton = Ldlib2XmlUi.require(ui, "fpsmatch.map_shop.back", AccessibleButton.class);
        @SuppressWarnings("unchecked")
        VirtualScrollerView<EditableShopInfo> shopList =
                (VirtualScrollerView<EditableShopInfo>) Ldlib2XmlUi.require(
                        ui, "fpsmatch.map_shop.list", VirtualScrollerView.class);
        list = shopList;
        rowTemplate = UITemplate.of(Ldlib2XmlUi.loadUi(ROW_LAYOUT).rootElement);
        if (list != null) {
            list.setItemUIProvider(this::shopRow);
        }
        if (backButton != null) {
            backButton.setOnClick(event -> onClose());
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
        boolean empty = detail.editableShops().isEmpty();
        if (list != null) {
            list.setItems(detail.editableShops());
            list.refreshVisibleItems();
        }
        if (emptyLabel != null) {
            emptyLabel.setVisible(empty);
        }
        if (!openingEditor && statusLabel != null) {
            statusLabel.setValue(Component.translatable(empty
                    ? "gui.fpsm.map_shop.unsupported"
                    : "gui.fpsm.map_shop.selection.ready"));
            statusLabel.textStyle(style -> style.textColor(
                    empty ? FPSMMapSelectTheme.MUTED : FPSMMapSelectTheme.SUCCESS));
        }
    }

    private UIElement shopRow(EditableShopInfo shop) {
        UIElement row = rowTemplate.copy().createUI().rootElement;
        String id = rowId(shop);
        row.setId(id + ".root");
        row.layout(layout -> layout.widthPercent(100).height(compact ? 50 : 40));

        AccessiblePanel entry = row.selectId("row", AccessiblePanel.class).findFirst().orElse(null);
        if (entry == null) {
            FPSMatch.LOGGER.error("[FPSM UI] shop row fragment is missing #row panel");
            return row;
        }
        entry.setId(id);
        entry.setAccessibleName(Component.literal(shop.displayName()));
        entry.setAccessibleState(() -> Component.literal(shop.teamName()));
        entry.setFocusable(false);
        if (compact) {
            entry.addClass("compact");
        } else {
            entry.removeClass("compact");
        }

        Label name = row.selectId("name", Label.class).findFirst().orElse(null);
        if (name != null) {
            name.setId(id + ".name");
            name.setValue(Component.literal(shop.displayName()));
        }
        Label team = row.selectId("team", Label.class).findFirst().orElse(null);
        if (team != null) {
            team.setId(id + ".team");
            team.setValue(Component.literal(shop.teamName()));
            team.setVisible(false);
        }
        AccessibleButton edit = row.selectId("edit", AccessibleButton.class).findFirst().orElse(null);
        if (edit != null) {
            edit.setId(id + ".edit");
            edit.setAccessibleHint(() -> Component.translatable("gui.fpsm.map_shop.edit.hint"));
            edit.setOnClick(event -> openEditor(shop));
            edit.setActive(!openingEditor);
            edit.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                    .leftAuto().bottomAuto().right(compact ? 7 : 8).top(compact ? 12 : 7)
                    .width(compact ? 64 : 86).height(26));
        }
        return row;
    }

    private void openEditor(EditableShopInfo shop) {
        if (openingEditor || !detail.editableShops().contains(shop)) {
            return;
        }
        openingEditor = true;
        openingTicks = 0;
        if (statusLabel != null) {
            statusLabel.setValue(Component.translatable("gui.fpsm.shop_editor.state.opening"));
            statusLabel.textStyle(style -> style.textColor(FPSMMapSelectTheme.WARNING));
        }
        setBackEnabled(false);
        if (list != null) {
            list.refreshVisibleItems();
        }
        MapRoomDetail capturedDetail = detail;
        Screen capturedParent = parent;
        ShopEditorNavigation.beginMapRoom(
                () -> new Ldlib2MapShopScreen(capturedDetail, capturedParent),
                shop.gameType(), shop.mapName(), shop.teamName());
        FPSMatch.sendToServer(new OpenShopEditorC2SPacket(
                shop.gameType(), shop.mapName(), shop.teamName()));
    }

    private void setBackEnabled(boolean enabled) {
        if (backButton == null) {
            return;
        }
        backButton.setActive(enabled);
        backButton.setAllowHitTest(enabled);
        backButton.setFocusable(enabled);
        if (enabled) {
            backButton.removeClass("__disabled__");
        } else {
            if (backButton.isFocused()) {
                backButton.blur();
            }
            backButton.addClass("__disabled__");
        }
    }

    private void applyResponsiveLayout() {
        if (!bound) {
            return;
        }
        compact = width < 460 || height < 300;
        int margin = compact ? 8 : 16;
        int headerHeight = compact ? 48 : 58;
        int actionHeight = compact ? 48 : 44;
        absolute(header, margin + 2, 8, width - margin * 2 - 4, 22);
        absolute(subtitleLabel, margin + 2, 32, width - margin * 2 - 4, 15);
        absolute(panel, margin, headerHeight, width - margin * 2,
                Math.max(1, height - headerHeight - actionHeight));
        absolute(list, 8, 8, Math.max(1, width - margin * 2 - 16),
                Math.max(1, height - headerHeight - actionHeight - 16));
        absolute(emptyLabel, 14, 18, Math.max(1, width - margin * 2 - 28), 20);
        int backWidth = compact ? 82 : 104;
        absolute(statusLabel, margin + 2, height - actionHeight + 13,
                Math.max(1, width - margin * 2 - backWidth - 12), 18);
        absolute(backButton, Math.max(margin, width - margin - backWidth),
                height - actionHeight + 8, backWidth, 28);
        if (list != null) {
            list.virtualScrollerViewStyle(style -> style.estimatedItemHeight(compact ? 53f : 43f));
            list.refreshVisibleItems();
        }
    }

    @Override
    public void onClose() {
        if (openingEditor) {
            return;
        }
        ShopEditorNavigation.clear();
        super.onClose();
    }

    private static String rowId(EditableShopInfo shop) {
        return "fpsmatch.map_shop.row." + shop.gameType() + "." + shop.mapName()
                + "." + shop.teamName();
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
