package net.ptcrys.fpsmatch.editor.core.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/** Test data assembly through public APIs; no fake loaded snapshots or package constructors. */
final class ProjectSessionFixtures {
    static final EditorId OWNER = EditorId.parse("supplier:module");
    static final EditorId TYPE = EditorId.parse("example:note");
    static final EditorId CODEC = EditorId.parse("example:utf8");
    static final String BUILD = "4.2";
    static final DocumentDependencies NO_DEPENDENCIES = new DocumentDependencies(Set.of(), Set.of(), Set.of());

    private ProjectSessionFixtures() { }

    static ExtensionRegistry registry(DocumentType<String> type) {
        return builder(type).freeze();
    }

    static ExtensionRegistryBuilder builder(DocumentType<String> type) {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, BUILD, api, Set.of(), Set.of()));
        if (type != null) {
            builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE),
                    OWNER, Contribution.Side.COMMON, Set.of()), type);
        }
        return builder;
    }

    /** Explicit synthetic raw-byte format support; never a production resource registration. */
    static void syntheticValidator(ExtensionRegistryBuilder builder, EditorId importer, EditorId owner) {
        builder.add(ResourceContentValidators.KIND,
                new Contribution(new ContributionKey(ResourceContentValidators.KIND.id(), importer),
                        owner, Contribution.Side.COMMON, Set.of()), (version, content) -> {
                    content.checkCancelled();
                    if (version.sourceEntry() != null)
                        content.copyContent(version.sourceEntry(), java.io.OutputStream.nullOutputStream());
                    for (UUID output : version.outputEntries())
                        content.copyContent(output, java.io.OutputStream.nullOutputStream());
                    return List.of();
                });
    }

    static SourceDocument source(UUID id, String value) {
        return new SourceDocument(id, OWNER.toString(), TYPE.toString(), CODEC.toString(), 1, "note",
                value.getBytes(StandardCharsets.UTF_8), Set.of(), Set.of());
    }

    static Fixture project(Path directory, ExtensionRegistry registry, List<SourceDocument> sources)
            throws IOException {
        return project(directory, registry, sources, Map.of(OWNER.toString(), BUILD));
    }

    static Fixture project(Path directory, ExtensionRegistry registry, List<SourceDocument> sources,
            Map<String, String> modules) throws IOException {
        LocalProjectStore store = LocalProjectStore.create(directory);
        try {
            Map<UUID, ProjectState.Entry> entries = new TreeMap<>();
            for (SourceDocument source : sources) {
                entries.put(source.id(), new ProjectState.Entry(source.id(), source.owner(), source.type(),
                        source.codec(), source.schema(), source.name(),
                        store.stage(BlobRef.Kind.DOCUMENT, source.bytes()), source.documents(), source.assets()));
            }
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "Test project", modules,
                    entries, Map.of(), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            return new Fixture(store, access, access.read(), registry);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                store.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    record SourceDocument(UUID id, String owner, String type, String codec, int schema, String name,
            byte[] bytes, Set<UUID> documents, Set<UUID> assets) { }

    record Fixture(LocalProjectStore store, LocalProjectAccess access, LoadedProject loaded,
            ExtensionRegistry registry) implements AutoCloseable {
        ProjectSession open() { return ProjectSession.open(access, loaded, registry, 8); }
        @Override public void close() throws IOException { store.close(); }
    }

    static class TestType implements DocumentType<String> {
        int creates;
        int decodes;
        int snapshots;
        int validations;
        int encodings;
        int dependencies;
        int schema = 1;
        Set<Integer> schemas = Set.of(1);
        String initial = "new";
        UnaryOperator<String> decodeTransform = UnaryOperator.identity();
        UnaryOperator<String> snapshotTransform = UnaryOperator.identity();
        Function<String, List<Problem>> validateFunction = value -> List.of();
        Function<String, byte[]> encodeFunction = value -> value.getBytes(StandardCharsets.UTF_8);
        Function<String, DocumentDependencies> dependencyFunction = value -> NO_DEPENDENCIES;

        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return schema; }
        @Override public Set<Integer> readableSchemas() { return schemas; }
        @Override public String create() { creates++; return initial; }
        @Override public String decode(int sourceSchema, byte[] payload) {
            decodes++;
            return decodeTransform.apply(new String(payload, StandardCharsets.UTF_8));
        }
        @Override public String snapshot(String value) { snapshots++; return snapshotTransform.apply(value); }
        @Override public List<Problem> validate(String value) { validations++; return validateFunction.apply(value); }
        @Override public byte[] encode(String value) { encodings++; return encodeFunction.apply(value); }
        @Override public DocumentDependencies dependencies(String value) {
            dependencies++;
            return dependencyFunction.apply(value);
        }
    }
}
