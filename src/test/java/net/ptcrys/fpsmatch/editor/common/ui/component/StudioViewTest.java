package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;
import org.junit.jupiter.api.Test;

class StudioViewTest {
    private static StudioView view() { return new StudioView(StudioTextLayout.scaled(value -> value.length() * 5, 9), StudioMetrics.compact()); }

    @Test void frozenTranslationPublishesMeasuredParagraphLinesWithoutRemeasurement() {
        var calls = new AtomicInteger();
        var view = new StudioView(new StudioTextLayout((value,size) -> { calls.incrementAndGet(); return value.length() * 5; }), StudioMetrics.compact());
        var root = view.column("root", Style.defaults().gap(4), List.of(view.paragraph("paragraph", "alpha beta gamma"), view.button(StudioComponents.Action.plain("next", "Next", () -> fail("Composition invokes no action")))));
        var layout = view.layoutContent(root, 0, 0, 30);int measured = calls.get();
        var shifted = layout.translated(10, 25);var frame = new StudioFrame();shifted.emit(frame, 2);
        assertEquals(measured, calls.get());
        assertEquals(List.of("alpha", "beta", "gamma"), frame.visual("paragraph").lines());
        assertEquals(layout.contentHeight(), shifted.contentHeight());
        var source = layout.bounds("next");assertEquals(new Bounds(source.x()+10,source.y()+25,source.width(),source.height()), shifted.bounds("next"));
        var paragraph = shifted.bounds("paragraph");assertTrue(paragraph.y()+paragraph.height() <= shifted.bounds("next").y());
        assertEquals(2, frame.nodes().size());assertTrue(frame.nodes().stream().allMatch(node -> node.layer()==2));
    }

    @Test void realHintsAndErrorsExtendRowsAndActionsKeepTheirExactCallbacks() {
        var view = view();var change = new AtomicReference<String>();var commit = new AtomicInteger();var discard = new AtomicInteger();
        var field = new StudioComponents.Field("amount","A long localized amount label","-1e","","A hint with units and enough text to wrap",StudioComponents.FieldKind.TEXT,0,true,true,change::set,()->{},commit::incrementAndGet);
        var actions = new StudioComponents.FieldActions("row","Apply","Discard",true,()->{},discard::incrementAndGet,"The incomplete number cannot be applied yet");
        var narrow = view.layoutContent(view.fields("row",List.of(field),actions),0,0,100);
        var wide = view.layoutContent(view.fields("row",List.of(field),actions),0,0,400);
        assertTrue(narrow.contentHeight()>wide.contentHeight());
        assertTrue(narrow.bounds("amount/hint").y()>=narrow.bounds("amount").y()+narrow.bounds("amount").height());
        assertTrue(narrow.bounds("row/error").y()>=narrow.bounds("row/discard").y()+narrow.bounds("row/discard").height());
        var frame = new StudioFrame();narrow.emit(frame,0);var snapshot=frame.snapshot();
        assertEquals("-1e",frame.nodes().stream().filter(node->node.id().equals("amount")).findFirst().orElseThrow().value());
        assertEquals("row/",snapshot.groups().get("amount"));assertEquals(0,commit.get());assertNull(change.get());
        snapshot.changes().get("amount").accept("-");snapshot.actions().get("row/discard").run();
        assertEquals("-",change.get());assertEquals(1,discard.get());assertEquals(0,commit.get());
    }

    @Test void checkboxesUseASeparateLabelAndSquareHitTargetAcrossReflow() {
        var view=view();var selected=new AtomicInteger();
        var field=new StudioComponents.Field("enabled","Allow damage","true","Enabled","",StudioComponents.FieldKind.TOGGLE,0,true,false,value->{},selected::incrementAndGet,()->{});
        for(int width:List.of(100,300)){
            var layout=view.layoutContent(view.fields("row",List.of(field),null),0,0,width);var frame=new StudioFrame();layout.emit(frame,0);
            var checkbox=frame.nodes().stream().filter(node->node.id().equals("enabled")).findFirst().orElseThrow();
            assertEquals(Kind.CHECKBOX,frame.visual("enabled").kind());assertTrue(checkbox.selected());
            assertEquals(checkbox.bounds().height(),checkbox.bounds().width());assertNotNull(layout.bounds("enabled/label"));
            var tree=new ControlTree();tree.publish(frame.nodes());assertEquals("enabled",tree.hit(checkbox.bounds().x()+1,checkbox.bounds().y()+1).orElseThrow().id());
        }
        assertEquals(0,selected.get());
    }

