package com.ptcrys.fpsmatch.common.packet.team;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.MapTeams;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 队伍管理操作数据包，用于客户端请求移动玩家到指定队伍（需OP权限）
 */
public record TeamManageActionC2SPacket(String mapName, UUID targetPlayer, String targetTeam) {

    public static void encode(TeamManageActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.mapName());
        buf.writeUUID(packet.targetPlayer());
        buf.writeUtf(packet.targetTeam());
    }

    public static TeamManageActionC2SPacket decode(FriendlyByteBuf buf) {
        return new TeamManageActionC2SPacket(buf.readUtf(), buf.readUUID(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            // 验证OP权限
            if (!sender.getServer().getPlayerList().isOp(sender.getGameProfile())) {
                FPSMatch.sendToPlayer(sender, new TeamManageResultS2CPacket(false, Component.translatable("gui.fpsm.team_manage.no_permission")));
                return;
            }

            BaseMap map = FPSMCore.getInstance().getMapByName(mapName).orElse(null);
            if (map == null) {
                FPSMatch.sendToPlayer(sender, new TeamManageResultS2CPacket(false, Component.translatable("gui.fpsm.team_manage.map_not_found")));
                return;
            }

            ServerPlayer target = sender.getServer().getPlayerList().getPlayer(targetPlayer);
            if (target == null) {
                FPSMatch.sendToPlayer(sender, new TeamManageResultS2CPacket(false, Component.translatable("gui.fpsm.team_manage.player_not_found")));
                return;
            }

            if (map.getMapTeams().getTeamByPlayer(target)
                    .filter(team -> team.getName().equals(targetTeam))
                    .isPresent()) {
                FPSMatch.sendToPlayer(sender, new TeamManageResultS2CPacket(true,
                        Component.translatable("gui.fpsm.team_manage.move_success",
                                target.getDisplayName().getString(), targetTeam)));
                return;
            }

            // 先让玩家离开当前队伍，再加入目标队伍
            MapTeams.JoinTeamResult result = map.join(targetTeam, target);
            if (result.status() == MapTeams.JoinTeamResult.Status.JOINED) {
                // MapTeams.join already sends the joining player's full snapshot and
                // the changed team/player packets to the other clients. A forced
                // broadcast here repeats every team definition and every player
                // stat for every online player, turning a single move into an
                // O(teams * players * online) packet burst.
                FPSMatch.sendToPlayer(sender, new TeamManageResultS2CPacket(true, Component.translatable("gui.fpsm.team_manage.move_success", target.getDisplayName().getString(), targetTeam)));
            } else {
                FPSMatch.sendToPlayer(sender, new TeamManageResultS2CPacket(false, Component.translatable("gui.fpsm.team_manage.move_failed", result.status().name())));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
