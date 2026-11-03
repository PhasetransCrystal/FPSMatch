package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;

/**
 * Behaviour of the bounded local read seam against a real, temporary {@link LocalProjectStore}.
 *
 * <p>Every fixture is a real store with a real published commit: content is staged through the store,
 * the state is committed through the store, the project is reopened through the store, and neither a
 * public test hook of the production classes nor a stub store is used.</p>
 *
 * <p>The declared budget fixture keeps a single half-budget blob on disk while four document entries
 * declare it, so the manifest total is twice the read budget. The refusal is therefore
 * produced from the declaration: no document content is buffered, and a read that trusted the
 * declaration instead of checking it would return a project. Note that this is not a claim about the
 * first {@link LocalProjectStore#load()}, which by design streams and hashes the stored blobs.</p>
 */
class LocalProjectAccessTest {

    private static final UUID PROJECT = id(1);
    private static final UUID DOC_A = id(10);
    private static final UUID DOC_B = id(11);
    private static final UUID DOC_C = id(12);
    private static final UUID RESOURCE = id(20);
    private static final UUID VERSION_V1 = id(21);
    private static final UUID VERSION_V2 = id(22);
    private static final UUID FOREIGN_ASSET = id(30);
    private static final UUID FOREIGN_ASSET_OTHER = id(31);

    private static final String DOCUMENT_OWNER = "editor:documents";
    private static final String DOCUMENT_TYPE = "editor:document";
    private static final String DOCUMENT_CODEC = "editor:document_json";
    private static final String FOREIGN_OWNER = "editor:text-java2d";
    private static final EditorId TEXTURE_TYPE = EditorId.parse("editor:texture");
    private static final EditorId TEXT_IMPORTER = EditorId.parse("editor:text-java2d");

    @TempDir
    Path temp;

