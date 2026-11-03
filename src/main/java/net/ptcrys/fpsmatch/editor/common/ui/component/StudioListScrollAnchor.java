package net.ptcrys.fpsmatch.editor.common.ui.component;

import java.util.Objects;
import java.util.Optional;

/** Stable-row scroll anchoring for a changed fixed-height list projection. */
public final class StudioListScrollAnchor {

    private StudioListScrollAnchor() {}

    /**
     * Holds one immutable old source so deletion can search its original order. viewportOffset
     * is viewport.y - row.y and can be negative while a header above the first row is visible.
     * The caller keeps only the anchor needed for its next projection replacement.
     */
    public record Anchor(StudioRowSource<?> source, String rowKey, int oldIndex, int viewportOffset) {

        public Anchor {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(rowKey, "row key");
            Objects.checkIndex(oldIndex, source.size());
            if (!rowKey.equals(source.keyAt(oldIndex)))
                throw new IllegalArgumentException("anchor key differs from old row");
        }
    }

    /** Zero-area, offscreen and gap-only windows have no visible row to anchor. */
    public static Optional<Anchor> capture(StudioRowSource<?> source, StudioVirtualList.Window window) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(window, "window");
        if (source.size() != window.total()) throw new IllegalArgumentException("source size differs from window");
        if (window.visibleCount() == 0) return Optional.empty();
        int index = window.firstVisible();
        long offset = (long) window.viewport().y() - window.rowBounds(index).y();
        if (offset < Integer.MIN_VALUE || offset > Integer.MAX_VALUE)
            throw new IllegalArgumentException("anchor offset overflow");
        return Optional.of(new Anchor(source, source.keyAt(index), index, (int) offset));
    }

    /**
     * Returns the new pixel offset in the owning scroll body. listContentY is the list's origin
     * within that body, before scrolling, not a screen coordinate. The owning container clamps
     * the upper scroll limit. Retains the key if possible, otherwise uses the first surviving
     * old successor, then the nearest surviving old predecessor. No surviving key means zero.
     * This search happens only when replacing a projection, never on ordinary scrolling.
     */
    public static int restore(Anchor anchor, StudioRowSource<?> next, int listContentY,
                              int rowHeight, int gap) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(next, "next source");
        StudioVirtualList.contentHeight(next.size(), rowHeight, gap);
        int index = next.indexOf(anchor.rowKey());
        if (index < 0) {
            for (int old = anchor.oldIndex() + 1; old < anchor.source().size(); old++) {
                index = next.indexOf(anchor.source().keyAt(old));
                if (index >= 0) break;
            }
        }
        if (index < 0) {
            for (int old = anchor.oldIndex() - 1; old >= 0; old--) {
                index = next.indexOf(anchor.source().keyAt(old));
                if (index >= 0) break;
            }
        }
        if (index < 0) return 0;
        long offset = (long) listContentY + (long) index * (rowHeight + gap) + anchor.viewportOffset();
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("restored scroll offset overflow");
        return (int) Math.max(0, offset);
    }
}
