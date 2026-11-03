package net.ptcrys.fpsmatch.editor.common.text.java2d.examples;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.text.java2d.FontFaceId;
import net.ptcrys.fpsmatch.editor.common.text.java2d.Java2dTextBackend;
import net.ptcrys.fpsmatch.editor.common.text.java2d.Raster;
import net.ptcrys.fpsmatch.editor.common.text.java2d.TextLine;

/**
 * External, dependency free performance baseline for the frozen public text API.
 *
 * <p>The program consumes the built {@code editor-api} and {@code editor-text-java2d} jars only and
 * writes one JSON document. Every short workload is measured at three scales with three separate
 * items, so a slow number always belongs to exactly one call:</p>
 *
 * <ol>
 *   <li>{@code layout} - {@link Java2dTextBackend#layout} on the fixed string;</li>
 *   <li>{@code rasterize} - {@link TextLine#rasterize()} on the same already laid out line, never
 *       mixed into the layout sample;</li>
 *   <li>{@code hit} - {@link TextLine#hitTest(float)} on positions generated before sampling
 *       started, reported as one {@value #HIT_QUERIES} query batch divided by
 *       {@value #HIT_QUERIES}.</li>
 * </ol>
 *
 * <p>Font file reading, warmup iterations, standard output and JSON writing are outside every timed
 * region. No sample object is retained: a measurement keeps only its time array and folds one
 * scalar checksum, so no number can be explained by lines or rasters held alive by the harness.</p>
 *
 * <p>The hit item delegates to the platform: {@link TextLine#hitTest(float)} takes the insertion
 * position from the JDK's own text layout hit test, so the hit numbers describe what that
 * implementation costs for the measured content. They are not a characterization of this module's
 * own work, and this program claims neither growth nor flatness of that cost with the line length.
 * The recorded checksum supports one comparison only: equal checksums mean the two runs produced
 * equal summaries, and nothing more, in particular not that any work ran completely or correctly.</p>
 *
 * <p>The long line is measured for layout and hit testing only. It is deliberately never rasterized
 * in the baseline, because its inked width is far past the {@link Raster#MAX_SIDE} limit; one probe
 * outside the timed region records the resulting refusal so the exclusion is evidence, not a guess.
 * </p>
 *
 * <p>Usage: {@code java -Djava.awt.headless=true -cp <classes>;<api jar>;<text jar>
 * net.ptcrys.fpsmatch.editor.common.text.java2d.examples.TextBenchmark [json output] [font directory]}. The JSON
 * default already points at the {@code platform-batch03/Q09} artifact directory of the repository
 * root.</p>
 */
public final class TextBenchmark {
    /** Warmup iterations run and discarded before every measured item. */
    private static final int WARMUPS = 20;
    /** Measured iterations kept per item. */
    private static final int SAMPLES = 100;
    /** Physical size handed to every layout call. */
    private static final float LOGICAL_SIZE = 16f;
    /** Scales measured for every short workload. */
    private static final float[] SCALES = {1f, 1.5f, 2f};
    /** Hit queries inside one timing sample; the per query value divides the batch by this. */
    private static final int HIT_QUERIES = 1000;
    /** Length of the long line; the public limit is {@code Java2dTextBackend.MAX_TEXT_LENGTH}. */
    private static final int LONG_LINE_UTF16 = 16384;
    /** Accepted UTF-16 length band of a short workload, which the card fixes at about 120 units. */
    private static final int SHORT_MIN = 100;
    private static final int SHORT_MAX = 140;

    /** Default JSON destination, relative to the repository root the program is started from. */
    private static final String DEFAULT_JSON =
            ".local-artifacts/world-editor/rewrite/platform-batch03/Q09/text-java2d-baseline.json";
    /** Default font directory; the card fixes the Windows font directory. */
    private static final String DEFAULT_FONT_DIR = "C:/Windows/Fonts";

    /** Real Latin faces tried in order; the first readable file wins. */
    private static final String[] LATIN_FILES = {"segoeui.ttf", "arial.ttf", "verdana.ttf"};
    /** Real CJK faces tried in order; the first readable file wins. */
    private static final String[] CJK_FILES = {"msyh.ttc", "msyh.ttf", "simsun.ttc", "malgun.ttf"};

