package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StudioPublicationCycleTest {
    @Test void closedCycleNeverBuildsOrAuthorizesAnEvent() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.invalidate();
        cycle.retry();
        assertFalse(cycle.refresh(builds::incrementAndGet));
        assertEquals(0, builds.get());
        assertFalse(cycle.building());
    }

    @Test void unchangedOpenCycleReusesItsPublicationUntilInvalidated() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.open();
        assertTrue(cycle.refresh(builds::incrementAndGet));
        assertTrue(cycle.refresh(builds::incrementAndGet));
        assertEquals(1, builds.get());
        cycle.invalidate();
        assertTrue(cycle.refresh(builds::incrementAndGet));
        assertEquals(2, builds.get());
    }

    @Test void closingAndReopeningRequiresANewPublication() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.open();
        assertTrue(cycle.refresh(builds::incrementAndGet));
        cycle.close();
        cycle.invalidate();
        assertFalse(cycle.refresh(builds::incrementAndGet));
        assertEquals(1, builds.get());
        cycle.open();
        assertTrue(cycle.refresh(builds::incrementAndGet));
        assertEquals(2, builds.get());
    }

    @Test void lifecycleTokenChangesOnlyWhenTheHostOpensOrCloses() {
        var cycle = new StudioPublicationCycle();
        long closed = cycle.epoch();
        assertFalse(cycle.current(closed));
        cycle.open();
        long opened = cycle.epoch();
        assertNotEquals(closed, opened);
        assertTrue(cycle.current(opened));
        cycle.invalidate();
        cycle.retry();
        assertTrue(cycle.current(opened));
        assertTrue(cycle.refresh(() -> {}));
        assertTrue(cycle.current(opened));
        cycle.close();
        assertFalse(cycle.current(opened));
        cycle.open();
        assertFalse(cycle.current(opened));
        assertTrue(cycle.current(cycle.epoch()));
    }

    @Test void failedRebuildBlocksOldFrameUntilExplicitRetry() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.open();
        assertTrue(cycle.refresh(builds::incrementAndGet));
        cycle.invalidate();
        var failure = new IllegalStateException("candidate failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> cycle.refresh(() -> { throw failure; })));
        assertTrue(cycle.failed());
        assertFalse(cycle.building());
        cycle.invalidate();
        assertFalse(cycle.refresh(builds::incrementAndGet));
        assertEquals(1, builds.get());
        cycle.retry();
        assertFalse(cycle.failed());
        assertTrue(cycle.refresh(builds::incrementAndGet));
        assertEquals(2, builds.get());
    }

    @Test void reopeningRecoversAFailedCycleWithoutUsingTheOldFrame() {
        var cycle = new StudioPublicationCycle();
        cycle.open();
        assertThrows(IllegalArgumentException.class,
                () -> cycle.refresh(() -> { throw new IllegalArgumentException("bad frame"); }));
        cycle.close();
        cycle.open();
        var builds = new AtomicInteger();
        assertFalse(cycle.failed());
        assertTrue(cycle.refresh(builds::incrementAndGet));
        assertEquals(1, builds.get());
    }

    @Test void invalidationDuringBuildIsPublishedBeforeAnEventCanContinue() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.open();
        assertTrue(cycle.refresh(() -> {
            assertTrue(cycle.building());
            if (builds.incrementAndGet() == 1) cycle.invalidate();
        }));
        assertEquals(2, builds.get());
        assertFalse(cycle.building());
    }

    @Test void recursiveRefreshCannotBuildOrAuthorizeAnEvent() {
        var cycle = new StudioPublicationCycle();
        var nestedBuilds = new AtomicInteger();
        var nestedEvents = new AtomicInteger();
        cycle.open();
        assertTrue(cycle.refresh(() -> {
            assertTrue(cycle.building());
            if (cycle.refresh(nestedBuilds::incrementAndGet)) nestedEvents.incrementAndGet();
            assertTrue(cycle.building());
        }));
        assertEquals(0, nestedBuilds.get());
        assertEquals(0, nestedEvents.get());
        assertFalse(cycle.building());
    }

    @Test void recursiveRefreshDoesNotConsumeAnInvalidation() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.open();
        assertTrue(cycle.refresh(() -> {
            if (builds.incrementAndGet() == 1) {
                cycle.invalidate();
                assertFalse(cycle.refresh(() -> fail("nested publication")));
            }
        }));
        assertEquals(2, builds.get());
    }

    @Test void closingDuringBuildBlocksTheWaitingEvent() {
        var cycle = new StudioPublicationCycle();
        var events = new AtomicInteger();
        cycle.open();
        if (cycle.refresh(() -> {
            cycle.close();
            assertTrue(cycle.building(), "resource retirement must wait until the builder unwinds");
            assertFalse(cycle.refresh(() -> fail("closed recursive publication")));
        })) events.incrementAndGet();
        assertEquals(0, events.get());
        assertFalse(cycle.building());
        assertFalse(cycle.refresh(() -> fail("closed publication")));
    }

    @Test void closeAndReopenInsideBuildCannotTransferTheOldEventToTheNewLifecycle() {
        var cycle = new StudioPublicationCycle();
        var oldBuilds = new AtomicInteger();
        var oldEvents = new AtomicInteger();
        cycle.open();
        if (cycle.refresh(() -> {
            if (oldBuilds.incrementAndGet() == 1) {
                cycle.close();
                cycle.open();
                assertFalse(cycle.refresh(() -> fail("reopened recursive publication")));
            }
        })) oldEvents.incrementAndGet();
        assertEquals(0, oldEvents.get(), "the initiating event belongs to the closed lifecycle");
        assertEquals(1, oldBuilds.get(), "the old callback must not rebuild the reopened lifecycle");
        var newBuilds = new AtomicInteger();
        assertTrue(cycle.refresh(newBuilds::incrementAndGet));
        assertEquals(1, newBuilds.get());
    }

    @Test void exceptionFromAnOldLifecycleDoesNotPoisonTheReopenedOne() {
        var cycle = new StudioPublicationCycle();
        cycle.open();
        var failure = new IllegalStateException("old builder unwinding");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> cycle.refresh(() -> {
            cycle.close();
            cycle.open();
            throw failure;
        })));
        assertFalse(cycle.building());
        assertFalse(cycle.failed());
        var builds = new AtomicInteger();
        assertTrue(cycle.refresh(builds::incrementAndGet));
        assertEquals(1, builds.get());
    }

    @Test void closingDuringASecondPassStopsFurtherPassesAndBlocksTheEvent() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.open();
        assertFalse(cycle.refresh(() -> {
            if (builds.incrementAndGet() == 1) cycle.invalidate();
            else cycle.close();
        }));
        assertEquals(2, builds.get());
        assertFalse(cycle.building());
    }

    @Test void perpetualInvalidationIsBoundedAndNeverAuthorizesAStaleFrame() {
        var cycle = new StudioPublicationCycle();
        var builds = new AtomicInteger();
        cycle.open();
        assertFalse(cycle.refresh(() -> { builds.incrementAndGet(); cycle.invalidate(); }));
        assertTrue(builds.get() > 0 && builds.get() <= 8);
        assertFalse(cycle.building());
        assertFalse(cycle.failed());
        var recoveryBuilds = new AtomicInteger();
        assertTrue(cycle.refresh(recoveryBuilds::incrementAndGet));
        assertEquals(1, recoveryBuilds.get());
    }
}
