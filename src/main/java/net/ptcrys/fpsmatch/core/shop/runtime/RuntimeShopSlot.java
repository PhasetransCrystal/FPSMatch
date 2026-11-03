package net.ptcrys.fpsmatch.core.shop.runtime;

import net.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** A player slot sharing fixed templates owned by one runtime candidate. */
public final class RuntimeShopSlot extends ShopSlot {

    private final RuntimeItemTemplates.Template ownerTemplate;
    private Definition definition;
    private Object frozenListenerVersion;
    private Runnable runtimeAccess;

    public RuntimeShopSlot(RuntimeItemTemplates.Template template,
                           int defaultCost, int maxBuyCount, int groupId) {
        super(available(template), defaultCost, maxBuyCount, groupId, null);
        ownerTemplate = template;
    }

    private static RuntimeItemTemplates.Template available(RuntimeItemTemplates.Template template) {
        if (template == null) throw RuntimeItemMessages.error("invalid_input");
        template.requireAvailable();
        return template;
    }

    public RuntimeItemTemplates.Template currentTemplate() {
        ownerTemplate.requireAvailable();
        if (runtimeAccess != null) runtimeAccess.run();
        RuntimeItemTemplates.Template template = validateSupplier(itemSupplier);
        if (definition != null) {
            if (defaultCost != definition.defaultCost || super.getMaxBuyCount() != definition.maxBuyCount || super.getGroupId() != definition.groupId || super.getIndex() != definition.index || listenerReferenceVersion() != frozenListenerVersion || !definition.templates.contains(template)) {
                throw RuntimeItemMessages.error("invalid_slot");
            }
        }
        return template;
    }

    void bindRuntimeAccess(Runnable access) {
        currentTemplate();
        if (access == null) throw RuntimeItemMessages.error("invalid_input");
        if (definition == null || runtimeAccess != null) throw RuntimeItemMessages.error("invalid_slot");
        runtimeAccess = access;
    }

    boolean isRuntimeBound() {
        return runtimeAccess != null;
    }

    private void requireBoundObservation() {
        if (runtimeAccess != null) currentTemplate();
    }

    @Override
    public int getCost() {
        requireBoundObservation();
        return super.getCost();
    }

    @Override
    public int getDefaultCost() {
        requireBoundObservation();
        return super.getDefaultCost();
    }

    @Override
    public int getGroupId() {
        requireBoundObservation();
        return super.getGroupId();
    }

    @Override
    public int getBoughtCount() {
        requireBoundObservation();
        return super.getBoughtCount();
    }

    @Override
    public int getCountAgainstLimit() {
        requireBoundObservation();
        return super.getCountAgainstLimit();
    }

    @Override
    public int getMaxBuyCount() {
        requireBoundObservation();
        return super.getMaxBuyCount();
    }

    @Override
    public boolean isLocked() {
        requireBoundObservation();
        return super.isLocked();
    }

    @Override
    public int getIndex() {
        requireBoundObservation();
        return super.getIndex();
    }

    @Override
    public boolean haveGroup() {
        requireBoundObservation();
        return super.haveGroup();
    }

    @Override
    public List<ListenerModule> getListenerModules() {
        requireBoundObservation();
        return super.getListenerModules();
    }

    /** Fix the exact definition while leaving price, counts and locks as runtime state. */
    public void freezeDefinition() {
        ownerTemplate.requireAvailable();
        if (isListenerMutationActive()) throw RuntimeItemMessages.error("busy");
        RuntimeItemTemplates.Template current = currentTemplate();
        if (definition != null) {
            if (!hasListenerReferences(definition.listeners)) throw RuntimeItemMessages.error("invalid_slot");
            return;
        }
        if (super.getIndex() < 0) throw RuntimeItemMessages.error("invalid_slot");
        List<ListenerModule> listeners = super.getListenerModules();
        if (listeners.size() > 8192) throw RuntimeItemMessages.error("budget");
        Set<RuntimeItemTemplates.Template> templates = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ListenerModule> declared = Collections.newSetFromMap(new IdentityHashMap<>());
        templates.add(current);
        for (ListenerModule listener : listeners) {
            declared.add(listener);
            if (listener instanceof RuntimeChangeItemModule change) {
                templates.add(validateSupplier(change.defaultTemplate()));
                templates.add(validateSupplier(change.changedTemplate()));
            }
        }
        Definition frozen = new Definition(defaultCost, super.getMaxBuyCount(), super.getGroupId(), super.getIndex(),
                listeners, Collections.unmodifiableSet(templates), Collections.unmodifiableSet(declared));
        if (!hasListenerReferences(frozen.listeners)) throw RuntimeItemMessages.error("invalid_slot");
        frozenListenerVersion = listenerReferenceVersion();
        definition = frozen;
    }

