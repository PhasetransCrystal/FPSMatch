package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** LDLib2 selector with explicit keyboard disclosure and narratable value state. */
@LDLRegister(name = "fpsm-selector", group = "fpsm", registry = "ldlib2:ui_element")
public class AccessibleSelector<T> extends Selector<T>
        implements Ldlib2AccessibilityController.FocusTarget {
    private final KeyboardActivationLatch activationLatch = new KeyboardActivationLatch();
    private Supplier<Component> accessibleName = Component::empty;
    private Supplier<Component> accessibleHint = Component::empty;
    private Function<? super T, Component> valueNarration = value -> Component.empty();

    public AccessibleSelector() {
        setFocusable(true);
        display.setFocusable(false);
        display.addEventListener("mouseDown", event -> focus());
        Ldlib2AccessibilityController.installKeyboardActivation(
                this,
                activationLatch,
                this::canActivate,
                this::activate
        );
    }

    public AccessibleSelector<T> setAccessibleName(Component name) {
        return setAccessibleName(() -> name);
    }

    public AccessibleSelector<T> setAccessibleName(Supplier<Component> name) {
        accessibleName = Objects.requireNonNull(name, "name");
        return this;
    }

    public AccessibleSelector<T> setAccessibleHint(Supplier<Component> hint) {
        accessibleHint = Objects.requireNonNull(hint, "hint");
        return this;
    }

    public AccessibleSelector<T> setValueNarration(Function<? super T, Component> narration) {
        valueNarration = Objects.requireNonNull(narration, "narration");
        return this;
    }

    @Override
    public void activate() {
        if (!isActive()) {
            return;
        }
        if (isOpen()) {
            hide();
        } else {
            show();
        }
    }

    @Override
    public boolean canActivate() {
        return isActive();
    }

    @Override
    public AccessibleSelector<T> element() {
        return this;
    }

    @Override
    public Supplier<Component> accessibleName() {
        return accessibleName;
    }

    @Override
    public Supplier<Component> state() {
        return () -> getValue() == null ? Component.empty() : valueNarration.apply(getValue());
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
