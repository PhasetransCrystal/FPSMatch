package net.ptcrys.fpsmatch.common.client.camera;

import net.ptcrys.fpsmatch.common.camera.CameraEndReason;
import net.ptcrys.fpsmatch.common.client.event.FPSMClientResetEvent;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = "fpsmatch")
public final class CameraEvents {

    private static long renderFrame;
    private static long overlayFrame = -1;

    private CameraEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void validate(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) CameraDirector.validate();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !Minecraft.getInstance().isPaused()) CameraDirector.tick();
    }

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ++renderFrame;
            CameraDirector.prepareFrame(event.renderTickTime);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void angles(ViewportEvent.ComputeCameraAngles event) {
        CameraFrame frame = CameraDirector.frame();
        if (frame == null || frame.pose() == null) return;
        event.setYaw(frame.pose().yaw());
        event.setPitch(frame.pose().pitch());
        event.setRoll(frame.pose().roll());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void fov(ViewportEvent.ComputeFov event) {
        CameraFrame frame = CameraDirector.frame();
        if (frame != null && frame.pose() != null && Double.isFinite(frame.pose().fov())) event.setFOV(frame.pose().fov());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void movement(MovementInputUpdateEvent event) {
        if (!CameraDirector.policy().blockMovement()) return;
        var input = event.getInput();
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (Minecraft.getInstance().screen != null || !CameraDirector.policy().blockInteraction()) return;
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        if (Minecraft.getInstance().screen == null && CameraDirector.hasSession() && CameraDirector.policy().blockInteraction()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void hand(RenderHandEvent event) {
        if (CameraDirector.policy().hideHands()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void overlay(RenderGuiOverlayEvent.Pre event) {
        if (CameraDirector.policy().hideHud()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void gui(RenderGuiEvent.Pre event) {
        if (!CameraDirector.policy().hideHud()) return;
        renderOverlay(event.getGuiGraphics(), event.getPartialTick());
        event.setCanceled(true);
    }

    public static void renderOverlay(net.minecraft.client.gui.GuiGraphics graphics, float partialTick) {
        if (overlayFrame == renderFrame) return;
        overlayFrame = renderFrame;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new CameraOverlayEvent(graphics, partialTick));
        renderFade(graphics);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void player(RenderPlayerEvent.Pre event) {
        if (CameraDirector.policy().hideLocalModel() && event.getEntity() == Minecraft.getInstance().player) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void fade(RenderGuiEvent.Post event) {
        if (!CameraDirector.policy().hideHud()) renderFade(event.getGuiGraphics());
    }

    public static void renderFade(net.minecraft.client.gui.GuiGraphics graphics) {
        CameraFrame frame = CameraDirector.frame();
        if (frame == null || frame.fade() <= 0) return;
        int alpha = Math.min(255, Math.round(frame.fade() * 255));
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
    }

    @SubscribeEvent
    public static void reset(FPSMClientResetEvent event) {
        CameraDirector.reset(CameraEndReason.MATCH_RESET);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        CameraDirector.reset(CameraEndReason.WORLD_CHANGED);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide() && event.getLevel() == Minecraft.getInstance().level) CameraDirector.reset(CameraEndReason.WORLD_CHANGED);
    }
}
