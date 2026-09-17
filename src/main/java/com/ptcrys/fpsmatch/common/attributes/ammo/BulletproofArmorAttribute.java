package com.ptcrys.fpsmatch.common.attributes.ammo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.attribute.BulletproofArmorAttributeS2CPacket;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BulletproofArmorAttribute {

    public static final BulletproofArmorAttribute EMPTY = new BulletproofArmorAttribute(false, 0);
    private static final Map<UUID, BulletproofArmorAttribute> PLAYER_ATTRIBUTES = new ConcurrentHashMap<>();
    private boolean hasHelmet;
    private int durability;

    public BulletproofArmorAttribute(boolean hasHelmet) {
        this(hasHelmet, 100);
    }

    public BulletproofArmorAttribute(boolean hasHelmet, int durability) {
        this.hasHelmet = hasHelmet;
        this.durability = durability;
    }

    public static Optional<BulletproofArmorAttribute> getInstance(Player player) {
        return getInstance(player.getUUID());
    }

    public static Optional<BulletproofArmorAttribute> getInstance(UUID player) {
        return Optional.ofNullable(PLAYER_ATTRIBUTES.getOrDefault(player, null));
    }

    public boolean hasHelmet() {
        return hasHelmet;
    }

    public void setHasHelmet(boolean hasHelmet) {
        this.hasHelmet = hasHelmet;
    }

    public int getDurability() {
        return durability;
    }

    public void setDurability(int durability) {
        this.durability = Math.max(0, durability);
    }

    public void reduceDurability(int amount) {
        setDurability(this.durability - amount);
    }

    public static void removePlayer(ServerPlayer player) {
        removePlayer(player.getUUID());
        sync(player, EMPTY);
    }

    public static void removePlayer(UUID player) {
        PLAYER_ATTRIBUTES.remove(player);
    }

    public static void sync(ServerPlayer player, BulletproofArmorAttribute attribute) {
        FPSMatch.sendToPlayer(player, new BulletproofArmorAttributeS2CPacket(attribute));
    }

    public static void addPlayer(ServerPlayer player, BulletproofArmorAttribute attribute) {
        addPlayer(player.getUUID(), attribute);
        sync(player, attribute);
    }

    public static void addPlayer(UUID player, BulletproofArmorAttribute attribute) {
        PLAYER_ATTRIBUTES.put(player, attribute);
    }

    public static class Client {

        public static boolean bpAttributeHasHelmet = false;
        public static int bpAttributeDurability = 0;
    }
}
