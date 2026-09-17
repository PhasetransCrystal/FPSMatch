package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraftforge.fml.util.thread.EffectiveSide;

import com.ptcrys.fpsmatch.compat.spectate.tacz.SpectatorGunItemMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents server slot updates from overwriting the mirrored spectator item.
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerSpectatorItemMirror {

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$ignoreFakeSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (!EffectiveSide.get().isClient()) {
            return;
        }
        if (!SpectatorGunItemMirror.isActive()) {
            return;
        }
        int slotId = packet.getSlot();
        if (packet.getContainerId() == 0) {
            int fakeSlotIndex = SpectatorGunItemMirror.getFakeSlotIndex();
            int netSlotId = 36 + fakeSlotIndex;
            if (slotId == netSlotId) {
                ci.cancel();
            }
        }
    }
}
