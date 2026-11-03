package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

/** One conversion from logical edges to framebuffer edges, including fractional density. */
public final class StudioDeviceClip {

    private StudioDeviceClip() {}

    public static Bounds pixels(Bounds logical, double scale, int framebufferHeight) {
        if (!Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("invalid device scale");
        int left = (int) Math.floor(logical.x() * scale), right = (int) Math.ceil(((double) logical.x() + logical.width()) * scale);
        int top = (int) Math.floor(logical.y() * scale), bottom = (int) Math.ceil(((double) logical.y() + logical.height()) * scale);
        return new Bounds(left, framebufferHeight - bottom, logical.width() == 0 ? 0 : Math.max(0, right - left), logical.height() == 0 ? 0 : Math.max(0, bottom - top));
    }
}
