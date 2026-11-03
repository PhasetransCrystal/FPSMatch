package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.resources;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItemModules;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.*;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class TaczResourceImporterTest {
    @TempDir Path temporary;
    private static final UUID SEED = new UUID(0x82570001L, 1);
    private static final ResourceCatalog EMPTY = new ResourceCatalog(Map.of(), Map.of(), Map.of());
    private static final BuiltinWeaponPackFormat.Source PROFILE = BuiltinWeaponPackFormat.Source.TACZ_DEFAULT;

    @Test void registrationAndRejectionsNeverOpenUntrustedOrCancelledInput() throws Exception {
        var registry = registry(); var importer = importer(registry);
        ResourceImporters.require(registry, WeaponItemValues.TACZ_IMPORTER, importer);
        assertEquals(List.of("container"), importer.inputs().stream().map(ResourceImporter.Input::id).toList());
        assertEquals(ResourceImporter.MAX_INPUT_BYTES, importer.inputs().get(0).maxBytes());
        var opened = new AtomicBoolean();
        var source = new ResourceImporter.Source() {
            public BlobRef content() { return new BlobRef(BlobRef.Kind.ASSET, PROFILE.containerSha256(), 10); }
            public InputStream open() { opened.set(true); throw new AssertionError("must not open"); }
        };
        assertThrows(IOException.class, () -> importer.inspect(registry, Map.of(), EMPTY, SEED, () -> false));
        assertThrows(ResourceValidationCancelledException.class,
                () -> importer.inspect(registry, Map.of("container", source), EMPTY, SEED, () -> true));
        var wrong = new ResourceImporter.Source() {
            public BlobRef content() { return new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 10); }
            public InputStream open() { throw new AssertionError("unsupported source must not open"); }
        };
        var rejected = assertThrows(ResourceImportException.class,
                () -> importer.inspect(registry, Map.of("container", wrong), EMPTY, SEED, () -> false));
        assertEquals(new TextContent.Message("editor.workbench.resource_tacz_unsupported", List.of()), rejected.problems().get(0).message());
        assertFalse(opened.get());
    }

    @Test void dishonestBytesAndReadCancellationReleaseTheSource() throws Exception {
        var registry = registry(); var importer = importer(registry);
        for (boolean cancel : List.of(false, true)) {
            var closed = new AtomicBoolean(); var cancelled = new AtomicBoolean();
            var source = new ResourceImporter.Source() {
                public BlobRef content() { return new BlobRef(BlobRef.Kind.ASSET, PROFILE.containerSha256(), 1); }
                public InputStream open() {
                    return new ByteArrayInputStream(new byte[] { 7 }) {
                        @Override public synchronized int read(byte[] bytes, int off, int length) {
                            int read = super.read(bytes, off, length); if (cancel) cancelled.set(true); return read;
                        }
                        @Override public void close() throws IOException { closed.set(true); super.close(); }
                    };
                }
            };
            if (cancel) assertThrows(ResourceValidationCancelledException.class,
                    () -> importer.inspect(registry, Map.of("container", source), EMPTY, SEED, cancelled::get));
            else assertThrows(IOException.class,
                    () -> importer.inspect(registry, Map.of("container", source), EMPTY, SEED, () -> false));
            assertTrue(closed.get()); assertTrue(EMPTY.resources().isEmpty());
        }
    }

    @Test @EnabledIfSystemProperty(named = "editor.builtin.taczJar", matches = ".+")
    void originalProducesAll47GunsWithExactDisplayClosuresAndPrivateOwnedBytes() throws Exception {
        var registry = registry(); var importer = importer(registry);
        Path original = temporary.resolve("original.jar"); Files.copy(Path.of(System.getProperty("editor.builtin.taczJar")), original);
        var source = fileSource(original);
        try (var prepared = importer.inspect(registry, Map.of("container", source), EMPTY, SEED, () -> false)) {
            var candidate = prepared.candidate();
            assertEquals(96, candidate.resources().size()); assertEquals(96, candidate.versions().size());
            assertEquals(96, candidate.byteEntries().size()); assertEquals(candidate.byteEntries().keySet(), prepared.sources().keySet());
            var all = new HashSet<>(candidate.resources().keySet()); all.addAll(candidate.versions().keySet()); all.addAll(candidate.byteEntries().keySet());
            assertEquals(288, all.size()); assertEquals(3, prepared.summary().size());
            assertThrows(IOException.class, () -> importer.inspect(registry, Map.of("container", source), candidate, SEED, () -> false));
            var displays = new HashMap<EditorId, ResourceVersion>();
            for (var version : candidate.versions().values()) {
                var logical = candidate.resources().get(version.resourceId());
                if (logical.type().equals(WeaponItemValues.TACZ_DISPLAY_ASSET)) {
                    var asset = TaczBuiltinAssetFormat.asset(version, logical.type()).orElseThrow();
                    displays.put(asset.id(), version); assertEquals(2, version.dependencyVersions().size());
                }
            }
            assertEquals(47, displays.size()); int guns = 0;
            for (var version : candidate.versions().values()) {
                var logical = candidate.resources().get(version.resourceId());
                assertTrue(importer.resourceLabel(candidate, logical.id()).isPresent());
                if (logical.type().equals(WeaponItemValues.TACZ_GUN_ASSET)) {
                    var asset = TaczBuiltinAssetFormat.asset(version, logical.type()).orElseThrow();
                    var display = displays.get(asset.defaultDisplay()); var expected = new HashSet<>(display.dependencyVersions()); expected.add(display.id());
                    assertEquals(expected, version.dependencyVersions()); guns++;
                }
                var renamed = new HashMap<>(candidate.resources());
                renamed.put(logical.id(), new LogicalResource(logical.id(), logical.type(), "User name", logical.currentVersion()));
                assertTrue(importer.resourceLabel(new ResourceCatalog(renamed, candidate.versions(), candidate.byteEntries()), logical.id()).isEmpty());
            }
            assertEquals(47, guns);
            Files.delete(original);
            for (var entry : prepared.sources().entrySet()) {
                var bytes = entry.getValue(); assertEquals(candidate.byteEntries().get(entry.getKey()).content(), bytes.content());
                var digest = MessageDigest.getInstance("SHA-256"); long count = 0;
                try (var input = new DigestInputStream(bytes.open(), digest)) { count = input.transferTo(OutputStream.nullOutputStream()); }
                assertEquals(bytes.content().size(), count); assertEquals(bytes.content().sha256(), HexFormat.of().formatHex(digest.digest()));
            }
            var heldSource = prepared.sources().values().iterator().next(); var stream = heldSource.open();
            prepared.close(); assertThrows(IOException.class, heldSource::open); assertThrows(IOException.class, stream::read); prepared.close();
        }
    }
    @Test @EnabledIfSystemProperty(named = "editor.builtin.taczJar", matches = ".+")
    void fiveIndependentCompleteImportsSaveAndReopenWithinTheUnchangedValidationBudget() throws Exception {
        var registry = registry(); var importer = importer(registry);
        var source = fileSource(Path.of(System.getProperty("editor.builtin.taczJar")));
        Path project = temporary.resolve("five-imports"); ResourceCatalog catalog = EMPTY;
        try (var store = LocalProjectStore.create(project)) {
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "five complete imports",
                    Map.of(), Map.of(), Map.of(), null));
            for (int iteration = 0; iteration < 5; iteration++) {
                try (var prepared = importer.inspect(registry, Map.of("container", source), catalog,
                        new UUID(SEED.getMostSignificantBits(), iteration + 1), () -> false)) {
                    for (var bytes : prepared.sources().values()) {
                        try (var input = bytes.open()) {
                            assertEquals(bytes.content(), store.stage(BlobRef.Kind.ASSET, input));
                        }
                    }
                    catalog = prepared.candidate();
                }
            }
            assertEquals(480, catalog.resources().size());
            assertEquals(480, catalog.versions().size());
            assertEquals(480, catalog.byteEntries().size());
            var access = new LocalProjectAccess(store);
            try (var session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                var handle = session.resources().orElseThrow(); ResourceCatalog captured = catalog;
                handle.edit(handle.view().token(), new TextContent.Literal("five imports"), ignored -> captured);
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                var job = session.dispatchSave().orElseThrow().job().orElseThrow();
                var result = access.publish(job);
                assertEquals(SaveResult.Outcome.CONFIRMED, result.outcome());
                assertTrue(session.completeSave(job, result));
            }
        }
        try (var store = LocalProjectStore.open(project)) {
            var loaded = new LocalProjectAccess(store).readValidated(registry, () -> false);
            assertTrue(loaded.problems().stream().noneMatch(p -> p.severity() == Problem.Severity.ERROR), loaded.problems().toString());
            assertEquals(catalog, loaded.resources().orElseThrow());
        }
    }

    private static ResourceImporter.Source fileSource(Path path) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), hash)) { input.transferTo(OutputStream.nullOutputStream()); }
        var blob = new BlobRef(BlobRef.Kind.ASSET, HexFormat.of().formatHex(hash.digest()), Files.size(path));
        return new ResourceImporter.Source() {
            public BlobRef content() { return blob; }
            public InputStream open() throws IOException { return Files.newInputStream(path); }
        };
    }
    private static ResourceImporter importer(ExtensionRegistry registry) {
        return registry.find(ResourceImporters.KIND, WeaponItemValues.TACZ_IMPORTER).orElseThrow();
    }
    private static ExtensionRegistry registry() {
        var api = new ApiVersion(1, 0); var b = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        b.define(DocumentTypes.KIND); b.addModule(new ModuleSpec(EditorId.parse("editor:resources"), "1", api, Set.of(), Set.of()));
        BasicItemModules.registerVanilla(b, "test", "1.20.1"); WeaponItemValues.registerTacz(b, "test", "1.1.7-hotfix");
        BuiltinWeaponPackFormat.registerTacz(b); TaczBuiltinAssetFormat.register(b); TaczResourceImporter.register(b); return b.freeze();
    }
}
