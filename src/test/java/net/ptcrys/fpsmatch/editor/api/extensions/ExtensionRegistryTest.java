package net.ptcrys.fpsmatch.editor.api.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.api.registry.RegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.RegistrySnapshot;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the generic extension registry against the card's counterexamples: two third-party
 * kinds published and actually invoked, atomic developer-contract failures, unavailable
 * contributions never exposed, one-shot freeze and an immutable frozen result.
 */
class ExtensionRegistryTest {

    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final Contribution.Side COMMON = Contribution.Side.COMMON;
    private static final Contribution.Side CLIENT = Contribution.Side.CLIENT;

    private static final EditorId CORE = EditorId.parse("thirdparty:core");
    private static final EditorId ADDON = EditorId.parse("thirdparty:addon");

    private static final EditorId SCORER_KIND_ID = EditorId.parse("thirdparty:scorer");
    private static final EditorId LABEL_KIND_ID = EditorId.parse("thirdparty:label");

    private static final ExtensionKind<IntUnaryOperator> SCORER =
            kind(SCORER_KIND_ID, IntUnaryOperator.class);
    private static final ExtensionKind<UnaryOperator<String>> LABEL =
            kind(LABEL_KIND_ID, UnaryOperator.class);

    private static final EditorId DOUBLE = EditorId.parse("thirdparty:double");
    private static final EditorId SHOUT = EditorId.parse("thirdparty:shout");
    private static final EditorId SHARED = EditorId.parse("thirdparty:shared");
    private static final EditorId DUPLICATE = EditorId.parse("thirdparty:duplicate");
    private static final EditorId DEPENDENT = EditorId.parse("thirdparty:dependent");
    private static final EditorId FIRST = EditorId.parse("thirdparty:first");
    private static final EditorId SECOND = EditorId.parse("thirdparty:second");
    private static final EditorId CLIENT_ONLY = EditorId.parse("thirdparty:client_only");

    private static final String DUPLICATE_MODULE = "editor:registry/duplicate_module";
    private static final String DUPLICATE_CONTRIBUTION = "editor:registry/duplicate_contribution";
    private static final String DEPENDENCY_UNAVAILABLE = "editor:registry/dependency_unavailable";
    private static final String MISSING_OWNER = "editor:registry/missing_owner";
    private static final String INCOMPATIBLE_API = "editor:registry/incompatible_api";
    private static final String CONTRIBUTION_CYCLE = "editor:registry/contribution_cycle";
    private static final String WRONG_SIDE = "editor:registry/wrong_side";

    /** A third-party implementation whose calls are observable, to prove lookups never run it. */
    private static final class CountingScorer implements IntUnaryOperator {
        private final int factor;
        private int calls;

        CountingScorer(int factor) {
            this.factor = factor;
        }

        @Override
        public int applyAsInt(int operand) {
            calls++;
            return operand * factor;
        }
    }

    /** Shared instances: the registration-order test compares the frozen maps including values. */
    private static final IntUnaryOperator DOUBLER = operand -> operand * 2;
    private static final IntUnaryOperator INCREMENTER = operand -> operand + 1;
    private static final UnaryOperator<String> SHOUTER = text -> text + "!";

    // ---------------------------------------------------------------- two real kinds

