package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRegionActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.core.data.AreaData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Operator editor for the map boundary and demolition plant regions. */
public final class Ldlib2MapRegionsScreen extends Ldlib2MapChildScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_regions.xml";
    private static final int REQUEST_TIMEOUT_TICKS = 200;
    private Label header;
    private Label subtitle;
    private UIElement listPanel;
    private VirtualScrollerView<RegionEntry> list;
    private UIElement editor;
    private Label selection;
    private Label pos1Label;
    private Label pos2Label;
    private TextField pos1Field;
    private TextField pos2Field;
    private AccessibleButton usePos1;
    private AccessibleButton usePos2;
    private Label status;
    private AccessibleButton add;
    private AccessibleButton apply;
    private AccessibleButton delete;
    private AccessibleButton preview;
    private AccessibleButton back;
    private int selectedIndex = -1;
    private boolean addingBomb;
    private boolean deleteConfirmation;
    private boolean pending;
    private int pendingTicks;
    private boolean bound;

    public Ldlib2MapRegionsScreen(MapRoomDetail detail, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT), Component.translatable("gui.fpsm.map_regions.title"), detail, parent);
    }

    @Override
    public void init() {
        super.init();
        bind();
        applyResponsiveLayout();
        selectMapArea();
        refreshContent();
    }

    private void bind() {
        if (bound) return;
        UI ui = modularUI.ui;
        header = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.header", Label.class);
        subtitle = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.subtitle", Label.class);
        listPanel = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.list_panel", UIElement.class);
        @SuppressWarnings("unchecked")
        VirtualScrollerView<RegionEntry> regionList = (VirtualScrollerView<RegionEntry>)
                Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.list", VirtualScrollerView.class);
        list = regionList;
        editor = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.editor", UIElement.class);
        selection = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.selection", Label.class);
        pos1Label = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.pos1_label", Label.class);
        pos2Label = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.pos2_label", Label.class);
        pos1Field = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.pos1", TextField.class);
        pos2Field = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.pos2", TextField.class);
        usePos1 = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.use_pos1", AccessibleButton.class);
        usePos2 = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.use_pos2", AccessibleButton.class);
        status = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.status", Label.class);
        add = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.add", AccessibleButton.class);
        apply = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.apply", AccessibleButton.class);
        delete = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.delete", AccessibleButton.class);
        preview = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.preview", AccessibleButton.class);
        back = Ldlib2XmlUi.require(ui, "fpsmatch.map_regions.back", AccessibleButton.class);
        list.setItemUIProvider(this::regionRow);
        pos1Field.setAnyString();
        pos2Field.setAnyString();
        pos1Field.textFieldStyle(style -> style.placeholder(Component.literal("x y z")));
        pos2Field.textFieldStyle(style -> style.placeholder(Component.literal("x y z")));
        usePos1.setOnClick(event -> useCurrentPosition(pos1Field));
        usePos2.setOnClick(event -> useCurrentPosition(pos2Field));
        add.setOnClick(event -> beginAdd());
        apply.setOnClick(event -> applyEdit());
        delete.setOnClick(event -> deleteSelected());
        preview.setOnClick(event -> send(MapRegionActionC2SPacket.Action.PREVIEW, -1, BlockPos.ZERO, BlockPos.ZERO));
        back.setOnClick(event -> onClose());
        bound = true;
    }

    @Override
    protected void onDetailApplied() {
        if (!bound) return;
        if (!addingBomb && selectedIndex >= detail.bombAreas().size()) {
            selectedIndex = detail.bombAreas().isEmpty() ? -1 : detail.bombAreas().size() - 1;
        }
        if (!pending) {
            loadSelectedArea();
        }
        refreshContent(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (!pending || ++pendingTicks < REQUEST_TIMEOUT_TICKS) {
            return;
        }
        pending = false;
        pendingTicks = 0;
        deleteConfirmation = false;
        Component timeout = Component.translatable("gui.fpsm.map_regions.action.timeout");
        status.setValue(timeout);
        status.textStyle(style -> style.textColor(FPSMMapSelectTheme.DANGER));
        refreshContent(false);
        announce(timeout, true);
    }

    public boolean isActionPending() {
        return pending;
    }

    public void applyActionResult(MapRoomToastS2CPacket result) {
        pending = false;
        pendingTicks = 0;
        deleteConfirmation = false;
        if (!result.error() && addingBomb) {
            addingBomb = false;
            selectedIndex = Math.max(0, detail.bombAreas().size());
        }
        status.setValue(result.message());
        status.textStyle(style -> style.textColor(result.error()
                ? FPSMMapSelectTheme.DANGER : FPSMMapSelectTheme.SUCCESS));
        refreshContent(false);
        announce(result.message(), true);
    }

    private void refreshContent() {
        refreshContent(true);
    }

    private void refreshContent(boolean resetStatus) {
        subtitle.setValue(Component.literal(detail.summary().gameType() + " / " + detail.summary().mapName()));
        List<RegionEntry> entries = new ArrayList<>();
        entries.add(new RegionEntry(-1, detail.mapArea()));
        for (int i = 0; i < detail.bombAreas().size(); i++) {
            entries.add(new RegionEntry(i, detail.bombAreas().get(i)));
        }
        list.setItems(entries);
        list.refreshVisibleItems();
        selection.setValue(Component.translatable(addingBomb
                ? "gui.fpsm.map_regions.new_bomb"
                : selectedIndex < 0 ? "gui.fpsm.map_regions.map_area" : "gui.fpsm.map_regions.bomb_area",
                selectedIndex + 1));
        if (resetStatus && !pending) {
            status.setValue(Component.translatable(detail.demolitionRegionsSupported()
                    ? "gui.fpsm.map_regions.ready" : "gui.fpsm.map_regions.no_demolition"));
            status.textStyle(style -> style.textColor(FPSMMapSelectTheme.MUTED));
        }
        boolean editable = detail.summary().currentPlayerOp() && !pending;
        pos1Field.setActive(editable);
        pos1Field.setAllowHitTest(editable);
        pos1Field.setFocusable(editable);
        pos2Field.setActive(editable);
        pos2Field.setAllowHitTest(editable);
        pos2Field.setFocusable(editable);
        usePos1.setAvailability(true, editable);
        usePos2.setAvailability(true, editable);
        add.setAvailability(detail.demolitionRegionsSupported(), editable && detail.demolitionRegionsSupported());
        apply.setAvailability(true, editable);
        delete.setAvailability(!addingBomb && selectedIndex >= 0,
                editable && !addingBomb && selectedIndex >= 0);
        delete.setText(Component.translatable(deleteConfirmation
                ? "gui.fpsm.map_regions.delete_confirm" : "gui.fpsm.map_regions.delete"));
        preview.setAvailability(true, !pending);
        back.setAvailability(true, !pending);
    }

    private UIElement regionRow(RegionEntry entry) {
        AccessibleButton row = new AccessibleButton();
        boolean selectedRow = !addingBomb && entry.index() == selectedIndex;
        row.setText(entry.index() < 0
                ? Component.translatable("gui.fpsm.map_regions.map_area")
                : Component.translatable("gui.fpsm.map_regions.bomb_area", entry.index() + 1));
        row.setAccessibleState(() -> Component.literal(format(entry.area().pos1()) + " -> " + format(entry.area().pos2())));
        row.setOnClick(event -> select(entry));
        row.layout(layout -> layout.widthPercent(100).height(27).marginBottom(4));
        row.addClass(selectedRow ? "btn-primary" : "btn-secondary");
        row.setActive(!pending);
        return row;
    }

    private void select(RegionEntry entry) {
        addingBomb = false;
        deleteConfirmation = false;
        selectedIndex = entry.index();
        setFields(entry.area());
        refreshContent();
    }

    private void selectMapArea() {
        addingBomb = false;
        selectedIndex = -1;
        setFields(detail.mapArea());
    }

    private void beginAdd() {
        if (pending || !detail.demolitionRegionsSupported()) return;
        addingBomb = true;
        selectedIndex = -1;
        deleteConfirmation = false;
        BlockPos current = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
        setFields(new AreaData(current, current));
        refreshContent();
    }

    private void applyEdit() {
        Optional<BlockPos> pos1 = parsePosition(pos1Field.getValue());
        Optional<BlockPos> pos2 = parsePosition(pos2Field.getValue());
        if (pos1.isEmpty() || pos2.isEmpty()) {
            Component invalid = Component.translatable("gui.fpsm.map_regions.action.invalid");
            status.setValue(invalid);
            status.textStyle(style -> style.textColor(FPSMMapSelectTheme.DANGER));
            announce(invalid, true);
            return;
        }
        MapRegionActionC2SPacket.Action action = addingBomb
                ? MapRegionActionC2SPacket.Action.ADD_BOMB
                : selectedIndex < 0 ? MapRegionActionC2SPacket.Action.SET_MAP
                : MapRegionActionC2SPacket.Action.UPDATE_BOMB;
        send(action, selectedIndex, pos1.get(), pos2.get());
    }

    private void deleteSelected() {
        if (pending || addingBomb || selectedIndex < 0) return;
        if (!deleteConfirmation) {
            deleteConfirmation = true;
            status.setValue(Component.translatable("gui.fpsm.map_regions.delete_warning", selectedIndex + 1));
            status.textStyle(style -> style.textColor(FPSMMapSelectTheme.WARNING));
            refreshContent(false);
            return;
        }
        send(MapRegionActionC2SPacket.Action.REMOVE_BOMB, selectedIndex, BlockPos.ZERO, BlockPos.ZERO);
    }

    private void send(MapRegionActionC2SPacket.Action action, int index, BlockPos pos1, BlockPos pos2) {
        if (pending) return;
        pending = true;
        pendingTicks = 0;
        status.setValue(Component.translatable("gui.fpsm.map_regions.saving"));
        refreshContent(false);
        FPSMatch.sendToServer(new MapRegionActionC2SPacket(action, detail.summary().gameType(),
                detail.summary().mapName(), index, pos1, pos2));
    }

    private void loadSelectedArea() {
        if (addingBomb) return;
        AreaData area = selectedIndex < 0 ? detail.mapArea()
                : selectedIndex < detail.bombAreas().size() ? detail.bombAreas().get(selectedIndex) : detail.mapArea();
        setFields(area);
    }

    private void setFields(AreaData area) {
        pos1Field.setText(format(area.pos1()), false);
        pos2Field.setText(format(area.pos2()), false);
    }

    private void useCurrentPosition(TextField field) {
        if (minecraft.player != null) {
            field.setText(format(minecraft.player.blockPosition()), false);
        }
    }

    private static Optional<BlockPos> parsePosition(String value) {
        if (value == null) return Optional.empty();
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 3) return Optional.empty();
        try {
            return Optional.of(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        FPSMLdlib2Backdrop.drawMapIndex(graphics, width, height);
    }

    private void applyResponsiveLayout() {
        int margin = width < 460 ? 8 : 16;
        int contentWidth = width - margin * 2;
        boolean stacked = width < 280;
        int top = 54;
        int columns = contentWidth < 500 ? 3 : 5;
        int buttonRows = (5 + columns - 1) / columns;
        int footer = 36 + buttonRows * 31;
        int contentHeight = Math.max(80, height - top - footer);
        absolute(header, margin + 2, 8, contentWidth - 4, 22);
        absolute(subtitle, margin + 2, 32, contentWidth - 4, 15);
        if (stacked) {
            int listHeight = Math.max(55, contentHeight * 42 / 100);
            absolute(listPanel, margin, top, contentWidth, listHeight);
            absolute(list, 6, 6, contentWidth - 12, listHeight - 12);
            absolute(editor, margin, top + listHeight + 6, contentWidth, contentHeight - listHeight - 6);
        } else {
            int listWidth = Math.max(90, Math.min(180, contentWidth * 32 / 100));
            absolute(listPanel, margin, top, listWidth, contentHeight);
            absolute(list, 6, 6, listWidth - 12, contentHeight - 12);
            absolute(editor, margin + listWidth + 6, top, contentWidth - listWidth - 6, contentHeight);
        }
        int editorWidth = stacked ? contentWidth
                : contentWidth - Math.max(90, Math.min(180, contentWidth * 32 / 100)) - 6;
        absolute(selection, 8, 8, editorWidth - 16, 16);
        layoutPositionRow(pos1Label, pos1Field, usePos1, editorWidth, 32);
        layoutPositionRow(pos2Label, pos2Field, usePos2, editorWidth, 61);
        absolute(status, margin + 2, height - footer + 4, contentWidth - 4, 18);
        int buttonWidth = Math.max(1, (contentWidth - (columns - 1) * 5) / columns);
        AccessibleButton[] buttons = {add, apply, delete, preview, back};
        for (int i = 0; i < buttons.length; i++) {
            absolute(buttons[i], margin + i % columns * (buttonWidth + 5),
                    height - 32 - (buttonRows - 1 - i / columns) * 31, buttonWidth, 26);
        }
    }

    private static void layoutPositionRow(Label label, TextField field, AccessibleButton use, int width, int top) {
        absolute(label, 8, top + 6, 52, 14);
        int useWidth = Math.min(96, Math.max(68, width / 4));
        absolute(field, 62, top, Math.max(40, width - 78 - useWidth), 24);
        absolute(use, Math.max(66, width - useWidth - 8), top, useWidth, 24);
    }

    private static void absolute(UIElement element, int left, int top, int width, int height) {
        element.layout(layout -> layout.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto().left(left).top(top).width(Math.max(1, width)).height(Math.max(1, height)));
    }

    private record RegionEntry(int index, AreaData area) {
    }
}
