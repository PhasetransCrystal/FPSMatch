package net.ptcrys.fpsmatch.editor.common.text.java2d.examples;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.text.java2d.FontFaceId;
import net.ptcrys.fpsmatch.editor.common.text.java2d.Java2dTextBackend;
import net.ptcrys.fpsmatch.editor.common.text.java2d.Raster;
import net.ptcrys.fpsmatch.editor.common.text.java2d.TextLine;

/**
 * External consumer example: renders Latin, CJK, bidi and emoji lines with real Windows fonts and
 * writes one PNG sheet.
 *
 * <p>This file lives outside {@code src/main/java}, so it is not part of the compiled module and it
 * is the only place here that touches {@code java.desktop} for output. Everything it uses from the
 * backend is the frozen public surface: {@code register}, {@code layout}, {@code TextLine} metrics
 * and {@code Raster}. The example also shows the intended composition rule: a {@link Raster} is
 * straight ARGB, so the consumer composites it over its own background and never asks the backend to
 * own a window or a {@code Graphics2D}.</p>
 *
 * <p>Usage: {@code java TextSmoke [output.png] [fontDirectory]}. It runs headless and never needs a
 * display, so it also works on a build server. The sheet is painted with {@link #BACKGROUND} before
 * any raster is composited over it: outside the ink a {@link Raster} is fully transparent, so a sheet
 * left unfilled shows up as a black block in viewers that composite alpha over black. The run also
 * prints the pixel statistics of the finished sheet, which is the checkable evidence for that.</p>
 */
public final class TextSmoke {
    private static final int MARGIN = 10;
    private static final int BACKGROUND = 0xFFF4F4F6;
    private static final String DEFAULT_FONT_DIR = "C:/Windows/Fonts";
    /** Lines cover Latin ligatures, CJK fallback, mixed fallback, Arabic bidi and a surrogate pair. */
    private static final List<String> LINES = List.of(
            "Latin ligature: office waffle affine",
            "\u4e2d\u6587\u6e32\u67d3\uff1a\u4e16\u754c\u7f16\u8f91\u5668\u6587\u5b57\u540e\u7aef",
            "Mixed \u6df7\u6392 text with \u56de\u9000 fallback",
            "Bidi: \u0645\u0631\u062d\u0628\u0627 abc \u0627\u0644\u0639\u0627\u0644\u0645",
            "Emoji: \uD83D\uDE00 surrogate pair stays one cluster");

    private TextSmoke() {
    }

    /**
     * Renders the sheet and reports per line diagnostics on standard output.
     *
     * @param arguments optional output path and font directory
     */
    public static void main(String[] arguments) {
        System.setProperty("java.awt.headless", "true");
        Path output = arguments.length > 0 ? Path.of(arguments[0]) : Path.of("text-smoke.png");
        Path fontDir = arguments.length > 1 ? Path.of(arguments[1]) : Path.of(DEFAULT_FONT_DIR);
        Java2dTextBackend backend = new Java2dTextBackend();
        FontFaceId latin = firstFace(backend, "editor:fonts/latin", fontDir,
                "segoeui.ttf", "arial.ttf", "verdana.ttf");
        FontFaceId cjk = firstFace(backend, "editor:fonts/cjk", fontDir,
                "msyh.ttc", "msyh.ttf", "simsun.ttc", "malgun.ttf");
        FontFaceId logical = backend.registerLogical(EditorId.parse("editor:fonts/dialog"), "Dialog");
        List<FontFaceId> fallback = List.of(latin, cjk, logical);

        List<Raster> rasters = new ArrayList<>();
        int width = 0;
        int height = MARGIN;
        for (String text : LINES) {
            TextLine line = backend.layout(text, fallback, 20f, 1f);
            Raster raster = line.rasterize();
            rasters.add(raster);
            width = Math.max(width, raster.width());
            height += raster.height() + MARGIN;
            System.out.printf("%-6s advance=%8.3f ascent=%6.3f descent=%6.3f caretEnd=%8.3f "
                            + "hitMid=%d raster=%dx%d origin=(%.1f,%.1f) fonts=%s%n",
                    line.text().length() + "cp", line.advance(), line.ascent(), line.descent(),
                    line.caretX(line.text().length()),
                    line.hitTest(line.advance() / 2f), raster.width(), raster.height(),
                    raster.originX(), raster.baseline(), line.usedFonts());
        }
        BufferedImage sheet = new BufferedImage(width + 2 * MARGIN, height, BufferedImage.TYPE_INT_ARGB);
        fill(sheet, BACKGROUND);
        int y = MARGIN;
        for (Raster raster : rasters) {
            composite(sheet, raster, MARGIN, y);
            y += raster.height() + MARGIN;
        }
        try {
            ImageIO.write(sheet, "png", output.toFile());
        } catch (IOException failure) {
            System.err.println("cannot write " + output.toAbsolutePath() + ": " + failure.getMessage());
            System.exit(2);
        }
        System.out.println("wrote " + output.toAbsolutePath() + " ("
                + sheet.getWidth() + "x" + sheet.getHeight() + ")");
        report(sheet);
    }

