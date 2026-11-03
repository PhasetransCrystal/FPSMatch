package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.resources;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
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
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Positive preparation evidence always uses the configured original LR JAR. */
class LrResourceImporterTest {
    @TempDir Path temporary;
    private static final UUID SEED = new UUID(0x82530001L, 1);
    private static final ResourceCatalog EMPTY = new ResourceCatalog(Map.of(), Map.of(), Map.of());
    private static final BuiltinWeaponPackFormat.Source PROFILE = BuiltinWeaponPackFormat.Source.LR_RESOURCES;

    @Test void registrationOwnsAnExplicitSingleBoundedContainerInput() {
        var registry = registry(); var importer = importer(registry);
        ResourceImporters.require(registry, WeaponItemValues.LR_IMPORTER, importer);
        assertEquals(List.of("container"), importer.inputs().stream().map(ResourceImporter.Input::id).toList());
        assertEquals(ResourceImporter.MAX_INPUT_BYTES, importer.inputs().get(0).maxBytes());
        assertTrue(importer.label() instanceof TextContent.Message);
    }

    @Test void invalidInputAndEarlyCancellationNeverOpenSource() {
        var registry = registry(); var importer = importer(registry);
        var opened = new AtomicBoolean();
        var source = new ResourceImporter.Source() {
            public BlobRef content() { return new BlobRef(BlobRef.Kind.ASSET, PROFILE.containerSha256(), 10); }
            public InputStream open() { opened.set(true); throw new AssertionError("must not read source"); }
        };
        assertThrows(IOException.class, () -> importer.inspect(registry, Map.of(), EMPTY, SEED, () -> false));
        assertThrows(IOException.class, () -> importer.inspect(registry, Map.of("container", source, "extra", source), EMPTY, SEED, () -> false));
        assertThrows(ResourceValidationCancelledException.class,
                () -> importer.inspect(registry, Map.of("container", source), EMPTY, SEED, () -> true));
        assertFalse(opened.get()); assertEquals(0, EMPTY.resources().size());
        var wrong=new ResourceImporter.Source(){
            public BlobRef content(){return new BlobRef(BlobRef.Kind.ASSET,"0".repeat(64),1);}
            public InputStream open(){throw new AssertionError("unsupported source must not open");}
        };
        var rejection=assertThrows(ResourceImportException.class,()->importer.inspect(registry,Map.of("container",wrong),EMPTY,SEED,()->false));
        assertEquals(new TextContent.Message("editor.workbench.resource_lr_unsupported",List.of()),rejection.problems().get(0).message());
    }

