package com.ptcrys.fpsmatch.mixin.sound;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import com.ptcrys.fpsmatch.common.sound.FPSMSoundRegister;
import com.ptcrys.fpsmatch.compat.LrtacticalCompat;
import com.ptcrys.fpsmatch.compat.impl.FPSMImpl;
import com.ptcrys.fpsmatch.mixin.accessor.ClientPacketListenerAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ItemPickupSoundMixin {

    @Inject(
            method = "handleTakeItemEntity",
            at = @At(
                     value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),
            cancellable = true)
    private void onPlayPickupSound(ClientboundTakeItemEntityPacket pPacket, CallbackInfo ci) {
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        Entity entity = self.getLevel().getEntity(pPacket.getItemId());

        if (entity instanceof ItemEntity itemEntity) {
            SoundEvent sound;
            if (FPSMImpl.findLrtacticalMod() && LrtacticalCompat.isKnife(itemEntity.getItem().getItem())) {
                sound = FPSMSoundRegister.getKnifePickupSound();
            } else {
                sound = FPSMSoundRegister.getItemPickSound(itemEntity.getItem().getItem());
            }
            if (sound != null) {
                ClientPacketListenerAccessor accessor = (ClientPacketListenerAccessor) self;
                RandomSource random = accessor.getRandom();
                self.getLevel().playLocalSound(
                        entity.getX(), entity.getY(), entity.getZ(),
                        sound, SoundSource.PLAYERS, 0.2F,
                        (random.nextFloat() - random.nextFloat()) * 1.4F + 2.0F, false);
                ci.cancel();
            }
        }
    }
}
