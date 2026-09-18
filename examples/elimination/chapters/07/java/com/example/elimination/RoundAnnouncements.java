package com.example.elimination;

import net.ptcrys.fpsmatch.core.capability.map.MapCapability;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.minecraft.network.chat.Component;

public final class RoundAnnouncements extends MapCapability {
    private int round;

    public RoundAnnouncements(BaseMap map) { super(map); }

    public void announceNextRound() {
        round++;
        Component text = Component.translatable("message.elimination.round", round);
        map.getMapTeams().getOnlineWithSpec().forEach(player -> player.sendSystemMessage(text));
    }

    @Override
    public void reset() { round = 0; }
}
