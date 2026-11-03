package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.project.SaveQueueView;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.session.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Long-lived owner-domain model. Views may disappear or resize without closing the project. */
public final class ProjectWorkbench {

    private final Thread owner = Thread.currentThread();
    private final ExtensionRegistry registry;
    private final Executor io, completion;
    private final StoreCreator creator;
    private final Map<UUID, DocumentEditor<?>> editors = new HashMap<>();
    private final Map<Address, Draft> inputs = new HashMap<>();
    private LocalProjectStore store;
    private LocalProjectAccess access;
    private ProjectSession session;
    private Path directory;
    private UUID activeDocument;
    private String selectedNode;
    private final Map<UUID, String> selectedNodes = new HashMap<>();
    private Pending pending;
    private Preview<?> preview;
    private ResourcePreview resourcePreview;
    private ProjectSaveJob uncertainJob;
    private DocumentSaveRequest lastDocumentSave, uncertainDocumentSave;
    private DocumentSaveState lastDocumentSaveState;
    private Creation creation;
    private boolean creationOutcomeKnown;
    private TextContent status = text("ready");
    private List<Problem> problems = List.of();
    private Throwable technicalFailure;
    private FailureDiagnostic failureDiagnostic;
    private Set<Address> failureInputs = Set.of();
    private long failureSequence;
    private final FailureReporter failureReporter;
    private long version;
    private CreationDraft objectCreation;
    private ReferenceDraft referenceDraft;
    private RemovalDraft removalDraft;
    private PointMoveDraft pointMove;
    private AreaEditDraft areaEdit;
    private SavedRuntimeSource runtimeSource;
    private StudioClipboard studioClipboard;

    private record StudioClipboard(DocumentHandle<?> document, DocumentEditor<?> editor, Object payload) {}

    /** Safe display metadata only. Exception messages and input values stay out of the view. */
    public record FailureDiagnostic(long eventId, String operation, String exceptionType, TextContent reason) {

        public FailureDiagnostic {
            Objects.requireNonNull(reason);
        }

        public FailureDiagnostic(long eventId, String operation, String exceptionType) {
            this(eventId, operation, exceptionType, text("failure_unexpected"));
        }
    }

    @FunctionalInterface
    public interface FailureReporter {

        void report(FailureDiagnostic diagnostic, Throwable failure);
    }

    /** Owner-issued borrow: the source store stays open until the installation coordinator releases it. */
    public record SavedRuntimeSource(LocalProjectAccess access, ExtensionRegistry registry, SavedProject saved,
                                     UUID documentId, String documentName) {}

    public SavedRuntimeSource borrowSavedRuntimeSource() {
        owner();
        requireIdle();
        requireWritable();
        var saved = session.saves().confirmed();
        if (activeDocument == null) throw rejection("install_save_first");
        var entry = saved.state().documents().get(activeDocument);
        if (entry == null) throw rejection("install_save_first");
        runtimeSource = new SavedRuntimeSource(access, registry, saved, activeDocument, entry.displayName());
        version++;
        return runtimeSource;
    }

    public void releaseSavedRuntimeSource(SavedRuntimeSource source) {
        owner();
        if (runtimeSource != source) throw new IllegalArgumentException("foreign runtime source");
        runtimeSource = null;
        version++;
    }

    boolean ownsRuntimeSource(SavedRuntimeSource source) {
        owner();
        return source != null && runtimeSource == source;
    }

    public Map<EditorId, RuntimeInstallationProfile> runtimeProfiles() {
        owner();
        if (session == null || activeDocument == null || !registry.defines(RuntimeInstallationProfile.KIND)) return Map.of();
        var entry = session.saves().confirmed().state().documents().get(activeDocument);
        if (entry == null) return Map.of();
        Map<EditorId, RuntimeInstallationProfile> result = new TreeMap<>();
        registry.entries(RuntimeInstallationProfile.KIND).forEach((id, profile) -> {
            if (profile.documentType().toString().equals(entry.typeId())) result.put(id, profile);
        });
        return Collections.unmodifiableMap(result);
    }

    public record RemovalDraft(UUID document, DocumentEditor.Node target, DocumentToken expected, DocumentEditor.RemovalPlan plan) {}

    /**
     * Owner-issued document gesture. A preview replaces this immutable capture; it never writes
     * raw input or history. World/frame validation belongs to the input host, not this draft.
     */
    public static final class PointMoveDraft {

        private final ProjectWorkbench issuer;
        private final DocumentHandle<?> handle;
        private final DocumentToken token;
        private final SpatialDocument.Point origin;
        private final Optional<SpatialDocument.Position> target;

        private PointMoveDraft(ProjectWorkbench issuer, DocumentHandle<?> handle, DocumentToken token,
                               SpatialDocument.Point origin, Optional<SpatialDocument.Position> target) {
            this.issuer = issuer;
            this.handle = handle;
            this.token = token;
            this.origin = origin;
            this.target = target;
        }

        public UUID document() {
            return token.documentId();
        }

        public DocumentToken token() {
            return token;
        }

        public String node() {
            return origin.node();
        }

        public SpatialDocument.Point origin() {
            return origin;
        }

        public Optional<SpatialDocument.Position> target() {
            return target;
        }
    }

    /** One complete area-bounds candidate. Preview and cancellation carry no write authority. */
    public static final class AreaEditDraft {

        private final ProjectWorkbench issuer;
        private final DocumentHandle<?> handle;
        private final DocumentToken token;
        private final SpatialDocument.Area origin;
        private final Optional<SpatialDocument.AreaBounds> target;

        private AreaEditDraft(ProjectWorkbench issuer, DocumentHandle<?> handle, DocumentToken token,
                              SpatialDocument.Area origin, Optional<SpatialDocument.AreaBounds> target) {
            this.issuer = issuer;
            this.handle = handle;
            this.token = token;
            this.origin = origin;
            this.target = target;
        }

        public UUID document() {
            return token.documentId();
        }

        public DocumentToken token() {
            return token;
        }

        public String node() {
            return origin.node();
        }

        public SpatialDocument.Area origin() {
            return origin;
        }

        public Optional<SpatialDocument.AreaBounds> target() {
            return target;
        }
    }

    public record ReferenceDraft(Address address, DocumentToken expected, DocumentEditor.ReferenceList list) {}

    public record CreationChoice(EditorId id, EditorId owner, TextContent label, TextContent category, ObjectCreator.Target target) {}

    public record CreationDraft(UUID document, EditorId creator, String parent, UUID identity,
                                DocumentToken expected, List<DocumentEditor.Field> fields) {

        public CreationDraft {
            fields = List.copyOf(fields);
        }
    }

    public record Address(UUID document, String node, String property) {

        public Address {
            Objects.requireNonNull(document);
            DocumentEditor.requireAddress(node);
            DocumentEditor.requireAddress(property);
        }

        public String pendingKey() {
            return node.length() + ":" + node + property.length() + ":" + property;
        }
    }

    private record Draft(DocumentToken expected, String raw, String baseline) {}

    private static final class Pending {

        final AtomicBoolean cancelled = new AtomicBoolean();
        final boolean cancellable;
        final String operation;
        boolean releasingCapture;
        DocumentSaveRequest documentSave;

        Pending(boolean cancellable, String operation) {
            this.cancellable = cancellable;
            this.operation = operation;
        }

        void check() {
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
        }
    }

    private record Opened(Path directory, LocalProjectStore store, LocalProjectAccess access, LoadedProject loaded, Creation uncertain) {}

    private record Creation(Path directory, LocalProjectStore store, UUID request, ProjectState state) {}

    private record InitialDocument(UUID id, EditorId owner, EditorId type, EditorId codec, int schema,
                                   String name, byte[] bytes, Map<String, String> modules) {}

    private record Preview<T>(ProjectSession session, DocumentImporter<T> importer, Map<String, byte[]> sources,
                              UUID seed, DocumentImporter.Result<T> result) {}

    private record ResourcePreview(ProjectSession session, ResourceToken expected, TextContent label, ResourceImportCapture capture) {}

    @FunctionalInterface
    interface StoreCreator {

        LocalProjectStore create(Path directory) throws IOException;
    }

    public ProjectWorkbench(ExtensionRegistry registry, Executor io, Executor completion) {
        this(registry, io, completion, LocalProjectStore::create, (diagnostic, failure) -> {});
    }

    public ProjectWorkbench(ExtensionRegistry registry, Executor io, Executor completion, FailureReporter failureReporter) {
        this(registry, io, completion, LocalProjectStore::create, failureReporter);
    }

    ProjectWorkbench(ExtensionRegistry registry, Executor io, Executor completion, StoreCreator creator) {
        this(registry, io, completion, creator, (diagnostic, failure) -> {});
    }

