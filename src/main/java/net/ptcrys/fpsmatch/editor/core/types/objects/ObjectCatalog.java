package net.ptcrys.fpsmatch.editor.core.types.objects;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.values.ActivatedValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueSnapshot;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Object capabilities on the actual value catalog, with catalog-local node ownership. */
public final class ObjectCatalog {

    private record Entry<T>(ActivatedValueType<T> codec, ObjectType<T> implementation, EditorId role) {}

    private final Map<EditorId, Entry<?>> entries;
    private final Map<EditorId, RuntimeException> failures;

    private ObjectCatalog(Map<EditorId, Entry<?>> entries, Map<EditorId, RuntimeException> failures) {
        this.entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        this.failures = Collections.unmodifiableMap(new TreeMap<>(failures));
    }

    public static ObjectCatalog activate(ExtensionRegistry registry) {
        if (registry == null) throw ObjectMessages.node("objects", "registry");
        ValueTypeCatalog values = ValueTypeCatalog.activate(registry);
        Map<EditorId, Entry<?>> entries = new TreeMap<>();
        Map<EditorId, RuntimeException> failures = new TreeMap<>();
        for (Map.Entry<EditorId, ValueType<?>> item : registry.entries(ValueTypes.KIND).entrySet()) {
            if (!(item.getValue() instanceof ObjectType<?> object)) continue;
            RuntimeException activationFailure = values.activationFailures().get(item.getKey());
            if (activationFailure != null) {
                failures.put(item.getKey(), activationFailure);
                continue;
            }
            try {
                entries.put(item.getKey(), entry(values.find(item.getKey()).orElseThrow(), object));
            } catch (RuntimeException failure) {
                failures.put(item.getKey(), failure);
            }
        }
        return new ObjectCatalog(entries, failures);
    }

    private static <T> Entry<T> entry(ActivatedValueType<T> codec, ObjectType<?> implementation) {
        EditorId role = implementation.role();
        if (role == null) throw ObjectMessages.node(codec.id(), "role");
        // Both handles come from the same registry entry. No second activation occurs here.
        @SuppressWarnings("unchecked")
        ObjectType<T> typed = (ObjectType<T>) implementation;
        return new Entry<>(codec, typed, role);
    }

    public Map<EditorId, RuntimeException> activationFailures() {
        return failures;
    }

    public boolean owns(ObjectNode node) {
        return node != null && node.issuedBy(this);
    }

    public <T> ObjectNode capture(UUID id, Optional<UUID> parent, EditorId role,
                                  EditorId typeId, Class<T> valueType, T value) {
        requirePosition(id, parent, role);
        Entry<?> entry = requireType(typeId);
        if (valueType == null || entry.codec().valueType() != valueType) {
            throw ObjectMessages.node(id, "type_class");
        }
        return captureOne(id, parent, role, entry, value);
    }

    private <T> ObjectNode captureOne(UUID id, Optional<UUID> parent, EditorId role,
                                      Entry<T> entry, Object value) {
        try {
            if (value == null || !entry.codec().valueType().isInstance(value)) {
                throw ObjectMessages.node(id, "type_class");
            }
            return issue(id, parent, role, entry, entry.codec().capture(entry.codec().valueType().cast(value)));
        } catch (DocumentCodecException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw ObjectMessages.node(id, "capture");
        }
    }

    public ObjectNode decode(UUID id, Optional<UUID> parent, EditorId role,
                             EditorId typeId, EditorId codecId, int schema, byte[] bytes) {
        requirePosition(id, parent, role);
        Entry<?> entry = requireType(typeId);
        if (codecId == null || schema <= 0 || bytes == null) throw ObjectMessages.node(id, "encoding");
        ObjectMessages.requireBudget("bytes", bytes.length, ObjectGraphCodec.MAX_BYTES);
        return decodeOne(id, parent, role, entry, codecId, schema, bytes);
    }

    private <T> ObjectNode decodeOne(UUID id, Optional<UUID> parent, EditorId role,
                                     Entry<T> entry, EditorId codecId, int schema, byte[] bytes) {
        try {
            return issue(id, parent, role, entry, entry.codec().decode(codecId, schema, bytes));
        } catch (DocumentCodecException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw ObjectMessages.node(id, "decode");
        }
    }

    private <T> ObjectNode issue(UUID id, Optional<UUID> parent, EditorId role,
                                 Entry<T> entry, ValueSnapshot<T> value) {
        Set<ObjectReference> references;
        try {
            references = entry.implementation().references(entry.codec().valueType().cast(value.content()));
            if (references == null) throw ObjectMessages.node(id, "refs");
            ObjectMessages.requireBudget("references", references.size(), ObjectGraphCodec.MAX_OBJECTS);
            Set<ObjectReference> copy = new HashSet<>();
            for (ObjectReference reference : references) {
                if (reference == null || reference.property() == null || reference.target() == null) {
                    throw ObjectMessages.node(id, "refs");
                }
                copy.add(reference);
                ObjectMessages.requireBudget("references", copy.size(), ObjectGraphCodec.MAX_OBJECTS);
            }
            return new ObjectNode(this, id, parent, role, entry.role(), value, copy);
        } catch (DocumentCodecException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw ObjectMessages.node(id, "refs");
        }
    }

    public ObjectNode adopt(ObjectNode node) {
        if (node == null) throw ObjectMessages.node("objects", "missing_node");
        if (owns(node)) return node;
        ValueSnapshot<?> value = node.value();
        return decode(node.id(), node.parent(), node.role(), value.typeId(), value.codecId(),
                value.schemaVersion(), value.encoding());
    }

    private Entry<?> requireType(EditorId typeId) {
        if (typeId == null) throw ObjectMessages.unavailable(null);
        Entry<?> entry = entries.get(typeId);
        if (entry == null) throw ObjectMessages.unavailable(typeId);
        return entry;
    }

    private static void requirePosition(UUID id, Optional<UUID> parent, EditorId role) {
        if (id == null || parent == null || role == null) throw ObjectMessages.node(id, "position");
    }
}
