package net.ptcrys.fpsmatch.editor.common.domain.weapon.assets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.common.domain.item.BasicItemModules;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.BuiltinWeaponPackFormat.Source;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.resources.BuiltinWeaponPacks;
import net.ptcrys.fpsmatch.editor.core.packio.*;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.session.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class BuiltinWeaponPackFormatTest {
    @TempDir Path temporary;
    private static final UUID RESOURCE = uuid(1), VERSION = uuid(2), BYTE = uuid(3);
    private static final TextContent LABEL = new TextContent.Literal("Capture builtin source pack");

    @Test void registrationOffersOnlyExactPackFormatsAndNeverSingleWeaponProducers() {
        var registry = registry(); var producers = ResourceProducerCatalog.activate(registry);
        assertEquals(Set.of(BuiltinWeaponPackFormat.TACZ_IMPORTER, BuiltinWeaponPackFormat.LR_IMPORTER), registry.entries(ResourceProducers.KIND).keySet());
        assertEquals(registry.entries(ResourceProducers.KIND).keySet(), registry.entries(ResourceContentValidators.KIND).keySet());
        for (Source source : Source.values()) {
            var producerKey = producers.requireContribution(version(source));
            assertEquals(source.owner(), registry.metadata().contributions().get(producerKey).owner());
            var validatorKey = producers.requireValidationContribution(version(source));
            assertEquals(source.owner(), registry.metadata().contributions().get(validatorKey).owner());
            assertEquals(Set.of("1"), registry.entries(ResourceProducers.KIND).get(source.importer()).readableVersions());
            ResourceVersion unknown = descriptor(source.importer(), "2", source.settings(), BYTE, Set.of(), Set.of());
            assertThrows(SessionRejection.class, () -> producers.requireContribution(unknown));
        }
        for (EditorId importer : List.of(WeaponItemValues.TACZ_IMPORTER, WeaponItemValues.LR_IMPORTER)) {
            assertFalse(registry.entries(ResourceContentValidators.KIND).containsKey(importer));
            assertThrows(SessionRejection.class, () -> producers.requireContribution(descriptor(importer, "1", Map.of(), BYTE, Set.of(), Set.of())));
        }
    }

    @Test void malformedDescriptorsAndSourcesAreRejectedBeforeAnyByteCopy() throws Exception {
        var registry = registry();
        for (Source source : Source.values()) {
            var validator = registry.entries(ResourceContentValidators.KIND).get(source.importer());
            var good = version(source); var entry = entry(source, source.owner().toString(), BuiltinWeaponPackFormat.BYTES.toString(), BuiltinWeaponPackFormat.CODEC.toString(), 1, blob(source), Set.of(), Set.of());
            List<ResourceVersion> badVersions = new ArrayList<>();
            badVersions.add(descriptor(source.importer(), "1", Map.of(), BYTE, Set.of(), Set.of()));
            for (String field : List.of("source", "containerSha256", "rootPrefix")) {
                var settings = new HashMap<>(source.settings()); settings.put(field, "wrong");
                badVersions.add(descriptor(source.importer(), "1", settings, BYTE, Set.of(), Set.of()));
                settings = new HashMap<>(source.settings()); settings.remove(field);
                badVersions.add(descriptor(source.importer(), "1", settings, BYTE, Set.of(), Set.of()));
            }
            var extra = new HashMap<>(source.settings()); extra.put("unexpected", "value");
            badVersions.add(descriptor(source.importer(), "1", extra, BYTE, Set.of(), Set.of()));
            badVersions.add(descriptor(EditorId.parse("test:other_importer"), "1", source.settings(), BYTE, Set.of(), Set.of()));
            badVersions.add(descriptor(source.importer(), "unknown", source.settings(), BYTE, Set.of(), Set.of()));
            badVersions.add(descriptor(source.importer(), "1", source.settings(), null, Set.of(), Set.of()));
            badVersions.add(descriptor(source.importer(), "1", source.settings(), BYTE, Set.of(uuid(8)), Set.of()));
            badVersions.add(descriptor(source.importer(), "1", source.settings(), BYTE, Set.of(), Set.of(uuid(8))));
            for (var bad : badVersions) rejectBeforeCopy(validator, bad, BuiltinWeaponPackFormat.TYPE, Map.of(BYTE, entry));
            rejectBeforeCopy(validator, good, EditorId.parse("test:other_type"), Map.of(BYTE, entry));
            rejectBeforeCopy(validator, good, null, Map.of(BYTE, entry));
            rejectBeforeCopy(validator, good, BuiltinWeaponPackFormat.TYPE, Map.of());
            List<ProjectState.Entry> badEntries = List.of(
                    entry(source, "test:wrong_owner", entry.typeId(), entry.codecId(), 1, blob(source), Set.of(), Set.of()),
                    entry(source, entry.ownerModule(), "test:wrong_type", entry.codecId(), 1, blob(source), Set.of(), Set.of()),
                    entry(source, entry.ownerModule(), entry.typeId(), "test:wrong_codec", 1, blob(source), Set.of(), Set.of()),
                    entry(source, entry.ownerModule(), entry.typeId(), entry.codecId(), 2, blob(source), Set.of(), Set.of()),
                    entry(source, entry.ownerModule(), entry.typeId(), entry.codecId(), 1, new BlobRef(BlobRef.Kind.DOCUMENT, source.snapshotSha256(), source.snapshotBytes()), Set.of(), Set.of()),
                    entry(source, entry.ownerModule(), entry.typeId(), entry.codecId(), 1, new BlobRef(BlobRef.Kind.ASSET, source.snapshotSha256(), source.snapshotBytes() - 1), Set.of(), Set.of()),
                    entry(source, entry.ownerModule(), entry.typeId(), entry.codecId(), 1, new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), source.snapshotBytes()), Set.of(), Set.of()),
                    entry(source, entry.ownerModule(), entry.typeId(), entry.codecId(), 1, blob(source), Set.of(uuid(8)), Set.of()),
                    entry(source, entry.ownerModule(), entry.typeId(), entry.codecId(), 1, blob(source), Set.of(), Set.of(uuid(8))));
            for (var bad : badEntries) rejectBeforeCopy(validator, good, BuiltinWeaponPackFormat.TYPE, Map.of(BYTE, bad));
            assertThrows(IllegalArgumentException.class, () -> source.catalog(RESOURCE, VERSION, BYTE, new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), source.snapshotBytes())));
        }
    }

    @Test void metadataCannotAuthenticateContentAndCopyFailuresOrCancellationPropagate() throws Exception {
        var registry = registry();
        for (Source source : Source.values()) {
            var validator = registry.entries(ResourceContentValidators.KIND).get(source.importer());
            var entries = source.catalog(RESOURCE, VERSION, BYTE, blob(source)).byteEntries();
            IOException absent = new IOException("No actual source blob");
            var content = new ProbeContent(version(source), BuiltinWeaponPackFormat.TYPE, entries, absent);
            assertSame(absent, assertThrows(IOException.class, () -> validator.validate(version(source), content)));
            assertEquals(1, content.copies, "matching metadata must still attempt real content authentication");
            var cancelled = new ProbeContent(version(source), BuiltinWeaponPackFormat.TYPE, entries, absent);
            cancelled.cancel = true;
            assertThrows(ResourceValidationCancelledException.class, () -> validator.validate(version(source), cancelled));
            assertEquals(0, cancelled.copies);
        }
    }

    @Test void captureRejectsWrongContainerCancellationAndSupplierFailureWithoutTouchingTargets() throws Exception {
        Path wrong = temporary.resolve("other.jar"); Files.writeString(wrong, "not an authenticated original mod container");
        for (Source source : Source.values()) {
            Path target = temporary.resolve(source.key() + ".edpack");
            assertThrows(IOException.class, () -> BuiltinWeaponPacks.capture(source, wrong, target, () -> false));
            assertFalse(Files.exists(target));
            assertThrows(ResourceValidationCancelledException.class, () -> BuiltinWeaponPacks.capture(source, wrong, target, () -> true));
            assertFalse(Files.exists(target));
            RuntimeException failure = new IllegalStateException("cancellation supplier failed");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> BuiltinWeaponPacks.capture(source, wrong, target, () -> { throw failure; })));
            assertFalse(Files.exists(target));
            AtomicInteger checks = new AtomicInteger();
            assertThrows(ResourceValidationCancelledException.class, () -> BuiltinWeaponPacks.capture(source, wrong, target, () -> checks.incrementAndGet() > 1));
            assertFalse(Files.exists(target));
            Files.writeString(target, "existing target");
            assertThrows(IOException.class, () -> BuiltinWeaponPacks.capture(source, wrong, target, () -> false));
            assertEquals("existing target", Files.readString(target));
        }
    }

    @Test void exactLookingMetadataWithNoStoredBlobCannotBePreparedOrPublished() throws Exception {
        var registry = registry();
        for (Source source : Source.values()) try (var store = LocalProjectStore.create(temporary.resolve("metadata-only-" + source.key()))) {
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "Metadata is not content", Map.of(), Map.of(), Map.of(), null));
            var saved = store.load(); var access = new LocalProjectAccess(store);
            try (var session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                var handle = session.resources().orElseThrow();
                var metadata = source.catalog(RESOURCE, VERSION, BYTE, blob(source));
                handle.edit(handle.view().token(), LABEL, ignored -> metadata);
                var before = handle.view();
                assertThrows(IOException.class, () -> access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                assertEquals(before, handle.view()); assertEquals(saved, store.load());
                assertEquals(0, session.saves().queued());
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "editor.builtin.taczJar", matches = ".+")
    void exactOriginalContainersCaptureAuthenticateSaveAndReopenUsingOnlyProjectBytes() throws Exception {
        String lrProperty = System.getProperty("editor.builtin.lrJar");
        assertNotNull(lrProperty, "real-chain execution requires both exact original containers");
        assertFalse(lrProperty.isBlank());
        Path tacz = temporary.resolve("original-tacz.jar"), lr = temporary.resolve("original-lr.jar");
        Files.copy(Path.of(System.getProperty("editor.builtin.taczJar")), tacz);
        Files.copy(Path.of(lrProperty), lr);
        var registry = registry(); Path project = temporary.resolve("project");
        ResourceCatalog catalog = new ResourceCatalog(Map.of(), Map.of(), Map.of());
        var captures = new ArrayList<Path>();
        try (var store = LocalProjectStore.create(project)) {
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "Exact builtin packs", Map.of(), Map.of(), Map.of(), null));
            for (Source source : Source.values()) {
                Path original = source == Source.LR_RESOURCES ? lr : tacz;
                Path snapshot = temporary.resolve("real-" + source.key() + ".edpack"); captures.add(snapshot);
                PackIndex captured = BuiltinWeaponPacks.capture(source, original, snapshot, () -> false);
                assertEquals(source.snapshotBytes(), Files.size(snapshot));
                assertEquals(source.snapshotSha256(), captured.sha256()); assertEquals(source.entryCount(), captured.entries().size());
                try (var opened = PackSnapshots.open(snapshot, () -> false)) {
                    assertEquals(captured, opened.index());
                    for (var physical : opened.index().entries()) {
                        assertTrue(source.selects(physical.path()), physical.path());
                        try (InputStream input = opened.openEntry(physical.path(), () -> false)) {
                            assertEquals(physical.size(), input.transferTo(OutputStream.nullOutputStream()));
                        }
                    }
                }
                BlobRef staged; try (InputStream input = Files.newInputStream(snapshot)) { staged = store.stage(BlobRef.Kind.ASSET, input); }
                assertEquals(blob(source), staged);
                int offset = source.ordinal() * 10;
                catalog = merge(catalog, source.catalog(uuid(100 + offset), uuid(101 + offset), uuid(102 + offset), staged));
                // An authenticated original still cannot replace a caller-owned target.
                assertThrows(IOException.class, () -> BuiltinWeaponPacks.capture(source, original, snapshot, () -> false));
                assertEquals(source.snapshotBytes(), Files.size(snapshot));
                try (var preserved = PackSnapshots.open(snapshot, () -> false)) { assertEquals(captured, preserved.index()); }
            }
            var access = new LocalProjectAccess(store);
            try (var session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                assertFalse(session.protectedMode()); var handle = session.resources().orElseThrow();
                ResourceCatalog selected = catalog;
                handle.edit(handle.view().token(), LABEL, ignored -> selected);
                assertEquals(catalog, handle.view().content());
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                var job = session.dispatchSave().orElseThrow().job().orElseThrow(); var saved = access.publish(job);
                assertEquals(SaveResult.Outcome.CONFIRMED, saved.outcome()); assertTrue(session.completeSave(job, saved));
            }
        }
        // Only test-owned copies are removed; property paths remain untouched.
        Files.delete(tacz); Files.delete(lr); for (Path capture : captures) Files.delete(capture);
        try (var store = LocalProjectStore.open(project)) {
            var access = new LocalProjectAccess(store); var loaded = access.readValidated(registry, () -> false);
            assertTrue(loaded.problems().stream().noneMatch(problem -> problem.severity() == Problem.Severity.ERROR));
            assertEquals(catalog, loaded.resources().orElseThrow());
            try (var session = ProjectSession.open(access, loaded, registry, 8)) {
                assertFalse(session.protectedMode()); assertEquals(catalog, session.editorResources().catalog().orElseThrow());
            }
            for (var entry : catalog.byteEntries().values()) store.copyContent(entry.content(), OutputStream.nullOutputStream());
        }
        var producers = ResourceProducerCatalog.activate(registry);
        for (EditorId single : List.of(WeaponItemValues.TACZ_IMPORTER, WeaponItemValues.LR_IMPORTER))
            assertThrows(SessionRejection.class, () -> producers.requireContribution(descriptor(single, "1", Map.of(), BYTE, Set.of(), Set.of())));
    }

    private static void rejectBeforeCopy(ResourceContentValidator validator, ResourceVersion version, EditorId type, Map<UUID, ProjectState.Entry> entries) throws Exception {
        var content = new ProbeContent(version, type, entries, new IOException("unexpected copy"));
        assertTrue(validator.validate(version, content).stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR));
        assertEquals(0, content.copies);
    }
    /** A call probe only: it never supplies authenticated bytes or a successful validation result. */
    private static final class ProbeContent implements ResourceContent {
        final ResourceVersion version; final EditorId type; final Map<UUID, ProjectState.Entry> entries; final IOException failure;
        int copies; boolean cancel;
        ProbeContent(ResourceVersion version, EditorId type, Map<UUID, ProjectState.Entry> entries, IOException failure) {
            this.version = version; this.type = type; this.entries = entries; this.failure = failure;
        }
        public Map<UUID, EditorId> resourceTypes() { return type == null ? Map.of() : Map.of(RESOURCE, type); }
        public Map<UUID, ResourceVersion> versions() { return Map.of(VERSION, version); }
        public Map<UUID, ProjectState.Entry> byteEntries() { return entries; }
        public void copyContent(UUID id, OutputStream target) throws IOException { copies++; assertEquals(BYTE, id); throw failure; }
        public void checkCancelled() throws ResourceValidationCancelledException { if (cancel) throw new ResourceValidationCancelledException(); }
    }
    private static BlobRef blob(Source source) { return new BlobRef(BlobRef.Kind.ASSET, source.snapshotSha256(), source.snapshotBytes()); }
    private static ResourceVersion version(Source source) { return descriptor(source.importer(), "1", source.settings(), BYTE, Set.of(), Set.of()); }
    private static ResourceVersion descriptor(EditorId importer, String format, Map<String, String> settings, UUID source, Set<UUID> deps, Set<UUID> outputs) {
        return new ResourceVersion(VERSION, RESOURCE, importer, format, settings, source, deps, outputs);
    }
    private static ProjectState.Entry entry(Source source, String owner, String type, String codec, int schema, BlobRef blob, Set<UUID> documents, Set<UUID> assets) {
        return new ProjectState.Entry(BYTE, owner, type, codec, schema, source.key(), blob, documents, assets);
    }
    private static ResourceCatalog merge(ResourceCatalog first, ResourceCatalog second) {
        var resources = new HashMap<>(first.resources()); resources.putAll(second.resources());
        var versions = new HashMap<>(first.versions()); versions.putAll(second.versions());
        var entries = new HashMap<>(first.byteEntries()); entries.putAll(second.byteEntries());
        return new ResourceCatalog(resources, versions, entries);
    }
    private static ExtensionRegistry registry() {
        var api = new ApiVersion(1, 0); var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.addModule(new ModuleSpec(EditorId.parse("editor:resources"), "1", api, Set.of(), Set.of()));
        BasicItemModules.registerVanilla(builder, "test", "1.20.1");
        WeaponItemValues.registerTacz(builder, "test", "1"); WeaponItemValues.registerLrTactical(builder, "test", "1");
        BuiltinWeaponPackFormat.registerTacz(builder); BuiltinWeaponPackFormat.registerLrTactical(builder);
        return builder.freeze();
    }
    private static UUID uuid(long number) { return new UUID(0x82520000L, number); }
}
