package net.ptcrys.fpsmatch.editor.common.ui.component;

import java.util.List;

/**
 * Host-neutral presentation vocabulary from the approved #fps-studio design.
 * Business identifiers never select paint rules. New contributors compose these kinds and states.
 */
public final class StudioComponent {

    private StudioComponent() {}

    public enum Kind {
        LABEL,
        IMAGE,
        BUTTON,
        ICON,
        CHECKBOX,
        TAB,
        DOCUMENT_TAB,
        TREE_ROW,
        INPUT,
        PANEL,
        HEADER,
        TOOLBAR,
        SECTION,
        INLINE_CARD,
        ACTION_ROW,
        SLOT,
        MENU,
        DIALOG,
        SHADOW,
        SEPARATOR,
        SCROLL_TRACK,
        SCROLL_THUMB,
        SOLID
    }

    public enum Variant {
        DEFAULT,
        PRIMARY,
        OUTLINED,
        DANGER,
        MUTED
    }

    public enum TextSize {

        SMALL(8),
        BODY(9),
        HEADING(10),
        TITLE(13);

        public final int height;

        TextSize(int height) {
            this.height = height;
        }
    }

    public enum Icon {
        NONE,
        BOX,
        FOLDER,
        TREE,
        SLIDERS,
        SEARCH,
        PLUS,
        CLOSE,
        MORE,
        CHEVRON_RIGHT,
        CHEVRON_DOWN,
        PREVIOUS,
        NEXT,
        UP,
        DOWN,
        UNDO,
        REDO,
        PLAY,
        SAVE,
        MAXIMIZE,
        RESTORE,
        COPY,
        PASTE,
        CLEAR,
        SWAP,
        SETTINGS,
        SUN,
        MOON,
        WARNING,
        CLOCK,
        MAP,
        TEAM,
        POINT,
        AREA,
        SHOP,
        RULE,
        LAYOUT,
        GRID,
        RESOURCES,
        DELETE,
        MINUS,
        ERASER
    }

    /** Local resource reference; loading and failure fallback are owned by the host renderer. */
    public record Image(String resource, Icon fallback) {

        public Image {
            resource = resource == null ? "" : resource.strip();
            java.util.Objects.requireNonNull(fallback);
        }
    }

    public record Visual(Kind kind, Variant variant, Icon icon, TextSize size, List<String> lines,
                         boolean empty, boolean peer, boolean dimmed, boolean invalid, Integer color, Image image) {

        public Visual {
            lines = List.copyOf(lines);
        }

        public Visual(Kind kind, Variant variant, Icon icon, TextSize size, List<String> lines,
                      boolean empty, boolean peer, boolean dimmed, boolean invalid, Integer color) {
            this(kind, variant, icon, size, lines, empty, peer, dimmed, invalid, color, null);
        }

        public static Visual of(Kind kind) {
            return new Visual(kind, Variant.DEFAULT, Icon.NONE, TextSize.BODY, List.of(), false, false, false, false, null);
        }

        public Visual variant(Variant value) {
            return new Visual(kind, value, icon, size, lines, empty, peer, dimmed, invalid, color, image);
        }

        public Visual icon(Icon value) {
            return new Visual(kind, variant, value, size, lines, empty, peer, dimmed, invalid, color, image);
        }

        public Visual size(TextSize value) {
            return new Visual(kind, variant, icon, value, lines, empty, peer, dimmed, invalid, color, image);
        }

        public Visual lines(List<String> value) {
            return new Visual(kind, variant, icon, size, value, empty, peer, dimmed, invalid, color, image);
        }

        public Visual flags(boolean isEmpty, boolean isPeer, boolean isDimmed, boolean isInvalid) {
            return new Visual(kind, variant, icon, size, lines, isEmpty, isPeer, isDimmed, isInvalid, color, image);
        }

        public Visual color(int value) {
            return new Visual(kind, variant, icon, size, lines, empty, peer, dimmed, invalid, value, image);
        }

        public Visual image(Image value) {
            return new Visual(kind, variant, icon, size, lines, empty, peer, dimmed, invalid, color, value);
        }
    }
}
