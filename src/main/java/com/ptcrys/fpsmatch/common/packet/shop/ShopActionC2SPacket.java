package com.ptcrys.fpsmatch.common.packet.shop;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.shop.*;
import com.ptcrys.fpsmatch.core.team.BaseTeam;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class ShopActionC2SPacket {

    private static final Map<UUID, Object> PLAYER_LOCKS = new ConcurrentHashMap<>();
    public final long requestId;
    public final String name;
    public final INamedType type;
    public final int index;
    public final int action;

    public ShopActionC2SPacket(long requestId, String mapName, INamedType type, int index, ShopAction action) {
        this.requestId = requestId;
        this.name = mapName;
        this.type = type;
        this.index = index;
        this.action = action.ordinal();
    }

    public static void encode(ShopActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.requestId);
        buf.writeUtf(packet.name);
        buf.writeUtf(packet.type.name());
        buf.writeInt(packet.index);
        buf.writeVarInt(packet.action);
    }

    public static ShopActionC2SPacket decode(FriendlyByteBuf buf) {
        return new ShopActionC2SPacket(
                buf.readLong(),
                buf.readUtf(),
                new UnknownShopType(buf.readUtf()),
                buf.readInt(),
                buf.readVarInt());
    }

    private ShopActionC2SPacket(long requestId, String mapName, INamedType type, int index, int action) {
        this.requestId = requestId;
        this.name = mapName;
        this.type = type;
        this.index = index;
        this.action = action;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null) {
                return;
            }
            Object playerLock = PLAYER_LOCKS.computeIfAbsent(
                    serverPlayer.getUUID(), ignored -> new Object());
            synchronized (playerLock) {
                handleAuthorized(serverPlayer);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void handleAuthorized(ServerPlayer serverPlayer) {
        ShopAction decodedAction = action >= 0 && action < ShopAction.values().length ? ShopAction.values()[action] : null;
        ShopActionResult result;
        try {
            ShopActionResultS2CPacket replay = ShopActionReplayLedger.find(
                    serverPlayer.getUUID(), requestId);
            if (replay != null) {
                FPSMatch.sendToPlayer(serverPlayer, replay);
                return;
            }
            result = ShopActionResult.failure(ShopActionResult.Code.INVALID_REQUEST);
            if (requestId >= 0 && index >= 0 && type != null && type.name() != null && !type.name().isBlank() && decodedAction != null) {
                BaseMap map = FPSMCore.getInstance().getMapByPlayer(serverPlayer).orElse(null);
                BaseTeam team = map == null ? null : map.getMapTeams().getTeamByPlayer(serverPlayer).orElse(null);
                ShopCapability cap = team == null ? null : team.getCapabilityMap().get(ShopCapability.class).orElse(null);
                if (map == null || cap == null || cap.getShop() == null) {
                    result = ShopActionResult.failure(ShopActionResult.Code.SHOP_UNAVAILABLE);
                } else if (!map.canUseShop(cap, serverPlayer)) {
                    result = ShopActionResult.failure(ShopActionResult.Code.NOT_ALLOWED);
                } else {
                    result = cap.getShop().handleButton(
                            serverPlayer, type, index, decodedAction);
                }
            }
        } catch (RuntimeException failure) {
            FPSMatch.LOGGER.error("Shop action request {} failed", requestId, failure);
            result = ShopActionResult.failure(ShopActionResult.Code.INVALID_REQUEST);
        }
        sendResult(serverPlayer, decodedAction, result);
    }

    private void sendResult(
                            ServerPlayer serverPlayer,
                            ShopAction decodedAction,
                            ShopActionResult result) {
        ShopActionResultS2CPacket response = new ShopActionResultS2CPacket(
                requestId,
                type == null || type.name() == null ? "" : type.name(),
                index,
                decodedAction == null ? ShopAction.BUY : decodedAction,
                result);
        FPSMatch.sendToPlayer(serverPlayer, ShopActionReplayLedger.record(
                serverPlayer.getUUID(), response));
    }
}
