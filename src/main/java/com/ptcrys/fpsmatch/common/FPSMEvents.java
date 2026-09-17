package com.ptcrys.fpsmatch.common;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.event.register.RegisterListenerModuleEvent;
import com.ptcrys.fpsmatch.common.item.MapCreatorTool;
import com.ptcrys.fpsmatch.common.item.SpawnPointTool;
import com.ptcrys.fpsmatch.common.packet.FPSMatchStatsResetS2CPacket;
import com.ptcrys.fpsmatch.common.shop.functional.BulletproofArmorWithHelmetListenerModule;
import com.ptcrys.fpsmatch.common.shop.functional.BulletproofArmorWithoutHelmetListenerModule;
import com.ptcrys.fpsmatch.common.shop.functional.ChangeShopItemModule;
import com.ptcrys.fpsmatch.common.shop.functional.ReturnGoodsModule;
import com.ptcrys.fpsmatch.core.FPSMCore;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FPSMEvents {

    @SubscribeEvent
    public static void onServerTickEvent(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FPSMCore.getInstance().onServerTick();
        }
    }

    @SubscribeEvent
    public static void onPlayerTickEvent(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() instanceof MapCreatorTool mapCreatorTool) {
            mapCreatorTool.syncHeldPreview(player, stack);
            SpawnPointTool.clearHeldPreview(player);
            return;
        }
        if (stack.getItem() instanceof SpawnPointTool spawnPointTool) {
            spawnPointTool.syncHeldPreview(player, stack);
            MapCreatorTool.clearHeldPreview(player);
            return;
        }

        MapCreatorTool.clearHeldPreview(player);
        SpawnPointTool.clearHeldPreview(player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new FPSMatchStatsResetS2CPacket());
        }
    }

    @SubscribeEvent
    public static void onRegisterListenerModuleEvent(RegisterListenerModuleEvent event) {
        event.register(new ReturnGoodsModule());
        ChangeShopItemModule changeShopItemModule = new ChangeShopItemModule(new ItemStack(Items.APPLE), 50, new ItemStack(Items.GOLDEN_APPLE), 300);
        event.register(changeShopItemModule);
        event.register(new BulletproofArmorWithoutHelmetListenerModule());
        event.register(new BulletproofArmorWithHelmetListenerModule());
    }
}
