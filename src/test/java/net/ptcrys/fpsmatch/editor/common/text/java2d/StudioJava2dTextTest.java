package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.BODY;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioTextLayout;
import org.junit.jupiter.api.Test;

class StudioJava2dTextTest {
    @Test void geometryMetricsRasterAndWarmFramesReuseOneShapedLine() {
        try(var backend=new StudioJava2dText()){
            String raw="Mixed é אבג 中文";var shape=backend.line(raw,BODY);var layout=new StudioTextLayout(backend);var geometry=layout.line(raw,BODY);
            var raster=backend.raster(shape,2f,0f,80f);long calls=backend.stats().layoutCalls(),rasters=backend.stats().rasterCalls();
            for(int i=0;i<5;i++){
                assertSame(shape,backend.line(raw,BODY));assertSame(geometry,layout.line(raw,BODY));assertSame(raster,backend.raster(shape,2f,0f,80f));
                assertEquals((int)Math.ceil(shape.advance()),backend.width(raw,BODY));
                var view=geometry.revealAt(0,5,35);assertEquals(shape.hitTest(view.originX()+10),view.hitTest(10));
                assertEquals(Math.round(shape.caretX(5)),geometry.x(5));
                geometry.selections(0,5);
            }
            assertEquals(calls,backend.stats().layoutCalls());assertEquals(rasters,backend.stats().rasterCalls());
            backend.raster(shape,3f,0f,80f);assertEquals(calls,backend.stats().layoutCalls());assertEquals(rasters+1,backend.stats().rasterCalls());
        }
    }
    @Test void budgetsEvictAndClearReleasesResultsWithoutRecyclingIdentity() {
        var budget=new StudioJava2dText.Budget(1,20000,100,1,20000);
        try(var backend=new StudioJava2dText(budget)){
            var first=backend.line("first",BODY);backend.raster(first,1f,0f,30f);
            var second=backend.line("second",BODY);backend.raster(second,1f,0f,30f);
            assertEquals(1,backend.stats().shapeEntries());assertEquals(1,backend.stats().rasterEntries());
            assertEquals(1,backend.stats().shapeEvictions());assertEquals(1,backend.stats().rasterEvictions());
            long layouts=backend.stats().layoutCalls();backend.raster(first,2f,0f,30f);assertEquals(layouts,backend.stats().layoutCalls());
            backend.clear();assertEquals(0,backend.stats().shapeWeight());assertEquals(0,backend.stats().rasterBytes());
            assertEquals(0,backend.stats().shapeEntries());assertEquals(0,backend.stats().rasterEntries());
            assertNotEquals(first.identity(),backend.line("first",BODY).identity());assertEquals("first",first.text());
        }
    }
    @Test void oversizedEntriesBypassWithoutEvictingAUsefulSmallEntry() {
        try(var backend=new StudioJava2dText(new StudioJava2dText.Budget(1,20000,8,1,3000))){
            var small=backend.line("ok",BODY);var image=backend.raster(small,1f,0f,5f);
            var large=backend.line("W".repeat(4096),BODY);backend.raster(large,2f,0f,500f);
            assertSame(small,backend.line("ok",BODY));assertSame(image,backend.raster(small,1f,0f,5f));
            assertEquals(1,backend.stats().shapeBypasses());assertEquals(1,backend.stats().rasterBypasses());
            assertTrue(backend.stats().rasterBytes()<=3000);assertTrue(backend.stats().shapeWeight()<=20000);
        }
    }
}
