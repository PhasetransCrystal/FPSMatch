package net.ptcrys.fpsmatch.editor.core.types.values;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.types.ActivatedCodec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** A catalog-issued codec with frozen metadata and a private snapshot ownership identity. */
public final class ActivatedValueType<T> {

    private final EditorId id;
    private final EditorId owner;
    private final ActivatedCodec<T> codec;
    private final ContributionKey contribution;

    ActivatedValueType(EditorId id, EditorId owner, ValueType<T> implementation) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.codec = new ActivatedCodec<>(id, implementation);
        this.contribution = new ContributionKey(ValueTypes.KIND.id(), id);
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

    /**
     * Snapshots, validates and fixes the current encoding before collecting complete dependencies.
     * A failure issues no snapshot; warnings and information retain their original order.
     */
    public ValueSnapshot<T> capture(T value) {
        T content = codec.snapshot(value);
        List<Problem> problems = codec.validate(content);
        for (Problem problem : problems) {
            if (problem.severity() == Problem.Severity.ERROR) {
                throw new DocumentCodecException(problems);
            }
        }
        byte[] encoding = codec.encode(content);
        requireByteBudget(encoding.length);
        // Fix the bytes before dependencies() can reuse or mutate the implementation's buffer.
        byte[] retainedEncoding = encoding.clone();
        DocumentDependencies dependencies = codec.dependencies(content);
        long total = (long) dependencies.documents().size() + dependencies.resources().size() + dependencies.contributions().size() + (dependencies.contributions().contains(contribution) ? 0L : 1L);
        if (total > ValueTypeCatalog.MAX_DEPENDENCIES) {
            throw ValueMessages.valueDependencies(id, total, ValueTypeCatalog.MAX_DEPENDENCIES);
        }
        Set<ContributionKey> contributions = new TreeSet<>(dependencies.contributions());
        contributions.add(contribution);
        DocumentDependencies complete = new DocumentDependencies(dependencies.documents(),
                dependencies.resources(), contributions);
        return new ValueSnapshot<>(this, content, retainedEncoding, complete, problems);
    }

    /** Rejects oversized input before cloning or invoking any implementation callback. */
    public ValueSnapshot<T> decode(EditorId sourceCodec, int sourceSchema, byte[] encoding) {
        Objects.requireNonNull(encoding, "encoding");
        requireByteBudget(encoding.length);
        return capture(codec.decode(sourceCodec, sourceSchema, encoding));
    }

    /** Ownership is instance identity, including across catalogs activated from the same registry. */
    public boolean owns(ValueSnapshot<?> snapshot) {
        return Objects.requireNonNull(snapshot, "snapshot").issuedBy(this);
    }

    private void requireByteBudget(long actual) {
        if (actual > ValueTypeCatalog.MAX_VALUE_BYTES) {
            throw ValueMessages.valueBytes(id, actual, ValueTypeCatalog.MAX_VALUE_BYTES);
        }
    }
}
