package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.text.java2d.StudioJava2dText;
import net.ptcrys.fpsmatch.editor.common.ui.component.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.*;
import net.ptcrys.fpsmatch.editor.common.ui.input.SingleLineText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/** Thin MC font/clipboard/input adapter. No AbstractWidget, native skin or native focus rebuilding. */
final class WorkbenchSurface {

    enum Style {
        GHOST,
        PRIMARY,
        SECONDARY,
        DANGER
    }

    private final StudioRuntime runtime = new StudioRuntime();
    final ControlTree tree = runtime.tree();
    private final StudioComponents.InputBridge inputBridge = runtime.input();
    WorkbenchTheme theme = WorkbenchTheme.dark();
    private final StudioFrame frame = new StudioFrame();
    final StudioComponents components;
    final StudioTextLayout textLayout;
    private final StudioJava2dText textBackend = new StudioJava2dText();
    final StudioView view;
    private final StudioScrollState scrolling = new StudioScrollState();
    private final StudioRenderer renderer;
    private final StudioPaintCache paintCache;
    private long resourceGeneration = -1;

    private record ResourceRetirement(long sequence, String reason, long generation, StudioRenderer.ResourceStats renderer, int shapeEntries, int rasterEntries, int textEntries, int paintEntries) {}

    private long retirementSequence;
    private ResourceRetirement lastRetirement;
    private final Map<String, Integer> offsets = new HashMap<>();
    private Map<String, Bounds> tooltipRows = Map.of();
    private final Map<String, Bounds> buildingTooltipRows = new HashMap<>();
    private Bounds tooltipRow;
    private final Runnable invalidate;
    private final Runnable requestFrame;
    private final float density;
    private StudioRuntime.Mount pressed, draggingText;
    private boolean keyboardFocus;
    private int labelId;
    boolean dimOverlay = true;
    private char pendingSurrogate;

    WorkbenchSurface(Runnable invalidate, Runnable requestFrame, float density) {
        this.invalidate = invalidate;
        this.requestFrame = requestFrame;
        this.density = density;
        textLayout = new StudioTextLayout(textBackend);
        view = new StudioView(textLayout, StudioMetrics.compact());
        components = new StudioComponents(frame, StudioMetrics.compact(), textLayout, invalidate);
        renderer = new StudioRenderer(textBackend);
        paintCache = new StudioPaintCache(new StudioPainter(textLayout));
        refreshResources();
    }

    void emit(StudioView.Layout layout, int layer) {
        layout.emit(frame, layer);
    }

    void begin() {
        frame.clear();
        buildingTooltipRows.clear();
        labelId = 0;
    }

    void theme(String name) {
        theme = "light".equals(name) ? WorkbenchTheme.light() : WorkbenchTheme.dark();
    }

    void onBlur(String id, String actionGroup, Runnable action) {
        frame.blur(id, actionGroup, action);
    }

    /** Resolve the old input before its destination action runs; discarding never applies it first. */
    void focus(String id) {
        inputBridge.focus(id);
        pendingSurrogate = 0;
    }

    void restoreFocus(String id) {
        inputBridge.restoreFocus(id);
        runtime.captureEdits();
    }

    void virtualRange(StudioFocusNavigation.VirtualRange range) {
        frame.virtualRange(range);
    }

    void virtualTarget(String nodeId, String rangeId, String rowKey, String slotKey) {
        frame.virtualTarget(nodeId, rangeId, rowKey, slotKey);
    }

    Bounds currentClip() {
        return frame.clip();
    }

    StudioFocusNavigation.FocusRef logicalFocus() {
        return inputBridge.currentLogicalFocus();
    }

    boolean requestFocus(StudioFocusNavigation.FocusRef target) {
        return inputBridge.requestFocus(target);
    }

    boolean currentReveal(StudioFocusNavigation.RevealRequest request) {
        return inputBridge.isCurrent(request);
    }

    void cycleFocus(boolean backwards) {
        inputBridge.cycleFocus(backwards);
        runtime.captureEdits();
    }

    void abortInput() {
        cancelPointer();
        pendingSurrogate = 0;
        inputBridge.abortNavigation();
    }

