package net.ptcrys.fpsmatch.editor.common.domain.map;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.Set;

final class MapTypes<T> implements ObjectType<T> {

    private final Class<T> type;
    private final EditorId id, role;

    MapTypes(Class<T> type, EditorId id, String role) {
        this.type = type;
        this.id = id;
        this.role = EditorId.parse(role);
    }

    @Override
    public Class<T> valueType() {
        return type;
    }

    @Override
    public EditorId role() {
        return role;
    }

    @Override
    public EditorId codecId() {
        return new EditorId(id.namespace(), id.path() + "_json");
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Set<Integer> readableSchemas() {
        return Set.of(1);
    }

    @Override
    public T snapshot(T value) {
        return type.cast(Objects.requireNonNull(value));
    }

    @Override
    public List<Problem> validate(T value) {
        snapshot(value);
        return List.of();
    }

    @Override
    public DocumentDependencies dependencies(T value) {
        snapshot(value);
        return MapCodec.EMPTY_DEPENDENCIES;
    }

    @Override
    public byte[] encode(T value) {
        snapshot(value);
        JsonObject json = new JsonObject();
        if (value instanceof MapTeam team) json.addProperty("name", team.name());
        else if (value instanceof MapShop shop) {
            json.addProperty("sourceName", shop.sourceName());
            json.addProperty("defaultMoney", shop.defaultMoney());
        } else if (value instanceof ChangeShopItem change) {
            json.addProperty("defaultCost", change.defaultCost());
            json.addProperty("changedCost", change.changedCost());
        }
        return MapCodec.freeze(json).canonicalUtf8();
    }

    @Override
    public T decode(int schema, byte[] bytes) {
        List<String> fields;
        if (type == MapTeam.class) fields = List.of("name");
        else if (type == MapShop.class) fields = List.of("sourceName", "defaultMoney");
        else if (type == ChangeShopItem.class) fields = List.of("defaultCost", "changedCost");
        else fields = List.of();
        JsonObject j = MapCodec.object(MapCodec.read(schema, bytes), "", fields);
        Object value;
        if (type == MapTeam.class) value = new MapTeam(MapCodec.text(j.get("name"), "/name"));
        else if (type == MapShop.class) value = new MapShop(MapCodec.text(j.get("sourceName"), "/sourceName"),
                MapCodec.integerValue(j.get("defaultMoney"), "/defaultMoney"));
        else if (type == ChangeShopItem.class) value = new ChangeShopItem(
                MapCodec.integerValue(j.get("defaultCost"), "/defaultCost"),
                MapCodec.integerValue(j.get("changedCost"), "/changedCost"));
        else value = type == EmptyItem.class ? EmptyItem.INSTANCE : ReturnGoods.INSTANCE;
        return type.cast(value);
    }
}
