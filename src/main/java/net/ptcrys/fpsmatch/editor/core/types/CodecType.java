package net.ptcrys.fpsmatch.editor.core.types;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentModel;

import java.util.Set;

/** Pure persistent content shared by registered document and embedded value codecs. */
public interface CodecType<T> extends DocumentModel<T> {

    Class<T> valueType();

    EditorId codecId();

    int schemaVersion();

    Set<Integer> readableSchemas();

    /** Reads a supported schema without modifying the input or publishing migrated content. */
    T decode(int sourceSchema, byte[] encoding);

    /** Reports every direct document, locked resource and embedded contribution dependency. */
    DocumentDependencies dependencies(T snapshot);
}
