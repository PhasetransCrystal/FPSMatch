package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.resources;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.*;
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

/** Explicit fixed original TaCZ default pack; never consults a live gun manager. */
public final class TaczResourceImporter implements ResourceImporter {

    private static final BuiltinWeaponPackFormat.Source DEFAULT = BuiltinWeaponPackFormat.Source.TACZ_DEFAULT;
    private static final BuiltinWeaponPackFormat.Source ORDINARY = BuiltinWeaponPackFormat.Source.TACZ_RESOURCES;

    public static void register(ExtensionRegistryBuilder builder) {
        ResourceImporters.register(builder, WeaponItemValues.TACZ_IMPORTER, WeaponItemValues.TACZ_MODULE, new TaczResourceImporter());
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
        if (version == null) return Optional.empty();
        for (var pack : List.of(DEFAULT, ORDINARY)) {
            if (resource.type().equals(BuiltinWeaponPackFormat.TYPE) && resource.name().equals(pack.key()) && version.importer().equals(pack.importer()) && version.importerVersion().equals("1") && version.settings().equals(pack.settings())) return Optional.of(text("name_" + pack.key()));
        }
        return TaczBuiltinAssetFormat.asset(version, resource.type())
                .filter(asset -> resource.name().equals(asset.id().toString()))
                .map(asset -> new TextContent.Message("editor.workbench.resource_tacz_name_" + (asset.type().equals(WeaponItemValues.TACZ_GUN_ASSET) ? "gun" : "display"),
                        List.of(new TextContent.NestedArgument(new TextContent.Message(asset.labelKey(), List.of())))));
    }

    @Override
    public Prepared inspect(ExtensionRegistry registry, Map<String, Source> inputs, ResourceCatalog baseline,
                            UUID seed, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(cancelled);
        ResourceImporters.require(registry, WeaponItemValues.TACZ_IMPORTER, this);
        if (!inputs.keySet().equals(Set.of("container"))) throw new IOException("Expected one TaCZ container");
        Source original = Objects.requireNonNull(inputs.get("container"));
        BlobRef declared = Objects.requireNonNull(original.content());
        if (declared.size() > MAX_INPUT_BYTES || !declared.sha256().equals(DEFAULT.containerSha256()))
            throw new ResourceImportException(List.of(new Problem(Problem.Severity.ERROR,
                    EditorId.parse("fpsmatch:resource_import/unsupported_tacz"), "container", text("unsupported"))));
        var identities = new LinkedHashMap<String, UUID[]>();
        for (var pack : List.of(DEFAULT, ORDINARY)) identities.put(pack.key(), ids(seed, pack.key()));
        for (var asset : TaczBuiltinAssetFormat.assets()) identities.put(asset.type() + "|" + asset.id(), ids(seed, asset.type() + "|" + asset.id()));
        var all = new HashSet<UUID>();
        for (var group : identities.values()) for (var id : group) {
            if (!all.add(id) || baseline.resources().containsKey(id) || baseline.versions().containsKey(id) || baseline.byteEntries().containsKey(id)) throw new IOException("Existing TaCZ import identity");
        }
        check(cancelled);
        Path directory = Files.createTempDirectory("editor-resource-tacz-");
        var files = new ArrayList<Path>();
        Path container = directory.resolve("container.jar");
        files.add(container);
        OwnedPrepared prepared = new OwnedPrepared(directory, files);
        try {
            BlobRef copied;
            try (InputStream input = original.open()) {
                copied = copy(input, container, MAX_INPUT_BYTES, cancelled);
            }
            if (!copied.equals(declared)) throw new IOException("TaCZ container content changed");
            ResourceCatalog candidate = baseline;
            Path defaultPack = null;
            for (var pack : List.of(DEFAULT, ORDINARY)) {
                check(cancelled);
                Path target = directory.resolve(pack.key() + ".edpack");
                files.add(target);
                var index = BuiltinWeaponPacks.capture(pack, container, target, cancelled);
                var blob = new BlobRef(BlobRef.Kind.ASSET, index.sha256(), Files.size(target));
                var group = identities.get(pack.key());
                var added = pack.catalog(group[0], group[1], group[2], blob);
                var resources = new HashMap<>(candidate.resources());
                resources.putAll(added.resources());
                var versions = new HashMap<>(candidate.versions());
                versions.putAll(added.versions());
                var entries = new HashMap<>(candidate.byteEntries());
                entries.putAll(added.byteEntries());
                candidate = new ResourceCatalog(resources, versions, entries);
                prepared.add(group[2], target, blob);
                if (pack == DEFAULT) defaultPack = target;
            }
            // Displays must exist before guns refer to their exact version identities.
            var assets = TaczBuiltinAssetFormat.assets().stream()
                    .sorted(Comparator.comparing(asset -> asset.type().equals(WeaponItemValues.TACZ_GUN_ASSET))).toList();
            try (var physical = PackSnapshots.open(Objects.requireNonNull(defaultPack), cancelled)) {
                for (var asset : assets) {
                    check(cancelled);
                    var group = identities.get(asset.type() + "|" + asset.id());
                    Path target = directory.resolve(group[2] + ".json");
                    files.add(target);
                    BlobRef blob;
                    try (InputStream input = physical.openEntry(asset.path(), cancelled)) {
                        blob = copy(input, target, asset.size(), cancelled);
                    }
                    UUID display = asset.defaultDisplay() == null ? null : identities.get(WeaponItemValues.TACZ_DISPLAY_ASSET + "|" + asset.defaultDisplay())[1];
                    candidate = asset.addTo(candidate, group[0], group[1], group[2], blob,
                            identities.get(DEFAULT.key())[1], identities.get(ORDINARY.key())[1], display);
                    prepared.add(group[2], target, blob);
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

    private static UUID[] ids(UUID seed, String key) {
        return List.of("resource", "version", "source").stream()
                .map(role -> UUID.nameUUIDFromBytes((seed + ":tacz:" + key + ":" + role).getBytes(StandardCharsets.UTF_8)))
                .toArray(UUID[]::new);
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
                if (read > limit - size) throw new IOException("TaCZ input byte budget exceeded");
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
        return new TextContent.Message("editor.workbench.resource_tacz_" + suffix, List.of());
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
            if (closed) throw new IOException("TaCZ preparation is closed");
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
            return List.of(text("summary_packs"), text("summary_guns"), text("summary_displays"));
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
