package com.ptcrys.fpsmatch.mixin.sound;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.ptcrys.fpsmatch.common.sound.FPSMSoundRegister;
import com.ptcrys.fpsmatch.compat.LrtacticalCompat;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import com.ptcrys.fpsmatch.compat.gun.GunTabTypeEnum;
import com.ptcrys.fpsmatch.compat.gun.IGunProvider;
import com.ptcrys.fpsmatch.compat.impl.FPSMImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemDropSoundMixin extends Entity {

    public ItemDropSoundMixin(EntityType<?> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    @Shadow
    public abstract ItemStack getItem();

    @Unique
    private boolean fpsmatch$hasPlayedLandSound = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (this.level().isClientSide) return;

        if (this.onGround() && !fpsmatch$hasPlayedLandSound) {
            fpsmatch$playLandSound(this.getItem());
            fpsmatch$hasPlayedLandSound = true;
        } else {
            if (!this.onGround()) fpsmatch$hasPlayedLandSound = false;
        }
    }

    @Unique
    private void fpsmatch$playLandSound(ItemStack itemStack) {
        if (!this.level().isClientSide) {
            IGunProvider provider = GunCompatManager.findProvider(itemStack);
            if (provider.isGun(itemStack)) {
                GunTabTypeEnum type = provider.getGunTabType(itemStack);
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        FPSMSoundRegister.getGunDropSound(type),
                        this.getSoundSource(), 0.3F, 0.8F + this.random.nextFloat() * 0.4F);
            } else {
                SoundEvent sound;
                if (FPSMImpl.findLrtacticalMod() && LrtacticalCompat.isKnife(itemStack.getItem())) {
                    sound = FPSMSoundRegister.getKnifeDropSound();
                } else {
                    sound = FPSMSoundRegister.getItemDropSound(itemStack.getItem());
                }

                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        sound,
                        this.getSoundSource(), 0.3F, 0.8F + this.random.nextFloat() * 0.4F);
            }
        }
    }
}
