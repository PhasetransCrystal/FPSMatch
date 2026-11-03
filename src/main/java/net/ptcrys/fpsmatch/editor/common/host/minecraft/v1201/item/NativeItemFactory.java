package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Function;

/** Trusted synchronous construction of a borrowed native value from an authenticated pure value. */
public record NativeItemFactory<T>(Class<T> valueType, boolean permitsEmpty,
                                   Function<T, ItemStack> factory) {

    public NativeItemFactory {
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(factory, "factory");
        if (valueType.isPrimitive() || valueType == void.class) {
            throw new IllegalArgumentException("native item value type must be a reference type");
        }
    }
}
