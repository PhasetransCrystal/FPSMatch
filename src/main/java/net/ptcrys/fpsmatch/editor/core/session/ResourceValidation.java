package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContent;
import net.ptcrys.fpsmatch.editor.core.resources.ResourcePlan;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One bounded, synchronous execution over a fixed resource plan. */
final class ResourceValidation {

    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CHECKING_CANCELLATION = new ThreadLocal<>();

    private ResourceValidation() {}

    static void requireOutsideCallback() {
        if (CALLBACK.get() != null) throw new IllegalStateException("resource validation callback cannot reenter session mutation or I/O");
    }

    /** Guards trusted extension code, including collection traversal performed by its consumer. */
    static <T> T callback(Supplier<T> operation) {
        requireOutsideCallback();
        Boolean previous = CALLBACK.get();
        CALLBACK.set(Boolean.TRUE);
        try {
            return operation.get();
        } finally {
            if (previous == null) CALLBACK.remove();
            else CALLBACK.set(previous);
        }
    }

    @FunctionalInterface
    interface IOCallback<T> {

        T run() throws IOException;
    }

    /** Checked I/O from an adapter retains its original exception identity. */
    static <T> T callbackIO(IOCallback<T> operation) throws IOException {
        requireOutsideCallback();
        CALLBACK.set(Boolean.TRUE);
        try {
            return operation.run();
        } finally {
            CALLBACK.remove();
        }
    }

    /** Cancellation belongs to one read/prepare attempt and cannot be reset by its supplier. */
    static final class Cancellation implements BooleanSupplier {

        private final BooleanSupplier source;
        private boolean observed;

        Cancellation(BooleanSupplier source) {
            this.source = Objects.requireNonNull(source, "cancelled");
        }

        @Override
        public boolean getAsBoolean() {
            if (!observed) observed = source.getAsBoolean();
            return observed;
        }
    }

    static void check(Cancellation cancelled) throws ResourceValidationCancelledException {
        if (poll(cancelled)) throw new ResourceValidationCancelledException();
    }

    /** Adapter polling shares the latch and callback/recursive-supplier boundary. */
    static boolean poll(Cancellation cancelled) {
        if (CHECKING_CANCELLATION.get() != null) throw new IllegalStateException("recursive cancellation evaluation");
        Boolean previous = CALLBACK.get();
        CALLBACK.set(Boolean.TRUE);
        CHECKING_CANCELLATION.set(Boolean.TRUE);
        try {
            return cancelled.getAsBoolean();
        } finally {
            CHECKING_CANCELLATION.remove();
            if (previous == null) CALLBACK.remove();
            else CALLBACK.set(previous);
        }
    }

    static List<Problem> run(LocalProjectStore store, ResourcePlan plan,
                             ResourceProducerCatalog producers, Cancellation cancelled) throws IOException {
        check(cancelled);
        List<Problem> result = new ArrayList<>();
        // Resolve the complete table before running even the first contribution.
        for (ResourceVersion version : plan.versions().values()) {
            try {
                producers.requireValidationContribution(version);
            } catch (SessionRejection failure) {
                if (failure.problems().size() > 1024 - result.size()) throw new IllegalArgumentException("validator problem budget exceeded");
                result.addAll(failure.problems());
            }
        }
        Diagnostics budget = new Diagnostics();
        if (!result.isEmpty()) {
            List<Problem> problems = budget.add(result, result.size(), 1024);
            check(cancelled);
            return problems;
        }
        long bytes = 0;
        HashSet<BlobRef> seen = new HashSet<>();
        for (ProjectState.Entry entry : plan.byteEntries().values()) {
            if (seen.add(entry.content())) {
                long size = entry.content().size();
                if (size > 16L * 1024 * 1024 * 1024 - bytes) throw new IllegalArgumentException("resource validation byte set exceeds 16 GiB");
                bytes += size;
            }
        }
        Map<UUID, EditorId> types = new TreeMap<>();
        plan.resources().forEach((id, resource) -> types.put(id, resource.type()));
        Map<UUID, EditorId> fixedTypes = Collections.unmodifiableMap(types);
        CopyBudget copies = new CopyBudget();
        for (ResourceVersion version : plan.versions().values()) {
            check(cancelled);
            Context context = new Context(store, plan, fixedTypes, cancelled, copies);
            List<Problem> returned;
            requireOutsideCallback();
            CALLBACK.set(Boolean.TRUE);
            try {
                returned = producers.validator(version).validate(version, context);
            } finally {
                context.active = false;
                CALLBACK.remove();
            }
            // A custom List may itself execute contribution code while being inspected.
            CALLBACK.set(Boolean.TRUE);
            try {
                Objects.requireNonNull(returned, "validator problems");
                int count = returned.size();
                result.addAll(budget.add(returned, count, 256));
            } finally {
                CALLBACK.remove();
            }
            check(cancelled);
        }
        check(cancelled);
        return List.copyOf(result);
    }

