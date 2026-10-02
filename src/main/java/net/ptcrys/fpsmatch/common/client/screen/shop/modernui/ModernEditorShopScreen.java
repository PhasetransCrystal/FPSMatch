package net.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorRequest;
import net.ptcrys.fpsmatch.common.packet.shop.OpenShopEditorC2SPacket;
import net.ptcrys.fpsmatch.common.packet.shop.SetShopGroupsC2SPacket;
import net.ptcrys.fpsmatch.common.packet.shop.ShopEditorResultS2CPacket;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorResult;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorValues;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

public final class ModernEditorShopScreen extends ModernShopScreen {

    private final ShopEditorSnapshot.Target target;
    private final ShopEditorRequest request;
    private final Object connection;
    private ShopEditorSnapshot snapshot;
    private List<ShopEditorSnapshot.Slot> slots = List.of();
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();

    private record TypeInfo(int startIndex, int slotCount) {}

    private int selected = 0;
    private String category = "";
    private String status = "";
    private boolean batchMode, groupConflict;
    private final Set<Integer> checked = new LinkedHashSet<>();
    private String groupDraft = "-1";
    private SetShopGroupsC2SPacket submittedGroups;

    public ModernEditorShopScreen(ShopEditorSnapshot.Target target, Screen parent) {
        super(Component.translatable("gui.fpsm.shop_editor.title"), parent);
        this.target = target;
        request = new ShopEditorRequest(target);
        connection = Minecraft.getInstance().getConnection();
        load();
    }

    public ShopEditorSnapshot snapshot() {
        return snapshot;
    }

    public void applySnapshot(ShopEditorSnapshot next) {
        if (!target.equals(next.target())) return;
        snapshot = next;
        slots = next.slots();
        types.clear();
        int start = 0;
        for (var entry : next.categories()) {
            types.put(entry.name(), new TypeInfo(start, entry.slots().size()));
            start += entry.slots().size();
        }
        selected = Math.max(0, Math.min(selected, slots.size() - 1));
        var selectedCategory = types.get(category);
        if (selectedCategory == null || selected < selectedCategory.startIndex() || selected >= selectedCategory.startIndex() + selectedCategory.slotCount()) category = "";
        checked.removeIf(index -> index >= slots.size());
    }

    private void load() {
        if (request.waiting()) return;
        submittedGroups = null;
        long id = request.begin(ShopEditorResultS2CPacket.Operation.LOAD);
        status = tr("gui.fpsm.shop_editor.state.loading");
        FPSMatch.sendToServer(new OpenShopEditorC2SPacket(id, target));
    }

