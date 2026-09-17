package com.ptcrys.fpsmatch.mixin.ammo;

import com.ptcrys.fpsmatch.compat.IPassThroughEntity;
import com.ptcrys.fpsmatch.compat.PassThroughFlagResolver;
import com.tacz.guns.entity.EntityKineticBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class AmmoEntityMixin implements IPassThroughEntity {

    @Unique
    private boolean fpsmatch$passedThroughWall = false;

    @Unique
    private boolean fpsmatch$passedThroughSmoke = false;

    @Unique
    private boolean fpsmatch$scoped = false;

    @Inject(method = "onBulletTick", at = @At(value = "HEAD"))
    private void fpsmatch$checkPassedSmoke(CallbackInfo ci) {
        EntityKineticBullet bullet = (EntityKineticBullet) (Object) this;
        if (bullet.level().isClientSide()) return;
        if (fpsmatch$passedThroughSmoke) return;

        if (PassThroughFlagResolver.isSmokeBetween(bullet, null)) {
            fpsmatch$passedThroughSmoke = true;
        }
    }

    @Override
    public boolean fpsmatch$isWall() {
        return this.fpsmatch$passedThroughWall;
    }

    @Override
    public void fpsmatch$setThroughWall(boolean passed) {
        this.fpsmatch$passedThroughWall = passed;
    }

    @Override
    public boolean fpsmatch$isSmoke() {
        return this.fpsmatch$passedThroughSmoke;
    }

    @Override
    public void fpsmatch$setThroughSmoke(boolean passed) {
        this.fpsmatch$passedThroughSmoke = passed;
    }

    @Override
    public boolean fpsmatch$isScoped() {
        return this.fpsmatch$scoped;
    }

    @Override
    public void fpsmatch$setScoped(boolean scoped) {
        this.fpsmatch$scoped = scoped;
    }
}
