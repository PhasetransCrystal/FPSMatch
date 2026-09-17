package com.ptcrys.fpsmatch.core.shop;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.PacketDistributor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.event.FPSMShopEvent;
import com.ptcrys.fpsmatch.common.packet.AddAreaDataS2CPacket;
import com.ptcrys.fpsmatch.common.packet.RemoveDebugDataByPrefixS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.ShopDataSlotS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.ShopMoneyS2CPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.util.PreviewColorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * FPSMatch 商店系统的核心类，用于管理玩家的商店数据和默认商店配置。
 * <p>
 * 该类提供了玩家商店数据的同步、默认商店配置的管理以及商店操作的处理。
 * 支持通过网络包同步商店数据和金钱信息。
 */
public class FPSMShop<T extends Enum<T> & INamedType> {

    private static final Map<String, Class<? extends INamedType>> REGISTERED_SHOP_TYPES = new HashMap<>();

    /**
     * 注册商店类型
     * 
     * @param typeId    类型标识符
     * @param typeClass 类型类
     */
    public static void registerShopType(String typeId, Class<? extends INamedType> typeClass) {
        if (REGISTERED_SHOP_TYPES.containsKey(typeId)) {
            FPSMatch.LOGGER.warn("Shop type {} already registered, overriding with {}", typeId, typeClass.getSimpleName());
        }
        REGISTERED_SHOP_TYPES.put(typeId, typeClass);
        FPSMatch.LOGGER.info("Registered shop type: {} -> {}", typeId, typeClass.getSimpleName());
    }

    /**
     * 获取所有已注册的商店类型
     */
    public static Set<String> getRegisteredShopTypes() {
        return new HashSet<>(REGISTERED_SHOP_TYPES.keySet());
    }

    /**
     * 根据类型ID获取商店类型类
     */
    @SuppressWarnings("unchecked")
    public static <T extends Enum<T> & INamedType> Class<T> getShopTypeClass(String typeId) {
        Class<? extends INamedType> clazz = REGISTERED_SHOP_TYPES.get(typeId);
        if (clazz != null && clazz.isEnum()) {
            return (Class<T>) clazz;
        }
        return null;
    }

    /**
     * 检查类型是否已注册
     */
    public static boolean isShopTypeRegistered(String typeId) {
        return REGISTERED_SHOP_TYPES.containsKey(typeId);
    }

    /**
     * 创建商店（使用注册的类型ID）
     */
    public static <T extends Enum<T> & INamedType> FPSMShop<T> createWithTypeId(String typeId, String name, int startMoney) {
        Class<T> typeClass = getShopTypeClass(typeId);
        if (typeClass == null) {
            throw new IllegalArgumentException("Shop type not registered: " + typeId);
        }
        return create(typeClass, name, startMoney);
    }

    public static <T extends Enum<T> & INamedType> FPSMShop<T> create(Class<T> enumClass, String name) {
        return create(enumClass, name, 800);
    }

    public static <T extends Enum<T> & INamedType> FPSMShop<T> create(Class<T> enumClass, String name, int startMoney) {
        Map<T, ArrayList<ShopSlot>> shopSlots = new HashMap<>();
        for (T type : enumClass.getEnumConstants()) {
            shopSlots.put(type, type.defaultSlots());
        }
        return new FPSMShop<>(enumClass, name, shopSlots, startMoney);
    }

    public final Class<T> enumClass;
    /**
     * 商店的名称，通常与队伍名称相关联。
     */
    public final String name;

    /**
     * 默认商店数据，存储了商店中所有类型及其对应的商店槽位列表。
     */
    private final Map<T, ArrayList<ShopSlot>> defaultShopData;

    /**
     * 玩家初始金钱。
     */
    private int startMoney;

    /**
     * 存储所有玩家的商店数据，键为玩家 UUID，值为对应的 ShopData。
     */
    public final Map<UUID, ShopData<T>> playersData = new HashMap<>();

