package net.ptcrys.fpsmatch.editor.common.ui.layout;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import java.util.*;
import java.util.function.Function;

/**
 * Owner-thread retained layout state. Declarations are short-lived; unchanged layout nodes and
 * their constraint-dependent results survive. Candidate writes never mutate committed caches.
 */
public final class RetainedLayout {

    /**
     * Tokens must be immutable value objects covering every input used by the measurer.
     * Presentation-only state is deliberately excluded.
     */
    public record Dependency(String type, Object token, boolean textMetrics) {

        public Dependency {
            Objects.requireNonNull(type);
            Objects.requireNonNull(token);
        }

        public Dependency(String type, Object token) {
            this(type, token, false);
        }
    }

    /**
     * Bounds retained acceleration payload. Active mount identities and returned frame geometry
     * have their own lifetimes and are never evicted to satisfy this cache budget.
     */
    public record Budget(int maxNodes, int maxEntries, long maxWeight) {

        public Budget {
            if (maxNodes < 1 || maxEntries < 0 || maxWeight < 1) throw new IllegalArgumentException("invalid layout budget");
        }

        public static Budget defaults() {
            return new Budget(20_000, 100_000, 32L * 1024 * 1024);
        }
    }

    public record Stats(int retainedNodes, int cacheEntries, long retainedWeight, long cacheHits, long cacheMisses,
                        long leafMeasures, long arrangedNodes, long reusedNodes, long mountedNodes, long evictions, long publications) {}

    private record Mount(FlowLayout.Kind kind, String type, long generation) {}

    private record Retained(FlowLayout.Node node, Dependency dependency, long metrics, long weight) {}

    private record Root(FlowLayout.Node node, Map<String, Retained> nodes, long weight) {}

    private record Cached(Object value, long weight) {}

    private record State(Object scope, long metrics, Map<String, Root> roots, Map<String, Map<String, Mount>> mounts,
                         Map<FlowLayout.Node, Map<FlowLayout.Query, Cached>> cache, int nodes, int entries, long weight) {}

    private final Budget budget;
    private State state = new State("default", 0, Map.of(), Map.of(), Map.of(), 0, 0, 0);
    private Transaction transaction;
    private long serial, cacheHits, cacheMisses, leafMeasures, arrangedNodes, reusedNodes, mountedNodes, evictions, publications;

    public RetainedLayout() {
        this(Budget.defaults());
    }

    public RetainedLayout(Budget budget) {
        this.budget = Objects.requireNonNull(budget);
    }

    public Stats stats() {
        return new Stats(state.nodes, state.entries, state.weight, cacheHits, cacheMisses, leafMeasures, arrangedNodes, reusedNodes, mountedNodes, evictions, publications);
    }

    public long mountGeneration(String root, String id) {
        var tree = state.mounts.get(root);
        var node = tree == null ? null : tree.get(id);
        return node == null ? 0 : node.generation;
    }

    public void clear() {
        if (transaction != null) throw new IllegalStateException("publication active");
        state = new State(state.scope, state.metrics, Map.of(), Map.of(), Map.of(), 0, 0, 0);
    }

    public final class Publication implements AutoCloseable {

        private final Transaction owner;
        private boolean closed;

        private Publication(Transaction owner) {
            this.owner = owner;
        }

        public void commit() {
            if (closed || transaction != owner) throw new IllegalStateException("publication is not active");
            try {
                if (owner.failed) throw new IllegalStateException("cannot commit a failed layout publication");
                state = owner.prepared;
                publications++;
            } finally {
                transaction = null;
                closed = true;
            }
        }

        @Override
        public void close() {
            if (!closed) {
                if (transaction == owner) transaction = null;
                closed = true;
            }
        }
    }

    /** Successful publication retires roots not evaluated in this scope's current frame. */
    public Publication beginPublication(Object scope, long metricsGeneration) {
        if (transaction != null) throw new IllegalStateException("nested layout publication");
        transaction = new Transaction(Objects.requireNonNull(scope), metricsGeneration);
        return new Publication(transaction);
    }

    private final class Transaction {

        final Object scope;
        final long metrics;
        final State base;
        final LinkedHashMap<String, Root> roots;
        final Set<String> seen = new HashSet<>();
        final LinkedHashMap<String, Map<String, Mount>> mounts;
        final Map<FlowLayout.Node, LinkedHashMap<FlowLayout.Query, Cached>> writes = new IdentityHashMap<>();
        int pendingEntries;
        long pendingWeight;
        boolean failed;
        State prepared;

