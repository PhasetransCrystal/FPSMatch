package net.ptcrys.fpsmatch.common.shop.editor;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import net.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import net.ptcrys.fpsmatch.common.packet.shop.ShopEditorResultS2CPacket;
import net.ptcrys.fpsmatch.common.packet.shop.ShopEditorResultS2CPacket.Operation;
import net.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.shop.FPSMShop;
import net.ptcrys.fpsmatch.core.shop.INamedType;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import net.ptcrys.fpsmatch.util.FPSMUtil;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import io.netty.buffer.Unpooled;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Server-thread configuration operations. No player menu or editor-session ownership. */
public final class ShopEditorService {

    private static final Map<FPSMShop<?>, State> STATES = new WeakHashMap<>();

    private static final class State {

        final UUID incarnation = UUID.randomUUID();
        long generation;
        String contentRevision;
        final LinkedHashMap<Request, Receipt> receipts = new LinkedHashMap<>();
    }

    private record Request(UUID player, long id) {}

    private record Receipt(String fingerprint) {}

    private record SlotRef(String type, int index) {}

    private ShopEditorService() {}

    public static void load(ServerPlayer player, long requestId, ShopEditorSnapshot.Target target) {
        execute(player, requestId, Operation.LOAD, target, "", shop -> ShopEditorResult.SUCCESS);
    }

    public static void saveSlot(ServerPlayer player, long requestId, ShopEditorSnapshot.Target target,
                                String revision, String type, int index, ShopEditorSnapshot.Slot draft) {
        String fingerprint = hash(buf -> {
            buf.writeEnum(Operation.SAVE_SLOT);
            target.write(buf);
            buf.writeUtf(revision, 64);
            buf.writeUtf(type, 128);
            buf.writeInt(index);
            draft.write(buf);
        });
        execute(player, requestId, Operation.SAVE_SLOT, target, fingerprint,
                shop -> applySlot(shop, revision, type, index, draft, registeredModules()));
    }

    public static void setGroups(ServerPlayer player, long requestId, ShopEditorSnapshot.Target target,
                                 String revision, int group, int[] indices) {
        String fingerprint = hash(buf -> {
            buf.writeEnum(Operation.SET_GROUPS);
            target.write(buf);
            buf.writeUtf(revision, 64);
            buf.writeInt(group);
            buf.writeVarIntArray(indices);
        });
        execute(player, requestId, Operation.SET_GROUPS, target, fingerprint,
                shop -> applyGroups(shop, revision, group, indices));
    }

    private static Map<String, ListenerModule> registeredModules() {
        var manager = FPSMCore.getInstance().getListenerModuleManager();
        Map<String, ListenerModule> result = new LinkedHashMap<>();
        for (String name : manager.getListenerModules()) result.put(name, manager.getListenerModule(name));
        return result;
    }

