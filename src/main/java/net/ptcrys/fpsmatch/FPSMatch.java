package net.ptcrys.fpsmatch;

import net.ptcrys.fpsmatch.bukkit.FPSMBukkit;
import net.ptcrys.fpsmatch.common.capability.FPSMCapabilityRegister;
import net.ptcrys.fpsmatch.common.client.net.FPSMClientNetwork;
import net.ptcrys.fpsmatch.common.client.net.FPSMClientPacketRegistrar;
import net.ptcrys.fpsmatch.common.client.screen.VanillaGuiRegister;
import net.ptcrys.fpsmatch.common.command.FPSMCommand;
import net.ptcrys.fpsmatch.common.drop.ThrowableRegistry;
import net.ptcrys.fpsmatch.common.effect.FPSMEffectRegister;
import net.ptcrys.fpsmatch.common.entity.EntityRegister;
import net.ptcrys.fpsmatch.common.gamerule.FPSMatchRule;
import net.ptcrys.fpsmatch.common.item.FPSMItemRegister;
import net.ptcrys.fpsmatch.common.packet.*;
import net.ptcrys.fpsmatch.common.packet.attribute.BulletproofArmorAttributeS2CPacket;
import net.ptcrys.fpsmatch.common.packet.effect.FlashBombAddonS2CPacket;
import net.ptcrys.fpsmatch.common.packet.entity.ThrowEntityC2SPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.*;
import net.ptcrys.fpsmatch.common.packet.register.NetworkPacketRegister;
import net.ptcrys.fpsmatch.common.packet.shop.*;
import net.ptcrys.fpsmatch.common.packet.spec.SpectateModeS2CPacket;
import net.ptcrys.fpsmatch.common.packet.spec.SpectatorSwitchC2SPacket;
import net.ptcrys.fpsmatch.common.packet.spec.SpectatorTargetS2CPacket;
import net.ptcrys.fpsmatch.common.packet.team.*;
import net.ptcrys.fpsmatch.common.sound.FPSMSoundRegister;
import net.ptcrys.fpsmatch.compat.CounterStrikeGrenadesCompat;
import net.ptcrys.fpsmatch.compat.cloth.FPSMenuIntegration;
import net.ptcrys.fpsmatch.compat.impl.FPSMImpl;
import net.ptcrys.fpsmatch.compat.tacz.TACZBootstrap;
import net.ptcrys.fpsmatch.config.FPSMConfig;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * <FPSMatch>
 * Copyright (C) <2025> <SSOrangeCATY>
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
@Mod(FPSMatch.MODID)
public class FPSMatch {

    public static final String MODID = "fpsmatch";
    public static final Logger LOGGER = LoggerFactory.getLogger("FPSMatch");
    private static final String PROTOCOL_VERSION = "1.4.2";
    private static final NetworkPacketRegister PACKET_REGISTER = new NetworkPacketRegister(ResourceLocation.tryBuild("fpsmatch", "main"), PROTOCOL_VERSION);
    public static final SimpleChannel INSTANCE = PACKET_REGISTER.getChannel();
    public static final String DEBUG_SYS_PROP = "fpsm.debug";
    private static volatile boolean DEBUG_ENABLED = Boolean.parseBoolean(System.getProperty(DEBUG_SYS_PROP, "false"));

    @SuppressWarnings("removal")
    public FPSMatch() {
        this(FMLJavaModLoadingContext.get());
    }

