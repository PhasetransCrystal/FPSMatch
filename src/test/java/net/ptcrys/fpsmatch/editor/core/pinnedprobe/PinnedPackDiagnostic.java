package net.ptcrys.fpsmatch.editor.core.pinnedprobe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.PinnedSnapshotPack;

/** Fixed input parsing and one bounded ResourceManager consumption, without filesystem discovery. */
final class PinnedPackDiagnostic {
    static final String PACK_ID = "editor:q47_pinned_snapshot";
    static final int ASSET_COUNT = 3258;
    static final Map<String, String> SOURCE_SHA = Map.of(
            "default", "4a13ff3c995db582808761b479a4e81054001c3a815e474c7e7bbf351c4d3b3b",
            "mcs", "a69ace9ca708c297ce02e3441a3115beb0b73d363d2553156d91a3be11358880",
            "gz", "0ce40b67e1a6922bd5d26d44d6f784036db3e5ff0029a0a3f181c54a79af1992");
    private static final String HEADER = "type\tnamespace\tpath\tlayer\tarchiveSha256\tsize\tsha256";

    record Expected(ResourceLocation location, long size, String sha256) { }
    record Inputs(List<PinnedSnapshotPack.Source> sources, List<Expected> assets) { }

    static final class Progress {
        volatile int listReadEntries;
        volatile int getReadEntries;
        volatile int verifiedAssets;
        volatile long bytesRead;
        volatile long maximumEntryBytes;
        volatile String maximumEntry = "";
    }

    private PinnedPackDiagnostic() { }

    static Inputs inputs() throws IOException {
        Path snapshotDir = propertyPath("q47.probe.snapshotDir");
        Path expected = propertyPath("q47.probe.expected");
        List<PinnedSnapshotPack.Source> sources = new ArrayList<>();
        for (String id : List.of("default", "mcs", "gz")) {
            sources.add(new PinnedSnapshotPack.Source(id, snapshotDir.resolve(id + ".edpack"), SOURCE_SHA.get(id)));
        }
        if (Files.size(expected) > 2 * 1024 * 1024) throw new IOException("Expected TSV exceeds bounded size");
        List<Expected> assets = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int dataRows = 0;
        try (BufferedReader reader = Files.newBufferedReader(expected, StandardCharsets.UTF_8)) {
            if (!HEADER.equals(reader.readLine())) throw new IOException("Expected TSV header mismatch");
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (++rows > 4058 || line.length() > 4096) throw new IOException("Expected TSV row budget exceeded");
                String[] cells = line.split("\t", -1);
                if (cells.length != 7 || !(cells[0].equals("assets") || cells[0].equals("data"))) {
                    throw new IOException("Invalid expected TSV row " + rows);
                }
                ResourceLocation location = ResourceLocation.tryBuild(cells[1], cells[2]);
                if (location == null || cells[1].equals(".") || cells[1].equals("..")
                        || cells[2].isEmpty() || !validSegments(cells[2])) {
                    throw new IOException("Invalid expected resource identity at row " + rows);
                }
                if (!identities.add(cells[0] + '/' + location)) throw new IOException("Duplicate expected identity");
                if (!cells[4].equals(SOURCE_SHA.get(cells[3])) || !cells[6].matches("[0-9a-f]{64}")
                        || !cells[5].matches("0|[1-9][0-9]*")) {
                    throw new IOException("Invalid expected source, size or digest at row " + rows);
                }
                long size;
                try {
                    size = Long.parseLong(cells[5]);
                } catch (NumberFormatException failure) {
                    throw new IOException("Expected size overflow", failure);
                }
                if (cells[0].equals("assets")) assets.add(new Expected(location, size, cells[6]));
                else dataRows++;
            }
        }
        if (assets.size() != ASSET_COUNT || dataRows != 800) throw new IOException("Expected TSV count mismatch");
        return new Inputs(List.copyOf(sources), List.copyOf(assets));
    }

    private static Path propertyPath(String name) throws IOException {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IOException("Missing diagnostic property " + name);
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean validSegments(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    static void scan(ResourceManager manager, List<Expected> expected, AtomicBoolean cancelled,
                     AtomicLong factoryGeneration, long capturedGeneration, Progress progress) throws IOException {
        checkCancellation(cancelled, factoryGeneration, capturedGeneration);
        Map<ResourceLocation, Expected> identities = new LinkedHashMap<>();
        for (Expected entry : expected) identities.put(entry.location(), entry);
        // Restrict enumeration to the authenticated TSV identities; extras from other packs are irrelevant.
        Map<ResourceLocation, Resource> listed = manager.listResources("", identities::containsKey);
        checkCancellation(cancelled, factoryGeneration, capturedGeneration);
        for (Expected entry : expected) {
            checkCancellation(cancelled, factoryGeneration, capturedGeneration);
            Resource fromList = listed.get(entry.location());
            if (fromList == null) throw new IOException("listResources missing " + entry.location());
            consume(fromList, entry, cancelled, factoryGeneration, capturedGeneration, progress);
            progress.listReadEntries++;
            Resource fromGet = manager.getResource(entry.location())
                    .orElseThrow(() -> new IOException("getResource missing " + entry.location()));
            consume(fromGet, entry, cancelled, factoryGeneration, capturedGeneration, progress);
            progress.getReadEntries++;
            progress.verifiedAssets++;
        }
    }

    private static void consume(Resource resource, Expected expected, AtomicBoolean cancelled,
                                AtomicLong factoryGeneration, long capturedGeneration, Progress progress) throws IOException {
        if (!PACK_ID.equals(resource.sourcePackId())) throw new IOException("Unexpected source for " + expected.location());
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        long size = 0;
        byte[] buffer = new byte[32768];
        try (InputStream input = resource.open()) {
            while (true) {
                checkCancellation(cancelled, factoryGeneration, capturedGeneration);
                int read = input.read(buffer);
                if (read == -1) break;
                if (read == 0) throw new IOException("Resource stream made no progress");
                size = Math.addExact(size, read);
                progress.bytesRead = Math.addExact(progress.bytesRead, read);
                if (size > expected.size()) throw new IOException("Resource exceeds expected size " + expected.location());
                digest.update(buffer, 0, read);
            }
        }
        checkCancellation(cancelled, factoryGeneration, capturedGeneration);
        if (size != expected.size() || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
            throw new IOException("Resource content mismatch " + expected.location());
        }
        if (size > progress.maximumEntryBytes) {
            progress.maximumEntryBytes = size;
            progress.maximumEntry = expected.location().toString();
        }
    }

    private static void checkCancellation(AtomicBoolean cancelled, AtomicLong factoryGeneration,
                                          long capturedGeneration) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()
                || factoryGeneration.get() != capturedGeneration) throw new IOException("Q47 scan cancelled or generation changed");
    }
}
