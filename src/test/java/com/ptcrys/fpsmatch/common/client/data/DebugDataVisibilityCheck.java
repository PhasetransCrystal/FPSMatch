package com.ptcrys.fpsmatch.common.client.data;

/** Regression check for held-tool refresh packets arriving while previews are hidden. */
public final class DebugDataVisibilityCheck {
    public static void main(String[] args) {
        DebugData data = new DebugData();
        // Null payloads keep this collection-lifecycle check independent of Minecraft rendering.
        data.upsertRenderableArea("held:area", null);
        data.upsertRenderableArea("map:other", null);
        data.upsertRenderablePoint("held:point:0", null);
        require(data.getAreas().size() == 2 && data.getPoints().size() == 1, "previews start visible");

        data.toggleVisibility();
        // SpawnPointTool clears its prefix and resends the current area/points every 10 ticks.
        for (int tick = 0; tick < 3; tick++) {
            data.removeByPrefix("held:");
            data.upsertRenderableArea("held:area", null);
            data.upsertRenderablePoint("held:point:1", null);
            data.upsertRenderablePoint("held:point:2", null);
            require(!data.isVisible() && data.getAreas().isEmpty() && data.getPoints().isEmpty(),
                    "periodic refresh must not restore hidden previews");
        }
        data.toggleVisibility();
        require(data.getAreas().size() == 2, "restore retained areas");
        require(data.getPoints().size() == 2, "restore latest points only");
        data.removeByPrefix("held:");
        require(data.getAreas().size() == 1 && data.getPoints().isEmpty(), "preserve unrelated areas");

        data.setVisible(false);
        data.clearAll();
        data.setVisible(true);
        require(data.getAreas().isEmpty() && data.getPoints().isEmpty(), "cleared previews must not reappear");
        System.out.println("Debug preview visibility checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
