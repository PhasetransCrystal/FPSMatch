package net.ptcrys.fpsmatch.editor.common.domain.weapon.assets;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.*;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.core.packio.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact trusted builtin profile, not a parser for modified or arbitrary LR packs. */
public final class LrBuiltinAssetFormat {

    public static final EditorId KARAMBIT = EditorId.parse("lrtactical:karambit");
    public static final EditorId BYTES = EditorId.parse("fpsmatch:weapon_asset_bytes");
    public static final EditorId CODEC = EditorId.parse("fpsmatch:weapon_asset_source");
    public static final String PROFILE = "lr-0.4.3-karambit";
    private static final List<String> REQUIRED = List.of(
            "data/lrtactical/index/melee/karambit.json", "assets/lrtactical/display/melee/karambit.json",
            "assets/lrtactical/geo_models/melee/karambit_geo.json",
            "assets/lrtactical/animations/melee/karambit.animation.json",
            "assets/lrtactical/textures/melee/karambit_uv.png",
            "assets/lrtactical/textures/melee/slot/karambit.png",
            "assets/lrtactical/scripts/default_melee_state_machine.lua");

    private LrBuiltinAssetFormat() {}

    public enum Asset {

        MELEE(WeaponItemValues.LR_MELEE_ASSET, "data/lrtactical/index/melee/karambit.json",
                "95b9ea591e517a07ac9fb209048b25805137d4a07f29e4c03843e76cbffc186d", 1502),
        DISPLAY(WeaponItemValues.LR_DISPLAY_ASSET, "assets/lrtactical/display/melee/karambit.json",
                "69905a2f10676030f79f7c96e4d606855152c9226719d42733dba606b219eebf", 775);

        private final EditorId type;
        private final String path, sha256;
        private final long size;

        Asset(EditorId type, String path, String sha256, long size) {
            this.type = type;
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
        }

        public EditorId type() {
            return type;
        }

        public String path() {
            return path;
        }

        public String sha256() {
            return sha256;
        }

        public long size() {
            return size;
        }

        public Map<String, String> settings() {
            return Map.of("assetId", KARAMBIT.toString(), "profile", PROFILE, "indexPath", path);
        }

        /** Structural candidate only. Real content validation is mandatory before saving or use. */
        public ResourceCatalog addTo(ResourceCatalog existing, UUID resource, UUID version, UUID sourceEntry,
                                     BlobRef blob, UUID packVersion, UUID defaultDisplayVersion) {
            Objects.requireNonNull(existing);
            Objects.requireNonNull(packVersion);
            if (this == MELEE && defaultDisplayVersion == null || this == DISPLAY && defaultDisplayVersion != null)
                throw new IllegalArgumentException("default display dependency");
            if (!matchesBlob(blob)) throw new IllegalArgumentException("unexpected builtin asset source");
            if (existing.resources().containsKey(resource) || existing.versions().containsKey(version) || existing.byteEntries().containsKey(sourceEntry)) throw new IllegalArgumentException("existing asset identity");
            var resources = new HashMap<>(existing.resources());
            var versions = new HashMap<>(existing.versions());
            var entries = new HashMap<>(existing.byteEntries());
            resources.put(resource, new LogicalResource(resource, type, KARAMBIT.toString(), version));
            versions.put(version, new ResourceVersion(version, resource, WeaponItemValues.LR_IMPORTER, "1", settings(),
                    sourceEntry, this == DISPLAY ? Set.of(packVersion) : Set.of(packVersion, defaultDisplayVersion), Set.of()));
            entries.put(sourceEntry, new ProjectState.Entry(sourceEntry, WeaponItemValues.LR_MODULE.toString(),
                    BYTES.toString(), CODEC.toString(), 1, path, blob, Set.of(), Set.of()));
            var result = new ResourceCatalog(resources, versions, entries);
            ResourceStorageBridge.planAll(result);
            return result;
        }

        private boolean matchesBlob(BlobRef blob) {
            return blob.kind() == BlobRef.Kind.ASSET && blob.size() == size && blob.sha256().equals(sha256);
        }
    }

