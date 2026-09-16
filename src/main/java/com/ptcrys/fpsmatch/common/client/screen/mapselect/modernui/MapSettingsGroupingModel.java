package com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui;

import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable grouping for the categorized map-settings list. */
public final class MapSettingsGroupingModel {
    private MapSettingsGroupingModel() {
    }

    public record Group(String category, List<MapRoomSettingInfo> settings) {
        public Group {
            settings = List.copyOf(settings);
        }
    }

    public static List<Group> group(List<MapRoomSettingInfo> settings) {
        return group(settings, Set.of());
    }

    /** An empty category selection deliberately means that every category is visible. */
    public static List<Group> group(List<MapRoomSettingInfo> settings, Set<String> selectedCategories) {
        Map<String, List<MapRoomSettingInfo>> grouped = new LinkedHashMap<>();
        for (MapRoomSettingInfo setting : settings) {
            if (!selectedCategories.isEmpty() && !selectedCategories.contains(setting.category())) {
                continue;
            }
            grouped.computeIfAbsent(setting.category(), ignored -> new ArrayList<>()).add(setting);
        }
        return grouped.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static List<String> categories(List<MapRoomSettingInfo> settings) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        settings.forEach(setting -> categories.add(setting.category()));
        return List.copyOf(categories);
    }
}
