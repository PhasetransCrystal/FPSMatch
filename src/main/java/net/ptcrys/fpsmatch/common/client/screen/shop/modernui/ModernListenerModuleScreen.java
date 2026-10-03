package net.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorRequest;
import net.ptcrys.fpsmatch.common.packet.shop.ListenerModuleActionC2SPacket;
import net.ptcrys.fpsmatch.common.packet.shop.ListenerModuleActionC2SPacket.Action;
import net.ptcrys.fpsmatch.common.packet.shop.ListenerModuleResultS2CPacket;
import net.ptcrys.fpsmatch.common.shop.editor.ListenerModuleSnapshot;
import net.ptcrys.fpsmatch.common.shop.editor.ListenerModuleSnapshot.Definition;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorResult;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Catalog and shared definition drafts remain separate from the parent's slot draft. */
public final class ModernListenerModuleScreen extends ModernShopScreen {

    private final ModernEditShopSlotScreen slotEditor;
    private final ShopEditorSnapshot.Target target;
    private final Object connection;
    private final ItemStack sample;
    private ListenerModuleSnapshot catalog;
    private ListenerModuleActionC2SPacket submitted;
    private boolean waiting, editing, creating, changedSample, discard, conflict;
    private int ticks;
    private String selected, query = "", name = "", defaultCost = "0", changedCost = "0", status = "";
    private ItemStack defaultItem = ItemStack.EMPTY, changedItem = ItemStack.EMPTY;
    private Definition baseline, confirmedUpdate;
    private String confirmedDelete = "";

    public ModernListenerModuleScreen(ModernEditShopSlotScreen parent, ShopEditorSnapshot.Target target, String selected, ItemStack sample) {
        super(Component.translatable("gui.fpsm.listener_editor.title"), parent);
        this.slotEditor = parent;
        this.target = target;
        this.selected = selected;
        this.sample = sample.copy();
        connection = Minecraft.getInstance().getConnection();
        send(Action.LOAD, new Definition("", ItemStack.EMPTY, 0, ItemStack.EMPTY, 0));
    }

    private boolean idle() {
        return !waiting;
    }

    private ListenerModuleSnapshot.Module selectedModule() {
        return catalog == null ? null : catalog.modules().stream().filter(module -> module.name().equals(selected)).findFirst().orElse(null);
    }

