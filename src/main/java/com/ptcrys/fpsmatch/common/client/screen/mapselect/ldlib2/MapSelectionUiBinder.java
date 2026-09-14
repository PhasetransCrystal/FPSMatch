package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UITemplate;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSummary;
import net.minecraft.network.chat.Component;

/**
 * Data-binding layer for the XML-defined map selection page. Row fragments are cloned from
 * {@code fpsmatch:ldlib2/ui/rows/*.xml}; per-status colors remain procedural because they are data.
 */
final class MapSelectionUiBinder {
    static final int ROW_HEIGHT = 56;
    static final int ROW_GAP = 4;
    static final String ROOM_ROW_LAYOUT = "fpsmatch:ldlib2/ui/rows/room_row.xml";

    private MapSelectionUiBinder() {
    }

    static String roomId(MapRoomSummary summary) {
        return MapSelectionWidgetCatalog.ROOM_LIST + "." + summary.gameType() + "." + summary.mapName();
    }

    static UIElement roomRow(UITemplate template, Ldlib2MapSelectionScreen screen, MapRoomSummary summary) {
        UIElement host = template.copy().createUI().rootElement;
        host.layout(layout -> layout.height(ROW_HEIGHT).widthPercent(100).marginBottom(ROW_GAP));
        AccessibleButton row = host.selectId("row", AccessibleButton.class).findFirst().orElse(null);
        if (row == null) {
            FPSMatch.LOGGER.error("[FPSM UI] room row fragment is missing #row button");
            return host;
        }
        String rowId = roomId(summary);
        row.noText();
        row.setId(rowId);
        row.setAccessibleName(() -> Component.translatable(
                "gui.fpsm.map_select.info.map", summary.displayName(), summary.mapName()));
        row.setAccessibleState(() -> Ldlib2MapSelectionScreen.statusText(summary));
        // A room row is the entry point to the unified room/lobby page on every viewport.
        row.setOnClick(event -> screen.openRoomDetail(summary));
        if (screen.usesCompactRoomRows()) {
            row.addClass("compact");
        } else {
            row.removeClass("compact");
        }
        row.removeClass("__selected__");

        int statusColor = Ldlib2MapSelectionScreen.roomStatusColor(summary);
        host.selectId("status", UIElement.class).findFirst().ifPresent(status -> {
            status.setId(rowId + ".status");
            status.style(style -> style.background(new ColorRectTexture(statusColor)));
        });
        host.selectId("preview", Ldlib2MapThumbnailElement.class).findFirst().ifPresent(preview -> {
            preview.setId(rowId + ".preview");
            preview.setThumbnailData(summary.iconTexture(), summary.mapName(), summary.gameType(), summary.displayName());
        });
        host.selectId("name", Label.class).findFirst().ifPresent(name -> {
            name.setId(rowId + ".name");
            name.setValue(Component.literal(summary.displayName()));
        });
        host.selectId("meta", Label.class).findFirst().ifPresent(meta -> {
            meta.setId(rowId + ".meta");
            meta.setValue(Component.literal(
                    Ldlib2MapSelectionScreen.gameTypeText(summary.gameType()).getString() + " / " + summary.mapName()));
        });
        host.selectId("players", Label.class).findFirst().ifPresent(players -> {
            players.setId(rowId + ".players");
            players.setValue(Component.literal(
                    summary.joinedPlayers() + "/" + Ldlib2MapSelectionScreen.maxPlayers(summary)));
            if (summary.full()) {
                players.addClass("danger-text");
            } else {
                players.removeClass("danger-text");
            }
        });
        host.selectId("state", Label.class).findFirst().ifPresent(state -> {
            state.setId(rowId + ".state");
            state.setValue(Ldlib2MapSelectionScreen.statusText(summary));
            // per-status color is data, applied procedurally
            state.textStyle(style -> style.textColor(statusColor));
        });
        return host;
    }

    static Label selectorLabel(Component text) {
        Label label = new Label();
        label.setValue(text);
        label.addClass("selector-candidate");
        return label;
    }

    static Component stateFilterText(String filter) {
        String valueKey = switch (filter == null ? "all" : filter) {
            case "waiting" -> "gui.fpsm.map_select.filter.waiting";
            case "running" -> "gui.fpsm.map_select.filter.running";
            case "open" -> "gui.fpsm.map_select.filter.open";
            default -> "gui.fpsm.map_select.filter.all";
        };
        return Component.translatable("gui.fpsm.map_select.filter.status_selector",
                Component.translatable(valueKey));
    }

    static Component modeFilterText(String mode) {
        String normalized = Ldlib2MapSelectionScreen.normalizeMode(mode);
        Component value = "all".equals(normalized)
                ? Component.translatable("gui.fpsm.map_select.filter.mode.all")
                : Ldlib2MapSelectionScreen.gameTypeText(normalized);
        return Component.translatable("gui.fpsm.map_select.filter.mode_selector", value);
    }

    static void setButtonEnabled(AccessibleButton button, boolean enabled) {
        if (button != null) button.setAvailability(true, enabled);
    }
}
