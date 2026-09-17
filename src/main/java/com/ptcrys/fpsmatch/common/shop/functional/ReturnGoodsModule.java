package com.ptcrys.fpsmatch.common.shop.functional;

import com.ptcrys.fpsmatch.core.shop.event.CheckCostEvent;
import com.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import com.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

/**
 * 退货模块，用于处理商店槽位的退货逻辑。
 * <p>
 * 当玩家尝试退回已购买的物品时，该模块会检查是否允许退货，并执行退货操作。
 * 如果允许退货，会将物品退回玩家的背包，并返还相应的金钱。
 */
public class ReturnGoodsModule implements ListenerModule {

    /**
     * 处理商店槽位变更事件。
     * <p>
     * 如果事件标志为正（表示购买操作）且槽位支持退货，则执行退货操作：
     * - 将槽位的物品退回玩家背包。
     * - 返还槽位的购买成本。
     *
     * @param event 商店槽位变更事件
     */
    @Override
    public void onChange(ShopSlotChangeEvent event) {
        if (event.flag >= 1 && event.shopSlot.canReturn(event.player)) {
            event.addMoney(event.shopSlot.getCost());
            event.shopSlot.returnItem(event.player);
        }
    }

    @Override
    public void onCostCheck(CheckCostEvent event, ShopSlot slot) {
        if (slot.canReturn(event.player())) {
            event.addCost(slot.getCost());
        }
    }

    /**
     * 获取模块名称。
     * <p>
     * 该模块的名称为 "returnGoods"，表示其功能是处理退货逻辑。
     * 
     * @return 模块名称
     */
    @Override
    public String getName() {
        return "returnGoods";
    }

    /**
     * 获取模块优先级。
     * <p>
     * 
     * @return 模块优先级
     */
    @Override
    public int getPriority() {
        return 5;
    }
}
