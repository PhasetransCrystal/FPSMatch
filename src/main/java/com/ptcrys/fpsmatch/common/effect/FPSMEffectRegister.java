package com.ptcrys.fpsmatch.common.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.ptcrys.fpsmatch.FPSMatch;

public class FPSMEffectRegister {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, FPSMatch.MODID);
    public static final RegistryObject<MobEffect> FLASH_BLINDNESS = MOB_EFFECTS.register("flash_blindness",
            () -> new FlashBlindnessMobEffect(MobEffectCategory.HARMFUL));
}
