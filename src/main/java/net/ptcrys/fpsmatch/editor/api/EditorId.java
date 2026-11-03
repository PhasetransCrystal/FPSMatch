package net.ptcrys.fpsmatch.editor.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** A case-sensitive protocol identity. Display text and file paths are separate. */
public record EditorId(String namespace, String path) implements Comparable<EditorId> {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]{1,256}");

    public EditorId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid editor identity");
        }
    }

    public static EditorId parse(String value) {
        Objects.requireNonNull(value, "value");
        int colon = value.indexOf(':');
        if (colon < 1 || colon != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("expected namespace:path");
        }
        return new EditorId(value.substring(0, colon), value.substring(colon + 1));
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }

    @Override
    public int compareTo(EditorId other) {
        return toString().compareTo(other.toString());
    }
}
