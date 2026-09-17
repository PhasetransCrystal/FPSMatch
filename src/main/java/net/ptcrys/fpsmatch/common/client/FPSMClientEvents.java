package net.ptcrys.fpsmatch.common.client;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.data.RenderableArea;
import net.ptcrys.fpsmatch.common.client.data.RenderablePoint;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import net.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import net.ptcrys.fpsmatch.common.client.spec.SpectateState;
import net.ptcrys.fpsmatch.common.client.spec.SpectatorCameraController;
import net.ptcrys.fpsmatch.common.effect.FPSMEffectRegister;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionSnapshotS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.OpenMapSelectionC2SPacket;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.Collection;
import java.util.List;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class FPSMClientEvents {

    private static Button mapSelectionButton;
    private static int mapSelectionButtonX;
    private static int mapSelectionButtonY;
    private static int mapSelectionButtonWidth;
    private static int mapSelectionButtonHeight;
    private static boolean pendingOpenMapSelection;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen)) {
            mapSelectionButton = null;
            return;
        }
        if (!FPSMClient.getGlobalData().isMapSelectionButtonVisible()) {
            mapSelectionButton = null;
            return;
        }
        int screenWidth = event.getScreen().width;
        int screenHeight = event.getScreen().height;
        List<AbstractWidget> pauseMenuWidgets = event.getListenersList().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget -> isCenteredPauseMenuWidget(widget, screenWidth))
                .toList();
        int menuTop = pauseMenuWidgets.stream()
                .mapToInt(AbstractWidget::getY)
                .min()
                .orElse(Math.max(0, screenHeight / 4));
        int menuBottom = pauseMenuWidgets.stream()
                .filter(Button.class::isInstance)
                .mapToInt(widget -> widget.getY() + widget.getHeight())
                .max()
                .orElse(menuTop);
        PauseMenuLayoutModel.Placement placement = PauseMenuLayoutModel.belowMenu(
                screenWidth, screenHeight, menuTop, menuBottom);
        if (placement.menuShiftUp() > 0) {
            pauseMenuWidgets.forEach(widget -> widget.setY(widget.getY() - placement.menuShiftUp()));
        }
        mapSelectionButtonX = placement.x();
        mapSelectionButtonY = placement.y();
        mapSelectionButtonWidth = placement.width();
        mapSelectionButtonHeight = placement.height();
        mapSelectionButton = Button.builder(Component.translatable("gui.fpsm.map_select.open"), button -> requestOpenMapSelectionFromPause())
                .pos(mapSelectionButtonX, mapSelectionButtonY)
                .size(mapSelectionButtonWidth, mapSelectionButtonHeight)
                .build();
        event.addListener(mapSelectionButton);
    }

    private static boolean isCenteredPauseMenuWidget(AbstractWidget widget, int screenWidth) {
        int screenCenter = screenWidth / 2;
        int widgetCenter = widget.getX() + widget.getWidth() / 2;
        return Math.abs(widgetCenter - screenCenter) <= 110;
    }

    /**
     * Backup hit-test for the pause map button.
     * Some screen stacks swallow widget clicks while the button still renders.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPauseMapButtonMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof PauseScreen)) {
            return;
        }
        if (mapSelectionButton == null || !FPSMClient.getGlobalData().isMapSelectionButtonVisible()) {
            return;
        }
        if (event.getButton() != 0) {
            return;
        }
        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        if (mouseX < mapSelectionButtonX || mouseX >= mapSelectionButtonX + mapSelectionButtonWidth || mouseY < mapSelectionButtonY || mouseY >= mapSelectionButtonY + mapSelectionButtonHeight) {
            return;
        }
        event.setCanceled(true);
        requestOpenMapSelectionFromPause();
    }

    /**
     * Defer opening until after the current mouse/screen event finishes.
     * Calling setScreen() synchronously from a PauseScreen button press is unreliable.
     */
    static void requestOpenMapSelectionFromPause() {
        if (pendingOpenMapSelection) {
            return;
        }
        pendingOpenMapSelection = true;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            pendingOpenMapSelection = false;
            openMapSelectionFromPause();
        });
    }

    /**
     * Open the Modern UI map browser immediately on the client, then ask the server for a fresh snapshot.
     * Waiting only for the S2C reply fails on singleplayer because PauseScreen freezes the integrated server.
     * Parent is null so closing does not re-open PauseScreen.
     */
    static void openMapSelectionFromPause() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof PauseScreen) && FPSMMapSelectScreens.isMapSelectionScreen(minecraft.screen)) {
            FPSMatch.sendToServer(new OpenMapSelectionC2SPacket());
            return;
        }
        MapSelectionSnapshotS2CPacket snapshot = FPSMClient.getGlobalData().getMapSelectionSnapshot()
                .orElseGet(() -> new MapSelectionSnapshotS2CPacket(List.of(), false, true));
        // Do not keep PauseScreen as parent: reopening it after close re-freezes integrated server flows.
        FPSMMapSelectScreens.openSelection(snapshot, null);
        FPSMatch.sendToServer(new OpenMapSelectionC2SPacket());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (event.phase == TickEvent.Phase.END && SpectateState.isRestricted() && (player == null || !player.isSpectator())) {
            // Also cover sessions entered directly by a team switch, without a killcam.
            SpectateState.set(SpectateMode.FREE);
            SpectatorCameraController.reset();
            net.ptcrys.fpsmatch.common.client.camera.CameraDirector.restoreBase();
        }
        if (player != null && player.hasEffect(FPSMEffectRegister.FLASH_BLINDNESS.get())) {
            mc.getSoundManager().stop();
        }
    }

    @SubscribeEvent
    public static void onLevelRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;

        Collection<RenderableArea> areas = FPSMClient.getGlobalData().getDebugData().getAreas();
        Collection<RenderablePoint> points = FPSMClient.getGlobalData().getDebugData().getPoints();
        if (areas.isEmpty() && points.isEmpty()) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        poseStack.pushPose();

        try {
            Camera camera = event.getCamera();
            Vec3 cameraPos = camera.getPosition();

            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            for (RenderableArea renderable : areas) {
                renderable.render(poseStack, bufferSource);
            }

            for (RenderablePoint renderable : points) {
                renderable.render(poseStack, bufferSource);
            }

            bufferSource.endBatch();
        } finally {
            poseStack.popPose();
        }
    }
}
