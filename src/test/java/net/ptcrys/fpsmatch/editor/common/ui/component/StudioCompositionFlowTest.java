package net.ptcrys.fpsmatch.editor.common.ui.component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Length;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;
import org.junit.jupiter.api.Test;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real declarative controls through layout, publication and input; no Minecraft host required. */
class StudioCompositionFlowTest {
    private static final StudioMetrics METRICS = StudioMetrics.compact();
    private static final String HINT = "World position in meters. Wide WWW glyphs, thin iii glyphs and 世界文字 must use the same measured lines as painting.";
    private static final String ERROR = "The incomplete exponent cannot be applied. Keep the draft intact, then enter a complete number or discard this edit.";

    @Test void multiFieldReflowPublishesTheSameSemanticControlsWithMeasuredHintsAndErrors() {
        var fixture = new Form();
        var wide = fixture.publish(480);
        var narrow = fixture.publish(150);
        assertEquals(ids(wide.frame), ids(narrow.frame));
        assertTrue(narrow.layout.contentHeight() > wide.layout.contentHeight());
        assertEquals(wide.layout.bounds("doc/x/field").y(), wide.layout.bounds("doc/y/field").y());
        assertTrue(narrow.layout.bounds("doc/y/field").y() > narrow.layout.bounds("doc/x/field").y());
        assertEquals(wide.layout.bounds("doc/limits").y(), wide.layout.bounds("doc/duration").y());
        assertTrue(narrow.layout.bounds("doc/duration").y() > narrow.layout.bounds("doc/limits").y());
        for (var publication : List.of(wide, narrow)) {
            var tree = new ControlTree();
            var bridge = new StudioComponents.InputBridge(tree);
            bridge.publish(publication.frame.snapshot());
            for (String id : List.of("doc/x", "doc/y", "doc/z")) {
                var control = node(publication.frame, id);
                assertEquals(publication.layout.contentBounds(id), control.bounds());
                assertEquals(id, tree.hit(centerX(control.bounds()), centerY(control.bounds())).orElseThrow().id());
                assertBefore(publication.layout.bounds(id + "/label"), control.bounds());
                assertBefore(control.bounds(), publication.layout.bounds(id + "/hint"));
                var hint = node(publication.frame, id + "/hint");
                var visual = publication.frame.visual(hint.id());
                assertFalse(visual.lines().isEmpty());
                assertEquals(visual.lines().size() * fixture.view.textLayout().lineHeight(visual.size()), hint.bounds().height());
                for (String line : visual.lines()) assertTrue(fixture.view.textLayout().width(line, visual.size()) <= hint.bounds().width());
            }
            assertBefore(publication.layout.bounds("doc/vector/apply"), publication.layout.bounds("doc/vector/error"));
            assertBefore(publication.layout.bounds("doc/vector/discard"), publication.layout.bounds("doc/vector/error"));
            assertBefore(publication.layout.bounds("doc/vector/error"), publication.layout.bounds("doc/limits"));
            assertNoInteractiveOverlap(publication.frame);
        }
        assertEquals(0, fixture.commits.get());
        assertEquals(0, fixture.actions.get());
        assertEquals("-1e", fixture.raw.get());
    }

    @Test void sameIdRawSelectionAndEditorIdentitySurviveNarrowAndWidePublicationUntilRealBlur() {
        var fixture = new Form(); var tree = new ControlTree(); var bridge = new StudioComponents.InputBridge(tree);
        bridge.publish(fixture.publish(480).frame.snapshot()); bridge.focus("doc/x");
        var editor = bridge.visibleText().get("doc/x");
        editor.selectAll(); assertTrue(bridge.insert("-12e"));
        editor.select(1, false); editor.select(3, true);
        assertEquals("-12e", fixture.raw.get());
        for (int width : List.of(150, 320, 150, 480)) {
            var next = fixture.publish(width); bridge.publish(next.frame.snapshot());
            assertSame(editor, bridge.visibleText().get("doc/x"));
            assertEquals("doc/x", tree.focused().orElseThrow().id());
            assertEquals("-12e", editor.text()); assertEquals(3, editor.caret()); assertEquals(1, editor.anchor());
            assertEquals("doc/vector/", next.frame.snapshot().groups().get("doc/x"));
            assertEquals(0, fixture.commits.get(), "layout publication is not an edit commit");
        }
        bridge.focus("doc/end");
        assertEquals(1, fixture.commits.get());
        bridge.publish(fixture.publish(150).frame.snapshot());
        assertEquals(1, fixture.commits.get(), "the completed blur must not repeat on a later reflow");
    }

