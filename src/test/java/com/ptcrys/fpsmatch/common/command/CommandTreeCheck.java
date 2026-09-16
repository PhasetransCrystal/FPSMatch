package com.ptcrys.fpsmatch.common.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.ptcrys.fpsmatch.common.event.register.RegisterFPSMCommandEvent;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Regression coverage for permission boundaries, actual argument paths and client/server routing. */
public final class CommandTreeCheck {
    public static void main(String[] args) throws Exception {
        var ordinary = source(0, "ordinary");
        var admin = source(2, "admin");
        var secondAdmin = source(2, "second");
        var helper = FPSMHelpManager.getInstance();
        helper.reset();
        var tree = Commands.literal("fpsm");
        FPSMBaseCommand.init(tree);
        var event = new RegisterFPSMCommandEvent(tree, null, helper);
        event.addPlayerChild(Commands.literal("play").executes(context -> 7));
        event.addChild(Commands.literal("secret").requires(source -> source.hasPermission(3)).executes(context -> 9));
        event.addChild(Commands.literal("edit")
                .then(Commands.argument("map", StringArgumentType.word())
                        .then(Commands.literal("set").executes(context -> 1))
                        .then(Commands.literal("clear").executes(context -> 2))));
        helper.registerCommandHelp("fpsm edit", Component.literal("old"));
        helper.registerCommandHelp("fpsm edit", Component.literal("new description"));
        helper.registerCommandHelp("fpsm ghost", Component.literal("not registered"));
        helper.registerCommandParameters("fpsm edit", "*wrong");
        helper.registerCommandParameters("fpsm edit", "*wrong");
        FPSMClientCommands.registerServerEntries(tree);
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var root = dispatcher.register(tree);
        helper.bind(root);

        check(dispatcher.execute("fpsm play", ordinary) == 7, "ordinary player command remains accessible");
        for (String name : new String[]{"save", "reload", "debug", "tacz", "listener_module", "edit", "secret"}) {
            check(!root.getChild(name).canUse(ordinary), "management branch is protected: " + name);
        }
        check(!root.getChild("secret").canUse(admin), "extension keeps its stronger requirement");
        check(root.getChild("secret").canUse(source(3, "owner")), "stronger requirement is satisfiable");
        String publicHelp = helper.buildCommandTreeHelp(ordinary).getString();
        check(!publicHelp.contains("edit") && !publicHelp.contains("secret") && publicHelp.contains("play"), "help filters inaccessible paths");
        check(publicHelp.contains("camera") && publicHelp.contains("mapselect"), "client commands are documented");
        String adminHelp = helper.buildCommandTreeHelp(admin).getString();
        check(adminHelp.contains("new description") && !adminHelp.contains("old"), "descriptions can be updated");
        check(adminHelp.contains("<map>") && !adminHelp.contains("wrong") && !adminHelp.contains("ghost"), "actual registered tree wins over stale metadata");
        var expandable = helper.getCommandTree(admin).stream().filter(line -> line.getString().contains("<map>")).findFirst().orElseThrow();
        String click = expandable.getStyle().getClickEvent().getValue();
        int id = Integer.parseInt(click.substring(click.lastIndexOf(' ') + 1));
        check(helper.toggleNodeExpanded(id, admin), "argument branch expands");
        check(helper.buildCommandTreeHelp(admin).getString().contains("clear"), "expanded branch shows commands");
        check(!helper.buildCommandTreeHelp(secondAdmin).getString().contains("clear"), "viewers have independent expansion");
        check(!helper.toggleNodeExpanded(id, ordinary), "hidden node cannot be toggled by stale chat link");

        var client = new CommandDispatcher<CommandSourceStack>();
        var clientRoot = Commands.literal("fpsm");
        FPSMClientCommands.append(clientRoot, context -> 11, context -> 12);
        client.register(clientRoot);
        check(client.execute("fpsm camera", ordinary) == 12, "camera executes locally");
        check(client.execute("fpsm mapselect", ordinary) == 11, "map selection executes locally");
        for (String input : new String[]{"fpsm", "fpsm help", "fpsm save", "fpsm play"}) {
            try {
                client.execute(input, ordinary);
                throw new AssertionError("client consumed server command: " + input);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
                var builtins = com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS;
                check(expected.getType() == builtins.dispatcherUnknownCommand() || expected.getType() == builtins.dispatcherUnknownArgument(),
                        "Forge will fall through to server: " + input);
            }
        }
        helper.reset();
        helper.bind(Commands.literal("fpsm").build());
        check(!helper.toggleNodeExpanded(id, admin), "reload invalidates old links");
        check(!helper.buildCommandTreeHelp(admin).getString().contains("edit"), "reload drops stale registrations");
        System.out.println("Command tree checks passed");
    }

    private static CommandSourceStack source(int permission, String name) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                name, Component.literal(name), null, null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
