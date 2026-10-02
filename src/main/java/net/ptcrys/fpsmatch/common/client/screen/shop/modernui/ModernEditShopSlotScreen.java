package net.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorRequest;
import net.ptcrys.fpsmatch.common.packet.shop.*;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorResult;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorValues;
import net.ptcrys.fpsmatch.compat.gun.GunCompatManager;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Local product draft; inventory cells copy samples without performing container clicks. */
public final class ModernEditShopSlotScreen extends ModernShopScreen {

    private String ammo, price, group;
    private String originalAmmo, originalPrice, originalGroup;
    private ItemStack originalItem;
    private String status = "";
    private final ModernEditorShopScreen editor;
    private final String shopType;
    private final int slotNum;
    private final Object connection;
    private final ShopEditorRequest request;
    private ShopEditorSnapshot loaded;
    private ItemStack item;
    private int maxBuyCount;
    private boolean conflict, confirmReload;
    private SaveShopSlotConfigurationC2SPacket submitted;
    private Draft reloadDraft;
    private ItemStack reloadItem;
    private Draft discardDraft;
    private ItemStack discardItem;
    private final List<String> listeners = new ArrayList<>();
    private List<String> originalListeners;
    private String selectedListener = "";
    private boolean editingListeners;

    private record Draft(String ammo, String price, String group, List<String> listeners) {}

    public ModernEditShopSlotScreen(ModernEditorShopScreen editor, String shopType, int slotNum) {
        super(Component.translatable("gui.fpsm.edit_shop_slot.title"), editor);
        this.editor = editor;
        this.shopType = shopType;
        this.slotNum = slotNum;
        loaded = editor.snapshot();
        request = new ShopEditorRequest(loaded.target());
        connection = Minecraft.getInstance().getConnection();
        loadSlot(loaded);
    }

    private ShopEditorSnapshot.Slot slot(ShopEditorSnapshot data) {
        return data.categories().stream().filter(category -> category.name().equals(shopType)).findFirst()
                .filter(category -> slotNum >= 0 && slotNum < category.slots().size()).map(category -> category.slots().get(slotNum)).orElse(null);
    }

    private void loadSlot(ShopEditorSnapshot data) {
        var slot = slot(data);
        if (slot == null) {
            editor.applySnapshot(data);
            Minecraft.getInstance().setScreen(editor);
            return;
        }
        loaded = data;
        item = slot.item();
        ammo = Integer.toString(slot.ammo());
        price = Integer.toString(slot.price());
        group = Integer.toString(slot.group());
        maxBuyCount = slot.maxBuyCount();
        listeners.clear();
        listeners.addAll(slot.modules());
        conflict = false;
        discardDraft = null;
        confirmReload = false;
        baseline();
    }

    private void baseline() {
        originalAmmo = ammo;
        originalPrice = price;
        originalGroup = group;
        originalItem = item.copy();
        originalListeners = List.copyOf(listeners);
    }

