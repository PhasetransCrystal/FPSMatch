package net.ptcrys.fpsmatch.editor.common.backend.tacz.script;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.backend.tacz.script.ScriptValidationException.Reason.*;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Public API tests using actual, synthetic Lua source; no VM mocks or implementation access. */
final class PinnedScriptValidatorTest {
    private static final ScriptConstants EMPTY = new ScriptConstants(Map.of(), Map.of());
    private static final BooleanSupplier NEVER = () -> false;
    private static final long BUDGET = 2_000_000;

    @Test
    void fixedSourcesExecuteOnceInSortedOrderWithSharedTablesAndActualEdges() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("t_z", "assert(order == 'abc'); return {}");
        sources.put("t_c", "order = order .. 'c'; return {}");
        sources.put("t_b", "assert(order == 'a'); order = order .. 'b'; return {value=7}");
        sources.put("t_a", "order = 'a'; local b=require('t_b'); assert(b.value==7); "
                + "b.value=9; assert(rawequal(b, require('t_b'))); "
                + "assert(require('t_b').value==9); if false then require('t_unused') end; return {}");
        var result = validate(sources);
        assertEquals(List.of("t_a", "t_b", "t_c", "t_z"), new ArrayList<>(result.modules()));
        assertEquals(List.of("t_a", "t_b", "t_c", "t_z"), new ArrayList<>(result.requires().keySet()));
        assertEquals(Map.of("t_a", Set.of("t_b"), "t_b", Set.of(), "t_c", Set.of(), "t_z", Set.of()),
                result.requires());
        List<Map.Entry<String, String>> entries = new ArrayList<>(sources.entrySet());
        Collections.reverse(entries);
        Map<String, String> reversed = new LinkedHashMap<>();
        entries.forEach(e -> reversed.put(e.getKey(), e.getValue()));
        assertEquals(result, validate(reversed));
        assertTrue(result.instructions() > 0);
    }

    @Test
    void unreferencedSourcesAreExecutedAndChecked() {
        failure(SCRIPT_ERROR, Map.of("t_a", "return {}", "t_z", "error('unreferenced')"));
        failure(INVALID_RESULT, Map.of("t_a", "return {}", "t_z", "return 17"));
    }

    @Test
    void missingModulesCyclesBadReturnsSyntaxAndRuntimeErrorsAreDistinctFailures() {
        failure(UNKNOWN_MODULE, Map.of("t_a", "return require('t_missing')"));
        failure(CYCLIC_REQUIRE, Map.of("t_a", "return require('t_a')"));
        failure(CYCLIC_REQUIRE, Map.of("t_a", "return require('t_b')", "t_b", "return require('t_a')"));
        for (String source : List.of("return nil", "return false", "return 3", "return 'x'", "return function() end")) {
            failure(INVALID_RESULT, Map.of("t_a", source));
        }
        failure(SCRIPT_ERROR, Map.of("t_a", "local = broken"));
        failure(SCRIPT_ERROR, Map.of("t_a", "error('synthetic failure')"));
    }

    @Test
    void protectedCallsCannotPublishAfterARequireFailure() {
        failure(UNKNOWN_MODULE, Map.of("t_a", "pcall(function() require('t_missing') end); return {}"));
        failure(UNKNOWN_MODULE, Map.of("t_a", "xpcall(function() require('t_missing') end, "
                + "function(e) return 'hidden' end); return {}"));
        failure(INVALID_RESULT, Map.of("t_a", "pcall(function() require('t_b') end); return {}", "t_b", "return 1"));
        failure(CYCLIC_REQUIRE, Map.of("t_a", "pcall(function() require('t_a') end); return {}"));
    }

    @Test
    void loopsProtectedCallsChildClosuresAndRequiredClosuresKeepTheirInstructionHook() {
        List<Map<String, String>> cases = List.of(
                Map.of("t_a", "while true do end"),
                Map.of("t_a", "pcall(function() while true do end end); return {}"),
                Map.of("t_a", "xpcall(function() while true do end end, function(e) return e end); return {}"),
                Map.of("t_a", "local function child() while true do end end; child(); return {}"),
                Map.of("t_a", "return require('t_b')", "t_b", "while true do end"),
                Map.of("t_a", "require('t_b').spin(); return {}", "t_b", "return {spin=function() while true do end end}"));
        for (var sources : cases) {
            assertEquals(INSTRUCTION_LIMIT, assertThrows(ScriptValidationException.class,
                    () -> PinnedScriptValidator.validate(sources, EMPTY, NEVER, 2_000)).reason());
        }
    }

    @Test
    void exactInstructionBudgetAllowsTheLastInstructionAndRejectsTheNext() throws Exception {
        Map<String, String> sources = Map.of("t_a", "local n=0; for i=1,15 do n=n+i end; "
                + "assert(n==120); return require('t_b')", "t_b", "return {ok=true}");
        long exact = validate(sources).instructions();
        assertTrue(exact > 1);
        assertEquals(exact, PinnedScriptValidator.validate(sources, EMPTY, NEVER, exact).instructions());
        assertEquals(INSTRUCTION_LIMIT, assertThrows(ScriptValidationException.class,
                () -> PinnedScriptValidator.validate(sources, EMPTY, NEVER, exact - 1)).reason());
    }

    @Test
    void cancelledBeforeExecutionAndDuringProtectedLoopsNeverProducesValidation() {
        assertEquals(CANCELLED, assertThrows(ScriptValidationException.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", "error('must not run')"), EMPTY, () -> true, BUDGET)).reason());
        AtomicInteger polls = new AtomicInteger();
        BooleanSupplier pulse = () -> polls.incrementAndGet() == 25;
        assertEquals(CANCELLED, assertThrows(ScriptValidationException.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", "pcall(function() while true do end end); return {}"),
                        EMPTY, pulse, BUDGET)).reason());
        assertTrue(polls.get() >= 25);
        assertFalse(pulse.getAsBoolean(), "the external supplier has reset, but cancellation already won");
    }

    @Test
    void cancellationIsPolledForEveryTinySourceAndAtCompletion() throws Exception {
        Map<String, String> sources = Map.of("t_a", "return {}", "t_b", "return {}", "t_c", "return {}");
        AtomicInteger polls = new AtomicInteger();
        PinnedScriptValidator.validate(sources, EMPTY, () -> { polls.incrementAndGet(); return false; }, BUDGET);
        int total = polls.get();
        assertTrue(total > 3);
        for (int stopAt : List.of(1, total / 2, total)) {
            AtomicInteger current = new AtomicInteger();
            assertEquals(CANCELLED, assertThrows(ScriptValidationException.class,
                    () -> PinnedScriptValidator.validate(sources, EMPTY, () -> current.incrementAndGet() == stopAt, BUDGET)).reason());
        }
    }

    @Test
    void supplierRuntimeExceptionAndErrorRetainIdentityEvenInsideProtectedLua() throws Exception {
        IllegalArgumentException runtime = new IllegalArgumentException("supplier identity");
        AssertionError error = new AssertionError("supplier identity");
        for (int threshold : List.of(1, 25)) {
            AtomicInteger runtimePolls = new AtomicInteger();
            assertSame(runtime, assertThrows(IllegalArgumentException.class,
                    () -> PinnedScriptValidator.validate(Map.of("t_a", "pcall(function() while true do end end); return {}"),
                            EMPTY, () -> { if (runtimePolls.incrementAndGet() == threshold) throw runtime; return false; }, BUDGET)));
            AtomicInteger errorPolls = new AtomicInteger();
            assertSame(error, assertThrows(AssertionError.class,
                    () -> PinnedScriptValidator.validate(Map.of("t_a", "pcall(function() while true do end end); return {}"),
                            EMPTY, () -> { if (errorPolls.incrementAndGet() == threshold) throw error; return false; }, BUDGET)));
            assertEquals(Set.of("t_ok"), validate(Map.of("t_ok", "return {}")).modules());
        }
    }

    @Test
    void sameThreadSupplierReentryIsRejectedAndTheGuardIsReleased() throws Exception {
        AtomicInteger rejected = new AtomicInteger();
        var result = PinnedScriptValidator.validate(Map.of("t_outer", "return {}"), EMPTY, () -> {
            assertThrows(IllegalStateException.class,
                    () -> PinnedScriptValidator.validate(Map.of("t_nested", "return {}"), EMPTY, NEVER, BUDGET));
            rejected.incrementAndGet();
            return false;
        }, BUDGET);
        assertTrue(rejected.get() > 0);
        assertEquals(Set.of("t_outer"), result.modules());
        assertThrows(IllegalStateException.class,
                () -> PinnedScriptValidator.validate(Map.of("t_outer", "return {}"), EMPTY, () -> {
                    try {
                        PinnedScriptValidator.validate(Map.of("t_nested", "return {}"), EMPTY, NEVER, BUDGET);
                        throw new AssertionError("nested validation unexpectedly succeeded");
                    } catch (ScriptValidationException exception) {
                        throw new AssertionError("reentry must be rejected before script validation", exception);
                    }
                }, BUDGET));
        assertEquals(Set.of("t_after"), validate(Map.of("t_after", "return {}")).modules());
    }

    @Test
    void globalsHaveOnlyTheFrozenPureLibraryAndNoFileOrClasspathFallback() throws Exception {
        StringBuilder source = new StringBuilder("assert(_G == _G._G); assert(type(_VERSION)=='string'); ");
        for (String name : List.of("print", "load", "loadfile", "dofile", "collectgarbage", "package", "io", "os",
                "debug", "coroutine", "luajava", "string", "table", "math", "bit32", "module")) {
            source.append("assert(").append(name).append(" == nil); ");
        }
        for (String name : List.of("assert", "error", "pcall", "xpcall", "type", "tostring", "tonumber", "select", "next",
                "pairs", "ipairs", "rawequal", "rawget", "rawset", "rawlen", "setmetatable", "getmetatable", "require")) {
            source.append("assert(type(").append(name).append(")== 'function'); ");
        }
        source.append("local t={}; rawset(t,'a',3); assert(rawget(t,'a')==3); "
                + "assert(tonumber('42')==42); assert(tostring(42)=='42'); "
                + "assert(select(2,7,8)==8); assert(rawlen({1,2})==2); "
                + "setmetatable(t,{__index={b=4}}); assert(t.b==4); assert(type(getmetatable(t))=='table'); return {}");
        validate(Map.of("t_a", source.toString()));
        failure(INVALID_NAME, Map.of("t_a", "return require('org/luaj/vm2/lib/BaseLib')"));
        failure(UNKNOWN_MODULE, Map.of("t_a", "return require('t_not_in_fixed_input')"));
    }

    @Test
    void constantsAreTypedDefensivelyCopiedAndExposedWithoutHostObjects() throws Exception {
        Map<String, String> text = new LinkedHashMap<>();
        text.put("INPUT_IDLE", "idle");
        Map<String, Integer> ints = new LinkedHashMap<>();
        ints.put("STATE_ZERO", 0);
        ints.put("MIN_VALUE", Integer.MIN_VALUE);
        ints.put("MAX_VALUE", Integer.MAX_VALUE);
        ScriptConstants constants = new ScriptConstants(text, ints);
        text.put("INPUT_IDLE", "changed");
        ints.clear();
        PinnedScriptValidator.validate(Map.of("t_a", "assert(type(INPUT_IDLE)=='string' and INPUT_IDLE=='idle'); "
                + "assert(type(STATE_ZERO)=='number' and STATE_ZERO==0); "
                + "assert(MIN_VALUE==-2147483648 and MAX_VALUE==2147483647); return {}"), constants, NEVER, BUDGET);
        assertThrows(UnsupportedOperationException.class, () -> constants.text().put("NEW", "x"));
        assertThrows(UnsupportedOperationException.class, () -> constants.integers().clear());
    }

    @Test
    void constantNamesDuplicatesLengthsCountsAndNullsAreCheckedAtConstruction() {
        for (String bad : List.of("", "lower", "A-B", "1A", "_G", "_VERSION", "A".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> new ScriptConstants(Map.of(bad, "x"), Map.of()));
        }
        for (String reserved : List.of("require", "assert", "error", "pcall", "xpcall", "type", "tostring", "tonumber",
                "select", "next", "pairs", "ipairs", "rawequal", "rawget", "rawset", "rawlen", "setmetatable", "getmetatable")) {
            assertThrows(IllegalArgumentException.class, () -> new ScriptConstants(Map.of(), Map.of(reserved, 1)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ScriptConstants(Map.of("SAME", "x"), Map.of("SAME", 1)));
        assertThrows(IllegalArgumentException.class, () -> new ScriptConstants(Map.of("TEXT", "x".repeat(4097)), Map.of()));
        assertDoesNotThrow(() -> new ScriptConstants(Map.of("A".repeat(128), "x".repeat(4096)), Map.of()));
        Map<String, String> manyText = new LinkedHashMap<>();
        Map<String, Integer> manyInts = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) { manyText.put("T" + i, "x"); manyInts.put("I" + i, i); }
        assertDoesNotThrow(() -> new ScriptConstants(manyText, manyInts));
        manyText.put("EXTRA", "x");
        assertThrows(IllegalArgumentException.class, () -> new ScriptConstants(manyText, Map.of()));
        manyInts.put("EXTRA", 1);
        assertThrows(IllegalArgumentException.class, () -> new ScriptConstants(Map.of(), manyInts));
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("TEXT", null);
        assertThrows(NullPointerException.class, () -> new ScriptConstants(nullValue, Map.of()));
        Map<String, Integer> nullKey = new LinkedHashMap<>();
        nullKey.put(null, 1);
        assertThrows(NullPointerException.class, () -> new ScriptConstants(Map.of(), nullKey));
        Map<String, Integer> nullInteger = new LinkedHashMap<>();
        nullInteger.put("NUMBER", null);
        assertThrows(NullPointerException.class, () -> new ScriptConstants(Map.of(), nullInteger));
        assertThrows(NullPointerException.class, () -> new ScriptConstants(null, Map.of()));
        assertThrows(NullPointerException.class, () -> new ScriptConstants(Map.of(), null));
    }

    @Test
    void moduleNamesRejectTraversalAndAcceptFrozenLengthBoundary() throws Exception {
        for (String bad : List.of("", "plain", "T_a", "t_a:b", "t_a\\b", "t_/a", "t_a/", "t_a//b",
                "t_.", "t_..", "t_a/./b", "t_a/../b", "t_" + "a".repeat(255))) {
            failure(INVALID_NAME, Map.of(bad, "return {}"));
        }
        String boundary = "t_" + "a".repeat(254);
        assertEquals(Set.of(boundary), validate(Map.of(boundary, "return {}")).modules());
        validate(Map.of("ns.part-name_dir/sub-name.lua", "return {}"));
    }

    @Test
    void sourceCountsAndUtf16LengthsHaveExactLimits() throws Exception {
        failure(SOURCE_LIMIT, Map.of());
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) many.put("t_m" + i, "return {}");
        assertEquals(256, validate(many).modules().size());
        many.put("t_extra", "return {}");
        failure(SOURCE_LIMIT, many);
        failure(INVALID_SOURCE, Map.of("t_a", ""));
        failure(INVALID_SOURCE, Map.of("t_a", " \t\n"));
        failure(SOURCE_LIMIT, Map.of("t_a", "x".repeat(1_048_577)));
        String full = "return {} --" + "x".repeat(1_048_576 - "return {} --".length());
        Map<String, String> total = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) total.put("t_m" + i, full);
        assertEquals(8, validate(total).modules().size());
        total.put("t_extra", "x");
        failure(SOURCE_LIMIT, total);
    }

    @Test
    void allInputNamesAndLengthsAreCheckedBeforeAnySourceExecutes() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("t_a", "error('execution must not precede input validation')");
        sources.put("BAD", "return {}");
        failure(INVALID_NAME, sources);
        sources.remove("BAD");
        sources.put("t_z", "x".repeat(1_048_577));
        failure(SOURCE_LIMIT, sources);
    }

    @Test
    void sourceCaptureDoesNotRereadAChangingMapAfterCheckingItsFirstEntries() throws Exception {
        Map<String, String> checked = Map.of("t_a", "assert(require('t_b').captured); return {}",
                "t_b", "return {captured=true}");
        List<Map<String, String>> laterViews = List.of(Map.of("BAD", "return {}"),
                Map.of("t_a", "return {} --" + "x".repeat(1_048_576)));
        for (Map<String, String> later : laterViews) {
            SwitchingEntriesMap<String> input = new SwitchingEntriesMap<>(checked, later);
            var result = validate(input);
            assertEquals(Set.of("t_a", "t_b"), result.modules());
            assertEquals(Set.of("t_b"), result.requires().get("t_a"));
            assertEquals(1, input.entryReads, "execution must consume the exact entries already checked");
        }
    }

    @Test
    void constantCaptureKeepsCheckedValuesWithoutRereadingEitherCallerMap() throws Exception {
        for (Map<String, String> laterText : List.of(Map.of("bad", "not checked"),
                Map.of("TEXT", "x".repeat(4097)))) {
            SwitchingEntriesMap<String> text = new SwitchingEntriesMap<>(Map.of("TEXT", "checked"), laterText);
            SwitchingEntriesMap<Integer> numbers = new SwitchingEntriesMap<>(Map.of("NUMBER", 7), Map.of("bad", 99));
            ScriptConstants constants = new ScriptConstants(text, numbers);
            assertEquals(Map.of("TEXT", "checked"), constants.text());
            assertEquals(Map.of("NUMBER", 7), constants.integers());
            assertEquals(1, text.entryReads);
            assertEquals(1, numbers.entryReads);
            PinnedScriptValidator.validate(Map.of("t_a", "assert(TEXT=='checked' and NUMBER==7); return {}"),
                    constants, NEVER, BUDGET);
        }
    }

    @Test
    void duplicateConstantsAreComparedOnlyInTheTwoCapturedPrivateMaps() {
        SwitchingEntriesMap<String> text = new SwitchingEntriesMap<>(Map.of("DUPLICATE", "checked"), Map.of("OTHER", "changed"));
        SwitchingEntriesMap<Integer> numbers = new SwitchingEntriesMap<>(Map.of("DUPLICATE", 1), Map.of("OTHER_NUMBER", 2));
        assertThrows(IllegalArgumentException.class, () -> new ScriptConstants(text, numbers));
        assertEquals(1, text.entryReads);
        assertEquals(1, numbers.entryReads);
    }

    @Test
    void requireDepthHasIndependent64ModuleBoundary() throws Exception {
        assertEquals(64, validate(chain(64)).modules().size());
        failure(SOURCE_LIMIT, chain(65));
    }

    @Test
    void distinctRequireEdgesHaveTheirOwnLimitAndRepeatedRequiresAreDeduplicated() throws Exception {
        var exact = validate(bipartite(64, 64));
        assertEquals(4096, exact.requires().values().stream().mapToInt(Set::size).sum());
        failure(SOURCE_LIMIT, bipartite(65, 64));
        String repeated = "require('t_b'); ".repeat(100) + "return {}";
        assertEquals(Set.of("t_b"), validate(Map.of("t_a", repeated, "t_b", "return {}")).requires().get("t_a"));
    }

    @Test
    void invalidCallBudgetsAndNullsUseTheDocumentedJavaExceptions() {
        for (long bad : new long[] {Long.MIN_VALUE, -1, 0, 10_000_001, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PinnedScriptValidator.validate(Map.of("t_a", "return {}"), EMPTY, NEVER, bad));
        }
        assertThrows(NullPointerException.class, () -> PinnedScriptValidator.validate(null, EMPTY, NEVER, BUDGET));
        assertThrows(NullPointerException.class, () -> PinnedScriptValidator.validate(Map.of("t_a", "return {}"), null, NEVER, BUDGET));
        assertThrows(NullPointerException.class, () -> PinnedScriptValidator.validate(Map.of("t_a", "return {}"), EMPTY, null, BUDGET));
        Map<String, String> nullSource = new LinkedHashMap<>();
        nullSource.put("t_a", null);
        assertThrows(NullPointerException.class, () -> validate(nullSource));
        Map<String, String> nullName = new LinkedHashMap<>();
        nullName.put(null, "return {}");
        assertThrows(NullPointerException.class, () -> validate(nullName));
        assertDoesNotThrow(() -> PinnedScriptValidator.validate(Map.of("t_a", "return {}"), EMPTY, NEVER, 10_000_000));
        assertEquals(INSTRUCTION_LIMIT, assertThrows(ScriptValidationException.class,
                () -> PinnedScriptValidator.validate(Map.of("t_a", "return {}"), EMPTY, NEVER, 1)).reason());
    }

    @Test
    void repeatedCallsShareNeitherGlobalsNorCachedModuleTables() throws Exception {
        Map<String, String> sources = Map.of("t_a", "assert(leaked==nil); leaked=true; "
                + "local b=require('t_b'); assert(b.n==1); b.n=20; return {}", "t_b", "return {n=1}");
        var first = validate(sources);
        assertEquals(first, validate(sources));
        failure(SCRIPT_ERROR, Map.of("t_a", "leaked=true; error('fail')"));
        assertEquals(first, validate(sources));
        assertEquals(CANCELLED, assertThrows(ScriptValidationException.class,
                () -> PinnedScriptValidator.validate(sources, EMPTY, () -> true, BUDGET)).reason());
        assertEquals(first, validate(sources));
    }

    @Test
    void validationAndItsPublicConstructorDefensivelyFreezeNestedCollections() throws Exception {
        var result = validate(Map.of("t_a", "return require('t_b')", "t_b", "return {}"));
        assertThrows(UnsupportedOperationException.class, () -> result.modules().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.requires().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.requires().get("t_a").clear());
        Set<String> modules = new java.util.LinkedHashSet<>(List.of("t_b", "t_a"));
        Set<String> edges = new java.util.LinkedHashSet<>(List.of("t_b"));
        Map<String, Set<String>> requires = new LinkedHashMap<>();
        requires.put("t_a", edges);
        requires.put("t_b", Set.of());
        var copy = new PinnedScriptValidator.Validation(modules, requires, 7);
        modules.clear(); edges.clear(); requires.clear();
        assertEquals(Set.of("t_a", "t_b"), copy.modules());
        assertEquals(Set.of("t_b"), copy.requires().get("t_a"));
        assertEquals(7, copy.instructions());
        assertThrows(UnsupportedOperationException.class, () -> copy.requires().get("t_a").add("t_c"));
    }

    @Test
    void exceptionsCarryReasonAndModuleWithoutEmbeddingTheEntireSource() {
        String source = "local private_source_marker='do not copy source'; error('failure')";
        var exception = failure(SCRIPT_ERROR, Map.of("t_a", source));
        assertEquals("t_a", exception.moduleName());
        assertFalse(exception.toString().contains(source));
        var constructed = new ScriptValidationException(INVALID_NAME, "", "technical detail");
        assertEquals(INVALID_NAME, constructed.reason());
        assertEquals("", constructed.moduleName());
    }

    private static PinnedScriptValidator.Validation validate(Map<String, String> sources) throws ScriptValidationException {
        return PinnedScriptValidator.validate(sources, EMPTY, NEVER, BUDGET);
    }

    private static ScriptValidationException failure(ScriptValidationException.Reason reason, Map<String, String> sources) {
        var failure = assertThrows(ScriptValidationException.class, () -> validate(sources));
        assertEquals(reason, failure.reason());
        return failure;
    }

    private static Map<String, String> chain(int length) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) {
            sources.put("t_m" + String.format("%03d", i), i + 1 == length ? "return {}"
                    : "return require('t_m" + String.format("%03d", i + 1) + "')");
        }
        return sources;
    }

    private static Map<String, String> bipartite(int parents, int leaves) {
        Map<String, String> sources = new LinkedHashMap<>();
        StringBuilder requiring = new StringBuilder();
        for (int i = 0; i < leaves; i++) {
            sources.put("t_z" + i, "return {}");
            requiring.append("require('t_z").append(i).append("'); ");
        }
        requiring.append("return {}");
        for (int i = 0; i < parents; i++) sources.put("t_a" + i, requiring.toString());
        return sources;
    }

    /** A caller-owned map whose second enumeration is intentionally different from its first. */
    private static final class SwitchingEntriesMap<V> extends AbstractMap<String, V> {
        private final Map<String, V> checked;
        private final Map<String, V> later;
        private int entryReads;

        private SwitchingEntriesMap(Map<String, V> checked, Map<String, V> later) {
            this.checked = checked;
            this.later = later;
        }

        @Override
        public int size() {
            return checked.size();
        }

        @Override
        public Set<Map.Entry<String, V>> entrySet() {
            return (++entryReads == 1 ? checked : later).entrySet();
        }

        @Override
        public boolean containsKey(Object key) {
            throw new AssertionError("caller-owned containsKey must not be used after capture");
        }
    }
}
