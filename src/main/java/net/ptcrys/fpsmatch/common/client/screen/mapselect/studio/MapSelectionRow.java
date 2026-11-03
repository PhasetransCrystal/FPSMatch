package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponents;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioView;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Length;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;

import java.util.List;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/** One focusable room row; the thumbnail and all text are passive children. */
public final class MapSelectionRow {

    private MapSelectionRow() {}

    public record Text(String title, String identity, String players, String state, String openAction) {}

    public static StudioView.Element compose(StudioView view, StudioComponents.Action action, Text text, String texture, int width) {
        String id = action.id();
        boolean wide = width >= 680;
        var thumbnail = view.image(id + "/thumbnail", texture, Icon.MAP, wide ? 96 : 64, wide ? 58 : 40);
        var name = view.column(id + "/names", Style.defaults().gap(5), List.of(
                view.paragraph(id + "/title", text.title(), TextSize.HEADING, Variant.DEFAULT),
                view.paragraph(id + "/identity", text.identity(), TextSize.SMALL, Variant.MUTED)));
        var facts = view.column(id + "/facts", Style.defaults().gap(5), List.of(
                view.paragraph(id + "/players", text.players()),
                view.paragraph(id + "/state", text.state(), TextSize.SMALL, Variant.MUTED)));
        var open = view.paragraph(id + "/open", text.openAction(), TextSize.BODY, Variant.PRIMARY);
        List<StudioView.Element> children;
        if (wide) {
            children = List.of(thumbnail, name, facts.style(Style.defaults().width(Length.fixed(128)).gap(5)),
                    open.style(Style.defaults().width(Length.fixed(110))));
        } else {
            var footer = view.wrap(id + "/footer", Style.defaults().gap(8), List.of(
                    facts.style(Style.defaults().width(Length.stretch().limits(112, Integer.MAX_VALUE)).gap(5)),
                    open.style(Style.defaults().width(Length.stretch().limits(96, Integer.MAX_VALUE)))));
            var heading = view.row(id + "/heading", Style.defaults().gap(12), List.of(thumbnail, name));
            children = List.of(view.column(id + "/content", Style.defaults().gap(10), List.of(heading, footer)));
        }
        return view.actionRow(action, Style.defaults().padding(Insets.all(12)).gap(12), children);
    }
}
