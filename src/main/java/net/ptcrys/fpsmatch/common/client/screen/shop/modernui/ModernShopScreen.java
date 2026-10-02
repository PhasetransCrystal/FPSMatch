package net.ptcrys.fpsmatch.common.client.screen.shop.modernui;

import net.ptcrys.fpsmatch.common.client.screen.modernui.ModernScreen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import icyllis.modernui.text.TextUtils;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.TextView;

import java.util.List;

/** Shared shop chrome keeps commands visible while each content region can scroll. */
abstract class ModernShopScreen extends ModernScreen {

    protected ModernShopScreen(Component title, Screen parent) {
        super(title, parent);
    }

    @Override
    protected int designWidth() {
        return Math.max(240, width);
    }

    @Override
    protected int designHeight() {
        return Math.max(160, height);
    }

    protected final int layoutWidth() {
        return designWidth();
    }

    protected final int layoutHeight() {
        return designHeight();
    }

    protected final Node place(Node node, ShopEditorLayoutModel.Rect rect) {
        return node.at(rect.x(), rect.y(), rect.width(), rect.height());
    }

    protected final Node header(String title, String identity, Node action) {
        int w = layoutWidth() - 16, actionWidth = action.kind() == Kind.ICON ? 24 : 112;
        return place(canvas("header", List.of(
                ModernScreen.title("title", title).hint(title).at(0, 0, w - actionWidth - 8, 20),
                action.at(w - actionWidth, 0, actionWidth, 24),
                muted("identity", identity).hint(identity).at(0, 24, w, 14))),
                ShopEditorLayoutModel.frame(layoutWidth(), layoutHeight()).header());
    }

    protected final Node footer(String status, Node reload, Node primary, Node back) {
        int w = layoutWidth() - 16;
        int backWidth = Math.min(100, w / 3), primaryWidth = Math.min(168, w - backWidth - 40);
        return place(canvas("actions", List.of(
                muted("status", status).hint(status).at(0, 0, w, 20),
                reload.at(w - backWidth - primaryWidth - 36, 24, 24, 24),
                primary.at(w - backWidth - primaryWidth - 8, 24, primaryWidth, 24),
                back.at(w - backWidth, 24, backWidth, 24))),
                ShopEditorLayoutModel.frame(layoutWidth(), layoutHeight()).actions());
    }

    @Override
    protected void styleView(View view, Node node, float scale, boolean changed) {
        if (view instanceof TextView label && node.kind() != Kind.INPUT) {
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
        }
    }
}
