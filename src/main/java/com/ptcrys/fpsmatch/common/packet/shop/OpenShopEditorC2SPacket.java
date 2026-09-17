package com.ptcrys.fpsmatch.common.packet.shop;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import com.google.gson.Gson;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.common.client.screen.EditorShopContainer;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.shop.FPSMShop;
import com.ptcrys.fpsmatch.core.shop.INamedType;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.util.FPSMCodec;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public record OpenShopEditorC2SPacket(String gameType, String mapName, String teamName) {

    private static final int ID_MAX_LENGTH = 128;

    public static void encode(OpenShopEditorC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.gameType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.mapName(), ID_MAX_LENGTH);
        buf.writeUtf(packet.teamName(), ID_MAX_LENGTH);
    }

    public static OpenShopEditorC2SPacket decode(FriendlyByteBuf buf) {
        return new OpenShopEditorC2SPacket(buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (!isValidId(gameType) || !isValidId(mapName) || !isValidId(teamName)) {
                reject(player, "gui.fpsm.shop_editor.open.invalid_id");
                return;
            }
            if (!MapRoomQueryService.isMapOperator(player)) {
                reject(player, "gui.fpsm.shop_editor.open.no_permission");
                return;
            }
            Optional<BaseMap> map = MapRoomQueryService.findMap(gameType, mapName);
            Optional<ServerTeam> team = map.flatMap(this::findTeam);
            Optional<FPSMShop<?>> shopOpt = team.flatMap(ShopCapability::getShop);
            if (shopOpt.isEmpty()) {
                reject(player, "gui.fpsm.shop_editor.open.shop_unavailable");
                return;
            }
            FPSMShop<?> shop = shopOpt.get();
            List<?> enums = shop.getEnums();

            NetworkHooks.openScreen(player,
                    new SimpleMenuProvider(
                            (windowId, inv, p) -> new EditorShopContainer(windowId, inv, shop, gameType, mapName, teamName),
                            Component.translatable("gui.fpsm.shop_editor.title")),
                    buf -> {
                        buf.writeUtf(gameType);
                        buf.writeUtf(mapName);
                        buf.writeUtf(teamName);

                        // 序列化商店数据到客户端
                        buf.writeInt(enums.size());
                        for (Object type : enums) {
                            if (!(type instanceof INamedType named)) continue;
                            List<ShopSlot> slots = shop.getDefaultShopSlotListByType(named.name());
                            buf.writeUtf(named.name());
                            buf.writeInt(slots.size());
                        }
                        for (Object type : enums) {
                            if (!(type instanceof INamedType named)) continue;
                            List<ShopSlot> slots = shop.getDefaultShopSlotListByType(named.name());
                            for (ShopSlot slot : slots) {
                                buf.writeItem(slot.process());
                                try {
                                    String json = new Gson().toJson(FPSMCodec.encodeToJson(ShopSlot.CODEC, slot));
                                    buf.writeUtf(json);
                                } catch (Exception e) {
                                    buf.writeUtf("");
                                }
                            }
                        }
                    });
        });
        ctx.get().setPacketHandled(true);
    }

    private static boolean isValidId(String value) {
        return value != null && !value.isBlank() && value.length() <= ID_MAX_LENGTH;
    }

    private static void reject(ServerPlayer player, String translationKey) {
        FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(
                Component.translatable(translationKey), true));
    }

    private Optional<ServerTeam> findTeam(BaseMap map) {
        return map.getMapTeams().getNormalTeams().stream()
                .filter(team -> team.getName().equals(teamName))
                .findFirst();
    }
}
