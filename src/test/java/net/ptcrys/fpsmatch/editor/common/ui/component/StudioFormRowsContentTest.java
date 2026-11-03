package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioFormRowsContentTest {
    private static StudioComponents components(StudioFrame frame){return new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);}
    private static StudioComponents.Field textField(String id,String raw){return new StudioComponents.Field(id,id,raw,raw,"",StudioComponents.FieldKind.TEXT,0,true,false,value->{},()->{},()->{});}
    private static ControlTree.Node node(StudioFrame frame,String id){return frame.nodes().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();}
    private static void assertHit(ControlTree tree,ControlTree.Node node){assertEquals(node.bounds(),node.visibleBounds());assertEquals(node.id(),tree.hit(node.bounds().x()+1,node.bounds().y()+1).orElseThrow().id());}

    @Test void shortBodySharesOneRailForHeadingFieldsErrorAndFooter(){
        var frame=new StudioFrame();var ui=components(frame);var viewport=new Bounds(20,40,200,60);
        List<List<StudioComponents.Field>> rows=List.of(List.of(textField("first","unfinished")),List.of(),List.of(textField("last","-")));
        int formHeight=ui.formRowsHeight(rows,viewport.width()-8),contentHeight=24+formHeight+13+4+20;
        int rowHeight=ui.formRowsHeight(List.of(rows.get(0)),viewport.width()-8);assertEquals(rowHeight*2,formHeight);
        for(int offset:List.of(0,rowHeight,contentHeight-viewport.height())){
            frame.clear();
            assertEquals(offset,ui.scrollContent("creation",viewport,contentHeight,0,offset,value->fail("Composition cannot change scroll state"),body->{
                ui.button(StudioComponents.Action.plain("heading","Choose type",()->{}),new Bounds(body.x(),body.y(),body.width(),20),0,Kind.BUTTON);
                assertEquals(formHeight,ui.formRowsContent(rows,new Bounds(body.x(),body.y()+24,body.width(),formHeight),0));
                ui.text("error","Complete the required field",new Bounds(body.x(),body.y()+24+formHeight,body.width(),13),0,TextSize.SMALL,Variant.DANGER);
                ui.button(StudioComponents.Action.plain("footer","Create",()->{}),new Bounds(body.x(),body.y()+24+formHeight+17,body.width(),20),0,Kind.BUTTON);
            }));
            assertEquals(1,frame.scrolls().size());assertEquals("creation/scroll/track",frame.scrolls().get(0).id());
            assertEquals(1L,frame.nodes().stream().filter(value->frame.visual(value.id()).kind()==Kind.SCROLL_TRACK).count());
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
            if(offset==0){assertHit(tree,node(frame,"heading"));assertHit(tree,node(frame,"first"));}
            else if(offset==rowHeight)assertHit(tree,node(frame,"last"));
            else {assertEquals(node(frame,"error").bounds(),node(frame,"error").visibleBounds());assertHit(tree,node(frame,"footer"));}
        }
    }

    @Test void actualContentWidthControlsReflowWithoutAnExtraRailOrImplicitHeightClip(){
        var frame=new StudioFrame();var ui=components(frame);
        List<List<StudioComponents.Field>> rows=List.of(List.of(textField("first","1"),textField("second","2")));
        int wide=ui.formRowsHeight(rows,146),narrow=ui.formRowsHeight(rows,138);assertTrue(narrow>wide);assertTrue(frame.nodes().isEmpty());
        assertEquals(wide,ui.formRowsContent(rows,new Bounds(30,40,146,1),0));
        assertEquals(new Bounds(36,56,65,20),node(frame,"first").bounds());assertEquals(new Bounds(105,56,65,20),node(frame,"second").bounds());
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());assertHit(tree,node(frame,"first"));assertHit(tree,node(frame,"second"));
        assertNull(frame.clip());assertTrue(frame.scrolls().isEmpty());
        frame.clear();assertEquals(narrow,ui.formRowsContent(rows,new Bounds(30,40,138,0),0));
        assertEquals(56,node(frame,"first").bounds().y());assertTrue(node(frame,"second").bounds().y()>=node(frame,"first").bounds().y()+node(frame,"first").bounds().height());assertTrue(frame.scrolls().isEmpty());
    }

    @Test void zeroWidthAndEmptyRowsPublishNothingAndKeepMeasuredHeight(){
        var frame=new StudioFrame();var ui=components(frame);var outer=new Bounds(10,20,100,60);
        List<List<StudioComponents.Field>> rows=List.of(List.of(),List.of(textField("first","1"),textField("second","2")),List.of());
        try(var ignored=frame.clipped(outer)){
            int height=ui.formRowsHeight(rows,0);assertTrue(height>0);assertEquals(height,ui.formRowsContent(rows,new Bounds(20,30,0,height),0));
            assertEquals(0,ui.formRowsHeight(List.of(List.of(),List.of()),100));
            assertEquals(0,ui.formRowsContent(List.of(List.of(),List.of()),new Bounds(20,30,100,100),0));assertEquals(outer,frame.clip());
        }
        assertTrue(frame.nodes().isEmpty());assertTrue(frame.scrolls().isEmpty());assertTrue(frame.actions().isEmpty());assertTrue(frame.changes().isEmpty());assertTrue(frame.blur().isEmpty());assertNull(frame.clip());
        assertThrows(IllegalArgumentException.class,()->ui.formRowsHeight(rows,-1));
    }

    @Test void nestedClipPreservesOffscreenSemanticsWithoutMakingThemFocusable(){
        var frame=new StudioFrame();var ui=components(frame);var outer=new Bounds(25,40,80,80);var inner=new Bounds(30,55,70,35);
        List<List<StudioComponents.Field>> rows=List.of(List.of(textField("first","1")),List.of(textField("middle","2")),List.of(textField("last","3")));
        try(var ignored=frame.clipped(outer)){
            try(var nested=frame.clipped(inner)){
                int height=ui.formRowsHeight(rows,200);assertEquals(height,ui.formRowsContent(rows,new Bounds(20,10,200,height),0));assertEquals(outer.intersect(inner),frame.clip());
                assertEquals(node(frame,"middle").bounds().intersect(outer).intersect(inner),node(frame,"middle").visibleBounds());assertTrue(node(frame,"middle").focusable());
                assertFalse(node(frame,"first").focusable());assertFalse(node(frame,"last").focusable());
                ui.button(StudioComponents.Action.plain("after","After",()->{}),new Bounds(20,85,200,20),0,Kind.BUTTON);
                assertEquals(new Bounds(30,85,70,5),node(frame,"after").visibleBounds());
            }
            assertEquals(outer,frame.clip());
        }
        assertNull(frame.clip());assertTrue(frame.scrolls().isEmpty());var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
        int middleY=node(frame,"middle").visibleBounds().y()+1;assertEquals("middle",tree.hit(31,middleY).orElseThrow().id());assertTrue(tree.hit(29,middleY).isEmpty());
    }

    @Test void rawValuesAndOriginalChangeChooseAndBlurCallbacksSurviveWideAndStackedRows(){
        for(int width:List.of(146,130)){
            var frame=new StudioFrame();var ui=components(frame);var raw=new AtomicReference<String>();var commits=new AtomicInteger();var choices=new AtomicInteger();var toggles=new AtomicInteger();
            Consumer<String> change=raw::set;Runnable commit=commits::incrementAndGet,choose=choices::incrementAndGet,toggle=toggles::incrementAndGet;
            var text=new StudioComponents.Field("form/raw","Raw","-1e","-1e","Incomplete number",StudioComponents.FieldKind.TEXT,0,true,true,change,()->{},commit);
            var choice=new StudioComponents.Field("form/choice","Mode","draft","Draft","",StudioComponents.FieldKind.CHOICE,0,true,false,value->fail("Choice is not text"),choose,()->{});
            var flag=new StudioComponents.Field("form/toggle","Enabled","true","On","",StudioComponents.FieldKind.TOGGLE,0,true,false,value->fail("Toggle is not text"),toggle,()->{});
            List<List<StudioComponents.Field>> rows=List.of(List.of(),List.of(text,choice),List.of(flag),List.of());
            int height=ui.formRowsHeight(rows,width);assertEquals(height,ui.formRowsContent(rows,new Bounds(10,20,width,height),0));
            var snapshot=frame.snapshot();assertEquals("-1e",node(frame,"form/raw").value());assertEquals("form/raw/row/",snapshot.groups().get("form/raw"));
            assertSame(change,snapshot.changes().get("form/raw"));assertSame(commit,snapshot.blur().get("form/raw"));assertSame(choose,snapshot.actions().get("form/choice"));assertSame(toggle,snapshot.actions().get("form/toggle"));
            assertNull(raw.get());assertEquals(0,commits.get());assertEquals(0,choices.get());assertEquals(0,toggles.get());
            var tree=new ControlTree();tree.publish(snapshot.nodes());for(String id:List.of("form/raw","form/choice","form/toggle"))assertHit(tree,node(frame,id));
            snapshot.changes().get("form/raw").accept("-");snapshot.blur().get("form/raw").run();snapshot.actions().get("form/choice").run();snapshot.actions().get("form/toggle").run();
            assertEquals("-",raw.get());assertEquals(1,commits.get());assertEquals(1,choices.get());assertEquals(1,toggles.get());assertTrue(frame.scrolls().isEmpty());
        }
    }

    @Test void actualLocalizedLabelsAndHintsDetermineTheNextRowAndScrollExtent(){
        var frame=new StudioFrame();var ui=components(frame);
        var first=new StudioComponents.Field("first","A localized label long enough to require several lines","-1e","","The field depends on another setting and keeps an unfinished number",StudioComponents.FieldKind.TEXT,0,true,true,value->{},()->{},()->{});
        List<List<StudioComponents.Field>> rows=List.of(List.of(first),List.of(textField("last","last raw")));
        int width=110,height=ui.formRowsHeight(rows,width);assertTrue(height>ui.formRowsHeight(rows,500));
        assertEquals(height,ui.formRowsContent(rows,new Bounds(0,0,width,1),0));
        var hint=node(frame,"first/hint");var last=node(frame,"last");assertTrue(last.bounds().y()>=hint.bounds().y()+hint.bounds().height());assertTrue(last.bounds().y()+last.bounds().height()<=height);
        frame.clear();int viewportHeight=40;assertEquals(height-viewportHeight,ui.formRows("body",rows,new Bounds(0,0,width+8,viewportHeight),0,Integer.MAX_VALUE,value->fail("Layout cannot scroll")));
        assertEquals(node(frame,"last").bounds(),node(frame,"last").visibleBounds());
    }

    @Test void standaloneWrapperMatchesExplicitSingleBodyComposition(){
        var wrapped=new StudioFrame();var composed=new StudioFrame();var wrapper=components(wrapped);var content=components(composed);var viewport=new Bounds(20,30,154,50);var outer=new Bounds(25,35,145,40);
        List<List<StudioComponents.Field>> rows=List.of(List.of(textField("first","1"),textField("second","2")),List.of(),List.of(textField("last","3")));
        IntConsumer change=value->fail("Composition cannot change scroll state");int wrappedOffset,composedOffset;
        try(var ignored=wrapped.clipped(outer)){wrappedOffset=wrapper.formRows("form",rows,viewport,0,100,change);}
        int height=content.formRowsHeight(rows,viewport.width()-8);
        try(var ignored=composed.clipped(outer)){composedOffset=content.scrollContent("form",viewport,height,0,100,change,body->content.formRowsContent(rows,body,0));}
        assertEquals(wrappedOffset,composedOffset);assertEquals(wrapped.snapshot().nodes(),composed.snapshot().nodes());assertEquals(wrapped.scrolls(),composed.scrolls());
        assertEquals(wrapped.changes(),composed.changes());assertEquals(wrapped.blur(),composed.blur());assertEquals(wrapped.groups(),composed.groups());assertEquals(1,wrapped.scrolls().size());
        assertNull(wrapped.clip());assertNull(composed.clip());
    }
}
