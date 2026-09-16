package com.ptcrys.fpsmatch.core.team;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.team.FPSMAddTeamS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.TeamCapabilitiesS2CPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.network.PacketDistributor;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * 服务器端队伍实现类，处理所有服务器端队伍逻辑
 */
@SuppressWarnings("all")
public final class ServerTeam extends BaseTeam {

    public final Map<UUID, PlayerData> players = new HashMap<>();
    private final BaseMap map;
    private boolean enableRounds = false;

    public ServerTeam(BaseMap map, String name, int playerLimit, PlayerTeam playerTeam) {
        super(map.getGameType(), map.getMapName(), name, playerLimit, playerTeam);
        this.map = map;
    }

    @Override
    public boolean join(Player player) {
        if(!super.join(player)) return false;
        if(player.level().isClientSide()) return false;
        String name = player.getScoreboardName();
        if(!getPlayerTeam().getPlayers().contains(name)){
            player.getScoreboard().addPlayerToTeam(name, getPlayerTeam());
        }
        players.put(player.getUUID(), new PlayerData(player,this.enableRounds));
        sync((ServerPlayer) player);
        return true;
    }

    @Override
    public boolean leave(Player player) {
        if(!super.leave(player)) return false;
        if(player.level().isClientSide()) return false;
        if (hasPlayer(player.getUUID())) {
            delPlayer(player.getUUID());
        }
        return true;
    }

    @Override
    public void delPlayer(UUID uuid) {
        players.remove(uuid);
    }

    @Override
    public void resetLiving() {
        players.values().forEach(data -> {
            if (data.isOnline()) {
                data.setLiving(true);
                data.saveRoundData();
            }
        });
    }

    @Override
    public Optional<PlayerData> getPlayerData(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    @Override
    public List<PlayerData> getPlayersData() {
        return new ArrayList<>(players.values());
    }

    @Override
    public List<UUID> getPlayerList() {
        return new ArrayList<>(players.keySet());
    }

    public List<UUID> getOfflinePlayers() {
        List<UUID> offlinePlayers = new ArrayList<>();
        players.values().forEach(data -> {
            if (!data.isOnline()) {
                offlinePlayers.add(data.getOwner());
            }
        });
        return offlinePlayers;
    }

    public List<UUID> getOnlinePlayers() {
        List<UUID> onlinePlayers = new ArrayList<>();
        players.values().forEach(data -> {
            if (data.isOnline()) {
                onlinePlayers.add(data.getOwner());
            }
        });
        return onlinePlayers;
    }

    public List<UUID> getLivingPlayers() {
        List<UUID> uuids = new ArrayList<>();
        players.values().forEach(data -> {
            if (data.isLivingOnServer()) {
                uuids.add(data.getOwner());
            }
        });
        return uuids;
    }

    @Override
    public boolean hasPlayer(UUID uuid) {
        return players.containsKey(uuid);
    }

    @Override
    public int getPlayerCount() {
        return players.size();
    }

    public boolean hasNoOnlinePlayers() {
        if (players.isEmpty()) return true;
        for (PlayerData data : players.values()) {
            if (data.isOnline()) return false;
        }
        return true;
    }

    @Override
    public boolean isEmpty() {
        return players.isEmpty();
    }

    @Override
    public Map<UUID, PlayerData> getPlayers() {
        return players;
    }

    public List<ServerPlayer> getOnline(){
        List<ServerPlayer> onlinePlayers = new ArrayList<>();
        players.values().forEach(data -> {data.getPlayer().ifPresent(onlinePlayers::add);});
        return onlinePlayers;
    }

    @Override
    public void clearAndPutPlayers(Map<UUID, PlayerData> players) {
        this.clearAndPutPlayers(players,(t,d)->{});
    }

    public void clearAndPutPlayers(Map<UUID, PlayerData> players, BiConsumer<ServerTeam,PlayerData> offline) {
        this.players.clear();
        this.players.putAll(players);
        players.values().forEach(data -> {
            data.getPlayer().ifPresentOrElse(onlinePlayer -> {
                onlinePlayer.level().getScoreboard().addPlayerToTeam(onlinePlayer.getScoreboardName(), getPlayerTeam());
            },()->{
                offline.accept(this,data);
            });
        });
    }

    @Override
    public void sendMessage(Component message , boolean onlyLiving) {
        List<UUID> players = onlyLiving ? getLivingPlayers() : getPlayerList();

        players.forEach(uuid -> {
            FPSMCore.getInstance().getPlayerByUUID(uuid).ifPresent(
                    player -> player.displayClientMessage(message, false)
            );
        });
    }

    public void sendMessage(Component message) {
        this.sendMessage(message,false);
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    public void sync(ServerPlayer player) {
        FPSMAddTeamS2CPacket addTeamPacket = FPSMAddTeamS2CPacket.of(this);
        FPSMatch.sendToPlayer(player, addTeamPacket);
        this.syncCapabilities(player);
    }

    public void syncCapabilities(ServerPlayer player) {
        for (TeamCapabilitiesS2CPacket packet : TeamCapabilitiesS2CPacket.toList(this,this.getCapabilityMap().getSynchronizableCapabilityClasses(false))) {
            FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public <T extends TeamCapability & FPSMCapability.CapabilitySynchronizable> void syncCapabilities(Collection<ServerPlayer> players) {
        if(players.isEmpty()) return;
        List<Class<T>> caps = this.getCapabilityMap().getSynchronizableCapabilityClasses(true);
        if(caps.isEmpty()) return;

        for (TeamCapabilitiesS2CPacket packet : TeamCapabilitiesS2CPacket.toList(this,caps)) {
            for (ServerPlayer player : players) {
                FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
        for (Class<T> cap : caps) getCapabilityMap().get(cap).ifPresent(FPSMCapability.CapabilitySynchronizable::onBroadcast);
    }

    public void setEnableRounds(boolean enableRounds) {
        this.enableRounds = enableRounds;
    }

    public BaseMap getMap(){
        return map;
    }

    public void tick() {
        this.getCapabilityMap().tick();
    }
}
