package net.ptcrys.fpsmatch.editor.core.session.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContent;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/** Synthetic public-JAR, full-content push example; no game or BO format access. */
public final class ResourceValidationLargePushSmoke {
    private static final long MIB = 1024L * 1024;
    private static final EditorId OWNER = EditorId.parse("demo:large_push_module");
    private static final EditorId RESOURCES = EditorId.parse("editor:resources");
    private static final EditorId IMPORTER = EditorId.parse("demo:large_push_format");
    private static final EditorId TYPE = EditorId.parse("demo:large_push_resource");
    private static final UUID RESOURCE = UUID.fromString("11333333-0000-0000-0000-000000000001");
    private static final UUID VERSION = UUID.fromString("11333333-0000-0000-0000-000000000002");
    private static final UUID SOURCE = UUID.fromString("11333333-0000-0000-0000-000000000003");
    // SHA-256 of exactly 134217728 bytes, byte at position p = (byte) (p & 255).
    private static final String SHA256_128_MIB =
            "a626d17da2e502f5b4b8e3ebd23f0bf9daef6255688d8e0bb482b3ae3794a682";

    private ResourceValidationLargePushSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("<new-project-root> <sizeMiB:1..512>; use 128 for low-heap acceptance");
        int sizeMiB = Integer.parseInt(args[1]);
        require(sizeMiB >= 1 && sizeMiB <= 512, "sizeMiB must fit the default 512MiB blob budget");
        long size = sizeMiB * MIB;
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Probe probe = new Probe(size, expectedDigest(size));
        UUID projectId = UUID.randomUUID();
        UUID publishedCommit;
        ExtensionRegistry registry = registry(probe);

        // create refuses an existing root. All project files stay beneath this new root.
        try (LocalProjectStore store = LocalProjectStore.create(root)) {
            BlobRef blob;
            try (InputStream generated = new PatternSource(size)) {
                blob = store.stage(BlobRef.Kind.ASSET, generated);
            }
            require(blob.size() == size && blob.sha256().equals(probe.expectedSha), "staged source size/SHA mismatch");
            ProjectState.Entry source = new ProjectState.Entry(SOURCE, OWNER.toString(), TYPE.toString(),
                    "demo:position_bytes", 1, "Synthetic streamed bytes", blob, Set.of(), Set.of());
            ResourceVersion version = new ResourceVersion(VERSION, RESOURCE, IMPORTER, "1",
                    Map.of("pattern", "position-mod-256"), SOURCE, Set.of(), Set.of());
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(RESOURCE, new LogicalResource(RESOURCE, TYPE, "Large push example", VERSION)),
                    Map.of(VERSION, version), Map.of(SOURCE, source));
            // Storage bootstrap is deliberately followed by the public session publication gate.
            store.commit(null, UUID.randomUUID(), new ProjectState(projectId, "Large content push example",
                    Map.of(OWNER.toString(), "1", RESOURCES.toString(), "1"), Map.of(),
                    ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(catalog)), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            var loaded = access.readValidated(registry, () -> false);
            require(probe.pushes == 1, "initial readValidated must perform one full push");
            require(loaded.problems().isEmpty(), "initial validation must have no problems");
            try (ProjectSession session = ProjectSession.open(access, loaded, registry, 2)) {
                require(!session.protectedMode(), "validated initial session must be healthy");
                var capture = session.captureSave(Set.of(), true);
                var prepared = access.prepareValidated(capture, () -> false);
                require(probe.pushes == 2, "prepareValidated must perform one more full push");
                require(prepared.problems().isEmpty(), "prepared save must have no problems");
                session.enqueueSave(prepared);
                var job = session.dispatchSave().orElseThrow().job().orElseThrow();
                var result = access.publish(job);
                require(result.outcome() == SaveResult.Outcome.CONFIRMED, "validated resource publication must be CONFIRMED");
                require(session.completeSave(job, result), "validated resource save must confirm");
                publishedCommit = result.saved().commitId();
                require(!session.requiresReopen(), "confirmed save must not require recovery");
            }
        }

