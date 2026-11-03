package net.ptcrys.fpsmatch.editor.common.domain.map.legacy;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

@FunctionalInterface
public interface LegacyItemAdapter {

    Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item);

    default Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item,
                                                 LegacyItemContext context) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(context, "context");
        return convert(item);
    }

    static LegacyItemAdapter contextual(BiFunction<ExactJson.FrozenJson, LegacyItemContext, Optional<LegacyItemEncoding>> conversion) {
        Objects.requireNonNull(conversion, "conversion");
        return new LegacyItemAdapter() {

            @Override
            public Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item) {
                return convert(item, LegacyItemContext.empty());
            }

            @Override
            public Optional<LegacyItemEncoding> convert(ExactJson.FrozenJson item,
                                                        LegacyItemContext context) {
                Objects.requireNonNull(item, "item");
                Objects.requireNonNull(context, "context");
                return Objects.requireNonNull(conversion.apply(item, context), "conversion result");
            }
        };
    }
}
