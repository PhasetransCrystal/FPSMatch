package com.ptcrys.fpsmatch.core.shop.functional;

import com.ptcrys.fpsmatch.core.shop.event.CheckCostEvent;
import com.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

/**
 * 监听模块接口，用于定义商店槽位变更事件的处理逻辑。
 * <p>
 * 该接口允许开发者实现自定义的监听模块，用于在商店槽位变更时执行特定的逻辑。
 * 每个监听模块都有一个唯一的名称和优先级，用于控制事件处理的顺序。
 */
public interface ListenerModule {

    /**
     * 处理商店槽位变更事件。
     * <p>
     * 当商店槽位的状态发生变化时（例如购买、退回或锁定），该方法会被调用。
     * 实现该方法时，可以访问事件对象以获取相关信息，并执行自定义逻辑。
     *
     * @param event 商店槽位变更事件
     */
    default void onChange(ShopSlotChangeEvent event) {}

    /**
     * 处理商店槽位变更事件。
     * <p>
     * 当商店槽位的有组ID时且在购买物品计算价格时，该方法会被调用。
     * 实现该方法时，可以访问事件对象以获取相关信息，并执行自定义逻辑。
     *
     * @param event 商店槽位价格检查事件
     * @param slot  当前商店槽位
     */
    default void onCostCheck(CheckCostEvent event, ShopSlot slot) {}

    /**
     * 处理商店槽位重置事件。
     * <p>
     * 当商店槽位重置时，该方法会被调用。
     * 实现该方法时，可以访问事件对象以获取相关信息，并执行自定义逻辑。
     *
     * @param slot 商店槽位
     */
    default void onReset(ShopSlot slot) {}

    /**
     * 获取监听模块的名称。
     * <p>
     * 模块名称用于唯一标识该模块，并在日志和调试中显示。
     *
     * @return 监听模块的名称
     */
    String getName();

    /**
     * 获取监听模块的优先级。
     * <p>
     * 优先级用于控制事件处理的顺序。优先级越高的模块会先处理事件。
     * 如果多个模块的优先级相同，则按照注册顺序处理。
     *
     * @return 监听模块的优先级
     */
    int getPriority();
}
