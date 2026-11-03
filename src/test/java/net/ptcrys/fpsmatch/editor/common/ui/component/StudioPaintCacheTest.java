package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.WorkbenchTheme;
import org.junit.jupiter.api.Test;

class StudioPaintCacheTest {
    private static final WorkbenchTheme THEME=WorkbenchTheme.dark();
    private static final Visual LABEL=Visual.of(Kind.LABEL);
    private static StudioPainter painter() { return new StudioPainter(new StudioTextLayout((value,size)->value.length()*5)); }
    private static Node node(String id,String name) {
        return new Node(id,Role.BUTTON,name,"",new Bounds(10,20,100,24),true,false,false,"",0);
    }
    private static StudioCanvas.Picture picture(StudioPaintCache cache,Node node) {
        return cache.picture(node,LABEL,THEME,false,false,false,null,0);
    }

    @Test void equalRepublishedStateHitsWithoutRemeasuringAndKeepsOtherControls() {
        var calls=new AtomicInteger();
        var cache=new StudioPaintCache(new StudioPainter(new StudioTextLayout((value,size)->{calls.incrementAndGet();return value.length()*5;})));
        var first=picture(cache,node("first","First"));var second=picture(cache,node("second","Second"));int before=calls.get();
        assertSame(first,picture(cache,node("first","First")));assertSame(second,picture(cache,node("second","Second")));
        assertEquals(before,calls.get());assertEquals(2,cache.stats().compilations());assertEquals(2,cache.stats().hits());assertEquals(2,cache.stats().entries());
    }

    @Test void eachHoverFocusAndPressedChangeRecompilesOnlyItsControl() {
        for(int state=0;state<3;state++){
            var cache=new StudioPaintCache(painter());var node=node("button","Button");var other=picture(cache,node("other","Other"));
            var visual=Visual.of(Kind.BUTTON).variant(Variant.OUTLINED);
            var baseline=cache.picture(node,visual,THEME,false,false,false,null,0);
            var changed=cache.picture(node,visual,THEME,state==0,state==1,state==2,null,0);
            assertNotSame(baseline,changed);assertNotEquals(baseline.commands(),changed.commands());
            assertSame(changed,cache.picture(node,visual,THEME,state==0,state==1,state==2,null,0));
            assertSame(other,picture(cache,node("other","Other")));assertEquals(3,cache.stats().compilations());
        }
    }

    @Test void eachDynamicInputCoordinateAndBlinkStateParticipatesInTheKey() {
        var base=new StudioPainter.Input("abcdef",0,0,10,10,30,true);
        var alternatives=List.of(
                new StudioPainter.Input("xbcdef",0,0,10,10,30,true),
                new StudioPainter.Input("abcdef",5,0,10,10,30,true),
                new StudioPainter.Input("abcdef",0,5,10,10,30,true),
                new StudioPainter.Input("abcdef",0,0,15,10,30,true),
                new StudioPainter.Input("abcdef",0,0,10,15,30,true),
                new StudioPainter.Input("abcdef",0,0,10,10,35,true),
                new StudioPainter.Input("abcdef",0,0,10,10,30,false));
        for(var input:alternatives){
            var cache=new StudioPaintCache(painter());var node=node("input","Placeholder");var visual=Visual.of(Kind.INPUT);
            var old=cache.picture(node,visual,THEME,false,true,false,base,0);
            var changed=cache.picture(node,visual,THEME,false,true,false,input,0);
            assertNotSame(old,changed);assertSame(changed,cache.picture(node,visual,THEME,false,true,false,input,0));
            assertEquals(2,cache.stats().compilations());assertEquals(1,cache.stats().entries());
        }
    }

    @Test void generationThemeVisualAndGeometryChangesInvalidateAnOtherwiseEqualKey() {
        var scale=new AtomicInteger(5);var layout=new StudioTextLayout((value,size)->value.length()*scale.get());
        var cache=new StudioPaintCache(new StudioPainter(layout));
        var bounds=new Bounds(0,0,20,20);var node=new Node("label",Role.LABEL,"abcd","",bounds,true,false,false,"",0);
        var original=cache.picture(node,LABEL,THEME,false,false,false,null,layout.generation());assertFalse(original.truncated());
        scale.set(8);layout.generation(1);
        var reloaded=cache.picture(node,LABEL,THEME,false,false,false,null,layout.generation());assertTrue(reloaded.truncated());assertNotSame(original,reloaded);
        assertSame(reloaded,cache.picture(node,LABEL,THEME,false,false,false,null,1));
        var light=cache.picture(node,LABEL,WorkbenchTheme.light(),false,false,false,null,1);assertNotSame(reloaded,light);
        var changedVisual=cache.picture(node,LABEL.variant(Variant.MUTED),WorkbenchTheme.light(),false,false,false,null,1);assertNotSame(light,changedVisual);
        var moved=new Node("label",Role.LABEL,"abcd","",new Bounds(10,10,20,20),true,false,false,"",0);
        var changedGeometry=cache.picture(moved,LABEL.variant(Variant.MUTED),WorkbenchTheme.light(),false,false,false,null,1);assertNotSame(changedVisual,changedGeometry);
        assertEquals(5,cache.stats().compilations());assertEquals(1,cache.stats().entries());
    }

