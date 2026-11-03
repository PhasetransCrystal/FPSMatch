package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Diagnostic identities owned by the project runtime session.
 *
 * <p>
 * Product sentences live in
 * {@code assets/fpsmatch/lang/*.json}; nothing here
 * hardcodes prose. Every rejection has exactly one stable code ({@code editor:session/<name>}) and
 * exactly one translation key ({@code editor.session.<name>}). Identities and counts travel only as
 * typed arguments, and the argument order of a code is part of its contract:
 * </p>
 *
 * <ul>
 * <li>{@code type_activation_failed} - document type id. The failing descriptor field is not a
 * product argument: the implementation's own exception stays in the technical failure map of
 * the catalog, so a sentence never carries a stack trace or a field label.</li>
 * <li>{@code unsupported_codec} - document type id, source codec id, expected codec id.</li>
 * <li>{@code unsupported_schema} - document type id, source schema version (a number).</li>
 * <li>{@code contribution_unavailable} - the required contribution key.</li>
 * <li>{@code module_unavailable} - the required module id.</li>
 * <li>{@code contribution_budget_exceeded}, {@code module_budget_exceeded} and
 * {@code dependency_budget_exceeded} - the observed count, then the crossed limit; all three
 * use the fixed target {@value #DEPENDENCY_GRAPH_TARGET} because the traversal as a whole is
 * what exceeded its budget.</li>
 * </ul>
 *
 * <p>
 * Two further codes of this table are consumed by the project runtime read domain, not by this
 * module: {@code document_bytes_exceeded} (observed bytes, then the read limit) and
 * {@code resource_catalog_unavailable} (the project id). That domain builds its own
 * {@link Problem}s from the public API with the project as the target; this table only owns the
 * stable code, key and both translations, so a reader of those domains uses the same identities.
 * </p>
 */
final class SessionMessages {

    static final String NAMESPACE = "editor";

    /** The one target every dependency-graph budget diagnostic carries. */
    static final String DEPENDENCY_GRAPH_TARGET = "dependency_graph";

    static final EditorId TYPE_ACTIVATION_FAILED = code("type_activation_failed");
    static final EditorId UNSUPPORTED_CODEC = code("unsupported_codec");
    static final EditorId UNSUPPORTED_SCHEMA = code("unsupported_schema");
    static final EditorId CONTRIBUTION_UNAVAILABLE = code("contribution_unavailable");
    static final EditorId MODULE_UNAVAILABLE = code("module_unavailable");
    static final EditorId CONTRIBUTION_BUDGET_EXCEEDED = code("contribution_budget_exceeded");
    static final EditorId MODULE_BUDGET_EXCEEDED = code("module_budget_exceeded");
    static final EditorId DEPENDENCY_BUDGET_EXCEEDED = code("dependency_budget_exceeded");
    static final EditorId DOCUMENT_BYTES_EXCEEDED = code("document_bytes_exceeded");
    static final EditorId RESOURCE_CATALOG_UNAVAILABLE = code("resource_catalog_unavailable");

    static final String KEY_TYPE_ACTIVATION_FAILED = "editor.session.type_activation_failed";
    static final String KEY_UNSUPPORTED_CODEC = "editor.session.unsupported_codec";
    static final String KEY_UNSUPPORTED_SCHEMA = "editor.session.unsupported_schema";
    static final String KEY_CONTRIBUTION_UNAVAILABLE = "editor.session.contribution_unavailable";
    static final String KEY_MODULE_UNAVAILABLE = "editor.session.module_unavailable";
    static final String KEY_CONTRIBUTION_BUDGET_EXCEEDED = "editor.session.contribution_budget_exceeded";
    static final String KEY_MODULE_BUDGET_EXCEEDED = "editor.session.module_budget_exceeded";
    static final String KEY_DEPENDENCY_BUDGET_EXCEEDED = "editor.session.dependency_budget_exceeded";
    static final String KEY_DOCUMENT_BYTES_EXCEEDED = "editor.session.document_bytes_exceeded";
    static final String KEY_RESOURCE_CATALOG_UNAVAILABLE = "editor.session.resource_catalog_unavailable";

    private SessionMessages() {}

    private static EditorId code(String path) {
        return new EditorId(NAMESPACE, "session/" + path);
    }

    // --------------------------------------------------------------- factories

    /**
     * A document type whose descriptor or initialization failed. The implementation's own throwable is
     * not an argument of the sentence: the catalog keeps it in its technical failure map.
     */
    static SessionRejection typeActivationFailed(EditorId typeId) {
        return rejection(TYPE_ACTIVATION_FAILED, typeId.toString(), KEY_TYPE_ACTIVATION_FAILED, null,
                literal(typeId.toString()));
    }

    /**
     * The same rejection, with the original throwable kept as the cause.
     *
     * <p>
     * The sentence stays the one of {@link #typeActivationFailed(EditorId)}: a descriptor field
     * name is a label of this platform's own code, not product wording, so it never becomes an
     * argument a translation could print. The implementation's exception travels only as the cause,
     * where a technical reader finds it and a product sentence cannot leak it.
     * </p>
     */
    static SessionRejection typeActivationFailed(EditorId typeId, Throwable cause) {
        return rejection(TYPE_ACTIVATION_FAILED, typeId.toString(), KEY_TYPE_ACTIVATION_FAILED, cause,
                literal(typeId.toString()));
    }

    /** The cause-less variant of {@link #typeActivationFailed(EditorId, Throwable)}. */
    static SessionRejection typeActivationFailed(EditorId typeId, String field, Throwable cause) {
        Objects.requireNonNull(field, "field");
        return typeActivationFailed(typeId, cause);
    }

    /** A payload written by another codec is refused before any implementation callback runs. */
    static SessionRejection unsupportedCodec(EditorId typeId, EditorId sourceCodec,
                                             EditorId expectedCodec) {
        return rejection(UNSUPPORTED_CODEC, typeId.toString(), KEY_UNSUPPORTED_CODEC, null,
                literal(typeId.toString()), literal(sourceCodec.toString()),
                literal(expectedCodec.toString()));
    }

    /** A payload of a schema this type does not read is refused before any implementation callback. */
    static SessionRejection unsupportedSchema(EditorId typeId, int sourceSchema) {
        return rejection(UNSUPPORTED_SCHEMA, typeId.toString(), KEY_UNSUPPORTED_SCHEMA, null,
                literal(typeId.toString()), number(sourceSchema));
    }

    static SessionRejection contributionUnavailable(ContributionKey key) {
        return rejection(CONTRIBUTION_UNAVAILABLE, key.toString(), KEY_CONTRIBUTION_UNAVAILABLE, null,
                literal(key.toString()));
    }

    static SessionRejection moduleUnavailable(EditorId moduleId) {
        return rejection(MODULE_UNAVAILABLE, moduleId.toString(), KEY_MODULE_UNAVAILABLE, null,
                literal(moduleId.toString()));
    }

    static SessionRejection contributionBudgetExceeded(long observed, long limit) {
        return budget(CONTRIBUTION_BUDGET_EXCEEDED, KEY_CONTRIBUTION_BUDGET_EXCEEDED, observed, limit);
    }

    static SessionRejection moduleBudgetExceeded(long observed, long limit) {
        return budget(MODULE_BUDGET_EXCEEDED, KEY_MODULE_BUDGET_EXCEEDED, observed, limit);
    }

    static SessionRejection dependencyBudgetExceeded(long observed, long limit) {
        return budget(DEPENDENCY_BUDGET_EXCEEDED, KEY_DEPENDENCY_BUDGET_EXCEEDED, observed, limit);
    }

    private static SessionRejection budget(EditorId code, String key, long observed, long limit) {
        return rejection(code, DEPENDENCY_GRAPH_TARGET, key, null, number(observed), number(limit));
    }

    // ---------------------------------------------------------------- plumbing

    private static SessionRejection rejection(EditorId code, String target, String messageKey,
                                              Throwable cause, TextContent.Argument... arguments) {
        return new SessionRejection(
                List.of(new Problem(Problem.Severity.ERROR, code, target, message(messageKey,
                        arguments))),
                cause);
    }

    private static TextContent message(String key, TextContent.Argument... arguments) {
        return new TextContent.Message(key, List.of(arguments));
    }

    private static TextContent.Argument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.Argument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
