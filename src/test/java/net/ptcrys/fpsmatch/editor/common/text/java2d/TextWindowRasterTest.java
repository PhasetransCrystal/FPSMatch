package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextWindowRasterTest {
    private static TextLine line(String text) {
        var backend=new Java2dTextBackend();
        var face=backend.registerLogical(TestFonts.id("window"),"Dialog");
        return backend.layout(text,List.of(face),13f,1f);
    }
    @Test void windowMatchesTheSameWholeLineAtUnitDensityIncludingPadding() {
        var line=line("jAg é אבג end");var full=line.rasterize();var window=line.rasterize(1f,12f,30f);
        int[] all=full.argb(),part=window.argb();
        for(int y=0;y<window.height();y++)for(int x=0;x<window.width();x++){
            int fullX=x+Math.round(full.originX()-window.originX()),fullY=y+Math.round(full.baseline()-window.baseline());
            int expected=fullX<0||fullY<0||fullX>=full.width()||fullY>=full.height()?0:all[fullY*full.width()+fullX];
            assertEquals(expected,part[y*window.width()+x],"Window must preserve the original line's pixel positions");
        }
    }
    @Test void densitiesRenderTheOriginalLogicalLayoutIntoThePhysicalWindow() {
        String text="Scale é אבג";var line=line(text);float advance=line.advance(),caret=line.caretX(3);
        var oracle=new TextLayout(text,new Font("Dialog",Font.PLAIN,1).deriveFont(13f),new FontRenderContext(null,true,true));
        for(float density:new float[]{1f,1.5f,2f,3f}){
            var raster=line.rasterize(density,3.25f,70f);
            var expected=new BufferedImage(raster.width(),raster.height(),BufferedImage.TYPE_INT_ARGB);var graphics=expected.createGraphics();
            try{
                graphics.setColor(Color.BLACK);graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                graphics.translate(raster.originX(),raster.baseline());graphics.scale(density,density);oracle.draw(graphics,0f,0f);
            }finally{graphics.dispose();}
            assertArrayEquals(expected.getRGB(0,0,raster.width(),raster.height(),null,0,raster.width()),raster.argb());
            assertEquals(advance,line.advance());assertEquals(caret,line.caretX(3));
            int[] samples=raster.argb();for(int x=0;x<raster.width();x++){assertEquals(0,samples[x]);assertEquals(0,samples[(raster.height()-1)*raster.width()+x]);}
        }
    }
    @Test void full4096CharacterInputUsesOnlyAVisibleWindowBuffer() {
        var line=line("W".repeat(4096));assertTrue(line.advance()>Raster.MAX_SIDE);
        assertThrows(IllegalStateException.class,line::rasterize);
        var raster=line.rasterize(2f,line.advance()-96f,96f);
        assertTrue(raster.width()<=197);assertTrue(raster.height()<100);
        assertTrue(java.util.Arrays.stream(raster.argb()).anyMatch(pixel->(pixel>>>24)!=0));
        assertEquals(4096,line.text().length());assertEquals(4096,line.hitTest(line.advance()+1));
    }
    @Test void invalidDensityAndOversizedWindowsAreRejectedBeforeAllocation() {
        var line=line("valid");
        for(float density:new float[]{0f,-1f,Float.NaN,Float.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->line.rasterize(density,0,20));
        assertThrows(IllegalArgumentException.class,()->line.rasterize(1,0,-1));
        assertThrows(IllegalStateException.class,()->line.rasterize(2,0,Raster.MAX_SIDE));
        assertEquals(1,line.rasterize(2,0,0).width());
    }
    @Test void logicalSelectionExposesDisjointBidiSpansAndClusterBoundaries() {
        var line=line("abc אבג xyz");var spans=line.selectionSpans(0,5);
        assertEquals(2,spans.size());assertTrue(spans.get(0).x()+spans.get(0).width()<spans.get(1).x());
        assertEquals(List.of(),line.selectionSpans(3,3));
        var combined=line("Aé😀Z");assertEquals(1,combined.boundary(2));assertEquals(3,combined.boundary(4));
        assertEquals(combined.text().length(),combined.boundary(Integer.MAX_VALUE));
    }
}
