package com.ptcrys.fpsmatch.common.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.ptcrys.fpsmatch.common.attributes.ammo.BulletproofArmorAttribute;
import org.jetbrains.annotations.NotNull;

public class BulletproofArmor extends Item {

    public final boolean hasHelmet;

    public BulletproofArmor(Properties pProperties, boolean hasHelmet) {
        super(pProperties);
        this.hasHelmet = hasHelmet;
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level pLevel, @NotNull Player pPlayer, @NotNull InteractionHand pUsedHand) {
        if (!pLevel.isClientSide) {
            BulletproofArmorAttribute.addPlayer((ServerPlayer) pPlayer, new BulletproofArmorAttribute(hasHelmet));
            ItemStack itemstack = pPlayer.getItemInHand(pUsedHand);
            if (!pPlayer.isCreative()) {
                itemstack.shrink(1);
            }
            return InteractionResultHolder.success(pPlayer.getItemInHand(pUsedHand));
        }
        return super.use(pLevel, pPlayer, pUsedHand);
    }
}
