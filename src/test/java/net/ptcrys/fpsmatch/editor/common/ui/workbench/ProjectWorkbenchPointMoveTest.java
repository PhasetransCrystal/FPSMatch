package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditors;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorkbenchPointMoveTest {
    @TempDir Path temporary;
    private static final EditorId OWNER = EditorId.parse("test:point_move");
    private static final EditorId TYPE = EditorId.parse("test:point_document");
    private static final EditorId DIMENSION = EditorId.parse("minecraft:overworld");
    private static final TextContent LABEL = new TextContent.Literal("Point");
    private static final SpatialDocument.Position TARGET = position("10.125", "65.5", "-4.75");

    @Test void previewIsPureAndOneCommitPreservesMetadataAndHasOneUndoStep() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("move")); UUID document = h.document("Alpha");
            var initial = h.handle(document).view(); long version = h.model.version();
            var start = h.model.beginPointMove(document, "point");
            assertEquals(initial.content().point(), start.origin()); assertTrue(start.target().isEmpty());
            var first = h.model.previewPointMove(start, position("1", "2", "3"));
            var preview = h.model.previewPointMove(first, TARGET);
            assertFalse(h.model.pointMoveCurrent(start)); assertFalse(h.model.pointMoveCurrent(first));
            assertTrue(h.model.pointMoveCurrent(preview)); assertEquals(Optional.of(TARGET), preview.target());
            assertEquals(initial, h.handle(document).view()); assertEquals(version, h.model.version());
            assertEquals(0, h.editor.moves);
            assertTrue(h.model.commitPointMove(preview)); assertFalse(h.model.pointMoveCurrent(preview));
            var moved = h.handle(document).view();
            assertTrue(TARGET.sameCoordinates(moved.content().point().position()));
            assertEquals(initial.content().point().dimension(), moved.content().point().dimension());
            assertEquals(initial.content().point().yaw(), moved.content().point().yaw());
            assertEquals(initial.content().point().pitch(), moved.content().point().pitch());
            assertTrue(initial.content().point().direction().isPresent());
            assertEquals(initial.content().point().direction(), moved.content().point().direction());
            assertTrue(initial.content().point().groundDirection().isPresent());
            assertEquals(initial.content().point().groundDirection(), moved.content().point().groundDirection());
            assertEquals(initial.content().other(), moved.content().other());
            assertEquals(initial.content().parent(), moved.content().parent());
            assertEquals(initial.content().note(), moved.content().note()); assertEquals(1, h.editor.moves);
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(preview));
            h.model.undo(); assertEquals(initial.content(), h.handle(document).view().content());
            assertFalse(h.model.canUndo()); h.model.redo(); assertEquals(moved.content(), h.handle(document).view().content());
        }
    }

    @Test void scaleOnlyChangeConsumesGestureWithoutEditingOrLosingExactNumbers() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("no-op")); UUID document = h.document("Alpha");
            var initial = h.handle(document).view(); long version = h.model.version();
            var point = initial.content().point();
            var target = new SpatialDocument.Position(point.x().setScale(8), point.y().setScale(8), point.z().setScale(8));
            var draft = h.model.previewPointMove(h.model.beginPointMove(document, "point"), target);
            assertFalse(h.model.commitPointMove(draft)); assertEquals(initial, h.handle(document).view());
            assertEquals(version, h.model.version()); assertEquals(0, h.editor.moves); assertFalse(h.model.canUndo());
            assertFalse(h.model.cancelPointMove(draft));
        }
    }

    @Test void oldPreviewForeignDraftAndLateCancellationCannotConsumeNewGesture() {
        try (var h = new Harness(); var foreign = new Harness()) {
            h.create(temporary.resolve("identity")); UUID document = h.document("Alpha");
            foreign.create(temporary.resolve("foreign")); UUID other = foreign.document("Beta");
            var old = h.model.beginPointMove(document, "point");
            var current = h.model.previewPointMove(old, TARGET);
            var foreignDraft = foreign.model.beginPointMove(other, "point");
            assertFalse(h.model.cancelPointMove(old)); assertFalse(h.model.cancelPointMove(foreignDraft));
            assertFalse(h.model.pointMoveCurrent(foreignDraft));
            assertThrows(SessionRejection.class, () -> h.model.previewPointMove(old, TARGET));
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(foreignDraft));
            assertTrue(h.model.pointMoveCurrent(current));
            var replacement = h.model.beginPointMove(document, "other");
            assertFalse(h.model.cancelPointMove(current)); assertTrue(h.model.pointMoveCurrent(replacement));
            assertTrue(h.model.cancelPointMove(replacement)); assertFalse(h.model.cancelPointMove(replacement));
            assertFalse(h.model.canUndo()); assertEquals(0, h.editor.moves);
        }
    }

    @Test void unpositionedCommitRejectsAndConsumesOnlyThatGesture() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("no-target")); UUID document = h.document("Alpha");
            var before = h.handle(document).view(); var draft = h.model.beginPointMove(document, "point");
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(draft));
            assertFalse(h.model.pointMoveCurrent(draft)); assertFalse(h.model.cancelPointMove(draft));
            assertEquals(before, h.handle(document).view()); assertEquals(0, h.editor.moves);
        }
    }

    @Test void pendingRawAndBusyStateRejectWithoutApplyingOrDiscardingInput() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("raw")); UUID document = h.document("Alpha");
            var address = new ProjectWorkbench.Address(document, "root", "note");
            h.model.input(address, "draft");
            assertThrows(SessionRejection.class, () -> h.model.beginPointMove(document, "point"));
            h.model.discardInput(address); var draft = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            var token = h.handle(document).view().token(); h.handle(document).setPendingInput("external", "raw");
            assertFalse(h.model.pointMoveCurrent(draft));
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(draft));
            assertEquals(token, h.handle(document).view().token());
            assertEquals("raw", h.handle(document).view().pendingInputs().get("external"));
            h.handle(document).clearPendingInput("external");
            var busyDraft = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            h.model.saveAll(); assertFalse(h.model.pointMoveCurrent(busyDraft));
            assertThrows(SessionRejection.class, () -> h.model.beginPointMove(document, "point"));
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(busyDraft)); h.drain();
            assertEquals(0, h.editor.moves);
        }
    }

    @Test void fullTokensRejectDirectEditsUndoRedoAndEqualRevisionBranchReplacement() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("tokens")); UUID document = h.document("Alpha");
            var direct = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            long version = h.model.version(); h.note(document, "changed");
            assertEquals(version, h.model.version()); assertFalse(h.model.pointMoveCurrent(direct));
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(direct));
            var undo = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET); h.model.undo();
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(undo));
            var redo = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET); h.model.redo();
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(redo));
            h.model.discardDocumentChanges(document);
            var branch = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            h.model.discardDocumentChanges(document);
            assertEquals(branch.token().revision(), h.handle(document).view().token().revision());
            assertNotEquals(branch.token().branchId(), h.handle(document).view().token().branchId());
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(branch));
            assertEquals(0, h.editor.moves);
        }
    }

    @Test void commitAddressesCapturedDocumentAndPreservesOtherDocuments() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("document")); UUID alpha = h.document("Alpha"), beta = h.document("Beta");
            var betaBefore = h.handle(beta).view();
            var draft = h.model.previewPointMove(h.model.beginPointMove(alpha, "point"), TARGET);
            assertEquals(beta, h.model.activeDocument().orElseThrow());
            assertTrue(h.model.commitPointMove(draft));
            assertTrue(TARGET.sameCoordinates(h.handle(alpha).view().content().point().position()));
            assertEquals(betaBefore, h.handle(beta).view()); assertEquals(beta, h.model.activeDocument().orElseThrow());
        }
    }

    @Test void postconditionsAndDomainValidationRejectCompleteCandidateAtomically() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("invalid")); UUID document = h.document("Alpha");
            for (String failure : List.of("throw", "wrong-position", "orientation", "direction", "missing-direction",
                    "other-direction", "ground-direction", "missing-ground-direction", "other-ground-direction",
                    "dimension", "other-point", "remove", "reparent", "invalid-domain")) {
                var before = h.handle(document).view();
                var draft = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
                h.editor.failure = failure;
                assertThrows(RuntimeException.class, () -> h.model.commitPointMove(draft), failure);
                assertEquals(before, h.handle(document).view(), failure); assertFalse(h.model.pointMoveCurrent(draft));
                assertFalse(h.model.canUndo(), failure); h.editor.failure = "";
            }
            assertTrue(h.model.commitPointMove(h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET)));
        }
    }

    @Test void groundFacingCannotBeChangedAddedOrRemovedOnTheTargetOrAnotherPoint() {
        for (String subject : List.of("point", "other")) for (String mutation : List.of("change", "add", "remove")) {
            try (var h = new Harness()) {
                h.create(temporary.resolve("ground-" + subject + "-" + mutation));
                UUID document = h.document("Alpha");
                Optional<SpatialDocument.Direction> ground = mutation.equals("add") ? Optional.empty()
                        : Optional.of(new SpatialDocument.Direction(0, 0, 1));
                var handle = h.handle(document);
                handle.edit(handle.view().token(), LABEL, value -> new State(
                        subject.equals("point") ? withGroundDirection(value.point(), ground) : value.point(),
                        subject.equals("other") ? withGroundDirection(value.other(), ground) : value.other(),
                        "ground-fixture", value.parent(), value.present()));
                var before = handle.view();
                assertEquals(ground, (subject.equals("point") ? before.content().point() : before.content().other()).groundDirection());
                var draft = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
                h.editor.failure = subject.equals("point")
                        ? mutation.equals("remove") ? "missing-ground-direction" : "ground-direction"
                        : mutation.equals("remove") ? "missing-other-ground-direction" : "other-ground-direction";
                assertThrows(RuntimeException.class, () -> h.model.commitPointMove(draft), subject + " " + mutation);
                assertEquals(before, handle.view(), "rejection preserves content, token and history");
                assertFalse(h.model.pointMoveCurrent(draft));
            }
        }
    }

    @Test void changedGroundDeclarationInvalidatesAnExistingMoveWithoutATokenChange() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("ground-source")); UUID document = h.document("Alpha");
            var before = h.handle(document).view();
            var draft = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            h.editor.declaration = "ground-direction";
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(draft));
            assertEquals(before, h.handle(document).view());
            assertEquals(0, h.editor.moves);
        }
    }

    @Test void invalidDeclarationsAndChangingReadNeverInstallADraft() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("declarations")); UUID document = h.document("Alpha");
            assertEquals(List.of("point", "other"), h.model.spatialPoints(document).stream().map(SpatialDocument.Point::node).toList());
            assertThrows(UnsupportedOperationException.class, () -> h.model.spatialPoints(document).clear());
            for (String invalid : List.of("duplicate", "missing", "budget")) {
                h.editor.declaration = invalid;
                assertThrows(RuntimeException.class, () -> h.model.beginPointMove(document, "point"));
            }
            h.editor.declaration = "none"; assertTrue(h.model.spatialPoints(document).isEmpty());
            assertThrows(SessionRejection.class, () -> h.model.beginPointMove(document, "point"));
            h.editor.declaration = "";
            h.editor.readHook = () -> h.note(document, "during-read");
            assertThrows(SessionRejection.class, () -> h.model.beginPointMove(document, "point"));
            assertThrows(SessionRejection.class, () -> h.model.beginPointMove(document, "root"));
            assertTrue(h.model.pointMoveCurrent(h.model.beginPointMove(document, "point")));
        }
    }

    @Test void commitRevalidatesTheTypedTargetAndTokenAfterContributorReads() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("commit-recheck")); UUID document = h.document("Alpha");
            var missing = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            h.editor.declaration = "none";
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(missing));
            h.editor.declaration = "";
            var changed = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            var originalPoint = h.handle(document).view().content().point();
            h.editor.readHook = () -> h.note(document, "changed-before-commit");
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(changed));
            assertEquals(originalPoint, h.handle(document).view().content().point());
            assertEquals("changed-before-commit", h.handle(document).view().content().note());
            assertEquals(0, h.editor.moves);
        }
    }

    @Test void closeAndSameIdReopenRetireOldCapturesAndKeepTheSavedMove() {
        try (var h = new Harness()) {
            Path path = temporary.resolve("reopen"); h.create(path); UUID document = h.document("Alpha");
            assertTrue(h.model.commitPointMove(h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET)));
            h.model.saveAll(); h.drain();
            var old = h.model.previewPointMove(h.model.beginPointMove(document, "point"), position("7", "8", "9"));
            var handle = h.handle(document); h.model.closeProject(true); h.drain();
            assertFalse(h.model.pointMoveCurrent(old)); assertFalse(h.model.cancelPointMove(old));
            assertTrue(h.model.spatialPoints(document).isEmpty());
            h.model.open(path); h.drain(); assertNotSame(handle, h.handle(document));
            assertThrows(SessionRejection.class, () -> h.model.commitPointMove(old));
            assertTrue(TARGET.sameCoordinates(h.handle(document).view().content().point().position()));
            assertEquals(initial().point().direction(), h.handle(document).view().content().point().direction());
            assertEquals(initial().point().groundDirection(), h.handle(document).view().content().point().groundDirection());
        }
    }

    @Test void ownerThreadIsRequiredEvenForCurrentAndCancel() throws InterruptedException {
        try (var h = new Harness()) {
            h.create(temporary.resolve("thread")); UUID document = h.document("Alpha");
            var draft = h.model.beginPointMove(document, "point"); var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { h.model.cancelPointMove(draft); } catch (Throwable thrown) { failure.set(thrown); } });
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(h.model.pointMoveCurrent(draft));
        }
    }

    @Test void exactCoordinateValuesAndDefaultCapabilityHaveNarrowContracts() {
        var tiny = new BigDecimal("1e-1000"); var p = new SpatialDocument.Position(tiny, BigDecimal.ZERO, BigDecimal.ONE);
        assertSame(tiny, p.x()); assertTrue(position("1", "2", "3").sameCoordinates(position("1.00", "2.0", "3.000")));
        assertThrows(IllegalArgumentException.class, () -> position("1e1000", "0", "0"));
        var empty = new DocumentEditor<State>() {
            public Class<State> valueType() { return State.class; }
            public TextContent label() { return LABEL; }
            public List<Node> nodes(State value) { return List.of(); }
            public List<Field> fields(State value, String node) { return List.of(); }
            public State apply(State value, String node, String property, String raw) { return value; }
        };
        assertTrue(empty.spatialPoints(initial()).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> empty.moveSpatialPoint(initial(), "point", TARGET));
        assertTrue(empty.spatialAreas(initial()).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> empty.setSpatialAreaBounds(initial(), "area", initial().area().bounds()));
    }

    private static SpatialDocument.Position position(String x, String y, String z) {
        return new SpatialDocument.Position(new BigDecimal(x), new BigDecimal(y), new BigDecimal(z));
    }

    @Test void areaPreviewKeepsOrderedExactCornersAndCommitsBothEndpointsAsOneHistoryStep() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("area-history")); UUID document = h.document("Area");
            var before = h.handle(document).view(); long version = h.model.version();
            var target = bounds("-90.00000000000001", "5.25", "1e-1000", "10.125", "5.25", "-4");
            var start = h.model.beginAreaEdit(document, "area");
            var first = h.model.previewAreaEdit(start, before.content().area().bounds());
            var draft = h.model.previewAreaEdit(first, target);
            assertEquals(before.content().area(), draft.origin()); assertEquals(Optional.of(target), draft.target());
            assertFalse(h.model.areaEditCurrent(start)); assertFalse(h.model.areaEditCurrent(first));
            assertTrue(h.model.areaEditCurrent(draft)); assertEquals(before, h.handle(document).view());
            assertEquals(version, h.model.version()); assertEquals(0, h.editor.areaEdits);
            assertTrue(h.model.commitAreaEdit(draft));
            var after = h.handle(document).view();
            assertEquals(target, after.content().area().bounds());
            assertEquals(before.content().area().semantics(), after.content().area().semantics());
            assertEquals(before.content().area().dimension(), after.content().area().dimension());
            assertEquals(before.content().otherArea(), after.content().otherArea());
            assertEquals(before.content().point(), after.content().point());
            assertEquals(before.content().other(), after.content().other());
            assertEquals(before.content().note(), after.content().note());
            assertEquals(before.token().revision() + 1, after.token().revision());
            assertEquals(1, h.editor.areaEdits); assertFalse(h.model.areaEditCurrent(draft));
            h.model.undo(); assertEquals(before.content(), h.handle(document).view().content()); assertFalse(h.model.canUndo());
            h.model.redo(); assertEquals(after.content(), h.handle(document).view().content());
            h.model.saveAll(); h.drain(); h.model.closeProject(true); h.drain();
            h.model.open(temporary.resolve("area-history")); h.drain();
            assertEquals(after.content(), h.handle(document).view().content());
        }
    }

    @Test void areaCancelNoTargetNoOpAndForeignDraftsNeverCreateHistory() {
        try (var h = new Harness(); var other = new Harness()) {
            h.create(temporary.resolve("area-cancel")); UUID document = h.document("Area");
            other.create(temporary.resolve("area-foreign")); UUID foreignDocument = other.document("Area");
            var before = h.handle(document).view(); long version = h.model.version();
            var old = h.model.beginAreaEdit(document, "area");
            var current = h.model.previewAreaEdit(old, bounds("10.0", "3.00", "9.000", "-2.00", "3.0", "4.0000"));
            var foreign = other.model.beginAreaEdit(foreignDocument, "area");
            assertFalse(h.model.cancelAreaEdit(old)); assertFalse(h.model.cancelAreaEdit(foreign));
            assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(foreign));
            assertThrows(SessionRejection.class, () -> h.model.previewAreaEdit(old, before.content().area().bounds()));
            assertTrue(h.model.areaEditCurrent(current)); assertFalse(h.model.commitAreaEdit(current));
            var empty = h.model.beginAreaEdit(document, "area");
            assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(empty));
            assertFalse(h.model.cancelAreaEdit(empty));
            var cancelled = h.model.beginAreaEdit(document, "area");
            assertTrue(h.model.cancelAreaEdit(cancelled)); assertFalse(h.model.cancelAreaEdit(cancelled));
            assertEquals(before, h.handle(document).view()); assertEquals(version, h.model.version());
            assertFalse(h.model.canUndo()); assertEquals(0, h.editor.areaEdits);
        }
    }

    @Test void areaAndPointGesturesRetireEachOtherWithoutLateCancellation() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("area-point")); UUID document = h.document("Area");
            var point = h.model.beginPointMove(document, "point");
            var area = h.model.beginAreaEdit(document, "area");
            assertFalse(h.model.pointMoveCurrent(point)); assertFalse(h.model.cancelPointMove(point));
            assertTrue(h.model.areaEditCurrent(area));
            var next = h.model.beginPointMove(document, "point");
            assertFalse(h.model.areaEditCurrent(area)); assertFalse(h.model.cancelAreaEdit(area));
            assertTrue(h.model.pointMoveCurrent(next)); assertFalse(h.model.canUndo());
        }
    }

    @Test void areaCandidatesCannotAlterMetadataOtherAreasPointsOrHierarchy() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("area-invalid")); UUID document = h.document("Area");
            var target = bounds("7", "8", "9", "1", "2", "3");
            for (String failure : List.of("throw", "wrong-bounds", "dimension", "semantics", "other-area",
                    "point-position", "point-direction", "remove", "reparent", "invalid-domain")) {
                var before = h.handle(document).view();
                var draft = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), target);
                h.editor.failure = failure;
                assertThrows(RuntimeException.class, () -> h.model.commitAreaEdit(draft), failure);
                assertEquals(before, h.handle(document).view(), failure); assertFalse(h.model.areaEditCurrent(draft));
                assertFalse(h.model.canUndo()); h.editor.failure = "";
            }
            var point = h.model.previewPointMove(h.model.beginPointMove(document, "point"), TARGET);
            h.editor.failure = "area-bounds";
            assertThrows(RuntimeException.class, () -> h.model.commitPointMove(point)); assertFalse(h.model.canUndo());
        }
    }

    @Test void areaSourceChecksRejectInvalidDeclarationsReentrantReadsAndChangedSemantics() {
        try (var h = new Harness()) {
            h.create(temporary.resolve("area-declaration")); UUID document = h.document("Area");
            assertEquals(2, h.model.spatialAreas(document).size());
            assertThrows(UnsupportedOperationException.class, () -> h.model.spatialAreas(document).clear());
            for (String declaration : List.of("duplicate", "missing", "point-id", "budget", "none")) {
                h.editor.areaDeclaration = declaration;
                assertThrows(RuntimeException.class, () -> h.model.beginAreaEdit(document, "area"), declaration);
            }
            h.editor.areaDeclaration = "";
            h.editor.areaReadHook = () -> h.note(document, "changed-during-read");
            assertThrows(SessionRejection.class, () -> h.model.beginAreaEdit(document, "area"));
            for (String declaration : List.of("changed", "semantics")) {
                h.editor.areaDeclaration = "";
                var draft = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), bounds("1", "2", "3", "4", "5", "6"));
                var before = h.handle(document).view(); h.editor.areaDeclaration = declaration;
                assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(draft));
                assertEquals(before, h.handle(document).view()); assertEquals(0, h.editor.areaEdits);
            }
        }
    }

    @Test void areaTokensPendingInputBusyAndHandleReplacementRejectWithoutOverwriting() {
        try (var h = new Harness()) {
            Path path = temporary.resolve("area-stale"); h.create(path); UUID document = h.document("Area");
            var target = bounds("1", "2", "3", "4", "5", "6");
            var direct = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), target); h.note(document, "external");
            assertFalse(h.model.areaEditCurrent(direct)); assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(direct));
            var undo = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), target); h.model.undo();
            assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(undo));
            var redo = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), target); h.model.redo();
            assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(redo));
            h.model.discardDocumentChanges(document);
            var branch = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), target); h.model.discardDocumentChanges(document);
            assertEquals(branch.token().revision(), h.handle(document).view().token().revision());
            assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(branch));
            var raw = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), target);
            h.handle(document).setPendingInput("external", "raw");
            assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(raw));
            assertEquals("raw", h.handle(document).view().pendingInputs().get("external")); h.handle(document).clearPendingInput("external");
            var busy = h.model.previewAreaEdit(h.model.beginAreaEdit(document, "area"), target); h.model.saveAll();
            assertFalse(h.model.areaEditCurrent(busy)); assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(busy)); h.drain();
            var old = h.model.beginAreaEdit(document, "area"); var handle = h.handle(document);
            h.model.closeProject(true); h.drain(); assertFalse(h.model.cancelAreaEdit(old));
            h.model.open(path); h.drain(); assertNotSame(handle, h.handle(document));
            assertThrows(SessionRejection.class, () -> h.model.commitAreaEdit(old)); assertEquals(0, h.editor.areaEdits);
        }
    }

    @Test void areaUsesItsCapturedDocumentAndEnforcesTheOwnerThread() throws InterruptedException {
        try (var h = new Harness()) {
            h.create(temporary.resolve("area-owner")); UUID alpha = h.document("Alpha"), beta = h.document("Beta");
            var betaBefore = h.handle(beta).view();
            var draft = h.model.previewAreaEdit(h.model.beginAreaEdit(alpha, "area"), bounds("1", "2", "3", "4", "5", "6"));
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { h.model.areaEditCurrent(draft); } catch (Throwable thrown) { failure.set(thrown); } });
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(h.model.areaEditCurrent(draft)); assertTrue(h.model.commitAreaEdit(draft));
            assertEquals(betaBefore, h.handle(beta).view()); assertEquals(beta, h.model.activeDocument().orElseThrow());
        }
    }

    private static SpatialDocument.Point point(String node, EditorId dimension, SpatialDocument.Position position, BigDecimal yaw, BigDecimal pitch) {
        return new SpatialDocument.Point(node, dimension, position.x(), position.y(), position.z(), yaw, pitch);
    }
    private static SpatialDocument.Point withDirection(SpatialDocument.Point point, Optional<SpatialDocument.Direction> direction) {
        return new SpatialDocument.Point(point.node(), point.dimension(), point.x(), point.y(), point.z(),
                point.yaw(), point.pitch(), direction);
    }
    private static SpatialDocument.Point withGroundDirection(SpatialDocument.Point point, Optional<SpatialDocument.Direction> ground) {
        return new SpatialDocument.Point(point.node(), point.dimension(), point.x(), point.y(), point.z(),
                point.yaw(), point.pitch(), point.direction(), ground);
    }
    private static final SpatialDocument.AreaSemantics AREA_SEMANTICS = new SpatialDocument.AreaSemantics(
            SpatialDocument.AreaCoordinates.DECIMAL, SpatialDocument.AreaBoundary.ENDPOINT_PLANES,
            Set.of(SpatialDocument.AreaUse.POSITION_HALF_OPEN));
    private static SpatialDocument.Area area(String node, SpatialDocument.AreaBounds bounds) {
        return new SpatialDocument.Area(node, DIMENSION, bounds, AREA_SEMANTICS);
    }
    private static SpatialDocument.AreaBounds bounds(String ax, String ay, String az, String bx, String by, String bz) {
        return new SpatialDocument.AreaBounds(position(ax, ay, az), position(bx, by, bz));
    }
    private record State(SpatialDocument.Point point, SpatialDocument.Point other, SpatialDocument.Area area,
                         SpatialDocument.Area otherArea, String note, String parent, boolean present) {
        State(SpatialDocument.Point point, SpatialDocument.Point other, String note, String parent, boolean present) {
            this(point, other, ProjectWorkbenchPointMoveTest.area("area", bounds("10.000", "3", "9", "-2", "3", "4")),
                    ProjectWorkbenchPointMoveTest.area("other-area", bounds("20", "30", "40", "22", "33", "44")), note, parent, present);
        }
    }
    private static State initial() {
        return new State(withGroundDirection(withDirection(point("point", DIMENSION, position("1.250", "64", "-2.500"), new BigDecimal("181.125"), new BigDecimal("-93.25")),
                        Optional.of(new SpatialDocument.Direction(1, 0, 0))), Optional.of(new SpatialDocument.Direction(0, 0, 1))),
                point("other", DIMENSION, position("9", "70", "4"), BigDecimal.ZERO, BigDecimal.ZERO), "base", "group", true);
    }
    private static final class CountingEditor implements DocumentEditor<State> {
        int moves, areaEdits; String failure = "", declaration = "", areaDeclaration = ""; Runnable readHook, areaReadHook;
        public Class<State> valueType() { return State.class; }
        public TextContent label() { return LABEL; }
        public List<Node> nodes(State value) {
            var result = new ArrayList<>(List.of(new Node("root", Optional.empty(), LABEL), new Node("group", Optional.of("root"), LABEL),
                    new Node("other", Optional.of("group"), LABEL)));
            if (value.present()) result.add(new Node("point", Optional.of(value.parent()), LABEL));
            if (value.area() != null) result.add(new Node("area", Optional.of(value.parent()), LABEL));
            result.add(new Node("other-area", Optional.of("group"), LABEL));
            return result;
        }
        public List<Node> sceneNodes(State value) { return List.of(new Node("root", Optional.empty(), LABEL)); }
        public List<Field> fields(State value, String node) { return List.of(new Field("note", LABEL, Kind.TEXT, value.note(), LABEL)); }
        public State apply(State value, String node, String property, String raw) { return new State(value.point(), value.other(),
                value.area(), value.otherArea(), raw, value.parent(), value.present()); }
        public List<SpatialDocument.Area> spatialAreas(State value) {
            if (areaReadHook != null) { var hook = areaReadHook; areaReadHook = null; hook.run(); }
            return switch (areaDeclaration) {
                case "none" -> List.of();
                case "duplicate" -> List.of(value.area(), value.area());
                case "missing" -> List.of(area("missing-area", value.area().bounds()));
                case "point-id" -> List.of(area("point", value.area().bounds()));
                case "budget" -> java.util.Collections.nCopies(8193, value.area());
                case "changed" -> List.of(area("area", bounds("1", "2", "3", "4", "5", "6")), value.otherArea());
                case "semantics" -> List.of(new SpatialDocument.Area("area", DIMENSION, value.area().bounds(),
                        new SpatialDocument.AreaSemantics(SpatialDocument.AreaCoordinates.DECIMAL, SpatialDocument.AreaBoundary.ENDPOINT_PLANES,
                                Set.of(SpatialDocument.AreaUse.BLOCK_CLOSED))), value.otherArea());
                default -> value.area() == null ? List.of(value.otherArea()) : List.of(value.area(), value.otherArea());
            };
        }
        public State setSpatialAreaBounds(State value, String node, SpatialDocument.AreaBounds target) {
            areaEdits++; if (failure.equals("throw")) throw new IllegalArgumentException("candidate rejected");
            var original = value.area();
            var changed = failure.equals("remove") ? null : new SpatialDocument.Area(node,
                    failure.equals("dimension") ? EditorId.parse("minecraft:the_nether") : original.dimension(),
                    failure.equals("wrong-bounds") ? original.bounds() : target,
                    failure.equals("semantics") ? new SpatialDocument.AreaSemantics(SpatialDocument.AreaCoordinates.DECIMAL,
                            SpatialDocument.AreaBoundary.ENDPOINT_PLANES, Set.of(SpatialDocument.AreaUse.BLOCK_CLOSED)) : original.semantics());
            var otherArea = failure.equals("other-area") ? area("other-area", target) : value.otherArea();
            var point = failure.equals("point-position") ? point("point", DIMENSION, TARGET, value.point().yaw(), value.point().pitch()) : value.point();
            if (failure.equals("point-direction")) point = withDirection(point, Optional.of(new SpatialDocument.Direction(0, 1, 0)));
            return new State(point, value.other(), changed, otherArea, failure.equals("invalid-domain") ? "invalid" : value.note(),
                    failure.equals("reparent") ? "root" : value.parent(), value.present());
        }
        public List<SpatialDocument.Point> spatialPoints(State value) {
            if (readHook != null) { var hook = readHook; readHook = null; hook.run(); }
            return switch (declaration) {
                case "none" -> List.of();
                case "duplicate" -> List.of(value.point(), value.point());
                case "missing" -> List.of(point("missing", DIMENSION, value.point().position(), BigDecimal.ZERO, BigDecimal.ZERO));
                case "budget" -> java.util.Collections.nCopies(8193, value.point());
                case "ground-direction" -> List.of(withGroundDirection(value.point(),
                        Optional.of(new SpatialDocument.Direction(0, 0, -1))), value.other());
                default -> value.present() ? List.of(value.point(), value.other()) : List.of(value.other());
            };
        }
        public State moveSpatialPoint(State value, String node, SpatialDocument.Position target) {
            moves++; if (failure.equals("throw")) throw new IllegalArgumentException("candidate rejected");
            var original = value.point();
            var moved = point(node, failure.equals("dimension") ? EditorId.parse("minecraft:the_nether") : original.dimension(),
                    failure.equals("wrong-position") ? original.position() : target,
                    failure.equals("orientation") ? BigDecimal.ZERO : original.yaw(), original.pitch());
            moved = withDirection(moved, switch (failure) {
                case "direction" -> Optional.of(new SpatialDocument.Direction(0, 1, 0));
                case "missing-direction" -> Optional.empty();
                default -> original.direction();
            });
            moved = withGroundDirection(moved, switch (failure) {
                case "ground-direction" -> Optional.of(new SpatialDocument.Direction(0, 0, -1));
                case "missing-ground-direction" -> Optional.empty();
                default -> original.groundDirection();
            });
            var other = failure.equals("other-point") ? point("other", DIMENSION, target, BigDecimal.ZERO, BigDecimal.ZERO) : value.other();
            if (failure.equals("other-direction")) other = withDirection(other, Optional.of(new SpatialDocument.Direction(0, 0, 1)));
            if (failure.equals("other-ground-direction")) other = withGroundDirection(other, Optional.of(new SpatialDocument.Direction(0, 0, -1)));
            if (failure.equals("missing-other-ground-direction")) other = withGroundDirection(other, Optional.empty());
            var changedArea = failure.equals("area-bounds") ? area("area", new SpatialDocument.AreaBounds(target, target)) : value.area();
            return new State(moved, other, changedArea, value.otherArea(), failure.equals("invalid-domain") ? "invalid" : value.note(),
                    failure.equals("reparent") ? "root" : value.parent(), !failure.equals("remove"));
        }
    }
    private static final class PointType implements DocumentType<State> {
        public Class<State> valueType() { return State.class; }
        public EditorId codecId() { return EditorId.parse("test:point_utf8"); }
        public int schemaVersion() { return 1; }
        public Set<Integer> readableSchemas() { return Set.of(1); }
        public State create() { return initial(); }
        public State snapshot(State value) { return value; }
        public List<Problem> validate(State value) { return value.note().equals("invalid")
                ? List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("test:invalid"), "point", LABEL)) : List.of(); }
        public byte[] encode(State value) {
            var p = value.point(); return String.join(";", p.dimension().toString(), p.x().toString(), p.y().toString(), p.z().toString(),
                    p.yaw().toString(), p.pitch().toString(), value.note(), value.parent(), Boolean.toString(value.present()),
                    encodeArea(value.area()), encodeArea(value.otherArea())).getBytes(StandardCharsets.UTF_8);
        }
        public State decode(int schema, byte[] bytes) {
            var values = new String(bytes, StandardCharsets.UTF_8).split(";");
            return new State(withGroundDirection(withDirection(point("point", EditorId.parse(values[0]), position(values[1], values[2], values[3]), new BigDecimal(values[4]), new BigDecimal(values[5])),
                            initial().point().direction()), initial().point().groundDirection()),
                    initial().other(), decodeArea("area", values, 9), decodeArea("other-area", values, 16),
                    values[6], values[7], Boolean.parseBoolean(values[8]));
        }
        private static String encodeArea(SpatialDocument.Area area) {
            var a = area.bounds().first(); var b = area.bounds().second();
            return String.join(";", area.dimension().toString(), a.x().toString(), a.y().toString(), a.z().toString(),
                    b.x().toString(), b.y().toString(), b.z().toString());
        }
        private static SpatialDocument.Area decodeArea(String node, String[] values, int offset) {
            return new SpatialDocument.Area(node, EditorId.parse(values[offset]), bounds(values[offset+1], values[offset+2],
                    values[offset+3], values[offset+4], values[offset+5], values[offset+6]), AREA_SEMANTICS);
        }
        public DocumentDependencies dependencies(State value) { return new DocumentDependencies(Set.of(), Set.of(), Set.of()); }
    }
    private static ExtensionRegistry registry(CountingEditor editor) {
        var api = new ApiVersion(1, 0); var builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.define(ResourceProducers.KIND); builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1", api, Set.of(), Set.of()));
        builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), TYPE), OWNER, Contribution.Side.COMMON, Set.of()), new PointType());
        DocumentEditors.register(builder, TYPE, OWNER, editor); return builder.freeze();
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable action) { tasks.addLast(action); }
    }
    private static final class Harness implements AutoCloseable {
        final Queue io = new Queue(), completion = new Queue(); final CountingEditor editor = new CountingEditor();
        final ProjectWorkbench model = new ProjectWorkbench(registry(editor), io, completion);
        void create(Path path) { model.create(path, "Points"); drain(); }
        UUID document(String name) { model.createDocument(TYPE, name); return model.activeDocument().orElseThrow(); }
        @SuppressWarnings("unchecked") DocumentHandle<State> handle(UUID document) { return (DocumentHandle<State>) model.documents().get(document); }
        void note(UUID document, String note) { var handle = handle(document); handle.edit(handle.view().token(), LABEL, value -> new State(
                value.point(), value.other(), value.area(), value.otherArea(), note, value.parent(), value.present())); }
        void drain() {
            for (int i = 0; !io.tasks.isEmpty() || !completion.tasks.isEmpty(); i++) {
                assertTrue(i < 32, "asynchronous work failed to settle");
                if (!io.tasks.isEmpty()) io.tasks.removeFirst().run();
                if (!completion.tasks.isEmpty()) completion.tasks.removeFirst().run();
            }
            assertFalse(model.busy());
        }
        public void close() { drain(); if (model.hasProject()) { model.closeProject(true); drain(); } }
    }
}
