package net.ptcrys.fpsmatch.editor.common.backend.tacz.script;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/** Checks one fixed execution domain without exposing or sharing LuaJ state. */
public final class PinnedScriptValidator {

    private static final String ENGINE = "net.ptcrys.fpsmatch.editor.common.backend.tacz.script.internal.IsolatedScriptEngine";
    private static final Set<String> RESERVED = Set.of(
            "_G", "_VERSION", "require", "assert", "error", "pcall", "xpcall", "type", "tostring",
            "tonumber", "select", "next", "pairs", "ipairs", "rawequal", "rawget", "rawset",
            "rawlen", "setmetatable", "getmetatable");
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private PinnedScriptValidator() {}

    public static Validation validate(Map<String, String> sources, ScriptConstants constants,
                                      BooleanSupplier cancelled, long maxInstructions)
                                                                                       throws ScriptValidationException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(constants, "constants");
        Objects.requireNonNull(cancelled, "cancelled");
        if (maxInstructions < 1 || maxInstructions > 10_000_000) {
            throw new IllegalArgumentException("Instruction budget must be 1..10000000");
        }
        if (Boolean.TRUE.equals(ACTIVE.get())) {
            throw new IllegalStateException("Script validation cannot be reentered on the same thread");
        }
        Throwable[] supplierFailure = new Throwable[1];
        ACTIVE.set(Boolean.TRUE);
        try {
            BooleanSupplier trackedCancellation = () -> {
                try {
                    return cancelled.getAsBoolean();
                } catch (RuntimeException | Error failure) {
                    supplierFailure[0] = failure;
                    throw failure;
                }
            };
            ClassLoader loader = new IsolatedLoader(PinnedScriptValidator.class.getClassLoader());
            Class<?> engine = loader.loadClass(ENGINE);
            Method entry = engine.getMethod("validate", Map.class, ScriptConstants.class,
                    BooleanSupplier.class, long.class);
            Validation result = (Validation) entry.invoke(null, sources, constants,
                    trackedCancellation, maxInstructions);
            rethrowSupplier(supplierFailure[0]);
            return result;
        } catch (InvocationTargetException exception) {
            rethrowSupplier(supplierFailure[0]);
            Throwable cause = exception.getCause();
            if (cause instanceof ScriptValidationException failure) {
                // Recreate in the parent class space: Throwable's hidden backtrace can retain classes.
                throw new ScriptValidationException(failure.reason(), failure.moduleName(),
                        failure.getMessage());
            }
            if (cause instanceof LinkageError) {
                throw unavailable();
            }
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw unavailable();
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            rethrowSupplier(supplierFailure[0]);
            throw unavailable();
        } finally {
            ACTIVE.remove();
        }
    }

    /** Immutable Java-only result, with lexically stable iteration order. */
    public record Validation(Set<String> modules, Map<String, Set<String>> requires,
                             long instructions) {

        public Validation {
            Objects.requireNonNull(modules, "modules");
            Objects.requireNonNull(requires, "requires");
            modules = Collections.unmodifiableSet(new TreeSet<>(modules));
            Map<String, Set<String>> copy = new TreeMap<>();
            requires.forEach((module, edges) -> copy.put(Objects.requireNonNull(module, "module"),
                    Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(edges, "edges")))));
            requires = Collections.unmodifiableMap(copy);
        }
    }

    static boolean isReserved(String name) {
        return RESERVED.contains(name);
    }

    private static void rethrowSupplier(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static ScriptValidationException unavailable() {
        // Do not retain a reflection exception which could retain an engine or its loader.
        return new ScriptValidationException(ScriptValidationException.Reason.SCRIPT_ERROR, "",
                "Isolated script validation backend is unavailable");
    }

    private static final class IsolatedLoader extends ClassLoader {

        private IsolatedLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    if (name.startsWith("org.luaj.") || name.equals(ENGINE) || name.startsWith(ENGINE + "$")) {
                        result = findClass(name);
                    } else {
                        result = getParent().loadClass(name);
                    }
                }
                if (resolve) {
                    resolveClass(result);
                }
                return result;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream input = getParent().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }
    }
}