    private static final class CopyBudget {

        int calls;
        long remaining = 32L * 1024 * 1024 * 1024;
    }

    private static final class Context implements ResourceContent {

        private final LocalProjectStore store;
        private final ResourcePlan plan;
        private final Map<UUID, EditorId> types;
        private final Cancellation cancelled;
        private final CopyBudget budget;
        private final Thread thread = Thread.currentThread();
        private volatile boolean active = true;

        Context(LocalProjectStore store, ResourcePlan plan, Map<UUID, EditorId> types,
                Cancellation cancelled, CopyBudget budget) {
            this.store = store;
            this.plan = plan;
            this.types = types;
            this.cancelled = cancelled;
            this.budget = budget;
        }

        private void requireActive() {
            if (!active || Thread.currentThread() != thread) throw new IllegalStateException("resource content context is outside its callback");
        }

        public Map<UUID, EditorId> resourceTypes() {
            requireActive();
            return types;
        }

        public Map<UUID, ResourceVersion> versions() {
            requireActive();
            return plan.versions();
        }

        public Map<UUID, ProjectState.Entry> byteEntries() {
            requireActive();
            return plan.byteEntries();
        }

        public void checkCancelled() throws ResourceValidationCancelledException {
            requireActive();
            check(cancelled);
        }

        public void copyContent(UUID entryId, OutputStream target) throws IOException {
            requireActive();
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(target, "target");
            ProjectState.Entry entry = plan.byteEntries().get(entryId);
            if (entry == null) throw new IllegalArgumentException("unknown resource byte entry " + entryId);
            checkCancelled();
            if (budget.calls == 65536) throw new IllegalArgumentException("resource validation copy call budget exceeded");
            long size = entry.content().size();
            if (size > budget.remaining) throw new IllegalArgumentException("resource validation copy byte budget exceeded");
            budget.calls++;
            budget.remaining -= size;
            store.copyContent(entry.content(), new OutputStream() {

                public void write(int value) throws IOException {
                    checkCancelled();
                    target.write(value);
                }

                public void write(byte[] bytes, int offset, int length) throws IOException {
                    Objects.checkFromIndexSize(offset, length, bytes.length);
                    while (length > 0) {
                        checkCancelled();
                        int block = Math.min(length, 65536);
                        target.write(bytes, offset, block);
                        offset += block;
                        length -= block;
                    }
                }
            });
            checkCancelled();
        }
    }

    private record Node(Object value, int depth) {}

    private static final class Diagnostics {

        int problems;
        int nodes;
        long text;

        List<Problem> add(List<Problem> values, int count, int perCall) {
            if (count < 0 || count > perCall || count > 1024 - problems) throw new IllegalArgumentException("validator problem budget exceeded");
            problems += count;
            // Check size before copying or traversing producer diagnostics.
            List<Problem> snapshot = List.copyOf(values);
            if (snapshot.size() != count) throw new IllegalArgumentException("validator problem list changed");
            for (Problem problem : snapshot) {
                if (++nodes > 65536) throw new IllegalArgumentException("validator diagnostic traversal budget exceeded");
                string(problem.code().toString());
                string(problem.target());
                ArrayDeque<Node> pending = new ArrayDeque<>();
                pending.push(new Node(problem.message(), 0));
                while (!pending.isEmpty()) {
                    Node node = pending.pop();
                    if (++nodes > 65536 || node.depth() > 32) throw new IllegalArgumentException("validator diagnostic traversal budget exceeded");
                    Object value = node.value();
                    if (value instanceof TextContent.Literal literal) string(literal.text());
                    else if (value instanceof TextContent.Message message) {
                        string(message.key());
                        for (TextContent.Argument argument : message.arguments()) pending.push(new Node(argument, node.depth()));
                    } else if (value instanceof TextContent.LiteralArgument literal) string(literal.value());
                    else if (value instanceof TextContent.NumberArgument number) {
                        if (number.value().precision() > 4096 || Math.abs((long) number.value().scale()) > 4096) throw new IllegalArgumentException("validator diagnostic number budget exceeded");
                    } else if (value instanceof TextContent.NestedArgument nested) pending.push(new Node(nested.value(), node.depth() + 1));
                    else throw new IllegalArgumentException("invalid validator diagnostic node");
                }
            }
            return snapshot;
        }

        private void string(String value) {
            if (value.length() > 1048576 - text) throw new IllegalArgumentException("validator diagnostic text budget exceeded");
            text += value.length();
        }
    }
}
