package com.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import com.ptcrys.fpsmatch.common.client.screen.EditorShopContainer;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorNavigation;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorValues;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.shop.SetShopGroupsC2SPacket;
import com.ptcrys.fpsmatch.common.packet.shop.ShopGroupsResultS2CPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.*;

public final class ModernEditorShopScreen extends ModernMenuScreen<EditorShopContainer> {
    private boolean opening;
    private int ticks;
    private int selected;
    private String category="";
    private String status = "";
    private boolean batchMode, savingGroups;
    private final Set<Integer> checked = new LinkedHashSet<>();
    private String groupDraft = "-1";
    private static final java.util.concurrent.atomic.AtomicLong REQUESTS = new java.util.concurrent.atomic.AtomicLong();
    private long groupRequest;
    public ModernEditorShopScreen(EditorShopContainer menu, Inventory inventory, Component title) {
        super(menu, Component.translatable("gui.fpsm.shop_editor.title"));
        selected = ShopEditorNavigation.selectionFor(menu.getGameType(), menu.getMapName(), menu.getTeamName());
    }
    public boolean isSlotOpenPending() { return opening; }
    public void applySlotOpenFailure(Component message) { opening = false; status = message.getString(); refresh(); }
    @Override public void tick() {
        if (opening && ++ticks >= 200) applySlotOpenFailure(Component.translatable("gui.fpsm.shop_editor.open.timeout"));
        if (savingGroups && ++ticks >= 200) {
            savingGroups = false;
            status = tr("gui.fpsm.shop_editor.save.timeout");
        }
        super.tick();
    }
    @Override protected List<Node> content() {
        var layout=com.ptcrys.fpsmatch.common.client.screen.shop.modernui.ShopEditorLayoutModel.responsive(Math.max(1,width-16),Math.max(1,height-16));
        List<Node> nodes=new ArrayList<>(),categories=new ArrayList<>(),slots=new ArrayList<>(),properties=new ArrayList<>();
        if(!menu.getTypes().containsKey(category)) category=menu.getTypes().entrySet().stream()
                .filter(e->selected>=e.getValue().startIndex()&&selected<e.getValue().startIndex()+e.getValue().slotCount())
                .map(Map.Entry::getKey).findFirst().orElseGet(()->menu.getTypes().keySet().stream().findFirst().orElse(""));
        if (batchMode) return batchContent();
        for(var entry:menu.getTypes().entrySet()) {
            String type=entry.getKey();var info=entry.getValue();
            categories.add(button("type."+type,type,!opening,()->{category=type;selected=info.startIndex();}).selected(category.equals(type)).size(-1,24));
        }
        if(menu.getTypes().containsKey(category)) {
            var info=menu.getTypes().get(category);
            for(int i=0;i<info.slotCount();i++) {
                int index=info.startIndex()+i;var stack=menu.slots.get(index).getItem();var slot=menu.getAllSlots().get(index);
                int sw=Math.max(1,layout.slots().width()-16);
                if (slot == null) continue;
                slots.add(actionCanvas("slot."+index,stack.getHoverName().getString(),!opening,event->{
                    if(!event.startsWith("hover")) {selected=index;ShopEditorNavigation.rememberSelection(menu.getGameType(),menu.getMapName(),menu.getTeamName(),index);}
                },List.of(item("icon."+index,stack).at(5,5,28,28),text("name",(i+1)+"  "+stack.getHoverName().getString()).at(39,2,sw-44,20),
                        text("price","$"+slot.getDefaultCost()).at(39,22,sw-44,16))).selected(selected==index).size(-1,42));
            }
        }
        properties.add(title("title",tr("gui.fpsm.shop_editor.properties")));
        if(selected>=0&&selected<menu.getAllSlots().size()&&menu.getAllSlots().get(selected)!=null) {
            var slot=menu.getAllSlots().get(selected);var stack=menu.slots.get(selected).getItem();
            properties.add(text("name",stack.getHoverName().getString()));
            properties.add(text("type",category+" #"+(selected+1)));
            properties.add(text("price",tr("gui.fpsm.price")+": $"+slot.getDefaultCost()));
            properties.add(text("ammo",tr("gui.fpsm.dummy_ammo")+": "+slot.getAmmoCount()));
            properties.add(text("group",tr("gui.fpsm.group")+": "+slot.getGroupId()));
        }
        nodes.add(place(canvas("header",List.of(title("title",getTitle().getString()).at(0,0,width-160,20),
                text("identity",menu.getGameType()+" / "+menu.getMapName()+" / "+menu.getTeamName()).at(0,22,width-32,14),
                button("batch",tr("gui.fpsm.shop_editor.batch.title"),!opening,()->{batchMode=true;status="";}).at(width-154,0,126,22))),layout.header()));
        Node categoryView=layout.compact()?select("categories",category,categories.stream().map(n->text(n.key().substring(5),n.text())).toList(),v->{category=v;selected=menu.getTypes().get(v).startIndex();}):scroll("categories",categories);
        nodes.add(place(categoryView.surface(),layout.categories()));
        nodes.add(place(scroll("slots",slots).surface(),layout.slots()));
        if (layout.properties().height() < 56 && properties.size() > 1) {
            int columnWidth = Math.max(1, (layout.properties().width() - 4) / 3);
            List<Node> summary = new ArrayList<>();
            for (int i = 1; i < properties.size(); i++) {
                Node property = properties.get(i);
                summary.add(muted(property.key(), property.text()).hint(property.text())
                        .at((i - 1) % 3 * columnWidth, (i - 1) / 3 * 14, columnWidth, 14));
            }
            nodes.add(place(canvas("properties", summary).surface(), layout.properties()));
        } else {
            nodes.add(place(scroll("properties",properties).surface(),layout.properties()));
        }
        nodes.add(place(row("actions",text("status",status),button("edit",tr("gui.fpsm.shop_editor.edit_selected"),!opening&&selected>=0&&selected<menu.getAllSlots().size(),()->open(selected)),
                button("back",tr("gui.back"),!opening,this::onClose)),layout.actions()));
        return List.of(canvas("editor",nodes).fill());
    }
    private List<Node> batchContent() {
        List<Node> nodes = new ArrayList<>(), slots = new ArrayList<>();
        nodes.add(title("title",tr("gui.fpsm.shop_editor.batch.title")).at(8,6,width-148,20));
        nodes.add(button("done",tr("gui.fpsm.shop_editor.batch.done"),!savingGroups,()->{batchMode=false;status="";}).at(width-132,6,124,22));
        nodes.add(select("categories",category,menu.getTypes().keySet().stream().map(type->text(type,type)).toList(),
                value->category=value).enabled(!savingGroups).at(8,34,Math.max(60,width-198),24));
        nodes.add(button("select.category",tr("gui.fpsm.shop_editor.batch.select_category"),!savingGroups&&menu.getTypes().containsKey(category),this::selectCategory).at(width-182,34,100,24));
        nodes.add(button("clear",tr("gui.fpsm.shop_editor.batch.clear"),!savingGroups&&!checked.isEmpty(),()->checked.clear()).at(width-78,34,70,24));
        nodes.add(muted("selection",tr("gui.fpsm.shop_editor.batch.selection",checked.size(),ShopEditorValues.MAX_SELECTION)).at(8,62,width-16,16));
        var info = menu.getTypes().get(category);
        if (info != null) for (int i=0;i<info.slotCount();i++) {
            int index=info.startIndex()+i;
            var slot=menu.getAllSlots().get(index);
            if (slot==null) continue;
            var stack=menu.slots.get(index).getItem();
            String name=stack.isEmpty()?tr("gui.fpsm.shop_editor.batch.empty"):stack.getHoverName().getString();
            slots.add(actionCanvas("slot."+index,name,!savingGroups,event->{
                if (savingGroups||event.startsWith("hover")) return;
                if(!checked.remove(index)&&checked.size()<ShopEditorValues.MAX_SELECTION) checked.add(index);
            },List.of(text("check",checked.contains(index)?"☑":"☐").at(4,6,20,20),
                    item("icon",stack).at(28,4,26,26),
                    text("name",(i+1)+"  "+name).at(60,1,width-96,16),
                    muted("group",tr("gui.fpsm.group")+": "+slot.getGroupId()).at(60,18,width-96,14)))
                    .selected(checked.contains(index)).size(-1,36));
        }
        nodes.add(scroll("slots",slots).surface().at(8,82,width-16,Math.max(1,height-154)));
        nodes.add(text("group.label",tr("gui.fpsm.group")).at(8,height-65,56,20));
        nodes.add(field("group",groupDraft,!savingGroups,value->groupDraft=value).at(68,height-66,Math.max(50,width-204),24));
        nodes.add(button("apply",tr("gui.fpsm.shop_editor.batch.apply"),!savingGroups&&!checked.isEmpty()&&validGroupDraft(),this::saveGroups).at(width-128,height-66,120,24));
        String feedback=!validGroupDraft()?tr("gui.fpsm.shop_editor.batch.invalid"):status.isEmpty()?tr("gui.fpsm.shop_editor.batch.help"):status;
        nodes.add(text("status",feedback).hint(feedback).at(8,height-36,width-16,26));
        return List.of(canvas("batch.editor",nodes).fill());
    }
    private boolean validGroupDraft() {
        try { return ShopEditorValues.validGroup(Integer.parseInt(groupDraft)); }
        catch (NumberFormatException invalid) { return false; }
    }
    private void selectCategory() {
        var info=menu.getTypes().get(category);
        if (savingGroups||info==null) return;
        for(int i=0;i<info.slotCount()&&checked.size()<ShopEditorValues.MAX_SELECTION;i++) {
            int index=info.startIndex()+i;
            if(menu.getAllSlots().get(index)!=null) checked.add(index);
        }
    }
    private void saveGroups() {
        if(savingGroups||checked.isEmpty()||!validGroupDraft()) return;
        savingGroups=true; ticks=0; groupRequest=REQUESTS.incrementAndGet();
        status=tr("gui.fpsm.shop_editor.state.saving");
        FPSMatch.sendToServer(new SetShopGroupsC2SPacket(menu.containerId,groupRequest,Integer.parseInt(groupDraft),checked.stream().mapToInt(Integer::intValue).toArray()));
    }
    public void applyGroupResult(ShopGroupsResultS2CPacket packet) {
        if(packet.containerId()!=menu.containerId||packet.requestId()!=groupRequest) return;
        savingGroups=false;
        if(packet.result().success()) {
            menu.applyGroups(packet.indices(),packet.groupId());
            status=tr("gui.fpsm.shop_editor.batch.success",packet.indices().length,packet.groupId());
        } else status=tr(packet.result().translationKey());
        refresh();
    }
    private Node place(Node node,com.ptcrys.fpsmatch.common.client.screen.shop.modernui.ShopEditorLayoutModel.Rect rect) {
        return node.at(rect.x()+8,rect.y()+8,Math.max(1,rect.width()-4),Math.max(1,rect.height()-4));
    }
    private void open(int index) {
        if (opening || index < 0 || index >= menu.getAllSlots().size()) return;
        selected = index; opening = true; ticks = 0; status = tr("gui.fpsm.shop_editor.state.opening");
        ShopEditorNavigation.rememberSelection(menu.getGameType(), menu.getMapName(), menu.getTeamName(), index);
        clickSlot(index, 0);
    }
    @Override public void onClose() {
        if (opening || savingGroups) return;
        if (batchMode) { batchMode=false; status=""; refresh(); return; }
        super.onClose(); ShopEditorNavigation.returnFromEditor(menu.getGameType(), menu.getMapName(), menu.getTeamName());
    }
}
