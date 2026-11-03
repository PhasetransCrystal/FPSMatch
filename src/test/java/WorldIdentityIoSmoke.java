import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;

/** Public-JAR consumer. Arguments must point only to dedicated empty diagnostic directories. */
public final class WorldIdentityIoSmoke {
    private static final FileIdentityProvider IDENTITIES = new WindowsFileIdentityProvider();
    private WorldIdentityIoSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("create|read|replace ROOT [UUID]");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "create" -> create(root);
            case "read" -> read(root, UUID.fromString(args[2]));
            case "replace" -> replace(root, UUID.fromString(args[2]));
            default -> throw new IllegalArgumentException("unknown diagnostic mode");
        }
    }

    private static void create(Path root) throws Exception {
        Files.createDirectory(root);
        try {
            WorldIdentities.read(root, IDENTITIES, () -> false);
            throw new AssertionError("missing identity accepted");
        } catch (WorldIdentityException expected) {
            require(expected.code() == WorldIdentityException.Code.MISSING, "missing diagnosis");
        }
        try (var nodes = Files.list(root)) {
            require(nodes.findAny().isEmpty(), "read created data");
        }
        WorldIdentitySnapshot captured = WorldIdentities.initialize(root, IDENTITIES, () -> false);
        require(root.equals(captured.root()), "root capture");
        WorldIdentities.verify(captured, () -> false);
        verifyBytes(root, captured.worldId());
        System.out.println("WORLD_IDENTITY_CREATED " + captured.worldId());
    }

    private static void read(Path root, UUID expectedId) throws Exception {
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, () -> false);
        require(captured.worldId().equals(expectedId), "new JVM identity drift");
        WorldIdentities.verify(captured, () -> false);
        byte[] before = Files.readAllBytes(identity(root));
        try {
            WorldIdentities.initialize(root, IDENTITIES, () -> false);
            throw new AssertionError("existing identity overwritten");
        } catch (WorldIdentityException expected) {
            require(expected.code() == WorldIdentityException.Code.ALREADY_EXISTS, "existing diagnosis");
        }
        require(Arrays.equals(before, Files.readAllBytes(identity(root))), "existing bytes changed");
        verifyBytes(root, expectedId);
        System.out.println("WORLD_IDENTITY_REOPENED " + expectedId);
    }

    private static void replace(Path root, UUID expectedId) throws Exception {
        WorldIdentitySnapshot captured = WorldIdentities.read(root, IDENTITIES, () -> false);
        require(captured.worldId().equals(expectedId), "copy lineage");
        byte[] bytes = Files.readAllBytes(identity(root));
        Files.move(identity(root), identity(root).resolveSibling("identity.retired"));
        Files.write(identity(root), bytes);
        try {
            WorldIdentities.verify(captured, () -> false);
            throw new AssertionError("same bytes at a new file node accepted");
        } catch (WorldIdentityException expected) {
            require(expected.code() == WorldIdentityException.Code.CHANGED, "changed-node diagnosis");
        }
        require(WorldIdentities.read(root, IDENTITIES, () -> false).worldId().equals(expectedId), "copy content changed");
        System.out.println("WORLD_IDENTITY_REPLACEMENT_REJECTED " + expectedId);
    }

    private static void verifyBytes(Path root, UUID expectedId) throws Exception {
        byte[] bytes = Files.readAllBytes(identity(root));
        require(bytes.length == 24, "identity length");
        require(Arrays.equals(Arrays.copyOf(bytes, 8), "EDWORLD1".getBytes(StandardCharsets.US_ASCII)), "identity magic");
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 8, 16);
        require(new UUID(buffer.getLong(), buffer.getLong()).equals(expectedId), "identity byte order");
    }

    private static Path identity(Path root) {
        return root.resolve(".editor-platform/world.identity");
    }

    private static void require(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
}
