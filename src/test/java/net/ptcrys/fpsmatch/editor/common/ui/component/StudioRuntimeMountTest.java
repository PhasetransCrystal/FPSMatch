package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioRuntimeMountTest {
    private static StudioFrame.Snapshot field(String id,int width) {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        ui.input(id,"Field","seed",new Bounds(0,0,width,20),0,true,false,ignored->{});return frame.snapshot();
    }

    @Test void continuousRebuildAndEditCaptureKeepTheSameMount() {
        var runtime=new StudioRuntime();var first=runtime.publish("document",field("field",100));var mount=first.mount("field");
        assertEquals("document",mount.scope());assertEquals(Role.TEXT_FIELD,mount.role());
        var second=runtime.publish("document",field("field",30));assertSame(mount,second.mount("field"));
        assertTrue(second.isCurrent(mount));assertTrue(first.isCurrent(mount));assertNotEquals(first.frameId(),second.frameId());
        runtime.input().focus("field");runtime.input().insert("!");assertSame(mount,runtime.frame().mount("field"));
        assertFalse(runtime.frame().isCurrent(null));assertNull(runtime.frame().mount("missing"));
        assertThrows(UnsupportedOperationException.class,()->runtime.frame().mounts().clear());
    }

    @Test void scopeSwitchAndReturningToThatScopeAlwaysMountANewInstance() {
        var runtime=new StudioRuntime();var old=runtime.publish("one",field("field",100)).mount("field");
        var other=runtime.publish("two",field("field",100)).mount("field");
        assertNotEquals(old,other);assertFalse(runtime.frame().isCurrent(old));assertTrue(other.generation()>old.generation());
        var returned=runtime.publish("one",field("field",100)).mount("field");
        assertNotEquals(old,returned);assertNotEquals(other,returned);assertTrue(returned.generation()>other.generation());
    }

    @Test void unmountInvalidatesTheTokenWithoutDiscardingTheEditSession() {
        var runtime=new StudioRuntime();var old=runtime.publish(field("field",100)).mount("field");runtime.input().focus("field");runtime.input().insert("!");
        var editor=runtime.input().visibleText().get("field");
        runtime.publish(new StudioFrame().snapshot());assertFalse(runtime.frame().isCurrent(old));assertNull(runtime.frame().mount("field"));
        runtime.publish(field("field",100));assertFalse(runtime.frame().isCurrent(old));assertSame(editor,runtime.input().visibleText().get("field"));
        assertEquals("seed!",editor.text());assertTrue(runtime.frame().mount("field").generation()>old.generation());
    }

    @Test void roleReplacementChangesMountAndRejectedPreparationDoesNotConsumeAnIdentity() {
        var runtime=new StudioRuntime();var old=runtime.publish(field("field",100));var mount=old.mount("field");
        var invalid=new StudioFrame.Snapshot(old.snapshot().nodes(),Map.of(),Map.of("absent",()->{}),Map.of(),Map.of(),Map.of(),Map.of(),List.of());
        assertThrows(IllegalArgumentException.class,()->runtime.publish(invalid));assertSame(old,runtime.frame());assertSame(mount,runtime.frame().mount("field"));
        var button=new Node("field",Role.BUTTON,"Button","",new Bounds(0,0,100,20),true,false,false,"",0);
        runtime.publish(new StudioFrame.Snapshot(List.of(button),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of()));
        assertFalse(runtime.frame().isCurrent(mount));assertEquals(mount.generation()+1,runtime.frame().mount("field").generation());
        assertEquals(Role.BUTTON,runtime.frame().mount("field").role());
    }

    @Test void blurThatUnmountsAndRemountsTheTargetCannotContinueAnOldFocusRequest() {
        var runtime=new StudioRuntime();var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var calls=new AtomicInteger();
        ui.input("source","Source","seed",new Bounds(0,0,100,20),0,true,false,ignored->{});
        ui.input("target","Target","seed",new Bounds(0,30,100,20),0,true,false,ignored->{});
        frame.blur("source","source/",()->{calls.incrementAndGet();runtime.publish(new StudioFrame().snapshot());runtime.publish(field("target",80));});
        var old=runtime.publish(frame.snapshot()).mount("target");runtime.input().focus("source");runtime.input().focus("target");
        assertEquals(1,calls.get());assertNull(runtime.frame().focusedId());assertFalse(runtime.frame().isCurrent(old));
        assertEquals(80,runtime.frame().geometry().node("target").orElseThrow().bounds().width());
    }

    @Test void abortNavigationDropsDeferredGroupCommitWithoutEditingTheDraft() {
        var runtime=new StudioRuntime();var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var calls=new AtomicInteger();
        ui.input("row/input","Field","seed",new Bounds(0,0,100,20),0,true,false,ignored->{});
        frame.blur("row/input","row/",calls::incrementAndGet);
        frame.emit("row/apply",Role.BUTTON,"Apply","",new Bounds(0,30,50,20),true,false,false,"",0,StudioComponent.Visual.of(StudioComponent.Kind.BUTTON));
        frame.emit("outside",Role.BUTTON,"Outside","",new Bounds(60,30,50,20),true,false,false,"",0,StudioComponent.Visual.of(StudioComponent.Kind.BUTTON));
        runtime.publish(frame.snapshot());runtime.input().focus("row/input");runtime.input().insert("!");
        var editor=runtime.input().visibleText().get("row/input");var before=editor.snapshot();runtime.input().focus("row/apply");
        assertEquals(0,calls.get());runtime.input().abortNavigation();assertEquals(before,editor.snapshot());
        runtime.input().focus("outside");assertEquals(0,calls.get());assertTrue(editor.undo());assertEquals("seed",editor.text());
    }

    @Test void abortNavigationDropsPendingRevealWithoutBlurOrAnotherScrollRequest() {
        var runtime=new StudioRuntime();var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        var blurs=new AtomicInteger();var requests=new AtomicInteger();
        ui.scrollContent("body",new Bounds(0,0,160,40),140,0,0,ignored->requests.incrementAndGet(),body->{
            ui.input("source","Source","seed",new Bounds(body.x(),body.y(),body.width(),20),0,true,false,ignored->{});
            frame.blur("source","source/",blurs::incrementAndGet);
            ui.button(StudioComponents.Action.plain("target","Target",()->{}),new Bounds(body.x(),body.y()+100,body.width(),20),0,StudioComponent.Kind.BUTTON);
        });
        runtime.publish(frame.snapshot());runtime.input().focus("source");runtime.input().insert("!");
        var editor=runtime.input().visibleText().get("source");var before=editor.snapshot();runtime.input().cycleFocus(false);
        assertEquals(1,requests.get());assertEquals(0,blurs.get());runtime.input().abortNavigation();
        assertEquals(before,editor.snapshot());assertEquals("source",runtime.frame().focusedId());
        runtime.publish(frame.snapshot());assertEquals(1,requests.get());assertEquals(0,blurs.get());
        assertEquals("source",runtime.frame().focusedId());assertEquals(before,editor.snapshot());
        assertTrue(editor.undo());assertEquals("seed",editor.text());
    }
}