    void windowActive(boolean active) {
        if (inputBridge.windowActive(active)) {
            cancelPointer();
            pendingSurrogate = 0;
        }
    }

    void style(String id, Style style) {
        frame.visual(id, frame.visual(id).variant(switch (style) {
            case PRIMARY -> Variant.PRIMARY;
            case SECONDARY -> Variant.OUTLINED;
            case DANGER -> Variant.DANGER;
            default -> Variant.DEFAULT;
        }));
    }

    void kind(String id, Kind kind) {
        var v = frame.visual(id);
        frame.visual(id, new Visual(kind, v.variant(), v.icon(), v.size(), v.lines(), v.empty(), v.peer(), v.dimmed(), v.invalid(), v.color()));
    }

    void icon(String id, Icon icon) {
        frame.visual(id, frame.visual(id).icon(icon));
    }

    void detail(String id, String detail) {
        frame.detail(id, detail);
    }

    void tooltipRow(String id, Bounds row) {
        buildingTooltipRows.put(id, row);
    }

    void releaseText(String prefix) {
        runtime.releasePrefix(prefix);
        offsets.keySet().removeIf(id -> id.startsWith(prefix));
    }

    void clip(Bounds bounds) {
        frame.clip(bounds);
    }

    void releaseDocumentText() {
        releaseText("field/");
    }

    void publish(String sessionScope, Runnable afterCommit) {
        var next = frame.snapshot();
        // Validate the entire candidate before touching retained text or replacing live callbacks.
        // A failed builder leaves the previous drawing, input, raw history and actions operational.
        var rows = Map.copyOf(buildingTooltipRows);
        String previousScope = inputBridge.sessionScope();
        var published = runtime.publish(sessionScope, next, () -> {
            tooltipRows = rows;
            afterCommit.run();
            if (!previousScope.equals(sessionScope)) {
                cancelPointer();
                runtime.sessions().releaseScope(previousScope);
                offsets.clear();
            }
        });
        scrolling.reconcile(published.snapshot().scrolls(), tree);
        paintCache.retain(published.snapshot().visuals().keySet());
        if (pressed != null && !published.isCurrent(pressed) || draggingText != null && !published.isCurrent(draggingText)) cancelPointer();
    }

    void cancelPointer() {
        pressed = null;
        draggingText = null;
        scrolling.cancel();
    }

    void cancelNavigation() {
        inputBridge.cancelNavigation();
    }

    void reflow() {
        inputBridge.reflow();
    }

    void keyboardNavigation() {
        keyboardFocus = true;
    }

    void panel(String id, int x, int y, int w, int h, int layer) {
        add(id, Role.PANEL, "", "", x, y, w, h, true, false, false, "", layer);
    }

    void rectangle(String id, int x, int y, int w, int h, int layer, int color) {
        frame.emit(id, Role.PANEL, "", "", new Bounds(x, y, Math.max(0, w), Math.max(0, h)), true, false, false, "", layer, Visual.of(Kind.SOLID).color(color));
    }

    void surface(String id, int x, int y, int w, int h, int layer, Kind kind) {
        components.surface(id, new Bounds(x, y, Math.max(0, w), Math.max(0, h)), layer, kind);
    }

    void label(Component text, int x, int y, int w, int layer) {
        label("label/" + labelId++, text, x, y, w, layer);
    }

    void label(String id, Component text, int x, int y, int w, int layer) {
        add(id, Role.LABEL, text.getString(), "", x, y, w, 12, true, false, false, "", layer);
    }

    void heading(Component text, int x, int y, int w, int layer) {
        components.text("label/" + labelId++, text.getString(), new Bounds(x, y, Math.max(0, w), 12), layer, TextSize.HEADING, Variant.DEFAULT);
    }

    void coloredLabel(Component text, int x, int y, int w, int layer, int color) {
        String id = "label/" + labelId++;
        add(id, Role.LABEL, text.getString(), "", x, y, w, 12, true, false, false, "", layer);
        frame.visual(id, frame.visual(id).color(color));
    }

    void error(Component text, int x, int y, int w, int layer) {
        String id = "label/" + labelId++;
        add(id, Role.LABEL, text.getString(), "", x, y, w, 12, true, false, false, "", layer);
        style(id, Style.DANGER);
    }

