package net.ptcrys.fpsmatch.editor.api.registry;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Diagnostic identities and message keys owned by this module. Product sentences live in
 * {@code assets/fpsmatch/lang/*.json}; nothing here hardcodes English prose.
 */
final class Messages {

    static final String NAMESPACE = "editor";

    static final EditorId DUPLICATE_MODULE = code("duplicate_module");
    static final EditorId INCOMPATIBLE_API = code("incompatible_api");
    static final EditorId MISSING_MODULE = code("missing_module");
    static final EditorId MODULE_CYCLE = code("module_cycle");
    static final EditorId DEPENDENCY_UNAVAILABLE = code("dependency_unavailable");
    static final EditorId DUPLICATE_CONTRIBUTION = code("duplicate_contribution");
    static final EditorId MISSING_OWNER = code("missing_owner");
    static final EditorId MISSING_CONTRIBUTION = code("missing_contribution");
    static final EditorId CONTRIBUTION_CYCLE = code("contribution_cycle");
    static final EditorId WRONG_SIDE = code("wrong_side");

    static final String KEY_DUPLICATE_MODULE = "editor.registry.duplicate_module";
    static final String KEY_INCOMPATIBLE_API = "editor.registry.incompatible_api";
    static final String KEY_MISSING_MODULE = "editor.registry.missing_module";
    static final String KEY_MODULE_CYCLE = "editor.registry.module_cycle";
    // The depended-upon category ("module" / "contribution") is part of the sentence itself, so one
    // complete sentence per category replaces a category literal argument. Both sentences take the
    // same three literal arguments in this order: {registration, dependency, environment}.
    static final String KEY_MODULE_DEPENDENCY_UNAVAILABLE = "editor.registry.module_dependency_unavailable";
    static final String KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE = "editor.registry.contribution_dependency_unavailable";
    static final String KEY_DUPLICATE_CONTRIBUTION = "editor.registry.duplicate_contribution";
    static final String KEY_MISSING_OWNER = "editor.registry.missing_owner";
    static final String KEY_MISSING_CONTRIBUTION = "editor.registry.missing_contribution";
    static final String KEY_CONTRIBUTION_CYCLE = "editor.registry.contribution_cycle";
    static final String KEY_WRONG_SIDE = "editor.registry.wrong_side";
    static final String KEY_COMMON_REQUIRES_CLIENT = "editor.registry.common_requires_client";

    private Messages() {}

    private static EditorId code(String path) {
        return new EditorId(NAMESPACE, "registry/" + path);
    }

    static Problem problem(EditorId code, String target, String messageKey, String... arguments) {
        List<TextContent.Argument> values = new ArrayList<>(arguments.length);
        for (String argument : arguments) {
            values.add(new TextContent.LiteralArgument(argument));
        }
        return new Problem(Problem.Severity.ERROR, code, target, new TextContent.Message(messageKey, values));
    }

    /**
     * Total order for diagnostics: severity class, code, target, message key and then the typed
     * arguments. Argument values are part of the key, so two problems that share code, target and
     * message key but differ in arguments still have one fixed order.
     */
    static Comparator<Problem> order() {
        return Comparator.comparing((Problem problem) -> problem.severity().ordinal())
                .thenComparing(problem -> problem.code().toString())
                .thenComparing(Problem::target)
                .thenComparing(Messages::messageKey)
                .thenComparing(Messages::argumentKey);
    }

    private static String messageKey(Problem problem) {
        return problem.message() instanceof TextContent.Message message ? message.key() : "";
    }

    private static String argumentKey(Problem problem) {
        if (!(problem.message() instanceof TextContent.Message message)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (TextContent.Argument argument : message.arguments()) {
            appendArgument(builder, argument);
        }
        return builder.toString();
    }

    private static void appendArgument(StringBuilder builder, TextContent.Argument argument) {
        if (argument instanceof TextContent.LiteralArgument literal) {
            builder.append('L').append(literal.value());
        } else if (argument instanceof TextContent.NumberArgument number) {
            builder.append('N').append(number.value().toPlainString());
        } else if (argument instanceof TextContent.NestedArgument nested) {
            builder.append('(');
            appendContent(builder, nested.value());
            builder.append(')');
        }
        builder.append('\u0000');
    }

    private static void appendContent(StringBuilder builder, TextContent content) {
        if (content instanceof TextContent.Message message) {
            builder.append('M').append(message.key());
            for (TextContent.Argument argument : message.arguments()) {
                appendArgument(builder, argument);
            }
        } else if (content instanceof TextContent.Literal literal) {
            builder.append('T').append(literal.text());
        }
    }
}
