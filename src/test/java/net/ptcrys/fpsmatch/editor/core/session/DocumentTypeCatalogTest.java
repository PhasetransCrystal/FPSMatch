package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/**
 * Frozen behaviour of {@link DocumentTypeCatalog#requiredModules(Set)} and of the assembly step that
 * produces the catalog.
 *
 * <p>Every registry below is built through the real {@link ExtensionRegistryBuilder}: both extension
 * points are defined, every registration carries a real implementation object, and the frozen
 * snapshot is always the product of the production builder - no {@code RegistrySnapshot} is ever
 * fabricated and no test hook is added to production code.</p>
 *
 * <p>The dependency closure is exercised on real graphs only, so the two unreachable budget
 * diagnostics are deliberately not asserted: a rejected module or contribution never reaches the
 * snapshot, and the registry already caps accepted edges at the same 65536 the catalog counts, so
 * {@code module_unavailable} and {@code dependency_budget_exceeded} cannot be produced by a real
 * registry. What is reachable is pinned exactly: the catalog's own 1024-module directory budget (the
 * registry accepts 8192 modules), the root budget checked before any lookup, and the edge budget
 * saturated exactly at its limit. Every diagnostic is cross-checked against the frozen sentences of
 * both language resources through {@link LangResources}.</p>
 */
class DocumentTypeCatalogTest {

    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final Contribution.Side COMMON = Contribution.Side.COMMON;
    private static final Map<String, String> EN = LangResources.load("en_us");
    private static final Map<String, String> ZH = LangResources.load("zh_cn");

    /** A second, third-party extension point: document types are not the only kind in a registry. */
    private static final ExtensionKind<Tool> TOOL_KIND =
            new ExtensionKind<>(EditorId.parse("test:tool"), Tool.class);

    /** A real registered tool implementation; the registry publishes this object itself. */
    private record Tool(String label) { }

