package net.ptcrys.fpsmatch.editor.api.registry;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Collects module and contribution declarations, then freezes them into one {@link RegistrySnapshot}.
 *
 * <p>
 * The builder is single-use: after {@link #freeze()} every further {@code add*} or {@code freeze()}
 * call is rejected with {@link IllegalStateException} and changes nothing. Every input limit is checked
 * before the first internal write of that call, so a rejected call never poisons the records that were
 * already collected: a module that was accepted before a rejected duplicate add stays usable.
 * </p>
 *
 * <p>
 * A repeated module id or contribution key is a conflict, not an input error. The conflict is
 * recorded while collecting, and resolution emits exactly one {@code duplicate_*} diagnostic for that
 * id or key. No conflicting declaration is used as a graph node: neither its required API, nor its
 * owner, side or dependencies are allowed to influence the graph or any other diagnostic, so the
 * snapshot never depends on which of the conflicting declarations happened to arrive last. Every
 * declaration that requires the conflicting id or key becomes unavailable through the same closure as
 * any other unavailable dependency.
 * </p>
 *
 * <p>
 * Hard bounds: at most {@value #MAX_MODULES} module records, {@value #MAX_CONTRIBUTIONS}
 * contribution records, counted per accepted record including repeats, and at most
 * {@value #MAX_DEPENDENCY_EDGES} dependency edges. Module required plus optional edges and
 * contribution required edges share the single edge budget, and repeated records contribute their
 * edges to it exactly like unique ones.
 * </p>
 */
public final class RegistryBuilder {

    static final int MAX_MODULES = 8192;
    static final int MAX_CONTRIBUTIONS = 8192;
    /** Module required plus optional edges and contribution required edges share this one budget. */
    static final int MAX_DEPENDENCY_EDGES = 65536;

    private final ApiVersion api;
    private final Contribution.Side environment;

    private final List<ModuleSpec> moduleRecords = new ArrayList<>();
    private final Set<EditorId> seenModules = new TreeSet<>();
    private final Set<EditorId> duplicateModules = new TreeSet<>();

    private final List<Contribution> contributionRecords = new ArrayList<>();
    private final Set<ContributionKey> seenContributions = new TreeSet<>();
    private final Set<ContributionKey> duplicateContributions = new TreeSet<>();

    private int moduleEdges;
    private int contributionEdges;
    private boolean frozen;

    public RegistryBuilder(ApiVersion api, Contribution.Side environment) {
        this.api = Objects.requireNonNull(api, "api");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    private void requireOpen() {
        if (frozen) {
            throw new IllegalStateException("registry builder is already frozen");
        }
    }

    private static void requireEdgeBudget(long edges) {
        if (edges > MAX_DEPENDENCY_EDGES) {
            throw new IllegalArgumentException("too many dependency edges: " + MAX_DEPENDENCY_EDGES);
        }
    }

    /**
     * Registers one module record. A repeated module id is a conflict, not an input error: the id is
     * remembered as conflicting and no declaration with that id is ever used as a graph node.
     */
    public void addModule(ModuleSpec spec) {
        requireOpen();
        Objects.requireNonNull(spec, "spec");
        if (moduleRecords.size() >= MAX_MODULES) {
            throw new IllegalArgumentException("too many modules: " + MAX_MODULES);
        }
        int edges = spec.requiredModules().size() + spec.optionalModules().size();
        requireEdgeBudget((long) moduleEdges + contributionEdges + edges);
        moduleRecords.add(spec);
        if (!seenModules.add(spec.id())) {
            duplicateModules.add(spec.id());
        }
        moduleEdges += edges;
    }

    /** Registers one contribution record; a repeated key is a conflict resolved exactly like a module id. */
    public void add(Contribution contribution) {
        requireOpen();
        Objects.requireNonNull(contribution, "contribution");
        if (contributionRecords.size() >= MAX_CONTRIBUTIONS) {
            throw new IllegalArgumentException("too many contributions: " + MAX_CONTRIBUTIONS);
        }
        int edges = contribution.requiredContributions().size();
        requireEdgeBudget((long) moduleEdges + contributionEdges + edges);
        contributionRecords.add(contribution);
        if (!seenContributions.add(contribution.key())) {
            duplicateContributions.add(contribution.key());
        }
        contributionEdges += edges;
    }

    /** Resolves availability and returns the immutable snapshot; valid exactly once. */
    public RegistrySnapshot freeze() {
        requireOpen();
        frozen = true;

        List<ModuleSpec> modules = new ArrayList<>();
        Map<EditorId, Integer> moduleOf = new HashMap<>();
        for (ModuleSpec spec : moduleRecords) {
            if (duplicateModules.contains(spec.id())) {
                continue;
            }
            moduleOf.put(spec.id(), modules.size());
            modules.add(spec);
        }

        List<Contribution> contributions = new ArrayList<>();
        Map<ContributionKey, Integer> contributionOf = new HashMap<>();
        for (Contribution contribution : contributionRecords) {
            if (duplicateContributions.contains(contribution.key())) {
                continue;
            }
            contributionOf.put(contribution.key(), contributions.size());
            contributions.add(contribution);
        }

        List<Problem> problems = new ArrayList<>();
        boolean[] moduleUsable = resolveModules(modules, moduleOf, problems);
        boolean[] contributionUsable = resolveContributions(
                contributions, contributionOf, moduleOf, moduleUsable, problems);

        Map<EditorId, ModuleSpec> acceptedModules = new TreeMap<>();
        for (int index = 0; index < modules.size(); index++) {
            if (moduleUsable[index]) {
                ModuleSpec spec = modules.get(index);
                acceptedModules.put(spec.id(), spec);
            }
        }
        Map<ContributionKey, Contribution> acceptedContributions = new TreeMap<>();
        for (int index = 0; index < contributions.size(); index++) {
            if (contributionUsable[index]) {
                Contribution contribution = contributions.get(index);
                acceptedContributions.put(contribution.key(), contribution);
            }
        }

        problems.sort(Messages.order());
        return new RegistrySnapshot(acceptedModules, acceptedContributions, problems);
    }

    // ------------------------------------------------------------------ modules

    private boolean[] resolveModules(
                                     List<ModuleSpec> modules, Map<EditorId, Integer> moduleOf, List<Problem> problems) {
        int count = modules.size();

        // Exactly one diagnostic per conflicting id, emitted independently of every conflicting spec.
        for (EditorId duplicate : duplicateModules) {
            problems.add(Messages.problem(Messages.DUPLICATE_MODULE, duplicate.toString(),
                    Messages.KEY_DUPLICATE_MODULE, duplicate.toString()));
        }

        List<List<Problem>> own = new ArrayList<>(count);
        boolean[] failed = new boolean[count];
        for (int index = 0; index < count; index++) {
            own.add(new ArrayList<>());
            ModuleSpec spec = modules.get(index);
            if (!api.isCompatibleWith(spec.requiredApi())) {
                fail(own, failed, index, Messages.INCOMPATIBLE_API, spec.id().toString(),
                        Messages.KEY_INCOMPATIBLE_API, spec.id().toString(),
                        spec.requiredApi().toString(), api.toString());
            }
            for (EditorId required : sortedModules(spec.requiredModules())) {
                if (!moduleOf.containsKey(required) && !duplicateModules.contains(required)) {
                    fail(own, failed, index, Messages.MISSING_MODULE, spec.id().toString(),
                            Messages.KEY_MISSING_MODULE, spec.id().toString(), required.toString());
                }
            }
        }

        boolean[] cycleMember = new boolean[count];
        boolean[] selfLoop = new boolean[count];
        for (int index = 0; index < count; index++) {
            ModuleSpec spec = modules.get(index);
            selfLoop[index] = spec.requiredModules().contains(spec.id());
        }
        findCycles(count, index -> moduleTargets(modules.get(index), moduleOf), cycleMember,
                index -> selfLoop[index]);
        for (int index = 0; index < count; index++) {
            if (cycleMember[index]) {
                ModuleSpec spec = modules.get(index);
                fail(own, failed, index, Messages.MODULE_CYCLE, spec.id().toString(),
                        Messages.KEY_MODULE_CYCLE, spec.id().toString());
            }
        }

        List<List<Integer>> targets = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            targets.add(moduleTargets(modules.get(index), moduleOf));
        }
        boolean[] seeds = failed.clone();
        for (int index = 0; index < count; index++) {
            if (seeds[index]) {
                continue;
            }
            for (EditorId required : sortedModules(modules.get(index).requiredModules())) {
                if (moduleUnavailable(required, moduleOf, seeds)) {
                    seeds[index] = true;
                    break;
                }
            }
        }
        boolean[] unavailable = unavailableClosure(count, seeds, targets);

        boolean[] usable = new boolean[count];
        for (int index = 0; index < count; index++) {
            if (!unavailable[index]) {
                usable[index] = true;
                continue;
            }
            if (failed[index]) {
                problems.addAll(own.get(index));
                continue;
            }
            // Exactly one propagated diagnostic per node, naming the first unavailable dependency in
            // sorted order, so the message never depends on the order the closure happened to visit.
            ModuleSpec spec = modules.get(index);
            for (EditorId required : sortedModules(spec.requiredModules())) {
                if (moduleUnavailable(required, moduleOf, unavailable)) {
                    problems.add(Messages.problem(Messages.DEPENDENCY_UNAVAILABLE, spec.id().toString(),
                            Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, spec.id().toString(),
                            required.toString(), environment.name()));
                    break;
                }
            }
        }
        return usable;
    }

    private boolean moduleUnavailable(
                                      EditorId id, Map<EditorId, Integer> moduleOf, boolean[] unavailable) {
        Integer index = moduleOf.get(id);
        return index == null ? duplicateModules.contains(id) : unavailable[index];
    }

    private static List<Integer> moduleTargets(ModuleSpec spec, Map<EditorId, Integer> moduleOf) {
        List<Integer> targets = new ArrayList<>();
        for (EditorId required : sortedModules(spec.requiredModules())) {
            Integer target = moduleOf.get(required);
            if (target != null) {
                targets.add(target);
            }
        }
        return targets;
    }

    // ------------------------------------------------------------ contributions

    private boolean[] resolveContributions(
                                           List<Contribution> contributions,
                                           Map<ContributionKey, Integer> contributionOf,
                                           Map<EditorId, Integer> moduleOf,
                                           boolean[] moduleUsable,
                                           List<Problem> problems) {
        int count = contributions.size();

        // Exactly one diagnostic per conflicting key: owner, side and dependencies of the conflicting
        // declarations are never inspected, so no later declaration can steer the outcome.
        for (ContributionKey duplicate : duplicateContributions) {
            problems.add(Messages.problem(Messages.DUPLICATE_CONTRIBUTION, duplicate.toString(),
                    Messages.KEY_DUPLICATE_CONTRIBUTION, duplicate.toString()));
        }

        List<List<Problem>> own = new ArrayList<>(count);
        boolean[] failed = new boolean[count];
        for (int index = 0; index < count; index++) {
            own.add(new ArrayList<>());
            Contribution contribution = contributions.get(index);
            EditorId owner = contribution.owner();
            Integer ownerIndex = moduleOf.get(owner);
            if (ownerIndex == null) {
                if (duplicateModules.contains(owner)) {
                    fail(own, failed, index, Messages.DEPENDENCY_UNAVAILABLE,
                            contribution.key().toString(), Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE,
                            contribution.key().toString(), owner.toString(), environment.name());
                } else {
                    fail(own, failed, index, Messages.MISSING_OWNER, contribution.key().toString(),
                            Messages.KEY_MISSING_OWNER, contribution.key().toString(), owner.toString());
                }
            } else if (!moduleUsable[ownerIndex]) {
                fail(own, failed, index, Messages.DEPENDENCY_UNAVAILABLE, contribution.key().toString(),
                        Messages.KEY_MODULE_DEPENDENCY_UNAVAILABLE, contribution.key().toString(),
                        owner.toString(), environment.name());
            }
            if (contribution.side() == Contribution.Side.CLIENT && environment == Contribution.Side.COMMON) {
                fail(own, failed, index, Messages.WRONG_SIDE, contribution.key().toString(),
                        Messages.KEY_WRONG_SIDE, contribution.key().toString(),
                        contribution.side().name(), environment.name());
            }
            for (ContributionKey required : sortedKeys(contribution.requiredContributions())) {
                Integer target = contributionOf.get(required);
                if (target == null) {
                    if (!duplicateContributions.contains(required)) {
                        fail(own, failed, index, Messages.MISSING_CONTRIBUTION,
                                contribution.key().toString(), Messages.KEY_MISSING_CONTRIBUTION,
                                contribution.key().toString(), required.toString());
                    }
                } else if (contribution.side() == Contribution.Side.COMMON && contributions.get(target).side() == Contribution.Side.CLIENT) {
                    // A COMMON contribution must stay usable on the common side, so it may never
                    // require a CLIENT contribution. This is rejected in both environments.
                    fail(own, failed, index, Messages.WRONG_SIDE, contribution.key().toString(),
                            Messages.KEY_COMMON_REQUIRES_CLIENT, contribution.key().toString(),
                            required.toString(), environment.name());
                }
            }
        }

        boolean[] cycleMember = new boolean[count];
        boolean[] selfLoop = new boolean[count];
        for (int index = 0; index < count; index++) {
            selfLoop[index] = contributions.get(index).requiredContributions()
                    .contains(contributions.get(index).key());
        }
        findCycles(count, index -> contributionTargets(contributions.get(index), contributionOf),
                cycleMember, index -> selfLoop[index]);
        for (int index = 0; index < count; index++) {
            if (cycleMember[index]) {
                ContributionKey key = contributions.get(index).key();
                fail(own, failed, index, Messages.CONTRIBUTION_CYCLE, key.toString(),
                        Messages.KEY_CONTRIBUTION_CYCLE, key.toString());
            }
        }

        List<List<Integer>> targets = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            targets.add(contributionTargets(contributions.get(index), contributionOf));
        }
        boolean[] seeds = failed.clone();
        for (int index = 0; index < count; index++) {
            if (seeds[index]) {
                continue;
            }
            Contribution contribution = contributions.get(index);
            for (ContributionKey required : sortedKeys(contribution.requiredContributions())) {
                if (contributionUnavailable(required, contributionOf, seeds)) {
                    seeds[index] = true;
                    break;
                }
            }
        }
        boolean[] unavailable = unavailableClosure(count, seeds, targets);

        boolean[] usable = new boolean[count];
        for (int index = 0; index < count; index++) {
            if (!unavailable[index]) {
                usable[index] = true;
                continue;
            }
            if (failed[index]) {
                problems.addAll(own.get(index));
                continue;
            }
            Contribution contribution = contributions.get(index);
            for (ContributionKey required : sortedKeys(contribution.requiredContributions())) {
                if (contributionUnavailable(required, contributionOf, unavailable)) {
                    problems.add(Messages.problem(Messages.DEPENDENCY_UNAVAILABLE,
                            contribution.key().toString(),
                            Messages.KEY_CONTRIBUTION_DEPENDENCY_UNAVAILABLE,
                            contribution.key().toString(), required.toString(), environment.name()));
                    break;
                }
            }
        }
        return usable;
    }

    private boolean contributionUnavailable(
                                            ContributionKey key, Map<ContributionKey, Integer> contributionOf, boolean[] unavailable) {
        Integer index = contributionOf.get(key);
        return index == null ? duplicateContributions.contains(key) : unavailable[index];
    }

    private static List<Integer> contributionTargets(
                                                     Contribution contribution, Map<ContributionKey, Integer> contributionOf) {
        List<Integer> targets = new ArrayList<>();
        for (ContributionKey required : sortedKeys(contribution.requiredContributions())) {
            Integer target = contributionOf.get(required);
            if (target != null) {
                targets.add(target);
            }
        }
        return targets;
    }

    // ------------------------------------------------------------------- shared

    private static void fail(
                             List<List<Problem>> own,
                             boolean[] failed,
                             int index,
                             EditorId code,
                             String target,
                             String messageKey,
                             String... arguments) {
        own.get(index).add(Messages.problem(code, target, messageKey, arguments));
        failed[index] = true;
    }

    /**
     * Iterative breadth-first closure of unavailability over the reverse edges of {@code targets}: the
     * seeds are the nodes that failed on their own, and every node reachable from a seed through a
     * "is required by" edge becomes unavailable. One pass over the prebuilt adjacency replaces the
     * repeated whole-table sweeps, so a reversed failure chain stays linear in the number of edges.
     */
    private static boolean[] unavailableClosure(
                                                int count, boolean[] seeds, List<List<Integer>> targets) {
        boolean[] unavailable = seeds.clone();
        List<List<Integer>> dependents = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            dependents.add(new ArrayList<>());
        }
        for (int source = 0; source < count; source++) {
            for (int target : targets.get(source)) {
                dependents.get(target).add(source);
            }
        }
        Deque<Integer> queue = new ArrayDeque<>();
        for (int index = 0; index < count; index++) {
            if (unavailable[index]) {
                queue.add(index);
            }
        }
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (int dependent : dependents.get(node)) {
                if (!unavailable[dependent]) {
                    unavailable[dependent] = true;
                    queue.add(dependent);
                }
            }
        }
        return unavailable;
    }

    private interface Neighbours {

        List<Integer> of(int node);
    }

    private interface SelfLoop {

        boolean of(int node);
    }

    /**
     * Iterative Tarjan: marks every node of a strongly connected component with more than one member
     * and every node with a self edge. Nodes are visited in index order, so the result does not
     * depend on registration order for the same logical graph.
     */
    private static void findCycles(
                                   int count, Neighbours neighbours, boolean[] cycleMember, SelfLoop selfLoop) {
        int[] order = new int[count];
        int[] low = new int[count];
        boolean[] onStack = new boolean[count];
        for (int node = 0; node < count; node++) {
            order[node] = -1;
        }

        Deque<int[]> frames = new ArrayDeque<>();
        Deque<Integer> members = new ArrayDeque<>();
        List<List<Integer>> adjacency = new ArrayList<>(count);
        for (int node = 0; node < count; node++) {
            adjacency.add(null);
        }
        int next = 0;

        for (int start = 0; start < count; start++) {
            if (order[start] != -1) {
                continue;
            }
            order[start] = next;
            low[start] = next;
            next++;
            onStack[start] = true;
            members.push(start);
            adjacency.set(start, neighbours.of(start));
            frames.push(new int[] { start, 0 });
            while (!frames.isEmpty()) {
                int[] frame = frames.peek();
                int node = frame[0];
                List<Integer> targets = adjacency.get(node);
                if (frame[1] < targets.size()) {
                    int target = targets.get(frame[1]);
                    frame[1]++;
                    if (order[target] == -1) {
                        order[target] = next;
                        low[target] = next;
                        next++;
                        onStack[target] = true;
                        members.push(target);
                        adjacency.set(target, neighbours.of(target));
                        frames.push(new int[] { target, 0 });
                    } else if (onStack[target]) {
                        low[node] = Math.min(low[node], order[target]);
                    }
                    continue;
                }
                frames.pop();
                if (low[node] == order[node]) {
                    List<Integer> component = new ArrayList<>();
                    while (true) {
                        int member = members.pop();
                        onStack[member] = false;
                        component.add(member);
                        if (member == node) {
                            break;
                        }
                    }
                    if (component.size() > 1) {
                        for (int member : component) {
                            cycleMember[member] = true;
                        }
                    } else if (selfLoop.of(component.get(0))) {
                        cycleMember[component.get(0)] = true;
                    }
                }
                if (!frames.isEmpty()) {
                    int parent = frames.peek()[0];
                    low[parent] = Math.min(low[parent], low[node]);
                }
            }
        }
    }

    private static Set<EditorId> sortedModules(Set<EditorId> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static Set<ContributionKey> sortedKeys(Set<ContributionKey> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }
}
