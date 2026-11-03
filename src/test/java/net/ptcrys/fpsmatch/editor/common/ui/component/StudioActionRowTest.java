package net.ptcrys.fpsmatch.editor.common.ui.component;

import java.util.List;
import org.junit.jupiter.api.Test;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioActionRowTest {
    private StudioView view() { return new StudioView(StudioTextLayout.scaled(s -> s.length() * 5, 9), StudioMetrics.compact()); }
    @Test void labelsAndNestedLayoutKeepOneHitTarget() {
        var view = view();
        var row = view.actionRow(StudioComponents.Action.plain("row", "Room", () -> {}), Style.fill(), List.of(
                view.column("names", Style.defaults(), List.of(view.text("name", "Room"), view.text("meta", "CS"))),
                view.text("count", "2 / 10")));
        var frame = new StudioFrame(); view.layout(row, new Bounds(10, 20, 240, 60)).emit(frame, 0);
        var tree = new ControlTree(); tree.publish(frame.nodes());
        assertEquals(1, frame.nodes().stream().filter(ControlTree.Node::focusable).count());
        for (int x : List.of(11, 90, 150, 249)) for (int y : List.of(21, 30, 79))
            assertEquals("row", tree.hit(x, y).orElseThrow().id());
    }
    @Test void nestedPanelsAndInteractiveDescendantsAreRejectedBeforeEmission() {
        var view = view();
        for (var child : List.of(view.surface("panel", Kind.INLINE_CARD, Style.defaults(), List.of(view.text("label", "Label"))),
                view.button(StudioComponents.Action.plain("child", "Child", () -> {})),
                view.input(new StudioView.Input("input", "Input", "", true, false, false, "", null, s -> {}, null)))) {
            var row = view.actionRow(StudioComponents.Action.plain("row", "Room", () -> {}), Style.defaults(),
                    List.of(view.column("nested", Style.defaults(), List.of(child))));
            assertThrows(IllegalArgumentException.class, () -> view.layoutContent(row, 0, 0, 240));
        }
    }
    @Test void disabledRowHasNoEnabledActionTarget() {
        var view = view();
        var action = new StudioComponents.Action("row", "Room", Icon.NONE, Variant.DEFAULT, false, false, "Waiting", () -> fail("must not invoke during layout"));
        var frame = new StudioFrame(); view.layoutContent(view.actionRow(action, Style.defaults(), List.of(view.text("label", "Room"))), 0, 0, 240).emit(frame, 0);
        assertFalse(frame.nodes().stream().filter(node -> node.id().equals("row")).findFirst().orElseThrow().enabled());
    }
}
