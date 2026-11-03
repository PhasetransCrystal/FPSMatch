package net.ptcrys.fpsmatch.editor.core.types.values;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.TextContent;

/** Frozen six-row language table, including exact positional and typed arguments. */
class CodecLanguageTest {
    private record Row(String suffix, List<TextContent.Argument> arguments, String english, String chinese) { }

    @Test
    void bothLanguagesHaveExactlyTheFrozenSixMessagesAndRenderEveryArgument() {
        TextContent.LiteralArgument type = literal("vendor:note");
        List<Row> rows = List.of(
                new Row("unsupported_codec", List.of(type, literal("source:codec"), literal("current:codec")),
                        "Type vendor:note cannot read codec source:codec; its codec is current:codec.",
                        "类型 vendor:note 无法读取 codec source:codec；其 codec 为 current:codec。"),
                new Row("unsupported_schema", List.of(type, number(99)),
                        "Type vendor:note cannot read schema 99.", "类型 vendor:note 无法读取 schema 99。"),
                new Row("binding_mismatch", List.of(type),
                        "Bound metadata for type vendor:note differs from its prototype.", "类型 vendor:note 绑定后的元数据与原型不一致。"),
                new Row("type_activation_failed", List.of(type),
                        "Value type vendor:note could not be activated.", "值类型 vendor:note 无法激活。"),
                new Row("value_bytes", List.of(type, number(8388609), number(8388608)),
                        "Value type vendor:note has 8388609 encoded bytes, exceeding limit 8388608.",
                        "值类型 vendor:note 的编码字节数为 8388609，超过上限 8388608。"),
                new Row("value_dependencies", List.of(type, number(8193), number(8192)),
                        "Value type vendor:note has 8193 dependencies, exceeding limit 8192.",
                        "值类型 vendor:note 的依赖数量为 8193，超过上限 8192。"));
        Set<String> keys = rows.stream().map(row -> "editor.codec." + row.suffix()).collect(java.util.stream.Collectors.toSet());
        for (String language : List.of("en_us", "zh_cn")) {
            Map<String, String> translations = LangResources.load(language);
            assertEquals(keys, translations.keySet());
            for (Row row : rows) {
                TextContent.Message message = new TextContent.Message("editor.codec." + row.suffix(), row.arguments());
                assertEquals(language.equals("en_us") ? row.english() : row.chinese(), LangResources.rendered(translations, message));
            }
        }
    }

    private static TextContent.LiteralArgument literal(String value) { return new TextContent.LiteralArgument(value); }
    private static TextContent.NumberArgument number(int value) { return new TextContent.NumberArgument(BigDecimal.valueOf(value)); }
}
