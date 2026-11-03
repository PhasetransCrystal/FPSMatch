package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.ptcrys.fpsmatch.editor.common.model.FieldPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Formal contract test of R06.0: {@link FieldPath}, {@link SchemaException} and {@link ContentDigest}.
 *
 * <p>The test proves the frozen R06.0 contract, not the implementation's own view of it: pointer
 * encoding and decoding are checked against an independent character oracle written here, and every
 * digest is checked against canonical text this test prepares itself, hashed by this test's own JDK
 * {@link MessageDigest}. No {@link ContentDigest} output is ever used as the oracle of another
 * {@link ContentDigest} call, and no assertion depends on the wording of a technical message.</p>
 *
 * <p>The encoding rule is fully fixed by the card and restated here because it is what the round trip
 * below depends on. The escape alphabet is exactly two spellings, {@code "~0"} for a literal
 * {@code '~'} and {@code "~1"} for a literal {@code '/'}; no other escape letter exists, so a dangling
 * {@code '~'} (one that ends the pointer, ends a segment, or is followed by {@code '/'}) and every
 * {@code "~x"} with another letter are rejected outright. Every other character is ordinary and is
 * copied verbatim, including {@code '='}, which is never a separator and is never escaped:
 * {@code ["=","a=b","c~2d"]} therefore has the pointer {@code "/=/a=b/c~02d"} and no pointer ever
 * contains a {@code "~2"}. Decoding is left to right and never recursive, so {@code "/~01"} is one
 * segment holding the two characters {@code '~'} and {@code '1'}. The round trip is exact for every
 * path this contract can construct: {@code pointer()} escapes every reserved character and
 * {@code fromPointer} returns those characters unchanged.</p>
 */
class PathDigestContractTest {

    /** Independent oracle of the pointer encoding rules of the card, written as its own character walk. */
    private static final char TILDE = '\u007E';

    private static final char SLASH = '/';

    /** The card's own pointer length budget, restated here as an independent constant. */
    private static final int EXPECTED_MAX_POINTER_CHARS = 133185;

    /** Fixed seed so the generated segment sets are the same on every run. */
    private static final long SEED = 20261014L;

    /** The card requires at least one thousand distinct generated segment lists. */
    private static final int GENERATED_PATHS = 1000;

    /** The card requires at least one thousand distinct digest values. */
    private static final int GENERATED_DIGESTS = 1000;

    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final String[] CODE_ALPHABET = {
        "A", "Z", "0", "9", "_",
        "MISSING_FIELD", "WRONG_TYPE", "INVALID_VALUE", "UNSUPPORTED_SCHEMA", "RESERVED_COLLISION",
        "INVALID_JSON", "INVALID_NUMBER", "INVALID_UNICODE", "DUPLICATE_KEY", "LIMIT_EXCEEDED",
        "A_", "A_0", "A_B_C", "A9999999999", "Z_", "AA", "A0Z", "_A", "a", "Ab", "A-b", "A B", "A.", "A:",
        "A\u00c4", "A\nB", "\u00c4A", "A_0_", "X".repeat(64), "X".repeat(65),
    };

    // ------------------------------------------------------------------
    // FieldPath: construction, snapshot and identity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("root has no segments, an empty pointer and isolated identity")
    void rootIsEmptyAndIsolated() {
        final FieldPath root = FieldPath.root();
        assertEquals(0, root.segments().size());
        assertTrue(root.segments().isEmpty());
        assertEquals("", root.pointer());
        assertEquals(FieldPath.root(), root);
        assertSame(FieldPath.root(), FieldPath.root());
        assertEquals(FieldPath.root().hashCode(), root.hashCode());
        assertEquals(FieldPath.MAX_SEGMENTS, 65);
        assertEquals(133185, EXPECTED_MAX_POINTER_CHARS);
        assertEquals(133185, 65 * (2 * 1024 + 1));
    }

    @Test
    @DisplayName("construction snapshots the input list and the getter is immutable")
    void constructionSnapshotsAndGetterIsImmutable() {
        final List<String> input = new ArrayList<>(List.of("a", "b"));
        final FieldPath path = new FieldPath(input);

        assertEquals(List.of("a", "b"), path.segments());
        input.add("c");
        input.set(0, "z");
        assertEquals(List.of("a", "b"), path.segments(), "later input mutation must not be visible");
        assertEquals("/a/b", path.pointer());

        assertThrows(UnsupportedOperationException.class, () -> path.segments().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> path.segments().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> path.segments().set(0, "x"));
        assertThrows(UnsupportedOperationException.class, () -> path.segments().clear());
        assertEquals(List.of("a", "b"), path.segments());
    }

