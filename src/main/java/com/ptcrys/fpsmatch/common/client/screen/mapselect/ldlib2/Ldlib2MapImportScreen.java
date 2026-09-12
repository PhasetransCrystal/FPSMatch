package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleToggle;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.packet.mapselect.ImportMapConfigC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapImportSourceInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapImportSourcesS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.RequestMapImportSourcesC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.List;

/** Selects a same-game-type map and imports map settings/team configurations. */
public final class Ldlib2MapImportScreen extends Ldlib2MapChildScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_import.xml";
    private static final int REQUEST_TIMEOUT_TICKS = 200;
    private Label header;
    private Label subtitle;
    private UIElement panel;
    private VirtualScrollerView<MapImportSourceInfo> list;
    private Label empty;
    private Label status;
    private AccessibleToggle settings;
    private AccessibleToggle shop;
    private AccessibleToggle kits;
    private AccessibleButton refresh;
    private AccessibleButton apply;
    private AccessibleButton back;
    private List<MapImportSourceInfo> sources = List.of();
    private MapImportSourceInfo selected;
    private int confirmation;
    private boolean pending;
    private int pendingTicks;
    private boolean bound;

    public Ldlib2MapImportScreen(MapRoomDetail detail, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT), Component.translatable("gui.fpsm.map_import.title"), detail, parent);
    }

    @Override
    public void init() {
        super.init();
        bind();
        applyResponsiveLayout();
        refreshContent();
        requestRefresh();
    }

    private void bind() {
        if (bound) return;
        UI ui = modularUI.ui;
        header = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.header", Label.class);
        subtitle = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.subtitle", Label.class);
        panel = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.panel", UIElement.class);
        @SuppressWarnings("unchecked")
        VirtualScrollerView<MapImportSourceInfo> sourceList = (VirtualScrollerView<MapImportSourceInfo>)
                Ldlib2XmlUi.require(ui, "fpsmatch.map_import.list", VirtualScrollerView.class);
        list = sourceList;
        empty = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.empty", Label.class);
        status = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.status", Label.class);
        settings = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.settings", AccessibleToggle.class);
        shop = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.shop", AccessibleToggle.class);
        kits = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.kits", AccessibleToggle.class);
        settings.setText(Component.translatable("gui.fpsm.map_import.settings"));
        shop.setText(Component.translatable("gui.fpsm.map_import.shop"));
        kits.setText(Component.translatable("gui.fpsm.map_import.kits"));
        refresh = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.refresh", AccessibleButton.class);
        apply = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.apply", AccessibleButton.class);
        back = Ldlib2XmlUi.require(ui, "fpsmatch.map_import.back", AccessibleButton.class);
        list.setItemUIProvider(this::sourceRow);
        refresh.setOnClick(event -> requestRefresh());
        apply.setOnClick(event -> requestImport());
        back.setOnClick(event -> onClose());
        settings.setOnToggleChanged(value -> { confirmation = 0; refreshContent(false); });
        shop.setOnToggleChanged(value -> { confirmation = 0; refreshContent(false); });
        kits.setOnToggleChanged(value -> { confirmation = 0; refreshContent(false); });
        bound = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!pending || ++pendingTicks < REQUEST_TIMEOUT_TICKS) return;
        pending = false;
        pendingTicks = 0;
        confirmation = 0;
        status.setValue(Component.translatable("gui.fpsm.map_import.timeout"));
        status.textStyle(style -> style.textColor(FPSMMapSelectTheme.DANGER));
        refreshContent(false);
    }

    public boolean acceptsSources(MapImportSourcesS2CPacket packet) {
        return packet.gameType().equals(detail.summary().gameType())
                && packet.mapName().equals(detail.summary().mapName());
    }

    public void applySources(MapImportSourcesS2CPacket packet) {
        if (!acceptsSources(packet)) return;
        sources = List.copyOf(packet.sources());
        pending = false;
        pendingTicks = 0;
        refreshContent();
    }

    public boolean isActionPending() { return pending; }

    public void applyActionResult(MapRoomToastS2CPacket result) {
        pending = false;
        pendingTicks = 0;
        confirmation = 0;
        status.setValue(result.message());
        status.textStyle(style -> style.textColor(result.error() ? FPSMMapSelectTheme.DANGER : FPSMMapSelectTheme.SUCCESS));
        refreshContent(false);
        announce(result.message(), true);
    }

    private void refreshContent() { refreshContent(true); }

    private void refreshContent(boolean resetStatus) {
        if (!bound) return;
        if (selected != null && sources.stream().noneMatch(source -> source.id().equals(selected.id()))) {
            selected = null;
            confirmation = 0;
        }
        subtitle.setValue(Component.literal(detail.summary().gameType() + " / " + detail.summary().mapName()));
        list.setItems(sources);
        list.refreshVisibleItems();
        empty.setVisible(sources.isEmpty());
        updateOptions();
        if (resetStatus && !pending) {
            status.setValue(Component.translatable(selected == null ? "gui.fpsm.map_import.select"
                    : "gui.fpsm.map_import.selected", selected == null ? "" : selected.mapName()));
            status.textStyle(style -> style.textColor(FPSMMapSelectTheme.MUTED));
        }
        apply.setText(Component.translatable(confirmation == 0 ? "gui.fpsm.map_import.action"
                : confirmation == 1 ? "gui.fpsm.map_import.confirm" : "gui.fpsm.map_import.confirm_final"));
        apply.setAvailability(true, selected != null && !pending && anySelected());
        refresh.setAvailability(true, !pending);
        back.setAvailability(true, !pending);
    }

    private void updateOptions() {
        boolean enabled = selected != null && !pending;
        setToggleAvailable(settings, enabled && selected.hasSettings());
        setToggleAvailable(shop, enabled && selected.hasShop());
        setToggleAvailable(kits, enabled && selected.hasStartKits());
    }

    private static void setToggleAvailable(AccessibleToggle toggle, boolean enabled) {
        toggle.setActive(enabled);
        toggle.setAllowHitTest(enabled);
        toggle.setFocusable(enabled);
    }

    private boolean anySelected() { return settings.isOn() || shop.isOn() || kits.isOn(); }

    private UIElement sourceRow(MapImportSourceInfo source) {
        AccessibleButton row = new AccessibleButton();
        row.setId("fpsmatch.map_import.source." + source.id());
        row.setText(Component.literal(source.archiveName() + " / " + source.mapName()));
        row.setAccessibleName(Component.literal(source.archiveName() + " / " + source.mapName()));
        row.setAccessibleState(() -> Component.translatable("gui.fpsm.map_import.source.state", source.mappedTeams()));
        row.setOnClick(event -> {
            selected = source;
            confirmation = 0;
            settings.setOn(source.hasSettings(), false);
            shop.setOn(source.hasShop(), false);
            kits.setOn(source.hasStartKits(), false);
            refreshContent();
        });
        row.layout(layout -> layout.widthPercent(100).height(31).marginBottom(4));
        row.addClass(selected != null && selected.id().equals(source.id()) ? "btn-primary" : "btn-secondary");
        row.setActive(!pending);
        return row;
    }

    private void requestRefresh() {
        if (pending) return;
        pending = true;
        pendingTicks = 0;
        status.setValue(Component.translatable("gui.fpsm.map_import.scanning"));
        refreshContent(false);
        FPSMatch.sendToServer(new RequestMapImportSourcesC2SPacket(detail.summary().gameType(), detail.summary().mapName()));
    }

    private void requestImport() {
        if (pending || selected == null || !anySelected()) return;
        if (confirmation < 2) {
            confirmation++;
            status.setValue(Component.translatable(confirmation == 1 ? "gui.fpsm.map_import.warning"
                    : "gui.fpsm.map_import.warning_final", selected.archiveName(), selected.mapName())
                    .copy().append(Component.literal(" ")).append(selectedGroups()));
            status.textStyle(style -> style.textColor(FPSMMapSelectTheme.WARNING));
            refreshContent(false);
            return;
        }
        pending = true;
        pendingTicks = 0;
        status.setValue(Component.translatable("gui.fpsm.map_import.importing"));
        refreshContent(false);
        FPSMatch.sendToServer(new ImportMapConfigC2SPacket(detail.summary().gameType(), detail.summary().mapName(),
                selected.id(), settings.isOn(), shop.isOn(), kits.isOn()));
    }

    private Component selectedGroups() {
        java.util.ArrayList<Component> groups = new java.util.ArrayList<>();
        if (settings.isOn()) groups.add(Component.translatable("gui.fpsm.map_import.settings"));
        if (shop.isOn()) groups.add(Component.translatable("gui.fpsm.map_import.shop"));
        if (kits.isOn()) groups.add(Component.translatable("gui.fpsm.map_import.kits"));
        Component result = Component.empty();
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) result = result.copy().append(Component.literal(", "));
            result = result.copy().append(groups.get(i));
        }
        return Component.translatable("gui.fpsm.map_import.groups", result);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.drawMapIndex(graphics, width, height);
    }

    private void applyResponsiveLayout() {
        int margin = width < 460 ? 8 : 16;
        int footer = width < 360 ? 112 : 80;
        absolute(header, margin + 2, 8, width - margin * 2 - 4, 22);
        absolute(subtitle, margin + 2, 32, width - margin * 2 - 4, 15);
        absolute(panel, margin, 54, width - margin * 2, Math.max(1, height - 54 - footer - 28));
        absolute(list, 7, 7, Math.max(1, width - margin * 2 - 14), Math.max(1, height - 54 - footer - 42));
        absolute(empty, 12, 14, Math.max(1, width - margin * 2 - 24), 18);
        absolute(status, margin + 2, height - footer - 22, width - margin * 2 - 4, 18);
        int optionWidth = Math.max(1, (width - margin * 2 - 10) / 3);
        AccessibleToggle[] options = {settings, shop, kits};
        for (int i = 0; i < options.length; i++) absolute(options[i], margin + i * (optionWidth + 5), height - footer + 2, optionWidth, 24);
        int buttonWidth = Math.max(1, (width - margin * 2 - 10) / 3);
        AccessibleButton[] buttons = {refresh, apply, back};
        for (int i = 0; i < buttons.length; i++) absolute(buttons[i], margin + i * (buttonWidth + 5), height - 32, buttonWidth, 26);
    }

    private static void absolute(UIElement element, int left, int top, int width, int height) {
        element.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE).rightAuto().bottomAuto()
                .left(left).top(top).width(Math.max(1, width)).height(Math.max(1, height)));
    }
}
