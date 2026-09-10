package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.AccessibleButton;
import com.ptcrys.fpsmatch.common.client.screen.ldlib2.Ldlib2XmlUi;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaPositionType;

import java.util.List;
import java.util.function.Consumer;

/** Shared navigation geometry for the room pages, independent of permissions. */
final class MapLobbyTabs {
    private static final List<String> TABS = List.of("details", "players", "settings", "more");
    private final UIElement strip;
    private final List<AccessibleButton> buttons;

    MapLobbyTabs(UI ui, String prefix, String selected, Consumer<String> navigate) {
        strip = Ldlib2XmlUi.require(ui, prefix + ".tabs", UIElement.class);
        buttons = TABS.stream().map(tab -> {
            AccessibleButton button = Ldlib2XmlUi.require(ui, prefix + ".tab." + tab, AccessibleButton.class);
            UIElement indicator = new UIElement().addClass("tab-indicator");
            indicator.setAllowHitTest(false);
            indicator.setFocusable(false);
            button.addChild(indicator);
            button.setOnClick(event -> navigate.accept(tab));
            button.setAccessibleState(() -> Component.translatable(selected.equals(tab)
                    ? "gui.fpsm.team_manage.tab.current" : "gui.fpsm.team_manage.tab.available"));
            if (selected.equals(tab)) button.addClass("tab-active");
            else button.removeClass("tab-active");
            return button;
        }).toList();
    }

    void update(boolean operator, boolean enabled) {
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setAvailability(true, enabled && (i != 3 || operator));
        }
        buttons.get(3).setAccessibleHint(() -> operator ? Component.empty()
                : Component.translatable("gui.fpsm.team_manage.more.restricted"));
    }

    void select(String selected) {
        for (int i = 0; i < buttons.size(); i++) {
            AccessibleButton button = buttons.get(i);
            boolean current = TABS.get(i).equals(selected);
            if (current) button.addClass("tab-active");
            else button.removeClass("tab-active");
            button.setAccessibleState(() -> Component.translatable(current
                    ? "gui.fpsm.team_manage.tab.current" : "gui.fpsm.team_manage.tab.available"));
        }
    }

    void layout(int width, int height) {
        int margin = Math.min(16, Math.max(8, width / 32));
        int available = Math.max(1, width - margin * 2);
        strip.layout(l -> l.positionType(YogaPositionType.ABSOLUTE)
                .left(margin).top(height < 300 ? 32 : 47).width(available).height(24));
        int tabWidth = Math.max(1, (available - 15) / 4);
        for (int i = 0; i < buttons.size(); i++) {
            int left = i * (tabWidth + 5);
            buttons.get(i).layout(l -> l.positionType(YogaPositionType.ABSOLUTE)
                    .left(left).top(0).width(tabWidth).height(24));
        }
    }
}
