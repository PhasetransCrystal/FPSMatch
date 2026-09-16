package com.ptcrys.fpsmatch.common.client;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.camera.CameraDirector;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FPSMClientCommands {
    private FPSMClientCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        var root = Commands.literal("fpsm");
        // No root executor: unknown client branches must fall through to the server.
        com.ptcrys.fpsmatch.common.command.FPSMClientCommands.append(root, context -> {
            if (!FPSMClient.getGlobalData().isMapSelectionButtonVisible()) {
                context.getSource().sendFailure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
                return 0;
            }
            FPSMClientEvents.requestOpenMapSelectionFromPause();
            return 1;
        }, context -> {
            context.getSource().sendSuccess(() -> Component.literal(CameraDirector.status()), false);
            return 1;
        });
        event.getDispatcher().register(root);
    }
}
