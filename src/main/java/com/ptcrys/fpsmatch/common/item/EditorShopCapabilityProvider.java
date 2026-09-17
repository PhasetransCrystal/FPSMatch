package com.ptcrys.fpsmatch.common.item;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EditorShopCapabilityProvider implements ICapabilityProvider {

    public static final int ROWS = 5;
    public static final int COLS = 5;
    private final ItemStack shopEditToolStack;
    private final ItemStackHandler itemStackHandler = new ItemStackHandler(ROWS * COLS) {

        @Override
        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);
            save();
        }
    };
    private final LazyOptional<ItemStackHandler> lazyOptional = LazyOptional.of(() -> itemStackHandler);

    public EditorShopCapabilityProvider(ItemStack shopEditToolStack) {
        this.shopEditToolStack = shopEditToolStack;
        CompoundTag tag = shopEditToolStack.getTag();
        if (tag != null && tag.contains("ShopItems")) {
            itemStackHandler.deserializeNBT(tag.getCompound("ShopItems"));
        }
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction direction) {
        return capability == ForgeCapabilities.ITEM_HANDLER ? lazyOptional.cast() : LazyOptional.empty();
    }

    public void save() {
        CompoundTag tag = shopEditToolStack.getOrCreateTag();
        tag.put("ShopItems", itemStackHandler.serializeNBT());
    }
}
