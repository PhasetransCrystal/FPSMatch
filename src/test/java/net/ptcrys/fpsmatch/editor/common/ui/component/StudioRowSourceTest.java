package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioRowSource.FocusSlot;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioRowSourceTest {
    private static final FocusSlot ACTIVATE = new FocusSlot("activate", Role.TREE_ITEM);
    private static final FocusSlot EXPAND = new FocusSlot("expand", Role.BUTTON);

    @Test void immutableSnapshotCopiesRowsAndFocusSlotsAndKeepsStableKeyLookups() {
        var rows = new ArrayList<>(List.of("oak", "pine"));
        var slots = new ArrayList<>(List.of(EXPAND, ACTIVATE));
        var source = StudioRowSource.of(7, rows, value -> value, value -> slots);
        rows.clear(); slots.clear();
        assertEquals(7, source.revision());
        assertEquals(2, source.size());
        assertEquals("oak", source.rowAt(0));
        assertEquals("pine", source.keyAt(1));
        assertEquals(1, source.indexOf("pine"));
        assertEquals(-1, source.indexOf("absent"));
        assertEquals(-1, source.indexOf(null));
        assertEquals(List.of(EXPAND, ACTIVATE), source.focusSlots(0));
        assertThrows(UnsupportedOperationException.class, () -> source.focusSlots(0).clear());
        assertSame(source.focusIndex(), source.focusIndex());
    }

    @Test void focusIndexSkipsEmptyRowsAndRoundTripsEveryLogicalSlot() {
        var source = StudioRowSource.of(1, List.of("empty0", "branch", "empty1", "leaf", "empty2"),
                value -> value, value -> switch (value) {
                    case "branch" -> List.of(EXPAND, ACTIVATE);
                    case "leaf" -> List.of(ACTIVATE);
                    default -> List.of();
                });
        var index = source.focusIndex();
        assertEquals(3, index.size());
        assertEquals(new StudioRowSource.FocusTarget(1, "branch", EXPAND), index.at(0));
        assertEquals(new StudioRowSource.FocusTarget(1, "branch", ACTIVATE), index.at(1));
        assertEquals(new StudioRowSource.FocusTarget(3, "leaf", ACTIVATE), index.at(2));
        for (int ordinal = 0; ordinal < index.size(); ordinal++) {
            var target = index.at(ordinal);
            assertEquals(ordinal, index.indexOf(target.rowKey(), target.slot().key()));
        }
        assertEquals(-1, index.indexOf("leaf", "expand"));
        assertEquals(-1, index.indexOf("absent", "activate"));
        assertEquals(-1, index.indexOf(null, "activate"));
        assertEquals(-1, index.indexOf("leaf", null));
    }

    @Test void reorderingAndFilteringRelocateByIdentityInsteadOfOldIndex() {
        var before = StudioRowSource.of(10, List.of("a", "b", "c"), value -> value, value -> List.of(ACTIVATE));
        var after = StudioRowSource.of(11, List.of("c", "a"), value -> value, value -> List.of(ACTIVATE));
        var requested = before.focusIndex().at(2);
        assertEquals(0, after.indexOf(requested.rowKey()));
        assertEquals(0, after.focusIndex().indexOf(requested.rowKey(), requested.slot().key()));
        assertEquals(-1, after.focusIndex().indexOf("b", "activate"));
        assertEquals(2, before.indexOf("c"));
        assertEquals(3, before.size());
    }

    @Test void tenThousandLogicalRowsBuildIndexesOnceWithoutNavigationReevaluation() {
        var keys = new AtomicInteger();
        var slots = new AtomicInteger();
        var source = StudioRowSource.of(3, IntStream.range(0, 10_000).boxed().toList(),
                value -> { keys.incrementAndGet(); return "row-" + value; },
                value -> { slots.incrementAndGet(); return value % 2 == 0 ? List.of(EXPAND, ACTIVATE) : List.of(ACTIVATE); });
        assertEquals(10_000, keys.get());
        assertEquals(10_000, slots.get());
        assertEquals(15_000, source.focusIndex().size());
        for (int ordinal = source.focusIndex().size() - 1; ordinal >= 0; ordinal--) {
            var target = source.focusIndex().at(ordinal);
            assertEquals(ordinal, source.focusIndex().indexOf(target.rowKey(), target.slot().key()));
            assertEquals(target.rowIndex(), source.indexOf(target.rowKey()));
        }
        assertEquals(10_000, keys.get());
        assertEquals(10_000, slots.get());
        // This is a generic UI source. It does not change any domain's 8192-object ceiling.
    }

    @Test void emptyAndNonNavigableSourcesHaveNoArtificialFocusTargets() {
        var empty = StudioRowSource.<String>of(0, List.of(), value -> value, value -> List.of());
        var labels = StudioRowSource.of(1, List.of("a", "b"), value -> value, value -> List.of());
        assertEquals(0, empty.size());
        assertEquals(0, empty.focusIndex().size());
        assertEquals(0, labels.focusIndex().size());
        assertThrows(IndexOutOfBoundsException.class, () -> empty.focusIndex().at(0));
        assertThrows(IndexOutOfBoundsException.class, () -> labels.focusIndex().at(0));
        assertThrows(IndexOutOfBoundsException.class, () -> labels.rowAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> labels.keyAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> labels.focusSlots(2));
    }

    @Test void duplicateOrInvalidIdentitiesFailAtSourceConstruction() {
        assertThrows(IllegalArgumentException.class, () -> StudioRowSource.of(-1, List.of("a"), value -> value, value -> List.of()));
        assertThrows(IllegalArgumentException.class, () -> StudioRowSource.of(0, List.of("a", "a"), value -> value, value -> List.of()));
        assertThrows(IllegalArgumentException.class, () -> StudioRowSource.of(0, List.of("a"), value -> " ", value -> List.of()));
        assertThrows(IllegalArgumentException.class, () -> StudioRowSource.of(0, List.of("a"), value -> value, value -> List.of(ACTIVATE, ACTIVATE)));
        assertThrows(NullPointerException.class, () -> StudioRowSource.of(0, List.of("a"), value -> null, value -> List.of()));
        assertThrows(NullPointerException.class, () -> StudioRowSource.of(0, List.of("a"), value -> value, value -> null));
        assertThrows(IllegalArgumentException.class, () -> new FocusSlot(" ", Role.BUTTON));
        assertThrows(IllegalArgumentException.class, () -> new FocusSlot("label", Role.LABEL));
        assertThrows(IllegalArgumentException.class, () -> new FocusSlot("panel", Role.PANEL));
        var source = StudioRowSource.of(0, List.of("a"), value -> value, value -> List.of(ACTIVATE));
        assertThrows(IndexOutOfBoundsException.class, () -> source.focusIndex().at(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> source.focusIndex().at(1));
    }
}
