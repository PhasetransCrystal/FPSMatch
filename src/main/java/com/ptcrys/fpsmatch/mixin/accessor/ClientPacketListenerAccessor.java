package com.ptcrys.fpsmatch.mixin.accessor;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.RandomSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {

    @Accessor("random")
    RandomSource getRandom();
}
