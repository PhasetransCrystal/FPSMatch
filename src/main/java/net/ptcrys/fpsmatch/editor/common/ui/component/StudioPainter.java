package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.WorkbenchTheme;

/**
 * Pure Studio paint compiler. It contains no native drawing API or business IDs.
 * Bounds/clips/state come from the published component frame used by ControlTree.
 */
public final class StudioPainter {

    public record Input(String text, int origin, java.util.List<StudioTextLine.Selection> selections, int caretX, int width, boolean caretVisible) {

        public Input {
            java.util.Objects.requireNonNull(text);
            selections = java.util.List.copyOf(selections);
        }

        /** Compatibility for a single continuous visual selection. */
        public Input(String text, int origin, int selectionStartX, int selectionEndX, int caretX, int width, boolean caretVisible) {
            this(text, origin, selectionStartX == selectionEndX ? java.util.List.of() : java.util.List.of(
                    new StudioTextLine.Selection(0, 0, Math.min(selectionStartX, selectionEndX), Math.subtractExact(Math.max(selectionStartX, selectionEndX), Math.min(selectionStartX, selectionEndX)))), caretX, width, caretVisible);
        }
    }

    private final StudioTextLayout textLayout;

    public StudioPainter(StudioTextLayout textLayout) {
        this.textLayout = java.util.Objects.requireNonNull(textLayout);
    }

    public StudioCanvas.Picture compile(Node node, Visual visual, WorkbenchTheme theme, boolean hover, boolean focus, boolean pressed, Input input) {
        var canvas = new StudioCanvas(node.visibleBounds());
        return canvas.finish(draw(canvas, node, visual, theme, hover, focus, pressed, input));
    }

