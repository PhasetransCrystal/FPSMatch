package net.ptcrys.fpsmatch.editor.common.domain.host.v1201.item;

import net.ptcrys.fpsmatch.editor.common.domain.weapon.TaczGun;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.tacz.guns.api.item.IGun;

/** Optional TACZ integration; constructing a stack does not authenticate weapon resources. */
public final class TaczNativeItems {

    private TaczNativeItems() {}

    public static NativeItemFactory<TaczGun> factory() {
        return new NativeItemFactory<>(TaczGun.class, false, TaczNativeItems::create);
    }

    private static ItemStack create(TaczGun value) {
        // This MC-version backend deliberately uses the loader-neutral registry lookup.
        @SuppressWarnings("deprecation") // Forge deprecates this MC field in favor of its own API.
        Item item = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("tacz", "modern_kinetic_gun"))
                .orElseThrow(() -> new IllegalStateException("Missing registered TACZ item"));
        require(item != Items.AIR && item instanceof IGun, "TACZ item interface");
        IGun access = (IGun) item;
        CompoundTag expected = new CompoundTag();
        expected.put("GunId", StringTag.valueOf(value.gunId().toString()));
        expected.put("GunFireMode", StringTag.valueOf(value.fireMode().name()));
        expected.put("HasBulletInBarrel", ByteTag.valueOf((byte) (value.bulletInBarrel() ? 1 : 0)));
        expected.put("GunCurrentAmmoCount", IntTag.valueOf(value.ammoCount()));
        value.dummyAmmo().ifPresent(amount -> expected.put("DummyAmmo", IntTag.valueOf(amount)));
        value.maxDummyAmmo().ifPresent(amount -> expected.put("MaxDummyAmmo", IntTag.valueOf(amount)));
        value.displayId().ifPresent(id -> expected.put("GunDisplayId", StringTag.valueOf(id.toString())));
        ItemStack stack = new ItemStack(item, value.count());
        stack.setTag(expected.copy());
        verifyStack(stack, item, value.count(), expected);
        require(access.getGunId(stack).toString().equals(value.gunId().toString()), "gun identity getter");
        require(access.getGunDisplayId(stack).toString()
                .equals(value.displayId().map(Object::toString).orElse("tacz:default")), "gun display getter");
        require(access.getFireMode(stack).name().equals(value.fireMode().name()), "fire mode getter");
        require(access.hasBulletInBarrel(stack) == value.bulletInBarrel(), "barrel getter");
        require(access.getCurrentAmmoCount(stack) == value.ammoCount(), "ammo getter");
        require(access.useDummyAmmo(stack) == value.dummyAmmo().isPresent(), "dummy presence getter");
        require(access.getDummyAmmoAmount(stack) == value.dummyAmmo().orElse(0), "dummy amount getter");
        require(access.hasMaxDummyAmmo(stack) == value.maxDummyAmmo().isPresent(), "max dummy presence getter");
        require(access.getMaxDummyAmmoAmount(stack) == value.maxDummyAmmo().orElse(0), "max dummy amount getter");
        verifyStack(stack, item, value.count(), expected);
        return stack;
    }

    private static void verifyStack(ItemStack stack, Item item, int count, CompoundTag expected) {
        require(!stack.isEmpty() && stack.getItem() == item && stack.getCount() == count && expected.equals(stack.getTag()), "item, count and exact tag");
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new IllegalStateException("TACZ materialization mismatch: " + field);
    }
}