    private static void execute(ServerPlayer player, long requestId, Operation operation, ShopEditorSnapshot.Target target,
                                String fingerprint, Function<FPSMShop<?>, ShopEditorResult> change) {
        if (player == null) return;
        ShopEditorResult result;
        ShopEditorSnapshot snapshot = null;
        if (!target.valid()) result = ShopEditorResult.INVALID_ID;
        else if (!MapRoomQueryService.isMapOperator(player)) result = ShopEditorResult.NO_PERMISSION;
        else {
            var resolved = MapRoomQueryService.findMap(target.gameType(), target.mapName())
                    .flatMap(map -> map.getMapTeams().getNormalTeams().stream().filter(team -> team.getName().equals(target.teamName())).findFirst())
                    .flatMap(ShopCapability::getShop);
            if (resolved.isEmpty()) result = ShopEditorResult.SHOP_UNAVAILABLE;
            else {
                var shop = resolved.get();
                State state = STATES.computeIfAbsent(shop, ignored -> new State());
                Request request = new Request(player.getUUID(), requestId);
                Receipt receipt = state.receipts.get(request);
                Map<String, List<ShopSlot>> before = new LinkedHashMap<>();
                for (Object value : shop.getEnums()) {
                    String type = ((INamedType) value).name();
                    before.put(type, new ArrayList<>(shop.getDefaultShopSlotListByType(type)));
                }
                try {
                    if (operation != Operation.LOAD && receipt != null) {
                        result = receipt.fingerprint().equals(fingerprint) ? ShopEditorResult.SUCCESS : ShopEditorResult.INVALID_VALUE;
                    } else {
                        result = change.apply(shop);
                        if (result == ShopEditorResult.SUCCESS && operation != Operation.LOAD) {
                            FPSMCore.getInstance().getFPSMDataManager().saveAllData();
                            state.receipts.put(request, new Receipt(fingerprint));
                            while (state.receipts.size() > 128) state.receipts.remove(state.receipts.keySet().iterator().next());
                            shop.syncShopData();
                        }
                    }
                    if (result == ShopEditorResult.SUCCESS || result == ShopEditorResult.CONFLICT) {
                        snapshot = snapshot(shop, target, registeredModules().keySet());
                    }
                } catch (RuntimeException failure) {
                    // An unacknowledged write must not leave an unseen configuration change in memory.
                    if (!state.receipts.containsKey(request) && operation != Operation.LOAD) {
                        before.forEach((type, slots) -> {
                            var current = shop.getDefaultShopSlotListByType(type);
                            current.clear();
                            current.addAll(slots);
                        });
                        shop.resetPlayerData();
                    }
                    FPSMatch.LOGGER.error("Shop configuration request failed for {}", target, failure);
                    result = ShopEditorResult.FAILED;
                }
            }
        }
        FPSMatch.sendToPlayer(player, new ShopEditorResultS2CPacket(requestId, operation, target, result, snapshot));
    }

    public static ShopEditorSnapshot snapshot(FPSMShop<?> shop, ShopEditorSnapshot.Target target, Collection<String> modules) {
        List<ShopEditorSnapshot.Category> categories = new ArrayList<>();
        int count = 0;
        for (Object value : shop.getEnums()) {
            String type = ((INamedType) value).name();
            List<ShopEditorSnapshot.Slot> slots = new ArrayList<>();
            for (ShopSlot slot : shop.getDefaultShopSlotListByType(type)) {
                slots.add(new ShopEditorSnapshot.Slot(slot.process(), slot.getDefaultCost(), slot.getAmmoCount(), slot.getGroupId(), slot.getMaxBuyCount(), slot.getListenerNames()));
            }
            count += slots.size();
            categories.add(new ShopEditorSnapshot.Category(type, slots));
        }
        if (count > ShopEditorSnapshot.MAX_SLOTS) throw new IllegalArgumentException("Too many shop slots");
        return new ShopEditorSnapshot(target, revision(shop), categories, modules.stream().sorted().toList());
    }

    /** Content revision also detects command edits, direct mutations, reloads and shop replacement. */
    public static String revision(FPSMShop<?> shop) {
        State state = STATES.computeIfAbsent(shop, ignored -> new State());
        String content = hash(buf -> {
            for (Object value : shop.getEnums()) {
                String type = ((INamedType) value).name();
                buf.writeUtf(type);
                List<ShopSlot> slots = shop.getDefaultShopSlotListByType(type);
                buf.writeVarInt(slots.size());
                for (ShopSlot slot : slots) {
                    writeTag(buf, slot.process().save(new CompoundTag()));
                    buf.writeInt(slot.getDefaultCost());
                    buf.writeInt(slot.getMaxBuyCount());
                    buf.writeInt(slot.getGroupId());
                    buf.writeCollection(slot.getListenerNames(), FriendlyByteBuf::writeUtf);
                    for (String name : slot.getListenerNames()) {
                        var module = FPSMCore.getInstance().getListenerModuleManager().getListenerModule(name);
                        if (module != null) ListenerModuleService.writeDefinition(buf, module);
                    }
                }
            }
        });
        if (state.contentRevision != null && !state.contentRevision.equals(content)) state.generation++;
        state.contentRevision = content;
        return hash(buf -> {
            buf.writeUUID(state.incarnation);
            buf.writeLong(state.generation);
            buf.writeUtf(content);
        });
    }

