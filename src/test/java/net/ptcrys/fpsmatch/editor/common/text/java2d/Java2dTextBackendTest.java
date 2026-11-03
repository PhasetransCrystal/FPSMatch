package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;

/** Behaviour tests for {@link Java2dTextBackend} and {@link TextLine}. */
class Java2dTextBackendTest {

    /** Padding of the independent oracle canvas, far larger than the raster's own padding. */
    private static final int CANVAS_PADDING = 32;

    @Test
    void emptyTextHasZeroAdvanceAndUsableQueries() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("empty"), "Dialog");
        TextLine line = backend.layout("", List.of(face), 12f, 1f);
        assertEquals("", line.text());
        assertEquals(0f, line.advance());
        assertTrue(line.usedFonts().isEmpty(), "empty line uses no face");
        assertEquals(0, line.hitTest(0f));
        assertEquals(0, line.hitTest(1000f));
        assertEquals(0f, line.caretX(0));
        Raster raster = line.rasterize();
        assertEquals(1, raster.width());
        assertEquals(1, raster.height());
        assertEquals(1, raster.argb().length);
    }

    @Test
    void embeddedFontIsRegisteredWithGeneratedFaceIdentity() {
        Java2dTextBackend backend = new Java2dTextBackend();
        List<FontFaceId> faces = backend.register(TestFonts.id("latin"), TestFonts.latin());
        assertFalse(faces.isEmpty(), "font file contains at least one face");
        assertEquals(TestFonts.id("latin/face-0"), faces.get(0).id());
        for (int index = 0; index < faces.size(); index++) {
            assertEquals(TestFonts.id("latin/face-" + index), faces.get(index).id());
        }
        assertThrows(UnsupportedOperationException.class, () -> faces.add(faces.get(0)));
    }

    @Test
    void registeredFontKeepsWorkingAfterSourceBytesAreMutated() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] source = TestFonts.latin();
        FontFaceId face = backend.register(TestFonts.id("mutable"), source).get(0);
        float before = backend.layout("Mu", List.of(face), 16f, 1f).advance();
        java.util.Arrays.fill(source, (byte) 0);
        float after = backend.layout("Mu", List.of(face), 16f, 1f).advance();
        assertEquals(before, after, "registered content is a private copy of the input bytes");
        assertTrue(after > 0f);
    }

    @Test
    void logicalRegistrationAcceptsOnlyJdkLogicalFamilies() {
        Java2dTextBackend backend = new Java2dTextBackend();
        for (String name : List.of("Dialog", "SansSerif", "Serif", "Monospaced", "DialogInput")) {
            FontFaceId face = backend.registerLogical(EditorId.parse("test:logical-" + name.toLowerCase(Locale.ROOT)), name);
            assertTrue(backend.layout("x", List.of(face), 10f, 1f).advance() > 0f);
        }
        assertThrows(IllegalArgumentException.class,
                () -> backend.registerLogical(TestFonts.id("bogus"), "Comic Sans MS"));
        assertThrows(IllegalArgumentException.class,
                () -> backend.registerLogical(TestFonts.id("bogus"), "dialog"));
        assertThrows(IllegalArgumentException.class,
                () -> backend.registerLogical(TestFonts.id("bogus2"), "Sans Serif"));
    }

    @Test
    void invalidFontBytesLeaveNoResidue() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId good = backend.registerLogical(TestFonts.id("good"), "Dialog");
        byte[] garbage = new byte[512];
        for (int index = 0; index < garbage.length; index++) {
            garbage[index] = (byte) (index * 31);
        }
        assertThrows(IllegalArgumentException.class, () -> backend.register(TestFonts.id("bad"), garbage));
        assertThrows(IllegalArgumentException.class, () -> backend.register(TestFonts.id("bad"), new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> backend.register(TestFonts.id("bad"), new byte[Java2dTextBackend.MAX_FONT_BYTES + 1]));
        assertThrows(NullPointerException.class, () -> backend.register(TestFonts.id("bad"), null));
        // The failed registrations must not have consumed the identity or corrupted the registry.
        FontFaceId retry = backend.register(TestFonts.id("bad"), TestFonts.latin()).get(0);
        assertEquals(TestFonts.id("bad/face-0"), retry.id());
        assertEquals(good, backend.layout("ok", List.of(good), 12f, 1f).usedFonts().get(0));
    }

    @Test
    void conflictingRegistrationIsRejectedAtomically() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] bytes = TestFonts.latin();
        List<FontFaceId> first = backend.register(TestFonts.id("dup"), bytes);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> backend.register(TestFonts.id("dup"), bytes));
        assertTrue(failure.getMessage().contains("dup"), failure.getMessage());
        assertEquals(first.size(), backend.layout("x", List.of(first.get(0)), 12f, 1f).usedFonts().size());
        // A later, non-conflicting registration still succeeds.
        assertFalse(backend.register(TestFonts.id("dup2"), bytes).isEmpty());
    }

    @Test
    void duplicateLogicalIdentityIsRejected() {
        Java2dTextBackend backend = new Java2dTextBackend();
        backend.registerLogical(TestFonts.id("once"), "Dialog");
        assertThrows(IllegalArgumentException.class,
                () -> backend.registerLogical(TestFonts.id("once"), "Dialog"));
        assertThrows(IllegalArgumentException.class,
                () -> backend.registerLogical(TestFonts.id("once"), "Serif"));
    }

    @Test
    void fallbackSelectsFirstFaceThatCoversTheCluster() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        byte[] wideBytes = TestFonts.wide();
        FontFaceId latinFace = backend.register(TestFonts.id("latin-first"), latinBytes).get(0);
        FontFaceId wideFace = backend.register(TestFonts.id("wide-second"), wideBytes).get(0);
        Font latinFont = TestFonts.inspect(latinBytes);
        Font wideFont = TestFonts.inspect(wideBytes);
        char uncovered = TestFonts.findUncoveredBy(wideFont, latinFont);

        String mixed = "ab" + uncovered + "cd";
        TextLine line = backend.layout(mixed, List.of(latinFace, wideFace), 18f, 1f);
        assertEquals(mixed, line.text());
        assertEquals(List.of(latinFace, wideFace), line.usedFonts(),
                "the cluster the first face cannot show must use the next face");
        assertTrue(line.advance() > 0f);

        TextLine allCovered = backend.layout("Latin", List.of(latinFace, wideFace), 18f, 1f);
        assertEquals(List.of(latinFace), allCovered.usedFonts());

        // The wide face comes first and covers the whole line, so no fallback happens at all.
        TextLine reversed = backend.layout(mixed, List.of(wideFace, latinFace), 18f, 1f);
        assertEquals(List.of(wideFace), reversed.usedFonts());
    }

    @Test
    void chosenFaceActuallyDrivesTheShapedMetrics() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        byte[] wideBytes = TestFonts.wide();
        FontFaceId latinFace = backend.register(TestFonts.id("metrics-latin"), latinBytes).get(0);
        FontFaceId wideFace = backend.register(TestFonts.id("metrics-wide"), wideBytes).get(0);
        char uncovered = TestFonts.findUncoveredBy(TestFonts.inspect(wideBytes),
                TestFonts.inspect(latinBytes));

        TextLine latinOnly = backend.layout("MMMM", List.of(latinFace), 24f, 1f);
        TextLine wideOnly = backend.layout("MMMM", List.of(wideFace), 24f, 1f);
        assertFalse(Math.abs(latinOnly.advance() - wideOnly.advance()) < 0.5f,
                "the two host faces must differ in metrics for this test to mean anything");

        // First match wins, so the first face decides the metrics of a fully covered cluster.
        TextLine first = backend.layout("MMMM", List.of(latinFace, wideFace), 24f, 1f);
        assertEquals(latinOnly.advance(), first.advance(), 0.001f,
                "the first covering face must shape the cluster");
        assertEquals(List.of(latinFace), first.usedFonts());

        // A cluster only the second face covers is shaped by that face: the mixed metrics equal the
        // metrics of the same text laid out with the covering face alone.
        String onlyWide = String.valueOf(uncovered) + uncovered;
        TextLine mixed = backend.layout(onlyWide, List.of(latinFace, wideFace), 24f, 1f);
        TextLine wideAlone = backend.layout(onlyWide, List.of(wideFace), 24f, 1f);
        assertEquals(List.of(wideFace), mixed.usedFonts());
        assertEquals(wideAlone.advance(), mixed.advance(), 0.001f);
    }

    @Test
    void noFaceCoversClusterAndFirstFaceIsRecorded() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] firstBytes = TestFonts.latin();
        byte[] secondBytes = TestFonts.wide();
        FontFaceId first = backend.register(TestFonts.id("first"), firstBytes).get(0);
        FontFaceId second = backend.register(TestFonts.id("second"), secondBytes).get(0);
        Font firstFont = TestFonts.inspect(firstBytes);
        Font secondFont = TestFonts.inspect(secondBytes);
        int missing = -1;
        for (int codePoint = 0x20; codePoint <= 0xFFFF; codePoint++) {
            if (Character.isSurrogate((char) codePoint)) {
                continue;
            }
            if (!firstFont.canDisplay(codePoint) && !secondFont.canDisplay(codePoint)) {
                missing = codePoint;
                break;
            }
        }
        assertTrue(missing >= 0, "host fonts must leave some BMP code point uncovered");
        TextLine line = backend.layout(String.valueOf((char) missing), List.of(first, second), 18f, 1f);
        assertEquals(List.of(first), line.usedFonts(), "first fallback face is the recorded choice");
    }

    @Test
    void surrogatePairStaysInOneCluster() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("surrogate"), "Dialog");
        String text = "a" + new String(Character.toChars(0x1F600)) + "b";
        assertArrayEquals(new int[] {0, 1, 3, 4}, oracleBoundaries(text),
                "the surrogate pair is one cluster on the host as well");
        TextLine line = backend.layout(text, List.of(face), 20f, 1f);
        assertEquals(text, line.text());
        assertEquals(4, text.length());
        assertThrows(IllegalArgumentException.class, () -> line.caretX(2));
        // No hit may land on index 2, the low surrogate of the pair, and every cluster boundary must
        // still round trip through its own caret position.
        for (float x = -20f; x <= line.advance() + 20f; x += 0.5f) {
            int hit = line.hitTest(x);
            assertTrue(hit == 0 || hit == 1 || hit == 3 || hit == 4,
                    "the hit must stay on a cluster boundary, never inside the surrogate pair: " + hit);
        }
        for (int boundary : new int[] {0, 1, 3, 4}) {
            assertEquals(boundary, line.hitTest(line.caretX(boundary)), "round trip at " + boundary);
        }
        assertEquals(0, line.hitTest(-20f));
        assertEquals(text.length(), line.hitTest(line.advance() + 20f));
    }

    @Test
    void caretAndHitTestAgreeOnLeftToRightText() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("ltr"), "Dialog");
        String text = "The quick brown fox";
        TextLine line = backend.layout(text, List.of(face), 16f, 1f);
        for (int index = 0; index <= text.length(); index++) {
            float caret = line.caretX(index);
            assertTrue(Float.isFinite(caret));
            assertTrue(caret >= 0f && caret <= line.advance() + 0.001f,
                    "caret stays inside the advance: " + index + " -> " + caret);
            assertTrue(Math.abs(line.hitTest(caret) - index) <= 1,
                    "round trip at boundary " + index + " -> " + line.hitTest(caret));
        }
        assertEquals(0, line.hitTest(-500f));
        assertEquals(text.length(), line.hitTest(line.advance() + 500f));
    }

    @Test
    void bidiLineKeepsLogicalIndicesValid() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("bidi"), "Dialog");
        String text = "abc \u0645\u0631\u062d\u0628\u0627 def";
        TextLine line = backend.layout(text, List.of(face), 16f, 1f);
        assertTrue(line.advance() > 0f);
        for (int index = 0; index <= text.length(); index++) {
            assertTrue(Float.isFinite(line.caretX(index)));
        }
        for (float x = -20f; x <= line.advance() + 20f; x += 1.5f) {
            int hit = line.hitTest(x);
            assertTrue(hit >= 0 && hit <= text.length(), "hit inside text: " + hit);
            assertTrue(isCharacterBoundary(text, hit), "hit must not split a cluster: " + hit);
        }
        assertEquals(text.length(), line.hitTest(line.advance() + 40f));
        assertEquals(0, line.hitTest(-40f));
    }

    /**
     * A cross font bidi line whose font assignment follows the documented fallback rule, rebuilt in
     * the test as one whole line {@link AttributedString}. Laying each font run out separately and
     * concatenating it would break the bidi order across the font boundary, and every number below
     * would move; the oracle is built straight from the host fonts instead.
     */
    @Test
    void crossFontBidiLineMatchesAnIndependentWholeLineLayout() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] wideBytes = TestFonts.wide();
        byte[] latinBytes = TestFonts.latin();
        List<FontFaceId> faces = List.of(backend.register(TestFonts.id("rtl-wide"), wideBytes).get(0),
                backend.register(TestFonts.id("rtl-latin"), latinBytes).get(0));
        List<Font> hosts = List.of(TestFonts.inspect(wideBytes), TestFonts.inspect(latinBytes));
        String text = "\u0645\u0631\u062d\u0628\u0627 abc";
        float size = 28f;
        FontRenderContext context = new FontRenderContext(null, true, true);

        List<Font> usedFaces = oracleFaces(text, hosts);
        assertTrue(usedFaces.size() > 1,
                "the host faces must split this line across a font boundary: " + usedFaces);
        TextLayout oracle = oracleLayout(text, hosts, size, context);
        int[] boundaries = oracleBoundaries(text);
        assertTrue(boundaries.length > 2, "the line must have several clusters");

        TextLine line = backend.layout(text, faces, size, 1f);
        assertEquals(usedFaces.size(), line.usedFonts().size(),
                "the oracle and the backend must use the same faces: " + line.usedFonts());
        assertEquals(oracle.getAdvance(), line.advance(), 0.01f, "advance");
        assertEquals(oracle.getAscent(), line.ascent(), 0.01f, "ascent");
        assertEquals(oracle.getDescent(), line.descent(), 0.01f, "descent");
        for (int boundary : boundaries) {
            assertEquals(oracleCaretX(oracle, boundary), line.caretX(boundary), 0.01f,
                    "caret at cluster boundary " + boundary);
        }
        for (float x = -30f; x <= line.advance() + 30f; x += 1.25f) {
            int hit = line.hitTest(x);
            assertTrue(isCharacterBoundary(text, hit), "hit must not split a cluster: " + hit);
            assertEquals(oracleHit(oracle, boundaries, x), hit, "hit at x=" + x);
        }
        assertRasterMatchesOracleCanvas(line.rasterize(), oracle);
    }

    /** A latin face followed by a CJK face: the middle cluster must move to the second face. */
    @Test
    void crossFontLatinCjkLineKeepsEveryPixelItShaped() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        byte[] wideBytes = TestFonts.wide();
        FontFaceId latinFace = backend.register(TestFonts.id("cjk-latin"), latinBytes).get(0);
        FontFaceId wideFace = backend.register(TestFonts.id("cjk-wide"), wideBytes).get(0);
        Font latinHost = TestFonts.inspect(latinBytes);
        Font wideHost = TestFonts.inspect(wideBytes);
        assertFalse(latinHost.canDisplay('\u4E2D'),
                "the latin face must lack the CJK cluster for this case to be cross font");
        assertTrue(wideHost.canDisplay('\u4E2D'));

        String text = "A\u4E2DB";
        float size = 40f;
        FontRenderContext context = new FontRenderContext(null, true, true);
        TextLayout oracle = oracleLayout(text, List.of(latinHost, wideHost), size, context);
        TextLine line = backend.layout(text, List.of(latinFace, wideFace), size, 1f);
        assertEquals(List.of(latinFace, wideFace), line.usedFonts());
        assertEquals(oracle.getAdvance(), line.advance(), 0.01f, "advance");
        assertEquals(oracle.getAscent(), line.ascent(), 0.01f, "ascent");
        assertEquals(oracle.getDescent(), line.descent(), 0.01f, "descent");
        for (int index = 0; index <= text.length(); index++) {
            assertEquals(oracleCaretX(oracle, index), line.caretX(index), 0.01f, "caret " + index);
        }

        Raster raster = line.rasterize();
        assertRasterSane(raster);
        assertInkPresent(raster);
        // No ink on the border of a buffer sized from the real pixel bounds, and the samples are a
        // pixel exact crop of the same layout drawn on an independently padded canvas, so neither the
        // CJK cluster nor the AA spill of the outer glyphs can have been clipped away.
        assertNoInkAtBorder(raster);
        assertRasterMatchesOracleCanvas(raster, oracle);
    }

    @Test
    void combiningMarkClusterIsNeverSplitByHitTest() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.register(TestFonts.id("combining"), TestFonts.latin()).get(0);
        String text = "a\u0301";
        assertArrayEquals(new int[] {0, 2}, oracleBoundaries(text),
                "a and its combining acute form one cluster");
        TextLine line = backend.layout(text, List.of(face), 32f, 1f);

        for (float x = -10f; x <= line.advance() + 10f; x += 0.5f) {
            int hit = line.hitTest(x);
            assertTrue(hit == 0 || hit == text.length(),
                    "the combining cluster must never be split: hit=" + hit + " at x=" + x);
        }
        assertEquals(0, line.hitTest(-10f));
        assertEquals(text.length(), line.hitTest(line.advance() + 10f));
        assertTrue(Float.isFinite(line.caretX(1)));
    }

    @Test
    void hitTestOnAMaximumLengthLineStaysCheap() {
        Java2dTextBackend backend = new Java2dTextBackend();
        List<FontFaceId> faces = List.of(backend.registerLogical(TestFonts.id("long"), "Dialog"));
        String text = "W".repeat(Java2dTextBackend.MAX_TEXT_LENGTH);
        TextLine line = backend.layout(text, faces, 12f, 1f);
        float advance = line.advance();
        assertTrue(advance > 0f);

        // A drag over a maximum length line: 500 hits. The per boundary caret scan this replaced
        // measured about 4.8 ms per hit; hitTest now measures about 0.15 ms per hit, so this budget
        // fails the old strategy and passes the current one with a wide margin.
        int hits = 500;
        int[] results = new int[hits];
        long start = System.nanoTime();
        for (int index = 0; index < hits; index++) {
            results[index] = line.hitTest(advance * index / hits);
        }
        long elapsed = System.nanoTime() - start;
        for (int hit : results) {
            assertTrue(hit >= 0 && hit <= text.length(), "hit inside the text: " + hit);
            assertTrue(isCharacterBoundary(text, hit), "hit must not split a cluster: " + hit);
        }
        assertTrue(elapsed < 1_000_000_000L,
                hits + " hits on " + text.length() + " chars took " + elapsed / 1_000_000 + " ms");
    }

    @Test
    void physicalSizeMustStayFiniteAndPositive() {
        Java2dTextBackend backend = new Java2dTextBackend();
        List<FontFaceId> faces = List.of(backend.registerLogical(TestFonts.id("size"), "Dialog"));
        // Products that underflow to zero or overflow to infinity are rejected...
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", faces, Float.MIN_VALUE, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> backend.layout("x", faces, Float.MIN_VALUE, Float.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", faces, Float.MAX_VALUE, 2f));
        assertThrows(IllegalArgumentException.class,
                () -> backend.layout("x", faces, Float.MAX_VALUE, Float.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> backend.layout("x", faces, Float.NaN, Float.MIN_VALUE));
        // ...while a positive subnormal product is a legal, degenerate size with zero ink.
        TextLine tiny = backend.layout("x", faces, Float.MIN_VALUE, 1f);
        assertEquals(0f, tiny.advance());
        int degenerateHit = tiny.hitTest(Float.MIN_VALUE);
        assertTrue(degenerateHit >= 0 && degenerateHit <= 1,
                "a hit on a zero advance line stays a legal index: " + degenerateHit);
        assertRasterSane(tiny.rasterize());
    }

    @Test
    void rasterKeepsItalicNegativeBearingInsideTheBuffer() {
        Java2dTextBackend backend = new Java2dTextBackend();
        TestFonts.ItalicSiblings siblings = TestFonts.italicSiblings();
        List<FontFaceId> faces = backend.register(TestFonts.id("italic"), siblings.italicBytes());

        // An upright face also overhangs for some glyphs (Segoe UI's "j" starts 6px left of the
        // origin), so picking the glyph on the host and requiring the italic face to overhang
        // clearly further than its upright sibling is what makes this test about italic ink.
        Font italicHost = TestFonts.inspect(siblings.italicBytes()).deriveFont(48f);
        Font uprightHost = TestFonts.inspect(siblings.uprightBytes()).deriveFont(48f);
        FontRenderContext context = new FontRenderContext(null, true, true);
        String glyph = TestFonts.findOverhangingGlyph(italicHost, uprightHost, 48f);
        String text = glyph.repeat(2);
        float italicInkLeft = TestFonts.inkMinX(italicHost, text, context);
        float uprightInkLeft = TestFonts.inkMinX(uprightHost, text, context);
        assertTrue(italicInkLeft < 0f,
                siblings.italicFile() + " must overhang the origin for " + glyph + ": " + italicInkLeft);
        assertTrue(italicInkLeft < uprightInkLeft - 0.5f,
                "the overhang must come from the italic design, not from the glyph: italic="
                        + italicInkLeft + " upright=" + uprightInkLeft);

        TextLine line = backend.layout(text, List.of(faces.get(0)), 48f, 1f);
        Raster raster = line.rasterize();
        assertRasterSane(raster);
        assertEquals(raster.width() * raster.height(), raster.argb().length);
        assertTrue(raster.originX() >= 1f, "origin must sit inside the buffer: " + raster.originX());
        assertTrue(raster.baseline() >= 1f && raster.baseline() < raster.height());
        assertInkPresent(raster);
        // Bounds are the real pixel bounds plus padding, so ink never reaches the buffer edge.
        assertNoInkAtBorder(raster);

        // The host ink is left of the layout origin, so the raster must contain ink strictly left of
        // originX as well, and it must not need more room than the measured bearing.
        int firstInked = firstInkedColumn(raster);
        assertTrue(firstInked >= 0, "raster must contain ink");
        assertTrue(firstInked < raster.originX(),
                "ink left of the layout origin must be kept: first=" + firstInked
                        + " origin=" + raster.originX() + " inkLeft=" + italicInkLeft);
        assertTrue(firstInked >= raster.originX() + italicInkLeft - 1f,
                "the buffer must not be padded beyond the measured bearing: first=" + firstInked
                        + " origin=" + raster.originX() + " inkLeft=" + italicInkLeft);
    }

    @Test
    void scaleChangesPhysicalPixelsNotLogicalMetrics() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("dpi"), "SansSerif");
        TextLine single = backend.layout("Resolution", List.of(face), 16f, 1f);
        TextLine doubled = backend.layout("Resolution", List.of(face), 16f, 2f);
        assertEquals(single.advance() * 2f, doubled.advance(), single.advance() * 0.05f);
        Raster singleRaster = single.rasterize();
        Raster doubledRaster = doubled.rasterize();
        assertRasterSane(singleRaster);
        assertRasterSane(doubledRaster);
        assertTrue(doubledRaster.width() > singleRaster.width());
        float widthTolerance = Math.max(4f, singleRaster.width() * 0.15f);
        assertTrue(Math.abs(doubledRaster.width() - singleRaster.width() * 2f) <= widthTolerance,
                "raster width roughly doubles: " + singleRaster.width() + " -> " + doubledRaster.width());
        // Ink extents are quantized to whole pixels and hinting varies per size, so the buffered
        // height only tracks the scale roughly while the advance is exactly proportional.
        float heightTolerance = Math.max(4f, singleRaster.height() * 0.5f);
        assertTrue(Math.abs(doubledRaster.height() - singleRaster.height() * 2f) <= heightTolerance,
                "raster height roughly doubles: " + singleRaster.height() + " -> " + doubledRaster.height());
    }

    @Test
    void rasterIsFreshOnEveryCall() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("fresh"), "Dialog");
        TextLine line = backend.layout("fresh buffer", List.of(face), 20f, 1f);
        Raster first = line.rasterize();
        Raster second = line.rasterize();
        assertNotSame(first.argb(), second.argb());
        assertArrayEquals(first.argb(), second.argb(), "rasterization is deterministic");
        int[] stolen = first.argb();
        java.util.Arrays.fill(stolen, 0);
        assertArrayEquals(first.argb(), second.argb(), "external mutation cannot reach stored samples");
    }

    @Test
    void rasterSamplesAreStraightArgbOfTheInkColour() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.register(TestFonts.id("samples"), TestFonts.latin()).get(0);
        Raster raster = backend.layout("Hamburgefonstiv", List.of(face), 24f, 1f).rasterize();
        assertRasterSane(raster);
        assertInkPresent(raster);
        int partial = 0;
        for (int sample : raster.argb()) {
            // Coverage lives in alpha alone and the ink colour is opaque black, so the RGB channels
            // are zero at every coverage level. This only holds because rasterization pins the ink
            // colour; see whiteDefaultPaintWouldFringe... for the reproduction of the trap.
            assertEquals(0, sample & 0x00FFFFFF,
                    "RGB channels must carry the black ink colour: " + Integer.toHexString(sample));
            int alpha = sample >>> 24;
            if (alpha > 0 && alpha < 255) {
                partial++;
            }
        }
        assertTrue(partial > 0, "an antialiased 24px line must produce partially covered pixels");
    }

    /**
     * Minimal reproduction of the trap behind the ink colour: a fresh {@code createGraphics()} paints
     * white, so text drawn without setting a colour leaves {@code 0xFFFFFF} in the RGB channels of
     * every touched pixel. The samples are then not straight ARGB of black ink and fringe as soon as
     * a consumer composites RGB with alpha over its own background.
     */
    @Test
    void unsetPaintLeavesWhiteRgbWhileTheBackendPinsBlackInk() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.register(TestFonts.id("paint"), TestFonts.latin()).get(0);
        String text = "Hamburgefonstiv";
        Font hostFont = TestFonts.inspect(TestFonts.latin()).deriveFont(24f);
        FontRenderContext context = new FontRenderContext(null, true, true);
        TextLayout hostLayout = new TextLayout(text, hostFont, context);
        Rectangle2D pixels = hostLayout.getPixelBounds(context, 0f, 0f);
        int width = (int) Math.ceil(pixels.getMaxX()) - (int) Math.floor(pixels.getMinX()) + 4;
        int height = (int) Math.ceil(pixels.getMaxY()) - (int) Math.floor(pixels.getMinY()) + 4;
        float originX = 2f - (float) Math.floor(pixels.getMinX());
        float originY = 2f - (float) Math.floor(pixels.getMinY());

        assertTrue(whiteRgbSamples(hostLayout, width, height, originX, originY, false) > 0,
                "this JDK's default paint is white, so an unpinned paint leaves 0xFFFFFF behind");
        assertEquals(0, whiteRgbSamples(hostLayout, width, height, originX, originY, true),
                "an explicit black paint must not leave white RGB");

        Raster raster = backend.layout(text, List.of(face), 24f, 1f).rasterize();
        int whiteInBackend = 0;
        for (int sample : raster.argb()) {
            if ((sample >>> 24) > 0 && (sample & 0x00FFFFFF) == 0x00FFFFFF) {
                whiteInBackend++;
            }
        }
        assertEquals(0, whiteInBackend, "the backend must not expose white RGB in covered samples");
    }

    /** Counts covered pixels whose RGB channels are white, optionally painting black explicitly. */
    private static int whiteRgbSamples(TextLayout layout, int width, int height, float originX,
            float originY, boolean pinColour) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            if (pinColour) {
                graphics.setColor(Color.BLACK);
            }
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            layout.draw(graphics, originX, originY);
        } finally {
            graphics.dispose();
        }
        int white = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sample = image.getRGB(x, y);
                if ((sample >>> 24) > 0 && (sample & 0x00FFFFFF) == 0x00FFFFFF) {
                    white++;
                }
            }
        }
        return white;
    }

    @Test
    void layoutInputValidationIsComplete() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("validate"), "Dialog");
        FontFaceId unregistered = new FontFaceId(TestFonts.id("never-registered"));
        List<FontFaceId> ok = List.of(face);

        assertThrows(IllegalArgumentException.class, () -> backend.layout("a\rb", ok, 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("a\nb", ok, 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("\uD800", ok, 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("\uDC00", ok, 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("\uD800x", ok, 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x".repeat(
                Java2dTextBackend.MAX_TEXT_LENGTH + 1), ok, 12f, 1f));
        assertThrows(NullPointerException.class, () -> backend.layout(null, ok, 12f, 1f));
        assertThrows(NullPointerException.class, () -> backend.layout("x", null, 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", List.of(), 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", List.of(face, face), 12f, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> backend.layout("x", List.of(unregistered), 12f, 1f));
        assertThrows(NullPointerException.class, () -> backend.layout("x", List.of((FontFaceId) null), 12f, 1f));
        List<FontFaceId> tooMany = new ArrayList<>();
        for (int index = 0; index <= Java2dTextBackend.MAX_FALLBACK_FACES; index++) {
            tooMany.add(new FontFaceId(EditorId.parse("test:many-" + index)));
        }
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", tooMany, 12f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", ok, 0f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", ok, -1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", ok, Float.NaN, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> backend.layout("x", ok, Float.POSITIVE_INFINITY, 1f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", ok, 12f, 0f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", ok, 12f, -2f));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("x", ok, 12f, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> backend.layout("x", ok, Java2dTextBackend.MAX_PHYSICAL_SIZE + 1f, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> backend.layout("x", ok, 200f, 2f));
        // The boundary is inclusive and does not throw.
        assertTrue(backend.layout("x", ok, Java2dTextBackend.MAX_PHYSICAL_SIZE, 1f).advance() > 0f);
        assertThrows(IndexOutOfBoundsException.class, () -> backend.layout("ab", ok, 12f, 1f).caretX(3));
        assertThrows(IndexOutOfBoundsException.class, () -> backend.layout("ab", ok, 12f, 1f).caretX(-1));
        assertThrows(IllegalArgumentException.class, () -> backend.layout("ab", ok, 12f, 1f).hitTest(Float.NaN));
    }

    @Test
    void usedFontsListIsImmutable() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("immutable"), "Dialog");
        TextLine line = backend.layout("abc", List.of(face), 12f, 1f);
        List<FontFaceId> used = line.usedFonts();
        assertThrows(UnsupportedOperationException.class, () -> used.add(face));
        assertEquals(1, used.size());
    }

    @Test
    void lineRasterForLongTextStaysWithinRasterLimits() {
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId face = backend.registerLogical(TestFonts.id("long"), "Monospaced");
        TextLine line = backend.layout("W".repeat(32), List.of(face), 12f, 1f);
        Raster raster = line.rasterize();
        assertRasterSane(raster);
        assertInkPresent(raster);
    }

    private static void assertRasterSane(Raster raster) {
        assertTrue(raster.width() >= 1 && raster.width() <= Raster.MAX_SIDE);
        assertTrue(raster.height() >= 1 && raster.height() <= Raster.MAX_SIDE);
        long pixels = raster.width();
        pixels *= raster.height();
        assertTrue(pixels <= Raster.MAX_PIXELS);
        assertEquals(raster.width() * raster.height(), raster.argb().length);
        assertTrue(raster.originX() >= 0f && raster.originX() <= raster.width());
        assertTrue(raster.baseline() >= 0f && raster.baseline() <= raster.height());
    }

    private static void assertInkPresent(Raster raster) {
        int[] pixels = raster.argb();
        int inked = 0;
        for (int value : pixels) {
            if ((value >>> 24) != 0) {
                inked++;
            }
        }
        assertTrue(inked > 0, "glyphs must produce visible pixels");
    }

    private static void assertNoInkAtBorder(Raster raster) {
        int width = raster.width();
        int height = raster.height();
        int[] pixels = raster.argb();
        for (int x = 0; x < width; x++) {
            assertEquals(0, pixels[x] >>> 24, "top row must be padding at x=" + x);
            assertEquals(0, pixels[(height - 1) * width + x] >>> 24, "bottom row must be padding");
        }
        for (int y = 0; y < height; y++) {
            assertEquals(0, pixels[y * width] >>> 24, "left column must be padding at y=" + y);
            assertEquals(0, pixels[y * width + width - 1] >>> 24, "right column must be padding");
        }
    }

    /** Leftmost column holding ink, or {@code -1} when the raster is fully transparent. */
    private static int firstInkedColumn(Raster raster) {
        int[] pixels = raster.argb();
        for (int x = 0; x < raster.width(); x++) {
            for (int y = 0; y < raster.height(); y++) {
                if ((pixels[y * raster.width() + x] >>> 24) != 0) {
                    return x;
                }
            }
        }
        return -1;
    }

    /** The face the documented fallback rule picks for one cluster, computed from host fonts only. */
    private static Font oracleFaceFor(String cluster, List<Font> ordered) {
        for (Font candidate : ordered) {
            if (candidate.canDisplayUpTo(cluster) < 0) {
                return candidate;
            }
        }
        return ordered.get(0);
    }

    /** The distinct host faces the documented fallback rule selects, in first use order. */
    private static List<Font> oracleFaces(String text, List<Font> ordered) {
        List<Font> used = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE;
                start = end, end = iterator.next()) {
            Font chosen = oracleFaceFor(text.substring(start, end), ordered);
            if (!used.contains(chosen)) {
                used.add(chosen);
            }
        }
        return used;
    }

    /** Independent whole line layout: one {@link AttributedString} with one font per cluster. */
    private static TextLayout oracleLayout(String text, List<Font> ordered, float size,
            FontRenderContext context) {
        AttributedString attributed = new AttributedString(text);
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE;
                start = end, end = iterator.next()) {
            Font chosen = oracleFaceFor(text.substring(start, end), ordered);
            attributed.addAttribute(TextAttribute.FONT, chosen.deriveFont(size), start, end);
        }
        return new TextLayout(attributed.getIterator(), context);
    }

    /** Cluster boundaries of the text, computed independently of the backend. */
    private static int[] oracleBoundaries(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        List<Integer> boundaries = new ArrayList<>();
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE;
                boundary = iterator.next()) {
            boundaries.add(boundary);
        }
        int[] result = new int[boundaries.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = boundaries.get(index);
        }
        return result;
    }

    /** Caret x of the oracle layout at a cluster boundary, using the documented leading/trailing rule. */
    private static float oracleCaretX(TextLayout oracle, int index) {
        Rectangle2D box = oracle.getCaretShapes(index)[0].getBounds2D();
        return oracle.isLeftToRight() ? (float) box.getMinX() : (float) box.getMaxX();
    }

    /** The documented hit rule applied to the oracle: nearest adjacent cluster boundary, ties lower. */
    private static int oracleHit(TextLayout oracle, int[] boundaries, float x) {
        int length = boundaries[boundaries.length - 1];
        int insertion = oracle.hitTestChar(x, 0f).getInsertionIndex();
        if (insertion <= 0) {
            return 0;
        }
        if (insertion >= length) {
            return length;
        }
        int slot = 0;
        while (slot + 1 < boundaries.length && boundaries[slot + 1] <= insertion) {
            slot++;
        }
        if (boundaries[slot] == insertion) {
            return insertion;
        }
        float lowerDistance = Math.abs(oracleCaretX(oracle, boundaries[slot]) - x);
        float upperDistance = Math.abs(oracleCaretX(oracle, boundaries[slot + 1]) - x);
        return lowerDistance <= upperDistance ? boundaries[slot] : boundaries[slot + 1];
    }

    /**
     * Draws the oracle layout on its own generously padded canvas and requires the backend's raster
     * to be a pixel exact crop of it. Because both are drawn at integer offsets with the same ink
     * colour and hints, any clipped column, misplaced glyph or wrong font assignment in the raster
     * changes at least one sample.
     */
    private static void assertRasterMatchesOracleCanvas(Raster raster, TextLayout oracle) {
        FontRenderContext context = new FontRenderContext(null, true, true);
        Rectangle2D pixels = oracle.getPixelBounds(context, 0f, 0f);
        int left = (int) Math.floor(pixels.getMinX()) - CANVAS_PADDING;
        int top = (int) Math.floor(pixels.getMinY()) - CANVAS_PADDING;
        int width = (int) Math.ceil(pixels.getMaxX()) - left + CANVAS_PADDING + 1;
        int height = (int) Math.ceil(pixels.getMaxY()) - top + CANVAS_PADDING + 1;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            oracle.draw(graphics, -left, -top);
        } finally {
            graphics.dispose();
        }
        int[] canvas = image.getRGB(0, 0, width, height, null, 0, width);
        int dx = Math.round(-raster.originX() - left);
        int dy = Math.round(-raster.baseline() - top);
        assertTrue(dx >= 0 && dy >= 0 && dx + raster.width() <= width && dy + raster.height() <= height,
                "the raster must fit inside the oracle canvas");
        int[] samples = raster.argb();
        for (int y = 0; y < raster.height(); y++) {
            for (int x = 0; x < raster.width(); x++) {
                int expected = canvas[(y + dy) * width + x + dx];
                int actual = samples[y * raster.width() + x];
                assertEquals(Integer.toHexString(expected), Integer.toHexString(actual),
                        "raster sample differs from an independent canvas at " + x + "," + y);
            }
        }
    }

    private static boolean isCharacterBoundary(String text, int index) {
        if (index < 0 || index > text.length()) {
            return false;
        }
        if (index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index))
                && Character.isHighSurrogate(text.charAt(index - 1))) {
            return false;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            if (boundary == index) {
                return true;
            }
            if (boundary > index) {
                return false;
            }
        }
        return false;
    }

}
