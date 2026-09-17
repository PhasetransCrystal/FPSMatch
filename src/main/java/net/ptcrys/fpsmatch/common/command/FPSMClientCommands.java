package net.ptcrys.fpsmatch.common.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/** Shared paths keep client execution and server-side help in sync. */
public final class FPSMClientCommands {

    private FPSMClientCommands() {}

    public static void append(LiteralArgumentBuilder<CommandSourceStack> root,
                              Command<CommandSourceStack> map, Command<CommandSourceStack> camera) {
        root.then(Commands.literal("mapselect").executes(map));
        root.then(Commands.literal("camera").executes(camera));
    }

    public static void registerServerEntries(LiteralArgumentBuilder<CommandSourceStack> root) {
        Command<CommandSourceStack> clientOnly = context -> {
            context.getSource().sendFailure(Component.translatable("commands.fpsm.client_only"));
            return 0;
        };
        append(root, clientOnly, clientOnly);
        var help = FPSMHelpManager.getInstance();
        help.registerCommandHelp("fpsm mapselect", "commands.fpsm.help.mapselect");
        help.registerCommandHelp("fpsm camera", "commands.fpsm.help.camera");
    }
}
