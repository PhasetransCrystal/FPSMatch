package com.ptcrys.fpsmatch.common.drop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

public class ThrowableRegistry {

    private static final Map<String, ThrowableSubType> SUBTYPES_BY_ID = new ConcurrentHashMap<>();
    private static final Map<Item, ThrowableSubType> ITEM_TO_SUBTYPE = new ConcurrentHashMap<>();
    private static final Map<ThrowableSubType, Set<Item>> SUBTYPE_TO_ITEMS = new ConcurrentHashMap<>();
    private static final Map<ThrowableSubType, Integer> SUBTYPE_LIMITS = new ConcurrentHashMap<>();

    public static final ThrowableSubType GRENADE = ThrowableRegistry.registerSubType("grenade", 1);
    public static final ThrowableSubType FLASH_BANG = ThrowableRegistry.registerSubType("flash_bang", 2);
    public static final ThrowableSubType SMOKE = ThrowableRegistry.registerSubType("smoke", 1);
    public static final ThrowableSubType MOLOTOV = ThrowableRegistry.registerSubType("molotov", 1);
    public static final ThrowableSubType DECOY = ThrowableRegistry.registerSubType("decoy", 1);

    /**
     * 注册一个新的投掷物子类型
     */
    public static ThrowableSubType registerSubType(String id, int defaultLimit, String displayName) {
        ThrowableSubType subType = new ThrowableSubType(id, defaultLimit, displayName);
        SUBTYPES_BY_ID.put(id, subType);
        SUBTYPE_TO_ITEMS.put(subType, new HashSet<>());
        SUBTYPE_LIMITS.put(subType, defaultLimit);
        return subType;
    }

    /**
     * 注册一个新的投掷物子类型（使用id作为显示名）
     */
    public static ThrowableSubType registerSubType(String id, int defaultLimit) {
        return registerSubType(id, defaultLimit, id);
    }

    /**
     * 将物品注册到指定的投掷物子类型
     */
    public static void registerItemToSubType(Item item, ThrowableSubType subType) {
        ITEM_TO_SUBTYPE.put(item, subType);
        SUBTYPE_TO_ITEMS.get(subType).add(item);
    }

    /**
     * 将物品注册到指定的投掷物子类型
     */
    public static void registerItemToSubType(ResourceLocation itemId, String subTypeId) {
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item != null) {
            registerItemToSubType(item, subTypeId);
        }
    }

    /**
     * 将物品注册到指定的投掷物子类型
     */
    public static void registerItemToSubType(Item item, String subTypeId) {
        ThrowableSubType subType = SUBTYPES_BY_ID.get(subTypeId);
        if (subType != null) {
            registerItemToSubType(item, subType);
        }
    }

    /**
     * 批量注册物品到子类型
     */
    public static void registerItemsToSubTypes(Map<ResourceLocation, String> registrations) {
        registrations.forEach(ThrowableRegistry::registerItemToSubType);
    }

    /**
     * 获取物品的投掷物子类型
     */
    @Nullable
    public static ThrowableSubType getThrowableSubType(Item item) {
        return ITEM_TO_SUBTYPE.get(item);
    }

    /**
     * 获取指定ID的子类型
     */
    @Nullable
    public static ThrowableSubType getSubTypeById(String id) {
        return SUBTYPES_BY_ID.get(id);
    }

    /**
     * 获取指定子类型的所有物品
     */
    public static Set<Item> getItemsForSubType(ThrowableSubType subType) {
        return Collections.unmodifiableSet(SUBTYPE_TO_ITEMS.getOrDefault(subType, new HashSet<>()));
    }

    /**
     * 获取指定ID的子类型的所有物品
     */
    public static Set<Item> getItemsForSubTypeId(String subTypeId) {
        ThrowableSubType subType = getSubTypeById(subTypeId);
        return subType != null ? getItemsForSubType(subType) : Collections.emptySet();
    }

    /**
     * 获取指定子类型的限制数量
     */
    public static int getLimitForSubType(ThrowableSubType subType) {
        return SUBTYPE_LIMITS.getOrDefault(subType, subType.getDefaultLimit());
    }

    /**
     * 获取指定ID的子类型的限制数量
     */
    public static int getLimitForSubTypeId(String subTypeId) {
        ThrowableSubType subType = getSubTypeById(subTypeId);
        return subType != null ? getLimitForSubType(subType) : 1;
    }

    /**
     * 设置指定子类型的限制数量
     */
    public static void setLimitForSubType(ThrowableSubType subType, int limit) {
        if (limit >= 0) {
            SUBTYPE_LIMITS.put(subType, limit);
        }
    }

    /**
     * 设置指定ID的子类型的限制数量
     */
    public static void setLimitForSubTypeId(String subTypeId, int limit) {
        ThrowableSubType subType = getSubTypeById(subTypeId);
        if (subType != null) {
            setLimitForSubType(subType, limit);
        }
    }

    /**
     * 检查物品是否是已注册的投掷物
     */
    public static boolean isRegisteredThrowable(Item item) {
        return ITEM_TO_SUBTYPE.containsKey(item);
    }

    /**
     * 获取所有已注册的投掷物子类型
     */
    public static Collection<ThrowableSubType> getAllSubTypes() {
        return Collections.unmodifiableCollection(SUBTYPES_BY_ID.values());
    }

    /**
     * 获取所有已注册的投掷物物品
     */
    public static Set<Item> getAllRegisteredThrowables() {
        return Collections.unmodifiableSet(ITEM_TO_SUBTYPE.keySet());
    }

    /**
     * 清除所有注册（用于重载）
     */
    public static void clearRegistry() {
        SUBTYPES_BY_ID.clear();
        ITEM_TO_SUBTYPE.clear();
        for (Set<Item> items : SUBTYPE_TO_ITEMS.values()) {
            items.clear();
        }
        SUBTYPE_LIMITS.clear();
    }

    /**
     * 获取所有注册的子类型ID
     */
    public static Set<String> getAllSubTypeIds() {
        return Collections.unmodifiableSet(SUBTYPES_BY_ID.keySet());
    }
}
