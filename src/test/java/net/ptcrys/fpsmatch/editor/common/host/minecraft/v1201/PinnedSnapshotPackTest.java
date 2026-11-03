package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceFilterSection;
import net.ptcrys.fpsmatch.editor.core.packio.PackIndex;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshotException;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Uses captured archives and the actual Minecraft consumer; no fake readers/channels. */
@Timeout(30)
class PinnedSnapshotPackTest {
    private static final BooleanSupplier NEVER = () -> false;
    private static final ResourceLocation SHARED = ResourceLocation.fromNamespaceAndPath("demo", "models/shared.json");
    @TempDir Path workspace;

    @Test
    void actualMinecraftGetAndListFollowBothExplicitPriorityOrders() throws Exception {
        Fixture first = capture(Map.of("assets/demo/models/shared.json", bytes("FIRST"),
                "assets/demo/models/only_first.json", bytes("one")));
        Fixture second = capture(Map.of("assets/demo/models/shared.json", bytes("SECOND"),
                "assets/demo/models/only_second.json", bytes("two")));
        for (boolean reverse : new boolean[] {false, true}) {
            List<PinnedSnapshotPack.Source> order = reverse
                    ? List.of(second.source("second"), first.source("first"))
                    : List.of(first.source("first"), second.source("second"));
            try (PinnedSnapshotPack pack = PinnedSnapshotPack.open("test:ordered", order, NEVER);
                 MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack))) {
                byte[] expected = bytes(reverse ? "SECOND" : "FIRST");
                assertArrayEquals(expected, read(manager.getResource(SHARED).orElseThrow()));
                Map<ResourceLocation, Resource> listed = manager.listResources("models", id -> true);
                assertEquals(Set.of(SHARED, ResourceLocation.fromNamespaceAndPath("demo", "models/only_first.json"),
                        ResourceLocation.fromNamespaceAndPath("demo", "models/only_second.json")), listed.keySet());
                assertArrayEquals(expected, read(listed.get(SHARED)));
                assertEquals(reverse ? "second" : "first", pack.overlay()
                        .resolve("assets/demo/models/shared.json").orElseThrow().winner().layerId());
                assertEquals(2, pack.overlay().resolve("assets/demo/models/shared.json").orElseThrow().candidates().size());
                assertEquals(1, manager.getResourceStack(SHARED).size());
            }
        }
        reusable(first);
        reusable(second);
    }

    @Test
    void nativeTypesHaveIndependentNamespacesContentAndNoFallback() throws Exception {
        Fixture fixture = capture(Map.of("assets/demo/models/shared.json", bytes("client"),
                "data/demo/models/shared.json", bytes("server"),
                "assets/client_only/lang/en_us.json", bytes("language"),
                "data/server_only/recipes/a.json", bytes("recipe"),
                "README", bytes("root"), "gunpack.meta.json", bytes("source metadata")));
        try (PinnedSnapshotPack pack = open(fixture);
             MultiPackResourceManager client = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack));
             MultiPackResourceManager server = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack))) {
            assertEquals(Set.of("client_only", "demo"), client.getNamespaces());
            assertEquals(Set.of("demo", "server_only"), server.getNamespaces());
            assertArrayEquals(bytes("client"), read(client.getResource(SHARED).orElseThrow()));
            assertArrayEquals(bytes("server"), read(server.getResource(SHARED).orElseThrow()));
            assertTrue(server.getResource(ResourceLocation.fromNamespaceAndPath("client_only", "lang/en_us.json")).isEmpty());
            assertTrue(client.getResource(ResourceLocation.fromNamespaceAndPath("server_only", "recipes/a.json")).isEmpty());
            assertEquals(2, client.listResources("", id -> true).size());
            assertEquals(2, server.listResources("", id -> true).size());
            assertEquals(6, pack.overlay().list("").size());
            assertEquals(List.of(), pack.ignoredResourcePaths());
        }
        reusable(fixture);
    }

    @Test
    void exactDirectoryBoundaryAndMalformedNativePathsRemainAuditableUnderTurkishLocale() throws Exception {
        Fixture fixture = capture(Map.of("assets/demo/models/a.json", bytes("a"),
                "assets/demo/models/sub/b.json", bytes("b"),
                "assets/demo/models2/c.json", bytes("c"),
                "assets/UPPER/models/x.json", bytes("bad namespace"),
                "assets/demo/models/I.json", bytes("bad case"),
                "data/demo/recipes/a).json", bytes("bad punctuation"),
                "assets/incomplete", bytes("missing native path"), "README", bytes("non-native")));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            try (PinnedSnapshotPack pack = open(fixture);
                 MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack))) {
                assertEquals(Set.of(ResourceLocation.fromNamespaceAndPath("demo", "models/a.json"),
                        ResourceLocation.fromNamespaceAndPath("demo", "models/sub/b.json")),
                        manager.listResources("models", id -> true).keySet());
                assertEquals(3, manager.listResources("", id -> true).size());
                assertTrue(manager.listResources("model", id -> true).isEmpty());
                assertEquals(Set.of("demo"), pack.getNamespaces(PackType.CLIENT_RESOURCES));
                assertEquals(Set.of(), pack.getNamespaces(PackType.SERVER_DATA));
                assertEquals(List.of("assets/UPPER/models/x.json", "assets/demo/models/I.json",
                        "assets/incomplete", "data/demo/recipes/a).json"), pack.ignoredResourcePaths());
                assertThrows(UnsupportedOperationException.class, () -> pack.ignoredResourcePaths().add("x"));
                assertThrows(UnsupportedOperationException.class, () -> pack.getNamespaces(PackType.CLIENT_RESOURCES).add("x"));
                assertNull(pack.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", "models/i.json")));
                for (String prefix : List.of("/models", "models/", "models//sub", "models/../sub", ".", "..", "models\\sub")) {
                    assertThrows(IllegalArgumentException.class,
                            () -> pack.listResources(PackType.CLIENT_RESOURCES, "demo", prefix, (id, supplier) -> fail("invalid prefix emitted")));
                }
                for (String namespace : List.of("", "UPPER", "bad)", ".", "..")) {
                    assertThrows(IllegalArgumentException.class,
                            () -> pack.listResources(PackType.CLIENT_RESOURCES, namespace, "", (id, supplier) -> fail("invalid namespace emitted")));
                }
                for (String path : List.of("", ".", "..", "/models/a.json", "models/", "models//a.json", "models/../a.json")) {
                    assertNull(pack.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", path)));
                }
                // These identifiers are legal to MC, but no captured physical entry can use them.
                for (ResourceLocation absent : List.of(ResourceLocation.fromNamespaceAndPath("con", "a"),
                        ResourceLocation.fromNamespaceAndPath("demo", "con"), ResourceLocation.fromNamespaceAndPath("demo", "segment./a"))) {
                    assertNull(pack.getResource(PackType.CLIENT_RESOURCES, absent));
                }
                for (String prefix : List.of("con", "segment.", "segment./a")) {
                    pack.listResources(PackType.CLIENT_RESOURCES, "demo", prefix, (id, supplier) -> fail("absent physical prefix emitted"));
                }
                pack.listResources(PackType.CLIENT_RESOURCES, "con", "", (id, supplier) -> fail("absent physical namespace emitted"));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void synthesizedRootUsesActualMinecraftDeserializerAndNeverSourceMetadata() throws Exception {
        Fixture fixture = capture(Map.of("pack.mcmeta", bytes("{\"pack\":{\"pack_format\":1,\"description\":\"source\"},\"filter\":{\"block\":[{}]}}"),
                "gunpack.meta.json", bytes("private source metadata"), "assets/demo/a", bytes("a")));
        try (PinnedSnapshotPack pack = open(fixture)) {
            PackMetadataSection metadata = present(pack.getMetadataSection(PackMetadataSection.TYPE));
            assertEquals(15, metadata.getPackFormat(PackType.CLIENT_RESOURCES));
            assertEquals(15, metadata.getPackFormat(PackType.SERVER_DATA));
            TranslatableContents description = assertInstanceOf(TranslatableContents.class, metadata.getDescription().getContents());
            assertEquals("fpsmatch.editor.snapshot_pack", description.getKey());
            assertNull(pack.getMetadataSection(ResourceFilterSection.TYPE));
            assertNull(pack.getRootResource("gunpack.meta.json"));
            assertNull(pack.getRootResource("README"));
            assertNull(pack.getRootResource("pack.mcmeta", "extra"));
            assertNull(pack.getRootResource("..", "pack.mcmeta"));
            assertNull(pack.getRootResource("../pack.mcmeta"));
            assertNull(pack.getRootResource());
            assertTrue(pack.overlay().resolve("pack.mcmeta").isPresent());
        }
    }

    @Test
    void delayedSuppliersAndOpenedStreamsHonorCloseWhileImmutableEvidenceSurvives() throws Exception {
        Fixture fixture = capture(Map.of("assets/demo/a", bytes("physical")));
        PinnedSnapshotPack pack = open(fixture);
        IoSupplier<InputStream> old = present(pack.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", "a")));
        IoSupplier<InputStream> root = present(pack.getRootResource("pack.mcmeta"));
        var overlay = pack.overlay();
        var ignored = pack.ignoredResourcePaths();
        try (InputStream physical = old.get(); InputStream metadata = root.get()) {
            pack.close();
            pack.close();
            assertThrows(IOException.class, old::get);
            assertThrows(IOException.class, root::get);
            assertThrows(IOException.class, physical::read);
            assertThrows(IOException.class, physical::available);
            String json = new String(metadata.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("fpsmatch.editor.snapshot_pack"));
            assertThrows(IllegalStateException.class, () -> pack.getRootResource("pack.mcmeta"));
            assertThrows(IllegalStateException.class, () -> pack.getResource(PackType.CLIENT_RESOURCES, SHARED));
            assertThrows(IllegalStateException.class, () -> pack.getNamespaces(PackType.CLIENT_RESOURCES));
            assertThrows(IllegalStateException.class, () -> pack.listResources(PackType.CLIENT_RESOURCES, "demo", "", (id, supplier) -> fail()));
            assertSame(overlay, pack.overlay());
            assertEquals(ignored, pack.ignoredResourcePaths());
            assertEquals("test", pack.packId());
            assertFalse(pack.isBuiltin());
        } finally {
            pack.close();
        }
        reusable(fixture);
    }

    @Test
    void listCallbackCanCloseFromAnotherThreadWithoutOwnerLockDeadlock() throws Exception {
        Fixture fixture = capture(Map.of("assets/demo/a", bytes("a"), "assets/demo/b", bytes("b")));
        PinnedSnapshotPack pack = open(fixture);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        List<Future<?>> futures = new ArrayList<>();
        List<ResourceLocation> emissions = new ArrayList<>();
        try {
            pack.listResources(PackType.CLIENT_RESOURCES, "demo", "", (id, supplier) -> {
                if (emissions.isEmpty()) {
                    Future<?> close = closer.submit(pack::close);
                    futures.add(close);
                    assertDoesNotThrow(() -> close.get(3, TimeUnit.SECONDS), "external callback must run outside pack owner lock");
                }
                emissions.add(id);
                assertThrows(IOException.class, supplier::get);
            });
            assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("demo", "a"), ResourceLocation.fromNamespaceAndPath("demo", "b")), emissions);
        } finally {
            for (Future<?> future : futures) future.cancel(true);
            closer.shutdownNow();
            assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS), "close worker must terminate");
            pack.close();
        }
        reusable(fixture);
    }

    @Test
    void actualMinecraftResourceOpenedAfterManagerCloseFails() throws Exception {
        Fixture fixture = capture(Map.of("assets/demo/models/shared.json", bytes("payload")));
        PinnedSnapshotPack pack = open(fixture);
        MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack));
        try {
            Resource oldGet = manager.getResource(SHARED).orElseThrow();
            Resource oldList = manager.listResources("models", id -> true).get(SHARED);
            manager.close();
            assertThrows(IOException.class, oldGet::open);
            assertThrows(IOException.class, oldList::open);
        } finally {
            manager.close();
            pack.close();
        }
    }

    @Test
    void badExpectedHashAndCorruptSecondSourceReleaseEarlierArchives() throws Exception {
        Fixture first = capture(Map.of("assets/demo/a", bytes("first")));
        Fixture second = capture(Map.of("assets/demo/b", bytes("second")));
        String wrong = first.index().sha256().equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64);
        code(PackSnapshotException.Code.CONTENT_MISMATCH, () -> PinnedSnapshotPack.open("bad-hash",
                List.of(new PinnedSnapshotPack.Source("first", first.archive(), wrong)), NEVER));
        reusable(first);
        Path broken = workspace.resolve("broken.pack");
        Files.write(broken, bytes("corrupt archive"));
        code(PackSnapshotException.Code.INVALID_ARCHIVE, () -> PinnedSnapshotPack.open("bad-second",
                List.of(first.source("first"), new PinnedSnapshotPack.Source("second", broken, second.index().sha256())), NEVER));
        reusable(first);
        Files.write(broken, Files.readAllBytes(second.archive()));
        try (PackSnapshot recovered = PackSnapshots.open(broken, NEVER)) {
            assertEquals(second.index().sha256(), recovered.index().sha256());
        }
        Files.delete(broken);
        code(PackSnapshotException.Code.CONTENT_MISMATCH, () -> PinnedSnapshotPack.open("second-hash",
                List.of(first.source("first"), new PinnedSnapshotPack.Source("second", second.archive(), wrong)), NEVER));
        reusable(first);
        reusable(second);
    }

    @Test
    void cancellationAtEveryObservedFactoryPollClosesAllReadersAndDoesNotEscapeIntoSuppliers() throws Exception {
        Fixture first = capture(Map.of("assets/demo/a", bytes("one")));
        Fixture second = capture(Map.of("assets/demo/b", bytes("two")));
        List<PinnedSnapshotPack.Source> sources = List.of(first.source("first"), second.source("second"));
        AtomicInteger totalPolls = new AtomicInteger();
        AtomicBoolean cancelAfterPublish = new AtomicBoolean();
        try (PinnedSnapshotPack pack = PinnedSnapshotPack.open("baseline", sources, () -> {
            totalPolls.incrementAndGet();
            return cancelAfterPublish.get();
        })) {
            int pollsAtPublish = totalPolls.get();
            cancelAfterPublish.set(true);
            assertArrayEquals(bytes("one"), read(present(pack.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", "a")))));
            assertEquals(pollsAtPublish, totalPolls.get(), "successful suppliers must not retain the factory cancellation closure");
        }
        assertTrue(totalPolls.get() > 2);
        for (int stop = 1; stop <= totalPolls.get(); stop++) {
            int cancelAt = stop;
            AtomicInteger calls = new AtomicInteger();
            code(PackSnapshotException.Code.CANCELLED, () -> PinnedSnapshotPack.open("cancelled", sources,
                    () -> calls.incrementAndGet() >= cancelAt));
            reusable(first);
            reusable(second);
        }
        try {
            Thread.currentThread().interrupt();
            code(PackSnapshotException.Code.CANCELLED, () -> PinnedSnapshotPack.open("interrupted", sources, NEVER));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        reusable(first);
        reusable(second);
    }

    @Test
    void factoryPreflightsSourcesAndFreezesCallerListAndPath() throws Exception {
        Fixture fixture = capture(Map.of("assets/demo/a", bytes("a")));
        PinnedSnapshotPack.Source valid = fixture.source("first");
        assertThrows(IllegalArgumentException.class, () -> new PinnedSnapshotPack.Source("UPPER", fixture.archive(), fixture.index().sha256()));
        assertThrows(IllegalArgumentException.class, () -> new PinnedSnapshotPack.Source("first", fixture.archive(), "A".repeat(64)));
        assertThrows(NullPointerException.class, () -> new PinnedSnapshotPack.Source("first", null, fixture.index().sha256()));
        assertEquals(fixture.archive().toAbsolutePath().normalize(), new PinnedSnapshotPack.Source("first",
                fixture.archive().getParent().resolve("unused/../" + fixture.archive().getFileName()), fixture.index().sha256()).archive());
        List<PinnedSnapshotPack.Source> oversized = new AbstractList<>() {
            @Override public int size() { return 33; }
            @Override public PinnedSnapshotPack.Source get(int index) { throw new AssertionError("size gate must precede traversal"); }
        };
        assertThrows(IllegalArgumentException.class, () -> PinnedSnapshotPack.open("test", oversized, NEVER));
        assertThrows(IllegalArgumentException.class, () -> PinnedSnapshotPack.open("test", List.of(), NEVER));
        assertThrows(IllegalArgumentException.class, () -> PinnedSnapshotPack.open("Bad id", List.of(valid), NEVER));
        Path missing = workspace.resolve("missing.pack");
        PinnedSnapshotPack.Source unopened = new PinnedSnapshotPack.Source("duplicate", missing, fixture.index().sha256());
        assertThrows(IllegalArgumentException.class, () -> PinnedSnapshotPack.open("test", List.of(unopened, unopened), NEVER));
        List<PinnedSnapshotPack.Source> mutable = new ArrayList<>(List.of(valid));
        try (PinnedSnapshotPack pack = PinnedSnapshotPack.open("test", mutable, NEVER)) {
            mutable.clear();
            assertArrayEquals(bytes("a"), read(present(pack.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", "a")))));
        }
    }

    @Test
    void cumulativeOverlayBudgetFailureLeavesTheSharedPhysicalArchiveReusable() throws Exception {
        Path zip = workspace.resolve("many.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 5500; i++) {
                output.putNextEntry(new ZipEntry("assets/demo/many/" + i));
                output.closeEntry();
            }
        }
        Path archive = workspace.resolve("many.pack");
        Fixture fixture = new Fixture(archive, PackSnapshots.captureZip(zip, archive, NEVER));
        assertThrows(IllegalArgumentException.class, () -> PinnedSnapshotPack.open("budget",
                List.of(fixture.source("a"), fixture.source("b"), fixture.source("c")), NEVER));
        reusable(fixture);
    }

    @Test
    void concurrentRealSuppliersEnforceIndependent64StreamBudgetsAndReleaseSlots() throws Exception {
        Fixture first = capture(Map.of("assets/demo/a", bytes("first")));
        Fixture second = capture(Map.of("assets/demo/b", bytes("second")));
        try (PinnedSnapshotPack pack = PinnedSnapshotPack.open("streams", List.of(first.source("first"), second.source("second")), NEVER)) {
            IoSupplier<InputStream> a = present(pack.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", "a")));
            IoSupplier<InputStream> b = present(pack.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", "b")));
            ExecutorService executor = Executors.newFixedThreadPool(8);
            List<InputStream> held = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> opens = new ArrayList<>();
            try {
                for (int i = 0; i < 64; i++) {
                    for (IoSupplier<InputStream> supplier : List.of(a, b)) {
                        opens.add(executor.submit(() -> {
                            InputStream input = supplier.get();
                            held.add(input);
                            return null;
                        }));
                    }
                }
                for (Future<?> future : opens) future.get(5, TimeUnit.SECONDS);
                assertEquals(128, held.size());
                code(PackSnapshotException.Code.BUDGET, a::get);
                code(PackSnapshotException.Code.BUDGET, b::get);
                // EOF still owns a slot until close, including through the native supplier.
                assertTrue(held.get(0).readAllBytes().length > 0);
                code(PackSnapshotException.Code.BUDGET, a::get);
                code(PackSnapshotException.Code.BUDGET, b::get);
                for (InputStream input : held) input.close();
                held.clear();
                try (InputStream newA = a.get(); InputStream newB = b.get()) {
                    assertArrayEquals(bytes("first"), newA.readAllBytes());
                    assertArrayEquals(bytes("second"), newB.readAllBytes());
                }
            } finally {
                for (Future<?> future : opens) future.cancel(true);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "supplier workers must terminate before cleanup");
                for (InputStream input : held) input.close();
            }
        }
        reusable(first);
        reusable(second);
    }

    @Test
    void nativeSupplierRetainsQ45InterruptionAndDoesNotReserveAFailedOpenSlot() throws Exception {
        Fixture fixture = capture(Map.of("assets/demo/a", bytes("payload")));
        try (PinnedSnapshotPack pack = open(fixture)) {
            IoSupplier<InputStream> supplier = present(pack.getResource(PackType.CLIENT_RESOURCES,
                    ResourceLocation.fromNamespaceAndPath("demo", "a")));
            try {
                Thread.currentThread().interrupt();
                code(PackSnapshotException.Code.CANCELLED, supplier::get);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            List<InputStream> held = new ArrayList<>();
            try {
                for (int i = 0; i < 64; i++) held.add(supplier.get());
                code(PackSnapshotException.Code.BUDGET, supplier::get);
                try {
                    Thread.currentThread().interrupt();
                    code(PackSnapshotException.Code.CANCELLED, held.get(0)::read);
                    assertTrue(Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                }
                assertThrows(IOException.class, held.get(0)::read);
                assertArrayEquals(bytes("payload"), held.get(1).readAllBytes());
            } finally {
                for (InputStream input : held) input.close();
            }
        }
        reusable(fixture);
    }

    @Test
    void sameLengthExternalMutationFailsCompleteMinecraftConsumptionWithoutFallback() throws Exception {
        byte[] original = new byte[150_003];
        new java.util.Random(9173).nextBytes(original);
        Fixture first = capture(Map.of("assets/demo/models/shared.json", original));
        Fixture lower = capture(Map.of("assets/demo/models/shared.json", bytes("lower must not hide failure")));
        byte[] archiveBefore = Files.readAllBytes(first.archive());
        int payload = uniqueOccurrence(archiveBefore, original);
        try (PinnedSnapshotPack pack = PinnedSnapshotPack.open("mutated", List.of(first.source("first"), lower.source("lower")), NEVER);
             MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack));
             InputStream input = manager.getResource(SHARED).orElseThrow().open()) {
            assertArrayEquals(java.util.Arrays.copyOf(original, 32), input.readNBytes(32));
            long length = Files.size(first.archive());
            try (FileChannel writer = FileChannel.open(first.archive(), StandardOpenOption.WRITE)) {
                ByteBuffer changed = ByteBuffer.wrap(new byte[] {(byte) (original[original.length - 1] ^ 0x55)});
                while (changed.hasRemaining()) writer.write(changed, payload + original.length - 1L);
            }
            assertEquals(length, Files.size(first.archive()));
            code(PackSnapshotException.Code.CONTENT_MISMATCH, input::readAllBytes);
            assertThrows(IOException.class, input::read);
            try (InputStream listed = manager.listResources("models", id -> true).get(SHARED).open()) {
                code(PackSnapshotException.Code.CONTENT_MISMATCH, listed::readAllBytes);
            }
        }
        Files.write(first.archive(), archiveBefore);
        reusable(first);
        reusable(lower);
    }

    private PinnedSnapshotPack open(Fixture fixture) throws IOException {
        return PinnedSnapshotPack.open("test", List.of(fixture.source("fixture")), NEVER);
    }

    private Fixture capture(Map<String, byte[]> files) throws IOException {
        Path directory = Files.createTempDirectory(workspace, "source-");
        for (var file : new TreeMap<>(files).entrySet()) {
            Path path = directory.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
        }
        Path archive = workspace.resolve(directory.getFileName() + ".pack");
        PackIndex index = PackSnapshots.captureDirectory(directory, archive, NEVER);
        // Detach every content fixture from its original source before native consumption.
        for (String path : files.keySet()) Files.delete(directory.resolve(path));
        return new Fixture(archive, index);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static <T> T present(T value) {
        assertNotNull(value);
        return value;
    }

    private static byte[] read(Resource resource) throws IOException {
        try (InputStream input = resource.open()) { return input.readAllBytes(); }
    }

    private static byte[] read(IoSupplier<InputStream> supplier) throws IOException {
        try (InputStream input = supplier.get()) { return input.readAllBytes(); }
    }

    /** Operational evidence only: shared-open OS semantics cannot prove that no hidden reader survives. */
    private static void reusable(Fixture fixture) throws IOException {
        byte[] original = Files.readAllBytes(fixture.archive());
        try (FileChannel writable = FileChannel.open(fixture.archive(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertEquals(original.length, writable.size());
        }
        Files.delete(fixture.archive());
        Files.write(fixture.archive(), original, StandardOpenOption.CREATE_NEW);
        try (PackSnapshot reopened = PackSnapshots.open(fixture.archive(), NEVER)) {
            assertEquals(fixture.index().sha256(), reopened.index().sha256());
        }
        assertEquals(fixture.index().sha256(), HexFormat.of().formatHex(sha256(original)));
    }

    private static byte[] sha256(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static int uniqueOccurrence(byte[] archive, byte[] payload) {
        int found = -1;
        for (int at = 0; at <= archive.length - payload.length; at++) {
            if (java.util.Arrays.equals(archive, at, at + payload.length, payload, 0, payload.length)) {
                assertEquals(-1, found, "fixture payload must occur exactly once");
                found = at;
            }
        }
        assertTrue(found >= 0, "fixture payload must be present without relying on archive-layout offsets");
        return found;
    }

    private static void code(PackSnapshotException.Code expected, IoAction action) {
        PackSnapshotException failure = assertThrows(PackSnapshotException.class, action::run);
        assertEquals(expected, failure.code());
    }

    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    private record Fixture(Path archive, PackIndex index) {
        PinnedSnapshotPack.Source source(String id) {
            return new PinnedSnapshotPack.Source(id, archive, index.sha256());
        }
    }
}
