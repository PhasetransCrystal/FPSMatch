import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
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
import net.ptcrys.fpsmatch.editor.core.session.LoadedProject;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.PreparedRuntime;
import net.ptcrys.fpsmatch.editor.core.session.SavedRuntimeAdapter;
import net.ptcrys.fpsmatch.editor.core.session.SavedRuntimeAdapters;
import net.ptcrys.fpsmatch.editor.core.session.SavedRuntimeInput;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectSnapshot;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/**
 * Default-package, public-JAR consumer; run create and reopen in separate Java 17 JVMs.
 * All I/O is confined to the explicitly supplied synthetic project root on the caller thread.
 * This third-party format demonstrates complete saved-content checks and registered runtime preparation.
 * Successful verification grants no world binding, installation or match-start authority.
 */
public final class ValidatedSavedSourceSmoke {
    private static final EditorId MODULE = EditorId.parse("vendor:validated_source_demo");
    private static final EditorId RUNTIME_MODULE = EditorId.parse("vendor:runtime_demo");
    private static final EditorId HELPER_MODULE = EditorId.parse("vendor:runtime_helper");
    private static final EditorId ADAPTER = EditorId.parse("validateddemo:runtime");
    private static final EditorId RESOURCE_MODULE = EditorId.parse("editor:resources");
    private static final EditorId TYPE = EditorId.parse("validateddemo:note");
    private static final EditorId CODEC = EditorId.parse("validateddemo:utf8");
    private static final EditorId IMPORTER = EditorId.parse("validateddemo:source");
    private static final EditorId RESOURCE_TYPE = EditorId.parse("validateddemo:resource");
    private static final UUID PROJECT = id(1);
    private static final UUID COMMIT_A = id(2);
    private static final UUID COMMIT_B = id(3);
    private static final UUID DOCUMENT = id(4);
    private static final UUID RESOURCE = id(5);
    private static final UUID V1 = id(6);
    private static final UUID V2 = id(7);
    private static final UUID V3 = id(8);
    private static final UUID BYTES1 = id(9);
    private static final UUID BYTES2 = id(10);
    private static final UUID BYTES3 = id(11);

