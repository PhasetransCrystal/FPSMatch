package net.ptcrys.fpsmatch.core.shop.runtime;

import net.ptcrys.fpsmatch.core.shop.INamedType;
import net.ptcrys.fpsmatch.core.shop.ShopData;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;

/** Fixed player graph whose access is authorized by its publishing shop. */
final class RuntimeShopData<T extends Enum<T> & INamedType> extends ShopData<T> {

    private final ImmutableMap<T, ImmutableList<ShopSlot>> graph;
    private final Runnable access;

    RuntimeShopData(ImmutableMap<T, ImmutableList<ShopSlot>> graph, int money, Runnable access) {
        super(graph, money);
        if (access == null) throw RuntimeItemMessages.error("invalid_input");
        this.graph = graph;
        this.access = access;
    }

    @Override
    protected void requireRuntimeState() {
        access.run();
    }

    @Override
    protected void requireRuntimeSlot(ShopSlot slot) {
        access.run();
        ((RuntimeShopSlot) slot).currentTemplate();
    }

    @Override
    protected void requireRuntimeGraph() {
        access.run();
        for (ImmutableList<ShopSlot> slots : graph.values()) {
            for (ShopSlot slot : slots) ((RuntimeShopSlot) slot).currentTemplate();
        }
        access.run();
    }

    @Override
    protected void requireDefinitionMutable() {
        access.run();
        throw RuntimeItemMessages.error("invalid_slot");
    }

    @Override
    public Map<T, ImmutableList<ShopSlot>> getData() {
        access.run();
        return graph;
    }

    @Override
    public <E extends List<ShopSlot>> void checkData(Map<T, E> data, int checker) {
        access.run();
        if (data == null) throw RuntimeItemMessages.error("invalid_input");
        int size = data.size();
        access.run();
        if (checker != graph.size() || size != graph.size()) throw RuntimeItemMessages.error("invalid_slot");
        for (Map.Entry<T, ImmutableList<ShopSlot>> entry : graph.entrySet()) {
            access.run();
            E slots = data.get(entry.getKey());
            access.run();
            if (slots == null) throw RuntimeItemMessages.error("invalid_slot");
            int slotCount = slots.size();
            access.run();
            if (slotCount != entry.getValue().size()) throw RuntimeItemMessages.error("invalid_slot");
        }
        access.run();
    }
}
