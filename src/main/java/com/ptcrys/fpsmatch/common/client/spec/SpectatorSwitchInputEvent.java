package com.ptcrys.fpsmatch.common.client.spec;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

public final class SpectatorSwitchInputEvent extends Event {

    private final ServerPlayer player;
    private final SpectatorSwitchDirection direction;

    public SpectatorSwitchInputEvent(ServerPlayer player, SpectatorSwitchDirection direction) {
        this.player = player;
        this.direction = direction;
    }

    public ServerPlayer player() {
        return player;
    }

    public SpectatorSwitchDirection direction() {
        return direction;
    }
}