    private ValidatedSavedSourceSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("create ROOT | reopen ROOT");
        Path root = Path.of(args[1]);
        if (args[0].equals("create")) create(root);
        else if (args[0].equals("reopen")) reopen(root);
        else throw new IllegalArgumentException("create ROOT | reopen ROOT");
    }

    private static void create(Path root) throws IOException {
        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "create requires a nonexistent root");
        Checks checks = new Checks();
        ExtensionRegistry registry = registry(checks);
        LoadedProject source;
        LoadedProject current;
        try (LocalProjectStore store = LocalProjectStore.create(root)) {
            ProjectState.Entry first = asset(store, BYTES1, "1");
            ProjectState.Entry second = asset(store, BYTES2, "2");
            ResourceCatalog a = catalog(false, Map.of(BYTES1, first, BYTES2, second));
            store.commit(null, COMMIT_A, state(store, "A", a));
            LocalProjectAccess access = new LocalProjectAccess(store);
            // The current read and explicit capability read must each retain their selected A.
            source = access.readValidated(registry, () -> false);
            ProjectSnapshot fixedA = access.captureSnapshot();
            LoadedProject explicit = access.readValidated(fixedA, registry, () -> false);
            require(checks.calls == 4, "both reads must validate all retained A versions");
            check(source, false);
            check(explicit, false);
            verify(access, source, registry, checks);
            verify(access, explicit, registry, checks);

            ProjectState.Entry third = asset(store, BYTES3, "3");
            ResourceCatalog b = catalog(true, Map.of(BYTES1, first, BYTES2, second, BYTES3, third));
            store.commit(COMMIT_A, COMMIT_B, state(store, "B", b));
            verify(access, source, registry, checks);
            verify(access, explicit, registry, checks);
            LoadedProject reread = access.readValidated(fixedA, registry, () -> false);
            require(checks.calls == 6, "fixed reread must validate retained A after B is published");
            verify(access, reread, registry, checks);
            check(reread, false);
            current = access.read();
            check(current, true);
            rejectUnvalidated(access, current, registry);
            rejectUnvalidated(access, source, registry(new Checks()));
            try {
                new LocalProjectAccess(store).verifyValidatedSource(source, registry, () -> false);
                throw new IllegalStateException("another access must reject the source");
            } catch (IllegalArgumentException expected) {
                // Sharing the same live store does not share issuing authority.
            }
            verify(access, source, registry, checks);
            check(source, false);
            check(access.read(), true);
            require(store.load().commitId().equals(COMMIT_B), "verification must leave CURRENT at B");
        }
        report("create", source, current);
    }

    private static void reopen(Path root) throws IOException {
        Checks checks = new Checks();
        ExtensionRegistry registry = registry(checks);
        LoadedProject source;
        LoadedProject current;
        try (LocalProjectStore store = LocalProjectStore.open(root)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            current = access.read();
            check(current, true);
            rejectUnvalidated(access, current, registry);
            // No old capability is serialized: the new access validates and verifies current B.
            source = access.readValidated(registry, () -> false);
            require(checks.calls == 3, "fresh process must validate every retained B version");
            check(source, true);
            verify(access, source, registry, checks);
            require(store.load().commitId().equals(COMMIT_B), "reopen must leave CURRENT at B");
        }
        report("reopen", source, current);
    }

    private static void verify(LocalProjectAccess access, LoadedProject source,
            ExtensionRegistry registry, Checks checks) throws IOException {
        int before = checks.calls;
        access.verifyValidatedSource(source, registry, () -> false);
        require(access.verifyValidatedContent(source, registry, () -> false).isEmpty(),
                "complete saved content must have no diagnostics in this synthetic example");
        PreparedRuntime<String> prepared = access.prepareValidatedRuntime(source, registry, DOCUMENT,
                ADAPTER, String.class, () -> false);
        require(prepared.source() == source && prepared.documentId().equals(DOCUMENT)
                && prepared.adapterId().equals(ADAPTER) && prepared.problems().isEmpty(),
                "prepared result must retain the exact selected source and contribution");
        require(prepared.value().equals("runtime:" + new String(source.documentBytes(DOCUMENT),
                StandardCharsets.UTF_8)), "runtime must consume the selected saved bytes");
        require(prepared.runtimeModules().equals(Map.of(MODULE, "1", RUNTIME_MODULE, "1", HELPER_MODULE, "2")),
                "runtime-only code dependencies must be captured without rewriting saved modules");
        require(!source.saved().state().modules().containsKey(RUNTIME_MODULE.toString()),
                "runtime adapter does not have to be embedded in historical document requirements");
        require(checks.calls == before, "verification must not rerun the content validator");
    }

    private static void rejectUnvalidated(LocalProjectAccess access, LoadedProject source,
            ExtensionRegistry registry) throws IOException {
        try {
            access.verifyValidatedSource(source, registry, () -> false);
            throw new IllegalStateException("unvalidated or different-registry source must be rejected");
        } catch (SessionRejection expected) {
            require(expected.problems().size() == 1
                    && expected.problems().get(0).severity() == Problem.Severity.ERROR
                    && expected.problems().get(0).code().toString()
                            .equals("editor:session/saved_source_validation_required"),
                    "rejection must identify the saved-source validation requirement");
        }
    }

    private static ProjectState state(LocalProjectStore store, String doc, ResourceCatalog catalog)
            throws IOException {
        ProjectState.Entry note = new ProjectState.Entry(DOCUMENT, MODULE.toString(), TYPE.toString(),
                CODEC.toString(), 1, "Synthetic note", store.stage(BlobRef.Kind.DOCUMENT, utf8(doc)),
                Set.of(), Set.of(RESOURCE, doc.equals("A") ? V1 : V3));
        return new ProjectState(PROJECT, "Validated saved source public consumer",
                Map.of(MODULE.toString(), "1", RESOURCE_MODULE.toString(), "1"), Map.of(DOCUMENT, note),
                ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(catalog)), null);
    }

    private static ProjectState.Entry asset(LocalProjectStore store, UUID id, String version)
            throws IOException {
        return new ProjectState.Entry(id, MODULE.toString(), "validateddemo:bytes", CODEC.toString(),
                1, "Synthetic source", store.stage(BlobRef.Kind.ASSET, sourceBytes(version)), Set.of(), Set.of());
    }

    private static ResourceCatalog catalog(boolean b, Map<UUID, ProjectState.Entry> bytes) {
        return new ResourceCatalog(Map.of(RESOURCE,
                new LogicalResource(RESOURCE, RESOURCE_TYPE, b ? "B" : "A", b ? V3 : V2)), versions(b), bytes);
    }

    private static Map<UUID, ResourceVersion> versions(boolean b) {
        ResourceVersion first = version(V1, "1", BYTES1);
        ResourceVersion second = version(V2, "2", BYTES2);
        return b ? Map.of(V1, first, V2, second, V3, version(V3, "3", BYTES3))
                : Map.of(V1, first, V2, second);
    }

    private static ResourceVersion version(UUID id, String format, UUID bytes) {
        return new ResourceVersion(id, RESOURCE, IMPORTER, format,
                Map.of("format", "synthetic-utf8"), bytes, Set.of(), Set.of());
    }

    private static void check(LoadedProject loaded, boolean b) {
        require(loaded.saved().state().projectId().equals(PROJECT)
                && loaded.saved().commitId().equals(b ? COMMIT_B : COMMIT_A), "saved identity must match");
        require(loaded.problems().isEmpty() && loaded.resourceFailure().isEmpty(), "clean resource graph required");
        require(loaded.documentIds().equals(Set.of(DOCUMENT))
                && Arrays.equals(loaded.documentBytes(DOCUMENT), utf8(b ? "B" : "A")), "exact saved document required");
        ResourceCatalog resources = loaded.resources().orElseThrow();
        require(resources.versions().equals(versions(b)), "complete retained version set required");
        require(resources.resources().equals(Map.of(RESOURCE,
                new LogicalResource(RESOURCE, RESOURCE_TYPE, b ? "B" : "A", b ? V3 : V2))),
                "selected logical resource required");
        require(resources.byteEntries().keySet().equals(b ? Set.of(BYTES1, BYTES2, BYTES3)
                : Set.of(BYTES1, BYTES2)), "selected retained byte entries required");
    }

    private static ExtensionRegistry registry(Checks checks) {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        builder.define(SavedRuntimeAdapters.KIND);
        builder.addModule(new ModuleSpec(MODULE, "1", api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(RESOURCE_MODULE, "1", api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(HELPER_MODULE, "2", api, Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(RUNTIME_MODULE, "1", api, Set.of(HELPER_MODULE), Set.of()));
        builder.add(DocumentTypes.KIND, contribution(DocumentTypes.KIND.id(), TYPE), new Note());
        builder.add(ResourceProducers.KIND, contribution(ResourceProducers.KIND.id(), IMPORTER),
                new ResourceProducerSpec(Set.of("1", "2", "3")));
        builder.add(ResourceContentValidators.KIND,
                contribution(ResourceContentValidators.KIND.id(), IMPORTER), checks::validate);
        builder.add(SavedRuntimeAdapters.KIND, new Contribution(
                new ContributionKey(SavedRuntimeAdapters.KIND.id(), ADAPTER), RUNTIME_MODULE,
                Contribution.Side.COMMON, Set.of(new ContributionKey(DocumentTypes.KIND.id(), TYPE))),
                new NoteRuntime(null));
        return builder.freeze();
    }

    private static Contribution contribution(EditorId kind, EditorId id) {
        return new Contribution(new ContributionKey(kind, id), MODULE, Contribution.Side.COMMON, Set.of());
    }

    private static final class Checks {
        private int calls;
        List<Problem> validate(ResourceVersion version, ResourceContent content) throws IOException {
            calls++;
            boolean b = content.versions().containsKey(V3);
            require(content.resourceTypes().equals(Map.of(RESOURCE, RESOURCE_TYPE))
                    && content.versions().equals(versions(b)), "validator must see the complete selected graph");
            require(content.byteEntries().keySet().equals(b ? Set.of(BYTES1, BYTES2, BYTES3)
                    : Set.of(BYTES1, BYTES2)), "validator must see retained source bytes");
            require(version.equals(content.versions().get(version.id())), "exact selected version required");
            require(content.byteEntries().get(version.sourceEntry()).content().size() <= 128,
                    "synthetic format input budget");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            content.copyContent(version.sourceEntry(), bytes);
            require(Arrays.equals(bytes.toByteArray(), sourceBytes(version.importerVersion())),
                    "source content must match its declared synthetic version");
            return List.of();
        }
    }

    private static final class Note implements DocumentType<String> {
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String create() { return "A"; }
        @Override public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return utf8(value); }
        @Override public List<Problem> validate(String value) {
            require(value.equals("A") || value.equals("B"), "synthetic note must be A or B");
            return List.of();
        }
        @Override public DocumentDependencies dependencies(String value) {
            return new DocumentDependencies(Set.of(),
                    Set.of(new ResourceRef(RESOURCE, value.equals("A") ? V1 : V3)), Set.of());
        }
    }

    private static final class NoteRuntime implements SavedRuntimeAdapter<String, String> {
        private final ExtensionRegistry registry;
        private NoteRuntime(ExtensionRegistry registry) { this.registry = registry; }
        @Override public EditorId documentType() { return TYPE; }
        @Override public Class<String> inputType() { return String.class; }
        @Override public Class<String> resultType() { return String.class; }
        @Override public SavedRuntimeAdapter<String, String> bind(ExtensionRegistry actual) {
            return new NoteRuntime(actual);
        }
        @Override public String prepare(SavedRuntimeInput<String> input,
                java.util.function.BooleanSupplier cancelled) {
            require(registry != null && registry.find(DocumentTypes.KIND, TYPE).isPresent(),
                    "runtime must bind to the actual frozen registry");
            require(!cancelled.getAsBoolean(), "synthetic preparation was cancelled");
            require(input.documentId().equals(DOCUMENT) && input.saved().state().projectId().equals(PROJECT)
                    && input.resources().versions().equals(versions(input.content().equals("B"))),
                    "runtime must receive the complete selected saved resource graph");
            return "runtime:" + input.content();
        }
    }

    private static void report(String mode, LoadedProject source, LoadedProject current) {
        System.out.println("{\"status\":\"PASS\",\"mode\":\"" + mode + "\",\"projectId\":\"" + PROJECT
                + "\",\"selectedCommitId\":\"" + source.saved().commitId()
                + "\",\"currentCommitId\":\"" + current.saved().commitId()
                + "\",\"sourceDoc\":\"" + new String(source.documentBytes(DOCUMENT), StandardCharsets.UTF_8)
                + "\",\"currentDoc\":\"" + new String(current.documentBytes(DOCUMENT), StandardCharsets.UTF_8)
                + "\",\"validatedSource\":true,\"validatedContent\":true,\"validatedRuntime\":true,\"noLiveHost\":true}");
    }

    private static UUID id(int suffix) {
        return UUID.fromString("59000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012d", suffix));
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] sourceBytes(String version) { return utf8("Q59 synthetic source " + version + "\n"); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
