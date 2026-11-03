package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.*;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StudioTextCacheTest {
    @Test void repeatedWidthWrapEllipsisAndLineRequestsDoNotMeasureAgain() {
        var calls=new AtomicInteger();var text=new StudioTextLayout((value,size)->{calls.incrementAndGet();return value.codePointCount(0,value.length())*5;});
        text.width("cached",BODY);int before=calls.get();text.width("cached",BODY);assertEquals(before,calls.get());
        var wrapped=text.wrap("alpha beta gamma",30,BODY);before=calls.get();assertSame(wrapped,text.wrap("alpha beta gamma",30,BODY));assertEquals(before,calls.get());
        var ellipsis=text.ellipsize("alpha beta gamma",30,BODY);before=calls.get();assertSame(ellipsis,text.ellipsize("alpha beta gamma",30,BODY));assertEquals(before,calls.get());
        var line=text.line("cached😀",BODY);before=calls.get();assertSame(line,text.line("cached😀",BODY));assertEquals(before,calls.get());
        assertTrue(text.cacheStats().hits()>=4);
    }

    @Test void explicitResourceGenerationInvalidatesTheSameMetricsObject() {
        var scale=new AtomicInteger(5);var calls=new AtomicInteger();
        StudioTextLayout.Metrics metrics=(value,size)->{calls.incrementAndGet();return value.length()*scale.get();};
        var text=new StudioTextLayout(metrics);var old=text.line("abcd",BODY);assertEquals(20,old.width());
        var oldWrap=text.wrap("abcd",20,BODY);assertEquals(1,oldWrap.lines().size());assertEquals("abcd",text.ellipsize("abcd",20,BODY));
        scale.set(8);int before=calls.get();text.generation(0);assertSame(old,text.line("abcd",BODY));assertEquals(before,calls.get());
        text.generation(1);assertEquals(0,text.cacheStats().entries());var changed=text.line("abcd",BODY);
        assertNotSame(old,changed);assertEquals(32,changed.width());assertEquals(1,changed.generation());assertEquals(20,old.width());assertEquals(0,old.generation());
        assertEquals(2,text.wrap("abcd",20,BODY).lines().size());assertEquals("a…",text.ellipsize("abcd",20,BODY));assertEquals(1,oldWrap.lines().size());
        assertTrue(calls.get()>before);text.clearCache();assertEquals(0,text.cacheStats().entries());assertEquals(0,text.cacheStats().weight());assertEquals(1,text.generation());
    }

    @Test void lruEvictionAndWeightRemainWithinTheConfiguredBudget() {
        var calls=new HashMap<String,Integer>();var budget=new StudioTextLayout.CacheBudget(2,160,20);
        var text=new StudioTextLayout((value,size)->{calls.merge(value,1,Integer::sum);return value.length();},budget);
        text.width("a",BODY);text.width("b",BODY);text.width("a",BODY);text.width("c",BODY);text.width("a",BODY);text.width("b",BODY);
        assertEquals(1,calls.get("a"));assertEquals(2,calls.get("b"));assertTrue(text.cacheStats().evictions()>0);
        for(int index=0;index<100;index++){text.width("entry"+index,BODY);assertTrue(text.cacheStats().entries()<=budget.maxEntries());assertTrue(text.cacheStats().weight()<=budget.maxWeight());}
    }

    @Test void oversizedRequestsBypassTheWholeOperationWithoutPollutingSmallEntries() {
        var text=new StudioTextLayout((value,size)->value.length()*5,new StudioTextLayout.CacheBudget(20,2048,8));
        text.width("small",BODY);var before=text.cacheStats();String large="abcdefghijklmnop".repeat(8);
        assertEquals(large.length()*5,text.width(large,BODY));assertFalse(text.wrap(large,20,BODY).lines().isEmpty());
        assertEquals("abc…",text.ellipsize(large,20,BODY));assertEquals(large,text.line(large,BODY).text());
        assertEquals(before.entries(),text.cacheStats().entries());assertEquals(before.weight(),text.cacheStats().weight());assertTrue(text.cacheStats().bypasses()>0);
        var noRetention=new StudioTextLayout((value,size)->value.length(),new StudioTextLayout.CacheBudget(0,0,0));
        assertEquals(4,noRetention.line("full",BODY).width());assertEquals(0,noRetention.cacheStats().entries());
    }

    @Test void oversizedLineGeometryBypassesEvenWhenItsRawTextFitsTheLengthLimit() {
        var text=new StudioTextLayout((value,size)->value.length(),new StudioTextLayout.CacheBudget(10,160,100));
        text.width("keep",BODY);var before=text.cacheStats();assertEquals(10,text.line("0123456789",BODY).width());
        assertEquals(before.entries(),text.cacheStats().entries());assertEquals(before.weight(),text.cacheStats().weight());
        assertThrows(IllegalArgumentException.class,()->new StudioTextLayout.CacheBudget(-1,10,10));
    }
}
