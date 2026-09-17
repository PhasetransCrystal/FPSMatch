package net.ptcrys.fpsmatch.common.client.camera;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.eventbus.api.Event;

/** A scene's overlay surface when its camera policy hides the normal HUD. */
public final class CameraOverlayEvent extends Event {

    private final GuiGraphics graphics;
    private final float partialTick;

    public CameraOverlayEvent(GuiGraphics graphics, float partialTick) {
        this.graphics = graphics;
        this.partialTick = partialTick;
    }

    public GuiGraphics graphics() {
        return graphics;
    }

    public float partialTick() {
        return partialTick;
    }
}
