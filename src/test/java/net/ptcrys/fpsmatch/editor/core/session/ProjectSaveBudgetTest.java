package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hits real entry points with real shared blobs; sizes are never forged in manifest metadata. */
class ProjectSaveBudgetTest {
    @TempDir Path directory;
    private static final int BLOCK = 16 * 1024 * 1024;

    @Test void captureAndCompleteCandidateShareTheReadableLimitEvenAcrossSeparateSaves() throws Exception {
        byte[] large = new byte[BLOCK];
        TestType type = new TestType();
        type.encodeFunction = value -> value.equals("small") ? new byte[] {1} : large;
        try (var fixture = project(directory.resolve("candidate-budget"), registry(type), List.of());
             var session = fixture.open()) {
            Set<UUID> ids = new LinkedHashSet<>();
            for (int index = 0; index < 4; index++) {
                UUID id = UUID.randomUUID();
                session.create(id, TYPE, String.class, "large");
                ids.add(id);
            }
            assertEquals(64L * 1024 * 1024, session.captureSave(ids).encodedBytes(), "exactly 64MiB may be captured");
            type.initial = "small";
            UUID extra = UUID.randomUUID();
            var small = session.create(extra, TYPE, String.class, "small");
            Set<UUID> oversized = new LinkedHashSet<>(ids);
            oversized.add(extra);
            var initial = fixture.store().load();
            reject("capture_bytes_exceeded", () -> session.captureSave(oversized));
            assertEquals(initial, fixture.store().load());
            assertEquals(0, session.saves().queued());
            // Save three, then the fourth separately. Each capture fits, and the full 64MiB fits.
            UUID fourth = ids.iterator().next();
            ids.remove(fourth);
            publish(session, fixture.access(), ids);
            publish(session, fixture.access(), Set.of(fourth));
            var legal = fixture.store().load();
            assertEquals(4, legal.state().documents().size());
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(extra))));
            ProjectSaveDispatch rejected = session.dispatchSave().orElseThrow();
            assertTrue(rejected.job().isEmpty(), "one extra byte cannot create a project this runtime cannot read");
            assertEquals("editor:runtime_save/capture_bytes_exceeded", rejected.problems().get(0).code().toString());
            assertEquals(legal, fixture.store().load());
            assertTrue(small.hasUnpublishedChanges());
            assertEquals(0, session.saves().queued());
        }
    }

    @Test void activeAndQueuedCapturesAreBoundedAndTerminalConfirmationReleasesTheirBudget() throws Exception {
        byte[] large = new byte[BLOCK];
        TestType type = new TestType();
        type.encodeFunction = value -> large;
        try (var fixture = project(directory.resolve("retention-budget"), registry(type), List.of());
             var session = fixture.open()) {
            UUID id = UUID.randomUUID();
            session.create(id, TYPE, String.class, "large");
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            ProjectSaveJob active = session.dispatchSave().orElseThrow().job().orElseThrow();
            for (int index = 0; index < 7; index++) {
                session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            }
            var over = fixture.access().prepare(session.captureSave(Set.of(id)));
            var before = session.saves();
            reject("retained_bytes_exceeded", () -> session.enqueueSave(over));
            assertEquals(before, session.saves());
            SaveResult result = fixture.access().publish(active);
            assertEquals(SaveResult.Outcome.CONFIRMED, result.outcome());
            assertTrue(session.completeSave(active, result));
            assertDoesNotThrow(() -> session.enqueueSave(over), "terminal confirmation must release the active capture's bytes");
            assertEquals(8, session.saves().queued());
        }
    }

    @Test void queueCapacityRejectsAtomicallyAndAnAcceptedRejectionReleasesTheActiveSlot() throws Exception {
        try (var fixture = project(directory.resolve("queue-budget"), registry(new TestType()), List.of());
             var session = fixture.open()) {
            UUID id = UUID.randomUUID();
            session.create(id, TYPE, String.class, "note");
            for (int index = 0; index < 32; index++) {
                session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            }
            var over = fixture.access().prepare(session.captureSave(Set.of(id)));
            var before = session.saves();
            reject("queue_unavailable", () -> session.enqueueSave(over));
            assertEquals(before, session.saves());
            ProjectSaveJob active = session.dispatchSave().orElseThrow().job().orElseThrow();
            assertTrue(session.completeSave(active, new SaveResult(SaveResult.Outcome.REJECTED, null, null, List.of())));
            assertThrows(IllegalStateException.class, () -> fixture.access().publish(active));
            session.enqueueSave(over);
            assertEquals(32, session.saves().queued());
            assertEquals(fixture.loaded().saved(), fixture.store().load());
        }
    }

    private static void reject(String suffix, org.junit.jupiter.api.function.Executable call) {
        SessionRejection rejected = assertThrows(SessionRejection.class, call);
        assertEquals("editor:runtime_save/" + suffix, rejected.problems().get(0).code().toString());
    }

    private static void publish(ProjectSession session, LocalProjectAccess access, Set<UUID> ids) throws Exception {
        session.enqueueSave(access.prepare(session.captureSave(ids)));
        ProjectSaveJob job = session.dispatchSave().orElseThrow().job().orElseThrow();
        SaveResult result = access.publish(job);
        assertEquals(SaveResult.Outcome.CONFIRMED, result.outcome());
        assertTrue(session.completeSave(job, result));
    }
}
