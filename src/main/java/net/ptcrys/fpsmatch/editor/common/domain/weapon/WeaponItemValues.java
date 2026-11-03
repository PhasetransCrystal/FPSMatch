package net.ptcrys.fpsmatch.editor.common.domain.weapon;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItemModules;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemAdapter;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItems;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import java.util.Set;

/** Typed weapon declarations, separate from resource import, loaded indexes and native backends. */
public final class WeaponItemValues {

    public static final EditorId TACZ_MODULE = id("fpsmatch:editor_tacz_items");
    public static final EditorId TACZ_RUNTIME = id("fpsmatch:runtime_tacz");
    public static final EditorId TACZ_GUN = id("fpsmatch:tacz_gun");
    public static final EditorId LR_MODULE = id("fpsmatch:editor_lrtactical_items");
    public static final EditorId LR_RUNTIME = id("fpsmatch:runtime_lrtactical");
    public static final EditorId LR_MELEE = id("fpsmatch:lrtactical_melee");
    public static final EditorId TACZ_GUN_ASSET = id("fpsmatch:tacz_gun_asset");
    public static final EditorId TACZ_DISPLAY_ASSET = id("fpsmatch:tacz_display_asset");
    public static final EditorId TACZ_IMPORTER = id("fpsmatch:tacz_asset");
    public static final EditorId LR_MELEE_ASSET = id("fpsmatch:lrtactical_melee_asset");
    public static final EditorId LR_DISPLAY_ASSET = id("fpsmatch:lrtactical_display_asset");
    public static final EditorId LR_IMPORTER = id("fpsmatch:lrtactical_asset");

    private WeaponItemValues() {}

    /** The common vanilla runtime fingerprint must already be offered by its integration. */
    public static void registerTacz(ExtensionRegistryBuilder builder, String integrationBuildVersion,
                                    String taczBuildVersion) {
        ModuleSpec runtime = module(TACZ_RUNTIME, taczBuildVersion, Set.of());
        ModuleSpec integration = module(TACZ_MODULE, integrationBuildVersion,
                Set.of(TACZ_RUNTIME, BasicItemModules.VANILLA_RUNTIME));
        TaczGunType type = new TaczGunType();
        builder.addModule(runtime);
        register(builder, integration, TACZ_GUN, type, type);
    }

    /** LR requires both the declared Minecraft and TACZ runtime fingerprints. */
    public static void registerLrTactical(ExtensionRegistryBuilder builder, String integrationBuildVersion,
                                          String lrBuildVersion) {
        ModuleSpec runtime = module(LR_RUNTIME, lrBuildVersion, Set.of());
        ModuleSpec integration = module(LR_MODULE, integrationBuildVersion,
                Set.of(LR_RUNTIME, TACZ_RUNTIME, BasicItemModules.VANILLA_RUNTIME));
        LrMeleeType type = new LrMeleeType();
        builder.addModule(runtime);
        register(builder, integration, LR_MELEE, type, type);
    }

    private static ModuleSpec module(EditorId id, String version, Set<EditorId> required) {
        return new ModuleSpec(id, version, new ApiVersion(1, 0), required, Set.of());
    }

    private static void register(ExtensionRegistryBuilder builder, ModuleSpec module, EditorId typeId,
                                 ObjectType<?> type, LegacyItemAdapter adapter) {
        builder.define(ValueTypes.KIND);
        builder.define(LegacyItems.KIND);
        builder.addModule(module);
        ContributionKey value = new ContributionKey(ValueTypes.KIND.id(), typeId);
        builder.add(ValueTypes.KIND, new Contribution(value, module.id(),
                Contribution.Side.COMMON, Set.of()), type);
        builder.add(LegacyItems.KIND, new Contribution(new ContributionKey(LegacyItems.KIND.id(), typeId),
                module.id(), Contribution.Side.COMMON, Set.of(value)), adapter);
        // A codec declaration does not claim any persisted asset format is supported. Real resource
        // producers are registered only alongside the importer/format that actually implements it.
    }

    private static EditorId id(String value) {
        return EditorId.parse(value);
    }
}
