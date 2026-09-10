package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.MapThumbnailRenderer;

/** LDLib2 bridge for the existing map preview renderer. Data is bound post-construction. */
@LDLRegister(name = "map-thumbnail", group = "fpsm", registry = "ldlib2:ui_element")
public class Ldlib2MapThumbnailElement extends UIElement {
    private String texture = "";
    private String mapName = "";
    private String gameType = "";
    private String displayName = "";

    public Ldlib2MapThumbnailElement() {
        setAllowHitTest(false);
    }

    Ldlib2MapThumbnailElement(String id, String texture, String mapName, String gameType, String displayName) {
        this();
        setId(id);
        setThumbnailData(texture, mapName, gameType, displayName);
    }

    public Ldlib2MapThumbnailElement setThumbnailData(String texture, String mapName, String gameType, String displayName) {
        this.texture = texture == null ? "" : texture;
        this.mapName = mapName == null ? "" : mapName;
        this.gameType = gameType == null ? "" : gameType;
        this.displayName = displayName == null ? "" : displayName;
        return this;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        if (getSizeWidth() <= 0 || getSizeHeight() <= 0) return;
        MapThumbnailRenderer.render(
                context.graphics,
                Math.round(getPositionX()),
                Math.round(getPositionY()),
                Math.round(getSizeWidth()),
                Math.round(getSizeHeight()),
                texture,
                mapName,
                gameType,
                displayName,
                false
        );
    }
}
