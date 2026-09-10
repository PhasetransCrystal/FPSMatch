import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Deterministic nine-slice UI material assets. Run from the FPSMatch project directory. */
public final class GenerateLobbyTextures {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("src/main/resources/assets/fpsmatch/textures/gui/rhodes");
        Files.createDirectories(output);
        surface(output, "team_band", 64, 32, 0xD4E5DE, 0x6E958D, false);
        surface(output, "room_surface", 64, 32, 0xF3F7F1, 0x8EAAA2, false);
        surface(output, "room_hover", 64, 32, 0xDFEEE7, 0x4E8D82, false);
        surface(output, "room_pressed", 64, 32, 0xC9E2D8, 0x33766D, false);
        surface(output, "command_surface", 48, 24, 0xE5EFEA, 0x7B9F97, false);
        surface(output, "command_hover", 48, 24, 0xD2E8DF, 0x4E8D82, false);
        surface(output, "command_primary", 48, 24, 0xCAD7D4, 0xEDF3ED, true);
        surface(output, "command_primary_hover", 48, 24, 0xDEE6E1, 0xF5F8F4, true);
        surface(output, "dialog_surface", 64, 64, 0xF8FAF4, 0x668D85, false);
        surface(output, "header_surface", 96, 32, 0xD7E8E0, 0x5C8880, false);
        surface(output, "field_surface", 48, 16, 0xF8FAF4, 0x91B2A8, false);
        surface(output, "panel", 48, 48, 0xE8F0EA, 0x89A9A0, false);
        surface(output, "panel_elevated", 48, 48, 0xF8FAF4, 0x6A948A, true);
        surface(output, "input", 48, 16, 0xF8FAF4, 0x91B2A8, false);
        surface(output, "focus_input", 48, 16, 0xE1F0E9, 0x4E8D82, true);
        surface(output, "header_band", 96, 32, 0xD7E8E0, 0x5C8880, false);
        surface(output, "toast", 64, 16, 0xDCEDE4, 0x4E8D82, false);
        surface(output, "ticks_strip", 32, 8, 0xC4DDD3, 0x6E958D, false);
        surface(output, "scroller_bar", 8, 24, 0x82AAA0, 0x477D74, false);
        surface(output, "scroller_bar_hover", 8, 24, 0x4E8D82, 0x2F675F, false);
        icon(output, "icon_refresh", 16, 0);
        icon(output, "icon_close", 16, 1);
        icon(output, "icon_chevron_down", 16, 2);
        icon(output, "icon_chevron_right", 16, 3);
        icon(output, "icon_tab_details", 16, 4);
        icon(output, "icon_tab_players", 16, 5);
        icon(output, "icon_tab_settings", 16, 6);
        icon(output, "icon_tab_more", 16, 7);
        System.out.println("Generated light Wuling material textures and icons");
    }

    private static void surface(Path folder, String name, int width, int height,
                                int base, int edge, boolean light) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int variation = y < 5 ? 4 - y : y > height - 5 ? -2 : 0;
                // Material detail stays at slice edges; the content center stretches cleanly.
                if ((x < 7 || x >= width - 7) && (x * 13 + y * 7) % 17 == 0) variation += 2;
                int rgb = tint(base, variation);
                if (y == 0 || x == 0 || x == width - 1) rgb = edge;
                if (y == height - 1) rgb = tint(base, -10);
                if (y == 1 && x > 1 && x < width - 2) rgb = tint(base, light ? 7 : 12);
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        drawMaterialMarks(image, name);
        ImageIO.write(image, "png", folder.resolve(name + ".png").toFile());
    }

    private static void drawMaterialMarks(BufferedImage image, String name) {
        int width = image.getWidth();
        int height = image.getHeight();
        int jade = 0xFF4E8D82;
        int paleJade = 0xFF9BC4B8;
        int copper = 0xFFB8743B;
        if (name.contains("header")) {
            line(image, 8, 4, Math.max(8, width - 20), 4, jade);
            line(image, Math.max(8, width - 22), 4, Math.max(8, width - 10), 4, copper);
            line(image, width - 10, 5, width - 4, Math.min(height - 3, 11), jade);
            line(image, width - 4, Math.min(height - 3, 11), width - 4, height - 4, jade);
        } else if (name.contains("room")) {
            line(image, 5, 6, 5, Math.max(6, height - 7), jade);
            line(image, 8, 4, Math.min(width - 9, 32), 4, paleJade);
            line(image, width - 18, height - 5, width - 7, height - 5, copper);
        } else if (name.contains("team")) {
            line(image, 5, 5, 5, Math.max(5, height - 6), jade);
            line(image, 8, 4, width - 12, 4, paleJade);
            line(image, width - 13, height - 5, width - 6, height - 5, copper);
        } else if (name.contains("panel") || name.contains("dialog")) {
            line(image, 8, 4, Math.max(8, width - 18), 4, paleJade);
            line(image, 8, 5, Math.min(width - 8, 20), 5, jade);
            line(image, width - 8, height - 5, width - 8, height - 12, jade);
            pixel(image, width - 9, height - 6, copper);
        } else if (name.contains("field") || name.equals("input")) {
            line(image, 6, 3, Math.max(6, width - 8), 3, paleJade);
            line(image, 6, height - 3, Math.min(width - 8, 18), height - 3, jade);
        } else if (name.contains("scroller")) {
            line(image, 2, 5, 2, Math.max(5, height - 6), jade);
            line(image, width - 3, 7, width - 3, Math.max(7, height - 8), copper);
        }
    }

    private static void line(BufferedImage image, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1);
        int sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        int x = x1;
        int y = y1;
        while (true) {
            pixel(image, x, y, color);
            if (x == x2 && y == y2) return;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x += sx; }
            if (twice <= dx) { error += dx; y += sy; }
        }
    }

    private static int tint(int rgb, int offset) {
        int r = Math.max(0, Math.min(255, (rgb >> 16 & 255) + offset));
        int g = Math.max(0, Math.min(255, (rgb >> 8 & 255) + offset));
        int b = Math.max(0, Math.min(255, (rgb & 255) + offset));
        return r << 16 | g << 8 | b;
    }

    private static void icon(Path folder, String name, int size, int type) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int color = 0xFF2B6E66;
        if (type == 0) {
            for (int i = 3; i <= 12; i++) {
                pixel(image, i, 4, color); pixel(image, i, 5, color);
                pixel(image, 4, i, color); pixel(image, 5, i, color);
            }
            for (int i = 4; i <= 10; i++) {
                pixel(image, i, 3, color); pixel(image, i, 2, color);
            }
            for (int i = 10; i <= 13; i++) {
                pixel(image, i, 2, color); pixel(image, i, 3, color);
                pixel(image, i, 4, color); pixel(image, i, 5, color);
            }
        } else if (type == 1) {
            for (int i = 3; i < 13; i++) {
                pixel(image, i, i, color); pixel(image, i + 1, i, color);
                pixel(image, i, 15 - i, color); pixel(image, i + 1, 15 - i, color);
            }
        } else if (type <= 3) {
            for (int i = 3; i < 12; i++) {
                int x = type == 2 ? i : 15 - i;
                int y = type == 2 ? 15 - i : i;
                pixel(image, x, y, color); pixel(image, x + 1, y, color);
            }
        } else if (type == 4) {
            for (int i = 4; i <= 11; i++) { pixel(image, i, 5, color); pixel(image, i, 6, color); }
            for (int i = 5; i <= 10; i++) { pixel(image, i, 4, color); pixel(image, i, 7, color); }
            line(image, 4, 10, 12, 10, color);
        } else if (type == 5) {
            pixel(image, 6, 5, color); pixel(image, 7, 5, color);
            pixel(image, 10, 5, color); pixel(image, 11, 5, color);
            line(image, 4, 9, 8, 9, color); line(image, 9, 9, 13, 9, color);
            line(image, 3, 12, 9, 12, color); line(image, 7, 11, 13, 11, color);
        } else if (type == 6) {
            line(image, 4, 4, 12, 4, color); line(image, 4, 12, 12, 12, color);
            line(image, 4, 4, 4, 12, color); line(image, 12, 4, 12, 12, color);
            for (int i = 6; i <= 10; i++) { pixel(image, i, 7, color); pixel(image, i, 8, color); }
        } else {
            for (int y : new int[]{5, 8, 11}) {
                pixel(image, 5, y, color); pixel(image, 6, y, color);
                pixel(image, 10, y, color); pixel(image, 11, y, color);
            }
        }
        ImageIO.write(image, "png", folder.resolve(name + ".png").toFile());
    }

    private static void pixel(BufferedImage image, int x, int y, int color) {
        if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) image.setRGB(x, y, color);
    }
}