    @Test void translatedScrollingBodyCannotMoveOrHitThroughTheFixedFooter() {
        var fixture = new Form(); var scroll = new AtomicInteger();
        var first = chrome(fixture.view, fixture.content(260), 260, 190, scroll);
        int maximum = first.content.contentHeight() - first.viewport.height();
        assertTrue(maximum > 0);
        assertEquals(0, node(first.frame, "doc/end").visibleBounds().height());
        assertTrue(first.frame.snapshot().focusTargets().containsKey("doc/end"));
        scroll.set(maximum);
        var last = chrome(fixture.view, fixture.content(260), 260, 190, scroll);
        assertEquals(first.shell.bounds("shell/footer"), last.shell.bounds("shell/footer"));
        assertEquals(first.shell.bounds("shell/save"), last.shell.bounds("shell/save"));
        assertEquals(node(first.frame, "doc/end").bounds().y() - maximum, node(last.frame, "doc/end").bounds().y());
        assertTrue(node(last.frame, "doc/end").visibleBounds().height() > 0);
        for (var publication : List.of(first, last)) {
            var tree = new ControlTree(); new StudioComponents.InputBridge(tree).publish(publication.frame.snapshot());
            var save = node(publication.frame, "shell/save");
            assertEquals("shell/save", tree.hit(centerX(save.bounds()), centerY(save.bounds())).orElseThrow().id());
            for (var bodyNode : publication.frame.nodes()) if (bodyNode.id().startsWith("doc/") && bodyNode.visibleBounds().height() > 0) {
                assertTrue(bodyNode.visibleBounds().y() >= publication.viewport.y());
                assertTrue(bottom(bodyNode.visibleBounds()) <= bottom(publication.viewport));
            }
            var target = publication.frame.snapshot().focusTargets().get("doc/end");
            assertEquals(publication.viewport.height(), target.region().viewport().height());
            assertEquals(maximum, target.region().maximum());
        }
        assertEquals(0, fixture.commits.get()); assertEquals(0, fixture.actions.get());
    }

    @Test void keyboardRevealUsesThePublishedBodyGeometryAndCommitsOnlyAfterFocusActuallyMoves() {
        var view = view(); var scroll = new AtomicInteger(); var commits = new AtomicInteger();
        var raw = new AtomicReference<>("draft");
        var content = view.column("doc/content", Style.defaults().padding(Insets.all(8)).gap(8), List.of(
                view.input(new StudioView.Input("doc/first", "First", raw.get(), true, false, false, "", "doc/first/", raw::set, commits::incrementAndGet)),
                view.paragraph("doc/explanation", HINT.repeat(8)),
                view.input(new StudioView.Input("doc/last", "Last", "saved", true, false, false, "", "doc/last/", value -> {}, () -> {}))));
        var first = chrome(view, content, 240, 180, scroll);
        var tree = new ControlTree(); var bridge = new StudioComponents.InputBridge(tree);
        bridge.publish(first.frame.snapshot()); bridge.focus("doc/first"); bridge.cycleFocus(false);
        assertTrue(scroll.get() > 0, "tabbing requests the measured hidden control's reveal offset");
        assertEquals("doc/first", tree.focused().orElseThrow().id());
        assertEquals(0, commits.get(), "requesting a new scroll snapshot does not yet move focus");
        var revealed = chrome(view, content, 240, 180, scroll); bridge.publish(revealed.frame.snapshot());
        assertEquals("doc/last", tree.focused().orElseThrow().id());
        assertEquals(1, commits.get());
        var last = node(revealed.frame, "doc/last");
        assertEquals(last.bounds(), last.visibleBounds());
        assertEquals("doc/last", tree.hit(centerX(last.bounds()), centerY(last.bounds())).orElseThrow().id());
        assertEquals(first.shell.bounds("shell/footer"), revealed.shell.bounds("shell/footer"));
    }

