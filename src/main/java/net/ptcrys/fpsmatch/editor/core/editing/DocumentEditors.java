package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.session.EditorResourceContext;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Document presentation uses the document type's exact id, owner and class. */
public final class DocumentEditors {

    public static final ExtensionKind<DocumentEditor<?>> KIND = kind();
    public static final ExtensionKind<DocumentImporter<?>> IMPORTERS = importerKind();
    public static final ExtensionKind<ObjectCreator<?>> CREATORS = creatorKind();

    private DocumentEditors() {}

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ExtensionKind<DocumentEditor<?>> kind() {
        return (ExtensionKind) new ExtensionKind<>(EditorId.parse("editor:document_editor"), DocumentEditor.class);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ExtensionKind<DocumentImporter<?>> importerKind() {
        return (ExtensionKind) new ExtensionKind<>(EditorId.parse("editor:document_importer"), DocumentImporter.class);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ExtensionKind<ObjectCreator<?>> creatorKind() {
        return (ExtensionKind) new ExtensionKind<>(EditorId.parse("editor:object_creator"), ObjectCreator.class);
    }

    public static void registerCreator(ExtensionRegistryBuilder builder, EditorId id, EditorId owner, ObjectCreator<?> creator) {
        builder.define(CREATORS);
        builder.add(CREATORS, metadata(CREATORS.id(), id, owner, creator.documentType()), creator);
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectCreator<T> creator(ExtensionRegistry registry, EditorId id, EditorId type, Class<T> clazz) {
        ObjectCreator<?> creator = registry.find(CREATORS, id).orElseThrow();
        requireOwner(registry, CREATORS.id(), id, type);
        if (!creator.documentType().equals(type) || creator.valueType() != clazz) throw new IllegalArgumentException("creator document differs");
        ObjectCreator<?> bound = Objects.requireNonNull(creator.bind(registry));
        if (!bound.documentType().equals(type) || bound.valueType() != clazz) throw new IllegalArgumentException("bound creator document differs");
        return (ObjectCreator<T>) bound;
    }

    public static void register(ExtensionRegistryBuilder builder, EditorId type, EditorId owner, DocumentEditor<?> editor) {
        builder.define(KIND);
        builder.add(KIND, metadata(KIND.id(), type, owner, type), editor);
    }

    public static void registerImporter(ExtensionRegistryBuilder builder, EditorId id, EditorId owner, DocumentImporter<?> importer) {
        builder.define(IMPORTERS);
        builder.add(IMPORTERS, metadata(IMPORTERS.id(), id, owner, importer.documentType()), importer);
    }

    private static Contribution metadata(EditorId kind, EditorId id, EditorId owner, EditorId type) {
        return new Contribution(new ContributionKey(kind, id), owner, Contribution.Side.COMMON,
                Set.of(new ContributionKey(DocumentTypes.KIND.id(), type)));
    }

    public static <T> Optional<DocumentEditor<T>> find(ExtensionRegistry registry, EditorId type, Class<T> clazz) {
        if (!registry.defines(KIND)) return Optional.empty();
        return registry.find(KIND, type).map(editor -> bind(registry, type, clazz, editor, null));
    }

    public static <T> Optional<DocumentEditor<T>> find(ExtensionRegistry registry, EditorId type,
                                                       Class<T> clazz, EditorResourceContext resources) {
        Objects.requireNonNull(resources).requireRegistry(registry);
        if (!registry.defines(KIND)) return Optional.empty();
        return registry.find(KIND, type).map(editor -> bind(registry, type, clazz, editor, resources));
    }

    @SuppressWarnings("unchecked")
    private static <T> DocumentEditor<T> bind(ExtensionRegistry registry, EditorId type, Class<T> clazz,
                                              DocumentEditor<?> editor, EditorResourceContext resources) {
        requireOwner(registry, KIND.id(), type, type);
        if (editor.valueType() != clazz) throw new IllegalArgumentException("document editor class differs");
        DocumentEditor<?> bound = Objects.requireNonNull(resources == null ? editor.bind(registry) : editor.bind(registry, resources));
        if (bound.valueType() != clazz) throw new IllegalArgumentException("bound document editor class differs");
        return (DocumentEditor<T>) bound;
    }

    public static void requireImporter(ExtensionRegistry registry, EditorId id, DocumentImporter<?> importer) {
        requireOwner(registry, IMPORTERS.id(), id, importer.documentType());
        var type = registry.find(DocumentTypes.KIND, importer.documentType()).orElseThrow();
        if (type.valueType() != importer.valueType()) throw new IllegalArgumentException("importer class differs");
    }

    private static void requireOwner(ExtensionRegistry registry, EditorId kind, EditorId id, EditorId type) {
        var metadata = registry.metadata().contributions();
        var presentation = Objects.requireNonNull(metadata.get(new ContributionKey(kind, id)));
        var document = Objects.requireNonNull(metadata.get(new ContributionKey(DocumentTypes.KIND.id(), type)));
        if (presentation.side() != Contribution.Side.COMMON || !presentation.owner().equals(document.owner()) || !presentation.requiredContributions().contains(document.key()))
            throw new IllegalArgumentException("document presentation registration differs");
    }
}
