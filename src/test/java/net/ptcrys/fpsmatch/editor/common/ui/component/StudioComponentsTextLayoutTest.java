package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Icon;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Variant;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioComponentsTextLayoutTest {
    @Test void sharedServiceSuppliesBodyWidthsAndSizedWrappingFromItsExistingCache(){
        var calls=new AtomicInteger();
        var text=new StudioTextLayout((value,size)->{calls.incrementAndGet();return value.length()*(size==TextSize.SMALL?3:11);});
        int body=text.width("Button",TextSize.BODY);var wrapped=text.wrap("abcdef",6,TextSize.SMALL);int warm=calls.get();
        var ui=new StudioComponents(new StudioFrame(),StudioMetrics.compact(),text);
        for(int i=0;i<50;i++){
            assertEquals(body+12,ui.captionWidth("Button",Icon.NONE));
            assertEquals(body+28,ui.captionWidth("Button",Icon.PLUS));
            assertSame(wrapped.lines(),ui.wrap("abcdef",6,TextSize.SMALL));
        }
        assertEquals(List.of("ab","cd","ef"),wrapped.lines());assertEquals(warm,calls.get());
    }

    @Test void hostGenerationInvalidatesCaptionAndWrapMeasurementsTogether(){
        var advance=new AtomicInteger(1);var text=new StudioTextLayout((value,size)->value.length()*advance.get());
        var ui=new StudioComponents(new StudioFrame(),StudioMetrics.compact(),text,()->{});
        assertEquals(14,ui.captionWidth("AA",Icon.NONE));assertEquals(List.of("ab","cd"),ui.wrap("abcd",2));
        advance.set(2);text.generation(1);
        assertEquals(16,ui.captionWidth("AA",Icon.NONE));assertEquals(List.of("a","b","c","d"),ui.wrap("abcd",2));
        assertEquals(1,text.cacheStats().generation());
    }

    @Test void legacyMeasurementConstructorRetainsItsBodyScale(){
        var ui=new StudioComponents(new StudioFrame(),StudioMetrics.compact(),value->value.length()*9);
        assertEquals(39,ui.captionWidth("abc",Icon.NONE));
        assertEquals(List.of("ab","cd"),ui.wrap("abcd",16,TextSize.SMALL));
        assertEquals(List.of("ab","cd"),ui.wrap("abcd",26,TextSize.TITLE));
    }

    @Test void emptyAndNonpositiveWidthsKeepTheirExistingUnmeasuredContract(){
        var text=new StudioTextLayout((value,size)->{fail("These edge cases must not measure text");return 0;});
        var ui=new StudioComponents(new StudioFrame(),StudioMetrics.compact(),text);
        assertEquals(List.of(),ui.wrap("",100));assertEquals(List.of(),ui.wrap("",0));
        assertEquals(List.of("a\nb"),ui.wrap("a\nb",0));assertEquals(List.of("abc"),ui.wrap("abc",-1));
    }

    @Test void paragraphKeepsTerminatingNewlineAndThirteenPixelRowContracts(){
        var frame=new StudioFrame();
        var text=new StudioTextLayout(new StudioTextLayout.Metrics(){
            @Override public int width(String value,TextSize size){return value.length();}
            @Override public int lineHeight(TextSize size){return 40;}
        });
        var ui=new StudioComponents(frame,StudioMetrics.compact(),text);
        assertEquals(List.of("a"),ui.wrap("a\n",10));assertEquals(List.of("a",""),ui.wrap("a\n\n",10));
        assertEquals(List.of(""),ui.wrap("\n",10));assertEquals(List.of("","a"),ui.wrap("\na",10));
        assertEquals(39,ui.paragraph("paragraph","abcdef",new Bounds(5,7,2,100),0,Variant.DEFAULT));
        assertEquals(List.of("ab","cd","ef"),frame.nodes().stream().map(node->node.name()).toList());
        assertEquals(List.of(new Bounds(5,7,2,13),new Bounds(5,20,2,13),new Bounds(5,33,2,13)),frame.nodes().stream().map(node->node.bounds()).toList());
    }
}
