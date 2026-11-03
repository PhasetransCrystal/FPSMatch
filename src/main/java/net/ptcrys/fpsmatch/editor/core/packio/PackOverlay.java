package net.ptcrys.fpsmatch.editor.core.packio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A fixed physical-source view in caller-supplied, highest-priority-first order.
 * Index descriptors are borrowed immutable values; this view neither authenticates
 * archive bytes nor owns snapshots, tasks, threads, or host resources.
 */
public final class PackOverlay {

    private static final int LAYER_LIMIT = 32;
    private static final int ENTRY_LIMIT = 16384;
    private static final long METADATA_LIMIT = 8L * 1024 * 1024;

    public record Layer(String id, PackIndex index) {

        public Layer {
            validateLayerId(id);
            Objects.requireNonNull(index, "index");
        }
    }

    public record Candidate(String layerId, String archiveSha256, PackEntry entry) {

        public Candidate {
            validateLayerId(layerId);
            Objects.requireNonNull(archiveSha256, "archiveSha256");
            PackIo.validateHash(archiveSha256);
            Objects.requireNonNull(entry, "entry");
        }
    }

    public record Selection(String path, List<Candidate> candidates) {

        public Selection {
            Objects.requireNonNull(path, "path");
            PackIo.validatePath(path);
            Objects.requireNonNull(candidates, "candidates");
            if (candidates.isEmpty() || candidates.size() > LAYER_LIMIT) {
                throw new IllegalArgumentException("Invalid candidate count");
            }
            candidates = List.copyOf(candidates);
            Set<String> layerIds = new HashSet<>();
            for (Candidate candidate : candidates) {
                if (!path.equals(candidate.entry().path())) {
                    throw new IllegalArgumentException("Candidate path mismatch");
                }
                if (!layerIds.add(candidate.layerId())) {
                    throw new IllegalArgumentException("Duplicate candidate layer");
                }
            }
        }

        public Candidate winner() {
            return candidates.get(0);
        }

        public boolean hasDifferentContent() {
            PackEntry first = winner().entry();
            for (Candidate candidate : candidates) {
                PackEntry entry = candidate.entry();
                if (entry.size() != first.size() || !entry.sha256().equals(first.sha256())) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Layer> layers;
    private final NavigableMap<String, Selection> selections;

    public PackOverlay(List<Layer> layers) {
        Objects.requireNonNull(layers, "layers");
        if (layers.isEmpty() || layers.size() > LAYER_LIMIT) {
            throw new IllegalArgumentException("Invalid layer count");
        }
        List<Layer> fixedLayers = List.copyOf(layers);

        // Count every entry occurrence before allocating either aggregate map.
        Set<String> layerIds = new HashSet<>();
        int entryCount = 0;
        long metadata = 0;
        for (Layer layer : fixedLayers) {
            if (!layerIds.add(layer.id())) throw new IllegalArgumentException("Duplicate layer id");
            entryCount += layer.index().entries().size();
            if (entryCount > ENTRY_LIMIT) throw new IllegalArgumentException("Overlay entry budget exceeded");
            metadata += 128L + 2L * layer.id().length();
            for (PackEntry entry : layer.index().entries()) {
                metadata += 128L + 2L * entry.path().length();
            }
            if (metadata > METADATA_LIMIT) {
                throw new IllegalArgumentException("Overlay metadata budget exceeded");
            }
        }

        NavigableMap<String, List<Candidate>> grouped = new TreeMap<>();
        for (Layer layer : fixedLayers) {
            for (PackEntry entry : layer.index().entries()) {
                grouped.computeIfAbsent(entry.path(), path -> new ArrayList<>())
                        .add(new Candidate(layer.id(), layer.index().sha256(), entry));
            }
        }
        NavigableMap<String, Selection> fixedSelections = new TreeMap<>();
        grouped.forEach((path, candidates) -> fixedSelections.put(path, new Selection(path, candidates)));
        this.layers = fixedLayers;
        this.selections = Collections.unmodifiableNavigableMap(fixedSelections);
    }

    public List<Layer> layers() {
        return layers;
    }

    public Optional<Selection> resolve(String path) {
        Objects.requireNonNull(path, "path");
        PackIo.validatePath(path);
        return Optional.ofNullable(selections.get(path));
    }

    public List<Selection> list(String directory) {
        Objects.requireNonNull(directory, "directory");
        if (directory.isEmpty()) return List.copyOf(selections.values());
        PackIo.validatePath(directory);
        String prefix = directory + '/';
        // '0' immediately follows '/' in String.compareTo order, bounding descendants.
        return List.copyOf(selections.subMap(prefix, true, directory + '0', false).values());
    }

    private static void validateLayerId(String id) {
        Objects.requireNonNull(id, "layerId");
        if (id.length() > 128 || !id.matches("[a-z0-9][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid layer id");
        }
    }
}
