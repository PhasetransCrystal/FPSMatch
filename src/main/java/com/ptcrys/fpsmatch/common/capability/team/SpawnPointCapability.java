package com.ptcrys.fpsmatch.common.capability.team;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import com.ptcrys.fpsmatch.common.command.FPSMCommand;
import com.ptcrys.fpsmatch.common.command.FPSMCommandSuggests;
import com.ptcrys.fpsmatch.common.command.FPSMHelpManager;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.data.SpawnPointData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.BaseTeam;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.util.SpawnPointSafety;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import javax.annotation.Nonnull;

public class SpawnPointCapability extends TeamCapability implements FPSMCapability.Savable<List<SpawnPointData>> {

    private final List<SpawnPointData> spawnPointsData = new ArrayList<>();

    public SpawnPointCapability(BaseTeam team) {
        super(team);
    }

    /**
     * 注册能力到全局管理器
     */
    public static void register() {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, SpawnPointCapability.class, new TeamCapability.Factory<>() {

            @Override
            public boolean isOriginal() {
                return true;
            }

            @Override
            public SpawnPointCapability create(BaseTeam team) {
                return new SpawnPointCapability(team);
            }

            @Override
            public Command command() {
                return new SpawnPointCommand();
            }
        });
    }

    public void setAllSpawnPointData(List<SpawnPointData> spawnPointsData) {
        this.spawnPointsData.clear();
        this.spawnPointsData.addAll(spawnPointsData);
    }

    public boolean randomSpawnPoints() {
        return assignNextSpawnPoints();
    }

    public void addSpawnPointData(@Nonnull SpawnPointData data) {
        this.spawnPointsData.add(data);
    }

    public boolean addSpawnPointDataIfAbsent(@Nonnull SpawnPointData data) {
        if (this.spawnPointsData.contains(data)) {
            return false;
        }
        this.spawnPointsData.add(data);
        return true;
    }

    public void addAllSpawnPointData(@Nonnull List<SpawnPointData> data) {
        this.spawnPointsData.addAll(data);
    }

    public List<SpawnPointData> getSpawnPointsData() {
        return spawnPointsData;
    }

    public boolean removeSpawnPointData(@Nonnull SpawnPointData data) {
        return this.spawnPointsData.remove(data);
    }

    public Optional<SpawnPointData> removeSpawnPointData(int index) {
        if (index < 0 || index >= this.spawnPointsData.size()) {
            return Optional.empty();
        }
        return Optional.of(this.spawnPointsData.remove(index));
    }

    public Optional<SpawnPointData> assignNextSpawnPoint(UUID playerId) {
        PlayerData playerData = this.team.getPlayerData(playerId).orElse(null);
        if (playerData == null) {
            return Optional.empty();
        }

        List<SpawnPointData> allPoints = this.getUniqueSpawnPoints();
        if (allPoints.isEmpty()) {
            return Optional.empty();
        }

        SpawnPointData previousPoint = playerData.getSpawnPointsData();
        Set<SpawnPointData> reservedByTeammates = this.team.getPlayersData().stream()
                .filter(data -> !playerId.equals(data.getOwner()))
                .map(PlayerData::getSpawnPointsData)
                .filter(Objects::nonNull)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);

        List<SpawnPointData> exclusivePoints = allPoints.stream()
                .filter(point -> !reservedByTeammates.contains(point))
                .toList();

        SpawnPointData nextPoint = this.pickSpawnPoint(exclusivePoints, previousPoint)
                .or(() -> this.pickSpawnPoint(allPoints, previousPoint))
                .orElse(null);
        if (nextPoint == null) {
            return Optional.empty();
        }

        playerData.setSpawnPointsData(nextPoint);
        return Optional.of(nextPoint);
    }

    public boolean assignNextSpawnPoints() {
        if (team.isSpectator()) {
            return true;
        }
        if (this.spawnPointsData.isEmpty()) {
            this.sendNoSpawnPointsMessage();
            return false;
        }

        boolean success = true;
        for (UUID playerUUID : new ArrayList<>(this.team.getPlayers().keySet())) {
            success &= this.assignNextSpawnPoint(playerUUID).isPresent();
        }
        return success;
    }

    public void clearSpawnPointsData() {
        spawnPointsData.clear();
    }

    public void clearPlayerSpawnPointAssignments() {
        this.team.getPlayersData().forEach(data -> data.setSpawnPointsData(null));
    }

    private void sendNoSpawnPointsMessage() {
        FPSMCore.getInstance().getServer().sendSystemMessage(Component.translatable("message.fpsmatch.error.no_spawn_points")
                .append(Component.literal("error from -> " + team.name)).withStyle(ChatFormatting.RED));
    }

    private List<SpawnPointData> getUniqueSpawnPoints() {
        return new ArrayList<>(new LinkedHashSet<>(this.spawnPointsData));
    }

    private Optional<SpawnPointData> pickSpawnPoint(List<SpawnPointData> points, @Nullable SpawnPointData previousPoint) {
        if (points.isEmpty()) {
            return Optional.empty();
        }

        List<SpawnPointData> candidatePoints = points;
        if (previousPoint != null && points.size() > 1) {
            List<SpawnPointData> withoutPrevious = points.stream()
                    .filter(point -> !point.equals(previousPoint))
                    .toList();
            if (!withoutPrevious.isEmpty()) {
                candidatePoints = withoutPrevious;
            }
        }

        return Optional.of(candidatePoints.get(new Random().nextInt(candidatePoints.size())));
    }

    @Override
    public void destroy() {
        clearSpawnPointsData();
    }

    @Override
    public boolean isImmutable() {
        return true;
    }

    @Override
    public Codec<List<SpawnPointData>> codec() {
        return SpawnPointData.CODEC.listOf();
    }

    @Override
    public List<SpawnPointData> write(List<SpawnPointData> value) {
        spawnPointsData.clear();
        spawnPointsData.addAll(value);
        return spawnPointsData;
    }

    @Override
    public @Nullable List<SpawnPointData> read() {
        return spawnPointsData;
    }

    static class SpawnPointCommand implements TeamCapability.Factory.Command {

        @Override
        public String getName() {
            return "spawnpoints";
        }

        // 构建指令树，挂载到团队指令后
        @Override
        public LiteralArgumentBuilder<CommandSourceStack> builder(LiteralArgumentBuilder<CommandSourceStack> parent, CommandBuildContext context) {
            return parent
                    .then(Commands.literal("add")
                            .executes(SpawnPointCommand::handleSpawnAdd))
                    .then(Commands.literal("clear")
                            .executes(SpawnPointCommand::handleSpawnClear));
        }

        @Override
        public void help(FPSMHelpManager helper) {
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("spawnpoints"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("spawnpoints add"), Component.translatable("commands.fpsm.help.capability.spawnpoints.add"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("spawnpoints clear"), Component.translatable("commands.fpsm.help.capability.spawnpoints.clear"));
        }

        private static int handleSpawnAdd(CommandContext<CommandSourceStack> context) {
            String teamName = StringArgumentType.getString(context, FPSMCommandSuggests.TEAM_NAME_ARG);
            SpawnPointData spawnPointData = getSpawnPointData(context);
            return FPSMCommand.getMap(context).flatMap(map -> getNormalTeam(context).flatMap(team -> team.getCapabilityMap().get(SpawnPointCapability.class).map(spawnCap -> {
                if (map.isStart()) {
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("gui.fpsm.map_regions.action.in_progress"));
                    return 0;
                }
                if (!map.getServerLevel().dimension().equals(spawnPointData.getDimension())) {
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.spawn_point_tool.dimension_mismatch"));
                    return 0;
                }
                BlockPos spawnBlockPos = spawnPointData.getBlockPos();
                if (!map.getMapArea().isBlockPosInPlacementArea(spawnBlockPos) && !map.getMapArea().isBlockPosInPlacementArea(spawnBlockPos.below())) {
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.spawn_point_tool.outside_map"));
                    return 0;
                }
                if (!SpawnPointSafety.isSafe(map.getServerLevel(), spawnBlockPos)) {
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.spawn_point_tool.unsafe"));
                    return 0;
                }
                if (!spawnCap.addSpawnPointDataIfAbsent(spawnPointData)) {
                    FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.spawn_point_tool.duplicate"));
                    return 0;
                }
                FPSMCore.getInstance().getFPSMDataManager().saveAllData();
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.modify.spawn.add.success", teamName));
                return 1;
            }))).orElseGet(() -> {
                FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.spawn_point_tool.team_not_found", teamName));
                return 0;
            });
        }

        private static int handleSpawnClear(CommandContext<CommandSourceStack> context) {
            String teamName = StringArgumentType.getString(context, FPSMCommandSuggests.TEAM_NAME_ARG);
            Optional<BaseMap> map = FPSMCommand.getMap(context);
            if (map.isEmpty()) {
                FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.spawn_point_tool.team_not_found", teamName));
                return 0;
            }
            if (map.get().isStart()) {
                FPSMCommand.sendFailure(context.getSource(), Component.translatable("gui.fpsm.map_regions.action.in_progress"));
                return 0;
            }

            return getNormalTeam(context)
                    .flatMap(team -> team.getCapabilityMap().get(SpawnPointCapability.class).map(spawnCap -> {
                        spawnCap.clearSpawnPointsData();
                        spawnCap.clearPlayerSpawnPointAssignments();
                        FPSMCore.getInstance().getFPSMDataManager().saveAllData();
                        FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.modify.spawn.clear.success", teamName));
                        return 1;
                    }))
                    .orElseGet(() -> {
                        FPSMCommand.sendFailure(context.getSource(), Component.translatable("message.fpsm.spawn_point_tool.team_not_found", teamName));
                        return 0;
                    });
        }

        private static Optional<ServerTeam> getNormalTeam(CommandContext<CommandSourceStack> context) {
            return FPSMCommand.getTeam(context).filter(ServerTeam::isNormal);
        }

        private static SpawnPointData getSpawnPointData(CommandContext<CommandSourceStack> context) {
            Entity entity = context.getSource().getEntity();
            Vec2 sourceRotation = context.getSource().getRotation();
            float yaw = entity == null ? sourceRotation.y : entity.getYRot();
            float pitch = entity == null ? sourceRotation.x : entity.getXRot();
            return new SpawnPointData(
                    context.getSource().getLevel().dimension(),
                    context.getSource().getPosition(),
                    yaw,
                    pitch);
        }
    }
}
