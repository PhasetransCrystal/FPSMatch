package net.ptcrys.fpsmatch.editor.common.domain.spatial;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import java.util.Set;

/** Ordinary extension assembly; the caller owns registry freezing and activation. */
public final class SpatialValues {

    public static final EditorId MODULE = EditorId.parse("fpsmatch:editor_map_values");
    public static final EditorId POINT = EditorId.parse("fpsmatch:map_point");
    public static final EditorId AREA = EditorId.parse("fpsmatch:map_area");

    private SpatialValues() {}

    public static void register(ExtensionRegistryBuilder builder, String buildVersion) {
        builder.define(ValueTypes.KIND);
        builder.addModule(new ModuleSpec(MODULE, buildVersion, new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.add(ValueTypes.KIND, new Contribution(new ContributionKey(ValueTypes.KIND.id(), POINT),
                MODULE, Contribution.Side.COMMON, Set.of()), new MapPointType());
        builder.add(ValueTypes.KIND, new Contribution(new ContributionKey(ValueTypes.KIND.id(), AREA),
                MODULE, Contribution.Side.COMMON, Set.of()), new MapAreaType());
    }
}
