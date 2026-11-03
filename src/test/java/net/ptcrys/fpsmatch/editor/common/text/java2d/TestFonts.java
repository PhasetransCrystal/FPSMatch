package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.ptcrys.fpsmatch.editor.api.EditorId;

/**
 * Loads real host fonts for the behavioural tests. Every test asserts an observable behaviour and
 * fails clearly when none of the candidate fonts exists, instead of silently skipping the case.
 *
 * <p>Italic faces are always paired with the upright face of the same family, because
 * {@code Font.createFont} reports every created face as plain ({@code isItalic()} is false and
 * {@code getItalicAngle()} is zero even for a real italic file), so the only reliable host-side
 * evidence is the measured ink of the two faces. An upright face such as Segoe UI already puts a
 * negative bearing on {@code j}, which is why a test that simply asserts a negative bearing would
 * pass without testing italic rendering at all.</p>
 */
final class TestFonts {
    private static final String FONTS = "C:/Windows/Fonts";
    private static final List<String> LATIN_CANDIDATES = List.of("segoeui.ttf", "arial.ttf", "verdana.ttf");
    private static final List<String> CJK_CANDIDATES = List.of("msyh.ttc", "msyh.ttf", "simsun.ttc",
            "gulim.ttc", "malgun.ttf", "batang.ttc");
    /** Italic face and the upright face of the same family, tried in order. */
    private static final List<String[]> ITALIC_SIBLINGS = List.of(
            new String[] {"segoeuii.ttf", "segoeui.ttf"},
            new String[] {"ariali.ttf", "arial.ttf"},
            new String[] {"verdanai.ttf", "verdana.ttf"},
            new String[] {"georgiai.ttf", "georgia.ttf"},
            new String[] {"timesi.ttf", "times.ttf"},
            new String[] {"cambriai.ttf", "cambria.ttf"});

    private TestFonts() {
    }

    /** An italic face together with its upright sibling, so tests can tell the two designs apart. */
    record ItalicSiblings(String italicFile, byte[] italicBytes, String uprightFile, byte[] uprightBytes) {
    }

    /** Bytes of the first existing candidate; fails loudly when none exists. */
    static byte[] read(List<String> candidates, String purpose) {
        List<Path> tried = new ArrayList<>();
        for (String name : candidates) {
            Path path = Path.of(FONTS, name);
            tried.add(path);
            if (Files.isRegularFile(path)) {
                return readFile(path);
            }
        }
        fail("no " + purpose + " font found, tried " + tried);
        return new byte[0];
    }

    private static byte[] readFile(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new AssertionError("cannot read font " + path, failure);
        }
    }

    static byte[] latin() {
        return read(LATIN_CANDIDATES, "latin TrueType");
    }

    /** A font with a large glyph repertoire, used as fallback and as a visibly different face. */
    static byte[] wide() {
        return read(CJK_CANDIDATES, "CJK");
    }

    /** The first italic face that also has its upright sibling on this host. */
    static ItalicSiblings italicSiblings() {
        for (String[] pair : ITALIC_SIBLINGS) {
            Path italic = Path.of(FONTS, pair[0]);
            Path upright = Path.of(FONTS, pair[1]);
            if (Files.isRegularFile(italic) && Files.isRegularFile(upright)) {
                return new ItalicSiblings(pair[0], readFile(italic), pair[1], readFile(upright));
            }
        }
        fail("no italic face with an upright sibling found, tried " + ITALIC_SIBLINGS);
        return null;
    }

    /** Bytes of the italic face used by the negative bearing test. */
    static byte[] italic() {
        return italicSiblings().italicBytes();
    }

    /**
     * Finds a printable glyph whose ink starts left of the origin in the italic face and clearly
     * further left than in the upright face of the same family, so rasterizing it proves that the
     * italic design's negative left bearing survives instead of being clipped.
     */
    static String findOverhangingGlyph(Font italic, Font upright, float size) {
        FontRenderContext context = new FontRenderContext(null, true, true);
        Font sizedItalic = italic.deriveFont(size);
        Font sizedUpright = upright.deriveFont(size);
        for (char candidate = 0x21; candidate <= 0x7E; candidate++) {
            String probe = String.valueOf(candidate);
            float italicMin = inkMinX(sizedItalic, probe, context);
            float uprightMin = inkMinX(sizedUpright, probe, context);
            if (italicMin < -0.5f && italicMin < uprightMin - 0.5f) {
                return probe;
            }
        }
        fail("no glyph overhangs the origin only in the italic face of " + italic.getFontName());
        return "j";
    }

    /** Left edge of the ink of {@code probe}, measured on the host with the backend's hints. */
    static float inkMinX(Font font, String probe, FontRenderContext context) {
        TextLayout layout = new TextLayout(probe, font, context);
        return (float) layout.getBlackBoxBounds(0, layout.getCharacterCount()).getBounds2D().getMinX();
    }

    /** Parses font bytes for host-side inspection; the backend never sees this object. */
    static Font inspect(byte[] bytes) {
        try (java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(bytes)) {
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (java.awt.FontFormatException | IOException failure) {
            throw new AssertionError("host font cannot be parsed for inspection", failure);
        }
    }

    /**
     * Finds a single BMP code point that {@code primary} can display and {@code fallback} cannot, so
     * the fallback path is proven by observable font selection rather than by luck.
     */
    static char findUncoveredBy(Font primary, Font fallback) {
        for (int codePoint = 0x20; codePoint <= 0xFFFF; codePoint++) {
            if (Character.isSurrogate((char) codePoint)) {
                continue;
            }
            if (primary.canDisplay(codePoint) && !fallback.canDisplay(codePoint)) {
                return (char) codePoint;
            }
        }
        fail("no code point distinguishes the two fonts");
        return ' ';
    }

    static EditorId id(String path) {
        return EditorId.parse("test:" + path);
    }
}
