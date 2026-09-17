package net.ptcrys.fpsmatch.core.shop;

import net.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import net.ptcrys.fpsmatch.core.shop.event.CheckCostEvent;
import net.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.mojang.datafixers.util.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用于管理玩家商店数据的类。
 * <p>
 * 该类存储了玩家的金钱和商店槽位数据，并提供了购买、退回和锁定槽位的功能。
 * 同时支持广播事件以处理槽位变更和成本检查。
 */
public class ShopData<T extends Enum<T> & INamedType> {

    private int maxMoney = 16000;

    /**
     * 玩家当前的金钱数量。
     */
    private int money = 800;

    /**
     * 存储商店数据的 Map，键为物品类型，值为不可变的商店槽位列表。
     */
    private final Map<T, ImmutableList<ShopSlot>> data = new HashMap<>();

    /**
     * 存储分组数据的 Multimap，键为分组 ID，值为商店槽位。
     */
    private Multimap<Integer, ShopSlot> grouped;

    /**
     * 构造函数，初始化商店数据。
     *
     * @param shopData 商店数据
     */
    private <E extends ImmutableList<ShopSlot>> ShopData(Map<T, E> shopData, int checker) {
        // 检查数据是否合法
        checkData(shopData, checker);
        this.setDoneData(shopData);
    }

    /**
     * 设置商店数据。
     *
     * @param shopData 商店数据
     */
    public <E extends ImmutableList<ShopSlot>> void setDoneData(Map<T, E> shopData) {
        // 赋值给 data 字段
        data.clear();
        data.putAll(shopData);

        // 遍历 data 中的每个值，即每个类型的商店槽位列表
        data.values().forEach(shopSlots -> {
            // 创建一个原子整数，用于记录当前的索引值
            AtomicInteger index = new AtomicInteger();
            // 遍历每个商店槽位，并设置其索引值
            shopSlots.forEach(slots -> slots.setIndex(index.getAndIncrement()));
        });

        // 创建一个不可变 Multimap 的构建器
        ImmutableMultimap.Builder<Integer, ShopSlot> builder2 = ImmutableMultimap.builder();
        // 遍历 data 中的每个值，即每个类型的商店槽位列表
        data.values().stream().flatMap(Collection::stream).filter(ShopSlot::haveGroup).forEach(slot -> builder2.put(slot.getGroupId(), slot));
        // 赋值给 grouped 字段
        grouped = builder2.build();
    }

    /**
     * 带初始金钱的构造函数。
     *
     * @param shopData 商店数据
     * @param money    玩家初始金钱
     */
    public <E extends ImmutableList<ShopSlot>> ShopData(Map<T, E> shopData, int checker, int money) {
        this(shopData, checker);
        this.money = money;
    }

    /**
     * 检查商店数据是否合法。
     * <p>
     * 确保每个物品类型都有对应的商店槽位列表，并且每个列表的大小为 5。
     *
     * @param data 商店数据
     */
    public <E extends List<ShopSlot>> void checkData(Map<T, E> data, int checker) {
        // 获取枚举类的所有值
        List<T> enumConstants = data.keySet().stream().toList();
        int typeN = enumConstants.size();
        if (typeN != checker) {
            throw new RuntimeException("Incorrect number of type. Expected " + checker + " but found " + typeN);
        }

        // 遍历所有的物品类型
        for (T type : enumConstants) {
            // 获取该类型的商店槽位列表
            List<ShopSlot> slots = data.getOrDefault(type, null);
            // 如果没有找到该类型的商店槽位列表，则抛出异常
            if (slots == null) throw new RuntimeException("No slots found for type " + type);
            // 如果该类型的商店槽位列表数量不等于枚举类型数量，则抛出异常
            if (slots.size() != type.slotCount()) throw new RuntimeException("Incorrect number of slots for type " + type + ". Expected " + type.slotCount() + " but found " + slots.size());
        }
    }

    /**
     * 获取商店数据。
     *
     * @return 商店数据
     */
    public Map<T, ImmutableList<ShopSlot>> getData() {
        return data;
    }

    /**
     * 设置玩家的金钱数量。
     *
     * @param money 玩家的金钱数量
     */
    public void setMoney(int money) {
        // 保留 -1（无限金钱）哨兵，与 unlimited() 语义一致；
        // 否则 addMoney/reduceMoney 中的 unlimited() 短路分支形同虚设（-1 在入口即被夹成 0）
        if (money == -1) {
            this.money = -1;
        } else {
            this.money = Math.max(0, Math.min(16000, money));
        }
    }

