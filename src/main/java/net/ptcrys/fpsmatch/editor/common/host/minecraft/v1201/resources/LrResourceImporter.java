package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.resources;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.BuiltinWeaponPackFormat;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.LrBuiltinAssetFormat.Asset;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Imports the locked original LR 0.4.3 profile through privately owned, bounded snapshots. */
public final class LrResourceImporter implements ResourceImporter {

    private static final BuiltinWeaponPackFormat.Source PROFILE = BuiltinWeaponPackFormat.Source.LR_RESOURCES;
    private static final List<String> ROLES = List.of("pack", "pack-version", "pack-source",
            "display", "display-version", "display-source", "melee", "melee-version", "melee-source");

    public static void register(ExtensionRegistryBuilder builder) {
        ResourceImporters.register(builder, WeaponItemValues.LR_IMPORTER, WeaponItemValues.LR_MODULE, new LrResourceImporter());
    }

    @Override
    public TextContent label() {
        return text("label");
    }

    @Override
    public List<Input> inputs() {
        return List.of(new Input("container", text("container"), MAX_INPUT_BYTES));
    }

    @Override
    public Optional<TextContent> resourceLabel(ResourceCatalog catalog, UUID id) {
        var resource = Objects.requireNonNull(catalog.resources().get(id));
        var version = catalog.versions().get(resource.currentVersion());
        if (version != null && version.importer().equals(PROFILE.importer()) && version.importerVersion().equals("1") && resource.type().equals(BuiltinWeaponPackFormat.TYPE) && resource.name().equals(PROFILE.key()) && version.settings().equals(PROFILE.settings()))
            return Optional.of(text("name_pack"));
        if (version != null && version.importer().equals(WeaponItemValues.LR_IMPORTER) && version.importerVersion().equals("1")) {
            for (var asset : Asset.values()) if (resource.type().equals(asset.type()) && version.settings().equals(asset.settings()) && resource.name().equals(net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.LrBuiltinAssetFormat.KARAMBIT.toString()))
                return Optional.of(text(asset == Asset.MELEE ? "name_melee" : "name_display"));
        }
        return Optional.empty();
    }

