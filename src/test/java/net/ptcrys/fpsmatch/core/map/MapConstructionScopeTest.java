package net.ptcrys.fpsmatch.core.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MapConstructionScopeTest {
    @Test
    void successTransfersExactResourcesAndClearsScopeWithoutCallingRollback() {
        Object resource = new Object();
        AtomicInteger constructors = new AtomicInteger();
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        assertFalse(MapConstructionScope.isConstructing());
        Object result = MapConstructionScope.construct(() -> {
            constructors.incrementAndGet();
            assertTrue(MapConstructionScope.isConstructing());
            return MapConstructionScope.acquire(() -> {
                acquisitions.incrementAndGet();
                assertTrue(MapConstructionScope.isConstructing());
                return resource;
            }, acquired -> {
                assertSame(resource, acquired);
                rollbacks.incrementAndGet();
            });
        });
        assertSame(resource, result);
        assertEquals(1, constructors.get());
        assertEquals(1, acquisitions.get());
        assertEquals(0, rollbacks.get());
        assertFalse(MapConstructionScope.isConstructing());
        assertSame(resource, MapConstructionScope.construct(() -> resource));
    }

    @Test
    void acquisitionOutsideScopeReturnsExactResourceWithoutRegisteringCleanup() {
        Object resource = new Object();
        AtomicInteger acquisitions = new AtomicInteger();
        assertSame(resource, MapConstructionScope.acquire(() -> {
            acquisitions.incrementAndGet();
            assertFalse(MapConstructionScope.isConstructing());
            return resource;
        }, ignored -> fail("an unscoped resource belongs to the caller")));
        assertEquals(1, acquisitions.get());
        RuntimeException later = new IllegalArgumentException("unrelated failure");
        assertSame(later, assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> { throw later; })));
        assertFalse(MapConstructionScope.isConstructing());
    }

    @Test
    void runtimeAndErrorRetainIdentityAndRollbackAllResourcesInReverseOrder() {
        for (Throwable primary : new Throwable[] {new IllegalArgumentException("constructor"), new AssertionError("constructor")}) {
            List<String> order = new ArrayList<>();
            Object first = new Object();
            Object second = new Object();
            assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                MapConstructionScope.acquire(() -> first, value -> {
                    assertSame(first, value);
                    assertFalse(MapConstructionScope.isConstructing());
                    order.add("first");
                });
                MapConstructionScope.acquire(() -> second, value -> {
                    assertSame(second, value);
                    assertFalse(MapConstructionScope.isConstructing());
                    order.add("second");
                });
                throwUnchecked(primary);
                return new Object();
            })));
            assertEquals(List.of("second", "first"), order);
            assertEquals(0, primary.getSuppressed().length);
            assertFalse(MapConstructionScope.isConstructing());
        }
    }

    @Test
    void nestedAcquisitionRollsBackByReservationOrderRatherThanCompletionOrder() {
        List<String> order = new ArrayList<>();
        RuntimeException primary = new IllegalArgumentException("constructor");
        Object a = new Object();
        Object b = new Object();
        assertSame(primary, assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> {
            assertSame(a, MapConstructionScope.acquire(() -> {
                assertSame(b, MapConstructionScope.acquire(() -> b, value -> {
                    assertSame(b, value);
                    order.add("B");
                }));
                return a;
            }, value -> {
                assertSame(a, value);
                order.add("A");
            }));
            throw primary;
        })));
        assertEquals(List.of("B", "A"), order);
    }

    @Test
    void acquisitionThatThrowsAfterAcquiringAChildCleansOnlyTheReturnedChild() {
        List<String> order = new ArrayList<>();
        AssertionError primary = new AssertionError("parent acquisition");
        assertSame(primary, assertThrows(AssertionError.class, () -> MapConstructionScope.construct(() ->
                MapConstructionScope.acquire(() -> {
                    MapConstructionScope.acquire(Object::new, value -> order.add("child"));
                    throw primary;
                }, value -> order.add("unreturned parent")))));
        assertEquals(List.of("child"), order);
        assertFalse(MapConstructionScope.isConstructing());
    }

    @Test
    void cleanupFailuresAreSuppressedInAttemptOrderWithoutReplacingPrimaryOrSkippingResources() {
        for (Throwable primary : new Throwable[] {new IllegalArgumentException("constructor"), new AssertionError("constructor")}) {
            RuntimeException runtimeCleanup = new IllegalStateException("cleanup runtime");
            AssertionError errorCleanup = new AssertionError("cleanup error");
            List<String> order = new ArrayList<>();
            assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                MapConstructionScope.acquire(Object::new, value -> order.add("last"));
                MapConstructionScope.acquire(Object::new, value -> {
                    order.add("error");
                    throw errorCleanup;
                });
                MapConstructionScope.acquire(Object::new, value -> {
                    order.add("runtime");
                    throw runtimeCleanup;
                });
                throwUnchecked(primary);
                return new Object();
            })));
            assertEquals(List.of("runtime", "error", "last"), order);
            assertArrayEquals(new Throwable[] {runtimeCleanup, errorCleanup}, primary.getSuppressed());
            assertFalse(MapConstructionScope.isConstructing());
        }
    }

    @Test
    void cleanupThrowingPrimaryDoesNotSelfSuppressAndDoesNotSkipLaterResources() {
        AssertionError primary = new AssertionError("shared");
        AtomicInteger cleaned = new AtomicInteger();
        assertSame(primary, assertThrows(AssertionError.class, () -> MapConstructionScope.construct(() -> {
            MapConstructionScope.acquire(Object::new, value -> cleaned.incrementAndGet());
            MapConstructionScope.acquire(Object::new, value -> { throw primary; });
            throw primary;
        })));
        assertEquals(1, cleaned.get());
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void nullConstructorResultRollsBackAndLeavesNextScopeUsable() {
        AtomicInteger cleaned = new AtomicInteger();
        assertThrows(NullPointerException.class, () -> MapConstructionScope.construct(() -> {
            MapConstructionScope.acquire(Object::new, value -> cleaned.incrementAndGet());
            return null;
        }));
        assertEquals(1, cleaned.get());
        assertFalse(MapConstructionScope.isConstructing());
        assertEquals("next", MapConstructionScope.construct(() -> "next"));
    }

    @Test
    void nullParametersRejectBeforeAcquisitionAndDoNotDamageExistingScope() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(NullPointerException.class, () -> MapConstructionScope.construct(null));
        assertThrows(NullPointerException.class, () -> MapConstructionScope.acquire(null, value -> calls.incrementAndGet()));
        assertThrows(NullPointerException.class, () -> MapConstructionScope.acquire(() -> {
            calls.incrementAndGet();
            return new Object();
        }, null));
        assertThrows(NullPointerException.class, () -> MapConstructionScope.acquire(() -> null, ignored -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
        assertEquals("outer", MapConstructionScope.construct(() -> {
            assertThrows(NullPointerException.class, () -> MapConstructionScope.acquire(null, value -> {}));
            assertThrows(NullPointerException.class, () -> MapConstructionScope.acquire(() -> {
                calls.incrementAndGet();
                return new Object();
            }, null));
            assertTrue(MapConstructionScope.isConstructing());
            return "outer";
        }));
        assertEquals(0, calls.get());
        assertFalse(MapConstructionScope.isConstructing());
    }

    @Test
    void nestedConstructRejectsBeforeNewCallbackAndOuterCanContinueOrRollback() {
        AtomicInteger nestedCalls = new AtomicInteger();
        AtomicInteger cleaned = new AtomicInteger();
        assertEquals("outer", MapConstructionScope.construct(() -> {
            assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
                nestedCalls.incrementAndGet();
                return new Object();
            }));
            assertTrue(MapConstructionScope.isConstructing());
            return "outer";
        }));
        assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
            MapConstructionScope.acquire(Object::new, value -> cleaned.incrementAndGet());
            return MapConstructionScope.construct(() -> {
                nestedCalls.incrementAndGet();
                return new Object();
            });
        }));
        assertEquals(0, nestedCalls.get());
        assertEquals(1, cleaned.get());
        assertFalse(MapConstructionScope.isConstructing());
    }

    @Test
    void rollbackRejectsBothKindsOfNewCallbackBeforeExecutionAndContinuesCleaning() {
        RuntimeException primary = new IllegalArgumentException("constructor");
        List<String> order = new ArrayList<>();
        AtomicInteger forbidden = new AtomicInteger();
        assertSame(primary, assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> {
            MapConstructionScope.acquire(Object::new, value -> order.add("last"));
            MapConstructionScope.acquire(Object::new, value -> {
                order.add("acquire rejected");
                assertFalse(MapConstructionScope.isConstructing());
                MapConstructionScope.acquire(() -> {
                    forbidden.incrementAndGet();
                    return new Object();
                }, ignored -> forbidden.incrementAndGet());
            });
            MapConstructionScope.acquire(Object::new, value -> {
                order.add("construct rejected");
                assertFalse(MapConstructionScope.isConstructing());
                MapConstructionScope.construct(() -> {
                    forbidden.incrementAndGet();
                    return new Object();
                });
            });
            throw primary;
        })));
        assertEquals(List.of("construct rejected", "acquire rejected", "last"), order);
        assertEquals(0, forbidden.get());
        assertEquals(2, primary.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, primary.getSuppressed()[0]);
        assertInstanceOf(IllegalStateException.class, primary.getSuppressed()[1]);
        assertFalse(MapConstructionScope.isConstructing());
    }

    @Test
    void exactly4096AcquisitionsSucceedAnd4097NeverRunsItsSupplier() {
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger cleaned = new AtomicInteger();
        AtomicInteger forbidden = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
            for (int index = 0; index < 4096; index++) {
                MapConstructionScope.acquire(() -> {
                    acquired.incrementAndGet();
                    return new Object();
                }, value -> cleaned.incrementAndGet());
            }
            return MapConstructionScope.acquire(() -> {
                forbidden.incrementAndGet();
                return new Object();
            }, value -> fail("over-budget resource cannot exist"));
        }));
        assertEquals(4096, acquired.get());
        assertEquals(4096, cleaned.get());
        assertEquals(0, forbidden.get());
        assertFalse(MapConstructionScope.isConstructing());
    }

    @Test
    void caughtThrowingAndNullAcquisitionsStillConsumeAllReservedBudget() {
        for (boolean returnsNull : new boolean[] {false, true}) {
            AtomicInteger attempted = new AtomicInteger();
            AtomicInteger cleaned = new AtomicInteger();
            AtomicInteger forbidden = new AtomicInteger();
            RuntimeException acquisitionFailure = new IllegalArgumentException("caught acquisition");
            RuntimeException primary = new IllegalStateException("constructor after caught failures");
            assertSame(primary, assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> {
                MapConstructionScope.acquire(Object::new, value -> cleaned.incrementAndGet());
                for (int index = 0; index < 4095; index++) {
                    Throwable failure = assertThrows(RuntimeException.class, () -> MapConstructionScope.acquire(() -> {
                        attempted.incrementAndGet();
                        if (returnsNull) return null;
                        throw acquisitionFailure;
                    }, value -> fail("an unsuccessful acquisition has no cleanup handle")));
                    if (returnsNull) assertInstanceOf(NullPointerException.class, failure);
                    else assertSame(acquisitionFailure, failure);
                }
                assertThrows(IllegalStateException.class, () -> MapConstructionScope.acquire(() -> {
                    forbidden.incrementAndGet();
                    return new Object();
                }, ignored -> forbidden.incrementAndGet()));
                throw primary;
            })));
            assertEquals(4095, attempted.get());
            assertEquals(1, cleaned.get());
            assertEquals(0, forbidden.get());
            assertEquals(0, primary.getSuppressed().length);
            assertFalse(MapConstructionScope.isConstructing());
        }
    }

    @Test
    void caughtAcquisitionFailureDoesNotLatchFailureWhenConstructorReturnsSuccessfully() {
        AtomicInteger cleaned = new AtomicInteger();
        RuntimeException caught = new IllegalArgumentException("caller swallowed acquisition");
        Object result = new Object();
        assertSame(result, MapConstructionScope.construct(() -> {
            MapConstructionScope.acquire(Object::new, value -> cleaned.incrementAndGet());
            assertSame(caught, assertThrows(RuntimeException.class, () -> MapConstructionScope.acquire(() -> {
                throw caught;
            }, value -> fail("no returned handle"))));
            return result;
        }));
        assertEquals(0, cleaned.get());
        assertFalse(MapConstructionScope.isConstructing());
    }

    @Test
    void childThreadDoesNotInheritScopeAndWorkerCanStartFreshAfterFailure() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch childRan = new CountDownLatch(1);
        AtomicInteger childCleanup = new AtomicInteger();
        AtomicInteger ownerCleanup = new AtomicInteger();
        RuntimeException primary = new IllegalArgumentException("owner failure");
        try {
            assertSame(primary, assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> {
                MapConstructionScope.acquire(Object::new, value -> ownerCleanup.incrementAndGet());
                Future<Boolean> child = worker.submit(() -> {
                    assertFalse(MapConstructionScope.isConstructing());
                    MapConstructionScope.acquire(Object::new, value -> childCleanup.incrementAndGet());
                    RuntimeException childFailure = new IllegalArgumentException("child failure");
                    assertSame(childFailure, assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> {
                        assertTrue(MapConstructionScope.isConstructing());
                        throw childFailure;
                    })));
                    assertFalse(MapConstructionScope.isConstructing());
                    childRan.countDown();
                    return MapConstructionScope.construct(() -> MapConstructionScope.isConstructing());
                });
                try {
                    assertTrue(child.get(5, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError("child scope failed", failure);
                }
                assertTrue(MapConstructionScope.isConstructing());
                throw primary;
            })));
            assertTrue(childRan.await(5, TimeUnit.SECONDS));
            assertEquals(1, ownerCleanup.get());
            assertEquals(0, childCleanup.get());
            assertFalse(MapConstructionScope.isConstructing());
            assertEquals("fresh", worker.submit(() -> {
                assertFalse(MapConstructionScope.isConstructing());
                return MapConstructionScope.construct(() -> "fresh");
            }).get(5, TimeUnit.SECONDS));
            assertEquals("owner fresh", MapConstructionScope.construct(() -> "owner fresh"));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "worker must not survive the test");
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }
}