    public static void register(ExtensionRegistryBuilder builder) {
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        var producer = new ContributionKey(ResourceProducers.KIND.id(), WeaponItemValues.LR_IMPORTER);
        builder.add(ResourceProducers.KIND, new Contribution(producer, WeaponItemValues.LR_MODULE,
                Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(Set.of("1")));
        builder.add(ResourceContentValidators.KIND, new Contribution(
                new ContributionKey(ResourceContentValidators.KIND.id(), WeaponItemValues.LR_IMPORTER),
                WeaponItemValues.LR_MODULE, Contribution.Side.COMMON, Set.of(producer)), LrBuiltinAssetFormat::validate);
    }

    private static Asset shape(ResourceVersion version, ResourceContent content) {
        if (!version.importer().equals(WeaponItemValues.LR_IMPORTER) || !version.importerVersion().equals("1") || version.sourceEntry() == null || !version.outputEntries().isEmpty()) return null;
        for (Asset asset : Asset.values()) {
            if (!asset.type.equals(content.resourceTypes().get(version.resourceId())) || !asset.settings().equals(version.settings())) continue;
            var entry = content.byteEntries().get(version.sourceEntry());
            if (entry != null && entry.ownerModule().equals(WeaponItemValues.LR_MODULE.toString()) && entry.typeId().equals(BYTES.toString()) && entry.codecId().equals(CODEC.toString()) && entry.schemaVersion() == 1 && entry.documentDependencies().isEmpty() && entry.assetDependencies().isEmpty() && asset.matchesBlob(entry.content())) return asset;
        }
        return null;
    }

    private static List<Problem> validate(ResourceVersion version, ResourceContent content) throws IOException {
        content.checkCancelled();
        Asset asset = shape(version, content);
        if (asset == null) return invalid(version, "descriptor");
        ResourceVersion pack = null, display = null;
        for (UUID dependency : version.dependencyVersions()) {
            content.checkCancelled();
            var candidate = content.versions().get(dependency);
            if (candidate == null) return invalid(version, "dependency");
            if (BuiltinWeaponPackFormat.matchingSource(candidate, content) == BuiltinWeaponPackFormat.Source.LR_RESOURCES) {
                if (pack != null) return invalid(version, "duplicate source");
                pack = candidate;
            } else if (asset == Asset.MELEE && shape(candidate, content) == Asset.DISPLAY) {
                if (display != null) return invalid(version, "duplicate display");
                display = candidate;
            } else return invalid(version, "dependency type");
        }
        if (pack == null || asset == Asset.MELEE && (display == null || !display.dependencyVersions().equals(Set.of(pack.id()))) || asset == Asset.DISPLAY && version.dependencyVersions().size() != 1) return invalid(version, "closure");
        // Exact approved raw source, including the original comments, must really exist in storage.
        content.copyContent(version.sourceEntry(), OutputStream.nullOutputStream());
        if (display != null) content.copyContent(display.sourceEntry(), OutputStream.nullOutputStream());
        BooleanSupplier cancelled = () -> {
            try {
                content.checkCancelled();
                return false;
            } catch (ResourceValidationCancelledException failure) {
                throw new CancellationSignal(failure);
            } catch (RuntimeException | Error failure) {
                throw new SupplierSignal(failure);
            }
        };
        try (var physical = Physical.open(pack, content, cancelled)) {
            var snapshot = physical.snapshot;
            var paths = new HashMap<String, PackEntry>();
            for (var entry : snapshot.index().entries()) {
                content.checkCancelled();
                paths.put(entry.path(), entry);
            }
            for (String path : REQUIRED) {
                content.checkCancelled();
                if (!paths.containsKey(path)) return invalid(version, "missing builtin member");
                try (InputStream input = snapshot.openEntry(path, cancelled)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
            }
            for (Asset expected : Asset.values()) {
                var entry = paths.get(expected.path);
                if (entry.size() != expected.size || !entry.sha256().equals(expected.sha256)) return invalid(version, "source member");
            }
            var scripts = new TreeMap<String, String>();
            for (var entry : snapshot.index().entries()) {
                content.checkCancelled();
                if (!entry.path().startsWith("assets/lrtactical/scripts/") || !entry.path().endsWith(".lua")) continue;
                if (entry.size() > 1_048_576 || scripts.size() >= 256) return invalid(version, "script budget");
                String name = "lrtactical_" + entry.path().substring("assets/lrtactical/scripts/".length(), entry.path().length() - 4);
                byte[] bytes;
                try (InputStream input = snapshot.openEntry(entry.path(), cancelled)) {
                    bytes = input.readNBytes(1_048_577);
                }
                if (bytes.length != entry.size()) return invalid(version, "script bytes");
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (scripts.put(name, text) != null) return invalid(version, "script collision");
            }
            if (!scripts.keySet().equals(Set.of("lrtactical_c4_state_machine", "lrtactical_consumable_state_machine",
                    "lrtactical_default_grenade_state_machine", "lrtactical_default_melee_state_machine",
                    "lrtactical_flash_shield_state_machine")))
                return invalid(version, "script domain");
            PinnedScriptValidator.validate(scripts, constants(), cancelled, 2_000_000);
            content.checkCancelled();
            return List.of();
        } catch (CancellationSignal failure) {
            transferSuppressed(failure, failure.cancellation);
            throw failure.cancellation;
        } catch (SupplierSignal failure) {
            transferSuppressed(failure, failure.original);
            if (failure.original instanceof RuntimeException original) throw original;
            throw (Error) failure.original;
        } catch (PackSnapshotException failure) {
            if (failure.code() == PackSnapshotException.Code.CANCELLED) {
                var cancellation = new ResourceValidationCancelledException();
                cancellation.initCause(failure);
                throw cancellation;
            }
            throw failure;
        } catch (ScriptValidationException failure) {
            if (failure.reason() == ScriptValidationException.Reason.CANCELLED) throw new ResourceValidationCancelledException();
            return invalid(version, "script " + failure.reason().name());
        }
    }

    private static void transferSuppressed(Throwable wrapper, Throwable original) {
        for (Throwable failure : wrapper.getSuppressed()) if (failure != original) original.addSuppressed(failure);
    }

    private static ScriptConstants constants() {
        var text = new TreeMap<String, String>();
        for (String name : List.of("bayonet_muzzle", "bayonet_push", "bayonet_stock", "cancel_reload", "draw", "fire_select",
                "idle", "inspect", "put_away", "reload", "run", "shoot", "walk"))
            text.put("INPUT_" + name.toUpperCase(Locale.ROOT), name);
        text.put("INPUT_BOLT", "blot"); // Exact TACZ 1.1.7-hotfix constant, including its original spelling.
        return new ScriptConstants(text, Map.ofEntries(Map.entry("AUTO", 0), Map.entry("SEMI", 1), Map.entry("BURST", 2),
                Map.entry("UNKNOWN", 3), Map.entry("PLAY_ONCE_HOLD", 0), Map.entry("PLAY_ONCE_STOP", 1), Map.entry("LOOP", 2),
                Map.entry("NOT_RELOADING", 0), Map.entry("EMPTY_RELOAD_FEEDING", 1), Map.entry("EMPTY_RELOAD_FINISHING", 2),
                Map.entry("TACTICAL_RELOAD_FEEDING", 3), Map.entry("TACTICAL_RELOAD_FINISHING", 4)));
    }

    private static List<Problem> invalid(ResourceVersion version, String field) {
        return List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("fpsmatch:weapons/invalid_builtin_asset"),
                "resource:" + version.id(), new TextContent.Message("fpsmatch.editor.weapons.invalid_builtin_asset",
                        List.of(new TextContent.LiteralArgument(field)))));
    }

