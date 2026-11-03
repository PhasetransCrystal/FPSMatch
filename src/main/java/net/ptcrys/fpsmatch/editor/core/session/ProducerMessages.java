package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Diagnostic identities owned by the resource producer resolution of the project runtime.
 *
 * <p>
 * Product sentences live in
 * {@code assets/fpsmatch/lang/*.json}; nothing
 * here hardcodes prose. Every rejection has exactly one stable code
 * ({@code editor:session/resource_producer_...}) and exactly one translation key
 * ({@code editor.session.resource_producer_...}). Both rows target the stored resource version and
 * carry the producer identity and the stored algorithm version as typed literal arguments, in the
 * frozen order:
 * </p>
 *
 * <ul>
 * <li>{@code resource_producer_unavailable} - the importer id of the version.</li>
 * <li>{@code resource_producer_version_unavailable} - the importer id, then the stored
 * {@code importerVersion} the producer does not declare.</li>
 * </ul>
 *
 * <p>
 * The two codes are kept in this small package-private table instead of the session table of the
 * document domain, so producer resolution owns its own identities and language file. Both sentences
 * ask the caller to keep the original resource data: refusing to resolve a producer is not a verdict
 * that the stored resource is damaged.
 * </p>
 */
final class ProducerMessages {

    static final String NAMESPACE = "editor";

    static final EditorId RESOURCE_PRODUCER_UNAVAILABLE = code("resource_producer_unavailable");
    static final EditorId RESOURCE_PRODUCER_VERSION_UNAVAILABLE = code("resource_producer_version_unavailable");

    static final String KEY_RESOURCE_PRODUCER_UNAVAILABLE = "editor.session.resource_producer_unavailable";
    static final String KEY_RESOURCE_PRODUCER_VERSION_UNAVAILABLE = "editor.session.resource_producer_version_unavailable";

    private ProducerMessages() {}

    private static EditorId code(String path) {
        return new EditorId(NAMESPACE, "session/" + path);
    }

    // --------------------------------------------------------------- factories

    /** No producer is registered for the stored importer id of this version. */
    static SessionRejection resourceProducerUnavailable(UUID versionId, EditorId importer) {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(importer, "importer");
        return rejection(RESOURCE_PRODUCER_UNAVAILABLE, versionId,
                KEY_RESOURCE_PRODUCER_UNAVAILABLE, literal(importer.toString()));
    }

    /**
     * A producer exists for the stored importer id but does not declare the stored algorithm version.
     *
     * <p>
     * The stored version string travels verbatim: neither the producer set nor this diagnostic
     * normalizes it.
     * </p>
     */
    static SessionRejection resourceProducerVersionUnavailable(UUID versionId, EditorId importer,
                                                               String importerVersion) {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(importer, "importer");
        Objects.requireNonNull(importerVersion, "importerVersion");
        return rejection(RESOURCE_PRODUCER_VERSION_UNAVAILABLE, versionId,
                KEY_RESOURCE_PRODUCER_VERSION_UNAVAILABLE, literal(importer.toString()),
                literal(importerVersion));
    }

    // ---------------------------------------------------------------- plumbing

    private static SessionRejection rejection(EditorId code, UUID versionId, String messageKey,
                                              TextContent.Argument... arguments) {
        return new SessionRejection(List.of(new Problem(Problem.Severity.ERROR, code,
                versionId.toString(), new TextContent.Message(messageKey, List.of(arguments)))));
    }

    private static TextContent.Argument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }
}
