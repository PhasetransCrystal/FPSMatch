package net.ptcrys.fpsmatch.editor.core.session.examples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
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
import net.ptcrys.fpsmatch.editor.core.session.LoadedProject;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectSnapshot;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/**
 * Standalone public-JAR consumer of a fixed saved input. Run create and receive in separate JVMs;
 * receive needs only the archive and a new destination. All I/O runs on this example's caller.
 * Synthetic third-party format only: resource validation does not grant document installation,
 * production-module availability, world permissions, BO compatibility or any installation gate.
 */
public final class FixedSnapshotReadSmoke {
    private static final EditorId MODULE = EditorId.parse("vendor:fixed_snapshot_demo");
    private static final EditorId RESOURCE_MODULE = EditorId.parse("editor:resources");
    private static final EditorId TYPE = EditorId.parse("snapshotdemo:note");
    private static final EditorId CODEC = EditorId.parse("snapshotdemo:note_utf8");
    private static final EditorId IMPORTER = EditorId.parse("snapshotdemo:source");
    private static final EditorId RESOURCE_TYPE = EditorId.parse("snapshotdemo:resource");
    private static final UUID PROJECT = id(1);
    private static final UUID COMMIT_A = id(2);
    private static final UUID DOCUMENT = id(3);
    private static final UUID RESOURCE = id(4);
    private static final UUID V1 = id(5);
    private static final UUID V2 = id(6);
    private static final UUID SOURCE1 = id(7);
    private static final UUID SOURCE2 = id(8);
    private static final UUID V3 = id(9);
    private static final UUID SOURCE3 = id(10);
    private static final String SOURCE_V1 = "Q55 synthetic source v1\nA-original bytes\n";
    private static final String SOURCE_V2 = "Q55 synthetic source v2\nretained original bytes\n";
    private static final String SOURCE_V3 = "Q55 synthetic source v3\nB-only original bytes\n";
    private static final String DOCUMENT_A = document("A", V1);
    private static final String DOCUMENT_B = document("B", V3);
    private static final String DOCUMENT_C = document("C-unsaved", V3);
    private static final TextContent EDIT = new TextContent.Message("snapshotdemo.edit", List.of());
    private static final Problem WRONG_BYTES = new Problem(Problem.Severity.ERROR,
            EditorId.parse("snapshotdemo:wrong_bytes"), RESOURCE.toString(),
            new TextContent.Message("snapshotdemo.wrong_bytes", List.of()));

