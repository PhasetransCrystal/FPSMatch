import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;
import java.io.ByteArrayInputStream;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.installation.LocalInstallationIndex;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;

/** Offline diagnostic registration, restricted to a fresh Q58 further copy. */
public final class PrepareWorldIdentity {
    private PrepareWorldIdentity() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 2 && args[1].equals("--q80-ownership-fixture"), "Supply exact fresh Q58 attempt directory and explicit Q80 fixture opt-in");
        Path attempt = Path.of(args[0]);
        Path base = Path.of("F:/BlockOffensive/.local-artifacts/world-editor/rewrite/platform-batch07/Q58/host-runs");
        require(attempt.isAbsolute() && attempt.equals(attempt.normalize())
                && attempt.getParent().equals(base) && attempt.getFileName().toString().matches("[A-Za-z0-9-]+"),
                "Exact Q58 attempt required");
        require(attempt.toRealPath().equals(attempt), "Canonical attempt required");
        for (String name : new String[] { "input-manifest.json", "capture.json", "run.started", "identity-preparation.json" }) {
            require(!Files.exists(attempt.resolve(name), LinkOption.NOFOLLOW_LINKS), "Attempt already prepared or used");
        }
        Path game = attempt.resolve("game");
        require(!Files.exists(game.resolve("logs"), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(game.resolve("mcagent"), LinkOption.NOFOLLOW_LINKS), "Attempt already launched");
        Path a = game.resolve("saves/Q58-A");
        Path b = game.resolve("saves/Q58-B");
        for (Path root : new Path[] { a, b }) {
            Path component = root.getRoot();
            for (Path part : root) {
                component = component.resolve(part);
                BasicFileAttributes attrs = Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther(), "Nonregular root ancestor");
            }
            require(Files.isRegularFile(root.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS), "Complete world copy required");
            require(!Files.exists(root.resolve(".editor-platform"), LinkOption.NOFOLLOW_LINKS), "Identity already present; do not retry");
        }
        WindowsFileIdentityProvider provider = new WindowsFileIdentityProvider();
        WorldIdentitySnapshot first = WorldIdentities.initialize(a, provider, () -> false);
        WorldIdentities.verify(first, () -> false);
        Path identityA = a.resolve(".editor-platform/world.identity");
        byte[] bytes = Files.readAllBytes(identityA);
        require(bytes.length == 24 && Arrays.equals(Arrays.copyOf(bytes, 8), "EDWORLD1".getBytes(StandardCharsets.US_ASCII)), "Identity bytes");
        ByteBuffer values = ByteBuffer.wrap(bytes, 8, 16);
        require(new UUID(values.getLong(), values.getLong()).equals(first.worldId()), "UUID byte order");
        Files.createDirectory(b.resolve(".editor-platform"));
        Path identityB = b.resolve(".editor-platform/world.identity");
        Files.write(identityB, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        WorldIdentitySnapshot second = WorldIdentities.read(b, provider, () -> false);
        WorldIdentities.verify(second, () -> false);
        require(first.worldId().equals(second.worldId()), "Copied lineage changed");
        StringBuilder nodes = new StringBuilder();
        String[] suffixes = { "", ".editor-platform", ".editor-platform/world.identity" };
        String[] names = { "root", "directory", "file" };
        for (int i = 0; i < suffixes.length; i++) {
            FileNodeIdentity left = provider.identity(a.resolve(suffixes[i]));
            FileNodeIdentity right = provider.identity(b.resolve(suffixes[i]));
            require(!left.equals(right) && left.provider().equals("win32-file-id-128") && right.provider().equals(left.provider()), "Copies share physical nodes");
            if (i != 0) nodes.append(',');
            nodes.append('"').append(names[i]).append("\":{\"a\":\"").append(left.key())
                    .append("\",\"b\":\"").append(right.key()).append("\"}");
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        JsonObject fixture = new JsonObject();
        JsonArray reservations = new JsonArray();
        try (LocalInstallationIndex index = LocalInstallationIndex.initialize(a, provider)) {
            String[] types = { "csdm", "cs", "q80_generic" };
            LocalInstallationIndex.State[] states = LocalInstallationIndex.State.values();
            for (int i = 0; i < types.length; i++) {
                InstallationPlan plan = new InstallationPlan(UUID.randomUUID(), new InstallationPlan.Key(types[i], "q80_persistent"),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), EditorId.parse("q80:diagnostic_adapter"),
                        EditorId.parse("q80:diagnostic_factory"), "1.20.1", Map.of(EditorId.parse("q80:diagnostic_module"), "1"),
                        Map.of(EditorId.parse("project:main"), EditorId.parse("minecraft:overworld")));
                index.reserve(index.currentCommitId(), UUID.randomUUID(), plan,
                        new ByteArrayInputStream(("Q80 ownership diagnostic; not an installable project: " + types[i]).getBytes(StandardCharsets.UTF_8)));
                if (states[i] != LocalInstallationIndex.State.PREPARED) {
                    index.transition(index.currentCommitId(), UUID.randomUUID(), plan.key(), states[i]);
                }
                JsonObject row = new JsonObject();
                row.addProperty("type", types[i]);
                row.addProperty("name", plan.key().name());
                row.addProperty("state", states[i].name());
                reservations.add(row);
            }
            index.verify();
        }
        JsonArray files = new JsonArray(), directories = new JsonArray();
        try (var paths = Files.walk(a.resolve(".editor-platform"))) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(a.resolve(".editor-platform")) || path.equals(identityA)) continue;
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String relative = game.relativize(path).toString().replace('\\', '/');
                require(!attrs.isSymbolicLink() && !attrs.isOther(), "Nonregular fixture node");
                if (attrs.isDirectory()) directories.add(relative);
                else {
                    require(attrs.isRegularFile(), "Nonregular fixture file");
                    JsonObject row = new JsonObject();
                    row.addProperty("path", relative);
                    row.addProperty("bytes", attrs.size());
                    row.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                    files.add(row);
                }
            }
        }
        fixture.add("reservations", reservations);
        fixture.add("files", files);
        fixture.add("directories", directories);
        System.out.println("{\"status\":\"PASS\",\"worldId\":\"" + first.worldId()
                + "\",\"identitySha256\":\"" + digest + "\",\"provider\":\"win32-file-id-128\",\"nodes\":{" + nodes + "},\"installationFixture\":" + fixture + "}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