    /** Latin text with ligature rich words, about 120 UTF-16 units. */
    private static final String LATIN =
            "The quick brown fox jumps over the lazy dog; "
                    + "Pack my box with five dozen liquor jugs! "
                    + "office waffle affine find flat fluid";
    /** Chinese and Latin mixed, about 120 UTF-16 units: the runs force real fallback. */
    private static final String MIXED =
            "Mixed \u6df7\u6392 text with \u56de\u9000 fallback \u5b57\u4f53 "
                    + "Mixed \u6df7\u6392 text with \u56de\u9000 fallback \u5b57\u4f53 "
                    + "Mixed \u6df7\u6392 text with \u56de\u9000 fallback \u5b57\u4f53 "
                    + "\u7f16\u8f91\u5668\u6e32\u67d3 mixed line";
    /** Arabic and Latin alternating, about 120 UTF-16 units: bidi order is not index order. */
    private static final String ARABIC =
            repeat("\u0645\u0631\u062d\u0628\u0627 abc \u0627\u0644\u0639\u0627\u0644\u0645 ", 6)
                    + "tail \u0645\u0631\u062d\u0628\u0627 \u0627\u0644\u0639\u0627\u0644\u0645";
    /** Latin base letters carrying combining marks, about 120 UTF-16 units in 54 clusters. */
    private static final String COMBINING =
            "Cafe\u0301 de\u0301ja\u0300 vu: "
                    + repeat("a\u0301e\u0302i\u0303o\u0303u\u0308 ", 9);
    /** Long line, exactly {@value #LONG_LINE_UTF16} UTF-16 units, built from 32 unit chunks. */
    private static final String LONG_LATIN = repeat("The quick brown fox jumps over! ", 512);

    private TextBenchmark() {
    }

    /**
     * Runs the baseline and writes the JSON document.
     *
     * @param arguments optional JSON output path, then optional font directory
     * @throws IOException if the JSON document cannot be written
     */
    public static void main(String[] arguments) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Path jsonPath = Path.of(arguments.length > 0 ? arguments[0] : DEFAULT_JSON)
                .toAbsolutePath().normalize();
        Path fontDir = Path.of(arguments.length > 1 ? arguments[1] : DEFAULT_FONT_DIR)
                .toAbsolutePath().normalize();

        requireLength("latin", LATIN);
        requireLength("mixed", MIXED);
        requireLength("arabic", ARABIC);
        requireLength("combining", COMBINING);
        if (LONG_LATIN.length() != LONG_LINE_UTF16) {
            throw new IllegalStateException("long line is " + LONG_LATIN.length()
                    + " UTF-16 units, expected " + LONG_LINE_UTF16);
        }

        Java2dTextBackend backend = new Java2dTextBackend();
        Registered latin = registerFamily(backend, "latin", "editor:fonts/latin", fontDir, LATIN_FILES);
        Registered cjk = registerFamily(backend, "cjk", "editor:fonts/cjk", fontDir, CJK_FILES);
        List<FontFaceId> fallback = List.of(latin.face(), cjk.face());
        System.out.println("fonts latin=" + latin.file() + " cjk=" + cjk.file()
                + " fallback=" + fallback);

        Meter meter = new Meter();
        List<Result> results = new ArrayList<>();
        for (Workload workload : workloads()) {
            for (float scale : SCALES) {
                Result result = measureShort(backend, fallback, meter, workload, scale);
                results.add(result);
                System.out.println(summary(result));
            }
        }
        LongLineResult longLine = measureLongLine(backend, fallback, meter);
        System.out.println("long-latin scale=1.0 len=" + longLine.utf16Length()
                + " advance=" + fixed(longLine.advancePx(), 2) + "px layout.p50="
                + fixed(p50(longLine.layout()), 4) + "ms hit.p50="
                + fixed(p50(longLine.hit()), 2) + "ns/q raster=" + longLine.rasterProbe());