    /**
     * 减少玩家的金钱。
     *
     * @param money 减少的金钱数量
     */
    public void reduceMoney(int money) {
        if (unlimited()) return;
        this.money = Math.max(0, this.money -= Math.max(0, money));
    }

    /**
     * 增加玩家的金钱。
     *
     * @param money 增加的金钱数量
     */
    public void addMoney(int money) {
        if (unlimited()) return;
        this.money = Math.min(getMaxMoney(), this.money += Math.max(0, money));
    }

    /**
     * 获取指定类型的商店槽位列表。
     *
     * @param type 物品类型
     * @return 商店槽位列表
     */
    public List<ShopSlot> getShopSlotsByType(T type) {
        return this.data.get(type);
    }

    /**
     * 获取玩家的当前金钱。
     *
     * @return 玩家的金钱数量
     */
    public int getMoney() {
        return this.money;
    }

    public boolean unlimited() {
        return this.money == -1;
    }

    /**
     * 处理商店按钮操作。
     * <p>
     * 根据操作类型（购买或退回），调用对应的处理方法。
     *
     * @param player 玩家对象
     * @param type   物品类型
     * @param index  槽位索引
     * @param action 操作类型
     */
    public ShopActionResult handleButton(ServerPlayer player, T type, int index, ShopAction action) {
        List<ShopSlot> shopSlotList = data.get(type);
        if (shopSlotList == null || index < 0 || index >= shopSlotList.size()) {
            return ShopActionResult.failure(ShopActionResult.Code.INVALID_REQUEST);
        }
        ShopSlot currentSlot = shopSlotList.get(index);

        return switch (action) {
            case BUY -> this.handleBuy(player, currentSlot);
            case RETURN -> this.handleReturn(player, currentSlot);
        };
    }

    /**
     * 处理购买操作。
     * <p>
     * 检查玩家是否有足够的金钱，并调用槽位的购买方法。
     *
     * @param player      玩家对象
     * @param currentSlot 当前槽位
     */
    protected ShopActionResult handleBuy(ServerPlayer player, ShopSlot currentSlot) {
        if (currentSlot.isLocked() || currentSlot.getBoughtCount() >= currentSlot.getMaxBuyCount()) {
            return ShopActionResult.failure(ShopActionResult.Code.LOCKED_OR_MAX_COUNT);
        }
        boolean check = this.broadcastCostCheckEvent(player, currentSlot);
        if (check || currentSlot.canBuy(this.money)) {
            this.broadcastGroupChangeEvent(player, currentSlot, 1);
            this.setMoney(currentSlot.buy(player, this.money));
            return ShopActionResult.success();
        }
        return ShopActionResult.failure(ShopActionResult.Code.INSUFFICIENT_FUNDS);
    }

    /**
     * 处理退回操作。
     * <p>
     * 检查玩家是否可以退回物品，并调用槽位的退回方法。
     *
     * @param player      玩家对象
     * @param currentSlot 当前槽位
     */
    protected ShopActionResult handleReturn(ServerPlayer player, ShopSlot currentSlot) {
        AtomicBoolean checkFlag = new AtomicBoolean(true);
        List<ShopSlot> groupSlot = currentSlot.haveGroup() ? this.grouped.get(currentSlot.getGroupId()).stream().filter((slot) -> slot != currentSlot).toList() : new ArrayList<>();
        for (ShopSlot slot : groupSlot) {
            slot.getListenerNames().forEach(name -> {
                if (name.contains("changeItem") && slot.getBoughtCount() > 0 && !slot.canReturn(player)) {
                    checkFlag.set(false);
                }
            });
        }

        if (currentSlot.canReturn(player) && checkFlag.get()) {
            this.broadcastGroupChangeEvent(player, currentSlot, -1);
            this.addMoney(currentSlot.getCost());
            currentSlot.returnItem(player);
            return ShopActionResult.success();
        }
        return ShopActionResult.failure(ShopActionResult.Code.CANNOT_RETURN);
    }