    @Test void lruBudgetRetainAndClearReleaseOnlyTheExpectedPictures() {
        var cache=new StudioPaintCache(painter(),2,1000);var a=node("a","A");var b=node("b","B");var c=node("c","C");
        var first=picture(cache,a);var second=picture(cache,b);assertSame(first,picture(cache,a));
        picture(cache,c);assertSame(first,picture(cache,a));assertNotSame(second,picture(cache,b));
        assertEquals(2,cache.stats().entries());assertTrue(cache.stats().weight()<=1000);
        cache.retain(Set.of("a"));assertEquals(1,cache.stats().entries());assertSame(first,picture(cache,a));
        cache.retain(Set.of());assertEquals(0,cache.stats().entries());assertEquals(0,cache.stats().weight());
        picture(cache,a);var before=cache.stats();cache.clear();assertEquals(0,cache.stats().entries());assertEquals(0,cache.stats().weight());
        assertEquals(before.compilations(),cache.stats().compilations());assertEquals(before.hits(),cache.stats().hits());
    }

    @Test void oversizedReplacementBypassesWithoutDiscardingThePreviousSmallState() {
        var cache=new StudioPaintCache(painter(),3,40);var small=node("control","a");var previous=picture(cache,small);var before=cache.stats();
        var large=node("control","x".repeat(100));var transientPicture=picture(cache,large);
        assertTrue(transientPicture.truncated());assertNotSame(previous,transientPicture);
        assertEquals(before.entries(),cache.stats().entries());assertEquals(before.weight(),cache.stats().weight());
        assertNotSame(transientPicture,picture(cache,large));assertSame(previous,picture(cache,small));
        picture(cache,node("id".repeat(100),"a"));assertEquals(before.entries(),cache.stats().entries());assertEquals(before.weight(),cache.stats().weight());
        var noRetention=new StudioPaintCache(painter(),0,0);
        assertNotSame(picture(noRetention,small),picture(noRetention,small));assertEquals(0,noRetention.stats().entries());assertEquals(0,noRetention.stats().weight());
        assertThrows(IllegalArgumentException.class,()->new StudioPaintCache(painter(),-1,100));
        assertThrows(IllegalArgumentException.class,()->new StudioPaintCache(painter(),1,-1));
    }

    @Test void retainedTextWeightCanEvictBeforeTheEntryCountLimit() {
        var cache=new StudioPaintCache(painter(),10,8);var a=node("a","A");var b=node("b","B");var c=node("c","C");
        var first=picture(cache,a);var second=picture(cache,b);
        assertEquals(8,cache.stats().weight());assertEquals(2,cache.stats().entries());
        assertSame(first,picture(cache,a));picture(cache,c);
        assertEquals(8,cache.stats().weight());assertEquals(2,cache.stats().entries());
        assertSame(first,picture(cache,a));assertNotSame(second,picture(cache,b));
        assertEquals(8,cache.stats().weight());assertEquals(2,cache.stats().entries());
    }

    @Test void failedCompilationRetainsTheOldEntryAndItsBudgetAccounting() {
        var failure=new IllegalStateException("font unavailable");
        var cache=new StudioPaintCache(new StudioPainter(new StudioTextLayout((value,size)->{
            if(value.equals("fail"))throw failure;
            return value.length()*5;
        })));
        var first=picture(cache,node("control","good"));var other=picture(cache,node("other","other"));var before=cache.stats();
        assertSame(failure,assertThrows(IllegalStateException.class,()->picture(cache,node("control","fail"))));
        assertEquals(before,cache.stats());assertSame(first,picture(cache,node("control","good")));assertSame(other,picture(cache,node("other","other")));
    }

    @Test void cachedPicturesCanBeConsumedInAlternatingPaintOrder() {
        var cache=new StudioPaintCache(painter());var a=node("a","A");var b=node("b","B");
        var pictures=List.of(picture(cache,a),picture(cache,b),picture(cache,a));
        assertSame(pictures.get(0),pictures.get(2));
        assertEquals(List.of("A","B","A"),pictures.stream().flatMap(p->p.commands().stream()).filter(StudioCanvas.Text.class::isInstance).map(StudioCanvas.Text.class::cast).map(StudioCanvas.Text::value).toList());
        assertEquals(2,cache.stats().compilations());assertEquals(1,cache.stats().hits());
    }
}
