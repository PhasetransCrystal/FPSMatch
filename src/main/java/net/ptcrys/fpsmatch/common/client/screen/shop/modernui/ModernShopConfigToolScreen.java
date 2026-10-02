package net.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.packet.mapselect.EditableShopInfo;
import net.ptcrys.fpsmatch.common.packet.shop.*;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.*;

public final class ModernShopConfigToolScreen extends ModernShopScreen {

    private OpenShopConfigToolScreenS2CPacket data;

    public ModernShopConfigToolScreen(OpenShopConfigToolScreenS2CPacket data) {
        super(Component.translatable("gui.fpsm.shop_config.title"), null);
        this.data = data;
    }

    public void applyData(OpenShopConfigToolScreenS2CPacket data) {
        this.data = data;
        refresh();
    }

    @Override
    protected List<Node> content() {
        List<Node> nodes = new ArrayList<>();
        var frame = ShopEditorLayoutModel.frame(layoutWidth(), layoutHeight());
        int w = frame.body().width();
        boolean narrow = layoutWidth() < 400;
        nodes.add(header(getTitle().getString(), data.selectedType() + " / " + data.selectedMap(),
                iconButton("close", "x", tr("gui.back"), true, this::onClose)));
        List<Node> body = new ArrayList<>();
        List<Node> types = new ArrayList<>();
        data.maps().stream().map(OpenShopConfigToolScreenS2CPacket.MapEntry::gameType).distinct().forEach(type -> types.add(text(type, type)));
        List<Node> filters = new ArrayList<>();
        filters.add(select("types", data.selectedType(), types.stream().map(n -> text(n.key(), n.key())).toList(),
                type -> select(type, data.maps().stream().filter(m -> type.equals(m.gameType())).map(OpenShopConfigToolScreenS2CPacket.MapEntry::mapName).findFirst().orElse("")))
                .at(0, 0, narrow ? w : (w - 8) / 2, 24));
        List<Node> maps = new ArrayList<>();
        data.maps().stream().filter(m -> m.gameType().equals(data.selectedType())).forEach(map -> maps.add(button(map.mapName(),
                map.mapName(), true, () -> select(map.gameType(), map.mapName()))));
        filters.add(select("maps", data.selectedMap(), maps.stream().map(n -> text(n.key(), n.key())).toList(), map -> select(data.selectedType(), map))
                .at(narrow ? 0 : (w - 8) / 2 + 8, narrow ? 30 : 0, narrow ? w : (w - 8) / 2, 24));
        body.add(canvas("filters", filters).size(-1, narrow ? 60 : 30));
        for (EditableShopInfo shop : data.shops()) body.add(button(shop.gameType() + ":" + shop.mapName() + ":" + shop.teamName(),
                shop.displayName() + " / " + shop.teamName(), true, () -> Minecraft.getInstance().setScreen(new ModernEditorShopScreen(
                        new ShopEditorSnapshot.Target(shop.gameType(), shop.mapName(), shop.teamName()), this)))
                .size(-1, 30));
        if (data.shops().isEmpty()) body.add(text("empty", tr("gui.fpsm.shop_config.empty")));
        nodes.add(place(scroll("body", body), frame.body()));
        nodes.add(footer("", iconButton("reload", "rotate-cw", tr("gui.fpsm.map_select.refresh"), true, () -> FPSMatch.sendToServer(
                new ShopConfigToolActionC2SPacket(ShopConfigToolActionC2SPacket.Action.REFRESH, data.selectedType(), data.selectedMap()))),
                button("refresh", tr("gui.fpsm.map_select.refresh"), true, () -> FPSMatch.sendToServer(
                        new ShopConfigToolActionC2SPacket(ShopConfigToolActionC2SPacket.Action.REFRESH, data.selectedType(), data.selectedMap()))),
                button("close", tr("gui.back"), true, this::onClose)));
        return List.of(canvas("shop.config", nodes).fill());
    }

    private void select(String type, String map) {
        if (!type.isBlank() && !map.isBlank()) FPSMatch.sendToServer(new ShopConfigToolActionC2SPacket(ShopConfigToolActionC2SPacket.Action.SELECT, type, map));
    }
}