    /**
     * Paints the sheet background before compositing. The backend raster is straight ARGB with
     * transparent pixels outside the ink, so without this fill those pixels stay fully transparent and
     * every viewer that shows alpha over black renders the sheet as a black block.
     */
    private static void fill(BufferedImage sheet, int argb) {
        Graphics2D graphics = sheet.createGraphics();
        try {
            graphics.setColor(new Color(argb, true));
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        } finally {
            graphics.dispose();
        }
    }

    /**
     * Prints the pixel statistics of the finished sheet so the result can be checked without trusting
     * a viewer: a correct sheet has no transparent pixel left and keeps most pixels at the background
     * colour, while a sheet that is one black block reports {@code backgroundPixels=0}.
     */
    private static void report(BufferedImage sheet) {
        int background = 0;
        int inked = 0;
        int transparent = 0;
        for (int row = 0; row < sheet.getHeight(); row++) {
            for (int column = 0; column < sheet.getWidth(); column++) {
                int sample = sheet.getRGB(column, row);
                if ((sample >>> 24) != 0xFF) {
                    transparent++;
                } else if (sample == BACKGROUND) {
                    background++;
                } else {
                    inked++;
                }
            }
        }
        System.out.printf("sheet %dx%d backgroundPixels=%d inkedPixels=%d transparentPixels=%d%n",
                sheet.getWidth(), sheet.getHeight(), background, inked, transparent);
    }

    private static FontFaceId firstFace(Java2dTextBackend backend, String id, Path fontDir,
            String... candidates) {
        List<Path> tried = new ArrayList<>();
        for (String name : candidates) {
            Path path = fontDir.resolve(name);
            tried.add(path);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                List<FontFaceId> faces = backend.register(EditorId.parse(id), Files.readAllBytes(path));
                System.out.println("registered " + path + " -> " + faces);
                return faces.get(0);
            } catch (IOException failure) {
                throw new IllegalStateException("cannot read " + path, failure);
            }
        }
        throw new IllegalStateException("no font found, tried " + tried);
    }

    /** Straight ARGB samples composited over the sheet background; no premultiplied assumption. */
    private static void composite(BufferedImage sheet, Raster raster, int x, int y) {
        int[] samples = raster.argb();
        for (int row = 0; row < raster.height(); row++) {
            for (int column = 0; column < raster.width(); column++) {
                int sample = samples[row * raster.width() + column];
                int alpha = sample >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int target = sheet.getRGB(x + column, y + row);
                sheet.setRGB(x + column, y + row, blend(sample, alpha, target));
            }
        }
    }

    private static int blend(int sample, int alpha, int target) {
        int inverse = 255 - alpha;
        int red = (((sample >> 16) & 0xFF) * alpha + ((target >> 16) & 0xFF) * inverse) / 255;
        int green = (((sample >> 8) & 0xFF) * alpha + ((target >> 8) & 0xFF) * inverse) / 255;
        int blue = ((sample & 0xFF) * alpha + (target & 0xFF) * inverse) / 255;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
