package net.ptcrys.fpsmatch.core.shop.runtime;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.event.FPSMShopEvent;
import net.ptcrys.fpsmatch.common.packet.shop.ShopDataSlotS2CPacket;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.shop.*;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.PacketDistributor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.Codec;

import java.util.*;

/** Fixed definitions with synchronous, atomic player-graph publication. Does not own templates. */
public final class RuntimeShop<T extends Enum<T> & INamedType> extends FPSMShop<T> implements AutoCloseable {

    private final RuntimeItemTemplates owner;
    private final Thread thread = Thread.currentThread();
    private final ImmutableMap<T, ImmutableList<ShopSlot>> canonical;
    private final PlayerTable<T> table;
    private final int slots;
    private boolean publishing;
    private boolean closed;
    private boolean runtimeOwnership;
    private Runnable ownershipAccess;

    public RuntimeShop(Class<T> enumClass, String name, Map<T, ? extends List<ShopSlot>> defaults,
                       int startMoney, List<AreaData> areas, RuntimeItemTemplates owner) {
        this(prepare(enumClass, name, defaults, areas, owner), startMoney);
    }

    private RuntimeShop(Prepared<T> prepared, int money) {
        super(prepared.enumClass, prepared.name, Collections.emptyMap(), money,
                prepared.areas, prepared.table.view);
        owner = prepared.owner;
        canonical = prepared.graph;
        slots = prepared.slots;
        table = prepared.table;
    }

    private static <E extends Enum<E> & INamedType> Prepared<E> prepare(
                                                                        Class<E> enumClass, String name, Map<E, ? extends List<ShopSlot>> defaults,
                                                                        List<AreaData> areas, RuntimeItemTemplates owner) {
        if (owner == null) throw RuntimeItemMessages.error("invalid_input");
        owner.requireAvailable();
        if (enumClass == null || name == null || defaults == null || areas == null)
            throw RuntimeItemMessages.error("invalid_input");
        E[] types = enumClass.getEnumConstants();
        if (types == null) throw RuntimeItemMessages.error("invalid_input");
        int areaCount = areas.size();
        owner.requireAvailable();
        int categories = defaults.size();
        owner.requireAvailable();
        if (types.length > 8192 || categories > 8192 || areaCount > 8192) throw RuntimeItemMessages.error("budget");
        if (categories != types.length) throw RuntimeItemMessages.error("invalid_slot");
        int[] counts = new int[types.length];
        long total = 0;
        for (int i = 0; i < types.length; i++) {
            counts[i] = types[i].slotCount();
            owner.requireAvailable();
            if (counts[i] < 0) throw RuntimeItemMessages.error("invalid_slot");
            total += counts[i];
            if (total > 8192) throw RuntimeItemMessages.error("budget");
        }
        Set<ShopSlot> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        long listeners = 0;
        ImmutableMap.Builder<E, ImmutableList<ShopSlot>> inputGraph = ImmutableMap.builder();
        for (int i = 0; i < types.length; i++) {
            List<ShopSlot> input = defaults.get(types[i]);
            owner.requireAvailable();
            if (input == null) throw RuntimeItemMessages.error("invalid_input");
            int inputCount = input.size();
            owner.requireAvailable();
            if (inputCount != counts[i]) throw RuntimeItemMessages.error("invalid_slot");
            ImmutableList.Builder<ShopSlot> copied = ImmutableList.builder();
            for (int j = 0; j < counts[i]; j++) {
                ShopSlot raw = input.get(j);
                owner.requireAvailable();
                if (raw == null) throw RuntimeItemMessages.error("invalid_input");
                if (!(raw instanceof RuntimeShopSlot slot)) throw RuntimeItemMessages.error("invalid_slot");
                RuntimeItemTemplates.Template template = slot.currentTemplate();
                if (!owner.owns(template)) throw RuntimeItemMessages.error("foreign_template");
                if (!slot.isDefinitionFrozen() || slot.isRuntimeBound() || slot.getIndex() != j || !identities.add(slot))
                    throw RuntimeItemMessages.error("invalid_slot");
                listeners += slot.getListenerModules().size();
                if (listeners > 8192) throw RuntimeItemMessages.error("budget");
                copied.add(slot);
            }
            inputGraph.put(types[i], copied.build());
        }
        ArrayList<AreaData> fixedAreas = new ArrayList<>();
        for (AreaData area : areas) {
            owner.requireAvailable();
            if (fixedAreas.size() >= 8192) throw RuntimeItemMessages.error("budget");
            if (area == null) throw RuntimeItemMessages.error("invalid_input");
            var first = area.pos1();
            owner.requireAvailable();
            if (first == null) throw RuntimeItemMessages.error("invalid_input");
            BlockPos fixedFirst = new BlockPos(first.getX(), first.getY(), first.getZ());
            owner.requireAvailable();
            var second = area.pos2();
            owner.requireAvailable();
            if (second == null) throw RuntimeItemMessages.error("invalid_input");
            BlockPos fixedSecond = new BlockPos(second.getX(), second.getY(), second.getZ());
            owner.requireAvailable();
            fixedAreas.add(new AreaData(fixedFirst, fixedSecond));
        }
        ImmutableMap.Builder<E, ImmutableList<ShopSlot>> graph = ImmutableMap.builder();
        for (var entry : inputGraph.build().entrySet()) {
            ImmutableList.Builder<ShopSlot> copied = ImmutableList.builder();
            for (ShopSlot slot : entry.getValue()) {
                RuntimeShopSlot runtime = (RuntimeShopSlot) slot;
                if (runtime.isRuntimeBound() || !owner.owns(runtime.currentTemplate()))
                    throw RuntimeItemMessages.error("invalid_slot");
                copied.add(runtime.copy());
            }
            graph.put(entry.getKey(), copied.build());
        }
        owner.requireAvailable();
        return new Prepared<>(enumClass, name, graph.build(), List.copyOf(fixedAreas), owner,
                new PlayerTable<>(), (int) total);
    }

