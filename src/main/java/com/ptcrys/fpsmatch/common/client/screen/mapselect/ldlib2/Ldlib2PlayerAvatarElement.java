package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import com.ptcrys.fpsmatch.util.RenderUtil;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/** Renders the face and hat layers from a player's Minecraft skin in an LDLib2 row. */
@LDLRegister(name = "player-avatar", group = "fpsm", registry = "ldlib2:ui_element")
public class Ldlib2PlayerAvatarElement extends UIElement {
    @Nullable
    private ResourceLocation skin;

    public Ldlib2PlayerAvatarElement() {
        setAllowHitTest(false);
    }

    Ldlib2PlayerAvatarElement(String id, UUID uuid, String name) {
        this();
        setId(id);
        setAvatar(uuid, name);
    }

    public Ldlib2PlayerAvatarElement setAvatar(UUID uuid, String name) {
        this.skin = RenderUtil.fetchSkin(uuid, name == null ? "" : name);
        return this;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        if (skin == null) {
            return;
        }
        int size = Math.min(Math.round(getSizeWidth()), Math.round(getSizeHeight()));
        if (size <= 0) {
            return;
        }
        int x = Math.round(getPositionX());
        int y = Math.round(getPositionY());
        context.graphics.blit(skin, x, y, 8, 8, size, size, 64, 64);
        context.graphics.blit(skin, x, y, 40, 8, size, size, 64, 64);
    }
}