    private ProjectWorkbench(ExtensionRegistry registry, Executor io, Executor completion, StoreCreator creator, FailureReporter failureReporter) {
        this.registry = Objects.requireNonNull(registry);
        this.io = Objects.requireNonNull(io);
        this.completion = Objects.requireNonNull(completion);
        this.creator = Objects.requireNonNull(creator);
        this.failureReporter = Objects.requireNonNull(failureReporter);
    }

    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("workbench owner thread required");
    }

    public long version() {
        owner();
        return version;
    }

    public boolean busy() {
        owner();
        return pending != null || runtimeSource != null;
    }

    public boolean canCancel() {
        owner();
        return pending != null && pending.cancellable;
    }

    public boolean hasProject() {
        owner();
        return session != null;
    }

    public boolean unresolvedSave() {
        owner();
        return uncertainJob != null || creation != null && !creationOutcomeKnown;
    }

    public boolean pendingCreation() {
        owner();
        return creation != null;
    }

    public boolean protectedMode() {
        owner();
        return session != null && (session.protectedMode() || session.requiresReopen());
    }

    public TextContent status() {
        owner();
        return status;
    }

    public List<Problem> problems() {
        owner();
        return problems;
    }

    public Optional<Throwable> technicalFailure() {
        owner();
        return Optional.ofNullable(technicalFailure);
    }

    public Optional<FailureDiagnostic> failureDiagnostic() {
        owner();
        return Optional.ofNullable(failureDiagnostic);
    }

    public Optional<Path> directory() {
        owner();
        return Optional.ofNullable(directory);
    }

    /** Stable saved identity for host-local preferences; never a session generation or commit id. */
    public Optional<UUID> projectId() {
        owner();
        return session == null ? Optional.empty() : Optional.of(session.saves().confirmed().state().projectId());
    }

    public Optional<UUID> activeDocument() {
        owner();
        return Optional.ofNullable(activeDocument);
    }

    public Optional<String> selectedNode() {
        owner();
        return Optional.ofNullable(selectedNode);
    }

    /** Exact panel context; reading a pinned document never changes the active document. */
    public Optional<String> selectedNode(UUID document) {
        owner();
        return document(document) != null ? Optional.ofNullable(selectedNodes.get(document)) : Optional.empty();
    }

    public Map<UUID, DocumentHandle<?>> documents() {
        owner();
        return session == null ? Map.of() : session.documents();
    }

    public String projectName() {
        owner();
        return session == null ? "" : session.saves().confirmed().state().name();
    }

    public Optional<UUID> savedVersion() {
        owner();
        return session == null ? Optional.empty() : Optional.of(session.saves().confirmed().commitId());
    }

    public Map<EditorId, ActivatedDocumentType<?>> documentTypes() {
        owner();
        return DocumentTypeCatalog.activate(registry).entries();
    }

    public Map<EditorId, ProjectTemplates.Category> projectCategories() {
        owner();
        return registry.defines(ProjectTemplates.CATEGORIES) ? registry.entries(ProjectTemplates.CATEGORIES) : Map.of();
    }

    public Map<EditorId, ProjectTemplate<?>> projectTemplates(EditorId category) {
        owner();
        Map<EditorId, ProjectTemplate<?>> result = new TreeMap<>();
        if (registry.defines(ProjectTemplates.TEMPLATES)) registry.entries(ProjectTemplates.TEMPLATES).forEach((id, template) -> {
            if (template.category().equals(category)) {
                requireTemplate(id);
                result.put(id, template);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    public List<DocumentEditor.Field> projectTemplateFields(EditorId template) {
        owner();
        var fields = List.copyOf(requireTemplate(template).fields());
        if (fields.size() > 64 || fields.stream().map(DocumentEditor.Field::property).distinct().count() != fields.size())
            throw rejection("invalid_presentation");
        return fields;
    }

    public boolean templateRequiresWorld(EditorId template) {
        owner();
        return requireTemplate(template).requiresWorld();
    }

    /** Uses contributed requirements and includes every saved or current document type. */
    public boolean projectRequiresWorld() {
        owner();
        if (session == null || !registry.defines(ProjectTemplates.TEMPLATES)) return false;
        Set<String> types = new HashSet<>();
        session.saves().confirmed().state().documents().values().forEach(entry -> types.add(entry.typeId()));
        session.documents().values().forEach(handle -> types.add(handle.typeId().toString()));
        return registry.entries(ProjectTemplates.TEMPLATES).values().stream()
                .anyMatch(template -> template.requiresWorld() && types.contains(template.documentType().toString()));
    }

    private ProjectTemplate<?> requireTemplate(EditorId id) {
        var template = registry.defines(ProjectTemplates.TEMPLATES) ? registry.find(ProjectTemplates.TEMPLATES, id).orElse(null) : null;
        if (template == null) throw rejection("missing_target");
        var metadata = registry.metadata().contributions();
        var contribution = metadata.get(new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(ProjectTemplates.TEMPLATES.id(), id));
        var documentKey = new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(net.ptcrys.fpsmatch.editor.core.types.DocumentTypes.KIND.id(), template.documentType());
        var categoryKey = new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(ProjectTemplates.CATEGORIES.id(), template.category());
        var document = metadata.get(documentKey);
        if (document == null || !contribution.owner().equals(document.owner()) || !contribution.requiredContributions().containsAll(Set.of(documentKey, categoryKey)) || contribution.side() != net.ptcrys.fpsmatch.editor.api.registry.Contribution.Side.COMMON)
            throw rejection("invalid_presentation");
        return template;
    }

    public Collection<net.ptcrys.fpsmatch.editor.common.ui.model.WorkspaceTemplate> workspaceTemplates() {
        owner();
        var kind = net.ptcrys.fpsmatch.editor.common.ui.model.WorkspaceTemplates.KIND;
        return registry.defines(kind) ? List.copyOf(registry.entries(kind).values()) : List.of();
    }

    public Map<EditorId, DocumentImporter<?>> importers() {
        owner();
        return registry.defines(DocumentEditors.IMPORTERS) ? registry.entries(DocumentEditors.IMPORTERS) : Map.of();
    }

    public Map<EditorId, ResourceImporter> resourceImporters() {
        owner();
        return registry.defines(ResourceImporters.KIND) ? registry.entries(ResourceImporters.KIND) : Map.of();
    }

    public Optional<ResourceView> resources() {
        owner();
        return session == null ? Optional.empty() : session.resources().map(ResourceHandle::view);
    }

    /** Exact direct locked references of the applied draft; pending raw input never changes this list. */
    public Optional<List<net.ptcrys.fpsmatch.editor.core.resources.ResourceRef>> resourcesForActiveDocument() {
        owner();
        return activeDocument == null ? Optional.empty() : resourcesForDocument(activeDocument);
    }

    public Optional<List<net.ptcrys.fpsmatch.editor.core.resources.ResourceRef>> resourcesForDocument(UUID document) {
        owner();
        var handle = document(document);
        return handle == null ? Optional.empty() : Optional.of(List.copyOf(handle.currentDependencies().resources()));
    }

    public TextContent resourceLabel(UUID id) {
        owner();
        var catalog = resources().orElseThrow().content();
        var resource = Objects.requireNonNull(catalog.resources().get(id));
        var version = catalog.versions().get(resource.currentVersion());
        var metadata = registry.metadata().contributions();
        var producer = version == null ? null : metadata.get(new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(
                net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers.KIND.id(), version.importer()));
        if (producer != null) for (var entry : new TreeMap<>(resourceImporters()).entrySet()) {
            var contribution = metadata.get(new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(ResourceImporters.KIND.id(), entry.getKey()));
            if (contribution != null && contribution.owner().equals(producer.owner())) {
                var label = entry.getValue().resourceLabel(catalog, id);
                if (label.isPresent()) return label.get();
            }
        }
        return new TextContent.Literal(resource.name());
    }

    public TextContent typeLabel(EditorId id) {
        owner();
        var type = documentTypes().get(id);
        if (type == null) return new TextContent.Literal(id.toString());
        return typeLabel(type);
    }

    private <T> TextContent typeLabel(ActivatedDocumentType<T> type) {
        return DocumentEditors.find(registry, type.id(), type.valueType()).map(DocumentEditor::label)
                .orElseGet(() -> new TextContent.Literal(type.id().toString()));
    }

    public boolean unsaved() {
        owner();
        return session != null && (session.documents().values().stream().anyMatch(DocumentHandle::hasUnpublishedChanges) || session.resources().map(r -> r.view().dirty()).orElse(false) || !inputs.isEmpty());
    }

    public boolean documentUnsaved(UUID document) {
        owner();
        var handle = document(document);
        return handle != null && (handle.hasUnpublishedChanges() || documentHasPendingInputs(document));
    }

    public boolean documentHasPendingInputs(UUID document) {
        owner();
        var handle = document(document);
        return handle != null && (!handle.view().pendingInputs().isEmpty() || inputs.keySet().stream().anyMatch(address -> address.document().equals(document)));
    }

    public boolean documentHasSavedBaseline(UUID document) {
        owner();
        return session != null && session.saves().confirmed().state().documents().containsKey(document);
    }

    public enum DocumentSaveState {
        PREPARING,
        PUBLISHING,
        CONFIRMED,
        CANCELLED,
        FAILED,
        UNKNOWN
    }

    /** Unique owner-issued operation identity; a later session cannot satisfy an earlier close guard. */
    public record DocumentSaveRequest(UUID id, UUID document) {

        public DocumentSaveRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(document);
        }
    }

    public Optional<DocumentSaveState> documentSaveState(DocumentSaveRequest request) {
        owner();
        return request != null && request.equals(lastDocumentSave) ? Optional.of(lastDocumentSaveState) : Optional.empty();
    }

    /** Explicitly abandons only this document's applied draft, pending inputs and history. */
    public void discardDocumentChanges(UUID document) {
        owner();
        requireIdle();
        requireWritable();
        boolean ownFailure = !failureInputs.isEmpty() && failureInputs.stream().allMatch(address -> address.document().equals(document));
        session.discardDocumentChanges(document);
        inputs.keySet().removeIf(address -> address.document().equals(document));
        selectedNodes.remove(document);
        if (document.equals(activeDocument)) selectedNode = null;
        if (objectCreation != null && objectCreation.document().equals(document)) objectCreation = null;
        if (referenceDraft != null && referenceDraft.address().document().equals(document)) referenceDraft = null;
        if (removalDraft != null && removalDraft.document().equals(document)) removalDraft = null;
        if (pointMove != null && pointMove.document().equals(document)) pointMove = null;
        if (ownFailure) {
            problems = List.of();
            clearTechnicalFailure();
        }
        if (failureDiagnostic == null) status = text("document_discarded");
        version++;
    }

    public boolean canUndo() {
        owner();
        var handle = active();
        return handle != null && handle.view().canUndo() && handle.view().pendingInputs().isEmpty();
    }

    public boolean canRedo() {
        owner();
        var handle = active();
        return handle != null && handle.view().canRedo() && handle.view().pendingInputs().isEmpty();
    }

    public void open(Path path) {
        open(path, null);
    }

    public void create(Path path, String name) {
        open(path, Objects.requireNonNull(name));
    }

    public void create(Path path, String projectName, EditorId templateId, String documentName, Map<String, String> raw) {
        owner();
        requireIdle();
        if (session != null || creation != null) throw rejection("close_first");
        if (!Objects.requireNonNull(path).isAbsolute()) throw rejection("invalid_path");
        if (Objects.requireNonNull(projectName).isBlank() || Objects.requireNonNull(documentName).isBlank()) throw rejection("invalid_name");
        // Validate both metadata names before invoking a recipe or creating a directory.
        new ProjectState(UUID.randomUUID(), projectName, Map.of(), Map.of(), Map.of(), null);
        new ProjectState(UUID.randomUUID(), documentName, Map.of(), Map.of(), Map.of(), null);
        var template = requireTemplate(templateId);
        var fields = projectTemplateFields(templateId);
        Map<String, String> fixedRaw = Map.copyOf(raw);
        if (!fields.stream().map(DocumentEditor.Field::property).collect(java.util.stream.Collectors.toSet()).equals(fixedRaw.keySet()))
            throw rejection("missing_input");
        for (var entry : fixedRaw.entrySet()) if (entry.getValue().length() > 4096)
            throw new SessionRejection(List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("editor:workbench/invalid_template_parameter"),
                    entry.getKey(), text("invalid_template_parameter"))));
        InitialDocument initial = prepareInitial(template, documentName, fixedRaw);
        open(path, projectName, initial);
    }

    private <T> InitialDocument prepareInitial(ProjectTemplate<T> template, String name, Map<String, String> raw) {
        var catalog = DocumentTypeCatalog.activate(registry);
        var candidateType = catalog.find(template.documentType()).orElseThrow(() -> rejection("missing_target"));
        if (candidateType.valueType() != template.valueType()) throw rejection("invalid_presentation");
        @SuppressWarnings("unchecked")
        ActivatedDocumentType<T> type = (ActivatedDocumentType<T>) candidateType;
        T value = type.snapshot(Objects.requireNonNull(template.create(registry, raw)));
        var diagnostics = type.validate(value);
        if (diagnostics.stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR)) throw new DocumentCodecException(diagnostics);
        var dependencies = type.dependencies(value);
        // Initial recipes have no existing project or resource catalog to resolve external references.
        if (!dependencies.documents().isEmpty() || !dependencies.resources().isEmpty()) throw rejection("missing_target");
        var roots = new TreeSet<>(dependencies.contributions());
        roots.add(new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(net.ptcrys.fpsmatch.editor.core.types.DocumentTypes.KIND.id(), type.id()));
        Map<String, String> modules = new TreeMap<>();
        catalog.requiredModules(roots).forEach((id, version) -> modules.put(id.toString(), version));
        return new InitialDocument(UUID.randomUUID(), type.owner(), type.id(), type.codecId(), type.schemaVersion(), name, type.encode(value).clone(), Map.copyOf(modules));
    }

    private void open(Path path, String newName) {
        open(path, newName, null);
    }

    private void open(Path path, String newName, InitialDocument initial) {
        owner();
        requireIdle();
        if (session != null || creation != null) throw rejection("close_first");
        if (!Objects.requireNonNull(path).isAbsolute() || (newName != null && newName.isBlank())) throw rejection("invalid_path");
        Path fixed = path.normalize();
        // Creating starts a real publication and cannot be represented as cancelled afterwards.
        Pending operation = start(newName == null ? "opening" : "creating", newName == null);
        submit(operation, () -> {
            operation.check();
            LocalProjectStore opened = newName == null ? LocalProjectStore.open(fixed) : creator.create(fixed);
            try {
                if (newName != null) {
                    Map<UUID, ProjectState.Entry> entries = Map.of();
                    Map<String, String> modules = Map.of();
                    if (initial != null) {
                        BlobRef blob = opened.stage(BlobRef.Kind.DOCUMENT, initial.bytes);
                        var entry = new ProjectState.Entry(initial.id, initial.owner.toString(), initial.type.toString(),
                                initial.codec.toString(), initial.schema, initial.name, blob, Set.of(), Set.of());
                        entries = Map.of(initial.id, entry);
                        modules = initial.modules;
                    }
                    Creation initialCreation = new Creation(fixed, opened, UUID.randomUUID(),
                            new ProjectState(UUID.randomUUID(), newName, modules, entries, Map.of(), null));
                    try {
                        opened.commit(null, initialCreation.request, initialCreation.state);
                    } catch (ProjectStorageException failure) {
                        if (failure.code() != ProjectStorageException.Code.RESULT_UNKNOWN) throw failure;
                        return new Opened(fixed, opened, null, null, initialCreation);
                    }
                }
                LocalProjectAccess projectAccess = new LocalProjectAccess(opened);
                // Validated reads retain unsupported content and diagnostics for protected inspection.
                // The same operation owns cancellation throughout content verification and adoption.
                LoadedProject loaded = projectAccess.readValidated(registry, operation.cancelled::get);
                operation.check();
                return new Opened(fixed, opened, projectAccess, loaded, null);
            } catch (Throwable failure) {
                try {
                    opened.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }, opened -> adopt(operation, opened));
    }

    private void adopt(Pending operation, Opened opened) {
        if (opened.uncertain != null) {
            creation = opened.uncertain;
            creationOutcomeKnown = false;
            directory = opened.directory;
            finish(operation, text("unknown"), List.of());
            return;
        }
        // A cancellation may arrive after the worker returned and before this owner callback.
        if (operation.cancelled.get()) {
            closeUnadopted(operation, opened, null);
            return;
        }
        try {
            ProjectSession adopted = ProjectSession.open(opened.access, opened.loaded, registry, 256);
            store = opened.store;
            access = opened.access;
            directory = opened.directory;
            session = adopted;
            lastDocumentSave = null;
            lastDocumentSaveState = null;
            uncertainDocumentSave = null;
            creation = null;
            creationOutcomeKnown = false;
            activeDocument = adopted.documents().keySet().stream().findFirst().orElse(null);
            finish(operation, text("opened"), adopted.problems());
        } catch (Throwable failure) {
            closeUnadopted(operation, opened, failure);
        }
    }

    private void closeUnadopted(Pending operation, Opened opened, Throwable original) {
        io.execute(() -> {
            Throwable failure = original;
            try {
                opened.store.close();
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            Throwable result = failure;
            completion.execute(() -> {
                if (result == null) finish(operation, text("cancelled"), List.of());
                else fail(operation, result);
            });
        });
    }

    public void closeProject(boolean discardDrafts) {
        owner();
        requireIdle();
        if (creation != null) {
            if (!creationOutcomeKnown) throw rejection("unknown_close");
            LocalProjectStore closing = creation.store;
            creation = null;
            directory = null;
            creationOutcomeKnown = false;
            Pending operation = start("closing", false);
            submit(operation, () -> {
                closing.close();
                return Boolean.TRUE;
            }, ignored -> finish(operation, text("closed"), List.of()));
            return;
        }
        if (session == null) return;
        if (uncertainJob != null || session.saves().state() == SaveQueueView.State.UNKNOWN) throw rejection("unknown_close");
        if (!discardDrafts && unsaved()) throw rejection("unsaved_close");
        session.close();
        session = null;
        pointMove = null;
        areaEdit = null;
        lastDocumentSave = null;
        lastDocumentSaveState = null;
        uncertainDocumentSave = null;
        LocalProjectStore closing = store;
        ResourcePreview abandoned = resourcePreview;
        resourcePreview = null;
        store = null;
        access = null;
        directory = null;
        activeDocument = null;
        selectedNode = null;
        inputs.clear();
        editors.clear();
        selectedNodes.clear();
        preview = null;
        objectCreation = null;
        referenceDraft = null;
        removalDraft = null;
        studioClipboard = null;
        Pending operation = start("closing", false);
        Work<Boolean> closingWork = () -> {
            try {
                if (abandoned != null) abandoned.capture.close();
            } finally {
                closing.close();
            }
            return Boolean.TRUE;
        };
        submit(operation, closingWork, ignored -> finish(operation, text("closed"), List.of()), ignored -> {}, closingWork);
    }

    public void cancel() {
        owner();
        if (pending != null && pending.cancellable) {
            pending.cancelled.set(true);
            status = text("cancelling");
            version++;
        }
    }

    public void selectDocument(UUID id) {
        owner();
        if (document(id) == null) throw rejection("missing_target");
        if (id.equals(activeDocument)) return;
        activeDocument = id;
        selectedNode = selectedNodes.get(id);
        version++;
    }

    public void selectNode(String id) {
        owner();
        if (nodes().stream().noneMatch(node -> node.id().equals(id))) throw rejection("missing_target");
        if (id.equals(selectedNode)) return;
        selectedNode = id;
        selectedNodes.put(Objects.requireNonNull(activeDocument), id);
        version++;
    }

    public List<DocumentEditor.Node> nodes() {
        owner();
        var handle = active();
        return handle == null ? List.of() : nodes(handle);
    }

    public List<DocumentEditor.Node> nodes(UUID document) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : nodes(handle);
    }

    /** Validated pure metadata, including points hidden from the scene-only node projection. */
    public List<SpatialDocument.Point> spatialPoints(UUID document) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : spatialPoints(handle);
    }

    private <T> List<SpatialDocument.Point> spatialPoints(DocumentHandle<T> handle) {
        var token = handle.view().token();
        var editor = editor(handle);
        var points = editor.isEmpty() ? List.<SpatialDocument.Point>of() : spatialData(editor.orElseThrow(), handle.view().content()).points();
        requirePointDocument(handle, token);
        return points;
    }

    public List<SpatialDocument.Area> spatialAreas(UUID document) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : spatialAreas(handle);
    }

    private <T> List<SpatialDocument.Area> spatialAreas(DocumentHandle<T> handle) {
        var token = handle.view().token();
        var editor = editor(handle);
        var areas = editor.isEmpty() ? List.<SpatialDocument.Area>of() : spatialData(editor.orElseThrow(), handle.view().content()).areas();
        requirePointDocument(handle, token);
        return areas;
    }

    private record SpatialData(Map<String, DocumentEditor.Node> nodes, List<SpatialDocument.Point> points,
                               List<SpatialDocument.Area> areas) {}

    private <T> SpatialData spatialData(DocumentEditor<T> editor, T value) {
        var nodes = List.copyOf(editor.nodes(value));
        new ObjectHierarchy(nodes);
        var index = new LinkedHashMap<String, DocumentEditor.Node>();
        nodes.forEach(node -> index.put(node.id(), node));
        var points = List.copyOf(editor.spatialPoints(value));
        var areas = List.copyOf(editor.spatialAreas(value));
        var ids = new HashSet<String>();
        if (points.size() + areas.size() > 8192) throw rejection("invalid_presentation");
        for (var point : points)
            if (!index.containsKey(point.node()) || !ids.add(point.node())) throw rejection("invalid_presentation");
        for (var area : areas)
            if (!index.containsKey(area.node()) || !ids.add(area.node())) throw rejection("invalid_presentation");
        return new SpatialData(Collections.unmodifiableMap(index), points, areas);
    }

    public PointMoveDraft beginPointMove(UUID document, String node) {
        owner();
        requireIdle();
        requireWritable();
        DocumentEditor.requireAddress(node);
        if (unresolvedSave()) throw rejection("protected");
        var handle = document(document);
        if (handle == null) throw rejection("missing_target");
        requirePointInputs(document);
        var token = handle.view().token();
        var previous = pointMove;
        var previousArea = areaEdit;
        var point = spatialPoints(handle).stream().filter(value -> value.node().equals(node)).findFirst()
                .orElseThrow(() -> rejection("missing_target"));
        requirePointDocument(handle, token);
        requireIdle();
        requireWritable();
        requirePointInputs(document);
        if (unresolvedSave()) throw rejection("protected");
        if (pointMove != previous || areaEdit != previousArea) throw rejection("source_changed");
        areaEdit = null;
        pointMove = new PointMoveDraft(this, handle, token, point, Optional.empty());
        return pointMove;
    }

    /** Each new preview retires its predecessor. No editor command, model version or history changes. */
    public PointMoveDraft previewPointMove(PointMoveDraft draft, SpatialDocument.Position target) {
        owner();
        requirePointMove(draft);
        Objects.requireNonNull(target, "target");
        requirePointReady(draft);
        pointMove = new PointMoveDraft(this, draft.handle, draft.token, draft.origin, Optional.of(target));
        return pointMove;
    }

    /** Cheap current-context query; it does not call document contributors or consume the draft. */
    public boolean pointMoveCurrent(PointMoveDraft draft) {
        owner();
        if (draft == null || draft.issuer != this || pointMove != draft || busy() || !hasProject() || protectedMode() || unresolvedSave() || document(draft.document()) != draft.handle) return false;
        return draft.handle.view().token().equals(draft.token) && !documentHasPendingInputs(draft.document());
    }

    /**
     * One attempt consumes the current draft, even when validation rejects it. Foreign or retired
     * drafts cannot consume a newer gesture. False means numerically unchanged coordinates.
     */
    public boolean commitPointMove(PointMoveDraft draft) {
        owner();
        requirePointMove(draft);
        pointMove = null;
        requirePointReady(draft);
        var target = draft.target.orElseThrow(() -> rejection("missing_target"));
        return commitPointMove(draft.handle, draft, target);
    }

    private <T> boolean commitPointMove(DocumentHandle<T> handle, PointMoveDraft draft, SpatialDocument.Position target) {
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        var before = spatialData(editor, handle.view().content());
        var origin = before.points().stream().filter(point -> point.node().equals(draft.node())).findFirst()
                .orElseThrow(() -> rejection("missing_target"));
        if (!samePoint(origin, draft.origin)) throw rejection("source_changed");
        requirePointReady(draft);
        if (origin.position().sameCoordinates(target)) return false;
        handle.edit(draft.token, text("move_point"), value -> {
            T candidate = editor.moveSpatialPoint(value, draft.node(), target);
            validatePointMove(before, spatialData(editor, candidate), draft.node(), target);
            return candidate;
        });
        status = text("edited");
        problems = List.of();
        clearTechnicalFailure();
        version++;
        return true;
    }

    private void validatePointMove(SpatialData before, SpatialData after, String targetNode, SpatialDocument.Position target) {
        validateSpatialNodes(before, after);
        validateSpatialPoints(before, after, targetNode, target);
        validateSpatialAreas(before, after, null, null);
    }

    private void validateSpatialNodes(SpatialData before, SpatialData after) {
        if (!before.nodes().keySet().equals(after.nodes().keySet())) throw rejection("invalid_presentation");
        for (var entry : before.nodes().entrySet()) {
            var next = after.nodes().get(entry.getKey());
            if (!entry.getValue().parent().equals(next.parent()) || entry.getValue().symbol() != next.symbol())
                throw rejection("invalid_presentation");
        }
    }

    private void validateSpatialPoints(SpatialData before, SpatialData after, String targetNode, SpatialDocument.Position target) {
        var points = new HashMap<String, SpatialDocument.Point>();
        after.points().forEach(point -> points.put(point.node(), point));
        if (before.points().size() != points.size()) throw rejection("invalid_presentation");
        for (var previous : before.points()) {
            var next = points.get(previous.node());
            if (next == null || !samePointMetadata(previous, next) || !(previous.node().equals(targetNode) ? target : previous.position()).sameCoordinates(next.position()))
                throw rejection("invalid_presentation");
        }
    }

    private void validateSpatialAreas(SpatialData before, SpatialData after, String targetNode, SpatialDocument.AreaBounds target) {
        var areas = new HashMap<String, SpatialDocument.Area>();
        after.areas().forEach(area -> areas.put(area.node(), area));
        if (before.areas().size() != areas.size()) throw rejection("invalid_presentation");
        for (var previous : before.areas()) {
            var next = areas.get(previous.node());
            if (next == null || !sameAreaMetadata(previous, next) || !(previous.node().equals(targetNode) ? target : previous.bounds()).sameBounds(next.bounds()))
                throw rejection("invalid_presentation");
        }
    }

    private static boolean sameAreaMetadata(SpatialDocument.Area left, SpatialDocument.Area right) {
        return left.node().equals(right.node()) && left.dimension().equals(right.dimension()) && left.semantics().equals(right.semantics());
    }

    private static boolean sameArea(SpatialDocument.Area left, SpatialDocument.Area right) {
        return sameAreaMetadata(left, right) && left.bounds().sameBounds(right.bounds());
    }

    private static boolean samePointMetadata(SpatialDocument.Point left, SpatialDocument.Point right) {
        return left.node().equals(right.node()) && left.dimension().equals(right.dimension()) && left.yaw().compareTo(right.yaw()) == 0 && left.pitch().compareTo(right.pitch()) == 0 && left.direction().equals(right.direction()) && left.groundDirection().equals(right.groundDirection());
    }

    private static boolean samePoint(SpatialDocument.Point left, SpatialDocument.Point right) {
        return samePointMetadata(left, right) && left.position().sameCoordinates(right.position());
    }

    private void requirePointMove(PointMoveDraft draft) {
        if (draft == null || draft.issuer != this || pointMove != draft) throw rejection("source_changed");
    }

    private void requirePointReady(PointMoveDraft draft) {
        requireIdle();
        requireWritable();
        if (unresolvedSave()) throw rejection("protected");
        requirePointDocument(draft.handle, draft.token);
        requirePointInputs(draft.document());
    }

    private void requirePointDocument(DocumentHandle<?> handle, DocumentToken token) {
        if (document(token.documentId()) != handle || !handle.view().token().equals(token)) throw rejection("source_changed");
    }

    private void requirePointInputs(UUID document) {
        if (documentHasPendingInputs(document)) throw rejection("pending_inputs");
    }

    public boolean cancelPointMove(PointMoveDraft draft) {
        owner();
        if (draft == null || draft.issuer != this || pointMove != draft) return false;
        pointMove = null;
        return true;
    }

    public AreaEditDraft beginAreaEdit(UUID document, String node) {
        owner();
        requireIdle();
        requireWritable();
        DocumentEditor.requireAddress(node);
        if (unresolvedSave()) throw rejection("protected");
        var handle = document(document);
        if (handle == null) throw rejection("missing_target");
        requirePointInputs(document);
        var token = handle.view().token();
        var previous = areaEdit;
        var previousPoint = pointMove;
        var area = spatialAreas(handle).stream().filter(value -> value.node().equals(node)).findFirst()
                .orElseThrow(() -> rejection("missing_target"));
        requirePointDocument(handle, token);
        requireIdle();
        requireWritable();
        requirePointInputs(document);
        if (unresolvedSave()) throw rejection("protected");
        if (areaEdit != previous || pointMove != previousPoint) throw rejection("source_changed");
        pointMove = null;
        areaEdit = new AreaEditDraft(this, handle, token, area, Optional.empty());
        return areaEdit;
    }

    public AreaEditDraft previewAreaEdit(AreaEditDraft draft, SpatialDocument.AreaBounds target) {
        owner();
        requireAreaEdit(draft);
        Objects.requireNonNull(target, "target");
        requireAreaReady(draft);
        areaEdit = new AreaEditDraft(this, draft.handle, draft.token, draft.origin, Optional.of(target));
        return areaEdit;
    }

    public boolean areaEditCurrent(AreaEditDraft draft) {
        owner();
        if (draft == null || draft.issuer != this || areaEdit != draft || busy() || !hasProject() || protectedMode() || unresolvedSave() || document(draft.document()) != draft.handle) return false;
        return draft.handle.view().token().equals(draft.token) && !documentHasPendingInputs(draft.document());
    }

    /** One attempt consumes only this current capture; all six coordinates publish together. */
    public boolean commitAreaEdit(AreaEditDraft draft) {
        owner();
        requireAreaEdit(draft);
        areaEdit = null;
        requireAreaReady(draft);
        var target = draft.target.orElseThrow(() -> rejection("missing_target"));
        return commitAreaEdit(draft.handle, draft, target);
    }

    private <T> boolean commitAreaEdit(DocumentHandle<T> handle, AreaEditDraft draft, SpatialDocument.AreaBounds target) {
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        var before = spatialData(editor, handle.view().content());
        var origin = before.areas().stream().filter(area -> area.node().equals(draft.node())).findFirst()
                .orElseThrow(() -> rejection("missing_target"));
        if (!sameArea(origin, draft.origin)) throw rejection("source_changed");
        requireAreaReady(draft);
        if (origin.bounds().sameBounds(target)) return false;
        handle.edit(draft.token, text("edit_area"), value -> {
            T candidate = editor.setSpatialAreaBounds(value, draft.node(), target);
            var after = spatialData(editor, candidate);
            validateSpatialNodes(before, after);
            validateSpatialPoints(before, after, null, null);
            validateSpatialAreas(before, after, draft.node(), target);
            return candidate;
        });
        status = text("edited");
        problems = List.of();
        clearTechnicalFailure();
        version++;
        return true;
    }

    private void requireAreaEdit(AreaEditDraft draft) {
        if (draft == null || draft.issuer != this || areaEdit != draft) throw rejection("source_changed");
    }

    private void requireAreaReady(AreaEditDraft draft) {
        requireIdle();
        requireWritable();
        if (unresolvedSave()) throw rejection("protected");
        requirePointDocument(draft.handle, draft.token);
        requirePointInputs(draft.document());
    }

    public boolean cancelAreaEdit(AreaEditDraft draft) {
        owner();
        if (draft == null || draft.issuer != this || areaEdit != draft) return false;
        areaEdit = null;
        return true;
    }

    public List<DocumentEditor.Node> sceneNodes(UUID document) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : sceneNodes(handle);
    }

    private <T> List<DocumentEditor.Node> sceneNodes(DocumentHandle<T> handle) {
        var editor = editor(handle);
        if (editor.isEmpty()) return List.of();
        var all = nodes(handle);
        new ObjectHierarchy(all);
        var byId = new HashMap<String, DocumentEditor.Node>();
        all.forEach(n -> byId.put(n.id(), n));
        var scene = List.copyOf(editor.get().sceneNodes(handle.view().content()));
        new ObjectHierarchy(scene);
        if (scene.size() > all.size()) throw rejection("invalid_presentation");
        for (var node : scene) {
            var actual = byId.get(node.id());
            if (actual == null || !actual.label().equals(node.label())) throw rejection("invalid_presentation");
            // A display parent may skip hidden internals, but must remain a real ancestor.
            var parent = actual.parent();
            while (parent.isPresent() && !parent.equals(node.parent())) parent = byId.get(parent.orElseThrow()).parent();
            if (!parent.equals(node.parent())) throw rejection("invalid_presentation");
        }
        return scene;
    }

    public List<StudioDocument.Form> studioForms(UUID document) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : studioForms(handle);
    }

    private <T> List<StudioDocument.Form> studioForms(DocumentHandle<T> handle) {
        var editor = editor(handle);
        if (editor.isEmpty()) return List.of();
        var all = nodes(handle);
        new ObjectHierarchy(all);
        var ids = all.stream().map(DocumentEditor.Node::id).collect(java.util.stream.Collectors.toSet());
        var forms = List.copyOf(editor.get().studioForms(handle.view().content()));
        if (forms.size() > 32 || forms.stream().map(StudioDocument.Form::id).distinct().count() != forms.size()) throw rejection("invalid_presentation");
        for (var form : forms) {
            if (!ids.contains(form.target())) throw rejection("invalid_presentation");
            var pages = InspectorPresentation.pages(fields(handle, form.target())).stream()
                    .map(InspectorPresentation.Page::id).collect(java.util.stream.Collectors.toSet());
            if (!pages.containsAll(form.pageIds())) throw rejection("invalid_presentation");
        }
        return forms;
    }

    public Optional<net.ptcrys.fpsmatch.editor.core.editing.StudioDocument.Grid> studioGrid(UUID document, String node) {
        owner();
        var handle = document(document);
        return handle == null ? Optional.empty() : studioGrid(handle, node);
    }

    private <T> Optional<net.ptcrys.fpsmatch.editor.core.editing.StudioDocument.Grid> studioGrid(DocumentHandle<T> handle, String node) {
        var editor = editor(handle);
        if (editor.isEmpty()) return Optional.empty();
        var all = nodes(handle);
        new ObjectHierarchy(all);
        var byId = new HashMap<String, DocumentEditor.Node>();
        all.forEach(n -> byId.put(n.id(), n));
        if (!byId.containsKey(node)) throw rejection("missing_target");
        var grid = editor.get().studioGrid(handle.view().content(), node);
        grid.ifPresent(g -> {
            if (!byId.containsKey(g.owner()) || !ancestor(byId, node, g.owner())) throw rejection("invalid_presentation");
            for (var cell : g.cells()) {
                cell.node().ifPresent(id -> { if (!byId.containsKey(id) || !byId.get(id).parent().equals(Optional.of(g.owner()))) throw rejection("invalid_presentation"); });
                cell.item().ifPresent(id -> { if (!byId.containsKey(id) || !byId.get(id).parent().equals(cell.node())) throw rejection("invalid_presentation"); });
            }
        });
        return grid;
    }

    public List<net.ptcrys.fpsmatch.editor.core.editing.StudioDocument.Section> studioSections(UUID document, String node) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : studioSections(handle, node);
    }

    private <T> List<net.ptcrys.fpsmatch.editor.core.editing.StudioDocument.Section> studioSections(DocumentHandle<T> handle, String node) {
        var editor = editor(handle);
        if (editor.isEmpty()) return List.of();
        var all = nodes(handle);
        new ObjectHierarchy(all);
        var byId = new HashMap<String, DocumentEditor.Node>();
        all.forEach(n -> byId.put(n.id(), n));
        if (!byId.containsKey(node)) throw rejection("missing_target");
        var sections = List.copyOf(editor.get().studioSections(handle.view().content(), node));
        if (sections.size() > 8192 || sections.stream().map(net.ptcrys.fpsmatch.editor.core.editing.StudioDocument.Section::id).distinct().count() != sections.size()) throw rejection("invalid_presentation");
        for (var section : sections) {
            if (!section.target().equals(byId.get(section.target().id()))) throw rejection("invalid_presentation");
            for (var child : section.children()) if (!child.equals(byId.get(child.id())) || child.id().equals(section.target().id()) || !ancestor(byId, child.id(), section.target().id())) throw rejection("invalid_presentation");
            for (var source : section.referencedBy()) {
                if (!source.equals(byId.get(source.id())) || referenceLists(handle, source.id()).stream().noneMatch(list -> list.selected().contains(section.target().id()))) throw rejection("invalid_presentation");
            }
        }
        return sections;
    }

    private static boolean ancestor(Map<String, DocumentEditor.Node> nodes, String child, String parent) {
        String current = child;
        while (current != null) {
            if (current.equals(parent)) return true;
            current = nodes.get(current).parent().orElse(null);
        }
        return false;
    }

    public void copyStudioCell(UUID document, String node) {
        owner();
        var handle = document(document);
        if (handle == null) throw rejection("missing_target");
        copyStudioCell(handle, node);
    }

    private <T> void copyStudioCell(DocumentHandle<T> handle, String node) {
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        if (studioGrid(handle, node).stream().flatMap(g -> g.cells().stream()).noneMatch(c -> c.node().filter(node::equals).isPresent())) throw rejection("missing_target");
        studioClipboard = new StudioClipboard(handle, editor, Objects.requireNonNull(editor.copyStudioCell(handle.view().content(), node)));
        version++;
    }

    public boolean canPasteStudioCell(UUID document) {
        owner();
        return studioClipboard != null && studioClipboard.document() == document(document);
    }

    public void applyStudioCommand(UUID document, StudioDocument.Command command) {
        owner();
        requireWritable();
        var handle = document(document);
        if (handle == null) throw rejection("missing_target");
        applyStudioCommand(handle, Objects.requireNonNull(command));
        status = text("edited");
        problems = List.of();
        clearTechnicalFailure();
        version++;
    }

    private <T> void applyStudioCommand(DocumentHandle<T> handle, StudioDocument.Command command) {
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        Optional<Object> clipboard = Optional.empty();
        if (command.action() == StudioDocument.Action.PASTE) {
            if (studioClipboard == null || studioClipboard.document() != handle || studioClipboard.editor() != editor) throw rejection("missing_target");
            clipboard = Optional.of(studioClipboard.payload());
        }
        var captured = clipboard;
        handle.edit(handle.view().token(), text("edited"), value -> editor.applyStudioCommand(value, command, captured));
    }

    public String createStudioCell(UUID document, String owner, String column, int row) {
        owner();
        requireWritable();
        var handle = document(document);
        if (handle == null) throw rejection("missing_target");
        String created = createStudioCell(handle, owner, column, row);
        selectNode(document, created);
        status = text("object_created");
        problems = List.of();
        clearTechnicalFailure();
        version++;
        return created;
    }

    private <T> String createStudioCell(DocumentHandle<T> handle, String owner, String column, int row) {
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var grid = studioGrid(handle, owner).orElseThrow(() -> rejection("missing_target"));
        var cell = grid.cells().stream().filter(c -> c.column().equals(column) && c.row() == row).findFirst().orElseThrow(() -> rejection("missing_target"));
        if (cell.node().isPresent()) throw rejection("missing_target");
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        UUID identity = UUID.randomUUID();
        handle.edit(handle.view().token(), text("object_created"), value -> {
            T candidate = editor.createStudioCell(value, owner, column, row, identity);
            var created = editor.studioGrid(candidate, owner).orElseThrow(() -> rejection("invalid_presentation"));
            if (created.cells().stream().noneMatch(c -> c.column().equals(column) && c.row() == row && c.node().filter(identity.toString()::equals).isPresent())) throw rejection("invalid_presentation");
            return candidate;
        });
        return identity.toString();
    }

    public void selectNode(UUID document, String id) {
        owner();
        if (nodes(document).stream().noneMatch(node -> node.id().equals(id))) throw rejection("missing_target");
        if (id.equals(selectedNodes.get(document))) return;
        selectedNodes.put(document, id);
        if (document.equals(activeDocument)) selectedNode = id;
        version++;
    }

    public List<CreationChoice> creationChoices() {
        owner();
        var handle = active();
        if (!registry.defines(DocumentEditors.CREATORS)) return List.of();
        if (handle == null) {
            List<CreationChoice> result = new ArrayList<>();
            for (var entry : registry.entries(DocumentEditors.CREATORS).entrySet()) {
                var metadata = registry.metadata().contributions().get(new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(DocumentEditors.CREATORS.id(), entry.getKey()));
                result.add(new CreationChoice(entry.getKey(), metadata.owner(), entry.getValue().label(), entry.getValue().category(), ObjectCreator.Target.unavailable(text("no_document"))));
            }
            return List.copyOf(result);
        }
        return creationChoices(handle, selectedNode);
    }

    public List<CreationChoice> creationChoices(UUID document, String selection) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : creationChoices(handle, selection);
    }

    public List<CreationChoice> creationChoicesForReference(Address address) {
        owner();
        var handle = document(address.document());
        return handle == null ? List.of() : creationChoicesForReference(handle, address);
    }

    private <T> List<CreationChoice> creationChoicesForReference(DocumentHandle<T> handle, Address address) {
        if (referenceLists(handle, address.node()).stream().noneMatch(r -> r.property().equals(address.property()))) return List.of();
        return creationChoices(handle, address.node()).stream().filter(choice -> DocumentEditors.creator(registry, choice.id(), handle.typeId(), handle.valueType())
                .supportsReference(handle.view().content(), address.node(), address.property())).toList();
    }

    private <T> List<CreationChoice> creationChoices(DocumentHandle<T> handle, String selection) {
        List<CreationChoice> result = new ArrayList<>();
        for (var entry : registry.entries(DocumentEditors.CREATORS).entrySet()) {
            if (!entry.getValue().documentType().equals(handle.typeId())) continue;
            var creator = DocumentEditors.creator(registry, entry.getKey(), handle.typeId(), handle.valueType());
            var metadata = registry.metadata().contributions().get(new net.ptcrys.fpsmatch.editor.api.registry.ContributionKey(DocumentEditors.CREATORS.id(), entry.getKey()));
            result.add(new CreationChoice(entry.getKey(), metadata.owner(), creator.label(), creator.category(),
                    creator.target(handle.view().content(), Optional.ofNullable(selection))));
        }
        return List.copyOf(result);
    }

    public CreationDraft beginObjectCreation(EditorId id) {
        owner();
        requireWritable();
        var handle = active();
        if (handle == null) throw rejection("missing_target");
        objectCreation = beginObjectCreation(handle, selectedNode, id);
        version++;
        return objectCreation;
    }

    public CreationDraft beginObjectCreation(UUID document, String selection, EditorId id) {
        owner();
        requireWritable();
        var handle = document(document);
        if (handle == null) throw rejection("missing_target");
        objectCreation = beginObjectCreation(handle, selection, id);
        version++;
        return objectCreation;
    }

    private <T> CreationDraft beginObjectCreation(DocumentHandle<T> handle, String selection, EditorId id) {
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var creator = DocumentEditors.creator(registry, id, handle.typeId(), handle.valueType());
        var target = creator.target(handle.view().content(), Optional.ofNullable(selection));
        String parent = target.parent().orElseThrow(() -> rejection("missing_target"));
        if (nodes(handle).stream().noneMatch(n -> n.id().equals(parent))) throw rejection("invalid_presentation");
        List<DocumentEditor.Field> fields = List.copyOf(creator.fields(handle.view().content(), parent));
        if (fields.size() > 256 || fields.stream().map(DocumentEditor.Field::property).distinct().count() != fields.size()) throw rejection("invalid_presentation");
        return new CreationDraft(handle.id(), id, parent, UUID.randomUUID(), handle.view().token(), fields);
    }

    public void cancelObjectCreation() {
        owner();
        objectCreation = null;
        version++;
    }

    public void createObject(CreationDraft draft, Map<String, String> raw) {
        createObject(draft, raw, null);
    }

    /** Creates a definition and appends its reference as one document command and one undo step. */
    public void createObject(CreationDraft draft, Map<String, String> raw, Address attachTo) {
        owner();
        requireWritable();
        if (objectCreation != draft) throw rejection("missing_target");
        var handle = document(draft.document());
        if (handle == null) throw rejection("missing_target");
        Set<String> properties = new HashSet<>();
        draft.fields().forEach(f -> properties.add(f.property()));
        if (!properties.equals(raw.keySet()) || raw.values().stream().anyMatch(v -> v == null || v.length() > 4096)) throw rejection("invalid_presentation");
        for (var field : draft.fields()) if (field.kind() == DocumentEditor.Kind.CHOICE && field.choices().stream().noneMatch(c -> c.raw().equals(raw.get(field.property())))) throw rejection("invalid_choice");
        if (attachTo != null && !attachTo.document().equals(draft.document())) throw rejection("missing_target");
        createObject(handle, draft, Map.copyOf(raw), attachTo);
        objectCreation = null;
        String created = draft.identity().toString();
        selectedNodes.put(draft.document(), created);
        if (draft.document().equals(activeDocument)) selectedNode = created;
        status = text("object_created");
        problems = List.of();
        clearTechnicalFailure();
        version++;
    }

    private <T> void createObject(DocumentHandle<T> handle, CreationDraft draft, Map<String, String> raw, Address attachTo) {
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var creator = DocumentEditors.creator(registry, draft.creator(), handle.typeId(), handle.valueType());
        handle.edit(draft.expected(), creator.label(), value -> {
            if (attachTo != null && !creator.supportsReference(value, attachTo.node(), attachTo.property())) throw rejection("missing_target");
            T candidate = creator.create(value, draft.parent(), draft.identity(), raw);
            // A creator must expose exactly its captured identity at the captured parent.
            var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
            List<DocumentEditor.Node> nodes = editor.nodes(candidate);
            if (nodes.size() > 8192 || nodes.stream().filter(n -> n.id().equals(draft.identity().toString()) && n.parent().filter(draft.parent()::equals).isPresent()).count() != 1)
                throw rejection("invalid_presentation");
            if (attachTo != null) {
                var list = editor.referenceLists(candidate, attachTo.node()).stream().filter(r -> r.property().equals(attachTo.property())).findFirst().orElseThrow(() -> rejection("missing_target"));
                var selected = new ArrayList<>(list.selected());
                selected.add(draft.identity().toString());
                new DocumentEditor.ReferenceList(list.property(), list.label(), list.unit(), list.candidates(), selected);
                candidate = editor.replaceReferences(candidate, attachTo.node(), attachTo.property(), selected);
            }
            return candidate;
        });
    }

    private <T> List<DocumentEditor.Node> nodes(DocumentHandle<T> handle) {
        var editor = editor(handle);
        if (editor.isEmpty()) return List.of();
        List<DocumentEditor.Node> nodes = List.copyOf(editor.get().nodes(handle.view().content()));
        if (nodes.size() > 8192 || nodes.stream().map(DocumentEditor.Node::id).distinct().count() != nodes.size()) throw rejection("invalid_presentation");
        return nodes;
    }

    public RemovalDraft beginRemoval() {
        owner();
        return beginRemoval(activeDocument, selectedNode);
    }

    public RemovalDraft beginRemoval(UUID document, String node) {
        owner();
        requireWritable();
        var handle = document == null ? null : document(document);
        if (handle == null || node == null) throw rejection("missing_target");
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var target = nodes(handle).stream().filter(n -> n.id().equals(node)).findFirst().orElseThrow(() -> rejection("missing_target"));
        var plan = removalPlan(handle, target.id());
        removalDraft = new RemovalDraft(handle.id(), target, handle.view().token(), plan);
        version++;
        return removalDraft;
    }

    private <T> DocumentEditor.RemovalPlan removalPlan(DocumentHandle<T> handle, String node) {
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        return removalPlan(editor, handle.view().content(), node);
    }

    private <T> DocumentEditor.RemovalPlan removalPlan(DocumentEditor<T> editor, T value, String node) {
        var plan = editor.planRemoval(value, node).orElseGet(() -> new DocumentEditor.RemovalPlan(List.of(), List.of(), Optional.of(text("delete_unavailable"))));
        validateRemovalPlan(List.copyOf(editor.nodes(value)), node, plan);
        return plan;
    }

    private void validateRemovalPlan(List<DocumentEditor.Node> nodes, String target, DocumentEditor.RemovalPlan plan) {
        new ObjectHierarchy(nodes);
        var byId = new HashMap<String, DocumentEditor.Node>();
        var children = new HashMap<String, List<String>>();
        for (var node : nodes) {
            byId.put(node.id(), node);
            node.parent().ifPresent(p -> children.computeIfAbsent(p, k -> new ArrayList<>()).add(node.id()));
        }
        if (!byId.containsKey(target)) throw rejection("missing_target");
        var removed = new HashSet<String>();
        for (var node : plan.objects()) if (!node.equals(byId.get(node.id())) || !removed.add(node.id())) throw rejection("invalid_presentation");
        for (var ref : plan.references()) if (!ref.source().equals(byId.get(ref.source().id())) || !ref.target().equals(byId.get(ref.target().id()))) throw rejection("invalid_presentation");
        if (plan.unavailable().isEmpty()) {
            var subtree = new HashSet<String>();
            var queue = new ArrayDeque<String>();
            queue.add(target);
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                subtree.add(id);
                queue.addAll(children.getOrDefault(id, List.of()));
            }
            if (!subtree.equals(removed)) throw rejection("invalid_presentation");
        }
    }

    public void cancelRemoval() {
        owner();
        removalDraft = null;
        version++;
    }

    public void removeObject(RemovalDraft draft) {
        owner();
        requireWritable();
        if (removalDraft != draft) throw rejection("missing_target");
        var handle = document(draft.document());
        if (handle == null) throw rejection("missing_target");
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        removeObject(handle, draft);
        removalDraft = null;
        String remaining = draft.target().parent().orElse(null);
        if (remaining == null) selectedNodes.remove(draft.document());
        else selectedNodes.put(draft.document(), remaining);
        if (draft.document().equals(activeDocument)) {
            selectedNode = remaining;
            reconcileSelection();
        }
        status = text("object_deleted");
        problems = List.of();
        clearTechnicalFailure();
        version++;
    }

    private <T> void removeObject(DocumentHandle<T> handle, RemovalDraft draft) {
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        handle.edit(draft.expected(), text("delete_object"), value -> {
            var originalNodes = List.copyOf(editor.nodes(value));
            var current = removalPlan(editor, value, draft.target().id());
            if (!current.equals(draft.plan())) throw rejection("stale_removal");
            if (!current.allowed()) throw rejection("delete_blocked");
            var remaining = new HashMap<String, Optional<String>>();
            Set<String> removed = current.objects().stream().map(DocumentEditor.Node::id).collect(java.util.stream.Collectors.toSet());
            for (var node : originalNodes) if (!removed.contains(node.id())) remaining.put(node.id(), node.parent());
            T candidate = editor.remove(value, draft.target().id());
            var actual = List.copyOf(editor.nodes(candidate));
            new ObjectHierarchy(actual);
            if (actual.size() != remaining.size() || actual.stream().anyMatch(node -> !node.parent().equals(remaining.get(node.id())))) throw rejection("invalid_presentation");
            return candidate;
        });
    }

    public List<DocumentEditor.Field> fields() {
        owner();
        var handle = active();
        if (handle == null || selectedNode == null) return List.of();
        return fields(handle, selectedNode);
    }

    public List<DocumentEditor.Field> fields(UUID document, String node) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : fields(handle, node);
    }

    private <T> List<DocumentEditor.Field> fields(DocumentHandle<T> handle, String node) {
        var editor = editor(handle);
        if (editor.isEmpty()) return List.of();
        List<DocumentEditor.Field> fields = List.copyOf(editor.get().fields(handle.view().content(), node));
        if (fields.size() > 256 || fields.stream().map(DocumentEditor.Field::property).distinct().count() != fields.size()) throw rejection("invalid_presentation");
        InspectorPresentation.pages(fields);
        return fields;
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<DocumentEditor<T>> editor(DocumentHandle<T> handle) {
        if (!editors.containsKey(handle.id())) DocumentEditors.find(registry, handle.typeId(), handle.valueType(), session.editorResources()).ifPresent(editor -> editors.put(handle.id(), editor));
        return Optional.ofNullable((DocumentEditor<T>) editors.get(handle.id()));
    }

    public List<DocumentEditor.ReferenceList> referenceLists() {
        owner();
        var handle = active();
        return handle == null || selectedNode == null ? List.of() : referenceLists(handle, selectedNode);
    }

    public List<DocumentEditor.ReferenceList> referenceLists(UUID document, String node) {
        owner();
        var handle = document(document);
        return handle == null ? List.of() : referenceLists(handle, node);
    }

    private <T> List<DocumentEditor.ReferenceList> referenceLists(DocumentHandle<T> handle, String node) {
        var editor = editor(handle);
        if (editor.isEmpty()) return List.of();
        var lists = List.copyOf(editor.get().referenceLists(handle.view().content(), node));
        if (lists.size() > 256 || lists.stream().map(DocumentEditor.ReferenceList::property).distinct().count() != lists.size()) throw rejection("invalid_presentation");
        return lists;
    }

    public ReferenceDraft beginReferences(Address address) {
        owner();
        requireWritable();
        var handle = document(address.document());
        if (handle == null) throw rejection("missing_target");
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var list = referenceLists(handle, address.node()).stream().filter(r -> r.property().equals(address.property()))
                .findFirst().orElseThrow(() -> rejection("missing_target"));
        referenceDraft = new ReferenceDraft(address, handle.view().token(), list);
        version++;
        return referenceDraft;
    }

    public void cancelReferences() {
        owner();
        referenceDraft = null;
        version++;
    }

    public void replaceReferences(ReferenceDraft draft, List<String> selected) {
        owner();
        requireWritable();
        if (referenceDraft != draft) throw rejection("missing_target");
        var handle = document(draft.address().document());
        if (handle == null) throw rejection("missing_target");
        replaceReferences(handle, draft, List.copyOf(selected));
        referenceDraft = null;
        status = text("edited");
        problems = List.of();
        clearTechnicalFailure();
        version++;
    }

    private <T> void replaceReferences(DocumentHandle<T> handle, ReferenceDraft draft, List<String> selected) {
        if (!handle.view().pendingInputs().isEmpty()) throw rejection("pending_inputs");
        var address = draft.address();
        var current = referenceLists(handle, address.node()).stream().filter(r -> r.property().equals(address.property()))
                .findFirst().orElseThrow(() -> rejection("missing_target"));
        // Validate against today's catalog, then use the exact captured revision. Lists never rebase.
        new DocumentEditor.ReferenceList(current.property(), current.label(), current.unit(), current.candidates(), selected);
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        handle.edit(draft.expected(), current.label(), value -> editor.replaceReferences(value, address.node(), address.property(), selected));
    }

    public String raw(Address address, String fallback) {
        owner();
        Draft input = inputs.get(address);
        return input == null ? fallback : input.raw;
    }

    public boolean pending(Address address) {
        owner();
        return inputs.containsKey(address);
    }

    public void input(Address address, String raw) {
        owner();
        requireWritable();
        DocumentHandle<?> handle = document(address.document);
        if (handle == null || raw.length() > 4096) throw rejection("missing_target");
        requireEnabled(handle, address);
        Draft prior = inputs.get(address);
        String baseline = prior == null ? field(handle, address).raw() : prior.baseline;
        handle.setPendingInput(address.pendingKey(), raw);
        inputs.put(address, new Draft(prior == null ? handle.view().token() : prior.expected, raw, baseline));
        // Raw input does not rebuild native controls or move keyboard focus.
    }

    public void discardInput(Address address) {
        owner();
        DocumentHandle<?> handle = document(address.document);
        if (handle != null) handle.clearPendingInput(address.pendingKey());
        inputs.remove(address);
        if (failureInputs.contains(address) && failureInputs.stream().noneMatch(inputs::containsKey)) {
            problems = List.of();
            status = text("ready");
            clearTechnicalFailure();
        }
        version++;
    }

    public void apply(Address address) {
        owner();
        requireWritable();
        var handle = document(address.document);
        if (handle == null) throw rejection("missing_target");
        apply(handle, address);
    }

    /** Applies an explicit compact row as one document command, with no partially published axes. */
    public void applyFields(List<Address> addresses) {
        owner();
        requireWritable();
        var fixed = List.copyOf(addresses);
        if (fixed.isEmpty() || fixed.size() > 256 || fixed.stream().distinct().count() != fixed.size()) throw rejection("invalid_presentation");
        Address first = fixed.get(0);
        if (fixed.stream().anyMatch(a -> !a.document().equals(first.document()) || !a.node().equals(first.node()))) throw rejection("missing_target");
        var handle = document(first.document());
        if (handle == null) throw rejection("missing_target");
        applyFields(handle, fixed);
    }

    private <T> void applyFields(DocumentHandle<T> handle, List<Address> addresses) {
        List<Address> pending = addresses.stream().filter(inputs::containsKey).toList();
        if (pending.isEmpty()) return;
        if (pending.size() == 1) {
            apply(handle, pending.get(0));
            return;
        }
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        DocumentToken expected = handle.view().token();
        Map<Address, String> raw = new LinkedHashMap<>();
        for (Address address : pending) {
            requireEnabled(handle, address);
            Draft draft = inputs.get(address);
            var field = field(handle, address);
            if (field.kind() == DocumentEditor.Kind.CHOICE) throw rejection("choice_pending_inputs");
            if (!draft.expected.equals(expected) && !draft.baseline.equals(field.raw())) throw rejection("stale_input");
            raw.put(address, draft.raw);
        }
        for (Address other : inputs.keySet()) if (other.document().equals(pending.get(0).document()) && other.node().equals(pending.get(0).node()) && field(handle, other).kind() == DocumentEditor.Kind.CHOICE) throw rejection("choice_pending_inputs");
        handle.edit(expected, new TextContent.Message("editor.workbench.edit_fields", List.of(new TextContent.NumberArgument(java.math.BigDecimal.valueOf(pending.size())))), value -> {
            T candidate = value;
            for (var entry : raw.entrySet()) candidate = editor.apply(candidate, entry.getKey().node(), entry.getKey().property(), entry.getValue());
            return candidate;
        });
        pending.forEach(this::discardInput);
        status = text("edited");
        problems = List.of();
        clearTechnicalFailure();
    }

    private <T> void apply(DocumentHandle<T> handle, Address address) {
        Draft input = inputs.get(address);
        if (input == null) return;
        var editor = editor(handle).orElseThrow(() -> rejection("missing_editor"));
        requireEnabled(handle, address);
        var currentField = field(handle, address);
        // A choice can replace the selected object's payload and remove or reinterpret scalar
        // fields. Resolve sibling drafts first; otherwise an old count/damage could become hidden
        // or be rebased onto a different item with the same numeric baseline.
        for (Address sibling : inputs.keySet()) {
            if (sibling.equals(address) || !sibling.document().equals(address.document()) || !sibling.node().equals(address.node())) continue;
            if (currentField.kind() == DocumentEditor.Kind.CHOICE || field(handle, sibling).kind() == DocumentEditor.Kind.CHOICE)
                throw rejection("choice_pending_inputs");
        }
        if (currentField.kind() == DocumentEditor.Kind.CHOICE && currentField.choices().stream().noneMatch(choice -> choice.raw().equals(input.raw)))
            throw rejection("invalid_choice");
        DocumentToken expected = input.expected;
        // Another field may have been committed while this raw input was retained. Rebase only
        // when the same stable field still exists and its actual value equals the captured baseline.
        if (!expected.equals(handle.view().token()) && field(handle, address).raw().equals(input.baseline))
            expected = handle.view().token();
        handle.edit(expected, new TextContent.Message("editor.workbench.edit_field", List.of(new TextContent.LiteralArgument(address.property))),
                value -> editor.apply(value, address.node, address.property, input.raw));
        discardInput(address);
        status = text("edited");
        problems = List.of();
        clearTechnicalFailure();
    }

    private void requireEnabled(DocumentHandle<?> handle, Address address) {
        var all = fields(handle, address.node());
        var value = all.stream().filter(f -> f.property().equals(address.property())).findFirst().orElseThrow(() -> rejection("missing_target"));
        value.presentation().flatMap(DocumentEditor.Presentation::dependency).ifPresent(dependency -> {
            if (all.stream().noneMatch(f -> f.property().equals(dependency.property()) && f.raw().equals(dependency.expectedRaw()))) throw rejection("field_dependency_disabled");
        });
    }

    private DocumentEditor.Field field(DocumentHandle<?> handle, Address address) {
        return fields(handle, address.node).stream().filter(field -> field.property().equals(address.property))
                .findFirst().orElseThrow(() -> rejection("missing_target"));
    }

    public void undo() {
        owner();
        requireWritable();
        if (!canUndo()) throw rejection("pending_inputs");
        var h = active();
        h.undo(h.view().token());
        reconcileSelection();
        version++;
    }

    public void redo() {
        owner();
        requireWritable();
        if (!canRedo()) throw rejection("pending_inputs");
        var h = active();
        h.redo(h.view().token());
        reconcileSelection();
        version++;
    }

    private void reconcileSelection() {
        List<DocumentEditor.Node> nodes = nodes();
        if (selectedNode != null && nodes.stream().noneMatch(n -> n.id().equals(selectedNode))) {
            selectedNode = nodes.isEmpty() ? null : nodes.get(0).id();
            if (selectedNode == null) selectedNodes.remove(activeDocument);
            else selectedNodes.put(activeDocument, selectedNode);
        }
    }

    public void createDocument(EditorId type, String name) {
        owner();
        requireIdle();
        requireWritable();
        ActivatedDocumentType<?> descriptor = documentTypes().get(type);
        if (descriptor == null) throw rejection("missing_target");
        createDocument(descriptor, name);
    }

    private <T> void createDocument(ActivatedDocumentType<T> type, String name) {
        if (name.isBlank()) throw rejection("invalid_name");
        DocumentHandle<T> handle = session.create(UUID.randomUUID(), type.id(), type.valueType(), name);
        activeDocument = handle.id();
        selectedNode = null;
        status = text("document_created");
        version++;
    }

    public void saveAll() {
        owner();
        requireIdle();
        requireWritable();
        if (!inputs.isEmpty()) throw rejection("pending_inputs");
        saveCapture(session.captureSave(session.documents().keySet(), true), null);
    }

    /** Saves the addressed document without silently publishing other drafts or resource changes. */
    public DocumentSaveRequest saveDocument(UUID document) {
        owner();
        requireIdle();
        requireWritable();
        if (document(document) == null) throw rejection("missing_target");
        if (documentHasPendingInputs(document)) throw rejection("pending_inputs");
        var capture = session.captureSave(Set.of(document), false);
        var request = new DocumentSaveRequest(UUID.randomUUID(), document);
        lastDocumentSave = request;
        lastDocumentSaveState = DocumentSaveState.PREPARING;
        saveCapture(capture, request);
        return request;
    }

    private void saveCapture(ProjectSaveCapture capture, DocumentSaveRequest request) {
        ProjectSession fixedSession = session;
        LocalProjectAccess fixedAccess = access;
        Pending operation = start("preparing", true);
        operation.documentSave = request;
        submit(operation, () -> fixedAccess.prepareValidated(capture, operation.cancelled::get), prepared -> {
            if (operation.cancelled.get()) {
                finish(operation, text("cancelled"), List.of());
                return;
            }
            try {
                fixedSession.enqueueSave(prepared);
                var dispatch = fixedSession.dispatchSave().orElseThrow();
                if (dispatch.job().isEmpty()) {
                    finish(operation, text("failed"), dispatch.problems());
                    return;
                }
                ProjectSaveJob job = dispatch.job().get();
                uncertainJob = job;
                uncertainDocumentSave = request;
                // From this point cancellation cannot revoke the submitted publication.
                pending = new Pending(false, "publishing");
                Pending publishing = pending;
                status = text("publishing");
                version++;
                publishing.documentSave = request;
                if (request != null && request.equals(lastDocumentSave)) lastDocumentSaveState = DocumentSaveState.PUBLISHING;
                submit(publishing, () -> fixedAccess.publish(job), result -> completeSave(publishing, fixedSession, job, result));
            } catch (Throwable failure) {
                fail(operation, failure);
            }
        });
    }

    public void reconcile() {
        owner();
        requireIdle();
        if (creation != null) {
            reconcileCreation();
            return;
        }
        if (uncertainJob == null) throw rejection("missing_target");
        ProjectSession fixed = session;
        LocalProjectAccess fixedAccess = access;
        ProjectSaveJob job = uncertainJob;
        Pending operation = start("reconciling", false);
        operation.documentSave = uncertainDocumentSave;
        submit(operation, () -> fixedAccess.reconcile(job), result -> completeSave(operation, fixed, job, result));
    }

    private void reconcileCreation() {
        Creation fixed = creation;
        Pending operation = start("reconciling", false);
        submit(operation, () -> fixed.store.lookup(fixed.request), receipt -> {
            creationOutcomeKnown = receipt.status() == LocalProjectStore.RequestStatus.PUBLISHED;
            if (receipt.status() == LocalProjectStore.RequestStatus.PUBLISHED && fixed.request.equals(receipt.currentCommitId()) && fixed.state.equals(receipt.saved().state())) {
                // Lookup intentionally does not advance a store's captured baseline. Only after
                // resolving the original request may we reopen and verify the exact publication.
                submit(operation, () -> {
                    fixed.store.close();
                    LocalProjectStore reopened = LocalProjectStore.open(fixed.directory);
                    try {
                        var projectAccess = new LocalProjectAccess(reopened);
                        LoadedProject loaded = projectAccess.readValidated(registry, operation.cancelled::get);
                        if (!loaded.saved().commitId().equals(fixed.request) || !loaded.saved().state().equals(fixed.state))
                            throw rejection("source_changed");
                        return new Opened(fixed.directory, reopened, projectAccess, loaded, null);
                    } catch (Throwable failure) {
                        try {
                            reopened.close();
                        } catch (Throwable cleanup) {
                            if (cleanup != failure) failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                }, opened -> {
                    creation = null;
                    creationOutcomeKnown = false;
                    directory = null;
                    adopt(operation, opened);
                });
            } else {
                finish(operation, text(creationOutcomeKnown ? "conflict" : "unknown"), List.of());
            }
        });
    }

    private void completeSave(Pending operation, ProjectSession fixed, ProjectSaveJob job, SaveResult result) {
        try {
            if (session != fixed || !fixed.completeSave(job, result)) throw rejection("missing_target");
            uncertainJob = result.outcome() == SaveResult.Outcome.UNKNOWN ? job : null;
            if (uncertainJob == null) uncertainDocumentSave = null;
            finish(operation, text(switch (result.outcome()) {
                case CONFIRMED -> "saved";
                case UNKNOWN -> "unknown";
                case CONFLICT -> "conflict";
                case REJECTED -> "failed";
            }), result.problems());
        } catch (Throwable failure) {
            // A reporter failure after publication must retain the same original request.
            uncertainJob = job;
            uncertainDocumentSave = operation.documentSave;
            fail(operation, failure);
        }
    }

    public void inspectResourceImport(EditorId id, Map<String, Path> selected) {
        owner();
        requireIdle();
        requireWritable();
        if (resourcePreview != null) throw rejection("resource_preview_open");
        var importer = resourceImporters().get(id);
        if (importer == null) throw rejection("missing_target");
        ResourceImporters.require(registry, id, importer);
        var specs = List.copyOf(importer.inputs());
        if (specs.isEmpty() || specs.size() > 16 || specs.stream().map(ResourceImporter.Input::id).distinct().count() != specs.size() || !new HashSet<>(specs.stream().map(ResourceImporter.Input::id).toList()).equals(selected.keySet())) throw rejection("missing_input");
        var paths = Map.copyOf(selected);
        var target = session;
        var current = target.resources().orElseThrow().view();
        UUID seed = UUID.randomUUID();
        Pending operation = start("resource_inspecting", true);
        submit(operation, () -> ResourceImportCapture.inspect(registry, importer, specs, paths, current.content(), seed, operation.cancelled::get), capture -> {
            if (operation.cancelled.get() || session != target) {
                discardCapture(operation, capture);
                return;
            }
            resourcePreview = new ResourcePreview(target, current.token(), importer.label(), capture);
            finish(operation, text("resource_import_ready"), List.of());
        }, capture -> capture.close());
    }

    public boolean resourceImportReady() {
        owner();
        return resourcePreview != null;
    }

    public List<TextContent> resourceImportSummary() {
        owner();
        return resourcePreview == null ? List.of() : resourcePreview.capture.summary;
    }

    public void cancelResourceImport() {
        owner();
        if (pending != null) {
            if (pending.operation.equals("resource_inspecting") || pending.operation.equals("resource_staging")) cancel();
            return;
        }
        if (resourcePreview == null) {
            dismissImportFailure("resource_inspecting", "resource_staging");
            return;
        }
        var abandoned = resourcePreview;
        resourcePreview = null;
        Pending operation = new Pending(false, "cancelling");
        pending = operation;
        if (failureDiagnostic == null) status = text("cancelling");
        version++;
        discardCapture(operation, abandoned.capture);
    }

    private void discardCapture(Pending operation, ResourceImportCapture capture) {
        operation.releasingCapture = true;
        submit(operation, () -> {
            capture.close();
            return Boolean.TRUE;
        }, ignored -> finishImportCancellation(operation, "resource_inspecting", "resource_staging"), ignored -> {}, () -> {
            capture.close();
            return null;
        });
    }

    public void acceptResourceImport() {
        owner();
        requireIdle();
        requireWritable();
        var fixed = resourcePreview;
        if (fixed == null || session != fixed.session) throw rejection("missing_target");
        var handle = session.resources().orElseThrow();
        if (!handle.view().token().equals(fixed.expected)) throw rejection("source_changed");
        ResourceCatalog candidate = fixed.capture.candidate;
        LocalProjectStore fixedStore = store;
        resourcePreview = null;
        Pending operation = start("resource_staging", true);
        submit(operation, () -> {
            try (var capture = fixed.capture) {
                capture.stage(fixedStore, operation.cancelled::get);
            }
            return candidate;
        }, result -> {
            if (operation.cancelled.get() || session != fixed.session) {
                finishImportCancellation(operation, "resource_staging");
                return;
            }
            try {
                handle.edit(fixed.expected, fixed.label, ignored -> result);
                finish(operation, text("resource_imported"), List.of());
            } catch (Throwable failure) {
                fail(operation, failure);
            }
        }, ignored -> {}, () -> {
            fixed.capture.close();
            return null;
        });
    }

    public void undoResources() {
        owner();
        requireIdle();
        requireWritable();
        var h = session.resources().orElseThrow();
        h.undo(h.view().token());
        version++;
    }

    public void redoResources() {
        owner();
        requireIdle();
        requireWritable();
        var h = session.resources().orElseThrow();
        h.redo(h.view().token());
        version++;
    }

    public void inspectImport(EditorId importerId, Map<String, Path> selected) {
        owner();
        requireIdle();
        requireWritable();
        var importer = importers().get(importerId);
        if (importer == null) throw rejection("missing_target");
        DocumentEditors.requireImporter(registry, importerId, importer);
        inspectImport(importer, Map.copyOf(selected));
    }

    private <T> void inspectImport(DocumentImporter<T> importer, Map<String, Path> selected) {
        ProjectSession target = session;
        UUID seed = UUID.randomUUID();
        List<DocumentImporter.Input> specs = List.copyOf(importer.inputs());
        if (specs.size() > 16 || specs.stream().map(DocumentImporter.Input::id).distinct().count() != specs.size()) throw rejection("invalid_presentation");
        preview = null;
        Pending operation = start("inspecting", true);
        submit(operation, () -> {
            Map<String, byte[]> sources = readInputs(specs, selected, operation);
            operation.check();
            var converted = importer.convert(registry, sources, seed);
            operation.check();
            converted.value().ifPresent(value -> importer.valueType().cast(value));
            return new Preview<>(target, importer, sources, seed, converted);
        }, result -> {
            if (operation.cancelled.get() || session != target) {
                finishImportCancellation(operation, "inspecting");
                return;
            }
            preview = result;
            finish(operation, text(result.result.value().isPresent() ? "import_ready" : "failed"), result.result.problems());
        });
    }

    public boolean importReady() {
        owner();
        return preview != null && preview.result.value().isPresent();
    }

    /** Compatibility diagnostics belong to the fixed preview, independently of later UI failures. */
    public List<Problem> importProblems() {
        owner();
        return preview == null ? List.of() : preview.result.problems();
    }

    public void cancelImport() {
        owner();
        if (pending != null) {
            if (pending.operation.equals("inspecting")) cancel();
            return;
        }
        boolean hadPreview = preview != null;
        preview = null;
        if (!dismissImportFailure("inspecting", "import_accepting") && hadPreview && failureDiagnostic == null) {
            status = text("cancelled");
            problems = List.of();
        }
        version++;
    }

    /** Leaving one import flow owns only that flow's current diagnostic, never another task. */
    private boolean dismissImportFailure(String... operations) {
        if (pending != null || failureDiagnostic == null || !List.of(operations).contains(failureDiagnostic.operation())) return false;
        status = text("cancelled");
        problems = List.of();
        clearTechnicalFailure();
        version++;
        return true;
    }

    private void finishImportCancellation(Pending operation, String... scopes) {
        owner();
        if (pending != operation) return;
        pending = null;
        if (failureDiagnostic == null || List.of(scopes).contains(failureDiagnostic.operation())) {
            status = text("cancelled");
            problems = List.of();
            clearTechnicalFailure();
        }
        version++;
    }

    public void acceptImport(String name) {
        owner();
        requireIdle();
        requireWritable();
        if (!importReady()) throw rejection("missing_target");
        accept(preview, name);
    }

    private <T> void accept(Preview<T> fixed, String name) {
        if (fixed.session != session) throw rejection("missing_target");
        if (name.isBlank()) throw rejection("invalid_name");
        var handle = session.createWithValue(UUID.randomUUID(), fixed.importer.documentType(), fixed.importer.valueType(), name, fixed.result.value().orElseThrow());
        activeDocument = handle.id();
        selectedNode = null;
        preview = null;
        status = text("imported");
        version++;
        if (failureDiagnostic == null || Set.of("inspecting", "import_accepting").contains(failureDiagnostic.operation())) {
            problems = List.of();
            clearTechnicalFailure();
        }
    }

    private static Map<String, byte[]> readInputs(List<DocumentImporter.Input> specs, Map<String, Path> selected, Pending operation) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        long[] total = { 0 };
        for (var spec : specs) {
            operation.check();
            Path path = selected.get(spec.id());
            if (path == null) {
                if (spec.required()) throw rejection("missing_input");
                else continue;
            }
            if (!path.isAbsolute()) throw rejection("invalid_path");
            if (!spec.directory()) readInput(result, spec.id(), path, total, operation);
            else {
                if (!inputAttributes(path).isDirectory()) throw rejection("invalid_path");
                try (var files = Files.newDirectoryStream(path)) {
                    for (Path file : files) {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw rejection("invalid_path");
                        readInput(result, spec.id() + "/" + file.getFileName(), file, total, operation);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void readInput(Map<String, byte[]> result, String id, Path path, long[] total, Pending operation) throws IOException {
        operation.check();
        var attributes = inputAttributes(path);
        if (!attributes.isRegularFile()) throw rejection("import_file_type");
        if (result.size() >= 8192) throw rejection("import_budget");
        long size = attributes.size();
        if (size > 8L * 1024 * 1024 || size > 64L * 1024 * 1024 - total[0]) throw rejection("import_budget");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(8 * 1024 * 1024 + 1);
        }
        if (bytes.length != size || bytes.length > 8 * 1024 * 1024) throw rejection("source_changed");
        total[0] += bytes.length;
        result.put(id, bytes);
        operation.check();
    }

    private static BasicFileAttributes inputAttributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw rejection("import_missing_file");
        }
    }

    private DocumentHandle<?> document(UUID document) {
        owner();
        Objects.requireNonNull(document, "document");
        return session == null ? null : session.find(document).orElse(null);
    }

    private DocumentHandle<?> active() {
        return activeDocument == null ? null : document(activeDocument);
    }

    private void requireIdle() {
        if (pending != null || runtimeSource != null) throw rejection("busy");
    }

    private void requireWritable() {
        if (session == null) throw rejection("missing_project");
        if (protectedMode() || uncertainJob != null) throw rejection("protected");
    }

    private Pending start(String key, boolean cancellable) {
        Pending operation = new Pending(cancellable, key);
        pending = operation;
        status = text(key);
        problems = List.of();
        clearTechnicalFailure();
        version++;
        return operation;
    }

    @FunctionalInterface
    private interface Work<T> {

        T run() throws Throwable;
    }

    private <T> void submit(Pending operation, Work<T> work, java.util.function.Consumer<T> accepted) {
        submit(operation, work, accepted, ignored -> {});
    }

    @FunctionalInterface
    private interface Cleanup<T> {

        void close(T value) throws Throwable;
    }

    private <T> void submit(Pending operation, Work<T> work, java.util.function.Consumer<T> accepted, Cleanup<T> abandoned) {
        submit(operation, work, accepted, abandoned, () -> null);
    }

    private <T> void submit(Pending operation, Work<T> work, java.util.function.Consumer<T> accepted, Cleanup<T> abandoned, Work<?> rejected) {
        try {
            io.execute(() -> {
                try {
                    T result = work.run();
                    try {
                        completion.execute(() -> {
                            owner();
                            if (pending == operation) accepted.accept(result);
                            else io.execute(() -> {
                                try {
                                    abandoned.close(result);
                                } catch (Throwable failure) {
                                    completion.execute(() -> report(failure));
                                }
                            });
                        });
                    } catch (Throwable failure) {
                        try {
                            abandoned.close(result);
                        } catch (Throwable cleanup) {
                            if (cleanup != failure) failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                } catch (Throwable failure) {
                    completion.execute(() -> fail(operation, failure));
                }
            });
        } catch (Throwable failure) {
            // An executor that refuses work cannot own its capture. Release it here as a last resort.
            try {
                rejected.run();
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            fail(operation, failure);
        }
    }

    private void finish(Pending operation, TextContent result, List<Problem> diagnostics) {
        owner();
        if (pending != operation) return;
        if (operation.documentSave != null && operation.documentSave.equals(lastDocumentSave))
            lastDocumentSaveState = result.equals(text("saved")) ? DocumentSaveState.CONFIRMED : uncertainJob != null || result.equals(text("unknown")) ? DocumentSaveState.UNKNOWN : result.equals(text("cancelled")) ? DocumentSaveState.CANCELLED : DocumentSaveState.FAILED;
        pending = null;
        status = result;
        problems = List.copyOf(diagnostics);
        clearTechnicalFailure();
        version++;
    }

    private void fail(Pending operation, Throwable failure) {
        owner();
        if (pending != operation) return;
        // A read-only inspection can fail before its queued callback observes cancellation.
        // Cleanup failures remain visible, including those suppressed by a cancellation exception.
        boolean cancelled = !operation.releasingCapture && failure.getSuppressed().length == 0 && (failure instanceof java.util.concurrent.CancellationException || failure instanceof net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException || operation.cancelled.get() && failure instanceof Exception && (operation.operation.equals("inspecting") || operation.operation.equals("resource_inspecting")));
        List<Problem> diagnostics = cancelled ? List.of() : failure instanceof SessionRejection rejected ? rejected.problems() : failure instanceof ResourceImportException rejected ? rejected.problems() : failure instanceof DocumentCodecException rejected ? rejected.problems() : failure instanceof ResourceImportCapture.InputException ? List.of(new Problem(Problem.Severity.ERROR,
                EditorId.parse("editor:workbench/resource_input"), "project", resourceFailureReason(operation.operation, failure))) : List.of(
                        new Problem(Problem.Severity.ERROR, EditorId.parse("editor:workbench/operation_failed"), "project",
                                new TextContent.Message("editor.workbench.operation_failed", List.of(new TextContent.LiteralArgument(failure instanceof ProjectStorageException storage ? storage.code().name() : failure.getClass().getSimpleName())))));
        if (cancelled && (operation.operation.equals("inspecting") || operation.operation.equals("resource_inspecting") || operation.operation.equals("resource_staging")))
            finishImportCancellation(operation, operation.operation);
        else finish(operation, text(cancelled ? "cancelled" : "failed"), diagnostics);
        if (!cancelled) recordFailure(operation.operation, failure);
    }

    public void report(Throwable failure) {
        report("action", failure);
    }

    /** Associate a failed compact row only with drafts that were actually submitted. */
    public void reportFieldFailure(List<Address> addresses, Throwable failure) {
        owner();
        var fixed = Set.copyOf(addresses);
        report("action", failure);
        failureInputs = fixed.stream().filter(inputs::containsKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Synchronous source-path validation uses the same diagnostic scope as its I/O worker. */
    public void reportResourceImportFailure(Throwable failure) {
        report("resource_inspecting", failure);
    }

    /** Synchronous document source validation shares the preflight worker's diagnostic ownership. */
    public void reportImportFailure(Throwable failure) {
        report("inspecting", failure);
    }

    /** A rejected adoption keeps its fixed preview available for correction and retry. */
    public void reportImportAcceptanceFailure(Throwable failure) {
        report("import_accepting", failure);
    }

    private void report(String operationName, Throwable failure) {
        owner();
        // A rejected UI action must not invalidate a worker or orphan its owned result.
        if (pending != null) {
            recordFailure(operationName, failure);
            problems = failure instanceof SessionRejection rejected ? rejected.problems() : List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("editor:workbench/action_failed"), "project", text("failed")));
            version++;
            return;
        }
        Pending operation = new Pending(false, operationName);
        pending = operation;
        fail(operation, failure);
    }

    private void clearTechnicalFailure() {
        technicalFailure = null;
        failureDiagnostic = null;
        failureInputs = Set.of();
    }

    private void recordFailure(String operation, Throwable failure) {
        failureInputs = Set.of();
        technicalFailure = Objects.requireNonNull(failure);
        String type = failure.getClass().getSimpleName();
        failureDiagnostic = new FailureDiagnostic(++failureSequence, operation, type.isEmpty() ? "Throwable" : type,
                resourceFailureReason(operation, failure));
        // A diagnostic sink cannot invalidate a completed edit or orphan a pending operation.
        try {
            failureReporter.report(failureDiagnostic, failure);
        } catch (RuntimeException ignored) { /* The original failure remains available to the owner. */ }
    }

    private static TextContent resourceFailureReason(String operation, Throwable failure) {
        if (operation.equals("inspecting") && failure instanceof InvalidPathException) return text("invalid_path");
        if (failure instanceof ResourceImportCapture.InputException input)
            return new TextContent.Message("editor.workbench.resource_failure_" + input.reason.name().toLowerCase(Locale.ROOT),
                    List.of(new TextContent.NestedArgument(input.label)));
        if (operation.equals("resource_inspecting")) {
            if (failure instanceof InvalidPathException) return text("resource_failure_path");
            if (failure instanceof SessionRejection rejected && rejected.problems().stream()
                    .anyMatch(problem -> problem.code().equals(EditorId.parse("editor:workbench/missing_input"))))
                return text("resource_failure_required");
        }
        return failureReason(failure);
    }

    /** Stable codes become author-facing reasons; raw exception messages remain in the host log. */
    private static TextContent failureReason(Throwable failure) {
        if (failure instanceof SessionRejection rejected) {
            for (String key : List.of("import_missing_file", "import_file_type", "import_budget", "invalid_path", "missing_input", "source_changed"))
                if (rejected.problems().stream().anyMatch(problem -> problem.code().equals(EditorId.parse("editor:workbench/" + key)))) return text(key);
        }
        if (failure instanceof ProjectStorageException storage) return text(switch (storage.code()) {
            case INVALID_FORMAT -> "failure_storage_format";
            case UNSUPPORTED_FORMAT -> "failure_storage_version";
            case LIMIT_EXCEEDED -> "failure_storage_limit";
            case CORRUPT_CONTENT -> "failure_storage_corrupt";
            case CONFLICT -> "failure_storage_conflict";
            case BUSY -> "failure_storage_busy";
            case INVALID_PATH -> "failure_storage_path";
            case MISSING_PROJECT -> "failure_storage_missing";
            case ATOMIC_MOVE_UNSUPPORTED -> "failure_storage_atomic";
            case RESULT_UNKNOWN -> "failure_storage_unknown";
            case REQUEST_MISMATCH -> "failure_storage_request";
            case CLOSED -> "failure_storage_closed";
        });
        if (failure instanceof AccessDeniedException || failure instanceof SecurityException)
            return text("failure_access");
        if (failure instanceof InvalidPathException) return text("failure_storage_path");
        if (failure instanceof IOException) return text("failure_io");
        return text("failure_unexpected");
    }

    public static TextContent.Message text(String key) {
        return new TextContent.Message("editor.workbench." + key, List.of());
    }

    private static SessionRejection rejection(String key) {
        return new SessionRejection(List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("editor:workbench/" + key), "project", text(key))));
    }
}