    static void writeTag(FriendlyByteBuf buf, Tag tag) {
        buf.writeByte(tag.getId());
        if (tag instanceof CompoundTag compound) {
            var keys = compound.getAllKeys().stream().sorted().toList();
            buf.writeVarInt(keys.size());
            for (String key : keys) {
                buf.writeUtf(key);
                writeTag(buf, compound.get(key));
            }
        } else if (tag instanceof ListTag list) {
            buf.writeVarInt(list.size());
            for (Tag entry : list) writeTag(buf, entry);
        } else {
            CompoundTag wrapper = new CompoundTag();
            wrapper.put("value", tag);
            buf.writeNbt(wrapper);
        }
    }

    static String hash(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            buf.release();
        }
    }

    public static ShopEditorResult applySlot(FPSMShop<?> shop, String expectedRevision, String type, int index,
                                             ShopEditorSnapshot.Slot draft, Map<String, ListenerModule> registered) {
        if (!revision(shop).equals(expectedRevision)) return ShopEditorResult.CONFLICT;
        final List<ShopSlot> slots;
        try {
            slots = shop.getDefaultShopSlotListByType(type);
        } catch (IllegalArgumentException invalid) {
            return ShopEditorResult.INVALID_SLOT;
        }
        if (slots == null || index < 0 || index >= slots.size()) return ShopEditorResult.INVALID_SLOT;
        ShopSlot current = slots.get(index);
        if (draft.price() < 0 || draft.price() > 1_000_000 || draft.ammo() < 0 || draft.ammo() > 999_999 || !ShopEditorValues.validGroup(draft.group()) || draft.maxBuyCount() != current.getMaxBuyCount()) return ShopEditorResult.INVALID_VALUE;
        if (!ShopEditorValues.validModules(draft.modules(), registered.keySet())) return ShopEditorResult.INVALID_MODULE;
        ItemStack item = draft.item();
        if (item.isEmpty() || item.getCount() < 1 || item.getCount() > item.getMaxStackSize()) return ShopEditorResult.INVALID_ITEM;
        if (GunCompatManager.isGun(item)) FPSMUtil.setTotalDummyAmmo(item, GunCompatManager.findProvider(item), draft.ammo());
        if (ItemStack.matches(current.process(), item) && current.getDefaultCost() == draft.price() && current.getGroupId() == draft.group() && current.getListenerNames().equals(draft.modules())) return ShopEditorResult.SUCCESS;

        // A replacement needs a checker bound to its new product, not the previous slot's closure.
        ShopSlot replacement = new ShopSlot(item, draft.price(), current.getMaxBuyCount(), draft.group());
        replacement.setIndex(current.getIndex());
        for (String name : draft.modules()) replacement.addListener(registered.get(name));
        slots.set(index, replacement);
        shop.resetPlayerData();
        return ShopEditorResult.SUCCESS;
    }

    public static ShopEditorResult applyGroups(FPSMShop<?> shop, String expectedRevision, int group, int[] indices) {
        if (!revision(shop).equals(expectedRevision)) return ShopEditorResult.CONFLICT;
        List<SlotRef> refs = new ArrayList<>();
        for (Object value : shop.getEnums()) {
            String type = ((INamedType) value).name();
            for (int i = 0; i < shop.getDefaultShopSlotListByType(type).size(); i++) refs.add(new SlotRef(type, i));
        }
        if (!ShopEditorValues.validGroup(group) || !ShopEditorValues.validSelection(indices, refs.size())) return ShopEditorResult.INVALID_VALUE;
        List<ShopSlot> replacements = new ArrayList<>();
        boolean changed = false;
        for (int index : indices) {
            SlotRef ref = refs.get(index);
            ShopSlot replacement = shop.getDefaultShopSlotListByType(ref.type()).get(ref.index()).copy();
            changed |= replacement.getGroupId() != group;
            replacement.setGroupId(group);
            replacements.add(replacement);
        }
        if (!changed) return ShopEditorResult.SUCCESS;
        for (int i = 0; i < indices.length; i++) {
            SlotRef ref = refs.get(indices[i]);
            shop.getDefaultShopSlotListByType(ref.type()).set(ref.index(), replacements.get(i));
        }
        shop.resetPlayerData();
        return ShopEditorResult.SUCCESS;
    }
}
