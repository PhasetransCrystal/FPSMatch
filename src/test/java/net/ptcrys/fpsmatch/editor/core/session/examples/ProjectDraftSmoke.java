package net.ptcrys.fpsmatch.editor.core.session.examples;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;
import net.ptcrys.fpsmatch.editor.core.session.LoadedProject;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/** External public-JAR consumer; creates its own temporary project and never opens a user save. */
public final class ProjectDraftSmoke {
    private static final EditorId OWNER = EditorId.parse("vendor:maps");
    private static final EditorId TYPE = EditorId.parse("thirdparty:note");
    private static final EditorId CODEC = EditorId.parse("thirdparty:note_utf8");
    private static final String BUILD = "7.4.0";

    private ProjectDraftSmoke() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("this example only creates its own temporary project");
        }
        Path directory = Files.createTempDirectory("editor-draft-smoke-").resolve("project");
        UUID documentId = UUID.randomUUID();
        byte[] originalBytes = "saved".getBytes(StandardCharsets.UTF_8);
        try (LocalProjectStore store = LocalProjectStore.create(directory)) {
            ProjectState.Entry entry = new ProjectState.Entry(documentId, OWNER.toString(),
                    TYPE.toString(), CODEC.toString(), 1, "note",
                    store.stage(BlobRef.Kind.DOCUMENT, originalBytes), Set.of(), Set.of());
            SavedProject baseline = store.commit(null, UUID.randomUUID(), new ProjectState(
                    UUID.randomUUID(), "Draft example", Map.of(OWNER.toString(), BUILD),
                    Map.of(documentId, entry), Map.of(), null)).saved();
            LocalProjectAccess access = new LocalProjectAccess(store);
            LoadedProject loaded = access.read();
            ExtensionRegistry registry = registry(true);
            try (ProjectSession session = ProjectSession.open(access, loaded, registry, 16)) {
                require(!session.protectedMode(), "registered source must be editable");
                DocumentHandle<String> note = session.find(documentId, String.class).orElseThrow();
                require(note.view().content().equals("saved") && !note.hasUnpublishedChanges(),
                        "source starts at the real saved value");
                note.edit(note.view().token(),
                        new TextContent.Message("demo.note.change", List.of()), value -> "draft");
                require(note.hasUnpublishedChanges(), "editing creates an unpublished change");
                note.undo(note.view().token());
                require(note.view().content().equals("saved") && !note.hasUnpublishedChanges(),
                        "undo restores the saved value");
                note.redo(note.view().token());
                require(note.view().content().equals("draft") && note.hasUnpublishedChanges(),
                        "redo restores the draft");
                DocumentHandle<String> created = session.create(UUID.randomUUID(), TYPE,
                        String.class, "new note");
                require(!created.view().dirty() && created.hasUnpublishedChanges(),
                        "a new in-memory baseline has never been published");
                require(store.load().equals(baseline), "draft editing must not publish to disk");
            }
            try (ProjectSession reopened = ProjectSession.open(access, access.read(), registry, 16)) {
                require(reopened.find(documentId, String.class).orElseThrow().view().content()
                        .equals("saved"), "reopening reads the saved value, not the discarded draft");
                require(reopened.documents().size() == 1, "unpublished additions are not on disk");
            }
            try (ProjectSession protectedSession = ProjectSession.open(access, access.read(),
                    registry(false), 16)) {
                require(protectedSession.protectedMode(), "missing type protects publication");
                require(protectedSession.unavailableDocuments().equals(Set.of(documentId)),
                        "unavailable identity remains addressable");
                require(Arrays.equals(originalBytes, protectedSession.source().documentBytes(documentId)),
                        "unknown content remains available verbatim");
                require(store.load().equals(baseline), "inspection must preserve the disk snapshot");
            }
        }
        System.out.println("DRAFT_SMOKE_PASS: registry -> disk read -> edit/undo/redo -> protected reopen");
        System.out.println("Temporary project retained at " + directory);
        System.out.println("Draft publication, bootstrap and recovery are not implemented by this example.");
    }

    private static ExtensionRegistry registry(boolean withType) {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.addModule(new ModuleSpec(OWNER, BUILD, api, Set.of(), Set.of()));
        if (withType) {
            builder.add(DocumentTypes.KIND,
                    new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE),
                            OWNER, Contribution.Side.COMMON, Set.of()), new NoteType());
        }
        return builder.freeze();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class NoteType implements DocumentType<String> {
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String create() { return "new"; }
        @Override public String decode(int schema, byte[] payload) {
            return new String(payload, StandardCharsets.UTF_8);
        }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public List<Problem> validate(String value) { return List.of(); }
        @Override public DocumentDependencies dependencies(String value) {
            return new DocumentDependencies(Set.of(), Set.of(), Set.of());
        }
    }
}
