package com.ptcrys.fpsmatch.mixin.compat.spectate.lrt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorLrtAttackPackets.C2SLrtAttackPacket;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorSyncNetwork;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import me.xjqsh.lrtactical.api.melee.MeleeAction;
import me.xjqsh.lrtactical.client.input.AttackKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Broadcasts LRTactical melee attacks so spectators can mirror animations.
 */
@Mixin(value = AttackKeys.class, remap = false)
public abstract class MixinLrtAttackKeys {

    @Redirect(
              // triggerAnimation(...) 实际位于 ifPresent 的 lambda 方法体中（编译后为 lambda$xxx$N），不在 tick/onNormalAttack/onSpAttack
              // 本体里
              method = { "lambda$tick$0", "lambda$onNormalAttack$1", "lambda$onSpAttack$2" },
              at = @At(
                       value = "INVOKE",
                       target = "Lcom/tacz/guns/client/renderer/item/AnimateGeoItemRenderer;triggerAnimation(Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;)V"))
    private static void fpsmatch$broadcastAttack(AnimateGeoItemRenderer renderer, ItemStack stack, String animationName) {
        renderer.triggerAnimation(stack, animationName);
        if (!"attack_left".equals(animationName) && !"attack_right".equals(animationName)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || SpectatorView.isSpectatingOther(player)) {
            return;
        }
        MeleeAction action = "attack_right".equals(animationName) ? MeleeAction.RIGHT : MeleeAction.LEFT;
        SpectatorSyncNetwork.CHANNEL.sendToServer(new C2SLrtAttackPacket(action));
    }
}
