package com.ptcrys.fpsmatch.common.client.screen.ldlib2.element;

import com.lowdragmc.lowdraglib2.LDLib2Registries;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegister;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessiblePanel;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleSelector;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleTab;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleTextField;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleToggle;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2.Ldlib2MapThumbnailElement;
import com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2.Ldlib2PlayerAvatarElement;

import java.util.List;
import java.util.function.Supplier;

/**
 * Safety net for FPSM custom LDLib2 XML element tags.
 *
 * <p>All listed classes carry {@code @LDLRegister(registry = "ldlib2:ui_element")} and are picked
 * up by LDLib2's annotation scan. This verifier runs once during client setup: if a tag is missing
 * from the registry (scan regression), it registers it manually and logs loudly instead of letting
 * XML layouts silently drop the element.</p>
 */
public final class FPSMUiElements {
    private static final List<Class<? extends UIElement>> ELEMENTS = List.of(
            AccessibleButton.class,
            AccessiblePanel.class,
            AccessibleSelector.class,
            AccessibleTextField.class,
            AccessibleToggle.class,
            AccessibleTab.class,
            Ldlib2MapThumbnailElement.class,
            Ldlib2PlayerAvatarElement.class
    );

    private FPSMUiElements() {
    }

    public static void registerAll() {
        for (Class<? extends UIElement> clazz : ELEMENTS) {
            LDLRegister annotation = clazz.getAnnotation(LDLRegister.class);
            if (annotation == null) {
                FPSMatch.LOGGER.error("[FPSM UI] {} is missing @LDLRegister; XML tag unavailable", clazz.getName());
                continue;
            }
            String name = annotation.name();
            if (LDLib2Registries.UI_ELEMENTS.get(name) != null) {
                continue;
            }
            FPSMatch.LOGGER.error("[FPSM UI] ui_element tag '{}' was not auto-scanned; registering manually ({})",
                    name, clazz.getName());
            try {
                Supplier<UIElement> creator = AutoRegistry.noArgsCreator(annotation, clazz);
                LDLib2Registries.UI_ELEMENTS.register(name, AutoRegistry.Holder.of(annotation, clazz, creator));
            } catch (Throwable throwable) {
                FPSMatch.LOGGER.error("[FPSM UI] failed to register ui_element tag '{}'", name, throwable);
            }
        }
    }
}
