package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Development-only real Screen events across client ticks; never asserts a product outcome. */
final class UiGestureProbe {
    private static Session active;
    private UiGestureProbe() {}
    static boolean isActive() { return active != null; }
    static void tick(boolean enabled) { if (active != null) active.tick(enabled); }

    static JsonObject execute(ViewportAgentBridge.ActiveRun context, JsonObject args) throws Exception {
        require(args.keySet().equals(Set.of("action", "start_x", "start_y", "end_x", "end_y", "ticks", "finish")), "ui_gesture_arguments");
        double sx = coordinate(args, "start_x"), sy = coordinate(args, "start_y");
        double ex = coordinate(args, "end_x"), ey = coordinate(args, "end_y");
        int ticks = args.get("ticks").getAsBigDecimal().intValueExact();
        String finish = args.get("finish").getAsString();
        require(ticks >= 1 && ticks <= 240 && Set.of("release", "escape", "resize", "focus").contains(finish), "ui_gesture_limits");
        Minecraft minecraft = Minecraft.getInstance();
        require(!minecraft.isSameThread(), "ui_gesture_worker_required");
        Session session = new Session(context, sx, sy, ex, ey, ticks, finish);
        context.checkActive();
        minecraft.execute(() -> {
            try {
                require(!session.cancelled && !ViewportCameraProbe.anyInputProbeActive(), "ui_gesture_cancelled_or_busy");
                session.input(() -> active = session);
            } catch (Exception failure) { session.complete(false, failure.toString()); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            while (true) {
                try { return session.result.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) {
                    context.checkActive();
                    require(System.nanoTime() < deadline, "ui_gesture_real_tick_timeout");
                }
            }
        } finally {
            if (!session.result.isDone()) {
                session.cancelLocal();
                minecraft.execute(() -> session.complete(false, "ui_gesture_cancelled"));
                boolean interrupted = Thread.interrupted();
                try { session.result.get(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { interrupted = true; }
                catch (Exception failure) { System.err.println("Q82_UI_GESTURE_RELEASE_PENDING " + failure); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    private static final class Session {
        private final ViewportAgentBridge.ActiveRun context;
        private final double sx, sy, ex, ey;
        private final int holdTicks;
        private final String finish;
        private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        private final Minecraft minecraft = Minecraft.getInstance();
        private volatile boolean cancelled;
        private Screen screen;
        private int ticks, held, width, height;
        private boolean pressed, moved, escaped, released;
        private long window;
        private int targetWidth, targetHeight, inactiveTicks, settleTicks;
        private boolean nativeRequested, nativeObserved, restoreRequested;

        private Session(ViewportAgentBridge.ActiveRun context, double sx, double sy, double ex, double ey, int holdTicks, String finish) {
            this.context = context; this.sx = sx; this.sy = sy; this.ex = ex; this.ey = ey;
            this.holdTicks = holdTicks; this.finish = finish;
        }

        private synchronized void cancelLocal() { cancelled = true; }

        /** Local timeout cancellation and external run cancellation both precede input dispatch. */
        private synchronized void input(Runnable action) throws Exception {
            require(!cancelled && !result.isDone(), "ui_gesture_cancelled_before_input");
            context.runClientInput(action);
        }

        private void tick(boolean enabled) {
            if (result.isDone()) return;
            ticks++;
            try {
                require(enabled && !cancelled, "ui_gesture_inactive");
                require(minecraft.getOverlay() == null, "ui_gesture_overlay_changed");
                context.checkClientActive();
                if (nativeRequested) { observeNativeCancellation(); return; }
                require(minecraft.isWindowActive(), "ui_gesture_window_inactive");
                if (screen == null) {
                    screen = minecraft.screen;
                    require(screen != null && screen.getClass().getName().equals("net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchScreen"), "ui_gesture_workbench_required");
                    width = minecraft.getWindow().getGuiScaledWidth(); height = minecraft.getWindow().getGuiScaledHeight();
                    window = minecraft.getWindow().getWindow();
                    require(sx < width && ex < width && sy < height && ey < height, "ui_gesture_coordinates_outside_window");
                    input(() -> {
                        screen.mouseMoved(sx, sy); pressed = true;
                        require(screen.mouseClicked(sx, sy, GLFW.GLFW_MOUSE_BUTTON_LEFT), "ui_gesture_press_unhandled");
                    });
                } else {
                    require(minecraft.screen == screen && width == minecraft.getWindow().getGuiScaledWidth()
                            && height == minecraft.getWindow().getGuiScaledHeight(), "ui_gesture_screen_or_size_changed");
                    if (!moved) {
                        input(() -> {
                            screen.mouseMoved(ex, ey);
                            screen.mouseDragged(ex, ey, GLFW.GLFW_MOUSE_BUTTON_LEFT, ex - sx, ey - sy);
                            moved = true;
                        });
                    } else if (++held >= holdTicks) {
                        if (finish.equals("resize") || finish.equals("focus")) { requestNativeCancellation(); return; }
                        input(() -> {
                            if (finish.equals("escape")) { screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); escaped = true; }
                            screen.mouseReleased(ex, ey, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                            pressed = false; released = true;
                        });
                        complete(true, "input_sequence_completed_no_product_assertion");
                    }
                }
            } catch (Exception failure) { complete(false, failure.toString()); }
        }

        private void requestNativeCancellation() throws Exception {
            require(!minecraft.getWindow().isFullscreen(), "ui_gesture_native_cancel_requires_windowed_client");
            require(GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE,
                    "ui_gesture_native_cancel_requires_focus");
            targetWidth = minecraft.getWindow().getWidth() == 1280 && minecraft.getWindow().getHeight() == 720 ? 854 : 1280;
            targetHeight = targetWidth == 854 ? 480 : 720;
            input(() -> {
                nativeRequested = true;
                if (finish.equals("focus")) GLFW.glfwIconifyWindow(window);
                else { GLFW.glfwRestoreWindow(window); GLFW.glfwSetWindowSize(window, targetWidth, targetHeight); }
            });
        }

        private void observeNativeCancellation() throws Exception {
            require(minecraft.screen == screen && minecraft.getWindow().getWindow() == window,
                    "ui_gesture_native_cancel_screen_changed");
            if (finish.equals("focus")) {
                boolean focused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
                if (!minecraft.isWindowActive() && !focused) {
                    nativeObserved = true;
                    if (++inactiveTicks >= 3 && !restoreRequested) {
                        input(() -> { restoreRequested = true; restoreNativeWindow(); });
                    }
                }
                if (!nativeObserved || !restoreRequested || !minecraft.isWindowActive() || !focused) return;
            } else {
                int[] nativeWidth = new int[1], nativeHeight = new int[1];
                GLFW.glfwGetWindowSize(window, nativeWidth, nativeHeight);
                if (nativeWidth[0] != targetWidth || nativeHeight[0] != targetHeight
                        || minecraft.getWindow().getWidth() != targetWidth || minecraft.getWindow().getHeight() != targetHeight) return;
                nativeObserved = true;
                require(minecraft.isWindowActive(), "ui_gesture_unexpected_focus_loss_during_resize");
            }
            if (++settleTicks < 3) return;
            // Do not cancel the product gesture ourselves: the real native event must do that.
            input(() -> {
                screen.mouseReleased(ex, ey, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                pressed = false; released = true;
            });
            complete(true, "native_event_observed_and_input_released_no_product_assertion");
        }

        private void restoreNativeWindow() {
            GLFW.glfwRestoreWindow(window); GLFW.glfwFocusWindow(window);
        }

        private synchronized void complete(boolean ok, String reason) {
            if (result.isDone()) return;
            if (cancelled && ok) { ok = false; reason = "ui_gesture_cancelled_before_completion"; }
            cancelled = true;
            try {
                // Release outside all controls on cancellation; never click a late destination.
                if (pressed && screen != null) { screen.mouseReleased(-1, -1, GLFW.GLFW_MOUSE_BUTTON_LEFT); pressed = false; released = true; }
            } catch (RuntimeException failure) { ok = false; reason += ";release_failed:" + failure; }
            try {
                if (nativeRequested && finish.equals("focus") && minecraft.getWindow().getWindow() == window
                        && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) restoreNativeWindow();
            } catch (RuntimeException failure) { ok = false; reason += ";window_restore_failed:" + failure; }
            JsonObject report = new JsonObject();
            report.addProperty("action", "ui_gesture"); report.addProperty("ok", ok); report.addProperty("reason", reason);
            report.addProperty("finish", finish); report.addProperty("real_client_ticks", ticks);
            report.addProperty("held_client_ticks", held); report.addProperty("moved", moved);
            report.addProperty("escape_dispatched", escaped); report.addProperty("release_dispatched", released);
            report.addProperty("inputs_released", !pressed); report.addProperty("manual_tick", false);
            report.addProperty("global_desktop_input", false);
            report.addProperty("native_event_requested", nativeRequested); report.addProperty("native_event_observed", nativeObserved);
            report.addProperty("native_inactive_ticks", inactiveTicks); report.addProperty("native_settle_ticks", settleTicks);
            report.addProperty("native_restore_requested", restoreRequested);
            if (finish.equals("resize")) {
                report.addProperty("requested_width", targetWidth); report.addProperty("requested_height", targetHeight);
                report.addProperty("framebuffer_width", minecraft.getWindow().getWidth());
                report.addProperty("framebuffer_height", minecraft.getWindow().getHeight());
            }
            if (active == this) active = null;
            result.complete(report);
        }
    }

    private static double coordinate(JsonObject args, String key) {
        require(args.get(key).isJsonPrimitive() && args.getAsJsonPrimitive(key).isNumber(), "ui_gesture_numeric_coordinate_required");
        double value = args.get(key).getAsDouble();
        require(Double.isFinite(value) && value >= 0, "ui_gesture_finite_nonnegative_coordinate_required");
        return value;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
