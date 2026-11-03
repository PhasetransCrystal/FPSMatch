package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.api.*;
import org.junit.jupiter.api.Test;

/** Coordination tests only: fake targets do not authenticate a Minecraft world or player. */
class WorldTargetWorkbenchTest {
    private static final EditorId OVERWORLD = id("minecraft:overworld"), NETHER = id("minecraft:the_nether");
    private static final TextContent FAILURE = new TextContent.Literal("Authority was revoked");

    @Test void noWorldAndUnavailableHostNeverCreateAnImplicitBinding() {
        var queue = new Queue();
        var model = new WorldTargetWorkbench(() -> new WorldTargetWorkbench.Capture(null, FAILURE), queue);
        model.refresh();
        assertEquals(WorldTargetWorkbench.State.UNAVAILABLE, model.state());
        assertEquals(FAILURE, model.status()); assertFalse(model.available());
        assertTrue(model.worldId().isEmpty()); assertTrue(model.mappings().isEmpty());
        assertThrows(IllegalStateException.class, model::bind);
        var h = new Harness();
        assertEquals(Map.of(OVERWORLD, OVERWORLD), h.model.mappings());
        assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state());
        assertEquals(0, h.target.requests.size()); assertTrue(h.model.worldId().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> h.model.dimensions().clear());
    }

    @Test void workerCompletionOnlyChangesStateWhenDispatchedOnOwner() throws Exception {
        var h = new Harness(); h.model.bind(); var connection = new FakeConnection();
        onWorker(() -> h.target.request(0).complete(success(connection)));
        assertEquals(WorldTargetWorkbench.State.VERIFYING, h.model.state());
        assertTrue(h.model.busy()); assertTrue(h.model.worldId().isEmpty());
        h.queue.next();
        assertBound(h, connection); assertFalse(h.model.busy()); assertEquals(0, connection.closed);
    }

    @Test void queuedSuccessAfterPanelCancellationIsClosedAndCannotBind() {
        var h = new Harness(); h.model.bind(); var request = h.target.request(0);
        var late = new FakeConnection(); request.complete(success(late));
        h.model.cancelPending();
        assertEquals(1, request.cancelled); assertFalse(h.model.busy());
        assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state());
        h.queue.next();
        assertEquals(1, late.closed); assertTrue(h.model.worldId().isEmpty());
        assertEquals(WorldTargetWorkbench.text("world_cancelled"), h.model.status());
    }

    @Test void oldQueuedSuccessCannotOverwriteANewerAcceptedBinding() {
        var h = new Harness(); h.model.bind(); var first = h.target.request(0);
        var old = new FakeConnection(); first.complete(success(old));
        h.model.bind(); var next = new FakeConnection(); h.target.request(1).complete(success(next));
        h.queue.last(); assertBound(h, next);
        h.queue.next();
        assertEquals(1, first.cancelled); assertEquals(1, old.closed);
        assertBound(h, next); assertEquals(0, next.closed);
    }

    @Test void mappingEditsRevokeBindingAndCapturedMappingCannotBeMutatedOrFollowLaterEdits() {
        var h = new Harness(); var accepted = h.bind();
        Map<EditorId, EditorId> original = h.model.mappings();
        h.model.setMapping(OVERWORLD, NETHER);
        assertEquals(1, accepted.closed); assertTrue(h.model.worldId().isEmpty());
        assertEquals(Map.of(OVERWORLD, OVERWORLD), original);
        assertThrows(UnsupportedOperationException.class, () -> original.put(NETHER, OVERWORLD));
        h.model.bind(); var request = h.target.request(1);
        assertEquals(Map.of(OVERWORLD, NETHER), request.mapping);
        assertThrows(UnsupportedOperationException.class, () -> request.mapping.clear());
        h.model.setMapping(OVERWORLD, OVERWORLD);
        assertEquals(1, request.cancelled); assertEquals(Map.of(OVERWORLD, NETHER), request.mapping);
        var late = new FakeConnection(); request.complete(success(late)); h.queue.next();
        assertEquals(1, late.closed); assertTrue(h.model.worldId().isEmpty());
        assertEquals(Map.of(OVERWORLD, OVERWORLD), h.model.mappings());
    }

    @Test void oneToOneAnd64EntryBudgetRejectWithoutRevokingAcceptedBinding() {
        var h = new Harness();
        for (int n = 1; n < 64; n++) h.model.setMapping(id("source:dim_" + n), id("target:dim_" + n));
        var accepted = h.bind(); var before = h.model.mappings(); long version = h.model.version();
        assertEquals(64, before.size());
        assertThrows(IllegalArgumentException.class, () -> h.model.setMapping(id("source:overflow"), id("target:dim_64")));
        assertThrows(IllegalArgumentException.class, () -> h.model.setMapping(id("source:dim_1"), OVERWORLD));
        assertThrows(IllegalArgumentException.class, () -> h.model.setMapping(OVERWORLD, id("missing:dimension")));
        h.model.setMapping(OVERWORLD, OVERWORLD); h.model.removeMapping(id("source:absent"));
        assertEquals(before, h.model.mappings()); assertEquals(version, h.model.version());
        assertBound(h, accepted); assertEquals(0, accepted.closed);
    }

    @Test void removingLastMappingRevokesAndAnEmptyMappingCannotBeVerified() {
        var h = new Harness(); var accepted = h.bind(); h.model.removeMapping(OVERWORLD);
        assertEquals(1, accepted.closed); assertTrue(h.model.mappings().isEmpty());
        assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state());
        assertThrows(IllegalStateException.class, h.model::bind);
        assertEquals(1, h.target.requests.size());
    }

    @Test void explicitDisconnectCancelsPendingAndRejectsItsQueuedFailure() {
        var h = new Harness(); h.model.bind(); var request = h.target.request(0);
        request.complete(failure(new IllegalStateException("old request")));
        h.model.disconnect(); h.queue.next();
        assertEquals(1, request.cancelled); assertTrue(h.model.worldId().isEmpty());
        assertTrue(h.model.diagnostic().isEmpty());
        assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state());
        var accepted = h.bind(); h.model.disconnect();
        assertEquals(1, accepted.closed); assertTrue(h.model.worldId().isEmpty());
    }

    @Test void aDisconnectedCapturedTargetCannotAcceptASuccessEvenBeforeTick() {
        var h = new Harness(); h.model.bind(); var late = new FakeConnection();
        h.target.request(0).complete(success(late)); h.target.current = false; h.queue.next();
        assertEquals(1, late.closed); assertTrue(h.model.worldId().isEmpty());
        assertEquals(WorldTargetWorkbench.State.UNAVAILABLE, h.model.state());
        assertFalse(h.model.available());
    }

    @Test void targetReplacementRejectsOldCompletionAndKeepsNewTargetIdentity() {
        var h = new Harness(); var oldTarget = h.target; h.model.bind();
        var late = new FakeConnection(); oldTarget.request(0).complete(success(late));
        oldTarget.current = false; h.host.selected = new FakeTarget("Replacement"); h.model.refresh();
        assertEquals(1, oldTarget.request(0).cancelled);
        h.model.bind(); var accepted = new FakeConnection(); h.host.selected.request(0).complete(success(accepted));
        h.queue.last(); h.queue.next();
        assertBound(h, accepted); assertEquals("Replacement", h.model.targetLabel());
        assertEquals(1, late.closed); assertEquals(0, accepted.closed);
    }

    @Test void periodicAuthorityCheckCoalescesAndFailureRevokesWithOriginalDiagnostic() {
        var h = new Harness(); var accepted = h.bind();
        h.model.tick(false); assertTrue(accepted.verifications.isEmpty());
        h.model.tick(true); h.model.tick(true); assertEquals(1, accepted.verifications.size());
        accepted.verifications.get(0).accept(success(accepted)); h.queue.next();
        assertBound(h, accepted); assertEquals(0, accepted.closed);
        h.model.tick(true); assertEquals(2, accepted.verifications.size());
        var diagnostic = new IllegalStateException("permission revoked by host");
        accepted.verifications.get(1).accept(failure(diagnostic));
        assertBound(h, accepted); h.queue.next();
        assertEquals(1, accepted.closed); assertTrue(h.model.worldId().isEmpty());
        assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state());
        assertEquals(FAILURE, h.model.status()); assertSame(diagnostic, h.model.diagnostic().orElseThrow());
    }

    @Test void disconnectedTargetTickClosesAcceptedConnectionWithoutAuthorityRequest() {
        var h = new Harness(); var accepted = h.bind(); h.target.current = false;
        h.model.tick(false);
        assertEquals(1, accepted.closed); assertTrue(accepted.verifications.isEmpty());
        assertEquals(WorldTargetWorkbench.State.UNAVAILABLE, h.model.state());
        assertTrue(h.model.worldId().isEmpty());
        assertTrue(h.model.diagnostic().isEmpty());
    }

    @Test void invalidationDiagnosticIsCapturedBeforeReleaseAndSurvivesLateAuthoritySuccess() {
        var h = new Harness(); var accepted = h.bind(); h.model.tick(true);
        var diagnostic = new IllegalStateException("captured authoring generation changed");
        h.target.invalidation = diagnostic;
        accepted.onClose = () -> h.target.invalidation = null;
        h.target.current = false; h.model.tick(false);
        assertEquals(1, accepted.closed); assertEquals(1, h.target.diagnosticReads);
        assertNull(h.target.invalidation, "release must happen after the diagnostic capture");
        assertEquals(WorldTargetWorkbench.State.UNAVAILABLE, h.model.state());
        assertEquals(WorldTargetWorkbench.text("world_disconnected"), h.model.status());
        assertSame(diagnostic, h.model.diagnostic().orElseThrow());
        assertTrue(h.model.worldId().isEmpty()); assertFalse(h.model.available());
        accepted.verifications.get(0).accept(success(accepted)); h.queue.next();
        h.model.tick(true);
        assertSame(diagnostic, h.model.diagnostic().orElseThrow());
        assertEquals(1, h.target.diagnosticReads);
    }

    @Test void healthyTargetDoesNotReadInvalidationDiagnosticAndPendingInvalidationStillCancels() {
        var h = new Harness(); h.model.bind(); var pending = h.target.request(0);
        var diagnostic = new IllegalStateException("client level changed");
        h.target.invalidation = diagnostic; h.model.tick(true);
        assertEquals(0, h.target.diagnosticReads); assertEquals(0, pending.cancelled);
        assertEquals(WorldTargetWorkbench.State.VERIFYING, h.model.state());
        h.target.current = false; h.model.tick(false);
        assertEquals(1, pending.cancelled); assertFalse(h.model.busy());
        assertEquals(WorldTargetWorkbench.State.UNAVAILABLE, h.model.state());
        assertSame(diagnostic, h.model.diagnostic().orElseThrow());
    }

    @Test void diagnosticProviderFailureCannotPreventInvalidTargetRelease() {
        var h = new Harness(); var accepted = h.bind();
        var diagnostic = new IllegalStateException("diagnostic capture failed");
        h.target.diagnosticFailure = diagnostic; h.target.current = false; h.model.tick(false);
        assertEquals(1, accepted.closed); assertTrue(h.model.worldId().isEmpty());
        assertEquals(WorldTargetWorkbench.State.UNAVAILABLE, h.model.state());
        assertSame(diagnostic, h.model.diagnostic().orElseThrow());
    }

    @Test void lateAuthorityFailureCannotRevokeTheNewConnectionOrSuppressItsCheck() {
        var h = new Harness(); var old = h.bind(); h.model.tick(true);
        Consumer<WorldTargetWorkbench.Result> oldCheck = old.verifications.get(0);
        var accepted = h.bind(); h.model.tick(true);
        oldCheck.accept(failure(new IllegalStateException("old permission"))); h.queue.next();
        assertBound(h, accepted); assertEquals(0, accepted.closed); assertEquals(1, old.closed);
        h.model.tick(true); assertEquals(1, accepted.verifications.size());
        accepted.verifications.get(0).accept(success(accepted)); h.queue.next();
        h.model.tick(true); assertEquals(2, accepted.verifications.size());
    }

    @Test void oldVerifySuccessWithSeparateConnectionIsReleasedWithoutClosingNewBinding() {
        var h = new Harness(); var old = h.bind(); h.model.tick(true);
        var accepted = h.bind(); var late = new FakeConnection();
        old.verifications.get(0).accept(success(late)); h.queue.next();
        assertEquals(1, late.closed); assertEquals(1, old.closed);
        assertBound(h, accepted); assertEquals(0, accepted.closed);
    }

    @Test void panelClosePreservesCompletedBindingAndPendingPeriodicCheck() {
        var h = new Harness(); var accepted = h.bind(); long version = h.model.version();
        h.model.tick(true); h.model.cancelPending();
        assertBound(h, accepted); assertEquals(0, accepted.closed); assertEquals(version, h.model.version());
        accepted.verifications.get(0).accept(success(accepted)); h.queue.next(); assertBound(h, accepted);
    }

    @Test void rejectedOwnerDispatchClosesLateBindAndVerifyResultsOnDeliveringWorker() throws Exception {
        var h = new Harness(); h.model.bind(); var pending = h.target.request(0);
        var lateBind = new FakeConnection(); h.queue.reject = true;
        onWorker(() -> assertThrows(RejectedExecutionException.class, () -> pending.complete(success(lateBind))));
        assertEquals(1, lateBind.closed); assertTrue(h.model.worldId().isEmpty());
        h.model.cancelPending(); assertEquals(1, pending.cancelled);
        h.queue.reject = false; var old = h.bind(); h.model.tick(true);
        var accepted = h.bind(); var lateVerify = new FakeConnection(); h.queue.reject = true;
        onWorker(() -> assertThrows(RejectedExecutionException.class, () -> old.verifications.get(0).accept(success(lateVerify))));
        assertEquals(1, lateVerify.closed); assertBound(h, accepted); assertEquals(0, accepted.closed);
    }

    @Test void staleAuthorityCallbackCannotCloseTheCurrentConnectionEvenIfItReturnsThatInstance() {
        var h = new Harness(); var old = h.bind(); h.model.tick(true);
        var accepted = h.bind(); old.verifications.get(0).accept(success(accepted)); h.queue.next();
        assertBound(h, accepted); assertEquals(0, accepted.closed);
    }

    @Test void rejectedCurrentBindDeliveryBecomesFailedOnOwnerTickAndCanBindAgain() throws Exception {
        for (boolean successfulResult : List.of(false, true)) {
            var h = new Harness(); h.model.bind(); var request = h.target.request(0);
            var undeliverable = new FakeConnection(); var rejection = new AtomicReference<RejectedExecutionException>();
            h.queue.reject = true;
            onWorker(() -> rejection.set(assertThrows(RejectedExecutionException.class,
                    () -> request.complete(successfulResult ? success(undeliverable) : failure(new IllegalStateException("host failure"))))));
            assertEquals(WorldTargetWorkbench.State.VERIFYING, h.model.state());
            assertTrue(h.model.busy()); assertEquals(successfulResult ? 1 : 0, undeliverable.closed);
            h.model.tick(false);
            assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state()); assertFalse(h.model.busy());
            assertTrue(h.model.worldId().isEmpty()); assertEquals(1, request.cancelled);
            assertSame(rejection.get(), h.model.diagnostic().orElseThrow());
            assertEquals(WorldTargetWorkbench.text("world_failed"), h.model.status());
            assertEquals(Map.of(OVERWORLD, OVERWORLD), h.model.mappings());
            h.queue.reject = false; var accepted = h.bind(); h.model.tick(true);
            assertBound(h, accepted); assertEquals(1, accepted.verifications.size());
            assertTrue(h.model.diagnostic().isEmpty());
        }
    }

    @Test void rejectedCurrentAuthorityDeliveryRevokesOnOwnerTickAndCanBindAgain() throws Exception {
        for (boolean successfulResult : List.of(false, true)) {
            var h = new Harness(); var old = h.bind(); h.model.tick(true);
            var rejection = new AtomicReference<RejectedExecutionException>(); h.queue.reject = true;
            onWorker(() -> rejection.set(assertThrows(RejectedExecutionException.class,
                    () -> old.verifications.get(0).accept(successfulResult ? success(old) : failure(new IllegalStateException("host failure"))))));
            h.model.tick(false);
            assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state());
            assertTrue(h.model.worldId().isEmpty()); assertFalse(h.model.busy());
            assertTrue(old.closed >= 1, "rejected authority result must release the accepted connection");
            assertSame(rejection.get(), h.model.diagnostic().orElseThrow());
            assertEquals(WorldTargetWorkbench.text("world_failed"), h.model.status());
            h.queue.reject = false; var accepted = h.bind(); h.model.tick(true);
            assertEquals(1, accepted.verifications.size());
            accepted.verifications.get(0).accept(success(accepted)); h.queue.next();
            assertBound(h, accepted); assertEquals(0, accepted.closed); assertTrue(h.model.diagnostic().isEmpty());
        }
    }

    @Test void rejectedOldBindDeliveryCannotFailLaterBindingOrItsAuthorityCheck() throws Exception {
        var h = new Harness(); h.model.bind(); var stale = h.target.request(0);
        var accepted = h.bind(); h.model.tick(true); var late = new FakeConnection(); h.queue.reject = true;
        onWorker(() -> assertThrows(RejectedExecutionException.class, () -> stale.complete(success(late))));
        assertEquals(1, late.closed); h.model.tick(false);
        assertBound(h, accepted); assertEquals(0, accepted.closed); assertTrue(h.model.diagnostic().isEmpty());
        h.queue.reject = false; accepted.verifications.get(0).accept(success(accepted)); h.queue.next();
        h.model.tick(true); assertEquals(2, accepted.verifications.size()); assertBound(h, accepted);
    }

    @Test void rejectedOldAuthorityDeliveryCannotFailLaterBindingOrSuppressItsPendingCheck() throws Exception {
        var h = new Harness(); var old = h.bind(); h.model.tick(true);
        var accepted = h.bind(); h.model.tick(true); var late = new FakeConnection(); h.queue.reject = true;
        onWorker(() -> assertThrows(RejectedExecutionException.class, () -> old.verifications.get(0).accept(success(late))));
        assertEquals(1, late.closed); h.model.tick(false); h.model.tick(true);
        assertBound(h, accepted); assertEquals(0, accepted.closed); assertTrue(h.model.diagnostic().isEmpty());
        assertEquals(1, accepted.verifications.size(), "current pending check remains current");
        h.queue.reject = false; accepted.verifications.get(0).accept(success(accepted)); h.queue.next();
        h.model.tick(true); assertEquals(2, accepted.verifications.size()); assertBound(h, accepted);
    }

    @Test void ownerOnlyOperationsAndWrongThreadDispatchAreRejected() throws Exception {
        var h = new Harness(); long version = h.model.version();
        onWorker(() -> {
            assertThrows(IllegalStateException.class, h.model::refresh);
            assertThrows(IllegalStateException.class, h.model::state);
            assertThrows(IllegalStateException.class, h.model::worldId);
            assertThrows(IllegalStateException.class, h.model::mappings);
            assertThrows(IllegalStateException.class, () -> h.model.setMapping(OVERWORLD, NETHER));
            assertThrows(IllegalStateException.class, h.model::bind);
            assertThrows(IllegalStateException.class, h.model::cancelPending);
            assertThrows(IllegalStateException.class, h.model::disconnect);
            assertThrows(IllegalStateException.class, () -> h.model.tick(true));
        });
        assertEquals(version, h.model.version()); assertEquals(0, h.target.requests.size());
        h.model.bind(); var accepted = new FakeConnection(); h.target.request(0).complete(success(accepted));
        Runnable completion = h.queue.tasks.removeFirst();
        onWorker(() -> assertThrows(IllegalStateException.class, completion::run));
        assertEquals(WorldTargetWorkbench.State.VERIFYING, h.model.state());
        assertTrue(h.model.worldId().isEmpty());
        completion.run(); assertBound(h, accepted);
    }

    @Test void resultRequiresExactlyOneOfConnectionAndFailure() {
        var connection = new FakeConnection();
        assertThrows(IllegalArgumentException.class, () -> new WorldTargetWorkbench.Result(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WorldTargetWorkbench.Result(connection, FAILURE, null));
        assertSame(connection, success(connection).connection()); assertEquals(FAILURE, failure(null).failure());
    }

    @Test void registrationRequiresAnExplicitMissingIdentityResultAndConfirmationBeforeCallingHost() {
        var h = new Harness();
        assertFalse(h.model.canRegister()); assertThrows(IllegalStateException.class, h.model::registrationPrompt);
        var bound = new Harness(); bound.bind();
        assertFalse(bound.model.canRegister()); assertThrows(IllegalStateException.class, bound.model::registrationPrompt);
        h.model.bind(); h.target.request(0).complete(failure(new IllegalStateException("general failure"))); h.queue.next();
        assertFalse(h.model.canRegister()); assertThrows(IllegalStateException.class, h.model::registrationPrompt);
        var prompt = missing(h);
        assertTrue(h.model.canRegister(prompt)); assertEquals("Integrated world", prompt.targetLabel());
        assertEquals(0, h.target.registrations.size()); assertFalse(h.model.registering());
        assertTrue(h.model.registrationReceipt().isEmpty());
        // Merely opening and leaving the confirmation page has no write request to cancel.
        h.model.cancelPending(); assertEquals(0, h.target.registrations.size());
        h.model.register(prompt); assertEquals(1, h.target.registrations.size());
        assertTrue(h.model.registering()); assertTrue(h.model.busy()); assertFalse(h.model.canRegister());
        assertNull(h.model.registrationReceipt().orElseThrow().result()); assertTrue(h.model.worldId().isEmpty());
    }

    @Test void registrationPromptsCannotCrossModelsOrBecomeFreshAgainAfterAnotherMissingCheck() {
        var h = new Harness(); var other = new Harness();
        var stale = missing(h); var foreign = missing(other);
        assertFalse(h.model.canRegister(foreign));
        assertThrows(IllegalStateException.class, () -> h.model.register(foreign));
        h.model.setMapping(OVERWORLD, NETHER);
        assertFalse(h.model.canRegister(stale));
        assertThrows(IllegalStateException.class, () -> h.model.register(stale));
        var fresh = missing(h);
        assertFalse(h.model.canRegister(stale), "a later MISSING result must not renew an old confirmation");
        assertThrows(IllegalStateException.class, () -> h.model.register(stale));
        assertTrue(h.model.canRegister(fresh)); assertTrue(h.target.registrations.isEmpty());
    }

    @Test void registrationPromptFromReplacedTargetCannotSubmitToTheNewTarget() {
        var h = new Harness(); var prompt = missing(h); h.target.current = false;
        h.host.selected = new FakeTarget("Replacement"); h.model.refresh(); missing(h);
        assertFalse(h.model.canRegister(prompt));
        assertThrows(IllegalStateException.class, () -> h.model.register(prompt));
        assertTrue(h.target.registrations.isEmpty()); assertTrue(h.host.selected.registrations.isEmpty());
    }

    @Test void promptAndReceiptKeepTheLabelShownAtConfirmationEvenWhenTargetLabelChanges() {
        var h = new Harness(); var prompt = missing(h);
        h.target.label = "Renamed before submit";
        assertEquals("Integrated world", prompt.targetLabel());
        h.model.register(prompt);
        assertEquals("Integrated world", h.model.registrationReceipt().orElseThrow().targetLabel());
        h.target.label = "Renamed after submit";
        var result = registered(); h.target.registrations.get(0).accept(result); h.queue.next();
        assertEquals("Renamed after submit", h.model.targetLabel());
        assertEquals("Integrated world", h.model.registrationReceipt().orElseThrow().targetLabel());
        assertSame(result, h.model.registrationReceipt().orElseThrow().result());
    }

    @Test void submittedRegistrationRejectsReentryMappingsAndBindingUntilActualOutcomeIsAccepted() {
        var h = new Harness(); var prompt = missing(h); var mapping = h.model.mappings(); h.model.register(prompt);
        assertThrows(IllegalStateException.class, () -> h.model.register(prompt));
        assertThrows(IllegalStateException.class, h.model::registrationPrompt);
        assertThrows(IllegalStateException.class, h.model::bind);
        assertThrows(IllegalStateException.class, () -> h.model.setMapping(OVERWORLD, NETHER));
        assertThrows(IllegalStateException.class, () -> h.model.setMapping(OVERWORLD, OVERWORLD));
        assertThrows(IllegalStateException.class, () -> h.model.removeMapping(OVERWORLD));
        assertThrows(IllegalStateException.class, () -> h.model.removeMapping(id("absent:dimension")));
        assertEquals(mapping, h.model.mappings()); assertEquals(1, h.target.registrations.size());
        var result = registered(); h.target.registrations.get(0).accept(result);
        assertThrows(IllegalStateException.class, h.model::bind);
        h.queue.next(); assertFalse(h.model.registering()); assertFalse(h.model.busy());
        assertSame(result, h.model.registrationReceipt().orElseThrow().result()); assertTrue(h.model.worldId().isEmpty());
        var accepted = h.bind(); assertBound(h, accepted);
    }

    @Test void panelCloseDisconnectAndRefreshCannotCancelSubmittedRegistration() {
        var h = new Harness(); h.model.register(missing(h));
        h.model.cancelPending(); assertTrue(h.model.registering());
        h.model.disconnect(); assertTrue(h.model.registering());
        h.target.current = false; h.host.selected = new FakeTarget("Replacement"); h.model.refresh();
        assertTrue(h.model.registering()); assertTrue(h.model.busy()); assertTrue(h.model.worldId().isEmpty());
        assertThrows(IllegalStateException.class, h.model::bind);
        var result = registered(); h.target.registrations.get(0).accept(result); h.queue.next();
        var receipt = h.model.registrationReceipt().orElseThrow();
        assertEquals("Integrated world", receipt.targetLabel()); assertSame(result, receipt.result());
        assertEquals("Replacement", h.model.targetLabel()); assertTrue(h.model.worldId().isEmpty());
        assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state()); assertFalse(h.model.registering());
        assertTrue(h.host.selected.registrations.isEmpty());
    }

    @Test void successfulRegistrationFromWorkerIsAnOwnerAcceptedReceiptAndRequiresFreshBinding() throws Exception {
        var h = new Harness(); h.model.register(missing(h)); var result = registered();
        onWorker(() -> h.target.registrations.get(0).accept(result));
        assertTrue(h.model.registering()); assertNull(h.model.registrationReceipt().orElseThrow().result());
        h.queue.next();
        assertFalse(h.model.registering()); assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state());
        assertEquals(WorldTargetWorkbench.text("world_registered_verify"), h.model.status());
        assertSame(result, h.model.registrationReceipt().orElseThrow().result());
        assertTrue(h.model.worldId().isEmpty()); assertFalse(h.model.canRegister());
        assertEquals(1, h.target.requests.size(), "registration never auto-submits a bind request");
        var accepted = h.bind(); assertBound(h, accepted);
        assertSame(result, h.model.registrationReceipt().orElseThrow().result());
    }

    @Test void rejectedRegistrationDispatchKeepsActualSuccessOrUnknownFailureForOwnerTick() throws Exception {
        for (boolean success : List.of(false, true)) {
            var h = new Harness(); h.model.register(missing(h));
            var diagnostic = new IllegalStateException("UNKNOWN: original publication must be recovered");
            var result = success ? registered() : new WorldTargetWorkbench.RegistrationResult(null, FAILURE, diagnostic);
            h.queue.reject = true;
            onWorker(() -> h.target.registrations.get(0).accept(result));
            assertTrue(h.model.registering()); assertNull(h.model.registrationReceipt().orElseThrow().result());
            h.model.tick(false);
            assertSame(result, h.model.registrationReceipt().orElseThrow().result());
            assertFalse(h.model.registering()); assertFalse(h.model.busy()); assertTrue(h.model.worldId().isEmpty());
            assertEquals(success ? WorldTargetWorkbench.State.UNBOUND : WorldTargetWorkbench.State.FAILED, h.model.state());
            assertEquals(success ? WorldTargetWorkbench.text("world_registered_verify") : FAILURE, h.model.status());
            if (success) assertTrue(h.model.diagnostic().isEmpty()); else assertSame(diagnostic, h.model.diagnostic().orElseThrow());
            assertFalse(h.model.canRegister()); assertEquals(1, h.target.registrations.size());
        }
    }

    @Test void changedConnectionKeepsOriginalRegistrationReceiptWithoutChangingTheNewTargetState() throws Exception {
        for (boolean success : List.of(false, true)) {
            var h = new Harness(); h.model.register(missing(h));
            h.target.current = false; h.model.tick(false);
            h.host.selected = new FakeTarget("Replacement"); h.model.refresh();
            var diagnostic = new IllegalStateException("original target UNKNOWN");
            var result = success ? registered() : new WorldTargetWorkbench.RegistrationResult(null, FAILURE, diagnostic);
            onWorker(() -> h.target.registrations.get(0).accept(result)); h.queue.next();
            var receipt = h.model.registrationReceipt().orElseThrow();
            assertEquals("Integrated world", receipt.targetLabel()); assertSame(result, receipt.result());
            assertEquals("Replacement", h.model.targetLabel()); assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state());
            assertEquals(WorldTargetWorkbench.text("world_unverified"), h.model.status());
            assertTrue(h.model.diagnostic().isEmpty()); assertTrue(h.model.worldId().isEmpty());
            assertTrue(h.host.selected.requests.isEmpty()); assertTrue(h.host.selected.registrations.isEmpty());
        }
    }

    @Test void firstRegistrationOutcomeWinsOverDuplicateCallbacksAndThrowAfterSuccess() {
        var h = new Harness(); var result = registered(); var prompt = missing(h);
        h.target.registrationHook = completed -> {
            completed.accept(result);
            completed.accept(new WorldTargetWorkbench.RegistrationResult(null, FAILURE, new IllegalStateException("duplicate")));
            throw new IllegalStateException("throw after confirmed publication");
        };
        h.model.register(prompt); assertEquals(1, h.queue.tasks.size()); h.queue.next();
        assertSame(result, h.model.registrationReceipt().orElseThrow().result());
        assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state()); assertTrue(h.model.diagnostic().isEmpty());
        long version = h.model.version();
        h.target.registrations.get(0).accept(new WorldTargetWorkbench.RegistrationResult(null, FAILURE, null));
        h.model.tick(false);
        assertEquals(version, h.model.version()); assertTrue(h.queue.tasks.isEmpty());
        assertSame(result, h.model.registrationReceipt().orElseThrow().result()); assertTrue(h.model.worldId().isEmpty());
    }

    @Test void registrationFailureKeepsExistingIdentityOrUnknownDiagnosticAndNeverGrantsBinding() {
        for (String reason : List.of("EXISTS: do not overwrite", "UNKNOWN: recover original request")) {
            var h = new Harness(); h.model.register(missing(h));
            var diagnostic = new IllegalStateException(reason);
            var result = new WorldTargetWorkbench.RegistrationResult(null, FAILURE, diagnostic);
            h.target.registrations.get(0).accept(result); h.queue.next();
            assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state()); assertEquals(FAILURE, h.model.status());
            assertSame(diagnostic, h.model.diagnostic().orElseThrow());
            assertSame(result, h.model.registrationReceipt().orElseThrow().result());
            assertFalse(h.model.canRegister()); assertTrue(h.model.worldId().isEmpty()); assertEquals(1, h.target.requests.size());
        }
        var h = new Harness(); var diagnostic = new IllegalStateException("host registration failed before callback");
        h.target.registrationHook = completed -> { throw diagnostic; };
        h.model.register(missing(h)); h.queue.next();
        assertSame(diagnostic, h.model.registrationReceipt().orElseThrow().result().diagnostic());
        assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state()); assertTrue(h.model.worldId().isEmpty());
    }

    @Test void registrationOwnerOperationsRejectWrongThreadAndResultRequiresOneOutcome() throws Exception {
        var h = new Harness(); var prompt = missing(h);
        onWorker(() -> {
            assertThrows(IllegalStateException.class, h.model::registrationPrompt);
            assertThrows(IllegalStateException.class, h.model::registrationReceipt);
            assertThrows(IllegalStateException.class, h.model::registering);
            assertThrows(IllegalStateException.class, () -> h.model.canRegister(prompt));
            assertThrows(IllegalStateException.class, () -> h.model.register(prompt));
        });
        assertTrue(h.target.registrations.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new WorldTargetWorkbench.RegistrationResult(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WorldTargetWorkbench.RegistrationResult(UUID.randomUUID(), FAILURE, null));
        assertThrows(IllegalArgumentException.class, () -> new WorldTargetWorkbench.Result(new FakeConnection(), null, null, WorldTargetWorkbench.FailureKind.IDENTITY_MISSING));
    }

    @Test void stoppedQueuedSubmissionUnlocksRegistrationAndLateNativeTaskNeverWrites() throws Exception {
        var h = new Harness(); var submission = new AtomicReference<WorldTargetWorkbench.RegistrationSubmission>();
        h.target.registrationHook = completed -> submission.set(new WorldTargetWorkbench.RegistrationSubmission(completed));
        var diagnostic = new IllegalStateException("server stopped before registration started");
        var rejected = new WorldTargetWorkbench.RegistrationResult(null, FAILURE, diagnostic);
        h.target.registrationPoll = () -> submission.get().rejectBeforeStart(rejected);
        h.model.register(missing(h)); assertTrue(h.model.busy());
        h.model.tick(false);
        assertFalse(h.model.busy()); assertFalse(h.model.registering());
        assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state());
        assertSame(rejected, h.model.registrationReceipt().orElseThrow().result());
        assertSame(diagnostic, h.model.diagnostic().orElseThrow()); assertEquals(1, h.target.registrationPolls);
        var writes = new AtomicInteger();
        onWorker(() -> submission.get().run(() -> { writes.incrementAndGet(); return registered(); }));
        assertEquals(0, writes.get(), "a runnable arriving after pre-start rejection must not publish identity");
        h.queue.next(); long version = h.model.version();
        h.model.tick(false); h.model.tick(true);
        assertEquals(1, h.target.registrationPolls); assertEquals(version, h.model.version());
        assertSame(rejected, h.model.registrationReceipt().orElseThrow().result());
        var accepted = h.bind(); assertBound(h, accepted);
    }

    @Test void startedWriteKeepsItsRealSuccessOrUnknownWhenStopPollingRacesIt() throws Exception {
        for (boolean success : List.of(false, true)) {
            var h = new Harness(); var submission = new AtomicReference<WorldTargetWorkbench.RegistrationSubmission>();
            h.target.registrationHook = completed -> submission.set(new WorldTargetWorkbench.RegistrationSubmission(completed));
            var stopped = new WorldTargetWorkbench.RegistrationResult(null, new TextContent.Literal("Never started"), null);
            h.target.registrationPoll = () -> submission.get().rejectBeforeStart(stopped);
            h.model.register(missing(h));
            var entered = new CountDownLatch(1); var finishWrite = new CountDownLatch(1);
            var diagnostic = new IllegalStateException("UNKNOWN: write started and needs recovery");
            var actual = success ? registered() : new WorldTargetWorkbench.RegistrationResult(null, FAILURE, diagnostic);
            var workerFailure = new AtomicReference<Throwable>(); var writes = new AtomicInteger();
            Thread worker = new Thread(() -> {
                try {
                    submission.get().run(() -> {
                        writes.incrementAndGet(); entered.countDown(); await(finishWrite); return actual;
                    });
                } catch (Throwable failure) { workerFailure.set(failure); }
            }, "world-registration-started-worker");
            worker.start();
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                h.model.tick(false); h.model.tick(true);
                assertTrue(h.model.registering()); assertTrue(h.model.busy());
                assertNull(h.model.registrationReceipt().orElseThrow().result());
                assertThrows(IllegalStateException.class, h.model::bind);
            } finally { finishWrite.countDown(); worker.join(10000); }
            assertFalse(worker.isAlive()); assertNull(workerFailure.get()); assertEquals(1, writes.get());
            h.model.tick(false);
            assertSame(actual, h.model.registrationReceipt().orElseThrow().result()); assertFalse(h.model.busy());
            assertEquals(success ? WorldTargetWorkbench.State.UNBOUND : WorldTargetWorkbench.State.FAILED, h.model.state());
            if (!success) assertSame(diagnostic, h.model.diagnostic().orElseThrow());
            assertTrue(h.model.worldId().isEmpty());
            long version = h.model.version(); h.queue.next();
            assertEquals(version, h.model.version()); assertSame(actual, h.model.registrationReceipt().orElseThrow().result());
        }
    }

    @Test void rejectedDeliveryOfFinishedSubmissionRecoversActualOutcomeOnNextOwnerTick() throws Exception {
        for (boolean success : List.of(false, true)) {
            var h = new Harness(); var submission = new AtomicReference<WorldTargetWorkbench.RegistrationSubmission>();
            h.target.registrationHook = completed -> submission.set(new WorldTargetWorkbench.RegistrationSubmission(completed));
            h.target.registrationPoll = () -> submission.get().rejectBeforeStart(new WorldTargetWorkbench.RegistrationResult(null, new TextContent.Literal("Server stopped"), null));
            h.model.register(missing(h)); var diagnostic = new IllegalStateException("UNKNOWN after native write");
            var actual = success ? registered() : new WorldTargetWorkbench.RegistrationResult(null, FAILURE, diagnostic);
            h.queue.reject = true; onWorker(() -> submission.get().run(() -> actual));
            assertTrue(h.model.busy()); assertNull(h.model.registrationReceipt().orElseThrow().result());
            h.model.tick(false);
            assertFalse(h.model.busy()); assertSame(actual, h.model.registrationReceipt().orElseThrow().result());
            assertEquals(success ? WorldTargetWorkbench.State.UNBOUND : WorldTargetWorkbench.State.FAILED, h.model.state());
            assertEquals(success ? WorldTargetWorkbench.text("world_registered_verify") : FAILURE, h.model.status());
            if (success) assertTrue(h.model.diagnostic().isEmpty()); else assertSame(diagnostic, h.model.diagnostic().orElseThrow());
            assertTrue(h.model.worldId().isEmpty());
        }
    }

    @Test void queuedSubmissionRejectionStillUnlocksWhenOwnerDeliveryExecutorRejects() {
        var h = new Harness(); var submission = new AtomicReference<WorldTargetWorkbench.RegistrationSubmission>();
        h.target.registrationHook = completed -> submission.set(new WorldTargetWorkbench.RegistrationSubmission(completed));
        var actual = new WorldTargetWorkbench.RegistrationResult(null, FAILURE, new IllegalStateException("stopped while queued"));
        h.target.registrationPoll = () -> submission.get().rejectBeforeStart(actual);
        h.model.register(missing(h)); h.queue.reject = true; h.model.tick(false);
        assertFalse(h.model.busy()); assertSame(actual, h.model.registrationReceipt().orElseThrow().result());
        assertSame(actual.diagnostic(), h.model.diagnostic().orElseThrow());
        assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state());
        assertTrue(h.queue.tasks.isEmpty());
    }

    @Test void registrationPollFollowsOriginalTargetAfterDisconnectAndRefresh() throws Exception {
        var h = new Harness(); var submission = new AtomicReference<WorldTargetWorkbench.RegistrationSubmission>();
        h.target.registrationHook = completed -> submission.set(new WorldTargetWorkbench.RegistrationSubmission(completed));
        var actual = new WorldTargetWorkbench.RegistrationResult(null, FAILURE, new IllegalStateException("original server stopped"));
        h.target.registrationPoll = () -> submission.get().rejectBeforeStart(actual);
        h.model.register(missing(h)); h.target.current = false;
        h.host.selected = new FakeTarget("Replacement"); h.model.refresh(); h.model.tick(false);
        assertEquals(1, h.target.registrationPolls); assertEquals(0, h.host.selected.registrationPolls);
        assertFalse(h.model.busy()); assertEquals("Integrated world", h.model.registrationReceipt().orElseThrow().targetLabel());
        assertSame(actual, h.model.registrationReceipt().orElseThrow().result());
        assertEquals("Replacement", h.model.targetLabel()); assertEquals(WorldTargetWorkbench.State.UNBOUND, h.model.state());
        assertEquals(WorldTargetWorkbench.text("world_unverified"), h.model.status());
        var writes = new AtomicInteger();
        onWorker(() -> submission.get().run(() -> { writes.incrementAndGet(); return registered(); }));
        assertEquals(0, writes.get()); assertTrue(h.model.worldId().isEmpty());
    }

    @Test void nativeRuntimeExceptionAndErrorProduceRetainedReceiptsAndUnlockRegistration() throws Exception {
        for (Throwable thrown : List.of(new IllegalStateException("native registration failure"), new AssertionError("native registration error"))) {
            var h = new Harness(); var submission = new AtomicReference<WorldTargetWorkbench.RegistrationSubmission>();
            h.target.registrationHook = completed -> submission.set(new WorldTargetWorkbench.RegistrationSubmission(completed));
            h.model.register(missing(h));
            onWorker(() -> {
                Throwable observed = assertThrows(thrown.getClass(), () -> submission.get().run(() -> {
                    if (thrown instanceof RuntimeException failure) throw failure;
                    throw (Error) thrown;
                }));
                assertSame(thrown, observed);
            });
            h.model.tick(false);
            var result = h.model.registrationReceipt().orElseThrow().result();
            assertSame(thrown, result.diagnostic()); assertNull(result.worldId());
            assertEquals(WorldTargetWorkbench.text("world_registration_failed"), result.failure());
            assertEquals(WorldTargetWorkbench.State.FAILED, h.model.state()); assertFalse(h.model.busy());
            assertSame(thrown, h.model.diagnostic().orElseThrow()); assertTrue(h.model.worldId().isEmpty());
        }
    }

    @Test void aSuccessCannotRejectQueuedWorkOrConsumeTheOpportunityToRunIt() {
        var outcome = new AtomicReference<WorldTargetWorkbench.RegistrationResult>(); var deliveries = new AtomicInteger();
        var submission = new WorldTargetWorkbench.RegistrationSubmission(result -> { outcome.set(result); deliveries.incrementAndGet(); });
        var actual = registered();
        assertThrows(IllegalArgumentException.class, () -> submission.rejectBeforeStart(actual));
        assertNull(outcome.get()); assertEquals(0, deliveries.get());
        var writes = new AtomicInteger(); submission.run(() -> { writes.incrementAndGet(); return actual; });
        assertEquals(1, writes.get()); assertEquals(1, deliveries.get()); assertSame(actual, outcome.get());
    }

    private static EditorId id(String value) { return EditorId.parse(value); }
    private static WorldTargetWorkbench.Result success(FakeConnection connection) { return new WorldTargetWorkbench.Result(connection, null, null); }
    private static WorldTargetWorkbench.Result failure(Throwable diagnostic) { return new WorldTargetWorkbench.Result(null, FAILURE, diagnostic); }
    private static WorldTargetWorkbench.RegistrationResult registered() { return new WorldTargetWorkbench.RegistrationResult(UUID.randomUUID(), null, null); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "native write must be released"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static WorldTargetWorkbench.RegistrationPrompt missing(Harness h) {
        h.model.bind();
        h.host.selected.request(h.host.selected.requests.size() - 1).complete(new WorldTargetWorkbench.Result(null, FAILURE, null, WorldTargetWorkbench.FailureKind.IDENTITY_MISSING));
        h.queue.next(); return h.model.registrationPrompt();
    }
    private static void assertBound(Harness h, FakeConnection connection) {
        assertEquals(WorldTargetWorkbench.State.BOUND, h.model.state());
        assertEquals(Optional.of(connection.worldId), h.model.worldId());
    }
    private static void onWorker(Runnable action) throws Exception {
        var thrown = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable failure) { thrown.set(failure); } }, "world-target-test-worker");
        worker.start(); worker.join(10000);
        assertFalse(worker.isAlive(), "worker must finish");
        if (thrown.get() != null) throw new AssertionError("worker assertion failed", thrown.get());
    }
    private static final class Queue implements Executor {
        final Deque<Runnable> tasks = new ConcurrentLinkedDeque<>();
        boolean reject;
        @Override public void execute(Runnable command) { if (reject) throw new RejectedExecutionException("owner dispatch rejected"); tasks.addLast(command); }
        void next() { tasks.removeFirst().run(); }
        void last() { tasks.removeLast().run(); }
    }
    private static final class Request {
        final Map<EditorId, EditorId> mapping;
        final Consumer<WorldTargetWorkbench.Result> completion;
        int cancelled;
        Request(Map<EditorId, EditorId> mapping, Consumer<WorldTargetWorkbench.Result> completion) { this.mapping = mapping; this.completion = completion; }
        void complete(WorldTargetWorkbench.Result result) { completion.accept(result); }
    }
    private static final class FakeTarget implements WorldTargetWorkbench.Target {
        String label;
        final List<EditorId> dimensions = new ArrayList<>(List.of(OVERWORLD, NETHER));
        final List<Request> requests = new ArrayList<>();
        final List<Consumer<WorldTargetWorkbench.RegistrationResult>> registrations = new ArrayList<>();
        Consumer<Consumer<WorldTargetWorkbench.RegistrationResult>> registrationHook = completed -> {};
        Runnable registrationPoll = () -> {};
        int registrationPolls;
        Throwable invalidation;
        RuntimeException diagnosticFailure;
        int diagnosticReads;
        boolean current = true;
        FakeTarget(String label) { this.label = label; for (int n = 1; n <= 64; n++) dimensions.add(id("target:dim_" + n)); }
        @Override public String label() { return label; }
        @Override public List<EditorId> dimensions() { return dimensions; }
        @Override public EditorId currentDimension() { return OVERWORLD; }
        @Override public boolean current() { return current; }
        @Override public Throwable invalidationDiagnostic() {
            diagnosticReads++;
            if (diagnosticFailure != null) throw diagnosticFailure;
            return invalidation == null ? WorldTargetWorkbench.Target.super.invalidationDiagnostic() : invalidation;
        }
        @Override public WorldTargetWorkbench.Cancellation bind(Map<EditorId, EditorId> mapping, Consumer<WorldTargetWorkbench.Result> completion) {
            var request = new Request(mapping, completion); requests.add(request); return () -> request.cancelled++;
        }
        @Override public void register(Consumer<WorldTargetWorkbench.RegistrationResult> completed) {
            registrations.add(completed); registrationHook.accept(completed);
        }
        @Override public void pollRegistration() { registrationPolls++; registrationPoll.run(); }
        Request request(int index) { return requests.get(index); }
    }
    private static final class FakeHost implements WorldTargetWorkbench.Host {
        FakeTarget selected;
        FakeHost(FakeTarget target) { selected = target; }
        @Override public WorldTargetWorkbench.Capture capture() { return new WorldTargetWorkbench.Capture(selected, null); }
    }
    private static final class FakeConnection implements WorldTargetWorkbench.Connection {
        final UUID worldId = UUID.randomUUID(), requestId = UUID.randomUUID();
        final List<Consumer<WorldTargetWorkbench.Result>> verifications = new ArrayList<>();
        int closed;
        Runnable onClose = () -> {};
        @Override public UUID worldId() { return worldId; }
        @Override public UUID requestId() { return requestId; }
        @Override public void verify(Consumer<WorldTargetWorkbench.Result> completed) { verifications.add(completed); }
        @Override public void close() { closed++; onClose.run(); }
    }
    private static final class Harness {
        final FakeTarget target = new FakeTarget("Integrated world");
        final FakeHost host = new FakeHost(target);
        final Queue queue = new Queue();
        final WorldTargetWorkbench model = new WorldTargetWorkbench(host, queue);
        Harness() { model.refresh(); }
        FakeConnection bind() {
            model.bind(); var connection = new FakeConnection();
            host.selected.request(host.selected.requests.size() - 1).complete(success(connection)); queue.next();
            assertBound(this, connection); return connection;
        }
    }
}
