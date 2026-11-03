package net.ptcrys.fpsmatch.editor.core.session.examples;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import net.ptcrys.fpsmatch.editor.core.project.SaveQueueView;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.PreparedProjectSave;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSaveCapture;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSaveDispatch;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSaveJob;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/**
 * Standalone public-JAR consumer: a third-party document type saves and reopens a private temporary
 * project. All calls run sequentially in this headless example; a UI host must dispatch blocking I/O
 * separately and keep domain work on its owner. No user project or game world is accepted as input.
 */
public final class ProjectSaveSmoke {
    private static final EditorId MODULE = EditorId.parse("vendor:notes");
    private static final EditorId TYPE = EditorId.parse("thirdparty:note");
    private static final EditorId CODEC = EditorId.parse("thirdparty:note_utf8");
    private static final String BUILD = "1.0.0";
    private static final String SAVED_TEXT = "Third-party note saved through the public runtime.";

    private ProjectSaveSmoke() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("ProjectSaveSmoke accepts no paths; it creates a private temporary project.");
        }
        Path directory = Files.createTempDirectory("editor-save-consumer-").resolve("project");
        System.out.println("Temporary project: " + directory);
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID savedCommit;
        try (LocalProjectStore store = LocalProjectStore.create(directory)) {
            // The example host explicitly creates the required empty first commit. This is not a
            // claim that ProjectSession implements an end-user new-project/bootstrap workflow.
            SavedProject empty = store.commit(null, UUID.randomUUID(), new ProjectState(projectId,
                    "Public save consumer", Map.of(MODULE.toString(), BUILD), Map.of(), Map.of(), null)).saved();
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.read(), registry(), 8)) {
                require(!session.protectedMode(), "registered empty project must be editable");
                require(session.documents().isEmpty(), "first commit must contain no documents");
                DocumentHandle<String> note = session.create(documentId, TYPE, String.class, "Example note");
                require(note.view().content().equals("new") && note.hasUnpublishedChanges(),
                        "the created default is an unpublished draft");
                note.edit(note.view().token(), new TextContent.Message("demo.note.edit", List.of()),
                        value -> SAVED_TEXT);
                note.undo(note.view().token());
                require(note.view().content().equals("new") && note.hasUnpublishedChanges(),
                        "undo restores the unpublished default");
                note.redo(note.view().token());
                require(note.view().content().equals(SAVED_TEXT), "redo restores the intended note");
                ProjectSaveCapture capture = session.captureSave(Set.of(documentId));
                require(capture.documentIds().equals(Set.of(documentId)), "capture contains exactly the selected note");
                PreparedProjectSave prepared = access.prepare(capture);
                require(store.load().equals(empty), "capture and prepare must leave CURRENT unchanged");
                UUID requestId = session.enqueueSave(prepared);
                ProjectSaveDispatch dispatch = session.dispatchSave().orElseThrow();
                require(dispatch.requestId().equals(requestId), "dispatch must preserve the queued request identity");
                require(dispatch.problems().isEmpty(), "the valid note must pass publication preflight");
                ProjectSaveJob job = dispatch.job().orElseThrow();
                SaveResult result = access.publish(job);
                require(result.outcome() == SaveResult.Outcome.CONFIRMED, "the store must confirm publication");
                require(note.hasUnpublishedChanges(), "only owner acceptance confirms the captured draft");
                require(session.completeSave(job, result), "the session must accept its exact active job result");
                require(!note.hasUnpublishedChanges() && !note.view().dirty(), "the confirmed note must be clean");
                require(session.saves().state() == SaveQueueView.State.READY
                                && session.saves().queued() == 0 && !session.requiresReopen(),
                        "normal confirmed publication must finish with an idle queue");
                savedCommit = requestId;
                require(store.load().commitId().equals(savedCommit), "CURRENT must identify the published request");
            }
        }

        // Both the original session and store are now closed. No old access, capture or registry
        // instance participates in reopening the persisted third-party document.
        try (LocalProjectStore store = LocalProjectStore.open(directory)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession reopened = ProjectSession.open(access, access.read(), registry(), 8)) {
                require(!reopened.protectedMode(), "the third-party type must activate again on fresh open");
                require(reopened.source().saved().commitId().equals(savedCommit), "reopen must read the actual saved commit");
                require(reopened.source().saved().state().projectId().equals(projectId), "project identity must survive");
                require(reopened.documents().size() == 1, "exactly the saved note must reopen");
                DocumentHandle<String> note = reopened.find(documentId, String.class).orElseThrow();
                require(note.typeId().equals(TYPE) && note.view().content().equals(SAVED_TEXT),
                        "fresh activation and decode must recover the third-party value");
                require(!note.hasUnpublishedChanges() && !note.view().dirty(), "the reopened note must be clean");
                require(new String(reopened.source().documentBytes(documentId), StandardCharsets.UTF_8).equals(SAVED_TEXT),
                        "the persisted bytes must match the captured value");
            }
        }
        System.out.println("SAVE_CONSUMER_PASS: public registry -> create/edit/undo/redo -> capture/prepare/queue/publish/confirm -> fresh reopen");
        System.out.println("Scope: third-party note document API only; BO, resources, UI and recovery are outside this example.");
        System.out.println("Temporary project retained: " + directory);
    }

    private static ExtensionRegistry registry() {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.addModule(new ModuleSpec(MODULE, BUILD, api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND,
                new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE), MODULE,
                        Contribution.Side.COMMON, Set.of()), new NoteType());
        return builder.freeze();
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }

    private static final class NoteType implements DocumentType<String> {
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String create() { return "new"; }
        @Override public String decode(int schema, byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public List<Problem> validate(String value) { return List.of(); }
        @Override public DocumentDependencies dependencies(String value) {
            return new DocumentDependencies(Set.of(), Set.of(), Set.of());
        }
    }
}