    /**
     * FPSMShop 的编解码器，用于序列化和反序列化商店配置。
     */
    public final Codec<FPSMShop<T>> codec;

    public final int typeCount;

    public final List<AreaData> areas;

    /**
     * 获取默认金钱。
     *
     * @return 默认金钱数量
     */
    private int getDefaultMoney() {
        return startMoney;
    }

    /**
     * 获取商店名称。
     *
     * @return 商店名称
     */
    public String getName() {
        return name;
    }

    /**
     * 构造函数，用于创建一个新的 FPSMShop 实例（自定义默认商店数据和初始金钱）。
     *
     * @param name       商店名称
     * @param data       默认商店数据
     * @param startMoney 玩家初始金钱
     */
    public FPSMShop(Class<T> enumClass, String name, Map<T, ArrayList<ShopSlot>> data, int startMoney) {
        this.enumClass = enumClass;
        this.typeCount = getEnums().size();
        this.defaultShopData = data;
        this.startMoney = startMoney;
        this.name = name;
        this.codec = withCodec(enumClass);
        this.areas = new ArrayList<>();
    }

    /**
     * 构造函数，用于创建一个新的 FPSMShop 实例（自定义默认商店数据和初始金钱）。
     *
     * @param name       商店名称
     * @param data       默认商店数据
     * @param startMoney 玩家初始金钱
     */
    public FPSMShop(Class<T> enumClass, String name, Map<T, ArrayList<ShopSlot>> data, int startMoney, List<AreaData> areas) {
        this.enumClass = enumClass;
        this.typeCount = getEnums().size();
        this.defaultShopData = data;
        this.startMoney = startMoney;
        this.name = name;
        this.codec = withCodec(enumClass);
        this.areas = areas;
    }

    public List<T> getEnums() {
        return List.of(enumClass.getEnumConstants());
    }

