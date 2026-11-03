package net.ptcrys.fpsmatch.editor.core.packio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Behavioral tests of the frozen Q44 contract, using only valid public index values. */
final class PackOverlayTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void explicitPriorityControlsBothQueriesAndKeepsTheOriginalViewFixed() {
        PackEntry first = entry("assets/tacz/item.json", 4, HASH_A);
        PackEntry second = entry(first.path(), 7, HASH_B);
        PackOverlay.Layer high = new PackOverlay.Layer("z-high", index(HASH_A, first));
        PackOverlay.Layer low = new PackOverlay.Layer("a-low", index(HASH_B, second));
        ArrayList<PackOverlay.Layer> source = new ArrayList<>(List.of(high, low));
        PackOverlay original = new PackOverlay(source);
        source.clear();
        source.add(low);
        source.add(high);
        PackOverlay reversed = new PackOverlay(source);

        PackOverlay.Selection selected = original.resolve(first.path()).orElseThrow();
        assertEquals(List.of(
                new PackOverlay.Candidate("z-high", HASH_A, first),
                new PackOverlay.Candidate("a-low", HASH_B, second)), selected.candidates());
        assertSame(first, selected.winner().entry());
        assertTrue(selected.hasDifferentContent());
        assertEquals(List.of(selected), original.list("assets/tacz"));
        assertEquals(List.of(high, low), original.layers());