    @Test
    void undefinedDocumentKindIsAnAssemblyError() {
        // document_type is deliberately not defined: assembling a catalog out of a registry that
        // never declared the extension point is a programming error, and the contract requires the
        // registry's own diagnosis to survive instead of an empty but healthy-looking catalog.
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(TOOL_KIND);
        builder.addModule(new ModuleSpec(EditorId.parse("app:docs"), "1.0.0", API, Set.of(), Set.of()));
        builder.add(TOOL_KIND, new Contribution(key(TOOL_KIND, "test:only"), EditorId.parse("app:docs"),
                COMMON, Set.of()), new Tool("test:only"));
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty(), "the fixture registry itself is healthy");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DocumentTypeCatalog.activate(registry));
        assertTrue(failure.getMessage().contains(DocumentTypes.KIND.id().toString()),
                failure.getMessage());
    }

    @Test
    void definedDocumentKindWithoutTypesYieldsAnEmptyCatalog() {
        ExtensionRegistry registry = new Fixture().module("app:docs", "1.0.0").freeze();

        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry);

        assertTrue(catalog.entries().isEmpty());
        assertTrue(catalog.problems().isEmpty());
        assertTrue(catalog.activationFailures().isEmpty());
        assertTrue(catalog.find(EditorId.parse("app:text")).isEmpty());
        assertTrue(catalog.requiredModules(Set.of()).isEmpty(),
                "an empty root set closes over nothing and crosses no budget");
    }

    @Test
    void missingRootContributionIsReportedWithItsKey() {
        ExtensionRegistry registry = new Fixture()
                .module("app:docs", "1.0.0")
                .textDocument("app:docs", "app:text")
                .freeze();
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry);
        assertTrue(catalog.problems().isEmpty(), "the registered type activates without a problem");
        assertTrue(catalog.find(EditorId.parse("app:text")).isPresent());

        // A sibling document type of the same kind exists, so this is a missing id, not a missing kind.
        ContributionKey ghost = key(DocumentTypes.KIND, "vendor:ghost");
        Problem problem = singleProblem(rejection(() -> catalog.requiredModules(Set.of(ghost))));

        assertEquals(EditorId.parse("editor:session/contribution_unavailable"), problem.code());
        assertEquals(ghost.toString(), problem.target());
        assertFrozen(problem, "Required contribution " + ghost + " is unavailable.",
                "必需贡献 " + ghost + " 不可用。");
    }

    @Test
    void contributionRejectedByTheRegistryIsReportedUnavailable() {
        ExtensionRegistry registry = new Fixture()
                .module("app:docs", "1.0.0")
                .textDocument("app:docs", "app:text")
                .tool("app:docs", "vendor:healthy")
                .tool("app:docs", "vendor:broken", "vendor:absent")
                .freeze();
        ContributionKey healthy = key(TOOL_KIND, "vendor:healthy");
        ContributionKey broken = key(TOOL_KIND, "vendor:broken");

        assertFalse(registry.metadata().contributions().containsKey(broken),
                "a contribution whose requirement is missing is rejected and never published");
        assertFalse(registry.metadata().problems().isEmpty(),
                "the registry explains its own rejection");
        assertEquals(new Tool("vendor:healthy"),
                registry.find(TOOL_KIND, healthy.id()).orElseThrow(),
                "the fixture publishes real implementation objects");

        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry);
        assertTrue(catalog.problems().isEmpty(),
                "registry problems of other kinds never become type-activation problems");
        assertTrue(catalog.find(EditorId.parse("app:text")).isPresent());
        assertEquals(Map.of(EditorId.parse("app:docs"), "1.0.0"),
                catalog.requiredModules(Set.of(healthy)),
                "the healthy sibling of the rejected contribution still resolves");

        Problem problem = singleProblem(rejection(() -> catalog.requiredModules(Set.of(broken))));
        assertEquals(EditorId.parse("editor:session/contribution_unavailable"), problem.code());
        assertEquals(broken.toString(), problem.target());
        assertFrozen(problem, "Required contribution " + broken + " is unavailable.",
                "必需贡献 " + broken + " 不可用。");
    }

    @Test
    void closureUsesRealOwnersExactVersionsAndExcludesOptionalModules() {
        ExtensionRegistry registry = new Fixture()
                .module("vendor:base", "1.4.9-rc1")
                .module("vendor:optional", "9.9.9")
                .moduleRequiring("app:core", "2.0.0", "vendor:base")
                .moduleRequiring("other:helpers", "0.5.0", "vendor:base")
                .module("app:docs", "3.2.1-alpha+build.7", Set.of("app:core"), Set.of("vendor:optional"))
                .textDocument("app:docs", "app:text", "vendor:tool")
                .tool("other:helpers", "vendor:tool", "other:helper")
                .tool("vendor:base", "other:helper")
                .freeze();
        assertTrue(registry.metadata().problems().isEmpty());
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry);

        Map<EditorId, String> reached = catalog.requiredModules(Set.of(key(DocumentTypes.KIND, "app:text")));

        // app:docs -> app:core -> vendor:base is the required-module chain of the document type's own
        // owner; other:helpers and vendor:base are reached through the nested contributions, whose
        // owners live in two other namespaces than the document type.
        Map<EditorId, String> expected = new TreeMap<>();
        expected.put(EditorId.parse("app:core"), "2.0.0");
        expected.put(EditorId.parse("app:docs"), "3.2.1-alpha+build.7");
        expected.put(EditorId.parse("other:helpers"), "0.5.0");
        expected.put(EditorId.parse("vendor:base"), "1.4.9-rc1");
        assertEquals(expected, reached);

        assertTrue(registry.metadata().modules().containsKey(EditorId.parse("vendor:optional")),
                "the optional module is registered; it is excluded by the edge kind, not by availability");
        assertEquals(Set.of(EditorId.parse("vendor:optional")),
                registry.metadata().modules().get(EditorId.parse("app:docs")).optionalModules());
        assertFalse(reached.containsKey(EditorId.parse("vendor:optional")),
                "optional modules never enter the required-module closure");
        assertEquals(List.copyOf(expected.keySet()), new ArrayList<>(reached.keySet()),
                "the result iterates in the frozen EditorId order");
    }

    @Test
    void registrationOrderAndRootOrderDoNotChangeTheClosure() {
        ExtensionRegistry forward = new Fixture()
                .module("vendor:base", "0.9.0")
                .moduleRequiring("app:core", "2.0.0", "vendor:base")
                .module("app:docs", "1.0.0", Set.of("app:core"), Set.of())
                .textDocument("app:docs", "app:text", "other:helper")
                .tool("vendor:base", "other:helper")
                .tool("app:docs", "vendor:tool")
                .freeze();
        ExtensionRegistry reversed = new Fixture()
                .tool("app:docs", "vendor:tool")
                .tool("vendor:base", "other:helper")
                .textDocument("app:docs", "app:text", "other:helper")
                .module("app:docs", "1.0.0", Set.of("app:core"), Set.of())
                .moduleRequiring("app:core", "2.0.0", "vendor:base")
                .module("vendor:base", "0.9.0")
                .freeze();

        ContributionKey document = key(DocumentTypes.KIND, "app:text");
        ContributionKey tool = key(TOOL_KIND, "vendor:tool");
        Map<EditorId, String> expected = Map.of(
                EditorId.parse("app:core"), "2.0.0",
                EditorId.parse("app:docs"), "1.0.0",
                EditorId.parse("vendor:base"), "0.9.0");

        assertEquals(expected, DocumentTypeCatalog.activate(forward).requiredModules(Set.of(document, tool)));
        assertEquals(expected, DocumentTypeCatalog.activate(reversed)
                .requiredModules(new LinkedHashSet<>(List.of(tool, document))));
    }

    @Test
    void resultIsAnImmutableSnapshotInFrozenOrder() {
        ExtensionRegistry registry = new Fixture()
                .module("vendor:base", "0.9.0")
                .moduleRequiring("vendor:extra", "0.8.0", "vendor:base")
                .module("app:docs", "1.0.0")
                .textDocument("app:docs", "app:text")
                .tool("vendor:extra", "vendor:tool")
                .freeze();
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry);

        Map<EditorId, String> reached = catalog.requiredModules(Set.of(key(TOOL_KIND, "vendor:tool")));

        assertEquals(List.of(EditorId.parse("vendor:base"), EditorId.parse("vendor:extra")),
                new ArrayList<>(reached.keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> reached.put(EditorId.parse("app:docs"), "1.0.0"));
        assertThrows(UnsupportedOperationException.class,
                () -> reached.remove(EditorId.parse("vendor:base")));
        assertEquals(reached, catalog.requiredModules(Set.of(key(TOOL_KIND, "vendor:tool"))),
                "each call returns its own equal snapshot");
    }

    @Test
    void longContributionChainIsWalkedWithoutRecursion() {
        int length = 1500;
        Fixture fixture = new Fixture()
                .module("chain:head", "1.0.0-20240101")
                .module("chain:tail", "1.0.0-20240102");
        for (int index = 0; index < length; index++) {
            String id = "chain:tool_" + index;
            if (index == length - 1) {
                fixture.tool("chain:tail", id);
            } else {
                fixture.tool("chain:head", id, "chain:tool_" + (index + 1));
            }
        }
        ExtensionRegistry registry = fixture.freeze();
        assertTrue(registry.metadata().problems().isEmpty());

        // Nothing requires chain:tail and no earlier tool owns it, so its presence proves that all
        // 1500 contributions were dequeued and the 1500th edge was counted.
        Map<EditorId, String> reached = DocumentTypeCatalog.activate(registry)
                .requiredModules(Set.of(key(TOOL_KIND, "chain:tool_0")));

        assertEquals(Map.of(EditorId.parse("chain:head"), "1.0.0-20240101",
                EditorId.parse("chain:tail"), "1.0.0-20240102"), reached);
    }

    @Test
    void moduleDirectoryBudgetIsTheCatalogsOwnThousandTwentyFour() {
        int modules = 1025;
        Fixture fixture = new Fixture();
        for (int index = 0; index < modules; index++) {
            String id = "deep:mod_" + index;
            if (index == modules - 1) {
                fixture.module(id, "1.0.0");
            } else {
                fixture.moduleRequiring(id, "1.0.0", "deep:mod_" + (index + 1));
            }
        }
        fixture.textDocument("deep:mod_0", "deep:text");
        ExtensionRegistry registry = fixture.freeze();
        assertEquals(modules, registry.metadata().modules().size(),
                "the registry accepts 1025 modules, so the rejection is the catalog's own budget");
        assertTrue(registry.metadata().problems().isEmpty());

        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry);
        Problem problem = singleProblem(rejection(
                () -> catalog.requiredModules(Set.of(key(DocumentTypes.KIND, "deep:text")))));

        assertEquals(EditorId.parse("editor:session/module_budget_exceeded"), problem.code());
        assertEquals("dependency_graph", problem.target());
        assertFrozen(problem, "Required module count 1025 exceeds limit 1024.",
                "必需模块数量 1025 超过上限 1024。");
    }

    @Test
    void tooManyRootsAreRejectedBeforeAnyLookup() {
        ExtensionRegistry registry = new Fixture()
                .module("app:docs", "1.0.0")
                .textDocument("app:docs", "app:text")
                .freeze();
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(registry);

        Problem overflow = singleProblem(rejection(() -> catalog.requiredModules(absentTools(8193))));
        assertEquals(EditorId.parse("editor:session/contribution_budget_exceeded"), overflow.code());
        assertEquals("dependency_graph", overflow.target());
        // None of these keys exists in the registry: the budget is crossed before the first lookup.
        assertFrozen(overflow, "Required contribution count 8193 exceeds limit 8192.",
                "必需贡献数量 8193 超过上限 8192。");

        // 8192 roots are not a budget crossing, so the walk starts and reports the first missing root
        // in the frozen order, which is test:absent_0 because every id shares the same prefix.
        Problem boundary = singleProblem(rejection(() -> catalog.requiredModules(absentTools(8192))));
        assertEquals(EditorId.parse("editor:session/contribution_unavailable"), boundary.code());
        assertEquals(key(TOOL_KIND, "test:absent_0").toString(), boundary.target());
    }

    @Test
    void saturatedRealEdgeBudgetIsNotInflatedByRootsOrOwners() {
        int modules = 1024;
        Fixture fixture = new Fixture();
        for (int index = 0; index < modules; index++) {
            fixture.module("budget:mod_" + index, "1.0." + index, budgetDependencies(index), Set.of());
        }
        fixture.textDocument("budget:mod_0", "budget:text");
        fixture.tool("budget:mod_1", "budget:tool");
        fixture.tool("budget:mod_2", "budget:tool_2");
        ExtensionRegistry registry = fixture.freeze();

        long edges = registry.metadata().modules().values().stream()
                .mapToLong(spec -> spec.requiredModules().size() + spec.optionalModules().size())
                .sum()
                + registry.metadata().contributions().values().stream()
                        .mapToLong(contribution -> contribution.requiredContributions().size())
                        .sum();
        assertEquals(65536L, edges, "the fixture saturates the registry edge budget exactly");
        assertEquals(modules, registry.metadata().modules().size());
        assertTrue(registry.metadata().problems().isEmpty());

        // The registry refuses a 65537th edge, so this is the only graph that can touch the catalog's
        // edge limit: if roots or owner relations were charged as edges, a graph the registry accepted
        // would be rejected here, and the deepest module would never be reported.
        Set<EditorId> expected = new TreeSet<>();
        for (int index = 0; index < modules; index++) {
            expected.add(EditorId.parse("budget:mod_" + index));
        }
        Map<EditorId, String> reached = DocumentTypeCatalog.activate(registry).requiredModules(Set.of(
                key(DocumentTypes.KIND, "budget:text"),
                key(TOOL_KIND, "budget:tool"),
                key(TOOL_KIND, "budget:tool_2")));

        assertEquals(expected, reached.keySet());
        assertEquals("1.0.0", reached.get(EditorId.parse("budget:mod_0")));
        assertEquals("1.0.1023", reached.get(EditorId.parse("budget:mod_1023")));
    }

    // ------------------------------------------------------------------ fixtures

    /** The required set of one module of the edge-saturating graph; the sizes sum to exactly 65536. */
    private static Set<String> budgetDependencies(int index) {
        int count;
        if (index <= 64) {
            count = 96;
        } else if (index <= 959) {
            count = 64;
        } else {
            count = 1023 - index;
        }
        Set<String> required = new TreeSet<>();
        for (int step = 1; step <= count; step++) {
            required.add("budget:mod_" + (index + step));
        }
        return required;
    }

    /** Root keys that no registry of these tests ever declares. */
    private static Set<ContributionKey> absentTools(int count) {
        Set<ContributionKey> roots = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            roots.add(key(TOOL_KIND, "test:absent_" + index));
        }
        assertEquals(count, roots.size());
        return roots;
    }

    /**
     * One real builder fixture: both extension points are defined, and every module and contribution
     * is registered with a real implementation object through the production builder.
     */
    private static final class Fixture {

        private final ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);

        Fixture() {
            builder.define(DocumentTypes.KIND);
            builder.define(TOOL_KIND);
        }

        Fixture module(String id, String buildVersion) {
            return module(id, buildVersion, Set.of(), Set.of());
        }

        Fixture module(String id, String buildVersion, Set<String> required, Set<String> optional) {
            builder.addModule(new ModuleSpec(EditorId.parse(id), buildVersion, API, ids(required),
                    ids(optional)));
            return this;
        }

        Fixture moduleRequiring(String id, String buildVersion, String... required) {
            return module(id, buildVersion, Set.of(required), Set.of());
        }

        Fixture textDocument(String owner, String id, String... requiredTools) {
            Contribution metadata = new Contribution(key(DocumentTypes.KIND, id),
                    EditorId.parse(owner), COMMON, toolKeys(requiredTools));
            builder.add(DocumentTypes.KIND, metadata, new TextType());
            return this;
        }

        Fixture tool(String owner, String id, String... requiredTools) {
            Contribution metadata = new Contribution(key(TOOL_KIND, id), EditorId.parse(owner), COMMON,
                    toolKeys(requiredTools));
            builder.add(TOOL_KIND, metadata, new Tool(id));
            return this;
        }

        ExtensionRegistry freeze() {
            return builder.freeze();
        }
    }

    /** A real registered document type; its descriptor is valid, so activation cannot fail on it. */
    private static final class TextType implements DocumentType<String> {

        @Override
        public Class<String> valueType() {
            return String.class;
        }

        @Override
        public EditorId codecId() {
            return EditorId.parse("test:text_codec");
        }

        @Override
        public int schemaVersion() {
            return 2;
        }

        @Override
        public Set<Integer> readableSchemas() {
            return Set.of(1, 2);
        }

        @Override
        public String create() {
            return "";
        }

        @Override
        public String decode(int sourceSchema, byte[] encoding) {
            if (sourceSchema < 1 || sourceSchema > 2) {
                throw new IllegalArgumentException("unreadable schema: " + sourceSchema);
            }
            return new String(encoding, StandardCharsets.UTF_8);
        }

        @Override
        public DocumentDependencies dependencies(String snapshot) {
            return new DocumentDependencies(Set.of(), Set.of(), Set.of());
        }

        @Override
        public String snapshot(String value) {
            return value;
        }

        @Override
        public byte[] encode(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<Problem> validate(String value) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------- helpers

    private static ContributionKey key(ExtensionKind<?> kind, String id) {
        return new ContributionKey(kind.id(), EditorId.parse(id));
    }

    private static Set<ContributionKey> toolKeys(String... ids) {
        Set<ContributionKey> keys = new LinkedHashSet<>();
        for (String id : ids) {
            keys.add(key(TOOL_KIND, id));
        }
        return keys;
    }

    private static Set<EditorId> ids(Set<String> values) {
        Set<EditorId> parsed = new TreeSet<>();
        for (String value : values) {
            parsed.add(EditorId.parse(value));
        }
        return parsed;
    }

    private static SessionRejection rejection(Executable operation) {
        SessionRejection rejection = assertThrows(SessionRejection.class, operation);
        assertNull(rejection.getCause(), "a self-detected rejection carries no cause: " + rejection);
        return rejection;
    }

    private static Problem singleProblem(SessionRejection rejection) {
        List<Problem> problems = rejection.problems();
        assertEquals(1, problems.size(), "exactly one diagnostic: " + problems);
        Problem problem = problems.get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        return problem;
    }

    /** Cross-checks one produced diagnostic against the frozen sentences of both language files. */
    private static void assertFrozen(Problem problem, String english, String chinese) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals(english, LangResources.rendered(EN, message));
        assertEquals(chinese, LangResources.rendered(ZH, message));
    }
}