    @Test void checkboxPublicationKeepsAccessibleNameSelectionDisabledReasonAndDistinctHitGeometry() {
        var view = view(); var enabled = new AtomicBoolean(true); var changes = new AtomicInteger();
        for (int width : List.of(360, 110)) {
            var fields = List.of(
                    new StudioComponents.Field("doc/friendly", "Allow friendly fire", Boolean.toString(enabled.get()), "Enabled", "Team damage permission", StudioComponents.FieldKind.TOGGLE, 0, true, false, value -> {}, () -> { enabled.set(!enabled.get()); changes.incrementAndGet(); }, () -> {}),
                    new StudioComponents.Field("doc/locked", "Locked setting", "false", "Disabled", "Read-only in this project", StudioComponents.FieldKind.TOGGLE, 0, false, false, value -> {}, () -> fail("disabled setting activated"), () -> {}));
            var layout = view.layoutContent(view.fields("doc/options", fields, null), 10, 20, width);
            var frame = new StudioFrame(); layout.emit(frame, 0);
            var tree = new ControlTree(); var bridge = new StudioComponents.InputBridge(tree); bridge.publish(frame.snapshot());
            var checkbox = node(frame, "doc/friendly"); var locked = node(frame, "doc/locked");
            assertEquals(Role.BUTTON, checkbox.role()); assertEquals(Kind.CHECKBOX, frame.visual(checkbox.id()).kind());
            assertEquals("Allow friendly fire", checkbox.name()); assertEquals(enabled.get(), checkbox.selected());
            assertTrue(checkbox.enabled()); assertFalse(locked.enabled()); assertFalse(locked.selected());
            assertEquals("Read-only in this project", locked.reason());
            assertEquals(checkbox.bounds().width(), checkbox.bounds().height());
            assertEquals(checkbox.id(), tree.hit(centerX(checkbox.bounds()), centerY(checkbox.bounds())).orElseThrow().id());
            assertEquals(Role.LABEL, node(frame, "doc/friendly/label").role());
            assertNoInteractiveOverlap(frame);
            assertFalse(bridge.visibleText().containsKey(checkbox.id()));
            assertEquals(width == 360 ? 0 : 1, changes.get(), "reflow must not invoke checkbox actions");
            if (width == 360) { bridge.beforeAction(checkbox.id()); frame.snapshot().actions().get(checkbox.id()).run(); }
        }
        assertFalse(enabled.get()); assertEquals(1, changes.get());
    }

    private static final class Form {
        final StudioView view = view();
        final AtomicReference<String> raw = new AtomicReference<>("-1e");
        final AtomicInteger commits = new AtomicInteger(), actions = new AtomicInteger();
        StudioView.Element content(int width) {
            var x = new StudioComponents.Field("doc/x", "Position X", raw.get(), "", HINT, StudioComponents.FieldKind.TEXT, 0, true, true, raw::set, () -> {}, commits::incrementAndGet);
            var y = new StudioComponents.Field("doc/y", "Position Y", "64", "", HINT, StudioComponents.FieldKind.TEXT, 0, true, false, value -> {}, () -> {}, () -> {});
            var z = new StudioComponents.Field("doc/z", "Maximum distance", "120", "", HINT, StudioComponents.FieldKind.TEXT, 0, true, false, value -> {}, () -> {}, () -> {});
            var seconds = new StudioComponents.Field("doc/seconds", "Round duration", "45", "", HINT, StudioComponents.FieldKind.TEXT, 0, true, false, value -> {}, () -> {}, () -> {});
            var vector = view.fields("doc/vector", List.of(x, y), new StudioComponents.FieldActions("doc/vector", "Apply", "Discard", false, actions::incrementAndGet, actions::incrementAndGet, ERROR));
            var limits = view.fields("doc/limits", List.of(z), null);
            var duration = view.fields("doc/duration", List.of(seconds), null);
            var scalars = view.grid("doc/scalars", Style.defaults().columns(width >= 360 ? 2 : 1).gap(8), List.of(limits, duration));
            return view.column("doc/content", Style.defaults().padding(Insets.all(8)).gap(12), List.of(
                    view.button(StudioComponents.Action.plain("doc/group", "World rules", actions::incrementAndGet)).style(Style.defaults()),
                    view.column("doc/group/section", Style.defaults().gap(6), List.of(vector, scalars)),
                    view.button(StudioComponents.Action.plain("doc/end", "Continue", actions::incrementAndGet)).style(Style.defaults())));
        }
        Publication publish(int width) {
            var layout = view.layoutContent(content(width), 0, 0, width); var frame = new StudioFrame(); layout.emit(frame, 0);
            return new Publication(layout, frame);
        }
    }

