package net.ptcrys.fpsmatch.editor.core.types.values;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import java.util.List;
import java.util.Objects;

/**
 * An immutable captured value and its current-schema encoding, issued only by an activated codec.
 * The codec guarantees deep immutability of content; encoding access always returns a copy.
 */
public final class ValueSnapshot<T> {

    private final ActivatedValueType<T> issuer;
    private final EditorId typeId;
    private final EditorId owner;
    private final EditorId codecId;
    private final int schemaVersion;
    private final T content;
    private final byte[] encoding;
    private final DocumentDependencies dependencies;
    private final List<Problem> problems;

    ValueSnapshot(ActivatedValueType<T> issuer, T content, byte[] encoding,
                  DocumentDependencies dependencies, List<Problem> problems) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.typeId = issuer.id();
        this.owner = issuer.owner();
        this.codecId = issuer.codecId();
        this.schemaVersion = issuer.schemaVersion();
        this.content = Objects.requireNonNull(content, "content");
        // The sole package caller transfers its private, already isolated capture buffer.
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.problems = List.copyOf(problems);
    }

    public EditorId typeId() {
        return typeId;
    }

    public EditorId owner() {
        return owner;
    }

    public EditorId codecId() {
        return codecId;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public T content() {
        return content;
    }

    /** Returns the retained byte count without copying or exposing the private encoding. */
    public int encodingSize() {
        return encoding.length;
    }

    public byte[] encoding() {
        return encoding.clone();
    }

    public DocumentDependencies dependencies() {
        return dependencies;
    }

    public List<Problem> problems() {
        return problems;
    }

    boolean issuedBy(ActivatedValueType<?> candidate) {
        return issuer == candidate;
    }
}
