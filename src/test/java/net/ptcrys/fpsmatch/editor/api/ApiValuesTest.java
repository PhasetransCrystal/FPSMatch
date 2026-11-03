package net.ptcrys.fpsmatch.editor.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiValuesTest {
    @Test void identitiesAreExplicitAndCaseSensitive() {
        EditorId id = EditorId.parse("third_party:custom/object");
        assertEquals("third_party", id.namespace());
        assertEquals("custom/object", id.path());
        assertEquals(id, new EditorId("third_party", "custom/object"));
        for (String invalid : List.of("no_namespace", "a:b:c", ":value", "a:", "A:b", "a:some value")) {
            assertThrows(IllegalArgumentException.class, () -> EditorId.parse(invalid), invalid);
        }
    }

    @Test void presentationKeepsTypedArgumentsAndOwnsItsList() {
        List<TextContent.Argument> input = new ArrayList<>();
        input.add(new TextContent.NumberArgument(new BigDecimal("1.50")));
        input.add(new TextContent.LiteralArgument("editor.label.literal_user_name"));
        TextContent.Message message = new TextContent.Message("editor.example", input);
        input.clear();
        assertEquals(2, message.arguments().size());
        assertEquals(new BigDecimal("1.50"), ((TextContent.NumberArgument)message.arguments().get(0)).value());
        assertEquals("editor.label.literal_user_name", ((TextContent.LiteralArgument)message.arguments().get(1)).value());
        assertThrows(UnsupportedOperationException.class, () -> message.arguments().clear());
        assertThrows(IllegalArgumentException.class, () -> new TextContent.Message(" ", List.of()));
        assertThrows(NullPointerException.class, () -> new TextContent.Message("valid", java.util.Arrays.asList((TextContent.Argument)null)));
    }

    @Test void diagnosticsPreserveIdentityAndUnresolvedContent() {
        TextContent.Message message = new TextContent.Message("editor.problem", List.of());
        Problem problem = new Problem(Problem.Severity.ERROR, EditorId.parse("third:invalid"), "object-id/property-id", message);
        assertSame(message, problem.message());
        assertEquals("third:invalid", problem.code().toString());
        assertEquals("object-id/property-id", problem.target());
    }
}