    boolean draw(StudioCanvas g, Node node, Visual visual, WorkbenchTheme t, boolean hover, boolean focus, boolean pressed, Input input) {
        Bounds b = node.bounds();
        Kind kind = visual.kind();
        boolean disabled = !node.enabled() || node.readOnly();
        int foreground = visual.color() != null ? visual.color() : visual.variant() == Variant.DANGER ? t.danger() : visual.variant() == Variant.MUTED ? t.muted() : t.text();
        int fill = 0, stroke = 0, radius = 3;
        switch (kind) {
            case PANEL -> {
                fill = t.panel();
                radius = 0;
            }
            case HEADER, TOOLBAR -> {
                fill = t.panel();
                radius = 0;
            }
            case SECTION -> {
                radius = 0;
            }
            case INLINE_CARD -> {
                fill = t.panel();
                stroke = t.border();
            }
            case IMAGE -> {
                fill = t.background();
                stroke = t.border();
                radius = 0;
            }
            case ACTION_ROW -> {
                fill = node.selected() ? t.selected() : hover && node.enabled() ? t.hover() : t.panel();
                stroke = node.selected() ? t.accent() : t.border();
            }
            case MENU, DIALOG -> {
                fill = t.raised();
                stroke = t.border();
                radius = kind == Kind.MENU ? 4 : 5;
            }
            case SHADOW -> {
                fill = t.shadow();
                radius = 5;
            }
            case INPUT -> {
                fill = t.raised();
                stroke = visual.invalid() ? t.danger() : focus ? t.accent() : t.border();
                if (disabled) foreground = t.disabled();
            }
            case SLOT -> {
                fill = visual.empty() ? t.background() : t.panel();
                stroke = node.selected() || visual.peer() ? t.accent() : t.border();
                if (node.selected()) fill = t.selected();
                else if (hover) fill = t.hover();
            }
            case DOCUMENT_TAB -> {
                radius = 0;
                fill = node.selected() ? t.raised() : hover ? t.hover() : t.panel();
                foreground = node.selected() ? t.text() : t.muted();
            }
            case TREE_ROW, TAB -> {
                if (node.selected()) fill = t.selected();
                else if (hover) fill = t.hover();
                foreground = node.selected() ? t.accent() : foreground;
                radius = 2;
            }
            case BUTTON, ICON -> {
                if (visual.variant() == Variant.PRIMARY) {
                    fill = t.accent();
                    foreground = t.onAccent();
                } else if (visual.variant() == Variant.OUTLINED) {
                    fill = t.raised();
                    stroke = t.border();
                }
                if (node.selected() && visual.variant() != Variant.PRIMARY) {
                    fill = t.selected();
                    foreground = t.accent();
                } else if (hover && node.enabled()) fill = visual.variant() == Variant.PRIMARY ? mix(t.accent(), 0xffffffff, .07) : t.hover();
            }
            case SCROLL_TRACK -> {
                fill = t.background();
                radius = 2;
            }
            case SCROLL_THUMB -> {
                fill = t.border();
                radius = 2;
            }
            case SOLID -> {
                fill = visual.color() == null ? t.border() : visual.color();
                radius = 0;
            }
            case SEPARATOR -> {
                fill = t.border();
                radius = 0;
            }
            case LABEL, CHECKBOX -> {}
        }
        if (pressed && node.enabled() && (kind == Kind.BUTTON || kind == Kind.ICON)) fill = t.selected();
        if (disabled && kind != Kind.INPUT) {
            foreground = mix(foreground, t.panel(), .6);
            if (fill != 0) fill = mix(fill, t.panel(), .6);
        }
        if (visual.dimmed()) {
            foreground = mix(foreground, t.raised(), .65);
            fill = mix(fill == 0 ? t.raised() : fill, t.raised(), .65);
            stroke = mix(stroke, t.raised(), .65);
        }
        if (fill != 0) rounded(g, b, radius, fill);
        if (stroke != 0) {
            if (kind == Kind.SLOT && visual.empty()) dashedBorder(g, b, stroke);
            else outline(g, b, radius, stroke);
        }
        if (kind == Kind.HEADER || kind == Kind.TOOLBAR) g.fill(b.x(), b.y() + b.height() - 1, b.x() + b.width(), b.y() + b.height(), t.border());
        if (kind == Kind.SECTION) g.fill(b.x(), b.y(), b.x() + b.width(), b.y() + 1, t.border());
        if (kind == Kind.DOCUMENT_TAB) {
            g.fill(b.x() + b.width() - 1, b.y(), b.x() + b.width(), b.y() + b.height(), t.border());
            if (node.selected()) g.fill(b.x(), b.y() + b.height() - 1, b.x() + b.width(), b.y() + b.height(), t.accent());
        }
        if (focus && kind != Kind.INPUT && node.focusable()) focusOutline(g, b, radius, t, visual.variant() == Variant.PRIMARY && (kind == Kind.BUTTON || kind == Kind.ICON));
        if (kind == Kind.IMAGE) {
            var image = visual.image();
            if (image != null) g.image(image.resource(), image.fallback(),
                    new Bounds(b.x() + 1, b.y() + 1, Math.max(0, b.width() - 2), Math.max(0, b.height() - 2)), t.muted());
            return false;
        }
        if (node.role() == Role.PANEL || kind == Kind.ACTION_ROW) return false;
        if (kind == Kind.CHECKBOX) {
            int size = Math.min(12, Math.min(b.width(), b.height()));
            var box = new Bounds(b.x() + (b.width() - size) / 2, b.y() + (b.height() - size) / 2, size, size);
            int accent = disabled ? t.disabled() : t.accent();
            rounded(g, box, 2, node.selected() ? accent : hover ? t.hover() : t.raised());
            outline(g, box, 2, node.selected() ? accent : t.border());
            if (node.selected() && size >= 8) {
                int cx = box.x() + 2, cy = box.y() + size / 2;
                for (int i = 0; i < 3; i++) g.fill(cx + i, cy + i, cx + i + 2, cy + i + 2, t.onAccent());
                for (int i = 0; i < 5; i++) g.fill(cx + 2 + i, cy + 2 - i, cx + 4 + i, cy + 4 - i, t.onAccent());
            }
            return false;
        }
        if (kind == Kind.INPUT && input != null) {
            int textX = b.x() + 6, textY = b.y() + (b.height() - TextSize.BODY.height) / 2;
            if (focus) for (var selection : input.selections()) {
                long start = (long) textX + selection.x() - input.origin(), end = start + selection.width();
                long from = Math.max(textX, start), to = Math.min((long) b.x() + b.width() - 6, end);
                if (to > from) g.fill(Math.toIntExact(from), b.y() + 3, Math.toIntExact(to), b.y() + b.height() - 3, t.selected());
            }
            if (input.text().isEmpty() && !focus) text(g, node.name(), textX, textY, Math.max(0, b.width() - 12), t.muted(), TextSize.BODY);
            else g.text(input.text(), textX - input.origin(), textY, foreground, TextSize.BODY, new Bounds(textX, b.y() + 2, Math.max(0, b.width() - 12), Math.max(0, b.height() - 4)));
            if (focus && input.caretVisible()) {
                int caret = textX + input.caretX() - input.origin();
                if (caret >= textX && caret < b.x() + b.width() - 5) g.fill(caret, b.y() + 4, caret + 1, b.y() + b.height() - 4, t.accent());
            }
            return input.width() > b.width() - 12;
        }
        if (kind == Kind.SLOT) {
            int pad = 5, width = Math.max(0, b.width() - 2 * pad), top = b.y() + 4;
            var lines = visual.lines();
            int secondary = visual.dimmed() ? mix(t.muted(), t.raised(), .65) : t.muted();
            if (!lines.isEmpty()) text(g, lines.get(0), b.x() + pad, top, width, secondary, TextSize.SMALL);
            if (lines.size() > 3 && !lines.get(3).isEmpty()) {
                String badge = lines.get(3);
                int badgeWidth = textLayout.width(badge, TextSize.SMALL);
                text(g, badge, b.x() + b.width() - pad - Math.min(width - 12, badgeWidth), top, Math.max(0, width - 12), t.accent(), TextSize.SMALL);
            }
            boolean truncated = text(g, node.name(), b.x() + pad, top + 11, width, foreground, TextSize.BODY);
            for (int i = 1; i < Math.min(3, lines.size()); i++) truncated |= text(g, lines.get(i), b.x() + pad, top + 21 + (i - 1) * 10, width, secondary, TextSize.SMALL);
            return truncated;
        }
        if (kind == Kind.LABEL && !visual.lines().isEmpty()) {
            int y = b.y();
            boolean truncated = false;
            for (String line : visual.lines()) {
                truncated |= text(g, line, b.x(), y, b.width(), foreground, visual.size());
                y += textLayout.lineHeight(visual.size());
            }
            return truncated;
        }
        int padding = kind == Kind.LABEL ? 0 : kind == Kind.TREE_ROW ? 3 : 6, textX = b.x() + padding, available = Math.max(0, b.width() - padding * 2);
        if (visual.icon() != Icon.NONE) {
            int ix = kind == Kind.ICON ? b.x() + (b.width() - StudioIcon.SIZE) / 2 : textX;
            icon(g, visual.icon(), ix, b.y() + (b.height() - StudioIcon.SIZE) / 2, foreground);
            if (kind == Kind.ICON) return !node.name().isBlank();
            textX += StudioIcon.ADVANCE;
            available = Math.max(0, available - StudioIcon.ADVANCE);
        }
        int textY = b.y() + (b.height() - visual.size().height) / 2;
        return text(g, node.name(), textX, textY, available, foreground, visual.size());
    }

