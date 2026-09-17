package com.ptcrys.fpsmatch.common.event;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.eventbus.api.Event;

public class RequestSpectatorOutlinesEvent extends Event {

    private final LocalPlayer player;
    private final MultiPlayerGameMode gameMode;

    public RequestSpectatorOutlinesEvent(LocalPlayer player, MultiPlayerGameMode gameMode) {
        this.player = player;
        this.gameMode = gameMode;
    }

    public LocalPlayer getPlayer() {
        return player;
    }

    public MultiPlayerGameMode getGameMode() {
        return gameMode;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
