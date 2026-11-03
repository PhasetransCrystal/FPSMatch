package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201;

import net.ptcrys.fpsmatch.editor.core.packio.PackOverlay;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshotException;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/** Minecraft 1.20.1 view of explicitly ordered, authenticated fixed snapshots. */
public final class PinnedSnapshotPack extends AbstractPackResources {

    private static final byte[] METADATA = ("{\"pack\":{\"pack_format\":15," + "\"description\":{\"translate\":\"fpsmatch.editor.snapshot_pack\"}}}")
            .getBytes(StandardCharsets.UTF_8);
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    public record Source(String id, Path archive, String archiveSha256) {

        public Source {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(archive, "archive");
            Objects.requireNonNull(archiveSha256, "archiveSha256");
            if (!id.matches("[a-z0-9][a-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Invalid source id");
            }
            if (!archiveSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid SHA-256");
            }
            archive = archive.toAbsolutePath().normalize();
        }
    }

    private record Emission(ResourceLocation location, IoSupplier<InputStream> supplier) {}

    private final Object owner = new Object();
    private final PackOverlay overlay;
    private final Map<String, PackSnapshot> readers;
    private final Map<PackType, Set<String>> namespaces;
    private final List<String> ignoredResourcePaths;
    private boolean closed;

    private PinnedSnapshotPack(String packId, PackOverlay overlay, Map<String, PackSnapshot> readers) {
        super(packId, false);
        this.overlay = overlay;
        this.readers = Map.copyOf(readers);
        Map<PackType, Set<String>> collected = new EnumMap<>(PackType.class);
        for (PackType type : PackType.values()) collected.put(type, new TreeSet<>());
        Set<String> ignored = new TreeSet<>();
        for (PackOverlay.Selection selection : overlay.list("")) {
            for (PackType type : PackType.values()) {
                String root = type.getDirectory() + '/';
                if (!selection.path().startsWith(root)) continue;
                ResourceLocation location = nativeLocation(selection.path().substring(root.length()));
                if (location == null) ignored.add(selection.path());
                else collected.get(type).add(location.getNamespace());
            }
        }
        Map<PackType, Set<String>> fixed = new EnumMap<>(PackType.class);
        collected.forEach((type, values) -> fixed.put(type, Collections.unmodifiableSortedSet(new TreeSet<>(values))));
        this.namespaces = Map.copyOf(fixed);
        this.ignoredResourcePaths = List.copyOf(ignored);
    }