    void button(String id, Component text, int x, int y, int w, int h, int layer, boolean enabled, boolean selected, Role role, String reason, Runnable action) {
        add(id, role, text.getString(), "", x, y, w, h, enabled, selected, false, reason, layer);
        frame.action(id, action);
    }

    void card(String id, Component title, int x, int y, int w, int h, int layer, boolean enabled, boolean selected, String reason, Runnable action) {
        button(id, title, x, y, w, h, layer, enabled, selected, Role.BUTTON, reason, action);
        kind(id, Kind.SLOT);
    }

    void edit(String id, Component label, String initial, int x, int y, int w, int layer, boolean editable, Consumer<String> change) {
        components.input(id, label.getString(), initial, new Bounds(x, y, Math.max(0, w), theme.row()), layer, editable, false, change);
    }

    private void add(String id, Role role, String name, String value, int x, int y, int w, int h, boolean enabled, boolean selected, boolean readonly, String reason, int layer) {
        var bounds = new Bounds(x, y, Math.max(0, w), Math.max(0, h));
        Kind kind = switch (role) {
            case PANEL -> Kind.PANEL;
            case LABEL -> Kind.LABEL;
            case TEXT_FIELD -> Kind.INPUT;
            case TREE_ITEM -> Kind.TREE_ROW;
            case TAB -> Kind.TAB;
            default -> Kind.BUTTON;
        };
        frame.emit(id, role, name, value, bounds, enabled, selected, readonly, reason, layer, Visual.of(kind).variant(role == Role.LABEL ? Variant.MUTED : Variant.DEFAULT));
    }

    boolean refreshResources() {
        long next = StudioClientResources.generation();
        if (resourceGeneration == next) return false;
        renderer.close();
        textBackend.clear();
        textLayout.generation(next);
        paintCache.clear();
        captureRetirement("reload", next);
        resourceGeneration = next;
        return true;
    }

    void releasePaintResources() {
        paintCache.clear();
        textLayout.clearCache();
        textBackend.clear();
        view.clearRetainedLayout();
        components.view().clearRetainedLayout();
        renderer.close();
        captureRetirement("removed", resourceGeneration);
    }

    private void captureRetirement(String reason, long generation) {
        var text = textBackend.stats();
        lastRetirement = new ResourceRetirement(++retirementSequence, reason, generation, renderer.resourceStats(), text.shapeEntries(), text.rasterEntries(), textLayout.cacheStats().entries(), paintCache.stats().entries());
    }

    private StudioTextLine.Viewport textViewport(Node node, SingleLineText.Snapshot state) {
        var line = textLayout.line(state.text(), TextSize.BODY);
        var view = line.revealAt(offsets.getOrDefault(node.id(), 0), state.caret(), Math.max(0, node.bounds().width() - 14));
        offsets.put(node.id(), view.originX());
        return view;
    }

    private int caretAt(Node node, double x) {
        return textViewport(node, inputBridge.visibleText().get(node.id()).snapshot()).hitTest(x - node.bounds().x() - 6);
    }

    boolean click(double x, double y, int button, boolean extend) {
        keyboardFocus = false;
        if (button == 0 && scrolling.press(runtime.frame().snapshot().scrolls(), x, y, tree.topLayer(), tree)) {
            focus(null);
            pressed = null;
            draggingText = null;
            requestFrame.run();
            return true;
        }
        var hit = tree.hit(x, y);
        if (hit.isEmpty()) {
            focus(null);
            return tree.topLayer() > 0;
        }
        var mount = runtime.frame().mount(hit.get().id());
        focus(hit.get().id());
        if (!runtime.frame().isCurrent(mount)) return true;
        var current = runtime.frame().geometry().hit(x, y);
        if (current.isEmpty() || !current.get().id().equals(mount.id())) return true;
        Node node = current.get();
        pendingSurrogate = 0;
        if (button != 0) return true;
        if (node.role() == Role.TEXT_FIELD) {
            var editor = inputBridge.visibleText().get(node.id());
            if (editor != null) {
                editor.select(caretAt(node, x), extend);
                draggingText = mount;
                runtime.captureEdits();
            }
        } else if (node.enabled()) pressed = mount;
        return true;
    }

