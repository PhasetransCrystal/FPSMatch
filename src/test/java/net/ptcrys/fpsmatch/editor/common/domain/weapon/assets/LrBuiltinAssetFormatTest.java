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
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.LrBuiltinAssetFormat.Asset;
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

/** Only the property-gated original-byte chain is a positive asset authentication test. */
class LrBuiltinAssetFormatTest {
    @TempDir Path temporary;
    private static final UUID PACK = id(1), PACK_VERSION = id(2), PACK_BYTE = id(3);
    private static final UUID DISPLAY = id(11), DISPLAY_VERSION = id(12), DISPLAY_BYTE = id(13);
    private static final UUID MELEE = id(21), MELEE_VERSION = id(22), MELEE_BYTE = id(23);
    private static final TextContent EDIT = new TextContent.Literal("LR fixed asset profile");

    @Test void registrationAndStructuralBuilderLockTheExactProfileAndDependencies() {
        var registry = registry(); var producers = ResourceProducerCatalog.activate(registry);
        ResourceCatalog metadata = metadata();
        for (Asset asset : Asset.values()) {
            var version = version(metadata, asset);
            assertEquals(WeaponItemValues.LR_MODULE, registry.metadata().contributions().get(producers.requireContribution(version)).owner());
            assertEquals(WeaponItemValues.LR_MODULE, registry.metadata().contributions().get(producers.requireValidationContribution(version)).owner());
            assertEquals(asset.settings(), version.settings());
            assertEquals(asset == Asset.DISPLAY ? Set.of(PACK_VERSION) : Set.of(PACK_VERSION, DISPLAY_VERSION), version.dependencyVersions());
            assertTrue(version.outputEntries().isEmpty());
            var unknown = change(version, version.importer(), "2", version.settings(), version.sourceEntry(), version.dependencyVersions(), Set.of());
            assertThrows(SessionRejection.class, () -> producers.requireContribution(unknown));
            assertThrows(IllegalArgumentException.class, () -> asset.addTo(metadata, id(31), id(32), id(33), new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), asset.size()), PACK_VERSION, asset == Asset.MELEE ? DISPLAY_VERSION : null));
        }
        assertThrows(IllegalArgumentException.class, () -> Asset.MELEE.addTo(packMetadata(), MELEE, MELEE_VERSION, MELEE_BYTE, blob(Asset.MELEE), PACK_VERSION, null));
        assertThrows(IllegalArgumentException.class, () -> Asset.DISPLAY.addTo(packMetadata(), DISPLAY, DISPLAY_VERSION, DISPLAY_BYTE, blob(Asset.DISPLAY), PACK_VERSION, DISPLAY_VERSION));
        assertThrows(IllegalArgumentException.class, () -> Asset.DISPLAY.addTo(metadata, DISPLAY, DISPLAY_VERSION, DISPLAY_BYTE, blob(Asset.DISPLAY), PACK_VERSION, null));
        assertFalse(registry.entries(ResourceProducers.KIND).containsKey(WeaponItemValues.TACZ_IMPORTER));
    }

    @Test void descriptorAndSourceMetadataMutationsFailBeforeAnyContentCopy() throws Exception {
        ResourceCatalog catalog = metadata(); var validator = validator();
        for (Asset asset : Asset.values()) {
            var original = version(catalog, asset);
            List<ResourceVersion> bad = new ArrayList<>();
            bad.add(change(original, EditorId.parse("test:wrong_importer"), "1", original.settings(), original.sourceEntry(), original.dependencyVersions(), Set.of()));
            bad.add(change(original, original.importer(), "unknown", original.settings(), original.sourceEntry(), original.dependencyVersions(), Set.of()));
            bad.add(change(original, original.importer(), "1", original.settings(), null, original.dependencyVersions(), Set.of()));
            bad.add(change(original, original.importer(), "1", original.settings(), id(999), original.dependencyVersions(), Set.of()));
            bad.add(change(original, original.importer(), "1", original.settings(), original.sourceEntry(), original.dependencyVersions(), Set.of(PACK_BYTE)));
            for (String field : List.of("assetId", "profile", "indexPath")) {
                var settings = new HashMap<>(original.settings()); settings.put(field, "changed");
                bad.add(change(original, original.importer(), "1", settings, original.sourceEntry(), original.dependencyVersions(), Set.of()));
                settings = new HashMap<>(original.settings()); settings.remove(field);
                bad.add(change(original, original.importer(), "1", settings, original.sourceEntry(), original.dependencyVersions(), Set.of()));
            }
            var extra = new HashMap<>(original.settings()); extra.put("extra", "value");
            bad.add(change(original, original.importer(), "1", extra, original.sourceEntry(), original.dependencyVersions(), Set.of()));
            for (var value : bad) reject(validator, value, catalog);
            var resourceTypes = types(catalog); resourceTypes.put(original.resourceId(), EditorId.parse("test:wrong_type"));
            reject(validator, original, new Probe(catalog, resourceTypes, new IOException("unexpected copy")));
            var source = catalog.byteEntries().get(original.sourceEntry());
            List<ProjectState.Entry> sources = List.of(
                    entry(source, "test:wrong_owner", source.typeId(), source.codecId(), 1, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), "test:wrong_type", source.codecId(), 1, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), "test:wrong_codec", 1, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 2, source.content(), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, new BlobRef(BlobRef.Kind.DOCUMENT, asset.sha256(), asset.size()), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, new BlobRef(BlobRef.Kind.ASSET, asset.sha256(), asset.size() + 1), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), asset.size()), Set.of(), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, source.content(), Set.of(id(99)), Set.of()),
                    entry(source, source.ownerModule(), source.typeId(), source.codecId(), 1, source.content(), Set.of(), Set.of(id(99))));
            for (var value : sources) {
                var entries = new HashMap<>(catalog.byteEntries()); entries.put(value.id(), value);
                var probe = new Probe(catalog, types(catalog), new IOException("unexpected copy")); probe.entries = entries;
                reject(validator, original, probe);
            }
            var entries = new HashMap<>(catalog.byteEntries()); entries.remove(original.sourceEntry());
            reject(validator, original, new ResourceCatalog(catalog.resources(), catalog.versions(), entries));
        }
    }

    @Test void dependenciesRequireOneExactLrPackAndMeleeDisplayFromThatSamePack() throws Exception {
        var catalog = metadata(); var validator = validator();
        for (Asset asset : Asset.values()) {
            var original = version(catalog, asset);
            List<Set<UUID>> bad = new ArrayList<>(List.of(Set.of(), Set.of(id(999)), Set.of(DISPLAY_VERSION), Set.of(PACK_VERSION, MELEE_VERSION)));
            if (asset == Asset.MELEE) bad.add(Set.of(PACK_VERSION)); else bad.add(Set.of(PACK_VERSION, DISPLAY_VERSION));
            for (Set<UUID> deps : bad) reject(validator, change(original, original.importer(), "1", original.settings(), original.sourceEntry(), deps, Set.of()), catalog);
            var pack = catalog.versions().get(PACK_VERSION);
            var alteredPack = change(pack, BuiltinWeaponPackFormat.TACZ_IMPORTER, "1", Source.TACZ_RESOURCES.settings(), PACK_BYTE, Set.of(), Set.of());
            reject(validator, original, replace(catalog, alteredPack));
            var packSettings = new HashMap<>(pack.settings()); packSettings.put("source", "other");
            reject(validator, original, replace(catalog, change(pack, pack.importer(), "1", packSettings, PACK_BYTE, Set.of(), Set.of())));
        }
        // A second structurally matching physical pack does not permit a display locked to it.
        ResourceCatalog secondPack = Source.LR_RESOURCES.catalog(id(41), id(42), id(43), packBlob());
        ResourceCatalog twoPacks = merge(catalog, secondPack);
        var display = twoPacks.versions().get(DISPLAY_VERSION);
        var differentPackDisplay = change(display, display.importer(), "1", display.settings(), DISPLAY_BYTE, Set.of(id(42)), Set.of());
        reject(validator, version(twoPacks, Asset.MELEE), replace(twoPacks, differentPackDisplay));
        var extraPackMelee = version(twoPacks, Asset.MELEE);
        reject(validator, change(extraPackMelee, extraPackMelee.importer(), "1", extraPackMelee.settings(), MELEE_BYTE, Set.of(PACK_VERSION, id(42), DISPLAY_VERSION), Set.of()), twoPacks);
    }

    @Test void matchingMetadataStillRequestsRealCopyAndPropagatesFailureOrCancellation() throws Exception {
        var catalog = metadata(); var validator = validator();
        for (Asset asset : Asset.values()) {
            IOException failure = new IOException("No actual stored index bytes");
            var content = new Probe(catalog, types(catalog), failure);
            assertSame(failure, assertThrows(IOException.class, () -> validator.validate(version(catalog, asset), content)));
            assertEquals(List.of(version(catalog, asset).sourceEntry()), content.copies);
            var cancelled = new Probe(catalog, types(catalog), failure); cancelled.cancel = true;
            assertThrows(ResourceValidationCancelledException.class, () -> validator.validate(version(catalog, asset), cancelled));
            assertTrue(cancelled.copies.isEmpty());
            var supplierFailure = new IllegalStateException("cancel supplier failed");
            var broken = new Probe(catalog, types(catalog), failure); broken.supplierFailure = supplierFailure;
            assertSame(supplierFailure, assertThrows(IllegalStateException.class, () -> validator.validate(version(catalog, asset), broken)));
            assertTrue(broken.copies.isEmpty());
        }
    }

    @Test void absentStoredBytesAndPreparationCancellationPreserveResourceHistoryAndDisk() throws Exception {
        var registry = registry();
        try (var store = newStore(temporary.resolve("no-blobs"))) {
            var access = new LocalProjectAccess(store); var saved = store.load();
            try (var session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                var handle = session.resources().orElseThrow(); var metadata = metadata();
                handle.edit(handle.view().token(), EDIT, ignored -> metadata);
                handle.undo(handle.view().token()); assertTrue(handle.view().canRedo());
                handle.redo(handle.view().token()); var before = handle.view();
                var undo = handle.undoLabel(); var redo = handle.redoLabel();
                assertThrows(IOException.class, () -> access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                assertEquals(before, handle.view()); assertEquals(saved, store.load());
                assertThrows(ResourceValidationCancelledException.class, () -> access.prepareValidated(session.captureSave(Set.of(), true), () -> true));
                assertEquals(before, handle.view());
                var failure = new IllegalStateException("supplier failed");
                assertSame(failure, assertThrows(IllegalStateException.class, () -> access.prepareValidated(session.captureSave(Set.of(), true), () -> { throw failure; })));
                assertEquals(before, handle.view()); assertEquals(undo, handle.undoLabel()); assertEquals(redo, handle.redoLabel());
                assertEquals(saved, store.load()); assertEquals(0, session.saves().queued());
                handle.undo(handle.view().token()); assertTrue(handle.view().content().resources().isEmpty());
                handle.redo(handle.view().token()); assertEquals(metadata, handle.view().content());
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "editor.builtin.lrJar", matches = ".+")
    void originalLrPackAndRawCommentedSourcesValidateSaveAndReopenWithoutOriginalFiles() throws Exception {
        var registry = registry(); Path original = temporary.resolve("original-lr.jar"), snapshot = temporary.resolve("lr.edpack");
        Files.copy(Path.of(System.getProperty("editor.builtin.lrJar")), original);
        var captured = BuiltinWeaponPacks.capture(Source.LR_RESOURCES, original, snapshot, () -> false);
        assertEquals(Source.LR_RESOURCES.snapshotSha256(), captured.sha256()); assertEquals(Source.LR_RESOURCES.entryCount(), captured.entries().size());
        Path project = temporary.resolve("real-lr-project"); ResourceCatalog catalog;
        try (var store = newStore(project)) {
            BlobRef pack; try (InputStream input = Files.newInputStream(snapshot)) { pack = store.stage(BlobRef.Kind.ASSET, input); }
            catalog = Source.LR_RESOURCES.catalog(PACK, PACK_VERSION, PACK_BYTE, pack);
            try (var physical = PackSnapshots.open(snapshot, () -> false)) {
                for (Asset asset : List.of(Asset.DISPLAY, Asset.MELEE)) {
                    byte[] raw; try (InputStream input = physical.openEntry(asset.path(), () -> false)) { raw = input.readAllBytes(); }
                    assertEquals(asset.size(), raw.length);
                    BlobRef source = store.stage(BlobRef.Kind.ASSET, new ByteArrayInputStream(raw)); assertEquals(blob(asset), source);
                    catalog = asset.addTo(catalog, resource(asset), versionId(asset), byteId(asset), source, PACK_VERSION, asset == Asset.MELEE ? DISPLAY_VERSION : null);
                    // Even a comment-only addition changes the approved original source profile.
                    byte[] commentOnly = (new String(raw, StandardCharsets.UTF_8) + "\n// changed comment only\n").getBytes(StandardCharsets.UTF_8);
                    BlobRef changed = store.stage(BlobRef.Kind.ASSET, new ByteArrayInputStream(commentOnly));
                    ResourceCatalog existing = catalog;
                    assertThrows(IllegalArgumentException.class, () -> asset.addTo(existing, id(71), id(72), id(73), changed, PACK_VERSION, asset == Asset.MELEE ? DISPLAY_VERSION : null));
                    var altered = new HashMap<>(catalog.byteEntries());
                    ProjectState.Entry entry = altered.get(byteId(asset));
                    altered.put(entry.id(), entry(entry, entry.ownerModule(), entry.typeId(), entry.codecId(), 1, changed, Set.of(), Set.of()));
                    reject(validator(), version(catalog, asset), new ResourceCatalog(catalog.resources(), catalog.versions(), altered));
                }
            }
            var access = new LocalProjectAccess(store);
            // These contexts copy authenticated real store bytes; they only inject cancellation/failure.
            for (int mode = 0; mode < 3; mode++) {
                var actual = new StoredContent(store, catalog, mode);
                try {
                    if (mode == 2) assertSame(actual.failure, assertThrows(IllegalArgumentException.class, () -> validator().validate(version(actual.catalog, Asset.MELEE), actual)));
                    else assertThrows(ResourceValidationCancelledException.class, () -> validator().validate(version(actual.catalog, Asset.MELEE), actual));
                    assertTrue(actual.injected, "the failure must occur inside physical pack handling");
                } finally { Thread.interrupted(); }
                var restored = validator().validate(version(catalog, Asset.MELEE), new StoredContent(store, catalog, -1));
                assertTrue(restored.isEmpty(), "subsequent real authentication must succeed after cleanup: " + restored);
            }
            try (var session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                var handle = session.resources().orElseThrow(); ResourceCatalog fixed = catalog;
                handle.edit(handle.view().token(), EDIT, ignored -> fixed); var before = handle.view();
                assertThrows(ResourceValidationCancelledException.class, () -> access.prepareValidated(session.captureSave(Set.of(), true), () -> true));
                assertEquals(before, handle.view());
                var failure = new IllegalStateException("real preparation supplier failure");
                assertSame(failure, assertThrows(IllegalStateException.class, () -> access.prepareValidated(session.captureSave(Set.of(), true), () -> { throw failure; })));
                assertEquals(before, handle.view());
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                var job = session.dispatchSave().orElseThrow().job().orElseThrow(); var result = access.publish(job);
                assertEquals(SaveResult.Outcome.CONFIRMED, result.outcome()); assertTrue(session.completeSave(job, result));
                assertEquals(catalog, handle.view().content());
            }
        }
        Files.delete(original); Files.delete(snapshot); // Only test-owned copies; configured originals remain untouched.
        try (var store = LocalProjectStore.open(project)) {
            var access = new LocalProjectAccess(store); var loaded = access.readValidated(registry, () -> false);
            assertTrue(loaded.problems().stream().noneMatch(problem -> problem.severity() == Problem.Severity.ERROR), loaded.problems().toString());
            assertEquals(catalog, loaded.resources().orElseThrow());
            try (var session = ProjectSession.open(access, loaded, registry, 8)) {
                assertFalse(session.protectedMode()); assertEquals(catalog, session.editorResources().catalog().orElseThrow());
            }
            for (var entry : catalog.byteEntries().values()) store.copyContent(entry.content(), OutputStream.nullOutputStream());
        }
    }

    private static void reject(ResourceContentValidator validator, ResourceVersion version, ResourceCatalog catalog) throws Exception {
        reject(validator, version, new Probe(catalog, types(catalog), new IOException("unexpected byte copy")));
    }
    private static void reject(ResourceContentValidator validator, ResourceVersion version, Probe probe) throws Exception {
        assertTrue(validator.validate(version, probe).stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR));
        assertTrue(probe.copies.isEmpty(), "invalid metadata must be rejected before content or scripts are read");
    }
    /** Rejection/copy probe only; never provides real bytes or pretends validation succeeded. */
    private static final class Probe implements ResourceContent {
        final ResourceCatalog catalog; final Map<UUID, EditorId> types; final IOException failure;
        Map<UUID, ProjectState.Entry> entries;
        final List<UUID> copies = new ArrayList<>(); boolean cancel; RuntimeException supplierFailure;
        Probe(ResourceCatalog catalog, Map<UUID, EditorId> types, IOException failure) { this.catalog = catalog; this.types = types; this.failure = failure; this.entries = catalog.byteEntries(); }
        public Map<UUID, EditorId> resourceTypes() { return types; }
        public Map<UUID, ResourceVersion> versions() { return catalog.versions(); }
        public Map<UUID, ProjectState.Entry> byteEntries() { return entries; }
        public void copyContent(UUID entry, OutputStream target) throws IOException { copies.add(entry); throw failure; }
        public void checkCancelled() throws ResourceValidationCancelledException {
            if (supplierFailure != null) throw supplierFailure;
            if (cancel) throw new ResourceValidationCancelledException();
        }
    }
    /** Real authenticated storage reads, with one injected failure at a public pack I/O boundary. */
    private static final class StoredContent implements ResourceContent {
        final LocalProjectStore store; final ResourceCatalog catalog; final int mode;
        final IllegalArgumentException failure = new IllegalArgumentException("physical open supplier failed");
        boolean copiedPack, injected;
        StoredContent(LocalProjectStore store, ResourceCatalog catalog, int mode) { this.store = store; this.catalog = catalog; this.mode = mode; }
        public Map<UUID, EditorId> resourceTypes() { return types(catalog); }
        public Map<UUID, ResourceVersion> versions() { return catalog.versions(); }
        public Map<UUID, ProjectState.Entry> byteEntries() { return catalog.byteEntries(); }
        public void copyContent(UUID entry, OutputStream output) throws IOException {
            checkCancelled(); store.copyContent(catalog.byteEntries().get(entry).content(), output);
            if (entry.equals(PACK_BYTE)) {
                copiedPack = true;
                if (mode == 0 && !injected) { injected = true; Thread.currentThread().interrupt(); }
            }
        }
        public void checkCancelled() throws ResourceValidationCancelledException {
            if (mode == 2 && copiedPack && !injected) { injected = true; throw failure; }
            if (mode == 1 && !injected) for (StackTraceElement frame : Thread.currentThread().getStackTrace())
                if (frame.getClassName().equals("net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot") && frame.getMethodName().equals("openEntry")) {
                    injected = true; Thread.currentThread().interrupt(); break;
                }
            if (Thread.currentThread().isInterrupted()) throw new ResourceValidationCancelledException();
        }
    }
    private static ResourceCatalog packMetadata() { return Source.LR_RESOURCES.catalog(PACK, PACK_VERSION, PACK_BYTE, packBlob()); }
    private static ResourceCatalog metadata() {
        var display = Asset.DISPLAY.addTo(packMetadata(), DISPLAY, DISPLAY_VERSION, DISPLAY_BYTE, blob(Asset.DISPLAY), PACK_VERSION, null);
        return Asset.MELEE.addTo(display, MELEE, MELEE_VERSION, MELEE_BYTE, blob(Asset.MELEE), PACK_VERSION, DISPLAY_VERSION);
    }
    private static BlobRef packBlob() { return new BlobRef(BlobRef.Kind.ASSET, Source.LR_RESOURCES.snapshotSha256(), Source.LR_RESOURCES.snapshotBytes()); }
    private static BlobRef blob(Asset asset) { return new BlobRef(BlobRef.Kind.ASSET, asset.sha256(), asset.size()); }
    private static UUID resource(Asset asset) { return asset == Asset.DISPLAY ? DISPLAY : MELEE; }
    private static UUID versionId(Asset asset) { return asset == Asset.DISPLAY ? DISPLAY_VERSION : MELEE_VERSION; }
    private static UUID byteId(Asset asset) { return asset == Asset.DISPLAY ? DISPLAY_BYTE : MELEE_BYTE; }
    private static ResourceVersion version(ResourceCatalog catalog, Asset asset) { return catalog.versions().get(versionId(asset)); }
    private static ResourceVersion change(ResourceVersion old, EditorId importer, String format, Map<String, String> settings, UUID source, Set<UUID> deps, Set<UUID> outputs) {
        return new ResourceVersion(old.id(), old.resourceId(), importer, format, settings, source, deps, outputs);
    }
    private static ProjectState.Entry entry(ProjectState.Entry old, String owner, String type, String codec, int schema, BlobRef blob, Set<UUID> documents, Set<UUID> assets) {
        return new ProjectState.Entry(old.id(), owner, type, codec, schema, old.displayName(), blob, documents, assets);
    }
    private static HashMap<UUID, EditorId> types(ResourceCatalog catalog) {
        var result = new HashMap<UUID, EditorId>(); catalog.resources().forEach((id, resource) -> result.put(id, resource.type())); return result;
    }
    private static ResourceCatalog replace(ResourceCatalog catalog, ResourceVersion version) {
        var versions = new HashMap<>(catalog.versions()); versions.put(version.id(), version);
        return new ResourceCatalog(catalog.resources(), versions, catalog.byteEntries());
    }
    private static ResourceCatalog merge(ResourceCatalog first, ResourceCatalog second) {
        var resources = new HashMap<>(first.resources()); resources.putAll(second.resources());
        var versions = new HashMap<>(first.versions()); versions.putAll(second.versions());
        var entries = new HashMap<>(first.byteEntries()); entries.putAll(second.byteEntries()); return new ResourceCatalog(resources, versions, entries);
    }
    private static LocalProjectStore newStore(Path path) throws Exception {
        var store = LocalProjectStore.create(path);
        try { store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "LR builtin asset", Map.of(), Map.of(), Map.of(), null)); return store; }
        catch (Throwable failure) { store.close(); throw failure; }
    }
    private static ExtensionRegistry registry() {
        var api = new ApiVersion(1, 0); var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.addModule(new ModuleSpec(EditorId.parse("editor:resources"), "1", api, Set.of(), Set.of()));
        BasicItemModules.registerVanilla(builder, "test", "1.20.1");
        WeaponItemValues.registerTacz(builder, "test", "1"); WeaponItemValues.registerLrTactical(builder, "test", "1");
        BuiltinWeaponPackFormat.registerLrTactical(builder); LrBuiltinAssetFormat.register(builder); return builder.freeze();
    }
    private static ResourceContentValidator validator() { return registry().entries(ResourceContentValidators.KIND).get(WeaponItemValues.LR_IMPORTER); }
    private static UUID id(long number) { return new UUID(0x82520001L, number); }
}