    public void applyResult(ShopEditorResultS2CPacket result) {
        if (!request.accepts(result)) return;
        boolean unchangedSinceSubmit = submitted != null && sameDraft(submitted.draft());
        request.complete();
        submitted = null;
        status = tr(result.result().translationKey());
        if (result.snapshot() != null) editor.applySnapshot(result.snapshot());
        if (result.result() == ShopEditorResult.CONFLICT) conflict = true;
        if (result.result() == ShopEditorResult.SUCCESS && result.snapshot() != null) {
            if (result.operation() == ShopEditorResultS2CPacket.Operation.LOAD) {
                if (reloadDraft != null && reloadDraft.equals(draft()) && ItemStack.matches(reloadItem, item)) loadSlot(result.snapshot());
                else {
                    conflict = true;
                    status = tr("gui.fpsm.shop_editor.save.conflict");
                }
                reloadDraft = null;
                reloadItem = null;
            } else if (unchangedSinceSubmit) Minecraft.getInstance().setScreen(editor);
            else {
                // A late success acknowledges the submitted draft, not edits made after its timeout.
                var saved = slot(result.snapshot());
                if (saved != null) {
                    loaded = result.snapshot();
                    originalAmmo = Integer.toString(saved.ammo());
                    originalPrice = Integer.toString(saved.price());
                    originalGroup = Integer.toString(saved.group());
                    originalItem = saved.item();
                    originalListeners = saved.modules();
                }
            }
        }
        refresh();
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().getConnection() != connection) {
            Minecraft.getInstance().setScreen(null);
            return;
        }
        if (request.tick()) status = tr(submitted == null ? "gui.fpsm.shop_editor.load.timeout" : "gui.fpsm.shop_editor.save.timeout");
        if (discardDraft != null && (!discardDraft.equals(draft()) || !ItemStack.matches(discardItem, item))) {
            discardDraft = null;
            confirmReload = false;
        }
        refresh();
    }

    private boolean idle() {
        return !request.waiting();
    }

    private boolean dirty() {
        return !ammo.equals(originalAmmo) || !price.equals(originalPrice) || !group.equals(originalGroup) || !listeners.equals(originalListeners) || !ItemStack.matches(originalItem, item);
    }

    private static boolean valid(String value, int min, int max) {
        try {
            int number = Integer.parseInt(value);
            return number >= min && number <= max;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private boolean valid() {
        return !item.isEmpty() && valid(ammo, 0, 999_999) && valid(price, 0, 1_000_000) && valid(group, -1, 999_999) && ShopEditorValues.validModules(listeners, loaded.availableModules());
    }

    private Draft draft() {
        return new Draft(ammo, price, group, List.copyOf(listeners));
    }

    @Override
    protected List<Node> content() {
        var layout = ShopEditorLayoutModel.slot(layoutWidth(), layoutHeight());
        List<Node> nodes = new ArrayList<>();
        var target = loaded.target();
        nodes.add(header(getTitle().getString(), target.gameType() + " / " + target.mapName() + " / " + target.teamName() + " / " + shopType + " #" + (slotNum + 1),
                button("listener.tab", tr(editingListeners ? "gui.fpsm.shop_editor.fields" : "gui.fpsm.shop_editor.modules.title", listeners.size()),
                        idle(), () -> editingListeners = !editingListeners)));
        if (editingListeners) {
            nodes.add(place(scroll("modules.body", listenerContent(layout.frame().body().width())), layout.frame().body()));
        } else {
            var product = item;
            int fieldWidth = layout.fields().width();
            List<Node> fields = new ArrayList<>();
            fields.add(actionCanvas("product", tr("gui.fpsm.shop_editor.item.replace.hint"), idle(), event -> {
                if (!idle() || event.startsWith("hover")) return;
                var player = Minecraft.getInstance().player;
                if (player != null) replaceItem(player.getMainHandItem());
            }, List.of(inventoryItem("product.icon", product).at(6, 6, 28, 28),
                    text("name", product.getHoverName().getString()).hint(product.getHoverName().getString()).at(40, 4, fieldWidth - 48, 32))).size(-1, 40));
            List<Node> form = new ArrayList<>();
            int labelWidth = Math.min(86, fieldWidth / 2);
            List<Node> entries = List.of(input("ammo", tr("gui.fpsm.dummy_ammo"), ammo, idle() && GunCompatManager.isGun(item), v -> ammo = v),
                    input("price", tr("gui.fpsm.price"), price, idle(), v -> price = v),
                    input("group", tr("gui.fpsm.group"), group, idle(), v -> group = v));
            for (int i = 0; i < entries.size(); i++) {
                Node entry = entries.get(i);
                form.add(entry.children().get(0).hint(entry.children().get(0).text()).at(0, i * 28, labelWidth, 24));
                form.add(entry.children().get(1).at(labelWidth + 4, i * 28, fieldWidth - labelWidth - 8, 24));
            }
            fields.add(canvas("form", form).size(-1, 84));
            List<Node> inventory = new ArrayList<>();
            int cell = layout.cellSize(), gridWidth = cell * 9, inventoryWidth = layout.inventory().width();
            inventory.add(muted("caption", tr("container.inventory")).at(0, 0, inventoryWidth, 18));
            for (int i = 0; i < 36; i++) {
                int slotIndex = i < 27 ? i + 9 : i - 27;
                var player = Minecraft.getInstance().player;
                var stack = player == null ? ItemStack.EMPTY : player.getInventory().getItem(slotIndex).copy();
                int index = i;
                inventory.add(actionCanvas("slot." + index, stack.getHoverName().getString(), idle(), event -> {
                    var currentPlayer = Minecraft.getInstance().player;
                    if (idle() && !event.startsWith("hover") && currentPlayer != null) replaceItem(currentPlayer.getInventory().getItem(slotIndex));
                }, List.of(inventoryItem("slot.icon." + index, stack).at(2, 2, cell - 4, cell - 4))).hint(stack.isEmpty() ? "" : stack.getHoverName().getString() + " ×" + stack.getCount())
                        .at((inventoryWidth - gridWidth) / 2f + i % 9 * cell, 22 + i / 9 * cell + (i >= 27 ? 4 : 0), cell, cell));
            }
            Node inventoryView = canvas("inventory", inventory).size(-1, 30 + cell * 4);
            if (layout.split()) {
                nodes.add(place(scroll("fields", fields), layout.fields()));
                nodes.add(place(scroll("inventory.body", List.of(inventoryView)), layout.inventory()));
            } else {
                fields.add(inventoryView);
                nodes.add(place(scroll("fields", fields), layout.frame().body()));
            }
        }
        nodes.add(footer(!status.isEmpty() ? status : !valid() ? tr("gui.fpsm.shop_editor.input.invalid") : tr(dirty() ? "gui.fpsm.shop_editor.state.pending" : "gui.fpsm.shop_editor.state.editing"),
                iconButton("reload", "rotate-cw", tr(confirmReload ? "gui.fpsm.shop_editor.reload.confirm" : "gui.fpsm.map_select.refresh"), idle(), this::reload),
                button("save", tr("gui.fpsm.map_select.settings.save"), idle() && !conflict && dirty() && valid(), this::save),
                button("back", tr(discardDraft == null || confirmReload ? "gui.back" : "gui.fpsm.shop_editor.discard.button"), idle(), this::onClose)));
        return List.of(canvas("slot.editor", nodes).fill());
    }

    private List<Node> listenerContent(int w) {
        List<Node> nodes = new ArrayList<>(), attached = new ArrayList<>();
        var available = loaded.availableModules().stream().filter(name -> !listeners.contains(name)).toList();
        if (!available.contains(selectedListener)) selectedListener = available.isEmpty() ? "" : available.get(0);
        List<Node> toolbar = new ArrayList<>();
        if (available.isEmpty()) toolbar.add(muted("modules.none", tr("gui.fpsm.shop_editor.modules.none_available")).at(0, 0, w - 90, 24));
        else toolbar.add(select("modules.select", selectedListener, available.stream().map(name -> text(name, name)).toList(),
                name -> { if (idle()) selectedListener = name; }).enabled(idle()).at(0, 0, w - 90, 24));
        toolbar.add(button("modules.add", tr("gui.fpsm.shop_editor.modules.add"), idle() && !selectedListener.isEmpty() && listeners.size() < ShopEditorValues.MAX_MODULES, () -> {
            if (!selectedListener.isEmpty() && !listeners.contains(selectedListener)) listeners.add(selectedListener);
        }).at(w - 82, 0, 82, 24));
        nodes.add(button("modules.manage", tr("gui.fpsm.listener_editor.manage"), idle(), () -> openModuleCatalog(selectedListener)).size(-1, 24));
        nodes.add(canvas("modules.toolbar", toolbar).size(-1, 30));
        for (String name : listeners) attached.add(canvas("module." + name, List.of(
                text("name", name).hint(name).at(0, 0, w - 64, 26),
                iconButton("inspect", "sliders-horizontal", tr("gui.fpsm.listener_editor.inspect"), idle(), () -> openModuleCatalog(name)).at(w - 56, 0, 24, 24),
                iconButton("remove", "x", tr("gui.fpsm.shop_editor.modules.remove"), idle(), () -> listeners.remove(name)).at(w - 24, 0, 24, 24))).size(-1, 28));
        if (attached.isEmpty()) attached.add(muted("empty", tr("gui.fpsm.shop_editor.modules.empty")));
        nodes.addAll(attached);
        return nodes;
    }

    private void openModuleCatalog(String name) {
        if (idle()) Minecraft.getInstance().setScreen(new ModernListenerModuleScreen(this, loaded.target(), name, item));
    }

    public void applyModuleCatalog(ShopEditorSnapshot fresh) {
        if (fresh == null || !loaded.target().equals(fresh.target())) return;
        editor.applySnapshot(fresh);
        if (!loaded.revision().equals(fresh.revision())) {
            conflict = true;
            status = tr("gui.fpsm.listener_editor.slot_changed");
        }
        // Refresh names without advancing the version of an unsaved slot draft.
        loaded = new ShopEditorSnapshot(loaded.target(), loaded.revision(), loaded.categories(), fresh.availableModules());
    }

    private void replaceItem(ItemStack sample) {
        if (sample.isEmpty()) {
            status = tr("gui.fpsm.shop_editor.item.replace.empty");
            return;
        }
        item = sample.copy();
        ammo = Integer.toString(GunCompatManager.findProvider(item).getMaxDummyAmmo(item));
        status = "";
    }

    private ShopEditorSnapshot.Slot configuration() {
        return new ShopEditorSnapshot.Slot(item, Integer.parseInt(price), Integer.parseInt(ammo), Integer.parseInt(group), maxBuyCount, listeners);
    }

    private boolean sameDraft(ShopEditorSnapshot.Slot saved) {
        return valid() && ItemStack.matches(item, saved.item()) && Integer.parseInt(price) == saved.price() && Integer.parseInt(ammo) == saved.ammo() && Integer.parseInt(group) == saved.group() && listeners.equals(saved.modules());
    }

    private void save() {
        if (!idle() || conflict || !dirty() || !valid()) return;
        if (submitted != null && sameDraft(submitted.draft())) request.retry();
        else submitted = new SaveShopSlotConfigurationC2SPacket(request.begin(ShopEditorResultS2CPacket.Operation.SAVE_SLOT), loaded.target(), loaded.revision(), shopType, slotNum, configuration());
        discardDraft = null;
        status = tr("gui.fpsm.shop_editor.state.saving");
        FPSMatch.sendToServer(submitted);
    }

    private void reload() {
        if (!idle()) return;
        if (dirty() && (!confirmReload || discardDraft == null || !discardDraft.equals(draft()) || !ItemStack.matches(discardItem, item))) {
            confirmReload = true;
            discardDraft = draft();
            discardItem = item.copy();
            status = tr("gui.fpsm.shop_editor.reload.confirm");
            return;
        }
        submitted = null;
        discardDraft = null;
        confirmReload = false;
        status = tr("gui.fpsm.shop_editor.state.loading");
        reloadDraft = draft();
        reloadItem = item.copy();
        FPSMatch.sendToServer(new OpenShopEditorC2SPacket(request.begin(ShopEditorResultS2CPacket.Operation.LOAD), loaded.target()));
    }

    @Override
    public void onClose() {
        if (!idle()) return;
        if ((dirty() || !valid()) && (confirmReload || discardDraft == null || !discardDraft.equals(draft()) || !ItemStack.matches(discardItem, item))) {
            confirmReload = false;
            discardDraft = draft();
            discardItem = item.copy();
            status = tr("gui.fpsm.shop_editor.discard.confirm");
            refresh();
            return;
        }
        Minecraft.getInstance().setScreen(editor);
    }
}
