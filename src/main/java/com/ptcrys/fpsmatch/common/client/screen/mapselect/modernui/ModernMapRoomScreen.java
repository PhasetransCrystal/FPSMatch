package com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.client.screen.FPSMTeamActionScreen;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapDetailChildScreen;
import com.ptcrys.fpsmatch.common.client.screen.modernui.ModernScreen;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorNavigation;
import com.ptcrys.fpsmatch.common.client.screen.team.TeamActionModel;
import com.ptcrys.fpsmatch.common.packet.mapselect.*;
import com.ptcrys.fpsmatch.common.packet.shop.OpenShopEditorC2SPacket;
import com.ptcrys.fpsmatch.core.data.AreaData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.math.BigDecimal;
import java.util.*;

/** All map-room pages use the same native view lifecycle and server-owned room context. */
public final class ModernMapRoomScreen extends ModernScreen implements FPSMMapDetailChildScreen {
    public enum Page { DETAILS, PLAYERS, SETTINGS, MORE, INVITE, SHOPS, REGIONS, IMPORT }
    private MapRoomDetail detail;
    private Page page = Page.DETAILS;
    private Set<UUID> ready;
    private int countdown;
    private final Set<String> collapsedTeams = new HashSet<>();
    private final Map<String, String> drafts = new LinkedHashMap<>();
    private final Map<String, String> submitted = new LinkedHashMap<>();
    private boolean categoryPopup;
    private UUID contextPlayer;
    private final Set<String> selectedCategories = new LinkedHashSet<>();
    private String search = "", category = "", status = "", pending = "";
    private int pendingTicks;
    private Runnable discardDestination;
    private boolean clearConfirmation;
    private int regionIndex = -1;
    private boolean addingRegion, regionDirty, deleteConfirmation;
    private MapRegionActionC2SPacket.Action pendingRegionAction;
    private String pos1 = "", pos2 = "";
    private List<MapImportSourceInfo> sources = List.of();
    private MapImportSourceInfo source;
    private boolean importSettings, importShop, importKits;
    private int importConfirmation;

