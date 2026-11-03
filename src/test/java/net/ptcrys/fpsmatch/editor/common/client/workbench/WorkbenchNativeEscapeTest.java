package net.ptcrys.fpsmatch.editor.common.client.workbench;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchWorldSession.NativeEscape.dispatch;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises cancellation without constructing a Minecraft client or render target. */
class WorkbenchNativeEscapeTest {
    private static final class Child implements BooleanSupplier {
        int calls;
        boolean handled;
        Runnable action=()->{};
        @Override public boolean getAsBoolean(){calls++;action.run();return handled;}
    }

    @Test void refusalIsDeliveredOnceAndDoesNotBecomeForcedCancellation(){
        var child=new Child();
        var current=new AtomicReference<>(child);
        var cancellations=new AtomicInteger();
        dispatch(true,false,false,cancellations::incrementAndGet,current.get());
        assertEquals(1,child.calls);
        assertSame(child,current.get());
        assertEquals(0,cancellations.get());
    }

    @Test void replacementDoesNotReceiveTheSameEscapeEvenWhenOriginalReturnsFalse(){
        var original=new Child();
        var replacement=new Child();
        var current=new AtomicReference<>(original);
        original.action=()->current.set(replacement);
        replacement.action=()->current.set(null);
        replacement.handled=true;
        dispatch(true,false,false,()->fail("replacement must not trigger load cancellation"),current.get());
        assertEquals(1,original.calls);
        assertEquals(0,replacement.calls);
        assertSame(replacement,current.get());
        dispatch(true,false,false,()->fail("ordinary child dismissal is not load cancellation"),current.get());
        assertEquals(1,original.calls);
        assertEquals(1,replacement.calls);
        assertNull(current.get());
    }

    @Test void naturalDismissalDoesNotFallThroughToAnotherAction(){
        var child=new Child();
        var current=new AtomicReference<>(child);
        child.action=()->current.set(null);
        child.handled=true;
        dispatch(true,false,false,()->fail("dismissal must not cancel the world"),current.get());
        dispatch(true,false,false,()->fail("no child is a no-op"),current.get());
        assertEquals(1,child.calls);
        assertNull(current.get());
    }

    @Test void loadingUsesOwnedCancellationWithoutAlsoSendingEscapeToItsChild(){
        var child=new Child();
        var progress=new Child();
        var current=new AtomicReference<>(child);
        var cancellations=new AtomicInteger();
        dispatch(true,true,true,()->{cancellations.incrementAndGet();current.set(progress);},current.get());
        assertEquals(1,cancellations.get());
        assertEquals(0,child.calls);
        assertEquals(0,progress.calls);
        assertSame(progress,current.get());
    }

    @Test void retiredOwnerCannotCancelOrDispatchToItsFormerChild(){
        var child=new Child();
        var cancellations=new AtomicInteger();
        dispatch(false,true,false,cancellations::incrementAndGet,child);
        dispatch(false,false,false,cancellations::incrementAndGet,child);
        assertEquals(0,cancellations.get());
        assertEquals(0,child.calls);
    }

    @Test void overlayBlocksUnderlyingChildUntilAFutureEscape(){
        var child=new Child();
        dispatch(true,false,true,()->fail("overlay is not a load to cancel"),child);
        assertEquals(0,child.calls);
        dispatch(true,false,false,()->fail("ordinary child is not a load to cancel"),child);
        assertEquals(1,child.calls);
    }

    @Test void childFailureIsNotRetriedOrReinterpretedAsCancellation(){
        var child=new Child();
        var failure=new IllegalStateException("child failed");
        child.action=()->{throw failure;};
        assertSame(failure,assertThrows(IllegalStateException.class,()->dispatch(
                true,false,false,()->fail("child failure must not cancel the world"),child)));
        assertEquals(1,child.calls);
    }
}
