package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.math.Size;
import com.lowdragmc.lowdraglib2.utils.XmlUtils;
import com.ptcrys.fpsmatch.FPSMatch;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.Document;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Loads LDLib2 XML layouts with a guaranteed non-null fallback so broken assets never crash the client. */
public final class Ldlib2XmlUi {
    private Ldlib2XmlUi() {
    }

    /**
     * Loads an XML layout (e.g. {@code fpsmatch:ldlib2/ui/map_invite.xml}) and wraps it in a
     * {@link ModularUI} that always fills the screen. On any failure this logs the problem and
     * returns an empty root so screens can still open.
     */
    public static ModularUI load(String location) {
        UI ui = loadUi(location);
        return ModularUI.of(ui);
    }

    public static UI loadUi(String location) {
        return loadUi(location, size -> Size.of(size.getWidth(), size.getHeight()));
    }

    /** Variant with a custom dynamic-size wrapper (e.g. inset work surfaces like the shop editor). */
    public static UI loadUi(String location, java.util.function.UnaryOperator<Size> sizeWrapper) {
        Document xml = null;
        try {
            xml = XmlUtils.loadXml(ResourceLocation.parse(location));
        } catch (Throwable throwable) {
            FPSMatch.LOGGER.error("[FPSM UI] failed to parse ui xml {}", location, throwable);
        }
        UI base;
        if (xml == null) {
            FPSMatch.LOGGER.error("[FPSM UI] ui xml {} not found; using empty fallback root", location);
            base = UI.of(new UIElement().setId("fpsmatch.xml.fallback"));
        } else {
            try {
                base = UI.of(xml);
            } catch (Throwable throwable) {
                FPSMatch.LOGGER.error("[FPSM UI] failed to build ui from xml {}", location, throwable);
                base = UI.of(new UIElement().setId("fpsmatch.xml.fallback"));
            }
        }
        return UI.of(base.rootElement, base.stylesheets, sizeWrapper::apply);
    }

    /**
     * Resolves a required element by id after the UI has been attached to a screen.
     * Logs every missing id once per screen so XML/catalog drift is loud, and returns null.
     */
    @Nullable
    public static <T extends UIElement> T require(UI ui, String id, Class<T> type) {
        return ui.rootElement.selectId(id, type).findFirst().orElseGet(() -> {
            FPSMatch.LOGGER.error("[FPSM UI] missing required element #{} of type {}", id, type.getSimpleName());
            return null;
        });
    }

    /** Batch variant of {@link #require(UI, String, Class)}; returns the ids that were not found. */
    public static List<String> verify(UI ui, List<Binding<?>> bindings) {
        List<String> missing = new ArrayList<>();
        for (Binding<?> binding : bindings) {
            if (ui.rootElement.selectId(binding.id(), binding.type()).findFirst().isEmpty()) {
                missing.add(binding.id());
            }
        }
        if (!missing.isEmpty()) {
            FPSMatch.LOGGER.error("[FPSM UI] xml layout is missing required element ids: {}", missing);
        }
        return missing;
    }

    public record Binding<T extends UIElement>(String id, Class<T> type) {
        public static <T extends UIElement> Binding<T> of(String id, Class<T> type) {
            return new Binding<>(id, type);
        }
    }
}
