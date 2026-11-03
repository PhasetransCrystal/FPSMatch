package net.ptcrys.fpsmatch.editor.core.examples;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.ptcrys.fpsmatch.editor.core.packio.PackOverlay;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshot;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;

/** Public-JAR consumer of fixed physical-source precedence, without a weapon producer claim. */
public final class PackOverlayConsumer {
    private PackOverlayConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("NEW_OUTPUT DEFAULT MCS GZ");
        Path output = Files.createDirectory(Path.of(args[0]));
        try (PackSnapshot defaults = PackSnapshots.open(Path.of(args[1]), () -> false);
                PackSnapshot mcs = PackSnapshots.open(Path.of(args[2]), () -> false);
                PackSnapshot gz = PackSnapshots.open(Path.of(args[3]), () -> false)) {
            List<PackSnapshot> readers = List.of(defaults, mcs, gz);
            List<PackOverlay.Layer> layers = List.of(new PackOverlay.Layer("default", defaults.index()),
                    new PackOverlay.Layer("mcs", mcs.index()), new PackOverlay.Layer("gz", gz.index()));
            long start = System.nanoTime();
            PackOverlay overlay = new PackOverlay(layers);
            List<PackOverlay.Layer> reversed = new ArrayList<>(layers);
            Collections.reverse(reversed);
            PackOverlay reverse = new PackOverlay(reversed);
            long constructNanos = System.nanoTime() - start;
            verifyAndWrite(overlay, output.resolve("forward.tsv"));
            verifyAndWrite(reverse, output.resolve("reverse.tsv"));
            check(overlay.layers().equals(layers), "explicit order retained");
            check(overlay.resolve("assets/absent/file.json").isEmpty(), "missing stays absent");
            for (String target : List.of("data/gz/index/guns/military_ak74.json",
                    "data/tacz/index/guns/deagle.json", "data/tacz/index/guns/cz75.json",
                    "data/tacz/index/guns/glock_17.json", "data/tacz/index/guns/ai_awp.json")) {
                PackOverlay.Candidate winner = overlay.resolve(target).orElseThrow().winner();
                int selected = -1;
                for (int i = 0; i < layers.size(); i++) {
                    if (layers.get(i).id().equals(winner.layerId())) selected = i;
                }
                check(selected >= 0 && readers.get(selected).index().sha256().equals(winner.archiveSha256()),
                        "selected source is the fixed archive");
                readers.get(selected).copyEntry(target, OutputStream.nullOutputStream(), () -> false);
            }
            List<PackOverlay.Selection> selected = overlay.list("");
            long collisions = selected.stream().filter(s -> s.candidates().size() > 1).count();
            long different = selected.stream().filter(PackOverlay.Selection::hasDifferentContent).count();
            Files.writeString(output.resolve("measurements.txt"), "inputEntries="
                    + layers.stream().mapToInt(l -> l.index().entries().size()).sum()
                    + "\nresolvedPaths=" + selected.size() + "\ncollisions=" + collisions
                    + "\ndifferentContent=" + different + "\nconstructTwoViewsNanos=" + constructNanos
                    + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("PACK_OVERLAY_PUBLIC_CONSUMER_PASS resolved=" + selected.size()
                    + " collisions=" + collisions + " differentContent=" + different
                    + "; physical precedence only; weapon closure and host mount unverified");
        }
    }

    private static void verifyAndWrite(PackOverlay overlay, Path report) throws Exception {
        List<PackOverlay.Selection> all = overlay.list("");
        for (PackOverlay.Selection selection : all) {
            check(overlay.resolve(selection.path()).orElseThrow().equals(selection), "list/resolve agreement");
        }
        for (String directory : List.of("assets", "data", "assets/tacz", "assets/gz", "assets/tacz2")) {
            List<PackOverlay.Selection> expected = all.stream()
                    .filter(s -> s.path().startsWith(directory + "/")).toList();
            check(overlay.list(directory).equals(expected), "directory boundary: " + directory);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
            writer.write("path\twinner\tarchiveSha256\tsize\tsha256\tcandidateLayers\tdifferentContent\n");
            for (PackOverlay.Selection selection : all) {
                PackOverlay.Candidate winner = selection.winner();
                writer.write(selection.path() + "\t" + winner.layerId() + "\t" + winner.archiveSha256()
                        + "\t" + winner.entry().size() + "\t" + winner.entry().sha256() + "\t"
                        + String.join(",", selection.candidates().stream().map(PackOverlay.Candidate::layerId).toList())
                        + "\t" + selection.hasDifferentContent() + "\n");
            }
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
