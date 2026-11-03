package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.core.resources.ResourcePlan;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** An owner-issued fixed save intent. Keep it only until it is prepared and queued or discarded. */
public final class ProjectSaveCapture {

    private final UUID id = UUID.randomUUID();
    final LocalProjectAccess access;
    final Object lease;
    final Map<UUID, DocumentSaveCapture> documents;
    final ResourcePlan resources;
    final ResourceProducerCatalog producers;
    private final UUID projectId;
    final ResourceSaveCapture resourceCapture;
    final Set<UUID> removedAssets;
    final Map<String, String> modules;
    private final long encodedBytes;
    final long resourceMetadataBytes;
    private boolean preparationStarted;
    private boolean enqueued;

    ProjectSaveCapture(LocalProjectAccess access, Object lease, Map<UUID, DocumentSaveCapture> documents,
                       ResourcePlan resources, ResourceSaveCapture resourceCapture, Set<UUID> removedAssets,
                       Map<String, String> modules, long encodedBytes, long resourceMetadataBytes, ResourceProducerCatalog producers, UUID projectId) {
        this.projectId = projectId;
        this.access = access;
        this.lease = lease;
        this.documents = Collections.unmodifiableMap(new TreeMap<>(documents));
        this.resources = resources;
        this.producers = producers;
        this.resourceCapture = resourceCapture;
        this.removedAssets = Collections.unmodifiableSet(new TreeSet<>(removedAssets));
        this.modules = Collections.unmodifiableMap(new TreeMap<>(modules));
        this.encodedBytes = encodedBytes;
        this.resourceMetadataBytes = resourceMetadataBytes;
    }

    UUID accessProjectId() {
        return projectId;
    }

    public UUID id() {
        return id;
    }

    public Set<UUID> documentIds() {
        return Collections.unmodifiableSet(new TreeSet<>(documents.keySet()));
    }

    public long encodedBytes() {
        return encodedBytes;
    }

    synchronized void beginPreparation() {
        if (preparationStarted) throw new IllegalStateException("capture was already prepared or is preparing");
        preparationStarted = true;
    }

    synchronized void preparationFailed() {
        preparationStarted = false;
    }

    boolean enqueued() {
        return enqueued;
    }

    void markEnqueued() {
        enqueued = true;
    }
}
