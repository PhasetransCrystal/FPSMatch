package net.ptcrys.fpsmatch.common.client.screen.mapselect.modernui;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.FPSMClient;
import net.ptcrys.fpsmatch.common.client.screen.modernui.ModernScreen;
import net.ptcrys.fpsmatch.common.packet.mapselect.*;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class ModernMapInvitationScreen extends ModernScreen {

    private final MapRoomInvitationS2CPacket invitation;

    public ModernMapInvitationScreen(MapRoomInvitationS2CPacket invitation, Screen parent) {
        super(Component.translatable("gui.fpsm.map_select.invitation.title"), parent);
        this.invitation = invitation;
    }

    public Screen parentScreen() {
        return parent;
    }

    @Override
    protected List<Node> content() {
        return dialog("invitation", List.of(title("title", getTitle().getString()), text("message", invitation.message().getString()),
                text("room", invitation.gameType() + " / " + invitation.mapName()),
                row("actions", button("accept", tr("gui.fpsm.map_select.invitation.accept"), true, () -> {
                    FPSMatch.sendToServer(new MapRoomActionC2SPacket(MapRoomActionC2SPacket.Action.ACCEPT_INVITE,
                            invitation.gameType(), invitation.mapName(), null));
                    onClose();
                }), button("reject", tr("gui.fpsm.map_select.invitation.reject"), true, this::onClose))), 320, 145);
    }

    @Override
    public void onClose() {
        FPSMClient.getGlobalData().clearMapRoomInvitation();
        super.onClose();
    }
}
