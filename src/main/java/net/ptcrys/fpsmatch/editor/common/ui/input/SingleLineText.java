package net.ptcrys.fpsmatch.editor.common.ui.input;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Owner-thread, host-independent single-line editing state. Positions are UTF-16 offsets. */
public final class SingleLineText {

    public static final int MAX_LENGTH = 4096;
    private static final int HISTORY_LIMIT = 100;
    private String text;
    private int caret;
    private int anchor;
    private long revision;
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();

    public SingleLineText(String text) {
        this.text = requiredClean(text);
        caret = anchor = this.text.length();
    }

    public String text() {
        return text;
    }

    public int caret() {
        return caret;
    }

    public int anchor() {
        return anchor;
    }

    public long revision() {
        return revision;
    }

    public int start() {
        return Math.min(caret, anchor);
    }

    public int end() {
        return Math.max(caret, anchor);
    }

    public record Snapshot(String text, int caret, int anchor, long revision) {

        public Snapshot {
            Objects.requireNonNull(text);
        }

        public int start() {
            return Math.min(caret, anchor);
        }

        public int end() {
            return Math.max(caret, anchor);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(text, caret, anchor, revision);
    }

    /** A detached copy for publication preparation; no live history is changed. */
    public SingleLineText copy() {
        var copy = new SingleLineText(text);
        copy.caret = caret;
        copy.anchor = anchor;
        copy.revision = revision;
        copy.undo.addAll(undo);
        copy.redo.addAll(redo);
        return copy;
    }

    /** External content must be representable without sanitizing or truncating the original. */
    public static boolean supportsExternal(String source) {
        Objects.requireNonNull(source);
        return source.length() <= MAX_LENGTH && source.equals(clean(source, MAX_LENGTH));
    }

    /** Synchronizes external content; a changed value starts a new local undo history. */
    public void set(String text) {
        String next = requiredClean(text);
        if (this.text.equals(next)) return;
        this.text = next;
        caret = boundary(caret);
        anchor = boundary(anchor);
        undo.clear();
        redo.clear();
        revision++;
    }

    /** Invalid offsets clamp to the preceding code point boundary. */
    public void select(int caret, boolean extend) {
        int oldCaret = this.caret, oldAnchor = anchor;
        this.caret = boundary(caret);
        if (!extend) anchor = this.caret;
        if (oldCaret != this.caret || oldAnchor != anchor) revision++;
    }

    public void selectAll() {
        if (anchor != 0 || caret != text.length()) revision++;
        anchor = 0;
        caret = text.length();
    }

    public boolean insert(String text) {
        String inserted = clean(text, MAX_LENGTH - (this.text.length() - (end() - start())));
        // Empty or wholly rejected clipboard input must not delete the selection.
        if (inserted == null || inserted.isEmpty()) return false;
        return replace(start(), end(), inserted);
    }

    public boolean backspace() {
        if (start() != end()) return replace(start(), end(), "");
        if (caret == 0) return false;
        return replace(previous(caret), caret, "");
    }

    public boolean deleteForward() {
        if (start() != end()) return replace(start(), end(), "");
        if (caret == text.length()) return false;
        return replace(caret, next(caret), "");
    }

    /** Direction's sign selects left or right; zero leaves the selection untouched. */
    public void move(int direction, boolean extend, boolean byWord) {
        if (direction == 0) return;
        if (!extend && start() != end()) {
            select(direction < 0 ? start() : end(), false);
            return;
        }
        int destination = caret;
        if (byWord) destination = wordBoundary(destination, direction);
        else if (direction < 0 && destination > 0) destination = previous(destination);
        else if (direction > 0 && destination < text.length()) destination = next(destination);
        select(destination, extend);
    }

    public void home(boolean extend) {
        select(0, extend);
    }

    public void end(boolean extend) {
        select(text.length(), extend);
    }

    public String selected() {
        return text.substring(start(), end());
    }

    public boolean undo() {
        return restore(undo, redo);
    }

    public boolean redo() {
        return restore(redo, undo);
    }

    private boolean replace(int start, int end, String inserted) {
        String next = text.substring(0, start) + inserted + text.substring(end);
        if (text.equals(next)) {
            if (caret != start + inserted.length() || anchor != start + inserted.length()) revision++;
            caret = anchor = start + inserted.length();
            return false;
        }
        push(undo, state());
        redo.clear();
        text = next;
        caret = anchor = start + inserted.length();
        revision++;
        return true;
    }

    private boolean restore(Deque<State> from, Deque<State> to) {
        if (from.isEmpty()) return false;
        push(to, state());
        State restored = from.removeLast();
        text = restored.text;
        caret = restored.caret;
        anchor = restored.anchor;
        revision++;
        return true;
    }

    private State state() {
        return new State(text, caret, anchor);
    }

    private static void push(Deque<State> history, State state) {
        if (history.size() == HISTORY_LIMIT) history.removeFirst();
        history.addLast(state);
    }

    private int boundary(int position) {
        int result = Math.max(0, Math.min(position, text.length()));
        if (result > 0 && result < text.length() && Character.isHighSurrogate(text.charAt(result - 1)) && Character.isLowSurrogate(text.charAt(result))) result--;
        return result;
    }

    private int previous(int position) {
        return text.offsetByCodePoints(position, -1);
    }

    private int next(int position) {
        return text.offsetByCodePoints(position, 1);
    }

    private int wordBoundary(int position, int direction) {
        if (direction < 0) {
            while (position > 0 && wordClass(text.codePointBefore(position)) == 0) position = previous(position);
            if (position == 0) return 0;
            int kind = wordClass(text.codePointBefore(position));
            while (position > 0 && wordClass(text.codePointBefore(position)) == kind) position = previous(position);
        } else {
            if (position == text.length()) return position;
            int kind = wordClass(text.codePointAt(position));
            while (position < text.length() && wordClass(text.codePointAt(position)) == kind) position = next(position);
            while (position < text.length() && wordClass(text.codePointAt(position)) == 0) position = next(position);
        }
        return position;
    }

    private static int wordClass(int codePoint) {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return 0;
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK ? 1 : 2;
    }

    private static String clean(String source, int budget) {
        Objects.requireNonNull(source, "text");
        StringBuilder result = new StringBuilder(Math.min(source.length(), budget));
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            int size = Character.charCount(codePoint);
            index += size;
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE) continue;
            if (result.length() + size > budget) return null;
            result.appendCodePoint(codePoint);
        }
        return result.toString();
    }

    private static String requiredClean(String source) {
        String result = clean(source, MAX_LENGTH);
        if (result == null) throw new IllegalArgumentException("single-line text exceeds " + MAX_LENGTH + " UTF-16 units");
        return result;
    }

    private record State(String text, int caret, int anchor) {}
}