    @Test
    void definesDistinguishesAbsentEmptyAndPopulatedKindsWithoutInvokingCallbacks() {
        ExtensionRegistryBuilder builder = open();
        CountingScorer scorer = new CountingScorer(2);
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), scorer);
        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.defines(SCORER));
        assertTrue(registry.defines(new ExtensionKind<>(SCORER_KIND_ID, IntUnaryOperator.class)));
        assertTrue(registry.defines(LABEL));
        assertFalse(registry.defines(kind(EditorId.parse("thirdparty:absent"), String.class)));
        assertThrows(NullPointerException.class, () -> registry.defines(null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.defines(kind(SCORER_KIND_ID, String.class)));
        assertEquals(0, scorer.calls);
    }

    @Test
    void definesRejectsSameBinaryClassNameFromAnotherLoader() throws IOException {
        Class<?> otherClass = loadWithFreshLoader(Contract.class);
        ExtensionRegistryBuilder builder = open();
        EditorId contractKind = EditorId.parse("thirdparty:contract");
        builder.define(kind(contractKind, Contract.class));
        ExtensionRegistry registry = builder.freeze();

        assertThrows(IllegalArgumentException.class,
                () -> registry.defines(kind(contractKind, otherClass)));
        assertTrue(registry.defines(kind(contractKind, Contract.class)));
    }

    @Test
    void twoThirdPartyKindsPublishAndRunTheirRealImplementations() {
        ExtensionRegistryBuilder builder = open();
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        builder.add(LABEL, contribution(LABEL_KIND_ID, SHOUT), text -> text.toUpperCase(Locale.ROOT) + "!");

        ExtensionRegistry registry = builder.freeze();

        assertEquals(42, registry.find(SCORER, DOUBLE).orElseThrow().applyAsInt(21));
        assertEquals("HELLO!", registry.find(LABEL, SHOUT).orElseThrow().apply("hello"));
        Map<EditorId, IntUnaryOperator> scorers = registry.entries(SCORER);
        assertEquals(Set.of(DOUBLE), scorers.keySet());
        assertEquals(8, scorers.get(DOUBLE).applyAsInt(4));
        assertEquals(Set.of(SHOUT), registry.entries(LABEL).keySet());
        assertEquals(Set.of(CORE), Set.copyOf(registry.metadata().modules().keySet()));
        assertTrue(registry.metadata().problems().isEmpty(), () -> registry.metadata().problems().toString());
    }

    @Test
    void oneContributionIdMayExistInTwoDifferentKinds() {
        ExtensionRegistryBuilder builder = open();
        builder.add(SCORER, contribution(SCORER_KIND_ID, SHARED), operand -> operand + 1);
        builder.add(LABEL, contribution(LABEL_KIND_ID, SHARED), text -> "[" + text + "]");

        ExtensionRegistry registry = builder.freeze();

        assertEquals(6, registry.find(SCORER, SHARED).orElseThrow().applyAsInt(5));
        assertEquals("[x]", registry.find(LABEL, SHARED).orElseThrow().apply("x"));
        assertEquals(Map.of(SHARED, registry.find(SCORER, SHARED).orElseThrow()), registry.entries(SCORER));
        assertEquals(Map.of(SHARED, registry.find(LABEL, SHARED).orElseThrow()), registry.entries(LABEL));
        assertEquals(2, registry.metadata().contributions().size());
    }

    @Test
    void registrationOrderDoesNotChangeTheResult() {
        ExtensionRegistry first = buildOrdered(true);
        ExtensionRegistry second = buildOrdered(false);

        assertEquals(first.metadata().modules(), second.metadata().modules());
        assertEquals(first.metadata().contributions(), second.metadata().contributions());
        assertEquals(first.metadata().problems(), second.metadata().problems());
        assertEquals(first.entries(SCORER), second.entries(SCORER));
        assertEquals(first.entries(LABEL), second.entries(LABEL));
        assertEquals(42, second.find(SCORER, DOUBLE).orElseThrow().applyAsInt(21));
        assertEquals(43, second.find(SCORER, FIRST).orElseThrow().applyAsInt(42));
    }

    private static ExtensionRegistry buildOrdered(boolean forward) {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        ContributionKey doubled = new ContributionKey(SCORER_KIND_ID, DOUBLE);
        builder.define(SCORER);
        builder.define(LABEL);
        if (forward) {
            builder.addModule(module(CORE));
            builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), DOUBLER);
            builder.add(LABEL, contribution(LABEL_KIND_ID, SHOUT), SHOUTER);
            builder.addModule(module(ADDON));
            builder.add(SCORER, contribution(SCORER_KIND_ID, FIRST, ADDON, COMMON, doubled), INCREMENTER);
        } else {
            builder.add(SCORER, contribution(SCORER_KIND_ID, FIRST, ADDON, COMMON, doubled), INCREMENTER);
            builder.add(LABEL, contribution(LABEL_KIND_ID, SHOUT), SHOUTER);
            builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), DOUBLER);
            builder.addModule(module(ADDON));
            builder.addModule(module(CORE));
        }
        return builder.freeze();
    }

    // ----------------------------------------------------------- unavailable records

    @Test
    void duplicateContributionPublishesNeitherRecordAndBlocksItsDependents() {
        ExtensionRegistryBuilder builder = open();
        builder.addModule(module(ADDON));
        builder.add(SCORER, contribution(SCORER_KIND_ID, DUPLICATE, CORE, COMMON), operand -> operand * 2);
        builder.add(SCORER, contribution(SCORER_KIND_ID, DUPLICATE, ADDON, COMMON), operand -> operand * 3);
        ContributionKey duplicateKey = new ContributionKey(SCORER_KIND_ID, DUPLICATE);
        ContributionKey dependentKey = new ContributionKey(SCORER_KIND_ID, DEPENDENT);
        builder.add(SCORER, contribution(SCORER_KIND_ID, DEPENDENT, CORE, COMMON, duplicateKey),
                operand -> operand + 100);

        ExtensionRegistry registry = builder.freeze();
        RegistrySnapshot metadata = registry.metadata();

        assertTrue(registry.find(SCORER, DUPLICATE).isEmpty());
        assertTrue(registry.find(SCORER, DEPENDENT).isEmpty());
        assertTrue(registry.entries(SCORER).isEmpty());
        assertFalse(metadata.contributions().containsKey(duplicateKey));
        assertFalse(metadata.contributions().containsKey(dependentKey));
        assertTrue(hasProblem(metadata, DUPLICATE_CONTRIBUTION, duplicateKey.toString()));
        assertTrue(hasProblem(metadata, DEPENDENCY_UNAVAILABLE, dependentKey.toString()));
        assertFalse(hasProblem(metadata, DUPLICATE_CONTRIBUTION, dependentKey.toString()));
        assertTrue(hasMessageKey(metadata, "editor.registry.duplicate_contribution"),
                () -> metadata.problems().toString());
    }

    @Test
    void duplicateModuleOnlyMakesItsOwnContributionsUnavailable() {
        ExtensionRegistryBuilder builder = open();
        builder.addModule(module(CORE));
        builder.addModule(module(ADDON));
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        builder.add(SCORER, contribution(SCORER_KIND_ID, FIRST, ADDON, COMMON), operand -> operand + 7);

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.find(SCORER, DOUBLE).isEmpty());
        assertEquals(7, registry.find(SCORER, FIRST).orElseThrow().applyAsInt(0));
        assertTrue(hasProblem(registry.metadata(), DUPLICATE_MODULE, CORE.toString()));
        assertTrue(hasProblem(registry.metadata(), DEPENDENCY_UNAVAILABLE,
                new ContributionKey(SCORER_KIND_ID, DOUBLE).toString()));
    }

    @Test
    void implementationOfAMissingOwnerIsNeverPublished() {
        ExtensionRegistryBuilder builder = open();
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE, ADDON, COMMON), operand -> operand * 2);

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.find(SCORER, DOUBLE).isEmpty());
        assertTrue(registry.entries(SCORER).isEmpty());
        assertTrue(hasProblem(registry.metadata(), MISSING_OWNER,
                new ContributionKey(SCORER_KIND_ID, DOUBLE).toString()));
    }

    @Test
    void implementationOfAnIncompatibleApiModuleIsNeverPublished() {
        ExtensionRegistryBuilder builder = open();
        builder.addModule(new ModuleSpec(ADDON, "2.0.0", new ApiVersion(2, 0), Set.of(), Set.of()));
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE, ADDON, COMMON), operand -> operand * 2);

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.find(SCORER, DOUBLE).isEmpty());
        assertFalse(registry.metadata().modules().containsKey(ADDON));
        assertTrue(hasProblem(registry.metadata(), INCOMPATIBLE_API, ADDON.toString()));
        assertTrue(hasProblem(registry.metadata(), DEPENDENCY_UNAVAILABLE,
                new ContributionKey(SCORER_KIND_ID, DOUBLE).toString()));
    }

    @Test
    void cyclicContributionsAreNeverPublished() {
        ExtensionRegistryBuilder builder = open();
        ContributionKey first = new ContributionKey(SCORER_KIND_ID, FIRST);
        ContributionKey second = new ContributionKey(SCORER_KIND_ID, SECOND);
        builder.add(SCORER, contribution(SCORER_KIND_ID, FIRST, CORE, COMMON, second), operand -> operand * 2);
        builder.add(SCORER, contribution(SCORER_KIND_ID, SECOND, CORE, COMMON, first), operand -> operand * 3);

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.entries(SCORER).isEmpty());
        assertTrue(hasProblem(registry.metadata(), CONTRIBUTION_CYCLE, first.toString()));
        assertTrue(hasProblem(registry.metadata(), CONTRIBUTION_CYCLE, second.toString()));
    }

    @Test
    void commonEnvironmentDropsClientImplementationsButKeepsThemOnTheClient() {
        ExtensionRegistry common = registered(COMMON);
        assertTrue(common.find(SCORER, CLIENT_ONLY).isEmpty());
        assertEquals(8, common.find(SCORER, DOUBLE).orElseThrow().applyAsInt(4));
        assertTrue(hasProblem(common.metadata(), WRONG_SIDE,
                new ContributionKey(SCORER_KIND_ID, CLIENT_ONLY).toString()));

        ExtensionRegistry client = registered(CLIENT);
        assertEquals(9, client.find(SCORER, CLIENT_ONLY).orElseThrow().applyAsInt(3));
        assertTrue(client.metadata().problems().isEmpty(), () -> client.metadata().problems().toString());
    }

    private static ExtensionRegistry registered(Contribution.Side environment) {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, environment);
        builder.define(SCORER);
        builder.addModule(module(CORE));
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        builder.add(SCORER, contribution(SCORER_KIND_ID, CLIENT_ONLY, CORE, CLIENT), operand -> operand * 3);
        return builder.freeze();
    }

    @Test
    void commonContributionRequiringAClientContributionIsRefusedInBothEnvironments() {
        for (Contribution.Side environment : Contribution.Side.values()) {
            ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, environment);
            builder.define(SCORER);
            builder.addModule(module(CORE));
            builder.add(SCORER, contribution(SCORER_KIND_ID, CLIENT_ONLY, CORE, CLIENT), operand -> operand * 3);
            ContributionKey required = new ContributionKey(SCORER_KIND_ID, CLIENT_ONLY);
            builder.add(SCORER, contribution(SCORER_KIND_ID, DEPENDENT, CORE, COMMON, required),
                    operand -> operand + 1);

            ExtensionRegistry registry = builder.freeze();

            assertTrue(registry.find(SCORER, DEPENDENT).isEmpty(), environment.name());
            assertTrue(hasProblem(registry.metadata(), WRONG_SIDE,
                    new ContributionKey(SCORER_KIND_ID, DEPENDENT).toString()), environment.name());
        }
    }

    // ----------------------------------------------------- atomic developer failures

    @Test
    void undefinedKindIsRejectedForDefineAddAndLookup() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(SCORER);
        builder.addModule(module(CORE));
        assertThrows(IllegalArgumentException.class,
                () -> builder.add(LABEL, contribution(LABEL_KIND_ID, SHOUT), text -> text));

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.metadata().contributions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.find(LABEL, SHOUT));
        assertThrows(IllegalArgumentException.class, () -> registry.entries(LABEL));
        assertTrue(registry.find(SCORER, DOUBLE).isEmpty());
    }

    @Test
    void conflictingKindContractIsRejectedAtomicallyAndNeverReplacesTheFirstDefinition() {
        ExtensionRegistryBuilder builder = open();
        ExtensionKind<String> sameIdOtherClass = kind(SCORER_KIND_ID, String.class);
        assertThrows(IllegalArgumentException.class, () -> builder.define(sameIdOtherClass));
        assertThrows(IllegalArgumentException.class,
                () -> builder.add(sameIdOtherClass, contribution(SCORER_KIND_ID, DOUBLE), "text"));

        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        ExtensionRegistry registry = builder.freeze();

        assertEquals(42, registry.find(SCORER, DOUBLE).orElseThrow().applyAsInt(21));
        assertThrows(IllegalArgumentException.class, () -> registry.find(sameIdOtherClass, DOUBLE));
        assertThrows(IllegalArgumentException.class, () -> registry.entries(sameIdOtherClass));
        assertEquals(1, registry.metadata().contributions().size());
    }

    @Test
    void redefiningTheSameKindIdWithTheSameClassIsIdempotent() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        ExtensionKind<IntUnaryOperator> first = kind(SCORER_KIND_ID, IntUnaryOperator.class);
        ExtensionKind<IntUnaryOperator> second = new ExtensionKind<>(SCORER_KIND_ID, IntUnaryOperator.class);
        assertNotSame(first, second);

        builder.define(first);
        builder.define(second);
        builder.addModule(module(CORE));
        builder.add(second, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        ExtensionRegistry registry = builder.freeze();

        assertEquals(42, registry.find(first, DOUBLE).orElseThrow().applyAsInt(21));
        assertEquals(42, registry.find(second, DOUBLE).orElseThrow().applyAsInt(21));
        assertEquals(42, registry.entries(first).get(DOUBLE).applyAsInt(21));
    }

    @Test
    void theSameClassNameFromAnotherLoaderIsNotTheSameContract() throws IOException {
        Class<?> sameNameOtherLoader = loadWithFreshLoader(Contract.class);
        assertEquals(Contract.class.getName(), sameNameOtherLoader.getName());
        assertNotSame(Contract.class, sameNameOtherLoader);

        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(kind(SCORER_KIND_ID, Contract.class));
        assertThrows(IllegalArgumentException.class,
                () -> builder.define(kind(SCORER_KIND_ID, sameNameOtherLoader)));

        builder.addModule(module(CORE));
        assertTrue(builder.freeze().metadata().contributions().isEmpty());
    }

    /** Loads one class again under a throwaway loader: same binary name, different identity. */
    private static Class<?> loadWithFreshLoader(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream stream = type.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new AssertionError("missing class resource: " + resource);
        }
        byte[] bytes;
        try (InputStream in = stream) {
            bytes = in.readAllBytes();
        }
        return new SameNameLoader(type.getClassLoader()).loadSameName(type.getName(), bytes);
    }

    /** A child loader that defines the same binary name independently of its parent. */
    private static final class SameNameLoader extends ClassLoader {
        SameNameLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> loadSameName(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Kind contract fixture for the class-identity check; never instantiated or linked. */
    private static final class Contract {
    }

    @Test
    void metadataNamingAnotherKindIsRejectedWithoutCollectingAnything() {
        ExtensionRegistryBuilder builder = open();
        assertThrows(IllegalArgumentException.class,
                () -> builder.add(LABEL, contribution(SCORER_KIND_ID, DOUBLE), text -> text));

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.metadata().contributions().isEmpty());
        assertTrue(registry.entries(LABEL).isEmpty());
    }

    @Test
    void nullArgumentsAndNonReferenceValueTypesAreRejected() {
        ExtensionRegistryBuilder builder = open();
        IntUnaryOperator identity = operand -> operand;
        assertThrows(NullPointerException.class, () -> builder.define(null));
        assertThrows(NullPointerException.class, () -> builder.addModule(null));
        assertThrows(NullPointerException.class,
                () -> builder.add(null, contribution(SCORER_KIND_ID, DOUBLE), identity));
        assertThrows(NullPointerException.class, () -> builder.add(SCORER, null, identity));
        assertThrows(NullPointerException.class,
                () -> builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), null));
        assertThrows(NullPointerException.class, () -> new ExtensionKind<>(null, IntUnaryOperator.class));
        assertThrows(NullPointerException.class, () -> new ExtensionKind<>(SCORER_KIND_ID, null));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionKind<>(SCORER_KIND_ID, int.class));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionKind<>(SCORER_KIND_ID, void.class));
        assertThrows(NullPointerException.class, () -> new ExtensionRegistryBuilder(null, COMMON));
        assertThrows(NullPointerException.class, () -> new ExtensionRegistryBuilder(API, null));

        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        assertTrue(builder.freeze().find(SCORER, DOUBLE).isPresent());
    }

    @Test
    void rawImplementationFailingTheValueTypeContractIsRejected() {
        ExtensionRegistryBuilder builder = open();
        assertThrows(IllegalArgumentException.class,
                () -> addRaw(builder, SCORER, contribution(SCORER_KIND_ID, DOUBLE), "not a scorer"));

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.metadata().contributions().isEmpty());
    }

    @Test
    void kindBudgetIsCheckedBeforeInsertionAndLeavesTheBuilderUsable() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        for (int index = 0; index < ExtensionRegistryBuilder.MAX_KINDS; index++) {
            builder.define(kind(EditorId.parse("thirdparty:kind" + index), IntUnaryOperator.class));
        }
        EditorId overBudget = EditorId.parse("thirdparty:kind" + ExtensionRegistryBuilder.MAX_KINDS);
        assertThrows(IllegalArgumentException.class,
                () -> builder.define(kind(overBudget, IntUnaryOperator.class)));

        ExtensionKind<IntUnaryOperator> defined = kind(EditorId.parse("thirdparty:kind0"), IntUnaryOperator.class);
        builder.addModule(module(CORE));
        builder.add(defined, contribution(EditorId.parse("thirdparty:kind0"), DOUBLE), operand -> operand * 2);
        ExtensionRegistry registry = builder.freeze();

        assertEquals(42, registry.find(defined, DOUBLE).orElseThrow().applyAsInt(21));
        assertEquals(1, registry.metadata().contributions().size());
        assertThrows(IllegalArgumentException.class,
                () -> registry.find(kind(overBudget, IntUnaryOperator.class), DOUBLE));
    }

    @Test
    void frozenBuilderRejectsEveryFurtherMutation() {
        ExtensionRegistryBuilder builder = open();
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        ExtensionRegistry registry = builder.freeze();

        assertThrows(IllegalStateException.class, () -> builder.define(LABEL));
        assertThrows(IllegalStateException.class, () -> builder.addModule(module(ADDON)));
        assertThrows(IllegalStateException.class,
                () -> builder.add(SCORER, contribution(SCORER_KIND_ID, FIRST), operand -> operand));
        assertThrows(IllegalStateException.class, builder::freeze);

        assertEquals(42, registry.find(SCORER, DOUBLE).orElseThrow().applyAsInt(21));
        assertTrue(registry.find(SCORER, FIRST).isEmpty());
        assertTrue(registry.entries(LABEL).isEmpty());
    }

    // ------------------------------------------------------------ frozen result

    @Test
    void frozenResultIgnoresLaterInputChangesAndExposesNoMutableCollection() {
        ExtensionRegistryBuilder builder = open();
        Set<ContributionKey> required = new HashSet<>();
        Contribution metadata = new Contribution(
                new ContributionKey(SCORER_KIND_ID, DEPENDENT), CORE, COMMON, required);
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        builder.add(SCORER, metadata, operand -> operand + 1);

        ExtensionRegistry registry = builder.freeze();
        required.add(new ContributionKey(SCORER_KIND_ID, DOUBLE));

        assertTrue(registry.metadata().contributions()
                .get(new ContributionKey(SCORER_KIND_ID, DEPENDENT)).requiredContributions().isEmpty());
        assertTrue(registry.find(SCORER, DEPENDENT).isPresent());

        Map<EditorId, IntUnaryOperator> entries = registry.entries(SCORER);
        assertThrows(UnsupportedOperationException.class, () -> entries.put(FIRST, operand -> operand));
        assertThrows(UnsupportedOperationException.class, entries::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> registry.metadata().contributions().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.metadata().modules().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.metadata().problems().clear());

        assertEquals(2, entries.size());
        assertEquals(42, entries.get(DOUBLE).applyAsInt(21));
    }

    @Test
    void anEmptyKindIsLegalAndNeitherFindNorEntriesLieAboutIt() {
        ExtensionRegistryBuilder builder = open();
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);

        ExtensionRegistry registry = builder.freeze();

        assertTrue(registry.entries(LABEL).isEmpty());
        assertTrue(registry.find(LABEL, SHOUT).isEmpty());
        assertEquals(Optional.empty(), registry.find(LABEL, SHOUT));
        assertTrue(registry.metadata().contributions()
                .containsKey(new ContributionKey(SCORER_KIND_ID, DOUBLE)));
    }

    @Test
    void lookupsNeverInvokeAnImplementationAndKeepItsIdentity() {
        ExtensionRegistryBuilder builder = open();
        CountingScorer scorer = new CountingScorer(2);
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), scorer);

        ExtensionRegistry registry = builder.freeze();

        assertSame(scorer, registry.find(SCORER, DOUBLE).orElseThrow());
        assertSame(scorer, registry.entries(SCORER).get(DOUBLE));
        assertSame(scorer, registry.find(SCORER, DOUBLE).orElseThrow());
        assertEquals(0, scorer.calls);
        assertEquals(6, registry.find(SCORER, DOUBLE).orElseThrow().applyAsInt(3));
        assertEquals(1, scorer.calls);
    }

    @Test
    void publicApiExposesNoRawRegistryBuilderAndNoPublicRegistryConstructor() {
        for (Constructor<?> constructor : ExtensionRegistry.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()),
                    "ExtensionRegistry must not expose a public constructor");
            assertFalse(Modifier.isProtected(constructor.getModifiers()),
                    "ExtensionRegistry must not expose a protected constructor");
        }
        for (Class<?> type : List.of(ExtensionKind.class, ExtensionRegistryBuilder.class, ExtensionRegistry.class)) {
            assertTrue(Modifier.isPublic(type.getModifiers()));
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                assertNotEquals(RegistryBuilder.class, method.getReturnType(),
                        () -> "raw registry builder returned from " + method);
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertNotEquals(RegistryBuilder.class, parameter,
                            () -> "raw registry builder accepted by " + method);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ExtensionRegistryBuilder open() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(SCORER);
        builder.define(LABEL);
        builder.addModule(module(CORE));
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static <T> ExtensionKind<T> kind(EditorId id, Class<?> valueType) {
        return new ExtensionKind<>(id, (Class<T>) valueType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addRaw(ExtensionRegistryBuilder builder, ExtensionKind<?> kind,
                               Contribution metadata, Object implementation) {
        builder.add((ExtensionKind) kind, metadata, implementation);
    }

    private static ModuleSpec module(EditorId id, EditorId... required) {
        return new ModuleSpec(id, "1.0.0", API, Set.of(required), Set.of());
    }

    private static Contribution contribution(EditorId kindId, EditorId id) {
        return contribution(kindId, id, CORE, COMMON);
    }

    private static Contribution contribution(EditorId kindId, EditorId id, EditorId owner,
                                             Contribution.Side side, ContributionKey... required) {
        return new Contribution(new ContributionKey(kindId, id), owner, side, Set.of(required));
    }

    private static boolean hasProblem(RegistrySnapshot snapshot, String code, String target) {
        return snapshot.problems().stream()
                .anyMatch(problem -> problem.code().toString().equals(code)
                        && problem.target().equals(target));
    }

    private static boolean hasMessageKey(RegistrySnapshot snapshot, String key) {
        return snapshot.problems().stream()
                .anyMatch(problem -> problem.message() instanceof TextContent.Message message
                        && message.key().equals(key));
    }
}
