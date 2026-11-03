package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;

/** Explicit development module only. Dispatches input callbacks; never calls gameplay services. */
final class GameplayInputProbe {
    /** Owned by the client thread. Only cancellation and the result cross threads. */
    private static Session active;

    private GameplayInputProbe() {}

    static boolean isActive() { return active != null; }

    static void tick(boolean enabled) {
        if (active != null) active.tick(enabled);
    }

    static JsonObject execute(ViewportAgentBridge.ActiveRun context, JsonObject args) throws Exception {
        require(args.keySet().equals(Set.of("action", "key", "ticks")), "game_input_requires_action_key_ticks_only");
        require(args.get("key").isJsonPrimitive() && args.getAsJsonPrimitive("key").isString(), "game_input_key_string_required");
        String key = args.get("key").getAsString();
        require(Set.of("B", "E", "use").contains(key), "game_input_key_must_be_B_E_or_use");
        require(args.get("ticks").isJsonPrimitive() && args.getAsJsonPrimitive("ticks").isNumber(), "game_input_integer_ticks_required");
        int ticks;
        try { ticks = args.get("ticks").getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("game_input_integer_ticks_required", invalid); }
        require(ticks >= 1 && ticks <= 240, "game_input_ticks_must_be_1_to_240");
        Minecraft minecraft = Minecraft.getInstance();
        require(!minecraft.isSameThread(), "game_input_requires_module_worker_thread");
        Session session = new Session(context, key, ticks);
        context.checkActive();
        minecraft.execute(() -> {
            if (session.cancelReason != null) { session.finish(false, session.cancelReason); return; }
            if (ViewportCameraProbe.anyInputProbeActive()) { session.finish(false, "another_viewport_probe_is_active"); return; }
            active = session;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        try {
            while (true) {
                try { return session.result.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) {
                    context.checkActive();
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("game_input_real_tick_timeout");
                }
            }
        } finally {
            if (!session.result.isDone()) {
                session.cancelReason = "module_cancelled_or_timed_out";
                minecraft.execute(() -> session.finish(false, session.cancelReason));
                // Cancellation can interrupt the worker. Still allow the client to acknowledge
                // release before this worker exits, without blocking the client or advancing ticks.
                boolean interrupted = Thread.interrupted();
                try { session.result.get(5, TimeUnit.SECONDS); }
                catch (InterruptedException interruptedAgain) { interrupted = true; }
                catch (Exception cleanupUnavailable) {
                    System.err.println("Q82_GAME_INPUT_RELEASE_PENDING " + cleanupUnavailable);
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    private static final class Session {
        private final String key;
        private final ViewportAgentBridge.ActiveRun context;
        private final int requestedTicks;
        private final Minecraft minecraft = Minecraft.getInstance();
        private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        private final JsonArray samples = new JsonArray();
        private volatile String cancelReason;
        private ClientLevel level;
        private LocalPlayer player;
        private ClientPacketListener connection;
        private Method mousePress;
        private MouseHandler mouse;
        private KeyboardHandler keyboard;
        private List<KeyMapping> ownedMappings = List.of();
        private Field mappingDown, mappingClicks, rightPressed, mouseActiveButton, mouseClickDepth, dismantleLatch;
        private Object dismantleEdge;
        private long window;
        private String startScreen = "not_started";
        private int realTicks, heldTicks, focusWaitTicks;
        private boolean prepared, focusRequested, started, inputDown, pressDispatched, releaseDispatched;
        private boolean detachedCleanup, nativeReleaseAttempted, localCleanupAttempted, localCleanupSucceeded;

        private Session(ViewportAgentBridge.ActiveRun context, String key, int requestedTicks) {
            this.context = context;
            this.key = key;
            this.requestedTicks = requestedTicks;
        }

        private void tick(boolean enabled) {
            if (result.isDone()) return;
            realTicks++;
            try {
                require(cancelReason == null, cancelReason == null ? "" : cancelReason);
                context.checkClientActive();
                require(enabled, "game_input_probe_flags_revoked");
                require(minecraft.isSameThread(), "game_input_not_on_client_thread");
                require(minecraft.getOverlay() == null, "game_input_overlay_took_input");
                if (!started) {
                    require(minecraft.screen == null, "game_input_gameplay_without_screen_required");
                    if (!prepared) {
                        level = minecraft.level;
                        player = minecraft.player;
                        connection = minecraft.getConnection();
                        require(level != null && player != null && connection != null, "game_input_ready_world_required");
                        window = minecraft.getWindow().getWindow();
                        mouse = minecraft.mouseHandler;
                        keyboard = minecraft.keyboardHandler;
                        startScreen = screenName();
                        prepared = true;
                    }
                    require(sameConnectedSession(), "game_input_world_disconnected_or_changed");
                    if (!actualFocus()) {
                        if (!focusRequested) {
                            GLFW.glfwFocusWindow(window);
                            focusRequested = true;
                        }
                        require(++focusWaitTicks <= 20, "game_input_actual_window_focus_timeout");
                        return; // Observe real focus on a subsequent actual client tick.
                    }
                    if (key.equals("use")) {
                        // The agent control lease releases cursor capture each tick. Vanilla
                        // onPress still updates right-button/keyUse state without capture;
                        // verify that actual state after dispatch and throughout the hold.
                        require(minecraft.options.keyUse.matchesMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT), "game_input_use_requires_right_button_binding");
                        require(!minecraft.options.keyUse.isDown() && !minecraft.mouseHandler.isRightPressed()
                                && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_RELEASE,
                                "game_input_use_already_down");
                        mousePress = mousePressMethod();
                    } else require(!InputConstants.isKeyDown(window, keyCode()) && !matchingMappingDown(), "game_input_key_already_down");
                    prepareOwnedInput();
                    started = true;
                    sample("before_press");
                    context.runClientInput(() -> {
                        inputDown = true; // Cleanup even if the callback throws after partially handling input.
                        dispatch(GLFW.GLFW_PRESS);
                        pressDispatched = true;
                    });
                    sample("after_press");
                    require(!key.equals("use") || useInputDown(), "game_input_use_press_blocked_by_native_input_path");
                    return; // Hold across the next requested number of actual client ticks.
                }
                require(sameConnectedSession(), "game_input_world_disconnected_or_changed");
                require(actualFocus(), "game_input_actual_window_focus_lost");
                require(!key.equals("use") || useInputDown(), "game_input_use_released_before_requested_ticks");
                // B normally opens a shop. E/use must stop if another screen intercepts input.
                require(key.equals("B") || minecraft.screen == null, "game_input_screen_changed");
                heldTicks++;
                if (heldTicks >= requestedTicks) finish(true, "observed_no_gameplay_outcome_assertion");
            } catch (Exception failure) { finish(false, failure.toString()); }
        }

        private int keyCode() { return key.equals("B") ? GLFW.GLFW_KEY_B : GLFW.GLFW_KEY_E; }

        private boolean useInputDown() {
            return mouse.isRightPressed() && minecraft.options.keyUse.isDown();
        }

        private boolean actualFocus() {
            return minecraft.isWindowActive()
                    && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        }

        private boolean sameConnectedSession() {
            return prepared && minecraft.level == level && minecraft.player == player
                    && minecraft.getConnection() == connection && connection.getConnection().isConnected()
                    && minecraft.keyboardHandler == keyboard && minecraft.mouseHandler == mouse
                    && minecraft.getWindow().getWindow() == window;
        }

        private boolean matchesInput(KeyMapping mapping) {
            return key.equals("use") ? mapping.matchesMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT) : mapping.matches(keyCode(), 0);
        }

        private void prepareOwnedInput() {
            try {
                ownedMappings = Arrays.stream(minecraft.options.keyMappings).filter(this::matchesInput).toList();
                mappingDown = inputField(KeyMapping.class, boolean.class, "isDown", "f_90817_");
                mappingClicks = inputField(KeyMapping.class, int.class, "clickCount", "f_90818_");
                for (KeyMapping mapping : ownedMappings) {
                    require(!mappingDown.getBoolean(mapping) && mappingClicks.getInt(mapping) == 0,
                            "game_input_mapping_already_down_or_has_pending_clicks");
                }
                if (key.equals("use")) {
                    rightPressed = inputField(MouseHandler.class, boolean.class, "isRightPressed", "f_91506_");
                    mouseActiveButton = inputField(MouseHandler.class, int.class, "activeButton", "f_91510_");
                    mouseClickDepth = inputField(MouseHandler.class, int.class, "clickDepth", "f_91512_");
                    require(!rightPressed.getBoolean(mouse), "game_input_right_button_already_down");
                    require(mouseActiveButton.getInt(mouse) == -1 && mouseClickDepth.getInt(mouse) == 0,
                            "game_input_mouse_callback_already_active");
                }
                if (key.equals("E")) {
                    // Only the BO input edge is touched; never a bomb, player, map, or packet.
                    Class<?> owner = Class.forName("net.ptcrys.blockoffensive.client.key.DismantleBombKey");
                    Field edge = owner.getDeclaredField("INPUT_EDGE");
                    edge.setAccessible(true);
                    dismantleEdge = edge.get(null);
                    dismantleLatch = inputField(dismantleEdge.getClass(), boolean.class, "latched");
                    require(!dismantleLatch.getBoolean(dismantleEdge), "game_input_dismantle_edge_already_latched");
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("game_input_local_cleanup_fields_unavailable", failure);
            }
        }

        /** No callbacks: the captured objects may belong to a disconnected world. */
        private void clearOwnedLocalInput() {
            localCleanupAttempted = true;
            RuntimeException failures = null;
            for (KeyMapping mapping : ownedMappings) {
                try {
                    mappingDown.setBoolean(mapping, false);
                    mappingClicks.setInt(mapping, 0);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    if (failures == null) failures = new IllegalStateException("game_input_local_mapping_cleanup_failed");
                    failures.addSuppressed(failure);
                }
            }
            try {
                if (rightPressed != null) {
                    rightPressed.setBoolean(mouse, false);
                    if (mouseActiveButton.getInt(mouse) == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                        mouseActiveButton.setInt(mouse, -1);
                        mouseClickDepth.setInt(mouse, 0);
                    }
                }
            }
            catch (ReflectiveOperationException | RuntimeException failure) {
                if (failures == null) failures = new IllegalStateException("game_input_local_mouse_cleanup_failed");
                failures.addSuppressed(failure);
            }
            try { if (dismantleLatch != null) dismantleLatch.setBoolean(dismantleEdge, false); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                if (failures == null) failures = new IllegalStateException("game_input_local_edge_cleanup_failed");
                failures.addSuppressed(failure);
            }
            try {
                for (KeyMapping mapping : ownedMappings)
                    require(!mappingDown.getBoolean(mapping) && mappingClicks.getInt(mapping) == 0, "game_input_mapping_not_cleared");
                require(rightPressed == null || !rightPressed.getBoolean(mouse), "game_input_mouse_not_cleared");
                require(mouseActiveButton == null || mouseActiveButton.getInt(mouse) != GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                        "game_input_mouse_callback_not_cleared");
                require(dismantleLatch == null || !dismantleLatch.getBoolean(dismantleEdge), "game_input_edge_not_cleared");
            } catch (ReflectiveOperationException | RuntimeException failure) {
                if (failures == null) failures = new IllegalStateException("game_input_local_cleanup_verification_failed");
                failures.addSuppressed(failure);
            }
            localCleanupSucceeded = failures == null;
            if (localCleanupSucceeded) inputDown = false;
            if (failures != null) throw failures;
        }

        private void dispatch(int action) {
            if (!key.equals("use")) {
                keyboard.keyPress(window, keyCode(), 0, action, 0);
                return;
            }
            try { mousePress.invoke(mouse, window, GLFW.GLFW_MOUSE_BUTTON_RIGHT, action, 0); }
            catch (IllegalAccessException | InvocationTargetException failure) {
                Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
                throw new IllegalStateException("game_input_mouse_callback_failed", cause);
            }
        }

        private void finish(boolean ok, String reason) {
            if (result.isDone()) return;
            try {
                if (inputDown) {
                    sample("before_release");
                    detachedCleanup = !sameConnectedSession();
                    if (!detachedCleanup) {
                        nativeReleaseAttempted = true;
                        dispatch(GLFW.GLFW_RELEASE);
                        releaseDispatched = true;
                    }
                }
            } catch (RuntimeException failure) {
                ok = false;
                reason += "; release_failed:" + failure;
            } finally {
                if (inputDown) {
                    try { clearOwnedLocalInput(); }
                    catch (RuntimeException failure) {
                        ok = false;
                        reason += "; local_input_cleanup_failed:" + failure;
                    }
                }
                sample("after_release");
                JsonObject report = new JsonObject();
                report.addProperty("ok", ok);
                report.addProperty("status", ok ? "done" : "failed");
                report.addProperty("reason", reason);
                report.addProperty("action", "game_input");
                report.addProperty("key", key);
                report.addProperty("requested_hold_ticks", requestedTicks);
                report.addProperty("real_client_ticks", realTicks);
                report.addProperty("held_client_ticks", heldTicks);
                report.addProperty("focus_requested", focusRequested);
                report.addProperty("focus_wait_ticks", focusWaitTicks);
                report.addProperty("start_screen", startScreen);
                report.addProperty("end_screen", screenName());
                report.addProperty("press_dispatched", pressDispatched);
                report.addProperty("release_dispatched", releaseDispatched);
                report.addProperty("inputs_released", !inputDown);
                report.addProperty("detached_cleanup", detachedCleanup);
                report.addProperty("native_release_attempted", nativeReleaseAttempted);
                report.addProperty("native_release_dispatched", releaseDispatched);
                report.addProperty("local_input_cleanup_attempted", localCleanupAttempted);
                report.addProperty("local_input_cleanup_verified", localCleanupSucceeded);
                report.addProperty("owned_mapping_count", ownedMappings.size());
                report.addProperty("cleanup_executed", true);
                report.addProperty("worker_finally_requested_cleanup", cancelReason != null);
                report.addProperty("native_keyboard_handler", !key.equals("use"));
                report.addProperty("native_mouse_handler", key.equals("use"));
                report.addProperty("manual_tick", false);
                report.addProperty("global_desktop_input", false);
                report.addProperty("direct_gameplay_packet", false);
                report.addProperty("direct_gameplay_state_write", false);
                report.addProperty("agent_registry_loader", ViewportAgentBridge.registrationIdentity());
                report.add("samples", samples);
                if (active == this) active = null;
                System.out.println("Q82_GAME_INPUT_RESULT " + report);
                result.complete(report);
            }
        }

        private String screenName() { return minecraft.screen == null ? "none" : minecraft.screen.getClass().getName(); }

        private boolean matchingMappingDown() {
            return Arrays.stream(minecraft.options.keyMappings)
                    .anyMatch(mapping -> matchesInput(mapping) && mapping.isDown());
        }

        private void sample(String name) {
            JsonObject sample = new JsonObject();
            sample.addProperty("name", name);
            sample.addProperty("client_tick", realTicks);
            sample.addProperty("held_client_ticks", heldTicks);
            sample.addProperty("screen", screenName());
            sample.addProperty("input_down", inputDown);
            sample.addProperty("actual_window_active", minecraft.isWindowActive());
            sample.addProperty("world_present", minecraft.level != null);
            if (minecraft.level != null) sample.addProperty("client_game_time", minecraft.level.getGameTime());
            sample.addProperty("use_mapping_down", minecraft.options.keyUse.isDown());
            sample.addProperty("right_button_down", minecraft.mouseHandler.isRightPressed());
            sample.addProperty("matching_mapping_down", matchingMappingDown());
            sample.addProperty("captured_right_button_down", mouse != null && mouse.isRightPressed());
            samples.add(sample);
        }
    }

    private static Method mousePressMethod() {
        // The vanilla callback is private; accept only its exact mapped 1.20.1 names/signature.
        for (String name : new String[]{"onPress", "m_91530_"}) {
            try {
                Method method = MouseHandler.class.getDeclaredMethod(name, long.class, int.class, int.class, int.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) { /* Try the production SRG name. */ }
        }
        throw new IllegalStateException("game_input_native_mouse_callback_unavailable");
    }

    private static Field inputField(Class<?> owner, Class<?> type, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                require(field.getType() == type, "game_input_input_field_type_changed:" + name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) { /* Only the explicitly listed mapped names. */ }
        }
        throw new NoSuchFieldException(owner.getName() + ":" + String.join("/", names));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
