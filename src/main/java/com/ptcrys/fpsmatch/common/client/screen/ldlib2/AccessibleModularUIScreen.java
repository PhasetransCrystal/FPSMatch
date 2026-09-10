package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.VirtualScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ArrayList;

/** Base screen that adds direct pointer dispatch and narration to LDLib2. */
public abstract class AccessibleModularUIScreen extends ModularUIScreen {
    private final Ldlib2AccessibilityController accessibility;
    private UIElement directPointerTarget;
    private int directPointerButton = -1;
    private float directPointerStartX;
    private float directPointerStartY;
    private boolean modularUiRemoved;
    private boolean keyboardFocusVisible;

    protected AccessibleModularUIScreen(ModularUI modularUI, Component title) {
        super(modularUI, title);
        accessibility = new Ldlib2AccessibilityController(modularUI, title);
        accessibility.registerGroup(() -> {
            List<Ldlib2AccessibilityController.FocusTarget> targets = new ArrayList<>();
            collectFocusTargets(modularUI.ui.rootElement, targets);
            return targets;
        });
    }

    protected final Ldlib2AccessibilityController accessibility() {
        return accessibility;
    }

    @Override
    public void init() {
        // LDLib2 renders opacity < 1 through a full-window offscreen target. Keep the
        // root on the direct render path, including the first frame after a resize.
        modularUI.ui.rootElement.style(style -> style.opacity(1f));
        super.init();
        modularUiRemoved = false;
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (keyboardFocusVisible && net.minecraft.client.Minecraft.getInstance().screen == this) {
            FPSMLdlib2Theme.drawIndicator(modularUI.getFocusedElement(), graphics,
                    (int) modularUI.getLeftPos(), (int) modularUI.getTopPos());
        }
    }

