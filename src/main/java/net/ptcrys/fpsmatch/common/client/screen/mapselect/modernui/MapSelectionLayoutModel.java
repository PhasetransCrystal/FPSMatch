package net.ptcrys.fpsmatch.common.client.screen.mapselect.modernui;

/**
 * Pure responsive layout calculation used by the Modern UI map-room screen.
 * Full-width room browser. Room rows open the unified lobby; legacy detail bounds
 * remain empty for compatibility with older bindings.
 */
public record MapSelectionLayoutModel(
                                      Rect header,
                                      Rect toast,
                                      Rect list,
                                      Rect detail,
                                      boolean compact) {

    public record Rect(int x, int y, int width, int height) {

        public Rect {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("layout dimensions must be non-negative");
            }
        }

        public boolean intersects(Rect other) {
            return x < other.x + other.width && other.x < x + width && y < other.y + other.height && y < height + other.height;
        }
    }

    /**
     * Search and filters live in an inline toolbar above the room list. Compact windows
     * wrap that toolbar and use compact rows; the lobby owns room details.
     */
    public static MapSelectionLayoutModel responsive(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("screen dimensions must be positive");
        }
        int headerHeight = Math.min(height, height >= 180 ? 44 : Math.max(24, height / 4));
        // The notice band is reserved, even while hidden, so asynchronous feedback never shifts
        // the first room row.
        int toastHeight = Math.min(24, Math.max(0, height - headerHeight));
        int contentTop = headerHeight + toastHeight;
        int contentHeight = Math.max(0, height - contentTop);

        // Keep the browsing surface full width; compact rows are only needed when the toolbar wraps.
        return new MapSelectionLayoutModel(
                new Rect(0, 0, width, headerHeight),
                new Rect(0, headerHeight, width, toastHeight),
                new Rect(0, contentTop, width, contentHeight),
                new Rect(0, 0, 0, 0),
                width < 360 || contentHeight < 150);
    }
}
