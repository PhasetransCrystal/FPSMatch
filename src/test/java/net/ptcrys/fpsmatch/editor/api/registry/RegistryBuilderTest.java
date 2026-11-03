package net.ptcrys.fpsmatch.editor.api.registry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behaviour tests for the frozen P01 registry contracts. No Gradle is started here; the main agent
 * runs this suite through the module build.
 *
 * <p>The diagnostic message keys are the module-owned translation vocabulary
 * ({@code editor.registry.*}); importing the internal {@link Messages} keeps the assertions honest
 * without duplicating string literals.</p>
 */
class RegistryBuilderTest {
    private static final ApiVersion API_1_2 = new ApiVersion(1, 2);

    private static EditorId id(String path) {
        return new EditorId("editor", path);
    }

    private static ModuleSpec module(String path, ApiVersion required, String... requiredModules) {
        Set<EditorId> dependencies = new HashSet<>();
        for (String dependency : requiredModules) {
            dependencies.add(id(dependency));
        }
        return new ModuleSpec(id(path), "1.0.0", required, dependencies, Set.of());
    }

    private static Contribution contribution(String kind, String ownerModule, Contribution.Side side,
            ContributionKey... requiredContributions) {
        return new Contribution(new ContributionKey(id(kind), id(kind + "/main")), id(ownerModule), side,
                Set.of(requiredContributions));
    }

    private static ContributionKey key(String kind) {
        return new ContributionKey(id(kind), id(kind + "/main"));
    }

    private static String keyOf(Problem problem) {
        return ((TextContent.Message) problem.message()).key();
    }

    /** Literal argument values of a diagnostic, in order. */
    private static List<String> arguments(Problem problem) {
        List<TextContent.Argument> values = ((TextContent.Message) problem.message()).arguments();
        List<String> rendered = new ArrayList<>(values.size());
        for (TextContent.Argument value : values) {
            rendered.add(((TextContent.LiteralArgument) value).value());
        }
        return rendered;
    }

    private static Optional<Problem> problem(RegistrySnapshot snapshot, EditorId code) {
        return snapshot.problems().stream().filter(problem -> problem.code().equals(code)).findFirst();
    }

    private static RegistrySnapshot freeze(Contribution.Side environment, ApiVersion api,
            List<ModuleSpec> modules, List<Contribution> contributions) {
        RegistryBuilder builder = new RegistryBuilder(api, environment);
        modules.forEach(builder::addModule);
        contributions.forEach(builder::add);
        return builder.freeze();
    }

    private static RegistrySnapshot freeze(Contribution.Side environment,
            List<ModuleSpec> modules, List<Contribution> contributions) {
        return freeze(environment, API_1_2, modules, contributions);
    }

