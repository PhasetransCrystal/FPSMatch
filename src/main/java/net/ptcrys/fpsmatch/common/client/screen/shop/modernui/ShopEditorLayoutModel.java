package net.ptcrys.fpsmatch.common.client.screen.shop.modernui;

/** Layout in GUI units. Content scrolls independently from the header and actions. */
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
        var frame = frame(width, height);
        Rect body = frame.body();
        if (width < 620) {
            int categoryHeight = Math.min(24, body.height());
            int top = body.y() + categoryHeight + 6;
            int remaining = Math.max(0, body.height() - categoryHeight - 6);
            if (width >= 400) {
                int propertyWidth = Math.min(200, body.width() * 2 / 5);
                int slotWidth = body.width() - propertyWidth - 8;
                return new ShopEditorLayoutModel(frame.header(),
                        new Rect(body.x(), body.y(), body.width(), categoryHeight),
                        new Rect(body.x(), top, slotWidth, remaining),
                        new Rect(body.x() + slotWidth + 8, top, propertyWidth, remaining),
                        frame.actions(), true);
            }
            int propertyHeight = remaining >= 80 ? 38 : 0;
            int slotHeight = remaining - propertyHeight - (propertyHeight > 0 ? 6 : 0);
            return new ShopEditorLayoutModel(frame.header(),
                    new Rect(body.x(), body.y(), body.width(), categoryHeight),
                    new Rect(body.x(), top, body.width(), slotHeight),
                    new Rect(body.x(), top + slotHeight + (propertyHeight > 0 ? 6 : 0), body.width(), propertyHeight),
                    frame.actions(), true);
        }
        int categoryWidth = Math.min(152, body.width() / 5);
        int propertyWidth = Math.min(260, body.width() / 3);
        int slotWidth = body.width() - categoryWidth - propertyWidth - 16;
        return new ShopEditorLayoutModel(
                frame.header(),
                new Rect(body.x(), body.y(), categoryWidth, body.height()),
                new Rect(body.x() + categoryWidth + 8, body.y(), slotWidth, body.height()),
                new Rect(body.x() + categoryWidth + slotWidth + 16, body.y(), propertyWidth, body.height()),
                frame.actions(),
                false);
    }

    public record Frame(Rect header, Rect body, Rect actions) {}

    public record SlotLayout(Frame frame, Rect fields, Rect inventory, boolean split, int cellSize) {}

    public record BatchLayout(Frame frame, Rect category, Rect selectCategory, Rect clear, int toolbarHeight,
                              Rect groupLabel, Rect group, Rect apply, Rect status) {}

    public static Frame frame(int width, int height) {
        if (width < 240 || height < 160) throw new IllegalArgumentException("Shop viewport must be at least 240 x 160 GUI units");
        return new Frame(new Rect(8, 8, width - 16, 38),
                new Rect(8, 54, width - 16, height - 118),
                new Rect(8, height - 56, width - 16, 48));
    }

    public static SlotLayout slot(int width, int height) {
        Frame frame = frame(width, height);
        Rect body = frame.body();
        boolean split = width >= 420;
        int fieldWidth = split ? Math.min(320, (body.width() - 8) / 2) : body.width();
        int inventoryWidth = split ? body.width() - fieldWidth - 8 : body.width();
        return new SlotLayout(frame,
                new Rect(body.x(), body.y(), fieldWidth, body.height()),
                new Rect(split ? body.x() + fieldWidth + 8 : body.x(), body.y(), inventoryWidth, body.height()),
                split, Math.max(18, Math.min(28, (inventoryWidth - 16) / 9)));
    }

    public static BatchLayout batch(int width, int height) {
        Frame frame = frame(width, height);
        Rect body = frame.body();
        boolean narrow = width < 400;
        int categoryWidth = narrow ? body.width() : body.width() - 180;
        int controlsY = narrow ? 30 : 0;
        int footerY = frame.actions().y();
        int labelWidth = 60, applyWidth = Math.min(120, body.width() - labelWidth - 76);
        return new BatchLayout(frame,
                new Rect(0, 0, categoryWidth, 24),
                new Rect(narrow ? 0 : categoryWidth + 8, controlsY, narrow ? body.width() - 80 : 100, 24),
                new Rect(body.width() - 72, controlsY, 72, 24),
                narrow ? 60 : 30,
                new Rect(body.x(), footerY, labelWidth, 24),
                new Rect(body.x() + labelWidth + 4, footerY, body.width() - labelWidth - applyWidth - 12, 24),
                new Rect(body.x() + body.width() - applyWidth, footerY, applyWidth, 24),
                new Rect(body.x(), footerY + 28, body.width(), 20));
    }
}
