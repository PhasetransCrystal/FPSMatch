package net.ptcrys.fpsmatch.editor.common.domain.weapon;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;

import java.util.Objects;
import java.util.Optional;

/** Pure melee state with an optional explicit display selection. */
public record LrMelee(EditorId weaponId, ResourceRef weaponResource,
                      Optional<EditorId> displayId, Optional<ResourceRef> displayResource,
                      int count, int damage) {

    public LrMelee {
        Objects.requireNonNull(weaponId, "weaponId");
        Objects.requireNonNull(weaponResource, "weaponResource");
        Objects.requireNonNull(displayId, "displayId");
        Objects.requireNonNull(displayResource, "displayResource");
        EditorId empty = EditorId.parse("lrtactical:empty");
        if (weaponId.equals(empty) || displayId.filter(empty::equals).isPresent()) {
            throw new IllegalArgumentException("empty melee identity");
        }
        if (count < 1 || count > 127) throw new IllegalArgumentException("count");
        if (damage < 0) throw new IllegalArgumentException("damage");
        if (displayId.isPresent() != displayResource.isPresent()) {
            throw new IllegalArgumentException("display resource must match display identity");
        }
    }
}
