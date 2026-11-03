package net.ptcrys.fpsmatch.editor.common.backend.tacz.script;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.backend.tacz.script.ScriptValidationException.Reason.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaBoolean;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaNil;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.StringLib;

/** Parent LuaJ is only the host fixture. Assertions exercise the frozen public validator API. */
@ResourceLock("q51-parent-luaj-static-metatables")
final class PinnedScriptIsolationTest {
    private static final ScriptConstants EMPTY = new ScriptConstants(Map.of(), Map.of());
    private static final long BUDGET = 2_000_000;
    private static final String NO_STRING_INDEX = "assert(string==nil); "
            + "local ok=pcall(function() return ('x'):rep(2) end); assert(not ok); ";
    private static final String ISOLATED_PRIMITIVES = "local function probe(v) "
            + "local mt=getmetatable(v); if mt then local marker=mt.host_marker; "
            + "mt.host_marker='script_mutation'; local idx=rawget(mt,'__index'); "
            + "if type(idx)=='table' then idx.rep=nil end; assert(marker==nil) end; assert(mt==nil) end; "
            + "probe('x'); probe(nil); probe(true); probe(false); probe(1); probe(1.5); "
            + "probe(function() end); probe(assert); " + NO_STRING_INDEX;
    private static final String HANDLER_NORMAL = "local handled=0; "
            + "local ok,msg=xpcall(function() error('handler target') end, function(e) "
            + "handled=handled+1; assert(type(e)=='string'); return 'handled' end); "
            + "assert(handled==1 and not ok and msg=='handled'); return {}";
    private static final String HANDLER_LOOP = "local handled=0; "
            + "local ok,msg=xpcall(function() error('handler target') end, function(e) "
            + "handled=handled+1; assert(type(e)=='string'); while true do end; return 'handled' end); "
            + "assert(handled==1 and not ok and msg=='handled'); return {}";

    @Test
    void stringIndexIsAbsentBeforeAndAfterParentStringLibraryInstallation() throws Exception {
        HostMetatables original = HostMetatables.capture();
        try {
            LuaString.s_metatable = null;
            validate(Map.of("t_a", NO_STRING_INDEX + "return {}"));
            assertNull(LuaString.s_metatable, "validation must not initialize the parent string metatable");
            try (HostFixture fixture = new HostFixture()) {
                assertEquals("xx", LuaValue.valueOf("x").get("rep")
                        .call(LuaValue.valueOf("x"), LuaValue.valueOf(2)).tojstring());
                for (int i = 0; i < 3; i++) {
                    validate(Map.of("t_a", ISOLATED_PRIMITIVES + "return {}"));
                    fixture.assertUnchanged();
                }
            }
        } finally {
            original.restore();
        }
    }

