package net.ptcrys.fpsmatch.editor.common.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudioImageRasterizerTest {
    private static byte[] png(int width,int height,int... pixels) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,width,height,pixels,0,width);var output=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image,"png",output));return output.toByteArray();
    }
    private static int argb(byte[] rgba,int pixel){int offset=pixel*4;return (rgba[offset+3]&255)<<24|(rgba[offset]&255)<<16|(rgba[offset+1]&255)<<8|(rgba[offset+2]&255);}

    @Test void preservesOriginalColourAlphaAndDoesNotDecodeAgainOnCacheHits() throws Exception {
        byte[] png=png(2,1,0x80ff0000,0xff00ff00);var opened=new AtomicInteger();var closed=new AtomicInteger();
        var images=new StudioImageRasterizer(resource->{opened.incrementAndGet();return new ByteArrayInputStream(png){@Override public void close(){closed.incrementAndGet();}};});
        images.beginFrame();var raster=images.raster("fpsmatch:map.png",2,1).orElseThrow();
        assertEquals(0x80ff0000,argb(raster.rgba(),0));assertEquals(0xff00ff00,argb(raster.rgba(),1));
        var copy=raster.rgba();copy[0]=0;assertEquals(0x80ff0000,argb(raster.rgba(),0));
        for(int i=0;i<10;i++){images.beginFrame();assertSame(raster,images.raster("fpsmatch:map.png",2,1).orElseThrow());}
        assertEquals(1,opened.get());assertEquals(1,closed.get());assertEquals(1,images.stats().decodes());
    }

    @Test void coverUsesTheCentreWithoutStretchingTheWholeSource() throws Exception {
        byte[] png=png(4,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffff00,0xffff0000,0xff00ff00,0xff0000ff,0xffffff00);
        var images=new StudioImageRasterizer(resource->new ByteArrayInputStream(png));images.beginFrame();
        var rgba=images.raster("fpsmatch:wide.png",2,2).orElseThrow().rgba();
        assertEquals(0xff00ff00,argb(rgba,0));assertEquals(0xff0000ff,argb(rgba,1));
        assertEquals(0xff00ff00,argb(rgba,2));assertEquals(0xff0000ff,argb(rgba,3));
    }

    @Test void missesAreLimitedPerFrameAndCachedImagesDoNotConsumeTheQuota() throws Exception {
        byte[] png=png(1,1,0xff123456);var opened=new AtomicInteger();
        var images=new StudioImageRasterizer(resource->{opened.incrementAndGet();return new ByteArrayInputStream(png);});
        images.beginFrame();assertTrue(images.raster("fpsmatch:a.png",2,2).isPresent());
        assertTrue(images.raster("fpsmatch:b.png",2,2).isEmpty());assertEquals(1,opened.get());
        images.beginFrame();assertTrue(images.raster("fpsmatch:a.png",2,2).isPresent());
        assertTrue(images.raster("fpsmatch:b.png",2,2).isPresent());assertEquals(2,opened.get());
        assertTrue(images.raster("fpsmatch:a.png",4,4).isEmpty());
        images.beginFrame();assertTrue(images.raster("fpsmatch:a.png",4,4).isPresent());assertEquals(3,opened.get());
    }

    @Test void missingInvalidAndCorruptResourcesAreNegativeCachedUntilClear() throws Exception {
        var opened=new AtomicInteger();var replacement=new AtomicReference<byte[]>();
        var images=new StudioImageRasterizer(resource->{opened.incrementAndGet();if(replacement.get()!=null)return new ByteArrayInputStream(replacement.get());
            if(resource.equals("missing"))return null;if(resource.equals("invalid"))throw new IllegalArgumentException("invalid resource");
            return new ByteArrayInputStream(new byte[]{1,2,3});});
        images.beginFrame();assertTrue(images.raster("",2,2).isEmpty());assertEquals(0,opened.get());
        for(String resource:new String[]{"missing","invalid","corrupt"}){
            images.beginFrame();assertTrue(images.raster(resource,2,2).isEmpty());
            images.beginFrame();assertTrue(images.raster(resource,4,4).isEmpty());
        }
        assertEquals(3,opened.get());assertEquals(3,images.stats().negativeEntries());
        replacement.set(png(1,1,0xffabcdef));images.clear();assertEquals(0,images.stats().negativeEntries());
        images.beginFrame();assertTrue(images.raster("missing",2,2).isPresent());assertEquals(4,opened.get());
        replacement.set(png(1,1,0xff001122));images.clear();images.beginFrame();
        assertEquals(0xff001122,argb(images.raster("missing",2,2).orElseThrow().rgba(),0));
    }

    @Test void sourceBytesDimensionsAndOutputSizeAreBounded() throws Exception {
        var reads=new AtomicInteger();var closes=new AtomicInteger();
        var huge=new StudioImageRasterizer(resource->new InputStream(){
            @Override public int read(){reads.incrementAndGet();return 0;}
            @Override public int read(byte[] bytes,int offset,int length){reads.addAndGet(length);java.util.Arrays.fill(bytes,offset,offset+length,(byte)0);return length;}
            @Override public void close(){closes.incrementAndGet();}
        });
        huge.beginFrame();assertTrue(huge.raster("huge",2,2).isEmpty());
        assertEquals(StudioImageRasterizer.MAX_SOURCE_BYTES+1,reads.get());assertEquals(1,closes.get());
        byte[] oversized=png(1,1,0xff123456);ByteBuffer.wrap(oversized).putInt(16,StudioImageRasterizer.MAX_SOURCE_SIDE+1);
        var crc=new CRC32();crc.update(oversized,12,17);ByteBuffer.wrap(oversized).putInt(29,(int)crc.getValue());
        var images=new StudioImageRasterizer(resource->new ByteArrayInputStream(oversized));images.beginFrame();
        assertTrue(images.raster("oversized",2,2).isEmpty());assertEquals(1,images.stats().failures());
        assertThrows(IllegalArgumentException.class,()->images.raster("oversized",StudioImageRasterizer.MAX_SIDE+1,1));
    }

    @Test void positiveAndNegativeCachesHaveFiniteEvictionBudgets() throws Exception {
        byte[] png=png(1,1,0xff123456);var opened=new AtomicInteger();
        var images=new StudioImageRasterizer(resource->{opened.incrementAndGet();return resource.startsWith("bad")?null:new ByteArrayInputStream(png);},
                new StudioImageRasterizer.Budget(1,1024,1));
        for(String resource:new String[]{"a","b","a"}){images.beginFrame();assertTrue(images.raster(resource,2,2).isPresent());}
        assertEquals(3,opened.get());assertEquals(1,images.stats().entries());assertTrue(images.stats().bytes()<=1024);
        for(String resource:new String[]{"bad1","bad2","bad1"}){images.beginFrame();assertTrue(images.raster(resource,2,2).isEmpty());}
        assertEquals(6,opened.get());assertEquals(1,images.stats().negativeEntries());
        images.clear();assertEquals(0,images.stats().entries());assertEquals(0,images.stats().bytes());
    }
}
