package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.project.ProjectSaveQueue;
import net.ptcrys.fpsmatch.editor.core.project.SaveDispatch;
import net.ptcrys.fpsmatch.editor.core.project.SaveQueueView;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourcePlan;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Owner-thread assembly of a real loaded project and its registered document drafts.
 *
 * <p>
 * The complete original snapshot and bytes remain in {@link #source()}, including unavailable
 * documents and assets. Missing types, incompatible module versions and unavailable resource
 * producers protect publication; readable documents can still be edited in memory. This slice
 * supplies explicit capture, preparation, queue and confirmation stages. Editing never stages or
 * commits data. Recovery and first publication of a new project are separate host workflows.
 *
 * <p>
 * One access object lends at most one live session. The host must also avoid wrapping the same
 * physical store in multiple access objects. Closing releases the lease and invalidates every
 * handle, but never closes the borrowed store. Neither this class nor its handles start threads.
 */
public final class ProjectSession implements AutoCloseable {

    static final int MAX_DOCUMENTS = 8192;
    private static final int MAX_DOCUMENT_DEPENDENCIES = 65536;
    private static final Comparator<ResourceRef> RESOURCE_ORDER = Comparator
            .comparing(ResourceRef::resourceId).thenComparing(ResourceRef::versionId);

    private final UUID id = UUID.randomUUID();
    private final Object lease = new Object();
    private final SessionOwner owner = new SessionOwner(id);
    private final LocalProjectAccess access;
    private final LoadedProject source;
    private final DocumentTypeCatalog types;
    private final int maxHistoryEntries;
    private final Map<UUID, DocumentHandle<?>> documents = new TreeMap<>();
    private final Map<UUID, RuntimeException> documentFailures = new TreeMap<>();
    private final List<Problem> problems;
    private final Optional<RuntimeException> resourceFailure;
    private final ResourceProducerCatalog producers;
    private final Optional<ResourceHandle> resourceDraft;
    private final EditorResourceContext editorResources;
    private final ProjectSaveQueue saveQueue;
    private final Map<UUID, ProjectSaveCapture> retained = new TreeMap<>();
    private long retainedBytes;
    private long retainedResourceBytes;
    private ProjectSaveJob activeJob;
    private boolean requiresReopen;
    private static final long MAX_RETAINED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_RETAINED_RESOURCE_BYTES = 64L * 1024 * 1024;

    private ProjectSession(LocalProjectAccess access, LoadedProject source, ExtensionRegistry registry,
                           int maxHistoryEntries) {
        this.access = access;
        this.source = source;
        this.maxHistoryEntries = maxHistoryEntries;
        access.claim(lease);
        try {
            types = DocumentTypeCatalog.activate(registry);
            producers = ResourceProducerCatalog.activate(registry);
            ProjectContentChecks opened = owner.mutate(() -> assemble(registry, producers));
            problems = List.copyOf(opened.problems);
            resourceFailure = Optional.ofNullable(opened.resourceFailure);
            resourceDraft = source.resources().map(catalog -> new ResourceHandle(owner, catalog,
                    maxHistoryEntries, this::validateResourceCandidate));
            editorResources = new EditorResourceContext(owner, registry, source);
            // Document validation runs on captured drafts; resource content validation is explicit
            // during preparation. The queue also checks the assembled plain-data candidate.
            saveQueue = new ProjectSaveQueue(source.saved(), candidate -> {
                long bytes = 0;
                for (ProjectState.Entry entry : candidate.documents().values()) bytes += entry.content().size();
                return bytes > LocalProjectAccess.MAX_DOCUMENT_BYTES ? SaveMessages.captureBytes(bytes, LocalProjectAccess.MAX_DOCUMENT_BYTES).problems() : List.of();
            }, 32);
        } catch (RuntimeException | Error failure) {
            access.release(lease);
            throw failure;
        }
    }

    /** Opens drafts on the calling owner thread; all file reading has already happened in access. */
    public static ProjectSession open(LocalProjectAccess access, LoadedProject loaded,
                                      ExtensionRegistry registry, int maxHistoryEntries) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(registry, "registry");
        if (maxHistoryEntries < 0) {
            throw new IllegalArgumentException("maxHistoryEntries must be non-negative");
        }
        if (!access.owns(loaded)) {
            throw new IllegalArgumentException("loaded project belongs to another access object");
        }
        if (loaded.documentIds().size() > MAX_DOCUMENTS) {
            throw new SessionRejection(List.of(RuntimeMessages.documentLimit(
                    loaded.saved().state().projectId(), loaded.documentIds().size(), MAX_DOCUMENTS)));
        }
        return new ProjectSession(access, loaded, registry, maxHistoryEntries);
    }

    public UUID id() {
        owner.requireReadable();
        return id;
    }

    public LoadedProject source() {
        owner.requireReadable();
        return source;
    }

    /** Independent resource-directory draft; unavailable original catalogs stay protected. */
    public Optional<ResourceHandle> resources() {
        owner.requireReadable();
        return resourceDraft;
    }

    /** Frozen, content-validated resources for document presentation in this session. */
    public EditorResourceContext editorResources() {
        owner.requireReadable();
        return editorResources;
    }

    /** A stable directory snapshot; later create calls do not mutate a previously returned map. */
    public Map<UUID, DocumentHandle<?>> documents() {
        owner.requireReadable();
        return Collections.unmodifiableMap(new TreeMap<>(documents));
    }

    public Set<UUID> unavailableDocuments() {
        owner.requireReadable();
        return Collections.unmodifiableSet(new TreeSet<>(documentFailures.keySet()));
    }

    public Map<UUID, RuntimeException> documentFailures() {
        owner.requireReadable();
        return Collections.unmodifiableMap(new TreeMap<>(documentFailures));
    }

    public List<Problem> problems() {
        owner.requireReadable();
        return problems;
    }

    public Optional<RuntimeException> resourceFailure() {
        owner.requireReadable();
        return resourceFailure;
    }

    /** Opening diagnostics only; a later save must validate its own exact capture independently. */
    public boolean protectedMode() {
        owner.requireReadable();
        return problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR);
    }

    /** Current handle lookup without allocating a directory snapshot. */
    public Optional<DocumentHandle<?>> find(UUID documentId) {
        owner.requireReadable();
        Objects.requireNonNull(documentId, "documentId");
        return Optional.ofNullable(documents.get(documentId));
    }

    public <T> Optional<DocumentHandle<T>> find(UUID documentId, Class<T> valueType) {
        owner.requireReadable();
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(valueType, "valueType");
        DocumentHandle<?> handle = documents.get(documentId);
        return handle == null ? Optional.empty() : Optional.of(handle.narrow(valueType));
    }

    /** Creates an unpublished draft only after every domain initialization step succeeds. */
    public <T> DocumentHandle<T> create(UUID documentId, EditorId typeId, Class<T> valueType,
                                        String displayName) {
        return createInternal(documentId, typeId, valueType, displayName, true, null);
    }

    /**
     * Creates an unpublished draft from an already converted domain value without calling the
     * default factory. Its isolated, validated initial value is the draft's history baseline.
     * The registered snapshot, validation, encoding and contribution checks must all succeed before
     * the document becomes visible; failure leaves its id available for another attempt.
     *
     * <p>
     * This owner-thread operation performs no I/O or publication. A new draft can be clean relative
     * to its initial content while still having unpublished changes. An asynchronous importer must
     * check cancellation and the target session before calling this method with its converted value.
     * </p>
     */
    public <T> DocumentHandle<T> createWithValue(UUID documentId, EditorId typeId,
                                                 Class<T> valueType, String displayName, T initialValue) {
        return createInternal(documentId, typeId, valueType, displayName, false, initialValue);
    }

    private <T> DocumentHandle<T> createInternal(UUID documentId, EditorId typeId,
                                                 Class<T> valueType, String displayName, boolean defaultFactory, Object initialValue) {
        return owner.mutate(() -> {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(typeId, "typeId");
            Objects.requireNonNull(valueType, "valueType");
            if (!defaultFactory) Objects.requireNonNull(initialValue, "initialValue");
            requireDisplayName(displayName);
            if (documents.containsKey(documentId) || source.documentIds().contains(documentId) || source.saved().state().assets().containsKey(documentId) || resourceDraft.map(handle -> handle.knownAssetIds().contains(documentId)).orElse(false)) {
                throw new SessionRejection(List.of(RuntimeMessages.duplicateDocument(documentId)));
            }
            int count = documents.size() + documentFailures.size();
            if (count >= MAX_DOCUMENTS) {
                throw new SessionRejection(List.of(RuntimeMessages.documentLimit(
                        source.saved().state().projectId(), (long) count + 1, MAX_DOCUMENTS)));
            }
            ActivatedDocumentType<?> type = requireType(typeId);
            if (type.valueType() != valueType) {
                throw new IllegalArgumentException("document value type does not match requested class");
            }
            DocumentHandle<?> created = createOne(documentId, type, displayName,
                    defaultFactory, initialValue);
            Roots roots = new Roots();
            roots.add(new ContributionKey(DocumentTypes.KIND.id(), type.id()));
            for (ContributionKey key : created.initialDependencies().contributions()) {
                roots.add(key);
            }
            types.requiredModules(roots.contributions);
            DocumentHandle<T> narrowed = created.narrow(valueType);
            documents.put(documentId, narrowed);
            return narrowed;
        });
    }

    public boolean isClosed() {
        return owner.isClosed();
    }

    public SaveQueueView saves() {
        owner.requireReadable();
        return saveQueue.view();
    }

    /** An uncertain publication was resolved, or conflicted: reopen the physical store before saving. */
    public boolean requiresReopen() {
        owner.requireReadable();
        return requiresReopen;
    }

    /**
     * Restores one document's latest confirmed content, or its initial content if never saved.
     * A new or imported document remains in the directory and remains unpublished. Successful
     * restoration clears this document's history and raw input and invalidates its old tokens and
     * save captures. Other documents, resources and the physical store are untouched.
     *
     * <p>
     * Protected projects, a required reopen and any queued or active publication refuse the
     * operation. Domain callback failure leaves the draft, raw input and old branch intact.
     * </p>
     */
    public void discardDocumentChanges(UUID documentId) {
        owner.mutate(() -> {
            if (protectedMode()) throw SaveMessages.protectedProject(source.saved().state().projectId());
            if (requiresReopen) throw SaveMessages.queueUnavailable("REOPEN_REQUIRED");
            SaveQueueView queue = saveQueue.view();
            if (queue.state() != SaveQueueView.State.READY || queue.active().isPresent()) {
                throw SaveMessages.queueUnavailable(queue.state().name());
            }
            if (queue.queued() != 0) throw SaveMessages.queueUnavailable("QUEUED");
            requireDocument(documentId).discardChanges();
            return null;
        });
    }

    /** Captures exactly the chosen documents; unselected dirty dependencies require user action. */
    public ProjectSaveCapture captureSave(Set<UUID> selected) {
        return captureSave(selected, false);
    }

    /**
     * Captures selected documents and, only when explicitly requested, the resource draft. A
     * resource-only save may select no documents. Other intents preserve the latest queued assets.
     */
    public ProjectSaveCapture captureSave(Set<UUID> selected, boolean includeResources) {
        return owner.mutate(() -> {
            if (protectedMode()) throw SaveMessages.protectedProject(source.saved().state().projectId());
            if (requiresReopen) throw SaveMessages.queueUnavailable("REOPEN_REQUIRED");
            Objects.requireNonNull(selected, "selected");
            Set<UUID> ids = new TreeSet<>(selected);
            if (ids.isEmpty() && !includeResources) throw new IllegalArgumentException("save scope must not be empty");
            long bytes = 0;
            for (UUID documentId : ids) {
                DocumentHandle<?> handle = requireDocument(documentId);
                if (!handle.view().pendingInputs().isEmpty()) throw SaveMessages.pendingInput(documentId);
                bytes += handle.encodedLength();
            }
            if (bytes > LocalProjectAccess.MAX_DOCUMENT_BYTES) {
                throw SaveMessages.captureBytes(bytes, LocalProjectAccess.MAX_DOCUMENT_BYTES);
            }
            Map<UUID, DocumentSaveCapture> captures = new TreeMap<>();
            for (UUID documentId : ids) captures.put(documentId, requireDocument(documentId).capture());
            ResourceHandle resourceHandle = resourceDraft.orElseThrow();
            ResourceSaveCapture resourceCapture = includeResources ? resourceHandle.capture() : null;
            ResourceCatalog catalog = includeResources ? resourceCapture.catalog() : resourceHandle.savedCatalog();
            Roots roots = new Roots();
            Set<ResourceRef> refs = new TreeSet<>(RESOURCE_ORDER);
            long edges = 0;
            for (DocumentHandle<?> handle : documents.values()) {
                DocumentSaveCapture captured = captures.get(handle.id());
                DocumentDependencies deps = captured == null ? handle.publishedDependencies() : captured.dependencies;
                if (deps == null) continue; // Unselected new documents are not part of this publication.
                roots.add(new ContributionKey(DocumentTypes.KIND.id(), handle.typeId()));
                for (ContributionKey key : deps.contributions()) roots.add(key);
                edges += (long) deps.documents().size() + deps.resources().size() + deps.contributions().size();
                if (edges > MAX_DOCUMENT_DEPENDENCIES) {
                    throw SessionMessages.dependencyBudgetExceeded(edges, MAX_DOCUMENT_DEPENDENCIES);
                }
                refs.addAll(deps.resources());
                if (captured != null) {
                    if (!includeResources && deps.resources().stream().anyMatch(ref -> !contains(catalog, ref))) {
                        throw ResourceMessages.unsavedResources(handle.id());
                    }
                    for (UUID dependency : deps.documents()) {
                        if (!ids.contains(dependency)) {
                            DocumentHandle<?> target = documents.get(dependency);
                            if (target == null || target.publishedDependencies() == null || target.hasUnpublishedChanges()) {
                                throw SaveMessages.unsavedDependency(handle.id(), dependency);
                            }
                        }
                    }
                }
            }
            requireResourceRefs(catalog, refs);
            ResourcePlan plan = ResourceStorageBridge.planAll(catalog);
            Set<EditorId> moduleRoots = new TreeSet<>();
            // Existing exact versions remain roots; no save silently drops a required module.
            saveQueue.view().confirmed().state().modules().keySet().forEach(value -> moduleRoots.add(EditorId.parse(value)));
            if (!plan.resources().isEmpty() || !plan.versions().isEmpty()) moduleRoots.add(EditorId.parse("editor:resources"));
            for (ResourceVersion version : plan.versions().values()) {
                roots.add(producers.requireContribution(version));
                roots.add(producers.requireValidationContribution(version));
            }
            plan.byteEntries().values().forEach(entry -> moduleRoots.add(EditorId.parse(entry.ownerModule())));
            Map<String, String> modules = new TreeMap<>();
            types.requiredModules(roots.contributions, moduleRoots).forEach((key, value) -> modules.put(key.toString(), value));
            Set<UUID> removedAssets = new TreeSet<>();
            if (includeResources) {
                removedAssets.addAll(resourceHandle.knownAssetIds());
                removedAssets.removeAll(catalog.resources().keySet());
                removedAssets.removeAll(catalog.versions().keySet());
                removedAssets.removeAll(catalog.byteEntries().keySet());
            }
            return new ProjectSaveCapture(access, lease, captures, plan, resourceCapture, removedAssets,
                    modules, bytes, ResourceHandle.metadataBytes(catalog), producers, source.saved().state().projectId());
        });
    }

    /** Accepts one fixed prepared intent; older late preparation cannot reverse a newer intent. */
    public UUID enqueueSave(PreparedProjectSave prepared) {
        return owner.mutate(() -> {
            Objects.requireNonNull(prepared, "prepared");
            if (requiresReopen) throw SaveMessages.queueUnavailable("REOPEN_REQUIRED");
            ProjectSaveCapture capture = prepared.capture;
            if (capture.access != access || capture.lease != lease) throw new IllegalArgumentException("foreign project capture");
            if (capture.enqueued()) throw new IllegalStateException("capture was already queued");
            for (DocumentSaveCapture document : capture.documents.values()) {
                if (!document.canEnqueue()) throw SaveMessages.staleCapture(document.token.documentId());
            }
            if (capture.resourceCapture != null && !capture.resourceCapture.canEnqueue()) {
                ResourceToken token = capture.resourceCapture.token();
                throw ResourceMessages.staleCapture(token.sessionId(), token.revision());
            }
            if (capture.encodedBytes() > MAX_RETAINED_BYTES - retainedBytes) {
                throw SaveMessages.retainedBytes(retainedBytes + capture.encodedBytes(), MAX_RETAINED_BYTES);
            }
            if (capture.resourceMetadataBytes > MAX_RETAINED_RESOURCE_BYTES - retainedResourceBytes) {
                throw ResourceMessages.retainedBudget(retainedResourceBytes + capture.resourceMetadataBytes,
                        MAX_RETAINED_RESOURCE_BYTES);
            }
            SaveQueueView.State state = saveQueue.view().state();
            if (state != SaveQueueView.State.READY && state != SaveQueueView.State.PUBLISHING) {
                throw SaveMessages.queueUnavailable(state.name());
            }
            UUID requestId;
            try {
                requestId = saveQueue.enqueue(prepared.delta);
            } catch (IllegalStateException full) {
                throw SaveMessages.queueUnavailable("CAPACITY");
            }
            capture.markEnqueued();
            capture.documents.values().forEach(DocumentSaveCapture::queued);
            if (capture.resourceCapture != null) capture.resourceCapture.queued();
            retained.put(requestId, capture);
            retainedBytes += capture.encodedBytes();
            retainedResourceBytes += capture.resourceMetadataBytes;
            return requestId;
        });
    }

    public Optional<ProjectSaveDispatch> dispatchSave() {
        return owner.mutate(() -> {
            if (requiresReopen) return Optional.empty();
            Optional<SaveDispatch> next = saveQueue.dispatch();
            if (next.isEmpty()) return Optional.empty();
            SaveDispatch dispatched = next.orElseThrow();
            if (dispatched.attempt().isEmpty()) {
                releaseCapture(dispatched.requestId());
                return Optional.of(new ProjectSaveDispatch(dispatched.requestId(), Optional.empty(), dispatched.problems()));
            }
            activeJob = new ProjectSaveJob(access, lease, dispatched.attempt().orElseThrow());
            return Optional.of(new ProjectSaveDispatch(dispatched.requestId(), Optional.of(activeJob), dispatched.problems()));
        });
    }

    /** Only a verified result for the exact active job may confirm its original document captures. */
    public boolean completeSave(ProjectSaveJob job, SaveResult result) {
        return owner.mutate(() -> {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(result, "result");
            if (job != activeJob || job.access != access || job.lease != lease) return false;
            // No I/O or plugin callback runs in this short boundary. A worker cannot restart the
            // same job between the idle check and its terminal revocation.
            synchronized (access) {
                access.requireJobIdle(job);
                boolean wasUnknown = saveQueue.view().state() == SaveQueueView.State.UNKNOWN;
                if (!saveQueue.complete(job.attempt(), result)) return false;
                if (result.outcome() == SaveResult.Outcome.CONFIRMED) {
                    ProjectSaveCapture capture = retained.get(job.attempt().requestId());
                    capture.documents.values().forEach(DocumentSaveCapture::confirm);
                    if (capture.resourceCapture != null) capture.resourceCapture.confirm();
                }
                if (result.outcome() == SaveResult.Outcome.CONFLICT) {
                    job.publishable = false;
                    requiresReopen = true;
                } else if (wasUnknown && result.outcome() == SaveResult.Outcome.CONFIRMED) {
                    requiresReopen = true;
                }
                if (result.outcome() == SaveResult.Outcome.CONFIRMED || result.outcome() == SaveResult.Outcome.REJECTED) {
                    releaseCapture(job.attempt().requestId());
                    access.retireJob(job);
                    activeJob = null;
                }
            }
            return true;
        });
    }

    private DocumentHandle<?> requireDocument(UUID documentId) {
        DocumentHandle<?> handle = documents.get(Objects.requireNonNull(documentId, "documentId"));
        if (handle == null) throw new IllegalArgumentException("document is not available in this session");
        return handle;
    }

    private void releaseCapture(UUID requestId) {
        ProjectSaveCapture capture = retained.remove(requestId);
        if (capture != null) {
            retainedBytes -= capture.encodedBytes();
            retainedResourceBytes -= capture.resourceMetadataBytes;
        }
    }

    /** Invalidates handles and releases only the session lease, never the borrowed store. */
    @Override
    public void close() {
        owner.close(() -> {
            access.release(lease); // Refuses immediately if actual I/O is still running.
            retained.clear();
            retainedBytes = 0;
            retainedResourceBytes = 0;
            activeJob = null;
        });
    }

    private void validateResourceCandidate(ResourceCatalog catalog) {
        if (protectedMode()) throw SaveMessages.protectedProject(source.saved().state().projectId());
        ResourcePlan plan = ResourceStorageBridge.planAll(catalog);
        Set<UUID> identities = new TreeSet<>(catalog.resources().keySet());
        identities.addAll(catalog.versions().keySet());
        identities.addAll(catalog.byteEntries().keySet());
        for (UUID document : source.documentIds()) {
            if (identities.contains(document)) throw ResourceMessages.documentIdentityConflict(document);
        }
        for (DocumentHandle<?> document : documents.values()) {
            if (identities.contains(document.id())) throw ResourceMessages.documentIdentityConflict(document.id());
        }
        Set<ResourceRef> refs = new TreeSet<>(RESOURCE_ORDER);
        long edges = 0;
        for (DocumentHandle<?> document : documents.values()) {
            DocumentDependencies current = document.initialDependencies();
            DocumentDependencies published = document.publishedDependencies();
            edges = addResourceRefs(refs, current, edges);
            if (published != null) edges = addResourceRefs(refs, published, edges);
        }
        for (ProjectSaveCapture capture : retained.values()) {
            for (DocumentSaveCapture document : capture.documents.values()) {
                edges = addResourceRefs(refs, document.dependencies, edges);
            }
        }
        requireResourceRefs(catalog, refs);
        Roots roots = new Roots();
        Set<EditorId> moduleRoots = new TreeSet<>();
        if (!plan.resources().isEmpty() || !plan.versions().isEmpty()) moduleRoots.add(EditorId.parse("editor:resources"));
        for (ResourceVersion version : plan.versions().values()) {
            roots.add(producers.requireContribution(version));
            roots.add(producers.requireValidationContribution(version));
        }
        plan.byteEntries().values().forEach(entry -> moduleRoots.add(EditorId.parse(entry.ownerModule())));
        types.requiredModules(roots.contributions, moduleRoots);
    }

    private static long addResourceRefs(Set<ResourceRef> refs, DocumentDependencies dependencies, long edges) {
        long count = edges + dependencies.resources().size();
        if (count > MAX_DOCUMENT_DEPENDENCIES) {
            throw SessionMessages.dependencyBudgetExceeded(count, MAX_DOCUMENT_DEPENDENCIES);
        }
        refs.addAll(dependencies.resources());
        return count;
    }

    private static boolean contains(ResourceCatalog catalog, ResourceRef ref) {
        ResourceVersion version = catalog.versions().get(ref.versionId());
        return catalog.resources().containsKey(ref.resourceId()) && version != null && version.resourceId().equals(ref.resourceId());
    }

    private static void requireResourceRefs(ResourceCatalog catalog, Set<ResourceRef> refs) {
        for (ResourceRef ref : refs) {
            if (!contains(catalog, ref)) {
                throw ResourceMessages.referenceUnavailable(ref.resourceId(), ref.versionId());
            }
        }
    }

    private ProjectContentChecks assemble(ExtensionRegistry registry, ResourceProducerCatalog producers) {
        ProjectContentChecks checks = new ProjectContentChecks(source, types, registry, producers);
        for (ProjectState.Entry entry : source.saved().state().documents().values()) {
            DocumentHandle<?> handle;
            try {
                ActivatedDocumentType<?> type = requireType(EditorId.parse(entry.typeId()));
                if (!entry.ownerModule().equals(type.owner().toString())) {
                    throw new SessionRejection(List.of(RuntimeMessages.ownerMismatch(
                            entry.id(), entry.ownerModule(), type.owner())));
                }
                handle = decodeOne(entry, type);
            } catch (RuntimeException failure) {
                documentFailures.put(entry.id(), failure);
                checks.documentFailure(entry, failure);
                continue;
            }
            checks.addDocument(entry, handle.typeId(), handle.initialDependencies());
            documents.put(entry.id(), handle);
        }
        checks.finish();
        return checks;
    }

    private ActivatedDocumentType<?> requireType(EditorId typeId) {
        return ProjectContentChecks.requireType(types, typeId);
    }

    private <T> DocumentHandle<T> decodeOne(ProjectState.Entry entry, ActivatedDocumentType<T> type) {
        byte[] original = source.documentBytes(entry.id());
        T value = type.decode(EditorId.parse(entry.codecId()), entry.schemaVersion(), original);
        return new DocumentHandle<>(owner, entry.id(), type, entry.displayName(), value,
                maxHistoryEntries, entry, original);
    }

    private <T> DocumentHandle<T> createOne(UUID documentId, ActivatedDocumentType<T> type,
                                            String displayName, boolean defaultFactory, Object initialValue) {
        T value = defaultFactory ? type.create() : type.valueType().cast(initialValue);
        return new DocumentHandle<>(owner, documentId, type, displayName, value,
                maxHistoryEntries, null, null);
    }

    private static void requireDisplayName(String name) {
        Objects.requireNonNull(name, "displayName");
        if (name.length() > 4096) {
            throw new IllegalArgumentException("displayName exceeds 4096 UTF-16 units");
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= name.length() || !Character.isLowSurrogate(name.charAt(index))) {
                    throw new IllegalArgumentException("displayName has an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("displayName has an unpaired surrogate");
            }
        }
    }

    private static final class Roots {

        private final Set<ContributionKey> contributions = new TreeSet<>();
        private boolean overflow;

        void add(ContributionKey key) {
            if (!contributions.contains(key) && contributions.size() == DocumentTypeCatalog.MAX_DEPENDENCIES) {
                throw SessionMessages.contributionBudgetExceeded((long) contributions.size() + 1,
                        DocumentTypeCatalog.MAX_DEPENDENCIES);
            }
            contributions.add(key);
        }

        void addOrReport(ContributionKey key, List<Problem> problems) {
            if (overflow) {
                return;
            }
            try {
                add(key);
            } catch (SessionRejection rejection) {
                overflow = true;
                problems.addAll(rejection.problems());
            }
        }
    }
}
