package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioTextLayout;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

/** Temporary native-font backend. The UI owns geometry, caching and input positions. */
final class StudioFontMetrics implements StudioTextLayout.Metrics {

    private final Font font;

    StudioFontMetrics(Font font) {
        this.font = font;
    }

    @Override
    public int width(String text, TextSize size) {
        return scaled(font.getSplitter().stringWidth(text), size);
    }

    private int scaled(float advance, TextSize size) {
        return (int) Math.ceil(advance * (double) size.height / font.lineHeight);
    }

    @Override
    public int[] prefixAdvances(String text, TextSize size) {
        int[] result = new int[text.codePointCount(0, text.length()) + 1];
        int[] cursor = { 0, 0 };
        float[] advance = { 0 };
        StringDecomposer.iterateFormatted(text, Style.EMPTY, (index, style, codePoint) -> {
            // Formatting codes occupy source positions but do not advance the pen.
            while (cursor[0] < index) {
                int next = cursor[0] + Character.charCount(text.codePointAt(cursor[0]));
                if (next > index) break;
                cursor[0] = next;
                result[++cursor[1]] = scaled(advance[0], size);
            }
            advance[0] += font.getSplitter().stringWidth(FormattedCharSequence.codepoint(codePoint, style));
            int end = index + Character.charCount(codePoint);
            while (cursor[0] < end) {
                cursor[0] += Character.charCount(text.codePointAt(cursor[0]));
                result[++cursor[1]] = scaled(advance[0], size);
            }
            return true;
        });
        while (cursor[0] < text.length()) {
            cursor[0] += Character.charCount(text.codePointAt(cursor[0]));
            result[++cursor[1]] = scaled(advance[0], size);
        }
        return result;
    }
}
