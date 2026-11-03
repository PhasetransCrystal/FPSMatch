package net.ptcrys.fpsmatch.editor.core.session.examples;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContent;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/** Public-JAR example format only. Not a BO weapon producer, game installer or UI. */
public final class ResourceValidationSmoke {
    private static final EditorId OWNER = EditorId.parse("vendor:content_module");
    private static final EditorId RESOURCE_OWNER = EditorId.parse("editor:resources");
    private static final EditorId IMPORTER = EditorId.parse("demo:line_format");
    private static final EditorId TYPE = EditorId.parse("demo:note");
    private static final EditorId RESOURCE_TYPE = EditorId.parse("demo:line_resource");
    private static final TextContent EDIT = new TextContent.Message("demo.resource.edit", List.of());
    private static final Problem WARNING = new Problem(Problem.Severity.WARNING,
            EditorId.parse("demo:old_format"), "demo", new TextContent.Message("demo.old_format", List.of()));

    private ResourceValidationSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("create")) create(Path.of(args[1]), Path.of(args[2]));
        else if (args.length == 3 && args[0].equals("receive")) {
            LocalProjectStore.importSnapshot(Path.of(args[1]), Path.of(args[2]));
            reopen(Path.of(args[2]));
        }
        else if (args.length == 2 && args[0].equals("reopen")) reopen(Path.of(args[1]));
        else throw new IllegalArgumentException("create <new-project> <new-share.zip> | receive <share.zip> <new-project> | reopen <project>");
    }

    private static void create(Path root, Path archive) throws Exception {
        UUID resource = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        UUID firstBytes = UUID.randomUUID();
        UUID nextBytes = UUID.randomUUID();
        UUID outputBytes = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        ExtensionRegistry registry = registry(resource, old, true);
        try (LocalProjectStore store = LocalProjectStore.create(root)) {
            ProjectState.Entry first = asset(store, firstBytes, "DEMO:1\nold content\n");
            ResourceVersion v1 = version(old, resource, "1", firstBytes, Set.of(), Set.of());
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(resource, new LogicalResource(resource, RESOURCE_TYPE, "Example", old)),
                    Map.of(old, v1), Map.of(firstBytes, first));
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "Content validation example",
                    Map.of(OWNER.toString(), "7.0", RESOURCE_OWNER.toString(), "1"), Map.of(),
                    ResourceStorageBridge.stage(store, catalog, Set.of()), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession unvalidated = ProjectSession.open(access, access.read(), registry, 8)) {
                require(unvalidated.protectedMode(), "ordinary reads must not certify resource content");
            }
            var loaded = access.readValidated(registry, () -> false);
            require(loaded.problems().contains(WARNING), "open warning must stay unresolved and intact");
            try (ProjectSession wrong = ProjectSession.open(access, loaded, registry(resource, old, true), 8)) {
                require(wrong.protectedMode(), "another frozen registry must not reuse the validation result");
            }
            // Stage host-selected bytes outside the owner edit; the owner command itself performs no I/O.
            ProjectState.Entry second = asset(store, nextBytes, "DEMO:2\nnew content\n");
            ProjectState.Entry output = asset(store, outputBytes, "NORMALIZED\nnew content\n");
            ResourceVersion v2 = version(next, resource, "2", nextBytes, Set.of(old), Set.of(outputBytes));
            ResourceCatalog candidate = new ResourceCatalog(
                    Map.of(resource, new LogicalResource(resource, RESOURCE_TYPE, "Updated", next)),
                    Map.of(old, v1, next, v2), Map.of(firstBytes, first, nextBytes, second, outputBytes, output));
            try (ProjectSession session = ProjectSession.open(access, loaded, registry, 8)) {
                require(!session.protectedMode(), "validated example must open without errors");
                var handle = session.resources().orElseThrow();
                handle.edit(handle.view().token(), EDIT, value -> candidate);
                handle.undo(handle.view().token());
                require(handle.view().content().resources().get(resource).currentVersion().equals(old), "undo keeps v1");
                handle.redo(handle.view().token());
                var note = session.createWithValue(document, TYPE, String.class, "Locked note", "lock old");
                note.edit(note.view().token(), EDIT, value -> "saved note");
                var capture = session.captureSave(Set.of(document), true);
                boolean blocked = false;
                try { access.prepare(capture); } catch (SessionRejection expected) { blocked = true; }
                require(blocked, "ordinary preparation must not bypass content validation");
                var prepared = access.prepareValidated(capture, () -> false);
                require(prepared.problems().contains(WARNING), "save warning must be retained");
                // A later draft cannot alter the bytes/content selected by capture.
                note.edit(note.view().token(), EDIT, value -> "later draft");
                session.enqueueSave(prepared);
                var job = session.dispatchSave().orElseThrow().job().orElseThrow();
                require(session.completeSave(job, access.publish(job)), "exact fixed save must confirm");
                require(note.view().dirty(), "later edit must remain dirty");
            }
            store.exportSnapshot(archive);
        }
        System.out.println("RESOURCE_CONTENT_CREATE_PASS");
    }

    private static void reopen(Path root) throws Exception {
        try (LocalProjectStore store = LocalProjectStore.open(root)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            var initial = access.read();
            ResourceCatalog catalog = initial.resources().orElseThrow();
            UUID resource = catalog.resources().keySet().iterator().next();
            UUID old = catalog.versions().values().stream().filter(v -> v.importerVersion().equals("1"))
                    .map(ResourceVersion::id).findFirst().orElseThrow();
            ExtensionRegistry registry = registry(resource, old, true);
            try (ProjectSession session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                require(!session.protectedMode(), "fresh process must validate received byte sources");
                require(catalog.versions().size() == 2 && !catalog.resources().get(resource).currentVersion().equals(old),
                        "share must preserve old version and current v2");
                var note = session.documents().values().iterator().next();
                require(note.view().content().equals("saved note"), "fresh reopen must recover captured note");
                require(!note.view().dirty(), "reopened note must be clean");
            }
            var missing = registry(resource, old, false);
            var unaccepted = access.readValidated(missing, () -> false);
            try (ProjectSession session = ProjectSession.open(access, unaccepted, missing, 8)) {
                require(session.protectedMode(), "declaration alone must not certify an independent share");
                require(session.source().saved().state().assets().equals(initial.saved().state().assets()),
                        "missing validator must preserve all original entries");
            }
        }
        System.out.println("RESOURCE_CONTENT_REOPEN_PASS");
    }

    private static ResourceVersion version(UUID id, UUID resource, String format, UUID source,
            Set<UUID> dependencies, Set<UUID> outputs) {
        return new ResourceVersion(id, resource, IMPORTER, format, Map.of("format", "demo-lines"), source, dependencies, outputs);
    }

    private static ProjectState.Entry asset(LocalProjectStore store, UUID id, String value) throws Exception {
        return new ProjectState.Entry(id, OWNER.toString(), "demo:bytes", "demo:bytes", 1, "Example bytes",
                store.stage(BlobRef.Kind.ASSET, value.getBytes(StandardCharsets.UTF_8)), Set.of(), Set.of());
    }

    private static ExtensionRegistry registry(UUID resource, UUID old, boolean validator) {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, "7.0", api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(RESOURCE_OWNER, "1", api, Set.of(), Set.of()));
        builder.add(ResourceProducers.KIND, contribution(ResourceProducers.KIND.id(), IMPORTER),
                new ResourceProducerSpec(Set.of("1", "2")));
        if (validator) builder.add(ResourceContentValidators.KIND, contribution(ResourceContentValidators.KIND.id(), IMPORTER),
                (version, content) -> validate(version, content, old));
        builder.add(DocumentTypes.KIND, contribution(DocumentTypes.KIND.id(), TYPE), new Note(resource, old));
        return builder.freeze();
    }

    private static Contribution contribution(EditorId kind, EditorId id) {
        return new Contribution(new ContributionKey(kind, id), OWNER, Contribution.Side.COMMON, Set.of());
    }

    private static List<Problem> validate(ResourceVersion version, ResourceContent content, UUID old) throws java.io.IOException {
        require(content.resourceTypes().get(version.resourceId()).equals(RESOURCE_TYPE), "resource type is part of the format");
        require(version.settings().equals(Map.of("format", "demo-lines")), "settings must be fully interpreted");
        String source = text(content, version.sourceEntry());
        require(source.startsWith("DEMO:" + version.importerVersion() + "\n"), "source must match declared format version");
        if (version.importerVersion().equals("1")) {
            require(version.dependencyVersions().isEmpty() && version.outputEntries().isEmpty(), "v1 has no dependencies or output");
            return List.of(WARNING);
        }
        require(version.dependencyVersions().equals(Set.of(old)) && content.versions().containsKey(old), "v2 pins v1");
        require(version.outputEntries().size() == 1, "v2 requires one normalized output");
        require(text(content, version.outputEntries().iterator().next()).equals("NORMALIZED\n" + source.substring(7)),
                "required output must match source semantics");
        return List.of();
    }

    private static String text(ResourceContent content, UUID id) throws java.io.IOException {
        require(content.byteEntries().get(id).content().size() <= 4096, "demo parser has an explicit input budget");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        content.copyContent(id, bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Note implements DocumentType<String> {
        private final UUID resource;
        private final UUID version;
        Note(UUID resource, UUID version) { this.resource = resource; this.version = version; }
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return EditorId.parse("demo:note_utf8"); }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String create() { return "new"; }
        @Override public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public List<Problem> validate(String value) { return List.of(); }
        @Override public DocumentDependencies dependencies(String value) {
            return new DocumentDependencies(Set.of(), Set.of(new ResourceRef(resource, version)), Set.of());
        }
    }
}
