package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Private-to-runtime capture: staging reads frozen data; confirmation belongs to the owner. */
abstract class DocumentSaveCapture {

    final DocumentToken token;
    final DocumentDependencies dependencies;
    private final EditorId type;
    private final EditorId owner;
    private final EditorId codec;
    private final int schema;
    private final String name;

    DocumentSaveCapture(DocumentToken token, DocumentDependencies dependencies, EditorId type,
                        EditorId owner, EditorId codec, int schema, String name) {
        this.token = token;
        this.dependencies = dependencies;
        this.type = type;
        this.owner = owner;
        this.codec = codec;
        this.schema = schema;
        this.name = name;
    }

    final ProjectState.Entry stage(LocalProjectStore store) throws IOException {
        Set<UUID> assets = new TreeSet<>();
        dependencies.resources().forEach(ref -> {
            assets.add(ref.resourceId());
            assets.add(ref.versionId());
        });
        return new ProjectState.Entry(token.documentId(), owner.toString(), type.toString(),
                codec.toString(), schema, name, store.stage(BlobRef.Kind.DOCUMENT, encoding()),
                dependencies.documents(), assets);
    }

    abstract boolean canEnqueue();

    abstract byte[] encoding();

    abstract void queued();

    abstract void confirm();
}
