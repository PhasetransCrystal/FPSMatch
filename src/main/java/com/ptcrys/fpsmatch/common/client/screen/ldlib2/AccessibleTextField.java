package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** LDLib2 text field that participates in the application-local focus model. */
@LDLRegister(name = "fpsm-textfield", group = "fpsm", registry = "ldlib2:ui_element")
public final class AccessibleTextField extends TextField
        implements Ldlib2AccessibilityController.FocusTarget {
    private Supplier<Component> accessibleName = Component::empty;
    private Supplier<Component> accessibleHint = Component::empty;

    public AccessibleTextField() {
        setFocusable(true);
    }

    public AccessibleTextField setAccessibleName(Component name) {
        return setAccessibleName(() -> name);
    }

    public AccessibleTextField setAccessibleName(Supplier<Component> name) {
        accessibleName = Objects.requireNonNull(name, "name");
        return this;
    }

    public AccessibleTextField setAccessibleHint(Supplier<Component> hint) {
        accessibleHint = Objects.requireNonNull(hint, "hint");
        return this;
    }

    @Override
    public void activate() {
        if (isActive()) {
            focus();
        }
    }

    @Override
    public boolean canActivate() {
        return isActive();
    }

    @Override
    public AccessibleTextField element() {
        return this;
    }

    @Override
    public Supplier<Component> accessibleName() {
        return accessibleName;
    }

    @Override
    public Supplier<Component> state() {
        return () -> Component.literal(getText());
    }

    @Override
    public Supplier<Component> hint() {
        return accessibleHint;
    }

    @Override
    public void drawBackgroundOverlay(GUIContext context) {
        super.drawBackgroundOverlay(context);
        FPSMLdlib2Theme.drawFocusRing(this, context);
    }
}
