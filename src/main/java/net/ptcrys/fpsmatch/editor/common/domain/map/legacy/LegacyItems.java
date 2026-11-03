package net.ptcrys.fpsmatch.editor.common.domain.map.legacy;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;

public final class LegacyItems {

    public static final ExtensionKind<LegacyItemAdapter> KIND = new ExtensionKind<>(EditorId.parse("fpsmatch:legacy_item_adapter"), LegacyItemAdapter.class);

    private LegacyItems() {}
}