    @Test
    @DisplayName("an empty segment, a numeric segment and separator characters are ordinary identities")
    void segmentsAreOpaqueIdentities() {
        final FieldPath empty = new FieldPath(List.of(""));
        assertEquals(1, empty.segments().size());
        assertEquals("/", empty.pointer());
        assertNotEquals(FieldPath.root(), empty);
        assertNotEquals(FieldPath.root().hashCode(), 0 == empty.hashCode() ? 1 : empty.hashCode());

        final FieldPath numeric = new FieldPath(List.of("0", "12"));
        assertEquals("0", numeric.segments().get(0));
        assertEquals(List.of("0", "12"), numeric.segments());
        assertEquals("/0/12", numeric.pointer());

        final FieldPath dotted = new FieldPath(List.of("a.b", "c:d", "e f", "g\\h", "\"i\""));
        assertEquals(List.of("a.b", "c:d", "e f", "g\\h", "\"i\""), dotted.segments());
        assertEquals("/a.b/c:d/e f/g\\h/\"i\"", dotted.pointer());
        assertEquals(dotted, FieldPath.fromPointer(dotted.pointer()));

        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/~2"),
                "~2 is not an escape of this contract: only ~0 and ~1 exist");
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/~3"));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/~9"));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/a~xb"));
        assertEquals(List.of("~2"), FieldPath.fromPointer("/~02").segments(),
                "~02 decodes to the two literal characters ~2, which re-encodes to ~02");
        assertEquals("/~02", FieldPath.fromPointer("/~02").pointer());
        assertEquals(FieldPath.fromPointer("/~02"), new FieldPath(List.of("~2")));

        // '=' is an ordinary character: it never splits a segment, is never escaped and is never
        // normalized, so the independent pointer writes it verbatim.
        final FieldPath equalsPath = new FieldPath(List.of("=", "a=b", "c~2d", "e~f"));
        assertEquals("/=/a=b/c~02d/e~0f", equalsPath.pointer());
        assertEquals(List.of("=", "a=b", "c~2d", "e~f"), FieldPath.fromPointer(equalsPath.pointer()).segments());
        assertEquals(oracleDecode(equalsPath.pointer()), equalsPath.segments());
        assertEquals(List.of("="), FieldPath.fromPointer("/=").segments());
        assertEquals("/=", FieldPath.fromPointer("/=").pointer());

        // A dot never splits a segment: one segment holding a colon is a different identity from two
        // segments whose text concatenates to the same characters.
        assertNotEquals(new FieldPath(List.of("a:b", "c")), new FieldPath(List.of("a", "b:c")));
        assertNotEquals(new FieldPath(List.of("a/b")).hashCode(),
                new FieldPath(List.of("a", "b")).hashCode());
        assertNotEquals(new FieldPath(List.of("a/b")), new FieldPath(List.of("a", "b")));
        assertNotEquals(new FieldPath(List.of("a", "b")), new FieldPath(List.of("a/b")));
        assertNotEquals(new FieldPath(List.of("a", "")), new FieldPath(List.of("a")));
        assertNotEquals(new FieldPath(List.of("")), FieldPath.root());
        assertEquals(new FieldPath(List.of("a:b", "c")), new FieldPath(List.of("a:b", "c")));
        assertEquals(new FieldPath(List.of("a:b", "c")).hashCode(),
                new FieldPath(List.of("a:b", "c")).hashCode());
    }

    @Test
    @DisplayName("every reserved character and the ordinary '=' survive encoding, decoding and identity")
    void separatorCharactersRoundTrip() {
        final List<List<String>> cases = List.of(
                List.of("~"),
                List.of("/"),
                List.of("="),
                List.of("~0"),
                List.of("~1"),
                List.of("~2"),
                List.of("~01"),
                List.of("a=b"),
                List.of("0~1"),
                List.of("/~", "~/"),
                List.of("a~b", "c/d"),
                List.of("//"),
                List.of("~~"),
                List.of("~/" + "~1" + "/~0"),
                List.of("", "", ""),
                List.of("a", "", "b"),
                List.of("~", "/", "=", "~0", "~1", "~2", "~01", "~/~"));
        for (List<String> segments : cases) {
            final FieldPath path = new FieldPath(segments);
            final String pointer = path.pointer();
            assertEquals(oraclePointer(segments), pointer, "oracle pointer for " + segments);
            assertEquals(oracleDecode(pointer), segments, "oracle decode for " + pointer);
            final FieldPath reparsed = FieldPath.fromPointer(pointer);
            assertEquals(segments, reparsed.segments(), "round trip through " + pointer);
            assertEquals(path, reparsed);
            assertEquals(path.hashCode(), reparsed.hashCode());
            assertEquals(pointer, reparsed.pointer());
        }
        assertEquals("/~0", new FieldPath(List.of("~")).pointer());
        assertEquals("/~1", new FieldPath(List.of("/")).pointer());
        assertEquals("/=", new FieldPath(List.of("=")).pointer());
        assertEquals("/~00", new FieldPath(List.of("~0")).pointer());
        assertEquals("/~01", new FieldPath(List.of("~1")).pointer());
        assertEquals("/~001", new FieldPath(List.of("~01")).pointer());
        // The escape alphabet is exactly {'0','1'}: no path can ever encode to a '~2' escape, and no
        // hand-written '~2' is ever accepted, so the two alphabets cannot drift apart.
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/~2"));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/a/~2"));
        for (List<String> segments : cases) {
            assertFalse(new FieldPath(segments).pointer().contains("~2"),
                    "no encoded pointer may contain a ~2 escape: " + segments);
            assertFalse(oraclePointer(segments).contains("~2"),
                    "the independent oracle must agree there is no ~2 escape: " + segments);
        }
    }

    @Test
    @DisplayName("pointer decoding handles every documented shape")
    void pointerDecodingShapes() {
        assertEquals(FieldPath.root(), FieldPath.fromPointer(""));
        assertEquals(0, FieldPath.fromPointer("").segments().size());
        assertTrue(FieldPath.fromPointer("").segments().isEmpty());

        assertEquals(List.of(""), FieldPath.fromPointer("/").segments());
        assertEquals(List.of("", ""), FieldPath.fromPointer("//").segments());
        assertEquals(List.of("", "", ""), FieldPath.fromPointer("///").segments());
        assertEquals(List.of("", "", "").size(), FieldPath.fromPointer("///").segments().size());
        assertEquals("/", FieldPath.fromPointer("/").pointer());
        assertEquals("", FieldPath.fromPointer("//").segments().get(0),
                "the text between the first and the second '/' of '//' is empty");
        assertEquals("", FieldPath.fromPointer("//").segments().get(1));
        assertEquals("//", FieldPath.fromPointer("//").pointer());
        assertEquals(oracleDecode("//"), List.of("", ""));
        // One slash inside a segment is only ever produced by the "~1" escape, never by a bare '/'.
        assertEquals(List.of("/"), FieldPath.fromPointer("/~1").segments());
        assertEquals("/~1", FieldPath.fromPointer("/~1").pointer());

        assertEquals(List.of("~1"), FieldPath.fromPointer("/~01").segments());
        assertEquals("/~01", FieldPath.fromPointer("/~01").pointer());
        assertEquals(List.of("~"), FieldPath.fromPointer("/~0").segments());
        assertEquals(List.of("/"), FieldPath.fromPointer("/~1").segments());
        assertEquals(List.of("a", "b"), FieldPath.fromPointer("/a/b").segments());
        assertEquals(List.of("0"), FieldPath.fromPointer("/0").segments());
        assertEquals("0", FieldPath.fromPointer("/0").segments().get(0));
        assertEquals(List.of("\u007e"), FieldPath.fromPointer("/" + "\u007e" + "0").segments());

        // Decoding is left to right and not recursive: ~01 is ~1, never ~ followed by 1.
        assertNotEquals(List.of("~", ""), FieldPath.fromPointer("/~01").segments());
        assertNotEquals(List.of("~", "1"), FieldPath.fromPointer("/~01").segments());

        // A dangling escape is rejected in every shape: an unescaped '~' is never legal text, so the
        // only two admitted spellings are "~0" and "~1" and a '~' that ends the pointer, ends a
        // segment, or is followed by '/' has no escape letter at all.
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/~"));    // ends the pointer
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/a~"));   // ends its segment
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/a~/b")); // followed by '/'
    }

    @Test
    @DisplayName("child appends one segment, never mutates the parent and respects the segment budget")
    void childIsAppendOnlyAndBounded() {
        final FieldPath parent = new FieldPath(List.of("a", "b"));
        final FieldPath child = parent.child("c");

        assertEquals(List.of("a", "b"), parent.segments(), "the parent must be unchanged");
        assertEquals("/a/b", parent.pointer());
        assertEquals(List.of("a", "b", "c"), child.segments());
        assertEquals("/a/b/c", child.pointer());
        assertNotSame(parent, child);
        assertNotEquals(parent, child);

        assertEquals(List.of(""), FieldPath.root().child("").segments());
        assertEquals(List.of("~1"), FieldPath.root().child("~1").segments());
        assertEquals("/~01", FieldPath.root().child("~1").pointer());
        assertEquals(List.of("0"), FieldPath.root().child("0").segments());
        assertEquals(1, FieldPath.root().child("a").segments().size());

        assertEquals(List.of("="), FieldPath.root().child("=").segments());
        assertEquals("/a=b", FieldPath.root().child("a=b").pointer(),
                "child keeps the ordinary character '=' verbatim");
        assertEquals(List.of("a=b"), FieldPath.fromPointer(FieldPath.root().child("a=b").pointer()).segments());

        assertEquals(List.of("~2"), new FieldPath(List.of("~2")).segments());
        assertEquals(List.of("~2"), FieldPath.fromPointer("/~02").segments(),
                "a literal '~2' is written '~02', never as an escape");
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/~2"));
        assertEquals(List.of("="), FieldPath.fromPointer("/=").segments());
        assertEquals("/=", FieldPath.fromPointer("/=").pointer(), "'=' is never escaped");
        FieldPath deep = FieldPath.root();
        for (int i = 0; i < FieldPath.MAX_SEGMENTS; i++) {
            deep = deep.child("seg" + i);
        }
        assertEquals(FieldPath.MAX_SEGMENTS, deep.segments().size());
        final FieldPath full = deep;
        assertThrows(IllegalArgumentException.class, () -> full.child("oneMore"));
        assertEquals(FieldPath.MAX_SEGMENTS, full.segments().size(), "a rejected child must not mutate the parent");
        assertEquals(deep, FieldPath.fromPointer(deep.pointer()));
        assertEquals(FieldPath.MAX_SEGMENTS, FieldPath.fromPointer(deep.pointer()).segments().size());
    }

    // ------------------------------------------------------------------
    // FieldPath: budgets and rejection without an unbounded allocation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("segment count is 65 accepted and 66 rejected, with no shallow copy first")
    void segmentCountBudget() {
        assertEquals(FieldPath.MAX_SEGMENTS,
                new FieldPath(sixtyFive("s")).segments().size());
        assertEquals(FieldPath.MAX_SEGMENTS,
                FieldPath.fromPointer(sixtyFivePointer()).segments().size());

        assertThrows(IllegalArgumentException.class, () -> new FieldPath(sixtySix("s")));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer(sixtyFivePointer() + "/x"));

        // The reported size is rejected before anything is iterated.
        final TripWireList<String> lying = new TripWireList<>(100, "x");
        assertThrows(IllegalArgumentException.class, () -> new FieldPath(lying));
        assertEquals(0, lying.reads(), "a rejected reported size must not advance the iterator");

        // A list that reports a small but false size is stopped at the 66th element.
        final TripWireList<String> endless = new TripWireList<>(1, "x");
        assertThrows(IllegalArgumentException.class, () -> new FieldPath(endless));
        assertEquals(FieldPath.MAX_SEGMENTS + 1, endless.reads());

        // Both List.copyOf-style paths are equally bounded; the honest distinct list is accepted and its
        // values really are copied. TripWireList is deliberately endless, so it can only ever prove
        // rejection (above), never acceptance.
        final CountingList counted = new CountingList(FieldPath.MAX_SEGMENTS);
        final FieldPath countedPath = new FieldPath(counted);
        assertEquals(FieldPath.MAX_SEGMENTS, countedPath.segments().size());
        assertEquals(FieldPath.MAX_SEGMENTS, counted.reads());
        assertEquals("s0", countedPath.segments().get(0));
        assertEquals("s" + (FieldPath.MAX_SEGMENTS - 1), countedPath.segments().get(FieldPath.MAX_SEGMENTS - 1));
        assertEquals(countedPath, FieldPath.fromPointer(countedPath.pointer()));

        final CountingList countedOver = new CountingList(FieldPath.MAX_SEGMENTS + 1);
        assertThrows(IllegalArgumentException.class, () -> new FieldPath(countedOver));
        assertEquals(0, countedOver.reads(),
                "an honest oversized size is rejected from size() alone, without reading one element");

        // The reported size is consulted exactly once and only as an early rejection: a list that
        // answers 0 to the first call and Integer.MAX_VALUE to any later call, while its iterator is
        // finite, must still be accepted. This is the contract's own vector for "size() is captured,
        // never re-read": re-reading it could reject a legal list, and (on a hostile list) allocate or
        // scan an unbounded container before the first element is even validated.
        final ChangingSizeList changing = new ChangingSizeList(List.of("a", "b", "c"));
        final FieldPath changingPath = new FieldPath(changing);
        assertEquals(List.of("a", "b", "c"), changingPath.segments());
        assertEquals("/a/b/c", changingPath.pointer());
        assertEquals(1, changing.sizeCalls(), "size() must be read exactly once per construction");

        // A list whose size() answers 0 and whose iterator is finite but far longer than the budget is
        // still stopped at the 66th element, because the iteration bound never comes from size().
        final ChangingSizeList longChanging = new ChangingSizeList(sixtySix("s"));
        assertThrows(IllegalArgumentException.class, () -> new FieldPath(longChanging));
        assertEquals(1, longChanging.sizeCalls());
        assertEquals(FieldPath.MAX_SEGMENTS + 1, longChanging.reads());
    }

    @Test
    @DisplayName("a segment of 1024 and 1025 UTF-8 bytes sits exactly on the budget")
    void segmentByteBudget() {
        final String ascii1024 = "a".repeat(1024);
        final String ascii1025 = "a".repeat(1025);
        final String twoByte1024 = "\u00e9".repeat(512);
        final String twoByte1026 = "\u00e9".repeat(513);
        // 341 three byte characters are 1023 bytes: the card says a segment is at most 1024 UTF-8
        // bytes, and this implementation can represent any string, so the accepted case here is the
        // 1024 byte one, spelt as 341 three byte characters plus one more byte.
        final String threeByte1024 = "\u4e2d".repeat(341) + "a";
        final String threeByte1026 = "\u4e2d".repeat(342);
        final String pair1024 = "\ud83d\ude00".repeat(256);
        final String pair1028 = "\ud83d\ude00".repeat(257);
        final String control1024 = "\n".repeat(1024);

        for (String accepted : new String[] {
            ascii1024, twoByte1024, threeByte1024, pair1024, control1024, "\u0000".repeat(1024)}) {
            assertEquals(1024, accepted.getBytes(StandardCharsets.UTF_8).length, "oracle width of the accepted case");
            final FieldPath path = new FieldPath(List.of(accepted));
            assertEquals(List.of(accepted), path.segments());
            assertEquals(accepted, FieldPath.fromPointer(path.pointer()).segments().get(0));
        }
        for (String rejected : new String[] {
            ascii1025, twoByte1026, threeByte1026, pair1028, "\u0000".repeat(1025)}) {
            final int width = rejected.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(width > 1024, "oracle width of the rejected case");
            assertThrows(IllegalArgumentException.class, () -> new FieldPath(List.of(rejected)));
            assertThrows(IllegalArgumentException.class, () -> FieldPath.root().child(rejected));
            assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/" + rejected));
        }

        // The same budget is what the frozen codec applies to a JSON object key, so the constants agree.
        assertEquals(ExactJson.MAX_KEY_UTF8_BYTES, 1024);
        assertEquals(1024, ExactJson.MAX_KEY_UTF8_BYTES);
    }

    @Test
    @DisplayName("illegal surrogates are rejected in construction, append and pointer decoding")
    void illegalSurrogatesAreRejected() {
        final String loneHigh = "a\ud83db";
        final String loneLow = "a\ude00b";
        final String trailingHigh = "a\ud83d";
        final String leadingLow = "\ude00a";

        for (String bad : new String[] {loneHigh, loneLow, trailingHigh, leadingLow}) {
            assertThrows(IllegalArgumentException.class, () -> new FieldPath(List.of(bad)));
            assertThrows(IllegalArgumentException.class, () -> FieldPath.root().child(bad));
            assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("/" + bad));
        }
        // A lone surrogate is not a character, so it has no UTF-8 width at all, and the JDK encoder
        // does not fail on it: it silently substitutes a replacement byte. The contract therefore
        // rejects it explicitly in all three entry points above instead of trusting a byte round trip.

        final String paired = "\u4e2d\ud83d\ude00~/\u0000\u007f=";
        final FieldPath path = new FieldPath(List.of(paired));
        assertEquals(paired, FieldPath.fromPointer(path.pointer()).segments().get(0));
        assertEquals(oraclePointer(List.of(paired)), path.pointer());
        assertEquals(oracleDecode(path.pointer()), List.of(paired));
    }

    @Test
    @DisplayName("an oversized pointer is rejected before it is scanned")
    void oversizedPointerIsRejectedEarly() {
        final int overLimit = EXPECTED_MAX_POINTER_CHARS + 1;
        final String huge = "/" + "a".repeat(overLimit - 1);
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer(huge));

        // A missing leading slash is rejected on the first character, not after scanning the text.
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("a".repeat(overLimit)));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("a"));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer("a/b"));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer(null));

        // A segment whose available characters already prove the byte budget is exceeded is rejected
        // without being decoded into a string: the text is exactly 1025 legal characters.
        assertThrows(IllegalArgumentException.class,
                () -> FieldPath.fromPointer("/" + "a".repeat(1025)));
        // One character of headroom is exactly the accepted boundary.
        assertEquals(1024, FieldPath.fromPointer("/" + "a".repeat(1024)).segments().get(0).length());

        // The pointer budget is an independent constant of the card, not a restatement of a production
        // field: 65 segments each holding 1024 decoded bytes that each cost two encoded characters is
        // exactly 65 * (2 * 1024 + 1) == 133185 characters and is accepted, while one character more is
        // rejected. 1024 ASCII bytes would only reach 66625 of those 133185 characters, so the maximal
        // legal pointer uses segments whose decoded form is 1024 bytes wide but two characters wide in
        // wire form, i.e. 1024 literal '~' characters written as '~0'.
        final String maximal = sixtyFiveEscapedPointer();
        assertEquals(EXPECTED_MAX_POINTER_CHARS, maximal.length());
        assertEquals(FieldPath.MAX_SEGMENTS, FieldPath.fromPointer(maximal).segments().size());
        assertEquals(ExactJson.MAX_KEY_UTF8_BYTES, FieldPath.fromPointer(maximal).segments().get(0).length());
        assertEquals(maximal, FieldPath.fromPointer(maximal).pointer());
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer(maximal + "a"));
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer(maximal + "/a"));
    }

    @Test
    @DisplayName("null segments and null input lists are rejected")
    void nullSegmentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FieldPath(null));
        assertThrows(IllegalArgumentException.class, () -> new FieldPath(twoSegmentsWithNull()));
        final FieldPath path = new FieldPath(List.of("a"));
        assertThrows(IllegalArgumentException.class, () -> path.child(null));
        assertEquals(List.of("a"), path.segments());
    }

    // ------------------------------------------------------------------
    // FieldPath: oracle over one thousand distinct generated segment lists
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one thousand generated paths round trip against the independent character oracle")
    void generatedPathsMatchOracle() {
        final Random random = new Random(SEED);
        final List<List<String>> generated = generatedSegments(random, GENERATED_PATHS);
        final List<String> pointers = new ArrayList<>(GENERATED_PATHS);
        final List<FieldPath> paths = new ArrayList<>(GENERATED_PATHS);
        int withTilde = 0;
        int withSlash = 0;
        int withEmpty = 0;
        int withEscapedInput = 0;
        int withEquals = 0;
        boolean noTildeTwo = true;

        for (int i = 0; i < generated.size(); i++) {
            final List<String> segments = generated.get(i);
            final FieldPath path = new FieldPath(segments);
            final String pointer = path.pointer();
            final String oracle = oraclePointer(segments);

            assertEquals(oracle, pointer, "generated pointer #" + i);
            assertEquals(oracleDecode(oracle), FieldPath.fromPointer(oracle).segments(),
                    "generated decode #" + i);
            assertEquals(segments, FieldPath.fromPointer(pointer).segments(), "generated round trip #" + i);
            assertEquals(path, FieldPath.fromPointer(path.pointer()));
            assertEquals(path.hashCode(), FieldPath.fromPointer(path.pointer()).hashCode());
            assertEquals(pointer, FieldPath.fromPointer(pointer).pointer());

            for (String segment : segments) {
                if (segment.indexOf('~') >= 0) {
                    withTilde++;
                    break;
                }
            }
            for (String segment : segments) {
                if (segment.indexOf('/') >= 0) {
                    withSlash++;
                    break;
                }
            }
            for (String segment : segments) {
                if (segment.isEmpty()) {
                    withEmpty++;
                    break;
                }
            }
            for (String segment : segments) {
                if (segment.contains("~0") || segment.contains("~1") || segment.contains("~2")) {
                    withEscapedInput++;
                    break;
                }
            }
            noTildeTwo &= !pointer.contains("~2");
            for (String segment : segments) {
                if (segment.indexOf('=') >= 0) {
                    withEquals++;
                    break;
                }
            }
            pointers.add(pointer);
            paths.add(path);
        }

        assertEquals(GENERATED_PATHS, pointers.size());
        assertEquals(GENERATED_PATHS, new java.util.HashSet<>(pointers).size(),
                "all generated segment lists must be distinct");
        assertEquals(GENERATED_PATHS, new java.util.HashSet<>(paths).size());
        assertTrue(withTilde > 100, "the generator must cover '~', saw " + withTilde);
        assertTrue(withSlash > 100, "the generator must cover '/', saw " + withSlash);
        assertTrue(withEmpty > 100, "the generator must cover the empty segment, saw " + withEmpty);
        assertTrue(withEscapedInput > 100, "the generator must cover segment text that looks escaped, saw "
                + withEscapedInput);
        assertTrue(withEquals > 100, "the generator must cover the ordinary character '=', saw " + withEquals);
        assertTrue(noTildeTwo, "no generated pointer may contain a ~2 escape");

        // Character level collisions: two distinct segment lists may never share one pointer.
        for (int i = 0; i < 200; i++) {
            final List<String> left = generated.get(i);
            final List<String> right = generated.get(i + 1);
            assertNotEquals(left, right);
            assertNotEquals(paths.get(i).pointer(), paths.get(i + 1).pointer());
        }
    }

    // ------------------------------------------------------------------
    // SchemaException
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both constructors keep code, path, message and cause identity")
    void schemaExceptionKeepsIdentity() {
        final FieldPath path = new FieldPath(List.of("a", "~1", ""));
        final String message = "field \"a\" has the wrong type, expected object, got array \u4e2d\u6587";
        final IllegalArgumentException cause = new IllegalArgumentException("real underlying failure");

        final SchemaException without = new SchemaException(SchemaException.CODE_WRONG_TYPE, path, message);
        assertEquals(SchemaException.CODE_WRONG_TYPE, without.code());
        assertSame(path, without.path());
        assertEquals(message, without.getMessage());
        assertEquals(null, without.getCause());
        assertInstanceOf(IllegalArgumentException.class, without);

        final SchemaException with = new SchemaException(SchemaException.CODE_WRONG_TYPE, path, message, cause);
        assertEquals(SchemaException.CODE_WRONG_TYPE, with.code());
        assertSame(path, with.path());
        assertEquals(message, with.getMessage());
        assertSame(cause, with.getCause(), "the cause must be the very object supplied");
        assertEquals("real underlying failure", with.getCause().getMessage());

        final SchemaException nullCause =
                new SchemaException(SchemaException.CODE_WRONG_TYPE, path, message, null);
        assertEquals(null, nullCause.getCause());
        assertEquals(without.getMessage(), nullCause.getMessage());
        assertEquals(without.code(), nullCause.code());
        assertSame(path, nullCause.path());

        assertEquals("", new SchemaException(SchemaException.CODE_MISSING_FIELD, FieldPath.root(), "").getMessage());
        assertEquals(FieldPath.root(), new SchemaException(SchemaException.CODE_MISSING_FIELD, FieldPath.root(), "")
                .path());
    }

    @Test
    @DisplayName("every fixed code and every in-shape code is accepted, out-of-shape codes are rejected")
    void schemaExceptionCodeContract() {
        final String[] fixed = {
            SchemaException.CODE_MISSING_FIELD, SchemaException.CODE_WRONG_TYPE,
            SchemaException.CODE_INVALID_VALUE, SchemaException.CODE_UNSUPPORTED_SCHEMA,
            SchemaException.CODE_RESERVED_COLLISION, SchemaException.CODE_INVALID_JSON,
            SchemaException.CODE_INVALID_NUMBER, SchemaException.CODE_INVALID_UNICODE,
            SchemaException.CODE_DUPLICATE_KEY, SchemaException.CODE_LIMIT_EXCEEDED,
        };
        assertEquals(10, fixed.length);
        assertEquals("MISSING_FIELD", SchemaException.CODE_MISSING_FIELD);
        assertEquals("WRONG_TYPE", SchemaException.CODE_WRONG_TYPE);
        assertEquals("INVALID_VALUE", SchemaException.CODE_INVALID_VALUE);
        assertEquals("UNSUPPORTED_SCHEMA", SchemaException.CODE_UNSUPPORTED_SCHEMA);
        assertEquals("RESERVED_COLLISION", SchemaException.CODE_RESERVED_COLLISION);
        assertEquals("INVALID_JSON", SchemaException.CODE_INVALID_JSON);
        assertEquals("INVALID_NUMBER", SchemaException.CODE_INVALID_NUMBER);
        assertEquals("INVALID_UNICODE", SchemaException.CODE_INVALID_UNICODE);
        assertEquals("DUPLICATE_KEY", SchemaException.CODE_DUPLICATE_KEY);
        assertEquals("LIMIT_EXCEEDED", SchemaException.CODE_LIMIT_EXCEEDED);
        for (String code : fixed) {
            assertTrue(CODE_SHAPE.matcher(code).matches(), code);
            final SchemaException failure = new SchemaException(code, FieldPath.root(), "detail");
            assertEquals(code, failure.code());
            assertNotNull(failure.code());
        }

        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        for (String code : CODE_ALPHABET) {
            final boolean inShape = code != null && CODE_SHAPE.matcher(code).matches();
            if (inShape) {
                final SchemaException failure =
                        new SchemaException(code, FieldPath.root(), "detail");
                assertEquals(code, failure.code());
                assertTrue(failure.code().matches("[A-Z][A-Z0-9_]{0,63}"));
                accepted.incrementAndGet();
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new SchemaException(code, FieldPath.root(), "detail"),
                        "code outside the fixed shape: " + code);
                rejected.incrementAndGet();
            }
        }
        assertEquals(CODE_ALPHABET.length, accepted.get() + rejected.get());
        assertTrue(accepted.get() >= 20, "accepted=" + accepted.get());
        assertTrue(rejected.get() >= 10, "rejected=" + rejected.get());
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaException(null, FieldPath.root(), "detail"));
    }

    @Test
    @DisplayName("null path and null message are rejected instead of being defaulted")
    void schemaExceptionRejectsNulls() {
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaException("MISSING_FIELD", null, "detail"));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaException("MISSING_FIELD", null, "detail", new IllegalStateException("cause")));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaException(null, FieldPath.root(), "detail", new IllegalStateException("cause")));
        // A null message is rejected with the same exception family as every other null argument, in
        // both constructors, so the caller can never receive a failure report with a fabricated text.
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaException("MISSING_FIELD", FieldPath.root(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaException("MISSING_FIELD", FieldPath.root(), null, new IllegalStateException("c")));
        // An empty message is a real, legal message: it is distinguishable from a missing one.
        assertEquals("", new SchemaException("MISSING_FIELD", FieldPath.root(), "").getMessage());
        // The failure never fabricates a root path or a code for the caller.
        final FieldPath deep = new FieldPath(List.of("x", "y"));
        final SchemaException failure = new SchemaException("INVALID_VALUE", deep, "\u4e2d\u6587\u6d88\u606f");
        assertSame(deep, failure.path());
        assertNotEquals(FieldPath.root(), failure.path());
        assertEquals("\u4e2d\u6587\u6d88\u606f", failure.getMessage());
        assertTrue(failure.getMessage().contains("\u4e2d\u6587"));
    }

    // ------------------------------------------------------------------
    // ContentDigest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the digest is lowercase SHA-256 over the complete canonical UTF-8 bytes")
    void digestMatchesIndependentJdkOracle() throws Exception {
        assertEquals("exact-json-r1", ContentDigest.ALGORITHM);

        final String[][] cases = {
            // Each entry is: source JSON text, and the canonical text this test prepares beforehand.
            {"null", "null"},
            {"{}", "{}"},
            {"[]", "[]"},
            {"{\"a\":1}", "{\"a\":1}"},
            {"{\"a\":1,\"b\":[1,2,3]}", "{\"a\":1,\"b\":[1,2,3]}"},
            {"{\"b\":1,\"a\":2}", "{\"a\":2,\"b\":1}"},
            {"{\"a\":\"\u4e2d\u6587\u00e9\\u0000\\n\"}", "{\"a\":\"\u4e2d\u6587\u00e9\\u0000\\n\"}"},
            {"{\"k\":\"\ud83d\ude00\"}", "{\"k\":\"\ud83d\ude00\"}"},
            {"1", "1"},
            {"1.0", "1"},
            {"1e0", "1"},
            {"0.5", "0.5"},
            {"true", "true"},
            {"false", "false"},
            {"{\"\":null}", "{\"\":null}"},
            {"[null,false,true,\"\"]", "[null,false,true,\"\"]"},
        };
        for (String[] entry : cases) {
            final ExactJson.FrozenJson payload = ExactJson.parse(entry[0]);
            assertEquals(entry[1], payload.canonical(), "canonical text of " + entry[0]);
            final String expected =
                    jdkSha256(payload.canonical().getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, ContentDigest.sha256(payload), "digest of " + entry[0]);
            assertTrue(ContentDigest.sha256(payload).matches("[0-9a-f]{64}"),
                    "digest must be 64 lowercase hex characters");
            assertEquals(64, ContentDigest.sha256(payload).length());
        }

        // The documented reference value of the empty canonical text, prepared by this test itself.
        assertEquals("{}", ExactJson.parse("{}").canonical());
        assertEquals(jdkSha256("{}".getBytes(StandardCharsets.UTF_8)), ContentDigest.sha256(ExactJson.parse("{}")));
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                ContentDigest.sha256(ExactJson.parse("{}")),
                "independent JDK oracle of the canonical bytes of an empty object");
        assertEquals("74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
                ContentDigest.sha256(ExactJson.parse("null")),
                "independent JDK oracle of the canonical bytes of JSON null");
    }

    @Test
    @DisplayName("object insertion order cannot change the digest and unknown members participate in it")
    void digestCoversUnknownMembersAndIgnoresInsertionOrder() throws Exception {
        final String left = "{\"schemaVersion\":1,\"extras\":{\"b\":2,\"a\":1},\"name\":\"x\",\"z\":[1,2]}";
        final String right = "{\"z\":[1,2],\"name\":\"x\",\"extras\":{\"a\":1,\"b\":2},\"schemaVersion\":1}";
        final String[] members = {
            "{\"schemaVersion\":1,\"name\":\"x\",\"z\":[1,2],\"extras\":{\"a\":1,\"b\":2}}",
            "{\"schemaVersion\":1,\"name\":\"x\",\"z\":[1,2],\"extras\":{\"a\":1,\"b\":3}}",
            "{\"schemaVersion\":1,\"name\":\"x\",\"z\":[1,2],\"extras\":{\"a\":1,\"b\":2,\"c\":0}}",
            "{\"schemaVersion\":1,\"name\":\"x\",\"z\":[1,3],\"extras\":{\"a\":1,\"b\":2}}",
            "{\"schemaVersion\":1,\"name\":\"y\",\"z\":[1,2],\"extras\":{\"a\":1,\"b\":2}}",
            "{\"schemaVersion\":2,\"name\":\"x\",\"z\":[1,2],\"extras\":{\"a\":1,\"b\":2}}",
        };
        final String baseDigest = ContentDigest.sha256(ExactJson.parse(members[0]));

        assertEquals(ExactJson.parse(members[0]).canonical(), ExactJson.parse(left).canonical());
        assertEquals(ExactJson.parse(members[0]).canonical(), ExactJson.parse(right).canonical());
        assertEquals(baseDigest, ContentDigest.sha256(ExactJson.parse(left)));
        assertEquals(baseDigest, ContentDigest.sha256(ExactJson.parse(right)));

        for (int i = 1; i < members.length; i++) {
            final String digest = ContentDigest.sha256(ExactJson.parse(members[i]));
            assertNotEquals(baseDigest, digest, "member change #" + i + " must change the digest");
            assertEquals(jdkSha256(ExactJson.parse(members[i]).canonical().getBytes(StandardCharsets.UTF_8)),
                    digest);
        }
    }

    @Test
    @DisplayName("numbers with the same value share a digest and a fifty digit difference does not")
    void digestOfNumericEquivalence() throws Exception {
        final String[] sameValue = {"1", "1.0", "1e0", "1.00", "10e-1", "0.1e1", "1E0"};
        final String reference = ContentDigest.sha256(ExactJson.parse("1"));
        for (String literal : sameValue) {
            assertEquals("1", ExactJson.parse(literal).canonical(), "canonical of " + literal);
            assertEquals(reference, ContentDigest.sha256(ExactJson.parse(literal)), "digest of " + literal);
        }

        final String fiftyA = "12345678901234567890123456789012345678901234567890";
        final String fiftyB = "12345678901234567890123456789012345678901234567891";
        assertEquals(50, fiftyA.length());
        assertEquals(50, fiftyB.length());
        assertNotEquals(ExactJson.parse(fiftyA).canonical(), ExactJson.parse(fiftyB).canonical());
        assertNotEquals(ContentDigest.sha256(ExactJson.parse(fiftyA)),
                ContentDigest.sha256(ExactJson.parse(fiftyB)));
        assertNotEquals(ContentDigest.sha256(ExactJson.parse("[" + fiftyA + "]")),
                ContentDigest.sha256(ExactJson.parse("[" + fiftyB + "]")));

        assertEquals(jdkSha256(ExactJson.parse("-0").canonical().getBytes(StandardCharsets.UTF_8)),
                ContentDigest.sha256(ExactJson.parse("-0")));
        assertEquals(ContentDigest.sha256(ExactJson.parse("0")), ContentDigest.sha256(ExactJson.parse("-0")));
        assertEquals(ContentDigest.sha256(ExactJson.parse("-0")), ContentDigest.sha256(ExactJson.parse("0e3")));
    }

    @Test
    @DisplayName("every canonical byte participates in the digest")
    void everyByteParticipates() throws Exception {
        final JsonObject payload = new JsonObject();
        final JsonArray array = new JsonArray();
        for (int i = 0; i < 32; i++) {
            array.add(new JsonPrimitive((char) i));
        }
        payload.add("control", array);
        payload.add("text", new JsonPrimitive("\u0000\u0001\u001f !\"\\/~\u00e9\u4e2d\ud83d\ude00"));
        payload.add("nested", ExactJson.parse("{\"a\":[1,2,{\"b\":null}],\"c\":{\"d\":true}}").toJson());
        final ExactJson.FrozenJson frozen = ExactJson.freeze(payload);
        final byte[] canonical = frozen.canonicalUtf8();
        assertEquals(frozen.utf8Size(), canonical.length);
        assertEquals(frozen.canonical(), frozen.toString());
        assertEquals(jdkSha256(canonical), ContentDigest.sha256(frozen));

        final int reference = jdkSha256(canonical).hashCode();
        assertTrue(canonical.length > 200, "the canonical sample must be long enough, got " + canonical.length);
        int mutations = 0;
        int distinct = 0;
        for (int i = 0; i < canonical.length; i++) {
            final byte[] mutated = canonical.clone();
            mutated[i] = (byte) (mutated[i] ^ 0x01);
            final byte[] hashed = jdkSha256Bytes(mutated);
            if (!java.util.Arrays.equals(hashed, jdkSha256Bytes(canonical))) {
                distinct++;
                assertNotEquals(jdkSha256(canonical), jdkSha256(mutated), "byte " + i + " must participate");
            }
            mutations++;
        }
        assertEquals(canonical.length, mutations);
        assertEquals(canonical.length, distinct, "every single byte mutation must change the digest input");
        assertEquals(reference, jdkSha256(canonical).hashCode());
        assertTrue(mutations > 200, "the canonical sample must be long enough, got " + mutations);
    }

    @Test
    @DisplayName("one thousand distinct payloads produce one thousand distinct digests")
    void generatedDigestsAreDistinct() throws Exception {
        final Random random = new Random(SEED + 1);
        final java.util.Set<String> digests = new java.util.HashSet<>();
        final java.util.Set<String> canonicals = new java.util.HashSet<>();
        int unknownHeavy = 0;
        int unicodeHeavy = 0;

        for (int i = 0; i < GENERATED_DIGESTS; i++) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("schemaVersion", 1);
            payload.addProperty("name", "map-" + i + "-" + random.nextInt(1000000));
            payload.addProperty("index", i);
            payload.addProperty("ratio", (i % 7) + "." + (i % 3));
            payload.add("known", ExactJson.parse("[" + (i % 11) + ",{\"k\":" + (i * 3) + "}]").toJson());
            if (i % 3 == 0) {
                payload.addProperty("unknownField", "\u00e9\u4e2d\ud83d\ude00-" + i);
                unknownHeavy++;
            }
            if (i % 5 == 0) {
                payload.addProperty("control", "\n\t\u0000");
                unicodeHeavy++;
            }
            if (i % 4 == 0) {
                payload.add("flags", ExactJson.parse("[true,false,null]").toJson());
            }
            final ExactJson.FrozenJson frozen = ExactJson.freeze(payload);
            final String digest = ContentDigest.sha256(frozen);
            final String oracle = jdkSha256(frozen.canonical().getBytes(StandardCharsets.UTF_8));

            assertEquals(oracle, digest, "oracle digest of generated payload #" + i);
            assertTrue(digest.matches("[0-9a-f]{64}"));
            assertEquals(64, digest.length());
            assertTrue(canonicals.add(frozen.canonical()), "generated canonical #" + i + " must be distinct");
            assertTrue(digests.add(digest), "generated digest #" + i + " must be distinct");
        }
        assertEquals(GENERATED_DIGESTS, digests.size());
        assertEquals(GENERATED_DIGESTS, canonicals.size());
        assertTrue(unknownHeavy > 300, "unknown field coverage " + unknownHeavy);
        assertTrue(unicodeHeavy > 150, "control coverage " + unicodeHeavy);
    }

    @Test
    @DisplayName("the digest refuses a null payload and never mutates or reveals the frozen bytes")
    void digestRejectsNullAndKeepsThePayloadIntact() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ContentDigest.sha256(null));

        final ExactJson.FrozenJson frozen = ExactJson.parse("{\"a\":[1,2,{\"b\":\"x\"}]}");
        final byte[] before = frozen.canonicalUtf8();
        final String first = ContentDigest.sha256(frozen);
        final String second = ContentDigest.sha256(frozen);
        assertEquals(first, second, "a repeated digest of the same frozen payload must be stable");
        assertBytesEqual(before, frozen.canonicalUtf8());

        final byte[] exposed = frozen.canonicalUtf8();
        exposed[0] = (byte) 'X';
        assertEquals(first, ContentDigest.sha256(frozen), "mutating a returned array must not affect the payload");
        assertFalse(frozen.canonical().startsWith("X"));
        assertEquals(jdkSha256(frozen.canonical().getBytes(StandardCharsets.UTF_8)), first);
    }

    // ------------------------------------------------------------------
    // helpers: independent oracles and hostile input lists
    // ------------------------------------------------------------------

    /** Independent pointer encoder: one character walk with no shared code with the production type. */
    private static String oraclePointer(List<String> segments) {
        final StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            out.append(SLASH);
            for (int i = 0; i < segment.length(); i++) {
                final char c = segment.charAt(i);
                if (c == TILDE) {
                    out.append('~').append('0');
                } else if (c == SLASH) {
                    out.append('~').append('1');
                } else {
                    // Every other character, '=' included, is kept verbatim: there is no third escape.
                    out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Independent pointer decoder: one character walk producing the expected segment list. */
    private static List<String> oracleDecode(String pointer) {
        final List<String> out = new ArrayList<>();
        if (pointer.isEmpty()) {
            return out;
        }
        int cursor = 0;
        while (cursor < pointer.length()) {
            if (pointer.charAt(cursor) != SLASH) {
                throw new IllegalArgumentException("oracle: expected '/' at " + cursor);
            }
            cursor++;
            final StringBuilder segment = new StringBuilder();
            while (cursor < pointer.length() && pointer.charAt(cursor) != SLASH) {
                final char c = pointer.charAt(cursor);
                if (c == TILDE) {
                    final char escaped = pointer.charAt(cursor + 1);
                    if (escaped == '0') {
                        segment.append(TILDE);
                    } else if (escaped == '1') {
                        segment.append(SLASH);
                    } else {
                        // '~2' and every other '~x' are rejected: the escape alphabet is exactly
                        // {'0','1'}, so '=' is never produced by an escape.
                        throw new IllegalArgumentException("oracle: illegal escape at " + cursor);
                    }
                    cursor += 2;
                } else {
                    segment.append(c);
                    cursor++;
                }
            }
            out.add(segment.toString());
        }
        return out;
    }

    /** Independent JDK SHA-256 oracle: its own instance, its own lowercase hex spelling. */
    private static String jdkSha256(byte[] bytes) throws NoSuchAlgorithmException {
        final String text = hexLower(jdkSha256Bytes(bytes));
        assertEquals(64, text.length());
        assertEquals(text.toLowerCase(java.util.Locale.ROOT), text);
        return text;
    }

    /** The raw JDK digest bytes, used to prove that a mutated input reaches the JDK digest at all. */
    private static byte[] jdkSha256Bytes(byte[] bytes) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    /** Lowercase hexadecimal spelling of a byte array. */
    private static String hexLower(byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            final String part = Integer.toHexString(b & 0xFF);
            if (part.length() == 1) {
                hex.append('0');
            }
            hex.append(part);
        }
        return hex.toString();
    }

    /** Builds a deterministic set of distinct segment lists over the interesting character classes. */
    private static List<List<String>> generatedSegments(Random random, int count) {
        final List<List<String>> generated = new ArrayList<>(count);
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final String[] fixedBodies = {
            "", "a", "b", "0", "12", "~", "/", "~/", "~0", "~1", "~01", "0~1", "//", "~~", "a~b", "c/d",
            ".", ":", " ", "\t", "\n", "\u0000", "\u001f", "\u007f", "\u00e9", "\u4e2d", "\ud83d\ude00",
            "\u00e9\u4e2d\ud83d\ude00", "a.b", "a:b", "a/b", "a~b~c", "emoji\ud83c\udf10", "\ud83c\udf10\ud83c\udf10",
            "=", "a=b", "~2", "a~2b", "=a=",
        };
        final char[] alphabet =
                {'~', '/', '0', '1', 'a', 'b', '.', ':', ' ', '\t', '\u0000', '\u00e9', '\u4e2d', '='};

        int guard = 0;
        while (generated.size() < count) {
            guard++;
            if (guard > count * 50) {
                throw new IllegalStateException("generator failed to reach the required distinct count");
            }
            final int size = random.nextInt(3) + 1;
            final List<String> segments = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final int kind = random.nextInt(5);
                if (kind == 0) {
                    segments.add(fixedBodies[random.nextInt(fixedBodies.length)]);
                } else if (kind == 1) {
                    final int length = random.nextInt(6);
                    final StringBuilder body = new StringBuilder();
                    for (int j = 0; j < length; j++) {
                        body.append(alphabet[random.nextInt(alphabet.length)]);
                    }
                    segments.add(body.toString());
                } else if (kind == 2) {
                    if (random.nextBoolean()) {
                        // Force the empty segment to appear often enough for the coverage floor below.
                        segments.add("");
                    } else {
                        segments.add("seg" + random.nextInt(1000));
                    }
                } else if (kind == 3) {
                    if (random.nextBoolean()) {
                        // '=' is an ordinary character, but it must still be covered often enough for
                        // the coverage floor below.
                        segments.add("a=b" + random.nextInt(10));
                    } else {
                        segments.add("k" + random.nextInt(100));
                    }
                } else {
                    segments.add(fixedBodies[random.nextInt(fixedBodies.length)]
                            + alphabet[random.nextInt(alphabet.length)]
                            + fixedBodies[random.nextInt(fixedBodies.length)]);
                }
            }
            final String key = segments.toString();
            if (seen.add(key)) {
                generated.add(segments);
            }
        }
        return generated;
    }

    /**
     * A list that reports one size, yields an endless stream of elements, and counts how many elements
     * were actually read. It fails any copy that trusts the reported size, which is exactly the vector
     * the contract must survive.
     */
    private static final class TripWireList<T> extends java.util.AbstractList<T> {

        private final int reportedSize;

        private final T element;

        private final AtomicInteger reads = new AtomicInteger();

        TripWireList(int reportedSize, T element) {
            this.reportedSize = reportedSize;
            this.element = element;
        }

        int reads() {
            return this.reads.get();
        }

        @Override
        public T get(int index) {
            this.reads.incrementAndGet();
            return this.element;
        }

        @Override
        public int size() {
            return this.reportedSize;
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<T>() {
                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public T next() {
                    reads.incrementAndGet();
                    return element;
                }
            };
        }
    }

    /** An honest list of distinct segment values that counts every element it hands out. */
    private static final class CountingList extends java.util.AbstractList<String> {

        private final int size;

        private final AtomicInteger reads = new AtomicInteger();

        CountingList(int size) {
            this.size = size;
        }

        int reads() {
            return this.reads.get();
        }

        @Override
        public String get(int index) {
            this.reads.incrementAndGet();
            return "s" + index;
        }

        @Override
        public int size() {
            return this.size;
        }
    }

    /**
     * A finite list that lies about its size: the first {@code size()} answers {@code 0} and every
     * later call answers {@link Integer#MAX_VALUE}, while the iterator is honest and finite. It counts
     * both size calls and element reads, so a construction that re-reads {@code size()} is observable
     * rather than merely suspected.
     */
    private static final class ChangingSizeList extends java.util.AbstractList<String> {

        private final List<String> backing;

        private final AtomicInteger sizeCalls = new AtomicInteger();

        private final AtomicInteger reads = new AtomicInteger();

        ChangingSizeList(List<String> backing) {
            this.backing = List.copyOf(backing);
        }

        int sizeCalls() {
            return this.sizeCalls.get();
        }

        int reads() {
            return this.reads.get();
        }

        @Override
        public String get(int index) {
            this.reads.incrementAndGet();
            return this.backing.get(index);
        }

        @Override
        public int size() {
            return this.sizeCalls.getAndIncrement() == 0 ? 0 : Integer.MAX_VALUE;
        }

        @Override
        public Iterator<String> iterator() {
            final Iterator<String> inner = this.backing.iterator();
            return new Iterator<String>() {
                @Override
                public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override
                public String next() {
                    reads.incrementAndGet();
                    return inner.next();
                }
            };
        }
    }

    /**
     * The longest legal pointer: {@code MAX_SEGMENTS} segments whose decoded form is exactly 1024
     * UTF-8 bytes and whose wire form costs exactly two characters per byte. Each segment is 1024
     * literal {@code '~'} characters, i.e. 1024 UTF-8 bytes, written as 1024 {@code "~0"} escapes, so
     * its encoded width is 2048 characters. With the leading {@code '/'} that is 2049 characters per
     * segment, so the independent maximal length is 65 * (2 * 1024 + 1) == 133185, which is exactly
     * the card's budget.
     */
    private static String sixtyFiveEscapedPointer() {
        final StringBuilder segment = new StringBuilder(2048);
        for (int i = 0; i < ExactJson.MAX_KEY_UTF8_BYTES; i++) {
            segment.append("~0");
        }
        final StringBuilder pointer = new StringBuilder(EXPECTED_MAX_POINTER_CHARS);
        for (int i = 0; i < FieldPath.MAX_SEGMENTS; i++) {
            pointer.append('/').append(segment);
        }
        return pointer.toString();
    }

    private static List<String> sixtyFive(String prefix) {
        final List<String> segments = new ArrayList<>(FieldPath.MAX_SEGMENTS);
        for (int i = 0; i < FieldPath.MAX_SEGMENTS; i++) {
            segments.add(prefix + i);
        }
        return segments;
    }

    private static List<String> sixtySix(String prefix) {
        final List<String> segments = sixtyFive(prefix);
        segments.add(prefix + FieldPath.MAX_SEGMENTS);
        assertEquals(FieldPath.MAX_SEGMENTS + 1, segments.size());
        return segments;
    }

    private static String sixtyFivePointer() {
        final StringBuilder pointer = new StringBuilder();
        for (int i = 0; i < FieldPath.MAX_SEGMENTS; i++) {
            pointer.append("/s").append(i);
        }
        return pointer.toString();
    }

    private static List<String> twoSegmentsWithNull() {
        final List<String> segments = new ArrayList<>();
        segments.add("a");
        segments.add(null);
        return segments;
    }

    /** Byte comparison with a per index report; avoids depending on an array assertion overload. */
    private static void assertBytesEqual(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte " + i);
        }
    }
}