    @Test
    void readsAnEmptyProjectWithoutInventingResources() throws IOException {
        Path project = temp.resolve("empty");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            commit(store, id(900), projectState(Map.of(), Map.of()));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LoadedProject loaded = new LocalProjectAccess(store).read();

            assertTrue(loaded.documentIds().isEmpty());
            assertTrue(loaded.problems().isEmpty());
            assertTrue(loaded.resourceFailure().isEmpty());
            assertEquals(Optional.of(new ResourceCatalog(Map.of(), Map.of(), Map.of())), loaded.resources());
            assertEquals(PROJECT, loaded.saved().state().projectId());
            assertEquals(id(900), loaded.saved().commitId());
        }
    }

    @Test
    void returnsEveryDocumentByteInStableOrderAndIsolatesTheCaller() throws IOException {
        Path project = temp.resolve("documents");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Map<UUID, ProjectState.Entry> documents = new LinkedHashMap<>();
            documents.put(DOC_C, storedDocument(store, DOC_C, "c-body", "c.doc"));
            documents.put(DOC_A, storedDocument(store, DOC_A, "a-body", "a.doc"));
            documents.put(DOC_B, storedDocument(store, DOC_B, "b-body", "b.doc"));
            commit(store, id(901), projectState(documents, Map.of()));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LoadedProject loaded = new LocalProjectAccess(store).read();

            assertEquals(Set.of(DOC_A, DOC_B, DOC_C), loaded.documentIds());
            assertEquals(List.of(DOC_A, DOC_B, DOC_C), new ArrayList<>(loaded.documentIds()));
            assertArrayEquals(bytes("a-body"), loaded.documentBytes(DOC_A));
            assertArrayEquals(bytes("b-body"), loaded.documentBytes(DOC_B));
            assertArrayEquals(bytes("c-body"), loaded.documentBytes(DOC_C));

            // The caller mutates a returned array and the read keeps its own copy.
            byte[] first = loaded.documentBytes(DOC_A);
            first[0] = 'X';
            assertArrayEquals(bytes("a-body"), loaded.documentBytes(DOC_A));
            assertNotSame(loaded.documentBytes(DOC_A), loaded.documentBytes(DOC_A));

            assertThrows(UnsupportedOperationException.class, () -> loaded.documentIds().remove(DOC_A));
            assertThrows(UnsupportedOperationException.class, () -> loaded.problems().add(null));
            assertThrows(IllegalArgumentException.class, () -> loaded.documentBytes(id(9999)));
        }
    }

    @Test
    void refusesAnUnknownDocumentIdAsACallerProgrammingError() throws IOException {
        Path project = temp.resolve("unknown-id");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            commit(store, id(902), projectState(
                    Map.of(DOC_A, storedDocument(store, DOC_A, "body", "a.doc")), Map.of()));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LoadedProject loaded = new LocalProjectAccess(store).read();

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> loaded.documentBytes(DOC_B));
            assertTrue(refused.getMessage().contains(DOC_B.toString()));
        }
    }

    /**
     * A result answers for the identity of exactly one access object. The identity is the private token
     * the issuer signs with - the same one-way idiom {@code LocalProjectStore} and
     * {@code DocumentSession} use - so a caller can only ask whether a candidate is that identity,
     * never read it out of an access object or a result. This same-package test reads the token
     * reflectively rather than widening the production surface for it.
     */
    @Test
    void marksEachResultWithTheOpaqueIdentityOfOneAccessObject() throws IOException, ReflectiveOperationException {
        Path project = temp.resolve("identity");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            commit(store, id(903), projectState(
                    Map.of(DOC_A, storedDocument(store, DOC_A, "body", "a.doc")), Map.of()));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LocalProjectAccess first = new LocalProjectAccess(store);
            LocalProjectAccess second = new LocalProjectAccess(store);

            LoadedProject fromFirst = first.read();
            LoadedProject fromSecond = second.read();

            Object firstIdentity = identityOf(first);
            Object secondIdentity = identityOf(second);

            // Two access objects never share an identity, so neither result stands in for the other.
            assertNotSame(firstIdentity, secondIdentity);
            assertTrue(fromFirst.issuedBy(firstIdentity));
            assertFalse(fromFirst.issuedBy(secondIdentity));
            assertTrue(fromSecond.issuedBy(secondIdentity));
            assertFalse(fromSecond.issuedBy(firstIdentity));

            // The token is not the access object itself, so holding an access object or a result is
            // never enough to forge the identity of an issuer.
            assertNotSame(firstIdentity, first);
            assertFalse(fromFirst.issuedBy(first));
            assertFalse(fromFirst.issuedBy(fromSecond));
            assertFalse(fromFirst.issuedBy(fromFirst));
            assertFalse(fromFirst.issuedBy(null));
        }
    }

    @Test
    void readsTheStoredResourceGraphAndKeepsALockedOlderVersion() throws IOException {
        Path project = temp.resolve("resources");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Map<UUID, ProjectState.Entry> staged =
                    ResourceStorageBridge.stage(store, twoVersionCatalog(), Set.of(new ResourceRef(RESOURCE, VERSION_V1)));
            Map<UUID, ProjectState.Entry> assets = new LinkedHashMap<>(staged);
            assets.put(FOREIGN_ASSET, foreignAsset(FOREIGN_ASSET, store.stage(BlobRef.Kind.ASSET, bytes("locked")),
                    Set.of(VERSION_V1), Set.of()));
            Map<UUID, ProjectState.Entry> documents = Map.of(
                    DOC_A, document(store, DOC_A, "scene", "scene.doc", Set.of(), Set.of(FOREIGN_ASSET)));
            commit(store, id(904), projectState(documents, assets));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LoadedProject loaded = new LocalProjectAccess(store).read();

            assertTrue(loaded.problems().isEmpty());
            assertTrue(loaded.resourceFailure().isEmpty());
            ResourceCatalog recovered = loaded.resources().orElseThrow();
            assertEquals(Set.of(RESOURCE), recovered.resources().keySet());
            assertEquals(Set.of(VERSION_V1, VERSION_V2), recovered.versions().keySet());
            assertEquals(VERSION_V2, recovered.resources().get(RESOURCE).currentVersion());
            // The older locked version survives the read even though it is no longer current.
            assertTrue(recovered.byteEntries().containsKey(FOREIGN_ASSET));
            assertEquals(Set.of(VERSION_V1), recovered.byteEntries().get(FOREIGN_ASSET).assetDependencies());
            assertArrayEquals(bytes("scene"), loaded.documentBytes(DOC_A));
        }
    }

    @Test
    void reportsAnUnavailableCatalogForAThirdPartyAssetDocumentEdge() throws IOException {
        Path project = temp.resolve("foreign-edge");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Map<UUID, ProjectState.Entry> assets = Map.of(FOREIGN_ASSET,
                    foreignAsset(FOREIGN_ASSET, store.stage(BlobRef.Kind.ASSET, bytes("payload")),
                            Set.of(), Set.of(DOC_A)));
            Map<UUID, ProjectState.Entry> documents = Map.of(
                    DOC_A, document(store, DOC_A, "scene", "scene.doc", Set.of(), Set.of(FOREIGN_ASSET)));
            commit(store, id(905), projectState(documents, assets));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LoadedProject loaded = new LocalProjectAccess(store).read();

            assertTrue(loaded.resources().isEmpty());
            assertEquals(1, loaded.problems().size());
            Problem problem = loaded.problems().get(0);
            assertEquals(Problem.Severity.ERROR, problem.severity());
            assertEquals(EditorId.parse("editor:session/resource_catalog_unavailable"), problem.code());
            assertEquals(PROJECT.toString(), problem.target());
            TextContent.Message message = (TextContent.Message) problem.message();
            assertEquals("editor.session.resource_catalog_unavailable", message.key());
            assertEquals(List.of(PROJECT.toString()), literalArguments(message));
            assertTrue(loaded.resourceFailure().orElseThrow() instanceof IllegalArgumentException);

            // The protection state preserves the original entry instead of dropping or repairing it.
            assertEquals(1, loaded.saved().state().assets().size());
            ProjectState.Entry preserved = loaded.saved().state().assets().get(FOREIGN_ASSET);
            assertTrue(preserved.documentDependencies().contains(DOC_A));
            assertTrue(preserved.assetDependencies().isEmpty());
            assertArrayEquals(bytes("scene"), loaded.documentBytes(DOC_A));
        }
    }

    @Test
    void keepsBothDocumentsAndProtectionStateForAnAssetDocumentCycle() throws IOException {
        Path project = temp.resolve("asset-document-cycle");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Map<UUID, ProjectState.Entry> assets = new LinkedHashMap<>();
            assets.put(FOREIGN_ASSET, foreignAsset(FOREIGN_ASSET,
                    store.stage(BlobRef.Kind.ASSET, bytes("first")), Set.of(FOREIGN_ASSET_OTHER), Set.of(DOC_A)));
            assets.put(FOREIGN_ASSET_OTHER, foreignAsset(FOREIGN_ASSET_OTHER,
                    store.stage(BlobRef.Kind.ASSET, bytes("second")), Set.of(FOREIGN_ASSET), Set.of()));
            Map<UUID, ProjectState.Entry> documents = Map.of(
                    DOC_A, document(store, DOC_A, "scene", "scene.doc", Set.of(), Set.of(FOREIGN_ASSET)));
            commit(store, id(906), projectState(documents, assets));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LoadedProject loaded = new LocalProjectAccess(store).read();

            assertEquals(2, loaded.saved().state().assets().size());
            assertTrue(loaded.saved().state().assets().containsKey(FOREIGN_ASSET));
            assertTrue(loaded.saved().state().assets().containsKey(FOREIGN_ASSET_OTHER));
            assertTrue(loaded.saved().state().documents().containsKey(DOC_A));
            assertArrayEquals(bytes("scene"), loaded.documentBytes(DOC_A));
            assertEquals(1, loaded.problems().size());
            assertEquals(EditorId.parse("editor:session/resource_catalog_unavailable"),
                    loaded.problems().get(0).code());
            assertTrue(loaded.resources().isEmpty());
        }
    }

    /**
     * A cycle through assets alone, with no third-party document edge in it, is outside the graph the
     * bridge defines. It is a protected state and not a corrupt project: the original asset entries and
     * the document stay readable, and the gap is reported as one diagnostic instead of being replaced
     * by a catalog that dropped what it did not understand.
     */
    @Test
    void reportsAnUnavailableCatalogForAnOrdinaryAssetCycle() throws IOException {
        Path project = temp.resolve("asset-cycle");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Map<UUID, ProjectState.Entry> assets = new LinkedHashMap<>();
            assets.put(FOREIGN_ASSET, foreignAsset(FOREIGN_ASSET,
                    store.stage(BlobRef.Kind.ASSET, bytes("first")), Set.of(FOREIGN_ASSET_OTHER), Set.of()));
            assets.put(FOREIGN_ASSET_OTHER, foreignAsset(FOREIGN_ASSET_OTHER,
                    store.stage(BlobRef.Kind.ASSET, bytes("second")), Set.of(FOREIGN_ASSET), Set.of()));
            Map<UUID, ProjectState.Entry> documents = Map.of(
                    DOC_A, document(store, DOC_A, "scene", "scene.doc", Set.of(), Set.of(FOREIGN_ASSET)));
            commit(store, id(907), projectState(documents, assets));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LoadedProject loaded = new LocalProjectAccess(store).read();

            assertTrue(loaded.resources().isEmpty());
            assertEquals(1, loaded.problems().size());
            Problem problem = loaded.problems().get(0);
            assertEquals(Problem.Severity.ERROR, problem.severity());
            assertEquals(EditorId.parse("editor:session/resource_catalog_unavailable"), problem.code());
            assertEquals(PROJECT.toString(), problem.target());
            TextContent.Message message = (TextContent.Message) problem.message();
            assertEquals("editor.session.resource_catalog_unavailable", message.key());
            assertEquals(List.of(PROJECT.toString()), literalArguments(message));
            assertTrue(loaded.resourceFailure().orElseThrow() instanceof IllegalArgumentException);

            // Both cyclic entries survive unchanged, and the document is still readable.
            assertEquals(Set.of(FOREIGN_ASSET, FOREIGN_ASSET_OTHER), loaded.saved().state().assets().keySet());
            assertEquals(Set.of(FOREIGN_ASSET_OTHER),
                    loaded.saved().state().assets().get(FOREIGN_ASSET).assetDependencies());
            assertEquals(Set.of(FOREIGN_ASSET),
                    loaded.saved().state().assets().get(FOREIGN_ASSET_OTHER).assetDependencies());
            assertArrayEquals(bytes("scene"), loaded.documentBytes(DOC_A));
        }
    }

    /**
     * The declared total is checked before a single document blob is buffered. The fixture keeps one
     * shared blob on disk whose declared size is {@code MAX_DOCUMENT_BYTES / 2} while four documents
     * declare it. The third entry already crosses the budget, but the diagnostic must include the
     * fourth entry rather than report the first overflowing prefix as the total.
     */
    @Test
    void refusesAProjectWhoseDeclaredDocumentBytesExceedTheReadBudget() throws IOException {
        Path project = temp.resolve("budget");
        long half = LocalProjectAccess.MAX_DOCUMENT_BYTES / 2;
        long declared = 4 * half;

        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            BlobRef shared = store.stage(BlobRef.Kind.DOCUMENT, new byte[(int) half]);
            Map<UUID, ProjectState.Entry> documents = new LinkedHashMap<>();
            documents.put(DOC_A, documentOf(shared, DOC_A, "a.doc"));
            documents.put(DOC_B, documentOf(shared, DOC_B, "b.doc"));
            documents.put(DOC_C, documentOf(shared, DOC_C, "c.doc"));
            documents.put(id(13), documentOf(shared, id(13), "d.doc"));
            commit(store, id(908), projectState(documents, Map.of()));
        }

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            SessionRejection refused = assertThrows(SessionRejection.class, access::read);

            assertEquals(1, refused.problems().size());
            Problem problem = refused.problems().get(0);
            assertEquals(Problem.Severity.ERROR, problem.severity());
            assertEquals(EditorId.parse("editor:session/document_bytes_exceeded"), problem.code());
            assertEquals(PROJECT.toString(), problem.target());
            TextContent.Message message = (TextContent.Message) problem.message();
            assertEquals("editor.session.document_bytes_exceeded", message.key());
            assertEquals(List.of(declared, LocalProjectAccess.MAX_DOCUMENT_BYTES), numberArguments(message));

            // The refused read changed nothing: the manifest and its single shared blob still load.
            assertEquals(4, store.load().state().documents().size());
        }
    }

    /**
     * A damaged blob fails the read, and the failure is the storage's own failure propagated as is
     * rather than a half project. The project is published and reopened while its content is intact,
     * so the damage below is what the read meets, not what opening the project meets.
     */
    @Test
    void propagatesAStorageFailureInsteadOfReturningAHalfProject() throws IOException {
        Path project = temp.resolve("corrupt-blob");
        Path blob = publishedDocumentBlob(project);

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            Files.delete(blob);
            Files.write(blob, bytes("wrong"));

            ProjectStorageException corrupt = assertThrows(ProjectStorageException.class,
                    () -> new LocalProjectAccess(store).read());
            assertEquals(ProjectStorageException.Code.CORRUPT_CONTENT, corrupt.code());

            // A blob that is missing rather than wrong fails the same read the same way.
            Files.delete(blob);
            assertThrows(IOException.class, () -> new LocalProjectAccess(store).read());
        }
    }

    /**
     * Publishes one document into a fresh project and returns the path of its stored blob. A
     * {@link BlobRef} exposes neither its directory nor its relative path outside the storage package,
     * so the fixture builds the same path the store uses: {@code <documents>/<sha256>.blob}.
     */
    private Path publishedDocumentBlob(Path project) throws IOException {
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ProjectState.Entry entry = storedDocument(store, DOC_A, "body", "a.doc");
            commit(store, id(909), projectState(Map.of(DOC_A, entry), Map.of()));
            Path blob = project.resolve("documents").resolve(entry.content().sha256() + ".blob");
            assertTrue(Files.exists(blob), "the fixture must have written a real blob");
            return blob;
        }
    }

    /**
     * Reads the identity token an access object signs its results with. The production API never
     * publishes that token - {@code issuedBy} can only be asked, never read - so this same-package test
     * reaches it reflectively instead of widening the production surface for it.
     */
    private static Object identityOf(LocalProjectAccess access) throws ReflectiveOperationException {
        Field identity = LocalProjectAccess.class.getDeclaredField("identity");
        identity.setAccessible(true);
        return identity.get(access);
    }

    private static ResourceCatalog twoVersionCatalog() {
        return new ResourceCatalog(
                Map.of(RESOURCE, new LogicalResource(RESOURCE, TEXTURE_TYPE, "brick.png", VERSION_V2)),
                Map.of(VERSION_V1, new ResourceVersion(VERSION_V1, RESOURCE, TEXT_IMPORTER, "1.0.0",
                                Map.of(), null, Set.of(), Set.of()),
                        VERSION_V2, new ResourceVersion(VERSION_V2, RESOURCE, TEXT_IMPORTER, "1.1.0",
                                Map.of(), null, Set.of(), Set.of())),
                Map.of());
    }

    private static void commit(LocalProjectStore store, UUID requestId, ProjectState state) throws IOException {
        store.commit(null, requestId, state);
    }

    private ProjectState projectState(Map<UUID, ProjectState.Entry> documents,
                                      Map<UUID, ProjectState.Entry> assets) {
        Map<String, String> modules = new TreeMap<>();
        modules.put(DOCUMENT_OWNER, "1.0.0");
        modules.put("editor:resources", "1.0.0");
        modules.put(FOREIGN_OWNER, "1.0.0");
        return new ProjectState(PROJECT, "runtime", modules, documents, assets, null);
    }

    private static ProjectState.Entry storedDocument(LocalProjectStore store, UUID entryId, String body, String name)
            throws IOException {
        return document(store, entryId, body, name, Set.of(), Set.of());
    }

    private static ProjectState.Entry document(LocalProjectStore store, UUID entryId, String body, String name,
                                               Set<UUID> documents, Set<UUID> assets) throws IOException {
        return new ProjectState.Entry(entryId, DOCUMENT_OWNER, DOCUMENT_TYPE, DOCUMENT_CODEC, 1, name,
                store.stage(BlobRef.Kind.DOCUMENT, bytes(body)), documents, assets);
    }

    /** One document entry that shares {@code content} with its siblings but keeps its own identity. */
    private static ProjectState.Entry documentOf(BlobRef content, UUID entryId, String name) {
        return new ProjectState.Entry(entryId, DOCUMENT_OWNER, DOCUMENT_TYPE, DOCUMENT_CODEC, 1, name,
                content, Set.of(), Set.of());
    }

    private static ProjectState.Entry foreignAsset(UUID entryId, BlobRef content, Set<UUID> assets,
                                                   Set<UUID> documents) {
        return new ProjectState.Entry(entryId, FOREIGN_OWNER, "editor:texture_png", "editor:png",
                1, "foreign.png", content, documents, assets);
    }

    private static List<String> literalArguments(TextContent.Message message) {
        List<String> values = new ArrayList<>();
        for (TextContent.Argument argument : message.arguments()) {
            values.add(((TextContent.LiteralArgument) argument).value());
        }
        return values;
    }

    private static List<Long> numberArguments(TextContent.Message message) {
        List<Long> values = new ArrayList<>();
        for (TextContent.Argument argument : message.arguments()) {
            values.add(((TextContent.NumberArgument) argument).value().longValueExact());
        }
        return values;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static UUID id(long value) {
        return new UUID(0x5150430000000000L, value);
    }
}