    private record Prepared<E extends Enum<E> & INamedType>(Class<E> enumClass, String name,
                                                            ImmutableMap<E, ImmutableList<ShopSlot>> graph, List<AreaData> areas,
                                                            RuntimeItemTemplates owner, PlayerTable<E> table, int slots) {}

    /** One authoritative reference; the public wrapper always reads its current value. */
    private static final class PlayerTable<E extends Enum<E> & INamedType> {

        private Map<UUID, ShopData<E>> current = ImmutableMap.of();
        private final Map<UUID, ShopData<E>> view = Collections.unmodifiableMap(new AbstractMap<>() {

            private final Set<Entry<UUID, ShopData<E>>> entries = new AbstractSet<>() {

                @Override
                public Iterator<Entry<UUID, ShopData<E>>> iterator() {
                    return current.entrySet().iterator();
                }

                @Override
                public int size() {
                    return current.size();
                }
            };

            @Override
            public Set<Entry<UUID, ShopData<E>>> entrySet() {
                return entries;
            }

            @Override
            public ShopData<E> get(Object key) {
                return current.get(key);
            }

            @Override
            public boolean containsKey(Object key) {
                return current.containsKey(key);
            }

            @Override
            public int size() {
                return current.size();
            }
        });
    }

    public void requireAvailable() {
        owner.requireAvailable();
        if (closed) throw RuntimeItemMessages.error("closed");
        requireOwnershipAccess();
        if (publishing) throw RuntimeItemMessages.error("busy");
    }

    /** Pure readiness check; an already closed consumer must not prevent shared-owner retirement. */
    public void requireRetirementReady() {
        owner.requireAvailable();
        if (publishing) throw RuntimeItemMessages.error("busy");
    }

    public boolean hasRuntimeOwnership() {
        return runtimeOwnership;
    }

    public void claimRuntimeOwnership(Runnable access) {
        requireAvailable();
        if (runtimeOwnership || !table.current.isEmpty()) throw RuntimeItemMessages.error("invalid_slot");
        if (access == null) throw RuntimeItemMessages.error("invalid_input");
        ownershipAccess = access;
        runtimeOwnership = true;
    }

    private void requireOwnershipAccess() {
        if (ownershipAccess != null) ownershipAccess.run();
    }

    @Override
    public void close() {
        if (Thread.currentThread() != thread) throw RuntimeItemMessages.error("wrong_thread");
        if (closed) return;
        owner.requireAvailable();
        if (publishing) throw RuntimeItemMessages.error("busy");
        closed = true;
        ownershipAccess = null;
        table.current = ImmutableMap.of();
    }

