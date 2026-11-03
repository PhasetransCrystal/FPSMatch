package net.ptcrys.fpsmatch.common.client.screen;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.studio.StudioMapScreen;
import net.ptcrys.fpsmatch.common.item.MatchConfigTool;
import net.ptcrys.fpsmatch.common.packet.MatchConfigToolActionC2SPacket;
import net.ptcrys.fpsmatch.common.packet.OpenMatchConfigToolScreenS2CPacket;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingInfo;
import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchClient;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponents;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioView;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static net.ptcrys.fpsmatch.common.client.screen.MatchConfigToolState.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/** A read-only runtime snapshot. Authoring continues in the existing workbench. */
@Mod.EventBusSubscriber(modid = FPSMatch.MODID, value = Dist.CLIENT)
public final class MatchConfigToolScreen extends StudioMapScreen {

    private static final AtomicLong SESSIONS = new AtomicLong(System.currentTimeMillis());
    private static final String PREFIX = "gui.fpsm.match_config.studio.";
    private final MatchConfigToolState<OpenMatchConfigToolScreenS2CPacket> state;
    private String search = "";
    private int bodyScroll;
    private boolean choosingMap;

    private MatchConfigToolScreen(Connection connection) {
        super(Component.translatable("gui.fpsm.match_config.title"), null, "match-config");
        state = new MatchConfigToolState<>(connection, SESSIONS.incrementAndGet());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLocalRightClick(PlayerInteractEvent.RightClickItem event) {
        Minecraft client = Minecraft.getInstance();
        if (!event.getLevel().isClientSide || event.getEntity() != client.player || event.getHand() != InteractionHand.MAIN_HAND || event.isCanceled() || !(event.getItemStack().getItem() instanceof MatchConfigTool) || client.screen != null || client.getConnection() == null) return;
        // This main-hand interaction is handled; do not continue into an off-hand use.
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        MatchConfigToolScreen screen = new MatchConfigToolScreen(client.getConnection().getConnection());
        // Publish local intent before sending: even an immediate reply must find this exact page.
        client.setScreen(screen);
        screen.begin(Operation.OPEN, Target.NONE);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private Object currentConnection() {
        Connection connection = minecraft == null || minecraft.getConnection() == null ? null : minecraft.getConnection().getConnection();
        return connection != null && connection.isConnected() ? connection : null;
    }

    private void begin(Operation operation, Target target) {
        if (state.tick(currentConnection(), now())) showState();
        state.begin(operation, target, now()).ifPresent(this::send);
    }

    private void send(Ticket ticket) {
        showState();
        FPSMatch.sendToServer(new MatchConfigToolActionC2SPacket(
                MatchConfigToolActionC2SPacket.Action.valueOf(ticket.operation().name()),
                ticket.target().gameType(), ticket.target().mapName(), "", "", ticket.session(), ticket.request()));
    }

    private void retry() {
        if (state.tick(currentConnection(), now())) showState();
        state.retry(now()).ifPresent(this::send);
    }

    public void applyData(OpenMatchConfigToolScreenS2CPacket packet, Connection source) {
        if (state.tick(currentConnection(), now())) showState();
        if (packet.action() == MatchConfigToolActionC2SPacket.Action.SET_SETTING) return;
        Target previous = state.selected();
        if (!state.complete(source, packet.session(), packet.request(), Operation.valueOf(packet.action().name()),
                new Target(packet.selectedType(), packet.selectedMap()), packet, packet.success() ? "" : (packet.notice().isBlank() ? "failed" : packet.notice())))
            return;
        if (packet.success() && !previous.equals(state.selected())) bodyScroll = 0;
        showState();
        if (packet.success() && !packet.notice().isBlank()) message(tr(PREFIX + packet.notice()), true);
    }

    private void showState() {
        if (state.pending() != null) {
            String key = switch (state.pending().operation()) {
                case OPEN -> "loading";
                case SELECT -> "selecting";
                case REFRESH -> "refreshing";
            };
            message(tr(PREFIX + key), false);
        } else if (!state.failure().isEmpty()) {
            String key = switch (state.failure()) {
                case "timeout", "disconnected", "cancelled", "removed", "no_tool" -> state.failure();
                default -> "failed";
            };
            String text = tr(PREFIX + key);
            if (state.snapshot() != null && !key.equals("failed") && !key.equals("cancelled")) text += " " + tr(PREFIX + "failed");
            message(text, true);
        } else message("", false);
    }

    @Override
    protected void onScreenTick() {
        if (state.tick(currentConnection(), now())) showState();
    }

    @Override
    protected void onScreenRemoved() {
        state.close();
    }

    @Override
    public void onClose() {
        if (choosingMap) {
            choosingMap = false;
            requestLayout();
            return;
        }
        state.close();
        if (minecraft != null) minecraft.setScreen(null);
    }

    private void openWorkbench() {
        state.close();
        if (minecraft != null) minecraft.setScreen(null);
        WorkbenchClient.openWorkbench();
    }

    @Override
    protected String backLabel() {
        return tr("gui.done");
    }

    @Override
    protected String pageSubtitle() {
        Target selected = state.selected();
        return selected.equals(Target.NONE) ? tr(PREFIX + "choose_map") : selected.gameType() + " / " + selected.mapName();
    }

    @Override
    protected StudioView.Element topControls(StudioView view) {
        boolean idle = state.canRequest();
        var data = state.snapshot();
        var actions = new ArrayList<StudioComponents.Action>();
        actions.add(action("config/maps", tr(PREFIX + "choose_map"), Icon.MAP, Variant.OUTLINED,
                idle && data != null && !data.maps().isEmpty(), () -> {
                    choosingMap = true;
                    resetOverlayScroll();
                    requestLayout();
                }));
        actions.add(action("config/refresh", tr("gui.fpsm.match_config.refresh"), Icon.REDO, Variant.OUTLINED,
                idle && data != null, () -> begin(Operation.REFRESH, state.selected())));
        if (state.pending() != null) actions.add(action("config/cancel", tr("gui.cancel"), Icon.CLOSE, Variant.DEFAULT, true,
                () -> {
                    state.cancel();
                    showState();
                }));
        else if (!state.failure().isEmpty()) actions.add(action("config/retry", tr(PREFIX + "retry"), Icon.REDO, Variant.DEFAULT, idle, this::retry));
        return buttons(view, "config/controls", actions);
    }

    @Override
    protected void composeBody(Composition content, Bounds body) {
        var view = content.view();
        var entries = new ArrayList<StudioView.Element>();
        entries.add(view.paragraph("config/read-only", tr(PREFIX + "read_only"), TextSize.SMALL, Variant.MUTED));
        entries.add(view.button(action("config/workbench", tr(PREFIX + "workbench"), Icon.FOLDER, Variant.OUTLINED, true, this::openWorkbench)));
        var data = state.snapshot();
        if (data == null) {
            entries.add(view.paragraph("config/initial", tr(PREFIX + (state.pending() != null ? "loading" : "choose_map"))));
        } else if (data.maps().isEmpty()) {
            entries.add(view.paragraph("config/no-maps", tr("gui.fpsm.match_config.empty_maps")));
        } else if (state.selected().equals(Target.NONE)) {
            entries.add(view.paragraph("config/no-selection", tr(PREFIX + "choose_map")));
        } else {
            entries.add(view.input(new StudioView.Input("config/search", tr("gui.fpsm.map_select.settings.search"), search, true, false, false, "", null,
                    value -> {
                        search = value;
                        bodyScroll = 0;
                        requestLayout();
                    }, null)));
            if (data.settings().isEmpty()) entries.add(view.paragraph("config/no-settings", tr("gui.fpsm.match_config.empty_settings")));
            else composeSettings(view, entries, data.settings());
        }
        bodyScroll = content.scroll("config/body", view.column("config/entries", Style.defaults().gap(10), entries), body, 0, bodyScroll,
                value -> {
                    bodyScroll = value;
                    requestLayout();
                });
    }

    private void composeSettings(StudioView view, List<StudioView.Element> entries, List<MapRoomSettingInfo> settings) {
        String query = search.strip().toLowerCase(Locale.ROOT);
        String category = null;
        int count = 0;
        for (var setting : settings.stream().sorted(Comparator.comparing(MapRoomSettingInfo::category).thenComparing(MapRoomSettingInfo::name)).toList()) {
            if (!(tr(setting.translationKey()) + " " + setting.name() + " " + tr(setting.categoryTranslationKey())).toLowerCase(Locale.ROOT).contains(query)) continue;
            count++;
            if (!setting.category().equals(category)) {
                category = setting.category();
                entries.add(view.paragraph("config/category/" + part(category), tr(setting.categoryTranslationKey()), TextSize.HEADING, Variant.MUTED));
            }
            String id = "config/value/" + part(setting.category()) + part(setting.name());
            entries.add(view.surface(id, Kind.INLINE_CARD, Style.defaults().padding(Insets.all(10)).gap(5), List.of(
                    view.paragraph(id + "/name", tr(setting.translationKey()), TextSize.HEADING, Variant.DEFAULT),
                    view.paragraph(id + "/current", setting.value()),
                    view.paragraph(id + "/default", tr("gui.fpsm.match_config.default", setting.defaultValue()), TextSize.SMALL, Variant.MUTED),
                    view.paragraph(id + "/description", tr(setting.descriptionKey()), TextSize.SMALL, Variant.MUTED))));
        }
        if (count == 0) entries.add(view.paragraph("config/no-match", tr(PREFIX + "no_match")));
    }

    @Override
    protected void composeOverlay(Composition content) {
        if (!choosingMap || state.snapshot() == null) return;
        var actions = new ArrayList<StudioComponents.Action>();
        for (var map : state.snapshot().maps()) {
            String name = map.displayName().isBlank() ? map.mapName() : map.displayName();
            actions.add(action("config/map/" + part(map.gameType()) + part(map.mapName()),
                    name + " · " + map.gameType() + " / " + map.mapName(), Icon.MAP, Variant.DEFAULT, state.canRequest(),
                    () -> {
                        choosingMap = false;
                        begin(Operation.SELECT, new Target(map.gameType(), map.mapName()));
                    }));
        }
        choices(content, "config/map-picker", tr(PREFIX + "choose_map"), actions, () -> {
            choosingMap = false;
            requestLayout();
        });
    }
}
