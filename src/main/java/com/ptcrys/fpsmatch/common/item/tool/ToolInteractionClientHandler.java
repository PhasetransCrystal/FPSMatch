package com.ptcrys.fpsmatch.common.item.tool;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.ToolInteractionC2SPacket;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ToolInteractionClientHandler {

    private static boolean isControlDown() {
        Minecraft minecraft = Minecraft.getInstance();
        long window = minecraft.getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private static boolean isMenuModifierDown(Player player) {
        return isControlDown() || player.isShiftKeyDown();
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (!level.isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof WorldToolItem)) {
            return;
        }

        FPSMatch.sendToServer(new ToolInteractionC2SPacket(ToolInteractionAction.LEFT_CLICK_BLOCK, event.getPos()));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (!level.isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof WorldToolItem)) {
            return;
        }

        ToolInteractionAction action = isMenuModifierDown(player) ? ToolInteractionAction.CTRL_RIGHT_CLICK : ToolInteractionAction.RIGHT_CLICK_BLOCK;
        FPSMatch.sendToServer(new ToolInteractionC2SPacket(action, event.getPos()));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (!level.isClientSide || event.getHand() != InteractionHand.MAIN_HAND || !isMenuModifierDown(player)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof WorldToolItem)) {
            return;
        }

        FPSMatch.sendToServer(new ToolInteractionC2SPacket(ToolInteractionAction.CTRL_RIGHT_CLICK, null));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        Player player = event.getEntity();
        if (!isMenuModifierDown(player)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof WorldToolItem)) {
            return;
        }

        FPSMatch.sendToServer(new ToolInteractionC2SPacket(ToolInteractionAction.CTRL_RIGHT_CLICK, null));
    }
}
