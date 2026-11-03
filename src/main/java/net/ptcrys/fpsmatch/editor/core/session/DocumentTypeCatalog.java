package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.api.registry.RegistrySnapshot;
import net.ptcrys.fpsmatch.editor.core.types.ActivatedCodec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Activated document implementations and bounded code-dependency lookup from one frozen registry. */
public final class DocumentTypeCatalog {

    static final int MAX_DEPENDENCIES = 8192;
    static final int MAX_MODULES = 1024;
    static final int MAX_DEPENDENCY_EDGES = 65536;

    private final RegistrySnapshot metadata;
    private final Map<EditorId, ActivatedDocumentType<?>> entries;
    private final Map<EditorId, RuntimeException> failures;
    private final List<Problem> problems;

    private DocumentTypeCatalog(RegistrySnapshot metadata,
                                Map<EditorId, ActivatedDocumentType<?>> entries,
                                Map<EditorId, RuntimeException> failures, List<Problem> problems) {
        this.metadata = metadata;
        this.entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        this.failures = Collections.unmodifiableMap(new TreeMap<>(failures));
        this.problems = List.copyOf(problems);
    }

    /** No alternate metadata/implementation input can bypass the actual frozen registry. */
    public static DocumentTypeCatalog activate(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        // An undefined KIND is an assembly error, outside per-type failure isolation.
        Map<EditorId, DocumentType<?>> types = registry.entries(DocumentTypes.KIND);
        RegistrySnapshot metadata = registry.metadata();
        Map<EditorId, ActivatedDocumentType<?>> entries = new TreeMap<>();
        Map<EditorId, RuntimeException> failures = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<EditorId, DocumentType<?>> entry : types.entrySet()) {
            EditorId id = entry.getKey();
            try {
                ContributionKey key = new ContributionKey(DocumentTypes.KIND.id(), id);
                Contribution contribution = Objects.requireNonNull(metadata.contributions().get(key),
                        "registered contribution metadata");
                entries.put(id, activateOne(id, contribution.owner(), entry.getValue(), registry));
            } catch (RuntimeException failure) {
                failures.put(id, failure);
                problems.add(SessionMessages.typeActivationFailed(id).problems().get(0));
            }
        }
        return new DocumentTypeCatalog(metadata, entries, failures, problems);
    }

    private static <T> ActivatedDocumentType<T> activateOne(EditorId id, EditorId owner,
                                                            DocumentType<T> implementation, ExtensionRegistry registry) {
        ActivatedCodec<T> prototype = new ActivatedCodec<>(id, implementation);
        DocumentType<T> bound = Objects.requireNonNull(implementation.bind(registry), "bound document type");
        ActivatedCodec<T> codec = bound == implementation ? prototype : new ActivatedCodec<>(id, bound);
        if (prototype.valueType() != codec.valueType() || !prototype.codecId().equals(codec.codecId()) || prototype.schemaVersion() != codec.schemaVersion() || !prototype.readableSchemas().equals(codec.readableSchemas())) {
            throw new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                    new EditorId("editor", "codec/binding_mismatch"), id.toString(),
                    new TextContent.Message("editor.codec.binding_mismatch",
                            List.of(new TextContent.LiteralArgument(id.toString()))))));
        }
        return new ActivatedDocumentType<>(id, owner, bound, codec);
    }

    public Map<EditorId, ActivatedDocumentType<?>> entries() {
        return entries;
    }

    public List<Problem> problems() {
        return problems;
    }

    public Map<EditorId, RuntimeException> activationFailures() {
        return failures;
    }

    public Optional<ActivatedDocumentType<?>> find(EditorId id) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(id, "id")));
    }

    /** Returns exact owner/required-module build versions, excluding optional modules. */
    public Map<EditorId, String> requiredModules(Set<ContributionKey> roots) {
        return requiredModules(roots, Set.of());
    }

    /** Includes direct module owners of stored descriptors/bytes without inventing contributions. */
    public Map<EditorId, String> requiredModules(Set<ContributionKey> roots, Set<EditorId> moduleRoots) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(moduleRoots, "moduleRoots");
        if (roots.size() > MAX_DEPENDENCIES) {
            throw SessionMessages.contributionBudgetExceeded(roots.size(), MAX_DEPENDENCIES);
        }
        if (moduleRoots.size() > MAX_MODULES) {
            throw SessionMessages.moduleBudgetExceeded(moduleRoots.size(), MAX_MODULES);
        }
        DependencyClosure closure = new DependencyClosure(metadata);
        for (ContributionKey root : new TreeSet<>(roots)) {
            closure.enqueueContribution(root);
        }
        for (EditorId moduleRoot : new TreeSet<>(moduleRoots)) {
            closure.enqueueModule(moduleRoot);
        }
        closure.run();
        return Collections.unmodifiableMap(new TreeMap<>(closure.modules));
    }

    private static final class DependencyClosure {

        private final RegistrySnapshot metadata;
        private final Set<ContributionKey> contributions = new TreeSet<>();
        private final Set<EditorId> discoveredModules = new TreeSet<>();
        private final Map<EditorId, String> modules = new TreeMap<>();
        private final Deque<ContributionKey> contributionQueue = new ArrayDeque<>();
        private final Deque<EditorId> moduleQueue = new ArrayDeque<>();
        private int edges;

        DependencyClosure(RegistrySnapshot metadata) {
            this.metadata = metadata;
        }

        void enqueueContribution(ContributionKey key) {
            Objects.requireNonNull(key, "contribution");
            if (contributions.contains(key)) {
                return;
            }
            if (contributions.size() == MAX_DEPENDENCIES) {
                throw SessionMessages.contributionBudgetExceeded(contributions.size() + 1L,
                        MAX_DEPENDENCIES);
            }
            contributions.add(key);
            contributionQueue.add(key);
        }

        private void enqueueModule(EditorId id) {
            Objects.requireNonNull(id, "module");
            if (discoveredModules.contains(id)) {
                return;
            }
            if (discoveredModules.size() == MAX_MODULES) {
                throw SessionMessages.moduleBudgetExceeded(discoveredModules.size() + 1L, MAX_MODULES);
            }
            discoveredModules.add(id);
            moduleQueue.add(id);
        }

        private void countDependencyEdge() {
            if (edges == MAX_DEPENDENCY_EDGES) {
                throw SessionMessages.dependencyBudgetExceeded(edges + 1L, MAX_DEPENDENCY_EDGES);
            }
            edges++;
        }

        void run() {
            while (!contributionQueue.isEmpty() || !moduleQueue.isEmpty()) {
                if (!contributionQueue.isEmpty()) {
                    ContributionKey key = contributionQueue.remove();
                    Contribution item = metadata.contributions().get(key);
                    if (item == null) {
                        throw SessionMessages.contributionUnavailable(key);
                    }
                    enqueueModule(item.owner());
                    for (ContributionKey required : new TreeSet<>(item.requiredContributions())) {
                        countDependencyEdge();
                        enqueueContribution(required);
                    }
                } else {
                    EditorId id = moduleQueue.remove();
                    ModuleSpec item = metadata.modules().get(id);
                    if (item == null) {
                        throw SessionMessages.moduleUnavailable(id);
                    }
                    modules.put(id, item.buildVersion());
                    for (EditorId required : new TreeSet<>(item.requiredModules())) {
                        countDependencyEdge();
                        enqueueModule(required);
                    }
                }
            }
        }
    }
}
