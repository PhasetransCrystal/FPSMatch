package com.ptcrys.fpsmatch.common.capability.team;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.ptcrys.fpsmatch.common.command.FPSMCommand;
import com.ptcrys.fpsmatch.common.command.FPSMCommandSuggests;
import com.ptcrys.fpsmatch.common.command.FPSMHelpManager;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.common.event.FPSMTeamEvent;
import com.ptcrys.fpsmatch.common.event.FPSMapEvent;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.shop.FPSMShop;
import com.ptcrys.fpsmatch.core.shop.INamedType;
import com.ptcrys.fpsmatch.core.shop.ShopData;
import com.ptcrys.fpsmatch.core.shop.functional.LMManager;
import com.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import com.ptcrys.fpsmatch.core.team.BaseTeam;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.util.FPSMUtil;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import com.ptcrys.fpsmatch.compat.gun.IGunProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 商店能力：为队伍提供商店系统支持
 * 使用注册的商店类型系统，无需泛型
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ShopCapability extends TeamCapability implements FPSMCapability.Savable<FPSMShop<?>>, FPSMCapability.DataSynchronizable {

    public static Optional<FPSMShop<?>> getShopByPlayer(ServerPlayer player) {
        return FPSMCore.getInstance().getMapByPlayer(player)
                .flatMap(map -> map.getMapTeams().getTeamByPlayer(player)
                        .flatMap(team -> team.getCapabilityMap().get(ShopCapability.class)
                                .flatMap(ShopCapability::getShopSafe)));
    }

    public static Optional<FPSMShop<?>> getShopByPlayer(BaseMap map, ServerPlayer player){
        return map.getMapTeams().getTeamByPlayer(player).flatMap(t -> t.getCapabilityMap().get(ShopCapability.class).map(ShopCapability::getShopSafe)).orElse(null);
    }

    public static Optional<FPSMShop<?>> getShop(ServerTeam team) {
        return team.getCapabilityMap().get(ShopCapability.class).flatMap(ShopCapability::getShopSafe);
    }

    public static Optional<ShopData<?>> getPlayerShopData(BaseMap map, UUID player) {
        return map.getMapTeams().getTeamByPlayer(player)
                .flatMap(team -> team.getCapabilityMap().get(ShopCapability.class)
                        .flatMap(ShopCapability::getShopSafe)
                        .map(shop -> shop.getPlayerShopData(player)));
    }

    public static Optional<ShopData<?>> getPlayerShopData(ServerPlayer player) {
        return getShopByPlayer(player)
                .map(shop -> shop.getPlayerShopData(player));
    }

    public static void setPlayerMoney(BaseMap map, UUID playerUUID, int money){
        map.getMapTeams().getTeamByPlayer(playerUUID)
                .flatMap(team -> team.getCapabilityMap().get(ShopCapability.class))
                .flatMap(ShopCapability::getShopSafe)
                .ifPresent(shop -> {
                    shop.getPlayerShopData(playerUUID).setMoney(money);
                    shop.syncShopMoneyData(playerUUID);
                });
    }

    public static void setPlayerMoney(BaseMap map, int money){
        map.getMapTeams().getNormalTeams().forEach(team -> {
            team.getCapabilityMap()
                    .get(ShopCapability.class)
                    .flatMap(ShopCapability::getShopSafe)
                    .ifPresent(shop -> {
                        team.getPlayerList().forEach(player -> {
                            shop.getPlayerShopData(player).setMoney(money);
                        });
                        shop.syncShopMoneyData();
                    });
        });
    }

    private FPSMShop<?> shop;
    private String shopTypeId;
    private int startMoney = 800;
    private boolean initialized = false;

    public ShopCapability(BaseTeam team) {
        super(team);
    }

    @SubscribeEvent
    public void onJoin(FPSMTeamEvent.JoinEvent event) {
        if (isInitialized() && team.equals(event.getTeam())) {
            if(event.getPlayer() instanceof ServerPlayer serverPlayer) {
                shop.syncShopData(serverPlayer);
            }
        }
    }

    @SubscribeEvent
    public void onLeave(FPSMTeamEvent.LeaveEvent event) {
        if (isInitialized() && team.equals(event.getTeam())) {
            shop.clearPlayerShopData(event.getPlayer().getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerPickupItem(FPSMapEvent.PlayerEvent.PickupItemEvent event){

        ServerPlayer player = event.getPlayer();
        ShopCapability.getShopByPlayer(player).ifPresent(shop -> {
            ShopData<?> shopData = shop.getPlayerShopData(player.getUUID());
            Pair<? extends Enum<?>, ShopSlot> pair = shopData.checkItemStackIsInData(event.getStack());
            if(pair != null){
                ShopSlot slot = pair.getSecond();
                slot.lock(event.getStack().getCount());
                shop.syncShopData(player,pair.getFirst().name(),slot);
            }
        });

        FPSMUtil.sortPlayerInventory(player);
    }


    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerDropItem(ItemTossEvent event){
        if(event.getEntity().level().isClientSide) return;
        ItemStack itemStack = event.getEntity().getItem();

        ShopCapability.getShopByPlayer((ServerPlayer) event.getPlayer()).ifPresent(shop -> {
                ShopData<?> shopData = shop.getPlayerShopData(event.getEntity().getUUID());
                Pair<? extends INamedType, ShopSlot> pair = shopData.checkItemStackIsInData(itemStack);
                if(pair != null){
                    ShopSlot slot = pair.getSecond();
                    if(pair.getFirst().dorpUnlock()){
                        slot.unlock(itemStack.getCount());
                        shop.syncShopData((ServerPlayer) event.getPlayer(),pair.getFirst().name(),slot);
                    }
                }
        });
    }

    /**
     * 初始化商店系统
     */
    public boolean initialize(String shopTypeId, int startMoney) {
        try {
            this.shopTypeId = shopTypeId;
            this.startMoney = startMoney;
            this.shop = FPSMShop.createWithTypeId(shopTypeId, team.name, startMoney);
            this.initialized = true;
            return true;
        } catch (Exception e) {
            this.initialized = false;
            return false;
        }
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized && shop != null;
    }

    public boolean isInArea(Entity entity){
        return this.initialized && getShop().isInArea(entity);
    }

    public boolean addArea(AreaData data){
        return getShopSafe().map(shop->{
            shop.addArea(data);
            return true;
        }).orElse(false);
    }

    public boolean clearAreas(){
        return getShopSafe().map(shop->{
            shop.clearAreas();
            return true;
        }).orElse(false);
    }

    public boolean displayAreas(ServerPlayer player){
        return getShopSafe().map(shop->{
            shop.displayAreas(player);
            return true;
        }).orElse(false);
    }

    /**
     * 设置起始金钱（仅在使用前设置有效）
     */
    public void setStartMoney(int startMoney) {
        this.startMoney = startMoney;
        if (isInitialized()) {
            this.shop.setStartMoney(startMoney);
        }
    }

    /**
     * 获取商店实例
     */
    public FPSMShop<?> getShop() {
        if (!isInitialized()) {
            throw new IllegalStateException("ShopCapability not initialized. Call initialize() first.");
        }
        return shop;
    }

    public void setShop(FPSMShop<?> shop) {
        if(isInitialized()) {
            this.shop.clearPlayerShopData();
        }
        this.shop = shop;
    }

    public boolean importConfigurationFrom(FPSMShop<?> source) {
        if (!isInitialized() || source == null) {
            return false;
        }
        try {
            shop.copyConfigurationFrom(source);
            startMoney = shop.getStartMoney();
            shop.resetPlayerData(team.getPlayerList());
            shop.syncShopData();
            shop.syncShopMoneyData();
            return true;
        } catch (IllegalArgumentException incompatible) {
            return false;
        }
    }

    public boolean canImportConfigurationFrom(FPSMShop<?> source) {
        return isInitialized() && source != null && shop.isConfigurationCompatible(source);
    }

    /**
     * 安全获取商店实例
     */
    public Optional<FPSMShop<?>> getShopSafe() {
        return isInitialized() ? Optional.of(shop) : Optional.empty();
    }

    /**
     * 获取商店类型ID
     */
    public String getShopTypeId() {
        return shopTypeId;
    }

    /**
     * 获取起始金钱
     */
    public int getStartMoney() {
        return startMoney;
    }

    /**
     * 重置玩家商店数据
     */
    public void resetPlayerData() {
        if (isInitialized()) {
            shop.resetPlayerData();
            shop.syncShopData();
            shop.syncShopMoneyData();
        }
    }

    /**
     * 同步商店数据
     */
    public void syncShopData() {
        if (isInitialized()) {
            shop.syncShopData();
        }
    }

    @Override
    public void sync(){
        if (isInitialized()) {
            shop.sync();
        }
    }

    @Override
    public void sync(Player player){
        if (isInitialized() && player instanceof ServerPlayer serverPlayer) {
            shop.sync(serverPlayer);
        }
    }

    /**
     * 同步金钱数据
     */
    public void syncShopMoneyData() {
        if (isInitialized()) {
            if (team instanceof ServerTeam serverTeam) {
                shop.syncShopMoneyData(serverTeam.getOnline());
            } else {
                shop.syncShopMoneyData();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Codec<FPSMShop<?>> codec() {
        if (!isInitialized()) {
            throw new IllegalStateException("ShopCapability not initialized. Cannot get codec.");
        }
        return (Codec<FPSMShop<?>>) (Codec<?>) shop.getCodec();
    }

    @Override
    public FPSMShop<?> write(FPSMShop<?> value) {
        if (isInitialized()) {
            this.shop = value;
        }
        return this.shop;
    }

    @Override
    public FPSMShop<?> read() {
        return shop;
    }

    @Override
    public void destroy() {
        if (isInitialized()) {
            shop.clearPlayerShopData();
            initialized = false;
            shop = null;
            shopTypeId = null;
        }
    }

    @Override
    public void reset() {
        if (isInitialized()) {
            resetPlayerData();
        }
    }

    // ------------------------------ 商店相关工具方法 ------------------------------
    /**
     * 添加监听器模块到商店
     */
    public void addListenerModule(String moduleName, String shopType, int slotNum) {
        LMManager manager = FPSMCore.getInstance().getListenerModuleManager();
        ListenerModule module = manager.getListenerModule(moduleName);
        getShop().addDefaultShopDataListenerModule(shopType, slotNum, module);
    }

    /**
     * 从商店移除监听器模块
     */
    public void removeListenerModule(String moduleName, String shopType, int slotNum) {
        getShop().removeDefaultShopDataListenerModule(shopType, slotNum, moduleName);
    }

    /**
     * 设置商店组ID
     */
    public void setShopGroupID(int groupId, String shopType, int slotNum) {
        getShop().setDefaultShopDataGroupId(shopType, slotNum, groupId);
    }

    /**
     * 设置商店成本
     */
    public void setShopCost(int cost, String shopType, int slotNum) {
        getShop().setDefaultShopDataCost(shopType, slotNum, cost);
    }

    /**
     * 设置商店物品
     */
    public void setShopItem(ItemStack itemStack, String shopType, int slotNum) {
        if (GunCompatManager.isGun(itemStack)) {
            FPSMUtil.fixGunItem(itemStack, GunCompatManager.findProvider(itemStack));
        }
        getShop().setDefaultShopDataItemStack(shopType, slotNum, itemStack);
    }

    /**
     * 从玩家手中获取物品并设置到商店
     */
    public void setShopItemFromPlayer(ServerPlayer player, String shopType, int slotNum) {
        ItemStack itemStack = player.getMainHandItem().copy();
        setShopItem(itemStack, shopType, slotNum);
    }

    /**
     * 设置枪支弹药数量
     */
    public void setGunAmmoAmount(int amount, String shopType, int slotNum) {
        ItemStack itemStack = getShop().getDefaultShopDataItemStack(shopType, slotNum);
        if (GunCompatManager.isGun(itemStack)) {
            FPSMUtil.setDummyAmmo(itemStack, GunCompatManager.findProvider(itemStack), amount);
        }
        getShop().setDefaultShopDataItemStack(shopType, slotNum, itemStack);
    }

    /**
     * 注册能力到全局管理器
     */
    public static void register() {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, ShopCapability.class, new Factory<>() {
            @Override
            public ShopCapability create(BaseTeam team) {
                if (team instanceof ServerTeam serverTeam) {
                    return new ShopCapability(serverTeam);
                } else {
                    throw new IllegalArgumentException("Team is client side");
                }
            }

            @Override
            public Command command() {
                return new ShopCommand();
            }
        });
    }

    /**
     * 商店命令处理器
     */
    protected static class ShopCommand implements Factory.Command {
        @Override
        public String getName() {
            return "shop";
        }

        @Override
        public LiteralArgumentBuilder<CommandSourceStack> builder(LiteralArgumentBuilder<CommandSourceStack> builder, CommandBuildContext context) {
            return builder
                    .then(Commands.literal("initialize")
                            .then(Commands.argument("type", StringArgumentType.string())
                                    .suggests(FPSMCommandSuggests.SHOP_TYPE_SUGGESTION)
                                    .executes(c -> handleInitialize(c, 800))
                                    .then(Commands.argument("startMoney", IntegerArgumentType.integer(0))
                                            .executes(c -> handleInitialize(c, IntegerArgumentType.getInteger(c, "startMoney"))))))
                    .then(Commands.literal("reset")
                            .executes(ShopCommand::handleReset))
                    .then(Commands.literal("sync")
                            .executes(ShopCommand::handleSync))
                    .then(Commands.literal("info")
                            .executes(ShopCommand::handleInfo))
                    .then(Commands.literal("areas")
                            .then(Commands.literal("add")
                                    .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                            .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                    .executes(ShopCommand::handleAddArea))
                                    ))
                            .then(Commands.literal("display").executes(ShopCommand::handleDisplayAreas))
                            .then(Commands.literal("clear").executes(ShopCommand::handleClearAreas)))
                    .then(Commands.literal("modify")
                            .then(Commands.literal("set")
                                    .then(Commands.argument(FPSMCommandSuggests.SHOP_TYPE_ARG, StringArgumentType.string())
                                            .suggests(FPSMCommandSuggests.SHOP_ITEM_TYPES_SUGGESTION)
                                            .then(Commands.argument(FPSMCommandSuggests.SHOP_SLOT_ARG, IntegerArgumentType.integer(1, 5))
                                                    .suggests(FPSMCommandSuggests.SHOP_SET_SLOT_ACTION_SUGGESTION)
                                                    .then(Commands.literal("listener_module")
                                                            .then(Commands.literal("add")
                                                                    .then(Commands.argument("listener_module", StringArgumentType.string())
                                                                            .suggests(FPSMCommandSuggests.SHOP_SLOT_ADD_LISTENER_MODULES_SUGGESTION)
                                                                            .executes(ShopCommand::handleAddListenerModule)))
                                                            .then(Commands.literal("remove")
                                                                    .then(Commands.argument("listener_module", StringArgumentType.string())
                                                                            .suggests(FPSMCommandSuggests.SHOP_SLOT_REMOVE_LISTENER_MODULES_SUGGESTION)
                                                                            .executes(ShopCommand::handleRemoveListenerModule))))
                                                    .then(Commands.literal("group_id")
                                                            .then(Commands.argument("group_id", IntegerArgumentType.integer(0))
                                                                    .executes(ShopCommand::handleModifyShopGroupID)))
                                                    .then(Commands.literal("cost")
                                                            .then(Commands.argument("cost", IntegerArgumentType.integer(0))
                                                                    .executes(ShopCommand::handleModifyCost)))
                                                    .then(Commands.literal("item")
                                                            .executes(ShopCommand::handleModifyItemWithoutValue)
                                                            .then(Commands.argument("item", ItemArgument.item(context))
                                                                    .executes(ShopCommand::handleModifyItem)))
                                                    .then(Commands.literal("dummy_ammo_amount")
                                                            .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                                                    .executes(ShopCommand::handleGunModifyGunAmmoAmount)))))));
        }

        private static int handleDisplayAreas(CommandContext<CommandSourceStack> context) {
            ServerPlayer player = context.getSource().getPlayer();
            if(player == null){
                context.getSource().sendSuccess(() ->
                        Component.translatable("commands.fpsm.only.player"), true);
                return 0;
            }

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(cap -> {
                if (cap.displayAreas(player)) {
                    context.getSource().sendSuccess(() ->
                            Component.translatable("commands.fpsm.modify.shop.display.success"), true);
                    return 1;
                } else {
                    context.getSource().sendFailure(
                            Component.translatable("commands.fpsm.modify.shop.display.failed"));
                    return 0;
                }
            }).orElseGet(() -> {
                context.getSource().sendFailure(
                        Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        /**
         * 清除所有商店区域
         */
        private static int handleClearAreas(CommandContext<CommandSourceStack> context) {
            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(cap -> {
                if (cap.clearAreas()) {
                    context.getSource().sendSuccess(() ->
                            Component.translatable("commands.fpsm.modify.shop.clear_areas.success"), true);
                    return 1;
                } else {
                    context.getSource().sendFailure(
                            Component.translatable("commands.fpsm.modify.shop.clear_areas.failed"));
                    return 0;
                }
            }).orElseGet(() -> {
                context.getSource().sendFailure(
                        Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        /**
         * 添加商店区域
         */
        private static int handleAddArea(CommandContext<CommandSourceStack> context) {
            BlockPos pos1 = BlockPosArgument.getBlockPos(context, "pos1");
            BlockPos pos2 = BlockPosArgument.getBlockPos(context, "pos2");

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(cap -> {
                if (cap.addArea(new AreaData(pos1, pos2))) {
                    context.getSource().sendSuccess(() ->
                            Component.translatable("commands.fpsm.modify.shop.add_area.success",
                                    pos1.toShortString(), pos2.toShortString()), true);
                    return 1;
                } else {
                    context.getSource().sendFailure(
                            Component.translatable("commands.fpsm.modify.shop.add_area.failed"));
                    return 0;
                }
            }).orElseGet(() -> {
                context.getSource().sendFailure(
                        Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }
        @Override
        public void help(FPSMHelpManager helper) {
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop initialize"), Component.translatable("commands.fpsm.help.capability.shop.initialize"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop reset"), Component.translatable("commands.fpsm.help.capability.shop.reset"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop sync"), Component.translatable("commands.fpsm.help.capability.shop.sync"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop info"), Component.translatable("commands.fpsm.help.capability.shop.info"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify set"), Component.translatable("commands.fpsm.help.capability.shop.modify"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify set listener_module add"), Component.translatable("commands.fpsm.help.capability.shop.modify.set.listener_module.add"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify set listener_module remove"), Component.translatable("commands.fpsm.help.capability.shop.modify.set.listener_module.remove"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify set group_id"), Component.translatable("commands.fpsm.help.capability.shop.modify.set.group_id"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify set cost"), Component.translatable("commands.fpsm.help.capability.shop.modify.set.cost"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify set item"), Component.translatable("commands.fpsm.help.capability.shop.modify.set.item"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop modify set dummy_ammo_amount"), Component.translatable("commands.fpsm.help.capability.shop.modify.set.dummy_ammo_amount"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop areas add"), Component.translatable("commands.fpsm.help.capability.shop.areas.add"),Component.translatable("commands.fpsm.help.capability.shop.areas.add.hover"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop areas display"), Component.translatable("commands.fpsm.help.capability.shop.areas.display"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("shop areas clear"), Component.translatable("commands.fpsm.help.capability.shop.areas.clear"));

            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop initialize"), "*type", "startMoney");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop modify set"), "*type", "*slot", "*action");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop modify set listener_module add"), "*listener_module");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop modify set listener_module remove"), "*listener_module");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop modify set group_id"), "*group_id");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop modify set cost"), "*cost");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop modify set item"), "item");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop modify set dummy_ammo_amount"), "*amount");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop areas add"), "*pos1", "*pos2");
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop areas display"));
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("shop areas clear"));
        }

        /**
         * 处理商店初始化
         */
        private static int handleInitialize(CommandContext<CommandSourceStack> context, int startMoney) {
            String typeId = StringArgumentType.getString(context, "type");

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!FPSMShop.isShopTypeRegistered(typeId)) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.initialize.type_not_found", typeId));
                    return 0;
                }

                if (capability.initialize(typeId, startMoney)) {
                    context.getSource().sendSuccess(() -> Component.translatable("commands.fpsm.modify.shop.initialize.success",
                            capability.team.name, typeId, startMoney), true);
                    return 1;
                } else {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.initialize.failed", typeId));
                    return 0;
                }
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        /**
         * 处理重置玩家数据
         */
        private static int handleReset(CommandContext<CommandSourceStack> context) {
            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.resetPlayerData();
                context.getSource().sendSuccess(() -> Component.translatable("commands.fpsm.modify.shop.reset.success",
                        capability.team.name), true);
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        /**
         * 处理数据同步
         */
        private static int handleSync(CommandContext<CommandSourceStack> context) {
            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.syncShopData();
                capability.syncShopMoneyData();
                context.getSource().sendSuccess(() -> Component.translatable("commands.fpsm.modify.shop.sync.success",
                        capability.team.name), true);
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        /**
         * 处理信息查询
         */
        private static int handleInfo(CommandContext<CommandSourceStack> context) {
            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                FPSMShop<?> shop = capability.getShop();
                context.getSource().sendSuccess(() -> Component.translatable("commands.fpsm.modify.shop.info",
                        capability.team.name,
                        capability.getShopTypeId(),
                        capability.getStartMoney(),
                        shop.playersData.size()), true);
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        // ------------------------------ 商店相关处理方法 ------------------------------
        private static int handleAddListenerModule(CommandContext<CommandSourceStack> context) {
            String moduleName = StringArgumentType.getString(context, "listener_module");
            String shopType = StringArgumentType.getString(context, FPSMCommandSuggests.SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
            int slotNum = IntegerArgumentType.getInteger(context, FPSMCommandSuggests.SHOP_SLOT_ARG) - 1;

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.addListenerModule(moduleName, shopType, slotNum);
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.listener.add.success", moduleName));
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        private static int handleRemoveListenerModule(CommandContext<CommandSourceStack> context) {
            String moduleName = StringArgumentType.getString(context, "listener_module");
            String shopType = StringArgumentType.getString(context, FPSMCommandSuggests.SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
            int slotNum = IntegerArgumentType.getInteger(context, FPSMCommandSuggests.SHOP_SLOT_ARG) - 1;

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.removeListenerModule(moduleName, shopType, slotNum);
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.shop.slot.listener.remove.success", moduleName));
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        private static int handleModifyShopGroupID(CommandContext<CommandSourceStack> context) {
            int group_id = IntegerArgumentType.getInteger(context, "group_id");
            String shopType = StringArgumentType.getString(context, FPSMCommandSuggests.SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
            int slotNum = IntegerArgumentType.getInteger(context, FPSMCommandSuggests.SHOP_SLOT_ARG) - 1;

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.setShopGroupID(group_id, shopType, slotNum);
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.shop.slot.modify.group.success", shopType, slotNum, group_id));
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        private static int handleModifyCost(CommandContext<CommandSourceStack> context) {
            String shopType = StringArgumentType.getString(context, FPSMCommandSuggests.SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
            int slotNum = IntegerArgumentType.getInteger(context, FPSMCommandSuggests.SHOP_SLOT_ARG) - 1;
            int cost = IntegerArgumentType.getInteger(context, "cost");

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.setShopCost(cost, shopType, slotNum);
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.shop.modify.cost.success", shopType, slotNum, cost));
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        private static int handleModifyItemWithoutValue(CommandContext<CommandSourceStack> context) {
            try {
                ServerPlayer player = FPSMCommand.getPlayerOrFail(context);
                String shopType = StringArgumentType.getString(context, FPSMCommandSuggests.SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
                int slotNum = IntegerArgumentType.getInteger(context, FPSMCommandSuggests.SHOP_SLOT_ARG) - 1;

                return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                    if (!capability.isInitialized()) {
                        context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                        return 0;
                    }

                    capability.setShopItemFromPlayer(player, shopType, slotNum);
                    FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.shop.modify.item.success",
                            shopType, slotNum, player.getMainHandItem().getDisplayName()));
                    return 1;
                }).orElseGet(() -> {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                    return 0;
                });
            } catch (CommandSyntaxException e) {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.only.player"));
                return 0;
            }
        }

        private static int handleModifyItem(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            String shopType = StringArgumentType.getString(context, FPSMCommandSuggests.SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
            int slotNum = IntegerArgumentType.getInteger(context, FPSMCommandSuggests.SHOP_SLOT_ARG) - 1;

            ItemStack itemStack = ItemArgument.getItem(context, "item").createItemStack(1, false);

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.setShopItem(itemStack, shopType, slotNum);
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.shop.modify.item.success",
                        shopType, slotNum, itemStack.getDisplayName()));
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }

        private static int handleGunModifyGunAmmoAmount(CommandContext<CommandSourceStack> context) {
            String shopType = StringArgumentType.getString(context, FPSMCommandSuggests.SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
            int slotNum = IntegerArgumentType.getInteger(context, FPSMCommandSuggests.SHOP_SLOT_ARG) - 1;
            int amount = IntegerArgumentType.getInteger(context, "amount");

            return FPSMCommand.getTeamCapability(context, ShopCapability.class).map(capability -> {
                if (!capability.isInitialized()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.shop.not_initialized"));
                    return 0;
                }

                capability.setGunAmmoAmount(amount, shopType, slotNum);
                FPSMShop<?> shop = capability.getShop();
                ItemStack itemStack = shop.getDefaultShopDataItemStack(shopType, slotNum);
                FPSMCommand.sendSuccess(context.getSource(), Component.translatable("commands.fpsm.shop.modify.gun.success",
                        shopType, slotNum, itemStack.getDisplayName(), amount));
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", ShopCapability.class.getSimpleName()));
                return 0;
            });
        }
    }

    public static class Data{
        public final FPSMShop<?> shop;
        public AreaData data;

        public Data(FPSMShop<?> shop) {
            this.shop = shop;
        }

        public Data(FPSMShop<?> shop, AreaData data) {
            this.shop = shop;
            this.data = data;
        }

        @Nullable
        public AreaData getData() {
            return data;
        }

        public FPSMShop<?> getShop() {
            return shop;
        }
    }
}
