package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** LDLib2 toggle whose pointer and keyboard paths share the native toggle transition. */
@LDLRegister(name = "fpsm-toggle", group = "fpsm", registry = "ldlib2:ui_element")
public class AccessibleToggle extends Toggle implements Ldlib2AccessibilityController.FocusTarget {
    private final KeyboardActivationLatch activationLatch = new KeyboardActivationLatch();
    private Supplier<Component> accessibleName = () -> toggleLabel.getText();
    private Supplier<Component> accessibleHint = Component::empty;

    public AccessibleToggle() {
        setFocusable(true);
        toggleButton.setFocusable(false);
        toggleLabel.setFocusable(false);
        toggleButton.addEventListener("mouseDown", event -> focus());
        Ldlib2AccessibilityController.installKeyboardActivation(
                this,
                activationLatch,
                this::canActivate,
                this::activate
        );
    }

    @Override
    public AccessibleToggle setText(Component text) {
        super.setText(text);
        return this;
    }

    @Override
    public AccessibleToggle setText(String text) {
        super.setText(text);
        return this;
    }

    @Override
    public AccessibleToggle setText(String text, boolean translate) {
        super.setText(text, translate);
        return this;
    }

    public AccessibleToggle setAccessibleName(Component name) {
        return setAccessibleName(() -> name);
    }

    public AccessibleToggle setAccessibleName(Supplier<Component> name) {
        accessibleName = Objects.requireNonNull(name, "name");
        return this;
    }

    public AccessibleToggle setAccessibleHint(Supplier<Component> hint) {
        accessibleHint = Objects.requireNonNull(hint, "hint");
        return this;
    }

    @Override
    public void activate() {
        if (isActive()) {
            onToggleClick(Ldlib2AccessibilityController.keyboardActivationEvent(this));
        }
    }

    @Override
    public boolean canActivate() {
        return isActive();
    }

    @Override
    public AccessibleToggle element() {
        return this;
    }

    @Override
    public Supplier<Component> accessibleName() {
        return accessibleName;
    }

    @Override
    public Supplier<Component> state() {
        return () -> Component.translatable(isOn() ? "options.on" : "options.off");
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
