package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentRejection;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Shared opening/install checks over decoded saved content, without owning drafts or I/O. */
final class ProjectContentChecks {

    private static final int MAX_DOCUMENT_DEPENDENCIES = 65536;
    private final LoadedProject source;
    private final DocumentTypeCatalog types;
    private final ExtensionRegistry registry;
    private final ResourceProducerCatalog producers;
    final List<Problem> problems;
    RuntimeException resourceFailure;
    private final UUID projectId;
    private final Roots roots = new Roots();
    private final Set<ResourceRef> resources = new TreeSet<>(Comparator
            .comparing(ResourceRef::resourceId).thenComparing(ResourceRef::versionId));
    private long dependencyCount;

    ProjectContentChecks(LoadedProject source, DocumentTypeCatalog types,
                         ExtensionRegistry registry, ResourceProducerCatalog producers) {
        this.source = source;
        this.types = types;
        this.registry = registry;
        this.producers = producers;
        problems = new ArrayList<>(source.problems());
        resourceFailure = source.resourceFailure().orElse(null);
        projectId = source.saved().state().projectId();
    }

    void addDocument(ProjectState.Entry entry, EditorId typeId, DocumentDependencies dependencies) {
        dependencyCount += (long) dependencies.documents().size() + dependencies.resources().size() + dependencies.contributions().size();
        if (dependencyCount > MAX_DOCUMENT_DEPENDENCIES) {
            throw SessionMessages.dependencyBudgetExceeded(dependencyCount, MAX_DOCUMENT_DEPENDENCIES);
        }
        Set<UUID> assets = new TreeSet<>();
        for (ResourceRef ref : dependencies.resources()) {
            assets.add(ref.resourceId());
            assets.add(ref.versionId());
            resources.add(ref);
        }
        if (!entry.documentDependencies().equals(dependencies.documents()) || !entry.assetDependencies().equals(assets)) {
            problems.add(RuntimeMessages.manifestMismatch(entry.id()));
        }
        roots.addOrReport(new ContributionKey(DocumentTypes.KIND.id(), typeId), problems);
        for (ContributionKey key : dependencies.contributions()) roots.addOrReport(key, problems);
    }

    void finish() {
        if (source.resources().isPresent() && !source.resources().orElseThrow().versions().isEmpty() && !source.validatedWith(registry)) {
            SessionRejection required = ValidationMessages.required(projectId);
            problems.addAll(required.problems());
            resourceFailure(required);
        }
        inspectResources();
        inspectModules();
    }

    void documentFailure(ProjectState.Entry entry, RuntimeException failure) {
        problems.add(RuntimeMessages.documentUnavailable(entry.id(), entry.typeId()));
        appendProblems(problems, failure);
    }

    static ActivatedDocumentType<?> requireType(DocumentTypeCatalog types, EditorId typeId) {
        ActivatedDocumentType<?> type = types.find(typeId).orElse(null);
        if (type == null) {
            RuntimeException failure = types.activationFailures().get(typeId);
            if (failure != null) throw failure;
            throw new SessionRejection(List.of(RuntimeMessages.typeUnavailable(typeId)));
        }
        return type;
    }

    private void resourceFailure(RuntimeException failure) {
        if (resourceFailure == null) {
            resourceFailure = failure;
            problems.add(RuntimeMessages.resourceUnavailable(projectId));
        }
    }

    private void inspectResources() {
        if (source.resources().isEmpty()) {
            return;
        }
        ResourceCatalog catalog = source.resources().orElseThrow();
        for (ResourceVersion version : catalog.versions().values()) {
            try {
                roots.addOrReport(producers.requireContribution(version), problems);
                roots.addOrReport(producers.requireValidationContribution(version), problems);
            } catch (RuntimeException failure) {
                resourceFailure(failure);
                appendProblems(problems, failure);
            }
        }
        try {
            ResourceStorageBridge.plan(catalog, resources);
        } catch (RuntimeException failure) {
            resourceFailure(failure);
            appendProblems(problems, failure);
        }
    }

    private void inspectModules() {
        Map<String, String> savedModules = source.saved().state().modules();
        Set<EditorId> moduleRoots = new TreeSet<>();
        boolean overflow = savedModules.size() > DocumentTypeCatalog.MAX_MODULES;
        if (overflow) {
            problems.addAll(SessionMessages.moduleBudgetExceeded(savedModules.size(),
                    DocumentTypeCatalog.MAX_MODULES).problems());
        }
        for (Map.Entry<String, String> entry : savedModules.entrySet()) {
            EditorId moduleId;
            try {
                moduleId = EditorId.parse(entry.getKey());
            } catch (IllegalArgumentException failure) {
                problems.add(RuntimeMessages.moduleUnavailable(entry.getKey()));
                continue;
            }
            ModuleSpec installed = registry.metadata().modules().get(moduleId);
            if (installed == null) {
                problems.add(RuntimeMessages.moduleUnavailable(entry.getKey()));
            } else if (!installed.buildVersion().equals(entry.getValue())) {
                problems.add(RuntimeMessages.moduleVersionMismatch(entry.getKey(),
                        entry.getValue(), installed.buildVersion()));
            }
            if (!overflow) {
                moduleRoots.add(moduleId);
            }
        }
        if (overflow || roots.overflow) {
            return;
        }
        try {
            for (Map.Entry<EditorId, String> required : types.requiredModules(
                    roots.contributions, moduleRoots).entrySet()) {
                if (!savedModules.containsKey(required.getKey().toString())) {
                    problems.add(RuntimeMessages.moduleNotRecorded(
                            required.getKey(), required.getValue()));
                }
            }
        } catch (SessionRejection rejection) {
            problems.addAll(rejection.problems());
        }
    }

    static void appendProblems(List<Problem> target, RuntimeException failure) {
        if (failure instanceof SessionRejection rejection) {
            target.addAll(rejection.problems());
        } else if (failure instanceof DocumentCodecException codec) {
            target.addAll(codec.problems());
        } else if (failure instanceof DocumentRejection document) {
            target.addAll(document.problems());
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