    public void applyResult(ShopEditorResultS2CPacket packet) {
        if (!request.accepts(packet)) return;
        request.complete();
        if (packet.snapshot() != null) applySnapshot(packet.snapshot());
        if (packet.result() == ShopEditorResult.CONFLICT) groupConflict = true;
        if (packet.result() == ShopEditorResult.SUCCESS && packet.operation() == ShopEditorResultS2CPacket.Operation.LOAD) {
            if (groupConflict) checked.clear();
            groupConflict = false;
        }
        status = packet.result() == ShopEditorResult.SUCCESS ? "" : tr(packet.result().translationKey());
        if (packet.result() == ShopEditorResult.SUCCESS && packet.operation() == ShopEditorResultS2CPacket.Operation.SET_GROUPS && submittedGroups != null) {
            status = tr("gui.fpsm.shop_editor.batch.success", submittedGroups.indices().length, submittedGroups.groupId());
        }
        submittedGroups = null;
        refresh();
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().getConnection() != connection) {
            Minecraft.getInstance().setScreen(null);
            return;
        }
        if (request.tick()) {
            status = tr(submittedGroups == null ? "gui.fpsm.shop_editor.load.timeout" : "gui.fpsm.shop_editor.save.timeout");
            refresh();
        }
    }

    @Override
    protected List<Node> content() {
        var layout = ShopEditorLayoutModel.responsive(layoutWidth(), layoutHeight());
        boolean busy = request.waiting();
        if (snapshot == null) return List.of(canvas("loading", List.of(
                header(getTitle().getString(), target.gameType() + " / " + target.mapName() + " / " + target.teamName(),
                        iconButton("back", "x", tr("gui.back"), true, this::onClose)),
                footer(status, iconButton("reload", "rotate-cw", tr("gui.fpsm.map_select.refresh"), !busy, this::load),
                        button("retry", tr("gui.fpsm.map_select.refresh"), !busy, this::load),
                        button("back", tr("gui.back"), true, this::onClose))))
                .fill());
        List<Node> nodes = new ArrayList<>(), categories = new ArrayList<>(), slots = new ArrayList<>(), properties = new ArrayList<>();
        if (!types.containsKey(category)) category = types.entrySet().stream()
                .filter(e -> selected >= e.getValue().startIndex() && selected < e.getValue().startIndex() + e.getValue().slotCount())
                .map(Map.Entry::getKey).findFirst().orElseGet(() -> types.keySet().stream().findFirst().orElse(""));
        if (batchMode) return batchContent();
        for (var entry : types.entrySet()) {
            String type = entry.getKey();
            var info = entry.getValue();
            categories.add(button("type." + type, type, !busy, () -> {
                category = type;
                selected = info.startIndex();
            }).selected(category.equals(type)).size(-1, 24));
        }
        if (types.containsKey(category)) {
            var info = types.get(category);
            for (int i = 0; i < info.slotCount(); i++) {
                int index = info.startIndex() + i;
                var slot = this.slots.get(index);
                var stack = slot.item();
                int sw = Math.max(1, layout.slots().width());
                slots.add(actionCanvas("slot." + index, stack.getHoverName().getString(), !busy, event -> {
                    if (!event.startsWith("hover")) {
                        selected = index;
                    }
                }, List.of(item("icon." + index, stack).at(5, 5, 28, 28), text("name", (i + 1) + "  " + stack.getHoverName().getString()).hint(stack.getHoverName().getString()).at(39, 2, sw - 44, 20),
                        text("price", "$" + slot.price()).at(39, 22, sw - 44, 16))).selected(selected == index).size(-1, 42));
            }
        }
        properties.add(title("title", tr("gui.fpsm.shop_editor.properties")));
        if (selected >= 0 && selected < this.slots.size()) {
            var slot = this.slots.get(selected);
            var stack = slot.item();
            properties.add(text("name", stack.getHoverName().getString()).hint(stack.getHoverName().getString()).size(-1, 20));
            properties.add(muted("type", category + " #" + (selected - types.get(category).startIndex() + 1)).size(-1, 18));
            properties.add(text("price", tr("gui.fpsm.price") + ": $" + slot.price()).size(-1, 20));
            properties.add(text("ammo", tr("gui.fpsm.dummy_ammo") + ": " + slot.ammo()).size(-1, 20));
            properties.add(text("group", tr("gui.fpsm.group") + ": " + slot.group()).size(-1, 20));
        }
        nodes.add(header(getTitle().getString(), target.gameType() + " / " + target.mapName() + " / " + target.teamName(),
                button("batch", tr("gui.fpsm.shop_editor.batch.title"), !busy, () -> {
                    batchMode = true;
                    status = "";
                })));
        Node categoryView = layout.compact() ? select("categories", category, categories.stream().map(n -> text(n.key().substring(5), n.text())).toList(), v -> {
            category = v;
            selected = types.get(v).startIndex();
        }).enabled(!busy) : scroll("categories", categories);
        nodes.add(place(categoryView, layout.categories()));
        nodes.add(place(scroll("slots", slots), layout.slots()));
        if (layout.properties().height() > 0 && layout.properties().height() < 56 && properties.size() > 1) {
            int columnWidth = Math.max(1, layout.properties().width() / 3);
            List<Node> summary = new ArrayList<>();
            for (int i = 1; i < properties.size(); i++) {
                Node property = properties.get(i);
                summary.add(muted(property.key(), property.text()).hint(property.text())
                        .at((i - 1) % 3 * columnWidth, (i - 1) / 3 * 18, columnWidth, 18));
            }
            nodes.add(place(canvas("properties", summary), layout.properties()));
        } else if (layout.properties().height() > 0) {
            nodes.add(place(scroll("properties", properties), layout.properties()));
        }
        nodes.add(footer(status, iconButton("reload", "rotate-cw", tr("gui.fpsm.map_select.refresh"), !busy, this::load),
                button("edit", tr("gui.fpsm.shop_editor.edit_selected"), !busy && selected >= 0 && selected < this.slots.size(), () -> open(selected)),
                button("back", tr("gui.back"), !busy, this::onClose)));
        return List.of(canvas("editor", nodes).fill());
    }

    private List<Node> batchContent() {
        boolean savingGroups = request.waiting();
        var layout = ShopEditorLayoutModel.batch(layoutWidth(), layoutHeight());
        int bodyWidth = layout.frame().body().width();
        List<Node> nodes = new ArrayList<>(), slots = new ArrayList<>(), toolbar = new ArrayList<>();
        nodes.add(header(tr("gui.fpsm.shop_editor.batch.title"), target.gameType() + " / " + target.mapName() + " / " + target.teamName(),
                canvas("commands", List.of(
                        iconButton("reload", "rotate-cw", tr("gui.fpsm.map_select.refresh"), !savingGroups, this::load).at(0, 0, 24, 24),
                        button("done", tr("gui.fpsm.shop_editor.batch.done"), !savingGroups, () -> {
                            batchMode = false;
                            status = "";
                        }).at(32, 0, 80, 24)))));
        toolbar.add(place(select("categories", category, types.keySet().stream().map(type -> text(type, type)).toList(),
                value -> category = value).enabled(!savingGroups), layout.category()));
        toolbar.add(place(button("select.category", tr("gui.fpsm.shop_editor.batch.select_category"), !savingGroups && types.containsKey(category), this::selectCategory), layout.selectCategory()));
        toolbar.add(place(button("clear", tr("gui.fpsm.shop_editor.batch.clear"), !savingGroups && !checked.isEmpty(), () -> checked.clear()), layout.clear()));
        slots.add(canvas("batch.toolbar", toolbar).size(-1, layout.toolbarHeight()));
        String selection = tr("gui.fpsm.shop_editor.batch.selection", checked.size(), ShopEditorValues.MAX_SELECTION);
        slots.add(muted("selection", selection).hint(selection).size(-1, 18));
        var info = types.get(category);
        if (info != null) for (int i = 0; i < info.slotCount(); i++) {
            int index = info.startIndex() + i;
            var slot = this.slots.get(index);
            var stack = slot.item();
            String name = stack.isEmpty() ? tr("gui.fpsm.shop_editor.batch.empty") : stack.getHoverName().getString();
            slots.add(actionCanvas("slot." + index, name, !savingGroups, event -> {
                if (savingGroups || event.startsWith("hover")) return;
                if (!checked.remove(index) && checked.size() < ShopEditorValues.MAX_SELECTION) checked.add(index);
            }, List.of(text("check", checked.contains(index) ? "☑" : "☐").at(4, 6, 20, 20),
                    item("batch.icon." + index, stack).at(28, 4, 26, 26),
                    text("name", (i + 1) + "  " + name).hint(name).at(60, 1, bodyWidth - 68, 16),
                    muted("group", tr("gui.fpsm.group") + ": " + slot.group()).at(60, 18, bodyWidth - 68, 14)))
                    .selected(checked.contains(index)).size(-1, 36));
        }
        nodes.add(place(scroll("slots", slots), layout.frame().body()));
        nodes.add(place(text("group.label", tr("gui.fpsm.group")), layout.groupLabel()));
        nodes.add(place(field("group", groupDraft, !savingGroups, value -> groupDraft = value), layout.group()));
        nodes.add(place(button("apply", tr("gui.fpsm.shop_editor.batch.apply"), !savingGroups && !groupConflict && !checked.isEmpty() && validGroupDraft(), this::saveGroups), layout.apply()));
        String feedback = !validGroupDraft() ? tr("gui.fpsm.shop_editor.batch.invalid") : status;
        nodes.add(place(muted("status", feedback).hint(feedback), layout.status()));
        return List.of(canvas("batch.editor", nodes).fill());
    }

    private boolean validGroupDraft() {
        try {
            return ShopEditorValues.validGroup(Integer.parseInt(groupDraft));
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private void selectCategory() {
        var info = types.get(category);
        if (request.waiting() || info == null) return;
        for (int i = 0; i < info.slotCount() && checked.size() < ShopEditorValues.MAX_SELECTION; i++) {
            int index = info.startIndex() + i;
            checked.add(index);
        }
    }

    private void saveGroups() {
        if (request.waiting() || groupConflict || checked.isEmpty() || !validGroupDraft()) return;
        int group = Integer.parseInt(groupDraft);
        int[] indices = checked.stream().mapToInt(Integer::intValue).toArray();
        if (submittedGroups != null && submittedGroups.groupId() == group && Arrays.equals(submittedGroups.indices(), indices)) request.retry();
        else submittedGroups = new SetShopGroupsC2SPacket(request.begin(ShopEditorResultS2CPacket.Operation.SET_GROUPS), target, snapshot.revision(), group, indices);
        status = tr("gui.fpsm.shop_editor.state.saving");
        FPSMatch.sendToServer(submittedGroups);
    }

    private void open(int index) {
        if (request.waiting() || index < 0 || index >= slots.size()) return;
        selected = index;
        var entry = types.entrySet().stream().filter(e -> index >= e.getValue().startIndex() && index < e.getValue().startIndex() + e.getValue().slotCount()).findFirst().orElseThrow();
        Minecraft.getInstance().setScreen(new ModernEditShopSlotScreen(this, entry.getKey(), index - entry.getValue().startIndex()));
    }

    @Override
    public void onClose() {
        if (snapshot != null && request.waiting()) return;
        if (batchMode) {
            batchMode = false;
            status = "";
            refresh();
            return;
        }
        super.onClose();
    }
}
