package com.ptcrys.fpsmatch.mixin.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import com.ptcrys.fpsmatch.common.item.BaseThrowAbleItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nullable;

/**
 * 手持投掷物时，把鼠标左键的「攻击」替换为「使用/蓄力」。
 * <p>
 * {@link BaseThrowAbleItem} 的蓄力投掷需要左键也能进入物品使用状态(use -> startUsingItem)，
 * 否则左键会被原版当作近战攻击。这里只重定向 {@code startAttack()Z} 这一处调用，
 * 其余按键逻辑(视角切换/物品栏/聊天/持续攻击等)完全交由原版 {@code handleKeybinds} 处理，
 * 避免像旧实现那样整体 cancel 后整段复刻原版方法造成版本漂移与其它 mod 注入冲突。
 * </p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftKeybindingMixin {

    @Shadow
    @Nullable
    public LocalPlayer player;

    @Shadow
    protected abstract void startUseItem();

    @Shadow
    protected abstract boolean startAttack();

    /**
     * 只有当鼠标左键触发 {@code startAttack()} 且主手/副手持投掷物时才改为触发蓄力使用。
     */
    @Redirect(
              method = "handleKeybinds",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startAttack()Z"))
    private boolean fpsmatch$throwableLeftClickCharges(Minecraft instance) {
        // 手持投掷物时左键一律进入蓄力使用，绝不做近战攻击（即使是已蓄力中再按左键也不攻击）
        if (this.player != null && this.isHoldingThrowable()) {
            this.startUseItem();
            return false;
        }
        return this.startAttack();
    }

    private boolean isHoldingThrowable() {
        ItemStack main = this.player.getMainHandItem();
        if (main.getItem() instanceof BaseThrowAbleItem) {
            return true;
        }
        ItemStack offhand = this.player.getOffhandItem();
        return offhand.getItem() instanceof BaseThrowAbleItem;
    }
}
