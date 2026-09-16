package com.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.modernui.ModernScreen;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorNavigation;
import com.ptcrys.fpsmatch.common.packet.shop.*;
import com.ptcrys.fpsmatch.common.packet.mapselect.EditableShopInfo;
import net.minecraft.network.chat.Component;
import java.util.*;

public final class ModernShopConfigToolScreen extends ModernScreen {
    private OpenShopConfigToolScreenS2CPacket data;
    private boolean opening;
    private int ticks;
    private String status = "";
    public ModernShopConfigToolScreen(OpenShopConfigToolScreenS2CPacket data) {
        super(Component.translatable("gui.fpsm.shop_config.title"), null); this.data = data;
    }
    public void applyData(OpenShopConfigToolScreenS2CPacket data) { this.data = data; refresh(); }
    public boolean isEditorOpenPending() { return opening; }
    public void applyEditorOpenFailure(Component message) { opening = false; status = message.getString(); refresh(); }
    @Override public void tick() {
        if (opening && ++ticks >= 200) applyEditorOpenFailure(Component.translatable("gui.fpsm.shop_editor.open.timeout"));
    }
    @Override protected List<Node> content() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(title("title", getTitle().getString()).at(8,8,width-16,26));
        List<Node> body = new ArrayList<>();
        List<Node> types = new ArrayList<>();
        data.maps().stream().map(OpenShopConfigToolScreenS2CPacket.MapEntry::gameType).distinct().forEach(type ->
                types.add(button(type, (type.equals(data.selectedType()) ? "● " : "") + type, !opening, () ->
                        select(type, data.maps().stream().filter(m -> type.equals(m.gameType())).map(OpenShopConfigToolScreenS2CPacket.MapEntry::mapName).findFirst().orElse("")))));
        nodes.add(select("types",data.selectedType(),types.stream().map(n->text(n.key(),n.key())).toList(),
                type->select(type,data.maps().stream().filter(m->type.equals(m.gameType())).map(OpenShopConfigToolScreenS2CPacket.MapEntry::mapName).findFirst().orElse("")))
                .at(8,42,Math.max(1,(width-22)/2),24));
        List<Node> maps = new ArrayList<>();
        data.maps().stream().filter(m -> m.gameType().equals(data.selectedType())).forEach(map -> maps.add(button(map.mapName(),
                (map.mapName().equals(data.selectedMap()) ? "● " : "") + map.mapName(), !opening, () -> select(map.gameType(), map.mapName()))));
        nodes.add(select("maps",data.selectedMap(),maps.stream().map(n->text(n.key(),n.key())).toList(),map->select(data.selectedType(),map))
                .at(14+(width-22)/2,42,Math.max(1,(width-22)/2),24));
        for (EditableShopInfo shop : data.shops()) body.add(button(shop.gameType() + ":" + shop.mapName() + ":" + shop.teamName(),
                shop.displayName() + " / " + shop.teamName(), !opening, () -> {
                    if (opening) return; opening = true; ticks = 0; status = tr("gui.fpsm.shop_editor.state.opening");
                    ShopEditorNavigation.beginConfigTool(shop.gameType(), shop.mapName(), shop.teamName());
                    FPSMatch.sendToServer(new OpenShopEditorC2SPacket(shop.gameType(), shop.mapName(), shop.teamName()));
                }));
        if (data.shops().isEmpty()) body.add(text("empty", tr("gui.fpsm.shop_config.empty")));
        nodes.add(scroll("body", body).surface().at(8,74,width-16,Math.max(1,height-130)));
        nodes.add(text("status", status).at(8,height-52,width-16,18));
        nodes.add(row("actions", button("refresh", tr("gui.fpsm.map_select.refresh"), !opening, () -> FPSMatch.sendToServer(
                new ShopConfigToolActionC2SPacket(ShopConfigToolActionC2SPacket.Action.REFRESH, data.selectedType(), data.selectedMap()))),
                button("close", tr("gui.back"), !opening, this::onClose)).at(8,height-32,width-16,24));
        return List.of(canvas("shop.config",nodes).fill());
    }
    private void select(String type, String map) {
        if (!opening && !type.isBlank() && !map.isBlank()) FPSMatch.sendToServer(new ShopConfigToolActionC2SPacket(ShopConfigToolActionC2SPacket.Action.SELECT, type, map));
    }
    @Override public void onClose() { if (!opening) { ShopEditorNavigation.clear(); super.onClose(); } }
}
