package net.ptcrys.fpsmatch.mixin.editor;

import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchWorldSession;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public abstract class WorkbenchLoadingOverlayMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Overlay;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private void fpsmatch$editorOverlay(Overlay overlay, GuiGraphics graphics, int x, int y, float partial) {
        if (!WorkbenchWorldSession.renderOverlay(overlay, graphics, x, y, partial)) overlay.render(graphics, x, y, partial);
    }
}
