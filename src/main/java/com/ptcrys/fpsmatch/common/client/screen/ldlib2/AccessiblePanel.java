package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** Focusable LDLib2 panel for selectable rows and other composite controls. */
@LDLRegister(name = "fpsm-panel", group = "fpsm", registry = "ldlib2:ui_element")
public class AccessiblePanel extends UIElement implements Ldlib2AccessibilityController.FocusTarget {
    private final KeyboardActivationLatch activationLatch = new KeyboardActivationLatch();
    private Supplier<Component> accessibleName = Component::empty;
    private Supplier<Component> accessibleState = Component::empty;
    private Supplier<Component> accessibleHint = Component::empty;
    private Runnable action;

    public AccessiblePanel() {
        setFocusable(true);
        addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 0 && canActivate()) {
                activate();
                event.stopPropagation();
            }
        });
        Ldlib2AccessibilityController.installKeyboardActivation(
                this,
                activationLatch,
                this::canActivate,
                this::activate
        );
    }

    public AccessiblePanel setOnActivate(Runnable action) {
        this.action = Objects.requireNonNull(action, "action");
        return this;
    }

    public AccessiblePanel setAccessibleName(Component name) {
        return setAccessibleName(() -> name);
    }

    public AccessiblePanel setAccessibleName(Supplier<Component> name) {
        accessibleName = Objects.requireNonNull(name, "name");
        return this;
    }

    public AccessiblePanel setAccessibleState(Supplier<Component> state) {
        accessibleState = Objects.requireNonNull(state, "state");
        return this;
    }

    public AccessiblePanel setAccessibleHint(Supplier<Component> hint) {
        accessibleHint = Objects.requireNonNull(hint, "hint");
        return this;
    }

    @Override
    public void activate() {
        if (action != null && isActive()) {
            action.run();
        }
    }

    @Override
    public boolean canActivate() {
        return action != null && isActive();
    }

    @Override
    public AccessiblePanel element() {
        return this;
    }

    @Override
    public Supplier<Component> accessibleName() {
        return accessibleName;
    }

    @Override
    public Supplier<Component> state() {
        return accessibleState;
    }

    @Override
    public Supplier<Component> hint() {
        return accessibleHint;
    }

    @Override
    public void resetActivationLatch() {
        activationLatch.reset();
    }

    @Override
    public void drawBackgroundOverlay(GUIContext context) {
        super.drawBackgroundOverlay(context);
        FPSMLdlib2Theme.drawFocusRing(this, context);
    }
}