    boolean release(double x, double y, int button) {
        if (button == 0 && scrolling.release(y)) {
            requestFrame.run();
            return true;
        }
        var captured = pressed;
        cancelPointer();
        if (button == 0 && runtime.frame().isCurrent(captured) && tree.hit(x, y).filter(n -> n.id().equals(captured.id()) && n.enabled()).isPresent()) invokeAction(captured.id());
        return captured != null || tree.topLayer() > 0;
    }

    private void invokeAction(String id) {
        if (!inputBridge.actionReady(id)) return;
        var mount = runtime.frame().mount(id);
        if (mount == null || !runtime.frame().snapshot().actions().containsKey(id)) return;
        inputBridge.beforeAction(id);
        if (!runtime.frame().isCurrent(mount) || tree.node(id).filter(Node::enabled).isEmpty()) return;
        Runnable action = runtime.frame().snapshot().actions().get(id);
        if (action != null) action.run();
    }

    boolean scroll(double x, double y, double delta) {
        if (delta != 0) inputBridge.cancelNavigation();
        if (scrolling.wheel(runtime.frame().snapshot().scrolls(), x, y, delta, tree.topLayer(), tree)) {
            requestFrame.run();
            return true;
        }
        return false;
    }

    boolean drag(double x, double y) {
        if (scrolling.drag(y)) {
            requestFrame.run();
            return true;
        }
        if (!runtime.frame().isCurrent(draggingText)) {
            draggingText = null;
            return false;
        }
        tree.node(draggingText.id()).ifPresent(n -> {
            var editor = inputBridge.visibleText().get(n.id());
            if (editor != null) editor.select(caretAt(n, x), true);
        });
        runtime.captureEdits();
        return true;
    }

    boolean character(char character) {
        var node = tree.focused().orElse(null);
        if (node == null || node.role() != Role.TEXT_FIELD || node.readOnly()) return false;
        if (Character.isHighSurrogate(character)) {
            pendingSurrogate = character;
            return true;
        }
        String input = Character.isLowSurrogate(character) && pendingSurrogate != 0 ? new String(new char[] { pendingSurrogate, character }) : String.valueOf(character);
        pendingSurrogate = 0;
        boolean handled = inputBridge.insert(input);
        if (handled) invalidate.run();
        return handled;
    }

