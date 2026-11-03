package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentModel;
import net.ptcrys.fpsmatch.editor.core.types.ActivatedCodec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A real registered document implementation with a frozen descriptor. */
public final class ActivatedDocumentType<T> implements DocumentModel<T> {

    private final EditorId id;
    private final EditorId owner;
    private final DocumentType<T> implementation;
    private final ActivatedCodec<T> codec;

    ActivatedDocumentType(EditorId id, EditorId owner, DocumentType<T> implementation,
                          ActivatedCodec<T> codec) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public EditorId id() {
        return id;
    }

    public EditorId owner() {
        return owner;
    }

    public Class<T> valueType() {
        return codec.valueType();
    }

    public EditorId codecId() {
        return codec.codecId();
    }

    public int schemaVersion() {
        return codec.schemaVersion();
    }

    public Set<Integer> readableSchemas() {
        return codec.readableSchemas();
    }

    public T create() {
        return valueType().cast(Objects.requireNonNull(implementation.create(), "document value"));
    }

    /** Retains the runtime diagnostics, then delegates the single isolated input copy. */
    public T decode(EditorId sourceCodec, int sourceSchema, byte[] payload) {
        Objects.requireNonNull(sourceCodec, "sourceCodec");
        Objects.requireNonNull(payload, "payload");
        if (!codecId().equals(sourceCodec)) {
            throw SessionMessages.unsupportedCodec(id, sourceCodec, codecId());
        }
        if (!readableSchemas().contains(sourceSchema)) {
            throw SessionMessages.unsupportedSchema(id, sourceSchema);
        }
        return codec.decode(sourceCodec, sourceSchema, payload);
    }

    @Override
    public T snapshot(T value) {
        return codec.snapshot(value);
    }

    /** DocumentSession owns the encoding copy at its capture boundary. */
    @Override
    public byte[] encode(T value) {
        return codec.encode(value);
    }

    @Override
    public List<Problem> validate(T value) {
        return codec.validate(value);
    }

    public DocumentDependencies dependencies(T snapshot) {
        return codec.dependencies(snapshot);
    }
}
