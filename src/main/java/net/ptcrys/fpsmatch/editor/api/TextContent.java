package net.ptcrys.fpsmatch.editor.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Unresolved presentation content; never serialized as domain identity. */
public sealed interface TextContent permits TextContent.Literal, TextContent.Message {

    record Literal(String text) implements TextContent {

        public Literal {
            Objects.requireNonNull(text, "text");
        }
    }

    record Message(String key, List<Argument> arguments) implements TextContent {

        public Message {
            Objects.requireNonNull(key, "key");
            if (key.isBlank() || key.length() > 256) throw new IllegalArgumentException("invalid message key");
            if (arguments.size() > 32) throw new IllegalArgumentException("too many message arguments");
            arguments = List.copyOf(arguments);
        }
    }

    sealed interface Argument permits LiteralArgument, NumberArgument, NestedArgument {}

    record LiteralArgument(String value) implements Argument {

        public LiteralArgument {
            Objects.requireNonNull(value, "value");
        }
    }

    record NumberArgument(BigDecimal value) implements Argument {

        public NumberArgument {
            Objects.requireNonNull(value, "value");
        }
    }

    record NestedArgument(TextContent value) implements Argument {

        public NestedArgument {
            Objects.requireNonNull(value, "value");
        }
    }
}
