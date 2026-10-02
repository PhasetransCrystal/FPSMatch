package net.ptcrys.fpsmatch.common.shop.editor;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import net.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import net.ptcrys.fpsmatch.common.packet.shop.ListenerModuleActionC2SPacket;
import net.ptcrys.fpsmatch.common.packet.shop.ListenerModuleActionC2SPacket.Action;
import net.ptcrys.fpsmatch.common.packet.shop.ListenerModuleResultS2CPacket;
import net.ptcrys.fpsmatch.common.shop.functional.ChangeShopItemModule;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.persistence.PersistenceUtils;
import net.ptcrys.fpsmatch.core.shop.FPSMShop;
import net.ptcrys.fpsmatch.core.shop.INamedType;
import net.ptcrys.fpsmatch.core.shop.functional.LMManager;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.BiConsumer;

/** Shared definitions have their own revisions and dependency checks, independent of slot drafts. */
public final class ListenerModuleService {

    private static final Map<LMManager, State> STATES = new WeakHashMap<>();

    private static final class State {

        final UUID incarnation = UUID.randomUUID();
        long generation;
        String content;
        final LinkedHashMap<Request, String> receipts = new LinkedHashMap<>();
    }

    private record Request(UUID player, long id) {}

    public record ShopRef(ShopEditorSnapshot.Target target, FPSMShop<?> shop) {}

    private record Replacement(List<ShopSlot> slots, int index, ShopSlot value) {}

    private ListenerModuleService() {}

    private static List<ShopRef> shops() {
        List<ShopRef> result = new ArrayList<>();
        FPSMCore.getInstance().getAllMaps().forEach((type, maps) -> maps.forEach(map -> map.getMapTeams().getTeamsWithSpectator().forEach(team -> ShopCapability.getShop(team).ifPresent(shop -> result.add(new ShopRef(new ShopEditorSnapshot.Target(type, map.getMapName(), team.getName()), shop))))));
        result.sort(Comparator.comparing((ShopRef ref) -> ref.target().gameType()).thenComparing(ref -> ref.target().mapName()).thenComparing(ref -> ref.target().teamName()));
        return result;
    }

    public static void writeDefinition(FriendlyByteBuf buf, ListenerModule module) {
        buf.writeUtf(module.getName(), 256);
        buf.writeInt(module.getPriority());
        buf.writeUtf(module.getClass().getName());
        buf.writeBoolean(module instanceof ChangeShopItemModule);
        if (module instanceof ChangeShopItemModule change) {
            ShopEditorService.writeTag(buf, change.defaultItem().save(new CompoundTag()));
            buf.writeInt(change.defaultCost());
            ShopEditorService.writeTag(buf, change.changedItem().save(new CompoundTag()));
            buf.writeInt(change.changedCost());
        }
    }

    public static ListenerModuleSnapshot snapshot(LMManager manager, List<ShopRef> shops) {
        List<ListenerModuleSnapshot.Module> modules = new ArrayList<>();
        for (String name : manager.getListenerModules().stream().sorted().toList()) {
            List<ListenerModuleSnapshot.Reference> references = new ArrayList<>();
            for (ShopRef ref : shops) {
                for (Object value : ref.shop().getEnums()) {
                    String type = ((INamedType) value).name();
                    var slots = ref.shop().getDefaultShopSlotListByType(type);
                    for (int i = 0; i < slots.size(); i++) {
                        if (slots.get(i).getListenerNames().contains(name)) references.add(new ListenerModuleSnapshot.Reference(ref.target(), type, i, slots.get(i).getGroupId()));
                    }
                }
            }
            var module = ListenerModuleSnapshot.describe(manager.getListenerModule(name), references);
            modules.add(manager.isBuiltIn(name) ? new ListenerModuleSnapshot.Module(name, module.priority(), module.definition(), false, references) : module);
        }
        String content = ShopEditorService.hash(buf -> {
            for (var module : modules) {
                writeDefinition(buf, manager.getListenerModule(module.name()));
                buf.writeCollection(module.references(), (out, reference) -> reference.write(out));
            }
        });
        State state = STATES.computeIfAbsent(manager, ignored -> new State());
        if (state.content != null && !state.content.equals(content)) state.generation++;
        state.content = content;
        String revision = ShopEditorService.hash(buf -> {
            buf.writeUUID(state.incarnation);
            buf.writeLong(state.generation);
            buf.writeUtf(content);
        });
        return new ListenerModuleSnapshot(revision, modules);
    }

    public static boolean validDefinition(ListenerModuleSnapshot.Definition draft) {
        return validName(draft.name()) && validItem(draft.defaultItem()) && validItem(draft.changedItem()) && draft.defaultCost() >= 0 && draft.defaultCost() <= 1_000_000 && draft.changedCost() >= 0 && draft.changedCost() <= 1_000_000;
    }

    public static boolean validName(String name) {
        return name != null && name.matches("changeItem_[A-Za-z0-9_]{1,117}");
    }

    private static boolean validItem(ItemStack item) {
        return !item.isEmpty() && item.getCount() > 0 && item.getCount() <= item.getMaxStackSize();
    }

