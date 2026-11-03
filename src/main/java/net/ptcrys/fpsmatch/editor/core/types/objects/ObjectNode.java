package net.ptcrys.fpsmatch.editor.core.types.objects;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueSnapshot;

import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Immutable node issued by one catalog. Its position role and value role are distinct. */
public final class ObjectNode {

    private final ObjectCatalog issuer;
    private final UUID id;
    private final Optional<UUID> parent;
    private final EditorId role;
    private final EditorId valueRole;
    private final ValueSnapshot<?> value;
    private final Set<ObjectReference> references;

    ObjectNode(ObjectCatalog issuer, UUID id, Optional<UUID> parent, EditorId role,
               EditorId valueRole, ValueSnapshot<?> value, Set<ObjectReference> references) {
        this.issuer = issuer;
        this.id = id;
        this.parent = parent;
        this.role = role;
        this.valueRole = valueRole;
        this.value = value;
        TreeSet<ObjectReference> copy = new TreeSet<>(Comparator.comparing(ObjectReference::property)
                .thenComparing(ObjectReference::target));
        copy.addAll(references);
        this.references = Collections.unmodifiableSet(copy);
    }

    public UUID id() {
        return id;
    }

    public Optional<UUID> parent() {
        return parent;
    }

    public EditorId role() {
        return role;
    }

    public EditorId valueRole() {
        return valueRole;
    }

    public ValueSnapshot<?> value() {
        return value;
    }

    public Set<ObjectReference> references() {
        return references;
    }

    boolean issuedBy(ObjectCatalog catalog) {
        return issuer == catalog;
    }
}