    private record Publication(StudioView.Layout layout, StudioFrame frame) { }
    private record ScrollPublication(StudioView.Layout shell, StudioView.Layout content, StudioFrame frame, Bounds viewport) { }

    /** Same fixed chrome / independently measured translated body wiring as WorkbenchScreen. */
    private static ScrollPublication chrome(StudioView view, StudioView.Element content, int width, int height, AtomicInteger scroll) {
        var footer = view.surface("shell/footer", Kind.HEADER, FlowLayout.Kind.ROW, Style.defaults().height(Length.fixed(METRICS.row() + 8)).padding(Insets.all(4)), List.of(
                view.button(StudioComponents.Action.plain("shell/save", "Save", () -> fail("layout activated Save"))).style(Style.fill())));
        var shell = view.layout(view.column("shell", Style.fill(), List.of(
                view.surface("shell/header", Kind.HEADER, Style.defaults().height(Length.fixed(24)).padding(Insets.all(2)), List.of(view.text("shell/title", "Rules"))),
                view.placeholder("shell/body", Style.fill()), footer)), new Bounds(20, 30, width, height));
        var body = shell.bounds("shell/body");
        var arranged = view.layoutContent(content, 0, 0, Math.max(0, body.width() - 8));
        var frame = new StudioFrame(); shell.emit(frame, 0);
        var components = new StudioComponents(frame, METRICS, StudioCompositionFlowTest::nativeWidth);
        components.scrollContent("doc/rules", body, arranged.contentHeight(), 0, scroll.get(), scroll::set,
                area -> arranged.translated(area.x(), area.y()).emit(frame, 0));
        return new ScrollPublication(shell, arranged, frame, body);
    }

    private static StudioView view() { return new StudioView(StudioTextLayout.scaled(StudioCompositionFlowTest::nativeWidth, 9), METRICS); }
    private static int nativeWidth(String text) { return text.codePoints().map(cp -> Character.isWhitespace(cp) ? 3 : cp == 'i' ? 2 : cp == 'W' || cp > 127 ? 9 : 5).sum(); }
    private static Node node(StudioFrame frame, String id) { return frame.nodes().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(); }
    private static Set<String> ids(StudioFrame frame) { return frame.nodes().stream().map(Node::id).collect(Collectors.toSet()); }
    private static int bottom(Bounds bounds) { return bounds.y() + bounds.height(); }
    private static double centerX(Bounds bounds) { return bounds.x() + bounds.width() / 2.0; }
    private static double centerY(Bounds bounds) { return bounds.y() + bounds.height() / 2.0; }
    private static void assertBefore(Bounds first, Bounds next) { assertTrue(bottom(first) <= next.y(), () -> first + " overlaps later content " + next); }
    private static void assertNoInteractiveOverlap(StudioFrame frame) {
        var controls = frame.nodes().stream().filter(value -> value.role() != Role.LABEL && value.role() != Role.PANEL).toList();
        for (int a = 0; a < controls.size(); a++) for (int b = a + 1; b < controls.size(); b++) {
            var first = controls.get(a); var second = controls.get(b); var overlap = first.bounds().intersect(second.bounds());
            assertTrue(overlap.width() == 0 || overlap.height() == 0, () -> first.id() + " overlaps " + second.id());
        }
    }
}