    public ModernMapRoomScreen(MapRoomDetail detail, Screen parent) {
        super(Component.literal(detail.summary().displayName()), parent);
        this.detail = detail;
        ready = Set.copyOf(detail.readyPlayers());
        countdown = detail.summary().readyCountdownSeconds();
        loadRegion();
    }
    public boolean accepts(MapRoomDetail incoming) {
        return incoming.summary().gameType().equals(detail.summary().gameType())
                && incoming.summary().mapName().equals(detail.summary().mapName());
    }
    @Override public void applyDetail(MapRoomDetail next) {
        if (!accepts(next)) return;
        detail = next;
        ready = Set.copyOf(next.readyPlayers());
        countdown = next.summary().readyCountdownSeconds();
        if (pending.equals("settings")) {
            submitted.entrySet().removeIf(e -> next.settings().stream().anyMatch(s ->
                    s.name().equals(e.getKey()) && equivalent(s, e.getValue())));
            drafts.entrySet().removeIf(e -> next.settings().stream().anyMatch(s ->
                    s.name().equals(e.getKey()) && equivalent(s, e.getValue())));
            if (submitted.isEmpty()) { pending = ""; status = tr("gui.fpsm.map_select.settings.save_success", savedCount); }
        }
        if (!regionDirty && !addingRegion) {
            if (regionIndex >= next.bombAreas().size()) regionIndex = -1;
            loadRegion();
        }
        refresh();
    }
    public void applyReadyState(String type, String map, int seconds, Set<UUID> players) {
        if (!type.equals(detail.summary().gameType()) || !map.equals(detail.summary().mapName())) return;
        ready = Set.copyOf(players); countdown = seconds; refresh();
    }
    public boolean acceptsSources(MapImportSourcesS2CPacket packet) {
        return packet.gameType().equals(detail.summary().gameType()) && packet.mapName().equals(detail.summary().mapName());
    }
    public void applySources(MapImportSourcesS2CPacket packet) {
        if (!acceptsSources(packet)) return;
        sources = List.copyOf(packet.sources());
        if (source != null) source = sources.stream().filter(s -> s.id().equals(source.id())).findFirst().orElse(null);
        if (pending.equals("sources")) pending = "";
        status = tr(sources.isEmpty() ? "gui.fpsm.map_import.empty" : "gui.fpsm.map_import.select"); refresh();
    }
    public void applyToast(MapRoomToastS2CPacket packet) {
        status = packet.message().getString();
        String key = packet.message().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey() : "";
        boolean generalError = packet.error() && (key.endsWith("no_permission") || key.endsWith("map_not_found"));
        if (generalError || pending.equals("settings") && packet.error() && key.contains(".setting.")
                || pending.equals("regions") && key.startsWith("gui.fpsm.map_regions.")
                || pending.equals("import") && key.startsWith("gui.fpsm.map_import.")
                || pending.equals("editor") && key.startsWith("gui.fpsm.shop_editor.open.")) {
            if (pending.equals("regions") && !packet.error() && pendingRegionAction != MapRegionActionC2SPacket.Action.PREVIEW) {
                if (pendingRegionAction == MapRegionActionC2SPacket.Action.ADD_BOMB) regionIndex = detail.bombAreas().size();
                if (pendingRegionAction == MapRegionActionC2SPacket.Action.REMOVE_BOMB) regionIndex = -1;
                regionDirty = false; addingRegion = false;
            }
            pending = ""; deleteConfirmation = false; importConfirmation = 0;
        }
        refresh();
    }
    @Override public void tick() {
        if (!pending.isEmpty() && ++pendingTicks >= 200) {
            status = tr(pending.equals("settings") ? "gui.fpsm.map_select.settings.save_timeout" : "gui.fpsm.map_import.timeout");
            pending = ""; refresh();
        }
    }
    private boolean idle() { return pending.isEmpty(); }
    private boolean operator() { return detail.summary().currentPlayerOp(); }
    private UUID selfId() { return Minecraft.getInstance().player == null ? new UUID(0, 0) : Minecraft.getInstance().player.getUUID(); }
    private void begin(String operation) { pending = operation; pendingTicks = 0; status = tr("gui.fpsm.shop_editor.state.opening"); }
    private void send(MapRoomActionC2SPacket.Action action, UUID player, String data) {
        FPSMatch.sendToServer(new MapRoomActionC2SPacket(action, detail.summary().gameType(), detail.summary().mapName(), player, data));
    }
    private void navigate(Page destination) {
        if (!idle() || destination == page) return;
        requestLeave(() -> { page = destination; status = ""; search = "";
            if (page == Page.REGIONS && !regionDirty) loadRegion();
            if (page == Page.IMPORT) requestSources();
            refresh(); });
    }
    private void requestLeave(Runnable destination) {
        if (!idle()) return;
        if (!drafts.isEmpty() || regionDirty) { discardDestination = destination; refresh(); }
        else destination.run();
    }
    @Override public void onClose() {
        if (contextPlayer != null) { contextPlayer = null; refresh(); return; }
        if (categoryPopup) { categoryPopup = false; refresh(); return; }
        if (discardDestination != null) { discardDestination = null; refresh(); return; }
        if (page == Page.SHOPS || page == Page.REGIONS || page == Page.IMPORT || page == Page.INVITE) {
            navigate(page == Page.INVITE ? Page.PLAYERS : Page.SETTINGS); return;
        }
        requestLeave(() -> {
            if (parent instanceof FPSMMapDetailChildScreen room) room.applyDetail(detail);
            if (!(parent instanceof ModernMapSelectionScreen) && !(parent instanceof FPSMMapDetailChildScreen))
                FPSMatch.sendToServer(new CloseMapViewC2SPacket());
            super.onClose();
        });
    }
    @Override protected List<Node> content() {
        if(contextPlayer!=null) {
            var player=detail.players().stream().filter(p->p.uuid().equals(contextPlayer)).findFirst().orElse(null);
            if(player==null)contextPlayer=null;
            else return dialog("player.commands",List.of(title("name",player.name()),
                    button("move",tr("gui.fpsm.team_manage.context.switch"),(operator()||player.uuid().equals(selfId()))
                            &&!TeamActionModel.availableTargetTeams(detail,player.uuid()).isEmpty(),()->{
                        contextPlayer=null;Minecraft.getInstance().setScreen(new FPSMTeamActionScreen(detail,this,player));}),
                    button("kick",tr("gui.fpsm.team_manage.context.kick"),TeamActionModel.canKick(detail,player.uuid()),()->{
                        contextPlayer=null;Minecraft.getInstance().setScreen(FPSMTeamActionScreen.confirmKick(detail,this,player));}),
                    button("back",tr("gui.back"),true,()->contextPlayer=null)),240,145);
        }
        int margin=Math.min(16,Math.max(8,width/32)), w=Math.max(1,width-2*margin), top=height<300?61:76;
        List<Node> nodes=new ArrayList<>();
        nodes.add(title("title",switch(page){case REGIONS->tr("gui.fpsm.map_regions.title");case IMPORT->tr("gui.fpsm.map_import.title");case INVITE->tr("gui.fpsm.map_select.invite.title");case SHOPS->tr("gui.fpsm.map_shop.title");default->detail.summary().displayName();}).at(margin,8,w,21));
        if(height>=300)nodes.add(text("identity",ModernMapSelectionScreen.gameTypeText(detail.summary().gameType())+" / "+detail.summary().mapName()).at(margin,32,w,13));
        boolean child=page==Page.REGIONS||page==Page.IMPORT||page==Page.INVITE||page==Page.SHOPS;
        if(child)top=54;
        List<Node> tabs=new ArrayList<>();
        for(Page tab:List.of(Page.DETAILS,Page.PLAYERS,Page.SETTINGS,Page.MORE))tabs.add(button(tab.name(),tr("gui.fpsm.team_manage.tab."+tab.name().toLowerCase(Locale.ROOT)),
                idle()&&(tab!=Page.MORE||operator()),()->navigate(tab)).selected(page==tab));
        if(!child)nodes.add(group("tabs",true,tabs).at(margin,height<300?32:47,w,24));
        List<Node> actions=new ArrayList<>();
        if(page==Page.DETAILS||page==Page.PLAYERS) actions.addAll(membership());
        else if(page==Page.SETTINGS) {
            actions.add(button("shops",tr("gui.fpsm.map_settings.shop"),operator()&&idle()&&!detail.editableShops().isEmpty(),()->navigate(Page.SHOPS)));
            actions.add(button("regions",tr("gui.fpsm.map_settings.regions"),operator()&&idle(),()->navigate(Page.REGIONS)));
            actions.add(button("import",tr("gui.fpsm.map_settings.import"),operator()&&idle(),()->navigate(Page.IMPORT)));
            actions.add(button("clear",tr(clearConfirmation?"gui.fpsm.map_select.settings.discard":"gui.fpsm.map_select.settings.clear_changes"),idle()&&!drafts.isEmpty(),
                    ()->{if(clearConfirmation){drafts.clear();clearConfirmation=false;}else clearConfirmation=true;}));
            actions.add(button("save",tr("gui.fpsm.map_select.settings.save"),operator()&&idle()&&validDrafts()&&!drafts.isEmpty(),this::save));
        }
        List<Node> childBody=List.of();
        if(page==Page.REGIONS) {
            childBody=regions();
            Node regionActions=childBody.stream().filter(n->n.key().equals("actions")).findFirst().orElseThrow();
            actions.add(regionActions.children().get(1)); actions.add(regionActions.children().get(0)); actions.add(regionActions.children().get(2));
            actions.add(childBody.stream().filter(n->n.key().equals("preview")).findFirst().orElseThrow());
            var debugData = FPSMClient.getGlobalData().getDebugData();
            actions.add(button("preview.visibility", tr(debugData.isVisible() ? "gui.fpsm.preview.hide" : "gui.fpsm.preview.show"),
                    idle(), debugData::toggleVisibility));
        } else if(page==Page.IMPORT) {
            childBody=imports();
            actions.add(childBody.stream().filter(n->n.key().equals("refresh")).findFirst().orElseThrow());
            actions.add(childBody.stream().filter(n->n.key().equals("import")).findFirst().orElseThrow());
        }
        actions.add(button("back",tr("gui.back"),idle(),this::onClose));
        int columns=page==Page.SETTINGS?(w<280?2:w<500?3:6):page==Page.REGIONS?(w<500?3:6):actions.size(), rows=(actions.size()+columns-1)/columns;
        int footer=page==Page.SETTINGS?43+rows*31:page==Page.REGIONS?36+rows*31:page==Page.IMPORT?70:40, bodyHeight=Math.max(1,height-top-footer);
        if(page==Page.SETTINGS) {
            nodes.add(settingsPanel(w,bodyHeight).at(margin,top,w,bodyHeight));
            nodes.add(text("pending",status.isBlank()?tr(drafts.isEmpty()?"gui.fpsm.map_select.settings.no_changes":"gui.fpsm.map_select.settings.pending",drafts.size()):status).at(margin,height-footer+3,w,30));
        } else if(page==Page.REGIONS) {
            int listWidth=Math.max(90,Math.min(180,w*32/100));
            boolean stacked=width<280;
            int listHeight=stacked?Math.max(40,bodyHeight*42/100):bodyHeight;
            int ew=stacked?w:w-listWidth-6;
            List<Node> regionList=childBody.stream().filter(n->n.key().equals("map")||n.key().startsWith("bomb.")).toList();
            nodes.add(scroll("regions.list",regionList).surface().at(margin,top,stacked?w:listWidth,listHeight));
            List<Node> editor=new ArrayList<>();
            for(Node n:childBody) {
                switch(n.key()) {
                    case "region"->editor.add(n.at(8,8,ew-16,18));
                    case "pos1.field"->{editor.add(n.children().get(0).at(8,38,48,16));editor.add(n.children().get(1).at(62,32,Math.max(32,ew-154),24));}
                    case "pos2.field"->{editor.add(n.children().get(0).at(8,67,48,16));editor.add(n.children().get(1).at(62,61,Math.max(32,ew-154),24));}
                    case "use1"->editor.add(n.at(ew-84,32,76,24));
                    case "use2"->editor.add(n.at(ew-84,61,76,24));
                }
            }
            nodes.add(scroll("regions.editor",List.of(canvas("fields",editor).size(-1,90))).surface()
                    .at(stacked?margin:margin+listWidth+6,stacked?top+listHeight+6:top,ew,stacked?Math.max(1,bodyHeight-listHeight-6):bodyHeight));
            nodes.add(text("status",status).at(margin,height-footer+4,w,18));
        } else if(page==Page.IMPORT) {
            List<Node> sources=childBody.stream().filter(n->!Set.of("title","settings","shop","kits","refresh","import").contains(n.key())).toList();
            nodes.add(scroll("import.sources",sources.isEmpty()?List.of(text("empty",tr("gui.fpsm.map_import.empty"))):sources).surface().at(margin,top,w,Math.max(1,bodyHeight-24)));
            nodes.add(text("status",status).at(margin,height-footer-22,w,18));
            int optionWidth=(w-10)/3,index=0;
            for(Node n:childBody)if(Set.of("settings","shop","kits").contains(n.key()))nodes.add(n.at(margin+index++*(optionWidth+5),height-footer+2,optionWidth,24));
        } else if (page == Page.MORE) {
            int commandColumns = w < 340 ? 1 : 2;
            int commandWidth = Math.max(1, (w - 16 - (commandColumns - 1) * 8) / commandColumns);
            List<Node> commands = new ArrayList<>();
            commands.add(text("title", tr("gui.fpsm.map_select.manage.match_actions")).at(4, 6, w - 12, 14));
            List<Node> buttons = management();
            for (int i = 0; i < buttons.size(); i++) {
                commands.add(buttons.get(i).at(4 + i % commandColumns * (commandWidth + 8),
                        26 + i / commandColumns * 34, commandWidth, 26));
            }
            nodes.add(scroll("commands", List.of(canvas("grid", commands)
                    .size(-1, 30 + (buttons.size() + commandColumns - 1) / commandColumns * 34)))
                    .surface().at(margin, top, w, Math.max(1, height - top - 74)));
            nodes.add(text("status", operator() ? status : tr("gui.fpsm.map_select.manage.no_permission"))
                    .at(margin, height - 70, w, 28));
        } else if (page == Page.PLAYERS) {
            nodes.add(text("ready.summary", readiness()).at(margin, top, Math.max(1, w - 104), 18));
            nodes.add(button("invite", tr("gui.fpsm.map_select.invite.title"), idle(), () -> navigate(Page.INVITE))
                    .at(margin + w - 100, top, 100, 18));
            nodes.add(scroll("roster", players()).at(margin, top + 20, w, Math.max(1, height - top - 60)));
        } else if (page == Page.SHOPS) {
            boolean compact = width < 460 || height < 300;
            int shopMargin = compact ? 8 : 16, panelTop = compact ? 48 : 58, actionHeight = compact ? 48 : 44;
            int panelWidth = width - shopMargin * 2, panelHeight = Math.max(1, height - panelTop - actionHeight);
            nodes.add(canvas("shops.panel", List.of(scroll("shops", shops())
                    .at(8, 8, panelWidth - 16, Math.max(1, panelHeight - 16))))
                    .surface().at(shopMargin, panelTop, panelWidth, panelHeight));
            int backWidth = compact ? 82 : 104;
            nodes.add(text("status", status.isBlank() ? tr(detail.editableShops().isEmpty()
                    ? "gui.fpsm.map_shop.unsupported" : "gui.fpsm.map_shop.selection.ready") : status)
                    .at(shopMargin + 2, height - actionHeight + 13, panelWidth - backWidth - 12, 18));
        } else if (page == Page.INVITE) {
            boolean compact = width < 420 || height < 270;
            int inviteMargin = compact ? 8 : 16, panelTop = compact ? 52 : 58, footerHeight = compact ? 38 : 46;
            int panelWidth = width - inviteMargin * 2, panelHeight = Math.max(1, height - panelTop - footerHeight);
            nodes.add(canvas("invite.panel", List.of(scroll("targets", invitations())
                    .at(6, 6, panelWidth - 12, Math.max(1, panelHeight - 12))))
                    .surface().at(inviteMargin, panelTop, panelWidth, panelHeight));
        } else {
            List<Node> body=switch(page){case DETAILS->details();case PLAYERS->players();case MORE->management();case INVITE->invitations();
                case SHOPS->shops();case REGIONS->regions();case IMPORT->imports();default->List.of();};
            nodes.add(scroll("page."+page,body).at(margin,top,w,Math.max(1,bodyHeight-18)));
            nodes.add(text("status",status).at(margin,height-footer-18,w,18));
        }
        int buttonWidth=Math.max(1,(w-(columns-1)*5)/columns);
        for(int i=0;i<actions.size();i++) {
            if (page == Page.MORE) nodes.add(actions.get(i).at(margin, height - 34, Math.min(112, w), 26));
            else if (page == Page.SHOPS) {
                boolean compact = width < 460 || height < 300;
                int shopMargin = compact ? 8 : 16, backWidth = compact ? 82 : 104;
                nodes.add(actions.get(i).at(width - shopMargin - backWidth, height - (compact ? 48 : 44) + 8, backWidth, 28));
            } else if (page == Page.INVITE) {
                boolean compact = width < 420 || height < 270;
                nodes.add(actions.get(i).at(compact ? 8 : 16, height - (compact ? 38 : 46) + 7, Math.min(112, w), 26));
            } else nodes.add(actions.get(i).at(margin+i%columns*(buttonWidth+5),height-34-(rows-1-i/columns)*31,buttonWidth,26));
        }
        if(discardDestination!=null) {
            int dw=Math.min(320,w),dh=Math.min(140,height-16),dx=(width-dw)/2,dy=(height-dh)/2;
            return List.of(canvas("confirmation",List.of(canvas("dialog",List.of(
                    title("title",tr("gui.fpsm.map_select.settings.discard")).at(8,8,dw-16,24),
                    text("message",tr("gui.fpsm.map_select.settings.discard.message")).at(8,36,dw-16,Math.max(20,dh-74)),
                    row("actions",button("keep",tr("gui.fpsm.map_select.settings.keep_editing"),true,()->discardDestination=null),
                            button("discard",tr("gui.fpsm.map_select.settings.discard"),true,()->{
                                Runnable destination=discardDestination;discardDestination=null;drafts.clear();regionDirty=false;addingRegion=false;
                                loadRegion();if(destination!=null)destination.run();})).at(8,dh-32,dw-16,24))).surface().at(dx,dy,dw,dh))).fill());
        }
        return List.of(canvas("room",nodes).fill());
    }
    private List<Node> details() {
        var room = detail.summary();
        List<Node> nodes = new ArrayList<>();
        String texture = detail.backgroundTexture().isBlank() ? detail.iconTexture() : detail.backgroundTexture();
        int previewWidth=Math.max(1,Math.min(320,width-40));
        if(!texture.isBlank())nodes.add(image("map",texture).size(previewWidth,previewWidth*9f/16f));
        nodes.add(title("name",room.displayName()));
        nodes.add(text("mode",ModernMapSelectionScreen.gameTypeText(room.gameType())+" · "+ModernMapSelectionScreen.statusText(room)));
        nodes.add(text("players", tr("gui.fpsm.map_select.detail.players", room.joinedPlayers(), room.maxPlayers() < 0 ? "∞" : room.maxPlayers())));
        nodes.add(text("area", tr("gui.fpsm.map_select.detail.area", room.areaText())));
        nodes.add(text("dimension", tr("gui.fpsm.map_select.detail.dimension", room.dimension())));
        nodes.add(text("rules", detail.rulesKey().isBlank() ? tr("gui.fpsm.map_select.detail.rules.none") : tr(detail.rulesKey())));
        nodes.add(text("ready",tr("gui.fpsm.team_manage.ready_summary",ready.size(),detail.players().stream().filter(p->!p.spectator()).count()))); return nodes;
    }
    private List<Node> membership() {
        var room=detail.summary(); boolean joined=room.currentPlayerJoined()||room.currentPlayerSpectating();
        return List.of(button("membership",tr(joined?"gui.fpsm.map_select.leave":"gui.fpsm.map_select.join"),
                        joined||!room.full()&&(!room.started()||room.allowJoinInProgress()),
                        ()->send(joined?MapRoomActionC2SPacket.Action.LEAVE:MapRoomActionC2SPacket.Action.JOIN,selfId(),"")),
                button("switch",tr("gui.fpsm.team_manage.context.switch"),joined&&!TeamActionModel.availableTargetTeams(detail,selfId()).isEmpty(),
                        ()->detail.players().stream().filter(p->p.uuid().equals(selfId())).findFirst().ifPresent(p->Minecraft.getInstance().setScreen(new FPSMTeamActionScreen(detail,this,p)))),
                button("ready",tr(ready.contains(selfId())?"gui.fpsm.team_manage.ready.off":"gui.fpsm.team_manage.ready.on"),
                        room.currentPlayerJoined()&&!room.started(),()->send(MapRoomActionC2SPacket.Action.READY,selfId(),"")));
    }
    private List<Node> players() {
        List<Node> nodes = new ArrayList<>();
        for (MapRoomTeamInfo team : detail.teams().stream().sorted(Comparator.comparing(MapRoomTeamInfo::spectator).thenComparing(MapRoomTeamInfo::name)).toList()) {
            List<Node> rows = new ArrayList<>();
            rows.add(button("team", (collapsedTeams.contains(team.name()) ? "+ " : "− ") + team.name() + "  " + team.currentPlayers()
                    + "/" + (team.playerLimit() < 0 ? "∞" : team.playerLimit()), true,
                    () -> { if (!collapsedTeams.remove(team.name())) collapsedTeams.add(team.name()); }));
            if (!collapsedTeams.contains(team.name())) for (MapRoomPlayerInfo player : detail.players()) {
                if (!team.name().equals(player.teamName())) continue;
                int rowWidth=width-2*Math.min(16,Math.max(8,width/32));
                rows.add(actionCanvas(player.uuid().toString(),player.name(),true,event->{
                    if(!event.startsWith("hover")&&(operator()||player.uuid().equals(selfId())))contextPlayer=player.uuid();
                },List.of(avatar("avatar."+player.uuid(),player.uuid(),player.name()).at(8,4,20,20),
                        text("name",player.name()).at(36,4,Math.max(1,rowWidth-134),20),
                        (ready.contains(player.uuid())?accent("state",tr("gui.fpsm.team_manage.ready_mark")):
                                muted("state",tr(player.online()?"gui.fpsm.map_select.online":"gui.fpsm.map_select.offline")))
                                .at(rowWidth-94,4,86,20))).size(-1,28));
            }
            nodes.add(group(team.name(), false, rows));
        }
        return nodes;
    }
    private String readiness() {
        return tr("gui.fpsm.team_manage.ready_summary", ready.size(), detail.players().stream().filter(p -> !p.spectator()).count())
                + (countdown > 0 ? " · " + tr("gui.fpsm.team_manage.countdown", countdown) : "");
    }
    private List<Node> invitations() {
        List<Node> nodes = new ArrayList<>();
        boolean compact = width < 420 || height < 270;
        int rowWidth = width - (compact ? 16 : 32) - 12;
        for (MapRoomPlayerInfo player : detail.availableInviteTargets()) {
            nodes.add(actionCanvas(player.uuid().toString(), tr("gui.fpsm.map_select.invite") + " " + player.name(), idle(), event -> {
                if (!event.startsWith("hover")) send(MapRoomActionC2SPacket.Action.INVITE, player.uuid(), "");
            }, List.of(text("name", player.name()).at(8, 4, Math.max(1, rowWidth - 100), 18),
                    muted("online", tr("gui.fpsm.map_select.online")).at(compact ? 8 : rowWidth - 150, compact ? 24 : 8, 60, 16),
                    accent("action", tr("gui.fpsm.map_select.invite")).at(rowWidth - 76, compact ? 14 : 8, 68, 18)))
                    .size(-1, compact ? 46 : 34));
        }
        if (nodes.isEmpty()) nodes.add(text("empty", tr("gui.fpsm.map_select.invite.empty")));
        return nodes;
    }
    private List<Node> management() {
        List<Node> nodes = new ArrayList<>();
        for (MapRoomActionC2SPacket.Action action : List.of(MapRoomActionC2SPacket.Action.DEBUG_START, MapRoomActionC2SPacket.Action.DEBUG_RESET,
                MapRoomActionC2SPacket.Action.DEBUG_NEW_ROUND, MapRoomActionC2SPacket.Action.DEBUG_CLEANUP, MapRoomActionC2SPacket.Action.DEBUG_SWITCH)) {
            String suffix = action.name().substring(6).toLowerCase(Locale.ROOT);
            nodes.add(button(action.name(), tr("gui.fpsm.map_select.debug." + suffix), operator(), () -> send(action, null, "")));
        }
        return nodes;
    }
    private Node settingsPanel(int w,int h) {
        int usable=w-14, filterWidth=Math.min(126,Math.max(82,usable*34/100));
        boolean compact=w<250, stacked=w<230;
        int listTop=stacked?61:34;
        List<Node> nodes=new ArrayList<>();
        nodes.add(field("settings.search",search,true,v->search=v).hint(tr("gui.fpsm.map_select.settings.search"))
                .at(7,6,stacked?usable:Math.max(1,usable-filterWidth-6),22));
        nodes.add(button("category.filter",tr("gui.fpsm.map_select.settings.category_filter")+(selectedCategories.isEmpty()?"":" ("+selectedCategories.size()+")"),true,
                ()->categoryPopup=!categoryPopup).at(stacked?7:w-7-filterWidth,stacked?33:6,stacked?usable:filterWidth,22));
        List<Node> settings=new ArrayList<>();
        var groups=com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui.MapSettingsGroupingModel.group(detail.settings(),selectedCategories);
        for(var group:groups) {
            List<Node> entries=new ArrayList<>();
            for(var setting:group.settings()) {
                if(!(setting.name()+" "+tr(setting.translationKey())+" "+tr(setting.descriptionKey())).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))continue;
                boolean enabled=operator()&&setting.editable()&&idle()&&discardDestination==null;
                String value=drafts.getOrDefault(setting.name(),setting.value());
                int rw=Math.max(1,usable-4),rh=compact?51:33;
                int controlX=compact?5:Math.max(60,rw*43/100),controlY=compact?25:5,controlWidth=Math.max(24,rw-controlX-35);
                List<Node> controls=new ArrayList<>();
                String label=tr(setting.translationKey())+(drafts.containsKey(setting.name())?" *":"")+(normalize(setting,value).isEmpty()?" !":"");
                controls.add(text("name",label).hint(tr(setting.descriptionKey())).at(4,3,compact?rw-8:controlX-8,24));
                if(setting.type()==MapRoomSettingInfo.SettingType.BOOLEAN)
                    controls.add(toggle("value","",Boolean.parseBoolean(value),enabled,v->stage(setting.name(),v.toString())).at(controlX,controlY,controlWidth,23));
                else if(setting.slider()&&MapSettingNumbers.hasRange(setting.minValue(),setting.maxValue(),setting.step())&&controlWidth>95) {
                    int steps=MapSettingNumbers.sliderSteps(setting.minValue(),setting.maxValue(),setting.step());
                    int progress=MapSettingNumbers.sliderProgress(value,setting.minValue(),setting.maxValue(),setting.step());
                    controls.add(slider("slider",Math.max(0,Math.min(steps,progress)),steps,enabled,v->{
                        String next=MapSettingNumbers.sliderValue(Integer.parseInt(v),setting.type()==MapRoomSettingInfo.SettingType.INTEGER,setting.minValue(),setting.maxValue(),setting.step());
                        stage(setting.name(),next);}).at(controlX,controlY,controlWidth-52,23));
                    controls.add(field("value",value,enabled,v->stage(setting.name(),v)).at(controlX+controlWidth-48,controlY,48,23));
                } else controls.add(field("value",value,enabled,v->stage(setting.name(),v)).at(controlX,controlY,controlWidth,23));
                if (!equivalent(setting, setting.defaultValue(), value))
                    controls.add(iconButton("reset","rotate-cw",tr("controls.reset"),enabled,()->stage(setting.name(),setting.defaultValue())).at(rw-29,controlY,25,23));
                entries.add(canvas("setting."+setting.name(),controls).surface().size(-1,rh));
            }
            if(!entries.isEmpty()) { settings.add(text("category."+group.category(),tr(MapRoomSettingInfo.categoryTranslationKey(group.category()))).size(-1,22));settings.addAll(entries); }
        }
        if(settings.isEmpty())settings.add(text("empty",tr("gui.fpsm.map_select.settings.empty")));
        nodes.add(scroll("settings.list",settings).at(7,listTop,usable,Math.max(1,h-listTop-7)));
        if(categoryPopup) {
            List<Node> categories=new ArrayList<>();
            for(String c:com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui.MapSettingsGroupingModel.categories(detail.settings()))
                categories.add(toggle("category."+c,tr(MapRoomSettingInfo.categoryTranslationKey(c)),selectedCategories.contains(c),true,
                        v->{if(v)selectedCategories.add(c);else selectedCategories.remove(c);}).size(-1,23));
            categories.add(button("clear",tr("gui.fpsm.map_select.settings.category_filter.clear"),!selectedCategories.isEmpty(),selectedCategories::clear));
            nodes.add(scroll("categories.popup",categories).surface().at(stacked?7:w-7-filterWidth,stacked?58:31,stacked?usable:filterWidth,Math.max(1,Math.min(h-listTop-7,categories.size()*26))));
        }
        return canvas("settings.panel",nodes).surface();
    }
    private void stage(String name, String value) {
        MapRoomSettingInfo current = detail.settings().stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        if (current == null || !current.editable() || !operator() || !idle()) return;
        clearConfirmation = false;
        if (value.equals(current.value())) drafts.remove(name); else drafts.put(name, value);
    }
    private boolean validDrafts() {
        return drafts.entrySet().stream().allMatch(e -> detail.settings().stream().anyMatch(s ->
                s.name().equals(e.getKey()) && s.editable() && normalize(s, e.getValue()).isPresent()));
    }
    private int savedCount;
    private void save() {
        if (!operator() || !idle() || !validDrafts() || drafts.isEmpty()) return;
        submitted.clear();
        for (MapRoomSettingInfo s : detail.settings()) if (drafts.containsKey(s.name()))
            normalize(s, drafts.get(s.name())).ifPresent(v -> submitted.put(s.name(), v));
        drafts.putAll(submitted); savedCount = submitted.size(); begin("settings");
        status = tr("gui.fpsm.map_select.settings.saving");
        submitted.forEach((name, value) -> FPSMatch.sendToServer(new MapRoomSettingsC2SPacket(detail.summary().gameType(), detail.summary().mapName(), name, value)));
    }
    private static Optional<String> normalize(MapRoomSettingInfo s, String value) {
        if (value == null || value.length() > 1024) return Optional.empty();
        return switch (s.type()) {
            case INTEGER, DECIMAL -> MapSettingNumbers.normalize(value, s.type() == MapRoomSettingInfo.SettingType.INTEGER, s.minValue(), s.maxValue(), s.step());
            case BOOLEAN -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") ? Optional.of(value.toLowerCase(Locale.ROOT)) : Optional.empty();
            default -> Optional.of(value);
        };
    }
    private static boolean equivalent(MapRoomSettingInfo s, String value) {
        return equivalent(s, s.value(), value);
    }
    private static boolean equivalent(MapRoomSettingInfo s, String reference, String value) {
        if (reference.equals(value)) return true;
        if (s.type() == MapRoomSettingInfo.SettingType.BOOLEAN) return reference.equalsIgnoreCase(value);
        try {
            if (s.type() == MapRoomSettingInfo.SettingType.INTEGER || s.type() == MapRoomSettingInfo.SettingType.DECIMAL)
                return new BigDecimal(reference.trim()).compareTo(new BigDecimal(value.trim())) == 0
                        || s.type() == MapRoomSettingInfo.SettingType.DECIMAL && Float.toString(Float.parseFloat(value)).equals(reference);
        } catch (NumberFormatException ignored) {}
        return false;
    }
    private List<Node> shops() {
        List<Node> nodes = new ArrayList<>();
        boolean compact = width < 460 || height < 300;
        int rowWidth = width - (compact ? 16 : 32) - 16, editWidth = compact ? 64 : 86;
        for (EditableShopInfo shop : detail.editableShops()) nodes.add(canvas(shop.teamName(), List.of(
                text("name", shop.displayName()).at(8, compact ? 15 : 10, Math.max(1, rowWidth - editWidth - 24), 20),
                button("edit", tr("gui.fpsm.map_shop.edit"), operator() && idle(), () -> {
            if (!operator() || !idle()) return;
            begin("editor");
            ShopEditorNavigation.beginMapRoom(() -> { pending = ""; return this; }, shop.gameType(), shop.mapName(), shop.teamName());
            FPSMatch.sendToServer(new OpenShopEditorC2SPacket(shop.gameType(), shop.mapName(), shop.teamName()));
        }).hint(tr("gui.fpsm.map_shop.edit.hint")).at(rowWidth - editWidth - 8, compact ? 12 : 7, editWidth, 26)))
                .surface().size(-1, compact ? 50 : 40));
        if (nodes.isEmpty()) nodes.add(text("empty", tr("gui.fpsm.map_shop.unsupported")));
        return nodes;
    }
    private static String format(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private BlockPos currentPosition() { return Minecraft.getInstance().player == null ? BlockPos.ZERO : Minecraft.getInstance().player.blockPosition(); }
    private void loadRegion() {
        AreaData area = regionIndex < 0 || regionIndex >= detail.bombAreas().size() ? detail.mapArea() : detail.bombAreas().get(regionIndex);
        pos1 = format(area.pos1()); pos2 = format(area.pos2());
    }
    private void selectRegion(int index) {
        requestLeave(() -> { regionIndex = index; addingRegion = false; deleteConfirmation = false; loadRegion(); refresh(); });
    }
    private List<Node> regions() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(button("map", tr("gui.fpsm.map_regions.map_area"), idle(), () -> selectRegion(-1)));
        for (int i = 0; i < detail.bombAreas().size(); i++) { int index = i;
            nodes.add(button("bomb." + i, tr("gui.fpsm.map_regions.bomb_area", i + 1), idle(), () -> selectRegion(index))); }
        nodes.add(title("region", tr(addingRegion ? "gui.fpsm.map_regions.new_bomb" : regionIndex < 0 ? "gui.fpsm.map_regions.map_area" : "gui.fpsm.map_regions.bomb_area", regionIndex + 1)));
        boolean editable = operator() && idle() && !detail.summary().started();
        nodes.add(input("pos1", tr("gui.fpsm.map_regions.pos1"), pos1, editable, v -> { pos1 = v; regionDirty = true; deleteConfirmation = false; }));
        nodes.add(button("use1", tr("gui.fpsm.map_regions.use_position"), editable, () -> { pos1 = format(currentPosition()); regionDirty = true; }));
        nodes.add(input("pos2", tr("gui.fpsm.map_regions.pos2"), pos2, editable, v -> { pos2 = v; regionDirty = true; deleteConfirmation = false; }));
        nodes.add(button("use2", tr("gui.fpsm.map_regions.use_position"), editable, () -> { pos2 = format(currentPosition()); regionDirty = true; }));
        nodes.add(row("actions", button("save", tr("gui.fpsm.map_regions.apply"), editable, this::saveRegion),
                button("add", tr("gui.fpsm.map_regions.add"), editable && detail.demolitionRegionsSupported(), () -> requestLeave(() -> {
                    addingRegion = true; regionIndex = -1; pos1 = pos2 = format(currentPosition()); regionDirty = true; refresh(); })),
                button("delete", tr(deleteConfirmation ? "gui.fpsm.map_regions.delete_confirm" : "gui.fpsm.map_regions.delete"), editable && !addingRegion && regionIndex >= 0, () -> {
                    if (!deleteConfirmation) { deleteConfirmation = true; status = tr("gui.fpsm.map_regions.delete_warning", regionIndex + 1); }
                    else regionAction(MapRegionActionC2SPacket.Action.REMOVE_BOMB, BlockPos.ZERO, BlockPos.ZERO);
                })));
        nodes.add(button("preview", tr("gui.fpsm.map_regions.preview"), idle(), () -> regionAction(MapRegionActionC2SPacket.Action.PREVIEW, BlockPos.ZERO, BlockPos.ZERO)));
        return nodes;
    }
    private void saveRegion() {
        Optional<BlockPos> a = parsePosition(pos1), b = parsePosition(pos2);
        if (a.isEmpty() || b.isEmpty()) { status = tr("gui.fpsm.map_regions.action.invalid"); return; }
        regionAction(addingRegion ? MapRegionActionC2SPacket.Action.ADD_BOMB : regionIndex < 0
                ? MapRegionActionC2SPacket.Action.SET_MAP : MapRegionActionC2SPacket.Action.UPDATE_BOMB, a.get(), b.get());
    }
    static Optional<BlockPos> parsePosition(String value) {
        String[] xyz = value.trim().split("\\s+");
        if (xyz.length != 3) return Optional.empty();
        try { return Optional.of(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]))); }
        catch (NumberFormatException invalid) { return Optional.empty(); }
    }
    private void regionAction(MapRegionActionC2SPacket.Action action, BlockPos a, BlockPos b) {
        if (!idle() || action != MapRegionActionC2SPacket.Action.PREVIEW && (!operator() || detail.summary().started())) return;
        if (action == MapRegionActionC2SPacket.Action.PREVIEW) FPSMClient.getGlobalData().getDebugData().setVisible(true);
        begin("regions");
        pendingRegionAction = action;
        FPSMatch.sendToServer(new MapRegionActionC2SPacket(action, detail.summary().gameType(), detail.summary().mapName(), regionIndex, a, b));
    }
    private void requestSources() {
        if (!idle()) return;
        begin("sources");
        FPSMatch.sendToServer(new RequestMapImportSourcesC2SPacket(detail.summary().gameType(), detail.summary().mapName()));
    }
    private List<Node> imports() {
        List<Node> nodes = new ArrayList<>();

        for (MapImportSourceInfo item : sources) nodes.add(button("source."+item.id(), (source != null && source.id().equals(item.id()) ? "● " : "")
                + item.archiveName() + " / " + item.mapName(), idle(), () -> {
            source = item; importSettings = item.hasSettings(); importShop = item.hasShop(); importKits = item.hasStartKits(); importConfirmation = 0;
        }));
        nodes.add(toggle("settings", tr("gui.fpsm.map_import.settings"), importSettings, source != null && source.hasSettings() && idle(), v -> { importSettings = v; importConfirmation = 0; }));
        nodes.add(toggle("shop", tr("gui.fpsm.map_import.shop"), importShop, source != null && source.hasShop() && idle(), v -> { importShop = v; importConfirmation = 0; }));
        nodes.add(toggle("kits", tr("gui.fpsm.map_import.kits"), importKits, source != null && source.hasStartKits() && idle(), v -> { importKits = v; importConfirmation = 0; }));
        nodes.add(button("refresh", tr("gui.fpsm.map_select.refresh"), idle(), this::requestSources));
        nodes.add(button("import", tr(importConfirmation == 0 ? "gui.fpsm.map_import.action" : importConfirmation == 1
                ? "gui.fpsm.map_import.confirm" : "gui.fpsm.map_import.confirm_final"), operator() && idle() && source != null && (importSettings || importShop || importKits), () -> {
            if (!idle() || source == null || !operator()) return;
            if (importConfirmation++ < 2) { status = tr(importConfirmation == 1 ? "gui.fpsm.map_import.warning" : "gui.fpsm.map_import.warning_final", source.archiveName(), source.mapName()); return; }
            begin("import"); FPSMatch.sendToServer(new ImportMapConfigC2SPacket(detail.summary().gameType(), detail.summary().mapName(), source.id(), importSettings && source.hasSettings(), importShop && source.hasShop(), importKits && source.hasStartKits()));
        }));
        return nodes;
    }
}
