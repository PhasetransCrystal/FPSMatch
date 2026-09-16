package com.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.EditShopSlotMenu;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorValues;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.common.packet.shop.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Native form plus synchronized inventory. Invalid text remains a draft, never a silent clamp. */
public final class ModernEditShopSlotScreen extends ModernMenuScreen<EditShopSlotMenu> {
    private String ammo, price, group;
    private String originalAmmo, originalPrice, originalGroup;
    private ItemStack originalItem;
    private String status = "";
    private boolean saving, returning;
    private int ticks, lateResultTicks;
    private Draft discardDraft;
    private ItemStack discardItem;
    private final List<String> listeners = new ArrayList<>();
    private List<String> originalListeners;
    private String selectedListener = "";
    private boolean editingListeners;
    private record Draft(String ammo, String price, String group, List<String> listeners) {}
    public ModernEditShopSlotScreen(EditShopSlotMenu menu, Inventory inventory, Component title) {
        super(menu, Component.translatable("gui.fpsm.edit_shop_slot.title"));
        ammo = Integer.toString(menu.getAmmo()); price = Integer.toString(menu.getPrice()); group = Integer.toString(menu.getGroupId());
        listeners.addAll(menu.getListeners());
        baseline();
    }
    private void baseline() {
        originalAmmo = ammo; originalPrice = price; originalGroup = group;
        originalItem = menu.slots.get(0).getItem().copy();
        originalListeners = List.copyOf(listeners);
    }
    public boolean isSavePending() { return saving; }
    public boolean isSaveResultRelevant() { return saving || lateResultTicks > 0; }
    public boolean isReturnPending() { return returning; }
    public void applyReturnFailure(Component message) { returning = false; status = message.getString(); refresh(); }
    public void applySaveResult(MapRoomToastS2CPacket result) {
        if (!isSaveResultRelevant()) return;
        saving = false; lateResultTicks = 0; status = result.message().getString();
        if (!result.error()) { baseline(); openEditor(); }
        refresh();
    }
    @Override public void tick() {
        if ((saving || returning) && ++ticks >= 200) {
            status = tr(saving ? "gui.fpsm.shop_editor.save.timeout" : "gui.fpsm.shop_editor.return.timeout");
            if (saving) lateResultTicks = 40;
            saving = false; returning = false;
        } else if (lateResultTicks > 0) lateResultTicks--;
        if (discardDraft != null && (!discardDraft.equals(draft()) || !ItemStack.matches(discardItem, menu.slots.get(0).getItem()))) discardDraft = null;
        super.tick();
    }
    private boolean idle() { return !saving && !returning; }
    private boolean dirty() {
        return !ammo.equals(originalAmmo) || !price.equals(originalPrice) || !group.equals(originalGroup)
                || !listeners.equals(originalListeners)
                || !ItemStack.matches(originalItem, menu.slots.get(0).getItem());
    }
    private static boolean valid(String value, int min, int max) {
        try { int number = Integer.parseInt(value); return number >= min && number <= max; }
        catch (NumberFormatException invalid) { return false; }
    }
    private boolean valid() { return valid(ammo, 0, 999_999) && valid(price, 0, 1_000_000) && valid(group, -1, 999_999)
            && ShopEditorValues.validModules(listeners, menu.getAvailableListeners()); }
    private Draft draft() { return new Draft(ammo, price, group, List.copyOf(listeners)); }
    @Override protected List<Node> content() {
        boolean compact = width < 440 || height < 300;
        int margin=2,w=Math.max(1,width-4),left=compact?94:Math.min(128,width/3-4),top=compact?44:50;
        int actionHeight=compact?46:38, inventoryHeight=86, inventoryTop=height-actionHeight-88;
        int formHeight=Math.max(38,inventoryTop-top-4);
        List<Node> nodes=new ArrayList<>();
        nodes.add(title("title",getTitle().getString()).at(8,6,width-132,21));
        nodes.add(button("listener.tab",tr(editingListeners?"gui.fpsm.shop_editor.fields":"gui.fpsm.shop_editor.modules.title",listeners.size()),
                idle(),()->editingListeners=!editingListeners).at(width-120,6,112,21));
        nodes.add(text("identity",menu.getGameType()+" / "+menu.getMapName()+" / "+menu.getTeamName()+" / "+menu.getShopType()+" #"+(menu.getSlotNum()+1)).at(8,27,width-16,13));
        if (editingListeners) {
            nodes.addAll(listenerContent());
        } else {
        var product=menu.slots.get(0).getItem();
        nodes.add(actionCanvas("product",tr("gui.fpsm.shop_editor.item.replace.hint"),idle(),event->{
            if(!idle()||event.startsWith("hover"))return;
            if(menu.getCarried().isEmpty())status=tr("gui.fpsm.shop_editor.item.replace.empty");else clickSlot(0,0);
        },List.of(text("name",product.getHoverName().getString()).hint(product.getHoverName().getString()).at(4,2,left-8,14),
                inventoryItem("product.icon",product).at((left-30)/2f,17,30,30))).at(margin,top,left,formHeight));
        List<Node> form=new ArrayList<>();
        int formWidth=Math.max(1,w-left-6), cellWidth=formWidth/3;
        List<Node> fields=List.of(input("ammo",tr("gui.fpsm.dummy_ammo"),ammo,idle()&&menu.isGun(),v->ammo=v),
                input("price",tr("gui.fpsm.price"),price,idle(),v->price=v),
                input("group",tr("gui.fpsm.group"),group,idle(),v->group=v));
        for(int i=0;i<fields.size();i++) {
            Node entry=fields.get(i);
            if(compact) {
                form.add(entry.children().get(0).at(i*cellWidth+4,4,cellWidth-8,12));
                form.add(entry.children().get(1).at(i*cellWidth+4,18,cellWidth-8,Math.max(18,formHeight-24)));
            } else {
                int rowHeight=Math.min(26,formHeight/3), labelWidth=Math.min(82,Math.max(58,formWidth/3));
                form.add(entry.children().get(0).at(6,9+i*rowHeight,labelWidth,14));
                form.add(entry.children().get(1).at(6+labelWidth,5+i*rowHeight,formWidth-labelWidth-12,Math.max(18,rowHeight-3)));
            }
        }
        nodes.add(canvas("form",form).surface().at(margin+left+6,top,formWidth,formHeight));
        List<Node> inventory=new ArrayList<>();
        int cell=18,gridWidth=cell*9;
        inventory.add(text("caption",tr("container.inventory")).at(8,1,w-16,12));
        for(int index=1;index<menu.slots.size();index++) {
            int slotIndex=index;var stack=menu.slots.get(index).getItem();int i=index-1;
            inventory.add(actionCanvas("slot."+index,stack.getHoverName().getString(),idle(),event->{
                if(idle()&&!event.startsWith("hover"))clickSlot(slotIndex,event.equals("secondary")?1:0);
            },List.of(inventoryItem("slot.icon."+index,stack).at(1,1,16,16))).hint(stack.isEmpty()?"":stack.getHoverName().getString()+" ×"+stack.getCount())
                    .at((w-gridWidth)/2f+i%9*cell,14+i/9*cell,cell,cell));
        }
        nodes.add(canvas("inventory",inventory).surface().at(margin,inventoryTop,w,inventoryHeight));
        }
        nodes.add(text("status",!valid()?tr("gui.fpsm.shop_editor.input.invalid"):status.isEmpty()?tr(dirty()?"gui.fpsm.shop_editor.state.pending":"gui.fpsm.shop_editor.state.editing"):status)
                .at(8,height-actionHeight+4,compact?w-16:Math.max(1,w-224),14));
        nodes.add(button("save",tr("gui.fpsm.map_select.settings.save"),idle()&&dirty()&&valid(),this::save).at(width-216,height-26,100,20));
        nodes.add(button("back",tr(discardDraft==null?"gui.back":"gui.fpsm.shop_editor.discard.button"),idle(),this::onClose).at(width-110,height-26,100,20));
        return List.of(canvas("slot.editor",nodes).fill());
    }
    private List<Node> listenerContent() {
        List<Node> nodes = new ArrayList<>(), attached = new ArrayList<>();
        var available = menu.getAvailableListeners().stream().filter(name -> !listeners.contains(name)).toList();
        if (!available.contains(selectedListener)) selectedListener = available.isEmpty() ? "" : available.get(0);
        if (available.isEmpty()) nodes.add(muted("modules.none",tr("gui.fpsm.shop_editor.modules.none_available")).at(8,48,width-104,24));
        else nodes.add(select("modules.select",selectedListener,available.stream().map(name -> text(name,name)).toList(),
                name->{if(idle())selectedListener=name;}).enabled(idle()).at(8,48,width-104,24));
        nodes.add(button("modules.add",tr("gui.fpsm.shop_editor.modules.add"),idle()&&!selectedListener.isEmpty()&&listeners.size()<ShopEditorValues.MAX_MODULES,()->{
            if(!selectedListener.isEmpty()&&!listeners.contains(selectedListener))listeners.add(selectedListener);
        }).at(width-90,48,82,24));
        nodes.add(muted("modules.help",tr("gui.fpsm.shop_editor.modules.help")).at(8,76,width-16,16));
        for (String name : listeners) attached.add(canvas("module."+name,List.of(
                text("name",name).hint(name).at(0,0,width-122,26),
                button("remove",tr("gui.fpsm.shop_editor.modules.remove"),idle(),()->listeners.remove(name)).at(width-114,0,78,24))).size(-1,28));
        if (attached.isEmpty()) attached.add(muted("empty",tr("gui.fpsm.shop_editor.modules.empty")));
        nodes.add(scroll("modules.attached",attached).surface().at(8,96,width-16,Math.max(1,height-154)));
        return nodes;
    }
    @Override public void render(net.minecraft.client.gui.GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        super.render(graphics,mouseX,mouseY,partialTick);
        if(!menu.getCarried().isEmpty()) {graphics.renderItem(menu.getCarried(),mouseX-8,mouseY-8);graphics.renderItemDecorations(font,menu.getCarried(),mouseX-8,mouseY-8);}
    }
    private void save() {
        if (!idle() || !dirty() || !valid()) return;
        menu.setAmmo(Integer.parseInt(ammo)); menu.setPrice(Integer.parseInt(price)); menu.setGroupId(Integer.parseInt(group));
        saving = true; ticks = 0; lateResultTicks = 0; discardDraft = null;
        status = tr("gui.fpsm.shop_editor.state.saving");
        FPSMatch.sendToServer(new SaveShopSlotConfigurationC2SPacket(menu.containerId, menu.getAmmo(), menu.getPrice(), menu.getGroupId(), listeners));
    }
    private void openEditor() {
        returning = true; ticks = 0; discardDraft = null; status = tr("gui.fpsm.shop_editor.state.opening");
        FPSMatch.sendToServer(new OpenShopEditorC2SPacket(menu.getGameType(), menu.getMapName(), menu.getTeamName()));
    }
    @Override public void onClose() {
        if (!idle()) return;
        if ((dirty() || !valid()) && (discardDraft == null || !discardDraft.equals(draft())
                || !ItemStack.matches(discardItem, menu.slots.get(0).getItem()))) {
            discardDraft = draft(); discardItem = menu.slots.get(0).getItem().copy();
            status = tr("gui.fpsm.shop_editor.discard.confirm"); refresh(); return;
        }
        openEditor(); refresh();
    }
}
