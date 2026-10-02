package net.ptcrys.fpsmatch.common.shop.editor;

import net.ptcrys.fpsmatch.common.shop.functional.ChangeShopItemModule;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Server catalog, editable data and configuration references; never executable client logic. */
public record ListenerModuleSnapshot(String revision, List<Module> modules) {

    public static final int MAX_REFERENCES = 16384;

    public ListenerModuleSnapshot {
        modules = List.copyOf(modules);
        if (modules.size() > ShopEditorSnapshot.MAX_CATALOG || modules.stream().mapToInt(m -> m.references().size()).sum() > MAX_REFERENCES)
            throw new IllegalArgumentException("Module catalog exceeds editor limits");
    }

    public record Reference(ShopEditorSnapshot.Target target, String type, int index, int group) {

        void write(FriendlyByteBuf buf) {
            target.write(buf);
            buf.writeUtf(type, 128);
            buf.writeInt(index);
            buf.writeInt(group);
        }

        static Reference read(FriendlyByteBuf buf) {
            return new Reference(ShopEditorSnapshot.Target.read(buf), buf.readUtf(128), buf.readInt(), buf.readInt());
        }
    }

    public record Definition(String name, ItemStack defaultItem, int defaultCost, ItemStack changedItem, int changedCost) {

        public Definition {
            defaultItem = defaultItem.copy();
            changedItem = changedItem.copy();
        }

        @Override
        public ItemStack defaultItem() {
            return defaultItem.copy();
        }

        @Override
        public ItemStack changedItem() {
            return changedItem.copy();
        }

        public ChangeShopItemModule create() {
            return new ChangeShopItemModule(defaultItem, defaultCost, changedItem, changedCost, name);
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(name, 256);
            buf.writeNbt(defaultItem.save(new CompoundTag()));
            buf.writeInt(defaultCost);
            buf.writeNbt(changedItem.save(new CompoundTag()));
            buf.writeInt(changedCost);
        }

        public static Definition read(FriendlyByteBuf buf) {
            String name = buf.readUtf(256);
            CompoundTag original = buf.readNbt();
            int originalCost = buf.readInt();
            CompoundTag changed = buf.readNbt();
            return new Definition(name, original == null ? ItemStack.EMPTY : ItemStack.of(original), originalCost,
                    changed == null ? ItemStack.EMPTY : ItemStack.of(changed), buf.readInt());
        }
    }

    public record Module(String name, int priority, Definition definition, boolean editable, List<Reference> references) {

        public Module {
            references = List.copyOf(references);
        }
    }

    public static Module describe(ListenerModule module, List<Reference> references) {
        Definition definition = module instanceof ChangeShopItemModule change ? new Definition(module.getName(), change.defaultItem(), change.defaultCost(), change.changedItem(), change.changedCost()) : null;
        return new Module(module.getName(), module.getPriority(), definition, definition != null, references);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(revision, 64);
        buf.writeCollection(modules, (out, module) -> {
            out.writeUtf(module.name(), 256);
            out.writeInt(module.priority());
            out.writeBoolean(module.definition() != null);
            if (module.definition() != null) module.definition().write(out);
            out.writeBoolean(module.editable());
            out.writeCollection(module.references(), (referenceBuf, reference) -> reference.write(referenceBuf));
        });
    }

    public static ListenerModuleSnapshot read(FriendlyByteBuf buf) {
        String revision = buf.readUtf(64);
        int count = buf.readVarInt();
        if (count < 0 || count > ShopEditorSnapshot.MAX_CATALOG) throw new IllegalArgumentException("Too many modules");
        List<Module> modules = new ArrayList<>();
        int remaining = MAX_REFERENCES;
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf(256);
            int priority = buf.readInt();
            Definition definition = buf.readBoolean() ? Definition.read(buf) : null;
            boolean editable = buf.readBoolean();
            var references = buf.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, remaining), Reference::read);
            remaining -= references.size();
            modules.add(new Module(name, priority, definition, editable, references));
        }
        return new ListenerModuleSnapshot(revision, modules);
    }
}