    /**
     * 锁定商店槽位。
     * <p>
     * 根据玩家的背包物品，锁定已购买的槽位。
     *
     * @param player 玩家对象
     */
    public void lockShopSlots(ServerPlayer player) {
        List<NonNullList<ItemStack>> items = ImmutableList.of(player.getInventory().items, player.getInventory().armor, player.getInventory().offhand);

        Map<ShopSlot, Boolean> checkFlag = new HashMap<>();
        data.forEach(((itemType, shopSlots) -> items.forEach(list -> list.forEach(itemStack -> {
            for (ShopSlot shopSlot : shopSlots) {
                if (itemStack.isEmpty()) continue;
                if (shopSlot.returningChecker.test(itemStack)) {
                    if (itemStack.getCount() >= shopSlot.getMaxBuyCount()) {
                        shopSlot.lock();
                    } else {
                        shopSlot.unlock(itemStack.getCount());
                    }
                    checkFlag.put(shopSlot, false);
                } else if (checkFlag.getOrDefault(shopSlot, true)) {
                    shopSlot.unlock();
                    checkFlag.put(shopSlot, true);
                }
            }
        }))));
        checkFlag.forEach(((shopSlot, aBoolean) -> {
            if (aBoolean && shopSlot.getBoughtCount() > 0) {
                shopSlot.reset();
            }
        }));
    }

    /**
     * 广播成本检查事件。
     * <p>
     * 检查玩家是否有足够的金钱购买当前槽位的物品。
     *
     * @param player      玩家对象
     * @param currentSlot 当前槽位
     * @return 如果检查通过，返回 true；否则返回 false
     */
    protected boolean broadcastCostCheckEvent(ServerPlayer player, ShopSlot currentSlot) {
        List<ShopSlot> groupSlot = currentSlot.haveGroup() ? new ArrayList<>() : this.grouped.get(currentSlot.getGroupId()).stream().filter((slot) -> slot != currentSlot).toList();
        CheckCostEvent event = new CheckCostEvent(player, currentSlot.getCost());
        groupSlot.forEach(slot -> slot.handleCheckCostEvent(event));

        return event.success();
    }

    /**
     * 广播槽位变更事件。
     * <p>
     * 通知其他槽位当前槽位的状态变更。
     *
     * @param player      玩家对象
     * @param currentSlot 当前槽位
     * @param flag        变更标志（1 表示购买，-1 表示退回）
     */
    protected void broadcastGroupChangeEvent(ServerPlayer player, ShopSlot currentSlot, int flag) {
        List<ShopSlot> groupSlot = currentSlot.haveGroup() ? this.grouped.get(currentSlot.getGroupId()).stream().filter((slot) -> slot != currentSlot).toList() : new ArrayList<>();

        groupSlot.forEach(slot -> {
            ShopSlotChangeEvent event = new ShopSlotChangeEvent(slot, player, this.money, flag);
            slot.onGroupSlotChanged(event);
            this.setMoney(event.getMoney());
        });
    }

    /**
     * 检查玩家的背包中是否包含商店中的某个物品。
     *
     * @param itemStack 玩家的物品堆
     * @return 如果找到匹配的槽位，返回槽位信息；否则返回 null
     */
    @Nullable
    public Pair<T, ShopSlot> checkItemStackIsInData(ItemStack itemStack) {
        AtomicReference<Pair<T, ShopSlot>> flag = new AtomicReference<>();
        if (GunCompatManager.isGun(itemStack)) {
            ResourceLocation gunId = GunCompatManager.findProvider(itemStack).getGunId(itemStack);
            data.forEach(((itemType, shopSlots) -> shopSlots.forEach(shopSlot -> {
                ItemStack itemStack1 = shopSlot.process();
                if (GunCompatManager.isGun(itemStack1) && gunId.equals(GunCompatManager.findProvider(itemStack1).getGunId(itemStack1)) && !itemStack1.isEmpty()) {
                    flag.set(new Pair<>(itemType, shopSlot));
                }
            })));
        } else {
            data.forEach(((itemType, shopSlots) -> shopSlots.forEach(shopSlot -> {
                ItemStack itemStack1 = shopSlot.process();
                if (itemStack.getDisplayName().getString().equals(itemStack1.getDisplayName().getString()) && !itemStack1.isEmpty()) {
                    flag.set(new Pair<>(itemType, shopSlot));
                }
            })));
        }
        return flag.get();
    }

    public int getMaxMoney() {
        return maxMoney;
    }

    public void setMaxMoney(int maxMoney) {
        this.maxMoney = maxMoney;
    }
}
