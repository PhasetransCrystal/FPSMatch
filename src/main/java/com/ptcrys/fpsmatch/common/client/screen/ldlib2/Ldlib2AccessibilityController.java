package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;

/** Application-local keyboard focus and narration bridge for the pinned LDLib2 API. */
public final class Ldlib2AccessibilityController {
    private final ModularUI modularUI;
    private final Supplier<Component> title;
    private final List<Supplier<List<FocusTarget>>> groups = new ArrayList<>();
    private final Ldlib2FocusModel<FocusTarget> focusModel;
    private final KeyboardActivationLatch activationLatch = new KeyboardActivationLatch();
    private Component announcement;
    private boolean announcementIsError;

    public Ldlib2AccessibilityController(ModularUI modularUI, Component title) {
        this(modularUI, () -> title);
    }

    public Ldlib2AccessibilityController(ModularUI modularUI, Supplier<Component> title) {
        this.modularUI = Objects.requireNonNull(modularUI, "modularUI");
        this.title = Objects.requireNonNull(title, "title");
        this.focusModel = new Ldlib2FocusModel<>(this::eligible);
    }

    public void registerGroup(Supplier<List<FocusTarget>> targets) {
        groups.add(Objects.requireNonNull(targets, "targets"));
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW_KEY_TAB) {
            activationLatch.reset();
            return moveFocus((modifiers & GLFW_MOD_SHIFT) != 0);
        }
        if (!KeyboardActivationLatch.isActivationKey(keyCode)) {
            return false;
        }
        refreshTargets();
        Optional<FocusTarget> focused = focusModel.reconcile();
        if (focused.isEmpty()) {
            return false;
        }
        FocusTarget target = focused.get();
        if (activationLatch.press(keyCode) && target.canActivate()) {
            target.activate();
        }
        return true;
    }

    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        return activationLatch.release(keyCode);
    }

    public boolean moveFocus(boolean reverse) {
        refreshTargets();
        Optional<FocusTarget> previous = focusModel.focused();
        Optional<FocusTarget> next = focusModel.move(reverse);
        applyFocus(previous, next);
        return next.isPresent();
    }

    public void reconcileFocus() {
        refreshTargets();
        Optional<FocusTarget> previous = focusModel.focused();
        Optional<FocusTarget> next = focusModel.reconcile();
        applyFocus(previous, next);
    }

    public Optional<FocusTarget> focusedTarget() {
        return focusModel.focused();
    }

    public void updateNarrationState(NarrationElementOutput output) {
        Objects.requireNonNull(output, "output");
        output.add(NarratedElementType.TITLE, component(title));
        for (UIElement element = modularUI.getFocusedElement(); element != null; element = element.getParent()) {
            if (element instanceof FocusTarget target && eligible(target)) {
                output.add(NarratedElementType.POSITION, component(target.accessibleName()));
                addIfPresent(output, NarratedElementType.HINT, target.state());
                addIfPresent(output, NarratedElementType.USAGE, target.hint());
                break;
            }
        }
        if (announcement != null) {
            output.add(announcementIsError
                    ? NarratedElementType.HINT
                    : NarratedElementType.USAGE, announcement);
        }
    }

    public void announce(Component message, boolean error) {
        announcement = Objects.requireNonNull(message, "message");
        announcementIsError = error;
    }

    public void clearAnnouncement() {
        announcement = null;
        announcementIsError = false;
    }

    public void clearFocus() {
        Optional<FocusTarget> previous = focusModel.focused();
        focusModel.clear();
        applyFocus(previous, Optional.empty());
    }

    private void refreshTargets() {
        List<FocusTarget> targets = new ArrayList<>();
        Set<FocusTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Supplier<List<FocusTarget>> group : groups) {
            for (FocusTarget target : Objects.requireNonNull(group.get(), "focus group")) {
                Objects.requireNonNull(target, "focus target");
                if (seen.add(target)) {
                    targets.add(target);
                }
            }
        }
        focusModel.setTargets(targets);
        targets.stream()
                .filter(this::eligible)
                .filter(target -> target.element().isFocused() || target.element().isChildFocused())
                .findFirst()
                .ifPresent(focusModel::adopt);
    }

    private boolean eligible(FocusTarget target) {
        UIElement element = target.element();
        if (element == null || element.getModularUI() != modularUI || !element.isFocusable()) {
            return false;
        }
        while (element != null) {
            if (!element.isVisible() || !element.isDisplayed() || !element.isActive()) {
                return false;
            }
            element = element.getParent();
        }
        return true;
    }

    private void applyFocus(Optional<FocusTarget> previous, Optional<FocusTarget> next) {
        if (previous.equals(next)) {
            return;
        }
        activationLatch.reset();
        previous.ifPresent(target -> {
            target.resetActivationLatch();
            if (target.element().getModularUI() == modularUI) {
                target.element().blur();
            }
        });
        next.ifPresent(target -> {
            target.element().focus();
            announcement = component(target.accessibleName());
            announcementIsError = false;
        });
    }

    private static Component component(Supplier<Component> supplier) {
        Component component = Objects.requireNonNull(supplier, "supplier").get();
        return component == null ? Component.empty() : component;
    }

    private static void addIfPresent(
            NarrationElementOutput output,
            NarratedElementType type,
            Supplier<Component> supplier
    ) {
        Component component = component(supplier);
        if (!component.getString().isEmpty()) {
            output.add(type, component);
        }
    }

    static boolean isAvailable(UIElement target) {
        UIElement element = target;
        if (element.getModularUI() == null || !element.isFocusable()) {
            return false;
        }
        while (element != null) {
            if (!element.isVisible() || !element.isDisplayed() || !element.isActive()) {
                return false;
            }
            element = element.getParent();
        }
        return true;
    }

    static void installKeyboardActivation(
            UIElement element,
            KeyboardActivationLatch latch,
            BooleanSupplier canActivate,
            Runnable action
    ) {
        element.addEventListener(UIEvents.KEY_DOWN, event -> {
            if (!isAvailable(element) || !canActivate.getAsBoolean()) {
                return;
            }
            if (latch.press(event.keyCode)) {
                action.run();
                event.stopPropagation();
            } else if (latch.isHeld(event.keyCode)) {
                event.stopPropagation();
            }
        });
        element.addEventListener(UIEvents.KEY_UP, event -> {
            if (latch.release(event.keyCode)) {
                event.stopPropagation();
            }
        });
        element.addEventListener(UIEvents.BLUR, event -> latch.reset());
    }

    static UIEvent keyboardActivationEvent(UIElement target) {
        UIEvent event = UIEvent.create(UIEvents.KEY_DOWN);
        event.target = target;
        event.currentElement = target;
        return event;
    }

    public interface FocusTarget {
        UIElement element();

        Supplier<Component> accessibleName();

        default Supplier<Component> state() {
            return Component::empty;
        }

        default Supplier<Component> hint() {
            return Component::empty;
        }

        default boolean canActivate() {
            return true;
        }

        void activate();

        default void resetActivationLatch() {
        }
    }
}
