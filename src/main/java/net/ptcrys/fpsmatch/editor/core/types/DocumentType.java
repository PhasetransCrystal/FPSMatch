package net.ptcrys.fpsmatch.editor.core.types;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentModel;

import java.util.Objects;
import java.util.Set;

/**
 * One document type: the model contract of a document kind plus the codec and schema identities the
 * editor needs to create, read and reopen it.
 *
 * <p>
 * A document type is published as a real implementation under the {@link DocumentTypes#KIND}
 * contribution; its document type id and owner are the contribution metadata. An implementation
 * therefore carries no second, possibly contradictory copy of that id, and one implementation may
 * serve exactly the contribution it was published under.
 * </p>
 *
 * <p>
 * {@link #codecId()} names the <em>current</em> codec and {@link #schemaVersion()} the content
 * format version that codec writes. A module's {@code buildVersion} or the registry API version
 * never substitute for a schema version: they version code, not bytes on disk.
 * </p>
 *
 * <p>
 * Implementations and their metadata are a trusted Java contract, not a sandbox. {@code T} must
 * be a non-null reference type, and {@link #create()}, {@link #decode(int, byte[])} and
 * {@link DocumentModel#snapshot(Object)} must return non-null values of {@code valueType()}. The
 * metadata of one implementation must stay stable for its registration lifetime; a consuming
 * service captures {@code codecId}, {@code schemaVersion}, {@code readableSchemas} and
 * {@code valueType} once when it activates, instead of re-reading a mutable description on every
 * save.
 * </p>
 *
 * @param <T> the document value type
 */
public interface DocumentType<T> extends CodecType<T> {

    /**
     * Resolves this prototype against the actual final registry before data callbacks run.
     * A context-dependent type returns a fresh immutable implementation with identical codec
     * metadata. It performs no I/O and never mutates the prototype or another registry's binding.
     * Hosts consume the activated type rather than calling an unresolved prototype's create/decode.
     */
    default DocumentType<T> bind(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return this;
    }

    /** Returns the runtime class of {@link #create()}, {@link #decode(int, byte[])} and snapshots. */
    Class<T> valueType();

    /** Returns the identity of the current codec that writes {@link #schemaVersion()}. */
    EditorId codecId();

    /** Returns the content format version this codec currently writes; always at least 1. */
    int schemaVersion();

    /**
     * Returns every schema this codec can read, always including {@link #schemaVersion()}.
     *
     * <p>
     * The set is non-empty, holds at most 256 positive versions, and lists only formats this
     * implementation really understands. A version outside it is not "probably the current one":
     * {@link #decode(int, byte[])} rejects it instead of guessing.
     * </p>
     */
    Set<Integer> readableSchemas();

    /** Creates a new, valid empty document value; a pure content factory with no I/O. */
    T create();

    /**
     * Decodes {@code encoding} of {@code sourceSchema} into an in-memory value.
     *
     * <p>
     * Only schemas in {@link #readableSchemas()} are accepted; anything else, and any damaged
     * input, is reported as a {@link DocumentCodecException} with translatable problems. A rejected
     * input never silently becomes {@link #create()}'s default content. Migration of an older but
     * readable schema happens in memory only: this method writes no file, stages nothing, confirms no
     * save and must not modify {@code encoding} - the caller keeps the source schema and the original
     * bytes, which may only be replaced by an explicit save.
     * </p>
     *
     * <p>
     * The caller establishes a session by snapshotting and validating the returned value first.
     * Problems from that validation keep their own diagnostics; other exceptions from an
     * implementation are failures of that implementation and are not swallowed here.
     * </p>
     *
     * @param sourceSchema the content format version {@code encoding} was written with
     * @param encoding     the opaque encoded content, read but never written
     */
    T decode(int sourceSchema, byte[] encoding);

    /**
     * Collects the complete set of direct dependencies of {@code snapshot}.
     *
     * <p>
     * Input is an already isolated snapshot; this method performs no I/O, scans no project and
     * reads neither a UI selection nor a "current" resource pointer. A composite codec that embeds
     * third-party objects is responsible for walking its nested values and merging every direct
     * dependency. Ordinary document references may form cycles. The document type's own contribution
     * is counted by the consumer and does not have to be listed again, but a dependency set must not
     * consist of only the top-level document owner: embedded contributions and locked resource
     * versions belong here too.
     * </p>
     *
     * <p>
     * An incomplete report is a defect of this implementation, not something the platform can
     * repair: it cannot parse arbitrary opaque payload bytes to find references the type forgot.
     * </p>
     */
    DocumentDependencies dependencies(T snapshot);
}
