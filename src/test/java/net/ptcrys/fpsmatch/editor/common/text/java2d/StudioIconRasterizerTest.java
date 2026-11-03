package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Icon;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioIcon;
import org.junit.jupiter.api.Test;

class StudioIconRasterizerTest {
    @Test void everyIconRendersAtTheRequestedPhysicalSizeWithStraightWhiteCoverage(){
        var rasterizer=new StudioIconRasterizer();
        for(int side:new int[]{9,12,18,24,27})for(var icon:Icon.values()){
            var mask=rasterizer.mask(icon,side,side);
            assertEquals(side,mask.width());assertEquals(side,mask.height());assertEquals(side*side*4,mask.byteSize());
            var rgba=mask.rgba();boolean visible=false;
            for(int i=0;i<rgba.length;i+=4){
                assertEquals(255,Byte.toUnsignedInt(rgba[i]));assertEquals(255,Byte.toUnsignedInt(rgba[i+1]));assertEquals(255,Byte.toUnsignedInt(rgba[i+2]));
                visible|=rgba[i+3]!=0;
            }
            assertEquals(icon!=Icon.NONE,visible,icon+" at "+side);
        }
    }

    @Test void fractionalDeviceScaleProducesCoverageInsteadOfEnlargedBinaryPixels(){
        var rasterizer=new StudioIconRasterizer();
        for(var icon:new Icon[]{Icon.CLOSE,Icon.SEARCH,Icon.CLOCK,Icon.CHEVRON_DOWN}){
            var rgba=rasterizer.mask(icon,18,18).rgba();boolean partial=false,transparent=false;
            for(int i=3;i<rgba.length;i+=4){int alpha=Byte.toUnsignedInt(rgba[i]);partial|=alpha>0&&alpha<255;transparent|=alpha==0;}
            assertTrue(partial,icon+" requires antialiased edge coverage");assertTrue(transparent);
        }
        var small=rasterizer.mask(Icon.CLOSE,12,12).rgba();var large=rasterizer.mask(Icon.CLOSE,24,24).rgba();
        boolean differsFromPixelDoubling=false;
        for(int y=0;y<24;y++)for(int x=0;x<24;x++)
            differsFromPixelDoubling|=large[(y*24+x)*4+3]!=small[((y/2)*12+x/2)*4+3];
        assertTrue(differsFromPixelDoubling,"paths must be rasterized again at the target resolution");
    }

    @Test void masksAreImmutableAndTheirKeysExcludeColorAndPosition(){
        var rasterizer=new StudioIconRasterizer();var first=rasterizer.mask(Icon.SAVE,18,18);
        var expected=first.rgba();var callerCopy=first.rgba();Arrays.fill(callerCopy,(byte)0);
        assertArrayEquals(expected,first.rgba());assertSame(first,rasterizer.mask(Icon.SAVE,18,18));
        assertEquals(new StudioIconRasterizer.Key(Icon.SAVE,18,18,StudioIcon.GEOMETRY_VERSION,StudioIconRasterizer.RASTER_VERSION),first.key());
        var input=first.rgba();var copy=new StudioIconRasterizer.Mask(first.key(),input);Arrays.fill(input,(byte)0);assertArrayEquals(expected,copy.rgba());
        assertNotEquals(first.key(),rasterizer.mask(Icon.SAVE,27,27).key());
        assertEquals(1,rasterizer.stats().hits());assertEquals(2,rasterizer.stats().rasterizations());
    }

    @Test void leastRecentlyUsedMasksAreEvictedWithoutInvalidatingReturnedValues(){
        var budget=new StudioIconRasterizer.Budget(2,100_000,32);var rasterizer=new StudioIconRasterizer(budget);
        var close=rasterizer.mask(Icon.CLOSE,18,18);var save=rasterizer.mask(Icon.SAVE,18,18);var original=save.rgba();
        assertSame(close,rasterizer.mask(Icon.CLOSE,18,18));rasterizer.mask(Icon.SEARCH,18,18);
        assertEquals(2,rasterizer.stats().entries());assertEquals(1,rasterizer.stats().evictions());assertTrue(rasterizer.stats().bytes()<=budget.maxBytes());
        assertArrayEquals(original,save.rgba());assertNotSame(save,rasterizer.mask(Icon.SAVE,18,18));
        long measured=rasterizer.stats().rasterizations();rasterizer.clear();
        assertEquals(0,rasterizer.stats().entries());assertEquals(0,rasterizer.stats().bytes());
        assertArrayEquals(original,rasterizer.mask(Icon.SAVE,18,18).rgba());assertEquals(measured+1,rasterizer.stats().rasterizations());
    }

    @Test void tinyCacheBypassesRetentionAndOversizedJobsAreRejectedBeforeAllocation(){
        var rasterizer=new StudioIconRasterizer(new StudioIconRasterizer.Budget(2,32,24));
        assertEquals(18,rasterizer.mask(Icon.CLOSE,18,18).width());assertEquals(0,rasterizer.stats().entries());assertEquals(1,rasterizer.stats().bypasses());
        var before=rasterizer.stats();
        assertThrows(IllegalArgumentException.class,()->rasterizer.mask(Icon.CLOSE,25,18));
        assertThrows(IllegalArgumentException.class,()->rasterizer.mask(Icon.CLOSE,0,18));
        assertThrows(IllegalArgumentException.class,()->rasterizer.mask(Icon.CLOSE,Integer.MAX_VALUE,18));
        assertEquals(before,rasterizer.stats());
        assertThrows(IllegalArgumentException.class,()->new StudioIconRasterizer.Mask(new StudioIconRasterizer.Key(Icon.CLOSE,18,18,StudioIcon.GEOMETRY_VERSION,StudioIconRasterizer.RASTER_VERSION),new byte[3]));
    }
}
