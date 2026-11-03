package net.ptcrys.fpsmatch.editor.common.text.java2d;

/** One visual horizontal interval of a logical text selection, in the line's units. */
public record TextSpan(float x, float width) {

    public TextSpan {
        if (!Float.isFinite(x) || !Float.isFinite(width) || width < 0f) throw new IllegalArgumentException("invalid text span");
    }
}
