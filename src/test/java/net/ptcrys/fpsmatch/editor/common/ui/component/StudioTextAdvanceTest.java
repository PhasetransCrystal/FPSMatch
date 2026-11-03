package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.BODY;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize;
import org.junit.jupiter.api.Test;

class StudioTextAdvanceTest {
    @Test void bulkMetricsMeasureLongUncachedLinesInOnePassWithoutPrefixStrings() {
        var calls=new AtomicInteger();var visited=new AtomicInteger();
        var metrics=new StudioTextLayout.Metrics() {
            @Override public int width(String value,TextSize size) { throw new AssertionError("bulk line must not measure prefixes"); }
            @Override public int[] prefixAdvances(String value,TextSize size) {
                calls.incrementAndGet();int[] advances=new int[value.codePointCount(0,value.length())+1];int width=0;
                for(int slot=1,index=0;index<value.length();slot++){
                    int point=value.codePointAt(index);index+=Character.charCount(point);visited.incrementAndGet();
                    if(Character.getType(point)!=Character.NON_SPACING_MARK)width+=5;
                    advances[slot]=width;
                }
                return advances;
            }
        };
        var layout=new StudioTextLayout(metrics,new StudioTextLayout.CacheBudget(10,160,8));
        String raw="A😀é".repeat(4096);int count=raw.codePointCount(0,raw.length());
        var first=layout.line(raw,BODY);assertEquals(raw,first.text());assertEquals(4096*15,first.width());
        assertEquals(1,calls.get());assertEquals(count,visited.get());assertEquals(0,layout.cacheStats().entries());
        assertEquals(1,first.boundary(2));assertEquals(5,first.x(2));assertEquals(10,first.x(3));
        var second=layout.line(raw,BODY);assertNotSame(first,second);
        assertEquals(2,calls.get());assertEquals(2*count,visited.get());assertEquals(0,layout.cacheStats().entries());
    }

    @Test void bulkAdvancesKeepWholePrefixRoundingAndDefensivelyCopyTheHostArray() {
        int[] host={0,5,9,14,14};var calls=new AtomicInteger();
        var layout=new StudioTextLayout(new StudioTextLayout.Metrics() {
            @Override public int width(String value,TextSize size) { throw new AssertionError("unexpected width call"); }
            @Override public int[] prefixAdvances(String value,TextSize size) { calls.incrementAndGet();return host; }
        });
        var line=layout.line("A😀é",BODY);
        assertEquals(14,line.width());assertEquals(9,line.x(3));assertEquals(1,line.boundary(2));
        assertEquals(new StudioTextLine.Selection(1,5,5,9),line.selection(1,5));
        host[2]=999;assertEquals(9,line.x(3));assertSame(line,layout.line(line.text(),BODY));assertEquals(1,calls.get());
    }

    @Test void legacyMetricsRetainContextualPrefixWidthsAndRejectMalformedBulkResults() {
        var legacy=new StudioTextLayout((value,size)->value.equals("AV")?8:value.length()*5);
        var line=legacy.line("AV",BODY);assertEquals(5,line.x(1));assertEquals(8,line.x(2));
        assertEquals(0,legacy.line("",BODY).width());
        for(int[] invalid:new int[][]{{0},{1,5},{0,-1},{0,5,10}}){
            var layout=new StudioTextLayout(new StudioTextLayout.Metrics() {
                @Override public int width(String value,TextSize size) { return 5; }
                @Override public int[] prefixAdvances(String value,TextSize size) { return invalid; }
            });
            assertThrows(IllegalArgumentException.class,()->layout.line("A",BODY));
            assertEquals(0,layout.cacheStats().entries());
        }
    }
}