    @Test void treeRowsRetainLegacyDisclosureSelectionAndHitGeometry() {
        var view=view();var metrics=StudioMetrics.compact();var activated=new AtomicInteger();var toggled=new AtomicInteger();
        Runnable activate=activated::incrementAndGet,toggle=toggled::incrementAndGet;
        for(int width:List.of(40,80,240))for(int depth:List.of(0,2,30))for(boolean branch:List.of(false,true))for(boolean selected:List.of(false,true)){
            var row=new StudioComponents.TreeRow("node","A long object name",Icon.FOLDER,depth,branch,selected,selected,activate,branch?toggle:null);
            var bounds=new Bounds(12,23,width,metrics.row());var legacy=new StudioFrame();var declared=new StudioFrame();
            new StudioComponents(legacy,metrics,value->value.length()*5).treeRow(row,bounds,2);
            var arranged=view.layoutContent(view.treeRow(row),bounds.x(),bounds.y(),width);arranged.emit(declared,2);
            assertEquals(metrics.row(),arranged.contentHeight());assertEquals(legacy.nodes(),declared.nodes());
            for(var node:declared.nodes())assertEquals(legacy.visual(node.id()),declared.visual(node.id()));
            assertSame(activate,declared.actions().get("node"));assertEquals(branch,declared.actions().containsKey("node/expand"));
            assertEquals(row.label(),declared.details().get("node"));
            if(branch){assertSame(toggle,declared.actions().get("node/expand"));assertEquals(row.label(),declared.details().get("node/expand"));}
            var tree=new ControlTree();tree.publish(declared.nodes());
            for(var node:declared.nodes())if(node.focusable())assertEquals(node.id(),tree.hit(node.bounds().x()+1,node.bounds().y()+1).orElseThrow().id());
        }
        assertEquals(0,activated.get());assertEquals(0,toggled.get());
    }

    @Test void translatedTreeRowsKeepOneFrozenGeometryForPaintClipAndActions() {
        var measured=new AtomicInteger();var activated=new AtomicInteger();var toggled=new AtomicInteger();
        var view=new StudioView(new StudioTextLayout((value,size)->{measured.incrementAndGet();return value.length()*5;}),StudioMetrics.compact());
        var row=new StudioComponents.TreeRow("object","Object",Icon.FOLDER,2,true,true,true,activated::incrementAndGet,toggled::incrementAndGet);
        var arranged=view.layoutContent(view.treeRow(row),0,0,200);int before=measured.get();
        var translated=arranged.translated(10,30);var frame=new StudioFrame();var clip=new Bounds(10,35,200,10);
        try(var ignored=frame.clipped(clip)){translated.emit(frame,0);}
        assertEquals(before,measured.get());var tree=new ControlTree();tree.publish(frame.nodes());
        for(String id:List.of("object","object/expand","object/selection")){
            var node=frame.nodes().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();
            assertEquals(translated.bounds(id),node.bounds());assertEquals(node.bounds().intersect(clip),node.visibleBounds());
        }
        var disclosure=translated.bounds("object/expand");var item=translated.bounds("object");
        assertEquals("object/expand",tree.hit(disclosure.x()+1,36).orElseThrow().id());
        assertEquals("object",tree.hit(item.x()+1,36).orElseThrow().id());assertTrue(tree.hit(item.x()+1,34).isEmpty());
        frame.actions().get("object/expand").run();assertEquals(1,toggled.get());assertEquals(0,activated.get());
        frame.actions().get("object").run();assertEquals(1,activated.get());assertEquals(1,toggled.get());
    }

    @Test void longDeepTreeRowsRetainCompactHeightAndReachTheEndOfAScrollingList() {
        var view=view();var metrics=StudioMetrics.compact();
        var rows=java.util.stream.IntStream.range(0,12).mapToObj(index->view.treeRow(new StudioComponents.TreeRow("object/"+index,"Long localized object name ".repeat(5),Icon.FOLDER,index*10,index%2==0,false,index==11,()->{},()->{}))).toList();
        var arranged=view.layoutContent(view.column("objects",Style.defaults(),rows),0,0,100);
        assertEquals(12*metrics.row(),arranged.contentHeight());
        var frame=new StudioFrame();var components=new StudioComponents(frame,metrics,String::length);
        var viewport=new Bounds(10,20,108,30);
        int offset=components.scrollContent("list",viewport,arranged.contentHeight(),0,Integer.MAX_VALUE,value->fail("Composition must not scroll"),body->arranged.translated(body.x(),body.y()).emit(frame,0));
        assertEquals(arranged.contentHeight()-viewport.height(),offset);
        var last=frame.nodes().stream().filter(node->node.id().equals("object/11")).findFirst().orElseThrow();
        assertEquals(last.bounds(),last.visibleBounds());assertTrue(last.bounds().width()>=50);
        var tree=new ControlTree();tree.publish(frame.nodes());assertEquals(last.id(),tree.hit(last.bounds().x()+1,last.bounds().y()+1).orElseThrow().id());
        assertTrue(frame.snapshot().focusTargets().containsKey(last.id()));
    }
}
