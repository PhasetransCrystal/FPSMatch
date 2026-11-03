package net.ptcrys.fpsmatch.editor.common.client.workbench;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchPathPicker.Start.*;
import static net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchPathPicker.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchPathPickerTest {
    private static final WorkbenchPathPicker.Request REQUEST = WorkbenchPathPicker.Request.openFile(
            "Choose JSON", "", "JSON", "*.json");
    private static final WorkbenchPathPicker.Result SELECTION = WorkbenchPathPicker.Result.selected(Path.of("chosen.json"));

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        void next() { tasks.removeFirst().run(); }
    }

    @Test void nativeWorkAndCompletionAreDispatchedSeparatelyAndRepeatedClicksAreRejected() {
        var worker = new Queue();
        var client = new Queue();
        var calls = new AtomicInteger();
        var coordinator = new WorkbenchPathPicker.Coordinator(worker, request -> {
            calls.incrementAndGet();
            return SELECTION;
        });
        var picker = new WorkbenchPathPicker(client, coordinator);
        var results = new ArrayList<WorkbenchPathPicker.Result>();
        assertEquals(STARTED, picker.request(REQUEST, results::add));
        assertEquals(BUSY, picker.request(REQUEST, results::add));
        assertEquals(0, calls.get());
        worker.next();
        assertEquals(1, calls.get());
        assertTrue(results.isEmpty());
        assertTrue(picker.isBusy(), "delivery still belongs to the original field");
        client.next();
        assertEquals(List.of(SELECTION), results);
        assertFalse(picker.isBusy());
    }

    @Test void cancellationBeforeWorkerStartsDoesNotOpenAnObsoleteDialog() {
        var worker = new Queue();
        var client = new Queue();
        var coordinator = new WorkbenchPathPicker.Coordinator(worker, request -> fail("obsolete dialog opened"));
        var picker = new WorkbenchPathPicker(client, coordinator);
        picker.request(REQUEST, result -> fail("cancelled request delivered"));
        picker.cancelPending();
        worker.next();
        assertFalse(picker.isBusy());
        assertTrue(client.tasks.isEmpty());
    }

    @Test void cancellingAnOpenDialogKeepsTheSharedSlotUntilNativeReturns() {
        var worker = new Queue();
        var client = new Queue();
        var first = new AtomicReference<WorkbenchPathPicker>();
        var second = new AtomicReference<WorkbenchPathPicker>();
        var coordinator = new WorkbenchPathPicker.Coordinator(worker, request -> {
            first.get().cancelPending();
            assertTrue(second.get().isBusy());
            assertEquals(BUSY, second.get().request(REQUEST, result -> fail("concurrent native request")));
            return SELECTION;
        });
        first.set(new WorkbenchPathPicker(client, coordinator));
        second.set(new WorkbenchPathPicker(client, coordinator));
        first.get().request(REQUEST, result -> fail("cancelled native result delivered"));
        worker.next();
        assertTrue(client.tasks.isEmpty());
        assertFalse(second.get().isBusy());
        assertEquals(STARTED, second.get().request(REQUEST, result -> fail("cancelled new request delivered")));
        second.get().cancelPending();
        worker.next();
    }

    @Test void oldQueuedResultCannotWriteOrClearTheNewFieldRequest() {
        var worker = new Queue();
        var client = new Queue();
        var picker = new WorkbenchPathPicker(client, new WorkbenchPathPicker.Coordinator(worker, request -> SELECTION));
        var newField = new ArrayList<WorkbenchPathPicker.Result>();
        picker.request(REQUEST, result -> fail("old field was updated"));
        worker.next();
        picker.cancelPending();
        assertEquals(STARTED, picker.request(REQUEST, newField::add));
        client.next();
        assertTrue(picker.isBusy());
        assertEquals(BUSY, picker.request(REQUEST, result -> fail("new field binding was lost")));
        worker.next();
        client.next();
        assertEquals(List.of(SELECTION), newField);
        assertFalse(picker.isBusy());
    }

    @Test void closeSuppressesAlreadyQueuedResultsAndPermanentlyRejectsNewRequests() {
        var worker = new Queue();
        var client = new Queue();
        var picker = new WorkbenchPathPicker(client, new WorkbenchPathPicker.Coordinator(worker, request -> SELECTION));
        picker.request(REQUEST, result -> fail("disposed screen updated"));
        worker.next();
        picker.close();
        picker.close();
        client.next();
        assertEquals(CLOSED, picker.request(REQUEST, result -> fail("disposed picker restarted")));
        assertFalse(picker.isBusy());
    }

    @Test void nativeLinkFailureIsReportedAndDoesNotRetainTheSharedSlot() {
        var worker = new Queue();
        var client = new Queue();
        var failure = new UnsatisfiedLinkError("missing native library");
        var coordinator = new WorkbenchPathPicker.Coordinator(worker, request -> { throw failure; });
        var picker = new WorkbenchPathPicker(client, coordinator);
        var results = new ArrayList<WorkbenchPathPicker.Result>();
        picker.request(REQUEST, results::add);
        worker.next();
        client.next();
        assertEquals(FAILED, results.get(0).status());
        assertSame(failure, results.get(0).failure());
        assertNull(results.get(0).path());
        assertFalse(picker.isBusy());
        assertEquals(STARTED, picker.request(REQUEST, result -> fail("cancelled retry delivered")));
        picker.cancelPending();
        worker.next();
    }

    @Test void nativeCancelHasNoSelectedPathAndAllowsAnotherRequest() {
        var worker = new Queue();
        var client = new Queue();
        var picker = new WorkbenchPathPicker(client, new WorkbenchPathPicker.Coordinator(
                worker, request -> WorkbenchPathPicker.Result.cancelled()));
        var results = new ArrayList<WorkbenchPathPicker.Result>();
        picker.request(REQUEST, results::add);
        worker.next();
        client.next();
        assertEquals(CANCELLED, results.get(0).status());
        assertNull(results.get(0).path());
        assertNull(results.get(0).failure());
        assertFalse(picker.isBusy());
    }

    @Test void rejectedWorkerSubmissionReportsFailureAndReleasesItsReservation() {
        var failure = new RejectedExecutionException("worker stopped");
        var picker = new WorkbenchPathPicker(new Queue(), new WorkbenchPathPicker.Coordinator(
                command -> { throw failure; }, request -> fail("rejected work ran")));
        var results = new ArrayList<WorkbenchPathPicker.Result>();
        assertEquals(STARTED, picker.request(REQUEST, results::add));
        assertEquals(FAILED, results.get(0).status());
        assertSame(failure, results.get(0).failure());
        assertFalse(picker.isBusy());
    }

    @Test void stoppedClientExecutorDropsTheFieldReferenceAndReleasesThePicker() {
        var worker = new Queue();
        var picker = new WorkbenchPathPicker(command -> { throw new RejectedExecutionException("client stopped"); },
                new WorkbenchPathPicker.Coordinator(worker, request -> SELECTION));
        picker.request(REQUEST, result -> fail("stopped client callback invoked"));
        worker.next();
        assertFalse(picker.isBusy());
        assertEquals(STARTED, picker.request(REQUEST, result -> fail("cancelled retry delivered")));
        picker.cancelPending();
        worker.next();
    }
}
