package net.ptcrys.fpsmatch.core.shop.runtime;

import net.ptcrys.fpsmatch.core.persistence.RuntimeItemStackCodec;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import java.util.ArrayList;
import java.util.List;

/** Ordered fixed kit handles; the shared candidate owner retains retirement authority. */
public final class RuntimeKitTemplates {

    private final RuntimeItemTemplates owner;
    private final List<RuntimeItemTemplates.Template> templates;

    public RuntimeKitTemplates(RuntimeItemTemplates owner, List<RuntimeItemTemplates.Template> templates) {
        if (owner == null) throw RuntimeItemMessages.error("invalid_input");
        owner.requireAvailable();
        if (templates == null) throw RuntimeItemMessages.error("invalid_input");
        if (templates.size() > 8192) throw RuntimeItemMessages.error("budget");
        ArrayList<RuntimeItemTemplates.Template> captured = new ArrayList<>();
        for (RuntimeItemTemplates.Template template : templates) {
            if (captured.size() == 8192) throw RuntimeItemMessages.error("budget");
            if (template == null) throw RuntimeItemMessages.error("invalid_input");
            if (!owner.owns(template)) throw RuntimeItemMessages.error("foreign_template");
            template.requireAvailable();
            captured.add(template);
        }
        owner.requireAvailable();
        this.owner = owner;
        this.templates = List.copyOf(captured);
    }

    public List<RuntimeItemTemplates.Template> templates() {
        requireAvailable();
        return templates;
    }

    public void requireAvailable() {
        owner.requireAvailable();
    }

    public boolean isEmpty(int index) {
        return template(index).inspect(ItemStack::isEmpty);
    }

    public ItemStack createDelivery(int index) {
        return template(index).get();
    }

    public List<Component> displayNames() {
        requireAvailable();
        ArrayList<Component> names = new ArrayList<>(templates.size());
        for (RuntimeItemTemplates.Template template : templates) {
            names.add(template.inspect(stack -> stack.getDisplayName().copy()));
        }
        return List.copyOf(names);
    }

    public JsonArray toJson() {
        requireAvailable();
        JsonArray result = new JsonArray();
        for (RuntimeItemTemplates.Template template : templates) {
            JsonElement encoded = template.inspect(stack -> RuntimeItemStackCodec.CODEC.encodeStart(JsonOps.INSTANCE, stack)
                    .getOrThrow(false, ignored -> { throw RuntimeItemMessages.error("invalid_copy"); }).deepCopy());
            result.add(encoded);
        }
        return result;
    }

    private RuntimeItemTemplates.Template template(int index) {
        requireAvailable();
        if (index < 0 || index >= templates.size()) throw RuntimeItemMessages.error("invalid_slot");
        return templates.get(index);
    }
}
