package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.ui.component.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mojang.logging.LogUtils;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Reusable Minecraft host for Studio layout, controls and rendering. Business screens compose
 * semantic content; this host owns publication, coordinates, focus and graphics resources.
 * Subclasses must not add native widgets or publish the component frame themselves.
 */
public abstract class StudioScreen extends Screen {

    public static final float DENSITY = .75f;
    protected final Screen parent;
    private final String scope;
    private final StudioPublicationCycle publication = new StudioPublicationCycle();
    private WorkbenchSurface surface;
    private boolean retired, deferredRetirement;
    // Ordinary Studio pages share the current client-session selection, including
    // cached parents and freshly opened invitation pages. This is not project state.
    private static String theme = "dark";

    private record ModalReturn(int layer, String control) {}

    private final Deque<ModalReturn> modalReturns = new ArrayDeque<>();
    private ModalReturn pendingModalReturn;

    protected StudioScreen(Component title, Screen parent, String scope) {
        super(title);
        this.parent = parent;
        this.scope = Objects.requireNonNull(scope);
        if (scope.isBlank()) throw new IllegalArgumentException("empty Studio scope");
    }

    protected abstract void compose(Composition content);

    protected void onScreenTick() {}

    protected void onScreenRemoved() {}

    protected final void requestLayout() {
        publication.invalidate();
    }

    protected final String themeName() {
        return theme;
    }

    protected final void setTheme(String name) {
        theme = "light".equals(name) ? "light" : "dark";
        if (surface != null) surface.theme(theme);
        requestLayout();
    }

    public final Screen parentScreen() {
        return parent;
    }

    public final Bounds logicalBounds() {
        return new Bounds(0, 0, (int) Math.ceil(width / DENSITY), (int) Math.ceil(height / DENSITY));
    }

    @Override
    protected final void init() {
        retired = false;
        if (!publication.building()) deferredRetirement = false;
        if (surface == null) surface = new WorkbenchSurface(this::requestLayout, this::requestLayout, DENSITY);
        surface.cancelPointer();
        surface.reflow();
        surface.theme(theme);
        publication.open();
        refresh(false);
    }

    /** Every input route passes here, even if no render occurred since a network update. */
    private boolean refresh(boolean input) {
        if (surface == null || retired) return false;
        if (publication.building() || publication.failed()) {
            surface.abortInput();
            return false;
        }
        long lifecycle = publication.epoch();
        try {
            boolean active = minecraft != null && minecraft.isWindowActive();
            surface.windowActive(active);
            if (!publication.current(lifecycle) || retired) return false;
            if (surface.refreshResources()) {
                surface.reflow();
                requestLayout();
            }
            boolean current = publication.refresh(this::publish);
            return current && publication.current(lifecycle) && (!input || active);
        } catch (RuntimeException failure) {
            surface.abortInput();
            if (publication.current(lifecycle)) publication.fail();
            LogUtils.getLogger().error("Studio screen publication failed: {}", scope, failure);
            return false;
        } finally {
            if (deferredRetirement && !publication.building()) {
                releaseResources();
                if (!retired) {
                    surface.reflow();
                    requestLayout();
                }
            }
        }
    }

    private void publish() {
        long lifecycle = publication.epoch();
        int previousLayer = surface.tree.topLayer();
        String previousFocus = surface.tree.focusedId();
        try (var view = surface.view.beginPublication(scope);
                var components = surface.components.view().beginPublication(scope)) {
            surface.begin();
            var content = new Composition(surface, logicalBounds());
            try {
                compose(content);
            } finally {
                content.valid = false;
            }
            if (!publication.current(lifecycle)) return;
            surface.publish(scope, () -> {
                view.commit();
                components.commit();
                if (!publication.current(lifecycle) || retired) return;
                int nextLayer = surface.tree.topLayer();
                if (nextLayer > previousLayer) {
                    pendingModalReturn = null;
                    modalReturns.addLast(new ModalReturn(previousLayer, previousFocus));
                } else if (nextLayer < previousLayer) {
                    pendingModalReturn = null;
                    ModalReturn destination = null;
                    while (!modalReturns.isEmpty() && modalReturns.getLast().layer() >= nextLayer)
                        destination = modalReturns.removeLast();
                    if (destination != null && destination.layer() == nextLayer)
                        pendingModalReturn = destination;
                }
            });
            // Record transitions at commit, before fallible blur callbacks. Apply focus
            // after navigation settles; a failed callback leaves this pending for retry.
            if (publication.current(lifecycle) && !retired && pendingModalReturn != null && surface.tree.topLayer() == pendingModalReturn.layer()) {
                surface.restoreFocus(pendingModalReturn.control());
                pendingModalReturn = null;
            }
        }
    }

