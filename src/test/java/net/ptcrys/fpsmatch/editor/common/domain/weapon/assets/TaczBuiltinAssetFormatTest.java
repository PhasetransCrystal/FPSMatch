package net.ptcrys.fpsmatch.editor.common.domain.weapon.assets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItemModules;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.BuiltinWeaponPackFormat.Source;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.TaczBuiltinAssetFormat.Asset;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.resources.BuiltinWeaponPacks;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.session.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Only the property-gated original-byte chain grants positive source authentication. */
class TaczBuiltinAssetFormatTest {
    @TempDir Path temporary;
    private static final UUID DEFAULT_VERSION = id(2), DEFAULT_BYTE = id(3), ORDINARY_VERSION = id(5);
    private static final UUID DISPLAY_VERSION = id(12), GUN_VERSION = id(22);
    private static final Asset GUN = find("tacz:ak47"), DISPLAY = find("tacz:ak47_display");

    @Test void allFortySevenGunsAndTheirOriginalDisplaysHaveStableFixedDescriptors() {
        var all = TaczBuiltinAssetFormat.assets();
        assertEquals(94, all.size());
        assertEquals(94, all.stream().map(Asset::id).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
        var registry = registry(); var producers = ResourceProducerCatalog.activate(registry);
        ResourceCatalog catalog = packs();
        Map<EditorId, UUID> displays = new HashMap<>();
        int identity = 100;
        for (Asset asset : all) if (asset.defaultDisplay() == null) {
            UUID resource = id(identity++), version = id(identity++), source = id(identity++);
            displays.put(asset.id(), version);
            catalog = asset.addTo(catalog, resource, version, source, blob(asset), DEFAULT_VERSION, ORDINARY_VERSION, null);
        }
        for (Asset asset : all) if (asset.defaultDisplay() != null) {
            UUID resource = id(identity++), version = id(identity++), source = id(identity++);
            catalog = asset.addTo(catalog, resource, version, source, blob(asset), DEFAULT_VERSION, ORDINARY_VERSION, displays.get(asset.defaultDisplay()));
        }
        assertEquals(96, catalog.resources().size());
        for (var version : catalog.versions().values()) {
            if (!version.importer().equals(WeaponItemValues.TACZ_IMPORTER)) continue;
            EditorId type = catalog.resources().get(version.resourceId()).type();
            Asset asset = TaczBuiltinAssetFormat.asset(version, type).orElseThrow();
            assertEquals(WeaponItemValues.TACZ_MODULE, registry.metadata().contributions().get(producers.requireContribution(version)).owner());
            assertEquals(WeaponItemValues.TACZ_MODULE, registry.metadata().contributions().get(producers.requireValidationContribution(version)).owner());
            assertEquals(asset.defaultDisplay() == null ? Set.of(DEFAULT_VERSION, ORDINARY_VERSION)
                    : Set.of(DEFAULT_VERSION, ORDINARY_VERSION, displays.get(asset.defaultDisplay())), version.dependencyVersions());
            assertEquals(asset.id().toString(), version.settings().get("assetId"));
            assertTrue(asset.labelKey().startsWith("tacz.gun."));
            assertTrue(version.outputEntries().isEmpty());
        }
        assertTrue(all.subList(0, 47).stream().allMatch(asset -> asset.type().equals(WeaponItemValues.TACZ_GUN_ASSET)));
        assertTrue(all.subList(47, 94).stream().allMatch(asset -> asset.type().equals(WeaponItemValues.TACZ_DISPLAY_ASSET)));
        assertEquals(all.subList(0, 47).stream().map(a -> a.id().toString()).sorted().toList(), all.subList(0, 47).stream().map(a -> a.id().toString()).toList());
        assertEquals(all.subList(47, 94).stream().map(a -> a.id().toString()).sorted().toList(), all.subList(47, 94).stream().map(a -> a.id().toString()).toList());
        assertThrows(IllegalArgumentException.class, () -> GUN.addTo(packs(), id(31), id(32), id(33), blob(GUN), DEFAULT_VERSION, ORDINARY_VERSION, null));
        assertThrows(IllegalArgumentException.class, () -> DISPLAY.addTo(packs(), id(31), id(32), id(33), blob(DISPLAY), DEFAULT_VERSION, ORDINARY_VERSION, DISPLAY_VERSION));
        assertThrows(IllegalArgumentException.class, () -> DISPLAY.addTo(packs(), id(31), id(32), id(33), new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), DISPLAY.size()), DEFAULT_VERSION, ORDINARY_VERSION, null));
    }

    @Test void exactDescriptorAndSourceMetadataAreRequiredBeforeAnyByteCopy() throws Exception {
        var catalog = metadata();
        for (UUID versionId : List.of(GUN_VERSION, DISPLAY_VERSION)) {
            var original = catalog.versions().get(versionId);
            var type = catalog.resources().get(original.resourceId()).type();
            for (var bad : List.of(
                    change(original, EditorId.parse("test:other"), "1", original.settings(), original.sourceEntry(), original.dependencyVersions(), Set.of()),
                    change(original, original.importer(), "2", original.settings(), original.sourceEntry(), original.dependencyVersions(), Set.of()),
                    change(original, original.importer(), "1", original.settings(), null, original.dependencyVersions(), Set.of()),
                    change(original, original.importer(), "1", original.settings(), id(999), original.dependencyVersions(), Set.of()),
                    change(original, original.importer(), "1", original.settings(), original.sourceEntry(), original.dependencyVersions(), Set.of(DEFAULT_BYTE)))) reject(bad, catalog);
            assertTrue(TaczBuiltinAssetFormat.asset(change(original, original.importer(), "2", original.settings(), original.sourceEntry(), original.dependencyVersions(), Set.of()), type).isEmpty());
            assertTrue(TaczBuiltinAssetFormat.asset(original, EditorId.parse("test:other")).isEmpty());
            for (String field : List.of("assetId", "profile", "indexPath", "extra")) {
                var settings = new HashMap<>(original.settings()); settings.put(field, "changed");
                var bad = change(original, original.importer(), "1", settings, original.sourceEntry(), original.dependencyVersions(), Set.of());
                reject(bad, catalog); assertTrue(TaczBuiltinAssetFormat.asset(bad, type).isEmpty());
            }
            var source = catalog.byteEntries().get(original.sourceEntry());
            for (var altered : List.of(
                    entry(source, "test:owner", source.typeId(), source.codecId(), 1, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), "test:type", source.codecId(), 1, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), "test:codec", 1, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 2, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, new BlobRef(BlobRef.Kind.DOCUMENT, source.content().sha256(), source.content().size()), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), source.content().size()), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, new BlobRef(BlobRef.Kind.ASSET, source.content().sha256(), source.content().size() + 1), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, source.content(), Set.of(id(99)), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, source.content(), Set.of(), Set.of(id(99))))) {
                var entries = new HashMap<>(catalog.byteEntries()); entries.put(altered.id(), altered);
                if (altered.content().kind() != BlobRef.Kind.ASSET) {
                    assertThrows(IllegalArgumentException.class, () -> new ResourceCatalog(catalog.resources(), catalog.versions(), entries));
                } else reject(original, new ResourceCatalog(catalog.resources(), catalog.versions(), entries));
            }
        }
    }

    @Test void dependenciesRequireBothExactSourcesAndTheCorrectDisplayUsingIdenticalSourceVersions() throws Exception {
        var catalog = metadata();
        for (UUID versionId : List.of(GUN_VERSION, DISPLAY_VERSION)) {
            var original = catalog.versions().get(versionId);
            for (Set<UUID> deps : List.of(Set.<UUID>of(), Set.of(DEFAULT_VERSION), Set.of(ORDINARY_VERSION), Set.of(id(999)),
                    Set.of(DEFAULT_VERSION, DISPLAY_VERSION), Set.of(ORDINARY_VERSION, DISPLAY_VERSION), Set.of(DEFAULT_VERSION, ORDINARY_VERSION, GUN_VERSION)))
                reject(withDependencies(original, deps), catalog);
        }
        reject(withDependencies(catalog.versions().get(GUN_VERSION), Set.of(DEFAULT_VERSION, ORDINARY_VERSION)), catalog);
        reject(withDependencies(catalog.versions().get(DISPLAY_VERSION), Set.of(DEFAULT_VERSION, ORDINARY_VERSION, DISPLAY_VERSION)), catalog);
        Asset wrong = find("tacz:aa12_display");
        var withWrong = wrong.addTo(catalog, id(61), id(62), id(63), blob(wrong), DEFAULT_VERSION, ORDINARY_VERSION, null);
        reject(withDependencies(catalog.versions().get(GUN_VERSION), Set.of(DEFAULT_VERSION, ORDINARY_VERSION, id(62))), withWrong);
        var secondDefault = Source.TACZ_DEFAULT.catalog(id(41), id(42), id(43), packBlob(Source.TACZ_DEFAULT));
        var secondOrdinary = Source.TACZ_RESOURCES.catalog(id(51), id(52), id(53), packBlob(Source.TACZ_RESOURCES));
        var twoSources = merge(merge(catalog, secondDefault), secondOrdinary);
        for (Set<UUID> deps : List.of(Set.of(id(42), ORDINARY_VERSION), Set.of(DEFAULT_VERSION, id(52)), Set.of(id(42), id(52))))
            reject(catalog.versions().get(GUN_VERSION), replace(twoSources, withDependencies(catalog.versions().get(DISPLAY_VERSION), deps)));
        reject(withDependencies(catalog.versions().get(GUN_VERSION), Set.of(DEFAULT_VERSION, ORDINARY_VERSION, id(42), DISPLAY_VERSION)), twoSources);
        reject(withDependencies(catalog.versions().get(DISPLAY_VERSION), Set.of(DEFAULT_VERSION, ORDINARY_VERSION, id(52))), twoSources);
    }

    @Test void matchingMetadataCannotGrantSuccessAndCancellationAndSupplierFailuresPropagate() throws Exception {
        var catalog = metadata();
        for (UUID version : List.of(GUN_VERSION, DISPLAY_VERSION)) {
            var content = new Probe(catalog); var expected = new IOException("no authenticated source bytes"); content.failure = expected;
            assertSame(expected, assertThrows(IOException.class, () -> validator().validate(catalog.versions().get(version), content)));
            assertEquals(List.of(catalog.versions().get(version).sourceEntry()), content.copies);
            var cancelled = new Probe(catalog); cancelled.cancel = true;
            assertThrows(ResourceValidationCancelledException.class, () -> validator().validate(catalog.versions().get(version), cancelled));
            assertTrue(cancelled.copies.isEmpty());
            var failed = new Probe(catalog); failed.supplierFailure = new IllegalArgumentException("supplier");
            assertSame(failed.supplierFailure, assertThrows(IllegalArgumentException.class, () -> validator().validate(catalog.versions().get(version), failed)));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "editor.builtin.taczJar", matches = ".+")
    void originalInventoryAndRealStoreSourcesValidateSaveAndReopenWithoutOriginalFiles() throws Exception {
        Path original = temporary.resolve("original.jar"); Files.copy(Path.of(System.getProperty("editor.builtin.taczJar")), original);
        Path defaults = temporary.resolve("defaults.edpack"), ordinary = temporary.resolve("ordinary.edpack");
        BuiltinWeaponPacks.capture(Source.TACZ_DEFAULT, original, defaults, () -> false);
        BuiltinWeaponPacks.capture(Source.TACZ_RESOURCES, original, ordinary, () -> false);
        Path project = temporary.resolve("project"); ResourceCatalog catalog;
        try (var store = newStore(project)) {
            BlobRef first, second;
            try (InputStream input = Files.newInputStream(defaults)) { first = store.stage(BlobRef.Kind.ASSET, input); }
            try (InputStream input = Files.newInputStream(ordinary)) { second = store.stage(BlobRef.Kind.ASSET, input); }
            catalog = merge(Source.TACZ_DEFAULT.catalog(id(1), DEFAULT_VERSION, DEFAULT_BYTE, first), Source.TACZ_RESOURCES.catalog(id(4), ORDINARY_VERSION, id(6), second));
            try (var snapshot = PackSnapshots.open(defaults, () -> false)) {
                // All 94 hashes/lengths come from actual preserved member bytes, including comments.
                for (Asset asset : TaczBuiltinAssetFormat.assets()) {
                    byte[] raw; try (InputStream input = snapshot.openEntry(asset.path(), () -> false)) { raw = input.readAllBytes(); }
                    BlobRef source = store.stage(BlobRef.Kind.ASSET, new ByteArrayInputStream(raw));
                    assertEquals(blob(asset), source);
                    if (asset == DISPLAY) catalog = asset.addTo(catalog, id(11), DISPLAY_VERSION, id(13), source, DEFAULT_VERSION, ORDINARY_VERSION, null);
                }
                byte[] raw; try (InputStream input = snapshot.openEntry(GUN.path(), () -> false)) { raw = input.readAllBytes(); }
                var source = store.stage(BlobRef.Kind.ASSET, new ByteArrayInputStream(raw));
                catalog = GUN.addTo(catalog, id(21), GUN_VERSION, id(23), source, DEFAULT_VERSION, ORDINARY_VERSION, DISPLAY_VERSION);
                BlobRef changed = store.stage(BlobRef.Kind.ASSET, new ByteArrayInputStream((new String(raw, StandardCharsets.UTF_8) + "\n// changed comment\n").getBytes(StandardCharsets.UTF_8)));
                ResourceCatalog fixed = catalog;
                assertThrows(IllegalArgumentException.class, () -> GUN.addTo(fixed, id(71), id(72), id(73), changed, DEFAULT_VERSION, ORDINARY_VERSION, DISPLAY_VERSION));
                var alteredEntries = new HashMap<>(catalog.byteEntries()); var entry = alteredEntries.get(id(23));
                alteredEntries.put(entry.id(), entry(entry, entry.ownerModule(), entry.typeId(), entry.codecId(), 1, changed, Set.of(), Set.of()));
                reject(catalog.versions().get(GUN_VERSION), new ResourceCatalog(catalog.resources(), catalog.versions(), alteredEntries));
            }
            for (int mode = 0; mode < 3; mode++) {
                Set<Path> beforeFiles = privateMaterializations();
                var actual = new StoredContent(store, catalog, mode);
                try {
                    if (mode == 2) assertSame(actual.failure, assertThrows(IllegalArgumentException.class, () -> packValidator().validate(actual.catalog.versions().get(DEFAULT_VERSION), actual)));
                    else assertThrows(ResourceValidationCancelledException.class, () -> packValidator().validate(actual.catalog.versions().get(DEFAULT_VERSION), actual));
                    assertTrue(actual.injected);
                } finally { Thread.interrupted(); }
                assertEquals(beforeFiles, privateMaterializations(), "failed physical validation must release every private pack");
                assertTrue(packValidator().validate(catalog.versions().get(DEFAULT_VERSION), new StoredContent(store, catalog, -1)).isEmpty());
                assertEquals(beforeFiles, privateMaterializations(), "successful physical validation must release every private pack");
            }
            var access = new LocalProjectAccess(store); var registry = registry();
            try (var session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                var handle = session.resources().orElseThrow(); ResourceCatalog fixed = catalog;
                handle.edit(handle.view().token(), new TextContent.Literal("TaCZ fixed assets"), ignored -> fixed);
                var before = handle.view();
                assertThrows(ResourceValidationCancelledException.class, () -> access.prepareValidated(session.captureSave(Set.of(), true), () -> true));
                assertEquals(before, handle.view());
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                var job = session.dispatchSave().orElseThrow().job().orElseThrow(); var result = access.publish(job);
                assertEquals(SaveResult.Outcome.CONFIRMED, result.outcome()); assertTrue(session.completeSave(job, result));
            }
        }
        Files.delete(original); Files.delete(defaults); Files.delete(ordinary);
        try (var store = LocalProjectStore.open(project)) {
            var access = new LocalProjectAccess(store); var loaded = access.readValidated(registry(), () -> false);
            assertTrue(loaded.problems().stream().noneMatch(problem -> problem.severity() == Problem.Severity.ERROR), loaded.problems().toString());
            assertEquals(catalog, loaded.resources().orElseThrow());
            for (var entry : catalog.byteEntries().values()) store.copyContent(entry.content(), OutputStream.nullOutputStream());
        }
    }

    private static final class Probe implements ResourceContent {
        final ResourceCatalog catalog; final List<UUID> copies = new ArrayList<>();
        IOException failure = new IOException("unexpected byte copy"); boolean cancel; RuntimeException supplierFailure;
        Probe(ResourceCatalog catalog) { this.catalog = catalog; }
        public Map<UUID, EditorId> resourceTypes() { return types(catalog); }
        public Map<UUID, ResourceVersion> versions() { return catalog.versions(); }
        public Map<UUID, ProjectState.Entry> byteEntries() { return catalog.byteEntries(); }
        public void copyContent(UUID id, OutputStream output) throws IOException { copies.add(id); throw failure; }
        public void checkCancelled() throws ResourceValidationCancelledException {
            if (supplierFailure != null) throw supplierFailure;
            if (cancel) throw new ResourceValidationCancelledException();
        }
    }

    /** Authenticated real store bytes with failures only at public physical I/O boundaries. */
    private static final class StoredContent implements ResourceContent {
        final LocalProjectStore store; final ResourceCatalog catalog; final int mode;
        final IllegalArgumentException failure = new IllegalArgumentException("physical supplier failure");
        boolean copiedPack, injected;
        StoredContent(LocalProjectStore store, ResourceCatalog catalog, int mode) { this.store = store; this.catalog = catalog; this.mode = mode; }
        public Map<UUID, EditorId> resourceTypes() { return types(catalog); }
        public Map<UUID, ResourceVersion> versions() { return catalog.versions(); }
        public Map<UUID, ProjectState.Entry> byteEntries() { return catalog.byteEntries(); }
        public void copyContent(UUID entry, OutputStream output) throws IOException {
            checkCancelled(); store.copyContent(catalog.byteEntries().get(entry).content(), output);
            if (entry.equals(DEFAULT_BYTE)) {
                copiedPack = true;
                if (mode == 0 && !injected) { injected = true; Thread.currentThread().interrupt(); }
            }
        }
        public void checkCancelled() throws ResourceValidationCancelledException {
            if (mode == 2 && copiedPack && !injected) { injected = true; throw failure; }
            if (mode == 1 && !injected) for (var frame : Thread.currentThread().getStackTrace())
                if (frame.getClassName().equals("net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot") && frame.getMethodName().equals("openEntry")) {
                    injected = true; Thread.currentThread().interrupt(); break;
                }
            if (Thread.currentThread().isInterrupted()) throw new ResourceValidationCancelledException();
        }
    }

    private static void reject(ResourceVersion version, ResourceCatalog catalog) throws Exception {
        var probe = new Probe(catalog);
        assertTrue(validator().validate(version, probe).stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR));
        assertTrue(probe.copies.isEmpty());
    }
    private static Asset find(String id) { return TaczBuiltinAssetFormat.assets().stream().filter(asset -> asset.id().toString().equals(id)).findFirst().orElseThrow(); }
    private static Set<Path> privateMaterializations() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return new HashSet<>(paths.filter(path -> path.getFileName().toString().startsWith("editor-tacz-asset-")).toList());
        }
    }
    private static BlobRef blob(Asset asset) { return new BlobRef(BlobRef.Kind.ASSET, asset.sha256(), asset.size()); }
    private static BlobRef packBlob(Source source) { return new BlobRef(BlobRef.Kind.ASSET, source.snapshotSha256(), source.snapshotBytes()); }
    private static ResourceCatalog packs() { return merge(Source.TACZ_DEFAULT.catalog(id(1), DEFAULT_VERSION, DEFAULT_BYTE, packBlob(Source.TACZ_DEFAULT)), Source.TACZ_RESOURCES.catalog(id(4), ORDINARY_VERSION, id(6), packBlob(Source.TACZ_RESOURCES))); }
    private static ResourceCatalog metadata() {
        var display = DISPLAY.addTo(packs(), id(11), DISPLAY_VERSION, id(13), blob(DISPLAY), DEFAULT_VERSION, ORDINARY_VERSION, null);
        return GUN.addTo(display, id(21), GUN_VERSION, id(23), blob(GUN), DEFAULT_VERSION, ORDINARY_VERSION, DISPLAY_VERSION);
    }
    private static ResourceVersion withDependencies(ResourceVersion original, Set<UUID> dependencies) { return change(original, original.importer(), original.importerVersion(), original.settings(), original.sourceEntry(), dependencies, original.outputEntries()); }
    private static ResourceVersion change(ResourceVersion old, EditorId importer, String format, Map<String, String> settings, UUID source, Set<UUID> deps, Set<UUID> outputs) { return new ResourceVersion(old.id(), old.resourceId(), importer, format, settings, source, deps, outputs); }
    private static ProjectState.Entry entry(ProjectState.Entry old, String owner, String type, String codec, int schema, BlobRef blob, Set<UUID> documents, Set<UUID> assets) { return new ProjectState.Entry(old.id(), owner, type, codec, schema, old.displayName(), blob, documents, assets); }
    private static HashMap<UUID, EditorId> types(ResourceCatalog catalog) { var result = new HashMap<UUID, EditorId>(); catalog.resources().forEach((id, resource) -> result.put(id, resource.type())); return result; }
    private static ResourceCatalog replace(ResourceCatalog catalog, ResourceVersion version) { var versions = new HashMap<>(catalog.versions()); versions.put(version.id(), version); return new ResourceCatalog(catalog.resources(), versions, catalog.byteEntries()); }
    private static ResourceCatalog merge(ResourceCatalog first, ResourceCatalog second) {
        var resources = new HashMap<>(first.resources()); resources.putAll(second.resources());
        var versions = new HashMap<>(first.versions()); versions.putAll(second.versions());
        var entries = new HashMap<>(first.byteEntries()); entries.putAll(second.byteEntries()); return new ResourceCatalog(resources, versions, entries);
    }
    private static LocalProjectStore newStore(Path path) throws Exception {
        var store = LocalProjectStore.create(path);
        try { store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "TaCZ builtin asset", Map.of(), Map.of(), Map.of(), null)); return store; }
        catch (Throwable failure) { store.close(); throw failure; }
    }
    private static ExtensionRegistry registry() {
        var api = new ApiVersion(1, 0); var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.addModule(new ModuleSpec(EditorId.parse("editor:resources"), "1", api, Set.of(), Set.of()));
        BasicItemModules.registerVanilla(builder, "test", "1.20.1"); WeaponItemValues.registerTacz(builder, "test", "1");
        BuiltinWeaponPackFormat.registerTacz(builder); TaczBuiltinAssetFormat.register(builder); return builder.freeze();
    }
    private static ResourceContentValidator packValidator() { return registry().entries(ResourceContentValidators.KIND).get(BuiltinWeaponPackFormat.TACZ_IMPORTER); }
    private static ResourceContentValidator validator() { return registry().entries(ResourceContentValidators.KIND).get(WeaponItemValues.TACZ_IMPORTER); }
    private static UUID id(long number) { return new UUID(0x82530001L, number); }
}
