package net.ptcrys.fpsmatch.editor.common.text.java2d;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Java2D backed single line text backend.
 *
 * <p>
 * This is the only module of the platform that touches {@code java.desktop}. Fonts are supplied
 * as byte arrays, explicit installed family names or JDK logical family names; the backend never reads a path given by
 * the caller,
 * never falls back to an arbitrary system font silently, and never returns an AWT object. Instances
 * are safe for concurrent use: registration swaps an immutable snapshot, and layout only reads that
 * snapshot.
 * </p>
 *
 * <p>
 * Scope of this stage: one line, no wrapping, no IME and no caret animation. Layout rejects
 * {@code CR} and {@code LF}. Shaping, bidi reordering and metrics come from one {@link TextLayout}
 * built over an {@link AttributedString} that assigns a font to every cluster: adjacent clusters
 * that resolve to the same face share one {@code FONT} attribute run, so the JDK sees one run per
 * face change instead of one run per cluster, while bidi is still resolved over the whole line
 * rather than per run. The result is only as good as the JDK font
 * pipeline on the host; headless layout is explicitly not evidence about a real Minecraft window or
 * about IME behaviour.
 * </p>
 */
public final class Java2dTextBackend {

    /** Maximum accepted size of one embedded font file. */
    public static final int MAX_FONT_BYTES = 64 * 1024 * 1024;
    /** Maximum number of fallback faces accepted by one layout call. */
    public static final int MAX_FALLBACK_FACES = 32;
    /** Maximum accepted text length in UTF-16 units. */
    public static final int MAX_TEXT_LENGTH = 16384;
    /** Maximum accepted physical size, that is {@code logicalSize * scale}, in pixels. */
    public static final float MAX_PHYSICAL_SIZE = 256f;

    private static final Set<String> LOGICAL_FAMILIES = Set.of(
            "Dialog", "SansSerif", "Serif", "Monospaced", "DialogInput");

    private final Object lock = new Object();
    private volatile Map<EditorId, Face> faces = Map.of();
    private final FontRenderContext renderContext = TextLine.renderContext();
    private final Supplier<List<Font>> installedFonts;
    private Map<String, InstalledChoice> installedCatalog;

