package net.ptcrys.fpsmatch.editor.common.domain.map.legacy;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;

/** An adapter-defined asset identity within an actual legacy item adapter contribution. */
public record LegacyItemResourceKey(EditorId provider, EditorId kind, EditorId asset)
        implements Comparable<LegacyItemResourceKey> {

    public LegacyItemResourceKey {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(asset, "asset");
    }

    @Override
    public int compareTo(LegacyItemResourceKey other) {
        Objects.requireNonNull(other, "other");
        int order = provider.compareTo(other.provider);
        if (order == 0) order = kind.compareTo(other.kind);
        if (order == 0) order = asset.compareTo(other.asset);
        return order;
    }
}