    @Test
    void parentPrimitiveMetatablesStayPrivateDuringConcurrentValidationsAndLibraryInstallations() throws Exception {
        try (HostFixture fixture = new HostFixture()) {
            Map<String, String> sources = Map.of("t_a", ISOLATED_PRIMITIVES
                    + "assert(per_call==nil); per_call=true; local b=require('t_b'); "
                    + "assert(b.n==0); b.n=1; return {}", "t_b", "return {n=0}");
            ExecutorService executor = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch enteredValidation = new CountDownLatch(2);
            CountDownLatch hostInstalled = new CountDownLatch(1);
            CountDownLatch validationsFinished = new CountDownLatch(2);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int worker = 0; worker < 2; worker++) {
                    futures.add(executor.submit(() -> {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        AtomicInteger polls = new AtomicInteger();
                        BooleanSupplier cancellation = () -> {
                            if (polls.incrementAndGet() == 1) {
                                enteredValidation.countDown();
                                awaitGate(hostInstalled);
                            }
                            return false;
                        };
                        try {
                            for (int i = 0; i < 3; i++) {
                                var result = PinnedScriptValidator.validate(sources, EMPTY, cancellation, BUDGET);
                                assertEquals(Set.of("t_a", "t_b"), result.modules());
                                assertEquals(Set.of("t_b"), result.requires().get("t_a"));
                                fixture.assertUnchanged();
                            }
                        } finally {
                            validationsFinished.countDown();
                        }
                        return null;
                    }));
                }
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    assertTrue(enteredValidation.await(10, TimeUnit.SECONDS));
                    new StringLib().call(LuaValue.NIL, new Globals());
                    fixture.assertUnchanged();
                    hostInstalled.countDown();
                    while (!validationsFinished.await(1, TimeUnit.MILLISECONDS)) {
                        new StringLib().call(LuaValue.NIL, new Globals());
                        fixture.assertUnchanged();
                    }
                    return null;
                }));
                start.countDown();
                for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            } finally {
                hostInstalled.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
            fixture.assertUnchanged();
        }
    }

    @Test
    void ordinaryXpcallRunsItsHandlerAndReturnsTheHandlersResult() throws Exception {
        assertEquals(Set.of("t_a"), validate(Map.of("t_a", HANDLER_NORMAL)).modules());
    }

    @Test
    void infiniteXpcallErrorHandlerStopsAtTheInstructionLimitAndTheNextCallWorks() throws Exception {
        var failure = assertThrows(ScriptValidationException.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", HANDLER_LOOP), EMPTY, () -> false, 2_000));
        assertEquals(INSTRUCTION_LIMIT, failure.reason());
        validate(Map.of("t_a", HANDLER_NORMAL));
    }

    @Test
    void transientCancellationInsideXpcallHandlerRemainsLatchedAndTheNextCallWorks() throws Exception {
        int threshold = pollsBeyondNormalHandler();
        AtomicInteger polls = new AtomicInteger();
        BooleanSupplier pulse = () -> polls.incrementAndGet() == threshold;
        var failure = assertThrows(ScriptValidationException.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", HANDLER_LOOP), EMPTY, pulse, BUDGET));
        assertEquals(CANCELLED, failure.reason());
        assertTrue(polls.get() >= threshold);
        assertFalse(pulse.getAsBoolean());
        validate(Map.of("t_a", HANDLER_NORMAL));
    }

    @Test
    void supplierRuntimeExceptionInsideXpcallHandlerEscapesAsTheSameInstanceAndRecovers() throws Exception {
        int threshold = pollsBeyondNormalHandler();
        AtomicInteger polls = new AtomicInteger();
        IllegalArgumentException expected = new IllegalArgumentException("handler supplier identity");
        assertSame(expected, assertThrows(IllegalArgumentException.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", HANDLER_LOOP), EMPTY, () -> {
                    if (polls.incrementAndGet() == threshold) throw expected;
                    return false;
                }, BUDGET)));
        assertTrue(polls.get() >= threshold);
        validate(Map.of("t_a", HANDLER_NORMAL));
    }

    @Test
    void supplierErrorInsideXpcallHandlerEscapesAsTheSameInstanceAndRecovers() throws Exception {
        int threshold = pollsBeyondNormalHandler();
        AtomicInteger polls = new AtomicInteger();
        AssertionError expected = new AssertionError("handler supplier identity");
        assertSame(expected, assertThrows(AssertionError.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", HANDLER_LOOP), EMPTY, () -> {
                    if (polls.incrementAndGet() == threshold) throw expected;
                    return false;
                }, BUDGET)));
        assertTrue(polls.get() >= threshold);
        validate(Map.of("t_a", HANDLER_NORMAL));
    }

    @Test
    void supplierMissingClassErrorInsideXpcallHandlerKeepsItsIdentityAndTheNextCallWorks() throws Exception {
        int threshold = pollsBeyondNormalHandler();
        AtomicInteger polls = new AtomicInteger();
        NoClassDefFoundError expected = new NoClassDefFoundError("synthetic/supplier/Only");
        assertSame(expected, assertThrows(NoClassDefFoundError.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", HANDLER_LOOP), EMPTY, () -> {
                    if (polls.incrementAndGet() == threshold) throw expected;
                    return false;
                }, BUDGET)));
        assertTrue(polls.get() >= threshold);
        validate(Map.of("t_a", HANDLER_NORMAL));
    }

    @Test
    void matchingReadableClasspathLuaCannotSatisfyARequireAbsentFromTheFixedMap() throws Exception {
        // The ordinary LuaJ package search path ?.lua could load this real resource.
        // The public validator must use only its explicit source map.
        Path classRoot = Path.of(PinnedScriptIsolationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classRoot), "the test classes need their ordinary directory classpath");
        String module = "q51_shadow_" + UUID.randomUUID().toString().replace("-", "");
        Path resource = classRoot.resolve(module + ".lua");
        String source = "return {classpath_fallback=true}";
        try {
            Files.writeString(resource, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            var located = PinnedScriptIsolationTest.class.getClassLoader().getResource(module + ".lua");
            assertNotNull(located, "the shadow fixture must really be on the classpath");
            try (var stream = located.openStream()) {
                assertEquals(source, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            var failure = assertThrows(ScriptValidationException.class,
                    () -> validate(Map.of("t_a", "return require('" + module + "')")));
            assertEquals(UNKNOWN_MODULE, failure.reason());
            assertEquals(module, failure.moduleName());
        } finally {
            Files.deleteIfExists(resource);
        }
    }

    private static PinnedScriptValidator.Validation validate(Map<String, String> sources) throws ScriptValidationException {
        return PinnedScriptValidator.validate(sources, EMPTY, () -> false, BUDGET);
    }

    private static int pollsBeyondNormalHandler() throws ScriptValidationException {
        AtomicInteger polls = new AtomicInteger();
        PinnedScriptValidator.validate(Map.of("t_a", HANDLER_NORMAL), EMPTY,
                () -> { polls.incrementAndGet(); return false; }, BUDGET);
        // The loop variant has the same prefix. This deliberately passes all the normal
        // handler's and successful call's work, so the one-shot failure occurs in the loop.
        return polls.get() + 100;
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            assertTrue(gate.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("validation coordination interrupted", exception);
        }
    }

    private record HostMetatables(LuaValue string, LuaValue nil, LuaValue bool, LuaValue number, LuaValue function) {
        private static HostMetatables capture() {
            return new HostMetatables(LuaString.s_metatable, LuaNil.s_metatable, LuaBoolean.s_metatable,
                    LuaNumber.s_metatable, LuaFunction.s_metatable);
        }

        private void restore() {
            LuaString.s_metatable = string;
            LuaNil.s_metatable = nil;
            LuaBoolean.s_metatable = bool;
            LuaNumber.s_metatable = number;
            LuaFunction.s_metatable = function;
        }
    }

    private static final class HostFixture implements AutoCloseable {
        private final HostMetatables original = HostMetatables.capture();
        private final List<LuaTable> tables = new ArrayList<>();
        private final List<Map<LuaValue, LuaValue>> contents = new ArrayList<>();

        private HostFixture() {
            Globals parent = new Globals();
            new StringLib().call(LuaValue.NIL, parent);
            for (String kind : List.of("string", "nil", "boolean", "number", "function")) {
                LuaTable table = new LuaTable();
                table.rawset("host_marker", LuaValue.valueOf("parent_" + kind));
                if (kind.equals("string")) table.rawset("__index", parent.get("string"));
                tables.add(table);
                contents.add(snapshot(table));
            }
            LuaString.s_metatable = tables.get(0);
            LuaNil.s_metatable = tables.get(1);
            LuaBoolean.s_metatable = tables.get(2);
            LuaNumber.s_metatable = tables.get(3);
            LuaFunction.s_metatable = tables.get(4);
            LuaTable stringMethods = parent.get("string").checktable();
            tables.add(stringMethods);
            contents.add(snapshot(stringMethods));
        }

        private void assertUnchanged() {
            assertSame(tables.get(0), LuaString.s_metatable);
            assertSame(tables.get(1), LuaNil.s_metatable);
            assertSame(tables.get(2), LuaBoolean.s_metatable);
            assertSame(tables.get(3), LuaNumber.s_metatable);
            assertSame(tables.get(4), LuaFunction.s_metatable);
            for (int i = 0; i < tables.size(); i++) {
                Map<LuaValue, LuaValue> actual = snapshot(tables.get(i));
                assertEquals(contents.get(i).keySet(), actual.keySet());
                for (var entry : contents.get(i).entrySet()) assertSame(entry.getValue(), actual.get(entry.getKey()));
            }
        }

        @Override
        public void close() {
            try {
                assertUnchanged();
            } finally {
                original.restore();
            }
        }

        private static Map<LuaValue, LuaValue> snapshot(LuaTable table) {
            Map<LuaValue, LuaValue> entries = new LinkedHashMap<>();
            for (LuaValue key : table.keys()) entries.put(key, table.rawget(key));
            return entries;
        }
    }
}
