package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import net.ptcrys.fpsmatch.editor.common.client.viewport.LogicalRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.model.viewport.ScaleTransform;
import org.junit.jupiter.api.Test;

class WorldViewportDepthTest {
    @Test void nonzeroOriginAndNonuniformScaleShareOnePixelForRayAndReadback() {
        var layout = new ViewportLayout(1, new LogicalRect(10, 20, 100, 40),
                new ScaleTransform(2, 3, 7, 11, 500, 500));
        assertEquals(new WorldViewportDepth.Pixel(31.5, 80.5, 4, 110),
                WorldViewportDepth.pixel(layout, 12.4, 23.2).orElseThrow());
        assertEquals(new WorldViewportDepth.Pixel(27.5, 71.5, 0, 119),
                WorldViewportDepth.pixel(layout, 10, 20).orElseThrow());
        assertEquals(new WorldViewportDepth.Pixel(226.5, 190.5, 199, 0),
                WorldViewportDepth.pixel(layout, Math.nextDown(110.0), Math.nextDown(60.0)).orElseThrow());
        assertTrue(WorldViewportDepth.pixel(layout, 110, 20).isEmpty());
        assertTrue(WorldViewportDepth.pixel(layout, 10, 60).isEmpty());
        assertTrue(WorldViewportDepth.pixel(layout, Math.nextDown(10.0), 20).isEmpty());
    }

    @Test void clippedPhysicalAreaIsCheckedInAdditionToLogicalBounds() {
        var layout = new ViewportLayout(1, new LogicalRect(-3.25, -2.25, 10, 10),
                new ScaleTransform(1.5, 1.25, 2, 1, 10, 10));
        assertEquals(new WorldViewportDepth.Pixel(2.5, 1.5, 2, 8),
                WorldViewportDepth.pixel(layout, 0, 0).orElseThrow());
        assertEquals(new WorldViewportDepth.Pixel(.5, .5, 0, 9),
                WorldViewportDepth.pixel(layout, -1.3, -.7).orElseThrow());
        assertTrue(WorldViewportDepth.pixel(layout, -2, -1).isEmpty());
        assertTrue(WorldViewportDepth.pixel(layout, 6, 0).isEmpty());
        assertTrue(WorldViewportDepth.pixel(layout, 0, 8).isEmpty());
    }

    @Test void fractionalEdgePixelsUseTheRealFloorCeilFboExtent() {
        var layout = new ViewportLayout(1, new LogicalRect(10.75, 20.75, .5, .5),
                new ScaleTransform(1, 1, 0, 0, 100, 100));
        assertEquals(new WorldViewportDepth.Pixel(10.5, 20.5, 0, 1),
                WorldViewportDepth.pixel(layout, 10.75, 20.75).orElseThrow());
        assertEquals(new WorldViewportDepth.Pixel(11.5, 21.5, 1, 0),
                WorldViewportDepth.pixel(layout, 11.24, 21.24).orElseThrow());
        assertTrue(WorldViewportDepth.pixel(layout, 11.25, 21).isEmpty());
        assertTrue(WorldViewportDepth.pixel(layout, 10.5, 20.8).isEmpty(),
                "padding pixel coverage does not expand the logical input area");
    }

    @Test void nativeMeasuredBoundsAreNotReplacedByALossyLogicalRoundTrip() {
        var scale = new ScaleTransform(3, 1, 0, 0, 50, 50);
        var raw = new LogicalRect(Math.nextDown(1.0), 20, 1, 1);
        var logical = new LogicalRect(scale.toLogicalX(raw.left()), scale.toLogicalY(raw.top()),
                raw.width() / scale.xScale(), raw.height() / scale.yScale());
        var layout = new ViewportLayout(1, logical, scale, Optional.of(raw));
        assertEquals(0, layout.framebufferRect().x());
        assertEquals(new WorldViewportDepth.Pixel(1.5, 20.5, 1, 0),
                WorldViewportDepth.pixel(layout, logical.left(), logical.top()).orElseThrow());
    }

    @Test void invalidEmptyAndOverflowingPixelInputsFailClosed() {
        var scale = new ScaleTransform(2, 2, 0, 0, 100, 100);
        var normal = new ViewportLayout(1, new LogicalRect(0, 0, 10, 10), scale);
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertTrue(WorldViewportDepth.pixel(normal, invalid, 0).isEmpty());
            assertTrue(WorldViewportDepth.pixel(normal, 0, invalid).isEmpty());
        }
        assertTrue(WorldViewportDepth.pixel(new ViewportLayout(1, new LogicalRect(0, 0, 0, 10), scale), 0, 0).isEmpty());
        assertTrue(WorldViewportDepth.pixel(new ViewportLayout(1, new LogicalRect(100, 0, 10, 10), scale), 100, 0).isEmpty());
        assertTrue(WorldViewportDepth.pixel(new ViewportLayout(1, new LogicalRect(1e308, 0, 1e307, 1), scale), 1e308, 0).isEmpty());
    }

    @Test void fixedAttachmentsMatchTheirActualQuantizationAndFloatReadback() {
        for (int bits : new int[] {16, 24, 32}) {
            double levels = Math.scalb(1.0, bits) - 1;
            for (double expected : new double[] {.1, .4, .75, .999}) {
                float sampled = (float) (Math.rint(expected * levels) / levels);
                assertTrue(WorldViewportDepth.matches(expected, sampled, bits, false), "fixed bits=" + bits);
            }
        }
        assertTrue(WorldViewportDepth.matches(.500005, .5f, 16, false));
        assertFalse(WorldViewportDepth.matches(.500005, .5f, 24, false));
        assertFalse(WorldViewportDepth.matches(.99001, .99f, 24, false));
        assertFalse(WorldViewportDepth.matches(.98999, .99f, 24, false));
    }

    @Test void floatingAttachmentUsesLocalFloatSpacingWithoutAnExtraReadbackRound() {
        for (double expected : new double[] {0, .1, .75, .999, 1e-20})
            assertTrue(WorldViewportDepth.matches(expected, (float) expected, 32, true));
        double halfStep = (double) Math.ulp(.5f) * .5;
        assertTrue(WorldViewportDepth.matches(.5 + halfStep, .5f, 32, true));
        assertFalse(WorldViewportDepth.matches(.5 + halfStep * 1.5, .5f, 32, true));
        assertTrue(WorldViewportDepth.matches((double) Float.MIN_VALUE * .5, 0, 32, true));
        assertFalse(WorldViewportDepth.matches((double) Float.MIN_VALUE * .75, 0, 32, true));
        assertFalse(WorldViewportDepth.matches(1e-10, 0, 32, true), "no fixed epsilon near zero");
    }

    @Test void backgroundNonfiniteOutOfRangeAndUnknownDepthFormatsAreRejected() {
        assertTrue(WorldViewportDepth.matches(0, 0, 24, false));
        for (double invalid : new double[] {-1e-8, 1, 2, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(WorldViewportDepth.matches(invalid, .5f, 24, false));
        for (float invalid : new float[] {-1e-8f, 1, 2, Float.NaN, Float.POSITIVE_INFINITY})
            assertFalse(WorldViewportDepth.matches(.5, invalid, 24, false));
        assertFalse(WorldViewportDepth.matches(Math.nextDown(1.0), 1, 32, true));
        assertFalse(WorldViewportDepth.matches(.5, .5f, 0, false));
        assertFalse(WorldViewportDepth.matches(.5, .5f, 33, false));
        assertFalse(WorldViewportDepth.matches(.5, .5f, 24, true));
    }

}
