package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Diagnostic identities owned by the project runtime of an opened draft session.
 *
 * <p>
 * Product sentences live in
 * {@code assets/fpsmatch/lang/*.json}; nothing
 * here hardcodes prose. Every diagnostic has exactly one stable code ({@code editor:runtime/<name>})
 * and exactly one translation key ({@code editor.runtime.<name>}). Identities travel only as typed
 * arguments, counts as numbers, and the argument order of a code is part of its contract:
 * </p>
 *
 * <ul>
 * <li>{@code document_unavailable} - the document identity, then its declared type id. The
 * implementation's own throwable is not an argument: the session keeps the original rejection
 * or codec exception next to this diagnostic, so a product sentence never carries a stack
 * trace.</li>
 * <li>{@code document_owner_mismatch} - the document identity, the owner recorded in the entry,
 * then the owner the registry actually reports.</li>
 * <li>{@code required_module_unavailable} - the required module id.</li>
 * <li>{@code module_version_mismatch} - the module id, the saved build version, then the installed
 * build version.</li>
 * <li>{@code required_module_not_recorded} - the module id, then the installed build version the
 * saved module list does not record.</li>
 * <li>{@code dependency_manifest_mismatch} - the document identity.</li>
 * <li>{@code resource_dependencies_unavailable} - the project identity.</li>
 * <li>{@code document_count_exceeded} - the observed document count, then the session limit; both
 * are numbers.</li>
 * <li>{@code reentrant_mutation} - the session identity. This one is a state rejection of the
 * owner domain rather than a reported problem, so it travels as a
 * {@link SessionRejection}.</li>
 * <li>{@code document_identity_conflict} - the document identity that is already used.</li>
 * <li>{@code document_type_unavailable} - the requested document type id.</li>
 * </ul>
 *
 * <p>
 * All sentences are ERROR diagnostics with the stored identity as their target. None of them
 * claims the stored data is damaged: an unavailable producer, a missing module or an unresolved
 * dependency asks the caller to keep the original content and to migrate explicitly.
 * </p>
 */
final class RuntimeMessages {

    static final String NAMESPACE = "editor";

    static final EditorId DOCUMENT_UNAVAILABLE = code("document_unavailable");
    static final EditorId DOCUMENT_OWNER_MISMATCH = code("document_owner_mismatch");
    static final EditorId REQUIRED_MODULE_UNAVAILABLE = code("required_module_unavailable");
    static final EditorId MODULE_VERSION_MISMATCH = code("module_version_mismatch");
    static final EditorId REQUIRED_MODULE_NOT_RECORDED = code("required_module_not_recorded");
    static final EditorId DEPENDENCY_MANIFEST_MISMATCH = code("dependency_manifest_mismatch");
    static final EditorId RESOURCE_DEPENDENCIES_UNAVAILABLE = code("resource_dependencies_unavailable");
    static final EditorId DOCUMENT_COUNT_EXCEEDED = code("document_count_exceeded");
    static final EditorId REENTRANT_MUTATION = code("reentrant_mutation");
    static final EditorId DOCUMENT_IDENTITY_CONFLICT = code("document_identity_conflict");
    static final EditorId DOCUMENT_TYPE_UNAVAILABLE = code("document_type_unavailable");

    static final String KEY_DOCUMENT_UNAVAILABLE = "editor.runtime.document_unavailable";
    static final String KEY_DOCUMENT_OWNER_MISMATCH = "editor.runtime.document_owner_mismatch";
    static final String KEY_REQUIRED_MODULE_UNAVAILABLE = "editor.runtime.required_module_unavailable";
    static final String KEY_MODULE_VERSION_MISMATCH = "editor.runtime.module_version_mismatch";
    static final String KEY_REQUIRED_MODULE_NOT_RECORDED = "editor.runtime.required_module_not_recorded";
    static final String KEY_DEPENDENCY_MANIFEST_MISMATCH = "editor.runtime.dependency_manifest_mismatch";
    static final String KEY_RESOURCE_DEPENDENCIES_UNAVAILABLE = "editor.runtime.resource_dependencies_unavailable";
    static final String KEY_DOCUMENT_COUNT_EXCEEDED = "editor.runtime.document_count_exceeded";
    static final String KEY_REENTRANT_MUTATION = "editor.runtime.reentrant_mutation";
    static final String KEY_DOCUMENT_IDENTITY_CONFLICT = "editor.runtime.document_identity_conflict";
    static final String KEY_DOCUMENT_TYPE_UNAVAILABLE = "editor.runtime.document_type_unavailable";

    private RuntimeMessages() {}

    private static EditorId code(String path) {
        return new EditorId(NAMESPACE, "runtime/" + path);
    }

    // --------------------------------------------------------------- factories

    /** One document could not be decoded; its original bytes stay in the opened project. */
    static Problem documentUnavailable(UUID documentId, String typeId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(typeId, "typeId");
        return problem(DOCUMENT_UNAVAILABLE, documentId.toString(), KEY_DOCUMENT_UNAVAILABLE,
                literal(documentId.toString()), literal(typeId));
    }

    /** The owner of a stored entry and the owner the registry reports do not agree. */
    static Problem ownerMismatch(UUID documentId, String savedOwner, EditorId actualOwner) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(savedOwner, "savedOwner");
        Objects.requireNonNull(actualOwner, "actualOwner");
        return problem(DOCUMENT_OWNER_MISMATCH, documentId.toString(), KEY_DOCUMENT_OWNER_MISMATCH,
                literal(documentId.toString()), literal(savedOwner), literal(actualOwner.toString()));
    }

    /** A required module is not installed, so a stored dependency cannot be honoured. */
    static Problem moduleUnavailable(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return problem(REQUIRED_MODULE_UNAVAILABLE, moduleId, KEY_REQUIRED_MODULE_UNAVAILABLE,
                literal(moduleId));
    }

    /**
     * An installed module reports another build version than the saved list records.
     *
     * <p>
     * Both strings travel verbatim: the comparison is exact, and this diagnostic never normalizes
     * a version, it asks for an explicit migration.
     * </p>
     */
    static Problem moduleVersionMismatch(String moduleId, String savedVersion,
                                         String installedVersion) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(savedVersion, "savedVersion");
        Objects.requireNonNull(installedVersion, "installedVersion");
        return problem(MODULE_VERSION_MISMATCH, moduleId, KEY_MODULE_VERSION_MISMATCH,
                literal(moduleId), literal(savedVersion), literal(installedVersion));
    }

    /** A required module of the resolved closure is missing from the saved module list. */
    static Problem moduleNotRecorded(EditorId moduleId, String installedVersion) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(installedVersion, "installedVersion");
        return problem(REQUIRED_MODULE_NOT_RECORDED, moduleId.toString(),
                KEY_REQUIRED_MODULE_NOT_RECORDED, literal(moduleId.toString()),
                literal(installedVersion));
    }

    /** The dependencies of a decoded document differ from the manifest its entry recorded. */
    static Problem manifestMismatch(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return problem(DEPENDENCY_MANIFEST_MISMATCH, documentId.toString(),
                KEY_DEPENDENCY_MANIFEST_MISMATCH, literal(documentId.toString()));
    }

    /** Resource dependencies of the project cannot be satisfied; original assets are kept. */
    static Problem resourceUnavailable(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return problem(RESOURCE_DEPENDENCIES_UNAVAILABLE, projectId.toString(),
                KEY_RESOURCE_DEPENDENCIES_UNAVAILABLE, literal(projectId.toString()));
    }

    /** The project holds more documents than one session may carry. */
    static Problem documentLimit(UUID projectId, long count, long limit) {
        Objects.requireNonNull(projectId, "projectId");
        return problem(DOCUMENT_COUNT_EXCEEDED, projectId.toString(), KEY_DOCUMENT_COUNT_EXCEEDED,
                number(count), number(limit));
    }

    /**
     * A document callback or command tried to change the session reentrantly.
     *
     * <p>
     * This is a rejected state change, not a reported problem of a stored entry, so it travels as
     * a {@link SessionRejection} with a single ERROR problem and no cause.
     * </p>
     */
    static SessionRejection reentrant(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return new SessionRejection(List.of(problem(REENTRANT_MUTATION, sessionId.toString(),
                KEY_REENTRANT_MUTATION, literal(sessionId.toString()))));
    }

    /** The identity of a new document is already used by this project. */
    static Problem duplicateDocument(UUID identity) {
        Objects.requireNonNull(identity, "identity");
        return problem(DOCUMENT_IDENTITY_CONFLICT, identity.toString(),
                KEY_DOCUMENT_IDENTITY_CONFLICT, literal(identity.toString()));
    }

    /** The requested document type is not an activated contribution of this project. */
    static Problem typeUnavailable(EditorId typeId) {
        Objects.requireNonNull(typeId, "typeId");
        return problem(DOCUMENT_TYPE_UNAVAILABLE, typeId.toString(), KEY_DOCUMENT_TYPE_UNAVAILABLE,
                literal(typeId.toString()));
    }

    // ---------------------------------------------------------------- plumbing

    private static Problem problem(EditorId code, String target, String messageKey,
                                   TextContent.Argument... arguments) {
        return new Problem(Problem.Severity.ERROR, code, target,
                new TextContent.Message(messageKey, List.of(arguments)));
    }

    private static TextContent.Argument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.Argument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
