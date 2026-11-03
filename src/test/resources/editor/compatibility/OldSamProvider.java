package net.ptcrys.fpsmatch.editor.core.compatibility;

import java.util.Optional;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemAdapter;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemEncoding;

/** Compiled exclusively against the captured Q37 interface, before its default overload existed. */
public final class OldSamProvider {
    private static int calls;
    private OldSamProvider() { }
    public static LegacyItemAdapter create() {
        return item -> { calls++; return Optional.empty(); };
    }
    public static int calls() { return calls; }
    public static LegacyItemEncoding encoding(byte[] bytes) {
        return new LegacyItemEncoding(EditorId.parse("example:old_value"),
                EditorId.parse("example:old_codec"), 1, bytes);
    }
}
