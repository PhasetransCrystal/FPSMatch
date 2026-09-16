package com.ptcrys.fpsmatch.common.client.net;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.client.data.FPSMClientGlobalData;
import com.ptcrys.fpsmatch.common.client.data.RenderableArea;
import com.ptcrys.fpsmatch.common.client.data.RenderablePoint;
import com.ptcrys.fpsmatch.common.client.screen.MapCreatorToolScreen;
import com.ptcrys.fpsmatch.common.client.screen.MatchConfigToolScreen;
import com.ptcrys.fpsmatch.common.client.screen.SpawnPointToolScreen;
import com.ptcrys.fpsmatch.common.client.screen.shop.modernui.ModernEditShopSlotScreen;
import com.ptcrys.fpsmatch.common.client.screen.shop.modernui.ModernEditorShopScreen;
import com.ptcrys.fpsmatch.common.client.screen.shop.modernui.ModernShopConfigToolScreen;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui.*;
import com.ptcrys.fpsmatch.common.packet.AddAreaDataS2CPacket;
import com.ptcrys.fpsmatch.common.packet.AddPointDataS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMInventorySelectedS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMSoundPlayS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMatchGameTypeS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMusicPlayS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMusicStopS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMatchRespawnS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMatchStatsResetS2CPacket;
import com.ptcrys.fpsmatch.common.packet.OpenMapCreatorToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.OpenMatchConfigToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.OpenSpawnPointToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.RemoveDebugDataByPrefixS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetailS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomInvitationS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomReadyStateS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapImportSourcesS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionAccessS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionSnapshotS2CPacket;
import com.ptcrys.fpsmatch.common.client.music.FPSClientMusicManager;
import com.ptcrys.fpsmatch.common.client.shop.ShopActionResultListener;
import com.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import com.ptcrys.fpsmatch.common.client.spec.SpectateState;
import com.ptcrys.fpsmatch.common.client.spec.SpectatorCameraController;
import com.ptcrys.fpsmatch.common.packet.shop.OpenShopConfigToolScreenS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.ShopActionResultS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.ShopDataSlotS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.ShopMoneyS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.FPSMAddTeamS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.TeamCapabilitiesS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.TeamManageResultS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.TeamPlayerLeaveS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.TeamPlayerStatsS2CPacket;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.team.ClientTeam;
import com.ptcrys.fpsmatch.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Optional;

public final class FPSMClientPacketHandlers {
    private FPSMClientPacketHandlers() {
    }

