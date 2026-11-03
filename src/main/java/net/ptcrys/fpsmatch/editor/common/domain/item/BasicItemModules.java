package net.ptcrys.fpsmatch.editor.common.domain.item;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItems;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import java.util.Map;
import java.util.Set;

/** Explicit integration profiles; versions are supplied by the host, never guessed from item IDs. */
public final class BasicItemModules {

    public static final EditorId VANILLA_MODULE = id("fpsmatch:editor_vanilla_items");
    public static final EditorId VANILLA_RUNTIME = id("fpsmatch:runtime_minecraft");
    public static final EditorId VANILLA_ITEM = id("fpsmatch:vanilla_item");
    public static final EditorId FPSMATCH_MODULE = id("fpsmatch:editor_fpsmatch_items");
    public static final EditorId FPSMATCH_ITEM = id("fpsmatch:registered_item");
    public static final EditorId CSGRENADES_MODULE = id("fpsmatch:editor_csgrenades_items");
    public static final EditorId CSGRENADES_RUNTIME = id("fpsmatch:runtime_csgrenades");
    public static final EditorId CSGRENADES_ITEM = id("fpsmatch:csgrenades_item");

    private BasicItemModules() {}

    public static void registerVanilla(ExtensionRegistryBuilder builder,
                                       String integrationBuildVersion, String minecraftBuildVersion) {
        ModuleSpec runtime = module(VANILLA_RUNTIME, minecraftBuildVersion, Set.of());
        ModuleSpec integration = module(VANILLA_MODULE, integrationBuildVersion, Set.of(VANILLA_RUNTIME));
        BasicItemType type = new BasicItemType(VANILLA_ITEM, Map.of(
                id("minecraft:apple"), BasicItemType.TagMode.EMPTY,
                id("minecraft:golden_apple"), BasicItemType.TagMode.EMPTY,
                id("minecraft:diamond"), BasicItemType.TagMode.EMPTY,
                id("minecraft:jungle_log"), BasicItemType.TagMode.EMPTY,
                id("minecraft:iron_helmet"), BasicItemType.TagMode.DAMAGE));
        builder.addModule(runtime);
        register(builder, integration, type);
    }

    public static void registerFpsMatch(ExtensionRegistryBuilder builder, String buildVersion) {
        register(builder, module(FPSMATCH_MODULE, buildVersion, Set.of()),
                new BasicItemType(FPSMATCH_ITEM,
                        Map.of(id("fpsmatch:flash_bomb"), BasicItemType.TagMode.EMPTY)));
    }

    public static void registerCsGrenades(ExtensionRegistryBuilder builder,
                                          String integrationBuildVersion, String csGrenadesBuildVersion) {
        ModuleSpec runtime = module(CSGRENADES_RUNTIME, csGrenadesBuildVersion, Set.of());
        ModuleSpec integration = module(CSGRENADES_MODULE, integrationBuildVersion, Set.of(CSGRENADES_RUNTIME));
        BasicItemType type = new BasicItemType(CSGRENADES_ITEM,
                Map.of(id("csgrenades:molotov"), BasicItemType.TagMode.EMPTY));
        builder.addModule(runtime);
        register(builder, integration, type);
    }

    private static ModuleSpec module(EditorId id, String version, Set<EditorId> requires) {
        return new ModuleSpec(id, version, new ApiVersion(1, 0), requires, Set.of());
    }

    private static void register(ExtensionRegistryBuilder builder, ModuleSpec module, BasicItemType type) {
        builder.define(ValueTypes.KIND);
        builder.define(LegacyItems.KIND);
        builder.addModule(module);
        ContributionKey value = new ContributionKey(ValueTypes.KIND.id(), type.typeId());
        builder.add(ValueTypes.KIND, new Contribution(value, module.id(),
                Contribution.Side.COMMON, Set.of()), type);
        builder.add(LegacyItems.KIND, new Contribution(
                new ContributionKey(LegacyItems.KIND.id(), type.typeId()), module.id(),
                Contribution.Side.COMMON, Set.of(value)), type);
    }

    private static EditorId id(String value) {
        return EditorId.parse(value);
    }
}