    private final class Access implements Runnable {

        private int state; // STAGE=0, LIVE=1, RETIRED=2
        private final UUID uuid;
        private final boolean detached;
        private RuntimeShopData<T> data;

        private Access(UUID uuid, boolean detached) {
            this.uuid = uuid;
            this.detached = detached;
        }

        @Override
        public void run() {
            owner.requireAvailable();
            if (closed || state == 2) throw RuntimeItemMessages.error("closed");
            requireOwnershipAccess();
            if (state == 0) {
                if (!publishing) throw RuntimeItemMessages.error("closed");
                return;
            }
            if (publishing) throw RuntimeItemMessages.error("busy");
            if (!detached && table.current.get(uuid) != data) throw RuntimeItemMessages.error("closed");
        }
    }

    private final class Candidate {

        private final Access access;
        private final ImmutableMap<T, ImmutableList<ShopSlot>> graph;
        private RuntimeShopData<T> data;

        private Candidate(Access access, ImmutableMap<T, ImmutableList<ShopSlot>> graph) {
            this.access = access;
            this.graph = graph;
        }
    }

    private ImmutableMap<T, ImmutableList<ShopSlot>> copyGraph(Access access) {
        ImmutableMap.Builder<T, ImmutableList<ShopSlot>> graph = ImmutableMap.builder();
        for (var entry : canonical.entrySet()) {
            ImmutableList.Builder<ShopSlot> copied = ImmutableList.builder();
            for (ShopSlot raw : entry.getValue()) {
                RuntimeShopSlot slot = ((RuntimeShopSlot) raw).copy();
                slot.bindRuntimeAccess(access);
                copied.add(slot);
            }
            graph.put(entry.getKey(), copied.build());
        }
        return graph.build();
    }

