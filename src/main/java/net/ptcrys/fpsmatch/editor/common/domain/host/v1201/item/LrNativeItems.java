package net.ptcrys.fpsmatch.editor.common.domain.host.v1201.item;

import net.ptcrys.fpsmatch.editor.common.domain.weapon.LrMelee;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import me.xjqsh.lrtactical.api.item.IMeleeWeapon;

/** Optional LR integration; constructing a stack does not authenticate weapon resources. */
public final class LrNativeItems {

    private LrNativeItems() {}

    public static NativeItemFactory<LrMelee> factory() {
        return new NativeItemFactory<>(LrMelee.class, false, LrNativeItems::create);
    }

    private static ItemStack create(LrMelee value) {
        // This MC-version backend deliberately uses the loader-neutral registry lookup.
        @SuppressWarnings("deprecation") // Forge deprecates this MC field in favor of its own API.
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("lrtactical", "melee"))
                .orElseThrow(() -> new IllegalStateException("Missing registered LR melee item"));
        require(item != Items.AIR && item instanceof IMeleeWeapon, "LR item interface");
        IMeleeWeapon access = (IMeleeWeapon) item;
        CompoundTag expected = new CompoundTag();
        expected.put("MeleeWeaponId", StringTag.valueOf(value.weaponId().toString()));
        expected.put("Damage", IntTag.valueOf(value.damage()));
        value.displayId().ifPresent(id -> expected.put("DisplayId", StringTag.valueOf(id.toString())));
        ItemStack stack = new ItemStack(item, value.count());
        stack.setTag(expected.copy());
        verifyStack(stack, item, value.count(), expected);
        require(access.getId(stack).toString().equals(value.weaponId().toString()), "weapon identity getter");
        require(access.getDisplayId(stack).toString()
                .equals(value.displayId().orElse(value.weaponId()).toString()), "weapon display getter");
        require(stack.getDamageValue() == value.damage(), "damage getter");
        verifyStack(stack, item, value.count(), expected);
        return stack;
    }

    private static void verifyStack(ItemStack stack, Item item, int count, CompoundTag expected) {
        require(!stack.isEmpty() && stack.getItem() == item && stack.getCount() == count && expected.equals(stack.getTag()), "item, count and exact tag");
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new IllegalStateException("LR materialization mismatch: " + field);
    }
}