    private static final class CancellationSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;
        final ResourceValidationCancelledException cancellation;

        CancellationSignal(ResourceValidationCancelledException cancellation) {
            super(null, null, false, false);
            this.cancellation = cancellation;
        }
    }

    /** Keep supplier IllegalArgumentException out of archive format error conversion. */
    private static final class SupplierSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;
        final Throwable original;

        SupplierSignal(Throwable original) {
            super(null, null, false, false);
            this.original = original;
        }
    }

    /** Owns both its private materialization and its authenticated fixed channel. */
    private static final class Physical implements AutoCloseable {

        final Path path;
        final PackSnapshot snapshot;

        Physical(Path path, PackSnapshot snapshot) {
            this.path = path;
            this.snapshot = snapshot;
        }

        static Physical open(ResourceVersion pack, ResourceContent content, BooleanSupplier cancelled) throws IOException {
            Path path = Files.createTempFile("editor-lr-asset-", ".edpack");
            try {
                try (OutputStream output = Files.newOutputStream(path)) {
                    content.copyContent(pack.sourceEntry(), output);
                }
                return new Physical(path, PackSnapshots.open(path, cancelled));
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        public void close() throws IOException {
            IOException failure = null;
            try {
                snapshot.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                if (failure == null) throw cleanup;
                else failure.addSuppressed(cleanup);
            }
            if (failure != null) throw failure;
        }
    }
}