    public boolean isDefinitionFrozen() {
        return definition != null;
    }

    @Override
    protected void requireDefinitionMutable() {
        currentTemplate();
        if (definition != null) throw RuntimeItemMessages.error("invalid_slot");
    }

    @Override
    protected void requireRuntimeState() {
        currentTemplate();
    }

    @Override
    public void setIndex(int index) {
        currentTemplate();
        if (definition != null) {
            if (index != definition.index) throw RuntimeItemMessages.error("invalid_slot");
            return;
        }
        super.setIndex(index);
    }

    void requireDeclaredListener(ListenerModule listener) {
        currentTemplate();
        if (definition != null && !definition.declared.contains(listener)) {
            throw RuntimeItemMessages.error("invalid_slot");
        }
    }

    private RuntimeItemTemplates.Template validateSupplier(Supplier<ItemStack> supplier) {
        if (!(supplier instanceof RuntimeItemTemplates.Template template)) {
            throw RuntimeItemMessages.error("unmanaged_supplier");
        }
        if (!ownerTemplate.hasSameOwner(template)) throw RuntimeItemMessages.error("foreign_template");
        template.requireAvailable();
        return template;
    }

    @Override
    public void setItemSupplier(Supplier<ItemStack> supplier) {
        currentTemplate();
        RuntimeItemTemplates.Template template = validateSupplier(supplier);
        if (definition != null && !definition.templates.contains(template)) {
            throw RuntimeItemMessages.error("invalid_slot");
        }
        itemSupplier = template;
    }

    @Override
    public ItemStack process() {
        return currentTemplate().get();
    }

    @Override
    public RuntimeShopSlot copy() {
        RuntimeItemTemplates.Template current = currentTemplate();
        if (definition != null && !hasListenerReferences(definition.listeners)) {
            throw RuntimeItemMessages.error("invalid_slot");
        }
        RuntimeShopSlot slot = new RuntimeShopSlot(current, getDefaultCost(), getMaxBuyCount(), getGroupId());
        slot.setIndex(getIndex());
        copyListenerReferencesTo(slot);
        if (definition != null) {
            if (!slot.hasListenerReferences(definition.listeners)) throw RuntimeItemMessages.error("invalid_slot");
            slot.frozenListenerVersion = slot.listenerReferenceVersion();
            slot.definition = definition;
        }
        currentTemplate();
        slot.runtimeAccess = runtimeAccess;
        return slot;
    }

    @Override
    public Predicate<ItemStack> getDefaultChecker() {
        return item -> currentTemplate().inspect(shopItem -> {
            if (GunCompatManager.isGun(item)) {
                ResourceLocation gunId = GunCompatManager.findProvider(item).getGunId(item);
                return GunCompatManager.isGun(shopItem) && gunId.equals(GunCompatManager.findProvider(shopItem).getGunId(shopItem));
            }
            return item.getDisplayName().getString().equals(shopItem.getDisplayName().getString()) && shopItem.getItem() == item.getItem();
        });
    }

    @Override
    public boolean isEmptyItem() {
        return currentTemplate().inspect(ItemStack::isEmpty);
    }

    @Override
    protected ItemStack createGroupDropItem() {
        return process();
    }

    @Override
    public int getAmmoCount() {
        return currentTemplate().inspect(item -> GunCompatManager.findProvider(item).getMaxDummyAmmo(item));
    }

    @Override
    public int getDummyAmmoCount() {
        return currentTemplate().inspect(item -> GunCompatManager.findProvider(item).getMaxDummyAmmo(item));
    }

    @Override
    public String toString() {
        RuntimeItemTemplates.Template template = currentTemplate();
        java.util.ArrayList<String> listeners = new java.util.ArrayList<>();
        for (ListenerModule listener : super.getListenerModules()) {
            currentTemplate();
            listeners.add(String.valueOf(listener));
            currentTemplate();
        }
        String state = ", defaultCost=" + super.getDefaultCost() + ", cost=" + super.getCost() + ", groupId=" + super.getGroupId() + ", boughtCount=" + super.getBoughtCount() + ", maxBuyCount=" + super.getMaxBuyCount() + ", locked=" + super.isLocked() + ", index=" + super.getIndex() + ", listener=" + listeners;
        currentTemplate();
        return template.inspect(item -> "ShopSlot{" + "itemStack=" + item + state + '}');
    }

    private record Definition(int defaultCost, int maxBuyCount, int groupId, int index,
                              List<ListenerModule> listeners, Set<RuntimeItemTemplates.Template> templates,
                              Set<ListenerModule> declared) {}
}
