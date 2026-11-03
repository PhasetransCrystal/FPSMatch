package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioContentSlotsTest {
    private static StudioView view() {
        return new StudioView(StudioTextLayout.scaled(value -> value.length()*5,9),StudioMetrics.compact());
    }
    @Test void scrollReusesDeclarationButPublishesCurrentGeometryAndSameAction() {
        var slots=new StudioContentSlots<StudioView.Element>(4);var view=view();
        var builds=new AtomicInteger();var actions=new AtomicInteger();
        for(int offset:List.of(0,20,40)){
            try(var publication=slots.begin("project");var layout=view.beginPublication("project")){
                var content=publication.content("inspector",1,()->{builds.incrementAndGet();return view.button(StudioComponents.Action.plain("action","Apply",actions::incrementAndGet));});
                var arranged=view.layoutContent(content,0,0,100).translated(10,50-offset);
                var frame=new StudioFrame();arranged.emit(frame,0);var runtime=new StudioRuntime();
                runtime.publish("project",frame.snapshot(),()->{layout.commit();publication.commit();});
                assertEquals("action",runtime.frame().geometry().hit(11,51-offset).orElseThrow().id());
                assertEquals(new Bounds(10,50-offset,100,20),arranged.bounds("action"));
                runtime.frame().snapshot().actions().get("action").run();
            }
        }
        assertEquals(1,builds.get());assertEquals(3,actions.get());assertEquals(2,slots.stats().reuses());
    }
    @Test void failedPublicationDoesNotInstallCandidate() {
        var slots=new StudioContentSlots<String>(2);
        try(var p=slots.begin("project")){p.content("field",1,()->"old raw");p.commit();}
        try(var p=slots.begin("project")){
            assertEquals("new raw",p.content("field",2,()->"new raw"));
            // A later frame validator rejects the candidate; closing rolls it back.
        }
        try(var p=slots.begin("project")){
            assertEquals("old raw",p.content("field",1,()->{throw new AssertionError("old declaration lost");}));p.commit();
        }
    }
    @Test void contentRevisionAndScopeReplaceValuesAndHiddenSlotsAreRetired() {
        var slots=new StudioContentSlots<String>(2);
        try(var p=slots.begin("one")){p.content("field",1,()->"raw1");p.content("hidden",1,()->"hidden");p.commit();}
        try(var p=slots.begin("one")){assertEquals("raw2",p.content("field",2,()->"raw2"));p.commit();}
        assertEquals(1,slots.stats().retained());
        try(var p=slots.begin("two")){assertEquals("other document",p.content("field",2,()->"other document"));p.commit();}
        slots.clear();assertEquals(0,slots.stats().retained());
    }
    @Test void limitBoundsRetentionAndRejectedBuilderCannotCommit() {
        var slots=new StudioContentSlots<String>(1);
        try(var p=slots.begin("project")){p.content("a",1,()->"a");p.content("b",1,()->"b");p.commit();}
        assertEquals(1,slots.stats().retained());
        try(var p=slots.begin("project")){
            assertThrows(IllegalStateException.class,()->p.content("b",2,()->{throw new IllegalStateException("failed");}));
            assertThrows(IllegalStateException.class,p::commit);
        }
        try(var p=slots.begin("project")){assertEquals("b",p.content("b",1,()->{throw new AssertionError("failed candidate installed");}));p.commit();}
    }
}