        String document = buildJson(jsonPath, fontDir, latin, cjk, results, longLine, meter.checksum());
        Path parent = jsonPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(jsonPath, document, StandardCharsets.UTF_8);
        System.out.println("wrote " + jsonPath);
        System.out.println("checksum " + meter.checksum());
    }

    /**
     * Measures the three items of one short workload at one scale.
     *
     * @param backend the backend under test
     * @param fallback ordered real font faces
     * @param meter checksum accumulator shared by every item
     * @param workload fixed text and its description
     * @param scale physical scale, one of {@link #SCALES}
     * @return the measured row
     */
    private static Result measureShort(Java2dTextBackend backend, List<FontFaceId> fallback, Meter meter,
            Workload workload, float scale) {
        String text = workload.text();
        TextLine line = backend.layout(text, fallback, LOGICAL_SIZE, scale);
        Raster reference = line.rasterize();
        int[] pixels = reference.argb();
        int ink = 0;
        for (int sample : pixels) {
            if ((sample >>> 24) != 0) {
                ink++;
            }
        }
        float[] positions = hitPositions(line.advance());

        long[] layoutNanos = meter.measure(() -> {
            TextLine fresh = backend.layout(text, fallback, LOGICAL_SIZE, scale);
            return Float.floatToIntBits(fresh.advance()) * 31L + fresh.usedFonts().size();
        });
        long[] rasterNanos = meter.measure(() -> {
            Raster raster = line.rasterize();
            return raster.width() * 31L + raster.height() + Float.floatToIntBits(raster.originX());
        });
        long[] hitNanos = meter.measure(() -> {
            long digest = 0L;
            for (float x : positions) {
                digest = digest * 31L + line.hitTest(x);
            }
            return digest;
        });
        return new Result(workload.name(), workload.description(), text.length(), scale, line.advance(),
                reference.width(), reference.height(), ink, faceNames(line),
                new Samples("ms", 1, layoutNanos),
                new Samples("ms", 1, rasterNanos),
                new Samples("nsPerQuery", HIT_QUERIES, hitNanos));
    }

    /**
     * Measures the long line: layout and hit batches only, plus one untimed rasterize probe.
     *
     * @param backend the backend under test
     * @param fallback ordered real font faces
     * @param meter checksum accumulator shared by every item
     * @return the measured long line, never rasterized inside the timed regions
     */
    private static LongLineResult measureLongLine(Java2dTextBackend backend, List<FontFaceId> fallback,
            Meter meter) {
        String text = LONG_LATIN;
        TextLine line = backend.layout(text, fallback, LOGICAL_SIZE, 1f);
        float[] positions = hitPositions(line.advance());
        long[] layoutNanos = meter.measure(() -> {
            TextLine fresh = backend.layout(text, fallback, LOGICAL_SIZE, 1f);
            return Float.floatToIntBits(fresh.advance()) * 31L + fresh.usedFonts().size();
        });
        long[] hitNanos = meter.measure(() -> {
            long digest = 0L;
            for (float x : positions) {
                digest = digest * 31L + line.hitTest(x);
            }
            return digest;
        });
        return new LongLineResult(text.length(), 1f, line.advance(), faceNames(line),
                new Samples("ms", 1, layoutNanos), new Samples("nsPerQuery", HIT_QUERIES, hitNanos),
                rasterProbe(line));
    }

    /**
     * Confirms outside the timed region that the long line really is past the raster limits.
     *
     * <p>The size check inside {@link TextLine#rasterize()} runs before any buffer is allocated, so
     * this single probe is cheap and cannot build a huge image.</p>
     *
     * @param line the long line
     * @return the refusal or, if the call unexpectedly succeeded, the produced size
     */
    private static String rasterProbe(TextLine line) {
        try {
            Raster raster = line.rasterize();
            return "unexpected raster " + raster.width() + "x" + raster.height();
        } catch (IllegalStateException expected) {
            String message = expected.getMessage();
            return "IllegalStateException: " + (message == null ? "(no message)" : message);
        }
    }

    /**
     * Query positions fixed before sampling: one golden-ratio stride across the whole advance.
     *
     * @param advance the line advance in physical pixels
     * @return {@value #HIT_QUERIES} positions, the same array for every batch of this run and the same
     *     values again whenever the advance is the same
     */
    private static float[] hitPositions(float advance) {
        float[] positions = new float[HIT_QUERIES];
        for (int index = 0; index < positions.length; index++) {
            double fraction = (index * 0.6180339887498949) % 1.0;
            positions[index] = (float) (advance * fraction);
        }
        return positions;
    }

    /** The workload table: four short texts and no randomness anywhere. */
    private static List<Workload> workloads() {
        return List.of(
                new Workload("latin", "Latin with ligatures, Latin face only", LATIN),
                new Workload("mixed", "Chinese and Latin, exercises fallback", MIXED),
                new Workload("arabic", "Arabic and Latin, bidi, non monotonic carets", ARABIC),
                new Workload("combining", "Latin letters with combining marks", COMBINING));
    }

    /**
     * Registers the first readable file of one family.
     *
     * @param backend the backend to register into
     * @param role short role name used in the JSON document
     * @param id editor identity of the family
     * @param fontDir directory holding real font files
     * @param fileNames candidate file names, tried in order
     * @return the registration record
     * @throws IOException if a candidate exists but cannot be read
     */
    private static Registered registerFamily(Java2dTextBackend backend, String role, String id,
            Path fontDir, String[] fileNames) throws IOException {
        for (String fileName : fileNames) {
            Path file = fontDir.resolve(fileName);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(file);
            List<FontFaceId> faces = backend.register(EditorId.parse(id), bytes);
            if (faces.isEmpty()) {
                throw new IllegalStateException("register(" + fileName + ") returned no face");
            }
            return new Registered(role, id, fileName, file.toString(), bytes.length, faces.size(),
                    faces.get(0));
        }
        throw new IllegalStateException("no real font file found for " + role + " under " + fontDir
                + "; tried " + String.join(", ", fileNames) + "; pass the font directory as argument 2");
    }

    /**
     * Face identities of one line, as strings, for the JSON evidence of fallback.
     *
     * @param line a laid out line
     * @return the distinct used faces in first use order
     */
    private static List<String> faceNames(TextLine line) {
        List<String> names = new ArrayList<>();
        for (FontFaceId face : line.usedFonts()) {
            names.add(String.valueOf(face.id()));
        }
        return names;
    }

    /**
     * Rejects a workload whose UTF-16 length drifted away from the fixed card value.
     *
     * @param name workload name
     * @param text the workload text
     */
    private static void requireLength(String name, String text) {
        if (text.length() < SHORT_MIN || text.length() > SHORT_MAX) {
            throw new IllegalStateException(name + " workload is " + text.length()
                    + " UTF-16 units, expected " + SHORT_MIN + ".." + SHORT_MAX);
        }
    }

    /**
     * Repeats a chunk without any dependency on locale or random state.
     *
     * @param chunk the repeated chunk
     * @param times repetition count
     * @return the repeated text
     */
    private static String repeat(String chunk, int times) {
        StringBuilder builder = new StringBuilder(chunk.length() * times);
        for (int index = 0; index < times; index++) {
            builder.append(chunk);
        }
        return builder.toString();
    }

    /** Nearest-rank percentile over ascending values; rank 1 is the smallest sample. */
    private static double percentile(long[] sorted, double quantile) {
        int rank = (int) Math.ceil(quantile * sorted.length);
        int index = Math.min(Math.max(rank, 1), sorted.length) - 1;
        return sorted[index];
    }

    /** p50 in the unit of the sample: milliseconds, or nanoseconds per hit query. */
    private static double p50(Samples samples) {
        return percentile(ascending(samples), 0.5) / divisorOf(samples);
    }

    /** Nanoseconds to the sample unit: one millisecond is 1e6 ns, a hit sample is 1000 queries. */
    private static double divisorOf(Samples samples) {
        return samples.unit().equals("ms") ? 1_000_000.0 : samples.perSample();
    }

    /** A sorted copy, so percentiles never disturb the recorded sample order. */
    private static long[] ascending(Samples samples) {
        long[] sorted = samples.nanos().clone();
        Arrays.sort(sorted);
        return sorted;
    }

    /** Fixed decimal rendering with a locale independent separator, so the JSON stays valid. */
    private static String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** JSON escaping for the few strings that are not already ASCII identifiers. */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.toString();
    }

    /** A JSON string array from already escaped free text. */
    private static String stringArray(List<String> values) {
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append('"').append(escape(values.get(index))).append('"');
        }
        return out.append(']').toString();
    }

    /** One metric block: milliseconds per operation, or nanoseconds per hit query plus the batch. */
    private static String samplesJson(Samples samples) {
        long[] sorted = ascending(samples);
        boolean perQuery = !samples.unit().equals("ms");
        double divisor = divisorOf(samples);
        int decimals = perQuery ? 2 : 4;
        StringBuilder out = new StringBuilder(256);
        out.append("{\"unit\": \"").append(samples.unit()).append('"')
                .append(", \"samples\": ").append(sorted.length)
                .append(", \"warmups\": ").append(WARMUPS)
                .append(", \"perSample\": ").append(samples.perSample())
                .append(", \"p50\": ").append(fixed(percentile(sorted, 0.5) / divisor, decimals))
                .append(", \"p95\": ").append(fixed(percentile(sorted, 0.95) / divisor, decimals))
                .append(", \"max\": ").append(fixed(percentile(sorted, 1.0) / divisor, decimals));
        if (perQuery) {
            out.append(", \"batchMs\": {\"p50\": ").append(fixed(percentile(sorted, 0.5) / 1e6, 4))
                    .append(", \"p95\": ").append(fixed(percentile(sorted, 0.95) / 1e6, 4))
                    .append(", \"max\": ").append(fixed(percentile(sorted, 1.0) / 1e6, 4)).append('}');
        }
        return out.append('}').toString();
    }

    /** One JSON row of the short workload table. */
    private static String resultJson(Result result) {
        StringBuilder out = new StringBuilder(768);
        out.append("    {\"workload\": \"").append(escape(result.workload())).append('"')
                .append(", \"description\": \"").append(escape(result.description())).append('"')
                .append(", \"utf16Length\": ").append(result.utf16Length())
                .append(", \"scale\": ").append(fixed(result.scale(), 1))
                .append(", \"advancePx\": ").append(fixed(result.advancePx(), 2))
                .append(", \"rasterWidth\": ").append(result.rasterWidth())
                .append(", \"rasterHeight\": ").append(result.rasterHeight())
                .append(", \"inkPixels\": ").append(result.inkPixels())
                .append(", \"usedFonts\": ").append(stringArray(result.usedFonts())).append(",\n")
                .append("     \"layout\": ").append(samplesJson(result.layout())).append(",\n")
                .append("     \"rasterize\": ").append(samplesJson(result.rasterize())).append(",\n")
                .append("     \"hit\": ").append(samplesJson(result.hit())).append('}');
        return out.toString();
    }

    /** The long line block, including the recorded reason why it is not rasterized. */
    private static String longLineJson(LongLineResult longLine) {
        StringBuilder out = new StringBuilder(768);
        out.append("    {\"workload\": \"long-latin\"")
                .append(", \"description\": \"16384 UTF-16 Latin units: layout and hit batches only\"")
                .append(", \"utf16Length\": ").append(longLine.utf16Length())
                .append(", \"scale\": ").append(fixed(longLine.scale(), 1))
                .append(", \"advancePx\": ").append(fixed(longLine.advancePx(), 2))
                .append(", \"usedFonts\": ").append(stringArray(longLine.usedFonts())).append(",\n")
                .append("     \"layout\": ").append(samplesJson(longLine.layout())).append(",\n")
                .append("     \"hit\": ").append(samplesJson(longLine.hit())).append(",\n")
                .append("     \"rasterize\": {\"attempted\": false")
                .append(", \"excludedBy\": \"Raster.MAX_SIDE=8192 and MAX_PIXELS=16Mi\"")
                .append(", \"refusalProbeOutsideTiming\": \"")
                .append(escape(longLine.rasterProbe())).append("\"}}");
        return out.toString();
    }

    /** One console line per row: enough to see a regression, no table and no log dump. */
    private static String summary(Result result) {
        StringBuilder out = new StringBuilder(256);
        out.append(result.workload()).append(" scale=").append(fixed(result.scale(), 1))
                .append(" len=").append(result.utf16Length())
                .append(" advance=").append(fixed(result.advancePx(), 1)).append("px")
                .append(" raster=").append(result.rasterWidth()).append('x').append(result.rasterHeight())
                .append(" ink=").append(result.inkPixels())
                .append(" layout.p50=").append(fixed(p50(result.layout()), 4)).append("ms")
                .append(" raster.p50=").append(fixed(p50(result.rasterize()), 4)).append("ms")
                .append(" hit.p50=").append(fixed(p50(result.hit()), 2)).append("ns/q")
                .append(" fonts=").append(result.usedFonts());
        return out.toString();
    }

    /** Builds the whole JSON document with a stable key order. */
    private static String buildJson(Path jsonPath, Path fontDir, Registered latin, Registered cjk,
            List<Result> results, LongLineResult longLine, long checksum) {
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("{\n")
                .append("  \"schema\": \"editor.text.java2d.benchmark/1\",\n")
                .append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n")
                .append("  \"scope\": \"single run baseline of the frozen public text API; host specific, ")
                .append("not a pass/fail threshold and not a cross machine comparison\",\n")
                .append("  \"environment\": {\n")
                .append("    \"javaVersion\": \"").append(escape(System.getProperty("java.version")))
                .append("\",\n")
                .append("    \"javaVendor\": \"").append(escape(System.getProperty("java.vendor")))
                .append("\",\n")
                .append("    \"javaVm\": \"").append(escape(System.getProperty("java.vm.name")))
                .append("\",\n")
                .append("    \"osName\": \"").append(escape(System.getProperty("os.name"))).append("\",\n")
                .append("    \"osVersion\": \"").append(escape(System.getProperty("os.version")))
                .append("\",\n")
                .append("    \"osArch\": \"").append(escape(System.getProperty("os.arch"))).append("\",\n")
                .append("    \"availableProcessors\": ")
                .append(Runtime.getRuntime().availableProcessors()).append(",\n")
                .append("    \"maxHeapBytes\": ").append(Runtime.getRuntime().maxMemory()).append(",\n")
                .append("    \"headless\": ").append(GraphicsEnvironment.isHeadless()).append(",\n")
                .append("    \"fontDirectory\": \"").append(escape(fontDir.toString())).append("\",\n")
                .append("    \"jsonOutput\": \"").append(escape(jsonPath.toString())).append("\",\n")
                .append("    \"compileTargetRelease\": 17\n")
                .append("  },\n")
                .append("  \"fonts\": [").append(registeredJson(latin)).append(", ")
                .append(registeredJson(cjk)).append("],\n")
                .append("  \"parameters\": {\"logicalSize\": ").append(fixed(LOGICAL_SIZE, 1))
                .append(", \"scales\": [1.0, 1.5, 2.0]")
                .append(", \"warmups\": ").append(WARMUPS)
                .append(", \"samples\": ").append(SAMPLES)
                .append(", \"hitQueriesPerSample\": ").append(HIT_QUERIES)
                .append(", \"longLineUtf16\": ").append(LONG_LINE_UTF16)
                .append(", \"hitPositions\": \"advance * frac(i * 0.6180339887498949), i in [0,")
                .append(HIT_QUERIES).append("), fixed before sampling\"")
                .append(", \"timedRegions\": \"layout call, rasterize call, one hit query batch\"")
                .append(", \"excludedFromTiming\": [\"font file reading\", \"warmups\", ")
                .append("\"standard output\", \"JSON writing\", \"long line raster probe\"]")
                .append(", \"fallbackOrder\": [\"")
                .append(escape(String.valueOf(latin.face().id()))).append("\", \"")
                .append(escape(String.valueOf(cjk.face().id()))).append("\"]")
                .append("},\n")
                .append("  \"workloads\": [");
        List<Workload> workloads = workloads();
        for (int index = 0; index < workloads.size(); index++) {
            Workload workload = workloads.get(index);
            if (index > 0) {
                out.append(", ");
            }
            out.append("{\"name\": \"").append(escape(workload.name()))
                    .append("\", \"description\": \"").append(escape(workload.description()))
                    .append("\", \"utf16Length\": ").append(workload.text().length()).append('}');
        }
        out.append("],\n  \"results\": [\n");
        for (int index = 0; index < results.size(); index++) {
            if (index > 0) {
                out.append(",\n");
            }
            out.append(resultJson(results.get(index)));
        }
        out.append("\n  ],\n")
                .append("  \"longLine\": [\n").append(longLineJson(longLine)).append("\n  ],\n")
                .append("  \"checksum\": \"").append(checksum).append("\",\n")
                .append("  \"notes\": [\n")
                .append("    \"p50 is rank 50 of 100 ascending samples, p95 is rank 95, max is rank 100\",\n")
                .append("    \"hit unit is one 1000 query batch divided by 1000, so p50 is ns per query\",\n")
                .append("    \"rasterize is measured on a line laid out before sampling, never with layout\",\n")
                .append("    \"rasterize includes the defensive sample copy inside the Raster constructor\",\n")
                .append("    \"hitTest delegates the position to the JDK TextLayout hit test, whose cost may")
                .append(" vary with the line content\",\n")
                .append("    \"the long line is not rasterized: its inked width exceeds Raster.MAX_SIDE=8192\",\n")
                .append("    \"equal checksums only mean two runs produced equal output summaries, not that any")
                .append(" work ran completely or correctly\",\n")
                .append("    \"checksum is a decimal string because a 64 bit integer would round as a JSON number\",\n")
                .append("    \"fonts[] is in fallback order: the Latin face first, then the CJK face\",\n")
                .append("    \"no JIT, GC, CPU affinity or DPI tuning: numbers are host specific and descriptive\"\n")
                .append("  ]\n}\n");
        return out.toString();
    }

    /** One font entry: role, identity, resolved real file and its size. */
    private static String registeredJson(Registered registered) {
        return "{\"role\": \"" + escape(registered.role())
                + "\", \"familyId\": \"" + escape(registered.id())
                + "\", \"file\": \"" + escape(registered.file())
                + "\", \"path\": \"" + escape(registered.absolutePath())
                + "\", \"bytes\": " + registered.bytes()
                + ", \"facesInFile\": " + registered.facesInFile()
                + ", \"registeredFaceId\": \"" + escape(String.valueOf(registered.face().id()))
                + "\"}";
    }

    /** One fixed workload: a name, a description and the exact text. */
    private record Workload(String name, String description, String text) {
    }

    /** One registered real font family. */
    private record Registered(String role, String id, String file, String absolutePath, long bytes,
            int facesInFile, FontFaceId face) {
    }

    /** Time samples of one item plus the unit they are reported in. */
    private record Samples(String unit, int perSample, long[] nanos) {
    }

    /** One measured short workload row. */
    private record Result(String workload, String description, int utf16Length, float scale,
            float advancePx, int rasterWidth, int rasterHeight, int inkPixels, List<String> usedFonts,
            Samples layout, Samples rasterize, Samples hit) {
    }

    /** The measured long line: layout and hit samples, plus the untimed raster probe. */
    private record LongLineResult(int utf16Length, float scale, float advancePx, List<String> usedFonts,
            Samples layout, Samples hit, String rasterProbe) {
    }

    /** One timed operation returning a digest that is folded into the run checksum. */
    @FunctionalInterface
    private interface Job {
        /**
         * Runs the operation once.
         *
         * @return a digest of the result, cheap enough to stay inside the timed region
         */
        long run();
    }

    /** Runs warmups and samples, keeps only the time arrays and one checksum. */
    private static final class Meter {
        private static final long FOLD = 1_000_003L;
        private long checksum;

        /**
         * Runs one item: {@value #WARMUPS} discarded warmups, then {@value #SAMPLES} samples.
         *
         * @param job the operation under test
         * @return the per sample durations in nanoseconds
         */
        long[] measure(Job job) {
            for (int index = 0; index < WARMUPS; index++) {
                fold(job.run());
            }
            long[] nanos = new long[SAMPLES];
            for (int index = 0; index < SAMPLES; index++) {
                long start = System.nanoTime();
                long digest = job.run();
                nanos[index] = System.nanoTime() - start;
                fold(digest);
            }
            return nanos;
        }

        /** Folds one digest; the fold stays outside the timed window. */
        private void fold(long digest) {
            checksum = checksum * FOLD + digest;
        }

        /**
         * The run checksum. Equal values mean the folded digests agreed, which only identifies equal
         * output summaries; it is not evidence that every warmup and sample executed completely or
         * correctly.
         */
        long checksum() {
            return checksum;
        }
    }
}