    @Override
    public final void tick() {
        onScreenTick();
        refresh(false);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public final void removed() {
        retired = true;
        publication.close();
        modalReturns.clear();
        pendingModalReturn = null;
        if (surface != null) {
            // Closing/discarding does not apply a field by invoking a blur callback.
            surface.abortInput();
            if (publication.building()) deferredRetirement = true;
            else releaseResources();
        }
        onScreenRemoved();
    }

    private void releaseResources() {
        surface.releasePaintResources();
        deferredRetirement = false;
    }

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        long lifecycle = publication.epoch();
        if (!refresh(true)) {
            if (ownsInput(lifecycle) && publication.failed() && button == 0 && surface.recoveryAction(logicalBounds().width(), logicalBounds().height()).contains(x / DENSITY, y / DENSITY)) {
                publication.retry();
                refresh(false);
            }
            return true;
        }
        return surface.click(x / DENSITY, y / DENSITY, button, hasShiftDown());
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        return !refresh(true) || surface.release(x / DENSITY, y / DENSITY, button);
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return !refresh(true) || surface.drag(x / DENSITY, y / DENSITY);
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double delta) {
        return !refresh(true) || surface.scroll(x / DENSITY, y / DENSITY, delta);
    }

    @Override
    public final boolean charTyped(char character, int modifiers) {
        return !refresh(true) || surface.character(character);
    }

    @Override
    public final boolean keyPressed(int key, int scan, int modifiers) {
        long lifecycle = publication.epoch();
        boolean current = refresh(true);
        if (!ownsInput(lifecycle)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (!current) {
            if (publication.failed() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
                publication.retry();
                refresh(false);
            }
            return true;
        }
        return surface.key(key, hasControlDown(), hasShiftDown());
    }

    @Override
    public final boolean keyReleased(int key, int scan, int modifiers) {
        return !refresh(true);
    }

    private boolean ownsInput(long lifecycle) {
        return !retired && publication.current(lifecycle) && minecraft != null && minecraft.screen == this && minecraft.isWindowActive();
    }

    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean current = refresh(false);
        if (surface == null || retired) return;
        var bounds = logicalBounds();
        Component tooltip = null;
        graphics.pose().pushPose();
        try {
            graphics.pose().scale(DENSITY, DENSITY, 1);
            if (current) tooltip = surface.render(graphics, (int) (mouseX / DENSITY), (int) (mouseY / DENSITY), bounds.width(), bounds.height());
            else surface.renderRecovery(graphics, bounds.width(), bounds.height(), (int) (mouseX / DENSITY), (int) (mouseY / DENSITY));
        } finally {
            graphics.pose().popPose();
        }
        if (tooltip != null) surface.renderTooltip(graphics, tooltip, mouseX, mouseY);
    }

    /** Valid only while compose runs. Neither the runtime nor renderer is exposed. */
    public static final class Composition {

        private final WorkbenchSurface surface;
        private final Bounds bounds;
        private boolean valid = true;

        private Composition(WorkbenchSurface surface, Bounds bounds) {
            this.surface = surface;
            this.bounds = bounds;
        }

        private void check() {
            if (!valid) throw new IllegalStateException("composition already published");
        }

        public Bounds bounds() {
            check();
            return bounds;
        }

        public StudioView view() {
            check();
            return surface.view;
        }

        public StudioComponents components() {
            check();
            return surface.components;
        }

        public StudioTextLayout textLayout() {
            check();
            return surface.textLayout;
        }

        public void background(String id) {
            check();
            surface.rectangle(id, 0, 0, bounds.width(), bounds.height(), 0, surface.theme.background());
        }

        public void emit(StudioView.Layout layout, int layer) {
            check();
            surface.emit(layout, layer);
        }

        public void detail(String id, String text) {
            check();
            surface.detail(id, text);
        }

        public void clip(Bounds clip, Runnable body) {
            check();
            var previous = surface.currentClip();
            surface.clip(previous == null ? clip : previous.intersect(clip));
            try {
                body.run();
            } finally {
                surface.clip(previous);
            }
        }

        public int scroll(String id, StudioView.Element element, Bounds viewport, int layer, int offset, IntConsumer change) {
            check();
            var layout = surface.view.layoutContent(element, 0, 0, Math.max(0, viewport.width() - 8));
            return surface.components.scrollContent(id, viewport, layout.contentHeight(), layer, offset, change,
                    body -> surface.emit(layout.translated(body.x(), body.y()), layer));
        }
    }
}
