package com.ptcrys.fpsmatch.common.client.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DebugData {
    private final Map<String, RenderableArea> areas = new LinkedHashMap<>();
    private final Map<String, RenderablePoint> points = new LinkedHashMap<>();
    private boolean visible = true;

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void toggleVisibility() {
        visible = !visible;
    }

    public void addRenderableArea(RenderableArea area) {
        upsertRenderableArea(area.key(), area);
    }

    public void upsertRenderableArea(String key, RenderableArea area) {
        areas.put(key, area);
    }

    public void upsertRenderablePoint(String key, RenderablePoint point) {
        points.put(key, point);
    }

    public Collection<RenderableArea> getAreas() {
        return visible ? areas.values() : List.of();
    }

    public Collection<RenderablePoint> getPoints() {
        return visible ? points.values() : List.of();
    }

    public void removeByPrefix(String prefix) {
        areas.keySet().removeIf(key -> key.startsWith(prefix));
        points.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void clearAll() {
        areas.clear();
        points.clear();
    }

    public void clearAreas() {
        clearAll();
    }
}
