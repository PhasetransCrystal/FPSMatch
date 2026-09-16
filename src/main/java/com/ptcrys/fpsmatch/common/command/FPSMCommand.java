package com.ptcrys.fpsmatch.common.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import com.ptcrys.fpsmatch.core.capability.CapabilityMap;
import com.ptcrys.fpsmatch.core.capability.map.MapCapability;
import com.ptcrys.fpsmatch.common.event.register.RegisterFPSMCommandEvent;
import com.ptcrys.fpsmatch.core.map.*;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.team.BaseTeam;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.*;

import static com.ptcrys.fpsmatch.common.command.FPSMCommandSuggests.*;

public class FPSMCommand {

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        FPSMHelpManager.getInstance().reset();

        LiteralArgumentBuilder<CommandSourceStack> tree = Commands.literal("fpsm")
                .executes(FPSMCommand::handleHelp)
                .then(Commands.literal("help")
                        .executes(FPSMCommand::handleHelp)
                        .then(Commands.literal("toggle")
                                .then(Commands.argument("hash", IntegerArgumentType.integer())
                                        .executes(FPSMCommand::handleHelpToggle)))
                        );
        CommandBuildContext context = event.getBuildContext();

        Pair<LiteralArgumentBuilder<CommandSourceStack>, CommandBuildContext> builder = Pair.of(tree,context);

        LiteralArgumentBuilder<CommandSourceStack> literal = init(builder);

        RegisterFPSMCommandEvent registerFPSMCommandEvent = new RegisterFPSMCommandEvent(literal,context, FPSMHelpManager.getInstance());
        MinecraftForge.EVENT_BUS.post(registerFPSMCommandEvent);
        FPSMHelpManager.getInstance().bind(dispatcher.register(registerFPSMCommandEvent.getTree()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> init(Pair<LiteralArgumentBuilder<CommandSourceStack>, CommandBuildContext> builder) {
        FPSMBaseCommand.init(builder.getFirst());
        FPSMapCommand.init(builder);
        FPSMClientCommands.registerServerEntries(builder.getFirst());
        return builder.getFirst();
    }

    private static int handleHelp(CommandContext<CommandSourceStack> context) {
        FPSMHelpManager helpManager = FPSMHelpManager.getInstance();
        // 显示命令树
        MutableComponent helpMessage = helpManager.buildCommandTreeHelp(context.getSource());

        context.getSource().sendSuccess(() -> helpMessage, false);
        return 1;
    }
    
    private static int handleHelpToggle(CommandContext<CommandSourceStack> context) {

        int hash = IntegerArgumentType.getInteger(context, "hash");
        FPSMHelpManager helpManager = FPSMHelpManager.getInstance();
        // 切换节点展开/闭合状态
        boolean success = helpManager.toggleNodeExpanded(hash, context.getSource());
        if (success) {
            MutableComponent helpMessage = Component.literal("\n".repeat(2)).append(helpManager.buildCommandTreeHelp(context.getSource()));
            context.getSource().sendSuccess(() -> helpMessage, false);
        }
        return success ? 1 : 0;
    }

    // 辅助方法
    public static Optional<BaseMap> getMap(CommandContext<CommandSourceStack> context) {
        String gameType = StringArgumentType.getString(context, GAME_TYPE_ARG);
        String mapName = StringArgumentType.getString(context, MAP_NAME_ARG);
        return FPSMCore.getInstance().getMapByTypeWithName(gameType,mapName);
    }

    public static Optional<ServerTeam> getTeam(CommandContext<CommandSourceStack> context) {
        String teamName = StringArgumentType.getString(context, TEAM_NAME_ARG);
        Optional<BaseMap> mapOpt = getMap(context);
        if (mapOpt.isPresent()) {
           return mapOpt.get().getMapTeams().getTeamByName(teamName);
        }else{
            return Optional.empty();
        }
    }

    public static Optional<CapabilityMap<BaseMap,MapCapability>> getMapCapabilities(CommandContext<CommandSourceStack> context) {
        Optional<BaseMap> map = getMap(context);
        return map.map(BaseMap::getCapabilityMap);
    }

    public static Optional<CapabilityMap<BaseTeam, TeamCapability>> getTeamCapabilities(CommandContext<CommandSourceStack> context) {
        Optional<ServerTeam> map = getTeam(context);
        return map.map(ServerTeam::getCapabilityMap);
    }

    public static <T extends TeamCapability> Optional<T> getTeamCapability(CommandContext<CommandSourceStack> context , Class<T> clazz) {
        Optional<ServerTeam> team = getTeam(context);
        return team.flatMap(serverTeam -> serverTeam.getCapabilityMap().get(clazz));
    }

    public static <T extends MapCapability> Optional<T> getMapCapability(CommandContext<CommandSourceStack> context , Class<T> clazz) {
        Optional<BaseMap> map = getMap(context);
        return map.flatMap(baseMap -> baseMap.getCapabilityMap().get(clazz));
    }

    public static void sendSuccess(CommandSourceStack source, Component key) {
        source.sendSuccess(() -> key, true);
    }

    public static void sendFailure(CommandSourceStack source, Component key) {
        source.sendFailure(key);
    }

    public static boolean isPlayer(CommandSourceStack source) {
        return source.getEntity() instanceof Player;
    }

    public static ServerPlayer getPlayerOrFail(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (!isPlayer(context.getSource())) {
            sendFailure(context.getSource(), Component.translatable("commands.fpsm.only.player"));
        }
        return context.getSource().getPlayerOrException();
    }
}
