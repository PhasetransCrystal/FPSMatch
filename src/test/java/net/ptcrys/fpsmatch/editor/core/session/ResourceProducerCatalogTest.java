package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

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
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/**
 * Frozen behaviour of {@link ResourceProducerCatalog}.
 *
 * <p>Every fixture is assembled through the production builders and read back from the frozen
 * registry of that assembly: modules, contributions, implementations and language files are all
 * real, no snapshot is fabricated and no test hook is used. That is what makes the assertions about
 * ownership meaningful - the contribution key the catalog returns is the key the registry itself
 * published, and the module build versions come from the metadata of the same registry.</p>
 *
 * <p>The tests pin the two directions the design froze: a stored version is explained only by the
 * producer registered under its own importer id and only when that producer declares the stored
 * algorithm version string exactly; and nothing about the result is guessed from a name, so a
 * producer's namespace, its owner module and its module build version never stand in for the stored
 * importer version.</p>
 */
class ResourceProducerCatalogTest {

    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final Contribution.Side COMMON = Contribution.Side.COMMON;

    private static final EditorId PRODUCER_KIND = EditorId.parse("editor:resource_producer");
    private static final EditorId MODEL_IMPORTER = EditorId.parse("sandbox:mesh_importer");
    private static final EditorId OTHER_IMPORTER = EditorId.parse("vendor:other_importer");

    private static final ExtensionKind<Tool> TOOL_KIND =
            new ExtensionKind<>(EditorId.parse("test:tool"), Tool.class);

    /** The frozen producer sentences read from the original mod assets. */
    private static final Map<String, String> EN = loadProducerLang("en_us");
    private static final Map<String, String> ZH = loadProducerLang("zh_cn");

    @Test
    void producerLanguageFilesDeclareTheFrozenTable() {
        Set<String> frozen = Set.of("editor.session.resource_producer_unavailable",
                "editor.session.resource_producer_version_unavailable");

        assertEquals(frozen, EN.keySet());
        assertEquals(frozen, ZH.keySet());
    }

