package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/**
 * Development-only bridge to the already-running packaged agent's child class loader.
 * The agent's exact bootstrap owns its private loader list under the bootstrap class monitor.
 * There is no public loader getter in this runtime. Reading that one field is deliberately
 * narrow and fails closed if the packaged runtime changes; no rail classes are copied or
 * loaded into the Minecraft parent loader, and no new class loader is created here.
 */
final class ViewportAgentBridge {
    private static volatile String registrationIdentity = "not_registered";

    private ViewportAgentBridge() {}

    @FunctionalInterface
    interface ActiveRun {
        void checkActive() throws Exception;
        default void checkClientActive() throws Exception {
            throw new IllegalStateException("client_active_check_unavailable");
        }
        default void runClientInput(Runnable input) throws Exception {
            checkClientActive();
            input.run();
        }
    }

    @FunctionalInterface
    interface Executor { JsonObject execute(ActiveRun run, JsonObject args) throws Exception; }

    static String registrationIdentity() { return registrationIdentity; }

    static void registerAsync(String id, JsonObject schema, Executor executor) {
        // Runtime startup holds UniversalBootstrap.class while constructing its entrypoint.
        // Do not wait on that monitor from the client thread: startup may need that thread.
        Thread registration = new Thread(() -> {
            try {
                // The full rail's delayed entrypoint allows 8 seconds before checking client
                // readiness and then up to 120 seconds to initialize its singleton.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
                Class<?> bootstrap = Class.forName("top.shmfun.mcagent.bootstrap.UniversalBootstrap", false,
                        ClassLoader.getSystemClassLoader());
                Field field = bootstrap.getDeclaredField("RUNTIME_CLASS_LOADERS");
                field.setAccessible(true);
                while (enabled()) {
                    List<ClassLoader> candidates = new ArrayList<>();
                    synchronized (bootstrap) {
                        Object registeredLoaders = field.get(null);
                        if (!(registeredLoaders instanceof List<?> loaders))
                            throw new IllegalStateException("agent_runtime_loader_list_shape_changed");
                        for (Object value : loaders) {
                            if (!(value instanceof ClassLoader loader))
                                throw new IllegalStateException("agent_runtime_loader_entry_shape_changed");
                            candidates.add(loader);
                        }
                    }
                    ClassLoader selected = null;
                    boolean fullRuntimePending = false;
                    boolean minimalRuntimeSeen = false;
                    List<String> unavailable = new ArrayList<>();
                    for (ClassLoader loader : candidates) {
                        Class<?> runtime;
                        try {
                            if (Class.forName(Minecraft.class.getName(), false, loader) != Minecraft.class) continue;
                            runtime = Class.forName("top.shmfun.mcagent.client.ClientRuntime", false, loader);
                        } catch (ClassNotFoundException unsupported) {
                            // The Java-agent default mc119_120 fallback rail intentionally has
                            // no ClientRuntime or public OperationModule registry. It must not
                            // abort discovery of another already-started full-rail candidate.
                            boolean minimal = loader.getResource("top/shmfun/mcagent/rail/runtime/MinimalRailRuntime.class") != null;
                            minimalRuntimeSeen |= minimal;
                            unavailable.add(loaderIdentity(loader) + (minimal ? ":minimal_rail_without_operation_module_api"
                                    : ":missing_" + unsupported.getMessage()));
                            continue;
                        }
                        if (runtime.getClassLoader() != loader) {
                            unavailable.add(loaderIdentity(loader) + ":runtime_owned_by_another_loader");
                            continue;
                        }
                        if (invoke(runtime.getMethod("tryGet"), null) == null) {
                            fullRuntimePending = true;
                            continue;
                        }
                        if (selected != null && selected != loader)
                            throw new IllegalStateException("multiple_active_agent_runtime_loaders");
                        selected = loader;
                    }
                    if (selected != null) {
                        register(selected, id, schema, executor);
                        return;
                    }
                    if (minimalRuntimeSeen && !fullRuntimePending)
                        throw new IllegalStateException("loaded_minimal_rail_has_no_operation_module_api; "
                                + "restart_this_explicit_probe_run_with_-Dmcagent.forceLegacyForgeFullRail=true; "
                                + "candidates=" + unavailable);
                    if (System.nanoTime() >= deadline)
                        throw new IllegalStateException("active_agent_full_runtime_loader_timeout; candidates=" + unavailable);
                    Thread.sleep(1000);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                registrationIdentity = "failed:" + failure.getClass().getSimpleName();
                System.err.println("Q82_VIEWPORT_PROBE_REGISTRATION_FAILED " + failure);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                registrationIdentity = "failed:" + failure.getClass().getSimpleName();
                System.err.println("Q82_VIEWPORT_PROBE_REGISTRATION_FAILED " + failure);
            }
        }, "fpsmatch-viewport-probe-agent-registration");
        registration.setDaemon(true);
        registration.start();
    }

    private static void register(ClassLoader loader, String id, JsonObject schema, Executor executor) throws Exception {
        if (!enabled()) throw new IllegalStateException("viewport_probe_flags_revoked_before_registration");
        Class<?> api = Class.forName("top.shmfun.mcagent.api.McAgentApi", true, loader);
        Class<?> module = Class.forName("top.shmfun.mcagent.api.OperationModule", false, loader);
        Class<?> descriptor = Class.forName("top.shmfun.mcagent.api.OperationModuleDescriptor", false, loader);
        Class<?> context = Class.forName("top.shmfun.mcagent.api.OperationModuleContext", false, loader);
        if (api.getClassLoader() != loader || module.getClassLoader() != loader
                || descriptor.getClassLoader() != loader || context.getClassLoader() != loader
                || Class.forName(JsonObject.class.getName(), false, loader) != JsonObject.class)
            throw new IllegalStateException("agent_api_class_identity_mismatch");
        Method guiScene = context.getMethod("guiScene");
        Object description = descriptor.getConstructor(String.class, String.class, String.class, boolean.class,
                String.class, JsonObject.class, List.class).newInstance(id, "fpsmatch",
                "Development-only Screen/keyboard events, retained state observations and isolated native runtime fault injection; camera actions require an active window and a ready viewport.",
                true, "normal", schema, List.of("gui_scene"));
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{module}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "ViewportCameraProbe[" + id + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            return switch (method.getName()) {
                case "descriptor" -> description;
                case "execute" -> {
                    if (!enabled()) throw new IllegalStateException("viewport_probe_flags_revoked");
                    Object liveContext = args[0];
                    if (!context.isInstance(liveContext) || !(args[1] instanceof JsonObject payload))
                        throw new IllegalStateException("agent_execute_argument_identity_mismatch");
                    ActiveRun activeRun;
                    String action = payload.has("action") ? payload.get("action").getAsString() : "";
                    if (List.of("game_input", "ui_resize", "ui_gesture", "ui_focus", "ui_focus_snapshot",
                            "ui_tab", "ui_backtab", "ui_enter", "ui_reload", "text_snapshot", "text_undo", "text_redo").contains(action)) {
                        Object run = exactOperationRun(loader, context, liveContext);
                        Method ensureActive = run.getClass().getDeclaredMethod("ensureActive", String.class);
                        ensureActive.setAccessible(true);
                        activeRun = new ActiveRun() {
                            public void checkActive() throws Exception { invoke(guiScene, liveContext); }
                            public void checkClientActive() throws Exception { invoke(ensureActive, run, "external_module." + action); }
                            public void runClientInput(Runnable input) throws Exception {
                                synchronized (run) {
                                    invoke(ensureActive, run, "external_module." + action);
                                    input.run();
                                }
                            }
                        };
                    } else activeRun = () -> invoke(guiScene, liveContext);
                    yield executor.execute(activeRun, payload);
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        invoke(api.getMethod("registerOperationModule", module), null, proxy);
        Object modules = invoke(api.getMethod("operationModules"), null);
        if (!(modules instanceof List<?> list) || list.stream().noneMatch(value -> value == proxy))
            throw new IllegalStateException("module_absent_from_selected_runtime_registry");
        registrationIdentity = loaderIdentity(loader);
        System.out.println("Q82_VIEWPORT_PROBE_REGISTERED module=" + id + " runtime_loader=" + registrationIdentity);
    }

    /** Read the existing run only; ensureActive is synchronized and never queues client work. */
    private static Object exactOperationRun(ClassLoader loader, Class<?> context, Object liveContext) throws Exception {
        Class<?> scoped = Class.forName("top.shmfun.mcagent.control.module.ScopedOperationModuleContext", false, loader);
        Class<?> service = Class.forName("top.shmfun.mcagent.control.module.OperationModuleService$ServiceOperationModuleContext", false, loader);
        Class<?> runType = Class.forName("top.shmfun.mcagent.control.module.OperationRun", false, loader);
        if (scoped.getClassLoader() != loader || service.getClassLoader() != loader || runType.getClassLoader() != loader
                || liveContext.getClass() != scoped) throw new IllegalStateException("agent_run_context_identity_mismatch");
        Field delegate = scoped.getDeclaredField("delegate");
        if (delegate.getType() != context) throw new IllegalStateException("agent_context_delegate_type_changed");
        delegate.setAccessible(true);
        Object serviceContext = delegate.get(liveContext);
        if (serviceContext == null || serviceContext.getClass() != service)
            throw new IllegalStateException("agent_service_context_identity_mismatch");
        Field run = service.getDeclaredField("run");
        if (run.getType() != runType) throw new IllegalStateException("agent_operation_run_type_changed");
        run.setAccessible(true);
        Object value = run.get(serviceContext);
        if (value == null || value.getClass() != runType) throw new IllegalStateException("agent_operation_run_identity_mismatch");
        return value;
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof Exception failure) throw failure;
            if (cause instanceof Error failure) throw failure;
            throw new IllegalStateException("agent_invocation_failed", cause);
        }
    }

    private static boolean enabled() {
        return Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode");
    }

    private static String loaderIdentity(ClassLoader loader) {
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
