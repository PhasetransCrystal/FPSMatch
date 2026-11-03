package net.ptcrys.fpsmatch.editor.common.domain.map;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemEncoding;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItems;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.Set;

/** Pure built-in map values and the narrowly scoped legacy air adapter. */
public final class MapDomainValues {

    public static final EditorId MODULE = EditorId.parse("fpsmatch:editor_map_objects");
    public static final EditorId TEAM = EditorId.parse("fpsmatch:team");
    public static final EditorId SHOP = EditorId.parse("fpsmatch:shop");
    public static final EditorId EMPTY_ITEM = EditorId.parse("fpsmatch:empty_item");
    public static final EditorId RETURN_GOODS = EditorId.parse("fpsmatch:return_goods");
    public static final EditorId CHANGE_ITEM = EditorId.parse("fpsmatch:change_shop_item");

    private MapDomainValues() {}

    public static void register(ExtensionRegistryBuilder builder, String buildVersion) {
        builder.define(ValueTypes.KIND);
        builder.define(LegacyItems.KIND);
        builder.addModule(new ModuleSpec(MODULE, buildVersion, new ApiVersion(1, 0),
                Set.of(), Set.of()));
        add(builder, TEAM, MapTeam.class, "fpsmatch:team");
        add(builder, SHOP, MapShop.class, "fpsmatch:shop");
        add(builder, EMPTY_ITEM, EmptyItem.class, "fpsmatch:item");
        add(builder, RETURN_GOODS, ReturnGoods.class, "fpsmatch:shop_listener");
        add(builder, CHANGE_ITEM, ChangeShopItem.class, "fpsmatch:shop_listener");
        builder.add(LegacyItems.KIND, new Contribution(
                new ContributionKey(LegacyItems.KIND.id(), EMPTY_ITEM), MODULE,
                Contribution.Side.COMMON,
                Set.of(new ContributionKey(ValueTypes.KIND.id(), EMPTY_ITEM))),
                MapDomainValues::convertEmpty);
    }

    private static Optional<LegacyItemEncoding> convertEmpty(ExactJson.FrozenJson item) {
        JsonElement value = item.toJson();
        if (!value.isJsonObject()) return Optional.empty();
        JsonObject object = value.getAsJsonObject();
        if (!object.keySet().equals(Set.of("id", "Count"))) return Optional.empty();
        JsonElement id = object.get("id");
        JsonElement count = object.get("Count");
        if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !id.getAsString().equals("minecraft:air") || !count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber() || count.getAsBigDecimal().signum() != 0) {
            return Optional.empty();
        }
        return Optional.of(new LegacyItemEncoding(EMPTY_ITEM,
                EditorId.parse("fpsmatch:empty_item_json"), 1, new byte[] { 123, 125 }));
    }

    private static <T> void add(ExtensionRegistryBuilder builder, EditorId id,
                                Class<T> type, String role) {
        builder.add(ValueTypes.KIND, new Contribution(
                new ContributionKey(ValueTypes.KIND.id(), id), MODULE,
                Contribution.Side.COMMON, Set.of()), new MapTypes<>(type, id, role));
    }
}