    @Test
    void undefinedProducerKindIsAnAssemblyError() {
        // The resource producer kind is deliberately not defined: publishing resource versions
        // without the extension point they are registered under is a programming error, and the
        // registry's own diagnosis has to survive instead of an empty but healthy-looking catalog.
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(TOOL_KIND);
        builder.addModule(module("app:runtime", "1.0.0"));
        builder.add(TOOL_KIND, new Contribution(new ContributionKey(TOOL_KIND.id(),
                EditorId.parse("test:only")), EditorId.parse("app:runtime"), COMMON, Set.of()),
                new Tool("test:only"));
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty(), "the fixture registry itself is healthy");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ResourceProducerCatalog.activate(registry));

        assertTrue(failure.getMessage().contains(PRODUCER_KIND.toString()), failure.getMessage());
        assertThrows(NullPointerException.class, () -> ResourceProducerCatalog.activate(null));
    }

    @Test
    void definedKindWithoutProducersRejectsTheVersionAsUnavailable() {
        ExtensionRegistry registry = new Fixture().module("app:runtime", "1.0.0").freeze();
        assertTrue(registry.entries(ResourceProducers.KIND).isEmpty(),
                "the kind is defined but publishes no producer");

        ResourceProducerCatalog catalog = ResourceProducerCatalog.activate(registry);
        ResourceVersion version = version(MODEL_IMPORTER, "3");
        Problem problem = singleProblem(rejection(() -> catalog.requireContribution(version)));

        assertEquals(EditorId.parse("editor:session/resource_producer_unavailable"), problem.code());
        assertEquals(version.id().toString(), problem.target());
        assertEquals(new TextContent.Message("editor.session.resource_producer_unavailable",
                List.of(new TextContent.LiteralArgument(MODEL_IMPORTER.toString()))),
                problem.message());
        assertFrozen(problem,
                "Resource producer sandbox:mesh_importer is unavailable; "
                        + "preserve the original resource data.",
                "资源来源模块 sandbox:mesh_importer 不可用；请保留原始资源数据。");
        assertThrows(NullPointerException.class, () -> catalog.requireContribution(null));
    }

    @Test
    void registeredProducerResolvesByImporterAndDeclaredVersion() {
        ExtensionRegistry registry = new Fixture()
                .module("vendor:pipeline", "4.2.0")
                .producer("vendor:pipeline", "sandbox:mesh_importer", "1", "2")
                .freeze();
        assertTrue(registry.metadata().problems().isEmpty());

        ResourceProducerCatalog catalog = ResourceProducerCatalog.activate(registry);
        assertEquals(Set.of(MODEL_IMPORTER), registry.entries(ResourceProducers.KIND).keySet());
        assertEquals(Set.of("1", "2"),
                registry.entries(ResourceProducers.KIND).get(MODEL_IMPORTER).readableVersions());

        ContributionKey key = catalog.requireContribution(version(MODEL_IMPORTER, "1"));
        assertEquals(new ContributionKey(PRODUCER_KIND, MODEL_IMPORTER), key);
        assertEquals(key, catalog.requireContribution(version(MODEL_IMPORTER, "2")),
                "one producer explains both declared versions");

        // Neither the producer's own namespace nor its owner module is inferred from the resolution.
        assertEquals("sandbox", key.id().namespace());
        assertNotEquals(key.id(), registry.metadata().contributions().get(key).owner());
        assertTrue(registry.metadata().contributions().containsKey(key));
    }

    @Test
    void undeclaredStoredVersionIsRejectedWithoutFallingBackToAnotherProducer() {
        ExtensionRegistry registry = new Fixture()
                .module("vendor:pipeline", "1.0.0")
                .module("sandbox:tools", "1.0.0")
                .producer("vendor:pipeline", "sandbox:mesh_importer", "2", "10")
                .producer("sandbox:tools", "vendor:other_importer", "1")
                .freeze();
        ResourceProducerCatalog catalog = ResourceProducerCatalog.activate(registry);

        ResourceVersion version = version(MODEL_IMPORTER, "1");
        Problem problem = singleProblem(rejection(() -> catalog.requireContribution(version)));

        assertEquals(EditorId.parse("editor:session/resource_producer_version_unavailable"),
                problem.code());
        assertEquals(version.id().toString(), problem.target());
        assertEquals(new TextContent.Message("editor.session.resource_producer_version_unavailable",
                List.of(new TextContent.LiteralArgument(MODEL_IMPORTER.toString()),
                        new TextContent.LiteralArgument("1"))), problem.message());
        assertFrozen(problem,
                "Resource producer sandbox:mesh_importer cannot interpret version 1; "
                        + "preserve the original resource data.",
                "资源来源模块 sandbox:mesh_importer 无法解释版本 1；请保留原始资源数据。");

        // A newer declared version of the same producer is accepted, and the stored string is left
        // untouched: the rejection above did not downgrade or rewrite it.
        assertEquals(new ContributionKey(PRODUCER_KIND, MODEL_IMPORTER),
                catalog.requireContribution(version(MODEL_IMPORTER, "10")));
        // Another producer that does declare "1" is never consulted as a substitute.
        assertEquals(new ContributionKey(PRODUCER_KIND, OTHER_IMPORTER),
                catalog.requireContribution(version(OTHER_IMPORTER, "1")));
        assertEquals(version, version(MODEL_IMPORTER, "1"),
                "the stored version survived the rejection unchanged");
    }

    @Test
    void storedVersionIsComparedExactlyWithoutTrimming() {
        ExtensionRegistry registry = new Fixture()
                .module("vendor:pipeline", "1.0.0")
                .producer("vendor:pipeline", "sandbox:mesh_importer", "1.0.0", " 2.0 ")
                .freeze();
        ResourceProducerCatalog catalog = ResourceProducerCatalog.activate(registry);

        assertEquals(new ContributionKey(PRODUCER_KIND, MODEL_IMPORTER),
                catalog.requireContribution(version(MODEL_IMPORTER, "1.0.0")));
        assertEquals(new ContributionKey(PRODUCER_KIND, MODEL_IMPORTER),
                catalog.requireContribution(version(MODEL_IMPORTER, " 2.0 ")));

        // Exact string membership only: no trimming, no version-scheme coercion, no prefix match.
        for (String undeclared : List.of("1.0", "1", "1.0.0 ", " 1.0.0", "v1.0.0", "2.0")) {
            Problem problem = singleProblem(
                    rejection(() -> catalog.requireContribution(version(MODEL_IMPORTER, undeclared))));
            assertEquals(EditorId.parse("editor:session/resource_producer_version_unavailable"),
                    problem.code(), "undeclared stored version <" + undeclared + ">");
        }
    }

    @Test
    void ownerClosureIsCollectedFromTheSameRegistryMetadata() {
        ExtensionRegistry registry = new Fixture()
                .module("vendor:base", "0.9.0")
                .moduleRequiring("vendor:pipeline", "7.3.1-rc4", "vendor:base")
                .producer("vendor:pipeline", "sandbox:mesh_importer", "2019.1", "2020.2")
                .freeze();
        assertTrue(registry.metadata().problems().isEmpty());

        ResourceProducerCatalog producers = ResourceProducerCatalog.activate(registry);
        DocumentTypeCatalog documents = DocumentTypeCatalog.activate(registry);
        ResourceVersion version = version(MODEL_IMPORTER, "2019.1");

        ContributionKey key = producers.requireContribution(version);
        assertTrue(registry.metadata().contributions().containsKey(key),
                "the key is a real contribution of this registry");
        assertEquals(EditorId.parse("vendor:pipeline"),
                registry.metadata().contributions().get(key).owner());
        assertEquals("sandbox", key.id().namespace());
        assertEquals("vendor", registry.metadata().contributions().get(key).owner().namespace());

        Map<EditorId, String> reached = documents.requiredModules(Set.of(key), Set.<EditorId>of());
        assertEquals(Map.of(EditorId.parse("vendor:base"), "0.9.0",
                EditorId.parse("vendor:pipeline"), "7.3.1-rc4"), reached);
        assertEquals(reached, documents.requiredModules(Set.of(key)),
                "both entry points close over the metadata of this registry");

        // The stored algorithm version stays a separate fact from the module build version.
        assertEquals("2019.1", version.importerVersion());
        assertNotEquals(version.importerVersion(), reached.get(EditorId.parse("vendor:pipeline")));
    }

    @Test
    void contributionMadeUnavailableByTheRegistryIsRejectedLikeAMissingProducer() {
        ExtensionRegistry registry = new Fixture()
                .module("vendor:pipeline", "1.0.0")
                .moduleRequiring("vendor:broken", "1.0.0", "vendor:missing")
                .moduleRequiring("vendor:downstream", "1.0.0", "vendor:broken")
                .producer("vendor:pipeline", "sandbox:healthy_importer", "1")
                .producer("vendor:downstream", "sandbox:indirect_importer", "1")
                .producer("vendor:absent", "sandbox:orphan_importer", "1")
                .freeze();
        ResourceProducerCatalog catalog = ResourceProducerCatalog.activate(registry);

        assertFalse(registry.metadata().problems().isEmpty(),
                "the registry reports why those contributions were not published");
        for (String rejected : List.of("sandbox:indirect_importer", "sandbox:orphan_importer")) {
            ContributionKey key = new ContributionKey(PRODUCER_KIND, EditorId.parse(rejected));
            assertFalse(registry.metadata().contributions().containsKey(key), rejected);
            assertFalse(registry.entries(ResourceProducers.KIND).containsKey(EditorId.parse(rejected)),
                    rejected);
        }

        // A sibling producer of the same kind is unaffected, so the rejections above are not an
        // artefact of an unhealthy fixture.
        EditorId healthy = EditorId.parse("sandbox:healthy_importer");
        assertEquals(new ContributionKey(PRODUCER_KIND, healthy),
                catalog.requireContribution(version(healthy, "1")));

        EditorId indirect = EditorId.parse("sandbox:indirect_importer");
        ResourceVersion indirectVersion = version(indirect, "1");
        Problem indirectProblem = singleProblem(
                rejection(() -> catalog.requireContribution(indirectVersion)));
        assertEquals(EditorId.parse("editor:session/resource_producer_unavailable"),
                indirectProblem.code());
        assertEquals(indirectVersion.id().toString(), indirectProblem.target());
        assertFrozen(indirectProblem,
                "Resource producer sandbox:indirect_importer is unavailable; "
                        + "preserve the original resource data.",
                "资源来源模块 sandbox:indirect_importer 不可用；请保留原始资源数据。");

        ResourceVersion orphanVersion = version(EditorId.parse("sandbox:orphan_importer"), "1");
        Problem orphanProblem = singleProblem(rejection(() -> catalog.requireContribution(orphanVersion)));
        assertEquals(EditorId.parse("editor:session/resource_producer_unavailable"),
                orphanProblem.code());
        assertEquals(orphanVersion.id().toString(), orphanProblem.target());
    }

    // ---------------------------------------------------------------------- fixture

    /**
     * One real builder fixture: all three extension points are defined, and every module and
     * contribution is registered as a real implementation object through the production builder.
     */
    private static final class Fixture {

        private final ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);

        Fixture() {
            builder.define(DocumentTypes.KIND);
            builder.define(ResourceProducers.KIND);
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

        Fixture producer(String owner, String id, String... readableVersions) {
            Contribution metadata = new Contribution(key(ResourceProducers.KIND, id),
                    EditorId.parse(owner), COMMON, Set.of());
            builder.add(ResourceProducers.KIND, metadata,
                    new ResourceProducerSpec(versions(readableVersions)));
            return this;
        }

        ExtensionRegistry freeze() {
            return builder.freeze();
        }
    }

    /** A real non-producer contribution, so the undefined-kind fixture registry is not empty. */
    private record Tool(String label) {
    }

    // ---------------------------------------------------------------------- helpers

    private static ModuleSpec module(String id, String buildVersion) {
        return new ModuleSpec(EditorId.parse(id), buildVersion, API, Set.of(), Set.of());
    }

    private static ContributionKey key(ExtensionKind<?> kind, String id) {
        return new ContributionKey(kind.id(), EditorId.parse(id));
    }

    private static Set<EditorId> ids(Set<String> values) {
        Set<EditorId> parsed = new TreeSet<>();
        for (String value : values) {
            parsed.add(EditorId.parse(value));
        }
        return parsed;
    }

    private static Set<String> versions(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    /**
     * A stored version whose identity follows from its importer and stored algorithm version, so two
     * calls for the same pair describe the same persisted record.
     */
    private static ResourceVersion version(EditorId importer, String importerVersion) {
        UUID id = UUID.nameUUIDFromBytes(
                (importer + "@" + importerVersion).getBytes(StandardCharsets.UTF_8));
        return new ResourceVersion(id, UUID.nameUUIDFromBytes(("resource:" + importer)
                .getBytes(StandardCharsets.UTF_8)), importer, importerVersion, Map.of(),
                UUID.nameUUIDFromBytes(("entry:" + id).getBytes(StandardCharsets.UTF_8)), Set.of(),
                Set.of());
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

    /** Reads the strict original assets table, retaining the frozen producer namespace. */
    private static Map<String, String> loadProducerLang(String language) {
        String resource = "/assets/fpsmatch/lang/" + language + ".json";
        try (InputStream stream = ResourceProducerCatalogTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "language resource is missing: " + resource);
            Map<String, String> entries = new TreeMap<>(LangResources.parse(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), resource));
            entries.keySet().removeIf(key -> !key.startsWith("editor.session.resource_producer_"));
            return Map.copyOf(entries);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + resource, failure);
        }
    }
}