    @Test void unsupportedBytesAndCancellationCloseTheInputWithoutPublishingACandidate() {
        var registry = registry(); var importer = importer(registry);
        for (boolean cancelDuringRead : List.of(false, true)) {
            var closed = new AtomicBoolean(); var cancelled = new AtomicBoolean();
            // Deliberately invalid rejection probe; never used as successful import evidence.
            var source = new ResourceImporter.Source() {
                public BlobRef content() { return new BlobRef(BlobRef.Kind.ASSET, PROFILE.containerSha256(), 1); }
                public InputStream open() {
                    return new ByteArrayInputStream(new byte[] { 7 }) {
                        @Override public synchronized int read(byte[] bytes, int off, int length) {
                            int result = super.read(bytes, off, length);
                            if (cancelDuringRead) cancelled.set(true);
                            return result;
                        }
                        @Override public void close() throws IOException { closed.set(true); super.close(); }
                    };
                }
            };
            if (cancelDuringRead) assertThrows(ResourceValidationCancelledException.class,
                    () -> importer.inspect(registry, Map.of("container", source), EMPTY, SEED, cancelled::get));
            else assertThrows(IOException.class,
                    () -> importer.inspect(registry, Map.of("container", source), EMPTY, SEED, cancelled::get));
            assertTrue(closed.get()); assertTrue(EMPTY.byteEntries().isEmpty());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "editor.builtin.lrJar", matches = ".+")
    void originalContainerProducesThreeExactSourcesPreservesBaselineAndOwnsLifetime() throws Exception {
        var registry = registry(); var importer = importer(registry);
        Path original = temporary.resolve("original.jar");
        Files.copy(Path.of(System.getProperty("editor.builtin.lrJar")), original);
        var input = fileSource(original);
        ResourceCatalog first;
        try (var prepared = importer.inspect(registry, Map.of("container", input), EMPTY, SEED, () -> false)) {
            first = prepared.candidate();
            assertEquals(3, first.resources().size()); assertEquals(3, first.versions().size()); assertEquals(3, first.byteEntries().size());
            assertEquals(first.byteEntries().keySet(), prepared.sources().keySet());
            assertEquals(9, allIds(first).size());
            assertEquals(3, prepared.summary().size());
            for(var resource:first.resources().values()) {
                String suffix=resource.type().equals(BuiltinWeaponPackFormat.TYPE)?"pack":resource.type().equals(LrBuiltinAssetFormat.Asset.MELEE.type())?"melee":"display";
                assertEquals(Optional.of(new TextContent.Message("editor.workbench.resource_lr_name_"+suffix,List.of())),importer.resourceLabel(first,resource.id()));
                var renamed=new HashMap<>(first.resources());
                renamed.put(resource.id(),new LogicalResource(resource.id(),resource.type(),"Custom name",resource.currentVersion()));
                assertTrue(importer.resourceLabel(new ResourceCatalog(renamed,first.versions(),first.byteEntries()),resource.id()).isEmpty());
            }
            authenticateSources(prepared);
            try (var repeated = importer.inspect(registry, Map.of("container", input), EMPTY, SEED, () -> false)) {
                assertEquals(first, repeated.candidate());
            }
            // Reusing a seed refuses every existing identity instead of replacing history.
            assertThrows(IOException.class, () -> importer.inspect(registry, Map.of("container", input), first, SEED, () -> false));
            try (var next = importer.inspect(registry, Map.of("container", input), first, new UUID(0x82530001L, 2), () -> false)) {
                assertEquals(6, next.candidate().resources().size()); assertEquals(6, next.candidate().versions().size());
                assertEquals(6, next.candidate().byteEntries().size());
                first.resources().forEach((id, value) -> assertEquals(value, next.candidate().resources().get(id)));
                first.versions().forEach((id, value) -> assertEquals(value, next.candidate().versions().get(id)));
                first.byteEntries().forEach((id, value) -> assertEquals(value, next.candidate().byteEntries().get(id)));
                var newEntries = new HashSet<>(next.candidate().byteEntries().keySet()); newEntries.removeAll(first.byteEntries().keySet());
                assertEquals(newEntries, next.sources().keySet()); authenticateSources(next);
            }
            Files.delete(original);
            authenticateSources(prepared); // No original path or open original stream remains necessary.
            var source = prepared.sources().values().iterator().next();
            InputStream outstanding = source.open();
            prepared.close();
            assertThrows(IOException.class, source::open);
            assertThrows(IOException.class, outstanding::read);
            prepared.close();
        }
    }

    private static void authenticateSources(ResourceImporter.Prepared prepared) throws Exception {
        for (var entry : prepared.sources().entrySet()) {
            var source = entry.getValue(); assertEquals(prepared.candidate().byteEntries().get(entry.getKey()).content(), source.content());
            byte[] bytes; try (var input = source.open()) { bytes = input.readNBytes((int) PROFILE.snapshotBytes() + 1); }
            assertEquals(source.content().size(), bytes.length);
            assertEquals(source.content().sha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        for (var asset : LrBuiltinAssetFormat.Asset.values()) {
            var version = prepared.candidate().versions().values().stream()
                    .filter(value -> value.settings().equals(asset.settings()) && prepared.sources().containsKey(value.sourceEntry())).findFirst().orElseThrow();
            assertEquals(asset.sha256(), prepared.sources().get(version.sourceEntry()).content().sha256());
            assertEquals(asset.size(), prepared.sources().get(version.sourceEntry()).content().size());
            assertEquals(asset == LrBuiltinAssetFormat.Asset.DISPLAY ? 1 : 2, version.dependencyVersions().size());
        }
    }
    private static Set<UUID> allIds(ResourceCatalog catalog) {
        var ids = new HashSet<>(catalog.resources().keySet()); ids.addAll(catalog.versions().keySet()); ids.addAll(catalog.byteEntries().keySet()); return ids;
    }
    private static ResourceImporter.Source fileSource(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        BlobRef content = new BlobRef(BlobRef.Kind.ASSET, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length);
        return new ResourceImporter.Source() {
            public BlobRef content() { return content; }
            public InputStream open() throws IOException { return Files.newInputStream(path); }
        };
    }
    private static ResourceImporter importer(ExtensionRegistry registry) {
        return registry.find(ResourceImporters.KIND, WeaponItemValues.LR_IMPORTER).orElseThrow();
    }
    private static ExtensionRegistry registry() {
        var api = new ApiVersion(1, 0); var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.addModule(new ModuleSpec(EditorId.parse("editor:resources"), "1", api, Set.of(), Set.of()));
        BasicItemModules.registerVanilla(builder, "test", "1.20.1");
        WeaponItemValues.registerTacz(builder, "test", "1"); WeaponItemValues.registerLrTactical(builder, "test", "1");
        BuiltinWeaponPackFormat.registerLrTactical(builder); LrBuiltinAssetFormat.register(builder);
        LrResourceImporter.register(builder); return builder.freeze();
    }
}