    /** A real {@code dependency_unavailable} produced when a module needs an unavailable module. */
    private static Problem producedModuleDependency() {
        RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                List.of(module("app", API_1_2, "absent"), module("plugin", API_1_2, "app")), List.of());
        return problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
    }

    /** A real {@code dependency_unavailable} produced when a contribution needs an unavailable one. */
    private static Problem producedContributionDependency() {
        RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(module("app", API_1_2)),
                List.of(
                        contribution("a", "app", Contribution.Side.COMMON, key("b")),
                        contribution("b", "app", Contribution.Side.COMMON, key("a")),
                        contribution("dependent", "app", Contribution.Side.COMMON, key("a"))));
        return problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
    }

    /** Renders a produced diagnostic with the sentence its own message key names. */
    private static String render(Map<String, String> language, Problem problem) {
        String template = language.get(keyOf(problem));
        if (template == null) {
            throw new AssertionError("no sentence for " + keyOf(problem));
        }
        return LangResources.render((TextContent.Message) problem.message(), template);
    }

    @Nested
    @DisplayName("value contracts")
    class Values {
        @Test
        void apiVersionIsNonNegativeAndCompatibleBySameMajorAndSufficientMinor() {
            assertThrows(IllegalArgumentException.class, () -> new ApiVersion(-1, 0));
            assertThrows(IllegalArgumentException.class, () -> new ApiVersion(1, -1));
            assertTrue(new ApiVersion(1, 5).isCompatibleWith(new ApiVersion(1, 2)));
            assertTrue(new ApiVersion(1, 2).isCompatibleWith(new ApiVersion(1, 2)));
            assertFalse(new ApiVersion(1, 1).isCompatibleWith(new ApiVersion(1, 2)));
            assertFalse(new ApiVersion(2, 9).isCompatibleWith(new ApiVersion(1, 0)));
        }

        @Test
        void moduleSpecCopiesDependenciesAndRejectsBlankVersion() {
            Set<EditorId> required = new HashSet<>();
            required.add(id("a"));
            ModuleSpec spec = new ModuleSpec(id("m"), "1.0", API_1_2, required, Set.of());
            required.add(id("b"));
            assertEquals(Set.of(id("a")), spec.requiredModules());
            assertThrows(UnsupportedOperationException.class, () -> spec.requiredModules().add(id("c")));
            assertThrows(IllegalArgumentException.class,
                    () -> new ModuleSpec(id("m"), " ", API_1_2, Set.of(), Set.of()));
        }

        @Test
        void contributionKeyIsTwoDimensional() {
            assertFalse(new ContributionKey(id("panel"), id("x"))
                    .equals(new ContributionKey(id("tool"), id("x"))));
            assertFalse(new ContributionKey(id("panel"), id("x"))
                    .equals(new ContributionKey(id("panel"), id("y"))));
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {
        @Test
        void reversedRegistrationOrderIsEquivalent() {
            List<ModuleSpec> modules = List.of(
                    module("app", API_1_2, "lib", "ui"),
                    module("lib", API_1_2),
                    module("ui", API_1_2),
                    module("ghost", new ApiVersion(2, 0)));
            List<Contribution> contributions = List.of(
                    new Contribution(key("tab"), id("ui"), Contribution.Side.COMMON, Set.of(key("base"))),
                    new Contribution(key("base"), id("lib"), Contribution.Side.COMMON, Set.of()));
            List<Contribution> reversedContributions = new ArrayList<>(contributions);
            java.util.Collections.reverse(reversedContributions);
            List<ModuleSpec> reversedModules = new ArrayList<>(modules);
            java.util.Collections.reverse(reversedModules);

            RegistrySnapshot forward = freeze(Contribution.Side.CLIENT, modules, contributions);
            RegistrySnapshot backward = freeze(Contribution.Side.CLIENT, reversedModules, reversedContributions);

            assertEquals(forward.modules().keySet(), backward.modules().keySet());
            assertEquals(new ArrayList<>(forward.modules().keySet()),
                    new ArrayList<>(backward.modules().keySet()));
            assertEquals(new ArrayList<>(forward.contributions().keySet()),
                    new ArrayList<>(backward.contributions().keySet()));
            assertEquals(forward.problems(), backward.problems());
            assertEquals(List.of(id("app"), id("lib"), id("ui")), new ArrayList<>(forward.modules().keySet()));
            assertEquals(3, forward.modules().size());
        }
    }

    @Nested
    @DisplayName("module availability")
    class Modules {
        @Test
        void missingOptionalModuleKeepsTheModuleUsable() {
            ModuleSpec spec = new ModuleSpec(id("app"), "1.0", API_1_2, Set.of(), Set.of(id("absent")));
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(spec), List.of());
            assertTrue(snapshot.modules().containsKey(id("app")));
            assertTrue(snapshot.problems().isEmpty());
        }

        @Test
        void missingRequiredModuleFailsAndPropagatesToDependents() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(
                    module("app", API_1_2, "core"),
                    module("plugin", API_1_2, "app")), List.of());

            assertTrue(snapshot.modules().isEmpty());
            Problem missing = problem(snapshot, Messages.MISSING_MODULE).orElseThrow();
            assertEquals(Messages.KEY_MISSING_MODULE, keyOf(missing));
            assertEquals("editor:app", missing.target());
            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals("editor:plugin", propagated.target());
            assertEquals(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of("editor:plugin", "editor:app", "CLIENT"), arguments(propagated));
        }

        @Test
        void incompatibleApiIsRejectedByMajorAndMinor() {
            // The registry provides 1.2: a module needing more minor version and a module needing a
            // different major version are both rejected, while a module needing 1.2 itself is fine.
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(
                    module("needs-more", new ApiVersion(1, 3)),
                    module("future", new ApiVersion(2, 0)),
                    module("exact", new ApiVersion(1, 2))), List.of());

            assertEquals(Set.of(id("exact")), snapshot.modules().keySet());
            assertEquals(2, snapshot.problems().size());
            snapshot.problems().forEach(problem -> {
                assertEquals(Messages.INCOMPATIBLE_API, problem.code());
                assertEquals(Messages.KEY_INCOMPATIBLE_API, keyOf(problem));
            });
            assertTrue(snapshot.problems().stream()
                    .anyMatch(problem -> problem.target().equals("editor:needs-more")));
            assertTrue(snapshot.problems().stream().anyMatch(problem -> problem.target().equals("editor:future")));
        }

        @Test
        void realModuleCycleIsRejectedAndPropagates() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(
                    module("a", API_1_2, "b"),
                    module("b", API_1_2, "a"),
                    module("c", API_1_2, "a"),
                    module("safe", API_1_2)), List.of());

            assertEquals(Set.of(id("safe")), snapshot.modules().keySet());
            assertEquals(2, snapshot.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.MODULE_CYCLE)).count());
            Problem propagated = snapshot.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.DEPENDENCY_UNAVAILABLE))
                    .findFirst().orElseThrow();
            assertEquals("editor:c", propagated.target());
        }

        @Test
        void selfRequiredModuleIsACycle() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("loop", API_1_2, "loop")), List.of());
            assertTrue(snapshot.modules().isEmpty());
            assertEquals(Messages.MODULE_CYCLE, snapshot.problems().get(0).code());
        }

        @Test
        void duplicateModuleIdEmitsOneDiagnosticAndIgnoresConflictingMetadata() {
            // The two conflicting declarations disagree about API and dependencies: one is
            // incompatible with this builder and requires an absent module, the other is compatible.
            // Neither may steer resolution, so neither of their diagnostics may appear.
            ModuleSpec incompatible = new ModuleSpec(id("dup"), "1.0.0", new ApiVersion(2, 0),
                    Set.of(id("absent")), Set.of());
            ModuleSpec compatible = module("dup", API_1_2, "safe");
            List<ModuleSpec> records = List.of(incompatible, compatible,
                    module("dependent", API_1_2, "dup"), module("safe", API_1_2));

            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, records, List.of());

            // A repeated id is a conflict, not an input error: exactly one diagnostic, and no record
            // with that id is accepted.
            assertEquals(Set.of(id("safe")), snapshot.modules().keySet());
            assertEquals(1, snapshot.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.DUPLICATE_MODULE)).count());
            assertTrue(problem(snapshot, Messages.INCOMPATIBLE_API).isEmpty());
            assertTrue(problem(snapshot, Messages.MISSING_MODULE).isEmpty());
            // A dependent of the conflicting id is unavailable like any other broken dependency.
            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals("editor:dependent", propagated.target());
            // The duplicate id is a module, so the sentence itself carries the category and only the
            // three technical arguments remain.
            assertEquals(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of("editor:dependent", "editor:dup", "CLIENT"), arguments(propagated));
        }

        @Test
        void conflictingDuplicateModuleMetadataResolvesIdenticallyInEitherOrder() {
            ModuleSpec first = new ModuleSpec(id("dup"), "2.0.0", new ApiVersion(1, 0),
                    Set.of(id("lib")), Set.of());
            ModuleSpec second = new ModuleSpec(id("dup"), "3.0.0", new ApiVersion(2, 0),
                    Set.of(id("absent")), Set.of(id("gone")));
            ModuleSpec dependent = module("dependent", API_1_2, "dup");
            ModuleSpec lib = module("lib", API_1_2);

            RegistrySnapshot forward = freeze(Contribution.Side.CLIENT,
                    List.of(first, second, dependent, lib), List.of());
            RegistrySnapshot backward = freeze(Contribution.Side.CLIENT,
                    List.of(second, first, lib, dependent), List.of());

            assertEquals(forward, backward);
            assertEquals(Set.of(id("lib")), forward.modules().keySet());
        }

        @Test
        void propagatedFailureNamesTheFirstUnavailableDependencyInSortedOrder() {
            ModuleSpec mixed = module("mixed", API_1_2, "root_b", "root_a");
            ModuleSpec rootA = module("root_a", API_1_2, "absent_a");
            ModuleSpec rootB = module("root_b", API_1_2, "absent_b");

            RegistrySnapshot forward = freeze(Contribution.Side.CLIENT,
                    List.of(mixed, rootA, rootB), List.of());
            RegistrySnapshot backward = freeze(Contribution.Side.CLIENT,
                    List.of(rootB, mixed, rootA), List.of());

            assertEquals(forward, backward);
            Problem propagated = forward.problems().stream()
                    .filter(problem -> problem.target().equals("editor:mixed"))
                    .findFirst().orElseThrow();
            assertEquals(Messages.DEPENDENCY_UNAVAILABLE, propagated.code());
            assertEquals("editor:root_a", arguments(propagated).get(1));
        }

        @Test
        void reversedFailureChainResolvesIdenticallyInEitherOrder() {
            List<ModuleSpec> modules = new ArrayList<>();
            modules.add(module("root", API_1_2, "absent"));
            for (int index = 1; index < 600; index++) {
                modules.add(module("node" + index, API_1_2, "node" + (index - 1)));
            }
            List<ModuleSpec> reversed = new ArrayList<>(modules);
            Collections.reverse(reversed);

            RegistrySnapshot forward = freeze(Contribution.Side.CLIENT, modules, List.of());
            RegistrySnapshot backward = freeze(Contribution.Side.CLIENT, reversed, List.of());

            assertEquals(forward, backward);
            assertTrue(forward.modules().isEmpty());
            assertEquals(modules.size(), forward.problems().size());
        }

        @Test
        void problemsWithEqualCodeAndTargetAreOrderedByArguments() {
            Set<EditorId> wide = new HashSet<>();
            wide.add(id("dep_b"));
            wide.add(id("dep_a"));
            ModuleSpec search = new ModuleSpec(id("wide"), "1.0", API_1_2, wide, Set.of());

            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(search), List.of());

            List<Problem> missing = snapshot.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.MISSING_MODULE))
                    .toList();
            assertEquals(2, missing.size());
            // Same code and same target: only the typed arguments keep the order fixed.
            assertEquals(List.of("editor:dep_a", "editor:dep_b"),
                    missing.stream().map(problem -> arguments(problem).get(1)).toList());
        }

        @Test
        void longRequiredChainDoesNotOverflowTheStack() {
            int length = 4000;
            List<ModuleSpec> modules = new ArrayList<>(length + 1);
            modules.add(module("link0", API_1_2));
            for (int index = 1; index < length; index++) {
                modules.add(module("link" + index, API_1_2, "link" + (index - 1)));
            }
            modules.add(module("tail", API_1_2, "absent"));

            RegistrySnapshot snapshot = assertDoesNotThrow(
                    () -> freeze(Contribution.Side.CLIENT, modules, List.of()));
            assertEquals(length, snapshot.modules().size());
            assertFalse(snapshot.modules().containsKey(id("tail")));
            assertEquals(1, snapshot.problems().size());
        }

        @Test
        void longFailingChainPropagatesWithoutRecursion() {
            int length = 2000;
            List<ModuleSpec> modules = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                modules.add(index == 0
                        ? module("root", API_1_2, "absent")
                        : module("node" + index, API_1_2, "node" + (index - 1)));
            }
            modules.set(1, module("node1", API_1_2, "root"));

            RegistrySnapshot snapshot = assertDoesNotThrow(
                    () -> freeze(Contribution.Side.CLIENT, modules, List.of()));
            assertTrue(snapshot.modules().isEmpty());
            assertEquals(length, snapshot.problems().size());
        }
    }

    @Nested
    @DisplayName("contribution availability")
    class Contributions {
        @Test
        void missingOwnerIsRejected() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("app", API_1_2)),
                    List.of(contribution("tab", "ghost", Contribution.Side.COMMON)));

            assertTrue(snapshot.contributions().isEmpty());
            Problem owner = problem(snapshot, Messages.MISSING_OWNER).orElseThrow();
            assertEquals(Messages.KEY_MISSING_OWNER, keyOf(owner));
            assertEquals("editor:tab#editor:tab/main", owner.target());
        }

        @Test
        void contributionWhoseOwnerIsUnavailableIsRejected() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("app", new ApiVersion(2, 0))),
                    List.of(contribution("tab", "app", Contribution.Side.COMMON)));

            assertTrue(snapshot.contributions().isEmpty());
            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals(key("tab").toString(), propagated.target());
            assertEquals(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of(key("tab").toString(), "editor:app", "CLIENT"), arguments(propagated));
        }

        @Test
        void missingRequiredContributionIsRejected() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("app", API_1_2)),
                    List.of(contribution("tab", "app", Contribution.Side.COMMON, key("base"))));

            assertTrue(snapshot.contributions().isEmpty());
            Problem missing = problem(snapshot, Messages.MISSING_CONTRIBUTION).orElseThrow();
            assertEquals(Messages.KEY_MISSING_CONTRIBUTION, keyOf(missing));
        }

        @Test
        void realContributionCycleIsRejectedAndPropagates() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("app", API_1_2)),
                    List.of(
                            contribution("a", "app", Contribution.Side.COMMON, key("b")),
                            contribution("b", "app", Contribution.Side.COMMON, key("a")),
                            contribution("c", "app", Contribution.Side.COMMON, key("a")),
                            contribution("safe", "app", Contribution.Side.COMMON)));

            assertEquals(Set.of(key("safe")), snapshot.contributions().keySet());
            assertEquals(2, snapshot.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.CONTRIBUTION_CYCLE)).count());
            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals(key("c").toString(), propagated.target());
            assertEquals(Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of(key("c").toString(), key("a").toString(), "CLIENT"),
                    arguments(propagated));
        }

        @Test
        void conflictingDuplicateContributionKeyResolvesIdenticallyInEitherOrder() {
            // The conflicting declarations disagree about owner, side and dependencies. In the COMMON
            // environment a leaked CLIENT record would add wrong_side, a leaked unknown owner
            // missing_owner and a leaked dependency missing_contribution.
            Contribution unknownOwner = new Contribution(key("tab"), id("ghost"),
                    Contribution.Side.COMMON, Set.of());
            Contribution clientSide = new Contribution(key("tab"), id("app"),
                    Contribution.Side.CLIENT, Set.of(key("ghost")));
            Contribution dependent = contribution("dependent", "app", Contribution.Side.COMMON, key("tab"));
            Contribution safe = contribution("safe", "app", Contribution.Side.COMMON);
            List<ModuleSpec> modules = List.of(module("app", API_1_2));

            RegistrySnapshot forward = freeze(Contribution.Side.COMMON, modules,
                    List.of(unknownOwner, clientSide, dependent, safe));
            RegistrySnapshot backward = freeze(Contribution.Side.COMMON, modules,
                    List.of(clientSide, safe, dependent, unknownOwner));

            assertEquals(forward, backward);
            assertEquals(Set.of(key("safe")), forward.contributions().keySet());
            assertEquals(1, forward.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.DUPLICATE_CONTRIBUTION)).count());
            assertTrue(problem(forward, Messages.MISSING_OWNER).isEmpty());
            assertTrue(problem(forward, Messages.MISSING_CONTRIBUTION).isEmpty());
            assertTrue(problem(forward, Messages.WRONG_SIDE).isEmpty());
            Problem propagated = problem(forward, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals(key("dependent").toString(), propagated.target());
            assertEquals(Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of(key("dependent").toString(), key("tab").toString(), "COMMON"),
                    arguments(propagated));
        }

        @Test
        void commonEnvironmentDoesNotPublishClientContributions() {
            ModuleSpec app = module("app", API_1_2);
            Contribution client = contribution("hud", "app", Contribution.Side.CLIENT);
            Contribution common = contribution("tab", "app", Contribution.Side.COMMON);

            RegistrySnapshot commonSide = freeze(Contribution.Side.COMMON,
                    List.of(app), List.of(client, common));
            assertEquals(Set.of(key("tab")), commonSide.contributions().keySet());
            Problem wrongSide = problem(commonSide, Messages.WRONG_SIDE).orElseThrow();
            assertEquals(key("hud").toString(), wrongSide.target());
            assertFalse(commonSide.contributions().containsKey(key("hud")));
            // The message is `wrong_side{contributionKey, side, environment}`: the contribution's own
            // side precedes the environment that does not publish it.
            assertEquals(Messages.KEY_WRONG_SIDE, keyOf(wrongSide));
            assertEquals(List.of(key("hud").toString(), "CLIENT", "COMMON"), arguments(wrongSide));

            RegistrySnapshot clientSide = freeze(Contribution.Side.CLIENT,
                    List.of(app), List.of(client, common));
            assertEquals(Set.of(key("hud"), key("tab")), clientSide.contributions().keySet());
            assertTrue(clientSide.problems().isEmpty());
        }

        @Test
        void commonContributionRequiringAClientContributionIsRejectedInBothEnvironments() {
            ModuleSpec app = module("app", API_1_2);
            Contribution client = contribution("hud", "app", Contribution.Side.CLIENT);
            Contribution common = contribution("tab", "app", Contribution.Side.COMMON, key("hud"));

            RegistrySnapshot commonSide = freeze(Contribution.Side.COMMON,
                    List.of(app), List.of(client, common));
            assertTrue(commonSide.contributions().isEmpty());
            Problem commonProblem = commonSide.problems().stream()
                    .filter(problem -> problem.target().equals(key("tab").toString()))
                    .findFirst().orElseThrow();
            assertEquals(Messages.WRONG_SIDE, commonProblem.code());

            // The client environment publishes CLIENT contributions, but the layering rule still
            // rejects a COMMON contribution that requires one.
            RegistrySnapshot clientSide = freeze(Contribution.Side.CLIENT,
                    List.of(app), List.of(client, common));
            assertEquals(Set.of(key("hud")), clientSide.contributions().keySet());
            Problem clientProblem = clientSide.problems().stream()
                    .filter(problem -> problem.target().equals(key("tab").toString()))
                    .findFirst().orElseThrow();
            assertEquals(Messages.WRONG_SIDE, clientProblem.code());
            assertTrue(clientProblem.message() instanceof TextContent.Message message
                    && message.key().equals(Messages.KEY_COMMON_REQUIRES_CLIENT));
        }
    }

    @Nested
    @DisplayName("dependency sentences")
    class DependencySentences {
        @Test
        void unavailableModuleDependencyUsesTheModuleSentenceAndThreeArguments() {
            List<ModuleSpec> modules = List.of(
                    module("app", API_1_2, "absent"),
                    module("plugin", API_1_2, "app"));

            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, modules, List.of());

            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals("editor:plugin", propagated.target());
            assertEquals(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of("editor:plugin", "editor:app", "CLIENT"), arguments(propagated));
            // The same graph registered in reverse order resolves to exactly the same diagnostics.
            assertEquals(snapshot, freeze(Contribution.Side.CLIENT,
                    List.of(modules.get(1), modules.get(0)), List.of()));
        }

        @Test
        void duplicateOwnerOfAContributionUsesTheModuleSentence() {
            ModuleSpec first = module("dup", API_1_2);
            ModuleSpec second = new ModuleSpec(id("dup"), "2.0.0", API_1_2, Set.of(), Set.of());
            Contribution tab = contribution("tab", "dup", Contribution.Side.COMMON);

            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(first, second),
                    List.of(tab));

            assertTrue(snapshot.contributions().isEmpty());
            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals(key("tab").toString(), propagated.target());
            assertEquals(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of(key("tab").toString(), "editor:dup", "CLIENT"), arguments(propagated));
            assertEquals(snapshot,
                    freeze(Contribution.Side.CLIENT, List.of(second, first), List.of(tab)));
        }

        @Test
        void unavailableOwnerOfAContributionUsesTheModuleSentence() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("app", new ApiVersion(2, 0))),
                    List.of(contribution("tab", "app", Contribution.Side.COMMON)));

            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals(key("tab").toString(), propagated.target());
            assertEquals(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of(key("tab").toString(), "editor:app", "CLIENT"), arguments(propagated));
        }

        @Test
        void unavailableRequiredContributionUsesTheContributionSentence() {
            List<Contribution> contributions = List.of(
                    contribution("a", "app", Contribution.Side.COMMON, key("b")),
                    contribution("b", "app", Contribution.Side.COMMON, key("a")),
                    contribution("c", "app", Contribution.Side.COMMON, key("a")));

            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("app", API_1_2)), contributions);

            Problem propagated = problem(snapshot, Messages.DEPENDENCY_UNAVAILABLE).orElseThrow();
            assertEquals(key("c").toString(), propagated.target());
            assertEquals(Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE, keyOf(propagated));
            assertEquals(List.of(key("c").toString(), key("a").toString(), "CLIENT"),
                    arguments(propagated));
            List<Contribution> reversed = new ArrayList<>(contributions);
            Collections.reverse(reversed);
            assertEquals(snapshot, freeze(Contribution.Side.CLIENT,
                    List.of(module("app", API_1_2)), reversed));
        }

        @Test
        void bothLanguagesCarryOneCompleteSentencePerCategoryAndNoGenericKey() {
            Map<String, String> zh = LangResources.load("zh_cn");
            Map<String, String> en = LangResources.load("en_us");
            Set<String> expected = Set.of(
                    Messages.KEY_DUPLICATE_MODULE,
                    Messages.KEY_INCOMPATIBLE_API,
                    Messages.KEY_MISSING_MODULE,
                    Messages.KEY_MODULE_CYCLE,
                    Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE,
                    Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE,
                    Messages.KEY_DUPLICATE_CONTRIBUTION,
                    Messages.KEY_MISSING_OWNER,
                    Messages.KEY_MISSING_CONTRIBUTION,
                    Messages.KEY_CONTRIBUTION_CYCLE,
                    Messages.KEY_WRONG_SIDE,
                    Messages.KEY_COMMON_REQUIRES_CLIENT);

            assertEquals(expected, zh.keySet());
            assertEquals(expected, en.keySet());
            assertFalse(zh.containsKey("editor.registry.dependency_unavailable"));

            for (Map<String, String> language : List.of(zh, en)) {
                assertEquals(Set.of(0, 1, 2), LangResources.placeholders(
                        language.get(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE)));
                assertEquals(Set.of(0, 1, 2), LangResources.placeholders(
                        language.get(Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE)));
            }
            // The category is carried by the whole sentence, so the Chinese text contains no English
            // category word at all: the two sentences differ exactly in the category noun.
            assertFalse(zh.get(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE).contains("module"));
            assertFalse(zh.get(Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE).contains("contribution"));
            assertFalse(zh.get(Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE).contains("module"));
            assertFalse(zh.get(Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE).contains("contribution"));
        }

        @Test
        void producedProblemsRenderBothSentencesWithBothLanguages() {
            Map<String, String> zh = LangResources.load("zh_cn");
            Map<String, String> en = LangResources.load("en_us");
            Problem moduleProblem = producedModuleDependency();
            Problem contributionProblem = producedContributionDependency();

            String zhModule = render(zh, moduleProblem);
            assertEquals("注册项 editor:plugin 依赖模块 editor:app，该模块在 CLIENT 环境中不可用。", zhModule);
            String zhContribution = render(zh, contributionProblem);
            assertEquals("注册项 " + key("dependent") + " 依赖贡献 " + key("a")
                    + "，该贡献在 CLIENT 环境中不可用。", zhContribution);
            assertFalse(zhModule.contains("module") || zhModule.contains("contribution"));
            assertFalse(zhContribution.contains("module") || zhContribution.contains("contribution"));

            assertEquals("Registration editor:plugin depends on module editor:app, "
                    + "which is not available in the CLIENT environment.", render(en, moduleProblem));
            assertEquals("Registration " + key("dependent") + " depends on contribution " + key("a")
                    + ", which is not available in the CLIENT environment.",
                    render(en, contributionProblem));
        }
    }

    @Nested
    @DisplayName("snapshot and limits")
    class SnapshotRules {
        @Test
        void snapshotCollectionsAreUnmodifiable() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT,
                    List.of(module("app", API_1_2)),
                    List.of(contribution("tab", "app", Contribution.Side.COMMON)));

            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.modules().put(id("x"), module("x", API_1_2)));
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.contributions().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.problems().clear());
        }

        @Test
        void repeatedFreezeIsRejected() {
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            builder.freeze();
            assertThrows(IllegalStateException.class, builder::freeze);
            assertThrows(IllegalStateException.class, () -> builder.addModule(module("late", API_1_2)));
            assertThrows(IllegalStateException.class,
                    () -> builder.add(contribution("late", "app", Contribution.Side.COMMON)));
        }

        @Test
        void builderCopiesSourceModuleSetBeforeFreezing() {
            Set<EditorId> required = new HashSet<>();
            required.add(id("lib"));
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            builder.addModule(module("lib", API_1_2));
            builder.addModule(new ModuleSpec(id("app"), "1.0", API_1_2, required, Set.of()));
            required.add(id("mutated"));
            RegistrySnapshot snapshot = builder.freeze();
            assertEquals(Set.of(id("lib")), snapshot.modules().get(id("app")).requiredModules());
            assertTrue(snapshot.modules().containsKey(id("lib")));
            assertTrue(snapshot.problems().isEmpty());
        }

        @Test
        void unknownOwnerAndUnknownDependencyAppearOnlyAsDiagnostics() {
            RegistrySnapshot snapshot = freeze(Contribution.Side.CLIENT, List.of(), List.of(
                    new Contribution(key("tab"), id("ghost"), Contribution.Side.COMMON, Set.of(key("none")))));
            assertTrue(snapshot.modules().isEmpty());
            assertTrue(snapshot.contributions().isEmpty());
            assertEquals(2, snapshot.problems().size());
            assertEquals(Messages.MISSING_CONTRIBUTION, snapshot.problems().get(0).code());
            assertEquals(Messages.MISSING_OWNER, snapshot.problems().get(1).code());
        }

        @Test
        void moduleLimitIsEnforcedDuringCollection() {
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            for (int index = 0; index < RegistryBuilder.MAX_MODULES; index++) {
                builder.addModule(module("m" + index, API_1_2));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> builder.addModule(module("overflow", API_1_2)));
            assertEquals(RegistryBuilder.MAX_MODULES, builder.freeze().modules().size());
        }

        @Test
        void contributionLimitIsEnforcedDuringCollection() {
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            builder.addModule(module("app", API_1_2));
            for (int index = 0; index < RegistryBuilder.MAX_CONTRIBUTIONS; index++) {
                builder.add(new Contribution(new ContributionKey(id("kind"), id("c" + index)), id("app"),
                        Contribution.Side.COMMON, Set.of()));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> builder.add(new Contribution(new ContributionKey(id("kind"), id("overflow")),
                            id("app"), Contribution.Side.COMMON, Set.of())));
            assertEquals(RegistryBuilder.MAX_CONTRIBUTIONS, builder.freeze().contributions().size());
        }

        @Test
        void moduleRecordLimitCountsRepeats() {
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            for (int index = 0; index < RegistryBuilder.MAX_MODULES; index++) {
                builder.addModule(module("dup", API_1_2));
            }
            // Repeats are records too, so a conflicting flood cannot slip past the bound by sharing
            // one id: the 8193rd registration is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> builder.addModule(module("dup", API_1_2)));

            RegistrySnapshot snapshot = builder.freeze();
            assertTrue(snapshot.modules().isEmpty());
            assertEquals(1, snapshot.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.DUPLICATE_MODULE)).count());
        }

        @Test
        void contributionRecordLimitCountsRepeats() {
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            builder.addModule(module("app", API_1_2));
            for (int index = 0; index < RegistryBuilder.MAX_CONTRIBUTIONS; index++) {
                builder.add(contribution("dup", "app", Contribution.Side.COMMON));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> builder.add(contribution("dup", "app", Contribution.Side.COMMON)));

            RegistrySnapshot snapshot = builder.freeze();
            assertTrue(snapshot.contributions().isEmpty());
            assertEquals(1, snapshot.problems().stream()
                    .filter(problem -> problem.code().equals(Messages.DUPLICATE_CONTRIBUTION)).count());
        }

        @Test
        void rejectedDuplicateAddLeavesCollectedRecordsIntact() {
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            int perModule = 64;
            int bulk = RegistryBuilder.MAX_DEPENDENCY_EDGES / perModule;
            builder.addModule(module("anchor", API_1_2));
            builder.add(contribution("base", "anchor", Contribution.Side.COMMON));
            for (int index = 0; index < bulk; index++) {
                Set<EditorId> required = new HashSet<>();
                for (int edge = 0; edge < perModule; edge++) {
                    required.add(id("dep" + index + "_" + edge));
                }
                builder.addModule(new ModuleSpec(id("bulk" + index), "1.0", API_1_2, required, Set.of()));
            }
            // The budget is full, so both repeats fail. They fail before touching any collected record:
            // the earlier valid declarations must survive, and no conflict may be recorded for them.
            assertThrows(IllegalArgumentException.class,
                    () -> builder.addModule(module("anchor", API_1_2, "one")));
            assertThrows(IllegalArgumentException.class,
                    () -> builder.add(contribution("base", "anchor", Contribution.Side.COMMON, key("one"))));

            RegistrySnapshot snapshot = builder.freeze();
            assertTrue(snapshot.modules().containsKey(id("anchor")));
            assertTrue(snapshot.contributions().containsKey(key("base")));
            assertTrue(problem(snapshot, Messages.DUPLICATE_MODULE).isEmpty());
            assertTrue(problem(snapshot, Messages.DUPLICATE_CONTRIBUTION).isEmpty());
        }

        @Test
        void dependencyEdgeBudgetIsEnforcedDuringCollection() {
            RegistryBuilder builder = new RegistryBuilder(API_1_2, Contribution.Side.CLIENT);
            int perModule = 64;
            int bulk = RegistryBuilder.MAX_DEPENDENCY_EDGES / perModule;
            for (int index = 0; index < bulk; index++) {
                Set<EditorId> required = new HashSet<>();
                for (int edge = 0; edge < perModule; edge++) {
                    required.add(id("dep" + index + "_" + edge));
                }
                builder.addModule(new ModuleSpec(id("bulk" + index), "1.0", API_1_2, required, Set.of()));
            }
            // The budget is exactly exhausted: an edge-free module still fits, one more edge does not.
            builder.addModule(module("exact", API_1_2));
            assertThrows(IllegalArgumentException.class,
                    () -> builder.addModule(module("over", API_1_2, "one")));
            // Contributions draw on the same budget, so a single required contribution no longer fits.
            assertThrows(IllegalArgumentException.class,
                    () -> builder.add(new Contribution(key("tab"), id("bulk0"), Contribution.Side.COMMON,
                            Set.of(key("base")))));
            RegistrySnapshot snapshot = builder.freeze();
            // The edge-free module is inside the budget, so it survives collection.
            assertTrue(snapshot.modules().containsKey(id("exact")));
            assertFalse(snapshot.modules().containsKey(id("over")));
        }
    }
}
