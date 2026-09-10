package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UITemplate;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleModularUIScreen;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleSelector;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleTextField;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.FPSMLdlib2Backdrop;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2AccessibilityController;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2RenderGuard;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapDetailChildScreen;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import com.ptcrys.fpsmatch.common.packet.mapselect.CloseMapViewC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomActionC2SPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSummary;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionSnapshotS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.OpenMapSelectionC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * LDLib2 map-room browser. Details are owned by the unified lobby.
 * Full-width room browser with an inline toolbar. Layout structure lives in
 * {@code fpsmatch:ldlib2/ui/map_selection.xml}; ids mirror
 * {@link MapSelectionWidgetCatalog}. Row fragments are cloned by {@link MapSelectionUiBinder}.
 */
public final class Ldlib2MapSelectionScreen extends AccessibleModularUIScreen
        implements FPSMMapDetailChildScreen {
    private static final String LAYOUT = "fpsmatch:ldlib2/ui/map_selection.xml";
    private static final int PANEL_HORIZONTAL_INSET = 3;
    private static final int PANEL_VERTICAL_INSET = 6;

    private final Screen parent;
    private final Set<MapRoomToastS2CPacket> announcedToasts =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private UIElement headerPanel;
    private Label scopeLabel;
    private VirtualScrollerView<MapRoomSummary> roomList;
    private Label roomListHeading;
    private Label emptyState;
    private AccessibleTextField search;
    private AccessibleSelector<String> stateSelector;
    private AccessibleSelector<String> modeSelector;
    private UIElement toast;
    private Label toastLabel;
    private AccessibleButton refreshButton;
    private AccessibleButton closeButton;
    private UITemplate roomRowTemplate;
    private MapSelectionSnapshotS2CPacket snapshot;
    private MapRoomDetail detail;
    private MapRoomSummary selected;
    private String query = "";
    private String stateFilter = "all";
    private String gameModeFilter = "all";
    private boolean refreshing;
    private String pendingRoomFocus;
    private PendingOpen pendingOpen = PendingOpen.NONE;
    private MapRoomToastS2CPacket dismissedToast;
    private boolean compactLayout;
    private boolean bound;

    private enum PendingOpen {
        NONE,
        DETAIL,
        TEAM
    }

    public Ldlib2MapSelectionScreen(MapSelectionSnapshotS2CPacket snapshot, Screen parent) {
        super(Ldlib2XmlUi.load(LAYOUT), Component.translatable("gui.fpsm.map_select.title"));
        this.parent = parent;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public void init() {
        super.init();
        // Element IDs are registered only after ModularUI.setScreenAndInit (super.init).
        bindRequiredWidgets();
        bind();
        applyResponsiveLayout();
        refreshToast();
        // The browser keeps a room as request state, but does not enter with a
        // visual selection. Focus appears only after an explicit mouse or keyboard action.
        modularUI.clearFocus();
        setKeyboardFocusVisible(false);
    }

    private void bind() {
        if (bound) return;
        UI ui = modularUI.ui;
        headerPanel = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.HEADER + ".band", UIElement.class);
        scopeLabel = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.HEADER_SCOPE, Label.class);
        @SuppressWarnings("unchecked")
        VirtualScrollerView<MapRoomSummary> rooms = (VirtualScrollerView<MapRoomSummary>) Ldlib2XmlUi.require(
                ui, MapSelectionWidgetCatalog.ROOM_LIST, VirtualScrollerView.class);
        roomList = rooms;
        roomListHeading = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.ROOM_LIST_HEADING, Label.class);
        emptyState = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.EMPTY_STATE, Label.class);
        search = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.SEARCH, AccessibleTextField.class);
        @SuppressWarnings("unchecked")
        AccessibleSelector<String> state = (AccessibleSelector<String>) Ldlib2XmlUi.require(
                ui, MapSelectionWidgetCatalog.STATE_FILTER, AccessibleSelector.class);
        stateSelector = state;
        @SuppressWarnings("unchecked")
        AccessibleSelector<String> mode = (AccessibleSelector<String>) Ldlib2XmlUi.require(
                ui, MapSelectionWidgetCatalog.MODE_FILTER, AccessibleSelector.class);
        modeSelector = mode;
        toast = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.TOAST, UIElement.class);
        toastLabel = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.TOAST + ".text", Label.class);
        refreshButton = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.BROWSER_REFRESH, AccessibleButton.class);
        closeButton = Ldlib2XmlUi.require(ui, MapSelectionWidgetCatalog.BROWSER_CLOSE, AccessibleButton.class);

        roomRowTemplate = UITemplate.of(Ldlib2XmlUi.loadUi(MapSelectionUiBinder.ROOM_ROW_LAYOUT).rootElement);

        if (headerPanel == null || scopeLabel == null || roomList == null || roomListHeading == null
                || emptyState == null || search == null
                || stateSelector == null || modeSelector == null || toast == null || toastLabel == null
                || refreshButton == null || closeButton == null) {
            FPSMatch.LOGGER.error("[FPSM UI] map_selection.xml is missing required elements; "
                    + "binding aborted, fallback UI shown (see errors above)");
            return;
        }

        emptyState.setAllowHitTest(false);
        emptyState.setFocusable(false);
        toast.setAllowHitTest(false);
        toast.setFocusable(false);
        roomList.viewContainer(container -> container.layout(layout -> layout
                .paddingHorizontal(5).paddingVertical(5)));
        roomList.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL)
                .adaptiveWidth(false).adaptiveHeight(false));
        roomList.setItemUIProvider(summary -> MapSelectionUiBinder.roomRow(roomRowTemplate, this, summary));

        search.setAnyString();
        search.setAccessibleName(Component.translatable("gui.fpsm.map_select.search"));
        search.textFieldStyle(style -> style.fontSize(10)
                .placeholder(Component.translatable("gui.fpsm.map_select.search")));
        search.setTextResponder(this::setQuery);

        stateSelector.setAccessibleName(() -> MapSelectionUiBinder.stateFilterText(stateSelector.getValue()));
        stateSelector.setCandidateUIProvider(value ->
                MapSelectionUiBinder.selectorLabel(MapSelectionUiBinder.stateFilterText(value)));
        stateSelector.setCandidates(List.of("all", "waiting", "running", "open"));
        stateSelector.setSelected("all", false);
        stateSelector.setOnValueChanged(value -> setStateFilter(value == null ? "all" : value));

        modeSelector.setAccessibleName(() -> MapSelectionUiBinder.modeFilterText(modeSelector.getValue()));
        modeSelector.setCandidateUIProvider(value ->
                MapSelectionUiBinder.selectorLabel(MapSelectionUiBinder.modeFilterText(value)));
        modeSelector.setCandidates(List.of("all"));
        modeSelector.setSelected("all", false);
        modeSelector.setOnValueChanged(value -> setGameModeFilter(value == null ? "all" : value));

        refreshButton.noText();
        refreshButton.setAccessibleName(Component.translatable("gui.fpsm.map_select.refresh"));
        refreshButton.style(style -> style.tooltips(Component.translatable("gui.fpsm.map_select.refresh")));
        refreshButton.setOnClick(event -> refresh());
        closeButton.noText();
        closeButton.setAccessibleName(Component.translatable("gui.done"));
        closeButton.style(style -> style.tooltips(Component.translatable("gui.done")));
        closeButton.setOnClick(event -> onClose());

        bound = true;
        refreshFilterState();
        refreshList();
    }

    @Override
    public void tick() {
        super.tick();
        modularUI.tick();
        if (bound) {
            refreshToast();
            if (pendingRoomFocus != null) {
                modularUI.ui.rootElement.selectId(pendingRoomFocus, AccessibleButton.class)
                        .findFirst().ifPresent(button -> {
                            button.focus();
                            pendingRoomFocus = null;
                        });
            }
        }
    }

    @Override
    public void applyDetail(MapRoomDetail detail) {
        if (detail == null || !bound) {
            return;
        }
        dismissToast();
        List<MapRoomSummary> rooms = filteredRooms();
        retainSelection(rooms);
        if (selected == null || !sameRoom(selected, detail.summary())) {
            // The router consumes a pending child-open immediately after this callback. A late
            // response must not open a child for a room that is no longer selected or visible.
            pendingOpen = PendingOpen.NONE;
            updateEmptyState(rooms);

            refreshActionState();
            roomList.refreshVisibleItems();
            return;
        }
        this.detail = detail;

        refreshActionState();
    }

    public boolean acceptsDetail(MapRoomDetail incoming) {
        return incoming != null && sameRoom(selected, incoming.summary());
    }

    public void applySnapshot(MapSelectionSnapshotS2CPacket snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (!bound) {
            return;
        }
        refreshing = false;
        dismissToast();
        refreshFilterState();
        refreshList();
        refreshToast();
    }

    @Override
    public void onClose() {
        FPSMatch.sendToServer(new CloseMapViewC2SPacket());
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // Always paint a deterministic frame before ModularUI renders. This covers the
        // first frame while the virtual list and XML-bound widgets are being initialized.
        FPSMLdlib2Backdrop.drawMapIndex(graphics, this.width, this.height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        try {
            super.render(graphics, mouseX, mouseY, partialTick);
        } catch (ConcurrentModificationException failure) {
            if (!Ldlib2RenderGuard.ignoreConcurrentModification(this, failure)) {
                throw failure;
            }
        }
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        modularUI.getWidget().mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!bound) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && stateSelector.isOpen()) {
            stateSelector.hide();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && modeSelector.isOpen()) {
            modeSelector.hide();
            return true;
        }
        if (!search.isFocused() && !search.isChildFocused() && !stateSelector.isOpen() && !modeSelector.isOpen()
                && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            setKeyboardFocusVisible(true);
            UIElement focused = modularUI.getFocusedElement();
            if (focused != null) {
                filteredRooms().stream().filter(room -> MapSelectionUiBinder.roomId(room).equals(focused.getId()))
                        .findFirst().ifPresent(room -> selected = room);
            }
            return moveRoomSelection(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void refreshList() {
        List<MapRoomSummary> rooms = filteredRooms();
        retainSelection(rooms);
        roomList.setItems(rooms);
        roomList.virtualScrollerViewStyle(style -> style
                .estimatedItemHeight(MapSelectionUiBinder.ROW_HEIGHT + MapSelectionUiBinder.ROW_GAP)
                .overscanPixels(MapSelectionUiBinder.ROW_HEIGHT * 2));
        updateEmptyState(rooms);
        updateIndexLabels(rooms);

        refreshActionState();
        roomList.refreshVisibleItems();
    }

    private List<MapRoomSummary> filteredRooms() {
        return snapshot.maps().stream()
                .filter(this::matchesQuery)
                .filter(this::matchesStateFilter)
                .filter(this::matchesModeFilter)
                .toList();
    }

    private void retainSelection(List<MapRoomSummary> rooms) {
        if (selected == null) {
            if (!rooms.isEmpty()) {
                selected = rooms.get(0);
            }
            return;
        }
        selected = rooms.stream().filter(summary -> sameRoom(summary, selected)).findFirst().orElse(null);
        if (selected == null) {
            detail = null;
            pendingOpen = PendingOpen.NONE;
            if (!rooms.isEmpty()) {
                selected = rooms.get(0);
            }
        }
    }

    private void updateEmptyState(List<MapRoomSummary> rooms) {
        emptyState.setVisible(rooms.isEmpty());
        if (!rooms.isEmpty()) {
            return;
        }
        emptyState.setValue(Component.translatable(refreshing
                ? "gui.fpsm.map_select.loading"
                : snapshot.maps().isEmpty()
                ? "gui.fpsm.map_select.empty"
                : "gui.fpsm.map_select.empty.filtered"));
    }

    private void updateIndexLabels(List<MapRoomSummary> rooms) {
        scopeLabel.setValue(Component.translatable(
                refreshing ? "gui.fpsm.map_select.sync.refreshing" : "gui.fpsm.map_select.snapshot_count",
                snapshot.maps().size()));
        roomListHeading.setValue(Component.translatable(
                "gui.fpsm.map_select.rooms.filtered", rooms.size(), snapshot.maps().size()));
    }

    private void applyResponsiveLayout() {
        if (!bound) {
            return;
        }
        int horizontalMargin = Math.min(15, Math.max(2, width / 24));
        int verticalMargin = Math.min(10, Math.max(2, height / 24));
        int canvasWidth = Math.max(1, width - horizontalMargin * 2);
        int canvasHeight = Math.max(1, height - verticalMargin * 2);
        MapSelectionLayoutModel layout = MapSelectionLayoutModel.responsive(canvasWidth, canvasHeight);
        compactLayout = layout.compact();
        int originX = horizontalMargin;
        int originY = verticalMargin;
        place(headerPanel, layout.header(), originX, originY);
        scopeLabel.setVisible(layout.header().width() >= 420);
        Ldlib2XmlUi.require(modularUI.ui, MapSelectionWidgetCatalog.HEADER, Label.class)
                .layout(style -> style.right(layout.header().width() >= 420 ? 180 : 56));
        place(toast, layout.toast(), originX, originY);

        // ----- list column: heading row + inline toolbar + virtual list -----
        MapSelectionLayoutModel.Rect listRect = layout.list();
        int listInsetX = insetFor(listRect.width(), PANEL_HORIZONTAL_INSET);
        int listInsetY = insetFor(listRect.height(), PANEL_VERTICAL_INSET);
        int listLeft = originX + listRect.x() + listInsetX;
        int listTop = originY + listRect.y() + listInsetY;
        int listWidth = placedDimension(listRect.width(), PANEL_HORIZONTAL_INSET);
        int listHeight = placedDimension(listRect.height(), PANEL_VERTICAL_INSET);

        // heading row: index counter
        roomListHeading.layout(style -> style.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto()
                .left(listLeft + 7).top(listTop + 3)
                .width(Math.max(1, listWidth - 14)).height(14));

        // Toolbar uses one line on normal widths and two lines on narrow windows.
        int toolbarTop = listTop + 20;
        int toolbarHeight = 22;
        boolean wrappedToolbar = listWidth < 250;
        int selectorWidth = wrappedToolbar ? Math.max(70, (listWidth - 6) / 2) : (listWidth < 300 ? 70 : 88);
        int searchWidth = wrappedToolbar ? listWidth : Math.max(60, listWidth - selectorWidth * 2 - 12);
        search.layout(style -> style.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto()
                .left(listLeft).top(toolbarTop)
                .width(searchWidth).height(toolbarHeight));
        stateSelector.layout(style -> style.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto()
                .left(wrappedToolbar ? listLeft : listLeft + searchWidth + 6)
                .top(wrappedToolbar ? toolbarTop + toolbarHeight + 4 : toolbarTop)
                .width(selectorWidth).height(toolbarHeight));
        modeSelector.layout(style -> style.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto()
                .left(wrappedToolbar ? listLeft + selectorWidth + 6 : listLeft + searchWidth + selectorWidth + 12)
                .top(wrappedToolbar ? toolbarTop + toolbarHeight + 4 : toolbarTop)
                .width(selectorWidth).height(toolbarHeight));

        int listAreaTop = toolbarTop + toolbarHeight + (wrappedToolbar ? toolbarHeight + 10 : 6);
        int listAreaHeight = Math.max(1, listTop + listHeight - listAreaTop);
        roomList.layout(style -> style.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto()
                .left(listLeft).top(listAreaTop)
                .width(listWidth).height(listAreaHeight));

        int emptyHeight = Math.min(20, listAreaHeight);
        emptyState.layout(style -> style.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto()
                .left(listLeft + 4)
                .top(listAreaTop + Math.max(0, (listAreaHeight - emptyHeight) / 2))
                .width(Math.max(1, listWidth - 8)).height(emptyHeight));

        roomList.refreshVisibleItems();
    }

    private static void place(UIElement element, MapSelectionLayoutModel.Rect rect, int originX, int originY) {
        int horizontalInset = insetFor(rect.width(), PANEL_HORIZONTAL_INSET);
        int verticalInset = insetFor(rect.height(), PANEL_VERTICAL_INSET);
        element.layout(style -> style.positionType(YogaPositionType.ABSOLUTE)
                .rightAuto().bottomAuto()
                .left(originX + rect.x() + horizontalInset)
                .top(originY + rect.y() + verticalInset)
                .width(placedDimension(rect.width(), PANEL_HORIZONTAL_INSET))
                .height(placedDimension(rect.height(), PANEL_VERTICAL_INSET)));
    }

    private static int insetFor(int dimension, int requestedInset) {
        return Math.min(requestedInset, Math.max(0, (dimension - 1) / 2));
    }

    private static int placedDimension(int dimension, int inset) {
        return Math.max(1, dimension - insetFor(dimension, inset) * 2);
    }

    void select(MapRoomSummary summary) {
        dismissToast();
        boolean alreadySelected = sameRoom(selected, summary);
        if (alreadySelected && detail != null) {
            return;
        }
        selected = summary;
        detail = null;
        pendingOpen = PendingOpen.NONE;

        refreshActionState();
        roomList.refreshVisibleItems();
    }

    /** Opens the unified lobby for a room row. */
    void openRoomDetail(MapRoomSummary summary) {
        if (summary == null) {
            return;
        }
        if (!sameRoom(selected, summary)) {
            selected = summary;
            detail = null;
            pendingOpen = PendingOpen.NONE;

            refreshActionState();
            roomList.refreshVisibleItems();
        }
        openSelectedDetail();
    }

    private boolean moveRoomSelection(int direction) {
        List<MapRoomSummary> rooms = filteredRooms();
        if (rooms.isEmpty()) {
            return false;
        }
        int currentIndex = -1;
        for (int index = 0; index < rooms.size(); index++) {
            if (sameRoom(rooms.get(index), selected)) {
                currentIndex = index;
                break;
            }
        }
        int nextIndex = currentIndex < 0
                ? (direction < 0 ? rooms.size() - 1 : 0)
                : Math.max(0, Math.min(rooms.size() - 1, currentIndex + direction));
        MapRoomSummary next = rooms.get(nextIndex);
        if (sameRoom(selected, next)) {
            return true;
        }
        select(next);
        focusVisibleRoom(next);
        return true;
    }

    void setQuery(String value) {
        pendingRoomFocus = null;
        query = value == null ? "" : value.trim();
        dismissToast();
        refreshList();
    }

    MapRoomSummary selectedRoom() {
        return selected;
    }

    boolean usesCompactRoomRows() {
        return compactLayout;
    }

    /** Scrolls the room list so the given room is visible (keyboard arrows). */
    private void focusVisibleRoom(MapRoomSummary summary) {
        pendingRoomFocus = MapSelectionUiBinder.roomId(summary);
        List<MapRoomSummary> rooms = filteredRooms();
        int index = -1;
        for (int candidate = 0; candidate < rooms.size(); candidate++) {
            if (sameRoom(rooms.get(candidate), summary)) {
                index = candidate;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        roomList.verticalScroller.setNormalizedValue(
                (float) normalizedRoomPosition(index, rooms.size())
        );
        roomList.refreshVisibleItems();
    }

    private static double normalizedRoomPosition(int index, int size) {
        return size <= 1 ? 0.0 : index / (double) (size - 1);
    }

    private void requestDetailFor(PendingOpen next) {
        if (selected == null) {
            return;
        }
        pendingOpen = next;

        FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.REQUEST_DETAIL,
                selected.gameType(), selected.mapName(), new UUID(0L, 0L)));
    }

    public boolean consumePendingDetailOpen() {
        if (pendingOpen != PendingOpen.DETAIL) {
            return false;
        }
        pendingOpen = PendingOpen.NONE;
        return true;
    }

    public boolean consumePendingTeamOpen() {
        if (pendingOpen != PendingOpen.TEAM) {
            return false;
        }
        pendingOpen = PendingOpen.NONE;
        return true;
    }

    private void refresh() {
        dismissToast();
        refreshing = true;
        updateEmptyState(filteredRooms());
        updateIndexLabels(filteredRooms());
        refreshActionState();
        FPSMatch.sendToServer(new OpenMapSelectionC2SPacket());
    }

    private void openSelectedDetail() {
        if (selected == null) {
            return;
        }
        requestDetailFor(PendingOpen.DETAIL);
    }

    private boolean matchesQuery(MapRoomSummary summary) {
        if (query.isBlank()) return true;
        String normalized = query.toLowerCase(Locale.ROOT);
        return summary.displayName().toLowerCase(Locale.ROOT).contains(normalized)
                || summary.mapName().toLowerCase(Locale.ROOT).contains(normalized)
                || summary.gameType().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private boolean matchesStateFilter(MapRoomSummary summary) {
        return switch (stateFilter) {
            case "waiting" -> !summary.started();
            case "running" -> summary.started();
            case "open" -> !summary.full() && (!summary.started() || summary.allowJoinInProgress());
            default -> true;
        };
    }

    private boolean matchesModeFilter(MapRoomSummary summary) {
        return "all".equals(gameModeFilter)
                || gameModeFilter.equals(normalizeMode(summary.gameType()));
    }

    private void setStateFilter(String filter) {
        stateFilter = Set.of("all", "waiting", "running", "open").contains(filter) ? filter : "all";
        stateSelector.setSelected(stateFilter, false);
        refreshList();
    }

    private void setGameModeFilter(String filter) {
        gameModeFilter = normalizeMode(filter);
        if (gameModeFilter.isBlank()) {
            gameModeFilter = "all";
        }
        refreshModeSelector();
        refreshList();
    }

    private void refreshFilterState() {
        stateSelector.setSelected(stateFilter, false);
        refreshModeSelector();
    }

    private void refreshModeSelector() {
        List<String> modes = snapshot.maps().stream()
                .map(MapRoomSummary::gameType)
                .map(Ldlib2MapSelectionScreen::normalizeMode)
                .filter(mode -> !mode.isBlank())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        List<String> candidates = new ArrayList<>(modes.size() + 1);
        candidates.add("all");
        candidates.addAll(modes);
        if (!candidates.contains(gameModeFilter)) {
            gameModeFilter = "all";
        }
        modeSelector.setCandidates(candidates);
        modeSelector.setSelected(gameModeFilter, false);
    }

    static String normalizeMode(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private void refreshActionState() {
        if (!bound) {
            return;
        }
        // 浏览页只负责选择；进入详情通过房间卡片双击或详情栏卡片点击完成。
        MapSelectionUiBinder.setButtonEnabled(refreshButton, !refreshing);
        MapSelectionUiBinder.setButtonEnabled(closeButton, true);
    }

    public void applyToast() {
        dismissedToast = null;
        refreshToast();
    }

    private void refreshToast() {
        if (!bound) {
            return;
        }
        FPSMClient.getGlobalData().getMapRoomToast().ifPresentOrElse(packet -> {
            if (packet == dismissedToast) {
                toast.setVisible(false);
                return;
            }
            toast.setVisible(true);
            toastLabel.setValue(packet.message());
            if (packet.error()) {
                toast.addClass("__error__");
                toastLabel.textStyle(style -> style.textColor(FPSMMapSelectTheme.DANGER));
            } else {
                toast.removeClass("__error__");
                toastLabel.textStyle(style -> style.textColor(FPSMMapSelectTheme.SUCCESS));
            }
            if (announcedToasts.add(packet)) {
                accessibility().announce(packet.message(), packet.error());
            }
        }, () -> toast.setVisible(false));
    }

    private void dismissToast() {
        if (!bound) {
            return;
        }
        FPSMClient.getGlobalData().getMapRoomToast().ifPresent(packet ->
                dismissedToast = packet
        );
        toast.setVisible(false);
    }

    private MapRoomSummary summaryFor(MapRoomSummary source) {
        return snapshot.maps().stream().filter(summary -> sameRoom(summary, source)).findFirst().orElse(null);
    }

    static boolean sameRoom(MapRoomSummary first, MapRoomSummary second) {
        return first != null && second != null && first.gameType().equals(second.gameType()) && first.mapName().equals(second.mapName());
    }

    private static String roomKey(MapRoomSummary summary) {
        return summary == null ? "" : summary.gameType() + "\u0000" + summary.mapName();
    }

    static String maxPlayers(MapRoomSummary summary) {
        return summary.maxPlayers() < 0 ? "?" : Integer.toString(summary.maxPlayers());
    }

    static int roomStatusColor(MapRoomSummary summary) {
        if (summary.full()) return FPSMMapSelectTheme.DANGER;
        if (summary.debug()) return FPSMMapSelectTheme.ACCENT;
        if (summary.started()) return summary.allowJoinInProgress() ? FPSMMapSelectTheme.WARNING : FPSMMapSelectTheme.DANGER;
        return FPSMMapSelectTheme.SUCCESS;
    }

    static Component statusText(MapRoomSummary summary) {
        if (summary.full()) return Component.translatable("gui.fpsm.map_select.full");
        if (summary.debug()) return Component.translatable("gui.fpsm.map_select.status.debug");
        if (summary.started()) return Component.translatable(summary.allowJoinInProgress()
                ? "gui.fpsm.map_select.status.started_joinable" : "gui.fpsm.map_select.status.started");
        return Component.translatable("gui.fpsm.map_select.status.waiting");
    }

    /** Catalog id self-check: log every id the binder expects before binding. */
    private void bindRequiredWidgets() {
        List<Ldlib2XmlUi.Binding<?>> bindings = new ArrayList<>();
        for (String id : MapSelectionWidgetCatalog.ids()) {
            Class<? extends UIElement> type = switch (id) {
                case MapSelectionWidgetCatalog.ROOM_LIST ->
                        VirtualScrollerView.class;
                case MapSelectionWidgetCatalog.SEARCH -> AccessibleTextField.class;
                case MapSelectionWidgetCatalog.STATE_FILTER, MapSelectionWidgetCatalog.MODE_FILTER ->
                        AccessibleSelector.class;
                case MapSelectionWidgetCatalog.BROWSER_REFRESH, MapSelectionWidgetCatalog.BROWSER_CLOSE ->
                        AccessibleButton.class;
                default -> UIElement.class;
            };
            bindings.add(Ldlib2XmlUi.Binding.of(id, type));
        }
        Ldlib2XmlUi.verify(modularUI.ui, bindings);
    }
}