    /** Creates an empty backend with no registered face. */
    public Java2dTextBackend() {
        this(() -> List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()));
    }

    /** Package-private catalog seam: tests can supply physical faces without installing them. */
    Java2dTextBackend(Supplier<List<Font>> installedFonts) {
        this.installedFonts = Objects.requireNonNull(installedFonts);
    }

    /**
     * Registers an exact installed family match (case-insensitive, using its Locale.ROOT name).
     * Missing families return empty and do not mutate registration. Logical JDK families must use
     * registerLogical; this method never resolves an unknown name through new Font(name,...).
     * The installed catalog is inspected once per backend and is not refreshed during rendering.
     */
    public Optional<InstalledFont> registerInstalled(EditorId id, String requestedFamily) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestedFamily, "requestedFamily");
        if (requestedFamily.isBlank()) throw new IllegalArgumentException("installed family is blank");
        synchronized (lock) {
            if (faces.containsKey(id)) throw new IllegalArgumentException("face already registered " + id);
            var choice = installedCatalog().get(requestedFamily.toLowerCase(Locale.ROOT));
            if (choice == null) return Optional.empty();
            var face = new FontFaceId(id);
            var next = new LinkedHashMap<>(faces);
            next.put(id, new Face(face, choice.font().deriveFont(Font.PLAIN, 1f)));
            faces = Map.copyOf(next);
            return Optional.of(new InstalledFont(face, choice.family()));
        }
    }

    private Map<String, InstalledChoice> installedCatalog() {
        // Caller holds lock, including while the initial catalog is captured.
        if (installedCatalog != null) return installedCatalog;
        var result = new LinkedHashMap<String, InstalledChoice>();
        for (var font : installedFonts.get()) {
            Objects.requireNonNull(font, "installed font");
            String family = font.getFamily(Locale.ROOT);
            if (LOGICAL_FAMILIES.stream().anyMatch(name -> name.equalsIgnoreCase(family))) continue;
            var key = family.toLowerCase(Locale.ROOT);
            var candidate = new InstalledChoice(family, font);
            var previous = result.get(key);
            if (previous == null || regularRank(candidate) < regularRank(previous)) result.put(key, candidate);
        }
        installedCatalog = Map.copyOf(result);
        return installedCatalog;
    }

    private static int regularRank(InstalledChoice choice) {
        String name = choice.font().getFontName(Locale.ROOT), family = choice.family();
        if (name.equalsIgnoreCase(family) || name.equalsIgnoreCase(family + " Regular") || name.equalsIgnoreCase(family + " Normal")) return 0;
        return choice.font().isPlain() ? 1 : 2;
    }

    private record InstalledChoice(String family, Font font) {}

    String registeredFamily(FontFaceId id) {
        var found = faces.get(id.id());
        if (found == null) throw new IllegalArgumentException("face is not registered: " + id);
        return found.font().getFamily(Locale.ROOT);
    }

    /**
     * Registers every face of one embedded font file.
     *
     * <p>
     * The bytes are copied first and bounded to {@link #MAX_FONT_BYTES}; the caller may mutate or
     * release its array afterwards and the registered faces keep working. Faces are added
     * atomically: when any face cannot be created, or when a generated identity collides with an
     * already registered face, nothing is added and the previous registry is unchanged.
     * </p>
     *
     * <p>
     * Generated identities are {@code familyPath/face-N}, where {@code N} is the zero based index
     * of the face inside the file. {@code TTF}, {@code OTF} and multi-face {@code TTC} files are
     * supported to the extent the JDK supports them. Registering a file never installs it into the
     * host font registry.
     * </p>
     *
     * @param family    identity whose path becomes the prefix of every generated face identity
     * @param fontBytes complete font file content, not a path
     * @return the new faces in file order
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the input is empty, too large, malformed or conflicting
     */
    public List<FontFaceId> register(EditorId family, byte[] fontBytes) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(fontBytes, "fontBytes");
        if (fontBytes.length == 0) {
            throw new IllegalArgumentException("font bytes are empty");
        }
        if (fontBytes.length > MAX_FONT_BYTES) {
            throw new IllegalArgumentException("font bytes exceed " + MAX_FONT_BYTES);
        }
        Font[] created;
        try (ByteArrayInputStream stream = new ByteArrayInputStream(fontBytes.clone())) {
            created = Font.createFonts(stream);
        } catch (FontFormatException | IOException failure) {
            throw new IllegalArgumentException("font could not be parsed", failure);
        }
        if (created == null || created.length == 0) {
            throw new IllegalArgumentException("font contains no face");
        }
        List<EditorId> ids = new ArrayList<>(created.length);
        Set<EditorId> generated = new LinkedHashSet<>();
        for (int index = 0; index < created.length; index++) {
            if (created[index] == null) {
                throw new IllegalArgumentException("font face " + index + " is unavailable");
            }
            EditorId id = new EditorId(family.namespace(), family.path() + "/face-" + index);
            if (!generated.add(id)) {
                throw new IllegalArgumentException("duplicate generated face identity " + id);
            }
            ids.add(id);
        }
        synchronized (lock) {
            Map<EditorId, Face> current = faces;
            for (EditorId id : ids) {
                if (current.containsKey(id)) {
                    throw new IllegalArgumentException("face already registered " + id);
                }
            }
            Map<EditorId, Face> next = new LinkedHashMap<>(current);
            List<FontFaceId> result = new ArrayList<>(ids.size());
            for (int index = 0; index < ids.size(); index++) {
                FontFaceId faceId = new FontFaceId(ids.get(index));
                next.put(faceId.id(), new Face(faceId, created[index]));
                result.add(faceId);
            }
            faces = Map.copyOf(next);
            return List.copyOf(result);
        }
    }

    /**
     * Registers one JDK logical family.
     *
     * <p>
     * Only {@code Dialog}, {@code SansSerif}, {@code Serif}, {@code Monospaced} and
     * {@code DialogInput} are accepted. A logical face is resolved by the JDK font configuration at
     * layout time; it is not a way to load a file, and its identity is exactly the supplied
     * {@code id}.
     * </p>
     *
     * @param id          identity of the new face
     * @param logicalName one of the accepted JDK logical family names
     * @return the registered face
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the name is not an accepted logical family, or the
     *                                  identity is already registered in this backend
     */
    public FontFaceId registerLogical(EditorId id, String logicalName) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(logicalName, "logicalName");
        if (!LOGICAL_FAMILIES.contains(logicalName)) {
            throw new IllegalArgumentException("unsupported logical family " + logicalName);
        }
        Font font = new Font(logicalName, Font.PLAIN, 1);
        FontFaceId faceId = new FontFaceId(id);
        synchronized (lock) {
            if (faces.containsKey(id)) {
                throw new IllegalArgumentException("face already registered " + id);
            }
            Map<EditorId, Face> next = new LinkedHashMap<>(faces);
            next.put(id, new Face(faceId, font));
            faces = Map.copyOf(next);
            return faceId;
        }
    }

    /**
     * Lays out one line with per cluster font fallback.
     *
     * <p>
     * {@code text} must be non-null, at most {@link #MAX_TEXT_LENGTH} UTF-16 units, valid Unicode
     * without unpaired surrogates, and must not contain {@code CR} or {@code LF}. {@code fallback}
     * must be non-null, non-empty, at most {@link #MAX_FALLBACK_FACES} entries without duplicates,
     * and every face must already be registered in this backend. Sizes must be finite and positive,
     * and {@code logicalSize * scale} must not exceed {@link #MAX_PHYSICAL_SIZE}.
     * </p>
     *
     * <p>
     * Text is split on JDK {@link BreakIterator} character clusters. Each cluster uses the first
     * fallback face whose coverage satisfies {@code canDisplayUpTo(cluster) == -1}; when no face
     * covers the cluster, the first fallback face is used and {@link TextLine#usedFonts()} records
     * that actual choice, which is the only diagnostic for a missing glyph. A surrogate pair is one
     * cluster and is never split. A maximal run of adjacent clusters that chose the same face shares
     * one {@code FONT} attribute, but the per-character font assignment, the recorded cluster
     * boundaries and the first use order of {@link TextLine#usedFonts()} are the same as if every
     * cluster carried its own attribute. The whole line is shaped by a single {@link TextLayout}, so
     * bidi ordering and Arabic shaping are computed over the full logical text even when
     * neighbouring clusters use different faces.
     * </p>
     *
     * <p>
     * Every input is validated before any layout work, so an invalid call never produces a
     * partially usable line and never mutates the registry.
     * </p>
     *
     * @param text        logical line content, may be empty
     * @param fallback    ordered fallback faces, first match wins
     * @param logicalSize logical font size before scaling
     * @param scale       device scale factor, for example {@code 2.0} on a HiDPI surface
     * @return immutable line whose metrics and bounds are physical pixels
     * @throws NullPointerException     if {@code text} or {@code fallback} is {@code null}, or holds a
     *                                  {@code null} face
     * @throws IllegalArgumentException if any input violates the limits above, including a face that
     *                                  is not registered in this backend
     */
    public TextLine layout(String text, List<FontFaceId> fallback, float logicalSize, float scale) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(fallback, "fallback");
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text exceeds " + MAX_TEXT_LENGTH + " UTF-16 units");
        }
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("single line layout rejects CR and LF");
        }
        requireValidUnicode(text);
        if (fallback.isEmpty() || fallback.size() > MAX_FALLBACK_FACES) {
            throw new IllegalArgumentException("fallback must hold 1.." + MAX_FALLBACK_FACES + " faces");
        }
        if (!Float.isFinite(logicalSize) || logicalSize <= 0f) {
            throw new IllegalArgumentException("logical size must be finite and positive");
        }
        if (!Float.isFinite(scale) || scale <= 0f) {
            throw new IllegalArgumentException("scale must be finite and positive");
        }
        float physicalSize = logicalSize * scale;
        if (!Float.isFinite(physicalSize) || physicalSize <= 0f) {
            // A positive logical size can still underflow to zero when multiplied by a tiny scale;
            // such a line has no physical size at all and is rejected instead of shaped at size 0.
            throw new IllegalArgumentException(
                    "physical size must be finite and positive: " + physicalSize);
        }
        if (physicalSize > MAX_PHYSICAL_SIZE) {
            throw new IllegalArgumentException("physical size exceeds " + MAX_PHYSICAL_SIZE);
        }
        Map<EditorId, Face> snapshot = faces;
        List<Face> ordered = new ArrayList<>(fallback.size());
        Set<FontFaceId> distinct = new LinkedHashSet<>();
        for (FontFaceId faceId : fallback) {
            Objects.requireNonNull(faceId, "fallback face");
            if (!distinct.add(faceId)) {
                throw new IllegalArgumentException("duplicate fallback face " + faceId);
            }
            Face face = snapshot.get(faceId.id());
            if (face == null) {
                throw new IllegalArgumentException("fallback face is not registered: " + faceId);
            }
            ordered.add(face);
        }
        Map<FontFaceId, Font> sized = new HashMap<>();
        for (Face face : ordered) {
            sized.put(face.id(), face.font().deriveFont(physicalSize));
        }
        if (text.isEmpty()) {
            // The JDK rejects a zero length string in the TextLayout constructor, and an empty line
            // has no cluster to shape, so it is represented without a layout.
            return new TextLine(text, null, List.of(), new int[] { 0 });
        }
        AttributedString attributed = new AttributedString(text);
        List<FontFaceId> used = new ArrayList<>();
        // The cluster boundaries found here are kept by the line, so hitTest binary searches them
        // instead of splitting the text a second time.
        List<Integer> boundaries = new ArrayList<>();
        Face first = ordered.get(0);
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        // The face is still chosen per cluster, but adjacent clusters that resolve to the same face
        // identity share one FONT attribute: the run is opened at the first of them and written once
        // when the identity changes, so a line that stays on one face adds one attribute for the
        // whole line instead of one per cluster. Identity is the FontFaceId, never a Font instance or
        // a family name, and the Font written is the one derived for this very layout call.
        FontFaceId runFace = null;
        int runStart = 0;
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            boundaries.add(start);
            String cluster = text.substring(start, end);
            Face chosen = first;
            for (Face candidate : ordered) {
                if (candidate.font().canDisplayUpTo(cluster) < 0) {
                    chosen = candidate;
                    break;
                }
            }
            FontFaceId chosenId = chosen.id();
            if (!used.contains(chosenId)) {
                used.add(chosenId);
            }
            if (!chosenId.equals(runFace)) {
                if (runFace != null) {
                    attributed.addAttribute(TextAttribute.FONT, sized.get(runFace), runStart, start);
                }
                runFace = chosenId;
                runStart = start;
            }
        }
        // Empty text returned earlier, so the loop above opened at least one run and the final run
        // still has to be written.
        attributed.addAttribute(TextAttribute.FONT, sized.get(runFace), runStart, text.length());
        boundaries.add(text.length());
        int[] clusterBoundaries = new int[boundaries.size()];
        for (int index = 0; index < clusterBoundaries.length; index++) {
            clusterBoundaries[index] = boundaries.get(index);
        }
        TextLayout layout = new TextLayout(attributed.getIterator(), renderContext);
        return new TextLine(text, layout, used, clusterBoundaries);
    }

    private static void requireValidUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException("text contains an unpaired high surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("text contains an unpaired low surrogate");
            }
        }
    }

    /** Internal registry entry; the AWT font never leaves this module. */
    private record Face(FontFaceId id, Font font) {}
}
