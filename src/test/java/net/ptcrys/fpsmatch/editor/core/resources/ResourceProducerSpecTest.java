package net.ptcrys.fpsmatch.editor.core.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Frozen behaviour of {@link ResourceProducerSpec}: declaration budgets, defensive copying and the
 * verbatim carry-over of stored version strings.
 *
 * <p>Every input below is an ordinary caller-owned collection. No registry, storage or plugin takes
 * part: a producer declaration is a closed description, and the assertions pin that the description
 * neither normalizes the strings it was given nor keeps a reference to them. The three frozen budgets
 * are exercised on both sides of each boundary, using literal limits, so a change of a production
 * constant cannot silently move a boundary the module froze.</p>
 */
class ResourceProducerSpecTest {

    @Test
    void unorderedInputBecomesAStableLexicographicImmutableSet() {
        Set<String> forward = versions("v10", "v2", "V1", "1.0", "0.9");
        Set<String> reversed = versions("0.9", "1.0", "V1", "v2", "v10");

        ResourceProducerSpec spec = new ResourceProducerSpec(forward);
        ResourceProducerSpec sameContent = new ResourceProducerSpec(reversed);

        // Lexicographic order of the strings themselves: "v10" precedes "v2" and "V1" precedes
        // "v10", so no version scheme was parsed and no "newest entry" was selected.
        assertEquals(List.of("0.9", "1.0", "V1", "v10", "v2"),
                new ArrayList<>(spec.readableVersions()));
        assertEquals(spec, sameContent);
        assertEquals(spec.readableVersions(), sameContent.readableVersions());
        assertEquals(spec.hashCode(), sameContent.hashCode());
        assertThrows(UnsupportedOperationException.class,
                () -> spec.readableVersions().add("3.0"));
        assertThrows(UnsupportedOperationException.class,
                () -> spec.readableVersions().remove("1.0"));
        assertThrows(UnsupportedOperationException.class, () -> spec.readableVersions().clear());
    }

    @Test
    void versionsAreCarriedVerbatimWithoutTrimming() {
        ResourceProducerSpec spec = new ResourceProducerSpec(versions(" 1.0 ", "1.0", "\t2.0", "2.0"));

        assertEquals(Set.of(" 1.0 ", "1.0", "\t2.0", "2.0"), spec.readableVersions());
        assertTrue(spec.readableVersions().contains(" 1.0 "));
        assertFalse(spec.readableVersions().contains("1.0 "));
        assertFalse(spec.readableVersions().contains("2.0\t"));
        assertEquals(List.of("\t2.0", " 1.0 ", "1.0", "2.0"),
                new ArrayList<>(spec.readableVersions()),
                "a tab sorts before a space and before the numbers");
    }

    @Test
    void callerOwnedCollectionCannotChangeTheSpec() {
        Set<String> input = new HashSet<>(List.of("1", "2"));
        ResourceProducerSpec spec = new ResourceProducerSpec(input);

        input.add("3");
        input.remove("1");

        assertEquals(Set.of("1", "2"), spec.readableVersions());
        assertEquals(2, spec.readableVersions().size());
        // The declaration is readable after the caller's collection was fully cleared as well.
        input.clear();
        assertEquals(Set.of("1", "2"), spec.readableVersions());
    }

    @Test
    void nullSetNullEntryEmptySetAndBlankEntriesAreRejected() {
        assertThrows(NullPointerException.class, () -> new ResourceProducerSpec(null));
        assertThrows(NullPointerException.class, () -> new ResourceProducerSpec(versions("1", null)));

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new ResourceProducerSpec(Set.of()));
        assertTrue(empty.getMessage().contains("at least one"), empty.getMessage());

        for (String blank : List.of("", " ", "   ", "\t", "\n", " \t\n ")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ResourceProducerSpec(Set.of(blank)));
            assertTrue(failure.getMessage().contains("blank"),
                    "blank entry <" + blank + ">: " + failure.getMessage());
        }
        // A description that carries one usable entry next to a blank one is rejected as a whole:
        // there is no partial declaration and no "closest entry" fallback.
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceProducerSpec(versions("1.0", "  ")));
    }

    @Test
    void versionCountBudgetIsTwoHundredFiftySixAndCheckedFirst() {
        assertEquals(256, new ResourceProducerSpec(range(256)).readableVersions().size());

        Set<String> overCount = range(257);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ResourceProducerSpec(overCount));
        assertTrue(failure.getMessage().contains("257"), failure.getMessage());

        // The count is checked before any per-entry rule: an oversized declaration that also holds a
        // blank entry is reported as a count violation, not as a blank one.
        Set<String> oversizedWithBlank = range(257);
        oversizedWithBlank.add("   ");
        assertEquals(258, oversizedWithBlank.size());
        IllegalArgumentException fromCount = assertThrows(IllegalArgumentException.class,
                () -> new ResourceProducerSpec(oversizedWithBlank));
        assertTrue(fromCount.getMessage().contains("258"), fromCount.getMessage());
        assertFalse(fromCount.getMessage().contains("blank"), fromCount.getMessage());
    }

    @Test
    void oneVersionIsBoundToFourThousandNinetySixUtf16Units() {
        String ascii = "x".repeat(4096);
        assertEquals(Set.of(ascii), new ResourceProducerSpec(Set.of(ascii)).readableVersions());

        IllegalArgumentException overAscii = assertThrows(IllegalArgumentException.class,
                () -> new ResourceProducerSpec(Set.of("x".repeat(4097))));
        assertTrue(overAscii.getMessage().contains("4096"), overAscii.getMessage());

        // The bound counts UTF-16 units, not code points: 2048 astral pair instances fit exactly.
        String pairs = "\uD83D\uDE00".repeat(2048);
        assertEquals(4096, pairs.length());
        assertEquals(2048, pairs.codePointCount(0, pairs.length()));
        assertEquals(Set.of(pairs), new ResourceProducerSpec(Set.of(pairs)).readableVersions());

        IllegalArgumentException overPairs = assertThrows(IllegalArgumentException.class,
                () -> new ResourceProducerSpec(Set.of(pairs + "x")));
        assertTrue(overPairs.getMessage().contains("4096"), overPairs.getMessage());
    }

    @Test
    void cumulativeBudgetIsSixtyFiveThousandFiveHundredThirtySixUnits() {
        // Sixteen maximal entries sit exactly on the cumulative boundary.
        Set<String> atLimit = new LinkedHashSet<>();
        for (int index = 0; index < 16; index++) {
            String prefix = "v" + index;
            atLimit.add(prefix + "x".repeat(4096 - prefix.length()));
        }
        assertEquals(16, atLimit.size());
        assertEquals(65536L, atLimit.stream().mapToLong(String::length).sum());
        assertEquals(16, new ResourceProducerSpec(atLimit).readableVersions().size());

        Set<String> overLimit = new LinkedHashSet<>(atLimit);
        overLimit.add("y".repeat(4096));
        assertEquals(17, overLimit.size());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ResourceProducerSpec(overLimit));
        assertTrue(failure.getMessage().contains("65536"), failure.getMessage());

        // The cumulative budget is a length budget, not a count budget: many short entries fit.
        assertEquals(200, new ResourceProducerSpec(range(200)).readableVersions().size());
    }

    private static Set<String> versions(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    /** Distinct short entries {@code 1..count}, so only the count can cross a budget. */
    private static Set<String> range(int count) {
        Set<String> values = new LinkedHashSet<>();
        for (int index = 1; index <= count; index++) {
            values.add(String.valueOf(index));
        }
        assertEquals(count, values.size());
        return values;
    }
}
