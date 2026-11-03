package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.*;
import net.ptcrys.fpsmatch.editor.common.ui.layout.RetainedLayout;
import org.junit.jupiter.api.Test;

class StudioRetainedLayoutTest {
    private static StudioView view(){return new StudioView(new StudioTextLayout((text,size)->text.length()*5),StudioMetrics.compact());}
    private static StudioView.Element form(StudioView view,Variant color,String raw,Runnable action){
        return view.column("form",Style.defaults().gap(2),List.of(
                view.text("title","Title",TextSize.BODY,color),
                view.input(new StudioView.Input("input","Input",raw,true,false,false,"",null,ignored->{},null)),
                view.button(new StudioComponents.Action("save","Save",Icon.NONE,color,true,false,"",action))));
    }

    @Test void shortLivedDeclarationsReuseGeometryButPublishCurrentPaintRawAndCallbacks(){
        var view=view();var oldActions=new AtomicInteger();var newActions=new AtomicInteger();var bounds=new Bounds(0,0,200,100);
        var first=view.layout(form(view,Variant.DEFAULT,"old",oldActions::incrementAndGet),bounds);var before=view.layoutStats();
        var second=view.layout(form(view,Variant.DANGER,"new",newActions::incrementAndGet),bounds);var after=view.layoutStats();
        assertSame(first.geometry(),second.geometry());assertEquals(before.leafMeasures(),after.leafMeasures());
        assertEquals(before.arrangedNodes(),after.arrangedNodes());assertEquals(before.mountedNodes(),after.mountedNodes());
        var frame=new StudioFrame();second.emit(frame,0);var snapshot=frame.snapshot();
        assertEquals("new",snapshot.nodes().stream().filter(node->node.id().equals("input")).findFirst().orElseThrow().value());
        assertEquals(Variant.DANGER,snapshot.visuals().get("title").variant());snapshot.actions().get("save").run();
        assertEquals(0,oldActions.get());assertEquals(1,newActions.get());
    }

    private static StudioView.Element twoRows(StudioView view,int firstHeight,AtomicInteger first,AtomicInteger second){
        return view.column("rows",Style.defaults(),List.of(
                view.placeholder("first",Style.defaults(),firstHeight,limits->{first.incrementAndGet();return new Size(10,firstHeight);}),
                view.placeholder("second",Style.defaults(),"constant",limits->{second.incrementAndGet();return new Size(10,5);})));
    }
    @Test void changedMeasurementOnlyRecomputesItsBranchAndMovesTheUnchangedSibling(){
        var view=view();var first=new AtomicInteger();var second=new AtomicInteger();
        var old=view.layoutContent(twoRows(view,20,first,second),0,0,100);int unchangedCalls=second.get(),changedCalls=first.get();
        long arranged=view.layoutStats().arrangedNodes();
        var updated=view.layoutContent(twoRows(view,40,first,second),0,0,100);
        assertEquals(25,old.contentHeight());assertEquals(45,updated.contentHeight());
        assertEquals(20,old.bounds("second").y());assertEquals(40,updated.bounds("second").y());
        assertTrue(first.get()>changedCalls);assertEquals(unchangedCalls,second.get());
        assertEquals(2,view.layoutStats().arrangedNodes()-arranged,"only the changed leaf and its dependent column need arrangement");
    }

    @Test void alternatingWidthsAndPositionChangesReuseTheCorrespondingMeasurements(){
        var view=view();String text="A long paragraph with several words that changes height when its width changes";
        var wide=view.layoutContent(view.paragraph("text",text),0,0,200);
        var narrow=view.layoutContent(view.paragraph("text",text),0,0,50);assertTrue(narrow.contentHeight()>wide.contentHeight());
        var before=view.layoutStats();
        assertSame(wide.geometry(),view.layoutContent(view.paragraph("text",text),0,0,200).geometry());
        var moved=view.layoutContent(view.paragraph("text",text),17,-30,50);
        assertEquals(new Bounds(17,-30,50,narrow.contentHeight()),moved.bounds("text"));
        assertEquals(before.leafMeasures(),view.layoutStats().leafMeasures());assertEquals(before.arrangedNodes(),view.layoutStats().arrangedNodes());
    }

