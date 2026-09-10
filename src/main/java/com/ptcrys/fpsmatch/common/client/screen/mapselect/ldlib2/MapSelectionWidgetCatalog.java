package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import java.util.List;

/**
 * Stable IDs used by the LDLib2 map-room UI and its passive refresh bridge.
 * The shell is a room browser; each row opens the unified lobby/detail page.
 */
public final class MapSelectionWidgetCatalog {
    public static final String ROOT = "fpsmatch.map_selection.root";
    public static final String HEADER = "fpsmatch.map_selection.header";
    public static final String HEADER_SCOPE = "fpsmatch.map_selection.header.scope";
    public static final String SEARCH = "fpsmatch.map_selection.search";
    public static final String STATE_FILTER = "fpsmatch.map_selection.filters.status";
    public static final String MODE_FILTER = "fpsmatch.map_selection.filters.mode";
    public static final String ROOM_LIST = "fpsmatch.map_selection.room_list";
    public static final String ROOM_LIST_HEADING = "fpsmatch.map_selection.room_list.heading";
    public static final String EMPTY_STATE = "fpsmatch.map_selection.empty";
    public static final String TOAST = "fpsmatch.map_selection.toast";
    public static final String BROWSER_REFRESH = "fpsmatch.map_selection.browser_actions.refresh";
    public static final String BROWSER_CLOSE = "fpsmatch.map_selection.browser_actions.close";

    private MapSelectionWidgetCatalog() {
    }

    public static List<String> ids() {
        return List.of(ROOT, HEADER, HEADER_SCOPE, SEARCH, STATE_FILTER, MODE_FILTER, ROOM_LIST,
                ROOM_LIST_HEADING, EMPTY_STATE,
                TOAST, BROWSER_REFRESH, BROWSER_CLOSE);
    }
}
