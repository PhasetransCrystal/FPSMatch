package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Stable resource-scope diagnostics. Product sentences belong to the matching language files. */
final class ResourceMessages {

    private ResourceMessages() {}

    static SessionRejection staleToken(UUID sessionId, long expectedRevision, long currentRevision) {
        return rejection("stale_token", "project-resources", literal(sessionId),
                number(expectedRevision), number(currentRevision));
    }

    static SessionRejection identityConflict(UUID id) {
        return rejection("identity_conflict", id.toString(), literal(id));
    }

    static SessionRejection removalUnsupported(UUID id) {
        return rejection("removal_unsupported", id.toString(), literal(id));
    }

    static SessionRejection metadataBudget(long observed, long limit) {
        return rejection("metadata_budget", "project-resources", number(observed), number(limit));
    }

    static SessionRejection identityBudget(long observedNodes, long nodeLimit,
                                           long observedBytes, long byteLimit) {
        return rejection("identity_budget", "project-resources", number(observedNodes),
                number(nodeLimit), number(observedBytes), number(byteLimit));
    }

    static SessionRejection nothingToUndo() {
        return rejection("nothing_to_undo", "project-resources");
    }

    static SessionRejection nothingToRedo() {
        return rejection("nothing_to_redo", "project-resources");
    }

    static SessionRejection staleCapture(UUID sessionId, long revision) {
        return rejection("stale_capture", "project-resources", literal(sessionId), number(revision));
    }

    static SessionRejection retainedBudget(long observed, long limit) {
        return rejection("retained_budget", "project-resources", number(observed), number(limit));
    }

    static SessionRejection unsavedResources(UUID documentId) {
        return rejection("unsaved_resources", documentId.toString(), literal(documentId));
    }

    static SessionRejection referenceUnavailable(UUID resourceId, UUID versionId) {
        return rejection("reference_unavailable", resourceId.toString(), literal(resourceId),
                literal(versionId));
    }

    static SessionRejection documentIdentityConflict(UUID id) {
        return rejection("document_identity_conflict", id.toString(), literal(id));
    }

    static SessionRejection invalidCatalog(IllegalArgumentException cause) {
        Objects.requireNonNull(cause, "cause");
        return new SessionRejection(List.of(problem("invalid_catalog", "project-resources")), cause);
    }

    private static SessionRejection rejection(String suffix, String target,
                                              TextContent.Argument... arguments) {
        return new SessionRejection(List.of(problem(suffix, target, arguments)));
    }

    private static Problem problem(String suffix, String target, TextContent.Argument... arguments) {
        return new Problem(Problem.Severity.ERROR, new EditorId("editor", "resource_draft/" + suffix),
                target, new TextContent.Message("editor.resource_draft." + suffix, List.of(arguments)));
    }

    private static TextContent.Argument literal(UUID value) {
        return new TextContent.LiteralArgument(Objects.requireNonNull(value, "identity").toString());
    }

    private static TextContent.Argument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
