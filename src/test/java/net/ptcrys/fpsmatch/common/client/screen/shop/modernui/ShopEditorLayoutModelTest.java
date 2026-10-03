package net.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShopEditorLayoutModelTest {

    @Test
    void visibleRegionsStayInsideViewportWithoutOverlappingAcrossSizesAndGuiScales() {
        int[][] resolutions = { { 640, 480 }, { 854, 480 }, { 1280, 720 }, { 1920, 1080 }, { 2560, 1080 }, { 1080, 1920 } };
        for (int[] resolution : resolutions) {
            for (int scale = 1; scale <= 6; scale++) {
                int width = Math.max(240, (resolution[0] + scale - 1) / scale);
                int height = Math.max(160, (resolution[1] + scale - 1) / scale);
                var overview = ShopEditorLayoutModel.responsive(width, height);
                assertRegions(width, height, List.of(overview.header(), overview.categories(), overview.slots(), overview.properties(), overview.actions()));
                var slot = ShopEditorLayoutModel.slot(width, height);
                assertRegions(width, height, List.of(slot.frame().header(), slot.frame().body(), slot.frame().actions()));
                assertTrue(slot.cellSize() * 9 <= slot.inventory().width(), "All nine inventory columns must fit");
                if (slot.split()) {
                    assertRegions(width, height, List.of(slot.fields(), slot.inventory()));
                    assertTrue(slot.fields().width() >= 180, "Three value rows need readable inputs");
                }
                var batch = ShopEditorLayoutModel.batch(width, height);
                assertRegions(width, height, List.of(batch.frame().header(), batch.frame().body(), batch.groupLabel(), batch.group(), batch.apply(), batch.status()));
                assertRegions(batch.frame().body().width(), batch.toolbarHeight(), List.of(batch.category(), batch.selectCategory(), batch.clear()));
                assertTrue(batch.group().width() >= 48, "Group input must remain editable");
            }
        }
    }

    @Test
    void narrowWindowsStackAndWideWindowsExposeParallelRegions() {
        var narrow = ShopEditorLayoutModel.responsive(320, 240);
        assertTrue(narrow.slots().y() > narrow.categories().y());
        assertTrue(narrow.properties().y() > narrow.slots().y());
        var medium = ShopEditorLayoutModel.responsive(480, 270);
        assertEquals(medium.slots().y(), medium.properties().y());
        var wide = ShopEditorLayoutModel.responsive(960, 540);
        assertFalse(wide.compact());
        assertEquals(wide.categories().y(), wide.slots().y());
        assertFalse(ShopEditorLayoutModel.slot(320, 240).split());
        assertTrue(ShopEditorLayoutModel.slot(480, 270).split());
        assertTrue(ShopEditorLayoutModel.batch(320, 240).toolbarHeight() > ShopEditorLayoutModel.batch(480, 270).toolbarHeight());
    }

    @Test
    void shortWindowsReserveActionsAndScrollTheContent() {
        var shortWindow = ShopEditorLayoutModel.slot(480, 160);
        var tallWindow = ShopEditorLayoutModel.slot(480, 540);
        assertEquals(48, shortWindow.frame().actions().height());
        assertEquals(tallWindow.cellSize(), shortWindow.cellSize());
        assertTrue(shortWindow.frame().body().height() < tallWindow.frame().body().height());
    }

    private static void assertRegions(int width, int height, List<ShopEditorLayoutModel.Rect> regions) {
        for (var region : regions) {
            assertTrue(region.x() >= 0 && region.y() >= 0, region.toString());
            assertTrue(region.x() + region.width() <= width, region.toString());
            assertTrue(region.y() + region.height() <= height, region.toString());
        }
        for (int i = 0; i < regions.size(); i++) for (int j = i + 1; j < regions.size(); j++) {
            var first = regions.get(i);
            var second = regions.get(j);
            if (first.width() > 0 && first.height() > 0 && second.width() > 0 && second.height() > 0)
                assertFalse(first.intersects(second), first + " overlaps " + second);
        }
    }
}