        PackOverlay.Selection changed = reversed.resolve(first.path()).orElseThrow();
        assertSame(second, changed.winner().entry());
        assertEquals(List.of(selected.candidates().get(1), selected.candidates().get(0)),
                changed.candidates());
        assertEquals(List.of(changed), reversed.list("assets/tacz"));
        assertEquals(selected, original.resolve(first.path()).orElseThrow());
        assertEquals(List.of(selected), original.list(""));
    }

    @Test
    void equalContentRetainsEveryArchiveAndOnlySizeOrContentDigestMarksADifference() {
        PackEntry content = entry("same", 3, HASH_A);
        PackOverlay overlay = new PackOverlay(List.of(
                new PackOverlay.Layer("first", index(HASH_A, content)),
                new PackOverlay.Layer("second", index(HASH_B, entry("same", 3, HASH_A)))));
        PackOverlay.Selection same = overlay.resolve("same").orElseThrow();
        assertEquals(2, same.candidates().size());
        assertEquals(List.of(HASH_A, HASH_B),
                same.candidates().stream().map(PackOverlay.Candidate::archiveSha256).toList());
        assertFalse(same.hasDifferentContent());
        assertFalse(selection("same", content).hasDifferentContent());
        assertTrue(selection("same", content, entry("same", 4, HASH_A)).hasDifferentContent());
        assertTrue(selection("same", content, entry("same", 3, HASH_B)).hasDifferentContent());
    }

    @Test
    void directoryQueriesRespectPrefixBoundariesCaseAndCompletePathSorting() {
        PackOverlay overlay = new PackOverlay(List.of(
                new PackOverlay.Layer("one", index(HASH_A,
                        entry("assets/tacz/z/deep", 0, HASH_A),
                        entry("assets/tacz/a", 0, HASH_A),
                        entry("assets/tacz2/other", 0, HASH_A),
                        entry("Z-root", 0, HASH_A))),
                new PackOverlay.Layer("two", index(HASH_B,
                        entry("assets/tacz", 0, HASH_A))),
                new PackOverlay.Layer("three", index(HASH_B,
                        entry("assets/Tacz/a", 0, HASH_B)))));
        assertEquals(List.of("Z-root", "assets/Tacz/a", "assets/tacz", "assets/tacz/a",
                "assets/tacz/z/deep", "assets/tacz2/other"), paths(overlay.list("")));
        assertEquals(List.of("assets/tacz/a", "assets/tacz/z/deep"),
                paths(overlay.list("assets/tacz")));
        assertEquals(List.of("assets/Tacz/a"), paths(overlay.list("assets/Tacz")));
        assertTrue(overlay.list("assets/TACZ").isEmpty());
        assertTrue(overlay.list("assets/tacz/a").isEmpty());
        assertTrue(overlay.list("missing").isEmpty());
        assertTrue(overlay.resolve("assets/TACZ/a").isEmpty());
        assertTrue(overlay.resolve("missing").isEmpty());
        for (PackOverlay.Selection value : overlay.list("")) {
            assertEquals(value, overlay.resolve(value.path()).orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a", "a/", "a//b", ".", "a/../b", "a\\b", "a:b", "NUL.txt",
            "a. ", "a.", "a\n", "\uD800"})
    void queriesAndSelectionsRejectInvalidPathsWithoutRepair(String path) {
        PackOverlay overlay = oneLayer();
        assertThrows(IllegalArgumentException.class, () -> overlay.resolve(path));
        assertThrows(IllegalArgumentException.class, () -> overlay.list(path));
        assertThrows(IllegalArgumentException.class,
                () -> new PackOverlay.Selection(path, List.of(candidate("one", entry("a", 0, HASH_A)))));
    }

    @Test
    void rootIsOnlyAcceptedForListingAndUtf8LengthIsValidated() {
        PackOverlay overlay = oneLayer();
        assertEquals(List.of("a"), paths(overlay.list("")));
        for (String path : List.of("", "x".repeat(1025), "中".repeat(342))) {
            assertThrows(IllegalArgumentException.class, () -> overlay.resolve(path));
            assertThrows(IllegalArgumentException.class,
                    () -> new PackOverlay.Selection(path, List.of(candidate("one", entry("a", 0, HASH_A)))));
            if (!path.isEmpty()) assertThrows(IllegalArgumentException.class, () -> overlay.list(path));
        }
        assertTrue(overlay.resolve("x".repeat(1024)).isEmpty());
        assertThrows(NullPointerException.class, () -> overlay.resolve(null));
        assertThrows(NullPointerException.class, () -> overlay.list(null));
    }

    @Test
    void publicValuesKeepReferencesToImmutableEntriesAndCopyEveryInputCollection() {
        PackEntry value = entry("a", 0, HASH_A);
        ArrayList<PackEntry> entries = new ArrayList<>(List.of(value));
        PackIndex index = new PackIndex(entries, 0, HASH_A);
        entries.clear();
        PackOverlay.Layer layer = new PackOverlay.Layer("one", index);
        PackOverlay.Candidate candidate = new PackOverlay.Candidate("one", HASH_A, value);
        ArrayList<PackOverlay.Candidate> candidates = new ArrayList<>(List.of(candidate));
        PackOverlay.Selection selected = new PackOverlay.Selection("a", candidates);
        candidates.clear();
        ArrayList<PackOverlay.Layer> layers = new ArrayList<>(List.of(layer));
        PackOverlay overlay = new PackOverlay(layers);
        layers.clear();

        assertSame(index, layer.index());
        assertSame(value, candidate.entry());
        assertSame(candidate, selected.winner());
        assertEquals(List.of(candidate), selected.candidates());
        assertEquals(List.of(layer), overlay.layers());
        assertThrows(UnsupportedOperationException.class, () -> index.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> selected.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> overlay.layers().clear());
        assertThrows(UnsupportedOperationException.class, () -> overlay.list("").clear());
        assertThrows(UnsupportedOperationException.class,
                () -> overlay.resolve("a").orElseThrow().candidates().clear());
    }

    @Test
    void publicConstructorsRejectNullEmptyAndInconsistentValues() {
        PackIndex index = index(HASH_A, entry("a", 0, HASH_A));
        PackOverlay.Layer layer = new PackOverlay.Layer("one", index);
        PackOverlay.Candidate candidate = candidate("one", index.entries().get(0));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Layer(null, index));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Layer("one", null));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Candidate(null, HASH_A, candidate.entry()));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Candidate("one", null, candidate.entry()));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Candidate("one", HASH_A, null));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Selection(null, List.of(candidate)));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Selection("a", null));
        assertThrows(NullPointerException.class, () -> new PackOverlay.Selection("a", Arrays.asList(candidate, null)));
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay.Selection("a", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay.Selection("other", List.of(candidate)));
        assertThrows(IllegalArgumentException.class,
                () -> new PackOverlay.Selection("a", List.of(candidate, candidate("one", candidate.entry()))));
        assertThrows(NullPointerException.class, () -> new PackOverlay(null));
        assertThrows(NullPointerException.class, () -> new PackOverlay(Arrays.asList(layer, null)));
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PackOverlay(List.of(layer, new PackOverlay.Layer("one", index))));
        for (String hash : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PackOverlay.Candidate("one", hash, candidate.entry()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "-first", "_first", ".first", "a/b", "a b", "a\n", "中"})
    void layerAndCandidateIdsUseTheSameExplicitGrammar(String id) {
        PackEntry value = entry("a", 0, HASH_A);
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay.Layer(id, index(HASH_A, value)));
        assertThrows(IllegalArgumentException.class, () -> candidate(id, value));
    }

    @Test
    void idLengthBoundaryAndAllowedPunctuationAreAccepted() {
        PackEntry value = entry("a", 0, HASH_A);
        for (String id : List.of("0", "a_.-9", "a".repeat(128))) {
            assertEquals(id, new PackOverlay.Layer(id, index(HASH_A, value)).id());
            assertEquals(id, candidate(id, value).layerId());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PackOverlay.Layer("a".repeat(129), index(HASH_A, value)));
        assertThrows(IllegalArgumentException.class, () -> candidate("a".repeat(129), value));
    }

    @Test
    void exactly32LayersAreAllowedAndOneMoreIsRejected() {
        PackIndex index = index(HASH_A, entry("a", 0, HASH_A));
        ArrayList<PackOverlay.Layer> layers = new ArrayList<>();
        for (int i = 0; i < 32; i++) layers.add(new PackOverlay.Layer("layer-" + i, index));
        PackOverlay boundary = new PackOverlay(layers);
        assertEquals(32, boundary.layers().size());
        assertEquals(32, boundary.resolve("a").orElseThrow().candidates().size());
        assertFalse(boundary.resolve("a").orElseThrow().hasDifferentContent());
        ArrayList<PackOverlay.Candidate> candidates = new ArrayList<>(
                boundary.resolve("a").orElseThrow().candidates());
        assertEquals(candidates, new PackOverlay.Selection("a", candidates).candidates());
        candidates.add(candidate("extra", index.entries().get(0)));
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay.Selection("a", candidates));
        List<PackOverlay.Candidate> oversized = new AbstractList<>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public PackOverlay.Candidate get(int position) {
                throw new AssertionError("Oversized selection must be rejected before reading elements");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay.Selection("a", oversized));
        layers.add(new PackOverlay.Layer("extra", index));
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay(layers));
    }

    @Test
    void entryBudgetCountsRepeatedOccurrencesEvenWhenDistinctPathsFit() {
        PackIndex index = generatedIndex(8192, 8, 0);
        ArrayList<PackOverlay.Layer> layers = new ArrayList<>(List.of(
                new PackOverlay.Layer("first", index), new PackOverlay.Layer("second", index)));
        PackOverlay boundary = new PackOverlay(layers);
        assertEquals(8192, boundary.list("").size());
        assertEquals(16384, boundary.list("").stream().mapToInt(s -> s.candidates().size()).sum());
        layers.add(new PackOverlay.Layer("extra", index(HASH_B, index.entries().get(0))));
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay(layers));
    }

    @Test
    void metadataBudgetAcceptsExactlyEightMiBAndRejectsOneMoreIdCharacter() {
        // 8192 occurrences: 256 paths of length 447, the rest 448; two 64-char ids.
        // Every single index is legal: 24 + 4096 * (44 + 448) - 128 = 2,015,128 bytes.
        PackIndex index = generatedIndex(4096, 448, 128);
        String first = "a".repeat(64);
        String second = "b".repeat(64);
        long perIndexMetadata = 24 + index.entries().stream().mapToLong(e -> 44L + e.path().length()).sum();
        assertEquals(2_015_128L, perIndexMetadata);
        long overlayMetadata = 2L * (128 + 2L * first.length())
                + 2 * index.entries().stream().mapToLong(e -> 128L + 2L * e.path().length()).sum();
        assertEquals(8L * 1024 * 1024, overlayMetadata);
        PackOverlay boundary = new PackOverlay(List.of(
                new PackOverlay.Layer(first, index), new PackOverlay.Layer(second, index)));
        assertEquals(4096, boundary.list("").size());
        assertEquals(2, boundary.resolve(index.entries().get(0).path()).orElseThrow().candidates().size());
        // The formula is always even; +2 bytes is the smallest expressible over-budget input.
        assertThrows(IllegalArgumentException.class, () -> new PackOverlay(List.of(
                new PackOverlay.Layer(first, index), new PackOverlay.Layer(second + "b", index))));
    }

    private static PackOverlay oneLayer() {
        return new PackOverlay(List.of(new PackOverlay.Layer("one", index(HASH_A, entry("a", 0, HASH_A)))));
    }

    private static PackEntry entry(String path, long size, String hash) {
        return new PackEntry(path, size, hash);
    }

    private static PackIndex index(String archiveHash, PackEntry... entries) {
        List<PackEntry> sorted = Arrays.stream(entries).sorted(Comparator.comparing(PackEntry::path)).toList();
        return new PackIndex(sorted, sorted.stream().mapToLong(PackEntry::size).sum(), archiveHash);
    }

    private static PackOverlay.Candidate candidate(String layerId, PackEntry entry) {
        return new PackOverlay.Candidate(layerId, HASH_A, entry);
    }

    private static PackOverlay.Selection selection(String path, PackEntry... entries) {
        ArrayList<PackOverlay.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) candidates.add(candidate("layer-" + i, entries[i]));
        return new PackOverlay.Selection(path, candidates);
    }

    private static List<String> paths(List<PackOverlay.Selection> values) {
        return values.stream().map(PackOverlay.Selection::path).toList();
    }

    private static PackIndex generatedIndex(int count, int length, int shorterCount) {
        ArrayList<PackEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String prefix = "e" + String.format(java.util.Locale.ROOT, "%04d", i);
            int target = length - (i < shorterCount ? 1 : 0);
            entries.add(entry(prefix + "x".repeat(target - prefix.length()), 0, HASH_A));
        }
        return new PackIndex(entries, 0, HASH_A);
    }
}
