package net.ptcrys.fpsmatch.editor.common.backend.tacz.script.internal;

import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.PinnedScriptValidator.Validation;
import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.ScriptConstants;
import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.ScriptValidationException;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.ptcrys.fpsmatch.editor.common.backend.tacz.script.ScriptValidationException.Reason;

/**
 * Checks all fixed sources in one execution domain. No runnable Lua object escapes.
 * This instruction-bounded content check does not bound compilation or allocation.
 */
public final class IsolatedScriptEngine {

    private static final Pattern MODULE_NAME = Pattern.compile("([a-z0-9_.-]+)_([a-z0-9_./-]+)");
    private static final String[] PURE_FUNCTIONS = {
            "assert", "error", "pcall", "xpcall", "type", "tostring", "tonumber", "select",
            "next", "pairs", "ipairs", "rawequal", "rawget", "rawset", "rawlen",
            "setmetatable", "getmetatable"
    };

    private IsolatedScriptEngine() {}

    public static Validation validate(Map<String, String> sources, ScriptConstants constants,
                                      BooleanSupplier cancelled, long maxInstructions)
                                                                                       throws ScriptValidationException {
        Execution execution = null;
        try {
            execution = new Execution(cancelled, maxInstructions);
            execution.check();
            Map<String, String> captured = capture(sources);
            execution.check();
            return execution.run(captured, constants);
        } catch (Stop stop) {
            execution.rethrowSupplier();
            throw stop.failure;
        } catch (SupplierFailure failure) {
            execution.rethrowSupplier();
            throw failure;
        } catch (RuntimeException | Error failure) {
            if (execution != null) {
                execution.rethrowSupplier();
            }
            throw failure;
        }
    }

