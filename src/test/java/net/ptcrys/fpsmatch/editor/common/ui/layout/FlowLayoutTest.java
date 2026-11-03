package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;
import static net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class FlowLayoutTest {
    private static Node box(String id, int width, int height) {
        return leaf(id, Style.content(), ignored -> new Size(width, height));
    }

    @Test void nestedShellAllocatesTheRemainingViewportWithoutIndependentCoordinateArithmetic() {
        var scene = leaf("scene", Style.fill(), ignored -> new Size(0, 0));
        var rail = leaf("rail", Style.fill().width(Length.fixed(20)), ignored -> new Size(0, 0));
        var body = row("body", Style.fill().gap(3), List.of(rail, scene));
        var root = column("shell", Style.fill(), List.of(
                leaf("header", Style.defaults().height(Length.fixed(12)), ignored -> new Size(0, 0)), body,
                leaf("footer", Style.defaults().height(Length.fixed(8)), ignored -> new Size(0, 0))));
        var result = layout(root, new Bounds(7, 9, 100, 70));
        assertEquals(new Bounds(7, 9, 100, 12), result.require("header").bounds());
        assertEquals(new Bounds(7, 21, 100, 50), result.require("body").bounds());
        assertEquals(new Bounds(30, 21, 77, 50), result.require("scene").bounds());
        assertEquals(new Bounds(7, 71, 100, 8), result.require("footer").bounds());
        assertEquals(0, result.root().overflowWidth());
        assertEquals(0, result.root().overflowHeight());
    }

    @Test void cappedStretchRedistributesEveryPixelInStableOrder() {
        var a = leaf("a", Style.defaults().width(Length.stretch().limits(10, 20)), ignored -> new Size(0, 10));
        var b = leaf("b", Style.defaults().width(Length.stretch().limits(10, 100)), ignored -> new Size(0, 10));
        var root = row("row", Style.defaults().gap(2), List.of(a, b, box("fixed", 11, 10)));
        var result = layoutContent(root, 0, 0, 80);
        assertEquals(new Bounds(0, 0, 20, 10), result.require("a").bounds());
        assertEquals(new Bounds(22, 0, 45, 10), result.require("b").bounds());
        assertEquals(new Bounds(69, 0, 11, 10), result.require("fixed").bounds());
    }

    @Test void insufficientSpacePreservesFixedAndMinimumSizesAndReportsOverflow() {
        var root = row("root", Style.defaults().gap(5), List.of(box("fixed", 50, 10),
                leaf("min", Style.defaults().width(Length.stretch().limits(30, 100)), ignored -> new Size(0, 10))));
        var result = layout(root, new Bounds(0, 0, 60, 8));
        assertEquals(50, result.require("fixed").bounds().width());
        assertEquals(30, result.require("min").bounds().width());
        assertEquals(25, result.root().overflowWidth());
        assertEquals(2, result.root().overflowHeight());
    }

    @Test void textHeightIsMeasuredAtTheFinalRowWidthAfterSiblingAllocation() {
        var widths = new ArrayList<Integer>();
        var text = leaf("text", Style.defaults(), limits -> {
            widths.add(limits.maxWidth());
            return wrappedText(limits, 100);
        });
        var root = row("row", Style.defaults().gap(3), List.of(box("icon", 20, 10), text));
        var result = layoutContent(root, 10, 20, 63);
        assertEquals(new Bounds(33, 20, 40, 30), result.require("text").bounds());
        assertEquals(30, result.root().bounds().height());
        assertTrue(widths.contains(40), "the leaf must receive its allocated width, not the full row");
        assertEquals(0, result.root().overflowHeight());
    }

    @Test void gridMeasuresEachRowAtItsTrackWidthAndRetainsTheEmptyFinalCell() {
        var root = grid("grid", Style.defaults().padding(Insets.all(2)).gap(3).columns(2), List.of(
                text("long", 100), text("short", 20), text("last", 70)));
        var result = layoutContent(root, 5, 8, 103);
        assertEquals(new Bounds(7, 10, 48, 30), result.require("long").bounds());
        assertEquals(new Bounds(58, 10, 48, 10), result.require("short").bounds());
        assertEquals(new Bounds(7, 43, 48, 20), result.require("last").bounds());
        assertEquals(57, result.root().bounds().height());
    }

    @Test void responsiveFieldsWrapAtTheirDeclaredMinimumAndShareTheirLine() {
        var fields = new ArrayList<Node>();
        for (String id : List.of("alpha", "beta", "gamma")) fields.add(leaf(id,
                Style.defaults().width(Length.stretch().limits(65, 500)), ignored -> new Size(0, 10)));
        var root = wrap("fields", Style.defaults().gap(5), fields);
        var wide = layoutContent(root, 0, 0, 140);
        assertEquals(new Bounds(0, 0, 68, 10), wide.require("alpha").bounds());
        assertEquals(new Bounds(73, 0, 67, 10), wide.require("beta").bounds());
        assertEquals(new Bounds(0, 15, 140, 10), wide.require("gamma").bounds());
        var narrow = layoutContent(root, 0, 0, 134);
        assertEquals(40, narrow.root().bounds().height());
        assertEquals(new Bounds(0, 15, 134, 10), narrow.require("beta").bounds());
        var tiny = layoutContent(root, 0, 0, 10);
        assertEquals(65, tiny.require("alpha").bounds().width());
        assertEquals(55, tiny.root().overflowWidth());
    }

    @Test void wrappingContentButtonsUsesMeasuredWidthAndTallestLineWithoutTrailingGap() {
        var root = wrap("actions", Style.defaults().gap(5), List.of(box("a", 40, 10), box("b", 30, 20), box("c", 50, 10)));
        var result = layoutContent(root, 0, 0, 80);
        assertEquals(new Bounds(45, 0, 30, 20), result.require("b").bounds());
        assertEquals(new Bounds(0, 25, 50, 10), result.require("c").bounds());
        assertEquals(35, result.root().bounds().height());
    }

    @Test void contentContainersUseIntrinsicChildrenRatherThanAnUnrelatedAvailableWidth() {
        var child = leaf("label", Style.defaults(), ignored -> new Size(18, 10));
        var root = column("column", Style.content().padding(Insets.symmetric(3, 2)), List.of(child));
        assertEquals(new Size(24, 14), measure(root, new Constraints(400, 200)));
        assertEquals(new Size(24, 14), measure(root, new Constraints(Constraints.UNBOUNDED, Constraints.UNBOUNDED)));
    }

    @Test void contentHeightMaximumKeepsNaturalDescendantOverflowVisibleToTheHost() {
        var group = column("group", Style.defaults().height(Length.content().limits(0, 15)), List.of(box("child", 10, 40)));
        var root = column("root", Style.defaults().padding(Insets.all(2)), List.of(group));
        var result = layoutContent(root, 0, 0, 50);
        assertEquals(19, result.root().bounds().height());
        assertEquals(25, result.require("group").overflowHeight());
        assertEquals(25, result.root().overflowHeight());
        assertEquals(40, result.require("child").bounds().height());
    }

    @Test void emptyAndZeroWidthContainersKeepOnlyDeclaredPaddingAndDoNotInventGaps() {
        var empty = column("empty", Style.defaults().padding(Insets.all(3)).gap(40), List.of());
        var result = layoutContent(empty, -10, -20, 0);
        assertEquals(new Bounds(-10, -20, 0, 6), result.root().bounds());
        assertEquals(6, result.root().overflowWidth());
        assertEquals(0, result.root().overflowHeight());
        assertEquals(new Bounds(-7, -17, 0, 0), result.root().contentBounds());
    }

    @Test void fractionalGridRemaindersRemainInsideTheAssignedWidthAcrossResizes() {
        var root = grid("grid", Style.defaults().gap(2).columns(3), List.of(text("a", 30), text("b", 30), text("c", 30)));
        for (int width = 4; width < 151; width++) {
            var result = layoutContent(root, 9, 4, width);
            var a = result.require("a").bounds(); var b = result.require("b").bounds(); var c = result.require("c").bounds();
            assertEquals(width, a.width() + b.width() + c.width() + 4);
            assertEquals(9 + width, c.x() + c.width());
            assertEquals(a.x() + a.width() + 2, b.x());
            assertEquals(b.x() + b.width() + 2, c.x());
            assertEquals(0, result.root().overflowWidth());
        }
    }

    @Test void publishedGeometryIsDeeplyImmutableAndReadingItNeverRemeasures() {
        var calls = new AtomicInteger();
        var children = new ArrayList<Node>();
        children.add(leaf("leaf", Style.defaults(), ignored -> { calls.incrementAndGet(); return new Size(7, 9); }));
        var root = column("root", Style.defaults(), children); children.clear();
        var result = layoutContent(root, 1, 2, 50); int measured = calls.get();
        assertSame(result.root().children().get(0), result.require("leaf"));
        assertThrows(UnsupportedOperationException.class, () -> result.byId().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.root().children().clear());
        assertThrows(UnsupportedOperationException.class, () -> root.children().clear());
        assertEquals(new Bounds(1, 2, 50, 9), result.require("leaf").bounds());
        assertEquals(measured, calls.get());
    }

    @Test void duplicateSemanticIdsAreRejectedBeforeAnyLeafIsMeasured() {
        var calls = new AtomicInteger();
        var a = leaf("same", Style.defaults(), ignored -> { calls.incrementAndGet(); return new Size(0, 0); });
        var root = column("root", Style.defaults(), List.of(a, box("same", 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> layoutContent(root, 0, 0, 30));
        assertEquals(0, calls.get());
    }

    @Test void invalidInputsAndUnrepresentableCoordinatesFailInsteadOfWrapping() {
        assertThrows(IllegalArgumentException.class, () -> new Constraints(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Length.content().limits(3, 2));
        assertThrows(IllegalArgumentException.class, () -> Insets.all(-1));
        assertThrows(IllegalArgumentException.class, () -> Style.defaults().columns(0));
        assertThrows(IllegalArgumentException.class, () -> Style.defaults().gap(-1));
        assertThrows(IllegalArgumentException.class, () -> leaf(" ", Style.defaults(), ignored -> new Size(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> layout(box("leaf", 10, 10), new Bounds(Integer.MAX_VALUE, 0, 1, 1)));
        var huge = column("huge", Style.defaults(), List.of(box("one", 0, Integer.MAX_VALUE), box("two", 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> layoutContent(huge, 0, 0, 10));
    }

    @Test void rowEndAlignmentUsesTheContentBoxAndPublishesTheSameHitBounds() {
        var root = row("footer", Style.defaults().padding(Insets.symmetric(5, 2)).gap(4).mainAlign(MainAlign.END),
                List.of(box("cancel", 20, 10), box("save", 30, 10)));
        var result = layoutContent(root, 10, 20, 100);
        assertEquals(new Bounds(51, 22, 20, 10), result.require("cancel").bounds());
        assertEquals(new Bounds(75, 22, 30, 10), result.require("save").bounds());
        assertEquals(result.root().contentBounds().x() + result.root().contentBounds().width(),
                result.require("save").bounds().x() + result.require("save").bounds().width());
        assertTrue(result.require("save").bounds().contains(75, 22));
        assertFalse(result.require("save").bounds().contains(105, 22));
        assertEquals(0, result.root().overflowWidth());
    }

    @Test void wrapEndAlignmentIndependentlyAnchorsEveryFooterLineAfterResize() {
        var root = wrap("footer", Style.defaults().gap(5).mainAlign(MainAlign.END),
                List.of(box("back", 35, 10), box("discard", 30, 20), box("apply", 40, 10)));
        var wide = layoutContent(root, 0, 0, 130);
        assertEquals(new Bounds(15, 0, 35, 10), wide.require("back").bounds());
        assertEquals(130, wide.require("apply").bounds().x() + 40);
        var narrow = layoutContent(root, 0, 0, 80);
        assertEquals(new Bounds(10, 0, 35, 10), narrow.require("back").bounds());
        assertEquals(new Bounds(50, 0, 30, 20), narrow.require("discard").bounds());
        assertEquals(new Bounds(40, 25, 40, 10), narrow.require("apply").bounds());
        assertEquals(35, narrow.root().bounds().height());
        assertEquals(0, narrow.root().overflowWidth());
    }

    @Test void centerAlignmentUsesFloorRemaindersAndDoesNotInflateIntrinsicMeasurement() {
        var style = Style.content().gap(3).mainAlign(MainAlign.CENTER);
        var root = row("row", style, List.of(box("first", 10, 7), box("last", 20, 9)));
        assertEquals(new Size(33, 9), measure(root, new Constraints(100, 100)));
        var placed = layout(root, new Bounds(0, 0, 100, 20));
        assertEquals(new Bounds(33, 0, 10, 7), placed.require("first").bounds());
        assertEquals(new Bounds(46, 0, 20, 9), placed.require("last").bounds());
        assertEquals(MainAlign.CENTER, style.width(Length.stretch()).height(Length.content())
                .padding(Insets.ZERO).gap(3).columns(2).mainAlign());
        assertEquals(MainAlign.START, new Style(Length.content(), Length.content(), Insets.ZERO, 0, 1).mainAlign());
    }

    @Test void cappedStretchIsAlignedOnlyAfterAllocationAndOverflowNeverGetsANegativeOrigin() {
        var capped = leaf("capped", Style.defaults().width(Length.stretch().limits(10, 20)), ignored -> new Size(0, 10));
        var root = row("row", Style.defaults().gap(5).mainAlign(MainAlign.END), List.of(capped, box("fixed", 30, 10)));
        var spacious = layoutContent(root, 0, 0, 100);
        assertEquals(new Bounds(45, 0, 20, 10), spacious.require("capped").bounds());
        assertEquals(new Bounds(70, 0, 30, 10), spacious.require("fixed").bounds());
        var small = layoutContent(root, 0, 0, 20);
        assertEquals(new Bounds(0, 0, 10, 10), small.require("capped").bounds());
        assertEquals(new Bounds(15, 0, 30, 10), small.require("fixed").bounds());
        assertEquals(25, small.root().overflowWidth());
    }

    @Test void columnAlignmentMovesOnlyTheMainAxisWithinAFixedHostSlot() {
        var root = column("column", Style.defaults().padding(Insets.all(2)).gap(3).mainAlign(MainAlign.END),
                List.of(box("first", 20, 10), box("second", 20, 15)));
        var placed = layout(root, new Bounds(4, 6, 50, 60));
        assertEquals(new Bounds(6, 36, 20, 10), placed.require("first").bounds());
        assertEquals(new Bounds(6, 49, 20, 15), placed.require("second").bounds());
        assertEquals(new Size(50, 32), measure(root, new Constraints(50, 60)));
    }

    private static Node text(String id, int advance) {
        return leaf(id, Style.defaults(), limits -> wrappedText(limits, advance));
    }

    /** A deterministic font fixture: each glyph has one pixel advance and lines are ten high. */
    private static Size wrappedText(Constraints limits, int advance) {
        int width = Math.min(advance, limits.maxWidth());
        int lines = width == 0 ? advance : (advance + width - 1) / width;
        return new Size(width, lines * 10);
    }
}
