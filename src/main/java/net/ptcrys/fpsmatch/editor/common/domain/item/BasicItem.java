package net.ptcrys.fpsmatch.editor.common.domain.item;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;
import java.util.OptionalInt;

/** Immutable item data; the owning profile supplies the complete tag policy. */
public record BasicItem(EditorId itemId, int count, OptionalInt damage) {

    public BasicItem {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(damage, "damage");
        if (count < 1 || count > 127) throw new IllegalArgumentException("count");
        if (damage.isPresent() && damage.getAsInt() < 0) throw new IllegalArgumentException("damage");
    }
}