        Transaction(Object scope, long metrics) {
            this.scope = scope;
            this.metrics = metrics;
            base = Objects.equals(scope, state.scope) ? state : new State(scope, metrics, Map.of(), Map.of(), Map.of(), 0, 0, 0);
            roots = new LinkedHashMap<>(base.roots);
            mounts = new LinkedHashMap<>(base.mounts);
            prepared = new State(scope, metrics, Map.of(), Map.of(), Map.of(), 0, 0, 0);
        }
    }

    /** All entry points, including presentation freezing, run inside the same candidate. */
    public <T> T evaluate(FlowLayout.Node source, Map<String, Dependency> dependencies, long metricsGeneration, Function<Session, T> work) {
        boolean own = transaction == null;
        var current = own ? new Transaction(state.scope, metricsGeneration) : transaction;
        if (current.failed) throw new IllegalStateException("layout publication already failed");
        try {
            if (current.metrics != metricsGeneration) throw new IllegalStateException("font generation changed during layout publication");
            Objects.requireNonNull(source);
            Objects.requireNonNull(dependencies);
            Objects.requireNonNull(work);
            var nodes = new LinkedHashMap<String, Retained>();
            var mounts = new LinkedHashMap<String, Mount>();
            var ids = new HashSet<String>();
            var previous = current.roots.get(source.id());
            var previousMounts = current.mounts.getOrDefault(source.id(), Map.of());
            var canonical = reconcile(source, dependencies, previous, previousMounts, nodes, mounts, ids, metricsGeneration);
            long weight = nodes.values().stream().mapToLong(Retained::weight).sum();
            var result = work.apply(new Session(canonical, current));
            current.roots.remove(source.id());
            current.roots.put(source.id(), new Root(canonical, Map.copyOf(nodes), weight));
            current.seen.add(source.id());
            current.mounts.put(source.id(), Map.copyOf(mounts));
            current.prepared = prepareState(current, !own);
            if (own) {
                state = current.prepared;
                publications++;
            }
            return result;
        } catch (RuntimeException | Error failure) {
            current.failed = true;
            throw failure;
        }
    }

    private FlowLayout.Node reconcile(FlowLayout.Node node, Map<String, Dependency> dependencies, Root previous, Map<String, Mount> previousMounts, Map<String, Retained> result, Map<String, Mount> mounts, Set<String> ids, long metrics) {
        if (!ids.add(node.id())) throw new IllegalArgumentException("duplicate layout id: " + node.id());
        var dependency = Objects.requireNonNull(dependencies.get(node.id()), "missing layout dependency: " + node.id());
        var children = new ArrayList<FlowLayout.Node>();
        for (var child : node.children()) children.add(reconcile(child, dependencies, previous, previousMounts, result, mounts, ids, metrics));
        var old = previous == null ? null : previous.nodes.get(node.id());
        var mount = previousMounts.get(node.id());
        boolean sameType = mount != null && mount.kind == node.kind() && mount.type.equals(dependency.type);
        long generation = sameType ? mount.generation : ++serial;
        mounts.put(node.id(), new Mount(node.kind(), dependency.type, generation));
        if (sameType) reusedNodes++;
        else mountedNodes++;
        boolean sameChildren = sameType && old != null && old.node.kind() == node.kind() && old.dependency.type.equals(dependency.type) && old.node.children().size() == children.size();
        for (int i = 0; sameChildren && i < children.size(); i++) sameChildren = old.node.children().get(i) == children.get(i);
        var canonical = sameChildren && old.node.style().equals(node.style()) && old.dependency.equals(dependency) && (!dependency.textMetrics || old.metrics == metrics) ? old.node :
                new FlowLayout.Node(node.id(), node.kind(), node.style(), children, node.measurer());
        long weight = 256L + 2L * node.id().length() + 2L * dependency.type.length() + 2L * dependency.token.toString().length() + 8L * children.size();
        result.put(node.id(), new Retained(canonical, dependency, metrics, weight));
        return canonical;
    }

    public final class Session {

        private final FlowLayout.Node root;
        private final FlowLayout.Engine engine;