    public FPSMatch(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegisterPackets);
        modEventBus.addListener(this::onEnqueue);
        MinecraftForge.EVENT_BUS.register(this);
        VanillaGuiRegister.CONTAINERS.register(modEventBus);
        FPSMItemRegister.ITEMS.register(modEventBus);
        FPSMItemRegister.TABS.register(modEventBus);
        FPSMSoundRegister.SOUNDS.register(modEventBus);
        EntityRegister.ENTITY_TYPES.register(modEventBus);
        FPSMEffectRegister.MOB_EFFECTS.register(modEventBus);
        FPSMatchRule.init();
        FPSMCapabilityRegister.register();
        context.registerConfig(ModConfig.Type.CLIENT, FPSMConfig.clientSpec);
        context.registerConfig(ModConfig.Type.COMMON, FPSMConfig.commonSpec);
        context.registerConfig(ModConfig.Type.SERVER, FPSMConfig.initServer());
        if (FPSMBukkit.isBukkitEnvironment()) {
            FPSMBukkit.register();
        }
    }

    @SubscribeEvent
    public void onEnqueue(final InterModEnqueueEvent event) {
        event.enqueueWork(() -> {
            if (FPSMImpl.findClothConfig()) {
                DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> FPSMenuIntegration::registerModsPage);
            } else {
                if (FMLEnvironment.dist == Dist.CLIENT) {
                    try {
                        // 尝试通过 TACZ 兼容层注册无 Cloth Config 页面
                        Class<?> clothScreenClass = Class.forName("com.tacz.guns.client.gui.compat.ClothConfigScreen");
                        clothScreenClass.getMethod("registerNoClothConfigPage").invoke(null);
                    } catch (Exception ignored) {
                        // TACZ 未加载，无需注册
                    }
                }
            }
        });
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ThrowableRegistry.registerItemToSubType(FPSMItemRegister.FLASH_BOMB.get(), ThrowableRegistry.FLASH_BANG);
            ThrowableRegistry.registerItemToSubType(FPSMItemRegister.GRENADE.get(), ThrowableRegistry.GRENADE);
            ThrowableRegistry.registerItemToSubType(FPSMItemRegister.SMOKE_SHELL.get(), ThrowableRegistry.SMOKE);
            ThrowableRegistry.registerItemToSubType(FPSMItemRegister.CT_INCENDIARY_GRENADE.get(), ThrowableRegistry.MOLOTOV);
            ThrowableRegistry.registerItemToSubType(FPSMItemRegister.T_INCENDIARY_GRENADE.get(), ThrowableRegistry.MOLOTOV);

            // 兼容层注册（各模组兼容层在此统一注册）
            registerCompat();
        });
    }

    /**
     * 统一注册所有模组兼容层。
     * 由 {@code commonSetup} 在 enqueueWork 中调用，确保在主线程执行。
     */
    private static void registerCompat() {
        if (FPSMImpl.findTacz()) {
            TACZBootstrap.registerCompat();
        }
        // CS Grenade 兼容层
        if (FPSMImpl.findCounterStrikeGrenadesMod()) {
            CounterStrikeGrenadesCompat.init();
        }
    }

    /**
     * 注册 FPSMatch 的网络包。
     * <p>
     * 注意：这里注册的 S2C packet 类必须保持“公共可加载”，不要在 packet 类中直接引用
     * Minecraft、Screen、FPSMClient、客户端渲染/音频类或其它 client-only 类型。
     * dedicated server 会在注册阶段反射扫描 packet 方法；一旦 packet 类自身带有客户端依赖，
     * 就可能在启动期触发 DistCleaner 崩溃。
     * <p>
     * 所有客户端执行行为都应通过 ClientPacketExecutor 分发；新增客户端包行为时，
     * 请在 {@link FPSMClientPacketRegistrar#registerAll()} 中
     * 添加 packet 到客户端处理器的注册映射，而不是把客户端逻辑直接写回 packet 类。
     */
    private void onRegisterPackets(final FMLCommonSetupEvent event) {
        PACKET_REGISTER.registerPacket(ShopDataSlotS2CPacket.class);
        PACKET_REGISTER.registerPacket(ShopActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(ShopMoneyS2CPacket.class);
        PACKET_REGISTER.registerPacket(FPSMatchStatsResetS2CPacket.class);
        PACKET_REGISTER.registerPacket(ThrowEntityC2SPacket.class);
        PACKET_REGISTER.registerPacket(FlashBombAddonS2CPacket.class);
        PACKET_REGISTER.registerPacket(FPSMatchGameTypeS2CPacket.class);
        PACKET_REGISTER.registerPacket(FPSMSoundPlayS2CPacket.class);
        PACKET_REGISTER.registerPacket(FPSMusicPlayS2CPacket.class);
        PACKET_REGISTER.registerPacket(FPSMSoundPlayC2SPacket.class);
        PACKET_REGISTER.registerPacket(FPSMusicStopS2CPacket.class);
        PACKET_REGISTER.registerPacket(SaveSlotDataC2SPacket.class);
        PACKET_REGISTER.registerPacket(EditToolClickC2SPacket.class);
        PACKET_REGISTER.registerPacket(PullGameInfoC2SPacket.class);
        PACKET_REGISTER.registerPacket(FPSMatchRespawnS2CPacket.class);
        PACKET_REGISTER.registerPacket(TeamPlayerStatsS2CPacket.class);
        PACKET_REGISTER.registerPacket(TeamPlayerLeaveS2CPacket.class);
        PACKET_REGISTER.registerPacket(OpenShopEditorC2SPacket.class);
        PACKET_REGISTER.registerPacket(RequestMapImportSourcesC2SPacket.class);
        PACKET_REGISTER.registerPacket(MapImportSourcesS2CPacket.class);
        PACKET_REGISTER.registerPacket(ImportMapConfigC2SPacket.class);
        PACKET_REGISTER.registerPacket(BulletproofArmorAttributeS2CPacket.class);
        PACKET_REGISTER.registerPacket(FPSMAddTeamS2CPacket.class);
        PACKET_REGISTER.registerPacket(TeamCapabilitiesS2CPacket.class);
        PACKET_REGISTER.registerPacket(SpectateModeS2CPacket.class);
        PACKET_REGISTER.registerPacket(SpectatorTargetS2CPacket.class);
        PACKET_REGISTER.registerPacket(SpectatorSwitchC2SPacket.class);
        PACKET_REGISTER.registerPacket(FPSMInventorySelectedS2CPacket.class);
        PACKET_REGISTER.registerPacket(TeamChatMessageC2SPacket.class);
        PACKET_REGISTER.registerPacket(AddAreaDataS2CPacket.class);
        PACKET_REGISTER.registerPacket(AddPointDataS2CPacket.class);
        PACKET_REGISTER.registerPacket(RemoveDebugDataByPrefixS2CPacket.class);
        PACKET_REGISTER.registerPacket(ToolInteractionC2SPacket.class);
        PACKET_REGISTER.registerPacket(OpenMapCreatorToolScreenS2CPacket.class);
        PACKET_REGISTER.registerPacket(MapCreatorToolActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(OpenMatchConfigToolScreenS2CPacket.class);
        PACKET_REGISTER.registerPacket(MatchConfigToolActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(OpenShopConfigToolScreenS2CPacket.class);
        PACKET_REGISTER.registerPacket(ShopConfigToolActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(OpenSpawnPointToolScreenS2CPacket.class);
        PACKET_REGISTER.registerPacket(SpawnPointToolActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(OpenMapSelectionC2SPacket.class);
        PACKET_REGISTER.registerPacket(CloseMapViewC2SPacket.class);
        PACKET_REGISTER.registerPacket(MapSelectionAccessS2CPacket.class);
        PACKET_REGISTER.registerPacket(MapSelectionSnapshotS2CPacket.class);
        PACKET_REGISTER.registerPacket(MapRoomActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(MapRoomDetailS2CPacket.class);
        PACKET_REGISTER.registerPacket(MapRoomReadyStateS2CPacket.class);
        PACKET_REGISTER.registerPacket(MapRoomSettingsC2SPacket.class);
        PACKET_REGISTER.registerPacket(MapRegionActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(MapRoomToastS2CPacket.class);
        PACKET_REGISTER.registerPacket(MapRoomInvitationS2CPacket.class);
        PACKET_REGISTER.registerPacket(TeamManageActionC2SPacket.class);
        PACKET_REGISTER.registerPacket(TeamManageResultS2CPacket.class);
        // Append to preserve existing packet IDs. Every shop request needs its result callback.
        PACKET_REGISTER.registerPacket(ShopActionResultS2CPacket.class);
        PACKET_REGISTER.registerPacket(SaveShopSlotConfigurationC2SPacket.class);
        PACKET_REGISTER.registerPacket(SetShopGroupsC2SPacket.class);
        PACKET_REGISTER.registerPacket(ShopGroupsResultS2CPacket.class);
        event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> FPSMClientPacketRegistrar::registerAll));
    }

    public static <M> void sendTo(Player player, M message) {
        if (player.level().isClientSide) {
            sendToServer(message);
        } else {
            sendToPlayer((ServerPlayer) player, message);
        }
    }

    public static <M> void sendToPlayer(ServerPlayer player, M message) {
        NetworkPacketRegister.getChannelFromCache(message.getClass()).send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static <M> void sendToServer(M message) {
        if (FMLEnvironment.dist != Dist.CLIENT || !FPSMClientNetwork.canSendToServer()) {
            return;
        }
        NetworkPacketRegister.getChannelFromCache(message.getClass()).sendToServer(message);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FPSMCommand.onRegisterCommands(event);
    }

    public static synchronized boolean switchDebug() {
        return DEBUG_ENABLED = !DEBUG_ENABLED;
    }

    public static boolean isDebugEnabled() {
        return DEBUG_ENABLED;
    }

    public static void debug(String msg, Object... args) {
        if (DEBUG_ENABLED) LOGGER.info(msg, args);
    }

    public static void info(String msg, Object... args) {
        LOGGER.info(msg, args);
    }

    @OnlyIn(Dist.CLIENT)
    public static void pullGameInfo() {
        sendToServer(new PullGameInfoC2SPacket());
    }
}
