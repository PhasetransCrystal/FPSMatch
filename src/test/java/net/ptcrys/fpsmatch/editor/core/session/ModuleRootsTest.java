package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/**
 * Behaviour of {@link DocumentTypeCatalog#requiredModules(Set, Set)} on registries that a real
 * {@link ExtensionRegistryBuilder} froze.
 *
 * <p>Contribution roots and module roots feed one single closure over the entries the registry
 * really offers. A contribution root enters through the module that owns it and then follows the
 * declared required modules of that module; a module root is that module itself. Both kinds share
 * one traversal, one budget and one result, so a module reached along both paths is counted once.
 * Nothing is invented for a module root: the module that owns stored byte entries and publishes no
 * contribution at all can only be reached because it is nameable as a root, and a module root never
 * pulls in the owner of a contribution no root names.</p>
 *
 * <p>Every fixture is built through the public extension API out of real {@link DocumentType}
 * implementations; no snapshot is fabricated and no private member is reached.</p>
 */
class ModuleRootsTest {

    private static final ApiVersion API = new ApiVersion(0, 1);

    private static final EditorId CORE = EditorId.parse("sample:core");
    private static final EditorId CODEC = EditorId.parse("sample:codec");
    private static final EditorId DOCUMENTS = EditorId.parse("sample:documents");
    private static final EditorId RESOURCES = EditorId.parse("sample:resources");
    /** Owns stored byte entries and publishes no contribution: only a module root can name it. */
    private static final EditorId BYTE_LAYOUT = EditorId.parse("sample:byte_layout");
    private static final EditorId OPTIONAL_HELPER = EditorId.parse("sample:optional_helper");
    private static final EditorId OPTIONAL_UI = EditorId.parse("sample:optional_ui");

    /** A document type that embeds a third-party palette, so it requires that contribution too. */
    private static final ContributionKey NOTE = key("sample:note");
    private static final ContributionKey PALETTE = key("sample:embedded_palette");

    // ---------------------------------------------------------------- fixtures