    /** Synchronous I/O; ownership transfers only after complete construction succeeds. */
    public static PinnedSnapshotPack open(String packId, List<Source> highToLow,
                                          BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(highToLow, "highToLow");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!packId.matches("[a-z0-9][a-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid pack id");
        }
        if (highToLow.isEmpty() || highToLow.size() > 32) {
            throw new IllegalArgumentException("Invalid source count");
        }
        List<Source> sources = List.copyOf(highToLow);
        Set<String> ids = new HashSet<>();
        for (Source source : sources) {
            if (!ids.add(source.id())) throw new IllegalArgumentException("Duplicate source id");
        }
        List<PackSnapshot> opened = new ArrayList<>(32);
        Map<String, PackSnapshot> readers = new HashMap<>();
        List<PackOverlay.Layer> layers = new ArrayList<>(32);
        try {
            PackOverlay overlay = null;
            for (Source source : sources) {
                checkCancellation(cancelled);
                PackSnapshot reader = PackSnapshots.open(source.archive(), cancelled);
                opened.add(reader);
                if (!reader.index().sha256().equals(source.archiveSha256())) {
                    throw new PackSnapshotException(PackSnapshotException.Code.CONTENT_MISMATCH,
                            "Archive digest does not match source");
                }
                checkCancellation(cancelled);
                readers.put(source.id(), reader);
                layers.add(new PackOverlay.Layer(source.id(), reader.index()));
                // Apply cumulative Q44 budgets before opening another archive.
                overlay = new PackOverlay(layers);
            }
            PinnedSnapshotPack pack = new PinnedSnapshotPack(packId, overlay, readers);
            checkCancellation(cancelled);
            return pack;
        } catch (IOException | RuntimeException | Error failure) {
            for (PackSnapshot reader : opened) {
                try {
                    reader.close();
                } catch (IOException | RuntimeException | Error closeFailure) {
                    if (closeFailure != failure) failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private static void checkCancellation(BooleanSupplier cancelled) throws PackSnapshotException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new PackSnapshotException(PackSnapshotException.Code.CANCELLED, "Operation cancelled");
        }
    }

    public PackOverlay overlay() {
        return overlay;
    }

    public List<String> ignoredResourcePaths() {
        return ignoredResourcePaths;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        synchronized (owner) {
            requireOpen();
            Objects.requireNonNull(elements, "elements");
            if (elements.length != 1 || !"pack.mcmeta".equals(elements[0])) return null;
            return () -> {
                synchronized (owner) {
                    requireSupplierOpen();
                    return new ByteArrayInputStream(METADATA);
                }
            };
        }
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        synchronized (owner) {
            requireOpen();
            Objects.requireNonNull(packType, "packType");
            Objects.requireNonNull(location, "location");
            if (!validNamespace(location.getNamespace()) || !validPath(location.getPath(), false)) return null;
            String path = packType.getDirectory() + '/' + location.getNamespace() + '/' + location.getPath();
            // MC accepts some names that the physical snapshot format rejects.
            PackOverlay.Selection selection;
            try {
                selection = overlay.resolve(path).orElse(null);
            } catch (IllegalArgumentException invalidPhysicalPath) {
                return null;
            }
            return selection == null ? null : supplier(selection);
        }
    }

    @Override
    public void listResources(PackType packType, String namespace, String path, ResourceOutput resourceOutput) {
        List<Emission> emissions;
        synchronized (owner) {
            requireOpen();
            Objects.requireNonNull(packType, "packType");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(resourceOutput, "resourceOutput");
            if (!validNamespace(namespace) || !validPath(path, true)) {
                throw new IllegalArgumentException("Invalid resource namespace or prefix");
            }
            String root = packType.getDirectory() + '/';
            String directory = root + namespace + (path.isEmpty() ? "" : '/' + path);
            List<PackOverlay.Selection> selections;
            try {
                selections = overlay.list(directory);
            } catch (IllegalArgumentException invalidPhysicalPath) {
                selections = List.of();
            }
            List<Emission> captured = new ArrayList<>();
            for (PackOverlay.Selection selection : selections) {
                ResourceLocation location = nativeLocation(selection.path().substring(root.length()));
                if (location != null) captured.add(new Emission(location, supplier(selection)));
            }
            emissions = List.copyOf(captured);
        }
        // User code can close this pack; captured suppliers then reject opening.
        for (Emission emission : emissions) resourceOutput.accept(emission.location(), emission.supplier());
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        synchronized (owner) {
            requireOpen();
            return namespaces.get(Objects.requireNonNull(type, "type"));
        }
    }

    private IoSupplier<InputStream> supplier(PackOverlay.Selection selection) {
        PackSnapshot reader = readers.get(selection.winner().layerId());
        String path = selection.path();
        return () -> {
            synchronized (owner) {
                requireSupplierOpen();
                return reader.openEntry(path, NEVER_CANCELLED);
            }
        };
    }

    private static ResourceLocation nativeLocation(String relative) {
        int slash = relative.indexOf('/');
        if (slash < 0) return null;
        String namespace = relative.substring(0, slash);
        String path = relative.substring(slash + 1);
        if (!validNamespace(namespace) || !validPath(path, false)) return null;
        return ResourceLocation.tryBuild(namespace, path);
    }

    private static boolean validNamespace(String namespace) {
        return !namespace.isEmpty() && !namespace.equals(".") && !namespace.equals("..") && ResourceLocation.isValidNamespace(namespace);
    }

    private static boolean validPath(String path, boolean allowEmpty) {
        if (path.isEmpty()) return allowEmpty;
        if (!ResourceLocation.isValidPath(path)) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Pack is closed");
    }

    private void requireSupplierOpen() throws IOException {
        if (closed) throw new IOException("Pack is closed");
    }

    @Override
    public void close() {
        synchronized (owner) {
            if (closed) return;
            closed = true;
        }
        IOException failure = null;
        for (PackSnapshot reader : readers.values()) {
            try {
                reader.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw new UncheckedIOException(failure);
    }
}