    private boolean text(StudioCanvas g, String value, int x, int y, int width, int color, TextSize size) {
        boolean truncated = textLayout.width(value, size) > width;
        String shown = textLayout.ellipsize(value, Math.max(0, width), size);
        g.text(shown, x, y, color, size);
        return truncated;
    }

    private static int mix(int a, int b, double amount) {
        int r = (int) (((a >>> 16) & 255) * (1 - amount) + ((b >>> 16) & 255) * amount), g = (int) (((a >>> 8) & 255) * (1 - amount) + ((b >>> 8) & 255) * amount), blue = (int) ((a & 255) * (1 - amount) + (b & 255) * amount);
        return 0xff000000 | r << 16 | g << 8 | blue;
    }

    private static void focusOutline(StudioCanvas g, Bounds b, int radius, WorkbenchTheme t, boolean primary) {
        if (!primary) {
            outline(g, b, radius, t.accent());
            return;
        }
        // Keep the prototype's accent outline separate from an accent-filled button.
        // Each node is scissored to its visible bounds, so the contrasting gap must sit inside.
        if (b.width() < 5 || b.height() < 5) {
            outline(g, b, radius, t.onAccent());
            return;
        }
        outline(g, b, radius, t.accent());
        outline(g, new Bounds(b.x() + 1, b.y() + 1, b.width() - 2, b.height() - 2), Math.max(0, radius - 1), t.onAccent());
    }

