package net.ptcrys.fpsmatch.editor.common.domain.item;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemAdapter;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemEncoding;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** An explicit, bounded item profile shared by current codecs and legacy matching. */
public final class BasicItemType implements ObjectType<BasicItem>, LegacyItemAdapter {

    public static final int MAX_ITEMS = 8192;

    public enum TagMode {
        EMPTY,
        DAMAGE
    }

    private final EditorId typeId;
    private final EditorId codecId;
    private final Map<EditorId, TagMode> acceptedItems;

    public BasicItemType(EditorId typeId, Map<EditorId, TagMode> acceptedItems) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(acceptedItems, "acceptedItems");
        int size = acceptedItems.size();
        if (size < 1 || size > MAX_ITEMS) throw new IllegalArgumentException("acceptedItems size");
        TreeMap<EditorId, TagMode> copy = new TreeMap<>();
        acceptedItems.forEach((id, mode) -> copy.put(Objects.requireNonNull(id, "itemId"),
                Objects.requireNonNull(mode, "tagMode")));
        this.acceptedItems = Collections.unmodifiableMap(copy);
        this.codecId = new EditorId(typeId.namespace(), typeId.path() + "_json");
    }

    public EditorId typeId() {
        return typeId;
    }

    public Map<EditorId, TagMode> acceptedItems() {
        return acceptedItems;
    }

    @Override
    public Class<BasicItem> valueType() {
        return BasicItem.class;
    }

    @Override
    public EditorId role() {
        return EditorId.parse("fpsmatch:item");
    }

    @Override
    public EditorId codecId() {
        return codecId;
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
    public BasicItem snapshot(BasicItem value) {
        if (value == null) throw BasicItemCodec.field("", "basic_item");
        BasicItemCodec.validate(value, acceptedItems);
        return value;
    }

    @Override
    public List<Problem> validate(BasicItem value) {
        snapshot(value);
        return List.of();
    }

    @Override
    public DocumentDependencies dependencies(BasicItem value) {
        snapshot(value);
        return BasicItemCodec.EMPTY_DEPENDENCIES;
    }

    @Override
    public byte[] encode(BasicItem value) {
        snapshot(value);
        JsonObject json = new JsonObject();
        json.addProperty("itemId", value.itemId().toString());
        json.addProperty("count", value.count());
        if (value.damage().isPresent()) json.addProperty("damage", value.damage().getAsInt());
        else json.add("damage", JsonNull.INSTANCE);
        return BasicItemCodec.freeze(json).canonicalUtf8();
    }

    @Override
    public BasicItem decode(int sourceSchema, byte[] encoding) {
        return BasicItemCodec.current(BasicItemCodec.read(sourceSchema, encoding), acceptedItems);
    }

    @Override
    public Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item) {
        Objects.requireNonNull(item, "item");
        final BasicItem value;
        try {
            value = BasicItemCodec.legacy(item.toJson(), acceptedItems);
        } catch (DocumentCodecException invalidPayload) {
            return Optional.empty();
        }
        return Optional.of(new LegacyItemEncoding(typeId, codecId, schemaVersion(), encode(value)));
    }
}