    private Definition draft() {
        try {
            return new Definition(name, defaultItem, Integer.parseInt(defaultCost), changedItem, Integer.parseInt(changedCost));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean same(Definition a, Definition b) {
        return a != null && b != null && a.name().equals(b.name()) && a.defaultCost() == b.defaultCost() && a.changedCost() == b.changedCost() && ItemStack.matches(a.defaultItem(), b.defaultItem()) && ItemStack.matches(a.changedItem(), b.changedItem());
    }

    private boolean valid() {
        var draft = draft();
        return draft != null && (!creating || net.ptcrys.fpsmatch.common.shop.editor.ListenerModuleService.validName(name)) && !defaultItem.isEmpty() && !changedItem.isEmpty() && draft.defaultCost() >= 0 && draft.defaultCost() <= 1_000_000 && draft.changedCost() >= 0 && draft.changedCost() <= 1_000_000;
    }

    private boolean dirty() {
        return !same(baseline, draft());
    }

    public void applyResult(ListenerModuleResultS2CPacket packet) {
        if (submitted == null || packet.requestId() != submitted.requestId() || packet.action() != submitted.action() || !target.equals(packet.target())) return;
        if (packet.shop() != null && !target.equals(packet.shop().target())) return;
        boolean unchanged = same(submitted.draft(), draft());
        var sent = submitted;
        waiting = false;
        submitted = null;
        status = tr(packet.result().translationKey());
        if (packet.result() == ShopEditorResult.SUCCESS) status = packet.action() == Action.LOAD ? "" : tr("gui.fpsm.listener_editor." + packet.action().name().toLowerCase(Locale.ROOT) + ".success");
        if (packet.catalog() != null) catalog = packet.catalog();
        slotEditor.applyModuleCatalog(packet.shop());
        if (packet.result() == ShopEditorResult.CONFLICT) conflict = true;
        if (packet.result() == ShopEditorResult.SUCCESS) {
            if (packet.action() == Action.CREATE || packet.action() == Action.UPDATE) {
                selected = sent.draft().name();
                var saved = selectedModule();
                if (saved != null) baseline = saved.definition();
                if (unchanged) editing = false;
                else if (name.equals(sent.draft().name())) creating = false;
            } else if (packet.action() == Action.DELETE) selected = "";
        }
        confirmedUpdate = null;
        confirmedDelete = "";
        refresh();
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().getConnection() != connection) {
            Minecraft.getInstance().setScreen(null);
            return;
        }
        if (waiting && ++ticks >= 200) {
            waiting = false;
            status = tr("gui.fpsm.listener_editor.timeout");
        }
        if (confirmedUpdate != null && !same(confirmedUpdate, draft())) confirmedUpdate = null;
        refresh();
    }

    private void send(Action action, Definition value) {
        if (!idle()) return;
        String revision = catalog == null ? "" : catalog.revision();
        if (submitted == null || submitted.action() != action || !same(submitted.draft(), value) || !submitted.revision().equals(revision))
            submitted = new ListenerModuleActionC2SPacket(ShopEditorRequest.nextId(), target, action, revision, value);
        waiting = true;
        ticks = 0;
        status = tr(action == Action.LOAD ? "gui.fpsm.shop_editor.state.loading" : "gui.fpsm.shop_editor.state.saving");
        FPSMatch.sendToServer(submitted);
    }

    private void beginEdit(boolean create) {
        if (!idle() || catalog == null) return;
        var module = selectedModule();
        if (!create && (module == null || !module.editable())) return;
        creating = create;
        conflict = false;
        editing = true;
        changedSample = false;
        discard = false;
        confirmedUpdate = null;
        confirmedDelete = "";
        var player = Minecraft.getInstance().player;
        Definition initial = create ? new Definition("changeItem_custom", sample, 0, player == null ? ItemStack.EMPTY : player.getMainHandItem(), 0) : module.definition();
        name = initial.name();
        defaultItem = initial.defaultItem();
        changedItem = initial.changedItem();
        defaultCost = Integer.toString(initial.defaultCost());
        changedCost = Integer.toString(initial.changedCost());
        baseline = initial;
        status = "";
    }

    private void saveDefinition() {
        if (!idle() || conflict || !valid() || (!creating && !dirty())) return;
        var module = selectedModule();
        if (!creating && module != null && !module.references().isEmpty() && !same(confirmedUpdate, draft())) {
            confirmedUpdate = draft();
            status = tr("gui.fpsm.listener_editor.update_warning", module.references().size());
            return;
        }
        send(creating ? Action.CREATE : Action.UPDATE, draft());
    }

    private void deleteDefinition() {
        var module = selectedModule();
        if (!idle() || module == null || !module.editable() || !module.references().isEmpty()) return;
        if (!confirmedDelete.equals(selected)) {
            confirmedDelete = selected;
            status = tr("gui.fpsm.listener_editor.delete_warning", selected);
            return;
        }
        send(Action.DELETE, module.definition());
    }

    @Override
    protected List<Node> content() {
        var layout = ShopEditorLayoutModel.slot(layoutWidth(), layoutHeight());
        List<Node> nodes = new ArrayList<>();
        nodes.add(header(getTitle().getString(), target.gameType() + " / " + target.mapName() + " / " + tr("gui.fpsm.listener_editor.shared"),
                button("new", tr("gui.fpsm.listener_editor.new"), idle() && catalog != null && !editing, () -> beginEdit(true))));
        if (editing) {
            List<Node> fields = definitionFields(layout.fields().width());
            Node inventory = inventory(layout.inventory().width(), layout.cellSize());
            if (layout.split()) {
                nodes.add(place(scroll("definition.fields", fields), layout.fields()));
                nodes.add(place(scroll("definition.inventory", List.of(inventory)), layout.inventory()));
            } else {
                fields.add(inventory);
                nodes.add(place(scroll("definition.body", fields), layout.frame().body()));
            }
        } else nodes.add(place(scroll("catalog.body", catalogContent(layout.frame().body().width())), layout.frame().body()));
        var module = selectedModule();
        nodes.add(footer(!status.isEmpty() ? status : editing && !valid() ? tr("gui.fpsm.shop_editor.input.invalid") : "",
                iconButton("reload", "rotate-cw", tr("gui.fpsm.map_select.refresh"), idle(), this::reload),
                button("primary", tr(editing ? confirmedUpdate != null ? "gui.fpsm.listener_editor.confirm_update" : "gui.fpsm.map_select.settings.save" : "gui.fpsm.listener_editor.edit"),
                        idle() && (editing ? !conflict && valid() && (creating || dirty()) : module != null && module.editable()), () -> {
                            if (editing) saveDefinition();
                            else beginEdit(false);
                        }),
                button("back", tr(discard ? "gui.fpsm.shop_editor.discard.button" : "gui.back"), idle(), this::onClose)));
        return List.of(canvas("module.editor", nodes).fill());
    }

    private List<Node> catalogContent(int w) {
        List<Node> result = new ArrayList<>();
        result.add(input("search", tr("gui.fpsm.listener_editor.search"), query, idle(), value -> {
            query = value;
            confirmedDelete = "";
        }).size(-1, 44));
        var matches = catalog == null ? List.<ListenerModuleSnapshot.Module>of() : catalog.modules().stream().filter(module -> module.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        if (matches.stream().noneMatch(module -> module.name().equals(selected))) selected = matches.isEmpty() ? "" : matches.get(0).name();
        if (matches.isEmpty()) result.add(muted("empty", tr("gui.fpsm.listener_editor.empty")).size(-1, 24));
        else result.add(select("catalog.select", selected, matches.stream().map(module -> text(module.name(), module.name())).toList(), value -> {
            selected = value;
            status = "";
            confirmedDelete = "";
        }).enabled(idle()).size(-1, 24));
        var module = selectedModule();
        if (module == null) return result;
        result.add(muted("type", tr(module.editable() ? "gui.fpsm.listener_editor.change_item" : "gui.fpsm.listener_editor.read_only")).size(-1, 20));
        result.add(muted("priority", tr("gui.fpsm.listener_editor.priority", module.priority())).size(-1, 20));
        if (module.definition() != null) {
            result.add(product("preview.default", module.definition().defaultItem(), tr("gui.fpsm.listener_editor.default_item"), w, null));
            result.add(muted("default.cost", tr("gui.fpsm.listener_editor.cost_value", module.definition().defaultCost())).size(-1, 20));
            result.add(product("preview.changed", module.definition().changedItem(), tr("gui.fpsm.listener_editor.changed_item"), w, null));
            result.add(muted("changed.cost", tr("gui.fpsm.listener_editor.cost_value", module.definition().changedCost())).size(-1, 20));
        }
        result.add(canvas("references.header", List.of(
                text("references.count", tr("gui.fpsm.listener_editor.references", module.references().size())).at(0, 0, w - 32, 24),
                iconButton("delete", "x", tr(confirmedDelete.equals(selected) ? "gui.fpsm.listener_editor.confirm_delete" : "gui.fpsm.listener_editor.delete"),
                        idle() && module.editable() && module.references().isEmpty(), this::deleteDefinition).at(w - 24, 0, 24, 24)))
                .size(-1, 28));
        for (int i = 0; i < module.references().size(); i++) {
            var usage = module.references().get(i);
            String label = usage.target().gameType() + " / " + usage.target().mapName() + " / " + usage.target().teamName() + " / " + usage.type() + " #" + (usage.index() + 1);
            result.add(text("reference." + i, label).hint(label + " / " + tr("gui.fpsm.group") + ": " + usage.group()).size(-1, 22));
        }
        return result;
    }

    private List<Node> definitionFields(int w) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(input("name", tr("gui.fpsm.listener_editor.name"), name, idle() && creating, value -> {
            name = value;
            discard = false;
        }).size(-1, 44));
        nodes.add(product("default.product", defaultItem, tr("gui.fpsm.listener_editor.default_item"), w, () -> {
            changedSample = false;
            discard = false;
        }));
        nodes.add(input("default.price", tr("gui.fpsm.listener_editor.default_cost"), defaultCost, idle(), value -> {
            defaultCost = value;
            discard = false;
        }).size(-1, 44));
        nodes.add(product("changed.product", changedItem, tr("gui.fpsm.listener_editor.changed_item"), w, () -> {
            changedSample = true;
            discard = false;
        }));
        nodes.add(input("changed.price", tr("gui.fpsm.listener_editor.changed_cost"), changedCost, idle(), value -> {
            changedCost = value;
            discard = false;
        }).size(-1, 44));
        return nodes;
    }

    private Node product(String key, ItemStack stack, String label, int w, Runnable select) {
        List<Node> content = List.of(inventoryItem(key + ".icon", stack).at(4, 4, 28, 28),
                muted(key + ".label", label).at(40, 0, w - 44, 18),
                text(key + ".name", stack.getHoverName().getString()).hint(stack.getHoverName().getString()).at(40, 18, w - 44, 18));
        if (select == null) return canvas(key, content).size(-1, 40);
        return actionCanvas(key, label, idle(), event -> { if (!event.startsWith("hover")) select.run(); }, content).selected(key.startsWith("changed") == changedSample).size(-1, 40);
    }

    private Node inventory(int w, int cell) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(select("sample.target", changedSample ? "changed" : "default", List.of(
                text("default", tr("gui.fpsm.listener_editor.default_item")), text("changed", tr("gui.fpsm.listener_editor.changed_item"))),
                value -> changedSample = value.equals("changed")).enabled(idle()).at(0, 0, w, 24));
        nodes.add(iconButton("sample.hand", "arrow-right-left", tr("gui.fpsm.listener_editor.hand"), idle(), () -> {
            var player = Minecraft.getInstance().player;
            if (player != null) replaceSample(player.getMainHandItem());
        }).at(w - 24, 28, 24, 24));
        nodes.add(muted("inventory.caption", tr("container.inventory")).at(0, 28, w - 32, 24));
        for (int i = 0; i < 36; i++) {
            int inventoryIndex = i < 27 ? i + 9 : i - 27;
            var player = Minecraft.getInstance().player;
            ItemStack stack = player == null ? ItemStack.EMPTY : player.getInventory().getItem(inventoryIndex).copy();
            String key = "inventory." + i;
            nodes.add(actionCanvas(key, stack.getHoverName().getString(), idle(), event -> {
                var current = Minecraft.getInstance().player;
                if (idle() && !event.startsWith("hover") && current != null) replaceSample(current.getInventory().getItem(inventoryIndex));
            }, List.of(inventoryItem(key + ".icon", stack).at(2, 2, cell - 4, cell - 4))).hint(stack.getHoverName().getString())
                    .at((w - cell * 9) / 2f + i % 9 * cell, 58 + i / 9 * cell + (i >= 27 ? 4 : 0), cell, cell));
        }
        return canvas("sample.inventory", nodes).size(-1, 66 + cell * 4);
    }

    private void replaceSample(ItemStack stack) {
        if (stack.isEmpty()) {
            status = tr("gui.fpsm.shop_editor.item.replace.empty");
            return;
        }
        if (changedSample) changedItem = stack.copy();
        else defaultItem = stack.copy();
        confirmedUpdate = null;
        discard = false;
        status = "";
    }

    private void reload() {
        if (!idle()) return;
        if (editing && dirty() && !discard) {
            discard = true;
            status = tr("gui.fpsm.shop_editor.reload.confirm");
            return;
        }
        editing = false;
        discard = false;
        conflict = false;
        send(Action.LOAD, new Definition("", ItemStack.EMPTY, 0, ItemStack.EMPTY, 0));
    }

    @Override
    public void onClose() {
        if (!idle()) return;
        if (editing) {
            if (dirty() && !discard) {
                discard = true;
                status = tr("gui.fpsm.shop_editor.discard.confirm");
                refresh();
                return;
            }
            editing = false;
            discard = false;
            status = "";
            refresh();
        } else Minecraft.getInstance().setScreen(slotEditor);
    }
}