    /**
     * 同步所有玩家的商店数据到客户端。
     * <p>
     * 遍历所有玩家的商店数据，并通过网络包发送给对应的玩家。
     */
    public void syncShopData() {
        for (UUID uuid : playersData.keySet()) {
            FPSMCore.getInstance().getPlayerByUUID(uuid).ifPresent(player -> {
                List<T> enumConstants = getEnums();
                ShopData<T> shopData = this.getPlayerShopData(uuid);
                for (T type : enumConstants) {
                    List<ShopSlot> slots = shopData.getShopSlotsByType(type);
                    slots.forEach((shopSlot -> FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new ShopDataSlotS2CPacket(type, shopSlot))));
                }
            });
        }
    }

    /**
     * 同步所有玩家的金钱数据到客户端。
     * <p>
     * 遍历所有玩家的商店数据，并通过网络包发送金钱信息。
     */
    public void syncShopMoneyData() {
        for (ServerPlayer recipient : FPSMCore.getInstance().getServer().getPlayerList().getPlayers()) {
            Optional<ServerTeam> recipientTeam = FPSMCore.getInstance().getMapByPlayer(recipient)
                    .flatMap(map -> map.getMapTeams().getTeamByPlayer(recipient));
            if (recipientTeam.isEmpty()) continue;
            for (UUID uuid : playersData.keySet()) {
                if (FPSMCore.getInstance().getMapByPlayer(uuid)
                        .flatMap(map -> map.getMapTeams().getTeamByPlayer(uuid))
                        .filter(recipientTeam.get()::equals).isEmpty())
                    continue;
                ShopData<T> shopData = this.getPlayerShopData(uuid);
                FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> recipient), new ShopMoneyS2CPacket(uuid, shopData.getMoney()));
            }
        }
    }

    /** Sends this team's economy to viewers that are already in this team. */
    public void syncShopMoneyData(Collection<ServerPlayer> viewers) {
        for (ServerPlayer viewer : viewers) {
            for (UUID uuid : playersData.keySet()) {
                ShopData<T> shopData = this.getPlayerShopData(uuid);
                FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> viewer),
                        new ShopMoneyS2CPacket(uuid, shopData.getMoney()));
            }
        }
    }

    /**
     * 同步指定玩家的金钱数据到客户端。
     *
     * @param uuid 玩家的 UUID
     */
    public void syncShopMoneyData(UUID uuid) {
        if (playersData.containsKey(uuid)) {
            FPSMCore.getInstance().getPlayerByUUID(uuid).ifPresent(player -> {
                ShopData<T> shopData = this.getPlayerShopData(uuid);
                FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new ShopMoneyS2CPacket(uuid, shopData.getMoney()));
            });
        }
    }

    /**
     * 同步指定玩家的金钱数据到客户端。
     *
     * @param player 玩家对象
     */
    public void syncShopMoneyData(@NotNull ServerPlayer player) {
        this.syncShopMoneyData(player.getUUID());
    }

    /**
     * 同步指定玩家列表的商店数据到客户端。
     *
     * @param players 玩家列表
     */
    public void syncShopData(List<ServerPlayer> players) {
        players.forEach(this::syncShopData);
    }

    /**
     * 同步指定玩家的商店数据到客户端。
     *
     * @param player 玩家对象
     */
    public void syncShopData(ServerPlayer player) {
        ShopData<T> shopData = this.getPlayerShopData(player.getUUID());
        List<T> enumConstants = getEnums();
        for (T type : enumConstants) {
            List<ShopSlot> slots = shopData.getShopSlotsByType(type);
            slots.forEach((shopSlot -> FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new ShopDataSlotS2CPacket(type, shopSlot))));
        }
    }

    /**
     * 同步指定玩家的商店槽位数据到客户端。
     *
     * @param player 玩家对象
     * @param type   类型
     * @param slot   商店槽位
     */
    public void syncShopData(ServerPlayer player, String type, ShopSlot slot) {
        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new ShopDataSlotS2CPacket(valueOf(type), slot));
    }

    /**
     * 同步指定玩家的商店槽位数据到客户端。
     *
     * @param player 玩家对象
     * @param type   类型
     * @param index  槽位索引
     */
    public void syncShopData(ServerPlayer player, T type, int index) {
        ShopSlot shopSlot = this.getPlayerShopData(player.getUUID()).getShopSlotsByType(type).get(index);
        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new ShopDataSlotS2CPacket(type, shopSlot));
    }

    public void sync() {
        this.syncShopData();
        this.syncShopMoneyData();
    }

    public void sync(ServerPlayer player) {
        this.syncShopData(player);
        this.syncShopMoneyData(player);
    }

    /**
     * 获取玩家的商店数据。
     * <p>
     * 如果玩家的商店数据不存在，则会创建一个新的默认商店数据。
     *
     * @param uuid 玩家的 UUID
     * @return 玩家的商店数据
     */
    public ShopData<T> getPlayerShopData(UUID uuid) {
        if (this.playersData.containsKey(uuid)) {
            return this.playersData.get(uuid);
        } else {
            return this.getDefaultAndPutData(uuid, true);
        }
    }

    /**
     * 获取玩家的商店数据。
     * <p>
     * 如果玩家的商店数据不存在，则会创建一个新的默认商店数据。
     *
     * @param player 玩家
     * @return 玩家的商店数据
     */
    public ShopData<T> getPlayerShopData(ServerPlayer player) {
        return this.getPlayerShopData(player.getUUID());
    }

    public Optional<ShopData<T>> getPlayerShopDataSafe(ServerPlayer player) {
        return getPlayerShopDataSafe(player.getUUID());
    }

    public Optional<ShopData<T>> getPlayerShopDataSafe(UUID player) {
        if (this.playersData.containsKey(player)) {
            return Optional.of(this.playersData.get(player));
        }
        return Optional.empty();
    }

    public void reduceMoney(ServerPlayer player, int amount) {
        this.getPlayerShopDataSafe(player).ifPresent(shopData -> shopData.reduceMoney(amount));
        this.syncShopMoneyData(player);
    }

    public void addMoney(UUID player, int amount) {
        this.getPlayerShopDataSafe(player).ifPresent(shopData -> shopData.addMoney(amount));
        this.syncShopMoneyData(player);
    }

    public void addMoney(ServerPlayer player, int amount) {
        this.getPlayerShopDataSafe(player).ifPresent(shopData -> shopData.addMoney(amount));
        this.syncShopMoneyData(player);
    }

    /**
     * 清空所有玩家的商店数据。
     */
    public void clearPlayerShopData() {
        this.playersData.clear();
    }

    public void clearPlayerShopData(UUID uuid) {
        this.playersData.remove(uuid);
    }

    public void resetPlayerData(List<UUID> uuids) {
        this.clearPlayerShopData();
        uuids.forEach(uuid -> this.getDefaultAndPutData(uuid, true));
    }

    public void resetPlayerData() {
        this.resetPlayerData(false);
    }

    public void resetPlayerData(boolean reset) {
        this.playersData.keySet().forEach(uuid -> getDefaultAndPutData(uuid, reset));
    }

    /**
     * 获取所有玩家的商店数据。
     *
     * @return 玩家商店数据的 Map
     */
    public Map<UUID, ShopData<T>> getPlayersData() {
        return playersData;
    }

    /**
     * 设置默认商店数据。
     *
     * @param data 默认商店数据
     */
    public void setDefaultShopData(Map<T, ArrayList<ShopSlot>> data) {
        this.defaultShopData.clear();
        this.defaultShopData.putAll(data);
        this.resetPlayerData();
    }

    /**
     * 替换默认商店数据中的某个槽位。
     *
     * @param type     类型
     * @param index    槽位索引
     * @param shopSlot 新的商店槽位
     */
    public void replaceDefaultShopData(String type, int index, ShopSlot shopSlot) {
        this.defaultShopData.get(valueOf(type)).set(index, shopSlot);
        this.resetPlayerData();
    }

    /**
     * 获取商店指定槽位的物品
     *
     * @param type  类型
     * @param index 槽位索引
     */
    public ItemStack getDefaultShopDataItemStack(String type, int index) {
        return this.defaultShopData.get(valueOf(type)).get(index).process();
    }

    /**
     * 设置默认商店数据的分组 ID。
     *
     * @param type    类型
     * @param index   槽位索引
     * @param groupId 分组 ID
     */
    public void setDefaultShopDataGroupId(String type, int index, int groupId) {
        this.defaultShopData.get(valueOf(type)).get(index).setGroupId(groupId);
        this.resetPlayerData();
    }

    /**
     * 添加默认商店数据的监听模块。
     *
     * @param type           类型
     * @param index          槽位索引
     * @param listenerModule 监听模块
     */
    public void addDefaultShopDataListenerModule(String type, int index, ListenerModule listenerModule) {
        this.defaultShopData.get(valueOf(type)).get(index).addListener(listenerModule);
        this.resetPlayerData();
    }

    /**
     * 移除默认商店数据的监听模块。
     *
     * @param type           类型
     * @param index          槽位索引
     * @param listenerModule 监听模块名称
     */
    public void removeDefaultShopDataListenerModule(String type, int index, String listenerModule) {
        this.defaultShopData.get(valueOf(type)).get(index).removeListenerModule(listenerModule);
        this.resetPlayerData();
    }

    /**
     * 设置默认商店数据的物品堆。
     *
     * @param type      类型
     * @param index     槽位索引
     * @param itemStack 物品堆
     */
    public void setDefaultShopDataItemStack(String type, int index, ItemStack itemStack) {
        this.defaultShopData.get(valueOf(type)).get(index).itemSupplier = itemStack::copy;
        this.resetPlayerData();
    }

    /**
     * 设置默认商店数据的成本。
     *
     * @param type  类型
     * @param index 槽位索引
     * @param cost  成本
     */
    public void setDefaultShopDataCost(String type, int index, int cost) {
        this.defaultShopData.get(valueOf(type)).get(index).setDefaultCost(cost);
        this.resetPlayerData();
    }

    public ImmutableMap<T, ImmutableList<ShopSlot>> getShopDataByRaw() {
        Map<T, List<ShopSlot>> modifiableMap = new HashMap<>(this.defaultShopData);

        ImmutableMap.Builder<T, ImmutableList<ShopSlot>> builder = ImmutableMap.builder();

        modifiableMap.forEach((k, v) -> {
            List<ShopSlot> shopSlots = new ArrayList<>();
            v.forEach(shopSlot -> shopSlots.add(shopSlot.copy()));
            builder.put(k, ImmutableList.copyOf(shopSlots));
        });

        return builder.build();
    }

    /**
     * 获取默认商店数据。
     *
     * @return 默认商店数据
     */
    public ShopData<T> getDefaultShopData() {
        return new ShopData<>(getShopDataByRaw(), this.typeCount, this.startMoney);
    }

    public List<ShopSlot> getDefaultShopSlotListByType(String type) {
        Map<T, ArrayList<ShopSlot>> map = new HashMap<>(this.defaultShopData);
        return map.get(valueOf(type));
    }

    public List<ShopSlot> getDefaultShopSlotListByType(T type) {
        Map<T, ArrayList<ShopSlot>> map = new HashMap<>(this.defaultShopData);
        return map.get(type);
    }

    public void getDefaultAndPutData(UUID uuid) {
        this.getDefaultAndPutData(uuid, false);
    }

    public ShopData<T> getDefaultAndPutData(UUID uuid, boolean resetMoney) {
        int money;
        if (this.playersData.containsKey(uuid) && !resetMoney) {
            money = this.playersData.get(uuid).getMoney();
        } else {
            money = this.startMoney;
        }
        FPSMShopEvent.DataInit<T> event = new FPSMShopEvent.DataInit<>(this, uuid, getShopDataByRaw(), money);
        MinecraftForge.EVENT_BUS.post(event);
        ShopData<T> data = new ShopData<>(event.getData(), this.typeCount, money);
        this.playersData.put(uuid, data);
        return data;
    }

    /**
     * 获取默认商店数据。
     *
     * @return 默认商店数据的 Map
     */
    public Map<T, ArrayList<ShopSlot>> getDefaultShopDataMap() {
        return this.defaultShopData;
    }

    /**
     * 获取默认商店数据（字符串键）。
     *
     * @return 默认商店数据的 Map（字符串键）
     */
    public Map<String, List<ShopSlot>> getDefaultShopDataMapString() {
        Map<String, List<ShopSlot>> map = new HashMap<>();
        this.defaultShopData.forEach((k, v) -> map.put(k.name(), v));
        return map;
    }

    public void setStartMoney(int money) {
        this.startMoney = money;
    }

    public int getStartMoney() {
        return startMoney;
    }

    /** Replaces this shop's editable configuration while preserving its identity and enum type. */
    public boolean isConfigurationCompatible(FPSMShop<?> source) {
        if (source == null) return false;
        for (T type : getEnums()) {
            try {
                if (source.getDefaultShopSlotListByType(type.name()) == null) return false;
            } catch (IllegalArgumentException incompatibleType) {
                return false;
            }
        }
        return true;
    }

    public void copyConfigurationFrom(FPSMShop<?> source) {
        Objects.requireNonNull(source, "source");
        Map<T, ArrayList<ShopSlot>> replacement = new HashMap<>();
        for (T type : getEnums()) {
            List<ShopSlot> sourceSlots;
            try {
                sourceSlots = source.getDefaultShopSlotListByType(type.name());
            } catch (IllegalArgumentException incompatibleType) {
                throw new IllegalArgumentException("Incompatible shop type: " + type.name(), incompatibleType);
            }
            if (sourceSlots == null) {
                throw new IllegalArgumentException("Missing shop type: " + type.name());
            }
            ArrayList<ShopSlot> copiedSlots = new ArrayList<>(sourceSlots.size());
            sourceSlots.forEach(slot -> copiedSlots.add(slot.copy()));
            replacement.put(type, copiedSlots);
        }

        clearPlayerShopData();
        defaultShopData.clear();
        defaultShopData.putAll(replacement);
        startMoney = source.getStartMoney();
    }

    public Codec<FPSMShop<T>> getCodec() {
        return codec;
    }

    public T valueOf(String named) {
        return T.valueOf(this.enumClass, named);
    }

    /**
     * 处理商店按钮操作。
     * <p>
     * 根据玩家的操作类型，更新玩家的商店数据并同步到客户端。
     *
     * @param serverPlayer 玩家对象
     * @param type         类型
     * @param index        槽位索引
     * @param action       操作类型
     */
    public ShopActionResult handleButton(ServerPlayer serverPlayer, INamedType type, int index, ShopAction action) {
        if (!serverPlayer.isAlive()) {
            return ShopActionResult.failure(ShopActionResult.Code.NOT_ALLOWED);
        }
        final T resolvedType;
        try {
            resolvedType = valueOf(type.name());
        } catch (IllegalArgumentException invalidType) {
            return ShopActionResult.failure(ShopActionResult.Code.INVALID_REQUEST);
        }
        ShopActionResult result = this.getPlayerShopData(serverPlayer.getUUID())
                .handleButton(serverPlayer, resolvedType, index, action);
        if (result.accepted()) {
            this.syncShopData(serverPlayer);
            this.syncShopMoneyData(serverPlayer);
        }
        return result;
    }

    public static <E extends Enum<E> & INamedType> Codec<FPSMShop<E>> withCodec(Class<E> enumClass) {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("mapName").forGetter(FPSMShop::getName),
                Codec.INT.fieldOf("defaultMoney").forGetter(FPSMShop::getDefaultMoney),
                Codec.unboundedMap(
                        Codec.STRING,
                        ShopSlot.CODEC.listOf()).fieldOf("shopData").forGetter(FPSMShop::getDefaultShopDataMapString),
                AreaData.CODEC.listOf().optionalFieldOf("areas", new ArrayList<>()).forGetter(FPSMShop::getAreas)).apply(instance, (n, defaultMoney, shopData, areas) -> {
                    Map<E, ArrayList<ShopSlot>> d = new HashMap<>();
                    shopData.forEach((t, l) -> {
                        try {
                            ArrayList<ShopSlot> list = new ArrayList<>(l);
                            d.put(E.valueOf(enumClass, t), list);
                        } catch (IllegalArgumentException e) {
                            // 未知/已删除的商店类型：跳过不崩，避免整个商店配置加载失败
                            FPSMatch.LOGGER.warn("FPSMShop: skipping unknown shop type '{}' while loading shop data", t);
                        }
                    });
                    return new FPSMShop<>(enumClass, n, d, defaultMoney, areas);
                }));
    }

    public List<AreaData> getAreas() {
        return List.copyOf(this.areas);
    }

    public void displayAreas(ServerPlayer player) {
        String prefix = "shop_area:" + this.name + ":";
        FPSMatch.sendToPlayer(player, new RemoveDebugDataByPrefixS2CPacket(prefix));

        int i = 1;
        for (AreaData data : this.areas) {
            FPSMatch.sendToPlayer(player, new AddAreaDataS2CPacket(
                    prefix + i,
                    Component.literal("SHOP_AREA_" + i),
                    PreviewColorUtil.getMapPreviewColor(this.name),
                    data));
            i++;
        }
    }

    public boolean isInArea(Entity entity) {
        for (AreaData areaData : this.areas) {
            if (areaData.isEntityInArea(entity)) return true;
        }
        return this.areas.isEmpty();
    }

    public void addArea(AreaData areaData) {
        this.areas.add(areaData);
    }

    public void clearAreas() {
        this.areas.clear();
    }
}
