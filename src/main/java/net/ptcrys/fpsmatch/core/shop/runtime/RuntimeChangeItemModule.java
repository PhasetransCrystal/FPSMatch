package net.ptcrys.fpsmatch.core.shop.runtime;

import net.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import net.ptcrys.fpsmatch.core.shop.functional.GroupReturnDependent;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import net.minecraft.world.item.ItemStack;

/** Private Change listener; its templates never enter the global module manager. */
public final class RuntimeChangeItemModule implements ListenerModule, GroupReturnDependent {

    private final RuntimeItemTemplates.Template defaultTemplate;
    private final RuntimeItemTemplates.Template changedTemplate;
    private final int defaultCost;
    private final int changedCost;
    private final String moduleName;

    public RuntimeChangeItemModule(RuntimeItemTemplates.Template defaultTemplate, int defaultCost,
                                   RuntimeItemTemplates.Template changedTemplate, int changedCost,
                                   String moduleName) {
        if (defaultTemplate == null) throw RuntimeItemMessages.error("invalid_input");
        defaultTemplate.requireAvailable();
        if (changedTemplate == null) throw RuntimeItemMessages.error("invalid_input");
        if (!defaultTemplate.hasSameOwner(changedTemplate)) throw RuntimeItemMessages.error("foreign_template");
        changedTemplate.requireAvailable();
        if (moduleName == null || moduleName.isEmpty()) throw RuntimeItemMessages.error("invalid_input");
        this.defaultTemplate = defaultTemplate;
        this.changedTemplate = changedTemplate;
        this.defaultCost = defaultCost;
        this.changedCost = changedCost;
        this.moduleName = moduleName;
    }

    public RuntimeItemTemplates.Template defaultTemplate() {
        return defaultTemplate;
    }

    public RuntimeItemTemplates.Template changedTemplate() {
        return changedTemplate;
    }

    public int defaultCost() {
        return defaultCost;
    }

    public int changedCost() {
        return changedCost;
    }

    private RuntimeShopSlot requireSlot(ShopSlot slot) {
        defaultTemplate.requireAvailable();
        changedTemplate.requireAvailable();
        if (!(slot instanceof RuntimeShopSlot managed)) throw RuntimeItemMessages.error("invalid_slot");
        if (!defaultTemplate.hasSameOwner(managed.currentTemplate())) {
            throw RuntimeItemMessages.error("foreign_template");
        }
        managed.requireDeclaredListener(this);
        return managed;
    }

    @Override
    public void onChange(ShopSlotChangeEvent event) {
        defaultTemplate.requireAvailable();
        if (event == null) throw RuntimeItemMessages.error("invalid_slot");
        RuntimeShopSlot slot = requireSlot(event.shopSlot);
        if (slot.getBoughtCount() > 0 && !matchesChanged(slot)) return;
        if (event.flag > 0) {
            slot.setItemSupplier(changedTemplate);
            slot.setCost(changedCost);
        } else if (event.flag < 0) {
            slot.returnItem(event.player);
            event.addMoney(slot.getCost());
            slot.setItemSupplier(defaultTemplate);
            slot.setCost(defaultCost);
        }
    }

    private boolean matchesChanged(RuntimeShopSlot slot) {
        ItemStack temporary = changedTemplate.get();
        Throwable failure = null;
        try {
            return slot.returningChecker.test(temporary);
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (temporary != ItemStack.EMPTY) {
                try {
                    temporary.invalidateCaps();
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) throw cleanup;
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
        }
    }

    @Override
    public void onReset(ShopSlot slot) {
        RuntimeShopSlot managed = requireSlot(slot);
        managed.setItemSupplier(defaultTemplate);
        managed.setCost(defaultCost);
    }

    @Override
    public String getName() {
        return moduleName;
    }

    @Override
    public int getPriority() {
        return 1;
    }
}
