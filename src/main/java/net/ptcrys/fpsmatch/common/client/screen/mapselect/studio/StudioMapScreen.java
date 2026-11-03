package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMMapSelectScreens;
import net.ptcrys.fpsmatch.editor.common.client.workbench.StudioScreen;
import net.ptcrys.fpsmatch.editor.common.ui.component.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/** Shared page chrome; layout and input remain owned by Studio. */
public abstract class StudioMapScreen extends StudioScreen {

    protected String status = "";
    protected boolean statusError;
    private int overlayScroll;

    protected StudioMapScreen(Component title, Screen parent, String identity) {
        super(title, parent, identity + "/" + UUID.randomUUID());
    }

    public final void navigationStateChanged() {
        requestLayout();
    }

    protected String pageTitle() {
        return getTitle().getString();
    }

    protected String pageSubtitle() {
        return "";
    }

    protected String backLabel() {
        return tr(parent instanceof StudioMapSelectionScreen ? "gui.fpsm.studio.back_to_maps" : "gui.back");
    }

    protected StudioView.Element topControls(StudioView view) {
        return null;
    }

    protected List<StudioComponents.Action> footerActions() {
        return List.of();
    }

    protected abstract void composeBody(Composition content, Bounds body);

    protected void composeOverlay(Composition content) {}

    protected final void message(String text, boolean error) {
        status = text;
        statusError = error;
        requestLayout();
    }

    protected static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    protected static String part(String value) {
        return value.length() + ":" + value;
    }

    protected static StudioComponents.Action action(String id, String label, Icon icon, Variant variant, boolean enabled, Runnable invoke) {
        return new StudioComponents.Action(id, label, icon, variant, enabled, false, "", invoke);
    }

    protected static StudioView.Element buttons(StudioView view, String id, List<StudioComponents.Action> actions) {
        return view.wrap(id, Style.defaults().gap(5), actions.stream().map(view::button).toList());
    }

    @Override
    protected final void compose(Composition content) {
        var view = content.view();
        content.background("page/background");
        var title = new ArrayList<StudioView.Element>();
        title.add(view.text("page/title", pageTitle(), TextSize.TITLE, Variant.DEFAULT));
        if (!pageSubtitle().isBlank()) title.add(view.text("page/subtitle", pageSubtitle(), TextSize.SMALL, Variant.MUTED));
        var back = view.button(action("page/back", backLabel(), Icon.PREVIOUS, Variant.DEFAULT, true, this::onClose)).style(Style.content());
        var headerActions = view.button(action("page/theme", tr("gui.fpsm.studio.theme"), themeName().equals("dark") ? Icon.SUN : Icon.MOON, Variant.DEFAULT, true, () -> setTheme(themeName().equals("dark") ? "light" : "dark")), Kind.ICON).style(Style.content());
        var elements = new ArrayList<StudioView.Element>();
        elements.add(view.row("page/header", Style.defaults().gap(10), List.of(back, view.column("page/titles", Style.defaults().gap(4), title), headerActions)));
        var controls = topControls(view);
        if (controls != null) elements.add(controls);
        elements.add(view.placeholder("page/body", Style.fill()));
        if (!status.isBlank()) elements.add(view.paragraph("page/status", status, TextSize.SMALL, statusError ? Variant.DANGER : Variant.MUTED));
        var footer = footerActions();
        if (!footer.isEmpty()) elements.add(buttons(view, "page/footer", footer));
        var layout = view.layout(view.column("page/root", Style.fill().padding(Insets.all(12)).gap(10), elements), content.bounds());
        content.clip(content.bounds(), () -> {
            content.emit(layout, 0);
            composeBody(content, layout.bounds("page/body"));
        });
        composeOverlay(content);
    }

    protected final void choices(Composition content, String id, String title, List<StudioComponents.Action> actions, Runnable close) {
        var view = content.view();
        var all = content.bounds();
        int width = Math.min(340, Math.max(0, all.width() - 24));
        var heading = view.text(id + "/title", title, TextSize.HEADING, Variant.DEFAULT);
        var cancel = view.button(action(id + "/cancel", tr("gui.cancel"), Icon.NONE, Variant.OUTLINED, true, close));
        var body = view.column(id + "/entries", Style.defaults().gap(4), actions.stream().map(a -> view.button(a).style(Style.defaults())).toList());
        int preferredHeight = view.layoutContent(view.column(id + "/natural",
                Style.defaults().padding(Insets.all(12)).gap(10), List.of(heading, body, cancel)), 0, 0, width).contentHeight();
        int height = Math.min(preferredHeight, Math.max(0, all.height() - 24));
        var bounds = new Bounds((all.width() - width) / 2, (all.height() - height) / 2, width, height);
        content.components().surface(id + "/surface", bounds, 10, Kind.DIALOG);
        var frame = view.layout(view.column(id + "/root", Style.fill().padding(Insets.all(12)).gap(10), List.of(
                heading,
                view.placeholder(id + "/body", Style.fill()),
                cancel)), bounds);
        content.emit(frame, 10);
        overlayScroll = content.scroll(id + "/scroll", body, frame.bounds(id + "/body"), 10, overlayScroll, value -> {
            overlayScroll = value;
            requestLayout();
        });
    }

    protected final void resetOverlayScroll() {
        overlayScroll = 0;
    }

    @Override
    public void onClose() {
        FPSMMapSelectScreens.closeView(this, parent);
    }
}