    boolean key(int key, boolean ctrl, boolean shift) {
        keyboardFocus = true;
        if (key == GLFW.GLFW_KEY_TAB) {
            inputBridge.cycleFocus(shift);
            invalidate.run();
            return true;
        }
        Node node = tree.focused().orElse(null);
        if (node == null) return false;
        if (node.role() != Role.TEXT_FIELD) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
                if (node.enabled()) invokeAction(node.id());
                return true;
            }
            return false;
        }
        SingleLineText state = inputBridge.visibleText().get(node.id());
        boolean changed = false;
        if (state == null) {
            var diagnostic = runtime.frame().diagnostics().get(node.id());
            if (ctrl && key == GLFW.GLFW_KEY_C && diagnostic != null) Minecraft.getInstance().keyboardHandler.setClipboard(diagnostic.externalValue());
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_A) state.selectAll();
        else if (ctrl && key == GLFW.GLFW_KEY_C) Minecraft.getInstance().keyboardHandler.setClipboard(state.selected());
        else if (ctrl && key == GLFW.GLFW_KEY_X) {
            Minecraft.getInstance().keyboardHandler.setClipboard(state.selected());
            if (!node.readOnly() && state.start() != state.end()) changed = state.backspace();
        } else if (ctrl && key == GLFW.GLFW_KEY_V) {
            if (!node.readOnly()) changed = state.insert(Minecraft.getInstance().keyboardHandler.getClipboard());
        } else if (ctrl && key == GLFW.GLFW_KEY_Z) {
            if (!node.readOnly()) changed = shift ? state.redo() : state.undo();
        } else if (ctrl && key == GLFW.GLFW_KEY_Y) {
            if (!node.readOnly()) changed = state.redo();
        } else if (key == GLFW.GLFW_KEY_LEFT) state.move(-1, shift, ctrl);
        else if (key == GLFW.GLFW_KEY_RIGHT) state.move(1, shift, ctrl);
        else if (key == GLFW.GLFW_KEY_HOME) state.home(shift);
        else if (key == GLFW.GLFW_KEY_END) state.end(shift);
        else if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!node.readOnly()) changed = state.backspace();
        } else if (key == GLFW.GLFW_KEY_DELETE) {
            if (!node.readOnly()) changed = state.deleteForward();
        }
        // Focused text consumes all remaining shortcuts, including document undo/save/delete.
        if (changed) changed(node.id());
        runtime.captureEdits();
        return true;
    }

    private void changed(String id) {
        inputBridge.changed(id);
        invalidate.run();
    }

    Component render(GuiGraphics g, int mx, int my, int width, int height) {
        tooltipRow = null;
        String tooltip = "";
        int layer = -1;
        var published = runtime.frame();
        var geometryFrame = published.geometry();
        String hit = geometryFrame.hit(mx, my).map(Node::id).orElse(null), focused = published.focusedId();
        int topLayer = geometryFrame.topLayer();
        boolean caretVisible = (System.currentTimeMillis() / 500) % 2 == 0;
        renderer.begin(g, density);
        try {
            for (var geometry : geometryFrame.visibleGeometry()) {
                Node node = geometry.node();
                var visible = geometry.visibleBounds();
                if (node.layer() > layer && node.layer() > 0 && dimOverlay) {
                    var dim = new StudioCanvas(new Bounds(0, 0, width, height));
                    dim.fill(0, 0, width, height, theme.isLight() ? 0x40253246 : 0x66000000);
                    renderer.submit(dim.finish(false));
                }
                layer = node.layer();
                boolean focus = node.id().equals(focused);
                boolean hover = node.layer() == topLayer && (geometry.focusable() ? node.id().equals(hit) : visible.contains(mx, my));
                StudioPainter.Input input = null;
                if (node.role() == Role.TEXT_FIELD) {
                    var state = published.edits().get(node.id());
                    if (state != null) {
                        var view = textViewport(node, state);
                        var line = view.line();
                        input = new StudioPainter.Input(state.text(), view.originX(), line.selections(state.start(), state.end()), line.x(state.caret()), line.width(), focus && caretVisible);
                    } else {
                        var diagnostic = published.diagnostics().get(node.id());
                        if (diagnostic != null) {
                            String message = diagnosticText(diagnostic);
                            input = new StudioPainter.Input(message, 0, 0, 0, 0, textLayout.width(message, TextSize.BODY), false);
                        }
                    }
                }
                var picture = paintCache.picture(node, published.snapshot().visuals().getOrDefault(node.id(), Visual.of(Kind.LABEL)), theme, hover, focus && (keyboardFocus || node.role() == Role.TEXT_FIELD), pressed != null && node.id().equals(pressed.id()), input, resourceGeneration);
                renderer.submit(picture);
                if (hover) {
                    if (geometry.focusable() || node.pointerBarrier()) {
                        tooltip = "";
                        tooltipRow = null;
                    }
                    var diagnostic = published.diagnostics().get(node.id());
                    String content = diagnostic == null ? StudioTooltip.content(node, published.snapshot().details().get(node.id()), picture.truncated(), focus) : diagnosticText(diagnostic);
                    if (!content.isEmpty()) {
                        tooltip = content;
                        tooltipRow = tooltipRows.getOrDefault(node.id(), node.bounds());
                    }
                }
            }
        } finally {
            renderer.end();
        }
        return tooltip.isEmpty() ? null : Component.literal(tooltip);
    }

    /** Screen-space tool instructions and mouse cursor only; spatial meshes belong to the world pass. */
    void renderPointPlacement(GuiGraphics g, Bounds viewport, Component hint, int x, int y, boolean cursor) {
        if (viewport.width() < 24 || viewport.height() < 24) return;
        var canvas = new StudioCanvas(viewport);
        if (cursor) {
            canvas.fill(x - 7, y - 2, x + 8, y + 3, theme.raised());
            canvas.fill(x - 2, y - 7, x + 3, y + 8, theme.raised());
            canvas.fill(x - 6, y, x + 7, y + 1, theme.accent());
            canvas.fill(x, y - 6, x + 1, y + 7, theme.accent());
        }
        var lines = textLayout.wrap(hint.getString(), Math.max(1, viewport.width() - 24), TextSize.BODY);
        int top = Math.max(viewport.y(), viewport.y() + viewport.height() - lines.height() - 14);
        canvas.fill(viewport.x() + 4, top, viewport.x() + viewport.width() - 4, viewport.y() + viewport.height() - 4, theme.raised());
        int row = top + 5;
        for (String line : lines.lines()) {
            canvas.text(line, viewport.x() + 12, row, theme.text(), TextSize.BODY);
            row += lines.lineHeight();
        }
        renderer.begin(g, density);
        try {
            renderer.submit(canvas.finish(false));
        } finally {
            renderer.end();
        }
    }

    private static String diagnosticText(net.ptcrys.fpsmatch.editor.common.ui.input.EditSessionStore.Diagnostic diagnostic) {
        return Component.translatable("fpsmatch.editor.ui." + diagnostic.code().name().toLowerCase(Locale.ROOT), diagnostic.maximumLength()).getString();
    }

    Bounds recoveryAction(int width, int height) {
        return new Bounds(Math.max(8, (width - 180) / 2), Math.max(8, height / 2 + 38), Math.min(180, Math.max(1, width - 16)), 24);
    }

    void renderRecovery(GuiGraphics g, int width, int height, int mouseX, int mouseY) {
        var canvas = new StudioCanvas(new Bounds(0, 0, width, height));
        canvas.fill(0, 0, width, height, theme.background());
        int available = Math.max(1, Math.min(420, width - 24)), left = Math.max(0, (width - available) / 2), y = Math.max(8, height / 2 - 60);
        for (String key : List.of("runtime_failed", "runtime_failed_hint")) {
            var lines = textLayout.wrap(Component.translatable("fpsmatch.editor.ui." + key).getString(), available, TextSize.BODY);
            for (String line : lines.lines()) {
                canvas.text(line, left, y, theme.text(), TextSize.BODY);
                y += lines.lineHeight();
            }
            y += 8;
        }
        var b = recoveryAction(width, height);
        canvas.fill(b.x(), b.y(), b.x() + b.width(), b.y() + b.height(), b.contains(mouseX, mouseY) ? theme.hover() : theme.accent());
        canvas.text(Component.translatable("fpsmatch.editor.ui.runtime_retry").getString(), b.x() + 8, b.y() + 7, theme.onAccent(), TextSize.BODY, b);
        renderer.begin(g, density);
        try {
            renderer.submit(canvas.finish(false));
        } finally {
            renderer.end();
        }
    }

    void renderTooltip(GuiGraphics g, Component tooltip, int mx, int my) {
        int width = (int) (g.guiWidth() / density), height = (int) (g.guiHeight() / density);
        Bounds row = tooltipRow == null ? new Bounds((int) (mx / density), (int) (my / density), 0, 0) : tooltipRow;
        var lines = textLayout.wrap(tooltip.getString(), Math.max(1, Math.min(300, width - 24)), TextSize.BODY);
        int boxWidth = Math.min(width - 8, lines.width() + 16), boxHeight = lines.height() + 12;
        int left = Math.max(4, Math.min((int) (mx / density) + 10, width - boxWidth - 4));
        int above = row.y() - boxHeight - 5, below = row.y() + row.height() + 5;
        int top = above >= 4 ? above : Math.max(4, Math.min(below, height - boxHeight - 4));
        var canvas = new StudioCanvas(new Bounds(0, 0, width, height));
        canvas.fill(left, top, left + boxWidth, top + boxHeight, theme.border());
        canvas.fill(left + 1, top + 1, left + boxWidth - 1, top + boxHeight - 1, theme.raised());
        int y = top + 6;
        for (String line : lines.lines()) {
            canvas.text(line, left + 8, y, theme.text(), TextSize.BODY);
            y += lines.lineHeight();
        }
        g.pose().pushPose();
        try {
            g.pose().scale(density, density, 1);
            renderer.begin(g, density);
            try {
                renderer.submit(canvas.finish(false));
            } finally {
                renderer.end();
            }
        } finally {
            g.pose().popPose();
        }
    }
}
