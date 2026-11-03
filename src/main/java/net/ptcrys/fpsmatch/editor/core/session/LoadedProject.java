package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectSnapshot;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One bounded local read: the complete published snapshot, the document content the host may still
 * address, and the resource catalog when the resource bridge accepts the stored asset graph.
 *
 * <p>
 * <b>Only an access object issues one.</b> The constructor is package-private and every instance
 * carries the opaque identity of the {@link LocalProjectAccess} that produced it, so a later save
 * preparation can be bound to that exact access object, session and capture instead of to a shared
 * {@code projectId}. The identity is never handed out: the package-private check only answers whether
 * a caller-supplied value is that same object.
 * </p>
 *
 * <p>
 * <b>Defensive copies on both sides.</b> Document bytes are copied on construction and again on
 * every {@link #documentBytes(UUID)} call, so neither the caller nor this object can change what the
 * other sees, and the document id set is an immutable, stably ordered copy.
 * </p>
 *
 * <p>
 * <b>An unavailable resource catalog is a protected state, not an empty one.</b> When the
 * resource bridge refuses the stored asset graph, {@link #resources()} is empty,
 * {@link #resourceFailure()} holds the original rejection for technical diagnosis, and
 * {@link #problems()} holds exactly one ERROR. The complete original asset entries remain in
 * {@link #saved()} so a host can inspect, back up and refuse publication instead of losing them. A
 * ordinary read whose bridge call succeeds reports no problem and no failure. An explicit
 * validated read can additionally retain content diagnostics and a content rejection even when
 * the catalog is structurally readable.
 * </p>
 *
 * <p>
 * This type exposes no store or document session and is not serializable. Validated reads retain
 * the exact frozen registry identity internally; validation authority is never serialized.
 * </p>
 */
public final class LoadedProject {

    private final Object identity;
    private final ExtensionRegistry validatedRegistry;
    final ProjectSnapshot snapshot;
    private final SavedProject saved;
    private final Optional<ResourceCatalog> resources;
    private final List<Problem> problems;
    private final Optional<RuntimeException> resourceFailure;
    private final Map<UUID, byte[]> documentContent;
    private final Set<UUID> documentIds;

    LoadedProject(Object identity, ProjectSnapshot snapshot, SavedProject saved, Optional<ResourceCatalog> resources,
                  List<Problem> problems, Optional<RuntimeException> resourceFailure,
                  Map<UUID, byte[]> documentContent) {
        this(identity, snapshot, saved, resources, problems, resourceFailure, documentContent, null);
    }

    private LoadedProject(Object identity, ProjectSnapshot snapshot, SavedProject saved, Optional<ResourceCatalog> resources,
                          List<Problem> problems, Optional<RuntimeException> resourceFailure,
                          Map<UUID, byte[]> documentContent, ExtensionRegistry validatedRegistry) {
        this.validatedRegistry = validatedRegistry;
        this.identity = Objects.requireNonNull(identity, "identity");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.saved = Objects.requireNonNull(saved, "saved");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.problems = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(problems, "problems")));
        this.resourceFailure = Objects.requireNonNull(resourceFailure, "resourceFailure");
        Map<UUID, byte[]> copy = new LinkedHashMap<>();
        Objects.requireNonNull(documentContent, "documentContent").forEach((id, bytes) -> copy.put(Objects.requireNonNull(id, "document id"), copyOf(bytes)));
        this.documentContent = Collections.unmodifiableMap(copy);
        this.documentIds = Collections.unmodifiableSet(new LinkedHashSet<>(copy.keySet()));
    }

    /** The complete published snapshot this read was taken from; no entry is dropped from it. */
    public SavedProject saved() {
        return saved;
    }

    /** The resource catalog, or empty when the resource bridge does not accept the stored graph. */
    public Optional<ResourceCatalog> resources() {
        return resources;
    }

    /** The diagnostics of this read, in the order the reader reported them; empty on a clean read. */
    public List<Problem> problems() {
        return problems;
    }

    /**
     * The original structure or content rejection, for technical diagnosis. A content rejection can
     * accompany a readable catalog; it still protects publication.
     * It is never rendered as a product sentence and never carried as a {@link Problem} identity.
     */
    public Optional<RuntimeException> resourceFailure() {
        return resourceFailure;
    }

    /** The document identities of the published snapshot, immutable and stably ordered. */
    public Set<UUID> documentIds() {
        return documentIds;
    }

    /**
     * A fresh copy of one document's published bytes.
     *
     * @throws IllegalArgumentException when {@code id} is not a document of this snapshot, which is a
     *                                  caller programming error rather than a product state
     */
    public byte[] documentBytes(UUID id) {
        Objects.requireNonNull(id, "id");
        byte[] bytes = documentContent.get(id);
        if (bytes == null) {
            throw new IllegalArgumentException("unknown document id " + id);
        }
        return copyOf(bytes);
    }

    /** Answers whether a caller-supplied value is this result's issuing access object. */
    boolean issuedBy(Object candidate) {
        return identity == candidate;
    }

    boolean validatedWith(ExtensionRegistry registry) {
        return validatedRegistry == registry;
    }

    LoadedProject validated(ExtensionRegistry registry, List<Problem> diagnostics) {
        List<Problem> all = new ArrayList<>(problems);
        all.addAll(diagnostics);
        boolean error = diagnostics.stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR);
        return new LoadedProject(identity, snapshot, saved, resources, all,
                error ? Optional.of(new SessionRejection(diagnostics)) : resourceFailure,
                documentContent, error ? null : registry);
    }

    private static byte[] copyOf(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return Arrays.copyOf(bytes, bytes.length);
    }
}
