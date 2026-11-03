package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable logical rows, independent of mounted controls. A new data, ordering or focus-slot
 * projection must have a new revision; scrolling does not create another source. Implementations
 * provide constant-time row/key lookups and retain their focus index for the life of the snapshot.
 * Domain collection limits remain the responsibility of the caller.
 */
public interface StudioRowSource<R> {

    long revision();

    int size();

    R rowAt(int index);

    String keyAt(int index);

    /** Returns -1 when the stable key does not occur in this projection. */
    int indexOf(String key);

    /** Logical navigation order within a row. Disabled explanatory targets remain in this list. */
    List<FocusSlot> focusSlots(int rowIndex);

    FocusIndex focusIndex();

    /** No node, callback or geometry is retained for an unmounted focus slot. */
    record FocusSlot(String key, Role role) {

        public FocusSlot {
            requireKey(key);
            Objects.requireNonNull(role, "role");
            if (role == Role.LABEL || role == Role.PANEL)
                throw new IllegalArgumentException("non-interactive focus role");
        }
    }

    record FocusTarget(int rowIndex, String rowKey, FocusSlot slot) {

        public FocusTarget {
            if (rowIndex < 0) throw new IllegalArgumentException("negative row index");
            requireKey(rowKey);
            Objects.requireNonNull(slot, "slot");
        }
    }

    /**
     * Builds the key and focus indexes once. Rows must themselves be immutable values; the row
     * list, keys and each focus-slot list are copied. Suppliers run only during construction.
     */
    static <R> StudioRowSource<R> of(long revision, List<? extends R> rows,
                                     Function<? super R, String> key,
                                     Function<? super R, ? extends List<FocusSlot>> slots) {
        return new Snapshot<>(revision, rows, key, slots);
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("empty stable key");
        return key;
    }

    /**
     * Compact ordinal index, built once per source. at() is O(log rows); indexOf() is O(1).
     * Zero-slot rows are supported without walking them during navigation. Targets are created
     * only on lookup, never for every logical row on a frame publication.
     */
    final class FocusIndex {

        private record SlotKey(String row, String slot) {}

        private final List<String> keys;
        private final List<List<FocusSlot>> slots;
        private final int[] prefix;
        private final Map<SlotKey, Integer> indices;

        private FocusIndex(List<String> keys, List<List<FocusSlot>> slots) {
            this.keys = keys;
            this.slots = slots;
            prefix = new int[keys.size() + 1];
            var positions = new HashMap<SlotKey, Integer>();
            for (int row = 0; row < keys.size(); row++) {
                long next = (long) prefix[row] + slots.get(row).size();
                if (next > Integer.MAX_VALUE) throw new IllegalArgumentException("too many focus slots");
                prefix[row + 1] = (int) next;
                for (int slot = 0; slot < slots.get(row).size(); slot++) {
                    var identity = new SlotKey(keys.get(row), slots.get(row).get(slot).key());
                    if (positions.putIfAbsent(identity, prefix[row] + slot) != null)
                        throw new IllegalArgumentException("duplicate focus slot: " + identity);
                }
            }
            indices = Map.copyOf(positions);
        }

        public int size() {
            return prefix[prefix.length - 1];
        }

        public FocusTarget at(int flatIndex) {
            Objects.checkIndex(flatIndex, size());
            int low = 0, high = prefix.length;
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (prefix[middle] <= flatIndex) low = middle + 1;
                else high = middle;
            }
            int row = low - 1;
            return new FocusTarget(row, keys.get(row), slots.get(row).get(flatIndex - prefix[row]));
        }

        /** Returns -1 if the row or slot is absent, including after filtering. */
        public int indexOf(String rowKey, String slotKey) {
            return rowKey == null || slotKey == null ? -1 : indices.getOrDefault(new SlotKey(rowKey, slotKey), -1);
        }
    }

    /** Factory-owned implementation; payload objects follow the immutability contract above. */
    final class Snapshot<R> implements StudioRowSource<R> {

        private final long revision;
        private final List<R> rows;
        private final List<String> keys;
        private final Map<String, Integer> indices;
        private final List<List<FocusSlot>> slots;
        private final FocusIndex focusIndex;

        private Snapshot(long revision, List<? extends R> rows, Function<? super R, String> key,
                         Function<? super R, ? extends List<FocusSlot>> slots) {
            if (revision < 0) throw new IllegalArgumentException("negative source revision");
            this.revision = revision;
            this.rows = List.copyOf(rows);
            Objects.requireNonNull(key, "key supplier");
            Objects.requireNonNull(slots, "slot supplier");
            var rowKeys = new ArrayList<String>(rows.size());
            var rowSlots = new ArrayList<List<FocusSlot>>(rows.size());
            var positions = new HashMap<String, Integer>();
            for (int index = 0; index < this.rows.size(); index++) {
                R row = this.rows.get(index);
                String identity = requireKey(key.apply(row));
                if (positions.putIfAbsent(identity, index) != null)
                    throw new IllegalArgumentException("duplicate row key: " + identity);
                rowKeys.add(identity);
                rowSlots.add(List.copyOf(slots.apply(row)));
            }
            this.keys = List.copyOf(rowKeys);
            this.slots = List.copyOf(rowSlots);
            indices = Map.copyOf(positions);
            focusIndex = new FocusIndex(this.keys, this.slots);
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public int size() {
            return rows.size();
        }

        @Override
        public R rowAt(int index) {
            return rows.get(index);
        }

        @Override
        public String keyAt(int index) {
            return keys.get(index);
        }

        @Override
        public int indexOf(String key) {
            return key == null ? -1 : indices.getOrDefault(key, -1);
        }

        @Override
        public List<FocusSlot> focusSlots(int rowIndex) {
            return slots.get(rowIndex);
        }

        @Override
        public FocusIndex focusIndex() {
            return focusIndex;
        }
    }
}