    private void validate(Map<T, ImmutableList<ShopSlot>> actual,
                          ImmutableMap<T, ImmutableList<ShopSlot>> expected) {
        owner.requireAvailable();
        if (actual == null) throw RuntimeItemMessages.error("invalid_input");
        if (actual.size() != expected.size()) throw RuntimeItemMessages.error("invalid_slot");
        for (var entry : expected.entrySet()) {
            List<ShopSlot> list = actual.get(entry.getKey());
            if (list == null || list.size() != entry.getValue().size()) throw RuntimeItemMessages.error("invalid_slot");
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != entry.getValue().get(i)) throw RuntimeItemMessages.error("invalid_slot");
                ((RuntimeShopSlot) list.get(i)).currentTemplate();
            }
        }
    }

    private void budget(long players) {
        if (players > 1024 || players * (long) slots > 65536) throw RuntimeItemMessages.error("budget");
    }

    /** Money is captured before the busy scope, because old data is unavailable within it. */
    private LinkedHashMap<UUID, Integer> moneyFor(Collection<UUID> ids, boolean reset) {
        LinkedHashMap<UUID, Integer> money = new LinkedHashMap<>();
        for (UUID uuid : ids) {
            ShopData<T> old = table.current.get(uuid);
            money.put(uuid, !reset && old != null ? old.getMoney() : super.getStartMoney());
        }
        return money;
    }

    private ShopData<T> publish(LinkedHashMap<UUID, Integer> money, boolean replaceAll) {
        requireAvailable();
        budget(replaceAll ? money.size() : table.current.size() + money.keySet().stream().filter(id -> !table.current.containsKey(id)).count());
        ArrayList<Access> accesses = new ArrayList<>();
        ArrayList<Candidate> candidates = new ArrayList<>();
        publishing = true;
        return publishInScope(money, replaceAll, accesses, candidates);
    }

    private ShopData<T> publishInScope(LinkedHashMap<UUID, Integer> money, boolean replaceAll,
                                       ArrayList<Access> accesses, ArrayList<Candidate> candidates) {
        boolean committed = false;
        try {
            for (var entry : money.entrySet()) {
                Access access = new Access(entry.getKey(), false);
                accesses.add(access);
                Candidate candidate = new Candidate(access, copyGraph(access));
                candidates.add(candidate);
                FPSMShopEvent.DataInit<T> event = new FPSMShopEvent.DataInit<>(this, entry.getKey(), candidate.graph, entry.getValue());
                MinecraftForge.EVENT_BUS.post(event);
                validate(event.getData(), candidate.graph);
                candidate.data = new RuntimeShopData<>(event.getData(), entry.getValue(), access);
                access.data = candidate.data;
            }
            for (Candidate candidate : candidates) {
                candidate.access.run();
                validate(candidate.data.getData(), candidate.graph);
                candidate.data.requireRuntimeGraph();
            }
            ImmutableMap.Builder<UUID, ShopData<T>> replacement = ImmutableMap.builder();
            if (!replaceAll) {
                for (var entry : table.current.entrySet()) {
                    if (!money.containsKey(entry.getKey())) replacement.put(entry);
                }
            }
            for (Candidate candidate : candidates) replacement.put(candidate.access.uuid, candidate.data);
            Map<UUID, ShopData<T>> ready = replacement.build();
            owner.requireAvailable();
            requireOwnershipAccess();
            // No callbacks or allocation between token activation and the sole map commit.
            for (int i = 0; i < accesses.size(); i++) accesses.get(i).state = 1;
            table.current = ready;
            committed = true;
            return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1).data;
        } finally {
            if (!committed) for (int i = 0; i < accesses.size(); i++) accesses.get(i).state = 2;
            publishing = false;
        }
    }

    private Candidate detached() {
        requireAvailable();
        Access access = new Access(null, true);
        publishing = true;
        boolean complete = false;
        try {
            Candidate candidate = new Candidate(access, copyGraph(access));
            candidate.data = new RuntimeShopData<>(candidate.graph, super.getStartMoney(), access);
            access.data = candidate.data;
            validate(candidate.data.getData(), candidate.graph);
            candidate.data.requireRuntimeGraph();
            requireOwnershipAccess();
            access.state = 1;
            complete = true;
            return candidate;
        } finally {
            if (!complete) access.state = 2;
            publishing = false;
        }
    }

    @Override
    public ShopData<T> getDefaultShopData() {
        return detached().data;
    }

    @Override
    public ImmutableMap<T, ImmutableList<ShopSlot>> getShopDataByRaw() {
        return detached().graph;
    }

    @Override
    public ShopData<T> getPlayerShopData(UUID uuid) {
        requireAvailable();
        if (uuid == null) throw RuntimeItemMessages.error("invalid_input");
        ShopData<T> result = table.current.get(uuid);
        if (result == null) result = getDefaultAndPutData(uuid, true);
        requireAvailable();
        return result;
    }

    @Override
    public ShopData<T> getPlayerShopData(ServerPlayer player) {
        requireAvailable();
        return getPlayerShopData(player.getUUID());
    }

    @Override
    public Optional<ShopData<T>> getPlayerShopDataSafe(UUID uuid) {
        requireAvailable();
        if (uuid == null) throw RuntimeItemMessages.error("invalid_input");
        Optional<ShopData<T>> result = Optional.ofNullable(table.current.get(uuid));
        requireAvailable();
        return result;
    }

    @Override
    public Optional<ShopData<T>> getPlayerShopDataSafe(ServerPlayer player) {
        requireAvailable();
        return getPlayerShopDataSafe(player.getUUID());
    }

    @Override
    public void getDefaultAndPutData(UUID uuid) {
        getDefaultAndPutData(uuid, false);
    }

    @Override
    public ShopData<T> getDefaultAndPutData(UUID uuid, boolean resetMoney) {
        requireAvailable();
        if (uuid == null) throw RuntimeItemMessages.error("invalid_input");
        return publish(moneyFor(List.of(uuid), resetMoney), false);
    }

    @Override
    public void resetPlayerData() {
        resetPlayerData(false);
    }

    @Override
    public void resetPlayerData(boolean resetMoney) {
        requireAvailable();
        publish(moneyFor(List.copyOf(table.current.keySet()), resetMoney), true);
    }

    @Override
    public void resetPlayerData(List<UUID> uuids) {
        requireAvailable();
        LinkedHashMap<UUID, Integer> money = new LinkedHashMap<>();
        ArrayList<Access> accesses = new ArrayList<>();
        ArrayList<Candidate> candidates = new ArrayList<>();
        publishing = true;
        try {
            if (uuids == null) throw RuntimeItemMessages.error("invalid_input");
            int count = uuids.size();
            owner.requireAvailable();
            budget(count);
            for (UUID uuid : uuids) {
                owner.requireAvailable();
                if (uuid == null) throw RuntimeItemMessages.error("invalid_input");
                if (money.containsKey(uuid)) throw RuntimeItemMessages.error("invalid_input");
                budget((long) money.size() + 1);
                money.put(uuid, super.getStartMoney());
            }
            publishInScope(money, true, accesses, candidates);
        } finally {
            publishing = false;
        }
    }

    @Override
    public void clearPlayerShopData() {
        requireAvailable();
        table.current = ImmutableMap.of();
    }

    @Override
    public void clearPlayerShopData(UUID uuid) {
        requireAvailable();
        if (uuid == null) throw RuntimeItemMessages.error("invalid_input");
        ImmutableMap.Builder<UUID, ShopData<T>> replacement = ImmutableMap.builder();
        for (var entry : table.current.entrySet()) if (!entry.getKey().equals(uuid)) replacement.put(entry);
        table.current = replacement.build();
    }

    @Override
    public Map<UUID, ShopData<T>> getPlayersData() {
        requireAvailable();
        return playersData;
    }

    @Override
    public int getStartMoney() {
        requireAvailable();
        int result = super.getStartMoney();
        requireAvailable();
        return result;
    }

    @Override
    public void setStartMoney(int money) {
        requireAvailable();
        super.setStartMoney(money);
        requireAvailable();
    }

    private RuntimeException editing() {
        requireAvailable();
        return RuntimeItemMessages.error("invalid_slot");
    }

    @Override
    public FPSMShop<T> withName(String name) {
        requireAvailable();
        if (this.name.equals(name)) return this;
        throw editing();
    }

    @Override
    public Map<T, ArrayList<ShopSlot>> getDefaultShopDataMap() {
        throw editing();
    }

    @Override
    public Map<String, List<ShopSlot>> getDefaultShopDataMapString() {
        throw editing();
    }

    @Override
    public List<ShopSlot> getDefaultShopSlotListByType(String type) {
        throw editing();
    }

    @Override
    public List<ShopSlot> getDefaultShopSlotListByType(T type) {
        throw editing();
    }

    @Override
    public ItemStack getDefaultShopDataItemStack(String type, int index) {
        throw editing();
    }

    @Override
    public Codec<FPSMShop<T>> getCodec() {
        throw editing();
    }

    @Override
    public void setDefaultShopData(Map<T, ArrayList<ShopSlot>> data) {
        throw editing();
    }

    @Override
    public void replaceDefaultShopData(String type, int index, ShopSlot slot) {
        throw editing();
    }

    @Override
    public void setDefaultShopDataGroupId(String type, int index, int group) {
        throw editing();
    }

    @Override
    public void addDefaultShopDataListenerModule(String type, int index, ListenerModule listener) {
        throw editing();
    }

    @Override
    public void removeDefaultShopDataListenerModule(String type, int index, String listener) {
        throw editing();
    }

    @Override
    public void setDefaultShopDataItemStack(String type, int index, ItemStack item) {
        throw editing();
    }

    @Override
    public void setDefaultShopDataCost(String type, int index, int cost) {
        throw editing();
    }

    @Override
    public boolean isConfigurationCompatible(FPSMShop<?> source) {
        throw editing();
    }

    @Override
    public void copyConfigurationFrom(FPSMShop<?> source) {
        throw editing();
    }

    @Override
    public void addArea(AreaData area) {
        throw editing();
    }

    @Override
    public void clearAreas() {
        throw editing();
    }

    @Override
    public void syncShopData() {
        requireAvailable();
        super.syncShopData();
        requireAvailable();
    }

    @Override
    public void syncShopMoneyData() {
        requireAvailable();
        super.syncShopMoneyData();
        requireAvailable();
    }

    @Override
    public void syncShopMoneyData(Collection<ServerPlayer> viewers) {
        requireAvailable();
        super.syncShopMoneyData(viewers);
        requireAvailable();
    }

    @Override
    public void syncShopMoneyData(UUID uuid) {
        requireAvailable();
        super.syncShopMoneyData(uuid);
        requireAvailable();
    }

    @Override
    public void syncShopMoneyData(ServerPlayer player) {
        requireAvailable();
        super.syncShopMoneyData(player);
        requireAvailable();
    }

    @Override
    public void syncShopData(List<ServerPlayer> players) {
        requireAvailable();
        super.syncShopData(players);
        requireAvailable();
    }

    @Override
    public void syncShopData(ServerPlayer player) {
        requireAvailable();
        super.syncShopData(player);
        requireAvailable();
    }

    /** Authorize identity before observing any part of a caller-supplied slot. */
    RuntimeShopSlot requirePlayerSlot(UUID uuid, String type, ShopSlot supplied) {
        requireAvailable();
        if (uuid == null || type == null || supplied == null) throw RuntimeItemMessages.error("invalid_slot");
        ShopData<T> data = table.current.get(uuid);
        if (data == null) throw RuntimeItemMessages.error("invalid_slot");
        final T resolved;
        try {
            resolved = valueOf(type);
        } catch (IllegalArgumentException invalidType) {
            throw RuntimeItemMessages.error("invalid_slot");
        }
        for (ShopSlot actual : data.getShopSlotsByType(resolved)) {
            if (actual == supplied) {
                RuntimeShopSlot slot = (RuntimeShopSlot) actual;
                slot.currentTemplate();
                requireAvailable();
                return slot;
            }
        }
        throw RuntimeItemMessages.error("invalid_slot");
    }

    @Override
    public void syncShopData(ServerPlayer player, String type, ShopSlot supplied) {
        requireAvailable();
        if (player == null) throw RuntimeItemMessages.error("invalid_input");
        RuntimeShopSlot slot = requirePlayerSlot(player.getUUID(), type, supplied);
        syncShopData(player, valueOf(type), slot.getIndex());
        requireAvailable();
    }

    @Override
    public void syncShopData(ServerPlayer player, T type, int index) {
        requireAvailable();
        if (player == null || type == null) throw RuntimeItemMessages.error("invalid_input");
        UUID uuid = player.getUUID();
        requireAvailable();
        ShopData<T> data = table.current.get(uuid);
        if (data == null || type.getDeclaringClass() != enumClass) throw RuntimeItemMessages.error("invalid_slot");
        List<ShopSlot> list = data.getShopSlotsByType(type);
        if (index < 0 || index >= list.size()) throw RuntimeItemMessages.error("invalid_slot");
        RuntimeShopSlot slot = (RuntimeShopSlot) list.get(index);
        slot.currentTemplate();
        ShopDataSlotS2CPacket packet = new ShopDataSlotS2CPacket(name, type, slot).revision(data.getRevision());
        requireAvailable();
        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        requireAvailable();
    }

    @Override
    public void sync() {
        requireAvailable();
        super.sync();
        requireAvailable();
    }

    @Override
    public void sync(ServerPlayer player) {
        requireAvailable();
        super.sync(player);
        requireAvailable();
    }

    @Override
    public void reduceMoney(ServerPlayer player, int amount) {
        requireAvailable();
        super.reduceMoney(player, amount);
        requireAvailable();
    }

    @Override
    public void addMoney(ServerPlayer player, int amount) {
        requireAvailable();
        super.addMoney(player, amount);
        requireAvailable();
    }

    @Override
    public void addMoney(UUID uuid, int amount) {
        requireAvailable();
        super.addMoney(uuid, amount);
        requireAvailable();
    }

    @Override
    public ShopActionResult handleButton(ServerPlayer player, INamedType type, int index, ShopAction action) {
        requireAvailable();
        ShopActionResult result = super.handleButton(player, type, index, action);
        requireAvailable();
        return result;
    }

    @Override
    public List<AreaData> getAreas() {
        requireAvailable();
        List<AreaData> result = super.getAreas();
        requireAvailable();
        return result;
    }

    @Override
    public void displayAreas(ServerPlayer player) {
        requireAvailable();
        super.displayAreas(player);
        requireAvailable();
    }

    @Override
    public boolean isInArea(Entity entity) {
        requireAvailable();
        boolean result = super.isInArea(entity);
        requireAvailable();
        return result;
    }
}