    private FixedSnapshotReadSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("create")) {
            create(Path.of(args[1]), Path.of(args[2]));
        } else if (args.length == 3 && args[0].equals("receive")) {
            Path destination = Path.of(args[2]);
            requireNew(destination);
            LocalProjectStore.importSnapshot(Path.of(args[1]), destination);
            receive(destination);
        } else {
            throw new IllegalArgumentException("create <new-project> <new-fixed-a.zip> | receive <a.zip> <new-received>");
        }
    }

    private static void create(Path root, Path archive) throws Exception {
        requireNew(root);
        requireNew(archive);
        Checks checks = new Checks();
        ExtensionRegistry registry = registry(checks);
        UUID commitB;
        try (LocalProjectStore store = LocalProjectStore.create(root)) {
            ProjectState.Entry first = asset(store, SOURCE1, SOURCE_V1);
            ProjectState.Entry second = asset(store, SOURCE2, SOURCE_V2);
            ProjectState.Entry third = asset(store, SOURCE3, SOURCE_V3);
            ResourceCatalog a = catalog("A", Map.of(V1, version(V1), V2, version(V2)),
                    Map.of(SOURCE1, first, SOURCE2, second));
            ProjectState.Entry noteEntry = new ProjectState.Entry(DOCUMENT, MODULE.toString(),
                    TYPE.toString(), CODEC.toString(), 1, "Fixed saved note",
                    store.stage(BlobRef.Kind.DOCUMENT, utf8(DOCUMENT_A)), Set.of(), Set.of(RESOURCE, V1));
            store.commit(null, COMMIT_A, new ProjectState(PROJECT, "Fixed snapshot public consumer",
                    Map.of(MODULE.toString(), "1", RESOURCE_MODULE.toString(), "1"),
                    Map.of(DOCUMENT, noteEntry),
                    ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(a)), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            ProjectSnapshot captured = access.captureSnapshot();
            require(captured.saved().commitId().equals(COMMIT_A), "capture must select published A");
            checks.expect(true);
            LoadedProject loadedA = access.readValidated(captured, registry, () -> false);
            checks.complete();
            verify(store, loadedA, true);
            try (ProjectSession session = ProjectSession.open(access, loadedA, registry, 8)) {
                require(!session.protectedMode(), "same-registry validated A must be editable");
                var note = session.find(DOCUMENT, String.class).orElseThrow();
                var resources = session.resources().orElseThrow();
                note.edit(note.view().token(), EDIT, value -> DOCUMENT_B);
                // Public session edits preserve resource history. B adds v3; destructive removal
                // scenarios belong to storage/runtime tests, not an unsupported owner command.
                ResourceCatalog b = catalog("B", Map.of(V1, version(V1), V2, version(V2), V3, version(V3)),
                        Map.of(SOURCE1, first, SOURCE2, second, SOURCE3, third));
                resources.edit(resources.view().token(), EDIT, value -> b);
                var save = session.captureSave(Set.of(DOCUMENT), true);
                checks.expect(false);
                var prepared = access.prepareValidated(save, () -> false);
                checks.complete();
                commitB = session.enqueueSave(prepared);
                var dispatch = session.dispatchSave().orElseThrow();
                require(dispatch.requestId().equals(commitB) && dispatch.problems().isEmpty(),
                        "dispatch must preserve the actual B request");
                var job = dispatch.job().orElseThrow();
                var result = access.publish(job);
                require(result.outcome() == SaveResult.Outcome.CONFIRMED, "B publication must confirm");
                require(session.completeSave(job, result), "owner must accept its exact B result");
                require(session.source() == loadedA && session.source().saved().equals(captured.saved()),
                        "session source must remain A after saving B");
                require(session.saves().confirmed().commitId().equals(commitB)
                        && !commitB.equals(COMMIT_A), "queue confirmed must advance to B");
                require(!note.view().dirty() && !resources.view().dirty(), "confirmed B drafts must be clean");
                note.edit(note.view().token(), EDIT, value -> DOCUMENT_C);
                ResourceCatalog c = catalog("C-unsaved", b.versions(), b.byteEntries());
                resources.edit(resources.view().token(), EDIT, value -> c);
                require(note.view().dirty() && resources.view().dirty(), "C must remain an unsaved draft");
                for (int repeat = 0; repeat < 2; repeat++) {
                    checks.expect(true);
                    LoadedProject fixed = access.readValidated(captured, registry, () -> false);
                    checks.complete();
                    verify(store, fixed, true);
                    require(fixed.saved().equals(captured.saved()), "fixed read must still select exact A");
                    LoadedProject current = access.read();
                    verify(store, current, false);
                    require(current.saved().equals(session.saves().confirmed()), "ordinary read must select B");
                    checks.expect(false);
                    verify(store, access.readValidated(registry, () -> false), false);
                    checks.complete();
                    require(note.view().content().equals(DOCUMENT_C), "reads must preserve draft C");
                    require(resources.view().content().equals(c) && resources.view().dirty(),
                            "reads must preserve C resource pointer and unsaved name");
                }
                store.exportSnapshot(archive, captured);
                require(store.load().commitId().equals(commitB), "exporting fixed A must leave current B");
            }
        }
        System.out.println("FIXED_SNAPSHOT_CREATE_PASS A=" + COMMIT_A + " B=" + commitB
                + " fixed=1,2 current=1,2,3 draft=C-unsaved");
    }

    private static void receive(Path root) throws Exception {
        Checks checks = new Checks();
        ExtensionRegistry registry = registry(checks);
        try (LocalProjectStore store = LocalProjectStore.open(root)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            checks.expect(true);
            LoadedProject loaded = access.readValidated(registry, () -> false);
            checks.complete();
            verify(store, loaded, true);
            require(new ProjectState.Origin(PROJECT, COMMIT_A).equals(loaded.saved().state().origin()),
                    "import must create a local save with the exact A source identity");
            try (ProjectSession session = ProjectSession.open(access, loaded, registry, 8)) {
                require(!session.protectedMode(), "received A must validate with freshly registered module");
                var note = session.find(DOCUMENT, String.class).orElseThrow();
                require(note.view().content().equals(DOCUMENT_A) && !note.view().dirty(),
                        "fresh session must reopen clean A document locked to v1");
                require(session.resources().orElseThrow().view().content().equals(loaded.resources().orElseThrow()),
                        "fresh session must retain complete A resource catalog");
            }
        }
        System.out.println("FIXED_SNAPSHOT_RECEIVE_PASS sourceA=" + COMMIT_A + " fixed=1,2 document-lock=1");
    }

    private static void verify(LocalProjectStore store, LoadedProject loaded, boolean a) throws IOException {
        require(loaded.problems().isEmpty() && loaded.resourceFailure().isEmpty(), "selected graph must be readable");
        require(loaded.saved().state().projectId().equals(PROJECT), "project identity must survive");
        require(loaded.documentIds().equals(Set.of(DOCUMENT)), "complete document set must survive");
        require(Arrays.equals(loaded.documentBytes(DOCUMENT), utf8(a ? DOCUMENT_A : DOCUMENT_B)),
                "saved document must be exact A or B, never C");
        require(loaded.saved().state().documents().get(DOCUMENT).assetDependencies().equals(Set.of(RESOURCE, a ? V1 : V3)),
                "saved manifest must retain the document's exact version lock");
        ResourceCatalog catalog = loaded.resources().orElseThrow();
        require(catalog.resources().equals(Map.of(RESOURCE,
                new LogicalResource(RESOURCE, RESOURCE_TYPE, a ? "A" : "B", a ? V2 : V3))), "logical resource must match selection");
        require(catalog.versions().equals(versions(a)),
                "selected graph must have exactly its retained versions");
        require(catalog.byteEntries().keySet().equals(sources(a)),
                "fixed A must retain both original sources and exclude B-only source3");
        for (ProjectState.Entry entry : catalog.byteEntries().values()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            store.copyContent(entry.content(), bytes);
            require(Arrays.equals(bytes.toByteArray(), utf8(sourceText(entry.id()))),
                    "selected source must preserve exact original UTF-8 bytes");
        }
    }

    private static ExtensionRegistry registry(Checks checks) {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(MODULE, "1", api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(RESOURCE_MODULE, "1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, contribution(DocumentTypes.KIND.id(), TYPE), new Note());
        builder.add(ResourceProducers.KIND, contribution(ResourceProducers.KIND.id(), IMPORTER),
                new ResourceProducerSpec(Set.of("1", "2", "3")));
        builder.add(ResourceContentValidators.KIND, contribution(ResourceContentValidators.KIND.id(), IMPORTER), checks::validate);
        return builder.freeze();
    }

    private static Contribution contribution(EditorId kind, EditorId id) {
        return new Contribution(new ContributionKey(kind, id), MODULE, Contribution.Side.COMMON, Set.of());
    }

    private static ResourceCatalog catalog(String name, Map<UUID, ResourceVersion> versions,
            Map<UUID, ProjectState.Entry> bytes) {
        return new ResourceCatalog(Map.of(RESOURCE, new LogicalResource(RESOURCE, RESOURCE_TYPE, name,
                name.equals("B") ? V3 : V2)), versions, bytes);
    }

    private static ResourceVersion version(UUID id) {
        return new ResourceVersion(id, RESOURCE, IMPORTER, id.equals(V1) ? "1" : id.equals(V2) ? "2" : "3",
                Map.of("format", "q55-exact-utf8"), id.equals(V1) ? SOURCE1 : id.equals(V2) ? SOURCE2 : SOURCE3,
                Set.of(), Set.of());
    }

    private static Map<UUID, ResourceVersion> versions(boolean a) {
        return a ? Map.of(V1, version(V1), V2, version(V2))
                : Map.of(V1, version(V1), V2, version(V2), V3, version(V3));
    }

    private static Set<UUID> sources(boolean a) {
        return a ? Set.of(SOURCE1, SOURCE2) : Set.of(SOURCE1, SOURCE2, SOURCE3);
    }

    private static String sourceText(UUID id) {
        return id.equals(SOURCE1) ? SOURCE_V1 : id.equals(SOURCE2) ? SOURCE_V2 : SOURCE_V3;
    }

    private static ProjectState.Entry asset(LocalProjectStore store, UUID id, String value) throws IOException {
        return new ProjectState.Entry(id, MODULE.toString(), "snapshotdemo:bytes", "snapshotdemo:bytes", 1,
                "Synthetic source", store.stage(BlobRef.Kind.ASSET, utf8(value)), Set.of(), Set.of());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("55000000-0000-0000-0000-" + String.format(java.util.Locale.ROOT, "%012d", suffix));
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String document(String label, UUID version) {
        return "Q55 document " + label + "\nresource=" + RESOURCE + "\nlocked-version=" + version + "\n";
    }
    private static void requireNew(Path path) {
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS), "destination must not exist: " + path);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** Host assertions observe each actual callback; no validation authority is manufactured. */
    private static final class Checks {
        private boolean a;
        private final Set<UUID> seen = new HashSet<>();
        void expect(boolean selectedA) { a = selectedA; seen.clear(); }
        void complete() { require(seen.equals(versions(a).keySet()), "every retained version must be validated"); }
        List<Problem> validate(ResourceVersion version, ResourceContent content) throws IOException {
            require(content.resourceTypes().equals(Map.of(RESOURCE, RESOURCE_TYPE)), "validator must see selected resource types");
            require(content.versions().equals(versions(a)),
                    "validator must see only the complete selected version graph");
            require(content.byteEntries().keySet().equals(sources(a)),
                    "validator must see only selected byte sources");
            require(version.equals(content.versions().get(version.id())) && seen.add(version.id()),
                    "each selected version must receive exactly one callback");
            for (UUID id : content.byteEntries().keySet()) {
                require(content.byteEntries().get(id).content().size() <= 4096, "synthetic parser input budget");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                content.copyContent(id, bytes);
                if (!Arrays.equals(bytes.toByteArray(), utf8(sourceText(id)))) {
                    return List.of(WRONG_BYTES);
                }
            }
            return List.of();
        }
    }

    private static final class Note implements DocumentType<String> {
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String create() { return DOCUMENT_A; }
        @Override public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return utf8(value); }
        @Override public List<Problem> validate(String value) {
            require(value.equals(DOCUMENT_A) || value.equals(DOCUMENT_B) || value.equals(DOCUMENT_C), "exact synthetic document required");
            return List.of();
        }
        @Override public DocumentDependencies dependencies(String value) {
            return new DocumentDependencies(Set.of(), Set.of(new ResourceRef(RESOURCE, value.equals(DOCUMENT_A) ? V1 : V3)), Set.of());
        }
    }
}
