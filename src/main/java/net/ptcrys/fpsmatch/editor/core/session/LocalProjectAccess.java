package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.project.LocalCommitRunner;
import net.ptcrys.fpsmatch.editor.core.project.ProjectDelta;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourcePlan;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectSnapshot;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The bounded local I/O seam of one editing session: it borrows an already open
 * {@link LocalProjectStore}, reads the published snapshot and the document content a host may still
 * address, and hands the result out as a {@link LoadedProject}.
 *
 * <p>
 * Ordinary reading and preparation never call registered domain callbacks. Explicit validated
 * entry points run trusted resource validators or saved-document checks on the caller thread. Publication and lookup
 * operate only on the original fixed job. No method creates or activates a runtime game object.
 * </p>
 *
 * <p>
 * <b>The caller owns the store and the thread.</b> The store is opened elsewhere, is not closed
 * here, and public I/O methods run on the caller's I/O executor. Short monitor boundaries register
 * I/O and session leases; no monitor is held across storage I/O. This class starts no thread. The
 * underlying store is never exposed through a {@link LoadedProject}.
 * </p>
 *
 * <p>
 * <b>The read budget is memory, not protocol.</b> The declared total of every document entry's
 * content must fit in {@link #MAX_DOCUMENT_BYTES} before a single document blob is buffered, so an
 * oversized project is refused with a {@link SessionRejection} instead of being materialized first.
 * That budget is separate from the storage limits ({@code StoreLimits.maxBlobBytes},
 * {@code maxProjectBytes}) that {@link LocalProjectStore#load()} already enforces while it validates
 * the stored content, and it is not a claim that the first {@code load()} performs no I/O: opening
 * the published snapshot streams and hashes stored blobs by design.
 * </p>
 *
 * <p>
 * <b>Failure is never a half project.</b> The selected snapshot is read once before the document
 * content and once after it, and both reads must agree; a change or an I/O failure propagates as the
 * original {@link IOException} rather than returning a partially read project. An unavailable
 * resource catalog is one deliberate exception: it is a protected state, not a corrupt project, so
 * the read still returns the complete saved snapshot, no document bytes and no original asset entry
 * are discarded, and the catalog gap is reported as a diagnostic. Real I/O failures inside that
 * bridge still propagate.
 * </p>
 */
public final class LocalProjectAccess {

    /** One owner-domain session may borrow this physical access at a time. */
    private Object sessionLease;
    private int operations;
    private boolean validating;

    boolean owns(LoadedProject loaded) {
        return Objects.requireNonNull(loaded, "loaded").issuedBy(identity);
    }

    synchronized void claim(Object lease) {
        ResourceValidation.requireOutsideCallback();
        Objects.requireNonNull(lease, "lease");
        if (sessionLease != null) {
            throw new IllegalStateException("project access already has an open session");
        }
        if (operations != 0) throw SaveMessages.ioBusy();
        sessionLease = lease;
    }

    synchronized void release(Object lease) {
        ResourceValidation.requireOutsideCallback();
        if (sessionLease != Objects.requireNonNull(lease, "lease")) {
            throw new IllegalStateException("project session does not own this access lease");
        }
        if (operations != 0) throw SaveMessages.ioBusy();
        sessionLease = null;
    }

    private synchronized void begin(Object lease) {
        ResourceValidation.requireOutsideCallback();
        if (validating) throw SaveMessages.ioBusy();
        if (sessionLease != Objects.requireNonNull(lease, "lease")) {
            throw new IllegalStateException("project session no longer owns this access lease");
        }
        operations++;
    }

    private synchronized void end() {
        operations--;
    }

    private synchronized void beginRead() {
        ResourceValidation.requireOutsideCallback();
        if (validating) throw SaveMessages.ioBusy();
        operations++;
    }

    private synchronized void beginValidation(Object lease, boolean requireLease) {
        ResourceValidation.requireOutsideCallback();
        if (operations != 0 || validating) throw SaveMessages.ioBusy();
        if (requireLease && sessionLease != Objects.requireNonNull(lease, "lease")) {
            throw new IllegalStateException("project session no longer owns this access lease");
        }
        validating = true;
        operations++;
    }

    private synchronized void endValidation() {
        validating = false;
        operations--;
    }

    private synchronized void beginJob(ProjectSaveJob job, boolean publishing) {
        requireJob(job);
        if (job.revoked || job.running) throw new IllegalStateException("save job is retired or already running");
        if (publishing && !job.publishable) throw new IllegalStateException("conflicted save job permits reconciliation only");
        begin(job.lease);
        job.running = true;
    }

    private synchronized void endJob(ProjectSaveJob job) {
        job.running = false;
        end();
    }

    synchronized void requireJobIdle(ProjectSaveJob job) {
        if (job.running) throw SaveMessages.ioBusy();
    }

    synchronized void retireJob(ProjectSaveJob job) {
        requireJobIdle(job);
        job.revoked = true;
    }

    /** Stages immutable captures without invoking registered callbacks. */
    public PreparedProjectSave prepare(ProjectSaveCapture capture) throws IOException {
        requireCapture(capture);
        begin(capture.lease);
        try {
            capture.beginPreparation();
            try {
                if (!capture.resources.versions().isEmpty()) {
                    throw ValidationMessages.required(capture.accessProjectId());
                }
                return stage(capture, List.of());
            } catch (IOException | RuntimeException | Error failure) {
                capture.preparationFailed();
                throw failure;
            }
        } finally {
            end();
        }
    }

    /** Validates every fixed captured version on the caller's I/O thread before staging. */
    public PreparedProjectSave prepareValidated(ProjectSaveCapture capture, BooleanSupplier cancelled) throws IOException {
        requireCapture(capture);
        ResourceValidation.Cancellation cancellation = new ResourceValidation.Cancellation(cancelled);
        beginValidation(capture.lease, true);
        try {
            capture.beginPreparation();
            try {
                List<Problem> problems = ResourceValidation.run(store, capture.resources, capture.producers, cancellation);
                if (problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR)) {
                    throw new SessionRejection(problems);
                }
                ResourceValidation.check(cancellation);
                PreparedProjectSave prepared = stage(capture, problems);
                ResourceValidation.check(cancellation);
                return prepared;
            } catch (IOException | RuntimeException | Error failure) {
                capture.preparationFailed();
                throw failure;
            }
        } finally {
            endValidation();
        }
    }

    private void requireCapture(ProjectSaveCapture capture) {
        ResourceValidation.requireOutsideCallback();
        if (Objects.requireNonNull(capture, "capture").access != this) throw new IllegalArgumentException("foreign project capture");
    }

    private PreparedProjectSave stage(ProjectSaveCapture capture, List<Problem> problems) throws IOException {
        Map<UUID, ProjectState.Entry> documents = new java.util.TreeMap<>();
        for (DocumentSaveCapture document : capture.documents.values()) {
            ProjectState.Entry entry = document.stage(store);
            documents.put(entry.id(), entry);
        }
        Map<UUID, ProjectState.Entry> assets;
        if (capture.resourceCapture != null) {
            assets = ResourceStorageBridge.stage(store, capture.resources);
        } else {
            java.util.Set<net.ptcrys.fpsmatch.editor.core.storage.BlobRef> verified = new java.util.HashSet<>();
            for (ProjectState.Entry entry : capture.resources.byteEntries().values()) {
                if (verified.add(entry.content())) store.copyContent(entry.content(), OutputStream.nullOutputStream());
            }
            assets = Map.of();
        }
        return new PreparedProjectSave(capture, new ProjectDelta(Optional.empty(), capture.modules,
                documents, java.util.Set.of(), assets, capture.removedAssets), problems);
    }

    /** Publishes the original fixed attempt, including when the caller explicitly retries it. */
    public SaveResult publish(ProjectSaveJob job) {
        beginJob(job, true);
        try {
            return LocalCommitRunner.run(store, job.attempt());
        } finally {
            endJob(job);
        }
    }

    /** Looks up the same request; an absent receipt remains unknown and never causes a new write. */
    public SaveResult reconcile(ProjectSaveJob job) throws IOException {
        beginJob(job, false);
        try {
            LocalProjectStore.RequestResult result = store.lookup(job.attempt().requestId());
            if (result.status() == LocalProjectStore.RequestStatus.UNKNOWN) {
                return new SaveResult(SaveResult.Outcome.UNKNOWN, null, result.currentCommitId(), List.of());
            }
            if (!job.attempt().requestId().equals(result.currentCommitId())) {
                return new SaveResult(SaveResult.Outcome.CONFLICT, null, result.currentCommitId(), List.of());
            }
            return new SaveResult(SaveResult.Outcome.CONFIRMED, result.saved(), result.currentCommitId(), List.of());
        } finally {
            endJob(job);
        }
    }

    private void requireJob(ProjectSaveJob job) {
        if (Objects.requireNonNull(job, "job").access != this) {
            throw new IllegalArgumentException("foreign project save job");
        }
    }

    /** Maximum declared document payload per read; buffers and defensive copies add heap overhead. */
    public static final long MAX_DOCUMENT_BYTES = 64L * 1024 * 1024;

    private static final EditorId DOCUMENT_BYTES_EXCEEDED = new EditorId("editor", "session/document_bytes_exceeded");
    private static final EditorId RESOURCE_CATALOG_UNAVAILABLE = new EditorId("editor", "session/resource_catalog_unavailable");
    private static final String KEY_DOCUMENT_BYTES_EXCEEDED = "editor.session.document_bytes_exceeded";
    private static final String KEY_RESOURCE_CATALOG_UNAVAILABLE = "editor.session.resource_catalog_unavailable";
    /** Grows by doubling, so this starting capacity keeps the doubling slack small for tiny documents. */
    private static final int BUFFER_START_CAPACITY = 64 * 1024;

    /**
     * The identity of this access object. It is deliberately an opaque value rather than a path or a
     * project id: sharing a copy may preserve a {@code projectId}, so a later save preparation must
     * be bound to one access object, one session and one capture instead of to that id.
     */
    private final Object identity = new Object();
    private final LocalProjectStore store;

    /** Borrows an already open store. This object neither closes it nor starts any thread. */
    public LocalProjectAccess(LocalProjectStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Reads the published snapshot and its document content, buffering at most
     * {@link #MAX_DOCUMENT_BYTES} of declared document bytes. The snapshot is read a second time after
     * the resource stage, so the returned project always comes from one unchanged publication.
     *
     * @throws SessionRejection when the declared document content exceeds the read budget
     * @throws IOException      when the stored project cannot be read, changed while it was read, or
     *                          cannot be opened as a resource catalog for a reason other than an unsupported graph
     */
    public LoadedProject read() throws IOException {
        beginRead();
        try {
            return readSnapshot(null);
        } finally {
            end();
        }
    }

    /**
     * Captures the currently published version using the borrowed store's own capability issuer.
     * This is blocking I/O, never a capture of editing drafts or an authorization to install.
     * The capability is valid only with that same live store and does not retain content for GC.
     */
    public ProjectSnapshot captureSnapshot() throws IOException {
        beginRead();
        try {
            return store.captureSnapshot();
        } finally {
            end();
        }
    }

    /**
     * Reads exactly a previously captured saved version, including its original resource catalog.
     * Later saves by this store do not replace it. Foreign capabilities, external entry changes
     * and missing or damaged captured content fail without falling back to the current version.
     */
    public LoadedProject read(ProjectSnapshot captured) throws IOException {
        Objects.requireNonNull(captured, "captured");
        beginRead();
        try {
            return readSnapshot(captured);
        } finally {
            end();
        }
    }

    /** Reads and validates the entire immutable published resource snapshot. */
    public LoadedProject readValidated(ExtensionRegistry registry, BooleanSupplier cancelled) throws IOException {
        return readValidatedSnapshot(null, registry, cancelled);
    }

    /**
     * Reads and validates the complete resource closure of the captured saved version. Validation
     * retains the exact registry identity; it proves neither document installability nor a world
     * binding. Cancellation is cooperative, including checks before and after storage scans.
     */
    public LoadedProject readValidated(ProjectSnapshot captured, ExtensionRegistry registry,
                                       BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(captured, "captured");
        return readValidatedSnapshot(captured, registry, cancelled);
    }

    /**
     * Re-certifies this access's validated fixed saved input with the same registry identity.
     * This does not rerun validators or document codecs and grants no world or install authority.
     * The fixed storage scan is blocking and cannot be interrupted; cancellation is checked before
     * and after it. A capability retains no content for GC and remains bound to the original store.
     */
    public void verifyValidatedSource(LoadedProject source, ExtensionRegistry registry,
                                      BooleanSupplier cancelled) throws IOException {
        requireValidatedSource(source, registry, cancelled);
        ResourceValidation.Cancellation cancellation = new ResourceValidation.Cancellation(cancelled);
        beginValidation(null, false);
        try {
            ResourceValidation.check(cancellation);
            requireUnchanged(source.saved(), store.load(source.snapshot));
            ResourceValidation.check(cancellation);
        } finally {
            endValidation();
        }
    }

    /**
     * Exports this access's exact validated saved version, including when CURRENT has advanced.
     * This certifies bytes, not installation authority. The caller supplies a new destination
     * outside the project. A complete archive may remain if a final check or cancellation fails;
     * callers must not publish an installation from a failed call. Blocking I/O, no executor.
     */
    public void exportValidatedSource(LoadedProject source, ExtensionRegistry registry, Path archive,
                                      BooleanSupplier cancelled) throws IOException {
        requireValidatedSource(source, registry, cancelled);
        Objects.requireNonNull(archive, "archive");
        ResourceValidation.Cancellation cancellation = new ResourceValidation.Cancellation(cancelled);
        beginValidation(null, false);
        try {
            ResourceValidation.check(cancellation);
            requireUnchanged(source.saved(), store.load(source.snapshot));
            ResourceValidation.check(cancellation);
            store.exportSnapshot(archive, source.snapshot);
            requireUnchanged(source.saved(), store.load(source.snapshot));
            ResourceValidation.check(cancellation);
        } finally {
            endValidation();
        }
    }

    /**
     * Checks every saved document and the complete module/contribution/resource closure of the
     * validated fixed input. No draft, history, lease or encoding is created. Trusted callbacks
     * execute synchronously; physical scans and cancellation checks bracket the entire operation.
     * Returns immutable non-error diagnostics only when all saved content is healthy.
     */
    public List<Problem> verifyValidatedContent(LoadedProject source, ExtensionRegistry registry,
                                                BooleanSupplier cancelled) throws IOException {
        requireValidatedSource(source, registry, cancelled);
        ResourceValidation.Cancellation cancellation = new ResourceValidation.Cancellation(cancelled);
        beginValidation(null, false);
        try {
            ContentValidation validated = validateSavedContent(source, registry, cancellation);
            requireUnchanged(source.saved(), store.load(source.snapshot));
            ResourceValidation.check(cancellation);
            return List.copyOf(validated.checks().problems);
        } finally {
            endValidation();
        }
    }

    private record ContentValidation(DocumentTypeCatalog types, ResourceProducerCatalog producers,
                                     ProjectContentChecks checks) {}

    /** The Q61 body, shared while the caller retains the same exclusive validation guard. */
    private ContentValidation validateSavedContent(LoadedProject source, ExtensionRegistry registry,
                                                   ResourceValidation.Cancellation cancellation) throws IOException {
        ResourceValidation.check(cancellation);
        requireUnchanged(source.saved(), store.load(source.snapshot));
        ResourceValidation.check(cancellation);
        if (source.documentIds().size() > ProjectSession.MAX_DOCUMENTS) {
            throw new SessionRejection(List.of(RuntimeMessages.documentLimit(
                    source.saved().state().projectId(), source.documentIds().size(), ProjectSession.MAX_DOCUMENTS)));
        }
        DocumentTypeCatalog types = ResourceValidation.callback(() -> DocumentTypeCatalog.activate(registry));
        ResourceValidation.check(cancellation);
        ResourceProducerCatalog producers = ResourceValidation.callback(() -> ResourceProducerCatalog.activate(registry));
        ResourceValidation.check(cancellation);
        ProjectContentChecks checks = new ProjectContentChecks(source, types, registry, producers);
        for (ProjectState.Entry entry : source.saved().state().documents().values()) {
            ActivatedDocumentType<?> type = documentCallback(entry, cancellation, () -> {
                ActivatedDocumentType<?> found = ProjectContentChecks.requireType(types, EditorId.parse(entry.typeId()));
                if (!entry.ownerModule().equals(found.owner().toString())) {
                    throw new SessionRejection(List.of(RuntimeMessages.ownerMismatch(
                            entry.id(), entry.ownerModule(), found.owner())));
                }
                return found;
            });
            verifySavedDocument(source, entry, type, checks, cancellation);
        }
        ResourceValidation.check(cancellation);
        ResourceValidation.callback(() -> {
            checks.finish();
            return null;
        });
        ResourceValidation.check(cancellation);
        if (checks.problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR)) {
            throw new SessionRejection(checks.problems, checks.resourceFailure);
        }
        return new ContentValidation(types, producers, checks);
    }

    /**
     * Prepares one explicit registered adapter from the original saved bytes, without touching
     * editing state. The source must be this access's healthy validated read with the identical
     * registry. The complete project is checked before activating the requested adapter; the
     * selected document is decoded again under the same exclusive validation guard.
     *
     * <p>
     * Storage scans and trusted callbacks run synchronously on the caller thread. The host must
     * choose the adapter's required execution context. No executor, world object, session lease,
     * history, save, install authority or cross-return storage pin is created. A result retains the
     * original source and its bounded document bytes until the caller drops it.
     * </p>
     *
     * <p>
     * Cancellation is cooperative and latched, including polling inside the adapter; scans and
     * callbacks cannot be forcibly interrupted. Adapter and cancellation-supplier exceptions keep
     * their identities. A final physical check of the original snapshot rejects changed bytes and
     * late cancellation. Successful preparation does not certify external runtime assets or the
     * target world, which an installer must verify separately.
     * </p>
     */
    public <R> PreparedRuntime<R> prepareValidatedRuntime(LoadedProject source, ExtensionRegistry registry,
                                                          UUID documentId, EditorId adapterId, Class<R> resultType, BooleanSupplier cancelled) throws IOException {
        requireValidatedSource(source, registry, cancelled);
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(resultType, "resultType");
        beginValidation(null, false);
        try {
            ResourceValidation.Cancellation cancellation = new ResourceValidation.Cancellation(cancelled);
            ContentValidation validated = validateSavedContent(source, registry, cancellation);
            ProjectState.Entry entry = source.saved().state().documents().get(documentId);
            if (entry == null) throw RuntimePreparationMessages.rejection("document_unavailable", documentId, documentId);
            ActivatedDocumentType<?> type = ProjectContentChecks.requireType(validated.types(), EditorId.parse(entry.typeId()));
            ResourceValidation.check(cancellation);
            RuntimeAdapter activated = activateRuntimeAdapter(
                    registry, validated.types(), type, adapterId, resultType, cancellation);
            ResourceValidation.check(cancellation);
            List<Problem> problems = new ArrayList<>(validated.checks().problems);
            ProjectContentChecks selected = new ProjectContentChecks(source, validated.types(), registry, validated.producers());
            int inheritedProblems = selected.problems.size();
            Object isolated = verifySavedDocument(source, entry, type, selected, cancellation);
            ResourceValidation.check(cancellation);
            ResourceValidation.callback(() -> {
                selected.finish();
                return null;
            });
            ResourceValidation.check(cancellation);
            if (selected.problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR)) {
                throw new SessionRejection(selected.problems, selected.resourceFailure);
            }
            problems.addAll(selected.problems.subList(inheritedProblems, selected.problems.size()));
            R value = prepareRuntimeAdapter(activated, source, documentId, isolated, resultType, cancellation);
            requireUnchanged(source.saved(), store.load(source.snapshot));
            ResourceValidation.check(cancellation);
            return new PreparedRuntime<>(source, documentId, adapterId, value, problems, activated.modules(), registry);
        } finally {
            endValidation();
        }
    }

    private record RuntimeDescriptor(EditorId documentType, Class<?> inputType, Class<?> resultType) {}

    private record RuntimeAdapter(EditorId id, SavedRuntimeAdapter<?, ?> implementation,
                                  RuntimeDescriptor descriptor, Map<EditorId, String> modules) {}

    /** Reads each extension-supplied descriptor once per activation; Class comparison is identity. */
    private static RuntimeDescriptor runtimeDescriptor(EditorId id, SavedRuntimeAdapter<?, ?> adapter) {
        EditorId documentType = adapter.documentType();
        Class<?> inputType = adapter.inputType();
        Class<?> resultType = adapter.resultType();
        if (documentType == null || inputType == null || resultType == null || inputType.isPrimitive() || resultType.isPrimitive()) {
            throw RuntimePreparationMessages.rejection("descriptor_invalid", id, id);
        }
        return new RuntimeDescriptor(documentType, inputType, resultType);
    }

    private static RuntimeAdapter activateRuntimeAdapter(ExtensionRegistry registry, DocumentTypeCatalog types,
                                                         ActivatedDocumentType<?> type, EditorId id, Class<?> requestedResult,
                                                         ResourceValidation.Cancellation cancellation) throws IOException {
        SavedRuntimeAdapter<?, ?> prototype;
        try {
            prototype = registry.defines(SavedRuntimeAdapters.KIND) ? registry.find(SavedRuntimeAdapters.KIND, id).orElse(null) : null;
        } catch (IllegalArgumentException failure) {
            throw new SessionRejection(RuntimePreparationMessages.rejection(
                    "adapter_unavailable", id, id).problems(), failure);
        }
        if (prototype == null) throw RuntimePreparationMessages.rejection("adapter_unavailable", id, id);
        RuntimeDescriptor original = runtimeCallback(cancellation, () -> runtimeDescriptor(id, prototype));
        ContributionKey key = new ContributionKey(SavedRuntimeAdapters.KIND.id(), id);
        Contribution metadata = registry.metadata().contributions().get(key);
        ContributionKey document = new ContributionKey(DocumentTypes.KIND.id(), original.documentType());
        if (metadata == null || !metadata.requiredContributions().contains(document)) {
            throw RuntimePreparationMessages.rejection("document_dependency_missing", id, id, original.documentType());
        }
        if (!original.documentType().equals(type.id()) || original.inputType() != type.valueType() || requestedResult.isPrimitive() || original.resultType() != requestedResult) {
            throw RuntimePreparationMessages.rejection("type_mismatch", id, id, type.id(), requestedResult.getName());
        }
        Map<EditorId, String> modules = ResourceValidation.callback(() -> types.requiredModules(Set.of(key)));
        ResourceValidation.check(cancellation);
        SavedRuntimeAdapter<?, ?> bound = runtimeCallback(cancellation, () -> prototype.bind(registry));
        if (bound == null) throw RuntimePreparationMessages.rejection("binding_mismatch", id, id);
        RuntimeDescriptor descriptor = runtimeCallback(cancellation, () -> runtimeDescriptor(id, bound));
        if (!original.documentType().equals(descriptor.documentType()) || original.inputType() != descriptor.inputType() || original.resultType() != descriptor.resultType()) {
            throw RuntimePreparationMessages.rejection("binding_mismatch", id, id);
        }
        return new RuntimeAdapter(id, bound, descriptor, modules);
    }

    private static <T> T runtimeCallback(ResourceValidation.Cancellation cancellation,
                                         Supplier<T> operation) throws IOException {
        ResourceValidation.check(cancellation);
        T result = ResourceValidation.callback(operation);
        ResourceValidation.check(cancellation);
        return result;
    }

    private static <R> R prepareRuntimeAdapter(RuntimeAdapter activated, LoadedProject source,
                                               UUID documentId, Object isolated, Class<R> requestedResult,
                                               ResourceValidation.Cancellation cancellation) throws IOException {
        ResourceValidation.check(cancellation);
        Object returned = ResourceValidation.callbackIO(() -> callRuntimeAdapter(
                activated.implementation(), source, documentId, isolated, () -> ResourceValidation.poll(cancellation)));
        ResourceValidation.check(cancellation);
        if (returned == null || !activated.descriptor().resultType().isInstance(returned)) {
            throw RuntimePreparationMessages.rejection("result_invalid", activated.id(), activated.id(),
                    activated.descriptor().resultType().getName());
        }
        return requestedResult.cast(activated.descriptor().resultType().cast(returned));
    }

    private static <T, R> R callRuntimeAdapter(SavedRuntimeAdapter<T, R> adapter, LoadedProject source,
                                               UUID documentId, Object isolated, BooleanSupplier cancelled) throws IOException {
        // The frozen descriptor was checked against the real document Class before this erasure bridge.
        @SuppressWarnings("unchecked")
        T content = (T) isolated;
        return adapter.prepare(new SavedRuntimeInput<>(documentId, source.saved(),
                source.resources().orElseThrow(), content), cancelled);
    }

    private void requireValidatedSource(LoadedProject source, ExtensionRegistry registry,
                                        BooleanSupplier cancelled) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!owns(source)) throw new IllegalArgumentException("foreign loaded project");
        if (!source.validatedWith(registry) || source.resources().isEmpty() || source.resourceFailure().isPresent() || source.problems().stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR)) {
            throw ValidationMessages.savedSourceRequired(source.saved().state().projectId());
        }
    }

    private static <T> T verifySavedDocument(LoadedProject source, ProjectState.Entry entry,
                                             ActivatedDocumentType<T> type, ProjectContentChecks checks,
                                             ResourceValidation.Cancellation cancelled) throws IOException {
        T decoded = documentCallback(entry, cancelled, () -> type.decode(
                EditorId.parse(entry.codecId()), entry.schemaVersion(), source.documentBytes(entry.id())));
        T isolated = documentCallback(entry, cancelled, () -> type.snapshot(decoded));
        List<Problem> diagnostics = documentCallback(entry, cancelled, () -> {
            List<Problem> problems = type.validate(isolated);
            if (problems.stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR)) {
                throw new SessionRejection(problems);
            }
            return problems;
        });
        checks.problems.addAll(diagnostics);
        DocumentDependencies dependencies = documentCallback(entry, cancelled, () -> type.dependencies(isolated));
        documentCallback(entry, cancelled, () -> {
            checks.addDocument(entry, type.id(), dependencies);
            return null;
        });
        return isolated;
    }

    private static <T> T documentCallback(ProjectState.Entry entry,
                                          ResourceValidation.Cancellation cancelled, Supplier<T> operation) throws IOException {
        ResourceValidation.check(cancelled);
        T result;
        try {
            result = ResourceValidation.callback(operation);
        } catch (RuntimeException failure) {
            // Keep both the stable document locator and the original typed domain diagnostics.
            // Only the extension call is caught: cancellation-supplier failures keep their identity.
            throw ResourceValidation.callback(() -> {
                List<Problem> problems = new ArrayList<>();
                problems.add(RuntimeMessages.documentUnavailable(entry.id(), entry.typeId()));
                ProjectContentChecks.appendProblems(problems, failure);
                return new SessionRejection(problems, failure);
            });
        }
        ResourceValidation.check(cancelled);
        return result;
    }

    private LoadedProject readValidatedSnapshot(ProjectSnapshot captured, ExtensionRegistry registry,
                                                BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(registry, "registry");
        ResourceValidation.Cancellation cancellation = new ResourceValidation.Cancellation(cancelled);
        beginValidation(null, false);
        try {
            ResourceValidation.check(cancellation);
            LoadedProject loaded = readSnapshot(captured);
            ResourceValidation.check(cancellation);
            if (loaded.resources().isEmpty()) return loaded;
            ResourcePlan plan = ResourceStorageBridge.planAll(loaded.resources().orElseThrow());
            List<Problem> diagnostics = ResourceValidation.run(store, plan, ResourceProducerCatalog.activate(registry), cancellation);
            SavedProject after = loadSelected(captured);
            requireUnchanged(loaded.saved(), after);
            ResourceValidation.check(cancellation);
            return loaded.validated(registry, diagnostics);
        } finally {
            endValidation();
        }
    }

    private SavedProject loadSelected(ProjectSnapshot captured) throws IOException {
        return captured == null ? store.load() : store.load(captured);
    }

    private LoadedProject readSnapshot(ProjectSnapshot captured) throws IOException {
        ProjectSnapshot selected = captured == null ? store.captureSnapshot() : captured;
        SavedProject before = captured == null ? selected.saved() : store.load(selected);
        ProjectState state = before.state();
        Map<UUID, ProjectState.Entry> documents = state.documents();
        requireReadableBudget(state.projectId(), documents);

        // Each document is buffered under its own declared size and under the remaining total, so the
        // sink refuses an oversized document inside the transfer instead of after it grew; the store
        // still verifies the digest and the length of every blob through copyContent, and its
        // iteration order over a ProjectState map is the sorted, stable UUID order.
        Map<UUID, byte[]> content = new LinkedHashMap<>();
        long remaining = MAX_DOCUMENT_BYTES;
        for (ProjectState.Entry entry : documents.values()) {
            long declared = entry.content().size();
            BoundedBuffer buffer = new BoundedBuffer(declared, remaining);
            store.copyContent(entry.content(), buffer);
            content.put(entry.id(), buffer.toByteArray());
            remaining -= declared;
        }

        List<Problem> problems = new ArrayList<>(1);
        Optional<ResourceCatalog> resources = Optional.empty();
        Optional<RuntimeException> resourceFailure = Optional.empty();
        try {
            resources = Optional.of(ResourceStorageBridge.read(store, state.assets()));
        } catch (IllegalArgumentException refused) {
            // The bridge accepts exactly the resource graph it defines. A third-party asset graph is a
            // protected state, not a corrupt project: the original asset entries stay in the saved
            // snapshot for inspection and backup, and the catalog is reported as unavailable instead
            // of being replaced by an empty one that would silently drop what it did not understand.
            resourceFailure = Optional.of(refused);
            problems.add(resourceCatalogUnavailable(state.projectId()));
        }

        // Re-certify the same selection after reading resources. Current-version reads must not
        // move; captured-version reads permit later saves by this store but reject foreign entry
        // changes and recheck the captured manifest and all its content, never substituting CURRENT.
        SavedProject after = loadSelected(captured);
        requireUnchanged(before, after);
        return new LoadedProject(identity, selected, after, resources, problems, resourceFailure, content);
    }

    /**
     * Sums every declared document size before buffering. Shared blobs count once per document;
     * custom store limits may therefore make the declared total larger than a long.
     */
    private static void requireReadableBudget(UUID projectId, Map<UUID, ProjectState.Entry> documents) {
        BigInteger total = BigInteger.ZERO;
        for (ProjectState.Entry entry : documents.values()) {
            total = total.add(BigInteger.valueOf(entry.content().size()));
        }
        if (total.compareTo(BigInteger.valueOf(MAX_DOCUMENT_BYTES)) > 0) {
            throw new SessionRejection(
                    List.of(documentBytesExceeded(projectId, total, MAX_DOCUMENT_BYTES)));
        }
    }

    /**
     * Both snapshot reads must agree. The store already fails on an external {@code CURRENT} change,
     * so this covers a published state that moved between the two reads for any other reason; either
     * way the caller receives no partially read project.
     */
    private static void requireUnchanged(SavedProject before, SavedProject after) throws IOException {
        if (!before.equals(after)) {
            throw new ProjectStorageException(ProjectStorageException.Code.CONFLICT,
                    "the published project changed while it was read; reopen the project");
        }
    }

    private static Problem documentBytesExceeded(UUID projectId, BigInteger bytes, long limit) {
        return new Problem(Problem.Severity.ERROR, DOCUMENT_BYTES_EXCEEDED, projectId.toString(),
                message(KEY_DOCUMENT_BYTES_EXCEEDED,
                        new TextContent.NumberArgument(new BigDecimal(bytes)), number(limit)));
    }

    private static Problem resourceCatalogUnavailable(UUID projectId) {
        return new Problem(Problem.Severity.ERROR, RESOURCE_CATALOG_UNAVAILABLE, projectId.toString(),
                message(KEY_RESOURCE_CATALOG_UNAVAILABLE, new TextContent.LiteralArgument(projectId.toString())));
    }

    private static TextContent.NumberArgument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }

    private static TextContent message(String key, TextContent.Argument... arguments) {
        return new TextContent.Message(key, List.of(arguments));
    }

    /**
     * A destination bounded by the declared size and by the remaining read budget. It refuses the
     * write that would exceed either bound, so an oversized document is stopped inside the transfer
     * rather than after a full buffer was allocated and filled.
     */
    private static final class BoundedBuffer extends OutputStream {

        private final ByteArrayOutputStream buffer;
        private final long limit;
        private long written;

        BoundedBuffer(long declaredSize, long remainingBudget) {
            this.limit = Math.min(declaredSize, remainingBudget);
            this.buffer = new ByteArrayOutputStream((int) Math.min(limit, BUFFER_START_CAPACITY));
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            buffer.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            buffer.write(bytes, offset, length);
        }

        private void reserve(int length) throws IOException {
            if (length > limit - written) {
                throw new IOException("document content exceeds its declared size or the read budget");
            }
            written += length;
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }

        @Override
        public void close() {
            // Deliberately inert: the buffered content must stay readable after the transfer ends.
        }
    }
}