        private Session(FlowLayout.Node root, Transaction current) {
            this.root = root;
            engine = new FlowLayout.Engine(new FlowLayout.Memo() {

                private final Map<FlowLayout.Node, Map<FlowLayout.Query, Object>> local = new IdentityHashMap<>();

                @Override
                public Object get(FlowLayout.Node node, FlowLayout.Query query) {
                    var recent = local.get(node);
                    if (recent != null && recent.containsKey(query)) {
                        cacheHits++;
                        return recent.get(query);
                    }
                    var pending = current.writes.get(node);
                    var cached = pending == null ? null : pending.get(query);
                    if (cached == null) {
                        var old = current.base.cache.get(node);
                        cached = old == null ? null : old.get(query);
                    }
                    if (cached != null) {
                        cacheHits++;
                        return cached.value;
                    }
                    cacheMisses++;
                    return null;
                }

                @Override
                public void put(FlowLayout.Node node, FlowLayout.Query query, Object value) {
                    local.computeIfAbsent(node, ignored -> new HashMap<>()).put(query, value);
                    long weight = cacheWeight(value);
                    if (current.pendingEntries >= budget.maxEntries || current.pendingWeight + weight > budget.maxWeight) return;
                    var entries = current.writes.computeIfAbsent(node, ignored -> new LinkedHashMap<>());
                    if (!entries.containsKey(query)) {
                        entries.put(query, new Cached(value, weight));
                        current.pendingEntries++;
                        current.pendingWeight += weight;
                    }
                }

                @Override
                public void leafMeasured() {
                    leafMeasures++;
                }

                @Override
                public void arranged() {
                    arrangedNodes++;
                }
            });
        }

        public FlowLayout.Size measure(FlowLayout.Constraints constraints) {
            return engine.measure(root, Objects.requireNonNull(constraints), false);
        }

        public FlowLayout.Result layout(Bounds bounds) {
            return engine.layout(root, Objects.requireNonNull(bounds));
        }

        public FlowLayout.Result layoutContent(int x, int y, int width) {
            if (width < 0) throw new IllegalArgumentException("negative layout width");
            return engine.layout(root, new Bounds(x, y, width, engine.atWidth(root, width).height()));
        }
    }

    private static long cacheWeight(Object value) {
        if (value instanceof FlowLayout.Result result) return 128L + 192L * result.byId().size();
        if (value instanceof FlowLayout.Arrangement arrangement) return 160L + 48L * arrangement.plan().cells().size();
        if (value instanceof FlowLayout.Plan plan) return 128L + 48L * plan.cells().size();
        return 112L;
    }

    private State prepareState(Transaction current, boolean sweep) {
        if (current.failed) throw new IllegalStateException("cannot commit a failed layout publication");
        var roots = new LinkedHashMap<>(current.roots);
        var mounts = new LinkedHashMap<>(current.mounts);
        if (sweep) {
            roots.keySet().removeIf(id -> !current.seen.contains(id));
            mounts.keySet().removeIf(id -> !current.seen.contains(id));
        }
        long nodes = roots.values().stream().mapToLong(root -> root.nodes.size()).sum();
        long weight = roots.values().stream().mapToLong(Root::weight).sum();
        while ((nodes > budget.maxNodes || weight > budget.maxWeight) && !roots.isEmpty()) {
            var oldest = roots.entrySet().iterator();
            var root = oldest.next().getValue();
            oldest.remove();
            nodes -= root.nodes.size();
            weight -= root.weight;
            evictions++;
        }
        var live = Collections.newSetFromMap(new IdentityHashMap<FlowLayout.Node, Boolean>());
        roots.values().forEach(root -> root.nodes.values().forEach(node -> live.add(node.node)));
        var cache = new IdentityHashMap<FlowLayout.Node, Map<FlowLayout.Query, Cached>>();
        int entries = 0;
        // New measurements take admission priority; older constraints fill the remaining budget.
        List<Map<FlowLayout.Node, ? extends Map<FlowLayout.Query, Cached>>> sources = List.of(current.writes, current.base.cache);
        for (var source : sources) {
            for (var node : source.entrySet()) if (live.contains(node.getKey())) {
                var values = cache.computeIfAbsent(node.getKey(), ignored -> new LinkedHashMap<>());
                for (var entry : node.getValue().entrySet()) {
                    if (values.containsKey(entry.getKey())) continue;
                    if (entries >= budget.maxEntries || weight + entry.getValue().weight > budget.maxWeight) {
                        evictions++;
                        continue;
                    }
                    values.put(entry.getKey(), entry.getValue());
                    entries++;
                    weight += entry.getValue().weight;
                }
            }
        }
        return new State(current.scope, current.metrics, Collections.unmodifiableMap(roots), Collections.unmodifiableMap(mounts), cache, (int) nodes, entries, weight);
    }
}
