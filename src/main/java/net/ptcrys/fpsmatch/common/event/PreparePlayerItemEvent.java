package net.ptcrys.fpsmatch.common.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class PreparePlayerItemEvent extends Event {

    private final ServerPlayer player;
    private final ItemStack stack;

    public PreparePlayerItemEvent(ServerPlayer player, ItemStack stack) {
        this.player = Objects.requireNonNull(player);
        this.stack = Objects.requireNonNull(stack);
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public ItemStack getStack() {
        return stack;
    }
}