    public static void handleOpenMapCreatorToolScreen(OpenMapCreatorToolScreenS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MapCreatorToolScreen screen) {
            screen.applyData(packet);
        } else {
            minecraft.setScreen(new MapCreatorToolScreen(packet));
        }
    }

    public static void handleOpenMatchConfigToolScreen(OpenMatchConfigToolScreenS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MatchConfigToolScreen screen) {
            screen.applyData(packet);
        } else {
            minecraft.setScreen(new MatchConfigToolScreen(packet));
        }
    }

    public static void handleOpenSpawnPointToolScreen(OpenSpawnPointToolScreenS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof SpawnPointToolScreen screen) {
            screen.applyData(packet);
        } else {
            minecraft.setScreen(new SpawnPointToolScreen(packet));
        }
    }

    public static void handleOpenShopConfigToolScreen(OpenShopConfigToolScreenS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ModernShopConfigToolScreen screen) {
            screen.applyData(packet);
        } else {
            minecraft.setScreen(new ModernShopConfigToolScreen(packet));
        }
    }

    public static void handleRespawn(FPSMatchRespawnS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            com.ptcrys.fpsmatch.common.client.camera.CameraDirector.playerRespawn();
            mc.setScreen(null);
            mc.player.respawn();
        }
    }

    public static void handleInventorySelected(FPSMInventorySelectedS2CPacket packet) {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null) {
            localPlayer.getInventory().selected = packet.selected();
        }
    }

    public static void handleAddAreaData(AddAreaDataS2CPacket packet) {
        FPSMClient.getGlobalData().getDebugData().upsertRenderableArea(packet.key(), new RenderableArea(packet.key(), packet.name(), packet.color(), packet.areaData()));
    }

    public static void handleAddPointData(AddPointDataS2CPacket packet) {
        FPSMClient.getGlobalData().getDebugData().upsertRenderablePoint(packet.key(), new RenderablePoint(packet.key(), packet.name(), packet.color(), packet.position()));
    }

    public static void handleGameType(FPSMatchGameTypeS2CPacket packet) {
        FPSMClientGlobalData data = FPSMClient.getGlobalData();
        if (!data.getCurrentMap().equals(packet.getMapName())
                || !data.getCurrentGameType().equals(packet.getGameType())) {
            resetCameraToPlayer(Minecraft.getInstance());
        }
        data.setCurrentGameType(packet.getGameType());
        data.setCurrentMap(packet.getMapName());
        data.setTeamGlow(packet.isTeamGlow());
        data.setEnemyGlow(packet.isEnemyGlow());
    }

    public static void handleStatsReset(FPSMatchStatsResetS2CPacket packet) {
        resetCameraToPlayer(Minecraft.getInstance());
        FPSMClient.reset();
    }

    private static void resetCameraToPlayer(Minecraft mc) {
        com.ptcrys.fpsmatch.common.client.camera.CameraDirector.reset(
                com.ptcrys.fpsmatch.common.camera.CameraEndReason.MATCH_RESET);
    }

    public static void handleSoundPlay(FPSMSoundPlayS2CPacket packet) {
        FPSClientMusicManager.playSound(packet.getLocation());
    }

    public static void handleMusicPlay(FPSMusicPlayS2CPacket packet) {
        FPSClientMusicManager.stopMusic();
        FPSClientMusicManager.playMusic(packet.getLocation());
    }

    public static void handleMusicStop(FPSMusicStopS2CPacket packet) {
        FPSClientMusicManager.stopMusic();
    }

    public static void handleRemoveDebugDataByPrefix(RemoveDebugDataByPrefixS2CPacket packet) {
        FPSMClient.getGlobalData().getDebugData().removeByPrefix(packet.prefix());
    }

    public static void handleShopDataSlot(ShopDataSlotS2CPacket packet) {
        var currentSlot = FPSMClient.getGlobalData().getSlotData(packet.type.name(), packet.index);
        if (currentSlot != null) {
            currentSlot.setItemStack(packet.itemStack);
            currentSlot.setCost(packet.cost);
            currentSlot.setBoughtCount(packet.boughtCount);
            currentSlot.setLock(packet.locked);
        } else {
            FPSMatch.LOGGER.error("Failed to update slot data for {} at index {}", packet.type.name(), packet.index);
        }
    }

    public static void handleShopActionResult(ShopActionResultS2CPacket packet) {
        ShopActionResultListener.dispatch(packet);
    }

    public static void handleShopMoney(ShopMoneyS2CPacket packet) {
        if (Minecraft.getInstance().player != null) {
            FPSMClient.getGlobalData().setPlayerMoney(packet.owner(), packet.money());
        }
    }

    public static void handleAddTeam(FPSMAddTeamS2CPacket packet) {
        FPSMClientGlobalData data = FPSMClient.getGlobalData();
        Optional<ClientTeam> existingTeam = data.getTeamByName(packet.teamData().name());
        if (existingTeam.filter(team -> existingTeamMatchesPacket(team, packet)).isPresent()) return;
        ClientTeam team = new ClientTeam(packet.gameType(), packet.mapName(), packet.teamData());
        team.setColor(RenderUtil.color(packet.color()));
        data.addTeam(team);
    }

    private static boolean existingTeamMatchesPacket(ClientTeam team, FPSMAddTeamS2CPacket packet) {
        return team.gameType.equals(packet.gameType()) && team.mapName.equals(packet.mapName());
    }

    public static void handleTeamCapabilities(TeamCapabilitiesS2CPacket packet) {
        FPSMClient.getGlobalData().getTeamByName(packet.teamName()).ifPresent(team -> {
            team.getCapabilityMap().deserializeCapability(packet.capName(), packet.capabilityData());
        });
        packet.capabilityData().release();
    }

    public static void handleTeamPlayerLeave(TeamPlayerLeaveS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        FPSMClient.getGlobalData().removePlayer(packet.player());

        // Leaving a team ends any active spectator view.  The target entity can
        // still exist on the client, so merely clearing team data leaves the
        // camera attached to the previous game's player until another target
        // packet arrives.
        if (mc.player != null && packet.player().equals(mc.player.getUUID())) {
            resetCameraToPlayer(mc);
        }
    }

    public static void handleTeamPlayerStats(TeamPlayerStatsS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        FPSMClientGlobalData global = FPSMClient.getGlobalData();
        if (packet.getUuid().equals(mc.player.getUUID()) && !FPSMClient.getGlobalData().isCurrentTeam(packet.getTeamName())) {
            global.setCurrentTeam(packet.getTeamName());
        }

        Optional<PlayerData> opt = FPSMClient.getGlobalData().getPlayerData(packet.getTeamName(), packet.getUuid());
        PlayerData data = opt.orElse(new PlayerData(packet.getUuid(), packet.getPlayerName(), false));
        data.setScores(packet.getScores());
        data.setKills(packet.getKills());
        data.setDeaths(packet.getDeaths());
        data.setAssists(packet.getAssists());
        data.setDamage(packet.getDamage());
        data.setUtilityDamage(packet.getUtilityDamage());
        data.setFlashedEnemies(packet.getFlashedEnemies());
        data.setTempKills(packet.getRoundKills());
        data.setMvpCount(packet.getMvpCount());
        data.setLiving(packet.isLiving());
        data.setHeadshotKills(packet.getHeadshotKills());
        data.setHealthPercent(packet.getHealthPercent());
        FPSMClient.getGlobalData().updatePlayerTeamData(packet.getTeamName(), packet.getUuid(), data);
    }

    public static void handleMapSelectionSnapshot(MapSelectionSnapshotS2CPacket packet) {
        FPSMClient.getGlobalData().setMapSelectionSnapshot(packet);
        FPSMClient.getGlobalData().setMapSelectionButtonVisible(packet.viewerOp() || packet.nonOpButtonEnabled());
        if (packet.passive()) {
            FPSMMapSelectScreens.applySelectionIfOpen(packet);
        } else {
            FPSMMapSelectScreens.openSelection(packet);
        }
    }

    public static void handleMapSelectionAccess(MapSelectionAccessS2CPacket packet) {
        FPSMClient.getGlobalData().setMapSelectionButtonVisible(packet.visible());
    }

    public static void handleMapRoomDetail(MapRoomDetailS2CPacket packet) {
        FPSMClient.getGlobalData().setMapRoomDetail(packet.detail());
        if (packet.passive()) {
            FPSMMapSelectScreens.applyDetailIfOpen(packet.detail());
        } else {
            FPSMMapSelectScreens.openDetail(packet);
        }
    }

    public static void handleMapRoomReadyState(MapRoomReadyStateS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ModernMapRoomScreen screen) {
            screen.applyReadyState(packet.gameType(), packet.mapName(), packet.countdownSeconds(), packet.readyPlayers());
        }
    }

    public static void handleMapImportSources(MapImportSourcesS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ModernMapRoomScreen screen && screen.acceptsSources(packet)) {
            screen.applySources(packet);
        }
    }

    public static void handleMapRoomToast(MapRoomToastS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        String toastKey = packet.message().getContents() instanceof TranslatableContents contents
                ? contents.getKey()
                : "";
        boolean isShopSaveToast = toastKey.startsWith("gui.fpsm.shop_editor.save.");
        boolean isShopOpenToast = toastKey.startsWith("gui.fpsm.shop_editor.open.");
        boolean isMapSettingToast = toastKey.equals("gui.fpsm.map_select.action.no_permission")
                || toastKey.equals("gui.fpsm.map_select.action.map_not_found")
                || toastKey.equals("gui.fpsm.map_select.action.setting.invalid")
                || toastKey.equals("gui.fpsm.map_select.action.setting.not_found");
        boolean isRegionToast = toastKey.startsWith("gui.fpsm.map_regions.action.")
                || toastKey.equals("gui.fpsm.map_select.action.no_permission")
                || toastKey.equals("gui.fpsm.map_select.action.map_not_found");
        boolean isMapImportToast = toastKey.startsWith("gui.fpsm.map_import.")
                || toastKey.equals("gui.fpsm.map_select.action.no_permission")
                || toastKey.equals("gui.fpsm.map_select.action.map_not_found");
        if (isShopSaveToast && minecraft.screen instanceof ModernEditShopSlotScreen screen
                && screen.isSaveResultRelevant()) {
            screen.applySaveResult(packet);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(packet.message(), packet.error());
            }
            return;
        }
        if (isShopOpenToast && minecraft.screen instanceof ModernEditorShopScreen screen
                && screen.isSlotOpenPending()) {
            screen.applySlotOpenFailure(packet.message());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(packet.message(), packet.error());
            }
            return;
        }
        if (isShopOpenToast && minecraft.screen instanceof ModernShopConfigToolScreen screen
                && screen.isEditorOpenPending()) {
            screen.applyEditorOpenFailure(packet.message());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(packet.message(), packet.error());
            }
            return;
        }
        if (isShopOpenToast && minecraft.screen instanceof ModernEditShopSlotScreen screen
                && screen.isReturnPending()) {
            screen.applyReturnFailure(packet.message());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(packet.message(), packet.error());
            }
            return;
        }
        if (minecraft.screen instanceof ModernMapRoomScreen room) room.applyToast(packet);
        FPSMClient.getGlobalData().setMapRoomToast(packet);
        if (minecraft.screen instanceof ModernMapSelectionScreen screen) {
            screen.applyToast();
        }
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(packet.message(), packet.error());
        }
    }

    public static void handleMapRoomInvitation(MapRoomInvitationS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        FPSMClient.getGlobalData().setMapRoomInvitation(packet.gameType(), packet.mapName(), packet.message());
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(packet.message(), false);
        }
        FPSMMapSelectScreens.openInvitation(packet);
    }

    public static void handleTeamManageResult(TeamManageResultS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(packet.message(), false);
        }
    }
}
