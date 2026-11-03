package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.component.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapSelectionRowTest {
    @Test void longNamesWrapAndTheOpenCueKeepsOneRowActionAtEveryWidth() {
        for (int width : List.of(224, 260, 420, 560, 679, 680, 900)) {
            var view = new StudioView(StudioTextLayout.scaled(value -> value.length() * 5, 9), StudioMetrics.compact());
            var invoked = new AtomicInteger();
            var action = StudioComponents.Action.plain("room", "View room", invoked::incrementAndGet);
            var text = new MapSelectionRow.Text("长地图名称 Long translated map title ".repeat(8),
                    "CS / internal_identifier_without_spaces_".repeat(3), "Players: 16 / 32", "In progress; joining allowed", "View room →");
            var layout = view.layoutContent(MapSelectionRow.compose(view, action, text, "minecraft:textures/block/stone.png", width), 0, 0, width);
            var frame = new StudioFrame();
            layout.emit(frame, 0);
            assertEquals(0, layout.geometry().root().overflowWidth(), "width=" + width);
            assertEquals(Set.of("room"), frame.actions().keySet());
            assertEquals(1, frame.nodes().stream().filter(node -> node.focusable()).count());
            assertTrue(frame.nodes().stream().allMatch(node -> node.bounds().x() >= 0
                    && node.bounds().x() + node.bounds().width() <= width), "width=" + width);
            assertTrue(frame.visual("room/title").lines().size() > 1);
            assertTrue(layout.bounds("room/open").height() > 0);
            assertEquals(width >= 680 ? 96 : 64, layout.bounds("room/thumbnail").width());
            assertEquals(width >= 680 ? 58 : 40, layout.bounds("room/thumbnail").height());
            if (width < 680) {
                int headingBottom = layout.bounds("room/heading").y() + layout.bounds("room/heading").height();
                assertTrue(layout.bounds("room/players").y() >= headingBottom);
                assertTrue(layout.bounds("room/open").y() >= headingBottom);
            }
            assertEquals(0, invoked.get());
            frame.actions().get("room").run();
            assertEquals(1, invoked.get());
        }
    }

    @Test void imageVariantsKeepTheSameGeometryAndOneAction() {
        var text = new MapSelectionRow.Text("地图 Map title", "CS / internal_map",
                "Players: 16 / 32", "Waiting for players", "View room →");
        for (int width : List.of(224, 679, 680, 900)) {
            var view = new StudioView(StudioTextLayout.scaled(value -> value.length() * 5, 9), StudioMetrics.compact());
            var action = StudioComponents.Action.plain("room", "View room", () -> {});
            Map<String, Bounds> baseline = null;
            for (String texture : List.of("minecraft:textures/block/stone.png", "",
                    "not a resource id", "fpsmatch:textures/nonexistent-map.png")) {
                var layout = view.layoutContent(MapSelectionRow.compose(view, action, text, texture, width), 0, 0, width);
                var frame = new StudioFrame();
                layout.emit(frame, 0);
                var geometry = new LinkedHashMap<String, Bounds>();
                frame.nodes().forEach(node -> geometry.put(node.id(), node.bounds()));
                if (baseline == null) baseline = geometry;
                else assertEquals(baseline, geometry, "width=" + width + ", texture=" + texture);
                assertEquals(Set.of("room"), frame.actions().keySet());
                assertEquals(1, frame.nodes().stream().filter(node -> node.focusable()).count());
                var thumbnail = frame.nodes().stream().filter(node -> node.id().equals("room/thumbnail")).findFirst().orElseThrow();
                assertEquals(Role.LABEL, thumbnail.role());
                assertFalse(thumbnail.focusable());
            }
        }
    }
}
