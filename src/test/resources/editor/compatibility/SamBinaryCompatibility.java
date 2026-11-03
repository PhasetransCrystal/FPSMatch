package net.ptcrys.fpsmatch.editor.core.compatibility;

import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemContext;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

/** The old provider class is not recompiled on the current classpath. */
public final class SamBinaryCompatibility {
    private SamBinaryCompatibility() { }
    public static void main(String[] arguments) {
        var provider = OldSamProvider.create();
        if (provider.convert(ExactJson.parse("{}"), LegacyItemContext.empty()).isPresent()
                || OldSamProvider.calls() != 1) throw new IllegalStateException("old SAM dispatch changed");
        try {
            provider.convert(ExactJson.parse("{}"), null);
            throw new IllegalStateException("null context accepted");
        } catch (NullPointerException expected) {
            if (OldSamProvider.calls() != 1) throw new IllegalStateException("null invoked old provider");
        }
        System.out.println("LEGACY_ITEM_OLD_SAM_BINARY_PASS Q37-compiled lambda -> Q38 default overload; runtime uses only current public JARs");
    }
}
