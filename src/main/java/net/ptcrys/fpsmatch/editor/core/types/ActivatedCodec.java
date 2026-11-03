package net.ptcrys.fpsmatch.editor.core.types;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentModel;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** One codec implementation with metadata captured once and shared class/boundary checks. */
public final class ActivatedCodec<T> implements DocumentModel<T> {

    private final EditorId typeId;
    private final CodecType<T> implementation;
    private final Class<T> valueType;
    private final EditorId codecId;
    private final int schemaVersion;
    private final Set<Integer> readableSchemas;

    public ActivatedCodec(EditorId typeId, CodecType<T> implementation) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        Class<T> declaredType = implementation.valueType();
        EditorId declaredCodec = implementation.codecId();
        int declaredSchema = implementation.schemaVersion();
        Set<Integer> declaredReadables = implementation.readableSchemas();
        if (declaredType == null || declaredType.isPrimitive() || declaredCodec == null || declaredSchema < 1 || declaredReadables == null || declaredReadables.isEmpty() || declaredReadables.size() > 256) {
            throw new IllegalArgumentException("invalid codec descriptor for " + typeId);
        }
        TreeSet<Integer> copied = new TreeSet<>();
        for (Integer schema : declaredReadables) {
            if (schema == null || schema < 1) {
                throw new IllegalArgumentException("invalid readable codec schema for " + typeId);
            }
            copied.add(schema);
        }
        if (!copied.contains(declaredSchema)) {
            throw new IllegalArgumentException("current codec schema is not readable for " + typeId);
        }
        valueType = declaredType;
        codecId = declaredCodec;
        schemaVersion = declaredSchema;
        readableSchemas = Collections.unmodifiableSet(copied);
    }

    public Class<T> valueType() {
        return valueType;
    }

    public EditorId codecId() {
        return codecId;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Set<Integer> readableSchemas() {
        return readableSchemas;
    }

    /** Checks format identities before passing an isolated input to trusted extension code. */
    public T decode(EditorId sourceCodec, int sourceSchema, byte[] encoding) {
        Objects.requireNonNull(sourceCodec, "sourceCodec");
        Objects.requireNonNull(encoding, "encoding");
        if (!codecId.equals(sourceCodec)) {
            throw CodecMessages.unsupportedCodec(typeId, sourceCodec, codecId);
        }
        if (!readableSchemas.contains(sourceSchema)) {
            throw CodecMessages.unsupportedSchema(typeId, sourceSchema);
        }
        return checked(implementation.decode(sourceSchema, encoding.clone()));
    }

    @Override
    public T snapshot(T value) {
        return checked(implementation.snapshot(checked(value)));
    }

    /** The consumer must copy this buffer before another extension callback. */
    @Override
    public byte[] encode(T value) {
        return Objects.requireNonNull(implementation.encode(checked(value)), "encoded content");
    }

    @Override
    public List<Problem> validate(T value) {
        return List.copyOf(implementation.validate(checked(value)));
    }

    public DocumentDependencies dependencies(T value) {
        return Objects.requireNonNull(implementation.dependencies(checked(value)), "dependencies");
    }

    private T checked(Object value) {
        return valueType.cast(Objects.requireNonNull(value, "codec value"));
    }
}
