package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TabView;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** LDLib2 tab with deterministic keyboard selection and narration. */
@LDLRegister(name = "fpsm-tab", group = "fpsm", registry = "ldlib2:ui_element")
public class AccessibleTab extends Tab implements Ldlib2AccessibilityController.FocusTarget {
    private final KeyboardActivationLatch activationLatch = new KeyboardActivationLatch();
    private Supplier<Component> accessibleName = () -> text.getText();
    private Supplier<Component> accessibleHint = Component::empty;

    public AccessibleTab() {
        setFocusable(true);
        text.setFocusable(false);
        Ldlib2AccessibilityController.installKeyboardActivation(
                this,
                activationLatch,
                this::canActivate,
                this::activate
        );
    }

    @Override
    public AccessibleTab setText(Component text) {
        super.setText(text);
        return this;
    }

    @Override
    public AccessibleTab setText(String text) {
        super.setText(text);
        return this;
    }

    @Override
    public AccessibleTab setText(String text, boolean translate) {
        super.setText(text, translate);
        return this;
    }

    public AccessibleTab setAccessibleName(Component name) {
        return setAccessibleName(() -> name);
    }

    public AccessibleTab setAccessibleName(Supplier<Component> name) {
        accessibleName = Objects.requireNonNull(name, "name");
        return this;
    }

    public AccessibleTab setAccessibleHint(Supplier<Component> hint) {
        accessibleHint = Objects.requireNonNull(hint, "hint");
        return this;
    }

    @Override
    public void activate() {
        TabView view = getTabView();
        if (view != null && isActive()) {
            view.selectTab(this);
        }
    }

    @Override
    public boolean canActivate() {
        return isActive() && getTabView() != null;
    }

    @Override
    public AccessibleTab element() {
        return this;
    }

    @Override
    public Supplier<Component> accessibleName() {
        return accessibleName;
    }

    @Override
    public Supplier<Component> state() {
        return () -> {
            TabView view = getTabView();
            return view != null && view.getSelectedTab() == this
                    ? Component.translatable("gui.done")
                    : Component.empty();
        };
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
