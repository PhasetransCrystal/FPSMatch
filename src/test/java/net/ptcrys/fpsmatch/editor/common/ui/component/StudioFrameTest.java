package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioFrameTest {
    @Test void nestedScrollClipsRestoreAfterFailureAndMatchInputGeometry() {
        var frame = new StudioFrame();
        var full = new Bounds(0, 0, 200, 200);
        try (var outer = frame.clipped(new Bounds(10, 20, 80, 60))) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = frame.clipped(new Bounds(30, 5, 100, 40))) {
                    emit(frame, "inner", full);
                    throw new IllegalStateException("cancel composition");
                }
            });
            emit(frame, "outer", full);
        }
        emit(frame, "unclipped", new Bounds(150, 150, 20, 20));
        var nodes = frame.nodes();
        assertEquals(new Bounds(30, 20, 60, 25), nodes.get(0).visibleBounds());
        assertEquals(new Bounds(10, 20, 80, 60), nodes.get(1).visibleBounds());
        assertEquals(new Bounds(150, 150, 20, 20), nodes.get(2).visibleBounds());
        assertNull(frame.clip());

        var tree = new ControlTree();
        tree.publish(List.of(nodes.get(0)));
        assertTrue(tree.hit(29, 21).isEmpty());
        assertEquals("inner", tree.hit(30, 20).orElseThrow().id());
        assertTrue(tree.hit(90, 30).isEmpty());
        assertTrue(tree.hit(40, 45).isEmpty());
    }

    @Test void publishedCopiesSurviveBuilderReuseWithTheirOriginalActions() {
        var frame = new StudioFrame();
        var calls = new AtomicInteger();
        emit(frame, "save", new Bounds(0, 0, 50, 20));
        frame.action("save", calls::incrementAndGet);
        var nodes = frame.nodes();
        var actions = frame.actions();
        frame.clear();
        emit(frame, "other", new Bounds(0, 0, 30, 20));
        frame.action("other", () -> calls.addAndGet(100));
        assertEquals("save", nodes.get(0).id());
        assertEquals(1, nodes.size());
        assertFalse(actions.containsKey("other"));
        actions.get("save").run();
        assertEquals(1, calls.get());
        assertThrows(UnsupportedOperationException.class, nodes::clear);
        assertThrows(UnsupportedOperationException.class, actions::clear);
    }

    private static void emit(StudioFrame frame, String id, Bounds bounds) {
        frame.emit(id, Role.BUTTON, id, "", bounds, true, false, false, "", 0, Visual.of(Kind.BUTTON));
    }
}