    /** Persistence happens before publishing replacements; disk failures cannot change live definitions. */
    public static ShopEditorResult apply(LMManager manager, List<ShopRef> shops, String revision, Action action,
                                         ListenerModuleSnapshot.Definition draft, BiConsumer<Action, ChangeShopItemModule> persist) {
        var catalog = snapshot(manager, shops);
        if (!catalog.revision().equals(revision)) return ShopEditorResult.CONFLICT;
        var current = manager.getListenerModule(draft.name());
        if (action == Action.CREATE) {
            if (current != null || manager.getListenerModules().stream().anyMatch(name -> PersistenceUtils.fixFileName(name).equalsIgnoreCase(PersistenceUtils.fixFileName(draft.name())))) return ShopEditorResult.MODULE_EXISTS;
            if (catalog.modules().size() >= ShopEditorSnapshot.MAX_CATALOG) return ShopEditorResult.INVALID_VALUE;
        } else if (current == null) return ShopEditorResult.INVALID_MODULE;
        else if (!(current instanceof ChangeShopItemModule) || manager.isBuiltIn(draft.name())) return ShopEditorResult.MODULE_READ_ONLY;

        if (action == Action.DELETE) {
            var module = catalog.modules().stream().filter(m -> m.name().equals(draft.name())).findFirst().orElseThrow();
            if (!module.references().isEmpty()) return ShopEditorResult.MODULE_IN_USE;
            persist.accept(action, (ChangeShopItemModule) current);
            manager.getRegistry().remove(draft.name());
            return ShopEditorResult.SUCCESS;
        }
        if (action != Action.CREATE && action != Action.UPDATE) return ShopEditorResult.INVALID_VALUE;
        // Legacy generated names remain editable, but new identities use a collision-free filename alphabet.
        if (!(action == Action.UPDATE && current != null) && !validName(draft.name())) return ShopEditorResult.INVALID_VALUE;
        if (!validItem(draft.defaultItem()) || !validItem(draft.changedItem())) return ShopEditorResult.INVALID_ITEM;
        if (draft.defaultCost() < 0 || draft.defaultCost() > 1_000_000 || draft.changedCost() < 0 || draft.changedCost() > 1_000_000) return ShopEditorResult.INVALID_VALUE;

        ChangeShopItemModule replacement = draft.create();
        List<Replacement> replacements = new ArrayList<>();
        for (ShopRef ref : shops) for (Object value : ref.shop().getEnums()) {
            var slots = ref.shop().getDefaultShopSlotListByType(((INamedType) value).name());
            for (int i = 0; i < slots.size(); i++) if (slots.get(i).getListenerNames().contains(draft.name())) {
                ShopSlot updated = slots.get(i).copy();
                updated.replaceListener(replacement);
                replacements.add(new Replacement(slots, i, updated));
            }
        }
        persist.accept(action, replacement);
        manager.addListenerType(replacement);
        replacements.forEach(value -> value.slots().set(value.index(), value.value()));
        return ShopEditorResult.SUCCESS;
    }

    public static void execute(ServerPlayer player, ListenerModuleActionC2SPacket packet) {
        if (player == null) return;
        ShopEditorResult result;
        ListenerModuleSnapshot catalog = null;
        ShopEditorSnapshot shopSnapshot = null;
        if (!MapRoomQueryService.isMapOperator(player)) result = ShopEditorResult.NO_PERMISSION;
        else if (!packet.target().valid()) result = ShopEditorResult.INVALID_ID;
        else {
            var shop = MapRoomQueryService.findMap(packet.target().gameType(), packet.target().mapName())
                    .flatMap(map -> map.getMapTeams().getNormalTeams().stream().filter(team -> team.getName().equals(packet.target().teamName())).findFirst()).flatMap(ShopCapability::getShop);
            if (shop.isEmpty()) result = ShopEditorResult.SHOP_UNAVAILABLE;
            else {
                var manager = FPSMCore.getInstance().getListenerModuleManager();
                var refs = shops();
                State state = STATES.computeIfAbsent(manager, ignored -> new State());
                Request request = new Request(player.getUUID(), packet.requestId());
                String fingerprint = ShopEditorService.hash(buf -> ListenerModuleActionC2SPacket.encode(packet, buf));
                try {
                    // Ensure the current state is representable before allowing any mutation.
                    catalog = snapshot(manager, refs);
                    if (packet.action() == Action.LOAD) result = ShopEditorResult.SUCCESS;
                    else if (state.receipts.containsKey(request)) result = state.receipts.get(request).equals(fingerprint) ? ShopEditorResult.SUCCESS : ShopEditorResult.INVALID_VALUE;
                    else {
                        result = apply(manager, refs, packet.revision(), packet.action(), packet.draft(), (action, definition) -> {
                            var data = FPSMCore.getInstance().getFPSMDataManager();
                            if (action == Action.DELETE) data.deleteData(ChangeShopItemModule.class, definition.getName());
                            else data.saveDataAtomic(definition, definition.getName());
                        });
                        if (result == ShopEditorResult.SUCCESS) {
                            state.receipts.put(request, fingerprint);
                            while (state.receipts.size() > 128) state.receipts.remove(state.receipts.keySet().iterator().next());
                            if (packet.action() == Action.UPDATE) for (ShopRef ref : refs) {
                                boolean affected = catalog.modules().stream().filter(m -> m.name().equals(packet.draft().name()))
                                        .flatMap(m -> m.references().stream()).anyMatch(usage -> usage.target().equals(ref.target()));
                                if (affected) {
                                    try {
                                        ref.shop().resetPlayerData();
                                        ref.shop().syncShopData();
                                    } catch (RuntimeException failure) {
                                        FPSMatch.LOGGER.error("Saved module but failed to refresh shop {}", ref.target(), failure);
                                    }
                                }
                            }
                        }
                    }
                    catalog = snapshot(manager, refs);
                    shopSnapshot = ShopEditorService.snapshot(shop.get(), packet.target(), manager.getListenerModules());
                } catch (RuntimeException failure) {
                    FPSMatch.LOGGER.error("Listener module request failed", failure);
                    result = ShopEditorResult.FAILED;
                }
            }
        }
        FPSMatch.sendToPlayer(player, new ListenerModuleResultS2CPacket(packet.requestId(), packet.target(), packet.action(), result, catalog, shopSnapshot));
    }
}
