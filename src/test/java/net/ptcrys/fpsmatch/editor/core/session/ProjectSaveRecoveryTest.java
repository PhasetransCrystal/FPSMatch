package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.project.SaveQueueView;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.RuntimeFaultStores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSaveRecoveryTest {
    @TempDir Path directory;
    private static final TextContent EDIT = new TextContent.Message("test.edit", List.of());

    @Test void realPublicationUncertaintyRequiresFreshStoreAndPreservesTheOriginalCapturedFact() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Path project = directory.resolve("uncertain");
        var registry = registry(new TestType());
        try (var fixture = project(project, registry, List.of(source(a, "A0"), source(b, "B0")))) {
            assertEquals(2, fixture.loaded().documentIds().size());
        }
        try (var store = RuntimeFaultStores.loseReceiptAfterPublish(project)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (var session = ProjectSession.open(access, access.read(), registry, 1)) {
                var first = session.find(a, String.class).orElseThrow();
                var second = session.find(b, String.class).orElseThrow();
                first.edit(first.view().token(), EDIT, value -> "A1");
                session.enqueueSave(access.prepare(session.captureSave(Set.of(a))));
                second.edit(second.view().token(), EDIT, value -> "B1");
                session.enqueueSave(access.prepare(session.captureSave(Set.of(b))));
                ProjectSaveJob job = session.dispatchSave().orElseThrow().job().orElseThrow();
                first.edit(first.view().token(), EDIT, value -> "A2");
                SaveResult outcome = access.publish(job);
                assertEquals(SaveResult.Outcome.UNKNOWN, outcome.outcome());
                assertTrue(session.completeSave(job, outcome));
                assertEquals(SaveQueueView.State.UNKNOWN, session.saves().state());
                assertTrue(session.dispatchSave().isEmpty());
                assertTrue(session.completeSave(job, access.reconcile(job)));
                assertTrue(session.requiresReopen());
                assertEquals(job.attempt().requestId(), session.saves().confirmed().commitId());
                assertEquals("A2", first.view().content());
                assertTrue(first.hasUnpublishedChanges(), "A2 was not part of A1's original capture");
                assertTrue(second.hasUnpublishedChanges(), "B's queued request was never dispatched");
                assertEquals(1, session.saves().queued());
                assertTrue(session.dispatchSave().isEmpty(), "queued B cannot use the unadopted physical baseline");
                assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(a)));
                assertThrows(IllegalStateException.class, () -> access.publish(job));
            }
        }
        // A fresh physical writer, not just a new ProjectSession on the old access, adopts CURRENT.
        try (var store = LocalProjectStore.open(project)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (var reopened = ProjectSession.open(access, access.read(), registry, 1)) {
                assertFalse(reopened.requiresReopen());
                assertEquals("A1", reopened.find(a, String.class).orElseThrow().view().content());
                assertEquals("B0", reopened.find(b, String.class).orElseThrow().view().content());
                var first = reopened.find(a, String.class).orElseThrow();
                first.edit(first.view().token(), EDIT, value -> "fresh save");
                reopened.enqueueSave(access.prepare(reopened.captureSave(Set.of(a))));
                ProjectSaveJob job = reopened.dispatchSave().orElseThrow().job().orElseThrow();
                assertTrue(reopened.completeSave(job, access.publish(job)));
                assertFalse(first.hasUnpublishedChanges());
            }
        }
    }
}