        // Fresh physical store and registry; no ResourceContent survives any validator callback.
        ExtensionRegistry reopenedRegistry = registry(probe);
        try (LocalProjectStore store = LocalProjectStore.open(root)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            var loaded = access.readValidated(reopenedRegistry, () -> false);
            require(probe.pushes == 3, "fresh readValidated must perform the third full push");
            require(loaded.problems().isEmpty(), "reopened validation must have no problems");
            require(loaded.saved().state().projectId().equals(projectId), "reopened project identity must survive");
            require(loaded.saved().commitId().equals(publishedCommit), "fresh store must reopen the validated publication");
            try (ProjectSession session = ProjectSession.open(access, loaded, reopenedRegistry, 2)) {
                require(!session.protectedMode(), "saved project must reopen healthy");
                require(session.resources().orElseThrow().view().content().versions().containsKey(VERSION),
                        "saved resource version must survive");
            }
        }
        System.out.println("RESOURCE_CONTENT_LARGE_PUSH_PASS sizeMiB=" + sizeMiB
                + " bytesPerPush=" + size + " fullPushes=" + probe.pushes + " sha256=" + probe.expectedSha);
    }

    private static ExtensionRegistry registry(Probe probe) {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1", api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(RESOURCES, "1", api, Set.of(), Set.of()));
        builder.add(ResourceProducers.KIND, contribution(ResourceProducers.KIND.id()), new ResourceProducerSpec(Set.of("1")));
        builder.add(ResourceContentValidators.KIND, contribution(ResourceContentValidators.KIND.id()), probe::validate);
        return builder.freeze();
    }

    private static Contribution contribution(EditorId kind) {
        return new Contribution(new ContributionKey(kind, IMPORTER), OWNER, Contribution.Side.COMMON, Set.of());
    }

    private static final class Probe {
        private final long expectedSize;
        private final String expectedSha;
        private int pushes;

        Probe(long expectedSize, String expectedSha) { this.expectedSize = expectedSize; this.expectedSha = expectedSha; }

        List<Problem> validate(ResourceVersion version, ResourceContent content) throws IOException {
            content.checkCancelled();
            require(version.id().equals(VERSION) && version.resourceId().equals(RESOURCE)
                    && version.importer().equals(IMPORTER) && version.importerVersion().equals("1"), "unexpected version");
            require(version.settings().equals(Map.of("pattern", "position-mod-256")), "unexpected settings");
            require(version.sourceEntry().equals(SOURCE) && version.dependencyVersions().isEmpty()
                    && version.outputEntries().isEmpty(), "unexpected source/dependency/output");
            require(content.resourceTypes().equals(Map.of(RESOURCE, TYPE)) && content.versions().size() == 1
                    && content.byteEntries().size() == 1, "unexpected fixed catalog");
            require(content.byteEntries().get(SOURCE).content().size() == expectedSize, "unexpected declared size");
            DigestSink sink = new DigestSink();
            try {
                content.copyContent(SOURCE, sink); // Always consumes the entire source; no early exception or cancellation.
                require(!sink.closed, "copyContent must not close the caller-owned sink");
                require(sink.count == expectedSize, "full push byte count mismatch");
                require(HexFormat.of().formatHex(sink.digest.digest()).equals(expectedSha), "full push SHA-256 mismatch");
                content.checkCancelled();
            } finally {
                sink.close(); // The validator owns this sink, including on failure.
            }
            pushes++;
            System.out.println("RESOURCE_CONTENT_LARGE_PUSH_COMPLETE push=" + pushes + " bytes=" + sink.count);
            return List.of();
        }
    }

    private static final class DigestSink extends OutputStream {
        private final MessageDigest digest = sha256();
        private long count;
        private boolean closed;

        @Override public void write(int value) throws IOException {
            if (closed) throw new IOException("sink is closed");
            digest.update((byte) value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) throw new IOException("sink is closed");
            digest.update(bytes, offset, length);
            count += length;
        }

        @Override public void close() { closed = true; }
    }

    private static final class PatternSource extends InputStream {
        private final long size;
        private long position;

        PatternSource(long size) { this.size = size; }

        @Override public int read() { return position == size ? -1 : (int) (position++ & 255); }

        @Override public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (position == size) return -1;
            int count = (int) Math.min(length, size - position);
            for (int i = 0; i < count; i++) bytes[offset + i] = (byte) ((position + i) & 255);
            position += count;
            return count;
        }
    }

    private static String expectedDigest(long size) {
        if (size == 128 * MIB) return SHA256_128_MIB;
        // Independent constant-space reference for other accepted sizes; never consumes PatternSource.
        MessageDigest digest = sha256();
        byte[] cycle = new byte[256];
        for (int i = 0; i < cycle.length; i++) cycle[i] = (byte) i;
        for (long remaining = size; remaining > 0; remaining -= cycle.length) digest.update(cycle);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