    private static void rounded(StudioCanvas g, Bounds b, int radius, int color) {
        radius = Math.max(0, Math.min(radius, Math.min(b.width(), b.height()) / 2));
        if (radius == 0) {
            g.fill(b.x(), b.y(), b.x() + b.width(), b.y() + b.height(), color);
            return;
        }
        g.fill(b.x(), b.y() + radius, b.x() + b.width(), b.y() + b.height() - radius, color);
        for (int i = 0; i < radius; i++) {
            int inset = (int) Math.ceil(radius - Math.sqrt(radius * radius - (radius - i - .5) * (radius - i - .5)));
            g.fill(b.x() + inset, b.y() + i, b.x() + b.width() - inset, b.y() + i + 1, color);
            g.fill(b.x() + inset, b.y() + b.height() - i - 1, b.x() + b.width() - inset, b.y() + b.height() - i, color);
        }
    }

    private static void outline(StudioCanvas g, Bounds b, int radius, int color) {
        int r = Math.min(radius, Math.min(b.width(), b.height()) / 2);
        if (b.width() < 1 || b.height() < 1) return;
        g.fill(b.x() + r, b.y(), b.x() + b.width() - r, b.y() + 1, color);
        g.fill(b.x() + r, b.y() + b.height() - 1, b.x() + b.width() - r, b.y() + b.height(), color);
        g.fill(b.x(), b.y() + r, b.x() + 1, b.y() + b.height() - r, color);
        g.fill(b.x() + b.width() - 1, b.y() + r, b.x() + b.width(), b.y() + b.height() - r, color);
        for (int i = 0; i < r; i++) {
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - (r - i - .5) * (r - i - .5)));
            for (int xx : new int[] { b.x() + inset, b.x() + b.width() - inset - 1 }) {
                g.fill(xx, b.y() + i, xx + 1, b.y() + i + 1, color);
                g.fill(xx, b.y() + b.height() - i - 1, xx + 1, b.y() + b.height() - i, color);
            }
        }
    }

    private static void dashedBorder(StudioCanvas g, Bounds b, int color) {
        for (int x = b.x() + 2; x < b.x() + b.width() - 2; x += 4) {
            g.fill(x, b.y(), Math.min(x + 2, b.x() + b.width() - 2), b.y() + 1, color);
            g.fill(x, b.y() + b.height() - 1, Math.min(x + 2, b.x() + b.width() - 2), b.y() + b.height(), color);
        }
        for (int y = b.y() + 2; y < b.y() + b.height() - 2; y += 4) {
            g.fill(b.x(), y, b.x() + 1, Math.min(y + 2, b.y() + b.height() - 2), color);
            g.fill(b.x() + b.width() - 1, y, b.x() + b.width(), Math.min(y + 2, b.y() + b.height() - 2), color);
        }
    }

    private static void icon(StudioCanvas g, Icon icon, int x, int y, int color) {
        g.icon(icon, new Bounds(x, y, StudioIcon.SIZE, StudioIcon.SIZE), color);
    }
}