    protected final void setKeyboardFocusVisible(boolean visible) {
        keyboardFocusVisible = visible;
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        keyboardFocusVisible = false;
        if (directPointerTarget != null) {
            return false;
        }
        UIElement target = hitElementAt(mouseX, mouseY);
        if (target == null) {
            return false;
        }
        if (target == modularUI.getLastHoveredElement()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        UIEvent mouseDown = pointerEvent(UIEvents.MOUSE_DOWN, target, mouseX, mouseY, button);
        UIEventDispatcher.dispatchEvent(mouseDown);
        if (!mouseDown.hasHandler) {
            return false;
        }

        focusPointerTarget(target);
        directPointerTarget = target;
        directPointerButton = button;
        directPointerStartX = (float) (mouseX - modularUI.getLeftPos());
        directPointerStartY = (float) (mouseY - modularUI.getTopPos());
        setFocused(modularUI.getWidget());
        if (button == 0) {
            setDragging(true);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY
    ) {
        if (directPointerTarget == null) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (button != directPointerButton) {
            return false;
        }

        UIEvent drag = pointerEvent(
                UIEvents.DRAG_UPDATE, directPointerTarget, mouseX, mouseY, button
        );
        drag.target = directPointerTarget;
        drag.deltaX = (float) dragX;
        drag.deltaY = (float) dragY;
        drag.dragStartX = directPointerStartX;
        drag.dragStartY = directPointerStartY;
        UIEventDispatcher.dispatchEvent(drag, true, true, false);
        return drag.hasHandler;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (directPointerTarget == null) {
            return super.mouseReleased(mouseX, mouseY, button);
        }
        if (button != directPointerButton) {
            return false;
        }

        UIElement pressedTarget = directPointerTarget;
        int pressedButton = directPointerButton;
        directPointerTarget = null;
        directPointerButton = -1;
        directPointerStartX = 0;
        directPointerStartY = 0;
        setDragging(false);

        UIElement releaseTarget = hitElementAt(mouseX, mouseY);
        UIEvent mouseUp = pointerEvent(
                UIEvents.MOUSE_UP, pressedTarget, mouseX, mouseY, pressedButton
        );
        mouseUp.target = pressedTarget;
        RuntimeException releaseFailure = null;
        try {
            UIEventDispatcher.dispatchEvent(mouseUp);
        } catch (RuntimeException failure) {
            releaseFailure = failure;
        } finally {
            try {
                modularUI.getDragHandler().stopDrag(releaseTarget);
            } catch (RuntimeException failure) {
                releaseFailure = mergeFailure(releaseFailure, failure);
            }
        }
        if (releaseFailure != null) {
            throw releaseFailure;
        }

        if (releaseTarget == pressedTarget) {
            UIEvent click = pointerEvent(
                    UIEvents.CLICK, pressedTarget, mouseX, mouseY, pressedButton
            );
            UIEventDispatcher.dispatchEvent(click);
        }
        return true;
    }

    private void focusPointerTarget(UIElement target) {
        if (target.isFocusable()) {
            if (target.isActive()) {
                modularUI.requestFocus(target);
            }
            return;
        }

        modularUI.clearFocus();
        List<UIElement> path = target.getStructurePath();
        for (int index = path.size() - 1; index >= 0; index--) {
            UIElement candidate = path.get(index);
            if (candidate.isFocusable()) {
                modularUI.requestFocus(candidate);
                return;
            }
        }
    }

    protected final UIElement hitElementAt(double mouseX, double mouseY) {
        var hit = modularUI.ui.rootElement.hitTest(
                mouseX - modularUI.getLeftPos(), mouseY - modularUI.getTopPos()
        );
        return hit == null ? null : hit.getA();
    }

    private UIEvent pointerEvent(
            String type,
            UIElement target,
            double mouseX,
            double mouseY,
            int button
    ) {
        UIEvent event = UIEvent.create(type);
        event.x = (float) (mouseX - modularUI.getLeftPos());
        event.y = (float) (mouseY - modularUI.getTopPos());
        event.button = button;
        event.target = target;
        return event;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB
                || keyCode >= org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT && keyCode <= org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
            keyboardFocusVisible = true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB
                && accessibility.moveFocus((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0)) {
            revealKeyboardFocus();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static void collectFocusTargets(UIElement element, List<Ldlib2AccessibilityController.FocusTarget> targets) {
        if (!element.isVisible() || !element.isDisplayed() || !element.isActive()) return;
        if (element instanceof Ldlib2AccessibilityController.FocusTarget target) targets.add(target);
        for (UIElement child : element.getChildren()) collectFocusTargets(child, targets);
    }

    private void revealKeyboardFocus() {
        UIElement focused = modularUI.getFocusedElement();
        if (focused == null) return;
        String id = focused.getId();
        for (UIElement ancestor = focused.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (!(ancestor instanceof ScrollerView scroller) || !scroller.viewContainer.isAncestorOf(focused)) continue;
            float top = scroller.viewPort.getContentY();
            float height = scroller.viewPort.getContentHeight();
            float delta = focused.getPositionY() < top ? focused.getPositionY() - top
                    : Math.max(0, focused.getPositionY() + focused.getSizeHeight() - top - height);
            float total = scroller instanceof VirtualScrollerView<?> virtual
                    ? virtual.getTotalVirtualHeight() : scroller.viewContainer.getSizeHeight();
            if (delta == 0 || total <= height) continue;
            float normalized = Math.max(0, Math.min(1, scroller.verticalScroller.getNormalizedValue() + delta / (total - height)));
            scroller.verticalScroller.setNormalizedValue(normalized);
            // Virtual scrolling replaces the focused row, so recover the new instance by its stable ID.
            if (id != null && !id.isEmpty()) modularUI.ui.rootElement.selectId(id, UIElement.class)
                    .findFirst().ifPresent(UIElement::focus);
            break;
        }
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        accessibility.updateNarrationState(output);
    }

    protected final void announce(Component message, boolean error) {
        accessibility.announce(message, error);
        triggerImmediateNarration(true);
    }

    @Override
    public void removed() {
        boolean stopDrag = modularUI.getDragHandler().isDragging();
        directPointerTarget = null;
        directPointerButton = -1;
        directPointerStartX = 0;
        directPointerStartY = 0;
        setDragging(false);

        RuntimeException teardownFailure = null;
        if (!modularUiRemoved) {
            modularUiRemoved = true;
            try {
                modularUI.onRemoved();
            } catch (RuntimeException failure) {
                teardownFailure = mergeFailure(teardownFailure, failure);
            }
        }
        if (stopDrag) {
            try {
                modularUI.getDragHandler().stopDrag(null);
            } catch (RuntimeException failure) {
                teardownFailure = mergeFailure(teardownFailure, failure);
            }
        }
        try {
            accessibility.clearFocus();
        } catch (RuntimeException failure) {
            teardownFailure = mergeFailure(teardownFailure, failure);
        }
        try {
            super.removed();
        } catch (RuntimeException failure) {
            teardownFailure = mergeFailure(teardownFailure, failure);
        }
        if (teardownFailure != null) {
            throw teardownFailure;
        }
    }

    private static RuntimeException mergeFailure(
            RuntimeException current,
            RuntimeException next
    ) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }
}
