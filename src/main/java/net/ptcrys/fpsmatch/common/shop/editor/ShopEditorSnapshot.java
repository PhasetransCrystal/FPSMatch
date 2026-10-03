package net.ptcrys.fpsmatch.common.shop.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Configuration data only; decoding never resolves server-side listener instances. */
public record ShopEditorSnapshot(Target target, String revision, List<Category> categories, List<String> availableModules) {

    public static final int MAX_SLOTS = 512;
    public static final int MAX_MODULES = 64;
    public static final int MAX_CATALOG = 1024;
    public static final int ID_LENGTH = 128;

    public ShopEditorSnapshot {
        categories = List.copyOf(categories);
        availableModules = List.copyOf(availableModules);
        if (categories.size() > MAX_SLOTS || categories.stream().mapToInt(category -> category.slots().size()).sum() > MAX_SLOTS || availableModules.size() > MAX_CATALOG) throw new IllegalArgumentException("Shop configuration exceeds editor limits");
    }

    public record Target(String gameType, String mapName, String teamName) {

        public boolean valid() {
            return validId(gameType) && validId(mapName) && validId(teamName);
        }

        private static boolean validId(String value) {
            return value != null && !value.isBlank() && value.length() <= ID_LENGTH;
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(gameType, ID_LENGTH);
            buf.writeUtf(mapName, ID_LENGTH);
            buf.writeUtf(teamName, ID_LENGTH);
        }

        public static Target read(FriendlyByteBuf buf) {
            return new Target(buf.readUtf(ID_LENGTH), buf.readUtf(ID_LENGTH), buf.readUtf(ID_LENGTH));
        }
    }

    public record Category(String name, List<Slot> slots) {

        public Category {
            slots = List.copyOf(slots);
        }
    }

    public record Slot(ItemStack item, int price, int ammo, int group, int maxBuyCount, List<String> modules) {

        public Slot {
            item = item.copy();
            modules = List.copyOf(modules);
            if (modules.size() > MAX_MODULES) throw new IllegalArgumentException("Too many listener modules");
        }

        @Override
        public ItemStack item() {
            return item.copy();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeNbt(item.save(new CompoundTag()));
            buf.writeInt(price);
            buf.writeInt(ammo);
            buf.writeInt(group);
            buf.writeInt(maxBuyCount);
            buf.writeCollection(modules, (out, name) -> out.writeUtf(name, 256));
        }

        public static Slot read(FriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new Slot(tag == null ? ItemStack.EMPTY : ItemStack.of(tag), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, MAX_MODULES), in -> in.readUtf(256)));
        }
    }

    public List<Slot> slots() {
        return categories.stream().flatMap(category -> category.slots().stream()).toList();
    }

    public void write(FriendlyByteBuf buf) {
        target.write(buf);
        buf.writeUtf(revision, 64);
        buf.writeCollection(categories, (out, category) -> {
            out.writeUtf(category.name(), ID_LENGTH);
            out.writeCollection(category.slots(), (slotBuf, slot) -> slot.write(slotBuf));
        });
        buf.writeCollection(availableModules, (out, name) -> out.writeUtf(name, 256));
    }

    public static ShopEditorSnapshot read(FriendlyByteBuf buf) {
        Target target = Target.read(buf);
        String revision = buf.readUtf(64);
        int categoryCount = buf.readVarInt();
        if (categoryCount < 0 || categoryCount > MAX_SLOTS) throw new IllegalArgumentException("Too many shop categories");
        List<Category> categories = new ArrayList<>();
        int remaining = MAX_SLOTS;
        for (int i = 0; i < categoryCount; i++) {
            String name = buf.readUtf(ID_LENGTH);
            List<Slot> slots = buf.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, remaining), Slot::read);
            remaining -= slots.size();
            categories.add(new Category(name, slots));
        }
        return new ShopEditorSnapshot(target, revision, categories,
                buf.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, MAX_CATALOG), in -> in.readUtf(256)));
    }
}
