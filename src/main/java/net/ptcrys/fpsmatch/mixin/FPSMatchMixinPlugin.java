package net.ptcrys.fpsmatch.mixin;

import net.ptcrys.fpsmatch.compat.impl.FPSMImpl;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Conditional mixin loader for FPSMatch.
 * Only loads compatibility mixins when target mods are present.
 */
public class FPSMatchMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean taczTweaksLoaded = FPSMImpl.findTaczTweaks();
        boolean taczLoaded = FPSMImpl.findTacz();
        if (mixinClassName.contains("compat.grenades.")) {
            return FPSMImpl.findCounterStrikeGrenadesMod();
        }

        switch (mixinClassName) {
            case "net.ptcrys.fpsmatch.mixin.ammo.DefaultAmmoMixin" -> {
                return taczLoaded && !taczTweaksLoaded;
            }
            case "net.ptcrys.fpsmatch.mixin.ammo.TweakAmmoMixin" -> {
                return taczLoaded && taczTweaksLoaded;
            }
            case "net.ptcrys.fpsmatch.mixin.combat.DeadOrDyingMixin" -> {
                return taczLoaded;
            }
        }
        if (mixinClassName.contains("compat.spectate.lrt") || mixinClassName.contains("compat.lrt.")) {
            return FPSMImpl.findLrtacticalMod();
        }
        if (mixinClassName.contains("compat.spectate.tacz") || mixinClassName.contains("mixin.ammo.")) {
            return taczLoaded;
        }
        if (mixinClassName.contains("render.HeadShotAabbMixin")) {
            return taczLoaded;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
