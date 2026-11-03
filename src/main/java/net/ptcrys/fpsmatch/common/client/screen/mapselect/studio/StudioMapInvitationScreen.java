package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import net.ptcrys.fpsmatch.common.client.FPSMClient;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomInvitationS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponents;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioView;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;

import net.minecraft.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/** An invitation remains visible until its matching room reply or a local close. */
public final class StudioMapInvitationScreen extends StudioMapScreen {

    private static final long LOCAL_LIFETIME_SECONDS = 60;
    private final MapRoomInvitationS2CPacket invitation;
    private final long receivedAt = Util.getMillis();
    private long displayedAge = -1;
    private boolean submitted;
    private int bodyScroll;

    public StudioMapInvitationScreen(MapRoomInvitationS2CPacket invitation, Screen parent) {
        super(Component.translatable("gui.fpsm.map_select.invitation.title"), parent, "map-invitation");
        this.invitation = Objects.requireNonNull(invitation, "invitation");
    }

    @Override
    protected String pageSubtitle() {
        return tr("gui.fpsm.studio.invitation.subtitle");
    }

    @Override
    protected void composeBody(Composition content, Bounds body) {
        StudioView view = content.view();
        var details = new ArrayList<StudioView.Element>();
        details.add(view.paragraph("invitation/message", invitation.message().getString(), TextSize.HEADING, Variant.DEFAULT));
        details.add(view.paragraph("invitation/room", invitation.gameType() + " / " + invitation.mapName()));
        details.add(view.paragraph("invitation/effect", tr("gui.fpsm.studio.invitation.effect")));
        details.add(view.paragraph("invitation/lifetime", tr(expiredLocally() ? "gui.fpsm.studio.invitation.expired" : "gui.fpsm.studio.invitation.ttl", elapsedSeconds()),
                TextSize.SMALL, expiredLocally() ? Variant.DANGER : Variant.MUTED));
        if (waiting()) details.add(view.paragraph("invitation/waiting", tr("gui.fpsm.studio.invitation.waiting"), TextSize.BODY, Variant.MUTED));
        var card = view.surface("invitation/card", Kind.SECTION, Style.defaults().padding(Insets.all(12)).gap(10), details);
        bodyScroll = content.scroll("invitation/body-scroll", card, body, 0, bodyScroll, value -> {
            bodyScroll = value;
            requestLayout();
        });
    }

    @Override
    protected List<StudioComponents.Action> footerActions() {
        if (waiting()) return List.of(
                action("invitation/accept", tr("gui.fpsm.studio.invitation.waiting_button"), Icon.CLOCK, Variant.PRIMARY, false, () -> {}),
                action("invitation/cancel-wait", tr("gui.fpsm.studio.invitation.cancel_wait"), Icon.CLOSE, Variant.OUTLINED, true, this::cancelWaiting));
        if (submitted) return List.of(action("invitation/close", tr("gui.done"), Icon.CLOSE, Variant.OUTLINED, true, this::onClose));
        return List.of(
                action("invitation/accept", tr("gui.fpsm.map_select.invitation.accept"), Icon.PLAY, Variant.PRIMARY, !expiredLocally(), this::accept),
                action("invitation/reject", tr("gui.fpsm.map_select.invitation.reject"), Icon.CLOSE, Variant.OUTLINED, true, this::onClose));
    }

    private void accept() {
        if (submitted || expiredLocally()) return;
        if (FPSMMapSelectScreens.acceptInvitation(this, invitation)) {
            // The server consumes this invitation before attempting JOIN. A local
            // timeout does not make the same invitation safe to submit again.
            submitted = true;
            message(tr("gui.fpsm.studio.invitation.submitted"), false);
        }
    }

    private void cancelWaiting() {
        if (!waiting()) return;
        FPSMMapSelectScreens.cancelDetailRequest(this);
        message(tr("gui.fpsm.studio.invitation.cancelled"), false);
    }

    @Override
    protected void onScreenTick() {
        long age = Math.min(LOCAL_LIFETIME_SECONDS, elapsedSeconds());
        if (displayedAge != age) {
            displayedAge = age;
            requestLayout();
        }
        if (FPSMMapSelectScreens.tickPendingRequest(this))
            message(tr("gui.fpsm.studio.invitation.timeout"), true);
    }

    public void applyToast(MapRoomToastS2CPacket packet) {
        message(packet.message().getString(), packet.error());
    }

    private boolean waiting() {
        return FPSMMapSelectScreens.isWaitingForReply(this);
    }

    private long elapsedSeconds() {
        return Math.max(0L, Util.getMillis() - receivedAt) / 1000L;
    }

    private boolean expiredLocally() {
        return elapsedSeconds() >= LOCAL_LIFETIME_SECONDS;
    }

    @Override
    public void onClose() {
        FPSMClient.getGlobalData().clearMapRoomInvitation();
        super.onClose();
    }
}