    @Test void fontGenerationInvalidatesTextButKeepsIndependentFixedMeasurements(){
        var advance=new AtomicInteger(1);var fixedCalls=new AtomicInteger();
        var text=new StudioTextLayout((value,size)->value.length()*advance.get());var view=new StudioView(text,StudioMetrics.compact());
        java.util.function.Supplier<StudioView.Element> root=()->view.column("root",Style.defaults(),List.of(
                view.text("label","abcdefghij").style(Style.content()),
                view.placeholder("fixed",Style.defaults(),"fixed",limits->{fixedCalls.incrementAndGet();return new Size(10,10);})));
        var first=view.layoutContent(root.get(),0,0,100);int fixed=fixedCalls.get();assertEquals(10,first.bounds("label").width());
        advance.set(2);text.generation(1);var updated=view.layoutContent(root.get(),0,0,100);
        assertEquals(20,updated.bounds("label").width());assertEquals(fixed,fixedCalls.get());
    }

    @Test void childReorderKeepsMountsWhileChangingLayoutTypeCreatesANewMount(){
        var view=view();
        var first=view.placeholder("a",Style.defaults().height(Length.fixed(10)));var second=view.placeholder("b",Style.defaults().height(Length.fixed(20)));
        view.layoutContent(view.column("root",Style.defaults(),List.of(first,second)),0,0,100);long generation=view.layoutMountGeneration("root","a");
        var reordered=view.layoutContent(view.column("root",Style.defaults(),List.of(second,first)),0,0,100);
        assertEquals(20,reordered.bounds("a").y());assertEquals(generation,view.layoutMountGeneration("root","a"));
        view.layoutContent(view.column("root",Style.defaults(),List.of(view.text("a","Text"))),0,0,100);
        assertNotEquals(generation,view.layoutMountGeneration("root","a"));
    }

    @Test void scopeChangesDeletionAndAbortedCandidatesRespectMountLifetimes(){
        var view=view();var element=view.text("text","same");
        try(var publication=view.beginPublication("one")){view.layoutContent(element,0,0,100);publication.commit();}
        long original=view.layoutMountGeneration("text","text");
        try(var publication=view.beginPublication("two")){view.layoutContent(element,0,0,100);}
        assertEquals(original,view.layoutMountGeneration("text","text"));
        try(var publication=view.beginPublication("two")){view.layoutContent(element,0,0,100);publication.commit();}
        long next=view.layoutMountGeneration("text","text");assertNotEquals(original,next);
        try(var publication=view.beginPublication("two")){publication.commit();}
        assertEquals(0,view.layoutMountGeneration("text","text"));
        try(var publication=view.beginPublication("two")){view.layoutContent(element,0,0,100);publication.commit();}
        assertNotEquals(next,view.layoutMountGeneration("text","text"));
    }

    @Test void duplicateOrThrowingCandidateCannotPublishItsEarlierSuccessfulChanges(){
        var view=view();StudioView.Layout original;
        try(var publication=view.beginPublication("scope")){original=view.layoutContent(view.text("root","Original"),0,0,100);publication.commit();}
        long committed=view.layoutStats().publications();
        try(var publication=view.beginPublication("scope")){
            view.layoutContent(view.text("root","Changed"),0,0,100);
            var duplicate=view.column("duplicate",Style.defaults(),List.of(view.text("same","a"),view.text("same","b")));
            assertThrows(IllegalArgumentException.class,()->view.layoutContent(duplicate,0,0,100));
            assertThrows(IllegalStateException.class,publication::commit);
        }
        assertEquals(committed,view.layoutStats().publications());
        try(var publication=view.beginPublication("scope")){
            var restored=view.layoutContent(view.text("root","Original"),0,0,100);assertSame(original.geometry(),restored.geometry());
            assertThrows(IllegalStateException.class,()->view.layoutContent(view.placeholder("broken",Style.defaults(),"failure",limits->{throw new IllegalStateException("measurement failure");}),0,0,100));
        }
        assertEquals(committed,view.layoutStats().publications());
    }

