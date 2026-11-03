package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Behavioural equivalence between {@link Java2dTextBackend#layout} and the per-cluster attribute path
 * it replaced, which is kept in {@link LegacyClusterReference}. Adjacent clusters that resolve to the
 * same face now share one {@code FONT} attribute instead of one attribute per cluster, which is meant
 * to be invisible except in how many runs the JDK has to build, so every case here compares the two
 * lines on shaped metrics, caret positions, hits and pixels rather than on time.
 *
 * <p>Each case is also pinned to be non vacuous: a pure single face line really is one run, and the
 * mixed cases really do alternate faces, so the merge has to close and reopen runs instead of
 * accidentally passing on a line that never switches.
 */
class AdjacentFaceMergeTest {

    @Test
    void singleFaceLineMatchesThePerClusterReference() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        FontFaceId latin = backend.register(TestFonts.id("merge-latin"), latinBytes).get(0);
        Font latinHost = TestFonts.inspect(latinBytes);
        String text = "office waffle affine fluid jiffy";
        assertEquals(1, LegacyClusterReference.runCount(text, List.of(latinHost)),
                "this line must stay on one face, so the merge has every adjacent cluster to join");

        TextLine line = backend.layout(text, List.of(latin), 24f, 1f);
        assertEquals(List.of(latin), line.usedFonts());
        assertEquivalent(text, line, List.of(latin), List.of(latinHost), 24f);
    }

    @Test
    void alternatingFallbackRunsMatchThePerClusterReference() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        byte[] wideBytes = TestFonts.wide();
        Font latinHost = TestFonts.inspect(latinBytes);
        Font wideHost = TestFonts.inspect(wideBytes);
        assertFalse(latinHost.canDisplay('\u4E2D'), "the latin face must lack the CJK cluster");
        assertTrue(wideHost.canDisplay('\u4E2D'));
        List<FontFaceId> faces = List.of(backend.register(TestFonts.id("merge-alt-latin"), latinBytes).get(0),
                backend.register(TestFonts.id("merge-alt-wide"), wideBytes).get(0));
        String text = "ab\u4E2D\u6587cd\u6F22\u5B57ef\u4E2D\u6587gh";
        // Latin, CJK, Latin, CJK, Latin, CJK, Latin: seven runs, so the merge closes and reopens a
        // run three times and still has to keep the first use order of the two faces.
        assertEquals(7, LegacyClusterReference.runCount(text, List.of(latinHost, wideHost)),
                "the two faces must alternate repeatedly and return to the first one");

        TextLine line = backend.layout(text, faces, 32f, 1f);
        assertEquals(faces, line.usedFonts(), "first use order, each face once even when reused later");
        assertEquivalent(text, line, faces, List.of(latinHost, wideHost), 32f);
    }

    @Test
    void crossFontBidiRunsMatchThePerClusterReference() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] wideBytes = TestFonts.wide();
        byte[] latinBytes = TestFonts.latin();
        Font wideHost = TestFonts.inspect(wideBytes);
        Font latinHost = TestFonts.inspect(latinBytes);
        List<FontFaceId> faces = List.of(backend.register(TestFonts.id("merge-bidi-wide"), wideBytes).get(0),
                backend.register(TestFonts.id("merge-bidi-latin"), latinBytes).get(0));
        String text = "\u0645\u0631\u062d\u0628\u0627 abc \u0639\u0627\u0644\u0645 def";
        assertTrue(LegacyClusterReference.runCount(text, List.of(wideHost, latinHost)) > 1,
                "the host faces must split this bidi line across a font boundary");

        TextLine line = backend.layout(text, faces, 28f, 1f);
        assertEquals(2, line.usedFonts().size(),
                "the oracle and the backend must use the same faces: " + line.usedFonts());
        assertEquivalent(text, line, faces, List.of(wideHost, latinHost), 28f);
    }

    @Test
    void combiningSequenceRunsMatchThePerClusterReference() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        FontFaceId latin = backend.register(TestFonts.id("merge-combining"), latinBytes).get(0);
        Font latinHost = TestFonts.inspect(latinBytes);
        String text = "a\u0301e\u0302o\u0308";
        assertArrayEquals(new int[] {0, 2, 4, 6}, LegacyClusterReference.boundaries(text),
                "each base letter and its mark form one cluster");
        assertEquals(1, LegacyClusterReference.runCount(text, List.of(latinHost)));

        TextLine line = backend.layout(text, List.of(latin), 32f, 1f);
        assertEquivalent(text, line, List.of(latin), List.of(latinHost), 32f);
    }

    @Test
    void surrogatePairRunsMatchThePerClusterReference() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        byte[] wideBytes = TestFonts.wide();
        FontFaceId latin = backend.register(TestFonts.id("merge-surrogate-latin"), latinBytes).get(0);
        FontFaceId wide = backend.register(TestFonts.id("merge-surrogate-wide"), wideBytes).get(0);
        Font latinHost = TestFonts.inspect(latinBytes);
        Font wideHost = TestFonts.inspect(wideBytes);
        assertFalse(latinHost.canDisplay('\u4E2D'), "the latin face must lack the CJK cluster");
        // Two surrogate pairs, one between CJK clusters and one next to them, so the pairs sit inside
        // a line whose neighbouring clusters use another face and the runs have to close and reopen.
        String text = "A\u4E2D\uD840\uDC0B\u4E2D\uD83D\uDE00z";
        assertArrayEquals(new int[] {0, 1, 2, 4, 5, 7, 8}, LegacyClusterReference.boundaries(text),
                "a surrogate pair is one cluster and must never be split");
        assertTrue(LegacyClusterReference.runCount(text, List.of(latinHost, wideHost)) >= 3,
                "the host faces must switch around the pairs");

        TextLine line = backend.layout(text, List.of(latin, wide), 32f, 1f);
        assertEquivalent(text, line, List.of(latin, wide), List.of(latinHost, wideHost), 32f);
    }

    @Test
    void italicOverhangRunsMatchThePerClusterReference() {
        Java2dTextBackend backend = new Java2dTextBackend();
        TestFonts.ItalicSiblings siblings = TestFonts.italicSiblings();
        byte[] wideBytes = TestFonts.wide();
        FontFaceId italic = backend.register(TestFonts.id("merge-italic"), siblings.italicBytes()).get(0);
        FontFaceId wide = backend.register(TestFonts.id("merge-italic-wide"), wideBytes).get(0);
        Font italicHost = TestFonts.inspect(siblings.italicBytes());
        Font wideHost = TestFonts.inspect(wideBytes);
        assertFalse(italicHost.canDisplay('\u4E2D'), "the italic face must lack the CJK cluster");
        assertTrue(wideHost.canDisplay('\u4E2D'));
        float size = 40f;
        String overhang = TestFonts.findOverhangingGlyph(italicHost,
                TestFonts.inspect(siblings.uprightBytes()), size);
        assertTrue(TestFonts.inkMinX(italicHost.deriveFont(size), overhang,
                new FontRenderContext(null, true, true)) < -0.5f,
                "the chosen glyph must overhang the origin in the italic face");
        String text = overhang + overhang + "\u4E2D\u6587" + overhang;
        assertEquals(3, LegacyClusterReference.runCount(text, List.of(italicHost, wideHost)),
                "italic, CJK fallback and italic again must be three runs");

        TextLine line = backend.layout(text, List.of(italic, wide), size, 1f);
        assertEquals(List.of(italic, wide), line.usedFonts());
        assertEquivalent(text, line, List.of(italic, wide), List.of(italicHost, wideHost), size);
    }

    /**
     * The maximum length line keeps the recorded behaviour of the replaced path: the same grapheme
     * boundary hits, a surrogate pair and a combining sequence that are never split, faces that switch
     * and switch back so the runs close and reopen, and the raster budget rejection. Only a handful of
     * hits are compared and the line is never rasterized, so the case stays cheap and does not become
     * a timing test.
     */
    @Test
    void maximumLengthLineMatchesThePerClusterReferenceAtGraphemeBoundaries() {
        Java2dTextBackend backend = new Java2dTextBackend();
        byte[] latinBytes = TestFonts.latin();
        byte[] wideBytes = TestFonts.wide();
        List<FontFaceId> faces = List.of(backend.register(TestFonts.id("merge-long-latin"), latinBytes).get(0),
                backend.register(TestFonts.id("merge-long-wide"), wideBytes).get(0));
        List<Font> hosts = List.of(TestFonts.inspect(latinBytes), TestFonts.inspect(wideBytes));
        assertFalse(hosts.get(0).canDisplay('\u4E2D'), "the latin face must lack the CJK cluster");
        int fill = Java2dTextBackend.MAX_TEXT_LENGTH - 4;
        // A maximum length line with a CJK group in the middle and the surrogate pair and combining
        // sequence at the tail, so a long line also has to close and reopen its font runs.
        int head = fill / 2 - 1;
        String text = "W".repeat(head) + "\u4E2D\u6587" + "W".repeat(fill - head - 2)
                + "\uD840\uDC0B" + "e\u0301";
        assertEquals(Java2dTextBackend.MAX_TEXT_LENGTH, text.length());
        assertTrue(LegacyClusterReference.runCount(text, hosts) >= 3,
                "the long line must switch faces and come back, so its runs close and reopen");

        TextLine line = backend.layout(text, faces, 16f, 1f);
        TextLine reference = LegacyClusterReference.line(text, faces, hosts, 16f);
        assertEquals(reference.advance(), line.advance(), 0.05f, "long line advance");
        assertEquals(reference.ascent(), line.ascent(), 0.01f, "long line ascent");
        assertEquals(reference.descent(), line.descent(), 0.01f, "long line descent");
        assertEquals(reference.usedFonts(), line.usedFonts(), "long line face order");

        int[] boundaries = LegacyClusterReference.boundaries(text);
        assertEquals(fill + 3, boundaries.length,
                "one boundary per W, then the surrogate pair, the combining cluster and the end");
        // The first, a few spread out, and the last boundaries, which are the surrogate pair and the
        // combining sequence: a hit there must agree with the reference and stay on a boundary.
        int[] slots = {0, 1, fill / 2, fill - 1, fill, fill + 1, boundaries.length - 1};
        for (int slot : slots) {
            int boundary = boundaries[slot];
            float x = reference.caretX(boundary);
            int hit = line.hitTest(x);
            assertEquals(reference.hitTest(x), hit, "long line hit at boundary " + boundary);
            assertTrue(isBoundary(boundaries, hit),
                    "a long line hit must never split a surrogate pair or a combining sequence: " + hit);
        }
        assertThrows(IllegalStateException.class, line::rasterize,
                "the maximum length line must still be refused by the raster budget");
    }

    /**
     * Compares a produced line with the retained per-cluster reference on everything the public API
     * exposes: metrics, the first use order, caret positions at every cluster boundary, hits over a
     * dense sweep of pixel positions, and the raster samples.
     */
    private static void assertEquivalent(String text, TextLine line, List<FontFaceId> faces,
            List<Font> hosts, float size) {
        TextLine reference = LegacyClusterReference.line(text, faces, hosts, size);
        String label = "\"" + text + "\"";
        assertEquals(reference.advance(), line.advance(), 0.01f, label + " advance");
        assertEquals(reference.ascent(), line.ascent(), 0.01f, label + " ascent");
        assertEquals(reference.descent(), line.descent(), 0.01f, label + " descent");
        assertEquals(reference.text(), line.text(), label + " text");
        assertEquals(reference.usedFonts(), line.usedFonts(), label + " face order");

        int[] boundaries = LegacyClusterReference.boundaries(text);
        for (int boundary : boundaries) {
            float caret = reference.caretX(boundary);
            assertEquals(caret, line.caretX(boundary), 0.01f, label + " caret at " + boundary);
            assertEquals(reference.hitTest(caret), line.hitTest(caret), label + " hit at caret " + boundary);
        }
        for (float x = -20f; x <= line.advance() + 20f; x += 0.75f) {
            int hit = line.hitTest(x);
            assertEquals(reference.hitTest(x), hit, label + " hit at x=" + x);
            assertTrue(isBoundary(boundaries, hit), label + " hit must stay on a cluster boundary: " + hit);
        }

        Raster expected = reference.rasterize();
        Raster actual = line.rasterize();
        assertEquals(expected.width(), actual.width(), label + " raster width");
        assertEquals(expected.height(), actual.height(), label + " raster height");
        assertEquals(expected.originX(), actual.originX(), 0f, label + " raster originX");
        assertEquals(expected.baseline(), actual.baseline(), 0f, label + " raster baseline");
        assertArrayEquals(expected.argb(), actual.argb(), label + " raster pixels");
    }

    private static boolean isBoundary(int[] boundaries, int index) {
        for (int boundary : boundaries) {
            if (boundary == index) {
                return true;
            }
        }
        return false;
    }
}
