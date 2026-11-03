import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.worldaccess.LocalWorldBindingHost;
import net.ptcrys.fpsmatch.editor.core.worldaccess.LocalWorldBindings;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheck;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingRead;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingRequest;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;

/** Public-JAR consumer using a fake owner host, without a Minecraft host claim.
 * Arguments must name dedicated diagnostic roots, never an original world.
 */
public final class LocalWorldBindingSmoke {
    private static final UUID ACTOR = UUID.fromString("47592f57-a435-4eb4-b165-d7385073ac58");
    private static final Map<EditorId, EditorId> DIMENSIONS = Map.of(
            EditorId.parse("project:main"), EditorId.parse("minecraft:overworld"));
    private static final FileIdentityProvider IDENTITIES = new WindowsFileIdentityProvider();

    private LocalWorldBindingSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("initialize|reopen|replace ROOT EXPECTED_WORLD_UUID");
        }
        String mode = args[0];
        if (!mode.equals("initialize") && !mode.equals("reopen") && !mode.equals("replace")) {
            throw new IllegalArgumentException("Unknown diagnostic mode");
        }
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        UUID expected;
        if (mode.equals("initialize")) {
            require(args[2].equals("-"), "initialize requires '-' as expected world UUID");
            expected = null;
            Files.createDirectory(root);
        } else {
            expected = UUID.fromString(args[2]);
            require(!expected.equals(new UUID(0, 0)), "expected world UUID must be nonzero");
        }

        Thread owner = Thread.currentThread();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldBinding completed;
        try {
            if (mode.equals("initialize")) {
                expected = onWorker(worker, owner,
                        () -> WorldIdentities.initialize(root, IDENTITIES, () -> false).worldId());
            }
            FakeHost host = new FakeHost(root);
            try (LocalWorldBindings bindings = new LocalWorldBindings(host)) {
                WorldBinding first = bind(bindings, worker, owner, expected);
                completed = first;
                if (mode.equals("replace")) {
                    WorldBindingCheck oldCheck = bindings.check(first);
                    onWorker(worker, owner, () -> {
                        replaceIdentity(root);
                        return null;
                    });
                    try {
                        onWorker(worker, owner, () -> oldCheck.verify(() -> false));
                        throw new AssertionError("same bytes at a new node accepted");
                    } catch (WorldIdentityException failure) {
                        require(failure.code() == WorldIdentityException.Code.CHANGED,
                                "replacement must report CHANGED");
                    }
                    try {
                        bindings.verify(first);
                        throw new AssertionError("old binding remained current after physical failure");
                    } catch (WorldBindingException failure) {
                        require(failure.code() == WorldBindingException.Code.STALE,
                                "old binding must report STALE");
                    }
                    completed = bind(bindings, worker, owner, expected);
                    require(!completed.requestId().equals(first.requestId()), "rebind reused request UUID");
                }
                bindings.verify(completed);
                require(host.verifications > 0, "host selection was not verified");
            }
        } finally {
            worker.shutdownNow();
            require(worker.awaitTermination(10, TimeUnit.SECONDS), "I/O worker did not stop");
        }
        System.out.println("{\"status\":\"PASS\",\"mode\":\"" + mode
                + "\",\"worldId\":\"" + completed.worldId()
                + "\",\"requestId\":\"" + completed.requestId() + "\",\"noLiveHost\":true}");
    }

    private static WorldBinding bind(LocalWorldBindings bindings, ExecutorService worker,
            Thread owner, UUID expected) throws Exception {
        WorldBindingRequest request = bindings.begin(ACTOR, DIMENSIONS);
        require(request.actorId().equals(ACTOR), "request actor changed");
        require(request.dimensions().equals(DIMENSIONS), "request mapping changed");
        require(!request.requestId().equals(new UUID(0, 0)), "zero request UUID");
        WorldBindingRead read = onWorker(worker, owner,
                () -> request.read(IDENTITIES, () -> false));
        WorldBinding binding = bindings.accept(read);
        require(binding.requestId().equals(request.requestId()), "binding request UUID changed");
        require(binding.actorId().equals(ACTOR), "binding actor changed");
        require(binding.dimensions().equals(DIMENSIONS), "binding mapping changed");
        require(binding.worldId().equals(expected), "world UUID differs from expected UUID");
        WorldBindingCheck check = bindings.check(binding);
        WorldBindingCheckResult result = onWorker(worker, owner, () -> check.verify(() -> false));
        bindings.confirm(result);
        return binding;
    }

    private static void replaceIdentity(Path root) throws Exception {
        Path identity = root.resolve(".editor-platform/world.identity");
        Path retired = identity.resolveSibling("retired-world.identity");
        byte[] original = Files.readAllBytes(identity);
        FileNodeIdentity before = IDENTITIES.identity(identity);
        // No REPLACE_EXISTING: a pre-existing retired path must fail closed.
        Files.move(identity, retired);
        Files.write(identity, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        require(IDENTITIES.identity(retired).equals(before), "move changed retired node identity");
        require(!IDENTITIES.identity(identity).equals(before), "replacement reused original node");
        require(Arrays.equals(original, Files.readAllBytes(identity)), "replacement changed identity bytes");
    }

    private static <T> T onWorker(ExecutorService worker, Thread owner, Callable<T> action)
            throws Exception {
        Future<T> future = worker.submit(() -> {
            require(Thread.currentThread() != owner, "I/O executed on owner");
            return action.call();
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("Unexpected I/O failure", cause);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static void require(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }

    private static final class FakeHost implements LocalWorldBindingHost {
        private final Thread owner = Thread.currentThread();
        private Path currentRoot;
        private boolean active = true;
        private int verifications;

        private FakeHost(Path root) { currentRoot = root; }

        @Override public void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new WorldBindingException(WorldBindingException.Code.WRONG_THREAD, "Wrong fake host owner");
            }
        }

        @Override public boolean active() {
            requireOwner();
            return active;
        }

        @Override public Selection capture(UUID actorId, Map<EditorId, EditorId> dimensions) {
            requireOwner();
            require(actorId.equals(ACTOR), "unexpected actor");
            require(dimensions.equals(DIMENSIONS), "unexpected dimension mapping");
            Path capturedRoot = currentRoot;
            return new Selection() {
                @Override public Path root() {
                    requireOwner();
                    return capturedRoot;
                }

                @Override public void verify() {
                    requireOwner();
                    if (!active) {
                        throw new WorldBindingException(WorldBindingException.Code.INACTIVE, "Inactive fake host");
                    }
                    if (!capturedRoot.equals(currentRoot)) {
                        throw new WorldBindingException(WorldBindingException.Code.STALE, "Fake host root changed");
                    }
                    verifications++;
                }
            };
        }
    }
}
