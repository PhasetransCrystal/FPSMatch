package net.ptcrys.fpsmatch.editor.common.domain.host.v1201.item;

import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItem;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

/** Materializes a basic value already authenticated by its registered pure codec. */
public final class BasicNativeItems {

    private BasicNativeItems() {}

    public static NativeItemFactory<BasicItem> factory() {
        return new NativeItemFactory<>(BasicItem.class, false, BasicNativeItems::create);
    }

    private static ItemStack create(BasicItem value) {
        // This MC-version backend deliberately uses the loader-neutral registry lookup.
        @SuppressWarnings("deprecation") // Forge deprecates this MC field in favor of its own API.
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(
                value.itemId().namespace(), value.itemId().path()))
                .orElseThrow(() -> new IllegalStateException("Missing registered basic item"));
        if (item == Items.AIR) throw new IllegalStateException("AIR basic item");
        CompoundTag expected = null;
        if (value.damage().isPresent()) {
            expected = new CompoundTag();
            expected.put("Damage", IntTag.valueOf(value.damage().getAsInt()));
        }
        ItemStack stack = new ItemStack(item, value.count());
        stack.setTag(expected == null ? null : expected.copy());
        if (stack.isEmpty() || stack.getItem() != item || stack.getCount() != value.count() || !Objects.equals(expected, stack.getTag())) {
            throw new IllegalStateException("Basic item materialization changed item, count or tag");
        }
        return stack;
    }
}