    private static Map<String, String> capture(Map<String, String> sources)
                                                                            throws ScriptValidationException {
        if (sources.isEmpty() || sources.size() > 256) {
            throw problem(Reason.SOURCE_LIMIT, "", "Source count must be 1..256");
        }
        long remaining = 8_388_608;
        int sourcesRemaining = 256;
        Map<String, String> captured = new TreeMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            if (sourcesRemaining == 0) {
                throw problem(Reason.SOURCE_LIMIT, "", "Source count exceeds limit");
            }
            sourcesRemaining--;
            String name = Objects.requireNonNull(entry.getKey(), "module name");
            String source = Objects.requireNonNull(entry.getValue(), "source text");
            if (!validName(name)) {
                throw problem(Reason.INVALID_NAME, name, "Invalid module name");
            }
            if (source.length() > 1_048_576 || source.length() > remaining) {
                throw problem(Reason.SOURCE_LIMIT, name, "Source text exceeds length budget");
            }
            remaining -= source.length();
            if (source.isBlank()) {
                throw problem(Reason.INVALID_SOURCE, name, "Source text is empty");
            }
            // Keep the exact checked immutable String, never reread the caller map.
            captured.put(name, source);
        }
        if (captured.isEmpty()) {
            throw problem(Reason.SOURCE_LIMIT, "", "No source entries");
        }
        return Collections.unmodifiableMap(captured);
    }

    private static boolean validName(String name) {
        if (name.length() > 256) {
            return false;
        }
        Matcher matcher = MODULE_NAME.matcher(name);
        if (!matcher.matches()) {
            return false;
        }
        for (String segment : matcher.group(2).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static ScriptValidationException problem(Reason reason, String module, String detail) {
        return new ScriptValidationException(reason, module, detail);
    }

    private static final class Execution {

        private final BooleanSupplier cancelled;
        private final long limit;
        private long remaining;
        private boolean cancellationObserved;
        private Throwable supplierFailure;
        private ScriptValidationException failure;
        private Map<String, String> sources;
        private final Map<String, LuaTable> loaded = new LinkedHashMap<>();
        private final Map<String, Set<String>> edges = new TreeMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private int edgesRemaining = 4096;
        private Globals globals;

        private Execution(BooleanSupplier cancelled, long limit) {
            this.cancelled = cancelled;
            this.limit = limit;
            this.remaining = limit;
        }

        private Validation run(Map<String, String> sources, ScriptConstants constants) {
            this.sources = sources;
            sources.keySet().forEach(name -> edges.put(name, new TreeSet<>()));
            globals = new Globals();
            Globals base = new Globals();
            new BaseLib().call(LuaValue.NIL, base);
            base.running = globals.running;
            for (String function : PURE_FUNCTIONS) {
                globals.rawset(function, base.rawget(function));
            }
            globals.rawset("_G", globals);
            globals.rawset("_VERSION", base.rawget("_VERSION"));
            constants.text().forEach((name, value) -> globals.rawset(name, LuaValue.valueOf(value)));
            constants.integers().forEach((name, value) -> globals.rawset(name, LuaValue.valueOf(value)));
            globals.rawset("require", new OneArgFunction() {

                @Override
                public LuaValue call(LuaValue argument) {
                    return require(argument);
                }
            });
            PrivateCompiler compiler = new PrivateCompiler();
            globals.compiler = compiler;
            globals.loader = compiler;
            globals.debuglib = new DebugLib() {

                @Override
                public void onCall(LuaFunction function) {}

                @Override
                public void onCall(LuaClosure closure, Varargs arguments, LuaValue[] values) {}

                @Override
                public void onReturn() {}

                @Override
                public CallFrame getCallFrame(int level) {
                    return null;
                }

                @Override
                public String traceback(int level) {
                    return "";
                }

                @Override
                public void onInstruction(int pc, Varargs values, int top) {
                    check();
                    if (remaining == 0) {
                        stop(Reason.INSTRUCTION_LIMIT, current(), "Instruction budget exceeded");
                    }
                    remaining--;
                }
            };
            for (String module : sources.keySet()) {
                check();
                load(module);
                check();
            }
            check();
            Validation result = new Validation(sources.keySet(), edges, limit - remaining);
            check();
            return result;
        }

        private LuaValue require(LuaValue argument) {
            check();
            if (argument.type() != LuaValue.TSTRING) {
                throw failed(Reason.INVALID_NAME, current(), "Require expects a module name string");
            }
            String module = argument.tojstring();
            if (!validName(module)) {
                throw failed(Reason.INVALID_NAME, current(), "Invalid required module name");
            }
            Set<String> outgoing = edges.get(current());
            if (!outgoing.contains(module)) {
                if (edgesRemaining == 0) {
                    throw failed(Reason.SOURCE_LIMIT, current(), "Require edge budget exceeded");
                }
                edgesRemaining--;
                outgoing.add(module);
            }
            LuaTable result = load(module);
            check();
            return result;
        }

        private LuaTable load(String module) {
            check();
            LuaTable cached = loaded.get(module);
            if (cached != null) {
                return cached;
            }
            if (!sources.containsKey(module)) {
                throw failed(Reason.UNKNOWN_MODULE, module, "Required module is absent from fixed sources");
            }
            if (stack.contains(module)) {
                throw failed(Reason.CYCLIC_REQUIRE, module, "Cyclic require dependency");
            }
            if (stack.size() >= 64) {
                throw failed(Reason.SOURCE_LIMIT, module, "Require depth budget exceeded");
            }
            stack.addLast(module);
            try {
                // Reader overload uses this exact Globals as the closure environment.
                LuaValue result = globals.load(new StringReader(sources.get(module)), module).call();
                check();
                if (!result.istable()) {
                    throw failed(Reason.INVALID_RESULT, module, "Module must return a table");
                }
                LuaTable table = result.checktable();
                loaded.put(module, table);
                return table;
            } catch (LuaError error) {
                rethrowSupplier();
                // Never retain LuaError: its message/cause may contain source or live Lua state.
                failed(Reason.SCRIPT_ERROR, module, "Module compilation or top-level execution failed");
                throw new Stop(failure);
            } finally {
                stack.removeLast();
            }
        }

        private String current() {
            return stack.isEmpty() ? "" : stack.getLast();
        }

        private void check() {
            if (supplierFailure != null) {
                throw new SupplierFailure();
            }
            if (failure != null) {
                throw new Stop(failure);
            }
            if (!cancellationObserved) {
                try {
                    cancellationObserved = cancelled.getAsBoolean();
                } catch (RuntimeException | Error exception) {
                    // errorHook can catch Throwable: preserve identity even if the bridge is swallowed.
                    supplierFailure = exception;
                    throw new SupplierFailure();
                }
            }
            if (cancellationObserved) {
                stop(Reason.CANCELLED, current(), "Script validation cancelled");
            }
        }

        private void rethrowSupplier() {
            if (supplierFailure instanceof RuntimeException exception) {
                throw exception;
            }
            if (supplierFailure instanceof Error error) {
                throw error;
            }
        }

        private LuaError failed(Reason reason, String module, String detail) {
            if (failure == null) {
                failure = problem(reason, module, detail);
            }
            return new LuaError("Pinned script content validation failed");
        }

        private void stop(Reason reason, String module, String detail) {
            if (failure == null) {
                failure = problem(reason, module, detail);
            }
            throw new Stop(failure);
        }
    }

    private static final class PrivateCompiler extends LuaC {

        private PrivateCompiler() {}
    }

    /** Not catchable by Lua BaseLib pcall/xpcall (which catch Exception). */
    private static final class Stop extends Error {

        private static final long serialVersionUID = 1L;
        private final ScriptValidationException failure;

        private Stop(ScriptValidationException failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
    }

    private static final class SupplierFailure extends Error {

        private static final long serialVersionUID = 1L;

        private SupplierFailure() {
            super(null, null, false, false);
        }
    }
}
