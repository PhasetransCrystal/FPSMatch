package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import java.util.*;
import java.util.function.Predicate;

/** Session-only central views. Closing a view never removes its shared business document. */
public final class StudioDocumentTabs {

    public enum Kind {
        SCENE,
        FORM,
        GRID,
        RESOURCES
    }

    public record Key(Kind kind, UUID document, String target) {

        public Key {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(target);
            if (kind == Kind.RESOURCES) {
                if (document != null || !target.isEmpty()) throw new IllegalArgumentException("resource view has no document owner");
            } else Objects.requireNonNull(document);
            if ((kind == Kind.FORM || kind == Kind.GRID) && target.isEmpty()) throw new IllegalArgumentException("view target is required");
            if (kind == Kind.SCENE && !target.isEmpty()) throw new IllegalArgumentException("scene view has no target");
        }

        public static Key scene(UUID document) {
            return new Key(Kind.SCENE, document, "");
        }

        public static Key form(UUID document, String form) {
            return new Key(Kind.FORM, document, form);
        }

        public static Key grid(UUID document, String owner) {
            return new Key(Kind.GRID, document, owner);
        }

        public static Key resources() {
            return new Key(Kind.RESOURCES, null, "");
        }

        public String controlId() {
            return switch (kind) {
                case SCENE -> "document/" + document;
                case FORM -> "document/form/" + document + "/" + target.length() + ":" + target;
                case GRID -> "document/grid/" + document + "/" + target.length() + ":" + target;
                case RESOURCES -> "document/resources";
            };
        }
    }

    /** An old save continuation cannot close a later reopening of the same stable view key. */
    public record CloseRequest(Key key, long incarnation) {}

    private final List<Key> order = new ArrayList<>();
    private final Map<Key, Long> incarnations = new HashMap<>();
    private long nextIncarnation;
    private Key active, dragSource;
    private double dragStart;
    private boolean dragMoved;

    public List<Key> keys() {
        return List.copyOf(order);
    }

    public Optional<Key> active() {
        return Optional.ofNullable(active);
    }

    public Optional<UUID> activeOwner() {
        return active().map(Key::document);
    }

    public boolean contains(Key key) {
        return incarnations.containsKey(key);
    }

    public void open(Key key) {
        Objects.requireNonNull(key);
        if (!contains(key)) {
            order.add(key);
            incarnations.put(key, ++nextIncarnation);
        }
        active = key;
    }

    public CloseRequest requestClose(Key key) {
        Long incarnation = incarnations.get(key);
        if (incarnation == null) throw new IllegalArgumentException("view is not open");
        return new CloseRequest(key, incarnation);
    }

    public boolean matches(CloseRequest request) {
        return request != null && Objects.equals(incarnations.get(request.key()), request.incarnation());
    }

    public boolean close(CloseRequest request) {
        if (!matches(request)) return false;
        Key key = request.key();
        int at = order.indexOf(key);
        order.remove(at);
        incarnations.remove(key);
        if (Objects.equals(active, key)) active = order.isEmpty() ? null : order.get(Math.max(0, at - 1));
        if (Objects.equals(dragSource, key)) cancelDrag();
        return true;
    }

    public void retain(Predicate<Key> available) {
        for (var key : keys()) if (!available.test(key)) close(requestClose(key));
    }

    public void clear() {
        order.clear();
        incarnations.clear();
        active = null;
        cancelDrag();
    }

    /** Matches the approved strip: moving right inserts after the target, moving left before it. */
    public boolean move(Key source, Key target) {
        int from = order.indexOf(source), to = order.indexOf(target);
        if (from < 0 || to < 0 || from == to) return false;
        order.remove(from);
        order.add(to, source);
        return true;
    }

    public void beginDrag(Key source, double x) {
        cancelDrag();
        if (contains(source)) {
            dragSource = source;
            dragStart = x;
        }
    }

    public boolean dragging() {
        return dragSource != null;
    }

    public Optional<Key> dragSource() {
        return Optional.ofNullable(dragSource);
    }

    public boolean movedDuringDrag() {
        return dragMoved;
    }

    public void drag(double x) {
        if (dragging() && Math.abs(x - dragStart) > 8) dragMoved = true;
    }

    /** Returns a normal click only when release is still over the pressed activation control. */
    public Optional<Key> endDrag(Key target, double x) {
        drag(x);
        Key source = dragSource;
        boolean moved = dragMoved;
        cancelDrag();
        if (source == null || !contains(source)) return Optional.empty();
        if (moved) {
            if (target != null) move(source, target);
            return Optional.empty();
        }
        return source.equals(target) ? Optional.of(source) : Optional.empty();
    }

    public void cancelDrag() {
        dragSource = null;
        dragMoved = false;
    }
}
