package com.ptcrys.fpsmatch.common.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Function3;
import com.mojang.datafixers.util.Pair;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.data.Setting;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.util.MapId;
import com.ptcrys.fpsmatch.core.team.BaseTeam;
import com.ptcrys.fpsmatch.core.team.MapTeams;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

import static net.minecraft.network.chat.CommonComponents.*;


public class FPSMapCommand {
    // 构建map modify命令树
    public static LiteralArgumentBuilder<CommandSourceStack> init(Pair<LiteralArgumentBuilder<CommandSourceStack>, CommandBuildContext> builder) {
        // 获取HelpManager实例
        FPSMHelpManager helpManager = FPSMHelpManager.getInstance();

        // 注册map命令帮助
        helpManager.registerCommandHelp("fpsm map");
        helpManager.registerCommandHelp("fpsm map create", Component.translatable("commands.fpsm.help.map.create"), Component.translatable("commands.fpsm.help.map.create.hover"));
        helpManager.registerCommandHelp("fpsm map modify", Component.translatable("commands.fpsm.help.map.modify"));
        helpManager.registerCommandHelp("fpsm map modify debug", Component.translatable("commands.fpsm.help.map.debug"));
        helpManager.registerCommandHelp("fpsm map modify debug start", Component.translatable("commands.fpsm.help.map.debug.start"));
        helpManager.registerCommandHelp("fpsm map modify debug reset", Component.translatable("commands.fpsm.help.map.debug.reset"));
        helpManager.registerCommandHelp("fpsm map modify debug new_round", Component.translatable("commands.fpsm.help.map.debug.new_round"));
        helpManager.registerCommandHelp("fpsm map modify debug cleanup", Component.translatable("commands.fpsm.help.map.debug.cleanup"));
        helpManager.registerCommandHelp("fpsm map modify debug switch", Component.translatable("commands.fpsm.help.map.debug.switch"));

        // 注册map team命令帮助
        helpManager.registerCommandHelp("fpsm map modify team", Component.translatable("commands.fpsm.help.map.team"));
        helpManager.registerCommandHelp("fpsm map modify team join", Component.translatable("commands.fpsm.help.map.team.join"));
        helpManager.registerCommandHelp("fpsm map modify team leave", Component.translatable("commands.fpsm.help.map.team.leave"));
        helpManager.registerCommandHelp("fpsm map modify team teams");
        helpManager.registerCommandHelp("fpsm map modify team teams players", Component.translatable("commands.fpsm.help.map.team.players"));
        // 注册map capability命令帮助
        helpManager.registerCommandHelp("fpsm map modify capability", Component.translatable("commands.fpsm.help.category.capability"));
        // 注册team capability命令帮助
        helpManager.registerCommandHelp("fpsm map modify team teams capability", Component.translatable("commands.fpsm.help.category.capability"));

        helpManager.registerCommandHelp("fpsm map modify settings", Component.translatable("commands.fpsm.help.map.settings"));
        helpManager.registerCommandHelp("fpsm map modify settings list", Component.translatable("commands.fpsm.help.map.settings.list"));
        helpManager.registerCommandHelp("fpsm map modify settings get", Component.translatable("commands.fpsm.help.map.settings.get"));
        helpManager.registerCommandHelp("fpsm map modify settings set", Component.translatable("commands.fpsm.help.map.settings.set"));
        helpManager.registerCommandHelp("fpsm map modify settings save", Component.translatable("commands.fpsm.help.map.settings.save"));
        helpManager.registerCommandHelp("fpsm map modify settings load", Component.translatable("commands.fpsm.help.map.settings.load"));
        helpManager.registerCommandHelp("fpsm map modify settings reset", "commands.fpsm.help.map.settings.reset");
        helpManager.registerCommandHelp("fpsm map modify settings reset all", "commands.fpsm.help.map.settings.reset_all");
        helpManager.registerCommandHelp("fpsm map modify team teams spectator players", "commands.fpsm.help.map.team.players");

        helpManager.registerCommandParameters("fpsm map modify settings get",
                "*" + FPSMCommandSuggests.SETTING_ARG);
        helpManager.registerCommandParameters("fpsm map modify settings set",
                "*" + FPSMCommandSuggests.SETTING_ARG, "*value");

        // 注册命令参数
        helpManager.registerCommandParameters("fpsm map create", "*" + FPSMCommandSuggests.GAME_TYPE_ARG, "*" + FPSMCommandSuggests.MAP_NAME_ARG, "*from", "*to");
        helpManager.registerCommandParameters("fpsm map modify", "*" + FPSMCommandSuggests.GAME_TYPE_ARG, "*" + FPSMCommandSuggests.MAP_NAME_ARG);
        helpManager.registerCommandParameters("fpsm map modify debug", "*" + FPSMCommandSuggests.ACTION_ARG);
        helpManager.registerCommandParameters("fpsm map modify team join", "*" + FPSMCommandSuggests.TARGETS_ARG);
        helpManager.registerCommandParameters("fpsm map modify team leave", "*" + FPSMCommandSuggests.TARGETS_ARG);
        helpManager.registerCommandParameters("fpsm map modify team teams", "*" + FPSMCommandSuggests.TEAM_NAME_ARG);
        helpManager.registerCommandParameters("fpsm map modify team teams players", "*" + FPSMCommandSuggests.TARGETS_ARG, "*" + FPSMCommandSuggests.ACTION_ARG);
        return builder.getFirst()
                .then(Commands.literal("map").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("create")
                                .then(Commands.argument(FPSMCommandSuggests.GAME_TYPE_ARG, StringArgumentType.string())
                                        .suggests(FPSMCommandSuggests.GAME_TYPES_SUGGESTION)
                                        .then(Commands.argument(FPSMCommandSuggests.MAP_NAME_ARG, StringArgumentType.string())
                                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                                .executes(FPSMapCommand::handleCreateMap))))))
                        .then(Commands.literal("modify")
                                .then(Commands.argument(FPSMCommandSuggests.GAME_TYPE_ARG, StringArgumentType.string())
                                        .suggests(FPSMCommandSuggests.GAME_TYPES_SUGGESTION)
                                        .then(Commands.argument(FPSMCommandSuggests.MAP_NAME_ARG, StringArgumentType.string())
                                                .suggests(FPSMCommandSuggests.MAP_NAMES_WITH_GAME_TYPE_SUGGESTION)
                                                // 调试操作
                                                .then(Commands.literal("debug")
                                                        .then(Commands.argument(FPSMCommandSuggests.ACTION_ARG, StringArgumentType.string())
                                                                .suggests(FPSMCommandSuggests.MAP_DEBUG_SUGGESTION)
                                                                .executes(FPSMapCommand::handleDebugAction)))
                                                .then(buildMapCapabilityCommands(builder.getSecond()))
                                                .then(buildSettingsCommands())
                                                // 团队相关修改
                                                .then(Commands.literal("team")
                                                        .then(Commands.literal("join")
                                                                .executes(FPSMapCommand::handleJoinMapWithoutTarget)
                                                                .then(Commands.argument(FPSMCommandSuggests.TARGETS_ARG, EntityArgument.players())
                                                                        .executes(FPSMapCommand::handleJoinMapWithTarget)))
                                                        .then(Commands.literal("leave")
                                                                .executes(FPSMapCommand::handleLeaveMapWithoutTarget)
                                                                .then(Commands.argument(FPSMCommandSuggests.TARGETS_ARG, EntityArgument.players())
                                                                        .executes(FPSMapCommand::handleLeaveMapWithTarget)))
                                                        .then(Commands.literal("teams")
                                                                .then(Commands.literal("spectator")
                                                                        .then(Commands.literal("players")
                                                                                .then(Commands.argument(FPSMCommandSuggests.TARGETS_ARG, EntityArgument.players())
                                                                                        .then(Commands.argument(FPSMCommandSuggests.ACTION_ARG, StringArgumentType.string())
                                                                                                .suggests(FPSMCommandSuggests.TEAM_ACTION_SUGGESTION)
                                                                                                .executes(FPSMapCommand::handleSpecTeamAction)
                                                                                        )
                                                                                )
                                                                        ))
                                                                .then(Commands.argument(FPSMCommandSuggests.TEAM_NAME_ARG, StringArgumentType.string())
                                                                        .suggests(FPSMCommandSuggests.TEAM_NAMES_SUGGESTION)
                                                                        .then(buildTeamCapabilityCommands(builder.getSecond()))
                                                                        .then(Commands.literal("players")
                                                                                .then(Commands.argument(FPSMCommandSuggests.TARGETS_ARG, EntityArgument.players())
                                                                                        .then(Commands.argument(FPSMCommandSuggests.ACTION_ARG, StringArgumentType.string())
                                                                                                .suggests(FPSMCommandSuggests.TEAM_ACTION_SUGGESTION)
                                                                                                .executes(FPSMapCommand::handleTeamAction)
                                                                                        )
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        ));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildMapCapabilityCommands(CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("capability");
        // 遍历所有注册的TeamCapability
        for (Class<? extends FPSMCapability<?>> capClass : FPSMCapabilityManager.getRegisteredCapabilities(FPSMCapabilityManager.CapabilityType.MAP)) {
            // 获取Capability的Factory
            FPSMCapabilityManager.getRawFactory(capClass).ifPresent(factory -> {
                // 获取Factory中定义的指令
                FPSMCapability.Factory.Command command = factory.command();
                if (command != null) {
                    root.then(command.builder(Commands.literal(command.getName()), context));
                    command.help(FPSMHelpManager.getInstance());
                }
            });
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildTeamCapabilityCommands(CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("capability");
        // 遍历所有注册的TeamCapability
        for (Class<? extends FPSMCapability<?>> capClass : FPSMCapabilityManager.getRegisteredCapabilities(FPSMCapabilityManager.CapabilityType.TEAM)) {
            // 获取Capability的Factory
            FPSMCapabilityManager.getRawFactory(capClass).ifPresent(factory -> {
                // 获取Factory中定义的指令
                FPSMCapability.Factory.Command command = factory.command();
                if (command != null) {
                    // 调用指令的builder方法，挂载到当前节点
                    root.then(command.builder(Commands.literal(command.getName()), context));
                    command.help(FPSMHelpManager.getInstance());
                }
            });
        }
        return root;
    }

    private static int handleCreateMap(CommandContext<CommandSourceStack> context) {
        String mapName = StringArgumentType.getString(context, FPSMCommandSuggests.MAP_NAME_ARG);
        String type = StringArgumentType.getString(context, FPSMCommandSuggests.GAME_TYPE_ARG);
        BlockPos pos1 = BlockPosArgument.getBlockPos(context, "from");
        BlockPos pos2 = BlockPosArgument.getBlockPos(context, "to");

        if (!MapId.isValid(mapName)) {
            FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.map_creator_tool.invalid_name"));
            return 0;
        }

        Function3<ServerLevel, String, AreaData, BaseMap> game = FPSMCore.getInstance().getPreBuildGame(type);
        if (game != null) {
            BaseMap newMap = game.apply(context.getSource().getLevel(), mapName, new AreaData(pos1, pos2));
            if (FPSMCore.getInstance().registerMap(type, newMap)) {
                FPSMCore.getInstance().getFPSMDataManager().saveAllData();
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.create.success", mapName));
                return 1;
            }
            FPSMCommand.sendFailure(context.getSource(), Component.translatable("commands.fpsm.create.failure.duplicate", mapName));
            return 0;
        }
        return 0;
    }

    private static int handleDebugAction(CommandContext<CommandSourceStack> context) {
        String action = StringArgumentType.getString(context, FPSMCommandSuggests.ACTION_ARG);

        return FPSMCommand.getMap(context)
                .map(map -> {
                    switch (action) {
                        case "start":
                            map.start();
                            FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.debug.start.success", map.getMapName()));
                            break;
                        case "reset":
                            map.reset();
                            FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.debug.reset.success", map.getMapName()));
                            break;
                        case "new_round":
                            map.startNewRound();
                            FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.debug.newround.success", map.getMapName()));
                            break;
                        case "cleanup":
                            map.cleanupMap();
                            FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.debug.cleanup.success", map.getMapName()));
                            break;
                        case "switch":
                            boolean debug = map.switchDebugMode();
                            context.getSource().sendSuccess(() -> Component.literal("Debug Mode : " + debug), true);
                            break;
                        default:
                            return 0;
                    }
                    return 1;
                })
                .orElseGet(() -> {
                    String mapName = StringArgumentType.getString(context, FPSMCommandSuggests.MAP_NAME_ARG);
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("commands.fpsm.map.notFound", mapName));
                    return 0;
                });
    }

    // ------------------------------ 团队相关处理方法 ------------------------------
    private static int handleJoinMapWithoutTarget(CommandContext<CommandSourceStack> context) {
        return FPSMCommand.getMap(context)
                .map(map -> handleJoinResult(context.getSource(), context.getSource().getPlayer(), map.join(context.getSource().getPlayer()), null))
                .orElse(0);
    }

    private static int handleJoinMapWithTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, FPSMCommandSuggests.TARGETS_ARG);
        return FPSMCommand.getMap(context)
                .map(map -> {
                    int success = 0;
                    for (ServerPlayer player : players) {
                        success += handleJoinResult(context.getSource(), player, map.join(player), null);
                    }
                    return success;
                })
                .orElse(0);
    }

    private static int handleLeaveMapWithoutTarget(CommandContext<CommandSourceStack> context) {
        return FPSMCommand.getMap(context)
                .map(map -> {
                    map.leave(context.getSource().getPlayer());
                    return 1;
                })
                .orElse(0);
    }

    private static int handleLeaveMapWithTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, FPSMCommandSuggests.TARGETS_ARG);
        return FPSMCommand.getMap(context)
                .map(map -> {
                    players.forEach(map::leave);
                    return 1;
                })
                .orElse(0);
    }

    private static int handleSpecTeamAction(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, FPSMCommandSuggests.TARGETS_ARG);
        String action = StringArgumentType.getString(context, FPSMCommandSuggests.ACTION_ARG);

        return FPSMCommand.getMap(context)
                .map(map -> {
                    BaseTeam team = map.getMapTeams().getSpectatorTeam();
                    if (team == null) {
                        FPSMCommand.sendFailure(context.getSource(), Component.translatable("commands.fpsm.team.leave.failure", "spectator"));
                        return 0;
                    }

                    switch (action) {
                        case "join":
                            int spectatorSuccess = 0;
                            for (ServerPlayer player : players) {
                                spectatorSuccess += handleJoinResult(context.getSource(), player, map.join("spectator", player), "spectator");
                            }
                            return spectatorSuccess;
                        case "leave":
                            players.forEach(player -> {
                                map.leave(player);
                                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.team.leave.success",
                                        player.getDisplayName()));
                            });
                            break;
                        default:
                            FPSMCommand.sendFailure(context.getSource(), Component.translatable("commands.fpsm.team.invalidAction"));
                            return 0;
                    }
                    return players.size();
                })
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("commands.fpsm.map.notFound"));
                    return 0;
                });
    }

    private static int handleTeamAction(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, FPSMCommandSuggests.TARGETS_ARG);
        String teamName = StringArgumentType.getString(context, FPSMCommandSuggests.TEAM_NAME_ARG);
        String action = StringArgumentType.getString(context, FPSMCommandSuggests.ACTION_ARG);

        return FPSMCommand.getMap(context)
                .map(map -> {
                    switch (action) {
                        case "join":
                            int success = 0;
                            for (ServerPlayer player : players) {
                                success += handleJoinResult(context.getSource(), player, map.join(teamName, player), teamName);
                            }
                            return success;
                        case "leave":
                            players.forEach(player -> {
                                map.leave(player);
                                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.team.leave.success",
                                        player.getDisplayName()));
                            });
                            return players.size();
                        default:
                            FPSMCommand.sendFailure(context.getSource(), Component.translatable("commands.fpsm.team.invalidAction"));
                            return 0;
                    }
                })
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("commands.fpsm.map.notFound"));
                    return 0;
                });
    }

    private static int handleJoinResult(CommandSourceStack source, ServerPlayer player, MapTeams.JoinTeamResult result, String requestedTeamName) {
        return switch (result.status()) {
            case JOINED -> {
                FPSMCommand.sendSuccess(source, Component.translatable("commands.fpsm.team.join.success",
                        player.getDisplayName(), result.teamName()));
                yield 1;
            }
            case TEAM_NOT_FOUND -> {
                if (requestedTeamName != null) {
                    FPSMCommand.sendFailure(source, Component.translatable("commands.fpsm.team.join.failure.null", requestedTeamName));
                }
                yield 0;
            }
            case TEAM_FULL -> {
                if (requestedTeamName != null) {
                    FPSMCommand.sendFailure(source, Component.translatable("commands.fpsm.team.join.failure.full", requestedTeamName));
                } else {
                    FPSMCommand.sendFailure(source, Component.translatable("commands.fpsm.team.join.failure.all_full"));
                }
                yield 0;
            }
            case MID_MATCH_JOIN_DISABLED -> {
                FPSMCommand.sendFailure(source, Component.translatable("commands.fpsm.team.join.failure.in_progress"));
                yield 0;
            }
            case NO_AVAILABLE_TEAM, CANCELLED -> 0;
        };
    }

    /**
     * 构建settings命令树
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildSettingsCommands() {
        return Commands.literal("settings")
                // 列出所有配置项
                .then(Commands.literal("list")
                        .executes(FPSMapCommand::handleListSettings))
                // 获取特定配置项的值
                .then(Commands.literal("get")
                        .then(Commands.argument(FPSMCommandSuggests.SETTING_ARG, StringArgumentType.string())
                                .suggests(FPSMCommandSuggests.MAP_SETTINGS_SUGGESTION_PROVIDER)
                                .executes(FPSMapCommand::handleGetSetting)))
                // 修改配置项的值
                .then(Commands.literal("set")
                        .then(Commands.argument(FPSMCommandSuggests.SETTING_ARG, StringArgumentType.string())
                                .suggests(FPSMCommandSuggests.MAP_SETTINGS_SUGGESTION_PROVIDER)
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(FPSMapCommand::handleSetSetting))))
                // 保存配置到文件
                .then(Commands.literal("save")
                        .executes(FPSMapCommand::handleSaveSettings))
                // 从文件加载配置
                .then(Commands.literal("load")
                        .executes(FPSMapCommand::handleLoadSettings))
                // 重置为默认值
                .then(Commands.literal("reset")
                        .then(Commands.argument(FPSMCommandSuggests.SETTING_ARG, StringArgumentType.string())
                                .suggests(FPSMCommandSuggests.MAP_SETTINGS_SUGGESTION_PROVIDER)
                                .executes(FPSMapCommand::handleResetSetting))
                        .then(Commands.literal("all")
                                .executes(FPSMapCommand::handleResetAllSettings)));
    }

    /**
     * 处理列出所有配置项的命令
     */
    private static int handleListSettings(CommandContext<CommandSourceStack> context) {
        return FPSMCommand.getMap(context)
                .map(map -> {
                    Collection<Setting<?>> settings = map.settings();
                    if (settings.isEmpty()) {
                        FPSMCommand.sendSuccess(context.getSource(),
                                Component.translatable("commands.fpsm.settings.list.empty", map.getMapName()));
                        return 1;
                    }

                    MutableComponent builder = Component.literal("");
                    builder.append(Component.translatable("commands.fpsm.settings.list.header", map.getMapName()).withStyle(ChatFormatting.BOLD));
                    builder.append(NEW_LINE);

                    for (Setting<?> setting : settings) {
                        String valueStr = String.valueOf(setting.get());
                        builder.append(NEW_LINE);
                        MutableComponent info = Component.literal("[" + setting.get().getClass().getSimpleName() + "]").withStyle(ChatFormatting.GRAY,ChatFormatting.BOLD);
                        MutableComponent name = Component.literal(setting.getConfigName()).withStyle(ChatFormatting.WHITE,ChatFormatting.BOLD);
                        MutableComponent value = Component.literal(valueStr).withStyle(ChatFormatting.GREEN,ChatFormatting.BOLD);;

                        builder.append(info.append(SPACE).append(name).append(SPACE).append(value));
                    }

                    context.getSource().sendSuccess(() -> builder, true);
                    return 1;
                })
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(),
                            Component.translatable("commands.fpsm.map.notFound"));
                    return 0;
                });
    }

    /**
     * 处理获取特定配置项值的命令
     */
    private static int handleGetSetting(CommandContext<CommandSourceStack> context) {
        String settingName = StringArgumentType.getString(context, FPSMCommandSuggests.SETTING_ARG);

        return FPSMCommand.getMap(context)
                .flatMap(map -> map.findSetting(settingName)
                        .map(setting -> {
                            String valueStr = String.valueOf(setting.get());
                            FPSMCommand.sendSuccess(context.getSource(),
                                    Component.translatable("commands.fpsm.settings.get.success",
                                            settingName, valueStr, map.getMapName()));
                            return 1;
                        }))
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(),
                            Component.translatable("commands.fpsm.settings.notFound", settingName));
                    return 0;
                });
    }

    /**
     * 处理设置配置项值的命令
     */
    private static int handleSetSetting(CommandContext<CommandSourceStack> context) {
        String settingName = StringArgumentType.getString(context, FPSMCommandSuggests.SETTING_ARG);
        String valueStr = StringArgumentType.getString(context, "value");

        return FPSMCommand.getMap(context)
                .flatMap(map -> map.findSetting(settingName)
                        .map(setting -> {
                            try {
                                if (setting.parse(valueStr)) {
                                    FPSMCommand.sendSuccess(context.getSource(),
                                            Component.translatable("commands.fpsm.settings.set.success",
                                                    settingName, valueStr, map.getMapName()));
                                    return 1;
                                } else {
                                    FPSMCommand.sendFailure(context.getSource(),
                                            Component.translatable("commands.fpsm.settings.set.invalidType",
                                                    settingName, valueStr));
                                    return 0;
                                }
                            } catch (Exception e) {
                                FPSMCommand.sendFailure(context.getSource(),
                                        Component.translatable("commands.fpsm.settings.set.error",
                                                settingName, valueStr, e.getMessage()));
                                return 0;
                            }
                        }))
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(),
                            Component.translatable("commands.fpsm.settings.notFound", settingName));
                    return 0;
                });
    }

    /**
     * 处理保存配置到文件的命令
     */
    private static int handleSaveSettings(CommandContext<CommandSourceStack> context) {
        return FPSMCommand.getMap(context)
                .map(map -> {
                    map.saveConfig();
                    FPSMCommand.sendSuccess(context.getSource(),
                            Component.translatable("commands.fpsm.settings.save.success", map.getMapName()));
                    return 1;
                })
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(),
                            Component.translatable("commands.fpsm.map.notFound"));
                    return 0;
                });
    }

    /**
     * 处理从文件加载配置的命令
     */
    private static int handleLoadSettings(CommandContext<CommandSourceStack> context) {
        return FPSMCommand.getMap(context)
                .map(map -> {
                    map.loadConfig();
                    FPSMCommand.sendSuccess(context.getSource(),
                            Component.translatable("commands.fpsm.settings.load.success", map.getMapName()));
                    return 1;
                })
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(),
                            Component.translatable("commands.fpsm.map.notFound"));
                    return 0;
                });
    }

    /**
     * 处理重置特定配置项到默认值的命令
     */
    private static int handleResetSetting(CommandContext<CommandSourceStack> context) {
        String settingName = StringArgumentType.getString(context, FPSMCommandSuggests.SETTING_ARG);

        return FPSMCommand.getMap(context)
                .flatMap(map -> map.findSetting(settingName)
                        .map(setting -> {
                            setting.reset();
                            FPSMCommand.sendSuccess(context.getSource(),
                                    Component.translatable("commands.fpsm.settings.reset.success", settingName));
                            return 1;
                        }))
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(),
                            Component.translatable("commands.fpsm.settings.notFound", settingName));
                    return 0;
                });
    }

    /**
     * 处理重置所有配置项到默认值的命令
     */
    private static int handleResetAllSettings(CommandContext<CommandSourceStack> context) {
        return FPSMCommand.getMap(context)
                .map(map -> {
                    map.settings().forEach(Setting::reset);
                    FPSMCommand.sendSuccess(context.getSource(),
                            Component.translatable("commands.fpsm.settings.resetAll.success", map.getMapName()));
                    return 1;
                })
                .orElseGet(() -> {
                    FPSMCommand.sendFailure(context.getSource(),
                            Component.translatable("commands.fpsm.map.notFound"));
                    return 0;
                });
    }

}