    /** The sample closure: documents -> codec -> core, resources -> core, byte_layout -> core. */
    private static ExtensionRegistry sampleRegistry() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.addModule(module(CORE, "1.0.0", Set.of(), Set.of()));
        builder.addModule(module(CODEC, "2.3.1", Set.of(CORE), Set.of(OPTIONAL_HELPER)));
        builder.addModule(module(DOCUMENTS, "4.0.0", Set.of(CODEC), Set.of(OPTIONAL_UI)));
        builder.addModule(module(RESOURCES, "3.1.4", Set.of(CORE), Set.of()));
        builder.addModule(module(BYTE_LAYOUT, "5.2.0", Set.of(CORE), Set.of()));
        builder.addModule(module(OPTIONAL_HELPER, "9.9.9", Set.of(), Set.of()));
        builder.addModule(module(OPTIONAL_UI, "8.8.8", Set.of(), Set.of()));
        builder.define(DocumentTypes.KIND);
        DocumentType<?> note = new PlainTextType("sample:note_codec", 1);
        DocumentType<?> palette = new PlainTextType("sample:palette_codec", 2);
        builder.add(DocumentTypes.KIND, contribution(NOTE, DOCUMENTS, Set.of(PALETTE)), note);
        builder.add(DocumentTypes.KIND, contribution(PALETTE, RESOURCES, Set.of()), palette);
        return builder.freeze();
    }

    private static DocumentTypeCatalog sampleCatalog() {
        return DocumentTypeCatalog.activate(sampleRegistry());
    }

    private static ModuleSpec module(EditorId id, String buildVersion, Set<EditorId> required,
            Set<EditorId> optional) {
        return new ModuleSpec(id, buildVersion, API, required, optional);
    }

    private static Contribution contribution(ContributionKey key, EditorId owner,
            Set<ContributionKey> required) {
        return new Contribution(key, owner, Contribution.Side.COMMON, required);
    }

    private static ContributionKey key(String id) {
        return new ContributionKey(DocumentTypes.KIND.id(), EditorId.parse(id));
    }

    // ---------------------------------------------------------------- module roots

    @Test
    void readsAModuleRootAndItsDeclaredRequiredModulesOnly() {
        DocumentTypeCatalog catalog = sampleCatalog();

        Map<EditorId, String> result = catalog.requiredModules(Set.of(), Set.of(DOCUMENTS));

        assertEquals(Map.of(DOCUMENTS, "4.0.0", CODEC, "2.3.1", CORE, "1.0.0"), result,
                "the direct root plus every level of its declared required modules");
        assertFalse(result.containsKey(RESOURCES),
                "the document type owned by sample:documents requires the palette contribution, but "
                        + "no contribution root was named, so its owner module must not be invented");
        assertFalse(result.containsKey(OPTIONAL_HELPER), "the optional module of sample:codec");
        assertFalse(result.containsKey(OPTIONAL_UI), "the optional module of the direct root");
        assertTrue(catalog.requiredModules(Set.of(), Set.of()).isEmpty(),
                "no root of either kind reads no module at all");
    }

    @Test
    void readsAContributionRootThroughTheModulesThatOwnIt() {
        DocumentTypeCatalog catalog = sampleCatalog();

        Map<EditorId, String> result = catalog.requiredModules(Set.of(NOTE), Set.of());

        assertEquals(Map.of(DOCUMENTS, "4.0.0", CODEC, "2.3.1", CORE, "1.0.0", RESOURCES, "3.1.4"),
                result, "owner module, its required chain and the owner of the required contribution");
        assertTrue(result.containsKey(DOCUMENTS), "a contribution root enters as its owner module");
    }

    @Test
    void countsAnOwnerModuleOnceWhenItIsAlsoADirectModuleRoot() {
        DocumentTypeCatalog catalog = sampleCatalog();

        Map<EditorId, String> fromContribution = catalog.requiredModules(Set.of(NOTE), Set.of());
        Map<EditorId, String> both = catalog.requiredModules(Set.of(NOTE), Set.of(DOCUMENTS));

        assertEquals(Map.of(DOCUMENTS, "4.0.0", CODEC, "2.3.1", CORE, "1.0.0", RESOURCES, "3.1.4"),
                both);
        assertEquals(4, both.size(),
                "the owner named by both roots appears once, so adding a module root changes nothing");
        assertEquals(fromContribution, both, "a root already inside the closure adds no entry");
    }

    @Test
    void joinsBothRootKindsIntoOneClosure() {
        DocumentTypeCatalog catalog = sampleCatalog();

        Map<EditorId, String> fromContribution = catalog.requiredModules(Set.of(NOTE), Set.of());
        Map<EditorId, String> fromModuleRoot = catalog.requiredModules(Set.of(), Set.of(BYTE_LAYOUT));
        Map<EditorId, String> joint =
                catalog.requiredModules(Set.of(NOTE), Set.of(BYTE_LAYOUT));

        assertEquals(Map.of(BYTE_LAYOUT, "5.2.0", CORE, "1.0.0"), fromModuleRoot,
                "the byte module publishes no contribution and is reachable as a root alone");
        assertEquals(Map.of(DOCUMENTS, "4.0.0", CODEC, "2.3.1", CORE, "1.0.0", RESOURCES, "3.1.4"),
                fromContribution);
        assertEquals(Map.of(DOCUMENTS, "4.0.0", CODEC, "2.3.1", CORE, "1.0.0", RESOURCES, "3.1.4",
                BYTE_LAYOUT, "5.2.0"), joint, "one shared closure over both root kinds");
        assertEquals(5, joint.size(), "the module required by both roots is counted once");
        assertTrue(joint.size() > fromContribution.size(), "the module root adds sample:byte_layout");
        assertTrue(joint.size() > fromModuleRoot.size(), "the contribution root adds three modules");
    }

    @Test
    void rejectsAModuleRootTheRegistryDoesNotOffer() {
        DocumentTypeCatalog catalog = sampleCatalog();
        EditorId absent = EditorId.parse("sample:absent_module");

        SessionRejection rejection = assertThrows(SessionRejection.class,
                () -> catalog.requiredModules(Set.of(), Set.of(absent)));

        assertEquals(1, rejection.problems().size());
        Problem problem = rejection.problems().get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(EditorId.parse("editor:session/module_unavailable"), problem.code(),
                "the typed diagnostic code, not a prose message");
        assertEquals(absent.toString(), problem.target(), "the refused root module is named");
        TextContent.Message message = messageOf(problem);
        assertEquals("editor.session.module_unavailable", message.key());
        assertEquals(List.of(new TextContent.LiteralArgument(absent.toString())),
                message.arguments());
        assertThrows(UnsupportedOperationException.class, () -> rejection.problems().add(problem),
                "the diagnostics of a rejection are immutable");
    }

    // ---------------------------------------------------------------- budgets

    @Test
    void rejectsMoreModuleRootsThanTheBudgetBeforeAnyTraversal() {
        DocumentTypeCatalog catalog = sampleCatalog();
        Set<EditorId> roots = new LinkedHashSet<>();
        for (int index = 0; index <= 1024; index++) {
            // None of these modules exists: only a precheck can reject the call before traversal.
            roots.add(EditorId.parse("sample:bulk_" + index));
        }

        SessionRejection rejection = assertThrows(SessionRejection.class,
                () -> catalog.requiredModules(Set.of(), roots));

        assertBudgetRejection(rejection, "editor:session/module_budget_exceeded",
                "editor.session.module_budget_exceeded", 1025, 1024);
    }

    @Test
    void rejectsTheTotalModuleCountWhenAModuleTreeJoinsAContributionOwner() {
        DocumentTypeCatalog catalog = DocumentTypeCatalog.activate(chainRegistry());
        EditorId head = chainModule(0);

        Map<EditorId, String> tree = catalog.requiredModules(Set.of(), Set.of(head));

        assertEquals(1024, tree.size(), "the bound itself is still accepted");
        assertEquals("1.0.0", tree.get(head));
        assertEquals("1.0.1023", tree.get(chainModule(1023)), "the exact build version survives");
        assertFalse(tree.containsKey(CHAIN_OWNER), "no contribution root was named");

        SessionRejection rejection = assertThrows(SessionRejection.class,
                () -> catalog.requiredModules(Set.of(CHAIN_CONTRIBUTION), Set.of(head)));

        assertBudgetRejection(rejection, "editor:session/module_budget_exceeded",
                "editor.session.module_budget_exceeded", 1025, 1024);
    }

    // ---------------------------------------------------------------- result shape

    @Test
    void returnsOneImmutableSortedClosureAndKeepsTheCallerSets() {
        DocumentTypeCatalog catalog = sampleCatalog();
        LinkedHashSet<ContributionKey> roots = new LinkedHashSet<>(List.of(PALETTE, NOTE));
        LinkedHashSet<EditorId> moduleRoots = new LinkedHashSet<>(List.of(RESOURCES));
        List<ContributionKey> rootsContent = List.copyOf(roots);
        List<EditorId> moduleRootsContent = List.copyOf(moduleRoots);

        Map<EditorId, String> result = catalog.requiredModules(roots, moduleRoots);

        assertEquals(rootsContent, List.copyOf(roots), "the caller's set keeps its own order");
        assertEquals(moduleRootsContent, List.copyOf(moduleRoots), "the caller's set keeps its order");
        assertEquals(result,
                catalog.requiredModules(new LinkedHashSet<>(List.of(NOTE, PALETTE)),
                        new TreeSet<>(moduleRootsContent)),
                "the iteration order of the caller's sets cannot steer the result");
        assertEquals(List.of(CODEC, CORE, DOCUMENTS, RESOURCES), List.copyOf(result.keySet()));
        assertEquals(List.copyOf(result.keySet()),
                new ArrayList<>(new TreeSet<>(result.keySet())), "natural module id order");

        Map<EditorId, String> published = Map.copyOf(result);
        roots.clear();
        moduleRoots.add(OPTIONAL_UI);
        assertEquals(published, result, "a later caller mutation cannot reach a published result");

        assertThrows(UnsupportedOperationException.class, () -> result.put(CORE, "9.9.9"));
        assertThrows(UnsupportedOperationException.class, () -> result.remove(CORE));
        assertThrows(UnsupportedOperationException.class, () -> result.keySet().remove(CORE));
        assertThrows(UnsupportedOperationException.class, () -> result.values().remove("1.0.0"));
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    // ---------------------------------------------------------------- helpers

    private static final int CHAIN_LENGTH = 1024;
    private static final EditorId CHAIN_OWNER = EditorId.parse("sample:chain_owner");
    private static final ContributionKey CHAIN_CONTRIBUTION = key("sample:chain_owner_type");

    private static EditorId chainModule(int index) {
        return EditorId.parse("sample:chain_" + index);
    }

    /** 1024 modules in one required chain plus the owner module of a single contribution. */
    private static ExtensionRegistry chainRegistry() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        for (int index = 0; index < CHAIN_LENGTH - 1; index++) {
            builder.addModule(module(chainModule(index), "1.0." + index,
                    Set.of(chainModule(index + 1)), Set.of()));
        }
        builder.addModule(module(chainModule(CHAIN_LENGTH - 1), "1.0." + (CHAIN_LENGTH - 1),
                Set.of(), Set.of()));
        builder.addModule(module(CHAIN_OWNER, "9.0.0", Set.of(), Set.of()));
        builder.define(DocumentTypes.KIND);
        DocumentType<?> owner = new PlainTextType("sample:chain_codec", 1);
        builder.add(DocumentTypes.KIND, contribution(CHAIN_CONTRIBUTION, CHAIN_OWNER, Set.of()), owner);
        return builder.freeze();
    }

    /** One diagnostic with the fixed graph target and typed observed/limit arguments. */
    private static void assertBudgetRejection(SessionRejection rejection, String code, String key,
            long observed, long limit) {
        assertEquals(1, rejection.problems().size(), "a budget precheck reports one problem");
        Problem problem = rejection.problems().get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(EditorId.parse(code), problem.code());
        assertEquals("dependency_graph", problem.target(),
                "the precheck names the graph, never one of the refused roots");
        TextContent.Message message = messageOf(problem);
        assertEquals(key, message.key());
        assertEquals(List.of(new TextContent.NumberArgument(BigDecimal.valueOf(observed)),
                new TextContent.NumberArgument(BigDecimal.valueOf(limit))), message.arguments());
    }

    private static TextContent.Message messageOf(Problem problem) {
        TextContent text = problem.message();
        assertTrue(text instanceof TextContent.Message, "a diagnostic carries a translation key");
        return (TextContent.Message) text;
    }

    // ---------------------------------------------------------------- fixture type

    /** A real, minimal text codec: one schema, UTF-8 bytes, no document dependencies. */
    private static final class PlainTextType implements DocumentType<String> {

        private static final EditorId UNREADABLE_SCHEMA =
                EditorId.parse("sample:text_schema_unreadable");

        private final EditorId codecId;
        private final int schema;

        PlainTextType(String codecId, int schema) {
            this.codecId = EditorId.parse(codecId);
            this.schema = schema;
        }

        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return codecId; }
        @Override public int schemaVersion() { return schema; }
        @Override public Set<Integer> readableSchemas() { return Set.of(schema); }
        @Override public String create() { return ""; }
        @Override public String snapshot(String value) { return value; }
        @Override public List<Problem> validate(String value) { return List.of(); }

        @Override public String decode(int sourceSchema, byte[] encoding) {
            if (sourceSchema != schema) {
                throw new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                        UNREADABLE_SCHEMA, codecId.toString(), new TextContent.Message(
                                "sample.text.schema_unreadable",
                                List.of(new TextContent.NumberArgument(
                                        BigDecimal.valueOf(sourceSchema)))))));
            }
            return new String(encoding, StandardCharsets.UTF_8);
        }

        @Override public byte[] encode(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override public DocumentDependencies dependencies(String value) {
            return new DocumentDependencies(Set.of(), Set.of(), Set.of());
        }
    }
}