    @Test void allEntryPointsShareConstraintResultsAndBudgetOnlyDropsAccelerationData(){
        var view=view();var leaf=view.text("leaf","abc").style(Style.content());var limits=new Constraints(100,100);
        assertEquals(new Size(15,14),view.measure(leaf,limits));long measures=view.layoutStats().leafMeasures();
        assertEquals(new Size(15,14),view.measure(view.text("leaf","abc").style(Style.content()),limits));assertEquals(measures,view.layoutStats().leafMeasures());
        var budget=new RetainedLayout.Budget(1,2,800);var bounded=new StudioView(new StudioTextLayout((text,size)->text.length()),StudioMetrics.compact(),budget);
        var tree=bounded.column("root",Style.defaults(),List.of(bounded.text("a","a"),bounded.text("b","b")));
        try(var publication=bounded.beginPublication("scope")){
            var result=bounded.layoutContent(tree,0,0,100);assertEquals(28,result.contentHeight());assertDoesNotThrow(publication::commit);
        }
        long mount=bounded.layoutMountGeneration("root","a");assertNotEquals(0,mount);
        try(var publication=bounded.beginPublication("scope")){
            bounded.layoutContent(tree,0,0,100);publication.commit();
        }
        assertEquals(mount,bounded.layoutMountGeneration("root","a"),"cache pressure must not remount a displayed control");
        assertTrue(bounded.layoutStats().retainedNodes()<=budget.maxNodes());assertTrue(bounded.layoutStats().cacheEntries()<=budget.maxEntries());assertTrue(bounded.layoutStats().retainedWeight()<=budget.maxWeight());
    }

    @Test void retiringTheLastRootReleasesItsMeasurementAndArrangementCaches(){
        var view=view();var first=new AtomicInteger();var second=new AtomicInteger();
        try(var publication=view.beginPublication("scope")){
            view.layoutContent(twoRows(view,20,first,second),0,0,100);publication.commit();
        }
        assertTrue(view.layoutStats().cacheEntries()>0);assertTrue(view.layoutStats().retainedWeight()>0);
        long generation=view.layoutMountGeneration("rows","first"),arranged=view.layoutStats().arrangedNodes();
        try(var publication=view.beginPublication("scope")){publication.commit();}
        assertEquals(0,view.layoutStats().retainedNodes());assertEquals(0,view.layoutStats().cacheEntries());
        assertEquals(0,view.layoutStats().retainedWeight());assertEquals(0,view.layoutMountGeneration("rows","first"));
        try(var publication=view.beginPublication("scope")){
            view.layoutContent(twoRows(view,20,first,second),0,0,100);publication.commit();
        }
        assertNotEquals(generation,view.layoutMountGeneration("rows","first"));
        assertEquals(3,view.layoutStats().arrangedNodes()-arranged);
    }

    @Test void screenCloseClearsRetainedStateWithoutReusingAMountGeneration(){
        var view=view();view.layoutContent(view.text("root","text"),0,0,100);
        long generation=view.layoutMountGeneration("root","root");
        try(var publication=view.beginPublication("scope")){
            assertThrows(IllegalStateException.class,view::clearRetainedLayout);
        }
        view.clearRetainedLayout();
        assertEquals(0,view.layoutStats().retainedNodes());assertEquals(0,view.layoutStats().cacheEntries());
        assertEquals(0,view.layoutStats().retainedWeight());assertEquals(0,view.layoutMountGeneration("root","root"));
        view.layoutContent(view.text("root","text"),0,0,100);
        assertTrue(view.layoutMountGeneration("root","root")>generation);
    }
}
