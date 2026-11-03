package net.ptcrys.fpsmatch.editor.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.PublicationFaultStores;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for the Q01 save queue: intent ordering, candidate assembly against the latest
 * confirmed manifest, structural rejection before any I/O, validator integration, result
 * verification, the unknown and recovery states, closing, budgets and the local commit runner.
 *
 * <p>Tests that need real receipts and real files publish through {@link LocalProjectStore} and run
 * {@link LocalCommitRunner} against it; tests about ordering, faults and budgets use an in-memory
 * baseline, because the queue itself never touches storage.
 */
class ProjectSaveQueueTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String CORE = "editor:core";
    private static final String VERSION = "1.0.0";
    private static final String TEXT = "editor:text";
    private static final String UTF8 = "editor:utf8";
    private static final String IMAGE = "editor:image";
    private static final String PNG = "editor:png";

    private static final ProjectValidator ACCEPT = candidate -> List.of();

    private Path projectDirectory;
    private LocalProjectStore store;

    @BeforeEach
    void createProject() throws IOException {
        projectDirectory = Files.createTempDirectory("q01-save-queue").resolve("project");
        store = LocalProjectStore.create(projectDirectory);
    }

    @AfterEach
    void deleteProject() throws IOException {
        if (store != null) {
            store.close();
        }
        deleteTree(projectDirectory == null ? null : projectDirectory.getParent());
    }

    // ---------------------------------------------------------------- value contracts

    @Test
    void capacityIsBoundedAndBaselineAndValidatorAreRequired() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectSaveQueue(baseline, ACCEPT, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProjectSaveQueue(baseline, ACCEPT, 65));
        assertThrows(NullPointerException.class, () -> new ProjectSaveQueue(null, ACCEPT, 1));
        assertThrows(NullPointerException.class, () -> new ProjectSaveQueue(baseline, null, 1));

        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 64);
        SaveQueueView view = queue.view();
        assertEquals(SaveQueueView.State.READY, view.state());
        assertEquals(0, view.queued());
        assertTrue(view.active().isEmpty());
        assertEquals(baseline, view.confirmed());
    }

    @Test
    void aDeltaRejectsItsOwnInvalidShape() {
        UUID id = doc(1);
        ProjectState.Entry entry = document(id, fakeBlob(BlobRef.Kind.DOCUMENT, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectDelta(Optional.of("x".repeat(4097)),
                Map.of(), Map.of(), Set.of(), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectDelta(Optional.empty(),
                Map.of("Editor:Core", VERSION), Map.of(), Set.of(), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectDelta(Optional.empty(), Map.of(),
                Map.of(id, entry), Set.of(id), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectDelta(Optional.empty(), Map.of(),
                Map.of(doc(2), entry), Set.of(), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectDelta(Optional.empty(), Map.of(),
                Map.of(id, asset(id, fakeBlob(BlobRef.Kind.ASSET, 1))), Set.of(), Map.of(), Set.of()));

        Map<UUID, ProjectState.Entry> tooMany = new TreeMap<>();
        for (int i = 0; i <= Budgets.MAX_ENTRIES; i++) {
            UUID generated = doc(100_000 + i);
            tooMany.put(generated, document(generated, fakeBlob(BlobRef.Kind.DOCUMENT, 100_000L + i)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProjectDelta(Optional.empty(), Map.of(),
                tooMany, Set.of(), Map.of(), Set.of()));
    }

    @Test
    void aBlankNameIsRefusedAtTheBoundaryWithoutDisturbingTheQueue() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        for (String blank : List.of("", " ", "\t", " \t\n")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> queue.enqueue(named(blank)),
                    "a present but blank name must be refused: <" + blank + ">");
            assertTrue(refused.getMessage().contains("name"), refused.getMessage());
        }
        assertEquals(0, queue.view().queued(), "a refused delta leaves no queued request behind");
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(baseline, queue.view().confirmed(), "a refused delta confirms nothing");

        UUID requestId = queue.enqueue(named("  renamed  "));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals(requestId, attempt.requestId());
        assertEquals("  renamed  ", attempt.candidate().name(),
                "a legal name keeps the user's own text, padding included");
        assertTrue(queue.complete(attempt, new SaveResult(SaveResult.Outcome.CONFIRMED,
                new SavedProject(requestId, attempt.expectedCommitId(), attempt.candidate()), requestId,
                List.of())));
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(0, queue.view().queued());
        assertEquals("  renamed  ", queue.view().confirmed().state().name());
    }

    @Test
    void aDeltaCopiesAndFreezesItsSources() {
        Map<UUID, ProjectState.Entry> documents = new HashMap<>();
        UUID id = doc(1);
        documents.put(id, document(id, fakeBlob(BlobRef.Kind.DOCUMENT, 1)));
        Set<UUID> removed = new HashSet<>();
        ProjectDelta delta = new ProjectDelta(Optional.of("name"), Map.of(), documents, removed,
                Map.of(), Set.of());
        documents.clear();
        removed.add(doc(3));

        assertTrue(delta.documents().containsKey(id));
        assertTrue(delta.removedDocuments().isEmpty());
        assertEquals(Optional.of("name"), delta.name());
        assertThrows(UnsupportedOperationException.class,
                () -> delta.documents().put(doc(4), document(doc(4), fakeBlob(BlobRef.Kind.DOCUMENT, 4))));
        assertThrows(UnsupportedOperationException.class, () -> delta.removedDocuments().add(doc(5)));
    }

    @Test
    void dispatchAndResultRecordsEnforceTheirContracts() {
        UUID requestId = UUID.randomUUID();
        SaveAttempt attempt = new SaveAttempt(UUID.randomUUID(), requestId, UUID.randomUUID(),
                state(Map.of(), Map.of()));
        Problem error = problem(Problem.Severity.ERROR, "editor:save/test_error");

        assertThrows(IllegalArgumentException.class,
                () -> new SaveDispatch(requestId, Optional.of(attempt), List.of(error)));
        assertThrows(IllegalArgumentException.class,
                () -> new SaveDispatch(requestId, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SaveDispatch(UUID.randomUUID(), Optional.of(attempt), List.of()));
        assertEquals(Optional.of(attempt), new SaveDispatch(requestId, Optional.of(attempt), List.of()).attempt());

        assertThrows(NullPointerException.class,
                () -> new SaveResult(SaveResult.Outcome.CONFIRMED, null, requestId, List.of()));
        SavedProject published = saved(requestId, attempt.candidate());
        assertThrows(IllegalArgumentException.class,
                () -> new SaveResult(SaveResult.Outcome.REJECTED, published, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SaveResult(SaveResult.Outcome.CONFIRMED, published, UUID.randomUUID(), List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> new SaveQueueView(SaveQueueView.State.READY, 0, Optional.of(attempt), published));
        assertThrows(IllegalArgumentException.class,
                () -> new SaveQueueView(SaveQueueView.State.PUBLISHING, -1, Optional.of(attempt), published));
        assertThrows(NullPointerException.class,
                () -> new SaveQueueView(SaveQueueView.State.READY, 0, Optional.empty(), null));
    }

    // ---------------------------------------------------------------- candidate assembly

    @Test
    void aCandidateIsBuiltFromTheLatestConfirmedManifestWhenTheHeadIsDispatched() throws IOException {
        ProjectState.Entry published = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(published));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        ProjectState.Entry first = storedDocument(doc(1), "first");
        ProjectState.Entry second = storedDocument(doc(2), "second");
        UUID firstId = queue.enqueue(addingDocuments(first));
        UUID secondId = queue.enqueue(addingDocuments(second));

        SaveDispatch firstDispatch = queue.dispatch().orElseThrow();
        SaveAttempt firstAttempt = firstDispatch.attempt().orElseThrow();
        assertEquals(firstId, firstDispatch.requestId());
        assertEquals(firstId, firstAttempt.requestId());
        assertEquals(baseline.commitId(), firstAttempt.expectedCommitId());
        assertTrue(firstDispatch.problems().isEmpty());
        assertTrue(firstAttempt.candidate().documents().containsKey(published.id()));
        assertTrue(firstAttempt.candidate().documents().containsKey(first.id()));
        assertFalse(firstAttempt.candidate().documents().containsKey(second.id()));

        SaveResult firstResult = LocalCommitRunner.run(store, firstAttempt);
        assertEquals(SaveResult.Outcome.CONFIRMED, firstResult.outcome());
        assertTrue(queue.complete(firstAttempt, firstResult));
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(firstResult.saved(), queue.view().confirmed());

        SaveDispatch secondDispatch = queue.dispatch().orElseThrow();
        SaveAttempt secondAttempt = secondDispatch.attempt().orElseThrow();
        assertEquals(secondId, secondAttempt.requestId());
        assertEquals(firstId, secondAttempt.expectedCommitId(), "the second intent waits for the first publication");
        Map<UUID, ProjectState.Entry> expected = new TreeMap<>(firstAttempt.candidate().documents());
        expected.put(second.id(), second);
        assertEquals(expected, secondAttempt.candidate().documents());

        SaveResult secondResult = LocalCommitRunner.run(store, secondAttempt);
        assertEquals(SaveResult.Outcome.CONFIRMED, secondResult.outcome());
        assertTrue(queue.complete(secondAttempt, secondResult));
        assertEquals(secondAttempt.candidate(), store.load().state());
    }

    @Test
    void aQueuedRemovalAppliesToContentConfirmedAfterTheIntentWasQueued() throws IOException {
        SavedProject baseline = publish(stateOf(storedDocument(doc(0), "base")));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID saveId = queue.enqueue(addingDocuments(storedDocument(doc(1), "first")));
        UUID removalId = queue.enqueue(removalsOf(doc(1)));

        SaveAttempt saveAttempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        SaveResult saveResult = LocalCommitRunner.run(store, saveAttempt);
        assertEquals(SaveResult.Outcome.CONFIRMED, saveResult.outcome());
        assertTrue(queue.complete(saveAttempt, saveResult));

        SaveAttempt removalAttempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertNotEquals(saveId, removalId);
        assertEquals(saveId, removalAttempt.expectedCommitId());
        assertTrue(removalAttempt.candidate().documents().containsKey(doc(0)),
                "content confirmed after the intent was queued is not rolled back");
        assertFalse(removalAttempt.candidate().documents().containsKey(doc(1)),
                "the queued removal is applied to the newest confirmed manifest");
        assertEquals(baseline.state().documents(), removalAttempt.candidate().documents());

        SaveResult removalResult = LocalCommitRunner.run(store, removalAttempt);
        assertEquals(SaveResult.Outcome.CONFIRMED, removalResult.outcome());
        assertTrue(queue.complete(removalAttempt, removalResult));
        assertEquals(baseline.state().documents(), store.load().state().documents());
    }

    @Test
    void dispatchIsFifoAndOnlyOneRequestIsInFlight() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID first = queue.enqueue(addingDocuments(document(doc(1), fakeBlob(BlobRef.Kind.DOCUMENT, 1))));
        UUID second = queue.enqueue(addingDocuments(document(doc(2), fakeBlob(BlobRef.Kind.DOCUMENT, 2))));

        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals(first, attempt.requestId());
        assertEquals(SaveQueueView.State.PUBLISHING, queue.view().state());
        assertEquals(1, queue.view().queued());
        assertTrue(queue.dispatch().isEmpty(), "only one request may be in flight");
        UUID third = queue.enqueue(addingDocuments(document(doc(3), fakeBlob(BlobRef.Kind.DOCUMENT, 3))));
        assertNotEquals(first, third);
        assertEquals(2, queue.view().queued(), "editing continues while a request is in flight");
        assertTrue(queue.dispatch().isEmpty());

        assertTrue(queue.complete(attempt, rejected()));
        SaveAttempt next = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals(second, next.requestId());
        assertEquals(baseline.commitId(), next.expectedCommitId());
    }

    @Test
    void requestIdsAreFreshAndNeverRepeatTheBaseline() {
        UUID baselineId = UUID.randomUUID();
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(baselineId, state(Map.of(), Map.of())), ACCEPT, 8);
        Set<UUID> issued = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            UUID requestId = queue.enqueue(delta());
            assertNotEquals(baselineId, requestId);
            assertTrue(issued.add(requestId), "request ids must not repeat");
        }
        assertEquals(4, queue.view().queued());

        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        UUID queued = queue.enqueue(delta());
        assertNotEquals(attempt.requestId(), queued, "an in-flight request stays reserved while it publishes");
        assertNotEquals(baselineId, queued);
        assertTrue(issued.add(queued));

        assertTrue(queue.complete(attempt, rejected()));
        assertTrue(issued.add(queue.enqueue(delta())), "the line keeps handing out fresh identities");
    }

    @Test
    void aCandidateKeepsProjectIdentityOriginAndAppliesOnlyTheRequestedName() {
        ProjectState.Origin origin = new ProjectState.Origin(PROJECT_ID, UUID.randomUUID());
        ProjectState base = new ProjectState(PROJECT_ID, "original", Map.of(CORE, VERSION), Map.of(),
                Map.of(), origin);
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), base), ACCEPT, 4);

        queue.enqueue(new ProjectDelta(Optional.of("renamed"), Map.of(), Map.of(), Set.of(), Map.of(), Set.of()));
        SaveAttempt renamed = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals("renamed", renamed.candidate().name());
        assertEquals(PROJECT_ID, renamed.candidate().projectId());
        assertEquals(origin, renamed.candidate().origin());
        assertEquals(Map.of(CORE, VERSION), renamed.candidate().modules());
        assertTrue(queue.complete(renamed, rejected()));

        queue.enqueue(delta());
        SaveAttempt kept = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals("original", kept.candidate().name(), "an intent without a name keeps the confirmed name");
        assertEquals(base, kept.candidate());
    }

    @Test
    void moduleAdditionsMayRestateAVersionAndRejectAChangedOne() {
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                ACCEPT, 4);

        queue.enqueue(new ProjectDelta(Optional.empty(), Map.of(CORE, VERSION), Map.of(), Set.of(),
                Map.of(), Set.of()));
        SaveAttempt restated = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals(VERSION, restated.candidate().modules().get(CORE));
        assertTrue(queue.complete(restated, rejected()));

        queue.enqueue(new ProjectDelta(Optional.empty(), Map.of(CORE, "2.0.0"), Map.of(), Set.of(),
                Map.of(), Set.of()));
        SaveDispatch changed = queue.dispatch().orElseThrow();
        assertTrue(changed.attempt().isEmpty());
        assertCode(changed.problems().get(0), "editor:save/module_version_conflict");
        assertEquals("editor.save.module_version_conflict",
                assertInstanceOf(TextContent.Message.class, changed.problems().get(0).message()).key());
        assertArgument(changed.problems().get(0).message(), "2.0.0");
        assertEquals(0, queue.view().queued());
        assertEquals(SaveQueueView.State.READY, queue.view().state());

        queue.enqueue(new ProjectDelta(Optional.empty(), Map.of("editor:extra", VERSION), Map.of(),
                Set.of(), Map.of(), Set.of()));
        assertTrue(queue.dispatch().orElseThrow().attempt().isPresent());
    }

    @Test
    void documentAndAssetIdentitiesMustNotCross() {
        UUID shared = doc(3);
        ProjectState mixed = new ProjectState(PROJECT_ID, "project", Map.of(CORE, VERSION),
                Map.of(shared, document(shared, fakeBlob(BlobRef.Kind.DOCUMENT, 3))), Map.of(), null);
        SavedProject mixedBaseline = saved(UUID.randomUUID(), mixed);
        ProjectSaveQueue queue = new ProjectSaveQueue(mixedBaseline, ACCEPT, 4);
        queue.enqueue(assetDelta(asset(shared, fakeBlob(BlobRef.Kind.ASSET, 3))));
        assertCode(dropped(queue).problems().get(0), "editor:save/entry_identity_conflict");
        assertEquals(mixedBaseline, queue.view().confirmed());

        ProjectState assetsOnly = new ProjectState(PROJECT_ID, "project", Map.of(CORE, VERSION), Map.of(),
                Map.of(shared, asset(shared, fakeBlob(BlobRef.Kind.ASSET, 4))), null);
        ProjectSaveQueue reverse = new ProjectSaveQueue(saved(UUID.randomUUID(), assetsOnly), ACCEPT, 4);
        reverse.enqueue(delta(Map.of(shared, document(shared, fakeBlob(BlobRef.Kind.DOCUMENT, 5)))));
        assertCode(dropped(reverse).problems().get(0), "editor:save/entry_identity_conflict");
    }

    @Test
    void anEntryOwnerMustExistInTheModuleRequirements() {
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                ACCEPT, 4);
        UUID id = doc(9);
        ProjectState.Entry foreign = new ProjectState.Entry(id, "editor:absent", TEXT, UTF8, 1, "doc",
                fakeBlob(BlobRef.Kind.DOCUMENT, 9), Set.of(), Set.of());
        queue.enqueue(delta(Map.of(id, foreign)));

        SaveDispatch dropped = queue.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertCode(dropped.problems().get(0), "editor:save/missing_owner");
        assertEquals(id.toString(), dropped.problems().get(0).target());
        assertEquals(0, queue.view().queued());
    }

    @Test
    void aDanglingReferenceRejectsTheHeadWithoutPublishingAnything() throws IOException {
        SavedProject baseline = publish(stateOf(storedDocument(doc(0), "base")));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        BlobRef staged = store.stage(BlobRef.Kind.DOCUMENT, "first".getBytes(StandardCharsets.UTF_8));
        UUID requestId = queue.enqueue(addingDocuments(document(doc(1), staged, doc(2))));

        SaveDispatch dropped = queue.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertEquals(requestId, dropped.requestId());
        assertCode(dropped.problems().get(0), "editor:save/missing_dependency");
        assertEquals(doc(1).toString(), dropped.problems().get(0).target());
        assertEquals("editor.save.missing_document_dependency", messageKey(dropped.problems().get(0)));
        assertLiteralArguments(dropped.problems().get(0),
                requestId.toString(), doc(1).toString(), doc(2).toString());
        assertFalse(carriesArgument(dropped.problems().get(0), "DOCUMENT"),
                "the category is part of the sentence, not a translatable argument");

        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(0, queue.view().queued());
        assertTrue(queue.view().active().isEmpty());
        assertEquals(baseline, queue.view().confirmed());
        assertEquals(baseline, store.load(), "a rejected head is never saved automatically");
        assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, store.lookup(requestId).status());
    }

    @Test
    void aDanglingAssetReferenceUsesTheAssetSentence() throws IOException {
        SavedProject baseline = publish(stateOf(storedDocument(doc(0), "base")));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        BlobRef staged = store.stage(BlobRef.Kind.DOCUMENT, "first".getBytes(StandardCharsets.UTF_8));
        UUID entryId = doc(1);
        UUID missingAsset = asset(doc(21), fakeBlob(BlobRef.Kind.ASSET, 21)).id();
        UUID requestId = queue.enqueue(addingDocuments(new ProjectState.Entry(entryId, CORE, TEXT, UTF8, 1,
                "document " + entryId, staged, Set.of(), Set.of(missingAsset))));

        SaveDispatch dropped = queue.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertEquals(requestId, dropped.requestId());
        assertCode(dropped.problems().get(0), "editor:save/missing_dependency");
        assertEquals(entryId.toString(), dropped.problems().get(0).target());
        assertEquals("editor.save.missing_asset_dependency", messageKey(dropped.problems().get(0)));
        assertLiteralArguments(dropped.problems().get(0),
                requestId.toString(), entryId.toString(), missingAsset.toString());
        assertFalse(carriesArgument(dropped.problems().get(0), "ASSET"),
                "the category is part of the sentence, not a translatable argument");

        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(0, queue.view().queued());
        assertEquals(baseline, queue.view().confirmed());
        assertEquals(baseline, store.load(), "a rejected head is never saved automatically");
    }

    @Test
    void ordinaryDocumentCyclesAreAccepted() {
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                ACCEPT, 4);
        UUID a = doc(11);
        UUID b = doc(12);
        Map<UUID, ProjectState.Entry> documents = new TreeMap<>();
        documents.put(a, document(a, fakeBlob(BlobRef.Kind.DOCUMENT, 11), b));
        documents.put(b, document(b, fakeBlob(BlobRef.Kind.DOCUMENT, 12), a));
        queue.enqueue(delta(documents));

        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals(2, attempt.candidate().documents().size());
        assertEquals(SaveQueueView.State.PUBLISHING, queue.view().state());
    }

    // ---------------------------------------------------------------- validator

    @Test
    void validatorErrorsRejectTheHeadAndWarningsTravelWithAnAttempt() {
        Problem error = problem(Problem.Severity.ERROR, "editor:save/test_error");
        Problem warning = problem(Problem.Severity.WARNING, "editor:save/test_warning");

        ProjectSaveQueue strict = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                candidate -> List.of(error, warning), 4);
        UUID refused = strict.enqueue(delta());
        SaveDispatch dropped = strict.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertEquals(refused, dropped.requestId());
        assertEquals(List.of(error, warning), dropped.problems());
        assertEquals(SaveQueueView.State.READY, strict.view().state());
        assertEquals(0, strict.view().queued());
        assertTrue(strict.view().active().isEmpty());

        ProjectSaveQueue lenient = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                candidate -> List.of(warning), 4);
        lenient.enqueue(delta());
        SaveDispatch dispatched = lenient.dispatch().orElseThrow();
        assertEquals(List.of(warning), dispatched.problems());
        SaveAttempt attempt = dispatched.attempt().orElseThrow();
        assertTrue(lenient.complete(attempt, rejected()));
        assertEquals(SaveQueueView.State.READY, lenient.view().state());
    }

    @Test
    void aValidatorFailureBecomesATechnicalDiagnosticAndReleasesTheQueue() {
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                candidate -> {
                    throw new IllegalStateException("validator exploded");
                }, 4);
        UUID requestId = queue.enqueue(delta());

        SaveDispatch dropped = queue.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertEquals(requestId, dropped.requestId());
        assertCode(dropped.problems().get(0), "editor:save/validator_failed");
        assertEquals(requestId.toString(), dropped.problems().get(0).target());
        assertTrue(carriesArgument(dropped.problems().get(0),
                "java.lang.IllegalStateException: validator exploded"));
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(0, queue.view().queued());
        assertTrue(queue.view().active().isEmpty());

        queue.enqueue(delta());
        assertEquals(1, queue.view().queued(), "a failed callback does not block later requests");
    }

    @Test
    void validatorReentrancyIsRefusedAndTheGuardIsReleased() {
        AtomicReference<ProjectSaveQueue> holder = new AtomicReference<>();
        AtomicReference<SaveQueueView> inside = new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        holder.set(new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())), candidate -> {
            inside.set(holder.get().view());
            try {
                holder.get().enqueue(delta());
            } catch (Throwable failure) {
                refused.set(failure);
            }
            return List.of();
        }, 4));

        ProjectSaveQueue queue = holder.get();
        queue.enqueue(delta());
        SaveDispatch dispatched = queue.dispatch().orElseThrow();
        SaveAttempt attempt = dispatched.attempt().orElseThrow();
        assertEquals(SaveQueueView.State.READY, inside.get().state(), "the view shows the last published state");
        assertEquals(1, inside.get().queued());
        assertInstanceOf(IllegalStateException.class, refused.get(), "re-entrant enqueue must be refused");
        assertEquals(0, dispatched.problems().size());
        assertTrue(queue.complete(attempt, rejected()));
        UUID next = queue.enqueue(delta());
        assertNotEquals(attempt.requestId(), next, "the guard is released after the callback");

        AtomicReference<ProjectSaveQueue> escaping = new AtomicReference<>();
        escaping.set(new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())), candidate -> {
            escaping.get().enqueue(delta());
            return List.of();
        }, 4));
        escaping.get().enqueue(delta());
        SaveDispatch dropped = escaping.get().dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertCode(dropped.problems().get(0), "editor:save/validator_failed");
        assertEquals(0, escaping.get().view().queued());
        escaping.get().enqueue(delta());
        assertEquals(1, escaping.get().view().queued(), "the guard is released even when the callback escapes");
    }

    // ---------------------------------------------------------------- result verification

    @Test
    void aResultMustBelongToTheActiveRequest() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(document(doc(1), fakeBlob(BlobRef.Kind.DOCUMENT, 1))));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        SaveAttempt stranger = new SaveAttempt(attempt.sessionId(), UUID.randomUUID(),
                attempt.expectedCommitId(), attempt.candidate());
        assertFalse(queue.complete(stranger, rejected()));

        SaveAttempt foreignSession = new SaveAttempt(UUID.randomUUID(), attempt.requestId(),
                attempt.expectedCommitId(), attempt.candidate());
        assertFalse(queue.complete(foreignSession, rejected()));

        SaveAttempt foreignCandidate = new SaveAttempt(attempt.sessionId(), attempt.requestId(),
                attempt.expectedCommitId(), state(Map.of(), Map.of()));
        assertFalse(queue.complete(foreignCandidate, rejected()));

        assertEquals(SaveQueueView.State.PUBLISHING, queue.view().state());
        assertEquals(Optional.of(attempt), queue.view().active());
        assertEquals(baseline, queue.view().confirmed());
        assertEquals(requestId, attempt.requestId());
        assertTrue(queue.complete(attempt, rejected()));
    }

    @Test
    void successRequiresCommitParentCandidateAndCurrentToAgree() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(document(doc(1), fakeBlob(BlobRef.Kind.DOCUMENT, 1))));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        UUID foreignCommit = UUID.randomUUID();
        SavedProject foreign = new SavedProject(foreignCommit, attempt.expectedCommitId(), attempt.candidate());
        assertFalse(queue.complete(attempt,
                new SaveResult(SaveResult.Outcome.CONFIRMED, foreign, foreignCommit, List.of())));

        SavedProject wrongParent = new SavedProject(requestId, UUID.randomUUID(), attempt.candidate());
        assertFalse(queue.complete(attempt,
                new SaveResult(SaveResult.Outcome.CONFIRMED, wrongParent, requestId, List.of())));

        ProjectState otherState = new ProjectState(PROJECT_ID, "other", Map.of(CORE, VERSION),
                attempt.candidate().documents(), Map.of(), null);
        SavedProject wrongState = new SavedProject(requestId, attempt.expectedCommitId(), otherState);
        assertFalse(queue.complete(attempt,
                new SaveResult(SaveResult.Outcome.CONFIRMED, wrongState, requestId, List.of())));

        assertEquals(SaveQueueView.State.PUBLISHING, queue.view().state());
        assertEquals(baseline, queue.view().confirmed());
        assertEquals(Optional.of(attempt), queue.view().active());

        SavedProject good = new SavedProject(requestId, attempt.expectedCommitId(), attempt.candidate());
        assertTrue(queue.complete(attempt,
                new SaveResult(SaveResult.Outcome.CONFIRMED, good, requestId, List.of())));
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(good, queue.view().confirmed());
    }

    @Test
    void aRejectionOnlyReleasesAPublishingRequest() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue publishing = new ProjectSaveQueue(baseline, ACCEPT, 4);
        publishing.enqueue(delta());
        SaveAttempt attempt = publishing.dispatch().orElseThrow().attempt().orElseThrow();
        assertTrue(publishing.complete(attempt, rejected()));
        assertEquals(SaveQueueView.State.READY, publishing.view().state());
        assertTrue(publishing.view().active().isEmpty());
        assertFalse(publishing.complete(attempt, rejected()), "a finished request accepts no second report");

        ProjectSaveQueue unresolved = new ProjectSaveQueue(baseline, ACCEPT, 4);
        unresolved.enqueue(delta());
        SaveAttempt inFlight = unresolved.dispatch().orElseThrow().attempt().orElseThrow();
        assertTrue(unresolved.complete(inFlight, unknown()));
        assertFalse(unresolved.complete(inFlight, rejected()),
                "an unresolved publication is never downgraded to a rejection");
        assertEquals(SaveQueueView.State.UNKNOWN, unresolved.view().state());
        assertEquals(Optional.of(inFlight), unresolved.view().active());
    }

    @Test
    void unknownBlocksNewWorkUntilTheSameRequestIsResolved() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(document(doc(1), fakeBlob(BlobRef.Kind.DOCUMENT, 1))));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        assertTrue(queue.complete(attempt, unknown()));
        SaveQueueView view = queue.view();
        assertEquals(SaveQueueView.State.UNKNOWN, view.state());
        assertEquals(Optional.of(attempt), view.active());
        assertEquals(baseline, view.confirmed());
        assertThrows(IllegalStateException.class,
                () -> queue.enqueue(addingDocuments(document(doc(2), fakeBlob(BlobRef.Kind.DOCUMENT, 2)))));
        assertTrue(queue.dispatch().isEmpty());
        assertThrows(IllegalStateException.class, queue::close);

        SavedProject published = new SavedProject(requestId, attempt.expectedCommitId(), attempt.candidate());
        assertTrue(queue.complete(attempt,
                new SaveResult(SaveResult.Outcome.CONFIRMED, published, requestId, List.of())),
                "the same request resolves the unknown result");
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(published, queue.view().confirmed());
        queue.enqueue(addingDocuments(document(doc(2), fakeBlob(BlobRef.Kind.DOCUMENT, 2))));
        assertEquals(1, queue.view().queued());
    }

    @Test
    void conflictKeepsTheActiveRequestAndFreezesRecoveryTerminally() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(document(doc(1), fakeBlob(BlobRef.Kind.DOCUMENT, 1))));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        assertTrue(queue.complete(attempt, new SaveResult(SaveResult.Outcome.CONFLICT, null,
                UUID.randomUUID(), List.of(problem(Problem.Severity.ERROR, "editor:save/conflict")))));
        SaveQueueView view = queue.view();
        assertEquals(SaveQueueView.State.RECOVERY, view.state());
        assertEquals(Optional.of(attempt), view.active());
        assertEquals(baseline, view.confirmed());
        assertThrows(IllegalStateException.class,
                () -> queue.enqueue(addingDocuments(document(doc(2), fakeBlob(BlobRef.Kind.DOCUMENT, 2)))));
        assertTrue(queue.dispatch().isEmpty());
        assertThrows(IllegalStateException.class, queue::close);
        assertFalse(queue.complete(attempt, rejected()));

        SavedProject published = new SavedProject(requestId, attempt.expectedCommitId(), attempt.candidate());
        assertFalse(queue.complete(attempt,
                new SaveResult(SaveResult.Outcome.CONFIRMED, published, requestId, List.of())),
                "recovery is terminal even when a matching receipt arrives later");
        assertFalse(queue.complete(attempt, unknown()));
        assertEquals(SaveQueueView.State.RECOVERY, queue.view().state());
        assertEquals(baseline, queue.view().confirmed());
    }

    @Test
    void anUnknownResultMayStillTurnIntoARecoveryConflict() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        queue.enqueue(delta());
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertTrue(queue.complete(attempt, unknown()));
        assertTrue(queue.complete(attempt, new SaveResult(SaveResult.Outcome.CONFLICT, null,
                UUID.randomUUID(), List.of())));
        assertEquals(SaveQueueView.State.RECOVERY, queue.view().state());
        assertEquals(Optional.of(attempt), queue.view().active());
        assertFalse(queue.complete(attempt, rejected()));
    }

    // ---------------------------------------------------------------- closing and ownership

    @Test
    void closeRefusesWhileARequestIsActiveAndCancelsTheLineOnce() {
        SavedProject baseline = saved(UUID.randomUUID(), state(Map.of(), Map.of()));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        queue.enqueue(delta());
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertThrows(IllegalStateException.class, queue::close);
        assertTrue(queue.complete(attempt, rejected()));

        UUID second = queue.enqueue(delta());
        UUID third = queue.enqueue(delta());
        assertEquals(List.of(second, third), queue.close());
        assertEquals(SaveQueueView.State.CLOSED, queue.view().state());
        assertEquals(0, queue.view().queued());
        assertTrue(queue.view().active().isEmpty());
        assertEquals(baseline, queue.view().confirmed());
        assertEquals(List.of(), queue.close(), "closing twice is a no-op");
        assertThrows(IllegalStateException.class, () -> queue.enqueue(delta()));
        assertTrue(queue.dispatch().isEmpty());
        assertFalse(queue.complete(attempt, rejected()));
    }

    @Test
    void onlyTheOwnerThreadMayDriveTheQueue() throws InterruptedException {
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                ACCEPT, 4);
        queue.enqueue(addingDocuments(document(doc(1), fakeBlob(BlobRef.Kind.DOCUMENT, 1))));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        List<Runnable> calls = List.of(
                () -> queue.view(),
                () -> queue.enqueue(delta()),
                queue::dispatch,
                () -> queue.close(),
                () -> queue.complete(attempt, rejected()));
        for (Runnable call : calls) {
            AtomicReference<Throwable> observed = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    call.run();
                } catch (Throwable failure) {
                    observed.set(failure);
                }
            });
            worker.start();
            worker.join();
            assertInstanceOf(IllegalStateException.class, observed.get(), "foreign threads must be refused");
        }

        assertEquals(SaveQueueView.State.PUBLISHING, queue.view().state());
        assertEquals(Optional.of(attempt), queue.view().active());
        assertTrue(queue.complete(attempt, rejected()), "the owner thread keeps working");
    }

    @Test
    void capacityIsCheckedBeforeAnythingIsAdded() {
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                ACCEPT, 1);
        UUID first = queue.enqueue(delta());
        assertThrows(IllegalStateException.class, () -> queue.enqueue(delta()));
        assertEquals(1, queue.view().queued());
        assertEquals(first, queue.dispatch().orElseThrow().requestId());
    }

    // ---------------------------------------------------------------- budgets

    @Test
    void thePendingEntryBudgetIsCheckedBeforeAnythingIsAdded() {
        ProjectSaveQueue queue = new ProjectSaveQueue(saved(UUID.randomUUID(), state(Map.of(), Map.of())),
                ACCEPT, 64);
        List<UUID> accepted = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            accepted.add(queue.enqueue(removing(1_000_000 + i * Budgets.MAX_ENTRIES, Budgets.MAX_ENTRIES)));
        }
        assertEquals(8, queue.view().queued());
        assertThrows(IllegalStateException.class,
                () -> queue.enqueue(removing(9_000_000, Budgets.MAX_ENTRIES)));
        assertEquals(8, queue.view().queued(), "a refused enqueue adds nothing");
        assertEquals(accepted, queue.close());
    }

    @Test
    void theEntryBudgetRejectsTheCandidateWithoutTouchingConfirmedState() {
        SavedProject baseline = saved(UUID.randomUUID(), largeState(Budgets.MAX_ENTRIES - 1));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);

        UUID last = doc(900_000);
        queue.enqueue(assetDelta(asset(last, fakeBlob(BlobRef.Kind.ASSET, 900_000))));
        SaveAttempt atLimit = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals(Budgets.MAX_ENTRIES, totalEntries(atLimit.candidate()));
        assertTrue(queue.complete(atLimit, rejected()));

        UUID over = doc(900_001);
        UUID alsoOver = doc(900_002);
        queue.enqueue(assetDelta(asset(over, fakeBlob(BlobRef.Kind.ASSET, 900_001)),
                asset(alsoOver, fakeBlob(BlobRef.Kind.ASSET, 900_002))));
        SaveDispatch dropped = queue.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertCode(dropped.problems().get(0), "editor:save/entry_budget_exceeded");
        assertEquals("editor.save.entry_budget_exceeded", messageKey(dropped.problems().get(0)));
        assertBudgetArguments(dropped.problems().get(0), dropped.requestId(),
                Budgets.MAX_ENTRIES + 1L, Budgets.MAX_ENTRIES);
        assertEquals(0, queue.view().queued());
        assertEquals(baseline, queue.view().confirmed());
    }

    @Test
    void theDependencyBudgetRejectsTheCandidateAtTheEdgeLimit() {
        int perEntry = 256;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < perEntry; i++) {
            ids.add(doc(5000 + i));
        }
        SavedProject baseline = saved(UUID.randomUUID(), denseDependencyState(perEntry));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);

        queue.enqueue(delta());
        SaveAttempt atLimit = queue.dispatch().orElseThrow().attempt().orElseThrow();
        assertEquals(perEntry * perEntry, totalDependencies(atLimit.candidate()));
        assertTrue(queue.complete(atLimit, rejected()));

        UUID extra = doc(6000);
        queue.enqueue(addingDocuments(document(extra, fakeBlob(BlobRef.Kind.DOCUMENT, 6000), ids.get(0))));
        SaveDispatch dropped = queue.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty());
        assertCode(dropped.problems().get(0), "editor:save/dependency_budget_exceeded");
        assertEquals("editor.save.dependency_budget_exceeded", messageKey(dropped.problems().get(0)));
        assertBudgetArguments(dropped.problems().get(0), dropped.requestId(),
                perEntry * perEntry + 1L, Budgets.MAX_DEPENDENCIES);
        assertEquals(baseline, queue.view().confirmed());
    }

    // ---------------------------------------------------------------- language resources

    @Test
    void bothLanguagesCarryTheSameSaveKeysIncludingBothDependencySentences() {
        Map<String, String> zh = LangResources.load("zh_cn");
        Map<String, String> en = LangResources.load("en_us");
        assertEquals(zh.keySet(), en.keySet(), "both languages carry exactly the same keys");
        assertEquals(expectedSaveKeys(), en.keySet());
        assertEquals(EditorId.parse("editor:save/missing_dependency"), Messages.MISSING_DEPENDENCY,
                "the problem code stays as it was, only the sentence splits");
        assertEquals("editor.save.missing_document_dependency", Messages.KEY_MISSING_DOCUMENT_DEPENDENCY);
        assertEquals("editor.save.missing_asset_dependency", Messages.KEY_MISSING_ASSET_DEPENDENCY);
        assertTrue(zh.containsKey(Messages.KEY_MISSING_DOCUMENT_DEPENDENCY), "document sentence is in the file");
        assertTrue(zh.containsKey(Messages.KEY_MISSING_ASSET_DEPENDENCY), "asset sentence is in the file");
        assertFalse(zh.containsKey("editor.save.missing_dependency"),
                "the unpublished generic dependency sentence is gone");

        for (String key : List.of(Messages.KEY_MISSING_DOCUMENT_DEPENDENCY,
                Messages.KEY_MISSING_ASSET_DEPENDENCY, "editor.save.entry_budget_exceeded",
                "editor.save.dependency_budget_exceeded")) {
            assertEquals(Set.of(0, 1, 2), LangResources.placeholders(zh.get(key)), key);
            assertEquals(Set.of(0, 1, 2), LangResources.placeholders(en.get(key)), key);
        }
        assertFalse(zh.get(Messages.KEY_MISSING_DOCUMENT_DEPENDENCY).contains("DOCUMENT"));
        assertFalse(zh.get(Messages.KEY_MISSING_ASSET_DEPENDENCY).contains("ASSET"));
    }

    @Test
    void realDependencyProblemsRenderBothSentencesFromTheRealLanguageFiles() {
        Map<String, String> zh = LangResources.load("zh_cn");
        Map<String, String> en = LangResources.load("en_us");
        for (BlobRef.Kind kind : List.of(BlobRef.Kind.DOCUMENT, BlobRef.Kind.ASSET)) {
            UUID entryId = doc(1);
            UUID dependencyId = doc(2);
            SaveDispatch dropped = danglingDependency(kind, entryId, dependencyId);
            Problem problem = dropped.problems().get(0);
            assertEquals(kind == BlobRef.Kind.DOCUMENT ? Messages.KEY_MISSING_DOCUMENT_DEPENDENCY
                    : Messages.KEY_MISSING_ASSET_DEPENDENCY, messageKey(problem));
            assertLiteralArguments(problem, dropped.requestId().toString(), entryId.toString(),
                    dependencyId.toString());

            String nounEn = kind == BlobRef.Kind.DOCUMENT ? "document" : "asset";
            String nounZh = kind == BlobRef.Kind.DOCUMENT ? "文档" : "资源";
            TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
            assertEquals("Entry " + entryId + " of request " + dropped.requestId() + " depends on " + nounEn
                    + " " + dependencyId + ", which the candidate does not contain;"
                    + " dangling references are rejected.", LangResources.render(message, en.get(message.key())));
            String chinese = LangResources.render(message, zh.get(message.key()));
            assertEquals("请求 " + dropped.requestId() + " 的条目 " + entryId + " 依赖" + nounZh + " "
                    + dependencyId + "，但候选中不存在该身份；悬空引用被拒绝。", chinese);
            assertFalse(chinese.contains("DOCUMENT") || chinese.contains("ASSET"),
                    "the Chinese sentence never falls back to the category code: " + chinese);
        }
    }

    @Test
    void realBudgetProblemsRenderTheSameTypedNumbersInBothLanguages() {
        Map<String, String> zh = LangResources.load("zh_cn");
        Map<String, String> en = LangResources.load("en_us");

        ProjectSaveQueue queue = new ProjectSaveQueue(
                saved(UUID.randomUUID(), largeState(Budgets.MAX_ENTRIES)), ACCEPT, 4);
        queue.enqueue(assetDelta(asset(doc(900_200), fakeBlob(BlobRef.Kind.ASSET, 900_200))));
        SaveDispatch entries = queue.dispatch().orElseThrow();
        assertTrue(entries.attempt().isEmpty());
        assertBudgetArguments(entries.problems().get(0), entries.requestId(),
                Budgets.MAX_ENTRIES + 1L, Budgets.MAX_ENTRIES);
        assertRendersNumbers(entries.problems().get(0), en, zh, Budgets.MAX_ENTRIES + 1L, Budgets.MAX_ENTRIES);

        int perEntry = 256;
        ProjectSaveQueue dense = new ProjectSaveQueue(
                saved(UUID.randomUUID(), denseDependencyState(perEntry)), ACCEPT, 4);
        dense.enqueue(addingDocuments(
                document(doc(7000), fakeBlob(BlobRef.Kind.DOCUMENT, 7000), doc(5000))));
        SaveDispatch edges = dense.dispatch().orElseThrow();
        assertTrue(edges.attempt().isEmpty());
        assertBudgetArguments(edges.problems().get(0), edges.requestId(),
                perEntry * perEntry + 1L, Budgets.MAX_DEPENDENCIES);
        assertRendersNumbers(edges.problems().get(0), en, zh,
                perEntry * perEntry + 1L, Budgets.MAX_DEPENDENCIES);
    }

    // ---------------------------------------------------------------- local commit runner

    @Test
    void theRunnerConfirmsARealPublicationAndTheQueueAcceptsIt() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(storedDocument(doc(1), "next")));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        SaveResult result = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.CONFIRMED, result.outcome());
        assertTrue(result.problems().isEmpty());
        assertEquals(requestId, result.currentCommitId());
        assertEquals(requestId, result.saved().commitId());
        assertEquals(baseline.commitId(), result.saved().parentCommitId());
        assertEquals(attempt.candidate(), result.saved().state());

        assertTrue(queue.complete(attempt, result));
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(result.saved(), queue.view().confirmed());
        assertEquals(attempt.candidate(), store.load().state());
        assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, store.lookup(requestId).status());
        assertEquals(requestId, store.lookup(requestId).saved().commitId());

        LocalProjectStore.CommitResult replay =
                store.commit(attempt.expectedCommitId(), requestId, attempt.candidate());
        assertTrue(replay.replayed(), "the same request id reports the same commit instead of a new baseline");
        assertEquals(requestId, replay.currentCommitId());
        assertEquals(result.saved(), replay.saved());
    }

    @Test
    void theRunnerReportsARealStaleBaselineAsAConflict() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        queue.enqueue(addingDocuments(storedDocument(doc(1), "first")));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        ProjectState.Entry other = storedDocument(doc(2), "other");
        SavedProject detached = store.commit(baseline.commitId(), UUID.randomUUID(), stateOf(base, other)).saved();
        assertNotEquals(baseline.commitId(), detached.commitId());

        SaveResult result = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.CONFLICT, result.outcome());
        assertNull(result.saved());
        assertNull(result.currentCommitId(), "a conflicting baseline yields no authoritative CURRENT");
        assertCode(result.problems().get(0), "editor:save/conflict_unobserved");

        assertTrue(queue.complete(attempt, result));
        assertEquals(SaveQueueView.State.RECOVERY, queue.view().state());
        assertEquals(baseline, queue.view().confirmed(), "the observed current commit never becomes a local baseline");
        assertEquals(detached, store.load(), "the detached publication is the authority, the queue keeps its own");
    }

    @Test
    void theRunnerKeepsTheObservedCurrentWhenAReceiptIsOlderThanTheHead() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        queue.enqueue(addingDocuments(storedDocument(doc(1), "first")));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();
        SaveResult confirmed = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.CONFIRMED, confirmed.outcome());

        ProjectState.Entry later = storedDocument(doc(2), "later");
        UUID laterRequest = UUID.randomUUID();
        SavedProject moved = store.commit(attempt.requestId(), laterRequest, stateOf(base, later)).saved();
        assertNotEquals(attempt.requestId(), moved.commitId());

        SaveResult replayed = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.CONFLICT, replayed.outcome());
        assertNull(replayed.saved());
        assertEquals(moved.commitId(), replayed.currentCommitId(), "the observed current is reported as observed");
        assertCode(replayed.problems().get(0), "editor:save/conflict");
        assertTrue(carriesArgument(replayed.problems().get(0), moved.commitId().toString()));

        assertTrue(queue.complete(attempt, replayed));
        assertEquals(SaveQueueView.State.RECOVERY, queue.view().state());
        assertEquals(baseline, queue.view().confirmed(),
                "a receipt is not applied locally once the head moved on");
        assertEquals(confirmed.saved().commitId(), attempt.requestId());
    }

    @Test
    void theRunnerReportsRealStorageRejectionsAsRejected() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectState candidate = stateOf(base, storedDocument(doc(1), "next"));

        UUID selfBaseline = UUID.randomUUID();
        SaveAttempt mismatch = new SaveAttempt(UUID.randomUUID(), selfBaseline, selfBaseline, candidate);
        SaveResult refused = LocalCommitRunner.run(store, mismatch);
        assertEquals(SaveResult.Outcome.REJECTED, refused.outcome());
        assertNull(refused.saved());
        assertCode(refused.problems().get(0), "editor:save/rejected");
        assertTrue(carriesArgument(refused.problems().get(0), "REQUEST_MISMATCH"));
        assertEquals(baseline, store.load());

        SaveAttempt afterClose = new SaveAttempt(UUID.randomUUID(), UUID.randomUUID(),
                baseline.commitId(), candidate);
        store.close();
        SaveResult closed = LocalCommitRunner.run(store, afterClose);
        assertEquals(SaveResult.Outcome.REJECTED, closed.outcome());
        assertCode(closed.problems().get(0), "editor:save/rejected");
        assertTrue(carriesArgument(closed.problems().get(0), "CLOSED"));
    }

    @Test
    void theRunnerRejectsAMissingBlobAndLeavesCurrentUntouched() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(
                document(doc(7), fakeBlob(BlobRef.Kind.DOCUMENT, 77))));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        SaveResult result = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.REJECTED, result.outcome());
        assertNull(result.saved());
        assertCode(result.problems().get(0), "editor:save/rejected");
        assertTrue(carriesArgument(result.problems().get(0), "INVALID_PATH"));
        assertEquals(baseline, store.load());
        assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, store.lookup(requestId).status());

        assertTrue(queue.complete(attempt, result));
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(baseline, queue.view().confirmed());
    }

    @Test
    void anUnknownResultIsResolvedByARealReopenAndTheSameRequest() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(storedDocument(doc(1), "next")));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        SaveResult published = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.CONFIRMED, published.outcome());
        assertTrue(queue.complete(attempt, unknown()), "the reporter may lose a result that was published");
        assertEquals(SaveQueueView.State.UNKNOWN, queue.view().state());

        store.close();
        store = LocalProjectStore.open(projectDirectory);
        LocalProjectStore.RequestResult looked = store.lookup(requestId);
        assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, looked.status());
        assertEquals(requestId, looked.saved().commitId());
        assertEquals(attempt.candidate(), looked.saved().state());

        assertTrue(queue.complete(attempt, new SaveResult(SaveResult.Outcome.CONFIRMED, looked.saved(),
                looked.currentCommitId(), List.of())));
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(looked.saved(), queue.view().confirmed());
        assertEquals(published.saved(), queue.view().confirmed());

        assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, store.lookup(UUID.randomUUID()).status(),
                "an unpublished request id is unknown, never a rejection");
    }

    @Test
    void aPublicationThatLosesItsReceiptIsUnknownAndOnlyTheSameRequestResolvesIt() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(storedDocument(doc(1), "next")));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        store.close();
        store = PublicationFaultStores.uncertainAfterPublish(projectDirectory,
                new IOException("the receipt was lost after the head was replaced"));
        SaveResult uncertain = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.UNKNOWN, uncertain.outcome(),
                "a publication whose receipt was lost is unknown, never a rejection");
        assertNull(uncertain.saved());
        assertNull(uncertain.currentCommitId(), "no authoritative CURRENT was observed");
        Problem reported = uncertain.problems().get(0);
        assertCode(reported, "editor:save/unknown");
        assertTrue(mentionOf(reported, requestId.toString()), "the diagnostic names the unresolved request");

        assertTrue(queue.complete(attempt, uncertain));
        assertEquals(SaveQueueView.State.UNKNOWN, queue.view().state());
        assertEquals(Optional.of(attempt), queue.view().active(), "the uncertain request stays in flight");
        assertEquals(baseline, queue.view().confirmed(), "an unknown result confirms nothing");
        assertThrows(IllegalStateException.class, () -> queue.enqueue(delta()));
        assertTrue(queue.dispatch().isEmpty());
        assertThrows(IllegalStateException.class, queue::close);

        store.close();
        store = LocalProjectStore.open(projectDirectory);
        LocalProjectStore.RequestResult looked = store.lookup(requestId);
        assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, looked.status(),
                "the head was replaced before the failure, so the request really published");
        assertEquals(attempt.candidate(), looked.saved().state());

        assertTrue(queue.complete(attempt, new SaveResult(SaveResult.Outcome.CONFIRMED, looked.saved(),
                looked.currentCommitId(), List.of())), "only the same request may resolve the unknown");
        assertEquals(SaveQueueView.State.READY, queue.view().state());
        assertEquals(looked.saved(), queue.view().confirmed());
        assertEquals(List.of(), queue.close(), "a resolved queue closes normally");
    }

    @Test
    void anUncheckedFailureAfterPublicationIsConservativelyUnknown() throws IOException {
        ProjectState.Entry base = storedDocument(doc(0), "base");
        SavedProject baseline = publish(stateOf(base));
        ProjectSaveQueue queue = new ProjectSaveQueue(baseline, ACCEPT, 4);
        UUID requestId = queue.enqueue(addingDocuments(storedDocument(doc(1), "next")));
        SaveAttempt attempt = queue.dispatch().orElseThrow().attempt().orElseThrow();

        store.close();
        IllegalStateException crash = new IllegalStateException("the writer died right after publishing");
        store = PublicationFaultStores.crashAfterPublish(projectDirectory, crash);
        SaveResult uncertain = LocalCommitRunner.run(store, attempt);
        assertEquals(SaveResult.Outcome.UNKNOWN, uncertain.outcome(),
                "an unexpected runtime failure may still have published, so it stays unknown");
        assertNull(uncertain.saved());
        assertNull(uncertain.currentCommitId());
        Problem reported = uncertain.problems().get(0);
        assertCode(reported, "editor:save/unknown");
        assertEquals(Messages.technical(crash), literalArgument(reported, 1),
                "the diagnostic carries the technical identity of the failure");

        assertTrue(queue.complete(attempt, uncertain));
        assertEquals(SaveQueueView.State.UNKNOWN, queue.view().state());
        assertThrows(IllegalStateException.class, () -> queue.enqueue(delta()));
        assertFalse(queue.complete(attempt, rejected()), "an unknown request is not closed as a rejection");

        store.close();
        store = LocalProjectStore.open(projectDirectory);
        LocalProjectStore.RequestResult looked = store.lookup(requestId);
        assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, looked.status(),
                "the publication happened before the crash; the queue is conservative, not wrong");
        assertEquals(attempt.candidate(), looked.saved().state());
        assertEquals(baseline, queue.view().confirmed(), "the queue still waits for the same request");
    }

    // ---------------------------------------------------------------- fixtures

    private static UUID doc(int index) {
        return new UUID(0x1000L, index);
    }

    private static BlobRef fakeBlob(BlobRef.Kind kind, long index) {
        return new BlobRef(kind, String.format("%064x", index + 1), 64);
    }

    private static ProjectState.Entry document(UUID id, BlobRef content, UUID... dependencies) {
        return new ProjectState.Entry(id, CORE, TEXT, UTF8, 1, "document " + id, content,
                new TreeSet<>(Arrays.asList(dependencies)), Set.of());
    }

    private static ProjectState.Entry asset(UUID id, BlobRef content) {
        return new ProjectState.Entry(id, CORE, IMAGE, PNG, 1, "asset " + id, content, Set.of(), Set.of());
    }

    private static ProjectState state(Map<UUID, ProjectState.Entry> documents,
            Map<UUID, ProjectState.Entry> assets) {
        return new ProjectState(PROJECT_ID, "project", Map.of(CORE, VERSION), documents, assets, null);
    }

    private static ProjectState stateOf(ProjectState.Entry... entries) {
        Map<UUID, ProjectState.Entry> documents = new TreeMap<>();
        for (ProjectState.Entry entry : entries) {
            documents.put(entry.id(), entry);
        }
        return state(documents, Map.of());
    }

    private static SavedProject saved(UUID commitId, ProjectState state) {
        return new SavedProject(commitId, null, state);
    }

    private static ProjectState largeState(int entries) {
        Map<UUID, ProjectState.Entry> documents = new TreeMap<>();
        for (int i = 0; i < entries; i++) {
            UUID id = doc(i);
            documents.put(id, document(id, fakeBlob(BlobRef.Kind.DOCUMENT, i)));
        }
        return state(documents, Map.of());
    }

    /** A baseline in which every document depends on all {@code perEntry} documents, so edges = n². */
    private static ProjectState denseDependencyState(int perEntry) {
        List<UUID> ids = new ArrayList<>();
        Map<UUID, ProjectState.Entry> documents = new TreeMap<>();
        for (int i = 0; i < perEntry; i++) {
            ids.add(doc(5000 + i));
        }
        for (UUID id : ids) {
            documents.put(id,
                    document(id, fakeBlob(BlobRef.Kind.DOCUMENT, 5000), ids.toArray(new UUID[0])));
        }
        return state(documents, Map.of());
    }

    private static int totalEntries(ProjectState state) {
        return state.documents().size() + state.assets().size();
    }

    private static int totalDependencies(ProjectState state) {
        int edges = 0;
        for (ProjectState.Entry entry : state.documents().values()) {
            edges += entry.documentDependencies().size() + entry.assetDependencies().size();
        }
        for (ProjectState.Entry entry : state.assets().values()) {
            edges += entry.documentDependencies().size() + entry.assetDependencies().size();
        }
        return edges;
    }

    private static ProjectDelta named(String name) {
        return new ProjectDelta(Optional.of(name), Map.of(), Map.of(), Set.of(), Map.of(), Set.of());
    }

    private static ProjectDelta delta() {
        return new ProjectDelta(Optional.empty(), Map.of(), Map.of(), Set.of(), Map.of(), Set.of());
    }

    private static ProjectDelta delta(Map<UUID, ProjectState.Entry> documents) {
        return new ProjectDelta(Optional.empty(), Map.of(), documents, Set.of(), Map.of(), Set.of());
    }

    private static ProjectDelta assetDelta(ProjectState.Entry... assets) {
        Map<UUID, ProjectState.Entry> map = new TreeMap<>();
        for (ProjectState.Entry entry : assets) {
            map.put(entry.id(), entry);
        }
        return new ProjectDelta(Optional.empty(), Map.of(), Map.of(), Set.of(), map, Set.of());
    }

    private static ProjectDelta addingDocuments(ProjectState.Entry... entries) {
        return delta(entryMap(entries));
    }

    private static ProjectDelta removalsOf(UUID... ids) {
        return new ProjectDelta(Optional.empty(), Map.of(), Map.of(),
                new TreeSet<>(Arrays.asList(ids)), Map.of(), Set.of());
    }

    private static ProjectDelta removing(int first, int count) {
        Set<UUID> removals = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            removals.add(doc(first + i));
        }
        return new ProjectDelta(Optional.empty(), Map.of(), Map.of(), removals, Map.of(), Set.of());
    }

    private static Map<UUID, ProjectState.Entry> entryMap(ProjectState.Entry... entries) {
        Map<UUID, ProjectState.Entry> map = new TreeMap<>();
        for (ProjectState.Entry entry : entries) {
            map.put(entry.id(), entry);
        }
        return map;
    }

    private static SaveResult rejected() {
        return new SaveResult(SaveResult.Outcome.REJECTED, null, null, List.of());
    }

    private static SaveResult unknown() {
        return new SaveResult(SaveResult.Outcome.UNKNOWN, null, null, List.of());
    }

    private static Problem problem(Problem.Severity severity, String code) {
        return new Problem(severity, EditorId.parse(code), "target", new TextContent.Literal("detail"));
    }

    private static SaveDispatch dropped(ProjectSaveQueue queue) {
        SaveDispatch dispatch = queue.dispatch().orElseThrow();
        assertTrue(dispatch.attempt().isEmpty(), "the head must be rejected before any I/O");
        return dispatch;
    }

    private static void assertCode(Problem problem, String code) {
        assertEquals(EditorId.parse(code), problem.code());
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertInstanceOf(TextContent.Message.class, problem.message());
    }

    private static String messageKey(Problem problem) {
        return assertInstanceOf(TextContent.Message.class, problem.message()).key();
    }

    /** Asserts the diagnostic carries exactly these literal arguments, in this order. */
    private static void assertLiteralArguments(Problem problem, String... values) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals(values.length, message.arguments().size(), "argument count of " + message.key());
        for (int index = 0; index < values.length; index++) {
            assertEquals(new TextContent.LiteralArgument(values[index]), message.arguments().get(index),
                    "argument " + index + " of " + message.key());
        }
    }

    /** Asserts a budget diagnostic is {@code [requestId literal, typed count, typed limit]}. */
    private static void assertBudgetArguments(Problem problem, UUID requestId, long count, long limit) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals(3, message.arguments().size(), "a budget diagnostic carries three arguments");
        assertEquals(new TextContent.LiteralArgument(requestId.toString()), message.arguments().get(0),
                "the request identity stays a literal argument");
        assertEquals(BigDecimal.valueOf(count), numberArgument(problem, 1), "the count keeps its typed value");
        assertEquals(BigDecimal.valueOf(limit), numberArgument(problem, 2), "the limit keeps its typed value");
    }

    /** Reads the typed quantity at {@code index}, refusing a literal string standing in for a number. */
    private static BigDecimal numberArgument(Problem problem, int index) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        return assertInstanceOf(TextContent.NumberArgument.class, message.arguments().get(index)).value();
    }

    /** The rendered budget sentence must show both quantities exactly as the typed arguments hold them. */
    private static void assertRendersNumbers(Problem problem, Map<String, String> english,
            Map<String, String> chinese, long count, long limit) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        for (Map<String, String> language : List.of(english, chinese)) {
            String rendered = LangResources.render(message, language.get(message.key()));
            assertTrue(rendered.contains(Long.toString(count)), rendered);
            assertTrue(rendered.contains(Long.toString(limit)), rendered);
        }
    }

    private static Set<String> expectedSaveKeys() {
        return Set.of("editor.save.module_version_conflict", "editor.save.entry_identity_conflict",
                "editor.save.missing_owner", "editor.save.missing_document_dependency",
                "editor.save.missing_asset_dependency", "editor.save.entry_budget_exceeded",
                "editor.save.dependency_budget_exceeded", "editor.save.invalid_candidate",
                "editor.save.validator_failed", "editor.save.conflict", "editor.save.conflict_unobserved",
                "editor.save.unknown", "editor.save.rejected");
    }

    /** Runs one real queue rejection caused by a dangling reference of the given category. */
    private static SaveDispatch danglingDependency(BlobRef.Kind kind, UUID entryId, UUID dependencyId) {
        ProjectSaveQueue queue = new ProjectSaveQueue(
                saved(UUID.randomUUID(), state(Map.of(), Map.of())), ACCEPT, 4);
        ProjectState.Entry entry = kind == BlobRef.Kind.DOCUMENT
                ? new ProjectState.Entry(entryId, CORE, TEXT, UTF8, 1, "entry",
                        fakeBlob(BlobRef.Kind.DOCUMENT, 1), Set.of(dependencyId), Set.of())
                : new ProjectState.Entry(entryId, CORE, TEXT, UTF8, 1, "entry",
                        fakeBlob(BlobRef.Kind.DOCUMENT, 1), Set.of(), Set.of(dependencyId));
        queue.enqueue(addingDocuments(entry));
        SaveDispatch dropped = queue.dispatch().orElseThrow();
        assertTrue(dropped.attempt().isEmpty(), "a dangling reference is rejected before any I/O");
        assertCode(dropped.problems().get(0), "editor:save/missing_dependency");
        assertEquals(1, dropped.problems().size(), "a structural rejection reports exactly one problem");
        assertEquals(entryId.toString(), dropped.problems().get(0).target());
        return dropped;
    }

    private static void assertArgument(TextContent content, String value) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, content);
        assertTrue(message.arguments().contains(new TextContent.LiteralArgument(value)),
                "expected argument " + value + " in " + message.key());
    }

    private static boolean carriesArgument(Problem problem, String value) {
        if (!(problem.message() instanceof TextContent.Message message)) {
            return false;
        }
        return message.arguments().contains(new TextContent.LiteralArgument(value));
    }

    /** True when any literal argument of the diagnostic contains the fragment. */
    private static boolean mentionOf(Problem problem, String fragment) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        for (TextContent.Argument argument : message.arguments()) {
            if (argument instanceof TextContent.LiteralArgument literal && literal.value().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String literalArgument(Problem problem, int index) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        return assertInstanceOf(TextContent.LiteralArgument.class, message.arguments().get(index)).value();
    }

    private ProjectState.Entry storedDocument(UUID id, String body, UUID... dependencies) throws IOException {
        return document(id, store.stage(BlobRef.Kind.DOCUMENT, body.getBytes(StandardCharsets.UTF_8)),
                dependencies);
    }

    private SavedProject publish(ProjectState state) throws IOException {
        return store.commit(null, UUID.randomUUID(), state).saved();
    }

    private static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
