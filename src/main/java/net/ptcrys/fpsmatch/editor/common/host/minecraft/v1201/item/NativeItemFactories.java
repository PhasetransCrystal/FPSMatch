package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import java.util.Objects;
import java.util.Set;

/** Native item factories share the original registry and the corresponding pure type identity. */
public final class NativeItemFactories {

    public static final ExtensionKind<NativeItemFactory<?>> KIND = factoryKind();

    private NativeItemFactories() {}

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ExtensionKind<NativeItemFactory<?>> factoryKind() {
        return (ExtensionKind<NativeItemFactory<?>>) (ExtensionKind<?>) new ExtensionKind<>(
                EditorId.parse("editor:native_item_factory"), NativeItemFactory.class);
    }

    public static void register(ExtensionRegistryBuilder builder, EditorId typeId,
                                EditorId owner, NativeItemFactory<?> factory) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(factory, "factory");
        builder.define(KIND);
        builder.add(KIND, new Contribution(new ContributionKey(KIND.id(), typeId), owner,
                Contribution.Side.COMMON,
                Set.of(new ContributionKey(ValueTypes.KIND.id(), typeId))), factory);
    }
}
