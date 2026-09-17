package com.ptcrys.fpsmatch.common.client.screen.shop.modernui;

/** Responsive bounds for the shop editor's preview, fields and action bar. */
public record ShopEditorLayoutModel(
                                    Rect header,
                                    Rect categories,
                                    Rect slots,
                                    Rect properties,
                                    Rect actions,
                                    boolean compact) {

    public record Rect(int x, int y, int width, int height) {

        public Rect {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("layout dimensions must be non-negative");
            }
        }

        public boolean intersects(Rect other) {
            return x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height;
        }
    }

    public static ShopEditorLayoutModel responsive(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("screen dimensions must be positive");
        }
        boolean shortViewport = height < 280;
        int headerHeight = shortViewport ? 42 : Math.min(56, Math.max(46, height / 8));
        int actionHeight = Math.min(40, Math.max(32, height / 10));
        int contentTop = headerHeight;
        int contentHeight = Math.max(1, height - headerHeight - actionHeight);
        if (width < 640) {
            int categoryHeight = shortViewport ? Math.min(26, Math.max(22, contentHeight / 5)) : Math.min(42, Math.max(28, contentHeight / 6));
            int propertyHeight = shortViewport ? Math.min(40, Math.max(32, contentHeight / 4)) : Math.min(104, Math.max(72, contentHeight / 3));
            int slotHeight = Math.max(1, contentHeight - categoryHeight - propertyHeight);
            return new ShopEditorLayoutModel(
                    new Rect(0, 0, width, headerHeight),
                    new Rect(0, contentTop, width, categoryHeight),
                    new Rect(0, contentTop + categoryHeight, width, slotHeight),
                    new Rect(0, contentTop + categoryHeight + slotHeight, width, propertyHeight),
                    new Rect(0, height - actionHeight, width, actionHeight),
                    true);
        }
        int categoryWidth = Math.max(120, Math.min(180, width / 5));
        int propertyWidth = Math.max(190, Math.min(280, width / 3));
        int slotWidth = Math.max(1, width - categoryWidth - propertyWidth);
        return new ShopEditorLayoutModel(
                new Rect(0, 0, width, headerHeight),
                new Rect(0, contentTop, categoryWidth, contentHeight),
                new Rect(categoryWidth, contentTop, slotWidth, contentHeight),
                new Rect(categoryWidth + slotWidth, contentTop, propertyWidth, contentHeight),
                new Rect(0, height - actionHeight, width, actionHeight),
                false);
    }
}
