package net.ptcrys.fpsmatch.editor.core.examples;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.PinnedSnapshotPack;
import net.ptcrys.fpsmatch.editor.core.packio.PackOverlay;

/** Exercises actual MC resource classes, without starting a client/server or third-party loaders. */
public final class PinnedPackConsumer {
    private PinnedPackConsumer() { }
    private record Content(long size, String sha256) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("output-directory default.edpack mcs.edpack gz.edpack");
        Path output = Path.of(args[0]);
        Files.createDirectory(output);
        List<PinnedSnapshotPack.Source> forward = List.of(
                new PinnedSnapshotPack.Source("default", Path.of(args[1]),
                        "4a13ff3c995db582808761b479a4e81054001c3a815e474c7e7bbf351c4d3b3b"),
                new PinnedSnapshotPack.Source("mcs", Path.of(args[2]),
                        "a69ace9ca708c297ce02e3441a3115beb0b73d363d2553156d91a3be11358880"),
                new PinnedSnapshotPack.Source("gz", Path.of(args[3]),
                        "0ce40b67e1a6922bd5d26d44d6f784036db3e5ff0029a0a3f181c54a79af1992"));
        List<PinnedSnapshotPack.Source> reverse = new ArrayList<>(forward);
        java.util.Collections.reverse(reverse);
        for (String order : List.of("forward", "reverse")) {
            List<PinnedSnapshotPack.Source> sources = order.equals("forward") ? forward : reverse;
            List<String> ignored = null;
            try (BufferedWriter report = Files.newBufferedWriter(output.resolve(order + ".tsv"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                report.write("type\tnamespace\tpath\tlayer\tarchiveSha256\tsize\tsha256\n");
                long bytes = 0;
                int count = 0;
                for (PackType type : List.of(PackType.CLIENT_RESOURCES, PackType.SERVER_DATA)) {
                    PinnedSnapshotPack pack = PinnedSnapshotPack.open("editor:pinned_test", sources, () -> false);
                    var stale = pack.getRootResource("pack.mcmeta");
                    if (stale == null || pack.getRootResource("gunpack.meta.json") != null) {
                        throw new AssertionError("source metadata leaked into aggregate root");
                    }
                    try (InputStream metadata = stale.get()) {
                        String text = new String(metadata.readAllBytes(), StandardCharsets.UTF_8);
                        if (!text.contains("fpsmatch.editor.snapshot_pack")) throw new AssertionError("metadata key");
                    }
                    if (ignored == null) ignored = pack.ignoredResourcePaths();
                    else if (!ignored.equals(pack.ignoredResourcePaths())) throw new AssertionError("unstable ignored paths");
                    try (MultiPackResourceManager manager = new MultiPackResourceManager(type, List.of(pack))) {
                        Map<ResourceLocation, Resource> listed = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
                        listed.putAll(manager.listResources("", location -> true));
                        for (var entry : listed.entrySet()) {
                            ResourceLocation id = entry.getKey();
                            Content fromList = content(entry.getValue());
                            Content fromGet = content(manager.getResource(id).orElseThrow());
                            if (!fromList.equals(fromGet)) throw new AssertionError("get/list content differs: " + id);
                            String path = type.getDirectory() + '/' + id.getNamespace() + '/' + id.getPath();
                            PackOverlay.Candidate winner = pack.overlay().resolve(path).orElseThrow().winner();
                            if (fromList.size() != winner.entry().size() || !fromList.sha256().equals(winner.entry().sha256())) {
                                throw new AssertionError("MC content differs from fixed winner: " + id);
                            }
                            report.write(type.getDirectory() + "\t" + id.getNamespace() + "\t" + id.getPath() + "\t"
                                    + winner.layerId() + "\t" + winner.archiveSha256() + "\t"
                                    + fromList.size() + "\t" + fromList.sha256() + "\n");
                            count++;
                            bytes += fromList.size();
                        }
                    } finally {
                        pack.close();
                    }
                    try {
                        stale.get().close();
                        throw new AssertionError("late root supplier opened after close");
                    } catch (IOException expected) {
                        // Successful close must make the captured supplier unusable.
                    }
                }
                System.out.println("PINNED_MC_RESOURCE_VIEW_PASS order=" + order + " files=" + count + " bytes=" + bytes);
            }
            Files.write(output.resolve(order + "-ignored.txt"), ignored == null ? List.of() : ignored,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        System.out.println("PINNED_MC_CONSUMER_PASS; actual isolated MC resource manager only; no game reload/weapon/RC approval");
    }

    private static Content content(Resource resource) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        byte[] buffer = new byte[32749];
        try (InputStream input = resource.open()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read <= 0) throw new AssertionError("no progress");
                size += read;
                digest.update(buffer, 0, read);
            }
        }
        return new Content(size, HexFormat.of().formatHex(digest.digest()));
    }
}