    @Override
    public Prepared inspect(ExtensionRegistry registry, Map<String, Source> inputs, ResourceCatalog baseline,
                            UUID seed, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(cancelled);
        ResourceImporters.require(registry, WeaponItemValues.LR_IMPORTER, this);
        if (!inputs.keySet().equals(Set.of("container"))) throw new IOException("Expected one LR container");
        Source original = Objects.requireNonNull(inputs.get("container"));
        BlobRef declared = Objects.requireNonNull(original.content());
        if (declared.size() > MAX_INPUT_BYTES || !declared.sha256().equals(PROFILE.containerSha256()))
            throw new ResourceImportException(List.of(new Problem(Problem.Severity.ERROR,
                    EditorId.parse("fpsmatch:resource_import/unsupported_lr"), "container", text("unsupported"))));
        UUID[] ids = ROLES.stream().map(role -> UUID.nameUUIDFromBytes((seed + ":lr:" + role).getBytes(StandardCharsets.UTF_8)))
                .toArray(UUID[]::new);
        for (UUID id : ids) if (baseline.resources().containsKey(id) || baseline.versions().containsKey(id) || baseline.byteEntries().containsKey(id)) throw new IOException("Existing LR import identity");
        check(cancelled);
        Path directory = Files.createTempDirectory("editor-resource-lr-");
        Path container = directory.resolve("container.jar"), pack = directory.resolve("pack.edpack");
        Path display = directory.resolve("display.json"), melee = directory.resolve("melee.json");
        OwnedPrepared prepared = new OwnedPrepared(directory, List.of(container, pack, display, melee));
        try {
            BlobRef copied;
            try (InputStream input = original.open()) {
                copied = copy(input, container, MAX_INPUT_BYTES, cancelled);
            }
            if (copied.size() != declared.size() || !copied.sha256().equals(declared.sha256()))
                throw new IOException("LR container content changed");
            var index = BuiltinWeaponPacks.capture(PROFILE, container, pack, cancelled);
            BlobRef packBlob = new BlobRef(BlobRef.Kind.ASSET, index.sha256(), Files.size(pack));
            var added = PROFILE.catalog(ids[0], ids[1], ids[2], packBlob);
            var resources = new HashMap<>(baseline.resources());
            resources.putAll(added.resources());
            var versions = new HashMap<>(baseline.versions());
            versions.putAll(added.versions());
            var entries = new HashMap<>(baseline.byteEntries());
            entries.putAll(added.byteEntries());
            ResourceCatalog candidate = new ResourceCatalog(resources, versions, entries);
            prepared.add(ids[2], pack, packBlob);
            try (var physical = PackSnapshots.open(pack, cancelled)) {
                for (Asset asset : List.of(Asset.DISPLAY, Asset.MELEE)) {
                    boolean isDisplay = asset == Asset.DISPLAY;
                    Path target = isDisplay ? display : melee;
                    BlobRef blob;
                    try (InputStream input = physical.openEntry(asset.path(), cancelled)) {
                        blob = copy(input, target, asset.size(), cancelled);
                    }
                    int offset = isDisplay ? 3 : 6;
                    candidate = asset.addTo(candidate, ids[offset], ids[offset + 1], ids[offset + 2], blob,
                            ids[1], isDisplay ? null : ids[4]);
                    prepared.add(ids[offset + 2], target, blob);
                }
            }
            Files.delete(container);
            check(cancelled);
            prepared.candidate = candidate;
            return prepared;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                prepared.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static BlobRef copy(InputStream input, Path target, long limit, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        long size = 0;
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[65536];
            while (true) {
                check(cancelled);
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, limit - size + 1));
                if (read < 0) break;
                check(cancelled);
                if (read > limit - size) throw new IOException("LR input byte budget exceeded");
                size += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        check(cancelled);
        return new BlobRef(BlobRef.Kind.ASSET, HexFormat.of().formatHex(digest.digest()), size);
    }

    private static void check(BooleanSupplier cancelled) throws ResourceValidationCancelledException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new ResourceValidationCancelledException();
    }

    private static TextContent text(String suffix) {
        return new TextContent.Message("editor.workbench.resource_lr_" + suffix, List.of());
    }

    private static final class OwnedPrepared implements Prepared {

        private final Path directory;
        private final List<Path> files;
        private final Map<UUID, Source> sources = new HashMap<>();
        private final Set<InputStream> streams = new HashSet<>();
        private ResourceCatalog candidate;
        private boolean closed;

        OwnedPrepared(Path directory, List<Path> files) {
            this.directory = directory;
            this.files = files;
        }

        void add(UUID id, Path path, BlobRef blob) {
            sources.put(id, new Source() {

                public BlobRef content() {
                    return blob;
                }

                public InputStream open() throws IOException {
                    return openOwned(path);
                }
            });
        }

        private synchronized InputStream openOwned(Path path) throws IOException {
            if (closed) throw new IOException("LR preparation is closed");
            InputStream stream = new FilterInputStream(Files.newInputStream(path)) {

                @Override
                public void close() throws IOException {
                    synchronized (OwnedPrepared.this) {
                        try {
                            super.close();
                        } finally {
                            streams.remove(this);
                        }
                    }
                }
            };
            streams.add(stream);
            return stream;
        }

        public ResourceCatalog candidate() {
            return candidate;
        }

        public Map<UUID, Source> sources() {
            return Map.copyOf(sources);
        }

        public List<TextContent> summary() {
            return List.of(text("summary_pack"), text("summary_display"), text("summary_melee"));
        }

        public synchronized void close() throws IOException {
            closed = true;
            IOException failure = null;
            for (InputStream stream : List.copyOf(streams)) {
                try {
                    stream.close();
                } catch (IOException exception) {
                    failure = append(failure, exception);
                }
            }
            for (Path path : files) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    failure = append(failure, exception);
                }
            }
            try {
                Files.deleteIfExists(directory);
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
            if (failure != null) throw failure;
        }

        private static IOException append(IOException first, IOException next) {
            if (first == null) return next;
            first.addSuppressed(next);
            return first;
        }
    }
}
